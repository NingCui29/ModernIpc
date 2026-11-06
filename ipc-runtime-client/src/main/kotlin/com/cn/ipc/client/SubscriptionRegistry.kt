package com.cn.ipc.client

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 跨进程订阅的本地注册表记录。
 */
data class SubscriptionRecord(
    val subscriptionId: Long,
    val generation: Long,
    val serviceId: Int,
    val operationId: Int, // 用于区分同一服务下的不同流
    val observerBinder: android.os.IBinder
)

/**
 * 管理客户端所有的跨进程流（Flow）订阅状态。
 * 在发生断线重连时，该注册表可用于自动发起重新订阅。
 */
class SubscriptionRegistry {
    
    /** 生成本地伪 subscriptionId 的计数器，在实际发起远程订阅前用于占位和本地解绑。 */
    private val localIdCounter = AtomicLong(1)
    
    /** 映射: 本地/远程 subscriptionId -> 订阅记录。用于快速查找及管理跨进程的生命周期。 */
    private val records = ConcurrentHashMap<Long, SubscriptionRecord>()

    /**
     * 生成一个本地唯一的订阅 ID。
     */
    fun allocateLocalId(): Long {
        // 使用负数或特定高位来区分本地 ID 和服务端返回的真实 ID，这里简单使用负数。
        return -localIdCounter.getAndIncrement()
    }

    /**
     * 注册一个活跃的订阅。
     * 当从服务端成功获取真实的 remoteSubscriptionId 后调用此方法。
     */
    fun register(
        remoteSubscriptionId: Long,
        generation: Long,
        serviceId: Int,
        operationId: Int,
        observerBinder: android.os.IBinder
    ) {
        records[remoteSubscriptionId] = SubscriptionRecord(
            subscriptionId = remoteSubscriptionId,
            generation = generation,
            serviceId = serviceId,
            operationId = operationId,
            observerBinder = observerBinder
        )
    }

    /**
     * 移除订阅记录，通常在客户端主动取消 Flow collection 时发生。
     */
    fun unregister(subscriptionId: Long): SubscriptionRecord? {
        return records.remove(subscriptionId)
    }

    /**
     * 找出所有需要因为连接重置（代次变更）而重新订阅的记录。
     * 这在 IpcConnectionController 成功重连后调用，帮助恢复所有的 active flow。
     */
    fun getRecordsForReconnection(currentGeneration: Long): List<SubscriptionRecord> {
        return records.values.filter { it.generation < currentGeneration }
    }

    /**
     * 清除特定代次的订阅。如果服务端彻底重启导致我们不想自动恢复（或者基于特定策略）。
     */
    fun clearGeneration(generation: Long) {
        val iterator = records.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.generation == generation) {
                records.remove(entry.key, entry.value)
            }
        }
    }
}
