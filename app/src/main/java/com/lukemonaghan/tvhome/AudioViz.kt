package com.lukemonaghan.tvhome

import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow

/**
 * The real equaliser for the music view: the spectrum of whatever the TV is playing, taken from Android's Visualizer on the
 * output mix. It never records the microphone or keeps any sound; Android just files the permission under RECORD_AUDIO.
 * Needs that permission (tools/install.sh grants it over ADB). Without it, or if this TV's audio does not allow it, [live]
 * stays false and the music view moves its bars on a gentle pattern instead. Runs only while the music view is open and playing.
 */
class AudioViz(private val ctx: Context) {
    val bands = FloatArray(BANDS)
    @Volatile var live = false; private set
    private var viz: Visualizer? = null
    private var edges: IntArray? = null

    fun start() {
        if (viz != null) return
        if (ctx.checkSelfPermission("android.permission.RECORD_AUDIO") != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            val v = Visualizer(0)
            v.enabled = false
            v.captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(512)
            val n = v.captureSize / 2
            // log-spaced bins from about 60 Hz up: bin 1 .. n
            val e = IntArray(BANDS + 1) { i -> (1 + (n - 1) * (i.toFloat() / BANDS).pow(2.0f)).toInt().coerceIn(1, n) }
            for (i in 1..BANDS) if (e[i] <= e[i - 1]) e[i] = (e[i - 1] + 1).coerceAtMost(n)
            edges = e
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(vis: Visualizer?, wave: ByteArray?, rate: Int) {}
                override fun onFftDataCapture(vis: Visualizer?, fft: ByteArray?, rate: Int) { if (fft != null) take(fft) }
            }, Visualizer.getMaxCaptureRate() / 2, false, true)
            v.enabled = true
            viz = v; live = true
        }.onFailure { stop() }
    }

    private fun take(fft: ByteArray) {
        val e = edges ?: return
        for (b in 0 until BANDS) {
            var peak = 0f
            for (k in e[b] until e[b + 1].coerceAtLeast(e[b] + 1)) {
                val i = 2 * k
                if (i + 1 >= fft.size) break
                val m = hypot(fft[i].toFloat(), fft[i + 1].toFloat())
                if (m > peak) peak = m
            }
            val db = 20f * log10(peak + 1f)               // 0 .. about 45
            val level = ((db - 6f) / 36f).coerceIn(0f, 1f)
            val cur = bands[b]
            bands[b] = if (level > cur) cur + (level - cur) * 0.6f else cur + (level - cur) * 0.18f
        }
    }

    fun stop() {
        runCatching { viz?.enabled = false; viz?.release() }
        viz = null; live = false
        java.util.Arrays.fill(bands, 0f)
    }

    companion object { const val BANDS = 32 }
}
