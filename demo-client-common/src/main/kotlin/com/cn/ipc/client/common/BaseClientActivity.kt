package com.cn.ipc.client.common

import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.cn.ipc.api.hub.IMessageHubService
import com.cn.ipc.api.hub.IMessageHubServiceClientAdapter
import com.cn.ipc.api.hub.MessageEnvelope
import com.cn.ipc.client.IpcClientState
import com.cn.ipc.client.IpcConnectionController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.text.SimpleDateFormat
import java.util.*

/**
 * 客户端公共基类 Activity。
 * 提供完整的 UI 交互、跨进程连接控制、消息收发与对照测试逻辑。
 */
abstract class BaseClientActivity : AppCompatActivity() {

    abstract val clientId: String
    abstract val clientName: String
    abstract val themeColor: Int // 主题主色调
    open val isSenderRole: Boolean = false // 是否为主发送发起方 (Client 1)

    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var controller: IpcConnectionController
    private var hubService: IMessageHubServiceClientAdapter? = null
    private var subscriptionJob: Job? = null

    // UI 组件引用
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnSubscribe: Button
    private lateinit var etMessage: EditText
    private lateinit var spTarget: Spinner
    private lateinit var tvBenchmarkStatus: TextView
    private lateinit var tvBenchmarkResult: TextView
    private var isBenchmarking = false

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        initIpcController()
        val rootView = buildUi()
        setContentView(rootView)

        // 默认进入界面后自动尝试连接
        connectToServer()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.hasExtra("trigger_benchmark") == true) {
            val mode = intent.getStringExtra("trigger_benchmark") ?: "all"
            runBenchmark(mode)
        }
    }

    private fun initIpcController() {
        val targetIntent = Intent("com.cn.ipc.ACTION_BROKER_SERVICE").apply {
            setPackage("com.cn.ipc.server.app")
            component = ComponentName("com.cn.ipc.server.app", "com.cn.ipc.server.app.ServerBrokerService")
        }

        controller = IpcConnectionController(
            context = this,
            targetIntent = targetIntent,
            scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
            clientPackage = packageName
        )

        // 监听连接状态变化
        controller.state.onEach { state ->
            runOnUiThread { updateConnectionStatus(state) }
        }.launchIn(activityScope)
    }

    private fun updateConnectionStatus(state: IpcClientState) {
        when (state) {
            is IpcClientState.Connected -> {
                tvStatus.text = "🟢 已连接 (代次: ${state.generation}, Server: ${state.protocol.serverVersionCode})"
                btnConnect.text = "断开连接"
                hubService = IMessageHubServiceClientAdapter(controller)
                log("系统", "✅ IPC 连接建立成功，正在注册身份与订阅流...")
                registerAndSubscribe()
                if (intent.hasExtra("trigger_benchmark")) {
                    val mode = intent.getStringExtra("trigger_benchmark") ?: "all"
                    activityScope.launch {
                        delay(1200)
                        runBenchmark(mode)
                    }
                }
            }
            is IpcClientState.Binding -> {
                tvStatus.text = "🟡 正在连接服务端..."
                btnConnect.text = "连接中..."
            }
            is IpcClientState.Reconnecting -> {
                tvStatus.text = "🟠 服务端异常，退避重连中 (第 ${state.attempt} 次)..."
                btnConnect.text = "重连中..."
                subscriptionJob?.cancel()
            }
            is IpcClientState.Disconnected, is IpcClientState.Idle, is IpcClientState.Closed -> {
                tvStatus.text = "🔴 未连接"
                btnConnect.text = "连接服务端"
                subscriptionJob?.cancel()
                btnSubscribe.text = "开启订阅"
            }
        }
    }

    private fun registerAndSubscribe() {
        val service = hubService ?: return
        activityScope.launch {
            try {
                // 1. 注册身份
                service.registerClient(clientId, clientName)
                log("身份", "向服务端登记为: $clientName ($clientId)")

                // 2. 开启订阅
                startSubscription()
            } catch (e: Exception) {
                log("错误", "注册失败: ${e.message}")
            }
        }
    }

    private fun startSubscription() {
        val service = hubService ?: return
        subscriptionJob?.cancel()
        btnSubscribe.text = "暂停订阅"

        subscriptionJob = service.observeMessages(clientId)
            .onEach { raw ->
                val envelope = MessageEnvelope.decode(raw)
                if (envelope != null) {
                    onMessageReceived(envelope)
                } else {
                    log("接收", "原始消息: $raw")
                }
            }
            .catch { e ->
                log("订阅异常", "消息流中断: ${e.message}")
            }
            .launchIn(activityScope)

        log("订阅", "📡 已成功订阅消息通道！正在监听消息...")
    }

    private fun stopSubscription() {
        subscriptionJob?.cancel()
        subscriptionJob = null
        btnSubscribe.text = "开启订阅"
        log("订阅", "⏸️ 已暂停订阅消息通道")
    }

    var onBenchmarkFlowReceived: ((String) -> Unit)? = null

    private fun onMessageReceived(msg: MessageEnvelope) {
        if (msg.content.contains("[BENCHMARK]")) {
            onBenchmarkFlowReceived?.invoke(msg.content)
            return
        }
        val time = timeFormat.format(Date(msg.timestamp))
        val isSelf = msg.fromId == clientId

        if (msg.target == MessageEnvelope.TARGET_ALL) {
            log("📢 全员广播", "[$time] 来自 ${msg.fromName} (${msg.fromId}):\n  👉 ${msg.content}")
        } else if (msg.target == clientId) {
            log("📩 定向私信", "[$time] 来自 ${msg.fromName} (${msg.fromId}) 发给我的专属消息:\n  👉 ${msg.content}")
        } else if (isSelf) {
            log("📤 本地回执", "[$time] 我发给 [${msg.target}] 的消息已转发: ${msg.content}")
        } else {
            // 如果路由隔离正常，这里绝不应该被收到
            log("🚨 警告(隔离泄漏)", "[$time] 收到未授权发给 [${msg.target}] 的消息: ${msg.content}")
        }
    }

    private fun connectToServer() {
        log("操作", "正在向 com.cn.ipc.server.app 发起 bindService...")
        controller.connect()
    }

    private fun disconnectFromServer() {
        activityScope.launch {
            try {
                hubService?.unregisterClient(clientId)
            } catch (ignored: Exception) {}
            stopSubscription()
            controller.close()
            hubService = null
            log("操作", "已主动断开与服务端的连接")
        }
    }

    private fun sendCustomMessage(target: String, content: String) {
        val service = hubService
        if (service == null) {
            log("错误", "尚未连接到服务端，无法发送！")
            return
        }

        activityScope.launch {
            try {
                val start = System.currentTimeMillis()
                val ack = service.sendMessage(clientId, target, content)
                val duration = System.currentTimeMillis() - start
                log("发送", "发送到 [$target] 成功！耗时: ${duration}ms, 回执: $ack")
            } catch (e: Exception) {
                log("错误", "发送失败: ${e.message}")
            }
        }
    }

    private fun log(tag: String, message: String) {
        val time = timeFormat.format(Date())
        val line = "[$time][$tag] $message\n"
        val current = tvLog.text.toString()
        tvLog.text = line + current
    }

    // ==========================================
    // UI 构建逻辑 (纯代码构建，稳定且无资源冲突)
    // ==========================================

    private fun buildUi(): View {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F7FA"))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // 1. 顶部 Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColor)
            setPadding(24, 28, 24, 24)
        }
        val tvTitle = TextView(this).apply {
            text = "$clientName  [$clientId]"
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
        }
        tvStatus = TextView(this).apply {
            text = "🔴 状态: 初始化中..."
            setTextColor(Color.parseColor("#E0E0E0"))
            textSize = 12f
            setPadding(0, 8, 0, 0)
        }
        header.addView(tvTitle)
        header.addView(tvStatus)
        rootLayout.addView(header)

        // 滚动内容容器
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            setPadding(16, 16, 16, 16)
        }
        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 2. 连接与控制操作行
        val controlCard = createCardLayout()
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnConnect = Button(this).apply {
            text = "连接服务端"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                if (controller.state.value is IpcClientState.Connected) {
                    disconnectFromServer()
                } else {
                    connectToServer()
                }
            }
        }
        btnSubscribe = Button(this).apply {
            text = "开启订阅"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                if (subscriptionJob?.isActive == true) {
                    stopSubscription()
                } else {
                    startSubscription()
                }
            }
        }
        row1.addView(btnConnect)
        row1.addView(btnSubscribe)
        controlCard.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnOnline = Button(this).apply {
            text = "在线客户端"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                activityScope.launch {
                    val res = hubService?.getOnlineClients() ?: "未连接"
                    log("查询在线", res)
                }
            }
        }
        val btnServerStatus = Button(this).apply {
            text = "服务端状态"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                activityScope.launch {
                    val res = hubService?.getServerStatus() ?: "未连接"
                    log("服务端指标", res)
                }
            }
        }
        val btnPing = Button(this).apply {
            text = "心跳 Ping"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                hubService?.ping(clientId)
                log("心跳", "已向 Server 发送 Ping (Oneway)")
            }
        }
        row2.addView(btnOnline)
        row2.addView(btnServerStatus)
        row2.addView(btnPing)
        controlCard.addView(row2)
        contentLayout.addView(controlCard)

        // 3. 特殊对照验证测试控制卡片 (仅由 Client 1 主导发起，或在当前端显示)
        if (isSenderRole) {
            val contrastCard = createCardLayout().apply {
                val banner = TextView(this@BaseClientActivity).apply {
                    text = "⚡ 对照验证控制台 (仅由 Client 1 发起)"
                    setTextColor(Color.parseColor("#1565C0"))
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, 0, 0, 12)
                }
                addView(banner)

                // 按钮 1: 全员广播（验证 2 和 3 均能收到）
                val btnTestBroadcast = createActionButton(
                    title = "①【广播消息】👉 验证 Client 2 和 3 均能收到",
                    bgColor = Color.parseColor("#1E88E5")
                ) {
                    sendCustomMessage("ALL", "【公共广播】大家好！我是客户端1，这是发送给全员的广播消息。")
                }
                addView(btnTestBroadcast)

                // 按钮 2: 定向 Client 2（验证 2 能收到，3 不能收到）
                val btnTestTarget2 = createActionButton(
                    title = "②【定向 Client 2】👉 验证 2 能收到，3 不能收到",
                    bgColor = Color.parseColor("#43A047")
                ) {
                    sendCustomMessage("client_2", "【私密信件】你好客户端 2！我是客户端 1，此消息仅发给你！")
                }
                addView(btnTestTarget2)

                // 按钮 3: 定向 Client 3（验证 3 能收到，2 不能收到）
                val btnTestTarget3 = createActionButton(
                    title = "③【定向 Client 3】👉 验证 3 能收到，2 不能收到",
                    bgColor = Color.parseColor("#FB8C00")
                ) {
                    sendCustomMessage("client_3", "【私密信件】你好客户端 3！我是客户端 1，此消息仅发给你！")
                }
                addView(btnTestTarget3)

                // 按钮 4: 仅发服务端（验证 2 和 3 均不能收到）
                val btnTestServerOnly = createActionButton(
                    title = "④【仅发服务端】👉 验证 Client 2 和 3 均不能收到",
                    bgColor = Color.parseColor("#546E7A")
                ) {
                    sendCustomMessage("SERVER_ONLY", "【机密上报】这是客户端 1 仅向服务端汇报的内部数据，任何其他客户端绝不可见！")
                }
                addView(btnTestServerOnly)
            }
            contentLayout.addView(contrastCard)

            val benchmarkCard = createCardLayout().apply {
                val banner = TextView(this@BaseClientActivity).apply {
                    text = "🚀 性能压力与基准测试控制台"
                    setTextColor(Color.parseColor("#C2185B"))
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, 0, 0, 8)
                }
                addView(banner)

                val btnRunAll = createActionButton(
                    title = "🚀 运行全量性能压测 (RTT+并发+大包+Oneway+Flow)",
                    bgColor = Color.parseColor("#E91E63")
                ) {
                    runBenchmark("all")
                }
                addView(btnRunAll)

                val rowQuick = LinearLayout(this@BaseClientActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                val btnRtt = createSmallTargetButton("仅测 RTT (1000次)", Color.parseColor("#7B1FA2")) {
                    runBenchmark("rtt")
                }
                val btnConc = createSmallTargetButton("仅测并发 (2000并)", Color.parseColor("#512DA8")) {
                    runBenchmark("concurrency")
                }
                val btnPayload = createSmallTargetButton("仅测大包 (500KB)", Color.parseColor("#303F9F")) {
                    runBenchmark("payload")
                }
                val btnOneway = createSmallTargetButton("仅测 Oneway (5k次)", Color.parseColor("#0288D1")) {
                    runBenchmark("oneway")
                }
                rowQuick.addView(btnRtt)
                rowQuick.addView(btnConc)
                rowQuick.addView(btnPayload)
                rowQuick.addView(btnOneway)
                addView(rowQuick)

                tvBenchmarkStatus = TextView(this@BaseClientActivity).apply {
                    text = "就绪: 点击上方按钮开始真实设备性能压测"
                    textSize = 11f
                    setTextColor(Color.parseColor("#D81B60"))
                    setPadding(4, 8, 4, 4)
                }
                addView(tvBenchmarkStatus)

                tvBenchmarkResult = TextView(this@BaseClientActivity).apply {
                    text = "暂无压测结果"
                    textSize = 10f
                    typeface = Typeface.MONOSPACE
                    setTextColor(Color.parseColor("#263238"))
                    setPadding(8, 8, 8, 8)
                    setBackgroundColor(Color.parseColor("#FCE4EC"))
                }
                addView(tvBenchmarkResult)
            }
            contentLayout.addView(benchmarkCard)
        }

        // 4. 自定义发送消息卡片
        val sendCard = createCardLayout().apply {
            val title = TextView(this@BaseClientActivity).apply {
                text = "💬 自定义发送消息"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, 8)
            }
            addView(title)

            val rowTarget = LinearLayout(this@BaseClientActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 4, 0, 8)
            }
            val lblTarget = TextView(this@BaseClientActivity).apply {
                text = "目标: "
                textSize = 13f
            }
            spTarget = Spinner(this@BaseClientActivity).apply {
                val targets = listOf("ALL", "client_1", "client_2", "client_3", "SERVER_ONLY")
                    .filter { it != clientId }
                adapter = ArrayAdapter(this@BaseClientActivity, android.R.layout.simple_spinner_dropdown_item, targets)
            }
            rowTarget.addView(lblTarget)
            rowTarget.addView(spTarget)
            addView(rowTarget)

            etMessage = EditText(this@BaseClientActivity).apply {
                hint = "输入消息内容..."
                setText("来自 $clientName 的打招呼消息")
                textSize = 13f
            }
            addView(etMessage)

            val btnSend = Button(this@BaseClientActivity).apply {
                text = "发送消息"
                setBackgroundColor(themeColor)
                setTextColor(Color.WHITE)
                setOnClickListener {
                    val target = spTarget.selectedItem?.toString() ?: "ALL"
                    val content = etMessage.text.toString()
                    if (content.isNotBlank()) {
                        sendCustomMessage(target, content)
                    }
                }
            }
            addView(btnSend)
        }
        contentLayout.addView(sendCard)

        // 5. 实时通信日志卡片
        val logCard = createCardLayout().apply {
            val logHeader = LinearLayout(this@BaseClientActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val logTitle = TextView(this@BaseClientActivity).apply {
                text = "📜 实时通信日志"
                typeface = Typeface.DEFAULT_BOLD
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val btnClear = Button(this@BaseClientActivity).apply {
                text = "清空"
                textSize = 11f
                setOnClickListener { tvLog.text = "" }
            }
            logHeader.addView(logTitle)
            logHeader.addView(btnClear)
            addView(logHeader)

            tvLog = TextView(this@BaseClientActivity).apply {
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextColor(Color.parseColor("#263238"))
                setPadding(8, 12, 8, 12)
                setBackgroundColor(Color.parseColor("#ECEFF1"))
            }
            addView(tvLog)
        }
        contentLayout.addView(logCard)

        scrollView.addView(contentLayout)
        rootLayout.addView(scrollView)
        return rootLayout
    }

    private fun createCardLayout(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            val bg = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 16f
                setStroke(1, Color.parseColor("#CFD8DC"))
            }
            background = bg
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 16)
            }
            layoutParams = params
        }
    }

    private fun createActionButton(title: String, bgColor: Int, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = title
            setTextColor(Color.WHITE)
            setBackgroundColor(bgColor)
            textSize = 12f
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 4, 0, 6)
            }
            layoutParams = params
            setOnClickListener { onClick() }
        }
    }

    private fun createSmallTargetButton(title: String, bgColor: Int, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = title
            textSize = 10f
            setTextColor(Color.WHITE)
            setBackgroundColor(bgColor)
            val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(2, 4, 2, 0)
            }
            layoutParams = params
            setOnClickListener { onClick() }
        }
    }

    private fun runBenchmark(mode: String = "all") {
        if (isBenchmarking) {
            log("压测", "⚠️ 当前压测任务正在运行中，请勿重复触发")
            return
        }
        val service = hubService
        if (service == null) {
            log("压测", "❌ 尚未连接到服务端，无法执行压测")
            return
        }
        isBenchmarking = true
        activityScope.launch {
            try {
                tvBenchmarkStatus.text = "⏳ 压测初始化中..."
                tvBenchmarkResult.text = "压测运行中，请稍候..."
                when (mode) {
                    "rtt" -> {
                        val rtt = IpcBenchmarkSuite.testRtt(service, clientId, 1000) { p ->
                            runOnUiThread {
                                tvBenchmarkStatus.text = p
                                log("压测", p)
                            }
                        }
                        tvBenchmarkResult.text = "【RTT 基准完成】\nMin: ${rtt.minMs}ms | Avg: ${rtt.avgMs}ms | P50: ${rtt.p50Ms}ms | P90: ${rtt.p90Ms}ms | P99: ${rtt.p99Ms}ms | Max: ${rtt.maxMs}ms"
                    }
                    "concurrency" -> {
                        val list = IpcBenchmarkSuite.testConcurrency(service, clientId) { p ->
                            runOnUiThread {
                                tvBenchmarkStatus.text = p
                                log("压测", p)
                            }
                        }
                        val sb = StringBuilder("【高并发压测完成】\n")
                        list.forEach { sb.append("${it.concurrency} 并发: 耗时 ${it.totalTimeMs}ms, QPS = ${"%.1f".format(it.qps)} req/s (成功率 ${it.successCount}/${it.concurrency})\n") }
                        tvBenchmarkResult.text = sb.toString()
                    }
                    "payload" -> {
                        val list = IpcBenchmarkSuite.testPayloadScaling(service, clientId) { p ->
                            runOnUiThread {
                                tvBenchmarkStatus.text = p
                                log("压测", p)
                            }
                        }
                        val sb = StringBuilder("【大数据阶梯压测完成】\n")
                        list.forEach { sb.append("${it.payloadSizeBytes / 1024}KB: RTT=${"%.2f".format(it.avgRttMs)}ms, 吞吐=${"%.2f".format(it.throughputMBps)} MB/s\n") }
                        tvBenchmarkResult.text = sb.toString()
                    }
                    "oneway" -> {
                        val oneway = IpcBenchmarkSuite.testOneway(service, clientId, 5000) { p ->
                            runOnUiThread {
                                tvBenchmarkStatus.text = p
                                log("压测", p)
                            }
                        }
                        tvBenchmarkResult.text = "【Oneway 压测完成】\n${oneway.count} 次调用总耗时: ${oneway.totalTimeMs}ms | 吞吐率: ${"%.1f".format(oneway.throughputQps)} calls/s | 单次调度: ${"%.2f".format(oneway.avgDispatchUs)} μs"
                    }
                    else -> {
                        val report = IpcBenchmarkSuite.runFullBenchmark(
                            service = service,
                            clientId = clientId,
                            flowListenerSetter = { listener -> onBenchmarkFlowReceived = listener }
                        ) { p ->
                            runOnUiThread {
                                tvBenchmarkStatus.text = p
                                log("压测", p)
                            }
                        }
                        val summary = buildString {
                            appendLine("================ 性能压测汇总 ================")
                            appendLine("【1. RTT 往返时延基准 (1,000 次)】")
                            appendLine("   Min: ${report.rtt.minMs}ms | Avg: ${report.rtt.avgMs}ms | P50: ${report.rtt.p50Ms}ms")
                            appendLine("   P90: ${report.rtt.p90Ms}ms | P99: ${report.rtt.p99Ms}ms | Max: ${report.rtt.maxMs}ms | StdDev: ${"%.3f".format(report.rtt.stdDevUs / 1000.0)}ms")
                            appendLine("\n【2. 高并发挂起请求吞吐 (QPS)】")
                            report.concurrency.forEach {
                                appendLine("   • ${it.concurrency} 并发: 耗时 ${it.totalTimeMs}ms, QPS = ${"%.1f".format(it.qps)} req/s (成功 ${it.successCount}/${it.concurrency})")
                            }
                            appendLine("\n【3. 大数据包阶梯传输吞吐】")
                            report.payload.forEach {
                                appendLine("   • ${it.payloadSizeBytes / 1024}KB: RTT = ${"%.2f".format(it.avgRttMs)}ms, 吞吐 = ${"%.2f".format(it.throughputMBps)} MB/s")
                            }
                            appendLine("\n【4. Oneway 极速投递 (5,000 次)】")
                            appendLine("   总耗时: ${report.oneway.totalTimeMs}ms, 吞吐率 = ${"%.1f".format(report.oneway.throughputQps)} calls/s, 单次调度 = ${"%.2f".format(report.oneway.avgDispatchUs)} μs")
                            appendLine("\n【5. 跨进程 Flow 广播 (1,000 条)】")
                            appendLine("   投递到达率: ${report.flow.receivedCount}/${report.flow.count}, 吞吐 = ${"%.1f".format(report.flow.throughputEventsPerSec)} events/s")
                            appendLine("==============================================")
                        }
                        tvBenchmarkResult.text = summary
                        android.util.Log.i("IPC_BENCHMARK_SUMMARY", summary)
                    }
                }
            } catch (e: Throwable) {
                tvBenchmarkStatus.text = "❌ 压测异常: ${e.message}"
                log("压测异常", e.message ?: "未知错误")
            } finally {
                isBenchmarking = false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
        controller.close()
    }
}
