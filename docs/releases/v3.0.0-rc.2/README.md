# 3.0.0-rc.2 发布记录

日期：2026-10-22。分支 `codex/v3.0.0-rc.2`，目标标签 `v3.0.0-rc.2`，VERSION_CODE=30002。

交付六 SDK、示例共用库和六个同签名 Debug APK（含 AndLinker 对照），附源码、Maven 仓库、仓库证据与另行上传的完整历史证据。

本目录记录该版本的构建退出码、实际产物身份与设备验收；发布前 D0E76 的 73+4+9 项仅属于原 APK。远端发布以 Actions、Packages 文件和 Release 附件实际核验为准。

发布说明见[release notes](../../../.github/release-notes.md)，源码修复与早先设备证明见[工程审计](../../ModernIPC_Engineering_Audit.md)。

## 本地验证

统一构建 **BUILD SUCCESSFUL in 2m54s**，退出码 0，465 tasks；六 SDK、示例共用库和六个 APK 的版本、公开依赖导出与同签名校验均通过。27 项 host、10 项发布校验单测通过。[构建与六 APK 完整身份](build-result.json)。

指定小米 925c23bb 在发布构建完成时未连接，新 rc.2 APK 设备验证 **NOT_RUN**，未使用其他设备。发布前 D0E76 的 73+4+9 项通过数仍绑定原 APK。

额外[完整证据包身份](full-evidence.json)：1,930 个原始文件，含 56 份历史 APK（17 个唯一 SHA），逐路径与解压内容 SHA 全部核验。上传状态由 GitHub Release 实际附件判断。

## 远端发布完成

- [v3.0.0-rc.2 Release](https://github.com/NingCui29/ModernIpc/releases/tag/v3.0.0-rc.2)，预发行，源码提交 `ebe2794ce3a9295877624bbe68d8ee71f7b590fb`。
- master、版本分支验证 CI 与[发布 CI](https://github.com/NingCui29/ModernIpc/actions/runs/39749858132)全部 success。
- 七个 Maven publication 已发布，35 个主制品/metadata/sources/javadoc 远端大小与 SHA256 一致。
- 全部 **49 个附件** uploaded；46 主附件与下载的 CI manifest/SHA256SUMS 一致，额外三个完整证据附件与本地 SHA256/大小一致。
- [最终发布与逐附件核验记录](publication-result.json)。发布 CI 生成的 demo SHA 为 `b1fd3d1e99c2c398f706cc2e20f897b85486dd77e73e36594394c8aeb505e099`，新发布 APK 真机仍为 **NOT_RUN**；先前小米回归属于原 APK。

本目录的本地 build-result/full-evidence 初始字段保留生成时点；最终状态以 publication-result 为准。后续文档提交只记录验证结果，发布标签及二进制保持各自身份。
