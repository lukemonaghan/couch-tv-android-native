package com.lukemonaghan.tvhome

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo

/** One tile the TV can open from the home: an app, or (with [inputId]) one of the TV's inputs pinned to the home. */
class AppItem(val pkg: String, val label: String, val ri: ResolveInfo?, val inputId: String? = null)

/**
 * The TV's apps, their order and where the dock ends. Same model as upstream couch-tv's home:
 * an ordered list of packages plus a "line": items before the line are in the dock, the rest sit behind
 * More apps. An app installed after the first run goes at the line with a "new" dot until it is opened once.
 *
 * Also: apps the household hid (they stay out of the dock and More apps until shown again in Home settings,
 * Apps), apps couch-tv tucks behind More apps by default (the TV maker's own clutter, the app store), and the
 * TV inputs pinned to the home, which are tiles in the same order as apps.
 *
 * Plain Android, no libraries. State is a few strings in SharedPreferences.
 */
class AppStore(private val ctx: Context) {
    private var prefs = ctx.getSharedPreferences("apps", Context.MODE_PRIVATE)
    private var profile: String? = null

    /** Apps in order. items[0 until line] are in the dock, the rest are behind More apps. */
    var items: List<AppItem> = emptyList(); private set
    var line = 0; private set
    private var fresh: Set<String> = emptySet()

    /** How many apps fit in the dock before the Settings and More apps tiles (1 to 10). */
    var dockSize = 10

    /** Apps hidden from the home, for Home settings, Apps to bring back. */
    var hiddenItems: List<AppItem> = emptyList(); private set
    /** Every input port the TV lists (pinned or not), and which of them are pinned to the home. */
    var detectedInputs: List<TvInputs.Port> = emptyList(); private set
    val pinnedInputs: Set<String> get() = prefs.getStringSet("inputs", emptySet()) ?: emptySet()
    /** Whether the Input quick button can work on this TV. */
    var inputsAvailable = false; private set

    fun isNew(pkg: String) = pkg in fresh
    val dock: List<AppItem> get() = items.subList(0, line)

    /** Reads the TV's apps again and applies the saved order. Call at start, on resume and when a package changes. */
    fun reload() {
        val pm = ctx.packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val all = pm.queryIntentActivities(q, 0)
            .filter { it.activityInfo.packageName != ctx.packageName && it.activityInfo.packageName != SETTINGS_PKG && it.activityInfo.packageName !in OLD_HOMES }
            .distinctBy { it.activityInfo.packageName }
            .map { AppItem(it.activityInfo.packageName, RENAME[it.activityInfo.packageName] ?: it.loadLabel(pm).toString(), it) }
            .toMutableList()
        appStoreFallback(pm)?.let { s -> if (all.none { it.pkg == s.pkg }) all += s }
        val hidden = prefs.getStringSet("hidden", emptySet()) ?: emptySet()
        hiddenItems = all.filter { it.pkg in hidden }.sortedBy { it.label.lowercase() }
        // the default order: the household's apps by name, then the tucked ones, the app store last of all
        val apps = all.filter { it.pkg !in hidden }
            .sortedWith(compareBy({ tucked(it) }, { it.pkg in APP_LAST }, { it.label.lowercase() }))

        detectedInputs = TvInputs.list(ctx)
        inputsAvailable = detectedInputs.isNotEmpty() || TvInputs.available(ctx)
        val pinned = pinnedInputs
        val inputs = detectedInputs.filter { it.id in pinned }
            .map { AppItem("$INPUT_PREFIX${it.id}", it.label.ifEmpty { if (it.type == "hdmi") "HDMI" else "Input" }, null, it.id) }

        val found = apps + inputs
        val byPkg = found.associateBy { it.pkg }
        val savedOrder = prefs.getString("order", null)?.split(',')?.filter { it.isNotEmpty() }
        var changed = false
        if (savedOrder == null) {
            val plain = apps.filter { !tucked(it) }
            val keep = maxOf(0, dockSize - inputs.size)   // pinned inputs take dock places, so they are never pushed behind More apps
            items = plain.take(keep) + inputs + plain.drop(keep) + apps.filter { tucked(it) }
            line = minOf(dockSize, plain.size + inputs.size)
        } else {
            val known = savedOrder.mapNotNull { byPkg[it] }
            val gained = found.filter { it.pkg !in savedOrder }.sortedBy { it.inputId == null && tucked(it) }   // new apps go below the line, tucked ones last
            val list = (known + gained).toMutableList()
            // the line counts the apps above it that this TV still has
            val savedLine = prefs.getInt("line", dockSize)
            var ln = minOf(dockSize, savedOrder.take(savedLine).count { it in byPkg })
            // a newly pinned input goes at the end of the dock
            for (inp in gained.filter { it.inputId != null }) {
                list.remove(inp); list.add(ln, inp); if (ln < dockSize) ln++
                changed = true
            }
            items = list
            line = ln
        }
        fresh = prefs.getStringSet("fresh", emptySet()) ?: emptySet()
        detectNew()
        fresh = fresh.filter { it in byPkg }.toSet()
        if (changed) save()
    }

    /** Fire TV's Appstore has no Leanback tile, so it is asked for by name. */
    private fun appStoreFallback(pm: PackageManager): AppItem? {
        val i = runCatching { pm.getLaunchIntentForPackage("com.amazon.venezia") }.getOrNull() ?: return null
        val ri = pm.resolveActivity(i, 0) ?: return null
        return AppItem("com.amazon.venezia", "App Store", ri)
    }

    /** Apps installed since the TV first saw its apps: first behind the dock line, with a dot until opened once. Inputs never get a dot. */
    private fun detectNew() {
        val pk = items.filter { it.inputId == null }.map { it.pkg }
        val seen = prefs.getStringSet("seen", null)
        if (seen == null) {   // the very first run: remember what is here and fix the order, so later installs go at the line
            prefs.edit().putStringSet("seen", pk.toSet()).apply()
            save()
            return
        }
        val added = pk.filter { it !in seen }
        if (added.isEmpty()) return
        fresh = fresh + added
        prefs.edit().putStringSet("seen", seen + added).putStringSet("fresh", fresh).apply()
        val list = items.toMutableList()
        for (p in added) {
            val i = list.indexOfFirst { it.pkg == p }
            if (i < 0 || i < line || tucked(list[i])) continue   // a tucked app stays behind More apps
            val it = list.removeAt(i)
            list.add(line, it)
            if (line < dockSize) line++
        }
        items = list
        save()
    }

    /** The app was opened: its "new" dot goes away. */
    fun opened(pkg: String) {
        if (pkg !in fresh) return
        fresh = fresh - pkg
        prefs.edit().putStringSet("fresh", fresh).apply()
    }

    /** Moves the app at dock position [from] to dock position [to]. */
    fun moveInDock(from: Int, to: Int) {
        if (from !in 0 until line || to !in 0 until line || from == to) return
        val list = items.toMutableList()
        list.add(to, list.removeAt(from))
        items = list
        save()
    }

    /** Down while moving: the app goes behind More apps, first in line there. */
    fun sendToMore(index: Int) {
        if (index !in 0 until line) return
        val list = items.toMutableList()
        val it = list.removeAt(index)
        line--
        list.add(line, it)
        items = list
        save()
    }

    /** Holding OK on an app behind More apps: it goes to the end of the dock. A full dock pushes its last app behind More apps. */
    fun bringToDock(index: Int) {
        if (index !in line until items.size) return
        val list = items.toMutableList()
        val it = list.removeAt(index)
        if (line < dockSize) {
            list.add(line, it)
            line++
        } else {
            list.add(line - 1, it)   // the old last dock app slides to the first place behind More apps
        }
        items = list
        save()
    }

    // ------------------------------------------------------------------ hiding apps and pinning inputs

    /** "Hide from home": the app leaves the dock and More apps until it is shown again in Home settings, Apps. An input is unpinned instead. */
    fun hide(pkg: String) {
        val input = items.firstOrNull { it.pkg == pkg }?.inputId
        if (input != null) { setPinned(input, false); return }
        prefs.edit().putStringSet("hidden", (prefs.getStringSet("hidden", emptySet()) ?: emptySet()) + pkg).apply()
        reload()
        save()
    }

    /** A hidden app comes back at the end of the dock (as Home settings does for it). */
    fun unhide(pkg: String) {
        prefs.edit().putStringSet("hidden", (prefs.getStringSet("hidden", emptySet()) ?: emptySet()) - pkg).apply()
        reload()
        val i = items.indexOfFirst { it.pkg == pkg }
        if (i >= 0) bringToDock(i)
    }

    /** Home settings, Apps, OK on an app: on home, then More apps, then hidden, then back on home. */
    fun cycle(pkg: String) {
        if (hiddenItems.any { it.pkg == pkg }) { unhide(pkg); return }
        val i = items.indexOfFirst { it.pkg == pkg }
        if (i < 0) return
        if (i < line) sendToMore(i) else hide(pkg)
    }

    /** Pins a TV input to the home (a tile at the end of the dock), or takes it off. */
    fun setPinned(id: String, on: Boolean) {
        val now = pinnedInputs
        prefs.edit().putStringSet("inputs", if (on) now + id else now - id).apply()
        reload()
        save()
    }

    /**
     * Whose apps these are: each person has their own order and dock line (and "new" dots). A person who has none yet starts from
     * what the shared list holds. [id] null is the one shared list (nobody, or one person).
     */
    fun setProfile(id: String?) {
        if (id == profile) return
        profile = id
        prefs = ctx.getSharedPreferences(if (id == null) "apps" else "apps_$id", Context.MODE_PRIVATE)
        if (id != null && !prefs.contains("order")) {
            val base = ctx.getSharedPreferences("apps", Context.MODE_PRIVATE)
            if (base.contains("order")) prefs.edit().putString("order", base.getString("order", null)).putInt("line", base.getInt("line", dockSize))
                .putStringSet("seen", base.getStringSet("seen", null)).apply()
        }
        reload()
    }

    private fun save() {
        prefs.edit().putString("order", items.joinToString(",") { it.pkg }).putInt("line", line).apply()
    }

    companion object {
        const val SETTINGS_PKG = "com.android.tv.settings"
        const val INPUT_PREFIX = "input:"
        /** Earlier versions of this home (couch-tv's web home and its on-TV app): never listed as an app. */
        val OLD_HOMES = setOf("app.couchtv.launcher", "dev.couchtv.ontv", "dev.couchtv.sync")

        /** Apps couch-tv tucks behind More apps unless the household moves them: a maker's own clutter and the app store (upstream TUCKED). */
        private val TUCKED = setOf("com.mediatek.wwtv.tvcenter", "com.google.android.youtube.tvmusic", "com.spotify.tv.android", "com.android.vending", "com.amazon.venezia")
        private val TUCKED_NAMES = Regex("^(screen ?shar|live ?tv|usb|media ?cent|browser|device assistant)", RegexOption.IGNORE_CASE)
        /** Sorted last in the default order. */
        private val APP_LAST = setOf("com.mediatek.wwtv.tvcenter", "com.android.vending", "com.amazon.venezia")
        /** The app stores are shown as "App Store" whatever the maker calls them. */
        private val RENAME = mapOf("com.android.vending" to "App Store", "com.amazon.venezia" to "App Store")

        fun tucked(a: AppItem) = a.inputId == null && (a.pkg in TUCKED || TUCKED_NAMES.containsMatchIn(a.label))
    }
}
