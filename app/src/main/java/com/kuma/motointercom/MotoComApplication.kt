package com.kuma.motointercom

import android.app.Application

class MotoComApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagnosticLog.initialize(this)
    }
}
