# 收敛表 v0.2：职责 / 状态 owner / 编译模块 / 测试恢复 / 覆盖更新

> 用途：按双方 2026-11-12 约定收敛成**一张表**（6 列），作为 A1 契约冻结与 A8 工程化的对照底稿。
> 版本：v0.2（2026-11-12）；上一版 v0.1 为同日初稿。
> 分工：**我方出「状态 owner / 编译模块 / 测试恢复映射 / 覆盖更新影响」四列**；「职责」与「失败域·回退点」两列原约定双方共同填，v0.2 起由我方先自拟。
> 状态：**v0.2 自拟稿，待对方逐项确认**。**对方未交草稿，我方自拟，待逐项确认**：v0.1 只写了「待对方补」，v0.2 由我方按评审主稿、ADR-0004 与当前代码/测试类自拟补全两列；其余四列沿用 v0.1（数据按当前 build359 工作树实测，模块拆分后需随被测类迁移更新）。
> 口径：21 状态模块 / 9 Gradle 模块（+2 二期）/ 1 Rust crate（首期旁路）/ 706 个 `*Test.java`。

## 怎么读

| 列 | 含义 | 现在填到哪 |
|---|---|---|
| 职责 | 一句话边界（不写实现细节） | 我方自拟（v0.2），待对方逐项确认 |
| 状态 owner | 该模块内**唯一权威状态**的持有者；没有可写"—" | 我方出（引用评审稿 §4.6） |
| 编译模块 | Gradle 落点（A8 前是目标，不是现状） | 我方出 |
| 测试恢复映射 | 现有测试类归属（模块化后随被测类迁移） | 我方出（按当前包统计） |
| 失败域·回退点 | 失败时的行为与可回退点 | 我方自拟（v0.2），待对方逐项确认 |
| 覆盖更新影响 | 改动是否进入 `launcherInputs` → 是否触发维护页 | 我方出（按 481 项清单匹配） |

**覆盖更新影响的读法**：
- **在清单**：改动该模块的主文件会改变受管运行时摘要 → `runtimeId` 变 → 覆盖安装进一次维护页；
- **部分在**：只有部分文件在清单里，需按实际改动判断；
- **不在清单**：只改这些文件时不改变 `runtimeId` → 可能零维护（仍以 `RuntimeDescriptor.latest` 为准）。

---

## 一、21 个状态模块

| 模块 | 职责（v0.2 自拟） | 状态 owner | 编译模块 | 测试恢复映射（现有） | 失败域·回退点（v0.2 自拟） | 覆盖更新影响 |
|---|---|---|---|---|---|---|
| M01 配置与状态 | 唯一配置读写入口：历史 SharedPreferences 键兼容 + 便携偏好投影；只负责值存取，不解释业务语义 | `ConfigStore`（唯一入口） | `:data` + `:platform:android` | core 50 中的 Config 类（如 `ConfigStoreHideFromRecentsTest`）+ `PortableSettingsOwnerTest` | API Key 加密或落盘失败时 `saveApiKey()` 返回 false 并保留旧密文，绝不明文落盘；便携设置交换文件 `commit()` 失败记 `SETTINGS_PROJECTION_FAILED` 并保留原投影；读侧坏值回落既有默认键。回退点=旧键+旧值；证据=`ConfigStoreRuntimeSettingsTest`、`ConfigStoreHideFromRecentsTest`、`PortableSettingsOwnerTest`、`PortablePreferenceProjectionTest` | **在清单**（`ConfigStore.java`、`PortableSettings.java`） |
| M02 凭据与密钥 | Keystore 凭据边界：`KeyVault` 的 AES/GCM 加解密（旧 API Key 别名 `dsh_api_key`，本机能力用独立别名）+ `CredentialPaths` 的凭据区清单 | `KeyVault`（别名不可改） | `:data` | `data/` 2 类中的 KeyVault 相关 + `CredentialPathsTest`、`CredentialReadTest` | 解密失败按 `CredentialRead` 分类：`DEVICE_LOCKED`/`AUTHENTICATION_REQUIRED`/`TRANSIENT_STORE` → `TEMPORARILY_UNAVAILABLE`，`KEY_MISSING`/`KEY_INVALIDATED`/`UNREADABLE` → `NEEDS_ATTENTION`；不新建替代密钥、不把失败伪装成未配置。回退点=保留旧密文并要求重新输入；证据=`CredentialReadTest`、`CredentialPathsTest`、`CredentialWriteTest` | **在清单**（`KeyVault.java`） |
| M03 诊断与可观测 | 有界脱敏诊断域：环形事件 + 环境探针状态 + 唯一脱敏出口 + 有上限导出；不是通用 logger | `DiagnosticRepository` / `SensitiveData`（唯一脱敏） | `:data`（桥呈现归 `:bridge`） | `diagnostics/` 3 + core 诊断类 + `SensitiveDataTest`、`DiagnosticHistoryTest` | 记录失败静默丢弃本次事件（`DiagnosticLog` 捕获后不阻塞业务），上一份完整文件是回退点；历史超 32 KiB 从最旧整行截断（`DiagnosticHistory.append`，保留文件 `.previous`）；导出超 `Limits.PRODUCTION`（1 MB）直接拒绝，`DiagnosticPrivacy` 再做私有路径替换。证据=`SensitiveDataTest`、`DiagnosticHistoryTest`、`DiagnosticExportArchiveLimitsTest` | **大部分在**（diagnostics 3/3、`SensitiveData`；部分 core 诊断类不在） |
| M04 进程身份与退出证据 | 进程身份与退出证据的唯一判据：出生时刻/命令行/UID 解析、双次核验、可发信号候选筛选；不持有 Web 生命周期 | 进程身份登记（`WebPidIdentity` / `ProcessBirthHandshake`） | `:runtime` + `:core:policy` 判据 | util 中停止相关 11 类（`WebProcSelTest`、`WebProofStopTest`、`WebSessionProofTest`、`WebForceStopTest`…） | 任一字段读不到或两次观察不一致 → `UNKNOWN`，不发信号、不换通道；`WebProofStop` 只认 `SENT`/`GONE`/`FOREIGN_UID`/`DENIED` 四态，`DENIED` 记 `SIGNAL_DENIED`；人工强制也必须先有身份核验。回退点=保留原环境与 `StoredWebSession.Record.proof`；证据=`WebProcSelTest`、`WebProofStopTest`、`WebSessionProofTest`、`WebOverwriteRetireTest` | **部分在**（`WebProcSel`/`ProcessBirthHandshake`/`WebPidIdentity` 在；`WebProofStop`、`ProcessIdentity`、`EnvironmentIdentity` 不在） |
| M05 授权与能力派发 | 授权与能力派发：状态机 + 一次性许可 + 屏幕授权栅栏；只判「能不能」，不执行 | `DeviceGrants` + `CapabilityBroker` | `:core:grants` | `CapabilityBrokerTest` + core 授权类 + `ScreenSessionGrantTest` | 授权存储损坏/不可读 → fail-closed 关闭能力（`ScreenModePolicy` 未知值=`OFF`）；撤销后迟到 allow 不重新授权，`policyRevision`/`authorityEpoch` 不合并、不归零；屏幕模式不匹配直接拒绝且不回退另一块屏。回退点=关闭对应能力（短信/屏幕/剪贴板/LAN 各自独立）；证据=`DeviceGrantsTest`、`CapabilityBrokerTest`、`ScreenSessionGrantTest`、`OneShotLaunchAuthorityTest` | **在清单**（capabilities 6/6、`DeviceGrants`、`ScreenSessionGrant`） |
| M06 设备执行通道 | 设备执行通道：ADB/Shizuku/Root 选择与保活 + 白名单 + 实际执行身份；不含授权判定 | `DeviceBridgeService`（通道选择） | `:device` | util 策略类（`DeviceShellPolicyTest`）+ root 设备类 + `device/` 相关 | 未完整识别的 argv 默认拒绝（`[POLICY_BLOCKED]`+126，无「仍然允许」）；`[EXECUTION_UNKNOWN]`/缺失结束标记 → `UNKNOWN`，不换通道重试，`DeviceTransportResult` 拒绝「UNKNOWN 却声称远端已退出」；通道掉线只影响本次执行通道选择，不把 `UNKNOWN` 当作可重放失败。回退点=关闭通道；证据=`DeviceShellPolicyTest`、`DeviceChannelOrderTest`、`DeviceChannelSnapshotTest`、`tools/test-device-shell-policy.py` | **大部分在**（root 14/36、`device` 6/6、`bridge` 4/12、`DeviceShellPolicy`） |
| M07 物理屏 | 物理屏能力：观察/截图、语义与坐标动作、敏感目标判定、授权模式；只出能力与凭据，不实现虚拟屏 | `DshaAccessibilityService` | `:device` | `screen/` 8 + `MainScreenObservationTest` + vscreen 共享语义用例 | 坐标凭据 30 秒/单次有效，过期或被新观察替换即拒绝且不换虚拟屏兜底；截图/读屏失败不推断界面已变；敏感界面按 `SensitiveScreenPolicy` 分档，全局 `ALLOW_ALL` 不代答敏感类别；`UNKNOWN` 不重放。回退点=关闭物理屏模式（`SCREEN_MODE_PHYSICAL_OFF`）；证据=`MainScreenObservationTest`、`ScreenCapabilityGateTest`、`ScreenConfirmationPolicyTest`、`SensitiveScreenPolicyTest` | **在清单**（`screen` 19/19 + 无障碍服务） |
| M08 虚拟屏 | 虚拟屏：单例 Core 生命周期、帧/节点/anchor 三类凭据、独立 `app_process`；与物理屏只共享能力枚举 | `VirtualScreenManager`（单例工厂） | `:device` | vscreen 32 | Core 启动/退出证据不足 → `START_RESULT_UNKNOWN`/`CLOSE_UNKNOWN`，不自动重建、不自动 close、不重放；`cleanupPending` 先读 status 再决定；凭据必须来自同一次观察，不静默换成 tap。回退点=用户同意后关闭该 Core；证据=`VirtualScreenManagerOwnershipTest`、`VirtualScreenLifecycleTest`、`VirtualScreenStartOutcomeTest`、`VirtualScreenAnchoredChainTest` | **在清单**（vscreen 36/36） |
| M09 生命周期与租约 | 生命周期与租约：Web 代次/停止意图、维护屏障、运行租约；只发状态与顺序，不组装命令 | `WebLifecycleController` + `MaintenanceCoordinator` | `:runtime` | `runtime/` 44 部分 + core 生命周期类 | 停止证据不足/未确认退出 → 保留维护屏障，不进入成功态；「服务还在」≠「Web 未停止」；旧代次回调不得发布端口或鉴权；未关闭 Web 只拦自动开始、不拦维护入口。回退点=停旧不启新（`WebStopCoordinator`/`WebSessionRecheck`）；证据=`MaintenancePreStopTest`、`WebStopCoordinatorTest`、`WebForceStopGateTest`、`WebLifecycleStartProtectionTest` | **大部分在**（core 34/60 + runtime 63/63） |
| M10 命令与挂载 | 命令与挂载的纯组装：argv/env/BINDS 生成，无状态；不得反向依赖 M09 | `RuntimeLauncher`（无状态） | `:runtime` | `runtime/` 44 部分（如 `ContainerRuntimeEnvironmentTest`） | 路径/输入越界 → fail-closed 拒绝组装；端口只由 `WebPortPolicy` 选择，不按端口猜 PID；组装失败保留上一次已验证 `LaunchSpec` 供对照，不重放已开始的启动。回退点=上一份 `LaunchSpec` 与运行模式；证据=`RuntimeLauncherTest`、`ContainerRuntimeEnvironmentTest`、`RuntimeHostPortsTest`、`WebPortPolicyTest` | **在清单**（runtime 63/63） |
| M11 受管安装 | 受管运行时安装：描述符、六步安装、隔离试运行、事务提交；`runtimeId` 不从 APK 版本推导 | `InstallPipeline` + `RuntimeDescriptor` | `:runtime` | runtime 44 + backup 71 部分（`RuntimeTrial*`、`ManagedRuntimeTransaction*`） | 试运行失败回切旧候选，未关闭现场不提交；`RuntimeTrialRecords` 只轮换已确认关闭现场（保留最近 3 成功/5 失败）；proroot 鉴权前明确退出且无插件故障时才按 `WebRuntimeFallback` 重试一次 proot，且不得报告成 proroot 原生成功。回退点=上一健康前代；证据=`InstallPipelineTest`、`RuntimeDescriptorTest`、`ManagedRuntimeTransactionTest`、`RuntimeTrialRecordsTest` | **在清单**（runtime + `RuntimeDescriptor`/`ManagedRuntimeTransaction`） |
| M12 宿主数据与备份 | 宿主数据与备份事务：全仓唯一允许删除/覆盖用户数据的模块——v5 备份、数据事务、自动计划、范围与上限 | `NativeBackupJobs` + `HostDataTransaction` | `:data` | `backup/` 71 | 先核验再写：`DataFormatEvidence` 对未经完整检查的数据保留 `unknown`，不据发件人声明删原件；错误用 `BackupErrorCode` 稳定码并保留原件；未知 scope 拒绝而非猜全量；部分备份绝不叫 `DSHA-backup-*`。回退点=原件+私有编号槽位；证据=`OneClickBackupTest`、`HostDataTransactionTest`、`PortableBackupEnvelopeTest`、`DataFormatEvidenceTest` | **在清单**（backup 83/83） |
| M13 插件生命周期与热检查 | 插件生命周期与热检查：启停意图、候选事务、热检查与 writer 锁同所有者 | `PluginManagementEngine`（writer 锁） | `:data` + `:runtime` 契约 | `plugins/` 8 | pending/未提交 journal 进入维护门禁、不自动重放（`PluginInstallJournals`、`PluginJournalQuarantine`）；`.disabled` 意图优先于旧实体；候选校验失败隔离而不覆盖原件；UI/桥不得直接 refresh/改写 profile。回退点=隔离候选并保留旧 profile；证据=`PluginManagementEngineTest`、`PluginOperationQueueTest`、`PluginInstallJournalsTest` | **在清单**（plugins 9/9 + `PluginRepository`） |
| M14 终端 | 终端：PTY/简终端两套标签、会话身份、关闭与退出回收；流式渲染不属本模块 | `TerminalSessionOwner` | `:runtime` | `runtime/` 44 部分 + 终端相关用例 | 未核验实际会话退出前不移除标签、不释放维护前置条件；孤儿 guest/launcher 按本次独立会话回收，不能只关 UI 就释放工作锁；关闭核验失败保留标签并报错。回退点=结束该会话并保留其余标签；证据=`TerminalSessionOwnerTest`、`TerminalRuntimeProtectionTest`、`TerminalPtyThreadContractTest`、`PtySessionLockContractTest` | **在清单**（`PtySession`、`TerminalRuntimeService`、`TerminalSessionOwner`） |
| M15 桥与入站网络 | 桥与入站网络：token 门控、端点表、能力派发、凭据区拒绝、SAF 文件面；只做入站，不含出站下载 | `HttpShellService` + `BridgeRoutes` | `:bridge` | util 桥类 + `saf/` 6 + `bridge/` 3 | token 缺失/不匹配 → fail-closed 拒绝，不因「内部调用」放宽；SAF 未授权或非文件树 → 拒绝；凭据区字符串 + `getCanonicalPath()` 双重拒绝；未知路由固定错误码，不落到下一个处理器。回退点=关闭 LAN/桥（无监听即无入站面）；证据=`BridgeCredentialAuthTest`、`BridgePathPolicyTest`、`BridgeRoutesTest`、`SafGrantsTest` | **在清单**（`HttpShellService`、`BridgeRoutes`、saf 4/4、LAN） |
| M16 网络与传输 | 出站网络与传输：下载/续传/摘要核验、切网与健康探测；不含入站桥 | `DownloadEngine` | `:bridge` | `transfer/` 3 | 切网弃旧连接、不在新网络重放已提交请求；续传只信已核验偏移，`FILE_PARTIAL_INVALID`/`FILE_LENGTH_INVALID` 直接拒绝，Range/If-Range 以已核验 `sha256` 为锚；探测失败只记 `Report`，不阻塞主流程。回退点=删除 `.part` 重下并保留已完成的旧件；证据=`ResumableDownloadTest`、`TransferProtocolTest`、`NetworkHealthPolicyTest`、`NetworkEpochPolicyTest` | **不在清单**（transfer/nethealth/`ResumableDownload` 均不在） |
| M17 应用更新与覆盖安装 | 应用更新与覆盖安装：版本/通道/变体选择、APK 下载与签名核验、维护门禁入口；不自建安装器 | `UpdateEngine`（须去静态单例） | `:app` + `:runtime`（维护） | core 50 中 Update 相关 + `UpdatePolicy` 用例 | 清单/摘要不符 → 候选置空不安装；`UpdateEngine` 比对已装签名，不一致即拒绝；下载可续传，安装不可重放，进程被杀后重查 `installableApk()`；同一候选只自动尝试一次。回退点=保留旧 APK 与旧版本（只能发更高 `versionCode` 回滚）；证据=`UpdateEngineCheckTest`、`UpdatePolicyTest`、`UpdateRedirectsTest`、`UpdateFailuresTest` | **不在清单**（`UpdateEngine`、`UpdateDownloadService` 不在；维护门禁本体属 M09） |
| M18 外壳 UI 与交互呈现 | 外壳 UI 与交互呈现：页面/主题/i18n/通知/助理/技能入口；只渲染快照，不持有生命周期或授权状态 | 组合根 + UI 快照 | `:ui` | `ui/` 78 + assistant 5 + control 3 + skills 3 | 切语言只重建界面，不停终端或 Web；`UiStateText` 在显示边界重译、不缓存语言；快照过期不推断「已退出」；视觉门禁失败按 `LayoutAuditInstrumentation` 定位，不靠像素猜测放行。回退点=上一份 UI 快照/上一语言；证据=`UiStateTextTest`、`LanguageControllerTest`、`CoreUiStateContractTest`、`LayoutAuditInstrumentation`（`app/src/debug`） | **不在清单**（ui/assistant/control/skills 全 0） |
| M19 网页宿主 | 网页宿主：WebView 生命周期、页面代次、脚本注入、IME/PiP/麦克风；不含原生 UI 主题 | `WebPreviewActivity` + 页面代次 | `:ui` | `ui/` 78 部分 | 页面代次/实例不符 → 拒绝旧回调（`STATE_PAGE_GENERATION`/`STATE_PAGE_INSTANCE`）；麦克风只放行当前本机页面的纯音频请求，旧回调不能批准新页；加载失败显示可恢复错误页，不自动重放鉴权。回退点=原生错误页/返回上一页；证据=`WebPageScriptsTest`、`WebBackgroundPageLifecycleTest`、`WebUploadChooserTest`、`MicrophonePolicyTest` | **不在清单**（ui 0/169） |
| M20 legacy 兼容 | legacy 只读兼容：读 v1–v4 归档、旧配置检查点与旧 schema；只读旧、只写新格式 | `LegacyBackupImporter`（只读） | `:data` | `backup/` 71 中 Legacy 相关 | 未知格式/超限/损坏 → 拒绝导入而非猜全量，旧原件保留；旧偏好键与旧 Web 会话只做迁移输入，不就地改写；`dataWrite` 首版不提升（否则旧版拒读整棵树）。回退点=保留原归档 + 私有槽位；证据=`LegacyBackupImporterTest`、`LegacyTarReaderTest`、`LegacyNativeCredentialFallbackTest`、`ConfigurationSnapshotsTest` | **大部分在**（backup Legacy* 在；util Legacy* 不在） |
| M21 独立应急 | 独立应急 DSH：独立运行根/HOME、五项受控修复工具、只读/提案 broker；不经正式环境迁移与全局锁 | `RecoveryController` | 独立（强制隔离） | `recovery/` 2 | broker 网络端永远不能确认写入，确认只由本机原生页面绑定候选/源摘要/数据代次；`RecoveryStoragePlan` 只读占用，不删会话或未知原件；恢复失败保留正式环境与应急根各自完整。回退点=关闭应急服务并回正式启动；证据=`RecoveryBrokerProtocolTest`、`RecoveryStoragePlanTest`、`RecoveryPublicationTest`、`RecoveryRepairPlanTest` | **部分在**（仅 `RecoveryStoragePlan` 在清单） |

---

## 二、汇总（初稿）

| 覆盖更新影响 | 模块数 | 模块 |
|---|---|---|
| **在清单**（改则必进维护页） | 11 | M01 M02 M05 M07 M08 M10 M11 M12 M13 M14 M15 |
| **大部分在** | 4 | M03 M06 M09 M20 |
| **部分在** | 2 | M04 M21 |
| **不在清单**（可能零维护） | 4 | M16 M17 M18 M19 |

**由这张表能直接读出的三条结论**：

1. **11 个模块的任何改动都会触发覆盖维护页**——这不是设计缺陷，是 `launcherInputs` 把宿主源码纳入了受管运行时身份。想减少维护页，只有两条路：缩小清单范围，或接受它。
2. **只有 M16/M17/M18/M19 可以做到"零维护"发布**——这正好是 UI、网络、应用更新这三类"不碰容器"的改动，与 build326 的 I6 结论一致。
3. **测试覆盖与模块权重不匹配**：M16 只有 3 个测试类、M17/M21 各 2 个，而 M18 有 89 个；A6/A8 收口时要先补 M16/M17 的失败路径用例，否则"模块化"只是把薄的地方摊得更薄。

---

## 三、方法与证据来源（可复核）

### 3.1 `launcherInputs` 匹配（v0.1，未重测）

- 读取 `app/src/main/assets/runtime-descriptor.json` 的 `launcherInputs`（481 项，其中 423 个 `.java`、14 个 `.so`、44 个生成输入），按模块所属包/文件匹配；`ui/` 为 0。

### 3.2 测试统计（v0.1，未重测）

- 按 `app/src/test/java/com/deepseekharness/app/<pkg>` 现有目录归属；当前 716 个测试源文件、706 个 `*Test.java`。模块化后随被测类迁移，届时以迁移结果为准。

### 3.3 状态 owner（v0.1 来源）

- 取自[评审主稿](README.md) §4.6 的 9 条单点清单；未覆盖的模块写"本模块"。

### 3.4 v0.2 两列的补写方法（本次新增）

- **依据**：[评审主稿](README.md) §4.3（必须补充的模块）、§4.5（依赖 DAG）、§4.6（状态单点）、§7.2（I1–I10 不变式）与 [ADR-0004](../../adr/0004-kotlin-rust-direction.md) 决定四（正交五字段、`UNKNOWN` 不重放、投影表随契约冻结）、决定五（覆盖更新兼容）。「职责」只写边界与所有权，「失败域·回退点」写"失败时行为 + 可回退点/证据"，不写实现步骤。
- **类名与文件核对**：2026-11-12 用 `glob`/`grep` 对 `app/src/main/java/com/deepseekharness/app/`、`app/src/test/java/com/deepseekharness/app/` 与 `app/src/debug/` 逐项核对（`LayoutAuditInstrumentation` 在 `app/src/debug`）；本表只写核实存在的类，测试类名同样核对。模块化后类会迁移，届时须按被测类重挂，不能直接沿用本表的包路径。
- **失败域判据来源**：ADR-0004 决定四的五字段（`dispatched`/`executed`/`partial`/`effect`/`retry`）与 `UNKNOWN` 不重放纪律；[覆盖安装与维护契约](../../overwrite-upgrade-and-maintenance.md) 的 I1–I10；评审主稿 §8.1 的 S1–S10。回退点优先写现有类/文件，其次写现有测试类作为证据位置。
- **本轮未做新实测**：未跑 Gradle、未构建、未安装、未跑单测、未重新统计 `launcherInputs`。两列是"文档 + 现有类名/测试名"的推断，不冒充新证据；「覆盖更新影响」列沿用 v0.1 的 481 项匹配结果，数值未改。

### 3.5 未统计

- 真机用例、`LayoutAuditInstrumentation`（在 `app/src/debug`，不计入 `*Test.java`）、host 侧 252 项检查——这些需要 A8 时按模块重新挂。

---

## 四、我方自拟后的待确认项

v0.2 的两列由我方自拟，最需要对方逐项确认的 5 条（按对 A1 契约的影响排序）：

1. **M04/M09 的边界**：本表按"判据在 M04（`WebProofStop`/`ProcessIdentity`/`EnvironmentIdentity`），顺序与屏障在 M09（`WebLifecycleController`/`MaintenanceCoordinator`）"写。请确认 `WebProofStop`/`EnvironmentIdentity` 是否应整体归 M09，还是维持跨模块调用。
2. **M12 的"唯一可删用户数据者"边界**：本表只把 `NativeBackupJobs`/`HostDataTransaction`/`BackupArchive`/`BackupLimits` 写为 owner，未把 `ConfigurationSnapshots` 与 `PostUpgradeCleanupService` 的清理/快照删除路径算入。请确认这两处是否也要在 M12 内声明回退点。
3. **M07/M08 的凭据不可互换落点**：本表按 ADR0003 写死三套观察门与"不得回退另一块屏"。请确认 A1 wire 是否直接冻结 `ScreenModePolicy` 三态与三套凭据类型参数位，而不是只冻结能力枚举。
4. **M06 的文本契约**：本表保留 `[POLICY_BLOCKED]`+126 与 `[EXECUTION_UNKNOWN]` 作为 Kotlin 层设备执行的稳定出口（ADR-0004 只禁把历史文本协议复制进 FFI）。请确认设备执行层是否继续以此为准，还是 A1 起改为结构化错误码。
5. **M17 的"零维护"失败域**：`UpdateEngine`/`UpdateDownloadService` 不在 `launcherInputs` 时，本表把回退点只写成"保留旧 APK + 更高 `versionCode` 回滚"，维护门禁本体仍归 M09。请确认 M17 是否需要单独再声明"候选曾进入维护页"的持久证据。

*本表是收敛底稿，不是验收报告；每格结论以该模块的测试回执与真机记录为准。v0.2 新增两列为文档推断，待对方确认后才冻结为 A1 对照底稿。*
