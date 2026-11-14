package com.lukemonaghan.tvhome

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo

/** One app the TV can open from the home. */
class AppItem(val pkg: String, val label: String, val ri: ResolveInfo)

/**
 * The TV's apps, their order and where the dock ends. Same model as upstream couch-tv's home:
 * an ordered list of packages plus a "line": items before the line are in the dock, the rest sit behind
 * More apps. An app installed after the first run goes at the line with a "new" dot until it is opened once.
 *
 * Plain Android, no libraries. State is a few strings in SharedPreferences.
 */
class AppStore(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("apps", Context.MODE_PRIVATE)

    /** Apps in order. items[0 until line] are in the dock, the rest are behind More apps. */
    var items: List<AppItem> = emptyList(); private set
    var line = 0; private set
    private var fresh: Set<String> = emptySet()

    /** How many apps fit in the dock before the Settings and More apps tiles (1 to 10). */
    var dockSize = 10

    fun isNew(pkg: String) = pkg in fresh
    val dock: List<AppItem> get() = items.subList(0, line)

    /** Reads the TV's apps again and applies the saved order. Call at start, on resume and when a package changes. */
    fun reload() {
        val pm = ctx.packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val found = pm.queryIntentActivities(q, 0)
            .filter { it.activityInfo.packageName != ctx.packageName && it.activityInfo.packageName != SETTINGS_PKG }
            .distinctBy { it.activityInfo.packageName }
            .map { AppItem(it.activityInfo.packageName, it.loadLabel(pm).toString(), it) }
            .sortedBy { it.label.lowercase() }
        val byPkg = found.associateBy { it.pkg }
        val savedOrder = prefs.getString("order", null)?.split(',')?.filter { it.isNotEmpty() }
        if (savedOrder == null) {
            items = found
            line = minOf(dockSize, found.size)
        } else {
            val known = savedOrder.mapNotNull { byPkg[it] }
            val gained = found.filter { it.pkg !in savedOrder }   // new apps go below the line
            items = known + gained
            // the line counts the apps above it that this TV still has
            val savedLine = prefs.getInt("line", dockSize)
            line = minOf(dockSize, savedOrder.take(savedLine).count { it in byPkg })
        }
        fresh = prefs.getStringSet("fresh", emptySet()) ?: emptySet()
        detectNew()
        fresh = fresh.filter { it in byPkg }.toSet()
    }

    /** Apps installed since the TV first saw its apps: first behind the dock line, with a dot until opened once. */
    private fun detectNew() {
        val pk = items.map { it.pkg }
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
            if (i < 0 || i < line) continue
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

    private fun save() {
        prefs.edit().putString("order", items.joinToString(",") { it.pkg }).putInt("line", line).apply()
    }

    companion object {
        const val SETTINGS_PKG = "com.android.tv.settings"
    }
}
