package com.lukemonaghan.tvhome

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

/**
 * The "Music visualiser" page of Home settings: how the MilkDrop background behaves, and the preset browser.
 * Browsing goes Category, then Group, then single presets. OK on a category or group row opens it, and the first row of
 * each level downloads or removes everything in it. Nothing is installed until asked: [PresetStore] fetches only what is picked.
 * Prefs (home): np_viz, np_lyrics_hidden, viz_q, viz_fps, viz_secs, viz_bright (20 to 100), viz_autohide, viz_slow (see Visualiser.kt).
 */
object VizSettings {
    /** "" is the top of the page; "mine" browses what is installed, "favs" lists the favourites, "skipped" the presets that were skipped as too slow, "get" browses the whole collection to download. */
    private var area = ""
    private var cat: String? = null
    private var group: String? = null
    private var msg = ""
    /** SettingsScreen sets this to put the focus back on the first row when the level changes. */
    var onNavigate: (() -> Unit)? = null
    /** True while the live preview is up (the home takes it away if Settings is left). */
    @Volatile var previewShown = false

    private val QUALITY = listOf("Fast (640 x 360)", "Normal (960 x 540)", "Sharp (1280 x 720)")
    private val SECS = listOf(0, 20, 45, 90, 180)
    private val HIDE = listOf(0, 5, 10, 20, 40)

    fun reset() { area = ""; cat = null; group = null; msg = ""; previewShown = false }

    /** Back: one level up. False when the page is at its top. */
    fun back(): Boolean {
        if (group != null) { group = null; msg = ""; onNavigate?.invoke(); return true }
        if (cat != null) { cat = null; msg = ""; onNavigate?.invoke(); return true }
        if (area != "") { area = ""; msg = ""; onNavigate?.invoke(); return true }
        return false
    }

    private fun mb(b: Long) = if (b >= 10L * 1024 * 1024) "${b / (1024 * 1024)} MB" else String.format(Locale.US, "%.1f MB", b / 1048576.0)
    private fun <T> next(l: List<T>, cur: T): T = l[(l.indexOf(cur).let { if (it < 0) -1 else it } + 1) % l.size]
    private fun go(a: String, c: String?, g: String?) { area = a; cat = c; group = g; msg = ""; onNavigate?.invoke() }

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
                have == paths.size -> {
                    val doRemove = { PresetStore.remove(ctx, paths); Unit }
                    if (title.startsWith("Everything")) IdleSettings.Item(title, "All ${paths.size} installed (${mb(bytes)}). OK removes them, after asking", "Installed", menu = listOf("Cancel" to {}, "Yes, remove all ${paths.size}" to doRemove))
                    else IdleSettings.Item(title, "All ${paths.size} installed (${mb(bytes)}). OK removes them", "Installed") { doRemove() }
                }
                else -> {
                    val need = bytes * (paths.size - have) / paths.size
                    val doGet = { if (!PresetStore.install(ctx, key, title, paths, need)) msg = "Not enough free space for ${mb(bytes)}"; Unit }
                    val note = "${paths.size - have} to download (${mb(need)})" + if (have > 0) ", $have already here" else ""
                    if (title.startsWith("Everything")) IdleSettings.Item(title, "$note. OK downloads them, after asking", "Download", menu = listOf("Cancel" to {}, "Yes, download ${paths.size - have} (${mb(need)})" to doGet))
                    else IdleSettings.Item(title, note, "Download") { doGet() }
                }
            }
        }

        val off = PresetStore.off(p); val favs = PresetStore.favs(p)
        fun kb(bytes: Int) = "${(bytes + 512) / 1024} KB"
        fun nameOf(path: String) = path.substringAfterLast('/').removeSuffix(".milk")

        // one installed preset: OK opens its choices (favourite, turn off, remove); the picture shows while it is focused
        fun mine(path: String, size: Int, where: String = ""): IdleSettings.Item {
            val isOff = path in off; val isFav = path in favs
            val note = listOf(where, if (isOff) "Turned off, never played" else "", if (path in skipped) "Skipped: too slow or failed on this TV" else "", if (size > 0) kb(size) else "").filter { it.isNotEmpty() }.joinToString("  ·  ")
            val menu = listOf(
                (if (isFav) "Remove from favourites" else "Add to favourites") to { PresetStore.setFav(p, listOf(path), !isFav); PresetStore.onChange?.invoke(); Unit },
                (if (isOff) "Turn on" else "Turn off") to { PresetStore.setOff(p, listOf(path), !isOff); PresetStore.onChange?.invoke(); Unit },
                "Remove from this TV" to { PresetStore.remove(ctx, listOf(path)); PresetStore.setFav(p, listOf(path), false); PresetStore.setOff(p, listOf(path), false); Unit },
            )
            return IdleSettings.Item(nameOf(path), note, if (isFav) "★" else "", check = !isOff, preview = path, menu = menu)
        }
        // one row that turns a whole set of installed presets on or off
        fun allRow(title: String, paths: List<String>): IdleSettings.Item {
            val n = paths.count { it !in off }
            val allOn = n == paths.size
            val ask = listOf("Cancel" to {}, (if (allOn) "Yes, turn all ${paths.size} off" else "Yes, turn all ${paths.size} on") to { PresetStore.setOff(p, paths, allOn); Unit })
            val nFav = paths.count { it in favs }
            val more = ArrayList<Pair<String, () -> Unit>>()
            if (nFav < paths.size) more += "Add all ${paths.size} to favourites" to { PresetStore.setFav(p, paths, true); Unit }
            if (nFav > 0) more += "Remove all from favourites" to { PresetStore.setFav(p, paths, false); Unit }
            return IdleSettings.Item(title, "OK turns them all on or off, after asking. Hold OK for favourites", if (allOn) "" else "$n of ${paths.size} on", check = allOn, menu = ask, holdMenu = more)
        }
        fun sizeOf(path: String): Int {
            val (cc, gg, ff) = path.split('/', limit = 3)
            return index.groups[cc]?.firstOrNull { it.name == gg }?.let { gr -> gr.sizes[gr.files.indexOf(ff).coerceAtLeast(0)] } ?: 0
        }

        val a = area; val c = cat; val g = group

        if (a == "skipped") {
            out += IdleSettings.Item("SKIPPED PRESETS", header = true)
            val list = skipped.sortedBy { nameOf(it).lowercase(Locale.ROOT) }
            if (list.isEmpty()) out += IdleSettings.Item("None skipped", "A preset lands here when it runs too slowly or fails on this TV")
            else {
                out += IdleSettings.Item("Let all ${list.size} try again", "They are played again, and skipped again if they still fail", "") { p.edit().remove("viz_slow").apply(); PresetStore.onChange?.invoke() }
                out += IdleSettings.Item("PRESETS", header = true)
                for (path in list) out += IdleSettings.Item(nameOf(path), "OK lets it try again  ·  " + path.substringBeforeLast('/').replace("/", "  ›  "), preview = path) {
                    p.edit().putStringSet("viz_slow", HashSet(skipped).apply { remove(path) }).apply(); PresetStore.onChange?.invoke()
                }
            }
            return out
        }

        if (a == "favs") {
            out += IdleSettings.Item("FAVOURITES", header = true)
            val list = installed.filter { it in favs }.sortedBy { nameOf(it).lowercase(Locale.ROOT) }
            if (list.isEmpty()) out += IdleSettings.Item("No favourites yet", "Open Your presets, press OK on a preset and choose Add to favourites")
            else { out += allRow("All favourites", list); list.forEach { out += mine(it, sizeOf(it), it.substringBeforeLast('/').replace("/", "  ›  ")) } }
            return out
        }

        if (a == "mine") {
            val cats = index.cats.filter { cc -> index.groups[cc].orEmpty().any { gr -> gr.files.indices.any { gr.path(it) in installed } } }
            if (c == null) {
                out += IdleSettings.Item("YOUR PRESETS", header = true)
                if (cats.isEmpty()) out += IdleSettings.Item("Nothing installed yet", "Get presets from Get more presets, or the Starter pack")
                for (cc in cats) {
                    val ps = index.groups[cc].orEmpty().flatMap { gr -> gr.files.indices.map { gr.path(it) } }.filter { it in installed }
                    val nOff = ps.count { it in off }; val nFav = ps.count { it in favs }
                    out += IdleSettings.Item(cc, listOf("${ps.size} installed", if (nOff > 0) "$nOff off" else "", if (nFav > 0) "$nFav ★" else "").filter { it.isNotEmpty() }.joinToString("  ·  ")) { go("mine", cc, null) }
                }
                return out
            }
            val groups = index.groups[c].orEmpty().filter { gr -> gr.files.indices.any { gr.path(it) in installed } }
            if (g == null) {
                out += IdleSettings.Item(c.uppercase(Locale.ROOT), header = true)
                out += allRow("Everything in $c", groups.flatMap { gr -> gr.files.indices.map { gr.path(it) } }.filter { it in installed })
                out += IdleSettings.Item("GROUPS", header = true)
                for (gr in groups) {
                    val ps = gr.files.indices.map { gr.path(it) }.filter { it in installed }
                    val nOff = ps.count { it in off }; val nFav = ps.count { it in favs }
                    out += IdleSettings.Item(gr.name, listOf("${ps.size} installed", if (nOff > 0) "$nOff off" else "", if (nFav > 0) "$nFav ★" else "").filter { it.isNotEmpty() }.joinToString("  ·  ")) { go("mine", c, gr.name) }
                }
                return out
            }
            val gr = groups.firstOrNull { it.name == g } ?: run { group = null; return out }
            out += IdleSettings.Item("${c.uppercase(Locale.ROOT)}  ›  ${gr.name.uppercase(Locale.ROOT)}", header = true)
            val ps = gr.files.indices.filter { gr.path(it) in installed }
            out += allRow("Everything in ${gr.name}", ps.map { gr.path(it) })
            out += IdleSettings.Item("PRESETS", header = true)
            for (i in ps) out += mine(gr.path(i), gr.sizes[i])
            return out
        }

        if (a == "") {
            out += IdleSettings.Item("MUSIC VISUALISER", header = true)
            out += IdleSettings.Item("Show the visualiser", "MilkDrop visuals behind the music view, moving with the music", check = p.getBoolean("np_viz", true)) { p.edit().putBoolean("np_viz", !p.getBoolean("np_viz", true)).apply() }
            out += IdleSettings.Item("Picture quality", "Drawn smaller and scaled up. Lower is smoother on an older TV", QUALITY[p.getInt("viz_q", 1).coerceIn(0, 2)]) { p.edit().putInt("viz_q", (p.getInt("viz_q", 1) + 1) % 3).apply() }
            out += IdleSettings.Item("Frame rate", "30 is easier on the TV", "${p.getInt("viz_fps", 30)} per second") { p.edit().putInt("viz_fps", if (p.getInt("viz_fps", 30) == 30) 60 else 30).apply() }
            out += IdleSettings.Item("New preset every", "Left and right change it by hand", SECS.let { s -> val v = p.getInt("viz_secs", 45); if (v == 0) "Only by hand" else if (v >= 60) "${v / 60} min${if (v % 60 != 0) " ${v % 60} s" else ""}" else "$v seconds" }) { p.edit().putInt("viz_secs", next(SECS, p.getInt("viz_secs", 45))).apply() }
            out += IdleSettings.Item("Brightness", "How bright the visualiser is. With the buttons hidden, up and down change it", "${p.getInt("viz_bright", 100)}%") { val b = p.getInt("viz_bright", 100); p.edit().putInt("viz_bright", if (b <= 20) 100 else b - 10).apply() }
            out += IdleSettings.Item("Hide the buttons after", "Fades the words and buttons so only the visualiser shows. Any key brings them back", p.getInt("viz_autohide", 0).let { if (it == 0) "Never" else "$it seconds" }) { p.edit().putInt("viz_autohide", next(HIDE, p.getInt("viz_autohide", 0))).apply() }
            out += IdleSettings.Item("Lyrics with the buttons hidden", "Keep the lyrics showing, in the middle of the screen, when the buttons are hidden. Needs Show lyrics on", check = p.getBoolean("np_lyrics_hidden", false)) { p.edit().putBoolean("np_lyrics_hidden", !p.getBoolean("np_lyrics_hidden", false)).apply() }
            out += IdleSettings.Item("Skipped presets", "Ones that ran too slowly or failed on this TV. OK to see them and let any try again", "${skipped.size}") { go("skipped", null, null) }

            if (installed.isNotEmpty()) {
                out += IdleSettings.Item("YOUR PRESETS", header = true)
                out += IdleSettings.Item("Browse your presets", "Preview, turn off or favourite any of them", "${installed.size} installed") { go("mine", null, null) }
                out += IdleSettings.Item("Favourites", "", "${installed.count { it in favs }}") { go("favs", null, null) }
                out += IdleSettings.Item("Play", "Favourites only falls back to everything if none are installed", if (p.getBoolean("viz_favs_only", false)) "Favourites only" else "Everything that is on") { p.edit().putBoolean("viz_favs_only", !p.getBoolean("viz_favs_only", false)).apply() }
            }

            out += IdleSettings.Item("GET PRESETS", header = true)
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
            val sBytes = starter.sumOf { sp -> sizeOf(sp).toLong() }
            out += bulk("Starter pack", "starter", starter, sBytes)
            out += IdleSettings.Item("Get more presets", "Browse the whole collection by category and download what you like") { go("get", null, null) }
            if (installed.isNotEmpty()) out += IdleSettings.Item("Remove every preset", "Frees the space. The textures stay", "") { PresetStore.remove(ctx, installed.toList()); p.edit().remove("viz_slow").remove("viz_fav").remove("viz_off").apply() }
            return out
        }

        // a == "get": the whole collection, to download
        if (c == null) {
            out += IdleSettings.Item("GET MORE PRESETS", header = true)
            for (cc in index.cats) {
                val paths = index.groups[cc].orEmpty().flatMap { gr -> gr.files.indices.map { gr.path(it) } }
                val have = paths.count { it in installed }
                val busy = PresetStore.tasks.keys.any { it.startsWith("c:$cc/") || it == "c:$cc" }
                out += IdleSettings.Item(cc, "${index.count(cc)} presets, ${mb(index.bytes(cc))}", if (busy) "Downloading" else if (have > 0) "$have installed" else "") { go("get", cc, null) }
            }
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
                out += IdleSettings.Item(gr.name, "${gr.files.size} presets, ${mb(gr.bytes)}", if (have > 0) "$have installed" else "") { go("get", c, gr.name) }
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
            out += IdleSettings.Item(name, if (path in skipped) "Skipped: too slow or failed on this TV. ${kb(gr.sizes[i])}" else kb(gr.sizes[i]), check = on, preview = path) {
                if (on) PresetStore.remove(ctx, listOf(path))
                else if (!PresetStore.install(ctx, "p:$path", name, listOf(path), gr.sizes[i].toLong())) msg = "Not enough free space"
            }
        }
        return out
    }

    /** What the panel beside a preset shows: its name, where it is from, a few status lines, and the preset to play in the live preview (null when it cannot). */
    class Panel(val name: String, val where: String, val lines: List<String>, val play: String?)

    fun panel(ctx: Context, p: SharedPreferences, path: String): Panel {
        val installed = PresetStore.installed(ctx)
        val parts = path.split('/', limit = 3)
        val lines = ArrayList<String>()
        if (path in PresetStore.favs(p)) lines += "★ Favourite"
        if (path in PresetStore.off(p)) lines += "Turned off: never played"
        val slow = path in (p.getStringSet("viz_slow", emptySet()) ?: emptySet())
        if (slow) lines += "Skipped: too slow or failed on this TV"
        var play: String? = null
        if (path !in installed) lines += "Not on this TV yet. OK downloads it"
        else if (!ProjectM.available || !PresetStore.texturesInstalled(ctx)) lines += "Download the textures to see a preview"
        else if (!slow) play = path
        return Panel(path.substringAfterLast('/').removeSuffix(".milk"), parts.take(2).joinToString("  ›  "), lines, play)
    }
}
