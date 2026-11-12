# 评审 C：十个模块的职责拆分与缺失模块补充

核验：2026-11-12，build359 / `0.2.1-rc.5`。实测 964 个 `.java`、0 个 `.kt`、706 个 `*Test.java`；包级为 ui 169、util 404、backup 83、runtime 63、core 60、vscreen 36、根包 36。AGENTS.md 的「ui 69 / core 27 / util 118」是 build170 历史索引，不能作拆分依据；下文归属均给文件或包级证据。

## 一、结论摘要

1. 骨架正确，三处切法必须改：**1 与 8 不重复**（1 是授权状态，8 是执行通道），但当前互相渗透，要按「状态/派发」与「通道/白名单」重切；**3 必须拆**为 APK 自更新与受管环境安装；**10 必须拆**为物理屏与虚拟屏，只统一凭据语义，不统一实现。
2. 缺失候选 **7 个必须独立**（宿主数据与备份、受管安装、进程身份、桥入站、配置与状态、凭据、网页宿主）；i18n/主题并入 UI，事件总线不做。
3. 现有代码比文档干净：`runtime/`、`backup/`、`bridge/`、`core/` 对 `ui/` 零 import，由 `tools/test-core-boundaries.py` 强制；仍须切断四处腐化：screen↔vscreen 双向 import（1+6 处）、`util/ScreenSessionGrant.java:4` 反向 import core、根包 3 个服务 import ui Activity、`UpdateEngine` 静态单例。

## 二、十个模块逐个评审

| # | 用户模块 | 职责边界 | 输入→输出契约 | 现有归属 | 判断 | 依赖 | 失败模式 |
|---|---|---|---|---|---|---|---|
| 1 | 安卓权限控制管理 | 授权状态机：系统权限申请、自有开关、敏感界面分档、撤销；只决定「能不能」，不执行 | 请求意图 → 授权快照 + `policyRevision`/`authorityEpoch` + 一次性 dispatch permit | `core/DeviceGrants.java:9`（552 行）、`capabilities/CapabilityBroker.java:16`、`util/ScreenSessionGrant.java` | **重切**：吸收模块 8 的开关面与 Broker，改名「授权与能力派发」 | 配置、诊断；被桥/屏幕/设备通道依赖 | 记录损坏 fail-closed 回 OFF；唯一确认 slot，取消返 NOT_SENT；撤销后迟到 allow 不得重授权 |
| 2 | 网络请求管理 | 出站请求：下载、更新包、健康探测、网络代次；不含 3090 入站与 LAN | URL/截止/代次 → 有界字节流 + 结果分类 + 已核验续传偏移 | `transfer/DownloadEngine.java:29`、`util/ResumableDownload.java:13`、`nethealth/NetworkHealthMonitor.java:31`、`util/NetworkEpochPolicy.java` | **拆分**：出站独立为「网络与传输」，入站归「桥与入站网络」。现状无统一 HTTP 栈，合并是伪抽象 | 配置、凭据、诊断 | 切网按 epoch 弃旧连接不重放；续传只信已核验偏移；下载完成≠签名通过 |
| 3 | 更新与覆盖 | 一个词覆盖两件事：APK 自更新（检查/下载/签名/覆盖安装）与受管环境安装（描述符/候选/试运行/事务切换） | ①channel → APK 候选；②`RuntimeDescriptor` → 候选树 → `RuntimeTrial` → 事务提交 | `core/UpdateEngine.java:30`、`core/UpdateRepository.java:12`、`UpdateDownloadService.java`、`core/EnvironmentMaintenance.java:14`、`runtime/InstallPipeline.java:17`、`backup/ManagedRuntimeTransaction.java:8`、`runtime/RuntimeTrial.java:13`、`backup/RuntimeDescriptor.java:7` | **拆两个模块**：失败域不同（签名/安装器 vs 进程/数据事务），核心不变式是「APK 更新≠环境就绪」 | 网络传输、宿主数据、生命周期 | 下载可续传、安装不可重放；试运行失败回切保留原件；未关闭现场不提交候选 |
| 4 | 日志输出与记录 | 有界、脱敏的事件记录与导出，不是通用 logger | 事件 → 环形历史 + 脱敏导出包；禁写正文与凭据 | `core/DiagnosticRepository.java:20`、`core/DiagnosticLog.java`、`core/ErrorLogRepository.java:19`、`core/StartupDiagnostics.java:12`、`backup/DiagnosticExportArchive.java`、`diagnostics/DiagnosticPrivacy.java` | **改名「诊断与可观测」**：实际模型是诊断域，不是 logcat 流 | 各模块写入；不依赖 UI | 写失败降级内存环形并告警一次；崩溃处理不得递归；脱敏失败拒绝导出 |
| 5 | 生命周期与挂载 | 生命周期=代次/停止意图/出生身份/租约/维护屏障；挂载=纯 argv/env/BINDS 组装，无状态 | `Start(请求, expectedGeneration)` → `Run(generation,port,url)` 或停止证据；`LaunchSpec` → argv | `core/WebLifecycleController.java:24`（`WebRun.generation:38`）、`runtime/WebProcessManager.java:19`、`core/MaintenanceCoordinator.java:14`、`runtime/ContainerRuntime.java:22`、`runtime/ProotBootstrap.java:20` | **拆两个模块**：合并会让「谁能启动」与「怎么拼命令」互相污染，也挡住 Rust 承接组装 | 进程身份、配置、受管安装 | 证据不足保留屏障、不切数据树；端口不复用旧记录；出生身份失败进强制结束入口 |
| 6 | 外壳 UI 渲染框架 | 原生界面（Activity/Fragment/对话框/主题/i18n）+ 交互呈现（通知/悬浮条/助理）；只读快照、提交请求 | 状态快照 → 视图；用户操作 → 请求对象 | `ui/` 169 类、`util/UiText.java`、`ui/LanguageController.java`、`ui/ThemeController.java`、`tools/i18n/messages.json`（3763 条）、`assistant/` 11 类、`OverlayController.java` | **保留并吸收 i18n/主题/交互呈现**；网页宿主（`WebPreviewActivity`/`WebPageScripts`/IME/PiP）拆出 | 各模块窄快照/请求端口（core 现零 ui import） | 切语言/主题只重建界面，不停终端或 Web；快照过期不以按钮文字推断已退出 |
| 7 | 终端管理 | 真 PTY 与简易终端的会话身份、显示编号、退出回收、维护前关闭 | 打开(shell/cwd/env) → 会话 id + 流；关闭 → 该会话退出证明 | `PtySession.java`、`runtime/TerminalRuntime.java`、`core/TerminalSessionOwner.java:18`、`TerminalRuntimeService.java`、`core/SimpleTerminalBackend.java` | **保留不拆** | 进程身份、命令与挂载、维护屏障 | 出生身份未登记不启动；未核验退出不移除标签；会话 ID 永久递增 |
| 8 | Root 与 ADB 集成 | 特权通道可用性、选择顺序、命令白名单；不持有授权开关，不弹同意 | 命令计划/目标 → 一次通道选择 → 结果 + 实际身份 | `util/DeviceShellPolicy.java:12`（860 行）、`DeviceShellExecutor.java`、`DeviceBridgeService.java`、`RootShell.java`、`ShizukuShell.java`、`bridge/AdbBridge.java`、`device/` 6 类、`extdev/` 3 类 | **保留但与模块 1 不重复**；去开关面，改名「设备执行通道」 | 授权派发、进程身份 | 默认拒绝、未知命令不放行、POLICY_BLOCKED/126 无放行；UNKNOWN 不换通道；配对≠连接≠授权 |
| 9 | 插件热更新热检查 | 系统/用户插件分层、启停意图、候选事务、变更检测；**不是容器**（同 UID 进程内插件树） | 安装(来源,包) → 候选+依赖摘要 → 提交/隔离；监测 → 合并后事件 | `plugins/PluginManagementEngine.java:24`（930 行）、`plugins/PluginChangeDetector.java:29`（721 行）、`core/PluginRepository.java:34`（2148 行）、`assets/plugin-transactions.py` | **改名「插件生命周期与热检查」**；热检查不得独立，须与 writer 锁同所有者 | 配置、诊断、生命周期（重启 Web 生效） | 未提交事务保留现场并拦启动；30s 预算含排队；启停意图优先于合并刷新 |
| 10 | 识屏与无障碍统一化 | 观察（帧/树/节点）与动作（坐标/语义/锚定），物理屏与虚拟屏两套实现；统一化只统一凭据语义与结果分类 | `observe(target)` → 帧/树 + 凭据；`act(凭据)` → 已提交/UNKNOWN，绝不重放 | `screen/` 19 类、`DshaAccessibilityService.java:16`、`capabilities/` 6 类、`control/` 5 类、`vscreen/` 36 类（`VirtualScreenManager.java:34`） | **拆为物理屏 / 虚拟屏**；共享契约放授权派发，不做两屏统一实现 | 授权派发、设备通道、进程身份 | 坐标凭据 30s/一次/绑帧；语义凭据绑树+节点；UNKNOWN 不重放、不换屏兜底；HANDOFF 暂停助手 |

## 三、缺失模块补充

| 候选 | 结论 | 理由与证据 |
|---|---|---|
| 宿主数据与备份事务 | **必须独立** | 83 类，唯一允许删除/覆盖用户数据的模块；`backup/NativeBackupJobs.java:18`（1591 行）、`backup/HostDataTransaction.java:7`（v5/编号槽位/NOFOLLOW） |
| 受管运行时安装与描述符 | **必须独立** | runtimeId/inputs 是独立身份，不能由 APK 版本推导；`runtime/InstallPipeline.java:17`、`backup/RuntimeDescriptor.java:7`、`backup/ManagedRuntimeTransaction.java:8` |
| 虚拟屏 | **必须独立** | 36 类 + 独立 `app_process` Core；与物理屏共享的是能力请求，不是生命周期 |
| 配置与状态存储 | **建议独立** | `core/ConfigStore.java:14`（956 行）是唯一读写入口；另有 `data/PortableSettings`、`backup/ProfileSettingsTransaction` |
| 凭据与密钥 | **建议独立** | `data/KeyVault.java:24`（Keystore AES/GCM）、`assets/credential-paths.json`、`backup/CredentialProjection.java`；失败域独立（四态、备份排除） |
| 进程/会话身份与看门狗 | **必须独立** | `util/ProcessBirthHandshake`、`ProcessIdentity`、`WebPidIdentity`、`WebProcSel`、`ProcessTermination`；被生命周期、终端、虚拟屏、安装四方共用 |
| 诊断与可观测性 | **不新增** | 已被用户模块 4 覆盖 |
| 桥协议与 AppBridge 契约 | **必须独立** | 现实中不存在 `bridge/AppBridge.java`（文档漂移）：契约由 `HttpShellService.java:48`（4889 行，`BRIDGE_PROTOCOL=3` 见 `:2623`）与 `util/BridgeRoutes.java:8` 组成 |
| i18n 与主题 | **并入 UI** | `tools/i18n/messages.json` 3763 条；「系统语言最早锁存」作为 UI 模块内单列契约 |
| 事件总线/跨模块端口 | **暂不需要** | 现有显式端口（`RuntimeHostPorts`/`MaintenanceGate`/`RuntimeWorkPort`）已够；总线会引入隐式依赖，与规范相反 |
| 测试与构建流水线 | **非产品模块** | 归工程治理：`tools/test-core-boundaries.py`、`tools/host-tests.manifest.json`（252 项）、108 个 `test-*.py` + 130 个 `test-*.mjs` |
| legacy 兼容层 | **强制隔离** | `LegacyBackupImporter`/`LegacyTarReader`/`LegacyConfigurationSnapshot`/`BrowserOriginRestore*`/旧偏好键；唯一允许「读旧写新」的区域 |
| 独立应急环境 | **强制隔离** | `recovery/` 5 类：独立 HOME/工作区/五项工具；broker 不能自行批准写入 |
| 交互呈现 | **并入 UI** | `assistant/` 11 类、`OverlayController`、`AgentTaskNotifications`；悬浮窗权限与前台租约在 UI 内单列 |
| 文件与 SAF | **并入桥/入站** | `saf/` 4 类、`DshaDocumentsProvider`、`backup/DocumentStreams`；共用授权簿与一次性 token |

## 四、模块依赖 DAG

```mermaid
graph TD
  subgraph L0[L0 基础设施]
    M01[配置与状态] M02[凭据与密钥] M03[诊断与可观测] M04[进程身份与退出证据]
  end
  subgraph L1[L1 授权与设备]
    M05[授权与能力派发] M06[设备执行通道] M07[物理屏] M08[虚拟屏]
  end
  subgraph L2[L2 运行环境]
    M09[生命周期与租约] M10[命令与挂载] M11[受管安装] M12[宿主数据与备份] M13[插件] M14[终端]
  end
  subgraph L3[L3 宿主表面]
    M15[桥与入站网络] M16[网络与传输] M17[应用更新] M18[外壳UI] M19[网页宿主]
  end
  subgraph ISO[强制隔离]
    M20[legacy 兼容] M21[独立应急]
  end
  M18 --> M05
  M18 --> M09
  M18 --> M12
  M18 --> M13
  M19 --> M09
  M19 --> M15
  M19 --> M18
  M15 --> M05
  M15 --> M06
  M15 --> M07
  M15 --> M08
  M15 --> M12
  M15 --> M14
  M17 --> M11
  M17 --> M16
  M16 --> M01
  M14 --> M04
  M14 --> M10
  M09 --> M04
  M09 --> M10
  M09 --> M12
  M11 --> M01
  M11 --> M09
  M11 --> M12
  M13 --> M09
  M13 --> M12
  M12 --> M01
  M12 --> M02
  M12 --> M04
  M10 --> M01
  M10 --> M04
  M07 --> M04
  M07 --> M05
  M08 --> M04
  M08 --> M05
  M08 --> M06
  M06 --> M04
  M06 --> M05
  M05 --> M01
  M05 --> M03
  M21 --> M04
  M20 -. 只读兼容 .-> M12
```

**禁止的反向依赖**：① 任何模块 → `ui/`（现状 runtime/backup/bridge/core 均 0 条，由边界脚本守住）；② `M10` 不得 → `M09`（否则 Rust 无法单独承接命令组装）；③ `M12` 不得 → `M13`（`tools/architecture-backup-core-baseline.json` 的 17 条 backup→core 边只许减少）；④ 根包服务不得 → UI Activity（现违规 3 条：`AgentTaskNotifications.java:20`、`TerminalRuntimeService.java:19`、`UpdateDownloadService.java:15`）；⑤ `M20` 不得被普通模块调用（只许 `M12` 读旧格式）。

## 五、状态单点持有清单

| 状态 | 唯一持有者 | 现状证据 | 新架构禁令 |
|---|---|---|---|
| Web 代次/停止意图 | M09 `WebLifecycleController` + `HarnessSessionState` | `WebLifecycleController.java:24`、`WebRun.generation:38`、`HarnessSessionState.java:10` | UI/桥不得自缓存 `isStarting`；旧代次回调不得发布端口或鉴权 |
| 环境代次/runtimeId | M01 + M11 | `backup/RuntimeDescriptor.java:7`、`core/InstallationIdentity.java` | 不得由 APK 版本推导；描述符变化必须进维护页 |
| 维护屏障 | M09 `MaintenanceCoordinator` | `MaintenanceCoordinator.java:14`、`:31` `isOwner()` | 备份/安装/插件不得各建第二把锁；忙状态不得用通用任务锁冒充 |
| 运行租约 | M09 `HarnessService` + `util/SharedServiceLeases` | `HarnessService.java`、`RuntimeTasks.java:7` | 「服务还在」≠「Web 未停止」；`DeviceBridgeService` 保活独立 |
| 授权/派发栅栏 | M05 | `DeviceGrants.java:9`、`ScreenSessionGrant.java` | `policyRevision`/`authorityEpoch`/Web generation 不可合并或归零 |
| 虚拟屏 Core owner | M08 单例工厂 | `DshaApp.virtualScreenManager()`（边界脚本强制唯一 `new`） | 预览/Activity/桥不得各建 Core |
| 更新任务 | M17 `UpdateEngine` | `core/UpdateEngine.java:31` 可变静态单例 | **必须改为 Application 注入**：静态单例违反组合根唯一持有 |
| 插件 writer 锁 | M13 `PluginManagementEngine` | `plugins/PluginManagementEngine.java:24` | UI 与桥不得直接 refresh/改写 profile |
| 终端会话 | M14 `TerminalSessionOwner` | `core/TerminalSessionOwner.java:18`、`:75` `shared()` | 维护前核验实际会话退出，不能只关 UI |

## 六、边界回归

| 边界 | 现有检查点 | 防腐化手段 |
|---|---|---|
| runtime/backup → ui 零依赖 | `test-core-boundaries.py::test_runtime_and_backup_have_no_direct_ui_dependency` | 每模块一份 `module-deps.json` 白名单，只许收紧 |
| backup→core 适配边 | `test_existing_backup_host_coordination_edges_can_only_decrease` + 基线 JSON（17 条） | 端口化后逐条删基线项，基线不得扩张 |
| 组合根唯一 | `test_one_application_owns_the_runtime_ports_and_web_state`、`test_service_reference_is_application_owned…`、`test_virtual_screen_owner_has_one_lazy_factory…` | 保留为跨模块单点门禁；新增静态单例即失败 |
| 门面不执行业务 | `test_harness_facade_does_not_execute_split_concerns`（`HarnessController.java` ≤300 行） | 新壳 `HarnessFacade` 沿用行数与行为双上限 |
| 设备白名单 | `util/DeviceShellPolicyTest`、`tools/test-device-shell-policy.py` | 策略纯逻辑独立成模块，执行器内不得内嵌例外 |
| 授权与派发 | `capabilities/CapabilityBrokerTest`、`core/DeviceGrantsTest`、`ScreenSessionGrantTest` | 一次 dispatch、撤销栅栏、损坏 fail-closed 随模块走 |
| 桥鉴权 | `util/BridgeCredentialAuthTest`、`tools/test-bridge-token-transport.py` | 鉴权与路由同模块，避免跨模块传 token |
| 数据与备份 | `OneClickBackup*Test`、`BackupDownloadsPublicationTest` | 事务/范围纯逻辑不依赖 Android；UI 只提交请求 |
| 虚拟屏 | `VirtualScreen*Test`、`tools/test-adb-vscreen-bridge.py`、ADR0003 | 帧/节点/anchor 三类凭据分别测试；两屏无实现复用 |
| 全量 | 706 个 `*Test.java`、host manifest 252 项、`dsha-gradle` | 模块独立可测；跨模块只测端口契约 |

## 七、建议模块总表

| 用户模块 | 建议模块 | 变更 | 必须性 |
|---|---|---|---|
| 1 安卓权限 | M05 授权与能力派发 | 重切（收开关面+Broker） | 必须 |
| 2 网络请求 | M16 网络与传输（出站） | 拆分、收窄 | 建议 |
| 2 网络请求 | M15 桥与入站网络 | 拆分出入站 | 必须 |
| 3 更新与覆盖 | M17 应用更新与覆盖安装 | 拆分 | 必须 |
| 3 更新与覆盖 | M11 受管运行时安装 | 拆分 | 必须 |
| 4 日志与记录 | M03 诊断与可观测 | 改名 | 建议 |
| 5 生命周期与挂载 | M09 生命周期与租约 | 拆分 | 必须 |
| 5 生命周期与挂载 | M10 命令与挂载 | 拆分 | 必须 |
| 6 外壳 UI | M18 外壳 UI 与交互呈现 | 保留+吸收 i18n/主题/通知/助理 | 必须 |
| 7 终端 | M14 终端 | 保留 | 必须 |
| 8 Root/ADB | M06 设备执行通道 | 重切（去开关面） | 必须 |
| 9 插件热更新 | M13 插件生命周期与热检查 | 改名，热检查不独立 | 必须 |
| 10 识屏/无障碍 | M07 物理屏观察与动作 | 拆分 | 必须 |
| 10 识屏/无障碍 | M08 虚拟屏 | 拆分 | 必须 |

| 净新增 | 理由 | 必须性 |
|---|---|---|
| M01 配置与状态 | 唯一配置入口 + 偏好键迁移 | 建议 |
| M02 凭据与密钥 | Keystore 失败域、四态、备份排除 | 建议 |
| M04 进程身份与退出证据 | 生命周期/终端/虚拟屏/安装共用 | 必须 |
| M12 宿主数据与备份事务 | 83 类，唯一可写用户数据者 | 必须 |
| M19 网页宿主 | WebView 容器/页面脚本/上传下载/IME/PiP | 必须 |
| M20 legacy 兼容 | 防腐层，只读旧格式 | 强制隔离 |
| M21 独立应急 | 独立运行根 | 强制隔离 |

**最终 21 个模块**（比 10 多 11）：必须独立 15、建议独立 4（M01/M02/M03/M16）、强制隔离 2。不建议更少：M04/M12/M15/M19 各自被多方依赖或有独立失败域；不建议更多：事件总线、通用 HTTP 层、独立 i18n 都会制造隐式耦合。

## 八、给 Lead 的一句话

十个模块意图对，但须按「状态与派发 / 执行通道」「APK 更新 / 环境安装」「物理屏 / 虚拟屏」三处重切，并补进程身份、宿主数据、桥入站、网页宿主四个被低估模块：最终 21 个模块、15 个必须独立，用现有四件套门禁（`tools/test-core-boundaries.py`、`architecture-backup-core-baseline.json`、706 个单测、252 项 host 检查）逐模块收紧依赖。
