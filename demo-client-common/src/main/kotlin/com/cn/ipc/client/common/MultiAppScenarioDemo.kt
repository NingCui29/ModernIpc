package com.cn.ipc.client.common

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.cn.ipc.api.hub.MessageEnvelope
import com.cn.ipc.api.hub.MultiAppScenarioMessage
import com.cn.ipc.api.hub.MultiAppScenarioMessage.Companion.ORDER_ACCEPTED
import com.cn.ipc.api.hub.MultiAppScenarioMessage.Companion.ORDER_ASSIGN
import com.cn.ipc.api.hub.MultiAppScenarioMessage.Companion.ORDER_COMPLETED
import com.cn.ipc.api.hub.MultiAppScenarioMessage.Companion.SETTINGS
import com.cn.ipc.api.hub.MultiAppScenarioMessage.Companion.SETTINGS_APPLIED
import com.cn.ipc.api.hub.MultiAppScenarioMessage.Companion.SETTINGS_APPLY
import com.cn.ipc.api.hub.MultiAppScenarioMessage.Companion.TELEMETRY
import com.cn.ipc.api.hub.MultiAppScenarioMessage.Companion.TELEMETRY_ALERT
import com.cn.ipc.api.hub.MultiAppScenarioMessage.Companion.TELEMETRY_SAMPLE
import com.cn.ipc.api.hub.MultiAppScenarioMessage.Companion.WORK_ORDER
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

/** Main-thread business examples. ACK means TextView data was updated, including a hidden card. */
internal class MultiAppScenarioDemo(
    private val context: Context,
    private val clientId: String,
    parentScope: CoroutineScope,
    private val send: suspend (Long, String, String) -> String
) {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val cards = LinkedHashMap<String, LinearLayout>()
    private val statuses = LinkedHashMap<String, TextView>()
    private val operations = HashMap<String, Job>()
    private val replyJobs = HashMap<String, Job>()
    private var connectionGeneration: Long? = null
    private val orders = LinkedHashMap<String, Order>()
    private var currentOrderId: String? = null
    private var pendingOrder: Pending? = null
    private var pendingSettings: PendingSettings? = null
    private val settingsCommands = LinkedHashMap<String, MultiAppScenarioMessage>()
    private var currentSettings: MultiAppScenarioMessage? = null
    private var lastGeneratedRevision = 0L
    private var nextDark = true
    private val samples = LinkedHashMap<String, MultiAppScenarioMessage>()
    private val alerts = LinkedHashMap<String, MultiAppScenarioMessage>()

    private enum class Phase { ASSIGNED, ACCEPTED, COMPLETED }
    private data class Order(val command: MultiAppScenarioMessage, var phase: Phase)
    private data class Pending(val command: MultiAppScenarioMessage, val result: CompletableDeferred<Unit>)
    private data class PendingSettings(val command: MultiAppScenarioMessage,
        val result: CompletableDeferred<Unit>, val confirmed: MutableSet<String> = LinkedHashSet())

    val view: View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 16) }
        addView(makeCard(WORK_ORDER, "仓储工单", when (clientId) {
            "client_1" -> "调度台：下发工单，观察执行屏接受和完成。"
            "client_2" -> "执行屏：接受工单后，点击完成回传结果。"
            else -> "运维屏：观察工单接受和完成。"
        }).apply {
            if (clientId == "client_1") addView(button(WORK_ORDER, "assign", "向执行屏下发工单"))
            if (clientId == "client_2") addView(button(WORK_ORDER, "complete", "完成当前工单"))
            addView(note("完成只更新本页面工单状态；不连接仓储系统、不扣减库存。"))
        })
        addView(makeCard(SETTINGS, "配置同步", when (clientId) {
            "client_1" -> "配置中心：同步案例卡主题与字号，等待两端确认。"
            else -> "显示端：按递增版本更新自己的案例卡，然后回传确认。"
        }).apply {
            if (clientId == "client_1") addView(button(SETTINGS, "apply", "同步主题与字号（深/浅交替）"))
            addView(note("主题、字号、版本只在当前页面内存中；重建页面后不保证版本连续。"))
        })
        addView(makeCard(TELEMETRY, "遥测告警", when (clientId) {
            "client_1" -> "调度台：观察规则引擎广播的高温告警。"
            "client_2" -> "采样端：按钮发送预置温度，观察高温告警。"
            else -> "规则引擎：显示最近样本，温度达到 30°C 时广播告警。"
        }).apply {
            if (clientId == "client_2") {
                addView(button(TELEMETRY, "normal", "发送正常温度 25°C"))
                addView(button(TELEMETRY, "hot", "发送高温 35°C"))
            }
            addView(note("温度来自按钮预置演示值，无硬件传感器；正常样本不会清除其他端已有告警。"))
        })
        selectScenario(WORK_ORDER)
    }

    private fun makeCard(scenario: String, title: String, initial: String) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(20, 16, 20, 16)
        setBackgroundColor(Color.WHITE)
        cards[scenario] = this
        addView(TextView(context).apply { text = "多 App 案例：$title"; textSize = 16f; typeface = Typeface.DEFAULT_BOLD })
        val status = TextView(context).apply {
            text = initial
            textSize = 13f
            setPadding(0, 8, 0, 8)
            contentDescription = "scenario-$scenario-status"
        }
        statuses[scenario] = status
        addView(status)
    }

    private fun button(scenario: String, action: String, label: String) = Button(context).apply {
        text = label
        textSize = 12f
        contentDescription = "scenario-$scenario-$action"
        setOnClickListener { trigger(scenario, action) }
    }

    private fun note(label: String) = TextView(context).apply {
        text = label; textSize = 11f; setTextColor(Color.DKGRAY)
    }

    fun selectScenario(scenario: String) {
        cards.forEach { (key, card) -> card.visibility = if (key == scenario) View.VISIBLE else View.GONE }
    }

    fun onConnection(generation: Long?) {
        if (connectionGeneration == generation) return
        if (connectionGeneration != null) {
            job.cancelChildren()
            operations.clear()
            replyJobs.clear()
            pendingOrder?.result?.cancel()
            pendingSettings?.result?.cancel()
            val order = pendingOrder?.command ?: currentOrderId?.let { orders[it]?.command }
            val settings = pendingSettings?.command ?: currentSettings
            for (scenario in cards.keys) {
                val command = when (scenario) { WORK_ORDER -> order; SETTINGS -> settings; else -> samples.values.lastOrNull() }
                statuses.getValue(scenario).text = "连接已变更：未完成请求的结果未知；下面只保留本地历史。\n" +
                    statuses.getValue(scenario).text
                event("connection_lost", command, scenario = scenario, detail = "Connection changed; pending results unknown")
            }
            pendingOrder = null
            pendingSettings = null
        }
        connectionGeneration = generation
    }

    fun trigger(scenario: String, action: String, commandId: String = UUID.randomUUID().toString(),
        title: String? = null, detail: String? = null, revision: Long? = null, value: Int? = null) {
        if (scenario !in cards) { event("rejected", scenario = scenario, detail = "Unknown scenario"); return }
        val permitted = when (scenario) {
            WORK_ORDER -> action == "assign" && clientId == "client_1" || action == "complete" && clientId == "client_2"
            SETTINGS -> action == "apply" && clientId == "client_1"
            TELEMETRY -> action in setOf("normal", "hot") && clientId == "client_2"
            else -> false
        }
        if (!permitted) { event("rejected", scenario = scenario, detail = "Invalid action or publishing role"); return }
        val generation = connectionGeneration
        if (generation == null) {
            statuses.getValue(scenario).text = "尚未连接：请先连接中枢。"
            event("error", scenario = scenario, detail = "Not connected"); return
        }
        if (operations[scenario]?.isActive == true) {
            event("rejected", scenario = scenario, detail = "A scenario request is still pending"); return
        }
        if (scenario == WORK_ORDER && action == "complete") { completeOrder(generation); return }
        if (scenario == SETTINGS && revision == null && lastGeneratedRevision == Long.MAX_VALUE) {
            event("rejected", scenario = scenario, detail = "Revision exhausted"); return
        }
        val message = when (scenario) {
            WORK_ORDER -> MultiAppScenarioMessage(scenario, ORDER_ASSIGN, commandId,
                title ?: "出库复核工单", detail ?: "复核 A-01 货位并完成出库", revision ?: 1, value ?: 0)
            SETTINGS -> {
                val theme = detail ?: if (nextDark) "dark" else "light"
                MultiAppScenarioMessage(scenario, SETTINGS_APPLY, commandId, title ?: "案例卡显示配置", theme,
                    revision ?: lastGeneratedRevision + 1, value ?: if (theme == "dark") 18 else 14)
            }
            else -> MultiAppScenarioMessage(scenario, TELEMETRY_SAMPLE, commandId, title ?: "仓库温度",
                detail ?: "按钮预置温度样本", revision ?: 1, value ?: if (action == "hot") 35 else 25)
        }
        val encoded = try { message.encode() } catch (error: IllegalArgumentException) {
            event("rejected", message, detail = error.message.orEmpty()); return
        }
        when (scenario) {
            WORK_ORDER -> assignOrder(message, generation, encoded)
            SETTINGS -> applySettings(message, generation, encoded)
            else -> sendSample(message, generation, encoded)
        }
    }

    private fun assignOrder(message: MultiAppScenarioMessage, generation: Long, encoded: String) {
        val old = orders[message.commandId]
        if (old != null && old.command != message) { reject(message, "commandId payload conflict"); return }
        if (old == null) remember(orders, message.commandId, Order(message, Phase.ASSIGNED))
        currentOrderId = message.commandId
        val waiter = CompletableDeferred<Unit>()
        pendingOrder = Pending(message, waiter)
        if (old?.phase != Phase.COMPLETED) show(message, "等待执行屏接受（最多 5 秒）")
        launchOperation(WORK_ORDER) {
            try {
                withTimeout(5000) { route(generation, "client_2", message, encoded); waiter.await() }
            } catch (_: TimeoutCancellationException) {
                if (!confirmed(waiter)) {
                    show(message, "5 秒内未完成接受确认，结果未知；不会自动重发")
                    event("error", message, "client_2", "Acceptance deadline exceeded")
                } else event("route_result", message, "client_2", "Route callback unknown; receiver already confirmed")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (!confirmed(waiter)) { show(message, "请求失败或结果未知：${error.message}"); event("error", message, "client_2", error.message.orEmpty()) }
                else event("route_result", message, "client_2", "Route callback failed; receiver already confirmed")
            } finally {
                if (pendingOrder?.command === message) pendingOrder = null
                waiter.cancel()
            }
        }
    }

    private fun completeOrder(generation: Long) {
        val order = currentOrderId?.let { orders[it] }
        if (order == null || order.phase == Phase.ASSIGNED) {
            event("rejected", scenario = WORK_ORDER, detail = "No accepted work order"); return
        }
        val message = order.command.copy(kind = ORDER_COMPLETED)
        order.phase = Phase.COMPLETED
        show(message, "工单已完成")
        event("completed", message, MessageEnvelope.TARGET_ALL, "UI data applied; manual completion")
        launchOperation(WORK_ORDER) { sendWithoutAck(generation, MessageEnvelope.TARGET_ALL, message) }
    }

    private fun applySettings(message: MultiAppScenarioMessage, generation: Long, encoded: String) {
        val old = settingsCommands[message.commandId]
        if (old != null && old != message) { reject(message, "commandId payload conflict"); return }
        if (message.revision <= lastGeneratedRevision && old == null) {
            reject(message, "New configuration must increase this Activity's revision"); return
        }
        remember(settingsCommands, message.commandId, message)
        lastGeneratedRevision = maxOf(lastGeneratedRevision, message.revision)
        nextDark = message.detail != "dark"
        val pending = PendingSettings(message, CompletableDeferred())
        pendingSettings = pending
        show(message, "等待显示端确认（最多 5 秒）：client_2、client_3")
        launchOperation(SETTINGS) {
            try {
                withTimeout(5000) { route(generation, MessageEnvelope.TARGET_ALL, message, encoded); pending.result.await() }
            } catch (_: TimeoutCancellationException) {
                if (!confirmed(pending.result)) {
                    val missing = setOf("client_2", "client_3") - pending.confirmed
                    show(message, "5 秒内未确认：${missing.joinToString("、")}；结果未知，不会自动重发")
                    event("error", message, MessageEnvelope.TARGET_ALL, "Missing confirmations: ${missing.joinToString(",")}")
                } else event("route_result", message, MessageEnvelope.TARGET_ALL, "Route callback unknown; both receivers confirmed")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (!confirmed(pending.result)) {
                    val missing = setOf("client_2", "client_3") - pending.confirmed
                    show(message, "请求失败或结果未知：${error.message}\n未确认：${missing.joinToString("、")}")
                    event("error", message, MessageEnvelope.TARGET_ALL, error.message.orEmpty())
                } else event("route_result", message, MessageEnvelope.TARGET_ALL, "Route callback failed; both receivers confirmed")
            } finally {
                if (pendingSettings === pending) pendingSettings = null
                pending.result.cancel()
            }
        }
    }

    private fun sendSample(message: MultiAppScenarioMessage, generation: Long, encoded: String) {
        val old = samples[message.commandId]
        if (old != null && old != message) { reject(message, "commandId payload conflict"); return }
        remember(samples, message.commandId, message)
        show(message, "发送预置温度 ${message.value}°C；接收结果请在规则引擎查看")
        launchOperation(TELEMETRY) { sendWithoutAck(generation, "client_3", message, encoded) }
    }

    fun receive(envelope: MessageEnvelope): Boolean {
        val message = MultiAppScenarioMessage.decode(envelope.content) ?: return false
        event("received", message, envelope.target, "from=${envelope.fromId}")
        if (connectionGeneration == null) { reject(message, "No current connection", envelope.target); return true }
        val valid = when (message.kind) {
            ORDER_ASSIGN -> clientId == "client_2" && envelope.fromId == "client_1" && envelope.target == "client_2"
            ORDER_ACCEPTED, ORDER_COMPLETED -> clientId in setOf("client_1", "client_3") &&
                envelope.fromId == "client_2" && envelope.target == MessageEnvelope.TARGET_ALL
            SETTINGS_APPLY -> clientId in setOf("client_2", "client_3") &&
                envelope.fromId == "client_1" && envelope.target == MessageEnvelope.TARGET_ALL
            SETTINGS_APPLIED -> clientId == "client_1" && envelope.fromId in setOf("client_2", "client_3") && envelope.target == "client_1"
            TELEMETRY_SAMPLE -> clientId == "client_3" && envelope.fromId == "client_2" && envelope.target == "client_3"
            TELEMETRY_ALERT -> clientId in setOf("client_1", "client_2") && envelope.fromId == "client_3" && envelope.target == MessageEnvelope.TARGET_ALL
            else -> false
        }
        if (!valid) { reject(message, "Invalid message role or route", envelope.target); return true }
        when (message.kind) {
            ORDER_ASSIGN -> receiveOrder(message, envelope.target)
            ORDER_ACCEPTED, ORDER_COMPLETED -> receiveOrderUpdate(message, envelope.target)
            SETTINGS_APPLY -> receiveSettings(message, envelope.target)
            SETTINGS_APPLIED -> receiveSettingsAck(message, envelope.fromId, envelope.target)
            TELEMETRY_SAMPLE -> receiveSample(message, envelope.target)
            TELEMETRY_ALERT -> receiveAlert(message, envelope.target)
        }
        return true
    }

    private fun receiveOrder(message: MultiAppScenarioMessage, target: String) {
        val old = orders[message.commandId]
        if (old != null && old.command != message) { reject(message, "commandId payload conflict", target); return }
        if (old == null) remember(orders, message.commandId, Order(message, Phase.ACCEPTED))
        currentOrderId = message.commandId
        val complete = old?.phase == Phase.COMPLETED
        val confirmation = message.copy(kind = if (complete) ORDER_COMPLETED else ORDER_ACCEPTED)
        show(confirmation, if (complete) "工单已完成" else "工单已接受")
        event(if (complete) "completed" else "applied", if (complete) confirmation else message, target,
            if (old == null) "UI data applied" else "Already applied")
        sendReply(confirmation, MessageEnvelope.TARGET_ALL)
    }

    private fun receiveOrderUpdate(message: MultiAppScenarioMessage, target: String) {
        val canonical = message.copy(kind = ORDER_ASSIGN)
        val old = orders[message.commandId]
        if (old != null && old.command != canonical) { reject(message, "commandId payload conflict", target); return }
        if (clientId == "client_1" && old == null) { reject(message, "Unknown work order", target); return }
        if (old?.phase == Phase.COMPLETED && message.kind == ORDER_ACCEPTED) {
            event("stale_ignored", message, target, "Completed work order cannot return to accepted"); return
        }
        val phase = if (message.kind == ORDER_COMPLETED) Phase.COMPLETED else Phase.ACCEPTED
        if (old == null) remember(orders, message.commandId, Order(canonical, phase)) else old.phase = phase
        val pending = pendingOrder
        if (clientId == "client_1" && pending?.command == canonical) pending.result.complete(Unit)
        val isCurrent = clientId == "client_3" || currentOrderId == message.commandId
        if (isCurrent) {
            currentOrderId = message.commandId
            show(message, if (phase == Phase.COMPLETED) {
                if (clientId == "client_1") "执行屏已完成工单" else "已观测工单完成"
            } else if (clientId == "client_1") "执行屏已接受工单" else "已观测工单接受")
        }
        event(if (phase == Phase.COMPLETED) "completed" else "acknowledged", message, target,
            if (pending?.command == canonical) "Matched command confirmation" else "Historical work order update")
    }

    private fun receiveSettings(message: MultiAppScenarioMessage, target: String) {
        val old = settingsCommands[message.commandId]
        if (old != null && old != message) { reject(message, "commandId payload conflict", target); return }
        val current = currentSettings
        if (current != null && message.revision < current.revision) {
            event("stale_ignored", message, target, "Older configuration revision"); return
        }
        if (current != null && message.revision == current.revision && message != current) {
            reject(message, "Same revision has a different command or payload", target); return
        }
        remember(settingsCommands, message.commandId, message)
        currentSettings = message
        val dark = message.detail == "dark"
        // Only this demo card changes, never the whole application theme.
        cards.getValue(SETTINGS).setBackgroundColor(Color.parseColor(if (dark) "#263238" else "#FFFFFF"))
        val card = cards.getValue(SETTINGS)
        for (index in 0 until card.childCount) {
            val text = card.getChildAt(index) as? TextView ?: continue
            if (text is Button) continue
            text.setTextColor(if (dark) Color.WHITE else Color.BLACK)
            text.textSize = message.value.toFloat()
        }
        show(message, "配置已应用（本页面）")
        event("applied", message, target, "UI data applied; theme=${message.detail}; size=${message.value}")
        sendReply(message.copy(kind = SETTINGS_APPLIED), "client_1")
    }

    private fun receiveSettingsAck(message: MultiAppScenarioMessage, sender: String, target: String) {
        val expected = pendingSettings
        if (expected == null || message.copy(kind = SETTINGS_APPLY) != expected.command) {
            reject(message, "Unmatched or late configuration confirmation", target); return
        }
        expected.confirmed.add(sender)
        val complete = expected.confirmed.containsAll(setOf("client_2", "client_3"))
        show(message, if (complete) "配置确认完成：client_2、client_3"
            else "已确认：${expected.confirmed.joinToString("、")}；等待其余显示端")
        if (complete) expected.result.complete(Unit)
        event("acknowledged", message, target, "from=$sender; confirmed=${expected.confirmed.joinToString(",")}")
    }

    private fun receiveSample(message: MultiAppScenarioMessage, target: String) {
        val old = samples[message.commandId]
        if (old != null && old != message) { reject(message, "commandId payload conflict", target); return }
        remember(samples, message.commandId, message)
        show(message, "最近样本：${message.value}°C（按钮演示值）")
        event("sample_visible", message, target, "UI data applied; no physical sensor")
        if (message.value >= MultiAppScenarioMessage.ALERT_THRESHOLD) {
            sendReply(message.copy(kind = TELEMETRY_ALERT), MessageEnvelope.TARGET_ALL)
        }
    }

    private fun receiveAlert(message: MultiAppScenarioMessage, target: String) {
        val old = alerts[message.commandId]
        if (old != null && old != message) { reject(message, "commandId payload conflict", target); return }
        if (clientId == "client_2" && samples[message.commandId]?.copy(kind = TELEMETRY_ALERT) != message) {
            reject(message, "Alert does not match a local sample", target); return
        }
        remember(alerts, message.commandId, message)
        show(message, "高温告警：${message.value}°C（阈值30°C）")
        event("alert_visible", message, target, if (old == null) "UI data applied" else "Already applied")
    }

    private fun sendReply(message: MultiAppScenarioMessage, target: String) {
        val generation = connectionGeneration ?: return
        // At most one local response waiter per scenario. A started RPC may still complete remotely.
        replyJobs[message.scenario]?.cancel()
        replyJobs[message.scenario] = scope.launch { sendWithoutAck(generation, target, message) }
    }

    private suspend fun sendWithoutAck(generation: Long, target: String, message: MultiAppScenarioMessage,
        encoded: String = message.encode()) {
        try { withTimeout(5000) { route(generation, target, message, encoded) } }
        catch (_: TimeoutCancellationException) { event("error", message, target, "Route deadline exceeded; receiver result unknown") }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { event("error", message, target, error.message.orEmpty()) }
    }

    private suspend fun route(generation: Long, target: String, message: MultiAppScenarioMessage, encoded: String) {
        // Snapshot was captured before launch. The host additionally rejects a changed generation.
        check(connectionGeneration == generation) { "Connection changed before send" }
        event("send", message, target)
        val result = send(generation, target, encoded)
        event("route_result", message, target, result)
        check(result.startsWith("OK:")) { result }
    }

    private fun launchOperation(scenario: String, block: suspend () -> Unit) {
        operations[scenario] = scope.launch { block() }
    }

    private fun confirmed(result: CompletableDeferred<Unit>) = result.isCompleted && !result.isCancelled

    private fun show(message: MultiAppScenarioMessage, label: String) {
        statuses.getValue(message.scenario).text = "$label\n${message.title}\n${message.detail}\n" +
            "revision：${message.revision}\nvalue：${message.value}\ncommandId：${message.commandId}"
    }

    private fun reject(message: MultiAppScenarioMessage, detail: String, target: String = "") =
        event("rejected", message, target, detail)

    private fun <T> remember(map: LinkedHashMap<String, T>, id: String, item: T) {
        map[id] = item
        if (map.size > 64) map.remove(map.keys.first())
    }

    private fun event(event: String, message: MultiAppScenarioMessage? = null, target: String = "",
        detail: String = "", scenario: String = message?.scenario.orEmpty()) {
        Log.i("IPC_SCENARIO_CASE", JSONObject().put("event", event).put("clientId", clientId)
            .put("scenario", scenario).put("commandId", message?.commandId.orEmpty()).put("kind", message?.kind.orEmpty())
            .put("target", target).put("detail", detail).put("revision", message?.revision ?: 0L)
            .put("value", message?.value ?: 0).toString())
    }
}
