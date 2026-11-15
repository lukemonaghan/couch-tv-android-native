package com.lukemonaghan.tvhome

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

/** A soft blur of a picture, for card and music backgrounds: shrunk to 12 px wide then grown to 48 px wide with smoothing, then drawn stretched (about 9 KB, no blur library). */
object ArtBlur {
    fun soft(b: Bitmap): Bitmap {
        val ar = b.height.toFloat() / b.width.coerceAtLeast(1)
        val tiny = Bitmap.createScaledBitmap(b, 12, (12 * ar).toInt().coerceAtLeast(2), true)
        val soft = Bitmap.createScaledBitmap(tiny, 48, (48 * ar).toInt().coerceAtLeast(4), true)
        if (tiny !== b) tiny.recycle()
        return soft
    }

    private val src = Rect()

    /** Draws [b] filling [dst] without stretching it: scaled up evenly and cropped from the middle, like a cover fit. */
    fun fill(c: Canvas, b: Bitmap, dst: RectF, paint: Paint) {
        val sr = b.width.toFloat() / b.height; val dr = dst.width() / dst.height()
        if (sr > dr) { val w = (b.height * dr).toInt().coerceAtLeast(1); val x = (b.width - w) / 2; src.set(x, 0, x + w, b.height) }
        else { val h = (b.width / dr).toInt().coerceAtLeast(1); val y = (b.height - h) / 2; src.set(0, y, b.width, y + h) }
        paint.isFilterBitmap = true
        c.drawBitmap(b, src, dst, paint)
    }
}
