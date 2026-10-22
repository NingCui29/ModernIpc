package com.cn.ipc

import android.os.IBinder

/** Matches only the API used by the tracker; trace collection is disabled. */
object IpcRequestTrace {
    fun nowIfEnabled(): Long = 0L
    @Suppress("UNUSED_PARAMETER")
    fun recordAt(side: String, event: String, requestId: Long, callback: IBinder) {}
}
