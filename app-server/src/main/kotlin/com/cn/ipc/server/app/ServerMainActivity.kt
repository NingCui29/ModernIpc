package com.cn.ipc.server.app

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Process
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.cn.ipc.api.hub.MessageEnvelope
import java.text.SimpleDateFormat
import java.util.*

/**
 * 服务端主控制台 Activity。
 * 提供服务运行状态、在线客户端看板、主动消息广播与实时路由日志展示。
 */
class ServerMainActivity : AppCompatActivity() {

    private val hubImpl = MessageHubServiceImpl.instance
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private lateinit var tvStatusSummary: TextView
    private lateinit var tvClient1Status: TextView
    private lateinit var tvClient2Status: TextView
    private lateinit var tvClient3Status: TextView
    private lateinit var tvLog: TextView
    private lateinit var etBroadcastMsg: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        val rootView = buildUi()
        setContentView(rootView)

        // 监听服务端事件更新
        hubImpl.onServerEvent = { tag, message ->
            runOnUiThread {
                log(tag, message)
                updateStatus()
            }
        }

        updateStatus()
        log("系统", "ModernIPC Broker 服务已就绪，正在监听客户端连接...")
    }

    private fun updateStatus() {
        val total = hubImpl.totalRouted.get()
        val broadcast = hubImpl.totalBroadcast.get()
        val targeted = hubImpl.totalTargeted.get()
        val serverOnly = hubImpl.totalServerOnly.get()
        val count = hubImpl.onlineClients.size

        tvStatusSummary.text = "🟢 状态: 正常运行 (PID: ${Process.myPid()})\n" +
                "📊 统计: 在线客户端 $count 个 | 路由消息总计 $total 条 (广播 $broadcast, 定向 $targeted, 私有 $serverOnly)"

        updateClientBadge(tvClient1Status, "client_1", "客户端 1")
        updateClientBadge(tvClient2Status, "client_2", "客户端 2")
        updateClientBadge(tvClient3Status, "client_3", "客户端 3")
    }

    private fun updateClientBadge(view: TextView, clientId: String, defaultName: String) {
        val isOnline = hubImpl.onlineClients.containsKey(clientId)
        if (isOnline) {
            view.text = "🟢 $defaultName (在线)"
            view.setTextColor(Color.parseColor("#2E7D32"))
            view.setBackgroundColor(Color.parseColor("#E8F5E9"))
        } else {
            view.text = "⚪ $defaultName (未连接)"
            view.setTextColor(Color.parseColor("#78909C"))
            view.setBackgroundColor(Color.parseColor("#ECEFF1"))
        }
    }

    private fun log(tag: String, message: String) {
        val time = timeFormat.format(Date())
        val line = "[$time][$tag] $message\n"
        val current = tvLog.text.toString()
        tvLog.text = line + current
    }

    private fun buildUi(): View {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#ECEFF1"))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // 1. 顶部 Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#263238")) // 深黑灰色
            setPadding(24, 28, 24, 24)
        }
        val tvTitle = TextView(this).apply {
            text = "ModernIPC 服务端调度中枢 (Server)"
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
        }
        tvStatusSummary = TextView(this).apply {
            text = "🟢 状态: 正在检测..."
            setTextColor(Color.parseColor("#B0BEC5"))
            textSize = 12f
            setPadding(0, 8, 0, 0)
        }
        header.addView(tvTitle)
        header.addView(tvStatusSummary)
        rootLayout.addView(header)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            setPadding(16, 16, 16, 16)
        }
        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 2. 在线客户端状态看板卡片
        val clientBoardCard = createCardLayout().apply {
            val title = TextView(this@ServerMainActivity).apply {
                text = "👥 客户端连接看板"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, 12)
            }
            addView(title)

            val badgeLayout = LinearLayout(this@ServerMainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            tvClient1Status = createBadgeTextView("客户端 1")
            tvClient2Status = createBadgeTextView("客户端 2")
            tvClient3Status = createBadgeTextView("客户端 3")

            badgeLayout.addView(tvClient1Status)
            badgeLayout.addView(tvClient2Status)
            badgeLayout.addView(tvClient3Status)
            addView(badgeLayout)
        }
        contentLayout.addView(clientBoardCard)

        // 3. 服务端主动推送控制卡片
        val sendCard = createCardLayout().apply {
            val title = TextView(this@ServerMainActivity).apply {
                text = "📢 服务端主动消息推送"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, 8)
            }
            addView(title)

            etBroadcastMsg = EditText(this@ServerMainActivity).apply {
                hint = "输入要从服务端推送的内容..."
                setText("【系统维护通知】服务器网络状态良好，请注意查收！")
                textSize = 13f
            }
            addView(etBroadcastMsg)

            val btnBroadcast = Button(this@ServerMainActivity).apply {
                text = "📢 广播全网 (推送给 Client 1, 2, 3)"
                setBackgroundColor(Color.parseColor("#37474F"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    val text = etBroadcastMsg.text.toString()
                    if (text.isNotBlank()) {
                        hubImpl.sendFromServer(MessageEnvelope.TARGET_ALL, text)
                        updateStatus()
                    }
                }
            }
            addView(btnBroadcast)

            val rowTarget = LinearLayout(this@ServerMainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val btnTo1 = createSmallTargetButton("推给 Client 1", Color.parseColor("#1A237E")) {
                hubImpl.sendFromServer("client_1", etBroadcastMsg.text.toString())
                updateStatus()
            }
            val btnTo2 = createSmallTargetButton("推给 Client 2", Color.parseColor("#004D40")) {
                hubImpl.sendFromServer("client_2", etBroadcastMsg.text.toString())
                updateStatus()
            }
            val btnTo3 = createSmallTargetButton("推给 Client 3", Color.parseColor("#BF360C")) {
                hubImpl.sendFromServer("client_3", etBroadcastMsg.text.toString())
                updateStatus()
            }
            rowTarget.addView(btnTo1)
            rowTarget.addView(btnTo2)
            rowTarget.addView(btnTo3)
            addView(rowTarget)
        }
        contentLayout.addView(sendCard)

        // 4. 实时路由与审计日志卡片
        val logCard = createCardLayout().apply {
            val logHeader = LinearLayout(this@ServerMainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val logTitle = TextView(this@ServerMainActivity).apply {
                text = "📜 服务端中枢调度日志"
                typeface = Typeface.DEFAULT_BOLD
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val btnClear = Button(this@ServerMainActivity).apply {
                text = "清空"
                textSize = 11f
                setOnClickListener { tvLog.text = "" }
            }
            logHeader.addView(logTitle)
            logHeader.addView(btnClear)
            addView(logHeader)

            tvLog = TextView(this@ServerMainActivity).apply {
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

    private fun createBadgeTextView(title: String): TextView {
        return TextView(this).apply {
            text = "⚪ $title"
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(16, 12, 16, 12)
            val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(4, 0, 4, 0)
            }
            layoutParams = params
        }
    }

    private fun createSmallTargetButton(title: String, bgColor: Int, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = title
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(bgColor)
            val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(2, 4, 2, 0)
            }
            layoutParams = params
            setOnClickListener { onClick() }
        }
    }
}
