package com.cn.ipc.client.common

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.cn.ipc.api.hub.MeetingBoardMessage
import com.cn.ipc.api.hub.MessageEnvelope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID

/** A visible business example, using the same hub/Flow as the generic chat controls. Main thread only. */
internal class MeetingBoardDemo(
    context: Context,
    private val clientId: String,
    parentScope: CoroutineScope,
    private val send: suspend (Long, String, String) -> String,
    private val pause: (String) -> Unit,
    private val resume: (String) -> Unit
) {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val status = TextView(context)
    private val notice = TextView(context)
    private val applied = LinkedHashMap<String, MeetingBoardMessage>()
    private var pending: PendingShow? = null
    private var showJob: Job? = null
    private var connectionGeneration: Long? = null

    private data class PendingShow(val command: MeetingBoardMessage, val result: CompletableDeferred<Unit>)

    val view = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(20, 16, 20, 16)
        setBackgroundColor(Color.WHITE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 16) }
        addView(TextView(context).apply {
            text = "多 App 案例：会议通知"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        })
        status.text = when (clientId) {
            "client_1" -> "调度台：发布会议，等待会议屏的关联确认。"
            "client_2" -> "会议屏：等待会议指令，显示会议卡并回传处理确认。"
            else -> "运维屏：查看会议屏确认与会务通知。"
        }
        status.textSize = 13f
        status.contentDescription = "meeting-case-status"
        status.setPadding(0, 8, 0, 8)
        addView(status)
        notice.text = "会务通知：暂无"
        notice.contentDescription = "meeting-case-notice"
        addView(notice)
        if (clientId == "client_1") {
            addView(button(context, "发布会议到会议屏", "show") { trigger("show") })
            addView(button(context, "广播会务通知", "notice") { trigger("notice") })
            addView(button(context, "仅向中枢上报", "audit") { trigger("audit") })
        }
        addView(button(context, "暂停案例订阅", "pause") { trigger("pause") })
        addView(button(context, "恢复案例订阅", "resume") { trigger("resume") })
        addView(TextView(context).apply {
            text = "会议卡保存在当前页面内存中；确认表示页面数据已更新。暂停订阅期间不补发。"
            textSize = 11f
            setTextColor(Color.DKGRAY)
        })
    }

    private fun button(context: Context, label: String, action: String, click: () -> Unit) =
        Button(context).apply {
            text = label
            textSize = 12f
            contentDescription = "meeting-case-$action"
            setOnClickListener { click() }
        }

    fun onConnection(generation: Long?) {
        if (connectionGeneration == generation) return
        if (connectionGeneration != null) {
            job.cancelChildren()
            pending?.result?.cancel()
            pending = null
            showJob = null
            status.text = "连接已变更：未完成指令的结果未知。页面内容仅为本地记录，请重新确认。"
            event("connection_lost")
        }
        connectionGeneration = generation
    }

    fun subscriptionRequested(commandId: String = "") = event("subscription_requested", commandId = commandId)
    fun subscriptionPaused(commandId: String = "") = event("subscription_paused", commandId = commandId)

    fun trigger(action: String, commandId: String = UUID.randomUUID().toString(),
        title: String = if (action == "notice") "请参会人员提前五分钟入场" else "项目周会",
        room: String = "三楼会议室") {
        if (action == "pause") { pause(commandId); return }
        if (action == "resume") { resume(commandId); return }
        if (clientId != "client_1") { event("rejected", detail = "Only the control app can publish"); return }
        val kind = when (action) {
            "show" -> MeetingBoardMessage.SHOW
            "notice" -> MeetingBoardMessage.NOTICE
            "audit" -> MeetingBoardMessage.AUDIT
            else -> { event("rejected", detail = "Unknown action"); return }
        }
        val command = MeetingBoardMessage(kind, commandId, title, room)
        val encoded = try { command.encode() } catch (error: IllegalArgumentException) {
            event("rejected", command, detail = error.message.orEmpty()); return
        }
        val generation = connectionGeneration
        if (generation == null) {
            status.text = "尚未连接：请先连接中枢。"
            event("error", command, detail = "Not connected"); return
        }
        if (action == "show" && showJob?.isActive == true) {
            event("rejected", command, detail = "A meeting command is still pending"); return
        }
        val target = when (action) {
            "show" -> "client_2"
            "notice" -> MessageEnvelope.TARGET_ALL
            else -> MessageEnvelope.TARGET_SERVER
        }
        val launched = scope.launch {
            val waiter = if (action == "show") CompletableDeferred<Unit>().also {
                pending = PendingShow(command, it)
                status.text = card(command, "等待会议屏确认（最多 5 秒）")
            } else null
            try {
                withTimeout(5000) {
                    event("send", command, target)
                    val result = send(generation, target, encoded)
                    event("route_result", command, target, result)
                    check(result.startsWith("OK:")) { result }
                    if (waiter != null) waiter.await() else {
                        notice.text = card(command, if (action == "notice")
                            "中枢已处理广播；接收结果请在其他 App 确认" else "中枢已处理上报；未持久化")
                    }
                }
            } catch (_: TimeoutCancellationException) {
                if (waiter?.isCompleted == true && !waiter.isCancelled) {
                    // The receiving app's confirmation is stronger evidence than a late route callback.
                    event("route_result_unknown", command, target, "Already confirmed; route callback timed out")
                } else {
                    status.text = card(command, "5 秒内未完成确认，结果未知；不会自动重发")
                    event("error", command, target, "Confirmation deadline exceeded")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (waiter?.isCompleted == true && !waiter.isCancelled) {
                    event("route_result_unknown", command, target, "Already confirmed; ${error.message}")
                } else {
                    status.text = card(command, "请求失败或结果未知：${error.message}")
                    event("error", command, target, error.message.orEmpty())
                }
            } finally {
                if (pending?.command === command) pending = null
                waiter?.cancel()
            }
        }
        if (action == "show") showJob = launched
    }

    fun receive(envelope: MessageEnvelope): Boolean {
        val message = MeetingBoardMessage.decode(envelope.content) ?: return false
        event("received", message, envelope.target, "from=${envelope.fromId}")
        when (message.kind) {
            MeetingBoardMessage.SHOW -> {
                if (clientId != "client_2" || envelope.fromId != "client_1" || envelope.target != "client_2") {
                    event("rejected", message, envelope.target, "Invalid command role/route"); return true
                }
                val previous = applied[message.commandId]
                if (previous != null && previous != message) {
                    event("rejected", message, envelope.target, "commandId payload conflict"); return true
                }
                if (previous == null) {
                    status.text = card(message, "会议卡已更新")
                    applied[message.commandId] = message
                    if (applied.size > 64) applied.remove(applied.keys.first())
                }
                event("displayed", message, envelope.target, if (previous == null) "UI data applied" else "Already applied")
                val confirmation = message.copy(kind = MeetingBoardMessage.DISPLAYED)
                val generation = connectionGeneration ?: return true
                scope.launch {
                    try {
                        val result = send(generation, MessageEnvelope.TARGET_ALL, confirmation.encode())
                        event("route_result", confirmation, MessageEnvelope.TARGET_ALL, result)
                        check(result.startsWith("OK:")) { result }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        event("error", confirmation, MessageEnvelope.TARGET_ALL, error.message.orEmpty())
                    }
                }
            }
            MeetingBoardMessage.DISPLAYED -> {
                if (clientId !in setOf("client_1", "client_3") || envelope.fromId != "client_2" ||
                    envelope.target != MessageEnvelope.TARGET_ALL) {
                    event("rejected", message, envelope.target, "Invalid confirmation role/route"); return true
                }
                if (clientId == "client_1") {
                    val expected = pending
                    if (expected == null || message != expected.command.copy(kind = MeetingBoardMessage.DISPLAYED)) {
                        event("rejected", message, envelope.target, "Unmatched or late confirmation"); return true
                    }
                    status.text = card(message, "会议屏已确认页面数据更新")
                    expected.result.complete(Unit)
                } else status.text = card(message, "已观测到会议屏处理确认")
                event("acknowledged", message, envelope.target)
            }
            MeetingBoardMessage.NOTICE -> {
                if (envelope.fromId != "client_1" || envelope.target != MessageEnvelope.TARGET_ALL || clientId == "client_1") {
                    event("rejected", message, envelope.target, "Invalid notice role/route"); return true
                }
                notice.text = card(message, "会务通知已收到")
                event("notice_visible", message, envelope.target)
            }
            MeetingBoardMessage.AUDIT -> event("rejected", message, envelope.target, "Server-only payload reached a client")
        }
        return true
    }

    private fun card(message: MeetingBoardMessage, label: String) =
        "$label\n会议：${message.title}\n会场：${message.room}\ncommandId：${message.commandId}"

    private fun event(event: String, message: MeetingBoardMessage? = null, target: String = "", detail: String = "",
        commandId: String = message?.commandId.orEmpty()) {
        Log.i("IPC_MULTI_APP_CASE", JSONObject().put("event", event).put("clientId", clientId)
            .put("commandId", commandId).put("kind", message?.kind.orEmpty())
            .put("target", target).put("detail", detail).toString())
    }
}
