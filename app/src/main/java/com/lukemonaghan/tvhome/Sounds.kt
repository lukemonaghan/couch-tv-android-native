package com.lukemonaghan.tvhome

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.view.SoundEffectConstants
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Sleep soundscapes made from nothing: rain, a fireplace, ocean waves and brown noise, synthesised the way
 * couch-tv's soundscape.js does (a pink noise bed through filters, a slow swell for the waves, random pops for
 * the fire). Mono, 22.05 kHz, written to one AudioTrack from one small thread, so it costs a few percent of one
 * core and no sound files. Nothing starts by itself: the home plays it only when the quick button or Home settings
 * says so, and stops it when the home is no longer on screen.
 */
class Soundscape {
    companion object { val TYPES = listOf("off", "rain", "fire", "waves", "brown"); const val RATE = 22050 }

    @Volatile private var type = "off"
    @Volatile private var target = 0.5f
    private var thread: Thread? = null
    @Volatile private var running = false

    fun current(): String = type
    fun volume(): Float = target

    fun play(t: String, vol: Float) {
        target = vol.coerceIn(0f, 1f)
        if (t == "off" || t !in TYPES) { stop(); return }
        if (running && type == t) return
        stop()
        type = t
        running = true
        thread = Thread({ run(t) }, "tvhome-soundscape").apply { priority = Thread.NORM_PRIORITY - 1; isDaemon = true; start() }
    }

    fun stop() {
        running = false
        type = "off"
        runCatching { thread?.join(400) }
        thread = null
    }

    fun setVolume(v: Float) { target = v.coerceIn(0f, 1f) }

    // ------------------------------------------------------------------ the synthesis

    private class Pink {   // Paul Kellet's refined pink noise filter, as in soundscape.js
        var b0 = 0f; var b1 = 0f; var b2 = 0f; var b3 = 0f; var b4 = 0f; var b5 = 0f; var b6 = 0f
        fun next(w: Float): Float {
            b0 = 0.99886f * b0 + w * 0.0555179f; b1 = 0.99332f * b1 + w * 0.0750759f
            b2 = 0.96900f * b2 + w * 0.1538520f; b3 = 0.86650f * b3 + w * 0.3104856f
            b4 = 0.55000f * b4 + w * 0.5329522f; b5 = -0.7616f * b5 - w * 0.0168980f
            val o = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362f) * 0.11f
            b6 = w * 0.115926f
            return o
        }
    }

    private class OnePole { var y = 0f
        fun lp(x: Float, fc: Float): Float { val a = exp(-2.0 * PI * fc / RATE).toFloat(); y = (1 - a) * x + a * y; return y } }

    /** A biquad band pass (RBJ cookbook), constant 0 dB peak. */
    private class Band {
        var x1 = 0f; var x2 = 0f; var y1 = 0f; var y2 = 0f
        var b0 = 0f; var b2 = 0f; var a1 = 0f; var a2 = 0f
        fun set(fc: Float, q: Float) {
            val w = 2.0 * PI * fc / RATE; val al = sin(w) / (2 * q); val a0 = 1 + al
            b0 = (al / a0).toFloat(); b2 = (-al / a0).toFloat(); a1 = (-2 * cos(w) / a0).toFloat(); a2 = ((1 - al) / a0).toFloat()
        }
        fun next(x: Float): Float { val y = b0 * x + b2 * x2 - a1 * y1 - a2 * y2; x2 = x1; x1 = x; y2 = y1; y1 = y; return y }
    }

    private fun run(t: String) {
        val chunk = 1024
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = runCatching {
            AudioTrack(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                maxOf(min, chunk * 4), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
        }.getOrNull() ?: run { running = false; type = "off"; return }
        val pink = Pink(); val lp1 = OnePole(); val lp2 = OnePole(); val bp = Band(); val pop = Band()
        bp.set(1100f, 1f)
        val rnd = java.util.Random()
        val buf = ShortArray(chunk)
        var gain = 0f                     // smoothed towards the volume (setTargetAtTime, 80 ms)
        var phase = 0.0                   // the swell's phase: 0.08 Hz, a 12.5 second wave
        var rainLevel = 0.85f; var rainGoal = 0.85f; var rainClock = 0
        var popLeft = 0; var popLen = 1; var popGain = 0f; var nextPop = 2000
        val gk = (1 - exp(-1.0 / (0.08 * RATE))).toFloat()
        try {
            track.play()
            while (running) {
                for (i in 0 until chunk) {
                    gain += (target - gain) * gk
                    val white = rnd.nextFloat() * 2 - 1
                    val n = pink.next(white)
                    var s = when (t) {
                        "brown" -> lp1.lp(n, 400f) * 3.2f
                        "rain" -> {
                            if (++rainClock >= RATE * 5 / 2) { rainClock = 0; rainGoal = 0.75f + rnd.nextFloat() * 0.35f }   // gusts
                            rainLevel += (rainGoal - rainLevel) * 0.00004f
                            bp.next(lp1.lp(n, 2400f)) * rainLevel * 5.5f
                        }
                        "waves" -> {
                            phase += 0.08 / RATE
                            val l = sin(2 * PI * phase).toFloat()
                            lp1.lp(n, 350f + 250f * l) * (0.6f + 0.35f * l) * 3.2f
                        }
                        else -> {   // fire: a warm rumble and random pops
                            var o = lp1.lp(n, 320f) * 0.7f * 3.2f
                            if (--nextPop <= 0) {
                                popLen = (0.02 + rnd.nextFloat() * 0.03).times(RATE).toInt().coerceAtLeast(60) * 3
                                popLeft = popLen; popGain = 0.4f + rnd.nextFloat() * 0.55f
                                pop.set(1800f + rnd.nextFloat() * 2200f, 3.5f)
                                nextPop = ((0.12 + rnd.nextFloat() * 0.38) * RATE).toInt()
                            }
                            if (popLeft > 0) { o += pop.next(white) * popGain * 3f * exp(-6.9f * (popLen - popLeft) / popLen.toFloat()); popLeft-- }
                            o
                        }
                    }
                    s *= gain
                    buf[i] = (s.coerceIn(-1f, 1f) * 30000f).toInt().toShort()
                }
                if (track.write(buf, 0, chunk) < 0) break
            }
        } finally {
            runCatching { track.stop() }; runCatching { track.release() }
        }
    }
}

/**
 * The sound on every arrow key: "system" is Android's own navigation sound (the one other TV homes play, which
 * follows the TV's "system sounds" switch), "soft" is a quiet synthesised tick like couch-tv's, "off" is none.
 */
class NavSound {
    private val pcm: ShortArray = run {
        val n = (Soundscape.RATE * 0.1).toInt()
        ShortArray(n) { i ->
            val t = i / Soundscape.RATE.toDouble()
            val f = 392.0 * Math.pow(294.0 / 392.0, (t / 0.075).coerceAtMost(1.0))   // 392 Hz sliding down to 294 Hz
            val env = if (t < 0.008) t / 0.008 else exp(-(t - 0.008) / 0.0285)       // 8 ms rise, a soft fall
            (sin(2 * PI * f * t) * env * 0.05 * 32767 * 3).toInt().toShort()
        }
    }
    private var track: AudioTrack? = null

    fun tick(view: View, effect: Int, style: String) {
        when (style) {
            "off" -> {}
            "soft" -> soft()
            else -> view.playSoundEffect(effect)
        }
    }

    private fun soft() {
        runCatching {
            val t = track ?: AudioTrack(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                AudioFormat.Builder().setSampleRate(Soundscape.RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                pcm.size * 2, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE).also { it.write(pcm, 0, pcm.size); track = it }
            t.stop(); t.reloadStaticData(); t.play()
        }
    }

    fun release() { runCatching { track?.release() }; track = null }

    companion object { const val DEFAULT = SoundEffectConstants.NAVIGATION_DOWN }
}
