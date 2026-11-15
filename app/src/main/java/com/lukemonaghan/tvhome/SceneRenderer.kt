package com.lukemonaghan.tvhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
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
        val shadowDy: Float = 6f, val shadowBlur: Float = 28f, val shadowColor: Int = Color.argb(41, 0, 0, 0),   // .gclock drop-shadow (0.375rem, 1.75rem, 16%)
    )

    val NIGHT = Spec("night", "Night", "scene-night", true, clockTop = 150f, clockSize = 400f, dateTop = 96f,
        dateColor = Color.argb(235, 255, 232, 238), wxColor = Color.argb(199, 255, 232, 238), photoBrightness = 0.86f,
        fillBrightness = 1.15f, fillSaturation = 1.2f, fillAlpha = 0.9f,
        rimStroke = Color.argb(128, 255, 215, 225), rimFill = Color.argb(15, 255, 200, 215))
    val GOLDEN = Spec("golden", "Golden hour", "scene-golden", true, clockTop = 152f, clockSize = 390f, dateTop = 106f)
    val WATER = Spec("water", "Waterline", "scene-water", false, clockTop = 268f, clockSize = 420f, dateTop = 216f, water = 0.55f,
        shade = intArrayOf(Color.argb(107, 0, 12, 24), Color.argb(31, 0, 12, 24), Color.argb(0, 0, 0, 0), Color.argb(128, 0, 20, 40)),
        shadePos = floatArrayOf(0f, 0.4f, 0.52f, 1f), shadowDy = 8f, shadowBlur = 30f, shadowColor = Color.argb(89, 0, 20, 40))
    val FOG = Spec("fog", "Fog", "scene-fog", true, clockTop = 172f, clockSize = 470f, clockLeft = 96f, dateTop = 116f, dateLeft = 104f,
        dateColor = Color.rgb(22, 32, 29), wxColor = Color.argb(184, 22, 32, 29), dateShadow = false,
        fillBrightness = 0.46f, fillSaturation = 0.8f, fillContrast = 1.1f, fillAlpha = 0.8f,
        rimStroke = Color.argb(153, 255, 255, 255), rimFill = Color.TRANSPARENT, sheen = false, shadowColor = Color.TRANSPARENT)
    val DAWN = Spec("dawn", "Rainforest dawn", "scene-dawn", true, clockTop = 230f, clockSize = 400f, dateTop = 176f,
        shade = intArrayOf(Color.argb(107, 50, 28, 12), Color.argb(0, 50, 28, 12)), shadePos = floatArrayOf(0f, 0.55f),
        shadowDy = 8f, shadowBlur = 34f, shadowColor = Color.argb(77, 60, 30, 10))

    /** One of the household's own photos as a Night scene (.s-own): no cut-out and no glass copy, the clock's fill is the photo itself, lifted a little. */
    fun own(name: String, path: String) = Spec("own:$name", "Your photo", "file:$path", false, clockTop = 150f, clockSize = 400f, dateTop = 96f,
        fillBrightness = 1.4f, fillSaturation = 1.15f, fillAlpha = 0.88f, rimStroke = Color.argb(179, 255, 255, 255),
        shade = intArrayOf(Color.argb(77, 0, 0, 0), Color.argb(0, 0, 0, 0)), shadePos = floatArrayOf(0f, 0.45f))

    /** The scenes in couch-tv's order. */
    val ALL = listOf(NIGHT, GOLDEN, WATER, FOG, DAWN)

    fun font(ctx: Context): Typeface = Fonts.antonio(ctx)

    /** After 9 pm and before 6 am couch-tv dims the photo to 72% and the clock with it. */
    fun isDim(hour: Int) = hour >= 21 || hour < 6

    private val DEFAULT_STYLE = Looks.ClockStyle(false, 1f, .85f, .8f, false, false)

    /** What a clock is drawn from: the sharp photo or its frosted copy, and how big a screen pixel is. */
    private class Ink(val spec: Spec, val style: Looks.ClockStyle, val s: Float, val src: Bitmap?, val srcRect: Rect, val full: Rect, val dim: Boolean)

    fun render(ctx: Context, spec: Spec, time: String, date: String, wx: String, w: Int, h: Int, dim: Boolean,
               style: Looks.ClockStyle = DEFAULT_STYLE): Bitmap {
        val s = h / 1080f
        val video = style.video                                  // over a video: no photo, no cut-out, a pale glass clock, a clear bitmap
        val photoB = if (dim) 0.72f else spec.photoBrightness
        val out = Bitmap.createBitmap(w, h, if (video) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565)
        val c = Canvas(out)
        val full = Rect(0, 0, w, h)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val own = spec.photo.startsWith("file:")
        val photoPath = if (own) spec.photo else "scenes/${spec.photo}.jpg"
        val sharpFill = !video && (own || (style.liquid && !style.frost))   // the clock's fill is the sharp photo itself

        // photo, then the scene's shade over it
        var bg: Bitmap? = null
        if (!video) {
            bg = decode(ctx, photoPath, Bitmap.Config.RGB_565)
            if (bg != null) { p.colorFilter = brightness(photoB); c.drawBitmap(bg, cover(bg, w, h), full, p) } else c.drawColor(Color.parseColor("#0D1E19"))
            p.colorFilter = null
            if (!sharpFill) { bg?.recycle(); bg = null }
        }
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
        val glass = if (!video && !own && !sharpFill) decode(ctx, "scenes/${spec.photo}-glass.jpg", Bitmap.Config.RGB_565) else null
        val src = if (video) null else if (sharpFill) bg else glass
        val ink = Ink(spec, style, s, src, if (src != null) cover(src, w, h) else full, full, dim)

        // the clock's opacity (steps 4 and 5 of How clear let more through) and the night dimming
        val dimK = if (style.liquid) 0.8f else 0.72f
        val layerPaint = Paint().apply { alpha = (style.opacity * 255).toInt(); if (dim) colorFilter = brightness(dimK) }
        if (spec.water > 0f) {
            val line = (h * spec.water).toInt()
            // above the waterline the clock is sharp; below it a copy shows like a reflection (blurred, a little to the right)
            c.save(); c.clipRect(0f, 0f, w.toFloat(), line.toFloat())
            val l1 = c.saveLayer(null, layerPaint)
            drawGroup(c, ink, t, time, tx, baseline, box, under = false)
            c.restoreToCount(l1); c.restore()
            val th = h - line
            val tmp = Bitmap.createBitmap(w, th, Bitmap.Config.ARGB_8888)
            val tc = Canvas(tmp)
            tc.translate(0f, -line.toFloat()); tc.clipRect(0f, line.toFloat(), w.toFloat(), h.toFloat())
            drawGroup(tc, ink, t, time, tx, baseline, box, under = true)
            val soft = Bitmap.createScaledBitmap(tmp, maxOf(8, w / 3), maxOf(8, th / 3), true)   // blur(0.1375rem): a small copy stretched back
            tmp.recycle()
            val pp = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (0.78f * style.opacity * 255).toInt(); if (dim) colorFilter = brightness(dimK) }
            c.drawBitmap(soft, null, RectF(5 * s, line.toFloat(), 5 * s + w, h.toFloat()), pp)
            soft.recycle()
        } else {
            val l1 = c.saveLayer(null, layerPaint)
            drawGroup(c, ink, t, time, tx, baseline, box, under = false)
            c.restoreToCount(l1)
        }
        glass?.recycle(); bg?.recycle()

        // everything in front of the sky, over the digits
        if (spec.hasCutout && !video) decode(ctx, "scenes/${spec.photo}-fg.webp", Bitmap.Config.ARGB_8888)?.let { fg ->
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

    /** One glass clock, as the web home layers it: drop shadow, (liquid) dark edge, the fill, the sheen, the rim. */
    private fun drawGroup(c: Canvas, k: Ink, t: Paint, time: String, x: Float, baseline: Float, box: RectF, under: Boolean) {
        val spec = k.spec; val st = k.style; val s = k.s
        val liquid = st.liquid
        val fog = spec.id == "fog"
        t.style = Paint.Style.FILL

        // the drop shadow (.gclock filter): per scene, a pair over a video, a deeper one under the liquid clock at night
        if (!under) {
            val shadows: List<FloatArray> = when {
                st.video -> listOf(floatArrayOf(4f, 24f, 0.42f), floatArrayOf(0f, 3f, 0.30f))
                liquid && k.dim -> listOf(floatArrayOf(10f, 24f, 0.30f))
                Color.alpha(spec.shadowColor) > 0 -> listOf(floatArrayOf(spec.shadowDy, spec.shadowBlur, Color.alpha(spec.shadowColor) / 255f))
                else -> emptyList()
            }
            for (sh in shadows) {
                val base = if (!st.video && !(liquid && k.dim) && Color.alpha(spec.shadowColor) > 0) spec.shadowColor else Color.BLACK
                t.color = Color.argb((sh[2] * 255).toInt(), Color.red(base), Color.green(base), Color.blue(base))
                t.maskFilter = BlurMaskFilter(maxOf(1f, sh[1] * s * 0.85f), BlurMaskFilter.Blur.NORMAL)
                c.drawText(time, x, baseline + sh[0] * s, t)
                t.maskFilter = null
            }
        }
        // liquid: a dark edge just outside the outline gives the glass thickness
        if (liquid) {
            t.style = Paint.Style.STROKE; t.strokeWidth = 11.2f * s; t.color = Color.argb(41, 0, 0, 0)
            t.maskFilter = BlurMaskFilter(6f * s, BlurMaskFilter.Blur.NORMAL)
            c.drawText(time, x, baseline, t)
            t.maskFilter = null; t.style = Paint.Style.FILL
        }
        // the fill: the photo (or its frosted copy) seen through the digits; over a video a pale flat glass
        val layer = c.saveLayer(box, null)
        t.color = Color.WHITE
        c.drawText(time, x, baseline, t)
        val mask = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) }
        if (k.src == null) {
            mask.color = Color.argb(if (fog) 173 else 184, 255, 255, 255)
            c.drawRect(box, mask)
        } else {
            when {
                under -> { mask.colorFilter = fillHue(-8f, 1.4f); mask.alpha = 255 }
                liquid && (fog) -> { mask.colorFilter = fill(0.82f, 1.1f, 1.12f); mask.alpha = 255 }
                liquid -> { mask.colorFilter = fill(1.16f, 1.12f, 1.04f); mask.alpha = (st.fill * 255).toInt() }
                else -> { mask.colorFilter = fill(spec.fillBrightness, spec.fillSaturation, spec.fillContrast); mask.alpha = (spec.fillAlpha * 255).toInt() }
            }
            c.save()
            // liquid glass bends what is behind it: the photo magnified a little about the clock's middle
            if (liquid) c.scale(1.07f, 1.07f, (box.left + box.right) / 2f, (box.top + box.bottom) / 2f)
            c.drawBitmap(k.src, k.srcRect, k.full, mask)
            c.restore()
        }
        c.restoreToCount(layer)
        // the sheen: a light diagonal across the digits
        if (spec.sheen || liquid) {
            val l2 = c.saveLayer(box, null)
            t.color = Color.WHITE
            c.drawText(time, x, baseline, t)
            val sheen = Paint().apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
                shader = if (liquid) {   // CSS 165deg
                    val dx = 0.2588f; val dy = 0.9659f
                    val len = box.width() * dx + box.height() * dy
                    val cx = box.centerX(); val cy = box.centerY()
                    LinearGradient(cx - dx * len / 2, cy - dy * len / 2, cx + dx * len / 2, cy + dy * len / 2,
                        intArrayOf(Color.argb(158, 255, 255, 255), Color.argb(20, 255, 255, 255), 0, 0, Color.argb(77, 255, 255, 255)),
                        floatArrayOf(0f, 0.26f, 0.55f, 0.76f, 1f), Shader.TileMode.CLAMP)
                } else LinearGradient(box.left, box.top, box.right, box.bottom,
                    intArrayOf(Color.argb(140, 255, 255, 255), Color.argb(0, 255, 255, 255), Color.argb(0, 255, 255, 255), Color.argb(56, 255, 255, 255)),
                    floatArrayOf(0f, 0.38f, 0.7f, 1f), Shader.TileMode.CLAMP)
            }
            c.drawRect(box, sheen)
            c.restoreToCount(l2)
        }
        // the rim
        if (liquid) {
            t.style = Paint.Style.STROKE; t.strokeWidth = 2.56f * s; t.color = Color.argb((st.rim * 255).toInt(), 255, 255, 255)
        } else {
            t.style = Paint.Style.FILL; t.color = spec.rimFill
            if (spec.rimFill != Color.TRANSPARENT) c.drawText(time, x, baseline, t)
            t.style = Paint.Style.STROKE; t.strokeWidth = 1.44f * s; t.color = if (under) Color.argb(128, 170, 240, 255) else spec.rimStroke
        }
        c.drawText(time, x, baseline, t)
        t.style = Paint.Style.FILL
    }

    /** Crops [bmp] to the screen's shape the way CSS object-fit: cover does (a no-op for a 16:9 picture). */
    private fun cover(bmp: Bitmap, w: Int, h: Int): Rect {
        val sa = w.toFloat() / h; val ba = bmp.width.toFloat() / bmp.height
        return if (ba > sa) { val nw = (bmp.height * sa).toInt(); Rect((bmp.width - nw) / 2, 0, (bmp.width + nw) / 2, bmp.height) }
        else { val nh = (bmp.width / sa).toInt(); Rect(0, (bmp.height - nh) / 2, bmp.width, (bmp.height + nh) / 2) }
    }

    class Photo(val file: String, val place: String, val by: String, val path: String? = null)
    val PHOTOS = listOf(
        Photo("tessellated-pavement", "Tessellated Pavement, Tasmania", "JJ Harrison"),
        Photo("grose-valley", "Grose Valley, Blue Mountains", "Diliff"),
        Photo("albury-storm", "Storm at sunset, Albury", "Thennicke"),
        Photo("aresquiers-beach", "Aresquiers beach, France", "Christian Ferrer"),
        Photo("vang-vieng-sunset", "Vang Vieng, Laos", "Basile Morin"),
    )

    /** The Classic look's background: one of the photos, dimmed at night, with couch-tv's scrim (dark at the top for the clock, much darker at the bottom for the apps). */
    fun classic(ctx: Context, photo: Photo, w: Int, h: Int, dim: Boolean, video: Boolean = false): Bitmap {
        val out = Bitmap.createBitmap(w, h, if (video) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565)
        val c = Canvas(out)
        val bg = if (video) null else decode(ctx, if (photo.path != null) "file:${photo.path}" else "backgrounds/${photo.file}.jpg", Bitmap.Config.RGB_565)
        if (video) {}   // the video is the picture; only the scrims below are drawn
        else if (bg == null) c.drawColor(Color.parseColor("#0D1E19"))
        else {
            c.drawBitmap(bg, cover(bg, w, h), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG).apply { if (dim) colorFilter = brightness(0.72f) })
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
        val o = BitmapFactory.Options().apply { inPreferredConfig = cfg }
        if (path.startsWith("file:")) BitmapFactory.decodeFile(path.removePrefix("file:"), o)
        else ctx.assets.open(path).use { BitmapFactory.decodeStream(it, null, o) }
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

    /** CSS filter: hue-rotate(deg) saturate(k), in that order: the reflection under the waterline. */
    private fun fillHue(deg: Float, sat: Float): ColorMatrixColorFilter {
        val a = Math.toRadians(deg.toDouble()); val c = Math.cos(a).toFloat(); val sn = Math.sin(a).toFloat()
        val cm = ColorMatrix(floatArrayOf(
            0.213f + c * 0.787f - sn * 0.213f, 0.715f - c * 0.715f - sn * 0.715f, 0.072f - c * 0.072f + sn * 0.928f, 0f, 0f,
            0.213f - c * 0.213f + sn * 0.143f, 0.715f + c * 0.285f + sn * 0.140f, 0.072f - c * 0.072f - sn * 0.283f, 0f, 0f,
            0.213f - c * 0.213f - sn * 0.787f, 0.715f - c * 0.715f + sn * 0.715f, 0.072f + c * 0.928f + sn * 0.072f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f))
        cm.postConcat(ColorMatrix().apply { setSaturation(sat) })
        return ColorMatrixColorFilter(cm)
    }
}
