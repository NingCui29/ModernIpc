package com.cn.ipc.client.host

import android.os.Parcel
import com.cn.ipc.client.PendingCallRegistry
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

private class QueuedDispatcher : CoroutineDispatcher() {
    private val queue = ConcurrentLinkedQueue<Runnable>()
    override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
    fun drain() {
        var steps = 0
        while (true) {
            val next = queue.poll() ?: return
            check(++steps < 10_000) { "Dispatcher did not settle" }
            next.run()
        }
    }
}

private suspend fun immediateReply() {
    val registry = PendingCallRegistry()
    val cancels = AtomicInteger()
    var requestId = 0L
    val result: String = registry.callSuspendWithinDeadline(1, 1,
        onRemoteCancel = { cancels.incrementAndGet() }) {
        requestId = it
        check(registry.complete(it, "ok"))
    }
    check(result == "ok" && cancels.get() == 0 && registry.activeCallCount == 0)
    check(!registry.complete(requestId, "late"))
}

private suspend fun cancelDuringDispatch() = coroutineScope {
    val registry = PendingCallRegistry()
    val cancels = AtomicInteger()
    val owner = Job(coroutineContext[Job])
    try {
        val call = async(owner, start = CoroutineStart.UNDISPATCHED) {
            registry.callSuspendWithinDeadline<String>(1, 1,
                onRemoteCancel = { cancels.incrementAndGet() }) {
                owner.cancel()
                check(cancels.get() == 0) { "Remote cancellation preceded dispatch return" }
            }
        }
        call.join()
        check(call.isCancelled && cancels.get() == 1 && registry.activeCallCount == 0)
        registry.failAll(IllegalStateException("late-disconnect"))
        check(cancels.get() == 1)
    } finally { owner.cancel() }
}

private suspend fun disconnectDuringDispatch() {
    val registry = PendingCallRegistry()
    val cancels = AtomicInteger()
    var id = 0L
    val error = runCatching {
        registry.callSuspendWithinDeadline<String>(1, 1, generation = 7,
            onRemoteCancel = { cancels.incrementAndGet() }) {
            id = it
            registry.failAllForGeneration(7, IllegalStateException("lost"))
            check(cancels.get() == 0) { "Remote cancellation preceded dispatch return" }
        }
    }.exceptionOrNull()
    check(error?.message == "lost" && cancels.get() == 1 && registry.activeCallCount == 0)
    check(!registry.complete(id, "late"))
}

private suspend fun failedDispatch() {
    val registry = PendingCallRegistry()
    val cancels = AtomicInteger()
    for (disconnectFirst in listOf(false, true)) {
        var id = 0L
        val error = runCatching {
            registry.callSuspendWithinDeadline<String>(1, 1, generation = 7,
                onRemoteCancel = { cancels.incrementAndGet() }) {
                id = it
                if (disconnectFirst) registry.failAllForGeneration(7, IllegalStateException("lost"))
                throw IllegalArgumentException("send-error")
            }
        }.exceptionOrNull()
        check(error?.message == if (disconnectFirst) "lost" else "send-error")
        check(cancels.get() == 0 && registry.activeCallCount == 0)
        check(!registry.complete(id, "late"))
    }
}

private suspend fun cancelledBeforeRegistration() = coroutineScope {
    val registry = PendingCallRegistry()
    val owner = Job(coroutineContext[Job]).also { it.cancel() }
    val sends = AtomicInteger()
    val cancels = AtomicInteger()
    val call = async(owner, start = CoroutineStart.UNDISPATCHED) {
        registry.callSuspendWithinDeadline<String>(1, 1,
            onRemoteCancel = { cancels.incrementAndGet() }) { sends.incrementAndGet() }
    }
    call.join()
    check(call.isCancelled && sends.get() == 0 && cancels.get() == 0 && registry.activeCallCount == 0)
}

private suspend fun publicRegisterCompatibility() = coroutineScope {
    val registry = PendingCallRegistry()
    val hooks = AtomicInteger()
    for (throwingHook in listOf(false, true)) {
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                suspendCancellableCoroutine<String> { continuation ->
                    registry.register(41, 7, continuation, onConnectionClosed = {
                        hooks.incrementAndGet()
                        if (throwingHook) error("hook-error")
                    })
                }
            }
        }
        registry.failAllForGeneration(6, IllegalStateException("wrong-generation"))
        check(!waiter.isCompleted && registry.activeCallCount == 1)
        registry.failAllForGeneration(7, IllegalStateException("lost"))
        check(waiter.await().exceptionOrNull()?.message == "lost")
        check(registry.activeCallCount == 0 && !registry.complete(41, "late"))
    }
    check(hooks.get() == 2)
    val success = async(start = CoroutineStart.UNDISPATCHED) {
        suspendCancellableCoroutine<String> { continuation ->
            registry.register(42, 8, continuation, deserializer = { it.readString() },
                onConnectionClosed = { hooks.incrementAndGet() })
        }
    }
    val data = Parcel.obtain()
    try {
        data.writeString("registered")
        check(registry.completeWithParcel(42, data))
    } finally { data.recycle() }
    check(success.await() == "registered" && hooks.get() == 2 && registry.activeCallCount == 0)
}

private suspend fun parcelFailureAndRemoteError() = coroutineScope {
    val registry = PendingCallRegistry()
    for (decodeFailure in listOf(false, true)) {
        var id = 0L
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                registry.callSuspendWithinDeadline<String>(1, 1,
                    deserializer = { throw IllegalArgumentException("decode-error") }) { id = it }
            }
        }
        if (decodeFailure) {
            val data = Parcel.obtain()
            try { check(registry.completeWithParcel(id, data)) } finally { data.recycle() }
        } else check(registry.fail(id, IllegalStateException("remote-error")))
        check(waiter.await().exceptionOrNull()?.message == if (decodeFailure) "decode-error" else "remote-error")
        check(registry.activeCallCount == 0 && !registry.complete(id, "late"))
    }
}

private suspend fun replyDisconnectRace() = coroutineScope {
    val registry = PendingCallRegistry()
    val cancels = AtomicInteger()
    repeat(1000) {
        var id = 0L
        val beforeCancels = cancels.get()
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                registry.callSuspendWithinDeadline<String>(1, 1, generation = 7,
                    onRemoteCancel = { cancels.incrementAndGet() }) { id = it }
            }
        }
        val gate = CompletableDeferred<Unit>()
        val reply = async(Dispatchers.Default) { gate.await(); registry.complete(id, "ok") }
        val disconnect = async(Dispatchers.Default) {
            gate.await(); registry.failAllForGeneration(7, IllegalStateException("lost"))
        }
        gate.complete(Unit)
        val replyWon = reply.await()
        disconnect.await()
        val result = waiter.await()
        check(if (replyWon) result.getOrNull() == "ok" && cancels.get() == beforeCancels else
            result.exceptionOrNull()?.message == "lost" && cancels.get() == beforeCancels + 1)
        check(registry.activeCallCount == 0 && !registry.complete(id, "late"))
    }
}

private suspend fun defaultDeadline() {
    val registry = PendingCallRegistry(30)
    val cancels = AtomicInteger()
    var id = 0L
    val error = runCatching {
        registry.callSuspend<String>(1, 1, onRemoteCancel = { cancels.incrementAndGet() }) { id = it }
    }.exceptionOrNull()
    check(error is TimeoutCancellationException && error.message.orEmpty().contains("30 ms"))
    check(id > 0 && cancels.get() == 1 && registry.activeCallCount == 0 && !registry.complete(id, "late"))
}

private suspend fun bulkDisconnectAndThrowingCancel() = coroutineScope {
    val registry = PendingCallRegistry()
    val cancels = AtomicInteger()
    val waiters = (1L..2L).map { generation ->
        async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                registry.callSuspendWithinDeadline<String>(1, 1, generation = generation,
                    onRemoteCancel = { cancels.incrementAndGet(); error("cancel-hook-error") }) {}
            }
        }
    }
    registry.failAll(IllegalStateException("disposed"))
    check(waiters.awaitAll().all { it.exceptionOrNull()?.message == "disposed" })
    check(cancels.get() == 2 && registry.activeCallCount == 0)
}

private suspend fun promptCancellationAfterReply() {
    val registry = PendingCallRegistry()
    val dispatcher = QueuedDispatcher()
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val cancels = AtomicInteger()
    var id = 0L
    var observedValue = false
    try {
        val waiter = scope.async {
            registry.callSuspendWithinDeadline<String>(1, 1,
                onRemoteCancel = { cancels.incrementAndGet() }) { id = it }
            observedValue = true
        }
        dispatcher.drain()
        check(id > 0 && registry.complete(id, "ok"))
        waiter.cancel()
        dispatcher.drain()
        waiter.join()
        check(waiter.isCancelled && !observedValue && registry.activeCallCount == 0 && cancels.get() == 1)
    } finally { scope.cancel(); dispatcher.drain() }
}

private suspend fun callerDispatcherPreserved() {
    val registry = PendingCallRegistry()
    val caller = Executors.newSingleThreadExecutor { Thread(it, "registry-host-caller") }.asCoroutineDispatcher()
    val callback = Executors.newSingleThreadExecutor { Thread(it, "registry-host-callback") }
    try {
        withContext(caller) {
            val result: String = registry.callSuspendWithinDeadline(1, 1) { id ->
                callback.execute {
                    check(Thread.currentThread().name == "registry-host-callback")
                    check(registry.complete(id, "ok"))
                }
            }
            check(result == "ok" && Thread.currentThread().name == "registry-host-caller")
        }
        check(registry.activeCallCount == 0)
    } finally { caller.close(); callback.shutdownNow() }
}

fun main() = runBlocking {
    withTimeout(30_000) {
        val tests = listOf<Pair<String, suspend () -> Unit>>(
            "immediateReply" to ::immediateReply,
            "cancelDuringDispatch" to ::cancelDuringDispatch,
            "disconnectDuringDispatch" to ::disconnectDuringDispatch,
            "failedDispatch" to ::failedDispatch,
            "cancelledBeforeRegistration" to ::cancelledBeforeRegistration,
            "publicRegisterCompatibility" to ::publicRegisterCompatibility,
            "parcelFailureAndRemoteError" to ::parcelFailureAndRemoteError,
            "replyDisconnectRace1000" to ::replyDisconnectRace,
            "defaultDeadline" to ::defaultDeadline,
            "bulkDisconnectAndThrowingCancel" to ::bulkDisconnectAndThrowingCancel,
            "promptCancellationAfterReply" to ::promptCancellationAfterReply,
            "callerDispatcherPreserved" to ::callerDispatcherPreserved,
        )
        for ((name, test) in tests) { test(); println("PASS $name") }
        println("PASS pendingRegistryHost checks=${tests.size} deviceValidation=NOT_RUN binderValidation=NOT_RUN")
    }
}
