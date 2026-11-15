package com.lukemonaghan.tvhome

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.view.KeyEvent

/**
 * couch-tv's parent PIN pad: dots for what was typed (never the digits), a 3 x 4 pad moved with the arrows, the remote's
 * number keys, and a wait after five wrong tries. [submit] gets the digits and returns null when they are accepted, or the
 * words to show when they are not. With [matchOnType] it asks after every digit from the fourth on (the kids lock opens as
 * soon as the PIN is right); OK always asks.
 */
class PinPad(
    private val ctx: Context, private val title: String, private val sub: String, private val hint: String,
    private val matchOnType: Boolean, private val limitTries: Boolean,
    private val submit: (String) -> String?, private val close: () -> Unit,
) {
    private val digits = StringBuilder()
    private var at = 0                // focused key: 0 to 11
    private var msg = ""
    private var shakeAt = 0L
    private var done = false
    private val wrongs = ArrayList<Long>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    private fun waitLeft(): Int {
        val now = System.currentTimeMillis()
        wrongs.removeAll { now - it >= 60_000 }
        return if (limitTries && wrongs.size >= 5) Math.ceil((60_000 - (now - wrongs[0])) / 1000.0).toInt() else 0
    }

    private fun typed(d: Char) {
        if (done || waitLeft() > 0 || digits.length >= 8) return
        digits.append(d); msg = ""
        if (matchOnType && digits.length >= 4 && submit(digits.toString()) == null) done = true
    }

    private fun confirm() {
        if (done || waitLeft() > 0 || digits.isEmpty()) return
        val err = submit(digits.toString())
        if (err == null) { done = true; return }
        if (limitTries) wrongs.add(System.currentTimeMillis())
        digits.clear(); msg = err; shakeAt = System.currentTimeMillis()
    }

    private fun press() {
        when (at) {
            9 -> { if (digits.isNotEmpty()) digits.deleteCharAt(digits.length - 1); msg = "" }
            11 -> confirm()
            else -> typed(if (at == 10) '0' else ('1' + at))
        }
    }

    /** True when the key was ours. Back closes the pad. */
    fun key(code: Int): Boolean {
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (at % 3 > 0) at--
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (at % 3 < 2) at++
            KeyEvent.KEYCODE_DPAD_UP -> if (at >= 3) at -= 3
            KeyEvent.KEYCODE_DPAD_DOWN -> if (at < 9) at += 3
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> press()
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> { if (digits.isNotEmpty()) digits.deleteCharAt(digits.length - 1); msg = "" }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> close()
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> typed('0' + (code - KeyEvent.KEYCODE_0))
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> typed('0' + (code - KeyEvent.KEYCODE_NUMPAD_0))
            else -> return false
        }
        return true
    }

    /** The wait after five wrong PINs counts down: ask for another draw while it does. */
    fun waiting() = waitLeft() > 0

    /** True while the wrong-PIN shake plays or the wait counts down, so the home keeps redrawing. */
    fun animating() = System.currentTimeMillis() - shakeAt < 450 || waitLeft() > 0

    // ------------------------------------------------------------------ drawing (couch-tv's .kpin, 1 rem = 16 px at 1080p)

    fun draw(c: Canvas, w: Int, h: Int) {
        val u = h / 1080f; val rem = 16 * u
        paint.style = Paint.Style.FILL; paint.shader = null
        paint.color = Color.argb(184, 6, 8, 18)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

        val cardW = 34 * rem; val cardH = 45.6f * rem
        val cx = w / 2f
        var top = (h - cardH) / 2f
        val shake = System.currentTimeMillis() - shakeAt
        val dx = if (shake in 0..420) (Math.sin(shake / 420.0 * Math.PI * 4) * 12 * u * (1 - shake / 420.0)).toFloat() else 0f
        rect.set(cx - cardW / 2, top, cx + cardW / 2, top + cardH)
        paint.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, Color.rgb(42, 47, 82), Color.rgb(29, 33, 64), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, 40 * u, 40 * u, paint); paint.shader = null
        paint.style = Paint.Style.STROKE; paint.strokeWidth = u; paint.color = Color.argb(31, 255, 255, 255)
        rect.inset(u / 2, u / 2); c.drawRoundRect(rect, 40 * u, 40 * u, paint)
        paint.style = Paint.Style.FILL

        var y = top + 44 * u
        lock(c, cx, y, 48 * u, Color.argb(158, 245, 245, 247)); y += 48 * u + 16 * u
        text.textAlign = Paint.Align.CENTER
        text.typeface = Fonts.semibold(ctx); text.color = Color.rgb(245, 245, 247); text.textSize = 36 * u
        c.drawText(title, cx, y + 30 * u, text); y += 43 * u + 8 * u
        text.typeface = Fonts.regular(ctx); text.color = Color.argb(158, 245, 245, 247); text.textSize = 22 * u
        c.drawText(sub, cx, y + 20 * u, text); y += 30 * u

        // the dots: four to start, one more for each digit past four
        y += 28 * u
        val n = maxOf(4, digits.length)
        val step = 20 * u + 16 * u
        var x = cx - n * step / 2f + step / 2f + dx
        for (i in 0 until n) {
            if (i < digits.length) { paint.style = Paint.Style.FILL; paint.color = Color.WHITE; c.drawCircle(x, y + 10 * u, 10 * u, paint) }
            else { paint.style = Paint.Style.STROKE; paint.strokeWidth = 3 * u; paint.color = Color.argb(115, 255, 255, 255); c.drawCircle(x, y + 10 * u, 8.5f * u, paint) }
            x += step
        }
        paint.style = Paint.Style.FILL
        y += 20 * u + 24 * u

        // the pad: 120 x 80 keys, 6 px around each
        val kw = 120 * u; val kh = 80 * u; val m = 6 * u
        val gridL = cx - (3 * (kw + 2 * m)) / 2f
        for (i in 0 until 12) {
            val kx = gridL + (i % 3) * (kw + 2 * m) + m; val ky = y + (i / 3) * (kh + 2 * m) + m
            val focus = i == at
            val sc = if (focus) 1.08f else 1f
            c.save(); c.translate(kx + kw / 2, ky + kh / 2); c.scale(sc, sc); c.translate(-(kx + kw / 2), -(ky + kh / 2))
            rect.set(kx, ky, kx + kw, ky + kh)
            paint.color = if (focus) Color.WHITE else Color.argb(23, 255, 255, 255)
            c.drawRoundRect(rect, 20 * u, 20 * u, paint)
            val fg = if (focus) Color.rgb(17, 17, 17) else if (i == 9 || i == 11) Color.argb(158, 245, 245, 247) else Color.rgb(245, 245, 247)
            when (i) {
                9 -> glyph(c, kx + kw / 2, ky + kh / 2, 36 * u, fg, DEL)
                11 -> glyph(c, kx + kw / 2, ky + kh / 2, 36 * u, fg, OK)
                else -> { text.typeface = Fonts.semibold(ctx); text.textSize = 36 * u; text.color = fg
                          c.drawText(if (i == 10) "0" else ('1' + i).toString(), kx + kw / 2, ky + kh / 2 + 13 * u, text) }
            }
            c.restore()
        }
        y += 4 * (kh + 2 * m)

        val wait = waitLeft()
        text.typeface = Fonts.regular(ctx); text.textSize = 22 * u; text.color = Color.rgb(255, 159, 138)
        val line = if (wait > 0) "Too many tries. Wait $wait seconds." else msg
        c.drawText(line, cx, y + 8 * u + 22 * u, text); y += 8 * u + 28 * u
        text.textSize = 18 * u; text.color = Color.argb(107, 245, 245, 247)
        c.drawText(hint, cx, y + 4 * u + 18 * u, text)
        text.textAlign = Paint.Align.LEFT
    }

    // ------------------------------------------------------------------ icons on couch-tv's 24 unit grid

    private fun lock(c: Canvas, cx: Float, top: Float, size: Float, color: Int) {
        val k = size / 24f
        c.save(); c.translate(cx - size / 2, top); c.scale(k, k)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.8f; paint.color = color; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
        rect.set(5f, 10.5f, 19f, 20.5f); c.drawRoundRect(rect, 2.5f, 2.5f, paint)
        path.reset(); path.moveTo(8.5f, 10.5f); path.lineTo(8.5f, 7.5f); rect.set(8.5f, 4f, 15.5f, 11f); path.arcTo(rect, 180f, 180f); path.lineTo(15.5f, 10.5f)
        c.drawPath(path, paint)
        c.restore(); paint.style = Paint.Style.FILL
    }

    private fun glyph(c: Canvas, cx: Float, cy: Float, size: Float, color: Int, which: Int) {
        val k = size / 24f
        c.save(); c.translate(cx - size / 2, cy - size / 2); c.scale(k, k)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.8f; paint.color = color; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
        path.reset()
        if (which == DEL) {
            path.moveTo(9f, 5f); path.lineTo(19.5f, 5f); path.quadTo(21f, 5f, 21f, 6.5f); path.lineTo(21f, 17.5f); path.quadTo(21f, 19f, 19.5f, 19f); path.lineTo(9f, 19f); path.lineTo(3f, 12f); path.close()
            path.moveTo(12.5f, 9.5f); path.lineTo(17.5f, 14.5f); path.moveTo(17.5f, 9.5f); path.lineTo(12.5f, 14.5f)
        } else { path.moveTo(5f, 12.5f); path.lineTo(9.5f, 17f); path.lineTo(19f, 7.5f) }
        c.drawPath(path, paint)
        c.restore(); paint.style = Paint.Style.FILL
    }

    private companion object { const val DEL = 1; const val OK = 2 }
}
