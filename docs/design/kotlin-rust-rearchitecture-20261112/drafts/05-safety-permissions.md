# 评审 E：安全边界、权限通道与识屏统一化风险

对象：`build359` / `0.2.1-rc.5` 的 Java 单 `:app` → Kotlin 模块化外壳 + Rust 处理核心。本文只写**重写不得破坏什么**和破坏后怎么被抓住。已知弱点照抄，不修文。

## 结论摘要

1. **可下沉 Rust 的只有「判定之后的执行」。** 现行三道守卫（`DeviceShellPolicy` → `DeviceShellExecutor` 在执行身份下复核 → 设备侧 `device-shell-policy.py`）成立的前提是拒绝发生在派发之前、且带稳定失败码。Rust 若接管执行却保留任何 `exec(String)` 形状的入口，等于把策略降级为可选插件——这是本次最大的单点风险。
2. **识屏统一化只能统一能力枚举、凭据类型与失败码，不能统一凭据。** 坐标、语义节点、锚定是三套身份（ADR0003 决定二），共用参数位就是把旧坐标作用于新屏的口子。
3. **「同 UID 非内核沙箱」与三条已知弱点必须原样进入新仓库。** 新架构多出 `.so`、更多 fd 与信号持有者；表述不设防就会被读成「Rust 带来内核隔离」。

## 安全不变式表

| # | 不变式 | 现状证据 | 破坏后果 | 新架构归属 | 回归检查点 |
|---|---|---|---|---|---|
| S1 | 白名单默认拒绝，只接受可完整识别的单条 argv；拒绝返回 `[POLICY_BLOCKED]`+126，无「仍然允许」 | `util/DeviceShellPolicy.java:149-157`、`:163`、`:34-40`、`:104-106`；`DeviceShellExecutor.java:26` | 未知命令/脚本/管道被放行，插件获得等价 root 执行面 | `:core:policy`（Kotlin）唯一判定，`dsh-policy` 只做第二道 | `DeviceShellPolicyTest`、`DeviceShellPolicyDeleteTest`、`tools/test-native-device-policy.py` |
| S2 | 凭据区双重核验：字符串 + `getCanonicalPath()`；相对路径与 `\` 一律拒绝 | `util/BridgePathPolicy.java:86-98`、`:100-105`、`:115-128`、`:136-146`、`:229-257` | 一条 `/app/export?path=/root/.dsh/.bridge_token` 即把 token 落到公共目录，任意 App 接管 3090 桥（`:9-27` 是真机复现记录） | `:core:policy` + 桥模块；Rust 只收已解析路径与已核验 fd | `BridgePathPolicyTest`、`CredentialPathsTest`、`BridgeCredentialFilesTest` |
| S3 | 敏感能力默认关闭、可撤销、不从备份/环境/插件恢复 | `core/DeviceGrants.java:79-81`、`:89-96`、`:415-420`；`res/xml/backup_rules.xml` 只 include portable 设置 | 一次覆盖安装或一次恢复即静默开启短信/屏幕/剪贴板 | `:core:grants`（Kotlin）唯一写入点；Rust 不读 SharedPreferences | `DeviceGrantsTest`、`DeviceGrantsPreferenceRecoveryTest`、`ExternalBackupDataPresenceTest` |
| S4 | 备份/导出排除机器凭据与 ADB 私钥；`.credentials.yaml` 字段级剔除本机密钥 | `backup/MaintenanceDataSnapshot.java:129-130`；`assets/credential-paths.json` 的 `externalMachine`/`externalCredentials` | 拿到归档即可连接这台手机——`security-model.md:75` 已声明的历史弱点复发 | 宿主数据与备份事务（Kotlin）；Rust 只做摘要与归档 I/O | `BackupScopeTest`、`BackupScopesTest`、`CredentialPathsTest`、`LegacyNativeCredentialFallbackTest` |
| S5 | 同 UID 非内核沙箱，且对外如实声明 | `docs/security-model.md:11-18`、`:53`；`adr/0002-current-threat-model.md:15-16` | 被理解为「Rust 带来了强隔离」，据此放宽插件/终端默认值 | 文档 + 评审门禁（无运行时模块） | 无自动测试：`security-model.md` 的「已知弱点」逐字保留，外部 PR 审查逐项核对 |
| S6 | `UNKNOWN` 与 `NOT_SENT` 分开；未确认不重放、不换通道/换屏；UNKNOWN 不能反证远端退出 | `capabilities/CapabilityResult.java:8-14`、`:46-53`；`device/DeviceTransportResult.java:30-31` | 重复输入、重复点击、重复 root 命令；把「可能已执行」报成失败后自动重试 | 传输层与 FFI 契约；重试决策只在 Kotlin | `VirtualScreenTransportTest`、`VirtualScreenStartOutcomeTest`、`OneShotLaunchAuthorityTest`、`AdbVirtualScreenStartTest`、`BridgeLifecycleTest` |
| S7 | 不按裸 PID/端口/名称发信号；停止靠出生身份与会话证据 | `util/WebProcSel.java:39-52`；`DeviceShellExecutor.java:45-52`；`docs/engineering-standard.md:20` | 杀到复用 PID；杀 proot 启动器 = 环境连 App 一起走 | 进程模块（Kotlin 登记身份）+ `dsh-proc`（只接受已验证 handle） | `WebProcSelTest`、`DeviceAppPolicyTest`、`VirtualScreenCloseReconcileTest` |
| S8 | 设备写边界（共享根、DCIM、Pictures、Android/data|obb 及祖先只读）与短信独立闸门；已知缺口不粉饰 | `DeviceShellExecutor.java:99-114`；`security-model.md:55`（递归 READ 子链接目标未核验） | 受管命令读走短信库或写穿媒体目录；把缺口说成已修则失去修复压力 | `:core:policy`；Rust 读取器须能证明不跟随链接 | `DeviceShellPolicyTest` + `tools/test-device-shell-policy.py`；缺口本身**无**回归 |
| S9 | 敏感界面分档是屏幕策略的子策略：全局 `ALLOW_ALL` 不代答敏感类别、不解除密码硬阻断 | `core/DeviceGrants.java:485`；`docs/device-permissions-architecture.md:300-306` | 「全部允许」被当成隐私页面也放行，密码框回显 | 识屏模块（模块 10）+ `:core:grants` | `SensitiveScreenPolicyTest`、`SensitiveScreenClassifierTest`、`NativeConsentPolicyTest`、`ScreenConfirmationPolicyTest` |
| S10 | 识屏模式互斥，不匹配一律拒绝且不回退另一块屏 | `util/ScreenModePolicy.java:34-36`、`:40-42`；`util/ScreenCapabilityGate.java:16-18` | 请求落到用户没授权的屏 | 识屏模块（模块 10） | `ScreenModePolicyTest`、`ScreenCapabilityGateTest`、`ScreenModeFeedbackTest` |

## 三通道权限归属

原则：**Android 给你的留在 Kotlin；Linux 语义的活可以给 Rust；最终拒绝权永远在 Kotlin。**

| 能力 | 必须留 Kotlin | 可下沉 Rust | 最终拒绝权 |
|---|---|---|---|
| su 候选发现与 Root 真实身份 | 候选表与 `Os.stat` 判定；`su -c` 受管入口、stdin JSON、`myUid()==0`（`RootShellMain.java:72`） | 无（纯逻辑可由 crate 承载，调用方仍是 Kotlin） | Kotlin：发现 ≠ 授权 |
| Shizuku | `pingBinder`、UserService 绑定、回调 UID/描述符与单次窗口（`ShizukuManagerCompat.java:113`、`:137`；`ShizukuShell.java:61-77`） | 无（Rust 不持 Binder） | Kotlin |
| ADB | 用户开关、配对输入、API37 局域网门、连接状态与意图 epoch | TLS1.3-PSK + SPAKE2 客户端与 shell 帧解析（现为 Python `adb-shell.py`） | Kotlin：连接已验证 ≠ 本次已批准 |
| 命令 argv 校验 | `DeviceShellPolicy.inspect` 唯一判定点 | 同一份 `pathRules()` 的第二道复核（`DeviceShellPolicy.java:129-138`） | **Kotlin**；Rust 只能更严 |
| 进程监督/输出有界 | 65 秒与 256 KiB 的**策略值** | 轮询、超时、截断、退出码解析 | Kotlin |
| 信号发送 | 出生身份登记与本次会话归属 | 仅 `VerifiedProcess` 句柄的 `signal()`，**无 `kill(pid)`** | Kotlin |
| 系统权限/Service/无障碍 | 全部 | 无 | Kotlin |

**Rust 绕过策略的后果**：若暴露 `exec(String shell)`，任何新增 Kotlin 调用点都能跳过 `DeviceShellPolicy`，S1 退化为惯例。让绕过类型上不可能的做法：① FFI 只有 `execute(PlanEnvelope)`，envelope 含 `kind`（READ/FILE/STOP/SENSITIVE_READ/VIRTUAL_SCREEN/SHELL/DENY）、`argv[]`、`mode`、`policyRevision`、`expiresAt`、`permitMac`，**没有接收裸命令字符串的函数**；② `permitMac` 由 Kotlin 用进程内随机密钥对 plan 摘要做 HMAC，Rust 校验失败返回 `PLAN_UNTRUSTED`，因此 Rust 无法自造合法 plan、也无法判断「用户已授权」；③ `DENY` 也要能过 FFI（审计与统一文案），但只返回拒绝；④ crate 不访问 SharedPreferences/Keystore/Binder，唯一输入面是 envelope + 已打开 fd + 已建连接。

## 识屏能力矩阵与统一接口

| 能力 | DshaAccessibilityService（display 0） | PhysicalScreen 特权 Core（默认关闭） | 虚拟屏 vscreen | AdbBridge |
|---|---|---|---|---|
| 语义 dump/tree | 有（`DshaAccessibilityService.java:571`） | 无 | 有（语义适配器） | 无 |
| 节点语义动作 | 有（`treeId`/`nodeId`） | 无 | 有（`VirtualScreenAccessibility`） | 无 |
| 文本点按 | 有（`:579`） | 无 | 无 | 无 |
| 坐标触控 | 需最新截图 `observationId`（`screen/MainScreenActions.java:457-471`、`:538`） | `frameSeq`+`observationId`+`generation`，一次性 | `coreInstance`+`generation`+最新 `frameSeq` | 无 |
| 输入/按键/滑动 | 有（`:583-593`） | 固定 4 键，无文本 | 有，中文走无障碍 | 无 |
| 截图 | 有，存私有 `Pictures/DSHA`，不宣称不落盘 | 抓帧不落盘不进日志 | `see` 全分辨率副本（30 秒/2 份） | 无 |
| 依赖与失败码 | 无障碍；`SCREEN_*`/`FRAME_NOT_OBSERVED`/`TARGET_CHANGED` | 任一特权通道；`PRIV_*` | 同上；`VSCREEN_*`+`nextAction` | `PAIR_OK`/`CONNECT_OK` |

**该抽**：能力枚举（`DUMP/TREE/NODE_ACTION/TAP_TEXT/PIXEL_GESTURE/INPUT/KEY/SWIPE/SCREENSHOT/FRAME_OBSERVE/ANCHOR`）、统一失败码、能力对「无障碍/哪条设备通道」的声明位。

**不能抽**：凭据本身。统一层只持三套 sealed 类型——`PixelObservation(observationId, display, generation, frameSeq, issuedAt)`、`SemanticNode(treeId, nodeId, connectionEpoch, observedAt)`、`Anchor(originalObservation, anchorId, geometry)`，参数位必须互不兼容。ADR0003 决定二明确三套观察门不可互换（`adr/0003-virtual-screen-lifecycle-and-input.md:39-47`）。同样不能抽「observe 自动附加」「失败后换输入路径」「敏感界面自动确认」。

**「动作未确认」的 K↔R 语义**：必须传五态枚举（`NOT_SENT/REJECTED/COMPLETED/PARTIAL/UNKNOWN`），不是 bool、不是错误串。硬约束：① 未知 tag 一律 `UNKNOWN`（fail-closed），绝不默认 `COMPLETED`；② `PARTIAL`/`UNKNOWN` 保留 `executionMayHaveOccurred`，Rust 不得内部改写成 `Err`；③ `UNKNOWN` 却带 `remoteExitConfirmed` 直接报错（沿用 `DeviceTransportResult.java:30-31`）；④ 重试只能由 Kotlin 在**新观察+新凭据**后显式发起。

## 敏感能力默认值

| 能力 | 默认 | 事实来源 | 新架构要求 |
|---|---|---|---|
| 短信读取 | 关 | `DeviceGrants.java:79-81`（`sms_read=false`） | 仅当前用户 `content query --uri content://sms`；Shizuku 不执行；正文不进诊断 |
| 截图/读屏 | 每次请求 | `screen/ScreenSessionGrant`、`ScreenConfirmationPolicy` | 绑定运行代次与目标；截图落私有目录须如实声明 |
| 剪贴板读 | `off` | `ClipboardReadPolicy`，`:415-420` 缺省回落 `OFF` | 前台 + 系统限制；不缓存正文 |
| 位置 / 传感器 | 关 | `DeviceSense.java:61-69`、`:44-47`、`:92-115` | 撤销后 `SecurityException`/`DISABLED`，不返回旧值 |
| 麦克风 | 不预授权 | `ui/BrowserMicrophone` | 只放行当前本机页面纯音频；摄像头/屏幕音频拒绝 |
| 悬浮窗 | 需系统特殊访问 | `ScreenCapabilityGate.java:34`（`MISSING_OVERLAY`） | 只做预览提示条，不签发凭据、不转发触摸 |
| LAN | 独立启用+token | `LanAuth`、`bridge/LocalNetworkAccess` | 明文如实声明；不得因 Rust 化变成默认监听 |
| ADB / Root | 独立开关 | `DeviceBridgeService.apply`、`RootShell.enabled/present/status` | 连接或发现 ≠ 操作批准；探测不静默索权 |
| 实体屏特权执行 | 关 | `DeviceGrants.java:89-96` | 撤销即失效，无后台常驻；未授权目标 fail-closed |

「原生确认不被全局开关代答」的落地形式：全局只把 `ASK` 改成 `ALLOW`，显式 `DENY`、暂停、系统权限、目标与观察身份各自独立（`docs/device-permissions-architecture.md:242-249`）。新架构必须是**两个字段**（`globalConsent` 与 `perCapability`）；任何合并成 `permissionsEnabled` 布尔的重构都算破坏 S3+S9。

## 新增风险

| 风险 | 形态 | 对策 |
|---|---|---|
| Rust 持 fd | panic/超时后泄漏；或自行 `open` 绕过 `BridgePathPolicy` | fd 只由 Kotlin 打开并移交，Rust 记录 close 责任；桥 fd 必须已过 canonical 复核 |
| Rust 发信号 | 仅拿 pid 的 `signal()` 重演 S7 | 类型层禁止裸 pid，只接受 `VerifiedProcess` |
| Rust 监听 socket | 多一个未登记监听面，审计与撤销点丢失 | 除受管 Core 既有回环端口外禁止 `bind`，端口登记走 Kotlin |
| panic / UB | `panic=abort` 会杀整个 App（含 dsh web 与所有会话）；UB 无法隔离 | `extern "C"` 全 `catch_unwind` 映射 `UNKNOWN`；只有事务不可续才 abort，并在文档写明代价 |
| `.so` 供应链 | 新二进制资产未 pin 可被替换；16 KB 页与可复现构建 | 固定工具链、`--remap-path-prefix`、strip 后摘要进锁文件与运行时描述符，CI 核验摘要与页对齐 |
| JNI 字符串/路径 | `NewStringUTF` 是 modified UTF-8（NUL/代理对），路径可被截断 | 路径与命令走 `byte[]`+长度上限；禁止 Rust 重实现 shell 拼接，argv 数组贯穿到底（`ShellQuote` 只留一份，`util/ShellQuote.java:28-31`） |
| 脱敏退化 | 新增 Rust 日志绕过 `SensitiveData` | Rust 不直写 logcat，统一回 Kotlin 经 `SensitiveData.redact`（`:16-33`） |

## 安全回归清单

| 不变式 | 必须保留的测试/检查 |
|---|---|
| S1 白名单与 126 | `DeviceShellPolicyTest`、`DeviceShellPolicyDeleteTest`、`DeviceAppPolicyTest`、`tools/test-native-device-policy.py`、`tools/test-device-shell-policy.py` |
| S2 凭据区 | `BridgePathPolicyTest`、`CredentialPathsTest`、`CredentialReadTest`、`CredentialWriteTest`、`BridgeCredentialFilesTest`、`tools/test-bridge-token-transport.py` |
| S3/S4 授权与备份 | `DeviceGrantsTest`、`DeviceGrantsPreferenceRecoveryTest`、`BackupScopeTest`、`BackupScopesTest`、`ExternalBackupDataPresenceTest`、`LegacyNativeCredentialFallbackTest` |
| S5 非沙箱表述 | 无自动测试；`security-model.md:11-18`、`:53`、`:75` 与 ADR0002 逐条保留，外部 PR 审查核对文案 |
| S6 UNKNOWN 不重放 | `VirtualScreenTransportTest`、`VirtualScreenStartOutcomeTest`、`VirtualScreenFailuresTest`、`OneShotLaunchAuthorityTest`、`AdbVirtualScreenStartTest`、`BridgeLifecycleTest` |
| S7 不按裸 PID | `WebProcSelTest`、`DeviceAppPolicyTest`、`VirtualScreenCloseReconcileTest`、`VirtualScreenPersistentCleanupTest` |
| S8 写边界/短信 | `DeviceShellPolicyTest`、`tools/test-device-shell-policy.py`；**缺口无回归**，需新增「跟随链接的递归 READ」反例（现无此用例） |
| S9 确认与敏感界面 | `NativeConsentPolicyTest`、`NativeConsentProjectionTest`、`ScreenConfirmationPolicyTest`、`SensitiveScreenPolicyTest`、`SensitiveScreenClassifierTest`、`CapabilityBrokerTest`、`ScreenSessionGrantTest`、`VirtualScreenPasswordProbeTest` |
| S10 模式互斥 | `ScreenModePolicyTest`、`ScreenCapabilityGateTest`、`ScreenModeFeedbackTest`、`VirtualScreenManagerRevocationTest` |
| 识屏凭据不可互换 | `MainScreenObservationTest`、`VirtualScreenObservedPixelsTest`、`VirtualScreenAnchoredChainTest`、`VirtualScreenNativeInputFrameBoundaryTest`、`VirtualScreenNativeControlsTest`、`VirtualScreenActivityNodeControlsTest`、`PhysicalScreenManagerTest`、`VscreenTargetEvidenceTest` |
| 脱敏与转义 | `SensitiveDataTest`、`ShellQuoteTest`、`BridgeCredentialAuthTest`、`DiagnosticHistoryTest` |

## 给 Lead 的一句话结论

Rust 只能拿「Kotlin 判过、且带不可伪造许可」的已解析计划去执行；识屏统一层只统一能力与失败码，绝不统一凭据；三条已知弱点与「同 UID 非内核沙箱」必须原样进新仓库，否则这次重写的净效果是把安全边界从拒绝点降级成惯例。

待用户裁决：① 是否接受 `.so` 进入受管运行时输入（新增 pin 与可复现构建成本）；② 是否允许 Rust 侧 errno 级重试（当前立场：禁止，重试只在 Kotlin）；③ 短信递归 READ 缺口（`security-model.md:55`）在重写前单独修，还是与新架构一并修并补反例回归。
