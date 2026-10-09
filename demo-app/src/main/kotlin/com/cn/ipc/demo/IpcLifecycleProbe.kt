package com.cn.ipc.demo

import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import com.cn.ipc.api.test.IBenchmarkEchoServiceClientAdapter
import com.cn.ipc.client.IpcClientState
import com.cn.ipc.client.IpcConnectionController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicInteger

/** Explicit lifecycle intent only; the anchor keeps the actual remote Service alive. */
object IpcLifecycleProbe {
    private const val TAG = "IpcLifecycleProbe"
    private const val CYCLES = 8

    suspend fun run(context: Context, anchor: IpcConnectionController, runId: String) =
        withContext(Dispatchers.Default) {
            val connection = anchor.awaitConnected(10_000)
            check((connection.protocol.serviceVersions[9001] ?: 0) >= 4)
            val diagnostics = IBenchmarkEchoServiceClientAdapter(anchor)
            awaitCounts(diagnostics, subscriptions = 0, requests = 0)
            closeAndReconnect(context.applicationContext, diagnostics, runId)
            cancelOwner(context.applicationContext, diagnostics, runId)
            awaitCounts(diagnostics, subscriptions = 0, requests = 0)
            destroyAndRecreateService(anchor, runId)
            bindingGates(context.applicationContext, runId)
            check(diagnostics.echoDirect("lifecycle-anchor-alive") == "lifecycle-anchor-alive")
            Log.i(TAG, "DONE run=$runId passed=true cycles=$CYCLES subscriptions=0 requests=0 anchorAlive=true")
        }

    private suspend fun closeAndReconnect(
        context: Context,
        diagnostics: IBenchmarkEchoServiceClientAdapter,
        runId: String
    ) = coroutineScope {
        val ownerJob = SupervisorJob(coroutineContext[Job])
        val worker = newController(context, ownerJob)
        val events = AtomicInteger()
        val service = IBenchmarkEchoServiceClientAdapter(worker)
        val collector = async {
            runCatching { service.observeLifecycleTicks().collect { events.incrementAndGet() } }
        }
        var heldRequest: Deferred<Result<String>>? = null
        try {
            var connected = worker.awaitConnected(10_000)
            awaitCounts(diagnostics, subscriptions = 1, requests = 0)
            awaitEvents(events, previous = 0)
            heldRequest = async { runCatching { service.holdLifecycleRequest() } }
            awaitCounts(diagnostics, subscriptions = 1, requests = 1)

            repeat(CYCLES) { index ->
                worker.closeAndJoin()
                check(worker.state.value is IpcClientState.Closed && !worker.isDisposed)
                if (index == 0) {
                    val result = withTimeout(3000) { heldRequest!!.await() }
                    check(result.isFailure) { "Closed request returned successfully" }
                    Log.i(TAG, "PASS run=$runId closeHeldRequest failed=true cause=${result.exceptionOrNull()?.javaClass?.simpleName}")
                }
                awaitCounts(diagnostics, subscriptions = 0, requests = 0)
                awaitQuiet(events)
                check(collector.isActive) { "Recoverable close ended the Flow collector" }
                Log.i(TAG, "PASS run=$runId close cycle=${index + 1} subscriptions=0 requests=0 eventsQuiet=true")

                val previousEvents = events.get()
                val reconnected = worker.awaitConnected(10_000)
                check(reconnected.generation > connected.generation)
                connected = reconnected
                awaitCounts(diagnostics, subscriptions = 1, requests = 0)
                awaitEvents(events, previousEvents)
                // Keep the source running long enough to expose a duplicate re-subscription.
                delay(100)
                check(diagnostics.inspectActiveSubscriptionCount() == 1)
                Log.i(TAG, "PASS run=$runId reconnect cycle=${index + 1} subscriptions=1 generation=${connected.generation}")
            }

            worker.disposeAndJoin()
            check(worker.isDisposed && worker.state.value is IpcClientState.Disposed)
            withTimeout(3000) { collector.await() }
            awaitCounts(diagnostics, subscriptions = 0, requests = 0)
            awaitQuiet(events)
            check(runCatching { worker.awaitConnected(500) }.exceptionOrNull() is IllegalStateException)
            Log.i(TAG, "PASS run=$runId explicitDispose collectorEnded=true reconnectRejected=true subscriptions=0 requests=0")
        } finally {
            withContext(NonCancellable) {
                worker.disposeAndJoin()
                collector.cancelAndJoin()
                heldRequest?.cancelAndJoin()
                ownerJob.cancelAndJoin()
            }
        }
    }

    private suspend fun cancelOwner(
        context: Context,
        diagnostics: IBenchmarkEchoServiceClientAdapter,
        runId: String
    ) = coroutineScope {
        val ownerJob = SupervisorJob(coroutineContext[Job])
        val worker = newController(context, ownerJob)
        val events = AtomicInteger()
        val service = IBenchmarkEchoServiceClientAdapter(worker)
        // These waiters belong to the probe, so owner cancellation cannot hide a leak.
        val collector = async {
            runCatching { service.observeLifecycleTicks().collect { events.incrementAndGet() } }
        }
        val request = async {
            worker.awaitConnected(10_000)
            runCatching { service.holdLifecycleRequest() }
        }
        try {
            worker.awaitConnected(10_000)
            awaitCounts(diagnostics, subscriptions = 1, requests = 1)
            awaitEvents(events, previous = 0)
            ownerJob.cancelAndJoin()
            withTimeout(3000) { worker.state.first { it is IpcClientState.Disposed } }
            check(worker.isDisposed)
            worker.disposeAndJoin()
            check(withTimeout(3000) { request.await() }.isFailure)
            withTimeout(3000) { collector.await() }
            awaitCounts(diagnostics, subscriptions = 0, requests = 0)
            awaitQuiet(events)
            check(runCatching { worker.awaitConnected(500) }.exceptionOrNull() is IllegalStateException)
            Log.i(TAG, "PASS run=$runId ownerCancellation disposed=true collectorEnded=true requestFailed=true subscriptions=0 requests=0 eventsQuiet=true")
        } finally {
            withContext(NonCancellable) {
                worker.disposeAndJoin()
                collector.cancelAndJoin()
                request.cancelAndJoin()
                ownerJob.cancelAndJoin()
            }
        }
    }

    private fun newController(context: Context, ownerJob: Job) = IpcConnectionController(
        context = context,
        targetIntent = Intent(context, MyBrokerService::class.java),
        scope = CoroutineScope(Dispatchers.Default + ownerJob)
    )

    private suspend fun destroyAndRecreateService(anchor: IpcConnectionController, runId: String) {
        val oldConnection = anchor.awaitConnected(10_000)
        val oldBinder = anchor.getServiceBinderForConnection(oldConnection, 9001, 4)
        // Workers are disposed; this is the last bound connection in the clean probe scene.
        anchor.closeAndJoin()
        withTimeout(4000) {
            while (true) {
                val error = readRetainedStubError(oldBinder)
                if (error != null) {
                    check(error is IllegalStateException && error.message == "IPC service disposed") {
                        "Retained Stub failed for a different reason: $error"
                    }
                    break
                }
                delay(10)
            }
        }
        check(oldBinder.isBinderAlive) { "Service destruction check observed process death instead" }
        val recreated = anchor.awaitConnected(10_000)
        check(recreated.generation > oldConnection.generation)
        val freshBinder = anchor.getServiceBinderForConnection(recreated, 9001, 4)
        check(freshBinder != oldBinder) { "Service recreation reused the disposed Stub" }
        awaitCounts(IBenchmarkEchoServiceClientAdapter(anchor), subscriptions = 0, requests = 0)
        Log.i(TAG, "PASS run=$runId serviceDestroy oldStubRejected=true binderStillAlive=true recreatedStub=true subscriptions=0 requests=0")
    }

    private suspend fun bindingGates(context: Context, runId: String) {
        bindingGate(context, NullBindingService::class.java, bindingTimeoutMs = 5000,
            failureDeadlineMs = 1500, settleDelayMs = 0, label = "nullBinding", runId = runId)
        bindingGate(context, DelayedBindingService::class.java, bindingTimeoutMs = 200,
            failureDeadlineMs = 1000, settleDelayMs = 1700, label = "bindingTimeout", runId = runId)
    }

    private suspend fun bindingGate(
        context: Context,
        targetClass: Class<*>,
        bindingTimeoutMs: Long,
        failureDeadlineMs: Long,
        settleDelayMs: Long,
        label: String,
        runId: String
    ) = coroutineScope {
        val ownerJob = SupervisorJob(coroutineContext[Job])
        val worker = IpcConnectionController(
            context = context,
            targetIntent = Intent(context, targetClass),
            scope = CoroutineScope(Dispatchers.Default + ownerJob),
            maxReconnectAttempts = 0,
            bindingTimeoutMs = bindingTimeoutMs
        )
        // Inject one registry waiter to verify failure cleanup; no business request is sent.
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                worker.pendingCallRegistry.callSuspend<String>(9001, 19, generation = 0) {}
            }
        }
        try {
            check(worker.pendingCallRegistry.activeCallCount == 1)
            val start = SystemClock.elapsedRealtime()
            worker.connect()
            withTimeout(failureDeadlineMs) { worker.state.first { it is IpcClientState.Disconnected } }
            val failureElapsedMs = SystemClock.elapsedRealtime() - start
            check(withTimeout(1000) { pending.await() }.isFailure)
            check(worker.pendingCallRegistry.activeCallCount == 0)
            // Let the delayed Service return its Binder after the binding deadline.
            if (settleDelayMs > 0) delay(settleDelayMs)
            check(worker.state.value is IpcClientState.Disconnected)
            check(worker.pendingCallRegistry.activeCallCount == 0)
            Log.i(TAG, "PASS run=$runId $label disconnected=true failureElapsedMs=$failureElapsedMs " +
                "bindingTimeoutMs=$bindingTimeoutMs lateCallbackStable=true pending=0 syntheticWaiter=true")
        } finally {
            withContext(NonCancellable) {
                worker.disposeAndJoin()
                pending.cancelAndJoin()
                ownerJob.cancelAndJoin()
            }
        }
    }

    /** Bypass Broker discovery to verify that a retained data-plane Binder is disposed. */
    private fun readRetainedStubError(binder: IBinder): Throwable? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken("com.cn.ipc.api.test.IBenchmarkEchoService")
            data.writeString("lifecycle-retained-stub")
            check(binder.transact(12, data, reply, 0)) { "Retained Stub rejected transaction" }
            reply.readException()
            check(reply.readInt() == 1)
            check(reply.readString() == "lifecycle-retained-stub")
            null
        } catch (error: Exception) {
            error
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private suspend fun awaitCounts(
        service: IBenchmarkEchoServiceClientAdapter,
        subscriptions: Int,
        requests: Int
    ) = withTimeout(4000) {
        while (true) {
            val currentSubscriptions = service.inspectActiveSubscriptionCount()
            val currentRequests = service.inspectActiveRequestCount()
            if (currentSubscriptions == subscriptions && currentRequests == requests) break
            delay(10)
        }
    }

    private suspend fun awaitEvents(events: AtomicInteger, previous: Int) = withTimeout(3000) {
        while (events.get() <= previous) delay(10)
    }

    private suspend fun awaitQuiet(events: AtomicInteger) {
        // Allow callbacks already queued before unsubscribe to drain first.
        delay(100)
        val afterDrain = events.get()
        delay(150)
        check(events.get() == afterDrain) { "Closed subscription kept delivering events" }
    }
}
