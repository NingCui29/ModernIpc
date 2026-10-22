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

/** Caps in-flight RPCs and keeps their jobs addressable by the original caller. */
class IpcRequestTracker(maxInFlight: Int = 128) {
    private data class Key(val uid: Int, val callback: IBinder, val requestId: Long)

    private val permits = Semaphore(maxInFlight)
    private val requests = ConcurrentHashMap<Key, RequestEntry>()
    private val lifecycleLock = Any()
    @Volatile private var disposed = false

    /** One object owns start visibility, death notification and completion cleanup. */
    private inner class RequestEntry(val key: Key, val scope: CoroutineScope) :
        IBinder.DeathRecipient, (Throwable?) -> Unit {
        lateinit var job: Job
        @Volatile var started = false

        override fun binderDied() {
            // A delayed notification belongs to this job, even if its key has been reused.
            job.cancel()
        }

        override fun invoke(cause: Throwable?) {
            requests.remove(key, this)
            try { key.callback.unlinkToDeath(this, 0) } catch (_: Exception) {}
            permits.release()
            if (cause != null && !started) {
                val message = when {
                    disposed -> "SERVER_DISPOSED"
                    !scope.isActive -> "SERVER_SCOPE_CANCELLED"
                    else -> "REQUEST_CANCELLED"
                }
                sendError(key.callback, key.requestId, message)
            }
        }
    }

    /** Snapshot of tracked jobs; cancellation is complete only after this reaches zero. */
    val activeRequestCount: Int get() = requests.size

    fun dispose() {
        val activeJobs = synchronized(lifecycleLock) {
            if (disposed) return
            disposed = true
            requests.values.map { it.job }
        }
        activeJobs.forEach { it.cancel(CancellationException("SERVER_DISPOSED")) }
    }

    fun submit(callback: IBinder?, requestId: Long, scope: CoroutineScope, block: suspend () -> Unit) {
        if (callback == null) return
        val key = Key(Binder.getCallingUid(), callback, requestId)
        val tracing = IpcRequestTrace.nowIfEnabled() != 0L
        var rejection: String? = null
        val entry = synchronized(lifecycleLock) {
            when {
                disposed -> { rejection = "SERVER_DISPOSED"; null }
                !scope.isActive -> { rejection = "SERVER_SCOPE_CANCELLED"; null }
                !permits.tryAcquire() -> { rejection = "SERVER_BUSY"; null }
                else -> {
                    val candidate = RequestEntry(key, scope)
                    candidate.job = if (tracing) {
                        scope.launch(start = CoroutineStart.LAZY) {
                            candidate.started = true
                            IpcRequestTrace.recordAt("server", "server_job_start", requestId, callback = callback)
                            block()
                        }
                    } else {
                        // Keep the disabled wrapper's captures identical to the ordinary request path.
                        scope.launch(start = CoroutineStart.LAZY) {
                            candidate.started = true
                            block()
                        }
                    }
                    if (requests.putIfAbsent(key, candidate) == null) {
                        candidate
                    } else {
                        permits.release()
                        candidate.job.cancel()
                        rejection = "DUPLICATE_REQUEST"
                        null
                    }
                }
            }
        }
        if (entry == null) {
            sendError(callback, requestId, rejection ?: "SERVER_UNAVAILABLE")
            return
        }
        try {
            callback.linkToDeath(entry, 0)
        } catch (_: Exception) {
            entry.job.cancel()
        }
        entry.job.invokeOnCompletion(entry)
        IpcRequestTrace.recordAt("server", "server_enqueued", requestId, callback = callback)
        entry.job.start()
    }

    fun cancel(callback: IBinder?, requestId: Long) {
        if (callback == null) return
        requests[Key(Binder.getCallingUid(), callback, requestId)]?.job?.cancel()
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
