# 现有安卓授权申请：字段与流程清单

> 核对基线：`75924add`（build362，`0.2.1-rc.5-20261113.1000-dsh0.2.2-rc.1`）。
> 核对日期：2026-11-13。范围：仅 Android 原生（`minSdk 30` / `targetSdk 37`），不含原生鸿蒙与 iOS。
> 方法：只读当前源码、Manifest、布局、资源与单测；**未安装 APK、未操作真机、未运行构建**。文中「未验证」项须按目标机型补设备证据。
> 相关文档：[设备权限与执行通道架构](../../device-permissions-architecture.md)、[设备通道与失败语义](../../device-channels.md)、[首次引导应用外授权（build216 历史）](../onboarding-20261031/external-authorizations.md)、[安全模型](../../security-model.md)。

本文是**现状底稿**，不是新权限设计本身。厂商差异与模块准备见同目录 [README](README.md) 与 [vendors](vendors.md)。

## 1. 先分清五层「允许」

| 层 | 事实来源 | DSHA 中的载体 | 不能替代 |
|---|---|---|---|
| Android 运行时权限 | 系统权限控制器 | `checkSelfPermission` / `requestPermissions` / `ActivityResultContracts` | 特殊访问、设备通道 |
| Android 特殊应用访问 | 系统设置页 + app-op / 组件状态 | `Environment.isExternalStorageManager`、`Settings.canDrawOverlays`、`canRequestPackageInstalls`、无障碍绑定、通知渠道 | 运行时权限、DSHA 本机确认 |
| 设备通道 | Root 管理器、Shizuku 服务、系统 adbd | `RootShell` / `ShizukuShell` / `AdbBridge` / `DeviceBridgeService` | Android 权限、DSHA 策略 |
| DSHA 本机授权 | `DeviceGrants`、`ConfigStore`、`SafGrants`、屏幕策略 | ASK/ALLOW、短信、剪贴板、特权执行、SAF 账本、确认方式 | 系统权限、设备通道身份 |
| 单次动作授权 | `CapabilityBroker` + 一次性 dispatch | `CapabilityRequest`、屏幕 lease、`TurnCapabilityGrants` | 持久授权、系统授权 |

系统权限弹窗、DSHA 确认弹窗、Root/Shizuku 管理器弹窗是不同界面；「已打开设置页」不等于「已授权」，也不等于能力在本次运行中可用。

## 2. Manifest 权限与声明

文件：`app/src/main/AndroidManifest.xml`。共 25 条 `uses-permission`（第 5–41 行），另有 4 个组件级权限与 1 组 `<queries>`。

### 2.1 普通/签名权限（安装即声明，不等于能力可用）

| 权限 | 当前用途与使用点 | 备注 |
|---|---|---|
| `INTERNET` | 本机 Web、桥、版本检查、下载 | 普通权限 |
| `CHANGE_NETWORK_STATE` | 网络状态/保活相关 | 普通权限 |
| `MODIFY_AUDIO_SETTINGS` | Chromium 枚举/选择录音设备 | 注释说明录音仍须按需授权 |
| `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` / `CHANGE_WIFI_STATE` | 网络回调、Wi-Fi 保活 | 普通权限 |
| `FOREGROUND_SERVICE` + `..._SPECIAL_USE` / `..._CONNECTED_DEVICE` / `..._DATA_SYNC` | 助手、终端、设备桥、备份、下载、恢复服务 | 与 Manifest 中 `foregroundServiceType` 对应；Android 14+ 受 FGS 类型规则约束 |
| `RECEIVE_BOOT_COMPLETED` | `BootReceiver` 开机/升级后恢复 ADB 保活、自动备份 | 普通权限；实际能否自启仍受厂商后台策略影响 |
| `WAKE_LOCK` / `VIBRATE` | 长任务、通知/触感 | 普通权限 |
| `QUERY_ALL_PACKAGES` | 结束应用前取完整用户/系统应用分组 | 商店政策敏感；源码注释明确不能依赖 Android 11+ 可见性子集 |
| `REQUEST_INSTALL_PACKAGES` | `UpdateActivity` 与 `/app/install` 安装 APK | **仍需**「安装未知应用」特殊访问；该权限本身不授予安装能力 |
| `POST_PROMOTED_NOTIFICATIONS` | Android 16+ 实时活动/上岛 | `normal|appops`，装机授予但用户可关；见 `IslandSupport` |
| `ACCESS_LOCAL_NETWORK` | Android 17（API 37）局域网/ADB/LAN | 代码按运行时权限申请；`LocalNetworkAccess` 在 API<37 直接返回 true |
| `WRITE_SECURE_SETTINGS` | 开机自动开无线调试、无障碍窄修复 | **signature|privileged**，普通安装拿不到；仅 ADB 配对后 `--grant-keepalive` 授予（`AdbBridge.java:517-537`） |
| `moe.shizuku.manager.permission.API_V23` | Shizuku/Stellar 授权 | 第三方签名权限；`ShellService` 也以它保护 |
| `SYSTEM_ALERT_WINDOW` | 悬浮条、助手窗、确认窗、虚拟屏预览 | 特殊应用访问，非运行时弹窗 |
| `MANAGE_EXTERNAL_STORAGE` | 容器/Web 工作区读写 `/sdcard` 任意位置 | 特殊应用访问；见 `PermissionActions.storage()` 与 `DeviceGrantsFragment.openAllFilesAccess` |
| `RECORD_AUDIO` | 网页麦克风、助手语音 | 运行时权限，按使用点申请 |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | 位置能力 | 运行时权限；DSHA 另有 `cap_location` 开关 |
| `POST_NOTIFICATIONS` | 部署/下载/手机任务停止按钮/ADB 保活通知 | Android 13+ 运行时权限；低版本只看总开关 |

未声明（有意或现状）：`READ_SMS`/`SEND_SMS`、`CAMERA`、`READ_CONTACTS`、`READ_CALL_LOG`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`、`MediaProjection` 相关前台服务、`BLUETOOTH_*`、`NEARBY_WIFI_DEVICES`。短信走 root/ADB 的只读 `content query`，不申请 Android 短信权限；电池优化只跳设置页，不请求豁免。

### 2.2 组件级权限

| 组件 | 权限 | 作用 |
|---|---|---|
| `DshaAccessibilityService` | `android.permission.BIND_ACCESSIBILITY_SERVICE` | 系统绑定约束，不是运行时弹窗 |
| `DshaDocumentsProvider` | `android.permission.MANAGE_DOCUMENTS` | Android 强制要求；MT 管理器/系统「本地存储」经此访问私有目录 |
| `ShizukuProvider` | `android.permission.INTERACT_ACROSS_USERS_FULL` | 接收 Shizuku Binder 的签名保护入口 |
| `ShellService`（`:shizuku` 进程） | `moe.shizuku.manager.permission.API_V23` | Shizuku UserService 受保护入口 |
| 备份 JobService | `android.permission.BIND_JOB_SERVICE` | 系统绑定 Job |

### 2.3 `<queries>`（Android 11+ 包可见性）

`AndroidManifest.xml:43-55` 声明：语音识别服务 action、`moe.shizuku.privileged.api`、`roro.stellar.manager`、`rikka.shizuku.intent.action.REQUEST_BINDER`、`OPEN_DOCUMENT`/`GET_CONTENT`、`com.android.documentsui`/`com.google.android.documentsui`、`OPEN_DOCUMENT_TREE`。厂商设置页若要通过 `resolveActivity` 探测，需要按实际包名补 `<queries>`；当前没有声明任何厂商安全中心/系统管理器包。

## 3. 运行时权限请求点

| 权限 | 请求位置 | 请求身份/请求码 | 申请前/返回后判据 | 失败与回退 |
|---|---|---|---|---|
| `POST_NOTIFICATIONS` | 首次引导第 3 步：`PermissionPanel.requestNotifications`（`ui/PermissionPanel.java:441-454`） | 动态注册 `"dsha-onboarding-notifications-" + identityHashCode`，`ActivityResultContracts.RequestPermission` | `PermissionActions.notifications`：API 33+ 查权限 + `areNotificationsEnabled`；`notificationRequestExhausted` 用 `notify_denials`/`notify_requests` 与 rationale 判断是否已被系统静默 | 不可申请/已耗尽 → 打开系统通知设置；取消不记为拒绝 |
| `POST_NOTIFICATIONS` | 更新下载：`UpdateActivity.download`（`ui/UpdateActivity.java:336-341`） | `requestPermissions(..., 104)` | 无论结果继续下载（权限只影响进度通知） | 无 |
| `POST_NOTIFICATIONS` | 手机任务停止通知：`AssistantService` 发送前检查 | `checkSelfPermission` + 渠道 `floating-assistant` 非 `IMPORTANCE_NONE`、组未屏蔽 | 任务开始前必须有停止通知，否则拒绝 `ASSISTANT_STOP_NOTIFICATION_REQUIRED` | 发通知「打开系统通知设置」/助手页按钮 |
| `RECORD_AUDIO` | 网页麦克风：`BrowserMicrophone.request`（`ui/BrowserMicrophone.java:80-134`） | 每次请求动态注册 `"dsha-microphone-" + UUID`；`MicrophoneRequestGate` 单槽 | 仅本机 `http://127.0.0.1|localhost` 同端口来源 + 页面仍 current；只接受 `AUDIO_CAPTURE` 单资源 | 拒绝 toast「可在系统应用权限中允许后重试」；页面 stop/cancel 撤销票据 |
| `RECORD_AUDIO` | 助手语音：`AssistantVoiceActivity`（`assistant/AssistantVoiceActivity.java:52-53`） | `requestPermissions(..., 195)` | 只看系统权限 | 被拒提示 |
| `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION` | 设备能力授权 → 位置开关：`DeviceGrantsFragment`（`ui/DeviceGrantsFragment.java:165-181`） | `requestPermissions(..., 104)`；与 `UpdateActivity` 的 104 不冲突（不同组件） | 返回后 `onRequestPermissionsResult` 只要任一为 granted 即保留开关，否则写回 `cap_location=false` | 弹窗「需要系统定位权限」→ 打开应用详情；同时提示需开系统定位服务 |
| `ACCESS_LOCAL_NETWORK`（API 37+） | `MainActivity.requestLocalNetwork`（`ui/MainActivity.java:118-129`）与 `AdbPairActivity`（`ui/AdbPairActivity.java:60,69-71`） | `ActivityResultContracts.RequestPermission`；配对页 `requestPermissions(..., 37)` | `LocalNetworkAccess.granted` 在 `DeviceBridgeService:119`、`HttpShellService:1139`、`LanProxyService:267`、`ScreenCapabilities` 再检查 | 拒绝 toast「局域网未授权，本机对话不受影响」；偏好 `local_network_permission_asked` 只自动问一次 |
| `READ_EXTERNAL_STORAGE` | 插件导入（仅 API<30 分支）：`PluginFragment`（`ui/PluginFragment.java:138-145`） | `registerForActivityResult(RequestPermission)` | 选中 `file://` 且 <30 时补权限 | 拒绝则不导入 |
| `WRITE_EXTERNAL_STORAGE` | 旧「所有文件访问」分支：`DeviceGrantsFragment:687-695` | `requestPermissions(..., 501)` | **minSdk 30 下不可达**，保留历史分支 | 无 |
| Shizuku 授权 | `ShizukuShell.requestPermission`（`ShizukuShell.java:151-199`） | 请求码从 `9527` 递增；独立监听器 + 60 秒超时 | `Shizuku.pingBinder`、`checkSelfPermission`、绑定 UserService 三个独立事实 | 超时/拒绝写 `ShizukuChannelStatus.Outcome`；分身用户不发注定超时的请求 |

请求码/身份汇总：`104`（通知、定位；不同组件）、`195`（语音）、`37`（局域网）、`501`（历史存储）、`9527+`（Shizuku）、动态字符串身份（通知引导、麦克风）。新增模块不应继续复用裸请求码，建议改为每能力独立 `ActivityResultContract` 注册身份。

## 4. 特殊应用访问与系统页

| 能力 | 只读判据 | 系统入口（代码） | DSHA 开关/字段 | 备注 |
|---|---|---|---|---|
| 所有文件访问 | `Environment.isExternalStorageManager()`；`StorageAccess.granted`（历史 READ/WRITE） | `PermissionActions.openAllFilesAccess`（`ui/PermissionActions.java:96-109`）：`ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` + `package:`，失败退总列表 `ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION` | 无独立开关；授权后影响容器挂载 | 撤销后可能需重启 Web/终端；厂商文件管理器可能替换默认 DocumentsUI |
| 悬浮窗 | `Settings.canDrawOverlays()` | `PermissionActions.openOverlay`（`:116-136`）：`ACTION_MANAGE_OVERLAY_PERMISSION` + `package:`，失败退应用详情；`OverlayFragment.openOverlayPermission`（`:476-486`）同源 | `overlay_stream`（`OverlayController.K_ENABLED`）是**使用开关**，与系统权限分离 | Android 11 起该 Intent 在 AOSP 上忽略 `package:`，进列表；厂商可能改行为 |
| 安装未知应用 | `PackageManager.canRequestPackageInstalls()` | `UpdateActivity.install`（`ui/UpdateActivity.java:371-380`）、`HttpShellService`（`:3510-3520`）：`ACTION_MANAGE_UNKNOWN_APP_SOURCES` + `package:` | 无 | 后台 `/app/install` 被拒时发通知打开设置页，返回后需调用方重新请求；不自动重试 |
| 电池优化/后台 | `PowerManager.isIgnoringBatteryOptimizations` + `ActivityManager.isBackgroundRestricted` → `BatteryBackgroundState.State`（UNRESTRICTED/ALLOWED/RESTRICTED） | `DeviceGrantsFragment.openBatterySettings`（`:647-667`）：应用详情 → `android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL` → `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` | 无；导航空，不请求 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 厂商私有「允许后台运行/自启动」**无法读取**；ColorOS 耗电管理页不导出，只能从应用详情进入 |
| 无障碍 | `DshaAccessibilityService.enabledState` 三态（YES/NO/UNKNOWN）+ `connected()` | `AccessibilitySetupActivity.openSettings`（`ui/AccessibilitySetupActivity.java:74-96`）：API 26+ `android.settings.ACCESSIBILITY_DETAILS_SETTINGS` + `EXTRA_COMPONENT_NAME`，失败退 `ACTION_ACCESSIBILITY_SETTINGS` | 无独立开关；屏幕确认策略另存 | Android 13+ 侧载应用可能触发「受限设置」，需在应用详情菜单允许后再开；UNKNOWN 不当未开 |
| 通知总开关/渠道 | `POST_NOTIFICATIONS`（33+）+ `areNotificationsEnabled` + 渠道/组（`AssistantService`） | `AssistantService.notificationSettingsIntent`（`:291-298`）：`ACTION_APP_NOTIFICATION_SETTINGS` + `EXTRA_APP_PACKAGE`；`IslandSupport.settingsIntent`（`:137-150`）：先 `ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS` | 无 | 渠道 `floating-assistant`、`dsh_task_progress`、`dsha_update_download`、`dsh_adb_watch_channel`；渠道被关不改变权限返回 |
| ADB 无线调试 | `DeviceBridgeService.adbState`（need_pair/reconnecting/connected）+ `AdbBridge.status`（key/port） | `AdbPairActivity`（`:208-221`）：`android.settings.WIRELESS_DEBUGGING_SETTINGS`，失败退 `ACTION_APPLICATION_DEVELOPMENT_SETTINGS` | `adb_enabled` 开关 | 配对端口与连接端口不可互换；配对成功后自动授予 `WRITE_SECURE_SETTINGS`；撤销/重启后可能失效 |
| Root | `RootShell.enabled/present/status`；`RootShellMain` 复核 UID 0 | 无系统页；由 Root 管理器弹窗 | `allow_root_shell`、`root_policy_mode`（strict/relaxed/full） | 九个固定 su 候选；发现不等于授权；验证执行 `id`；65 秒收集上限；放宽档需三项勾选 + 10 秒倒计时 |
| Shizuku/Stellar | `isInstalled/isAvailable/hasPermission/isReady` + `ShizukuChannelStatus.Step` | `ShizukuManagerCompat.launchIntent` 打开管理器；`reconnect` 只发一次 `REQUEST_BINDER` | 无独立开关 | 管理器识别：先按 `API_V23` 权限声明者，再按白名单 Stellar；UserService 15 秒绑定、60 秒授权等待；分身用户只能去机主空间授权 |
| 虚拟屏特权 | `ADD_TRUSTED_DISPLAY` `checkSelfPermission`（`vscreen/VirtualScreenCore.java:660`） | 无系统页；由 Shizuku/ADB/root 通道创建 | 屏幕操作页开关；`privileged_execution_v1` 是 T130 实体屏特权输入另一能力 | 查不到按「不持有」；不请求 TRUSTED 也可创建普通屏 |
| 截屏 | 无障碍服务 `canTakeScreenshot`（`res/xml/accessibility_service_config.xml`） | 无系统权限页；系统可能拒绝 `ERROR_TAKE_SCREENSHOT_*` | 屏幕确认策略 + 最新帧凭据 | 不使用 MediaProjection；截图存应用私有目录，不要求所有文件访问 |
| 短信读取 | `DeviceGrants.smsReadAllowed()` | 无系统页；不申请 `READ_SMS` | `sms_read`（默认 false） | 仅 root/ADB 的 `content query --uri content://sms`，只读当前用户；全局确认不替代该开关 |
| 剪贴板读取 | `DeviceGrants.clipboardReadMode()` | 无系统页 | `clipboard_read_mode_v1`：OFF/ASK/ALLOW | Android 10+ 后台剪贴板本身受限；DSHA 另行策略化 |
| 位置/传感器能力 | `cap_location` / `cap_sensors` + 系统权限/服务状态 | 位置走运行时权限；传感器无运行时权限 | `deepseekharness` 偏好；关闭时 `DeviceSense.revoke` | 位置默认关；关闭开关即撤销域请求 |

## 5. 设备通道字段与状态机

### 5.1 通道选择与身份

- `DeviceChannelOrder.order(api, adbConnected)`：固定 Root → Shizuku → ADB；API≥37 且 ADB 已连接时 ADB 排在 Shizuku 前。`Kind` = NORMAL / SMS / SHELL，决定哪些通道有资格。
- `DeviceShellPolicy.Mode`：`STRICT`（默认，白名单）/ `RELAXED`（白名单外仍拦不可逆）/ `FULL`（不按内容拦）；`ConfigStore.rootPolicyMode` 非法值回落 strict。
- `ShizukuChannelStatus.Step`：INSTALL / CONNECT / PROFILE_NO_SERVICE / REQUEST / AUTHORIZE_IN_OWNER / BINDING / BIND_FAILED / READY；`Outcome`：NONE / DENIED / TIMEOUT（仅文案，不参与判定）。
- 设备执行结果：`DeviceTransportResult` 保留 typed 状态；`[EXECUTION_UNKNOWN]` 不换通道重放；`[POLICY_BLOCKED]` 不提供「仍然允许」。

### 5.2 ADB 配对后的系统权限

`AdbBridge` 在配对连接验证后执行 `adb-pair.py --grant-keepalive`，由 shell（uid 2000）给本 App 授予 `WRITE_SECURE_SETTINGS`（`AdbBridge.java:517-537`）。该权限之后被 `BootReceiver`（开机开无线调试）、`DeviceBridgeService`、`AccessibilityRepairOwner`（窄修复无障碍）使用；源码未见主动撤销入口（只能卸载/重置或系统侧收回）。

### 5.3 无障碍窄修复

`AccessibilityRepairOwner.afterAuthenticatedProbe`（`screen/AccessibilityRepairOwner.java:74-150`）仅在同时满足：本次新鲜已鉴权 ADB 探测、当前意图未变、已获 `WRITE_SECURE_SETTINGS`、未连接、设置仍含本应用组件时，尝试重绑定；5 秒窗口、每步复核；不把未开启改成用户同意。`AccessibilityRepairState` 只保留进程内有界诊断（32 条事件），不落盘。

## 6. DSHA 本机授权字段

### 6.1 `dsha_device_grants`（`core/DeviceGrants`）

| 键 | 类型/格式 | 默认/损坏处理 | 写入者 | 作用与失效 |
|---|---|---|---|---|
| `native_global_consent_v1` | 字符串，`NativeConsentPolicy` schema `2\n<bool>\n<revision>` | 缺失=UNCONFIGURED/OFF；损坏/回退=需重新确认或 fail-closed | `setGlobalAllowed` / `rebuildConsentDisabled` | 全局「不再询问 DSHA 安全确认」；保存前先递增 revision 并失效旧确认 |
| `screen_confirmation_v1` | 字符串，`ScreenConfirmationPolicy` schema `2`：mode（MANUAL/CUSTOM/ALLOW_ALL）、revision、`pkg=ASK|ALLOW` 行 | 损坏 → `manual()` | `setScreenPolicy` | 屏幕确认方式；`invalidateScreen` 使旧请求/旧弹窗失效 |
| `sensitive_screen_v1` | 字符串，schema `1`：revision、五类 `PASSWORD/OTP/PAYMENT/SYSTEM_SECURITY/SECURE_UNKNOWN=OFF|ASK|HANDOFF`、`T=`信任应用、`H=`强制交接应用 | 损坏 → defaults（全 ASK、空名单） | `setSensitiveScreenMode` / `setSensitiveScreenApps` / `resetSensitiveScreenPolicy` | 敏感界面分档；与确认方式共同构成 `screenAuthorityRevision`；HANDOFF 不改屏幕模式 |
| `sms_read` | bool | 缺失=false | `setSmsReadAllowed` | 短信读取预授权；关闭立即阻止新查询；不从备份恢复 |
| `privileged_execution_v1` | bool | 缺失/读取异常=false | `setPrivilegedExecutionAllowed` | T130 实体屏特权输入；关闭时撤销 Core |
| `clipboard_read_mode_v1` | 字符串 OFF/ASK/ALLOW | 缺失/非法=OFF | `setClipboardReadMode` | 剪贴板读取策略；保存成功才递增进程内 revision |
| `native_global_consent_quarantine_v1_<uuid>_record` / `_value` | 字符串 | — | 显式恢复 | 保留损坏原件（value 用 `utf8-base64:`），不自动恢复旧授权 |

`DeviceGrants` 通过 `HttpShellService.screenPolicyChanged/smsPolicyChanged/clipboardPolicyChanged/globalConsentChanged` 围栏旧请求；`revision` 是单调栅栏，不是开关次数。授权偏好不进入 Android 备份：`res/xml/backup_rules.xml` 只包含 `dsha-portable-settings.xml`。

### 6.2 `deepseekharness`（`Constants.PREFS`）与相关字段

| 键 | 类型 | 默认 | 读写 | 说明 |
|---|---|---|---|---|
| `allow_root_shell` | bool | false | `ConfigStore` | Root 通道总开关；关闭撤销虚拟屏 Root 请求 |
| `root_policy_mode` | 字符串 strict/relaxed/full | strict | `ConfigStore` | 仅 root 通道；Shizuku/ADB 仍严格 |
| `adb_enabled` | bool | false | `DeviceChannelsFragment` / `DeviceBridgeService` | ADB 通道开关；关闭后保留配对密钥 |
| `cap_location` | bool | false | `DeviceGrantsFragment` / `DeviceSense` | 位置能力；关闭撤销域请求 |
| `cap_sensors` | bool | false | 同上 | 传感器/手电能力 |
| `overlay_stream` | bool | false | `OverlayController` | 悬浮条使用开关（非系统权限） |
| `lan_mode` / `lan_token_v2` | bool / 字符串 | false / — | `ConfigStore`、`LanAuth` | 局域网代理；token fail-closed |
| `local_network_permission_asked` | bool | false | `MainActivity` | 局域网权限只自动询问一次 |
| `permission_mode` | 字符串 | `danger-full-access` | `PermissionMode` | DSH 运行权限模式；build191 起固定，不是 Android 权限 |
| `confirm_shell` | bool | — | `ConfigStore` | 历史命令确认字段，仍保留键名 |

### 6.3 `dsha-onboarding`（`PermissionPanel.PREFS`）

`goals`（用途集合）、`step`（引导步骤）、`notify_requests`、`notify_denials`。只驱动引导推荐与通知「已拒绝」文案，不保存任何系统授权。用途→卡片映射在 `util/PermissionGoals`：FILES→storage；SCREEN→a11y/overlay/notify；SHELL→channel；BACKGROUND→battery/notify。

### 6.4 SAF 授权账本

- 新账本：`saf_capability_ledger`（`SafLedgerStore.NAMESPACE`），按树保存 URI、可写位、新增位、应用标签、pending 状态；旧 `saf_grants` 一次性只读迁移。
- 授权入口：系统 `ACTION_OPEN_DOCUMENT_TREE`；只给写位 → `WRITE_ONLY`/`WRITE_ONLY_PENDING` 拒绝；撤销只释放本功能新拿到的读/写位。
- 桥端点 `/app/saf/*` 只接受账本短 id，不接受直接 URI/authority；`writable=false` 时写/删返回 `READ_ONLY`。

### 6.5 单次动作与运行绑定字段

| 字段/类 | 内容 | 失效条件 |
|---|---|---|
| `CapabilityRequest` | `requestId`、`capability`、`action`、`runtime{ownerId,instanceId,generation}`、`installInstanceId`、`bridgeRunId`、`target(TargetEvidence)`、`policyRevision`、`authorityEpoch`、`deadlineElapsedRealtimeMillis` | 截止、epoch 变化、运行/安装身份不符、取消 |
| `CapabilityBroker` | 单确认槽；`authorized`/`claimed`/`canceled` 弱引用集合；`DispatchPermit` 一次消费 | `revoke`/`revokeAll` 递增 epoch；迟到 allow 不能复活 |
| `ScreenSessionGrant` | paused、runtime Epoch、bridgeRunId、policyRevision、authorityEpoch、generation、revision | 暂停/停止/断连/策略或代次变化 |
| `TurnCapabilityGrants` | turn + capability → policyRevision，最多 32 条 | 本轮结束、策略 revision 变化 |
| `TurnScreenGrants` | turn + 包名 → policyRevision；敏感应用每 5 次重新确认，最多 64 条 | 本轮结束、暂停/停止、revision 变化 |
| `MicrophoneRequestGate` | 单槽 ticket、cancelled | 页面 stop/cancel、新请求占槽 |
| `AccessibilityRepairState` | repairEpoch、phase/code、attempt、32 条诊断事件 | 进程重启即失；不持久化 |

### 6.6 屏幕/敏感界面策略

- `ScreenConfirmationPolicy.Mode`：MANUAL / CUSTOM / ALLOW_ALL；`Rule`：ASK / ALLOW；revision 单调。
- `SensitiveScreenPolicy.Category`：PASSWORD / OTP / PAYMENT / SYSTEM_SECURITY / SECURE_UNKNOWN；`Mode`：OFF / ASK / HANDOFF；`Decision`：ALLOW / ASK / HANDOFF；信任/交接名单互斥、各 ≤64。
- 全局确认 ON 只把普通 ASK 投影为 ALLOW；不覆盖显式 DENY、不跳过敏感类别 ASK/HANDOFF、不解除密码文本硬阻断。

## 7. 主要流程

### 7.1 首次引导

`OnboardingActivity`：WELCOME(0) → GOALS(1) → PERMS(2) → FINISH(3)；步骤/用途存 `onSaveInstanceState` 与 `dsha-onboarding`。第 3 步 `PermissionPanel` 构建 6 张展示卡片（存储、设备通道、无障碍、通知、悬浮窗、电池优化；Root 在状态数组里但展示层去掉），推荐项按用途排序，其余收进「更多授权」。面板 1 秒轮询 + `onResume` 重读；无障碍 1.5 秒防抖；全部只读，外跳只由点击触发。

### 7.2 设置页

`DeviceGrantsFragment` 首页：全局确认（`FullPermissionControls`）→ 设备执行通道入口（`DeviceChannelsFragment`）→ 屏幕操作入口（`ScreenOperationFragment`）→ 系统权限（所有文件访问、跨应用文件访问、电池优化）→ 独立敏感能力（短信、特权执行、剪贴板、位置、传感器）。`onResume` 重读，1 秒轮询入口摘要；`onPause` 停轮询。

### 7.3 单权限典型流程

1. 用户点卡片/按钮 → 只读状态判定（`checkSelfPermission` / 特殊访问判据 / 通道 probe）。
2. 未满足 → 打开系统页或发起运行时请求；失败按代码回退（应用详情/总列表/toast）。
3. 返回 `onResume` 或结果回调 → 重新判定；**不把「打开过设置」或「弹过框」写成已授权**。
4. 能力使用点再判一次（例如 `DeviceSense.location`、`OverlayController.permitted`、`LocalNetworkAccess.granted`、`StorageAccess.granted`、`ShizukuShell.hasPermission`）。
5. 失败保留现状；不自动重试、不换通道、不用 Root/ADB 绕过系统拒绝。

## 8. 现状缺口与风险（按影响排序）

1. **通知没有设置页入口**：设备能力授权页没有通知行；通知只在引导、更新下载、助手拒绝路径出现。后续模块需统一。
2. **电池/后台只有两个 AOSP 可读事实**：`isIgnoringBatteryOptimizations` 与 `isBackgroundRestricted`；厂商「自启动/允许后台运行/后台弹出界面」无法读取，当前也未登记任何厂商设置 Intent。
3. **Android 13+ 受限设置没有独立状态字段**：只在文案里提示；无法区分「服务被系统禁用」与「用户未开」；UNKNOWN 三态只在无障碍有。
4. **特殊访问回退不一致**：`PermissionActions`/`OverlayFragment`/`ConfigFragment`/`AssistantActivity`/`VirtualScreenOverlayController` 各自写 `ACTION_MANAGE_OVERLAY_PERMISSION` 与回退；`VirtualScreenOverlayController` 该处无 try/catch 且 `show()` 生产路径未见调用方。
5. **`WRITE_SECURE_SETTINGS` 无撤销 UI**：只能由系统/ADB 侧收回；源码未见主动撤销。
6. **安装未知应用的后台路径需要重新请求**：`/app/install` 发通知打开设置，返回后不自动重试（正确），但没有统一的「返回后继续」状态机。
7. **厂商私有管理未建模**：没有 `Build.MANUFACTURER/BRAND`/系统属性检测，也没有厂商设置页 Intent 候选表；后续模块需要新增纯逻辑 + 真机证据。
8. **请求身份分散**：裸请求码 104/195/37/501 与动态字符串并存；新增能力若继续复用裸码，容易冲突。
9. **历史分支与注释漂移**：`READ/WRITE_EXTERNAL_STORAGE` 分支在 minSdk 30 不可达；`config_battery_opt_hint` 被隐藏；`AssistantService.notificationPermissionIntent` 源码未见调用方（历史文档已指出，仍需复核）。
10. **权限成功≠能力可用**：无障碍常见「已开启未连接」；Shizuku 常见「已授权未绑定」；ADB 常见「已配对未鉴权」；通知渠道可被单独关闭。
11. **厂商横向门禁未建模**：Android 13+ 受限设置、vivo Android 15+ 侧载敏感权限二次门禁、三星 One UI 6+ Auto Blocker、Android 17 AAPM 都会在 AOSP 权限之外增加前置条件。详见同目录 [vendors.md](vendors.md)；本清单的现有实现均未读取/识别这些状态。

## 9. 测试与核对入口

| 范围 | 现有测试/入口 |
|---|---|
| 用途→卡片映射 | `app/src/test/java/com/deepseekharness/app/util/PermissionGoalsTest.java` |
| 电池/后台状态 | `util/BatteryBackgroundStateTest.java` |
| 授权概览 | `util/GrantSummaryTest.java` |
| 全局确认编码/损坏 | `core/DeviceGrantsTest.java`、`core/DeviceGrantsPreferenceRecoveryTest.java`、`core/NativeConsentProjectionTest.java`、`util/NativeConsentPolicyTest.java` |
| 屏幕确认/敏感界面 | `core/DeviceGrantsSensitiveScreenTest.java`、`util/ScreenConfirmationPolicyTest.java`、`util/ScreenSessionGrantTest.java` |
| 无障碍三态/修复 | `util/AccessibilityEnableListTest.java`、`util/AccessibilityRepairStateTest.java`、`util/AccessibilityServiceLifecycleEventsTest.java` |
| Shizuku 状态机 | `util/ShizukuChannelStatusTest.java`、`util/ShizukuManagersTest.java` |
| 麦克风 | `util/MicrophonePolicyTest.java`、`util/MicrophoneRequestGate` 相关（若存在） |
| Broker/一次性 dispatch | `capabilities/CapabilityBrokerTest.java` |
| 布局/文案 | `LayoutAuditInstrumentation`（style 中英文、日夜、短屏、1.3 倍字体）；`docs/releases/` 真机回执 |

修改任何权限卡片时至少同步：Manifest、请求入口、状态判据、失败文案、`PermissionGoals`/`PermissionCards`、`DeviceGrantsFragment`（若新增设置页入口）、单测与 `documentation-index.md`。系统弹窗、厂商页与实际授权仍须独立真机证据。
