package com.cn.ipc.demo

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger

/** Functional races are outside the timed regions and run against both complete implementations. */
internal object PendingBenchmarkRaces {
    private const val TAG = "IpcPendingBench"

    suspend fun run(mode: PendingBenchmarkMode, runId: String) = coroutineScope {
        val port = mode.create()
        val cancels = AtomicInteger()
        val immediate: String = port.call(onRemoteCancel = { cancels.incrementAndGet() }) {
            check(port.complete(it, "ok"))
        }
        check(immediate == "ok" && cancels.get() == 0 && port.activeCallCount == 0)
        pass(runId, mode, "replyBeforeDispatchReturn", "cancelCount=0")

        var disconnectedId = 0L
        val disconnected = runCatching {
            port.call<String>(generation = 2, onRemoteCancel = { cancels.incrementAndGet() }) {
                disconnectedId = it
                port.failGeneration(2, IllegalStateException("lost-during-dispatch"))
            }
        }
        check(disconnected.exceptionOrNull()?.message == "lost-during-dispatch")
        check(cancels.get() == 1 && port.activeCallCount == 0 && !port.complete(disconnectedId, "late"))
        pass(runId, mode, "disconnectDuringDispatch", "cancelCount=1 lateReplyRejected=true")

        val cancellationOwner = Job(coroutineContext[Job])
        try {
            val cancelled = async(cancellationOwner, start = CoroutineStart.UNDISPATCHED) {
                port.call<String>(generation = 3, onRemoteCancel = { cancels.incrementAndGet() }) {
                    cancellationOwner.cancel()
                }
            }
            withTimeout(3000) { cancelled.join() }
            check(cancelled.isCancelled && cancels.get() == 2 && port.activeCallCount == 0)
        } finally { cancellationOwner.cancel() }
        pass(runId, mode, "cancelDuringDispatch", "cancelCount=1")

        repeat(1000) {
            coroutineScope {
                var requestId = 0L
                val beforeCancels = cancels.get()
                val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching {
                        port.call<String>(generation = 4, onRemoteCancel = { cancels.incrementAndGet() }) { requestId = it }
                    }
                }
                check(requestId > 0)
                val gate = CompletableDeferred<Unit>()
                val reply = async { gate.await(); port.complete(requestId, "ok") }
                val disconnect = async { gate.await(); port.failGeneration(4, IllegalStateException("lost")) }
                gate.complete(Unit)
                val replyWon = reply.await()
                disconnect.await()
                val result = withTimeout(3000) { waiter.await() }
                val cancellations = cancels.get() - beforeCancels
                check(if (replyWon) result.getOrNull() == "ok" && cancellations == 0 else
                    result.exceptionOrNull()?.message == "lost" && cancellations == 1)
                check(!port.complete(requestId, "late") && port.activeCallCount == 0)
            }
        }
        pass(runId, mode, "replyDisconnectRace", "iterations=1000 winnerMatchesResult=true cancelAtMostOnce=true lateRepliesRejected=true")

        val throwingHook = AtomicInteger()
        var hookRequestId = 0L
        val hookFailure = runCatching {
            port.call<String>(generation = 5, onRemoteCancel = {
                throwingHook.incrementAndGet()
                throw IllegalStateException("hook-error")
            }) {
                hookRequestId = it
                port.failGeneration(5, IllegalStateException("lost-with-hook"))
            }
        }
        check(hookFailure.exceptionOrNull()?.message == "lost-with-hook")
        check(throwingHook.get() == 1 && port.activeCallCount == 0 && !port.complete(hookRequestId, "late"))
        pass(runId, mode, "throwingCancelHook", "hookCalls=1 pending=0 lateReplyRejected=true")

        val timed = mode.create(timeoutMs = 30)
        val timeoutCancels = AtomicInteger()
        var timedId = 0L
        val start = SystemClock.elapsedRealtime()
        val timeoutError = runCatching {
            withTimeout(3000) {
                timed.call<String>(onRemoteCancel = { timeoutCancels.incrementAndGet() }) { timedId = it }
            }
        }.exceptionOrNull()
        val elapsedMs = SystemClock.elapsedRealtime() - start
        check(timeoutError is TimeoutCancellationException && timeoutError.message.orEmpty().contains("30 ms"))
        check(elapsedMs in 20L..1000L && timedId > 0 && timeoutCancels.get() == 1)
        check(timed.activeCallCount == 0 && !timed.complete(timedId, "late"))
        pass(runId, mode, "defaultDeadline", "defaultMs=30 elapsedMs=$elapsedMs cancelCount=1 pending=0 lateReplyRejected=true")
    }

    private fun pass(runId: String, mode: PendingBenchmarkMode, name: String, details: String) {
        Log.i(TAG, "PASS run=$runId mode=${mode.label} $name $details")
    }
}
