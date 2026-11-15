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
    fun setSleepTimer(minutes: Int)
    fun openCard(pkg: String, intentUri: String?)
    fun uninstall(pkg: String)
    fun appInfo(pkg: String)
    /** Settings, Music visualiser: a small live render of the preset at [path] (under filesDir/presets) in this screen rectangle, or null to remove it. */
    fun vizPreview(path: String?, x: Int, y: Int, w: Int, h: Int)
}

/**
 * The whole home, drawn on one Canvas: the clock, the dock (apps, then Settings and More apps), the
 * More apps grid, the hold-OK menu and the move mode. No child views, no adapters, no libraries.
 * The remote's D-pad drives everything through the key callbacks below.
 */
class HomeView(ctx: Context, private val store: AppStore, private val act: HomeActions) : View(ctx) {
    private enum class Mode { DOCK, MORE, MENU, MOVING, CARDS, SETTINGS, TOP, POP, KIDS, SMART, NP }
    /** Night: the scene with the big glass clock and the dock. OLED: a grid on pure black. Classic: a grid over the photo. */
    private enum class Look { NIGHT, OLED, CLASSIC, DARK }

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
    private var ti = 0              // focus in the top row: people, then the quick buttons
    private var ci = 0              // focus in Keep watching
    private var homeRow = 0         // first visible row of the home grid (OLED and Classic)

    private val prefs = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)
    private var look = runCatching { Look.valueOf(prefs.getString("look", null) ?: "NIGHT") }.getOrDefault(Look.NIGHT)
    private var sceneBmp: Bitmap? = null
    private var prevBmp: Bitmap? = null      // the scene fading out
    private var fadeStart = 0L
    private var shownId = ""                 // the scene sceneBmp holds
    private var sceneIdx = 0
    private var photoIdx = 0
    private var sceneKey = ""
    private var glassBmp: Bitmap? = null
    private var glassKey = ""
    private var sceneBusy = false
    private val worker = Executors.newSingleThreadExecutor()

    private val weather = Weather(ctx) { sceneKey = ""; invalidate() }
    private val settings by lazy { SettingsScreen(ctx, prefs, store, weather, act, peopleSettings) { onSettingsChanged() } }
    private val smart by lazy { SmartHomeScreen(ctx) { invalidate() } }
    private val icons = Icons(ctx) { invalidate() }
    val looks = Looks.of(ctx, prefs)                       // the Look and scenes choices (Looks.kt); shared with the settings page
    private val gstyle = GlassStyle(looks)
    private val clear = ClearIcon(ctx) { invalidate() }
    /** Set by the Activity: starts, stops or re-dims the video background when the looks, the minute or the hour change. */
    var videoHook: (() -> Unit)? = null
    /** The MilkDrop visualiser behind the music view (MainActivity adds and removes the view). */
    var vizHook: ((Boolean) -> Unit)? = null
    var vizStep: ((Int) -> Unit)? = null
    private var vizOn = false
    fun vizLive(on: Boolean) { np.vizLive = on; invalidate() }
    fun vizName(n: String) = np.presetName(n)
    val audio get() = np.audio
    init { looks.onChanged { sceneKey = ""; invalidate(); videoHook?.invoke() } }
    private val watch = WatchNext(ctx) { ci = ci.coerceIn(0, maxOf(0, cards().size - 1)); if (cards().isEmpty() && mode == Mode.CARDS) mode = Mode.DOCK; invalidate() }

    // ---- people: each has their own apps; a kids profile gets the kids shelf (KidsMode) instead of this home
    private val ppl = People(prefs)
    private val peopleUi = PeopleUi(ctx, ppl) { c, l, t, r, b, rad -> glass(c, l, t, r, b, rad) }
    private var unlockedFor: String? = null   // a kids profile a grown up unlocked with no grown up profile to move to
    private val kids = KidsMode(ctx, ppl, icons, watch, act, { clockText(Calendar.getInstance()) }, { kid, _ -> kidUnlocked(kid) }, { invalidate() })
    private val peopleSettings = PeopleSettings(ctx, ppl, peopleUi, { cards() }, { store.items }, act) { syncPerson() }
    // idle, sound, ambient weather and Now playing (IdleLayer, AmbientFx, NowPlaying): see SmartIdle.kt and friends
    private val navSound = NavSound()
    private val idle = IdleLayer(ctx, prefs, act as? IdleHost, { postInvalidateOnAnimation() },
        { clockText(Calendar.getInstance()) }, { dateText(Calendar.getInstance()) }, { weather.text() },
        { mode == Mode.DOCK || mode == Mode.CARDS || mode == Mode.TOP || mode == Mode.NP })
    private val fx = AmbientFx(prefs, weather) { postInvalidateDelayed(40) }
    private val np = NowPlaying(ctx, prefs, { act.launch(it) }) { auto -> onNowPlaying(auto) }
    init { np.onPreset = { vizStep?.invoke(it) } }

    /** The music changed: a new song may open the full screen view, and a card that has gone gives the remote back. */
    private fun onNowPlaying(auto: Boolean) {
        val zone = mode == Mode.DOCK || mode == Mode.CARDS || mode == Mode.TOP
        if (auto && zone && !idle.overlayActive()) { mode = Mode.NP; np.focus() }
        else if (!np.visible && mode == Mode.NP) { np.blur(); mode = Mode.DOCK }
        else if (np.ambientOpen && mode != Mode.NP) np.abandonAmbient()
        invalidate()
    }

    /** Keep watching: the Watch Next list. With the "demoCards" pref set (screenshots) two made-up shows stand in. */
    private fun cards(): List<Card> =
        (if (watch.cards.isEmpty() && prefs.getBoolean("demoCards", false)) DEMO else watch.cards) + CustomCards.cards

    // The dock after the apps: the Smart home tile (only when Home Assistant is set up), Settings, More apps.
    private fun smartIdx() = if (Plat.smartAvailable()) store.line else -1
    private fun gearIdx() = store.line + (if (smartIdx() >= 0) 1 else 0)
    private fun dockSlots() = gearIdx() + 1   // the apps, Smart home, then Settings; More apps opens with Down, it has no tile
    private val main = Handler(Looper.getMainLooper())

    // quick buttons beyond Sleep and Look, and what Keep watching adds (QuickSystem.kt, HomeOverlays.kt)
    private val quickSys = QuickSystem(ctx, prefs)
    private val over = HomeOverlays(ctx)
    private var screenOff = false
    private var screenOffAt = 0L
    private var wokeBy = -1             // the key that woke the screen: its release is not a press
    private var cardMenu = false        // the hold-OK menu on a Keep watching card
    private var cardMenuPick = 0
    private var noteText = ""; private var noteUntil = 0L      // a failed quick button, under the buttons
    private var toastText = ""; private var toastUntil = 0L    // a short confirmation at the top

    init { Motion.init(ctx) }
    private val ground = Color.parseColor("#0D1E19")

    // ---- movement: focus eases in and out, screens fade (Motion.kt sets the speed; "No motion" jumps)
    private val focusAmt = HashMap<Int, Float>()
    private var animLast = 0L
    private var frameMs = 16L
    private var animBusy = false
    private var fadeKey = 0
    private var fadeAt = 0L

    /** How focused the thing with this [key] is, 0 to 1, moved a step towards [target] each frame and eased (slow at the ends). */
    private fun anim(key: Int, target: Boolean): Float {
        val goal = if (target) 1f else 0f
        val cur = focusAmt[key] ?: goal
        if (cur == goal) return eased(cur)
        val dur = Motion.ms(prefs, 160)
        val next = if (dur <= 0L) goal else if (goal > cur) minOf(goal, cur + frameMs.toFloat() / dur) else maxOf(goal, cur - frameMs.toFloat() / dur)
        focusAmt[key] = next
        if (next != goal) animBusy = true
        return eased(next)
    }
    private fun eased(t: Float): Float { val k = 1f - t; return 1f - k * k * k }
    /** OLED and Dark: a solid colour, no scene. */
    private val solid get() = look == Look.OLED || look == Look.DARK
    private val ink = Color.WHITE
    private fun inkA(a: Int) = Color.argb(a, 255, 255, 255)
    private val tileBg = Color.parseColor("#1A2B26")
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; typeface = Fonts.regular(ctx) }
    private val clockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink
        typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    init { store.dockSize = prefs.getInt("dockSize", 10); store.reload(); main.post { syncPerson() } }
    private val rect = RectF()
    private val clip = Path()

    // ---- the top row: the people switch (top left) and the quick buttons (top right)

    fun people(): List<String> = ppl.list().map { it.name }
    private fun quick(): List<String> = QuickButtons.enabled(prefs).filter { quickSys.available(it) && (it != "input" || store.inputsAvailable) }
    private val qAnchor = HashMap<String, FloatArray>()   // each quick button's right edge and bottom, where the Input menu hangs from
    /** Minutes left on the sleep timer, 0 when none is running (the Activity keeps the timer). */
    private fun sleepLeft(): Int {
        val ends = (act as? IdleHost)?.sleepEndsAt() ?: 0L
        return if (ends <= 0L) 0 else Math.ceil((ends - System.currentTimeMillis()) / 60000.0).toInt().coerceAtLeast(0)
    }
    private fun lookLabel() = when (look) { Look.NIGHT -> "OLED Cinema look"; Look.OLED -> "Classic look"; Look.CLASSIC -> "Dark look"; Look.DARK -> "Night look" }

    private class Top(val kind: String, val label: String = "")
    private fun topItems(): List<Top> {
        val p = ppl.list()
        val who = if (p.size >= 3) listOf(Top("head", ppl.current()?.name.orEmpty())) else if (p.size == 2) p.map { Top("person", it.name) } else emptyList()
        return who + quick().map { Top(it) }
    }

    /** The scenes the household has on, in couch-tv's order (Home settings, Look and scenes). */
    private fun scenes(): List<SceneRenderer.Spec> {
        val on = prefs.getString("scenes", null)?.split(',')
        return SceneRenderer.ALL.filter { on == null || it.id in on }.ifEmpty { listOf(SceneRenderer.NIGHT) } + looks.ownScenes()
    }
    private fun timeSync() = prefs.getBoolean("timeSync", true)

    /** The scene now: following the time of day (the sun where the weather place is set, else the hour), or the rotation's turn. */
    private fun scene(): SceneRenderer.Spec {
        val list = scenes()
        if (!timeSync() || list.size < 2) return list[sceneIdx % list.size]
        val want = sunScene(Calendar.getInstance())
        fun pick(vararg order: String) = order.firstNotNull { id -> list.firstOrNull { it.id == id } } ?: list[0]
        return when (want) {
            "dawn" -> pick("dawn", "golden", "water", "fog", "night")
            "golden" -> pick("golden", "dawn", "water", "fog", "night")
            "fog" -> pick("fog", "water", "golden", "dawn", "night")
            "water" -> pick("water", "fog", "golden", "dawn", "night")
            else -> pick("night", "fog", "water", "golden", "dawn")
        }
    }
    private inline fun <T> Array<out String>.firstNotNull(f: (String) -> T?): T? { for (x in this) f(x)?.let { return it }; return null }

    /** Which scene suits the sky: couch-tv's sun sync. Below -6 degrees night; low sun dawn (morning) or golden (evening); daytime water, or fog when it is grey or wet. */
    private fun sunScene(cal: Calendar): String {
        val lat = prefs.getFloat("lat", 0f).toDouble(); val lon = prefs.getFloat("lon", 0f).toDouble()
        if (!weather.hasPlace) {
            val h = cal.get(Calendar.HOUR_OF_DAY)
            return if (h in 5..7) "dawn" else if (h in 8..15) "water" else if (h in 16..18) "golden" else "night"
        }
        val doy = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = cal.timeInMillis }.get(Calendar.DAY_OF_YEAR)
        val b = Math.toRadians(360 / 365.25 * (doy - 81))
        val eot = 9.87 * Math.sin(2 * b) - 7.53 * Math.cos(b) - 1.5 * Math.sin(b)
        val decl = Math.toRadians(23.45 * Math.sin(b))
        val utc = (cal.timeInMillis % 86400000L) / 3600000.0
        val hourAngle = Math.toRadians(((utc + lon / 15 + eot / 60 + 24) % 24 - 12) * 15)
        val la = Math.toRadians(lat)
        val alt = Math.toDegrees(Math.asin((Math.sin(la) * Math.sin(decl) + Math.cos(la) * Math.cos(decl) * Math.cos(hourAngle)).coerceIn(-1.0, 1.0)))
        return when {
            alt < -6 -> "night"
            alt < 10 -> if (hourAngle < 0) "dawn" else "golden"
            weather.code in intArrayOf(3, 45, 48, 51, 53, 55, 61, 63, 65, 80, 81, 82) -> "fog"
            else -> "water"
        }
    }

    /** Classic's photo now: sunrise in the morning, green by day, sunsets in the evening (couch-tv's own hours), or the rotation's turn. */
    private fun photo(): SceneRenderer.Photo {
        val list = looks.photos(); val n = list.size
        val kept = if (looks.keepPhoto) list.firstOrNull { it.file == looks.keepPick } else null
        val p = kept ?: if (!timeSync() || looks.keepPhoto) list[photoIdx % n] else {
            val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            list[when { h in 5..8 -> 0; h in 9..12 -> 1; h in 13..16 -> 2; h in 17..18 -> 3; else -> 4 } % n]
        }
        looks.shownPhoto = p.file
        return p
    }

    /** Every few seconds the next scene: it is drawn off the main thread, then fades in over 1.6 s. */
    private val rotate = object : Runnable {
        override fun run() {
            if (look == Look.NIGHT && !timeSync() && scenes().size > 1 && mode != Mode.SETTINGS) { sceneIdx = (sceneIdx + 1) % scenes().size; invalidate() }
            if (look == Look.CLASSIC && !timeSync() && !looks.keepPhoto && mode != Mode.SETTINGS) { photoIdx = (photoIdx + 1) % looks.photos().size; invalidate() }
            main.postDelayed(this, if (look == Look.CLASSIC) 90_000L else prefs.getInt("sceneSeconds", 20) * 1000L)
        }
    }

    private val minuteTick = object : Runnable {
        override fun run() {
            looks.stepMinute(); videoHook?.invoke()   // the OLED shift moves a step, the video re-dims at 9 pm and 6 am
            invalidate()
            CustomCards.refresh(context); Updater.tick(context)   // each rate-limited (10 minutes, a week) and off until set up
            val now = System.currentTimeMillis()
            main.postDelayed(this, 60_000L - now % 60_000L + 50L)   // once a minute, on the minute
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        CustomCards.listener = { ci = ci.coerceIn(0, maxOf(0, cards().size - 1)); invalidate() }   // cleared in MainActivity.onDestroy
        Updater.listener = { invalidate() }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) { idle.wake(); np.start(); if (mode == Mode.NP) { np.blur(); np.abandonAmbient(); mode = Mode.DOCK } } else { idle.stopSound(); np.stop() }   // no soundscape or music polling behind an app
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); idle.start(); requestFocus(); minuteTick.run(); main.postDelayed(rotate, prefs.getInt("sceneSeconds", 20) * 1000L) }
    override fun onDetachedFromWindow() { idle.stop(); np.stop(); navSound.release(); main.removeCallbacks(minuteTick); main.removeCallbacks(rotate); super.onDetachedFromWindow() }

    // ------------------------------------------------------------------ what the Activity calls

    /** Home pressed while the home is already showing: back to the first tile. */
    /** Up from the top of the home (the now playing card or the quick buttons): the full screen music view, when something is playing. */
    private fun openMusic() { if (np.openAmbient()) { mode = Mode.NP; np.focus(); invalidate() } }

    fun goHome() { idle.wake(); np.blur(); np.abandonAmbient(); if (screenOff) wake(-1); cardMenu = false; if (mode == Mode.KIDS) { kids.home(); return }; if (mode == Mode.SMART) smart.close(); peopleUi.drop(); mode = Mode.DOCK; fi = 0; invalidate() }

    /** The app list changed (a package came or went, or the home came back to the front). */
    fun refresh() {
        store.reload()
        kids.refresh()
        watch.reload()
        fi = fi.coerceIn(0, dockSlots() - 1)
        mi = mi.coerceIn(0, maxOf(0, store.items.size - 1))
        if (mode == Mode.MOVING && movingIndex >= store.line) mode = Mode.DOCK
        invalidate()
    }

    /** The TV is short of memory: drop the pictures, they are drawn again when needed. */
    fun trim() { icons.clear(); watch.clear(); clear.clear() }

    /** Back: one step out of whatever is open. False when the home is already at its top level. */
    fun back(): Boolean = if (quickBack()) true else when (mode) {
        Mode.MOVING -> { mode = Mode.DOCK; fi = movingIndex; invalidate(); true }
        Mode.MENU -> { mode = menuFrom; invalidate(); true }
        Mode.MORE -> { mode = Mode.DOCK; fi = dockReturn.coerceIn(0, dockSlots() - 1); invalidate(); true }
        Mode.CARDS, Mode.TOP -> { mode = Mode.DOCK; invalidate(); true }
        Mode.NP -> { if (np.backPressed()) {} else if (np.ambientOpen) np.closeAmbient(true) else { np.blur(); mode = Mode.DOCK }; invalidate(); true }
        Mode.SETTINGS -> { if (!settings.back()) { mode = Mode.DOCK; fi = gearIdx(); syncPerson() }; invalidate(); true }
        Mode.POP -> { peopleUi.drop(); mode = Mode.TOP; invalidate(); true }
        Mode.KIDS -> { kids.back(); true }
        Mode.SMART -> { smart.close(); mode = Mode.DOCK; fi = smartIdx().coerceAtLeast(0); invalidate(); true }
        Mode.DOCK -> false
    }

    // ------------------------------------------------------------------ keys

    private var kidsOk = false   // OK went down in kids mode: its key up must not press whatever the home becomes next

    override fun onKeyDown(code: Int, e: KeyEvent): Boolean {
        if (screenOff) { if (e.repeatCount == 0) wake(code); return true }   // any button wakes the screen
        if (mode == Mode.KIDS) {
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) kidsOk = true
            return kids.key(code, e) || super.onKeyDown(code, e)
        }
        if (mode == Mode.SETTINGS && (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 || code in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 || code == KeyEvent.KEYCODE_DEL)) {
            if (settings.key(code)) return true
        }
        val volume = code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE
        if (!volume && idle.key()) return true   // it only woke the ambient clock or the hidden rows
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
        if (code == wokeBy) { wokeBy = -1; return true }
        if (kidsOk && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER)) { kidsOk = false; return true }
        if (idle.eatUp()) return true
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
        fi = fi.coerceIn(0, dockSlots() - 1)
        ti = ti.coerceIn(0, maxOf(0, topItems().size - 1))
        invalidate()
    }

    private fun tick(sound: Int) { navSound.tick(this, sound, prefs.getString("nav_sound", "system") ?: "system") }

    private fun cycleLook() {
        look = Look.values()[(look.ordinal + 1) % Look.values().size]
        prefs.edit().putString("look", look.name).apply()
        sceneBmp = null; sceneKey = ""
        fi = fi.coerceIn(0, dockSlots() - 1)
        invalidate()
    }

    private val gridCols get() = if (look == Look.NIGHT) 5 else 8

    private fun dir(code: Int) {
        if (cardMenu) { cardMenuKey(code); return }
        if (mode == Mode.SETTINGS) { settings.key(code); return }
        if (mode == Mode.SMART) { smart.key(code); return }
        val dockCount = dockSlots()
        when (mode) {
            Mode.SETTINGS, Mode.KIDS, Mode.SMART -> {}
            Mode.POP -> when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> peopleUi.up()
                KeyEvent.KEYCODE_DPAD_DOWN -> peopleUi.down()
            }
            Mode.TOP -> {
                val n = topItems().size
                when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (ti > 0) ti-- else if (np.reach()) { mode = Mode.NP; np.focus() }
                    KeyEvent.KEYCODE_DPAD_UP -> openMusic()   // left of the first quick button: the now playing card
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (ti < n - 1) ti++
                    KeyEvent.KEYCODE_DPAD_DOWN -> if (np.reach()) { mode = Mode.NP; np.focus() } else mode = if (cards().isNotEmpty()) Mode.CARDS else Mode.DOCK
                }
            }
            Mode.NP -> if (!np.key(code)) when (code) {
                KeyEvent.KEYCODE_DPAD_DOWN -> { np.blur(); mode = if (cards().isNotEmpty()) Mode.CARDS else Mode.DOCK }
                KeyEvent.KEYCODE_DPAD_UP -> openMusic()   // up from the top: the full screen music view
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (topItems().isNotEmpty()) { np.blur(); mode = Mode.TOP; ti = 0 }
            }
            Mode.DOCK -> if (look == Look.NIGHT) when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (fi > 0) { fi--; tick(SoundEffectConstants.NAVIGATION_LEFT) }
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (fi < dockCount - 1) { fi++; tick(SoundEffectConstants.NAVIGATION_RIGHT) }
                KeyEvent.KEYCODE_DPAD_UP -> if (cards().isNotEmpty()) { mode = Mode.CARDS; ci = ci.coerceIn(0, cards().size - 1) } else if (np.reach()) { mode = Mode.NP; np.focus() } else if (topItems().isNotEmpty()) { mode = Mode.TOP; ti = ti.coerceIn(0, topItems().size - 1) }
                KeyEvent.KEYCODE_DPAD_DOWN -> openMore(if (fi < store.line) fi else 0)
            } else {
                val before = fi
                when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (fi > 0) fi--
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (fi < dockCount - 1) fi++
                    KeyEvent.KEYCODE_DPAD_UP -> if (fi >= gridCols) fi -= gridCols else if (cards().isNotEmpty()) { mode = Mode.CARDS; ci = ci.coerceIn(0, cards().size - 1) } else if (np.reach()) { mode = Mode.NP; np.focus() } else if (topItems().isNotEmpty()) { mode = Mode.TOP; ti = ti.coerceIn(0, topItems().size - 1) }
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
                    KeyEvent.KEYCODE_DPAD_UP -> if (look == Look.NIGHT && np.reach()) { mode = Mode.NP; np.focus() } else if (topItems().isNotEmpty()) { mode = Mode.TOP; ti = ti.coerceIn(0, topItems().size - 1) }
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
                KeyEvent.KEYCODE_DPAD_DOWN -> if (menuPick < menuNames().size - 1) menuPick++
            }
            Mode.MOVING -> when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (movingIndex > 0) { store.moveInDock(movingIndex, movingIndex - 1); movingIndex-- }
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (movingIndex < store.line - 1) { store.moveInDock(movingIndex, movingIndex + 1); movingIndex++ }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    store.sendToMore(movingIndex)   // behind More apps
                    mode = Mode.DOCK
                    fi = minOf(movingIndex, dockSlots() - 1)
                }
            }
        }
        invalidate()
    }

    /** OK on Settings: a short list under the button, our own settings or Android's. */
    private fun settingsMenu() {
        val u = height / 1080f
        val right = width - 80 * u
        val top = if (look == Look.NIGHT) height - 46 * u - 88 * u else height - 220 * u
        ListMenu.show(context, right, top, listOf("App settings", "Android settings"), 0, true) { i ->
            if (i == 0) { settings.open(); mode = Mode.SETTINGS; invalidate() }
            else if (!quickSys.openTvSettings()) note("Could not open the TV's settings.")
        }
    }

    private fun openMore(at: Int) {
        dockReturn = fi
        mi = at.coerceIn(0, maxOf(0, store.items.size - 1))
        scrollRow = 0
        mode = Mode.MORE
    }

    private fun press() {
        if (cardMenu) { chooseCardMenu(); return }
        when (mode) {
            Mode.DOCK -> when {
                fi < store.line -> launch(store.items[fi])
                fi == smartIdx() -> { smart.open(); mode = Mode.SMART; invalidate() }
                fi == gearIdx() -> settingsMenu()
                else -> { openMore(0); invalidate() }
            }
            Mode.SETTINGS -> settings.key(KeyEvent.KEYCODE_DPAD_CENTER)
            Mode.SMART -> smart.key(KeyEvent.KEYCODE_DPAD_CENTER)
            Mode.TOP -> topItems().getOrNull(ti)?.let { t ->
                when (t.kind) {
                    "person" -> ppl.list().getOrNull(ti)?.let { switchTo(it.id) }
                    "head" -> { peopleUi.open(); mode = Mode.POP }
                    "sleep" -> qAnchor["sleep"].let { a -> SleepMenu.show(context, a?.get(0) ?: (width - 80 * height / 1080f), a?.get(1) ?: (148 * height / 1080f), sleepLeft() > 0) { m -> act.setSleepTimer(m); looks.sleepSet(m); invalidate() } }
                    "look" -> cycleLook()
                    "input" -> qAnchor["input"].let { a -> InputMenu.show(context, a?.get(0) ?: (width - 80 * height / 1080f), a?.get(1) ?: (148 * height / 1080f)) }
                    "soundscape" -> idle.cycleSound()
                    else -> quickAction(t.kind)
                }
                invalidate()
            }
            Mode.NP -> { np.press(); if (!np.ambientOpen) { np.blur(); mode = Mode.DOCK } }
            Mode.CARDS -> cards().getOrNull(ci)?.let { if (CustomCards.isCustom(it)) Plat.openCustom(it) else act.openCard(it.pkg, it.intentUri) }
            Mode.MORE -> store.items.getOrNull(mi)?.let { launch(it) }
            Mode.MENU -> chooseMenu()
            Mode.MOVING -> { mode = Mode.DOCK; fi = movingIndex; invalidate() }
            Mode.POP -> if (peopleUi.lifted) peopleUi.drop() else peopleUi.picked()?.let { switchTo(it.id); mode = Mode.TOP }
            Mode.KIDS -> {}
        }
    }

    private fun longPress() {
        when (mode) {
            Mode.CARDS -> if (!cardMenu && cards().isNotEmpty()) { cardMenu = true; cardMenuPick = 0 }
            Mode.SETTINGS -> settings.hold()
            Mode.POP -> peopleUi.lift()
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

    private fun launch(a: AppItem) {
        if (a.inputId != null) { if (!TvInputs.open(context, a.inputId)) TvInputs.say(context, TvInputs.NO_INPUTS); return }   // a pinned TV input
        store.opened(a.pkg); act.launch(a.pkg); invalidate()
    }
    // ------------------------------------------------------------------ who is watching

    /** Another person is on screen: their own apps, and the kids shelf if theirs is a kids profile. */
    private fun switchTo(id: String) {
        ppl.select(id)
        if (unlockedFor != null && unlockedFor != id) unlockedFor = null
        syncPerson()
        fi = fi.coerceIn(0, dockSlots() - 1)
    }

    /** Call whenever who is watching, or the people, may have changed: app order follows the person, kids mode comes or goes. */
    private fun syncPerson() {
        val cur = ppl.current()
        store.setProfile(if (ppl.count >= 2) cur?.id else null)
        if (unlockedFor != null && cur?.id != unlockedFor) unlockedFor = null
        fi = fi.coerceIn(0, dockSlots() - 1)
        if (cur != null && ppl.isKid(cur) && cur.id != unlockedFor && mode != Mode.SETTINGS) {
            if (mode != Mode.KIDS) mode = Mode.KIDS
            kids.enter(cur)
        } else if (kids.active || mode == Mode.KIDS) {
            if (mode == Mode.KIDS) { mode = Mode.DOCK; fi = 0 }
            kids.leave()
        }
        invalidate()
    }

    /** The parent PIN was right: the TV moves to the first grown up, or stays on this profile, out of kids mode, until it changes. */
    private fun kidUnlocked(kid: Person) {
        val grown = ppl.list().firstOrNull { !ppl.isKid(it) }
        if (grown != null) { ppl.select(grown.id); unlockedFor = null } else unlockedFor = kid.id
        mode = Mode.DOCK; fi = 0
        kids.leave()
        syncPerson()
    }


    private fun openMenu(index: Int) {
        menuFrom = mode
        menuIndex = index
        menuPick = 0
        mode = Mode.MENU
    }

    private fun chooseMenu() {
        val a = store.items.getOrNull(menuIndex) ?: run { mode = menuFrom; return }
        when (menuNames().getOrNull(menuPick)) {
            "Move" -> { mode = Mode.MOVING; movingIndex = menuIndex }   // menuIndex is always in the dock here
            "Hide from home", "Remove from home" -> {
                store.hide(a.pkg)
                mode = menuFrom
                fi = fi.coerceIn(0, dockSlots() - 1)
                mi = mi.coerceIn(0, maxOf(0, store.items.size - 1))
                TvInputs.say(context, "Hidden. Show it again in Settings, Apps.")
            }
            "Uninstall" -> { mode = menuFrom; act.uninstall(a.pkg) }
            "App info" -> { mode = menuFrom; act.appInfo(a.pkg) }
        }
        invalidate()
    }

    /** The hold-OK menu's rows: an app can be moved, hidden, uninstalled or looked up; a pinned TV input can be moved or taken off. */
    private fun menuNames(): List<String> =
        if (store.items.getOrNull(menuIndex)?.inputId != null) listOf("Move", "Remove from home")
        else listOf("Move", "Hide from home", "Uninstall", "App info")

    // ------------------------------------------------------------------ drawing

    // ------------------------------------------------------------------ quick buttons beyond Sleep and Look, and Keep watching's menu

    private fun note(message: String) {
        noteText = message; noteUntil = android.os.SystemClock.uptimeMillis() + 6000
        main.postDelayed({ invalidate() }, 6100)
    }

    private fun toast(message: String) {
        toastText = message; toastUntil = android.os.SystemClock.uptimeMillis() + 3000
        main.postDelayed({ invalidate() }, 3100)
    }

    private fun quickAction(kind: String) {
        when (kind) {
            "screen_off" -> { screenOff = true; screenOffAt = android.os.SystemClock.uptimeMillis(); quickSys.screenOff(true) }
            "screensaver" -> if (!quickSys.startScreensaver()) note("This TV has no screensaver to open.")
            "headphones", "bluetooth" -> if (!quickSys.openSettingsScreen("bluetooth")) note("This TV has no Bluetooth screen to open.")
            "network" -> if (!quickSys.openSettingsScreen("network")) note("This TV has no network screen to open.")
            "tvsettings" -> if (!quickSys.openTvSettings()) note("Could not open the TV's settings.")
        }
    }

    /** Any button wakes the Screen off cover. [code] is the key, so its release is not taken for a press. */
    private fun wake(code: Int) { screenOff = false; wokeBy = code; quickSys.screenOff(false); invalidate() }

    /** Back wakes the screen or closes the card menu before it does anything else. */
    private fun quickBack(): Boolean {
        if (screenOff) { wake(-1); return true }
        if (cardMenu) { cardMenu = false; invalidate(); return true }
        return false
    }

    private fun cardMenuKey(code: Int) {
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> if (cardMenuPick > 0) cardMenuPick--
            KeyEvent.KEYCODE_DPAD_DOWN -> if (cardMenuPick < CARD_MENU.size - 1) cardMenuPick++
        }
        invalidate()
    }

    private fun chooseCardMenu() {
        val card = cards().getOrNull(ci)
        cardMenu = false
        if (cardMenuPick == 0 && card != null) { watch.dismiss(card); toast("Removed") }
        invalidate()
    }

    /** Classic and OLED show the focused show's title big at the top left. Night's cards carry their own titles. */
    private fun heroCard(): Card? = if (look == Look.NIGHT || mode != Mode.CARDS) null else cards().getOrNull(ci)

    private fun drawQuickOverlays(c: Canvas, u: Float) {
        heroCard()?.let { card ->
            val sub = listOf(card.app, card.episode.replace(" E", ", E")).filter { it.isNotBlank() }.joinToString("  ·  ")
            over.drawHero(c, u, if (people().size >= 2) 132 * u else 60 * u, card.title, sub)
        }
        if (cardMenu && cards().getOrNull(ci) != null) {
            // the card's middle and top, as drawCards and drawHomeGrid lay it out
            val night = look == Look.NIGHT
            val first = if (night) maxOf(0, ci - 2) else maxOf(0, ci - 4)
            val ax = if (night) 80 * u + (ci - first) * (542 * u) + 260 * u else 80 * u + (ci - first) * 360 * u + 160 * u
            over.drawMenu(c, u, width, ax, if (night) 692 * u else 369 * u, CARD_MENU, cardMenuPick)
        }
        val now = android.os.SystemClock.uptimeMillis()
        if (noteText.isNotEmpty() && now < noteUntil) over.drawNote(c, u, width - 80 * u, if (look == Look.NIGHT) 190 * u else 400 * u, noteText)
        if (toastText.isNotEmpty() && now < toastUntil) over.drawToast(c, u, width, toastText)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { sceneKey = ""; super.onSizeChanged(w, h, ow, oh) }

    private fun clockText(cal: Calendar): String {
        val h24 = when (prefs.getInt("clock", 0)) { 1 -> false; 2 -> true; else -> DateFormat.is24HourFormat(context) }
        return DateFormat.format(if (h24) "HH:mm" else "h:mm", cal).toString()
    }
    private fun dateText(cal: Calendar): String = DateFormat.format("EEEE d MMMM", cal).toString()

    /** Keeps the scene bitmap current: once a minute for Night (the clock is in it), once for Classic. Off the main thread. */
    private fun ensureScene() {
        if (solid || width == 0 || sceneBusy) return
        val cal = Calendar.getInstance(Locale.getDefault())
        weather.refresh()
        val tx = clockText(cal); val dt = dateText(cal); val wx = weather.text()
        val dim = SceneRenderer.isDim(cal.get(Calendar.HOUR_OF_DAY))
        val sp = scene()
        val ph = photo()
        val ls = looks.signature()
        val key = if (look == Look.NIGHT) "N|${sp.id}|$tx|$dt|$wx|$dim|${width}x$height|$ls" else "C|${ph.file}|$dim|${width}x$height|$ls"
        if (key == sceneKey) return
        sceneBusy = true
        val w = width; val h = height; val lk = look
        val style = looks.clockStyle(); val video = looks.videoPlaying
        // the blurred copy the glass is cut from: Night always (unless nothing is blurred), Classic only for glass panels or icons
        val wantBase = looks.blurPx > 0f && (lk == Look.NIGHT || looks.glass == Looks.Glass.ON || looks.glassIcons)
        val gk = if (wantBase) "${if (lk == Look.NIGHT) sp.id else ph.file}|$dim|${w}x$h|${looks.blurPx}|${looks.baseSaturation}|$lk" else ""
        val needGlass = gk.isNotEmpty() && glassKey != gk
        if (gk.isEmpty() && glassBmp != null) { glassBmp?.recycle(); glassBmp = null; glassKey = "" }   // nothing is blurred: give the 4 MB back
        worker.execute {
            val bmp = runCatching {
                if (lk == Look.NIGHT) SceneRenderer.render(context, sp, tx, dt, wx, w, h, dim, style)
                else SceneRenderer.classic(context, ph, w, h, dim, video)
            }.getOrNull()
            val glass = if (needGlass) runCatching { makeBase(lk, sp, ph, w, h, dim) }.getOrNull() else null
            main.post {
                sceneBusy = false
                if (glass != null) { val og = glassBmp; glassBmp = glass; glassKey = gk; og?.recycle() }
                if (bmp != null && lk == look) {
                    val old = sceneBmp
                    val sid = if (lk == Look.NIGHT) sp.id else ph.file
                    if (old != null && shownId != sid) {   // a different scene: fade from the old one
                        prevBmp?.recycle(); prevBmp = old; fadeStart = android.os.SystemClock.uptimeMillis()
                    } else old?.recycle()
                    sceneBmp = bmp; shownId = sid; sceneKey = key; invalidate()
                } else bmp?.recycle()
            }
        }
    }

    /** The blurred background for glass panels: the scene's photo (or the Classic photo), at the blur and saturation the glass setting asks for. */
    private fun makeBase(lk: Look, sp: SceneRenderer.Spec, ph: SceneRenderer.Photo, w: Int, h: Int, dim: Boolean): Bitmap? {
        val src = if (lk == Look.NIGHT) (if (sp.photo.startsWith("file:")) sp.photo else "scenes/${sp.photo}.jpg")
                  else (if (ph.path != null) "file:${ph.path}" else "backgrounds/${ph.file}.jpg")
        return GlassBase.make(context, src, w, h, dim, if (lk == Look.NIGHT) sp.photoBrightness else 1f,
            looks.blurPx, looks.baseSaturation, looks.baseBrightness, if (lk == Look.NIGHT) 1 else 2)
    }

    /** OLED burn-in shift (+-2 px a minute), the bedtime dimming over everything, and a steady frame rate while a tile sways. */
    override fun onDraw(c: Canvas) {
        looks.motionDrawn = false
        if (idle.coversHome()) { idle.drawOver(c, width, height); return }   // the ambient clock or the dark screen: nothing underneath to draw
        val now = android.os.SystemClock.uptimeMillis()
        frameMs = if (animLast == 0L) 16L else (now - animLast).coerceIn(4L, 50L); animLast = now
        animBusy = false
        // a whole screen coming up (More apps, Settings, Smart home, kids, the music view) fades in; the home itself does not
        val sk = when { np.ambientOpen -> 4; mode == Mode.MORE -> 1; mode == Mode.SETTINGS -> 2; mode == Mode.SMART -> 3; mode == Mode.KIDS -> 5; else -> 0 }
        if (sk != fadeKey) { fadeKey = sk; fadeAt = now }
        val fd = Motion.ms(prefs, 220)
        val fadeF = if (fd <= 0L) 1f else ((now - fadeAt).toFloat() / fd).coerceIn(0f, 1f)
        val shift = look == Look.OLED && mode != Mode.SETTINGS
        if (shift) { val u = height / 1080f; c.save(); c.translate(looks.shiftX(u), looks.shiftY(u)) }
        if (fadeF < 1f) { c.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (eased(fadeF) * 255).toInt()); animBusy = true }
        drawHome(c)
        if (fadeF < 1f) c.restore()
        if (shift) c.restore()
        idle.drawOver(c, width, height)
        val fading = looks.drawBedtime(c, width, height, paint)
        if (animBusy) postInvalidateOnAnimation() else if (fading || looks.motionDrawn) postInvalidateDelayed(40)
    }

    private fun drawHome(c: Canvas) {
        val u = height / 1080f
        if (VizSettings.previewShown && mode != Mode.SETTINGS) settings.closePreview()
        if (screenOff) { if (over.drawScreenOff(c, width, height, u, android.os.SystemClock.uptimeMillis() - screenOffAt, "Press any button to wake")) postInvalidateOnAnimation(); return }
        if (mode == Mode.KIDS) { kids.draw(c, width, height); return }
        if (mode == Mode.SETTINGS) { settings.draw(c, width, height); return }
        if (mode == Mode.SMART) { smart.draw(c, width, height); return }
        val wantViz = np.ambientOpen && np.vizWanted()
        if (wantViz != vizOn) { vizOn = wantViz; if (!wantViz) np.vizLive = false; vizHook?.invoke(wantViz) }
        if (np.ambientOpen) { np.drawAmbient(c, width, height, u); if (np.animating) postInvalidateOnAnimation() else postInvalidateDelayed(100); return }
        if (mode == Mode.MORE || (mode == Mode.MENU && menuFrom == Mode.MORE) || solid) {
            c.drawColor(when { look == Look.DARK -> Color.rgb(20, 22, 29); look == Look.OLED && mode != Mode.MORE -> Color.BLACK; else -> ground })
        } else {
            ensureScene()
            val b = sceneBmp
            val pv = prevBmp
            if (pv != null && b != null) {
                val sd = Motion.ms(prefs, 1600); val f = if (sd <= 0L) 1f else ((android.os.SystemClock.uptimeMillis() - fadeStart).toFloat() / sd).coerceIn(0f, 1f)
                c.drawBitmap(pv, 0f, 0f, null)
                paint.alpha = (f * 255).toInt(); c.drawBitmap(b, 0f, 0f, paint); paint.alpha = 255
                if (f >= 1f) { pv.recycle(); prevBmp = null } else postInvalidateOnAnimation()
            } else if (b != null) c.drawBitmap(b, 0f, 0f, null) else if (!looks.videoPlaying) c.drawColor(ground)
            if (look == Look.NIGHT) {   // the scrim couch-tv puts under the dock: clear at 60% of the height, half black at the bottom
                paint.style = Paint.Style.FILL
                paint.shader = LinearGradient(0f, height * 0.6f, 0f, height.toFloat(), 0x00000000, 0x80000000.toInt(), Shader.TileMode.CLAMP)
                c.drawRect(0f, height * 0.6f, width.toFloat(), height.toFloat(), paint)
                paint.shader = null
            }
        }
        val moreShown = mode == Mode.MORE || (mode == Mode.MENU && menuFrom == Mode.MORE)
        if (!solid && !moreShown) fx.draw(c, width, height)   // sun colour and weather, over the scene, under everything else
        drawClock(c, u)
        val ra = idle.rowsAlpha()   // Hide rows when idle: the rows fade, the clock and scene stay
        if (ra > 0f) {
            if (ra < 1f) c.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (ra * 255).toInt())
            when (mode) {
                Mode.MORE -> drawMore(c, u)
                Mode.MENU -> { if (menuFrom == Mode.MORE) drawMore(c, u) else { drawTop(c, u); drawCards(c, u); drawDock(c, u) }; drawMenu(c, u) }
                else -> { drawTop(c, u); drawCards(c, u); drawDock(c, u) }
            }
            np.fa = anim(2000, np.focused && mode == Mode.NP)
            if (!moreShown) np.draw(c, u, look == Look.NIGHT, width, ::glass)
            if (ra < 1f) c.restore()
        }
        drawQuickOverlays(c, u)
        if (mode == Mode.POP) peopleUi.drawPop(c, u)
    }

    /** Classic and OLED: the clock at the top right, the date, and under Classic the photo's place and photographer. */
    private fun drawClock(c: Canvas, u: Float) {
        if (mode == Mode.MORE || look == Look.NIGHT || (mode == Mode.MENU && menuFrom == Mode.MORE)) return
        val cal = Calendar.getInstance()
        val h24 = when (prefs.getInt("clock", 0)) { 1 -> false; 2 -> true; else -> DateFormat.is24HourFormat(context) }
        val right = width - 80 * u
        clockPaint.typeface = Fonts.light(context); clockPaint.color = ink
        clockPaint.textAlign = Paint.Align.RIGHT; clockPaint.textSize = 112 * u; clockPaint.letterSpacing = -0.035f
        clockPaint.setShadowLayer(24 * u, 0f, 2 * u, Color.argb(89, 0, 0, 0))
        if (looks.glass != Looks.Glass.OFF) {   // Glass panels: a glass card behind the clock and the date
            text.typeface = Fonts.semibold(context); text.textSize = 30 * u
            val cw = maxOf(clockPaint.measureText(clockText(cal)) + (if (h24) 0f else 90 * u), text.measureText(dateText(cal)))
            gstyle.panel(c, right - cw - 40 * u, 46 * u, right + 28 * u, 268 * u, 36 * u, glassBmp, u)
        }
        var x = right
        if (!h24) {
            text.typeface = Fonts.regular(context); text.textSize = 36 * u; text.textAlign = Paint.Align.RIGHT
            text.setShadowLayer(24 * u, 0f, 2 * u, Color.argb(89, 0, 0, 0))
            val ampm = if (cal.get(Calendar.HOUR_OF_DAY) < 12) "am" else "pm"
            c.drawText(ampm, right, 158 * u, text)
            x = right - text.measureText(ampm) - 8 * u
        }
        c.drawText(clockText(cal), x, 158 * u, clockPaint)
        clockPaint.clearShadowLayer(); clockPaint.letterSpacing = 0f
        text.typeface = Fonts.semibold(context); text.textSize = 30 * u; text.textAlign = Paint.Align.RIGHT
        c.drawText(dateText(cal), right, 241 * u, text)
        if (look == Look.CLASSIC && heroCard() == null) {
            val p = photo()
            text.typeface = Fonts.regular(context); text.textSize = 23 * u; text.color = inkA(168)
            c.drawText("${p.place}  ·  Photo: ${p.by}", right, 281 * u, text); text.color = ink
        }
        text.clearShadowLayer(); text.textAlign = Paint.Align.LEFT
    }

    private fun drawDock(c: Canvas, u: Float) {
        if (look != Look.NIGHT) { drawHomeGrid(c, u); return }
        // couch-tv's Night dock: a glass pill, 7 rem icons 1.625 rem apart, 2.875 rem off the bottom
        val n = dockSlots() - 1   // the apps (and Smart home), centred; Settings sits bottom right on its own
        val fit = minOf(1f, (width - 360 * u) / (n * 138 * u + 30 * u))
        val tw = 112 * u * fit; val gap = 26 * u * fit
        val padX = 28 * u * fit; val padY = 22 * u * fit
        val pw = n * tw + (n - 1) * gap + 2 * padX
        val ph = tw + 2 * padY
        val pl = (width - pw) / 2f
        val pt = height - 46 * u - ph
        // no card behind the dock: the icons sit straight on the scene
        val focus = if (mode == Mode.MOVING) movingIndex else if (mode == Mode.CARDS || mode == Mode.TOP || mode == Mode.NP) -1 else fi
        for (pass in 0..1) for (i in 0 until n) {
            if ((i == focus) != (pass == 1)) continue
            nightIcon(c, u, pl + padX + i * (tw + gap), pt + padY, tw, i, i == focus, mode == Mode.MOVING)
        }
        // Settings: a round glass button in the bottom right corner, the same as the quick buttons at the top
        val gs = 88 * u
        val gl = width - 80 * u - gs; val gt = height - 46 * u - gs
        val gf = gearIdx() == focus
        val am = anim(gearIdx(), gf)
        if (am > 0.001f) { paint.style = Paint.Style.FILL; paint.color = ink; paint.alpha = (255 * am).toInt(); rect.set(gl - 3 * u, gt - 3 * u, gl + gs + 3 * u, gt + gs + 3 * u); c.drawRoundRect(rect, 47 * u, 47 * u, paint) }
        glass(c, gl, gt, gl + gs, gt + gs, 44 * u)
        if (am > 0.001f) { paint.style = Paint.Style.FILL; paint.color = ink; paint.alpha = (255 * am).toInt(); rect.set(gl, gt, gl + gs, gt + gs); c.drawRoundRect(rect, 44 * u, 44 * u, paint) }
        paint.alpha = 255
        glyph(c, u, gl + gs / 2, gt + gs / 2, 112 * u * 0.62f, true, if (am > 0.5f) Color.rgb(17, 17, 17) else ink)
        if (Updater.ready) { paint.style = Paint.Style.FILL; paint.color = Color.parseColor("#3D8BFF"); paint.alpha = 255; c.drawCircle(gl + gs - 8 * u, gt + 8 * u, 7 * u, paint) }
    }

    /** Night's top row: the people switch at 80, 60 and the round quick buttons at the right edge. */
    private fun drawTop(c: Canvas, u: Float) {
        if (look != Look.NIGHT) { drawTopClassic(c, u); return }
        val items = topItems()
        if (items.isEmpty()) return
        val people = items.filter { it.kind == "person" }
        val sel = ppl.currentIndex().coerceIn(0, maxOf(0, people.size - 1))
        text.typeface = Fonts.semibold(context); text.textSize = 23 * u; text.textAlign = Paint.Align.LEFT
        if (people.isNotEmpty()) {
            val pw = people.map { text.measureText(it.label) + 40 * u }
            val total = pw.sum() + (people.size - 1) * 8 * u + 12 * u
            glass(c, 80 * u, 60 * u, 80 * u + total, 108 * u, 32 * u)
            var x = 86 * u
            for (i in people.indices) {
                val on = i == sel
                val focus = mode == Mode.TOP && ti == i
                rect.set(x, 66 * u, x + pw[i], 102 * u)
                if (focus) {
                    paint.style = Paint.Style.FILL; paint.color = ink
                    val g = 3 * u; paint.style = Paint.Style.STROKE; paint.strokeWidth = g; rect.inset(-g / 2, -g / 2); c.drawRoundRect(rect, 24 * u + g / 2, 24 * u + g / 2, paint); rect.inset(g / 2, g / 2); paint.style = Paint.Style.FILL
                }
                if (on || focus) { paint.style = Paint.Style.FILL; paint.color = if (on) ink else inkA(41); c.drawRoundRect(rect, 24 * u, 24 * u, paint) }
                text.color = if (on) Color.rgb(17, 17, 17) else if (focus) ink else inkA(199)
                c.drawText(people[i].label, x + 20 * u, 91 * u, text)
                x += pw[i] + 8 * u
            }
            text.color = ink
        }
        if (items.firstOrNull()?.kind == "head") peopleUi.drawHead(c, u, mode == Mode.TOP && ti == 0, true)
        // quick buttons, right to left from 80 px off the edge
        var right = width - 80 * u
        val quick = items.filter { it.kind != "person" && it.kind != "head" }.asReversed()
        for (q in quick) {
            val idx = items.indexOf(q)
            val focus = mode == Mode.TOP && ti == idx
            val label = when {
                q.kind == "sleep" && sleepLeft() > 0 -> "Off in ${sleepLeft()} min"
                q.kind == "soundscape" && idle.soundType() != "off" -> idle.soundLabel()
                else -> ""
            }
            text.textSize = 23 * u; text.typeface = Fonts.semibold(context)
            val w = if (label.isEmpty()) 88 * u + 4 * u else 28 * u + 36 * u + 10 * u + text.measureText(label) + 28 * u
            val l = right - w
            qAnchor.getOrPut(q.kind) { FloatArray(2) }.also { it[0] = right; it[1] = 148 * u }
            if (focus) {
                paint.style = Paint.Style.FILL; paint.color = ink
                rect.set(l - 3 * u, 57 * u, right + 3 * u, 151 * u); c.drawRoundRect(rect, 47 * u, 47 * u, paint)
            }
            if (focus) { paint.style = Paint.Style.FILL; paint.color = ink; rect.set(l, 60 * u, right, 148 * u); c.drawRoundRect(rect, 44 * u, 44 * u, paint) }
            else glass(c, l, 60 * u, right, 148 * u, 44 * u)
            val fg = if (focus) Color.rgb(17, 17, 17) else ink
            quickIcon(c, u, q.kind, if (label.isNotEmpty()) l + 28 * u + 18 * u else (l + right) / 2f, 104 * u, fg)
            if (label.isNotEmpty()) {
                text.color = fg; text.textSize = 23 * u; text.typeface = Fonts.semibold(context)
                c.drawText(label, l + 28 * u + 36 * u + 10 * u, 112 * u, text); text.color = ink
            }
            right = l - 16 * u
        }
    }

    /** Classic and OLED: a flat people switch top left, and labelled quick buttons under the clock. */
    private fun drawTopClassic(c: Canvas, u: Float) {
        val items = topItems()
        if (items.isEmpty()) return
        val people = items.filter { it.kind == "person" }
        val sel = ppl.currentIndex().coerceIn(0, maxOf(0, people.size - 1))
        text.typeface = Fonts.semibold(context); text.textSize = 23 * u; text.textAlign = Paint.Align.LEFT
        if (people.isNotEmpty()) {
            val pw = people.map { text.measureText(it.label) + 40 * u }
            val total = pw.sum() + (people.size - 1) * 8 * u + 12 * u
            paint.style = Paint.Style.FILL; paint.color = inkA(31)
            rect.set(80 * u, 60 * u, 80 * u + total, 108 * u); c.drawRoundRect(rect, 32 * u, 32 * u, paint)
            var x = 86 * u
            for (i in people.indices) {
                val on = i == sel; val focus = mode == Mode.TOP && ti == i
                rect.set(x, 66 * u, x + pw[i], 102 * u)
                if (focus) { paint.color = ink; val g = 3 * u; paint.style = Paint.Style.STROKE; paint.strokeWidth = g; rect.inset(-g / 2, -g / 2); c.drawRoundRect(rect, 24 * u + g / 2, 24 * u + g / 2, paint); rect.inset(g / 2, g / 2); paint.style = Paint.Style.FILL }
                if (on || focus) { paint.color = if (on) ink else inkA(41); c.drawRoundRect(rect, 24 * u, 24 * u, paint) }
                text.color = if (on) Color.rgb(17, 17, 17) else if (focus) ink else inkA(199)
                c.drawText(people[i].label, x + 20 * u, 91 * u, text)
                x += pw[i] + 8 * u
            }
            text.color = ink
        }
        if (items.firstOrNull()?.kind == "head") peopleUi.drawHead(c, u, mode == Mode.TOP && ti == 0, false)
        var right = width - 80 * u
        for (q in items.filter { it.kind != "person" && it.kind != "head" }.asReversed()) {
            val idx = items.indexOf(q); val focus = mode == Mode.TOP && ti == idx
            val set = (q.kind == "sleep" && sleepLeft() > 0) || (q.kind == "soundscape" && idle.soundType() != "off")
            val label = when { q.kind == "look" -> lookLabel(); q.kind == "soundscape" -> idle.soundLabel(); q.kind == "input" -> "Input"; set -> "Off in ${sleepLeft()} min"; q.kind != "sleep" -> QuickButtons.pill(q.kind); else -> "Sleep timer" }
            val w = 16 * u + 24 * u + 10 * u + text.measureText(label) + 20 * u
            val l = right - w
            qAnchor.getOrPut(q.kind) { FloatArray(2) }.also { it[0] = right; it[1] = 360 * u }
            rect.set(l, 312 * u, right, 360 * u)
            paint.style = Paint.Style.FILL
            paint.color = if (focus) ink else if (set) Color.argb(168, 16, 16, 20) else Color.argb(117, 16, 16, 20)
            c.drawRoundRect(rect, 32 * u, 32 * u, paint)
            val fg = if (focus) Color.rgb(17, 17, 17) else ink
            quickIcon(c, u * 0.75f, q.kind, l + 16 * u + 12 * u, 336 * u, fg)
            text.color = fg; c.drawText(label, l + 16 * u + 24 * u + 10 * u, 345 * u, text); text.color = ink
            right = l - 12 * u
        }
    }

    private fun quickIcon(c: Canvas, u: Float, kind: String, cx: Float, cy: Float, color: Int) {
        if (kind != "sleep" && kind != "look" && kind != "input" && kind != "soundscape") { QuickButtons.icon(c, paint, kind, cx, cy, 1.45f * u, color); return }
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 3.2f * u; paint.color = color; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
        clip.reset()
        if (kind == "sleep") {   // a crescent moon
            val r = 15 * u
            rect.set(cx - r, cy - r, cx + r, cy + r)
            clip.arcTo(rect, 50f, 260f, true)
            rect.set(cx - r * 0.35f, cy - r * 1.05f, cx + r * 1.2f, cy + r * 0.95f)
            clip.arcTo(rect, 290f, -220f, false)
            c.drawPath(clip, paint)
        } else if (kind == "input") {   // a screen with an arrow into it
            Glyphs.input(c, paint, cx, cy, 1.5f * u, color, 2.1f)
        } else if (kind == "soundscape") {   // sound waves: three bars of different heights
            for ((i, hgt) in floatArrayOf(10f, 20f, 14f, 24f).withIndex()) c.drawLine(cx + (i - 1.5f) * 9 * u, cy - hgt * u * 0.5f, cx + (i - 1.5f) * 9 * u, cy + hgt * u * 0.5f, paint)
        } else {                 // a sun-like look switch: a circle with rays
            c.drawCircle(cx, cy, 8 * u, paint)
            for (a in 0 until 8) {
                val t = Math.toRadians(a * 45.0)
                c.drawLine(cx + (13 * u * Math.cos(t)).toFloat(), cy + (13 * u * Math.sin(t)).toFloat(),
                    cx + (17 * u * Math.cos(t)).toFloat(), cy + (17 * u * Math.sin(t)).toFloat(), paint)
            }
        }
        paint.style = Paint.Style.FILL; paint.strokeCap = Paint.Cap.BUTT
    }

    /** Night's Keep watching: a heading and glass cards, 520 x 141, 22 apart, from 80 px in. Three fit; the row slides to keep focus in view. */
    private fun drawCards(c: Canvas, u: Float) {
        if (look != Look.NIGHT || cardMenu) return   // the cards step aside while their menu is open
        val list = cards()
        if (list.isEmpty()) return
        text.typeface = Fonts.semibold(context); text.textAlign = Paint.Align.LEFT
        text.textSize = 25 * u; text.color = inkA(199)
        c.drawText("Keep watching", 80 * u, 640 * u + 27 * u, text)
        text.color = ink
        val cw = 520 * u; val gap = 22 * u; val top = 692 * u; val ch = 141 * u
        val first = if (mode == Mode.CARDS) maxOf(0, ci - 2) else 0
        for (pass in 0..1) for (i in first until list.size) {
            val focused = mode == Mode.CARDS && i == ci
            if (focused != (pass == 1)) continue
            val x = 80 * u + np.slotWidth(u) + (i - first) * (cw + gap)
            if (x > width) continue
            drawCard(c, u, list[i], x, top, cw, ch, focused, anim(1000 + i, focused))
        }
    }

    private val cardBlur = HashMap<String, Pair<Bitmap, Bitmap>>()

    private fun drawCard(c: Canvas, u: Float, card: Card, x: Float, y: Float, w: Float, h: Float, focused: Boolean, am: Float) {
        c.save()
        if (am > 0.001f) { val k = 1f + 0.05f * am; c.translate(x + w / 2, y + h / 2); c.scale(k, k); c.translate(-(x + w / 2), -(y + h / 2)) }
        val r = 30 * u * looks.radiusK
        if (am > 0.001f) {
            paint.style = Paint.Style.FILL; paint.color = Color.argb((90 * am).toInt(), 0, 0, 0)
            rect.set(x, y + 12 * u, x + w, y + h + 12 * u)
            c.drawRoundRect(rect, r, r, paint)
            paint.color = ink; paint.alpha = (255 * am).toInt()
            rect.set(x - 3 * u, y - 3 * u, x + w + 3 * u, y + h + 3 * u)
            c.drawRoundRect(rect, r + 3 * u, r + 3 * u, paint)
        }
        glass(c, x, y, x + w, y + h, r)
        // the picture, blurred, fills the whole card behind the words (like the music view)
        val pic = watch.picture(card, (168 * u).toInt(), (95 * u).toInt())
        if (pic != null && looks.cardArt) {
            val key = card.app + "\n" + card.title
            var e = cardBlur[key]
            if (e == null || e.first !== pic) { if (cardBlur.size > 16) cardBlur.clear(); e = pic to ArtBlur.soft(pic); cardBlur[key] = e }
            rect.set(x, y, x + w, y + h); clip.reset(); clip.addRoundRect(rect, r, r, Path.Direction.CW)
            c.save(); c.clipPath(clip); paint.isFilterBitmap = true; paint.style = Paint.Style.FILL; paint.alpha = 255
            ArtBlur.fill(c, e.second, rect, paint)
            paint.color = Color.argb(looks.scrimAlpha, 0, 0, 0); c.drawRect(rect, paint); c.restore()
        }
        // the picture: 168 x 95, 16 px in, with 16 px corners
        val tx = x + 16 * u; val ty = y + (h - 95 * u) / 2
        rect.set(tx, ty, tx + 168 * u, ty + 95 * u)
        paint.style = Paint.Style.FILL; paint.color = inkA(20)
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
        text.typeface = Fonts.medium(context); text.textSize = 19 * u; text.letterSpacing = 0.06f; text.color = inkA(148)
        val nap = TextUtils.ellipsize(card.app.uppercase(), text, sw, TextUtils.TruncateAt.END)
        c.drawText(nap, 0, nap.length, sx, top + 18 * u, text)
        text.letterSpacing = 0f
        text.typeface = Fonts.semibold(context); text.textSize = 29 * u; text.color = Color.rgb(245, 245, 247)
        val title = TextUtils.ellipsize(card.title, text, sw, TextUtils.TruncateAt.END)
        c.drawText(title, 0, title.length, sx, top + 24 * u + 28 * u, text)
        if (card.episode.isNotEmpty()) {
            text.typeface = Fonts.regular(context); text.textSize = 21 * u; text.color = inkA(199)
            c.drawText(card.episode, sx, top + 60 * u + 22 * u, text)
        }
        text.color = ink
        if (card.progress > 0f) {
            val by = top + 104 * u
            paint.color = inkA(64)
            rect.set(sx, by, sx + sw, by + 5 * u); c.drawRoundRect(rect, 3 * u, 3 * u, paint)
            paint.color = ink
            rect.set(sx, by, sx + sw * card.progress, by + 5 * u); c.drawRoundRect(rect, 3 * u, 3 * u, paint)
        }
        c.restore()
    }

    /** couch-tv's glass panel (GlassStyle.kt): the home's own plum panel, or the clear one, as Look and scenes chose. */
    private fun glass(c: Canvas, l: Float, t: Float, r: Float, b: Float, radius: Float) {
        gstyle.panel(c, l, t, r, b, radius, glassBmp, height / 1080f)
    }

    private fun nightIcon(c: Canvas, u: Float, x: Float, y: Float, size: Float, index: Int, focused: Boolean, moving: Boolean) {
        val app = if (index < store.line) store.items[index] else null
        val radius = size * 0.265f
        // glass app icons: the app's glyph on a glass tile; the focused app shows its own colours unless "Stays glass"
        val glassy = looks.glassIcons && app != null && (!focused || looks.glassFocusStay)
        val am = anim(index, focused)   // 0 to 1: the lift and ring ease in and out
        c.save()
        val cx = x + size / 2; val cy = y + size / 2
        if (am > 0.001f) { val k = 1f + 0.16f * am; c.translate(cx, cy - 10 * u * am * (size / (112 * u))); c.scale(k, k); c.translate(-cx, -cy); if (focused) looks.sway(c, cx, cy) }
        rect.set(x, y, x + size, y + size)
        if (am > 0.001f && !glassy) {   // the white ring and its shadow
            softShadow(c, rect, radius, 18 * u, 22 * u, ((if (app != null) 110 else 40) * am).toInt())
            // a ring, not a white disc, so the Settings and More apps glass tiles stay see-through inside it
            rect.set(x - 2 * u, y - 2 * u, x + size + 2 * u, y + size + 2 * u)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 4 * u; paint.color = ink; paint.alpha = (255 * am).toInt()
            c.drawRoundRect(rect, radius + 2 * u, radius + 2 * u, paint)
            paint.style = Paint.Style.FILL
            rect.set(x, y, x + size, y + size)
        }
        paint.style = Paint.Style.FILL
        paint.color = if (app != null) Color.rgb(42, 42, 48) else inkA(36)
        paint.alpha = if (moving && !focused) 178 else if (app != null) 255 else 36
        if (glassy) gstyle.iconTile(c, x, y, x + size, y + size, radius, glassBmp, height / 1080f, focused)
        else if (app == null || icons.hasBackground(app)) c.drawRoundRect(rect, radius, radius, paint)   // a plain (not layered) icon gets no tile behind it
        if (app != null) {
            val bmp = if (glassy) null else icons.square(app, size.toInt().coerceAtLeast(1))
            if (glassy) glassGlyph(c, u, app, x, y, size, size, focused, false, radius)
            else if (bmp != null) {
                // the app's icon sits inside the tile with room around it, like the logos in couch-tv's dock
                val inset = 0f   // full size: the icon fills its tile
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
            if (index == smartIdx()) smartGlyph(c, u, cx, cy, size) else glyph(c, u, cx, cy, size, index == gearIdx())
            if (index == gearIdx() && Updater.ready) { paint.style = Paint.Style.FILL; paint.color = Color.parseColor("#3D8BFF"); paint.alpha = 255; c.drawCircle(x + size - 10 * u, y + 10 * u, 6 * u, paint) }
        }
        if (focused) {   // couch-tv's light sliding across a focused tile, held part way
            rect.set(x, y, x + size, y + size)
            clip.reset(); clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
            c.save(); c.clipPath(clip)
            paint.shader = LinearGradient(x + (looks.glintCentre() - 0.4f) * size, y, x + (looks.glintCentre() + 0.4f) * size, y + size, intArrayOf(0, Color.argb(46, 255, 255, 255), 0), floatArrayOf(0.3f, 0.55f, 0.8f), Shader.TileMode.CLAMP)
            paint.style = Paint.Style.FILL; paint.alpha = 255
            c.drawRect(rect, paint); paint.shader = null
            c.restore()
        }
        if (focused) {
            text.typeface = Fonts.semibold(context); text.textSize = 20 * u * (size / (112 * u)); text.textAlign = Paint.Align.CENTER
            c.drawText(dockTitle(index), cx, y + size + 12 * u + text.textSize * 0.85f, text)
        }
        c.restore()
    }

    /** A soft shadow under [r] (already at its place): stacked rounded rects, each a little bigger and fainter, so there is no hard edge and no blur filter to run on a weak TV. */
    private fun softShadow(c: Canvas, r: RectF, radius: Float, dy: Float, spread: Float, alpha: Int) {
        if (looks.lighter) return   // the lighter home casts no soft shadows
        val n = 7
        paint.style = Paint.Style.FILL
        val save = RectF(r)
        for (i in n downTo 1) {
            val g = spread * i / n
            r.set(save.left - g, save.top - g + dy, save.right + g, save.bottom + g + dy)
            paint.color = Color.argb(alpha / n + 1, 0, 0, 0)
            c.drawRoundRect(r, radius + g, radius + g, paint)
        }
        r.set(save)
    }

    /** The Smart home tile's house, stroked like the gear (couch-tv's icon: roof, walls, door). */
    private fun smartGlyph(c: Canvas, u: Float, cx: Float, cy: Float, size: Float) {
        val k = 58f * u / 24f * (size / (112 * u))
        paint.style = Paint.Style.STROKE; paint.color = ink; paint.alpha = 255
        paint.strokeWidth = 1.7f * k; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
        fun px(v: Float) = cx + (v - 12f) * k
        fun py(v: Float) = cy + (v - 12f) * k
        clip.reset()
        clip.moveTo(px(3.5f), py(11.2f)); clip.lineTo(px(12f), py(4f)); clip.lineTo(px(20.5f), py(11.2f))
        clip.moveTo(px(5.8f), py(9.5f)); clip.lineTo(px(5.8f), py(20f)); clip.lineTo(px(18.2f), py(20f)); clip.lineTo(px(18.2f), py(9.5f))
        clip.moveTo(px(10f), py(20f)); clip.lineTo(px(10f), py(14.8f)); clip.lineTo(px(14f), py(14.8f)); clip.lineTo(px(14f), py(20f))
        c.drawPath(clip, paint)
        paint.style = Paint.Style.FILL; paint.strokeCap = Paint.Cap.BUTT; paint.strokeJoin = Paint.Join.MITER
    }

    private val greyFilter = ColorMatrixColorFilter(ColorMatrix().apply {
        setSaturation(0f)
        postConcat(ColorMatrix(floatArrayOf(1.4f, 0f, 0f, 0f, -26f, 0f, 1.4f, 0f, 0f, -26f, 0f, 0f, 1.4f, 0f, -26f, 0f, 0f, 0f, 1f, 0f)))   // contrast 1.25, brightness 1.1 about the middle
    })

    /** The app's glyph, white, on its glass tile (ClearIcon.kt). An icon that will not split is shown grey and half see-through. [banner]: Classic's landscape tile. */
    private fun glassGlyph(c: Canvas, u: Float, app: AppItem, x: Float, y: Float, w: Float, h: Float, focused: Boolean, banner: Boolean, radius: Float) {
        val m = clear.mask(app, banner)
        paint.style = Paint.Style.FILL; paint.shader = null
        if (m != null) {
            if (banner) rect.set(x, y + h / 2 - w / 2, x + w, y + h / 2 + w / 2) else rect.set(x, y, x + w, y + h)
            clip.reset(); clip.addRoundRect(x, y, x + w, y + h, radius, radius, Path.Direction.CW)
            c.save(); c.clipPath(clip)
            paint.color = Color.argb(71, 0, 0, 0); rect.offset(0f, u); c.drawBitmap(m, null, rect, paint); rect.offset(0f, -u)   // a hair of shadow lifts the white glyph
            paint.color = ink; paint.alpha = ((if (focused) 1f else looks.glyphAlpha) * 255).toInt()
            c.drawBitmap(m, null, rect, paint)
            c.restore(); paint.alpha = 255
        } else if (clear.refused(app, banner)) {
            val b = if (banner) icons.get(app, w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1)) else icons.square(app, w.toInt().coerceAtLeast(1))
            if (b != null) {
                val inset = if (banner) 0f else 0f
                rect.set(x + inset, y + (if (banner) 0f else inset), x + w - inset, y + h - (if (banner) 0f else inset))
                clip.reset(); clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
                c.save(); c.clipPath(clip)
                paint.colorFilter = greyFilter; paint.alpha = if (focused) 255 else 140
                c.drawBitmap(b, null, rect, paint)
                paint.colorFilter = null; paint.alpha = 255
                c.restore()
            }
        }
        rect.set(x, y, x + w, y + h)
    }

    private val dimFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
        .82f, 0f, 0f, 0f, 0f, 0f, .82f, 0f, 0f, 0f, 0f, 0f, .82f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))

    /** The gear and the three dots, drawn with strokes the way couch-tv's icons are (24 unit grid, 36 px, stroke 1.7). */
    private fun glyph(c: Canvas, u: Float, cx: Float, cy: Float, size: Float, gear: Boolean, col: Int = Color.WHITE) {
        val k = 58f * u / 24f * (size / (112 * u))
        paint.style = Paint.Style.STROKE; paint.color = col; paint.alpha = 255
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

    /** Classic and OLED: Keep watching as posters, then the dock's apps (banners), Settings and More apps as an 8 wide grid. */
    private fun drawHomeGrid(c: Canvas, u: Float) {
        // Keep watching: 320 x 180 posters at 375, 40 apart, under a heading
        val list = cards()
        if (list.isNotEmpty()) {
            text.typeface = Fonts.semibold(context); text.textSize = 30 * u; text.textAlign = Paint.Align.LEFT; text.color = inkA(209)
            c.drawText("Keep watching", 80 * u, 342 * u, text); text.color = ink
            val first = if (mode == Mode.CARDS) maxOf(0, ci - 4) else 0
            for (pass in 0..1) for (i in first until list.size) {
                val focused = mode == Mode.CARDS && i == ci
                if (focused != (pass == 1)) continue
                val x = 80 * u + (i - first) * 360 * u
                if (x > width) continue
                posterTile(c, u, list[i], x, 375 * u, focused)
            }
        }
        // the apps: 184 x 104 banners, 40 apart across, 56 between rows, from 600
        val n = dockSlots()
        val tw = 184 * u; val th = 104 * u; val px = 224 * u; val py = 160 * u
        val focus = if (mode == Mode.MOVING) movingIndex else if (mode == Mode.DOCK || mode == Mode.MENU) fi else -1
        val rows = 3
        if (focus >= 0) { val row = focus / gridCols; if (row < homeRow) homeRow = row; if (row >= homeRow + rows) homeRow = row - rows + 1 }
        val topY = if (list.isEmpty()) 460 * u else 600 * u
        for (pass in 0..1) for (i in 0 until n) {
            if ((i == focus) != (pass == 1)) continue
            val r = i / gridCols - homeRow
            if (r < 0 || r >= rows) continue
            classicApp(c, u, i, 80 * u + (i % gridCols) * px, topY + r * py, tw, th, i == focus, mode == Mode.MOVING && i != focus)
        }
    }

    private fun posterTile(c: Canvas, u: Float, card: Card, x: Float, y: Float, focused: Boolean) {
        val w = 320 * u; val h = 180 * u; val r = 14 * u
        c.save()
        if (focused) { c.translate(x + w / 2, y + h / 2); c.scale(1.07f, 1.07f); c.translate(-(x + w / 2), -(y + h / 2)) }
        rect.set(x, y, x + w, y + h)
        if (focused) { paint.style = Paint.Style.FILL; softShadow(c, rect, r, 24 * u, 36 * u, 190) }
        paint.style = Paint.Style.FILL; paint.color = Color.rgb(28, 28, 33)
        c.drawRoundRect(rect, r, r, paint)
        clip.reset(); clip.addRoundRect(rect, r, r, Path.Direction.CW)
        c.save(); c.clipPath(clip)
        watch.picture(card, w.toInt(), h.toInt())?.let { b -> paint.alpha = 255; c.drawBitmap(b, null, rect, paint) }
        if (card.progress > 0f) {
            paint.color = Color.argb(115, 0, 0, 0); c.drawRect(x, y + h - 6 * u, x + w, y + h, paint)
            paint.color = Color.rgb(245, 245, 247); c.drawRect(x, y + h - 6 * u, x + w * card.progress, y + h, paint)
        }
        c.restore()
        store.items.firstOrNull { it.pkg == card.pkg }?.let { app ->   // the app's badge, bottom left
            icons.square(app, (48 * u).toInt())?.let { b ->
                rect.set(x + 16 * u, y + h - 20 * u - 48 * u, x + 64 * u, y + h - 20 * u)
                clip.reset(); clip.addRoundRect(rect, 12 * u, 12 * u, Path.Direction.CW)
                c.save(); c.clipPath(clip); c.drawBitmap(b, null, rect, paint); c.restore()
            }
        }
        if (focused) { paint.style = Paint.Style.STROKE; paint.strokeWidth = 2 * u; paint.color = inkA(46); rect.set(x, y, x + w, y + h); c.drawRoundRect(rect, r, r, paint); paint.style = Paint.Style.FILL }
        c.restore()
    }

    private fun classicApp(c: Canvas, u: Float, index: Int, x: Float, y: Float, w: Float, h: Float, focused: Boolean, dim: Boolean) {
        val app = if (index < store.line) store.items[index] else null
        val r = 10 * u
        val glassy = looks.glassIcons && app != null && (!focused || looks.glassFocusStay)
        c.save()
        if (focused) { c.translate(x + w / 2, y + h / 2); c.scale(1.16f, 1.16f); c.translate(-(x + w / 2), -(y + h / 2)); looks.sway(c, x + w / 2, y + h / 2) }
        rect.set(x, y, x + w, y + h)
        paint.style = Paint.Style.FILL
        softShadow(c, rect, r, if (focused) 28 * u else 8 * u, if (focused) 40 * u else 14 * u, if (focused) 190 else 100)
        clip.reset(); clip.addRoundRect(rect, r, r, Path.Direction.CW)
        c.save(); c.clipPath(clip)
        paint.alpha = if (dim) 120 else 255
        if (app != null) {
            if (glassy) {
                gstyle.iconTile(c, x, y, x + w, y + h, r, glassBmp, height / 1080f, focused)
                glassGlyph(c, u, app, x, y, w, h, focused, true, r)
            } else {
            paint.color = Color.rgb(28, 28, 33); c.drawRect(rect, paint)
            icons.get(app, w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1))?.let { b -> paint.alpha = if (dim) 120 else 255; c.drawBitmap(b, null, rect, paint) }
                ?: run { text.typeface = Fonts.semibold(context); text.textSize = 24 * u; text.textAlign = Paint.Align.CENTER
                         val l = TextUtils.ellipsize(app.label, text, w - 20 * u, TextUtils.TruncateAt.END); c.drawText(l, 0, l.length, x + w / 2, y + h / 2 + 8 * u, text) }
            }
        } else if (looks.glass != Looks.Glass.OFF) {   // Glass panels on: Settings and More apps turn to glass too
            gstyle.panel(c, x, y, x + w, y + h, r, glassBmp, height / 1080f)
            if (index == store.line) { text.typeface = Fonts.semibold(context); text.textSize = 24 * u; text.textAlign = Paint.Align.CENTER; c.drawText("Settings", x + w / 2, y + h / 2 + 8 * u, text) }
            else glyph(c, u, x + w / 2, y + h / 2, 112 * u * 0.7f, false)
        } else {   // the house's own tiles: a dark gradient, the name or three dots
            paint.shader = LinearGradient(x, y, x + w * 0.4f, y + h, Color.rgb(44, 44, 51), Color.rgb(26, 26, 31), Shader.TileMode.CLAMP)
            c.drawRect(rect, paint); paint.shader = null
            if (index == smartIdx() || index == gearIdx()) { text.typeface = Fonts.semibold(context); text.textSize = 24 * u; text.textAlign = Paint.Align.CENTER; c.drawText(if (index == smartIdx()) PS.SMART_HOME else "Settings", x + w / 2, y + h / 2 + 8 * u, text) }
            else glyph(c, u, x + w / 2, y + h / 2, 112 * u * 0.7f, false)
        }
        if (focused) { paint.alpha = 255; paint.style = Paint.Style.FILL; paint.color = Color.argb(46, 255, 255, 255)
            val sx = x - w * 0.6f
            val gc = looks.glintCentre()
            paint.shader = LinearGradient(x + (gc - 0.3f) * w, y, x + (gc + 0.3f) * w, y + h, intArrayOf(0, Color.argb(51, 255, 255, 255), 0), floatArrayOf(0.35f, 0.5f, 0.65f), Shader.TileMode.CLAMP)
            c.drawRect(rect, paint); paint.shader = null }
        c.restore()
        if (looks.glass != Looks.Glass.OFF && !focused) {   // Glass panels: every resting tile gets a light edge
            paint.style = Paint.Style.STROKE; paint.strokeWidth = u; paint.color = inkA(61)
            rect.set(x - u / 2, y - u / 2, x + w + u / 2, y + h + u / 2); c.drawRoundRect(rect, r, r, paint)
            paint.style = Paint.Style.FILL; rect.set(x, y, x + w, y + h)
        }
        if (app != null && store.isNew(app.pkg) || (app == null && index == gearIdx() && Updater.ready)) { paint.alpha = 255; paint.color = Color.parseColor("#3D8BFF"); c.drawCircle(x + w - 12 * u, y + 12 * u, 6 * u, paint) }
        if (focused) {
            text.typeface = Fonts.semibold(context); text.textSize = 20 * u; text.textAlign = Paint.Align.CENTER
            c.drawText(dockTitle(index), x + w / 2, y + h + 10 * u + 17 * u, text)
        }
        paint.alpha = 255
        c.restore()
    }

    private fun dockTitle(i: Int): String = when {
        i < store.line -> store.items[i].label
        i == smartIdx() -> PS.SMART_HOME
        i == gearIdx() -> "Settings"
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
            paint.color = ink; paint.alpha = 255
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
        val names = menuNames()
        val h = 110 * u + names.size * rowH
        val l = (width - w) / 2f
        val t = (height - h) / 2f
        rect.set(l, t, l + w, t + h)
        paint.color = Color.parseColor("#17241F"); paint.alpha = 255
        c.drawRoundRect(rect, 18 * u, 18 * u, paint)
        text.textAlign = Paint.Align.LEFT
        text.textSize = 32 * u
        val title = TextUtils.ellipsize(store.items.getOrNull(menuIndex)?.label.orEmpty(), text, w - 80 * u, TextUtils.TruncateAt.END)
        c.drawText(title, 0, title.length, l + 40 * u, t + 70 * u, text)
        for (i in names.indices) {
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
        val CARD_MENU = listOf("Remove from Keep watching", "Cancel")
    }
}
