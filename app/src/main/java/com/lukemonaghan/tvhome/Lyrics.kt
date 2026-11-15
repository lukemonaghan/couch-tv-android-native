package com.lukemonaghan.tvhome

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Time-synced lyrics from LRCLIB (https://lrclib.net), a free open lyrics database. Spotify and the other music apps do not
 * publish lyrics to the TV, so this looks each song up by artist, title, album and length. Only used when the user switches
 * "Show lyrics" on (pref np_lyrics, off by default): it then sends the song's artist and title, nothing else, to lrclib.net.
 * One small request per song on a background thread, no library, the answer kept only for the song playing. Songs the
 * database does not have, or has only as plain text, simply show no lyrics.
 */
object Lyrics {
    class Line(val ms: Long, val text: String)

    private const val BASE = "https://lrclib.net/api"

    /** Looks the song up and calls [done] on a background thread with the lines, or null when there are none. */
    fun fetch(artist: String, title: String, album: String, durationSec: Int, done: (List<Line>?) -> Unit) {
        Thread {
            val lines = runCatching { lookup(artist, title, album, durationSec) }.getOrNull()
            done(lines)
        }.apply { isDaemon = true; start() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun lookup(artist: String, title: String, album: String, durationSec: Int): List<Line>? {
        // the first artist only: Spotify joins several with commas, the database lists one
        val a = artist.substringBefore(',').trim()
        var q = "$BASE/get?artist_name=${enc(a)}&track_name=${enc(title)}"
        if (album.isNotBlank()) q += "&album_name=${enc(album)}"
        if (durationSec > 0) q += "&duration=$durationSec"
        get(q)?.let { parse(JSONObject(String(it))) }?.let { return it }
        // not an exact match: take the best synced result of a search
        val body = get("$BASE/search?track_name=${enc(title)}&artist_name=${enc(a)}") ?: return null
        val arr = JSONArray(String(body))
        for (i in 0 until minOf(arr.length(), 8)) parse(arr.getJSONObject(i))?.let { return it }
        return null
    }

    private fun get(url: String): ByteArray? {
        var c: HttpURLConnection? = null
        return try {
            c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 4000; c.readTimeout = 6000
            c.setRequestProperty("User-Agent", "tvhome (Android TV home screen)")
            if (c.responseCode != 200) null else c.inputStream.use { it.readBytes().takeIf { b -> b.size <= 256 * 1024 } }
        } catch (e: Exception) { null } finally { c?.disconnect() }
    }

    private val STAMP = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")

    private fun parse(o: JSONObject): List<Line>? {
        val raw = o.optString("syncedLyrics", "")
        if (raw.isBlank()) return null
        val out = ArrayList<Line>()
        for (row in raw.lineSequence()) {
            val stamps = STAMP.findAll(row).toList()
            if (stamps.isEmpty()) continue
            val text = row.substring(stamps.last().range.last + 1).trim()
            for (m in stamps) {
                val frac = m.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                out.add(Line(m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1000 + frac, text))
            }
        }
        out.sortBy { it.ms }
        return out.takeIf { it.size >= 2 }
    }
}
