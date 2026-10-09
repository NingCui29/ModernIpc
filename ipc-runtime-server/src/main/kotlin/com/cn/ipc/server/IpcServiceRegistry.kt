package com.cn.ipc.server

import android.os.IBinder

/** Resources owned by a registered service Stub. Counts are concurrent snapshots, not joins. */
interface IpcServiceLifecycle {
    val activeRequestCount: Int
    val activeSubscriptionCount: Int
    fun dispose()
}

/** Generated wire metadata; hashes of handwritten labels are not a compatibility proof. */
interface IpcServiceSchemaProvider {
    val ipcDescriptor: String
    val ipcMethodSignatures: Map<Int, String>
}

/**
 * 业务服务在注册表中的元数据。
 */
data class RegisteredService(
    val serviceId: Int,
    val apiVersion: Int,
    val apiHash: String,
    val requiredCapability: Long = 0L,
    val permission: String? = null,
    val binder: IBinder,
    val minSupportedClientVersion: Int = 1
)

/**
 * IPC 服务端注册表，负责存储和查找可用的业务服务。
 * 生产环境中，该注册表应由 KSP 自动生成以保证与配置一致。
 */
interface IpcServiceRegistry {
    /**
     * 根据 serviceId 获取已注册的服务信息。
     */
    fun get(serviceId: Int): RegisteredService?
}

/**
 * 一个简单的内存注册表实现，用于 M1 阶段。
 * 提供注册、获取服务及查询版本的功能。
 */
class DefaultIpcServiceRegistry : IpcServiceRegistry {
    // 使用 Map 存储已注册的服务，Key 为 serviceId
    private val services = mutableMapOf<Int, RegisteredService>()

    /**
     * 将一个新的服务注册到注册表中。
     *
     * @param service 待注册的 [RegisteredService] 对象
     * @throws IllegalArgumentException 如果对应的 serviceId 已经被注册过，则抛出异常
     */
    fun register(service: RegisteredService) {
        require(service.apiVersion >= 1 && service.minSupportedClientVersion in 1..service.apiVersion)
        require(!services.containsKey(service.serviceId)) {
            "ServiceId ${service.serviceId} is already registered."
        }
        services[service.serviceId] = service
    }

    /**
     * 根据指定的 serviceId 查找并返回已注册的服务。
     *
     * @param serviceId 需要查询的服务标识
     * @return 返回对应的 [RegisteredService] 实例，如果未找到则返回 null
     */
    override fun get(serviceId: Int): RegisteredService? {
        return services[serviceId]
    }
    
    /**
     * 获取所有已注册服务的版本信息，主要用于组装握手时的 [ProtocolInfo]。
     *
     * @return 返回一个以 serviceId 为键，以 apiVersion 为值的 Map
     */
    fun getServiceVersions(): Map<Int, Int> {
        return services.mapValues { it.value.apiVersion }
    }

    /** Release every registered lifecycle owner even if another owner fails to dispose. */
    fun dispose() {
        val owners = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<IpcServiceLifecycle, Boolean>()
        )
        services.values.forEach { service ->
            (service.binder as? IpcServiceLifecycle)?.let { owners.add(it) }
        }
        var failure: Throwable? = null
        owners.forEach { owner ->
            try {
                owner.dispose()
            } catch (error: Throwable) {
                val previous = failure
                if (previous == null) failure = error else if (previous !== error) previous.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
}
