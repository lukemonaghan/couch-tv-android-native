package com.lukemonaghan.tvhome

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.EditText
import android.view.inputmethod.EditorInfo
import java.lang.ref.WeakReference

/**
 * The glue for the optional platform features: updates, smart home, the doorbell, custom cards and making
 * tvhome the home screen. Everything here is off until it is set up, and the dialogs it shows are Android's own
 * (they take the remote). MainActivity calls [init], [readExtras], [resumed] and [firstRun]; the home and the
 * settings screen call the rest.
 *
 * Settings from a computer (tools/send-config.sh) arrive as extras on the MainActivity intent:
 *   ha_b64 (base64 of the smart home JSON, "none" removes it), cards_url, update_repo, update_auto (true or false).
 */
object Plat {
    private var ref: WeakReference<MainActivity>? = null
    private val main = Handler(Looper.getMainLooper())
    lateinit var app: Context; private set
    val activity: MainActivity? get() = ref?.get()

    private fun prefs(): SharedPreferences = app.getSharedPreferences("platform", Context.MODE_PRIVATE)

    fun init(a: MainActivity) {
        app = a.applicationContext
        ref = WeakReference(a)
        Updater.load(app)
        CustomCards.load(app)
        Doorbell.start(app)
        readExtras(a.intent)
    }

    /** Extras a computer sent with `am start`. Safe to call with any intent. */
    fun readExtras(i: Intent?) {
        if (i == null || !::app.isInitialized) return
        HomeAssistant.readExtras(app, i)
        i.getStringExtra("cards_url")?.let { CustomCards.setLink(app, it) }
        i.getStringExtra("update_repo")?.let { Updater.setRepo(app, it) }
        if (i.hasExtra("update_auto")) Updater.setAuto(app, i.getStringExtra("update_auto") == "true")
    }

    fun resumed() {
        Updater.resumed(app)
        CustomCards.refresh(app)
        Doorbell.start(app)   // safe to repeat: picks up a doorbell that was set up while away
    }

    fun smartAvailable(): Boolean = ::app.isInitialized && HomeAssistant.available(app)

    // ------------------------------------------------------------------ Android's own dialogs

    /** A text box (Android's own keyboard). [done] gets the text, which may be empty. */
    fun ask(title: String, hint: String, yes: String, current: String = "", done: (String) -> Unit) {
        val a = activity ?: return
        main.post {
            val box = EditText(a).apply { setSingleLine(); setText(current); this.hint = hint; imeOptions = EditorInfo.IME_ACTION_DONE }
            val dialog = AlertDialog.Builder(a).setTitle(title).setView(box)
                .setPositiveButton(yes) { _, _ -> done(box.text.toString().trim()) }
                .setNegativeButton("Cancel", null).create()
            box.setOnEditorActionListener { _, _, _ -> done(box.text.toString().trim()); dialog.dismiss(); true }
            dialog.show()
        }
    }

    fun confirm(title: String, body: String, yes: String, no: String = "Not now", answered: ((Boolean) -> Unit)? = null) {
        val a = activity ?: return
        main.post {
            AlertDialog.Builder(a).setTitle(title).setMessage(body)
                .setPositiveButton(yes) { _, _ -> answered?.invoke(true) }
                .setNegativeButton(no) { _, _ -> answered?.invoke(false) }
                .setOnCancelListener { answered?.invoke(false) }.show()
        }
    }

    fun say(title: String, body: String) {
        val a = activity ?: return
        main.post { AlertDialog.Builder(a).setTitle(title).setMessage(body).setPositiveButton("OK", null).show() }
    }

    // ------------------------------------------------------------------ a custom card

    /** A custom card opens its app, or its web link. */
    fun openCustom(card: Card) {
        val a = activity ?: return
        if (card.pkg.isNotEmpty()) { a.launch(card.pkg); return }
        val link = card.intentUri ?: return
        try { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}
    }

    // ------------------------------------------------------------------ the home screen (issue #98 in couch-tv)

    /** Asks Android to make tvhome the home, then says whether it worked. */
    fun requestHome() {
        val a = activity ?: return
        if (!HomeRole.request(a)) say(PS.HOME_ROW, PS.HOME_NOT_YET)
    }

    /** MainActivity.onActivityResult for HomeRole.REQUEST: Android's chooser closed. */
    fun homeAnswered() {
        main.postDelayed({ say(PS.HOME_ROW, if (HomeRole.held(app)) PS.HOME_NOW else PS.HOME_NOT_YET) }, 600)
    }

    /**
     * The first time the home opens: "Make tvhome your home screen?" (only where Android offers its own request),
     * then, once, "Tell you when a new version is out?" (only when a release address is set). Back counts as No.
     */
    fun firstRun() {
        if (!::app.isInitialized) return
        val p = prefs()
        if (!p.getBoolean("askedHome", false) && HomeRole.state(app) == "ask") {
            p.edit().putBoolean("askedHome", true).apply()
            confirm(PS.HOME_ASK, PS.HOME_ASK_SUB, "Yes, show me", "Not now") { yes ->
                if (yes) requestHome() else askUpdates()
            }
        } else askUpdates()
    }

    private fun askUpdates() {
        val st = Updater.state(app)
        if (!st.supported || st.answered) return
        confirm(PS.UPD_ASK, PS.UPD_ASK_SUB, "Yes, tell me", "No, I'll check myself") { yes -> Updater.setAuto(app, yes) }
    }

    // ------------------------------------------------------------------ Reset home settings

    /** Back to the starting settings: the "home" prefs are cleared except the people, the dock size and the bookkeeping. People and apps stay. */
    fun resetSettings(prefs: SharedPreferences, store: AppStore) {
        val keep = listOf("people", "person", "dockSize", "askedTv", "timeout_saved")
        val saved = keep.mapNotNull { k -> prefs.all[k]?.let { k to it } }
        val e = prefs.edit().clear()
        for ((k, v) in saved) when (v) {
            is String -> e.putString(k, v); is Int -> e.putInt(k, v); is Boolean -> e.putBoolean(k, v)
            is Long -> e.putLong(k, v); is Float -> e.putFloat(k, v)
        }
        e.apply()
        store.dockSize = prefs.getInt("dockSize", 10)
        store.reload()
    }

    /** Whether Android has the "display over other apps" switch for this app (the doorbell card needs it). */
    fun canOverlay(): Boolean = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(app)

    /** Opens Android's switch for "display over other apps" where the TV has one. False when it has none. */
    fun openOverlaySwitch(): Boolean {
        val a = activity ?: return false
        val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + app.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (i.resolveActivity(app.packageManager) == null) return false
        return try { a.startActivity(i); true } catch (e: Exception) { false }
    }
}
