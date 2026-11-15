package com.lukemonaghan.tvhome

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Custom cards: the household's own cards at the end of Keep watching, from a small JSON file at a link
 * (Home settings, Smart home, Custom cards link; or `--es cards_url <link>` from a computer). Ported from
 * couch-tv's cards.js. Display only: whatever the JSON holds is turned into plain text and cleaned, nothing
 * in it is ever run, and only an http(s) picture link or a package name is kept. At most 8 cards, read again
 * every 10 minutes while the home is on screen; the last good set is kept for when the link does not answer.
 *
 * The file is either a list or {"cards":[...]}; a card is {"title","subtitle","value","image","app" or "url"}.
 */
object CustomCards {
    private const val MAX = 8
    private const val EVERY_MS = 10 * 60_000L
    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    private val URL_RE = Regex("^https?://[^\\s'\"<>\\\\`$]+$", RegexOption.IGNORE_CASE)

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var lastTry = 0L
    @Volatile private var busy = false

    /** The cards now, as Keep watching cards (ids from -1000 down: see [isCustom]). */
    @Volatile var cards: List<Card> = emptyList(); private set
    @Volatile var listener: (() -> Unit)? = null

    fun isCustom(c: Card) = c.id <= -1000L

    private fun store(ctx: Context) = ctx.applicationContext.getSharedPreferences("platform", Context.MODE_PRIVATE)

    fun link(ctx: Context): String = store(ctx).getString("cardsUrl", "") ?: ""

    /** Sets the link ("" removes it and the cards). Returns false when it is not an http(s) address. */
    fun setLink(ctx: Context, v: String): Boolean {
        val u = v.trim()
        if (u.isNotEmpty() && !(u.length <= 1000 && URL_RE.matches(u))) return false
        val e = store(ctx).edit()
        if (u.isEmpty()) e.remove("cardsUrl").remove("cardsJson") else e.putString("cardsUrl", u)
        e.apply()
        lastTry = 0L
        if (u.isEmpty()) { cards = emptyList(); main.post { listener?.invoke() } } else refresh(ctx, true)
        return true
    }

    /** Loads the last good set (so the cards are there before the network answers). */
    fun load(ctx: Context) {
        val raw = store(ctx).getString("cardsJson", null) ?: return
        parse(raw)?.let { cards = toCards(it) }
    }

    /** From MainActivity.onResume and the minute tick: asks for the cards again when 10 minutes have gone. */
    fun refresh(ctx: Context, force: Boolean = false) {
        val app = ctx.applicationContext
        val url = link(app)
        if (url.isEmpty() || busy) return
        val now = System.currentTimeMillis()
        if (!force && now - lastTry < EVERY_MS) return
        lastTry = now; busy = true
        io.execute {
            val body = try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000; c.instanceFollowRedirects = true
                try {
                    if (c.responseCode !in 200..299) null
                    else c.inputStream.use { inp ->
                        val buf = java.io.ByteArrayOutputStream(); val b = ByteArray(8192)
                        while (true) { val n = inp.read(b); if (n < 0) break; buf.write(b, 0, n); if (buf.size() > 65536) return@use null }
                        String(buf.toByteArray(), Charsets.UTF_8)
                    }
                } finally { c.disconnect() }
            } catch (e: Exception) { null }
            val list = body?.let { parse(it) }
            busy = false
            if (list != null) {   // not JSON, or nothing came back: keep the last good cards
                store(app).edit().putString("cardsJson", body).apply()
                val cs = toCards(list)
                main.post { cards = cs; listener?.invoke() }
            }
        }
    }

    private class Raw(val title: String, val subtitle: String, val value: String, val image: String, val app: String, val url: String)

    private fun text(v: Any?, most: Int): String {
        if (v !is String && v !is Number) return ""
        return v.toString().replace(Regex("[\\x00-\\x1f\\x7f]+"), " ").trim().take(most).trim()
    }

    private fun url(v: Any?): String {
        if (v !is String) return ""
        val s = v.trim()
        return if (s.length <= 1000 && URL_RE.matches(s)) s else ""
    }

    /** A JSON string -> clean cards, or null when it is not JSON. */
    private fun parse(s: String): List<Raw>? {
        if (s.length > 65536) return null
        return try {
            val j = org.json.JSONTokener(s).nextValue()
            val arr: JSONArray = when (j) { is JSONArray -> j; is JSONObject -> j.optJSONArray("cards") ?: JSONArray(); else -> return null }
            val out = ArrayList<Raw>()
            for (i in 0 until arr.length()) {
                if (out.size >= MAX) break
                val c = arr.optJSONObject(i) ?: continue
                val title = text(c.opt("title"), 40)
                if (title.isEmpty()) continue
                val app = (c.opt("app") as? String)?.takeIf { it.length <= 200 && PACKAGE.matches(it) } ?: ""
                out.add(Raw(title, text(c.opt("subtitle"), 60), text(c.opt("value"), 24), url(c.opt("image")), app, if (app.isEmpty()) url(c.opt("url")) else ""))
            }
            out
        } catch (e: Exception) { null }
    }

    /** Night's glass card has a small heading line, the title, and a line under it: subtitle, title, value. */
    private fun toCards(l: List<Raw>): List<Card> = l.mapIndexed { i, r ->
        Card(-1000L - i, r.app, r.subtitle, r.title, r.value, r.image.ifEmpty { null }, 0f, r.url.ifEmpty { null })
    }
}
