package com.cn.ipc.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ensureActive
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Shared, detached Binder workers. Cancelling a waiter never waits for or interrupts its RPC. */
internal object HandshakeExecutor {
    private val workers = ThreadPoolExecutor(
        0, 4, 30, TimeUnit.SECONDS, SynchronousQueue<Runnable>(),
        { task -> Thread(task, "IpcHandshake").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )

    suspend fun <T> await(block: () -> T): T {
        kotlin.coroutines.coroutineContext.ensureActive()
        val result = CompletableDeferred<T>()
        try {
            workers.execute {
                // Cancellation can win before execution; an RPC already started cannot be interrupted.
                if (!result.isActive) return@execute
                try {
                    result.complete(block())
                } catch (error: Throwable) {
                    result.completeExceptionally(error)
                }
            }
            return result.await()
        } finally {
            // The deferred has no parent: drop late results without tying cleanup to worker completion.
            result.cancel()
        }
    }
}
