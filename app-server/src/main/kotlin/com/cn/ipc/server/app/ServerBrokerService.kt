package com.cn.ipc.server.app

import android.os.Binder
import android.os.Parcel
import android.os.Process
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

        val stub = object : IMessageHubServiceServerStub(this) {
            override val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (Binder.getCallingUid() != Process.myUid() && code in setOf(1, 2, 3, 10, 20)) {
                    val uid = Binder.getCallingUid()
                    val packages = packageManager.getPackagesForUid(uid)?.toSet().orEmpty()
                    val allowedIds = mapOf(
                        "com.cn.ipc.client1" to "client_1",
                        "com.cn.ipc.client2" to "client_2",
                        "com.cn.ipc.client3" to "client_3"
                    ).filterKeys { it in packages }.values
                    val position = data.dataPosition()
                    val claimedId = try {
                        data.enforceInterface("com.cn.ipc.api.hub.IMessageHubService")
                        if (code == 10) data.readLong()
                        data.readString()
                    } finally {
                        data.setDataPosition(position)
                    }
                    if (claimedId !in allowedIds) {
                        throw SecurityException("UID $uid cannot act as $claimedId")
                    }
                }
                return super.onTransact(code, data, reply, flags)
            }

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
                apiVersion = 2,
                apiHash = "hub_v2",
                requiredCapability = 0L,
                permission = null,
                binder = stub,
                minSupportedClientVersion = 2
            )
        )
        return registry
    }
}
