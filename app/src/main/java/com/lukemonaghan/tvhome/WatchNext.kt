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
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
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

    private val prefs = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)

    /** Shows the household removed from Keep watching on this TV: the row id, and "package|title" for when the app writes the row again. */
    private fun hidden(): Set<String> = prefs.getStringSet("hiddenWatchNext", emptySet()) ?: emptySet()

    /** Removes a show from Keep watching on this TV only. The app's own Watch Next row is never touched. */
    fun dismiss(card: Card) {
        val h = hidden().toMutableSet()
        h.add(card.id.toString()); h.add("${card.pkg}|${card.title}")
        prefs.edit().putStringSet("hiddenWatchNext", h).apply()
        cards = cards.filter { it.id != card.id && !(it.pkg == card.pkg && it.title == card.title) }
        onChange()
    }

    val permitted get() = ctx.checkSelfPermission(READ_TV) == PackageManager.PERMISSION_GRANTED

    /** Reads the list again (cheap: one query). Call when the home comes back to the front. */
    fun reload() {
        if (!permitted) { if (cards.isNotEmpty()) { cards = emptyList(); onChange() }; return }
        worker.execute {
            val rows = runCatching { read() }.getOrDefault(emptyList())
            main.post { cards = rows; onChange() }
            runCatching { saveCards(rows) }
        }
    }

    private fun read(): List<Card> {
        val out = ArrayList<Card>()
        val perApp = HashMap<String, Int>()
        val gone = hidden()
        val cols = arrayOf("_id", "package_name", "title", "episode_title", "season_display_number", "episode_display_number",
            "poster_art_uri", "thumbnail_uri", "last_playback_position_millis", "duration_millis", "intent_uri")
        ctx.contentResolver.query(WATCH_NEXT, cols, null, null, "last_engagement_time_utc_millis DESC")?.use { c ->
            while (c.moveToNext() && out.size < 20) {
                val pkg = c.getString(1) ?: continue
                if (pkg == ctx.packageName) continue
                val id = c.getLong(0)
                if (id.toString() in gone || "$pkg|${c.getString(2).orEmpty()}" in gone) continue
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
            val bmp = runCatching { fromDisk(key, w, h) ?: fetch(key, w, h)?.also { toDisk(key, w, h, it) } }.getOrNull()
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

    // Pictures live on disk too (already shrunk to the card), so a restart or a memory squeeze does not fetch them again.
    private val dir = File(ctx.cacheDir, "watchnext").apply { mkdirs() }
    private fun file(key: String, w: Int, h: Int) = File(dir, "${key.hashCode().toUInt().toString(16)}_${w}x$h.jpg")
    private fun fromDisk(key: String, w: Int, h: Int): Bitmap? {
        val f = file(key, w, h)
        return if (f.exists()) BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }) else null
    }
    private fun toDisk(key: String, w: Int, h: Int, b: Bitmap) {
        runCatching {
            file(key, w, h).outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            val files = dir.listFiles() ?: return
            if (files.size > 120) files.sortedBy { it.lastModified() }.take(files.size - 100).forEach { it.delete() }
        }
    }

    /** The last list, so the cards show at once after a restart, before the TV provider answers. */
    private fun saveCards(list: List<Card>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("pkg", it.pkg).put("app", it.app).put("title", it.title).put("ep", it.episode)
            .put("art", it.art ?: JSONObject.NULL).put("p", it.progress.toDouble()).put("uri", it.intentUri ?: JSONObject.NULL)) }
        prefs.edit().putString("watchNextLast", a.toString()).apply()
    }
    private fun loadCards(): List<Card> = runCatching {
        val a = JSONArray(prefs.getString("watchNextLast", "[]")); val gone = hidden()
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            Card(o.getLong("id"), o.getString("pkg"), o.getString("app"), o.getString("title"), o.getString("ep"),
                if (o.isNull("art")) null else o.getString("art"), o.getDouble("p").toFloat(), if (o.isNull("uri")) null else o.getString("uri"))
        }.filter { it.id.toString() !in gone && "${it.pkg}|${it.title}" !in gone }
    }.getOrDefault(emptyList())
    init { if (permitted) cards = loadCards() }

    fun clear() { art.evictAll() }   // memory only: the files on disk are what make the next start quick

    companion object {
        const val READ_TV = "android.permission.READ_TV_LISTINGS"
        val WATCH_NEXT: Uri = Uri.parse("content://android.media.tv/watch_next_program")
    }
}
