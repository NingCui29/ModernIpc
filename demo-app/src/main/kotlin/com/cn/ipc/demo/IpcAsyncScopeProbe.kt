package com.cn.ipc.demo

import android.util.Log
import com.cn.ipc.IpcRequestTrace
import com.cn.ipc.api.test.IBenchmarkEchoServiceClientAdapter
import com.cn.ipc.client.IpcConnectionController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList

/** Device checks for the optional short Async scope, independent of timing samples. */
object IpcAsyncScopeProbe {
    suspend fun run(controller: IpcConnectionController, runId: String) = withContext(Dispatchers.Default) {
        controller.awaitConnected(10_000)
        val echo = IBenchmarkEchoServiceClientAdapter(controller)
        suspend fun idle() = withTimeout(3000) {
            while (echo.inspectActiveRequestCount() != 0 || echo.inspectActiveSubscriptionCount() != 0) delay(2)
            check(controller.pendingCallRegistry.activeCallCount == 0)
        }
        idle()
        check(IpcRequestTrace.nowIfEnabled() == 0L)
        check(echo.echoDirect("__dispatch_dedicated__") == "__dispatch_dedicated__")
        Log.i(TAG, "PASS run=$runId dedicatedSelected=true traceDisabled=true")
        try {
            coroutineScope {
                val held = async { echo.holdLifecycleRequest() }
                withTimeout(3000) { while (echo.inspectActiveRequestCount() != 1) delay(2) }
                val ticks = withTimeout(3000) { echo.observeLifecycleTicks().take(3).toList() }
                check(ticks == listOf(0, 1, 2) && held.isActive)
                held.cancelAndJoin()
            }
            idle()
            Log.i(TAG, "PASS run=$runId dedicatedCancel=true concurrentFlow=0,1,2 pending=0 requests=0 subscriptions=0")
            val error = runCatching { withTimeout(150) { echo.holdLifecycleRequest() } }.exceptionOrNull()
            check(error is TimeoutCancellationException)
            idle()
            Log.i(TAG, "PASS run=$runId dedicatedDeadline=true pending=0 requests=0 subscriptions=0")
        } finally {
            withContext(NonCancellable) {
                idle()
                check(echo.echoDirect("__dispatch_default__") == "__dispatch_default__")
            }
        }
        check(echo.echo("restored") == "restored")
        idle()
        Log.i(TAG, "PASS run=$runId defaultRestored=true pending=0 requests=0 subscriptions=0")
        Log.i(TAG, "DONE run=$runId passed=true checks=4")
    }

    private const val TAG = "IpcAsyncScopeProbe"
}
