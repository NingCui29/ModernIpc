package com.cn.ipc.annotations

/**
 * 标记一个业务接口为 IPC 门面（Facade）。
 * 这是一套用于简化跨进程通信的注解体系的核心。
 *
 * KSP 编译器插件（ipc-compiler）将扫描所有带有此注解的接口，并自动生成：
 * - **Client Adapter**：供调用方进程使用的代理实现，将接口调用转发为跨进程 RPC 请求。
 * - **Server Stub**：供服务方进程继承并实现的抽象类，处理来自 Broker 的调用分发。
 * - **IpcSchema**：客户端与服务端共享的事务签名；服务注册仍由宿主完成。
 *
 * ### 使用示例
 * ```kotlin
 * @IpcFacade(serviceId = 1001, minApiVersion = 2)
 * interface IUserService {
 *     @IpcAsync(requestTransaction = 10, cancelTransaction = 11)
 *     suspend fun getUserInfo(userId: String): String
 * }
 * ```
 *
 * > **注意**：[serviceId] 一旦分配并上线，严禁修改或复用，否则会导致跨版本服务发现失败。
 *
 * @property serviceId      服务的全局唯一整型 ID。由团队统一分配，永久固定，不可复用。
 * @property minApiVersion  该接口向后兼容的最低服务端 API 版本，默认为 1。
 *                          客户端请求时若服务端版本低于此值，Broker 将拒绝连接，以保证接口契约的安全性。
 * @property aidlInterface  （可选）关联的底层 AIDL 接口全类名，例如 "com.cn.ipc.IUserService"。
 *                          当前生成器保留此字段但不使用；业务 Binder 的 descriptor 来自门面全类名。
 * @property contractVersion 客户端自身的业务契约版本，必须为正数；0 表示沿用 minApiVersion。
 *                           与服务端 API 版本下限不同，用于判断服务端是否接受该客户端契约。
 */
@Target(AnnotationTarget.CLASS) // 此注解仅可用于类或接口（通常是接口）
@Retention(AnnotationRetention.SOURCE) // KSP 编译期处理后即被抛弃
annotation class IpcFacade(
    val serviceId: Int,
    val minApiVersion: Int = 1,
    val aidlInterface: String = "",
    val contractVersion: Int = 0
)
