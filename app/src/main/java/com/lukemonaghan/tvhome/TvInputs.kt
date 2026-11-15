package com.lukemonaghan.tvhome

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast

/**
 * The TV's inputs (HDMI and the others Android lists), and the ways to switch to one. Same approach as
 * couch-tv's launcher: Android's passthrough channel for the input, which the TV's own input app plays; where
 * nothing on the TV takes that, the TV's own input list screen. Plain framework calls, no libraries.
 */
object TvInputs {
    /** One input port with the name the TV gives it and, behind it, the devices HDMI-CEC found (a console's name). */
    class Port(val id: String, val label: String, val type: String, val devices: List<String>)

    /** Makers name their input list screen differently, so each guess is checked with resolveActivity. */
    private val INPUT_ACTIONS = listOf(
        "android.settings.TV_INPUT_SETTINGS",           // some Android TV builds
        "com.android.tv.action.VIEW_INPUTS",            // the Live Channels input picker
        "com.google.android.tv.action.VIEW_INPUTS",
        "android.media.tv.action.SETUP_INPUTS",         // the system list of TV inputs
    )

    /** HDMI and the other passthrough inputs Android lists, minus any the TV's owner hid. */
    private fun passthrough(ctx: Context): List<TvInputInfo> = try {
        val tim = ctx.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
        tim?.tvInputList.orEmpty().filter { it.isPassthroughInput && (Build.VERSION.SDK_INT < 24 || !it.isHidden(ctx)) }
    } catch (e: Throwable) { emptyList() }

    /** The name the TV gives an input: the one its owner set, else the TV's own. */
    private fun label(ctx: Context, i: TvInputInfo): String? = try {
        ((if (Build.VERSION.SDK_INT >= 24) i.loadCustomLabel(ctx) else null) ?: i.loadLabel(ctx))?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: Throwable) { null }

    private fun parentOf(i: TvInputInfo): String? = if (Build.VERSION.SDK_INT >= 26) runCatching { i.parentId }.getOrNull() else null

    /** The input ports, each with the CEC devices behind it. Empty when the TV lists none. */
    fun list(ctx: Context): List<Port> {
        val all = passthrough(ctx)
        return all.filter { parentOf(it) == null }.map { p ->
            Port(p.id, label(ctx, p) ?: "", when (p.type) {
                TvInputInfo.TYPE_HDMI -> "hdmi"
                TvInputInfo.TYPE_COMPONENT -> "component"
                TvInputInfo.TYPE_COMPOSITE -> "composite"
                TvInputInfo.TYPE_DISPLAY_PORT -> "displayport"
                TvInputInfo.TYPE_VGA -> "vga"
                else -> "other"
            }, all.filter { parentOf(it) == p.id }.mapNotNull { label(ctx, it) })
        }
    }

    /** The TV's own input list screen, if it has one. */
    private fun listIntent(ctx: Context): Intent? = INPUT_ACTIONS.asSequence()
        .map { Intent(it) }
        .firstOrNull { ctx.packageManager.resolveActivity(it, 0) != null }

    /** Whether the Input button can work here: the TV lists inputs, or has a screen that does. */
    fun available(ctx: Context): Boolean = listIntent(ctx) != null || passthrough(ctx).isNotEmpty()

    private fun start(ctx: Context, i: Intent): Boolean = try {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: Exception) { Log.w("tvhome", "could not start $i: ${e.message}"); false }

    /** Switches the TV to one input, or opens the input list when nothing on the TV takes the passthrough channel. */
    fun open(ctx: Context, id: String): Boolean {
        if (id.isBlank()) return false
        val view = Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id))
        if (ctx.packageManager.resolveActivity(view, 0) != null && start(ctx, view)) return true
        return listIntent(ctx)?.let { start(ctx, it) } ?: false
    }

    /** The TV's own input list screen. False when it has none. */
    fun openList(ctx: Context): Boolean = listIntent(ctx)?.let { start(ctx, it) } ?: false

    /** The TV's Bluetooth or Network settings, for the Input menu (falls back to all Settings). */
    fun openSettingsScreen(ctx: Context, which: String): Boolean {
        val actions = when (which) {
            "bluetooth" -> listOf(Settings.ACTION_BLUETOOTH_SETTINGS)
            "network" -> listOf(Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS)
            else -> emptyList()
        } + Settings.ACTION_SETTINGS
        // Fire OS: every Settings action goes through Amazon's home, which the Home button watch puts us back over.
        val fire = if (FireWatch.connected) FireWatch.amazonSettings(ctx.packageManager) else null
        val i = fire ?: actions.asSequence().map { Intent(it) }.firstOrNull { ctx.packageManager.resolveActivity(it, 0) != null }
        FireWatch.allowSettingsNow()
        return i?.let { start(ctx, it) } ?: false
    }

    fun say(ctx: Context, msg: String) { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
    const val NO_INPUTS = "This TV has no input list to open."
}
