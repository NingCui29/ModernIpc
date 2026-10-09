package com.cn.ipc.demo

import android.os.SystemClock
import android.util.Log
import com.cn.ipc.IpcRequestTrace
import com.cn.ipc.api.test.IBenchmarkEchoServiceClientAdapter
import com.cn.ipc.client.IpcConnectionController
import kotlinx.coroutines.*
import java.io.File

/** Application scope configuration experiment; no change to runtime scheduling or wire format. */
object IpcDispatchBenchmark {
    private const val TAG = "IpcDispatchBench"
    private const val COUNT = 1000
    private const val CONCURRENT_COUNT = 1024

    suspend fun run(controller: IpcConnectionController, directory: File, runId: String) =
        withContext(Dispatchers.Default) {
            controller.awaitConnected(10_000)
            val echo = IBenchmarkEchoServiceClientAdapter(controller)
            val rows = ArrayList<String>(30_000)
            rows.add("phase,round,mode,payloadChars,concurrency,index,rttNs,blockNs")
            suspend fun idle() = withTimeout(3000) {
                while (echo.inspectActiveRequestCount() != 0) delay(2)
                check(controller.pendingCallRegistry.activeCallCount == 0)
            }
            suspend fun select(mode: String) {
                idle()
                val marker = if (mode == "dedicated") "__dispatch_dedicated__" else "__dispatch_default__"
                check(echo.echoDirect(marker) == marker)
            }
            suspend fun call(mode: String, payload: String): String =
                if (mode == "direct") echo.echoDirect(payload) else echo.echo(payload)
            try {
                check(IpcRequestTrace.nowIfEnabled() == 0L)
                check(echo.echoDirect("__trace_flush__") == "events=0,dropped=0")
                Log.i(TAG, "PASS run=$runId traceDisabled=true")
                // Warm all lanes before any measured block; every block also has its own warmup.
                for (mode in listOf("default", "dedicated", "direct")) {
                    select(mode)
                    repeat(500) { check(call(mode, "x".repeat(16)) == "x".repeat(16)) }
                }
                for (round in 1..4) {
                    val modes = if (round == 1 || round == 4)
                        listOf("default", "dedicated", "direct") else listOf("direct", "dedicated", "default")
                    val sizes = if (round % 2 == 1) listOf(16, 1024) else listOf(1024, 16)
                    for (size in sizes) for (mode in modes) {
                        select(mode)
                        val payload = "x".repeat(size)
                        repeat(50) { check(call(mode, payload) == payload) }
                        val samples = LongArray(COUNT)
                        val blockStart = SystemClock.elapsedRealtimeNanos()
                        for (index in samples.indices) {
                            val start = SystemClock.elapsedRealtimeNanos()
                            check(call(mode, payload) == payload)
                            samples[index] = SystemClock.elapsedRealtimeNanos() - start
                        }
                        val blockNs = SystemClock.elapsedRealtimeNanos() - blockStart
                        idle()
                        samples.forEachIndexed { index, ns -> rows.add("serial,$round,$mode,$size,1,${index + 1},$ns,$blockNs") }
                        val sorted = samples.sorted()
                        Log.i(TAG, "RESULT run=$runId phase=serial round=$round mode=$mode size=$size count=$COUNT failures=0 " +
                            "p50Ms=${sorted[499] / 1e6} p99Ms=${sorted[989] / 1e6}")
                    }
                }
                Log.i(TAG, "PASS run=$runId serialBlocks=24 count=24000 failures=0")
                // A single worker can trade parallel capacity for locality. Measure this boundary too.
                for (round in 1..2) {
                    val modes = if (round == 1) listOf("default", "dedicated") else listOf("dedicated", "default")
                    for (mode in modes) {
                        select(mode)
                        val payload = "x".repeat(16)
                        repeat(50) { check(echo.echo(payload) == payload) }
                        val samples = LongArray(CONCURRENT_COUNT)
                        val blockStart = SystemClock.elapsedRealtimeNanos()
                        coroutineScope {
                            val gate = CompletableDeferred<Unit>()
                            val workers = (0 until 16).map { worker -> async {
                                gate.await()
                                repeat(CONCURRENT_COUNT / 16) { iteration ->
                                    val index = worker * (CONCURRENT_COUNT / 16) + iteration
                                    val start = SystemClock.elapsedRealtimeNanos()
                                    check(echo.echo(payload) == payload)
                                    samples[index] = SystemClock.elapsedRealtimeNanos() - start
                                }
                            } }
                            gate.complete(Unit)
                            workers.awaitAll()
                        }
                        val blockNs = SystemClock.elapsedRealtimeNanos() - blockStart
                        idle()
                        samples.forEachIndexed { index, ns -> rows.add("concurrent,$round,$mode,16,16,${index + 1},$ns,$blockNs") }
                        Log.i(TAG, "RESULT run=$runId phase=concurrent round=$round mode=$mode count=$CONCURRENT_COUNT " +
                            "failures=0 blockNs=$blockNs")
                    }
                }
                Log.i(TAG, "PASS run=$runId concurrentBlocks=4 count=4096 failures=0")
                select("default")
                check(echo.inspectActiveSubscriptionCount() == 0)
                File(directory, "dispatch-$runId.csv").writeText(rows.joinToString("\n", postfix = "\n"))
                Log.i(TAG, "PASS run=$runId defaultRestored=true pending=0 requests=0 subscriptions=0")
                Log.i(TAG, "DONE run=$runId passed=true checks=4 serialBlocks=24 concurrentBlocks=4")
            } finally {
                withContext(NonCancellable) { runCatching { select("default") } }
            }
        }
}
