package com.cn.ipc.demo

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

/** Explicit benchmark configuration; caller resumption and Async wire stay unchanged. */
object IpcAsyncComparisonBenchmark {
    suspend fun run(controller: IpcConnectionController, directory: File, runId: String, mode: String) =
        withContext(Dispatchers.Default) {
            require(mode == "default" || mode == "dedicated")
            controller.awaitConnected(10_000)
            val echo = IBenchmarkEchoServiceClientAdapter(controller)
            suspend fun idle() = withTimeout(3000) {
                while (echo.inspectActiveRequestCount() != 0) delay(2)
                check(controller.pendingCallRegistry.activeCallCount == 0)
            }
            check(IpcRequestTrace.nowIfEnabled() == 0L)
            check(echo.echoDirect("__trace_flush__") == "events=0,dropped=0")
            idle()
            val marker = "__dispatch_${mode}__"
            check(echo.echoDirect(marker) == marker)
            Log.i("IpcEchoBench", "PASS run=$runId asyncDispatch=$mode traceDisabled=true")
            try {
                IpcEchoBenchmark.run(controller, directory, runId)
                idle()
                check(echo.inspectActiveSubscriptionCount() == 0)
                Log.i("IpcEchoBench", "PASS run=$runId pending=0 requests=0 subscriptions=0")
            } finally {
                withContext(NonCancellable) {
                    idle()
                    check(echo.echoDirect("__dispatch_default__") == "__dispatch_default__")
                }
            }
            Log.i("IpcEchoBench", "DONE run=$runId passed=true asyncDispatch=$mode defaultRestored=true count=4000")
        }
}
