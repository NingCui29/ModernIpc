package com.cn.ipc.client

import com.cn.ipc.IIpcBroker
import com.cn.ipc.ProtocolInfo

/**
 * 表示 IPC 客户端状态机的各个离散状态。
 *
 * 状态机遵循以下转换逻辑：
 * Idle -> Binding -> Connected -> Reconnecting -> Disconnected
 * 任何状态都可以直接转换为 Closed。
 */
sealed class IpcClientState {
    /** 初始状态，尚未发起连接 */
    object Idle : IpcClientState()

    /** 正在绑定服务（调用了 bindService 但未收到 onServiceConnected，或正在握手） */
    object Binding : IpcClientState()

    /**
     * 连接已建立且握手成功。
     *
     * @property generation 当前连接的生命周期代次。每次重新连接都会生成一个新的代次，
     *                      用于隔离旧的回调、待处理请求和死亡通知。
     * @property broker     服务端 Broker 代理实例。
     * @property protocol   握手协商后的协议信息。
     */
    data class Connected(
        val generation: Long,
        val broker: IIpcBroker,
        val protocol: ProtocolInfo
    ) : IpcClientState()

    /** 连接意外断开（Binder 死亡或解绑），正在等待重连策略执行（如 Backoff 期间） */
    data class Reconnecting(val attempt: Int) : IpcClientState()

    /** 连接已断开且不再自动重连，或连接尝试已达到上限 */
    object Disconnected : IpcClientState()

    /** 当前连接已关闭，可以通过 connect 再次连接。 */
    object Closed : IpcClientState()

    /** 控制器已永久释放；owner scope 结束时也会进入此状态。 */
    object Disposed : IpcClientState()
}
