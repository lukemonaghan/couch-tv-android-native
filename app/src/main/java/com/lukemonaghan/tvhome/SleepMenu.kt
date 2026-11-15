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
 * The sleep timer's dropdown: couch-tv's `.sleep-menu`, a short list straight under the Sleep timer button with
 * 15, 30, 60, 90 minutes and Off. It opens on 30 minutes, or on Off when a timer is already running. Up and down
 * choose, OK sets it, Back closes. One Canvas in a transparent dialog, so the home underneath stays as it is.
 */
object SleepMenu {
    val CHOICES = intArrayOf(15, 30, 60, 90, 0)   // 0 = off

    /** [anchorRight] and [anchorBottom] are the button's right edge and bottom in pixels; [running] a timer is set. [done] gets minutes (0 = off). */
    fun show(ctx: Context, anchorRight: Float, anchorBottom: Float, running: Boolean, done: (Int) -> Unit) {
        val dialog = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = MenuView(ctx, anchorRight, anchorBottom, if (running) CHOICES.size - 1 else 1) { m ->
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

    private class MenuView(ctx: Context, private val aRight: Float, private val aBottom: Float, start: Int,
                           private val done: (Int?) -> Unit) : View(ctx) {
        private var sel = start
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        init { isFocusable = true; isFocusableInTouchMode = true }

        override fun onKeyDown(code: Int, e: KeyEvent): Boolean {
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT -> { if (sel > 0) sel--; invalidate(); return true }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> { if (sel < CHOICES.size - 1) sel++; invalidate(); return true }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> { done(null); return true }
            }
            return super.onKeyDown(code, e)
        }

        override fun onKeyUp(code: Int, e: KeyEvent): Boolean {
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) { done(CHOICES[sel]); return true }
            return super.onKeyUp(code, e)
        }

        private fun label(m: Int) = if (m > 0) "$m min" else "Off"

        override fun onDraw(c: Canvas) {
            val u = height / 1080f
            val pad = 8 * u; val gap = 4 * u; val rowH = 44 * u
            text.typeface = Fonts.semibold(context); text.textSize = 22 * u
            var tw = 0f
            for (m in CHOICES) tw = maxOf(tw, text.measureText(label(m)))
            val w = maxOf(176 * u, tw + 40 * u + pad * 2)
            val h = rowH * CHOICES.size + gap * (CHOICES.size - 1) + pad * 2
            val left = (aRight - w).coerceIn(0f, maxOf(0f, width - w))
            val top = aBottom + 12 * u
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
            for (i in CHOICES.indices) {
                val focus = i == sel
                c.save()
                if (focus) c.scale(1.03f, 1.03f, left + w / 2, y + rowH / 2)
                if (focus) { rect.set(left + pad, y, left + w - pad, y + rowH); paint.color = Color.WHITE; c.drawRoundRect(rect, 20 * u, 20 * u, paint) }
                text.textAlign = Paint.Align.LEFT
                text.color = if (focus) Color.rgb(17, 17, 17) else Color.argb(217, 255, 255, 255)
                c.drawText(label(CHOICES[i]), left + pad + 20 * u, y + rowH / 2 + 8 * u, text)
                c.restore()
                y += rowH + gap
            }
        }
    }
}
