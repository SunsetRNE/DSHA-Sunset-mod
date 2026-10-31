# 落地记录：首次引导重构 · 部署胶囊 · 覆盖更新改为受限主界面

日期：2026-10-31 · 分支 `feat/onboarding-redesign`（基于 main build217 `5a768d0`）· 设计依据：同目录 [README.md](README.md)、[preview.html](preview.html)、[external-authorizations.md](external-authorizations.md)

本文记录落地时的决策、与原型的差异，以及第 3 项要求“覆盖更新运行时能否使用同一风格”的评估结论。最终验证范围以本轮交付回执为准；**本轮没有在真机上覆盖安装验收**。

## 1. 结构

| 原有 | 现在 | 说明 |
|---|---|---|
| `WelcomeActivity`（3 页 ViewPager2）+ `OnboardingPrepareActivity`（“4 / 4”）+ `OnboardingUi` | `OnboardingActivity`（单 Activity，4 步：欢迎 → 用途 → 授权 → 完成） | 启动契约不变：`welcomed == false` 只进引导；Main / 启动恢复页 / 终端入口照旧校验 |
| `ExtractActivity` 整屏等待首次部署 | 「开始部署」即在后台启动应用级维护任务，进度进入**部署胶囊** | 复用 `BackupTask` 与 `DataProtectionService` 前台通知；部署期间引导页保持屏幕常亮 |
| 覆盖更新后 `MainActivity` 跳整屏 `ExtractActivity` 自动更新 | 进入**受限主界面 + 顶栏胶囊**；只有磁盘上存在未完成维护事务时仍强制进入维护页 | 见 §3 |
| `ExtractActivity`（旧版式） | 同一个类，换成引导同款版式（阶段条 + 阶段列表 + 底部操作） | 保留全部 extra、保存态字段与审计依赖（`render()` / `lastRender` / `automaticEntry` / `R.id.extract_*`） |
| 授权只在 设置 → 设备能力授权 | 引导第 3 步 `PermissionPanel`（7 类 + ADB），按第 2 步用途推荐与排序 | 全部默认关闭、可跳过；外跳只由点击触发；返回后 `onResume` 复查 |

共用构件：

- `ui/OnboardingKit`：版式构件（标题 / 按钮 / 状态标签 / 提示条 / 阶段条 / 阶段列表 / 自适应网格 `Grid` / 换行 `FlowRow`），只用 `shell_*` 与 `onb_*` token。
- `util/DeployStages`（纯逻辑，配单测）：把任务快照的进度文案映射为固定阶段。**不推导百分比**；识别不了的进度行保持已到达的阶段，原文照常显示。
- `res/values/onboarding_ui.xml`、`values-night/onboarding_ui.xml`、`drawable/bg_onb_*`、`ic_onb_*`。

## 2. 自适应

| 条件 | 行为 |
|---|---|
| 宽 < 600dp（手机竖屏） | 单栏；胶囊在顶栏正中；底部按钮纵向铺满 |
| 宽 ≥ 600dp（平板、手机横屏、分屏宽窗） | 两栏：左栏为步骤 + 部署进度卡；右栏正文；底部按钮靠右横排，放不下时换行 |
| 内容区 ≥ 约 700dp | 授权卡片两列，单独一张或“宽卡片”（设备命令通道）占满整行 |
| 高 ≤ 480dp（手机横屏） | 压缩页头、页脚与按钮高度 |

判定读当前 `Configuration`（`screenWidthDp` / `screenHeightDp`），旋转与分屏走 Activity 正常重建，步骤与用途选择由 `onSaveInstanceState` 与 `dsha-onboarding` 偏好恢复。正文始终在 `ScrollView` 内，1.3 倍字体与短屏下可完整滚动，底栏始终可见。

## 3. 覆盖更新运行时：能否使用同一风格（评估结论：可以，已采用）

### 结论

覆盖更新与首次部署是**同一个应用级任务**（`BackupTask.updateEnvironment()` → `EnvironmentMaintenance.update()`；首次安装时它落到冷部署 `initializeFresh`），进度来源、失败语义、应急入口完全一致，只是阶段表不同。因此胶囊、阶段条与维护详情页直接复用，**不需要单独设计一套界面**；差异只在三处：

1. **阶段表**：`DeployStages.Mode.UPDATE`（停止 Web → 解压新版 dsh → 内置插件与界面适配 → 切换受管文件 → 隔离试运行），`REBUILD`（保护数据 → 解压 → 逐文件恢复 → 试运行），由任务种类与进度文案自动识别（`DeployStages.infer`），同一任务中途从“更新”转为“重建”时界面随之切换。
2. **宿主**：首次部署的胶囊在引导页；覆盖更新的胶囊在主界面顶栏（平板在顶栏右侧）。
3. **完成后的去向**：首次部署成功 → 完成页“进入主界面”并自动启动一次 Web；覆盖更新成功 → 胶囊变绿约 5 秒后收起，**不自动启动 Web**（与旧版一致，用户自己点启动）。

### 为什么覆盖更新不再整屏挡住仍然安全

| 安全约束（AGENTS.md 启动契约 / 已知 trap） | 落地方式 |
|---|---|
| 受限入口不能伪造就绪标记 | 受限主界面只是“不跳转”；不写 `.offline-extracted`、健康回执或任何就绪证明。Launch / 插件 / 终端沿用既有 `needsRecovery` 门禁，环境未就绪时保持禁用 |
| 未完成的维护事务必须先恢复 | `MaintenanceCoordinator.pending()` 为真时仍然进入维护页（`ExtractActivity`），用户需显式“恢复中断维护” |
| 自动更新不能写入“已选择受限入口” | 自动路径**不调用** `allowLimitedEntry`、不带 `limited_entry`。否则更新途中进程被杀后，下次启动会因 limited 短路掉 pending 检查 |
| 同一候选只自动尝试一次，旋转 / 进程重建不重复覆盖环境 | 自动开始逻辑原样搬入 `RuntimeMaintenanceStarter.autoStartUpdateIfEligible`，继续使用 `dsha_environment_upgrade/attempted_identity` 与 `EnvironmentIdentity.shouldAutoStart` |
| 成功文案不是就绪证明 | 胶囊“成功”状态要求 `SUCCEEDED` 且就绪检查通过；否则显示黄色“环境检查未通过”，不自动跳转 |
| 历史任务不能冒充当前进度 | `BackupTask` 快照跨进程持久化：胶囊只把本进程内观察到运行过的任务显示为成功；旧的成功记录不再出现，旧的失败记录只在环境仍需处理时显示 |

### 刻意保留的旧行为

- 通知点击（`DshaApp.dataProtectionPage`）仍进入 `ExtractActivity` 的 `review_only` 详情页（静态守卫 `tools/test-notification-actions.py` 锁定）。
- 主界面横幅：环境维护进行中由胶囊取代；“受限模式：环境需要恢复”“启动未完成”、备份 / 恢复备份等非环境任务仍用原横幅。

## 4. 与原型的差异

| 原型 | 落地 | 原因 |
|---|---|---|
| “隔离试运行”是独立阶段（示意命名） | 并入最后一段“检查启动配置与隔离试运行”（首次）/“隔离试运行与就绪检查”（更新） | 真实任务里试运行的进度文案（`正在核验原生模块…` 等）与启动检查交错出现，拆开会来回跳 |
| 首次部署 8 段 | 8 段：准备 / Ubuntu 与 Node / dsh 与内置依赖 / Python 与 pnpm / 应用工具 / curl·git·证书 / 核对并提交安装文件 / 启动检查与试运行 | “核对并提交”对应 `ColdBundleTransaction` 的真实阶段 |
| 空间、端口、su 路径为示意值 | 全部读真实值 | 空间判定 = `expandedEnvironmentBytes()` + 64 MB 余量，与 `ColdBundleTransaction` 的 `COLD_INSTALL_SPACE_REQUIRED` 同一判据 |
| Stellar“可能显示尚未安装 Shizuku（未验证）” | 已识别 `roro.stellar.manager`：状态文案、`<queries>` 包可见性、“打开管理器”入口均支持 Stellar | 不向 Stellar 发送任何未核实的私有广播；Binder 仍只接受 `moe.shizuku.server.IShizukuService` 且核对管理器 UID |

## 5. 新增能力（均只读或仅在点击后发起）

- 通知：Android 13+ 点击后申请 `POST_NOTIFICATIONS`；被拒后改为“去系统通知设置”。
- 电池优化：新增 `PowerManager.isIgnoringBatteryOptimizations` 只读检测；跳转仍是通用电池优化设置，**不申请豁免**（`BatterySettingsNavigationTest` 锁定）。
- 无障碍设置文案统一为系统列表实际显示的「DSH-A-Su 屏幕控制」。

## 6. 未验证

- 未在真机上安装、覆盖升级或走完首次部署；系统设置页的实际标题与 OEM 差异、Stellar 兼容层的实际授权交互、部署期间切到系统设置后 ROM 是否结束进程，均待真机核对。
- 本机（aarch64）单测环境缺 Robolectric 原生图形库，依赖原生绘制的用例以远端 x86-64 CI 结果为准。
