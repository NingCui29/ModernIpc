package com.cn.ipc.demo

import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.cn.ipc.api.test.IBenchmarkEchoServiceClientAdapter
import com.cn.ipc.api.test.IBenchmarkEchoServiceIpcSchema
import com.cn.ipc.api.test.IUserServiceIpcSchema
import com.cn.ipc.client.IpcClientState
import com.cn.ipc.client.IpcCompatibilityException
import com.cn.ipc.client.IpcConnectionController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.io.File
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/** Real cross-process metadata stalls, invoked only by an explicit cold* benchmark intent. */
object IpcColdDiscoveryProbe {
    private const val TAG = "IpcColdProbe"
    private const val ECHO_ID = 9001
    private const val USER_ID = 1001
    private const val CHECKS = 12
    private val echoSchema get() = IBenchmarkEchoServiceIpcSchema.CLIENT_SCHEMA
    private val userSchema get() = IUserServiceIpcSchema.CLIENT_SCHEMA

    suspend fun run(context: Context, runId: String) = withContext(Dispatchers.Default) {
        supervisorScope {
            val owner = SupervisorJob(coroutineContext[Job])
            val keeper = newController(context.applicationContext, owner)
            var environment: Environment? = null
            try {
                val connection = keeper.awaitConnected(10_000)
                val env = Environment(context.applicationContext, owner, connection.broker.asBinder(), runId)
                environment = env
                check(env.read().serverPid != Process.myPid()) { "Cold discovery fixture must run in another process" }
                Log.i(TAG, "START run=$runId checks=$CHECKS clientPid=${Process.myPid()} serverPid=${env.read().serverPid}")
                mainColdAsync(env)
                shortDeadline(env)
                flowDiscoveryDeadline(env)
                cancelDiscovery(env)
                concurrentSingleFlight(env)
                sharedDiscoveryCancelOne(env)
                closeWithPending(env, permanent = false)
                closeWithPending(env, permanent = true)
                lateGeneration(env)
                failureRetry(env)
                strictRejection(env)
                boundedSaturation(env)
                val echo = IBenchmarkEchoServiceClientAdapter(keeper)
                awaitCondition { echo.inspectActiveRequestCount() == 0 }
                check(echo.inspectActiveSubscriptionCount() == 0 && keeper.pendingCallRegistry.activeCallCount == 0)
                check(env.passed == CHECKS && env.read().watchdogs == 0)
                Log.i(TAG, "DONE run=$runId passed=true checks=$CHECKS requests=0 subscriptions=0 pending=0 watchdogs=0")
            } finally {
                withContext(NonCancellable) {
                    environment?.release()
                    environment?.save()
                    keeper.disposeAndJoin()
                    owner.cancelAndJoin()
                }
            }
        }
    }

    private suspend fun mainColdAsync(env: Environment) = withWorker(env) { worker, _ ->
        supervisorScope {
            val ticks = AtomicInteger()
            val heartbeat = launch(Dispatchers.Main.immediate) {
                while (isActive) { ticks.incrementAndGet(); delay(20) }
            }
            try {
                awaitCondition { ticks.get() > 0 }
                env.arm(ECHO_ID)
                val call = async(Dispatchers.Main.immediate) {
                    IBenchmarkEchoServiceClientAdapter(worker).echo("main-cold-async")
                }
                awaitBlocked(env)
                val ticksBefore = ticks.get()
                delay(300)
                val ticksDuringBlock = ticks.get() - ticksBefore
                check(ticksDuringBlock >= 5) { "Main heartbeat stalled during cold Async: $ticksDuringBlock ticks/300ms" }
                check(!call.isCompleted && worker.pendingCallRegistry.activeCallCount == 0)
                env.release()
                check(call.await() == "main-cold-async")
                check(env.read().let { it.metadata == 1 && it.checked == 1 && it.legacy == 0 })
                env.pass("mainColdAsync", "mainTicks=$ticksDuringBlock/300ms result=true discoveryBeforePending=true")
            } finally { heartbeat.cancelAndJoin() }
        }
    }

    private suspend fun shortDeadline(env: Environment) = withWorker(env, timeoutMs = 150) { worker, _ ->
        supervisorScope {
            env.arm(ECHO_ID)
            val started = SystemClock.elapsedRealtime()
            val call = async(Dispatchers.Main.immediate) {
                runCatching { IBenchmarkEchoServiceClientAdapter(worker).echo("cold-short-deadline") }
            }
            awaitBlocked(env)
            // No outer call timeout: the generated Async entry must enforce the controller's 150ms budget.
            val result = call.await()
            val elapsed = SystemClock.elapsedRealtime() - started
            check(result.exceptionOrNull() is TimeoutCancellationException) { "Wrong cold deadline result: $result" }
            check(elapsed < 1000) { "Cold discovery exceeded the short deadline: ${elapsed}ms" }
            check(worker.pendingCallRegistry.activeCallCount == 0)
            check(env.read().activeBlocked == 1) { "Deadline did not finish before the remote stall was released" }
            env.release()
            env.awaitIdle()
            check(env.read().checked == 0) { "Cancelled cold discovery continued to checked resolution" }
            env.pass("coldAsyncDeadline", "defaultCallTimeoutMs=150 elapsedMs=$elapsed timeout=true pending=0 lateChecked=0", elapsed)
        }
    }

    private suspend fun cancelDiscovery(env: Environment) = withWorker(env) { worker, connection ->
        supervisorScope {
            env.arm(ECHO_ID)
            val discovery = async { resolveEcho(worker, connection) }
            awaitBlocked(env)
            val started = SystemClock.elapsedRealtime()
            withTimeout(1000) { discovery.cancelAndJoin() }
            val elapsed = SystemClock.elapsedRealtime() - started
            check(discovery.isCancelled && worker.pendingCallRegistry.activeCallCount == 0)
            check(env.read().activeBlocked == 1)
            env.release()
            env.awaitIdle()
            check(env.read().checked == 0) { "Explicitly cancelled discovery continued to checked resolution" }
            env.pass("cancelDiscovery", "elapsedMs=$elapsed callerCancelled=true remoteStillBlockedBeforeRelease=true pending=0 lateChecked=0", elapsed)
        }
    }

    private suspend fun flowDiscoveryDeadline(env: Environment) = withWorker(env, timeoutMs = 150) { worker, _ ->
        supervisorScope {
            env.arm(ECHO_ID)
            val values = AtomicInteger()
            val started = SystemClock.elapsedRealtime()
            val collector = async(Dispatchers.Main.immediate) {
                // Echo owns observeTerminalScenario; "finite" would emit and complete if discovery succeeded.
                runCatching {
                    IBenchmarkEchoServiceClientAdapter(worker).observeTerminalScenario("finite")
                        .collect { values.incrementAndGet() }
                }
            }
            try {
                awaitBlocked(env)
                // Bound observation only. The collector has no external withTimeout to manufacture its failure.
                awaitCondition(timeoutMs = 1000) { collector.isCompleted }
                val result = collector.await()
                val elapsed = SystemClock.elapsedRealtime() - started
                check(result.exceptionOrNull() is TimeoutCancellationException) { "Wrong Flow discovery deadline result: $result" }
                check(elapsed < 1000 && values.get() == 0 && worker.pendingCallRegistry.activeCallCount == 0)
                check(env.read().activeBlocked == 1) { "Flow collector did not finish before releasing metadata" }
                collector.cancelAndJoin()
                env.release()
                env.awaitIdle()
                check(env.read().checked == 0) { "Timed-out Flow discovery continued to checked resolution" }
                env.pass("flowDiscoveryDeadline", "defaultCallTimeoutMs=150 elapsedMs=$elapsed collectorEnded=true timeout=true values=0 pending=0 lateChecked=0", elapsed)
            } finally {
                collector.cancelAndJoin()
            }
        }
    }

    private suspend fun concurrentSingleFlight(env: Environment) = withWorker(env) { worker, connection ->
        supervisorScope {
            env.arm(ECHO_ID)
            val discoveries = List(16) { async { resolveEcho(worker, connection) } }
            awaitBlocked(env)
            delay(150)
            check(env.read().let { it.metadata == 1 && it.checked == 0 && it.activeBlocked == 1 })
            env.release()
            val binders = discoveries.awaitAll()
            check(binders.all { it == binders.first() })
            val service = IBenchmarkEchoServiceClientAdapter(worker)
            repeat(10) { check(service.echo("shared-$it") == "shared-$it") }
            check(env.read().let { it.metadata == 1 && it.checked == 1 && it.legacy == 0 })
            env.pass("concurrentSingleFlight", "callers=16 metadata=1 checked=1 hotCalls=10 additionalDiscovery=0")
        }
    }

    private suspend fun sharedDiscoveryCancelOne(env: Environment) = withWorker(env) { worker, connection ->
        supervisorScope {
            env.arm(ECHO_ID)
            // UNDISPATCHED reaches each flight await before creating the next waiter.
            val discoveries = List(16) {
                async(start = CoroutineStart.UNDISPATCHED) { resolveEcho(worker, connection) }
            }
            awaitBlocked(env)
            withTimeout(1000) { discoveries.first().cancelAndJoin() }
            check(discoveries.first().isCancelled && discoveries.drop(1).none { it.isCompleted })
            check(env.read().let { it.metadata == 1 && it.checked == 0 && it.activeBlocked == 1 })
            env.release()
            val binders = discoveries.drop(1).awaitAll()
            check(binders.size == 15 && binders.all { it == binders.first() })
            check(worker.pendingCallRegistry.activeCallCount == 0)
            check(env.read().let { it.metadata == 1 && it.checked == 1 && it.legacy == 0 })
            env.pass("sharedDiscoveryCancelOne", "waiters=16 cancelled=1 succeeded=15 metadata=1 checked=1 legacy=0 pending=0")
        }
    }

    private suspend fun closeWithPending(env: Environment, permanent: Boolean) = withWorker(env) { worker, connection ->
        supervisorScope {
            resolveEcho(worker, connection)
            val echo = IBenchmarkEchoServiceClientAdapter(worker)
            val held = async { runCatching { echo.holdLifecycleRequest() } }
            awaitCondition { worker.pendingCallRegistry.activeCallCount == 1 && echo.inspectActiveRequestCount() == 1 }
            env.arm(USER_ID)
            val discovery = async { runCatching { resolveUser(worker, connection) } }
            awaitBlocked(env, USER_ID)
            val started = SystemClock.elapsedRealtime()
            if (permanent) worker.disposeAndJoin() else worker.closeAndJoin()
            val elapsed = SystemClock.elapsedRealtime() - started
            check(elapsed < 1000) { "${if (permanent) "dispose" else "close"} waited on blocked discovery: ${elapsed}ms" }
            check(worker.pendingCallRegistry.activeCallCount == 0 && held.await().isFailure)
            check(withTimeout(1000) { discovery.await() }.isFailure)
            check(env.read(USER_ID).activeBlocked == 1)
            check(worker.state.value === if (permanent) IpcClientState.Disposed else IpcClientState.Closed)
            env.release()
            env.awaitIdle()
            env.pass(if (permanent) "disposeDuringDiscovery" else "closeDuringDiscovery",
                "elapsedMs=$elapsed warmedPendingFailed=true discoveryWaiterFailed=true remoteStillBlockedBeforeRelease=true pending=0", elapsed)
        }
    }

    private suspend fun lateGeneration(env: Environment) = withWorker(env) { worker, oldConnection ->
        supervisorScope {
            env.arm(USER_ID)
            val oldDiscovery = async { runCatching { resolveUser(worker, oldConnection) } }
            awaitBlocked(env, USER_ID)
            worker.closeAndJoin()
            val newConnection = worker.awaitConnected(10_000)
            check(newConnection.generation > oldConnection.generation)
            // The one-shot gate only holds the old call: the new generation must make progress before release.
            val freshBinder = resolveUser(worker, newConnection)
            check(env.read(USER_ID).let { it.metadata == 2 && it.checked == 1 && it.activeBlocked == 1 })
            env.release()
            check(oldDiscovery.await().isFailure)
            env.awaitIdle()
            check(resolveUser(worker, newConnection) == freshBinder)
            check(env.read(USER_ID).let { it.metadata == 2 && it.checked == 1 && it.legacy == 0 })
            env.pass("lateGeneration", "generationAdvanced=true freshResolvedBeforeOldRelease=true oldFailed=true metadata=2 checked=1")
        }
    }

    private suspend fun failureRetry(env: Environment) = withWorker(env) { worker, connection ->
        supervisorScope {
            env.arm(ECHO_ID, failOnRelease = true)
            val first = async { runCatching { resolveEcho(worker, connection) } }
            awaitBlocked(env)
            env.release()
            val error = first.await().exceptionOrNull()
            check(error != null && causes(error).any { it.message.orEmpty().contains("cold-discovery-injected-failure") })
            check(env.read().let { it.metadata == 1 && it.checked == 0 })
            resolveEcho(worker, connection)
            check(IBenchmarkEchoServiceClientAdapter(worker).echo("retry-ok") == "retry-ok")
            check(env.read().let { it.metadata == 2 && it.checked == 1 && it.legacy == 0 })
            env.pass("failureRetry", "firstFailure=true retrySucceeded=true metadata=2 checked=1 legacy=0")
        }
    }

    private suspend fun strictRejection(env: Environment) = withWorker(env) { worker, connection ->
        val wrong = echoSchema.copy(descriptor = "${echoSchema.descriptor}.incompatible")
        val error = runCatching {
            worker.awaitServiceBinderForConnection(connection, ECHO_ID, echoSchema.contractVersion, wrong)
        }.exceptionOrNull()
        check(error is IpcCompatibilityException) { "Cold strict schema rejection changed: $error" }
        check(env.read().let { it.metadata == 1 && it.checked == 0 && it.legacy == 0 })
        resolveEcho(worker, connection)
        check(env.read().let { it.metadata == 2 && it.checked == 1 && it.legacy == 0 })
        env.pass("strictSchemaRejection", "explicitCompatibilityFailure=true rejectedChecked=0 legacyFallback=0 validSchemaRetry=true")
    }

    private suspend fun boundedSaturation(env: Environment) = supervisorScope {
        val workers = List(5) { newController(env.context, env.owner) }
        try {
            val connections = workers.map { it.awaitConnected(10_000) }
            env.reset()
            repeat(4) { env.arm(ECHO_ID) }
            val occupied = (0 until 4).map { index -> async { resolveEcho(workers[index], connections[index]) } }
            awaitCondition { env.read().let { it.activeBlocked == 4 && it.peakBlocked == 4 && it.entered == 4 } }
            check(env.read().let { it.metadata == 4 && it.checked == 0 && it.peakBlocked == 4 })
            val started = SystemClock.elapsedRealtime()
            val rejected = runCatching { resolveEcho(workers[4], connections[4]) }.exceptionOrNull()
            val elapsed = SystemClock.elapsedRealtime() - started
            check(rejected != null && causes(rejected).any { it is RejectedExecutionException }) { "Missing bounded discovery rejection: $rejected" }
            check(elapsed < 1000 && env.read().metadata == 4)
            env.release()
            occupied.awaitAll()
            env.awaitIdle()
            var recoveryRejections = 0
            withTimeout(2000) {
                while (true) {
                    val result = runCatching { resolveEcho(workers[4], connections[4]) }
                    if (result.isSuccess) break
                    check(causes(result.exceptionOrNull()!!).any { it is RejectedExecutionException })
                    recoveryRejections++
                    delay(20)
                }
            }
            check(IBenchmarkEchoServiceClientAdapter(workers[4]).echo("budget-recovered") == "budget-recovered")
            check(env.read().let { it.metadata == 5 && it.checked == 5 && it.peakBlocked == 4 && it.watchdogs == 0 })
            env.pass("boundedSaturation", "workerLimit=4 fifthRejected=true elapsedMs=$elapsed retrySucceeded=true recoveryRejections=$recoveryRejections peakBlocked=4", elapsed)
        } finally {
            withContext(NonCancellable) {
                env.release()
                env.awaitIdle()
                workers.forEach { it.disposeAndJoin() }
            }
        }
    }

    private suspend fun <T> withWorker(
        env: Environment,
        timeoutMs: Long = 5000,
        block: suspend (IpcConnectionController, IpcClientState.Connected) -> T
    ): T {
        val worker = newController(env.context, env.owner, timeoutMs)
        return try {
            val connection = worker.awaitConnected(10_000)
            env.reset()
            block(worker, connection)
        } finally {
            withContext(NonCancellable) {
                env.release()
                env.awaitIdle()
                worker.disposeAndJoin()
            }
        }
    }

    private fun newController(context: Context, owner: Job, timeoutMs: Long = 5000) = IpcConnectionController(
        context = context,
        targetIntent = Intent(context, ColdDiscoveryBrokerService::class.java),
        scope = CoroutineScope(Dispatchers.Default + owner),
        maxReconnectAttempts = 0,
        defaultCallTimeoutMs = timeoutMs
    )

    private suspend fun resolveEcho(worker: IpcConnectionController, connection: IpcClientState.Connected): IBinder =
        worker.awaitServiceBinderForConnection(connection, ECHO_ID, echoSchema.contractVersion, echoSchema)

    private suspend fun resolveUser(worker: IpcConnectionController, connection: IpcClientState.Connected): IBinder =
        worker.awaitServiceBinderForConnection(connection, USER_ID, userSchema.contractVersion, userSchema)

    private fun causes(error: Throwable): Sequence<Throwable> = generateSequence(error) { it.cause }

    private suspend fun awaitBlocked(env: Environment, serviceId: Int = ECHO_ID) =
        awaitCondition { env.read(serviceId).let { it.activeBlocked == 1 && it.entered >= 1 } }

    private suspend fun awaitCondition(timeoutMs: Long = 3000, condition: suspend () -> Boolean) = withTimeout(timeoutMs) {
        while (!condition()) delay(10)
    }

    private data class Stats(
        val metadata: Int, val checked: Int, val legacy: Int,
        val activeBlocked: Int, val peakBlocked: Int, val entered: Int,
        val finished: Int, val watchdogs: Int, val serverPid: Int
    )

    private class Environment(val context: Context, val owner: Job, val controls: IBinder, val runId: String) {
        var passed = 0
            private set
        private val rows = mutableListOf("case,elapsedMs,details")

        suspend fun read(serviceId: Int = ECHO_ID) = transact(ColdDiscoveryBrokerService.READ, serviceId)
        suspend fun arm(serviceId: Int, failOnRelease: Boolean = false) = transact(ColdDiscoveryBrokerService.ARM, serviceId, failOnRelease)
        suspend fun release() = transact(ColdDiscoveryBrokerService.RELEASE, ECHO_ID)
        suspend fun reset() = transact(ColdDiscoveryBrokerService.RESET, ECHO_ID)

        suspend fun awaitIdle() {
            awaitCondition { read().let { it.activeBlocked == 0 && it.entered == it.finished } }
            // Binder returned before the discovery worker finishes publishing its outcome; allow its epilogue to run.
            delay(20)
        }

        fun pass(label: String, details: String, elapsedMs: Long? = null) {
            passed++
            rows.add("$label,${elapsedMs ?: ""},\"${details.replace("\"", "\"\"")}\"")
            Log.i(TAG, "PASS run=$runId $label $details")
        }

        suspend fun save() = withContext(Dispatchers.IO) {
            val safeRunId = runId.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            val output = File(context.filesDir, "cold-$safeRunId.csv")
            output.writeText(rows.joinToString("\n", postfix = "\n"))
            Log.i(TAG, "RAW run=$runId checks=$passed file=${output.name}")
        }

        private suspend fun transact(code: Int, serviceId: Int, failOnRelease: Boolean = false): Stats = withContext(Dispatchers.IO) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(ColdDiscoveryBrokerService.DESCRIPTOR)
                data.writeInt(serviceId)
                if (code == ColdDiscoveryBrokerService.ARM) data.writeInt(if (failOnRelease) 1 else 0)
                check(controls.transact(code, data, reply, 0)) { "Cold discovery fixture control failed: $code" }
                reply.readException()
                Stats(reply.readInt(), reply.readInt(), reply.readInt(), reply.readInt(), reply.readInt(),
                    reply.readInt(), reply.readInt(), reply.readInt(), reply.readInt())
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
    }
}
