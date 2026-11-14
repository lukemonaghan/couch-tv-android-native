package com.lukemonaghan.tvhome

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** One Keep watching card: a row of Android's Watch Next list. */
class Card(
    val id: Long, val pkg: String, val app: String, val title: String, val episode: String,
    val art: String?, val progress: Float, val intentUri: String?,
)

/**
 * Keep watching with no computer: Android's own Watch Next list, which Netflix, YouTube, Disney+ and others fill.
 * Read straight from the TV provider (content://android.media.tv/watch_next_program) with READ_TV_LISTINGS,
 * read only. Pictures are fetched on one worker thread, shrunk to the card's size and kept in a small cache.
 * A card opens the app at the link that app wrote for it.
 */
class WatchNext(private val ctx: Context, private val onChange: () -> Unit) {
    var cards: List<Card> = emptyList(); private set
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val art = object : LruCache<String, Bitmap>(3 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pending = HashSet<String>()

    val permitted get() = ctx.checkSelfPermission(READ_TV) == PackageManager.PERMISSION_GRANTED

    /** Reads the list again (cheap: one query). Call when the home comes back to the front. */
    fun reload() {
        if (!permitted) { if (cards.isNotEmpty()) { cards = emptyList(); onChange() }; return }
        worker.execute {
            val rows = runCatching { read() }.getOrDefault(emptyList())
            main.post { cards = rows; onChange() }
        }
    }

    private fun read(): List<Card> {
        val out = ArrayList<Card>()
        val perApp = HashMap<String, Int>()
        val cols = arrayOf("_id", "package_name", "title", "episode_title", "season_display_number", "episode_display_number",
            "poster_art_uri", "thumbnail_uri", "last_playback_position_millis", "duration_millis", "intent_uri")
        ctx.contentResolver.query(WATCH_NEXT, cols, null, null, "last_engagement_time_utc_millis DESC")?.use { c ->
            while (c.moveToNext() && out.size < 20) {
                val pkg = c.getString(1) ?: continue
                if (pkg == ctx.packageName) continue
                val n = (perApp[pkg] ?: 0) + 1
                if (n > 5) continue            // at most 5 cards from one app
                perApp[pkg] = n
                val label = runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                val season = c.getString(4).orEmpty(); val ep = c.getString(5).orEmpty()
                val line = listOf(if (season.isNotBlank()) "S$season" else "", if (ep.isNotBlank()) "E$ep" else "").filter { it.isNotEmpty() }.joinToString(" ")
                    .ifEmpty { c.getString(3).orEmpty() }
                val pos = c.getLong(8); val dur = c.getLong(9)
                out.add(Card(c.getLong(0), pkg, label, c.getString(2)?.takeIf { it.isNotBlank() } ?: label, line,
                    c.getString(7)?.takeIf { it.isNotBlank() } ?: c.getString(6)?.takeIf { it.isNotBlank() },
                    if (dur > 0 && pos > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f, c.getString(10)))
            }
        }
        return out
    }

    /** The card's picture at [w] x [h], or null while it loads (the view asks again after [onChange]). */
    fun picture(card: Card, w: Int, h: Int): Bitmap? {
        val key = card.art ?: return null
        art.get(key)?.let { return it }
        if (!pending.add(key)) return null
        worker.execute {
            val bmp = runCatching { fetch(key, w, h) }.getOrNull()
            main.post { pending.remove(key); if (bmp != null) { art.put(key, bmp); onChange() } }
        }
        return null
    }

    private fun fetch(uri: String, w: Int, h: Int): Bitmap? {
        fun open() = if (uri.startsWith("http")) (URL(uri).openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000 }.inputStream
                     else ctx.contentResolver.openInputStream(Uri.parse(uri))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
        val full = open()?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }) } ?: return null
        // centre-crop to the card's shape
        val scale = maxOf(w.toFloat() / full.width, h.toFloat() / full.height)
        val sw = (full.width * scale).toInt().coerceAtLeast(w); val sh = (full.height * scale).toInt().coerceAtLeast(h)
        val scaled = Bitmap.createScaledBitmap(full, sw, sh, true)
        val out = Bitmap.createBitmap(scaled, (sw - w) / 2, (sh - h) / 2, w, h)
        if (scaled !== out) scaled.recycle(); if (full !== scaled) full.recycle()
        return out
    }

    fun clear() { art.evictAll() }

    companion object {
        const val READ_TV = "android.permission.READ_TV_LISTINGS"
        val WATCH_NEXT: Uri = Uri.parse("content://android.media.tv/watch_next_program")
    }
}
