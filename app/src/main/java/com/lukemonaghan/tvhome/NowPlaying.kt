package com.lukemonaghan.tvhome

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
    private val viz = AudioViz(ctx)
    private var lyr: List<Lyrics.Line>? = null
    val lyricsOn get() = prefs.getBoolean("np_lyrics", false)
    private var artKey = ""
    private var glow1 = Color.rgb(88, 66, 160); private var glow2 = Color.rgb(40, 90, 160)
    private var lastTrackKey = ""
    private var pausedAt = 0L                 // a paused song stays on the card for ten minutes
    private var started = false

    var focused = false                       // the remote is on the card
    private var btn = 1
    var ambientOpen = false; private set
    private var ambientDismissed = false
    private var ambBtn = 1

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
        if (key != artKey) loadArt(md, key)
        val newTrack = key != lastTrackKey
        track = Track(pick.packageName, app, title, artist, album, playing, if (pos < 0) 0 else pos, dur, pick)
        readAt = SystemClock.uptimeMillis()
        if (newTrack) {
            lastTrackKey = key; ambientDismissed = false; lyr = null
            if (lyricsOn) Lyrics.fetch(artist, title, album, (dur / 1000).toInt()) { l -> main.post { if (lastTrackKey == key) { lyr = l; changed(false) } } }
        }
        val auto = newTrack && ambientOn && !ambientDismissed
        if (auto) { ambientOpen = true; ambBtn = 1 }
        changed(auto)
    }

    private fun loadArt(md: MediaMetadata, key: String) {
        artKey = key
        val raw = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        art = raw?.let {
            val s = 480f / maxOf(it.width, it.height)
            if (s < 1f) Bitmap.createScaledBitmap(it, (it.width * s).toInt().coerceAtLeast(1), (it.height * s).toInt().coerceAtLeast(1), true) else it
        }
        bg = art?.let { blurred(it) }
        art?.let { palette(it) } ?: run { glow1 = Color.rgb(88, 66, 160); glow2 = Color.rgb(40, 90, 160) }
    }

    /** A soft blur of the cover for the music view's background: shrunk to 12 px then grown to 48 px with smoothing, drawn stretched over the screen (about 9 KB, no blur library). */
    private fun blurred(b: Bitmap): Bitmap {
        val tiny = Bitmap.createScaledBitmap(b, 12, 12, true)
        val soft = Bitmap.createScaledBitmap(tiny, 48, 48, true)
        if (tiny !== b) tiny.recycle()
        return soft
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
        return listOf("prev", if (t.playing) "pause" else "play", "next") + (if (ambientOn) listOf("expand") else emptyList())
    }

    fun focus() { focused = true; btn = 1.coerceAtMost(maxOf(0, buttons().size - 1)) }
    fun blur() { focused = false }

    /** A key while the card (or the full screen view) has the remote. True when it was used here; false lets the home move off the card. */
    fun key(code: Int): Boolean {
        if (ambientOpen) {
            val n = 4
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
    fun abandonAmbient() { ambientOpen = false; viz.stop() }

    fun closeAmbient(dismiss: Boolean) { ambientOpen = false; viz.stop(); if (dismiss) ambientDismissed = true; focus() }

    fun press() {
        val t = track ?: return
        val tc = t.controller.transportControls
        if (ambientOpen) {
            when (ambBtn) { 0 -> tc.skipToPrevious(); 1 -> toggle(t); 2 -> tc.skipToNext(); 3 -> launch(t.pkg) }
            main.postDelayed({ refresh() }, 700L); return
        }
        when (buttons().getOrNull(btn)) {
            "prev" -> tc.skipToPrevious()
            "play", "pause" -> toggle(t)
            "next" -> tc.skipToNext()
            "expand" -> { ambientOpen = true; ambientDismissed = false; ambBtn = 1 }
        }
        main.postDelayed({ refresh() }, 700L)
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

    /** The card. [night] puts it in the Keep watching row; otherwise it sits top right under the quick buttons. [glass] is the home's own glass panel. */
    fun draw(c: Canvas, u: Float, night: Boolean, width: Int, glass: (Canvas, Float, Float, Float, Float, Float) -> Unit) {
        val t = track ?: return
        if (!on) return
        val w = (if (night) 600 else 496) * u
        val h = (if (night) 96 else 100) * u
        val x = if (night) (width - w) / 2 else width - 80 * u - w   // Night: centred under the clock, not in the shelf
        val y = if (night) 546 * u else 380 * u
        val r = (if (night) 30 else 28) * u
        glass(c, x, y, x + w, y + h, r)
        val pad = (if (night) 16 else 12) * u
        val artS = (if (night) 68 else 76) * u
        val ax = x + pad; val ay = y + (h - artS) / 2
        art(c, art, ax, ay, artS, 16 * u)
        val names = buttons()
        val ctl = names.size * 46 * u + (names.size - 1) * 6 * u
        val tl = ax + artS + 16 * u
        val tr = x + w - pad - ctl - 12 * u
        text.textAlign = Paint.Align.LEFT
        text.typeface = Fonts.semibold(ctx); text.textSize = (if (night) 15 else 15) * u; text.letterSpacing = 0.06f
        text.color = Color.argb(158, 255, 255, 255)
        val app = TextUtils.ellipsize(t.app.uppercase(), text, tr - tl, TextUtils.TruncateAt.END)
        val cy = y + h / 2
        c.drawText(app, 0, app.length, tl, cy - (if (night) 20 else 17) * u, text)
        text.letterSpacing = 0f
        text.typeface = Fonts.semibold(ctx); text.textSize = (if (night) 23 else 22) * u; text.color = Color.WHITE
        val title = TextUtils.ellipsize(t.title, text, tr - tl, TextUtils.TruncateAt.END)
        c.drawText(title, 0, title.length, tl, cy + (if (night) 6 else 6) * u, text)
        text.typeface = Fonts.regular(ctx); text.textSize = (if (night) 18 else 18) * u; text.color = Color.argb(189, 255, 255, 255)
        val sub = TextUtils.ellipsize(t.artist.ifEmpty { t.album }, text, tr - tl, TextUtils.TruncateAt.END)
        c.drawText(sub, 0, sub.length, tl, cy + (if (night) 30 else 30) * u, text)
        text.color = Color.WHITE
        // the buttons: 46 px circles, 6 apart; the full screen one is reached from the keys, shown only with the setting on
        var bx = x + w - pad - ctl
        for ((i, n) in names.withIndex()) {
            val f = focused && !ambientOpen && btn == i
            round(c, bx + 23 * u, cy, 23 * u, f, n, u)
            bx += 52 * u
        }
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
        // the backdrop: couch-tv's dark radial wash, with two colour glows that breathe over eight seconds
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(w / 2f, h / 2f, w * 0.7f, Color.rgb(14, 12, 20), Color.rgb(6, 6, 10), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        val breathe = 0.75f + 0.1f * sin(SystemClock.uptimeMillis() / 8000.0 * Math.PI).toFloat()
        glowAt(c, w * 0.35f, h * 0.45f, w * 0.45f, glow1, (0.7f * breathe * 255).toInt())
        glowAt(c, w * 0.65f, h * 0.55f, w * 0.5f, glow2, (0.7f * breathe * 255).toInt())
        paint.shader = null
        bg?.let { b ->   // the cover, blurred, over the whole screen, darkened so the words stay easy to read
            rect.set(0f, 0f, w.toFloat(), h.toFloat()); paint.isFilterBitmap = true; paint.alpha = 255
            c.drawBitmap(b, null, rect, paint)
            paint.color = Color.argb(120, 0, 0, 0); c.drawRect(rect, paint)
        }

        val artS = 400 * u; val gap = 80 * u; val metaW = 640 * u
        val left = (w - (artS + gap + metaW)) / 2f
        val top = (h - artS) / 2f - 110 * u   // lifted by a third of the space above, so the lyrics have room below
        // the record behind the cover, peeking out on the right
        val vr = 184 * u
        val vcx = left + artS + 48 * u - vr; val vcy = top + 16 * u + vr
        paint.color = Color.rgb(8, 8, 8); c.drawCircle(vcx, vcy, vr, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2 * u; paint.color = Color.argb(70, 70, 70, 76)
        for (f in intArrayOf(45, 60, 75, 90)) c.drawCircle(vcx, vcy, vr * f / 100f, paint)
        paint.style = Paint.Style.FILL
        art(c, art, left, top, artS, 32 * u)

        val mx = left + artS + gap
        var y = top + 8 * u
        text.textAlign = Paint.Align.LEFT; text.letterSpacing = 0.08f
        text.typeface = Fonts.semibold(ctx); text.textSize = 18 * u
        val appW = text.measureText(t.app.uppercase()) + 32 * u
        rect.set(mx, y, mx + appW, y + 36 * u); paint.color = Color.argb(26, 255, 255, 255)
        c.drawRoundRect(rect, 18 * u, 18 * u, paint)
        text.color = Color.argb(166, 255, 255, 255); c.drawText(t.app.uppercase(), mx + 16 * u, y + 25 * u, text)
        text.letterSpacing = 0f
        y += 36 * u + 20 * u
        text.typeface = Fonts.semibold(ctx); text.textSize = 52 * u; text.color = Color.WHITE
        val title = TextUtils.ellipsize(t.title, text, metaW, TextUtils.TruncateAt.END)
        c.drawText(title, 0, title.length, mx, y + 48 * u, text)
        y += 62 * u + 8 * u
        text.typeface = Fonts.regular(ctx); text.textSize = 30 * u; text.color = Color.argb(204, 255, 255, 255)
        val sub = TextUtils.ellipsize(listOf(t.artist, t.album).filter { it.isNotEmpty() }.joinToString("  ·  "), text, metaW, TextUtils.TruncateAt.END)
        c.drawText(sub, 0, sub.length, mx, y + 30 * u, text)
        y += 48 * u + 32 * u
        // the equaliser: the real spectrum of what is playing, across the whole width (a gentle pattern when the TV does not allow it)
        val now = SystemClock.uptimeMillis()
        if (t.playing) viz.start() else viz.stop()
        val n = AudioViz.BANDS; val eqH = 52 * u
        val slot = metaW / n; val bw = slot * 0.62f
        paint.color = Color.argb(150, 255, 255, 255)
        for (i in 0 until n) {
            val lv = if (viz.live) viz.bands[i] else if (t.playing) (0.18f + 0.5f * (0.5f + 0.5f * sin(now / 260.0 + i * 0.7)).toFloat() * (0.5f + 0.5f * sin(now / 900.0 + i * 0.3)).toFloat()) else 0.06f
            val hh = (eqH * lv.coerceIn(0.05f, 1f))
            val bx0 = mx + i * slot
            rect.set(bx0, y + eqH - hh, bx0 + bw, y + eqH)
            c.drawRoundRect(rect, bw / 2, bw / 2, paint)
        }
        y += eqH + 24 * u
        // progress: elapsed, a bar, total (the position is the last read plus the time since)
        val pos = if (t.playing) t.position + (now - lastReadAt()) else t.position
        text.textSize = 18 * u; text.color = Color.argb(179, 255, 255, 255)
        c.drawText(fmt(pos), mx, y + 15 * u, text)
        val barL = mx + 70 * u; val barR = mx + metaW - (if (t.duration > 0) 70 * u else 0f)
        rect.set(barL, y + 4 * u, barR, y + 14 * u); paint.color = Color.argb(38, 255, 255, 255)
        c.drawRoundRect(rect, 5 * u, 5 * u, paint)
        val frac = if (t.duration > 0) (pos.toFloat() / t.duration).coerceIn(0f, 1f) else if (pos > 0) 0.4f else 0f
        if (frac > 0f) { rect.set(barL, y + 4 * u, barL + (barR - barL) * frac, y + 14 * u); paint.color = Color.WHITE; c.drawRoundRect(rect, 5 * u, 5 * u, paint) }
        if (t.duration > 0) { text.textAlign = Paint.Align.RIGHT; c.drawText(fmt(t.duration), mx + metaW, y + 15 * u, text); text.textAlign = Paint.Align.LEFT }
        y += 14 * u + 32 * u
        // buttons: previous, play or pause (bigger), next, and the app's name
        var bx = mx
        val kinds = listOf("prev", if (t.playing) "pause" else "play", "next")
        for ((i, k) in kinds.withIndex()) {
            val r = (if (i == 1) 40 else 34) * u
            val f = ambBtn == i
            val cx = bx + r; val cy = y + 40 * u
            c.save(); if (f) { c.translate(cx, cy); c.scale(1.12f, 1.12f); c.translate(-cx, -cy) }
            paint.color = if (f) Color.WHITE else Color.argb(if (i == 1) 51 else 26, 255, 255, 255)
            c.drawCircle(cx, cy, r, paint)
            glyph(c, cx, cy, r * 0.5f, k, if (f) Color.rgb(10, 10, 15) else Color.WHITE)
            c.restore()
            bx += 2 * r + 24 * u
        }
        val open = t.app
        text.typeface = Fonts.semibold(ctx); text.textSize = 18 * u
        val ow = text.measureText(open) + 48 * u
        val f = ambBtn == 3
        rect.set(bx, y + 40 * u - 34 * u, bx + ow, y + 40 * u + 34 * u)
        paint.color = if (f) Color.WHITE else Color.argb(26, 255, 255, 255)
        c.drawRoundRect(rect, 34 * u, 34 * u, paint)
        text.color = if (f) Color.rgb(10, 10, 15) else Color.WHITE
        c.drawText(open, bx + 24 * u, y + 46 * u, text)
        text.color = Color.argb(140, 255, 255, 255); text.textSize = 18 * u
        c.drawText("▼ Press Down or Back for apps", mx, y + 120 * u, text)
        if (lyricsOn) drawLyrics(c, w, h, u, pos)
        text.color = Color.WHITE
    }

    /** Three lines of the synced lyrics along the bottom: the one before, the one being sung, the next. */
    private fun drawLyrics(c: Canvas, w: Int, h: Int, u: Float, pos: Long) {
        val l = lyr ?: return
        var i = l.indexOfLast { it.ms <= pos }
        if (i < 0) i = -1
        text.textAlign = Paint.Align.CENTER; text.letterSpacing = 0f
        val maxW = w - 240 * u
        fun row(idx: Int, y: Float, size: Float, alpha: Int, bold: Boolean) {
            val t = l.getOrNull(idx)?.text?.takeIf { it.isNotBlank() } ?: return
            text.typeface = if (bold) Fonts.semibold(ctx) else Fonts.regular(ctx); text.textSize = size * u; text.color = Color.argb(alpha, 255, 255, 255)
            val e = TextUtils.ellipsize(t, text, maxW, TextUtils.TruncateAt.END)
            c.drawText(e, 0, e.length, w / 2f, y, text)
        }
        row(i - 1, h - 190 * u, 28f, 100, false)
        row(i, h - 130 * u, 44f, 255, true)
        row(i + 1, h - 74 * u, 28f, 130, false)
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
