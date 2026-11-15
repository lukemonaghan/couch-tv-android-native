package com.lukemonaghan.tvhome

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.lang.ref.SoftReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream

/**
 * The MilkDrop presets for the music view. None ship in the app: a TV has little disk, so the user picks what to download.
 *
 * The pack is projectM's "Cream of the Crop" (about 9,800 presets, 114 MB in all) from GitHub, pinned to one commit. A small
 * list of every preset (assets/presets/index.tsv, made by tools/make-preset-index.py) lets Settings browse it without the
 * network; a download fetches only the files asked for. Presets need the texture pack (3.5 MB, one zip).
 * Files live in filesDir/presets/<Category>/<Group>/<file>.milk and filesDir/textures. Remove deletes them.
 */
object PresetStore {
    const val PRESETS_SHA = "0180df21f5e0bd39b9060cc5de420ed2f1f9e509"
    const val TEXTURES_SHA = "6368812f27bc747b517218fbf89d21d59afce4d9"
    private const val RAW = "https://raw.githubusercontent.com/projectM-visualizer/presets-cream-of-the-crop/$PRESETS_SHA/"
    private const val TEX_ZIP = "https://github.com/projectM-visualizer/presets-milkdrop-texture-pack/archive/$TEXTURES_SHA.zip"
    private val RESERVE = 150L * 1024 * 1024   // never fill the TV: this much stays free

    class Group(val cat: String, val name: String, val files: Array<String>, val sizes: IntArray) {
        val bytes get() = sizes.sumOf { it.toLong() }
        fun path(i: Int) = "$cat/$name/${files[i]}"
    }
    class Index(val cats: List<String>, val groups: Map<String, List<Group>>, val starter: List<String>) {
        fun count(cat: String) = groups[cat].orEmpty().sumOf { it.files.size }
        fun bytes(cat: String) = groups[cat].orEmpty().sumOf { it.bytes }
    }

    /** What a download is doing: [done] of [total] files. */
    class Task(val key: String, val label: String, val total: Int) { val done = AtomicInteger(); @Volatile var cancelled = false; @Volatile var error: String = ""; @Volatile var finished = false }

    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(3) { r -> Thread(r, "presets").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    val tasks = ConcurrentHashMap<String, Task>()
    @Volatile var onChange: (() -> Unit)? = null
    private var lastPing = 0L
    private var cache: SoftReference<Index>? = null
    private var installedCache: HashSet<String>? = null

    fun dir(ctx: Context) = File(ctx.filesDir, "presets")
    fun textures(ctx: Context) = File(ctx.filesDir, "textures")

    fun index(ctx: Context): Index {
        cache?.get()?.let { return it }
        val rows = ArrayList<Array<String>>(10000)
        ctx.assets.open("presets/index.tsv").bufferedReader().useLines { ls -> ls.forEach { l -> val p = l.split('\t'); if (p.size == 4) rows.add(arrayOf(p[0], p[1], p[2], p[3])) } }
        val byGroup = LinkedHashMap<String, ArrayList<Array<String>>>()
        for (r in rows) byGroup.getOrPut(r[0] + "/" + r[1]) { ArrayList() }.add(r)
        val groups = LinkedHashMap<String, ArrayList<Group>>()
        for ((_, rs) in byGroup) groups.getOrPut(rs[0][0]) { ArrayList() }.add(Group(rs[0][0], rs[0][1], Array(rs.size) { rs[it][2] }, IntArray(rs.size) { rs[it][3].toInt() }))
        val starter = ctx.assets.open("presets/starter.txt").bufferedReader().readLines().filter { it.isNotBlank() }
        return Index(groups.keys.toList(), groups, starter).also { cache = SoftReference(it) }
    }

    /** Lets go of the list when Settings closes. */
    fun release() { cache = null }

    // ------------------------------------------------------------------ what is on the TV

    /** Paths ("Category/Group/file.milk") of every installed preset. Cached until something changes. */
    fun installed(ctx: Context): Set<String> {
        installedCache?.let { return it }
        val out = HashSet<String>()
        val root = dir(ctx)
        root.listFiles()?.forEach { c -> if (c.isDirectory) c.listFiles()?.forEach { g -> if (g.isDirectory) g.list()?.forEach { f -> if (f.endsWith(".milk")) out.add("${c.name}/${g.name}/$f") } } }
        installedCache = out
        return out
    }

    fun texturesInstalled(ctx: Context) = (textures(ctx).list()?.size ?: 0) > 20

    /** The music view can show the visualiser: the engine is in the app, the textures and at least one preset are installed. */
    fun ready(ctx: Context) = ProjectM.available && texturesInstalled(ctx) && installed(ctx).isNotEmpty()

    // ------------------------------------------------------------------ favourites and turned-off presets (prefs "home": viz_fav, viz_off)

    fun favs(p: android.content.SharedPreferences): Set<String> = p.getStringSet("viz_fav", emptySet()) ?: emptySet()
    fun off(p: android.content.SharedPreferences): Set<String> = p.getStringSet("viz_off", emptySet()) ?: emptySet()
    fun setFav(p: android.content.SharedPreferences, paths: Collection<String>, on: Boolean) {
        val n = HashSet(favs(p)); if (on) n.addAll(paths) else n.removeAll(paths.toSet()); p.edit().putStringSet("viz_fav", n).apply()
    }
    /** Turns presets off (never played) or back on. */
    fun setOff(p: android.content.SharedPreferences, paths: Collection<String>, off: Boolean) {
        val n = HashSet(off(p)); if (off) n.addAll(paths) else n.removeAll(paths.toSet()); p.edit().putStringSet("viz_off", n).apply()
    }

    fun freeBytes(ctx: Context) = ctx.filesDir.usableSpace

    // ------------------------------------------------------------------ downloading

    private fun url(path: String) = RAW + path.split('/').joinToString("/") { Uri.encode(it) }

    /** Fetches [paths] (those not already here). Returns false if there is not room. */
    fun install(ctx: Context, key: String, label: String, paths: List<String>, bytes: Long): Boolean {
        val todo = paths.filter { it !in installed(ctx) }
        if (todo.isEmpty()) return true
        if (freeBytes(ctx) - RESERVE < bytes) return false
        val app = ctx.applicationContext
        val task = Task(key, label, todo.size)
        tasks[key] = task
        pool.execute {
            val next = AtomicInteger()
            val workers = (1..3).map { Thread {
                while (!task.cancelled) {
                    val i = next.getAndIncrement(); if (i >= todo.size) break
                    if (fetch(app, todo[i])) task.done.incrementAndGet() else { task.error = "A download failed. Check the network and try again."; task.cancelled = true }
                }
            }.apply { isDaemon = true; start() } }
            workers.forEach { it.join() }
            task.finished = true; installedCache = null
            main.post { ping(true) }
            main.postDelayed({ if (tasks[key] === task && task.error.isEmpty()) tasks.remove(key); ping(true) }, 2500L)
        }
        return true
    }

    private fun fetch(ctx: Context, path: String): Boolean {
        val dest = File(dir(ctx), path)
        if (dest.exists()) return true
        dest.parentFile?.mkdirs()
        val tmp = File(dest.path + ".part")
        for (attempt in 0..1) {
            try {
                val c = URL(url(path)).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 15_000
                if (c.responseCode != 200) { c.disconnect(); continue }
                c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o, 16 * 1024) } }
                c.disconnect()
                if (tmp.renameTo(dest)) { ping(false); return true }
            } catch (_: Exception) { tmp.delete() }
        }
        return false
    }

    /** The textures: one zip, only its textures/ folder is kept. */
    fun installTextures(ctx: Context): Boolean {
        if (freeBytes(ctx) - RESERVE < 12L * 1024 * 1024) return false
        val app = ctx.applicationContext
        val task = Task("textures", "Textures", 1); tasks["textures"] = task
        pool.execute {
            try {
                val c = URL(TEX_ZIP).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 20_000
                if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
                val out = textures(app); out.mkdirs()
                ZipInputStream(c.inputStream.buffered()).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        val i = e.name.indexOf("/textures/")
                        if (e.isDirectory || i < 0) continue
                        val name = e.name.substring(i + 10)
                        if (name.contains('/') || name.contains("..")) continue
                        File(out, name).outputStream().use { o -> z.copyTo(o, 16 * 1024) }
                    }
                }
                task.done.incrementAndGet()
            } catch (e: Exception) { task.error = "The textures did not download. Check the network and try again." }
            task.finished = true
            main.post { ping(true) }
            main.postDelayed({ if (tasks["textures"] === task && task.error.isEmpty()) tasks.remove("textures"); ping(true) }, 2500L)
        }
        return true
    }

    fun cancel(key: String) { tasks[key]?.cancelled = true; tasks.remove(key); installedCache = null; ping(true) }

    fun remove(ctx: Context, paths: Collection<String>) {
        val root = dir(ctx)
        for (p in paths) File(root, p).delete()
        // tidy empty group and category folders
        root.listFiles()?.forEach { c -> c.listFiles()?.forEach { g -> if (g.list().isNullOrEmpty()) g.delete() }; if (c.list().isNullOrEmpty()) c.delete() }
        installedCache = null; ping(true)
    }

    fun removeTextures(ctx: Context) { textures(ctx).deleteRecursively(); ping(true) }

    /** Tells Settings to redraw, at most a few times a second. */
    private fun ping(force: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastPing < 400) return
        lastPing = now
        main.post { onChange?.invoke() }
    }
}
