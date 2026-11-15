package com.lukemonaghan.tvhome

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

/**
 * What the idle, sound and Now playing code needs from the Activity, kept apart from [HomeActions] so that
 * interface stays small. MainActivity implements it; the home asks with `act as? IdleHost`.
 */
interface IdleHost {
    /** True darkens the screen as far as Android allows (brightness 0) while sound and the TV carry on; false puts it back. */
    fun setScreenDark(dark: Boolean)
    /** When the sleep timer will fire (uptime millis from System.currentTimeMillis), or 0 when none is running. */
    fun sleepEndsAt(): Long
}

/**
 * Lets the TV go to sleep the way the sleep timer does: the screen-off time is shortened to 5 seconds, then put
 * back. Needs "Modify system settings" (WRITE_SETTINGS, see tools/install.sh). The saved time goes in the same
 * "timeout_saved" key MainActivity restores on resume, so a TV left with the short time is always repaired.
 */
object TvSleep {
    private val main = Handler(Looper.getMainLooper())

    fun now(ctx: Context): Boolean {
        if (!Settings.System.canWrite(ctx)) { Log.w("tvhome", "TV off: no WRITE_SETTINGS, cannot sleep the TV"); return false }
        val p = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)
        val cr = ctx.contentResolver
        val old = runCatching { Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT) }.getOrDefault(0)
        if (old > 10_000) p.edit().putInt("timeout_saved", old).apply()
        runCatching { Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 5_000) }
        val app = ctx.applicationContext
        main.postDelayed({
            val saved = p.getInt("timeout_saved", -1)
            if (saved > 10_000 && Settings.System.canWrite(app)) {
                runCatching { Settings.System.putInt(app.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, saved) }
                p.edit().remove("timeout_saved").apply()
            }
        }, 40_000L)
        return true
    }
}
