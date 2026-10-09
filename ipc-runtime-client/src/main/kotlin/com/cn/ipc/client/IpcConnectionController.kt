package com.cn.ipc.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import com.cn.ipc.ClientHello
import com.cn.ipc.IIpcBroker
import com.cn.ipc.ClientServiceSchema
import com.cn.ipc.ServiceSchema
import com.cn.ipc.IpcCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 负责 IPC 连接状态机管理、服务绑定与死亡恢复的控制器。
 */
class IpcConnectionController(
    private val context: Context,
    private val targetIntent: Intent,
    scope: CoroutineScope,
    private val clientPackage: String = context.packageName,
    private val clientVersionCode: Long = 2L,
    private val maxReconnectAttempts: Int = 10,
    private val bindingTimeoutMs: Long = 5000L,
    val defaultCallTimeoutMs: Long = 30_000L
) {
    // Binding and cleanup outlive owner cancellation; owner completion disposes this controller.
    private val controlJob = SupervisorJob()
    private val controlScope = CoroutineScope(scope.coroutineContext.minusKey(Job) + Dispatchers.IO + controlJob)
    private val disposed = AtomicBoolean(false)
    private val disposalCompletion = CompletableDeferred<Unit>()
    private var ownerCompletionHandle: DisposableHandle? = null
    val isDisposed: Boolean get() = disposed.get()
    private val mutex = Mutex()
    private val _state = MutableStateFlow<IpcClientState>(IpcClientState.Idle)
    val state: StateFlow<IpcClientState> = _state.asStateFlow()
    
    /** 管理客户端跨进程订阅的注册表 */
    val subscriptionRegistry = SubscriptionRegistry()
    
    /** 管理客户端待处理（挂起）的 IPC 调用的注册表 */
    val pendingCallRegistry = PendingCallRegistry(defaultCallTimeoutMs)
    
    /** 
     * 全局响应回调 Binder，负责接收服务端发来的回调数据。
     * 用于处理基于回调模式的异步响应。
     */
    val globalResponseBinder: IBinder = object : android.os.Binder() {
        override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean {
            if (code != 1) return super.onTransact(code, data, reply, flags)
            val requestId = try { data.readLong() } catch (_: Exception) { return true }
            val traceReply = com.cn.ipc.IpcRequestTrace.nowIfEnabled()
            if (traceReply != 0L) com.cn.ipc.IpcRequestTrace.recordAt("client", "client_reply", requestId,
                generation = pendingCallRegistry.generationOf(requestId) ?: 0L, callback = this, ns = traceReply)
            try {
                when (data.readInt()) {
                    1 -> pendingCallRegistry.completeWithParcel(requestId, data)
                    0 -> pendingCallRegistry.fail(requestId, RuntimeException(data.readString() ?: "Remote error"))
                    else -> pendingCallRegistry.fail(requestId, IllegalArgumentException("Invalid IPC response status"))
                }
            } catch (error: Exception) {
                pendingCallRegistry.fail(requestId, error)
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
    private data class ServiceBinderKey(val generation: Long, val serviceId: Int, val minApiVersion: Int, val schemaFingerprint: String?)
    @Volatile private var serviceBinderCache = ServiceDiscoveryCache<ServiceBinderKey>()

    /** 后台退避重连协程 Job，确保生命周期唯一并支持即时取消 */
    private var reconnectJob: Job? = null
    private var bindingTimeoutJob: Job? = null

    /** 当前的重连尝试计数 */
    private var reconnectAttempts = 0

    init {
        require(maxReconnectAttempts >= 0)
        require(bindingTimeoutMs > 0)
        ownerCompletionHandle = scope.coroutineContext[Job]?.invokeOnCompletion { dispose() }
    }

    /**
     * 安全获取指定业务的 Binder，若未连接或 Binder 死亡则返回 null。
     * 具备一级内存缓存与存活校验，消除重复跨进程获取开销同时提供失效自动刷新能力。
     *
     * @param serviceId 目标服务的唯一标识 ID
     * @return 目标服务对应的 Binder 代理对象，连接不可用时返回 null
     */
    fun getServiceBinderOrNull(serviceId: Int, minApiVersion: Int = 1, expectedSchema: ClientServiceSchema? = null): IBinder? {
        return try { getServiceBinder(serviceId, minApiVersion, expectedSchema) } catch (_: Exception) { null }
    }

    /**
     * 获取指定业务的 Binder (如果已连接，则通过本地缓存或 Broker 获取)。
     *
     * @param serviceId 目标服务的唯一标识 ID
     * @return 目标服务对应的 Binder 代理对象
     * @throws IllegalStateException 如果尚未建立 IPC 连接或 Binder 不可用
     */
    fun getServiceBinder(serviceId: Int, minApiVersion: Int = 1, expectedSchema: ClientServiceSchema? = null): IBinder {
        check(!isDisposed) { "IPC controller disposed" }
        val connection = _state.value as? IpcClientState.Connected
            ?: throw IllegalStateException("IPC not connected (current state: ${_state.value})")
        return getServiceBinderForConnection(connection, serviceId, minApiVersion, expectedSchema)
    }

    /** Synchronous resolution. Cold discovery must be called off Main; cached reads remain immediate. */
    fun getServiceBinderForConnection(
        connection: IpcClientState.Connected,
        serviceId: Int,
        minApiVersion: Int = 1,
        expectedSchema: ClientServiceSchema? = null
    ): IBinder {
        checkResolution(connection, serviceId, minApiVersion)
        val cache = serviceBinderCache
        val key = ServiceBinderKey(connection.generation, serviceId, minApiVersion, expectedSchema?.fingerprint)
        cache.cached(key)?.let { checkConnection(connection); return it }
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            "Cold IPC discovery must use awaitServiceBinderForConnection on Main"
        }
        val flight = cache.acquire(key) { needed -> discoverService(connection, serviceId, minApiVersion, expectedSchema, needed) }
        return try {
            flight.awaitBlocking(defaultCallTimeoutMs).also { checkConnection(connection) }
        } finally { cache.release(key, flight) }
    }

    /** Cancellable discovery wait. Underlying Binder work is bounded and may outlive this waiter. */
    suspend fun awaitServiceBinderForConnection(
        connection: IpcClientState.Connected,
        serviceId: Int,
        minApiVersion: Int = 1,
        expectedSchema: ClientServiceSchema? = null
    ): IBinder {
        kotlin.coroutines.coroutineContext.ensureActive()
        checkResolution(connection, serviceId, minApiVersion)
        val cache = serviceBinderCache
        val key = ServiceBinderKey(connection.generation, serviceId, minApiVersion, expectedSchema?.fingerprint)
        cache.cached(key)?.let { checkConnection(connection); return it }
        return withTimeout(defaultCallTimeoutMs) {
            val flight = cache.acquire(key) { needed -> discoverService(connection, serviceId, minApiVersion, expectedSchema, needed) }
            try { flight.await().also { checkConnection(connection) } }
            finally { cache.release(key, flight) }
        }
    }

    private fun checkConnection(connection: IpcClientState.Connected) {
        check(_state.value === connection && !isDisposed) {
            "IPC connection changed while resolving service (generation: ${connection.generation})"
        }
    }

    private fun checkResolution(connection: IpcClientState.Connected, serviceId: Int, minApiVersion: Int) {
        checkConnection(connection)
        if ((connection.protocol.serviceVersions[serviceId] ?: 0) < minApiVersion) {
            throw IpcCompatibilityException("Service $serviceId version is below $minApiVersion")
        }
    }

    private fun discoverService(
        connection: IpcClientState.Connected, serviceId: Int, minApiVersion: Int,
        expectedSchema: ClientServiceSchema?, needed: () -> Boolean
    ): IBinder {
        fun ensureNeeded() {
            checkConnection(connection)
            check(needed()) { "IPC discovery no longer needed" }
        }
        ensureNeeded()
        val binder = if (expectedSchema == null) connection.broker.getService(serviceId, minApiVersion) else {
            if (connection.protocol.supportedCapabilities and IpcCapabilities.SCHEMA_CHECKED_SERVICES == 0L) {
                throw IpcCompatibilityException("Broker does not support checked service schemas")
            }
            try {
                val actual = connection.broker.getServiceSchema(serviceId)
                    ?: throw IpcCompatibilityException("Broker schema method unavailable or returned null")
                ensureNeeded()
                validateSchema(actual, serviceId, minApiVersion, expectedSchema)
                connection.broker.getServiceChecked(serviceId, minApiVersion,
                    ClientServiceSchema(expectedSchema.descriptor, expectedSchema.contractVersion, expectedSchema.stableMethodSignatures))
                    ?: throw IpcCompatibilityException("Broker checked schema method unavailable or returned null")
            } catch (error: IpcCompatibilityException) { throw error }
            catch (error: RemoteException) { throw error }
            catch (error: SecurityException) { throw error }
            catch (error: IllegalStateException) { throw error }
            catch (error: Exception) {
                throw IpcCompatibilityException("Broker schema validation failed for service $serviceId", error)
            }
        } ?: throw IllegalStateException("Broker returned no service Binder")
        ensureNeeded()
        return binder
    }

    /**
     * Recheck a previously resolved Binder immediately before sending a registered request.
     * Service discovery and Schema validation belong to the first resolution; this guard
     * never refreshes the captured Binder or sends a request through a newer connection.
     * A disconnect can still race the following transact, which must handle transport failure.
     */
    fun checkServiceBinderForConnection(connection: IpcClientState.Connected, binder: IBinder) {
        check(_state.value === connection && !isDisposed) {
            "IPC connection changed before transaction (generation: ${connection.generation})"
        }
        check(binder.isBinderAlive) { "Captured service Binder is dead" }
        check(_state.value === connection && !isDisposed) {
            "IPC connection changed before transaction (generation: ${connection.generation})"
        }
    }

    private fun validateSchema(actual: ServiceSchema, serviceId: Int, minApiVersion: Int, expected: ClientServiceSchema) {
        if (actual.serviceId != serviceId || actual.apiVersion < minApiVersion ||
            expected.contractVersion < actual.minSupportedClientVersion || expected.contractVersion > actual.apiVersion) {
            throw IpcCompatibilityException("Client/service version mismatch for service $serviceId")
        }
        if (actual.descriptor != expected.descriptor) throw IpcCompatibilityException("Schema descriptor mismatch")
        expected.stableMethodSignatures.forEach { (transaction, signature) ->
            if (actual.methodSignatures[transaction] != signature) {
                throw IpcCompatibilityException("Schema transaction $transaction mismatch or missing")
            }
        }
    }

    /**
     * 挂起等待 IPC 连接建立成功并返回连接状态，平滑解决冷启动与重连期间的时序竞争。
     *
     * @param timeoutMs 最长等待超时时间（毫秒）
     * @return 成功连接后的状态实例
     */
    suspend fun awaitConnected(timeoutMs: Long = 5000L): IpcClientState.Connected {
        check(!isDisposed) { "IPC controller disposed" }
        val current = _state.value
        if (current is IpcClientState.Connected) return current

        connect()
        return withTimeout(timeoutMs) {
            val result = _state.first { it is IpcClientState.Connected || it is IpcClientState.Disposed }
            check(result is IpcClientState.Connected && !isDisposed) { "IPC controller disposed" }
            result
        }
    }

    /**
     * 发起连接。
     * 支持从 Idle、Disconnected 以及 Closed 状态无缝重新发起绑定；
     * 若处于 Reconnecting 状态，则打断退避等待立即发起绑定重试。
     */
    fun connect() {
        check(!isDisposed) { "IPC controller disposed" }
        controlScope.launch {
            mutex.withLock {
                if (isDisposed) return@withLock
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
                    is IpcClientState.Disposed -> Unit
                }
            }
        }
    }

    /**
     * 断开连接并关闭状态机，清空缓存并让所有挂起在途调用快速失败。
     * 状态转换为 Closed 后，后续仍可通过调用 connect() 重新建立连接。
     */
    fun close() {
        launchClose(permanent = false)
    }

    /** Close this connection and wait for unbinding; explicit connect remains available. */
    suspend fun closeAndJoin() = withContext(NonCancellable) {
        launchClose(permanent = false).join()
        if (isDisposed) disposalCompletion.await()
    }

    /** Permanently release this controller. Owner scope completion also calls this method. */
    fun dispose() {
        disposed.set(true)
        launchClose(permanent = true)
    }

    suspend fun disposeAndJoin() = withContext(NonCancellable) {
        dispose()
        disposalCompletion.await()
    }

    private fun launchClose(permanent: Boolean): Job = controlScope.launch {
            mutex.withLock {
                if (_state.value is IpcClientState.Disposed) return@withLock
                if (!permanent && !isDisposed && _state.value is IpcClientState.Closed) return@withLock
                
                cancelReconnectJobLocked()
                cancelBindingTimeoutLocked()
                resetReconnectAttempts()
                _state.value = if (permanent || isDisposed) IpcClientState.Disposed else IpcClientState.Closed
                doUnbindServiceLocked()
                
                pendingCallRegistry.failAll(IllegalStateException("IPC connection explicitly closed"))
            }
            if (_state.value is IpcClientState.Disposed) {
                ownerCompletionHandle?.dispose()
                disposalCompletion.complete(Unit)
                controlJob.cancel()
            }
        }

    /**
     * 在持锁状态下执行底层服务绑定逻辑。
     * 会更新内部状态为 Binding 并向系统发起 bindService 请求。
     */
    private fun doBindServiceLocked() {
        if (isDisposed) return
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
        } else {
            bindingTimeoutJob = controlScope.launch {
                delay(bindingTimeoutMs)
                mutex.withLock {
                    if (serviceConnection === connection && _state.value is IpcClientState.Binding) {
                        handleBindFailureLocked()
                    }
                }
            }
        }
    }

    /**
     * 在持锁状态下执行底层服务解绑逻辑。
     * 安全地解除当前的 ServiceConnection 绑定。
     */
    private fun doUnbindServiceLocked() {
        cancelBindingTimeoutLocked()
        val detached = serviceBinderCache
        serviceBinderCache = ServiceDiscoveryCache()
        detached.invalidate(IllegalStateException("IPC connection closed during service discovery"))
        serviceConnection?.let {
            it.unlinkDeathRecipient()
            try {
                context.unbindService(it)
            } catch (_: Exception) {
            }
            serviceConnection = null
        }
    }

    /**
     * 在持锁状态下处理绑定失败的逻辑。
     * 具备指数退避重试能力，只有达到重试上限才进入 Disconnected。
     */
    private fun handleBindFailureLocked() {
        val shouldReconnect = reconnectAttempts < maxReconnectAttempts
        if (shouldReconnect) {
            reconnectAttempts++
            _state.value = IpcClientState.Reconnecting(attempt = reconnectAttempts)
        } else {
            _state.value = IpcClientState.Disconnected
        }
        doUnbindServiceLocked()
        
        // 快速失败在途请求
        pendingCallRegistry.failAllForGeneration(
            currentGeneration,
            RemoteException("IPC bind failed or lost for generation $currentGeneration")
        )

        if (shouldReconnect) {
            scheduleReconnectLocked()
        }
    }

    /**
     * 内部的服务连接回调实现，用于监听底层 ServiceConnection 状态。
     */
    private inner class InnerServiceConnection : ServiceConnection {
        private var observedBinder: IBinder? = null
        private var deathRecipient: IBinder.DeathRecipient? = null
        // Accessed under the controller mutex; only the waiter belongs to the control scope.
        private var handshakeJob: Job? = null

        fun unlinkDeathRecipient() {
            val waiter = handshakeJob
            handshakeJob = null
            waiter?.cancel()
            val binder = observedBinder
            val recipient = deathRecipient
            if (binder != null && recipient != null) {
                try { binder.unlinkToDeath(recipient, 0) } catch (_: Exception) {}
            }
            observedBinder = null
            deathRecipient = null
        }

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            controlScope.launch {
                val connectionJob = checkNotNull(coroutineContext[Job])
                val accepted = mutex.withLock {
                    if (isDisposed || serviceConnection !== this@InnerServiceConnection ||
                        _state.value !is IpcClientState.Binding) return@withLock false
                    if (service == null) {
                        handleBindFailureLocked()
                        false
                    } else if (handshakeJob?.isActive == true) {
                        false
                    } else {
                        handshakeJob = connectionJob
                        true
                    }
                }
                if (!accepted || service == null) return@launch
                try {
                        val broker = IIpcBroker.Stub.asInterface(service)
                        
                        // 发起握手协商
                        val clientHello = ClientHello(
                            protocolMajor = 1,
                            protocolMinor = 1,
                            clientPackage = clientPackage,
                            clientVersionCode = clientVersionCode,
                            requestedCapabilities = IpcCapabilities.SUPPORTED,
                            nonce = System.currentTimeMillis()
                        )
                        
                        val protocolInfo = HandshakeExecutor.await { broker.handshake(clientHello) }
                        coroutineContext.ensureActive()
                        if (protocolInfo.protocolMajor != 1) throw IpcCompatibilityException("Broker protocol major mismatch")
                        mutex.withLock {
                        if (isDisposed || serviceConnection !== this@InnerServiceConnection ||
                            _state.value !is IpcClientState.Binding || handshakeJob !== connectionJob) return@withLock
                        
                        // 握手成功，取消重连任务、重置计数并推进代次
                        cancelReconnectJobLocked()
                        cancelBindingTimeoutLocked()
                        resetReconnectAttempts()
                        currentGeneration++
                        val connectedGeneration = currentGeneration
                        
                        // 注册死亡监听
                        val recipient = IBinder.DeathRecipient {
                            handleBinderDied(this@InnerServiceConnection, connectedGeneration)
                        }
                        service.linkToDeath(recipient, 0)
                        observedBinder = service
                        deathRecipient = recipient
                        
                        _state.value = IpcClientState.Connected(
                            generation = currentGeneration,
                            broker = broker,
                            protocol = protocolInfo
                        )
                        }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutex.withLock {
                        if (!isDisposed && serviceConnection === this@InnerServiceConnection &&
                            _state.value is IpcClientState.Binding && handshakeJob === connectionJob) {
                            handleBindFailureLocked()
                        }
                    }
                } finally {
                    withContext(NonCancellable) {
                        mutex.withLock {
                            if (handshakeJob === connectionJob) handshakeJob = null
                        }
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            controlScope.launch {
                mutex.withLock {
                    if (serviceConnection != this@InnerServiceConnection) {
                        return@withLock
                    }
                    handleBindFailureLocked()
                }
            }
        }

        override fun onNullBinding(name: ComponentName?) = onBindingFailure()

        override fun onBindingDied(name: ComponentName?) = onBindingFailure()

        private fun onBindingFailure() {
            controlScope.launch {
                mutex.withLock {
                    if (!isDisposed && serviceConnection === this@InnerServiceConnection) handleBindFailureLocked()
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
        controlScope.launch {
            mutex.withLock {
                if (serviceConnection != connection) {
                    return@withLock
                }
                val currentState = _state.value
                if (currentState is IpcClientState.Connected && currentState.generation == generation) {
                    reconnectAttempts = 1
                    _state.value = IpcClientState.Reconnecting(attempt = reconnectAttempts)
                    doUnbindServiceLocked()
                    
                    // 立即让当前代次的所有在途挂起请求快速失败，杜绝假死卡顿
                    pendingCallRegistry.failAllForGeneration(
                        generation,
                        android.os.DeadObjectException("Server process died for generation $generation")
                    )

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

    private fun cancelBindingTimeoutLocked() {
        bindingTimeoutJob?.cancel()
        bindingTimeoutJob = null
    }

    /**
     * 调度下一次重连尝试。
     * 使用指数退避 (Exponential Backoff) 和随机抖动 (Jitter) 策略，
     * 避免服务端恢复后被瞬间的重连风暴压垮。
     */
    private fun scheduleReconnectLocked() {
        cancelReconnectJobLocked()
        reconnectJob = controlScope.launch {
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
