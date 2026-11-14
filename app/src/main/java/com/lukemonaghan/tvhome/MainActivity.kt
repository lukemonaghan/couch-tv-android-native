package com.lukemonaghan.tvhome

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.View
import java.util.Calendar

/** Placeholder home: a clock on a dark background, drawn on a Canvas. Proves the build and the emulator loop. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ClockView(this))
    }

    private class ClockView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        private val tick = object : Runnable {
            override fun run() {
                invalidate()
                postDelayed(this, 1000)
            }
        }

        override fun onAttachedToWindow() { super.onAttachedToWindow(); tick.run() }
        override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.parseColor("#0D1E19"))
            val fmt = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
            paint.textSize = height * 0.28f
            canvas.drawText(DateFormat.format(fmt, Calendar.getInstance()).toString(), width / 2f, height / 2f, paint)
        }
    }
}
