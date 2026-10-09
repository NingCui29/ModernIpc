package com.cn.ipc.demo

import android.os.SystemClock
import android.util.Log
import com.cn.ipc.api.test.IBenchmarkEchoServiceClientAdapter
import com.cn.ipc.client.IpcConnectionController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Same-APK comparison of legacy and optimized server authentication. */
object IpcAuthBenchmark {
    private const val TAG = "IpcAuthBench"
    private const val WARMUP = 50
    private const val ITERATIONS = 1000

    suspend fun run(controller: IpcConnectionController, directory: File, runId: String) {
        val runNumber = runId.lastOrNull()?.digitToIntOrNull()
        require(runNumber != null && runNumber in 1..4) {
            "Authentication benchmark runId must end in 1, 2, 3, or 4"
        }
        controller.awaitConnected(10_000L)
        val echo = IBenchmarkEchoServiceClientAdapter(controller)
        withContext(Dispatchers.Default) {
            val authModes = if (runNumber == 1 || runNumber == 4) {
                listOf("legacy", "optimized")
            } else {
                listOf("optimized", "legacy")
            }
            val directModes = if (runNumber % 2 == 0) {
                listOf(true, false)
            } else {
                listOf(false, true)
            }
            val payload = "x".repeat(16)
            try {
                for (mode in authModes) {
                    val command = "__auth_${mode}__"
                    check(echo.echoDirect(command) == command) {
                        "Server did not acknowledge authentication mode $mode"
                    }
                    for (direct in directModes) {
                        repeat(WARMUP) {
                            check((if (direct) echo.echoDirect(payload) else echo.echo(payload)) == payload)
                        }
                        val samples = LongArray(ITERATIONS)
                        for (index in samples.indices) {
                            val start = SystemClock.elapsedRealtimeNanos()
                            check((if (direct) echo.echoDirect(payload) else echo.echo(payload)) == payload)
                            samples[index] = SystemClock.elapsedRealtimeNanos() - start
                        }
                        val api = if (direct) "direct" else "suspend"
                        val output = File(directory, "auth-$runId-$mode-$api.csv")
                        output.writeText(samples.joinToString(separator = "\n", postfix = "\n"))
                        val sorted = samples.sorted()
                        Log.i(TAG, "RESULT run=$runId mode=$mode api=$api count=$ITERATIONS failures=0 " +
                            "p50Ms=${sorted[499] / 1_000_000.0} " +
                            "p90Ms=${sorted[899] / 1_000_000.0} " +
                            "p99Ms=${sorted[989] / 1_000_000.0} " +
                            "meanMs=${samples.average() / 1_000_000.0} file=${output.name}")
                    }
                }
            } finally {
                check(echo.echoDirect("__auth_optimized__") == "__auth_optimized__") {
                    "Server did not acknowledge restored optimized authentication"
                }
            }
            Log.i(TAG, "DONE run=$runId restored=optimized")
        }
    }
}
