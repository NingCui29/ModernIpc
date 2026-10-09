package com.cn.ipc.demo

import android.os.SystemClock
import android.util.Log
import com.cn.ipc.IpcRequestTrace
import com.cn.ipc.api.test.IBenchmarkEchoServiceClientAdapter
import com.cn.ipc.client.IpcConnectionController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/** Diagnostic trace on/off runs use the same warmed adapter, payload and serial request loop. */
object IpcRequestTraceBenchmark {
    private const val TAG = "IpcTraceBench"
    private const val COUNT = 1000
    private const val WARMUP = 50
    private const val PAYLOAD_CHARS = 16

    suspend fun run(controller: IpcConnectionController, directory: File, runId: String, enabled: Boolean = true) =
        withContext(Dispatchers.Default) {
            controller.awaitConnected(10_000)
            val echo = IBenchmarkEchoServiceClientAdapter(controller)
            val payload = "x".repeat(PAYLOAD_CHARS)
            val samples = LongArray(COUNT)
            var clientFlushed = false
            var serverFlushed = false
            Log.i(TAG, "START run=$runId trace=$enabled count=$COUNT warmup=$WARMUP payloadChars=$PAYLOAD_CHARS dispatcher=Default")
            try {
                // Tracing is disabled by default; warm discovery and request machinery before starting either side.
                repeat(WARMUP) { check(echo.echo(payload) == payload) }
                if (enabled) {
                    val marker = "__trace_start__:$runId"
                    check(echo.echoDirect(marker) == marker)
                    IpcRequestTrace.start(runId)
                }
                for (index in samples.indices) {
                    val started = SystemClock.elapsedRealtimeNanos()
                    val response = echo.echo(payload)
                    samples[index] = SystemClock.elapsedRealtimeNanos() - started
                    check(response == payload)
                }
                // Flush only after the measurement loop; neither EVT logging nor file output is on the RTT path.
                val eventFile = if (enabled) File(directory, "request-events-$runId-client.log") else null
                val client = IpcRequestTrace.stopAndFlush(eventFile).also { clientFlushed = true }
                val server = parseStats(echo.echoDirect("__trace_flush__")).also { serverFlushed = true }
                check(client.dropped == 0 && server.dropped == 0) {
                    "Trace buffer dropped events: client=${client.dropped} server=${server.dropped}"
                }
                if (enabled) {
                    check(client.events > 0 && server.events > 0) { "Missing trace events on one side" }
                } else {
                    check(client.events == 0 && server.events == 0) { "Tracing was enabled during an off run" }
                }
                withTimeout(3000) {
                    while (echo.inspectActiveRequestCount() != 0) delay(2)
                }
                check(controller.pendingCallRegistry.activeCallCount == 0)
                val safeRunId = runId.replace(Regex("[^A-Za-z0-9_.-]"), "_")
                val filename = "trace-rtt-$safeRunId.csv"
                withContext(Dispatchers.IO) {
                    File(directory, filename).bufferedWriter().use { writer ->
                        writer.appendLine("requestIndex,payloadChars,rttNs")
                        samples.forEachIndexed { index, sample -> writer.appendLine("${index + 1},$PAYLOAD_CHARS,$sample") }
                    }
                    File(directory, "trace-stats-$safeRunId.csv").writeText(
                        "side,enabled,events,dropped\nclient,$enabled,${client.events},${client.dropped}\n" +
                            "server,$enabled,${server.events},${server.dropped}\n"
                    )
                }
                Log.i(TAG, "PASS run=$runId serialEcho trace=$enabled count=$COUNT failures=0 pending=0 requests=0 file=$filename")
                Log.i(TAG, "PASS run=$runId traceBuffers clientEvents=${client.events} serverEvents=${server.events} clientDropped=0 serverDropped=0")
                Log.i(TAG, "DONE run=$runId passed=true checks=2 trace=$enabled count=$COUNT clientEvents=${client.events} serverEvents=${server.events} dropped=0")
            } finally {
                withContext(NonCancellable + Dispatchers.Default) {
                    if (!clientFlushed) IpcRequestTrace.stopAndFlush(
                        if (enabled) File(directory, "request-events-$runId-client.log") else null)
                    if (!serverFlushed) runCatching { echo.echoDirect("__trace_flush__") }
                }
            }
        }

    private data class RemoteStats(val events: Int, val dropped: Int)

    private fun parseStats(reply: String): RemoteStats {
        val match = Regex("events=(\\d+),dropped=(\\d+)").matchEntire(reply)
            ?: error("Invalid server trace stats: $reply")
        return RemoteStats(match.groupValues[1].toInt(), match.groupValues[2].toInt())
    }
}
