package com.cn.ipc.server

import android.os.IBinder
import android.os.IInterface
import android.os.RemoteException

/**
 * ID-indexed subscriptions with Binder death cleanup. One active registration per Binder;
 * registering it again replaces the previous ID. User callbacks always run outside the lock.
 * A broadcast already in progress may deliver one last event after unregister/kill.
 */
class IpcSubscriptionManager<T : IInterface> {
    private val lock = Any()
    private val entries = mutableMapOf<Long, Entry>()
    private val binders = mutableMapOf<IBinder, Entry>()
    private var nextId = 1L
    private var killed = false

    private inner class Entry(val id: Long, val observer: T, val binder: IBinder) {
        var dead = false
        val recipient = IBinder.DeathRecipient {
            synchronized(lock) {
                dead = true
                if (entries[id] === this) entries.remove(id)
                if (binders[binder] === this) binders.remove(binder)
            }
        }

        fun unlink() {
            try { binder.unlinkToDeath(recipient, 0) } catch (_: Exception) { }
        }
    }

    /** Throws if closed or the observer is already dead; never returns a false success ID. */
    fun register(observer: T): Long {
        var cleanup: Entry? = null
        try {
            return synchronized(lock) {
                check(!killed) { "IPC subscription manager is closed" }
                val binder = observer.asBinder()
                val entry = Entry(nextId++, observer, binder)
                cleanup = entry
                binder.linkToDeath(entry.recipient, 0)
                if (entry.dead || !binder.isBinderAlive) throw RemoteException("IPC observer is dead")
                val previous = binders.put(binder, entry)
                entries[entry.id] = entry
                if (previous != null) entries.remove(previous.id)
                cleanup = previous
                entry.id
            }
        } finally {
            cleanup?.unlink()
        }
    }

    fun unregister(subscriptionId: Long) {
        val entry = synchronized(lock) {
            entries.remove(subscriptionId)?.also {
                if (binders[it.binder] === it) binders.remove(it.binder)
            }
        }
        entry?.unlink()
    }

    /**
     * Snapshot semantics permit concurrent broadcasts and synchronous callback re-entry.
     * RemoteException is ignored; other exceptions, including cancellation, propagate and stop
     * this broadcast. Callers needing per-observer isolation must handle/report recoverable
     * application errors inside action; programming errors are never silently swallowed.
     */
    fun broadcast(action: (T) -> Unit) {
        val snapshot = synchronized(lock) { entries.values.toList() }
        for (entry in snapshot) {
            if (!synchronized(lock) { entries[entry.id] === entry }) continue
            try {
                action(entry.observer)
            } catch (_: RemoteException) {
                // Transient transport errors are not proof of death; the recipient owns cleanup.
            }
        }
    }

    /** Permanently closes this manager and detaches all death recipients. */
    fun kill() {
        val snapshot = synchronized(lock) {
            killed = true
            entries.values.toList().also {
                entries.clear()
                binders.clear()
            }
        }
        snapshot.forEach { it.unlink() }
    }
}
