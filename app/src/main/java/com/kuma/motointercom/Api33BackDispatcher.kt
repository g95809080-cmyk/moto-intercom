package com.kuma.motointercom

import android.app.Activity
import android.os.Build
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal object Api33BackDispatcher {
    fun register(activity: Activity, onBack: () -> Unit): Any {
        val callback = OnBackInvokedCallback { onBack() }
        activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            callback
        )
        return callback
    }

    fun unregister(activity: Activity, callback: Any) {
        activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(
            callback as OnBackInvokedCallback
        )
    }
}
