package com.cn.ipc.client

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resumeWithException

private const val CALL_DISPATCHED = 1
private const val CALL_ABORTED = 2
private const val CALL_CANCEL_SENT = 4

/**
 * 等待中的调用记录。
 */
private data class PendingCall(
    val generation: Long,
    val continuation: CancellableContinuation<Any?>,
    val deserializer: ((android.os.Parcel) -> Any?)? = null,
    val onConnectionClosed: (() -> Unit)? = null
)

/**
 * 用于管理挂起函数的挂起状态。
 * 在发生回调、超时或连接断开时，确保只触发一次 continuation 恢复。
 */
class PendingCallRegistry(private val defaultCallTimeoutMs: Long = 30_000L) {
    init { require(defaultCallTimeoutMs > 0) }
    /** 存储正在挂起的请求，键为唯一的 requestId，值为 PendingCall 记录 */
    private val pendingMap = ConcurrentHashMap<Long, PendingCall>()
    val activeCallCount: Int get() = pendingMap.size
    internal fun generationOf(requestId: Long): Long? = pendingMap[requestId]?.generation
    
    /** 用于生成全局唯一的自增 requestId */
    private val requestCounter = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * 发起一个挂起请求。
     */
    suspend fun <T> callSuspend(
        serviceId: Int,
        operationId: Int,
        generation: Long = 1L,
        deserializer: ((android.os.Parcel) -> Any?)? = null,
        onRemoteCancel: ((Long) -> Unit)? = null,
        block: (Long) -> Unit
    ): T = withTimeout(defaultCallTimeoutMs) {
        callSuspendWithinDeadline(serviceId, operationId, generation, deserializer, onRemoteCancel, block)
    }

    /** Generated callers provide a single deadline covering discovery and this registered wait. */
    suspend fun <T> callSuspendWithinDeadline(
        serviceId: Int,
        operationId: Int,
        generation: Long = 1L,
        deserializer: ((android.os.Parcel) -> Any?)? = null,
        onRemoteCancel: ((Long) -> Unit)? = null,
        block: (Long) -> Unit
    ): T = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val requestId = requestCounter.incrementAndGet()
        // One state allocation per request; bits are monotonic across callback/dispatch races.
        val callState = AtomicInteger(0)
        fun mark(flag: Int): Int {
            while (true) {
                val previous = callState.get()
                val updated = previous or flag
                if (previous == updated || callState.compareAndSet(previous, updated)) return updated
            }
        }
        fun cancelRemoteIfDispatched() {
            while (true) {
                val previous = callState.get()
                if ((previous and (CALL_DISPATCHED or CALL_ABORTED)) != (CALL_DISPATCHED or CALL_ABORTED) ||
                    (previous and CALL_CANCEL_SENT) != 0) return
                if (callState.compareAndSet(previous, previous or CALL_CANCEL_SENT)) {
                    try { onRemoteCancel?.invoke(requestId) } catch (_: Exception) {}
                    return
                }
            }
        }
        register(requestId, generation, cont, deserializer) {
            mark(CALL_ABORTED)
            cancelRemoteIfDispatched()
        }
        cont.invokeOnCancellation {
            mark(CALL_ABORTED)
            cancel(requestId)
            cancelRemoteIfDispatched()
        }
        try {
            if (cont.isActive) {
                block(requestId)
                val dispatchedState = mark(CALL_DISPATCHED)
                if ((dispatchedState and CALL_ABORTED) != 0) cancelRemoteIfDispatched()
            }
        } catch (e: Exception) {
            if (cancel(requestId) && cont.isActive) cont.resumeWithException(e)
        }
    }

    /**
     * 发起一个挂起请求 (兼容旧版无 generation 的调用)。
     */
    suspend fun <T> callSuspend(
        serviceId: Int,
        operationId: Int,
        deserializer: ((android.os.Parcel) -> Any?)? = null,
        block: (Long) -> Unit
    ): T = callSuspend(serviceId, operationId, 1L, deserializer, null, block)

    /**
     * 发起一个挂起请求 (兼容旧版无 deserializer 的调用)。
     */
    suspend fun <T> callSuspend(
        serviceId: Int,
        operationId: Int,
        block: (Long) -> Unit
    ): T = callSuspend(serviceId, operationId, 1L, null, null, block)

    /**
     * 注册一个新的挂起调用。
     *
     * @param requestId    请求唯一 ID
     * @param generation   当前连接代次
     * @param continuation 协程的 Continuation
     * @param deserializer 反序列化器
     */
    fun register(
        requestId: Long,
        generation: Long,
        continuation: CancellableContinuation<*>,
        deserializer: ((android.os.Parcel) -> Any?)? = null,
        onConnectionClosed: (() -> Unit)? = null
    ) {
        @Suppress("UNCHECKED_CAST")
        pendingMap[requestId] = PendingCall(generation, continuation as CancellableContinuation<Any?>, deserializer, onConnectionClosed)
    }

    /**
     * 成功完成调用并反序列化结果。
     */
    fun completeWithParcel(requestId: Long, data: android.os.Parcel): Boolean {
        val pending = pendingMap.remove(requestId) ?: return false
        return if (pending.continuation.isActive) {
            val result = try {
                pending.deserializer?.invoke(data) ?: (data.readString() ?: "")
            } catch (e: Throwable) {
                pending.continuation.resumeWithException(e)
                return true
            }
            pending.continuation.resumeWith(Result.success(result))
            true
        } else {
            false
        }
    }

    /**
     * 成功完成调用。
     * 返回 true 表示成功原子性移除并处理，false 表示记录不存在（可能已超时或断线）。
     */
    fun complete(requestId: Long, result: Any?): Boolean {
        val pending = pendingMap.remove(requestId) ?: return false
        return if (pending.continuation.isActive) {
            pending.continuation.resumeWith(Result.success(result))
            true
        } else {
            false
        }
    }

    /**
     * 调用失败（如抛出业务异常或传输异常）。
     */
    fun fail(requestId: Long, error: Throwable): Boolean {
        val pending = pendingMap.remove(requestId) ?: return false
        return if (pending.continuation.isActive) {
            pending.continuation.resumeWithException(error)
            true
        } else {
            false
        }
    }

    /**
     * 取消调用（通常由于客户端协程被主动取消）。
     * 返回 true 表示确实取消了待处理请求，调用方应该尝试向远端发送 cancel。
     */
    fun cancel(requestId: Long): Boolean {
        return pendingMap.remove(requestId) != null
    }

    /**
     * 当 Binder 死亡或连接意外断开时，批量让当前代次的所有请求失败。
     * 避免阻塞处于挂起状态的协程。
     */
    fun failAllForGeneration(generation: Long, error: Throwable) {
        val iterator = pendingMap.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.generation == generation && pendingMap.remove(entry.key, entry.value)) {
                try { entry.value.onConnectionClosed?.invoke() } catch (_: Exception) {}
                if (entry.value.continuation.isActive) {
                    entry.value.continuation.resumeWithException(error)
                }
            }
        }
    }

    /** Release every generation when the connection owner closes or disposes. */
    fun failAll(error: Throwable) {
        pendingMap.entries.forEach { entry ->
            if (pendingMap.remove(entry.key, entry.value)) {
                try { entry.value.onConnectionClosed?.invoke() } catch (_: Exception) {}
                if (entry.value.continuation.isActive) entry.value.continuation.resumeWithException(error)
            }
        }
    }
}
