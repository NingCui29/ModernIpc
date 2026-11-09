# v3.0.0-rc.3 发布记录

2026-11-09 已复核历史发布。当前标签源码为 `c800b30b45af53f4360f96c53a528f793cf9905d`。本轮重建的源码包、证据包和校验文件见[当前附件回执](history-review.json)。下载后请使用同一 Release 中的 [SHA256SUMS.txt](https://github.com/NingCui29/ModernIpc/releases/download/v3.0.0-rc.3/SHA256SUMS.txt) 和 [完整证据校验和](https://github.com/NingCui29/ModernIpc/releases/download/v3.0.0-rc.3/FULL_EVIDENCE_SHA256SUMS.txt)。下文保留首次发布时的构建和验收记录。

[ModernIpc 3.0.0-rc.3](https://github.com/NingCui29/ModernIpc/releases/tag/v3.0.0-rc.3) 为预发行版，版本分支为 `release/v3.0.0-rc.3`，Android versionCode 为 `30003`。不可变标签源码提交为 `e93743320841b7c80ff2c32dedb976750a40a4ee`；发布后的文档回执可以继续提交到 master 与版本分支，不移动该标签。具体变更见[发布说明](../../../.github/release-notes.md)。

[发布工作流 42156767831](https://github.com/NingCui29/ModernIpc/actions/runs/42156767831)通过全量子模块检查、独立 Maven metadata/POM-only 消费、七库与六 APK 构建、版本/签名校验、GitHub Packages 发布及远端校验。对应提交的 [master 验证](https://github.com/NingCui29/ModernIpc/actions/runs/42156767512)和[版本分支验证](https://github.com/NingCui29/ModernIpc/actions/runs/42156767541)也均成功。

| 交付层 | 范围与核验 |
| --- | --- |
| GitHub Packages | 七模块：六 SDK 与 demo-client-common；CI 按大小和 SHA-256 校验 35 个远端主制品及元数据/sources/javadoc 文件 |
| Debug APK | demo-app、app-server、app-client1/2/3、benchmark-andlinker，共六个；版本号和集合内同签名校验通过 |
| 主要 Release 附件 | 46 个，包含库制品、APK、Maven 仓库、源码、仓库证据、manifest 与 SHA256SUMS；见[主要附件核验](main-assets-verification.json) |
| 完整历史证据 | 约 415 MB ZIP、逐文件 manifest 与独立 SHA256SUMS，共三个附件；包含 2,656 个本地历史证据文件 |
| 最终 Release 集合 | 49 个附件；完整状态、远端身份与核验结果以[最终发布回执](publication-result.json)为准 |

完整证据 ZIP 保留历史 APK、Perfetto、原始日志与失败尝试，不将失败记录改写为成功。上传中断产生的未完成附件已按身份检查后清理，已通过校验的附件按内容一致性复用；最终结果以回执为准。主要制品来源和文件清单见[CI manifest](manifest.json)与[主要附件校验和](SHA256SUMS.txt)。

最终发布 APK 的设备状态仍为 **NOT_RUN**。11 月 3 日的 86 项小米回归只属于[其记录中的 APK](../../benchmarks/runs/2026-11-03-xiaomi14ultra/api21-sdk-regression1/summary.json)，不迁移至版本更新或 CI 重新签名的 APK；API 21/22 仍为静态兼容检查，本次未形成新的 AndLinker 性能结论。

历史文档中“未发布”描述保留其当时的日期范围，相关源码修复已纳入本次标签。旧 rc.2 标签、制品及验证记录保持原有身份。

## 匿名 Maven 分发补充（2026-11-06）

默认消费地址调整为 `https://raw.githubusercontent.com/NingCui29/ModernIpc/maven`，坐标仍为 `com.modernipc:<module>:3.0.0-rc.3`。七模块复用本次 Release 的 `maven-repository.zip` 与 `manifest.json` 中记录的原始文件；不重新编译，不覆盖已发布 GAV，不改动 `v3.0.0-rc.3` 标签、49 个原 Release 附件或历史发布回执。

客户端按 [SDK 指南](../../ModernIPC_SDK_Guide.md#0-依赖与生成配置)使用匿名仓库和 `com.modernipc` 独占规则。下载不需要 Token；GitHub Packages 继续作为需要认证的可选渠道。

本次匿名渠道的发布与验证状态：**已发布并验证通过**。[匿名发布工作流 42190101231](https://github.com/NingCui29/ModernIpc/actions/runs/42190101231)复用七模块的 175 个原始文件，对制品、模块级元数据及来源回执共 211 个文件完成无凭据 HTTP 下载与 SHA-256 对照。独立消费者的 Gradle metadata、POM-only、Async/Flow KSP 编译及嵌套接口负例全部通过；30 项 Python 回归通过。

分发配置提交为 `ec28d8ad09b6a07bf7afdd63c51a1d378addceb3`，`maven` 分支制品提交为 `3e3a75163aa4cffc19a68ee655272763d4c95de3`。[master 全量验证](https://github.com/NingCui29/ModernIpc/actions/runs/42190096198)与[版本分支全量验证](https://github.com/NingCui29/ModernIpc/actions/runs/42190095772)均通过子模块 `check` 和本地隔离 Maven 消费回归。

结果见[匿名发布回执](anonymous-publication-result.json)、[逐文件下载核验](anonymous-maven/downloads.json)和[真实消费者报告](anonymous-maven/consumers.json)。原 `publication-result.json` 保留历史 Release/Packages 发布证据；本次渠道调整未更改 Android APK，也未新增设备验收。

[本机发布前全量检查](prepublish-check.txt) · [Python 回归](python-check.txt) · [11/03 Maven 与主机证据](../../benchmarks/runs/2026-11-03-xiaomi14ultra/api21-sdk-verification/summary.json) · [审计修复](../../ModernIPC_Engineering_Audit.md)。
