package com.cn.ipc.demo

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Raw binding exposes controls without handshaking; each gate holds one real Broker handshake. */
class HandshakeBlockingService : MyBrokerService() {
    private class Gate {
        val latch = CountDownLatch(1)
        var claimed = false
    }

    private val gateLock = Any()
    private val gates = mutableListOf<Gate>()
    private val attempts = AtomicInteger()
    private val activeBlocked = AtomicInteger()
    private val peakBlocked = AtomicInteger()
    private val entered = AtomicInteger()
    private val finished = AtomicInteger()
    private val replied = AtomicInteger()
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
                        throw SecurityException("Handshake controls require the application UID")
                    }
                    val response = reply ?: return false
                    when (code) {
                        ARM -> synchronized(gateLock) {
                            check(gates.size < 4) { "At most four handshake gates can be armed" }
                            gates.add(Gate())
                        }
                        RELEASE -> releaseAll()
                        RESET -> {
                            check(activeBlocked.get() == 0 && attempts.get() == replied.get()) { "Handshake still executing" }
                            synchronized(gateLock) { check(gates.isEmpty()) { "Release gates before reset" } }
                            attempts.set(0)
                            peakBlocked.set(0)
                            entered.set(0)
                            finished.set(0)
                            replied.set(0)
                            watchdogs.set(0)
                        }
                    }
                    response.writeNoException()
                    response.writeInt(attempts.get())
                    response.writeInt(activeBlocked.get())
                    response.writeInt(peakBlocked.get())
                    response.writeInt(entered.get())
                    response.writeInt(finished.get())
                    response.writeInt(replied.get())
                    response.writeInt(watchdogs.get())
                    response.writeInt(Process.myPid())
                    return true
                }
                if (code == IBinder.FIRST_CALL_TRANSACTION + 1) {
                    // AIDL explicitly declares handshake(...)=1, so its actual transaction code is 2.
                    val position = data.dataPosition()
                    try { data.enforceInterface(DESCRIPTOR) } finally { data.setDataPosition(position) }
                    attempts.incrementAndGet()
                    try {
                        val gate = synchronized(gateLock) {
                            gates.firstOrNull { !it.claimed }?.also { it.claimed = true }
                        }
                        if (gate != null) {
                            val active = activeBlocked.incrementAndGet()
                            peakBlocked.updateAndGet { maxOf(it, active) }
                            entered.incrementAndGet()
                            try {
                                if (!gate.latch.await(10_000, TimeUnit.MILLISECONDS)) {
                                    watchdogs.incrementAndGet()
                                    throw IllegalStateException("handshake-probe-watchdog")
                                }
                            } finally {
                                activeBlocked.decrementAndGet()
                                finished.incrementAndGet()
                            }
                        }
                        // Keep the original Binder caller identity and the untouched ClientHello Parcel.
                        return delegate.transact(code, data, reply, flags)
                    } finally { replied.incrementAndGet() }
                }
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
        const val ARM = 730001
        const val RELEASE = 730002
        const val READ = 730003
        const val RESET = 730004
    }
}
