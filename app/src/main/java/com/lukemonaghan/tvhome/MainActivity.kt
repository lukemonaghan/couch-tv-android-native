package com.lukemonaghan.tvhome

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import java.lang.ref.WeakReference

/** The home: one Activity holding one custom view (HomeView). */
class MainActivity : Activity(), HomeActions {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var resumed = false
    private lateinit var store: AppStore
    private lateinit var home: HomeView
    private var packageWatcher: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shown = WeakReference(this)
        CrashGuard.install(this)
        // Crashed twice in the last few minutes: stand aside for the TV's own home rather than crash
        // again, so the TV is never stuck going round (CrashGuard.kt). It tries again later.
        if (CrashGuard.standingAside(this) && CrashGuard.openStockHome(this)) {
            finish()
            return
        }
        CrashGuard.started(this)
        main.postDelayed({ CrashGuard.settled(this) }, CrashGuard.SETTLE_MS)
        store = AppStore(this).also { it.reload() }
        home = HomeView(this, store, this)
        setContentView(home)
        FireWatch.ensure(this)   // only once a computer has granted WRITE_SECURE_SETTINGS over ADB
        main.postDelayed({ FireWatch.rebindIfUnbound(applicationContext) }, 10_000L)   // listed but not bound after a force-stop
        watchPackages()
        askTvListings()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The remote's gear button, caught by KeyWatch: the gear menu comes later.
        if (intent.getBooleanExtra("gear", false)) { Log.i("tvhome", "gear pressed"); return }
        backToFirstTile()   // Home pressed while we are already in front
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (::home.isInitialized) home.refresh()   // an app may have been installed or removed while we were away
    }

    override fun onPause() { resumed = false; super.onPause() }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        packageWatcher?.let { runCatching { unregisterReceiver(it) } }
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND && ::home.isInitialized) home.trim()
    }

    /** Back never leaves the home: it only steps out of More apps, a menu or move mode. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (::home.isInitialized) home.back() }

    /** Asks once for the Watch Next list (Keep watching). Without it Android answers with nothing. A computer can also grant it over ADB. */
    private fun askTvListings() {
        if (checkSelfPermission(WatchNext.READ_TV) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val p = getSharedPreferences("home", MODE_PRIVATE)
        if (p.getBoolean("askedTv", false)) return
        p.edit().putBoolean("askedTv", true).apply()
        runCatching { requestPermissions(arrayOf(WatchNext.READ_TV), 77) }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (::home.isInitialized) home.refresh()
    }

    private fun watchPackages() {
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        packageWatcher = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { home.refresh() }
        }
        registerReceiver(packageWatcher, f)
    }

    // ------------------------------------------------------------------ HomeActions

    override fun launch(pkg: String) {
        val i = packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg) ?: return
        start(i)
    }

    /** A Keep watching card: the app's own link for the show, only ever to the app that owns it, else just the app. */
    override fun openCard(pkg: String, intentUri: String?) {
        if (intentUri != null) {
            val i = runCatching { Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME) }.getOrNull()
            if (i != null) {
                if (i.component != null && i.component!!.packageName != pkg) i.component = null
                i.`package` = pkg; i.selector = null
                if (i.resolveActivity(packageManager) != null && start(i)) return
            }
        }
        launch(pkg)
    }

    override fun openSettings() {
        FireWatch.allowSettingsNow()   // so the Home button watch leaves Settings alone on a Fire TV
        start(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }

    /** Android's own remove dialog, which always asks first. Needs REQUEST_DELETE_PACKAGES. */
    override fun uninstall(pkg: String) {
        if (pkg == packageName) return
        if (!start(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))) appInfo(pkg)
    }

    override fun appInfo(pkg: String) {
        start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
    }

    private fun start(i: Intent): Boolean = try {
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: Exception) { Log.w("tvhome", "could not start $i: ${e.message}"); false }

    companion object {
        @Volatile private var shown: WeakReference<MainActivity>? = null

        /** True while the home is resumed (in front). FireWatch checks this after a move to front on Android 10 (upstream #202). */
        fun isInFront(): Boolean = shown?.get()?.resumed == true

        /** Home pressed, or FireWatch brought us back: go to the first tile. */
        fun backToFirstTile() {
            val a = shown?.get() ?: return
            a.main.post { if (!a.isFinishing && !a.isDestroyed && a::home.isInitialized) a.home.goHome() }
        }
    }
}
