package com.lukemonaghan.tvhome

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService

/**
 * Why this service exists: Android only lets an app read what other apps are playing (the song, the artist, the
 * play and pause buttons) through the media sessions, and it only hands those to an app that is a notification
 * listener. The service does nothing with notifications. Being listed is the whole point, so [NowPlaying] can ask
 * the MediaSessionManager for the sessions.
 *
 * How to give the access (once, nothing else changes on the TV):
 *  - From a computer, same as the other grants in tools/install.sh:
 *      adb shell cmd notification allow_listener com.lukemonaghan.tvhome/com.lukemonaghan.tvhome.MediaListener
 *    (on Android 8 and older: adb shell settings put secure enabled_notification_listeners
 *    com.lukemonaghan.tvhome/com.lukemonaghan.tvhome.MediaListener)
 *  - Or with no computer: Settings, Apps, Special app access, Notification access, switch Home on (not every TV
 *    has that screen).
 *  - Or automatically: once a computer has granted WRITE_SECURE_SETTINGS (install.sh does), [ensure] adds it itself.
 * Without the access the home simply shows no Now playing card; nothing else is affected. The undo is
 *  adb shell cmd notification disallow_listener com.lukemonaghan.tvhome/com.lukemonaghan.tvhome.MediaListener
 *
 * While it is connected it also keeps the "Turn TV off when paused for" rule of Smart Idle: a song or show left
 * paused for that long, with nothing else playing, lets the TV sleep the way the sleep timer does.
 */
class MediaListener : NotificationListenerService() {
    private val main = Handler(Looper.getMainLooper())
    private var pausedSince = 0L

    private val watch = object : Runnable {
        override fun run() {
            runCatching { checkPaused() }
            main.postDelayed(this, 30_000L)
        }
    }

    override fun onListenerConnected() { main.removeCallbacks(watch); main.postDelayed(watch, 30_000L) }
    override fun onListenerDisconnected() { main.removeCallbacks(watch); pausedSince = 0L }

    private fun checkPaused() {
        val p = getSharedPreferences("home", MODE_PRIVATE)
        val mins = p.getInt("idle_paused", 30)
        if (!p.getBoolean("idle_on", false) || mins <= 0) { pausedSince = 0L; return }
        val sm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val states = sm.getActiveSessions(ComponentName(this, MediaListener::class.java)).mapNotNull { it.playbackState?.state }
        val playing = states.any { it == PlaybackState.STATE_PLAYING || it == PlaybackState.STATE_BUFFERING }
        val paused = states.any { it == PlaybackState.STATE_PAUSED }
        val now = SystemClock.uptimeMillis()
        if (playing || !paused) { pausedSince = 0L; return }
        if (pausedSince == 0L) pausedSince = now
        if (now - pausedSince >= mins * 60_000L) { pausedSince = 0L; TvSleep.now(applicationContext) }
    }

    companion object {
        fun component(ctx: Context) = ComponentName(ctx, MediaListener::class.java)

        /** True when Android has this service on its list of notification listeners. */
        fun enabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            return flat.split(':').any { ComponentName.unflattenFromString(it) == component(ctx) }
        }

        /** Lists this service itself, but only where a computer already granted WRITE_SECURE_SETTINGS. Harmless otherwise. */
        fun ensure(ctx: Context) {
            if (enabled(ctx)) return
            if (ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) return
            runCatching {
                val cur = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
                val me = component(ctx).flattenToString()
                Settings.Secure.putString(ctx.contentResolver, "enabled_notification_listeners", if (cur.isNullOrEmpty()) me else "$cur:$me")
            }
        }
    }
}
