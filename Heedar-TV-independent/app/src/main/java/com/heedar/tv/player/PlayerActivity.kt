package com.heedar.tv.player

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import com.heedar.tv.settings.AppPreferences

class PlayerActivity : Activity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private var pickingSubtitle = false
    private var resumeAfterSubtitlePicker = false
    private val preferences by lazy { AppPreferences(this) }

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        if (preferences.keepScreenOn) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        @Suppress("DEPRECATION")
        run {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }

        val url = intent.getStringExtra(EXTRA_STREAM_URL)
        if (url.isNullOrBlank() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            finish()
            return
        }

        playerView = PlayerView(this).apply {
            setBackgroundColor(Color.BLACK)
            useController = true
            controllerShowTimeoutMs = preferences.controlsTimeoutMs
            resizeMode = when (preferences.videoScale) {
                AppPreferences.SCALE_CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                AppPreferences.SCALE_STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            contentDescription = intent.getStringExtra(EXTRA_TITLE) ?: "Heedar TV player"
            keepScreenOn = preferences.keepScreenOn
            isFocusable = true
            isFocusableInTouchMode = true
        }
        setContentView(buildPlayerLayout())

        val httpDataSource = DefaultHttpDataSource.Factory()
            .setUserAgent("HeedarTV/0.10 (Linux; Android)")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpDataSource)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().also { exo ->
                playerView.player = exo
                exo.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        Toast.makeText(this@PlayerActivity, "تعذّر تشغيل البث أو ملف الترجمة.", Toast.LENGTH_LONG).show()
                    }
                })
                exo.setMediaItem(MediaItem.fromUri(url))
                exo.prepare()
                exo.playWhenReady = preferences.autoPlay
            }
        playerView.requestFocus()
    }

    private fun buildPlayerLayout(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(
            playerView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(0xCC101820.toInt())
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), preferences.accentColor)
            }
        }
        actions.addView(actionButton("الجودة").apply {
            setOnClickListener { showTrackSelection("اختيار الجودة", C.TRACK_TYPE_VIDEO, allowAdaptive = true) }
        }, actionParams())
        actions.addView(actionButton("الصوت").apply {
            setOnClickListener { showTrackSelection("اختيار المسار الصوتي", C.TRACK_TYPE_AUDIO, allowAdaptive = false) }
        }, actionParams())
        actions.addView(actionButton("الترجمة").apply {
            setOnClickListener { showTrackSelection("اختيار الترجمة", C.TRACK_TYPE_TEXT, allowAdaptive = false) }
        }, actionParams())
        actions.addView(actionButton("رابط ترجمة").apply { setOnClickListener { showExternalSubtitleDialog() } }, actionParams())
        actions.addView(actionButton("ملف ترجمة").apply { setOnClickListener { pickSubtitleFile() } }, actionParams())
        val layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ).apply {
            leftMargin = dp(16)
            rightMargin = dp(16)
            topMargin = dp(22)
        }
        root.addView(actions, layoutParams)
        return root
    }

    @OptIn(UnstableApi::class)
    private fun showTrackSelection(title: String, trackType: Int, allowAdaptive: Boolean) {
        val current = player ?: return
        try {
            TrackSelectionDialogBuilder(this, title, current, trackType)
                .setShowDisableOption(trackType == C.TRACK_TYPE_TEXT)
                .setAllowAdaptiveSelections(allowAdaptive)
                .build()
                .show()
        } catch (_: Exception) {
            Toast.makeText(this, "لا تتوفر مسارات لهذا النوع في البث الحالي.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showExternalSubtitleDialog() {
        val input = EditText(this).apply {
            hint = "https://example.com/subtitle.srt أو .vtt"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("إضافة ترجمة من رابط")
            .setMessage("يدعم SRT وWebVTT وTTML. تأكد أن الرابط متاح للجهاز.")
            .setView(input)
            .setPositiveButton("إضافة", null)
            .setNegativeButton("إلغاء", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val raw = input.text.toString().trim()
                val uri = try { Uri.parse(raw) } catch (_: Exception) { null }
                val mimeType = uri?.let(::mimeTypeForUri)
                if (uri == null || (uri.scheme != "http" && uri.scheme != "https") || uri.host.isNullOrBlank() || mimeType == null) {
                    input.error = "استخدم رابط HTTP/HTTPS ينتهي بـ .srt أو .vtt أو .ttml"
                    return@setOnClickListener
                }
                addExternalSubtitle(uri, mimeType)
                dialog.dismiss()
            }
        }
        if (player?.currentMediaItem?.localConfiguration?.subtitleConfigurations?.isNotEmpty() == true) {
            dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "إزالة الترجمات الخارجية") { _, _ -> clearExternalSubtitles() }
        }
        dialog.show()
    }

    @Suppress("DEPRECATION")
    private fun pickSubtitleFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(MimeTypes.APPLICATION_SUBRIP, MimeTypes.TEXT_VTT, MimeTypes.APPLICATION_TTML, "text/plain"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        resumeAfterSubtitlePicker = player?.playWhenReady == true
        player?.playWhenReady = false
        pickingSubtitle = true
        try {
            startActivityForResult(intent, REQUEST_SUBTITLE_FILE)
        } catch (_: Exception) {
            pickingSubtitle = false
            restorePlaybackAfterPicker()
            Toast.makeText(this, "لا يتوفر مدير ملفات لاختيار الترجمة.", Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SUBTITLE_FILE) return
        pickingSubtitle = false
        if (resultCode != RESULT_OK) {
            restorePlaybackAfterPicker()
            return
        }
        val resultData = data
        val uri = resultData?.data
        if (uri == null || resultData == null) {
            restorePlaybackAfterPicker()
            return
        }
        try {
            val permission = resultData.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (permission != 0) contentResolver.takePersistableUriPermission(uri, permission)
        } catch (_: Exception) {
            // وصول مؤقت للقراءة
        }
        val mimeType = mimeTypeForUri(uri) ?: contentResolver.getType(uri)?.takeIf {
            it == MimeTypes.APPLICATION_SUBRIP || it == MimeTypes.TEXT_VTT || it == MimeTypes.APPLICATION_TTML
        }
        if (mimeType == null) {
            Toast.makeText(this, "صيغة غير مدعومة. اختر SRT أو VTT أو TTML.", Toast.LENGTH_LONG).show()
            restorePlaybackAfterPicker()
            return
        }
        addExternalSubtitle(uri, mimeType)
        restorePlaybackAfterPicker()
    }

    private fun restorePlaybackAfterPicker() {
        if (resumeAfterSubtitlePicker) player?.playWhenReady = true
        resumeAfterSubtitlePicker = false
    }

    private fun mimeTypeForUri(uri: Uri): String? = when (uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()) {
        "srt" -> MimeTypes.APPLICATION_SUBRIP
        "vtt" -> MimeTypes.TEXT_VTT
        "ttml", "xml" -> MimeTypes.APPLICATION_TTML
        else -> null
    }

    private fun addExternalSubtitle(uri: Uri, mimeType: String) {
        val exo = player ?: return
        val current = exo.currentMediaItem ?: return
        val existing = current.localConfiguration?.subtitleConfigurations.orEmpty()
        if (existing.any { it.uri == uri }) {
            Toast.makeText(this, "هذه الترجمة مضافة بالفعل.", Toast.LENGTH_SHORT).show()
            return
        }
        val subtitle = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mimeType)
            .setLanguage("und")
            .setLabel(uri.lastPathSegment ?: "ترجمة خارجية")
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        replaceMediaItem(current, existing + subtitle)
        Toast.makeText(this, "أُضيفت الترجمة. اخترها من زر الترجمة إذا لم تُفعّل تلقائياً.", Toast.LENGTH_LONG).show()
    }

    private fun clearExternalSubtitles() {
        val exo = player ?: return
        val current = exo.currentMediaItem ?: return
        if (current.localConfiguration?.subtitleConfigurations.isNullOrEmpty()) return
        replaceMediaItem(current, emptyList())
        Toast.makeText(this, "أُزيلت الترجمات الخارجية.", Toast.LENGTH_SHORT).show()
    }

    private fun replaceMediaItem(current: MediaItem, subtitleConfigurations: List<MediaItem.SubtitleConfiguration>) {
        val exo = player ?: return
        val position = exo.currentPosition.coerceAtLeast(0L)
        val shouldPlay = exo.playWhenReady
        val replacement = current.buildUpon().setSubtitleConfigurations(subtitleConfigurations).build()
        exo.setMediaItem(replacement, position)
        exo.prepare()
        exo.playWhenReady = shouldPlay
    }

    private fun actionButton(title: String) = android.widget.Button(this).apply {
        text = title
        textSize = 13f
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(preferences.buttonColor)
            setStroke(dp(1), preferences.accentColor)
            cornerRadius = dp(10).toFloat()
        }
        minHeight = dp(48)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    private fun actionParams() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        marginStart = dp(4)
        marginEnd = dp(4)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density * preferences.densityScale).toInt()

    override fun onStop() {
        if (!pickingSubtitle) releasePlayer()
        super.onStop()
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }

    private fun releasePlayer() {
        if (::playerView.isInitialized) playerView.player = null
        player?.release()
        player = null
    }

    companion object {
        const val EXTRA_STREAM_URL = "com.heedar.tv.extra.STREAM_URL"
        const val EXTRA_TITLE = "com.heedar.tv.extra.TITLE"
        private const val REQUEST_SUBTITLE_FILE = 741
    }
}
