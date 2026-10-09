package com.cn.ipc.demo

import com.cn.ipc.api.test.IUserService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.atomic.AtomicInteger

/**
 * 在服务端进程中执行的真实业务实现类。
 * 实现了 [IUserService] 接口定义的所有跨进程调用方法。
 */
class UserServiceImpl(private val scope: CoroutineScope) : IUserService {
    
    // 用于生成唯一用户 ID 或计数的原子变量
    private val counter = AtomicInteger(0)

    /**
     * 测试服务端崩溃的接口。
     * 调用此方法将导致服务端进程在短暂停留后强制退出。
     */
    override fun ping() {
        println("Server: ping() called. CRASHING SERVER NOW!")
        // 模拟服务端进程意外崩溃
        Thread {
            Thread.sleep(100)
            Runtime.getRuntime().halt(0)
        }.start()
    }

    /**
     * 测试 Oneway (单向) 消息投递的接口。
     * @param msg 需要打印的日志消息
     */
    override fun logMessage(msg: String) {
        println("Server: received oneway logMessage -> $msg")
    }

    /**
     * 获取大批量数据的接口，用于测试大数据量跨进程传输。
     * @param sizeInBytes 需要生成的数据字节大小
     * @return 包含指定字节大小的字符串数据
     */
    override suspend fun getLargeData(sizeInBytes: Int): String {
        println("Server: generating $sizeInBytes bytes of data...")
        return "A".repeat(sizeInBytes)
    }

    /**
     * 获取用户信息的挂起函数，模拟高耗时操作。
     * @param userId 要查询的用户 ID
     * @return 模拟生成的用户信息字符串
     */
    override suspend fun getUserInfo(userId: String): String {
        println("Server: getUserInfo($userId) executing on thread: ${Thread.currentThread().name}")
        // 模拟高耗时数据库查询或网络请求
        delay(1000)
        val count = counter.incrementAndGet()
        return "DemoUser-$count (age: ${20 + count})"
    }

    // 全局共享流，用于向所有客户端广播系统通知
    private val globalSharedFlow = kotlinx.coroutines.flow.MutableSharedFlow<String>(
        extraBufferCapacity = 10,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )

    init {
        // 后台定时发送全局广播，以模拟服务端的全局事件
        scope.launch {
            var tick = 0
            while (true) {
                delay(1000)
                globalSharedFlow.tryEmit("【系统广播】全网通知：消息 #${tick++}")
            }
        }
    }

    /**
     * 订阅指定用户的状态变化流。
     * @param userId 目标用户 ID
     * @return 发送用户状态变化的 [Flow]
     */
    override fun observeUserStatus(userId: String): Flow<Int> = flow {
        println("Server: observeUserStatus($userId) started collecting")
        var status = 0
        while (true) {
            emit(status++)
            delay(500) // 每500ms发送一次状态变更
        }
    }

    /**
     * 订阅全局广播事件流。
     * 所有调用此接口的客户端将接收到相同的系统广播内容。
     * @return 发送全局广播的 [Flow]
     */
    override fun observeGlobalBroadcast(): Flow<String> {
        println("Server: A new client subscribed to global broadcast!")
        return globalSharedFlow
    }
}
