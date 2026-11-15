package com.lukemonaghan.tvhome

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.view.KeyEvent
import java.text.NumberFormat
import java.util.concurrent.Executors

/**
 * The Smart home screen (couch-tv's smarthome.js): each air conditioner is a card with a dial and four buttons
 * (cooler, warmer, on or off, mode), then the scenes five to a line. Left and right move along a row, up and
 * down between rows, OK presses, Back closes. While it is open the rooms are read again every 20 seconds;
 * closed, nothing is asked. Plain Canvas like the rest of the home.
 */
class SmartHomeScreen(private val ctx: Context, private val changed: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var rooms: List<HomeAssistant.Room> = emptyList()
    private var down = false
    private var row = 0
    private var col = 0
    private var msg = ""
    private val pendingTarget = HashMap<String, Double>()   // a change still waiting to be sent wins over what was read
    private var opened = false
    private var gen = 0                                       // an answer from before the screen was closed is dropped

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    private fun s() = HomeAssistant.settings(ctx)

    private class Item(val room: Int, val k: Int, val scene: Int)   // k: 0 down, 1 up, 2 power, 3 mode

    private fun rows(): List<List<Item>> {
        val out = ArrayList<List<Item>>()
        if (rooms.isNotEmpty()) out.add(rooms.indices.flatMap { r -> (0..3).map { Item(r, it, -1) } })
        val sc = s()?.scenes ?: emptyList()
        for (k in sc.indices step SCENES_PER_LINE) out.add((k until minOf(sc.size, k + SCENES_PER_LINE)).map { Item(-1, -1, it) })
        return out
    }

    fun open() {
        opened = true; gen++
        val st = s() ?: return
        rooms = st.climate.map { HomeAssistant.Room(it.entity, it.name.ifEmpty { HomeAssistant.pretty(it.entity) }).apply { loading = true } }
        row = 0; col = if (rooms.isNotEmpty()) 1 else 0; msg = ""; down = false
        load(); later(REFRESH_MS)
    }

    fun close() { opened = false; gen++; main.removeCallbacksAndMessages(null) }

    // ------------------------------------------------------------------ asking Home Assistant

    private fun load() {
        val st = s() ?: return
        if (!opened || st.climate.isEmpty()) return
        val g = gen
        io.execute {
            val read = runCatching { HomeAssistant.rooms(st) }.getOrDefault(emptyList())
            main.post {
                if (!opened || g != gen) return@post
                read.forEach { r -> pendingTarget[r.entity]?.let { if (r.ok) r.target = it } }
                rooms = if (read.isEmpty()) rooms.onEach { it.loading = false; it.ok = false } else read
                down = rooms.none { it.ok }
                clamp(); changed()
            }
        }
    }

    private fun later(ms: Long) {
        main.removeCallbacks(refreshTick)
        main.postDelayed(refreshTick, ms)
    }
    private val refreshTick = Runnable { if (opened) { load(); later(REFRESH_MS) } }

    private fun clamp() {
        val r = rows()
        if (row >= r.size) row = maxOf(0, r.size - 1)
        if (r.isNotEmpty() && col >= r[row].size) col = r[row].size - 1
    }

    private fun flash(m: String) {
        msg = m; changed()
        main.removeCallbacks(clearMsg); main.postDelayed(clearMsg, 4000)
    }
    private val clearMsg = Runnable { msg = ""; changed() }

    private fun num(v: Double) = Math.round(v * 10) / 10.0
    private fun deg(v: Double?): String {
        if (v == null || !v.isFinite()) return ""
        val f = NumberFormat.getInstance(); f.maximumFractionDigits = 1; f.minimumFractionDigits = 0
        return f.format(num(v)) + "°"
    }
    private fun isOff(r: HomeAssistant.Room) = r.state == "off"
    private fun canSet(r: HomeAssistant.Room) = r.ok && r.target != null

    private fun sent(ok: Boolean) {
        if (!ok) flash(PS.FAILED)
        later(1500)   // read it back once Home Assistant has passed it on
    }

    private val sendTemp = HashMap<String, Runnable>()
    private fun nudge(r: HomeAssistant.Room, d: Int) {
        if (!canSet(r)) { flash(if (r.ok) PS.NO_SETPOINT else if (r.loading) PS.CHECKING else PS.ROOM_QUIET); return }
        val t = (r.target ?: return)
        r.target = num(t + d * r.step).coerceIn(r.min, r.max)
        pendingTarget[r.entity] = r.target!!
        changed()
        // presses are joined for a moment, so five presses send one change
        sendTemp[r.entity]?.let { main.removeCallbacks(it) }
        val run = Runnable {
            val want = r.target ?: return@Runnable
            val st = s() ?: return@Runnable
            io.execute { val ok = HomeAssistant.setTemperature(st, r.entity, want); main.post { pendingTarget.remove(r.entity); sent(ok) } }
        }
        sendTemp[r.entity] = run
        main.postDelayed(run, JOIN_MS)
    }

    private fun power(r: HomeAssistant.Room) {
        if (!r.ok) { flash(if (r.loading) PS.CHECKING else PS.ROOM_QUIET); return }
        val on = isOff(r)
        r.state = if (on) r.modes.firstOrNull { it != "off" } ?: "auto" else "off"
        r.action = ""
        changed()
        flash(String.format(if (on) PS.TURNED_ON else PS.TURNED_OFF, r.name))
        val st = s() ?: return
        io.execute { val ok = HomeAssistant.power(st, r.entity, on); main.post { sent(ok) } }
    }

    private fun cycleMode(r: HomeAssistant.Room) {
        if (!r.ok) { flash(if (r.loading) PS.CHECKING else PS.ROOM_QUIET); return }
        val list = r.modes.filter { it != "off" }
        if (list.isEmpty()) { flash(PS.NO_MODES); return }
        val next = list[(list.indexOf(r.state) + 1) % list.size]
        r.state = next; r.action = ""
        changed()
        flash(String.format(PS.MODE_NOW, r.name, PS.mode(next)))
        val st = s() ?: return
        io.execute { val ok = HomeAssistant.setMode(st, r.entity, next); main.post { sent(ok) } }
    }

    private fun runScene(i: Int) {
        val st = s() ?: return
        val sc = st.scenes.getOrNull(i) ?: return
        val name = sc.name.ifEmpty { HomeAssistant.pretty(sc.entity) }
        flash(String.format(PS.STARTING, name))
        io.execute { val ok = HomeAssistant.runScene(st, sc.entity); main.post { flash(if (ok) String.format(PS.STARTED, name) else PS.FAILED) } }
    }

    // ------------------------------------------------------------------ keys

    fun key(code: Int): Boolean {
        val r = rows()
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (r.isNotEmpty() && col > 0) col--
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (r.isNotEmpty() && col < r[row].size - 1) col++
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val d = if (code == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                if (r.isNotEmpty() && r.getOrNull(row + d) != null) {
                    val x = centre(row, col)
                    row += d
                    var best = 0; var bd = Float.MAX_VALUE
                    for (c in r[row].indices) { val dd = Math.abs(centre(row, c) - x); if (dd < bd) { bd = dd; best = c } }
                    col = best
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                val item = r.getOrNull(row)?.getOrNull(col)
                if (item != null) {
                    if (item.scene >= 0) runScene(item.scene)
                    else rooms.getOrNull(item.room)?.let { room ->
                        when (item.k) { 0 -> nudge(room, -1); 1 -> nudge(room, 1); 2 -> power(room); else -> cycleMode(room) }
                    }
                }
            }
            else -> return false
        }
        changed()
        return true
    }

    // ------------------------------------------------------------------ layout (1080p numbers from couch-tv's CSS; u = height / 1080)

    private var u = 1f
    private val roomW get() = 408 * u
    private val roomGap get() = 32 * u

    private fun btnW(room: Int, k: Int): Float {
        if (k != 3) return 64 * u
        val r = rooms.getOrNull(room)
        text.typeface = Fonts.semibold(ctx); text.textSize = 22 * u
        val label = if (r != null && r.ok && !isOff(r)) PS.mode(r.state) else PS.MODE
        val tw = minOf(128 * u, text.measureText(label))
        return maxOf(64 * u, 14 * u * 2 + 30 * u + 8 * u + tw)
    }

    private fun firstRoom(): Int {
        val here = if (row == 0) col / 4 else 0
        return maxOf(0, minOf(here - (ROOMS_IN_VIEW - 1), rooms.size - ROOMS_IN_VIEW))
    }

    private fun centre(r: Int, c: Int): Float {
        val items = rows().getOrNull(r) ?: return 0f
        val item = items.getOrNull(c) ?: return 0f
        if (item.scene >= 0) {
            val i = item.scene % SCENES_PER_LINE
            return 80 * u + i * (332 * u + 20 * u) + 166 * u
        }
        val first = firstRoom()
        val rx = 80 * u + (item.room - first) * (roomW + roomGap)
        val widths = (0..3).map { k -> btnW(item.room, k) }
        val total = widths.sum() + 12 * u * 3
        var x = rx + (roomW - total) / 2
        for (k in 0 until item.k) x += widths[k] + 12 * u
        return x + widths[item.k] / 2
    }

    // ------------------------------------------------------------------ drawing

    fun draw(c: Canvas, w: Int, h: Int) {
        u = h / 1080f
        c.drawColor(Color.rgb(10, 8, 12))
        val st = s() ?: return
        text.textAlign = Paint.Align.LEFT; text.letterSpacing = 0f
        text.typeface = Fonts.semibold(ctx); text.color = Color.WHITE; text.textSize = 46 * u
        text.isFakeBoldText = true
        c.drawText(PS.SMART_HOME, 84 * u, 140 * u, text)
        text.isFakeBoldText = false
        var top = 184 * u   // a section's heading; its content starts 44 px lower
        if (st.climate.isNotEmpty()) {
            head(c, PS.CLIMATE, 84 * u, top)
            var y = top + 44 * u
            if (down) {
                text.typeface = Fonts.regular(ctx); text.textSize = 24 * u; text.color = Color.rgb(255, 196, 120)
                c.drawText(PS.DOWN, 84 * u, y + 20 * u, text)
                y += 34 * u + 16 * u
            }
            val first = firstRoom()
            c.save(); c.clipRect(0f, y - 16 * u, w.toFloat(), y + 440 * u + 16 * u)
            rooms.forEachIndexed { i, room -> drawRoom(c, room, i, 80 * u + (i - first) * (roomW + roomGap), y) }
            c.restore()
            top = y + 440 * u + 28 * u
        }
        if (st.scenes.isNotEmpty()) {
            head(c, PS.SCENES, 84 * u, top)
            val y = top + 44 * u
            val sceneRow = if (rooms.isNotEmpty()) 1 else 0
            st.scenes.forEachIndexed { i, sc ->
                val line = i / SCENES_PER_LINE; val k = i % SCENES_PER_LINE
                val x = 80 * u + k * (332 * u + 20 * u); val yy = y + line * (80 * u + 20 * u)
                drawScene(c, sc, x, yy, row == sceneRow + line && col == k)
            }
        }
        if (msg.isNotEmpty()) {
            text.typeface = Fonts.medium(ctx); text.textSize = 23 * u; text.color = Color.rgb(143, 211, 255)
            c.drawText(msg, 84 * u, 984 * u, text)
        }
        text.typeface = Fonts.regular(ctx); text.textSize = 20 * u; text.color = Color.argb(128, 255, 255, 255)
        c.drawText(PS.SH_HINT, 84 * u, 1012 * u, text)
    }

    private fun head(c: Canvas, label: String, x: Float, top: Float) {
        text.typeface = Fonts.semibold(ctx); text.textSize = 20 * u; text.letterSpacing = 0.06f; text.color = Color.argb(128, 255, 255, 255)
        c.drawText(label.uppercase(), x, top + 22 * u, text)
        text.letterSpacing = 0f
    }

    private fun drawRoom(c: Canvas, room: HomeAssistant.Room, index: Int, x: Float, y: Float) {
        val ok = room.ok; val off = isOff(room)
        val heat = room.state == "heat" || room.action == "heating"
        val here = row == 0 && col / 4 == index
        rect.set(x, y, x + roomW, y + 440 * u)
        paint.style = Paint.Style.FILL; paint.color = Color.argb(if (here) 36 else 20, 255, 255, 255)
        c.drawRoundRect(rect, 32 * u, 32 * u, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = u; paint.color = Color.argb(26, 255, 255, 255)
        rect.inset(u / 2, u / 2); c.drawRoundRect(rect, 32 * u, 32 * u, paint)
        // the name
        text.typeface = Fonts.semibold(ctx); text.textSize = 30 * u; text.color = Color.WHITE
        val nm = TextUtils.ellipsize(room.name, text, roomW - 56 * u, TextUtils.TruncateAt.END)
        c.drawText(nm, 0, nm.length, x + 28 * u, y + 28 * u + 30 * u, text)
        // the dial: 216 px, a 270 degree track from the bottom left
        val dc = x + roomW / 2; val dy = y + 28 * u + 38 * u + 12 * u + 108 * u
        val rad = 42f * 2.16f * u
        rect.set(dc - rad, dy - rad, dc + rad, dy + rad)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 8 * 2.16f * u; paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.argb(36, 255, 255, 255)
        c.drawArc(rect, 135f, 270f, false, paint)
        if (canSet(room)) {
            val frac = ((room.target!! - room.min) / maxOf(1.0, room.max - room.min)).coerceIn(0.02, 1.0).toFloat()
            paint.color = if (off) Color.argb(77, 255, 255, 255) else if (heat) Color.rgb(255, 180, 110) else Color.rgb(159, 216, 255)
            c.drawArc(rect, 135f, 270f * frac, false, paint)
        }
        paint.strokeCap = Paint.Cap.BUTT
        val big = if (!ok) "" else if (room.target != null) deg(room.target) else if (room.low != null && room.high != null) deg(room.low) + " " + deg(room.high) else ""
        val sub = if (room.loading) PS.CHECKING else if (!ok) PS.ROOM_QUIET else if (room.target != null) PS.SET_TO else if (room.low != null) PS.RANGE else ""
        text.typeface = Fonts.antonio(ctx); text.textSize = 88 * u; text.textAlign = Paint.Align.CENTER; text.letterSpacing = -0.03f
        text.color = if (!ok || off) Color.argb(168, 255, 255, 255) else Color.WHITE
        c.drawText(big, dc, dy + 20 * u, text)
        text.letterSpacing = 0f
        text.typeface = Fonts.regular(ctx); text.textSize = 21 * u; text.color = Color.argb(168, 255, 255, 255)
        c.drawText(sub, dc, dy + 20 * u + 34 * u, text)
        // "now" line
        val now = (if (ok && room.current != null) String.format(PS.NOW, deg(room.current)) + " · " else "") + (if (ok) actionWord(room) else "")
        text.textSize = 24 * u
        val nw = TextUtils.ellipsize(now, text, roomW - 56 * u, TextUtils.TruncateAt.END)
        c.drawText(nw, 0, nw.length, dc, y + 28 * u + 38 * u + 12 * u + 216 * u + 4 * u + 26 * u, text)
        text.textAlign = Paint.Align.LEFT
        // the four buttons
        val by = y + 440 * u - 24 * u - 64 * u
        val widths = (0..3).map { btnW(index, it) }
        var bx = x + (roomW - (widths.sum() + 12 * u * 3)) / 2
        for (k in 0..3) {
            val focus = row == 0 && col == index * 4 + k
            button(c, k, room, bx, by, widths[k], focus, ok, off, heat)
            bx += widths[k] + 12 * u
        }
    }

    private fun actionWord(r: HomeAssistant.Room): String {
        if (isOff(r)) return PS.OFF
        val a = PS.action(r.action)
        return if (a.isNotEmpty()) a else PS.mode(r.state)
    }

    private fun button(c: Canvas, k: Int, room: HomeAssistant.Room, x: Float, y: Float, w: Float, focus: Boolean, ok: Boolean, off: Boolean, heat: Boolean) {
        c.save()
        if (focus) { c.translate(x + w / 2, y + 32 * u); c.scale(1.12f, 1.12f); c.translate(-(x + w / 2), -(y + 32 * u)) }
        rect.set(x, y, x + w, y + 64 * u)
        if (focus) {
            paint.style = Paint.Style.FILL; paint.color = Color.argb(34, 0, 0, 0)
            for (i in 6 downTo 1) { rect.set(x - i * 4 * u, y + 12 * u - i * 4 * u, x + w + i * 4 * u, y + 64 * u + 12 * u + i * 4 * u); c.drawRoundRect(rect, 32 * u + i * 4 * u, 32 * u + i * 4 * u, paint) }
            rect.set(x, y, x + w, y + 64 * u)
        }
        paint.style = Paint.Style.FILL
        paint.color = when {
            focus -> Color.WHITE
            k == 2 && ok && !off -> if (heat) Color.argb(82, 255, 180, 110) else Color.argb(77, 159, 216, 255)
            else -> Color.argb(31, 255, 255, 255)
        }
        c.drawRoundRect(rect, 32 * u, 32 * u, paint)
        val fg = if (focus) Color.rgb(17, 17, 17) else Color.argb(235, 255, 255, 255)
        val cy = y + 32 * u
        val ix = if (k == 3) x + 14 * u + 15 * u else x + w / 2
        icon(c, k, ix, cy, fg)
        if (k == 3) {
            text.typeface = Fonts.semibold(ctx); text.textSize = 22 * u; text.color = fg; text.textAlign = Paint.Align.LEFT
            val label = if (ok && !off) PS.mode(room.state) else PS.MODE
            val l = TextUtils.ellipsize(label, text, 128 * u, TextUtils.TruncateAt.END)
            c.drawText(l, 0, l.length, x + 14 * u + 30 * u + 8 * u, cy + 8 * u, text)
        }
        c.restore()
    }

    /** The four button icons, 30 px, a 2 px round stroke on a 24 unit grid (couch-tv's SVG paths). */
    private fun icon(c: Canvas, k: Int, cx: Float, cy: Float, color: Int) {
        val g = 30f * u / 24f
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2 * g; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND; paint.color = color
        fun px(v: Float) = cx + (v - 12f) * g
        fun py(v: Float) = cy + (v - 12f) * g
        when (k) {
            0 -> c.drawLine(px(6f), py(12f), px(18f), py(12f), paint)
            1 -> { c.drawLine(px(6f), py(12f), px(18f), py(12f), paint); c.drawLine(px(12f), py(6f), px(12f), py(18f), paint) }
            2 -> {
                c.drawLine(px(12f), py(3.5f), px(12f), py(11.5f), paint)
                rect.set(px(4.5f), py(4.5f), px(19.5f), py(19.5f)); c.drawArc(rect, -50f, -260f, false, paint)
            }
            else -> { c.drawLine(px(12f), py(3f), px(12f), py(21f), paint); c.drawLine(px(4.2f), py(7.5f), px(19.8f), py(16.5f), paint); c.drawLine(px(4.2f), py(16.5f), px(19.8f), py(7.5f), paint) }
        }
        paint.style = Paint.Style.FILL; paint.strokeCap = Paint.Cap.BUTT; paint.strokeJoin = Paint.Join.MITER
    }

    private fun drawScene(c: Canvas, sc: HomeAssistant.Named, x: Float, y: Float, focus: Boolean) {
        c.save()
        val w = 332 * u; val h = 80 * u
        if (focus) { c.translate(x + w / 2, y + h / 2); c.scale(1.06f, 1.06f); c.translate(-(x + w / 2), -(y + h / 2)) }
        rect.set(x, y, x + w, y + h)
        if (focus) {
            paint.style = Paint.Style.FILL; paint.color = Color.argb(34, 0, 0, 0)
            for (i in 6 downTo 1) { rect.set(x - i * 5 * u, y + 16 * u - i * 5 * u, x + w + i * 5 * u, y + h + 16 * u + i * 5 * u); c.drawRoundRect(rect, 24 * u + i * 5 * u, 24 * u + i * 5 * u, paint) }
            rect.set(x, y, x + w, y + h)
        }
        paint.style = Paint.Style.FILL; paint.color = if (focus) Color.WHITE else Color.argb(26, 255, 255, 255)
        c.drawRoundRect(rect, 24 * u, 24 * u, paint)
        // the icon box: 52 px, 16 px in, soft inner tile
        rect.set(x + 16 * u, y + 14 * u, x + 16 * u + 52 * u, y + 14 * u + 52 * u)
        paint.color = if (focus) Color.argb(18, 0, 0, 0) else Color.argb(31, 255, 255, 255)
        c.drawRoundRect(rect, 16 * u, 16 * u, paint)
        val fg = if (focus) Color.rgb(17, 17, 17) else Color.argb(235, 255, 255, 255)
        val cx = x + 16 * u + 26 * u; val cy = y + 40 * u
        val g = 28f * u / 24f
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.7f * g; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND; paint.color = fg
        if (sc.entity.startsWith("script.")) {   // a play triangle
            path.reset(); path.moveTo(cx + (8f - 12f) * g, cy + (5.5f - 12f) * g); path.lineTo(cx + (19f - 12f) * g, cy); path.lineTo(cx + (8f - 12f) * g, cy + (18.5f - 12f) * g); path.close()
            c.drawPath(path, paint)
        } else {   // a star
            val pts = arrayOf(12f to 3.5f, 14.4f to 8.5f, 19.8f to 9.1f, 15.8f to 12.8f, 16.9f to 18.2f, 12f to 15.5f, 7.1f to 18.2f, 8.2f to 12.8f, 4.2f to 9.1f, 9.6f to 8.5f)
            path.reset(); pts.forEachIndexed { i, p -> val xx = cx + (p.first - 12f) * g; val yy = cy + (p.second - 12f) * g; if (i == 0) path.moveTo(xx, yy) else path.lineTo(xx, yy) }
            path.close(); c.drawPath(path, paint)
        }
        paint.style = Paint.Style.FILL; paint.strokeCap = Paint.Cap.BUTT; paint.strokeJoin = Paint.Join.MITER
        text.typeface = Fonts.semibold(ctx); text.textSize = 26 * u; text.color = fg; text.textAlign = Paint.Align.LEFT
        val name = sc.name.ifEmpty { HomeAssistant.pretty(sc.entity) }
        val l = TextUtils.ellipsize(name, text, w - 84 * u - 28 * u, TextUtils.TruncateAt.END)
        c.drawText(l, 0, l.length, x + 84 * u, y + h / 2 + 9 * u, text)
        c.restore()
    }

    private companion object {
        const val SCENES_PER_LINE = 5
        const val ROOMS_IN_VIEW = 4
        const val REFRESH_MS = 20_000L
        const val JOIN_MS = 700L
    }
}
