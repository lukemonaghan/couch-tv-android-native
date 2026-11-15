package com.lukemonaghan.tvhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The household's own photos: copied into the app's own files, turned the right way up and sized for the screen
 * (1920 x 1080 at most), so they show offline and cost one decode while they are on screen. They take turns with the
 * scenes (Night) and replace the Classic look's own pictures. Up to [MAX] are kept.
 *
 * Added from the TV's file picker (Android's "open a document" screen, where the TV has one) or from a web address,
 * which is downloaded once. Plain platform calls: BitmapFactory, HttpURLConnection. No image or network library.
 */
object OwnPhotos {
    const val MAX = 50
    private const val LONG_SIDE = 1920

    private fun dir(ctx: Context) = File(ctx.filesDir, "photos").apply { mkdirs() }

    fun list(ctx: Context): List<File> = dir(ctx).listFiles { f -> f.name.endsWith(".jpg") }?.sortedBy { it.name } ?: emptyList()

    fun remove(ctx: Context, name: String) { File(dir(ctx), name).delete() }

    /** Copies the chosen pictures in. Returns how many were added (call off the main thread). */
    fun addUris(ctx: Context, uris: List<Uri>): Int {
        var n = 0
        for (u in uris) {
            if (list(ctx).size >= MAX) break
            val ok = runCatching {
                val bytes = ctx.contentResolver.openInputStream(u)?.use { it.readBytes() } ?: return@runCatching false
                save(ctx, bytes, exifTurn(bytes))
            }.getOrDefault(false)
            if (ok) n++
        }
        return n
    }

    /** Downloads a photo (JPEG or PNG) from a web address. Returns an error line or null on success (call off the main thread). */
    fun addUrl(ctx: Context, url: String): String? {
        if (list(ctx).size >= MAX) return "Fifty photos is the most."
        val u = if (url.startsWith("http://", true) || url.startsWith("https://", true)) url else "https://$url"
        return runCatching {
            val cn = URL(u).openConnection() as HttpURLConnection
            cn.connectTimeout = 15000; cn.readTimeout = 20000
            try {
                if (cn.responseCode !in 200..299) return@runCatching "That address did not answer with a photo."
                val len = cn.contentLength
                if (len > 30_000_000) return@runCatching "That photo is too big."
                val bytes = cn.inputStream.use { it.readBytes() }
                if (save(ctx, bytes, exifTurn(bytes))) null else "That address is not a photo the TV can read."
            } finally { cn.disconnect() }
        }.getOrElse { "Could not load that address." }
    }

    private fun exifTurn(bytes: ByteArray): Int {
        if (Build.VERSION.SDK_INT < 24) return 0
        return runCatching {
            val o = android.media.ExifInterface(bytes.inputStream()).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)
            when (o) { 6 -> 90; 3 -> 180; 8 -> 270; else -> 0 }
        }.getOrDefault(0)
    }

    private fun save(ctx: Context, bytes: ByteArray, turn: Int): Boolean {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
        if (b.outWidth <= 0 || b.outHeight <= 0) return false
        var s = 1; while (maxOf(b.outWidth, b.outHeight) / (s * 2) >= LONG_SIDE) s *= 2
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: return false
        val m = Matrix()
        val big = maxOf(bmp.width, bmp.height)
        if (big > LONG_SIDE) { val k = LONG_SIDE.toFloat() / big; m.postScale(k, k) }
        if (turn != 0) m.postRotate(turn.toFloat())
        if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        val f = File(dir(ctx), "p${System.currentTimeMillis().toString(36)}${(Math.random() * 1296).toInt().toString(36)}.jpg")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        bmp.recycle()
        return true
    }
}

/** Implemented by the Activity that can show Android's file picker; the settings page finds it through the context. */
interface PhotoPicker { fun pickPhotos(): Boolean }
