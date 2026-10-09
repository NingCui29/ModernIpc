package com.cn.ipc.demo

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.cn.ipc.api.test.IBenchmarkEchoServiceClientAdapter
import com.cn.ipc.client.IpcClientState
import com.cn.ipc.client.IpcConnectionController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File

/** Explicit handshake* intent only: real remote blocking calls, no fake local Broker. */
object IpcHandshakeProbe {
    private const val TAG = "IpcHandshakeProbe"
    private const val CHECKS = 9

    suspend fun run(context: Context, runId: String) = withContext(Dispatchers.Default) {
        supervisorScope {
            val keep = RawBinding(context.applicationContext)
            var environment: Environment? = null
            try {
                val controls = keep.bind()
                val env = Environment(context.applicationContext, controls, runId)
                environment = env
                val initial = env.read()
                check(initial.serverPid != Process.myPid() && initial.attempts == 0 && initial.watchdogs == 0)
                Log.i(TAG, "START run=$runId checks=$CHECKS clientPid=${Process.myPid()} serverPid=${initial.serverPid}")
                env.pass("rawKeepBinding", "remoteProcess=true handshakeAttempts=0 serverPid=${initial.serverPid}")
                closeBlocked(env, permanent = false)
                closeBlocked(env, permanent = true)
                bindingTimeoutAndRetry(env)
                boundedReconnect(env)
                lateHandshake(env)
                ownerCancellation(env)
                saturationAndRecovery(env)
                env.awaitIdle()
                check(env.passed == CHECKS && env.read().watchdogs == 0)
                Log.i(TAG, "DONE run=$runId passed=true checks=$CHECKS pending=0 blocked=0 watchdogs=0")
            } finally {
                withContext(NonCancellable) {
                    environment?.release()
                    environment?.save()
                    keep.close()
                }
            }
        }
    }

    private suspend fun closeBlocked(env: Environment, permanent: Boolean) = withWorker(env) { worker, _ ->
        env.arm()
        worker.connect()
        env.awaitBlocked(1)
        check(worker.state.value === IpcClientState.Binding)
        val started = SystemClock.elapsedRealtime()
        if (permanent) worker.disposeAndJoin() else worker.closeAndJoin()
        val elapsed = SystemClock.elapsedRealtime() - started
        val terminal = if (permanent) IpcClientState.Disposed else IpcClientState.Closed
        check(elapsed < 500) { "Blocked handshake delayed ${if (permanent) "dispose" else "close"}: ${elapsed}ms" }
        check(worker.state.value === terminal && worker.pendingCallRegistry.activeCallCount == 0)
        check(env.read().activeBlocked == 1) { "Remote handshake was released before close/dispose completed" }
        env.release()
        env.awaitIdle()
        delay(100)
        check(worker.state.value === terminal && worker.currentGeneration == 0L)
        env.pass(if (permanent) "disposeBlockedHandshake" else "closeBlockedHandshake",
            "elapsedMs=$elapsed remoteStillBlockedBeforeRelease=true lateReplyNoRevive=true generation=0 pending=0", elapsed)
    }

    private suspend fun bindingTimeoutAndRetry(env: Environment) = withWorker(env, bindingTimeoutMs = 600) { worker, _ ->
        env.arm()
        val started = SystemClock.elapsedRealtime()
        worker.connect()
        env.awaitBlocked(1)
        awaitState(worker, 2000) { it === IpcClientState.Disconnected }
        val elapsed = SystemClock.elapsedRealtime() - started
        check(elapsed < 1500 && env.read().let { it.activeBlocked == 1 && it.attempts == 1 })
        delay(150)
        check(worker.state.value === IpcClientState.Disconnected && env.read().attempts == 1)
        env.release()
        env.awaitIdle()
        delay(100)
        check(worker.state.value === IpcClientState.Disconnected && worker.currentGeneration == 0L)
        // Do not call awaitConnected while testing Disconnected: it intentionally initiates a new connection.
        worker.connect()
        awaitState(worker) { it is IpcClientState.Connected }
        check(IBenchmarkEchoServiceClientAdapter(worker).echo("handshake-timeout-retry") == "handshake-timeout-retry")
        check(env.read().attempts == 2 && worker.currentGeneration == 1L && worker.pendingCallRegistry.activeCallCount == 0)
        env.pass("bindingTimeoutRetry", "bindingTimeoutMs=600 elapsedMs=$elapsed maxReconnectAttempts=0 disconnectedBeforeRelease=true lateReplyNoRevive=true explicitRetry=true attempts=2 pending=0", elapsed)
    }

    private suspend fun boundedReconnect(env: Environment) = withWorker(env, bindingTimeoutMs = 600, maxReconnectAttempts = 1) { worker, _ ->
        repeat(2) { env.arm() }
        worker.connect()
        env.awaitBlocked(2, timeoutMs = 3500)
        awaitState(worker, 2500) { it === IpcClientState.Disconnected }
        check(env.read().let { it.attempts == 2 && it.activeBlocked == 2 })
        delay(150)
        check(worker.state.value === IpcClientState.Disconnected && env.read().attempts == 2)
        env.release()
        env.awaitIdle()
        delay(100)
        check(worker.state.value === IpcClientState.Disconnected && worker.currentGeneration == 0L)
        check(worker.pendingCallRegistry.activeCallCount == 0)
        env.pass("boundedReconnect", "bindingTimeoutMs=600 maxReconnectAttempts=1 attempts=2 finalDisconnected=true lateRepliesNoRevive=true pending=0")
    }

    private suspend fun lateHandshake(env: Environment) = withWorker(env) { worker, _ ->
        env.arm()
        worker.connect()
        env.awaitBlocked(1)
        worker.closeAndJoin()
        check(worker.state.value === IpcClientState.Closed)
        worker.connect()
        val fresh = awaitState(worker) { it is IpcClientState.Connected } as IpcClientState.Connected
        check(env.read().let { it.attempts == 2 && it.activeBlocked == 1 })
        check(fresh.generation == 1L)
        env.release()
        env.awaitIdle()
        delay(100)
        check(worker.state.value === fresh && worker.currentGeneration == fresh.generation)
        check(IBenchmarkEchoServiceClientAdapter(worker).echo("fresh-handshake") == "fresh-handshake")
        env.pass("lateHandshakeIsolation", "freshConnectedBeforeOldRelease=true attempts=2 oldReplyDidNotReplaceConnected=true generation=1 pending=0")
    }

    private suspend fun ownerCancellation(env: Environment) = withWorker(env) { worker, owner ->
        env.arm()
        worker.connect()
        env.awaitBlocked(1)
        val started = SystemClock.elapsedRealtime()
        owner.cancelAndJoin()
        awaitState(worker, 1000) { it === IpcClientState.Disposed }
        check(worker.isDisposed)
        worker.disposeAndJoin()
        val elapsed = SystemClock.elapsedRealtime() - started
        check(elapsed < 500 && env.read().activeBlocked == 1)
        check(worker.pendingCallRegistry.activeCallCount == 0)
        env.release()
        env.awaitIdle()
        delay(100)
        check(worker.state.value === IpcClientState.Disposed && worker.currentGeneration == 0L)
        env.pass("ownerCancelBlockedHandshake", "elapsedMs=$elapsed autoDisposedBeforeExplicitJoin=true remoteStillBlockedBeforeRelease=true lateReplyNoRevive=true pending=0", elapsed)
    }

    private suspend fun saturationAndRecovery(env: Environment) = supervisorScope {
        val owners = List(5) { SupervisorJob(coroutineContext[Job]) }
        val workers = owners.map { newController(env.context, it, bindingTimeoutMs = 5000) }
        try {
            env.reset()
            repeat(4) { env.arm() }
            workers.take(4).forEach { it.connect() }
            env.awaitBlocked(4)
            check(env.read().let { it.attempts == 4 && it.peakBlocked == 4 })
            val started = SystemClock.elapsedRealtime()
            workers.last().connect()
            awaitState(workers.last(), 1500) { it === IpcClientState.Disconnected }
            val elapsed = SystemClock.elapsedRealtime() - started
            check(elapsed < 1000 && env.read().let { it.attempts == 4 && it.activeBlocked == 4 })
            check(workers.all { it.pendingCallRegistry.activeCallCount == 0 })
            env.pass("boundedHandshakePool", "workerLimit=4 fifthDisconnected=true elapsedMs=$elapsed bindingTimeoutMs=5000 serverAttempts=4 peakBlocked=4 pending=0", elapsed)
            env.release()
            env.awaitIdle()
            workers.take(4).forEach { worker -> awaitState(worker) { it is IpcClientState.Connected } }
            workers.last().connect()
            awaitState(workers.last()) { it is IpcClientState.Connected }
            check(IBenchmarkEchoServiceClientAdapter(workers.last()).echo("handshake-budget-recovered") == "handshake-budget-recovered")
            check(env.read().let { it.attempts == 5 && it.replied == 5 && it.activeBlocked == 0 && it.watchdogs == 0 })
            check(workers.all { it.pendingCallRegistry.activeCallCount == 0 })
            env.pass("handshakeCapacityRecovered", "releaseRecovered=true connectedControllers=5 serverAttempts=5 replies=5 pending=0 watchdogs=0")
        } finally {
            withContext(NonCancellable) {
                env.release()
                workers.forEach { it.disposeAndJoin() }
                owners.forEach { it.cancelAndJoin() }
                env.awaitIdle()
            }
        }
    }

    private suspend fun <T> withWorker(
        env: Environment,
        bindingTimeoutMs: Long = 3000,
        maxReconnectAttempts: Int = 0,
        block: suspend (IpcConnectionController, Job) -> T
    ): T {
        val owner = SupervisorJob(currentCoroutineContext()[Job])
        val worker = newController(env.context, owner, bindingTimeoutMs, maxReconnectAttempts)
        return try {
            env.reset()
            block(worker, owner)
        } finally {
            withContext(NonCancellable) {
                env.release()
                worker.disposeAndJoin()
                owner.cancelAndJoin()
                env.awaitIdle()
            }
        }
    }

    private fun newController(context: Context, owner: Job, bindingTimeoutMs: Long, maxReconnectAttempts: Int = 0) =
        IpcConnectionController(context, Intent(context, HandshakeBlockingService::class.java),
            CoroutineScope(Dispatchers.Default + owner), bindingTimeoutMs = bindingTimeoutMs,
            maxReconnectAttempts = maxReconnectAttempts)

    private suspend fun awaitState(worker: IpcConnectionController, timeoutMs: Long = 3000, predicate: (IpcClientState) -> Boolean) =
        withTimeout(timeoutMs) { worker.state.first { predicate(it) } }

    private data class Stats(val attempts: Int, val activeBlocked: Int, val peakBlocked: Int,
                             val entered: Int, val finished: Int, val replied: Int,
                             val watchdogs: Int, val serverPid: Int)

    private class Environment(val context: Context, val controls: IBinder, val runId: String) {
        var passed = 0
            private set
        private val rows = mutableListOf("case,elapsedMs,details")

        suspend fun read() = control(HandshakeBlockingService.READ)
        suspend fun arm() = control(HandshakeBlockingService.ARM)
        suspend fun release() = control(HandshakeBlockingService.RELEASE)
        suspend fun reset() {
            check(read().watchdogs == 0) { "A prior handshake reached its watchdog" }
            control(HandshakeBlockingService.RESET)
        }

        suspend fun awaitBlocked(count: Int, timeoutMs: Long = 2000) = withTimeout(timeoutMs) {
            while (!read().let { it.activeBlocked == count && it.entered >= count && it.peakBlocked >= count }) delay(10)
        }

        suspend fun awaitIdle() {
            withTimeout(3000) {
                while (!read().let { it.activeBlocked == 0 && it.entered == it.finished && it.attempts == it.replied }) delay(10)
            }
            // The Binder reply precedes the handshake worker's epilogue; leave it time to return its pool slot.
            delay(30)
        }

        fun pass(label: String, details: String, elapsedMs: Long? = null) {
            passed++
            rows.add("$label,${elapsedMs ?: ""},\"${details.replace("\"", "\"\"")}\"")
            Log.i(TAG, "PASS run=$runId $label $details")
        }

        suspend fun save() = withContext(Dispatchers.IO) {
            val safeRunId = runId.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            val output = File(context.filesDir, "handshake-$safeRunId.csv")
            output.writeText(rows.joinToString("\n", postfix = "\n"))
            Log.i(TAG, "RAW run=$runId checks=$passed file=${output.name}")
        }

        private suspend fun control(code: Int): Stats = withContext(Dispatchers.IO) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(HandshakeBlockingService.DESCRIPTOR)
                check(controls.transact(code, data, reply, 0))
                reply.readException()
                Stats(reply.readInt(), reply.readInt(), reply.readInt(), reply.readInt(),
                    reply.readInt(), reply.readInt(), reply.readInt(), reply.readInt())
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
    }

    /** The anchor uses Context directly, so acquiring controls cannot consume a handshake-pool slot. */
    private class RawBinding(private val context: Context) {
        private val connected = CompletableDeferred<IBinder>()
        private var bound = false
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service == null) connected.completeExceptionally(IllegalStateException("Null handshake fixture Binder"))
                else connected.complete(service)
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                connected.completeExceptionally(IllegalStateException("Handshake fixture disconnected"))
            }
            override fun onNullBinding(name: ComponentName?) {
                connected.completeExceptionally(IllegalStateException("Null handshake fixture binding"))
            }
            override fun onBindingDied(name: ComponentName?) {
                connected.completeExceptionally(IllegalStateException("Handshake fixture binding died"))
            }
        }

        suspend fun bind(): IBinder {
            withContext(Dispatchers.Main.immediate) {
                bound = context.bindService(Intent(context, HandshakeBlockingService::class.java), connection, Context.BIND_AUTO_CREATE)
                check(bound) { "Cannot raw-bind handshake fixture" }
            }
            return withTimeout(5000) { connected.await() }
        }

        suspend fun close() = withContext(Dispatchers.Main.immediate) {
            if (bound) {
                context.unbindService(connection)
                bound = false
            }
        }
    }
}
