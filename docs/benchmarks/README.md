# 验证索引

核对日期：2026-10-09。测试限定 **Xiaomi 14 Ultra / `925c23bb`**。本目录将按日期拆分的报告归总为三份；原始输出仍按实际采集日期保留，不修改运行身份或跨版本合算结果。

## 按问题阅读

| 文档 | 负责的内容 |
| --- | --- |
| [性能报告](Performance_Report.md) | AndLinker echo、Direct、鉴权、pending、发送前检查、历史调度消融、请求 trace 与同 APK dispatcher 配对。 |
| [回归报告](Regression_Report.md) | 生命周期、请求/流终态、严格兼容、故障、冷发现清理、握手隔离及完整性门禁。 |
| [多 App 报告](Multi_App_Report.md) | 历史跨包 smoke、四个业务场景的构建身份、实际覆盖与待设备验收计划。 |

使用方法与图见[业务指南](../ModernIPC_Business_Interaction_Guide.md)，源码调用链见[架构](../ModernIPC_Architecture.md)，优化取舍见[优化分析](../ModernIPC_Optimization_Analysis.md)，库差异见[详细对比](../ModernIPC_vs_AndLinker.md)。

## 当前状态

| 构建身份 / 时点 | 已有证据 | 未完成的证明 |
| --- | --- | --- |
| 发布候选3.0.0-rc.1 | [七库+五APK统一构建](../releases/v3.0.0-rc.1/build-result.json)退出码0、429任务；产物版本、POM/module与同签名校验。 | 发布APK安装、小米框架/业务回归和新性能均NOT_RUN。 |
| 四场景开发快照 / demo `22DAB065...` | 五应用统一构建退出码 0；35 个核心源码文件与会议构建相同；Hub Schema 前后相同；离线采集器检查及 prepare。 | 安装、会议 6 阶段、新场景 8 阶段、完整框架回归与实际业务性能均 NOT_RUN。15:04 指定小米未连接。 |
| 握手隔离 / `40268409...` | 五应用构建、安装包哈希校验。 | 当时锁屏/Dozing；新增握手 9 项、64 项回归及 trace 未运行。 |
| 调度实验 / `4494F529...` | 64 PASS / 5 DONE；traceon5 完整 1000 链；两轮同 APK 共 56,192 次测量成功。 | 小包收益有反向配对，不能承诺通用 RTT 降低；不能代替后续构建验收。 |
| 初始 trace / `5C8CECFD...` | 64 PASS / 5 DONE；跨包连接/本地订阅启动 smoke；traceon4、traceoff1 与内核关联。 | smoke 不证明业务投递；trace 仅覆盖隔离成功串行 echo。 |

完整 SHA-256、源码快照和日志链接位于三份报告。构建成功、功能通过、采样资格与性能改善分别判断。

## 原始证据与复算

- [2026-09-29 运行目录](runs/2026-09-29-xiaomi14ultra)：原版示例、同负载两库 echo、Direct 和调度消融。
- [2026-10-08 运行目录](runs/2026-10-08-xiaomi14ultra)：鉴权、顺序修复、pending、发送前检查与早期冷发现。
- [2026-10-09 运行目录](runs/2026-10-09-xiaomi14ultra)：trace/dispatcher、握手构建、业务构建及 prepare。
- [采集与复算工具](harness)：`capture_request_trace.py`、`capture_cross_package_smoke.py`、`capture_multi_app_case.py` 和 `capture_multi_app_scenarios.py` 默认 prepare，`--execute` 才操作设备。`capture_ordered.py` 直接通过 ADB 读取当前日志与快照，使用前按回归报告核对设备及运行身份；离线分析脚本读取已有文件。

原始目录中的 [auth 摘要](runs/2026-10-08-xiaomi14ultra/auth-allocation/summary.md)、[dispatcher 摘要](runs/2026-10-09-xiaomi14ultra/dispatch-analysis/summary.md)、[历史握手步骤](runs/2026-10-09-xiaomi14ultra/handshake-build/next-steps.md)保持原样，它们属于对应构建时点。当前执行计划以回归和多 App 报告为准。

## 指标解释

| 项目 | 如何解释 |
| --- | --- |
| echo RTT | 客户端方法入口到结果恢复，包含 codec、Binder、服务端路径与恢复上下文；区分同步、enqueue、suspend 和 Direct。 |
| 业务 RTT | `sendMessage` 返回与业务 ACK 分别计时；路由结束不证明消费或业务完成。 |
| CPU / 分配 | 区分测量线程、进程计数和整机；局部减少不能直接换算成端到端速度。 |
| 吞吐 | 报告实际成功数、失败、并发、输入与完整窗口；客户端 Oneway 提交率不等于服务端完成率。 |
| trace 区间 | 包含阶段间工作与埋点；分段分位数不可相加，Sleep 不能全部归为排队。 |
| 前台资格 | 起止快照、生命周期事件和周期采样共同限定观察范围；目录名 awake 不是资格证明。 |

[IpcBenchmarkSuite](../../demo-client-common/src/main/kotlin/com/cn/ipc/client/common/IpcBenchmarkSuite.kt)的消息中枢输入与纯 echo 不同；工具显示的近似分配或吞吐不能直接作为库间排名。

## 合并与历史原文

SDK 与 Maven 文档已合并；会议与新增场景指南已合并；13 份历史/日期报告归入上述三份。原文共 25 份项目 Markdown（含 3 份运行目录 Markdown）连同哈希清单保存在[归档 ZIP](../archive/pre-consolidation-2026-10-09.zip)。[合并映射](../archive/consolidation-manifest.json)列出每份旧文档的新位置与原文哈希。恢复某份原文时按其原路径读取 ZIP 条目并核对 `manifest.json`，不要覆盖新的主文档。

旧 `benchmark_Xiaomi_14_Ultra.md` 缺失 APK 和原始日志的数字仅保留为性能报告中的历史线索。`.gradle` 内第三方 README/许可证未参与合并。
