package com.lukemonaghan.tvhome

import android.content.Context
import android.content.SharedPreferences
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.sin

/**
 * The music view's moving background: MilkDrop presets played by projectM (ProjectM.kt) on a GLSurfaceView behind the home.
 * Plays the presets installed from Settings (PresetStore), picks the next one every few seconds or on a key, and feeds the
 * waveform of what the TV is playing (AudioViz). The surface is drawn smaller than the screen and scaled up, which is what
 * keeps it smooth on a weak GPU.
 *
 * A preset that comes in under about 14 frames a second, fails to load, or crashes the app (noticed on the next start) goes
 * on a skip list in the "viz_slow" pref and is not played again on this TV.
 * Prefs (home): viz_q (0 fast 640x360, 1 normal 960x540, 2 sharp 1280x720), viz_fps (30 or 60), viz_secs (seconds per preset, 0 = only on a key),
 * viz_slow, viz_off (turned off in Settings), viz_fav and viz_favs_only (play only the favourites), viz_last and viz_last_at (the preset to carry on with when the music view opens again), viz_loading (the preset being loaded: still set on the next start means it crashed).
 */
class Visualiser(ctx: Context, private val prefs: SharedPreferences, private val audio: AudioViz, private val onFirstFrame: () -> Unit,
                 private val onPreset: (String) -> Unit) : GLSurfaceView(ctx) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var step = 0                // +1 next, -1 previous: asked for from the UI thread
    @Volatile private var stopped = false

    init {
        setEGLContextClientVersion(2)
        val q = prefs.getInt("viz_q", 1).coerceIn(0, 2)
        holder.setFixedSize(intArrayOf(640, 960, 1280)[q], intArrayOf(360, 540, 720)[q])
        setRenderer(Draw(intArrayOf(640, 960, 1280)[q], intArrayOf(360, 540, 720)[q]))
    }

    @Volatile private var wantPath: String? = null
    /** The preset playing now (a path under filesDir/presets), so the picker can put it back. */
    @Volatile var nowPath = ""
    @Volatile private var reload = false
    /** The set of presets to rotate through changed (Play favourites only, say): rebuild it on the next frame. */
    fun repool() { reload = true }
    fun next() { step = 1 }
    /** Plays this preset now (a path under filesDir/presets), chosen in the music view's picker. */
    fun play(path: String) { wantPath = path }
    fun previous() { step = -1 }

    /** Stops the player on its own thread, where the GL context lives, then lets the thread go. */
    fun shutDown() {
        stopped = true
        queueEvent { ProjectM.destroy() }
        onPause()
    }

    private fun slow(): MutableSet<String> = HashSet(prefs.getStringSet("viz_slow", emptySet()) ?: emptySet())
    private fun markSlow(path: String) { prefs.edit().putStringSet("viz_slow", slow().apply { add(path) }).apply() }

    private inner class Draw(val w: Int, val h: Int) : GLSurfaceView.Renderer {
        private val root = PresetStore.dir(context)
        private var order: List<String> = emptyList()
        private var pos = -1
        private val history = ArrayList<String>()
        private var current = ""
        private var loadedAt = 0L
        private var judgeFrom = 0L; private var framesSince = 0; private var frames = 0
        private var shownFirst = false
        private var ready = false
        private val fps = prefs.getInt("viz_fps", 30).coerceIn(15, 60)
        private var pcm = FloatArray(1024); private var lastSeq = -1; private var tone = 0.0
        private var lastFrame = 0L

        private fun buildOrder() {
            val skip = slow()
            val off = PresetStore.off(prefs)
            var pool = PresetStore.installed(context).filter { it !in skip && it !in off }
            if (prefs.getBoolean("viz_favs_only", false)) { val f = PresetStore.favs(prefs); pool.filter { it in f }.takeIf { it.isNotEmpty() }?.let { pool = it } }
            order = pool.shuffled()
        }

        override fun onSurfaceCreated(g: GL10?, c: EGLConfig?) {
            prefs.getString("viz_loading", null)?.let { markSlow(it); prefs.edit().remove("viz_loading").apply() }   // it did not come back last time
            buildOrder()
            val r = ProjectM.init(PresetStore.textures(context).path + "/", w, h, fps, 32, 24)
            ready = r == "ok" && order.isNotEmpty()
            if (!ready) Log.w("tvhome", "visualiser not started: $r, ${order.size} presets")
            pos = -1; loadedAt = 0; shownFirst = false
            current = ""   // a new player has nothing loaded: put the same preset back (see resume)
        }

        override fun onSurfaceChanged(g: GL10?, width: Int, height: Int) { ProjectM.resize(width, height) }

        // The music view opening again (or the surface being made again) carries on with the preset it had, not a new one. A timer, if set, keeps counting from where it was.
        private fun resume(): Boolean {
            val last = prefs.getString("viz_last", null) ?: return false
            if (!File(root, last).exists() || last in slow() || last in PresetStore.off(prefs)) return false
            val secs = prefs.getInt("viz_secs", 45)
            val age = (System.currentTimeMillis() - prefs.getLong("viz_last_at", 0L)).coerceAtLeast(0L)
            if (secs > 0 && age > secs * 1000L) return false
            pos = order.indexOf(last).coerceAtLeast(-1)
            history.add(last)
            play(last, false)
            loadedAt = SystemClock.uptimeMillis() - age
            return true
        }

        private fun play(path: String, save: Boolean = true) {
            if (save) prefs.edit().putString("viz_last", path).putLong("viz_last_at", System.currentTimeMillis()).apply()
            prefs.edit().putString("viz_loading", path).commit()
            ProjectM.load(File(root, path).path, current.isNotEmpty())
            current = path; nowPath = path; loadedAt = SystemClock.uptimeMillis(); judgeFrom = if (path in slow()) Long.MAX_VALUE / 2 else loadedAt + 1500;   // a skipped preset picked by hand is not judged again; framesSince = 0; frames = 0
            val label = path.substringAfterLast('/').removeSuffix(".milk")
            main.post { onPreset(label) }
        }

        private fun advance(dir: Int) {
            if (order.isEmpty()) return
            if (dir < 0 && history.size > 1) { history.removeAt(history.size - 1); play(history.last()); return }
            pos = (pos + 1) % order.size
            val p = order[pos]
            history.add(p); if (history.size > 30) history.removeAt(0)
            play(p)
        }

        override fun onDrawFrame(g: GL10?) {
            if (!ready || stopped) return
            val now = SystemClock.uptimeMillis()
            val secs = prefs.getInt("viz_secs", 45)
            val s = step
            if (reload) { reload = false; buildOrder(); pos = order.indexOf(current) }
            val picked = wantPath
            if (picked != null) { wantPath = null; if (File(root, picked).exists()) { pos = order.indexOf(picked).coerceAtLeast(-1); history.add(picked); if (history.size > 30) history.removeAt(0); play(picked) } }
            else if (s != 0) { step = 0; advance(s) }
            else if (current.isEmpty()) { if (!resume()) advance(1) }
            else if (secs > 0 && now - loadedAt > secs * 1000L) advance(1)
            // the waveform: a new capture each time there is one; a soft pulse when this TV gives no audio tap
            if (audio.live) {
                val w = audio.wave
                if (w != null && audio.waveSeq != lastSeq) {
                    lastSeq = audio.waveSeq
                    val n = minOf(w.size, pcm.size)
                    for (i in 0 until n) pcm[i] = ((w[i].toInt() and 0xFF) - 128) / 128f
                    ProjectM.pcm(pcm, n)
                }
            } else {
                for (i in 0 until 735) {   // one frame of a calm 100 bpm pulse at 44.1 kHz
                    tone += 1.0 / 44100
                    val beat = Math.pow(1.0 - (tone * 1.6667 % 1.0), 4.0)
                    pcm[i] = (0.35 * beat * sin(2 * PI * 55 * tone) + 0.12 * sin(2 * PI * 330 * tone)).toFloat()
                }
                ProjectM.pcm(pcm, 735)
            }
            val err = ProjectM.frame()
            if (err != 0) Log.w("tvhome", "visualiser GL error $err in $current")
            frames++; framesSince++
            if (frames == 60) prefs.edit().remove("viz_loading").apply()   // it ran: not a crash
            if (!shownFirst && frames >= 3) { shownFirst = true; main.post { onFirstFrame() } }
            // too slow on this TV: skip it for good
            if (now > judgeFrom + 4000 && current.isNotEmpty()) {
                val f = framesSince * 1000f / (now - judgeFrom)
                if (f < 14f) { Log.i("tvhome", "visualiser: $current ran at ${"%.1f".format(f)} fps, skipping it"); markSlow(current); order = order.filter { it != current }; history.remove(current); if (order.isEmpty()) ready = false else advance(1) }
                else { judgeFrom = Long.MAX_VALUE / 2 }   // judged once per preset
            }
            if (frames % 20 == 0) ProjectM.takeFailed()?.let { f ->
                val rel = f.removePrefix(root.path + "/")
                markSlow(rel); order = order.filter { it != rel }; history.remove(rel); if (order.isEmpty()) ready = false else advance(1)
            }
            // hold the frame rate we asked for
            val spent = SystemClock.uptimeMillis() - now
            val want = 1000L / fps
            if (spent < want) try { Thread.sleep(want - spent) } catch (_: InterruptedException) {}
            lastFrame = now
        }
    }
}
