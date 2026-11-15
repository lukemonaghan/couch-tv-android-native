package com.lukemonaghan.tvhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader

/**
 * The blurred copy of the home's background that glass panels are cut out of (CSS backdrop-filter: blur, saturate, brightness).
 * No blur library: the photo is decoded small and stretched back up, which smooths like a blur of [blurPx] pixels
 * (a 1/16 size copy is the 28 px blur of couch-tv's own panels). Costs one RGB_565 screen, about 4 MB, while it is held.
 *
 * [source] is an asset path ("scenes/scene-night.jpg") or "file:/absolute/path" for the household's own photo.
 * [scrim] is the home's own shade between the photo and the glass: 0 none, 1 Night, 2 Classic.
 */
object GlassBase {
    fun make(ctx: Context, source: String, w: Int, h: Int, dim: Boolean, photoBrightness: Float,
             blurPx: Float, saturation: Float, brightness: Float, scrim: Int): Bitmap? {
        if (blurPx <= 0f) return null
        val f = maxOf(1f, blurPx * 0.5714f)                  // 28 px -> 16
        var s = 1; while (s * 2 <= f && s < 16) s *= 2
        val g = open(ctx, source, s) ?: return null
        val fullW = g.width * s
        val tw = maxOf(8, (fullW / f).toInt())
        val th = maxOf(8, (g.height.toLong() * tw / g.width).toInt())
        val down = if (tw < g.width) Bitmap.createScaledBitmap(g, tw, th, true) else g
        val up = Bitmap.createScaledBitmap(down, down.width * 4, down.height * 4, true)   // a gentler second step
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val cm = ColorMatrix().apply { setSaturation(saturation) }
        val b = brightness * (if (dim) 0.72f else photoBrightness)
        cm.postConcat(ColorMatrix(floatArrayOf(b, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
        val c = Canvas(out)
        c.drawBitmap(up, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(cm) })
        val hh = h.toFloat(); val ww = w.toFloat()
        when (scrim) {
            1 -> c.drawRect(0f, hh * 0.6f, ww, hh, Paint().apply {
                shader = LinearGradient(0f, hh * 0.6f, 0f, hh, 0x00000000, 0x80000000.toInt(), Shader.TileMode.CLAMP) })
            2 -> {
                c.drawRect(0f, 0f, ww, hh, Paint().apply {
                    shader = LinearGradient(0f, 0f, 0f, hh, intArrayOf(0x80000000.toInt(), 0, 0), floatArrayOf(0f, 0.26f, 1f), Shader.TileMode.CLAMP) })
                c.drawRect(0f, 0f, ww, hh, Paint().apply {
                    shader = LinearGradient(0f, 0f, 0f, hh, intArrayOf(0, 0, 0x80000000.toInt(), 0xDB000000.toInt()), floatArrayOf(0f, 0.32f, 0.55f, 1f), Shader.TileMode.CLAMP) })
            }
        }
        if (down !== g) g.recycle()
        down.recycle(); up.recycle()
        return out
    }

    /** An asset or a file, decoded at 1/[sample] size in RGB_565. */
    fun open(ctx: Context, source: String, sample: Int): Bitmap? = runCatching {
        val o = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }
        if (source.startsWith("file:")) BitmapFactory.decodeFile(source.removePrefix("file:"), o)
        else ctx.assets.open(source).use { BitmapFactory.decodeStream(it, null, o) }
    }.getOrNull()
}
