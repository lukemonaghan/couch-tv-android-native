package com.lukemonaghan.tvhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface

/**
 * One Night scene drawn into one bitmap: the photo, the clock as a piece of frosted glass (the blurred copy of
 * the photo cut to the digit shapes, with a sheen and a rim), the cut-out of everything in front of the sky
 * (the peaks overlap the digits) and the date. Same layers and numbers as couch-tv's web home, drawn on a Canvas.
 *
 * It is rendered off the main thread once a minute and the home just blits the result, so a key press never
 * pays for it. The layers are decoded, used and dropped each time: about 20 MB for a moment, nothing kept.
 */
object SceneRenderer {
    /** A scene's look. Sizes are in 1080p pixels (couch-tv's CSS rem is 16 px there) and scale with the screen. */
    class Spec(
        val photo: String,
        val hasCutout: Boolean,
        val clockTop: Float, val clockSize: Float, val dateTop: Float, val dateColor: Int,
        val photoBrightness: Float,
        val fillBrightness: Float, val fillSaturation: Float, val fillAlpha: Float,
        val rimStroke: Int, val rimFill: Int,
    )

    /** `.s-night` and `.scene-night` in couch-tv's app.css. */
    val NIGHT = Spec(
        photo = "scene-night", hasCutout = true,
        clockTop = 150f, clockSize = 400f, dateTop = 96f, dateColor = Color.argb(235, 255, 232, 238),
        photoBrightness = 0.86f,
        fillBrightness = 1.15f, fillSaturation = 1.2f, fillAlpha = 0.9f,
        rimStroke = Color.argb(128, 255, 215, 225), rimFill = Color.argb(15, 255, 200, 215),
    )

    fun font(ctx: Context): Typeface = Fonts.antonio(ctx)

    /** Draws the scene at [w] x [h]. Call from a background thread. [time] is the clock text, [date] the date line. */
    /** After 9 pm and before 6 am couch-tv dims the photo to 72% and the clock with it. */
    fun isDim(hour: Int) = hour >= 21 || hour < 6

    /** The blurred photo with the glass panels' saturation, for the dock and cards to be cut out of. */
    fun glassBase(ctx: Context, spec: Spec, w: Int, h: Int, dim: Boolean): Bitmap? {
        // blur by shrinking: the photo decoded at 1/16 size and stretched back up smooths like a 28 px blur
        val g = runCatching {
            ctx.assets.open("scenes/${spec.photo}.jpg").use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 16; inPreferredConfig = Bitmap.Config.RGB_565 })
            }
        }.getOrNull() ?: return null
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val cm = ColorMatrix().apply { setSaturation(1.7f) }
        val b = 1.06f * (if (dim) 0.72f else spec.photoBrightness)
        cm.postConcat(ColorMatrix(floatArrayOf(b, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
        val small = Bitmap.createScaledBitmap(g, g.width * 4, g.height * 4, true)   // a gentler second step
        val gc = Canvas(out)
        gc.drawBitmap(small, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(cm) })
        // the home's own scrim sits between the photo and the glass: clear at 60% of the height, half black at the bottom
        gc.drawRect(0f, h * 0.6f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, h * 0.6f, 0f, h.toFloat(), 0x00000000, 0x80000000.toInt(), Shader.TileMode.CLAMP) })
        g.recycle(); small.recycle()
        return out
    }

    fun render(ctx: Context, spec: Spec, time: String, date: String, wx: String, w: Int, h: Int, dim: Boolean): Bitmap {
        val s = h / 1080f
        val photoB = if (dim) 0.72f else spec.photoBrightness
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val c = Canvas(out)
        val full = Rect(0, 0, w, h)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // photo
        decode(ctx, "scenes/${spec.photo}.jpg", Bitmap.Config.RGB_565)?.let { bg ->
            p.colorFilter = brightness(photoB)
            c.drawBitmap(bg, null, full, p)
            bg.recycle()
        } ?: c.drawColor(Color.parseColor("#0D1E19"))
        p.colorFilter = null

        // the clock: where couch-tv puts it. CSS line-height .82 with the glyphs centred in that box.
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = font(ctx)
            textSize = spec.clockSize * s
            letterSpacing = -0.02f
            textAlign = Paint.Align.CENTER
        }
        val fm = t.fontMetrics
        val lineH = 0.82f * t.textSize
        val baseline = spec.clockTop * s + (lineH - (fm.descent - fm.ascent)) / 2f - fm.ascent
        val cx = w / 2f
        val tb = Rect()
        t.getTextBounds(time, 0, time.length, tb)
        val half = t.measureText(time) / 2f   // getTextBounds starts at x = 0 whatever the alignment, so centre by the measured width
        val box = RectF(cx - half - 40 * s, baseline + fm.ascent - 20 * s, cx + half + 40 * s, baseline + fm.descent + 20 * s)

        val clockLayer = c.saveLayer(null, if (dim) Paint().apply { colorFilter = brightness(0.72f) } else null)

        // glass fill: the blurred copy of the photo, cut to the digits (a layer, masked with SRC_IN)
        decode(ctx, "scenes/${spec.photo}-glass.jpg", Bitmap.Config.RGB_565)?.let { glass ->
            val layer = c.saveLayer(box, null)
            t.style = Paint.Style.FILL; t.color = Color.WHITE
            c.drawText(time, cx, baseline, t)
            val mask = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
                colorFilter = fill(spec.fillBrightness, spec.fillSaturation)
                alpha = (spec.fillAlpha * 255).toInt()
            }
            c.drawBitmap(glass, null, full, mask)
            c.restoreToCount(layer)
            glass.recycle()
        }

        // sheen: a diagonal highlight across the digits
        run {
            val layer = c.saveLayer(box, null)
            t.style = Paint.Style.FILL; t.color = Color.WHITE
            c.drawText(time, cx, baseline, t)
            val sheen = Paint().apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
                shader = LinearGradient(box.left, box.top, box.right, box.bottom,
                    intArrayOf(Color.argb(140, 255, 255, 255), Color.argb(0, 255, 255, 255), Color.argb(0, 255, 255, 255), Color.argb(56, 255, 255, 255)),
                    floatArrayOf(0f, 0.38f, 0.7f, 1f), Shader.TileMode.CLAMP)
            }
            c.drawRect(box, sheen)
            c.restoreToCount(layer)
        }

        // rim: a faint fill and a thin pink-white edge
        t.style = Paint.Style.FILL; t.color = spec.rimFill
        c.drawText(time, cx, baseline, t)
        t.style = Paint.Style.STROKE; t.strokeWidth = 1.44f * s; t.color = spec.rimStroke
        c.drawText(time, cx, baseline, t)

        c.restoreToCount(clockLayer)

        // everything in front of the sky, over the digits
        if (spec.hasCutout) decode(ctx, "scenes/${spec.photo}-fg.webp", Bitmap.Config.ARGB_8888)?.let { fg ->
            p.colorFilter = brightness(photoB)
            c.drawBitmap(fg, null, full, p)
            fg.recycle()
        }
        p.colorFilter = null

        // the date, then the weather a little dimmer. Inter 500 at 34 px, centred as one line.
        val d = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Fonts.medium(ctx); textSize = 34 * s; color = spec.dateColor
            setShadowLayer(18 * s, 0f, 2 * s, Color.argb(64, 0, 0, 0))
        }
        val wp = Paint(d).apply { typeface = Fonts.regular(ctx); color = Color.argb(199, 255, 232, 238) }
        val gap = if (wx.isEmpty()) 0f else 24 * s
        val dw = d.measureText(date); val ww = if (wx.isEmpty()) 0f else wp.measureText(wx)
        val x0 = (w - (dw + gap + ww)) / 2f
        val by = spec.dateTop * s + 30 * s + 2 * s
        c.drawText(date, x0, by, d)
        if (wx.isNotEmpty()) c.drawText(wx, x0 + dw + gap, by, wp)
        return out
    }

    /** The Classic look's background: the scene photo alone, dimmed so the app tiles stand out. */
    fun photoOnly(ctx: Context, spec: Spec, w: Int, h: Int, dim: Float = 0.55f): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val c = Canvas(out)
        val bg = decode(ctx, "scenes/${spec.photo}.jpg", Bitmap.Config.RGB_565)
        if (bg == null) { c.drawColor(Color.parseColor("#0D1E19")); return out }
        c.drawBitmap(bg, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = brightness(dim) })
        bg.recycle()
        return out
    }

    private fun decode(ctx: Context, path: String, cfg: Bitmap.Config): Bitmap? = runCatching {
        ctx.assets.open(path).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inPreferredConfig = cfg }) }
    }.getOrNull()

    private fun brightness(b: Float) = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
        b, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))

    private fun fill(brightness: Float, saturation: Float) = ColorMatrixColorFilter(ColorMatrix().apply {
        setSaturation(saturation)
        postConcat(ColorMatrix(floatArrayOf(
            brightness, 0f, 0f, 0f, 0f, 0f, brightness, 0f, 0f, 0f, 0f, 0f, brightness, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
    })
}
