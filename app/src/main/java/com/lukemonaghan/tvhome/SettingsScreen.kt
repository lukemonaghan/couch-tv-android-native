package com.lukemonaghan.tvhome

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.KeyEvent

/**
 * Home settings, drawn the way couch-tv's are: a title and a sidebar of pages on the left, the page's rows on
 * the right, white focus. Plain Canvas like the rest of the home. Settings live in the "home" prefs; the screen
 * only changes them and calls [changed] so the home redraws.
 */
class SettingsScreen(
    private val ctx: Context,
    private val prefs: android.content.SharedPreferences,
    private val store: AppStore,
    private val weather: Weather,
    private val act: HomeActions,
    private val peopleSettings: PeopleSettings,
    private val changed: () -> Unit,
) {
    init { peopleSettings.onNavigate = { row = 0; scroll = 0 }; peopleSettings.redraw = { changed() } }
    private class Row(val title: String, val note: String = "", val value: String = "", val check: Boolean? = null,
                       val header: Boolean = false, val avatar: Person? = null,
                       val preview: String? = null, val menu: List<Pair<String, () -> Unit>>? = null,
                       val holdMenu: List<Pair<String, () -> Unit>>? = null, val press: (() -> Unit)? = null)

    private val lookSet by lazy { LookSettings(ctx, prefs, act, changed) }
    private fun LookRow.row() = Row(title, note, value, check, header, null, press = press)

    private val pages = listOf("Location", "Weather", "Look and scenes", "Apps", "People", "Quick buttons", "Idle and sound", "Music visualiser", "Smart home", "Updates", "About")
    private var page = 0
    private var inRows = false
    private var row = 0
    private var scroll = 0
    private var lift: String? = null   // a quick button picked up with hold OK: up and down move it
    private val quickSys by lazy { QuickSystem(ctx, prefs) }
    private var results: List<Weather.Place>? = null   // the place search answer, shown on the Location page

    fun open() {
        page = 0; inRows = false; row = 0; scroll = 0; results = null; peopleSettings.reset()
        pvKey = ""; VizSettings.reset(); VizSettings.onNavigate = { row = 0; scroll = 0 }; PresetStore.onChange = { changed() }
    }

    /** Back: one step out. False when the screen should close. */
    fun back(): Boolean {
        if (lift != null) { lift = null; return true }
        if (results != null) { results = null; row = 0; return true }
        if (pages[page] == "People" && peopleSettings.back()) return true
        if (pages[page] == "Music visualiser" && VizSettings.back()) return true
        if (inRows) { inRows = false; return true }
        return false
    }

    // ------------------------------------------------------------------ the rows of each page

    private fun enabledScenes(): List<String> = prefs.getString("scenes", null)?.split(',') ?: SceneRenderer.ALL.map { it.id }

    private fun rows(): List<Row> = when (pages[page]) {
        "Location" -> {
            val r = results
            if (r != null) {
                if (r.isEmpty()) listOf(Row("No places found", "Back, then search again"))
                else r.map { p -> Row(p.name, p.detail) { weather.setPlace(p.lat, p.lon, p.name); results = null; row = 0 } }
            } else listOf(
                Row("Place", "Used for the weather", weather.placeName.ifEmpty { "Not set" }) {
                    act.askText("Search for a suburb or city") { q ->
                        weather.search(q) { found -> results = found; row = 0; scroll = 0; changed() }
                    }
                },
            )
        }
        "Weather" -> listOf(
            Row("Show the weather", "On the date line", check = weather.enabled) { weather.enabled = !weather.enabled },
            Row("Temperature", "", if (weather.fahrenheit) "Fahrenheit" else "Celsius") { weather.fahrenheit = !weather.fahrenheit },
        )
        "Look and scenes" -> {
            val look = prefs.getString("look", "NIGHT")
            fun pick(k: String) { prefs.edit().putString("look", k).apply() }
            listOf(
                Row("Night", "Glass clock over the scene, Keep watching and a dock", check = look == "NIGHT") { pick("NIGHT") },
                Row("OLED", "Pure black, a grid of your apps", check = look == "OLED") { pick("OLED") },
                Row("Classic", "One photo and a grid of app banners", check = look == "CLASSIC") { pick("CLASSIC") },
                Row("Dark", "A solid dark colour, a grid of your apps", check = look == "DARK") { pick("DARK") },
                Row("Follow the time of day", "Dawn, day, sunset and night pick the scene and photo", check = prefs.getBoolean("timeSync", true)) {
                    prefs.edit().putBoolean("timeSync", !prefs.getBoolean("timeSync", true)).apply()
                },
            ) + lookSet.photoRows(look).map { it.row() } + Row("SCENES", header = true) + SceneRenderer.ALL.map { sp ->
                val on = enabledScenes()
                Row(sp.label, check = sp.id in on) {
                    val now = enabledScenes().toMutableList()
                    if (sp.id in now) { if (now.size > 1) now.remove(sp.id) } else now.add(sp.id)
                    prefs.edit().putString("scenes", SceneRenderer.ALL.filter { it.id in now }.joinToString(",") { it.id }).apply()
                }
            } + listOf(
                Row("Each scene stays", "", "${prefs.getInt("sceneSeconds", 20)} seconds") {
                    val opts = listOf(10, 20, 30, 60)
                    prefs.edit().putInt("sceneSeconds", opts[(opts.indexOf(prefs.getInt("sceneSeconds", 20)) + 1) % opts.size]).apply()
                },
                Row("Clock", "", when (prefs.getInt("clock", 0)) { 1 -> "12 hour"; 2 -> "24 hour"; else -> "Follow the TV" }) {
                    prefs.edit().putInt("clock", (prefs.getInt("clock", 0) + 1) % 3).apply()
                },
                Row("Clock on music view", "", if (prefs.getBoolean("np_clock", false)) "On" else "Off") {
                    prefs.edit().putBoolean("np_clock", !prefs.getBoolean("np_clock", false)).apply()
                },
            ) + lookSet.rows().map { it.row() }
        }
        "Apps" -> {
            val list = mutableListOf(
                Row("Apps in the dock", "The rest are behind More apps", "${store.dockSize}") {
                    store.dockSize = if (store.dockSize >= 10) 3 else store.dockSize + 1
                    prefs.edit().putInt("dockSize", store.dockSize).apply()
                    store.reload()
                },
            )
            // OK on an app moves it between home, More apps and hidden; the focus follows it
            fun app(it: AppItem, where: String) = Row(it.label, value = where) {
                val title = it.label
                store.cycle(it.pkg)
                row = rows().indexOfFirst { r -> r.title == title && !r.header }.coerceAtLeast(0)
            }
            list += Row("ON HOME (${store.line} OF ${store.dockSize})", header = true)
            store.dock.forEach { list += app(it, "On home") }
            list += Row("IN MORE APPS", header = true)
            store.items.drop(store.line).forEach { list += app(it, "More apps") }
            if (store.hiddenItems.isNotEmpty()) {
                list += Row("HIDDEN", header = true)
                store.hiddenItems.forEach { list += app(it, "Hidden") }
            }
            list += Row("TV INPUTS", header = true)
            if (store.detectedInputs.isEmpty()) list += Row("No TV inputs found", "This TV lists none to put on the home")
            else store.detectedInputs.forEach { p ->
                val on = p.id in store.pinnedInputs
                list += Row(p.label.ifEmpty { if (p.type == "hdmi") "HDMI" else "Input" }, p.devices.joinToString(", ").ifEmpty { "Put it on the home as a tile" }, check = on) { store.setPinned(p.id, !on) }
            }
            list += Row("OK moves an app between home, More apps and hidden", "Hold OK on an app on the home to change the order")
            list
        }
        "People" -> peopleSettings.rows().map { r -> Row(r.title, r.note, r.value, r.check, r.header, r.avatar, press = r.press) }
        "Quick buttons" -> {
            val on = QuickButtons.enabled(prefs)
            QuickButtons.listed(prefs).map { id ->
                val k = QuickButtons.kind(id)
                val note = if (id == "screensaver" && !quickSys.screensaverReady()) "Needs a screensaver and \"modify system settings\" (the install script grants it)" else k.note
                Row(k.label, note, if (lift == id) "Up or down to move" else "", check = id in on) {
                    if (lift == id) lift = null else QuickButtons.toggle(prefs, id)
                }
            }
        }
        "Smart home" -> PlatformPages.smart(ctx).map(::pr)
        "Updates" -> PlatformPages.updates(ctx).map(::pr)
        "Idle and sound" -> IdleSettings.rows(ctx, prefs).map { Row(it.title, it.note, it.value, it.check, it.header, null, press = it.press) }
        "Music visualiser" -> VizSettings.rows(ctx, prefs).map { Row(it.title, it.note, it.value, it.check, it.header, null, it.preview, it.menu, it.holdMenu, it.press) }
        else -> PlatformPages.aboutTop(ctx).map(::pr) + listOf(
            Row("tvhome", "A light home screen for Android TV", "0.1"),
            Row("Licences", "projectM LGPL-2.1, fonts OFL-1.1, couch-tv MIT", "Source: github.com/lukemonaghan"),
            Row("Android settings", "Wi-Fi, display, accounts and the rest") { act.openSettings() },
        ) + PlatformPages.aboutBottom(prefs, store) { changed() }.map(::pr)
    }

    private fun pr(p: PlatformPages.PRow) = Row(p.title, p.note, p.value, p.check, p.header, null, press = p.press)

    // ------------------------------------------------------------------ keys

    /** Hold OK on a switched-on quick button picks it up to reorder. False when the row is not one. */
    fun hold(): Boolean {
        if (inRows && pages[page] == "Music visualiser") { val m = rows().getOrNull(row)?.holdMenu ?: return false; openMenu(m); return true }
        if (!inRows || pages[page] != "Quick buttons") return false
        val id = QuickButtons.listed(prefs).getOrNull(row) ?: return false
        if (id !in QuickButtons.enabled(prefs)) return false
        lift = id
        changed()
        return true
    }

    fun key(code: Int): Boolean {
        lift?.let { id ->
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val d = if (code == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                    QuickButtons.move(prefs, id, d)
                    row = QuickButtons.listed(prefs).indexOf(id).coerceAtLeast(0)
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> lift = null
            }
            changed()
            return true
        }
        peopleSettings.pad?.let { it.key(code); changed(); return true }
        val list = rows()
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> if (inRows) { do { if (row > 0) row-- } while (list.getOrNull(row)?.header == true && row > 0) } else if (page > 0) { page--; row = 0; results = null }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (inRows) { do { if (row < list.size - 1) row++ } while (list.getOrNull(row)?.header == true && row < list.size - 1) } else if (page < pages.size - 1) { page++; row = 0; results = null }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (!inRows) { inRows = true; row = 0 }
            KeyEvent.KEYCODE_DPAD_LEFT -> if (inRows) back()
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (!inRows) { inRows = true; row = 0 } else list.getOrNull(row)?.let { r -> if (r.menu != null) openMenu(r.menu) else r.press?.invoke() }
            }
            else -> return false
        }
        changed()
        return true
    }

    /** OK on a preset: its choices in a small list at the row. */
    private fun openMenu(items: List<Pair<String, () -> Unit>>) {
        val u = ctx.resources.displayMetrics.heightPixels / 1080f
        val y = 190 * u + (row - scroll) * 86 * u
        val low = y > ctx.resources.displayMetrics.heightPixels * 0.55f
        ListMenu.show(ctx, 1390 * u, if (low) y else y + 78 * u, items.map { it.first }, 0, low) { i -> items[i].second(); changed() }
    }

    // ------------------------------------------------------------------ the live preview

    private var pvKey = ""
    private fun syncPreview(play: String?, u: Float) {
        val k = play ?: ""
        if (k == pvKey) return
        pvKey = k
        VizSettings.previewShown = play != null
        act.vizPreview(play, (1440 * u).toInt(), (190 * u).toInt(), (400 * u).toInt(), (225 * u).toInt())
    }

    /** Settings left, or a row without a picture: the preview goes away. */
    fun closePreview() { pvKey = ""; VizSettings.previewShown = false; act.vizPreview(null, 0, 0, 0, 0) }

    private fun wrap(t: String, max: Float, lines: Int): List<String> {
        val out = ArrayList<String>(); var rest = t
        while (rest.isNotEmpty() && out.size < lines) {
            var n = text.breakText(rest, true, max, null)
            if (n < rest.length) { val sp = rest.lastIndexOf(' ', n); if (sp > n / 2) n = sp }
            if (out.size == lines - 1 && n < rest.length) { out += TextUtils.ellipsize(rest, text, max, TextUtils.TruncateAt.END).toString(); break }
            out += rest.substring(0, n).trim(); rest = rest.substring(n).trim()
        }
        return out
    }

    // ------------------------------------------------------------------ drawing

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun draw(c: Canvas, w: Int, h: Int) {
        val u = h / 1080f
        c.drawColor(Color.rgb(13, 13, 17))
        text.typeface = Fonts.semibold(ctx); text.color = Color.WHITE; text.textAlign = Paint.Align.LEFT
        text.textSize = 42 * u
        c.drawText("Home settings", 100 * u, 140 * u, text)

        // the sidebar: 82 px apart, an icon tile and the page name
        for (i in pages.indices) {
            val y = 195 * u + i * 82 * u
            val on = i == page
            if (on) {
                paint.style = Paint.Style.FILL
                paint.color = if (!inRows) Color.WHITE else Color.argb(36, 255, 255, 255)
                rect.set(80 * u, y, 656 * u, y + 70 * u)
                c.drawRoundRect(rect, 18 * u, 18 * u, paint)
            }
            val dark = on && !inRows
            paint.color = if (dark) Color.argb(30, 0, 0, 0) else Color.argb(36, 255, 255, 255)
            rect.set(96 * u, y + 11 * u, 144 * u, y + 59 * u)
            c.drawRoundRect(rect, 13 * u, 13 * u, paint)
            icon(c, i, 120 * u, y + 35 * u, u, if (dark) Color.rgb(17, 17, 17) else Color.argb(220, 255, 255, 255))
            text.typeface = Fonts.semibold(ctx); text.textSize = 29 * u
            text.color = if (dark) Color.rgb(17, 17, 17) else if (on) Color.WHITE else Color.argb(184, 255, 255, 255)
            c.drawText(pages[i], 166 * u, y + 46 * u, text)
        }

        // the page
        text.typeface = Fonts.semibold(ctx); text.color = Color.WHITE; text.textSize = 38 * u
        c.drawText(if (results != null) "Pick a place" else if (pages[page] == "People") peopleSettings.title() else pages[page], 748 * u, 140 * u, text)
        val list = rows()
        val showPanel = pages[page] == "Music visualiser" && list.any { it.preview != null }
        val R = (if (showPanel) 1400 else 1840) * u   // right edge of the rows
        val rh = 78 * u; val gap = 8 * u
        val top = 190 * u
        val fit = ((h - top - 110 * u) / (rh + gap)).toInt().coerceAtLeast(1)
        if (row < scroll) scroll = row
        if (row >= scroll + fit) scroll = row - fit + 1
        for (i in list.indices) {
            val r = i - scroll
            if (r < 0 || r >= fit) continue
            val y = top + r * (rh + gap)
            val focus = inRows && i == row
            val rw = list[i]
            if (rw.header) {
                text.typeface = Fonts.semibold(ctx); text.textSize = 21 * u; text.letterSpacing = 0.08f; text.color = Color.argb(130, 255, 255, 255)
                c.drawText(rw.title, 746 * u, y + 52 * u, text); text.letterSpacing = 0f
                continue
            }
            rect.set(720 * u, y, R, y + rh)
            paint.style = Paint.Style.FILL
            paint.color = if (focus) Color.WHITE else Color.argb(24, 255, 255, 255)
            c.drawRoundRect(rect, 18 * u, 18 * u, paint)
            val fg = if (focus) Color.rgb(17, 17, 17) else Color.WHITE
            text.typeface = Fonts.semibold(ctx); text.color = fg; text.textSize = 29 * u
            val tx = if (rw.avatar != null) 746 * u + 64 * u else 746 * u
            if (rw.avatar != null) peopleSettings.ui.avatar(c, 746 * u + 24 * u, y + rh / 2, 24 * u, rw.avatar, u)
            text.typeface = Fonts.medium(ctx); text.textSize = 27 * u
            val vw = if (rw.value.isNotEmpty()) minOf(380 * u, text.measureText(rw.value)) + 30 * u else 0f
            text.typeface = Fonts.semibold(ctx); text.textSize = 29 * u
            val avail = R - 720 * u - 60 * u - (if (rw.avatar != null) 64 * u else 0f) - vw - (if (rw.check != null) 56 * u else 0f)
            val t = TextUtils.ellipsize(rw.title, text, avail, TextUtils.TruncateAt.END)
            val twoLines = rw.note.isNotEmpty()
            c.drawText(t, 0, t.length, tx, y + (if (twoLines) 35 else 48) * u, text)
            if (twoLines) {
                text.typeface = Fonts.regular(ctx); text.textSize = 21 * u
                text.color = if (focus) Color.argb(160, 17, 17, 17) else Color.argb(150, 255, 255, 255)
                val n = TextUtils.ellipsize(rw.note, text, avail, TextUtils.TruncateAt.END)
                c.drawText(n, 0, n.length, tx, y + 62 * u, text)
            }
            if (rw.value.isNotEmpty()) {
                text.typeface = Fonts.medium(ctx); text.textSize = 27 * u; text.textAlign = Paint.Align.RIGHT
                text.color = if (focus) Color.argb(170, 17, 17, 17) else Color.argb(150, 255, 255, 255)
                val v = TextUtils.ellipsize(rw.value, text, 380 * u, TextUtils.TruncateAt.END)
                c.drawText(v, 0, v.length, R - 30 * u - (if (rw.check != null) 52 * u else 0f), y + 48 * u, text)
                text.textAlign = Paint.Align.LEFT
            }
            if (rw.check == true) {
                paint.color = Color.rgb(48, 209, 88)
                val kx = R - 44 * u
                c.drawCircle(kx, y + rh / 2, 17 * u, paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 3.4f * u; paint.color = Color.WHITE; paint.strokeCap = Paint.Cap.ROUND
                c.drawLine(kx - 9 * u, y + rh / 2, kx - 2 * u, y + rh / 2 + 7 * u, paint)
                c.drawLine(kx - 2 * u, y + rh / 2 + 7 * u, kx + 10 * u, y + rh / 2 - 7 * u, paint)
                paint.style = Paint.Style.FILL
            } else if (rw.check == false) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f * u; paint.color = if (focus) Color.argb(120, 0, 0, 0) else Color.argb(110, 255, 255, 255)
                c.drawCircle(R - 44 * u, y + rh / 2, 16 * u, paint)
                paint.style = Paint.Style.FILL
            }
        }
        var play: String? = null
        if (showPanel) {
            val pth = if (inRows) list.getOrNull(row)?.preview else null
            val px = 1440 * u; val py = 190 * u; val pw = 400 * u; val ph = 225 * u
            paint.style = Paint.Style.FILL; paint.color = Color.argb(30, 255, 255, 255)
            rect.set(px - 3 * u, py - 3 * u, px + pw + 3 * u, py + ph + 3 * u); c.drawRoundRect(rect, 6 * u, 6 * u, paint)
            if (pth != null) {
                val pn = VizSettings.panel(ctx, prefs, pth)
                play = pn.play
                if (play == null) {   // no picture: the same box, with a word
                    paint.color = Color.argb(60, 0, 0, 0); rect.set(px, py, px + pw, py + ph); c.drawRect(rect, paint)
                    text.typeface = Fonts.regular(ctx); text.textSize = 22 * u; text.color = Color.argb(140, 255, 255, 255); text.textAlign = Paint.Align.CENTER
                    c.drawText("No preview", px + pw / 2, py + ph / 2 + 8 * u, text); text.textAlign = Paint.Align.LEFT
                }
                var ty = py + ph + 46 * u
                text.typeface = Fonts.semibold(ctx); text.textSize = 28 * u; text.color = Color.WHITE
                for (ln in wrap(pn.name, pw, 3)) { c.drawText(ln, px, ty, text); ty += 36 * u }
                text.typeface = Fonts.regular(ctx); text.textSize = 21 * u; text.color = Color.argb(150, 255, 255, 255)
                ty += 4 * u
                val wh = TextUtils.ellipsize(pn.where, text, pw, TextUtils.TruncateAt.END); c.drawText(wh, 0, wh.length, px, ty, text); ty += 34 * u
                text.color = Color.argb(200, 255, 255, 255)
                for (l in pn.lines) for (ln in wrap(l, pw, 2)) { c.drawText(ln, px, ty, text); ty += 30 * u }
            } else {
                text.typeface = Fonts.regular(ctx); text.textSize = 22 * u; text.color = Color.argb(120, 255, 255, 255); text.textAlign = Paint.Align.CENTER
                c.drawText("Move to a preset to see it", px + pw / 2, py + ph / 2 + 8 * u, text); text.textAlign = Paint.Align.LEFT
            }
        }
        syncPreview(play, u)
        text.typeface = Fonts.regular(ctx); text.textSize = 21 * u; text.color = Color.argb(130, 255, 255, 255)
        c.drawText(if (pages[page] == "Quick buttons") "OK to switch on or off, hold OK on one that is on to move it, Back to go back" else "OK to change, Back to go back", 748 * u, 1005 * u, text)
        peopleSettings.pad?.draw(c, w, h)
    }

    /** Small line icons on the sidebar tiles. */
    private fun icon(c: Canvas, i: Int, cx: Float, cy: Float, u: Float, color: Int) {
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.6f * u; paint.color = color; paint.strokeCap = Paint.Cap.ROUND
        val k = u
        when (pages[i]) {   // the platform pages are found by name, so adding a page never shifts the others
            "Smart home" -> {   // a house
                c.drawLine(cx - 12 * k, cy - 1 * k, cx, cy - 11 * k, paint); c.drawLine(cx, cy - 11 * k, cx + 12 * k, cy - 1 * k, paint)
                c.drawLine(cx - 8 * k, cy - 4 * k, cx - 8 * k, cy + 11 * k, paint); c.drawLine(cx + 8 * k, cy - 4 * k, cx + 8 * k, cy + 11 * k, paint)
                c.drawLine(cx - 8 * k, cy + 11 * k, cx + 8 * k, cy + 11 * k, paint)
                paint.style = Paint.Style.FILL; paint.strokeCap = Paint.Cap.BUTT; return
            }
            "Music visualiser" -> {   // equaliser bars
                for ((n, hh) in intArrayOf(7, 14, 10, 17, 8).withIndex()) { val x = cx - 12 * k + n * 6 * k; c.drawLine(x, cy + 9 * k, x, cy + 9 * k - hh * k, paint) }
                paint.style = Paint.Style.FILL; paint.strokeCap = Paint.Cap.BUTT; return
            }
            "Updates" -> {   // an arrow up out of a tray
                c.drawLine(cx, cy + 5 * k, cx, cy - 11 * k, paint); c.drawLine(cx - 6 * k, cy - 5 * k, cx, cy - 11 * k, paint); c.drawLine(cx + 6 * k, cy - 5 * k, cx, cy - 11 * k, paint)
                c.drawLine(cx - 10 * k, cy + 11 * k, cx + 10 * k, cy + 11 * k, paint)
                paint.style = Paint.Style.FILL; paint.strokeCap = Paint.Cap.BUTT; return
            }
        }
        when (i) {
            0 -> { c.drawCircle(cx, cy - 3 * k, 8 * k, paint); c.drawLine(cx, cy + 5 * k, cx, cy + 13 * k, paint) }                // pin
            1 -> { c.drawCircle(cx - 3 * k, cy + 2 * k, 7 * k, paint); c.drawCircle(cx + 6 * k, cy + 4 * k, 5 * k, paint) }        // cloud
            2 -> { rect.set(cx - 12 * k, cy - 9 * k, cx + 12 * k, cy + 9 * k); c.drawRoundRect(rect, 3 * k, 3 * k, paint) }        // picture
            4 -> { c.drawCircle(cx, cy - 5 * k, 6 * k, paint); c.drawArc(cx - 11 * k, cy + 2 * k, cx + 11 * k, cy + 22 * k, 200f, 140f, false, paint) }
            5 -> { c.drawLine(cx + 3 * k, cy - 12 * k, cx - 5 * k, cy + 2 * k, paint); c.drawLine(cx - 5 * k, cy + 2 * k, cx + 5 * k, cy + 2 * k, paint); c.drawLine(cx + 5 * k, cy + 2 * k, cx - 3 * k, cy + 12 * k, paint) }
            3 -> for (a in 0..1) for (b in 0..1) { rect.set(cx - 11 * k + a * 13 * k, cy - 11 * k + b * 13 * k, cx - 1 * k + a * 13 * k, cy - 1 * k + b * 13 * k); c.drawRoundRect(rect, 2 * k, 2 * k, paint) }
            else -> { c.drawCircle(cx, cy, 11 * k, paint); c.drawLine(cx, cy - 1 * k, cx, cy + 6 * k, paint); c.drawPoint(cx, cy - 6 * k, paint) }
        }
        paint.style = Paint.Style.FILL; paint.strokeCap = Paint.Cap.BUTT
    }
}
