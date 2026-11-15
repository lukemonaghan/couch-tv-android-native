package com.lukemonaghan.tvhome

import android.content.Context

/** One row of the People page in Home settings (the settings screen draws it like its own rows). */
class PRow(
    val title: String, val note: String = "", val value: String = "", val check: Boolean? = null,
    val header: Boolean = false, val avatar: Person? = null, val press: (() -> Unit)? = null,
)

/**
 * Home settings, People: the list of people, then one person (name, colour, kid and guest flags, order, remove) and under
 * that Kids mode (the PIN, the shows, the apps). Pages stack: Back goes up one. All changes are saved at once through [People];
 * [peopleChanged] tells the home so the switch, the app order and the kids shelf follow.
 */
class PeopleSettings(
    private val ctx: Context, private val people: People, val ui: PeopleUi,
    private val cardsOf: () -> List<Card>, private val appsOf: () -> List<AppItem>,
    private val act: HomeActions, private val peopleChanged: () -> Unit,
) {
    private val stack = ArrayList<String>()
    private var confirm: String? = null
    private var pendingTitle = ""
    /** The PIN pad while a PIN is being set, drawn over the settings screen. */
    var pad: PinPad? = null; private set
    var onNavigate: (() -> Unit)? = null
    var redraw: (() -> Unit)? = null

    fun reset() { stack.clear(); pad = null; confirm = null }

    private fun go(page: String) { stack.add(page); confirm = null; onNavigate?.invoke(); redraw?.invoke() }
    private fun up(n: Int = 1) { repeat(n) { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }; confirm = null; onNavigate?.invoke(); redraw?.invoke() }

    /** Back: closes the pad, else one page up. False at the list itself, so settings can step out to the sidebar. */
    fun back(): Boolean {
        if (pad != null) { pad = null; return true }
        if (stack.isEmpty()) return false
        up(); return true
    }

    fun title(): String {
        val page = stack.lastOrNull() ?: return "People"
        val p = people.byId(page.substringAfter(':'))
        return when (page.substringBefore(':')) {
            "person" -> p?.name ?: "People"
            "kids" -> "Kids mode" + (p?.let { ", ${it.name}" } ?: "")
            "kadd" -> "Add from Keep watching"
            "kapp" -> "Which app plays it?"
            else -> "People"
        }
    }

    fun rows(): List<PRow> {
        val page = stack.lastOrNull() ?: return root()
        val id = page.substringAfter(':')
        val p = people.byId(id) ?: return root().also { stack.clear() }
        return when (page.substringBefore(':')) {
            "person" -> person(p)
            "kids" -> kids(p)
            "kadd" -> addFromWatch(p)
            "kapp" -> pickApp(p)
            else -> root()
        }
    }

    // ------------------------------------------------------------------ the pages

    private fun changed() { people.save(); peopleChanged(); redraw?.invoke() }

    private fun root(): List<PRow> {
        val list = people.list()
        val cur = people.current()?.id
        val out = ArrayList<PRow>()
        if (list.isEmpty()) out.add(PRow("No one yet", "Add the people who watch this TV"))
        for (p in list) {
            val tags = listOfNotNull(if (people.isKid(p)) "Kids home" else if (p.kid) "Kid" else null, if (p.guest) "Guest" else null).joinToString(", ")
            out.add(PRow(p.name, tags, if (p.id == cur && list.size > 1) "On screen" else "", avatar = p) { go("person:${p.id}") })
        }
        if (list.size < People.MAX) out.add(PRow("Add a person", "Two people show a switch, three or more show a round button") { act.askText("Name") { n -> people.add(n); changed() } })
        else out.add(PRow("That is the most", "A TV keeps up to ${People.MAX} people"))
        out.add(PRow("Each person has their own apps", "Order and the dock follow whoever is on screen"))
        return out
    }

    private fun person(p: Person): List<PRow> {
        val list = people.list(); val i = list.indexOf(p)
        val out = ArrayList<PRow>()
        out.add(PRow("Name", "", p.name, avatar = p) { act.askText("Name") { n -> people.rename(p.id, n); changed() } })
        val ci = People.PALETTE.indexOf(p.colour)
        out.add(PRow("Colour", "", if (p.colour == 0) "Automatic" else People.COLOUR_NAMES.getOrElse(ci) { "Custom" }) {
            p.colour = if (p.colour == 0) People.PALETTE[0] else if (ci in 0 until People.PALETTE.size - 1) People.PALETTE[ci + 1] else 0
            changed()
        })
        out.add(PRow("Kid profile", "Marks a child's profile", check = p.kid) { p.kid = !p.kid; changed() })
        out.add(PRow("Guest profile", "Marks a visitor's profile", check = p.guest) { p.guest = !p.guest; changed() })
        out.add(PRow("Kids mode", "Only chosen shows and apps, locked by a PIN", if (people.isKid(p)) "On" else "Off") { go("kids:${p.id}") })
        if (i > 0) out.add(PRow("Move earlier", "") { people.move(p.id, -1); changed() })
        if (i >= 0 && i < list.size - 1) out.add(PRow("Move later", "") { people.move(p.id, 1); changed() })
        val key = "rm:${p.id}"
        out.add(PRow(if (confirm == key) "Press OK again to remove ${p.name}" else "Remove ${p.name}", "Their app order goes with them") {
            if (confirm != key) { confirm = key; redraw?.invoke() }
            else {
                ctx.getSharedPreferences("apps_${p.id}", Context.MODE_PRIVATE).edit().clear().apply()
                people.remove(p.id); up(); changed()
            }
        })
        return out
    }

    private fun kc(p: Person): KidsConfig = p.kids ?: KidsConfig().also { p.kids = it }

    private fun kids(p: Person): List<PRow> {
        val k = kc(p)
        val out = ArrayList<PRow>()
        out.add(PRow("Kids home", "${p.name}'s home becomes one shelf of shows and a few apps", check = k.on && k.hasPin) {
            if (!k.hasPin) startPin(p) else { k.on = !k.on; changed() }
        })
        out.add(PRow("Parent PIN", "4 to 8 digits. Only a scrambled copy is kept", if (k.hasPin) "Change" else "Not set") { startPin(p) })
        out.add(PRow("SHOWS", header = true))
        if (k.shows.isEmpty()) out.add(PRow("No shows yet", "Add them below"))
        for (s in k.shows) out.add(PRow(s.title, s.sub.ifEmpty { appName(s.pkg) }, "OK removes") { k.shows.remove(s); changed() })
        if (k.shows.size < MAX_SHOWS) {
            out.add(PRow("Add from Keep watching", "Pick a show the TV already knows about") { go("kadd:${p.id}") })
            out.add(PRow("Add a show by name", "Type a title, then choose the app") { act.askText("Show name") { t -> if (t.isNotBlank()) { pendingTitle = t.trim().take(60); go("kapp:${p.id}") } } })
        }
        out.add(PRow("APPS", "", "Up to $MAX_APPS", header = true))
        for (a in appsOf()) out.add(PRow(a.label, check = a.pkg in k.apps) {
            if (a.pkg in k.apps) k.apps.remove(a.pkg) else if (k.apps.size < MAX_APPS) k.apps.add(a.pkg)
            changed()
        })
        return out
    }

    private fun addFromWatch(p: Person): List<PRow> {
        val k = kc(p)
        val cards = cardsOf().filter { c -> k.shows.none { it.title == c.title && it.pkg == c.pkg } }
        if (cards.isEmpty()) return listOf(PRow("Nothing to add", "Keep watching is empty, or all of it is already here"))
        return cards.map { c ->
            PRow(c.title, listOf(c.app, c.episode).filter { it.isNotEmpty() }.joinToString("  ·  ")) {
                k.shows.add(KidShow(c.title, c.pkg, c.intentUri, c.art, c.episode)); up(); changed()
            }
        }
    }

    private fun pickApp(p: Person): List<PRow> {
        val k = kc(p)
        return appsOf().map { a -> PRow(a.label, "Plays $pendingTitle") { k.shows.add(KidShow(pendingTitle, a.pkg, null, null, "")); up(2); changed() } }
    }

    private fun appName(pkg: String) = appsOf().firstOrNull { it.pkg == pkg }?.label ?: pkg

    // ------------------------------------------------------------------ the PIN

    private fun startPin(p: Person) {
        val hint = "Arrows and OK, or the number keys. Back cancels."
        pad = PinPad(ctx, "New parent PIN", "4 to 8 digits. You type it again next.", hint, false, false,
            submit = { first ->
                if (first.length < 4) "Use at least 4 digits."
                else {
                    pad = PinPad(ctx, "Type it again", "To make sure it is right.", hint, false, false,
                        submit = { again ->
                            if (again != first) "The two PINs are different."
                            else { val k = kc(p); k.setPin(again); k.on = true; pad = null; changed(); null }
                        }, close = { pad = null; redraw?.invoke() })
                    redraw?.invoke()
                    null
                }
            }, close = { pad = null; redraw?.invoke() })
        redraw?.invoke()
    }

    private companion object { const val MAX_SHOWS = 20; const val MAX_APPS = 6 }
}
