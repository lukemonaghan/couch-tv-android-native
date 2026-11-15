package com.lukemonaghan.tvhome

import android.content.Context
import android.content.SharedPreferences
import java.text.DateFormat
import java.util.Date

/** The rows of the platform pages of Home settings: Updates, Smart home, and the extra rows on About. SettingsScreen draws them. */
object PlatformPages {
    class PRow(val title: String, val note: String = "", val value: String = "", val check: Boolean? = null,
               val header: Boolean = false, val press: (() -> Unit)? = null)

    private fun onOff(b: Boolean) = if (b) "On" else "Off"

    // ------------------------------------------------------------------ Updates

    fun updates(ctx: Context): List<PRow> {
        val u = Updater.state(ctx)
        val rows = ArrayList<PRow>()
        rows.add(PRow("Version", value = u.current))
        if (u.available) {
            rows.add(PRow("Update to ${u.latest}",
                note = when (u.busy) { "downloading" -> "Downloading… ${u.progress}%"; "installing" -> PS.UPD_CONFIRM; else -> u.title }) {
                if (u.busy.isEmpty()) Updater.install(ctx)
            })
        }
        if (u.supported) {
            rows.add(PRow(PS.UPD_CHECK, note = if (u.busy == "checking") PS.UPD_CHECKING else last(u.checked)) { if (u.busy.isEmpty()) Updater.check(ctx, true) })
            if (u.error.isNotEmpty()) rows.add(PRow(PS.updErr(u.error)))
            else if (u.refused) rows.add(PRow(PS.updErr("other_key")))
            else if (u.checked > 0 && !u.available && u.busy.isEmpty()) rows.add(PRow(PS.UPD_LATEST))
            rows.add(PRow(PS.UPD_AUTO, value = onOff(u.auto)) { Updater.setAuto(ctx, !u.auto) })
        } else rows.add(PRow(PS.UPD_OFF))
        rows.add(PRow("Release address", "Where new versions come from: owner/name on GitHub",
            Updater.repo(ctx) ?: "Not set") {
            Plat.ask("Release address (owner/name)", "owner/name", "Save", Updater.repo(ctx).orEmpty()) { v ->
                if (!Updater.setRepo(ctx, v)) Plat.say("Release address", "That is not an owner/name address.")
            }
        })
        rows.add(PRow(PS.UPD_NOTE))
        return rows
    }

    private fun last(at: Long): String =
        if (at <= 0) PS.UPD_NEVER else "Last checked " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(at))

    // ------------------------------------------------------------------ Smart home

    fun smart(ctx: Context): List<PRow> {
        val s = HomeAssistant.settings(ctx)
        val rows = ArrayList<PRow>()
        rows.add(PRow("Home Assistant", "Air conditioning rooms and scenes on a Smart home tile",
            if (s == null) "Off" else "${s.climate.size} rooms, ${s.scenes.size} scenes") {
            Plat.ask("Smart home settings (JSON)", "{\"url\":\"http://…\",\"token\":\"…\"}", "Save") { v ->
                if (v.isNotEmpty() && !HomeAssistant.replace(ctx, v)) Plat.say("Smart home", "Those settings did not check out. Nothing changed.")
            }
        })
        if (s != null) rows.add(PRow("Turn smart home off", "Forgets the address, the token, the rooms and the doorbell") { HomeAssistant.replace(ctx, null) })
        val bell = s?.doorbell
        if (bell != null) {
            val ok = Plat.canOverlay()
            rows.add(PRow("Doorbell card", "Needs “display over other apps”", if (ok) "On" else "Needs permission") {
                if (!ok && !Plat.openOverlaySwitch())
                    Plat.say("Doorbell card", "This TV has no screen for it. From a computer: appops set com.lukemonaghan.tvhome SYSTEM_ALERT_WINDOW allow")
            })
        }
        rows.add(PRow("Custom cards link", "A JSON file of your own cards at the end of Keep watching",
            CustomCards.link(ctx).ifEmpty { "Not set" }) {
            Plat.ask("Custom cards link", "https://…", "Save", CustomCards.link(ctx)) { v ->
                if (!CustomCards.setLink(ctx, v)) Plat.say("Custom cards", "That is not an http or https address.")
            }
        })
        rows.add(PRow("From a computer: tools/send-config.sh sets all of these without typing on the remote."))
        return rows
    }

    // ------------------------------------------------------------------ About

    /** Rows added at the top of About: making tvhome the home, and (at the bottom) Reset home settings. */
    fun aboutTop(ctx: Context): List<PRow> = when (HomeRole.state(ctx)) {
        "ask" -> listOf(PRow(PS.HOME_ROW, PS.HOME_ROW_SUB) { Plat.requestHome() })
        "google_tv" -> listOf(PRow(PS.HOME_ROW, PS.HOME_GOOGLE_TV))
        else -> emptyList()
    }

    fun aboutBottom(prefs: SharedPreferences, store: AppStore, done: () -> Unit): List<PRow> =
        listOf(PRow(PS.RESET, PS.RESET_SUB) {
            Plat.confirm(PS.RESET_ASK, PS.RESET_SUB, "Reset", "Cancel") { yes ->
                if (yes) { Plat.resetSettings(prefs, store); done(); Plat.say(PS.RESET, PS.RESET_DONE) }
            }
        })
}
