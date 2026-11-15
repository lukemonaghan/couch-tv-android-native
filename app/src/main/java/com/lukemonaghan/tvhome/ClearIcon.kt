package com.lukemonaghan.tvhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * Clear app icons, after Apple's "clear" icon look (port of couch-tv's clearicon.js, #25): an app's own icon with its
 * background taken away, so only the glyph is left, drawn in white on a glass tile.
 *
 * How: the icon is drawn small (192 px); the colours along its edges are the background (one colour, or the steps of a
 * gradient); every pixel is scored by how far it is from the nearest of them, and that score is the glyph's alpha.
 * An icon that will not split cleanly (a photo, a glyph filling nearly all of it) is refused, and the home shows a grey
 * version of the icon instead. A logo that is itself a shape on a see-through square (YouTube's red box) keeps the shape,
 * with its mark cut out as a hole.
 *
 * Each result is a 192 x 192 ALPHA_8 mask (36 KB), kept in memory and in a file under cacheDir/clearicon, named with the
 * package's last update time so an updated app is worked out again. Work runs on one background thread.
 */
class ClearIcon(private val ctx: Context, private val onReady: () -> Unit) {
    private val mem = HashMap<String, Bitmap?>()          // null: refused
    private val pending = HashSet<String>()
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val dir = File(ctx.cacheDir, "clearicon").apply { mkdirs() }

    /** The glyph mask, or null while it is worked out or when the icon does not split (see [refused]). [banner]: Classic's landscape banner. */
    fun mask(app: AppItem, banner: Boolean): Bitmap? {
        val key = key(app, banner)
        if (mem.containsKey(key)) return mem[key]
        if (pending.add(key)) worker.execute {
            val m = runCatching { load(app, banner, key) }.getOrNull()
            main.post { pending.remove(key); mem[key] = m; onReady() }
        }
        return null
    }

    /** True once the icon is known not to split cleanly. */
    fun refused(app: AppItem, banner: Boolean): Boolean { val k = key(app, banner); return mem.containsKey(k) && mem[k] == null }

    fun clear() { mem.values.forEach { it?.recycle() }; mem.clear(); keys.clear() }

    private val keys = HashMap<String, String>()   // the home asks on every frame: the package's update time is read once
    private fun key(app: AppItem, banner: Boolean): String {
        val k = app.pkg + if (banner) "b" else "i"
        return keys.getOrPut(k) {
            val t = runCatching { ctx.packageManager.getPackageInfo(app.pkg, 0).lastUpdateTime }.getOrDefault(0L)
            "${app.pkg}-$t${if (banner) "b" else "i"}"
        }
    }

    private fun load(app: AppItem, banner: Boolean, key: String): Bitmap? {
        val f = File(dir, "$key.a8")
        if (f.exists()) {
            val bytes = f.readBytes()
            if (bytes.isEmpty()) return null
            if (bytes.size == N * N) return Bitmap.createBitmap(N, N, Bitmap.Config.ALPHA_8).also { it.copyPixelsFromBuffer(ByteBuffer.wrap(bytes)) }
        }
        val pm = ctx.packageManager
        val ai = app.ri?.activityInfo ?: return null   // a pinned TV input has no icon to cut out
        val d = (if (banner) runCatching { ai.loadBanner(pm) }.getOrNull() else null)
            ?: runCatching { ai.loadIcon(pm) }.getOrNull()
        if (d == null) { f.writeBytes(ByteArray(0)); return null }
        // fit, centred, in the N x N square (landscape banners keep their shape)
        val iw = if (d.intrinsicWidth > 0) d.intrinsicWidth else N
        val ih = if (d.intrinsicHeight > 0) d.intrinsicHeight else N
        val s = minOf(N.toFloat() / iw, N.toFloat() / ih)
        val dw = iw * s; val dh = ih * s
        val ox = (N - dw) / 2f; val oy = (N - dh) / 2f
        val bmp = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888)
        d.setBounds(Math.round(ox), Math.round(oy), Math.round(ox + dw), Math.round(oy + dh)); d.draw(Canvas(bmp))
        val px = IntArray(N * N); bmp.getPixels(px, 0, N, 0, 0, N, N); bmp.recycle()
        val alpha = cut(px, ox, oy, dw, dh)
        if (alpha == null) { f.writeBytes(ByteArray(0)); return null }
        f.writeBytes(alpha)
        return Bitmap.createBitmap(N, N, Bitmap.Config.ALPHA_8).also { it.copyPixelsFromBuffer(ByteBuffer.wrap(alpha)) }
    }

    // ------------------------------------------------------------------ the cut (clearicon.js, cut and shapeCut)

    private class Lab(val l: Float, val a: Float, val b: Float)
    private fun dist(p: Lab, q: Lab): Float { val x = p.l - q.l; val y = p.a - q.a; val z = p.b - q.b; return Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat() }

    private fun lab(r0: Int, g0: Int, b0: Int): Lab {
        fun lin(v: Int): Double { val c = v / 255.0; return if (c > 0.04045) Math.pow((c + 0.055) / 1.055, 2.4) else c / 12.92 }
        val r = lin(r0); val g = lin(g0); val b = lin(b0)
        val x = (r * 0.4124 + g * 0.3576 + b * 0.1805) / 0.95047
        val y = r * 0.2126 + g * 0.7152 + b * 0.0722
        val z = (r * 0.0193 + g * 0.1192 + b * 0.9505) / 1.08883
        fun f(t: Double) = if (t > 0.008856) Math.cbrt(t) else 7.787 * t + 16.0 / 116
        val fx = f(x); val fy = f(y); val fz = f(z)
        return Lab((116 * fy - 16).toFloat(), (500 * (fx - fy)).toFloat(), (200 * (fy - fz)).toFloat())
    }

    /** The alpha mask (N * N bytes) or null when the icon does not split cleanly. */
    private fun cut(d: IntArray, ox: Float, oy: Float, dw: Float, dh: Float): ByteArray? {
        fun A(i: Int) = d[i] ushr 24
        fun R(i: Int) = (d[i] shr 16) and 255
        fun G(i: Int) = (d[i] shr 8) and 255
        fun B(i: Int) = d[i] and 255
        val band = maxOf(3, Math.round(minOf(dw, dh) * 0.04f))
        val inner = Math.round(minOf(dw, dh) * 0.12f)
        val x0 = Math.round(ox); val y0 = Math.round(oy); val x1 = Math.round(ox + dw) - 1; val y1 = Math.round(oy + dh) - 1
        fun edge(px: Int, py: Int) = minOf(minOf(px - x0, x1 - px), minOf(py - y0, y1 - py))

        // a logo that is a shape on a see-through square: keep the shape, cut the other colours out as holes
        var clear = 0; var n = 0
        for (py in y0..y1) for (px in x0..x1) { if (edge(px, py) >= band) continue; n++; if (A(py * N + px) < 128) clear++ }
        if (n > 0 && clear.toFloat() / n >= 0.6f) return shapeCut(d)

        // the background: colours in a band just inside the edges, quantised, the common ones kept
        val bins = HashMap<Int, Int>(); var total = 0
        for (py in y0..y1) for (px in x0..x1) {
            val e = edge(px, py); if (e < 1 || e > band) continue
            val i = py * N + px; if (A(i) < 200) continue
            val k = ((R(i) shr 3) shl 10) or ((G(i) shr 3) shl 5) or (B(i) shr 3)
            bins[k] = (bins[k] ?: 0) + 1; total++
        }
        if (total < 50) return null
        val pal = ArrayList<Lab>()
        for ((k, v) in bins) { if (v.toFloat() / total < 0.004f) continue; pal.add(lab(((k shr 10) and 31) * 8 + 4, ((k shr 5) and 31) * 8 + 4, (k and 31) * 8 + 4)) }
        if (pal.isEmpty() || pal.size > 600) return null

        // each pixel's distance to the nearest background colour
        val dbg = FloatArray(N * N); val memo = HashMap<Int, Float>()
        for (j in 0 until N * N) {
            if (A(j) < 128) { dbg[j] = -1f; continue }
            val k = ((R(j) shr 2) shl 12) or ((G(j) shr 2) shl 6) or (B(j) shr 2)
            dbg[j] = memo.getOrPut(k) { val L = lab(R(j), G(j), B(j)); var best = 1e9f; for (p in pal) { val q = dist(L, p); if (q < best) best = q }; best }
        }
        // the glyph's own colours, most used first; a pale glow near a white glyph needs a looser limit
        val gpal = ArrayList<Lab>()
        for (lim in floatArrayOf(T1, 28f, 22f)) {
            val gb = HashMap<Int, Int>(); var gt = 0
            for (k in 0 until N * N) {
                if (dbg[k] <= lim) continue
                val gk = ((R(k) shr 4) shl 8) or ((G(k) shr 4) shl 4) or (B(k) shr 4)
                gb[gk] = (gb[gk] ?: 0) + 1; gt++
            }
            if (gt < N * N * 0.004f) continue
            gb.entries.sortedByDescending { it.value }.forEach { (g, cnt) ->
                if (cnt.toFloat() / gt < 0.03f) return@forEach
                gpal.add(lab(((g shr 8) and 15) * 16 + 8, ((g shr 4) and 15) * 16 + 8, (g and 15) * 16 + 8))
            }
            break
        }
        if (gpal.isEmpty() || gpal.size > 12) return null
        val mainSet = BooleanArray(gpal.size) { dist(gpal[it], gpal[0]) < 30f }

        // alpha: which side a pixel is nearer, the background or the glyph (soft at the edges)
        val out = ByteArray(N * N); val memo2 = HashMap<Int, FloatArray>()
        var sum = 0f; var cnt = 0; var edgeHits = 0; var edgeN = 0
        for (j in 0 until N * N) {
            var a = 0f; val db0 = dbg[j]
            if (db0 > T0 * 0.5f) {
                val k = ((R(j) shr 2) shl 12) or ((G(j) shr 2) shl 6) or (B(j) shr 2)
                val dg = memo2.getOrPut(k) {
                    val L = lab(R(j), G(j), B(j)); var bi = 0; var bd = 1e9f
                    for (m in gpal.indices) { val q = dist(L, gpal[m]); if (q < bd) { bd = q; bi = m } }
                    floatArrayOf(bd, bi.toFloat())
                }
                val r = db0 / (db0 + dg[0] + 1e-6f)
                a = ((r - 0.36f) / 0.28f).coerceIn(0f, 1f)
                a = a * a * (3 - 2 * a)
                if (!mainSet[dg[1].toInt()]) a *= 0.42f
            }
            out[j] = Math.round(a * 255).toByte()
            val ee = edge(j % N, j / N)
            if (ee >= inner) { sum += a; cnt++ } else if (ee in 0 until band) { edgeN++; if (a > 0.5f) edgeHits++ }
        }
        val cover = if (cnt > 0) sum / cnt else 0f
        if (cover < 0.006f || cover > 0.62f || (edgeN > 0 && edgeHits.toFloat() / edgeN > 0.25f)) return null
        return out
    }

    private fun shapeCut(d: IntArray): ByteArray? {
        val bins = HashMap<Int, Int>(); var best = -1; var bestN = 0
        for (j in 0 until N * N) {
            if ((d[j] ushr 24) < 200) continue
            val k = (((d[j] shr 16) and 255 shr 4) shl 8) or (((d[j] shr 8) and 255 shr 4) shl 4) or ((d[j] and 255) shr 4)
            val c = (bins[k] ?: 0) + 1; bins[k] = c
            if (c > bestN) { bestN = c; best = k }
        }
        if (best < 0) return null
        val D = lab(((best shr 8) and 15) * 16 + 8, ((best shr 4) and 15) * 16 + 8, (best and 15) * 16 + 8)
        val out = ByteArray(N * N); val memo = HashMap<Int, Float>(); var sum = 0f
        for (j in 0 until N * N) {
            val r = (d[j] shr 16) and 255; val g = (d[j] shr 8) and 255; val b = d[j] and 255
            val k = ((r shr 2) shl 12) or ((g shr 2) shl 6) or (b shr 2)
            val dd = memo.getOrPut(k) { dist(lab(r, g, b), D) }
            val a = ((45f - dd) / 25f).coerceIn(0f, 1f) * ((d[j] ushr 24) / 255f)
            out[j] = Math.round(a * 255).toByte(); sum += a
        }
        val cover = sum / (N * N)
        return if (cover < 0.05f || cover > 0.95f) null else out
    }

    private companion object {
        const val N = 192
        const val T0 = 16f
        const val T1 = 42f
    }
}
