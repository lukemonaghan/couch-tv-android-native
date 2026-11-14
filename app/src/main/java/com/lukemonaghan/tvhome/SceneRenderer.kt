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
    /** A scene's look: `.s-<id>` and `.scene-<id>` in couch-tv's app.css. Sizes are 1080p pixels (CSS 1 rem = 16 px) and scale with the screen. */
    class Spec(
        val id: String, val label: String, val photo: String, val hasCutout: Boolean,
        val clockTop: Float, val clockSize: Float, val clockLeft: Float? = null,   // clockLeft null: centred
        val dateTop: Float, val dateLeft: Float? = null,
        val dateColor: Int = Color.WHITE, val wxColor: Int = Color.argb(199, 255, 255, 255), val dateShadow: Boolean = true,
        val photoBrightness: Float = 1f,
        val fillBrightness: Float = 1f, val fillSaturation: Float = 1f, val fillContrast: Float = 1f, val fillAlpha: Float = 1f,
        val rimStroke: Int = Color.argb(140, 255, 255, 255), val rimFill: Int = Color.argb(26, 255, 255, 255),
        val sheen: Boolean = true,
        val shade: IntArray? = null, val shadePos: FloatArray? = null,   // a gradient laid over the photo, under the clock
        val water: Float = 0f,   // the waterline as a share of the screen height: sharp clock above, a reflection below
    )

    val NIGHT = Spec("night", "Night", "scene-night", true, clockTop = 150f, clockSize = 400f, dateTop = 96f,
        dateColor = Color.argb(235, 255, 232, 238), wxColor = Color.argb(199, 255, 232, 238), photoBrightness = 0.86f,
        fillBrightness = 1.15f, fillSaturation = 1.2f, fillAlpha = 0.9f,
        rimStroke = Color.argb(128, 255, 215, 225), rimFill = Color.argb(15, 255, 200, 215))
    val GOLDEN = Spec("golden", "Golden hour", "scene-golden", true, clockTop = 152f, clockSize = 390f, dateTop = 106f)
    val WATER = Spec("water", "Waterline", "scene-water", false, clockTop = 268f, clockSize = 420f, dateTop = 216f, water = 0.55f,
        shade = intArrayOf(Color.argb(107, 0, 12, 24), Color.argb(31, 0, 12, 24), Color.argb(0, 0, 0, 0), Color.argb(128, 0, 20, 40)),
        shadePos = floatArrayOf(0f, 0.4f, 0.52f, 1f))
    val FOG = Spec("fog", "Fog", "scene-fog", true, clockTop = 172f, clockSize = 470f, clockLeft = 96f, dateTop = 116f, dateLeft = 104f,
        dateColor = Color.rgb(22, 32, 29), wxColor = Color.argb(184, 22, 32, 29), dateShadow = false,
        fillBrightness = 0.46f, fillSaturation = 0.8f, fillContrast = 1.1f, fillAlpha = 0.8f,
        rimStroke = Color.argb(153, 255, 255, 255), rimFill = Color.TRANSPARENT, sheen = false)
    val DAWN = Spec("dawn", "Rainforest dawn", "scene-dawn", true, clockTop = 230f, clockSize = 400f, dateTop = 176f,
        shade = intArrayOf(Color.argb(107, 50, 28, 12), Color.argb(0, 50, 28, 12)), shadePos = floatArrayOf(0f, 0.55f))

    /** The scenes in couch-tv's order. */
    val ALL = listOf(NIGHT, GOLDEN, WATER, FOG, DAWN)

    fun font(ctx: Context): Typeface = Fonts.antonio(ctx)

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

        // photo, then the scene's shade over it
        decode(ctx, "scenes/${spec.photo}.jpg", Bitmap.Config.RGB_565)?.let { bg ->
            p.colorFilter = brightness(photoB)
            c.drawBitmap(bg, null, full, p)
            bg.recycle()
        } ?: c.drawColor(Color.parseColor("#0D1E19"))
        p.colorFilter = null
        spec.shade?.let { colors ->
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, 0f, h.toFloat(), colors, spec.shadePos, Shader.TileMode.CLAMP) })
        }

        // the clock: CSS line-height .82 with the glyphs centred in that box
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = font(ctx); textSize = spec.clockSize * s; letterSpacing = -0.02f
            textAlign = if (spec.clockLeft == null) Paint.Align.CENTER else Paint.Align.LEFT
        }
        val fm = t.fontMetrics
        val baseline = spec.clockTop * s + (0.82f * t.textSize - (fm.descent - fm.ascent)) / 2f - fm.ascent
        val tw = t.measureText(time)
        val tx = spec.clockLeft?.let { it * s } ?: (w / 2f)
        val left = if (spec.clockLeft == null) tx - tw / 2f else tx
        val box = RectF(left - 40 * s, baseline + fm.ascent - 20 * s, left + tw + 40 * s, baseline + fm.descent + 20 * s)
        val glass = decode(ctx, "scenes/${spec.photo}-glass.jpg", Bitmap.Config.RGB_565)

        val dimPaint = if (dim) Paint().apply { colorFilter = brightness(0.72f) } else null
        if (spec.water > 0f) {
            val line = h * spec.water
            // above the waterline the clock is sharp; below it a copy shows like a reflection, a little to the right
            c.save(); c.clipRect(0f, 0f, w.toFloat(), line)
            val l1 = c.saveLayer(null, dimPaint)
            drawGlassClock(c, spec, t, time, tx, baseline, box, glass, s, 1f, spec.fillSaturation, spec.rimStroke)
            c.restoreToCount(l1); c.restore()
            c.save(); c.clipRect(0f, line, w.toFloat(), h.toFloat())
            val l2 = c.saveLayer(null, Paint().apply { alpha = (0.78f * 255).toInt(); if (dim) colorFilter = brightness(0.72f) })
            c.translate(5 * s, 0f)
            drawGlassClock(c, spec, t, time, tx, baseline, box, glass, s, 1f, 1.4f, Color.argb(128, 170, 240, 255))
            c.restoreToCount(l2); c.restore()
        } else {
            val l1 = c.saveLayer(null, dimPaint)
            drawGlassClock(c, spec, t, time, tx, baseline, box, glass, s, 1f, spec.fillSaturation, spec.rimStroke)
            c.restoreToCount(l1)
        }
        glass?.recycle()

        // everything in front of the sky, over the digits
        if (spec.hasCutout) decode(ctx, "scenes/${spec.photo}-fg.webp", Bitmap.Config.ARGB_8888)?.let { fg ->
            p.colorFilter = brightness(photoB)
            c.drawBitmap(fg, null, full, p)
            fg.recycle()
        }
        p.colorFilter = null

        // the date, then the weather a little dimmer. Inter 500 at 34 px: centred, or from the left on Fog.
        val d = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Fonts.medium(ctx); textSize = 34 * s; color = spec.dateColor
            if (spec.dateShadow) setShadowLayer(18 * s, 0f, 2 * s, Color.argb(64, 0, 0, 0))
        }
        val wp = Paint(d).apply { typeface = Fonts.regular(ctx); color = spec.wxColor }
        val gap = if (wx.isEmpty()) 0f else 24 * s
        val dw = d.measureText(date); val ww = if (wx.isEmpty()) 0f else wp.measureText(wx)
        val x0 = spec.dateLeft?.let { it * s } ?: ((w - (dw + gap + ww)) / 2f)
        val by = spec.dateTop * s + 30 * s + 2 * s
        c.drawText(date, x0, by, d)
        if (wx.isNotEmpty()) c.drawText(wx, x0 + dw + gap, by, wp)
        return out
    }

    /** One glass clock: the frosted fill, the sheen, the rim. */
    private fun drawGlassClock(c: Canvas, spec: Spec, t: Paint, time: String, x: Float, baseline: Float, box: RectF,
                               glass: Bitmap?, s: Float, @Suppress("UNUSED_PARAMETER") unused: Float, sat: Float, rimStroke: Int) {
        val full = Rect(0, 0, c.width, c.height)
        if (glass != null) {
            val layer = c.saveLayer(box, null)
            t.style = Paint.Style.FILL; t.color = Color.WHITE
            c.drawText(time, x, baseline, t)
            val mask = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
                colorFilter = fill(spec.fillBrightness, sat, spec.fillContrast)
                alpha = (spec.fillAlpha * 255).toInt()
            }
            c.drawBitmap(glass, null, full, mask)
            c.restoreToCount(layer)
        }
        if (spec.sheen) {   // a diagonal highlight across the digits (CSS 160deg)
            val layer = c.saveLayer(box, null)
            t.style = Paint.Style.FILL; t.color = Color.WHITE
            c.drawText(time, x, baseline, t)
            val sheen = Paint().apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
                shader = LinearGradient(box.left, box.top, box.right, box.bottom,
                    intArrayOf(Color.argb(140, 255, 255, 255), Color.argb(0, 255, 255, 255), Color.argb(0, 255, 255, 255), Color.argb(56, 255, 255, 255)),
                    floatArrayOf(0f, 0.38f, 0.7f, 1f), Shader.TileMode.CLAMP)
            }
            c.drawRect(box, sheen)
            c.restoreToCount(layer)
        }
        t.style = Paint.Style.FILL; t.color = spec.rimFill
        if (spec.rimFill != Color.TRANSPARENT) c.drawText(time, x, baseline, t)
        t.style = Paint.Style.STROKE; t.strokeWidth = 1.44f * s; t.color = rimStroke
        c.drawText(time, x, baseline, t)
        t.style = Paint.Style.FILL
    }

    class Photo(val file: String, val place: String, val by: String)
    val PHOTOS = listOf(
        Photo("tessellated-pavement", "Tessellated Pavement, Tasmania", "JJ Harrison"),
        Photo("grose-valley", "Grose Valley, Blue Mountains", "Diliff"),
        Photo("albury-storm", "Storm at sunset, Albury", "Thennicke"),
        Photo("aresquiers-beach", "Aresquiers beach, France", "Christian Ferrer"),
        Photo("vang-vieng-sunset", "Vang Vieng, Laos", "Basile Morin"),
    )

    /** The Classic look's background: one of the photos, dimmed at night, with couch-tv's scrim (dark at the top for the clock, much darker at the bottom for the apps). */
    fun classic(ctx: Context, photo: Photo, w: Int, h: Int, dim: Boolean): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val c = Canvas(out)
        val bg = decode(ctx, "backgrounds/${photo.file}.jpg", Bitmap.Config.RGB_565)
        if (bg == null) c.drawColor(Color.parseColor("#0D1E19"))
        else {
            c.drawBitmap(bg, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG).apply { if (dim) colorFilter = brightness(0.72f) })
            bg.recycle()
        }
        val hh = h.toFloat()
        c.drawRect(0f, 0f, w.toFloat(), hh, Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, hh, intArrayOf(Color.argb(128, 0, 0, 0), 0, 0), floatArrayOf(0f, 0.26f, 1f), Shader.TileMode.CLAMP) })
        c.drawRect(0f, 0f, w.toFloat(), hh, Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, hh, intArrayOf(0, 0, Color.argb(128, 0, 0, 0), Color.argb(219, 0, 0, 0)), floatArrayOf(0f, 0.32f, 0.55f, 1f), Shader.TileMode.CLAMP) })
        return out
    }

    private fun decode(ctx: Context, path: String, cfg: Bitmap.Config): Bitmap? = runCatching {
        ctx.assets.open(path).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inPreferredConfig = cfg }) }
    }.getOrNull()

    private fun brightness(b: Float) = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
        b, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))

    private fun fill(brightness: Float, saturation: Float, contrast: Float = 1f) = ColorMatrixColorFilter(ColorMatrix().apply {
        setSaturation(saturation)
        postConcat(ColorMatrix(floatArrayOf(
            brightness, 0f, 0f, 0f, 0f, 0f, brightness, 0f, 0f, 0f, 0f, 0f, brightness, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
        if (contrast != 1f) { val o = 128f * (1f - contrast)
            postConcat(ColorMatrix(floatArrayOf(contrast, 0f, 0f, 0f, o, 0f, contrast, 0f, 0f, o, 0f, 0f, contrast, 0f, o, 0f, 0f, 0f, 1f, 0f))) }
    })
}
