# ModernIPC 各机型真实设备性能测试报告总览

本项目坚持**基于真实物理硬件设备**开展多维度跨进程通信（IPC）性能基准测试。每一款机型均拥有独立的性能测试报告文档，记录其在特定 SoC、内核与操作系统版本下的真实端到端时延、并发吞吐与系统表现。

---

## 📱 已测试机型报告列表

| 机型名称 | 芯片平台 (SoC) | 系统版本 | 测试日期 | 核心性能摘要 (RTT / 2000并发 QPS) | 独立报告链接 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Xiaomi 14 Ultra** | 高通骁龙 8 Gen 3 | Android 16 (HyperOS) | 2026-09-06 | • **RTT P50**: `2.73 ms`<br>• **2000 并发 QPS**: `5,540.2 req/s`<br>• **大包带宽**: `39.86 MB/s` | [📄 查看独立报告](benchmark_Xiaomi_14_Ultra.md) |

---

## 🧪 测试维度与指标规范

每一份机型独立测试报告统一遵循以下 5 大基准维度：

1. **往返时延 (RTT · 1,000 次连续调用)**：
   - 包含 Min, Avg, P50 (中位数), P90, P99, Max 及 StdDev (抖动标准差)。
2. **高并发挂起请求吞吐 (QPS · 100 ~ 2,000 协程并发)**：
   - 测量 100、500、1,000、2,000 级协程并发下的耗时、成功率与每秒处理请求数。
3. **大数据包阶梯传输吞吐 (1KB ~ 200KB)**：
   - 探索在 Binder 物理事务缓冲区限制下的单次 RTT 与传输带宽 (MB/s)。
4. **Oneway 极速调度开销 (1,000 次)**：
   - 评估单向无应答调用的吞吐量与单次系统调度开销（微秒级）。
5. **底层系统与系统级特性分析**：
   - 记录特定厂商 OS（如 Xiaomi HyperOS、ColorOS、OriginOS、HarmonyOS Next 等）对后台进程冻结（Freezer）、Binder 缓冲区大小限制及电源策略的影响。

---

## 🛠️ 新增机型测试指引

当需要在新的物理设备上进行压测时，步骤如下：

1. 连接新设备并通过 ADB 确认连通：
   ```bash
   adb devices
   ```
2. 安装服务端与客户端示例 App：
   ```bash
   ./gradlew installDebug
   ```
3. 打开客户端主界面，点击 **【🚀 运行全量性能压测】**（或通过 Intent Extra 启动自动化测试）：
   ```bash
   adb shell am start -n com.cn.ipc.client1/.Client1Activity --es trigger_benchmark all
   ```
4. 抓取日志并按照模板新建 `docs/benchmarks/benchmark_<Brand>_<Model>.md`。
5. 将新机型链接同步至本总览表格。
