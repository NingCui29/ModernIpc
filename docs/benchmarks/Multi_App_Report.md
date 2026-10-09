# 多 App 实现与验证报告

发布候选3.0.0-rc.1另完成七个库产物与五个APK的统一构建和本地产物校验，详见[发布记录](../releases/v3.0.0-rc.1/build-result.json)。该发布版安装与小米验收NOT_RUN。本文保留此前开发阶段的APK、时间和证明范围。

核对日期：2026-10-09。本文合并历史跨包 smoke、会议案例与三个新增场景的验证状态。具体部署、载荷、操作及架构/流程/时序图统一见[业务指南](../ModernIPC_Business_Interaction_Guide.md)。设备测试限定 **Xiaomi 14 Ultra / `925c23bb`**。

## 1. 四场景开发阶段结果与产物身份

四场景开发阶段五应用统一构建 **BUILD SUCCESSFUL in 3m 22s，退出码 0，272 个任务**。四个业务场景均有实际源码、页面按钮与采集入口。**当前 APK 安装与设备验收均为 NOT_RUN**；2026-10-09 15:04（北京时间）指定小米返回 `device not found`，没有改用其他设备。

| 开发快照模块（不是发布APK） | APK SHA-256 |
| --- | --- |
| demo-app | `22DAB06517B304F5F40264593CD94DC069E3A5DCF61C3BEB1F23293B1DAAF6A8` |
| app-server | `03409E4F8BFB2F0803118D93A98B324E0441BC3EC41D202E9FF4ECCDBE453AC3` |
| app-client1 | `1AE7C6D6F36A67B9D06C0D0C906E59F4C736E5CE323CC3F3CD35B002819805DF` |
| app-client2 | `10A2D1106FF7E71360AC075C66B910D10C10BFCE393F602C7501D6CE4685832F` |
| app-client3 | `2B3AE16FD0B4FEDE6AF79C3C1D5FBE45B3A68A238BDDC529170EC03A86C4FBEF` |

证据：[build-result.json](runs/2026-10-09-xiaomi14ultra/more-scenarios-build/build-result.json)、[构建日志](runs/2026-10-09-xiaomi14ultra/more-scenarios-build/gradle-build.txt)、[退出码](runs/2026-10-09-xiaomi14ultra/more-scenarios-build/gradle-exit-code.txt)、[设备状态](runs/2026-10-09-xiaomi14ultra/more-scenarios-build/device-state.json)。

Hub Schema [修改前](runs/2026-10-09-xiaomi14ultra/more-scenarios-build/hub-schema-before.kt)与[修改后](runs/2026-10-09-xiaomi14ultra/more-scenarios-build/hub-schema-after.kt)字节相同，SHA-256 为 `40713067BD984EABE6FFD031417AC2B283674839A92E97D37FE5CFB2226B1AE6`。35 个 runtime、contract、compiler、annotations 文件与此前会议构建相同。业务 JSON 复用 Hub 的 String content，未增加 Binder 事务；源码相同不替代设备回归。

## 2. 构建与测试时间线

| 时点 / 身份 | 已记录结果 | 证明范围 |
| --- | --- | --- |
| 2026-09-29 原版示例 | 跨包连接、两次定向消息窗口观察、两轮业务 RTT；AndLinker 同包分进程功能日志。 | 业务与拓扑不同；不是同负载两库排名。 |
| 2026-10-09 初始 trace / demo `5C8CECFD...` | crosspkg1 的 `valid=true`，四个不同 UID、安装哈希、连接/登记/本地订阅启动与 bindings。 | 冷进程 smoke，不是清数据首次安装；没有消息投递、路由隔离或业务确认验证。 |
| 调度 / demo `4494F529...` | 64 PASS / 5 DONE、完整成功请求 trace、两轮 dispatcher 实测。 | 框架与 echo；不能覆盖尚不存在的业务场景。 |
| 握手隔离 / demo `40268409...` | 五应用构建与安装哈希一致。 | 锁屏/Dozing 阻止后续验收；新增握手、框架及 crosspkg2 未运行。 |
| 会议初版 / demo `7B6C...` | 五应用构建退出码 0，1m14s，272 tasks；meetingprepare2。 | 14:38 小米未连接，安装与会议六阶段未运行。此 APK 已被后续构建取代。 |
| 四场景开发快照 / demo `22DAB065...` | 五应用构建退出码 0，3m22s；Schema 相同，scenariosprepare-current1。 | 安装、业务设备验收和新性能均未运行。 |

原始身份与结果：[crosspkg1](runs/2026-10-09-xiaomi14ultra/cross-package-smoke-crosspkg1/capture-result.json)、[4494 最终核验](runs/2026-10-09-xiaomi14ultra/cold-trace-analysis/final-verification.json)、[握手安装](runs/2026-10-09-xiaomi14ultra/handshake-build/build-install.json)、[会议构建](runs/2026-10-09-xiaomi14ultra/multi-app-case-build/build-result.json)、[会议设备状态](runs/2026-10-09-xiaomi14ultra/multi-app-case-build/device-state.json)。框架门禁详见[回归报告](Regression_Report.md)，所有性能数字详见[性能报告](Performance_Report.md)。

## 3. 实现与源码复审

| 源码入口 | 已实现行为 |
| --- | --- |
| [MeetingBoardMessage](../../ipc-api/src/main/kotlin/com/cn/ipc/api/hub/MeetingBoardMessage.kt) / [MeetingBoardDemo](../../demo-client-common/src/main/kotlin/com/cn/ipc/client/common/MeetingBoardDemo.kt) | 会议 JSON、角色校验、定向显示与确认、通知/上报、5 秒总预算、有界去重。 |
| [MultiAppScenarioMessage](../../ipc-api/src/main/kotlin/com/cn/ipc/api/hub/MultiAppScenarioMessage.kt) / [MultiAppScenarioDemo](../../demo-client-common/src/main/kotlin/com/cn/ipc/client/common/MultiAppScenarioDemo.kt) | 工单状态机、配置版本与双端确认、预置温度与阈值告警，严格整数类型/范围。 |
| [BaseClientActivity](../../demo-client-common/src/main/kotlin/com/cn/ipc/client/common/BaseClientActivity.kt) | 四场景选择器、共用连接/订阅、隐藏卡继续处理消息、捕获连接代次的发送/接收、DEBUG 动作一次消费。 |
| [MessageHubServiceImpl](../../app-server/src/main/kotlin/com/cn/ipc/server/app/MessageHubServiceImpl.kt) | UID/包名与 clientId 校验、ALL/定向/SERVER_ONLY 路由，合法会议上报关联日志。 |

会议复审修正了三处确定问题：确认先到时不再被迟到的路由错误覆盖；旧业务任务不能借用新连接代次发送；合法业务载荷先于普通压测 marker 解析。新增场景检查了迟到 accepted 不回退 completed、配置确认来源去重、旧版本/冲突拒绝、整数 JSON、隐藏卡和深色文字对比。构建包含这些实现，静态审查不证明真实绘制或后台稳定性。

三个新增场景各最多一个主动操作和一个自动回复等待者，新回复取消旧等待；会议场景没有相同的回复等待者上限。已经开始的 Binder RPC 仍可能远端完成。最近 64 条业务记录、配置版本与工单状态仅保存在当前 Activity 内存中。

## 4. 采集门槛与离线验证

[会议采集器](harness/capture_multi_app_case.py)与[新增场景采集器](harness/capture_multi_app_scenarios.py)默认只 prepare，不调用 ADB；`--execute` 才启动实际采集。它们不安装 APK、不清数据、不解锁，使用实际 Activity 的 DEBUG Intent 入口，与可见按钮共用业务处理函数。

- 执行前要求固定序列号、亮屏未锁屏、四包本地/安装哈希一致、四个不同 UID 和真实 PID 身份。新增场景还检查 Xiaomi / aurora / 24031PN0DC；系统 fingerprint 记录但不固定，以区分 OTA。
- 保存阶段 UI XML、前台/锁屏快照、关联 Logcat、进程身份、源码/APK 哈希和失败现场。只接受完整 JSON 事件与对应业务 TextView，不用普通日志中的 commandId 冒充页面变化。
- 会议 UI 节点为 `meeting-case-status` / `meeting-case-notice`；新场景为唯一 `scenario-<scenario>-status`。重发阶段须出现后续新日志行。
- 负向隔离、无重放与无告警只覆盖对应 ID 的 3 秒窗口。界面 XML 不证明像素、颜色/字号实际呈现，也不证明四 App 持续同时前台。
- 生命周期晚错误、致命错误、身份不匹配或日志丢失会使采集无效；不能追加成功样本来覆盖失败。

Python 编译、三个采集入口源码契约检查与离线数值/身份/UI 假阳性检查通过。默认 prepare 使用不可用 ADB 名仍成功，证明该准备路径不操作设备。准备记录不是设备 PASS：

| 准备快照 | 身份与用途 |
| --- | --- |
| [meetingprepare2](runs/2026-10-09-xiaomi14ultra/multi-app-case-meetingprepare2/capture-plan.json) | 会议初版构建的历史计划；不能当作当前 APK 快照。 |
| [scenariosprepare-current1](runs/2026-10-09-xiaomi14ultra/multi-app-scenarios-scenariosprepare-current1/capture-plan.json) | 四场景开发快照源码/APK的8阶段计划；发布版须重新prepare。 |

原 [cross-package smoke](harness/capture_cross_package_smoke.py) 已更新过时订阅文案为“已启动消息订阅”；该检查仍只证明本地启动。`isHubRegistered` 也是本地登记/订阅启动标记，不是远端 collector 已就绪证明。

## 5. 待小米执行的业务验收

### 5.1 会议六阶段

| 阶段 | 必须观察到 |
| --- | --- |
| 定向会议 | client2 会议卡更新；client1/client3 同 ID、title、room 的确认。 |
| 广播通知 | client2/client3 通知卡更新，发送端未收到自身广播。 |
| 仅中枢上报 | 服务端关联事件与 UI；客户端有限窗口内无该上报。 |
| 暂停运维屏 | client2 收到下一通知，暂停的 client3 未收到。 |
| 恢复运维屏 | 两端收到新通知，缺失通知在观察窗口内未补发。 |
| 再次定向 | 再次发布及确认闭环成功。 |

### 5.2 新场景八阶段

| 阶段 | 必须观察到 |
| --- | --- |
| 工单接单 | client2 应用工单，client1/client3 接单确认；原 assign 不派发观察端。 |
| 工单完成 | 三端同 ID 的 completed 状态。 |
| 已完成工单重发 | 同 ID/载荷/版本再 assign，新的 completed 确认日志且三端不倒退。 |
| 配置版本 1 | light/14，两端应用并由两个独立来源确认。 |
| 配置版本 2 | dark/18，新版本双端确认。 |
| 正常温度 | 25°C 到分析端，有限窗口没有该样本的新告警。 |
| 高温样本 | 35°C 到分析端，client1/client2 关联告警可见。 |
| 切换后返回 | 本次 Activity 的工单完成状态仍保留。 |

这是 **6 + 8 个业务采集阶段计划，不是设备通过数**；独立于框架回归的 73 项目标。额外手工/诊断项包括：暂停会议屏的确认超时、重复/冲突载荷、配置旧版本与部分确认缺失、完成前后迟到接单、断线重连、Activity 重建、连续高频样本。发布端拒绝冲突不能替代接收端拒绝覆盖。

设备恢复后按[业务指南](../ModernIPC_Business_Interaction_Guide.md#2-构建启动与场景选择)安装当前四包，核对 SHA，再用新的 run-id 准备和执行；准备目录不复用：

```powershell
python docs/benchmarks/harness/capture_multi_app_case.py --run-id meeting-current-prepare
python docs/benchmarks/harness/capture_multi_app_scenarios.py --run-id scenarios-current-prepare
python docs/benchmarks/harness/capture_multi_app_case.py --run-id meeting-current-run1 --execute
python docs/benchmarks/harness/capture_multi_app_scenarios.py --run-id scenarios-current-run1 --execute
```

采集日期默认实际当天，也可显式 `--date YYYY-MM-DD`。握手 9 项、框架 64 项、成功请求 trace 和跨包 smoke 按[回归报告](Regression_Report.md)重新验证当前 APK；历史[握手步骤](runs/2026-10-09-xiaomi14ultra/handshake-build/next-steps.md)保持原样，原 hash 与 run-id 不能用于当前构建。

## 6. 历史跨包观察（2026-09-29）

设备型号 24031PN0DC，Android 16/API36，Xiaomi OS3.0，构建 BP2A.250605.031.A3。当时电量 4%–5%、USB 供电、电池温度约 29.0–31.5°C、Thermal Status 0。

| 原版示例 | 部署与 APK SHA-256 |
| --- | --- |
| ModernIpc | 四独立包；server `170131EEB04AAC939614CFF37A96310FF8CD797C55CBC3911D316860111BE8B8`，client1 `617E97B375D65EC999BC4215F0E2D158DEB08AEF51167449675A03E0992EA80B`；其他 client 哈希未记录。来自提交 7586008421271537ef16500429277fd8434d40db 加当时未提交修复。 |
| AndLinker | 原版 sample 同包主/:remote 进程；`2C3B68ABD40E20815C7668DA7CD8FCCC9DE9B2E276A3AF68685ED415E5FE939E`。固定上游 7ae01bfd，临时克隆仅调整构建签名设置。 |

服务端未运行时先打开 client1，小米 WakePathChecker 连续 `MIUILOG-AutoStart Reject`，客户端重连但服务端未成功拉起；手动打开服务端后连接/注册/订阅。证据：[唤醒拒绝日志](runs/2026-09-29-xiaomi14ultra/wakepath-reject.txt)。这是该安装与前后台状态的观察，不能推广至所有小米设备。

两条定向消息在 client2 恢复前台后出现，client3 对应采集窗口内未出现。发送端回执 **37.411s / 77.895s**，在服务端恢复前台时几乎同时完成；没有进程冻结与完整调度轨迹，唯一根因未确定。证据：[client2 UI](runs/2026-09-29-xiaomi14ultra/modern-client2-ui.txt)、[client3 UI](runs/2026-09-29-xiaomi14ultra/modern-client3-ui.txt)。有限窗口不能证明所有时机的隔离；前台短测不能代表后台稳定时延。

AndLinker 主进程 PID8452、:remote PID16918，出现连接、初始化、Callback、Oneway 日志，见[原始功能日志](runs/2026-09-29-xiaomi14ultra/andlinker-smoke.txt)。sample 的 Oneway 主动 sleep 3 秒不属于 IPC 时延。两库同负载 echo 另见[性能报告](Performance_Report.md)，原版业务不可相除计算速度倍数。

## 7. 业务证明边界

- Hub `tryEmit` 与路由返回不能保证消费；源 SharedFlow replay=0、缓冲128、DROP_OLDEST，没有离线补发。
- 展示/配置 ACK 只表示本地数据处理；服务器上报与在线表为内存观察，没有持久审计或在线 TTL。
- 工单 accepted 不等于 completed；配置双端 ACK 不构成原子提交；25/35°C 来自按钮，不是物理传感器。
- 5 秒等待超时/断连时远端可能已生效，不自动重发。取消等待无法撤销已完成的副作用。
- 当前没有会议、工单、配置或遥测的端到端性能数据；旧 echo 和旧 smoke 均不能代替这些业务验收。

归总前原报告全文保存在[归档 ZIP](../archive/pre-consolidation-2026-10-09.zip)；所有原始记录在 runs 中保持原样。
