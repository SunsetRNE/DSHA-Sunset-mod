# 备份策略辅助域 Kotlin 等价迁移（2026-11-18）

基线：`2cc4e646f8ab232eef0070c9d844b806241caf6e`（开工时 `origin/main`）。独立分支 `task/kotlin-backup-policy`；生产与新增测试阶段提交 `a6440d1a`。本批只转换下列 8 个辅助类的实现语言，保留现有调用者、8 个原测试和数据/权限/命名契约。共享格式清单、源码布局、host-test 清单、最终 descriptor 与证明由主代理集成，不在本分支更改。

## 范围与 Java 原件冻结

原 Java 共 647 行、24,701 字节；原件可从基线 Git 提交恢复。本轮还在忽略目录保存原文件及源摘要回执。

| 类（均在 `com.deepseekharness.app.util`） | Java 行数 | 原始 SHA-256 |
|---|---:|---|
| BackupExportGuidance | 88 | `cda051d0ae3881ec3e10d1330cca4a8d2483a781ea60ec60f7be0e24889da30a` |
| BackupFileNames | 46 | `5cb599242760847b58fd979648aa528c63e34ecd1cdb371f243edb7341f50d30` |
| BackupHealth | 58 | `1af63509a31aede22c1bda423111a5af8dbaf04eead5acedd57a5e567184ef10` |
| BackupScope | 207 | `7c59d39baec652de272b7e490530229717b67a03ba71397fca89f6c0ee308695` |
| BackupScopes | 20 | `dcd531a939489a5c90abd5e764a455b9dc441a5decf5c697be4af8811373dcf6` |
| BackupTaskKinds | 51 | `c35a5bdb92722ad8519936d46e60804393922793622ffd79ba66ad55bcdd0930` |
| BackupTaskState | 95 | `67cef2b3be49b152ffd40992c8574a1570e77df1ecd607e5a486481026c7a8e3` |
| ScheduledBackupOwner | 82 | `61d87c127979ea462a1189f8959782a7fba41150cb975378fa2f82ed45e85428` |

每个旧 `.java` 整文件替换为同目录同名 `.kt`，没有双份类实现、跨包搬迁或新增模块。没有修改 Rust 文件、自动备份实现、DSH runtime 依赖或移动补丁。边界说明遵循 [Kotlin 工具链](../../kotlin-toolchain.md) 与 [ADR0004](../../adr/0004-kotlin-rust-direction.md)。按任务指定的 `dsha-kotlin-equivalence` 技能运行 scope，得到 `applicable: true`、本工作树及上述 HEAD。

调用闭包包含备份管理与归档、备份任务持久化、UI 状态投影、导出 MIME、自动备份调度 owner、备份/恢复 instrumentation。外部实现全部保持原状，特别是既有 [AutomaticBackupService](../../../app/src/main/java/com/deepseekharness/app/backup/AutomaticBackupService.kt) 的 Kotlin 调用方式没有改动；正式全项目重编译由主代理串行完成。

## 保留的行为

- 范围整数和清单 ID、UI 顺序、归档前缀/后缀、导出 MIME 及持久化任务标识不变。部分范围继续不使用 `DSHA-backup-`；未知整数的旧默认投影与 `includes*` 布尔行为保持原样，不擅自统一为 full。
- `fromId` / `fromFileName` 使用 Java `trim()` 的 `<= U+0020` 判据；不扩大到 Unicode 空白。路径分隔符、尾斜杠、大小写、UTF-16 孤立代理项及归档 suffix 正则边界均有黄金覆盖。
- `BackupScope.ALL` 仍为同一个 public static final 可变 `int[]`；各次 `dshPaths` / `mergeSubdirs` 返回新的可变数组。`BackupScopes.ORDERED` 继续使用 JDK `List.of`，保留它的 null 查询拒绝；`ALL` 仍为 `Collections.unmodifiableSet(LinkedHashSet(...))`，保留顺序、身份与修改失败。
- `BackupFileNames` 的大小写处理仍使用 `Locale.ROOT`，正则保留 Java Pattern 行为；UUID 仍为 version 4 / variant 2，不把现有较宽 UUID 文件名识别收紧。
- `BackupHealth` 保留优先级、错误字符串只判断空串、严格超过 7 天的边界及 Java long 溢出，不新增饱和运算。
- `BackupTaskState` 的 8 个原同步方法仍锁实例 `this`；null kind/status/detail 输入、脱敏调用点、旧回调拒绝、RUNNING/PREVIEW 恢复为 INTERRUPTED、lastStage 独立更新以及 id 溢出保持。Snapshot 是普通身份对象，没有 data-class 相等性。
- `ScheduledBackupOwner` 的事件线程先验在所有方法最先执行；参数和 Ticket 都按引用身份判断。失效票不会提交/结束新请求；停止后的票仍保留精确 jobId；回调可重入、抛异常不吞掉。泛型 `P` 不增加非空限制，Runnable 的 null 失败点保留在成功 current 检查之后。

## Java API 对照与明确差异

冻结并比较 13 个 JVM 类型：8 个顶层类，以及 `BackupExportGuidance$Kind`、`BackupHealth$Level`、`BackupTaskState$Status`、`BackupTaskState$Snapshot`、`ScheduledBackupOwner$Ticket`。

本轮最终 Python classfile 解析对比 **123 个原非 private / 非 synthetic 成员及 private 构造器**：类 access/继承/泛型、成员名、描述符、泛型签名、checked exceptions、常量值、完整方法 flags（含 final/static/synchronized）及字段 final/volatile 均与 Java 基线一致，只有下述已批准的 Snapshot 构造器可见性差异；新旧 class major version 都为 61。本批原 API 没有 checked exception 声明，候选未添加。

以下字节码差异显式保留，不能称为“整个 API flags 或 class 文件逐字节一致”：

1. **Snapshot 构造器可见性**：原 Java 包级 `(long, String, Status, String, String)` 构造器，在 Kotlin 中写为 `internal constructor`，JVM 为 public。开工时已报告并获主代理明确接受这一项；参数、字段、null 行为和对象身份都由 Java API/黄金测试覆盖。其他原 private 构造器保持 private。
2. **原 44 项方法 ACC_FINAL 差异已消除**：r1 的 Kotlin 默认方法显式带 final，虽原所属类本就 final、不改变继承行为，但反射和 classfile 可观察到。主代理批准有界追加修订后，26 个静态桥使用 `@JvmStatic open fun` 与 `NON_FINAL_MEMBER_IN_OBJECT` 抑制；`BackupTaskState` 的 8 个同步方法、Snapshot 的 `busy` 与 `ScheduledBackupOwner` 的 9 个方法使用 `open fun` 与 `NON_FINAL_MEMBER_IN_FINAL_CLASS` 抑制。保持类 final、函数体、字段、构造器及 `@Synchronized` 不变；锁定 KGP 2.2.10 格式化后实编确认这 44 项 flags 与 Java 完全一致。此项仅记历史修订过程，不再作为最终 API 差异接受。
3. **Kotlin 编译器辅助成员**：6 个静态工具类增加 Companion，3 个枚举增加 `getEntries()`。`BackupTaskKinds` 的私有 ROWS 产生 synthetic 访问器。Ticket 增加 Companion、internal synthetic 工厂/访问器和带 `DefaultConstructorMarker` 的 synthetic 构造桥；原单参数构造器仍 private。新增票不会绕过 `current` 对活跃票的对象身份检查。synthetic 成员不是旧 Java API 的替代，也不声明为同进程安全隔离。

新增 [BackupPolicyApiTest](../../../app/src/test/java/com/deepseekharness/app/util/BackupPolicyApiTest.java) 从 Java 端直接调用并反射核验静态入口、字段、枚举、构造器、泛型、异常和同步位。追加修订在同一 helper 中强制所有原方法不带 final，并补齐状态类及调度 owner 的类 final 断言；没有放宽任何原断言。Snapshot 唯一批准的 ctor 可见性范围在测试中有明确注释。

## 黄金与实际执行证据

原 8 个测试文件完整保留。新增 [BackupPolicyGoldenTest](../../../app/src/test/java/com/deepseekharness/app/util/BackupPolicyGoldenTest.java) 13 项与 [BackupPolicyApiTest](../../../app/src/test/java/com/deepseekharness/app/util/BackupPolicyApiTest.java) 5 项先在 Java 原实现验证，再用于 Kotlin 候选，未通过改期望或增加 skip 掩盖差异。

| 检查 | 结果与范围 |
|---|---|
| Java 基线 | 8 原 Java 类新编译，原 8 测试 + 新增 2 测试共 **53 tests / 0 failures / 0 errors / 0 skipped / 0 assumptions** |
| Kotlin 候选 r1 | KGP 2.2.10 新编译全部 8 个 Kotlin 类，再重新编译同一 10 个 Java 测试类；**53 / 0 / 0 / 0 / 0** |
| flags 修订 Java 基线 | 加强方法 non-final/类 final 断言后，重新编译同一 10 测试并对冻结 Java 类执行；**53 / 0 / 0 / 0 / 0** |
| 最终 Kotlin 候选 flags-r2 | 先锁定格式化，再新编全部 8 Kotlin 类及同一 10 Java 测试；**53 / 0 / 0 / 0 / 0** |
| 最终严格 API | 13 类型 / 123 原成员与 private ctor；完整 class/member flags 一致，仅保留批准的 Snapshot ctor 可见性差异 |
| Kotlin 格式 | 锁定 ktfmt 0.64，8 个文件定向格式化并验证幂等，exit 0 |
| 新 Java 测试格式 | 锁定 google-java-format 1.22.0，2 文件定向格式化，精确 Java tokens 保持不变，exit 0 |
| 架构边界 | `python3 tools/test-core-boundaries.py`：25 tests 通过，exit 0 |
| 源码空白 | `git diff --check` 通过 |

JVM 槽由主代理明确分配：javac/JUnit/javap 使用 128 MiB、kotlinc 使用 256 MiB，单进程串行，无 Gradle。Java 基线首次 JUnitCore 即通过；随后仅补 RunListener 对 frozen Java class 的 errors/skipped/assumptions 计数，不能把重复执行累加成 106 个不同用例。

只读依赖来自同机 `toolchain-arm64` 的真实 `2cc4e646` 编译产物：在复用前已逐一比对 **1080 份 main/standard/debug Java/Kotlin 源码 SHA-256 全等**。这份旧产物只提供未修改依赖，8 个被测 Java/Kotlin 类都在本轮新输出目录生成；候选 classpath 把新输出置于依赖之前。没有将旧整树输出当候选编译证明。

忽略目录 `app/build/kotlin-backup-policy/` 保存本轮命令、classpath、源摘要与日志，不提交本机产物。主要证据摘要：

| 证据（相对此忽略目录） | SHA-256 |
|---|---|
| `java-baseline/inputs.json` | `87d3fd6ac49908d8dfb0619be49a96ba3655cae9962ce7fb94235832c28e168d` |
| `java-baseline/java-api.txt` | `a8fdfbfe42a5d9cbaf85566b0ee1d6bfab2df221813968184d259e726018c7ad` |
| `counted-baseline/junit.log` | `658d45d0e419edd6cfe44af08b8451450c8647ce61682216e710634d855f41aa` |
| `candidate-r1/receipt.json` | `8c551a7b2fb0a8caf26c9db52d6f0760682c1c3acd55ba8ad3490705e2321679` |
| `candidate-r1/junit.log` | `658d45d0e419edd6cfe44af08b8451450c8647ce61682216e710634d855f41aa` |
| `candidate-r1/api.log` | `3e36ca7f5a30873bf5a3a5e59a61f53682b1f4132b2e47969936faae8adf1e10` |
| `api-comparison.json`（历史 r1） | `4eea617e6d5d2f6219079fd7c89186c24bc5dceb30a0c3401721a25a51ffaa29` |
| `flags-edit-receipt.json` | `a1836645e0eaf8aa42fd149baaaa5efb1518f0109e13f4c523699fe38e7df683` |
| `flags-java-baseline/receipt.json` | `1dbec4253b0279b9293464dea4aac80c938303810a4ced34550d9ee599b4196e` |
| `candidate-flags-r2/receipt.json` | `bdedd3b7028a7c3c6fcb2162eaac145d979c4ab0e9e668e0736e4988ddb378e2` |
| `candidate-flags-r2/junit.log` | `658d45d0e419edd6cfe44af08b8451450c8647ce61682216e710634d855f41aa` |
| `candidate-flags-r2/api.log` | `22238d11827154608ffe17d987262da6780be0230ae9ab51e08705f19c28a5ca` |
| `api-comparison-flags-r2.json`（最终） | `476b46bcf00774d036c22e7c7c4b013388000472589998b825e4fe38fe7dc789` |

历史候选 r1 编译/API 对拍发生在初次格式化之前。追加 flags 修订已在最终格式化后重编 8 个 Kotlin 类、重编全部 10 个相关 Java 测试，并重新严格 classfile 对拍；8 个被迁移类的格式后候选验证已补齐。`flags-edit-receipt.json` 记录修订前后 SHA，并在格式化前逆去新增的 `open` 与文件注解头核对函数体原字节不变。复验前对 1080 份依赖源再次 SHA 核对全部一致。全项目 Java/Kotlin 调用闭包仍由主代理串行集成补充，不将本次手工闭包结果写成完整 Gradle/CI 通过。

## 主代理集成项与未验范围

- 将 8 个原 Java 路径从共享 Java 格式登记中移除，加入 2 个新增 Java 测试；若全仓源码/测试登记要求更新，由集成者统一操作。
- 根据实际合入源码统一生成与验证最终 descriptor/受管证明。本分支不改运行时依赖、补丁、版本、数据格式、签名或权限。
- 基线与候选测试 filters：`*BackupExportGuidanceTest`、`*BackupFileNamesTest`、`*BackupHealthTest`、`*BackupScopeTest`、`*BackupScopesTest`、`*BackupTaskKindsTest`、`*BackupTaskStateTest`、`*ScheduledBackupOwnerTest`、`*BackupPolicyGoldenTest`、`*BackupPolicyApiTest`。
- 建议集成额外 filters：`*DeployDisplayTest`、`*DeployStagesTest`、`*EnvironmentIdentityTest`、`*BackupTaskScopeTest`、`*AutomaticBackupServiceGoldenTest`、`*OverwriteUpgradeGateTest`。这些是调用闭包建议，本 worker 未宣称运行过。
- 未执行：完整 Gradle/AGP 调用闭包、全量单测、全仓 Java 格式登记门禁、最终 descriptor/证明生成、CI、Lint、APK、设备及真实备份恢复。本机手工 JVM 回归不能代替真实数据/设备验收。
- 没有安装 APK/移动补丁，没有 push 或发布。原数据和既有备份完全未操作。
