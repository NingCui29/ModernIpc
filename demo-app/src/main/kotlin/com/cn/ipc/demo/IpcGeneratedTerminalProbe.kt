package com.cn.ipc.demo

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.cn.ipc.api.test.IBenchmarkEchoServiceClientAdapter
import com.cn.ipc.api.test.IBenchmarkEchoServiceIpcSchema
import com.cn.ipc.client.IpcConnectionController
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger

/** Regression for generated error terminals: a business cancellation is not caller abandonment. */
object IpcGeneratedTerminalProbe {
    private const val TAG = "IpcGeneratedTerminalProbe"
    private const val TOKEN = "com.cn.ipc.api.test.IBenchmarkEchoService"

    suspend fun run(controller: IpcConnectionController, runId: String) = withContext(Dispatchers.Default) {
        val connection = controller.awaitConnected(10_000)
        val echo = IBenchmarkEchoServiceClientAdapter(controller)
        val schema = IBenchmarkEchoServiceIpcSchema.CLIENT_SCHEMA
        val binder = controller.getServiceBinderForConnection(connection, 9001, schema.contractVersion, schema)
        suspend fun idle() = withTimeout(3000) {
            while (echo.inspectActiveRequestCount() != 0) delay(2)
            check(controller.pendingCallRegistry.activeCallCount == 0)
            check(echo.inspectActiveSubscriptionCount() == 0)
        }
        idle()
        try {
            for (mode in listOf("default", "dedicated")) {
                val marker = "__dispatch_${mode}__"
                check(echo.echoDirect(marker) == marker)
                for (payload in listOf("__business_timeout__", "__business_cancel__")) {
                    // Observe the wire terminal directly, including a quiet window for duplicates.
                    val reply = rawFailure(binder, payload)
                    check(reply.status == 0 && expected(payload, reply.message)) { "Wrong raw terminal: $reply" }
                    idle()
                    Log.i(TAG, "PASS run=$runId dispatch=$mode path=raw scenario=$payload terminals=1 requests=0")

                    // A waiting generated client must fail from a reply, before its watchdog.
                    val start = SystemClock.elapsedRealtime()
                    val error = runCatching { withTimeout(2000) { echo.echo(payload) } }.exceptionOrNull()
                    check(error is RuntimeException && error !is CancellationException && expected(payload, error.message)) {
                        "Business cancellation lost its error terminal: $error"
                    }
                    idle()
                    Log.i(TAG, "PASS run=$runId dispatch=$mode path=adapter scenario=$payload elapsedMs=${SystemClock.elapsedRealtime() - start} pending=0 requests=0")
                }
            }
        } finally {
            withContext(NonCancellable) {
                idle()
                check(echo.echoDirect("__dispatch_default__") == "__dispatch_default__")
            }
        }
        check(echo.echo("terminal-restored") == "terminal-restored")
        idle()
        Log.i(TAG, "PASS run=$runId defaultRestored=true pending=0 requests=0 subscriptions=0")
        Log.i(TAG, "DONE run=$runId passed=true checks=9")
    }

    private fun expected(payload: String, message: String?): Boolean =
        if (payload == "__business_timeout__") message?.contains("Timed out waiting for 80 ms") == true
        else message == "probe-business-cancel"

    private data class Reply(val id: Long, val status: Int, val message: String?)

    private suspend fun rawFailure(binder: IBinder, payload: String): Reply {
        val id = -SystemClock.elapsedRealtimeNanos()
        val received = CompletableDeferred<Reply>()
        val terminals = AtomicInteger()
        val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != 1) return super.onTransact(code, data, reply, flags)
                if (Binder.getCallingUid() != Process.myUid()) throw SecurityException("Unexpected reply UID")
                try {
                    val value = Reply(data.readLong(), data.readInt(), data.readString())
                    if (terminals.incrementAndGet() == 1) received.complete(value)
                } catch (error: Exception) {
                    received.completeExceptionally(error)
                }
                return true
            }
        }
        val request = Parcel.obtain()
        try {
            request.writeInterfaceToken(TOKEN)
            request.writeLong(id)
            request.writeString(payload)
            request.writeStrongBinder(callback)
            check(binder.transact(10, request, null, IBinder.FLAG_ONEWAY))
        } finally { request.recycle() }
        return try {
            val result = withTimeout(2000) { received.await() }
            delay(50)
            check(result.id == id && terminals.get() == 1) { "Expected one terminal for the original request" }
            result
        } catch (error: Exception) {
            // Do not strand a remote request if a regression removes the expected reply.
            val cancel = Parcel.obtain()
            try {
                cancel.writeInterfaceToken(TOKEN)
                cancel.writeLong(id)
                cancel.writeStrongBinder(callback)
                binder.transact(11, cancel, null, IBinder.FLAG_ONEWAY)
            } finally { cancel.recycle() }
            throw error
        }
    }
}
