# build361 真机覆盖验收回执（2026-11-13）

状态：**通过**（官方包主路径：358 → 360 → 361；零维护与「关于与许可」均实测）
范围：`io.github.sunsetrne.dsha` 正式包在真机上的覆盖安装、首启、受管运行时维护与 A1 用户可见项。
本回执只覆盖下表中的设备与路径，不扩写成全部机型 / 系统版本矩阵。

## 1. 设备与环境

| 项 | 值 |
|---|---|
| 设备 | 一加 12（PJD110，Android 16） |
| 连接 | USB 主机 + `adb`（termux adb server，端口 5038） |
| 既有正式包 | `io.github.sunsetrne.dsha` build358（`0.2.1-rc.5-20261111.0800-dsh0.2.2-rc.1`），首次安装 2026-10-30，最后更新 2026-11-11 |
| 共存包 | `io.github.sunsetrne.dsha.debug`、`dsh.android.client.rebirth`（本次未改动） |
| 正式包证书 | Mod 自有发布证书（与 Release 一致；覆盖安装未要求卸载） |

## 2. 安装与覆盖路径

两个包均从对应 GitHub Release 下载，先按 Release `SHA256SUMS` 校验再安装（未从其它来源取包）。

| 步骤 | 包 / 校验 | 结果 |
|---|---|---|
| 1 | build360 正式包 `dsha-a-su-f053b02f.apk`，SHA-256 `28b186ad671f19854efcac774217763b9e52b6be4b71d666ec2042ee5fddd2d9` | `adb install -r` 成功；包内 `versionCode=360`、`versionName=0.2.1-rc.5-20261112.1800-dsh0.2.2-rc.1` |
| 2 | build361 正式包 `dsha-a-su-faab36c5.apk`，SHA-256 `2b4cd9f09c8f1d6d220e3ae9b2d3b3bcc90df8186414496d2f9293b62d80bd6d` | `adb install -r` 成功；包内 `versionCode=361`、`versionName=0.2.1-rc.5-20261113.0000-dsh0.2.2-rc.1` |

### 2.1 358 → 360：预期进入一次受管运行时维护

- 首启进入 `ExtractActivity`「运行环境维护」，自动开始五步维护；观测到第 3/5 步（停止 Web 与终端任务、解压新版 dsh 已完成，准备内置插件与界面适配进行中）。
- 维护自动完成后进入 `MainActivity`，启动页显示 `READY`。
- 结论：358→360 的受管运行时变化按 I6 预期进入一次维护页并正常完成，无需人工干预；个人数据保留（同一正式包覆盖、未卸载）。

### 2.2 360 → 361：零维护

- 覆盖安装后首启**直接进入 `MainActivity`**，未出现「运行环境维护」页；`dumpsys activity activities` 中 `ExtractActivity` 计数为 0。
- 启动页 `READY`；`versionCode=361`，设置页底部技术说明显示 `DSHA v0.2.1-rc.5-20261113.0000-dsh0.2.2-rc.1`。
- 结论：与 A1 的 `runtimeId=4d55973d` 不变一致，**360→361 覆盖升级零维护**成立。

## 3. A1 用户可见项（「关于与许可」）

- 路径：设置 →「关于 DSHA」→ 页内标题「关于与许可」。版本行显示 `0.2.1-rc.5-20261113.0000-dsh0.2.2-rc.1 · 361`，包名 `io.github.sunsetrne.dsha`，架构 `arm64-v8a`，DSH `0.2.2-rc.1`。
- 「第三方许可」区块列出随包许可：Bouncy Castle、certifi（MPL-2.0）、DSHA（MIT）、GitHub CLI（MIT）、GPL-3.0、LGPL-3.0、Gson（Apache-2.0）、MPL-2.0、proot（GPL-2.0）、Shizuku（MIT）、SnakeYAML（Apache-2.0）、Termux terminal（Apache-2.0），并提供「许可声明全文（在线）· THIRD_PARTY_NOTICES.md」入口。
- 点按「DSHA · MIT」弹出正文对话框，完整显示 MIT 文本并可「确定」关闭；标题与正文与随包许可文件一致。
- 结论：A1 出口要求的「宿主固定入口 + 第三方许可列表 / 正文 / 在线声明」在正式包真机可用。

## 4. 日志核对

- `logcat -b crash`：**无** `io.github.sunsetrne.dsha` 的 FATAL / crash 记录（仅有其他应用的历史记录）。
- `logcat` 普通缓冲：本次启动只出现一条 `WindowManager` 的 `dispatchAppVisibility ... MainActivity EXITING` 警告（窗口退出过渡，属正常，不影响功能）。
- 未发现维护事务失败、环境回退、启动恢复页或 ANR 记录。

## 5. 未覆盖 / 限制

- 只验证 358→360→361 这一条主路径；未验证 360→361 在其它机型 / Android 版本上的表现。
- 未测平板横竖屏、深色主题、1.3× 字体下的「关于与许可」布局；未跑 `LayoutAuditInstrumentation` 视觉门禁。
- 未验证 361→362 或后续版本的升级（依赖下一轮发布）。
- 设备截图与 logcat 留在测试机私有缓存，按仓库规则**不进入仓库**；本回执只记录结论与可复核命令/摘要。

## 6. 复核命令（脱敏）

```bash
# 版本
adb -s <serial> shell dumpsys package io.github.sunsetrne.dsha | grep -E 'versionCode|versionName'
# 当前前台
adb -s <serial> shell dumpsys activity activities | grep ResumedActivity
# 维护页是否出现（0 = 未出现）
adb -s <serial> shell dumpsys activity activities | grep -c ExtractActivity
# 崩溃日志（应为空）
adb -s <serial> shell logcat -b crash -d | grep -iE 'sunsetrne|deepseek'
```

## 7. 结论

build361 官方包在本设备的 360→361 覆盖升级通过：**零维护**、`READY`、「关于与许可」与第三方许可列表 / 正文 / 在线声明可用，未发现崩溃或维护失败。G1（同包名 / 同签名 / versionCode 单调）与 G2（覆盖后数据与界面可用）在本次真机路径上得到证据；build361 的发布说明中「未做真机覆盖安装验收」的表述由本回执补充。
