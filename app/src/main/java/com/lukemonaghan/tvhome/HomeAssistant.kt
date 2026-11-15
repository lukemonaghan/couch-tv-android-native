package com.lukemonaghan.tvhome

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Smart home, through Home Assistant. Ported from couch-tv's HomeAssistant.kt. Optional and off until a
 * section is set: from a computer with `--es ha_b64 <base64 of the JSON>` on the MainActivity intent
 * (tools/send-config.sh), or by pasting the JSON in Home settings, Smart home. The section is kept in
 * this app's private store ("ha"), never in the repository.
 *
 * Only these are allowed, each for the entities named in the section and nothing else: read the state of a
 * named climate entity, change its temperature or mode, switch it on or off, and run a named scene or script.
 * The doorbell (Doorbell.kt) also reads the named doorbell entity and the named camera's still picture.
 * Locks, alarms and garage doors are not supported, on purpose. Every call goes to the configured address
 * only, never follows a redirect and gives up after a few seconds. The token is never logged.
 *
 * Section shape: {"url":"http://homeassistant.local:8123","token":"...","climate":[{"entity":"climate.bedroom","name":"Bedroom"}],
 *   "scenes":[{"entity":"scene.movie","name":"Movie night"}],"doorbell":{"entity":"event.front_door","camera":"camera.front_door","name":"Front door"}}
 */
object HomeAssistant {
    private const val STORE = "ha"
    private const val KEY = "section"

    class Named(val entity: String, val name: String)
    class Bell(val entity: String, val camera: String?, val name: String)
    class Settings(val base: String, val token: String, val climate: List<Named>, val scenes: List<Named>, val doorbell: Bell?)

    private val CLIMATE = Regex("climate\\.[a-z0-9_]{1,64}")
    private val SCENE = Regex("(scene|script)\\.[a-z0-9_]{1,64}")
    private val BELL = Regex("(binary_sensor|input_boolean|event)\\.[a-z0-9_]{1,64}")
    private val CAMERA = Regex("camera\\.[a-z0-9_]{1,64}")
    private val TOKEN = Regex("[A-Za-z0-9._-]{20,1024}")
    val MODES = listOf("off", "heat", "cool", "heat_cool", "auto", "dry", "fan_only")

    @Volatile private var cached: Settings? = null
    @Volatile private var cachedFrom: String? = null

    private fun store(ctx: Context) = ctx.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    /**
     * What a computer sent. The whole section is replaced at once, never merged, so an address can never be
     * changed without its token being replaced too. "none", an empty value or anything that does not check out
     * switches smart home off. Returns true when the intent carried the extra.
     */
    fun readExtras(ctx: Context, i: Intent): Boolean {
        val v = i.getStringExtra("ha_b64")?.trim() ?: return false
        val json = if (v.isEmpty() || v == "none") null else try {
            String(android.util.Base64.decode(v, android.util.Base64.DEFAULT), Charsets.UTF_8)
        } catch (x: Exception) { null }
        replace(ctx, json)
        return true
    }

    /** Replaces the section with [json] (a checked section) or removes it ("none", null, or something that does not check out). */
    fun replace(ctx: Context, json: String?): Boolean {
        val ok = json != null && try { parse(JSONObject(json)) != null } catch (x: Exception) { false }
        val e = store(ctx).edit()
        if (ok) e.putString(KEY, json) else e.remove(KEY)
        e.commit()
        cached = null; cachedFrom = null
        Doorbell.start(ctx)
        return ok || json == null
    }

    /** The section, checked, or null when smart home is off. */
    fun settings(ctx: Context): Settings? {
        val raw = store(ctx).getString(KEY, null) ?: return null
        if (raw == cachedFrom) return cached
        val s = try { parse(JSONObject(raw)) } catch (e: Exception) { null }
        cached = s; cachedFrom = raw
        return s
    }

    /** True when there is a screen to show: at least one room or scene. A doorbell alone has no screen. */
    fun available(ctx: Context): Boolean = settings(ctx)?.let { it.climate.isNotEmpty() || it.scenes.isNotEmpty() } == true

    private fun parse(o: JSONObject): Settings? {
        val base = cleanBase(o.optString("url")) ?: return null
        val token = o.optString("token").trim().takeIf { TOKEN.matches(it) } ?: return null
        fun list(key: String, ok: Regex, most: Int): List<Named> {
            val a = o.optJSONArray(key) ?: return emptyList()
            val out = mutableListOf<Named>()
            for (k in 0 until a.length()) {
                if (out.size >= most) break
                val x = a.optJSONObject(k) ?: continue
                val ent = x.optString("entity").trim()
                if (!ok.matches(ent) || out.any { it.entity == ent }) continue
                out.add(Named(ent, x.optString("name").trim().take(40)))   // no name: Home Assistant's own
            }
            return out
        }
        val bell = o.optJSONObject("doorbell")?.let { b ->
            val ent = b.optString("entity").trim()
            val cam = b.optString("camera").trim().takeIf { it.isNotEmpty() }
            if (!BELL.matches(ent) || (cam != null && !CAMERA.matches(cam))) null
            else Bell(ent, cam, b.optString("name").trim().take(40))
        }
        return Settings(base, token, list("climate", CLIMATE, 8), list("scenes", SCENE, 10), bell)
    }

    /** http(s)://host[:port][/path], nothing else: no user name, no query, no fragment. */
    private fun cleanBase(u: String): String? = try {
        val url = URL(u.trim())
        if ((url.protocol != "http" && url.protocol != "https") || url.host.isNullOrEmpty() ||
            url.userInfo != null || url.query != null || url.ref != null) null
        else url.protocol + "://" + url.host + (if (url.port > 0) ":" + url.port else "") + url.path.trimEnd('/')
    } catch (e: Exception) { null }

    fun pretty(entity: String) = entity.substringAfter('.').replace('_', ' ').trim().replaceFirstChar { it.uppercase() }

    // ---------------------------------------------------------------- talking to Home Assistant

    /** One call. Returns the status code and the body (at most [limit] bytes), or -1 when it failed. */
    private fun call(s: Settings, method: String, path: String, body: JSONObject?, limit: Int): Pair<Int, ByteArray?> {
        var c: HttpURLConnection? = null
        return try {
            c = URL(s.base + path).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false   // the token only ever goes to the configured address
            c.connectTimeout = 3000; c.readTimeout = 6000
            c.requestMethod = method
            c.setRequestProperty("Authorization", "Bearer " + s.token)
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = c.responseCode
            if (code !in 200..299) return code to null
            val out = ByteArrayOutputStream()
            c.inputStream.use { inp ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > limit) return -1 to null
                }
            }
            code to out.toByteArray()
        } catch (e: Exception) { -1 to null } finally { c?.disconnect() }
    }

    /** The state of one entity, as Home Assistant sends it, or null when it did not answer. */
    fun state(s: Settings, entity: String): JSONObject? {
        val (code, bytes) = call(s, "GET", "/api/states/$entity", null, 256 * 1024)
        if (code != 200 || bytes == null) return null
        return try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (e: Exception) { null }
    }

    /** The doorbell camera's still picture, shrunk to about [width] pixels wide. */
    fun snapshot(s: Settings, width: Int): Bitmap? {
        val cam = s.doorbell?.camera ?: return null
        val (code, bytes) = call(s, "GET", "/api/camera_proxy/$cam", null, 6 * 1024 * 1024)
        if (code != 200 || bytes == null) return null
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var sample = 1
            while (o.outWidth / (sample * 2) >= width) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: Exception) { null }
    }

    private fun service(s: Settings, domain: String, service: String, data: JSONObject): Boolean =
        call(s, "POST", "/api/services/$domain/$service", data, 512 * 1024).first in 200..299

    // ---------------------------------------------------------------- what the smart home screen may ask

    /** One room as last read. [ok] false: Home Assistant did not answer for it (or it is unavailable). */
    class Room(val entity: String, var name: String) {
        var ok = false
        var loading = false
        var state = ""
        var action = ""
        var min = 7.0; var max = 35.0; var step = 0.5
        var modes: List<String> = emptyList()
        var current: Double? = null
        var target: Double? = null
        var low: Double? = null
        var high: Double? = null
    }

    private class Limits(val min: Double, val max: Double, val step: Double, val modes: List<String>)
    private val limits = ConcurrentHashMap<String, Limits>()

    private fun room(n: Named, st: JSONObject?): Room {
        val r = Room(n.entity, n.name.ifEmpty { pretty(n.entity) })
        val state = st?.optString("state")
        if (st == null || state == null || state == "unavailable" || state == "unknown") return r
        val a = st.optJSONObject("attributes") ?: JSONObject()
        val max = a.optDouble("max_temp", 35.0)
        // Home Assistant only sends a step when the device has one: half a degree in Celsius, one in Fahrenheit
        val step = a.optDouble("target_temp_step", if (max > 50) 1.0 else 0.5).takeIf { it > 0 } ?: 0.5
        val min = a.optDouble("min_temp", 7.0)
        val modes = mutableListOf<String>()
        a.optJSONArray("hvac_modes")?.let { m -> for (k in 0 until m.length()) m.optString(k).takeIf { it in MODES }?.let { modes.add(it) } }
        limits[n.entity] = Limits(min, max, step, modes)
        r.ok = true; r.state = state; r.min = min; r.max = max; r.step = step; r.modes = modes; r.action = a.optString("hvac_action")
        fun num(k: String): Double? = if (a.has(k) && !a.isNull(k)) a.optDouble(k).takeIf { it.isFinite() } else null
        r.current = num("current_temperature"); r.target = num("temperature"); r.low = num("target_temp_low"); r.high = num("target_temp_high")
        if (n.name.isEmpty() && a.optString("friendly_name").isNotEmpty()) r.name = a.optString("friendly_name").take(40)
        return r
    }

    /** Reads every named room. Blocking: call off the main thread. */
    fun rooms(s: Settings): List<Room> = s.climate.map { room(it, state(s, it.entity)) }

    /** A new set temperature, kept inside the limits last read for the room. */
    fun setTemperature(s: Settings, entity: String, want: Double): Boolean {
        if (s.climate.none { it.entity == entity }) return false
        val lim = limits[entity] ?: return false
        if (!want.isFinite()) return false
        val t = Math.round(want.coerceIn(lim.min, lim.max) / lim.step) * lim.step
        return service(s, "climate", "set_temperature", JSONObject().put("entity_id", entity).put("temperature", t))
    }

    fun setMode(s: Settings, entity: String, mode: String): Boolean {
        if (s.climate.none { it.entity == entity }) return false
        val known = limits[entity]?.modes ?: return false
        if (mode !in MODES || mode !in known) return false
        return service(s, "climate", "set_hvac_mode", JSONObject().put("entity_id", entity).put("hvac_mode", mode))
    }

    fun power(s: Settings, entity: String, on: Boolean): Boolean {
        if (s.climate.none { it.entity == entity }) return false
        return service(s, "climate", if (on) "turn_on" else "turn_off", JSONObject().put("entity_id", entity))
    }

    fun runScene(s: Settings, entity: String): Boolean {
        val sc = s.scenes.firstOrNull { it.entity == entity } ?: return false
        return service(s, sc.entity.substringBefore('.'), "turn_on", JSONObject().put("entity_id", sc.entity))
    }
}
