# v3.0.0-rc.3 发布记录

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

[本机发布前全量检查](prepublish-check.txt) · [Python 回归](python-check.txt) · [11/03 Maven 与主机证据](../../benchmarks/runs/2026-11-03-xiaomi14ultra/api21-sdk-verification/summary.json) · [审计修复](../../ModernIPC_Engineering_Audit.md)。
