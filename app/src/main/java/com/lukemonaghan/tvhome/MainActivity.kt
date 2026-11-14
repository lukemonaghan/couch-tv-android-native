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
import android.content.Intent
import android.util.Log
import android.view.View
import java.lang.ref.WeakReference
import java.util.Calendar

/** Placeholder home: a clock on a dark background, drawn on a Canvas. Proves the build and the emulator loop. */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var resumed = false

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
        setContentView(ClockView(this))
        FireWatch.ensure(this)   // only once a computer has granted WRITE_SECURE_SETTINGS over ADB
        main.postDelayed({ FireWatch.rebindIfUnbound(applicationContext) }, 10_000L)   // listed but not bound after a force-stop
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The remote's gear button, caught by KeyWatch: the gear menu comes later.
        if (intent.getBooleanExtra("gear", false)) { Log.i("tvhome", "gear pressed"); return }
        backToFirstTile()   // Home pressed while we are already in front
    }

    override fun onResume() { super.onResume(); resumed = true }
    override fun onPause() { resumed = false; super.onPause() }
    override fun onDestroy() { main.removeCallbacksAndMessages(null); super.onDestroy() }

    companion object {
        @Volatile private var shown: WeakReference<MainActivity>? = null

        /** True while the home is resumed (in front). FireWatch checks this after a move to front on Android 10 (upstream #202). */
        fun isInFront(): Boolean = shown?.get()?.resumed == true

        /** Home pressed, or FireWatch brought us back: go to the first tile. Nothing to reset yet. */
        fun backToFirstTile() {}
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
