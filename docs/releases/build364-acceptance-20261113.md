# build364 真机覆盖验收回执（2026-11-13）

状态：**通过**（主路径：363 → 364，官方包覆盖；受管运行时维护一次 `SUCCEEDED`、`runtimeId` 迁至 `bd0016c3`、**首次 proroot trial 直接成功且无 ENOENT 重试**、启动 READY、无崩溃）

范围：`io.github.sunsetrne.dsha` 正式包在一加 12 上的 363→364 覆盖安装、首启受管运行时维护、维护后 dsh 启动与页面打开、`logcat -b crash` 检查，以及 RC1 迁移 history-only 复用与保留数据清单的只读核对。本回执只覆盖下表中的设备与路径，不扩写成全部机型 / 系统版本矩阵；设备序列号已脱敏；截图与原始日志按仓库规则不进入仓库。

## 1. 设备与环境

| 项 | 值 |
|---|---|
| 设备 | 一加 12（PJD110 / OP5929L1），Android 16（SDK 36） |
| 构建指纹 | `OnePlus/PJD110/OP5929L1:16/BP2A.250605.015/U.fb5f63_156f6fc_156f378:user/release-keys` |
| 连接与权限 | 经 root adb（KSU）执行安装与只读检查（用户当次授权验收）；序列号记为 `<serial>` |
| 验收方式 | 容器内 adb 客户端 → 宿主 adb server（`127.0.0.1:5038`）→ USB 目标；安装后经 adb 启动应用并采集证据 |
| 其他包 | 设备上既有 `.debug` / `rebirth` 等共存包未参与本次安装，也未改动 |

## 2. 安装与 APK 校验

- 安装前：`versionCode=363`、`versionName=0.2.1-rc.5-20261113.1700-dsh0.2.2-rc.1`；`base.apk` SHA-256 `21e56855…`（与 [build363 回执](build363-acceptance-20261113.md) 一致）。
- 覆盖安装未卸载：`adb install -r` 成功（约 13 秒）。
- 安装后：`versionCode=364`、`versionName=0.2.1-rc.5-20261113.1340-dsh0.2.2-rc.1`；`lastUpdateTime=2026-11-13 22:45:50`（设备本地 CST）；`firstInstallTime` 保持 `2026-10-30 22:16:48`。
- 已安装 `base.apk` SHA-256：`b9ff4793658e9f8de362748fa3c9a0672ce8aadbdc0e2bfc4807b813e1c5accb`，与 build364 Release `SHA256SUMS` 完全一致。

## 3. 覆盖升级入口链与维护结果

1. 安装后经 adb 启动应用，`topResumedActivity` 直接为整屏 `ExtractActivity`（运行环境维护页），与「覆盖升级后首次启动进入一次维护页」的预期一致。
2. 维护任务（`shared_prefs/dsha-data-task.xml`，安装后唯一任务）：

| 字段 | 值 |
|---|---|
| kind | `DSHA_UPDATE_RUNTIME_V1` |
| id | `1794581167273`（2026-11-13 22:46:07 CST） |
| 阶段 | 解压新版 dsh → 准备新版内置插件和界面适配 → 保留并切换受管文件 → 确认网页与数据读写 |
| status | `SUCCEEDED` |
| detail | 「dsh 与内置插件更新完成；Ubuntu、个人目录、会话、配置和第三方插件保持原位。」 |

3. 维护总耗时约 2 分 17 秒；完成后进入 `MainActivity`，主界面「环境维护进行中」提示条在随后一次刷新后消失，卡片显示「运行环境 READY」。

## 4. 维护 trial 结果（build364 修复重点）

安装前 `files/runtime-trials/` 有 8 个目录，维护后仍为 8 个（新增 1 个、按保留策略轮换 1 个旧记录）；本次只创建一个 trial：

| 项 | 值 |
|---|---|
| trial id | `83f65e27-6b65-42ac-b53c-a4098c2af6ac`（目录时间 22:47 CST） |
| 目录内容 | `intent.json`、`launched`、`closed`；**无 `failure.json`** |
| health 回执 | `files/runtime-health/bd0016c3….json`，`nonce=83f65e276b6542acb53ca4098c2af6ac` 与 trial 一致 |
| runtimeMode | **`proroot`**（回执中无 `fallbackFrom` 字段） |
| 检查项 | `assets` / `nativeModules` / `process` / `authentication` / `localApi` / `renderer` / `dataRead` / `dataWrite` / `storageFreshReopened` / `processExited` 全部 `true` |
| 端口 / 时序 | trial 端口 `46113`；`hostStageMillis` 记录 renderer / browser 握手与 `guestExitCheckEnd`，记录已 `closed` |

结论：**首次 proroot trial 直接成功**，没有 build363 中出现的 `ENOENT: scandir … node_modules` 失败与随后的兼容重试；成功路径不是 proot 兼容回退（`runtimeMode=proroot`、无 `fallbackFrom`），`cd4cfae2` 的修复在真机生效。

## 5. 更新后受管状态

| 项 | 值 |
|---|---|
| `files/linux/.runtime-descriptor.json` runtimeId | `bd0016c33e807a8e60cec8b3267e78fef0d003f618c083227cb651bd274eaabf` |
| `linux/ubuntu/usr/local/share/dsha/managed-assets-v2` 标记 | 同值 `bd0016c3…` |
| 安装前 runtimeId（363） | `5fd17eacd0597e478bb52b01f42e7659f084f90b30cf98566deb62cdac36a5fe` |
| 内置插件 `dsh-web-mobile` `client.js` SHA-256 | `3ddfe212c83e79f3c4eca75c670af291ee7ac9c8cc0a6d6d48e43d334e946cf8`（与 build363 回执相同；本版未改插件） |
| dsh 运行时版本 | `0.2.2-rc.1` |

## 6. 启动与崩溃检查

- 维护后在主界面点按「启动 DSHA」：新启动票据 `3259e1e6-8785-42aa-be6c-d96d8d1b559e`，运行方式 **proroot**。
- 后端鉴权：`BACKEND_AUTH_READY: 13.9s`（`nodeToAuthMs=6353`、`backendReadyMs=13860`）；主界面显示「工作台 · 已就绪」「实际 Web 端口 42117」。
- 点按「进入工作台」后 `WebPreviewActivity` 打开，启动记录更新为 `enStage=Web page ready`、`status=ready`、`BROWSER_READY: 238.9s`（其中约 225 秒是等待用户点按进入页面的空闲时间，非启动变慢）；`127.0.0.1:42117` 处于 LISTEN。
- `logcat -b crash`：**无** `io.github.sunsetrne.dsha` 的 FATAL / crash 记录（检查覆盖安装、维护、启动与打开页面之后）。

## 7. RC1 迁移与保留数据清单

- `files/rc1-migration-state/current.json`：`version=2`、`generation=de3fa589-…` 与安装前一致；`startupContext.mode=history-only`、`settingsSha256=null`、`proofDigest=dd210701…`；`startupId` 绑定本次启动票据 `3259e1e6…`。
- 本次为无迁移写入的普通启动，只读核对 history-only；`current.json` 按新启动票据更新绑定（`31f1d5b0` 的回执绑定语义），**未重写 settings 分节**（`settingsSha256=null`、generation 不变）。
- 设置 →「数据与备份」→「保留副本与旧环境」页面正常打开，显示「已显示 1–50 / 91 条」；RC1 记录显示为「rc1 迁移记录 · … · 活动目录 · 原时点已提交（历史，非本次核验） · 不自动删除」，即按 v3 语义投影为历史完成、只读、不授予自动删除。
- 本次启动未出现 RC1 迁移错误提示或待处理阻断。

## 8. 数据保留声明

- 维护任务 detail 原文见 §3。
- 复核对只读证据：`firstInstallTime` 不变；guest `/root/.dsh` 仍在（2 个会话工作区目录、2026-10-31 时点的 `.credentials.yaml`、attachments 与插件配置等旧数据保留）。
- 本回执不宣称逐文件 diff，也未做恢复演练；验收未卸载应用、未清数据、未写入用户数据。

## 9. 未覆盖 / 限制

- 未验证：SAF 增删改与 DocumentsUI 交互、平板横竖屏 / 深色主题 / 1.3× 大字体与 `LayoutAuditInstrumentation` 视觉门禁、回退（364→363 清数据）。
- 未验证：本设备之外的机型与系统版本；序列号、截图与原始日志按仓库规则不进入仓库。
- 未做故障注入；未在正式数据上执行强杀或删除文件。
- 本机（宿主平板）验收只用 `.debug` / `.dev` 测试包；一加 12 上按既有流程使用官方包覆盖，未在本机安装正式包。

## 10. 复核命令（脱敏）

```bash
adb -s <serial> shell dumpsys package io.github.sunsetrne.dsha | grep -E 'versionCode|versionName|lastUpdateTime|firstInstallTime'
adb -s <serial> shell pm path io.github.sunsetrne.dsha   # 对其 base.apk 执行 su -c 'sha256sum <base.apk 路径>'
adb -s <serial> shell su -c 'cat /data/data/io.github.sunsetrne.dsha/shared_prefs/dsha-data-task.xml'
adb -s <serial> shell su -c 'cat /data/data/io.github.sunsetrne.dsha/files/linux/.runtime-descriptor.json'
adb -s <serial> shell su -c 'cat /data/data/io.github.sunsetrne.dsha/files/runtime-health/bd0016c3*.json'
adb -s <serial> shell su -c 'ls -lt /data/data/io.github.sunsetrne.dsha/files/runtime-trials'
adb -s <serial> shell su -c 'cat /data/data/io.github.sunsetrne.dsha/files/rc1-migration-state/current.json'
adb -s <serial> shell logcat -b crash -d | grep -iE 'sunsetrne|deepseekharness'
```

> 说明：`runtime-trials/<trial-id>/failure.json` 与 `closed` 用于复核 §4；`files/runtime-health/<runtimeId>.json` 用于补证成功 trial 的 `runtimeMode` / `fallbackFrom`。这些路径都在应用私有目录，需要 root 或等价只读通道。

## 11. 结论

build364 官方包在本设备的 363→364 覆盖升级 **通过**：覆盖安装成功且安装后 `base.apk` SHA-256 与 Release `SHA256SUMS` 一致；首启只进入一次整屏维护页，维护任务 `SUCCEEDED`、`runtimeId` 迁至 `bd0016c3`；**首次 proroot trial 直接成功**（无 ENOENT 失败、无 proot 回退，`runtimeMode=proroot`）；维护后 dsh 在 proroot 下启动并到达 READY（后端鉴权 13.9s，进入页面后 `BROWSER_READY` / `status=ready`）；`logcat -b crash` 无本包记录；RC1 以 `history-only` 只读复用，保留数据清单按 v3 语义显示为历史、不自动删除；用户数据目录仍在。未覆盖项见 §9。
