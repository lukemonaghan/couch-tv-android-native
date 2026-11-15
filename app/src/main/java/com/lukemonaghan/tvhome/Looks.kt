package com.lukemonaghan.tvhome

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import android.os.SystemClock

/**
 * The household's choices for how the home looks, from Home settings > Look and scenes, read from the "home" prefs.
 * Same options and defaults as couch-tv's web home (app.js lookRows, app.css "Glass panels", "How clear",
 * "Glass app icons", "Liquid glass clock", "Lighter home"):
 *
 *   glass        Off (the home's own plum panels), On (clear light panels) or Lite (On, no blur)
 *   amount       How clear 1 (frosted) .. 5 (very clear)
 *   glassIcons   each app's symbol in light glass (ClearIcon cuts the icon's own background away)
 *   glassFocus   the focused app stays glass instead of showing its own colours
 *   clockLiquid  the Night clock as clear glass digits
 *   lighter      for a slow box: no blur, no soft shadows, no sway. On by default on Android 6 (SDK 23)
 *   movingTiles  the focused tile sways and its light slides (off unless chosen, and never on the lighter home)
 *   keepPhoto    Classic keeps one photo; keepPick says which
 *   bedtimeDim   an amber dimming over the last 15 minutes of the sleep timer
 *
 * Memory: this class holds a few ints; the only pictures behind these options are the blurred glass base
 * (one RGB_565 screen, about 4 MB, none at all when nothing is blurred), the ClearIcon masks (36 KB each) and
 * the optional video (a MediaPlayer surface, nothing in the heap).
 */
class Looks(private val ctx: Context, private val prefs: SharedPreferences) {
    enum class Glass { OFF, ON, LITE, MUTED }

    private val old = Build.VERSION.SDK_INT < 24
    private fun bool(k: String, d: Boolean) = prefs.getBoolean(k, d)
    private fun put(k: String, v: Boolean) { prefs.edit().putBoolean(k, v).apply() }

    var lighter: Boolean
        get() = if (prefs.contains("lighter")) bool("lighter", false) else old
        set(v) = put("lighter", v)
    var glass: Glass
        get() = runCatching { Glass.valueOf(prefs.getString("glass", null) ?: "OFF") }.getOrDefault(Glass.OFF)
        set(v) { prefs.edit().putString("glass", v.name).apply() }
    var amount: Int
        get() = prefs.getInt("glassAmount", 3).coerceIn(1, 5)
        set(v) { prefs.edit().putInt("glassAmount", v.coerceIn(1, 5)).apply() }
    /** The Dark and Light looks: a solid colour instead of a scene, panels and words follow. */
    val light get() = prefs.getString("look", null) == "LIGHT"
    /** Cards and the music pill: the blurred picture behind them, how dark it is laid over (0 light, 1 medium, 2 dark) and how round the corners are (0 sharp, 1 normal, 2 round). */
    var cardArt: Boolean
        get() = bool("cardArt", true)
        set(v) = put("cardArt", v)
    var cardDark: Int
        get() = prefs.getInt("cardDark", 1).coerceIn(0, 2)
        set(v) { prefs.edit().putInt("cardDark", v.coerceIn(0, 2)).apply() }
    var roundness: Int
        get() = prefs.getInt("roundness", 1).coerceIn(0, 2)
        set(v) { prefs.edit().putInt("roundness", v.coerceIn(0, 2)).apply() }
    val scrimAlpha get() = intArrayOf(70, 115, 165)[cardDark]
    val radiusK get() = floatArrayOf(0.55f, 1f, 1.45f)[roundness]
    var glassIcons: Boolean
        get() = bool("glassIcons", false)
        set(v) = put("glassIcons", v)
    var glassFocusStay: Boolean
        get() = bool("glassFocusStay", false)
        set(v) = put("glassFocusStay", v)
    var clockLiquid: Boolean
        get() = bool("clockLiquid", false)
        set(v) = put("clockLiquid", v)
    var movingTiles: Boolean
        get() = if (lighter) false else if (prefs.contains("movingTiles")) bool("movingTiles", false) else false   // the web home turns it on by default; here it is off until chosen: it redraws the whole home about 25 times a second
        set(v) = put("movingTiles", v)
    var keepPhoto: Boolean
        get() = bool("keepPhoto", false)
        set(v) = put("keepPhoto", v)
    var keepPick: String
        get() = prefs.getString("keepPick", "") ?: ""
        set(v) { prefs.edit().putString("keepPick", v).apply() }
    var bedtimeDim: Boolean
        get() = bool("bedtimeDim", false)
        set(v) = put("bedtimeDim", v)
    var videoOn: Boolean
        get() = bool("videoOn", false)
        set(v) = put("videoOn", v)
    var videoUrl: String
        get() = prefs.getString("videoUrl", "") ?: ""
        set(v) { prefs.edit().putString("videoUrl", v).apply() }

    // ------------------------------------------------------------------ the video background's state

    /** True while the clip is really playing: the home then leaves the photo out and draws over the video. */
    @Volatile var videoPlaying = false
    var videoStatus = ""          // shown under the video link in settings
    var photoMsg = ""             // the last answer to adding a photo, shown on the photos list
    var shownPhoto = ""           // the Classic photo on screen now (its file name), so "Keep one photo" keeps that one
    private var listener: (() -> Unit)? = null
    fun onChanged(l: () -> Unit) { listener = l }
    fun notifyChanged() { ownAt = 0L; listener?.invoke() }
    val videoWanted get() = videoOn && videoUrl.isNotEmpty() && !lighter

    // ------------------------------------------------------------------ glass numbers (app.css, .glass, .ga-1 .. .ga-5)

    /** The blur behind a panel in 1080p pixels (1 rem = 16 px). 0: nothing is blurred, so no glass base is made. */
    val blurPx: Float
        get() = when {
            lighter || videoPlaying -> 0f
            glass == Glass.OFF -> 28f                      // 1.75rem, the home's own panels
            glass == Glass.LITE -> 0f
            glass == Glass.MUTED -> 32f
            else -> BLUR[amount - 1]
        }
    val baseSaturation get() = if (glass == Glass.ON) SAT[amount - 1] else if (glass == Glass.MUTED) 0.8f else 1.7f
    val baseBrightness get() = if (glass == Glass.ON) BRIGHT[amount - 1] else if (glass == Glass.MUTED) 0.82f else 1.06f

    /** The panel recipe: a gradient of white at 160 degrees (three alphas at 0, 42 and 100 %) over a flat colour. */
    class Panel(val plum: Boolean, val g0: Int, val g42: Int, val g100: Int, val flat: Int, val rich: Boolean, val muted: Boolean = false, val light: Boolean = false)

    fun panel(): Panel = if (light) Panel(false, 0, 0, 0, Color.argb(a(.66f), 255, 255, 255), false, light = true) else when (glass) {
        Glass.MUTED -> Panel(false, 0, 0, 0, Color.argb(a(.74f), 18, 20, 28), false, muted = true)
        Glass.OFF -> Panel(true, 0, 0, 0, Color.argb(if (lighter) 158 else 82, 30, 20, 28), false)
        Glass.LITE -> Panel(false, a(.16f), a(.04f), a(.07f), Color.argb(a(.6f), 24, 24, 32), !lighter)
        Glass.ON -> when (amount) {
            1 -> Panel(false, a(.32f), a(.10f), a(.15f), Color.argb(a(.34f), 22, 22, 30), !lighter)
            2 -> Panel(false, a(.26f), a(.075f), a(.12f), Color.argb(a(.27f), 22, 22, 30), !lighter)
            4 -> Panel(false, a(.13f), a(.03f), a(.06f), Color.argb(a(.13f), 22, 22, 30), !lighter)
            5 -> Panel(false, a(.07f), a(.015f), a(.035f), Color.argb(a(.08f), 22, 22, 30), !lighter)
            else -> Panel(false, a(.20f), a(.05f), a(.09f), Color.argb(a(.20f), 22, 22, 30), !lighter)
        }
    }

    /** A glass app icon tile (--gi-tint): same recipe, its own numbers; Lite is a flat grey tile. */
    fun iconPanel(): Panel = when {
        light -> Panel(false, 0, 0, 0, Color.argb(a(.7f), 255, 255, 255), false, light = true)
        glass == Glass.MUTED -> Panel(false, 0, 0, 0, Color.argb(a(.7f), 26, 28, 36), false, muted = true)
        glass == Glass.LITE -> Panel(false, 0, 0, 0, Color.argb(a(.55f), 36, 36, 44), false)
        else -> when (amount) {
            1 -> Panel(false, a(.36f), a(.14f), a(.20f), Color.argb(a(.34f), 24, 24, 32), true)
            2 -> Panel(false, a(.30f), a(.10f), a(.16f), Color.argb(a(.28f), 22, 22, 30), true)
            4 -> Panel(false, a(.16f), a(.035f), a(.08f), Color.argb(a(.16f), 18, 18, 26), true)
            5 -> Panel(false, a(.10f), a(.02f), a(.05f), Color.argb(a(.11f), 16, 16, 24), true)
            else -> Panel(false, a(.24f), a(.06f), a(.12f), Color.argb(a(.22f), 20, 20, 28), true)
        }
    }
    val glyphAlpha get() = GLYPH[amount - 1]

    private fun a(f: Float) = Math.round(f * 255f)

    /** Everything that changes how the scene bitmap or the glass base is drawn, so the home draws again when one changes. */
    fun signature(): String = "$glass$amount${lighter}$clockLiquid$glassIcons$videoPlaying$glassFocusStay"

    // ------------------------------------------------------------------ the Night clock's style

    class ClockStyle(val liquid: Boolean, val opacity: Float, val fill: Float, val rim: Float, val frost: Boolean, val video: Boolean) {
        val key get() = "$liquid$opacity$fill$rim$frost$video"
    }
    fun clockStyle(): ClockStyle {
        val i = amount - 1
        return ClockStyle(clockLiquid, GC_OP[i], LQ_FILL[i], LQ_RIM[i], amount <= 2, videoPlaying)
    }

    // ------------------------------------------------------------------ OLED burn-in shift (+-2 px, one step a minute)

    private var shiftIdx = 0
    fun stepMinute() { shiftIdx = (shiftIdx + 1) % OLED_STEPS.size }
    fun shiftX(u: Float) = OLED_STEPS[shiftIdx][0] * u
    fun shiftY(u: Float) = OLED_STEPS[shiftIdx][1] * u

    // ------------------------------------------------------------------ bedtime amber dimming

    private var sleepEnds = 0L
    private var dimSince = 0L
    /** The sleep timer was set to [minutes] (0 clears it). */
    fun sleepSet(minutes: Int) { sleepEnds = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0L; dimSince = 0L }

    /** Draws the overlay (rgba 28,14,2,.45, fading in over 2.5 s) when dimming is on and 15 minutes or less remain. True while it is still fading. */
    fun drawBedtime(c: Canvas, w: Int, h: Int, paint: Paint): Boolean {
        if (!bedtimeDim || sleepEnds == 0L) return false
        val left = sleepEnds - System.currentTimeMillis()
        if (left <= 0L) { sleepEnds = 0L; dimSince = 0L; return false }
        if (left > 15 * 60_000L) return false
        val now = SystemClock.uptimeMillis()
        if (dimSince == 0L) dimSince = now
        val f = ((now - dimSince) / 2500f).coerceIn(0f, 1f)
        val e = f * f * (3 - 2 * f)   // an ease in and out like the CSS curve
        paint.style = Paint.Style.FILL; paint.shader = null; paint.colorFilter = null
        paint.color = Color.argb((115 * e).toInt(), 28, 14, 2)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        return f < 1f
    }

    // ------------------------------------------------------------------ the focused tile's sway and sliding light (.tile.focus .face)

    private val camera = Camera().apply { setLocation(0f, 0f, -13.3f) }   // perspective 60 rem = 960 px at 72 dpi
    private val m = Matrix()
    /** Set while a focused tile was drawn this frame with motion on: the home asks for another frame (about 25 a second). */
    var motionDrawn = false

    /** 0..1..0 over 6 s with the CSS ease-in-out, for the alternating sway and glint. */
    private fun phase(): Float {
        val t = (SystemClock.uptimeMillis() % 12000L) / 6000f
        val tri = if (t <= 1f) t else 2f - t
        return (1f - Math.cos(Math.PI * tri).toFloat()) / 2f
    }

    /** Tilts the canvas about (cx, cy) like rotateX(1.2 -> -1.2deg) rotateY(-1.8 -> 1.8deg). Call inside save/restore. */
    fun sway(c: Canvas, cx: Float, cy: Float) {
        if (!movingTiles) return
        motionDrawn = true
        val p = phase()
        camera.save()
        camera.rotateX(1.2f - 2.4f * p); camera.rotateY(-1.8f + 3.6f * p)
        camera.getMatrix(m)
        camera.restore()
        m.preTranslate(-cx, -cy); m.postTranslate(cx, cy)
        c.concat(m)
    }

    /** Where the band of light is across a tile of [w]: its centre as a share of the width (1.25 - 1.5 p in the CSS), or a held position. */
    fun glintCentre(): Float = if (movingTiles) 1.25f - 1.5f * phase() else 0.5f

    // ------------------------------------------------------------------ Classic's photos: the built in ones, or the household's own

    private var ownAt = 0L
    private var ownFiles: List<java.io.File> = emptyList()
    /** The photo files, listed at most every 1.5 s (the home asks on every frame). */
    private fun own(): List<java.io.File> {
        val now = SystemClock.uptimeMillis()
        if (now - ownAt > 1500L) { ownFiles = OwnPhotos.list(ctx); ownAt = now }
        return ownFiles
    }

    fun photos(): List<SceneRenderer.Photo> {
        val own = own()
        if (own.isEmpty()) return SceneRenderer.PHOTOS
        return own.mapIndexed { i, f -> SceneRenderer.Photo(f.name, "Your photo ${i + 1}", "", f.absolutePath) }
    }
    fun ownScenes(): List<SceneRenderer.Spec> = own().map { SceneRenderer.own(it.name, it.absolutePath) }

    companion object {
        @Volatile private var shared: Looks? = null
        /** One Looks for the whole home: the settings page and the view share its live state (video, messages, sleep end). */
        fun of(ctx: Context, prefs: SharedPreferences): Looks = shared ?: synchronized(this) { shared ?: Looks(ctx.applicationContext, prefs).also { shared = it } }

        private val BLUR = floatArrayOf(52f, 44f, 36f, 20f, 8f)           // 3.25, 2.75, 2.25, 1.25, 0.5 rem
        private val SAT = floatArrayOf(1.70f, 1.78f, 1.85f, 1.90f, 1.95f)
        private val BRIGHT = floatArrayOf(1.06f, 1.06f, 1.06f, 1.05f, 1.04f)
        private val GLYPH = floatArrayOf(.97f, .95f, .92f, .90f, .88f)
        private val GC_OP = floatArrayOf(1f, 1f, 1f, .8f, .6f)             // the frosted clock at steps 4 and 5
        private val LQ_FILL = floatArrayOf(.95f, .9f, .85f, .7f, .55f)
        private val LQ_RIM = floatArrayOf(.85f, .82f, .8f, .75f, .7f)
        private val OLED_STEPS = arrayOf(intArrayOf(0, 0), intArrayOf(1, 1), intArrayOf(-1, 1), intArrayOf(2, -1),
            intArrayOf(-1, -1), intArrayOf(1, -2), intArrayOf(-2, 0), intArrayOf(0, 1))
        val AMOUNT_NAMES = arrayOf("Frosted", "Soft", "Balanced", "Clear", "Very clear")
    }
}
