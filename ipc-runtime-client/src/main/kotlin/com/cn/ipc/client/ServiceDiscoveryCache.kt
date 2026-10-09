package com.cn.ipc.client

import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A detached cache container: no remote operation runs under a map or lifecycle lock. */
internal class ServiceDiscoveryCache<K : Any> {
    private val invalidated = AtomicBoolean(false)
    private val binders = ConcurrentHashMap<K, IBinder>()
    private val flights = ConcurrentHashMap<K, Flight>()

    fun cached(key: K): IBinder? = binders[key]?.takeIf { it.isBinderAlive }

    fun acquire(key: K, resolve: (() -> Boolean) -> IBinder): Flight {
        check(!invalidated.get()) { "IPC service cache invalidated" }
        while (true) {
            val existing = flights[key]
            if (existing != null) {
                if (existing.retain()) return existing
                flights.remove(key, existing)
                continue
            }
            val created = Flight()
            check(created.retain())
            val raced = flights.putIfAbsent(key, created)
            if (raced != null) continue
            if (invalidated.get()) {
                created.fail(IllegalStateException("IPC service cache invalidated"))
                flights.remove(key, created)
                return created
            }
            // Another resolver may have published and removed its flight after our caller's cache read.
            cached(key)?.let { binder ->
                created.succeed(binder)
                flights.remove(key, created)
                return created
            }
            try {
                workers.execute {
                    try {
                        val binder = resolve { !invalidated.get() && created.isNeeded }
                        check(!invalidated.get() && created.isNeeded) { "IPC discovery no longer needed" }
                        // A concurrent invalidation only affects this detached container, never a new generation.
                        binders[key] = binder
                        created.succeed(binder)
                    } catch (error: Throwable) {
                        created.fail(error)
                    } finally {
                        flights.remove(key, created)
                    }
                }
            } catch (error: java.util.concurrent.RejectedExecutionException) {
                created.fail(error)
                flights.remove(key, created)
            }
            return created
        }
    }

    fun release(key: K, flight: Flight) {
        if (flight.release()) flights.remove(key, flight)
    }

    fun invalidate(error: Throwable) {
        invalidated.set(true)
        binders.clear()
        flights.values.forEach { it.fail(error) }
        flights.clear()
    }

    class Flight {
        private data class Outcome(val binder: IBinder? = null, val error: Throwable? = null)
        private val outcome = AtomicReference<Outcome?>(null)
        private val result = CompletableDeferred<IBinder>()
        private val latch = CountDownLatch(1)
        private var waiters = 0
        private var abandoned = false
        val isNeeded: Boolean get() = synchronized(this) { !abandoned && outcome.get() == null }

        fun retain(): Boolean = synchronized(this) {
            if (abandoned || outcome.get() != null) false else { waiters++; true }
        }

        fun release(): Boolean {
            val abandon = synchronized(this) {
                check(waiters > 0)
                waiters--
                if (waiters == 0 && outcome.get() == null) { abandoned = true; true } else false
            }
            if (abandon) fail(kotlinx.coroutines.CancellationException("No service discovery waiters"))
            return abandon
        }

        suspend fun await(): IBinder = result.await()

        fun awaitBlocking(timeoutMs: Long): IBinder {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw TimeoutException("IPC service discovery timed out")
            val value = checkNotNull(outcome.get())
            value.error?.let { throw it }
            return checkNotNull(value.binder)
        }

        fun succeed(binder: IBinder) = finish(Outcome(binder = binder))
        fun fail(error: Throwable) = finish(Outcome(error = error))

        private fun finish(value: Outcome) {
            if (!outcome.compareAndSet(null, value)) return
            latch.countDown()
            if (value.error != null) result.completeExceptionally(value.error)
            else result.complete(checkNotNull(value.binder))
        }
    }

    companion object {
        // Synchronous Binder cannot be interrupted reliably. Bound stuck work across all controllers.
        private val workers = ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS, SynchronousQueue<Runnable>(),
            { task -> Thread(task, "IpcServiceDiscovery").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy())
    }
}
