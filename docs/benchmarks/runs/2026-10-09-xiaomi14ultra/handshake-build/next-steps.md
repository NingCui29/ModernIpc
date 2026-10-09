# 最新握手隔离版本的待执行验收

仅设备 `925c23bb`。五包安装和构建身份见 [build-install.json](build-install.json)。demo APK SHA-256：`40268409C9625500E63CAE5BB4B5114CE2EE1831013D0C35E221563ABED4B862`。

当前锁屏，以下测试尚未执行。须先人工解锁，并核实 Awake、keyguard=false；每次使用新 stage/run-id，不能覆盖历史证据。脚本自身还会等待生命周期/焦点/亮屏/未锁屏前台条件。

```powershell
$env:IPC_BENCHMARK_DATE='2026-10-09'
python docs/benchmarks/harness/run_guard.py handshake-final handshakefinal IpcHandshakeProbe 9
# 使用字节方式将 run-as com.cn.ipc.demo 的 files/handshake-handshakefinal.csv 拉到本轮目录。

python docs/benchmarks/harness/run_guard.py handshake-final-cold coldhandshakefinal IpcColdProbe 12
python docs/benchmarks/harness/run_guard.py handshake-final-lifecycle lifecyclehandshakefinal IpcLifecycleProbe 22
python docs/benchmarks/harness/run_guard.py handshake-final-terminals terminalhandshakefinal IpcTerminalProbe 8
python docs/benchmarks/harness/run_guard.py handshake-final-compatibility compathandshakefinal IpcCompatibilityProbe 16
python docs/benchmarks/harness/run_guard.py handshake-final-fault faulthandshakefinal IpcFaultProbe 6
# 同样拉取 files/cold-coldhandshakefinal.csv，核对9+64 PASS和6 DONE。

python docs/benchmarks/harness/capture_request_trace.py docs/benchmarks/runs/2026-10-09-xiaomi14ultra/ordered-trace-on6 --run-id traceon6 --seconds 20 --force-stop --execute
python docs/benchmarks/harness/analyze_request_trace.py docs/benchmarks/runs/2026-10-09-xiaomi14ultra/ordered-trace-on6 --run-id traceon6 --single-client --trace-processor .gradle/trace-tools/trace_processor_shell.exe
python docs/benchmarks/harness/capture_cross_package_smoke.py --run-id crosspkg2 --execute
```

握手修改不改变业务热路径的 wire；此前 4494 的调度实验仍保留其独立身份，不当作本版本速度证明。新增握手 probe 失败时先保留现场并停止后续验收，修复后使用新目录。
