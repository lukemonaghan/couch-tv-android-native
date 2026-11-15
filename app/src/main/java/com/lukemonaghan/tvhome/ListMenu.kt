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
 * A short dropdown list next to a button (the Settings button's App settings or Android settings). Up and down choose, OK
 * picks, Back closes. One Canvas in a transparent dialog, so the home underneath stays as it is. SleepMenu is the same
 * thing with the sleep timer's choices.
 */
object ListMenu {
    /**
     * [anchorRight] is the button's right edge in pixels; the list hangs under [anchor] (the button's bottom), or sits above it when
     * [above] (the button's top). [done] gets the chosen row.
     */
    fun show(ctx: Context, anchorRight: Float, anchor: Float, labels: List<String>, start: Int, above: Boolean, done: (Int) -> Unit) {
        val dialog = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = MenuView(ctx, anchorRight, anchor, labels, start, above) { m ->
            dialog.dismiss()
            if (m != null) done(m)
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

    private class MenuView(ctx: Context, private val aRight: Float, private val anchor: Float, private val labels: List<String>, start: Int, private val above: Boolean,
                           private val done: (Int?) -> Unit) : View(ctx) {
        private var sel = start
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        init { isFocusable = true; isFocusableInTouchMode = true }

        override fun onKeyDown(code: Int, e: KeyEvent): Boolean {
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT -> { if (sel > 0) sel--; invalidate(); return true }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> { if (sel < labels.size - 1) sel++; invalidate(); return true }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> { done(null); return true }
            }
            return super.onKeyDown(code, e)
        }

        override fun onKeyUp(code: Int, e: KeyEvent): Boolean {
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) { done(sel); return true }
            return super.onKeyUp(code, e)
        }

        override fun onDraw(c: Canvas) {
            val u = height / 1080f
            val pad = 8 * u; val gap = 4 * u; val rowH = 44 * u
            text.typeface = Fonts.semibold(context); text.textSize = 22 * u
            var tw = 0f
            for (m in labels) tw = maxOf(tw, text.measureText(m))
            val w = maxOf(176 * u, tw + 40 * u + pad * 2)
            val h = rowH * labels.size + gap * (labels.size - 1) + pad * 2
            val left = (aRight - w).coerceIn(0f, maxOf(0f, width - w))
            val top = if (above) anchor - 12 * u - h else anchor + 12 * u
            // the card: soft shadow, dark body, a light hairline
            paint.style = Paint.Style.FILL
            for (k in 6 downTo 1) {
                val g = 40 * u * k / 6
                rect.set(left - g, top - g + 16 * u, left + w + g, top + h + g + 16 * u)
                paint.color = Color.argb(11, 0, 0, 0)
                c.drawRoundRect(rect, 28 * u + g, 28 * u + g, paint)
            }
            rect.set(left, top, left + w, top + h)
            paint.color = Color.argb(225, 28, 28, 32)
            c.drawRoundRect(rect, 28 * u, 28 * u, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = u; paint.color = Color.argb(36, 255, 255, 255)
            c.drawRoundRect(rect, 28 * u, 28 * u, paint)
            paint.style = Paint.Style.FILL
            var y = top + pad
            for (i in labels.indices) {
                val focus = i == sel
                c.save()
                if (focus) c.scale(1.03f, 1.03f, left + w / 2, y + rowH / 2)
                if (focus) { rect.set(left + pad, y, left + w - pad, y + rowH); paint.color = Color.WHITE; c.drawRoundRect(rect, 20 * u, 20 * u, paint) }
                text.textAlign = Paint.Align.LEFT
                text.color = if (focus) Color.rgb(17, 17, 17) else Color.argb(217, 255, 255, 255)
                c.drawText(labels[i], left + pad + 20 * u, y + rowH / 2 + 8 * u, text)
                c.restore()
                y += rowH + gap
            }
        }
    }
}
