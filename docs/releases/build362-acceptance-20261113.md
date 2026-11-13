# build362 真机覆盖验收回执（2026-11-13）

状态：**通过**（主路径：361 → 362，官方包覆盖；一次受管运行时维护完成、READY、无崩溃）
范围：`io.github.sunsetrne.dsha` 正式包在真机上的覆盖安装、首启维护与 A2 账本迁移的可达性。
本回执只覆盖下表中的设备与路径，不扩写成全部机型 / 系统版本矩阵。

## 1. 设备与环境

| 项 | 值 |
|---|---|
| 设备 | 一加 12（PJD110，Android 16） |
| 连接 | USB 主机 + `adb`（termux adb server，端口 5038；本次连接多次 USB 重枚举，重启 server 后恢复） |
| 覆盖前版本 | build361（`0.2.1-rc.5-20261113.0000-dsh0.2.2-rc.1`，versionCode 361；同设备此前已从 358→360→361 升级） |
| 共存包 | `io.github.sunsetrne.dsha.debug`、`dsh.android.client.rebirth`（本次未改动） |
| 正式包证书 | Mod 自有发布证书（与 Release 一致；覆盖安装未要求卸载） |

## 2. 安装与校验

- 包：`dsha-a-su-75924add.apk`，316,867,394 B；SHA-256 `af021f2cf6ec5acb802fbf16aa2d8af4d1bc4cff686dce287c54a510441b4e31`，与 Release `SHA256SUMS` 逐字节一致。
- `adb install -r` 成功；包内 `versionCode=362`、`versionName=0.2.1-rc.5-20261113.1000-dsh0.2.2-rc.1`。

## 3. 361 → 362：一次受管运行时维护（预期）

- 首启进入 `ExtractActivity`「运行环境维护」（A2 slice1+2 改变 `saf/` 的 `launcherInputs`，`runtimeId` 4d55973d→a2847544，**一次维护是预期行为**）。
- 维护在约 2 分钟内自动完成，进入 `MainActivity`；启动页 `READY`。
- 维护完成后曾短暂显示「环境维护进行中 · 相关操作完成前暂缓」提示条，约 45 秒后自行消失，`READY` 保持稳定；未出现启动恢复页、未要求重新走引导。
- 数据保留（同包名同签名覆盖、未卸载）。

## 4. 日志核对

- `logcat -b crash`：**无** `io.github.sunsetrne.dsha` 的 FATAL / crash 记录。
- 普通日志：只有 OPPO/ColorOS 系统组件的既有警告（`OplusScrollToTopManager ... Receiver not registered`）与窗口生命周期日志；无本应用 ANR、维护失败或启动恢复记录。
- 维护窗口内 `ExtractActivity` 正常销毁并切到 `MainActivity`（窗口日志一致）。

## 5. SAF / A2 可达性

- 设置 →「设备能力授权」→「跨应用文件访问」页面可正常打开（未崩溃），显示当前为「未授权」。
- 本机此前没有通过该功能授权过目录，因此 **旧 `saf_grants` → 新账本的迁移在本设备上没有真实数据可导入**；账本读取路径（列表为空）可达，但没有验证「已有旧授权被迁移后仍可见/可用」。
- 受 USB 反复重枚举影响，本轮未完成交互式「添加目录 → 读取/写入 → 撤销 → 重新添加」全流程；该流程与 `documentsTree` 过滤、Provider 超时、8 树满额一起留给 A2 slice3/slice4 验收。

## 6. 未覆盖 / 限制

- 未验证：SAF 添加/撤销/重加的真实交互、旧 `saf_grants` 有数据时的迁移结果、DocumentsUI 选择器、Provider offline/timeout、8 树满额、工作台 UI（slice3 未实现）。
- 未测：平板横竖屏、深色主题、1.3× 字体；`LayoutAuditInstrumentation` 视觉门禁。
- 未验证回退（362→361 清数据）与本设备之外的机型。
- 设备截图与 logcat 留在测试机私有缓存，按仓库规则**不进入仓库**。

## 7. 复核命令（脱敏）

```bash
adb -s <serial> shell dumpsys package io.github.sunsetrne.dsha | grep -E 'versionCode|versionName'
adb -s <serial> shell dumpsys activity activities | grep -E 'ResumedActivity|ExtractActivity'
adb -s <serial> shell logcat -b crash -d | grep -iE 'sunsetrne|deepseek'
```

## 8. 结论

build362 官方包在本设备的 361→362 覆盖升级通过：**一次受管运行时维护按预期完成**、`READY`、无崩溃/ANR；A2 的 `saf/` 账本变更进入正式包且 UI 可达。旧授权迁移与 SAF 增删改仍需在 slice3/slice4 做真实数据与交互验收；build362 发布说明中「未真机验收」的表述由本回执补充。
