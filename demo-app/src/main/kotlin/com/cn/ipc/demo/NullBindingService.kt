package com.cn.ipc.demo

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Explicit lifecycle probe only: exercise ServiceConnection.onNullBinding. */
class NullBindingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
