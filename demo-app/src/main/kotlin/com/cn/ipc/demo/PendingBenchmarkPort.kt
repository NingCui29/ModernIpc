package com.cn.ipc.demo

import android.os.Parcel
import com.cn.ipc.client.PendingCallRegistry

/** Both lanes use the same adapter surface; the legacy implementation is a frozen source copy. */
internal interface PendingBenchmarkPort {
    val activeCallCount: Int
    suspend fun <T> call(
        generation: Long = 1,
        deserializer: ((Parcel) -> Any?)? = null,
        onRemoteCancel: ((Long) -> Unit)? = null,
        block: (Long) -> Unit
    ): T
    fun complete(requestId: Long, value: Any?): Boolean
    fun completeWithParcel(requestId: Long, data: Parcel): Boolean
    fun fail(requestId: Long, error: Throwable): Boolean
    fun failGeneration(generation: Long, error: Throwable)
}

internal enum class PendingBenchmarkMode(val label: String) {
    LEGACY("legacy"), OPTIMIZED("optimized");

    fun create(timeoutMs: Long = 30_000): PendingBenchmarkPort = when (this) {
        LEGACY -> LegacyPort(timeoutMs)
        OPTIMIZED -> ProductionPort(timeoutMs)
    }
}

private class LegacyPort(timeoutMs: Long) : PendingBenchmarkPort {
    private val registry = LegacyPendingCallRegistry(timeoutMs)
    override val activeCallCount: Int get() = registry.activeCallCount
    override suspend fun <T> call(generation: Long, deserializer: ((Parcel) -> Any?)?,
        onRemoteCancel: ((Long) -> Unit)?, block: (Long) -> Unit): T =
        registry.callSuspend(9001, 10, generation, deserializer, onRemoteCancel, block)
    override fun complete(requestId: Long, value: Any?): Boolean = registry.complete(requestId, value)
    override fun completeWithParcel(requestId: Long, data: Parcel): Boolean = registry.completeWithParcel(requestId, data)
    override fun fail(requestId: Long, error: Throwable): Boolean = registry.fail(requestId, error)
    override fun failGeneration(generation: Long, error: Throwable) = registry.failAllForGeneration(generation, error)
}

private class ProductionPort(timeoutMs: Long) : PendingBenchmarkPort {
    private val registry = PendingCallRegistry(timeoutMs)
    override val activeCallCount: Int get() = registry.activeCallCount
    override suspend fun <T> call(generation: Long, deserializer: ((Parcel) -> Any?)?,
        onRemoteCancel: ((Long) -> Unit)?, block: (Long) -> Unit): T =
        registry.callSuspend(9001, 10, generation, deserializer, onRemoteCancel, block)
    override fun complete(requestId: Long, value: Any?): Boolean = registry.complete(requestId, value)
    override fun completeWithParcel(requestId: Long, data: Parcel): Boolean = registry.completeWithParcel(requestId, data)
    override fun fail(requestId: Long, error: Throwable): Boolean = registry.fail(requestId, error)
    override fun failGeneration(generation: Long, error: Throwable) = registry.failAllForGeneration(generation, error)
}
