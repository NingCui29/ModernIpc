# ModernIpc 3.0.0-rc.1

本次为预发行版，发布当前源码、文档、全部库产物及五个同签名 Debug 示例 APK。版本分支：`codex/v3.0.0-rc.1`。SDK 版本与 Binder wire protocol 版本分别管理。

## 源码与功能

- 生命周期：独立控制 scope、close/dispose、连接代次、请求/订阅清理与迟到结果隔离。
- 请求与流：Async 入口截止、原 Binder 取消、唯一终态、Flow COMPLETE/ERROR 和明确溢出策略。
- 兼容：KSP 统一 codec/类型检查与逐事务 Schema、双端严格发现、版本区间与旧 Broker 明确拒绝。
- 响应性：冷发现锁外单飞、进程有界 worker、缓存清理；新增独立有界握手，取消本地等待与身份校验。
- 诊断与优化：默认关闭的有界请求 trace、pending 状态精简、发送前检查、可选短任务调度与 Direct。
- 四个独立包业务场景：会议通知、仓储工单、配置同步、遥测告警；仅 Activity 内存状态，不提供离线可靠投递或真实传感器/库存操作。
- 文档统一为接入/架构/业务/优化/两库对比和三类验证报告，保留架构、业务流程与时序图及原始失败证据。

## 兼容与迁移

新生成客户端要求 checked Schema，旧 Broker 缺少能力时明确失败；不能将本版本当作所有旧客户端/服务端的透明替换。Parcelable 字段布局仍由应用维护。取消等待不能中断已开始的同步 Binder 或回滚远端效果；Direct/Oneway 与 Async 的取消及容量语义不同。

## 交付内容

- 六个 SDK 模块：ipc-annotations、ipc-compiler、ipc-contract、ipc-runtime-client、ipc-runtime-server、ipc-api。
- 示例共用库：demo-client-common AAR。
- 五个 Debug APK：demo-app、app-server、app-client1/2/3；使用相同调试签名，供同签名示例部署。旧安装签名不同会拒绝覆盖安装；更新前保存所需数据。
- Maven 目录 ZIP、模块 sources/javadoc、完整源码 ZIP、仓库内证据 ZIP、发布 manifest 与 SHA-256 清单。
- `evidence-full.zip` 另含本地保存的历史 APK、Perfetto 和全部 runs 原始文件，按仓库相对路径归档，可解压回工作区核对原始报告。仓库内证据 ZIP 不保证包含 Git 忽略的历史 APK。

Packages 坐标为 `com.modernipc:<module>:3.0.0-rc.1`，Maven 地址为 `https://maven.pkg.github.com/NingCui29/ModernIpc`。下载需要 GitHub 账号和具有 `read:packages` 权限的令牌。

## 验证范围

发布构建与产物版本、签名、POM/module、校验和由发布流水线核验。最新版本的安装、小米设备回归与四场景验收尚未完成，状态 **NOT_RUN**；指定小米 `925c23bb` 在此前采样时不可用。

历史 `4494F529...` APK 的 64 PASS / 5 DONE、成功请求 trace 和调度测量，只属于该历史构建。新增握手9项+既有64项组成当前73项回归目标，尚未达到；会议6阶段及新增场景8阶段另行验收。旧 AndLinker echo 数据不能作为本版本性能排名。

详见仓库 README、docs/benchmarks/Regression_Report.md、Performance_Report.md 和 Multi_App_Report.md。本次不会沿用旧 APK 的设备通过数验收新包。
