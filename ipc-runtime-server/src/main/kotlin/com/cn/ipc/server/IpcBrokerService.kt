package com.cn.ipc.server

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Modern IPC 的宿主 Service。
 * 所有客户端都通过 bindService 连接到此 Service 从而获得 Broker 的 Binder。
 */
open class IpcBrokerService : Service() {

    private lateinit var registry: DefaultIpcServiceRegistry
    private lateinit var brokerStub: IpcBrokerStub
    
    // TODO: 在子类中重写此方法以注册真实的服务
    /**
     * 创建服务注册表。子类可以重写此方法，以提供包含了自定义业务服务的注册表。
     *
     * @return 默认的 IPC 服务注册表实例 [DefaultIpcServiceRegistry]
     */
    protected open fun onCreateRegistry(): DefaultIpcServiceRegistry {
        return DefaultIpcServiceRegistry()
    }

    /**
     * 服务创建时调用，初始化服务注册表和 Broker 存根（Stub）。
     */
    override fun onCreate() {
        super.onCreate()
        registry = onCreateRegistry()
        brokerStub = IpcBrokerStub(this, registry, serverVersionCode = 2L)
    }

    /**
     * 当客户端通过 bindService 绑定此服务时调用。
     * 
     * @param intent 客户端发起的绑定请求的 Intent
     * @return 返回核心 Broker 的 Binder (IpcBrokerStub) 供客户端通信
     */
    override fun onBind(intent: Intent?): IBinder? {
        // 返回 Broker Stub，客户端借此进行协议握手及服务获取
        return brokerStub
    }
}
