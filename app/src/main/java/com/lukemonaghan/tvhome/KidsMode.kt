package com.lukemonaghan.tvhome

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.text.TextUtils
import android.view.KeyEvent

/**
 * Kids mode, after couch-tv's kids.js: a kids profile's home is one shelf of big posters, each opening straight into its
 * show, and the few apps a grown up allowed. Nothing else: no other apps, no More apps, no settings, no Keep watching.
 * Leaving needs the parent PIN, typed on [PinPad]. Rows: 0 the Grown ups lock, 1 the shows, 2 the apps.
 */
class KidsMode(
    private val ctx: Context, private val people: People, private val icons: Icons, private val watch: WatchNext,
    private val act: HomeActions, private val clock: () -> String,
    private val unlocked: (Person, String) -> Unit, private val redraw: () -> Unit,
) {
    var person: Person? = null; private set
    val active get() = person != null

    private var row = 1
    private val col = intArrayOf(0, 0, 0)
    private var pad: PinPad? = null
    private var note = ""
    private var noteUntil = 0L
    private var bg: Bitmap? = null
    private var bgSize = 0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val clip = Path()
    private val apps = HashMap<String, AppItem?>()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun cfg() = person?.kids
    private fun shows() = cfg()?.shows ?: emptyList<KidShow>()

    /** The allowed apps this TV has, in the order the grown up chose. */
    private fun allowed(): List<AppItem> = (cfg()?.apps ?: emptyList<String>()).mapNotNull { app(it) }

    /** The app for a package, looked up once (null kept too, so a missing app is not searched for on every frame). */
    private fun app(pkg: String): AppItem? { if (!apps.containsKey(pkg)) apps[pkg] = lookup(pkg); return apps[pkg] }

    private fun lookup(pkg: String): AppItem? {
        val pm = ctx.packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(pkg)
        val ri = pm.queryIntentActivities(q, 0).firstOrNull()
            ?: pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg), 0).firstOrNull() ?: return null
        return AppItem(pkg, ri.loadLabel(pm).toString(), ri)
    }

    private fun rows() = booleanArrayOf(true, shows().isNotEmpty(), allowed().isNotEmpty())

    // ------------------------------------------------------------------ in and out

    fun enter(p: Person) {
        if (person?.id != p.id) { row = 1; col.fill(0); pad = null; apps.clear() }
        person = p
        redraw()
    }

    fun leave() { person = null; pad = null; bg?.recycle(); bg = null; redraw() }

    /** Home pressed: close the pad and go back to the first poster. */
    fun home() { pad = null; row = if (rows()[1]) 1 else 0; col[1] = 0; redraw() }

    /** Back: closes the pad, else back to the shows. Kids mode itself is never left with Back. */
    fun back() { if (pad != null) pad = null else row = if (rows()[1]) 1 else row; redraw() }

    fun refresh() { apps.clear(); redraw() }

    // ------------------------------------------------------------------ keys

    /** True when the key was ours. Volume, power and the rest stay with the TV. */
    fun key(code: Int, e: KeyEvent): Boolean {
        pad?.let { p -> p.key(code); redraw(); return true }
        val digit = when (code) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9, in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> code
            else -> -1
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> move(-1, 0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> move(1, 0)
            KeyEvent.KEYCODE_DPAD_UP -> move(0, -1)
            KeyEvent.KEYCODE_DPAD_DOWN -> move(0, 1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { if (e.repeatCount == 0) activate() }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> openPin()
            KeyEvent.KEYCODE_BACK -> back()
            else -> {
                if (digit < 0) return false
                openPin(); pad?.key(code)
            }
        }
        redraw()
        return true
    }

    private fun move(dx: Int, dy: Int) {
        val avail = rows()
        if (dy != 0) {
            var r = row + dy
            while (r in 0..2 && !avail[r]) r += dy
            if (r in 0..2) row = r
        } else if (row == 1) col[1] = (col[1] + dx).coerceIn(0, maxOf(0, shows().size - 1))
        else if (row == 2) col[2] = (col[2] + dx).coerceIn(0, maxOf(0, allowed().size - 1))
    }

    private fun activate() {
        when (row) {
            0 -> openPin()
            1 -> shows().getOrNull(col[1])?.let { s ->
                if (s.pkg.isEmpty()) say("${s.title} did not open. Ask a grown up.")
                else { say("Opening ${s.title}"); act.openCard(s.pkg, s.link) }
            }
            else -> allowed().getOrNull(col[2])?.let { a -> say("Opening ${a.label}"); act.launch(a.pkg) }
        }
    }

    private fun say(s: String) { note = s; noteUntil = System.currentTimeMillis() + 7000 }

    private fun openPin() {
        val kid = person ?: return
        val k = kid.kids ?: return
        pad = PinPad(ctx, "Grown ups only", "Type the parent PIN to leave kids mode.", "Arrows and OK, or the number keys. Back closes this.",
            matchOnType = true, limitTries = true,
            submit = { d -> if (k.matches(d)) { pad = null; unlocked(kid, d); null } else "That PIN is not right." },
            close = { pad = null })
    }

    // ------------------------------------------------------------------ drawing (couch-tv's .kids, 1 rem = 16 px at 1080p)

    fun draw(c: Canvas, w: Int, h: Int) {
        val kid = person ?: return
        val u = h / 1080f; val rem = 16 * u
        background(c, w, h)
        val colour = people.colourOf(kid)
        val avail = rows()
        if (!avail[row]) row = if (avail[1]) 1 else if (avail[2]) 2 else 0
        col[1] = col[1].coerceIn(0, maxOf(0, shows().size - 1)); col[2] = col[2].coerceIn(0, maxOf(0, allowed().size - 1))
        val pinUp = pad != null

        // header: the child's avatar and name on the left, the time and the Grown ups lock on the right
        val left = 5 * rem; val top = 3.75f * rem
        paint.style = Paint.Style.FILL; paint.color = colour
        c.drawCircle(left + 40 * u, top + 40 * u, 40 * u, paint)
        text.typeface = Fonts.semibold(ctx); text.color = Color.WHITE; text.textAlign = Paint.Align.CENTER; text.textSize = 38 * u
        c.drawText(kid.name.trim().take(1).uppercase().ifEmpty { "?" }, left + 40 * u, top + 40 * u + 13 * u, text)
        text.textAlign = Paint.Align.LEFT
        text.typeface = Fonts.semibold(ctx); text.textSize = 48 * u; text.color = Color.rgb(245, 245, 247)
        val tx = left + 80 * u + 24 * u
        val name = TextUtils.ellipsize("${kid.name}'s shows", text, w * 0.5f, TextUtils.TruncateAt.END)
        c.drawText(name, 0, name.length, tx, top + 37 * u, text)
        text.typeface = Fonts.regular(ctx); text.textSize = 24 * u; text.color = Color.argb(158, 245, 245, 247)
        c.drawText("Kids mode", tx, top + 37 * u + 8 * u + 24 * u, text)

        val lockFocus = !pinUp && row == 0
        text.typeface = Fonts.semibold(ctx); text.textSize = 24 * u
        val label = "Grown ups"
        val lw = 20 * u + 28 * u + 12 * u + text.measureText(label) + 24 * u
        val lh = 14 * u + 28 * u + 14 * u
        val right = w - 5 * rem
        val lx = right - lw; val ly = top + (80 * u - lh) / 2
        c.save()
        if (lockFocus) { c.translate(lx + lw / 2, ly + lh / 2); c.scale(1.06f, 1.06f); c.translate(-(lx + lw / 2), -(ly + lh / 2)) }
        rect.set(lx, ly, lx + lw, ly + lh)
        paint.color = if (lockFocus) Color.WHITE else Color.argb(26, 255, 255, 255)
        c.drawRoundRect(rect, 48 * u, 48 * u, paint)
        if (!lockFocus) { paint.style = Paint.Style.STROKE; paint.strokeWidth = u; paint.color = Color.argb(41, 255, 255, 255); rect.inset(u / 2, u / 2); c.drawRoundRect(rect, 48 * u, 48 * u, paint); paint.style = Paint.Style.FILL }
        val fg = if (lockFocus) Color.rgb(17, 17, 17) else Color.argb(158, 245, 245, 247)
        lockIcon(c, lx + 20 * u + 14 * u, ly + lh / 2, 28 * u, fg)
        text.color = fg; c.drawText(label, lx + 20 * u + 28 * u + 12 * u, ly + lh / 2 + 8.5f * u, text)
        c.restore()
        text.typeface = Fonts.semibold(ctx); text.textSize = 40 * u; text.color = Color.rgb(245, 245, 247); text.textAlign = Paint.Align.RIGHT
        c.drawText(clock(), lx - 32 * u, top + 40 * u + 14 * u, text)
        text.textAlign = Paint.Align.LEFT

        // the shows
        val list = shows(); val appList = allowed()
        val appsH = if (appList.isNotEmpty()) 143 * u else 0f
        val shelfTop = top + 80 * u; val shelfBottom = h - 3.75f * rem - appsH
        if (list.isEmpty()) {
            text.typeface = Fonts.regular(ctx); text.textSize = 36 * u; text.color = Color.argb(158, 245, 245, 247); text.textAlign = Paint.Align.CENTER
            val msg = "No shows here yet. A grown up can add some in Home settings, People."
            c.drawText(TextUtils.ellipsize(msg, text, 960 * u, TextUtils.TruncateAt.END).toString(), w / 2f, (shelfTop + shelfBottom) / 2, text)
            text.textAlign = Paint.Align.LEFT
        } else {
            val tw = 19 * rem; val ph = 28.5f * rem; val stepX = tw + 2.5f * rem
            val blockH = ph + 20 * u + 35 * u + 6 * u + 29 * u
            val py = shelfTop + (shelfBottom - shelfTop - blockH) / 2
            val fit = maxOf(1, ((w - 2 * left + 2.5f * rem) / stepX).toInt())
            val start = maxOf(0, minOf(col[1] - fit + 2, list.size - fit))
            c.save(); c.clipRect(0f, 0f, w.toFloat(), h.toFloat())
            // the focused poster is drawn last, so its ring and scale sit over its neighbours
            for (pass in 0..1) for (i in list.indices) {
                val focused = !pinUp && row == 1 && col[1] == i
                if (focused != (pass == 1)) continue
                val x = left + (i - start) * stepX
                if (x > w || x + tw < 0) continue
                poster(c, u, list[i], x, py, tw, ph, focused, colour)
            }
            c.restore()
        }

        // the apps
        if (appList.isNotEmpty()) {
            val ay = h - 3.75f * rem - appsH
            for (i in appList.indices) {
                val focused = !pinUp && row == 2 && col[2] == i
                kidApp(c, u, appList[i], left + i * 10 * rem, ay, focused)
            }
        }

        if (note.isNotEmpty() && System.currentTimeMillis() < noteUntil) {
            text.typeface = Fonts.regular(ctx); text.textSize = 24 * u; text.color = Color.argb(158, 245, 245, 247)
            c.drawText(note, left, h - 24 * u - 6 * u, text)
        }
        pad?.let { it.draw(c, w, h); if (it.animating()) handler.postDelayed({ redraw() }, 40) }
    }

    private fun poster(c: Canvas, u: Float, s: KidShow, x: Float, y: Float, w: Float, h: Float, focused: Boolean, colour: Int) {
        val r = 28 * u
        c.save()
        if (focused) { val cx = x + w / 2; val cy = y + h / 2; c.translate(cx, cy - 8 * u); c.scale(1.08f, 1.08f); c.translate(-cx, -cy) }
        rect.set(x, y, x + w, y + h)
        // soft shadow, then the focus rings: white 6 px, the child's colour to 12 px
        paint.style = Paint.Style.FILL
        for (i in 6 downTo 1) {
            val g = (if (focused) 56 else 28) * u * i / 6
            paint.color = Color.argb((if (focused) 120 else 90) / 6 + 1, 0, 0, 0)
            rect.set(x - g, y - g + (if (focused) 32 else 20) * u, x + w + g, y + h + g + (if (focused) 32 else 20) * u)
            c.drawRoundRect(rect, r + g, r + g, paint)
        }
        if (focused) {
            // two rings drawn as strokes, so the poster's own translucent body is not covered
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 6 * u
            paint.color = colour; rect.set(x - 9 * u, y - 9 * u, x + w + 9 * u, y + h + 9 * u); c.drawRoundRect(rect, r + 9 * u, r + 9 * u, paint)
            paint.color = Color.WHITE; rect.set(x - 3 * u, y - 3 * u, x + w + 3 * u, y + h + 3 * u); c.drawRoundRect(rect, r + 3 * u, r + 3 * u, paint)
            paint.style = Paint.Style.FILL
        }
        rect.set(x, y, x + w, y + h)
        paint.shader = LinearGradient(x, y, x + w * 0.35f, y + h, Color.argb(41, 255, 255, 255), Color.argb(13, 255, 255, 255), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, r, r, paint); paint.shader = null
        clip.reset(); clip.addRoundRect(rect, r, r, Path.Direction.CW)
        c.save(); c.clipPath(clip)
        val art = s.art?.let { watch.picture(s.card, w.toInt(), h.toInt()) }
        if (art != null) c.drawBitmap(art, null, rect, null)
        else {   // no picture: the title in big letters at the bottom, as couch-tv does
            text.typeface = Fonts.semibold(ctx); text.textSize = 36 * u; text.color = Color.rgb(245, 245, 247); text.textAlign = Paint.Align.LEFT
            val lines = wrap(s.title, w - 48 * u).takeLast(4)
            var by = y + h - 28 * u - (lines.size - 1) * 40 * u
            for (l in lines) { c.drawText(l, x + 24 * u, by, text); by += 40 * u }
        }
        // the app's icon as a badge, top left
        val bs = 52 * u
        paint.color = Color.argb(115, 0, 0, 0); rect.set(x + 16 * u, y + 16 * u, x + 16 * u + bs, y + 16 * u + bs); c.drawRoundRect(rect, 14 * u, 14 * u, paint)
        badge(s.pkg)?.let { a -> icons.square(a, bs.toInt())?.let { b ->
            clip.reset(); clip.addRoundRect(rect, 14 * u, 14 * u, Path.Direction.CW); c.save(); c.clipPath(clip); c.drawBitmap(b, null, rect, null); c.restore()
        } }
        c.restore()
        paint.style = Paint.Style.STROKE; paint.strokeWidth = u; paint.color = Color.argb(31, 255, 255, 255)
        rect.set(x + u / 2, y + u / 2, x + w - u / 2, y + h - u / 2); c.drawRoundRect(rect, r, r, paint); paint.style = Paint.Style.FILL

        // the title and the line under it
        text.typeface = Fonts.semibold(ctx); text.textSize = 30 * u; text.textAlign = Paint.Align.LEFT
        text.color = if (focused) Color.rgb(245, 245, 247) else Color.argb(158, 245, 245, 247)
        val t = TextUtils.ellipsize(s.title, text, w, TextUtils.TruncateAt.END)
        c.drawText(t, 0, t.length, x, y + h + 20 * u + 27 * u, text)
        val sub = s.sub.ifEmpty { app(s.pkg)?.label.orEmpty() }
        if (sub.isNotEmpty()) {
            text.typeface = Fonts.regular(ctx); text.textSize = 24 * u; text.color = Color.argb(107, 245, 245, 247)
            val st = TextUtils.ellipsize(sub, text, w, TextUtils.TruncateAt.END)
            c.drawText(st, 0, st.length, x, y + h + 20 * u + 35 * u + 6 * u + 22 * u, text)
        }
        c.restore()
    }

    private fun badge(pkg: String): AppItem? = if (pkg.isEmpty()) null else app(pkg)

    private fun kidApp(c: Canvas, u: Float, a: AppItem, x: Float, y: Float, focused: Boolean) {
        val fs = 104 * u; val r = 24 * u
        c.save()
        if (focused) { val cx = x + 64 * u; val cy = y + 52 * u; c.translate(cx, cy); c.scale(1.1f, 1.1f); c.translate(-cx, -cy) }
        val fx = x + 12 * u
        paint.style = Paint.Style.FILL
        if (focused) { paint.style = Paint.Style.STROKE; paint.strokeWidth = 4 * u; paint.color = Color.WHITE; rect.set(fx - 2 * u, y - 2 * u, fx + fs + 2 * u, y + fs + 2 * u); c.drawRoundRect(rect, r + 2 * u, r + 2 * u, paint); paint.style = Paint.Style.FILL }
        rect.set(fx, y, fx + fs, y + fs)
        paint.color = Color.argb(31, 255, 255, 255); c.drawRoundRect(rect, r, r, paint)
        val b = icons.square(a, fs.toInt())
        if (b != null) { clip.reset(); clip.addRoundRect(rect, r, r, Path.Direction.CW); c.save(); c.clipPath(clip); c.drawBitmap(b, null, rect, null); c.restore() }
        text.typeface = Fonts.regular(ctx); text.textSize = 22 * u; text.textAlign = Paint.Align.CENTER
        text.color = if (focused) Color.rgb(245, 245, 247) else Color.argb(158, 245, 245, 247)
        val l = TextUtils.ellipsize(a.label, text, 124 * u, TextUtils.TruncateAt.END)
        c.drawText(l, 0, l.length, x + 64 * u, y + fs + 12 * u + 20 * u, text)
        text.textAlign = Paint.Align.LEFT
        c.restore()
    }

    private fun wrap(s: String, width: Float): List<String> {
        val out = ArrayList<String>(); var cur = ""
        for (word in s.split(' ')) {
            val t = if (cur.isEmpty()) word else "$cur $word"
            if (text.measureText(t) <= width || cur.isEmpty()) cur = t else { out.add(cur); cur = word }
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    /** couch-tv's kids backdrop: a slow blue-violet diagonal with a warm glow top left and a cool one bottom right. */
    private fun background(c: Canvas, w: Int, h: Int) {
        if (bg == null || bgSize != w * 31 + h) {
            bg?.recycle()
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565); val bc = Canvas(b)
            val p = Paint(Paint.ANTI_ALIAS_FLAG); val a = Math.toRadians(160.0)
            val dx = Math.sin(a).toFloat(); val dy = (-Math.cos(a)).toFloat()
            val len = Math.abs(w * dx) + Math.abs(h * dy)
            p.shader = LinearGradient(w / 2f - dx * len / 2, h / 2f - dy * len / 2, w / 2f + dx * len / 2, h / 2f + dy * len / 2,
                intArrayOf(Color.rgb(27, 35, 71), Color.rgb(38, 26, 69), Color.rgb(19, 26, 51)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            bc.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            val u = h / 1080f
            fun glow(cx: Float, cy: Float, rx: Float, ry: Float, col: Int) {
                bc.save(); bc.translate(cx, cy); bc.scale(rx, ry)
                val g = Paint(Paint.ANTI_ALIAS_FLAG)
                g.shader = RadialGradient(0f, 0f, 1f, intArrayOf(col, col and 0x00FFFFFF), floatArrayOf(0f, 0.7f), Shader.TileMode.CLAMP)
                bc.drawCircle(0f, 0f, 1f, g); bc.restore()
            }
            glow(w * 0.12f, h * 0.08f, 960 * u, 640 * u, Color.argb(56, 255, 196, 92))
            glow(w * 0.95f, h * 1.0f, 1120 * u, 800 * u, Color.argb(61, 90, 160, 255))
            bg = b; bgSize = w * 31 + h
        }
        c.drawBitmap(bg!!, 0f, 0f, null)
    }

    private fun lockIcon(c: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        val k = size / 24f
        c.save(); c.translate(cx - size / 2, cy - size / 2); c.scale(k, k)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.8f; paint.color = color; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
        rect.set(5f, 10.5f, 19f, 20.5f); c.drawRoundRect(rect, 2.5f, 2.5f, paint)
        clip.reset(); clip.moveTo(8.5f, 10.5f); clip.lineTo(8.5f, 7.5f); rect.set(8.5f, 4f, 15.5f, 11f); clip.arcTo(rect, 180f, 180f); clip.lineTo(15.5f, 10.5f)
        c.drawPath(clip, paint)
        c.restore(); paint.style = Paint.Style.FILL
    }
}
