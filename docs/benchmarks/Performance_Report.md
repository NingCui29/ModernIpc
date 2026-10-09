# 小米性能报告

发布候选3.0.0-rc.1另完成七个库产物与五个APK的统一构建和本地产物校验，详见[发布记录](../releases/v3.0.0-rc.1/build-result.json)。该发布版安装与小米验收NOT_RUN。本文保留此前开发阶段的APK、时间和证明范围。

整理日期：2026-10-09。本文是性能数字、采集方法与证据限制的统一入口；仅涉及 Xiaomi 14 Ultra（`925c23bb`、`24031PN0DC`、Android 16）。具体历史系统构建为 `BP2A.250605.031.A3`，后续运行以各目录保存的设备快照为准。没有在其他设备补测。

## 1. 结论与阅读范围

| 问题 | 已有结果 | 可以采用的判断 |
| --- | --- | --- |
| AndLinker 为什么更快 | 9/29 Debug 同负载 Echo 中，AndLinker 同步与 enqueue 的 P50 均较低 | 调用协议和调度不同；不能把旧 APK 结果作为当前库的固定排名 |
| 同步 Direct | 同 APK 极短 Echo 的 RTT 较低 | 是独立同步语义，绕过 Async 的请求等待/取消路径，适用范围必须由业务约束 |
| 鉴权、pending、发送前检查 | 局部 CPU 或近似分配下降 | 保留已证明的局部改进；完整 RTT 尚未证明稳定收益 |
| 冷发现与关闭 | 后续版本具备有界可取消等待，历史 64 项回归通过 | 属于响应性和清理正确性修复；预热 Echo 没有测出冷发现优化收益 |
| 请求 trace | 两端文件和内核 Binder flow 可关联 1000 个完整请求 | 已定位准入、调度和恢复成本；诊断 trace 不能替代关闭 trace 后的性能 A/B |
| 服务端专用调度器 | 两个同 APK run 中，1024 字符八个 P50 配对、16 并发四个配对均改善；16 字符串行第二次出现反向配对 | 保留为短、非阻塞业务的应用侧可选 Scope；demo 默认仍为 Default |
| 当前源码性能 | 新握手隔离及业务场景 APK 已构建，未做新性能采集 | 最新源码没有可用于更新上述排名或收益的性能结果 |

生命周期、终态、协议与各版本验收见[回归报告](Regression_Report.md)；四包业务及当前设备状态见[多 App 报告](Multi_App_Report.md)；优化取舍见[优化分析](../ModernIPC_Optimization_Analysis.md)。

### 1.1 APK 身份

工作区含未提交修改，提交号不足以代表测试源码。以下完整 SHA-256 与各 run 的源码哈希共同定义实验身份。表中的构建、安装和采集分别说明，不能用前一版设备通过代替后一版。

| 实验 / 日期 | APK SHA-256 | 可核验身份与状态 |
| --- | --- | --- |
| ModernIpc Echo / 9/29 | `47BB67B492393E116B7239EFF712E0010C265D29B1CE0194896374D39C156E30` | [两库安装哈希](runs/2026-09-29-xiaomi14ultra/echo-ab2/installed-apk-hashes.txt)，同包 `:server`，targetSdk 34 |
| AndLinker Echo / 9/29 | `CFD5EE622B80470771C20391065F29611F17C5C1ED5A9AE038E174D5E8101B25` | 同上；同包 `:remote`，targetSdk 26，固定上游提交及本地补丁 |
| ModernIpc Direct / 9/29 | `208343DE0AA7968919EE1F054B35372738BACD6FBA927A7D0653B9E6B34BBA1B` | [串行及并发原始记录](runs/2026-09-29-xiaomi14ultra/direct-final)；安装哈希与构建一致 |
| Auth A/B / 10/8 | `FCD36F32540CFB9256A19013439A4911C16144873F1AA8D122778EB65CEDAA0D` | [evidence.json](runs/2026-10-08-xiaomi14ultra/auth-allocation/evidence.json)；同 APK 切换旧/新鉴权 |
| Pending A/B / 10/8 | `49C58DAA156DA8CEE1A79AAD11033D78DC65860680F3FEC27F70948AFC5DC4B4` | [原始身份](runs/2026-10-08-xiaomi14ultra/ordered-performance/evidence.json)；三组安装哈希一致，前台控制不足 |
| Guard A/B / 10/8 | `30ED4B9762422AFEBF5DBA9DEEBB125C81921EA60C5C766A7B53EEAE01A1782D` | [guardperf3](runs/2026-10-08-xiaomi14ultra/ordered-guardperf-3/evidence.json)、[guardperf5](runs/2026-10-08-xiaomi14ultra/ordered-guardperf-5/evidence.json)；两组合格记录 |
| 完整 trace / 10/9 | `5C8CECFDC5650E8186EC326DEAC916A9584755508BF555A5F48CE917AFBE20FC` | [traceon4](runs/2026-10-09-xiaomi14ultra/ordered-trace-on4/capture-metadata.json)、[traceoff1](runs/2026-10-09-xiaomi14ultra/ordered-trace-off1/capture-metadata.json)；各 1000 次成功 |
| 调度 A/B / 10/9 | `4494F5299DF407EA3DB2DFE915B14057A39A6DCE5F4171217237FF8F88E1F0A4` | [dispatch1](runs/2026-10-09-xiaomi14ultra/ordered-dispatch1/evidence.json)、[dispatch2](runs/2026-10-09-xiaomi14ultra/ordered-dispatch2/evidence.json)；同 APK 两次，共 56,192 次测量成功 |
| 握手隔离 / 10/9 | `40268409C9625500E63CAE5BB4B5114CE2EE1831013D0C35E221563ABED4B862` | [build-install.json](runs/2026-10-09-xiaomi14ultra/handshake-build/build-install.json)；五包构建/安装通过，新增握手和完整回归未运行，无性能结果 |
| 四场景开发快照 / 10/9 | `22DAB06517B304F5F40264593CD94DC069E3A5DCF61C3BEB1F23293B1DAAF6A8` | [build-result.json](runs/2026-10-09-xiaomi14ultra/more-scenarios-build/build-result.json)；五包构建 exit 0，安装及设备验证 `NOT_RUN`，无性能结果 |

5C8 与 4494 的 35 个 runtime/compiler/contract/api 文件哈希一致，4494 新增 demo 调度实验，见[历史最终核验](runs/2026-10-09-xiaomi14ultra/cold-trace-analysis/final-verification.json)。402684 随后修改握手执行路径；最新业务源码又产生新 APK。最新[设备快照](runs/2026-10-09-xiaomi14ultra/more-scenarios-build/device-state.json)在 10/9 15:04 本地时间记录 `device not found`，未采集新性能。

### 1.2 统一统计规则

- RTT 是调用前后 `SystemClock.elapsedRealtimeNanos` 的墙钟差，包含代理、编解码、Binder、服务端执行以及客户端恢复；不等同于 Binder 驱动 CPU。
- 字符数是业务 ASCII String 长度，未计真实 Parcel 字节数。串行样本在已连接并预热后采集，冷启动与绑定通常在计时前。
- “四轮 P50 中位数之比”与“每轮配对变化的中位数”不同，以下每表注明口径；不同 APK、日期或 capture 不合池。
- 单次请求是 block 内相关观察，不是独立实验；无合池请求的置信区间或显著性结论。QPS 采用成功请求数 / 实测总时长，串行完成率与并发吞吐分开。
- 分配是 ART 进程累计计数的近似增量，其他线程也会贡献；线程 CPU 只覆盖测量线程。计数为 0 表示未观察到增量，不是精确零分配。
- 前台门禁证明所记录的生命周期、焦点、亮屏、解锁及周期采样，不证明 CPU 频率、后台负载、JIT/GC 恒定。后续 `foreground-coverage-v1` 目标周期 500 ms、最大允许间隔 1000 ms，并检查覆盖数量。

## 2. 两库 Echo：协议与调度成本

### 2.1 同负载方法

ModernIpc 对应提交 `7586008421271537ef16500429277fd8434d40db` 加工作区修复及 Echo 契约；AndLinker 对应[上游 7ae01bfd](https://github.com/codezjx/AndLinker/tree/7ae01bfd87ddcf021319148a8727a95345cf350e)，加[可复现 Echo 补丁](harness/andlinker-echo.patch)，关闭逐次库日志。两端业务原样返回字符串，逐次校验内容相等，无数据库或人为 sleep。

每库每档每轮预热 50 次、串行 1000 次，四轮主序列按 And、Modern、Modern、And、And、Modern、Modern、And 运行；CSV 和摘要在采样后写出。设备前台、USB 供电，电量约 14%–15%，结束温度 31.5°C、Thermal Status 0；未固定 CPU 频率。

| 路径 | 请求方式与测量上下文 |
| --- | --- |
| ModernIpc suspend | 单条 Default 协程；oneway 请求、服务端 Job、oneway callback、续体恢复 |
| AndLinker 同步 | 后台线程；一次带 reply 的同步请求/响应 Binder |
| AndLinker enqueue | 后台发起 `Call.enqueue`，线程池执行并等待默认主线程回调后发下一次 |

### 2.2 结果

每行四轮，每轮 1000 次，全部返回匹配、失败 0。单位 ms；分位数取每轮排序第 500 / 990 项。

| 路径 | 字符 | 四轮 P50 | 四轮 P99 | 四轮 P50 中位数 |
| --- | ---: | --- | --- | ---: |
| ModernIpc suspend | 16 | 0.540 / 0.532 / 0.501 / 0.540 | 1.632 / 1.804 / 1.540 / 1.924 | 0.536 |
| AndLinker 同步 | 16 | 0.255 / 0.257 / 0.264 / 0.259 | 1.408 / 0.694 / 0.779 / 1.003 | 0.258 |
| AndLinker enqueue | 16 | 0.354 / 0.331 / 0.324 / 0.308 | 0.604 / 0.486 / 0.482 / 0.570 | 0.327 |
| ModernIpc suspend | 1024 | 0.598 / 0.864 / 0.557 / 0.766 | 1.086 / 1.588 / 1.185 / 1.606 | 0.682 |
| AndLinker 同步 | 1024 | 0.264 / 0.235 / 0.222 / 0.229 | 0.417 / 0.319 / 0.324 / 0.304 | 0.232 |
| AndLinker enqueue | 1024 | 0.373 / 0.354 / 0.351 / 0.351 | 0.529 / 0.497 / 0.510 / 0.483 | 0.353 |

摘要 Modern / And 比值：16 字符同步 2.08、enqueue 1.64；1024 字符同步 2.94、enqueue 1.93。该次 Debug 前台、同包两进程、单在途 Echo 中 AndLinker 较快。一次同步 reply 与请求加 callback 的开销不同；enqueue 更接近异步 API 对照，但线程和回调实现仍不同。targetSdk、构建工具、语言、协议和系统调度共同构成结果，不能单独归因为反射或固定“库倍数”。

原始 [24 个 CSV 与 Logcat](runs/2026-09-29-xiaomi14ultra/echo-ab2)及[复算脚本](harness/analyze_echo.py)保留所有逐轮 P50/P90/P99/mean；扩展 enqueue 前的[初始序列](runs/2026-09-29-xiaomi14ultra/echo-ab)使用不同 APK，仅供历史查阅。未覆盖跨包唤醒、后台、长消息、Oneway 服务端完成、CPU/内存或两库当前版本并发吞吐。跨包旧短测见[多 App 报告](Multi_App_Report.md)。

### 2.3 原版示例业务 RTT：不等价负载

9/29 四包 ModernIpc 示例中，client1 调用 `sendMessage(client_1, SERVER_ONLY, "[BENCHMARK] rtt_probe")`，每轮预热 50 次、串行 1000 次，测量业务调用至挂起恢复，包含 Hub 的 SERVER_ONLY 分支。服务 APK SHA 为 `170131EEB04AAC939614CFF37A96310FF8CD797C55CBC3911D316860111BE8B8`、client1 为 `617E97B375D65EC999BC4215F0E2D158DEB08AEF51167449675A03E0992EA80B`；其余客户端哈希未写入旧报告。

| 轮次 | P50 ms | P90 ms | P99 ms | mean ms | 原始证据 |
| --- | ---: | ---: | ---: | ---: | --- |
| 第一次 | 2.067 | 3.010 | 5.078 | 2.218 | 仅测试时汇总，逐次日志未保存 |
| 第二次 | 2.256 | 3.056 | 4.554 | 2.376 | [原始 RTT 日志](runs/2026-09-29-xiaomi14ultra/modern-rtt.txt) |

当时电量 4%–5%、USB 供电、温度约 29.0–31.5°C、Thermal Status 0。AndLinker 原 sample SHA 为 `2C3B68ABD40E20815C7668DA7CD8FCCC9DE9B2E276A3AF68685ED415E5FE939E`，仅有同包两进程的[功能日志](runs/2026-09-29-xiaomi14ultra/andlinker-smoke.txt)，没有等价 RTT 采集。此表不能与 Echo 结果拼为两库 A/B，前台短样本也不能代表后台业务长尾；后台唤醒拒绝、37.411/77.895 秒回执及功能边界见[多 App 报告](Multi_App_Report.md)。

## 3. Direct：减少跳转需要独立调用契约

9/29 同一 ModernIpc APK 中，`suspend echo` 和 `@IpcDirect echoDirect` 返回相同 String；服务 `9001`、apiVersion 2、apiHash `bench_echo_v2`，新增同步事务 12–16，原挂起 10/11 保留。Direct 在后台调用线程执行带 reply 的同步 Binder；服务端鉴权后在 Binder 线程执行短方法。它没有 Async 的 128 在途许可及远端取消路径，不能用 Echo 结果确定真实业务容量或替换可取消挂起等待。

### 3.1 串行与并发

四轮 release1–4，每档预热 50、串行 1000 次，交替路径顺序。全部 16 个串行 CSV 各 1000 条正数，无失败。

| 字符 | suspend 四轮 P50 ms | Direct 四轮 P50 ms | 四轮 P50 中位数，suspend → Direct |
| ---: | --- | --- | --- |
| 16 | 0.586 / 0.545 / 0.533 / 0.542 | 0.255 / 0.103 / 0.150 / 0.107 | 0.543 → 0.129 ms |
| 1024 | 0.603 / 0.600 / 0.608 / 0.585 | 0.350 / 0.318 / 0.231 / 0.338 | 0.602 → 0.328 ms |

16 字符 Direct P99 为 0.443/0.752/0.393/0.557 ms，suspend 为 1.652/1.398/1.475/1.494；1024 字符 Direct 为 0.558/0.536/0.475/0.502，suspend 为 1.159/1.214/1.114/0.956。最短一轮不能当稳定成本。

两轮 stress1/2，每模式以 16 / 64 个 IO 协程共发 1024 次 16 字符请求，以共同闸门到最后完成计总时长，8 个并发 CSV 各 1024 条，全部成功。

| 并发 | suspend 成功 QPS，两轮 | Direct 成功 QPS，两轮 | suspend P99 ms | Direct P99 ms |
| ---: | --- | --- | --- | --- |
| 16 | 2487 / 1908 | 12014 / 19067 | 24.645 / 18.422 | 6.002 / 3.576 |
| 64 | 3765 / 1929 | 34393 / 15272 | 30.906 / 72.142 | 4.442 / 17.035 |

极短无 I/O 方法中 Direct 较低，64 并发自身也明显波动。它占用调用线程和 Binder 线程；耗时业务会改变排队、线程资源和尾延迟。结束电量 24%、温度 31.0°C、Thermal Status 0，不代表频率一致。

### 3.2 功能和复算边界

串行前检查主线程 Direct 拒绝、指定服务端错误返回、Int/Long/Boolean/ByteArray 编解码；临时重复事务码 10 和非法 Unit 返回值的 KSP 负例构建失败，恢复后 Debug/Release 构建通过。原始 [24 个 CSV、日志与输出](runs/2026-09-29-xiaomi14ultra/direct-final)由[分析工具](harness/analyze_direct.py)复算。两库对比使用另一批 APK，不能与 Direct 表拼成两库当前速度排名。

### 3.3 历史临时消融：调度与死亡监听

9/29 在相同小米设备对极短、无挂起 Echo 做临时变体，每档 50 次预热、1000 次串行采样。每段重新构建安装，结束均恢复源码与 Default 实现；这些诊断没有成为生产配置或移除死亡监听。

服务端把 `CoroutineScope(Default + SupervisorJob())` 临时改为 Unconfined，使无挂起业务从 Binder 线程开始执行。首次 d1–d6 原→变→原序列前四轮约 1.0–1.3 ms、末两轮约 0.5–0.6 ms，明显漂移，全部保留且不算优化收益。再次 d7–d12：

| 路径 / 轮次 | 16 字符 P50 ms | 1024 字符 P50 ms | APK SHA-256 |
| --- | --- | --- | --- |
| Default，d7 / d8 | 0.513 / 0.515 | 0.547 / 0.623 | `96FFBED0AA12992CDCC5E9D1748B2924733F8688743E51B8B837D705B8DEA2A2` |
| 临时 Unconfined，d9 / d10 | 0.426 / 0.377 | 0.460 / 0.436 | `8C749A10DFB79D71B0F6F00B49EDF3D176D2492C5F72FDC4B8AB68AC863181EB` |
| Default 恢复，d11 / d12 | 0.545 / 0.543 | 0.542 / 0.634 | `06A601C1C5BEF21663A523F2511DD7DA262D77DF20B53CEEBE64D9A9A25B9DFB` |

三版历史安装 base.apk 与本地 SHA 核验一致。四轮 Default P50 中位数 0.529 / 0.585 ms，两轮变体 0.402 / 0.448 ms，约低 0.127 / 0.137 ms；变体 16 字符 P99 1.505 / 0.979 ms，Default 四轮 1.161 / 1.100 / 1.891 / 1.234 ms，没有稳定尾部优势。结束电量 21%、温度 30.4°C、Thermal Status 0；[全部 24 个 dispatch-ab CSV](runs/2026-09-29-xiaomi14ultra/dispatch-ab)保留。不同 APK 和首序列漂移限制了因果与固定收益结论。

继续以 A 原→B 变→A 恢复诊断两个因素：

| 临时改动 | 16 字符各轮 P50 ms，A → B → A | 1024 字符各轮 P50 ms，A → B → A | 原始数据 |
| --- | --- | --- | --- |
| Tracker 暂不 `linkToDeath/unlinkToDeath` | 0.547/0.508 → 0.533/0.466 → 0.548/0.528 | 0.640/0.758 → 0.689/0.609 → 0.562/0.661 | [death-ab](runs/2026-09-29-xiaomi14ultra/death-ab) |
| 仅基准 `withContext(Default)` 改 Unconfined | 0.507/0.509 → 0.430/0.395 → 0.566/0.539 | 0.551/0.635 → 0.476/0.414 → 0.623/0.564 | [client-dispatch-ab](runs/2026-09-29-xiaomi14ultra/client-dispatch-ab) |

每 CSV 1000 条正数并通过结果校验。死亡监听四轮 A 中位数 0.538 / 0.651 ms、两轮 B 0.500 / 0.649 ms；差值与波动同量级，不能据此优先开发共享监听或在正式代码删除监听。客户端四轮 A 0.524 / 0.594 ms、两轮 B 0.413 / 0.445 ms；改变的是测试调用上下文，Unconfined 可能在 callback Binder 线程继续下一次请求，未固定恢复线程，不能称库内部的等价优化收益。上述两组旧报告未保存逐段完整 APK 哈希，不推断它们属于其他已知 APK。

最后客户端基准与服务端同时 Unconfined，两轮 B P50 为 16 字符 **0.282/0.313 ms**、1024 字符 **0.315/0.283 ms**；恢复 Default 后两轮 A 为 **0.511/0.488 ms**、**0.449/0.518 ms**。[both-dispatch-ab](runs/2026-09-29-xiaomi14ultra/both-dispatch-ab)仅是 B→A、每侧两轮，未做完整 ABBA。恢复版安装/本地 SHA 同为 `D12A8F26C4D0153138A96FE1A9D89FD43E93087F7133B59788301D146EC5913C`，变体 APK 哈希未保存在旧报告；结束电量 22%、温度 30.9°C、Thermal Status 0。

这些观察说明调度选择在极短 Echo 中有可测影响，未量化协议本身净成本或证明高并发安全性，不能跨序列宣称达到 AndLinker 性能。调度开销的当前处理是第 6 节保留 Async 契约的专用 Scope 实验，历史 Unconfined 变体均已恢复。三组逐轮 P50/P90/P99/mean 可用[analyze_ablation.py](harness/analyze_ablation.py)从保留 CSV 重算，原旧章节在[归总前 ZIP](../archive/pre-consolidation-2026-10-09.zip)可查。

## 4. 局部对象与重复检查优化

### 4.1 鉴权：同 UID 快路径

[CallerAuthenticator](../../ipc-runtime-server/src/main/kotlin/com/cn/ipc/server/CallerAuthenticator.kt)每事务读取实际 Binder UID，同 UID 按原策略准入；不同 UID 每次查询包名并检查签名，未缓存准入结果。[生成 Stub](../../ipc-compiler/src/main/kotlin/com/cn/ipc/compiler/ServerStubGenerator.kt)复用无状态鉴权器，避免同 UID 旧路径的身份/集合/PID 等临时工作。

Auth 微测在真实同 UID Binder 线程上执行，预热 2000 次、旧新新旧各 50000 次，CPU 与分配分批。同一个服务 PID 8746 的第二次微测：旧 515.284 / 444.298 ns，优化 187.022 / 187.970 ns；两格中位数 **479.791 → 187.496 ns，−60.9%**。进程分配旧批次 3,260,416 / 3,194,880 B，新批次 0 / 0。第一轮受 JIT/频率影响，原值保留；这不是整个应用 CPU 下降 61% 或精确零分配。

IPC 同 APK 四轮旧新换序，16 字符，每格 50 次预热、1000 次采样，16,000 次全部成功。

| Run | suspend 配对 P50 变化 | Direct 配对 P50 变化 |
| --- | ---: | ---: |
| authab1 | +33.17% | −57.02% |
| authab2 | −13.95% | −61.58% |
| authab3 | −29.22% | −2.13% |
| authab4 | +82.02% | +101.28% |

四轮 P50 中位数之比：suspend **0.748333 → 0.764662 ms，+2.18%**；Direct **0.270286 → 0.187578 ms，−30.60%**。配对方向反转，未证明稳定 RTT 收益。authab1 优化 suspend 的 **11,341.402026 ms** 最大样本保留，根因未定位。有效签名跨 UID 1000 次调用是功能回归，不是跨 UID 性能增益或异签名拒绝证据。

完整 [Auth 原始证据](runs/2026-10-08-xiaomi14ultra/auth-allocation)、[逐轮自动摘要](runs/2026-10-08-xiaomi14ultra/auth-allocation/summary.md)、[复算脚本](harness/analyze_auth.py)保留各批次与尾部值。分配统计的近似语义见 [Android Debug 说明](https://developer.android.com/reference/android/os/Debug#getRuntimeStat(java.lang.String))。

### 4.2 Pending：三个原子对象合并为一个状态位

[PendingCallRegistry](../../ipc-runtime-client/src/main/kotlin/com/cn/ipc/client/PendingCallRegistry.kt)用一个 AtomicInteger 表示 DISPATCHED / ABORTED / CANCEL_SENT，OR-CAS 只置位，赢者最多发送一次取消。LAZY/截止、许可、死亡监听、鉴权、编解码与恢复契约保留。demo 旧 Registry 冻结自阶段 3，反向恢复后与原源码字节一致，原 SHA 为 `686065E9D7304328327085E2CBE04E17703AAB4FCA0BB4447AB649B132833B1F`。

三次同 APK，微测每模式预热 2000 次，四个 TIME 与四个 ALLOC 块，每块 50000 次完整注册/回复/续体/截止计时器清理。每个 run 的两版各 6 个竞态检查均通过，共 36 PASS。真实 IPC 共用相同 wire、Binder、codec、调用上下文，每 run 16,000 次成功，但没有包含生成代理每次缓存查询与连接检查。

| Run | 本地路径 | 线程 CPU μs，旧 → 新 | 进程近似 B/调用，旧 → 新 |
| --- | --- | ---: | ---: |
| pendingperf1 | 立即回复 | 34.97 → 30.85 | 897.19 → 849.67 |
| pendingperf1 | Parcel 回复 | 39.09 → 37.99 | 929.63 → 881.30 |
| pendingperf2 | 立即回复 | 35.50 → 31.43 | 897.52 → 849.51 |
| pendingperf2 | Parcel 回复 | 41.18 → 40.61 | 929.46 → 881.30 |
| pendingperf3awake | 立即回复 | 31.20 → 27.36 | 896.70 → 849.18 |
| pendingperf3awake | Parcel 回复 | 34.67 → 32.12 | 929.30 → 880.97 |

局部近似少 **约 48 B/调用，约 5.2%–5.35%**。CPU 汇总较低，但首末旧实现块漂移明显，首组立即回复约 40.6 → 29.3 μs，不能给固定稳态 CPU 收益。

| Run | 字符 | 四轮 P50 中位数 ms，旧 → 新 | 摘要变化 | 四轮配对变化 % |
| --- | ---: | --- | ---: | --- |
| pendingperf1 | 16 | 0.566 → 0.494 | −12.79% | −6.57 / −36.79 / −1.84 / −22.50 |
| pendingperf1 | 1024 | 0.590 → 0.545 | −7.64% | −7.43 / +32.16 / −18.57 / −43.88 |
| pendingperf2 | 16 | 0.762 → 0.501 | −34.31% | −32.10 / +13.42 / −44.76 / −36.33 |
| pendingperf2 | 1024 | 0.766 → 0.584 | −23.68% | +0.82 / −19.57 / −45.58 / +8.95 |
| pendingperf3awake | 16 | 1.409 → 1.450 | +2.94% | −7.86 / +6.13 / −62.06 / +9.60 |
| pendingperf3awake | 1024 | 1.486 → 1.408 | −5.27% | −2.82 / −8.14 / −1.38 / −9.53 |

前两次未保存开始电源/Activity 快照；第三次开始虽为 Awake，Activity 已 isSleeping/PAUSING，结束为 Dozing/STOPPED，目录名 awake 不能作为全程前台证据。第三次旧路径 16 字符最大值 **6411.066769 ms**、新路径 1024 字符最大值 34.753125 ms 保留，新路径 P99 范围分别 3.230–4.877 / 4.816–10.414 ms。没有关联 trace 定位其原因，不能归因于原子状态或 Dozing；真实 RTT 未证明稳定加速。

三次原始及完整复算：[pendingperf1](runs/2026-10-08-xiaomi14ultra/ordered-performance/analysis.json)、[pendingperf2](runs/2026-10-08-xiaomi14ultra/ordered-performance-repeat/analysis.json)、[pendingperf3awake](runs/2026-10-08-xiaomi14ultra/ordered-performance-awake/analysis.json)，工具为[analyze_pending.py](harness/analyze_pending.py)。

### 4.3 发送前 guard：避免丢弃结果的完整二次查询

Async 首次严格解析并捕获 Binder 后，旧代理在注册/编码后再次完整获取服务，丢弃第二次结果、仍向原 Binder 发送。新 `checkServiceBinderForConnection` 检查同一 Connected、dispose、捕获 Binder 存活，再复查身份。失败摘除本地等待，不获取新 Binder、重放旧请求或向新代次取消；guard 到 transact 之间仍可断连，不能保证远端没有副作用。

同 APK 两路径保留首次完整解析与其余 wire/调度，局部步骤预热 20000 次，TIME/ALLOC 分离、ABBA、每块 500000 次；真实 IPC 每 run 四轮 16/1024 字符共 16000 次。每 run 6 项 guard PASS、结束 pending/requests/subscriptions 0。guardperf3/5 全部前台状态观察合格、最大间隔 504/505 ms，独立开始/结束 Demo topResumed。

| Run | 局部线程 CPU μs/步骤，lookup → guard | 进程近似 B/步骤，lookup → guard |
| --- | ---: | ---: |
| guardperf3 | 1.310 → 0.711 | 48.087 → 0.000 |
| guardperf5 | 1.510 → 0.903 | 48.087 → 0.016 |

局部可确认约 **48 B、0.60 μs/步骤** 的减少，不能与前序 pending 的 48 B 直接相加为完整调用收益。温缓存旧查询不执行发现 RPC，不是每请求少一次远程往返。

| Run | 字符 | 四轮 P50 中位数 ms，lookup → guard | 摘要变化 | 四轮配对变化 % |
| --- | ---: | --- | ---: | --- |
| guardperf3 | 16 | 0.952 → 0.648 | −31.89% | −6.90 / −41.11 / −36.22 / −3.32 |
| guardperf3 | 1024 | 0.887 → 0.737 | −16.91% | +2.22 / +23.35 / −31.81 / −18.32 |
| guardperf5 | 16 | 0.813 → 0.852 | +4.84% | +14.51 / +22.77 / −5.34 / +6.30 |
| guardperf5 | 1024 | 0.741 → 0.815 | +9.98% | +43.03 / −27.80 / −1.23 / +17.74 |

两次整体方向反转。P99 范围（lookup / guard，ms）：run3 16 字符 1.373–2.014 / 1.267–1.835，1024 字符 1.057–1.649 / 1.392–1.788；run5 分别 1.157–1.861 / 0.862–1.741、1.333–1.640 / 1.460–1.639。两次最大样本 4.999010 / 4.733802 ms 保留；没再见前序 6.411 秒不证明原因已修复。

证据：[guardperf3](runs/2026-10-08-xiaomi14ultra/ordered-guardperf-3/analysis.json)、[guardperf5](runs/2026-10-08-xiaomi14ultra/ordered-guardperf-5/analysis.json)、[分析工具](harness/analyze_guard.py)。局部 0.60 μs 只相当于约 700–1000 μs RTT 的 0.06%–0.09% 直接成本量级；没有轨迹证明间接 GC/排队效应，不能解释数百 μs 的差异。

### 4.4 RTT 漂移与失败记录

[离线诊断](runs/2026-10-08-xiaomi14ultra/analysis-current-problems/problem-analysis.json)保留每 CSV 原顺序，32 个 block 的相邻相关明显：run3 0.743–0.974、run5 0.680–0.978。同一 R4 lookup/16 的四个连续 250 样本中位数：run3 **0.990 → 0.848 → 0.461 → 0.507 ms**；run5 **0.579 → 0.385 → 0.464 → 0.907 ms**。增加串行样本数不能替代独立 run 与环境控制。

四个 thermal 端点均 Status 0，但 HAL 当前 CPU5 温度 run3 **51.5 → 38.8°C**、run5 **36.4 → 38.4°C**；端点没有证明期间频率或温度造成反转，进程快照也没有连续负载/GC/JIT。完整图见[块内漂移及配对变化](runs/2026-10-08-xiaomi14ultra/analysis-current-problems/guard-problems.png)，复算见[analyze_guard_problems.py](harness/analyze_guard_problems.py)。旧冷发现/Map 清理问题后来已修复并单列回归，不能反过来解释温缓存样本漂移。

| 未纳入合格 Guard A/B 的记录 | 原因 | 保留证据 |
| --- | --- | --- |
| guardperf1，旧 APK 95ED9C… | interactive=false、锁屏，前台门禁 10 秒超时，未开始采样 | [原始目录](runs/2026-10-08-xiaomi14ultra/ordered-guardperf-1) |
| guardperf2，同旧 APK | 夹具把 Async `__error__` 错当成 Direct 异常条件；只纠正断言 | [原始目录](runs/2026-10-08-xiaomi14ultra/ordered-guardperf-2) |
| guardperf4，30ED4B… | 期间 ON_PAUSE/FOCUS_LOSS，4 次无效观察；虽有 DONE，整轮环境无效 | [18 个 CSV 与 qualification](runs/2026-10-08-xiaomi14ultra/ordered-guardperf-4) |

所有失败/失效样本保留，不挑下一轮替换反向结果。当前局部改善可信，稳定整体 RTT 收益仍无证据。

## 5. 请求关联 trace：主要耗时在哪里

### 5.1 文件事件与有效性

默认 trace 关闭；每进程缓冲上限 16,384 事件，开启后分配 Event、加锁并写 ATrace，采样后导出文件。关闭时不分配 Event、不取 TID、不加事件锁或日志，生成数值快照和分支仍存在，不能称零开销。

```mermaid
flowchart LR
    E[client_entry] --> R[client_resolved]
    R --> P[client_registered]
    P --> S[client_send]
    S --> V[server_receive]
    V --> Q[server_enqueued]
    Q --> J[server_job_start]
    J --> B[server_business_done]
    B --> T[server_reply]
    T --> C[client_reply]
    C --> U[client_resume]
```

客户端关联 run/PID/callback/generation/requestId；callback identity 是各进程内值，服务端 generation 未知记 0。仅在单客户端、单 callback、单代次、requestId 唯一的受控两进程场景按 requestId 跨端关联，未改 wire，不能直接推广到多客户端。补记阶段使用文件原始 ns，ATrace 写入时间仅作辅助；`client_encode`、`server_queue`、`client_resume` 都包含部分记录/处理成本。

| Run | APK | 有效性与结果 |
| --- | --- | --- |
| traceon1 | 历史采集准备 | 配置位于设备不可读目录，未完成；[保留目录](runs/2026-10-09-xiaomi14ultra/ordered-trace-on1) |
| traceon2 | 历史采集准备 | 设备 v49 不支持 record_thread_names；[保留目录](runs/2026-10-09-xiaomi14ultra/ordered-trace-on2) |
| traceon3 | 历史采集 | App 计数 11000，但 Logcat 仅 10424、丢 576，严格校验失败；[保留目录](runs/2026-10-09-xiaomi14ultra/ordered-trace-on3) |
| traceon4 | 5C8 | [文件采集](runs/2026-10-09-xiaomi14ultra/ordered-trace-on4/capture-metadata.json)：6000 + 5000 事件、App 丢弃 0、1000 唯一完整单调请求 |
| traceoff1 | 5C8 | [关闭采集](runs/2026-10-09-xiaomi14ultra/ordered-trace-off1/capture-metadata.json)：1000 成功、事件 0、pending/requests 0 |
| traceon5 | 4494 | [完整性复测](runs/2026-10-09-xiaomi14ultra/ordered-trace-on5/capture-metadata.json)：1000 完整请求、11000 事件、App 丢弃 0 |

traceon4 RTT P50/P99 **0.919896 / 1.576510 ms**，traceoff1 **0.771510 / 1.943386 ms**，各一轮且都有系统 Perfetto，不能把差值作为稳定 trace 开销。traceon5 **0.936303 / 2.607864 ms** 只作为另一 APK 的完整性复测。错误、取消、冷发现链尚未具备同等阶段完整性验收。

### 5.2 各段成本与内核关联

traceon4 [request-stages.csv](runs/2026-10-09-xiaomi14ultra/ordered-trace-on4/request-stages.csv)与[stage-summary.json](runs/2026-10-09-xiaomi14ultra/cold-trace-analysis/stage-summary.json)，均含 1000 条完整请求。单位 μs。

| 阶段 | 平均墙钟 | P50 | P99 |
| --- | ---: | ---: | ---: |
| client resolve | 118.673 | 110.365 | 237.865 |
| client register | 57.852 | 50.677 | 152.708 |
| client encode | 64.489 | 60.573 | 128.385 |
| request transport | 106.992 | 96.562 | 241.302 |
| server admission | 115.711 | 105.313 | 236.771 |
| server queue | 116.693 | 108.750 | 223.386 |
| server business | 16.253 | 14.531 | 43.125 |
| server reply encode | 11.775 | 10.364 | 39.792 |
| reply transport | 105.578 | 95.468 | 281.511 |
| client resume | 179.371 | 168.021 | 330.469 |

无重叠阶段均值合计 **893.387 μs**，外部 RTT 均值 **975.815 μs**，差额包括 entry 前与 resume 标记后外层开销。`server_dispatch = admission + queue`，不能再相加；各段 P50/P99 不能相加为整体分位数。数据是 trace 开启的诊断成本，不能当 trace 关闭后的函数成本。

原生 [关联摘要](runs/2026-10-09-xiaomi14ultra/ordered-trace-on4/correlated-analysis/summary.json)提供请求窗口与目标线程状态：

| 窗口，1000/1000 | 平均墙钟 μs | Running μs | Ready μs | Sleep μs |
| --- | ---: | ---: | ---: | ---: |
| server receive → enqueue，Binder 线程 | 115.711 | 113.257 | 0.734 | 1.720 |
| server enqueue → job start，worker | 116.693 | 38.936 | 36.952 | 40.805 |
| client reply → resume，worker | 179.371 | 46.339 | 49.381 | 83.651 |

999/1000 服务端 worker 唤醒来自该请求 Binder TID；1000/1000 客户端 worker 唤醒来自该 callback Binder TID。两向共 **2000/2000 唯一 Binder flow**，内核 send→receive 均值为 **48.207 / 52.520 μs**，其中接收线程 Ready 均值 **34.794 / 39.772 μs**。这是传输窗口加接收线程等待，不是驱动 CPU；Sleep 可能含源线程处理与 dispatch 准备，Running 是线程驻留，不是函数 profile。源与目标窗口相互重叠。

长尾例：request 273 的 reply→resume **1.673333 ms**，其中 Ready **1.484375 ms**；request 272 的请求 Binder send→receive **2.547084 ms**。这些局部线程证据支持先测服务端任务调度，不能归因为全设备、MIUI 单一策略或解释前序 6.411/11.341 秒样本。

### 5.3 Perfetto 边界

主机 processor v58.2、设备 tracing service v49.0；BOOTTIME 偏移 0，文件 ns 位于 trace 窗口。每 CPU 初始化 compact switch/waking 跳过合计 8+8，最晚初始化早于首请求 746.692 ms；当前缓冲 overwrite/discard、writer loss 与每 CPU overrun delta 0，480 chunks 写入=读取，非零 error/data_loss/notice 为空。全局 `traced_chunks_discarded=1` 是累计值，单个末尾快照无法归属，不宣称整个系统 trace 绝对零丢失。

进程名快照把客户端 PID 1466 标为 usap64，分析以事件 PID/TID 和 Binder flow 关联。GC 名称搜索也可能匹配含 GC 的 JIT 名，只有真实 copying-GC slice 的请求重叠才用于说明，不据此断言它造成全部长尾。统计语义链接见原始 JSON 中的 [v58.2 初始化实现](https://github.com/google/perfetto/blob/v58.2/src/trace_processor/importers/ftrace/ftrace_sched_event_tracker.cc#L132)与[v49.0 服务累计计数实现](https://github.com/google/perfetto/blob/v49.0/src/tracing/service/tracing_service_impl.cc#L2899)。

## 6. 同 APK 服务端调度配置

[IpcDispatchBenchmark](../../demo-app/src/main/kotlin/com/cn/ipc/demo/IpcDispatchBenchmark.kt)在同一个 Echo Stub 的现有 Scope 上切换 Default / 专用单线程执行器，保留 Tracker 的 LAZY Job、许可、callback 死亡监听、取消及 wire。共享 SupervisorJob，idle 才切换；成功恢复 Default，异常 finally 尝试恢复，恢复失败判失败并保留现场。专用线程服务销毁时关闭。该实验在 demo，SDK Scope 一直由宿主提供，没有改为通用 Unconfined 或 Binder 线程执行业务。

每 run 三路径各预热 500 次，每块另预热 50；16/1024 字符各四轮、三路径换序，共 24×1000 串行请求，再以 16 并发、16 字符两轮换序测 4×1024。不开请求 trace/系统 Perfetto；每块结束 pending/request 0。两次同 4494 APK，各 **28096 测量成功，4 PASS / 28 RESULT / DONE**，最大前台观察间隔 505 ms。

### 6.1 分别报告两次 capture

下表是每模式 block 分位数的中位数，单位 ms。Direct 是同步语义参考。

| Capture | 字符 | Default P50 / P99 | Dedicated P50 / P99 | Direct P50 / P99 |
| --- | ---: | --- | --- | --- |
| dispatchperf1 | 16 | 0.665885 / 1.104089 | 0.504948 / 0.871719 | 0.282239 / 0.426510 |
| dispatchperf1 | 1024 | 0.724818 / 1.374740 | 0.536901 / 0.868750 | 0.220052 / 0.518802 |
| dispatchperf2 | 16 | 0.780885 / 1.442604 | 0.755495 / 1.358985 | 0.299218 / 0.442968 |
| dispatchperf2 | 1024 | 0.903229 / 1.525157 | 0.736770 / 1.451093 | 0.410547 / 0.575287 |

以下改变量是**各轮 Dedicated 相对 Default 配对百分比的中位数**，延迟负数更低；与上表中位数之比不能混用。

| Capture | 场景 | 配对数 | ΔP50 | ΔP99 | Δmean | Δ并发成功 QPS |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| dispatchperf1 | 串行 16 字符 | 4 | −18.46% | −23.73% | −17.91% | — |
| dispatchperf2 | 串行 16 字符 | 4 | −0.29% | −9.28% | −9.68% | — |
| dispatchperf1 | 串行 1024 字符 | 4 | −30.52% | −42.22% | −31.78% | — |
| dispatchperf2 | 串行 1024 字符 | 4 | −17.30% | −2.17% | −7.91% | — |
| dispatchperf1 | 16 并发、16 字符 | 2 | −9.01% | −7.57% | −9.99% | +13.63% |
| dispatchperf2 | 16 并发、16 字符 | 2 | −13.46% | −20.81% | −15.40% | +22.46% |

并发吞吐 block 中位数：run1 **6548.39 → 7440.36 req/s**，run2 **6249.76 → 7650.33 req/s**；QPS 是完成数/共同闸门至最后任务结束，不是平均 RTT 倒数。并发样本编号按 worker 内部序列汇集，不是全局完成顺序或跨模式逐请求对齐。

run2 串行 16 字符四个 P50 配对为 **−18.46 / +17.88 / −38.27 / +20.83%**，两次反向，因此不能承诺小包稳定加速。1024 字符八次 P50 配对方向均下降，但 run2 一次 P99 +2.24%，也不能称所有尾延迟改善。Dedicated 总在三路径中间，Default/Direct 换首尾；平衡顺序未消除周期漂移与前块影响。

run1 跨包 smoke 四个进程仍在，run2 前显式停止；进程存在/缺席快照不是 CPU 负载，不能把跨 capture 差值归因为停止它们。保留每次内部配对，未合池为同负载独立实验。

原始 [dispatch1](runs/2026-10-09-xiaomi14ultra/ordered-dispatch1)、[dispatch2](runs/2026-10-09-xiaomi14ultra/ordered-dispatch2)，完整[逐块与全部配对摘要](runs/2026-10-09-xiaomi14ultra/dispatch-analysis/summary.md)、[JSON](runs/2026-10-09-xiaomi14ultra/dispatch-analysis/dispatch-analysis.json)及[复算脚本](harness/analyze_dispatch.py)保存所有 lane，包括 Direct。

### 6.2 采用决定

保留专用 Scope 为应用侧可选短任务配置，demo 默认 Default。它可避开 Default 共享工作队列，但单线程限制重任务并行度、每实例需要线程资源，宿主应在销毁时关闭 dispatcher 和 Job。短 Echo 结果不适用于阻塞 I/O、重 CPU、长任务。真实业务应重新评估线程数、公平性、取消、并发和尾延迟；可批量化时应增加兼容事务，继续保留旧契约。

## 7. 复算与后续采集

### 7.1 离线复算已有证据

以下命令只读已有证据并生成统计输出，不操作设备。重算后应核对各目录原 APK 身份，不改写原始日志/CSV。

```powershell
python docs/benchmarks/harness/analyze_echo.py docs/benchmarks/runs/2026-09-29-xiaomi14ultra/echo-ab2 5 8
python docs/benchmarks/harness/analyze_direct.py docs/benchmarks/runs/2026-09-29-xiaomi14ultra/direct-final
python docs/benchmarks/harness/analyze_ablation.py docs/benchmarks/runs/2026-09-29-xiaomi14ultra/death-ab docs/benchmarks/runs/2026-09-29-xiaomi14ultra/client-dispatch-ab docs/benchmarks/runs/2026-09-29-xiaomi14ultra/both-dispatch-ab
python docs/benchmarks/harness/analyze_auth.py docs/benchmarks/runs/2026-10-08-xiaomi14ultra/auth-allocation
python docs/benchmarks/harness/analyze_guard.py docs/benchmarks/runs/2026-10-08-xiaomi14ultra/ordered-guardperf-5
python docs/benchmarks/harness/analyze_guard_problems.py
python docs/benchmarks/harness/analyze_dispatch.py docs/benchmarks/runs/2026-10-09-xiaomi14ultra/ordered-dispatch1 dispatchperf1 docs/benchmarks/runs/2026-10-09-xiaomi14ultra/ordered-dispatch2 dispatchperf2
```

### 7.2 新采集门禁

先完成[回归报告](Regression_Report.md)中的当前源码握手、冷发现、终态、兼容门禁，再开展性能；设备必须连接并解锁为前台。工具固定 serial `925c23bb`，新 run/stage 使用新目录，不覆盖历史。

| 目标 | 采集要求与入口 |
| --- | --- |
| 两库当前速度 | 固定两边源码/APK、业务方法、payload、拓扑、调度、日志、构建类型；按声明的同步/异步语义独立报告，新测试不借用 9/29排名 |
| 请求链 | [capture_request_trace.py](harness/capture_request_trace.py)默认 prepare 不调用 ADB，执行加 `--execute`；[analyze_request_trace.py](harness/analyze_request_trace.py)需显式 `--single-client` 才按 requestId 跨端关联 |
| Guard | [run_guard.py](harness/run_guard.py)采功能/身份/前台，再[analyze_guard.py](harness/analyze_guard.py)；每轮保留完整失败、环境失效及方向反转 |
| Dispatcher | 每模式同业务/wire/调用方，仅切换服务端 Scope；真实业务的长尾、完成吞吐、公平性单独测，diagnostic trace 与关闭 trace 的收益实验分开 |
| 构建身份 | 五 APK 构建结束并记录 exit code 后安装，逐包拉取 base.apk 对比；源码哈希、UID、PID、系统状态、前台覆盖及缺失日志均保存 |

SDK 变更不得削弱鉴权、Schema、发送前代次检查、LAZY 注册顺序、许可、死亡监听和取消编码；不得绕过调用方 dispatcher 恢复。取消本地等待仍不能中断已开始的同步 Binder 或回滚远端副作用。

### 7.3 历史未复核数字

仓库早期 `benchmark_Xiaomi_14_Ultra.md` 未附对应日志、日期、APK/hash、运行脚本。为保留文档来源，旧原文已收入[归总前 ZIP](../archive/pre-consolidation-2026-10-09.zip)。以下数字仅为原文记录，**不计入当前验收或优化收益**。

| 旧指标 | 原文“优化后” | 原文“初始版本” | 缺失范围 |
| --- | --- | --- | --- |
| 串行 RTT min / P50 / mean / P90 / P99 / max，ms | 0.723 / 1.636 / 1.694 / 2.341 / 3.826 / 6.754 | 1.432 / 2.731 / 2.814 / 3.607 / 5.128 / 6.884 | 无逐次样本、源码与安装身份 |
| 100/500/1000/2000 并发总 ms | 35 / 107 / 191 / 326 | 未逐项给出 | 无成功/失败日志 |
| 相应原文 QPS | 2857 / 4673 / 5236 / 6135 | 2703 / 4630 / 5181 / 5540 | 旧工具以提交数计吞吐，失败时会高估 |
| 1/10/50/100/200 KB 平均 RTT，ms | 2.3 / 2.3 / 3.5 / 3.3 / 4.6 | 未给完整值 | 每档仅称 10 次、无原始日志 |
| 200 KB 原文“带宽” | 42.46 MB/s | 39.86 MB/s | 请求文本估算，未计真实 Binder 字节 |
| 1000 次 Oneway 客户端总 ms | 73 | 303 | 只测提交，未测服务端完成 |

原文 `cgroup.freeze=1`、parcel 1,024,188 B 错误、小包 Oneway 缓冲错误均无对应日志，不作为当前系统因果结论、通用“200 KB 安全上限”或可靠队列证据。后台问题需另采唤醒拒绝、进程/焦点时间、Binder 错误和真正业务确认。当前 Hub 缓冲 DROP_OLDEST 及 `tryEmit` 也不能保证投递。
