package com.lukemonaghan.tvhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.concurrent.Executors

/**
 * App pictures for the tiles, drawn once at the size they are shown and kept in a small cache.
 * A TV app's banner fills the tile; an app with only an icon gets the icon centred on a dark tile.
 * Decoding runs on one background thread so the home never waits for it. No image library.
 */
class Icons(private val ctx: Context, private val onLoaded: () -> Unit) {
    // About 12 MB at most: a 1080p-sized banner tile is a few hundred KB, 10 to 40 tiles fit.
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pending = HashSet<String>()
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** The tile picture, or null while it loads (the view draws a plain tile and asks again after [onLoaded]). */
    fun get(app: AppItem, w: Int, h: Int): Bitmap? {
        val key = "${app.pkg}:$w"
        cache.get(key)?.let { return it }
        if (!pending.add(key)) return null
        worker.execute {
            val bmp = runCatching { draw(app, w, h) }.getOrNull()
            main.post {
                pending.remove(key)
                if (bmp != null) { cache.put(key, bmp); onLoaded() }
            }
        }
        return null
    }

    /** The app's square icon filling [size] pixels (the Night dock clips it to its rounded tile), or null while it loads. */
    fun square(app: AppItem, size: Int): Bitmap? {
        val key = "${app.pkg}:sq:$size"
        cache.get(key)?.let { return it }
        if (!pending.add(key)) return null
        worker.execute {
            val bmp = runCatching {
                val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val ic = app.ri.activityInfo.loadIcon(ctx.packageManager)
                ic.setBounds(0, 0, size, size); ic.draw(Canvas(out)); out
            }.getOrNull()
            main.post { pending.remove(key); if (bmp != null) { cache.put(key, bmp); onLoaded() } }
        }
        return null
    }

    private fun draw(app: AppItem, w: Int, h: Int): Bitmap {
        val pm = ctx.packageManager
        val ai = app.ri.activityInfo
        val banner: Drawable? = runCatching { ai.loadBanner(pm) }.getOrNull()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        if (banner != null) {
            banner.setBounds(0, 0, w, h)
            banner.draw(c)
        } else {
            c.drawColor(Color.parseColor("#22352F"))
            val icon = runCatching { ai.loadIcon(pm) }.getOrNull() ?: return out
            val s = (h * 0.62f).toInt()
            val r = Rect((w - s) / 2, (h - s) / 2, (w + s) / 2, (h + s) / 2)
            icon.setBounds(r)
            icon.draw(c)
        }
        return out
    }

    /** Drops everything: the TV is short of memory, or packages changed. */
    fun clear() { cache.evictAll() }
}
