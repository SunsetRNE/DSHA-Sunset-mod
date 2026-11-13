# A3 任务基线（G2）：数据保护受管事务 + 恢复计划契约冻结 v1

> 版本：v0.3　日期：2026-11-13　基线：`origin/main` = `c2e7aec1`（build362 + 齿轮单实例修复 + 本基线 v0.2 / `0.2.1-rc.5` / DSH 0.2.2-rc.1）
> 状态：**已评审（Lead 2026-11-13），R2 已解锁并实现**。docs-only 设计基线，未写生产代码；Q1–Q5 全部按推荐 A 裁决（见 §7.1）；v0.3 按 R2 非实现者评审修复轮修正 nowMs 单口径、硬前置/多原因口径与 appVersion 事实（见 §9）。契约 v1 冻结、A3 切片与写域见 §5/§6。
> 依据：[迭代路径](iteration-roadmap-20261112.md) §4 跨轮不变式 + §5 A3、[R1 Rust 工具链与首期旁路方案](r1-rust-toolchain-plan-20261113.md) §4.6/§6、[A2 任务基线](a2-task-baseline-20261113.md)、[ADR-0004](../../adr/0004-kotlin-rust-direction.md) 决定四/决定五、[安全模型](../../security-model.md) 备份与恢复、[v5 备份格式](../../backup-format-v5.md)、[备份与恢复](../../backup-and-restore.md)、[覆盖升级与维护](../../overwrite-upgrade-and-maintenance.md)。
> 边界：不改 `app/src/**`、`rust/**`、构建/CI 文件、`CHANGELOG.md`、已发布 tag/Release；不 push、不合并、不安装、不操作手机；不跑 Gradle/cargo。

---

## 0. 结论摘要（先读这一节）

| 项 | 本轮结论 |
|---|---|
| A3 是什么 | F05 数据保护闭环：v5 一键备份 / 恢复与迁移 / 自动计划 / 保留数据管理，全部走既有受管事务 + 维护屏障；Rust 只在**旁路纯函数**里做 `digest`/`verify_plan`/`archive` |
| 本轮交付 | 恢复计划契约 **PlanView v1 冻结**（字段 id/类型码/范围/枚举/摘要口径/原因码/多原因顺序）+ A3 切片与写域；§4.6 未定义的编码（`type` 码、字段 `flags`、枚举数值、原因码去重与顺序）在此补齐（见 §3.2–§3.4、§3.7） |
| Lead 裁决 | **已裁决（Q1–Q5，Lead 2026-11-13，全部按推荐 A；见 §7.1）**：`rootCount` = 本次计划实际写入根数、上限维持 64、>64 不投影并记 `PLAN_VIEW_ROOT_LIMIT`；`sensitivePolicy` 闭集 5 值；有效期畸形归 `PLAN_EXPIRED`；整份 v5 摘要由 JVM 计算；补齐 type/枚举/flags/原因码顺序属实现细节、不需 ABI v2 |
| R2 状态 | **已解锁**（Lead 评审通过）；R2 只改 `util/`、`rust/`、`tools/`、测试，**不改 `backup/`、不接生产调用点、不改 ABI v1 符号、零维护页**（§5.7、§6.4） |
| A3 与 R2/R3 | R2（PlanView 双实现）= A3 slice1；R3（archive 旁路）是 A3 最后一个增量；A3 主线（`backup/`）才触发那**一次**维护页 |
| 数据格式 | **不升级**：`formatVersion=5` 与 `runtime-descriptor.dataWrite` 不动；PlanView 是运行时投影，**不写进归档**（§3.1、§6.4） |

---

## 1. 目标与非目标

### 1.1 目标（A3 交付面）

1. **用户可见**：一键备份（无密码 v5）、手动导出/恢复、跨版本恢复与迁移、自动备份计划与保留数据管理，行为与现有 v5 语义一致，不新增格式。
2. **工程**：把恢复决策抽成**可差分的只读计划投影**（PlanView v1），JVM 与 Rust 两个实现给出同一 `VALID/INVALID` + 原因集合；分歧 fail-closed。
3. **Rust 首次进入数据路径**：`digest`（已由 R1 落地）、`verify_plan`（R2）、`archive_pack/unpack`（R3），全部为有界纯函数，默认关闭、一键回 JVM。
4. **可复核**：每个契约条款都能在代码里找到来源（本文逐条标 `file:line`）；§4.6 未定义的编码由本文冻结（§3.2–§3.4、§3.7），Lead 已确认属实现细节（Q5-A）。

### 1.2 非目标（负清单）

- **不做**执行路径：没有 `execute`/`argv`/路径参数/进程或信号操作；`PlanEnvelope`/`permitMac` 不进首期 ABI（ADR-0004 决定四）。
- **不升级数据格式**：不改 v5 容器、`formatVersion`、记录字段、`dataRead`/`dataWrite`；不把 PlanView 持久化为新格式（「双读单写」，roadmap §4 G3）。
- **不扩 Android 权限**：不新增存储/短信/屏幕/Root 能力；恢复仍写私有槽位后由本机映射提交。
- **不改** 已发布 tag/Release、包名/签名/versionCode；不在本机安装正式包。
- **不改** 现有备份/恢复的对外语义：`DATA_RESTORED_*` 结果码、`preview.json` 既有字段、桥协议 `3` 均保持；新增键只能是加法。
- **不替代** Kotlin 授权判断：`verify_plan` 只做结构/范围/策略版本/有效期校验，不证明计划安全（R1 §4.6 原文）。

### 1.3 跨轮不变式在本轮的具体化

| 不变式 | A3 的落实 |
|---|---|
| G1 包名/签名/versionCode | 不动；R2 零维护、A3 一次维护页（§6.4） |
| G3 双读单写 | 读：v5 + v1–v4（`LegacyBackupImporter`）；写：只写既有 v5 私有槽位与 SAF 目标，不抬版本号（§2.5、§3.1） |
| G7 Rust 无执行入口 | `verify_plan` 只读入参、无副作用、不读时钟/不写文件/不读路径（§4.6） |
| G10 门禁 | Java format / 单测 / `check-rust-boundaries.py` / `check-document-links.py` / 边界脚本（§5.5、§6.5） |

---

## 2. 现状核对（代码事实，逐条 file:line）

> 本节只陈述**当前工作树**（`2729112e`）读到的实现事实；行号会随改动漂移，引用以「类.方法 + 行号」为准。

### 2.1 v5 容器与完整性载荷

| 事实 | 位置 |
|---|---|
| magic `DSHADATA`（8 字节）+ `formatVersion=5` | `BackupArchive.java:9`、`116-117`、`185-186` |
| 记录序列：`int metadataLen` + JSON + `long payloadLen`；`-1` 结束；尾清单；32 字节 SHA-256 尾摘要 | `BackupArchive.java:178-234`（写 `150-168`） |
| 尾摘要覆盖此前全部容器字节（含结束标记与清单），摘要后必须立即 EOF | `BackupArchive.java:227-232`（`ARCHIVE_FOOTER`） |
| 逐文件 SHA-256：`FILE` 必须匹配 `[a-f0-9]{64}`；非 `FILE` 必须零载荷空摘要 | `BackupArchive.java:77-79`、`217-218`（`RECORD_CHECKSUM`/`PAYLOAD_CHECKSUM`） |
| 流式摘要口径：SHA-256、64 KiB 缓冲、上限 `BYTES + 256 MiB` | `BackupArchive.java:43-57`（`53` 行上限） |
| 读入即验：`METADATA_LIMIT` / `PAYLOAD_SIZE` / `ARCHIVE_TRUNCATED` | `BackupArchive.java:193`、`199`、`207` |
| 上限：载荷 16 GiB、记录 100000、根 512、单条元数据 16 KiB、尾清单 2 MiB、累计元数据 32 MiB | `BackupLimits.java:9`、`10`、`23-25`；`BackupArchive.java:300` |
| 路径规则：≤2048 字符、≤64 层、拒绝绝对/盘符/反斜杠/NUL/`.`/`..`/换行；冲突键 NFC+小写折叠 | `BackupLimits.java:29-60` |

### 2.2 恢复计划的实际结构（`NativeRestorePlan`）

一次恢复由「源归档 → 私有槽位 → 候选树 → 宿主事务提交」四段构成。计划对象的**真实字段**：

| 字段 | 含义 | 位置 |
|---|---|---|
| `manifest` | 源归档尾清单（`BackupArchive.read` 校验通过后返回） | `NativeRestorePlan.java:37`、`68-93` |
| `targets` | 逻辑根 id → 写入目标（`Target.write/read/merge/group/prefix/memberRead`） | `NativeRestorePlan.java:8-27`、`38`、`115` |
| `groups` | 组 id → 目标（同一 `group` 的多根合并写入；组即宿主事务的「根」） | `NativeRestorePlan.java:117-119` |
| `rootGroups` | 根 id → 组 id | `NativeRestorePlan.java:39`、`116` |
| `before` / `readBefore` / `memberBefore` | 提交前目标（及其可读视图/成员）的 `BackupTree.digest` 快照 | `NativeRestorePlan.java:41-42`、`120-126` |
| `warnings` | `UNAVAILABLE_MEMBERS_NOT_DELETED`、`LINK_METADATA_RETAINED`、`PROFILE_CONFIGURATION_REQUIRES_REVIEW`、`TARGET_LINK_REQUIRES_REVIEW`、`DATA_FORMAT_*` | `NativeRestorePlan.java:101`、`111`、`136-137`、`145`、`151`、`175` |
| `conflicts` | 已存在同名条目的计数 | `NativeRestorePlan.java:44`、`159-179` |
| `restore-records` 索引 | 每记录 `int len + JSON + ordinal`，读回时 `RESTORE_INDEX` 限界 | `NativeRestorePlan.java:51`、`69-92`、`181-196` |

**决策点（顺序即代码顺序）**：

1. 范围过滤：`selected` 不含 `application` 且不含该根 `scope` 时整根跳过（`99`）。
2. 记录级过滤：没有任何 `FILE`/`DIRECTORY`/`LINK` 记录的逻辑根不参与写入，并告警 `UNAVAILABLE_MEMBERS_NOT_DELETED`（`83-84`、`100-103`）。
3. 本机目标映射：`mapping.target(root)` 返回 `null` 表示「本机不落」（机器状态根、内置系统插件包），并附 `mapping.skippedReason(root)`（默认 `ROOT_REQUIRES_DESTINATION`）（`104-108`、`29-35`）。
4. 特殊根：`dsh-profile-config` / `name=profiles` → 告警 `PROFILE_CONFIGURATION_REQUIRES_REVIEW`（`109-111`）；实际落点由映射层改为隔离区（`NativeRestoreTargets.java:96-104`）。
5. 组冲突：同组不同写入目录 → `TARGET_GROUP_CONFLICT`（`117-119`）；无任何可写根 → `NO_RECOVERABLE_SCOPE`（`128`）。
6. 链接：绝对目标/空路径/逃逸/类型不可解析 → 只保留元数据 `LINK_METADATA_RETAINED`（`130-147`、`351-359`）。
7. 提交前复核：`TARGET_CHANGED`（组写目标或其可读视图或成员摘要变化，`202-208`、`215-218`）；`TARGET_TYPE_CONFLICT`（`260`、`266`、`335`）；`RESTORED_CHECKSUM`（逐文件回读校验，`283`）；`ARCHIVE_LINK_CYCLE`（`317-322`）；`LINK_LIMIT`（16384，`249`）；`NO_RECOVERABLE_PAYLOAD`（`343`）。

恢复编排在 `NativeBackupJobs.prepareRestoreInput`：输入规范化/解密/旧格式转换（`1330-1406`）→ `inspect` 建计划（`1408-1410`）→ `inputSha256`（`1412-1413`）→ 写 `preview.json`（`1415-1421`）→ 用户确认闸门（`1430-1441`）→ 维护屏障内复算 `INPUT_CHANGED` 后提交（`1442-1481`）。

### 2.3 `validateSummary` / manifest / index 的校验规则

`BackupArchive.validateSummary`（`280-317`）是**读侧权威**，逐条：

| # | 规则 | 失败码 | 行 |
|---|---|---|---|
| V1 | `formatVersion == 5`、`entries == index.count`、`bytes == index.bytes` 三者全等 | `MANIFEST_PAYLOAD_MISMATCH` | `282-285` |
| V2 | `operation ∈ {EXPORT, MAINTENANCE, RESCUE}` | `BACKUP_POLICY` | `286-290` |
| V3 | `integrity ∈ {QUIESCENT, EXTERNAL_CHECKED, PARTIAL, BEST_EFFORT}` | `BACKUP_POLICY` | `286-290` |
| V4 | 记录中出现 `MISSING`/`UNREADABLE` 时 `integrity` 必须是 `PARTIAL`/`BEST_EFFORT` | `FALSE_COMPLETE` | `291-292` |
| V5 | 必须有 `createdAt`、`appVersion`、`runtime`、`dataFormat`、`sensitivePolicy`、`plugins` 六个键 | `MANIFEST_PROVENANCE` | `293-298` |
| V6 | `roots` 是 List 且 ≤512 | `ROOTS_LIMIT` | `299-301` |
| V7 | 每个根是 Map；`id` 合法且不重复；`scope` 合法 | `ROOTS_FORMAT`/`DUPLICATE_ROOT`/`UNKNOWN_SCOPE` | `302-310` |
| V8 | 非 `application` 根，其声明 `scope` 必须等于该根实际记录的 scope 集合 | `ROOT_SCOPE_MISMATCH` | `311-315` |
| V9 | 声明的根集合必须等于索引中出现的根集合 | `ROOTS_PAYLOAD_MISMATCH` | `316` |

`Index.add`（`327-348`）额外保证：条目数 ≤100000（`ENTRY_LIMIT`）、累计元数据 ≤32 MiB、累计字节 ≤16 GiB、冲突键唯一（`DUPLICATE_PATH`）、父目录先于子项且目录/非目录不冲突（`PATH_TYPE_CONFLICT`）、`incomplete` 标记（`MISSING`/`UNREADABLE`）。

**宽松点（A3 必须自己兜住）**：V5 只检查「键存在」，不检查类型/长度（`293-298` 没有调用 `BackupJson.string/number`）。也就是说一个 `createdAt` 为字符串、`sensitivePolicy` 为数字的清单能通过读者，但**无法投影成 PlanView**。投影器必须逐字段做类型检查并在失败时**放弃投影**，不得用默认值伪造（§3.9 规则 P-4、§4.3）。

### 2.4 恢复的目标映射（本机决策点，`NativeRestoreTargets`）

| 决策 | 取值 | 位置 |
|---|---|---|
| `homeMode` | `STABLE` / `LEGACY`（由 `UserDataLayout.selected()` 与 `rootfs/root` 是否存在决定，写进 `mapping.json` 后固定） | `NativeRestoreTargets.java:52-53`、`57-61`、`74-79` |
| `projectDestination` | `PRIVATE` / `GUEST_HOME` | `62-64`、`72-73` |
| 映射种类 | 直接写（`native-settings`、`project-*`）/ 合并进 home（`dsh-child`，带 `prefix`、`memberRead`）/ 隔离进 `plugin-store`（插件包、profile 配置、legacy 插件树、可执行配置）/ 跳过（机器状态 `DataRootPolicy.machine`、内置系统插件 `BuiltinPlugins.system`） | `88-143`（`117`、`118-122`、`132`、`135-141`、`145-148`） |

映射层的硬拒绝：`ROOT_SCOPE`、`ROOT_MAPPING`、`PLUGIN_MAPPING`、`PROJECT_MAPPING`、`PROJECT_DESTINATION`、`UNKNOWN_LOGICAL_ROOT`（`92`、`100`、`116`、`131`、`137`、`142`）。

### 2.5 v1–v4 旧格式导入

`LegacyBackupImporter` 只在私有槽位把旧包**转换成 v5**，再走同一预检：

- 旧版本号 1–4（`95-99`），scope ∈ {`full`,`sessions`,`settings`,`plugins`}（`100-105`）。
- 合成清单固定写：`operation=EXPORT`（`208`）、`integrity=EXTERNAL_CHECKED|PARTIAL`（`209`）、`dataFormat=LEGACY_<n>`（`215`）、`sensitivePolicy=LEGACY_PLAINTEXT`（`216`）、`legacyConfirmationRequired=true`（`226`）。
- 旧包无清单时只告警 `LEGACY_WITHOUT_MANIFEST`；清单冲突 `CONFLICTING_MANIFESTS`；插件依赖图节点必须是 `package-<20 hex>` 且有 Map 节点（`140-150`）。

**契约含义**：`operation/dataFormat/sensitivePolicy` 的取值集合必须覆盖这些真实写者，否则 v1–v4 导入后的恢复会被新的 `verify_plan` 判非法（§3.4 表）。

### 2.6 自动备份与保留

| 事实 | 位置 |
|---|---|
| 默认开启（`enabled` 缺省 `true`），`factoryResetPending` 时停用 | `AutomaticBackups.java:24-29`、`34-38` |
| 计划：`JobScheduler` 9137 周期 15 分钟的 Job，实际到期由 `AutomaticBackupPolicy.due(...)` 决定（每日时刻/1–168 小时间隔/停止后三种模式） | `AutomaticBackups.java:13`、`49-72`、`74-84` |
| 到期但仍在运行 → 延后，不强停业务（`AUTOMATIC_BACKUP_DEFERRED`） | `NativeBackupJobs.java:770-789` |
| 保留：只清理「automatic + 结果 COMPLETE/DATA_SAVED_PLUGIN_WARNINGS + 再次核验通过」的副本，按时间倒序保留最近 3 份，且目录内只允许 `portable.dshbak/verified.json/operation.json/source-checks` | `AutomaticBackupPruner.java:14-33` |
| 手动与未知记录不动；`busy` 或非 `FINISHED` 目录跳过 | `AutomaticBackupPruner.java:26-27` |

### 2.7 配置快照

- 六个受管文件：`profiles/web/package.json`、`cordis.patch.yml`、`pnpm-workspace.yaml`、`pnpm-lock.yaml`、`settings.yaml`、`cordis.patch.yml`（`ConfigurationSnapshots.java:7-13`）。
- 槽位 `healthy-1..3` 与 `before-1..3`（`443`、`518`、`637-638`）；总快照上限 72、保留未引用目录 32、标记保留 8（`29`）；进程内写入口串行锁（`34`）。
- 恢复页面操作走 `HostDataTransaction`，且 `native settings` 不允许由快照直接改（`NATIVE_UNCHANGED`，`44-50`）。

### 2.8 维护屏障与宿主数据事务

- 屏障：`MaintenanceCoordinator.exclusive(...)`（`MaintenanceCoordinator.java:66`）与 `snapshot(...)`（`61`）；自动备份另外要求 `RuntimeTasks.tryEnterMaintenance()`（`RuntimeTasks.java:100-102`、`NativeBackupJobs.java:781-784`）。
- 事务 `HostDataTransaction`：`plan.json` 记录 `version/id/settingsBefore/settingsAfter/roots[]`（`106-136`），每行含 `before/after/had/candidatePresent`（`128-134`）；目标路径互不嵌套（`OVERLAPPING_TARGETS`，`112-122`）；`prepare` 时复核 `before`（`123-124`）。
- `commit`：复核 `before`/`after`/settings（`206-215`）→ `switching` → 旧目录移 `previous/<id>`、候选移入（`217-228`）→ 写 settings → `files-committed`/`settings-committed` → 逐根回读摘要 + `validation.verify()`（`233-247`）→ `finalized`（`248`）；失败时 `recover()` 逆序回滚（`249-306`）。
- 「退出不明不得自动移动正在使用的树」由 `CommitCheck` 契约表达（`23-31`：`mayRecover()` 默认 true，实现必须在验证子进程退出不明时返回 false）；失败路径只在 `mayRecover()` 为真时回滚（`249-257`）。

### 2.9 Rust 旁路现状（R1 已合入）与 A3 的三个缺口

已落地（`feat/rust-r1` 已在 `origin/main`）：

- `RustNative` 冻结 11 个 JNI 符号、`ABI_VERSION=1`、加载失败不重试（`util/RustNative.java:15-18`、`45-120`、`137-158`）。
- `digest` 真实实现：`fd + offset + length(1..=32 MiB) + algorithm(1=SHA-256,2=SHA-1)`，短读 `SHORT_READ`，错误不写输出（`rust/crates/dsha-core/src/digest.rs:28-36`、`127-139`；`jni.rs` 对应入口）。
- `RustBackends` 运行层开关默认 `false`，一键回 JVM（`util/RustBackends.java:16`、`24-26`、`62-70`）。
- `RustDigest` 只在 `INTERNAL`/`PANIC` 时允许 JVM 重做一次并标 `fellBack=true`；输入非法不重试（`util/RustDigest.java:186-224`）。
- `verify_plan` 仍是 **`NOT_IMPLEMENTED` 占位**：`plan.rs` 只有 5 个常量（`1-23`），`jni.rs:295-303` 直接返回 `ERR_NOT_IMPLEMENTED`。

**三个缺口（A3/R2 必须显式处理）**：

1. **`digest` 不可组合**：ABI 是「一次调用返回一个完整摘要」，没有 init/update/finish 会话。整份 v5 容器摘要（`BackupArchive.digest`，可达 16 GiB）**不能**由多次 `≤32 MiB` 调用拼出来。→ 见 §3.5 与 §7.1 Q4（裁决 A）。
2. **`verify_plan` 未实现**，且 §4.6 未定义 `type` 字节编码、枚举数值、原因码去重与顺序。→ 本轮补齐（§3.2–§3.4、§3.7）。
3. **`rootCount ≤ 64` 与真实根上限不一致**（§4.6 vs `BackupArchive.java:300` 的 512）。→ §7.1 Q1（裁决 A：上限维持 64，>64 不投影）。

### 2.10 §4.6 字段 → 现有代码来源 → 可确定性

| §4.6 id | 字段 | 现有代码来源 | 本轮是否可定 |
|---:|---|---|---|
| 1 | `formatVersion` | `BackupArchive.java:154`、`282`（恒 5） | ✅ 定 5 |
| 2 | `operation` | `NativeBackupJobs.java:727`、`MaintenanceDataSnapshot.java:375`、`LegacyBackupImporter.java:208`；读者集合 `BackupArchive.java:288` | ✅ 定 3 值 |
| 3 | `integrity` | `HostSnapshot.java:216-224`、`LegacyBackupImporter.java:209`；读者集合 `BackupArchive.java:289-290` | ✅ 定 4 值 |
| 4 | `rootCount` | `NativeRestorePlan.java:38`（`groups`）与 `BackupArchive.java:300`（512） | ✅ 已裁决（Q1-A）：语义 = 计划写入根数，上限 64，>64 不投影 |
| 5 | `entryCount` | `BackupArchive.java:155`、`283`（=索引计数，≤100000） | ✅ 上限对齐 |
| 6 | `byteCount` | `BackupArchive.java:156`、`284`（≤16 GiB） | ✅ 上限对齐 |
| 7 | `createdAtMs` | `NativeBackupJobs.java:728` | ✅ 无跨字段规则 |
| 8 | `sourceDigest` | `NativeBackupJobs.java:1412-1416`（`inputSha256`），提交前再验 `1446-1449` | ✅ SHA-256 整份明文容器 |
| 9 | `selectedMask` | `NativeBackupJobs.java:1280`（非空、⊆`SCOPES`）、`util/BackupScopes.java:12-13`（顺序） | ✅ 5 位位图 |
| 10 | `targetMappingKind` | `NativeRestoreTargets.java:52-64`（两个独立决策） | ✅ 组合编码 |
| 11 | `appVersion` | `NativeBackupJobs.java:733`（`BuildConfig.VERSION_NAME`；身份清单 `ci/release-identity*.json` 当前 38 字符） | ✅ ≤256 |
| 12 | `dataFormat` | `NativeBackupJobs.java:735`（`UNINSPECTED`）、`MaintenanceDataSnapshot.java:379`、`LegacyBackupImporter.java:215` | ✅ 自由串 ≤64 |
| 13 | `sensitivePolicy` | `NativeBackupJobs.java:737`、`1316`、`BackupArchive.java:266`、`MaintenanceDataSnapshot.java:380`、`LegacyBackupImporter.java:216` | ✅ 已裁决（Q2-A）：闭集 5 值 |
| 14 | `pluginCount` | `plugins.nodes`（`NativePluginGraph.java:448-459`），上限 4096（`312`） | ✅ |
| 15 | `planDigest` | 无现有代码 | ✅ 本轮定义（v1 不校验内容） |

---

## 3. 恢复计划契约冻结 v1

### 3.1 总则

1. **PlanView 是运行时投影，不是持久格式**：只在一次恢复作业内部存在（内存 + 可选诊断记录）。**不得写进归档、不得写进 `runtime-descriptor`、不得改 `formatVersion`**。
2. **唯一生产者**是 Kotlin（A3 的 `backup/` 投影器）；**两个校验者**是 JVM `PlanViewValidator` 与 Rust `verify_plan`。Rust **不解析 JSON**、不接路径/命令、不读时钟（`nowMs` 是入参）。
3. **调用时机**（A3 接线，R2 不实现）：① 预览建立后、写 `preview.json` 前（`nowMs` = 当时，编码进视图），结果只入诊断；② 提交前、`INPUT_CHANGED` 复核之后，用 `nowMs` = 当时**重新编码 PlanView**（重算 `planDigest`）得到新视图，再让 JVM/Rust 对同一份新字节校验——不得只改视图内嵌时间或沿用旧 `planDigest`；结果必须为 `VALID` 才允许进入 `buildCandidates`/事务；`INVALID` 或**两实现分歧**一律 fail-closed，中止本次恢复并保留原件与私有槽位。`verify_plan` 的 `nowMs` 唯一来源是视图内嵌值（JNI 无 nowMs 参数）。
4. **不替代读者**：`verify_plan` 通过**不代表**归档合法；`BackupArchive.read`/`validateSummary` 仍是唯一读侧权威。反过来，`validateSummary` 通过也不保证 PlanView 可投影（V5 只查键存在，§2.3）。
5. **单向信息流**：PlanView 只携带决策所需的**摘要事实**（计数、枚举、位图、摘要），不回传可执行内容、不携带路径、不携带文件清单。

### 3.2 字节布局与类型编码（补齐 §4.6 未定义项）

布局按 R1 §4.6 原文（小端、字段按 id 升序、无重复、总长 ≤ 2 MiB）。**本轮补齐**：字段值类型码、字段 `flags` 语义、对齐规则。

头（48 字节，紧凑，无对齐填充）：

| 偏移 | 类型 | 字段 | 本轮规则 |
|---:|---|---|---|
| 0 | bytes[4] | magic `DSHP`（`44 53 48 50`） | 不符 → `PLAN_STRUCTURE` |
| 4 | u16 | `viewVersion` | 必须 = 1（其它 → `PLAN_STRUCTURE`；不是 `POLICY_VERSION`） |
| 6 | u16 | `policyVersion` | 允许集合由本文冻结，首期 {1}；其它 → `PLAN_POLICY_VERSION` |
| 8 | u32 | `totalLength` | 必须 == `viewLength`（→ `PLAN_STRUCTURE`） |
| 12 | u32 | `fieldCount` | v1 必须 = 15；>64 → `PLAN_LIMIT` |
| 16 | u32 | `flags` | bit0=hasExpiry；其余位必须 0（→ `PLAN_STRUCTURE`） |
| 20 | u64 | `generatedAtMs` | Kotlin 时钟；不参与其它校验 |
| 28 | u64 | `expiresAtMs` | 0=无；语义见 §3.6 |
| 36 | u64 | `nowMs` | 纯入参；Rust 绝不读系统时钟 |
| 44 | u32 | `reserved` | 必须 0（→ `PLAN_STRUCTURE`） |
| 48… | — | 字段区 | 每字段 `u16 id, u8 type, u8 flags, u32 length, value[length]`，**紧凑排列、无填充**；u32/u64 按小端跨字节解码（不要求自然对齐） |

**类型码（本轮冻结，R0 未定义）**：

| 码 | 名称 | `length` 约束 |
|---:|---|---|
| 1 | `U8` | = 1 |
| 2 | `U32` | = 4 |
| 3 | `U64` | = 8 |
| 4 | `BYTES32` | = 32 |
| 5 | `UTF8` | 1..=4096（`plan.rs:20` 的 `MAX_FIELD_BYTES`）；各字段另有更紧上限（§3.3） |

未知类型码、`length` 与类型不符 → `PLAN_STRUCTURE`。**字段 `flags`（每字段头里的 u8）v1 必须为 0**（→ `PLAN_STRUCTURE`）；该字节预留给将来的「非关键扩展字段」，v1 未启用，故任何未知字段 id 都按关键字段拒绝（`PLAN_UNKNOWN_FIELD`）。

### 3.3 字段表（id / 类型 / 范围 / 来源 / 截断规则）

v1 **必须**恰好包含 1–15 全部字段各一次、id 升序；缺、多、乱序、重复 → `PLAN_STRUCTURE`；未知 id → `PLAN_UNKNOWN_FIELD`。

| id | 字段 | 类型 | 范围（越界 → 原因码） | 生产者来源 | 截断/失败规则 |
|---:|---|---|---|---|---|
| 1 | `formatVersion` | U32 | 必须 == 5（`FIELD_RANGE`） | `manifest.formatVersion`（`BackupArchive.java:154`） | 值不符即不投影 |
| 2 | `operation` | U8 | {1,2,3}（`FIELD_RANGE`） | `manifest.operation`（§2.10） | 见 §3.4 |
| 3 | `integrity` | U8 | {1,2,3,4}（`FIELD_RANGE`） | `manifest.integrity`（`HostSnapshot.java:216-224`） | 见 §3.4 |
| 4 | `rootCount` | U32 | 1..=64（`FIELD_RANGE`） | 本次计划**实际写入根数** = `plan.groups.size()`（= `preview.restoreRoots`，`NativeBackupJobs.java:1417`） | >64 时**不投影、不调 Rust**，记类型化诊断 `PLAN_VIEW_ROOT_LIMIT`，JVM 权威恢复照常（不得截断成 64） |
| 5 | `entryCount` | U32 | 1..=100000（`FIELD_RANGE`） | `manifest.entries`（= 索引计数，`BackupArchive.java:283`） | 不截断 |
| 6 | `byteCount` | U64 | 0..=16 GiB（`FIELD_RANGE`） | `manifest.bytes`（`BackupArchive.java:284`） | 不截断 |
| 7 | `createdAtMs` | U64 | 任意 u64；**无跨字段规则** | `manifest.createdAt`（`NativeBackupJobs.java:728`） | 类型不符（字符串/BigDecimal 越界）→ 不投影 |
| 8 | `sourceDigest` | BYTES32 | 长度 32；内容 `verify_plan` 不校验 | `inputSha256`（`NativeBackupJobs.java:1412-1416`） | 见 §3.5 |
| 9 | `selectedMask` | U32 | 1..=31（`FIELD_RANGE`），bit5–31 必须 0 | `selected` 集合（`NativeBackupJobs.java:1280`） | 见 §3.4 |
| 10 | `targetMappingKind` | U8 | {1,2,3,4}（`FIELD_RANGE`） | `NativeRestoreTargets.homeMode` + `projectDestination`（`52-64`） | 见 §3.4 |
| 11 | `appVersion` | UTF8 | 非空、≤256、UTF-8、无 NUL | `manifest.appVersion`（`NativeBackupJobs.java:733`） | >256 **不投影**（不截断伪造） |
| 12 | `dataFormat` | UTF8 | 非空、≤64、UTF-8、无 NUL | `manifest.dataFormat`（§2.10） | 同上 |
| 13 | `sensitivePolicy` | UTF8 | 非空、≤64、UTF-8、无 NUL；闭集 5 值见 §3.4 | `manifest.sensitivePolicy` | 同上 |
| 14 | `pluginCount` | U32 | 0..=4096（`FIELD_RANGE`） | `plugins.nodes.size()`（`NativePluginGraph.java:312`、`448-459`）；非 v2 图（如旧包 `legacyVersion` 形态）或无 `nodes` → 0 | 不截断；>4096 不投影 |
| 15 | `planDigest` | BYTES32 | 长度 32；v1 `verify_plan` **不校验内容** | 生产者自行计算（§3.5） | — |

**字符串通用规则（v1 冻结）**：非法 UTF-8 → `PLAN_STRUCTURE`；含 NUL → `FIELD_RANGE`；空串 → `FIELD_RANGE`；超字段上限 → `FIELD_RANGE`。`createdAtMs`/`byteCount`/`entryCount`/`pluginCount` 的超界按上表 `FIELD_RANGE`。

**多原因叠加（v1 冻结）**：多个独立违规同时计入原因集合，不因先命中一条而丢弃其余。示例：空串同时违反 UTF8 类型长度（`length 1..=4096`）与非空规则 → `STRUCTURE+FIELD_RANGE`；`dataFormat` 4097 字节同时超类型上限与字段上限 → `STRUCTURE+FIELD_RANGE`；T5/T6 夹具的叠加集合以 §5.2 为准。

**`PLAN_VIEW_ROOT_LIMIT` 的归属**：它是 **Kotlin 投影器的类型化诊断**（写诊断/`preview` 加法键，属 A3 slice2），**不是** `verify_plan` 的原因码——原因码保持 20–25 冻结，新增原因码 = ABI v2。

### 3.4 枚举、位域与推导规则

| 字段 | 码 | 名称 | 代码依据 |
|---|---:|---|---|
| `operation` | 1 | `EXPORT` | `NativeBackupJobs.java:727`、`LegacyBackupImporter.java:208`、`BackupArchive.java:288` |
| | 2 | `MAINTENANCE` | `MaintenanceDataSnapshot.java:375`、`BackupArchive.java:288` |
| | 3 | `RESCUE` | `NativeBackupJobs.java:727`、`1310`、`BackupArchive.java:288` |
| `integrity` | 1 | `QUIESCENT` | `HostSnapshot.java:224` |
| | 2 | `EXTERNAL_CHECKED` | `HostSnapshot.java:223`、`LegacyBackupImporter.java:209` |
| | 3 | `PARTIAL` | `HostSnapshot.java:219` |
| | 4 | `BEST_EFFORT` | `HostSnapshot.java:221` |
| `targetMappingKind` | 1 | `STABLE_PRIVATE` | `homeMode=STABLE` + `projectDestination=PRIVATE`（默认路径） |
| | 2 | `LEGACY_PRIVATE` | `homeMode=LEGACY` + `PRIVATE` |
| | 3 | `STABLE_GUEST` | `STABLE` + `GUEST_HOME` |
| | 4 | `LEGACY_GUEST` | `LEGACY` + `GUEST_HOME` |

`targetMappingKind` 推导式（可复核）：`code = 1 + (homeMode==LEGACY ? 1 : 0) + (projectDestination==GUEST_HOME ? 2 : 0)`。两个决策是 `mapping.json` 里持久化的独立事实（`NativeRestoreTargets.java:46-70`），所以用 2×2 编码而不是「主导种类」推断；后者会丢失信息且无法差分。

`selectedMask`：bit0=`application`、bit1=`sessions`、bit2=`settings`、bit3=`plugins`、bit4=`projects`（顺序取自 `util/BackupScopes.java:12-13`，与 `NativeDataLocations.SCOPES` 同一集合）。非零是硬要求（`NativeBackupJobs.java:1280` 拒绝空集）。

`sensitivePolicy` v1 **闭集（5 值，已裁决 Q2-A）**：`{UNENCRYPTED, PASSWORD_ENCRYPTED, APP_PRIVATE_STAGING, LEGACY_PLAINTEXT, PRIVATE_LOCAL_NATIVE_STATE_IN_PLACE}`（来源见表 §2.10 id13）。这 5 个值覆盖**全部生产写者**；未知值 → `FIELD_RANGE`。新增写者值必须同时抬 `policyVersion`（=2）并改本基线，不允许多值自由扩写。

### 3.5 摘要口径（`sourceDigest` / `planDigest`）

**`sourceDigest`**（v1 冻结）：

- 语义：`plain` 明文 v5 容器的整份 SHA-256，即 `NativeBackupJobs.java:1412-1416` 的 `inputSha256`（提交前 `1446-1449` 再算一次，不等即 `INPUT_CHANGED`）。它是 PlanView 与源字节的唯一绑定。
- 算法：`algorithm=1`（SHA-256），**整份文件**，`offset=0`、`length=fileSize`，流式 64 KiB 分块（`BackupArchive.java:43-57`），总量上限 `BYTES + 256 MiB`。
- **实现归属**：此摘要由 **JVM** 计算（`BackupArchive.digest`）。理由：R1 的 `digest` ABI 是单次调用返回完整摘要，`length ≤ 32 MiB`（`digest.rs:33`），**不可组合**出整份摘要；在 ABI 增加增量会话前，Rust 不能承担整份摘要（§7.1 Q4，裁决 A 已确认此结论）。
- 若 A3 将来需要 Rust 参与：只能对 `≤32 MiB` 的**区间**做独立摘要（例如归档尾部 32 MiB 复核），不得声称等价于整份摘要。

**`planDigest`**（v1 冻结计算式，v1 不校验内容）：

```
planDigest = SHA-256( view[0, planDigestValueOffset) || 0x00 * 32 || view[planDigestValueOffset+32, totalLength) )
```

即「把字段 15 的 32 字节值置零后，对整个视图字节求 SHA-256」。这样生产者/诊断可以自校验视图未被意外改写，而 `verify_plan` 保持纯结构校验（§3.7 没有 `PLAN_DIGEST` 原因码；新增内容校验 = 新原因码 = ABI 升级）。

### 3.6 时间语义

| 字段 | 语义 | 规则 |
|---|---|---|
| `generatedAtMs` | Kotlin 生成视图时的墙钟（`System.currentTimeMillis()`） | 只记录；不与其他字段比较 |
| `createdAtMs`（字段 7） | 归档自身的创建时间（`manifest.createdAt`） | 不与 `generatedAtMs` 比较（设备时钟可回拨，加规则会产生误拒） |
| `expiresAtMs` | 计划有效期 | `flags.bit0=0` 时必须为 0；`bit0=1` 时见下 |
| `nowMs` | 编码进视图的调用方「当前时间」 | `verify_plan` 只读视图内嵌值与 `expiresAtMs` 比较，**不读系统时钟**；需要新时刻必须重新编码视图（JNI 无 nowMs 参数） |

有效期判定（v1，按 §4.6 字面冻结，已裁决 Q3-A；`expiresAtMs <= generatedAtMs` 与 `nowMs >= expiresAtMs` 同归 `PLAN_EXPIRED`，v2 再考虑拆分）：

- `hasExpiry=1` 时：`expiresAtMs > generatedAtMs` 且 `nowMs < expiresAtMs` → 通过；否则 → `PLAN_EXPIRED`。
- `hasExpiry=0` 时：不检查时间；`expiresAtMs` 必须为 0（否则 `PLAN_STRUCTURE`）。
- `nowMs < generatedAtMs`（时钟回拨）：v1 **不拒绝**（无此规则；`nowMs` 由调用方给出，加规则只会误伤），但要记录在诊断里。

### 3.7 原因码、去重与顺序

原因码取 R0 冻结的 `ERR_PLAN_*` 的**绝对值**（`rust/crates/dsha-core/src/abi.rs:29-34`），与 `verifyPlan` 返回码一一对应：

| 码 | 名称 | 触发（v1 完整列表） |
|---:|---|---|
| 20 | `PLAN_STRUCTURE` | magic 不符；`viewVersion != 1`；`totalLength != viewLength`；`viewLength < 48`；`fieldCount != 15`；`flags` 保留位；`reserved != 0`；字段顺序/重复；字段 `flags != 0`；类型码未知或长度不符；字段区总长与 `totalLength` 不等（含尾随字节）；`hasExpiry=0` 而 `expiresAtMs != 0`；非法 UTF-8；摘要字段长度 ≠ 32（后者也可归 20，见下） |
| 21 | `PLAN_FIELD_RANGE` | `formatVersion != 5`；枚举越界（`operation`/`integrity`/`targetMappingKind`）；`rootCount`/`entryCount`/`byteCount`/`pluginCount` 越界；`selectedMask` 越界；字符串空/NUL/超上限 |
| 22 | `PLAN_POLICY_VERSION` | `policyVersion ∉ {1}` |
| 23 | `PLAN_EXPIRED` | `hasExpiry=1` 且（`expiresAtMs <= generatedAtMs` 或 `nowMs >= expiresAtMs`） |
| 24 | `PLAN_LIMIT` | `viewLength > 2 MiB`；`fieldCount > 64` |
| 25 | `PLAN_UNKNOWN_FIELD` | 出现 id ∉ 1..15 的字段 |

**多原因**：不因首错提前返回。原因载荷是**码集合**（`u32 count` + `count × u32`），v1 规则：

1. 同一码最多出现一次（去重）；码升序排列。
2. `count ≤ min(maxReasons, 6)`；`plan.rs:23` 的 `MAX_REASONS=8` 只是**缓冲余量**，不是 v1 期望条数。
3. 去重是**线格式决定**的：载荷只有码、没有字段号，重复码无法区分；若将来需要「每个违规字段一条」，那要改 ABI（新版本 + 新评审）。

**硬前置例外（与「不因首错提前返回」的关系）**：`viewLength < 48` 时无法解析头部，只报 `PLAN_STRUCTURE`；`viewLength > 2 MiB` 时按硬上限只报 `PLAN_LIMIT`；两者不继续遍历字段区。`fieldCount > 64` 属可解析字段区规则：继续解析，若同时 `fieldCount != 15` 则 `LIMIT+STRUCTURE` 同报（夹具 T6 已固化）。

### 3.8 返回值与缓冲契约

| 情况 | 返回 | 原因缓冲 |
|---|---|---|
| 全部通过 | `0` | 写 `count=0` |
| 有原因 | `1` | 写 `count` + 码集合（≤`maxReasons`） |
| `view`/`reasons` 为 null、非 direct、`viewLength < 0`、缓冲容量不足、`maxReasons < 0` | 负码 `INVALID_ARGUMENT(-1)` | 不写 |
| 原因条数 > `maxReasons` | 负码 `OUTPUT_BOUND(-3)`（调用方放大缓冲重试，**不产生半成品**） | 不写 |
| JNI 异常 pending | 负码 `JNI_PENDING(-8)` | 不写 |
| 捕获 panic | 负码 `PANIC(-7)` | 不写 |

调用约定：`view` 容量 ≥ `viewLength`；`reasons` 容量 ≥ `4 + 4*maxReasons`；两缓冲在调用期间保持强引用、不被并发修改（R1 §4.7 同一套 JNI 卫生规则）。

### 3.9 与现有读者的一致性约束

| # | 约束 | 理由 |
|---|---|---|
| P-1 | `verify_plan` **不得**被用作 `validateSummary` 的替代或前置门槛来放宽任何检查 | 两个检查的职责不同；读者是权威（§3.1-4） |
| P-2 | PlanView 的枚举/范围必须**覆盖全部生产写者**（含旧格式导入与维护快照） | 否则真实归档会被新校验判非法（`LegacyBackupImporter.java:208-216`、`MaintenanceDataSnapshot.java:375-380`） |
| P-3 | 视图不过滤、不截断、不四舍五入；超出字段上限就**不投影** | 截断后的事实无法审计，且会与 `preview.json` 不一致 |
| P-4 | 清单值类型不符/缺失时**放弃投影**并记录类型化诊断（建议 `PLAN_PROJECTION_FAILED`），不编造默认值 | V5 只查键存在（§2.3） |
| P-5 | 两实现分歧 = 缺陷：恢复中止并上报，**禁止**用 JVM 结果覆盖后继续 | R1 §5.4 |
| P-6 | `rootCount`/`entryCount`/`byteCount` 与 `preview.json` 既有字段（`entries`/`bytes`/`restoreRoots`）必须自洽 | 避免界面显示与校验结论矛盾 |

### 3.10 契约冻结项清单

| 类别 | 内容 |
|---|---|
| **本文字节级冻结**（R2 直接实现） | 头 48 字节布局与规则；类型码 1–5；字段 flags 必须 0；字段 id/类型/顺序/必需性；枚举数值（`operation` 1–3、`integrity` 1–4、`targetMappingKind` 1–4）；`selectedMask` 位序；字符串通用规则；原因码集合、去重与升序；返回值/缓冲契约；`planDigest` 计算式与「v1 不校验内容」 |
| **Lead 已确认**（2026-11-13，Q1–Q5 全部 A；Q5 明确：补齐 `type` 字节编码/枚举数值/字段 `flags` 语义/原因码去重顺序属实现细节，R2 首提交写进 `plan.rs` 常量 + 夹具 pin，**不需要 ABI v2**） | `rootCount` 语义与上限（Q1）；`sensitivePolicy` 闭集（Q2）；有效期畸形归 `PLAN_EXPIRED`（Q3）；R1 `digest` 只用于区间摘要（Q4） |
| **明确留到 ABI v2**（不得在 R2 顺手做） | 增量摘要会话；`planDigest` 内容校验（需新原因码）；每违规字段一条原因（需带字段号）；非关键扩展字段（字段 flags 语义）；`rootCount` 上限提升 |

---

## 4. PlanView 投影与 JVM 校验器设计

### 4.1 职责边界（三实现）

| 角色 | 位置 | 职责 | 明确不做 |
|---|---|---|---|
| 投影器（Kotlin/Java，A3 才接） | `backup/RestorePlanProjection.java`（新）+ `NativeRestorePlan` 只读事实 | 把「清单 + 计划决策 + `inputSha256` + `selected` + 映射决策」编码成 `PlanView` 字节 | 不做校验裁决；不读系统时间之外的东西；不写归档 |
| JVM 校验器（`util/`，R2） | `util/PlanViewCodec.java`、`util/PlanViewValidator.java` | 纯字节编解码 + 规则校验 + 原因集合 | 不解析 JSON、不读文件/系统时钟/环境、不写日志、无静态可变状态 |
| Rust 第二实现（R2） | `rust/crates/dsha-core/src/plan.rs` + `jni.rs::verifyPlan` | 同一规则的第二实现 | 不读时钟、不写日志、不分配与输入无关的内存、无 `unsafe` |

**为什么 R2 不改 `backup/`**：投影器需要 `NativeRestorePlan`/`NativeRestoreTargets` 的具体类型，而 `backup/` 全树在 `launcherInputs` 内（`runtime-descriptor.json` 的 `launcherInputs` 含 83 个 `backup/*.java`），提前改就会在 R2 阶段触发一次维护页。R2 只落「字节层 + 校验层」，投影器归 A3 slice2（§6.1）。

### 4.2 `PlanViewCodec`（纯 Java、零 Android import、放 `util/`）

```java
package com.deepseekharness.app.util;

/** PlanView v1 规范化编码/解码；不解析 JSON、无副作用。 */
public final class PlanViewCodec {
  public static final int VIEW_VERSION = 1;
  public static final int HEADER_BYTES = 48;
  public static final int MAX_VIEW_BYTES = 2 * 1024 * 1024;
  public static final int FIELD_COUNT = 15;

  /** 不可变投影输入（构造时已做类型检查；不含任何路径/文件清单）。 */
  public static final class View {
    public final int policyVersion;
    public final long generatedAtMs, expiresAtMs, nowMs;
    public final boolean hasExpiry;
    public final int formatVersion, operation, integrity, rootCount, entryCount;
    public final long byteCount, createdAtMs;
    public final byte[] sourceDigest, planDigest;
    public final long selectedMask;
    public final int targetMappingKind, pluginCount;
    public final String appVersion, dataFormat, sensitivePolicy;
  }

  public static byte[] encode(View view);              // 规范化字节；超限抛类型化异常，不截断
  public static View decode(byte[] bytes) throws PlanViewException; // 结构/UTF-8 校验后返回
  public static byte[] withZeroPlanDigest(byte[] view); // §3.5 自校验用
}
```

约束：只用 `java.util`/`java.nio`；不 `import android.*`；不 `java.io.File`；不 `System.currentTimeMillis()`（时间全部来自 `View`）；编码结果是**确定性字节**（同输入同输出，供差分与 fixture pin）。

### 4.3 `PlanViewValidator`（纯 Java）

```java
public final class PlanViewValidator {
  public static final int VALID = 0, INVALID = 1;
  public static final int REASON_STRUCTURE = 20, REASON_FIELD_RANGE = 21,
      REASON_POLICY_VERSION = 22, REASON_EXPIRED = 23, REASON_LIMIT = 24,
      REASON_UNKNOWN_FIELD = 25;
  public static final class Result {
    public final boolean valid;
    public final int[] reasons;        // 去重、升序
    public final String diagnostic;    // 类型化短码，不含路径/内容
  }
  public static Result validate(byte[] view);  // nowMs 唯一来源是视图内嵌值（JNI 无 nowMs 参数）
}
```

要求：**fail-closed**（任何无法判定的结构 → `INVALID`+`STRUCTURE`，不抛到调用方）；**不解析 JSON、无副作用**；输入数组不被修改；无静态可变状态（可并发调用）；不读系统时钟/环境/文件。

**不提供 nowMs 覆盖重载**：需要新的当前时刻时，调用方必须用 `PlanViewCodec.encode(...)` 重新编码视图（含重算 `planDigest`），JVM 与 Rust 只对同一份已编码字节对拍；否则同字节两条口径可构成有效期绕过（2026-11-13 R2 评审 P1）。

### 4.4 Rust `plan.rs` 实现要求（R2）

- 在 `plan.rs` 补：`TYPE_*` 常量、字段 id 常量、枚举码常量、`validate(&[u8], now_ms) -> Result<(), Reasons>`；`MAX_*` 常量沿用（`1-23` 已有 5 个）。
- `jni.rs::verifyPlan` 由 `ERR_NOT_IMPLEMENTED` 改为真实实现，遵守 §3.8（返回 0/1/负码；`reasons` 只在 0/1 时写；JNI 每次调用后 `ExceptionCheck`；`catch_unwind`）。
- 边界：`#![forbid(unsafe_code)]` 保持（`plan.rs:5`）；不新增 crate；`f64`/浮点不参与；所有长度用 `checked_*` 运算。
- `tools/check-rust-boundaries.py` 的源清单/导出符号/依赖白名单必须保持 PASS（R2 只实现已冻结符号，不新增导出）。

### 4.5 差分 harness 与夹具

复用 R1 的骨架（`tools/rust/host-jvm-differential.py` + `tools/rust/host-jvm/RustHostProbe.java` + `tools/rust/build.py --host`）：

- JVM 侧用**生产类** `PlanViewCodec`/`PlanViewValidator`（R1 已建立「编译生产桥而不是副本」的先例）。
- 新增检查名必须写进 `EXPECTED_CHECKS` 固定集合（R1 评审 P2-7 的做法），漏跑/多跑都失败；`--report` 输出 JSON 供 CI artifact。
- 夹具：`tools/rust/fixtures.json`（小样本，十六进制/字段化描述）+ `tools/rust/make-fixtures.py`（确定性生成大样本到 `app/build/`，不入库）。最少覆盖 §5.2 矩阵的每条规则的正/反例，外加：15 字段完整视图、每字段一个边界值（64/65、100000/100001、16 GiB±1、4096/4097、expiry 前后 1 ms）、随机字节流（确定性种子）、截断/尾随 1 字节、字段乱序/重复/未知 id。
- **JVM 单测**：`app/src/test/java/com/deepseekharness/app/util/{PlanViewCodecTest,PlanViewValidatorTest,PlanViewDifferentialTest}.java`；差分测试沿用 `-Ddsha.rust.hostLib` + `-Ddsha.rust.required`（`RustDigestDifferentialTest.java:31-58` 的模式），缺库在 source-only 模式跳过、full 模式失败。

### 4.6 副作用与禁区（可执行检查）

`verify_plan` 及两个 JVM 类都必须满足：

1. 不解析 JSON、不读文件、不打开 fd、不建线程、不写 stdout/stderr/logcat。
2. 不接收路径/命令/argv；`PlanView` 里没有路径字段（字段表 §3.3 仅计数/枚举/摘要/短串）。
3. 不读系统时钟、不读环境变量、不读 `BuildConfig`。
4. 同输入重复调用结果完全一致（差分测试断言两次调用字节级相等）。
5. Rust 侧 `println!`/`log::`/`std::process`/`std::fs` 仍被边界脚本禁止（R1 §4.8 同一条）。

---

## 5. R2 实施范围与验收

> R2 = A3 slice1。**不接生产调用点、不改 `backup/`、不改 ABI v1 符号、不改数据格式、零维护页。**

### 5.1 改动文件清单（预计）

| 路径 | 性质 |
|---|---|
| `rust/crates/dsha-core/src/plan.rs` | 补齐常量 + `validate` 实现（既有 5 个常量与上限保留） |
| `rust/crates/dsha-core/src/jni.rs` | `verifyPlan` 真实实现（符号名/签名不变） |
| `app/src/main/java/com/deepseekharness/app/util/PlanViewCodec.java` / `PlanViewValidator.java` | 新增纯逻辑（**不要**加入 `managed-runtime-inputs.json` 的 `launcherSources`） |
| `app/src/test/java/com/deepseekharness/app/util/PlanViewCodecTest.java` / `PlanViewValidatorTest.java` / `PlanViewDifferentialTest.java` | 新增单测 + 差分 |
| `tools/rust/host-jvm-differential.py` | 新增 plan 检查 + 固定 `EXPECTED_CHECKS` |
| `tools/rust/host-jvm/RustHostProbe.java` | 新增 plan 探针（或新增 `PlanViewHostProbe.java` 并登记） |
| `tools/rust/fixtures.json` / `tools/rust/make-fixtures.py` | 新增夹具与生成器 |
| `tools/java-format-files.json` | 登记 5 个新 Java（`--check` 要求显式清单） |
| `rust/artifact.lock.json` | 源码变化 → 宿主 `.so` 摘要变化 → 按 §2.3 重 pin `x86_64-unknown-linux-gnu`（见 §5.6） |
| `docs/ci-nodes.md` / `tools/test-ci-workflows.py` | 仅当节点/期望集合变化时改（预计只需 `test-ci-workflows.py` 若新增 artifact 断言） |

**禁止触碰**：`app/src/main/java/com/deepseekharness/app/backup/**`、`app/src/main/assets/runtime-descriptor.json`、`app/build.gradle`、`ci/**`（R2 不新增 job）、`CHANGELOG.md`、`README*`、`AGENTS*`、`docs/releases/**`。

### 5.2 正/反例矩阵（每条规则 ≥1 正 1 反）

| # | 规则（§3 条款） | 正例 | 反例（期望） |
|---:|---|---|---|
| T1 | magic/头部（§3.2） | 合法 48 字节头 + 15 字段 | magic 改 1 字节 → `STRUCTURE` |
| T2 | `viewVersion` | =1 | =2 → `STRUCTURE` |
| T3 | `policyVersion`（§3.2） | =1 | =2 → `POLICY_VERSION` |
| T4 | `totalLength` | == `viewLength` | 差 1 → `STRUCTURE` |
| T5 | 视图上限（§3.7-24） | 2 MiB 整 | 2 MiB+1 → `LIMIT` |
| T6 | `fieldCount` | 15 | 14 或 16 → `STRUCTURE`；65 → `LIMIT` |
| T7 | `flags` 保留位 / `reserved` | 全 0 | 各置 1 位 → `STRUCTURE` |
| T8 | 字段顺序/重复 | 1..15 升序 | 交换两字段 / 重复 id → `STRUCTURE` |
| T9 | 未知字段 id | 15 个已知 | 追加 id=16 → `UNKNOWN_FIELD` |
| T10 | 字段 `flags` | 0 | =1 → `STRUCTURE` |
| T11 | 类型码/长度（§3.2） | 各字段正确 | U8 字段 length=2、未知类型码 → `STRUCTURE` |
| T12 | 尾随字节 | 字段区恰好结束 | 末尾多 1 字节 → `STRUCTURE` |
| T13 | `formatVersion` | 5 | 4 → `FIELD_RANGE` |
| T14 | `operation` | 1/2/3 各一次 | 0 或 4 → `FIELD_RANGE` |
| T15 | `integrity` | 1..4 各一次 | 0 或 5 → `FIELD_RANGE` |
| T16 | `rootCount` | 1 与 64（Q1-A：写入根数，上限 64） | 0、65 → `FIELD_RANGE`；生产者 >64 不投影并记 `PLAN_VIEW_ROOT_LIMIT` |
| T17 | `entryCount` | 1、100000 | 0、100001 → `FIELD_RANGE` |
| T18 | `byteCount` | 0、16 GiB（恰好上限） | 16 GiB+1 → `FIELD_RANGE`；超出 `u64` 的编码不存在（8 字节封顶） |
| T19 | `createdAtMs` | 0、任意 u64 | （无范围规则；只验证接受） |
| T20 | `sourceDigest` | 32 字节任意内容（含全 0） | 31/33 字节 → `STRUCTURE` |
| T21 | `selectedMask` | 1、31 | 0、32、bit5 → `FIELD_RANGE` |
| T22 | `targetMappingKind` | 1..4 | 0、5 → `FIELD_RANGE` |
| T23 | `appVersion` | 44 字符 | 空、257 字符、含 NUL、非法 UTF-8 → `FIELD_RANGE`/`STRUCTURE` |
| T24 | `dataFormat` | `UNINSPECTED`、`LEGACY_3`、`LOCAL_ORIGINAL_LAYOUT` | 空、65 字符 → `FIELD_RANGE` |
| T25 | `sensitivePolicy` | 5 个闭集值（Q2-A） | 未知值 → `FIELD_RANGE`；空 → `FIELD_RANGE` |
| T26 | `pluginCount` | 0、4096 | 4097 → `FIELD_RANGE` |
| T27 | `planDigest` | 32 字节 | 31 字节 → `STRUCTURE`；内容任意（v1 不校验） |
| T28 | 有效期（§3.6） | `hasExpiry=1`、`expiresAt=gen+1`、`now<expires` | `now==expires` → `EXPIRED`；`expires<=gen` → `EXPIRED`；`hasExpiry=0` 且 `expires!=0` → `STRUCTURE` |
| T29 | 多原因（§3.7） | 仅 1 条 | 同时 `FIELD_RANGE`+`POLICY_VERSION`+`EXPIRED` → 3 条、升序、去重 |
| T30 | 缓冲契约（§3.8） | `maxReasons=8` 足够 | `maxReasons=1` 但有 3 条 → `OUTPUT_BOUND(-3)`，缓冲不写；非 direct/null 缓冲 → `INVALID_ARGUMENT` |
| T31 | 幂等/无副作用（§4.6） | 连调两次字节与原因相等 | 输入数组调用后不变（断言） |
| T32 | 分歧 fail-closed（§3.9 P-5） | 两实现同结果 | 注入「Rust 返回不同结果」的测试桩 → 断言调用方**中止**而非继续（A3 接线后） |

### 5.3 差分与 fail-closed 断言

- JVM 与 Rust 在同一夹具上必须给出**完全相同的 `VALID/INVALID` + 原因集合**（比较集合而非首错）。
- 分歧在 CI 是硬失败；在运行时（A3 接线后）是恢复中止 + 类型化诊断，**不得**回退到 JVM 结果继续。
- 差分测试同时断言：Rust 路径真的被执行（`RustBackends` 开关 on + `-Pdsha.rust.required=true`），避免「全走 JVM 也算通过」。

### 5.4 默认关与一键回退

- 构建层不变：`-Pdsha.rustNative=off|prebuilt|build`（R1 §5.4）；R2 仍不接 `build-unsigned`，APK 内容不变。
- 运行层：`RustBackends` 默认 `false`；R2 只新增 `planSelection()`（与 `digestSelection()` 同构），默认关闭。
- 一键回退 = `RustBackends.setEnabled(false)`；不需要清数据、不改已写格式。**verify_plan 分歧不允许回退**（R1 §5.4 表）。

### 5.5 CI 接线（复用现有节点，不新增 job）

| 位置 | 现状 | R2 需要的改动 |
|---|---|---|
| `.github/workflows/ci-fast.yml` | 已有 `rust-differential` job（`32-36`），`_node-rust.yml` mode=differential 跑 `host-jvm-differential.py`（`_node-rust.yml:101-108`） | 脚本内新增 plan 检查即可，**workflow 无需改** |
| `_node-rust.yml` mode=check | `rust-check` 跑 fmt/clippy/test/边界/`--expect-lock` | 新 crate 测试随 `cargo test` 自动覆盖；`--expect-lock` 需先重 pin（§5.6） |
| `_node-unit-tests.yml` | `rust_host` 输入（R1 已接线） | 新测试类落在 `util/`，自动进 source/full 单测；差分用 `Assume` 跳过的行为与 R1 一致 |
| `java-format` | `tools/java-format-files.json` 显式清单 | 登记 5 个新文件 |
| `docs-check` | `check-document-links.py` | 新文档入索引后 PASS |

### 5.6 证据要求

| 证据 | 口径 |
|---|---|
| Rust 单测 | `cargo test --locked --offline`；新增 plan 单测计入总数并记录条数 |
| 主机差分 | `tools/rust/host-jvm-differential.py --lib … --report …`，**固定检查名集合**；报告条数与失败数入库到回执 |
| `--expect-lock` | R2 **改了 crate 源码** → 宿主 `.so` 字节必然变化，`x86_64-unknown-linux-gnu` 的既有 pin（284992 / `5af9ebc1…`，`rust/artifact.lock.json`）失效。按 R1 §2.3：连续 **3 次不同 runner 实例**一致后重新 pin；`aarch64-linux-android` 与本地 aarch64 宿主条目继续 `pending-cross-run`（前者需 `rust-native` 手动流程产物） |
| JVM 单测 | 本地 `javac` + `JUnitCore` 或 `dsha-gradle`（按 Lead 要求；一次一个构建）；CI `unit-source` / full 记录条数与 skipped |
| 负向证据 | 故意让 Rust 返回错误原因集合 → CI 失败（证明差分真的在比集合，不是只比布尔） |
| 未跑项 | 必须单列（本机交叉链接、Android `.so` 加载、16 KB 真机——仍归 R3） |

### 5.7 R2 明确不做

- 不改 `backup/`（不接投影器、不改 `NativeRestorePlan`/`NativeBackupJobs`）。
- 不改 ABI v1 的符号名/签名/错误码；不新增 JNI 导出；不升级 `ABI_VERSION`。
- 不改 `runtime-descriptor.json`（新 util 类**不**进 `launcherSources` → `runtimeId` 不变，仍 `5fd17eacd0597e478bb52b01f42e7659f084f90b30cf98566deb62cdac36a5fe`，齿轮单实例修复合入后的现行值）；R2 零维护，落地后用 `prepare-runtime-descriptor.py --check` 出「`runtimeId` 未变」回执。
- 不改数据格式、不改 `dataRead`/`dataWrite`、不接 `build-unsigned`、不做设备验收。

---

## 6. A3 实施切片与写域

### 6.1 切片表

| # | 内容 | 主要文件 | 依赖 | `runtimeId` | 维护页 |
|---|---|---|---|---|---|
| 0 | **本基线**（契约冻结 + 切片设计） | 本文件、`docs/documentation-index.md` | — | 不变 | 无 |
| 1 | **R2**：PlanView 编解码 + JVM 校验器 + Rust 第二实现 + 差分/夹具/CI | `util/PlanView*`、`rust/…/plan.rs`、`jni.rs`、`tools/rust/*`、测试 | 本基线已评审（Lead 2026-11-13） | **不变**（`5fd17eac…`） | **零** |
| 2 | 投影接线（预览期）：清单 + 计划决策 → PlanView；`verify_plan` 结果入诊断 | `backup/RestorePlanProjection.java`（新）、`backup/NativeRestorePlan.java`（只读事实）、`backup/NativeRestoreTargets.java`（暴露两决策）、`backup/NativeBackupJobs.java`、`preview.json` 加法键 | 1 | **变** | 一次 |
| 3 | 提交期二次校验 + 回退/诊断收口：`INPUT_CHANGED` 后用新 `nowMs` **重新编码**的新视图（`planDigest` 重算）再校验；`INVALID`/分歧中止；`RustBackends.planSelection()`；诊断回执 | `backup/NativeBackupJobs.java`、`util/RustBackends.java`、`backup/BackupOperationDiagnostics.java`/`BackupFailureDiagnostic.java`（加法） | 2 | 不变（同批） | 同批一次 |
| 4 | **R3**：`archive_pack/unpack` 旁路接入（gzip 流）+ 差分 + 体积/ABI/APK 门禁 | `util/RustArchiveCodec.java`、`backup/PortableBackupEnvelope.java`、`backup/StrictGzipInputStream.java`、`_node-rust.yml`、`_node-test-packages.yml` | 1、3 | 变（同批） | 同批一次 |
| 5 | A3 出口：双读单写证据、v1–v4 导入回归、自动计划/保留回归、覆盖安装真机、非实现者评审 | 证据文档、`CHANGELOG`/版本由 Lead 处理 | 2–4 | — | — |

**写域不重叠**：slice2/3 由同一实现者（`backup/`），slice1 由 Rust/JVM 侧实现者，slice4 与 slice2/3 共享 `backup/` 两个文件 → 必须**串行**合入（R1 §6 已定：R1 调用点 → R3 envelope 端口）。

### 6.2 与 R2/R3 的先后

```
本基线 ──► R2（slice1，零维护）──► A3 slice2/3（一次维护页）──► R3（slice4，同批）──► A3 出口（slice5）
                    └────────────── R3 的 crate 工作可与 slice2/3 并行开发，但合入排在 slice3 之后 ──────────────┘
```

- R2 必须先合入：没有 PlanView 字节契约，投影器无从写起。
- R3 可以先开发 crate（`gzip.rs`/`jni.rs`），但 `backup/` 调用点必须等 slice3 合入后串行改，否则同一文件双写。
- 若 Lead 希望 A3 只发一次版：slice1 可以单独合入（零维护），slice2–4 作为一次维护页发布。

### 6.3 与 A2 slice3/4 的关系

- A2 slice3（工作台 UI）/slice4（受控流收口）与 A3 的 `backup/` 写域**不重叠**（A2 写 `ui/`、`saf/`）。
- 但 A3 的备份源包含 SAF 目录（`SafBackupSource`）与 SAF 目标（`BackupDirectoryTarget`）：**A3 不修改 `saf/`**，只在回归清单里带上 A2 的 SAF 测试；若 A2 slice4 改动 `/app/saf/*` 限额/语义，A3 出口证据必须重跑一次 SAF 备份/导出用例。
- A2 已知窗口 **N3**（`ModuleRegistry.install` vs `close`）与 **N4**（`ledgerReady→legacy` 分裂）：A3 **不触达**——A3 不碰 `contract/` 与 `SafGrants` 迁移；A3 新增的写入都在 `backup/` 私有槽位与 SAF 目标，不写 `saf_grants`/`dsha_module_layout`。但 A3 的恢复会**读** SAF 授权状态（经 `SafGrants.lookup` 的现有语义），因此 A3 出口需确认「撤销后备份/导出按现有语义失败」不回归。

### 6.4 `launcherInputs` / `runtimeId` / 维护页

- `backup/` 全树在 `launcherInputs`（当前 83 个 `backup/*.java` 全部在 `runtime-descriptor.json` 的 `launcherInputs` 里）→ **slice2/3/4 任一批必变 `runtimeId` → 覆盖安装进一次整屏维护页**（I6 设计，不是缺陷）。基线值 `5fd17eac…`（齿轮单实例修复合入后），最终值由 `prepare-runtime-descriptor.py --write` 重算并随批次提交（生成物，不手改摘要）。
- `util/` 只有 139 个文件被**显式**列进 `launcherSources`，R1 的 3 个 Rust 桥文件与 R2 的 2 个 PlanView 类都**不在**其中 → R2 零维护。R2 落地后必须用 `prepare-runtime-descriptor.py --check` 给出「`runtimeId` 未变」回执。
- 数据格式不升级：`formatVersion=5`、`dataWrite=dsh-0.2.2-rc.1`、`dataRead=[dsh-0.2.1-rc.5, dsh-0.2.2-rc.1]`、`bridgeProtocol=3` 全不动（`RuntimeDescriptor.java:24-38`、`runtime-descriptor.json`）。

### 6.5 CI 门禁与回退点

| 门禁 | 命令 | 适用切片 |
|---|---|---|
| Java 格式 | `python3 tools/format-java-tree.py --check`（新文件必须进 `java-format-files.json`） | 1–4 |
| Kotlin 格式 | `python3 tools/format-kotlin-tree.py --check`（本批无 `.kt` 改动，仍随 CI 跑） | 全部 |
| 源码卫生 | `python3 tools/check-source-hygiene.py` | 全部 |
| 架构边界 | `python3 tools/test-core-boundaries.py`（基线只减不增） | 2–4 |
| Rust 边界 | `python3 tools/check-rust-boundaries.py`、`check-rust-toolchain.py`、`check-rust-vendor.py` | 1、4 |
| 文档链接 | `python3 tools/check-document-links.py` | 0、5 |
| 单测 | `dsha-gradle :app:testStandardDebugUnitTest`（full 0 skipped）／日常定向 `--tests` | 1–4 |
| 覆盖更新 | 真机覆盖安装：一次维护页、数据完好、无白屏（A3 slice2+） | 3 |

**回退点**：R2 = 运行层开关 false + 保留 JVM 校验器（数据无变化）；A3 slice2/3 = tag + 开关，删除投影调用点即回到当前恢复路径；R3 = 构建层 `off` + 运行层 false，已写归档仍可读（同一 gzip/v5 格式）。

### 6.6 Owner 与协调

| 角色 | owner |
|---|---|
| 本基线（契约冻结） | 本会话（`a3-baseline`，docs-only） |
| R2 实现（slice1） | Lead 指派（建议 Rust/JVM 侧实现者，非本基线作者） |
| A3 slice2/3（`backup/` 投影接线） | Lead 指派 |
| slice4（R3 archive 接线） | Lead 指派（与 slice2/3 串行） |
| 集成、descriptor、CI、发布、文档索引 | Lead |
| A3 出口非实现者评审 | 待指定（不得为实现者） |
| 真机覆盖验收 | 用户/Lead |

---

## 7. 风险与裁决记录

### 7.1 Lead 裁决（2026-11-13，5 项全部按推荐 A）

| # | 问题 | 裁决 |
|---|---|---|
| Q1 | `rootCount` 语义与上限（§4.6 的 64 vs 真实 512/2048） | **A**：语义 = 本次计划实际写入根数（`plan.groups.size()`，= `preview.restoreRoots`）；上限维持 64；>64 不投影、不调 Rust、记 `PLAN_VIEW_ROOT_LIMIT`，JVM 权威恢复照常 |
| Q2 | `sensitivePolicy` 闭集 vs 自由串 | **A**：闭集 5 值；新增值必须抬 `policyVersion`（=2）并改本基线；未知值 → `FIELD_RANGE` |
| Q3 | 有效期畸形（`expiresAtMs <= generatedAtMs`）原因码 | **A**：按 §4.6 字面归 `PLAN_EXPIRED`；「v2 拆分原因码」记入 ABI v2 清单 |
| Q4 | R1 `digest` 不可组合，A3 怎么用 | **A**：整份 v5 摘要继续由 JVM `BackupArchive.digest` 计算，Rust `digest` 只用于 ≤32 MiB 区间；A3 证据不得写「Rust 已接管整份摘要」；R1 §6 R1 行旧设想以本基线为准 |
| Q5 | 补齐 `type` 编码是否算改 ABI | **A**：`type` 字节编码、枚举数值、字段 `flags` 语义、原因码去重/顺序属实现细节，由基线冻结、R2 首提交写进 `plan.rs` 常量 + 夹具 pin，不需要 ABI v2；§3.10 分类保持不变 |

以下保留当时的选项历史（B/C 未被采用，仅作决策依据）。

**Q1（裁决 A）`rootCount` 的语义与上限：§4.6 的 `≤64` 与真实 512 / 一次恢复写入根可到 2048 不一致。**

- 代码事实：清单根上限 512（`BackupArchive.java:300`）；一次恢复的写入根（组）数受 `HostDataTransaction.prepare` 的 `roots.size() > 2048` 限制（`HostDataTransaction.java:99`）；每个项目根自成一个组，profile 配置与插件包共享 `plugin-store` 组（`NativeRestoreTargets.java:96-148`）。
- 选项：
  - **A（采用）** 保持 §4.6 的 64：`rootCount` 定义为**本次计划实际写入的根数**（`plan.groups.size()`，即 `preview.restoreRoots`）；>64 时**不投影**、不调 Rust、记录类型化诊断 `PLAN_VIEW_ROOT_LIMIT`；恢复照常由 JVM 读者/计划执行。代价：超大计划没有 Rust 第二实现覆盖（但 JVM 仍是权威，不影响安全）。
  - **B** 在 R2 前把上限改到 512 并新增 `rootIdsDigest` 字段——**这是 ABI 变更**，需新评审，推迟 R2。
  - **C** 把 `rootCount` 改成「清单根数」并保留 64——更糟：清单可达 512，会拒掉更多真实归档。
- **裁决：A。** B 记为 A3 之后的可选跟进（若实测超大计划常见），R2 不做。

**Q2（裁决 A）`sensitivePolicy` 是闭集还是自由串。**

- 选项：**A（采用）** 闭集 5 值（`UNENCRYPTED`/`PASSWORD_ENCRYPTED`/`APP_PRIVATE_STAGING`/`LEGACY_PLAINTEXT`/`PRIVATE_LOCAL_NATIVE_STATE_IN_PLACE`），未来新增值必须同时抬 `policyVersion`（=2）并改本文；未知值 → `FIELD_RANGE`。**B** 按 §4.6 字面只校验 UTF-8/非空/≤64，任何值通过。**C** 闭集 + 把未知值降级为「不投影」而不判 INVALID。
- **裁决：A。** 5 个值覆盖全部生产写者，闭集让「新写者」暴露在评审而不是悄悄进生产；C 会让 `verify_plan` 对同一输入给出「无法判定」之外的沉默。

**Q3（裁决 A）有效期畸形（`expiresAtMs <= generatedAtMs`）归哪个原因码。**

- §4.6 字面：`PLAN_EXPIRED`。语义上它更像字段范围错误（`PLAN_FIELD_RANGE`）。
- 选项：**A（采用）** 按 §4.6 字面用 `PLAN_EXPIRED`（本轮不放宽/不改冻结文案）；**B** 改文案用 `PLAN_FIELD_RANGE`（更好诊断，但要改 §4.6 并重新冻结）。
- **裁决：A。** 把「v2 拆分两个原因」记入 §3.10 的 ABI v2 清单。

**Q4（裁决 A）R1 `digest` 不可组合，A3 怎么用。**

- 选项：**A（采用）** 整份摘要继续由 JVM 计算（现状 `BackupArchive.digest`），Rust `digest` 只用于 ≤32 MiB 区间（如尾部复核、单记录 ≤32 MiB 的载荷摘要）；B 现在给 ABI 加增量会话（init/update/finish）——新 ABI 版本 + 新评审，R2 不做；C 用分块摘要「拼」（错误，摘要不可组合）。
- **裁决：A。** 与 R1 已验证的能力一致；A3 出口证据里必须写清「Rust 未参与整份摘要」，不得把 R1 的 digest 说成已接管 `BackupArchive`（R1 §6 R1 行里「`BackupArchive` 单个调用点」的设想需据此修正，以本基线为准）。

**Q5（裁决 A）补齐 `type` 字节编码是否算「改 ABI」。**

- 事实：§4.6 冻结了偏移/类型名/字段 id/上限，但没有定义 `type` 的**数值编码**、枚举**数值**、字段 `flags` 语义、原因码去重顺序——R0 的 `plan.rs` 也只落了 5 个常量。
- 选项：**A（采用）** 视为「实现细节」，由本文冻结、R2 首提交写进 `plan.rs` 常量与夹具，不需要 ABI v2；**B** 视为 ABI 变更 → R2 前需新一轮 ABI 评审。
- **裁决：A。** Lead 明确确认：`type` 字节编码、枚举数值、字段 `flags` 语义、原因码去重/顺序属实现细节，不需要 ABI v2；§3.10 的分类保持不变。

### 7.2 已知窗口是否被 A3 触及

| 窗口 | 内容 | A3 是否触及 |
|---|---|---|
| N3（A2 复审） | `ModuleRegistry.install` 持锁执行 factory、`close` 需等待，无法取消进行中的 install | **不触及**：A3 不改 `contract/`、不 install 模块 |
| N4（A2 复审） | `ledgerReady` 返回 false 后、走 legacy 前另一线程完成迁移 → 同一时刻一写旧命名空间一读账本 | **不触及**：A3 不写 `saf_grants`、不迁移授权；只经现有 `SafGrants` 读语义使用 SAF 源/目标。若 A2 slice4 收敛该窗口，A3 出口需重跑 SAF 备份/导出回归 |
| A2 slice3/4 未完成 | 工作台 UI、受控流收尾 | **不阻塞** A3：A3 不依赖工作台 UI；只依赖 `SafBackupSource`/`BackupDirectoryTarget` 现有语义 |
| R1 未跑的项 | 本机交叉链接、Android `.so` 加载、16 KB 真机 | **A3 出口需补**：R3 的签名 `.dev` 包真机冒烟；不在 R2 范围 |
| 历史明文备份（build336 及以前含 ADB 私钥） | 安全模型已声明 | A3 只按现有规则处理（不新增解密/导出面）；不得把旧包风险写成已修复 |

### 7.3 风险表（对本轮的具体形态）

| # | 风险 | 形态 | 止损 |
|---|---|---|---|
| R5 | 离线构建断裂 | R2 新增 `plan.rs` 实现后 CI 仍需 `cargo --offline --locked`；新增 crate 一律不允许 | 依赖白名单门禁（`check-rust-boundaries.py`）；本地 `cargo test --locked --offline` |
| R7 | 数据格式被顺手升级 | 有人把 PlanView 写进归档或给清单加字段 | 本文 §3.1 明确禁止；评审检查 `BackupArchive.java`/记录字段零改动；`dataWrite` 不变 |
| R9 | 语言锁存/覆盖率 | R2 只有 Java/Rust，不涉 Kotlin 与 `DshaApp` | 不计入风险，仅保留 |
| R-新1 | 校验与读者不一致 | PlanView 枚举漏掉真实写者 → 真实恢复被拒 | §3.4 枚举表以 `file:line` 对齐全部写者；T14/T15/T24/T25 用真实值做正例 |
| R-新2 | 「全走 JVM 也算通过」 | 差分测试在缺 `.so` 时被 `Assume` 跳过 | full/CI 用 `-Pdsha.rust.required=true`；报告记录 Rust 实际执行条数 |
| R-新3 | 双实现漂移 | JVM 与 Rust 各自改规则 | 同一份夹具 + 固定检查名集合 + 分歧硬失败；规则改动必须同批改两实现与本文 |

---

## 8. 给 Lead 的看板与索引建议（本会话未自行修改 §0.1）

按 roadmap §10「看板：只有 Lead 改 §0」，本会话**没有**改 `iteration-roadmap-20261112.md` 的看板；Lead 已确认 A3 看板行由 Lead 更新。建议 Lead 在 A3 行做**最小**状态补充（不改其他轮次）：

```text
| A3 | 数据保护 | 🟡 进行中（设计基线已评审） | 2026-11-13 | — | 备份/恢复/保留数据 | 一次维护页 | [A3 任务基线](a3-task-baseline-20261113.md)（契约 v1 冻结，Lead 已裁决 Q1–Q5；R2 已解锁） |
```

`docs/documentation-index.md` 的本条目已由本会话添加（任务明确授权）。

## 9. 版本记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v0.1 | 2026-11-13 | 初稿：A3 现状核对（逐条 `file:line`）、PlanView v1 契约冻结（补齐 type 码/枚举/`selectedMask`/`targetMappingKind`/摘要口径/原因码去重）、JVM 校验器与差分设计、R2 范围与验收、A3 切片与写域、5 项待裁决问题 |
| v0.2 | 2026-11-13 | Lead 评审通过并裁决 Q1–Q5（全部按推荐 A，见 §7.1）；按裁决定稿 §3.3/§3.4/§3.5/§3.6/§3.10；rebase 到 `origin/main`=`2729112e`（齿轮单实例修复），`runtimeId` 基线更新为 `5fd17eac…`；R2 已解锁 |
| v0.3 | 2026-11-13 | R2 非实现者评审修复轮（P1 nowMs 双口径 → 唯一来源=视图内嵌值，删除 Java override 重载；§3.1/§4.3/§6.1 同步为「重新编码」）；§3.7 补硬前置例外、§3.3 补多原因叠加示例；§2.10 appVersion 更正为 38 字符并指向 `ci/release-identity*.json`；基线更新为 `origin/main`=`c2e7aec1` |

---

*本基线已由 Lead 评审通过（2026-11-13，Q1–Q5 全部按推荐 A）；R2 已解锁，可开工。*
