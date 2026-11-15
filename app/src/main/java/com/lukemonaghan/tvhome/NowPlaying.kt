package com.lukemonaghan.tvhome

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.KeyEvent
import kotlin.math.sin

/**
 * Now playing (couch-tv #31): the song any app is playing behind the home, as a glass card with the cover, the app,
 * the title and artist, and previous, play or pause and next; and, if switched on, the full screen music view (a big
 * cover, a colour glow taken from it, a progress bar, the same buttons). Read from the TV's media sessions, so it needs
 * the notification access described on [MediaListener]; without it nothing shows and nothing breaks. Polled every
 * 3 seconds while the home is on screen, not at all otherwise. Covers are scaled to 480 px as they arrive and only
 * the current one is kept.
 *
 * Prefs ("home"): np_on (default on), np_ambient (the full screen view, default off).
 */
class NowPlaying(
    private val ctx: Context,
    private val prefs: SharedPreferences,
    private val launch: (String) -> Unit,
    private val changed: (autoOpenAmbient: Boolean) -> Unit,
) {
    class Track(val pkg: String, val app: String, val title: String, val artist: String, val album: String,
                val playing: Boolean, val position: Long, val duration: Long, val controller: MediaController)

    private val main = Handler(Looper.getMainLooper())
    private val manager: MediaSessionManager? = runCatching { ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager }.getOrNull()
    var track: Track? = null; private set
    private var art: Bitmap? = null
    private var bg: Bitmap? = null
    var fa = 0f   // how focused the card is, 0 to 1 (the home eases it)
    private val viz = AudioViz(ctx)
    private var lyr: Lyrics.Result? = null
    private var lyrDone = false   // the look-up came back (with or without lyrics)
    val lyricsOn get() = prefs.getBoolean("np_lyrics", false)
    private var artKey = ""
    private var glow1 = Color.rgb(88, 66, 160); private var glow2 = Color.rgb(40, 90, 160)
    private var lastTrackKey = ""
    private var pausedAt = 0L                 // a paused song stays on the card for ten minutes
    private var started = false
    private var stoppedKey: String? = null
    private var stopAt = 0L

    var focused = false                       // the remote is on the card
    private var btn = 1
    var ambientOpen = false; private set
    private var ambientDismissed = false
    private var ambBtn = 1

    // ---- the MilkDrop visualiser behind the music view (Visualiser.kt)
    var vizLive = false                       // the visualiser has drawn a frame: the view leaves its own backdrop out
    var vizName = ""                          // the preset playing now
    var onPreset: ((Int) -> Unit)? = null     // +1 / -1: the next / previous preset (the home passes it to the visualiser)
    val audio get() = viz
    /** The music view can run the visualiser: the engine is in the app, presets are installed, and it is not switched off in Settings. */
    fun vizWanted() = ProjectM.available && prefs.getBoolean("np_viz", true) && PresetStore.ready(ctx)
    private var hidden = false                // overlays faded out, only the visualiser shows
    private var ovFrom = 1f; private var ovTo = 1f; private var ovAt = 0L
    private var lastKeyAt = 0L
    private var nameAt = 0L                   // when the preset name last changed (it shows for a moment when the overlays are hidden)
    val animating get() = vizLive && SystemClock.uptimeMillis() - maxOf(ovAt, nameAt, brightAt, trackAt) < 3200L
    private fun overlay(): Float {
        val f = ((SystemClock.uptimeMillis() - ovAt) / 400f).coerceIn(0f, 1f)
        return ovFrom + (ovTo - ovFrom) * f
    }
    private var trackAt = 0L                  // when the song last changed (with the buttons hidden its name shows for a moment)
    private var brightAt = 0L                 // when the brightness was last changed with the keys (the slider shows for a moment)
    private fun bright() = prefs.getInt("viz_bright", 100).coerceIn(20, 100)
    private fun adjustBright(d: Int) { prefs.edit().putInt("viz_bright", (bright() + d).coerceIn(20, 100)).apply(); brightAt = SystemClock.uptimeMillis(); changed(false) }
    private fun resetOv() { hidden = false; ovFrom = 1f; ovTo = 1f; ovAt = 0L; lastKeyAt = SystemClock.uptimeMillis() }
    private fun setHidden(h: Boolean) { if (hidden == h) return; ovFrom = overlay(); hidden = h; ovTo = if (h) 0f else 1f; ovAt = SystemClock.uptimeMillis(); changed(false) }
    fun presetName(n: String) { vizName = n; nameAt = SystemClock.uptimeMillis(); changed(false) }
    /** Back in the music view: brings hidden overlays back first. True when that was all it did. */
    fun backPressed(): Boolean { if (vizLive && hidden) { setHidden(false); lastKeyAt = SystemClock.uptimeMillis(); return true }; return false }
    private fun items(): List<String> {
        val l = arrayListOf("prev", "play", "next", "stop")
        if (vizLive) { l.add("preset"); l.add("eye") }
        l.add("app")
        return l
    }

    val on get() = prefs.getBoolean("np_on", true)
    val ambientOn get() = prefs.getBoolean("np_ambient", true)
    val visible get() = on && track != null
    /** Whether the remote can land on the card: it shows and it has at least one button. */
    fun reach() = visible

    // ------------------------------------------------------------------ reading the sessions

    private val poll = object : Runnable {
        override fun run() { refresh(); if (started) main.postDelayed(this, 3000L) }
    }

    fun start() {
        if (started) return
        started = true
        MediaListener.ensure(ctx)
        main.post(poll)
    }

    fun stop() { viz.stop(); started = false; main.removeCallbacks(poll); track = null; ambientOpen = false; focused = false }

    private fun refresh() {
        if (!on) { if (track != null) { track = null; ambientOpen = false; changed(false) }; return }
        val sessions = runCatching { manager?.getActiveSessions(MediaListener.component(ctx)) }.getOrNull()   // SecurityException: no access
        val pick = sessions?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING || it.playbackState?.state == PlaybackState.STATE_BUFFERING }
            ?: sessions?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED && it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) != null }
        val md = pick?.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
        if (pick == null || title.isNullOrBlank()) {
            if (track != null) { track = null; ambientOpen = false; lastTrackKey = ""; art = null; bg = null; lyr = null; artKey = ""; changed(false) }
            return
        }
        val ps = pick.playbackState
        val playing = ps?.state == PlaybackState.STATE_PLAYING || ps?.state == PlaybackState.STATE_BUFFERING
        if (playing) pausedAt = 0L else if (pausedAt == 0L) pausedAt = SystemClock.uptimeMillis()
        if (!playing && SystemClock.uptimeMillis() - pausedAt > 10 * 60_000L) { if (track != null) { track = null; ambientOpen = false; changed(false) }; return }
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val album = md.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val pm = ctx.packageManager
        val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pick.packageName, 0)).toString() }.getOrDefault(pick.packageName)
        var pos = ps?.position ?: 0L
        if (playing && ps != null) pos += ((SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime) * ps.playbackSpeed).toLong()
        val dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val key = "$title\n$artist"
        if (stoppedKey != null) {   // Stop was pressed: the card stays away until something else plays (or this song is started again)
            if (key == stoppedKey && (!playing || SystemClock.uptimeMillis() - stopAt < 5000L)) {
                if (track != null) { track = null; ambientOpen = false; changed(false) }
                return
            }
            stoppedKey = null
        }
        if (key != artKey) loadArt(md, key)
        val newTrack = key != lastTrackKey
        track = Track(pick.packageName, app, title, artist, album, playing, if (pos < 0) 0 else pos, dur, pick)
        readAt = SystemClock.uptimeMillis()
        if (newTrack) {
            lastTrackKey = key; ambientDismissed = false; lyr = null; lyrDone = false; trackAt = SystemClock.uptimeMillis()
            if (lyricsOn) Lyrics.fetch(artist, title, album, (dur / 1000).toInt()) { l -> main.post { if (lastTrackKey == key) { lyr = l; lyrDone = true; changed(false) } } }
        }
        val auto = newTrack && ambientOn && !ambientDismissed
        if (auto) { resetOv(); ambientOpen = true; ambBtn = 1 }
        changed(auto)
    }

    private fun loadArt(md: MediaMetadata, key: String) {
        artKey = key
        val raw = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        art = raw?.let {
            val s = 480f / maxOf(it.width, it.height)
            if (s < 1f) Bitmap.createScaledBitmap(it, (it.width * s).toInt().coerceAtLeast(1), (it.height * s).toInt().coerceAtLeast(1), true) else it
        }
        bg = art?.let { ArtBlur.soft(it) }
        art?.let { palette(it) } ?: run { glow1 = Color.rgb(88, 66, 160); glow2 = Color.rgb(40, 90, 160) }
    }

    /** Two glow colours from the cover: its most vivid pixel and its average, at 16 x 16. */
    private fun palette(b: Bitmap) {
        val s = Bitmap.createScaledBitmap(b, 16, 16, true)
        var r = 0; var g = 0; var bl = 0; var bestSat = -1f; var best = Color.rgb(88, 66, 160)
        val hsv = FloatArray(3)
        for (y in 0 until 16) for (x in 0 until 16) {
            val p = s.getPixel(x, y); r += Color.red(p); g += Color.green(p); bl += Color.blue(p)
            Color.colorToHSV(p, hsv)
            val sat = hsv[1] * hsv[2]
            if (sat > bestSat) { bestSat = sat; best = p }
        }
        if (s !== b) s.recycle()
        glow1 = best
        glow2 = Color.rgb(r / 256, g / 256, bl / 256)
    }

    // ------------------------------------------------------------------ the remote

    private fun buttons(): List<String> {
        val t = track ?: return emptyList()
        return emptyList()   // the card has no buttons: OK opens the music view, which has them
    }

    fun focus() { focused = true }
    fun blur() { focused = false }

    /** A key while the card (or the full screen view) has the remote. True when it was used here; false lets the home move off the card. */
    fun key(code: Int): Boolean {
        if (ambientOpen) {
            lastKeyAt = SystemClock.uptimeMillis()
            if (vizLive && hidden) {   // only the visualiser shows: left and right change the preset, anything else brings the buttons back
                when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> onPreset?.invoke(-1)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> onPreset?.invoke(1)
                    KeyEvent.KEYCODE_DPAD_UP -> adjustBright(10)      // up and down are a brightness slider while only the visualiser shows
                    KeyEvent.KEYCODE_DPAD_DOWN -> adjustBright(-10)
                    else -> setHidden(false)
                }
                return true
            }
            val n = items().size
            ambBtn = ambBtn.coerceIn(0, n - 1)
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (ambBtn > 0) ambBtn--
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (ambBtn < n - 1) ambBtn++
                KeyEvent.KEYCODE_DPAD_DOWN -> closeAmbient(true)
            }
            return true
        }
        val n = buttons().size
        return when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (btn > 0) btn--; true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (btn < n - 1) { btn++; true } else false
            else -> false
        }
    }

    /** The full screen view cannot show now (Home settings is open): forget it without remembering a dismissal. */
    fun abandonAmbient() { ambientOpen = false; viz.stop(); resetOv() }

    /** Opens the full screen music view if something is playing (Up from the top of the home). */
    fun openAmbient(): Boolean { if (!visible) return false; resetOv(); ambientOpen = true; ambientDismissed = false; ambBtn = 1; changed(false); return true }

    fun closeAmbient(dismiss: Boolean) { ambientOpen = false; viz.stop(); resetOv(); if (dismiss) ambientDismissed = true; focus() }

    fun press() {
        val t = track ?: return
        val tc = t.controller.transportControls
        if (ambientOpen) {
            lastKeyAt = SystemClock.uptimeMillis()
            if (vizLive && hidden) { setHidden(false); return }
            val list = items(); ambBtn = ambBtn.coerceIn(0, list.size - 1)
            when (list[ambBtn]) { "prev" -> tc.skipToPrevious(); "play" -> toggle(t); "next" -> tc.skipToNext()
                "preset" -> { onPreset?.invoke(1); return }
                "eye" -> { setHidden(true); return }
                "stop" -> { stoppedKey = "${t.title}\n${t.artist}"; stopAt = SystemClock.uptimeMillis(); tc.pause(); tc.stop()
                    // an app that ignores both still gets the remote's own Pause key
                    runCatching { for (a in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) t.controller.dispatchMediaButtonEvent(KeyEvent(a, KeyEvent.KEYCODE_MEDIA_PAUSE)) }
                    ambientOpen = false; viz.stop(); resetOv(); track = null; focused = false; changed(false); main.postDelayed({ refresh() }, 700L); return }
                "app" -> launch(t.pkg) }
            main.postDelayed({ refresh() }, 700L); return
        }
        resetOv(); ambientOpen = true; ambientDismissed = false; ambBtn = 1   // OK on the card opens the full screen music view
        changed(false)
    }

    private fun toggle(t: Track) {
        val tc = t.controller.transportControls
        if (t.playing) tc.pause() else tc.play()
        val now = SystemClock.uptimeMillis()
        track = Track(t.pkg, t.app, t.title, t.artist, t.album, !t.playing, if (t.playing) t.position + (now - readAt) else t.position, t.duration, t.controller)
        readAt = now
        changed(false)
    }

    // ------------------------------------------------------------------ drawing

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    /** Night: the first card of the Keep watching row (this much room is left for it). Classic and OLED: top right. */
    fun slotWidth(u: Float) = 0f   // the card no longer sits in the Keep watching row

    /**
     * The now playing card: the same size and look as a Keep watching card (520 x 141: the cover, the app, the title, the artist and a
     * progress bar), top left in Night and top centre in the other looks, clear of the clock, the rows and the quick buttons.
     * OK opens the full screen music view; the play, pause and skip buttons live there (and the remote's own media keys work as usual).
     */
    fun draw(c: Canvas, u: Float, night: Boolean, width: Int, glass: (Canvas, Float, Float, Float, Float, Float) -> Unit) {
        val t = track ?: return
        if (!on) return
        val w = 520 * u; val h = 141 * u
        val x = if (night) 80 * u else (width - w) / 2f
        val y = 44 * u
        val r = 30 * u * floatArrayOf(0.55f, 1f, 1.45f)[prefs.getInt("roundness", 1).coerceIn(0, 2)]
        val ringColor = Color.WHITE
        c.save()
        if (fa > 0.001f && !ambientOpen) { val k = 1f + 0.05f * fa; c.translate(x + w / 2, y + h / 2); c.scale(k, k); c.translate(-(x + w / 2), -(y + h / 2)) }
        if (fa > 0.001f && !ambientOpen) {
            paint.style = Paint.Style.FILL; paint.shader = null; paint.color = Color.argb((90 * fa).toInt(), 0, 0, 0)
            rect.set(x, y + 12 * u, x + w, y + h + 12 * u); c.drawRoundRect(rect, r, r, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 3 * u; paint.color = ringColor; paint.alpha = (255 * fa).toInt()
            rect.set(x - 1.5f * u, y - 1.5f * u, x + w + 1.5f * u, y + h + 1.5f * u); c.drawRoundRect(rect, r + 1.5f * u, r + 1.5f * u, paint)
            paint.style = Paint.Style.FILL
        }
        glass(c, x, y, x + w, y + h, r)
        if (prefs.getBoolean("cardArt", true)) bg?.let { b ->   // the cover, blurred, fills the card like it fills the music view
            rect.set(x, y, x + w, y + h); path.reset(); path.addRoundRect(rect, r, r, Path.Direction.CW)
            c.save(); c.clipPath(path); paint.alpha = 255; paint.style = Paint.Style.FILL
            ArtBlur.fill(c, b, rect, paint)
            paint.color = Color.argb(intArrayOf(70, 115, 165)[prefs.getInt("cardDark", 1).coerceIn(0, 2)], 0, 0, 0); c.drawRect(rect, paint); c.restore()
        }
        val artS = 95 * u; val pad = 16 * u
        art(c, art, x + pad, y + (h - artS) / 2, artS, 16 * u)
        val tl = x + pad + artS + 20 * u; val tr = x + w - pad
        val top = y + (h - 109 * u) / 2
        text.textAlign = Paint.Align.LEFT
        text.typeface = Fonts.medium(ctx); text.textSize = 19 * u; text.letterSpacing = 0.06f; text.color = Color.argb(148, 255, 255, 255)
        val app = TextUtils.ellipsize((if (t.playing) t.app else t.app + "  ·  PAUSED").uppercase(), text, tr - tl, TextUtils.TruncateAt.END)
        c.drawText(app, 0, app.length, tl, top + 18 * u, text)
        text.letterSpacing = 0f
        text.typeface = Fonts.semibold(ctx); text.textSize = 29 * u; text.color = Color.rgb(245, 245, 247)
        val title = TextUtils.ellipsize(t.title, text, tr - tl, TextUtils.TruncateAt.END)
        c.drawText(title, 0, title.length, tl, top + 52 * u, text)
        text.typeface = Fonts.regular(ctx); text.textSize = 21 * u; text.color = Color.argb(199, 255, 255, 255)
        val sub = TextUtils.ellipsize(t.artist.ifEmpty { t.album }, text, tr - tl, TextUtils.TruncateAt.END)
        c.drawText(sub, 0, sub.length, tl, top + 82 * u, text)
        if (t.duration > 0) {
            val pos = if (t.playing) t.position + (SystemClock.uptimeMillis() - readAt) else t.position
            val by = top + 104 * u
            paint.color = Color.argb(64, 255, 255, 255); rect.set(tl, by, tr, by + 5 * u); c.drawRoundRect(rect, 3 * u, 3 * u, paint)
            paint.color = Color.WHITE; rect.set(tl, by, tl + (tr - tl) * (pos.toFloat() / t.duration).coerceIn(0f, 1f), by + 5 * u); c.drawRoundRect(rect, 3 * u, 3 * u, paint)
        }
        text.color = Color.WHITE
        c.restore()
    }

    private fun art(c: Canvas, b: Bitmap?, x: Float, y: Float, s: Float, radius: Float) {
        rect.set(x, y, x + s, y + s)
        paint.style = Paint.Style.FILL; paint.shader = null; paint.color = Color.argb(26, 255, 255, 255)
        c.drawRoundRect(rect, radius, radius, paint)
        if (b != null) {
            path.reset(); path.addRoundRect(rect, radius, radius, Path.Direction.CW)
            c.save(); c.clipPath(path)
            // centre-crop, like object-fit: cover
            val side = minOf(b.width, b.height)
            val src = android.graphics.Rect((b.width - side) / 2, (b.height - side) / 2, (b.width + side) / 2, (b.height + side) / 2)
            paint.color = Color.WHITE; c.drawBitmap(b, src, rect, paint)
            c.restore()
        } else note(c, x + s / 2, y + s / 2, s * 0.34f)
    }

    private fun note(c: Canvas, cx: Float, cy: Float, size: Float) {
        paint.style = Paint.Style.STROKE; paint.strokeWidth = size * 0.1f; paint.color = Color.argb(204, 255, 255, 255)
        paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
        rect.set(cx - size * 0.5f, cy + size * 0.1f, cx - size * 0.1f, cy + size * 0.5f)
        c.drawOval(rect, paint)
        rect.set(cx + size * 0.1f, cy - size * 0.1f, cx + size * 0.5f, cy + size * 0.3f)
        c.drawOval(rect, paint)
        c.drawLine(cx - size * 0.1f, cy + size * 0.3f, cx - size * 0.1f, cy - size * 0.45f, paint)
        c.drawLine(cx + size * 0.5f, cy + size * 0.1f, cx + size * 0.5f, cy - size * 0.65f, paint)
        c.drawLine(cx - size * 0.1f, cy - size * 0.45f, cx + size * 0.5f, cy - size * 0.65f, paint)
        paint.style = Paint.Style.FILL; paint.strokeCap = Paint.Cap.BUTT
    }

    /** One round control: a faint circle, white when focused, with its glyph. */
    private fun round(c: Canvas, cx: Float, cy: Float, r: Float, focus: Boolean, kind: String, u: Float) {
        c.save()
        if (focus) { c.translate(cx, cy); c.scale(1.12f, 1.12f); c.translate(-cx, -cy) }
        paint.style = Paint.Style.FILL; paint.shader = null
        paint.color = if (focus) Color.WHITE else Color.argb(26, 255, 255, 255)
        c.drawCircle(cx, cy, r, paint)
        glyph(c, cx, cy, r * 0.55f, kind, if (focus) Color.rgb(17, 17, 17) else Color.WHITE)
        c.restore()
    }

    private fun glyph(c: Canvas, cx: Float, cy: Float, s: Float, kind: String, color: Int) {
        paint.style = Paint.Style.FILL; paint.color = color
        path.reset()
        when (kind) {
            "play" -> { path.moveTo(cx - s * 0.6f, cy - s); path.lineTo(cx + s, cy); path.lineTo(cx - s * 0.6f, cy + s); path.close() }
            "pause" -> { path.addRect(cx - s * 0.8f, cy - s, cx - s * 0.2f, cy + s, Path.Direction.CW); path.addRect(cx + s * 0.2f, cy - s, cx + s * 0.8f, cy + s, Path.Direction.CW) }
            "stop" -> path.addRect(cx - s * 0.8f, cy - s * 0.8f, cx + s * 0.8f, cy + s * 0.8f, Path.Direction.CW)
            "preset" -> {   // a four-point sparkle: a new look
                path.moveTo(cx, cy - s * 1.1f); path.quadTo(cx + s * 0.15f, cy - s * 0.15f, cx + s * 1.1f, cy); path.quadTo(cx + s * 0.15f, cy + s * 0.15f, cx, cy + s * 1.1f)
                path.quadTo(cx - s * 0.15f, cy + s * 0.15f, cx - s * 1.1f, cy); path.quadTo(cx - s * 0.15f, cy - s * 0.15f, cx, cy - s * 1.1f); path.close()
            }
            "eye" -> {   // hide the buttons: an eye
                paint.style = Paint.Style.STROKE; paint.strokeWidth = s * 0.22f; paint.strokeCap = Paint.Cap.ROUND
                path.moveTo(cx - s * 1.15f, cy); path.quadTo(cx, cy - s * 1.2f, cx + s * 1.15f, cy); path.quadTo(cx, cy + s * 1.2f, cx - s * 1.15f, cy)
                path.addCircle(cx, cy, s * 0.38f, Path.Direction.CW)
            }
            "prev" -> { path.addRect(cx - s, cy - s * 0.9f, cx - s * 0.7f, cy + s * 0.9f, Path.Direction.CW)
                path.moveTo(cx + s, cy - s * 0.9f); path.lineTo(cx - s * 0.6f, cy); path.lineTo(cx + s, cy + s * 0.9f); path.close() }
            "next" -> { path.addRect(cx + s * 0.7f, cy - s * 0.9f, cx + s, cy + s * 0.9f, Path.Direction.CW)
                path.moveTo(cx - s, cy - s * 0.9f); path.lineTo(cx + s * 0.6f, cy); path.lineTo(cx - s, cy + s * 0.9f); path.close() }
            else -> {   // expand: four corners
                paint.style = Paint.Style.STROKE; paint.strokeWidth = s * 0.25f; paint.strokeCap = Paint.Cap.ROUND
                val k = s * 0.9f
                path.moveTo(cx - k, cy - k * 0.2f); path.lineTo(cx - k, cy - k); path.lineTo(cx - k * 0.2f, cy - k)
                path.moveTo(cx + k, cy - k * 0.2f); path.lineTo(cx + k, cy - k); path.lineTo(cx + k * 0.2f, cy - k)
                path.moveTo(cx - k, cy + k * 0.2f); path.lineTo(cx - k, cy + k); path.lineTo(cx - k * 0.2f, cy + k)
                path.moveTo(cx + k, cy + k * 0.2f); path.lineTo(cx + k, cy + k); path.lineTo(cx + k * 0.2f, cy + k)
            }
        }
        c.drawPath(path, paint)
        paint.style = Paint.Style.FILL; paint.strokeCap = Paint.Cap.BUTT
    }

    // ------------------------------------------------------------------ the full screen view

    /** The music view over the whole home: a glow from the cover, the cover on a record, the words, a bar and four buttons. */
    fun drawAmbient(c: Canvas, w: Int, h: Int, u: Float) {
        val t = track ?: return
        val ov = if (vizLive) overlay() else 1f
        if (vizLive) {
            // the visualiser shows through: a soft dark wash keeps the words readable, and goes with the overlays
            if (lastKeyAt != 0L && !hidden && ov >= 1f) {
                val secs = prefs.getInt("viz_autohide", 0)
                if (secs > 0 && SystemClock.uptimeMillis() - lastKeyAt > secs * 1000L) setHidden(true)
            }
            paint.style = Paint.Style.FILL; paint.color = Color.BLACK; paint.alpha = 255; paint.shader = null
            val b = bright()
            if (b < 100) { paint.color = Color.argb(((100 - b) * 255 / 100), 0, 0, 0); c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint) }   // the brightness setting: a black veil over the visualiser
        }
        val layer = if (vizLive && ov < 1f) c.saveLayerAlpha(0f, 0f, w.toFloat(), h.toFloat(), (ov * 255).toInt()) else -1
        // the backdrop: couch-tv's dark radial wash, with two colour glows that breathe over eight seconds
        if (!vizLive) {
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(w / 2f, h / 2f, w * 0.7f, Color.rgb(14, 12, 20), Color.rgb(6, 6, 10), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        val breathe = 0.75f + 0.1f * sin(SystemClock.uptimeMillis() / 8000.0 * Math.PI).toFloat()
        glowAt(c, w * 0.35f, h * 0.45f, w * 0.45f, glow1, (0.7f * breathe * 255).toInt())
        glowAt(c, w * 0.65f, h * 0.55f, w * 0.5f, glow2, (0.7f * breathe * 255).toInt())
        paint.shader = null
        bg?.let { b ->   // the cover, blurred, over the whole screen, darkened so the words stay easy to read
            rect.set(0f, 0f, w.toFloat(), h.toFloat()); paint.isFilterBitmap = true; paint.alpha = 255
            ArtBlur.fill(c, b, rect, paint)
            paint.color = Color.argb(120, 0, 0, 0); c.drawRect(rect, paint)
        }
        }

        if (vizLive && ov > 0.01f) {   // one half-clear dark veil over the whole visualiser, which goes with the overlays
            paint.style = Paint.Style.FILL; paint.shader = null; paint.color = Color.argb(128, 0, 0, 0)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        }
        if (vizLive && ov <= 0.01f) { c.restoreToCount(layer.takeIf { it >= 0 } ?: c.saveCount); toast(c, w, h, u); if (t.playing) viz.start() else viz.stop(); return }
        // The layout: the middle of the screen is left clear for the visualiser. The cover sits bottom left, the words bottom right,
        // a full-width progress bar runs along the bottom with the buttons centred under it, and the lyrics go top right.
        val now = SystemClock.uptimeMillis()
        if (t.playing) viz.start() else viz.stop()
        val m = 28 * u
        val barY = h - 134 * u
        val btnY = h - 66 * u
        val artS = 400 * u
        val coverBottom = barY - 40 * u
        val artTop = coverBottom - artS
        // the cover
        paint.style = Paint.Style.FILL
        art(c, art, m, artTop, artS, 32 * u)

        // the words, right aligned, ending level with the bottom of the cover (the app has its own button below)
        val maxMeta = w - (m + artS + 120 * u) - m - 52 * u
        text.letterSpacing = 0f
        text.typeface = Fonts.semibold(ctx); text.textSize = 52 * u
        val title = TextUtils.ellipsize(t.title, text, maxMeta, TextUtils.TruncateAt.END)
        val titleW = text.measureText(title, 0, title.length)
        text.typeface = Fonts.regular(ctx); text.textSize = 30 * u
        val sub = TextUtils.ellipsize(listOf(t.artist, t.album).filter { it.isNotEmpty() }.joinToString("  ·  "), text, maxMeta, TextUtils.TruncateAt.END)
        val subW = text.measureText(sub, 0, sub.length)
        val metaW = maxOf(titleW, subW)
        val right = w - m - 26 * u
        val artistBase = coverBottom - 12 * u
        val titleBase = artistBase - 56 * u
        text.textAlign = Paint.Align.RIGHT
        text.typeface = Fonts.regular(ctx); text.textSize = 30 * u; text.color = Color.argb(204, 255, 255, 255)
        c.drawText(sub, 0, sub.length, right, artistBase, text)
        text.typeface = Fonts.semibold(ctx); text.textSize = 52 * u; text.color = Color.WHITE
        c.drawText(title, 0, title.length, right, titleBase, text)
        text.textAlign = Paint.Align.LEFT

        // the equaliser (only without the visualiser, which replaces it): between the cover and the words, along the bottom of that band
        if (!vizLive) {
            val ex0 = m + artS + 70 * u; val ex1 = right - metaW - 70 * u
            if (ex1 - ex0 > 240 * u) {
                val n = AudioViz.BANDS; val eqH = 110 * u
                val slot = (ex1 - ex0) / n; val bw = slot * 0.62f
                paint.color = Color.argb(150, 255, 255, 255)
                for (i in 0 until n) {
                    val lv = if (viz.live) viz.bands[i] else if (t.playing) (0.18f + 0.5f * (0.5f + 0.5f * sin(now / 260.0 + i * 0.7)).toFloat() * (0.5f + 0.5f * sin(now / 900.0 + i * 0.3)).toFloat()) else 0.06f
                    val hh = (eqH * lv.coerceIn(0.05f, 1f))
                    rect.set(ex0 + i * slot, coverBottom - hh, ex0 + i * slot + bw, coverBottom)
                    c.drawRoundRect(rect, bw / 2, bw / 2, paint)
                }
            }
        }

        // the bottom band: progress, then the buttons, then the hints on the left
        val pos = if (t.playing) t.position + (now - lastReadAt()) else t.position
        text.typeface = Fonts.regular(ctx); text.textSize = 20 * u; text.color = Color.argb(179, 255, 255, 255)
        c.drawText(fmt(pos), m + 10 * u, barY + 7 * u, text)
        val barL = m + 100 * u; val barR = w - m - (if (t.duration > 0) 100 * u else 10 * u)
        rect.set(barL, barY - 5 * u, barR, barY + 5 * u); paint.color = Color.argb(38, 255, 255, 255)
        c.drawRoundRect(rect, 5 * u, 5 * u, paint)
        val frac = if (t.duration > 0) (pos.toFloat() / t.duration).coerceIn(0f, 1f) else if (pos > 0) 0.4f else 0f
        if (frac > 0f) { rect.set(barL, barY - 5 * u, barL + (barR - barL) * frac, barY + 5 * u); paint.color = Color.WHITE; c.drawRoundRect(rect, 5 * u, 5 * u, paint) }
        if (t.duration > 0) { text.textAlign = Paint.Align.RIGHT; text.color = Color.argb(179, 255, 255, 255); c.drawText(fmt(t.duration), w - m - 10 * u, barY + 7 * u, text); text.textAlign = Paint.Align.LEFT }

        // buttons: previous, play or pause (bigger), next, stop, ..., and the app's name, centred under the bar
        val all = items()
        val kinds = all.dropLast(1).map { if (it == "play") (if (t.playing) "pause" else "play") else it }
        text.typeface = Fonts.semibold(ctx); text.textSize = 18 * u
        val ow = text.measureText(t.app) + 48 * u
        var total = ow
        for (i in kinds.indices) total += 2 * (if (i == 1) 40 else 34) * u + 24 * u
        var bx = (w - total) / 2f
        val btnLeft = bx
        for ((i, k) in kinds.withIndex()) {
            val r = (if (i == 1) 40 else 34) * u
            val f = ambBtn == i
            val cx = bx + r; val cy = btnY
            c.save(); if (f) { c.translate(cx, cy); c.scale(1.12f, 1.12f); c.translate(-cx, -cy) }
            paint.color = if (f) Color.WHITE else Color.argb(if (i == 1) 51 else 26, 255, 255, 255)
            c.drawCircle(cx, cy, r, paint)
            glyph(c, cx, cy, r * 0.5f, k, if (f) Color.rgb(10, 10, 15) else Color.WHITE)
            c.restore()
            bx += 2 * r + 24 * u
        }
        val f = ambBtn == all.size - 1
        rect.set(bx, btnY - 34 * u, bx + ow, btnY + 34 * u)
        paint.color = if (f) Color.WHITE else Color.argb(26, 255, 255, 255)
        c.drawRoundRect(rect, 34 * u, 34 * u, paint)
        text.color = if (f) Color.rgb(10, 10, 15) else Color.WHITE
        c.drawText(t.app, bx + 24 * u, btnY + 6 * u, text)
        // preset name bottom left (left aligned), down hint bottom right (right aligned), same height
        val hy = btnY + 6 * u
        val sideW = bx - m - 20 * u
        text.typeface = Fonts.regular(ctx)
        val line2 = if (vizLive && vizName.isNotEmpty()) "Preset: $vizName"
            else if (!vizLive && ProjectM.available && prefs.getBoolean("np_viz", true) && !PresetStore.ready(ctx)) "Music visualiser: get presets in Settings" else ""
        text.textAlign = Paint.Align.LEFT
        if (line2.isNotEmpty()) {
            text.textSize = 18 * u; text.color = Color.argb(150, 255, 255, 255)
            val l2 = TextUtils.ellipsize(line2, text, sideW, TextUtils.TruncateAt.END)
            c.drawText(l2, 0, l2.length, m, hy, text)
        }
        text.textAlign = Paint.Align.RIGHT
        text.textSize = 18 * u; text.color = Color.argb(150, 255, 255, 255)
        val zoneL = bx + ow + 30 * u
        val hint = TextUtils.ellipsize("▼ Press Down or Back for apps", text, w - m - zoneL, TextUtils.TruncateAt.END)
        c.drawText(hint, 0, hint.length, w - m, hy, text)
        text.textAlign = Paint.Align.LEFT
        if (lyricsOn) drawLyrics(c, w, h, u, pos)
        text.color = Color.WHITE
        if (layer >= 0) c.restoreToCount(layer)
        if (vizLive) toast(c, w, h, u)
    }

    /** With the overlays hidden, the preset's name shows bottom left for a few seconds after it changes. */
    private fun toast(c: Canvas, w: Int, h: Int, u: Float) {
        if (!vizLive) return
        val bAge = SystemClock.uptimeMillis() - brightAt
        if (brightAt != 0L && bAge < 2200L) {   // the brightness slider, bottom centre
            val a = if (bAge > 1700L) (2200L - bAge) / 500f else 1f
            val tw = 420 * u; val x0 = (w - tw) / 2f; val y0 = h - 120 * u
            paint.style = Paint.Style.FILL; paint.shader = null
            rect.set(x0 - 34 * u, y0 - 62 * u, x0 + tw + 34 * u, y0 + 34 * u); paint.color = Color.argb((140 * a).toInt(), 0, 0, 0); c.drawRoundRect(rect, 34 * u, 34 * u, paint)
            rect.set(x0, y0 - 6 * u, x0 + tw, y0 + 6 * u); paint.color = Color.argb((70 * a).toInt(), 255, 255, 255); c.drawRoundRect(rect, 6 * u, 6 * u, paint)
            val f = (bright() - 20) / 80f
            rect.set(x0, y0 - 6 * u, x0 + tw * f, y0 + 6 * u); paint.color = Color.argb((255 * a).toInt(), 255, 255, 255); c.drawRoundRect(rect, 6 * u, 6 * u, paint)
            c.drawCircle(x0 + tw * f, y0, 12 * u, paint)
            text.typeface = Fonts.semibold(ctx); text.textSize = 22 * u; text.textAlign = Paint.Align.CENTER; text.color = Color.argb((235 * a).toInt(), 255, 255, 255)
            c.drawText("Brightness ${bright()}%", w / 2f, y0 - 24 * u, text); text.textAlign = Paint.Align.LEFT
        }
        val tr = track
        val tAge = SystemClock.uptimeMillis() - trackAt
        if (hidden && tr != null && trackAt != 0L && tAge < 3500L) {   // the song, bottom right, when it changes
            val a = if (tAge > 2900L) (3500L - tAge) / 600f else if (tAge < 300L) tAge / 300f else 1f
            text.textAlign = Paint.Align.RIGHT
            text.typeface = Fonts.semibold(ctx); text.textSize = 40 * u; text.color = Color.argb((255 * a).toInt(), 255, 255, 255)
            text.setShadowLayer(8 * u, 0f, 0f, Color.argb((210 * a).toInt(), 0, 0, 0))
            val tt = TextUtils.ellipsize(tr.title, text, w * 0.6f, TextUtils.TruncateAt.END)
            c.drawText(tt, 0, tt.length, w - 80 * u, h - 112 * u, text)
            text.typeface = Fonts.regular(ctx); text.textSize = 26 * u; text.color = Color.argb((220 * a).toInt(), 255, 255, 255)
            val ss = TextUtils.ellipsize(listOf(tr.artist, tr.album).filter { it.isNotEmpty() }.joinToString("  ·  "), text, w * 0.6f, TextUtils.TruncateAt.END)
            c.drawText(ss, 0, ss.length, w - 80 * u, h - 70 * u, text)
            text.clearShadowLayer(); text.textAlign = Paint.Align.LEFT
        }
        if (vizName.isEmpty()) return
        val age = SystemClock.uptimeMillis() - nameAt
        if (!hidden || age > 3000L) return
        val a = if (age > 2400L) (3000L - age) / 600f else if (age < 300L) age / 300f else 1f
        text.typeface = Fonts.regular(ctx); text.textSize = 22 * u; text.textAlign = Paint.Align.LEFT
        text.color = Color.argb((190 * a).toInt(), 255, 255, 255)
        text.setShadowLayer(6 * u, 0f, 0f, Color.argb((200 * a).toInt(), 0, 0, 0))
        c.drawText(TextUtils.ellipsize(vizName, text, w - 160 * u, TextUtils.TruncateAt.END), 0, TextUtils.ellipsize(vizName, text, w - 160 * u, TextUtils.TruncateAt.END).length, 80 * u, h - 70 * u, text)
        text.clearShadowLayer()
    }

    /** The lyrics, centred in the space above the cover: the line being sung large, a few before and after it fading away. Plain lyrics move along with the song. */
    private fun drawLyrics(c: Canvas, w: Int, h: Int, u: Float, pos: Long) {
        val res = lyr
        val top = 40 * u
        val bottom = h - 134 * u - 40 * u - 400 * u - 30 * u     // above the cover
        val maxW = w * 0.82f
        text.textAlign = Paint.Align.CENTER; text.letterSpacing = 0f
        if (res == null) {
            if (!lyrDone) { text.textAlign = Paint.Align.LEFT; return }
            text.typeface = Fonts.regular(ctx); text.textSize = 24 * u; text.color = Color.argb(130, 255, 255, 255)
            c.drawText("No lyrics found for this song", w / 2f, (top + bottom) / 2f, text)
            text.textAlign = Paint.Align.LEFT; return
        }
        val l = res.lines
        val i = l.indexOfLast { it.ms <= pos }
        val cy = (top + bottom) / 2f
        val room = (bottom - top) / 2f
        fun row(idx: Int, dy: Float, size: Float, alpha: Int, bold: Boolean) {
            val t = l.getOrNull(idx)?.text?.takeIf { it.isNotBlank() } ?: return
            val y = cy + dy
            if (y < top + size * u * 0.7f || y > bottom + size * u * 0.3f) return
            text.typeface = if (bold) Fonts.semibold(ctx) else Fonts.regular(ctx); text.textSize = size * u; text.color = Color.argb(alpha, 255, 255, 255)
            text.setShadowLayer(8 * u, 0f, 0f, Color.argb(alpha * 3 / 4, 0, 0, 0))
            val e = TextUtils.ellipsize(t, text, maxW, TextUtils.TruncateAt.END)
            c.drawText(e, 0, e.length, w / 2f, y + size * u * 0.35f, text)
            text.clearShadowLayer()
        }
        row(i, 0f, 52f, 255, true)
        var dy = 70 * u
        for (k in 1..4) { row(i + k, dy, 32f, (190 - k * 38).coerceAtLeast(40), false); row(i - k, -dy, 32f, (190 - k * 38).coerceAtLeast(40), false); dy += 56 * u }
        if (!res.synced) { text.typeface = Fonts.regular(ctx); text.textSize = 16 * u; text.color = Color.argb(110, 255, 255, 255); c.drawText("Lyrics are not timed for this song: the lines move along with it", w / 2f, bottom + 6 * u, text) }
        text.textAlign = Paint.Align.LEFT
    }

    private fun glowAt(c: Canvas, x: Float, y: Float, r: Float, color: Int, alpha: Int) {
        val a = Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
        paint.shader = RadialGradient(x, y, r, intArrayOf(a, Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(x - r, y - r, x + r, y + r, paint)
    }

    private var readAt = SystemClock.uptimeMillis()   // when [track]'s position was read
    private fun lastReadAt() = readAt

    private fun fmt(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }
}
