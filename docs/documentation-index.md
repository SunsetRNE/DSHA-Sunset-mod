# 文档总索引与维护规则

本索引对应build190/F10当前维护分支。先看当前专页，历史发布和验收说明按各自日期、源码与APK摘要阅读。资料依据分为官方平台/上游说明、当前代码/版本锁、实际执行记录三层；三者不能互相替代。

## 当前功能与架构

| 文档 | 解决的问题 |
|---|---|
| [项目维护总手册](project-architecture-handbook.md) | 全部主要功能、框架分层、入口类与修改/测试地图 |
| [ADB / Root / Shizuku 权限架构](device-permissions-architecture.md) | 系统权限、特权通道、无障碍、自有审批、服务与撤销、故障排查 |
| [环境、数据与插件机制](runtime-data-plugin-architecture.md) | 解压启动、正常/安全/应急、受管更新、pnpm/插件事务、凭据、备份与恢复 |
| [运行引擎与运行态](runtime-engines.md) | 当前proot/proroot、DSHA v1.1.6历史、vendor版本、loader/shim、退出监督与兼容边界 |
| [端口与网络](ports-and-networking.md) | 自动/手动Web端口、备用本机记忆、3090桥/ADB/LAN分域、当前重试接入边界 |
| [DNS配置](dns-configuration.md) | auto/ipv4/native的真实差异、Node窄lookup重试、glibc no-aaaa与用户选择 |
| [虚拟屏架构](virtual-screen-architecture.md) | Core/Manager/工具路由、帧/节点/anchor证据、采集输入、关闭恢复与修改回归 |
| [ADR0001](adr/0001-build154-boundaries.md) / [ADR0002](adr/0002-current-threat-model.md) / [ADR0003](adr/0003-virtual-screen-lifecycle-and-input.md) | 架构取舍、权限与数据边界、虚拟屏生命周期及后果 |
| [资料与来源依据](references-and-provenance.md) | 官方primary入口、工程上游关系、固定版本/摘要、许可与来源限制 |

## 用户操作与开发入口

- [README](../README.md)、[开发约束](../AGENTS.md)、[构建](../BUILD.md)、[贡献流程](../CONTRIBUTING.md)、[接手指南](接手指南.md)。
- [Standard](android-standard.md)、[Low](android-low.md)、[插件](plugins.md)、[虚拟屏开启](virtual-screen-guide.md)、[网页文件交互](web-file-sharing.md)、[v5备份格式](backup-format-v5.md)、[配置恢复](configuration-recovery-host.md)。
- [工程规范](engineering-standard.md)、[模块地图](module-map.md)、[安全模型](security-model.md)、[源码来源](source-provenance.md)、[下载校验](download-verification.md)。

当前默认与术语：proot默认、proroot显式可选；默认Web端口为自动，备用只在本机保存；DNS auto并非全局IPv4优先。原生模型配置生产入口已移除，模型设置在Web；画中画默认关闭；一键备份先安全停止DSH/任务并保存无密码归档，外置工作区内容另备。精确定义以对应专页和当前代码为准。

## 历史材料如何阅读

CHANGELOG保留旧版本的默认值和性能记录，同时追加build190迁移说明。DSHA v1.1.6不是proroot上游版本；当前vendor锁v1.2.8。旧构建的回退、安装、签名、包名或测试数量不能用作当前交付结论。

当前源码发布补齐普通维护Markdown及其需要的历史发布说明，包括真实fix1、build156与旧接手原件；它们保持原归属。历史文内的`app/build`回执、旧本机APK、原始设备日志、截图和私有审计附件没有为修复导航而上传。它们是当时本机材料引用，不能当成当前仓库下载入口或本轮复测证明。当前维护请从本索引进入，不按旧脚本生成额外审计APK或清理用户数据。

历史报告原文保持；发布可见性检查区分当前指南链接、可用历史说明和未分发的本机证据。找到旧报告的真实原件会核对摘要后纳入，不凭空重建遗失回执。下表用于定位报告本身，不声称其所有原始现场附件都在源码包内。

## 文档更新与可复核性

1. 改默认值、设置项、环境变量、授权或端口时，先核ConfigStore/配置入口与实际调用链，补用户可见作用层、下次生效时机及迁移规则。
2. 同步专页、总手册、README和CHANGELOG；历史条目保留时间归属，不用新状态重写旧结果。
3. 文档引用文件存在与该文件进入Git/完整源码ZIP是两项检查；新维护页必须进入最终发布清单。
4. 原生API文档只说明平台合同；隐藏API、厂商能力和实际权限按当前代码与明确的设备证据声明。引用官方资料不代表上游认证本分支。
5. 记录测试对象、失败/跳过、增量或缓存复用及未运行范围。源码CI、Host、浏览器、实际APK与手机验收分开；本轮暂停设备验收。
6. 运行源码检查、锁/描述符与许可检查；包/签名与附件摘要由统一交付负责人核对。私钥、用户凭据、设备原日志和截图不作为文档附件上传。

## 随源码提供的历史说明

- [ROADMAP.md](ROADMAP.md)
- [adb-flow-audit.md](adb-flow-audit.md)
- [backup-upgrade-acceptance.md](backup-upgrade-acceptance.md)
- [backup-upgrade-baseline.md](backup-upgrade-baseline.md)
- [device-acceptance-2026-09-05.md](device-acceptance-2026-09-05.md)
- [extract-startup-speed-2026-09-10.md](extract-startup-speed-2026-09-10.md)
- [functional-audit-rc1.4.md](functional-audit-rc1.4.md)
- [issue67-recovery.md](issue67-recovery.md)
- [maintenance-rc1.4-verification.md](maintenance-rc1.4-verification.md)
- [optimization-backup-verification.md](optimization-backup-verification.md)
- [optimization-implementation.md](optimization-implementation.md)
- [optimization-runtime-verification.md](optimization-runtime-verification.md)
- [optimization-site-verification.md](optimization-site-verification.md)
- [optimization-transfer-verification.md](optimization-transfer-verification.md)
- [optimization-web-verification.md](optimization-web-verification.md)
- [pid-stop-fix-2026-09-10.md](pid-stop-fix-2026-09-10.md)
- [plugin-download-npm-fix-2026-09-05.md](plugin-download-npm-fix-2026-09-05.md)
- [plugin-layout-install-fix-2026-09-05.md](plugin-layout-install-fix-2026-09-05.md)
- [release-rc1.2-2026-09-06.md](release-rc1.2-2026-09-06.md)
- [release-rc1.4-2026-09-07.md](release-rc1.4-2026-09-07.md)
- [releases/v0.1.5-rc1-notes.md](releases/v0.1.5-rc1-notes.md)
- [releases/v0.1.5-rc1.md](releases/v0.1.5-rc1.md)
- [releases/v0.1.5-rc2-build129.md](releases/v0.1.5-rc2-build129.md)
- [releases/v0.1.5-rc2-notes.md](releases/v0.1.5-rc2-notes.md)
- [releases/v0.1.5-rc2.1-build130.md](releases/v0.1.5-rc2.1-build130.md)
- [releases/v0.1.5-rc2.1-build131.md](releases/v0.1.5-rc2.1-build131.md)
- [releases/v0.1.6-alpha1-build134.md](releases/v0.1.6-alpha1-build134.md)
- [releases/v0.1.6-alpha1-build135.md](releases/v0.1.6-alpha1-build135.md)
- [releases/v0.1.6-alpha2-build136.md](releases/v0.1.6-alpha2-build136.md)
- [releases/v0.1.6-alpha2-build137.md](releases/v0.1.6-alpha2-build137.md)
- [releases/v0.1.6-alpha2-build140.md](releases/v0.1.6-alpha2-build140.md)
- [releases/v0.1.6-alpha2-build141.md](releases/v0.1.6-alpha2-build141.md)
- [releases/v0.1.7-rc1-build146.md](releases/v0.1.7-rc1-build146.md)
- [releases/v0.1.7-rc2-apk-size.md](releases/v0.1.7-rc2-apk-size.md)
- [releases/v0.1.7-rc2-build147.md](releases/v0.1.7-rc2-build147.md)
- [releases/v0.1.7-rc2-emergency-recovery.md](releases/v0.1.7-rc2-emergency-recovery.md)
- [releases/v0.1.7-rc2-microphone.md](releases/v0.1.7-rc2-microphone.md)
- [releases/v0.1.7-rc2-notes.md](releases/v0.1.7-rc2-notes.md)
- [releases/v0.1.7-rc2-plugin-network-issues79-80.md](releases/v0.1.7-rc2-plugin-network-issues79-80.md)
- [releases/v0.1.7-rc2-web-identity-recovery.md](releases/v0.1.7-rc2-web-identity-recovery.md)
- [releases/v0.1.7-rc2-web-mobile-3.0.3.md](releases/v0.1.7-rc2-web-mobile-3.0.3.md)
- [releases/v0.1.7-rc2.1-build148.md](releases/v0.1.7-rc2.1-build148.md)
- [releases/v0.1.7-rc2.1-maintenance-20260930.md](releases/v0.1.7-rc2.1-maintenance-20260930.md)
- [releases/v0.2.0-rc2-20260930.md](releases/v0.2.0-rc2-20260930.md)
- [releases/v0.2.0-rc2-notes.md](releases/v0.2.0-rc2-notes.md)
- [releases/v0.2.1-rc.2-build190-20261019.md](releases/v0.2.1-rc.2-build190-20261019.md)
- [releases/v0.2.1-rc.2-build190-fix1-20261019.md](releases/v0.2.1-rc.2-build190-fix1-20261019.md)
- [releases/v0.2.1-rc.2-build190-fix2-20261020.md](releases/v0.2.1-rc.2-build190-fix2-20261020.md)
- [releases/v0.2.1-rc.2-build190-fix3-20261020.md](releases/v0.2.1-rc.2-build190-fix3-20261020.md)
- [releases/v0.2.21-build170-20261009.md](releases/v0.2.21-build170-20261009.md)
- [releases/v0.2.5-build154-20261003.md](releases/v0.2.5-build154-20261003.md)
- [releases/v0.2.6-build155-20261003.md](releases/v0.2.6-build155-20261003.md)
- [releases/v0.2.7-build156-20261003.md](releases/v0.2.7-build156-20261003.md)
- [releases/v1.2.0-rc1.2.md](releases/v1.2.0-rc1.2.md)
- [releases/v1.2.0-rc1.4.md](releases/v1.2.0-rc1.4.md)
- [releases/v1.5-alpha.2.md](releases/v1.5-alpha.2.md)
- [source-updates/20261019-build190-vscreen-port.md](source-updates/20261019-build190-vscreen-port.md)
- [stability-acceptance.md](stability-acceptance.md)
- [terminal-process-reaping-handoff.md](terminal-process-reaping-handoff.md)
- [ui-layout-plan-rc1.4.md](ui-layout-plan-rc1.4.md)
- [ui-layout-rc1.4-verification.md](ui-layout-rc1.4-verification.md)
- [update-flow-rc1.4-verification.md](update-flow-rc1.4-verification.md)
- [upgrade-dsh-0.1.5-alpha.2.md](upgrade-dsh-0.1.5-alpha.2.md)

## 历史原字节与可读视图

下列历史文件原本使用CRLF。为符合当前源码的LF规范，其常用文档路径提供仅换行规范化的可读视图；完整原字节另存tools/history，既有Git属性保护原字节，退休材料不参与部署或测试发现。没有补写原验收事实，视图和原件摘要分别记录。

| 可读视图 | 原字节 | 原件SHA-256 |
|---|---|---|
| [releases/v0.2.1-rc.2-build190-20261019.md](releases/v0.2.1-rc.2-build190-20261019.md) | [原件](../tools/history/documents/docs/releases/v0.2.1-rc.2-build190-20261019.md) | `f172ce28b9cbc8933e8ae3bc7ad0f01f39441e3c0e9eecbbcf955b06ea432d37` |
| [releases/v0.2.1-rc.2-build190-fix1-20261019.md](releases/v0.2.1-rc.2-build190-fix1-20261019.md) | [原件](../tools/history/documents/docs/releases/v0.2.1-rc.2-build190-fix1-20261019.md) | `d34ca245134ce842f404c52fe52060c9255bd31d4ecee2bc7c5966a0017c3c0c` |
| [releases/v0.2.7-build156-20261003.md](releases/v0.2.7-build156-20261003.md) | [原件](../tools/history/documents/docs/releases/v0.2.7-build156-20261003.md) | `8cc70cb1720736e0f9c7427b6b5399155b1983d4bfe51d755ece02dc0196cd55` |
| [source-updates/20261019-build190-vscreen-port.md](source-updates/20261019-build190-vscreen-port.md) | [原件](../tools/history/documents/docs/source-updates/20261019-build190-vscreen-port.md) | `7a37fa7c1155553d0fa767e629a5a437ccf33fcabb9d0e41b976fe7841890bc4` |
