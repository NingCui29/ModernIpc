package com.cn.ipc.server.host

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.cn.ipc.server.IpcRequestTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.yield
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

private class QueuedDispatcher : CoroutineDispatcher() {
    private val queue = ConcurrentLinkedQueue<Runnable>()
    override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
    fun drain() {
        var steps = 0
        while (true) {
            val next = queue.poll() ?: return
            check(++steps < 100_000) { "Dispatcher did not settle" }
            next.run()
        }
    }
}

private class Callback(private val linkFails: Boolean = false) : IBinder {
    val recipients = CopyOnWriteArrayList<IBinder.DeathRecipient>()
    val errors = ConcurrentHashMap<Long, CopyOnWriteArrayList<String>>()
    val terminals = ConcurrentHashMap<Long, AtomicInteger>()
    override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
        if (linkFails) error("DEAD_OBJECT")
        recipients.add(recipient)
    }
    override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean = recipients.remove(recipient)
    override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        check(code == 1 && flags == IBinder.FLAG_ONEWAY)
        val requestId = data.values[0] as Long
        check(data.values[1] == 0)
        errors.computeIfAbsent(requestId) { CopyOnWriteArrayList() }.add(data.values[2] as String)
        terminal(requestId)
        return true
    }
    fun terminal(requestId: Long) { terminals.computeIfAbsent(requestId) { AtomicInteger() }.incrementAndGet() }
    fun assertError(requestId: Long, expected: String) {
        check(errors[requestId]?.toList() == listOf(expected)) { "Expected one $expected for $requestId, got ${errors[requestId]}" }
    }
}

private class Fixture(maxInFlight: Int = 128) {
    val dispatcher = QueuedDispatcher()
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val tracker = IpcRequestTracker(maxInFlight)
    val callback = Callback()
    fun close() { scope.cancel(); dispatcher.drain(); check(tracker.activeRequestCount == 0) }
}

private fun normalCompletionAndPermitReuse() {
    val f = Fixture(1)
    var calls = 0
    repeat(2) { id ->
        f.tracker.submit(f.callback, id.toLong(), f.scope) {
            check(f.tracker.activeRequestCount == 1) { "Job started before registration" }
            calls++
        }
        check(calls == id)
        f.dispatcher.drain()
        check(f.tracker.activeRequestCount == 0 && f.callback.recipients.isEmpty())
    }
    check(calls == 2 && f.callback.errors.isEmpty())
    f.close()
}

private fun cancellationBeforeStart() {
    val f = Fixture(1)
    var calls = 0
    f.tracker.submit(f.callback, 1, f.scope) { calls++ }
    f.tracker.cancel(f.callback, 1)
    f.dispatcher.drain()
    check(calls == 0 && f.tracker.activeRequestCount == 0)
    f.callback.assertError(1, "REQUEST_CANCELLED")
    f.tracker.submit(f.callback, 2, f.scope) { calls++ }
    f.dispatcher.drain()
    check(calls == 1)
    f.close()
}

private fun duplicateRetainsOriginalAndPermit() {
    val f = Fixture(2)
    var original = 0
    var duplicate = 0
    f.tracker.submit(f.callback, 1, f.scope) { original++ }
    f.tracker.submit(f.callback, 1, f.scope) { duplicate++ }
    f.callback.assertError(1, "DUPLICATE_REQUEST")
    f.tracker.submit(f.callback, 2, f.scope) { original++ }
    check(f.tracker.activeRequestCount == 2)
    f.dispatcher.drain()
    check(original == 2 && duplicate == 0 && f.tracker.activeRequestCount == 0)
    f.close()
}

private fun cancellationRequiresOriginalUid() {
    val f = Fixture()
    var calls = 0
    Binder.setCallingUid(1000)
    f.tracker.submit(f.callback, 1, f.scope) { calls++ }
    Binder.setCallingUid(2000)
    f.tracker.cancel(f.callback, 1)
    f.dispatcher.drain()
    check(calls == 1 && f.callback.errors.isEmpty())
    Binder.setCallingUid(1000)
    f.close()
}

private fun cancellationRequiresOriginalCallback() {
    val f = Fixture()
    val other = Callback()
    var calls = 0
    f.tracker.submit(f.callback, 1, f.scope) { calls++ }
    f.tracker.cancel(other, 1)
    f.dispatcher.drain()
    check(calls == 1 && f.callback.errors.isEmpty())
    f.close()
}

private fun cancelledScopeBeforeAdmission() {
    val f = Fixture(1)
    f.scope.cancel()
    f.tracker.submit(f.callback, 1, f.scope) { error("Cancelled scope ran") }
    f.callback.assertError(1, "SERVER_SCOPE_CANCELLED")
    check(f.tracker.activeRequestCount == 0 && f.callback.recipients.isEmpty())
    f.close()
}

private fun scopeCancellationBeforeStart() {
    val f = Fixture()
    f.tracker.submit(f.callback, 1, f.scope) { error("Cancelled request ran") }
    f.scope.cancel()
    f.dispatcher.drain()
    f.callback.assertError(1, "SERVER_SCOPE_CANCELLED")
    f.close()
}

private fun disposeBeforeStartAndRejectAfterward() {
    val f = Fixture()
    f.tracker.submit(f.callback, 1, f.scope) { error("Disposed request ran") }
    f.tracker.dispose()
    f.tracker.dispose()
    f.dispatcher.drain()
    f.callback.assertError(1, "SERVER_DISPOSED")
    f.tracker.submit(f.callback, 2, f.scope) { error("Disposed tracker admitted request") }
    f.callback.assertError(2, "SERVER_DISPOSED")
    f.close()
}

private fun retains128RequestLimit() {
    val f = Fixture()
    var calls = 0
    repeat(128) { id -> f.tracker.submit(f.callback, id.toLong(), f.scope) { calls++ } }
    check(f.tracker.activeRequestCount == 128)
    f.tracker.submit(f.callback, 128, f.scope) { error("129th request admitted") }
    f.callback.assertError(128, "SERVER_BUSY")
    f.dispatcher.drain()
    check(calls == 128 && f.tracker.activeRequestCount == 0)
    repeat(128) { id -> f.tracker.submit(f.callback, (id + 129).toLong(), f.scope) { calls++ } }
    check(f.tracker.activeRequestCount == 128)
    f.dispatcher.drain()
    check(calls == 256)
    f.close()
}

private fun runningCancellationLeavesTerminalToBody() {
    val f = Fixture()
    val waiting = CompletableDeferred<Unit>()
    var bodyTerminals = 0
    f.tracker.submit(f.callback, 1, f.scope) {
        try { waiting.await() } catch (cancelled: CancellationException) { bodyTerminals++; throw cancelled }
    }
    f.dispatcher.drain()
    check(f.tracker.activeRequestCount == 1)
    f.tracker.cancel(f.callback, 1)
    f.dispatcher.drain()
    check(bodyTerminals == 1 && f.callback.errors.isEmpty())
    f.close()
}

private fun deathBeforeStart() {
    val f = Fixture(1)
    f.tracker.submit(f.callback, 1, f.scope) { error("Dead callback request ran") }
    f.callback.recipients.single().binderDied()
    f.dispatcher.drain()
    f.callback.assertError(1, "REQUEST_CANCELLED")
    check(f.callback.recipients.isEmpty())
    f.close()
}

private fun linkFailureReleasesPermit() {
    val f = Fixture(1)
    val dead = Callback(linkFails = true)
    f.tracker.submit(dead, 1, f.scope) { error("Dead callback request ran") }
    f.dispatcher.drain()
    dead.assertError(1, "REQUEST_CANCELLED")
    var calls = 0
    f.tracker.submit(f.callback, 2, f.scope) { calls++ }
    f.dispatcher.drain()
    check(calls == 1)
    f.close()
}

private fun staleDeathRecipientDoesNotCancelReusedKey() {
    val f = Fixture(1)
    f.tracker.submit(f.callback, 1, f.scope) {}
    val oldRecipient = f.callback.recipients.single()
    f.dispatcher.drain()
    var calls = 0
    f.tracker.submit(f.callback, 1, f.scope) { calls++ }
    oldRecipient.binderDied()
    f.dispatcher.drain()
    check(calls == 1 && f.callback.errors.isEmpty()) { "Old death callback cancelled the replacement job" }
    f.close()
}

private fun nullCallbackIsIgnored() {
    val f = Fixture()
    f.tracker.submit(null, 1, f.scope) { error("Null callback admitted") }
    f.tracker.cancel(null, 1)
    f.tracker.reject(null, 1, "REJECTED")
    f.dispatcher.drain()
    check(f.tracker.activeRequestCount == 0)
    f.close()
}

private fun concurrentSubmitCancelAndDispose() {
    val executor = Executors.newFixedThreadPool(4)
    val dispatcher = executor.asCoroutineDispatcher()
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val tracker = IpcRequestTracker()
    val callback = Callback()
    val submissions = Executors.newFixedThreadPool(8)
    val requestCount = 1600
    val attempts = AtomicInteger()
    repeat(8) { worker ->
        submissions.submit {
            Binder.setCallingUid(1000)
            repeat(200) { index ->
                val id = (worker * 200 + index).toLong()
                tracker.submit(callback, id, scope) {
                    try { yield(); callback.terminal(id) }
                    catch (cancelled: CancellationException) { callback.terminal(id); throw cancelled }
                }
                if (index % 2 == 0) tracker.cancel(callback, id)
                if (attempts.incrementAndGet() == requestCount / 4) tracker.dispose()
            }
        }
    }
    submissions.shutdown()
    check(submissions.awaitTermination(20, TimeUnit.SECONDS)) { "Submitters did not finish" }
    tracker.dispose()
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
    while ((tracker.activeRequestCount != 0 || callback.terminals.size != requestCount) && System.nanoTime() < deadline) {
        Thread.sleep(1)
    }
    check(tracker.activeRequestCount == 0)
    check(callback.terminals.size == requestCount) { "Missing terminal: ${callback.terminals.size}/$requestCount" }
    check(callback.terminals.values.all { it.get() == 1 }) { "Multiple terminal callbacks" }
    check(callback.recipients.isEmpty()) { "Death recipients leaked" }
    scope.cancel()
    dispatcher.close()
    check(executor.awaitTermination(20, TimeUnit.SECONDS))
}

fun main(args: Array<String>) {
    val cases = linkedMapOf<String, () -> Unit>(
        "normal_completion_permit_reuse" to ::normalCompletionAndPermitReuse,
        "cancel_before_start" to ::cancellationBeforeStart,
        "duplicate_retains_original_permit" to ::duplicateRetainsOriginalAndPermit,
        "cancel_uid_isolation" to ::cancellationRequiresOriginalUid,
        "cancel_callback_isolation" to ::cancellationRequiresOriginalCallback,
        "cancelled_scope_rejection" to ::cancelledScopeBeforeAdmission,
        "scope_cancel_before_start" to ::scopeCancellationBeforeStart,
        "dispose_idempotence_rejection" to ::disposeBeforeStartAndRejectAfterward,
        "128_request_limit" to ::retains128RequestLimit,
        "running_cancel_terminal_owner" to ::runningCancellationLeavesTerminalToBody,
        "callback_death_before_start" to ::deathBeforeStart,
        "link_failure_permit_reuse" to ::linkFailureReleasesPermit,
        "null_callback" to ::nullCallbackIsIgnored,
        "concurrent_submit_cancel_dispose_1600" to ::concurrentSubmitCancelAndDispose,
    )
    if ("--skip-stale-death-regression" !in args) {
        cases["stale_death_recipient_reused_key"] = ::staleDeathRecipientDoesNotCancelReusedKey
    }
    cases.forEach { (name, test) -> test(); println("PASS $name") }
    println("PASS ${cases.size} host tracker cases; Android Binder and device performance were not tested")
}
