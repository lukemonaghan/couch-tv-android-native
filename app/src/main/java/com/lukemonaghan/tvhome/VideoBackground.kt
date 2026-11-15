package com.lukemonaghan.tvhome

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView

/**
 * The optional video background: a short clip that loops, muted, in place of the photo (Home settings, Look, Moving
 * background). A TextureView behind the home with the platform's MediaPlayer: no player library, no frames in the heap.
 *
 * The photo stays until the clip really plays ([Looks.videoPlaying] turns true on the first rendered frame, and the home
 * then leaves its photo out and draws over the video) and comes back if it fails or has not started within 20 seconds.
 * It pauses whenever the home is not in front, and is never used on the lighter home. At night the picture is dimmed to 72%.
 */
class VideoBackground(ctx: Context, private val looks: Looks) : TextureView(ctx), TextureView.SurfaceTextureListener {
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var shownUrl = ""
    private var visibleHome = false
    private val giveUp = Runnable { fail("The clip did not start in 20 seconds. The photo stays.") }

    private var dimOn = false
    private var shown = false
    // alpha 0, not INVISIBLE: a TextureView only gets its surface once it is drawn
    init { surfaceTextureListener = this; alpha = 0f }
    private fun paintAlpha() { alpha = if (shown) (if (dimOn) 0.72f else 1f) else 0f }

    /** Settings or the lifecycle changed: start, stop or keep going. */
    fun sync() {
        dim(SceneRenderer.isDim(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)))
        if (!looks.videoWanted) { stop(); return }
        if (visibleHome && isAvailable) start()
    }

    fun resume() { visibleHome = true; sync(); player?.let { runCatching { if (looks.videoPlaying) it.start() } } }
    fun pause() { visibleHome = false; runCatching { player?.pause() } }

    /** At night couch-tv dims the picture to 72%. */
    fun dim(on: Boolean) { dimOn = on; paintAlpha() }

    private fun start() {
        val url = looks.videoUrl
        if (player != null && url == shownUrl) return
        stop(keepWanted = true, notify = false)
        shownUrl = url
        looks.videoStatus = "Loading the clip"
        val s = surface ?: return
        main.postDelayed(giveUp, 20_000L)
        player = MediaPlayer().apply {
            runCatching {
                setSurface(s)
                setDataSource(url)
                isLooping = true
                setVolume(0f, 0f)
                setOnPreparedListener { fit(it.videoWidth, it.videoHeight); if (visibleHome) it.start() }
                setOnInfoListener { _, what, _ ->
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        main.removeCallbacks(giveUp)
                        shown = true; paintAlpha()
                        looks.videoStatus = "Playing"
                        looks.videoPlaying = true; looks.notifyChanged()
                    }
                    false
                }
                setOnErrorListener { _, _, _ -> fail("The clip would not play. The photo stays."); true }
                prepareAsync()
            }.onFailure { fail("That link is not a video the TV can play.") }
        }
    }

    private fun fail(msg: String) {
        looks.videoOn = false        // like the web home: a clip that fails is switched off, the photo stays
        stop(keepWanted = true)
        looks.videoStatus = msg
        looks.notifyChanged()
    }

    private fun stop(keepWanted: Boolean = false, notify: Boolean = true) {
        main.removeCallbacks(giveUp)
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null; shownUrl = ""
        shown = false; paintAlpha()
        val was = looks.videoPlaying
        looks.videoPlaying = false
        if (!keepWanted) looks.videoStatus = ""
        if (was && notify) looks.notifyChanged()
    }

    /** Fills the screen with the clip, centred and cropped (CSS object-fit: cover). */
    private fun fit(vw: Int, vh: Int) {
        if (vw <= 0 || vh <= 0 || width == 0 || height == 0) return
        val sx = width.toFloat() / vw; val sy = height.toFloat() / vh
        val k = maxOf(sx, sy)
        val m = Matrix()
        m.setScale(vw * k / width, vh * k / height, width / 2f, height / 2f)
        setTransform(m)
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { surface = Surface(st); sync() }
    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { stop(keepWanted = true); surface?.release(); surface = null; return true }
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
}
