package com.cn.ipc.demo

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Explicit cold-discovery fixture. Only metadata is blocked; control and business calls stay usable. */
class ColdDiscoveryBrokerService : MyBrokerService() {
    private class Counters {
        val metadata = AtomicInteger()
        val checked = AtomicInteger()
        val legacy = AtomicInteger()
    }

    private class Gate(val serviceId: Int, val failOnRelease: Boolean) {
        val latch = CountDownLatch(1)
        var claimed = false
    }

    private val counters = ConcurrentHashMap<Int, Counters>()
    private val gateLock = Any()
    private val gates = mutableListOf<Gate>()
    private val activeBlocked = AtomicInteger()
    private val peakBlocked = AtomicInteger()
    private val entered = AtomicInteger()
    private val finished = AtomicInteger()
    private val watchdogs = AtomicInteger()
    private var exposedBinder: IBinder? = null

    override fun onBind(intent: Intent?): IBinder? {
        exposedBinder?.let { return it }
        val delegate = super.onBind(intent) ?: return null
        return object : Binder() {
            init { attachInterface(null, DESCRIPTOR) }

            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code in ARM..RESET) {
                    data.enforceInterface(DESCRIPTOR)
                    if (Binder.getCallingUid() != Process.myUid()) {
                        throw SecurityException("Cold discovery controls require the application UID")
                    }
                    val response = reply ?: return false
                    val serviceId = data.readInt()
                    when (code) {
                        ARM -> {
                            val failOnRelease = data.readInt() != 0
                            synchronized(gateLock) {
                                check(gates.size < 4) { "At most four metadata gates can be armed" }
                                gates.add(Gate(serviceId, failOnRelease))
                            }
                        }
                        RELEASE -> releaseAll()
                        RESET -> {
                            check(activeBlocked.get() == 0) { "Cannot reset blocked metadata" }
                            synchronized(gateLock) { check(gates.isEmpty()) { "Release armed gates before reset" } }
                            counters.clear()
                            peakBlocked.set(0)
                            entered.set(0)
                            finished.set(0)
                            watchdogs.set(0)
                        }
                    }
                    val count = counters[serviceId]
                    response.writeNoException()
                    response.writeInt(count?.metadata?.get() ?: 0)
                    response.writeInt(count?.checked?.get() ?: 0)
                    response.writeInt(count?.legacy?.get() ?: 0)
                    response.writeInt(activeBlocked.get())
                    response.writeInt(peakBlocked.get())
                    response.writeInt(entered.get())
                    response.writeInt(finished.get())
                    response.writeInt(watchdogs.get())
                    response.writeInt(Process.myPid())
                    return true
                }
                if (code == IBinder.FIRST_CALL_TRANSACTION + 2 ||
                    code == IBinder.FIRST_CALL_TRANSACTION + 3 ||
                    code == IBinder.FIRST_CALL_TRANSACTION + 4) {
                    // Restore the complete original Parcel before forwarding to the real AIDL Stub.
                    val position = data.dataPosition()
                    val serviceId = try {
                        data.enforceInterface(DESCRIPTOR)
                        data.readInt()
                    } finally { data.setDataPosition(position) }
                    val count = counters.computeIfAbsent(serviceId) { Counters() }
                    when (code) {
                        IBinder.FIRST_CALL_TRANSACTION + 2 -> count.legacy.incrementAndGet()
                        IBinder.FIRST_CALL_TRANSACTION + 4 -> count.checked.incrementAndGet()
                        IBinder.FIRST_CALL_TRANSACTION + 3 -> {
                            count.metadata.incrementAndGet()
                            val gate = synchronized(gateLock) {
                                gates.firstOrNull { !it.claimed && it.serviceId == serviceId }
                                    ?.also { it.claimed = true }
                            }
                            if (gate != null) {
                                val active = activeBlocked.incrementAndGet()
                                peakBlocked.updateAndGet { maxOf(it, active) }
                                entered.incrementAndGet()
                                try {
                                    if (!gate.latch.await(WATCHDOG_MS, TimeUnit.MILLISECONDS)) {
                                        watchdogs.incrementAndGet()
                                        throw IllegalStateException("cold-discovery-watchdog")
                                    }
                                    if (gate.failOnRelease) throw IllegalStateException("cold-discovery-injected-failure")
                                } finally {
                                    activeBlocked.decrementAndGet()
                                    finished.incrementAndGet()
                                }
                            }
                        }
                    }
                }
                // Do not clear Binder calling identity: authorization still sees the real client.
                return delegate.transact(code, data, reply, flags)
            }
        }.also { exposedBinder = it }
    }

    private fun releaseAll() {
        val released = synchronized(gateLock) { gates.toList().also { gates.clear() } }
        released.forEach { it.latch.countDown() }
    }

    override fun onDestroy() {
        releaseAll()
        super.onDestroy()
    }

    companion object {
        const val DESCRIPTOR = "com.cn.ipc.IIpcBroker"
        const val ARM = 720001
        const val RELEASE = 720002
        const val READ = 720003
        const val RESET = 720004
        private const val WATCHDOG_MS = 10_000L
    }
}
