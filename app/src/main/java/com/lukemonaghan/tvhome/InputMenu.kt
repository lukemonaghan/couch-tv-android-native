package com.lukemonaghan.tvhome

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.text.TextPaint
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager

/**
 * The Input menu: a floating card under the Input button listing the TV's inputs (each by the TV's own
 * name, with the devices HDMI-CEC found behind it), then Bluetooth and Network. Up and down choose, OK
 * switches, Back closes. Drawn like couch-tv's `.app-pop` and `.grow` rows, on one Canvas in a transparent
 * dialog so the home underneath stays as it is. No libraries.
 */
object InputMenu {
    private class Item(val act: String, val id: String, val title: String, val sub: String)

    /** [anchorRight] and [anchorBottom] are the Input button's right edge and bottom in pixels; the card hangs under it. */
    fun show(ctx: Context, anchorRight: Float, anchorBottom: Float) {
        val ports = TvInputs.list(ctx)
        if (ports.isEmpty()) {   // a TV that lists no inputs keeps its own input screen
            if (!TvInputs.openList(ctx)) TvInputs.say(ctx, TvInputs.NO_INPUTS)
            return
        }
        val items = ports.map { Item("input", it.id, it.label.ifEmpty { if (it.type == "hdmi") "HDMI" else "Input" }, it.devices.joinToString(", ")) } +
            Item("bluetooth", "", "Bluetooth", "Speakers, headphones and controllers") +
            Item("network", "", "Network", "Wi-Fi and the network")
        val dialog = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = MenuView(ctx, items, anchorRight, anchorBottom) { item ->
            dialog.dismiss()
            if (item == null) return@MenuView
            if (item.act == "input") { if (!TvInputs.open(ctx, item.id)) TvInputs.say(ctx, TvInputs.NO_INPUTS) }
            else TvInputs.openSettingsScreen(ctx, item.act)
        }
        dialog.setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        dialog.show()
        view.requestFocus()
    }

    private class MenuView(ctx: Context, private val items: List<Item>, private val aRight: Float, private val aBottom: Float,
                           private val done: (Item?) -> Unit) : View(ctx) {
        private var sel = 0
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        init { isFocusable = true; isFocusableInTouchMode = true }

        override fun onKeyDown(code: Int, e: KeyEvent): Boolean {
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> { if (sel > 0) sel--; invalidate(); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { if (sel < items.size - 1) sel++; invalidate(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> return true
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> { done(null); return true }
            }
            return super.onKeyDown(code, e)
        }

        override fun onKeyUp(code: Int, e: KeyEvent): Boolean {
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) { done(items[sel]); return true }
            return super.onKeyUp(code, e)
        }

        override fun onDraw(c: Canvas) {
            val u = height / 1080f
            val pad = 10 * u
            val iconBox = 56 * u
            // size the card to its widest row
            text.typeface = Fonts.semibold(context); text.textSize = 27 * u
            var textW = 0f
            for (it in items) textW = maxOf(textW, text.measureText(it.title))
            text.typeface = Fonts.regular(context); text.textSize = 19 * u
            for (it in items) textW = maxOf(textW, text.measureText(it.sub))
            val rowW = 14 * u + iconBox + 20 * u + textW + 28 * u
            val w = minOf(rowW + pad * 2, 672 * u)
            val gap = 4 * u
            val rowHs = items.map { (if (it.sub.isNotEmpty()) 58 * u else 34 * u).coerceAtLeast(iconBox) + 28 * u }
            val h = rowHs.sum() + gap * (items.size - 1) + pad * 2
            // under the Input button, kept inside the screen's safe area
            val left = (aRight - w).coerceIn(80 * u, maxOf(80 * u, width - w - 80 * u))
            val top = (aBottom + 20 * u).coerceIn(60 * u, maxOf(60 * u, height - h - 60 * u))

            // the card: a soft shadow, a dark plum body, a pink hairline
            paint.style = Paint.Style.FILL
            for (k in 6 downTo 1) {
                val g = 36 * u * k / 6
                rect.set(left - g, top - g + 24 * u, left + w + g, top + h + g + 24 * u)
                paint.color = Color.argb(10, 0, 0, 0)
                c.drawRoundRect(rect, 32 * u + g, 32 * u + g, paint)
            }
            rect.set(left, top, left + w, top + h)
            paint.color = Color.argb(240, 28, 24, 30)
            c.drawRoundRect(rect, 32 * u, 32 * u, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1 * u; paint.color = Color.argb(41, 255, 220, 230)
            c.drawRoundRect(rect, 32 * u, 32 * u, paint)
            paint.style = Paint.Style.FILL

            var y = top + pad
            for (i in items.indices) {
                val it = items[i]; val rh = rowHs[i]; val focus = i == sel
                c.save()
                val rcx = left + w / 2; val rcy = y + rh / 2
                if (focus) c.scale(1.04f, 1.04f, rcx, rcy)
                rect.set(left + pad, y, left + w - pad, y + rh)
                if (focus) {
                    paint.color = Color.argb(90, 0, 0, 0)
                    rect.offset(0f, 14 * u); c.drawRoundRect(rect, 24 * u, 24 * u, paint); rect.offset(0f, -14 * u)
                    paint.color = Color.WHITE
                    c.drawRoundRect(rect, 24 * u, 24 * u, paint)
                }
                val fg = if (focus) Color.rgb(17, 17, 17) else Color.argb(235, 255, 255, 255)
                // the icon tile
                val ix = left + pad + 14 * u; val iy = y + (rh - iconBox) / 2
                rect.set(ix, iy, ix + iconBox, iy + iconBox)
                paint.color = if (focus) Color.argb(18, 0, 0, 0) else Color.argb(31, 255, 255, 255)
                c.drawRoundRect(rect, 16 * u, 16 * u, paint)
                val s = 32 * u / 24f
                when (it.act) {
                    "bluetooth" -> Glyphs.bluetooth(c, paint, ix + iconBox / 2, iy + iconBox / 2, s, fg)
                    "network" -> Glyphs.network(c, paint, ix + iconBox / 2, iy + iconBox / 2, s, fg)
                    else -> Glyphs.input(c, paint, ix + iconBox / 2, iy + iconBox / 2, s, fg, 1.7f)
                }
                // the words
                val tx = ix + iconBox + 20 * u
                text.textAlign = Paint.Align.LEFT; text.color = fg
                text.typeface = Fonts.semibold(context); text.textSize = 27 * u
                val titleY = if (it.sub.isNotEmpty()) y + rh / 2 - 29 * u + 25 * u else y + rh / 2 + 9 * u
                c.drawText(it.title, tx, titleY, text)
                if (it.sub.isNotEmpty()) {
                    text.typeface = Fonts.regular(context); text.textSize = 19 * u
                    text.color = if (focus) Color.argb(153, 0, 0, 0) else Color.argb(158, 255, 255, 255)
                    c.drawText(it.sub, tx, titleY + 26 * u, text)
                }
                c.restore()
                y += rh + gap
            }
            // the hint at the foot of the screen
            text.typeface = Fonts.medium(context); text.textSize = 22 * u; text.textAlign = Paint.Align.CENTER
            text.color = Color.argb(190, 255, 255, 255)
            c.drawText("Up and down to choose, OK to switch, Back to close", width / 2f, height - 64 * u, text)
        }
    }
}
