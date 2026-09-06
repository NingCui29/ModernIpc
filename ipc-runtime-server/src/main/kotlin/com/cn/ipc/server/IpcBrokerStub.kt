package com.cn.ipc.server

import android.os.IBinder
import android.os.RemoteException
import com.cn.ipc.ClientHello
import com.cn.ipc.IIpcBroker
import com.cn.ipc.ProtocolInfo
import java.util.concurrent.atomic.AtomicLong

/**
 * 核心 Broker 的服务端实现。
 * 负责处理客户端的握手请求，并分发业务 Binder。
 */
class IpcBrokerStub(
    private val context: android.content.Context,
    private val registry: DefaultIpcServiceRegistry,
    private val serverVersionCode: Long = 2L
) : IIpcBroker.Stub() {

    private val sessionCounter = AtomicLong(1)

    // 协议的主次版本号
    private val PROTOCOL_MAJOR = 1
    private val PROTOCOL_MINOR = 0

    /**
     * 处理客户端的握手请求，进行协议版本校验和能力协商。
     *
     * @param client 客户端发送的握手信息 [ClientHello]
     * @return 包含服务端协议信息、版本和支持能力的 [ProtocolInfo]
     * @throws RemoteException 当 ClientHello 为 null 或协议主版本不兼容时抛出
     */
    override fun handshake(client: ClientHello?): ProtocolInfo {
        if (client == null) {
            throw RemoteException("ClientHello cannot be null")
        }

        // 1. 验证主版本号兼容性。如果不匹配，则直接拒绝连接
        if (client.protocolMajor != PROTOCOL_MAJOR) {
            throw RemoteException("Protocol incompatible. Server expects major $PROTOCOL_MAJOR, got ${client.protocolMajor}")
        }

        // 2. (MVP 阶段) 简化的能力协商，默认全部支持客户端所请求的能力
        val supportedCapabilities = client.requestedCapabilities

        // 3. 生成全局唯一的会话 ID
        val sessionId = sessionCounter.getAndIncrement()

        // 4. 返回协商结果给客户端
        return ProtocolInfo(
            protocolMajor = PROTOCOL_MAJOR,
            protocolMinor = PROTOCOL_MINOR,
            serverVersionCode = serverVersionCode,
            supportedCapabilities = supportedCapabilities,
            maxInlinePayloadBytes = 512 * 1024, // 默认 512KB
            serviceVersions = registry.getServiceVersions(),
            sessionId = sessionId
        )
    }

    /**
     * 获取指定 serviceId 对应的业务服务 Binder。
     * 获取之前会先进行严谨的安全鉴权与版本匹配检查。
     *
     * @param serviceId 目标业务服务的唯一标识 ID
     * @param minApiVersion 客户端要求的该服务的最低 API 版本号
     * @return 目标服务的 [IBinder] 对象
     * @throws RemoteException 当鉴权失败、找不到服务或服务版本过低时抛出
     */
    override fun getService(serviceId: Int, minApiVersion: Int): IBinder {
        // M3 阶段: 严谨的鉴权机制集成
        val authenticator = CallerAuthenticator(context)
        // 获取调用方身份信息
        val caller = authenticator.authenticate()
        // 校验调用方是否有权限访问指定的 serviceId
        authenticator.authorize(caller, serviceId)
        
        // 从注册表中查找对应的业务服务
        val service = registry.get(serviceId)
            ?: throw RemoteException("ServiceId $serviceId not found")

        // 检查服务端提供的版本是否满足客户端要求的最低 API 版本
        if (service.apiVersion < minApiVersion) {
            throw RemoteException("Service version too low. Requested $minApiVersion, available ${service.apiVersion}")
        }

        return service.binder
    }
}
