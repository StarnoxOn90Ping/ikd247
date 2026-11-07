package ani.dantotsu.media

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import ani.dantotsu.others.ThemeSong

/** Plays one opening / ending at a time, straight from its stream. */
class ThemeSongPlayer(
    private val onChange: (ThemeSong?, State, Float) -> Unit,
    private val onFail: () -> Unit
) {
    enum class State { IDLE, LOADING, PLAYING, PAUSED }

    private var player: MediaPlayer? = null
    private var current: ThemeSong? = null
    private var state = State.IDLE
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            if (state != State.PLAYING || player == null) return
            onChange(current, state, progress())
            handler.postDelayed(this, 200)
        }
    }

    private fun progress(): Float {
        val p = player ?: return 0f
        val d = runCatching { p.duration }.getOrDefault(0)
        val pos = runCatching { p.currentPosition }.getOrDefault(0)
        return if (d > 0) (pos.toFloat() / d).coerceIn(0f, 1f) else 0f
    }

    fun toggle(song: ThemeSong) {
        val url = song.audioUrl ?: song.videoUrl
        if (url == null) {
            onFail()
            return
        }
        if (current == song) {
            when (state) {
                State.PLAYING -> {
                    runCatching { player?.pause() }
                    state = State.PAUSED
                    handler.removeCallbacks(ticker)
                    onChange(song, state, progress())
                }

                State.PAUSED -> {
                    runCatching { player?.start() }
                    state = State.PLAYING
                    onChange(song, state, progress())
                    handler.post(ticker)
                }

                State.LOADING -> Unit
                State.IDLE -> start(song, url)
            }
            return
        }
        start(song, url)
    }

    private fun start(song: ThemeSong, url: String) {
        release()
        current = song
        state = State.LOADING
        onChange(song, state, 0f)
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setDataSource(url)
            mp.setOnPreparedListener {
                if (player === it) {
                    it.start()
                    state = State.PLAYING
                    onChange(song, state, 0f)
                    handler.post(ticker)
                }
            }
            mp.setOnCompletionListener { if (player === it) stop() }
            mp.setOnErrorListener { _, _, _ ->
                if (player === mp) {
                    stop()
                    onFail()
                }
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            stop()
            onFail()
        }
    }

    fun stop() {
        release()
        current = null
        state = State.IDLE
        onChange(null, State.IDLE, 0f)
    }

    private fun release() {
        handler.removeCallbacks(ticker)
        player?.let {
            runCatching { it.reset() }
            runCatching { it.release() }
        }
        player = null
    }
}
