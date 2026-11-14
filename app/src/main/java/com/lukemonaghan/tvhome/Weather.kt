package com.lukemonaghan.tvhome

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Current temperature from Open-Meteo (free, no key). One small request every 30 minutes at most, on a worker
 * thread, with the platform's HttpURLConnection and org.json: no network library. The place is a latitude and
 * longitude in the "home" prefs (set from Home settings); until one is set nothing is fetched.
 */
class Weather(ctx: Context, private val onChange: () -> Unit) {
    private val prefs = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var lastTry = 0L
    var tempC: Int? = null; private set
    var code = 0; private set

    val hasPlace get() = prefs.contains("lat") && prefs.contains("lon")
    var fahrenheit: Boolean
        get() = prefs.getBoolean("fahrenheit", false)
        set(v) { prefs.edit().putBoolean("fahrenheit", v).apply(); onChange() }

    fun setPlace(lat: Double, lon: Double) {
        prefs.edit().putFloat("lat", lat.toFloat()).putFloat("lon", lon.toFloat()).apply()
        lastTry = 0L; refresh()
    }

    /** "22°" (or "72°F"), or empty while unknown. */
    fun text(): String {
        val c = tempC ?: return ""
        return if (fahrenheit) "${Math.round(c * 9.0 / 5 + 32)}°F" else "$c°"
    }

    /** Asks for a new reading if the last try was over 30 minutes ago. Cheap to call often. */
    fun refresh() {
        if (!hasPlace) return
        val now = System.currentTimeMillis()
        if (now - lastTry < 30 * 60_000L) return
        lastTry = now
        val lat = prefs.getFloat("lat", 0f); val lon = prefs.getFloat("lon", 0f)
        worker.execute {
            val r = runCatching {
                val u = URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code&forecast_days=1")
                val cn = u.openConnection() as HttpURLConnection
                cn.connectTimeout = 8000; cn.readTimeout = 8000
                try {
                    val cur = JSONObject(cn.inputStream.bufferedReader().readText()).getJSONObject("current")
                    Math.round(cur.getDouble("temperature_2m")).toInt() to cur.getInt("weather_code")
                } finally { cn.disconnect() }
            }.onFailure { android.util.Log.w("tvhome.weather", "fetch failed: $it") }.getOrNull()
            main.post {
                if (r == null) lastTry = now - 25 * 60_000L   // failed: try again in about 5 minutes
                else { tempC = r.first; code = r.second; onChange() }
            }
        }
    }
}
