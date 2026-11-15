package com.lukemonaghan.tvhome

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.WindowManager

/**
 * What the system-facing quick buttons do (screen off, screensaver, Bluetooth, network, TV settings), ported
 * from couch-tv's launcher. No bridge: everything is Android's own Settings screens or the window's brightness.
 */
class QuickSystem(private val ctx: Context, private val prefs: SharedPreferences) {
    private val main = Handler(Looper.getMainLooper())

    /** Whether quick button [kind] has anything to do on this TV (the screensaver needs a screensaver and "modify system settings"). */
    fun available(kind: String): Boolean = when (kind) {
        "screensaver" -> screensaverReadyCached()
        else -> true
    }

    // ------------------------------------------------------------------ screen off

    /** Audio-only: the picture goes black by dropping this window's backlight to 0, while sound and background media play on. */
    fun screenOff(off: Boolean) {
        val w = (ctx as? Activity)?.window ?: return
        runCatching {
            val lp = w.attributes
            lp.screenBrightness = if (off) 0.0f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            w.attributes = lp
        }.onFailure { Log.w("tvhome", "screen off failed: $it") }
    }

    // ------------------------------------------------------------------ settings screens

    /** The TV's Bluetooth or Wi-Fi screen, falling back to all of Settings. */
    fun openSettingsScreen(which: String): Boolean {
        val actions = when (which) {
            "bluetooth" -> listOf(Settings.ACTION_BLUETOOTH_SETTINGS)
            "network" -> listOf(Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS)
            else -> emptyList()
        } + Settings.ACTION_SETTINGS
        // Fire OS sends every Settings action through Amazon's home, which the Home button watch puts us back over:
        // with the watch on, open Fire OS's own Settings screen by name.
        val fire = if (FireWatch.connected) FireWatch.amazonSettings(ctx.packageManager) else null
        val i = fire ?: actions.asSequence().map { Intent(it) }.firstOrNull { ctx.packageManager.resolveActivity(it, 0) != null }
        return i?.let { startSettings(it) } ?: false
    }

    fun openTvSettings(): Boolean = startSettings(FireWatch.amazonSettings(ctx.packageManager) ?: Intent(Settings.ACTION_SETTINGS))

    private fun startSettings(i: Intent): Boolean {
        FireWatch.allowSettingsNow()   // so the Home button watch leaves Settings alone
        return runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)); true }
            .getOrElse { Log.w("tvhome", "could not open settings: $it"); false }
    }

    // ------------------------------------------------------------------ screensaver

    private var readyAt = 0L
    private var ready = false

    /** Cached for a few seconds: the home asks on every draw, and each answer reads Settings. */
    private fun screensaverReadyCached(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - readyAt > 5_000) { ready = screensaverReady(); readyAt = now }
        return ready
    }

    /** The button can work: "modify system settings" was granted, and a screensaver is on whose app is installed and enabled. */
    fun screensaverReady(): Boolean {
        if (!Settings.System.canWrite(ctx)) return false
        return runCatching {
            val cr = ctx.contentResolver
            if (Settings.Secure.getInt(cr, "screensaver_enabled", 1) == 0) return@runCatching false
            val dream = Settings.Secure.getString(cr, "screensaver_components")
                ?.split(",")?.firstOrNull()?.let { ComponentName.unflattenFromString(it) } ?: return@runCatching false
            ctx.packageManager.getApplicationInfo(dream.packageName, 0).enabled
        }.getOrDefault(false)
    }

    /** Starts the TV's screensaver, or opens its settings when it cannot. False when this TV has neither. */
    fun startScreensaver(): Boolean = startAmbientNow() || openAmbientSettings()

    /** Google TV's Ambient mode settings: where the source and the Google Photos albums are picked. */
    fun openAmbientSettings(): Boolean {
        val i = Intent(AMBIENT_SETTINGS)
        if (ctx.packageManager.resolveActivity(i, 0) == null) return false
        return runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }

    private var idleShots = 0
    private val idleTick = object : Runnable {
        override fun run() {
            if (prefs.getInt("timeout_saved", -1) <= 0) return
            runCatching {
                if (Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT) != AMBIENT_DELAY_MS)
                    Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, AMBIENT_DELAY_MS)
            }
            resetIdleTimer()
            if (++idleShots < 40) main.postDelayed(this, 600)
        }
    }
    private val deadline = Runnable { restore() }

    /**
     * Only the system may start a dream, so the screen timeout is cut to a few seconds and the idle timer reset
     * again and again: the TV then naps by itself and its own screensaver comes up. The timeout goes back when this
     * activity resumes (the screensaver ending, MainActivity.restoreTimeout) or after 25 seconds, whichever is first.
     * The timeout kept is the one from before the first press: a second press while the TV is already napping must
     * never save the five seconds, or the TV would keep them for good.
     */
    private fun startAmbientNow(): Boolean {
        if (!Settings.System.canWrite(ctx)) return false
        val now = runCatching { Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT) }.getOrNull() ?: return false
        if (!screensaverReadyCached()) return false
        if (prefs.getInt("timeout_saved", -1) <= 0) prefs.edit().putInt("timeout_saved", if (now > SHORT_MS) now else RESTORE_MS).apply()
        return runCatching {
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, AMBIENT_DELAY_MS)
            pressedAt = SystemClock.elapsedRealtime()
            main.removeCallbacks(idleTick); idleShots = 0; main.postDelayed(idleTick, 700)
            main.removeCallbacks(deadline); main.postDelayed(deadline, WINDOW_MS)
            true
        }.getOrElse { Log.w("tvhome", "screensaver refused: $it"); restore(); false }
    }

    /** Puts back the screen timeout the TV had before the button shortened it. */
    private fun restore() {
        val saved = prefs.getInt("timeout_saved", -1)
        main.removeCallbacks(idleTick); main.removeCallbacks(deadline)
        if (saved <= 0) return
        prefs.edit().remove("timeout_saved").apply()
        runCatching { Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, if (saved > SHORT_MS) saved else RESTORE_MS) }
    }

    /** The power manager only re-reads the timeout when user activity comes in; userActivity() is hidden, so it is called by reflection. */
    private fun resetIdleTimer() {
        val pm = ctx.getSystemService(android.os.PowerManager::class.java) ?: return
        runCatching {
            pm.javaClass.getMethod("userActivity", java.lang.Long.TYPE, java.lang.Boolean.TYPE)
                .invoke(pm, SystemClock.uptimeMillis(), false)
        }
    }

    companion object {
        private const val AMBIENT_SETTINGS = "dreamx.two.panel.SETTINGS"
        private const val AMBIENT_DELAY_MS = 5000
        private const val SHORT_MS = 15_000
        private const val RESTORE_MS = 600_000
        private const val WINDOW_MS = 25_000L
        private const val EARLY_MS = 4_000L

        @Volatile private var pressedAt = 0L

        /** True for the first moments after the screensaver button: a resume that early is a TV bringing us back, not the screensaver ending. */
        fun justPressed(): Boolean = pressedAt > 0 && SystemClock.elapsedRealtime() - pressedAt < EARLY_MS
    }
}
