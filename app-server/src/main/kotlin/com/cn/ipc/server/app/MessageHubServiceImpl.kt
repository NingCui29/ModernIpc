package com.cn.ipc.server.app

import com.cn.ipc.api.hub.IMessageHubService
import com.cn.ipc.api.hub.MessageEnvelope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 客户端会话数据模型。
 */
data class ClientSession(
    val clientId: String,
    val clientName: String,
    val connectTime: Long = System.currentTimeMillis(),
    var lastPingTime: Long = System.currentTimeMillis()
)

/**
 * 服务端多端通信中枢实现类。
 * 集中管理所有客户端的会话、路由规则、订阅通道与消息派发。
 */
class MessageHubServiceImpl private constructor() : IMessageHubService {

    companion object {
        val instance = MessageHubServiceImpl()
    }

    // 在线客户端会话表 (clientId -> ClientSession)
    val onlineClients = ConcurrentHashMap<String, ClientSession>()

    // 各客户端独立的消息下发流 (clientId -> Flow)
    private val clientFlows = ConcurrentHashMap<String, MutableSharedFlow<String>>()

    // 统计指标
    val totalRouted = AtomicInteger(0)
    val totalBroadcast = AtomicInteger(0)
    val totalTargeted = AtomicInteger(0)
    val totalServerOnly = AtomicInteger(0)

    private val messageCounter = AtomicLong(1000)
    private val serverStartTime = System.currentTimeMillis()

    // 供 ServerMainActivity 监听日志与在线状态更新的回调
    var onServerEvent: ((tag: String, message: String) -> Unit)? = null

    private fun getOrCreateFlow(clientId: String): MutableSharedFlow<String> {
        synchronized(clientFlows) {
            var flow = clientFlows[clientId]
            if (flow == null) {
                flow = MutableSharedFlow(
                    extraBufferCapacity = 128,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST
                )
                clientFlows[clientId] = flow
            }
            return flow
        }
    }

    override fun registerClient(clientId: String, clientName: String) {
        val session = ClientSession(clientId, clientName)
        onlineClients[clientId] = session
        getOrCreateFlow(clientId) // 预分配通信管道

        val logMsg = "客户端上线: $clientName [$clientId]"
        notifyUi("上线", logMsg)

        // 向其余在线客户端广播上线通知
        val notice = MessageEnvelope(
            messageId = "SYS_${messageCounter.getAndIncrement()}",
            fromId = "SERVER",
            fromName = "服务端",
            target = MessageEnvelope.TARGET_ALL,
            content = "🔔 提示: [$clientName] 已连接上线"
        )
        dispatchMessage(notice, excludeSender = clientId)
    }

    override fun unregisterClient(clientId: String) {
        val session = onlineClients.remove(clientId)
        clientFlows.remove(clientId)

        val name = session?.clientName ?: clientId
        notifyUi("下线", "客户端已断开: $name [$clientId]")

        val notice = MessageEnvelope(
            messageId = "SYS_${messageCounter.getAndIncrement()}",
            fromId = "SERVER",
            fromName = "服务端",
            target = MessageEnvelope.TARGET_ALL,
            content = "🔕 提示: [$name] 已断开离线"
        )
        dispatchMessage(notice, excludeSender = clientId)
    }

    override suspend fun sendMessage(fromClientId: String, targetScope: String, content: String): String {
        val senderSession = onlineClients[fromClientId]
        val fromName = senderSession?.clientName ?: fromClientId
        val msgId = "MSG_${messageCounter.getAndIncrement()}"
        totalRouted.incrementAndGet()

        val envelope = MessageEnvelope(
            messageId = msgId,
            fromId = fromClientId,
            fromName = fromName,
            target = targetScope,
            content = content
        )

        when (targetScope) {
            MessageEnvelope.TARGET_ALL -> {
                // 【全员广播模式】：推给所有已连接客户端（除发送方外）
                totalBroadcast.incrementAndGet()
                notifyUi("广播路由", "📢 [$fromName] -> [全员广播]: $content")
                dispatchMessage(envelope, excludeSender = fromClientId)
                return "OK: Broadcast delivered to all clients"
            }
            MessageEnvelope.TARGET_SERVER -> {
                // 【仅服务端模式】：只留在服务端记录，绝不向任何客户端派发
                totalServerOnly.incrementAndGet()
                notifyUi("机密上报", "🔒 [$fromName] -> [仅服务端可见]: $content")
                return "OK: Server received confidential report"
            }
            else -> {
                // 【定向推送模式】：仅且仅推给指定的目标客户端
                totalTargeted.incrementAndGet()
                val targetSession = onlineClients[targetScope]
                if (targetSession != null) {
                    notifyUi("定向路由", "🎯 [$fromName] -> [${targetSession.clientName}]: $content")
                    val targetFlow = getOrCreateFlow(targetScope)
                    targetFlow.tryEmit(envelope.encode())
                    return "OK: Delivered to ${targetSession.clientName}"
                } else {
                    notifyUi("路由拦截", "⚠️ [$fromName] 发给 [$targetScope] 失败: 目标客户端当前不在线")
                    return "ERROR: Target client [$targetScope] offline"
                }
            }
        }
    }

    override suspend fun getOnlineClients(): String {
        if (onlineClients.isEmpty()) return "暂无客户端在线"
        return onlineClients.values.joinToString(", ") { "${it.clientName}(${it.clientId})" }
    }

    override suspend fun getServerStatus(): String {
        val uptimeSec = (System.currentTimeMillis() - serverStartTime) / 1000
        return "运行时间: ${uptimeSec}s | 在线客户端数: ${onlineClients.size} | 路由消息总量: ${totalRouted.get()} (广播: ${totalBroadcast.get()}, 定向: ${totalTargeted.get()}, 仅服务端: ${totalServerOnly.get()})"
    }

    override fun observeMessages(clientId: String): Flow<String> {
        notifyUi("流订阅", "客户端 [$clientId] 开启了消息长连接监听")
        return getOrCreateFlow(clientId)
    }

    override fun ping(clientId: String) {
        val session = onlineClients[clientId]
        session?.lastPingTime = System.currentTimeMillis()
    }

    /**
     * 服务端主动向外部广播或推送消息。
     */
    fun sendFromServer(targetScope: String, content: String) {
        val msgId = "SYS_${messageCounter.getAndIncrement()}"
        val envelope = MessageEnvelope(
            messageId = msgId,
            fromId = "SERVER",
            fromName = "服务端控制台",
            target = targetScope,
            content = content
        )

        if (targetScope == MessageEnvelope.TARGET_ALL) {
            notifyUi("服务端广播", "📢 [服务端] -> [全员广播]: $content")
            dispatchMessage(envelope, excludeSender = null)
        } else {
            val targetFlow = clientFlows[targetScope]
            if (targetFlow != null) {
                notifyUi("服务端定向", "🎯 [服务端] -> [$targetScope]: $content")
                targetFlow.tryEmit(envelope.encode())
            } else {
                notifyUi("发送失败", "目标客户端 [$targetScope] 不在线")
            }
        }
    }

    private fun dispatchMessage(envelope: MessageEnvelope, excludeSender: String?) {
        val encoded = envelope.encode()
        clientFlows.forEach { (cId, flow) ->
            if (cId != excludeSender) {
                flow.tryEmit(encoded)
            }
        }
    }

    private fun notifyUi(tag: String, msg: String) {
        if (msg.contains("[BENCHMARK]")) return
        onServerEvent?.invoke(tag, msg)
    }
}
