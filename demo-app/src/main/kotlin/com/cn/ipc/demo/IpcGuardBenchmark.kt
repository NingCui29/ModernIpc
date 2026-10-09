package com.cn.ipc.demo

import android.os.Debug
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import com.cn.ipc.api.test.IBenchmarkEchoServiceClientAdapter
import com.cn.ipc.api.test.IBenchmarkEchoServiceIpcSchema
import com.cn.ipc.client.IpcClientState
import com.cn.ipc.client.IpcConnectionController
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.Executors

/** Same-APK comparison of a second service lookup and the captured-Binder send guard. */
object IpcGuardBenchmark {
    private const val TAG = "IpcGuardBench"
    private const val TOKEN = "com.cn.ipc.api.test.IBenchmarkEchoService"
    private const val LOCAL_COUNT = 500_000
    private const val IPC_COUNT = 1000
    private val SCHEMA = IBenchmarkEchoServiceIpcSchema.CLIENT_SCHEMA
    private val READER: (Parcel) -> Any? = { it.readString() ?: error("Missing echo response") }
    private val ORDER = listOf(false, true, true, false)

    suspend fun run(controller: IpcConnectionController, directory: File, runId: String) =
        withContext(Dispatchers.Default) {
            Log.i(TAG, "START run=$runId localCount=$LOCAL_COUNT ipcCount=$IPC_COUNT")
            probes(controller, runId)
            val connection = controller.awaitConnected(10_000)
            val binder = resolve(controller, connection)
            local(controller, connection, binder, directory, runId)
            ipc(controller, directory, runId)
            check(controller.pendingCallRegistry.activeCallCount == 0)
            controller.disposeAndJoin()
            check(runCatching { controller.checkServiceBinderForConnection(connection, binder) }
                .exceptionOrNull() is IllegalStateException)
            Log.i(TAG, "PASS run=$runId disposedSnapshotRejected=true pending=0")
            Log.i(TAG, "DONE run=$runId passed=true guardChecks=6 localBlocks=8 ipcBlocks=16")
        }

    private suspend fun probes(controller: IpcConnectionController, runId: String) {
        val connection = controller.awaitConnected(10_000)
        val binder = resolve(controller, connection)
        controller.checkServiceBinderForConnection(connection, binder)
        Log.i(TAG, "PASS run=$runId liveCapturedBinderAccepted=true")

        var sends = 0
        val deadFixture = object : IBinder by binder {
            override fun isBinderAlive(): Boolean = false
        }
        val deadError = runCatching {
            controller.pendingCallRegistry.callSuspend<String>(9001, 10, connection.generation) {
                controller.checkServiceBinderForConnection(connection, deadFixture)
                sends++
            }
        }.exceptionOrNull()
        check(deadError is IllegalStateException && sends == 0 && controller.pendingCallRegistry.activeCallCount == 0)
        Log.i(TAG, "PASS run=$runId deadBinderFixtureRejected=true sends=0 pending=0")

        val closingFixture = object : IBinder by binder {
            override fun isBinderAlive(): Boolean {
                runBlocking { controller.closeAndJoin() }
                return true
            }
        }
        val closeError = runCatching {
            controller.pendingCallRegistry.callSuspend<String>(9001, 10, connection.generation) {
                controller.checkServiceBinderForConnection(connection, closingFixture)
                sends++
            }
        }.exceptionOrNull()
        check(closeError is IllegalStateException && sends == 0 && controller.pendingCallRegistry.activeCallCount == 0)
        Log.i(TAG, "PASS run=$runId closeDuringAliveCheckRejected=true sends=0 pending=0")

        val next = controller.awaitConnected(10_000)
        check(next.generation > connection.generation)
        check(runCatching { controller.checkServiceBinderForConnection(connection, binder) }
            .exceptionOrNull() is IllegalStateException)
        controller.checkServiceBinderForConnection(next, resolve(controller, next))
        Log.i(TAG, "PASS run=$runId staleSnapshotRejected=true nextGenerationAccepted=true")

        val generated = IBenchmarkEchoServiceClientAdapter(controller)
        check(generated.echo("guard-generated") == "guard-generated")
        // The Async echo is intentionally a pure echo; __error__ belongs to the Direct fixture.
        check(generated.echo("__error__") == "__error__")
        check(controller.pendingCallRegistry.activeCallCount == 0)
        Log.i(TAG, "PASS run=$runId generatedAsyncReplies=true pending=0")
    }

    private fun resolve(controller: IpcConnectionController, connection: IpcClientState.Connected): IBinder =
        controller.getServiceBinderForConnection(connection, 9001, SCHEMA.contractVersion, SCHEMA)

    private fun recheck(controller: IpcConnectionController, connection: IpcClientState.Connected,
                        binder: IBinder, optimized: Boolean) {
        if (optimized) controller.checkServiceBinderForConnection(connection, binder)
        else resolve(controller, connection) // Previous generated code ignored this return value.
    }

    private suspend fun local(controller: IpcConnectionController, connection: IpcClientState.Connected,
                              binder: IBinder, directory: File, runId: String) {
        val rows = mutableListOf("kind,batch,mode,count,cpuNs,wallNs,beforeBytes,afterBytes,bytes")
        val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "IpcGuardLocalBench") }
            .asCoroutineDispatcher()
        try {
            withContext(dispatcher) {
                for (optimized in listOf(false, true)) repeat(20_000) { recheck(controller, connection, binder, optimized) }
                for (kind in listOf("TIME", "ALLOC")) {
                    for ((batch, optimized) in ORDER.withIndex()) {
                        val before = if (kind == "ALLOC") allocated() else null
                        val cpuStart = if (kind == "TIME") Debug.threadCpuTimeNanos() else 0L
                        val wallStart = if (kind == "TIME") SystemClock.elapsedRealtimeNanos() else 0L
                        repeat(LOCAL_COUNT) { recheck(controller, connection, binder, optimized) }
                        val cpu = if (kind == "TIME") Debug.threadCpuTimeNanos() - cpuStart else 0L
                        val wall = if (kind == "TIME") SystemClock.elapsedRealtimeNanos() - wallStart else 0L
                        val after = if (kind == "ALLOC") allocated() else null
                        val bytes = if (before != null && after != null && after >= before) after - before else null
                        val mode = if (optimized) "guard" else "lookup"
                        rows.add("$kind,$batch,$mode,$LOCAL_COUNT,$cpu,$wall,${before ?: ""},${after ?: ""},${bytes ?: ""}")
                        Log.i(TAG, "$kind run=$runId batch=$batch mode=$mode count=$LOCAL_COUNT cpuNs=$cpu " +
                            "wallNs=$wall bytes=$bytes scope=presend_step_only_process_allocation")
                    }
                }
            }
        } finally { dispatcher.close() }
        File(directory, "guard-local-$runId.csv").writeText(rows.joinToString("\n", postfix = "\n"))
    }

    private suspend fun ipc(controller: IpcConnectionController, directory: File, runId: String) {
        val connection = controller.awaitConnected(10_000)
        val diagnostics = IBenchmarkEchoServiceClientAdapter(controller)
        for (round in 1..4) {
            val modes = if (round == 1 || round == 4) listOf(false, true) else listOf(true, false)
            val sizes = if (round % 2 == 1) listOf(16, 1024) else listOf(1024, 16)
            for (size in sizes) for (optimized in modes) {
                val payload = "x".repeat(size)
                awaitIdle(controller, diagnostics)
                repeat(50) { check(echo(controller, connection, payload, optimized) == payload) }
                val samples = LongArray(IPC_COUNT)
                for (index in samples.indices) {
                    val start = SystemClock.elapsedRealtimeNanos()
                    check(echo(controller, connection, payload, optimized) == payload)
                    samples[index] = SystemClock.elapsedRealtimeNanos() - start
                }
                awaitIdle(controller, diagnostics)
                val mode = if (optimized) "guard" else "lookup"
                val file = File(directory, "guard-ipc-$runId-r$round-$mode-$size.csv")
                file.writeText(samples.joinToString("\n", postfix = "\n"))
                val sorted = samples.sorted()
                Log.i(TAG, "RESULT run=$runId round=$round mode=$mode size=$size count=$IPC_COUNT failures=0 " +
                    "p50Ms=${sorted[499] / 1e6} p99Ms=${sorted[989] / 1e6} maxMs=${sorted.last() / 1e6} file=${file.name}")
            }
        }
        check(diagnostics.inspectActiveSubscriptionCount() == 0)
        Log.i(TAG, "IPC_DONE run=$runId blocks=16 failures=0 requests=0 subscriptions=0 pending=0")
    }

    private suspend fun echo(controller: IpcConnectionController, connection: IpcClientState.Connected,
                             payload: String, optimized: Boolean): String {
        // Both lanes perform the same first strict resolution, registry, codec, callback and cancellation.
        val binder = resolve(controller, connection)
        return controller.pendingCallRegistry.callSuspend(9001, 10, connection.generation, READER,
            onRemoteCancel = { id ->
                val data = Parcel.obtain()
                try {
                    data.writeInterfaceToken(TOKEN)
                    data.writeLong(id)
                    data.writeStrongBinder(controller.globalResponseBinder)
                    binder.transact(11, data, null, IBinder.FLAG_ONEWAY)
                } finally { data.recycle() }
            }) { id ->
            val data = Parcel.obtain()
            try {
                data.writeInterfaceToken(TOKEN)
                data.writeLong(id)
                data.writeString(payload)
                data.writeStrongBinder(controller.globalResponseBinder)
                recheck(controller, connection, binder, optimized)
                check(binder.transact(10, data, null, IBinder.FLAG_ONEWAY))
            } finally { data.recycle() }
        }
    }

    private suspend fun awaitIdle(controller: IpcConnectionController, service: IBenchmarkEchoServiceClientAdapter) =
        withTimeout(3000) {
            check(controller.pendingCallRegistry.activeCallCount == 0)
            while (service.inspectActiveRequestCount() != 0) delay(2)
        }

    private fun allocated(): Long? = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()
}
