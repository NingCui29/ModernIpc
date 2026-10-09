package com.cn.ipc.demo

import android.os.Binder
import android.os.Debug
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import com.cn.ipc.api.test.IBenchmarkEchoServiceClientAdapter
import com.cn.ipc.api.test.IBenchmarkEchoServiceIpcSchema
import com.cn.ipc.client.IpcConnectionController
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Same-APK A/B of the complete pending-call path; local timings contain no remote Binder work. */
object IpcPendingBenchmark {
    private const val TAG = "IpcPendingBench"
    private const val LOCAL_COUNT = 50_000
    private const val LOCAL_WARMUP = 2000
    private const val IPC_COUNT = 1000
    private const val IPC_WARMUP = 50
    private const val PAYLOAD = "xxxxxxxxxxxxxxxx"
    private const val TOKEN = "com.cn.ipc.api.test.IBenchmarkEchoService"
    private const val BASELINE_SHA = "686065E9D7304328327085E2CBE04E17703AAB4FCA0BB4447AB649B132833B1F"
    private val STRING_READER: (Parcel) -> Any? = { it.readString() ?: error("Missing echo payload") }
    private val ABBA = listOf(PendingBenchmarkMode.LEGACY, PendingBenchmarkMode.OPTIMIZED,
        PendingBenchmarkMode.OPTIMIZED, PendingBenchmarkMode.LEGACY)

    suspend fun run(controller: IpcConnectionController, directory: File, runId: String) =
        withContext(Dispatchers.Default) {
            controller.awaitConnected(10_000)
            Log.i(TAG, "START run=$runId baselineSourceSha=$BASELINE_SHA localCount=$LOCAL_COUNT ipcCount=$IPC_COUNT")
            local(directory, runId)
            for (mode in PendingBenchmarkMode.values()) races(mode, runId)
            ipc(controller, directory, runId)
            check(controller.pendingCallRegistry.activeCallCount == 0)
            Log.i(TAG, "DONE run=$runId passed=true localTimeBlocks=8 localAllocationBlocks=8 raceChecks=12 ipcBlocks=16")
        }

    private suspend fun local(directory: File, runId: String) {
        val rows = mutableListOf("kind,scenario,batch,mode,count,wallNs,cpuNs,beforeBytes,afterBytes,bytes,gc")
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "IpcPendingLocalBench")
        }.asCoroutineDispatcher()
        try {
            withContext(dispatcher) {
                for (scenario in listOf("immediate", "parcel")) {
                    val ports = PendingBenchmarkMode.values().associateWith { it.create() }
                    val cancels = AtomicInteger()
                    val cancelHook: (Long) -> Unit = { cancels.incrementAndGet() }
                    for (mode in PendingBenchmarkMode.values()) {
                        localCalls(ports.getValue(mode), scenario, LOCAL_WARMUP, cancelHook)
                        Log.i(TAG, "WARMUP run=$runId scenario=$scenario mode=${mode.label} count=$LOCAL_WARMUP")
                    }
                    for ((batch, mode) in ABBA.withIndex()) {
                        val port = ports.getValue(mode)
                        val cpuStart = Debug.threadCpuTimeNanos()
                        val wallStart = SystemClock.elapsedRealtimeNanos()
                        localCalls(port, scenario, LOCAL_COUNT, cancelHook)
                        val wallNs = SystemClock.elapsedRealtimeNanos() - wallStart
                        val cpuNs = Debug.threadCpuTimeNanos() - cpuStart
                        check(port.activeCallCount == 0 && cancels.get() == 0)
                        rows.add("TIME,$scenario,$batch,${mode.label},$LOCAL_COUNT,$wallNs,$cpuNs,,,,")
                        Log.i(TAG, "TIME run=$runId scenario=$scenario batch=$batch mode=${mode.label} count=$LOCAL_COUNT " +
                            "wallNs=$wallNs cpuNs=$cpuNs scope=single_thread_complete_registry_no_remote_binder")
                    }
                    // ART counters cover the entire client process and are collected separately from CPU timings.
                    for ((batch, mode) in ABBA.withIndex()) {
                        val port = ports.getValue(mode)
                        val beforeBytes = runtimeCount("art.gc.bytes-allocated")
                        val beforeGc = runtimeCount("art.gc.gc-count")
                        localCalls(port, scenario, LOCAL_COUNT, cancelHook)
                        val afterBytes = runtimeCount("art.gc.bytes-allocated")
                        val afterGc = runtimeCount("art.gc.gc-count")
                        val bytes = delta(beforeBytes, afterBytes)
                        val gc = delta(beforeGc, afterGc)
                        check(port.activeCallCount == 0 && cancels.get() == 0)
                        rows.add("ALLOC,$scenario,$batch,${mode.label},$LOCAL_COUNT,,,${csv(beforeBytes)},${csv(afterBytes)},${csv(bytes)},${csv(gc)}")
                        Log.i(TAG, "ALLOC run=$runId scenario=$scenario batch=$batch mode=${mode.label} count=$LOCAL_COUNT " +
                            "beforeBytes=$beforeBytes afterBytes=$afterBytes bytes=$bytes gc=$gc scope=process_wide_approximate")
                    }
                }
            }
        } finally {
            dispatcher.close()
        }
        val output = File(directory, "pending-local-$runId.csv")
        output.writeText(rows.joinToString("\n", postfix = "\n"))
        Log.i(TAG, "LOCAL_DONE run=$runId rows=16 file=${output.name}")
    }

    private suspend fun localCalls(
        port: PendingBenchmarkPort,
        scenario: String,
        count: Int,
        cancelHook: (Long) -> Unit
    ) {
        repeat(count) {
            val result: String = if (scenario == "immediate") {
                port.call(onRemoteCancel = cancelHook) { requestId -> check(port.complete(requestId, PAYLOAD)) }
            } else {
                port.call(deserializer = STRING_READER, onRemoteCancel = cancelHook) { requestId ->
                    val reply = Parcel.obtain()
                    try {
                        reply.writeString(PAYLOAD)
                        reply.setDataPosition(0)
                        check(port.completeWithParcel(requestId, reply))
                    } finally { reply.recycle() }
                }
            }
            check(result == PAYLOAD)
        }
    }

    private suspend fun ipc(controller: IpcConnectionController, directory: File, runId: String) {
        val connection = controller.awaitConnected(10_000)
        val schema = IBenchmarkEchoServiceIpcSchema.CLIENT_SCHEMA
        val binder = controller.getServiceBinderForConnection(connection, 9001, schema.contractVersion, schema)
        val diagnostics = IBenchmarkEchoServiceClientAdapter(controller)
        val ports = PendingBenchmarkMode.values().associateWith { it.create() }
        val callback = SharedResponseBinder()
        for (round in 1..4) {
            val modes = if (round == 1 || round == 4) {
                listOf(PendingBenchmarkMode.LEGACY, PendingBenchmarkMode.OPTIMIZED)
            } else listOf(PendingBenchmarkMode.OPTIMIZED, PendingBenchmarkMode.LEGACY)
            val sizes = if (round % 2 == 1) listOf(16, 1024) else listOf(1024, 16)
            for (size in sizes) {
                val payload = "x".repeat(size)
                for (mode in modes) {
                    check(ports.values.all { it.activeCallCount == 0 })
                    awaitServerIdle(diagnostics)
                    val port = ports.getValue(mode)
                    callback.port = port
                    repeat(IPC_WARMUP) {
                        check(rawEcho(port, binder, callback, connection.generation, payload) == payload)
                    }
                    val samples = LongArray(IPC_COUNT)
                    for (index in samples.indices) {
                        check(controller.state.value === connection) { "Benchmark connection changed" }
                        val start = SystemClock.elapsedRealtimeNanos()
                        check(rawEcho(port, binder, callback, connection.generation, payload) == payload)
                        samples[index] = SystemClock.elapsedRealtimeNanos() - start
                    }
                    check(port.activeCallCount == 0 && callback.unexpectedReplies.get() == 0)
                    awaitServerIdle(diagnostics)
                    val output = File(directory, "pending-ipc-$runId-r$round-${mode.label}-$size.csv")
                    output.writeText(samples.joinToString("\n", postfix = "\n"))
                    val sorted = samples.sorted()
                    Log.i(TAG, "RESULT run=$runId round=$round mode=${mode.label} size=$size count=$IPC_COUNT failures=0 " +
                        "p50Ms=${sorted[499] / 1_000_000.0} p99Ms=${sorted[989] / 1_000_000.0} " +
                        "maxMs=${sorted.last() / 1_000_000.0} meanMs=${samples.average() / 1_000_000.0} " +
                        "file=${output.name} wire=shared_tx10_callback_binder")
                }
            }
        }
        callback.port = null
        check(diagnostics.inspectActiveSubscriptionCount() == 0 && diagnostics.inspectActiveRequestCount() == 0)
        Log.i(TAG, "IPC_DONE run=$runId blocks=16 failures=0 unexpectedReplies=0 requests=0 subscriptions=0")
    }

    private suspend fun rawEcho(port: PendingBenchmarkPort, binder: IBinder, callback: IBinder,
        generation: Long, payload: String): String = port.call(
        generation = generation,
        deserializer = STRING_READER,
        onRemoteCancel = { requestId ->
            val data = Parcel.obtain()
            try {
                data.writeInterfaceToken(TOKEN)
                data.writeLong(requestId)
                data.writeStrongBinder(callback)
                binder.transact(11, data, null, IBinder.FLAG_ONEWAY)
            } finally { data.recycle() }
        }
    ) { requestId ->
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(TOKEN)
            data.writeLong(requestId)
            data.writeString(payload)
            data.writeStrongBinder(callback)
            check(binder.transact(10, data, null, IBinder.FLAG_ONEWAY))
        } finally { data.recycle() }
    }

    private class SharedResponseBinder : Binder() {
        @Volatile var port: PendingBenchmarkPort? = null
        val unexpectedReplies = AtomicInteger()
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != 1) return super.onTransact(code, data, reply, flags)
            val requestId = data.readLong()
            val current = port
            val accepted = if (data.readInt() == 1) {
                current?.completeWithParcel(requestId, data) == true
            } else current?.fail(requestId, RuntimeException(data.readString() ?: "Remote error")) == true
            if (!accepted) unexpectedReplies.incrementAndGet()
            return true
        }
    }

    private suspend fun awaitServerIdle(service: IBenchmarkEchoServiceClientAdapter) = withTimeout(3000) {
        while (service.inspectActiveRequestCount() != 0) delay(2)
    }

    private fun runtimeCount(key: String): Long? = Debug.getRuntimeStat(key)?.toLongOrNull()
    private fun delta(before: Long?, after: Long?): Long? =
        if (before != null && after != null && after >= before) after - before else null
    private fun csv(value: Long?): String = value?.toString() ?: ""

    private suspend fun races(mode: PendingBenchmarkMode, runId: String) {
        PendingBenchmarkRaces.run(mode, runId)
    }
}
