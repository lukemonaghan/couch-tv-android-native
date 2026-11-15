package com.lukemonaghan.tvhome

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils

/**
 * The small things drawn over the home: the focused show's big title (Classic and OLED), the hold-OK menu on a
 * Keep watching card, the quick button note and the "Removed" toast, and the black Screen off cover. Sizes and
 * colours are couch-tv's (app.css: .hero, .app-pop and .grow, .quick-note, .toast, .screen-off-overlay).
 * Own paints, so the home's stay as they are. `u` is height / 1080.
 */
class HomeOverlays(private val ctx: Context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    /** The focused show's title and its app and episode, top left, under the people switch ([top] is where the block starts). */
    fun drawHero(c: Canvas, u: Float, top: Float, title: String, sub: String) {
        val maxW = 992 * u   // 62 rem
        text.textAlign = Paint.Align.LEFT; text.letterSpacing = -0.025f
        text.typeface = Fonts.semibold(ctx); text.textSize = 60 * u; text.color = Color.WHITE
        text.setShadowLayer(24 * u, 0f, 2 * u, Color.argb(89, 0, 0, 0))
        val t = TextUtils.ellipsize(title, text, maxW, TextUtils.TruncateAt.END)
        c.drawText(t, 0, t.length, 80 * u, top + 56 * u, text)
        text.letterSpacing = 0f
        if (sub.isNotEmpty()) {
            text.typeface = Fonts.regular(ctx); text.textSize = 29 * u; text.color = Color.argb(204, 255, 255, 255)
            val s = TextUtils.ellipsize(sub, text, maxW, TextUtils.TruncateAt.END)
            c.drawText(s, 0, s.length, 80 * u, top + 108 * u, text)
        }
        text.clearShadowLayer(); text.color = Color.WHITE
    }

    /**
     * The floating menu over a card: rows 62 tall, 28 px of room after the words, 10 px round the lot, 32 px corners,
     * above the card's top by 36 px (kept on screen). [anchorX] is the card's centre, [anchorTop] its top edge.
     */
    fun drawMenu(c: Canvas, u: Float, w: Int, anchorX: Float, anchorTop: Float, labels: List<String>, pick: Int) {
        text.textAlign = Paint.Align.LEFT; text.typeface = Fonts.semibold(ctx); text.textSize = 27 * u
        val inner = labels.maxOf { text.measureText(it) }
        val mw = (inner + 14 * u + 28 * u + 20 * u).coerceAtMost(672 * u)
        val rowH = 62 * u; val gap = 4 * u
        val mh = labels.size * rowH + (labels.size - 1) * gap + 20 * u
        val l = (anchorX - mw / 2).coerceIn(80 * u, w - mw - 80 * u)
        val t = maxOf(60 * u, anchorTop - mh - 36 * u)
        paint.style = Paint.Style.FILL
        // the soft shadow under it
        for (i in 7 downTo 1) {
            val g = 30 * u * i / 7
            paint.color = Color.argb(14, 0, 0, 0)
            rect.set(l - g, t - g + 24 * u, l + mw + g, t + mh + g + 24 * u)
            c.drawRoundRect(rect, 32 * u + g, 32 * u + g, paint)
        }
        rect.set(l, t, l + mw, t + mh)
        paint.color = Color.argb(247, 28, 26, 34); c.drawRoundRect(rect, 32 * u, 32 * u, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1 * u; paint.color = Color.argb(41, 255, 220, 230)
        c.drawRoundRect(rect, 32 * u, 32 * u, paint); paint.style = Paint.Style.FILL
        for (i in labels.indices) {
            val y = t + 10 * u + i * (rowH + gap)
            val focus = i == pick
            if (focus) {
                paint.color = Color.WHITE
                rect.set(l + 10 * u - 5 * u, y - 2 * u, l + mw - 10 * u + 5 * u, y + rowH + 2 * u)   // scale(1.04)
                c.drawRoundRect(rect, 24 * u, 24 * u, paint)
            }
            text.color = if (focus) Color.rgb(17, 17, 17) else Color.argb(235, 255, 255, 255)
            val s = TextUtils.ellipsize(labels[i], text, mw - 20 * u - 42 * u, TextUtils.TruncateAt.END)
            c.drawText(s, 0, s.length, l + 10 * u + 14 * u, y + 41 * u, text)
        }
        text.color = Color.WHITE
    }

    /** A failed quick button, in couch-tv's warning colour just under the buttons, right aligned. */
    fun drawNote(c: Canvas, u: Float, rightX: Float, baseline: Float, note: String) {
        text.textAlign = Paint.Align.RIGHT; text.typeface = Fonts.regular(ctx); text.textSize = 23 * u; text.color = Color.rgb(255, 159, 138)
        text.letterSpacing = 0f
        c.drawText(note, rightX, baseline, text)
        text.textAlign = Paint.Align.LEFT; text.color = Color.WHITE
    }

    /** A short confirmation in the middle at the top. */
    fun drawToast(c: Canvas, u: Float, w: Int, message: String) {
        text.textAlign = Paint.Align.LEFT; text.typeface = Fonts.semibold(ctx); text.textSize = 23 * u; text.color = Color.WHITE
        val s = TextUtils.ellipsize(message, text, 704 * u - 48 * u, TextUtils.TruncateAt.END)
        val tw = text.measureText(s, 0, s.length)
        val pw = tw + 48 * u; val ph = 60 * u
        val l = (w - pw) / 2f; val t = 32 * u
        paint.style = Paint.Style.FILL
        for (i in 5 downTo 1) {
            val g = 22 * u * i / 5
            paint.color = Color.argb(18, 0, 0, 0)
            rect.set(l - g, t - g + 16 * u, l + pw + g, t + ph + g + 16 * u)
            c.drawRoundRect(rect, 32 * u + g, 32 * u + g, paint)
        }
        rect.set(l, t, l + pw, t + ph)
        paint.color = Color.argb(219, 16, 16, 20); c.drawRoundRect(rect, 32 * u, 32 * u, paint)
        c.drawText(s, 0, s.length, l + 24 * u, t + 39 * u, text)
    }

    /**
     * The Screen off cover: black, with "Press any button to wake" fading in and out over three seconds, as in couch-tv.
     * [age] is milliseconds since it came on. True while the hint is still moving, so the home asks for another frame.
     */
    fun drawScreenOff(c: Canvas, w: Int, h: Int, u: Float, age: Long, hint: String): Boolean {
        c.drawColor(Color.BLACK)
        val p = (age / 3000f).coerceIn(0f, 1f)
        if (p >= 1f) return false
        // keyframes: 0% hidden and 4 px low, 20% shown, 75% shown, 100% hidden
        val a = when { p < 0.2f -> p / 0.2f; p < 0.75f -> 1f; else -> 1f - (p - 0.75f) / 0.25f }
        val dy = if (p < 0.2f) 4 * u * (1f - p / 0.2f) else 0f
        text.textAlign = Paint.Align.CENTER; text.typeface = Fonts.regular(ctx); text.textSize = 20 * u
        text.letterSpacing = 0.05f; text.color = Color.argb((115 * a).toInt(), 255, 255, 255)
        c.drawText(hint, w / 2f, h / 2f + 7 * u + dy, text)
        text.letterSpacing = 0f; text.textAlign = Paint.Align.LEFT; text.color = Color.WHITE
        return true
    }
}
