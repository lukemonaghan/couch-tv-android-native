package com.lukemonaghan.tvhome

import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/** Where the sun is: couch-tv's astronomical solar altitude (a day-of-year declination and equation of time). */
object Solar {
    class Pos(val alt: Double, val hourAngle: Double)

    fun at(lat: Double, lon: Double, millis: Long): Pos {
        val doy = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = millis }.get(Calendar.DAY_OF_YEAR)
        val b = Math.toRadians(360 / 365.25 * (doy - 81))
        val eot = 9.87 * sin(2 * b) - 7.53 * cos(b) - 1.5 * sin(b)
        val decl = Math.toRadians(23.45 * sin(b))
        val utc = (millis % 86_400_000L) / 3_600_000.0
        val ha = Math.toRadians(((utc + lon / 15 + eot / 60 + 24) % 24 - 12) * 15)
        val la = Math.toRadians(lat)
        val sinAlt = sin(la) * sin(decl) + cos(la) * cos(decl) * cos(ha)
        return Pos(Math.toDegrees(asin(sinAlt.coerceIn(-1.0, 1.0))), Math.toDegrees(ha))
    }

    /** With no place set couch-tv guesses from the hour: day is 35 degrees up, night 20 down. */
    fun byHour(hour: Int): Pos = Pos(if (hour in 6..17) 35.0 else -20.0, (hour - 12) * 15.0)
}

/**
 * Ambient weather over the scenes (couch-tv's ambient-weather.js): thin rain with splashes, drifting snow, slow fog
 * and thunderstorm flashes drawn on the home's own Canvas, plus a soft colour wash that follows the sun (rose at
 * sunrise, amber at golden hour, indigo at night). Off by default: the particles redraw the home about 25 times a
 * second, which a weak TV may not want. Particles are allocated once; nothing is allocated while drawing.
 *
 * Prefs ("home"): wx_fx (rain, snow and fog, default off), wx_sun (sun colour, default on), lighter (turns both off).
 */
class AmbientFx(private val prefs: SharedPreferences, private val weather: Weather, private val frame: () -> Unit) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val rnd = java.util.Random()
    private val W = 1920f; private val H = 1080f

    private class Drop(var x: Float = 0f, var y: Float = 0f, var len: Float = 30f, var speed: Float = 24f, var alpha: Float = 0.2f,
                       var width: Float = 1.4f, var splash: Float = 0f, var splashMax: Float = 8f, var sx: Float = 0f, var sy: Float = 0f)
    private class Flake(var x: Float = 0f, var y: Float = 0f, var r: Float = 2f, var vy: Float = 2f, var vx: Float = 0f,
                        var wob: Float = 0f, var wobSpeed: Float = 0.02f, var alpha: Float = 0.4f)
    private class Fog(var x: Float, var y: Float, var r: Float, var speed: Float, var alpha: Float)

    private val drops = Array(65) { Drop() }
    private val flakes = Array(40) { Flake() }
    private val fogs = Array(4) { Fog(r(0f, W), r(H * 0.35f, H * 0.95f), r(260f, 480f), r(0.12f, 0.28f) * (if (rnd.nextBoolean()) 1 else -1), r(0.04f, 0.08f)) }
    private var type = "clear"
    private var last = 0L
    private var flash = 0f; private var nextFlash = 0L

    private fun r(a: Float, b: Float) = a + rnd.nextFloat() * (b - a)

    private val RAIN = setOf(51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82)
    private val SNOW = setOf(71, 73, 75, 77, 85, 86)
    private val FOG = setOf(45, 48)
    private val STORM = setOf(95, 96, 99)

    private fun lighter() = prefs.getBoolean("lighter", false)
    val particlesOn get() = !Motion.off(prefs) && prefs.getBoolean("wx_fx", false) && prefs.getBoolean("weatherOn", true) && !lighter()
    val sunOn get() = prefs.getBoolean("wx_sun", true) && !lighter()

    private fun typeNow(): String {
        if (!particlesOn) return "clear"
        prefs.getString("wx_demo", null)?.let { return it } // test hook: rain, snow, fog, storm
        if (weather.tempC == null) return "clear"
        val c = weather.code
        return when { c in STORM -> "storm"; c in RAIN -> "rain"; c in SNOW -> "snow"; c in FOG -> "fog"; else -> "clear" }
    }

    private fun resetDrop(d: Drop, anywhere: Boolean) {
        d.len = r(28f, 52f); d.speed = r(24f, 36f); d.alpha = r(0.12f, 0.32f); d.width = r(1.2f, 1.8f); d.splash = 0f; d.splashMax = r(6f, 14f)
        d.x = r(-150f, W + 150f); d.y = if (anywhere) r(0f, H) else -d.len
    }
    private fun resetFlake(f: Flake, anywhere: Boolean) {
        f.x = r(-50f, W + 50f); f.y = if (anywhere) r(0f, H) else -10f; f.r = r(1.8f, 3.8f); f.vy = r(1.2f, 2.8f); f.vx = r(-0.4f, 0.4f)
        f.wob = r(0f, (2 * PI).toFloat()); f.wobSpeed = r(0.015f, 0.035f); f.alpha = r(0.25f, 0.65f)
    }

    /** Draws the sun wash and the weather over the scene, under the clock and rows. Asks for another frame while anything moves. */
    fun draw(c: Canvas, w: Int, h: Int) {
        val s = w / W
        val sunTint = sunOn
        if (sunTint) sunWash(c, w, h)
        val t = typeNow()
        if (t != type) {
            type = t
            for (d in drops) resetDrop(d, true)
            for (f in flakes) resetFlake(f, true)
            if (t == "storm") { nextFlash = SystemClock.uptimeMillis() + r(8000f, 18000f).toLong(); flash = 0f }
        }
        if (t == "clear") return
        tint(c, w, h, t)
        val now = SystemClock.uptimeMillis()
        if (last == 0L) last = now
        val dt = ((now - last) / 1000f).coerceAtMost(0.1f)
        last = now
        val k = dt * 60f
        c.save(); c.scale(s, h / H)
        paint.style = Paint.Style.STROKE; paint.shader = null
        when (t) {
            "rain", "storm" -> {
                val wind = 0.16f
                for (d in drops) {
                    if (d.splash > 0f) {
                        d.splash += dt * 30f
                        paint.strokeWidth = 1.4f; paint.color = Color.argb((((1 - d.splash / d.splashMax) * d.alpha * 0.7f).coerceIn(0f, 1f) * 255).toInt(), 220, 235, 255)
                        rect.set(d.sx - d.splash * 1.5f, d.sy - d.splash * 0.6f, d.sx + d.splash * 1.5f, d.sy + d.splash * 0.6f)
                        c.drawOval(rect, paint)
                        if (d.splash >= d.splashMax) resetDrop(d, false)
                    } else {
                        d.y += d.speed * k; d.x += d.speed * k * wind
                        if (d.y >= H - r(10f, 80f)) { d.splash = 1f; d.sx = d.x; d.sy = d.y }
                        else {
                            paint.strokeWidth = d.width; paint.color = Color.argb((d.alpha * 255).toInt(), 205, 225, 255)
                            c.drawLine(d.x, d.y, d.x - d.len * wind, d.y - d.len, paint)
                        }
                    }
                }
                if (t == "storm") {
                    if (now >= nextFlash && flash <= 0f) { flash = r(0.20f, 0.35f); nextFlash = now + r(14000f, 32000f).toLong() }
                    if (flash > 0f) {
                        flash = (flash - dt * 1.4f).coerceAtLeast(0f)
                        paint.style = Paint.Style.FILL; paint.color = Color.argb((flash * 255).toInt(), 225, 235, 255)
                        c.drawRect(0f, 0f, W, H, paint)
                    }
                }
            }
            "snow" -> {
                paint.style = Paint.Style.FILL
                for (f in flakes) {
                    f.wob += f.wobSpeed * k; f.y += f.vy * k; f.x += (sin(f.wob) * 0.75f + f.vx) * k
                    if (f.y > H + 10 || f.x < -20 || f.x > W + 20) resetFlake(f, false)
                    paint.color = Color.argb((f.alpha * 255).toInt(), 245, 250, 255)
                    c.drawCircle(f.x, f.y, f.r, paint)
                }
            }
            "fog" -> {
                paint.style = Paint.Style.FILL
                for (f in fogs) {
                    f.x += f.speed * k
                    if (f.speed > 0 && f.x - f.r > W) f.x = -f.r
                    if (f.speed < 0 && f.x + f.r < 0) f.x = W + f.r
                    val a = (f.alpha * 255).toInt()
                    paint.shader = RadialGradient(f.x, f.y, f.r, intArrayOf(Color.argb(a, 220, 230, 240), Color.argb((a * 0.45f).toInt(), 220, 230, 240), Color.argb(0, 220, 230, 240)),
                        floatArrayOf(0.1f, 0.6f, 1f), Shader.TileMode.CLAMP)
                    c.drawRect(f.x - f.r, f.y - f.r, f.x + f.r, f.y + f.r, paint)
                }
                paint.shader = null
            }
        }
        paint.shader = null; paint.style = Paint.Style.FILL
        c.restore()
        frame()   // slow rain needs no more than about 25 frames a second
    }

    /** Milliseconds the home should wait before drawing the next frame of weather (0 when nothing moves). */
    fun frameDelayMs(): Long = if (type == "clear") 0L else 40L

    private fun tint(c: Canvas, w: Int, h: Int, t: String) {
        val (a, b) = when (t) {
            "rain" -> argb(0.18f, 16, 26, 42) to argb(0.28f, 10, 18, 30)
            "snow" -> argb(0.10f, 220, 235, 255) to argb(0.16f, 180, 205, 235)
            "fog" -> argb(0.18f, 200, 215, 225) to argb(0.25f, 185, 200, 215)
            else -> argb(0.28f, 12, 16, 28) to argb(0.38f, 8, 10, 20)
        }
        gradient(c, w, h, a, b)
    }

    private fun argb(a: Float, r: Int, g: Int, b: Int) = Color.argb((a * 255).toInt(), r, g, b)

    private fun gradient(c: Canvas, w: Int, h: Int, top: Int, bottom: Int) {
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null
    }

    /** The sun's colour wash, straight from ambient-weather.js's table (altitude in degrees, morning or evening). */
    private fun sunWash(c: Canvas, w: Int, h: Int) {
        val pos = if (weather.hasPlace) Solar.at(prefs.getFloat("lat", 0f).toDouble(), prefs.getFloat("lon", 0f).toDouble(), System.currentTimeMillis())
                  else Solar.byHour(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
        val am = pos.hourAngle < 0
        val alt = pos.alt
        val top: Int; val bot: Int
        when {
            alt < -12 -> { top = argb(0.32f, 8, 12, 24); bot = argb(0.45f, 5, 8, 18) }
            alt < -4 -> if (am) { top = argb(0.28f, 24, 18, 48); bot = argb(0.22f, 45, 26, 60) } else { top = argb(0.32f, 35, 18, 55); bot = argb(0.38f, 14, 18, 42) }
            alt < 2 -> if (am) { top = argb(0.18f, 255, 130, 90); bot = argb(0.22f, 40, 20, 60) } else { top = argb(0.22f, 245, 100, 65); bot = argb(0.28f, 30, 18, 55) }
            alt < 12 -> if (am) { top = argb(0.15f, 255, 180, 105); bot = 0 } else { top = argb(0.18f, 255, 150, 50); bot = 0 }
            else -> { top = argb(0.04f * 0.5f, 255, 255, 255); bot = 0 }   // midday: crisp light, at half strength
        }
        gradient(c, w, h, top, bot)
    }
}
