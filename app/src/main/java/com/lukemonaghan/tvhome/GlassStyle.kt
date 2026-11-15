package com.lukemonaghan.tvhome

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Region
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build

/**
 * Draws couch-tv's glass: panels (the dock, the cards, the people switch, the quick buttons) and glass app icon tiles,
 * in the recipe [Looks] chose. Nothing is allocated except one gradient per panel per frame.
 *
 *  - Off: the home's own plum panels (a plum tint over the blurred background, a pink hairline).
 *  - On / Lite: the clear panel: a white gradient at 160 degrees over a dark flat colour, a light edge, a highlight along
 *    the top, a soft shadow outside. Lite and the lighter home leave the blurred background out.
 */
class GlassStyle(private val looks: Looks) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val clip = Path()

    /** One panel at (l, t, r, b). [base] is the blurred background (null: nothing blurred); [px] is one 1080p pixel. */
    fun panel(c: Canvas, l: Float, t: Float, r: Float, b: Float, radius: Float, base: Bitmap?, px: Float) {
        val st = looks.panel()
        draw(c, st, l, t, r, b, radius, base, px, edgeAlpha = 66, topAlpha = 153, bottomAlpha = 26, topWidth = 2f,
            shadowDy = 24f, shadowSpread = 36f, shadowAlpha = 76)
    }

    /** A glass app icon tile. [focusStay]: the focused app stays glass, told by a bright rim, its lift and a deeper shadow. */
    fun iconTile(c: Canvas, l: Float, t: Float, r: Float, b: Float, radius: Float, base: Bitmap?, px: Float, focusStay: Boolean) {
        val st = looks.iconPanel()
        if (focusStay) draw(c, st, l, t, r, b, radius, base, px, edgeAlpha = 217, topAlpha = 191, bottomAlpha = 26, topWidth = 3f,
            shadowDy = 24f, shadowSpread = 40f, shadowAlpha = 107, edgeWidth = 2f)
        else draw(c, st, l, t, r, b, radius, base, px, edgeAlpha = 77, topAlpha = 179, bottomAlpha = 26, topWidth = 2f,
            shadowDy = 12f, shadowSpread = 28f, shadowAlpha = 77)
    }

    private fun draw(c: Canvas, st: Looks.Panel, l: Float, t: Float, r: Float, b: Float, radius: Float, base: Bitmap?, px: Float,
                     edgeAlpha: Int, topAlpha: Int, bottomAlpha: Int, topWidth: Float, shadowDy: Float, shadowSpread: Float,
                     shadowAlpha: Int, edgeWidth: Float = 1f) {
        rect.set(l, t, r, b)
        clip.reset(); clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        if ((st.rich && !st.plum) || st.muted) shadow(c, radius, shadowDy * px, shadowSpread * px, shadowAlpha)
        rect.set(l, t, r, b)
        c.save(); c.clipPath(clip)
        if (base != null && looks.blurPx > 0f) c.drawBitmap(base, 0f, 0f, null)
        paint.style = Paint.Style.FILL; paint.shader = null
        paint.color = st.flat
        c.drawRect(rect, paint)
        if (!st.plum && st.g0 + st.g42 + st.g100 > 0) {
            // CSS linear-gradient(160deg, ...): the line runs mostly down, a little to the right
            val dx = 0.342f; val dy = 0.9397f
            val len = (r - l) * dx + (b - t) * dy
            val cx = (l + r) / 2f; val cy = (t + b) / 2f
            paint.shader = LinearGradient(cx - dx * len / 2, cy - dy * len / 2, cx + dx * len / 2, cy + dy * len / 2,
                intArrayOf(Color.argb(st.g0, 255, 255, 255), Color.argb(st.g42, 255, 255, 255), Color.argb(st.g100, 255, 255, 255)),
                floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(rect, paint)
            paint.shader = null
        }
        c.restore()
        // edges
        paint.style = Paint.Style.STROKE; paint.strokeWidth = px * edgeWidth
        if (st.plum) {
            paint.color = Color.argb(41, 255, 210, 220)
            rect.set(l, t, r, b); rect.inset(px / 2, px / 2)
            c.drawRoundRect(rect, radius, radius, paint)
            paint.color = Color.argb(36, 255, 220, 230)
            c.save(); c.clipRect(l, t, r, t + radius)
            rect.set(l + px / 2, t + px * 1.5f, r - px / 2, b)
            c.drawRoundRect(rect, radius, radius, paint)
            c.restore()
        } else {
            paint.color = if (st.muted) Color.argb(28, 255, 255, 255) else Color.argb(edgeAlpha, 255, 255, 255)
            rect.set(l, t, r, b); rect.inset(px * edgeWidth / 2, px * edgeWidth / 2)
            c.drawRoundRect(rect, radius, radius, paint)
            if (st.rich) {
                paint.strokeWidth = px * topWidth
                paint.color = Color.argb(topAlpha, 255, 255, 255)   // inset 0 2px 1px -1px: a light line along the top inside edge
                c.save(); c.clipRect(l, t, r, t + radius)
                rect.set(l + px, t + px * 1.5f, r - px, b)
                c.drawRoundRect(rect, radius, radius, paint)
                c.restore()
                paint.strokeWidth = px
                paint.color = Color.argb(bottomAlpha, 255, 255, 255) // inset 0 -1px 0: a faint line along the bottom
                c.save(); c.clipRect(l, b - radius, r, b)
                rect.set(l + px, t, r - px, b - px * 1.5f)
                c.drawRoundRect(rect, radius, radius, paint)
                c.restore()
            }
        }
        paint.style = Paint.Style.FILL
    }

    /** A soft shadow outside the panel only (it must not darken the glass), as stacked rounded rects, no blur filter. */
    private fun shadow(c: Canvas, radius: Float, dy: Float, spread: Float, alpha: Int) {
        if (looks.lighter) return
        c.save()
        if (Build.VERSION.SDK_INT >= 26) c.clipOutPath(clip) else @Suppress("DEPRECATION") c.clipPath(clip, Region.Op.DIFFERENCE)
        val n = 6
        paint.style = Paint.Style.FILL; paint.shader = null
        val b = RectF(rect)
        for (i in n downTo 1) {
            val g = spread * i / n
            rect.set(b.left - g * 0.6f, b.top - g * 0.6f + dy, b.right + g * 0.6f, b.bottom + g * 0.6f + dy)
            paint.color = Color.argb(alpha / n + 1, 0, 0, 0)
            c.drawRoundRect(rect, radius + g, radius + g, paint)
        }
        rect.set(b)
        c.restore()
    }
}
