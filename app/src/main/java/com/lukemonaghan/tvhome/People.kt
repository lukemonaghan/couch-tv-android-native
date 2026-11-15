package com.lukemonaghan.tvhome

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/** One show on a kid's shelf: a title, the app that plays it and, when there is one, the link that opens it and a picture. */
class KidShow(val title: String, val pkg: String, val link: String?, val art: String?, val sub: String) {
    /** The same show as a Keep watching card, so the poster is fetched and cached by the same code. */
    val card: Card by lazy { Card(0, pkg, "", title, sub, art, 0f, link) }
}

/**
 * A kids profile's home (couch-tv's "Kids mode"): the shows and apps a grown up chose, and the parent PIN as a salted
 * SHA-256 hash only (the PIN itself is never kept). Same hash as couch-tv's kids.js: SHA-256 applied [rounds] times to "salt:pin".
 */
class KidsConfig(
    var on: Boolean = false, var salt: String = "", var rounds: Int = 4000, var hash: String = "",
    val shows: MutableList<KidShow> = ArrayList(), val apps: MutableList<String> = ArrayList(),
) {
    val hasPin get() = hash.isNotEmpty() && salt.isNotEmpty()
    fun matches(pin: String) = hasPin && pin.length >= 4 && Pins.hash(pin, salt, rounds) == hash
    fun setPin(pin: String) { salt = Pins.newSalt(); rounds = 4000; hash = Pins.hash(pin, salt, rounds) }
}

class Person(var id: String, var name: String, var colour: Int = 0, var kid: Boolean = false, var guest: Boolean = false, var kids: KidsConfig? = null)

object Pins {
    fun hash(pin: String, salt: String, rounds: Int): String {
        val md = MessageDigest.getInstance("SHA-256")
        var h = "$salt:$pin".toByteArray(Charsets.US_ASCII)
        repeat(rounds) { h = md.digest(h) }
        return h.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun newSalt(): String { val b = ByteArray(16); SecureRandom().nextBytes(b); return b.joinToString("") { "%02x".format(it.toInt() and 255) } }
}

/**
 * Who watches this TV: up to 12 people, kept as one JSON string in the "home" prefs (with the old "people" list of names read
 * once if that is all there is). The selected person is "personId". Each person has their own app order, kept by [AppStore].
 */
class People(private val prefs: SharedPreferences) {
    private var cache: List<Person>? = null

    fun list(): List<Person> {
        cache?.let { return it }
        val out = ArrayList<Person>()
        val raw = prefs.getString("peopleJson", null)
        if (raw != null) {
            runCatching {
                val a = JSONArray(raw)
                for (i in 0 until a.length()) out.add(read(a.getJSONObject(i)))
            }
        } else {   // the first version kept only names, split by |
            prefs.getString("people", null)?.split('|')?.filter { it.isNotBlank() }?.forEach { n -> out.add(Person(newId(n, out), n.trim())) }
        }
        cache = out
        return out
    }

    fun save(list: List<Person> = list()) {
        cache = list
        val a = JSONArray(); list.forEach { a.put(write(it)) }
        prefs.edit().putString("peopleJson", a.toString()).putString("people", list.joinToString("|") { it.name }).apply()
    }

    val count get() = list().size
    fun byId(id: String?) = list().firstOrNull { it.id == id }
    fun index(id: String?) = list().indexOfFirst { it.id == id }.coerceAtLeast(0)

    /** The person on screen: the saved choice, else the first. Null when nobody is set up. */
    fun current(): Person? = byId(prefs.getString("personId", null)) ?: list().firstOrNull()
    fun select(id: String) { if (byId(id) != null) prefs.edit().putString("personId", id).apply() }
    fun currentIndex() = index(current()?.id)

    fun add(name: String): Person? {
        val n = clean(name); val l = list().toMutableList()
        if (n.isEmpty() || l.size >= MAX) return null
        val p = Person(newId(n, l), n)
        l.add(p); save(l); return p
    }
    fun rename(id: String, name: String) { val n = clean(name); if (n.isNotEmpty()) { byId(id)?.name = n; save() } }
    fun remove(id: String) {
        val l = list().filter { it.id != id }
        save(l)
        if (prefs.getString("personId", null) == id) prefs.edit().remove("personId").apply()
    }
    fun move(id: String, d: Int) {
        val l = list().toMutableList(); val i = l.indexOfFirst { it.id == id }; val j = i + d
        if (i < 0 || j < 0 || j >= l.size) return
        val p = l.removeAt(i); l.add(j, p); save(l)
    }

    /** A kids profile is one with a kids home switched on and a parent PIN set: only then does the home turn into the kids shelf. */
    fun isKid(p: Person?) = p?.kids?.let { it.on && it.hasPin } == true

    fun colourOf(p: Person): Int {
        if (p.colour != 0) return p.colour
        var h = 0
        for (ch in p.id) h = h * 31 + ch.code
        return PALETTE[((h.toLong() and 0xFFFFFFFFL) % PALETTE.size).toInt()]
    }

    private fun clean(v: String) = v.replace(Regex("\\s+"), " ").trim().take(40)

    private fun newId(name: String, list: List<Person>): String {
        var base = java.text.Normalizer.normalize(name.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("[^a-z0-9]+"), "-").trim('-').take(24).ifEmpty { "person" }
        if (base in listOf("both", "all", "everyone", "shared", "together")) base += "-1"
        var id = base; var n = 2
        while (list.any { it.id == id }) id = base + "-" + n++
        return id
    }

    private fun read(o: JSONObject): Person {
        val p = Person(o.getString("id"), o.getString("name"), o.optInt("colour", 0), o.optBoolean("kid"), o.optBoolean("guest"))
        o.optJSONObject("kids")?.let { k ->
            val c = KidsConfig(k.optBoolean("on"), k.optString("salt"), k.optInt("rounds", 4000), k.optString("hash"))
            k.optJSONArray("shows")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { s ->
                c.shows.add(KidShow(s.getString("title"), s.optString("pkg"), s.optString("link").ifEmpty { null }, s.optString("art").ifEmpty { null }, s.optString("sub")))
            } }
            k.optJSONArray("apps")?.let { a -> for (i in 0 until a.length()) c.apps.add(a.getString(i)) }
            p.kids = c
        }
        return p
    }

    private fun write(p: Person): JSONObject = JSONObject().put("id", p.id).put("name", p.name).put("colour", p.colour).put("kid", p.kid).put("guest", p.guest).also { o ->
        p.kids?.let { k ->
            val shows = JSONArray(); k.shows.forEach { s -> shows.put(JSONObject().put("title", s.title).put("pkg", s.pkg).put("link", s.link ?: "").put("art", s.art ?: "").put("sub", s.sub)) }
            o.put("kids", JSONObject().put("on", k.on).put("salt", k.salt).put("rounds", k.rounds).put("hash", k.hash).put("shows", shows).put("apps", JSONArray(k.apps)))
        }
    }

    companion object {
        const val MAX = 12
        /** couch-tv's person colours. */
        val PALETTE = intArrayOf(0xFFFF9F0A.toInt(), 0xFF30B350.toInt(), 0xFF0A84FF.toInt(), 0xFFBF5AF2.toInt(), 0xFFFF375F.toInt(), 0xFF00A5A8.toInt(), 0xFF5E5CE6.toInt(), 0xFFAC8E68.toInt())
        val COLOUR_NAMES = listOf("Orange", "Green", "Blue", "Purple", "Pink", "Teal", "Indigo", "Tan")
    }
}
