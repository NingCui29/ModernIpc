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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/**
 * 负责 IPC 连接状态机管理、服务绑定与死亡恢复的控制器。
 */
class IpcConnectionController(
    private val context: Context,
    private val targetIntent: Intent,
    private val scope: CoroutineScope,
    private val clientPackage: String = context.packageName,
    private val clientVersionCode: Long = 2L,
    private val maxReconnectAttempts: Int = 10
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
    private val serviceBinderCache = ConcurrentHashMap<Int, IBinder>()

    /** 后台退避重连协程 Job，确保生命周期唯一并支持即时取消 */
    private var reconnectJob: Job? = null

    /** 当前的重连尝试计数 */
    private var reconnectAttempts = 0

    /**
     * 安全获取指定业务的 Binder，若未连接或 Binder 死亡则返回 null。
     * 具备一级内存缓存与存活校验，消除重复跨进程获取开销同时提供失效自动刷新能力。
     *
     * @param serviceId 目标服务的唯一标识 ID
     * @return 目标服务对应的 Binder 代理对象，连接不可用时返回 null
     */
    fun getServiceBinderOrNull(serviceId: Int): IBinder? {
        val state = _state.value
        if (state is IpcClientState.Connected) {
            val cached = serviceBinderCache[serviceId]
            if (cached != null && cached.isBinderAlive) {
                return cached
            }
            val res = try {
                serviceBinderCache.compute(serviceId) { _, existing ->
                    if (existing != null && existing.isBinderAlive) {
                        existing
                    } else {
                        state.broker.getService(serviceId, 1) // 假定最低 apiVersion = 1
                    }
                }
            } catch (_: Exception) {
                null
            }
            return res
        }
        return null
    }

    /**
     * 获取指定业务的 Binder (如果已连接，则通过本地缓存或 Broker 获取)。
     *
     * @param serviceId 目标服务的唯一标识 ID
     * @return 目标服务对应的 Binder 代理对象
     * @throws IllegalStateException 如果尚未建立 IPC 连接或 Binder 不可用
     */
    fun getServiceBinder(serviceId: Int): IBinder {
        return getServiceBinderOrNull(serviceId)
            ?: throw IllegalStateException("IPC not connected (current state: ${_state.value})")
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
        return withTimeout(timeoutMs) {
            _state.first { it is IpcClientState.Connected } as IpcClientState.Connected
        }
    }

    /**
     * 发起连接。
     * 支持从 Idle、Disconnected 以及 Closed 状态无缝重新发起绑定；
     * 若处于 Reconnecting 状态，则打断退避等待立即发起绑定重试。
     */
    fun connect() {
        scope.launch {
            mutex.withLock {
                cancelReconnectJobLocked()
                when (_state.value) {
                    is IpcClientState.Idle, is IpcClientState.Disconnected, is IpcClientState.Closed -> {
                        resetReconnectAttempts()
                        doBindServiceLocked()
                    }
                    is IpcClientState.Reconnecting -> {
                        // 用户/业务主动触发立即重连，绕过延迟
                        doBindServiceLocked()
                    }
                    is IpcClientState.Binding, is IpcClientState.Connected -> {
                        // 状态处于进行中或已建立，无需重复发起绑定
                    }
                }
            }
        }
    }

    /**
     * 断开连接并关闭状态机，清空缓存并让所有挂起在途调用快速失败。
     * 状态转换为 Closed 后，后续仍可通过调用 connect() 重新建立连接。
     */
    fun close() {
        scope.launch {
            mutex.withLock {
                if (_state.value is IpcClientState.Closed) return@withLock
                
                cancelReconnectJobLocked()
                resetReconnectAttempts()
                doUnbindServiceLocked()
                
                // 立即让当前代次在途请求快速失败，避免调用端协程永久死锁挂起
                pendingCallRegistry.failAllForGeneration(
                    currentGeneration,
                    IllegalStateException("IPC connection explicitly closed")
                )

                _state.value = IpcClientState.Closed
            }
        }
    }

    /**
     * 在持锁状态下执行底层服务绑定逻辑。
     * 会更新内部状态为 Binding 并向系统发起 bindService 请求。
     */
    private fun doBindServiceLocked() {
        doUnbindServiceLocked()
        _state.value = IpcClientState.Binding
        
        val connection = InnerServiceConnection()
        serviceConnection = connection
        
        val bound = try {
            context.bindService(targetIntent, connection, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
        } catch (e: Exception) {
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
            } catch (_: IllegalArgumentException) {
            }
            serviceConnection = null
        }
    }

    /**
     * 在持锁状态下处理绑定失败的逻辑。
     * 具备指数退避重试能力，只有达到重试上限才进入 Disconnected。
     */
    private fun handleBindFailureLocked() {
        doUnbindServiceLocked()
        
        // 快速失败在途请求
        pendingCallRegistry.failAllForGeneration(
            currentGeneration,
            RemoteException("IPC bind failed or lost for generation $currentGeneration")
        )

        if (reconnectAttempts < maxReconnectAttempts) {
            reconnectAttempts++
            _state.value = IpcClientState.Reconnecting(attempt = reconnectAttempts)
            scheduleReconnectLocked()
        } else {
            _state.value = IpcClientState.Disconnected
        }
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
                        
                        // 握手成功，取消重连任务、重置计数并推进代次
                        cancelReconnectJobLocked()
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
                    } catch (_: Exception) {
                        // 握手或绑定异常，触发退避重连
                        handleBindFailureLocked()
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            scope.launch {
                mutex.withLock {
                    if (serviceConnection != this@InnerServiceConnection) {
                        return@withLock
                    }
                    serviceBinderCache.clear()
                    val currentState = _state.value
                    if (currentState is IpcClientState.Connected) {
                        pendingCallRegistry.failAllForGeneration(
                            currentGeneration,
                            RemoteException("Service disconnected by system for generation $currentGeneration")
                        )
                        reconnectAttempts = 1
                        _state.value = IpcClientState.Reconnecting(attempt = reconnectAttempts)
                        scheduleReconnectLocked()
                    }
                }
            }
        }
    }

    /**
     * 处理服务端 Binder 死亡的事件。
     * 当收到死亡通知时，会断开现有连接，并将状态推进至 Reconnecting 以触发自动退避重连机制。
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
                    
                    // 立即让当前代次的所有在途挂起请求快速失败，杜绝假死卡顿
                    pendingCallRegistry.failAllForGeneration(
                        generation,
                        android.os.DeadObjectException("Server process died for generation $generation")
                    )

                    reconnectAttempts = 1
                    _state.value = IpcClientState.Reconnecting(attempt = reconnectAttempts)
                    scheduleReconnectLocked()
                }
            }
        }
    }

    /**
     * 取消后台正在等待的退避重连任务，避免协程泄漏与并发竞争。
     */
    private fun cancelReconnectJobLocked() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    /**
     * 调度下一次重连尝试。
     * 使用指数退避 (Exponential Backoff) 和随机抖动 (Jitter) 策略，
     * 避免服务端恢复后被瞬间的重连风暴压垮。
     */
    private fun scheduleReconnectLocked() {
        cancelReconnectJobLocked()
        reconnectJob = scope.launch {
            // 指数退避: 500ms * 2^(attempts-1)，最大限制在 30 秒
            val shift = (reconnectAttempts - 1).coerceIn(0, 6)
            val baseDelay = (500L * (1 shl shift)).coerceAtMost(30000L)
            
            // 添加 10% 的随机抖动
            val jitter = (Math.random() * 0.1 * baseDelay).toLong()
            val finalDelay = baseDelay + jitter
            
            delay(finalDelay)
            
            mutex.withLock {
                if (_state.value is IpcClientState.Reconnecting) {
                    doBindServiceLocked()
                }
            }
        }
    }
    
    /**
     * 重置重连计数器。
     */
    private fun resetReconnectAttempts() {
        reconnectAttempts = 0
    }
}
