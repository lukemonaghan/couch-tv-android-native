package com.lukemonaghan.tvhome

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        TclPlaceholderInput.enableIfTcl(this)   // TCL only, upstream issue #94
    }
}
