package com.cn.ipc.demo

import android.os.SystemClock
import android.os.Looper
import android.util.Log
import com.cn.ipc.api.test.IBenchmarkEchoServiceClientAdapter
import com.cn.ipc.client.IpcConnectionController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File

/** Triggered only by the explicit ipc_benchmark intent extra. */
object IpcEchoBenchmark {
    private const val TAG = "IpcEchoBench"
    private const val WARMUP = 50
    private const val ITERATIONS = 1000
    private const val CONCURRENT_CALLS = 1024

    suspend fun run(controller: IpcConnectionController, directory: File, runId: String) {
        controller.awaitConnected(10_000L)
        val echo = IBenchmarkEchoServiceClientAdapter(controller)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            check(runCatching { echo.echoDirect("guard") }.exceptionOrNull() is IllegalStateException)
        }
        withContext(Dispatchers.Default) {
            // Verify framework exceptions use the same envelope as direct replies.
            val binder = controller.getServiceBinder(9001, 3)
            for (badToken in listOf(true, false)) {
                val data = android.os.Parcel.obtain()
                val reply = android.os.Parcel.obtain()
                try {
                    data.writeInterfaceToken(if (badToken) "invalid.token" else "com.cn.ipc.api.test.IBenchmarkEchoService")
                    if (badToken) data.writeString("guard") else data.writeString(null)
                    check(binder.transact(12, data, reply, 0))
                    val error = runCatching { reply.readException() }.exceptionOrNull()
                    check(if (badToken) error is SecurityException else error is IllegalArgumentException)
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }
            val remoteError = runCatching { echo.echoDirect("__error__") }.exceptionOrNull()
            check(remoteError is RuntimeException && remoteError.message == "bench-error")
            check(echo.echoIntDirect(42) == 42)
            check(echo.echoLongDirect(Long.MAX_VALUE) == Long.MAX_VALUE)
            check(echo.echoBooleanDirect(true))
            check(!echo.echoBooleanDirect(false))
            check(echo.echoBytesDirect(byteArrayOf(0, 1, -1)).contentEquals(byteArrayOf(0, 1, -1)))
            Log.i(TAG, "SMOKE run=$runId frameworkErrors=2 businessError=1 primitiveTypes=4 passed=true")
            val directFirst = runId.lastOrNull()?.digitToIntOrNull()?.rem(2) == 0
            val modes = if (directFirst) listOf(true, false) else listOf(false, true)
            for (size in intArrayOf(16, 1024)) {
                val payload = "x".repeat(size)
                for (direct in modes) {
                    repeat(WARMUP) {
                        check((if (direct) echo.echoDirect(payload) else echo.echo(payload)) == payload)
                    }
                    val samples = LongArray(ITERATIONS)
                    for (index in samples.indices) {
                        val start = SystemClock.elapsedRealtimeNanos()
                        check((if (direct) echo.echoDirect(payload) else echo.echo(payload)) == payload)
                        samples[index] = SystemClock.elapsedRealtimeNanos() - start
                    }
                    val sorted = samples.sorted()
                    val prefix = if (direct) "modern-direct-" else "modern-"
                    val output = File(directory, prefix + runId + "-" + size + ".csv")
                    output.writeText(samples.joinToString(separator = "\n", postfix = "\n"))
                    val mean = samples.average() / 1_000_000.0
                    val p50 = sorted[499] / 1_000_000.0
                    val p90 = sorted[899] / 1_000_000.0
                    val p99 = sorted[989] / 1_000_000.0
                    Log.i(TAG, "RESULT run=" + runId + " mode=" + (if (direct) "direct" else "suspend") + " size=" + size +
                        " count=" + ITERATIONS + " failures=0 meanMs=" + mean +
                        " p50Ms=" + p50 + " p90Ms=" + p90 + " p99Ms=" + p99 +
                        " file=" + output.name)
                }
            }
        }
    }

    suspend fun runConcurrency(controller: IpcConnectionController, directory: File, runId: String) {
        controller.awaitConnected(10_000L)
        val echo = IBenchmarkEchoServiceClientAdapter(controller)
        withContext(Dispatchers.IO) {
            val payload = "x".repeat(16)
            for (concurrency in intArrayOf(16, 64)) {
                val modes = if (runId.lastOrNull()?.digitToIntOrNull()?.rem(2) == 0)
                    listOf(true, false) else listOf(false, true)
                for (direct in modes) {
                    repeat(WARMUP) {
                        check((if (direct) echo.echoDirect(payload) else echo.echo(payload)) == payload)
                    }
                    val samples = LongArray(CONCURRENT_CALLS)
                    val gate = CompletableDeferred<Unit>()
                    val durationNanos = coroutineScope {
                        val jobs = (0 until concurrency).map { worker ->
                            async(Dispatchers.IO) {
                                gate.await()
                                repeat(CONCURRENT_CALLS / concurrency) { iteration ->
                                    val index = worker * (CONCURRENT_CALLS / concurrency) + iteration
                                    val start = SystemClock.elapsedRealtimeNanos()
                                    check((if (direct) echo.echoDirect(payload) else echo.echo(payload)) == payload)
                                    samples[index] = SystemClock.elapsedRealtimeNanos() - start
                                }
                            }
                        }
                        val start = SystemClock.elapsedRealtimeNanos()
                        gate.complete(Unit)
                        jobs.awaitAll()
                        SystemClock.elapsedRealtimeNanos() - start
                    }
                    val sorted = samples.sorted()
                    val mode = if (direct) "direct" else "suspend"
                    File(directory, "modern-$mode-$runId-c$concurrency.csv")
                        .writeText(samples.joinToString(separator = "\n", postfix = "\n"))
                    val seconds = durationNanos / 1_000_000_000.0
                    Log.i(TAG, "STRESS run=$runId mode=$mode concurrency=$concurrency " +
                        "count=$CONCURRENT_CALLS failures=0 durationMs=${durationNanos / 1_000_000.0} " +
                        "successQps=${CONCURRENT_CALLS / seconds} p50Ms=${sorted[511] / 1_000_000.0} " +
                        "p99Ms=${sorted[1013] / 1_000_000.0}")
                }
            }
        }
    }
}
