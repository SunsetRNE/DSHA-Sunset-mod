# A6 任务基线：DSH 插件管理、生命周期与配置命名空间

> 日期：2026-11-14；工作树 `feat/a6-plugins`；代码基线 `origin/main` = `5b547868bc5eaa747fb4e91e9c8e65293e3e208f`（build364 主线）。已发布 build364 的 tag 指向 `1545e104`，不能将主线增量当作已发布内容。
> 状态：**基线已通过 Lead 评审（2026-11-14）**；§8.3 Q1–Q5 全部按建议裁决，批准 P0→P4 全轮范围，每片独立提交/测试。原第一阶段提交为 `d1818317`；当前开始第二阶段 P0。批准不替代非实现者出口审查或用户对合并/发布的授权。
> 负责人：`@a6-plugins` / `session-33a8cf01-ff62-461f-b582-238af24d1b76`；版本线、合并、发布由 `lead` / `fe61d7df` 管理。
> 依据：[迭代路径 §2.1 / A6 / §7](iteration-roadmap-20261112.md)、[插件管理评估](../plugin-management-alignment-20261113/README.md)、[插件使用契约](../../plugins.md)、[安全模型](../../security-model.md)、[齿轮单实例约定](../../mobile-sidebar-settings-gear.md)。不重新调研厂商和权限矩阵。

## 0. 本次交付与审批边界

本次只新增本文；不改生产代码、受管资产、描述符、版本号、CI 或全局索引。原评估的五个 P1 缺口仍存在，沿用其 **P0 契约 → P1 读取 → P2 运行证据 → P3 更新/兼容 → P4 守护** 切片，不另起一套插件协议。

A6 整轮目标是一条可验证的闭环：列表展示 → 安装/更新候选 → 提交和账本 → 启停意图 → 当前运行证据 → 取消/超时/未知结果 → 正常维护或恢复。第一阶段的文档提交可独立评审、合并；不能据此宣布 A6 实现或出口完成。

开工核对的两处路径修正：用户提及的事务脚本实际在 [plugin-transactions.py](../../../app/src/main/assets/plugin-transactions.py)，测试在 [test-plugin-transactions.py](../../../tools/test-plugin-transactions.py)，没有同名的 tools 生产脚本；旧评估写在 backup 包下的恢复协调器已迁到 [core/PluginJournalRecovery.java:18](../../../app/src/main/java/com/deepseekharness/app/core/PluginJournalRecovery.java#L18)。后续以本基线代码路径为准。

仓库没有任务板文件，未自行初始化或认领虚构任务；用活跃声明及 `peer_report` 登记。原技能引用的两份通用维护指南在本仓不存在，实际约束采用 [AGENTS.md](../../../AGENTS.md)、上述迭代路径与本仓插件/维护文档。全局进度看板和文档索引由 Lead 统一登记。

## 1. 目标、非目标与状态所有权

### 1.1 目标

1. F06：原生插件页能准确呈现安装内容、bundle 选择、entry 禁用、运行证据、操作结果与重启原因；失败、排队、取消、超时和未知不能被旧卡片或成功提示遮住。
2. M13：复用既有应用级管理器、单消费者队列、profile writer 锁和插件事务；未提交计划不算已安装，不自动重放；热检查只失效和读取，不成为另一写者。
3. M01：明确插件配置的身份、命名空间、写者和恢复边界，不重命名现有偏好键，不将原生模块贡献目录当作 DSH 插件库存。
4. 系统与用户插件保持分层：系统源码由当前 APK 的精确受管证明重建；用户备份保留系统插件意图；`.disabled` 与实体冲突时禁用优先。
5. 内置 `dsh-web-mobile` 保持 3.3.5、固定上游 main `646fe837440f9939228bdba0024d407eaac01128`；只保留现有最小集成补丁与 `DSHA_GEAR_FIT_V1` / `DSHA_GEAR_SINGLETON_V1`。

### 1.2 非目标

- 不做新插件市场、非 web profile 管理、Gradle 模块拆分、Java→Kotlin 包迁移或 Rust 插件事务引擎。
- 不新增 Android、执行、下载、构建脚本权限；维持当前生命周期脚本/pnpmfile 策略，不恢复原生审阅门槛。
- 不让原生 UI 直接调用带 cookie 的 Typert RPC、写 entry patch 或版本豁免；在线管理链替换另立设计。
- 不改 A3 的 backup 域、R3 的 Rust/archive 域、A4 的终端域；A5 的维护/运行时写域只读。跨域必需修改需先交 Lead 协调。
- 不 push、合并、tag、Release、安装或操作手机；不把主机夹具结果写成真机启停/更新已验收。

### 1.3 M01 命名空间与唯一写者（拟冻结）

| 数据/身份 | 当前事实与 owner | A6 约束 |
|---|---|---|
| 原生插件页偏好 | [ConfigStore.java:128](../../../app/src/main/java/com/deepseekharness/app/core/ConfigStore.java#L128) 保留 `plugin_sort_order`、`plugin_download_source` | 继续经 ConfigStore；不搬键、不写第二份同义配置、不混入插件作者参数或凭据 |
| bundle 安装/选择 | web profile 的 dependency 和有序 bundles，由 DSH/受管 guest 写者提交；[plugin-manager.py:1074](../../../app/src/main/assets/plugin-manager.py#L1074) | 逻辑身份为 `(profile=web, scope=application, canonical npm name)`；来源标签/版本/路径不是身份；保留既有自动审阅别名规则 |
| entry 配置 | DSH 用户 patch 与 Loader 条目；原生尚无 entry 模型 | entry 身份为所属 profile + entryId，并核对 moduleHash/父组；配置只读投影，不能用包名或原生 ModuleId 代替 entryId |
| 原生贡献布局 | [ContributionCatalog.java:44](../../../app/src/main/java/com/deepseekharness/app/contract/ContributionCatalog.java#L44) 按全局贡献键登记，版本由 generation 绑定；[BuiltinModules.java:10](../../../app/src/main/java/com/deepseekharness/app/contract/BuiltinModules.java#L10) 当前只登记 about | 与 [PluginRegistry.java:12](../../../app/src/main/java/com/deepseekharness/app/plugins/PluginRegistry.java#L12) 的插件快照是两个域；隐藏卡片不等于禁用插件，撤销贡献不删除 npm 内容；A6 不接管 A8 的页面注册 |
| 禁用/安全模式/激活/更新账本 | guest 管理链持锁维护；[plugin-lifecycle.py:460](../../../app/src/main/assets/plugin-lifecycle.py#L460) / [722](../../../app/src/main/assets/plugin-lifecycle.py#L722) | 不引入第二持久权威；新增结果字段只加法，旧字段/旧日志可读；插件作者原始配置不自动翻译 |
| 备份恢复 | [NativeRestoreTargets.java:96](../../../app/src/main/java/com/deepseekharness/app/backup/NativeRestoreTargets.java#L96) 将可执行 profile 配置隔离；[132](../../../app/src/main/java/com/deepseekharness/app/backup/NativeRestoreTargets.java#L132) 跳过系统插件包 | 保持既有隔离、机器状态排除；不为方便热检查把备份配置自动应用到 live profile |

新增 A6 只读字段按 bundle / entry / session-entry 分组，不创建任意插件可写的原生配置 API。若第二阶段确实需要新增本机偏好，先列出精确键、owner、默认值、导出策略和旧读者行为再交 Lead 评审。

## 2. 当前缺口清单（以代码为证据）

以下 G1–G5 对应原评估的五个 **P1 严重度缺口**；这里的 P0–P4 是实施切片编号，两者不是同一优先级体系。

| 编号 | 当前证据 file:line | 影响与拟收口切片 |
|---|---|---|
| G1 entry 启停不可见 | [plugin-manager.py:1038](../../../app/src/main/assets/plugin-manager.py#L1038) 的 revision 不含 profile patch；[1078](../../../app/src/main/assets/plugin-manager.py#L1078) / [1124](../../../app/src/main/assets/plugin-manager.py#L1124) 只投影 bundles 等目录事实 | Web entry 禁用后，原生配置仍可能显示已启用。P1 增加 patch 修订和 tri-state 读取；不能执行 `!!js`，不能伪称已解析全部 patch 语义 |
| G2 bundle / entry 混同 | [PluginRepository.java:149](../../../app/src/main/java/com/deepseekharness/app/core/PluginRepository.java#L149) 的 Item 以包建模；[216](../../../app/src/main/java/com/deepseekharness/app/core/PluginRepository.java#L216) 默认 web；[runtime-plugins.js:114](../../../app/src/main/assets/app-integration/runtime-plugins.js#L114) 只输出包级状态 | 多 entry 部分禁用/失败无法定位。P0 身份契约、P1 字段和 UI、P2 entry 证据；entry 不获得“删除整个包”的动作 |
| G3 运行词表不同 | [runtime-plugins.js:37](../../../app/src/main/assets/app-integration/runtime-plugins.js#L37) 将 fiber 5（卸载中）折为 loading；[110](../../../app/src/main/assets/app-integration/runtime-plugins.js#L110) 取最坏子状态；[PluginRuntimeProjection.java:157](../../../app/src/main/java/com/deepseekharness/app/plugins/PluginRuntimeProjection.java#L157) 接受旧别名 | P0 集中映射母集 `pending/loading/unloading/active/failed/null`；旧 loaded 等为兼容别名；P2 输出 entry phase，ABSENT 不推断原因 |
| G4 更新/兼容性不对齐 | [plugin-lifecycle.py:161](../../../app/src/main/assets/plugin-lifecycle.py#L161) 先取单个 DSH peer 再取 engines；[627](../../../app/src/main/assets/plugin-lifecycle.py#L627) 只检查实际版本更高；[PluginRepository.java:314](../../../app/src/main/java/com/deepseekharness/app/core/PluginRepository.java#L314) 的 blocked 只看冲突和缺依赖 | 不仅是 UI 提示缺失，现有兼容规则优先级也与 DSH 不同。P3：engines 优先、缺省检查全部 DSH peer、已知不兼容阻断提交，补精确目标和结构化应用结果 |
| G5 启动失败归因过粗 | [plugin-lifecycle.py:521](../../../app/src/main/assets/plugin-lifecycle.py#L521) 将本轮 attempted 全部标为失败并停用；[PluginActivationHooks.java:118](../../../app/src/main/java/com/deepseekharness/app/core/PluginActivationHooks.java#L118) 按整轮失败调用 failed-load | Web/浏览器失败不能证明每个插件失败。P2 先区分已证实/无法归因；P4 补具体 failure、兼容性跳过和管理面原因 |
| G6 UNKNOWN 文案分类 | [PluginRepository.java:1235](../../../app/src/main/java/com/deepseekharness/app/core/PluginRepository.java#L1235) 用 `contains` 识别 UNKNOWN、未确认、TIMEOUT 等 | P0 定类型；P1 在调用边界传播 errorCode / stage / dispatch / confirmation，旧消息仅作保守兼容，不能用文案作主判据 |
| G7 窄卡片遮住结果 | [PluginCardState.java:126](../../../app/src/main/java/com/deepseekharness/app/util/PluginCardState.java#L126) 将消息排在重启/更新之后；[PluginFragment.java:1774](../../../app/src/main/java/com/deepseekharness/app/ui/PluginFragment.java#L1774) 的 COMPACT 仅保留首条 | 排队主徽章也是泛化“处理中”（[1982](../../../app/src/main/java/com/deepseekharness/app/ui/PluginFragment.java#L1982)）。P0 明确显示结果态优先级，P1 接线，UNKNOWN/失败/取消不得只藏在展开详情 |
| G8 证据新鲜度和内容身份 | [PluginRuntimeProjection.java:71](../../../app/src/main/java/com/deepseekharness/app/plugins/PluginRuntimeProjection.java#L71) / [119](../../../app/src/main/java/com/deepseekharness/app/plugins/PluginRuntimeProjection.java#L119) 仅对版本/来源/代次；[runtime-plugins.js:4](../../../app/src/main/assets/app-integration/runtime-plugins.js#L4) 的 moduleHash 是模块名摘要 | 同版本内容替换可能沿用旧 loaded；moduleHash 不是源码摘要。P2 对比启动时内容 fingerprint 与当前目录证据，不具备证明时保守待核验，不每 2 秒重扫磁盘 |
| G9 观察覆盖不足不可见 | [PluginChangeDetector.java:87](../../../app/src/main/java/com/deepseekharness/app/plugins/PluginChangeDetector.java#L87) / [165](../../../app/src/main/java/com/deepseekharness/app/plugins/PluginChangeDetector.java#L165) 有 128 路径、1024 目录、32768 条目、深度 48 限额；[226](../../../app/src/main/java/com/deepseekharness/app/plugins/PluginChangeDetector.java#L226) 已有 coverageLimited | P4 显示覆盖受限、管理面不可用、证据截断原因；不能当作空列表或无插件 |

已具备的能力不重做：[PluginRegistry.java:199](../../../app/src/main/java/com/deepseekharness/app/plugins/PluginRegistry.java#L199) 失败保留旧行；[247](../../../app/src/main/java/com/deepseekharness/app/plugins/PluginRegistry.java#L247) 精确目标回执；[318](../../../app/src/main/java/com/deepseekharness/app/plugins/PluginRegistry.java#L318) 心跳不能新增安装；[PluginListPresenter.java:247](../../../app/src/main/java/com/deepseekharness/app/ui/PluginListPresenter.java#L247) 队列意图优先于历史；[PluginRepository.java:1229](../../../app/src/main/java/com/deepseekharness/app/core/PluginRepository.java#L1229) 已确认操作与后续目录失败分开。新增实现必须保持这些负向回归。

## 3. 拟冻结的状态与 UI 契约

### 3.1 正交事实和证据

复用评估 §6 的 B1–B8：安装内容、profile 选择、entry 配置、当前运行、操作应用结果独立建模。P0 的纯逻辑视图建议含：

- 身份：kind、profile/scope、bundleName、entryId、moduleHash；系统来源证明与显示名称独立。
- 意图：selected、entryDisabled（true/false/unknown）、nativeMarker、effectiveIntent 与原因。entryDisabled=false **不能**覆盖祖先组禁用或原生 marker；未知表达式、无法归属、多个相同 id 都保留 unknown。
- 运行：phase、phaseEvidence、observedAt、generation、instance、staleReason、内容 fingerprint；LEDGER 只标“上次激活记录”，心跳消费侧标 HEARTBEAT（其来源可为 Loader），不能伪装成同步 Loader 查询。
- 操作：operationId、targetKey、transactionId/backendTaskId、phase、application、restartReason、stage、errorCode、是否已分发/是否确认/是否允许新请求。字段只加法；对旧生产者缺失字段采用保守默认，不把缺字段当 applied。

当前心跳年龄上限 30 秒（[PluginRuntimeProjection.java:22](../../../app/src/main/java/com/deepseekharness/app/plugins/PluginRuntimeProjection.java#L22)），目录 TTL 60 秒（[PluginManagementEngine.java:25](../../../app/src/main/java/com/deepseekharness/app/plugins/PluginManagementEngine.java#L25)），启动让路暂停自动查询 120 秒（[205](../../../app/src/main/java/com/deepseekharness/app/plugins/PluginManagementEngine.java#L205)）。A6 保持这些调度预算；分别显示最近核对与陈旧原因，不把刷新频率写成实时保证。

### 3.2 操作状态投影（必须参数化测试）

| 真实边界 | 账本/结果 | UI 必须呈现 | 后继行为 |
|---|---|---|---|
| 尚在队列 | QUEUED，独立 operationId、精确目标和期望版本 | 排队、待启用/待禁用；不能显示已应用 | 允许取消未 poll 请求；同目标替换只按既有 source/version 条件 |
| 未分发时取消 | CANCELLED，未执行 | 已取消；保持此前已核验意图 | 不执行该请求，不伪造成功 |
| 已分发且明确取消/恢复完成 | CANCELLED + 取消阶段和恢复确认 | 已取消，说明完成/未完成的阶段 | 不自动重装；正常后续请求重新核验目标 |
| 截止前从未分发 | 明确 timeout / not-sent 原因 | 超时且未执行 | 用户可新提交；不把旧请求静默重排 |
| 分发后超时、取消后退出不明、回执丢失 | UNCONFIRMED/UNKNOWN，保留 transactionId、现场、屏障 | 结果待核验和原因；优先于重启/更新提示 | 禁止自动重放/换源；先只读核对，pending 交维护入口 |
| 已确认提交，后续目录失败 | 提交成功回执 + catalog stale/error | 已提交、目录未核验并列；运行仍可未知 | 保留精确回执，失效后独立读；不能把已提交写成失败或 loaded |
| 明确失败/兼容拒绝 | FAILED/BLOCKED + stage/errorCode | 失败/不兼容及可读原因 | 不改变既有禁用意图；仅确认可回切的阶段才回切 |
| 操作提交但无热应用证据 | application=restart-required + restartReason | 已保存、重启生效 | 不将提交成功等同 Loader ready；本轮不启用 HMR |

保留 [PluginOperationQueue.java:38](../../../app/src/main/java/com/deepseekharness/app/plugins/PluginOperationQueue.java#L38) 的 SUPERSEDED / INVALIDATED / REJECTED 终态和 32 等待/64 历史边界；显示最新目标结果但不创建无界 UI 历史。纯内存操作历史不冒充跨进程持久证明；只有已有 guest 事务/激活记录提供跨进程证据。

错误/UNKNOWN/取消/超时/排队短标签在紧凑卡片和详情中均可见；既有 restart/update 信息保留，不能截断唯一结果信息。语言切换在显示边界重新渲染；原始插件消息、源 URL、包名不被当翻译键。布局沿用主题、滚动对话框和双栏规则。

## 4. 未提交事务、writer 锁与热检查

### 4.1 事务事实与恢复

- guest [plugin-transactions.py:333](../../../app/src/main/assets/plugin-transactions.py#L333) 和宿主 [PluginInstallJournals.java:22](../../../app/src/main/java/com/deepseekharness/app/backup/PluginInstallJournals.java#L22) 都把有计划且无 committed/rolled-back 的事务保留为 pending。候选/目录存在、preview 成功、进度 100% 不能替代提交回执。
- 安装计划现写 format 2，读取 format 1/2/3；删除/CAS 既有 format 3，未知算法失败关闭（[plugin-transactions.py:889](../../../app/src/main/assets/plugin-transactions.py#L889)、[951](../../../app/src/main/assets/plugin-transactions.py#L951)）。本轮不升格式、不删除旧字段；只读兼容与原件摘要检查不可削弱。
- 已完成日志不恢复后来修改的数据，未完成恢复先核对前后摘要、目录/链接身份；改变过的目标保留全部现场（[plugin-transactions.py:984](../../../app/src/main/assets/plugin-transactions.py#L984)）。完成证明是缓存，不是清空历史的理由。
- 宿主恢复要求维护 owner 且无其它任务，通过当前受管脚本和一次性 proof；成功后才归档完成日志、失效目录（[PluginJournalRecovery.java:21](../../../app/src/main/java/com/deepseekharness/app/core/PluginJournalRecovery.java#L21)）。UI 不能在刷新/重开时调用 recover 或重复 install。
- [PluginRepository.java:1188](../../../app/src/main/java/com/deepseekharness/app/core/PluginRepository.java#L1188) 的维护门禁和 [1321](../../../app/src/main/java/com/deepseekharness/app/core/PluginRepository.java#L1321) 的执行前阻断保持。正常启动、恢复及退出证明归 A5 所有，A6 仅消费其结果；pending 必须仍能进入整屏维护页。

### 4.2 锁和热检查同 owner

顺序保持：宿主环境租约/任务屏障 → guest 数据锁 → profile writer 锁 → 事务变更。guest [register-builtin-plugins.py:456](../../../app/src/main/assets/register-builtin-plugins.py#L456) 先锁数据根外的 `.dsha-data.lock`，再取与 DSH 共用的 `package.json.lock`；不可另造一把“热更新锁”使两者并写。

[register-builtin-plugins.py:323](../../../app/src/main/assets/register-builtin-plugins.py#L323) 仅明确退出返回 stale；live/unknown/不完整记录不接管；[370](../../../app/src/main/assets/register-builtin-plugins.py#L370) 的同内容 takeover claim 保持。锁中的 PID 存活探测只用于锁的保守占用判断，不授权按 PID 终止任何进程；不以超时删除锁。

热检查由 [PluginManagementEngine.java:23](../../../app/src/main/java/com/deepseekharness/app/plugins/PluginManagementEngine.java#L23) 调度，FileObserver 只失效，仓储单消费者执行；查询共享租约、变更独占租约（[PluginRepository.java:1129](../../../app/src/main/java/com/deepseekharness/app/core/PluginRepository.java#L1129)）。只读 list/refresh 不取 writer 锁，而是锁存在就报错、revision 双读不一致拒收（[plugin-manager.py:1015](../../../app/src/main/assets/plugin-manager.py#L1015)、[1158](../../../app/src/main/assets/plugin-manager.py#L1158)）。这保留查询的可取消性，也避免慢扫描把写者挡住。

P1 只增加 patch/兼容状态的快照输入和精确失效，不后台修复配置；P2 心跳只枚举当前 Loader，不能每次重新执行禁用表达式、读取目录或跑插件入口；P3 提交前在同一 writer 锁内复核目标版本、来源和兼容证据。来源 A→B→A、代次/实例变化必须让旧票据作废。

### 4.3 用户意图、系统字节与备份排除

安全模式只恢复本轮仍持有专用 marker 且 fingerprint 未变的插件，不覆盖用户期间主动禁用（[plugin-lifecycle.py:748](../../../app/src/main/assets/plugin-lifecycle.py#L748)）。同名更新、回滚维持用户禁用/安全模式意图；旧自动审阅 marker 与主动禁用须分别处理。

系统禁用同时摘除或隔离旧实体（[register-builtin-plugins.py:1102](../../../app/src/main/assets/register-builtin-plugins.py#L1102)）；恢复时 `enabled && !disabled` 决定选择（[SystemPluginProjection.java:45](../../../app/src/main/java/com/deepseekharness/app/backup/SystemPluginProjection.java#L45)）。不能通过保留一份旧实体绕过 `.disabled`。

[NativePluginGraph.java:97](../../../app/src/main/java/com/deepseekharness/app/backup/NativePluginGraph.java#L97) 只备份系统插件启停状态、排除源码；受管 provenance 按 APK 认证表与实际内容核对（[register-builtin-plugins.py:518](../../../app/src/main/assets/register-builtin-plugins.py#L518)、[589](../../../app/src/main/assets/register-builtin-plugins.py#L589)）。scope/前缀/安装树位置/可写 dependency 都不能提升为系统来源。A6 不修改这些备份实现，仅以测试守住边界；发现回归先报 Lead，由 A3 owner 处理。

## 5. P0–P4 切片、写域与出口

下表是申请批准的实现范围。批准单一切片不等于批准所有跨域改动；后续文件名可按职责命名，但写域不得静默扩展。

| 切片 | 拟交付 | 允许写域（批准后） | 单片出口与回退 |
|---|---|---|---|
| P0 契约 | 集中身份/phase/evidence/result 映射；正交状态与紧凑 UI 提示优先级；M01 所有权约定 | 新 util 纯逻辑视图、[PluginCardState.java](../../../app/src/main/java/com/deepseekharness/app/util/PluginCardState.java)、[PluginListPresenter.java](../../../app/src/main/java/com/deepseekharness/app/ui/PluginListPresenter.java)、[PluginFragment.java](../../../app/src/main/java/com/deepseekharness/app/ui/PluginFragment.java)、对应测试/文案 | 无新事实生产；旧字段兼容、未知不降成功；仅此片可零维护；单提交回退 |
| P1 读取补全 | patch revision、literal 禁用 tri-state、bundle/entry 关联、availability/readOnly 原因、结构化操作结果消费；原生 UI 显示 | [plugin-manager.py](../../../app/src/main/assets/plugin-manager.py)、[plugins 域](../../../app/src/main/java/com/deepseekharness/app/plugins/)、[PluginRepository.java](../../../app/src/main/java/com/deepseekharness/app/core/PluginRepository.java)、插件 UI/测试/文案 | 只读不执行 patch 表达式，writer 并发拒收混合快照；无新字段的旧 producer 不误判；不新增 entry 写入口 |
| P2 运行证据 | entryId/moduleHash/父组/phase 有界证据、诊断脱敏、内容 fingerprint 与新鲜度；避免整轮误伤 | [startup-observer.cjs](../../../app/src/main/assets/startup-observer.cjs)、[app-integration](../../../app/src/main/assets/app-integration/)、[PluginActivationHooks.java](../../../app/src/main/java/com/deepseekharness/app/core/PluginActivationHooks.java)、[plugin-lifecycle.py](../../../app/src/main/assets/plugin-lifecycle.py)、plugins/测试/插件 UI | 过期/代次/实例/entry/版本/内容不符 → 待核验；正常插件不被浏览器失败误标 failed；旧心跳可读 |
| P3 更新/兼容 | DSH 等价兼容规则、平台检查、精确版本目标/候选校验、application/stage/restartReason、只读豁免状态 | guest manager/lifecycle、必要事务回执加法、PluginRepository/plugins、安装/插件 UI 与测试 | 已知不兼容不提交，取消/未知不重放；保持用户意图和上一版；不新增豁免写/脚本批准权、不改网络白名单和来源策略 |
| P4 守护/补丁链 | 启动跳过结构化原因、管理面 capability/只读原因、补丁链状态与 coverageLimited | observer/lifecycle/ActivationHooks、plugins、插件 UI/已有诊断投影与测试；仅必要的插件补丁契约配方 | management 不可用≠零插件；缺证据保留 unknown；不靠 stderr 文案下结论，不屏蔽启动失败 |

各切片共用 [tools 下现有插件测试](../../../tools/test-plugin-discovery.py) 及定向 JVM/Robolectric 回归。文案通过 [messages.json](../../../tools/i18n/messages.json) 和既有生成流程，不新增硬编码色彩或独立 UI 状态机。

明确排除写域：backup 全域、Rust/archive、终端/进程实现、HarnessController、ProotBootstrap、ContainerRuntime、InstallPipeline、WebProcessManager、RuntimeTasks、ExtractActivity、UpdateDownloadService。若 P2/P4 需要启动链新增接缝，先提交最小变更建议给 A5/Lead；不能因 plugins 功能需要便自行改这些文件。共享 ConfigStore 当前只读，新增偏好需单独列键确认；contract 贡献目录本轮只读。

用户要求的五类结果和未提交事务边界是 A6 出口必需项。若只批准/完成 P0 或 P1，交付报告必须列出剩余 P2–P4，不能将评估建议的后续片默认移出 A6。实现完成后另需非实现者复核与用户在主会话的合并批准。

## 6. runtimeId 与维护页影响

| 阶段 | 受管输入事实 | 维护影响 |
|---|---|---|
| 本 docs-only 提交 | 不改受管输入；本基线 [runtime-descriptor.json:3](../../../app/src/main/assets/runtime-descriptor.json#L3) 为 `bd0016c33e807a8e60cec8b3267e78fef0d003f618c083227cb651bd274eaabf` | **零维护**；这是读取的描述符值，不表示本轮重建/验证了离线资产 |
| P0 纯 util/UI | ui 不在 launcherTrees；新增 util 不会仅因包名自动进入输入，须逐文件核对 | 可零维护；一旦改 plugins/core 生产字段便不再是 P0 零维护片 |
| P1–P4 合并交付 | [managed-runtime-inputs.json:623](../../../app/src/main/assets/managed-runtime-inputs.json#L623) 含 plugins tree；[654](../../../app/src/main/assets/managed-runtime-inputs.json#L654) 含 PluginRepository/Task/ActivationHooks；[56](../../../app/src/main/assets/managed-runtime-inputs.json#L56) / [76](../../../app/src/main/assets/managed-runtime-inputs.json#L76) 安装 guest 脚本 | **A6 整轮覆盖更新一次维护页**；片内未发布不累加安装次数 |

描述符生成以 [runtime_input_contract.py:107](../../../tools/runtime_input_contract.py#L107) 的 Java/Kotlin launcher 枚举和 [prepare-runtime-descriptor.py:75](../../../tools/prepare-runtime-descriptor.py#L75) 的实际摘要为准，不复用旧文档的输入数量。最终 runtimeId 由 Lead 在串行合并树上统一 `--write` 再 `--check`；本工作会话不拿 A3/R3 分支值替换主线值。

保持描述符 version=1、baseVersion、DSH 固定版本、dataRead/dataWrite、桥协议 3、包名/签名/发行身份不变。纯 UI 修复不触发额外环境替换；后端/运行资产改动正常走既有一次维护页。维护页、旧 Web 未确认、受限主界面等门禁继续由 A5/原运行时所有者管理。

## 7. 验证矩阵

### 7.1 实现后的必跑项（不是本次执行声明）

| 面 | 定向入口 | 必要正/负控 |
|---|---|---|
| 状态和 UI | PluginCardStateTest、PluginListPresenterTest、PluginFragmentRenderingTest、PluginFlowRowTest、拟新增纯视图参数测试 | 配置/运行/结果正交；排队/取消/超时/UNKNOWN/失败在紧凑与详情均可见；旧字段和语言重建不掩盖结果 |
| 单消费者/缓存/来源 | PluginOperationQueueTest、PluginRegistryTest、PluginManagementEngineTest、PluginRepositoryQueryLifecycleTest、PluginRepositoryDiscardTest、PluginTaskTest | FIFO/满队列/同目标替换；A→B→A；迟到回执；查询失败保留旧行标 stale；已确认意图不被旧运行证据覆盖 |
| patch 与锁 | [test-plugin-discovery.py](../../../tools/test-plugin-discovery.py)、[test-plugin-review.py](../../../tools/test-plugin-review.py) | literal true/false/unknown、动态表达式不执行、重复 entry、祖先 group；patch 改动进入 revision；live/unknown/stale 锁及等待取消；读写竞态不返回伪成功 |
| 事务 | [test-plugin-transactions.py](../../../tools/test-plugin-transactions.py)、PluginInstallJournalsTest、PluginJournalQuarantineTest | 每个移动/元数据/committed 边界真实子进程退出；format 1/2/3 可读；后改配置不覆盖；pending 不当安装；未知退出不释放屏障/不重放 |
| 运行证据 | [test-plugin-runtime-evidence.mjs](../../../tools/test-plugin-runtime-evidence.mjs)、[test-plugin-states.mjs](../../../tools/test-plugin-states.mjs)、PluginRuntimeProjectionTest | 用锁定 raw/managed 夹具；active/unloading/null；代次/实例/entry/module/内容不匹配、过期、超限；失败归因不误伤正常 sibling |
| 更新/依赖/取消 | [test-plugin-dependencies.py](../../../tools/test-plugin-dependencies.py)、[test-plugin-downloads.py](../../../tools/test-plugin-downloads.py)、[test-native-plugin-manager.mjs](../../../tools/test-native-plugin-manager.mjs) 及 P3 专项 | engines 与全部 DSH peers 优先级、平台、精确豁免只读、目标版本变化；已知不兼容零提交；生命周期已执行后的网络错误不换源重放；取消/回切阶段有账本 |
| 系统/用户分层 | NativePluginGraphTest、SystemPluginStateTest、PluginRestoreGraphTest；[test-builtin-plugin-default-off.py](../../../tools/test-builtin-plugin-default-off.py)、[test-restore-merge-plugins.py](../../../tools/test-restore-merge-plugins.py) | 系统字节来自当前 APK；用户假 scope/安装位置不提升来源；备份不含系统源码；disabled 与实体冲突/用户禁用优先；安全模式不覆盖用户改动 |
| 补丁/移动壳 | [test-plugin-management-patch-chain.py](../../../tools/test-plugin-management-patch-chain.py)、[test-plugin-management-probe.mjs](../../../tools/test-plugin-management-probe.mjs)、[test-mobile-gear-contract.mjs](../../../tools/test-mobile-gear-contract.mjs) | 管理面不可用、补丁不匹配、旧协议降级明确；单装载路径、多实例同一 triggerRow 仍只一颗齿轮 |

JVM 日常定向只走 `dsha-gradle :app:testStandardDebugUnitTest --tests '*PluginRegistryTest' ...`；由对应切片选择精确类名，不把同名通配符当实际执行计数。全量单测、lint 和 signed APK/发布门禁由 Lead 排队，Gradle 一次一个；不在本阶段占用构建槽位。

实施批次还必须跑：

```bash
python3 tools/format-java-tree.py --check
python3 tools/format-kotlin-tree.py --check
python3 tools/test-core-boundaries.py
python3 tools/check-ui-i18n.py
python3 tools/check-document-links.py
```

涉及受管资产时，在固定输入齐备、Lead 统一合并树上验证 descriptor 和生成物，不能跳过失败或自动下载/安装补环境。布局变化另由用户/验收会话执行 LayoutAuditInstrumentation：中英、日夜、短屏、1.3 倍字体。上述软件矩阵不替代设备验收。

### 7.2 本次已执行/未执行

本次在独立工作树运行隔离主机夹具，全部 exit 0：

| 命令 | 实际结果 |
|---|---|
| `python3 -B tools/test-plugin-transactions.py` | 14 tests / 0 failed / 0 errors / 0 skipped；含断点强杀与恢复，不作用于手机数据 |
| `python3 -B tools/test-plugin-discovery.py` | 40 tests / 0 failed / 0 errors / 0 skipped；含锁/禁用意图/来源证明 |
| `python3 -B tools/test-plugin-review.py` | 20 tests / 0 failed / 0 errors / 0 skipped；含安全模式和加载账本 |
| `python3 -B tools/test-builtin-plugin-default-off.py` | 9 tests / 0 failed / 0 errors / 0 skipped；默认关闭机制用合成配置，不改变当前移动插件默认启用 |
| `node tools/test-mobile-gear-contract.mjs` | 7 tests / 0 failed / 0 cancelled / 0 skipped |

合计 **90 项**，仅证明既有基线行为；不证明本文拟新增行为已实现。附加门禁如下：

| 门禁 | 本次结果 |
|---|---|
| `python3 tools/test-core-boundaries.py` | 25 tests，通过；未调整边界基线 |
| `python3 tools/check-ui-i18n.py` | newFindings=0；stale declarations=0 |
| `python3 tools/check-document-links.py` | 56 documents / 1309 ok / 0 warning / 0 error |
| `python3 tools/check-document-links.py docs/design/kotlin-rust-rearchitecture-20261112/a6-task-baseline-20261114.md` | 1 document / 104 ok / 0 warning / 0 error；只验证目标存在，不验证 fragment 语义 |
| Java / Kotlin format `--check --offline --jobs 1` | 首次缺省 PATH 报 `FORMAT_JDK17_MISSING`；找到并显式指定已有 `/opt/jdk17/bin/java` 后，两者报 `FORMAT_OFFLINE_CACHE_MISSING`（exit 2）。分别缺 google-java-format 1.22.0 和 ktfmt 0.64 的锁定 JAR；**格式检查未完成，不记通过**。未下载/安装工具；第二阶段前由固定工具链补跑 |
| `git diff --check` | 通过；交付提交时另对已暂存新增文档执行 `git diff --cached --check` |

格式器已完成清单枚举（Java 1767，声明清单 missing=0/extra=0；Kotlin 2），但没有进入格式验证，不能从枚举计数推导格式全绿。本次没有 Java/Kotlin 改动，因此该环境限制不改变 docs-only 审批范围，仍需向 Lead 明示。主机原始日志保留本机临时目录，必要计数与结论记录本文，不提交夹具输出、真实用户数据或设备日志。

未运行 Gradle、cargo、完整 host runtime、raw/managed entry 证据专项、descriptor 重建、APK 构建/安装和真机操作；本 docs-only 阶段无生产差异，不预填这些验收结果。

### 7.3 待用户的真机验收

经用户当次授权，由验收会话完成：上一轮→A6 同线覆盖只进一次维护页；旧 Web 未确认拦自动开始；pending 进维护、不自动重放；用户禁用/安全模式跨重启与更新保持；系统字节来自新 APK；安装/启停/更新/取消后的原生与 Web 状态可解释；短屏/横竖屏/语言切换不丢账本结果；不白屏、侧栏不重复、footer 齿轮唯一。故障注入限隔离测试根/测试包，不在正式数据强杀、坏插件或删环境。

## 8. 回退点、风险与待裁决

### 8.1 回退点

- 当前 `5b547868` 是第一阶段前的代码回退点；本文单文件提交可独立撤回，不动任何用户数据。
- 实现按 P0–P4 本地提交分界，Lead 可逐片回退代码；回退仍需更高 versionCode 的正式覆盖交付，不降级安装、不清数据、不由工作会话打 tag。
- guest 写者不升事务格式；回退读者仍认 format 1/2/3 和已完成标记。新增运行证据字段由旧消费者忽略；旧协议按缺字段→未知降级；未知记录和未确认候选不删除。
- 目录/profile 已经提交的状态不因 APK 回退自动回滚；用户通过已有预览/回滚/维护入口处理。禁用 marker 与安全模式意图不能被回退代码自动改写。
- 保留直接健康前代及现有 plugin-history；不因新增 UI/契约失败清理源码、依赖、外部源或会话。

### 8.2 风险与止损

| 风险 | 止损 |
|---|---|
| A5 尚未交付、A3/R3 在途 | 本阶段不依赖未合并实现；批准后由 Lead 统一 rebase/确认接缝，未确认不写跨域文件 |
| patch DSL、祖先禁用和动态表达式误解 | 不使用字符串扫描伪造有效态；只投影可证明 literal，无法证明附原因并保留未知；不执行表达式 |
| 单版本多 entry、同版本字节变化 | 身份核对与内容证明分开；moduleHash 不当内容摘要；不匹配即待核验 |
| 兼容性/P3 范围蔓延 | 复用 DSH 语义和现有来源；只读精确豁免，不加原生绕过，不扩大下载/脚本权 |
| 不归因的启动失败处理改变旧安全行为 | 明确故障只影响有证据目标；全局失败继续保留启动门禁/恢复入口，不能因未归因而报全健康或自动重启 |
| HMR/补丁链上游变化 | 本轮不打开 HMR；能力探针与版本/marker 明示降级，不把旧补丁存在当管理面健康 |
| 小卡片性能与文案变长 | 缓存签名包含结果结构与语言；保留投影增量刷新；中英和大字体验收，不用截断隐藏关键终态 |

### 8.3 交 Lead 的具体裁决项（2026-11-14 全部按建议批准）

| 项 | 建议 | 批准前的边界 |
|---|---|---|
| Q1 实施范围与依赖 | 按 P0→P4 完成 A6；允许先提交 P0/P1，但整轮出口含余片；A5 接缝由其 owner 提供 | 当前仍仅 docs-only；收到明确切片批准才开工 |
| Q2 安装/更新兼容性 | 已知 incompatible/unsupported-platform 拒绝提交；按 engines 优先、否则全部 DSH peers；缺少约束与检查失败分别表示，不造“兼容” | 不引入确认后强装原生入口；原生只读 DSH 精确豁免；另需绕过方案时重新评审 |
| Q3 生命周期脚本 | 维持现行允许策略，补真实执行/跳过证据，不移植新的 pendingBuilds 审批链 | 不增加执行或下载权限、不恢复旧原生审阅 |
| Q4 entry 管理与只读原因 | 本轮补 entry/patch 可见性和运行证据；写 entry/版本豁免继续走既有 DSH Web/CLI | 不新增原生 entry 写；DSH 管理面 readOnly 不用于取消现有原生紧急停用官方核心的明确能力 |
| Q5 无法归因的启动失败 | 账本标未知/未归因，保留整轮启动故障与维护屏障；只对明确失败条目作目标停用 | 不将浏览器 ready 单独当所有 entry loaded；更改与 A5 启动链协作 |

Lead 已批准本基线与 Q1–Q5 全部建议；P0→P4 每片独立提交/测试，不扩大 §5 写域。A5 启动链接缝先提最小方案，最终 descriptor 由 Lead 统一重算；实现后的定向验证、非实现者审查和用户真机验收仍是 A6 出口条件。

### 8.4 P2 / A5 最小启动身份生产者契约（Lead 2026-11-14 裁决）

- `startupId` 的唯一生产者为 A5 启动链；启动前由 A5 将它与该次 `generation`、`instance` 绑定并传入 guest 环境，具体字段名和传递方式由 A5 owner 定义。A6 不写 A5 文件、不新造关联 id 冒充 startupId；本条交 A5 owner 在其基线/handoff 采纳。
- A6 获准在 observer 与 PluginActivationHooks 现有 P2 写域增加有界同身份 sidecar 消费，固定身份字段为 `{identitySource,startupId,generation,instance,observedAt}`。只保存固定短码、ID、时间和有界状态，不能包含路径、源码、正文或异常消息；它是当前运行时投影，不是第二份持久账本。
- `identitySource=HOST_START_TICKET` 仅在已有 A5 startup 身份时可用；当前 A5 尚未提供时，只能以已有 generation+instance 标 `OBSERVER_SESSION`，`startupId=unknown`；连两者也没有则 `UNATTRIBUTED`。缺身份、旧实例或过期证据保留 unknown，不能据此宣称或目标停用某个失败插件。
- sidecar 不改变启动顺序、等待、权限或原始全局失败/维护屏障；observer 不因此求值禁用表达式、扫描目录或另跑插件入口。需要变更现有调用签名时，A6 先给 Lead 提最小签名及调用方清单。
- P2 必测三种 identitySource：有 A5 身份、仅 generation+instance、全部缺失；后两种不能伪造 startupId，缺证据不得改变既有全局失败与维护门禁。

### 8.5 P1 / A5 最小退出证明生产者契约（Lead 2026-11-14 裁决）

- A5 是进程归属与退出证明的唯一生产者。最小字段为 `operationId`、`backendTaskId`、`outcome=EXITED_CONFIRMED|UNKNOWN`、`evidenceSource`、`observedAt`，按需要附 `generation/instance`；A6 不读 `/proc`、不按 PID/端口/名称猜退出，也不自行签发证明。
- A6 的 [退出证明消费者](../../../app/src/main/java/com/deepseekharness/app/plugins/PluginExitProofGate.java) 默认没有 owner，全部拒绝。A5 将来只可通过 Repository 的 `bindExitProofOwner` 在无在途任务时绑定一次可信校验器，再向 `confirmRetainedExit` 提交真正 opaque token；自由文本、Map 和公开元数据记录本体都不能当证明。
- token 校验必须绑定精确 operation/backendTaskId，generation/instance 都不得缺失或以 null==null 降维；当前 Repository 能提供请求来源的 Web generation，真实执行 instance 尚未提供，因此生产保留票仍缺实例，消费者明确拒绝释放，不能宣称接线已闭环。证明时间不得早于本次保留、不得在未来、年龄不超过 30 秒；过期只拒绝证明，**不自动释放变更租约**。
- 同一 operation 可包含解析、提交、清理等多个实际投递，现 backendTaskId 是既有 M13 信号票。A5 owner 必须核对最后的真实执行实例并撤销先前投递的 token，不能只凭同名 ID 或当前字段重签旧结果。尤其 `discardPreviewConfirmed` 的旧 guest 调用不携信号票，A5 生产接线须覆盖它及 `runManager` 的实际调用上下文；A6 当前不通过猜测补齐。
- 类型化变更 `exitUnknown` 保留 Repository 的 Lease、RuntimeTasks/active 槽和信号文件，不推进后继、不清未知输入候选；另保留同 owner 的 detached guard，防止提交段已交还的原票使未知变更从维护屏障中消失。只读查询没有 mutation 槽位，不进入证明保留分支；退出/结果未知使目录陈旧，完成信号清理并交还共享租约与工作票，不自动重放。只有进程已确认闭合的变更 `effectUnknown` 才可走正常释放；线协议新增 `exitUnknown`，既有 `executionUnknown` 保留，旧 unknown 缺字段时保守处理。
- 有效 A5 证明只确认闭合，不把原操作改为成功；原效果仍待核验、同目标旧请求须重新读回。若信号清理异常，缓存的闭合事实仅可继续清理同一保留票，不再次运行 guest 或将旧 token 当新授权；active/guard 在清理完成前保持。
- **待 A5 交付后接线**：真实 opaque 生产者、执行代次关联、实例字段及冷启动归属恢复都未由本片实现。当前只能保持 unknown，不能靠 UI 确认、状态文字或通用批准解除。A5/PluginTask 实现和原有停止/维护顺序均未修改。

## 9. P0 切片实现与验证（2026-11-14）

- 已增加纯逻辑 phase/evidence/operation 映射；卸载中不折成加载中，过期和历史证据不能宣称当前 active/failed。旧别名集中兼容，未知非空操作阶段保守投影为待核验。
- 插件卡片短徽章区分排队/执行/提交/失败/取消/失效/拒绝/替代/超时/待核验；关键结果提示排在重启/更新提示之前，紧凑卡片保留首条时仍可见。开关仍反映已核验意图或在途请求，结果展示不回写配置或运行事实。
- 新增“已替代/已超时/正在卸载”三条短标签的十语译文；保留全部旧译文内容和原行尾，只对相应六个精确语言字节 pin 更新摘要，不削弱 source hygiene 检查。
- **真实纯 JVM**：使用现有 JDK 17 与缓存的 JUnit 4.13.2，直接编译生产类和既有测试，执行 PluginStateViewTest、PluginCardStateTest、PluginListPresenterTest，**36 tests / 0 failures**；没有 Android stub，不将它称为 Robolectric 通过。
- **已通过**：全树 Java format 1769/0、Kotlin format 2/0；core boundaries 25 tests；十语目录 11 tests；mobile gear 7 tests；UI i18n newFindings=0；source hygiene；当前文档链接 1309 和基线链接 104 均无错误；diff 空白检查。
- 固定格式器按锁文件 size/SHA 核验后缓存；固定构建输入按 Lead 指定 ZIP SHA `2c8dc3fa494a5faf6f65cafa966b47d234a3331c30396198005cedfccca82cad`（716787167 bytes）核对，再经 ci-assets 白名单/源码 pin 验证还原。缓存和归档不入提交。
- **Gradle / Robolectric 未完成**：先在缺少固定输入时失败；还原后真实 dsha-gradle 四个定向类调用已通过运行资产/描述符/语言前置，但 AAPT2 x86 二进制在 arm64 主机报 `bad machine` / `Daemon startup failed`，停在 processStandardDebugResources，未到单测。新 Fragment 渲染测试交 CI 补跑，不跳过资源或资产门禁来报告成功。
- P0 未修改受管资产或已枚举 launcher 文件，Gradle 前置实际生成/核验的 runtimeId 仍为 `bd0016c33e807a8e60cec8b3267e78fef0d003f618c083227cb651bd274eaabf`，**零维护**。P1–P4 与全轮出口继续执行，P0 不表示 A6 已完成。

## 10. P1 切片实现与验证（2026-11-14）

- guest 一次只读 Node 解析全部 bundle，使用固定 DSH anchor 的真实 js-yaml JSON_SCHEMA 和不透明 `!!js`，不加载 observer/Loader/插件、不继承任意 NODE_OPTIONS。profile/包 patch 字节和身份前后复核，writer 出现或读期间改变拒收快照；预算有界，未知退出按类型上报。
- `selected/installed/availability/nativeMarker/readOnlyReason` 与 entry 身份、profile 字面 override、原因码、patchRevision 分开。静态坏 manifest 保留可删除的目录行并标未知；不能把坏包回退 metadata 当竞态，也不能把解析不可用当确认无 entry。profile false 不覆盖祖先禁用，缺字段旧生产者按未知兼容。
- 未提交事务既拦成功目录快照，也拦新状态命令；等待 writer 锁后必须再次检查 pending。任何 marker（含坏类型/未知正文）都不能获得未经读回的 enabled=true；读回不一致返回 partial + intentVerified=false。
- 原生 [结果解析](../../../app/src/main/java/com/deepseekharness/app/plugins/PluginOperationResult.java) 校验真实结束/超时/截断和严格末行回执，拒绝重复或错类型字段；保留 errorCode/stage/application/restartReason，未知不靠中英文消息关键词分类。新增明确 exitUnknown 字段，效果未知且已闭合才释放，旧 unknown 缺字段保持保守。
- [队列](../../../app/src/main/java/com/deepseekharness/app/plugins/PluginOperationQueue.java) 保留结构化结果；退出未知锁存、旧进度不能降级未知，后继不启动。已知闭合的效果未知也不重放，同目标失败或未知后需新读回。配置开关拒绝时恢复投影值，不能留下乐观 UI。
- entry/profile 字面配置在原生详情显示，bundle 选择、禁用 marker、实际运行仍是独立事实。原生 entry/版本豁免写入口未新增；系统插件、移动壳字节/单实例齿轮和备份规则均未修改。十语新增短标签及固定结果文案齐全，旧译文保持原字节前缀，对应精确语言 pin 仅随追加更新。
- guest 回归：discovery **51**、真实固定归档 entry parser **7**、review **20**、transactions **14**、默认禁用机制 **9**，均无失败/错误/skip；十语目录 **11**，host manifest **255 项登记通过**。parser 专项独立归入 runtime/current_archive，直接验证归档 SHA，缺输入明确失败，不新增 skip。
- 独立 JDK/JUnit **79 项通过**；全树 Java format **1775/0**、Kotlin **2/0**、core boundaries **25**、文档主范围 **1309** 与本基线 **107** 条链接无错误。UI i18n 最终复核需保持 newFindings=0；整句参数模板取代翻译片段直接拼接，不增加基线例外。Robolectric/完整 Gradle/Lint/APK/设备测试仍受本机 x86 AAPT2 不可执行限制，未跑项交 CI/用户，不伪称通过。
- **受管输入已变，旧描述符有意未改**：source hygiene 当前只报告 plugin-manager.py 的 PINNED_BYTE_DIGEST；这是 main 在合并树统一重算前的真实失败，不能记成全绿，也不修改 pin 检查来绕过。manager 保留原全 CRLF，diff 空白检查使用 cr-at-eol，不整文件改行尾。A6 整轮仍计划一次维护页，数据/桥/发行身份不升级。

## 11. 后续接续点（P2–P4 尚未实施）

- 顺序保持 P2→P3→P4，各片独立提交/测试；当前不表示 A6 出口完成。A5 两类生产者契约见 §8.4/§8.5，默认没有证据就保持 unknown，不可通过普通确认放行。
- P2：复用 observer 现有唯一 id/moduleHash/parent/group 元数据和 sampler 真 Loader 读取，输出 entry phase/短诊断。moduleHash 是模块名摘要，不能冒充源码内容摘要；缺部署内容证明保留未核验。只对绑定完整、内容/版本匹配的明确失败作目标处理，未归因全局失败不误伤正常插件。
- P3 已对照源码 pin 验证的固定归档真实 app-boot：platforms 是 Node process.platform 非空白名单，不是 CPU/客户端类型；兼容检查 engines own 字段优先，缺省全部精确 DSH peer 前缀，includePrerelease=true，仅 peer 的 workspace:^/~/* 指当前 runtime。无约束与检查失败分开，不造兼容。
- 精确豁免真实结构为直接 `{package@exactVersion:[exactDshVersion]}`，只读、坏记录不放行，提交前重读；多包预检须在第一包 pnpm/脚本前完成。原生不新增强装/豁免写或 pendingBuilds 批准链。
- P3 更新保留旧 prepare-update argv，加 npm 精确更高版本可选参数与实际包版本/原安装版本二次复核；GitHub/Release 不静默转 registry。真实提交结果给 restart-required/no-hmr/stage；脚本证据只能报告真实准备路径，不声称逐个脚本执行完成。
- P4：结构化兼容跳过/管理面/补丁链/受管只读与覆盖受限原因，不能将 management 不可用当零插件；保留现有紧急停用核心能力。最终 main 重算 descriptor 并取得绑定最终提交的门禁，另需非实现者审查和用户真机验收。

## 12. P2 运行证据与保守归因（2026-11-14）

- 接手会话为 `66180613`；保留 `5f3deeeb` 的 P0/P1 基线，不 rebase。observer 只在启动读取元数据，心跳枚举真实 Loader；新增 entryId/moduleHash/parentId/group、phase 与固定诊断码，fiber 5 独立为 unloading。moduleHash 仍只是模块名称摘要，不作为源码内容证明。
- 同帧 activity 与有界 startup sidecar 只携带短码、ID、时间和状态；缺 A5 启动票据时只标 OBSERVER_SESSION/unknown，连会话身份也缺失时为 UNATTRIBUTED。生产未新增宿主票据环境变量，sidecar 自报 HOST 字符串没有目标停用权限。
- 当前缺少部署内容 fingerprint 生产者，原生保留 Loader 的 entry 观察并明确标记 CONTENT_UNVERIFIED；版本/实例/代次/父组/模块/截断/过期不符不能领取已核验 active。旧协议可读但不把缺字段变为成功，来源换代同步撤销 entry 与 bundle 证据，迟到心跳不覆盖目录配置。
- 全局失败将本轮 attempted 记为 unknown/STARTUP_FAILURE_UNATTRIBUTED，浏览器 ready 记 unknown/BROWSER_READY_UNVERIFIED；保持启用意图与原启动故障门禁。实体、名称、依赖缺失等现存 preflight 明确失败仍只处理对应目标；运行时目标停用仍待 A5 提供可信身份与内容绑定，本片不以文件 Map、UI 确认或状态文字绕过。
- 最终验证：JDK17/JUnit 的纯 util 与当前生产 Item/PendingReview 类体、Registry/Projection 共 **60 项**通过（生产 JSON 实现，移除测试的 Robolectric runner 后执行；不称为完整 Android 编译或 Robolectric）；纯采样 **17**、discovery **51**、review **26**、transactions **14**、十语目录 **11** 均零失败。全树 Java **1777/0**、Kotlin **2/0**，core boundaries **25**、UI i18n newFindings=0、主文档 **1309** 与基线 **107** 链接零错误，host manifest **256** 登记通过。
- sidecar/installed 投影最终收紧为 **128 KiB**，activity 总预算保持既有 **256 KiB**；动态摘要超预算标为 null（未知），不伪造空插件表。source hygiene 唯一未绿项仍是旧 descriptor 中 manager 的精确字节 pin，留 main 统一重算。仓库标准纯 JVM 入口实际在 javac 阶段缺少 Kotlin ShellQuote 输出而报 13 个 import 符号错误，未到测试；完整 Gradle/Robolectric/Lint/APK/真机未跑。真实 raw Loader 专项缺 app/build/test-runtimes/current.json，未进入测试；未下载或安装补环境。最终全量 `git diff --check` 与随后五路径 guarded 分片均命中已知 proroot SIGSEGV/139，不记通过；停止重试，另以 HEAD blob 对比的纯 Python 新增行空白检查收口。

## 13. P3 提交复核与兼容语义（2026-11-14）

- guest 复用同一份固定 npm SemVer，并在独立兼容动作中严格按锁定 app-boot 语义处理：own engines.dsh 优先，否则检查全部精确 DSH peers，includePrerelease=true，三个精确 workspace peer 别名指当前 runtime；坏类型和检查失败显式拒绝。runtime 版本来自实际 app-boot manifest，platform 来自 Node process.platform。无约束、精确豁免与兼容分别投影。
- 精确豁免只读，重复/坏记录不放行，提交前重读；已存在豁免文件的 inode/ctime/SHA 进入兼容证据。整批包在首个 pnpm/生命周期脚本前预检。唯一 writer 锁内复核实际候选、原版本、来源和兼容证据；同批自身已确认写入仅精确转移后项的来源文件证明，不重拍目标版本。第二包遇外部 ABA 则保留候选并返回已安装首包的 partial 回执。
- prepare-update 保留旧参数，可选 canonical npm 更高精确版本；GitHub/Release 不转 registry。启用、恢复、回退、退出安全模式和旧 marker 迁移均执行兼容检查，明确停用仍可用。提交回执给出 restart-required/no-hmr/stage，依赖准备记录只报告 package-manager-completed 或 existing-tree-verified，脚本逐项执行为 not-observed。
- Registry 私有持有变更票据 owner；入队、出队、候选与提交绑定来源 epoch、Web 就绪状态/实例和就绪事件 epoch。A→B→A 及同值迟到事件不能复活旧票。仅本 operation 的既有 exclusive 成功 owner 回调允许一次 generation+1、ABSENT、取消 revision+1 的精确转移；其他排队票失效。ABSENT 只是页面缺失，不是退出证明；迟到通知若在转移完成后到达会保守废票、保留候选，不自动续票或重放。A5 opaque 退出证明、PluginTask 和 unknown 租约保留均未修改。
- 原生显示兼容六态与只读豁免，预览拦不兼容/平台拒绝/检查失败；安装链接页请求只读来源观察，未绑定的旧请求仍拒绝，后续显式重试取新票。十语新增十三条状态/拒绝文案，真实生成目录 5782 条。
- 实跑：兼容专项 **29**（逐字节固定归档 SHA，执行真实上游纯函数对照）、downloads **22**、依赖 **10**、review **26**、transactions **14**、discovery **51**，合计 **152** 项零失败/skip；依赖使用本机已有 pnpm CLI 10.34.5，仅确认 package/--version，不声称 CLI 单文件 SHA。票据/生产 Item 类体/Registry/Projection 与纯 util **75** 项通过；结构化结果/兼容纯状态另 **11** 项通过；实际 Preview 类体与真实十语 **12** 个场景通过，均不冒称完整 Repository/Android 或 Robolectric。
- 门禁：Java **1781/0**、Kotlin **2/0**，边界 **25**，UI i18n newFindings=0，十语目录 **11**，host manifest **257**。标准 JVM 入口补入当前源新编译的 Kotlin ShellQuote 后，仍在既有 R.jar 缺 drawable/mipmap/layout/dimen 等资源符号时停止，未进入测试；不构造假 R 或跳资源门禁。完整 Gradle/Robolectric/Lint/APK/真机未跑。source hygiene 的 manager 精确旧 pin 仍留 main；git diff/numstat 的 proroot 139 不当通过，使用 HEAD blob 的新增行空白核验并保留失败事实。
- 最终合并树的纳管与 descriptor/runtimeId 由 main 按独立审查裁决执行；worker 未改描述符或输入清单。本条为 P3 交付时点，P4 与审查修复记录见后续章节。

## 14. P4 管理面、启动预检与覆盖原因（2026-11-14）

- observer 启动时只读一次当前 manifest 批次、兼容记录和补丁注册表，复用 P3 固定 helper 的非枚举导出做同轮兼容预检；不新建子进程、不运行插件、不改变 Loader。预检标为 PREFLIGHT，绑定精确包版本、profile、会话、runtime 版本/Node platform 和启动观察时间；检查失败/缺证据保留 unknown。**它不是 Loader 实际 skipped 或 failed 的证明**，现有上游自由文本 skip 原因没有被解析成状态码。
- 心跳仅观察 pluginManager 服务存在与缓存的管理保护 Set、已匹配的真实 owner entry，不调用 listPlugins/listBundles/protectsManager 或重新读 patch、禁用 getter、目录。管理面 absent/unknown 与已安装目录正交，不返回零插件掩盖不可用；bundle/entry 管理保护只允许有固定原因的 true 或 null，不把未命中当可写。原生核心紧急停用能力保持。
- patchChain 核对注册表版本与七个管理配方声明；齐全只标 present-unverified，缺失/错版本分别固定码。服务存在、marker 或配方声明都不冒充签名来源和实际模块字节健康。未新增配方或改变补丁链。
- activity/sidecar 同帧包含 management、patchChain、coverage；仍为 128 KiB sidecar、256 KiB activity。metadata/Loader/entry/字节预算受限输出至多八个固定原因码；动态摘要超限是 null，不当空表。只读状态经既有状态条和可滚动详情展示，关键操作结果继续优先。
- FileObserver 的 Coverage 是不可变内存快照，区分路径/目录/条目/深度/时间预算、观察器失败、路径不可读/变化/unsafe、overflow/invalidated。初始、后台、关闭及修复中不声明覆盖完整；独立回调只发布覆盖事实，不触发目录查询循环、不续目录 TTL。native startWatching 为 void，limited=false 仅表示当前有界枚举未发现限制，不是系统绝对监控成功保证。
- 原生管理缓存同时绑定来源与就绪实例；就绪通知撤销旧证据。空目录也发布管理状态变化，entry 模块/父组/组类型错配不能得到保护标记。最后心跳按 30 秒年龄上限下一毫秒安排一次内存失效，无全盘轮询；读取与发布期间被撤销的运行字段不能通过目录完成回调复活。
- 已执行专项：真实生产纯采样 **21**、observer 源码+固定 helper 的启动 metadata **17**（YAML/Loader 明确注入端口，不称真实 Loader）；原生状态/Item 类体/Registry/Projection 与纯 util **86**；完整生产 Detector 的真实 NIO/注入观察口 **11** 加 Engine 原样纯调度类体 **50**，共 **61**；实际管理 UI/State 类体与真实十语 **30** 个场景。新增管理/覆盖二十二条十语，真实目录 **5804** 条。完整 Repository/Fragment/原 Detector 的 Robolectric 接线测试已补而尚未运行。
- 最终门禁：全树 Java **1784/0**、Kotlin **2/0**；边界 **25**、UI i18n newFindings=0、十语目录 **11**、host manifest **258**、当前文档 **1309** 与本基线 **107** 链接零错误；固定兼容专项 **29**、移动齿轮 **7** 再次通过。source hygiene 唯一失败仍是旧 descriptor 的 plugin-manager.py 精确 pin。真实 Loader、管理 patch-chain 和 management-probe 三条专项均在缺 raw fixture 指针时 exit 1、未进入测试，不记为 skip 或通过；Gradle/Robolectric/Lint/APK/设备仍未跑。最终日志清单保存在本机 `/tmp/a6-p4-gates.json`。

## 15. 本轮实现交付与仍需完成的出口

- 接续本地提交：P2 `39453e7e`、P3 `ea892f22`，P4 独立提交号随回执给出。保留原分支基线，不 rebase/push/合并/tag/Release/安装/操作手机；声明保持 ACTIVE、整轮不可合并，交 main 安排非实现者审查。
- 可复核命令：`node tools/test-plugin-runtime-sampler.mjs`、`node tools/test-plugin-startup-metadata.mjs`、`python3 -B tools/test-plugin-compatibility.py`、`python3 -B tools/test-plugin-review.py`、`python3 -B tools/test-plugin-transactions.py`、`python3 -B tools/test-plugin-discovery.py`、`python3 -B tools/test-plugin-downloads.py`；依赖专项使用现有 `DSHA_TEST_PNPM_CLI=/usr/local/lib/dsha-pnpm/bin/pnpm.cjs python3 -B tools/test-plugin-dependencies.py`。测试只作用隔离临时根，不运行正式手机插件。
- 临时 JVM 回执脚本保存在本机 `/tmp/a6-p4-state-verify.py`、`/tmp/a6-p4-coverage-verify.py`，前者抽取当前 Item/PendingReview、使用真实 JSON 与完整生产 Registry/Projection/纯 util，后者抽取纯 Engine 调度体并编译完整 Detector；这些不是整个 Android 应用或 Robolectric 通过。实际 UI 类体十语回执在 `/tmp/a6-p4-ui-host`。
- 主线合并树按 main 的独立审查裁决处理：本轮六个新 util 视图不追加 launcherSources，plugins 树沿现有声明自动纳管；保留已合入主线输入后统一重算 descriptor/runtimeId 与 manager 精确 pin。worker 没有修改描述符或 managed-runtime-inputs。
- A5 startupId/opaque 退出证明的真实生产者、instance/冷启动归属仍待 owner 接线；缺内容部署证明仍 CONTENT_UNVERIFIED，exitUnknown 默认保留租约/屏障，不因 UI 确认、文字或普通批准释放。整轮维护页仍按最终合并树一次；设备/布局与覆盖升级验收、最终 CI、非实现者审查和用户合并批准不能由主机夹具替代。

## 16. 整轮独立审查修复（2026-11-14，59926c09 之后追加）

独立审查结论为 REQUEST_CHANGES（1×P0、1×P1、4×P2），以下逐项追加修复，不 amend 既有 P0–P4 提交，不 rebase。未借修复扩展 A5/PluginTask、维护页、备份或终端写域。

| 审查项 | 修复及回归 |
|---|---|
| P0-1 查询 exitUnknown 永久保留共享租约 | retainExit 标记、runManager 的退出未知标记和 RetainedExecution 准入均明确排除 query；只有队列当前 mutation 成功 retainUnconfirmed 才创建 guard。只读结果未知使目录陈旧，信号 close 后交还 lease/work，清理本身异常也不会为 query 创建永久保留；不改 mutation unknown 阻断。新增六条 Repository Robolectric 接线回归覆盖 timeout/null/IOException、真实预算截止、close 异常和查询期间排队的 mutation；原查询失败/排队超时也加释放断言。 |
| P1-1 非严格更高版本的预览清理 | prepare_update 在 inspect 已闭合且最终版本拒绝时，先通过本次 previewId→preview_path 清理该编号目录再抛原 typed 拒绝；不删除来源归档或其他预览。低版/同版各重复两次并核验 pending 不增、其他候选及已装版本逐字节保持。 |
| P2-1 缺实例/代次的退出证明 | Gate 对 expected 和 actual 任一 generation/instance 缺失都拒绝；缺宿主上下文时不请求 owner 降维核验。生产 RetainedExecution 的执行实例尚无 A5 producer，仍为 null，注释明确该状态不能释放；没有从 Web/PID/文案补造实例。 |
| P2-2 entry reader 环境继承 | _entry_read_node 同时移除 NODE_OPTIONS/NODE_PATH，保留 manager 原 CRLF。真实子进程确认两键不存在；恶意 preload/js-yaml 哨兵不运行，独立负控证明 NODE_PATH 模块夹具可实际执行。 |
| P2-3 空 afterSource | armStop 首先拒绝 null，保留当前 active 票不消耗；补空目标不抛异常且随后合法过渡仍可发起的负控。 |
| P2-4 sibling 原因互相污染 | bundle 身份/内容/覆盖公共原因在遍历前冻结；每个 entry 只继承该公共原因，局部条目错误仅参与 bundle 聚合。两种条目顺序下，错模块条目不能让另一个匹配 sibling 获得其错误原因。 |

测试收尾也已修正：通用 @After 不再临时签发证明救查询，先无条件断言没有共享租约。仅具名且显式声明的未知 mutation 测试，在断言非 query、UNCONFIRMED、队列 active 精确 request/id 后销毁测试拥有的真实对象，不调用生产确认、不推进后继。缺 A5 实例的 opaque proof 不可释放；未来完整绑定的清理重试正控仅在该具名夹具内反射补齐 expected 执行上下文，不声称生产接线已完成。

实际执行：`python3 -B tools/test-plugin-compatibility.py` **31/0**、`python3 -B tools/test-plugin-entry-reader.py` **8/0**；当前状态/Item 类体/Registry/Projection 与纯 util JVM **88/0**，独立 ExitProofGate/MutationTickets JVM **18/0**（含重复的票据类，不相加造总数）。另把当前生产完成/RetainedExecution 原样类体与真实 EnvironmentTaskGate/Queue 编译执行 **6** 个场景，任务/RuntimeTasks/UI 为明确夹具端口，仅用于核验查询清理分支、队列槽和变更保留，**不称 Robolectric 或整个 Repository 已执行**；回执位于 `/tmp/a6-review-query-completion-host`。新增六条 Robolectric 接线回归尚未在本机执行，既有 AAPT2/资源前提限制保持，不造 R、不跳门禁。

修复后门禁：全树 Java **1784/0**、Kotlin **2/0**、core boundaries **25/0**、UI i18n newFindings=0、host manifest **258**、主文档 **1309** 与本基线 **107** 链接零错误；source hygiene 唯一未绿仍为旧 descriptor 的 manager 精确 pin。命令/退出码/日志清单为 `/tmp/a6-review-fix-gates.json`。未重跑此前缺 raw fixture 的专项，未运行完整 Gradle/Robolectric/Lint/APK/设备。整批新增行以 HEAD blob/difflib 核验空白；不以历史 proroot SIGSEGV 输出冒充 diff 检查通过。

主会话后续事项：descriptor 重算、manager 精确 pin、绑定最终提交的 CI 与增量复审由 main 执行；本轮六个新 util 视图按审查裁决不追加 launcherSources。当前变更 exitUnknown 在生产没有完整执行实例的证明释放路径，需重启后核对维护，不能把重启当作成功。维护页在本工作会话排除写域内，交由 main/A5 owner 落地版本说明和页面文案，可用完整用户文案：**“插件变更结果无法核验时，会保留当前环境占用。请重启应用后到运行环境维护页核对；原候选和未完成事务不会自动重放，重启不代表变更已成功。”** 只读查询的未知结果不会触发上述永久保留。
