package com.lukemonaghan.tvhome

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

/**
 * The "Music visualiser" page of Home settings: how the MilkDrop background behaves, and the preset browser.
 * Browsing goes Category, then Group, then single presets. OK on a category or group row opens it, and the first row of
 * each level downloads or removes everything in it. Nothing is installed until asked: [PresetStore] fetches only what is picked.
 * Prefs (home): np_viz, viz_q, viz_fps, viz_secs, viz_bright (20 to 100), viz_autohide, viz_slow (see Visualiser.kt).
 */
object VizSettings {
    private var cat: String? = null
    private var group: String? = null
    private var msg = ""
    /** SettingsScreen sets this to put the focus back on the first row when the level changes. */
    var onNavigate: (() -> Unit)? = null

    private val QUALITY = listOf("Fast (640 x 360)", "Normal (960 x 540)", "Sharp (1280 x 720)")
    private val SECS = listOf(0, 20, 45, 90, 180)
    private val HIDE = listOf(0, 5, 10, 20, 40)

    fun reset() { cat = null; group = null; msg = "" }

    /** Back: one level up. False when the page is at its top. */
    fun back(): Boolean {
        if (group != null) { group = null; msg = ""; onNavigate?.invoke(); return true }
        if (cat != null) { cat = null; msg = ""; onNavigate?.invoke(); return true }
        return false
    }

    private fun mb(b: Long) = if (b >= 10L * 1024 * 1024) "${b / (1024 * 1024)} MB" else String.format(Locale.US, "%.1f MB", b / 1048576.0)
    private fun <T> next(l: List<T>, cur: T): T = l[(l.indexOf(cur).let { if (it < 0) -1 else it } + 1) % l.size]
    private fun go(c: String?, g: String?) { cat = c; group = g; msg = ""; onNavigate?.invoke() }

    fun rows(ctx: Context, p: SharedPreferences): List<IdleSettings.Item> {
        val out = ArrayList<IdleSettings.Item>()
        if (!ProjectM.available) {
            out += IdleSettings.Item("Not in this build", "The visualiser engine (projectM) is not in this copy of the app. Build it with tools/build-native.sh")
            return out
        }
        val index = PresetStore.index(ctx)
        val installed = PresetStore.installed(ctx)
        val skipped = p.getStringSet("viz_slow", emptySet()) ?: emptySet()
        if (msg.isNotEmpty()) out += IdleSettings.Item(msg, "")

        fun fmtTask(key: String): String? = PresetStore.tasks[key]?.let { t ->
            if (t.error.isNotEmpty()) "Failed" else if (t.finished) "Done" else if (t.total == 1) "Downloading" else "${t.done.get()} of ${t.total}"
        }
        // one row that installs, cancels or removes a set of presets
        fun bulk(title: String, key: String, paths: List<String>, bytes: Long): IdleSettings.Item {
            val have = paths.count { it in installed }
            val t = PresetStore.tasks[key]
            val running = t != null && !t.finished
            return when {
                running -> IdleSettings.Item(title, "OK stops the download", fmtTask(key) ?: "") { PresetStore.cancel(key) }
                t != null && t.error.isNotEmpty() -> IdleSettings.Item(title, t.error, "Try again") { PresetStore.tasks.remove(key); if (!PresetStore.install(ctx, key, title, paths, bytes)) msg = "Not enough free space for ${mb(bytes)}" }
                have == paths.size -> IdleSettings.Item(title, "All ${paths.size} installed (${mb(bytes)}). OK removes them", "Installed") { PresetStore.remove(ctx, paths) }
                else -> IdleSettings.Item(title, "${paths.size - have} to download (${mb(bytes * (paths.size - have) / paths.size)})" + if (have > 0) ", $have already here" else "", "Download") {
                    if (!PresetStore.install(ctx, key, title, paths, bytes * (paths.size - have) / paths.size)) msg = "Not enough free space for ${mb(bytes)}"
                }
            }
        }

        val c = cat; val g = group
        if (c == null) {
            out += IdleSettings.Item("MUSIC VISUALISER", header = true)
            out += IdleSettings.Item("Show the visualiser", "MilkDrop visuals behind the music view, moving with the music", check = p.getBoolean("np_viz", true)) { p.edit().putBoolean("np_viz", !p.getBoolean("np_viz", true)).apply() }
            out += IdleSettings.Item("Picture quality", "Drawn smaller and scaled up. Lower is smoother on an older TV", QUALITY[p.getInt("viz_q", 1).coerceIn(0, 2)]) { p.edit().putInt("viz_q", (p.getInt("viz_q", 1) + 1) % 3).apply() }
            out += IdleSettings.Item("Frame rate", "30 is easier on the TV", "${p.getInt("viz_fps", 30)} per second") { p.edit().putInt("viz_fps", if (p.getInt("viz_fps", 30) == 30) 60 else 30).apply() }
            out += IdleSettings.Item("New preset every", "Left and right change it by hand", SECS.let { s -> val v = p.getInt("viz_secs", 45); if (v == 0) "Only by hand" else if (v >= 60) "${v / 60} min${if (v % 60 != 0) " ${v % 60} s" else ""}" else "$v seconds" }) { p.edit().putInt("viz_secs", next(SECS, p.getInt("viz_secs", 45))).apply() }
            out += IdleSettings.Item("Brightness", "How bright the visualiser is. With the buttons hidden, up and down change it", "${p.getInt("viz_bright", 100)}%") { val b = p.getInt("viz_bright", 100); p.edit().putInt("viz_bright", if (b <= 20) 100 else b - 10).apply() }
            out += IdleSettings.Item("Hide the buttons after", "Fades the words and buttons so only the visualiser shows. Any key brings them back", p.getInt("viz_autohide", 0).let { if (it == 0) "Never" else "$it seconds" }) { p.edit().putInt("viz_autohide", next(HIDE, p.getInt("viz_autohide", 0))).apply() }
            out += IdleSettings.Item("Skipped presets", "Ones that ran too slowly or failed on this TV. OK lets them try again", "${skipped.size}") { p.edit().remove("viz_slow").apply() }

            out += IdleSettings.Item("PRESETS", header = true)
            val total = index.cats.sumOf { index.count(it) }
            out += IdleSettings.Item("On this TV", "Free space ${mb(PresetStore.freeBytes(ctx))}. Presets are MilkDrop files from projectM's Cream of the Crop pack (${total} in all)", "${installed.size} presets")
            val tex = PresetStore.tasks["textures"]
            out += when {
                tex != null && !tex.finished -> IdleSettings.Item("Textures", "Pictures the presets use. OK stops the download", "Downloading") { PresetStore.cancel("textures") }
                tex != null && tex.error.isNotEmpty() -> IdleSettings.Item("Textures", tex.error, "Try again") { PresetStore.tasks.remove("textures"); if (!PresetStore.installTextures(ctx)) msg = "Not enough free space" }
                PresetStore.texturesInstalled(ctx) -> IdleSettings.Item("Textures", "Installed. Every preset needs them. OK removes them", "Installed") { PresetStore.removeTextures(ctx) }
                else -> IdleSettings.Item("Textures", "Needed first: the pictures the presets use (3.5 MB)", "Download") { if (!PresetStore.installTextures(ctx)) msg = "Not enough free space" }
            }
            val starter = index.starter
            val sBytes = starter.sumOf { s -> val (cc, gg, ff) = s.split('/', limit = 3); index.groups[cc]?.firstOrNull { it.name == gg }?.let { gr -> gr.sizes[gr.files.indexOf(ff).coerceAtLeast(0)].toLong() } ?: 0L }
            out += bulk("Starter pack (${starter.size} favourites)", "starter", starter, sBytes)

            out += IdleSettings.Item("BROWSE THE COLLECTION", header = true)
            for (cc in index.cats) {
                val paths = index.groups[cc].orEmpty().flatMap { gr -> gr.files.indices.map { gr.path(it) } }
                val have = paths.count { it in installed }
                val busy = PresetStore.tasks.keys.any { it.startsWith("c:$cc/") || it == "c:$cc" }
                out += IdleSettings.Item(cc, "${index.count(cc)} presets, ${mb(index.bytes(cc))}", if (busy) "Downloading" else if (have > 0) "$have installed" else "") { go(cc, null) }
            }
            if (installed.isNotEmpty()) out += IdleSettings.Item("Remove every preset", "Frees the space. The textures stay", "") { PresetStore.remove(ctx, installed.toList()); p.edit().remove("viz_slow").apply() }
            return out
        }

        val groups = index.groups[c].orEmpty()
        if (g == null) {
            out += IdleSettings.Item(c.uppercase(Locale.ROOT), header = true)
            val all = groups.flatMap { gr -> gr.files.indices.map { gr.path(it) } }
            out += bulk("Everything in $c", "c:$c", all, index.bytes(c))
            out += IdleSettings.Item("GROUPS", header = true)
            for (gr in groups) {
                val have = gr.files.indices.count { gr.path(it) in installed }
                out += IdleSettings.Item(gr.name, "${gr.files.size} presets, ${mb(gr.bytes)}", if (have > 0) "$have installed" else "") { go(c, gr.name) }
            }
            return out
        }
        val gr = groups.firstOrNull { it.name == g } ?: run { group = null; return out }
        out += IdleSettings.Item("${c.uppercase(Locale.ROOT)}  ›  ${gr.name.uppercase(Locale.ROOT)}", header = true)
        out += bulk("Everything in ${gr.name}", "c:$c/${gr.name}", gr.files.indices.map { gr.path(it) }, gr.bytes)
        out += IdleSettings.Item("PRESETS", header = true)
        for (i in gr.files.indices) {
            val path = gr.path(i); val on = path in installed
            val name = gr.files[i].removeSuffix(".milk")
            val kb = "${(gr.sizes[i] + 512) / 1024} KB"
            out += IdleSettings.Item(name, if (path in skipped) "Skipped: too slow or failed on this TV. $kb" else kb, check = on) {
                if (on) PresetStore.remove(ctx, listOf(path))
                else if (!PresetStore.install(ctx, "p:$path", name, listOf(path), gr.sizes[i].toLong())) msg = "Not enough free space"
            }
        }
        return out
    }
}
