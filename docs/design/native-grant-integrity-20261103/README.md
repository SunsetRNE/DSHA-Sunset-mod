# 原生授权状态完整性设计（T44）

状态：**设计稿，未实现**。基线 build304（`006f3311`），本稿只做源码与既有设计稿盘点；未操作手机、未修改正式数据、未执行 `su`，也未改生产代码。实现必须先经用户确认。

来源范围：源码、`AGENTS.md`，以及 T42 设计稿 `docs/design/adb-native-20261103/README.md` 的 E1–E4（只读取证）。不把本稿写成“安全隔离”：容器、插件、终端与 App 共享 Android UID，白名单和原生入口不是内核沙箱。

## 交接说明（给接手实现的人）

- **本稿状态**：设计阶段由主会话整理收口，可直接交给其他人继续。§1 的存储位置与 §10 列出的类名已在 build304 源码中逐一核对存在；§1 的“同 UID 可写”判断沿用 T42 E2 的同类证据，**没有**在本稿阶段对这些具体键/文件做写入验证。
- **先做什么**：
  1. 请用户回答 §6 的 Q1–Q7（至少 Q1、Q2、Q3、Q7，它们决定实现边界和是否需要真机取证）。
  2. 按用户批准范围，在 `.test` 测试包上补 §7 的 E5（Keystore 能否被同 UID 调用）——这一项决定方案 A 的价值上限，建议最先做。
  3. 从 §5.1 的 P0 开始：只加 `util/NativeGrantIntegrity` 纯逻辑与单测，不改任何入口行为。
- **相关设计稿**：T39 [识屏守卫](../screen-guard-20261103/README.md)、T40 [设备命令确认](../device-exec-confirm-20261103/README.md)（T43 剪贴板/外跳方案在其 §4.4，T43 尚无独立设计稿）、T42 [ADB 原生化](../adb-native-20261103/README.md)。
- **约束**：实现前需用户确认本稿；只读取证、不在正式数据上试写、不执行 `su`；L2/L3 改动与 T39/T40/T42/T43 的实现批次协调，避免两边各自加一套状态码。

## 0. 结论摘要

- **G1**：当前若干授权意图落在 `SharedPreferences` 或普通私有文件中；同 UID 代码有机会读写这些位置。格式校验、修订号、进程内缓存和 `commit` 回读只能发现部分损坏，不能证明写入者是用户，也不能阻止同 UID 代码写入一个合法新值。
- **G2**：Android 系统授权（无障碍、悬浮窗、运行时权限、Shizuku/Root 管理器授权等）由系统或外部管理器维护；DSHA 自己的开关只是附加门槛。两者不能互相替代，也不能假定容器改不到系统或 Binder 状态。
- **G3**：Keystore 的不可导出密钥适合保护密文、签名或 MAC 密钥材料，能提高“读走文件”的成本；但同 UID 代码理论上可通过 Android Keystore 服务请求使用该密钥。必须用 `.test` 包验证调用方鉴别、进程边界、用户解锁和 StrongBox 行为，不能从“不可导出”推出“同 UID 不能使用”。
- **G4**：推荐组合是：高风险能力使用短时内存授权票据和原生界面实时确认；持久状态用 Keystore MAC 做篡改检测；检测到缺失、回滚、MAC 不匹配或系统授权变化时 fail-closed、撤销内存票据并要求用户在原生页重新确认。该组合降低绕过面，但不能约束同 UID 任意代码直接调用系统服务或执行已获管理器授权的通道。

## 1. 事实：当前授权存储与判定

下表列出“DSHA 自己保存的授权意图/确认状态”，以及读取判定点。表中“同 UID 可写”表示根据 T42 E2 的同类证据，不能把该目录视作容器不可达；不是本轮在正式数据上做写入试验。

| 编号 | 存储与键/文件（描述性） | 读取/判定点 | 当前语义 | 同 UID 可写判断 |
|---|---|---|---|---|
| F1 | `dsha_device_grants`：短信读取布尔值、全局原生同意记录、识屏策略记录 | `core/DeviceGrants` 的 `smsReadConfigured`、`consentSnapshot`、`screenPolicy`；桥服务和 ScreenGuard/DeviceGuard 设计调用这些快照 | 默认关闭；全局记录含严格编码与 revision；识屏损坏回落人工确认 | **是，待实现验证**。T42 E2 已证同身份可访问同类 `shared_prefs`、`no_backup`、`files` 私有目录并显示可写 |
| F2 | 通用 `deepseekharness` 偏好：Root shell 开关、Root 拦截档位、命令确认、位置/传感器开关（后两者在同名偏好中） | `ConfigStore`、`RootShell.enabled`、`DeviceSense.locationAllowed/sensorsAllowed`、`HttpShellService` | 缺失/非法通常回安全默认；合法值直接影响入口 | **是，待实现验证** |
| F3 | 通用偏好中的识屏模式与最近降级原因 | `screen/ScreenMode.current`、`bridgeRejection`、`capabilityRevoked` | `off/physical/virtual` 互斥；不在可迁移设置白名单 | **是，待实现验证** |
| F4 | 通用偏好中的 LAN 开关、LAN 口令 verifier、LAN token；运行中的 Web cookie 仅在内存 | `ConfigStore.isLanMode/hasLanPassword`、`LanProxyService`、`LanAuth` | 开关与口令由原生配置；cookie 绑定 Web generation；token 生成后持久保存并有内存 revoke | 偏好/token **是，待实现验证**；内存 cookie 受进程生命周期保护，但同 UID 代码可在同进程调用公开入口或读进程可达状态 |
| F5 | 外接设备授权偏好：enabled、目标 serial/product/fingerprint、epoch | `extdev/ExternalDeviceHost.enabled/binding/forgetTarget` | 关闭时回收导入中转区；目标切换递增 epoch | **是，待实现验证** |
| F6 | 普通文件：桥 token、运行态 journal、虚拟屏会话 journal、导入中转区及其它私有运行记录 | `HttpShellService` token 自检/门控、`VirtualScreenSessionJournal`、运行与外接设备组件 | 各自有代次、摘要、NOFOLLOW、原子写或加密；部分不进入备份 | 文件本身不能当授权根；同 UID 可写判断遵循 T42 E2，具体文件只列待取证，不在正式数据上试写 |
| F7 | Keystore：`KeyVault` 的 API key 与 `StoredWebSession` 的独立密钥别名；未来拟新增能力状态 MAC 别名 | `data/KeyVault`、`runtime/StoredWebSession` | AES/GCM 密文；读取失败不生成替代密钥 | 密钥不可导出不等于同 UID 不可调用；必须取证 E5，且 MAC 只检测文件一致性 |
| F8 | 系统/外部授权：无障碍连接与 enabled、悬浮窗、Android `RECORD_AUDIO`、定位/传感器系统权限、Shizuku 管理器与 Root 管理器授权、无线调试 | `ScreenMode.accessibility`、`OverlayController.permitted`、`BrowserMicrophone`、`DeviceSense.granted`、各通道探活/身份检查 | 平台或外部组件的事实与 DSHA 偏好共同决定能否执行 | 不能由 DSHA 偏好代表；同 UID 代码可能直接使用其已获 UID 能力，具体系统行为待真机取证 |

非目标：API Key、Web session 等机密加密存储不是授权状态；本稿只要求它们在授权审计中被标成“机密/运行凭据”，不把加密成功当作授权完整性证明。

## 2. 事实：现有校验能做什么

- `DeviceGrants` 对全局记录有严格解码、revision 单调观察、读写期间 generation、commit 后同进程 readback，并在 malformed、回滚、读取失败时进入不可用/需重新确认状态。这能降低并发和偶发损坏风险。
- 这些检查读取的仍是同一 UID 可写位置；攻击代码可以写入格式正确、revision 更高的合法记录，或在进程未观察前改写初始值。因此它们不解决“写入者不可证明”。
- ScreenMode 的互斥、DeviceSense 的缺省关闭、RootShell 的严格回落、CapabilityBroker 的 epoch/一次性 claim、Screen/Device Guard 的“重新采样”都是有价值的运行时围栏，应保留；它们是状态机约束，不是存储隔离。
- T42 E1–E4 已证明：在用户真机的 proroot 现场，容器进程仍为 App UID；绕过运行时路径翻译的只读系统调用可打开 App 私有 `files/shared_prefs/no_backup`，并显示后两者可写；`/dev/binder` 与宿主 `app_process64` 也可达。E2 只读、未写文件；E3 未执行 `su`；E4 未加载类。结论限于该设备/引擎/取证范围。

## 3. 影响

### 3.1 影响范围分级

| 等级 | 能力 | 主要后果 | 设计优先级 |
|---|---|---|---|
| L0 | UI 偏好、任务上岛、显示项 | 行为偏好被篡改，通常不直接取得设备数据 | 记录篡改，按现状默认值处理 |
| L1 | LAN 开关/口令、位置、传感器、剪贴板（T43） | 暴露网络入口或个人环境数据 | MAC 检测 + 实时能力检查 + 撤销内存租约 |
| L2 | 识屏、截图、无障碍输入、短信、外跳确认（T39/T43） | 暴露屏幕/短信或产生用户可见副作用 | 每次关键操作重采样；高风险动作只发一次性原生票据 |
| L3 | Root、Shizuku、ADB、外接设备高风险操作 | 取得高权限执行面或控制外部目标；同 UID 代码可能直接走系统/管理器能力 | 优先原生化、短时票据、通道 epoch；不宣称能拦截任意同 UID 代码 |

### 3.2 不能承诺的边界

- MAC 不能证明“用户刚刚点击了设置页”：拥有同 UID 执行能力的代码可能请求 Keystore 使用 MAC 密钥，或直接调用原生 API/管理器 Binder。
- 仅把状态移到 `no_backup`、改文件权限、加密文件、改名或绑定路径，不能消除同 UID 写入者问题。
- 仅把逻辑移到另一个 App 进程也不能自动形成不同 UID；若仍同 UID，必须验证 Binder 调用方鉴别和操作令牌。
- “关闭能力”若只改偏好且保留通道/密钥/管理器授权，不能写成通道已从系统撤销。T42 已决定 ADB 关闭保留密钥、由原生拒用；该语义必须与本稿的完整性状态一起呈现。

## 4. 方案比较

### 4.1 A：Keystore MAC 绑定持久状态（推荐的加固层）

**做法**：新增能力专用 MAC 密钥别名（优先硬件/StrongBox，平台不支持时记录实际回退）；对规范化的能力名、版本、值、revision、安装身份和包签名摘要做 MAC。MAC 与状态分开写入，原生读取时先验证类型/版本/单调 revision/MAC；失败则进入 `INTEGRITY_UNKNOWN`，清空内存授权，不能自动按旧值放行。密钥轮换、卸载、系统恢复和签名变化都要显式定义。

**能防**：普通文件被离线改写、旧值回滚（结合内存已见 revision/安装代次）、误把格式正确的伪造值当成可信值；也能发现文件删除、替换和跨安装复制。

**不能防**：同 UID 代码直接用 Keystore 做 MAC；同 UID 代码在原生进程/同进程内调用授权入口；已获 Root/Shizuku/WRITE_SECURE_SETTINGS 的代码；用户自己在设置页选择新的授权。MAC 是检测器，不是调用方鉴别。

### 4.2 B：只信进程内状态与原生实时确认（高风险必选层）

**做法**：持久偏好只作“用户上次选择”的显示线索；高风险执行需要由原生 UI 产生一次性、短 TTL、绑定 bridge generation/通道/命令摘要/任务身份的票据，票据只存在内存。每次执行前重新采样系统权限、通道状态、Guard 规则和完整性状态；暂停、进程重启、能力变化或任务结束即废票。

**能防**：容器直接改写持久开关后继续沿用旧内存授权；旧票据重放；确认后命令/通道被替换；大部分“随包入口”绕过确认。

**不能防**：同 UID 代码自行构造原生调用、读取同进程内存、直接使用系统服务或管理器 Binder；进程被完全控制时不能靠 Java 内存宣称可信。

### 4.3 C：被改写即失效、隔离原值并要求用户重建

**做法**：MAC/类型/revision/系统授权快照不一致时进入 `RECONFIRM_REQUIRED`；保留原记录到受限诊断/隔离记录（不作为授权），清空或暂停能力租约；设置页显示“状态无法证明”，用户在原生界面重新选择。不可静默恢复旧开关。

**能防**：损坏、回滚、删除、跨版本结构变更、写入失败后的误放行。

**不能防**：攻击代码在用户重新确认前后持续运行并伪造同 UID 操作；不能替代系统撤销和 Root/ADB 密钥迁移。

### 4.4 D：按风险分级与最小持久化（推荐产品策略）

- L0 继续用现有偏好，但异常只影响显示/回到默认值，不增加高成本确认。
- L1 使用 MAC + 系统实际权限 + 能力开关；LAN 口令 verifier/token 与授权开关分开处理，运行 cookie 只在内存且按 Web generation 失效。
- L2 使用 MAC + 每次操作前重采样；短信/剪贴板按 T40/T43 三态，ASK 不提供本轮豁免；识屏沿 T39 的 ScreenGuard，读/写/目标复核不合并。
- L3 优先 T42 原生 ADB、T40 DeviceGuard、Root/Shizuku 的原生执行器和短时票据；关闭开关应立即撤销 App 自己持有的连接/票据，但不声称撤销外部管理器已授予 UID 的能力。

## 5. 后续设计落点

### 5.1 统一状态接口（实现阶段）

新增 `util/NativeGrantIntegrity` 纯逻辑模型（不 import Android）：

- `Record(capability, schema, revision, value, mac, installationEpoch)`；值使用明确枚举，拒绝任意 JSON/字符串即兴解释。
- `Verdict`: `ON/OFF/UNCONFIGURED/RECONFIRM_REQUIRED/INTEGRITY_UNKNOWN/READ_UNAVAILABLE`；所有异常 fail-closed。
- `evaluate` 覆盖缺失、类型错误、MAC 错、旧 revision、安装代次变化、系统事实不匹配、进程 generation 变化。
- Android 适配层只负责 Keystore、SharedPreferences/文件原子写与系统权限采样；每次写后重新读取并验证，但把 readback 明确标为“缓存一致”而非“磁盘可信”。

实现拆分建议：

1. P0：`NativeGrantIntegrity`、规范编码、revision/epoch、状态迁移与单测；不改入口行为。
2. P1：`DeviceGrants`、`ScreenMode`、Root/位置/传感器/LAN/外接设备适配，统一 `reconfirm` 和内存租约；新增诊断但不自动放行。
3. P2：L2/L3 入口接入实时采样和一次性票据；与 T39/T40/T42/T43 各自设计确认后分批实现。
4. P3：清理旧路径、备份/恢复审计、升级迁移和文档；旧记录不能直接变成新 MAC 记录，须用户在原生页确认或明确迁移策略。

### 5.2 对 T39–T43 的影响

| 任务 | 影响与约定 |
|---|---|
| T39 识屏守卫 | `ScreenMode`/`DeviceGrants` 的完整性状态是 G2/G7 输入；`RECONFIRM_REQUIRED` 只能返回 `SCREEN_GRANT_RECONFIRM_REQUIRED`，不能以默认 ON 继续。T39 的本轮记忆仍只在内存，Core/任务/代次变化即失效；不把 MAC 当作屏幕确认。 |
| T40 设备命令确认 | DeviceGuard 在 D0–D4 前加入完整性状态采样；异常时 `DEVICE_GRANT_INTEGRITY_UNKNOWN`，不弹窗代替修复。确认票据绑定完整性 epoch、通道和命令摘要；Root shell 的强确认与拒绝优先级不变。 |
| T42 ADB 原生化 | K1/K2 选择仍按 T42：优先 Keystore 不可导出密钥，E7/E8 决定平台 TLS 可行性；T44 要求 ADB 开关、配对状态、连接 epoch 与密钥别名状态分开建模。Keystore 使用能力必须做 E5，不能写成同 UID 不可使用。旧容器密钥不导入、备份排除规则保持。 |
| T43 剪贴板/外跳 | 三态偏好需带 MAC/epoch；读取剪贴板和外跳确认在发送前重采样。ASK 只用内存一次性确认，不能由被改写的预授权值变成预授权；`/app/open` 的 scheme 白名单仍先于确认。 |

### 5.3 诊断与用户体验

- 诊断只记录能力名、状态码、revision/epoch 和是否需要重确认，不记录 MAC、密钥别名内容、口令、短信、屏幕图或原始路径。
- 设置页区分“已关闭”“未配置”“状态无法证明/需要重新确认”“系统权限未授予”；不要显示为一个含糊的关闭。
- 检测到异常后停止相关运行租约、关闭 ADB/虚拟屏/传感器监听等 App 自持资源；不自动执行系统撤销、不删除用户数据、不把异常原值恢复成 ON。
- 复原入口只能由原生设置页触发：显示受影响能力和原因，用户确认后写新 revision/MAC；不能从容器、备份、网页或插件恢复授权。

## 6. 用户需要决定的问题

- **Q1**：是否接受“Keystore MAC 只做篡改检测，不解决同 UID 代码使用密钥”这一边界，并把 E5 作为实现前置取证？建议接受。
- **Q2**：L3（ADB/Root/Shizuku/外接高风险）是否一律要求短时内存票据，即使用户选择预授权？建议是；预授权只减少 UI 询问，不取消执行前重采样。
- **Q3**：状态异常时是否统一要求用户在原生设置页重新确认，而不提供“沿用上次值”按钮？建议是。
- **Q4**：是否允许同 UID 代码在 App 已获 Root/Shizuku/WRITE_SECURE_SETTINGS 时被视为不可防范围，并在安全模型中持续明示？建议是。
- **Q5**：是否按 L0–L3 分批落地，先做 L2/L3，再补 L1/L0 的 MAC 迁移？建议是，避免先改大量低风险偏好阻塞授权主链。
- **Q6**：是否把 LAN 开关/口令 verifier/token 与高风险授权一起迁移，还是保持 LAN 作为 L1 独立批次？建议独立批次，先保证异常时 listener 关闭。
- **Q7**：E5–E8 真机取证是否仅在用户明确安排的 `.test` 包执行？本稿没有执行；建议仅在测试包做，不在正式数据上试写。

## 7. 待真机取证

以下项目未在本阶段执行，不能写成已验证：

- **E5**：测试包中验证同 UID `app_process`/Binder 路径能否请求 Android Keystore 使用 DSHA 能力 MAC/签名密钥；记录调用方鉴别、进程边界、用户锁定状态和失败码。不得导出密钥。
- **E6**：分别在 proot 与 proroot 的测试包环境确认绕过运行时路径翻译访问自身私有目录的范围，仅做目录/属性和只读打开后关闭；不得写入、删除或覆盖真实授权记录。
- **E7/E8**：按 T42 设计稿验证 AndroidKeyStore TLS 私钥和平台 TLS/导出密钥材料能力；不把失败自动解释成平台隔离成立。
- **E9**：仅在测试包临时偏好副本或内存 fake 上验证合法伪造、回滚、删除、MAC 错、进程重启和系统授权变化的状态机；不得改正式 `shared_prefs`、`no_backup` 或用户导出。
- **E10**：验证 Root/Shizuku/无线调试管理器对同 UID 代码的实际授权范围；不执行 `su`，除非用户另行明确授权；失败或未知都按不可防范围记录。
- **E11**：验证系统无障碍、悬浮窗、运行时定位/录音权限和 `WRITE_SECURE_SETTINGS` 的实际撤销/重启行为；只读查看授权状态，避免把 Android 版本差异写成普遍结论。

## 8. 测试方案（用户确认后）

纯逻辑必须放 `app/src/main/java/.../util/` 并配 `app/src/test/.../util/` 单测，至少覆盖：

- 规范编码、未知字段、类型错误、缺失/MAC 错、revision 回滚/耗尽、安装 epoch 变化；
- `ON/OFF/UNCONFIGURED/RECONFIRM_REQUIRED/INTEGRITY_UNKNOWN` 与系统事实组合，所有异常 fail-closed；
- 读写并发、generation 变化、撤销后旧票据不可用；
- L2/L3 票据绑定能力、通道、命令摘要、任务身份和有效期，UNKNOWN 不重放、不换通道；
- 旧无 MAC 记录只能迁移到“待用户确认”，不能隐式升级为 ON；备份/恢复不生成本机授权；
- 兼容 T39/T40/T42/T43 的错误码、`nextAction` 和 `userAction` 不重复、不冲突。

Android 适配单测/集成测试只验证 KeyStore/SharedPreferences/系统事实适配，不把 Robolectric 模拟的 Keystore 当成真机证明。真机验收单独记录设备型号、Android API、引擎、授权管理器、成功/失败/未知和未覆盖范围。

## 9. 后续

- 用户先回答 Q1–Q7，再由主会话决定是否拆出实现任务；本设计阶段不修改 `app/`。
- 实现前补齐 E5–E11 中用户批准的取证；若发现同 UID 代码可直接取得等价调用能力，更新安全模型的“能防/不能防”表，不回避结论。
- 实现完成后同步 `docs/security-model.md`、权限架构、T39/T40/T42 设计稿（T43 方案位于 T40 §4.4）与总索引，并跑 `python3 tools/check-document-links.py`、纯逻辑单测和针对性 Lint。

## 10. 取证引用

- T42 设计稿 E1–E4：同 UID、绕过运行时路径翻译、`/dev/binder`、`app_process64` 的只读结论；E5–E8 仍待做。
- 本稿源码盘点：`core/DeviceGrants`、`core/ConfigStore`、`screen/ScreenMode`、`DeviceSense`、`DeviceBridgeService`、`LanProxyService`、`core/LanAuthBridge`、`extdev/ExternalDeviceHost`、`data/KeyVault`、`runtime/StoredWebSession`、`capabilities/CapabilityBroker`、`util/BridgeConfirmations`、`ui/BrowserMicrophone`。
