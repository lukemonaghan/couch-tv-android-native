package com.lukemonaghan.tvhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.text.TextPaint
import android.text.format.DateFormat
import android.view.KeyEvent
import android.view.SoundEffectConstants
import android.view.View
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

/** What the home asks the Activity to do. */
interface HomeActions {
    fun launch(pkg: String)
    fun openSettings()
    fun askText(title: String, done: (String) -> Unit)
    fun openCard(pkg: String, intentUri: String?)
    fun uninstall(pkg: String)
    fun appInfo(pkg: String)
}

/**
 * The whole home, drawn on one Canvas: the clock, the dock (apps, then Settings and More apps), the
 * More apps grid, the hold-OK menu and the move mode. No child views, no adapters, no libraries.
 * The remote's D-pad drives everything through the key callbacks below.
 */
class HomeView(ctx: Context, private val store: AppStore, private val act: HomeActions) : View(ctx) {
    private enum class Mode { DOCK, MORE, MENU, MOVING, CARDS, SETTINGS }
    /** Night: the scene with the big glass clock and the dock. OLED: a grid on pure black. Classic: a grid over the photo. */
    private enum class Look { NIGHT, OLED, CLASSIC }

    private var mode = Mode.DOCK
    private var menuFrom = Mode.DOCK
    private var fi = 0              // focus in the dock: apps, then Settings, then More apps
    private var mi = 0              // focus in the More apps grid: an index into store.items
    private var dockReturn = 0      // where the dock focus goes back to after More apps
    private var scrollRow = 0
    private var menuPick = 0
    private var menuIndex = 0       // the app (index into store.items) the menu is about
    private var movingIndex = 0
    private var longHandled = false
    private var ci = 0              // focus in Keep watching
    private var homeRow = 0         // first visible row of the home grid (OLED and Classic)

    private val prefs = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)
    private var look = runCatching { Look.valueOf(prefs.getString("look", null) ?: "NIGHT") }.getOrDefault(Look.NIGHT)
    private var sceneBmp: Bitmap? = null
    private var prevBmp: Bitmap? = null      // the scene fading out
    private var fadeStart = 0L
    private var shownId = ""                 // the scene sceneBmp holds
    private var sceneIdx = 0
    private var sceneKey = ""
    private var glassBmp: Bitmap? = null
    private var glassKey = ""
    private var sceneBusy = false
    private val worker = Executors.newSingleThreadExecutor()

    private val weather = Weather(ctx) { sceneKey = ""; invalidate() }
    private val settings by lazy { SettingsScreen(ctx, prefs, store, weather, act) { onSettingsChanged() } }
    private val icons = Icons(ctx) { invalidate() }
    private val watch = WatchNext(ctx) { ci = ci.coerceIn(0, maxOf(0, cards().size - 1)); if (cards().isEmpty() && mode == Mode.CARDS) mode = Mode.DOCK; invalidate() }

    /** Keep watching: the Watch Next list. With the "demoCards" pref set (screenshots) two made-up shows stand in. */
    private fun cards(): List<Card> =
        if (watch.cards.isEmpty() && prefs.getBoolean("demoCards", false)) DEMO else watch.cards
    private val main = Handler(Looper.getMainLooper())

    private val ground = Color.parseColor("#0D1E19")
    private val tileBg = Color.parseColor("#1A2B26")
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Fonts.regular(ctx) }
    private val clockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    init { store.dockSize = prefs.getInt("dockSize", 10); store.reload() }
    private val rect = RectF()
    private val clip = Path()

    /** The scenes the household has on, in couch-tv's order (Home settings, Look and scenes). */
    private fun scenes(): List<SceneRenderer.Spec> {
        val on = prefs.getString("scenes", null)?.split(',')
        return SceneRenderer.ALL.filter { on == null || it.id in on }.ifEmpty { listOf(SceneRenderer.NIGHT) }
    }
    private fun scene(): SceneRenderer.Spec = scenes().let { it[sceneIdx % it.size] }

    /** Every few seconds the next scene: it is drawn off the main thread, then fades in over 1.6 s. */
    private val rotate = object : Runnable {
        override fun run() {
            if (look != Look.OLED && scenes().size > 1 && mode != Mode.SETTINGS) { sceneIdx = (sceneIdx + 1) % scenes().size; invalidate() }
            main.postDelayed(this, prefs.getInt("sceneSeconds", 20) * 1000L)
        }
    }

    private val minuteTick = object : Runnable {
        override fun run() {
            invalidate()
            val now = System.currentTimeMillis()
            main.postDelayed(this, 60_000L - now % 60_000L + 50L)   // once a minute, on the minute
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); requestFocus(); minuteTick.run(); main.postDelayed(rotate, prefs.getInt("sceneSeconds", 20) * 1000L) }
    override fun onDetachedFromWindow() { main.removeCallbacks(minuteTick); main.removeCallbacks(rotate); super.onDetachedFromWindow() }

    // ------------------------------------------------------------------ what the Activity calls

    /** Home pressed while the home is already showing: back to the first tile. */
    fun goHome() { mode = Mode.DOCK; fi = 0; invalidate() }

    /** The app list changed (a package came or went, or the home came back to the front). */
    fun refresh() {
        store.reload()
        watch.reload()
        fi = fi.coerceIn(0, store.line + 1)
        mi = mi.coerceIn(0, maxOf(0, store.items.size - 1))
        if (mode == Mode.MOVING && movingIndex >= store.line) mode = Mode.DOCK
        invalidate()
    }

    /** The TV is short of memory: drop the pictures, they are drawn again when needed. */
    fun trim() { icons.clear(); watch.clear() }

    /** Back: one step out of whatever is open. False when the home is already at its top level. */
    fun back(): Boolean = when (mode) {
        Mode.MOVING -> { mode = Mode.DOCK; fi = movingIndex; invalidate(); true }
        Mode.MENU -> { mode = menuFrom; invalidate(); true }
        Mode.MORE -> { mode = Mode.DOCK; fi = dockReturn.coerceIn(0, store.line + 1); invalidate(); true }
        Mode.CARDS -> { mode = Mode.DOCK; invalidate(); true }
        Mode.SETTINGS -> { if (!settings.back()) { mode = Mode.DOCK; fi = store.line }; invalidate(); true }
        Mode.DOCK -> false
    }

    // ------------------------------------------------------------------ keys

    override fun onKeyDown(code: Int, e: KeyEvent): Boolean {
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                dir(code); return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (e.repeatCount == 0) { longHandled = false; e.startTracking() }
                return true
            }
            KeyEvent.KEYCODE_MENU -> { if (e.repeatCount == 0) cycleLook(); return true }   // temporary: becomes the look quick button
        }
        return super.onKeyDown(code, e)
    }

    override fun onKeyLongPress(code: Int, e: KeyEvent): Boolean {
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            longHandled = true
            longPress()
            return true
        }
        return super.onKeyLongPress(code, e)
    }

    override fun onKeyUp(code: Int, e: KeyEvent): Boolean {
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (!longHandled) press()
            return true
        }
        return super.onKeyUp(code, e)
    }

    /** A setting changed: the look, the clock or the weather may have, so draw everything again. */
    private fun onSettingsChanged() {
        look = runCatching { Look.valueOf(prefs.getString("look", null) ?: "NIGHT") }.getOrDefault(Look.NIGHT)
        sceneKey = ""
        fi = fi.coerceIn(0, store.line + 1)
        invalidate()
    }

    private fun tick(sound: Int) { playSoundEffect(sound) }

    private fun cycleLook() {
        look = Look.values()[(look.ordinal + 1) % Look.values().size]
        prefs.edit().putString("look", look.name).apply()
        sceneBmp = null; sceneKey = ""
        fi = fi.coerceIn(0, store.line + 1)
        invalidate()
    }

    private val gridCols = 5

    private fun dir(code: Int) {
        if (mode == Mode.SETTINGS) { settings.key(code); return }
        val dockCount = store.line + 2
        when (mode) {
            Mode.SETTINGS -> {}
            Mode.DOCK -> if (look == Look.NIGHT) when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (fi > 0) { fi--; tick(SoundEffectConstants.NAVIGATION_LEFT) }
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (fi < dockCount - 1) { fi++; tick(SoundEffectConstants.NAVIGATION_RIGHT) }
                KeyEvent.KEYCODE_DPAD_UP -> if (cards().isNotEmpty()) { mode = Mode.CARDS; ci = ci.coerceIn(0, cards().size - 1) }
                KeyEvent.KEYCODE_DPAD_DOWN -> openMore(if (fi < store.line) fi else 0)
            } else {
                val before = fi
                when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (fi > 0) fi--
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (fi < dockCount - 1) fi++
                    KeyEvent.KEYCODE_DPAD_UP -> if (fi >= gridCols) fi -= gridCols
                    KeyEvent.KEYCODE_DPAD_DOWN -> if (fi + gridCols < dockCount) fi += gridCols else if (fi / gridCols == (dockCount - 1) / gridCols) openMore(if (fi < store.line) fi else 0) else fi = dockCount - 1
                }
                if (fi != before) tick(SoundEffectConstants.NAVIGATION_DOWN)
            }
            Mode.CARDS -> {
                val n = cards().size
                val before = ci
                when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (ci > 0) ci--
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (ci < n - 1) ci++
                    KeyEvent.KEYCODE_DPAD_DOWN -> mode = Mode.DOCK
                }
                if (ci != before) tick(SoundEffectConstants.NAVIGATION_RIGHT)
            }
            Mode.MORE -> {
                val before = mi
                when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (mi > 0) mi--
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (mi < store.items.size - 1) mi++
                    KeyEvent.KEYCODE_DPAD_UP -> if (mi >= COLS) mi -= COLS else { back(); return }
                    KeyEvent.KEYCODE_DPAD_DOWN -> if (mi + COLS < store.items.size) mi += COLS else mi = store.items.size - 1
                }
                if (mi != before) tick(SoundEffectConstants.NAVIGATION_DOWN)
            }
            Mode.MENU -> when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> if (menuPick > 0) menuPick--
                KeyEvent.KEYCODE_DPAD_DOWN -> if (menuPick < MENU_OPTIONS - 1) menuPick++
            }
            Mode.MOVING -> when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (movingIndex > 0) { store.moveInDock(movingIndex, movingIndex - 1); movingIndex-- }
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (movingIndex < store.line - 1) { store.moveInDock(movingIndex, movingIndex + 1); movingIndex++ }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    store.sendToMore(movingIndex)   // behind More apps
                    mode = Mode.DOCK
                    fi = minOf(movingIndex, store.line + 1)
                }
            }
        }
        invalidate()
    }

    private fun openMore(at: Int) {
        dockReturn = fi
        mi = at.coerceIn(0, maxOf(0, store.items.size - 1))
        scrollRow = 0
        mode = Mode.MORE
    }

    private fun press() {
        when (mode) {
            Mode.DOCK -> when {
                fi < store.line -> launch(store.items[fi])
                fi == store.line -> { settings.open(); mode = Mode.SETTINGS; invalidate() }
                else -> { openMore(0); invalidate() }
            }
            Mode.SETTINGS -> settings.key(KeyEvent.KEYCODE_DPAD_CENTER)
            Mode.CARDS -> cards().getOrNull(ci)?.let { act.openCard(it.pkg, it.intentUri) }
            Mode.MORE -> store.items.getOrNull(mi)?.let { launch(it) }
            Mode.MENU -> chooseMenu()
            Mode.MOVING -> { mode = Mode.DOCK; fi = movingIndex; invalidate() }
        }
    }

    private fun longPress() {
        when (mode) {
            Mode.DOCK -> if (fi < store.line) openMenu(fi)
            Mode.MORE -> {
                if (mi >= store.line) {
                    val pkg = store.items[mi].pkg
                    store.bringToDock(mi)   // behind More apps: to the end of the dock
                    mi = store.items.indexOfFirst { it.pkg == pkg }.coerceAtLeast(0)
                } else openMenu(mi)
            }
            else -> {}
        }
        invalidate()
    }

    private fun launch(a: AppItem) { store.opened(a.pkg); act.launch(a.pkg); invalidate() }

    private fun openMenu(index: Int) {
        menuFrom = mode
        menuIndex = index
        menuPick = 0
        mode = Mode.MENU
    }

    private fun chooseMenu() {
        val a = store.items.getOrNull(menuIndex) ?: run { mode = menuFrom; return }
        when (menuPick) {
            0 -> { mode = Mode.MOVING; movingIndex = menuIndex }   // menuIndex is always in the dock here
            1 -> { mode = menuFrom; act.uninstall(a.pkg) }
            2 -> { mode = menuFrom; act.appInfo(a.pkg) }
        }
        invalidate()
    }

    // ------------------------------------------------------------------ drawing

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { sceneKey = ""; super.onSizeChanged(w, h, ow, oh) }

    private fun clockText(cal: Calendar): String {
        val h24 = when (prefs.getInt("clock", 0)) { 1 -> false; 2 -> true; else -> DateFormat.is24HourFormat(context) }
        return DateFormat.format(if (h24) "HH:mm" else "h:mm", cal).toString()
    }
    private fun dateText(cal: Calendar): String = DateFormat.format("EEEE d MMMM", cal).toString()

    /** Keeps the scene bitmap current: once a minute for Night (the clock is in it), once for Classic. Off the main thread. */
    private fun ensureScene() {
        if (look == Look.OLED || width == 0 || sceneBusy) return
        val cal = Calendar.getInstance(Locale.getDefault())
        weather.refresh()
        val tx = clockText(cal); val dt = dateText(cal); val wx = weather.text()
        val dim = SceneRenderer.isDim(cal.get(Calendar.HOUR_OF_DAY))
        val sp = scene()
        val key = if (look == Look.NIGHT) "N|${sp.id}|$tx|$dt|$wx|$dim|${width}x$height" else "C|${sp.id}|$dim|${width}x$height"
        if (key == sceneKey) return
        sceneBusy = true
        val w = width; val h = height; val lk = look
        val needGlass = lk == Look.NIGHT && glassKey != "${sp.id}|$dim|${w}x$h"
        worker.execute {
            val bmp = runCatching {
                if (lk == Look.NIGHT) SceneRenderer.render(context, sp, tx, dt, wx, w, h, dim)
                else SceneRenderer.photoOnly(context, sp, w, h, if (dim) 0.4f else 0.55f)
            }.getOrNull()
            val glass = if (needGlass) runCatching { SceneRenderer.glassBase(context, sp, w, h, dim) }.getOrNull() else null
            main.post {
                sceneBusy = false
                if (glass != null) { val og = glassBmp; glassBmp = glass; glassKey = "${sp.id}|$dim|${w}x$h"; og?.recycle() }
                if (bmp != null && lk == look) {
                    val old = sceneBmp
                    if (old != null && shownId != sp.id) {   // a different scene: fade from the old one
                        prevBmp?.recycle(); prevBmp = old; fadeStart = android.os.SystemClock.uptimeMillis()
                    } else old?.recycle()
                    sceneBmp = bmp; shownId = sp.id; sceneKey = key; invalidate()
                } else bmp?.recycle()
            }
        }
    }

    override fun onDraw(c: Canvas) {
        val u = height / 1080f
        if (mode == Mode.SETTINGS) { settings.draw(c, width, height); return }
        if (mode == Mode.MORE || (mode == Mode.MENU && menuFrom == Mode.MORE) || look == Look.OLED) {
            c.drawColor(if (look == Look.OLED && mode != Mode.MORE) Color.BLACK else ground)
        } else {
            ensureScene()
            val b = sceneBmp
            val pv = prevBmp
            if (pv != null && b != null) {
                val f = ((android.os.SystemClock.uptimeMillis() - fadeStart) / 1600f).coerceIn(0f, 1f)
                c.drawBitmap(pv, 0f, 0f, null)
                paint.alpha = (f * 255).toInt(); c.drawBitmap(b, 0f, 0f, paint); paint.alpha = 255
                if (f >= 1f) { pv.recycle(); prevBmp = null } else postInvalidateOnAnimation()
            } else if (b != null) c.drawBitmap(b, 0f, 0f, null) else c.drawColor(ground)
            if (look == Look.NIGHT) {   // the scrim couch-tv puts under the dock: clear at 60% of the height, half black at the bottom
                paint.style = Paint.Style.FILL
                paint.shader = LinearGradient(0f, height * 0.6f, 0f, height.toFloat(), 0x00000000, 0x80000000.toInt(), Shader.TileMode.CLAMP)
                c.drawRect(0f, height * 0.6f, width.toFloat(), height.toFloat(), paint)
                paint.shader = null
            }
        }
        drawClock(c, u)
        when (mode) {
            Mode.MORE -> drawMore(c, u)
            Mode.MENU -> { if (menuFrom == Mode.MORE) drawMore(c, u) else { drawCards(c, u); drawDock(c, u) }; drawMenu(c, u) }
            else -> { drawCards(c, u); drawDock(c, u) }
        }
    }

    /** OLED and Classic: a small clock and the date, top left. Night's big clock is part of the scene bitmap. */
    private fun drawClock(c: Canvas, u: Float) {
        if (mode == Mode.MORE || look == Look.NIGHT || (mode == Mode.MENU && menuFrom == Mode.MORE)) return
        val cal = Calendar.getInstance()
        clockPaint.typeface = SceneRenderer.font(context)
        clockPaint.textAlign = Paint.Align.LEFT
        clockPaint.textSize = 150 * u
        c.drawText(clockText(cal), 100 * u, 230 * u, clockPaint)
        text.textAlign = Paint.Align.LEFT
        text.textSize = 32 * u
        c.drawText(dateText(cal), 104 * u, 285 * u, text)
    }

    private fun drawDock(c: Canvas, u: Float) {
        if (look != Look.NIGHT) { drawHomeGrid(c, u); return }
        // couch-tv's Night dock: a glass pill, 7 rem icons 1.625 rem apart, 2.875 rem off the bottom
        val n = store.line + 2
        val fit = minOf(1f, (width - 80 * u) / (n * 138 * u + 30 * u))
        val tw = 112 * u * fit; val gap = 26 * u * fit
        val padX = 28 * u * fit; val padY = 22 * u * fit
        val pw = n * tw + (n - 1) * gap + 2 * padX
        val ph = tw + 2 * padY
        val pl = (width - pw) / 2f
        val pt = height - 46 * u - ph
        glass(c, pl, pt, pl + pw, pt + ph, 46 * u)
        val focus = if (mode == Mode.MOVING) movingIndex else if (mode == Mode.CARDS) -1 else fi
        for (pass in 0..1) for (i in 0 until n) {
            if ((i == focus) != (pass == 1)) continue
            nightIcon(c, u, pl + padX + i * (tw + gap), pt + padY, tw, i, i == focus, mode == Mode.MOVING)
        }
    }

    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Night's Keep watching: a heading and glass cards, 520 x 141, 22 apart, from 80 px in. Three fit; the row slides to keep focus in view. */
    private fun drawCards(c: Canvas, u: Float) {
        if (look != Look.NIGHT) return
        val list = cards()
        if (list.isEmpty()) return
        text.typeface = Fonts.semibold(context); text.textAlign = Paint.Align.LEFT
        text.textSize = 25 * u; text.color = Color.argb(199, 255, 255, 255)
        c.drawText("Keep watching", 80 * u, 640 * u + 27 * u, text)
        text.color = Color.WHITE
        val cw = 520 * u; val gap = 22 * u; val top = 692 * u; val ch = 141 * u
        val first = if (mode == Mode.CARDS) maxOf(0, ci - 2) else 0
        for (pass in 0..1) for (i in first until list.size) {
            val focused = mode == Mode.CARDS && i == ci
            if (focused != (pass == 1)) continue
            val x = 80 * u + (i - first) * (cw + gap)
            if (x > width) continue
            drawCard(c, u, list[i], x, top, cw, ch, focused)
        }
    }

    private fun drawCard(c: Canvas, u: Float, card: Card, x: Float, y: Float, w: Float, h: Float, focused: Boolean) {
        c.save()
        if (focused) { c.translate(x + w / 2, y + h / 2); c.scale(1.05f, 1.05f); c.translate(-(x + w / 2), -(y + h / 2)) }
        val r = 30 * u
        if (focused) {
            paint.style = Paint.Style.FILL; paint.color = Color.argb(90, 0, 0, 0)
            rect.set(x, y + 12 * u, x + w, y + h + 12 * u)
            c.drawRoundRect(rect, r, r, paint)
            paint.color = Color.WHITE
            rect.set(x - 3 * u, y - 3 * u, x + w + 3 * u, y + h + 3 * u)
            c.drawRoundRect(rect, r + 3 * u, r + 3 * u, paint)
        }
        glass(c, x, y, x + w, y + h, r)
        // the picture: 168 x 95, 16 px in, with 16 px corners
        val tx = x + 16 * u; val ty = y + (h - 95 * u) / 2
        rect.set(tx, ty, tx + 168 * u, ty + 95 * u)
        paint.style = Paint.Style.FILL; paint.color = Color.argb(20, 255, 255, 255)
        c.drawRoundRect(rect, 16 * u, 16 * u, paint)
        watch.picture(card, (168 * u).toInt(), (95 * u).toInt())?.let { b ->
            clip.reset(); clip.addRoundRect(rect, 16 * u, 16 * u, Path.Direction.CW)
            c.save(); c.clipPath(clip); paint.alpha = 255; c.drawBitmap(b, null, rect, paint); c.restore()
        }
        // the words
        val sx = tx + 168 * u + 20 * u
        val sw = x + w - 16 * u - sx
        val top = y + (h - 109 * u) / 2
        text.textAlign = Paint.Align.LEFT
        text.typeface = Fonts.medium(context); text.textSize = 19 * u; text.letterSpacing = 0.06f; text.color = Color.argb(148, 255, 255, 255)
        val nap = TextUtils.ellipsize(card.app.uppercase(), text, sw, TextUtils.TruncateAt.END)
        c.drawText(nap, 0, nap.length, sx, top + 18 * u, text)
        text.letterSpacing = 0f
        text.typeface = Fonts.semibold(context); text.textSize = 29 * u; text.color = Color.rgb(245, 245, 247)
        val title = TextUtils.ellipsize(card.title, text, sw, TextUtils.TruncateAt.END)
        c.drawText(title, 0, title.length, sx, top + 24 * u + 28 * u, text)
        if (card.episode.isNotEmpty()) {
            text.typeface = Fonts.regular(context); text.textSize = 21 * u; text.color = Color.argb(199, 255, 255, 255)
            c.drawText(card.episode, sx, top + 60 * u + 22 * u, text)
        }
        text.color = Color.WHITE
        if (card.progress > 0f) {
            val by = top + 104 * u
            paint.color = Color.argb(64, 255, 255, 255)
            rect.set(sx, by, sx + sw, by + 5 * u); c.drawRoundRect(rect, 3 * u, 3 * u, paint)
            paint.color = Color.WHITE
            rect.set(sx, by, sx + sw * card.progress, by + 5 * u); c.drawRoundRect(rect, 3 * u, 3 * u, paint)
        }
        c.restore()
    }

    /** couch-tv's glass: the blurred photo behind it, a plum tint, a pink hairline and a lighter line along the top edge. */
    private fun glass(c: Canvas, l: Float, t: Float, r: Float, b: Float, radius: Float) {
        rect.set(l, t, r, b)
        clip.reset(); clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        c.save(); c.clipPath(clip)
        glassBmp?.let { c.drawBitmap(it, 0f, 0f, null) }
        glassPaint.style = Paint.Style.FILL; glassPaint.color = Color.argb(82, 30, 20, 28)
        c.drawRect(rect, glassPaint)
        c.restore()
        val px = height / 1080f
        glassPaint.style = Paint.Style.STROKE; glassPaint.strokeWidth = px
        glassPaint.color = Color.argb(41, 255, 210, 220)
        rect.inset(px / 2, px / 2)
        c.drawRoundRect(rect, radius, radius, glassPaint)
        glassPaint.color = Color.argb(36, 255, 220, 230)
        c.save(); c.clipRect(l, t, r, t + radius)
        rect.set(l + px / 2, t + px * 1.5f, r - px / 2, b)
        c.drawRoundRect(rect, radius, radius, glassPaint)
        c.restore()
    }

    private fun nightIcon(c: Canvas, u: Float, x: Float, y: Float, size: Float, index: Int, focused: Boolean, moving: Boolean) {
        val app = if (index < store.line) store.items[index] else null
        val radius = size * 0.265f
        c.save()
        val cx = x + size / 2; val cy = y + size / 2
        if (focused) { c.translate(cx, cy - 10 * u * (size / (112 * u))); c.scale(1.16f, 1.16f); c.translate(-cx, -cy) }
        rect.set(x, y, x + size, y + size)
        if (focused) {   // the white ring and its shadow
            paint.style = Paint.Style.FILL; paint.color = Color.argb(70, 0, 0, 0)
            rect.offset(0f, 14 * u); rect.inset(-4 * u, -4 * u)
            c.drawRoundRect(rect, radius + 4 * u, radius + 4 * u, paint)
            rect.set(x - 4 * u, y - 4 * u, x + size + 4 * u, y + size + 4 * u)
            paint.color = Color.WHITE
            c.drawRoundRect(rect, radius + 4 * u, radius + 4 * u, paint)
            rect.set(x, y, x + size, y + size)
        }
        paint.style = Paint.Style.FILL
        paint.color = if (app != null) Color.rgb(42, 42, 48) else Color.argb(36, 255, 255, 255)
        paint.alpha = if (moving && !focused) 178 else if (app != null) 255 else 36
        c.drawRoundRect(rect, radius, radius, paint)
        if (app != null) {
            val bmp = icons.square(app, size.toInt().coerceAtLeast(1))
            if (bmp != null) {
                // the app's icon sits inside the tile with room around it, like the logos in couch-tv's dock
                val inset = size * 0.15f
                rect.set(x + inset, y + inset, x + size - inset, y + size - inset)
                val ir = rect.width() * 0.265f
                clip.reset(); clip.addRoundRect(rect, ir, ir, Path.Direction.CW)
                c.save(); c.clipPath(clip)
                paint.colorFilter = if (focused) null else dimFilter
                paint.alpha = if (moving && !focused) 178 else 255
                c.drawBitmap(bmp, null, rect, paint)
                paint.colorFilter = null
                c.restore()
            } else {
                text.textSize = size * 0.16f; text.textAlign = Paint.Align.CENTER; text.typeface = Fonts.semibold(context)
                val l = TextUtils.ellipsize(app.label, text, size - 16 * u, TextUtils.TruncateAt.END)
                c.drawText(l, 0, l.length, cx, cy + size * 0.06f, text)
            }
            if (store.isNew(app.pkg)) {
                paint.color = Color.parseColor("#3D8BFF"); paint.alpha = 255
                c.drawCircle(x + size - 10 * u, y + 10 * u, 6 * u, paint)
            }
        } else {
            glyph(c, u, cx, cy, size, index == store.line)
        }
        if (focused) {
            text.typeface = Fonts.semibold(context); text.textSize = 20 * u * (size / (112 * u)); text.textAlign = Paint.Align.CENTER
            c.drawText(dockTitle(index), cx, y + size + 12 * u + text.textSize * 0.85f, text)
        }
        c.restore()
    }

    private val dimFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
        .82f, 0f, 0f, 0f, 0f, 0f, .82f, 0f, 0f, 0f, 0f, 0f, .82f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))

    /** The gear and the three dots, drawn with strokes the way couch-tv's icons are (24 unit grid, 36 px, stroke 1.7). */
    private fun glyph(c: Canvas, u: Float, cx: Float, cy: Float, size: Float, gear: Boolean) {
        val k = 58f * u / 24f * (size / (112 * u))
        paint.style = Paint.Style.STROKE; paint.color = Color.WHITE; paint.alpha = 255
        paint.strokeWidth = 1.7f * k; paint.strokeCap = Paint.Cap.ROUND
        if (gear) {
            c.drawCircle(cx, cy, 3.1f * k, paint)
            for (a in 0 until 8) {
                val r = Math.toRadians(a * 45.0)
                c.drawLine(cx + (7.4f * k * Math.cos(r)).toFloat(), cy + (7.4f * k * Math.sin(r)).toFloat(),
                    cx + (9.2f * k * Math.cos(r)).toFloat(), cy + (9.2f * k * Math.sin(r)).toFloat(), paint)
            }
            c.drawCircle(cx, cy, 6.2f * k, paint)
        } else {
            paint.style = Paint.Style.FILL
            for (d in -1..1) c.drawCircle(cx + d * 7.6f * k, cy, 2.1f * k, paint)
        }
        paint.style = Paint.Style.FILL; paint.strokeCap = Paint.Cap.BUTT
    }

    /** OLED and Classic: the dock's apps, Settings and More apps as a grid, five across. */
    private fun drawHomeGrid(c: Canvas, u: Float) {
        val n = store.line + 2
        val gap = 26 * u
        val tw = 270 * u
        val th = tw * 9f / 16f
        val pitch = th + 62 * u
        val top = 340 * u
        val rows = ((height - top - 30 * u) / pitch).toInt().coerceAtLeast(1)
        val focus = if (mode == Mode.MOVING) movingIndex else fi
        val row = focus / gridCols
        if (row < homeRow) homeRow = row
        if (row >= homeRow + rows) homeRow = row - rows + 1
        val x0 = (width - (gridCols * tw + (gridCols - 1) * gap)) / 2f
        val dim = mode == Mode.MOVING
        for (pass in 0..1) for (i in 0 until n) {
            if ((i == focus) != (pass == 1)) continue
            val r = i / gridCols - homeRow
            if (r < 0 || r >= rows) continue
            val x = x0 + (i % gridCols) * (tw + gap)
            val y = top + r * pitch
            tile(c, u, x, y, tw, th, i, i == focus && mode != Mode.MENU || i == focus, dim && i != focus, if (dim && i == focus) -14 * u else 0f)
            text.textSize = 24 * u
            text.textAlign = Paint.Align.CENTER
            val label = TextUtils.ellipsize(dockTitle(i), text, tw, TextUtils.TruncateAt.END)
            c.drawText(label, 0, label.length, x + tw / 2, y + th + 38 * u, text)
        }
    }

    private fun dockTitle(i: Int): String = when {
        i < store.line -> store.items[i].label
        i == store.line -> "Settings"
        else -> "More apps"
    }

    private fun drawMore(c: Canvas, u: Float) {
        text.textAlign = Paint.Align.LEFT
        text.textSize = 44 * u
        c.drawText("More apps", 100 * u, 120 * u, text)
        val gap = 28 * u
        val tw = 250 * u
        val th = tw * 9f / 16f
        val pitch = th + 52 * u
        val top = 190 * u
        val rows = ((height - top - 40 * u) / pitch).toInt().coerceAtLeast(1)
        val row = mi / COLS
        if (row < scrollRow) scrollRow = row
        if (row >= scrollRow + rows) scrollRow = row - rows + 1
        val x0 = (width - (COLS * tw + (COLS - 1) * gap)) / 2f
        for (i in store.items.indices) {
            val r = i / COLS - scrollRow
            if (r < 0 || r >= rows) continue
            val focused = i == mi && mode == Mode.MORE
            val x = x0 + (i % COLS) * (tw + gap)
            val y = top + r * pitch
            tile(c, u, x, y, tw, th, i, focused, false, 0f, grid = true)
            text.textSize = 24 * u
            text.textAlign = Paint.Align.CENTER
            val label = TextUtils.ellipsize(store.items[i].label, text, tw, TextUtils.TruncateAt.END)
            c.drawText(label, 0, label.length, x + tw / 2, y + th + 34 * u, text)
        }
    }

    /** One tile: an app, the Settings tile or the More apps tile. [index] is a dock position, or in a grid an items index. */
    private fun tile(c: Canvas, u: Float, x: Float, y: Float, w: Float, h: Float, index: Int,
                     focused: Boolean, dim: Boolean, lift: Float, grid: Boolean = false) {
        val app = if (grid) store.items.getOrNull(index) else if (index < store.line) store.items[index] else null
        val scale = if (focused) 1.12f else 1f
        val cx = x + w / 2
        val cy = y + h / 2 + lift
        c.save()
        c.translate(cx, cy)
        c.scale(scale, scale)
        rect.set(-w / 2, -h / 2, w / 2, h / 2)
        val radius = 10 * u
        paint.style = Paint.Style.FILL
        paint.color = tileBg
        paint.alpha = if (dim) 120 else 255
        c.drawRoundRect(rect, radius, radius, paint)
        if (app != null) {
            val bmp = icons.get(app, w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1))
            if (bmp != null) {
                clip.reset(); clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
                c.save(); c.clipPath(clip)
                paint.alpha = if (dim) 120 else 255
                c.drawBitmap(bmp, -w / 2, -h / 2, paint)
                c.restore()
            } else {
                text.textSize = h * 0.4f; text.textAlign = Paint.Align.CENTER
                c.drawText(app.label.take(1).uppercase(), 0f, h * 0.14f, text)
            }
            if (store.isNew(app.pkg)) {
                paint.color = Color.parseColor("#3D8BFF"); paint.alpha = 255
                c.drawCircle(w / 2 - 12 * u, -h / 2 + 12 * u, 6 * u, paint)
            }
        } else {
            text.textSize = h * 0.2f; text.textAlign = Paint.Align.CENTER
            c.drawText(if (index == store.line) "Settings" else "More apps", 0f, h * 0.07f, text)
        }
        if (focused) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3 * u
            paint.color = Color.WHITE; paint.alpha = 255
            c.drawRoundRect(rect, radius, radius, paint)
        }
        c.restore()
    }

    private fun drawMenu(c: Canvas, u: Float) {
        paint.style = Paint.Style.FILL
        paint.color = Color.BLACK; paint.alpha = 150
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        val w = 560 * u
        val rowH = 84 * u
        val h = 110 * u + MENU_OPTIONS * rowH
        val l = (width - w) / 2f
        val t = (height - h) / 2f
        rect.set(l, t, l + w, t + h)
        paint.color = Color.parseColor("#17241F"); paint.alpha = 255
        c.drawRoundRect(rect, 18 * u, 18 * u, paint)
        text.textAlign = Paint.Align.LEFT
        text.textSize = 32 * u
        val title = TextUtils.ellipsize(store.items.getOrNull(menuIndex)?.label.orEmpty(), text, w - 80 * u, TextUtils.TruncateAt.END)
        c.drawText(title, 0, title.length, l + 40 * u, t + 70 * u, text)
        val names = arrayOf("Move", "Uninstall", "App info")
        for (i in 0 until MENU_OPTIONS) {
            val ry = t + 100 * u + i * rowH
            if (i == menuPick) {
                rect.set(l + 20 * u, ry, l + w - 20 * u, ry + rowH - 8 * u)
                paint.color = Color.parseColor("#2B4A40"); paint.alpha = 255
                c.drawRoundRect(rect, 12 * u, 12 * u, paint)
            }
            text.textSize = 30 * u
            c.drawText(names[i], l + 50 * u, ry + rowH * 0.6f, text)
        }
    }

    private companion object {
        val DEMO = listOf(
            Card(-1, "com.disney.disneyplus", "Disney+", "The Bear", "S3 E2", null, 0.7f, null),
            Card(-2, "au.net.abc.iview", "ABC iview", "Fisk", "S3 E1", null, 0.55f, null),
        )
        const val COLS = 6
        const val MENU_OPTIONS = 3
    }
}
