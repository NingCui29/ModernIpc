package com.cn.ipc.api.test

import com.cn.ipc.annotations.IpcAsync
import com.cn.ipc.annotations.IpcFacade
import com.cn.ipc.annotations.IpcOneway
import com.cn.ipc.annotations.IpcStream
import kotlinx.coroutines.flow.Flow

/**
 * 测试用的用户信息 DTO。
 *
 * 用于跨进程通信的数据传输对象 (Data Transfer Object)。
 * 注意：在实际业务中应放在 contract 层，并实现 Parcelable，以便能够在不同的进程间进行序列化和反序列化传输。
 *
 * @property id 用户的唯一标识符
 * @property name 用户的名称
 */
data class UserDto(val id: String, val name: String)

/**
 * 用户服务接口，定义了跨进程通信 (IPC) 的各种方法。
 * 
 * 这是一个 IPC 门面 (Facade) 接口，服务ID配置为 1001，最低支持的 API 版本为 1。
 * 客户端将通过该接口与服务端进行通信，框架会自动生成相关的代理类和 Stub 类。
 */
@IpcFacade(serviceId = 1001, minApiVersion = 2)
interface IUserService {

    // 1. 简单的单向通信（不需要返回值）
    
    /**
     * 发送简单的 ping 请求（单向通信）。
     * 该方法无需服务端返回结果，主要用于测试或触发服务端特定的行为（例如触发 Crash）。
     * 配置事务ID为 1。
     */
    @IpcOneway(transaction = 1)
    fun ping()

    /**
     * 向服务端发送日志消息（单向通信）。
     * 用于正常的单向通信，客户端不需要知道服务端是否处理完成。
     * 
     * @param msg 需要记录的日志内容
     */
    @IpcOneway(transaction = 2)
    fun logMessage(msg: String)

    // 2. 异步挂起请求
    
    /**
     * 异步获取用户信息。
     * 这是一个挂起函数，支持协程，允许请求被取消，并且是幂等操作。
     *
     * @param userId 需要查询的目标用户ID
     * @return 返回用户信息的字符串表示（在实际应用中可能返回 [UserDto]）
     */
    @IpcAsync(requestTransaction = 10, cancelTransaction = 11, idempotent = true)
    suspend fun getUserInfo(userId: String): String

    /**
     * 异步获取大量数据，用于大数据传输性能测试。
     * 同样是支持协程取消的幂等操作。
     *
     * @param sizeInBytes 请求返回的数据大小（单位为字节）
     * @return 返回指定大小的字符串数据
     */
    @IpcAsync(requestTransaction = 12, cancelTransaction = 13, idempotent = true)
    suspend fun getLargeData(sizeInBytes: Int): String 

    // 3. 流式订阅
    
    /**
     * 订阅指定用户的状态变化流。
     * 建立一条持续的通信管道，一旦服务端的用户状态发生变化，就会通过 Flow 派发给客户端。
     *
     * @param userId 需要观察其状态的用户ID
     * @return 返回一个不断发出用户状态 (Int) 的数据流 [Flow]
     */
    @IpcStream(subscribeTransaction = 20, unsubscribeTransaction = 21)
    fun observeUserStatus(userId: String): Flow<Int>

    /**
     * 订阅全局广播事件流。
     * 允许客户端监听由服务端发出的全局消息或事件通知。
     *
     * @return 返回一个包含全局广播内容 (String) 的数据流 [Flow]
     */
    @IpcStream(subscribeTransaction = 22, unsubscribeTransaction = 23)
    fun observeGlobalBroadcast(): Flow<String>
}
