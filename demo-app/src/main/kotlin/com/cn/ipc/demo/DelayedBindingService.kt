package com.cn.ipc.demo

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log

/** Test-only delayed binding; never use this main-thread delay in a business Service. */
class DelayedBindingService : Service() {
    override fun onBind(intent: Intent?): IBinder {
        Log.i("IpcLifecycleProbe", "DELAYED_BIND started delayMs=1500")
        Thread.sleep(1500)
        Log.i("IpcLifecycleProbe", "DELAYED_BIND returned=true")
        return Binder()
    }
}
