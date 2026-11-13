# 应用内自动缓存回收模块：代码难度与开源机制评估

> 状态：**设计评估**（未实现代码、未真机验收）。
> 核对基线：`125189a4`（build362，`0.2.1-rc.5-20261113.1000-dsh0.2.2-rc.1`，单一 Standard，minSdk 30）。
> 用户前提：本项目为**开发性产物**，一切行为为开发性属性、非生产性属性（2026-11-13 裁定，见 [厂商权限资料 §0](../vendor-permissions-20261113/README.md)）。
> 本文回答四件事：**P1 是否成立、代码有多难、开源怎么做、按什么编译/后台边界落地**。本文不把记忆中的故障次数当作已验证证据。

## 0. 结论摘要

1. **P1 作为风险登记成立，作为已证实缺陷不成立。** 仓库内没有可核对的「缓存溢出导致十几次闪退」记录；现有崩溃记录分别指向通知重复前台化、折叠披露二次展开、proroot `git --stat` SIGSEGV、虚拟屏 WebView 复用等，**没有一条能归因到缓存增长**。磁盘耗尽与 LMK 杀进程是两种不同故障，需要先取证再定性。
2. **代码难度：中。** 纯算法不难，难在「运行中的 proot/Node 运行时不能安全删缓存」与「开发属性模块不进正式包」两件事。只做开发包最小闭环约 **4–6 人日 / 600–900 行（含测试）**；做到可长期运行、带阈值/分层/UI/十语的完整开发版约 **2–3 周**。不需要原生/Rust。
3. **编译生产性问题必须先说清：** 当前 release 构建 `minifyEnabled false`（[app/build.gradle:226](../../../app/build.gradle)），所以 `BuildConfig` 开关只能保证**运行时 inert**，不能保证代码不进正式 APK。要「一个字节都不进正式包」，必须用 source set / build type / flavor 排除；代价约 **2–4 人日 + CI/变体改动**。建议 Phase 1 先用运行时开发门控，Phase 2 再按需做字节排除。
4. **后台长期静默常驻：** 不新建前台服务。复用现有 JobScheduler 范式（`AutomaticBackups` 15 分钟周期、`PostUpgradeCleanupService` 单次持久作业）+ `WorkerQuiescence`/`RuntimeTasks` 单飞；重清扫只在 Web 已停止的空闲态执行，**绝不为了清理自动停用户会话**。真正「常驻且静默地随时清」受架构限制，见 §4.4。
5. **内存自动回收：** Android 应用无法像 PC 清理软件一样「回收 RAM」。可做的是 `onTrimMemory` 释放自有大对象、给 Node 堆设上限、用 `ApplicationExitInfo` 记录 LMK 原因；**不能**靠自动重启 Web 来「释放内存」。当前仓库的 WebView 摘挂页 trim 已覆盖一部分（[BrowserPageKeeper](../../../app/src/main/java/com/deepseekharness/app/ui/BrowserPageKeeper.java)）。
6. **既有资产覆盖约六成：** 精确缓存白名单、NOFOLLOW 安全删除、维护屏障、周期 Job、备份/试运行/快照轮换都已存在；缺的是**阈值触发、分层预算、轻量活体清理、遥测与测试**。

## 1. 口径与术语

### 1.1 两种「缓存」不能混为一谈

| 类型 | 本项目的典型对象 | 应用能否回收 | 主要风险 |
|---|---|---|---|
| 磁盘缓存 | `getCacheDir()` 临时文件、WebView HTTP/代码缓存、rootfs 内 `.dsh/.cache`/`session_projcache`、插件 `dist-cache`、运行时回退副本、自动备份、配置快照 | 能（自己的私有目录）；**不能**清别的应用 | 删错用户数据；运行中删除导致运行时读写失败 |
| 内存缓存 | WebView 渲染器、Node/proot 堆、截图/屏幕活动环形、文档列表快照、Bitmap | 只能释放自己的对象；系统 LMK 才是最终回收者 | 自动重启 Web 会中断 Agent 会话；`System.gc()` 不是策略 |

用户说的「缓存溢出导致闪退」可能同时指 **存储空间耗尽（ENOSPC）** 与 **内存不足被 LMK 杀死**。二者的证据来源、恢复动作和优先级不同，不能用一个「自动清理」模块同时承诺解决。

### 1.2 「编译生产属性」的两个轴

- **行为轴（运行时）**：模块是否在正式包里默认启用。开发属性基线要求默认关、用户可见、可回退。
- **字节轴（编译产物）**：模块的类/方法是否被打进正式 APK。当前 release 不混淆不收缩（`minifyEnabled false`），`BuildConfig.DEV_ONLY` 分支不会自动消失；只有 source set/flavor 才能保证不编译。两轴要分开验收，不能把「默认关闭」写成「不进正式包」。

### 1.3 「后台长期静默常驻」的候选

| 方式 | 常驻性 | 静默 | 平台约束 | 本项目适配 |
|---|---|---|---|---|
| JobScheduler 周期/单次 | 进程死了也能被系统拉起 | 是 | 周期下限 15 分钟；Doze 延后；单次执行约 10 分钟级 | 已有两处范式，**首选** |
| 现有前台服务内线程 | 服务活着才在 | 是（通知已存在） | 进程被杀即中断；不能长期占 CPU | 可做廉价阈值检查，不承载重扫 |
| WorkManager | 较好 | 是 | 新增依赖与初始化；本项目未使用 | 不引入 |
| AlarmManager 精确闹钟 | 可唤醒 | 是 | 精确闹钟权限/Doze 限制 | 不用 |
| root/Shizuku 深度清理 | 可清别的应用 | 依赖授权 | 破坏性、OEM 差异、越过「不删用户数据」边界 | **不做**，见 §4.10 |

## 2. 仓库现状与证据

### 2.1 用户记忆 vs 仓库记录

| 主张 | 仓库可核对事实 | 结论 |
|---|---|---|
| 缓存溢出导致十几次闪退 | `CHANGELOG.md`、`docs/releases/`、`docs/audits/` 中未找到缓存/空间耗尽归因的闪退记录；已记录的崩溃是通知、折叠披露、proroot SIGSEGV、虚拟屏 WebView 等 | **未证实**，不能直接写进发布结论 |
| 存储会持续增长 | 结构上成立：完整环境约 1 GB，运行时回退副本、自动备份、配置快照、WebView/rootfs 缓存都会累积；见 §2.3 | **风险真实** |
| 内存会持续增长 | 结构上可能：Node/proot 与 WebView renderer 都在本应用进程内；但仓库没有 `ApplicationExitInfo` 采集，无法区分 LMK/崩溃/用户强杀 | **未证实** |
| 已有自动清理 | 有，但只在覆盖升级后单次执行；见 §2.2 | 覆盖不足 |

现有可用于取证的资产：

- `DiagnosticLog.installCrashHandler` 记录未捕获异常摘要（[core/DiagnosticLog.java](../../../app/src/main/java/com/deepseekharness/app/core/DiagnosticLog.java)）。
- `ErrorLogRepository`、`StartupDiagnostics`、`last-startup-failure.log`、`startup-history`、`diagnostic-events`（32 KiB 有界，见 [DiagnosticHistory](../../../app/src/main/java/com/deepseekharness/app/core/DiagnosticHistory.java)）。
- 测试包「反馈与诊断」导出 logcat/诊断（`TestPackageFeedbackExporter`，仅 `.test/.debug/.dev`）。
- `/app/device` 已暴露 `storage_free` 与 `memory_free`（[HttpShellService.java:3192](../../../app/src/main/java/com/deepseekharness/app/HttpShellService.java)），但读的是外部存储与系统可用内存，不是应用私有目录的精确占用。
- **缺口**：没有 `ActivityManager.getHistoricalProcessExitReasons`（API 30+，`ApplicationExitInfo`）采集，也没有应用私有目录的长期体积趋势。这是 Phase 0 最值得补的两项。

### 2.2 既有组合件

| 资产 | 位置 | 作用 | 关键限制 |
|---|---|---|---|
| `StorageMaintenance.inspect/clean` | [backup/StorageMaintenance.java](../../../app/src/main/java/com/deepseekharness/app/backup/StorageMaintenance.java) | 统计分类体积；清理 WebView/根文件系统可再生缓存，轮换已核验运行时副本 | `clean()` 走 `MaintenanceCoordinator.exclusive`，会安全停止 Web/终端；要求自动备份空闲；**无单测** |
| `RegenerableCachePaths` | [util/RegenerableCachePaths.java](../../../app/src/main/java/com/deepseekharness/app/util/RegenerableCachePaths.java) | 根文件系统精确缓存白名单 + `safeRelative` 拒绝根/会话/插件源码等 | 白名单窄；测试只覆盖唯一性与少量否定项 |
| `PostUpgradeCleanupService` | [backup/PostUpgradeCleanupService.java](../../../app/src/main/java/com/deepseekharness/app/backup/PostUpgradeCleanupService.java) | 覆盖升级后单次持久 Job（`POLICY=3`，30 秒延迟，60 秒指数退避） | **每个 versionCode 只跑一次**；环境未就绪/不空闲就重试，不会主动停会话 |
| `AutomaticBackups` + `AutomaticBackupPruner` | [backup/AutomaticBackups.java](../../../app/src/main/java/com/deepseekharness/app/backup/AutomaticBackups.java)、[AutomaticBackupPruner.java](../../../app/src/main/java/com/deepseekharness/app/backup/AutomaticBackupPruner.java) | 15 分钟周期检查，按每日/间隔/停止后计划触发；只保留最新 3 份已验证自动副本 | 清理对象仅自动、完整、已验证副本；手动/未知/部分永不自动删 |
| `RuntimeTrialRecords` | [backup/RuntimeTrialRecords.java](../../../app/src/main/java/com/deepseekharness/app/backup/RuntimeTrialRecords.java) | 保留最近 3 条成功 + 5 条失败记录；未关闭现场永不删除 | 只轮换已确认关闭的现场 |
| `ConfigurationSnapshots` | [backup/ConfigurationSnapshots.java](../../../app/src/main/java/com/deepseekharness/app/backup/ConfigurationSnapshots.java) | 配置快照上限 72、隔离区 32、标记 8 | 数量上限不等于体积上限 |
| `ManagedRuntimeTransaction.trimOlder` | [backup/ManagedRuntimeTransaction.java:565](../../../app/src/main/java/com/deepseekharness/app/backup/ManagedRuntimeTransaction.java) | 保留最近两份已验证旧运行时；未知/失败原件不清理 | 依赖健康回执与逐根摘要核验 |
| `EnvironmentRebuildTransaction.cleanupCompleted` | [backup/EnvironmentRebuildTransaction.java:264](../../../app/src/main/java/com/deepseekharness/app/backup/EnvironmentRebuildTransaction.java) | 清理有 `retired-proof.json` 的旧环境 | 修改过或含额外成员的原件保留 |
| `BrowserPageKeeper.trimMemory` | [ui/BrowserPageKeeper.java](../../../app/src/main/java/com/deepseekharness/app/ui/BrowserPageKeeper.java) | 低内存时只销毁**已摘挂**的 WebView 页面 | 可见页面不静默销毁；不是全应用内存策略 |
| `HarnessService` keepalive | [HarnessService.java:479](../../../app/src/main/java/com/deepseekharness/app/HarnessService.java) | 15 秒周期守 Web 健康、LAN、ADB 桥 | 不是通用维护调度器，不应塞重 IO |
| `StorageActivity` | [ui/StorageActivity.java](../../../app/src/main/java/com/deepseekharness/app/ui/StorageActivity.java) | 手动「清理可再生缓存与多余运行时副本」+ 分类体积 | 只有手动入口；无自动状态/预算 |

### 2.3 缓存增长源与删除边界

| 增长源 | 典型量级 | 现有轮换 | 可否自动删 | 备注 |
|---|---|---|---|---|
| 当前 Linux/DSH 环境 | 约 1 GB | 无（在用的是用户环境） | 否 | 只能清其内部可再生缓存 |
| 运行时回退副本 | 每份约 1 GB | 保留最近 2 份健康旧版 | 仅维护态 | 需健康回执/摘要核验 |
| 环境重建保护副本 | 约 1 GB | 有退休证明才清 | 仅维护态 | 修改过/含额外文件不删 |
| 自动备份 | 随用户数据 | 保留 3 份已验证 | 否（有专门轮换） | 手动/未知副本永不自动删 |
| 配置快照 | 随配置数量 | 72/32/8 上限 | 可扩展 | 数量上限不控体积 |
| WebView HTTP/代码缓存 | 数百 MB 可能 | 仅覆盖升级清理 | 仅空闲 | 运行中删除有损坏风险 |
| rootfs 内 `.dsh/.cache`、`session_projcache`、插件 `dist-cache` | 随会话/插件增长 | 仅覆盖升级清理 | 仅空闲 | 运行中 Node 可能打开文件 |
| `getCacheDir()` 临时文件 | `web-downloads`/`web-shares`/`shm`/`dsha-document-list-*.db`/`diagnostic-exports` | 各消费方自行清一部分 | 部分可活体清 | 需按对象年龄与所有权；`shm` 运行中不可删 |
| 插件 npm/pnpm 缓存、node_modules | 随插件安装增长 | 无统一策略 | 仅空闲/需确认 | `pnpm store prune` 会降低后续安装速度 |

### 2.4 缺口清单

1. **没有阈值触发**：只有「升级后一次」，没有「剩余空间低于 X」或「应用私有占用高于 Y」。
2. **没有分层**：`StorageMaintenance.clean` 是一个重动作，要求停 Web；缺少「不打断会话也能安全删」的轻量层。
3. **没有预算/配额**：只有数量上限（备份 3、试运行 3/5、快照 72），没有字节预算；WebView/rootfs 缓存没有上限。
4. **没有常驻/周期检查**：`AutomaticBackups` 的 15 分钟 Job 只服务备份，未承担空间检查。
5. **没有遥测**：清理触发、释放字节、失败原因、连续失败、上次运行时间未持久化；后台清理是否真的跑过无法从 UI 看出。
6. **没有内存取证**：`ApplicationExitInfo` 未接；无法区分 LMK、Java 崩溃、用户强杀。
7. **没有测试**：`StorageMaintenance` 无单测；`PostUpgradeCleanupService` 无服务测试；`RegenerableCachePathsTest` 只覆盖很浅的否定项。
8. **没有编译门控**：模块若直接写在 `main`，release 不收缩，必定进正式包；`TEST_PACKAGE` 只是行为开关。

## 3. 开源机制调研

### 3.1 Android 平台：系统级配额与低存储，不是应用级万能清理

| 机制 | 触发/阈值 | 应用可用性 | 对 DSHA 的含义 |
|---|---|---|---|
| `CacheQuotaStrategy`（AOSP `frameworks/base/services/core/java/com/android/server/storage/`） | 系统按 UsageStats（前台时长由系统 `CacheQuotaService` 计算）为近一年使用过的应用算磁盘缓存配额，经 `Installer.setAppQuota` 落盘；不是普通应用可调用的清理器 | **系统进程**；普通应用不能替别的应用设配额或清缓存 | 说明「按配额管理缓存」是 OS 职责；应用侧只能管理自己的目录 |
| `DeviceStorageMonitorService` / `StorageManagerService` | 每分钟检查私有卷；可用空间低于 `1.5× lowBytes` 时调用 `PackageManagerInternal.freeStorage(2× lowBytes)` 自动清缓存，并广播/通知 LOW/FULL；`lowBytes` 由系统按卷计算 | 普通应用只能读 `StatFs` 或收低存储提示；`freeStorage`/`freeCache` 是系统/特权路径 | **水位 + 自动清**有系统级先例，但普通应用不能调用；我们只能在私有目录做同样思想 |
| `StorageStatsManager` | 查询自身/其他应用缓存与数据统计 | 查询其他应用通常受 `PACKAGE_USAGE_STATS`/系统权限限制；**不能**据此删除 | 统计自身目录用 `File`/`lstat` 即可，无需该 API |
| `Context.getCacheDir()` | 系统可在空间不足时删除缓存目录内容 | 应用可随时删自己的 cache | 适合放「可丢」文件；但不能把用户数据放这里 |
| `ComponentCallbacks2.onTrimMemory` | 系统内存压力分级（`RUNNING_LOW`/`RUNNING_CRITICAL`/`COMPLETE` 等） | 所有应用 | 只释放自有对象；不是「回收别的进程内存」 |
| `ActivityManager.getHistoricalProcessExitReasons` / `ApplicationExitInfo`（API 30+） | 读取本包历史退出原因（LMK、崩溃、ANR、用户强杀等） | 本包可用 | **Phase 0 最有价值的证据来源**；当前仓库未使用 |
| JobScheduler / Doze | 周期下限 15 分钟；Doze 维护窗口延后；`setPersisted` 需开机广播权限 | 所有应用 | 适合「日级检查 + 延迟执行」，不适合秒级常驻 |

结论：Android 平台没有「让普通应用在后台静默清别的应用缓存」的公开能力；系统自己也只做配额与低存储回收。**本模块应严格限定在应用私有目录**。若引入 root/Shizuku 清 `/data/data/*/cache`，那是另一类高风险能力，不属于「应用内缓存回收」。

### 3.2 开源 Android 应用：可借鉴权限模型与调度，不可直接嵌入

- **SD Maid 2/SE**（`d4rken-org/sdmaid-se`，GPLv3）：AppCleaner 清理应用缓存，CorpseFinder 清已卸载应用残留，SystemCleaner 用用户过滤器清任意文件，Scheduler 做定时动作；跨应用清理依赖 root/ADB/Shizuku 之一（[README](https://github.com/d4rken-org/sdmaid-se)）。它的价值是**功能分层与权限声明**，不是可直接复制的代码：GPLv3 与「清理别的应用」都不适合 DSHA 的开发属性模块。
- **SD Maid 的定时实现**（可核对）：`app-tool-scheduler/src/main/java/eu/darken/sdmse/scheduler/core/SchedulerWorker.kt` 是 `CoroutineWorker`，运行时 `setForeground(...)` 声明前台信息，结束/失败/取消都在 `finally` 里 `SchedulerManager.reschedule(...)` 续上下一次；`runAttemptCount > 0` 时返回 `Result.success` 而不是 `failure`，避免 WorkManager 把后续链一起判失败；`Schedule.kt`/`ScheduleStorage.kt` 持久化计划，`SchedulerManager.kt` 管理下一次。清理动作经 `TaskSubmitter` 交给 `AppCleaner`/`CorpseFinder`/`SystemCleaner` 模块；跨应用路径经 `app-common-root`/`app-common-adb`/`app-common-shell` 选择 root/ADB/Shizuku（[scheduler 目录](https://github.com/d4rken-org/sdmaid-se/tree/main/app-tool-scheduler/src/main/java/eu/darken/sdmse/scheduler/core)、[appcleaner 模块](https://github.com/d4rken-org/sdmaid-se/tree/main/app-tool-appcleaner)）。
- **对 DSHA 的启示**：连专门做清理的应用也没有用「永不退出的常驻守护线程」；它用 WorkManager 的持久任务 + 前台信息 + `finally` 续链。DSHA 对应的是 JobScheduler + `jobFinished(retry)`，比新造常驻服务更稳。
- 其他 Android 清理类项目大多同为 root/Shizuku 系统级工具；没有公开 API 时只能做「本应用缓存 + 用户显式选择」。本评估不把未核实的仓库写成依据。

对 DSHA 的可迁移点：
1. 清理对象必须**按类别显式列出**，不能扫全盘按扩展名删。
2. 跨应用清理必须单独授权、单独确认、单独审计；不要混进「自动缓存回收」。
3. 定时动作与手动动作共用同一套安全检查，不因「无人值守」放宽。

### 3.3 Node / 浏览器 / 构建系统：可照搬的回收算法

| 机制 | 触发与阈值 | 删除边界与安全设计 | 可迁移到 DSHA |
|---|---|---|---|
| npm `cacache` 的 `verify()` | 手动；无时间/大小阈值；并发默认 20 | 按索引可达性做 mark-and-sweep：删无索引引用的 `content-v2/**`、sha512 校验失败条目、坏索引、`tmp`；重写索引并写 `_lastverified`（[verify.js](https://github.com/npm/cacache/blob/main/lib/verify.js)、[content/path.js](https://github.com/npm/cacache/blob/main/lib/content/path.js)） | **内容寻址 + 校验后删 + tmp 清理**最值得照搬；不要按 key 删内容（npm `cache clean` 源码自注可能让其他索引条目失去内容） |
| pnpm `store prune` | 手动 | 按 store 的 `projects/` 注册判断「任何项目都不用」；先删过期 pnx 缓存，再清元数据缓存；不可解码索引保留并报告（[pnpm store](https://pnpm.io/cli/store)） | 用「受管引用清单」替代「文件被谁打开」的猜测；硬链接/引用边界要显式 |
| Chromium SimpleCache | 默认 80 MiB；按可用空间与平台系数放大；高水位 95%、低水位 90%，超过高水位才驱逐到低水位；单飞 | 评分≈闲置秒×（条目大小+512），高优先级乘因子缩小；`APP_CACHE` 不驱逐；先在索引移除再批量删文件；索引损坏可重建（[cache_util.cc](https://chromium.googlesource.com/chromium/src/+/refs/heads/main/net/disk_cache/cache_util.cc)、[simple_index.cc](https://chromium.googlesource.com/chromium/src/+/refs/heads/main/net/disk_cache/simple/simple_index.cc)） | **高/低水位 + 时间×大小评分 + 高优先级保护 + 单飞**可直接用于 WebView/rootfs 缓存预算 |
| Chromium 崩溃恢复 | 索引防抖；后台 100 ms 落盘 | 半写/损坏索引删文件重建；缓存目录整体 rename 后由低优先级线程删（[simple_backend_impl.cc](https://chromium.googlesource.com/chromium/src/+/refs/heads/main/net/disk_cache/simple/simple_backend_impl.cc)） | 「清单先写、删除可重入、索引坏了重建」 |
| Gradle 缓存清理 | 每 24 小时后台检查；构建缓存默认 7 天未用删除；daemon 空闲 3 小时自停 | 只按最后使用时间，不按大小；Gradle 9 用 `caches.buildCache.setRemoveUnusedEntriesAfterDays()`（[构建缓存文档](https://docs.gradle.org/current/userguide/build_cache.html)） | **周期 + 保留天数 + 维护/退出时执行**；不需要实时守护 |
| Chromium BackoffEntry | 失败后指数退避 | 带 jitter、上限、条目生命周期（[backoff_entry.h](https://chromium.googlesource.com/chromium/src/+/refs/heads/main/net/base/backoff_entry.h)） | 后台清理失败/未空闲时的重试策略 |

### 3.4 后台长期静默常驻的工程范式

可核对的做法集中在五点：

1. **单实例**：同一时刻只允许一个清理者在跑（cacache 并发受控、Chromium 驱逐单飞、pnpm 锁在 store 外）。本项目已有 `WorkerQuiescence` 与 `RuntimeTasks`。
2. **低优先级 IO**：Chromium 对条目 IO 用 `USER_BLOCKING`，批量删目录用 `BEST_EFFORT` + `CONTINUE_ON_SHUTDOWN`；不要在 UI 线程或 keepalive 热路径做全树遍历。
3. **空闲/延后触发**：Gradle 在 daemon 停止时清；Chromium 进后台落索引；Android 用 Doze 窗口。对应到 DSHA：**等 Web 已停止、备份已完成、没有维护事务**再清重缓存。
4. **退避与抖动**：失败/不满足条件时不要每分钟重试；用指数退避 + 随机抖动 + 上限。
5. **可观测**：cacache 返回 verified/reclaimed/bad/missing；Chromium 打 UMA 直方图。清理不记录「释放了多少、失败在哪」，就无法验收，也无法证明 P1 是否缓解。

### 3.5 编译期门控：保证 vs 优化

| 机制 | 能否保证不进正式包 | 代价/风险 | 依据 |
|---|---|---|---|
| `src/debug`、`src/<flavor>` source set | **保证**：该变体不编译这些源码 | 变体/包管理成本 | [Android 构建变体](https://developer.android.com/build/build-variants) |
| `buildConfigField` / `BuildConfig` 分支 | **不保证**：代码仍参与编译；删不删取决于常量折叠与 R8 | 一个布尔开关，最省事 | [收缩代码](https://developer.android.com/build/shrink-code)；本仓库 release `minifyEnabled false`，R8 不兜底 |
| R8 收缩 | 仅在 `minifyEnabled true` 且无 keep 规则时删未用代码；反射/keep 可保留 | 混淆、keep 规则维护 | 同上；AGP 8.0 起 `buildConfig` 默认关闭，本项目显式开启 |
| Rust `#[cfg(feature = ...)]` | **保证**：谓词为假时从源码移除，不参与编译/类型检查 | 每种组合都要编译测试 | [Rust Reference](https://doc.rust-lang.org/reference/conditional-compilation.html) |
| Rust `cfg!` / C `#if` | `cfg!` **不删代码**；`#if` 由预处理器剔除 | 易误判为「已排除」 | [cfg! 文档](https://doc.rust-lang.org/std/macro.cfg.html)、[cppreference](https://en.cppreference.com/w/c/preprocessor/conditional) |

**DSHA 具体结论**：`minifyEnabled false` + 代码在 `main` ⇒ 无论 `BuildConfig` 怎么写，类都会进正式 APK。开发属性模块若接受「进包但默认 inert」，用运行时门控即可；若要求「字节级不进正式包」，必须新增 source set/flavor，见 §4.9。

### 3.6 不可照搬的部分

- **按「未被任何项目引用」推断可删**：DSHA 的会话、插件、工作区没有 pnpm 那样的中心注册表；只能维护显式白名单。
- **按 key 删 npm 内容**：可能破坏共享同一内容的其它索引条目；本项目的插件缓存应整体按目录白名单清，或先做引用核验。
- **系统级配额/清别的应用**：普通应用无公开 API；root/Shizuku 路径不属于本模块的安全边界。
- **PC 式「内存清理」**：Android 上调用 `System.gc()`、杀后台进程、频繁重启 Web 会伤害体验，不构成回收策略。

## 4. 推荐方案

### 4.1 原则

1. **先取证，后自动删除。** Phase 0 只记录，不删。
2. **分层删除，不打断会话。** 重缓存只在用户已经停止 Web 的空闲态清；不为了清理强制停 Web。
3. **白名单 + 安全文件层。** 复用 `RegenerableCachePaths.safeRelative` 与 `BackupFileSystem.removeOwned`（NOFOLLOW、归属 UID、目标类型、目录权限），不写裸 `File.delete`。
4. **单飞 + 有界。** 扫描/删除有 entry 数、时间、深度上限；失败退避；不重放 UNKNOWN。
5. **开发属性默认关。** 默认关闭，设置页可见，可回退；正式包不显示入口（或显示为不可用）。
6. **不碰**：会话、配置、凭据、插件源码/原件、工作区、手动备份、未知/未核验原件。

### 4.2 三档删除范围

| 档 | 名称 | 运行条件 | 可删对象 | 不能删 |
|---|---|---|---|---|
| L0 | 活体安全 | 任何时候（Web 运行中也可） | `getCacheDir()` 下明确属于本应用、且已过期/孤儿的小对象：旧 `diagnostic-exports`、崩溃遗留的 `dsha-document-list-*.db`、插件导入/导出临时文件、明确可弃的下载/分享残留（按年龄 + 所有权 + 非活跃会话判定） | `shm`（运行中）、`web-downloads`/`web-shares` 中仍可续传/仍被用户引用的对象、任何会话/附件 |
| L1 | 空闲清理 | `AutomaticBackups.idle` 为真（Web 已停止、无启动/停止、无运行任务、无维护） | WebView HTTP/代码/GPU 缓存、rootfs 内 `RegenerableCachePaths.rootfs()` 白名单、插件 `dist-cache`、`cache/WebView` 等 | 当前 Linux 环境、会话缓存以外的一切用户数据、未确认关闭的试运行现场 |
| L2 | 维护清理 | 用户显式维护/覆盖升级路径，已有维护屏障 | `StorageMaintenance.clean` 现有的运行时副本轮换、环境重建清理、已核验旧副本 | 未知/失败原件、修改过的副本、手动备份 |

**关键点**：L0 的收益有限，但它是「后台静默」唯一不需要停会话的部分；L1 才是大头，但它天然依赖「Web 已停止」。因此本模块的诚实定位是「**空闲时自动回收 + 低空间时提醒用户**」，不是「随时静默清一切」。

### 4.3 触发与阈值

| 触发 | 频率 | 动作 | 备注 |
|---|---|---|---|
| 覆盖升级后 | 每 versionCode 一次 | 现有 `PostUpgradeCleanupService` L2 | 已有，保留 |
| 周期检查 | 24 小时（±10% 抖动） | 读 `StatFs` + 轻量目录统计；超阈值才排 L1/L0 | JobScheduler；Doze 延后不补跑 |
| 低空间 | 剩余空间 < `max(1 GB, 5% 总容量)` | 先尝试 L0；若仍低且空闲则 L1；否则发一条可操作的低空间通知/页面提示 | 阈值用常量 + 单测，待真机校准 |
| 临界低空间 | 剩余 < 512 MB | 同上，提示更明确；**不自动停 Web** | 与安装/更新的 `NO_SPACE` 保护呼应 |
| `onTrimMemory` | 系统回调 | 释放内存对象（见 §4.8） | 不触发磁盘重清扫 |
| 手动 | 设置页/存储页 | L2（用户确认） | 复用现有 `StorageActivity` |

滞回与节流：进入阈值后，至少 6 小时不再重复重扫；释放后需回到阈值的 1.2 倍以上才重置「低空间」状态；连续失败 3 次后指数退避（30 分钟 → 2 小时 → 6 小时），并在诊断里保留最后错误。

### 4.4 执行模型

```
DshaApp/BootReceiver
   └─ schedule periodic Job（24h, persisted）
HarnessService keepalive（可选）
   └─ 每 15 分钟只读 StatFs；低于阈值 → schedule 一次性 Job（不扫树）
CacheReclaimService (JobService)
   ├─ WorkerQuiescence.tryBegin() 失败 → 直接放弃本次
   ├─ 备份/安装/维护/试运行忙 → 稍后重试
   ├─ 读策略 + 快照（有界）
   ├─ L0 可活体清 → 执行 → 记录
   ├─ 若 idle 且仍低 → L1 → 执行 → 记录
   ├─ 若临界且仍低 → 发用户可见提示（不自动 L2）
   └─ jobFinished(params, retry)
```

- **不新增前台服务**：已有 `HarnessService` 的前台通知足够；把重活放进 JobService，避免 keepalive 线程被 IO 拖慢。
- **不自动停 Web**：L1/L2 只在 `AutomaticBackups.idle`/维护屏障已成立时执行；否则跳过并重试。自动路径永远不调用 `exclusiveUserConfirmed`，也不升级为强制停止。
- **不重放**：删除中途进程死亡，下一次只按「仍存在且仍满足条件」重新判定；不依赖上次「删到哪」的日志。
- **有界**：单次最多 20,000 个 entry / 60 秒扫描 / 10 分钟总预算；超过就提交已完成部分并安排下次，不无限遍历。
- **低优先级**：JobService 内起普通线程，`Process.setThreadPriority(THREAD_PRIORITY_BACKGROUND)`；不使用 `WakeLock`；不要求充电（清理本身耗电低），但可要求 `setRequiresBatteryNotLow(true)` 避免低电量重扫。

### 4.5 预算与保留（待校准的默认值）

以下是**提案值**，不是实测结论；必须在 Phase 0/1 用真机体积数据校准后再写死：

| 类别 | 预算/保留 | 理由 |
|---|---|---|
| 设备剩余空间 | 低水位 `max(1 GB, 5%)`；临界 512 MB | 低于安装/更新所需的 64–128 MB 余量太多会直接触发 `NO_SPACE` |
| 单次释放目标 | 最多 512 MB 或 60 秒，先到先停 | 避免一次清理长时间占 IO |
| WebView 缓存 | 高水位 256 MB，低水位 192 MB | 参考 Chromium 80 MiB 默认 + 本项目页面更重；待实测 |
| rootfs 可再生缓存 | 高水位 512 MB，低水位 384 MB | `.dsh/.cache`、`session_projcache`、插件 dist-cache 合计 |
| 诊断导出/临时快照 | 24 小时 | 崩溃后仍可取证；旧文件无语义 |
| 自动备份/试运行/快照 | 维持现有数量上限 | 已有专门轮换，不并入字节预算 |
| 重扫间隔 | 6 小时 | 防止抖动与 IO 放大 |

数值统一放在纯逻辑 `util/CacheReclaimPolicy` 的 `static final` 常量里，不在 UI/服务里散落；测试用固定时钟与假文件树验证边界。

### 4.6 与现有维护屏障的关系

- `StorageMaintenance.clean` 目前用 `MaintenanceCoordinator.exclusive`，会停 Web/终端，最长等待 45 秒；自动路径**不能**直接调用它。
- 建议把 `StorageMaintenance` 拆成：
  - `cleanLiveSafe(...)`：L0，不需要 Web 停止；
  - `cleanIdle(...)`：L1，要求 `AutomaticBackups.idle`，不主动停；
  - `cleanMaintenance(...)`：L2，保留现有 `exclusive` 语义，只给手动/覆盖升级。
- `clean()` 保持向后兼容（等价 `cleanMaintenance`），避免破坏 `StorageActivity` 与 `PostUpgradeCleanupService`。
- 单飞用 `WorkerQuiescence`/`RuntimeTasks`，与 `AutomaticBackupService`、`DataProtectionService`、维护事务互斥；清理期间不得有备份/安装/试运行并发。

### 4.7 删除安全

- 路径必须来自**编译期白名单**（`RegenerableCachePaths` 扩展），不接收用户/网络输入；`safeRelative` 拒绝绝对路径、`..`、`root/.dsh/sessions/`、`plugin-src` 等。
- 每个目标先用 `BackupFileSystem.stat`（lstat/NOFOLLOW）确认类型、归属 UID、不是链接；目录先 `prepareOwnedRemoval`；删除只走 `removeOwned`。
- 不跟随链接；不跨挂载点；不删根；不删 `root/.dsh` 本身；不删 `filesDir` 下的会话/配置/凭据/插件/工作区。
- 删除前后用同一 `lstat` 口径统计；「已释放字节」只在删除后复核通过时记录，失败只记错误，不写成功文案。
- 对「仍在被打开」的对象（`shm`、运行中的 Node 文件）不删；L0 必须依赖年龄 + 非活跃判定，不能只凭文件名。

### 4.8 内存路径（与磁盘策略分开）

1. **取证**：启动后读取 `ApplicationExitInfo`（`getHistoricalProcessExitReasons`），只持久化时间、reason、description、importance、RSS/PSS（若有），最多 20 条，脱敏；记录 `REASON_LOW_MEMORY` 次数。这是判断「十几次闪退」是否 LMK 的唯一低成本方法。
2. **释放自有对象**：扩展 `onTrimMemory` 处理，除已摘挂 WebView 外，释放可重建的 Bitmap/截图环形/文档快照缓存；可见页面仍不静默销毁。
3. **约束源头**：评估给 Node 设 `--max-old-space-size`（当前 `NODE_OPTIONS` 主要用于 DNS/net-epoch/启动观察预加载，未设堆上限，[RuntimeTools.java:1027](../../../app/src/main/java/com/deepseekharness/app/runtime/RuntimeTools.java)）；值必须按设备内存实测，不能拍脑袋。上限过低会让 dsh 在重会话下 OOM，属于新的稳定性风险。
4. **不自动重启 Web**：重启会中断 Agent 会话；只能作为用户可见的「低内存，建议重启」提示，或由现有 Web 看门狗按健康失败处理，不能归入缓存回收。
5. **不调用 `System.gc()`、不杀后台进程**：对 ART/Node 无可靠收益，且会制造卡顿与误杀。

### 4.9 编译/构建属性方案对比

| 方案 | 做法 | 保证 | 工作量 | 风险 | 建议 |
|---|---|---|---|---|---|
| A. 运行时开发门控 | 新增 `buildConfigField boolean CACHE_RECLAIM_DEV = (sunsetTest \|\| debugPackage \|\| devPackage)`；入口/调度/UI 全部 `if (BuildConfig.CACHE_RECLAIM_DEV)` | 默认关、正式包 inert；**不保证不进包** | 0.25 人日 | 低 | **Phase 1 采用** |
| B. `src/debug` | 模块放 `app/src/debug/java`，只在 debug 构建编译 | 保证；但 `.dev` 是 release 构建，**不含** | 0.5 人日 | 低；`.dev` 用不了 | 仅 debug 调试可用 |
| C. 新 build type/flavor + `src/dev` | 新增 `dev` build type（或 `dev` flavor），source set 只在该变体编译；CI 增加 `assembleStandardDev` | **保证** | 2–4 人日 + CI/变体/签名门禁 | 中高：变体膨胀、测试包附件、文档、identity 校验 | 仅当用户要求字节级排除时做 |
| D. 独立 Gradle 模块 | `:cachedev` 仅 dev 依赖 | 保证 | 3–5 人日 | 高：模块边界、资源/清单合并、CI | 不推荐 |
| E. Rust/native | 用 Rust 实现扫描/删除 | 可 `#[cfg]` 排除 | 1–2 周 + 工具链/ABI/CI | 高；收益低 | **不推荐**（见 §5.4） |

**建议**：Phase 1 用 A，并在设置页标注「开发实验，正式包不生效」；如果「非生产属性」被解释为「正式 APK 不得包含」，再单独做 C，不把 A 写成已满足字节排除。

### 4.10 明确不做

- 不清别的应用缓存；不使用 root/Shizuku 做跨应用清理；不调用 `pm trim-caches`/`freeCache` 等特权路径。
- 不自动删除会话、配置、凭据、插件源码、工作区、手动备份、未知/未核验副本。
- 不为了清理自动停止 Web/终端；不强制结束后台进程。
- 不做「一键加速」「内存清理」类 UI；不调用 `System.gc()`。
- 不在用户设备上注入故障（AGENTS 安全约定）；设备验收用 `.dev`/`.test` 包与隔离夹具。

## 5. 代码难度分解

### 5.1 组件与工作量（量级估算，不是承诺）

| 组件 | 文件（建议） | 新增/改动 | 测试 | 人日 | 风险 |
|---|---|---|---|---|---|
| 策略纯逻辑 | `util/CacheReclaimPolicy.java`（阈值、滞回、档位选择、退避、预算） | ~200 行 | ~300 行 | 1.5 | 低 |
| 分类/预算模型 | `util/CacheCategory.java`、`util/CacheBudget.java` | ~120 行 | ~150 行 | 0.5 | 低 |
| 缓存清单扩展 | `util/RegenerableCachePaths.java` 增加档位/年龄元数据 | ~80 行 | ~120 行 | 0.5 | 低 |
| 快照/统计 | `backup/StorageMaintenance.java` 拆分 live/idle/maintenance；有界遍历 | ~150 行 | ~200 行 | 1.5 | 中 |
| 安全清理实现 | 复用 `BackupFileSystem.removeOwned`；新增 age/ownership 判定 | ~120 行 | ~150 行 | 1 | 中 |
| JobService | `backup/CacheReclaimService.java`（调度、单飞、重试、jobFinished） | ~150 行 | 0（薄服务）+ 集成 | 1 | 中 |
| 调度接线 | `DshaApp`/`BootReceiver`/`HarnessService` 轻量检查 | ~50 行 | ~50 行 | 0.5 | 低 |
| 遥测/退出原因 | `core/CacheReclaimTelemetry.java`、`core/ApplicationExitRecorder.java` | ~180 行 | ~150 行 | 1.5 | 低 |
| 存储页/设置入口 | `ui/StorageActivity.java` 状态行、开发开关、i18n 10 语 | ~120 行 | UI 测试 | 1 | 中 |
| 编译门控 | `app/build.gradle`（A 方案） | 1 行 + 门控引用 | 变体冒烟 | 0.25 | 低 |
| 文档/CI/验收 | 本设计、索引、诊断说明、设备回执 | — | — | 1–2 | 低 |

**总计（开发版 MVP，不含 C 方案、不含十语 UI）**：约 **600–900 行生产代码 + 500–700 行测试，4–6 人日**。
**总计（完整开发版：遥测 + UI + 十语 + 设备验收 + 全量安全测试）**：约 **1,000–1,400 行，2–3 周**。
**追加 C 方案（字节级排除）**：**+2–4 人日**，并需要 CI/发布门禁复核。

### 5.2 难度评级

| 维度 | 评级 | 说明 |
|---|---|---|
| 算法/数据结构 | 低 | 阈值、滞回、预算、评分都是纯逻辑；参考 Chromium/cacache 即可 |
| Android 集成 | 中 | JobScheduler/Doze/单飞/维护屏障已有范式；新服务薄 |
| 删除安全 | **中高** | 运行中 proot/Node 的打开文件、符号链接、竞态、误删用户数据；这是主要风险 |
| 编译门控 | 中（运行时）/ 中高（字节排除） | 当前 release 不收缩；source set 会牵动 CI 与变体 |
| 测试/验收 | 中 | 需要假文件树、固定时钟、Robolectric/JobScheduler 夹具、真机 `.dev` 包 |
| **综合** | **中**（开发版 MVP）/ **中高**（可长期运行的完整版） | 不是「写不出来」，而是「不能写错」 |

### 5.3 为什么不需要原生/Rust

- 工作负载是目录遍历、`lstat`、删除与小量策略计算，不是 CPU 密集；Java/Kotlin 足够。
- Rust 会引入 NDK/ABI/16 KB 页/CI/打包/许可成本；[ADR-0004](../../adr/0004-kotlin-rust-direction.md) 把 Rust 限定在可验证纯函数旁路，缓存扫描不满足「必须原生」的理由。
- 现有 `BackupFileSystem` 的 NOFOLLOW/归属核验已经是安全关键路径，另写一套 native 删除只会增加第二套边界。

### 5.4 如果用户说的「编译方向」是指原生编译

结论不变：**不建议**。若只是想要「编译期常量而非运行时可调」，在 Java 侧用 `static final` 常量 + 单测即可，不需要 native；若想要「编译期排除」，见 §4.9 的 C 方案，仍然不需要 Rust。

### 5.5 Phase 1 最小改动面（证明「不难」）

| 文件 | 改动 | 备注 |
|---|---|---|
| `app/build.gradle` | 新增 `buildConfigField boolean CACHE_RECLAIM_DEV = (sunsetTest \|\| debugPackage \|\| devPackage)` | 只加字段，不改变体 |
| `util/CacheReclaimPolicy.java` | 新增纯逻辑：阈值/滞回/档位/预算/退避 | 无 Android import，必配单测 |
| `test/.../util/CacheReclaimPolicyTest.java` | 新增固定时钟/边界用例 | 纯 JVM |
| `backup/CacheReclaimService.java` | 新增薄 JobService：单飞、忙时跳过、`jobFinished(retry)` | 复用 `WorkerQuiescence` |
| `app/src/main/AndroidManifest.xml` | 注册 `BIND_JOB_SERVICE` 服务 | 与现有两个 JobService 同型 |
| `DshaApp.java`、`BootReceiver.java` | 启动/升级/开机调度 | 各 1–2 行 |
| `StorageMaintenance.java`（Phase 1 可先不改） | Phase 1 只在 idle 时复用现有 `clean()`；Phase 2 再拆分 L0/L1 | 降低首版风险 |
| `docs/` + `.ACTIVE-SESSION` | 设计记录、索引、验收回执 | 本文即设计记录 |

结论：Phase 1 **不需要新权限、不需要新依赖、不需要改维护屏障**，只是把已有 `clean()` 接到周期/阈值触发上，并加一层纯逻辑策略。真正的工作量在测试与证据，不在代码量。

## 6. 安全、测试与验收

### 6.1 不变式

1. 删除目标必须来自编译期白名单；不接收动态路径。
2. 不使用裸 `File.delete`/`rm -rf`；只走 `BackupFileSystem` 安全层。
3. 不跟随链接、不跨挂载点、不删根、不删 `root/.dsh` 本体。
4. 不碰会话、配置、凭据、插件源码/原件、工作区、手动备份、未知原件。
5. 自动路径不停止 Web/终端、不强制结束进程、不重放 UNKNOWN。
6. 单飞；与备份/安装/维护/试运行互斥。
7. 释放字节必须在删除后复核；失败只记错误，不写成功。
8. 开发属性开关默认关；正式包无入口/无调度。

### 6.2 测试矩阵

| 层 | 用例 |
|---|---|
| 纯逻辑单测 | 阈值进入/退出/滞回；预算裁剪；档位选择；退避与抖动；`never-delete` 规则；固定时钟 |
| 文件层单测 | 假文件树（`java.nio` 临时目录）：白名单命中/未命中、符号链接、父目录类型变化、归属/权限、删除后统计、部分失败 |
| 服务/调度 | `ShadowJobScheduler` 或直接调用服务线程逻辑：单飞、忙时跳过、重试、`jobFinished` 参数、取消 |
| 集成 | 与 `AutomaticBackupService`/维护事务并发时互斥；Web 运行中 L0 不碰 L1 对象 |
| 设备（`.dev`/`.test` 包） | 构造隔离缓存夹具（不碰正式数据）；验证 L0/L1 释放、诊断事件、无会话丢失、无崩溃、低空间通知；用 `ApplicationExitInfo` 回执验证取证 |
| 回归 | 现有 `StorageActivity` 手动清理、覆盖升级清理、备份轮换、运行时副本保留不变 |

### 6.3 验收标准（建议）

- 删除集合与白名单逐项一致；随机抽样 100% 可解释。
- 清理前后 `StorageMaintenance.inspect` 的对应分类下降；未列入的分类不变。
- 清理期间无 Web 停止、无会话中断、无备份/维护事务被抢。
- 低空间触发有诊断事件；连续失败有最后错误；不会每分钟重试。
- 正式包（release）中：A 方案下入口不可见、调度不注册；C 方案下 `dexdump`/`classes.dex` 中不存在模块类（需在 CI 加检查）。
- 设备验收记录机型/系统/包名/版本/体积前后/耗时/失败；不把本机结果扩写成全机型矩阵。

### 6.4 回滚

- 开发属性开关关闭即停止调度与入口；已写偏好保留但不再变更。
- 清理只删可再生缓存，不回滚用户数据；若发现误删，按 `DiagnosticLog` 事件定位并修复白名单，不尝试从备份恢复缓存。
- C 方案（新 build type）可整体移除变体，不影响 `standardRelease`。

### 6.5 风险登记

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| 误删用户数据/越界 | 低 | 高 | 编译期白名单 + `removeOwned`/NOFOLLOW + 归属核验 + 假树测试 + 复审 |
| 运行中删除导致 dsh/Node 读写异常 | 中 | 高 | 重缓存只在 idle；L0 只碰明确可弃对象；不自动停 Web |
| 后台清理拖慢前台/耗电 | 中 | 中 | JobService + 后台线程 + 有界扫描 + `setRequiresBatteryNotLow` |
| 阈值拍错导致频繁清理或从不清理 | 中 | 中 | 滞回、6 小时重扫间隔、指数退避、Phase 0 校准 |
| 正式包误启用 | 低 | 高 | `CACHE_RECLAIM_DEV` 默认 false + release 冒烟 + 评审；字节排除另做 C |
| 厂商后台限制导致 Job 不执行 | 高 | 中 | `setPersisted` + 开机重排 + 手动入口兜底；不承诺准时 |
| 证据不足把风险当已证实 P1 | 高 | 中 | Phase 0 先取证；发布文案只写已核对事实 |
| 测试不足引入回归 | 中 | 中 | 纯逻辑单测 + 文件层夹具 + 服务/调度 + 设备回执；不把「构建通过」当验收 |

## 7. 分阶段计划

| 阶段 | 内容 | 产出 | 人日 | 决策门 |
|---|---|---|---|---|
| **Phase 0 取证** | `ApplicationExitInfo` 采集；应用私有目录体积快照（分类/总数/时间）；清理触发与结果事件；不删任何东西 | 1–2 周数据 + 诊断回执 | 1–2 | 是否真的存在 ENOSPC/LMK；P1 是否维持 |
| **Phase 1 最小闭环（开发属性）** | 周期 24h Job + 低空间阈值 + L0 + 空闲 L1 + 遥测；`CACHE_RECLAIM_DEV` 运行时门控；无 UI/十语 | 600–900 行 + 测试 | 3–5 | 设备上不丢数据、不打断会话；释放量达标 |
| **Phase 2 完整开发版** | 三档拆分、预算/滞回、存储页状态、开发开关、十语、`ApplicationExitInfo` 展示 | 1,000–1,400 行 | 1–2 周 | 安全评审 + 非实现者评审 |
| **Phase 3 字节级排除（可选）** | 新 build type/flavor + `src/dev`；CI 变体与门禁 | 变体 + CI 改动 | 2–4 | 用户明确要求「正式包不含」 |

**建议起点**：Phase 0 + Phase 1 合并为一次小批次（先记录、再在开发包打开低频 L0/L1），不要直接做「完整内存自动回收」。如果用户坚持 P1，Phase 0 的 1–2 天取证应当先做，因为它决定后续投入是否值得。

## 8. 待用户确认

1. **「内存」具体指什么？** 是应用私有目录/存储空间（ENOSPC），还是 RAM/LMK？两者的模块设计不同。
2. **十几次闪退的时间/机型/日志还在吗？** 若能提供 `logcat`/`ApplicationExitInfo`/崩溃时间点，Phase 0 可直接验证；否则只能先按风险处理。
3. **「非生产属性」是否要求正式 APK 字节级不含该模块？** 若是，选 C 方案（+2–4 人日）；若只要求默认关/不生效，选 A。
4. **低空间时是否允许用户可见提示？** 「完全静默」意味着不能在临界低空间要求用户停 Web 做重清理，释放能力会受限。
5. **是否允许在 dsh 运行时执行 `pnpm store prune`/清 npm 缓存？** 会降低后续插件安装速度，且可能影响正在安装的插件；建议只在空闲态、且默认关闭。
6. **验收设备与系统版本**？本机一加 12 / Android 16 只能作为单点证据，不能扩写成全机型。

## 9. 参考

**仓库内**

- [覆盖升级、运行时维护与插件依赖契约](../overwrite-upgrade-and-maintenance.md)
- [备份、恢复与保留数据](../backup-and-restore.md)
- [安装与维护](../install-and-maintenance.md)
- [安全模型](../security-model.md)（「不是内核沙箱」与缓存/凭据边界）
- [厂商权限资料 §0：开发性基准线](../vendor-permissions-20261113/README.md)
- [DSHA 工程规范 §验证与证据](../engineering-standard.md)

**外部（本轮已核对/引用）**

- [Android `ApplicationExitInfo`](https://developer.android.com/reference/android/app/ApplicationExitInfo)
- [Android `StorageStatsManager`](https://developer.android.com/reference/android/app/usage/StorageStatsManager)
- [Android 构建变体](https://developer.android.com/build/build-variants)
- [Android 收缩代码（R8）](https://developer.android.com/build/shrink-code)
- [AOSP `CacheQuotaStrategy`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/storage/CacheQuotaStrategy.java)
- [AOSP `DeviceStorageMonitorService`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/storage/DeviceStorageMonitorService.java)
- [npm/cacache `verify.js`](https://github.com/npm/cacache/blob/main/lib/verify.js)
- [pnpm `store`（prune/status）](https://pnpm.io/cli/store)
- [Chromium `cache_util.cc`](https://chromium.googlesource.com/chromium/src/+/refs/heads/main/net/disk_cache/cache_util.cc)
- [Chromium `simple_index.cc`](https://chromium.googlesource.com/chromium/src/+/refs/heads/main/net/disk_cache/simple/simple_index.cc)
- [Gradle 构建缓存清理](https://docs.gradle.org/current/userguide/build_cache.html)
- [Rust 条件编译](https://doc.rust-lang.org/reference/conditional-compilation.html)
- [SD Maid 2/SE](https://github.com/d4rken-org/sdmaid-se)

**未核实/保留**

- `StorageStatsManager.getCacheQuotaBytes` 在目标 API 级别是否为公开 API、是否受 `PACKAGE_USAGE_STATS` 限制，需按编译 SDK 37 的参考文档再核；本方案不依赖它。
- `DeviceStorageMonitorService` 的具体低存储百分比随版本/厂商变化，本文不写死；应用侧只用 `StatFs` 与自身阈值。
- 其他 Android 清理类开源项目的具体实现未逐行核对，不写成依据。
