# 安卓授权申请模块：现状整理与厂商扩展准备

> 状态：**资料整理 + 设计准备**，未实现代码，未做真机验收。
> 核对基线：`75924add`（build362，`0.2.1-rc.5-20261113.1000-dsh0.2.2-rc.1`），Android 原生（`minSdk 30` / `targetSdk 37`）。
> 范围：原生 Android + 小米/Redmi（MIUI/HyperOS）、OPPO/一加/realme（ColorOS）、vivo/iQOO（OriginOS/Funtouch）、荣耀（MagicOS）、三星（One UI）。
> **明确排除**：原生鸿蒙 HarmonyOS NEXT 与 iOS。若 DSHA 运行在鸿蒙的 Android 兼容环境（卓易通/出境易），按「未知 Android 兼容环境」降级，不建厂商模块。
> 配套：[现有字段与流程清单](inventory.md)、[五厂商差异资料](vendors.md)。

## 1. 结论摘要

1. **现有实现已经覆盖 AOSP 主线，但分散在多个入口**：Manifest 25 条权限、运行时权限请求分布在 6 类入口、特殊访问/系统页约 9 类、设备通道 3 条（Root/Shizuku/ADB），另有 DSHA 本机授权 6 类。没有统一的权限注册表，也没有厂商抽象。
2. **真正的难题不是 AOSP 权限，而是厂商私有管理**：小米官方文档已明确「自启动/后台弹页/后台本地通知」默认拒绝、白名单制、无公开 API；OPPO/vivo/荣耀/三星的对应能力同样没有公开 API。模块只能返回 UNKNOWN，不能伪造 GRANTED。
3. **Android 13+「受限设置」与 Android 15+ vivo 侧载门禁是新的横向前置**：侧载 DSHA 在开启无障碍/悬浮窗/通知使用权前，可能需要用户在系统应用详情里二次允许；vivo 的官方门禁范围明显宽于 AOSP。模块必须能解释这种现象，不能按普通拒绝处理。
4. **Android 17 带来两类新阻断**：target 37 的 `ACCESS_LOCAL_NETWORK` 运行时权限（本仓库已接），以及用户可开启的 **AAPM 高级保护模式**（禁止侧载、强制 Play Protect、禁用非工具型无障碍）；后者会直接影响 Mod 包的分发与安装。
5. **三星 One UI 6+ 的 Auto Blocker 默认开启**：侧载安装、USB 命令、USB 软件更新可能被拦；「安装未知应用」允许不等于能安装，不能尝试绕过。
6. **现有 DSHA 授权边界必须保留**：`DeviceGrants`（全局确认/屏幕/敏感界面/短信/剪贴板/特权执行）、`CapabilityBroker`（一次性动作）、`ScreenSessionGrant`、`TurnCapabilityGrants` 等是 DSHA 自有授权，不是 Android 权限；新的权限模块只读、只解释、只导航，**不能**成为绕过这些门禁的入口。
7. **建议分两层落地**：先做 **AOSP 权限统一层**（`PermissionRegistry` + `PermissionReader` + `SystemSettingsNavigator`），再做 **厂商只读适配层**（`VendorOs` + `VendorProfile` + 已验证 Intent 表）。厂商私有状态永远不参与执行门禁。
8. **验收门槛应是「每机型证据」**：任何厂商 Intent/路径在进入生产前，必须具备目标 ROM 的 `resolveActivity` 结果、系统页截图与返回后复查记录；没有证据就只显示文字帮助，不显示「已开启」。

## 2. 现有资产一览

完整字段与行号见 [inventory.md](inventory.md)。本节只列模块化时要复用的资产。

| 资产 | 现状 | 对新模块的意义 |
|---|---|---|
| `AndroidManifest.xml` | 25 条 `uses-permission` + 4 个组件级权限 + `<queries>` | 新模块的「声明清单」基线；新增权限必须同步 |
| 首次引导 `PermissionPanel` | 7 项状态（展示 6 项），用途→卡片推荐，1 秒轮询，通知申请/拒绝计数 | 新模块的第一个消费者；不要重写整套 UI，先替换状态读取 |
| 设置页 `DeviceGrantsFragment` | 全局确认 → 通道/屏幕入口 → 系统权限 → 敏感能力 | 新模块的第二个消费者；通知目前**没有**行，需要补 |
| `PermissionActions` | 特殊访问的只读判据 + 系统页跳转 + 回退 | 可抽成 `SystemSettingsNavigator`，统一回退顺序 |
| `PermissionCards` / `PermissionChannelCard` | 卡片文案与按钮、Shizuku 三态、Root 验证 | 新模块的 UI 语义基线；厂商帮助应复用卡片而不是另起弹窗 |
| `BatteryBackgroundState` | UNRESTRICTED / ALLOWED / RESTRICTED 三态（只读 AOSP） | 新模块的「可读事实」示例；厂商私有项只能 UNKNOWN |
| `DeviceGrants` | `dsha_device_grants` 的 6 类本地授权 + 严格编码/损坏恢复 | 不要与新模块合并命名空间；新模块只读 |
| `SafGrants` | `saf_capability_ledger` 账本 | 文件访问不是「权限」，不要塞进权限注册表 |
| `CapabilityBroker` | 一次性动作许可、epoch、deadline、dispatch permit | 新模块不参与动作许可；权限状态不能替代它 |
| `ShizukuChannelStatus` / `RootShell` / `AdbBridge` | 通道状态机与身份核验 | 设备通道保持独立状态机；不要用「权限已授予」概括 |
| 测试 | `PermissionGoalsTest`、`BatteryBackgroundStateTest`、`GrantSummaryTest`、`DeviceGrantsTest` 等 | 新纯逻辑必须有对应单测；UI 行为走布局审计 |

### 2.1 现有 AOSP 权限请求点（模块化候选）

| 能力 | 当前入口 | 建议的统一 Key |
|---|---|---|
| 通知 | 引导面板、更新下载、助手任务 | `NOTIFICATIONS` |
| 麦克风 | 网页麦克风、助手语音 | `MICROPHONE` |
| 定位 | 设备授权页位置开关 | `LOCATION` |
| 局域网 | 主界面、ADB 配对（API 37） | `LOCAL_NETWORK` |
| 所有文件访问 | 设备授权页、引导存储卡 | `ALL_FILES` |
| 悬浮窗 | 引导、设置页、助手、悬浮条 | `OVERLAY` |
| 安装未知应用 | 更新页、`/app/install` | `INSTALL_UNKNOWN` |
| 电池优化 | 引导、设置页 | `BATTERY` |
| 无障碍 | 引导、设置页、屏幕操作页 | `ACCESSIBILITY` |
| Shizuku | 通道页、引导 | `CHANNEL_SHIZUKU` |
| ADB | 通道页、配对页 | `CHANNEL_ADB` |
| Root | 通道页、引导 | `CHANNEL_ROOT` |

## 3. 建议的数据模型（设计草案）

### 3.1 枚举

```text
PermissionKind
  RUNTIME          // checkSelfPermission + requestPermissions
  SPECIAL_ACCESS   // 系统「特殊应用访问」页 + 自定义判据
  SERVICE_BINDING  // 无障碍等由系统绑定/用户开关
  DEVICE_CHANNEL   // Root/Shizuku/ADB，独立状态机
  VENDOR_PRIVATE   // 厂商私有管理，无公开 API，默认 UNKNOWN
  DSHA_LOCAL       // DeviceGrants 等本机授权，独立命名空间

PermissionState
  GRANTED
  DENIED
  DENIED_FOREVER        // Android 11+ 二次拒绝/不再询问
  UNKNOWN               // 读不到（厂商私有、设置解析失败）
  UNSUPPORTED           // 当前系统无此能力
  NEEDS_SETTINGS        // 需要去系统设置（特殊访问/受限设置）
  NOT_CONNECTED         // 已授权但服务未连接（无障碍/Shizuku）
  PENDING               // 请求进行中/等待用户返回

PermissionEvidence
  source        // SYSTEM_API | SETTINGS_READ | COMPONENT_STATE | VENDOR_UNKNOWN | USER_CONFIRMED
  readAtElapsedRealtime
  androidSdk / buildFingerprint
  confidence    // CONFIRMED | INFERRED | UNKNOWN
```

### 3.2 `PermissionSpec`（注册表条目）

```text
PermissionSpec
  id                  // PermissionKey，稳定字符串，不写 UI 文案
  kind
  manifestPermission  // 可为空（SERVICE_BINDING/VENDOR_PRIVATE）
  read()              // 只读判据，返回 PermissionState + Evidence
  request             // RUNTIME 的 ActivityResultContract 身份；SPECIAL 的 IntentSpec 列表
  localSwitchKeys     // 关联的 DSHA 开关（只读展示，不代改）
  usageGate           // 真正执行能力的使用点（例如 DeviceSense/OverlayController）
  rationaleKey        // 文案键，避免硬编码中文/英文
  impact              // 关闭/拒绝时哪些功能不可用
  revocation          // 如何撤销、撤销后哪些旧请求失效
  evidenceLevel       // 官方文档 / 社区 / 真机验证；默认不得为「已验证」除非有设备证据
```

### 3.3 `VendorProfile`（厂商只读适配）

```text
VendorOs
  family        // AOSP | MIUI_HYPEROS | COLOROS | ORIGINOS | MAGICOS | ONE_UI | UNKNOWN
  brand, model, osVersion, androidSdk
  confidence    // CONFIRMED | LIKELY | UNKNOWN
  evidence      // 命中的 Build/系统属性，不记录无关个人信息

VendorCapability
  key           // AUTOSTART | BACKGROUND_POPUP | BACKGROUND_NOTIFICATION |
                // BATTERY_UNRESTRICTED | APP_SLEEP | SIDELOAD_GATE |
                // RESTRICTED_SETTINGS | ACCESSIBILITY_KEEPALIVE
  state         // 永远是 UNKNOWN（除非 AOSP 可读事实能证明）
  readable      // false：本模块不能读
  helpKey       // 系统页路径/说明
  intents       // ordered IntentSpec，全部 resolveRequired
  officialUrl   // 官方文档
  deviceNotes   // 限定版本/机型，必须来自真机证据

IntentSpec
  action / component / data / extras
  fallbackOrder
  source        // OFFICIAL | COMMUNITY | DEVICE_VERIFIED
  verifiedOn[]  // build fingerprint + date；空数组时仅可用于「帮助文字」
```

### 3.4 状态机（读与请求分离）

```text
读取：
  UNKNOWN ──read()──> GRANTED / DENIED / NEEDS_SETTINGS / NOT_CONNECTED / UNSUPPORTED
  厂商私有：永远 UNKNOWN（可另存用户确认提示，但不作为执行门禁）

运行时请求：
  NOT_REQUESTED -> REQUESTING -> GRANTED
                              -> DENIED ->（再次请求）-> GRANTED / DENIED_FOREVER
  DENIED_FOREVER -> 只能打开系统设置，不再弹框

特殊访问：
  NEEDS_SETTINGS -> 用户点击 -> 系统页 -> 返回 onResume 重新 read
  失败：应用详情 -> 对应 AOSP 总列表 -> 文案说明；不循环重试

设备通道：
  INSTALL/NOT_RUNNING -> CONNECT -> AUTHORIZE -> BINDING -> READY / BIND_FAILED
  与 Android 权限无关；不能共用 PermissionState
```

**关键规则**：`GRANTED` 只来自可读事实；`NEEDS_SETTINGS` 不等于失败；`UNKNOWN` 不等于未开；`USER_CONFIRMED` 只能用于提示，不能作为执行授权。

## 4. 模块拆分建议

### 4.1 包与职责

| 层 | 建议位置 | 职责 | 不允许 |
|---|---|---|---|
| 契约与纯逻辑 | `app/src/main/java/.../permission/`（新包）或 `util/` 纯逻辑部分 | Key/State/Spec、回退顺序、厂商匹配规则（纯函数） | Android API 调用、UI |
| Android 适配 | `permission/` 内的 `SystemPermissionReader`、`SystemSettingsNavigator`、`ActivityResultRegistry` 包装 | 读系统状态、构造 Intent、发起请求 | 自动授权、隐藏 API、root 绕过 |
| 厂商数据 | `permission/vendor/VendorProfiles`（Java 常量或受管 JSON） | 仅数据：路径、Intent、官方链接、证据等级 | 运行时反射厂商私有服务 |
| UI 消费者 | 现有 `PermissionPanel`、`DeviceGrantsFragment`、`DeviceChannelsFragment`、`AccessibilitySetupActivity`、`UpdateActivity` | 渲染状态、解释、导航 | 各自持有重复判据 |
| DSHA 授权 | 现有 `DeviceGrants` / `CapabilityBroker` | 不变 | 被新模块改写或合并 |

> 仓库约定 `util/` 不 import Android API；厂商识别依赖 `Build`，因此**不要**把 Android 调用塞进 `util/`。可把纯匹配函数（输入 manufacturer/brand/props 字符串）放 `util/` 并配单测，Android 采样放 `permission/`。

### 4.2 与现有入口的接线

| 入口 | 改造点 | 风险控制 |
|---|---|---|
| `PermissionPanel` | 用 `PermissionRegistry` 替换 `states()` 的 7 项硬编码；保留 6 张展示卡与用途推荐；通知申请继续走独立 contract | 不改变首次引导步骤/跳过语义；布局仍走 `LayoutAuditInstrumentation` |
| `DeviceGrantsFragment` | 新增「通知」行；系统权限区统一从 registry 读状态；新增「后台与厂商设置」只读帮助入口（可选） | 不改变现有开关/二次确认；不把厂商帮助做成主授权入口 |
| `DeviceChannelsFragment` | Shizuku/Root/ADB 状态继续由各自状态机提供；权限注册表只显示「通道是否可用」摘要 | 不合并三通道；不改命令执行顺序/策略 |
| `AccessibilitySetupActivity` | 增加受限设置/vivo 门禁的帮助文案；保留「返回后检查连接」 | 不自动开启；不引导绕过受限设置 |
| `UpdateActivity` / `HttpShellService` | 安装未知应用前统一提示；三星 Auto Blocker、Android 17 AAPM、vivo 门禁单独解释 | 失败后不自动重试；保留「用户返回后重新请求」 |
| `PermissionActions` | 抽为 `SystemSettingsNavigator`，统一「应用详情 → AOSP 特殊页 → 文案」回退 | 每个入口的现有回退差异先记录，再逐步统一；不得改变用户可见行为而不测试 |
| `BatteryBackgroundState` | 保留三态；厂商帮助只解释「无法读取」 | 不新增 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`；不把厂商页当授权 |
| `DeviceGrants` / `CapabilityBroker` | 不变 | 新模块只读，禁止写入/递增 revision |

### 4.3 建议新增的 `<queries>`（仅当真机确认要探测时）

按厂商文档/社区清单，可能需要的包：`com.miui.securitycenter`、`com.miui.powerkeeper`、`com.coloros.safecenter`、`com.oplus.safecenter`、`com.oppo.safe`、`com.oneplus.security`、`com.vivo.permissionmanager`、`com.iqoo.secure`、`com.hihonor.systemmanager`、`com.huawei.systemmanager`、`com.samsung.android.lool`。**这些包名大多未经本仓库真机验证，先不要一次性写进 Manifest**；按「设备证据 → 最小声明」逐步加，并评估 Play 政策与包可见性影响。`startActivity` 本身不需要 `<queries>`，只有 `resolveActivity`/查询需要。

## 5. 厂商模块准备（摘要）

完整矩阵与来源见 [vendors.md](vendors.md)。模块化时只需要记住四件事：

1. **官方文档能证明「没有 API」**：小米三篇文档（自启动/后台弹页/后台通知）明确默认拒绝 + 白名单；OPPO/vivo/荣耀/三星没有对应公开 API。不要设计「读取厂商私有状态」的接口。
2. **vivo 的侧载门禁是官方行为**：Android 15+ 侧载应用访问短信、默认应用、无障碍、使用情况、悬浮窗、通知使用权、设备管理时弹「风险受限提示框」；用户可选保持/解除，解除需机主校验。模块必须能识别并解释，不能当普通拒绝。
3. **三星 Auto Blocker 是安装前置**：One UI 6+ 默认开启，阻止非商店安装和 USB 命令/更新；安装/更新失败时先解释 Auto Blocker，再谈「未知来源」。
4. **Android 17 AAPM 是设备级开关**：禁止侧载、强制 Play Protect、禁用非工具型无障碍；Mod 包无法绕过，只能提示用户关闭或改用 ADB 安装。

## 6. 实施顺序（建议）

### Phase 0：资料与模型（本阶段，无行为变化）

- 本文 + `inventory.md` + `vendors.md` 合并评审。
- 纯逻辑原型与单测：`PermissionState` 转换、回退顺序、厂商匹配（输入字符串，不接 Android）。
- 不改 Manifest、不改 UI、不新增 `<queries>`。

### Phase 1：AOSP 统一读层

- 建 `PermissionRegistry`/`PermissionReader`，先覆盖 12 个 Key（见 2.1）。
- `PermissionPanel`、`DeviceGrantsFragment` 改为从 registry 读状态；输出与旧实现逐项对比，保持行为等价。
- 补通知设置行（当前缺失）。
- 测试：纯逻辑单测 + 现有 UI 测试/布局审计。

### Phase 2：特殊访问与受限设置

- 统一 `SystemSettingsNavigator` 的回退顺序；保留每个入口的现有回退，逐步替换。
- 增加「受限设置」三态帮助（不新增自动开启）。
- 补安装未知应用的返回后复查（`onResume`），不自动重试。

### Phase 3：厂商只读帮助

- `VendorOs` 识别 + `VendorProfiles` 首批文字帮助（先不跳厂商组件，只用 AOSP 回退）。
- 在引导/设置页显示「该机型可能限制后台」的说明；不显示「已开启」。
- 真机采集：每厂商至少一台，记录 `Build` 指纹、系统页截图、`resolveActivity` 结果。

### Phase 4：已验证 Intent

- 只在有设备证据的机型/版本上启用厂商组件跳转；数据表按 `verifiedOn` 过滤。
- 全部失败回退应用详情；不重试、不猜第二个组件。
- 对 vivo 门禁、Samsung Auto Blocker、AAPM 做专门文案与测试。

### Phase 5：验收与文档

- 真机矩阵（见第 7 节）与证据归档到 `docs/releases/`。
- 更新 `documentation-index.md`、`device-permissions-architecture.md` 的入口说明。
- 如新增 Manifest 权限/`<queries>`，同步安全模型与构建检查。

## 7. 验证计划

### 7.1 设备矩阵（最低）

| 平台 | 建议版本 | 重点 |
|---|---|---|
| AOSP/Pixel 模拟器 | API 30 / 33 / 37 | 基线、通知权限、受限设置、本地网络 |
| 小米/Redmi | HyperOS 3/4（Android 16/17） | 自启动/后台弹页/后台通知、PowerKeeper、AAPM、受限设置 |
| OPPO/一加 | ColorOS 16/17（Android 16/17） | 耗电管理、OppoGuardElf、ColorOS 冻结、Auto Blocker 不适用 |
| vivo/iQOO | OriginOS 7（Android 17） | **侧载敏感权限二次门禁**、后台高耗电、悬浮窗 |
| 荣耀 | MagicOS 10/11（Android 16/17） | 应用启动管理、纯净模式、无障碍保活 |
| 三星 | One UI 8/9（Android 16/17） | **Auto Blocker**、休眠名单、未使用应用暂停、USB/ADB |

### 7.2 每台设备要记录

- `Build.MANUFACTURER/BRAND/MODEL/DISPLAY/FINGERPRINT`、`Build.VERSION.SDK_INT/RELEASE`。
- 每个能力的：系统页路径、截图（脱敏）、`resolveActivity` 结果、返回后 `read()` 结果。
- 侧载安装：`/app/install` 与 App 内更新的实际提示（Auto Blocker/vivo 门禁/AAPM）。
- 后台 30 分钟：进程是否存活、无障碍是否仍连接、通知是否仍可发；用 `adb logcat -b events | grep am_kill` 记录 Reason（需用户授权的 ADB/终端，不写入公开证据）。
- 权限撤销/自动重置后：模块是否能读回 DENIED/UNKNOWN；不缓存旧 GRANTED。

### 7.3 验收判据

- 所有 `GRANTED` 都能追溯到可读 API 或系统组件状态；厂商私有项全部 UNKNOWN。
- 任何失败路径都能给出「为什么 + 去哪里 + 返回后如何复查」，且不自动重试/不换通道。
- 不新增隐藏 API、反射厂商服务、root/ADB 绕过。
- 现有 `DeviceGrants`/`CapabilityBroker` 行为与 revision 语义不变。
- 单测覆盖所有纯逻辑；真机证据按机型记录，不用单机结果外推全部 ROM。

## 8. 开放问题（需要用户/维护者决定）

1. **厂商状态如何展示**：是否允许用户手动勾选「我已在系统设置开启」？建议只作提示，不参与执行门禁；需要产品确认。
2. **`<queries>` 的取舍**：是否为五家系统管理器一次性声明包名？建议按真机证据逐个加，避免无依据扩大包可见性。
3. **通知入口放在哪里**：补在 `DeviceGrantsFragment` 系统权限区，还是新建「权限与后台」子页？建议先补行，后续再拆页。
4. **Android 17 AAPM/开发者验证**：Mod 包的分发说明是否需要在安装页明确提示？这是产品/发布策略，不只是权限模块。
5. **vivo 门禁的测试优先级**：DSHA 的悬浮窗与无障碍都在 vivo 清单内；是否要在文档里单独写「vivo Android 15+ 侧载说明」？
6. **厂商 Intent 的证据门槛**：是否要求「同一机型 + 同一大版本 + 连续两次成功」才可进生产表？建议是。
7. **是否统一请求码**：现有 104/195/37/501 分散；建议新模块一律用独立 `ActivityResultContract` 注册身份，旧码不动。
8. **是否记录厂商私有状态的用户选择**：如果记录，必须明确「不是授权、不随备份迁移、不参与执行」，并与 `DeviceGrants` 分开命名空间。

## 9. 来源与边界

- AOSP 官方：权限总览、运行时权限、特殊权限、Android 11 权限变更、Android 13 通知权限、包可见性、应用休眠、FGS 超时、本地网络权限、实时活动、Android 17 行为变更（链接见 [vendors.md](vendors.md) 第 0.4/9 节）。
- 厂商官方：小米澎湃 OS 开发者平台（自启动 pId=1624、后台弹页 pId=1625、后台通知 pId=1626、PowerKeeper pId=1628、进程管理 pId=1607、Android 17 适配 pId=2297）；三星 Knox Auto Blocker；vivo 官方 FAQ（侧载受限提示）；荣耀/OPPO/vivo 支持页（部分只抓到标题，正文未能抓取）。
- 第三方/社区：dontkillmyapp、notifee 后台限制文档、AutoStarter、XXPermissions、DeviceCompat、MIUI-autostart、HyperOS-a11y、Monik、GKD FAQ、Kvaesitso 受限设置说明；均为非官方资料，版本可能失效。
- 本仓库：`device-permissions-architecture.md`、`device-channels.md`、`security-model.md`、`ui/` 权限入口、`core/DeviceGrants`、`capabilities/CapabilityBroker`、`saf/SafGrants`、`docs/source-updates/` 中 vivo/小米设备证据、`docs/island-status.md` 版本表。
- **验证边界**：本文与两份配套文档均只做静态源码 + 网络资料核对；没有安装 APK、没有在五家真机上操作。所有厂商路径/Intent 均为候选，未标记 `DEVICE_VERIFIED` 前不得进入生产。
