package com.lukemonaghan.tvhome

import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * The quick buttons at the top right of the home (couch-tv's "quick" list): which exist, which the household
 * has switched on, in what order, and how each is drawn. Only the ones that need no computer: the picture mode,
 * soundscape, input and developer-mode buttons are other features or need a bridge.
 *
 * The choice is one string in the "home" prefs ("quick", ids in order, comma separated).
 */
object QuickButtons {
    /** [pill] is the label on Classic and OLED's pill buttons; [note] the line under it in Home settings. */
    class Kind(val id: String, val label: String, val pill: String, val note: String)

    val ALL = listOf(
        Kind("sleep", "Sleep timer", "Sleep timer", "Lets the TV go to sleep after 15, 30, 60 or 90 minutes"),
        Kind("look", "Look switch", "Look", "Flips between Night, OLED and Classic"),
        Kind("input", "Input list", "Input", "Opens a list of the TV's inputs"),
        Kind("soundscape", "Soundscape", "Sound", "Rain, fire, waves or brown noise, from the top row"),
        Kind("screen_off", "Screen off", "Screen off", "Turns the picture off while the sound keeps playing. Any button wakes it"),
        Kind("screensaver", "Screensaver", "Screensaver", "Starts the TV's photo screensaver now"),
        Kind("headphones", "Headphones", "Headphones", "Opens the TV's Bluetooth screen to pick headphones"),
        Kind("bluetooth", "Bluetooth devices", "Bluetooth", "Opens the TV's Bluetooth devices"),
        Kind("network", "Network", "Network", "Opens the TV's Wi-Fi and network screen"),
        Kind("tvsettings", "TV settings", "TV settings", "Opens Android's own Settings"),
    )
    private val IDS = ALL.map { it.id }

    fun kind(id: String): Kind = ALL.first { it.id == id }
    fun pill(id: String): String = ALL.firstOrNull { it.id == id }?.pill ?: id

    /** The buttons switched on, in order. The first run has the sleep timer and the look switch. */
    fun enabled(prefs: SharedPreferences): List<String> =
        (prefs.getString("quick", null) ?: "sleep,look").split(',').filter { it in IDS }.distinct()

    /** The switched-on buttons first, in order, then the rest: the order Home settings lists them in. */
    fun listed(prefs: SharedPreferences): List<String> = enabled(prefs).let { on -> on + IDS.filter { it !in on } }

    private fun save(prefs: SharedPreferences, list: List<String>) { prefs.edit().putString("quick", list.joinToString(",")).apply() }

    fun toggle(prefs: SharedPreferences, id: String) {
        val on = enabled(prefs)
        save(prefs, if (id in on) on - id else on + id)
    }

    /** Moves a switched-on button one place up (-1) or down (+1) in the row. */
    fun move(prefs: SharedPreferences, id: String, d: Int) {
        val l = enabled(prefs).toMutableList()
        val i = l.indexOf(id); val j = i + d
        if (i < 0 || j < 0 || j >= l.size) return
        l[i] = l[j]; l[j] = id
        save(prefs, l)
    }

    // ------------------------------------------------------------------ icons (couch-tv's 24 unit line icons)

    private val path = Path()
    private val box = RectF()

    private fun arcChord(cx: Float, cy: Float, r: Float, half: Float) {
        // an arc over the top, centred on (cx, cy), reaching [half] degrees either side of straight up
        box.set(cx - r, cy - r, cx + r, cy + r)
        path.reset(); path.arcTo(box, 270f - half, 2 * half)
    }

    /** Draws quick button [id] centred on ([cx], [cy]); [k] pixels per grid unit, so the icon is 24 k wide. Sleep and Look are drawn by the home itself. */
    fun icon(c: Canvas, p: Paint, id: String, cx: Float, cy: Float, k: Float, color: Int) {
        val style = p.style; val cap = p.strokeCap; val join = p.strokeJoin; val sw = p.strokeWidth
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.9f; p.color = color
        p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        c.save(); c.translate(cx - 12f * k, cy - 12f * k); c.scale(k, k)
        when (id) {
            "screen_off" -> {
                box.set(3f, 4f, 21f, 16f); c.drawRoundRect(box, 2.5f, 2.5f, p)
                c.drawLine(8f, 20f, 16f, 20f, p); c.drawLine(12f, 16f, 12f, 20f, p); c.drawLine(3f, 3f, 21f, 21f, p)
            }
            "screensaver" -> {
                box.set(2.5f, 4f, 21.5f, 18f); c.drawRoundRect(box, 2.5f, 2.5f, p)
                path.reset()
                path.moveTo(15.2f, 7.3f)
                box.set(14.36f - 3.4f, 10.59f - 3.4f, 14.36f + 3.4f, 10.59f + 3.4f); path.arcTo(box, -75.7f, -254.1f)
                box.set(18.83f - 3.9f, 8.72f - 3.9f, 18.83f + 3.9f, 8.72f + 3.9f); path.arcTo(box, 113.1f, 88.3f)
                path.close(); c.drawPath(path, p)
            }
            "headphones" -> {
                path.reset(); path.moveTo(3f, 14f); path.lineTo(3f, 12f)
                box.set(3f, 3f, 21f, 21f); path.arcTo(box, 180f, 180f); path.lineTo(21f, 14f)
                c.drawPath(path, p)
                box.set(3f, 14f, 8f, 21f); c.drawRoundRect(box, 2f, 2f, p)
                box.set(16f, 14f, 21f, 21f); c.drawRoundRect(box, 2f, 2f, p)
            }
            "bluetooth" -> {
                path.reset(); path.moveTo(6.5f, 7.5f); path.lineTo(17.5f, 16.5f); path.lineTo(12f, 21f); path.lineTo(12f, 3f)
                path.lineTo(17.5f, 7.5f); path.lineTo(6.5f, 16.5f)
                c.drawPath(path, p)
            }
            "network" -> {
                arcChord(12f, 19.28f, 14f, 42.7f); c.drawPath(path, p)
                arcChord(12f, 19.61f, 9.5f, 41.5f); c.drawPath(path, p)
                arcChord(12f, 19.66f, 4.8f, 40.2f); c.drawPath(path, p)
                p.style = Paint.Style.FILL; c.drawCircle(12f, 19.3f, 1.1f, p)
            }
            else -> {   // tvsettings: the same gear as the dock's
                c.drawCircle(12f, 12f, 3.1f, p); c.drawCircle(12f, 12f, 6.2f, p)
                for (a in 0 until 8) {
                    val t = Math.toRadians(a * 45.0)
                    c.drawLine(12f + (7.4 * Math.cos(t)).toFloat(), 12f + (7.4 * Math.sin(t)).toFloat(),
                        12f + (9.2 * Math.cos(t)).toFloat(), 12f + (9.2 * Math.sin(t)).toFloat(), p)
                }
            }
        }
        c.restore()
        p.style = style; p.strokeCap = cap; p.strokeJoin = join; p.strokeWidth = sw
    }
}
