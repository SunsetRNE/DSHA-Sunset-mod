# SAF 整域 Kotlin 迁移：本地实现与 API 对照（2026-11-15）

当前复验与集成以 `e368e6d8a0b8679faa138feb51dd70f2447860e1` 为基线，15 类实现提交为 `c06d8a8ba4432b64374c0871846ff97b9929d288`、配套原回执提交为 `5591633196ffc0a4161b7e8d8c0f8b164823d045`；本轮追加仅修回执和文档注记，生产不变。开工 fetch/远端读回曾为 `e2e6e341`，不能用旧基线日志代替以下新基线复验。发布仍为 build366 / `5ccc8aa3`；本 worker 不 push、合并、发布、安装或操作手机。

遵循 [ADR0004](../../adr/0004-kotlin-rust-direction.md)、[Kotlin 工具链](../../kotlin-toolchain.md) 和 [A2 原实现回执](a2-task-baseline-20261113.md)。本文件记录 Java/Kotlin 互调与 JVM 可见性的实际差异，不把 `internal` 当作 JVM 包访问控制。

## 生产替换与职责

SAF 生产目录的跟踪清单为 15 个 Java；本批全部删除，以下 15 个 Kotlin 文件分别成为原 FQCN 的唯一实现，源根仍是 `app/src/main/java`。

| 原 Java（已删除） | 唯一 Kotlin 实现 |
|---|---|
| `SafCapabilityHandles.java` | [SafCapabilityHandles.kt](../../../app/src/main/java/com/deepseekharness/app/saf/SafCapabilityHandles.kt) |
| `SafCapabilityLimits.java` | [SafCapabilityLimits.kt](../../../app/src/main/java/com/deepseekharness/app/saf/SafCapabilityLimits.kt) |
| `SafCapabilityPolicy.java` | [SafCapabilityPolicy.kt](../../../app/src/main/java/com/deepseekharness/app/saf/SafCapabilityPolicy.kt) |
| `SafGrantLedger.java` | [SafGrantLedger.kt](../../../app/src/main/java/com/deepseekharness/app/saf/SafGrantLedger.kt) |
| `SafGrantRecord.java` | [SafGrantRecord.kt](../../../app/src/main/java/com/deepseekharness/app/saf/SafGrantRecord.kt) |
| `SafGrants.java` | [SafGrants.kt](../../../app/src/main/java/com/deepseekharness/app/saf/SafGrants.kt) |
| `SafLedgerCodec.java` | [SafLedgerCodec.kt](../../../app/src/main/java/com/deepseekharness/app/saf/SafLedgerCodec.kt) |
| `SafLedgerStore.java` | [SafLedgerStore.kt](../../../app/src/main/java/com/deepseekharness/app/saf/SafLedgerStore.kt) |
| `SafLocalFiles.java` | [SafLocalFiles.kt](../../../app/src/main/java/com/deepseekharness/app/saf/SafLocalFiles.kt) |
| `SafPaths.java` | [SafPaths.kt](../../../app/src/main/java/com/deepseekharness/app/saf/SafPaths.kt) |
| `SafTransferGate.java` | [SafTransferGate.kt](../../../app/src/main/java/com/deepseekharness/app/saf/SafTransferGate.kt) |
| `SafTransferHistory.java` | [SafTransferHistory.kt](../../../app/src/main/java/com/deepseekharness/app/saf/SafTransferHistory.kt) |
| `SafTransferJob.java` | [SafTransferJob.kt](../../../app/src/main/java/com/deepseekharness/app/saf/SafTransferJob.kt) |
| `SafTree.java` | [SafTree.kt](../../../app/src/main/java/com/deepseekharness/app/saf/SafTree.kt) |
| `SafWorkbench.java` | [SafWorkbench.kt](../../../app/src/main/java/com/deepseekharness/app/saf/SafWorkbench.kt) |

不修改 `HttpShellService`、`ConfigStore`、transfer 下载引擎或运行时编排，不调整 Android Manifest、权限默认值、桥协议或数据格式。A8-P1、S1b、T2 的源码、脚本与受管证明不在本批改动范围。

保持历史 `saf_grants` 读取、新账本 `saf_capability_ledger`、schema=1、键名及系统 URI 权限/本功能新增权限位的归属；PENDING/ACTIVE/RELEASING、Previous 失败恢复、epoch 与旧句柄失效、只读/一次性删除/限额/Provider 截止及失败语义均延续。文件侧保留 NOFOLLOW、逐级目录锚定、lstat/fstat/readlink、O_NONBLOCK、验证后截断、FD 关闭所有权和发布前最后业务/身份复核；传输记录、取消、部分失败和不自动重放不变。

原 N3（ModuleRegistry.install 慢 factory 对 close 的窗口）与 N4（ledgerReady→legacy 的模式切换窗口）仍保留；本批不夹带行为修复。旧桥三参发布/路径 chmod 以及末次身份复核与 rename 非原子的既有限制，也不在本批扩大修复。

## 实际 Java 消费者与 record/集合契约

本机使用缓存 Kotlin 2.2.10、JDK17、Android SDK37 和缓存依赖，先让 Kotlin 编译器读取实际混合源，再由 javac 编译实际 Java 生产/测试源。只为资源引用从真实 XML 与当前 Gradle resValue/发行身份构造临时 R/BuildConfig 符号；SAF、transfer、宿主和测试均使用真实源码，没有用 API stub 掩盖互调变化。这不是 AAPT、APK 或 Gradle 完整构建。

实际树的跟踪生产输入（含仓库既有 1 个 AIDL stub）为 **1047 Java + 26 Kotlin → 1032 Java + 41 Kotlin**。其中 SourceLayout 的 main/standard/debug 映射根为 1046 Java + 26 Kotlin → 1031 Java + 41 Kotlin；两边各加既有 1 个 AIDL stub。补充混编另实际生成 13 个 Java（文案及临时 R/BuildConfig 符号），因此新版本显式 javac 输入为 **1045 Java**、Kotlin 为 **41**；不把额外生成源算进跟踪生产清单。测试显式输入为 **17 Java + 1 Kotlin**。分项清单和命令保存在本轮 inventory-receipt 与 compile 回执。

真实 Java 消费点包含：

- [HttpShellService](../../../app/src/main/java/com/deepseekharness/app/HttpShellService.java#L3849)：Grants 的静态列表/lookup、Grant 字段，SafTree 的 Page/Entry/Failure/PendingDelete 字段及读写删接口，SafLocalFiles 的流与锚定发布、共享 TRANSFERS。
- [SafAccessFragment](../../../app/src/main/java/com/deepseekharness/app/ui/SafAccessFragment.java)：授权列表、AddResult/Grant 字段和静态授权方法。
- [SafBrowseFragment](../../../app/src/main/java/com/deepseekharness/app/ui/SafBrowseFragment.java)：Page/Entry/Ticket 字段及工作台方法。
- [SafWorkbenchModel](../../../app/src/main/java/com/deepseekharness/app/ui/SafWorkbenchModel.java)：Java 实现 `SafTransferJob.Ports<T>`，默认方法与 checked 异常继续可用。
- [SafWorkbenchUi](../../../app/src/main/java/com/deepseekharness/app/ui/SafWorkbenchUi.java)：历史 Record 字段、DownloadTask.Status 与错误短码视图。
- [DownloadTask](../../../app/src/main/java/com/deepseekharness/app/transfer/DownloadTask.java) 与 [TransferArtifact](../../../app/src/main/java/com/deepseekharness/app/transfer/TransferArtifact.java)：历史仍消费原 `DownloadTask.Status` 嵌套枚举及 `TransferArtifact.safeName(String)` 静态方法；双方公共 Java ABI 与域外调用点未改。

`SafGrantRecord`/`Previous` 使用 `@JvmRecord`，保留 Record 父类、组件名/顺序、canonical 构造签名和 Java record 风格访问器；显式保持原 null 校验顺序、文本归一化、equals/hashCode/toString。canonical constructor 内的 `VAL_REASSIGNMENT` 局部抑制仅用于给 final record 字段写入原本归一化后的值，Kotlin 版本固定 2.2.10；Java 回归同时验证类确为 record、字段顺序、300 字符裁剪、null 消息与值语义。没有改用普通 data class 冒充 record。

`@JvmStatic`/`const val`/原字段的 `@JvmField` 保持调用形态；`@Throws` 保留原异常声明。`records()`、Decoded 的 map/warnings 和历史 `list()` 继续调用 Java copyOf，保留只读性与 null 拒绝；Page.entries 继续是原可变列表。Java trim 使用 `<= U+0020`，isBlank 使用 Character.isWhitespace，避免 Kotlin Unicode 空白判定不同造成行为变化。

## 原 API 对照与可见性例外

保留迁移前全部 48 个 SAF 类文件的 javap 快照。排除 4 个私有实现类型及 2 个匿名/编译器类型后，检查 **42 个原类型、358 个非私有字节码条目（345 个源级字段/构造/方法 + 13 个静态初始化条目）**：FQCN/descriptor、静态形态、字段 final、实例同步锁与异常声明均保留，无成员丢失；方法 final 修饰位的实际差异另见后表。此前提交说明/中间汇报中的“37 个包级成员”是人工计数错误，最终机器展开为下方 34 个，不增加成员凑数。

主会话批准的方案 A 是 **34 个原包级成员 + 5 个原包级类型容器** 的 JVM 可见性放宽，作为整域 Kotlin 的明确兼容取舍，合入仍须在用户批准问题中明示。源代码使用 internal，原 Java 需要的方法用稳定 @JvmName，原字段保持 @JvmField/const。JVM public 不等于包隔离；异包 Java 实际可调用，不能写成负控拒绝成功。

新增的私有构造/只读委派桥使用 internal + @JvmSynthetic，不作为稳定 API；它们没有替代原 Java 必须能调用的入口。编译器产生的 Companion、枚举 entries、record 的 component/copy、DefaultImpls 与 synthetic 构造/访问桥是 Kotlin 编译细节，不承诺为新的业务 API。没有把原 private FD/closed/token 字段或 AnchoredDirectory canonical 构造放宽。

### 34 个成员（不含下方 5 个类型容器）

| # | 入口（包前缀 com.deepseekharness.app.saf） | JVM descriptor | 原可见性 | 新 JVM / Kotlin | 理由 |
|---|---|---|---|---|---|
| 1 | `SafGrantLedger.Change.<init>` | `(Lcom/deepseekharness/app/saf/SafGrantLedger;Ljava/lang/String;)V` | package-private | public / internal | 原 Java 同包调用、字段与构造描述符兼容 |
| 2 | `SafGrants.KEY_ACQUIRED` | `Ljava/lang/String;` | package-private | public / internal | 原 Java 同包调用、字段与构造描述符兼容 |
| 3 | `SafGrants.KEY_LABEL` | `Ljava/lang/String;` | package-private | public / internal | 原 Java 同包调用、字段与构造描述符兼容 |
| 4 | `SafGrants.KEY_PENDING` | `Ljava/lang/String;` | package-private | public / internal | 原 Java 同包调用、字段与构造描述符兼容 |
| 5 | `SafGrants.KEY_PENDING_BEFORE` | `Ljava/lang/String;` | package-private | public / internal | 原 Java 同包调用、字段与构造描述符兼容 |
| 6 | `SafGrants.KEY_PENDING_INTENT` | `Ljava/lang/String;` | package-private | public / internal | 原 Java 同包调用、字段与构造描述符兼容 |
| 7 | `SafGrants.READ` | `I` | package-private | public / internal | 原 Java 同包调用、字段与构造描述符兼容 |
| 8 | `SafGrants.WRITE` | `I` | package-private | public / internal | 原 Java 同包调用、字段与构造描述符兼容 |
| 9 | `SafGrants.labelDeadlineMs` | `J` | package-private | public / internal | 原 Java 同包调用、字段与构造描述符兼容 |
| 10 | `SafGrants.capabilitySnapshot` | `(Landroid/content/Context;Ljava/lang/String;)Lcom/deepseekharness/app/saf/SafGrantLedger;` | package-private | public / internal | 原 Java 同包调用、字段与构造描述符兼容 |
| 11 | `SafGrants.releaseFlags` | `(Landroid/content/ContentResolver;Landroid/net/Uri;I)Z` | package-private | public / internal | 原 Java 同包调用、字段与构造描述符兼容 |
| 12 | `SafGrants.documentLabel` | `(Landroid/content/Context;Landroid/net/Uri;)Ljava/lang/String;` | package-private | public / internal | 原 Java 同包调用、字段与构造描述符兼容 |
| 13 | `SafGrants.AddResult.<init>` | `(ZZZLjava/lang/String;)V` | package-private | public / internal | 原 Java 同包调用、字段与构造描述符兼容 |
| 14 | `SafGrants.AddResult.failed` | `(Ljava/lang/String;)Lcom/deepseekharness/app/saf/SafGrants$AddResult;` | package-private | public / internal | 原 Java 同包调用、字段与构造描述符兼容 |
| 15 | `SafGrants.Grant.<init>` | `(Ljava/lang/String;Landroid/net/Uri;Ljava/lang/String;Ljava/lang/String;ZZZZ)V` | package-private | public / internal | 原 Java 同包调用、字段与构造描述符兼容 |
| 16 | `SafGrants.Lookup.<init>` | `(Lcom/deepseekharness/app/saf/SafGrants$Grant;Ljava/lang/String;)V` | package-private | public / internal | 原 Java 同包调用、字段与构造描述符兼容 |
| 17 | `SafLedgerCodec.Decoded.<init>` | `(Lcom/deepseekharness/app/saf/SafGrantLedger;Ljava/util/Map;Ljava/util/List;)V` | package-private | public / internal | 原 Java 同包调用、字段与构造描述符兼容 |
| 18 | `SafLocalFiles.publishInDirectory` | `(Lcom/deepseekharness/app/saf/SafLocalFiles$AnchoredDirectory;Ljava/lang/String;Ljava/lang/String;Ljava/io/OutputStream;ILcom/deepseekharness/app/saf/SafLocalFiles$PublishGuard;)V` | package-private | public / internal | 原 Java 同包调用、字段与构造描述符兼容 |
| 19 | `SafLocalFiles.setDirectoryListerForTests` | `(Lcom/deepseekharness/app/saf/SafLocalFiles$DirectoryLister;)V` | package-private | public / internal | 原 Java 同包调用、字段与构造描述符兼容 |
| 20 | `SafLocalFiles.OwningInputStream.<init>` | `(Ljava/io/FileInputStream;Ljava/io/FileDescriptor;)V` | package-private | public / internal | 原 Java 同包调用、字段与构造描述符兼容 |
| 21 | `SafLocalFiles.OwningOutputStream.<init>` | `(Ljava/io/FileOutputStream;Ljava/io/FileDescriptor;)V` | package-private | public / internal | 原 Java 同包调用、字段与构造描述符兼容 |
| 22 | `SafTransferHistory.<init>` | `(Landroid/content/SharedPreferences;Ljava/util/Set;)V` | package-private | public / internal | 原 Java 同包调用、字段与构造描述符兼容 |
| 23 | `SafTree.newDeadline` | `(J)Lcom/deepseekharness/app/saf/SafTree$Deadline;` | package-private | public / internal | 原 Java 同包调用、字段与构造描述符兼容 |
| 24 | `SafTree.<init>` | `(Landroid/content/ContentResolver;Landroid/net/Uri;J)V` | package-private | public / internal | 原 Java 同包调用、字段与构造描述符兼容 |
| 25 | `SafTree.pendingCountForTests` | `()I` | package-private | public / internal | 原 Java 同包调用、字段与构造描述符兼容 |
| 26 | `SafTree.clearPendingDeletesForTests` | `()V` | package-private | public / internal | 原 Java 同包调用、字段与构造描述符兼容 |
| 27 | `SafTree.setConfirmTtlForTests` | `(J)V` | package-private | public / internal | 原 Java 同包调用、字段与构造描述符兼容 |
| 28 | `SafTree.Deadline.signal` | `Landroid/os/CancellationSignal;` | package-private | public / internal | 原 Java 同包调用、字段与构造描述符兼容 |
| 29 | `SafTree.Deadline.<init>` | `(J)V` | package-private | public / internal | 原 Java 同包调用、字段与构造描述符兼容 |
| 30 | `SafTree.Deadline.check` | `()V` | package-private | public / internal | 原 Java 同包调用、字段与构造描述符兼容 |
| 31 | `SafTree.Entry.<init>` | `(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JJ)V` | package-private | public / internal | 原 Java 同包调用、字段与构造描述符兼容 |
| 32 | `SafTree.Page.<init>` | `(Ljava/util/List;Z)V` | package-private | public / internal | 原 Java 同包调用、字段与构造描述符兼容 |
| 33 | `SafTree.PendingDelete.<init>` | `(Ljava/lang/String;Ljava/lang/String;)V` | package-private | public / internal | 原 Java 同包调用、字段与构造描述符兼容 |
| 34 | `SafWorkbench.pruneTemps` | `(Lcom/deepseekharness/app/saf/SafLocalFiles$AnchoredDirectory;J)V` | package-private | public / internal | 原 Java 同包调用、字段与构造描述符兼容 |

### 5 个类型容器及访问标志

以下 class-file 与 InnerClasses access mask 都只新增 `ACC_PUBLIC (0x0001)`；类型 descriptor 不变，无新增业务入口。原可重写接口继续允许 Java 实现/扩展；原 final 类型仍为 final。

| 类型全名 | descriptor（未变） | class-file 原→新 | InnerClasses 原→新 | 原→新可见性 / 理由 |
|---|---|---|---|---|
| `com.deepseekharness.app.saf.SafLocalFiles$DirectoryLister` | `Lcom/deepseekharness/app/saf/SafLocalFiles$DirectoryLister;` | `0x0600→0x0601` | `0x0608→0x0609` | package-private→JVM public / Kotlin internal；原成员所需类型容器，未新增业务入口 |
| `com.deepseekharness.app.saf.SafLocalFiles$OwningInputStream` | `Lcom/deepseekharness/app/saf/SafLocalFiles$OwningInputStream;` | `0x0030→0x0031` | `0x0018→0x0019` | package-private→JVM public / Kotlin internal；原成员所需类型容器，未新增业务入口 |
| `com.deepseekharness.app.saf.SafLocalFiles$OwningOutputStream` | `Lcom/deepseekharness/app/saf/SafLocalFiles$OwningOutputStream;` | `0x0030→0x0031` | `0x0018→0x0019` | package-private→JVM public / Kotlin internal；原成员所需类型容器，未新增业务入口 |
| `com.deepseekharness.app.saf.SafLocalFiles$PublishGuard` | `Lcom/deepseekharness/app/saf/SafLocalFiles$PublishGuard;` | `0x0600→0x0601` | `0x0608→0x0609` | package-private→JVM public / Kotlin internal；原成员所需类型容器，未新增业务入口 |
| `com.deepseekharness.app.saf.SafTree$Deadline` | `Lcom/deepseekharness/app/saf/SafTree$Deadline;` | `0x0030→0x0031` | `0x0018→0x0019` | package-private→JVM public / Kotlin internal；原成员所需类型容器，未新增业务入口 |

### 139 项方法 ACC_FINAL 差异

Kotlin 对已有 final 类的实例实现及 @JvmStatic 包装增加 **139 项 ACC_FINAL，0 项移除**。逐项检查所属类 final/方法 static 与原可重写性，**0 项落在非 final 类中原本可 override 的方法上**。这不是“所有方法修饰位完全相同”的声明。原接口 default/abstract 方法的可重写边界另以真实 Java implements/extends/override 编译正控验证；本域原具体生产类均为 final。

| # | 方法（包前缀 com.deepseekharness.app.saf） | descriptor | 原所属类 final | 静态 / 形态 | 原 Java 可被子类 override |
|---|---|---|---|---|---|
| 1 | `SafCapabilityHandles.issue` | `(Lcom/deepseekharness/app/saf/SafGrantLedger;Ljava/lang/String;Lcom/deepseekharness/app/saf/SafCapabilityHandles$Operation;Ljava/lang/String;J)Lcom/deepseekharness/app/saf/SafCapabilityHandles$Issued;` | 是 | 实例实现 | 否 |
| 2 | `SafCapabilityHandles.check` | `(Lcom/deepseekharness/app/saf/SafGrantLedger;Ljava/lang/String;Lcom/deepseekharness/app/saf/SafCapabilityHandles$Operation;Ljava/lang/String;J)Lcom/deepseekharness/app/saf/SafCapabilityHandles$Result;` | 是 | 实例实现 | 否 |
| 3 | `SafCapabilityHandles.consume` | `(Lcom/deepseekharness/app/saf/SafGrantLedger;Ljava/lang/String;Lcom/deepseekharness/app/saf/SafCapabilityHandles$Operation;Ljava/lang/String;J)Lcom/deepseekharness/app/saf/SafCapabilityHandles$Result;` | 是 | 实例实现 | 否 |
| 4 | `SafCapabilityHandles.discard` | `(Ljava/lang/String;)V` | 是 | 实例实现 | 否 |
| 5 | `SafCapabilityHandles.revokeTree` | `(Ljava/lang/String;)I` | 是 | 实例实现 | 否 |
| 6 | `SafCapabilityHandles.purgeExpired` | `(J)I` | 是 | 实例实现 | 否 |
| 7 | `SafCapabilityHandles.size` | `()I` | 是 | 实例实现 | 否 |
| 8 | `SafCapabilityHandles.Issued.ok` | `()Z` | 是 | 实例实现 | 否 |
| 9 | `SafCapabilityLimits.readProblem` | `(JI)Ljava/lang/String;` | 是 | @JvmStatic 包装/委派 | 否 |
| 10 | `SafCapabilityLimits.writeProblem` | `(J)Ljava/lang/String;` | 是 | @JvmStatic 包装/委派 | 否 |
| 11 | `SafCapabilityLimits.textProblem` | `(I)Ljava/lang/String;` | 是 | @JvmStatic 包装/委派 | 否 |
| 12 | `SafCapabilityLimits.listProblem` | `(I)Ljava/lang/String;` | 是 | @JvmStatic 包装/委派 | 否 |
| 13 | `SafCapabilityLimits.pathSegmentProblem` | `(I)Ljava/lang/String;` | 是 | @JvmStatic 包装/委派 | 否 |
| 14 | `SafCapabilityLimits.uriProblem` | `(Ljava/lang/String;)Ljava/lang/String;` | 是 | @JvmStatic 包装/委派 | 否 |
| 15 | `SafCapabilityPolicy.treeUriProblem` | `(Ljava/lang/String;Ljava/lang/String;)Lcom/deepseekharness/app/saf/SafCapabilityPolicy$TreeUri;` | 是 | @JvmStatic 包装/委派 | 否 |
| 16 | `SafCapabilityPolicy.localSourceProblem` | `(Ljava/lang/String;)Ljava/lang/String;` | 是 | @JvmStatic 包装/委派 | 否 |
| 17 | `SafCapabilityPolicy.localCanonicalProblem` | `(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;` | 是 | @JvmStatic 包装/委派 | 否 |
| 18 | `SafCapabilityPolicy.credentialPathName` | `(Ljava/lang/String;)Z` | 是 | @JvmStatic 包装/委派 | 否 |
| 19 | `SafGrantLedger.empty` | `()Lcom/deepseekharness/app/saf/SafGrantLedger;` | 是 | @JvmStatic 包装/委派 | 否 |
| 20 | `SafGrantLedger.of` | `(Ljava/util/List;JJ)Lcom/deepseekharness/app/saf/SafGrantLedger;` | 是 | @JvmStatic 包装/委派 | 否 |
| 21 | `SafGrantLedger.records` | `()Ljava/util/List;` | 是 | 实例实现 | 否 |
| 22 | `SafGrantLedger.findById` | `(Ljava/lang/String;)Lcom/deepseekharness/app/saf/SafGrantRecord;` | 是 | 实例实现 | 否 |
| 23 | `SafGrantLedger.findByUri` | `(Ljava/lang/String;)Lcom/deepseekharness/app/saf/SafGrantRecord;` | 是 | 实例实现 | 否 |
| 24 | `SafGrantLedger.revision` | `()J` | 是 | 实例实现 | 否 |
| 25 | `SafGrantLedger.nextEpoch` | `()J` | 是 | 实例实现 | 否 |
| 26 | `SafGrantLedger.size` | `()I` | 是 | 实例实现 | 否 |
| 27 | `SafGrantLedger.beginTake` | `(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IIJ)Lcom/deepseekharness/app/saf/SafGrantLedger$Change;` | 是 | 实例实现 | 否 |
| 28 | `SafGrantLedger.commitTake` | `(Ljava/lang/String;IIZJ)Lcom/deepseekharness/app/saf/SafGrantLedger$Change;` | 是 | 实例实现 | 否 |
| 29 | `SafGrantLedger.abortTake` | `(Ljava/lang/String;)Lcom/deepseekharness/app/saf/SafGrantLedger$Change;` | 是 | 实例实现 | 否 |
| 30 | `SafGrantLedger.beginRelease` | `(Ljava/lang/String;)Lcom/deepseekharness/app/saf/SafGrantLedger$Change;` | 是 | 实例实现 | 否 |
| 31 | `SafGrantLedger.finishRelease` | `(Ljava/lang/String;)Lcom/deepseekharness/app/saf/SafGrantLedger$Change;` | 是 | 实例实现 | 否 |
| 32 | `SafGrantLedger.setMetadata` | `(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Lcom/deepseekharness/app/saf/SafGrantLedger$Change;` | 是 | 实例实现 | 否 |
| 33 | `SafGrantLedger.forget` | `(Ljava/lang/String;)Lcom/deepseekharness/app/saf/SafGrantLedger$Change;` | 是 | 实例实现 | 否 |
| 34 | `SafGrantLedger.setUserWritable` | `(Ljava/lang/String;Z)Lcom/deepseekharness/app/saf/SafGrantLedger$Change;` | 是 | 实例实现 | 否 |
| 35 | `SafGrantLedger.refreshSystemFlags` | `(Ljava/lang/String;I)Lcom/deepseekharness/app/saf/SafGrantLedger$Change;` | 是 | 实例实现 | 否 |
| 36 | `SafGrantLedger.Change.accepted` | `()Z` | 是 | 实例实现 | 否 |
| 37 | `SafGrantRecord.hasPrevious` | `()Z` | 是 | 实例实现 | 否 |
| 38 | `SafGrantRecord.canRead` | `()Z` | 是 | 实例实现 | 否 |
| 39 | `SafGrantRecord.canWrite` | `()Z` | 是 | 实例实现 | 否 |
| 40 | `SafGrantRecord.usableWrite` | `()Z` | 是 | 实例实现 | 否 |
| 41 | `SafGrantRecord.valid` | `()Z` | 是 | 实例实现 | 否 |
| 42 | `SafGrantRecord.summary` | `()Ljava/lang/String;` | 是 | 实例实现 | 否 |
| 43 | `SafGrantRecord.idOf` | `(Ljava/lang/String;)Ljava/lang/String;` | 是 | @JvmStatic 包装/委派 | 否 |
| 44 | `SafGrantRecord.validId` | `(Ljava/lang/String;)Z` | 是 | @JvmStatic 包装/委派 | 否 |
| 45 | `SafGrantRecord.id` | `()Ljava/lang/String;` | 是 | 实例实现 | 否 |
| 46 | `SafGrantRecord.uri` | `()Ljava/lang/String;` | 是 | 实例实现 | 否 |
| 47 | `SafGrantRecord.app` | `()Ljava/lang/String;` | 是 | 实例实现 | 否 |
| 48 | `SafGrantRecord.label` | `()Ljava/lang/String;` | 是 | 实例实现 | 否 |
| 49 | `SafGrantRecord.readable` | `()Z` | 是 | 实例实现 | 否 |
| 50 | `SafGrantRecord.writable` | `()Z` | 是 | 实例实现 | 否 |
| 51 | `SafGrantRecord.userWritable` | `()Z` | 是 | 实例实现 | 否 |
| 52 | `SafGrantRecord.acquired` | `()I` | 是 | 实例实现 | 否 |
| 53 | `SafGrantRecord.state` | `()Lcom/deepseekharness/app/saf/SafGrantRecord$State;` | 是 | 实例实现 | 否 |
| 54 | `SafGrantRecord.epoch` | `()J` | 是 | 实例实现 | 否 |
| 55 | `SafGrantRecord.takenAt` | `()J` | 是 | 实例实现 | 否 |
| 56 | `SafGrantRecord.pendingBefore` | `()I` | 是 | 实例实现 | 否 |
| 57 | `SafGrantRecord.pendingIntent` | `()I` | 是 | 实例实现 | 否 |
| 58 | `SafGrantRecord.previous` | `()Lcom/deepseekharness/app/saf/SafGrantRecord$Previous;` | 是 | 实例实现 | 否 |
| 59 | `SafGrantRecord.Previous.app` | `()Ljava/lang/String;` | 是 | 实例实现 | 否 |
| 60 | `SafGrantRecord.Previous.label` | `()Ljava/lang/String;` | 是 | 实例实现 | 否 |
| 61 | `SafGrantRecord.Previous.readable` | `()Z` | 是 | 实例实现 | 否 |
| 62 | `SafGrantRecord.Previous.writable` | `()Z` | 是 | 实例实现 | 否 |
| 63 | `SafGrantRecord.Previous.acquired` | `()I` | 是 | 实例实现 | 否 |
| 64 | `SafGrantRecord.Previous.takenAt` | `()J` | 是 | 实例实现 | 否 |
| 65 | `SafGrants.id` | `(Landroid/net/Uri;)Ljava/lang/String;` | 是 | @JvmStatic 包装/委派 | 否 |
| 66 | `SafGrants.list` | `(Landroid/content/Context;)Ljava/util/List;` | 是 | @JvmStatic 包装/委派 | 否 |
| 67 | `SafGrants.listLight` | `(Landroid/content/Context;)Ljava/util/List;` | 是 | @JvmStatic 包装/委派 | 否 |
| 68 | `SafGrants.lookup` | `(Landroid/content/Context;Ljava/lang/String;)Lcom/deepseekharness/app/saf/SafGrants$Lookup;` | 是 | @JvmStatic 包装/委派 | 否 |
| 69 | `SafGrants.add` | `(Landroid/content/Context;Landroid/net/Uri;Z)Lcom/deepseekharness/app/saf/SafGrants$AddResult;` | 是 | @JvmStatic 包装/委派 | 否 |
| 70 | `SafGrants.forget` | `(Landroid/content/Context;Landroid/net/Uri;)V` | 是 | @JvmStatic 包装/委派 | 否 |
| 71 | `SafGrants.setWritable` | `(Landroid/content/Context;Landroid/net/Uri;Z)V` | 是 | @JvmStatic 包装/委派 | 否 |
| 72 | `SafGrants.cleanupPending` | `(Landroid/content/Context;)V` | 是 | @JvmStatic 包装/委派 | 否 |
| 73 | `SafGrants.capabilitySnapshot` | `(Landroid/content/Context;Ljava/lang/String;)Lcom/deepseekharness/app/saf/SafGrantLedger;` | 是 | @JvmStatic 包装/委派 | 否 |
| 74 | `SafGrants.find` | `(Landroid/content/Context;Ljava/lang/String;)Lcom/deepseekharness/app/saf/SafGrants$Grant;` | 是 | @JvmStatic 包装/委派 | 否 |
| 75 | `SafGrants.pick` | `(Landroid/content/Context;Ljava/lang/String;)Lcom/deepseekharness/app/saf/SafGrants$Grant;` | 是 | @JvmStatic 包装/委派 | 否 |
| 76 | `SafGrants.ownProvider` | `(Landroid/content/Context;Landroid/net/Uri;)Z` | 是 | @JvmStatic 包装/委派 | 否 |
| 77 | `SafGrants.documentsTree` | `(Landroid/net/Uri;)Z` | 是 | @JvmStatic 包装/委派 | 否 |
| 78 | `SafGrants.releaseFlags` | `(Landroid/content/ContentResolver;Landroid/net/Uri;I)Z` | 是 | @JvmStatic 包装/委派 | 否 |
| 79 | `SafGrants.documentLabel` | `(Landroid/content/Context;Landroid/net/Uri;)Ljava/lang/String;` | 是 | @JvmStatic 包装/委派 | 否 |
| 80 | `SafGrants.AddResult.failed` | `(Ljava/lang/String;)Lcom/deepseekharness/app/saf/SafGrants$AddResult;` | 是 | @JvmStatic 包装/委派 | 否 |
| 81 | `SafGrants.Grant.summary` | `()Ljava/lang/String;` | 是 | 实例实现 | 否 |
| 82 | `SafLedgerCodec.decode` | `(Ljava/util/Map;)Lcom/deepseekharness/app/saf/SafLedgerCodec$Decoded;` | 是 | @JvmStatic 包装/委派 | 否 |
| 83 | `SafLedgerCodec.encode` | `(Lcom/deepseekharness/app/saf/SafGrantLedger;)Ljava/util/Map;` | 是 | @JvmStatic 包装/委派 | 否 |
| 84 | `SafLedgerCodec.knownKey` | `(Ljava/lang/String;)Z` | 是 | @JvmStatic 包装/委派 | 否 |
| 85 | `SafLedgerStore.open` | `(Landroid/content/Context;)Lcom/deepseekharness/app/saf/SafLedgerStore;` | 是 | @JvmStatic 包装/委派 | 否 |
| 86 | `SafLedgerStore.load` | `()Lcom/deepseekharness/app/saf/SafGrantLedger;` | 是 | 实例实现 | 否 |
| 87 | `SafLedgerStore.loadDecoded` | `()Lcom/deepseekharness/app/saf/SafLedgerCodec$Decoded;` | 是 | 实例实现 | 否 |
| 88 | `SafLedgerStore.initialized` | `()Z` | 是 | 实例实现 | 否 |
| 89 | `SafLedgerStore.save` | `(Lcom/deepseekharness/app/saf/SafGrantLedger;)Z` | 是 | 实例实现 | 否 |
| 90 | `SafLocalFiles.openRead` | `(Ljava/io/File;)Ljava/io/InputStream;` | 是 | @JvmStatic 包装/委派 | 否 |
| 91 | `SafLocalFiles.openRead` | `(Ljava/io/File;Lcom/deepseekharness/app/saf/SafLocalFiles$PathCheck;)Ljava/io/InputStream;` | 是 | @JvmStatic 包装/委派 | 否 |
| 92 | `SafLocalFiles.openWrite` | `(Ljava/io/File;)Ljava/io/OutputStream;` | 是 | @JvmStatic 包装/委派 | 否 |
| 93 | `SafLocalFiles.openAnchoredDirectory` | `(Ljava/io/File;)Lcom/deepseekharness/app/saf/SafLocalFiles$AnchoredDirectory;` | 是 | @JvmStatic 包装/委派 | 否 |
| 94 | `SafLocalFiles.openAnchoredDirectory` | `(Ljava/io/File;Lcom/deepseekharness/app/saf/SafLocalFiles$PathCheck;)Lcom/deepseekharness/app/saf/SafLocalFiles$AnchoredDirectory;` | 是 | @JvmStatic 包装/委派 | 否 |
| 95 | `SafLocalFiles.ensureAnchoredDirectory` | `(Ljava/io/File;Lcom/deepseekharness/app/saf/SafLocalFiles$PathCheck;)Lcom/deepseekharness/app/saf/SafLocalFiles$AnchoredDirectory;` | 是 | @JvmStatic 包装/委派 | 否 |
| 96 | `SafLocalFiles.createInDirectory` | `(Lcom/deepseekharness/app/saf/SafLocalFiles$AnchoredDirectory;Ljava/lang/String;)Ljava/io/OutputStream;` | 是 | @JvmStatic 包装/委派 | 否 |
| 97 | `SafLocalFiles.createInDirectory` | `(Lcom/deepseekharness/app/saf/SafLocalFiles$AnchoredDirectory;Ljava/lang/String;Lcom/deepseekharness/app/saf/SafLocalFiles$PathCheck;)Ljava/io/OutputStream;` | 是 | @JvmStatic 包装/委派 | 否 |
| 98 | `SafLocalFiles.publishInDirectory` | `(Lcom/deepseekharness/app/saf/SafLocalFiles$AnchoredDirectory;Ljava/lang/String;Ljava/lang/String;Ljava/io/OutputStream;ILcom/deepseekharness/app/saf/SafLocalFiles$PublishGuard;)V` | 是 | @JvmStatic 包装/委派 | 否 |
| 99 | `SafLocalFiles.publishInDirectory` | `(Lcom/deepseekharness/app/saf/SafLocalFiles$AnchoredDirectory;Ljava/lang/String;Ljava/lang/String;)V` | 是 | @JvmStatic 包装/委派 | 否 |
| 100 | `SafLocalFiles.deleteInDirectory` | `(Lcom/deepseekharness/app/saf/SafLocalFiles$AnchoredDirectory;Ljava/lang/String;)V` | 是 | @JvmStatic 包装/委派 | 否 |
| 101 | `SafLocalFiles.setDirectoryListerForTests` | `(Lcom/deepseekharness/app/saf/SafLocalFiles$DirectoryLister;)V` | 是 | @JvmStatic 包装/委派 | 否 |
| 102 | `SafLocalFiles.listInDirectory` | `(Lcom/deepseekharness/app/saf/SafLocalFiles$AnchoredDirectory;)[Ljava/lang/String;` | 是 | @JvmStatic 包装/委派 | 否 |
| 103 | `SafLocalFiles.link2SymlinkPath` | `(Ljava/lang/String;)Z` | 是 | @JvmStatic 包装/委派 | 否 |
| 104 | `SafLocalFiles.deleteQuietly` | `(Ljava/io/File;)V` | 是 | @JvmStatic 包装/委派 | 否 |
| 105 | `SafLocalFiles.AnchoredDirectory.path` | `()Ljava/lang/String;` | 是 | 实例实现 | 否 |
| 106 | `SafPaths.normalize` | `(Ljava/lang/String;)Ljava/lang/String;` | 是 | @JvmStatic 包装/委派 | 否 |
| 107 | `SafPaths.mimeOf` | `(Ljava/lang/String;)Ljava/lang/String;` | 是 | @JvmStatic 包装/委派 | 否 |
| 108 | `SafPaths.looksText` | `([B)Z` | 是 | @JvmStatic 包装/委派 | 否 |
| 109 | `SafTransferHistory.open` | `(Landroid/content/Context;)Lcom/deepseekharness/app/saf/SafTransferHistory;` | 是 | @JvmStatic 包装/委派 | 否 |
| 110 | `SafTransferHistory.begin` | `(Ljava/lang/String;ZJ)Ljava/lang/String;` | 是 | 实例实现 | 否 |
| 111 | `SafTransferHistory.finish` | `(Ljava/lang/String;JLjava/lang/String;)Z` | 是 | 实例实现 | 否 |
| 112 | `SafTransferHistory.list` | `()Ljava/util/List;` | 是 | 实例实现 | 否 |
| 113 | `SafTransferJob.run` | `(Lcom/deepseekharness/app/saf/SafTransferJob$Ports;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;ZJ)Lcom/deepseekharness/app/saf/SafTransferJob$Result;` | 是 | @JvmStatic 包装/委派 | 否 |
| 114 | `SafTransferJob.failureCode` | `(Ljava/lang/Exception;)Ljava/lang/String;` | 是 | @JvmStatic 包装/委派 | 否 |
| 115 | `SafTree.newDeadline` | `(J)Lcom/deepseekharness/app/saf/SafTree$Deadline;` | 是 | @JvmStatic 包装/委派 | 否 |
| 116 | `SafTree.uri` | `()Landroid/net/Uri;` | 是 | 实例实现 | 否 |
| 117 | `SafTree.root` | `()Lcom/deepseekharness/app/saf/SafTree$Entry;` | 是 | 实例实现 | 否 |
| 118 | `SafTree.list` | `(Ljava/lang/String;I)Lcom/deepseekharness/app/saf/SafTree$Page;` | 是 | 实例实现 | 否 |
| 119 | `SafTree.stat` | `(Ljava/lang/String;)Lcom/deepseekharness/app/saf/SafTree$Entry;` | 是 | 实例实现 | 否 |
| 120 | `SafTree.read` | `(Ljava/lang/String;JI)[B` | 是 | 实例实现 | 否 |
| 121 | `SafTree.write` | `(Ljava/lang/String;[BZ)J` | 是 | 实例实现 | 否 |
| 122 | `SafTree.writeStream` | `(Ljava/lang/String;Ljava/io/InputStream;JZ)J` | 是 | 实例实现 | 否 |
| 123 | `SafTree.mkdir` | `(Ljava/lang/String;)V` | 是 | 实例实现 | 否 |
| 124 | `SafTree.delete` | `(Ljava/lang/String;)Lcom/deepseekharness/app/saf/SafTree$PendingDelete;` | 是 | 实例实现 | 否 |
| 125 | `SafTree.deleteConfirmed` | `(Ljava/lang/String;Ljava/lang/String;)V` | 是 | 实例实现 | 否 |
| 126 | `SafTree.pendingCountForTests` | `()I` | 是 | @JvmStatic 包装/委派 | 否 |
| 127 | `SafTree.clearPendingDeletesForTests` | `()V` | 是 | @JvmStatic 包装/委派 | 否 |
| 128 | `SafTree.setConfirmTtlForTests` | `(J)V` | 是 | @JvmStatic 包装/委派 | 否 |
| 129 | `SafTree.normalize` | `(Ljava/lang/String;)Ljava/lang/String;` | 是 | @JvmStatic 包装/委派 | 否 |
| 130 | `SafTree.mimeOf` | `(Ljava/lang/String;)Ljava/lang/String;` | 是 | @JvmStatic 包装/委派 | 否 |
| 131 | `SafTree.looksText` | `([B)Z` | 是 | @JvmStatic 包装/委派 | 否 |
| 132 | `SafTree.Deadline.check` | `()V` | 是 | 实例实现 | 否 |
| 133 | `SafWorkbench.prepare` | `(Ljava/lang/String;Ljava/lang/String;Z)Lcom/deepseekharness/app/saf/SafWorkbench$Ticket;` | 是 | 实例实现 | 否 |
| 134 | `SafWorkbench.retarget` | `(Lcom/deepseekharness/app/saf/SafWorkbench$Ticket;Ljava/lang/String;)Lcom/deepseekharness/app/saf/SafWorkbench$Ticket;` | 是 | 实例实现 | 否 |
| 135 | `SafWorkbench.cancel` | `(Lcom/deepseekharness/app/saf/SafWorkbench$Ticket;)V` | 是 | 实例实现 | 否 |
| 136 | `SafWorkbench.list` | `(Ljava/lang/String;Ljava/lang/String;)Lcom/deepseekharness/app/saf/SafTree$Page;` | 是 | 实例实现 | 否 |
| 137 | `SafWorkbench.read` | `(Ljava/lang/String;Ljava/lang/String;I)[B` | 是 | 实例实现 | 否 |
| 138 | `SafWorkbench.transfer` | `(Lcom/deepseekharness/app/saf/SafWorkbench$Ticket;Ljava/lang/String;Z)J` | 是 | 实例实现 | 否 |
| 139 | `SafWorkbench.pruneTemps` | `(Lcom/deepseekharness/app/saf/SafLocalFiles$AnchoredDirectory;J)V` | 是 | @JvmStatic 包装/委派 | 否 |

## 迁移前后行为与 Java 编译对照

本轮 P1 证明脚本 exit **0**，绑定 HEAD `55916331`（实现 `c06d8a8b`、基线 `e368e6d8`）。原 Java 对照类文件来自开工基线；已逐文件核对其 15 个 SAF Java blob 与 `e368e6d8` 完全一致。迁移前 Java 与 rebase 后 Kotlin 各运行 **200 / 0 failures / 0 ignored / 0 assumptions**；200 条排序后的测试身份逐条相同（以 LF 连接、不加末尾 LF 的 `TEST 类#方法` 清单 SHA-256：`9c8648bdf9d6b7b8338cf1f082fe868ad61f24e5943dc3f61c49e80b36b98308`），没有丢失/改名既有测试。

| 实际 javac 控制（覆盖表中全部 34 入口及 5 类型，含接口 extends/implements/override） | 原 Java | 迁移后 Kotlin | 结论 |
|---|---|---|---|
| 同包 Java 原名调用 | exit 0 | exit 0 | 正控通过，原接口默认方法仍可被 Java override |
| 异包 Java 相同调用 | exit 1 | exit 0 | 实际可调用，属于已确认的 JVM 可见性放宽；不是拒绝负控通过 |

`javap -p -s` descriptor/静态/字段 final/同步/throws 对照 exit **0**；5 类型另有 `javap -p -s -v` 与直接 class-file/InnerClasses access mask 对照。私有输入/输出流 FD、closed、票据 token、锚定目录 canonical 构造在原/新字节码中均仍为 private。139 项新增方法 final 全部属于原 final 类，0 项原可 override；接口继承/实现正控编译 exit 0。

原 13 个 SAF 测试类与 BridgePathPolicyTest 共 **188 项**；新增 [SafJavaApiTest](../../../app/src/test/java/com/deepseekharness/app/saf/SafJavaApiTest.java) **9 项**、[SafKotlinInteropTest](../../../app/src/test/java/com/deepseekharness/app/saf/SafKotlinInteropTest.kt) **3 项**，均先在原 Java 类文件上编译执行，再对迁移后 Kotlin 执行，没有替换或改名任何既有测试。

188 项覆盖授权/拒绝/撤销、重新授权与系统撤权、旧键迁移/写失败/未知状态、只读、Provider 错误/截止/一次性删除、目录与 FD 核验、导入发布成功、临时对象替换、close/fchmod 后撤销/超时/取消的失败回滚、共享槽位、传输历史、记录失败和不自动重放。新增回归检查 record/JVM 形态、null 与空白、集合只读、Java default/checked 异常、实例 monitor、同一删除句柄的并发一次性消费和重入。此处 Provider 为真实编译的 Robolectric 夹具，不是外部应用/真机验收。

实际命令与本机回执位于仓库外：

```sh
python3 /tmp/saf-p1-compile-7cf7bc9f.py "$PWD" /tmp/saf-after-p1-7cf7bc9f
python3 /tmp/saf-abi-check-7cf7bc9f.py /tmp/saf-before-7cf7bc9f/abi.json /tmp/saf-after-p1-7cf7bc9f/abi.json
python3 /tmp/saf-p1-proof-7cf7bc9f.py "$PWD"
```

Robolectric 本机配置为 SDK34 缓存、offline、conscrypt OFF、`--add-opens=java.base/java.io=ALL-UNNAMED`；不替代 CI 默认配置。长 classpath 通过 @args 文件传入。重编译与 transfer 会话协调串行，仅通过 dsha-gradle 启动 Gradle，没有抢占/结束其它会话构建。

## 格式、边界、失败与未验项

以下计数须与 `e368e6d8` 基线、实现 `c06d8a8b` 和本轮 P1 命令日志对应；旧基线计数不混用。日志入口见文末命令，host manifest 以本轮 `--check-manifest` 实际输出 264 为准：

| 检查 | 实际结果 | exit |
|---|---|---|
| Kotlin 全树格式（ktfmt 0.64，offline） | 45 files / 0 need，含 SAF 15 生产 + 1 测试 | 0 |
| Java 全树格式（google-java-format 1.22.0，offline，jobs=1） | 1799 files / 0 need；manifest missing=0 / extra=0 | 0 |
| 格式清单精确完整性 | 仅 SAF 15 个生产 Java 条目删除 + 1 个 Java API 测试新增；其它字段/顺序不变，1799 条全部存在 | 0 |
| core boundaries | 25 tests / 0 failure，基线未扩大 | 0 |
| runtime descriptor source 单测 | 4 tests / 0 failure | 0 |
| source hygiene | PASS，LF/managed byte pins 等原政策不改 | 0 |
| host test manifest | PASS_MANIFEST / 264 项登记（不是 264 项执行） | 0 |
| 当前文档链接 | 56 文档 / 1328 有效 / 0 警告 / 0 错误 | 0 |
| 本次回执与 A2 最小链接修订 | 2 文档 / 61 有效 / 0 警告 / 0 错误 | 0 |
| diff whitespace | PASS | 0 |
| runtime-input-contract | 18 项：17 通过、1 固定输入缺失错误 | 1 |
| runtime-descriptor-inputs | DESCRIPTOR_LAUNCHER_MEMBER_SET；旧描述符尚未由 main 重算 | 1 |

最后两项没有写成 PASS，也没有修改检查器、受管清单或证明来消除拒绝。

已知失败与未验范围：

- `SafGrantRecord.kt` 的 4 处 `@Suppress("VAL_REASSIGNMENT")` 在 kotlinc 2.2.10 实际输出警告：compiler behavior is **UNSPECIFIED and WILL NOT BE PRESERVED**。当前固定版本的 record 形态、构造归一化和 Java 值语义已做真实编译/双端回归；不能据此保证未来编译器行为。任何 Kotlin 升级必须重新验证警告对应的构造字节码、组件顺序、归一化、equals/hashCode/toString 及 Java 消费者。
- source hygiene 的 UUID 源码扫描尚只覆盖 Java；扩到 Kotlin 按主会话安排在合并后补，不把本轮 hygiene PASS 写成这项覆盖已修复。
- 原 Gradle 基线经 dsha-gradle 在 prepareBackupAssets 因缺固定输入 dsh-runtime.bin 退出 1，未执行到 JUnit；新基线通过 dsha-gradle 串行执行三项生成器后尝试 compileStandardDebugKotlin/定向 testStandardDebugUnitTest（no-daemon、max-workers=1），仍在同一 prepareBackupAssets 配置核验停止，**exit 1 / 20s**。不把手工混编写成 Gradle 完整构建通过。
- 首次仓库外 JUnit 使用错误 SDK 缓存路径失败，改为本机已存在的缓存后原 188 项通过。一次超长 classpath 直接 argv 的 Java API 编译未解析到 JUnit，改 @args 后 9 项通过；这些是 harness/环境问题，失败日志保留。
- 最终证明表最初把人工误计的 37 写成断言，机器展开后更正为 34；一次自定义结果 runner 已输出原 Java 200/0/0/0，但 Robolectric 非 daemon 线程使 JVM 未退出，收集结果后终止本轮，补显式 System.exit 后完整证明脚本重新执行 exit 0。均为仓库外 harness 修正，生产与既有测试不改。
- 第一轮 Kotlin 编译发现 2 个 SDK nullable/fcntlInt 位置、一个不适用仓库默认模式的 JvmDefaultWithCompatibility 注解和清理列表的可空解引用位置；均按原 null 行为/默认接口形态修正后，R2/R3/最终混编与 Java default/ABI 回归通过，没有放宽原 private 字段以求编译。
- 既有 tools/test-saf-local-files.py 在本机 proot 的 5 项 Linux syscall 镜像中 **1 通过、4 错误**（符号链接/`/proc/self/fd/<fd>/<name>` 的本机映射语义），脚本及生产边界不改、不跳过；正常 Linux CI 的真实 syscall 证明待主会话。Robolectric 原文件/FD/发布反例已实际运行。
- runtime-input-contract 的 18 项有 1 项因固定输入缺失错误，其余 17 项通过；runtime-descriptor-inputs 按预期拒绝旧 descriptor 的 15 删 15 增，报 DESCRIPTOR_LAUNCHER_MEMBER_SET。没有改检查逻辑或伪造受管证明。
- 未做 Gradle 全量/默认 CI 矩阵、Lint、AAPT/APK/签名、安装、SAF DocumentsUI/外部 Provider/真机、LayoutAuditInstrumentation 或覆盖升级验收。

A2 历史文档保留原散文/结论，仅改 6 个 SAF 源码链接并加一条 2026-11-15 整域迁移注记；syscall 镜像脚本仅修 docstring 的 Kotlin 引用/迁移注记，AST 已核对执行代码与基线相同。Java 格式清单仅删除 15 个 SAF 生产 Java 条目、新增 SafJavaApiTest 1 条，其余顺序/政策字段不变，完整性检查全条目存在。

## 受管输入、集成与回退

只读枚举显示 launcherInputs 总数 **517→517**，精确删除以下 15 个 `saf/*.java` 键，加入同名 `saf/*.kt` 键；除本域后缀替换外没有其它输入改动。managed-runtime-inputs、managed-package-proofs、runtime-descriptor 均未由本 worker 修改。主会话须在合并树按最终固定输入重算 descriptor/runtimeId；runtimeId 预期改变，按既有契约触发一次运行时维护页，不由 worker 沿用旧 pin。

| 删除的 launcherInputs 键 | 加入的键 | 新源码 SHA-256 |
|---|---|---|
| `saf/SafCapabilityHandles.java` | [saf/SafCapabilityHandles.kt](../../../app/src/main/java/com/deepseekharness/app/saf/SafCapabilityHandles.kt) | `137988e140376f0ebea99f30a84289a6985983998bf3531f587f83e97b7317dd` |
| `saf/SafCapabilityLimits.java` | [saf/SafCapabilityLimits.kt](../../../app/src/main/java/com/deepseekharness/app/saf/SafCapabilityLimits.kt) | `c055a42b5fe8bc77382b660a82b13768240b076a30a279eb6268dfdd166c9950` |
| `saf/SafCapabilityPolicy.java` | [saf/SafCapabilityPolicy.kt](../../../app/src/main/java/com/deepseekharness/app/saf/SafCapabilityPolicy.kt) | `7ec13eea2561850fdf4c3f62d2315728fa0b75d9fc7e3e2849ff44e994bb4fb6` |
| `saf/SafGrantLedger.java` | [saf/SafGrantLedger.kt](../../../app/src/main/java/com/deepseekharness/app/saf/SafGrantLedger.kt) | `767cd4057217c99cc08c4224ff5d3a6113126fe9739ce68498cf74a58c3425e5` |
| `saf/SafGrantRecord.java` | [saf/SafGrantRecord.kt](../../../app/src/main/java/com/deepseekharness/app/saf/SafGrantRecord.kt) | `5811b6a0f5062ce3d1e744612cc2357482c5f623e4cdb538852f9699c7f7918e` |
| `saf/SafGrants.java` | [saf/SafGrants.kt](../../../app/src/main/java/com/deepseekharness/app/saf/SafGrants.kt) | `472f69a0e140bdeecfaf377bb13774919b308666551f8b83995dd4a932d0f04d` |
| `saf/SafLedgerCodec.java` | [saf/SafLedgerCodec.kt](../../../app/src/main/java/com/deepseekharness/app/saf/SafLedgerCodec.kt) | `f8e78b3d2662ed9a2585e6b6a1099d8dca85b0053cd96eaa7e0c4fab38996099` |
| `saf/SafLedgerStore.java` | [saf/SafLedgerStore.kt](../../../app/src/main/java/com/deepseekharness/app/saf/SafLedgerStore.kt) | `2399f4f22a4db1e1bce99c0f43a0ec63611c1fc3796a077e3e2b1d2eb5a28d42` |
| `saf/SafLocalFiles.java` | [saf/SafLocalFiles.kt](../../../app/src/main/java/com/deepseekharness/app/saf/SafLocalFiles.kt) | `c2aa959ce3087772fe8847acf4c9859106edc2b7f9bd3135ef4312d1cd791ab6` |
| `saf/SafPaths.java` | [saf/SafPaths.kt](../../../app/src/main/java/com/deepseekharness/app/saf/SafPaths.kt) | `5c546ff11a567c25c9ab2c0c2460854db4ae59728d7943c71642ec0459f3201f` |
| `saf/SafTransferGate.java` | [saf/SafTransferGate.kt](../../../app/src/main/java/com/deepseekharness/app/saf/SafTransferGate.kt) | `c8b960656332d69839dc7f2fcde505b444c29fc3be69452c022e911049725e6f` |
| `saf/SafTransferHistory.java` | [saf/SafTransferHistory.kt](../../../app/src/main/java/com/deepseekharness/app/saf/SafTransferHistory.kt) | `8a4f1e9010a9ad616997f33bd1a04d7d1ead4d4d028567a72f3ab3c7bcbc58e7` |
| `saf/SafTransferJob.java` | [saf/SafTransferJob.kt](../../../app/src/main/java/com/deepseekharness/app/saf/SafTransferJob.kt) | `bee69fe8823ed3ee4fcf108a3e4d0d365b0fa12f09f2c331fa93e9a814331e99` |
| `saf/SafTree.java` | [saf/SafTree.kt](../../../app/src/main/java/com/deepseekharness/app/saf/SafTree.kt) | `d3b977c1d0de5c1d7280b1c47a3e4748b6bbe44201df550e5f9cf102b2f668b2` |
| `saf/SafWorkbench.java` | [saf/SafWorkbench.kt](../../../app/src/main/java/com/deepseekharness/app/saf/SafWorkbench.kt) | `586041bd5b08b962277bf00ca40878be0faab75c080a03a118b7285869ece8fb` |

集成顺序：核对本地提交与本回执 → 非实现者审查全部 diff → 用户明确批准（明示 34 成员 + 5 类型 JVM 可见性放宽及编译器方法标志差异）→ 主会话串行整合格式清单与受管 descriptor、运行 CI/full 门禁。进 main 不等于发布。

新基线回退按逆序 revert 本轮回执追加、`55916331` 与实现 `c06d8a8b`，再由主会话重算受管 descriptor；15 个原 Java 与原格式条目恢复，15 个 Kotlin 删除。无 namespace/schema/数据格式/权限/包名变化，不需要清数据或重置授权。回退同样交主会话协调，不在本 worker 执行发布或设备动作。

## A8-P1 落地后复验（最新，2026-11-15）

main 通知 P1 出口通过后，无冲突 rebase 到 `e368e6d8`，当前实现/配套回执为 `c06d8a8b + 55916331`。15 个 SAF Kotlin 生产文件逐字节与旧交付一致，没有借工程更新修改业务。此节更新验证边界，不把 main 的 P1 CI 结果当作本分支的 CI。

新 `SourceLayout` 的 compile-inputs、verify-compile-head、launcher-inputs 均 exit 0；完整测试计划生成 exit 0，实际 **779 测试源 / 5209 方法声明 / 593 资源输入**，任务仍为 `:app:testStandardDebugUnitTest`。这些是计划/声明计数，不是全量测试执行结果。源码布局 16 项、unit contract 64 项、构建约定 25 项、core 边界 25 项及 architecture 边界全部通过。builtin/credential/changelog/notices 生成器只读检查和 UiLanguage 临时目录实际生成均 exit 0；不修改共享工具或生成政策。

新单一映射下受管 launcher 成员 **517→517**：仅删除表中 15 个 SAF Java 键并加入同名 Kotlin 键，其余成员与摘要全部不变。worker 不写 descriptor/proof；最终 descriptor/runtimeId **由 main 合并时按真实固定输入重算，并与合入同推**。本分支只保留成员和源码摘要影响检查，不把旧描述符或只读投影当作完整生成证明。

本轮基于 SourceLayout 的真实补充混编 exit **0**：跟踪生产输入 1032 Java + 41 Kotlin（含既有 AIDL stub），另 13 个实际临时生成 Java，显式编译 1045 Java + 41 Kotlin；测试为 17 Java + 1 Kotlin。双端证明 exit **0**，绑定 HEAD `55916331` / 实现 `c06d8a8b`：旧 Java 与新 Kotlin 各 **200 / 0 failures / 0 ignored / 0 assumptions**，200 条测试身份相同，SHA-256 仍为 `9c8648bdf9d6b7b8338cf1f082fe868ad61f24e5943dc3f61c49e80b36b98308`。原 345 个源级成员 + 13 个 clinit 的 descriptor/static/字段 final/synchronized/throws 对照通过；34 成员 + 5 类型可见性、139 新增方法 final 的逐项表与私有 FD/closed/token/构造核验不变。

Gradle 经 dsha-gradle 串行实际执行 prepareUiLanguages、verifyBuiltinRegistry、verifyCredentialRegistry 均通过（UI 5851 entries × 10 languages）；随后在 prepareBackupAssets 因缺固定 dsh-runtime.bin 停止，**exit 1 / 20s**，未到 Kotlin/单测执行。不能把补充混编/定向 JUnit 写成 Gradle full 新鲜报告或 APK 通过。

最终 P1 门禁与本轮日志对应：Java 全树 **1799/0**（missing=0/extra=0）、Kotlin 全树 **45/0**；source-layout **16/0**、unit contract **64/0**、build conventions **25/0**、core **25/0**、architecture PASS；builtin/credential/changelog/notices 检查和 UiLanguage 生成全部 exit 0；host manifest **264** 条登记、hygiene PASS、当前文档 **56/1328/0 error**。18 项 runtime-input-contract 中 17 通过、1 因固定资产缺失错误；descriptor input/source-set 影响检查如实保留 517/15:15 的拒绝点，worker 不写 descriptor/proof，main 合并时重算。未执行本分支的 CI full、Lint、AAPT/APK、真实 Provider/设备或 LayoutAudit。

本轮原始命令/exit 与输出保存在仓库外，主要入口为：

```sh
python3 tools/unit_test_contract.py plan --root "$PWD" --output /tmp/saf-p1-7cf7bc9f/unit-plan.json
python3 /tmp/saf-p1-compile-7cf7bc9f.py "$PWD" /tmp/saf-after-p1-7cf7bc9f
python3 /tmp/saf-p1-proof-7cf7bc9f.py "$PWD"
```

补充混编改为消费当前共享 `SourceLayout` 的实际 main/standard/debug/test 枚举，仍不是 Gradle full 新鲜报告证明；不得拿临时 R/BuildConfig 符号和定向 Robolectric 代替 P1 的 full/Rust/七夹具/编译输入执行回执。新基线回退应按相反顺序 revert 本轮回执追加、`55916331`、`c06d8a8b`，再由 main 重算受管身份。
