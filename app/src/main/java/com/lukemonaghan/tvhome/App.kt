package com.lukemonaghan.tvhome

import android.app.Application

/** Starts the crash guard before anything else in the app runs (CrashGuard.kt). */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashGuard.install(this)
        TclPlaceholderInput.enableIfTcl(this)   // TCL only, upstream issue #94
    }
}
