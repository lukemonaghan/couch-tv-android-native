package com.lukemonaghan.tvhome

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils

/**
 * The people picker as couch-tv draws it for three or more people: a round avatar button at the top left that opens a list
 * of avatars and names. Hold OK on a name lifts it, up and down move it, OK drops it. [glass] is the home's own glass panel.
 */
class PeopleUi(private val ctx: Context, private val people: People, private val glass: (Canvas, Float, Float, Float, Float, Float) -> Unit) {
    var pmi = 0; private set
    var lifted = false; private set

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun open() { pmi = people.currentIndex(); lifted = false }
    fun up() { if (lifted) { move(-1) } else if (pmi > 0) pmi-- }
    fun down() { if (lifted) { move(1) } else if (pmi < people.count - 1) pmi++ }
    private fun move(d: Int) {
        val p = people.list().getOrNull(pmi) ?: return
        val to = pmi + d
        if (to < 0 || to >= people.count) return
        people.move(p.id, d); pmi = to
    }
    /** Hold OK: lift the name under the focus (only when there is more than one to order). */
    fun lift() { if (people.count > 1) lifted = true }
    fun drop() { lifted = false }
    fun picked(): Person? = people.list().getOrNull(pmi)

    // ------------------------------------------------------------------ the avatar

    /** A coloured disc with the first letter, as couch-tv's .avatar. [r] is the radius. */
    fun avatar(c: Canvas, cx: Float, cy: Float, r: Float, p: Person, u: Float) {
        paint.style = Paint.Style.FILL; paint.color = people.colourOf(p)
        c.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = u; paint.color = Color.argb(71, 255, 255, 255)
        c.drawCircle(cx, cy, r - u / 2, paint)
        paint.style = Paint.Style.FILL
        text.typeface = Fonts.semibold(ctx); text.color = Color.WHITE; text.textAlign = Paint.Align.CENTER; text.textSize = r * 0.9f
        c.drawText(p.name.trim().take(1).uppercase().ifEmpty { "?" }, cx, cy + r * 0.32f, text)
        text.textAlign = Paint.Align.LEFT
    }

    /** The round head button: 88 px wide, the avatar 76 px inside it, at 80, 60. */
    fun drawHead(c: Canvas, u: Float, focus: Boolean, night: Boolean) {
        val cur = people.current() ?: return
        val cx = 80 * u + 44 * u; val cy = 60 * u + 44 * u
        c.save()
        if (focus) { c.translate(cx, cy); c.scale(1.08f, 1.08f); c.translate(-cx, -cy) }
        if (focus) { paint.style = Paint.Style.FILL; paint.color = Color.WHITE; c.drawCircle(cx, cy, 47 * u, paint) }
        if (night) glass(c, 80 * u, 60 * u, 168 * u, 148 * u, 44 * u)
        else { paint.style = Paint.Style.FILL; paint.color = Color.argb(31, 255, 255, 255); c.drawCircle(cx, cy, 44 * u, paint) }
        avatar(c, cx, cy, 38 * u, cur, u)
        c.restore()
    }

    // ------------------------------------------------------------------ the list

    fun drawPop(c: Canvas, u: Float) {
        val list = people.list()
        if (list.isEmpty()) return
        val cur = people.current()?.id
        text.typeface = Fonts.semibold(ctx); text.textSize = 27 * u; text.textAlign = Paint.Align.LEFT
        val hint = if (lifted) "Up and down to move, OK to drop" else "Hold OK on a name to move it"
        val rowH = 64 * u; val gap = 4 * u; val pad = 10 * u
        val widest = list.maxOf { text.measureText(it.name) }
        val pw = maxOf(384 * u, pad * 2 + 8 * u + 48 * u + 16 * u + widest + 20 * u + 44 * u)
        val ph = pad * 2 + list.size * rowH + (list.size - 1) * gap + 38 * u
        val left = 80 * u; val top = 164 * u
        paint.style = Paint.Style.FILL; paint.color = Color.argb(170, 28, 24, 30)
        rect.set(left, top, left + pw, top + ph); c.drawRoundRect(rect, 32 * u, 32 * u, paint)
        glass(c, left, top, left + pw, top + ph, 32 * u)
        for (i in list.indices) {
            val p = list[i]
            val x = left + pad; val y = top + pad + i * (rowH + gap); val rw = pw - 2 * pad
            val focus = i == pmi; val lift = focus && lifted
            c.save()
            if (focus) {
                val s = if (lift) 1.07f else 1.04f
                c.translate(x + rw / 2 + (if (lift) 8 * u else 0f), y + rowH / 2); c.scale(s, s); c.translate(-(x + rw / 2), -(y + rowH / 2))
            }
            rect.set(x, y, x + rw, y + rowH)
            if (lift) { paint.color = Color.argb(70, 0, 0, 0); rect.offset(0f, 16 * u); c.drawRoundRect(rect, 24 * u, 24 * u, paint); rect.offset(0f, -16 * u) }
            if (focus) { paint.color = Color.WHITE; c.drawRoundRect(rect, 24 * u, 24 * u, paint) }
            avatar(c, x + 8 * u + 24 * u, y + rowH / 2, 24 * u, p, u)
            val fg = if (focus) Color.rgb(17, 17, 17) else Color.argb(224, 255, 255, 255)
            text.color = fg; text.typeface = Fonts.semibold(ctx); text.textSize = 27 * u
            val nm = TextUtils.ellipsize(p.name, text, rw - 8 * u - 48 * u - 16 * u - 20 * u - 44 * u, TextUtils.TruncateAt.END)
            c.drawText(nm, 0, nm.length, x + 8 * u + 48 * u + 16 * u, y + rowH / 2 + 9 * u, text)
            if (lift) arrows(c, x + rw - 20 * u - 14 * u, y + rowH / 2, 28 * u, fg)
            else if (p.id == cur) check(c, x + rw - 20 * u - 14 * u, y + rowH / 2, 28 * u, fg)
            c.restore()
        }
        text.typeface = Fonts.medium(ctx); text.textSize = 19 * u; text.color = Color.argb(158, 255, 255, 255)
        c.drawText(hint, left + pad + 12 * u, top + ph - pad - 4 * u - 6 * u, text)
    }

    private fun check(c: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.2f * size / 24f; paint.color = color; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
        val k = size / 24f; val x = cx - size / 2; val y = cy - size / 2
        c.drawLine(x + 5 * k, y + 12.5f * k, x + 9.5f * k, y + 17 * k, paint); c.drawLine(x + 9.5f * k, y + 17 * k, x + 19 * k, y + 7.5f * k, paint)
        paint.style = Paint.Style.FILL
    }

    private fun arrows(c: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.2f * size / 24f; paint.color = color; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
        val k = size / 24f; val x = cx - size / 2; val y = cy - size / 2
        c.drawLine(x + 8 * k, y + 4 * k, x + 8 * k, y + 20 * k, paint); c.drawLine(x + 8 * k, y + 4 * k, x + 4.5f * k, y + 8 * k, paint); c.drawLine(x + 8 * k, y + 4 * k, x + 11.5f * k, y + 8 * k, paint)
        c.drawLine(x + 16 * k, y + 20 * k, x + 16 * k, y + 4 * k, paint); c.drawLine(x + 16 * k, y + 20 * k, x + 12.5f * k, y + 16 * k, paint); c.drawLine(x + 16 * k, y + 20 * k, x + 19.5f * k, y + 16 * k, paint)
        paint.style = Paint.Style.FILL
    }
}
