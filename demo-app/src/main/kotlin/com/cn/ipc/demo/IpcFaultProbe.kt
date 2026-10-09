package com.cn.ipc.demo

import android.util.Log
import com.cn.ipc.api.test.IUserServiceClientAdapter
import com.cn.ipc.client.IpcClientState
import com.cn.ipc.client.IpcConnectionController
import com.cn.ipc.client.PendingCallRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicInteger

/** Explicit fault intent only; registry races run on the Android runtime. */
object IpcFaultProbe {
    private const val TAG = "IpcFaultProbe"

    suspend fun run(controller: IpcConnectionController, runId: String) = withContext(Dispatchers.Default) {
        val registry = PendingCallRegistry()
        val cancels = AtomicInteger()
        val immediate: String = registry.callSuspend(1, 1, generation = 1L,
            onRemoteCancel = { cancels.incrementAndGet() }) { id ->
            check(registry.complete(id, "ok"))
        }
        check(immediate == "ok" && cancels.get() == 0)
        Log.i(TAG, "PASS run=$runId replyBeforeSendReturns cancelCount=0")

        coroutineScope {
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
                registry.callSuspend<String>(1, 1, generation = 2L,
                    onRemoteCancel = { cancels.incrementAndGet() }) {}
            }
            cancelled.cancelAndJoin()
        }
        check(cancels.get() == 1)
        Log.i(TAG, "PASS run=$runId explicitCancellation cancelCount=1")

        coroutineScope {
            val callJob = Job(coroutineContext[Job])
            try {
                val cancelled = async(callJob, start = CoroutineStart.UNDISPATCHED) {
                    registry.callSuspend<String>(1, 1, generation = 2L,
                        onRemoteCancel = { cancels.incrementAndGet() }) { callJob.cancel() }
                }
                withTimeout(2000) { cancelled.join() }
                check(cancelled.isCancelled)
            } finally {
                callJob.cancel()
            }
        }
        check(cancels.get() == 2)
        Log.i(TAG, "PASS run=$runId cancellationDuringDispatch cancelCount=1")

        repeat(1000) {
            coroutineScope {
                var id = 0L
                val call = async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { registry.callSuspend<String>(1, 1, generation = 3L) { id = it } }
                }
                val gate = CompletableDeferred<Unit>()
                val reply = async { gate.await(); registry.complete(id, "ok") }
                val disconnect = async { gate.await(); registry.failAllForGeneration(3L, IllegalStateException("lost")) }
                gate.complete(Unit)
                reply.await()
                disconnect.await()
                val result = withTimeout(2000) { call.await() }
                check(if (result.isSuccess) result.getOrNull() == "ok" else
                    result.exceptionOrNull() is IllegalStateException && result.exceptionOrNull()?.message == "lost")
                check(!registry.complete(id, "late"))
            }
        }
        Log.i(TAG, "PASS run=$runId replyDisconnectRace iterations=1000 lateRepliesRejected=true")

        val connected = controller.awaitConnected(10_000)
        val service = IUserServiceClientAdapter(controller)
        val error = runCatching {
            withTimeout(3000) { service.observeUserStatus("__factory_error__").first() }
        }.exceptionOrNull()
        check(error is IllegalArgumentException && error.message == "flow-factory-error") { "Wrong Flow failure: $error" }
        check(withTimeout(3000) { service.observeUserStatus("normal").first() } == 0)
        Log.i(TAG, "PASS run=$runId remoteFlowFactoryError propagated=true normalFlowFirstItem=0")

        coroutineScope {
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching {
                    controller.pendingCallRegistry.callSuspend<String>(1, 1, generation = connected.generation) {}
                }
            }
            controller.close()
            withTimeout(3000) { controller.state.first { it is IpcClientState.Closed } }
            check(withTimeout(3000) { pending.await() }.exceptionOrNull() is IllegalStateException)
        }
        val reconnected = controller.awaitConnected(10_000)
        check(reconnected.generation > connected.generation)
        val stale = runCatching {
            controller.pendingCallRegistry.callSuspend<String>(1, 1, generation = connected.generation) {
                controller.getServiceBinderForConnection(connected, 1001, 2)
            }
        }.exceptionOrNull()
        check(stale is IllegalStateException)
        check(withTimeout(3000) { service.observeUserStatus("reconnected").first() } == 0)
        Log.i(TAG, "PASS run=$runId closePendingFailed=true staleConnectionRejected=true reconnectedFlow=true")
        Log.i(TAG, "DONE run=$runId passed=true")
    }
}
