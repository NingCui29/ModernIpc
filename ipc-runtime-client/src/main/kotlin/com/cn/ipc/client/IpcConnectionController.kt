package com.cn.ipc.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import com.cn.ipc.ClientHello
import com.cn.ipc.IIpcBroker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * 负责 IPC 连接状态机管理、服务绑定与死亡恢复的控制器。
 */
class IpcConnectionController(
    private val context: Context,
    private val targetIntent: Intent,
    private val scope: CoroutineScope,
    private val clientPackage: String = context.packageName,
    private val clientVersionCode: Long = 2L
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<IpcClientState>(IpcClientState.Idle)
    val state: StateFlow<IpcClientState> = _state.asStateFlow()
    
    /** 管理客户端跨进程订阅的注册表 */
    val subscriptionRegistry = SubscriptionRegistry()
    
    /** 管理客户端待处理（挂起）的 IPC 调用的注册表 */
    val pendingCallRegistry = PendingCallRegistry()
    
    /** 
     * 全局响应回调 Binder，负责接收服务端发来的回调数据。
     * 用于处理基于回调模式的异步响应。
     */
    val globalResponseBinder: IBinder = object : android.os.Binder() {
        override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean {
            // 解析来自服务端的响应
            val requestId = data.readLong()
            val isSuccess = data.readInt() == 1
            if (isSuccess) {
                pendingCallRegistry.completeWithParcel(requestId, data)
            } else {
                val errorMsg = try { data.readString() ?: "Remote error" } catch (_: Exception) { "Remote error" }
                pendingCallRegistry.fail(requestId, RuntimeException(errorMsg))
            }
            return true
        }
    }

    /** 
     * 当前连接的生命周期代次（generation）。
     * 每次成功重连后代次递增，用于丢弃过期连接的响应和回调。
     */
    var currentGeneration: Long = 0L
        private set

    /** 当前底层的 ServiceConnection 实例，用于绑定和解绑系统服务 */
    private var serviceConnection: InnerServiceConnection? = null

    /** 业务 Binder 多级缓存，避免高频调用重复发起跨进程 getService 查询 */
    private val serviceBinderCache = java.util.concurrent.ConcurrentHashMap<Int, IBinder>()

    /**
     * 获取指定业务的 Binder (如果已连接，则通过本地缓存或 Broker 获取)。
     * 具备一级内存缓存，大幅度消除跨进程重复查询 Broker 的开销。
     *
     * @param serviceId 目标服务的唯一标识 ID
     * @return 目标服务对应的 Binder 代理对象
     * @throws IllegalStateException 如果尚未建立 IPC 连接
     */
    fun getServiceBinder(serviceId: Int): IBinder {
        val state = _state.value
        if (state is IpcClientState.Connected) {
            return serviceBinderCache.computeIfAbsent(serviceId) {
                state.broker.getService(serviceId, 1) // 假定最低 apiVersion = 1
            }
        }
        throw IllegalStateException("IPC not connected")
    }

    /**
     * 挂起等待 IPC 连接建立成功并返回连接状态，平滑解决冷启动与重连期间的时序竞争。
     *
     * @param timeoutMs 最长等待超时时间（毫秒）
     * @return 成功连接后的状态实例
     */
    suspend fun awaitConnected(timeoutMs: Long = 5000L): IpcClientState.Connected {
        val current = _state.value
        if (current is IpcClientState.Connected) return current

        connect()
        return kotlinx.coroutines.withTimeout(timeoutMs) {
            _state.first { it is IpcClientState.Connected } as IpcClientState.Connected
        }
    }

    /**
     * 发起连接。如果是第一次或已断开则开始连接；如果正在连接或已连接则直接返回当前状态。
     */
    fun connect() {
        scope.launch {
            mutex.withLock {
                when (val currentState = _state.value) {
                    is IpcClientState.Idle, is IpcClientState.Disconnected -> {
                        doBindServiceLocked()
                    }
                    is IpcClientState.Reconnecting -> {
                        // 强制立即重试
                        doBindServiceLocked()
                    }
                    is IpcClientState.Binding, is IpcClientState.Connected, is IpcClientState.Closed -> {
                        // 状态有效，不需要重复绑定
                    }
                }
            }
        }
    }

    /**
     * 断开连接并关闭状态机，不再自动重连。
     */
    fun close() {
        scope.launch {
            mutex.withLock {
                if (_state.value is IpcClientState.Closed) return@withLock
                
                doUnbindServiceLocked()
                _state.value = IpcClientState.Closed
            }
        }
    }

    /**
     * 在持锁状态下执行底层服务绑定逻辑。
     * 会更新内部状态为 Binding 并向系统发起 bindService 请求。
     */
    private fun doBindServiceLocked() {
        _state.value = IpcClientState.Binding
        
        val connection = InnerServiceConnection()
        serviceConnection = connection
        
        val bound = try {
            context.bindService(targetIntent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            false
        }

        if (!bound) {
            handleBindFailureLocked()
        }
    }

    /**
     * 在持锁状态下执行底层服务解绑逻辑。
     * 安全地解除当前的 ServiceConnection 绑定。
     */
    private fun doUnbindServiceLocked() {
        serviceBinderCache.clear()
        serviceConnection?.let {
            try {
                context.unbindService(it)
            } catch (ignored: IllegalArgumentException) {
            }
            serviceConnection = null
        }
    }

    /**
     * 在持锁状态下处理绑定失败的逻辑。
     * 将状态置为 Disconnected。
     */
    private fun handleBindFailureLocked() {
        doUnbindServiceLocked()
        _state.value = IpcClientState.Disconnected
        // TODO: 可以在这里加入自动重试策略
    }

    /**
     * 内部的服务连接回调实现，用于监听底层 ServiceConnection 状态。
     */
    private inner class InnerServiceConnection : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            scope.launch {
                mutex.withLock {
                    if (serviceConnection != this@InnerServiceConnection) {
                        return@withLock // 已经是过期的连接
                    }
                    
                    if (service == null) {
                        handleBindFailureLocked()
                        return@withLock
                    }

                    try {
                        val broker = IIpcBroker.Stub.asInterface(service)
                        
                        // 发起握手协商
                        val clientHello = ClientHello(
                            protocolMajor = 1,
                            protocolMinor = 0,
                            clientPackage = clientPackage,
                            clientVersionCode = clientVersionCode,
                            requestedCapabilities = 0L,
                            nonce = System.currentTimeMillis()
                        )
                        
                        val protocolInfo = broker.handshake(clientHello)
                        
                        // 握手成功，重置重连计数并推进状态
                        resetReconnectAttempts()
                        currentGeneration++
                        
                        // 注册死亡监听
                        val deathRecipient = IBinder.DeathRecipient {
                            handleBinderDied(this@InnerServiceConnection, currentGeneration)
                        }
                        service.linkToDeath(deathRecipient, 0)
                        
                        _state.value = IpcClientState.Connected(
                            generation = currentGeneration,
                            broker = broker,
                            protocol = protocolInfo
                        )
                    } catch (e: RemoteException) {
                        // 握手失败
                        handleBindFailureLocked()
                    } catch (e: Exception) {
                        // 其他异常
                        handleBindFailureLocked()
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // Android 系统通知服务断开，通常随后会自动重连
            // 依赖 Binder.DeathRecipient 来做精确的代次清理
        }
    }

    /**
     * 处理服务端 Binder 死亡的事件。
     * 当收到死亡通知时，会断开现有连接，并将状态推进至 Reconnecting 以触发自动重连机制。
     *
     * @param connection 发生死亡事件的底层连接实例
     * @param generation 发生死亡事件的代次，用于避免处理已过期的回调
     */
    private fun handleBinderDied(connection: InnerServiceConnection, generation: Long) {
        scope.launch {
            mutex.withLock {
                if (serviceConnection != connection) {
                    return@withLock
                }
                val currentState = _state.value
                if (currentState is IpcClientState.Connected && currentState.generation == generation) {
                    doUnbindServiceLocked()
                    _state.value = IpcClientState.Reconnecting(attempt = 1)
                    
                    // 立即让当前代次的所有在途挂起请求快速失败，杜绝假死卡顿
                    pendingCallRegistry.failAllForGeneration(
                        generation,
                        android.os.DeadObjectException("Server process died for generation $generation")
                    )

                    // 执行有上限的指数退避重连策略
                    scheduleReconnect()
                }
            }
        }
    }

    /**
     * 当前的重连次数，用于计算指数退避的延迟时间。
     */
    private var reconnectAttempts = 0

    /**
     * 调度下一次重连尝试。
     * 使用指数退避 (Exponential Backoff) 和随机抖动 (Jitter) 策略，
     * 避免服务端恢复后被瞬间的重连风暴压垮。
     */
    private fun scheduleReconnect() {
        scope.launch {
            // 计算基础延迟: 500ms * 2^attempts，最大限制在 30 秒
            val baseDelay = (500L * (1 shl reconnectAttempts.coerceAtMost(6))).coerceAtMost(30000L)
            
            // 添加 10% 的随机抖动
            val jitter = (Math.random() * 0.1 * baseDelay).toLong()
            val finalDelay = baseDelay + jitter
            
            reconnectAttempts++
            
            delay(finalDelay)
            
            mutex.withLock {
                if (_state.value is IpcClientState.Reconnecting) {
                    doBindServiceLocked()
                }
            }
        }
    }
    
    /**
     * 当连接成功时，重置重连计数器。
     */
    private fun resetReconnectAttempts() {
        reconnectAttempts = 0
    }
}
