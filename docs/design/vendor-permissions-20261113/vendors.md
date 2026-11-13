# 五厂商权限/后台管理差异资料（小米、OPPO、vivo、荣耀、三星）

> 范围：Android 基础上的 OEM 定制（MIUI/HyperOS、ColorOS、OriginOS/Funtouch、MagicOS、One UI）。**原生鸿蒙 HarmonyOS NEXT 与 iOS 明确不纳入**；若 DSHA 在鸿蒙的 Android 兼容环境（卓易通/出境易）中运行，只按「未知 Android 兼容环境」降级，不建立厂商模块。
> 核对日期：2026-11-13。方法：网络公开资料 + 当前仓库源码交叉核对；**未在五家真机上执行**。所有设置页路径、菜单名、Intent 组件与版本对应都可能随 ROM 更新变化。
> 结论先行：**这五家都没有把「自启动 / 后台弹出页面 / 后台本地通知 / 后台运行 / 应用休眠」开放为 Android 标准权限或公开申请 API。** 厂商官方文档本身也写「默认拒绝 + 白名单」。应用侧的基础路径是「检测系统类型 → 解释路径 → 打开系统页 → 返回后按 AOSP 可读事实复查 → 能力使用点降级」；按用户 2026-11-13 裁定的**开发性基准线**，允许在隔离桥接层中使用厂商隐藏 API/反射读取或修改这些私有状态，但必须版本门控、用户可见、可回退、有证据，且不能替代 DSHA 自有授权。

### 基准线调整（2026-11-13 用户裁定）

> 「当前项目为开发性产物，一切行为均为开发性属性，非生产性属性。」

本文中「隐藏 API」不再整体禁止，但使用范围仅限开发/实验代码，并须满足：

1. 集中封装（建议 `permission/vendor/hidden/`），不在 UI/业务层散落；
2. 版本/机型门控；未知 ROM 版本直接 UNKNOWN，不猜字段；
3. 用户可见（实验开关/明确提示），默认关，不静默改系统状态；
4. 失败回退官方设置页/文字说明，不重试、不崩溃；
5. 只做可逆项（权限/后台白名单等）；不删除数据、不改分区、不刷机；
6. 记录 API/字段、ROM 版本、调用结果，作为开发性证据；
7. 隐藏 API 结果不能替代 `DeviceGrants`/`CapabilityBroker`/设备通道状态机；
8. 正式发布前另评估 Google Play 隐藏 API 政策与厂商条款。

本文仍会标注哪些条目需要真机验证、哪些是社区来源；允许使用不等于已经验证。

## 0. 检测与字段

### 0.1 可用的系统字段

| 字段 | 用途 | 备注 |
|---|---|---|
| `Build.MANUFACTURER` / `Build.BRAND` | 粗分厂商（Xiaomi/OPPO/vivo/HONOR/samsung） | 品牌 ≠ ROM：小米手机可能刷成 AOSP 或其他 ROM |
| `Build.DISPLAY` / `Build.ID` / `Build.FINGERPRINT` | 版本与构建线索 | 只作诊断，不作授权依据 |
| `Build.VERSION.SDK_INT` / `RELEASE` | Android 版本分流 | API 37 = Android 17（本仓库 `targetSdk 37`） |
| 系统属性（`ro.miui.ui.version.name`、`ro.mi.os.version.name`、`ro.build.version.oplusrom`、`ro.vivo.os.version`、`ro.build.version.magic`、One UI 相关属性） | 区分 MIUI/HyperOS、ColorOS、OriginOS/Funtouch、MagicOS、One UI | 具体键名与版本格式因代际而异；需逐机型采集，不能只按厂商名判断 |
| `PackageManager.resolveActivity` + `<queries>` | 探测厂商设置页是否存在 | Android 11+ 包可见性会过滤；探测失败 ≠ 页面不存在 |

参考：`getActivity/DeviceCompat` 明确把「品牌识别」与「厂商系统识别」分开，并提供 Miui/HyperOS、ColorOS、OriginOS/FuntouchOS、MagicOS/EMUI、OneUI 等判断与系统属性读取；其 README 同时说明这是社区维护的适配库、需要持续采集机型属性（[DeviceCompat](https://github.com/getActivity/DeviceCompat)、[系统属性采集](https://github.com/getActivity/AndroidSystemPropertyCollect)）。该库**不是厂商官方 API**，只能作为字段设计参考，不能作为授权状态来源。

### 0.2 DSHA 现状

当前源码没有厂商检测：只在 `DeviceSupportReport`、`ExternalUsbModes`、`IslandSupport` 等少数位置使用 `Build`/厂商字符串；权限页也没有厂商设置入口。后续模块建议新增纯逻辑 `util/VendorOs`（品牌 + ROM 家族 + 版本 + 置信度），ROM 结果用于「显示哪组帮助路径与回退顺序」以及「是否允许调用已登记的开发性隐藏 API」，不单独决定任何授权判定。

### 0.3 通用红线（开发性基准线下仍保留）

1. **不把 ROM 私有权限建模为「已授权」生产承诺**：没有公开 API；隐藏 API 读到可按 `DEV_ONLY` 显示，但读不到就是 UNKNOWN，不得用默认值、猜测或缓存伪造「已开启」。
2. **隐藏 API 不得静默、破坏性或越权使用**：只允许集中桥接层、版本门控、用户可见、默认关、可回退、可追溯；不做删除数据、改分区、刷机、替用户点掉系统门禁等操作。root/ADB 仅作为开发性实验通道，不能把失败包装成成功。
3. **跳转 intent 必须可失败**：先 `resolveActivity`，再显式 component 启动；失败按「应用详情 → 对应 AOSP 特殊访问页 → 文案说明」回退。所有候选组件都要真机核对，失败时不重试、不猜第二个。
4. **一次点击只跳一次**：用户从系统页返回后由 `onResume` 重读 AOSP 可读事实，不自动再次跳转。
5. **Android 13+ 侧载受限设置是横向前置**：侧载的 DSHA 在开启无障碍/通知监听前，可能需要先在应用详情菜单允许「受限设置」；各厂商入口名称不同，不能跳过。参见 Google 支持页《了解受限制的设置》与社区步骤说明（[Google 支持](https://support.google.com/android/answer/12623953)、[Kvaesitso 社区说明](https://github.com/mm2-0/kvaesitso/blob/c4805f3f18d5bcf071c5287f81466efd79169b20/docs/docs/user-guide/troubleshooting/restricted-settings.md)）。开发性隐藏 API 可以探测/辅助，但需满足上文「基准线调整」的 8 条。

### 0.4 通用 AOSP 基线（先按官方规则处理，再叠加厂商差异）

| 事实 | 对模块的影响 | 来源 |
|---|---|---|
| Android 11（API 30）起，同一权限被用户点「拒绝」两次后不再弹系统框（`USER_FIXED`）；`shouldShowRequestPermissionRationale` 返回 false | 不能把「returned false」直接当成首次拒绝；需要区分「从未申请 / 拒绝一次 / 永久拒绝」，Android 13 通知已用 `notify_requests`/`notify_denials` 做过类似处理 | [Android 11 权限变更](https://developer.android.com/about/versions/11/privacy/permissions#permission-dialog-visibility) |
| Android 11+ 长期未用应用会被自动重置**运行时权限**；Android 12+ 应用休眠还会清缓存、停 Job/Alarm、不投递推送 | 特殊访问（悬浮窗、所有文件、无障碍、通知渠道）不会被这套机制直接重置，但厂商可能有自己的休眠名单 | [应用休眠](https://developer.android.com/topic/performance/app-hibernation) |
| Android 13（API 33）通知权限：target 33+ 由应用自行决定弹框时机；升级安装且已有渠道/未被关闭的应用可预授予；target ≤32 在创建首个渠道后首次启动 Activity 时弹 | DSHA 当前在引导第 3 步、更新下载、助手任务三处按需申请；低版本只看总开关 | [通知运行时权限](https://developer.android.com/develop/ui/views/notifications/notification-permission) |
| 通知权限被拒后 FGS 通知不在通知栏显示，但任务管理器仍可见；FGS 本身不要求通知权限 | 「没有通知」不等于「FGS 没启动」；不能用通知是否出现判断服务状态 | [Android 13 行为变更](https://developer.android.com/about/versions/13/behavior-changes-all) |
| Android 15（API 35）起，`dataSync`/`mediaProcessing` FGS 在后台 24 小时内累计最多 6 小时，超时回调 `Service.onTimeout` | 下载/备份类长任务不能假设可无限后台运行；需在超时前 `stopSelf()` 或改用 WorkManager | [FGS 超时](https://developer.android.com/develop/background-work/services/fgs/timeout) |
| Android 17（API 37）起，target 37 的应用访问局域网必须声明并运行时申请 `ACCESS_LOCAL_NETWORK`；低 target 继续由 `INTERNET` 隐式获得 | 本仓库已是 target 37，`LocalNetworkAccess` 已在配对/设备桥/LAN 前检查；厂商 ROM 是否提前强制或额外弹框需真机验证 | [本地网络权限](https://developer.android.com/privacy-and-security/local-network-permission) |
| Android 17 的 **AAPM（用户可开启的高级保护模式）**：禁止侧载安装 APK、限制 USB 数据传输、强制 Play Protect、非工具型 AccessibilityService 被强制禁用；开发者验证框架 2026-09 起分地区执行，ADB 安装始终绕过 | DSHA 是侧载 Mod 包 + 无障碍依赖者；AAPM 开启时安装/更新/无障碍都可能直接不可用。只能提示用户关闭 AAPM 或改用 ADB 安装，不能绕过 | [小米 Android 17 适配指南](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2297)、[Android 开发者验证](https://developer.android.com/developer-verification/guides) |
| 包可见性：`startActivity()` 启动其他应用 Activity **不需要** `<queries>`；但 `resolveActivity`/`queryIntentActivities`/`getPackageInfo` 查询需要 | 「探测厂商设置页是否存在」属于查询，必须声明目标包；不能因为探测不到就断言页面不存在 | [包可见性](https://developer.android.com/training/package-visibility)、[使用场景](https://developer.android.com/training/package-visibility/use-cases) |

## 1. 小米 / Redmi（MIUI、HyperOS / 澎湃 OS）

### 1.1 官方文档（小米澎湃 OS 开发者平台）

| 文档 | 关键事实 | 来源 |
|---|---|---|
| 自启动权限管理说明 | 手机**默认不允许应用自启动**；自启动功能需告知用户；用户可自行设置允许。未提供查询/申请 API。 | <https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1624>（更新 2024-09-25） |
| 后台弹出页面权限管理说明 | 控制应用能否在后台启动页面；**默认拒绝**；音乐歌词、运动、VOIP 等特殊应用走白名单；白名单应用若推广/恶意会被永久取消。特殊需求邮箱 `miui-security-open@xiaomi.com`。 | <https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1625>（更新 2024-11-27） |
| 后台发送本地通知权限管理说明 | 应用在后台发送本地通知的能力；**默认拒绝**；音乐播放、日程提醒等白名单；建议接入小米推送。 | <https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1626>（更新 2026-03-20） |
| PowerKeeper 对应用的管控说明 | 用户可在 `设置 → 省电与电池 → 右上角 → 应用智能省电` 查看限制；应用可调用小米页面查看对自己的限制；检测到后台长期持锁/短时耗电过多/长期后台不用/非法持有传感器等行为时会释放锁、kill 进程；用户选「无限制」即取消限制；应用调用 `isBackgroundRestricted()` 会返回 **true**；官方称暂无专门通知用户的方法，未来可能通过 usagestats 报告。 | <https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1628>（更新 2022-12-07） |
| MIUI 进程管理适配说明 | 列出用户主动清理（一键清理/强力清理/锁屏清理/游戏清理/优化清理/上滑清理）与被动查杀（AutoPowerKill/AutoThermalKill）；可用 `adb logcat -b events | grep am_kill` 查看被杀 Reason；官方建议引导用户在安全中心打开自启动。 | <https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1607>（更新 2024-09-25） |
| Android 17 应用适配指南（小米） | Android 17 引入 **AAPM（高级保护模式）**：开启后禁止侧载安装、限制 USB 数据传输、强制 Play Protect 扫描、**非工具型 AccessibilityService 被强制禁用**；开发者验证框架计划 2026-09 起分地区执行；ADB 安装始终绕过。另有本地网络权限、CT 默认启用等变更。 | <https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2297>（更新 2026-05-06） |
| MIUI 11 权限说明（历史） | 定位/录音弹窗在应用提交「权限用途说明」后才多出「始终允许」；该文档为 MIUI 11 时代，现行 HyperOS 是否仍如此未核实。 | <https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1616> |

这三篇文档足以证明：小米把「自启动」「后台弹页」「后台本地通知」作为**系统侧白名单能力**管理，第三方应用没有公开 API，不能把系统设置里某一项显示为绿色当成自己已获授权。

### 1.2 用户侧路径（官方 + 社区，需按 ROM 版本核对）

- 自启动：`设置 → 应用 → 应用管理 → 自启动`；或「手机管家/安全中心 → 权限 → 自启动」；MIUI 14 还有 `设置 → 应用 → 应用信息 → 应用权限 → 后台自启动`（[dontkillmyapp Xiaomi](https://dontkillmyapp.com/xiaomi)）。小米官方进程管理文档也建议「引导用户在安全中心中打开自启动开关」（pId=1607）。
- 后台弹页：小米安全中心/应用管理中的「后台弹出界面」；官方文档只说明默认拒绝，未给路径。
- 后台通知：小米安全中心里的「后台发送通知」类开关；官方文档未给路径。
- 电池/后台（官方）：`设置 → 省电与电池 → 右上角 → 应用智能省电` 可查看/取消对应用的限制；用户选「无限制」即取消限制；应用调 `isBackgroundRestricted()` 会得到 true（pId=1628）。
- 被杀原因（官方）：`adb logcat -b events | grep am_kill`，最后一列是 `OneKeyClean`/`ForceClean`/`GarbageClean`/`LockScreenClean`/`GameClean`/`OptimizationClean`/`SwipeUpClean`/`AutoPowerKill`/`AutoThermalKill` 等 Reason（pId=1607）。
- 最近任务锁定：最近任务下拉/长按卡片锁定，避免一键清理（dontkillmyapp）。
- 开发者选项里的「MIUI 优化」可能影响后台（dontkillmyapp，非官方）。

### 1.3 可用的 AOSP 回退

任何厂商页都打不开时，回退到 `Settings.ACTION_APPLICATION_DETAILS_SETTINGS`（应用详情）+ 「电池 → 不受限制」；这是 AOSP 通用页面，不假设 MIUI/HyperOS 的菜单位置。

### 1.4 已知风险

- **隐藏 API 读取（开发性可用）**：`MIUI-autostart` 等库通过隐藏 API 读自启动状态，README 明确提示可能违反 Google Play 对隐藏 API 的政策（[MIUI-autostart](https://github.com/XomaDev/MIUI-autostart)）。按上文「基准线调整」，可作为开发性只读实现参考，但必须集中封装、版本门控、实验开关、失败回退，并记录证据。`HyperOS-a11y` 另用 Shizuku 看门狗约 1 秒恢复无障碍（10 分钟 3 次），并声称绕过「十秒盾」与受限设置（<https://github.com/Insouciant21/HyperOS-a11y>）；**只能作为系统会关闭服务的证据与开发性实验参考**，不得静默替用户开启、不得把绕过结果包装成普通授权成功。
- **受限设置**：侧载 DSHA 在 MIUI/HyperOS 上开启无障碍时可能先遇到「受限设置」；社区路径为应用信息 → 高级设置 → 允许受限制的设置（GKD FAQ：<https://gkd.li/guide/faq>），官方入口名称随版本变化，需真机核对。
- **后台通知**：小米官方文档说明默认拒绝，DSHA 的「停止按钮/部署进度/下载进度」在后台可能被拦；只能在用户主动打开应用时发，或引导用户白名单。
- **Android 17 AAPM**：小米 Android 17 适配文档写明 AAPM 开启后禁止侧载、强制 Play Protect、非工具型 AccessibilityService 被强制禁用；开发者验证框架 2026-09 起分地区执行，ADB 安装始终绕过（pId=2297）。DSHA 是侧载 Mod 包，目标设备一旦开 AAPM，安装/无障碍/更新都会受阻；只能提示用户关闭 AAPM 或改用 ADB 安装，不能绕过。
- **MIUI 无线调试**：部分 MIUI 没有独立的 `android.settings.WIRELESS_DEBUGGING_SETTINGS` Activity，需要从开发者选项进入；仓库已有回退（`ui/AdbPairActivity.java:208-215`）。
- **版本对应**：HyperOS 2 基于 Android 15（[Mashable 报道](https://in.mashable.com/tech/85438/xiaomi-begins-hyperos-2-rollout-android-15-update-to-hit-these-devices)）；HyperOS 3 媒体称有 Android 15/16 双基座（[CNMO](https://phone.cnmo.com/news/791042.html)）；维基/社区整理为 HyperOS 1=13–15、2=14–16、3=15–16、3.1=16–17、4=17（[Wikipedia HyperOS](https://en.wikipedia.org/wiki/Xiaomi_HyperOS)），仓库 `docs/island-status.md` 记录「澎湃 OS 3.0.300+/4 = Android 16/17」。**同代覆盖多基线，以真机 `Build.VERSION.SDK_INT` 为准，不按营销版本猜。**

### 1.5 Intent 与隐藏 API 候选（开发性可用；必须真机解析，不得直接用于正式发布）

- 自启动：`com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity`（AutoStarter 源码：<https://github.com/judemanutd/AutoStarter/blob/master/autostarter/src/main/java/com/judemanutd/autostarter/AutoStartPermissionHelper.kt>；该库年代较早，未在 HyperOS 3/4 验证）。
- 权限编辑：`miui.intent.action.APP_PERM_EDITOR` + `extra_pkgname`，或组件 `...permcenter.permissions.AppPermissionsEditorActivity`（XXPermissions `PermissionSettingPage.java`：<https://github.com/getActivity/XXPermissions/blob/master/library/src/main/java/com/hjq/permissions/tools/PermissionSettingPage.java>）。
- 手机管家：`miui.intent.action.APP_MANAGER` / `miui.intent.action.SECURITY_CENTER`（同上来源）。
- 省电页（PowerKeeper）组件名本次未找到可靠来源；不要猜。可按上文「基准线调整」用隐藏 API/服务名探测，但必须记录实际字段并做版本门控。
- 隐藏 API 只读候选：MIUI-autostart 库读取自启动状态的实现（<https://github.com/XomaDev/MIUI-autostart>，实测 MIUI 10–14）；具体 provider/字段名需从其源码逐项核对，**本次未在 HyperOS 3/4 验证**。开发性写入（如自启动/后台白名单）只能走可逆项、写前记录原值、失败恢复。
- 显式组件可能未导出或被包可见性拦截（XXPermissions 源码注释记录了 OPPO 同类组件的 `SecurityException`）；必须 `resolveActivity` + try/catch + 回退，失败不重试。

## 2. OPPO / 一加 / realme（ColorOS、OxygenOS）

### 2.1 官方与社区资料

| 资料 | 关键事实 | 来源 |
|---|---|---|
| OPPO 开放平台开发者社区（官方论坛） | OPPO 有独立守护进程 `OppoGuardElf` 的 `AffairResControl`，会对特定包执行 force-stop；厂商按「名单」处理，开发者只能反馈名单。该帖为 2021 年 ColorOS 场景，不能代表 ColorOS 16。 | <https://open.oppomobile.com/bbs/forum.php?mod=viewthread&tid=4788> |
| OPPO 开放平台开发者社区 | 自启动管理的「拦截记录」由系统侧识别，官方回复只说明为合规整改，未给识别接口。 | <https://open.oppomobile.com/bbs/forum.php?mod=viewthread&tid=5433> |
| dontkillmyapp Oppo | 后台服务（含无障碍服务）可能在息屏后被结束；需要最近任务锁定、启动管理/浮动应用列表（`com.coloros.safecenter`）、关闭电池优化、前台常驻通知。 | <https://dontkillmyapp.com/oppo> |
| dontkillmyapp OnePlus | App Auto-Launch、电池优化可能被系统改回、Advanced optimization（深度优化/睡眠待机优化）；ColorOS 时代的一加同样有后台限制。 | <https://dontkillmyapp.com/oneplus> |
| 仓库源码 | ColorOS 16 的「耗电管理」页（`PowerControlActivity`）不对外导出，只能先打开应用详情再由用户点进去；`DeviceGrantsFragment.java:641-667` 按此回退。 | `docs/device-permissions-architecture.md`、`ui/DeviceGrantsFragment.java` |
| 仓库源码 | 厂商文件管理器可能替换 `ACTION_OPEN_DOCUMENT_TREE` 的默认处理程序，SAF 选择器不一定是 AOSP DocumentsUI。 | `ui/SafAccessFragment.java:185` 附近 |

### 2.2 用户侧路径（社区汇总，ColorOS 版本差异大）

- 自启动：`设置 → 应用 → 自启动管理` / 手机管家 → 权限 → 自启动；ColorOS 15/16 也可能在 `设置 → 应用 → 应用管理 → 应用 → 电池用量` 里同时出现「允许自启动 / 允许后台活动」。
- 后台运行/耗电：`设置 → 电池 → 应用耗电管理 → 允许完全后台行为/允许后台活动`；ColorOS 16 的「耗电管理」在应用详情内，不导出。
- 应用闲置：ColorOS 有「应用闲置后暂停活动」类开关（Android 休眠的厂商入口），关闭后才不会被自动停权（社区：<https://llcloud.app/guides/stop-oppo-realme-killing-apps/>）。
- 最近任务锁定：最近任务 → 应用卡片 → 锁定。
- 后台弹窗：社区资料提到 ColorOS 有「允许后台弹窗」类开关（如 AirDroid 帮助页标题），但本次未取得可核实的正文与版本范围，标 **未证实**。

### 2.3 Intent 候选（开源/社区；必须真机解析）

- 自启动（旧 ColorOS）：
  - `com.coloros.safecenter/...permission.startup.StartupAppListActivity`
  - `com.oppo.safe/...` 同类入口
  - `com.coloros.safecenter/...startupapp.StartupAppListActivity`
- 一加：`com.oneplus.security/...chainlaunch.view.ChainLaunchAppListActivity`；action `com.android.settings.action.BACKGROUND_OPTIMIZE`。
- 新包名 `com.oplus.safecenter` 见于 XXPermissions 的厂商映射，但对应 Activity 未找到可靠来源。
- 以上组件来自 AutoStarter（<https://github.com/judemanutd/AutoStarter>）与 XXPermissions（<https://github.com/getActivity/XXPermissions>）的旧清单；**OPPO 旧组件多次报 `SecurityException: not exported`，不宜硬跳**。notifee 另给出 `StartupAppListActivity`、`PowerUsageModelActivity`、`PowerSaverModeActivity`、`PowerConsumptionActivity` 四个简单名（<https://docs.page/invertase/notifee/react-native/android/background-restrictions>），无包名/版本。生产代码必须先 `resolveActivity`，失败回退应用详情，不重试。

### 2.4 已知风险

- **OppoGuardElf 名单**：即使应用自己没做错，也可能被系统按名单 force-stop；只能引导用户关闭电池限制/锁定后台，无法保证。
- **无障碍服务被结束**：dontkillmyapp 明确提到息屏后无障碍服务可能被结束，需要重新开启；DSHA 的 `AccessibilityRepairOwner` 只在已鉴权 ADB 探测后做窄修复，不能承诺覆盖 ColorOS。
- **ColorOS 冻结/Athena 类机制**：开源项目 Monik 记录 ColorOS 16 上系统可能在重启/更新后改写白名单与 standby bucket（<https://github.com/vovaxa55/Monik>）；这是社区证据，说明「用户开过限制」不等于长期有效。按上文「基准线调整」，Shizuku/隐藏 API 改 AppOps/Doze/standby 只能作为开发性实验（默认关、版本门控、只改可逆项、记录原值），不得静默替用户改状态或承诺长期有效。
- **SAF 被厂商文件管理器接管**：跨应用文件访问要接受「选中的不是 DocumentsUI」的情况，不能按 AOSP 目录结构假设。
- **版本对应**：ColorOS 14=Android 14、15=15、16=16、16.5=17、17=17（Wikipedia/社区整理：<https://en.wikipedia.org/wiki/ColorOS>）；ColorOS 16 官方发布稿见 [Smartprix](https://us.smartprix.com/bytes/oppo-launches-coloros-16-officially-check-out-new-features-and-rollout-plan/)。以真机 `Build.VERSION.SDK_INT` 为准。

## 3. vivo / iQOO（OriginOS、Funtouch OS）

### 3.1 官方与社区资料

| 资料 | 关键事实 | 来源 |
|---|---|---|
| **vivo 官方 FAQ** | **Android 15 起，侧载应用（非 vivo 应用商店安装，含浏览器/文件管理器/三方应用安装）访问高敏权限会被二次门禁**：短信权限、默认应用（电话/短信）、无障碍、使用情况访问、显示在其他应用上层（悬浮窗）、设备与应用通知（通知使用权）、设备管理应用。开启时弹「风险受限提示框」，可选「保持限制」（功能/权限保持关闭）或「解除限制」（解除后该应用以上功能均不再限制）；解除时若设了锁屏密码/指纹，需要校验机主身份。**范围明显宽于 AOSP 的受限设置。** | <https://www.vivo.com.cn/service/questions/all?categoryId=170&questionId=1836> |
| vivo 开放平台文档 | 存在《权限使用及申请标准》文档（doc 928），说明 vivo 对权限申请有商店审核口径；正文本次未能抓取，只确认页面存在。 | <https://dev.vivo.com.cn/documentCenter/doc/928> |
| dontkillmyapp Vivo | 后台应用需要「自启动」特殊权限；路径随版本：`设置 → 更多设置 → 应用 → 自启动`，旧版 `i 管家 → 应用管理 → 自启动管理`；电池页有「后台高耗电」「后台耗电管理」，可逐应用允许；可把应用设为「不优化」；最近任务锁定。 | <https://dontkillmyapp.com/vivo> |
| 仓库源码/文档 | vivo V2307A / Android 16 实测：悬浮条存在时对目标应用截图可能被遮挡（`docs/source-updates/20261029-build208-overlay-occlusion-cas-timeout.md`）；说明悬浮窗与截屏路径在该 ROM 上有额外遮挡行为，和权限不是同一问题，但会影响「已授权却看不到」的判断。 | 仓库 `docs/source-updates/` |
| 仓库文档 | OriginOS 6 = Android 16、OriginOS 7 = Android 17（`docs/island-status.md` 的公开资料归纳；未上机）。 | `docs/island-status.md` |
| 版本对应（维基） | OriginOS 1.0=Android 11、2.0(Ocean)=12、3=13、4=14、5=15、6=16、7=17；Funtouch 4.5/9.x=9、10=10、11=11、12=11/12、13=13、14=14、15=15；OriginOS 6 起全球统一、Funtouch 不再维护。 | <https://zh.wikipedia.org/zh-hans/Origin_OS>、<https://zh.wikipedia.org/zh-hans/Funtouch_OS> |

**对 DSHA 的直接含义**：在 vivo Android 15+ 上，侧载包第一次请求悬浮窗/无障碍/通知使用权时会出现厂商二次确认框；这不是崩溃也不是权限拒绝，不能按普通失败处理。DSHA 不使用通知使用权（NotificationListener），但悬浮窗与无障碍都在该清单内；「解除限制」只在用户侧完成，模块应解释并等待返回后复查。按上文「基准线调整」，可用开发性隐藏 API 读取/探测门禁状态，但不得静默替用户点掉门禁、不得把隐藏 API 结果当成普通系统权限已授予。

### 3.2 用户侧路径（社区汇总）

- 自启动：`设置 → 更多设置 → 应用 → 自启动`；旧 Funtouch：`i 管家 → 应用管理 → 自启动管理`。
- 后台高耗电：`设置 → 电池 → 后台高耗电/后台耗电管理`，逐应用允许。
- 电池优化：应用信息 → 电池 → 电池优化 → 不优化。
- 最近任务锁定：最近任务中下拉/锁定。
- 后台弹窗：vivo 权限管理中有「后台弹出界面」类开关的社区说法，本次未核实。

### 3.3 Intent 候选（社区/开源，未核实）

- `com.iqoo.secure/com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity`
- 回退：`com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpManagerActivity`
- 另有：`com.iqoo.secure/com.iqoo.secure.ui.phoneoptimize.BgStartUpManager`

以上来自开源库 AutoStarter 的组件清单（README 将 vivo 标为 **Untested**，且明确「没有官方 API，组件会变」）：<https://github.com/judemanutd/AutoStarter>。notifee 文档只给出 `AddWhiteListActivity`、`BgStartUpManagerActivity`、`BgStartUpManager` 三个简单名（<https://docs.page/invertase/notifee/react-native/android/background-restrictions>）。本次未在 OriginOS 真机解析，生产代码不得直接写死；必须 `resolveActivity` + try/catch + 回退。

### 3.4 已知风险

- **没有查询 API**：自启动/后台高耗电状态无法可靠读取；只能读 AOSP 的 `isIgnoringBatteryOptimizations` / `isBackgroundRestricted`，两者都不覆盖 vivo 私有开关。
- **OriginOS 7 / Android 17**：公开资料预期支持，但仓库无上机记录；本地网络权限（API 37）与后台策略叠加时需单独验证。
- **悬浮窗/截图遮挡**：已有一加/vivo 类设备证据表明悬浮窗会影响截图内容；权限模块不能把「截图全黑/被遮挡」误判为截图权限失败。

## 4. 荣耀（MagicOS；不含 HarmonyOS NEXT）

### 4.1 官方与社区资料

| 资料 | 关键事实 | 来源 |
|---|---|---|
| 荣耀官方支持 | 存在《荣耀手机/平板应用无法后台运行(播放、下载、更新等)》与《应用后台运行一段时间再次进入时会重新启动》两篇支持文档，说明荣耀官方承认并指导用户处理后台限制；正文本次只抓到标题与导航，具体菜单名未核实。 | <https://www.honor.com/cn/support/content/zh-cn00428704/>、<https://www.honor.com/cn/support/content/zh-cn00406916/> |
| 荣耀官方支持 | 《如何关闭自启动应用》页面存在，说明自启动由系统设置管理；正文未抓取。 | <https://www.honor.com/cn/support/content/zh-cn15851169/> |
| dontkillmyapp Huawei（含荣耀旧 EMUI/MagicUI 时代） | 需要关闭「智能维护/Smart tune-up」；`设置 → 电池 → 应用启动` 改为「手动管理」并打开全部开关；`启动管理` 允许自启；EMUI 9+ 有 PowerGenie 强杀，官方不提供白名单 API。 | <https://dontkillmyapp.com/huawei> |
| 第三方工程资料 | notifee 列出华为/荣耀可能用到的 Activity 简单名：`ProtectActivity`、`StartupNormalAppListActivity`、`StartupAppControlActivity`。 | <https://docs.page/invertase/notifee/react-native/android/background-restrictions> |
| 仓库源码 | 荣耀/华为自研内核上 WebView 更新与网页确认容易卡住，仓库建议先更新 Android System WebView；这与权限模块无关，但会影响「授权后仍不可用」的判断。 | `util/DeviceSupportReport.java:145-148` |

### 4.2 用户侧路径（社区汇总，需真机核对）

- 应用启动管理：`设置 → 应用 → 应用启动管理`，关闭「自动管理」，改为手动管理，打开「允许自启动 / 允许关联启动 / 允许后台活动」三项（荣耀/华为常见路径；社区教程与论坛帖，本次未在 MagicOS 10/11 真机核实）。参见掘金教程 <https://juejin.cn/post/7579887768227545122>、荣耀俱乐部帖 <https://club-api.c.honor.com/thread-28456435-1-1.html>。
- 电池优化：`设置 → 电池 → 更多电池设置 → 电池优化` 或应用信息 → 电池。
- 最近任务锁定：部分 MagicOS 版本支持。
- 后台弹窗：荣耀有「悬浮窗」与「后台弹出界面」类权限的社区说法，本次未核实。
- 纯净模式：社区讨论称会限制非商店安装（版本/路径未核实，见 <https://club-api.c.honor.com/thread-29170061-1-1.html>）。
- 版本对应：Magic UI 6=Android 12；MagicOS 7=13、8=14、9=15、10=16、11=17（MagicOS 11 于 2026-09-15 发布，Magic9 首发；见 [Wikipedia MagicOS](https://zh.wikipedia.org/wiki/MagicOS)、[荣耀官方发布页](https://www.honor.com/cn/news/magic-os-launch/)）。以真机 `Build.VERSION.SDK_INT` 为准。

### 4.3 Intent 候选（社区/开源，未核实）

- 旧华为/荣耀（MagicUI 时代）：`com.huawei.systemmanager/com.huawei.systemmanager.optimize.process.ProtectActivity`（AutoStarter 仍按旧 MagicUI 处理荣耀，README 将 Honor 标为 **Untested**：<https://github.com/judemanutd/AutoStarter>）。
- notifee 简单名：`ProtectActivity`、`StartupNormalAppListActivity`、`StartupAppControlActivity`（<https://docs.page/invertase/notifee/react-native/android/background-restrictions>）。
- **MagicOS 7+ 的系统管家包名/Activity（例如 `com.hihonor.systemmanager`）本次未找到可靠公开来源**；不得凭猜测硬编码。新荣耀可能使用 `com.hihonor.systemmanager`，但需真机 `pm resolve-activity` 采集后才能登记。

### 4.4 已知风险

- **MagicOS 版本与机型差异大**：荣耀独立后的 MagicOS 与旧 EMUI 路径不同；不能用旧华为资料直接代表 MagicOS 10。
- **HarmonyOS NEXT 排除**：原生鸿蒙不纳入；若检测到 `isHarmonyOsNextAndroidCompatible`（Android 兼容环境），按未知环境处理，不建立厂商模块。
- **应用启动管理三项开关**：社区普遍认为需要三项全开，但没有官方 API 可读；只能显示路径，不能显示「已开启」。
- **版本对应**：MagicOS 9 基于 Android 15、MagicOS 10 基于 Android 16（[Wikipedia MagicOS](https://zh.wikipedia.org/w/index.php?title=MagicOS&oldid=95549504)、[TuttoAndroid](https://www.tuttoandroid.net/news/2025/09/09/honor-magicos-10-beta-test-android-16-modelli-1116617/)）。以真机为准。

## 5. 三星（One UI）

### 5.1 官方与社区资料

| 资料 | 关键事实 | 来源 |
|---|---|---|
| Samsung Knox 官方白皮书（Auto Blocker） | **One UI 6.0（Android 14）起可用且默认开启**；阻止非官方商店（Google Play/Galaxy Store 之外）安装应用、阻止 USB 命令、阻止 USB 软件更新、拦截消息中的恶意图片。可在初始设置或 `安全与隐私` 中关闭；「最大限制」默认关闭，开启后还会打开应用保护、阻止设备管理器应用等。 | <https://docs.samsungknox.com/admin/fundamentals/whitepaper/samsung-knox-mobile-security/system-security/samsung-auto-blocker/>（更新 2025-03-07） |
| 三星中国支持页（标题） | 《使用自动拦截程序功能保护您的三星Galaxy设备》说明 Auto Blocker 的入口与作用；正文本次只抓到导航。 | <https://www.samsung.com.cn/support/mobile-devices/protect-your-galaxy-device-with-the-new-auto-blocker-feature/> |
| dontkillmyapp Samsung | 电池优化、Adaptive battery、Sleeping apps / Deep sleeping apps / Never sleeping apps、`Put unused apps to sleep`、`Auto-disable unused apps`、Background restrictions；One UI 6 起对 target 14 的 FGS 有统一承诺，但系统仍可能把应用加入休眠名单；Good Guardians/Good Lock 可辅助。 | <https://dontkillmyapp.com/samsung> |
| Samsung 官方支持（标题） | 《Sleeping apps on your Galaxy device》说明休眠应用管理；正文 403，未能抓取。 | <https://www.samsung.com/us/support/answer/ANS10003442/> |
| 第三方工程资料 | notifee 列出三星电池页 `BatteryActivity`。AutoStarter 给出 `com.samsung.android.lool/com.samsung.android.sm.ui.battery.BatteryActivity`、`com.samsung.android.sm.battery.ui.usage.CheckableAppListActivity`、`com.samsung.android.sm.battery.ui.BatteryActivity`；其 manifest 的 queries 写的是 `com.samsung.android`（并非真实包名），**不能照抄**。 | <https://docs.page/invertase/notifee/react-native/android/background-restrictions>、<https://github.com/judemanutd/AutoStarter> |
| 仓库文档 | 上岛/实时活动在 One UI 上的实际效果未纳入仓库验证表；不能把 AOSP Live Updates 行为直接外推到 One UI。 | `docs/island-status.md` |
| 版本对应 | One UI 1=Android 9、2=10、3=11、4=12、5=13、6=14、7=15、8=16、9=17；截至 2026-11 One UI 9（Android 17）正在推送（S24 之后 S23）。 | <https://zh.wikipedia.org/wiki/One_UI>、<https://www.androidauthority.com/one-ui-9-galaxy-s23-3723034/> |

### 5.2 用户侧路径（社区汇总）

- 电池：`设置 → 电池 → 后台使用限制`；`Sleeping apps` / `Deep sleeping apps` / `Never sleeping apps`；关闭 `Put unused apps to sleep`。
- 应用电池：`设置 → 应用 → 应用信息 → 电池 → 不受限制`。
- 自适应电池：`设置 → 电池 → 更多电池设置 → 自适应电池`。
- 自动拦截器：`设置 → 安全与隐私 → 自动拦截器`（Auto Blocker），关闭后才能安装非商店 APK；这是 DSHA 自更新/`/app/install` 的新阻断点。
- 未使用应用权限移除：`设置 → 应用 → 应用信息 → 暂停未使用应用的活动`（Android 13+ 名称）；系统可能在数月后重置运行时权限。

### 5.3 已知风险

- **Auto Blocker 默认开启**：DSHA 是侧载/Mod 包，One UI 6+ 上安装更新可能被直接拦截；「安装未知应用」允许还不够，需要用户关闭自动拦截器。按上文「基准线调整」可开发性探测其状态，但不做静默绕过；只能解释与回退。关闭自动拦截器后，系统仍可能要求重新确认安装来源。
- **深度休眠**：应用被加入 `Deep sleeping apps` 后无法被普通唤醒；即使电池优化已关闭也可能被休眠名单覆盖。
- **休眠后权限重置**：Android 官方 app hibernation 会重置 runtime 权限；三星「Remove permissions if app is unused」是同类行为。权限模块必须在每次使用点复查，不缓存「曾经授予」。
- **USB/ADB 被 Auto Blocker 拦**：Knox 文档写明阻止 USB 数据线发送命令；DSHA 的无线 ADB 通道是否受影响未真机验证，不能假设「配对成功」等于「命令一定能执行」。

## 6. 横向对照

| 能力/厂商 | 小米 | OPPO/一加 | vivo/iQOO | 荣耀 | 三星 |
|---|---|---|---|---|---|
| 官方是否承认私有管理 | 是（3 篇开发者文档） | 社区/官方论坛有痕迹，无统一文档 | 有权限申请标准文档，未抓取正文 | 官方支持页承认后台限制 | 官方支持页 + Knox 白皮书 |
| 自启动 | 默认拒绝，白名单 | 系统启动管理，名单 | 自启动特殊权限 | 应用启动管理（手动三项） | 无单独“自启动”，受休眠/电池限制 |
| 后台弹出页面 | 默认拒绝，白名单 | 社区称有「允许后台弹窗」 | 社区称有，未核实 | 社区称有，未核实 | 无同名概念 |
| 后台本地通知 | 默认拒绝，白名单 | 未核实 | 未核实 | 未核实 | 受通知渠道/休眠影响 |
| 电池/后台限制 | 省电策略、MIUI 优化 | 耗电管理、深度优化 | 后台高耗电、电池优化 | 电池优化、启动管理 | 电池优化、自适应电池、休眠名单 |
| 应用休眠/自动收回 | 有（系统级） | 有（系统级） | 有（系统级） | 有（系统级） | 有且激进，`Put unused apps to sleep` |
| 无障碍被清理 | 有报告 | 有报告（dontkillmyapp） | 有报告 | 有报告（EMUI 时代） | 有报告 |
| 侧载额外阻断 | 受限设置 + 安装器校验 | 受限设置（未核实厂商差异） | **官方二次门禁**（Android 15+，敏感权限清单，含悬浮窗/无障碍/通知使用权） | 受限设置 + 纯净模式（未证实） | **Auto Blocker 默认开启**（非商店安装/USB） |
| 可查询私有状态 | 否 | 否 | 否 | 否 | 否 |
| 官方 API | 无 | 无 | 无 | 无 | 无 |

## 7. 模块化准备：建议的字段与流程（只设计，不实现）

### 7.1 建议的数据模型

```
VendorOs { family: AOSP|MIUI_HYPEROS|COLOROS|ORIGINOS|MAGICOS|ONE_UI|UNKNOWN,
           brand, osVersion, androidSdk, confidence, evidence }
VendorCapability { key: AUTOSTART|BACKGROUND_POPUP|BACKGROUND_NOTIFICATION|
                        BATTERY_UNRESTRICTED|APP_SLEEP|SIDELOAD_BLOCK|
                        RESTRICTED_SETTINGS|ACCESSIBILITY_KEEPALIVE,
                   readable: boolean,   // 有公开判据或已登记隐藏 API
                   state: GRANTED|DENIED|UNKNOWN|UNSUPPORTED|NEEDS_SETTINGS,
                   evidence: OFFICIAL|AOSP_READ|HIDDEN_API_DEV|COMMUNITY,
                   hiddenApi: optional { field/component, romRange, readOnly },
                   settingsIntents: ordered list of IntentSpec,
                   rationaleKey, helpUrl, evidenceLevel }
IntentSpec { explicitComponent?, action?, data?, extras?, resolveRequired=true,
             source: OFFICIAL|COMMUNITY|DEVICE_VERIFIED, verifiedOn[] }
```

关键原则：

- `state` 对厂商私有能力默认 `UNKNOWN`；只有 AOSP 可读事实或**已登记的开发性隐藏 API** 才能返回确定状态，且必须带 `HIDDEN_API_DEV` 证据；隐藏 API 结果不得替代 DSHA 授权。
- `settingsIntents` 按「显式组件 → 官方 action → 应用详情」排序，每项都要能 `resolveActivity`；全部失败时显示帮助文案，不循环重试。
- `evidenceLevel` 与 `verifiedOn` 是数据的一部分；未核实组件/隐藏 API 不得进入正式发布列表，只能用于开发性实验。
- 版本字段决定隐藏 API 是否可用；未识别版本直接 UNKNOWN，不猜字段。

### 7.2 建议的流程

1. **识别**：`VendorOs.detect()` 只读 `Build` + 系统属性，失败返回 UNKNOWN。
2. **解释**：卡片显示厂商名、能力名、为什么需要、当前 AOSP 可读状态（如电池优化）+ 明确「厂商私有开关默认不可读；开发性隐藏 API 读取会标实验」。
3. **跳转**：用户点击后按候选顺序启动；`ActivityNotFound`/`SecurityException` 立即回退；不自动重试。
4. **返回复查**：`onResume` 重新读 AOSP 事实；开发性隐藏 API 可重读私有状态并标 `DEV_ONLY`，失败回 UNKNOWN；用户勾选「我已在系统设置中开启」只能作为本地提示，不能作为执行门禁。
5. **使用点降级**：即使 UNKNOWN，能力使用点仍按现有安全策略执行；厂商限制导致的失败记录为「环境限制」，不冒充权限失败，也不因隐藏 API 可用就跳过 DSHA 自有授权。

### 7.3 各厂商首批要登记的资料

| 厂商 | 首批字段 | 首批待真机核实 |
|---|---|---|
| 小米 | HyperOS/MIUI 检测、自启动/后台弹页/后台通知三项说明、电池页回退 | 安全中心组件、受限设置入口、后台通知实际拦截 |
| OPPO | ColorOS 检测、耗电管理回退、深度优化说明 | `com.coloros.safecenter`/`com.oplus.safecenter` 组件、后台弹窗开关、OppoGuardElf 影响 |
| vivo | OriginOS/Funtouch 检测、自启动/后台高耗电路径、**Android 15+ 侧载敏感权限二次门禁** | `com.vivo.permissionmanager`/`com.iqoo.secure` 组件（AutoStarter 未测试）、二次门禁的实际弹框与返回判定 |
| 荣耀 | MagicOS 检测、应用启动管理三项说明 | `com.hihonor.systemmanager` 组件（未找到可靠来源）、MagicOS 10/11 菜单、纯净模式 |
| 三星 | One UI/Auto Blocker 检测、休眠说明 | Auto Blocker 对侧载安装/更新的实际提示、休眠名单、Good Guardians、USB/ADB 影响 |

## 8. 验证边界与后续

- 本文所有厂商路径、Intent 与隐藏 API 均为「资料候选」，**没有一条经过本仓库真机验收**；`source` 标注见各节。
- 官方页面可能随版本更新；引用日期为 2026-11-13，正文抓取失败的支持页只记录了标题与链接。
- 建议的验收顺序：AOSP 基线（Pixel/模拟器）→ 小米 HyperOS 3 → OPPO ColorOS 16 → vivo OriginOS 7 → 荣耀 MagicOS 10 → 三星 One UI 8；每台记录 `Build` 指纹、系统页截图、`adb shell dumpsys package` 的权限/组件解析结果、隐藏 API 调用结果（字段名/返回/异常）、后台 30 分钟存活与无障碍连接状态。
- 按上文「基准线调整」：允许在开发/实验中使用隐藏 API/反射与 root/ADB 实验通道，但必须集中封装、版本门控、用户可见、默认关、可回退、记录证据；不得删除数据、改分区、刷机或静默替用户做选择。正式发布前另评估。**验收记录必须区分「官方可选路径」与「开发性隐藏 API」。**

## 9. 来源分级

- **官方（AOSP / 厂商 / 平台）**
  - Android Developers：权限总览 <https://developer.android.com/guide/topics/permissions/overview>、运行时权限 <https://developer.android.com/training/permissions/requesting>、特殊权限 <https://developer.android.com/training/permissions/requesting-special>、Android 11 权限变更 <https://developer.android.com/about/versions/11/privacy/permissions>、通知权限 <https://developer.android.com/develop/ui/views/notifications/notification-permission>、包可见性 <https://developer.android.com/training/package-visibility>、应用休眠 <https://developer.android.com/topic/performance/app-hibernation>、FGS 超时 <https://developer.android.com/develop/background-work/services/fgs/timeout>、本地网络权限 <https://developer.android.com/privacy-and-security/local-network-permission>、实时活动 <https://developer.android.com/develop/ui/views/notifications/live-update>、Android 17 行为变更 <https://developer.android.com/about/versions/17/behavior-changes-17>。
  - 小米澎湃 OS 开发者平台：自启动（pId=1624）、后台弹出页面（pId=1625）、后台发送本地通知（pId=1626）、PowerKeeper 管控（pId=1628）、MIUI 进程管理（pId=1607）、Android 17 应用适配（pId=2297）、MIUI 11 权限说明（pId=1616），见 1.1 节链接。
  - 三星 Knox Auto Blocker 白皮书、三星中国支持页，见 5.1 节链接。
  - vivo 官方 FAQ：侧载应用敏感权限受限提示（见 3.1 节链接）。
  - 荣耀官方支持页（仅标题与 URL，正文未抓取）；vivo 开放平台文档页（仅标题）。
- **第三方工程资料**
  - notifee《Background Restrictions》：<https://docs.page/invertase/notifee/react-native/android/background-restrictions>（只给 Activity 简单名，无完整类名/版本）。
  - AutoStarter：<https://github.com/judemanutd/AutoStarter>（组件清单；README 将 Honor/Oppo/Vivo/OnePlus 标为 Untested，并说明没有官方 API、组件会变；源码 `AutoStartPermissionHelper.kt` 含小米/OPPO/一加/vivo/三星/华为组件）。
  - XXPermissions：<https://github.com/getActivity/XXPermissions>，厂商设置页映射源码见 `library/src/main/java/com/hjq/permissions/tools/PermissionSettingPage.java`；README 声称第三方厂商兼容性优化但未给明细，不能作为厂商行为证据。
  - DeviceCompat：<https://github.com/getActivity/DeviceCompat>（系统识别思路，不是授权状态来源）。
  - MIUI-autostart：<https://github.com/XomaDev/MIUI-autostart>（隐藏 API 读取自启动状态的风险示例）。
  - HyperOS-a11y：<https://github.com/Insouciant21/HyperOS-a11y>（Shizuku 看门狗恢复无障碍，含受限设置绕过；按上文「基准线调整」可作开发性参考，但必须隔离、可见、可回退，不采用其静默/绕过式默认行为）。
  - Monik：<https://github.com/vovaxa55/Monik>（Shizuku 控制 AppOps/Doze/standby；按上文「基准线调整」可作开发性实验参考，只改可逆项、记录原值、默认关）。
  - Shizuku：<https://shizuku.rikka.app/guide/setup/>（ADB 级授权与 `pm grant` 能力边界）。
- **社区/媒体**
  - dontkillmyapp：<https://dontkillmyapp.com/xiaomi>、<https://dontkillmyapp.com/oppo>、<https://dontkillmyapp.com/oneplus>、<https://dontkillmyapp.com/vivo>、<https://dontkillmyapp.com/huawei>、<https://dontkillmyapp.com/samsung>（用户路径与后台限制；非官方、随版本失效）。
  - GKD FAQ：<https://gkd.li/guide/faq>（2026 仍在更新的厂商受限设置/保活路径，社区）。
  - Kvaesitso 受限设置说明：<https://github.com/mm2-0/kvaesitso/blob/c4805f3f18d5bcf071c5287f81466efd79169b20/docs/docs/user-guide/troubleshooting/restricted-settings.md>。
  - OPPO 开放平台开发者社区帖：<https://open.oppomobile.com/bbs/forum.php?mod=viewthread&tid=4788>、<https://open.oppomobile.com/bbs/forum.php?mod=viewthread&tid=5433>（个案，非 API 文档）。
  - 版本对应：CNMO、Mashable、Smartprix、Sammobile、TuttoAndroid、Wikipedia、Android Authority；各节已就近标注。
- **本仓库**：现有权限架构、ColorOS 电池页回退、SAF 厂商选择器、vivo 悬浮窗遮挡、Honor WebView 报告、island 版本表。

## 10. 明确的缺口（不得在实现时假装已解决）

- 五家的厂商私有开关都没有**公开**可查询状态；开发性隐藏 API 可以尝试读取，但字段名、版本范围与成功/失败行为均未逐项核实，失败时必须回 UNKNOWN。
- 隐藏 API 的写入路径（自启动/后台白名单/AppOps）在五家 ROM 上的可逆性、权限要求与副作用未验证；不得默认开启或静默执行。
- OPPO/vivo/荣耀的 Intent 组件名只有社区/开源清单，且 AutoStarter 明确标注部分未测试；必须逐机型采集。
- 荣耀 MagicOS 7+ 的系统管家组件未找到可靠公开来源；`com.hihonor.systemmanager` 目前只是猜测。
- vivo Android 15+ 二次门禁的弹框时序、返回判定、与 DSHA `AccessibilitySetupActivity` 的交互未真机验证；不能据此新增自动重试。
- 三星 Auto Blocker 对无线 ADB、自更新安装、已有侧载应用更新的具体拦截边界未真机验证。
- 各厂商对「通知渠道被单独关闭」「FGS 通知被隐藏」「无障碍被清理」的行为差异未系统测量。
