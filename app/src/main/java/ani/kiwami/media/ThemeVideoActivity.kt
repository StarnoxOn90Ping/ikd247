package ani.kiwami.media

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import ani.kiwami.R
import ani.kiwami.databinding.ActivityThemeVideoBinding
import ani.kiwami.others.ThemeSong
import ani.kiwami.themes.ThemeManager
import java.util.Locale

/** Plays the video of one opening / ending (from AnimeThemes) full screen. */
class ThemeVideoActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SUBTITLE = "subtitle"
        private const val EXTRA_YT_ID = "ytId"
        private const val EXTRA_SEARCH = "search"

        /** Plays a YouTube video inside the app (used when AnimeThemes has no video). */
        fun openYoutube(
            context: Context,
            videoId: String,
            title: String,
            subtitle: String?,
            searchUrl: String?
        ) {
            context.startActivity(
                Intent(context, ThemeVideoActivity::class.java)
                    .putExtra(EXTRA_YT_ID, videoId)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_SUBTITLE, subtitle)
                    .putExtra(EXTRA_SEARCH, searchUrl)
            )
        }

        fun open(context: Context, song: ThemeSong, animeName: String?) {
            val url = song.videoUrl ?: song.audioUrl ?: return
            val label = listOf(song.slug.ifEmpty { song.type }, song.title)
                .filter { it.isNotBlank() }.joinToString(" · ")
            val sub = listOfNotNull(
                animeName,
                song.artists.joinToString(", ").takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            val search = "https://www.youtube.com/results?search_query=" +
                    java.net.URLEncoder.encode(
                        listOfNotNull(animeName, song.title).joinToString(" "), "utf-8"
                    )
            context.startActivity(
                Intent(context, ThemeVideoActivity::class.java)
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_TITLE, label)
                    .putExtra(EXTRA_SUBTITLE, sub)
                    .putExtra(EXTRA_SEARCH, search)
            )
        }
    }

    private lateinit var binding: ActivityThemeVideoBinding
    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var videoWidth = 0f
    private var videoHeight = 0f
    private var seeking = false
    private var url = ""
    private var searchUrl: String? = null
    private var webMode = false

    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }
    private val hideControls = Runnable { setControlsVisible(false) }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityThemeVideoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val ytId = intent.getStringExtra(EXTRA_YT_ID)
        webMode = ytId != null
        url = intent.getStringExtra(EXTRA_URL) ?: ytId?.let { "https://www.youtube.com/watch?v=$it" }
                ?: run { finish(); return }
        searchUrl = intent.getStringExtra(EXTRA_SEARCH)
        binding.themeVideoTitle.text = intent.getStringExtra(EXTRA_TITLE)
        binding.themeVideoSubtitle.text = intent.getStringExtra(EXTRA_SUBTITLE)

        // keep the bars clear of the status / navigation bars and cutouts
        val d = resources.displayMetrics.density
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.themeVideoTop.setPadding(
                bars.left + (8 * d).toInt(), bars.top + (8 * d).toInt(),
                bars.right + (16 * d).toInt(), (24 * d).toInt()
            )
            binding.themeVideoBottom.setPadding(
                bars.left + (12 * d).toInt(), (24 * d).toInt(),
                bars.right + (16 * d).toInt(), bars.bottom + (12 * d).toInt()
            )
            insets
        }

        binding.themeVideoBack.setOnClickListener { finish() }
        binding.themeVideoBrowser.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
        binding.themeVideoSearch.visibility =
            if (searchUrl != null) View.VISIBLE else View.GONE
        binding.themeVideoSearch.setOnClickListener {
            searchUrl?.let { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
        }
        binding.themeVideoStage.setOnClickListener {
            setControlsVisible(binding.themeVideoTop.visibility != View.VISIBLE)
        }
        binding.themeVideoStage.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitSurface() }
        binding.themeVideoPlay.setOnClickListener {
            player?.let { p ->
                if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
                if (p.isPlaying) p.pause() else p.play()
            }
            scheduleHide()
        }
        binding.themeVideoSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                val duration = player?.duration ?: 0L
                if (duration > 0) binding.themeVideoTime.text = format(duration * value / 1000)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {
                seeking = true
                handler.removeCallbacks(hideControls)
            }

            override fun onStopTrackingTouch(bar: SeekBar?) {
                val duration = player?.duration ?: 0L
                if (duration > 0) player?.seekTo(duration * (bar?.progress ?: 0) / 1000)
                seeking = false
                scheduleHide()
            }
        })
        if (webMode) setupWeb(ytId!!) else setupPlayer()
    }

    // YouTube inside the app: same embed approach the trailer uses
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb(id: String) {
        val web = binding.themeVideoWeb
        web.visibility = View.VISIBLE
        binding.themeVideoStage.visibility = View.GONE
        binding.themeVideoBottom.visibility = View.GONE
        binding.themeVideoLoading.visibility = View.GONE
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.setBackgroundColor(0xFF000000.toInt())
        web.webChromeClient = android.webkit.WebChromeClient()
        // keep the video below the title bar, which stays visible in this mode
        binding.themeVideoTop.post {
            val d = resources.displayMetrics.density
            val lp = web.layoutParams as FrameLayout.LayoutParams
            lp.topMargin = (binding.themeVideoTop.height - 24 * d).toInt()
            web.layoutParams = lp
        }
        val html = """
            <html><body style="margin:0;background:#000">
            <iframe src="https://www.youtube-nocookie.com/embed/$id?autoplay=1&playsinline=1&rel=0"
                frameborder="0" allow="autoplay; encrypted-media; fullscreen" allowfullscreen
                style="position:absolute;top:0;left:0;width:100%;height:100%"></iframe>
            </body></html>
        """.trimIndent()
        web.loadDataWithBaseURL("https://www.youtube-nocookie.com", html, "text/html", "utf-8", null)
    }

    private fun setupPlayer() {
        val exo = ExoPlayer.Builder(this).build()
        player = exo
        exo.setVideoSurfaceView(binding.themeVideoSurface)
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                binding.themeVideoLoading.visibility =
                    if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                if (state == Player.STATE_READY) {
                    binding.themeVideoDuration.text = format(exo.duration)
                }
                if (state == Player.STATE_ENDED) setControlsVisible(true)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                binding.themeVideoPlay.setImageResource(
                    if (isPlaying) R.drawable.ic_theme_pause else R.drawable.ic_theme_play
                )
                if (isPlaying) scheduleHide()
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                videoWidth = size.width * size.pixelWidthHeightRatio
                videoHeight = size.height.toFloat()
                fitSurface()
            }

            override fun onPlayerError(error: PlaybackException) {
                binding.themeVideoLoading.visibility = View.GONE
                binding.themeVideoError.visibility = View.VISIBLE
                setControlsVisible(true)
            }
        })
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.prepare()
        exo.playWhenReady = true
        handler.post(ticker)
    }

    private fun fitSurface() {
        if (videoWidth <= 0f || videoHeight <= 0f) return
        val stage = binding.themeVideoStage
        if (stage.width <= 0 || stage.height <= 0) return
        val scale = minOf(stage.width / videoWidth, stage.height / videoHeight)
        val lp = binding.themeVideoSurface.layoutParams as FrameLayout.LayoutParams
        val w = (videoWidth * scale).toInt()
        val h = (videoHeight * scale).toInt()
        if (lp.width == w && lp.height == h) return
        lp.width = w
        lp.height = h
        lp.gravity = Gravity.CENTER
        binding.themeVideoSurface.layoutParams = lp
    }

    private fun updateProgress() {
        val p = player ?: return
        if (seeking) return
        val duration = p.duration
        if (duration > 0) {
            binding.themeVideoSeek.progress = (p.currentPosition * 1000 / duration).toInt()
            binding.themeVideoDuration.text = format(duration)
        }
        binding.themeVideoTime.text = format(p.currentPosition)
    }

    private fun setControlsVisible(visible: Boolean) {
        if (webMode) return
        handler.removeCallbacks(hideControls)
        val v = if (visible) View.VISIBLE else View.GONE
        binding.themeVideoTop.visibility = v
        binding.themeVideoBottom.visibility = v
        if (visible) scheduleHide()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideControls)
        if (player?.isPlaying == true) handler.postDelayed(hideControls, 3000)
    }

    private fun format(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
    }

    override fun onPause() {
        player?.pause()
        if (webMode) binding.themeVideoWeb.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (webMode) binding.themeVideoWeb.onResume()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        if (webMode) {
            binding.themeVideoWeb.loadUrl("about:blank")
            binding.themeVideoWeb.destroy()
        }
        super.onDestroy()
    }
}
