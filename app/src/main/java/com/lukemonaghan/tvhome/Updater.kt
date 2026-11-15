package com.lukemonaghan.tvhome

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Updates from this app's own GitHub releases (Home settings, Updates). Ported from couch-tv's Updater.kt.
 * Off until a release address (owner/name) is set, because this project has no public repository yet.
 * Only when the household said yes (asked once): once a week, while the home is on screen, the app asks
 * GitHub which release is the latest (one request, no account, no key). "Check now" asks straight away.
 * A newer release puts a dot on the Settings tile and offers "Update to ...". Nothing is installed without the
 * person at the TV: OK downloads tvhome.apk from that release, checks it, and hands it to Android's own
 * installer, which asks "Do you want to update this app?" first.
 *
 * Before Android's installer ever sees the file: it came over https from github.com and GitHub's own download
 * hosts and nowhere else; its size and SHA-256 are the ones GitHub lists; it is this app, with a higher
 * versionCode, signed with the same key.
 */
object Updater {
    private const val STORE = "update"
    private const val ASSET = "tvhome.apk"
    private const val WEEK_MS = 7 * 24 * 60 * 60 * 1000L
    private const val RETRY_MS = 60 * 60 * 1000L   // a weekly check that failed (no Wi-Fi yet) tries again an hour later
    private const val RESULT = "com.lukemonaghan.tvhome.UPDATE_RESULT"
    private val REPO = Regex("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}")
    private val SHA256 = Regex("[0-9a-f]{64}")

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    // What is happening now (not kept): "" (nothing), "checking", "downloading", "installing".
    @Volatile var busy = ""; private set
    @Volatile var progress = 0; private set
    // Why the last try stopped: "" (it did not), "net", "bad", "other_key", "allow", "cancelled", "failed".
    @Volatile var error = ""; private set
    @Volatile private var asking = false

    /** A new release is on offer: the Settings tile shows a dot. Kept up to date by [changed]. */
    @Volatile var ready = false; private set

    /** Called on the main thread whenever the state changes. */
    @Volatile var listener: (() -> Unit)? = null

    private fun store(ctx: Context) = ctx.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    fun repo(ctx: Context): String? = store(ctx).getString("repo", null)?.takeIf { REPO.matches(it) }

    /** A release address like "owner/name"; empty switches updates off. Returns false when it is not valid. */
    fun setRepo(ctx: Context, value: String): Boolean {
        val v = value.trim()
        if (v.isNotEmpty() && !REPO.matches(v)) return false
        val e = store(ctx).edit()
        if (v.isEmpty()) e.remove("repo") else e.putString("repo", v)
        e.remove("latest").remove("url").remove("checked").apply()
        changed(ctx)
        return true
    }

    private fun current(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "0"

    /** True when version a is newer than b ("v0.4" > "0.3", "0.10" > "0.9"). */
    fun newer(a: String, b: String): Boolean {
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }; val q = y.getOrElse(i) { 0 }
            if (p != q) return p > q
        }
        return false
    }

    private fun parts(v: String) = v.trim().removePrefix("v").split('.', '-', '+').map { s -> s.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    class State(
        val supported: Boolean, val current: String, val latest: String, val title: String, val available: Boolean,
        val refused: Boolean, val checked: Long, val auto: Boolean, val answered: Boolean,
        val busy: String, val progress: Int, val error: String,
    )

    fun state(ctx: Context): State {
        val s = store(ctx)
        val latest = s.getString("latest", "") ?: ""
        val cur = current(ctx)
        val refused = latest.isNotEmpty() && s.getString("other_key", "") == latest
        val stale = latest.isNotEmpty() && s.getString("not_newer", "") == latest
        val offered = latest.isNotEmpty() && newer(latest, cur) && !s.getString("url", "").isNullOrEmpty() && !refused && !stale
        return State(repo(ctx) != null, cur, latest, s.getString("title", "") ?: "", offered, refused && newer(latest, cur),
            s.getLong("checked", 0L), s.getBoolean("auto", false), s.contains("auto"), busy, progress, error)
    }

    private fun changed(ctx: Context) {
        ready = state(ctx).available
        main.post { listener?.invoke() }
    }

    /** Called once from Plat.init so the dot is right before anything is drawn. */
    fun load(ctx: Context) { ready = state(ctx).available }

    fun setAuto(ctx: Context, on: Boolean) {
        store(ctx).edit().putBoolean("auto", on).apply()
        changed(ctx)
        if (on) checkIfDue(ctx.applicationContext)   // a yes looks straight away, then weekly
    }

    /** From MainActivity.onResume: settle a cancelled install, then the weekly check when it is due and switched on. */
    fun resumed(ctx: Context) {
        val app = ctx.applicationContext
        if (busy == "installing" && asking) main.postDelayed({ if (busy == "installing" && asking) finish(app, "cancelled") }, 4000)
        if (error == "allow" && canInstall(app)) { error = ""; changed(app) }
        checkIfDue(app)
    }

    /** From the home's minute tick: the weekly check, when it is due and switched on. */
    fun tick(ctx: Context) = checkIfDue(ctx.applicationContext)

    private fun checkIfDue(ctx: Context) {
        val s = store(ctx)
        val now = System.currentTimeMillis()
        if (repo(ctx) == null || !s.getBoolean("auto", false) || busy.isNotEmpty()) return
        if (now - s.getLong("checked", 0L) < WEEK_MS || now - s.getLong("tried", 0L) < RETRY_MS) return
        check(ctx, false)
    }

    /** Asks GitHub for the latest release. manual: "Check now", so a failure is shown. */
    fun check(ctx: Context, manual: Boolean) {
        val app = ctx.applicationContext
        val repo = repo(app) ?: return
        if (busy.isNotEmpty()) return
        busy = "checking"
        if (manual) error = ""
        changed(app)
        io.execute {
            val s = store(app)
            s.edit().putLong("tried", System.currentTimeMillis()).apply()
            try {
                val j = JSONObject(connect("https://api.github.com/repos/$repo/releases/latest", true).let { c ->
                    try { c.inputStream.bufferedReader(Charsets.UTF_8).readText() } finally { c.disconnect() }
                })
                val tag = j.getString("tag_name").trim()
                var url = ""; var digest = ""; var size = 0L
                val assets = j.optJSONArray("assets")
                for (i in 0 until (assets?.length() ?: 0)) {
                    val a = assets!!.getJSONObject(i)
                    if (a.optString("name") != ASSET) continue
                    url = a.optString("browser_download_url").takeIf { it.startsWith("https://github.com/$repo/releases/download/") } ?: ""
                    digest = a.optString("digest").removePrefix("sha256:").lowercase().takeIf { SHA256.matches(it) } ?: ""
                    size = a.optLong("size", 0L)
                }
                val latest = tag.removePrefix("v")
                val e = s.edit().putLong("checked", System.currentTimeMillis())
                    .putString("latest", latest).putString("title", j.optString("name").trim().take(120))
                    .putString("url", url).putString("digest", digest).putLong("size", size)
                if (s.getString("other_key", "") != latest) e.remove("other_key")
                if (s.getString("not_newer", "") != latest) e.remove("not_newer")
                e.apply()
                error = ""
            } catch (x: Exception) {
                if (manual) error = "net"
            }
            busy = ""
            changed(app)
            if (!newer(s.getString("latest", "") ?: "", current(app))) File(app.cacheDir, "update").deleteRecursively()
        }
    }

    /** "Update to ...": download, check, then Android's installer asks the person at the TV. */
    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val s = store(app)
        val url = s.getString("url", "") ?: ""
        val latest = s.getString("latest", "") ?: ""
        if (busy.isNotEmpty() || repo(app) == null || url.isEmpty() || !newer(latest, current(app))) return
        if (!canInstall(app)) {
            // Android's "install unknown apps" switch for this app, where the TV has that screen.
            error = "allow"
            changed(app)
            val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (i.resolveActivity(app.packageManager) != null) main.post { try { app.startActivity(i) } catch (e: Exception) {} }
            return
        }
        busy = "downloading"; progress = 0; error = ""
        changed(app)
        io.execute {
            val file = File(File(app.cacheDir, "update").apply { mkdirs() }, ASSET)
            val why = try {
                download(app, url, file, s.getLong("size", 0L))
                verify(app, file, s.getString("digest", "") ?: "", s.getLong("size", 0L))
            } catch (x: Exception) { "net" }
            if (why.isNotEmpty()) {
                file.delete()
                if (why == "other_key") s.edit().putString("other_key", latest).apply()
                if (why == "not_newer") s.edit().putString("not_newer", latest).apply()
                finish(app, if (why == "not_newer") "" else why)
                return@execute
            }
            try { busy = "installing"; changed(app); commit(app, file) } catch (x: Exception) { finish(app, "failed") }
        }
    }

    private fun finish(ctx: Context, why: String) { asking = false; busy = ""; progress = 0; error = why; changed(ctx) }

    // ---------------------------------------------------------------- GitHub

    /** An https address on GitHub, following its redirects by hand so each hop is checked. */
    private fun connect(start: String, json: Boolean): HttpURLConnection {
        var u = URL(start)
        for (hop in 0 until 6) {
            if (u.protocol != "https" || !gitHub(u.host)) throw IOException("not GitHub")
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 8000; c.readTimeout = 20000
            c.setRequestProperty("User-Agent", "tvhome")
            if (json) c.setRequestProperty("Accept", "application/vnd.github+json")
            val code = c.responseCode
            if (code in 300..399) {
                val next = c.getHeaderField("Location"); c.disconnect()
                u = URL(u, next ?: throw IOException("redirect without an address")); continue
            }
            if (code !in 200..299) { c.disconnect(); throw IOException("HTTP $code") }
            return c
        }
        throw IOException("too many redirects")
    }

    private fun gitHub(host: String) = host == "github.com" || host == "api.github.com" || host.endsWith(".githubusercontent.com")

    private fun download(ctx: Context, url: String, file: File, size: Long) {
        val c = connect(url, false)
        try {
            val total = if (size > 0) size else if (Build.VERSION.SDK_INT >= 24) c.contentLengthLong else c.contentLength.toLong()
            var done = 0L; var shown = -1
            c.inputStream.use { inp ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (total > 0 && done > total) throw IOException("bigger than GitHub said")
                        val p = if (total > 0) (done * 100 / total).toInt() else 0
                        if (p >= shown + 5) { shown = p; progress = p; changed(ctx) }
                    }
                }
            }
        } finally { c.disconnect() }
    }

    // ---------------------------------------------------------------- the checks

    /** "" when the file may go to Android's installer, else why not ("bad", "other_key", "not_newer"). */
    private fun verify(ctx: Context, file: File, digest: String, size: Long): String {
        if (size > 0 && file.length() != size) return "bad"
        if (digest.isNotEmpty() && sha256(file) != digest) return "bad"
        val pm = ctx.packageManager
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val mine = pm.getPackageInfo(ctx.packageName, flag)
        val theirs = pm.getPackageArchiveInfo(file.path, flag) ?: return "bad"
        if (theirs.packageName != ctx.packageName) return "bad"
        if (versionCode(theirs) <= versionCode(mine)) return "not_newer"
        val a = signers(mine); val b = signers(theirs)
        // Some builds read no signer from a file that is not installed yet: Android's installer makes the same check then.
        if (a.isNotEmpty() && b.isNotEmpty() && a != b) return "other_key"
        return ""
    }

    private fun signers(p: PackageInfo): Set<String> {
        val list: Array<Signature>? = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) p.signingInfo?.apkContentsSigners else null)
            ?: @Suppress("DEPRECATION") p.signatures
        return list?.map { it.toCharsString() }?.toSet() ?: emptySet()
    }

    /** May this app hand an update to Android's installer? Android 8 and newer ask per app; older has one switch for all. */
    private fun canInstall(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.packageManager.canRequestPackageInstalls()
        else @Suppress("DEPRECATION") Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 1

    private fun versionCode(p: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { inp ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = inp.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ---------------------------------------------------------------- Android's installer

    private fun commit(ctx: Context, file: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            file.inputStream().use { inp ->
                session.openWrite(ASSET, 0, file.length()).use { out -> inp.copyTo(out); session.fsync(out) }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val back = Intent(ctx, Result::class.java).setAction(RESULT)
            session.commit(PendingIntent.getBroadcast(ctx, id, back, flags).intentSender)
        }
    }

    /** What Android's installer says: it needs the person at the TV (its screen is shown), then cancelled, refused or done. Not exported. */
    class Result : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != RESULT) return
            when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                        else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_INTENT)
                    if (confirm == null) { finish(c, "failed"); return }
                    try { c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); asking = true } catch (e: Exception) { finish(c, "failed") }
                }
                PackageInstaller.STATUS_SUCCESS -> finish(c, "")
                PackageInstaller.STATUS_FAILURE_ABORTED -> finish(c, "cancelled")
                PackageInstaller.STATUS_FAILURE_CONFLICT -> finish(c, "other_key")
                else -> finish(c, "failed")
            }
            File(c.cacheDir, "update").deleteRecursively()
        }
    }
}
