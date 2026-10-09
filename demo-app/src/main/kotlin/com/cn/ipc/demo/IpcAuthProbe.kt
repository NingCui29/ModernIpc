package com.cn.ipc.demo

import android.content.Context
import android.os.Binder
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.cn.ipc.server.CallerAuthenticator

/** Runs inside a real Binder call, without storing artificial escape sinks. */
object IpcAuthProbe {
    private const val TAG = "IpcAuthMicro"
    private const val COUNT = 50_000

    fun run(context: Context) {
        check(Binder.getCallingUid() == Process.myUid())
        val cached = CallerAuthenticator(context)
        fun checks(legacy: Boolean, count: Int) {
            if (legacy) repeat(count) {
                val authenticator = CallerAuthenticator(context)
                authenticator.authorize(authenticator.authenticate(), 9001)
            } else repeat(count) {
                cached.authorizeCurrentCaller(9001)
            }
        }
        checks(true, 2000)
        checks(false, 2000)
        for ((index, legacy) in listOf(true, false, false, true).withIndex()) {
            val cpuStart = Debug.threadCpuTimeNanos()
            val wallStart = SystemClock.elapsedRealtimeNanos()
            checks(legacy, COUNT)
            val wall = SystemClock.elapsedRealtimeNanos() - wallStart
            val cpu = Debug.threadCpuTimeNanos() - cpuStart
            val mode = if (legacy) "legacy" else "optimized"
            Log.i(TAG, "TIME batch=$index mode=$mode count=$COUNT wallNs=$wall cpuNs=$cpu")
        }
        // Allocation statistics are process totals and sampled separately from timing.
        for ((index, legacy) in listOf(true, false, false, true).withIndex()) {
            val beforeBytes = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()
            val beforeGc = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull()
            checks(legacy, COUNT)
            val afterBytes = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()
            val afterGc = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull()
            val bytes = if (beforeBytes != null && afterBytes != null) afterBytes - beforeBytes else null
            val gc = if (beforeGc != null && afterGc != null) afterGc - beforeGc else null
            val mode = if (legacy) "legacy" else "optimized"
            Log.i(TAG, "ALLOC batch=$index mode=$mode count=$COUNT beforeBytes=$beforeBytes afterBytes=$afterBytes bytes=$bytes gc=$gc")
        }
        Log.i(TAG, "DONE sameUid=true countPerBatch=$COUNT")
    }
}
