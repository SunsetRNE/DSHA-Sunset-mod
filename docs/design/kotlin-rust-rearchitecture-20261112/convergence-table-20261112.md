# 收敛表 v0.1：职责 / 状态 owner / 编译模块 / 测试恢复 / 覆盖更新

> 用途：按双方 2026-11-12 约定收敛成**一张表**（6 列），作为 A1 契约冻结与 A8 工程化的对照底稿。
> 分工：**我方出「状态 owner / 编译模块 / 测试恢复映射 / 覆盖更新影响」四列初稿**；「职责」与「失败域·回退点」双方共同填。
> 状态：**初稿，待评审**。数据按当前 build359 工作树实测，模块拆分后需随被测类迁移更新。
> 口径：21 状态模块 / 9 Gradle 模块（+2 二期）/ 706 个 `*Test.java`。

## 怎么读

| 列 | 含义 | 现在填到哪 |
|---|---|---|
| 职责 | 一句话边界（不写实现细节） | 我方草拟，待对方确认 |
| 状态 owner | 该模块内**唯一权威状态**的持有者；没有可写"—" | 我方出（引用评审稿 §4.6） |
| 编译模块 | Gradle 落点（A8 前是目标，不是现状） | 我方出 |
| 测试恢复映射 | 现有测试类归属（模块化后随被测类迁移） | 我方出（按当前包统计） |
| 失败域·回退点 | 失败时的行为与可回退点 | 我方草拟，待对方补 |
| 覆盖更新影响 | 改动是否进入 `launcherInputs` → 是否触发维护页 | 我方出（按 481 项清单匹配） |

**覆盖更新影响的读法**：
- **在清单**：改动该模块的主文件会改变受管运行时摘要 → `runtimeId` 变 → 覆盖安装进一次维护页；
- **部分在**：只有部分文件在清单里，需按实际改动判断；
- **不在清单**：只改这些文件时不改变 `runtimeId` → 可能零维护（仍以 `RuntimeDescriptor.latest` 为准）。

---

## 一、21 个状态模块

| 模块 | 职责（草拟） | 状态 owner | 编译模块 | 测试恢复映射（现有） | 失败域·回退点（草拟） | 覆盖更新影响 |
|---|---|---|---|---|---|---|
| M01 配置与状态 | 唯一配置读写、偏好键迁移、便携设置 | `ConfigStore`（唯一入口） | `:data` + `:platform:android` | core 50 中的 Config 类（如 `ConfigStoreHideFromRecentsTest`）+ `PortableSettingsOwnerTest` | 写失败保留旧值并告警；损坏 fail-closed 回默认；回退=读旧键 | **在清单**（`ConfigStore.java`、`PortableSettings.java`） |
| M02 凭据与密钥 | Keystore 别名与密文、凭据路径清单 | `KeyVault`（别名不可改） | `:data` | `data/` 2 类中的 KeyVault 相关 + `CredentialPathsTest`、`CredentialReadTest` | 解不开→`NEEDS_ATTENTION`，不新建替代；回退=保留旧密文 | **在清单**（`KeyVault.java`） |
| M03 诊断与可观测 | 有界事件、唯一脱敏出口、导出、启动诊断 | `DiagnosticRepository` / `SensitiveData`（唯一脱敏） | `:data`（桥呈现归 `:bridge`） | `diagnostics/` 3 + core 诊断类 + `SensitiveDataTest`、`DiagnosticHistoryTest` | 写失败降级内存环形并告警一次；脱敏失败拒绝导出 | **大部分在**（diagnostics 3/3、`SensitiveData`；部分 core 诊断类不在） |
| M04 进程身份与退出证据 | 出生身份、双次核验、停止证明 | 进程身份登记（`WebPidIdentity` / `ProcessBirthHandshake`） | `:runtime` + `:core:policy` 判据 | util 中停止相关 11 类（`WebProcSelTest`、`WebProofStopTest`、`WebSessionProofTest`、`WebForceStopTest`…） | 任一核验不符→`UNKNOWN` 不发信号；回退=保留原环境 | **部分在**（`WebProcSel`/`ProcessBirthHandshake`/`WebPidIdentity` 在；`WebProofStop`、`ProcessIdentity`、`EnvironmentIdentity` 不在） |
| M05 授权与能力派发 | 授权状态机、一次性派发、撤销栅栏 | `DeviceGrants` + `CapabilityBroker` | `:core:grants` | `CapabilityBrokerTest` + core 授权类 + `ScreenSessionGrantTest` | 损坏 fail-closed 回 OFF；迟到 allow 不重授权；回退=关闭能力 | **在清单**（capabilities 6/6、`DeviceGrants`、`ScreenSessionGrant`） |
| M06 设备执行通道 | 三通道选择、白名单、实际身份 | `DeviceBridgeService`（通道选择） | `:device` | util 策略类（`DeviceShellPolicyTest`）+ root 设备类 + `device/` 相关 | 默认拒绝/126；UNKNOWN 不换通道；回退=关闭通道 | **大部分在**（root 14/36、`device` 6/6、`bridge` 4/12、`DeviceShellPolicy`） |
| M07 物理屏 | 观察/截图/语义与坐标动作/敏感目标 | `DshaAccessibilityService` | `:device` | `screen/` 8 + `MainScreenObservationTest` + vscreen 共享语义用例 | 坐标凭据 30s/一次；失败不换屏；UNKNOWN 不重放 | **在清单**（`screen` 19/19 + 无障碍服务） |
| M08 虚拟屏 | Core 生命周期、帧/节点/anchor 凭据 | `VirtualScreenManager`（单例工厂） | `:device` | vscreen 32 | Core 退出证据；UNKNOWN 不重建；回退=关闭 Core | **在清单**（vscreen 36/36） |
| M09 生命周期与租约 | 代次、停止意图、维护屏障、运行租约 | `WebLifecycleController` + `MaintenanceCoordinator` | `:runtime` | `runtime/` 44 部分 + core 生命周期类 | 证据不足保留屏障；回退=停旧不启新 | **大部分在**（core 34/60 + runtime 63/63） |
| M10 命令与挂载 | argv/env/BINDS 纯组装，无状态 | `RuntimeLauncher`（无状态） | `:runtime` | `runtime/` 44 部分（如 `ContainerRuntimeEnvironmentTest`） | 组装失败 fail-closed；回退=上次 `LaunchSpec` | **在清单**（runtime 63/63） |
| M11 受管安装 | 描述符、候选、试运行、事务提交 | `InstallPipeline` + `RuntimeDescriptor` | `:runtime` | runtime 44 + backup 71 部分（`RuntimeTrial*`、`ManagedRuntimeTransaction*`） | 试运行失败回切；未关闭现场不提交 | **在清单**（runtime + `RuntimeDescriptor`/`ManagedRuntimeTransaction`） |
| M12 宿主数据与备份 | 唯一可删用户数据者；v5/槽位/NOFOLLOW | `NativeBackupJobs` + `HostDataTransaction` | `:data` | `backup/` 71 | 先核验再写；未知数据保留；回退=保留原件 | **在清单**（backup 83/83） |
| M13 插件生命周期与热检查 | 启停意图、候选事务、热检查 | `PluginManagementEngine`（writer 锁） | `:data` + `:runtime` 契约 | `plugins/` 8 | pending 拦启动不重放；回退=隔离候选 | **在清单**（plugins 9/9 + `PluginRepository`） |
| M14 终端 | PTY/简终端、会话身份、退出回收 | `TerminalSessionOwner` | `:runtime` | `runtime/` 44 部分 + 终端相关用例 | 未核验退出不移除标签；回退=结束会话 | **在清单**（`PtySession`、`TerminalRuntimeService`、`TerminalSessionOwner`） |
| M15 桥与入站网络 | 鉴权、端点、能力派发、SAF 授权簿 | `HttpShellService` + `BridgeRoutes` | `:bridge` | util 桥类 + `saf/` 6 + `bridge/` 3 | token fail-closed；凭据区拒绝；回退=关闭 LAN | **在清单**（`HttpShellService`、`BridgeRoutes`、saf 4/4、LAN） |
| M16 网络与传输 | 出站下载、续传、网络代次、健康探测 | `DownloadEngine` | `:bridge` | `transfer/` 3 | 切网弃旧连接不重放；续传只信已核验偏移 | **不在清单**（transfer/nethealth/`ResumableDownload` 均不在） |
| M17 应用更新与覆盖安装 | APK 检查/下载/签名/覆盖 + 维护门禁 | `UpdateEngine`（须去静态单例） | `:app` + `:runtime`（维护） | core 50 中 Update 相关 + `UpdatePolicy` 用例 | 下载可续传、安装不可重放；回退=保留旧 APK | **不在清单**（`UpdateEngine`、`UpdateDownloadService` 不在；维护门禁本体属 M09） |
| M18 外壳 UI 与交互呈现 | 页面/主题/i18n/通知/助理 | 组合根 + UI 快照 | `:ui` | `ui/` 78 + assistant 5 + control 3 + skills 3 | 切语言只重建界面；快照过期不推断已退出 | **不在清单**（ui/assistant/control/skills 全 0） |
| M19 网页宿主 | WebView 生命周期、页面代次、IME/PiP/麦克风 | `WebPreviewActivity` + 页面代次 | `:ui` | `ui/` 78 部分 | 页面代次不符拒绝回调；回退=原生恢复页 | **不在清单**（ui 0/169） |
| M20 legacy 兼容 | 只读旧格式（v1–v4、旧键、旧 schema） | `LegacyBackupImporter`（只读） | `:data` | `backup/` 71 中 Legacy 相关 | 未知格式拒绝而非猜；原件保留 | **大部分在**（backup Legacy* 在；util Legacy* 不在） |
| M21 独立应急 | 独立根/HOME/五项工具 | `RecoveryController` | 独立（强制隔离） | `recovery/` 2 | 不能确认写入；broker 不能自批 | **部分在**（仅 `RecoveryStoragePlan` 在清单） |

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

## 三、方法（可复核）

- **`launcherInputs` 匹配**：读取 `app/src/main/assets/runtime-descriptor.json` 的 `launcherInputs`（481 项，其中 423 个 `.java`、14 个 `.so`、44 个生成输入），按模块所属包/文件匹配；`ui/` 为 0。
- **测试统计**：按 `app/src/test/java/com/deepseekharness/app/<pkg>` 现有目录归属；当前 716 个测试源文件、706 个 `*Test.java`。模块化后随被测类迁移，届时以迁移结果为准。
- **状态 owner**：取自评审稿 §4.6 的 9 条单点清单；未覆盖的模块写"本模块"。
- **未统计**：真机用例、`LayoutAuditInstrumentation`（在 `app/src/debug`，不计入 `*Test.java`）、host 侧 252 项检查——这些需要 A8 时按模块重新挂。

---

## 四、待对方补的两列

- **职责**：请按你们 C01/C02 的定位，确认 M01–M21 的一句话边界（尤其 M04/M10/M11 的拆分是否接受）。
- **失败域·回退点**：我方已给草稿；请补你们最在意的三条（建议：M09 维护屏障、M12 数据事务、M15 入站鉴权）。

*本表是收敛底稿，不是验收报告；每格结论以该模块的测试回执与真机记录为准。*
