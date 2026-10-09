package com.cn.ipc.demo

import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import com.cn.ipc.api.test.IBenchmarkEchoServiceClientAdapter
import com.cn.ipc.client.IpcConnectionController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import java.util.concurrent.atomic.AtomicReference

/** Explicit terminal intent only; deadlines are exercised independently of test watchdogs. */
object IpcTerminalProbe {
    private const val TAG = "IpcTerminalProbe"
    private const val SHORT_DEADLINE_MS = 150L
    private const val TOKEN = "com.cn.ipc.api.test.IBenchmarkEchoService"

    suspend fun run(context: Context, anchor: IpcConnectionController, runId: String) =
        withContext(Dispatchers.Default) {
            val connection = anchor.awaitConnected(10_000)
            check((connection.protocol.serviceVersions[9001] ?: 0) >= 5)
            val service = IBenchmarkEchoServiceClientAdapter(anchor)
            awaitCounts(service, subscriptions = 0, requests = 0)

            val finite = withTimeout(3000) { service.observeTerminalScenario("finite").toList() }
            check(finite == listOf(1, 2, 3))
            awaitCounts(service, subscriptions = 0, requests = 0)
            Log.i(TAG, "PASS run=$runId finiteFlow items=1,2,3 completed=true subscriptions=0")

            val beforeError = mutableListOf<Int>()
            val runtimeError = runCatching {
                withTimeout(3000) { service.observeTerminalScenario("error").collect { beforeError.add(it) } }
            }.exceptionOrNull()
            check(beforeError == listOf(7))
            check(runtimeError is RuntimeException && runtimeError.message == "flow-runtime-error") {
                "Wrong runtime Flow error: $runtimeError"
            }
            awaitCounts(service, subscriptions = 0, requests = 0)
            Log.i(TAG, "PASS run=$runId runtimeFlowError itemBeforeError=7 message=flow-runtime-error subscriptions=0")

            var consumed = 0
            val overflow = runCatching {
                withTimeout(6000) {
                    service.observeTerminalScenario("overflow").collect {
                        consumed++
                        delay(20)
                    }
                }
            }.exceptionOrNull()
            check(overflow is IllegalStateException && overflow.message == "IPC stream buffer overflow") {
                "Wrong slow-consumer failure: $overflow"
            }
            check(consumed > 0)
            awaitCounts(service, subscriptions = 0, requests = 0)
            Log.i(TAG, "PASS run=$runId streamOverflow explicitFailure=true consumed=$consumed subscriptions=0")

            val conflated = mutableListOf<Int>()
            withTimeout(6000) {
                service.observeConflatedScenario().collect {
                    conflated.add(it)
                    delay(20)
                }
            }
            check(conflated.isNotEmpty() && conflated.last() == 999)
            awaitCounts(service, subscriptions = 0, requests = 0)
            Log.i(TAG, "PASS run=$runId streamConflate completed=true consumed=${conflated.size} last=999 overflow=false subscriptions=0")

            cancelStream(service, runId)
            deadlines(context.applicationContext, service, runId)
            awaitCounts(service, subscriptions = 0, requests = 0)
            check(anchor.pendingCallRegistry.activeCallCount == 0)
            check(service.echoDirect("terminal-anchor-alive") == "terminal-anchor-alive")
            Log.i(TAG, "DONE run=$runId passed=true checks=8 subscriptions=0 requests=0 pending=0 anchorAlive=true")
        }

    private suspend fun cancelStream(service: IBenchmarkEchoServiceClientAdapter, runId: String) = coroutineScope {
        val first = CompletableDeferred<Int>()
        val unexpectedError = AtomicReference<Throwable?>()
        val collector = launch {
            try {
                service.observeTerminalScenario("cancel").collect { first.complete(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                unexpectedError.set(error)
            }
        }
        try {
            check(withTimeout(3000) { first.await() } == 0)
            awaitCounts(service, subscriptions = 1, requests = 0)
            collector.cancelAndJoin()
            awaitCounts(service, subscriptions = 0, requests = 0)
            check(unexpectedError.get() == null) { "Explicit cancellation produced ${unexpectedError.get()}" }
            Log.i(TAG, "PASS run=$runId explicitStreamCancel extraError=false subscriptions=0")
        } finally {
            collector.cancelAndJoin()
        }
    }

    private suspend fun deadlines(
        context: Context,
        diagnostics: IBenchmarkEchoServiceClientAdapter,
        runId: String
    ) = coroutineScope {
        val ownerJob = SupervisorJob(coroutineContext[Job])
        val worker = IpcConnectionController(
            context = context,
            targetIntent = Intent(context, MyBrokerService::class.java),
            scope = CoroutineScope(Dispatchers.Default + ownerJob),
            defaultCallTimeoutMs = SHORT_DEADLINE_MS
        )
        try {
            val connection = worker.awaitConnected(10_000)
            val service = IBenchmarkEchoServiceClientAdapter(worker)
            check(service.echoDirect("short-deadline-warmup") == "short-deadline-warmup")
            val start = SystemClock.elapsedRealtime()
            val held = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { service.holdLifecycleRequest() }
            }
            withTimeout(1000) {
                while (diagnostics.inspectActiveRequestCount() != 1) {
                    check(!held.isCompleted) { "Held request expired before the server job was observed" }
                    delay(5)
                }
            }
            // The watchdog only detects a stuck probe; the call itself uses the 150 ms default.
            val heldError = withTimeout(3000) { held.await() }.exceptionOrNull()
            val elapsed = SystemClock.elapsedRealtime() - start
            assertDefaultDeadline(heldError, elapsed)
            awaitCounts(diagnostics, subscriptions = 0, requests = 0)
            check(worker.pendingCallRegistry.activeCallCount == 0)
            Log.i(TAG, "PASS run=$runId rpcDeadline defaultMs=$SHORT_DEADLINE_MS elapsedMs=$elapsed serverJobObserved=true requests=0 pending=0")

            val binder = worker.getServiceBinderForConnection(connection, 9001, 5)
            val parameterError = runCatching {
                withTimeout(3000) { rawEcho(worker, binder, connection.generation, TOKEN, payload = null) }
            }.exceptionOrNull()
            check(parameterError is RuntimeException && parameterError.message == "Missing payload") {
                "Wrong null-parameter error: $parameterError"
            }
            check(worker.pendingCallRegistry.activeCallCount == 0)
            awaitCounts(diagnostics, subscriptions = 0, requests = 0)
            Log.i(TAG, "PASS run=$runId asyncParameterError callbackReceived=true message=Missing_payload requests=0 pending=0")

            val badTokenStart = SystemClock.elapsedRealtime()
            val badTokenError = runCatching {
                withTimeout(3000) {
                    rawEcho(worker, binder, connection.generation, "invalid.terminal.token", payload = "bad-token")
                }
            }.exceptionOrNull()
            val badTokenElapsed = SystemClock.elapsedRealtime() - badTokenStart
            assertDefaultDeadline(badTokenError, badTokenElapsed)
            check(worker.pendingCallRegistry.activeCallCount == 0)
            awaitCounts(diagnostics, subscriptions = 0, requests = 0)
            Log.i(TAG, "PASS run=$runId badTokenDeadline callbackTrusted=false defaultMs=$SHORT_DEADLINE_MS elapsedMs=$badTokenElapsed requests=0 pending=0")
        } finally {
            withContext(NonCancellable) {
                worker.disposeAndJoin()
                ownerJob.cancelAndJoin()
            }
        }
    }

    private suspend fun rawEcho(
        controller: IpcConnectionController,
        binder: IBinder,
        generation: Long,
        token: String,
        payload: String?
    ): String = controller.pendingCallRegistry.callSuspend(
        serviceId = 9001,
        operationId = 10,
        generation = generation,
        deserializer = { it.readString() ?: "" },
        onRemoteCancel = { requestId ->
            val cancel = Parcel.obtain()
            try {
                cancel.writeInterfaceToken(TOKEN)
                cancel.writeLong(requestId)
                cancel.writeStrongBinder(controller.globalResponseBinder)
                binder.transact(11, cancel, null, IBinder.FLAG_ONEWAY)
            } finally { cancel.recycle() }
        }
    ) { requestId ->
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(token)
            data.writeLong(requestId)
            data.writeString(payload)
            data.writeStrongBinder(controller.globalResponseBinder)
            check(binder.transact(10, data, null, IBinder.FLAG_ONEWAY))
        } finally { data.recycle() }
    }

    private fun assertDefaultDeadline(error: Throwable?, elapsedMs: Long) {
        check(error is TimeoutCancellationException && error.message.orEmpty().contains("$SHORT_DEADLINE_MS")) {
            "Wrong default-deadline failure: $error"
        }
        check(elapsedMs in 100L..1000L) { "150 ms deadline finished outside the expected window: $elapsedMs ms" }
    }

    private suspend fun awaitCounts(
        service: IBenchmarkEchoServiceClientAdapter,
        subscriptions: Int,
        requests: Int
    ) = withTimeout(4000) {
        while (service.inspectActiveSubscriptionCount() != subscriptions ||
            service.inspectActiveRequestCount() != requests) delay(10)
    }
}
