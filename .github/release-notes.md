# ModernIpc 3.0.0-rc.2

本次为预发行版，版本分支 `codex/v3.0.0-rc.2`，Android versionCode 为 `30002`。本版本包含 Async 请求管理与可选调度扩展、公开依赖导出修复、自动验证门禁，以及对应源码、文档和实测证据。SDK 版本与 Binder wire protocol 分别管理。

## 本次变更

- PendingCallRegistry 合并请求状态记录，保留原 Binder 取消、请求所有权与唯一终态。
- IpcRequestTracker 合并请求记录，修复迟到死亡通知误取消复用 key 的后续请求，保留 UID/callback/requestId 隔离与 128 个在途许可。
- 生成 Stub 增加可选 `asyncCoroutineScope`，默认回落原 `coroutineScope`；Flow 沿用原 scope，默认调度不变。
- ipc-api 公开导出 contract、annotations、双端 runtime 和 coroutines；两端 runtime 导出其公开协程类型，修复外部 Maven 使用者缺少编译依赖。
- 独立 Maven 消费工程覆盖 metadata/POM-only、API-only、自定义 Async+Flow KSP 契约与仅契约下游。
- 27 项 host 状态机检查接入 Gradle `check`，日常与发布 CI 增加独立 Maven 消费门禁；Gradle wrapper 增加分发校验和。
- 撤回原取消降噪建议，保留业务内部 timeout/主动 CancellationException 的错误终态；增加真实生成 Stub 的 9 项设备回归。
- 补齐工程审计、架构与业务流程图、SDK 接入指南和 AndLinker 对比；上游固定提交 `7ae01bfd` 的 37 个 Java 文件保持原字节。

## 全部交付物

- 六 SDK：ipc-annotations、ipc-compiler、ipc-contract、ipc-runtime-client、ipc-runtime-server、ipc-api。
- 示例共用 AAR：demo-client-common。
- 六个同签名 Debug APK：demo-app、app-server、app-client1/2/3、benchmark-andlinker。AndLinker APK 仅用于受控对照测量。
- 七模块主制品、sources/javadoc、POM/Gradle metadata，Maven 仓库 ZIP、完整源码 ZIP、仓库证据 ZIP、manifest 和 SHA256SUMS。
- 额外完整证据附件 `ModernIpc-3.0.0-rc.2-evidence-full.zip`、逐文件 manifest 和独立校验清单，保留本地历史 APK、Perfetto、原始日志及失败尝试；历史测试按各自源码和 APK SHA 解释。

Maven 坐标：`com.modernipc:<module>:3.0.0-rc.2`。仓库：`https://maven.pkg.github.com/NingCui29/ModernIpc`，下载需要具有 `read:packages` 权限的 GitHub 凭据。

## 验证与性能范围

发布前工作区 APK `D0E76D896BDC3F0C9F01E14073F34201FFE79BFC6B07106E7885EA4181F28A9A` 仅在小米 `925c23bb` 上通过原框架 73 项、独立 scope 4 项、新增生成 Stub 业务异常终态 9 项；同 UID 鉴权微测独立记录。27 项 host 与独立 Maven 两种解析消费也通过。首次锁屏/Dozing 未开始业务测试的记录保留。

四轮性能对照绑定此前 `4F7ED116...` APK：请求管理改动没有稳定串行 RTT 收益；可选专用 scope 部分 P50 改善，但 P99 波动，仍慢于本轮 AndLinker enqueue。enqueue 测量包含等待回调完成与结果校验。该性能结论不移用于新版本 APK。

最终发布构建、七模块远端制品与附件核验见对应 Actions 和 `docs/releases/v3.0.0-rc.2/`；本版本新 APK 的设备状态按该目录实际记录，四个跨 App 业务场景仍未完成设备验收。

## 兼容与迁移

重新运行 KSP 才能获得新的 Stub 扩展点。checked Schema、契约版本比较、鉴权和原取消/终态协议保持；旧 Broker 缺少能力时明确失败。业务取消不回滚副作用，已开始的同步 Binder 不能强制中断；Direct/Oneway 与 Async 的取消和容量语义不同。Parcelable 字段布局仍由应用维护。四个多 App 示例仅保存 Activity 内存状态，不提供离线可靠投递、持久化或硬件执行保证。
