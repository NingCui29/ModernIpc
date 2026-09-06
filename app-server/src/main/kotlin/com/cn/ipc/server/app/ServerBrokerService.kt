package com.cn.ipc.server.app

import com.cn.ipc.api.hub.IMessageHubServiceServerStub
import com.cn.ipc.server.DefaultIpcServiceRegistry
import com.cn.ipc.server.IpcBrokerService
import com.cn.ipc.server.RegisteredService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * ModernIPC 服务端核心通信宿主 Service。
 * 对外暴露 Binder 接口并挂载 MessageHubService 路由。
 */
class ServerBrokerService : IpcBrokerService() {

    override fun onCreateRegistry(): DefaultIpcServiceRegistry {
        val registry = super.onCreateRegistry()
        val hubImpl = MessageHubServiceImpl.instance

        val stub = object : IMessageHubServiceServerStub() {
            override val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

            override fun registerClient(clientId: String, clientName: String) =
                hubImpl.registerClient(clientId, clientName)

            override fun unregisterClient(clientId: String) =
                hubImpl.unregisterClient(clientId)

            override suspend fun sendMessage(fromClientId: String, targetScope: String, content: String) =
                hubImpl.sendMessage(fromClientId, targetScope, content)

            override suspend fun getOnlineClients() =
                hubImpl.getOnlineClients()

            override suspend fun getServerStatus() =
                hubImpl.getServerStatus()

            override fun observeMessages(clientId: String) =
                hubImpl.observeMessages(clientId)

            override fun ping(clientId: String) =
                hubImpl.ping(clientId)
        }

        registry.register(
            RegisteredService(
                serviceId = 2001,
                apiVersion = 1,
                apiHash = "hub_v1",
                requiredCapability = 0L,
                permission = null,
                binder = stub
            )
        )
        return registry
    }
}
