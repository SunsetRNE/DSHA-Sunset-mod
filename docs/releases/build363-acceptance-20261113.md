# build363 真机覆盖验收回执（2026-11-13）

状态：**通过（有一项既有缺陷注记）**（主路径：362 → 363，官方包覆盖；受管运行时维护最终 `SUCCEEDED`、`runtimeId` 迁至 `5fd17eac`、插件已更新、启动 `READY`、无崩溃）
范围：`io.github.sunsetrne.dsha` 正式包在一加 12 上的 362→363 覆盖安装、首启受管运行时维护、维护后启动与 `logcat -b crash` 检查。
本回执只覆盖下表中的设备与路径，不扩写成全部机型 / 系统版本矩阵；设备序列号已脱敏。截图与原始日志不进入仓库。

## 1. 设备与环境

| 项 | 值 |
|---|---|
| 设备 | 一加 12（PJD110 / OP5929L1），Android 16（SDK 36） |
| 构建指纹 | `OnePlus/PJD110/OP5929L1:16/BP2A.250605.015/U.fb5f63_156f6fc_156f378:user/release-keys` |
| 启动完整性 | vbmeta green（本次只读检查） |
| 连接与权限 | 经 root adb（KSU）只读检查（KSU 授权）；序列号记为 `<serial>` |
| 共存包 | `io.github.sunsetrne.dsha.debug`（versionCode 353）与 `dsh.android.client.rebirth`；本次均未改动 |

## 2. 安装与 APK 校验

- 正式包 `io.github.sunsetrne.dsha`：versionCode **363**，versionName `0.2.1-rc.5-20261113.1700-dsh0.2.2-rc.1`。
- 覆盖安装未卸载：`lastUpdateTime=2026-11-13 18:15:31`（设备本地 CST，UTC+8）；`firstInstallTime` 保持 `2026-10-30`。
- 已安装 `base.apk` 的 SHA-256：`21e5685537f2b1c9697ee7a40e2bcc7195881f76205bd0928ebf0bcee9ee6fcb`，与 build363 Release 的 `SHA256SUMS` 完全一致。
- 共存包未随本次覆盖改变（`.debug` 仍为 353，`rebirth` 未改动）。

## 3. 覆盖升级入口链与维护结果

用户实际观察到的入口链是**同一维护的逐级入口**，不是两次独立维护：

1. 顶部提示条「环境维护进行中 · 点击查看进度」；
2. 「数据维护」；
3. 整屏「运行环境维护」。

维护页在「更新插件」阶段有停留（用户观察，阶段名按用户描述），随后继续并完成；该停留与 §4 中首次 trial 的失败 / 随后的成功处于同一维护窗口（用户观察，未逐帧对齐阶段）。

维护任务（`shared_prefs/dsha-data-task.xml` 最后一条）：

| 字段 | 值 |
|---|---|
| kind | `DSHA_UPDATE_RUNTIME_V1` |
| id | `1794566707873`（2026-11-13 18:45:07 CST） |
| status | `SUCCEEDED` |
| detail | 「dsh 与内置插件更新完成；Ubuntu、个人目录、会话、配置和第三方插件保持原位。」 |

18:45:07 创建的任务最终为 `SUCCEEDED`，此后没有新的任务 id。按 `BackupTaskState.start()` 每个任务都会取新的时间戳 id（`app/src/main/java/com/deepseekharness/app/util/BackupTaskState.java:20-28`），且 `RuntimeTrial.verify()` 在同一任务内有一次性 proot 兼容回退（`RuntimeTrial.java:70-81`、`WebRuntimeFallback.java:7-20`），两次 trial 的时间顺序更符合**同一维护任务内的自动兼容回退**，而不是用户手动重试。成功 trial 的 `runtime-health` 回执本次未采集，`runtimeMode` / `fallbackFrom` 待补证；这不影响「维护最终成功」的结论。

## 4. 维护 trial 逐个结果

时间均为设备本地 CST（UTC+8）；失败详情只摘录关键字段，不贴整段日志。

| 时间 | trial id | 结果 | runtimeMode | 证据摘要 |
|---|---|---|---|---|
| 18:16 | `d716c710-5b33-4b1b-8730-f704723c66f9` | 失败（收尾已确认，记录闭合） | `proroot` | `failure.json`：`error=IO_FAILURE`，子进程 exit 1；Node `ENOENT: scandir '/root/.dsha-runtime-trial-<nonce>/home/profiles/dsha-recovery-<nonce>/node_modules'`；发生在鉴权前 |
| 18:17 | `14d9f43d-92db-494d-ac88-aa98e3fee036` | 成功（closed） | 未单独采集 | 成功关闭，guest 已确认退出 |
| 18:46 | `3abff5e6-91f0-499b-b73f-be8ea78060f6` | 失败（收尾已确认，记录闭合） | `proroot` | 与 18:16 同形态：`IO_FAILURE` + 同一 `ENOENT` 目录枚举，exit 1 |
| 18:46 | `9775ce8f-0c51-47dd-8d9b-e0df55957a95` | 成功（closed） | 未单独采集 | 成功关闭；对应 `runtimeId=5fd17eac` |

- 两轮都是「失败 → 约 1 分钟内成功」。失败都发生在鉴权前、`runtimeMode=proroot`，且没有插件失败标记（不是 `TRIAL_PLUGIN_FAILED`），符合 `RuntimeTrial` 捕获已退出 `TrialExit` 后只重试一次 proot 的判定条件；失败记录能闭合并让同一任务继续下一次 trial，也说明本轮 guest 退出已确认（否则 `RuntimeTrialRecords.prepareForNew` 会要求先走恢复）。
- `error=IO_FAILURE` 是 `BackupErrorCode.from` 对长多行异常的兜底归类（见缺陷记录 §3.2），真实原因在 `failure.json` 的 `output` 字段；本回执只摘录其中的 `ENOENT` 行。

**不是 build363 新增**：更早的 trial 也保留同形态 `failure.json`——`97ca71f8-d3ac-41dd-b117-166b290a34a5`（2026-11-11）、`fc25c2f6-df3c-4813-9a69-9fe132af1fb6`（06:54）、`db86e1fe-51db-4fb1-94f9-fc8343411d84`（11:50）。因此这是**既有问题**；本回执只补记，不算作 build363 回归。缺陷分析见 [运行时 trial 首次失败后重试成功](../design/runtime-trial-profile-node-modules-20261113.md)。

## 5. 更新后受管状态

| 项 | 值 |
|---|---|
| `files/linux/.runtime-descriptor.json` 的 runtimeId | `5fd17eacd0597e478bb52b01f42e7659f084f90b30cf98566deb62cdac36a5fe` |
| `linux/ubuntu/usr/local/share/dsha/managed-assets-v2` 标记 | 同值 `5fd17eac…`（前 8 位已核对） |
| guest `linux/ubuntu/root/dsha-web-mobile/lib/client.js` SHA-256 | `3ddfe212c83e79f3c4eca75c670af291ee7ac9c8cc0a6d6d48e43d334e946cf8` |

上述 `client.js` 摘要与 build363 发布资产一致；`runtimeId` 与 build363 发布说明的 `5fd17eac…` 一致。

## 6. 启动与崩溃检查

- 18:20 启动记录 `runtimeMode=proroot`：`BACKEND_AUTH_READY` 5.1s、`BROWSER_READY` 6.6s。
- `logcat -b crash`：**无**本包（`io.github.sunsetrne.dsha`）的 FATAL / crash 记录。
- 检查结束时进程正常，前台为 `WebPreviewActivity`。

## 7. 数据保留声明

- 维护任务 detail 原文：「dsh 与内置插件更新完成；Ubuntu、个人目录、会话、配置和第三方插件保持原位。」
- 本回执只引用维护任务自报的保留结论，**不额外宣称已逐文件核对**；本次未做逐文件 diff、未做恢复演练。
- `firstInstallTime` 保持 `2026-10-30`，与「覆盖安装未卸载」一致。

## 8. 齿轮单实例验证范围

- 用户**在另一台设备**上确认侧栏设置齿轮单实例正常。
- 一加 12 本次**未单独复现**「项目还原 / 热重载后齿轮计数」交互。本回执如实写：未在本机复现该交互，用户在其他设备验证正常；不把它写成本机验收通过。

## 9. 未覆盖 / 限制

- 未验证：SAF 增删改与 DocumentsUI 交互（本次未走该流程）。
- 未验证：平板横竖屏、深色主题、1.3× 大字体与 `LayoutAuditInstrumentation` 视觉门禁。
- 未验证：回退（363→362 清数据）与回退后的数据表现。
- 未验证：本设备之外的机型与系统版本；设备序列号、截图与原始日志按仓库规则不进入仓库。
- 维护内首次 trial 失败 / 重试问题不在本回执的「通过」范围内，单独记录为既有缺陷（见 §4 链接）。

## 10. 复核命令（脱敏）

```bash
adb -s <serial> shell dumpsys package io.github.sunsetrne.dsha | grep -E 'versionCode|versionName|lastUpdateTime|firstInstallTime'
adb -s <serial> shell pm path io.github.sunsetrne.dsha
# 对上面输出中的 base.apk 路径执行：
adb -s <serial> shell su -c 'sha256sum <base.apk 路径>'
adb -s <serial> shell su -c 'cat /data/data/io.github.sunsetrne.dsha/files/linux/.runtime-descriptor.json'
adb -s <serial> shell su -c 'cat /data/data/io.github.sunsetrne.dsha/files/linux/ubuntu/usr/local/share/dsha/managed-assets-v2'
adb -s <serial> shell su -c 'sha256sum /data/data/io.github.sunsetrne.dsha/files/linux/ubuntu/root/dsha-web-mobile/lib/client.js'
adb -s <serial> shell su -c 'ls /data/data/io.github.sunsetrne.dsha/files/runtime-trials'
adb -s <serial> shell logcat -b crash -d | grep -iE 'sunsetrne|deepseek'
```

> 说明：`runtime-trials/<trial-id>/failure.json` 与 `closed` 用于复核 §4；`files/runtime-health/<runtimeId>.json` 用于补证成功 trial 的 `runtimeMode` / `fallbackFrom`。这些路径都在应用私有目录，需要 root 或等价只读通道。

## 11. 结论

build363 官方包在本设备的 362→363 覆盖升级 **通过**：覆盖安装成功（`base.apk` SHA-256 与 Release `SHA256SUMS` 一致）、维护最终 `SUCCEEDED`、`runtimeId` 迁至 `5fd17eac…`、内置插件已更新、启动 `READY`（proroot，鉴权 5.1s / 浏览器就绪 6.6s）、`logcat -b crash` 无本包记录；维护任务自报数据原位保留。入口链与「覆盖升级进入一次维护页」的预期一致。

存在一项**既有缺陷注记**（不是 build363 新增）：维护内首次 trial（`proroot`）在鉴权前以 Node `ENOENT`（试运行 profile 的 `node_modules` 目录枚举）退出，随后同一维护任务内一次兼容回退 / 重试成功。因此本回执的「通过」**不等于** proroot 原生 trial 已验证——成功路径可能是 proot 兼容回退（成功 trial 的 `runtime-health` 回执待补证）。跟进修与分级见 [缺陷记录](../design/runtime-trial-profile-node-modules-20261113.md)。
