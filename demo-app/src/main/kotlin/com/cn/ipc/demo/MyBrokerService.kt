package com.cn.ipc.demo

import com.cn.ipc.server.DefaultIpcServiceRegistry
import com.cn.ipc.server.IpcBrokerService
import com.cn.ipc.server.RegisteredService
import com.cn.ipc.api.test.IBenchmarkEchoServiceServerStub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow

/**
 * IPC 服务端点服务 (Broker Service)。
 * 运行在服务端进程中，负责提供底层的 Binder 通信支持，并注册和管理所有跨进程服务。
 */
open class MyBrokerService : IpcBrokerService() {
    private val echoJob = SupervisorJob()
    private val defaultEchoScope = CoroutineScope(Dispatchers.Default + echoJob)
    private val dedicatedEchoDispatcher = lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "IpcShortEcho") }
            .asCoroutineDispatcher()
    }
    @Volatile private var selectedEchoScope = defaultEchoScope

    override fun onDestroy() {
        try { super.onDestroy() } finally {
            echoJob.cancel()
            if (dedicatedEchoDispatcher.isInitialized()) dedicatedEchoDispatcher.value.close()
        }
    }
    
    /**
     * 创建并初始化 IPC 服务注册表。
     * 在此方法中，我们将真实的业务服务与其对应的 Binder Stub 进行绑定和注册。
     *
     * @return 配置完毕的服务注册表实例
     */
    override fun onCreateRegistry(): DefaultIpcServiceRegistry {
        val registry = DefaultIpcServiceRegistry()
        
        // 实例化真实的业务处理逻辑
        val userScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val serviceImpl = UserServiceImpl(userScope)
        
        // 创建对应的 Stub，它是跨进程通信中服务端的 Binder 存根。
        // Stub 将接收到的 IPC 调用转发给实际的业务实现 (serviceImpl)。
        val stub = object : com.cn.ipc.api.test.IUserServiceServerStub(this) {
            // 指定协程作用域，用于执行服务端的挂起函数和流操作
            override val coroutineScope = userScope
            
            override fun ping() = serviceImpl.ping()
            override fun logMessage(msg: String) = serviceImpl.logMessage(msg)
            override suspend fun getLargeData(sizeInBytes: Int) = serviceImpl.getLargeData(sizeInBytes)
            override suspend fun getUserInfo(userId: String) = serviceImpl.getUserInfo(userId)
            override fun observeUserStatus(userId: String): kotlinx.coroutines.flow.Flow<Int> {
                if (userId == "__factory_error__") throw IllegalArgumentException("flow-factory-error")
                return serviceImpl.observeUserStatus(userId)
            }
            override fun observeGlobalBroadcast() = serviceImpl.observeGlobalBroadcast()
        }

        // 将该服务注册到注册表中，公开给客户端调用
        registry.register(
            RegisteredService(
                serviceId = 1001,           // 服务的唯一标识符
                apiVersion = 2,             // Flow subscription replies include an exception header.
                apiHash = "user_v2",
                requiredCapability = 0L,    // 调用此服务所需的权限能力标识
                permission = null,          // 可选的 Android 权限字符串要求
                binder = stub,              // 注册刚刚构建的 Binder Stub
                minSupportedClientVersion = 2
            )
        )
        val echoStub = object : IBenchmarkEchoServiceServerStub(this) {
            @Volatile private var legacyAuthBenchmark = false
            @Volatile private var traceRunId: String? = null
            override val coroutineScope: CoroutineScope get() = selectedEchoScope
            override fun authorizeCaller() {
                if (legacyAuthBenchmark) {
                    val authenticator = com.cn.ipc.server.CallerAuthenticator(this@MyBrokerService)
                    authenticator.authorize(authenticator.authenticate(), 9001)
                } else {
                    super.authorizeCaller()
                }
            }
            override suspend fun echo(payload: String): String = payload
            override fun echoDirect(payload: String): String {
                if (payload == "__error__") throw IllegalArgumentException("bench-error")
                if (payload.startsWith("__trace_start__:")) {
                    val run = payload.removePrefix("__trace_start__:")
                    com.cn.ipc.IpcRequestTrace.start(run)
                    traceRunId = run
                    return payload
                }
                if (payload == "__trace_flush__") {
                    val output = traceRunId?.let { java.io.File(filesDir, "request-events-$it-server.log") }
                    val stats = com.cn.ipc.IpcRequestTrace.stopAndFlush(output)
                    traceRunId = null
                    return "events=${stats.events},dropped=${stats.dropped}"
                }
                when (payload) {
                    "__dispatch_default__" -> {
                        check(activeRequestCount == 0) { "Switch only while idle" }
                        selectedEchoScope = defaultEchoScope
                    }
                    "__dispatch_dedicated__" -> {
                        check(activeRequestCount == 0) { "Switch only while idle" }
                        selectedEchoScope = CoroutineScope(dedicatedEchoDispatcher.value + echoJob)
                    }
                    "__auth_legacy__" -> legacyAuthBenchmark = true
                    "__auth_optimized__" -> legacyAuthBenchmark = false
                    "__auth_probe__" -> IpcAuthProbe.run(this@MyBrokerService)
                }
                return payload
            }
            override fun echoIntDirect(value: Int): Int = value
            override fun echoLongDirect(value: Long): Long = value
            override fun echoBooleanDirect(value: Boolean): Boolean = value
            override fun echoBytesDirect(value: ByteArray): ByteArray = value
            override fun inspectActiveSubscriptionCount(): Int = activeSubscriptionCount
            override fun inspectActiveRequestCount(): Int = activeRequestCount
            override suspend fun holdLifecycleRequest(): String = awaitCancellation()
            override fun observeLifecycleTicks() = flow {
                var sequence = 0
                while (true) {
                    emit(sequence++)
                    delay(50)
                }
            }
            override fun observeTerminalScenario(mode: String) = flow {
                when (mode) {
                    "finite" -> listOf(1, 2, 3).forEach { emit(it) }
                    "error" -> {
                        emit(7)
                        throw IllegalStateException("flow-runtime-error")
                    }
                    "overflow" -> {
                        var sequence = 0
                        while (true) {
                            emit(sequence++)
                            delay(1)
                        }
                    }
                    "cancel" -> {
                        var sequence = 0
                        while (true) {
                            emit(sequence++)
                            delay(50)
                        }
                    }
                    else -> throw IllegalArgumentException("Unknown terminal scenario: $mode")
                }
            }
            override fun observeConflatedScenario() = flow {
                repeat(1000) { emit(it) }
            }
        }
        registry.register(
            RegisteredService(
                serviceId = 9001,
                apiVersion = 6,
                apiHash = "bench_echo_v6",
                requiredCapability = 0L,
                permission = null,
                binder = echoStub
            )
        )
        return registry
    }
}
