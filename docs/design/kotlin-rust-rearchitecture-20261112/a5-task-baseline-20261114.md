# A5 任务基线：运行环境与覆盖更新

> 日期：2026-11-14；状态：**第一阶段已获 Lead 评审批准；Q1–Q3 已裁决，S1 结构片已实现并完成本机有限验证，待独立审查；S2/S3 未开工**。
> S1 代码基线：`63b57efe5b3961429b6a03dd950a78abd5467b5e`；已 fetch 并无冲突重基，原文档提交 `d7193629` 重写为 `4cbc3cf1`（随后由 main 合入）。§8 的 S0 软件结果仍固定于 `d9b75bc5`，不迁记为 S1 通过；分支 `feat/a5-runtime`，owner `@a5-runtime`（session `545e6069`）。
> 依据：[迭代路径 A5 / §4 / §6 / §7](iteration-roadmap-20261112.md#5-各轮详情)、[ADR-0004](../../adr/0004-kotlin-rust-direction.md)、[覆盖升级契约](../../overwrite-upgrade-and-maintenance.md)、[运行引擎](../../runtime-engines.md)、[环境/数据/插件](../../runtime-data-plugin-architecture.md)、[端口与网络](../../ports-and-networking.md)、[仓库约束](../../../AGENTS.md)。

## 0. 本批结论与时间边界

A5 应收敛**既有状态所有者之间的读取与请求接缝**。目前已有应用级 Web 生命周期、环境任务票据、停止屏障、组件检查、受管事务和 APK 更新任务；再建工作台状态机，会使维护页、引导页、启动恢复页与一键更新出现更多独立判据。

S0 首批只新增本文，未改生产源码、测试源码、描述符、版本身份或进度看板。S1 已按 Lead 条件获准，仍不代表整轮实现或设备验收完成。

§2/§5 的既有 37 个源码引用目标已逐文件核对 `d9b75bc5..63b57efe`：这些文件无差异，旧锚点在新树仍对应相同代码；A4 新接口与 RC1 新语义另在 §5 补充，不能只靠锚点仍有效来继承旧测试结论。

| 时点 | 已核对事实 | 如何使用 |
|---|---|---|
| 开工 fetch / 建树 | `origin/main=d9b75bc5`，A3 slice2/3 v2 已在主线；A4 当时待合并 | 本文全部源码行号与测试结果均取该工作树，不使用侧分支实现作前提 |
| 调查期间主线前进 | `63b57efe` 含 RC1 与 A4 合入及描述符重算 | **S1 开工已无冲突重基到该提交**；后续 main 合入了重写后的文档 `4cbc3cf1`。S0 测试仍绑 d9；S1 重新验证 |
| 已发布身份 | build364，tag `dsha-a-su-build364-20261113-134000`，提交 `1545e104` | 进主线与已发布分别记账；本批不动发行线 |

路线图顶部与部分运行文档保留了历史状态、类规模和输入数量。实际接口以下表为准；只有 Lead 更新路线图进度。Rebirth 与 Mod 的发行身份也不在本批裁决，I1–I10 按**各自同包名、同签名发行线内部覆盖**执行，不以跨线安装代替覆盖验收。

## 1. 目标、非目标与状态所有权

### 1.1 目标

1. F02 工作台、整屏维护页、一键更新、启动恢复只读取同一组环境事实，提交类型化请求；启动、停止、重启继续走唯一生命周期与租约。
2. 保留当前受管更新的准备、切换、隔离试运行、健康验证、最终提交及失败回切；更新成功文字必须服从实际就绪证明。
3. 覆盖安装时正确区分 ready、latest、pending、busy、旧 Web 未确认和自动尝试身份；可见维护入口与可否自动执行分别判定。
4. 按 ADR 停止 APK 无人触发的自动检查段，保留用户明确发起的检查、下载、校验、系统安装器及覆盖后的维护门禁。
5. 钉住 I1–I10、旧会话 schema 与六类身份反例；先做结构切片，再改行为，每个切片可以独立回退。

### 1.2 非目标

- 不 push、合并、tag、Release、安装或操作手机；真机覆盖、维护页、白屏与侧栏验收交用户。
- 不写 `backup/`、`rust/`、`util/RustArchive*`，不写 PTY、终端 Fragment / Service、`RootShell*`；这些域分别归 A3/R3/A4。
- 不新建第二把维护锁、第二条 proot/proroot 命令链或功能层进程状态机；不在本轮做 Gradle 工程拆分、全包语言翻译、Compose 或 Gecko 接入。
- 不改包名、签名、版本号、桥协议、Ubuntu 基础版本、历史偏好键；不启用 journal format 4、Web session schema 2 或新的数据写格式。
- 不以清日志、清数据、卸载重装、禁用移动插件、延长超时或放宽 Web 门禁处理未知现场。

| 模块 | 唯一事实/执行 owner | F02 允许做的事 |
|---|---|---|
| M09 生命周期与租约 | 应用级 `HarnessSessionState` / `WebLifecycleController`；`EnvironmentTaskGate` / `RuntimeTasks` / `MaintenanceGate` | 观察代次、任务与结果，提交请求，不缓存一份可执行状态 |
| M10 命令与挂载 | `RuntimeExecution` → `LaunchSpec` → `RuntimeLauncher` → `ContainerRuntime` / `UserDataBindings` | 展示实际选择及诊断，不直接拼 argv、bind 或环境 |
| M11 受管安装 | `InstallRepository` / `InstallPipeline`；`BackupTask` / `EnvironmentMaintenance`，事务由 A3 数据层持有 | 展示检查与维护计划，按既有所有权提交检查/修复/更新/恢复 |
| M17 应用更新 | `UpdateRepository` / `UpdateEngine` / 下载服务 / 系统安装器 | 显式用户动作与真实阶段展示；APK 安装完成不等于运行时就绪 |
| M04 复用 | 当前出生身份、记录核验、退出证明；A4 接口重基后再核定 | 通过生命周期端口消费证明，不复制观测/信号算法 |
| M20 旧会话兼容 | `StoredWebSession` 的本机加密 schema 1 读写与恢复适配 | 保留读旧/未知拒绝语义；不迁移设备凭据或旧操作所有权 |

## 2. 现状证据（相对工作树的 file:line）

以下是 `d9b75bc5` 的代码事实；引用是方法入口/关键边界，不能把类存在当成验证通过。

| 主题 | 代码位置与现状 |
|---|---|
| 门面已拆 | [HarnessController:13](../../../app/src/main/java/com/deepseekharness/app/core/HarnessController.java#L13) 已持有 `web` / `hostConfig` 协作者；[HarnessSessionState:11](../../../app/src/main/java/com/deepseekharness/app/core/HarnessSessionState.java#L11) 持唯一 lifecycle、单线程 `dsh-io`、runs/currentRun、恢复与停止状态。不能沿用历史“57 KB 单体尚未拆分”的前提 |
| Web 准入 | [WebLifecycleController:982](../../../app/src/main/java/com/deepseekharness/app/core/WebLifecycleController.java#L982) 分别拒绝环境任务忙、未 ready/需维护、配置或宿主 pending；`START_STICKY`/看门狗不能用兼容但过期旧树拉起 Web |
| 普通启动/停止 | [StartupEnvironmentActions](../../../app/src/main/java/com/deepseekharness/app/core/StartupEnvironmentActions.java) 释放先前 launcher 并确认 guest；[WebLifecycleController:1532](../../../app/src/main/java/com/deepseekharness/app/core/WebLifecycleController.java#L1532) 管停止和强停；[WebLifecycleController:2108](../../../app/src/main/java/com/deepseekharness/app/core/WebLifecycleController.java#L2108) 慢鉴权只提示，等待本身不是故障 |
| 环境身份 | [EnvironmentAccess:12](../../../app/src/main/java/com/deepseekharness/app/core/EnvironmentAccess.java#L12) 查 latest；[EnvironmentAccess:43](../../../app/src/main/java/com/deepseekharness/app/core/EnvironmentAccess.java#L43) 自动尝试键为候选 runtimeId + APK 更新时间；受限入口键另取环境身份 + 更新时间；[EnvironmentAccess:60](../../../app/src/main/java/com/deepseekharness/app/core/EnvironmentAccess.java#L60) 是入口/服务共用启动门禁 |
| ready 与 latest | [RuntimeInstallationState:70](../../../app/src/main/java/com/deepseekharness/app/runtime/RuntimeInstallationState.java#L70) 查当前数据根、安装标记、Bash/入口、兼容与健康；[RuntimeDescriptor:122](../../../app/src/main/java/com/deepseekharness/app/backup/RuntimeDescriptor.java#L122) 另用 id/inputs 判 latest，二者不能合并成一个 Boolean |
| 自动一次 | [RuntimeMaintenanceStarter:80](../../../app/src/main/java/com/deepseekharness/app/ui/RuntimeMaintenanceStarter.java#L80) 按 ready/latest/busy/pending/候选/已尝试/旧任务/旧健康/旧 Web 判定，先持久化 `attempted_identity`，再提交更新；拒收也已消费此次自动尝试；[EnvironmentIdentity:43](../../../app/src/main/java/com/deepseekharness/app/util/EnvironmentIdentity.java#L43) 阻止未知/中断/取消现场自动重放 |
| 主入口 | [MainActivity:363](../../../app/src/main/java/com/deepseekharness/app/ui/MainActivity.java#L363) 异步聚合入口事实；[MainEntryMaintenance:15](../../../app/src/main/java/com/deepseekharness/app/ui/MainEntryMaintenance.java#L15) 保留本进程新出现且有 live owner 的事务留在主界面的例外，执行侧仍按磁盘 pending 拒绝 |
| 维护页 | [ExtractActivity:195](../../../app/src/main/java/com/deepseekharness/app/ui/ExtractActivity.java#L195) 渲染 pending=host pending 或未关闭 trial，ready=runnable 且 latest；[ExtractActivity:446](../../../app/src/main/java/com/deepseekharness/app/ui/ExtractActivity.java#L446) 缓存最长 15 秒；[ExtractActivity:469](../../../app/src/main/java/com/deepseekharness/app/ui/ExtractActivity.java#L469) 退出只登记受限身份，不写就绪标记 |
| 首次引导 | [OnboardingActivity:541](../../../app/src/main/java/com/deepseekharness/app/ui/OnboardingActivity.java#L541) 离开引导才写 welcomed；[OnboardingActivity:551](../../../app/src/main/java/com/deepseekharness/app/ui/OnboardingActivity.java#L551) 受限投影未包含 latest/trial；[DeployMonitor:212](../../../app/src/main/java/com/deepseekharness/app/ui/DeployMonitor.java#L212) 探测只取 ready/host pending/installed |
| 检查任务 | [InstallRepository](../../../app/src/main/java/com/deepseekharness/app/core/InstallRepository.java) 持任务与取消寿命；[InstallPipeline:17](../../../app/src/main/java/com/deepseekharness/app/runtime/InstallPipeline.java#L17) 六步检查/按需修复；检查成功不能提升为受管事务或网页鉴权成功 |
| 数据任务与屏障 | [BackupTask:169](../../../app/src/main/java/com/deepseekharness/app/core/BackupTask.java#L169) 取得环境票据后复核 pending 并持久化任务；[MaintenanceGate:79](../../../app/src/main/java/com/deepseekharness/app/util/MaintenanceGate.java#L79) 按票据→关终端→停 Web→运行任务围栏→其他进程退出→归档锁→owner/work→操作执行，finally 反向释放 |
| 租约退出不明 | [RuntimeTasks:100](../../../app/src/main/java/com/deepseekharness/app/core/RuntimeTasks.java#L100) 取得维护围栏；[RuntimeTasks:105](../../../app/src/main/java/com/deepseekharness/app/core/RuntimeTasks.java#L105) `retainUntilExit` 将 token 交退出监听；读失败或超时保留保护，Activity 销毁不得代替退出 |
| 用户确认的维护前停止 | [MaintenanceCoordinator:66](../../../app/src/main/java/com/deepseekharness/app/core/MaintenanceCoordinator.java#L66) 区分普通 exclusive 与 exclusiveUserConfirmed；[MaintenancePreStop:52](../../../app/src/main/java/com/deepseekharness/app/core/MaintenancePreStop.java#L52) 先普通停止，未确认才准备一次已核验强停候选，仍未知则不改原环境 |
| 普通停止的窄例外 | [WebProcessManager:1463](../../../app/src/main/java/com/deepseekharness/app/runtime/WebProcessManager.java#L1463) / [WebRecordedStop:127](../../../app/src/main/java/com/deepseekharness/app/runtime/WebRecordedStop.java#L127) 已允许同一受管 proroot bridge 在优雅停止未退时经双次身份复核升级 SIGQUIT/SIGKILL；不能写成“普通停止绝不升级信号”，也不能扩成通用强杀 |
| 受管更新 | [EnvironmentMaintenance:103](../../../app/src/main/java/com/deepseekharness/app/core/EnvironmentMaintenance.java#L103) 同 base 准备受管候选；[ManagedRuntimeTransaction:125](../../../app/src/main/java/com/deepseekharness/app/backup/ManagedRuntimeTransaction.java#L125) 固定受管根；[HostDataTransaction:197](../../../app/src/main/java/com/deepseekharness/app/backup/HostDataTransaction.java#L197) 复核后切换，在屏障内做健康校验，最后 finalized；并非“试运行前从未切过任何文件” |
| 健康与失败回切 | [EnvironmentMaintenance:135](../../../app/src/main/java/com/deepseekharness/app/core/EnvironmentMaintenance.java#L135) 在提交验证回调中执行真实隔离 trial、写健康、查 ready；失败仅在可确认无其他任务时回切；[EnvironmentMaintenance:167](../../../app/src/main/java/com/deepseekharness/app/core/EnvironmentMaintenance.java#L167) 健康提交后才注册内置插件，避免再次制造 `ENV_NOT_READY` 回滚 |
| 恢复路由 | [EnvironmentMaintenance:634](../../../app/src/main/java/com/deepseekharness/app/core/EnvironmentMaintenance.java#L634) 先检查记录可读/唯一，再分别恢复冷安装、trial、插件、重建、配置、受管、宿主及旧日志；这些日志由各自 owner 解释，UI 不能清理解除阻断 |
| 启动恢复页 | [StartupRecoveryActivity:190](../../../app/src/main/java/com/deepseekharness/app/ui/StartupRecoveryActivity.java#L190) 自组页面投影；[StartupRecoveryActivity:532](../../../app/src/main/java/com/deepseekharness/app/ui/StartupRecoveryActivity.java#L532) 转维护页；[StartupRecoveryModel:22](../../../app/src/main/java/com/deepseekharness/app/core/StartupRecoveryModel.java#L22) 后台读取持环境票据，仅只读争用可重试 |
| APK 自更新 | [MainActivity:627](../../../app/src/main/java/com/deepseekharness/app/ui/MainActivity.java#L627) 仍自动检查；[UpdateEngine:241](../../../app/src/main/java/com/deepseekharness/app/core/UpdateEngine.java#L241) 每进程一次；[UpdateActivity:123](../../../app/src/main/java/com/deepseekharness/app/ui/UpdateActivity.java#L123) 首次打开且无任务也检查；ADR 要求尚未全部落地 |
| 下载与安装不是运行时维护 | [UpdateDownloadService:50](../../../app/src/main/java/com/deepseekharness/app/UpdateDownloadService.java#L50) 恢复已发起 downloading；[UpdateEngine:432](../../../app/src/main/java/com/deepseekharness/app/core/UpdateEngine.java#L432) 下载完成只进入 ready；[UpdateRepository:160](../../../app/src/main/java/com/deepseekharness/app/core/UpdateRepository.java#L160) 单次领取安装；[UpdateActivity:369](../../../app/src/main/java/com/deepseekharness/app/ui/UpdateActivity.java#L369) 用户点击/未知来源授权/再校验后转系统安装器，不走维护停止链 |
| 旧 Web 会话 | [StoredWebSession:37](../../../app/src/main/java/com/deepseekharness/app/runtime/StoredWebSession.java#L37) 使用 noBackup + KeyVault；[StoredWebSession:78](../../../app/src/main/java/com/deepseekharness/app/runtime/StoredWebSession.java#L78) 仅读 schema 1；[StoredWebSession:129](../../../app/src/main/java/com/deepseekharness/app/runtime/StoredWebSession.java#L129) 仍写 schema 1；[StoredWebSession:173](../../../app/src/main/java/com/deepseekharness/app/runtime/StoredWebSession.java#L173) 退休必须比较原记录，旧回调不能删新记录 |

## 3. 重复状态与最小收敛

### 3.1 需要收敛的是真实接线，不是删除所有显示缓存

| 当前重复面 | 具体风险/事实 | 最小拆分 |
|---|---|---|
| 维护页、引导、部署胶囊、主界面横幅分别采样 ready/pending | 同一健康兼容旧树在维护页要更新，但引导完成页可能只看 ready；未关闭 trial 在渲染与退出输入中不一致 | 应用级只读事实采样器 + 纯投影，页面只保留观察/布局/滚动等呈现状态 |
| 维护页 15s、任务 pending 2s、横幅 2.5s、部署监视器独立轮询 | 外部应急恢复、任务换代、返回页面时可能显示旧结论；当前已有 onResume 刷新，不能删回归 | 缓存集中到读取层，以任务/候选/现场代次失效；提交前执行 owner 重新读盘，不把缓存当许可 |
| 组件检查与维护任务均有“运行/成功/失败” | 它们表达不同作业，不是可互换的环境状态；通用 RuntimeTasks 忙也不是“数据维护中” | 统一投影作业身份与正交结果字段，保留各作业结果；租约仍由既有 owner 掌握 |
| Web 控制器仍包含恢复/复核、启动准入、停止/强停 | 只是移动代码而另持 generation/runs/停止意图会造第二生命周期 | 若需结构切片，按协作者拆方法，全部借用原 session/队列；不扩 HarnessController 职责 |
| APK 更新与受管运行时更新都叫“更新” | APK ready/安装派发不是环境 latest/healthy；停 APK 自动检查不能取消候选自动维护一次 | 分开显示任务身份，工作台请求分别交 M17 与 M11；不强行合并两个事务模型 |
| M17 的 owner 尚为静态单例 | [UpdateEngine:31](../../../app/src/main/java/com/deepseekharness/app/core/UpdateEngine.java#L31) 持静态 instance/IO，页面仓库另持[安装领取状态:20](../../../app/src/main/java/com/deepseekharness/app/core/UpdateRepository.java#L20)；不能把当前实现写成已完成 Application 注入 | 先由应用组合根注入唯一 engine/队列，页面仓库只保留原请求领取语义；结构迁移与停自动行为分提交，确认销毁/旋转不重复下载或安装 |

**已证实的输入差异、尚未跑 Activity 故障复现**：维护页渲染计入 trial，但退出只传 host pending；引导受限入口未传 latest/trial。应先补“同一事实输入→各入口一致结果”的真实接线回归，不能只新增 helper 测试后宣布页面已同源。

### 3.2 拟定事实与请求契约（第二阶段评审输入）

只读事实至少包括：`installed`、`runnable`、`installedLatest`、运行时候选身份、APK 尝试身份、host pending（含可读/未知原因）、trial pending、配置修复 pending、任务 id/status/owner、Web generation/未确认旧会话、实际 runtimeMode、实际端口与鉴权状态。**所有凭据、完整 URL、用户路径与原始异常不进入公开投影**。

派生结论分开提供：是否打开维护页、是否允许自动尝试、是否可显示用户维护动作、是否受限进入、是否允许启动请求。保留主界面 live-owner 显示例外；运行准入仍复查权威记录。`pendingTrial` 读取失败不能在执行许可里降级成 false。

请求只允许表达意图，例如检查、按需修复、启动、停止、重启、更新受管运行时、恢复中断、回切健康前代、用户确认的强停。接纳后返回原任务/代次身份与明确结果；取消、拒收、失败、未知、部分完成不得被 UI 合并成“可安全再试”。超时不自动重放。

## 4. 切片、写域与出口

S0 docs-only 已由 main 合入；S1 按 §9 条件交付首个结构等价片（实际范围与回执见 §11）。S2–S4 及 trial/latest 行为修复仍需后续裁决；不把对本基线或 S1 的接收默认为整轮业务编码授权。

| 切片 | 内容与允许写域 | 依赖与可回退出口 |
|---|---|---|
| S0 调查基线 | 仅本文；活跃/完成声明不入提交 | 文档/边界/格式及可运行宿主回归如实记录；交 ready，等待 Lead |
| S1 结构与只读投影 | `core/` 新独立采样协作者、`util/` 纯投影及定向测试；接入 `ExtractActivity`、`RuntimeMaintenanceStarter`、`OnboardingEnvironment`、`DeployMonitor`、`EnvironmentUiStatus`、引导/主入口必要调用点 | 先用旧判据黄金向量证明结构等价，再单独提交 latest/trial 接线修复；入口不持有生命周期或写事务；退出不写 ready |
| S2 APK 更新 owner 与自动段收口 | `UpdateEngine`、`UpdateRepository`、`UpdateDownloadService`、更新页、Main/配置页；必要的 `DshaApp` 组合根注入先由 Lead 协调；保持历史偏好键可读 | 先以独立结构提交收口 Application owner/队列，再停无人触发检查/提示；用户手动检查、下载、再校验与系统安装器仍可用；已授权任务续接按 §9 推荐边界评审；不改维护自动一次 |
| S3 生命周期/租约与受管编排结构 | `WebLifecycleController` 的独立协作者、`MaintenanceCoordinator`、`RuntimeTasks`、`InstallRepository`、`EnvironmentMaintenance`、`ProotBootstrap` / `RuntimeExecution` / `RuntimeLauncher` 的必要适配与测试；不机械重写整个目录 | **先重基复核 A3/A4 接口**；只复用 M04 证明和 A3 事务；先结构后行为，保留原 session、锁顺序和失败现场；不进入禁止写域 |
| S4 F02 闭环与验收 | 现有 Fragment/XML 工作台入口与维护/恢复页连接；旧 schema 夹具、I1–I10 接线回归、运维文档与证据 | 启动/停止/重启/检查/更新/恢复/回切均可追踪到唯一 owner；系统安装器返回后重新观察；CI 全量与用户真机通过才讨论整轮出口 |

明确禁止顺带移动/修改 A3 数据层、R3 归档桥、终端实现、设备执行/识屏及独立应急运行根。后续如需新增受管源文件，必须同批纳入输入契约；新增纯 UI/投影文件是否受管须逐项说明，不为“零维护”故意遗漏实际执行字节。新协作者继续沿用现有 Java，语言迁移另按模块批准，不夹带逐文件往返翻译。

## 5. 依赖接口 / 需等待的合入（独立清单）

### 5.1 A3 数据事务与 R3

| 依赖 | `63b57efe` 已复核接口 | 后续依赖 / 不继承的证据 |
|---|---|---|
| 多根事务 | [HostDataTransaction:23](../../../app/src/main/java/com/deepseekharness/app/backup/HostDataTransaction.java#L23) `CommitCheck.verify/mayRecover`；[HostDataTransaction:82](../../../app/src/main/java/com/deepseekharness/app/backup/HostDataTransaction.java#L82) prepare；commit/recover 固定摘要与目标 | 退出不明必须 `mayRecover=false`；A5 只调用、不另写事务或恢复解释器 |
| 受管运行时 | [ManagedRuntimeTransaction:125](../../../app/src/main/java/com/deepseekharness/app/backup/ManagedRuntimeTransaction.java#L125) prepare、[ManagedRuntimeTransaction:187](../../../app/src/main/java/com/deepseekharness/app/backup/ManagedRuntimeTransaction.java#L187) commit(health, mayRecover)；旧 mapping 1/2 只读兼容 | 受管根不含用户目录，至少留直接健康前代；健康是本轮真实字节证明，不从版本字符串推导 |
| pending 与恢复 | [EnvironmentMaintenance:634](../../../app/src/main/java/com/deepseekharness/app/core/EnvironmentMaintenance.java#L634)、[MaintenanceCoordinator:53](../../../app/src/main/java/com/deepseekharness/app/core/MaintenanceCoordinator.java#L53) 现有保守入口 | A3 结束前复核其可读/唯一/源变动/PlanView 拒绝语义；新 RC1 unknown 决策不得被 F02 放行入口覆盖 |
| A3 新计划与归档旁路 | [A3 契约基线](a3-task-baseline-20261113.md) 区分 PlanView 投影、归档读者权威与 JVM 整份摘要 | slice2/3 已入 `d9b75bc5`，不代表 A3 整轮完成；R3 archive 接线/slice4 及出口仍待其 owner 交付，A5 不直接调用 `RustArchive*` 或假定 Rust 默认开启 |
| RC1 修复与发行门禁 | S1 已重基包含 `278a446a` 的 `63b57efe`；[Rc1MigrationCache:101](../../../app/src/main/java/com/deepseekharness/app/runtime/Rc1MigrationCache.java#L101) 将记录读失败映射 UNAVAILABLE，[四类结果:24](../../../app/src/main/java/com/deepseekharness/app/runtime/Rc1MigrationCache.java#L24) 不把未知当普通 cache miss | 已合入，无需再等该分支；S1 不修改或直接调用这些内部口。E=C 的 unknown 阻断继续保持，不因投影视图将旧历史证明提升为本次许可；CI 仍绑定各自提交 |

### 5.2 A4 进程身份与终端屏障

S1 已重基到包含 A4 的 `63b57efe`，复核 [MaintenanceCoordinator:193](../../../app/src/main/java/com/deepseekharness/app/core/MaintenanceCoordinator.java#L193) 仍调用 `TerminalSessionOwner.closeAllAndConfirm(5000)`；新 owner 的 [closeAllAndConfirm:491](../../../app/src/main/java/com/deepseekharness/app/core/TerminalSessionOwner.java#L491) 串行关闭 PTY/简易会话，PTY 先覆盖尚未发布标签的 fork。[TerminalExitPolicy:76](../../../app/src/main/java/com/deepseekharness/app/util/TerminalExitPolicy.java#L76) 保留 guest→launcher 与逐次核验，[自然退出双扫:139](../../../app/src/main/java/com/deepseekharness/app/util/TerminalExitPolicy.java#L139) 跨 20ms 观察间隔，未知继续保留保护。S1 不写这些 A4 文件。既有 [WebSessionProof](../../../app/src/main/java/com/deepseekharness/app/util/WebSessionProof.java)、[WebProofStop](../../../app/src/main/java/com/deepseekharness/app/util/WebProofStop.java)、[WebProcessSession](../../../app/src/main/java/com/deepseekharness/app/core/WebProcessSession.java)、[WebProcessManager](../../../app/src/main/java/com/deepseekharness/app/runtime/WebProcessManager.java) 引用文件与 d9 相同；A4 的软件/设备边界见其[实现回执](a4-implementation-evidence-20261114.md)，不迁记成本会话实测。

| 依赖面 | A5 要消费的契约 | 合入门禁 |
|---|---|---|
| 出生与观测 | boot、PID、starttime、完整命令、UID、generation/instance/nonce；两次观测一致；区分活态/退出/未知 | 不复刻 PTY fork/exec 握手，不在 Java 启动后补读一次当作出生证明 |
| 退出与租约 | 关闭终端后由会话 owner 确认退出；guest 与 launcher 分别证明；未知继续保留 token | 不以 Activity 消失、launcher handle 返回、cancel 或 timeout 释放维护许可 |
| 强停与 T91 | 候选一次消费；实际发信号前重新核对；覆盖例外才可 guest→旧 launcher；普通 proroot bridge 的既有窄升级保持 | 不按裸 PID/端口/名称/进程组；不扩到当前 launcher、终端、虚拟屏或 trial |
| A4 已合入 | 原 `0971a2d4` 已经重基并随 `63b57efe` 进入主线；S1 本树已含该实现且已复核以上接口 | 无 A4 合入等待项；保留其设备未跑矩阵，S3 再复核调用契约。不得将代码在树里或 A4 CI 绿当作本会话设备通过 |

### 5.3 M20 与 Rust 的边界

本机 Web 记录继续**读写 schema 1**；未知 schema、解密失败、字段不全、记录被替换均保留原件并拒绝接管/发信号。升级时 appIdentity 差异既参与[旧会话恢复兼容性判断](../../../app/src/main/java/com/deepseekharness/app/core/WebLifecycleController.java#L204)（[存活但不兼容的会话可成为 UNKNOWN](../../../app/src/main/java/com/deepseekharness/app/core/WebSessionRecovery.java#L101)），也是 T91 旧版 launcher 退休的必要条件之一；它不能替代 boot/UID/出生/代次/双次核验，也不单独授予发信号权限。不得把鉴权 URL、任务 token 或 Keystore 状态写入用户备份。若需额外诊断，用可选旁路记录，不启用 schema 2。

路线图 A5 的“六类身份夹具 Java/Rust 差分”出口已由 Lead 修订：**六类身份逐字段黄金/差分属于 JVM 侧 P0；Rust 不参与身份取得、核验或进程归属，只继续 A3 的 plan/digest/archive 旁路。** 这与 [ADR-0004 决定四](../../adr/0004-kotlin-rust-direction.md#决定四rust-首期只做旁路计算与计划校验) 的范围一致。目前没有 Rust 身份 ABI，不能声称已完成 Java/Rust 身份差分；将来若需只读判据，必须另立任务与评审，不在 A5 扩 FFI 或旁路调用点。

## 6. runtimeId、维护页与零维护判据

S0 调查身份为 `f02b4465e638c20b2a2f3d1be7ca022704d6742b601c83ac5da7c0f86372b9a3`，205 资产 / 490 启动输入。S1 重基后的[描述符:2](../../../app/src/main/assets/runtime-descriptor.json#L2) 仍 `version=1`，`runtimeId=8598a8dbf15d48773a41ff41e89935a9effcb5faaa0edc2b82f17dda223b5193`，205 资产 / **491 启动输入**（A4 纳入 TerminalExitPolicy）；runtime 63 项、ui 0 项。base `10`、DSH `0.2.2-rc.1`、`dataWrite=dsh-0.2.2-rc.1`、桥协议 `3` 保持。S1 不重写该描述符；不拿 S0 数量冒充新树核验。

[单一输入清单:623](../../../app/src/main/assets/managed-runtime-inputs.json#L623) 的 launcherTrees 收 runtime/backup 等目录，core 只收显式项；[清单:635](../../../app/src/main/assets/managed-runtime-inputs.json#L635) 的 launcherSources 与 generatorSources 另列。**不是全部 core/util 都受管**：当前 EnvironmentAccess、EnvironmentMaintenance、MaintenancePreStop、RuntimeUpgradeState、EnvironmentIdentity、UpdateEngine/Repository/DownloadService 不在该启动摘要集合内；移代码时必须同时评审覆盖边界。

[输入枚举:107](../../../tools/runtime_input_contract.py#L107) 同时收 main/java 下的 Java/Kotlin，拒绝重复 key；新源根/Gradle 模块不能无声移出清单。[描述符生成:75](../../../tools/prepare-runtime-descriptor.py#L75) 对契约字段与内容 SHA 计算身份，APK UI 版本不进 runtimeId；清单自身也属于资产摘要。[来源核验:28](../../../tools/verify-runtime-descriptor-source.py#L28) 只对具名离线归档允许缺失递延，其他成员和源字节都须匹配。

| 变更类型 | 预期覆盖行为 | 必备证据 |
|---|---|---|
| S0 仅本文 | **零维护**，描述符/受管输入零 diff | 提交路径白名单、源字节核验；不运行 `--write` |
| 纯 UI/文案或未入清单的展示结构 | 只有全部受管内容/成员都相同才能主张零维护 | 比较 runtimeId/inputs/launcherInputs，并验证没有实际执行源码被漏掉 |
| 改 runtime 既有文件、显式 core/util 或新增执行源并纳入清单 | 同 DSH 也须重算身份，**覆盖后一次整屏维护页是预期行为** | 最终合并树统一重算/核源字节/核 APK 内容；旧会话未确认只拦自动开始 |
| 同 base 普通 dsh/受管补丁更新 | 替换受管树，用户目录留原位 | 不递增 Ubuntu base，不抬 descriptor 主版本/dataWrite |
| 基础环境变化/用户显式重建 | 保数据快照与重建事务 | 不把它当普通 UI 更新的副作用；A3 事务和空间预检不可绕过 |

“首次维护页”验收必须分别记录首次空环境部署和同发行线第一次覆盖。首次部署仍遵守欢迎/授权/完成路径；覆盖更新必须有整屏维护与一键动作。成功后再次冷启动应无重复自动维护；同候选失败/中断/旋转/重建不自动重放。对同候选已尝试且有健康前代的现有受限继续规则保持精确，不写成“所有非 latest 树永远绝对禁用”。

## 7. I1–I10 回归矩阵

“既有”仅表示存在回归入口；本次实跑范围见 §8。每行都须在第二阶段完成调用点验证，纯 helper 通过不代表 Activity/服务接线通过。

| # | A5 必守结果与反例 | 既有回归入口 | 第二阶段/设备补证 |
|---|---|---|---|
| I1 | 兼容健康但非当前候选仍需要维护；322→323、323→324、只改受管输入 | [OverwriteUpgradeGateTest:76](../../../app/src/test/java/com/deepseekharness/app/backup/OverwriteUpgradeGateTest.java#L76) | 实际 Main→维护页；同 DSH 补丁覆盖 |
| I2 | runnable 不等于 up-to-date；更新仍主操作 | [RuntimeUpgradeStateTest:8](../../../app/src/test/java/com/deepseekharness/app/util/RuntimeUpgradeStateTest.java#L8)、[MaintenanceFooterPolicyTest](../../../app/src/test/java/com/deepseekharness/app/util/MaintenanceFooterPolicyTest.java) | 同事实注入 Extract/Onboarding/胶囊/横幅，含 trial pending |
| I3 | 始终可见整屏维护页和一键更新 | [OverwriteUpgradeGateTest:203](../../../app/src/test/java/com/deepseekharness/app/backup/OverwriteUpgradeGateTest.java#L203)、[MainEntryGateTest](../../../app/src/test/java/com/deepseekharness/app/ui/MainEntryGateTest.java) | 保旧 Web/冷覆盖两场景；失败态恢复/应急/导出/受限入口可达 |
| I4 | 未确认旧 Web 只拦自动，不拦页面；用户动作才进入确认停止链 | [EnvironmentIdentityTest:47](../../../app/src/test/java/com/deepseekharness/app/util/EnvironmentIdentityTest.java#L47) | 自动尝试键先写与任务拒收、读失败、进程重建的真实接线 |
| I5 | 旧树+新 APK 的首个新候选仍拒绝看门狗/START_STICKY 直启；不放宽门禁治白屏 | [WebLifecycleResponsivenessTest](../../../app/src/test/java/com/deepseekharness/app/core/WebLifecycleResponsivenessTest.java)、[WebLifecycleStartProtectionTest](../../../app/src/test/java/com/deepseekharness/app/core/WebLifecycleStartProtectionTest.java) | 后台服务先于 Main 重建，旧 generation 迟到结果不能发布 ready/鉴权 |
| I6 | UI-only 不重建；受管输入/成员变化须补丁维护 | [OverwriteUpgradeGateTest:129](../../../app/src/test/java/com/deepseekharness/app/backup/OverwriteUpgradeGateTest.java#L129)、[输入契约测试](../../../tools/test-runtime-descriptor-inputs.py) | 最终包的零维护反例与受管变化正例分开记录 |
| I7 | 同候选自动一次；中断/取消/旋转不重放；受限入口不写就绪 | [EnvironmentIdentityTest](../../../app/src/test/java/com/deepseekharness/app/util/EnvironmentIdentityTest.java)、[FirstRunWebStartTest](../../../app/src/test/java/com/deepseekharness/app/ui/FirstRunWebStartTest.java)、[OnboardingActivityTest](../../../app/src/test/java/com/deepseekharness/app/ui/OnboardingActivityTest.java) | 引导/维护退出同输入；trial-only/读失败/外部恢复后重查；旧首启票不能越过 Stop |
| I8 | 移动插件自有属性+实测几何；同一 footer 只一颗齿轮 | [齿轮契约](../../../tools/test-mobile-gear-contract.mjs) | 首次无连接指示灯与连接后均不白屏/重复；不能以停插件过关 |
| I9 | 维护自行关终端、停 Web、确认 guest/launcher、持围栏后才切换；未知保留原环境 | [MaintenancePreStopTest](../../../app/src/test/java/com/deepseekharness/app/core/MaintenancePreStopTest.java)、[MaintenanceGateTest](../../../app/src/test/java/com/deepseekharness/app/util/MaintenanceGateTest.java)、[WebRecordedStopTest](../../../app/src/test/java/com/deepseekharness/app/runtime/WebRecordedStopTest.java)、[WebForceStopGateTest](../../../app/src/test/java/com/deepseekharness/app/core/WebForceStopGateTest.java) | 用户确认强停与底层 proroot bridge 窄升级分别测；不用超时冒充退出 |
| I10 | 仅 APK 身份不同的记录允许双次复核后 guest→旧 launcher；guest 未退不碰 launcher | [WebOverwriteRetireTest](../../../app/src/test/java/com/deepseekharness/app/runtime/WebOverwriteRetireTest.java)、[WebProofStopTest](../../../app/src/test/java/com/deepseekharness/app/util/WebProofStopTest.java)、[WebSessionProofTest](../../../app/src/test/java/com/deepseekharness/app/util/WebSessionProofTest.java) | PID 复用/出生变/命令变/boot 变/他人 UID/记录不可读六类；加两次观察变、当前实例、trial、终端不受影响 |

另保留 [RuntimeTrialRecordsTest](../../../app/src/test/java/com/deepseekharness/app/backup/RuntimeTrialRecordsTest.java)、[TrialGuestCleanupTest](../../../app/src/test/java/com/deepseekharness/app/runtime/TrialGuestCleanupTest.java)、[TrialRecoveryTest](../../../app/src/test/java/com/deepseekharness/app/runtime/TrialRecoveryTest.java)、[ManagedRuntimeTransactionTest](../../../app/src/test/java/com/deepseekharness/app/backup/ManagedRuntimeTransactionTest.java) 的中断/半提交/回切用例；trial 实际 runtimeMode、原生模块、实际端口鉴权、会话写后重开、网页握手与退出均须完整证明。运行时回切不恢复旧对话快照。

M10 补 [RuntimeLauncherTest](../../../app/src/test/java/com/deepseekharness/app/runtime/RuntimeLauncherTest.java)、[ContainerRuntimeEnvironmentTest](../../../app/src/test/java/com/deepseekharness/app/runtime/ContainerRuntimeEnvironmentTest.java)、[RuntimeHostPortsTest](../../../app/src/test/java/com/deepseekharness/app/runtime/RuntimeHostPortsTest.java)：引擎/加载器快照、argv/env/binds、实际 runtimeMode，不加按超时自动换引擎。端口保持自动 0/本机记忆、实际鉴权发布；[WebPortPolicy](../../../app/src/main/java/com/deepseekharness/app/util/WebPortPolicy.java) 的 bind-race helper 没有生产调用，不能宣称已经自动重试。

## 8. 第一阶段软件验证与实际限制

本批未改行为，不新增镜像实现测试。全量单测、Lint、APK、签名与发布验证由最终提交的 CI 执行；不沿用历史绿回执。本机未安装、未读屏、未发信号给设备进程。

| 检查 | 本轮结果与证据范围 |
|---|---|
| 定向 Gradle（9 类） | `dsha-gradle :app:testStandardDebugUnitTest` 指定 OverwriteUpgradeGate、RuntimeUpgradeState、MaintenancePreStop、WebProofStop、WebOverwriteRetire、MaintenanceGate、InstallPipeline、RuntimeLauncher、ContainerRuntimeEnvironment。**exit 1，未进入单测**：prepareBackupAssets 缺新树离线固定输入 `dsh-runtime.bin`；不能报为测试通过或业务回归失败 |
| 当前源纯 JVM 回归 | JDK17 + 既有 JUnit4.13.2 / Hamcrest / 仓库锁定备份依赖，生成真实文案到临时目录，以当前源码 `-sourcepath` 编译，运行下列 12 类：**73 项通过，exit 0**。无 Android 适配器/进程信号，不能替代上行 Gradle、M04 或设备证据 |
| JVM 选择 | RuntimeUpgradeStateTest、EnvironmentIdentityTest、MaintenanceFooterPolicyTest、MaintenanceOwnershipTest、WebRuntimeFallbackTest、WebLifecycleTest、RuntimeTaskRegistryTest、EnvironmentTaskGateTest、MaintenanceGateTest、MaintenanceRegistrationTest、MaintenancePreStopTest、OverwriteUpgradeGateTest；原计划额外身份类的临时驱动因缺 Kotlin ShellQuote/Gson 编译依赖而未执行，收窄为可由当前 Java 源完整编译的上述集合，未使用旧 class 或替身 |
| 架构边界 | `python3 tools/test-core-boundaries.py`：**25 项，exit 0**；本批不改边界基线 |
| 受管源字节 | `python3 tools/verify-runtime-descriptor-source.py`：**exit 0**，核 199 个现有资产、490 个启动输入；六份具名离线归档缺失仅递延，不冒充完整包核验 |
| 描述符/Gradle 消费契约 | `python3 tools/test-runtime-descriptor-inputs.py`：**exit 0**，103 安装项、205 资产、490 启动输入；launcherTree 的 Gradle/身份集合一致（251 文件） |
| 全生产输入枚举 | `python3 tools/test-runtime-input-contract.py`：18 项中 1 error（同一缺失离线归档），**exit 1**；保留失败，未跳过该项来宣称全绿 |
| 移动齿轮契约 | `node tools/test-mobile-gear-contract.mjs`：**7 通过、0 fail/skip，exit 0** |
| Java / Kotlin 格式 | `python3 tools/format-java-tree.py --check --offline --jobs 1 --java /opt/jdk17/bin/java`：**1775 文件、0 待格式化，exit 0**；同参 Kotlin 检查：**2 文件、0 待格式化，exit 0**。首次 JDK 路径与本树格式 jar 缓存缺失导致预检 exit 2，补全路径并仅复制现有锁定工具到本树忽略的构建缓存后通过；未改源码 |
| 文档链接 / diff | `python3 tools/check-document-links.py docs/design/kotlin-rust-rearchitecture-20261112/a5-task-baseline-20261114.md`：**1 文档、112 链接、0 warning/error，exit 0**；默认维护文档集合：**56 文档、1309 链接、0 warning/error，exit 0**；`git diff --check` 通过，跟踪源码零 diff。链接检查只证目标存在，不证 fragment 语义 |
| 未跑 | Activity/Robolectric 接线全组、真实系统安装器、覆盖安装/维护页/首次部署、M04 真实退出、proot/proroot/ROM 矩阵、全量单测/Lint/APK/签名；§7 对应设备项均待用户 |

既有设备夹具还需要先核对目标身份与现有门面：本树 [RecoveryInstrumentation:117](../../../app/src/androidTest/java/com/deepseekharness/app/core/RecoveryInstrumentation.java#L117) 仍反射旧门面字段，[UpdateProcessInstrumentation:67](../../../app/src/androidTest/java/com/deepseekharness/app/core/UpdateProcessInstrumentation.java#L67) 仍有旧包名；它们的存在不能证明当前 Mod/Rebirth 设备验收可直接执行。A4 在后续主线已修过部分终端夹具，第二阶段应重基后精确重看，避免重复修或继承错误结论。

## 9. 风险、未决项与 Lead 评审请求

| # | 风险/未决点 | 推荐与解除条件 |
|---|---|---|
| Q1（已裁决） | 六类身份差分的语言归属 | **六类身份逐字段黄金/差分属于 JVM 侧 P0；Rust 不参与身份取得、核验或进程归属，只继续 A3 的 plan/digest/archive 旁路。** 将来 Rust 只读身份差分须另立只读判据任务与评审；A5 不扩 FFI 或旁路调用点 |
| Q2（已裁决） | 自动段与用户已发起任务续接 | 只停无人触发的新检查/新提示/新任务；用户明确发起的下载/安装意图绑定原任务、摘要与领取 token 后可续接；未知先观察，不重新派发安装。该产品行为不进入 S1，仅冻结边界 |
| Q3（已裁决） | S1 的显示与执行边界 | **入口显示与执行准入保留两个目的，不合并成一个布尔。** 保留 live-owner 例外；采样与纯投影分别测试；先交结构等价提交，再独立处理 trial/latest 接线 |
| R1 | A3/A4/RC1/A6 并行，重基后事实或 owner 变化 | 第二阶段先 fetch、重基、逐接口复核，协调实际写文件。A6 插件 writer、A3 事务、A4 终端不归 A5 重写 |
| R2 | 抽新 core/util 类时从显式清单漏出，导致真实行为变却 runtimeId 不变 | 对照 launcherSources/trees/generatorSources 与消费入口；最终合并树统一重算并核源/包字节 |
| R3 | UI helper 全绿却页面仍白屏/循环 | 必须补真实接线、trial-only/读失败/外部恢复/旋转用例，再由用户完成两种覆盖与 UI-only 反例；不放宽 I5 |
| R4 | 维护成功/错误/取消被合并展示为 ready 或 safe retry | 保留任务、现场和代次身份；退出未知保留 lease；恢复由 owner 执行，取消不清 pending |

Lead 于 2026-11-14 审阅原 `d7193629` 后认可本基线，并按上表裁决 Q1–Q3。**仅 S1 获条件开工批准**：开工前 fetch/rebase 当时主线（本次为 `63b57efe`），冲突即停报；新树重核 §5/§9 接口与引用；只做同事实投影、结构等价与对照回归，不改行为、版本身份或 runtimeId。若出现受管输入变动，只交精确清单，由 main 在最终合并树重算描述符。继续遵守禁止写域，不创建第二把维护锁或第二条命令链。S1 独立提交与实测后交 main 安排独立审查，再决定 S2/S3；S3 前必须复核 §5。**这不授权本会话 push、合并、发布、安装或设备动作**。

## 10. 回退点与两阶段门禁

- S0 回退就是撤回本文，不涉及运行时/用户数据；本批 runtimeId 不变。
- S1/S3 每次只移动一个所有者的结构接缝，以旧输入/结果作对照；不改变 session schema 或事务格式，提交级可回退。投影修复与结构移动分开提交。
- S2 单独提交自动段入口收口，保留旧偏好键与手动路径；回退不会重写已下载候选/用户数据，未知安装不能自动重派。
- 受管更新失败走既有事务回切；退出未知不回切仍可能被使用的树。健康前代也须经当前 launcher/数据兼容与真实 trial，不能只认“旧目录存在”。
- 正式发行回退由 main + 用户决定，使用更高 versionCode 发布旧代码，不降级安装、不清数据；APK 回退不等于恢复用户对话或数据格式。
- 第一阶段已获批准；S1 独立提交后交 main 安排非实现者审查，再决定后片。全量/Lint/APK 以绑定提交的 CI 为准；真机覆盖/维护/白屏证据由用户补齐后，才更新 A5 整轮完成状态。

## 11. S1 结构切片（基于 63b57efe）

本片收敛三条现有只读路径：部署胶囊、主界面横幅、引导页直入判定。新增 [EnvironmentUiFacts](../../../app/src/main/java/com/deepseekharness/app/util/EnvironmentUiFacts.java)（无 Android 的无状态采样与不可变结果）和 [EnvironmentUiReader](../../../app/src/main/java/com/deepseekharness/app/ui/EnvironmentUiReader.java)（原读口的无状态适配），分别接入 [DeployMonitor](../../../app/src/main/java/com/deepseekharness/app/ui/DeployMonitor.java)、[EnvironmentUiStatus](../../../app/src/main/java/com/deepseekharness/app/ui/EnvironmentUiStatus.java)、[OnboardingEnvironment](../../../app/src/main/java/com/deepseekharness/app/ui/OnboardingEnvironment.java)。

- 部署路径仍按 runnable → host pending → installed 读取；任一步 RuntimeException 使 runnable=false，**保留失败前已读到的 pending/installed**，不重试、不额外查询。
- 横幅仍按 runnable → latest →（必要时）主入口 live-owner 显示策略 → 配置修复状态读取；纯判定继续复用 RuntimeUpgradeState，不把“能保留页面”提升为执行许可。
- 引导仍按 maintenanceBusy → host pending → runnable → requiresMaintenance 短路；外层异常仍按原入口返回 false。全部旧缓存寿命、线程/handler、发布代次、异常出口不变。
- 维护详情页的逐字段缓存没有迁移到一次性快照：那会改变读取中途失败时已写入字段的时序。其退出/trial/latest 接线、自动一次、Main 的实际执行准入均保持原样；已知输入差异不在本片修复。

新增 [EnvironmentUiFactsTest](../../../app/src/test/java/com/deepseekharness/app/util/EnvironmentUiFactsTest.java)：8 项用例覆盖 8 组部署输入、16 组横幅输入、16 组引导输入、读取次序/短路、每个读取阶段失败时的已读前缀及部分事实、live-owner 页面例外不放宽引导显示门禁。纯投影另跑既有 RuntimeUpgradeStateTest，采样与纯投影分别验证。测试不模拟 Android 已执行、不创建用户任务/进程或租约。

本片新读口只由上述 UI 调用，未进入 core 启动或事务准入；现有受管源码/资产/成员表均未变。UI 原本不在 launcherTrees，新纯采样工具也不在显式 launcherSources；它只移动原 UI 读取，不绕过执行侧复核。**不改 runtimeId/描述符**；如 Lead 要求将该展示工具额外纳管，由 main 评审精确清单并在最终树重算。[Java 格式清单](../../../tools/java-format-files.json) 只增加本片三个新 Java 文件。

| S1 检查 | 实际结果 / 限制 |
|---|---|
| 当前源纯 JVM | 13 类、**81 项，failures=0、ignored=0、assumptionSkipped=0**；当前 JDK17 源码编译与锁定依赖，包含新采样8项和 S0 选取的旧纯策略回归；不复用旧 class |
| 格式 | `python3 tools/format-java.py --write --files` 仅选本片6份Java：0 changed、精确token保持；全树 `format-java-tree.py --check --offline --jobs 1 --java /opt/jdk17/bin/java`：**1781/1781、0待格式化**；同参 Kotlin：**2文件、0待格式化**，均exit0 |
| 边界 / 受管身份 | `test-core-boundaries.py` **25项exit0**；`verify-runtime-descriptor-source.py` **199资产/491启动输入exit0**（六离线归档递延）；`test-runtime-descriptor-inputs.py` **103安装项/205资产/491输入/Gradle树251文件一致exit0**；资产/成员表与core/runtime/backup/rust对63b零diff |
| 文档 / 卫生 / diff | 显式基线 **125链接**；默认 **56文档/1315链接**，均0warning/error、exit0；`check-source-hygiene.py` PASS；暂存diff检查通过、精确8文件白名单 |
| 未跑 / 不继承 | 完整 Gradle/Robolectric、实际页面/适配器的 Android 运行与完整资源类型编译、Lint/APK/签名、设备覆盖/白屏/ROM矩阵；离线归档缺失仍在，不拿 S0 或主线 CI 代替 S1 |

S1 不写 A3/R3/A4/A6/A7 域，不增加执行/信号/FFI/维护锁/命令链；M17 自动检查保持原行为，Q2 仅作为后片边界。S1 的当前源单测、CI、独立审查与设备证据分别记账。
