package com.cn.ipc.api.hub

import com.cn.ipc.annotations.IpcAsync
import com.cn.ipc.annotations.IpcFacade
import com.cn.ipc.annotations.IpcOneway
import com.cn.ipc.annotations.IpcStream
import kotlinx.coroutines.flow.Flow

/**
 * 消息通信信封数据结构。
 * 封装在客户端与服务端之间流转的消息元数据。
 */
data class MessageEnvelope(
    val messageId: String,
    val fromId: String,
    val fromName: String,
    val target: String, // "ALL", "client_2", "client_3", "SERVER_ONLY"
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * 编码为紧凑的跨进程传输字符串 (Base64安全分隔符)。
     */
    fun encode(): String {
        return "$messageId\u0001$fromId\u0001$fromName\u0001$target\u0001$content\u0001$timestamp"
    }

    companion object {
        const val TARGET_ALL = "ALL"
        const val TARGET_SERVER = "SERVER_ONLY"

        /**
         * 从字符串解码消息信封。
         */
        fun decode(raw: String): MessageEnvelope? {
            val parts = raw.split("\u0001")
            if (parts.size < 6) return null
            return MessageEnvelope(
                messageId = parts[0],
                fromId = parts[1],
                fromName = parts[2],
                target = parts[3],
                content = parts[4],
                timestamp = parts[5].toLongOrNull() ?: System.currentTimeMillis()
            )
        }
    }
}

/**
 * 多端通信中枢服务门面 (Facade)。
 * 服务ID分配为 2001。
 */
@IpcFacade(serviceId = 2001, minApiVersion = 2)
interface IMessageHubService {

    /**
     * 客户端上线注册 (单向 Oneway)。
     * @param clientId 客户端唯一 ID (如 client_1, client_2, client_3)
     * @param clientName 客户端展示名称 (如 客户端 1)
     */
    @IpcOneway(transaction = 1)
    fun registerClient(clientId: String, clientName: String)

    /**
     * 客户端离线注销 (单向 Oneway)。
     * @param clientId 客户端唯一 ID
     */
    @IpcOneway(transaction = 2)
    fun unregisterClient(clientId: String)

    /**
     * 发送消息（支持广播 ALL、定向推送 client_X、或仅服务端 SERVER_ONLY）。
     * 这是一个带超时的挂起异步调用，返回服务端的投递回执字符串。
     *
     * @param fromClientId 发送方客户端 ID
     * @param targetScope 目标范围 ("ALL", "client_2", "client_3", "SERVER_ONLY")
     * @param content 消息文本内容
     * @return 投递状态确认字符串
     */
    @IpcAsync(requestTransaction = 10, cancelTransaction = 11, idempotent = false)
    suspend fun sendMessage(fromClientId: String, targetScope: String, content: String): String

    /**
     * 获取当前所有已连接在线客户端的概要信息列表。
     */
    @IpcAsync(requestTransaction = 12, cancelTransaction = 13, idempotent = true)
    suspend fun getOnlineClients(): String

    /**
     * 查询服务端状态信息（包括 PID、在线数、历史路由总量、开机运行时长等）。
     */
    @IpcAsync(requestTransaction = 14, cancelTransaction = 15, idempotent = true)
    suspend fun getServerStatus(): String

    /**
     * 订阅消息事件流。
     * 客户端调用此方法后，将持续接收服务端下发或路由的消息。
     *
     * @param clientId 订阅方的客户端 ID
     * @return 消息编码流 Flow<String>
     */
    @IpcStream(subscribeTransaction = 20, unsubscribeTransaction = 21)
    fun observeMessages(clientId: String): Flow<String>

    /**
     * 快速心跳打卡 (单向 Oneway)。
     */
    @IpcOneway(transaction = 3)
    fun ping(clientId: String)
}
