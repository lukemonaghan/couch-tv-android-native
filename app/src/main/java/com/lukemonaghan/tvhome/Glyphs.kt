package com.lukemonaghan.tvhome

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * couch-tv's line icons that the apps and inputs work needs, drawn from the same 24 unit grid as the
 * web home's SVGs. Each takes the centre and [s], pixels per grid unit, and sets its own stroke.
 */
object Glyphs {
    private val path = Path()
    private val rect = RectF()

    private fun stroke(p: Paint, s: Float, color: Int, width: Float = 1.8f) {
        p.style = Paint.Style.STROKE; p.strokeWidth = width * s; p.color = color
        p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
    }
    private fun done(p: Paint) { p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT; p.strokeJoin = Paint.Join.MITER }

    /** An HDMI plug: a trapezoid with four pins. */
    fun hdmi(c: Canvas, p: Paint, cx: Float, cy: Float, s: Float, color: Int = 0xE6FFFFFF.toInt()) {
        stroke(p, s, color)
        fun x(v: Float) = cx + (v - 12f) * s
        fun y(v: Float) = cy + (v - 12.5f) * s
        path.reset()
        path.moveTo(x(4f), y(8f)); path.lineTo(x(20f), y(8f)); path.lineTo(x(20f), y(13f)); path.lineTo(x(17f), y(17f))
        path.lineTo(x(7f), y(17f)); path.lineTo(x(4f), y(13f)); path.close()
        c.drawPath(path, p)
        for (v in floatArrayOf(8f, 11f, 13f, 16f)) c.drawLine(x(v), y(8f), x(v), y(11f), p)
        done(p)
    }

    /** A screen with an arrow down into it: the Input switch. */
    fun input(c: Canvas, p: Paint, cx: Float, cy: Float, s: Float, color: Int, width: Float = 1.8f) {
        stroke(p, s, color, width)
        fun x(v: Float) = cx + (v - 12f) * s
        fun y(v: Float) = cy + (v - 12.5f) * s
        rect.set(x(2.5f), y(4f), x(21.5f), y(17f))
        c.drawRoundRect(rect, 2.5f * s, 2.5f * s, p)
        c.drawLine(x(8f), y(21f), x(16f), y(21f), p)
        c.drawLine(x(12f), y(7.5f), x(12f), y(13.5f), p)
        path.reset(); path.moveTo(x(9.5f), y(11f)); path.lineTo(x(12f), y(13.5f)); path.lineTo(x(14.5f), y(11f))
        c.drawPath(path, p)
        done(p)
    }

    fun bluetooth(c: Canvas, p: Paint, cx: Float, cy: Float, s: Float, color: Int) {
        stroke(p, s, color, 1.7f)
        fun x(v: Float) = cx + (v - 12f) * s
        fun y(v: Float) = cy + (v - 12f) * s
        path.reset()
        path.moveTo(x(6.5f), y(7.5f)); path.lineTo(x(17.5f), y(16.5f)); path.lineTo(x(12f), y(21f)); path.lineTo(x(12f), y(3f))
        path.lineTo(x(17.5f), y(7.5f)); path.lineTo(x(6.5f), y(16.5f))
        c.drawPath(path, p)
        done(p)
    }

    /** Wi-Fi: three arcs and a dot. */
    fun network(c: Canvas, p: Paint, cx: Float, cy: Float, s: Float, color: Int) {
        stroke(p, s, color, 1.7f)
        fun x(v: Float) = cx + (v - 12f) * s
        fun y(v: Float) = cy + (v - 12f) * s
        // radius, centre height and half the angle each arc spans, from the SVG's endpoints
        val arcs = arrayOf(floatArrayOf(14f, 19.28f, 42.7f), floatArrayOf(9.5f, 19.61f, 41.5f), floatArrayOf(4.8f, 19.66f, 40.2f))
        for (a in arcs) {
            rect.set(x(12f - a[0]), y(a[1] - a[0]), x(12f + a[0]), y(a[1] + a[0]))
            c.drawArc(rect, 270f - a[2], a[2] * 2, false, p)
        }
        p.style = Paint.Style.FILL
        c.drawCircle(x(12f), y(19.3f), 1f * s, p)
        done(p)
    }
}
