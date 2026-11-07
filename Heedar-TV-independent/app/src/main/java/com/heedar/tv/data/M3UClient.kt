package com.heedar.tv.data

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Minimal IPTV M3U importer. It accepts playable HTTP/HTTPS entries only. */
class M3UClient {
    fun download(playlistUrl: String): String {
        var currentUrl = playlistUrl.trim()
        var redirects = 0
        while (redirects < 5) {
            val parsed = try { URL(currentUrl) } catch (_: Exception) {
                throw IllegalArgumentException("أدخل رابط M3U صحيحاً.")
            }
            require(parsed.protocol == "http" || parsed.protocol == "https") { "يجب أن يبدأ رابط M3U بـ HTTP أو HTTPS." }
            require(!parsed.host.isNullOrBlank()) { "رابط M3U لا يحتوي على اسم خادم صحيح." }
            val connection = (parsed.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/x-mpegURL, audio/x-mpegurl, text/plain, */*")
                setRequestProperty("User-Agent", "HeedarTV/0.10")
            }
            val status = connection.responseCode
            if (status in 301..308) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) break
                currentUrl = if (location.startsWith("http://") || location.startsWith("https://")) location else URL(parsed, location).toString()
                redirects++
                continue
            }
            try {
                if (status !in 200..299) throw IllegalStateException("تعذر تنزيل قائمة M3U (HTTP $status).")
                return readLimited(connection.inputStream)
            } finally {
                connection.disconnect()
            }
        }
        throw IllegalStateException("تجاوزت القائمة الحد الأقصى لإعادة التوجيه.")
    }

    fun readLocal(input: InputStream): String = readLimited(input)

    fun parse(text: String): List<M3UChannel> {
        require(text.isNotBlank()) { "ملف M3U فارغ." }
        require(text.length <= MAX_PLAYLIST_CHARS) { "قائمة M3U أكبر من الحد المسموح (4 ميغابايت نصاً)." }
        val cacheKey = MemoryContentCache.fingerprint("m3u-parsed-v1", listOf(text))
        MemoryContentCache.get<List<M3UChannel>>(cacheKey)?.let { return it }
        val lines = text.removePrefix("\uFEFF").lineSequence().map(String::trim).iterator()
        val channels = mutableListOf<M3UChannel>()
        var pendingTitle: String? = null
        var pendingGroup: String? = null
        var pendingLogo: String? = null
        var pendingTvgId: String? = null
        var sawHeader = false
        while (lines.hasNext()) {
            val line = lines.next()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTM3U", ignoreCase = true)) {
                sawHeader = true
                continue
            }
            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                val comma = commaOutsideQuotes(line)
                val metadata = if (comma >= 0) line.substring(8, comma) else line.substring(8)
                val attrs = ATTRIBUTE.findAll(metadata).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                val title = if (comma >= 0) line.substring(comma + 1).trim() else attrs["tvg-name"].orEmpty()
                pendingTitle = title.ifBlank { attrs["tvg-name"].orEmpty() }.takeIf { it.isNotBlank() }
                pendingGroup = attrs["group-title"]?.trim()?.takeIf(String::isNotBlank)
                pendingLogo = attrs["tvg-logo"]?.validWebUrl()
                pendingTvgId = attrs["tvg-id"]?.trim()?.takeIf(String::isNotBlank)
                continue
            }
            if (line.startsWith("#")) continue
            val streamUrl = try { URL(line) } catch (_: Exception) { null }
            if (streamUrl == null || (streamUrl.protocol != "http" && streamUrl.protocol != "https") || streamUrl.host.isNullOrBlank()) {
                pendingTitle = null
                pendingGroup = null
                pendingLogo = null
                pendingTvgId = null
                continue
            }
            if (channels.size >= MAX_CHANNELS) throw IllegalArgumentException("قائمة M3U تحتوي على أكثر من $MAX_CHANNELS قناة.")
            val index = channels.size + 1
            val safeName = pendingTitle?.take(MAX_NAME_LENGTH)?.takeIf { it.isNotBlank() } ?: "قناة $index"
            channels += M3UChannel(
                id = "m3u_$index",
                name = safeName,
                streamUrl = line,
                groupName = pendingGroup?.take(MAX_NAME_LENGTH),
                logoUrl = pendingLogo,
                tvgId = pendingTvgId?.take(MAX_NAME_LENGTH),
            )
            pendingTitle = null
            pendingGroup = null
            pendingLogo = null
            pendingTvgId = null
        }
        require(sawHeader || channels.isNotEmpty()) { "الملف لا يبدو قائمة M3U صالحة." }
        require(channels.isNotEmpty()) { "لم يتم العثور على قنوات HTTP/HTTPS قابلة للتشغيل في القائمة." }
        val result = channels.toList()
        val strings = result.flatMap { listOf(it.id, it.name, it.streamUrl, it.groupName.orEmpty(), it.logoUrl.orEmpty(), it.tvgId.orEmpty()) }
        MemoryContentCache.put(cacheKey, result, MemoryContentCache.approximateStringListBytes(strings))
        return result
    }

    fun categories(channels: List<M3UChannel>): List<XtreamCategory> =
        channels.mapNotNull { it.groupName?.takeIf(String::isNotBlank) }.distinct().map { name ->
            XtreamCategory(categoryId = categoryId(name), name = name)
        }

    fun categoryId(name: String): String = MessageDigest.getInstance("SHA-256")
        .digest(name.trim().lowercase().toByteArray(Charsets.UTF_8))
        .take(12)
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun readLimited(input: InputStream): String {
        val result = StringBuilder()
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
            val buffer = CharArray(8_192)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                if (result.length + count > MAX_PLAYLIST_CHARS) {
                    throw IllegalArgumentException("قائمة M3U أكبر من الحد المسموح (4 ميغابايت نصاً).")
                }
                result.append(buffer, 0, count)
            }
        }
        return result.toString()
    }

    private fun commaOutsideQuotes(value: String): Int {
        var quoted = false
        value.forEachIndexed { index, char ->
            if (char == '"') quoted = !quoted
            if (char == ',' && !quoted) return index
        }
        return -1
    }

    private fun String.validWebUrl(): String? = takeIf {
        try { val url = URL(it); (url.protocol == "http" || url.protocol == "https") && !url.host.isNullOrBlank() }
        catch (_: Exception) { false }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 25_000
        const val MAX_PLAYLIST_CHARS = 4 * 1024 * 1024
        const val MAX_CHANNELS = 10_000
        const val MAX_NAME_LENGTH = 180
        val ATTRIBUTE = Regex("([A-Za-z0-9_-]+)=\\\"([^\\\"]*)\\\"")
    }
}

data class M3UChannel(
    val id: String,
    val name: String,
    val streamUrl: String,
    val groupName: String?,
    val logoUrl: String?,
    val tvgId: String?,
)
