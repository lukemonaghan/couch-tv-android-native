package com.lukemonaghan.tvhome

import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.text.TextPaint
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager

/**
 * The music view's "choose a preset" list (OK on the preset button). The same tree as Settings, Browse your presets:
 * Favourites and the categories, then groups, then presets, but only installed ones, and OK on a preset plays it now instead of
 * turning it on or off. Back goes up a level and closes at the top. One Canvas in a translucent dialog, like [ListMenu], so the
 * visualiser stays visible around it.
 */
object PresetPicker {
    private class Entry(val title: String, val note: String = "", val star: Boolean = false, val dim: Boolean = false, val now: Boolean = false, val open: (() -> Unit)? = null, val play: String? = null)

    fun show(ctx: Context, prefs: SharedPreferences, playing: String, hover: (String) -> Unit, changed: () -> Unit, done: (String?) -> Unit) {
        val dialog = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = PickView(ctx, prefs, playing, hover, changed) { path -> dialog.dismiss(); done(path) }
        dialog.setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        dialog.show()
        view.requestFocus()
    }

    private class PickView(ctx: Context, private val prefs: SharedPreferences, private val playing: String, private val hover: (String) -> Unit, private val changed: () -> Unit, private val done: (String?) -> Unit) : View(ctx) {
        private val index = PresetStore.index(ctx)
        private val installed = PresetStore.installed(ctx)
        private var favs = PresetStore.favs(prefs)
        private val off = PresetStore.off(prefs)
        private var cat: String? = null
        private var group: String? = null
        private var fav = false
        private var opts = false
        private var sel = 0
        private var scroll = 0
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private var entries: List<Entry> = emptyList()
        private var crumb = ""

        init { isFocusable = true; isFocusableInTouchMode = true; build() }

        private fun nameOf(p: String) = p.substringAfterLast('/').removeSuffix(".milk")
        private fun go(c: String?, g: String?, f: Boolean, o: Boolean = false) { cat = c; group = g; fav = f; opts = o; sel = 0; scroll = 0; build(); invalidate(); preview() }

        // the highlighted preset plays behind the card once the cursor has rested on it for a moment
        private val previewRun = Runnable { entries.getOrNull(sel)?.play?.let { hover(it) } }
        private fun preview() { removeCallbacks(previewRun); if (entries.getOrNull(sel)?.play != null) postDelayed(previewRun, 350) }
        override fun onDetachedFromWindow() { removeCallbacks(previewRun); super.onDetachedFromWindow() }

        private fun preset(p: String) = Entry(nameOf(p), "", p in favs, p in off, nameOf(p) == playing, null, p)

        private fun build() {
            val l = ArrayList<Entry>()
            val c = cat; val g = group
            if (opts) {
                crumb = "Options"
                val secs = prefs.getInt("viz_secs", 45)
                l += Entry("New preset every", if (secs == 0) "Only by hand" else if (secs >= 60) "${secs / 60} min${if (secs % 60 != 0) " ${secs % 60} s" else ""}" else "$secs seconds", open = { cycle("viz_secs", SECS, 45) })
                l += Entry("Play", if (prefs.getBoolean("viz_favs_only", false)) "Favourites only" else "Everything that is on", open = { flip("viz_favs_only", false) })
                l += Entry("Brightness", "${prefs.getInt("viz_bright", 100)}%", open = { val b = prefs.getInt("viz_bright", 100); prefs.edit().putInt("viz_bright", if (b <= 20) 100 else b - 10).apply(); refresh() })
                l += Entry("Hide the buttons after", prefs.getInt("viz_autohide", 0).let { if (it == 0) "Never" else "$it seconds" }, open = { cycle("viz_autohide", HIDE, 0) })
                l += Entry("Active lyric lines", prefs.getInt("np_lyr_lines", 3).let { if (it == 1) "1 line" else "$it lines" }, open = { cycle("np_lyr_lines", LYR_LINES, 3) })
                l += Entry("Lyrics with the buttons hidden", if (prefs.getBoolean("np_lyrics_hidden", false)) "On" else "Off", open = { flip("np_lyrics_hidden", false) })
            } else if (fav) {
                crumb = "Favourites"
                installed.filter { it in favs }.sortedBy { nameOf(it).lowercase() }.forEach { l += preset(it) }
            } else if (c == null) {
                crumb = "Choose a preset"
                l += Entry("⚙  Options", "", open = { go(null, null, false, true) })
                val nFav = installed.count { it in favs }
                if (nFav > 0) l += Entry("★  Favourites", "$nFav", open = { go(null, null, true) })
                for (cc in index.cats) {
                    val n = index.groups[cc].orEmpty().sumOf { gr -> gr.files.indices.count { gr.path(it) in installed } }
                    if (n > 0) l += Entry(cc, "$n", open = { go(cc, null, false) })
                }
            } else if (g == null) {
                crumb = c
                for (gr in index.groups[c].orEmpty()) {
                    val n = gr.files.indices.count { gr.path(it) in installed }
                    if (n > 0) l += Entry(gr.name, "$n", open = { go(c, gr.name, false) })
                }
            } else {
                crumb = "$c  ›  $g"
                index.groups[c].orEmpty().firstOrNull { it.name == g }?.let { gr -> gr.files.indices.map { gr.path(it) }.filter { it in installed }.forEach { l += preset(it) } }
            }
            entries = l
            if (l.isEmpty()) crumb += "  (nothing installed)"
        }

        private val SECS = listOf(0, 20, 45, 90, 180)
        private val HIDE = listOf(0, 5, 10, 20, 40)
        private val LYR_LINES = listOf(1, 3, 5, 7)
        // an option changed: rebuild the rows where they are, and tell the visualiser
        private fun refresh() { build(); invalidate(); changed() }
        private fun cycle(key: String, steps: List<Int>, def: Int) { val v = prefs.getInt(key, def); val i = steps.indexOfFirst { it > v }; prefs.edit().putInt(key, if (i < 0) steps[0] else steps[i]).apply(); refresh() }
        private fun flip(key: String, def: Boolean) { prefs.edit().putBoolean(key, !prefs.getBoolean(key, def)).apply(); refresh() }

        // hold OK on a preset: star it or unstar it, here and in Settings, and tell the visualiser (favourites-only play)
        private fun toggleFav() {
            val path = entries.getOrNull(sel)?.play ?: return
            PresetStore.setFav(prefs, listOf(path), path !in favs)
            favs = PresetStore.favs(prefs)
            build(); sel = sel.coerceAtMost((entries.size - 1).coerceAtLeast(0)); invalidate(); changed()
        }

        private fun up(): Boolean {
            if (opts) { go(null, null, false); return true }
            if (fav || group != null) { go(if (fav) null else cat, null, false); return true }
            if (cat != null) { go(null, null, false); return true }
            return false
        }

        // as in ListMenu: a release only counts if this list saw its press (it opens while OK is still held)
        private var armed = false
        private var held = false   // a long press already did its job for this press of OK

        override fun onKeyDown(code: Int, e: KeyEvent): Boolean {
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) { 
                if (e.repeatCount == 0) { armed = true; held = false }
                else if (armed && !held && e.isLongPress) { held = true; toggleFav() }
                return true
            }
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> { if (sel > 0) sel--; invalidate(); preview(); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { if (sel < entries.size - 1) sel++; invalidate(); preview(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> { if (!up()) { removeCallbacks(previewRun); done(null) }; return true }
            }
            return super.onKeyDown(code, e)
        }

        override fun onKeyUp(code: Int, e: KeyEvent): Boolean {
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (!armed) return true
                armed = false
                if (held) { held = false; return true }
                entries.getOrNull(sel)?.let { en -> if (en.play != null) { removeCallbacks(previewRun); done(en.play) } else en.open?.invoke() }
                return true
            }
            return super.onKeyUp(code, e)
        }

        override fun onDraw(c: Canvas) {
            val u = height / 1080f
            val w = 700 * u; val rowH = 62 * u; val gap = 6 * u; val pad = 18 * u
            val fit = 9
            val h = 84 * u + fit * (rowH + gap) + pad
            val left = width - w - 56 * u; val top = (height - h) / 2f
            paint.style = Paint.Style.FILL
            rect.set(left, top, left + w, top + h)
            paint.color = Color.argb(235, 24, 24, 28); c.drawRoundRect(rect, 28 * u, 28 * u, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = u; paint.color = Color.argb(36, 255, 255, 255); c.drawRoundRect(rect, 28 * u, 28 * u, paint)
            paint.style = Paint.Style.FILL
            text.typeface = Fonts.semibold(context); text.textSize = 24 * u; text.color = Color.argb(150, 255, 255, 255); text.textAlign = Paint.Align.LEFT; text.letterSpacing = 0.04f
            c.drawText(crumb.uppercase(), left + pad + 14 * u, top + 52 * u, text); text.letterSpacing = 0f
            if (entries.getOrNull(sel)?.play != null) {
                text.typeface = Fonts.regular(context); text.textSize = 20 * u; text.textAlign = Paint.Align.RIGHT; text.color = Color.argb(130, 255, 255, 255)
                c.drawText("Hold OK to ★ favourite", left + w - pad - 14 * u, top + 52 * u, text); text.textAlign = Paint.Align.LEFT
            }
            text.typeface = Fonts.semibold(context)
            if (sel < scroll) scroll = sel
            if (sel >= scroll + fit) scroll = sel - fit + 1
            var y = top + 74 * u
            for (i in scroll until minOf(entries.size, scroll + fit)) {
                val en = entries[i]; val f = i == sel
                if (f) { rect.set(left + pad, y, left + w - pad, y + rowH); paint.color = Color.WHITE; c.drawRoundRect(rect, 18 * u, 18 * u, paint) }
                val fg = if (f) Color.rgb(17, 17, 17) else if (en.dim) Color.argb(120, 255, 255, 255) else Color.WHITE
                var right = left + w - pad - 20 * u
                if (en.now) { text.typeface = Fonts.medium(context); text.textSize = 21 * u; text.textAlign = Paint.Align.RIGHT; text.color = if (f) Color.argb(170, 17, 17, 17) else Color.argb(160, 255, 255, 255); c.drawText("Playing", right, y + rowH / 2 + 7 * u, text); right -= 100 * u }
                if (en.star) { text.typeface = Fonts.regular(context); text.textSize = 24 * u; text.textAlign = Paint.Align.RIGHT; text.color = if (f) Color.argb(170, 17, 17, 17) else Color.argb(170, 255, 255, 255); c.drawText("★", right, y + rowH / 2 + 8 * u, text); right -= 44 * u }
                if (en.note.isNotEmpty()) { text.typeface = Fonts.medium(context); text.textSize = 24 * u; text.textAlign = Paint.Align.RIGHT; text.color = if (f) Color.argb(170, 17, 17, 17) else Color.argb(150, 255, 255, 255); c.drawText(en.note, right, y + rowH / 2 + 8 * u, text); right -= text.measureText(en.note) + 30 * u }
                text.typeface = Fonts.semibold(context); text.textSize = 27 * u; text.textAlign = Paint.Align.LEFT; text.color = fg
                val t = TextUtils.ellipsize(en.title, text, right - (left + pad + 20 * u), TextUtils.TruncateAt.END)
                c.drawText(t, 0, t.length, left + pad + 20 * u, y + rowH / 2 + 9 * u, text)
                y += rowH + gap
            }
            if (entries.isEmpty()) { text.typeface = Fonts.regular(context); text.textSize = 24 * u; text.textAlign = Paint.Align.CENTER; text.color = Color.argb(150, 255, 255, 255); c.drawText("Nothing here yet. Get presets in Settings.", width / 2f, top + h / 2, text) }
            text.textAlign = Paint.Align.LEFT
        }
    }
}
