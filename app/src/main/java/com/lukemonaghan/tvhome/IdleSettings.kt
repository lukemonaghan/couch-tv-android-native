package com.lukemonaghan.tvhome

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings

/**
 * The "Idle and sound" page of Home settings: Smart Idle, hiding rows, the soundscape, the navigation sound,
 * ambient weather and Now playing. Rows only; SettingsScreen draws them. Everything is a pref in "home":
 *   idle_on, idle_home (minutes), idle_action (ambient_clock|screen_off|tv_off), idle_paused (minutes),
 *   idle_q_on, idle_q_start, idle_q_end, idle_q_min, idle_rows (seconds),
 *   sound_type, sound_vol, sound_fade, nav_sound (system|soft|off),
 *   wx_fx, wx_sun, np_on, np_ambient.
 */
object IdleSettings {
    class Item(val title: String, val note: String = "", val value: String = "", val check: Boolean? = null,
               val header: Boolean = false, val press: (() -> Unit)? = null)

    private val HOME_STEPS = listOf(0, 5, 10, 15, 30, 60)
    private val PAUSED_STEPS = listOf(0, 10, 15, 20, 30, 45, 60)
    private val ROWS_STEPS = listOf(0, 5, 10, 15, 30, 60)
    private val ACTIONS = listOf("ambient_clock", "screen_off", "tv_off")
    private val ACTION_NAMES = mapOf("ambient_clock" to "OLED Ambient Clock", "screen_off" to "Screen off", "tv_off" to "Turn TV off")
    private val Q_START = listOf("20:00", "21:00", "22:00", "23:00", "00:00")
    private val Q_END = listOf("04:00", "05:00", "06:00", "07:00", "08:00")
    private val Q_MIN = listOf(5, 10, 15, 20, 30)
    private val VOLUMES = listOf(0.2f, 0.4f, 0.6f, 0.8f, 1.0f)
    private val SOUND_NAMES = mapOf("off" to "Off", "rain" to "Gentle rain", "fire" to "Fireplace", "waves" to "Ocean waves", "brown" to "Brown noise")
    private val NAV = listOf("system", "soft", "off")
    private val NAV_NAMES = mapOf("system" to "The TV's own", "soft" to "Soft", "off" to "Off")

    private fun <T> next(list: List<T>, cur: T): T = list[((list.indexOf(cur)).let { if (it < 0) -1 else it } + 1) % list.size]
    private fun mins(n: Int) = if (n == 0) "Off" else "$n min"

    fun rows(ctx: Context, p: SharedPreferences): List<Item> {
        fun flip(k: String, def: Boolean) { p.edit().putBoolean(k, !p.getBoolean(k, def)).apply() }
        val idle = IdleLayer.current
        val quietOn = p.getBoolean("idle_q_on", false)
        val out = ArrayList<Item>()

        out += Item("SMART IDLE & OLED PROTECTION", header = true)
        out += Item("Smart Idle", "Protect your screen and turn off idle or paused playback automatically", check = p.getBoolean("idle_on", false)) { flip("idle_on", false) }
        out += Item("Home screen idle after", value = mins(p.getInt("idle_home", 15))) { p.edit().putInt("idle_home", next(HOME_STEPS, p.getInt("idle_home", 15))).apply() }
        val action = p.getString("idle_action", "ambient_clock") ?: "ambient_clock"
        out += Item("When home screen is idle", value = ACTION_NAMES[action] ?: action) { p.edit().putString("idle_action", next(ACTIONS, action)).apply() }
        out += Item("Turn TV off when paused for", "Needs Now playing access. Only for apps with a media session, so a console on HDMI is never turned off",
            value = mins(p.getInt("idle_paused", 30))) { p.edit().putInt("idle_paused", next(PAUSED_STEPS, p.getInt("idle_paused", 30))).apply() }
        out += Item("Quiet hours", "A shorter wait and a dimmer ambient clock, 11 pm to 6 am unless changed here", check = quietOn) { flip("idle_q_on", false) }
        if (quietOn) {
            out += Item("Quiet hours start", value = p.getString("idle_q_start", "23:00") ?: "23:00") { p.edit().putString("idle_q_start", next(Q_START, p.getString("idle_q_start", "23:00") ?: "23:00")).apply() }
            out += Item("Quiet hours end", value = p.getString("idle_q_end", "06:00") ?: "06:00") { p.edit().putString("idle_q_end", next(Q_END, p.getString("idle_q_end", "06:00") ?: "06:00")).apply() }
            out += Item("Quiet hours wait", value = mins(p.getInt("idle_q_min", 15))) { p.edit().putInt("idle_q_min", next(Q_MIN, p.getInt("idle_q_min", 15))).apply() }
        }
        out += Item("Hide rows when idle", "After this long without a key, hide the apps and Keep watching. The clock, weather and scene stay; any key brings them back",
            value = p.getInt("idle_rows", 0).let { if (it == 0) "Off" else "$it seconds" }) { p.edit().putInt("idle_rows", next(ROWS_STEPS, p.getInt("idle_rows", 0))).apply() }

        out += Item("SOUNDSCAPE", header = true)
        val cur = idle?.soundType() ?: "off"
        out += Item("Soundscape", "Plays only while the home is on screen, never under a show", value = SOUND_NAMES[cur] ?: cur) { idle?.cycleSound() }
        val vol = p.getFloat("sound_vol", 0.5f)
        out += Item("Soundscape volume", value = "${Math.round(vol * 100)}%") {
            val i = VOLUMES.indexOfFirst { Math.abs(it - vol) < 0.05f }
            val v = if (i < 0) 0.6f else VOLUMES[(i + 1) % VOLUMES.size]
            idle?.setSoundVolume(v) ?: p.edit().putFloat("sound_vol", v).apply()
        }
        out += Item("Fade with the sleep timer", "Quieter over the timer's last 15 minutes, off when it runs out", check = p.getBoolean("sound_fade", true)) { flip("sound_fade", true) }

        out += Item("SOUND", header = true)
        val nav = p.getString("nav_sound", "system") ?: "system"
        out += Item("Navigation sound", "The sound on every arrow and OK", value = NAV_NAMES[nav] ?: nav) { p.edit().putString("nav_sound", next(NAV, nav)).apply() }

        out += Item("WEATHER EFFECTS", header = true)
        out += Item("Ambient weather effects", "Subtle rain, snow and fog over the scenes. Redraws the home often: leave off on a slow TV", check = p.getBoolean("wx_fx", false)) { flip("wx_fx", false) }
        out += Item("Sunlight colour grading", "Warm dawn and golden sunset tones matched to the sun", check = p.getBoolean("wx_sun", true)) { flip("wx_sun", true) }

        out += Item("NOW PLAYING", header = true)
        out += Item("Now playing card", "The song any app is playing, with previous, play and next", check = p.getBoolean("np_on", true)) { flip("np_on", true) }
        out += Item("Ambient music screen", "A full screen view of the cover when a new song starts", check = p.getBoolean("np_ambient", false)) { flip("np_ambient", false) }
        val granted = MediaListener.enabled(ctx)
        out += Item("Music access", if (granted) "Home can see what is playing" else "Needed for Now playing. OK opens Android's Notification access screen, or grant it from a computer (see MediaListener.kt)",
            value = if (granted) "On" else "Off") {
            if (!granted) runCatching { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        return out
    }
}
