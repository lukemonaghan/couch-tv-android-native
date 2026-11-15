package com.lukemonaghan.tvhome

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.Executors

/** One row of the Look and scenes page; Settings maps it to its own row type. */
class LookRow(val title: String, val note: String = "", val value: String = "", val check: Boolean? = null,
              val header: Boolean = false, val press: (() -> Unit)? = null)

/**
 * The rows Home settings, Look and scenes adds for this bundle (couch-tv's lookRows): the Classic photo, Glass, Bedtime,
 * the video background and the household's own photos. All the state is in [Looks] and the "home" prefs.
 */
class LookSettings(private val ctx: Context, private val prefs: SharedPreferences, private val act: HomeActions,
                   private val changed: () -> Unit) {
    private val looks = Looks.of(ctx, prefs)
    private val worker = Executors.newSingleThreadExecutor()

    /** Under the look choices, Classic only: stop the background changing, and which photo to keep. */
    fun photoRows(look: String?): List<LookRow> {
        if (look != "CLASSIC") return emptyList()
        val list = looks.photos()
        val keep = looks.keepPhoto
        val rows = ArrayList<LookRow>()
        rows.add(LookRow("Keep one photo", "Stop the background changing", check = keep) {
            looks.keepPhoto = !keep
            if (!keep) looks.keepPick = looks.shownPhoto.ifEmpty { list.firstOrNull()?.file ?: "" }
        })
        if (keep) {
            val i = list.indexOfFirst { it.file == looks.keepPick }.coerceAtLeast(0)
            rows.add(LookRow("Photo", "", "Photo ${i + 1} of ${list.size}") { looks.keepPick = list[(i + 1) % list.size].file })
        }
        return rows
    }

    /** After the scenes: glass, bedtime, video and photos. */
    fun rows(): List<LookRow> {
        val r = ArrayList<LookRow>()
        r.add(LookRow("GLASS", header = true))
        r.add(LookRow("Glass panels", "Clear, softly blurred panels behind the clock, Keep watching and the apps",
            when (looks.glass) { Looks.Glass.ON -> "Glass"; Looks.Glass.LITE -> "Glass, no blur"; Looks.Glass.MUTED -> "Muted blur"; else -> "Off" }) {
            looks.glass = when (looks.glass) { Looks.Glass.OFF -> Looks.Glass.ON; Looks.Glass.ON -> Looks.Glass.LITE; Looks.Glass.LITE -> Looks.Glass.MUTED; Looks.Glass.MUTED -> Looks.Glass.OFF }
        })
        r.add(LookRow("Card picture", "The show or album picture, blurred, fills each Keep watching card and the music bar", check = looks.cardArt) { looks.cardArt = !looks.cardArt })
        r.add(LookRow("Card picture darkness", "How much shade sits over the picture so words stay easy to read",
            listOf("Light", "Medium", "Dark")[looks.cardDark]) { looks.cardDark = (looks.cardDark + 1) % 3 })
        r.add(LookRow("Corners", "How round the cards and the music bar are", listOf("Sharp", "Normal", "Round")[looks.roundness]) { looks.roundness = (looks.roundness + 1) % 3 })
        r.add(LookRow("Moving tiles", "The focused tile sways slowly", check = looks.movingTiles) { looks.movingTiles = !looks.movingTiles })
        r.add(LookRow("Lighter home", "For a slow box: no blur, no soft shadows, no sway", check = looks.lighter) { looks.lighter = !looks.lighter })
        r.add(LookRow("Glass app icons", "Each app's symbol in light glass, its colours back when you move onto it", check = looks.glassIcons) { looks.glassIcons = !looks.glassIcons })
        if (looks.glassIcons) r.add(LookRow("Focused app", "", if (looks.glassFocusStay) "Stays glass" else "Its colours") { looks.glassFocusStay = !looks.glassFocusStay })
        r.add(LookRow("How clear", "How much of the background shows through the glass, the clock and the icons",
            "${Looks.AMOUNT_NAMES[looks.amount - 1]} (${looks.amount}/5)") { looks.amount = looks.amount % 5 + 1 })
        r.add(LookRow("Clock style", "", if (looks.clockLiquid) "Liquid glass" else "Frosted") { looks.clockLiquid = !looks.clockLiquid })

        r.add(LookRow("BEDTIME", header = true))
        r.add(LookRow("Bedtime dimming", "An amber dimming over the last 15 minutes of the sleep timer", check = looks.bedtimeDim) { looks.bedtimeDim = !looks.bedtimeDim })

        r.add(LookRow("MOVING BACKGROUND", header = true))
        val url = looks.videoUrl
        if (url.isNotEmpty()) r.add(LookRow("Video background", "A short clip that loops with no sound, in place of the photo", check = looks.videoOn) { looks.videoOn = !looks.videoOn })
        r.add(LookRow("Video link", url.ifEmpty { "None yet" }, "") {
            act.askText("Type the link to an MP4 or MOV video") { v -> setVideo(v) }
        })
        if (looks.videoStatus.isNotEmpty() && url.isNotEmpty()) r.add(LookRow(looks.videoStatus))
        if (url.isNotEmpty()) r.add(LookRow("Remove the video") { looks.videoUrl = ""; looks.videoOn = false })

        r.add(LookRow("YOUR PHOTOS", header = true))
        val own = OwnPhotos.list(ctx)
        r.add(LookRow("${own.size} of ${OwnPhotos.MAX} photos", "Your photos take turns with the scenes, and the Classic look shows them instead of its own"))
        if ((ctx as? PhotoPicker) != null) r.add(LookRow("Add photos from a USB drive or this TV", "Opens the TV's file picker") {
            if (!(ctx as PhotoPicker).pickPhotos()) { looks.photoMsg = "This TV has no file picker"; changed() }
        }) else r.add(LookRow("This TV has no file picker", "Add a photo from a web address instead"))
        r.add(LookRow("Add a photo from a web address", "A JPEG or PNG on the internet, saved on this TV") {
            act.askText("Type the address of a JPEG or PNG photo") { v -> addUrl(v) }
        })
        own.forEachIndexed { i, f -> r.add(LookRow("Remove photo ${i + 1}", f.name.removeSuffix(".jpg")) { OwnPhotos.remove(ctx, f.name) }) }
        if (looks.photoMsg.isNotEmpty()) r.add(LookRow(looks.photoMsg))
        return r
    }

    private fun setVideo(raw: String) {
        var u = raw.trim()
        if (u.isEmpty()) return
        // a computer at home (typed as its number, or a name ending in .local) is usually plain http
        if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(u))
            u = (if (Regex("^(\\d+\\.){3}\\d+|^[^/]+\\.local\\b", RegexOption.IGNORE_CASE).containsMatchIn(u)) "http://" else "https://") + u
        looks.videoUrl = u; looks.videoOn = true; looks.videoStatus = "Loading the clip"
        looks.notifyChanged(); changed()
    }

    private fun addUrl(raw: String) {
        if (raw.isBlank()) return
        looks.photoMsg = "Loading the photo"; changed()
        worker.execute {
            val err = OwnPhotos.addUrl(ctx, raw.trim())
            looks.photoMsg = err ?: "Photo added. It shows as a scene."
            android.os.Handler(android.os.Looper.getMainLooper()).post { looks.notifyChanged(); changed() }
        }
    }
}
