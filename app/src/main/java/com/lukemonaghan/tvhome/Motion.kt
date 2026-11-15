package com.lukemonaghan.tvhome

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings

/**
 * How fast the home's own movement is. One scale for every animation (focus easing, the fade between screens, the scene
 * crossfade, swaying tiles, weather): pref "motion" is "android" (follow Android's Animator duration scale from Developer
 * options, the default), "slow", "normal", "fast" or "off" (no motion at all: everything jumps to its end at once).
 * Android's own scale of 0 also means no motion.
 */
object Motion {
    private var appCtx: Context? = null
    private var cached = 1f
    private var at = 0L

    fun init(ctx: Context) { appCtx = ctx.applicationContext }

    /** 1 is normal speed, 2 twice as slow, 0 no motion. */
    fun scale(prefs: SharedPreferences): Float {
        when (prefs.getString("motion", "android")) {
            "off" -> return 0f
            "slow" -> return 1.6f
            "normal" -> return 1f
            "fast" -> return 0.55f
        }
        val now = SystemClock.uptimeMillis()
        if (now - at > 2000L) {
            at = now
            cached = runCatching { Settings.Global.getFloat(appCtx!!.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f)
        }
        return cached.coerceIn(0f, 10f)
    }

    /** [base] milliseconds at normal speed, scaled; 0 when there is no motion. */
    fun ms(prefs: SharedPreferences, base: Int): Long = (base * scale(prefs)).toLong()

    fun off(prefs: SharedPreferences) = scale(prefs) <= 0f
}
