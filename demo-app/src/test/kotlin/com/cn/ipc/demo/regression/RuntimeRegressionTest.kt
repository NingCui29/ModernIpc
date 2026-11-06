package com.cn.ipc.demo.regression

import android.content.*
import android.os.*
import com.cn.ipc.IpcRequestTrace
import com.cn.ipc.client.*
import com.cn.ipc.server.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RuntimeRegressionTest {
    private class Service(context: Context) : IRegressionServiceServerStub(context) {
        public override val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var sent: String? = null
        override suspend fun echo(requestId: Long) = requestId
        override suspend fun mix(requestId: Long, controller: String, data: String, responseBinder: String,
                                 _data: Long, __ipcArgument0: Long, kotlin: String) =
            "$requestId|$controller|$data|$responseBinder|$_data|$__ipcArgument0|$kotlin"
        override suspend fun bundle(requestId: Long, data: Bundle) = requestId + data.getLong("offset")
        override fun read(data: String) = data
        override fun send(data: String) { sent = data }
        override fun events(data: String): Flow<Int> = flow {
            when (data) {
                "timeout" -> withTimeout(40) { awaitCancellation() }
                "cancel" -> throw CancellationException("business-cancel")
                "wait" -> awaitCancellation()
                "burst" -> repeat(1000) { emit(it) }
                else -> emit(7)
            }
        }
    }

    private class DeathBinder(private val delegate: IBinder? = null) : Binder() {
        val recipients = CopyOnWriteArrayList<IBinder.DeathRecipient>()
        var alive = true
        var onUnlink: (() -> Unit)? = null
        override fun queryLocalInterface(descriptor: String): IInterface? = delegate?.queryLocalInterface(descriptor)
        override fun isBinderAlive() = alive
        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
            if (!alive) throw DeadObjectException()
            recipients += recipient
        }
        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean {
            onUnlink?.invoke()
            return recipients.remove(recipient)
        }
        fun die() { alive = false; recipients.toList().forEach { it.binderDied() } }
    }

    private class Harness(maxRetries: Int = 0) : ContextWrapper(RuntimeEnvironment.getApplication()) {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = Service(this)
        val broker = IpcBrokerStub(this, DefaultIpcServiceRegistry().apply {
            register(RegisteredService(7801, 1, "test", binder = service))
        })
        val deathBinder = DeathBinder(broker)
        @Volatile var connection: ServiceConnection? = null
        val binds = AtomicInteger()
        val controller = IpcConnectionController(this, Intent("regression"), owner,
            maxReconnectAttempts = maxRetries, bindingTimeoutMs = 2_000, defaultCallTimeoutMs = 3_000)
        val client = IRegressionServiceClientAdapter(controller)
        override fun bindService(intent: Intent, conn: ServiceConnection, flags: Int): Boolean {
            connection = conn
            if (binds.incrementAndGet() > 1) return false // Bound the retry scenario.
            conn.onServiceConnected(ComponentName(packageName, "Broker"), deathBinder)
            return true
        }
        override fun unbindService(conn: ServiceConnection) { }
        suspend fun start() {
            controller.connect()
            withTimeout(3_000) { controller.state.first { it is IpcClientState.Connected } }
        }
        suspend fun stop() { controller.disposeAndJoin(); service.dispose(); owner.cancel() }
    }


    @Test fun registryRegistrationIsAtomicAndDisposalClosesBeforeCallbacks() {
        val registry = DefaultIpcServiceRegistry()
        val successes = AtomicInteger()
        val failures = CopyOnWriteArrayList<Throwable>()
        val start = CountDownLatch(1)
        val disposed = AtomicInteger()
        val owner = object : Binder(), IpcServiceLifecycle {
            override val activeRequestCount = 0
            override val activeSubscriptionCount = 0
            override fun dispose() {
                disposed.incrementAndGet()
                val probe = Thread {
                    try {
                        assertNull(registry.get(1))
                        assertTrue(registry.getServiceVersions().isEmpty())
                        assertTrue(runCatching { registry.register(RegisteredService(2, 1, "", binder = Binder())) }.exceptionOrNull() is IllegalStateException)
                    } catch (e: Throwable) { failures += e }
                }
                probe.start()
                probe.join(2000)
                assertFalse("Dispose callback must run outside registry lock", probe.isAlive)
            }
        }
        val threads = List(16) {
            Thread {
                start.await()
                try {
                    registry.register(RegisteredService(1, 1, "", binder = owner))
                    successes.incrementAndGet()
                } catch (_: IllegalArgumentException) {
                } catch (e: Throwable) { failures += e }
                registry.getServiceVersions()
            }.apply { start() }
        }
        start.countDown()
        threads.forEach { it.join(3000); assertFalse(it.isAlive) }
        assertEquals(1, successes.get())
        registry.register(RegisteredService(2, 1, "", binder = owner))
        registry.dispose()
        registry.dispose()
        assertEquals(1, disposed.get())
        assertTrue(failures.toString(), failures.isEmpty())
    }

    @Test fun rejectedStreamEventTerminatesAndReleasesSubscription() = runBlocking {
        val h = Harness()
        val terminal = CompletableDeferred<String>()
        val events = AtomicInteger()
        val observer = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == 1) { events.incrementAndGet(); return false }
                if (code == 3) terminal.complete(data.readString().orEmpty())
                return true
            }
        }
        try {
            val request = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                request.writeInterfaceToken(IRegressionServiceIpcSchema.DESCRIPTOR)
                request.writeString("burst")
                request.writeStrongBinder(observer)
                assertTrue(h.service.transact(20, request, reply, 0))
                reply.readException()
            } finally { request.recycle(); reply.recycle() }
            assertEquals("IPC stream observer rejected event", withTimeout(3000) { terminal.await() })
            withTimeout(3000) { while (h.service.activeSubscriptionCount != 0) delay(1) }
            assertEquals(1, events.get())
            assertTrue(h.service.coroutineScope.isActive)
        } finally { h.stop() }
    }


    @Test fun breakingParcelableRevisionRequiresVersionFloor() {
        val context = RuntimeEnvironment.getApplication()
        val service = Service(context)
        val registry = DefaultIpcServiceRegistry()
        registry.register(RegisteredService(7801, 2, "v2", binder = service, minSupportedClientVersion = 2))
        val broker = IpcBrokerStub(context, registry)
        val old = IRegressionServiceIpcSchema.CLIENT_SCHEMA
        try {
            assertTrue(runCatching { broker.getService(7801, 1) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { broker.getServiceChecked(7801, 1, old) }.exceptionOrNull() is IllegalArgumentException)
            val current = com.cn.ipc.ClientServiceSchema(old.descriptor, 2, old.stableMethodSignatures)
            assertSame(service, broker.getServiceChecked(7801, 2, current))
        } finally { registry.dispose() }
    }


    @org.robolectric.annotation.Implements(className = "android.app.ApplicationPackageManager", isInAndroidSdk = false)
    class SignaturePackageManager : org.robolectric.shadows.ShadowApplicationPackageManager() {
        companion object {
            var allow = true
            var checks = 0
        }
        @org.robolectric.annotation.Implementation
        public override fun checkSignatures(uid1: Int, uid2: Int): Int {
            checks++
            return if (allow) android.content.pm.PackageManager.SIGNATURE_MATCH else android.content.pm.PackageManager.SIGNATURE_NO_MATCH
        }
    }

    @Test @Config(shadows = [SignaturePackageManager::class])
    fun brokerRechecksCrossUidAuthorizationAfterSuccessfulCall() {
        val context = RuntimeEnvironment.getApplication()
        val registry = DefaultIpcServiceRegistry()
        registry.register(RegisteredService(7801, 1, "", binder = Binder()))
        val broker = IpcBrokerStub(context, registry)
        val uid = android.os.Process.myUid() + 1000
        val pm = org.robolectric.Shadows.shadowOf(context.packageManager)
        pm.setPackagesForUid(uid, "caller.test")
        SignaturePackageManager.allow = true
        SignaturePackageManager.checks = 0
        org.robolectric.shadows.ShadowBinder.setCallingUid(uid)
        try {
            assertNotNull(broker.getService(7801, 1))
            SignaturePackageManager.allow = false
            assertTrue(runCatching { broker.getService(7801, 1) }.exceptionOrNull() is SecurityException)
            assertEquals(2, SignaturePackageManager.checks)
            SignaturePackageManager.allow = true
            pm.setPackagesForUid(uid)
            assertTrue(runCatching { broker.getService(7801, 1) }.exceptionOrNull() is SecurityException)
        } finally {
            org.robolectric.shadows.ShadowBinder.reset()
            registry.dispose()
        }
    }

    @Test fun generatedArgumentsKeepBusinessValuesWithTraceOnAndOff() = runBlocking {
        val h = Harness()
        try {
            h.start()
            for (trace in listOf(false, true)) {
                if (trace) IpcRequestTrace.start("argument-regression")
                try {
                    assertEquals(987654321L, h.client.echo(requestId = 987654321L))
                    assertEquals("99|controller|data|callback|77|88|kotlin", h.client.mix(
                        requestId = 99, controller = "controller", data = "data", responseBinder = "callback",
                        _data = 77, __ipcArgument0 = 88, kotlin = "kotlin"))
                    assertEquals(908L, h.client.bundle(900, Bundle().apply { putLong("offset", 8) }))
                } finally { if (trace) assertTrue(IpcRequestTrace.stopAndFlush().events > 0) }
            }
            withContext(Dispatchers.IO) {
                assertEquals("direct", h.client.read("direct"))
                h.client.send("oneway")
            }
            assertEquals("oneway", h.service.sent)
            assertEquals(listOf(7), h.client.events("normal").toList())
        } finally { h.stop() }
    }

    @Test fun rawRequestIdRemainsIndependentOfBusinessRequestId() = runBlocking {
        val h = Harness()
        try {
            val response = CompletableDeferred<Pair<Long, Long>>()
            val count = AtomicInteger()
            val callback = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    count.incrementAndGet()
                    val id = data.readLong()
                    check(data.readInt() == 1)
                    response.complete(id to data.readLong())
                    return true
                }
            }
            val request = Parcel.obtain()
            try {
                request.writeInterfaceToken(IRegressionServiceIpcSchema.DESCRIPTOR)
                request.writeLong(123)
                request.writeLong(987)
                request.writeStrongBinder(callback)
                check(h.service.transact(10, request, null, IBinder.FLAG_ONEWAY))
            } finally { request.recycle() }
            assertEquals(123L to 987L, withTimeout(3_000) { response.await() })
            withTimeout(3_000) { while (h.service.activeRequestCount != 0) delay(1) }
            assertEquals(1, count.get())
        } finally { h.stop() }
    }

    @Test fun flowBusinessCancellationKeepsCauseAndScopeAlive() = runBlocking {
        val h = Harness()
        try {
            h.start()
            for (mode in listOf("cancel", "timeout")) {
                val failure = try {
                    withTimeout(3_000) { h.client.events(mode).toList() }
                    error("Expected remote error")
                } catch (error: RuntimeException) { error }
                assertFalse(failure is TimeoutCancellationException)
                assertFalse(failure.message.orEmpty().contains("SERVER_SCOPE_CANCELLED"))
                if (mode == "cancel") assertEquals("business-cancel", failure.message)
                else assertTrue(failure.message.orEmpty().contains("Timed out"))
                assertTrue(h.service.coroutineScope.isActive)
                withTimeout(3_000) { while (h.service.activeSubscriptionCount != 0) delay(1) }
                assertEquals(12345L, h.client.echo(12345))
            }
        } finally { h.stop() }
    }

    @Test fun deathAndDisconnectRespectZeroAndOneRetryInBothOrders() = runBlocking {
        for (retries in 0..1) for (deathFirst in listOf(true, false)) {
            val h = Harness(retries)
            try {
                h.start()
                val connection = h.connection!!
                val recipients = h.deathBinder.recipients.toList()
                if (deathFirst) recipients.forEach { it.binderDied() }
                else connection.onServiceDisconnected(null)
                withTimeout(3_000) { h.controller.state.first { it !is IpcClientState.Connected } }
                if (deathFirst) connection.onServiceDisconnected(null)
                else recipients.forEach { it.binderDied() }
                withTimeout(3_000) { h.controller.state.first { it is IpcClientState.Disconnected } }
                delay(650) // Beyond first retry's maximum backoff, to catch an extra bind.
                assertEquals("retries=$retries deathFirst=$deathFirst", 1 + retries, h.binds.get())
                assertTrue(h.deathBinder.recipients.isEmpty())
            } finally { h.stop() }
        }
    }

    @Test fun rawFlowHasOneTerminalAndQuietUnsubscribe() = runBlocking {
        for (mode in listOf("cancel", "timeout", "scope", "dispose", "unsubscribe")) {
            val h = Harness()
            val events = CopyOnWriteArrayList<Pair<Int, String?>>()
            val callback = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    events += code to if (code == 3) data.readString() else null
                    return true
                }
            }
            try {
                val request = Parcel.obtain()
                val reply = Parcel.obtain()
                val id: Long
                try {
                    request.writeInterfaceToken(IRegressionServiceIpcSchema.DESCRIPTOR)
                    request.writeString(if (mode in listOf("cancel", "timeout")) mode else "wait")
                    request.writeStrongBinder(callback)
                    check(h.service.transact(20, request, reply, 0))
                    reply.readException()
                    id = reply.readLong()
                } finally { request.recycle(); reply.recycle() }
                when (mode) {
                    "scope" -> h.service.coroutineScope.cancel()
                    "dispose" -> h.service.dispose()
                    "unsubscribe" -> {
                        val cancel = Parcel.obtain()
                        try {
                            cancel.writeInterfaceToken(IRegressionServiceIpcSchema.DESCRIPTOR)
                            cancel.writeLong(id)
                            check(h.service.transact(21, cancel, null, IBinder.FLAG_ONEWAY))
                        } finally { cancel.recycle() }
                    }
                }
                // Joining every child also waits for completion hooks, rather than sampling counts early.
                withTimeout(3_000) { h.service.coroutineScope.coroutineContext[Job]!!.children.toList().joinAll() }
                withTimeout(3_000) { while (h.service.activeSubscriptionCount != 0) delay(1) }
                if (mode == "unsubscribe") assertTrue(events.isEmpty())
                else {
                    assertEquals(1, events.size)
                    assertEquals(3, events.single().first)
                    val message = events.single().second.orEmpty()
                    when (mode) {
                        "cancel" -> assertEquals("business-cancel", message)
                        "timeout" -> assertTrue(message.contains("Timed out"))
                        "scope" -> assertEquals("SERVER_SCOPE_CANCELLED", message)
                        "dispose" -> assertEquals("SERVER_DISPOSED", message)
                    }
                }
            } finally { h.stop() }
        }
    }

    @Test fun awaitConnectionFailsPromptlyAndCanStartFreshAttempt() = runBlocking {
        val h = Harness()
        var accept = false
        var callback: ServiceConnection? = null
        val bound = CompletableDeferred<Unit>()
        val context = object : ContextWrapper(h) {
            override fun bindService(intent: Intent, conn: ServiceConnection, flags: Int): Boolean {
                callback = conn
                bound.complete(Unit)
                if (accept) conn.onServiceConnected(null, h.broker)
                return accept
            }
            override fun unbindService(conn: ServiceConnection) { }
        }
        val controller = IpcConnectionController(context, Intent("test"), h.owner, maxReconnectAttempts = 0)
        try {
            val error = runCatching { withTimeout(1500) { controller.awaitConnected(5000) } }.exceptionOrNull()
            assertTrue(error is RemoteException)
            accept = true
            assertTrue(controller.awaitConnected() is IpcClientState.Connected)
            controller.closeAndJoin()
            assertTrue(controller.awaitConnected() is IpcClientState.Connected)
        } finally { controller.disposeAndJoin(); h.stop() }
    }

    @Test fun registrationDetachesOutsideManagerLock() {
        val manager = IpcSubscriptionManager<Observer>()
        val observer = Observer()
        manager.register(observer)
        val completed = java.util.concurrent.atomic.AtomicBoolean()
        observer.binder.onUnlink = {
            val thread = Thread { manager.broadcast { }; completed.set(true) }
            thread.start()
            thread.join(1500)
        }
        manager.register(observer)
        assertTrue(completed.get())
        completed.set(false)
        observer.binder.alive = false
        assertTrue(runCatching { manager.register(observer) }.exceptionOrNull() is RemoteException)
        assertTrue(completed.get())
        observer.binder.onUnlink = null
        manager.kill()
    }

    @Test fun closeFailsOldWaitersEvenWhenConnectionImmediatelyReopens() = runBlocking {
        val h = Harness()
        val calls = java.util.concurrent.LinkedBlockingQueue<ServiceConnection>()
        val context = object : ContextWrapper(h) {
            override fun bindService(intent: Intent, conn: ServiceConnection, flags: Int): Boolean {
                calls.add(conn)
                return true
            }
            override fun unbindService(conn: ServiceConnection) { }
        }
        val controller = IpcConnectionController(context, Intent("test"), h.owner, maxReconnectAttempts = 0)
        try {
            val old = async(Dispatchers.Default) { runCatching { controller.awaitConnected() } }
            assertNotNull(withContext(Dispatchers.IO) { calls.poll(2, TimeUnit.SECONDS) })
            controller.closeAndJoin()
            val fresh = async(Dispatchers.Default) { controller.awaitConnected() }
            val connection = withContext(Dispatchers.IO) { calls.poll(2, TimeUnit.SECONDS) }!!
            connection.onServiceConnected(null, h.broker)
            assertTrue(withTimeout(3000) { old.await() }.exceptionOrNull() is IllegalStateException)
            assertTrue(withTimeout(3000) { fresh.await() } is IpcClientState.Connected)
        } finally { controller.disposeAndJoin(); h.stop() }
    }

    @Test fun broadcastPreservesFailFastAndCancellationContract() {
        val manager = IpcSubscriptionManager<Observer>()
        manager.register(Observer())
        manager.register(Observer())
        var calls = 0
        manager.broadcast { calls++; throw RemoteException("transport") }
        assertEquals(2, calls)
        val failure = IllegalArgumentException("application")
        calls = 0
        val caught = runCatching { manager.broadcast { calls++; throw failure } }.exceptionOrNull()
        assertSame(failure, caught)
        assertEquals(1, calls)
        val cancelled = CancellationException("cancel")
        assertSame(cancelled, runCatching { manager.broadcast { throw cancelled } }.exceptionOrNull())
        manager.kill()
    }

    private class Observer(val binder: DeathBinder = DeathBinder()) : IInterface {
        override fun asBinder(): IBinder = binder
    }

    @Test fun subscriptionCallbacksCanUnregisterReenterAndBroadcastConcurrently() {
        val manager = IpcSubscriptionManager<Observer>()
        val first = manager.register(Observer())
        manager.broadcast { manager.unregister(first); manager.broadcast { fail("Removed observer") } }
        var calls = 0
        manager.broadcast { calls++ }
        assertEquals(0, calls)
        manager.register(Observer())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val thread = Thread {
            try { manager.broadcast { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) } }
            catch (error: Throwable) { failure.set(error) }
        }
        thread.start()
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            manager.broadcast { calls++ }
            assertEquals(1, calls)
            manager.kill()
        } finally { release.countDown(); thread.join(3_000) }
        assertNull(failure.get())
        assertFalse(thread.isAlive)
    }

    @Test fun closedAndDeadObserversCannotRegisterAndStaleDeathCannotRemoveReplacement() {
        val manager = IpcSubscriptionManager<Observer>()
        val observer = Observer()
        val oldId = manager.register(observer)
        val oldDeath = observer.binder.recipients.single()
        val newId = manager.register(observer)
        assertNotEquals(oldId, newId)
        oldDeath.binderDied()
        manager.unregister(oldId)
        var calls = 0
        manager.broadcast { calls++ }
        assertEquals(1, calls)
        observer.binder.die()
        manager.broadcast { fail("Dead observer") }
        try { manager.register(observer); fail("Dead registration succeeded") } catch (_: RemoteException) { }
        manager.kill()
        manager.kill()
        try { manager.register(Observer()); fail("Closed registration succeeded") } catch (_: IllegalStateException) { }
    }

    @Test fun clearingOldGenerationCannotRemoveReplacementSubscription() {
        val readOldEntry = CountDownLatch(1)
        val replaced = CountDownLatch(1)
        // Pause the actual registry's map iterator after selecting the old entry.
        val map = object : java.util.concurrent.ConcurrentHashMap<Long, SubscriptionRecord>() {
            override val entries: MutableSet<MutableMap.MutableEntry<Long, SubscriptionRecord>>
                get() {
                    val original = super.entries
                    return object : MutableSet<MutableMap.MutableEntry<Long, SubscriptionRecord>> by original {
                        override fun iterator(): MutableIterator<MutableMap.MutableEntry<Long, SubscriptionRecord>> {
                            val iterator = original.iterator()
                            return object : MutableIterator<MutableMap.MutableEntry<Long, SubscriptionRecord>> by iterator {
                                override fun next(): MutableMap.MutableEntry<Long, SubscriptionRecord> {
                                    val entry = iterator.next()
                                    readOldEntry.countDown()
                                    check(replaced.await(3, TimeUnit.SECONDS))
                                    return entry
                                }
                            }
                        }
                    }
                }
        }
        val registry = SubscriptionRegistry()
        SubscriptionRegistry::class.java.getDeclaredField("records").apply {
            isAccessible = true
            set(registry, map)
        }
        val binder = Binder()
        registry.register(1, 1, 100, 20, binder)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val cleaner = Thread {
            try { registry.clearGeneration(1) } catch (error: Throwable) { failure.set(error) }
        }
        cleaner.start()
        try {
            assertTrue(readOldEntry.await(3, TimeUnit.SECONDS))
            registry.register(1, 2, 100, 20, binder)
        } finally { replaced.countDown(); cleaner.join(3_000) }
        assertFalse(cleaner.isAlive)
        assertNull(failure.get())
        assertEquals(2L, registry.unregister(1)?.generation)
    }
}
