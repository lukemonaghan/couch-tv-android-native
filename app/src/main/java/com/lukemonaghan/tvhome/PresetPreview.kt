package com.lukemonaghan.tvhome

import android.content.Context
import android.content.SharedPreferences
import android.opengl.GLSurfaceView
import android.os.SystemClock
import java.io.File
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.sin

/**
 * The little live picture beside the preset list in Settings: projectM drawing the focused preset at 320 x 180 (a fraction of the
 * pixels of the real thing, so even a weak GPU keeps up), with a calm made-up beat for sound. It sits on top of the window at the
 * rectangle Settings gives it (MainActivity.vizPreview) and is taken away when Settings stops showing a preset.
 * Moving the focus waits half a second before loading, so scrolling fast does not load every preset on the way. It never marks a
 * preset slow; it only uses the same crash marker as the music view ("viz_loading") so a preset that crashes the app is skipped later.
 */
class PresetPreview(ctx: Context, private val prefs: SharedPreferences) : GLSurfaceView(ctx) {
    @Volatile private var want: String? = null
    @Volatile private var wantAt = 0L
    @Volatile private var stopped = false

    init {
        setEGLContextClientVersion(2)
        setZOrderOnTop(true)
        holder.setFixedSize(W, H)
        setRenderer(Draw())
    }

    fun show(path: String) { if (path != want) { want = path; wantAt = SystemClock.uptimeMillis() } }

    fun shutDown() {
        stopped = true
        queueEvent { ProjectM.destroy() }
        onPause()
    }

    private inner class Draw : GLSurfaceView.Renderer {
        private val root = PresetStore.dir(context)
        private var ready = false
        private var current = ""
        private var frames = 0
        private val pcm = FloatArray(1024)
        private var tone = 0.0

        override fun onSurfaceCreated(g: GL10?, c: EGLConfig?) {
            ready = ProjectM.init(PresetStore.textures(context).path + "/", W, H, FPS, 16, 12) == "ok"
            current = ""
        }

        override fun onSurfaceChanged(g: GL10?, width: Int, height: Int) { ProjectM.resize(width, height) }

        override fun onDrawFrame(g: GL10?) {
            if (!ready || stopped) return
            val now = SystemClock.uptimeMillis()
            val w = want
            if (w != null && w != current && now - wantAt > 450) {
                val f = File(root, w)
                if (f.exists()) {
                    prefs.edit().putString("viz_loading", w).commit()
                    ProjectM.load(f.path, false)
                    frames = 0
                }
                current = w
            }
            for (i in 0 until 1470) {   // one frame of a calm 100 bpm pulse at 44.1 kHz, twice the length projectM needs
                tone += 1.0 / 44100
                val beat = Math.pow(1.0 - (tone * 1.6667 % 1.0), 4.0)
                if (i < pcm.size) pcm[i] = (0.35 * beat * sin(2 * PI * 55 * tone) + 0.12 * sin(2 * PI * 330 * tone)).toFloat()
            }
            ProjectM.pcm(pcm, pcm.size)
            ProjectM.frame()
            if (++frames == 40) prefs.edit().remove("viz_loading").apply()
            ProjectM.takeFailed()
            val spent = SystemClock.uptimeMillis() - now
            if (spent < 1000L / FPS) try { Thread.sleep(1000L / FPS - spent) } catch (_: InterruptedException) {}
        }
    }

    companion object { const val W = 320; const val H = 180; const val FPS = 20 }
}
