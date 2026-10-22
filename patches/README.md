# 审计修复归档（2026-10-22）

对应报告：[ModernIpc 工程审计与纠正记录](../docs/ModernIPC_Engineering_Audit.md)。

## 当前状态

这些文件记录已实现修复与撤回建议的历史。**0001/0002 对应修改已经落入当前源码和文档，不要正向重复应用。**

| 文件 | 状态 | 说明 |
| --- | --- | --- |
| [0001](0001-D2-export-contract-and-annotations.patch) | 完整 ABI 修复归档，已实现 | ipc-api 导出 contract、双端 runtime、coroutines；annotations 便利性导出；两个 runtime 也导出公开协程类型 |
| [0002](0002-D3-fix-dependency-skeleton-doc.patch) | 指南修订归档，已实现 | 自定义契约骨架 api 导出 runtime/contract/coroutines、ipcVersion 属性；解释 SOURCE 注解、旧 RC1 元数据边界、新验收与 CI |
| [0003](0003-OPTIONAL-D1-quiet-caller-cancel.patch) | **撤回，不可应用** | 现为反例与撤回说明，非 diff；父 scope 活跃不能证明 caller 取消 |

0001/0002 取自当前 HEAD 到修复后工作区的相关文件完整差异，替代原审计的不完整草案；其中 0002 同时包含该指南相对 HEAD 已有的候选验收状态说明。文件名保留，便于追溯原登记 ID。

归档一致性检查：

```powershell
git apply --reverse --check patches/0001-D2-export-contract-and-annotations.patch
git apply --reverse --check patches/0002-D3-fix-dependency-skeleton-doc.patch
```

两条反向检查均已 exit 0。它们仅检查当前工作区已包含归档差异，**没有执行反向应用**。后续源码变化会使上下文失效，此时应阅读实际源码与提交历史。

## 为什么撤回 0003

业务内部 withTimeout 或主动 CancellationException 可发生在服务 scope 仍活跃、客户端仍等待时。原建议把这种情况当成 caller 取消并重抛，已经启动的 Tracker 请求不会补错误回包，因而丢失终态。原建议的新增关闭分支也遗漏了 server_reply trace。

现有协议保持：运行前终态由 Tracker 负责，运行后由生成业务体负责，客户端忽略已取消 pending 的迟到回包。不要简单重抛，也不要把上报整体搬到 Tracker；后者在原审计原型中曾触发重复终态。

## 验证边界

`git apply --check` 或反向检查只验证文本上下文，不证明消费者可编译或运行正确。完整依赖导出以独立 Maven 消费工程、POM/.module 与实际编译为准；取消语义以真实生成 Stub 的组合回归为准。主机 doubles 不证明 Android Binder 或设备性能。

本轮实际执行结果统一见审计第 10 节；旧小米回归和旧发布产物不能自动替代本轮验证。
