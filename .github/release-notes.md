# ModernIpc 3.0.0-rc.3

预发行版，版本分支 `release/v3.0.0-rc.3`，Android versionCode 为 `30003`。本次集中修复连接生命周期、生成代码、订阅清理与 API 21 兼容问题，并完善全量验证和外部 Maven 消费流程。

## 变更

- awaitConnected 绑定独立连接尝试，关闭结束旧等待；重试耗尽明确失败，新尝试不受旧终态影响。
- Binder 死亡遵循零次/有限重连预算；DeadObjectException 带消息构造器增加 API 23 守卫。
- KSP 隔离框架与业务参数名，保留原业务参数诊断；拒绝不支持的嵌套门面并给出明确错误。
- Flow 业务取消保留错误原因；事件 transact 失败停止生产并进入终态清理。
- 订阅旧代次按记录身份清理，死亡监听解绑及用户回调置于管理锁外；注册表原子注册、关闭后拒绝注册并锁外去重释放。
- 冷发现最多 4 个执行线程＋4 个排队任务，取消/失效回收排队容量；保留严格双 RPC 校验。
- Broker 复用鉴权对象并去掉无用身份分配，逐事务鉴权保留，没有永久 UID 放行缓存。
- 兼容旧 Android 的锁屏检测、原子操作、统计采样与日志标签；明确 Parcelable 版本下限和字段演进边界。
- Java 17 启动/测试约束；verify/release 使用全量子模块 check；Maven 消费脚本自动探测根 local.properties SDK。
- 更新架构、SDK、审计、回归文档及真实成功/失败证据。

## 全部交付物

六 SDK：ipc-annotations、ipc-compiler、ipc-contract、ipc-runtime-client、ipc-runtime-server、ipc-api；示例共用库 demo-client-common。交付七模块主制品、sources/javadoc、POM/Gradle metadata，以及六个同签名 Debug APK（demo-app、app-server、app-client1/2/3、benchmark-andlinker）。

Release 附带 Maven 仓库、源码、仓库证据、manifest 与 SHA256SUMS。额外完整历史证据 ZIP 含本地保留的 APK、原始日志、Perfetto 和失败尝试，附逐文件清单及独立校验和。

坐标：`com.modernipc:<module>:3.0.0-rc.3`。Maven 仓库为 https://maven.pkg.github.com/NingCui29/ModernIpc ，下载需要具有 read:packages 权限的 GitHub 凭据。

## 验证范围

11/03 发布前工作区完整 check 通过，包含 27 项状态机和 debug/release 各 16 组 Robolectric；13 项 Python 回归通过。无 SDK 环境变量/无 --android-sdk 参数的 metadata、POM-only 消费与嵌套接口负例全部通过。

小米 925c23bb 上的 86 项回归绑定 APK SHA256 `BEC94D05275485B8EB6542D74A5A6C77D1E920AA6D4A7287E8B79A7464C4FD66`。此前四 App 跨 UID 冷启动冒烟通过注册/订阅门禁，但不证明每项业务消息投递。原始失败记录保留。

这些历史设备结果不迁移至版本升级或 CI 重新签名后的 APK。发布 APK 的设备验证状态为 NOT_RUN，API 21/22 仅有静态兼容检查；本次无新 AndLinker 性能对照或跨 UID 延迟结论。远端构建、七库发布及附件校验以 Actions 和 docs/releases/v3.0.0-rc.3 为准。

## 兼容与迁移

双方重新运行 KSP 才能获得生成代码修复。公开方法参数名和既有 wire 格式保持；已生成错误代码需要重新编译升级。辅助注册表 dispose 后不再接收注册，重复 dispose 不承诺等待其他并发释放完成；broadcast 忽略 RemoteException，其他异常继续向调用方传播。

同步 Binder 调用不能可靠强制中断。Flow 的本地 buffer 配置不等于跨进程信用式背压；通道失效时无法保证终态送达。Parcelable 字段布局仍由业务维护，破坏性变更须保留旧编码/新事务，或提高服务允许的最低客户端契约版本。
