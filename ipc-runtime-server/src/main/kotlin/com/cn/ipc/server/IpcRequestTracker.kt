package com.cn.ipc.server

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.cn.ipc.IpcRequestTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/** Caps in-flight RPCs and keeps their jobs addressable by the original caller. */
class IpcRequestTracker(maxInFlight: Int = 128) {
    private data class Key(val uid: Int, val callback: IBinder, val requestId: Long)

    private val permits = Semaphore(maxInFlight)
    private val jobs = ConcurrentHashMap<Key, Job>()
    private val lifecycleLock = Any()
    @Volatile private var disposed = false

    /** Snapshot of tracked jobs; cancellation is complete only after this reaches zero. */
    val activeRequestCount: Int get() = jobs.size

    fun dispose() {
        val activeJobs = synchronized(lifecycleLock) {
            if (disposed) return
            disposed = true
            jobs.values.toList()
        }
        activeJobs.forEach { it.cancel(CancellationException("SERVER_DISPOSED")) }
    }

    fun submit(callback: IBinder?, requestId: Long, scope: CoroutineScope, block: suspend () -> Unit) {
        if (callback == null) return
        val key = Key(Binder.getCallingUid(), callback, requestId)
        val started = AtomicBoolean(false)
        val tracing = IpcRequestTrace.nowIfEnabled() != 0L
        var rejection: String? = null
        val job = synchronized(lifecycleLock) {
            when {
                disposed -> { rejection = "SERVER_DISPOSED"; null }
                !scope.isActive -> { rejection = "SERVER_SCOPE_CANCELLED"; null }
                !permits.tryAcquire() -> { rejection = "SERVER_BUSY"; null }
                else -> {
                    val candidate = if (tracing) {
                        scope.launch(start = CoroutineStart.LAZY) {
                            started.set(true)
                            IpcRequestTrace.recordAt("server", "server_job_start", requestId, callback = callback)
                            block()
                        }
                    } else {
                        // Keep the disabled wrapper's captures identical to the ordinary request path.
                        scope.launch(start = CoroutineStart.LAZY) {
                            started.set(true)
                            block()
                        }
                    }
                    if (jobs.putIfAbsent(key, candidate) == null) {
                        candidate
                    } else {
                        permits.release()
                        candidate.cancel()
                        rejection = "DUPLICATE_REQUEST"
                        null
                    }
                }
            }
        }
        if (job == null) {
            sendError(callback, requestId, rejection ?: "SERVER_UNAVAILABLE")
            return
        }
        val recipient = IBinder.DeathRecipient { jobs[key]?.cancel() }
        try {
            callback.linkToDeath(recipient, 0)
        } catch (_: Exception) {
            job.cancel()
        }
        job.invokeOnCompletion { cause ->
            jobs.remove(key, job)
            try { callback.unlinkToDeath(recipient, 0) } catch (_: Exception) {}
            permits.release()
            if (cause != null && !started.get()) {
                val message = when {
                    disposed -> "SERVER_DISPOSED"
                    !scope.isActive -> "SERVER_SCOPE_CANCELLED"
                    else -> "REQUEST_CANCELLED"
                }
                sendError(callback, requestId, message)
            }
        }
        IpcRequestTrace.recordAt("server", "server_enqueued", requestId, callback = callback)
        job.start()
    }

    fun cancel(callback: IBinder?, requestId: Long) {
        if (callback == null) return
        jobs[Key(Binder.getCallingUid(), callback, requestId)]?.cancel()
    }

    /** A decoded request may fail validation before it is admitted to the tracker. */
    fun reject(callback: IBinder?, requestId: Long, message: String) {
        if (callback != null) sendError(callback, requestId, message)
    }

    private fun sendError(callback: IBinder, requestId: Long, message: String) {
        val data = Parcel.obtain()
        try {
            data.writeLong(requestId)
            data.writeInt(0)
            data.writeString(message)
            IpcRequestTrace.recordAt("server", "server_reply", requestId, callback = callback)
            callback.transact(1, data, null, IBinder.FLAG_ONEWAY)
        } catch (_: Exception) {
            // The caller may have died while its request was being rejected.
        } finally {
            data.recycle()
        }
    }
}
