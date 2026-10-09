package com.cn.ipc.demo

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.cn.ipc.client.IpcClientState
import com.cn.ipc.client.IpcConnectionController
import com.cn.ipc.api.test.IUserServiceClientAdapter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.atomic.AtomicInteger

/**
 * 演示应用的主界面 Activity。
 * 提供各种 UI 按钮用于触发和测试不同的 IPC 通信场景。
 */
class MainActivity : AppCompatActivity() {

    // 管理主线程生命周期的协程作用域
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    // 负责与服务端进行 IPC 通信的核心连接控制器
    private lateinit var controller: IpcConnectionController
    // 用于在界面上展示实时日志输出的文本视图
    private lateinit var logView: TextView
    private var benchmarkForeground: BenchmarkForegroundSession? = null

    /**
     * Activity 创建时回调。
     * 初始化 UI 组件，并绑定各种测试场景的点击事件。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 构建界面的主垂直线性布局
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // 包含日志输出的滚动视图，以便支持查看长日志
        val scrollView = ScrollView(this).apply { 
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f
            )
        }
        
        // 定义各个测试功能的按钮
        val btnConnect = Button(this).apply { text = "1. 建立 IPC 连接 (Connect)" }
        val btnTestConcurrency = Button(this).apply { text = "2. 极限压测: 1000 并发挂起请求" }
        val btnTestFlow = Button(this).apply { text = "3. 长连接: 订阅 3 个独立状态流" }
        val btnTestSharedFlow = Button(this).apply { text = "8. 终极考验: 3 客户端同时订阅同一个广播源" }
        val btnTestCrash = Button(this).apply { text = "4. 容灾测试: 强制杀掉 Server 进程"; setBackgroundColor(Color.RED); setTextColor(Color.WHITE) }
        val btnTestCancel = Button(this).apply { text = "5. 协程取消测试 (防内存泄漏)" }
        val btnTestOneway = Button(this).apply { text = "6. Oneway 单向消息投递" }
        val btnTestLargeData = Button(this).apply { text = "7. 大数据量跨进程传输 (100KB)" }
        
        // 初始化日志视图，并设置字体和边距
        logView = TextView(this).apply { textSize = 11f; setPadding(16, 16, 16, 16) }
        scrollView.addView(logView)

        // 将所有组件添加到主布局中
        layout.addView(btnConnect)
        layout.addView(btnTestConcurrency)
        layout.addView(btnTestFlow)
        layout.addView(btnTestSharedFlow)
        layout.addView(btnTestCrash)
        layout.addView(btnTestCancel)
        layout.addView(btnTestOneway)
        layout.addView(btnTestLargeData)
        layout.addView(scrollView)
        setContentView(layout)

        // 创建指向服务端 Service 的 Intent
        val intent = Intent(this, MyBrokerService::class.java)
        // 初始化 IPC 连接控制器，运行在 Default 调度器中
        controller = IpcConnectionController(
            context = this,
            targetIntent = intent,
            scope = scope
        )
        if (this.intent.getStringExtra("ipc_benchmark") != null) {
            val runId = this.intent.getStringExtra("ipc_benchmark") ?: "manual"
            controller.connect()
            scope.launch {
                val foreground = BenchmarkForegroundSession(this@MainActivity, runId)
                benchmarkForeground = foreground
                try {
                    foreground.run {
                        if (runId.startsWith("handshake")) {
                            withTimeout(90_000) {
                                controller.awaitConnected(10_000)
                                IpcHandshakeProbe.run(this@MainActivity, runId)
                            }
                        } else if (runId.startsWith("dispatchperf")) {
                            withTimeout(120_000) { IpcDispatchBenchmark.run(controller, filesDir, runId) }
                        } else if (runId.startsWith("trace")) {
                            withTimeout(90_000) {
                                IpcRequestTraceBenchmark.run(controller, filesDir, runId,
                                    this@MainActivity.intent.getBooleanExtra("ipc_request_trace", true))
                            }
                        } else if (runId.startsWith("cold")) {
                            withTimeout(90_000) { IpcColdDiscoveryProbe.run(this@MainActivity, runId) }
                        } else if (runId.startsWith("guardperf")) {
                            withTimeout(120_000) { IpcGuardBenchmark.run(controller, filesDir, runId) }
                        } else if (runId.startsWith("pendingperf")) {
                            withTimeout(120_000) { IpcPendingBenchmark.run(controller, filesDir, runId) }
                        } else if (runId.startsWith("compat")) {
                            withTimeout(45_000) { IpcCompatibilityProbe.run(this@MainActivity, controller, runId) }
                        } else if (runId.startsWith("terminal")) {
                            withTimeout(45_000) { IpcTerminalProbe.run(this@MainActivity, controller, runId) }
                        } else if (runId.startsWith("lifecycle")) {
                            withTimeout(45_000) { IpcLifecycleProbe.run(this@MainActivity, controller, runId) }
                        } else if (runId.startsWith("authmicro")) {
                            controller.awaitConnected(10_000)
                            withContext(Dispatchers.Default) {
                                com.cn.ipc.api.test.IBenchmarkEchoServiceClientAdapter(controller).echoDirect("__auth_probe__")
                            }
                        } else if (runId.startsWith("auth")) {
                            withTimeout(30_000) { IpcAuthBenchmark.run(controller, filesDir, runId) }
                        } else if (runId.startsWith("fault")) {
                            withTimeout(30_000) { IpcFaultProbe.run(controller, runId) }
                        } else if (runId.startsWith("stress")) {
                            IpcEchoBenchmark.runConcurrency(controller, filesDir, runId)
                        } else {
                            IpcEchoBenchmark.run(controller, filesDir, runId)
                        }
                    }
                } catch (error: Exception) {
                    android.util.Log.e("IpcEchoBench", "FAILED run=" + runId, error)
                } finally {
                    benchmarkForeground = null
                }
            }
        }

        // 测试场景 1: 建立连接并监听状态
        btnConnect.setOnClickListener {
            log("--- [发起连接] ---")
            controller.connect()
            // 监听底层状态机的变化
            controller.state.onEach { state ->
                val stateName = state::class.simpleName
                log("🔄 [状态机流转]: -> $stateName")
                if (state is IpcClientState.Connected) {
                    log("✅ [已连接]: 代次=${state.generation}, ServerVers=${state.protocol.serverVersionCode}")
                }
            }.launchIn(scope)
        }

        // 测试场景 2: 高并发挂起请求压测
        btnTestConcurrency.setOnClickListener {
            val userService = IUserServiceClientAdapter(controller)
            log("--- [并发压测]: 发起 1000 个请求 ---")
            val successCount = AtomicInteger(0)
            val start = System.currentTimeMillis()
            
            for (i in 1..1000) {
                scope.launch {
                    try {
                        // 发起挂起函数的跨进程调用
                        val result = userService.getUserInfo("并发测试_ID_$i")
                        val current = successCount.incrementAndGet()
                        if (current % 200 == 0 || current == 1000) {
                            log("👍 进度: $current/1000 完成. (最新: $result)")
                        }
                        if (current == 1000) {
                            val time = System.currentTimeMillis() - start
                            log("🎉 [压测结束]: 1000 个并发 IPC 请求在 $time 毫秒内全部成功响应！没有发生任何死锁或异常。")
                        }
                    } catch (e: Exception) {
                        log("❌ 请求 $i 失败: ${e.javaClass.simpleName}")
                    }
                }
            }
        }

        // 测试场景 3: 订阅多个独立的跨进程 Flow
        btnTestFlow.setOnClickListener {
            val userService = IUserServiceClientAdapter(controller)
            log("--- [流订阅测试]: 启动 3 个跨进程 Flow ---")
            
            for (i in 1..3) {
                userService.observeUserStatus("流订阅_ID_$i")
                    .onEach { status ->
                        log("📡 Flow-$i 收到实时数据: status=$status")
                    }
                    .catch { e ->
                        log("⚠️ Flow-$i 异常或断开: ${e.message}")
                    }
                    .launchIn(scope)
            }
        }

        // 测试场景 8: 多客户端同时订阅同一共享数据流
        btnTestSharedFlow.setOnClickListener {
            log("--- [共享流广播]: 建立 3 个独立的 IPC Controller，订阅同一个广播源 ---")
            for (i in 1..3) {
                // 每个独立 Controller 相当于一个完全独立的客户端（甚至可以想象成 3 个不同的独立进程）
                val independentController = IpcConnectionController(
                    context = this@MainActivity,
                    targetIntent = intent,
                    scope = scope
                )
                independentController.connect()
                
                scope.launch {
                    // 等待连接成功
                    independentController.state.first { it is IpcClientState.Connected }
                    val service = IUserServiceClientAdapter(independentController)
                    service.observeGlobalBroadcast()
                        .onEach { msg ->
                            log("📻 客户端 [$i] 接收广播: $msg")
                        }
                        .catch { e -> log("客户端 [$i] 广播异常: ${e.message}") }
                        .launchIn(scope)
                }
            }
        }

        // 测试场景 4: 服务端崩溃时的容灾和重连机制测试
        btnTestCrash.setOnClickListener {
            val userService = IUserServiceClientAdapter(controller)
            log("--- [容灾测试]: 正在向 Server 发送必死指令 (Ping) ---")
            scope.launch {
                try {
                    val connected = controller.awaitConnected()
                    controller.awaitServiceBinderForConnection(connected, 1001, 2,
                        com.cn.ipc.api.test.IUserServiceIpcSchema.CLIENT_SCHEMA)
                    // ping() 会导致服务端执行 Runtime.getRuntime().halt(0)
                    userService.ping()
                    log("等待底层 Binder 抛出 DeathRecipient 回调，观察退避重连机制 (Exponential Backoff)...")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { log("Ping 失败: ${error.message}") }
            }
        }

        // 测试场景 5: 客户端主动取消挂起协程，防止内存泄漏
        btnTestCancel.setOnClickListener {
            val userService = IUserServiceClientAdapter(controller)
            log("--- [取消测试]: 发起挂起请求并立刻 Cancel ---")
            val job = scope.launch {
                try {
                    log("发起 getUserInfo...")
                    val result = userService.getUserInfo("取消测试用户")
                    log("结果: $result (如果不应该看到此消息说明 Cancel 失败)")
                } catch (e: CancellationException) {
                    log("✅ 客户端协程被成功取消 (CancellationException)，没有造成泄漏。")
                } catch (e: Exception) {
                    log("❌ 发生错误: ${e.message}")
                }
            }
            // 等待极短时间后取消协程
            scope.launch {
                delay(50)
                log("向该协程发送 Cancel 信号！")
                job.cancel()
            }
        }

        // 测试场景 6: 测试 Oneway (单向、无需等待结果) 消息发送
        btnTestOneway.setOnClickListener {
            val userService = IUserServiceClientAdapter(controller)
            log("--- [Oneway 测试]: 发送 Fire-and-Forget 消息 ---")
            scope.launch {
                try {
                    val connected = controller.awaitConnected()
                    controller.awaitServiceBinderForConnection(connected, 1001, 2,
                        com.cn.ipc.api.test.IUserServiceIpcSchema.CLIENT_SCHEMA)
                    userService.logMessage("Hello from Client Oneway!")
                    log("✅ Oneway 消息已发出 (非阻塞返回，请查看 Server 进程日志)")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { log("❌ Oneway 消息发送失败: ${error.message}") }
            }
        }

        // 测试场景 7: 大数据量的 IPC 传输
        btnTestLargeData.setOnClickListener {
            val userService = IUserServiceClientAdapter(controller)
            log("--- [大对象传输]: 请求 100KB 数据 ---")
            scope.launch {
                try {
                    val start = System.currentTimeMillis()
                    val result = userService.getLargeData(100 * 1024)
                    val time = System.currentTimeMillis() - start
                    log("✅ 成功接收大对象！大小: ${result.length} 字节, 耗时: $time ms")
                } catch (e: Exception) {
                    log("❌ 传输失败: ${e.message}")
                }
            }
        }
    }

    /**
     * 将信息输出并展示到界面上的日志视图中。
     * 确保更新操作在主线程进行。
     *
     * @param msg 需要记录和打印的消息内容
     */
    private fun log(msg: String) {
        runOnUiThread {
            val oldText = logView.text.toString()
            val newText = "[$msg]\n$oldText"
            logView.text = newText.take(5000) // 增加日志保留长度，避免内存占用过多
        }
    }

    /**
     * Activity 销毁时的回调。
     * 取消所有的协程任务，并安全地关闭 IPC 连接释放资源。
     */
    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        controller.dispose()
    }

    override fun onResume() {
        super.onResume()
        benchmarkForeground?.onResume()
    }

    override fun onPause() {
        benchmarkForeground?.onPause()
        super.onPause()
    }

    override fun onStop() {
        benchmarkForeground?.onStop()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        benchmarkForeground?.onWindowFocusChanged(hasFocus)
    }
}
