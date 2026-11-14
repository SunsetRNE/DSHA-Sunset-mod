# A7 任务基线：设备能力与识屏

状态：**第一阶段待 Lead 审批；本提交只有文档，不代表 A7 已实现或已验收**。日期：2026-11-14。
Owner：`@a7-device`，会话 `session-8a45a1db-4095-40cd-b045-8e7fbecbf2db`；分支 `feat/a7-device`。
源码取证基线：`d9b75bc52c7b36cad265dbe2b32a90ecb9971d74`（创建工作树时的 `origin/main`）。调查期间 main 的 RC1/A4 合入继续推进；本稿行号仍绑定 d9，实施前按 Lead 窗口更新基线、重核接缝，不把新 main 字段贴到旧证据上。

依据：[路线图 §2.1、A7、§7][roadmap]、[ADR-0004 决定四/七][adr4]、[A7 Root/ADB 设计核查][check]、[权限架构][permissions]、[虚拟屏架构][vscreen-doc]、[ADR-0003][adr3]、[安全模型][security]、[外接设备命令参考][external-doc]。用户给出的外接文档路径少一层 `design/`，此处链接指向仓库实际文件。

## 1. 目标、非目标与门禁

目标是完成 A03/A04/A05/F07 的一个设备能力闭环：三通道的许可、可用性、实际身份和执行结果分别呈现；实体屏与虚拟屏共享能力名称及失败语义，各自保有凭据与 owner；Root 模块管理先交付有界只读清单与管理器证据。

- 本阶段：源码调查、实施写域、失败域、权限/凭据矩阵、验证与回退方案；仅新增本文件。**收到 Lead 对本基线及写域的明确批准后才改产品源码**，报告 ready 不是实施批准。
- 第二阶段：按 §6 顺序逐片实现与提交，保留现有公开桥接口、偏好键名和拒绝语义；新代码使用真实 owner 的快照，不另造可授权的全局布尔缓存。
- 不新增 Manifest 权限，不自动索取 su/Shizuku/ADB/无障碍/截屏/悬浮窗，不开启任何能力或扩大文件、短信、目标应用范围。
- 不包含 T42 原生 ADB、密钥迁移、TLS/SPAKE2、重配对、协议升号；不包含 T40 全套命令确认。两者仍为设计稿，前置关系及决策单列，不能借 A7 默认开工。
- 不包含 Root 模块安装、更新、禁用、启用、删除、刷写、重启及执行模块脚本；不修改现有 Root 三档或声称所有既有 Root 写操作已关闭。
- 不进行 Gradle 模块搬迁、整包 Kotlin 翻译、Rust 设备执行、PTY/终端/运行时/备份重构；`:device` 是 A8 编译落点，当前仍在 `:app` 中按职责切片。
- 不 push、不合并、不打 tag、不发 Release、不安装、不操作手机。真机项目仅列待验，不沿用历史 APK 回执。

## 2. 源码事实与设计核查结论落地清单

下表承接[设计核查 R-1–R-8、A-1–A-9、X1–X7][check]，行号按当前任务源码重新核对；“拟做”均未实现。

| 核查项 | d9 源码事实与证据 | A7 处理及出口 |
|---|---|---|
| R-1 F07 缺设计 | [RootShellMain:96][root-main] 只分发既有执行类别；[ExternalInstallPlan:103][external-plan] 是外接目标探测/安装计划，不是本机 F07 | §5 补专用只读契约；S4 实现；写操作没有 F07 路由、计划或按钮 |
| R-2 授权 owner 分散 | Root 开关/档位在 [ConfigStore:280][config-root]；ADB 开关在 [DeviceBridgeService:112][adb-switch]；短信/确认在 [DeviceGrants:75][grants-sms]；识屏模式由 [ScreenMode:56][screen-mode] 持有 | S1 先设 M05 授权快照/唯一变更门面，保留旧存储键，M06 只消费；不把门面误写成存储已迁移或同 UID 隔离 |
| R-3 重复档位判定 | [HttpShellService:1202][http-plan] 和 [RootShell:102][root-exec] 都判档 | S1 提取共享纯判据，保持 `HostDeviceCommandGuard` 在三档之前的外层硬拒与执行侧再核验，做开关/档位/类别组合差分；FULL 也不能越过外层硬拒，不能删掉执行侧守卫 |
| R-4 / R-8 ADB+su 身份 | [HttpShellService:1114][http-execute] 只返 ADB 计划；[adb-shell.py:318][adb-shell] 拼 `su -c`，本机 helper 则在 [RootShellMain:72][root-uid] 核 UID 0 | S1 先将“请求 root”与“已证 UID”分开；ADB+su 证据不足必须 unknown/unverified，不伪报 uid0。若补发送前身份检查，须在同一实际执行上下文内完成，失败不派发原命令，不另连通道；完整原生化归 T42 |
| R-5 同 UID 风险 | [安全模型:11][security] 与 [T42:43][t42-risk] 明示同 UID、裸系统调用、prefs/Keystore/su 边界 | 本轮不解决 R2/R5，不宣称关闭开关形成隔离；现有事实与后续 owner 问题继续保留 |
| R-6 递归 READ | [权限架构:212][permissions-sms] 明示子链接目标未逐项核验；35/38 项 dry fixture 不构成缺口闭环 | F07 不复用任意 `READ`、`grep -R`、shell 枚举或外接安装计划；通用 READ 修复仍按 ADR 单独任务，不宣称已补齐 |
| R-7 管理器识别 | [RootShell:60][root-discovery] 只验 su 候选 metadata，不识别管理器 | F07 分开返回发现、实际 UID、版本握手、目录布局能力；不拿 `present()` 当管理器已授权/全版本兼容 |
| A-1 / A-2 / A-3 T42 漂移 | [T42:3][t42] / [T40:3][t40] 仍未实现；[HttpShellService:1116][http-execute] 仍返 `state=adb`；描述符桥协议已为 3 | 单独登记设计更新/协议决策/前置关系，不删强制 ADB 参数、不改 wire 语义、不顺手升号 |
| A-4 / A-5 / A-6 | T42 E5–E8、SPAKE2、原生单连接/队列数值、旧 key 重新配对仍未完成；其 Q8 已定排在 T40 之后 | 全部 deferred；本稿不新增真机证据、不实现 `AdbChannelOwner`、不删除配对或私钥，不改变既定顺序 |
| A-7 WRITE_SECURE_SETTINGS | [AdbBridge:461][adb-keepalive] 配对/验证后仍自动调用 keepalive grant；设计核查要求默认不 grant 尚未落地 | 现状如实保留；改成用户可选应作为独立授权边界小修另行批准，A7 不新增或主动触发该授权流程 |
| A-8 / A-9 | 身份证据与资产变化分别属于 S1 和最终集成 | 实际身份未证不能报已获 Root；不改描述符/数据 schema 的版本；最终由 main 合并树重算身份 |
| X1 只选一次 | [DeviceChannelOrder:26、78][channel-order] 与 [HttpShellService:1094][http-execute] 已统一 Root 优先、API37 条件次序、usable→authorized 两档选择；[ScreenCapabilityGate:105][channel-usable] 的 Root usable 仅开关+su metadata，[DeviceBridgeService:471、573][adb-probe] 的 connected 可由 TCP 探活刷新 | 保持选择规则，补反例；T40 B6 的旧“顺序未统一”不能再次记成待修；usable/connected 不能当新鲜鉴权或实际 UID 证明 |
| X2 NOT_SENT / UNKNOWN | [DeviceTransportResult:95][transport] 只在旧门面解析文本；[CapabilityResult:46][cap-result] 的 alternate eligibility 不等于本轮可自动 fallback | 只在适配器边界投影，可能已发送、超时、缺尾标记保留 UNKNOWN；调用方不得切通道重放 |
| X3 / X7 双层守卫与短信 | [DeviceShellExecutor:25][executor] 严格复核；[SmsQuery:37、89][sms-query] 限 URI/选项并固定 Android 用户。Root 宿主在 [RootShell:112][root-exec] 核开关，helper 在 [ShellService:60][root-sms-helper] 只复核 query/user；ADB 在 [adb-shell.py:301][adb-shell] 重取原生授权计划 | 保持 Java 与 guest 两侧，先对拍再调整；T42 完成前不删 Python 守卫。Root 慢 su 启动后的实时撤销复核不能写成已具备；若补需独立回归。短信只走设备 shell，拒绝写 provider、跨用户、复合脚本与文件绕路 |
| X4 凭据排除已部分落地 | [credential-paths:27–44][credential-table] 已排除用户备份里的 `adbkeys`；key 仍为 guest 可见明文 `/root/.dsh/adbkeys`，宿主物理数据根由 [UserDataLayout:10、74][data-layout] 选择 legacy 或 stable 绑定 | 不按 T42 旧 §7a 重做备份；M02 保持“备份排除”与“明文仍存/本机维护保留”两条独立事实，不误称已移入 Keystore |
| X5 / X6 对外边界与维护 | 随包入口受控≠任意同 UID 代码受控；[managed-runtime-inputs:623、694、737][managed] 已纳管双屏与设备根包类 | 文案保留限制；实施改变 runtimeId，接受一次维护页；本 docs-only 提交不重算资产 |
| M08 editor 证据类型 | [VirtualScreenAccessibility:327–338][editor] 使用当前焦点的 window/hash 身份，每次重新查焦点；与 30 秒/一次性 node map 不同 | 不称 editor 已签发独立一次性 30 秒票；仍需每次请求授权/目标/焦点/密码复核，S3 明确类型与反例 |

另有两项必须避免的误记：

1. [DeviceShellPolicy:163][policy] 与 [RootShell:108][root-exec] 存在既有 Root `strict/relaxed/full` 三档；[AGENTS:103][agents] 的概括与此有口径冲突。以现有实现和[安全模型:49][security-root] 为事实，不借本轮扩大或取消档位。F07“写入口关闭”只约束新增模块管理能力。
2. [DeviceGrants:75][grants-sms] 只有布尔短信授权，[HttpShellService:1278][http-sms] 返回 `remembered`。T40 B2 中的“逐次询问”还没有原生实现；提示词不能当作安全保证。S1/S5 需同步准确状态与文案，不能把关闭解释成自动弹原生询问。

## 3. M06/M07/M08/M02 的状态与凭据归属

| 模块 | 唯一事实 owner / 拟定接缝 | 允许提供 | 不得持有或替代 |
|---|---|---|---|
| M05 授权接缝 | 既有 `DeviceGrants`/`CapabilityBroker`；拟增本机设备授权门面，委托旧存储 | Root/ADB 用户意图、档位、短信与 screen 策略的不可变快照；先围栏后发布 | 不执行 shell；M06 不自行写 prefs；不复制第二份持久授权 |
| M06 | `DeviceBridgeService` 保活 owner；`RootShell`/`ShizukuShell`/`AdbBridge` 各自运输事实；纯 `DeviceChannelOrder` 只选一次 | 通道枚举、发现/授权/连接/身份事实、类型化结果和有限诊断 | 不把 backendReady 当业务批准；不持有屏幕帧或把通道探活变为业务动作 |
| M07 | `DshaAccessibilityService`、实体连接 epoch、`ScreenRequestContext`、截图 ledger 与 node lease | display 0 的观察与独立动作凭据 | 不复用虚拟 Core 身份；不以 dump 给坐标签权；T130 实验授权仍默认关闭 |
| M08 | Application 所属 `VirtualScreenManager`；Core/连接/显示/观察 owner 各层保留 | 原 Core 生命周期；raw、semantic/editor、anchored 三条显式动作链 | 不把 displayId、status 或 Native preview 当 Agent 输入许可；不改投实体屏 |
| M02 | 现有机器凭据清单与相应 owner；本机 ADB key 暂仍由 guest 持有 | 凭据位置/可用性分类和排除断言；公开诊断只输出状态 | 不读出/导出 key、token、ticket、journal；不借 A7 建新 Keystore 别名或替换旧密钥 |

拟定能力枚举是描述性数据：`DEVICE_COMMAND`、`DEVICE_CHANNEL_STATUS`、`PHYSICAL_OBSERVE`、`PHYSICAL_ACTION`、`VIRTUAL_LIFECYCLE`、`VIRTUAL_OBSERVE`、`VIRTUAL_RAW`、`VIRTUAL_SEMANTIC`、`VIRTUAL_ANCHORED`、`ROOT_MODULES_READ`。它们不创建新系统权限或持久授权；适配器仍使用既有原生能力门，声明中出现某项不等于获准。A1 的 [ModuleDescriptor:9][module-desc] 已明确声明不提供权限，不能把 [ContractResult][contract-result] 冒充设备执行结果。

结果按 ADR-0004 的 `dispatched/executed/partial/effect/retry` 五字段组织内部快照，旧枚举/文本仅为兼容投影。现有 `CapabilityResult` 与 `DeviceTransportResult` 保留，新增投影须逐字段对拍；缺少事实用 UNKNOWN，不从输出文案/exit=0猜 effect=VERIFIED。`state=adb` 是交给 guest 的计划，不能投影成“设备已执行”。

### 3.1 双屏凭据隔离矩阵

| 凭据 | 来源与绑定 | 消费/失效 | 禁止替代 |
|---|---|---|---|
| 实体 pixel | 最近真正交付的 screenshot `observationId`；目标 display/window/package、owner、连接 epoch、尺寸/方向/像素与可用区域 | [ScreenObservationLedger:103、125][physical-pixel]：新截图覆盖；30 秒；先移除再验 | dump/tree/nodeId、虚拟 frameSeq、原生状态窗画面 |
| 实体 semantic | `treeId/nodeId`；目标/owner/连接 epoch、声明 supportedActions | [ScreenNodeLeaseStore:28、52][physical-node]：独立 30 秒与一次 node 消费；随后还需当前目标/语义核验 | screenshot 坐标、虚拟 node/editor；零面积 bounds 不是触点 |
| 虚拟 raw | 同一次 `see` 的 `coreInstance/generation/frameSeq`，确曾观察且当前最新；坐标按显示原尺寸换算 | [VirtualScreenPolicy:98][raw-policy]；输入消费观察；流式 touch 另验 stroke 与阶段 | status 最新 frameSeq、旧图换新 generation、任意主屏坐标 |
| 虚拟 semantic node | tree 返回的一次性 nodeId；原 Core/显示代次、window/package、无障碍连接与节点 refresh | [VirtualScreenAccessibility:270][virtual-node]：tree 30 秒，nodes.remove 后 refresh；[SemanticAdapter:24][semantic] 经显式请求 | 不要求像素静止；不能冒充 editorId；失败不自动 tap |
| 虚拟 editor | get 返回当前焦点的 editorId（window/hash）；每次请求重新取当前焦点并匹配身份 | [VirtualScreenAccessibility:327][editor]：当前焦点/可编辑/密码硬拒与 editorId 再核验；当前没有独立一次性 30 秒票，不把 node TTL 套在此处 | editorId 不是 nodeId、截图帧或授权许可；动作后重新观察；不凭旧焦点换目标 |
| 虚拟 anchored | 原 `see` 的 `coreInstance/generation/frameSeq/observationId`→唯一候选→`anchorId`；原图区域与节点/几何/遮挡证据 | [ObservedControl:272、303][anchored]：select 不点击/不续期；click 先消费；sourceFrameSeq 保持原值 | status/新图升级旧证据；选点丢回复、UNKNOWN 后重新选点或换 raw |
| 虚拟启动 ticket | 原命令、manager epoch、有效期和当前原生授权；[HttpShellService:1135][launch-ticket] plan/commit | 一次提交；只有有界完整本地证据和原票原子撤回才能证 NOT_SENT | 桥 token、长期业务许可、另一 Core 的启动凭据 |

M07 的 owner 包含安装、bridgeRun、DSH generation/instance、授权 epoch/revision 和任务身份（[ScreenRequestContext:83][physical-owner]）；M08 再叠 Core/Manager/显示身份。只共享字段语义，不统一这些 ID 或 TTL owner。每次输入后重新观察再决定；被拒、UNKNOWN、PARTIAL、取消、超时均不自动回放动作。

模式仍由 `ScreenMode` 唯一持有：`off/physical/virtual` 互斥；错误保留 `SCREEN_MODE_OFF`、`SCREEN_MODE_PHYSICAL_OFF`、`SCREEN_MODE_VIRTUAL_OFF`。切换先撤销旧屏请求；virtual 清理未决拒绝离开，不自动新建替代。操作者 Native 面板仍用自己的 RESUMED owner/lease，与助手授权分开。

### 3.2 最小权限与撤销矩阵

下表为 A7 必须保留/补证的准入边界，不表示所有撤销时点已经闭环；当前实现缺口以 §2 为准。

| 能力 | 默认 / 系统事实 | 使用点与撤销要求 |
|---|---|---|
| Root | 本机开关缺省 false；有 su metadata≠管理器授权≠UID0 | 使用时再验开关/档位/固定 helper 身份；关闭围栏后续请求，已发动作不能承诺回滚 |
| Shizuku | 管理器 Binder、其授权、UserService ready 分别取证 | 不假定 UID2000 或 root；死亡/撤权失效旧连接；Binder 已调用后异常 UNKNOWN |
| ADB | `adb_enabled=false`；配对≠鉴权连接；API37 另验局域网门 | 关闭先增意图 epoch、取消探活/修复；不删 key、不恢复旧票。A7 不新增/主动触发授权；既有配对/验证自动 grant 现状保留，改默认不授予另项批准 |
| 短信 | `sms_read=false`，系统/provider 仍可拒绝；当前无原生 ASK 档 | 仅当前 Android 用户的严格 `content query --uri content://sms...`。Root 宿主核开关、helper复核query/user，ADB连接后重取原生授权计划；Root慢su后的实时撤销复核仍需另补。拒绝 Shizuku/原始数据库/其他工具旁路 |
| 实体屏 | 新安装无有效 screen 授权；系统无障碍设置与真实连接分别判断；截图另有系统能力 | 明确任务 begin/finish、当前会话/轮次、屏幕模式和确认均须满足；暂停/断连/任务结束使观察与旧确认失效；迁移规则不等于新装自动开启 |
| 虚拟屏 | 无有效 screen 授权或 Core 不代表可创建；需已授权运输通道与真实系统能力 | 原 Core 与请求身份再核验；撤销先围栏再清理；ACK、EXITED、journal 提交分别证明；pending 不自动 create/close 重放 |
| 敏感屏 / 密码 | 默认逐类 ASK、名单空；全局 ALLOW 不代答敏感类别 | HANDOFF 暂停并丢弃观察，恢复先重看；密码文本硬阻断与确认独立；无障碍不可用时按现行降级明确 `passwordFocusChecked=false`，不宣称已保护 |
| F07 | 无后台自动刷新；Root 总开关默认关闭；首次读取由用户显式发起 | 仅本机真实 UID0、专用只读计划；拒绝/未知不切 Shizuku/ADB；不增加持久预授权；页面离开/Root 关闭取消并围栏迟到结果 |

## 4. M02 凭据与外接设备边界

- ADB key 仍为 guest 可见明文 `/root/.dsh/adbkeys`；其宿主物理位置可能是 legacy rootfs 或 stable user-data-v5 绑定（[UserDataLayout:10、74][data-layout]），桥 token 同样按当前数据根定位（[HttpShellService:509][token-layout]）。本轮只描述状态，不读取私钥/配对码，不重配对，不把备份排除误称原生化。桥凭据仍用私有头文件/X-Token，不放 URL、argv 或日志。
- screen 确认、Root/ADB 本机意图、短信授权、观察/lease/ticket/bridge token 都不随用户归档复活；虚拟 journal 只恢复清理事实，不能恢复 READY 或输入许可。保持现有机器凭据排除规则；不进入 A3/R3 备份写域。
- [ExternalInstallPlan:24、111][external-plan] 与 `ExternalDevice*` 服务外接 serial/工具锁/独立 HOME；可借鉴测试形状，不复用其安装/刷写权限、会话或 argv。外接命令参考不是本机受管 shell 的允许清单。
- 公开诊断只含通道名、状态码、阶段、有限计数、是否有身份/退出证据；不含短信/OTP、截图/树正文、用户输入、模块名/版本、完整路径、key、token、handshake 或清理 handle。私有错误也经既有脱敏出口，不用通用 Map 承载秘密。

## 5. F07 只读先行契约（拟定，S4 的必要条件）

1. **入口与身份**：首片只做原生设备页的“Root 模块清单”及用户显式刷新，不新增 assistant bridge 读/写入口。后台 worker 使用本机受管 Root helper，核验当前安装包/UID/授权意图及实际 UID0；任一不成立拒绝，不换通道。页面渲染不探测 su、不启动进程。
2. **管理器证据**：首批枚举 Magisk/KernelSU/APatch，LSPosed 不纳入 Root 模块管理器。仅在固定、来源可核对、root 所有且非 group/other 可写的候选可执行文件上做只读版本握手，不信任 PATH、模块目录内命令或文件自述。候选路径、版本/布局支持表与固定上游出处须在 S4 编码前审定；未知/多管理器冲突不猜，返回不可用。`NO_MANAGER` 仅表示允许的探测未识别，不等于手机未 root。
3. **目录读取**：专用 reader 锚定固定模块根；每级目录与 `module.prop` 用目录 fd/NOFOLLOW + fstat 身份核验，拒绝 symlink、非常规文件和不可信父链；不执行脚本、不跟随链接、不递归，不复用通用 `cat/ls/grep -R` 或外接安装计划。模块 ID 严格白名单，不能拼绝对路径/`..`/NUL/通配符；无法取得这套证据的管理器/ROM返回 unsupported。
4. **有界数据**：最多 256 个模块；每份 `module.prop` 最多 16 KiB、单字段最多 256 码点；总元数据 256 KiB；只允许 id/name/version/versionCode 与经类型核验的 disable/remove 标记、更新时间元数据。忽略未知键，不解释转义/插值，不加载脚本正文。清单读取阶段截止 10 秒、并发 1，不创建自动轮询。Root 启动/管理器授权等待独立显示；当前 helper 的 65 秒只是结果收集边界，不承诺全请求 65 秒或 10 秒结束。
5. **结果与隐私**：区分 `ROOT_DISABLED`、`SU_UNAVAILABLE`、`ROOT_NOT_GRANTED`、`ROOT_IDENTITY_UNVERIFIED`、`NO_MANAGER`、`MANAGER_AMBIGUOUS`、`UNSUPPORTED_MANAGER_VERSION`、`MODULE_ROOT_UNREADABLE`、`LIST_PARTIAL`、`UNKNOWN`。均为拟定新内部码；保留旧公开码。空清单只有在已验证根目录、完整枚举且零条目时成立；权限拒绝/截断/竞态不是空清单。部分结果显式 incomplete；已派发后失回执保持 UNKNOWN，用户检查后可发起新的独立读取，但不自动重放。
6. **写入口封闭**：类型只含 probe/list，不接收 command/path/argv/模块 ZIP；UI 没有启停/安装/卸载按钮，执行侧未知 operation 默认拒绝，三种 Root 档位下 F07 写请求都拒绝。现有通用 Root/外接模块写链不因复用而成为 F07 后门；它们尚未封闭/降权，故 F07 写能力继续 deferred。

S4 必须同时验证替换父目录/条目竞态、软/硬链接、符号管道/设备文件、超时、超项/超字节、重复/恶意字段、无权限、多管理器、未知版本、过期页面回调与关闭 Root 后返回。缺少受管 helper/NOFOLLOW 实证时只能交付“检测受限”，不得以读取方便为由改成 shell 扫目录。

## 6. 分片、写域与并行接缝

仅本文件为本阶段写域。下表是待批准的第二阶段 allowlist，新增类名均为拟定，不表示文件已存在；每片独立提交、定向验证、明确可回退点。现有 Java 不因切片顺手翻译；若批准新 Kotlin 子模块，按 ADR-0004 与 [Kotlin 工具链][kotlin-doc] 同批格式/编译，禁止同类双实现。

| 切片 | 内容 / 模块 | 拟允许改动 | 完成证据 |
|---|---|---|---|
| S1 | M06 类型/失败/身份 + M02 状态边界、M05 授权接缝 | 新 `device/DeviceCapability*`、`device/DeviceChannelStatus*`；`DeviceBridgeService` 的只读状态接缝、`DeviceShellExecutor`、`RootShell`/`RootShellMain`/`ShizukuShell` 设备方法、`bridge/AdbBridge` 状态投影；`util/DeviceShellPolicy`/`DeviceChannelOrder` 纯判据；拟增 `core/DeviceAuthorization*`。`ConfigStore`/`DeviceGrants`/`HttpShellService` 只限授权委托/device 小入口，需 Lead 接缝批准 | 后端可用/业务获准/实际 UID 分开；新鲜事实与旧状态不同；档位差分；已选通道派发计数=1；旧协议与普通命令退出码不漂移；M02 无秘密回显 |
| S2 | M07 实体屏能力/凭据接缝 | `screen/` 中当前请求/观察 DTO 与必要适配器；`DshaAccessibilityService` 仅主屏观察/动作接线；`util/ScreenObservationLedger`/`ScreenNodeLeaseStore`/`ScreenRequestExecutor` 必要类型边界；对应 screen/util/control 测试 | 主屏凭据不可跨 owner/任务/连接/目标/模式；tree 不签坐标；撤销/UNKNOWN 不回放；不打开 T130 |
| S3 | M08 虚拟屏能力与三链边界 | `vscreen/VirtualScreenManager`、`VirtualScreenAccessibility`、`VirtualScreenObservedControl`、`VirtualScreenSemanticAdapter` 与相应 target DTO；`util/VirtualScreenPolicy`、`VscreenBridgeRequest` 必要字段校验；对应 vscreen/util/bridge 测试 | raw 必须最新已观察；semantic 不强制像素静止；anchor 原证据一次性；无隐式 fallback；START/CLOSE_UNKNOWN、pending 屏障保持 |
| S4 | F07 只读清单 + M06 受管 helper | 新 `device/RootModule*`、`util/RootModule*`、`ui/RootModulesFragment`；仅 Root helper 的专用 operation 扩展；只读来源/版本表与 fixture | §5 正/反例、默认无自动请求、真实 UID 必需、未知 operation 拒绝、写入口不可达、诊断无模块内容 |
| S5 | 设备页闭环/文档/出口 | `ui/DeviceChannelsFragment`、设备授权页的状态展示接缝、屏幕页最小状态消费、必要资源与十语目录；对应 UI/contract 测试；任务回执与权限/双屏文档。A1 注册/路由只在既有宿主入口确需接线且 Lead 同意时改 | 错误/取消/未知/排队不被缓存掩盖；切页/重启状态正确；安全入口始终可达；格式/边界/链接/权限清单及 main 集成门禁 |

S1 默认仅诚实投影 ADB+su 的 unverified 身份，不改 guest 协议。只有 Q4 明确批准同上下文身份门及反例后，才可将 [adb-shell.py:318][adb-shell] 和 [device-shell-policy.py][guest-policy] 的“su 包装/身份校验”方法段加入写域；不得改地址发现/密钥/配对/虚拟启动 commit，须额外跑真实组帧的旧新差分与“不发原命令/不换通道”负控。

共享写域控制：

- **禁止写** `backup/`、`rust/`、`util/RustArchive*`，A4 的 PTY/终端/出生退出 owner，A5 的 `runtime/`、维护/启动/停止协作者，A6 插件事务与 A8 窗口 owner。调用其已有接口；不基于未合入侧分支接口实现。
- `RootShell`/`RootShellMain` 含终端或其他域方法：A7 只动设备专用接缝，不动 PTY/终端关闭/进程回收段；在 A4 合入后重新核对文件。`ConfigStore`、`DeviceGrants`、`DshaApp`、`HttpShellService`、设备授权页都是共享接缝，Lead 指定方法级窗口后再写，不整体占用其所有职责。
- `managed-runtime-inputs`、descriptor、总资产/格式/host-test 清单与版本身份不并行改。A7 提交新增源/测试精确名单与补丁建议，Lead/main 在合并树统一纳管；若需 worker 写工具清单，先明确本片独占窗口。
- 不改路线图进度、总索引、包名/证书/versionCode；只有 Lead 维护版本线/合并/发布。实施批准与合并授权分别记录。

## 7. runtimeId、维护页与回退

本任务 d9 树的[描述符:1][descriptor] 存储 `runtimeId=f02b4465e638c20b2a2f3d1be7ca022704d6742b601c83ac5da7c0f86372b9a3`、`launcherInputs=490`，其中 `screen=19`、`vscreen=36`、`device=6`、`ui=0`，`bridgeProtocol=3`、`dataWrite=dsh-0.2.2-rc.1`。这是**读取已存 JSON 的计数，不是重算/验证当前源码摘要通过**，不沿用[安全模型历史发布段:85][security-release]的旧数量或旧 runtimeId。

[managed-runtime-inputs:623][managed] 将 screen/vscreen/device 整树纳管；Root/桥/无障碍根类通过显式 sources 纳管。第二阶段任一相关源码变化都应改变 runtimeId，不能靠漏列新类实现“零维护”。最终 main 将新增生产类补进精确 inputs，重算描述符，核对源字节/受管输入，再以同线上一版本覆盖验证**一次整屏维护页**与 I1–I10；只改文档的本提交不改变运行身份。

回退点为每个切片前的本地提交与最终由 main/用户管理的发布 tag；本 worker 不创建 tag。无数据格式升级、无旧授权导入、无 ADB key 迁移。代码回退不擦除偏好或现场；已派发动作不回放；虚拟 Core pending 继续保留证据直至原 owner 核验退出。正式回滚若要安装，由 main/用户以更高 versionCode 的旧实现执行，不用清数据或降级安装充当回退。

## 8. 验证矩阵与证据规则

本阶段 baseline 测试仅证明现有代码给定 fixture 的行为；第二阶段按触及写域跑下列定向集。全量 Gradle/lint/打包由 main 合并窗口串行执行；本机 Gradle 只走 `dsha-gradle`，不安装、不用设备命令补宿主证据，不把零执行/skip当通过。

| 域 | 必跑定向回归入口 | 关键反例 / 证据 |
|---|---|---|
| 白名单/Root/身份 | `DeviceShellPolicyTest`、`DeviceShellPolicyDeleteTest`、`RootShellCandidatesTest`、`DeviceChannelOrderTest`、`DeviceCommandResultTest`；[native policy 脚本][test-native]、[guest policy 脚本][test-guest] | 126 且零派发；三档和短信门；未知/非0 UID 不报 Root；SHELL 不落 Shizuku；超时/缺结果只派发一次 |
| M02/授权/脱敏 | `CredentialPathsTest`、`BridgeCredentialAuthTest`、`BridgePathPolicyTest`、`DeviceGrantsTest`、`DeviceGrantsPreferenceRecoveryTest`、`CapabilityBrokerTest`、`SensitiveDataTest` | 凭据区/备份排除、损坏记录 fail-closed、迟到 allow/撤销/暂停、日志不回显 key/短信/模块清单；现有 A3 回归由其 owner 执行，不改备份代码 |
| 短信 | `SmsQueryTest` 与策略双实现回归 | 非query、URI参数/别的provider、跨用户、重复flag、SQL/复合shell/短信库路径均拒绝；关闭不是自动逐次问；测试不读取真短信 |
| M07 | `MainScreenObservationTest`、`ScreenNodeActionsTest`、`ScreenOwnershipTest`、`ScreenAuthorityBindingsTest`、`ScreenCapabilityGateTest`、`ScreenModePolicyTest` | 新旧截图/tree/owner串用、30秒、单次、零bounds、暂停/断连/任务结束、模式错误，不触发另一屏 |
| M08 | `VirtualScreenPolicyTest`、`VirtualScreenNativeInputFrameBoundaryTest`、`VirtualScreenObservedPixelsTest`、`VirtualScreenAnchoredChainTest`、`VirtualScreenManagerOwnershipTest`、`VirtualScreenManagerRevocationTest`、`VirtualScreenCloseReconcileTest`、`VirtualScreenPersistentCleanupTest`、`AdbVirtualScreenStartTest` | 最新已观察帧、光标变化仍验语义、原图anchor不续期/不重选、原Core/窗口变更、ticket撤销、ACK≠EXITED、journal失败保屏障 |
| 敏感屏 | `SensitiveScreenPolicyTest`、`SensitiveScreenClassifierTest`、`DeviceGrantsSensitiveScreenTest`、`VirtualScreenPasswordProbeTest` | ALLOW_ALL 不跳敏感确认；HANDOFF 未发送且恢复重看；密码硬阻断；降级标记准确 |
| F07 | 拟增只读计划/parser/reader/helper/页面 owner 链测试 | §5 所有反例、三档写入口拒绝、目录被换链、部分清单不装空、取消/授权变化/迟到结果；经过真实编排，不只复制 helper |
| 工程/文档/UI | [边界脚本][test-boundary]、[链接脚本][test-links]、Java/Kotlin 固定全树格式；新增生产/测试纳管核对 | 边界基线只减不增；文档默认集 + 显式本文件；本地 `file:line` 再核对；有 UI 变化时 `LayoutAuditInstrumentation` 中英/日夜/短屏/1.3×，设备证据单列 |

### 8.1 当前阶段实际执行

- `python3 -B tools/test-native-device-policy.py`：首次因未配置 JDK 报 `JDK17_REQUIRED`，未执行用例；设置固定 `/opt/jdk17` 后 **35 个 production Java dry plans 通过**，无 Android 进程/权限证据。
- `python3 -B tools/test-device-shell-policy.py`：**38 项，0 failure/error/skip**，使用假设备元数据与记录发送器，无设备派发。
- `python3 -B tools/test-core-boundaries.py`：**25 项，0 failure/error/skip**。
- `python3 -B tools/check-document-links.py`：维护默认集 **56 文档/1309 链接/0 warning/0 error**；本文件显式集 **1 文档/61 引用目标/0 warning/0 error**。另核验 **73 处引用使用、47 个源码/文档行锚点、32 个实际测试类**，无缺失/越界/未定义引用；锚点含义再由 read 与只读复核确认，链接工具本身不校验 fragment 语义。
- 格式：首次离线缺格式 jar 未执行；按仓库锁定 size/SHA-256 复用现有缓存后串行重跑，**Java 1775/1775、Kotlin 2/2，需格式化 0，均 exit0**。只写忽略的构建报告，未改任何源码。
- 只读复核：M06/M02 调查与成稿复核均已完成，提出的授权时点、自动 grant、物理数据根与可选 guest 写域问题已修入本稿；双屏子调查两次中断，剩余主链由本会话直接读取核验，不将中断任务算作通过。
- 未跑：Gradle/JUnit Android fixture、全量单测、lint、Kotlin 编译、APK/签名/ELF、覆盖安装、所有真机矩阵。当前仅文档，以上不冒写为通过。

### 8.2 需用户另行授权的真机动作（本会话不执行）

| 验收 | 所需用户授权/操作 | 必留脱敏证据 |
|---|---|---|
| 三通道身份/撤权 | 用户在测试包分别开启/授权 Root、Shizuku、本机无线 ADB，批准只读 `id`/握手；之后逐项撤销 | 各自真实 UID、开关/服务/连接事实、失败码；拒绝不切通道，已发未知不重试 |
| F07 | 用户允许专用 UID0 只读版本/固定目录元数据读取；选择实际管理器/版本样本 | 支持/未知/拒绝、完整或partial计数、写入口不存在；模块清单正文不进公开证据 |
| 实体屏 | 明确操控手机、屏幕模式、系统无障碍/截屏权限与原生敏感确认；只在用户选定测试应用观察/点按/输入 | task begin/end、目标/epoch、凭据失效/撤销、动作后观察；不含个人截图/OTP/密码 |
| 虚拟屏 | 用户允许创建/启动指定测试应用/观察/一个输入/关闭原 Core；清理未知由用户明确决定后续动作 | 三条动作链、raw旧帧拒绝、semantic/anchor目标复核、模式不混用、UNKNOWN不重放、退出proof/journal |
| 敏感/权限矩阵 | 用户选择安全测试页进行 ASK/HANDOFF/撤销/恢复；不实际支付、不代填密码 | 普通页/敏感类别/密码硬阻断、恢复必须新观察、缺服务降级；不记录敏感文本 |
| 覆盖与视觉 | main/用户准备授权测试包安装、同线覆盖、语言/日夜/短屏/字体切换 | 一次维护页、数据/授权边界、无白屏、无重复侧栏、UI可滚动；不在正式包自行安装 |

## 9. 待 Lead 审批/裁决

| 编号 | 待定事项 | 本稿建议 / 影响 |
|---|---|---|
| Q1 | 是否批准 A7 主闭环与 T42/T40 分线及 S1–S5 写域 | 批准分线；本轮不受尚未验证的 Keystore/TLS/SPAKE2 卡住，也不假装解决其风险 |
| Q2 | M05 Root/ADB 唯一变更门面及共享文件窗口 | 保留旧键，单门面委托旧存储，先围栏后发布；Lead 明确 `ConfigStore`/`DeviceGrants`/桥/根 helper 的方法级窗口，未批前该片不写 |
| Q3 | F07 首批管理器与呈现面 | 原生页只读、三类枚举、仅经固定来源/版本/布局证明的样本支持，其余unsupported；不含LSPosed、不新增助手端点。各管理器路径/版本表在S4开工前补齐并审定 |
| Q4 | ADB+su 真实身份最小改动范围 | 至少未知事实诚实投影；若要补同上下文身份门，单独审阅 guest/执行侧协议与反例，不以单独一次 `id` 推断下一次su已获准；若前置不足则该身份仍unverified，不把S1标完整身份闭环 |
| Q5 | R2/R5、短信逐次确认、可选 WRITE_SECURE_SETTINGS 的任务归属 | 继续列已知边界；独立任务承接，是否改安全策略由用户/Lead决定，不在 A7 隐式启用或收紧/放宽旧偏好 |
| Q6 | 实施基线、A4/A5/A6/A8 接缝与出口顺序 | 等 Lead 审稿并指定当时 main；不擅自 rebase 或使用侧分支 API。全部切片的软件证据、未跑项与真机待验明确后才报整轮 ready |

审批后由本会话将选择与版本写回本基线，才开始对应实施切片。第一阶段完成不等于 A7 出口完成；main 合并/发布仍需用户另行批准。

[roadmap]: iteration-roadmap-20261112.md#21-18--21--轮次-映射外部开发者定位用
[adr4]: ../../adr/0004-kotlin-rust-direction.md#决定四rust-首期只做旁路计算与计划校验
[check]: ../a7-root-adb-design-check-20261113.md
[permissions]: ../../device-permissions-architecture.md
[permissions-sms]: ../../device-permissions-architecture.md#L212
[vscreen-doc]: ../../virtual-screen-architecture.md
[adr3]: ../../adr/0003-virtual-screen-lifecycle-and-input.md
[security]: ../../security-model.md
[security-root]: ../../security-model.md#L49
[security-adb]: ../../security-model.md#L75
[security-release]: ../../security-model.md#L85
[external-doc]: ../external-device-commands-20261102/README.md
[t42]: ../adb-native-20261103/README.md#L3
[t42-risk]: ../adb-native-20261103/README.md#L43
[t40]: ../device-exec-confirm-20261103/README.md#L3
[agents]: ../../../AGENTS.md#L103
[kotlin-doc]: ../../kotlin-toolchain.md
[root-main]: ../../../app/src/main/java/com/deepseekharness/app/RootShellMain.java#L96
[root-uid]: ../../../app/src/main/java/com/deepseekharness/app/RootShellMain.java#L72
[root-discovery]: ../../../app/src/main/java/com/deepseekharness/app/RootShell.java#L60
[root-exec]: ../../../app/src/main/java/com/deepseekharness/app/RootShell.java#L102
[config-root]: ../../../app/src/main/java/com/deepseekharness/app/core/ConfigStore.java#L280
[adb-switch]: ../../../app/src/main/java/com/deepseekharness/app/DeviceBridgeService.java#L112
[grants-sms]: ../../../app/src/main/java/com/deepseekharness/app/core/DeviceGrants.java#L75
[http-plan]: ../../../app/src/main/java/com/deepseekharness/app/HttpShellService.java#L1192
[http-execute]: ../../../app/src/main/java/com/deepseekharness/app/HttpShellService.java#L1083
[http-sms]: ../../../app/src/main/java/com/deepseekharness/app/HttpShellService.java#L1260
[launch-ticket]: ../../../app/src/main/java/com/deepseekharness/app/HttpShellService.java#L1135
[adb-shell]: ../../../app/src/main/assets/adb-shell.py#L318
[adb-keepalive]: ../../../app/src/main/java/com/deepseekharness/app/bridge/AdbBridge.java#L461
[channel-order]: ../../../app/src/main/java/com/deepseekharness/app/util/DeviceChannelOrder.java#L26
[policy]: ../../../app/src/main/java/com/deepseekharness/app/util/DeviceShellPolicy.java#L163
[transport]: ../../../app/src/main/java/com/deepseekharness/app/device/DeviceTransportResult.java#L95
[cap-result]: ../../../app/src/main/java/com/deepseekharness/app/capabilities/CapabilityResult.java#L46
[executor]: ../../../app/src/main/java/com/deepseekharness/app/DeviceShellExecutor.java#L25
[sms-query]: ../../../app/src/main/java/com/deepseekharness/app/util/SmsQuery.java#L37
[credential-table]: ../../../app/src/main/assets/credential-paths.json#L27
[screen-mode]: ../../../app/src/main/java/com/deepseekharness/app/screen/ScreenMode.java#L56
[physical-pixel]: ../../../app/src/main/java/com/deepseekharness/app/util/ScreenObservationLedger.java#L103
[physical-node]: ../../../app/src/main/java/com/deepseekharness/app/util/ScreenNodeLeaseStore.java#L28
[physical-owner]: ../../../app/src/main/java/com/deepseekharness/app/screen/ScreenRequestContext.java#L83
[raw-policy]: ../../../app/src/main/java/com/deepseekharness/app/util/VirtualScreenPolicy.java#L98
[semantic]: ../../../app/src/main/java/com/deepseekharness/app/vscreen/VirtualScreenSemanticAdapter.java#L24
[anchored]: ../../../app/src/main/java/com/deepseekharness/app/vscreen/VirtualScreenObservedControl.java#L272
[external-plan]: ../../../app/src/main/java/com/deepseekharness/app/util/ExternalInstallPlan.java#L103
[module-desc]: ../../../app/src/main/java/com/deepseekharness/app/contract/ModuleDescriptor.java#L9
[contract-result]: ../../../app/src/main/java/com/deepseekharness/app/contract/ContractResult.java
[descriptor]: ../../../app/src/main/assets/runtime-descriptor.json#L1
[managed]: ../../../app/src/main/assets/managed-runtime-inputs.json#L623
[test-native]: ../../../tools/test-native-device-policy.py
[test-guest]: ../../../tools/test-device-shell-policy.py
[test-boundary]: ../../../tools/test-core-boundaries.py
[test-links]: ../../../tools/check-document-links.py
[channel-usable]: ../../../app/src/main/java/com/deepseekharness/app/util/ScreenCapabilityGate.java#L105
[adb-probe]: ../../../app/src/main/java/com/deepseekharness/app/DeviceBridgeService.java#L471
[root-sms-helper]: ../../../app/src/main/java/com/deepseekharness/app/ShellService.java#L60
[data-layout]: ../../../app/src/main/java/com/deepseekharness/app/backup/UserDataLayout.java#L10
[token-layout]: ../../../app/src/main/java/com/deepseekharness/app/HttpShellService.java#L509
[virtual-node]: ../../../app/src/main/java/com/deepseekharness/app/vscreen/VirtualScreenAccessibility.java#L270
[editor]: ../../../app/src/main/java/com/deepseekharness/app/vscreen/VirtualScreenAccessibility.java#L327
[guest-policy]: ../../../app/src/main/assets/device-shell-policy.py#L359
