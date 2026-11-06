# 隐藏后台（从最近任务隐藏 DSHA）实现记录

- 日期：2026-11-06（UTC）
- 分支：`feat/hide-recents`，基线 `origin/main` = `9cc91f5e`（PR #3 SAF 已合并）
- 需求：ADB 保活的辅助项，在 设置 → 体验 增加独立开关，把 DSHA 的任务从最近任务隐藏；不改 ADB 通道、常驻通知、前台服务与停止语义。
- 参考：`/tmp/keepalive-feasibility.md` §5–§9（只读评审报告，未入仓库）。

## 1. 行为

| 状态 | 行为 |
|---|---|
| 默认（关） | 与现状一致：只留一张主卡，浏览器 / PiP / 旧 Main 壳按原策略隐藏 |
| 开 | 本应用全部有效任务 `setExcludeFromRecents(true)`，同一任务立即从最近任务消失；常驻通知、Web、dsh 与 ADB 保活都不受影响 |
| 关 | 先全部恢复显示，再按现有单张主卡规则重新分类 |
| App 重启 / 进程被杀后重开 | `MainActivity` 的 `rememberMain`（create / resume）重新读偏好并应用 |
| 应用失败 | 偏好已保存，`DiagnosticLog` 记录 `RECENTS_HIDE_APPLY`，toast 提示，下次打开重试；不崩溃、不重放 |

边界：`DeviceBridgeService` 在主进程，整包被滑卡清掉时 ADB 保活一起失效；本开关只降低「用户滑卡 / 一键清理」这一类风险，不改变进程优先级，不防 Doze、厂商省电或强行停止。开关不检查、不启动、不停止 `DeviceBridgeService`。

## 2. 实现

- `util/Constants.java`：新增 `KEY_HIDE_FROM_RECENTS = "hide_from_recents"`。
- `core/ConfigStore.java`：`isHideFromRecents()` / `setHideFromRecents(boolean)`，`flag(..., false)`；**不加入** `BACKUP_SETTING_KEYS`，与屏幕常亮一样是本机偏好。
- `util/TaskNavigationPolicy.java`：新增 5 参 `recents(..., boolean hideAll)`；`taskId < 0` 仍 `KEEP`，`hideAll` 时其余全部 `HIDE`；4 参重载委托 `hideAll=false`。
- `ui/BrowserTasks.java`：
  - `reconcileRecents` 读取一次偏好并传入策略，返回是否成功；启用时失败写诊断日志。
  - 新增 `applyRecentsPreference(Context)`：枚举本应用全部任务写入偏好值；关闭时再走 `reconcileRecents` 恢复单张主卡分类。
- `ui/SettingsFragment.java`：在「体验」组「省电模式」之后绑定新行；切换即保存 + 立即应用 + 失败 toast；整行可点。
- `res/layout/fragment_settings.xml`：新行 `settings_hidden_recents`（沿用 `ShellRow` / `ShellRowIcon` / `ShellRowTitle` / `ShellRowSub` / `ShellSwitch`）。
- `res/drawable/ic_ui2_hide_recents.xml`：20dp、24 viewport、`text_secondary` 描边的眼睛 + 斜杠图标，风格对齐 `ic_ui_keep_screen_on.xml`。
- 文案：`tools/i18n/messages.json` 新增 `m9025`–`m9028`，8 个 `tools/i18n/translations/*.json` 补齐十语。
- `app/src/main/assets/runtime-descriptor.json`：`ConfigStore.java` 属于 descriptor 的 `launcherSources`，改字节后按 `tools/prepare-runtime-descriptor.py --write` 重算 `runtimeId` 与该文件摘要。
- 未改：`AndroidManifest.xml`（无静态 `excludeFromRecents` / alias）、`HarnessService`、`DeviceBridgeService`、`BootReceiver`、`AdbKeepAliveReceiver`、`RuntimeTasks`、备份白名单、版本与发布文件。

## 3. 测试与门禁回执（2026-11-06）

- `dsha-gradle :app:testStandardDebugUnitTest --tests '*TaskNavigationPolicyTest' --tests '*BrowserTasksTest' --tests '*ConfigStoreHideFromRecentsTest'`：**通过**（TaskNavigationPolicyTest 5、BrowserTasksTest 43、ConfigStoreHideFromRecentsTest 3，0 失败 / 0 错误）。
- `dsha-gradle :app:compileStandardDebugJavaWithJavac`：**通过**。
- `python3 tools/check-ui-i18n.py`：`newFindings=0`，无 `NEW_UI_I18N`。
- `python3 tools/localize-android-ui.py --resources-only --check`、`python3 tools/test-ui-language-catalog.py`：**通过**。
- `python3 tools/check-source-hygiene.py`：**通过**（含刷新 `translations/{ar,fr,ja,ko,pt,ru}.json` 的语言字节钉）。
- `python3 tools/format-java-tree.py --check --java /usr/lib/jvm/java-17-openjdk-arm64/bin/java`：1536 个受管 Java，0 需格式化。
- `git diff --check`：**通过**。
- 首次构建需要按 `ci/sunset-inputs.lock.json` 还原固定输入（本机用 `tools/ci-assets.py --archive ... --sha256 ...`，`PASS locked CI asset inputs`）。

## 4. 未验证 / 风险

- **布局人工验收未做**：日 / 夜、360dp、1.3 倍字体、中 / 英 / 阿拉伯语 RTL 的截图核对未执行；`androidTest` 源集存在既有编译错误（`RecoveryInstrumentation.java` 调用 `HarnessService.refreshPowerMode()` 少传 `Context`），`ThemeInstrumentation` / `LayoutAuditInstrumentation` 不能作为本分支门禁。
- **真机功能未验证**：未构建 / 安装 `io.github.sunsetrne.dsha.test`，未在设备上核对最近任务卡片、通知、PiP、重启恢复与 OEM 行为；本机也不得覆盖安装正式包。
- **ROM 差异**：个别 ROM 可能忽略 `AppTask.setExcludeFromRecents` 或在当前前台任务上延迟生效；失败路径会保存偏好并重试，但无法保证所有厂商一致。
- **非保活承诺**：不防 Doze、厂商省电、强行停止或系统回收；文案与文档都不把它写成「后台保活」。

## 5. 建议验收步骤（测试包，需用户操作手机）

1. 默认关 → 最近任务显示 DSHA 卡片；打开开关 → 同一任务立即消失、常驻通知仍在、dsh Web 可访问；
2. 关闭开关 → 卡片恢复，重复 Main / 浏览器任务仍只留一张主卡；
3. 强杀 App 后从桌面图标重开 → 偏好仍生效；
4. 通知「回到网页」、Main ↔ Web、PiP、返回主界面正常；
5. 记录至少一台 OEM ROM（如本机 Lenovo Android 16）的结果，不外推到全部厂商。
