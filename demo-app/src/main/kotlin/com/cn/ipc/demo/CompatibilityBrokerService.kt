package com.cn.ipc.demo

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import java.util.concurrent.atomic.AtomicInteger

/** Test-only Broker wrapper; forwarding preserves the original Binder calling identity. */
open class CompatibilityBrokerService : MyBrokerService() {
    protected open val supportsSchemaMethods: Boolean = true
    private var exposedBinder: IBinder? = null

    override fun onBind(intent: Intent?): IBinder? {
        exposedBinder?.let { return it }
        val delegate = super.onBind(intent) ?: return null
        val metadataCalls = AtomicInteger()
        val checkedCalls = AtomicInteger()
        val legacyCalls = AtomicInteger()
        return object : Binder() {
            init { attachInterface(null, DESCRIPTOR) }

            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == READ_COUNTERS || code == RESET_COUNTERS) {
                    data.enforceInterface(DESCRIPTOR)
                    check(Binder.getCallingUid() == Process.myUid()) { "Probe counters require the application UID" }
                    val response = reply ?: return false
                    if (code == RESET_COUNTERS) {
                        metadataCalls.set(0)
                        checkedCalls.set(0)
                        legacyCalls.set(0)
                    }
                    response.writeNoException()
                    response.writeInt(metadataCalls.get())
                    response.writeInt(checkedCalls.get())
                    response.writeInt(legacyCalls.get())
                    return true
                }
                when (code) {
                    IBinder.FIRST_CALL_TRANSACTION + 2 -> legacyCalls.incrementAndGet()
                    IBinder.FIRST_CALL_TRANSACTION + 3 -> {
                        metadataCalls.incrementAndGet()
                        if (!supportsSchemaMethods) return false
                    }
                    IBinder.FIRST_CALL_TRANSACTION + 4 -> {
                        checkedCalls.incrementAndGet()
                        if (!supportsSchemaMethods) return false
                    }
                }
                // AIDL '= N' codes are FIRST_CALL_TRANSACTION + N, not N itself.
                return delegate.transact(code, data, reply, flags)
            }
        }.also { exposedBinder = it }
    }

    companion object {
        const val DESCRIPTOR = "com.cn.ipc.IIpcBroker"
        const val READ_COUNTERS = 710001
        const val RESET_COUNTERS = 710002
    }
}

/** Simulates a Broker that implements only the historical handshake/getService methods. */
class LegacyBrokerService : CompatibilityBrokerService() {
    override val supportsSchemaMethods: Boolean = false
}
