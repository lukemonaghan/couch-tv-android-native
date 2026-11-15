package com.lukemonaghan.tvhome

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextPaint
import java.util.Calendar
import kotlin.math.ceil

/**
 * Everything the home does when nobody is pressing keys, and the sound that goes with it (couch-tv's Smart Idle,
 * Hide rows when idle, soundscape glue and sleep fade). One small object the HomeView asks; the heavy parts live in
 * Sounds.kt. Settings are plain prefs in "home" (names in [IdleSettings]).
 *
 *  - Smart Idle: after N minutes without a key on the home (a shorter wait in quiet hours) do one of three things:
 *    an OLED-safe ambient clock (black, a dim clock that shifts a few pixels every minute), screen off (brightness
 *    to 0, sound and the TV carry on), or turn the TV off (the sleep timer's trick). Any key wakes it and is eaten,
 *    so waking the clock never opens an app. With a soundscape playing and no sleep timer "turn TV off" shows the
 *    ambient clock instead, as couch-tv does, so the sound is not cut.
 *  - Hide rows when idle: after N seconds the rows fade out and the clock, weather and scene stay.
 *  - The soundscape: stopped when the home leaves the screen, quieter over the sleep timer's last 15 minutes.
 */
class IdleLayer(
    private val ctx: Context,
    private val prefs: SharedPreferences,
    private val host: IdleHost?,
    private val invalidate: () -> Unit,
    private val clockText: () -> String,
    private val dateText: () -> String,
    private val weatherText: () -> String,
    private val canHideRows: () -> Boolean,
) {
    private val main = Handler(Looper.getMainLooper())
    private var last = SystemClock.uptimeMillis()
    private var overlay = 0                  // 0 none, 1 ambient clock, 2 screen off
    private var overlaySince = 0L
    private var rowsHidden = false
    private var rowsChangedAt = 0L
    private var swallow = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    val sound = Soundscape()
    private var fading = false
    private var endsSeen = 0L

    init { current = this }

    // ------------------------------------------------------------------ settings

    val enabled get() = prefs.getBoolean("idle_on", false)
    private fun homeMin() = prefs.getInt("idle_home", 15)
    fun action(): String = prefs.getString("idle_action", "ambient_clock") ?: "ambient_clock"
    private fun rowsSec() = prefs.getInt("idle_rows", 0)

    private fun minutesOf(hhmm: String, def: Int): Int {
        val p = hhmm.split(':'); return (p.getOrNull(0)?.toIntOrNull() ?: def / 60) * 60 + (p.getOrNull(1)?.toIntOrNull() ?: def % 60)
    }

    fun isQuietHours(): Boolean {
        if (!prefs.getBoolean("idle_q_on", false)) return false
        val now = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
        val s = minutesOf(prefs.getString("idle_q_start", "23:00") ?: "23:00", 23 * 60)
        val e = minutesOf(prefs.getString("idle_q_end", "06:00") ?: "06:00", 6 * 60)
        return if (s == e) true else if (s < e) now in s until e else now >= s || now < e
    }

    // ------------------------------------------------------------------ keys

    /** A key went down on the home. True when it only woke something (the ambient clock, the dark screen, the hidden rows): the home must ignore it. */
    fun key(): Boolean {
        last = SystemClock.uptimeMillis()
        val woke = overlay != 0 || rowsHidden
        if (overlay != 0) closeOverlay()
        if (rowsHidden) { rowsHidden = false; rowsChangedAt = SystemClock.uptimeMillis(); invalidate() }
        if (woke) swallow = true
        return woke
    }

    /** Wake without a key (Home pressed, an app came back): closes the ambient clock and shows the rows, eats nothing. */
    fun wake() {
        last = SystemClock.uptimeMillis()
        if (overlay != 0) closeOverlay()
        if (rowsHidden) { rowsHidden = false; rowsChangedAt = SystemClock.uptimeMillis(); invalidate() }
    }

    /** The matching key-up of a key [key] ate. */
    fun eatUp(): Boolean { if (!swallow) return false; swallow = false; return true }

    /** Something other than a key happened (the home came back to the front): the wait starts again. */
    fun touch() { last = SystemClock.uptimeMillis() }

    // ------------------------------------------------------------------ the one-second watch

    private val tick = object : Runnable {
        override fun run() { check(); followSleep(); main.postDelayed(this, 1000L) }
    }
    fun start() { touch(); main.removeCallbacks(tick); main.postDelayed(tick, 1000L) }
    fun stop() { main.removeCallbacks(tick); if (overlay != 0) closeOverlay(); stopSound() }

    private fun check() {
        if (!MainActivity.isInFront() || overlay != 0) return
        val idleMs = SystemClock.uptimeMillis() - last
        val sec = rowsSec()
        if (sec > 0 && !rowsHidden && idleMs >= sec * 1000L && canHideRows()) {
            rowsHidden = true; rowsChangedAt = SystemClock.uptimeMillis(); invalidate()
        }
        if (!enabled) return
        val mins = if (isQuietHours()) prefs.getInt("idle_q_min", 15) else homeMin()
        if (mins <= 0 || idleMs < mins * 60_000L) return
        when (action()) {
            "screen_off" -> openOverlay(2)
            "tv_off" -> {
                val playing = sound.current() != "off"
                if (playing && (host?.sleepEndsAt() ?: 0L) == 0L) openOverlay(1) else TvSleep.now(ctx)
                last = SystemClock.uptimeMillis()   // do not ask again at once
            }
            else -> openOverlay(1)
        }
    }

    private fun openOverlay(kind: Int) {
        overlay = kind; overlaySince = SystemClock.uptimeMillis()
        if (kind == 2) host?.setScreenDark(true)
        invalidate()
    }

    private fun closeOverlay() {
        if (overlay == 2) host?.setScreenDark(false)
        overlay = 0
        invalidate()
    }

    // ------------------------------------------------------------------ drawing

    /** True once the overlay is fully opaque: the home need not be drawn at all underneath it. */
    fun coversHome(): Boolean = overlay != 0 && SystemClock.uptimeMillis() - overlaySince >= fadeMs()
    fun overlayActive(): Boolean = overlay != 0
    private fun fadeMs() = if (overlay == 2) 400L else 800L

    /** Rows' opacity: 1 normally, fading to 0 in 400 ms once they are hidden. */
    fun rowsAlpha(): Float {
        val f = ((SystemClock.uptimeMillis() - rowsChangedAt) / 400f).coerceIn(0f, 1f)
        if (rowsChangedAt == 0L) return if (rowsHidden) 0f else 1f
        if (f < 1f) invalidate()
        return if (rowsHidden) 1f - f else f
    }

    /** The ambient clock or the black screen, on top of the home (or alone, once it is opaque). */
    fun drawOver(c: Canvas, w: Int, h: Int) {
        if (overlay == 0) return
        val u = h / 1080f
        val t = SystemClock.uptimeMillis() - overlaySince
        val a = (t / fadeMs().toFloat()).coerceIn(0f, 1f)
        paint.style = Paint.Style.FILL; paint.color = Color.argb((a * 255).toInt(), 0, 0, 0)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        val quiet = isQuietHours()
        text.textAlign = Paint.Align.CENTER
        val hint = "Press any button to wake"
        val hintAlpha = hintAlpha(t / (if (overlay == 2) 2500f else 3000f))
        if (overlay == 1) {
            // a few pixels of drift every minute so no pixel stays lit (IDLE_SHIFT_STEPS in couch-tv)
            val steps = arrayOf(0 to 0, 2 to 2, -2 to 2, 4 to -2, -2 to -2, 2 to -4, -4 to 0, 0 to 2)
            val s = steps[((t / 60_000L) % steps.size).toInt()]
            val cx = w / 2f + s.first * u; var y = h / 2f + s.second * u
            val dim = (if (quiet) 0.35f else 0.55f) * a
            text.typeface = Fonts.antonio(ctx); text.textSize = 152 * u; text.letterSpacing = 0.03f
            text.color = Color.argb((dim * 255).toInt(), 255, 255, 255)
            y += 10 * u
            c.drawText(clockText(), cx, y, text)
            text.letterSpacing = 0f
            text.typeface = Fonts.regular(ctx); text.textSize = 24 * u
            text.color = Color.argb((dim * 0.8f * 255).toInt(), 255, 255, 255)
            c.drawText(dateText(), cx, y + 70 * u, text)
            val wx = weatherText()
            if (wx.isNotEmpty()) {
                text.textSize = 20 * u; text.color = Color.argb((dim * 0.7f * 255).toInt(), 255, 255, 255)
                c.drawText(wx, cx, y + 70 * u + 36 * u, text)
            }
            text.textSize = 16 * u; text.letterSpacing = 0.04f
            text.color = Color.argb((hintAlpha * dim * 255).toInt(), 255, 255, 255)
            c.drawText(hint, cx, y + 70 * u + 36 * u + 64 * u, text)
        } else {
            text.typeface = Fonts.regular(ctx); text.textSize = 20 * u; text.letterSpacing = 0.05f
            text.color = Color.argb((hintAlpha * 0.45f * 255).toInt(), 255, 255, 255)
            c.drawText(hint, w / 2f, h / 2f, text)
        }
        text.letterSpacing = 0f; text.textAlign = Paint.Align.LEFT
        if (a < 1f || hintAlpha > 0f && t < 3100) invalidate()
    }

    /** screenOffHintFade: in over the first fifth, hold to three quarters, then out. */
    private fun hintAlpha(p: Float): Float = when { p >= 1f -> 0f; p < 0.2f -> p / 0.2f; p < 0.75f -> 1f; else -> (1f - p) / 0.25f }

    // ------------------------------------------------------------------ soundscape

    fun soundType(): String = sound.current()
    private fun savedType() = prefs.getString("sound_type", "off") ?: "off"
    fun soundVolume(): Float = prefs.getFloat("sound_vol", 0.5f)
    private fun fadeWithSleep() = prefs.getBoolean("sound_fade", true)

    /** The quick button and the Home settings row: off, then rain, fire, waves, brown noise, then off again. */
    fun cycleSound() {
        val types = Soundscape.TYPES
        val cur = sound.current()
        val next = if (cur == "off") (if (savedType() != "off") savedType() else types[1]) else types[(types.indexOf(cur) + 1) % types.size]
        sound.play(next, soundVolume())
        if (next != "off") prefs.edit().putString("sound_type", next).apply()
        fading = false
        invalidate()
    }

    fun setSoundVolume(v: Float) { prefs.edit().putFloat("sound_vol", v).apply(); sound.setVolume(v) }
    fun stopSound() { if (sound.current() != "off") { sound.stop(); invalidate() } }

    fun soundLabel(): String = when (val t = sound.current()) {
        "off" -> "Soundscape"; "rain" -> "Gentle rain"; "fire" -> "Fireplace"; "waves" -> "Ocean waves"; else -> if (t == "brown") "Brown noise" else t
    }

    /** Quieter over the sleep timer's last 15 minutes, off when it runs out, back to full if it is cancelled or extended. */
    private fun followSleep() {
        if (sound.current() == "off" || !fadeWithSleep()) { fading = false; return }
        val ends = host?.sleepEndsAt() ?: 0L
        val now = System.currentTimeMillis()
        val m = if (ends > now) ceil((ends - now) / 60000.0).toInt() else 0
        if (ends > 0) endsSeen = ends
        if (m in 1..15) { fading = true; sound.setVolume(soundVolume() * m / 15f) }
        else if (fading) {
            fading = false
            if (m == 0 && endsSeen > 0 && now >= endsSeen - 30_000L) { sound.stop(); invalidate() } else sound.setVolume(soundVolume())
        }
    }

    companion object {
        /** The one in use, so the settings screen can reach the soundscape without a new constructor argument. */
        @Volatile var current: IdleLayer? = null
    }
}
