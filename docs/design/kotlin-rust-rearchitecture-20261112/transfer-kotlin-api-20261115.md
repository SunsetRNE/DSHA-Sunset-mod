# transfer Kotlin API 精确对照（2026-11-15）

原 Java 基线 `b3602555`；与 [迁移回执](transfer-kotlin-migration-20261115.md) 配套。原 8 Java 在 `e368e6d8` 未变；最终重基到 SAF 后的 `9281a50e`，实现提交变为 `1515d037`，仅测试 P1 修订至 `2b03a20d`，生产 8 Kotlin 字节保持不变。本表从原 Java 与 Kotlin class 自动解析；e368 的手工/真实 Gradle 输出及最终 9281a50e 基线的实际 Gradle 输出均重核，以下 93 原成员、可见性/final/23 基础设施差异相同。只覆盖 12 个具名边界类型，原 private 内部实现与匿名/编译器类不充作稳定 API。最新生产 ABI 证据为 `/tmp/transfer-review-gradle-api-2e0bfb12{.json,.javap.json}`；最终测试编译/运行绑定 2b03a20d、旧 e368/4253b00a/ba65d641 证据作为历史，详见迁移回执。后续仅文档提交不冒称已执行源码 HEAD。

原非 private、非 synthetic 成员 **93**，其中 `<clinit>` **3**；丢失 0、checked exception 变化 0、原 private 入口放宽 0。Class major 均为 61。不是位级完全一致。

## 原字段保留表

| 字段 | descriptor | 原修饰符 | 新修饰符 | 源码形态 |
|---|---|---|---|---|
| `DownloadEngine.State.tasks` | `Ljava/util/List;` | public final (0x0011) | public final (0x0011) | @JvmField |
| `DownloadEngine.State.busy` | `Z` | public final (0x0011) | public final (0x0011) | @JvmField |
| `DownloadEngine.State.activeId` | `Ljava/lang/String;` | public final (0x0011) | public final (0x0011) | @JvmField |
| `DownloadTask.id` | `Ljava/lang/String;` | public final (0x0011) | public final (0x0011) | @JvmField |
| `DownloadTask.destination` | `Ljava/lang/String;` | public final (0x0011) | public final (0x0011) | @JvmField |
| `DownloadTask.message` | `Ljava/lang/String;` | public final (0x0011) | public final (0x0011) | @JvmField |
| `DownloadTask.uri` | `Ljava/lang/String;` | public final (0x0011) | public final (0x0011) | @JvmField |
| `DownloadTask.displayName` | `Ljava/lang/String;` | public final (0x0011) | public final (0x0011) | @JvmField |
| `DownloadTask.location` | `Ljava/lang/String;` | public final (0x0011) | public final (0x0011) | @JvmField |
| `DownloadTask.mime` | `Ljava/lang/String;` | public final (0x0011) | public final (0x0011) | @JvmField |
| `DownloadTask.artifact` | `Lcom/deepseekharness/app/transfer/TransferArtifact;` | public final (0x0011) | public final (0x0011) | @JvmField |
| `DownloadTask.status` | `Lcom/deepseekharness/app/transfer/DownloadTask$Status;` | public final (0x0011) | public final (0x0011) | @JvmField |
| `DownloadTask.downloaded` | `J` | public final (0x0011) | public final (0x0011) | @JvmField |
| `DownloadTask.total` | `J` | public final (0x0011) | public final (0x0011) | @JvmField |
| `DownloadTask.speedBytesPerSecond` | `J` | public final (0x0011) | public final (0x0011) | @JvmField |
| `DownloadTask.createdAt` | `J` | public final (0x0011) | public final (0x0011) | @JvmField |
| `DownloadTask.Status.QUEUED` | `Lcom/deepseekharness/app/transfer/DownloadTask$Status;` | public static final enum (0x4019) | public static final enum (0x4019) | enum 常量 |
| `DownloadTask.Status.DOWNLOADING` | `Lcom/deepseekharness/app/transfer/DownloadTask$Status;` | public static final enum (0x4019) | public static final enum (0x4019) | enum 常量 |
| `DownloadTask.Status.VERIFYING` | `Lcom/deepseekharness/app/transfer/DownloadTask$Status;` | public static final enum (0x4019) | public static final enum (0x4019) | enum 常量 |
| `DownloadTask.Status.PUBLISHING` | `Lcom/deepseekharness/app/transfer/DownloadTask$Status;` | public static final enum (0x4019) | public static final enum (0x4019) | enum 常量 |
| `DownloadTask.Status.WAITING_DESTINATION` | `Lcom/deepseekharness/app/transfer/DownloadTask$Status;` | public static final enum (0x4019) | public static final enum (0x4019) | enum 常量 |
| `DownloadTask.Status.PAUSED` | `Lcom/deepseekharness/app/transfer/DownloadTask$Status;` | public static final enum (0x4019) | public static final enum (0x4019) | enum 常量 |
| `DownloadTask.Status.FAILED` | `Lcom/deepseekharness/app/transfer/DownloadTask$Status;` | public static final enum (0x4019) | public static final enum (0x4019) | enum 常量 |
| `DownloadTask.Status.COMPLETE` | `Lcom/deepseekharness/app/transfer/DownloadTask$Status;` | public static final enum (0x4019) | public static final enum (0x4019) | enum 常量 |
| `DownloadTask.Status.CANCELLED` | `Lcom/deepseekharness/app/transfer/DownloadTask$Status;` | public static final enum (0x4019) | public static final enum (0x4019) | enum 常量 |
| `TransferArtifact.MAX_BYTES` | `J` | public static final (0x0019) | public static final (0x0019) | const val |
| `TransferArtifact.id` | `Ljava/lang/String;` | public final (0x0011) | public final (0x0011) | @JvmField |
| `TransferArtifact.name` | `Ljava/lang/String;` | public final (0x0011) | public final (0x0011) | @JvmField |
| `TransferArtifact.mime` | `Ljava/lang/String;` | public final (0x0011) | public final (0x0011) | @JvmField |
| `TransferArtifact.sha256` | `Ljava/lang/String;` | public final (0x0011) | public final (0x0011) | @JvmField |
| `TransferArtifact.version` | `Ljava/lang/String;` | public final (0x0011) | public final (0x0011) | @JvmField |
| `TransferArtifact.bytes` | `J` | public final (0x0011) | public final (0x0011) | @JvmField |
| `TransferFiles.FS` | `Lcom/deepseekharness/app/backup/BackupFileSystem;` | package-private static final (0x0018) | public static final (0x0019) | internal @JvmField（已批最小可见性例外） |
| `TransferProtocol.CONNECT_TIMEOUT_MS` | `I` | public static final (0x0019) | public static final (0x0019) | const val |
| `TransferProtocol.SOURCE_VERIFY_READ_TIMEOUT_MS` | `I` | public static final (0x0019) | public static final (0x0019) | const val |
| `TransferProtocol.CONTENT_READ_TIMEOUT_MS` | `I` | public static final (0x0019) | public static final (0x0019) | const val |
| `TransferProtocol.RELEASE_TIMEOUT_MS` | `I` | public static final (0x0019) | public static final (0x0019) | const val |

## 最小可见性例外：10 个成员 + 4 个类型

Kotlin 均以 internal 表示原包内边界；JVM 放宽为 public，不宣称异包 Java 负控。方法原名以 @JvmName/@JvmStatic 保留，Files.FS 用 internal @JvmField。

| 类型/成员 | descriptor | 原 → 新 JVM 可见性 | 原因 |
|---|---|---|---|
| `DownloadEngine.State.<init>` | `(Ljava/util/List;ZLjava/lang/String;)V` | package-private → public | Kotlin 无 JVM package-private；保持原 FQCN/Java 名/descriptor |
| `DownloadTask.<init>` | `(Ljava/lang/String;Lcom/deepseekharness/app/transfer/TransferArtifact;Ljava/lang/String;Lcom/deepseekharness/app/transfer/DownloadTask$Status;JJLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;J)V` | package-private → public | Kotlin 无 JVM package-private；保持原 FQCN/Java 名/descriptor |
| `TransferFiles` | `Lcom/deepseekharness/app/transfer/TransferFiles;` | package-private → public | Kotlin 无 JVM package-private；保持原 FQCN/Java 名/descriptor |
| `TransferFiles.FS` | `Lcom/deepseekharness/app/backup/BackupFileSystem;` | package-private → public | Kotlin 无 JVM package-private；保持原 FQCN/Java 名/descriptor |
| `TransferFiles.directory` | `(Ljava/io/File;Ljava/lang/String;)Ljava/io/File;` | package-private → public | Kotlin 无 JVM package-private；保持原 FQCN/Java 名/descriptor |
| `TransferFiles.size` | `(Ljava/io/File;)J` | package-private → public | Kotlin 无 JVM package-private；保持原 FQCN/Java 名/descriptor |
| `TransferFiles.read` | `(Ljava/io/File;)Ljava/io/InputStream;` | package-private → public | Kotlin 无 JVM package-private；保持原 FQCN/Java 名/descriptor |
| `TransferFiles.write` | `(Ljava/io/File;J)Ljava/io/OutputStream;` | package-private → public | Kotlin 无 JVM package-private；保持原 FQCN/Java 名/descriptor |
| `TransferPublisher` | `Lcom/deepseekharness/app/transfer/TransferPublisher;` | package-private → public | Kotlin 无 JVM package-private；保持原 FQCN/Java 名/descriptor |
| `TransferPublisher.publish` | `(Landroid/content/Context;Ljava/io/File;Lcom/deepseekharness/app/transfer/TransferArtifact;Ljava/lang/String;Ljava/lang/String;Landroid/net/Uri;Lcom/deepseekharness/app/backup/BackupControl;Ljava/lang/Object;Lcom/deepseekharness/app/transfer/TransferPublisher$Journal;)V` | package-private → public | Kotlin 无 JVM package-private；保持原 FQCN/Java 名/descriptor |
| `TransferPublisher.require` | `(Lcom/deepseekharness/app/transfer/TransferArtifact;Lcom/deepseekharness/app/util/FileIntegrity$Result;)V` | package-private → public | Kotlin 无 JVM package-private；保持原 FQCN/Java 名/descriptor |
| `TransferPublisher.Result` | `Lcom/deepseekharness/app/transfer/TransferPublisher$Result;` | package-private → public | Kotlin 无 JVM package-private；保持原 FQCN/Java 名/descriptor |
| `TransferPublisher.Result.<init>` | `(Landroid/net/Uri;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V` | package-private → public | Kotlin 无 JVM package-private；保持原 FQCN/Java 名/descriptor |
| `TransferPublisher.Journal` | `Lcom/deepseekharness/app/transfer/TransferPublisher$Journal;` | package-private → public | Kotlin 无 JVM package-private；保持原 FQCN/Java 名/descriptor |

## 方法 final 标志：35 增、2 减

所有这些 owner 在原 Java 中已为 final 类；新增 final 没有落在原可继承/可 override 的方法上。Result.equals/hashCode 去 final 也不形成可 override 入口。

| 成员 | descriptor | 原 final | 新 final | 原 owner final | static |
|---|---|---|---|---|---|
| `DownloadEngine.get` | `(Landroid/content/Context;)Lcom/deepseekharness/app/transfer/DownloadEngine;` | False | True | True | True |
| `DownloadEngine.state` | `()Landroidx/lifecycle/LiveData;` | False | True | True | False |
| `DownloadEngine.isBusy` | `()Z` | False | True | True | False |
| `DownloadEngine.onRuntimeAvailable` | `()V` | False | True | True | False |
| `DownloadEngine.enqueue` | `(Lcom/deepseekharness/app/transfer/TransferArtifact;Ljava/lang/String;)Ljava/lang/String;` | False | True | True | False |
| `DownloadEngine.enqueueLocal` | `(Ljava/io/File;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;` | False | True | True | False |
| `DownloadEngine.resume` | `(Ljava/lang/String;)V` | False | True | True | False |
| `DownloadEngine.provideSaveTarget` | `(Ljava/lang/String;Landroid/net/Uri;)V` | False | True | True | False |
| `DownloadEngine.pause` | `(Ljava/lang/String;)V` | False | True | True | False |
| `DownloadEngine.cancel` | `(Ljava/lang/String;)V` | False | True | True | False |
| `DownloadEngine.pauseAll` | `(Ljava/lang/String;)V` | False | True | True | False |
| `DownloadEngine.remove` | `(Ljava/lang/String;)V` | False | True | True | False |
| `DownloadEngine.snapshot` | `(Ljava/lang/String;)Lorg/json/JSONObject;` | False | True | True | False |
| `DownloadEngine.runQueued` | `()J` | False | True | True | False |
| `DownloadEngine.serviceGone` | `(JLjava/lang/String;)V` | False | True | True | False |
| `PendingFileAction.id` | `()Ljava/lang/String;` | False | True | True | False |
| `PendingFileAction.action` | `()Ljava/lang/String;` | False | True | True | False |
| `PendingFileAction.retire` | `()V` | False | True | True | False |
| `PendingFileAction.consume` | `(Ljava/lang/String;Ljava/lang/String;Lcom/deepseekharness/app/transfer/DownloadTask$Status;Z)Ljava/lang/String;` | False | True | True | False |
| `TransferArtifact.safeName` | `(Ljava/lang/String;)Z` | False | True | True | True |
| `TransferArtifact.fromJson` | `(Lorg/json/JSONObject;)Lcom/deepseekharness/app/transfer/TransferArtifact;` | False | True | True | True |
| `TransferArtifact.toJson` | `()Lorg/json/JSONObject;` | False | True | True | False |
| `TransferArtifact.requireSame` | `(Lcom/deepseekharness/app/transfer/TransferArtifact;)V` | False | True | True | False |
| `TransferFiles.directory` | `(Ljava/io/File;Ljava/lang/String;)Ljava/io/File;` | False | True | True | True |
| `TransferFiles.size` | `(Ljava/io/File;)J` | False | True | True | True |
| `TransferFiles.read` | `(Ljava/io/File;)Ljava/io/InputStream;` | False | True | True | True |
| `TransferFiles.write` | `(Ljava/io/File;J)Ljava/io/OutputStream;` | False | True | True | True |
| `TransferProtocol.responseOffset` | `(IJJLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)J` | False | True | True | True |
| `TransferProtocol.requiredSpace` | `(JJZ)J` | False | True | True | True |
| `TransferPublisher.publish` | `(Landroid/content/Context;Ljava/io/File;Lcom/deepseekharness/app/transfer/TransferArtifact;Ljava/lang/String;Ljava/lang/String;Landroid/net/Uri;Lcom/deepseekharness/app/backup/BackupControl;Ljava/lang/Object;Lcom/deepseekharness/app/transfer/TransferPublisher$Journal;)V` | False | True | True | True |
| `TransferPublisher.require` | `(Lcom/deepseekharness/app/transfer/TransferArtifact;Lcom/deepseekharness/app/util/FileIntegrity$Result;)V` | False | True | True | True |
| `TransferPublisher.Result.hashCode` | `()I` | True | False | True | False |
| `TransferPublisher.Result.equals` | `(Ljava/lang/Object;)Z` | True | False | True | False |
| `TransferPublisher.Result.uri` | `()Landroid/net/Uri;` | False | True | True | False |
| `TransferPublisher.Result.name` | `()Ljava/lang/String;` | False | True | True | False |
| `TransferPublisher.Result.mime` | `()Ljava/lang/String;` | False | True | True | False |
| `TransferPublisher.Result.location` | `()Ljava/lang/String;` | False | True | True | False |

## 编译器基础设施：23 项总数 = 5 字段 + 18 方法/初始化入口

不是业务字段/方法，不承诺稳定 API。Companion 字段在 class 文件中不带 ACC_SYNTHETIC，不能笼统称为 synthetic 成员。无新增业务源调用或生产反射白名单；原 Java 调用方字节未改。Kotlin 互调/@JvmStatic wrapper 会使用其编译器桥；这不等于新增产品接口。

| 新增成员 | descriptor | 修饰符 | ACC_SYNTHETIC | 使用与白名单 |
|---|---|---|---|---|
| `DownloadEngine.Companion` | `Lcom/deepseekharness/app/transfer/DownloadEngine$Companion;` | public static final (0x0019) | False | 仅编译器互调/wrapper；无业务源字段调用/生产反射白名单 |
| `DownloadEngine.access$getGate$p` | `(Lcom/deepseekharness/app/transfer/DownloadEngine;)Ljava/lang/Object;` | public static final synthetic (0x1019) | True | 仅生成代码访问原 private 状态/方法；无业务源调用/白名单 |
| `DownloadEngine.access$finishPublication` | `(Lcom/deepseekharness/app/transfer/DownloadEngine;Lcom/deepseekharness/app/transfer/DownloadEngine$Record;Lcom/deepseekharness/app/transfer/TransferPublisher$Result;)V` | public static final synthetic (0x1019) | True | 仅生成代码访问原 private 状态/方法；无业务源调用/白名单 |
| `DownloadEngine.access$save` | `(Lcom/deepseekharness/app/transfer/DownloadEngine;Lcom/deepseekharness/app/transfer/DownloadEngine$Record;)V` | public static final synthetic (0x1019) | True | 仅生成代码访问原 private 状态/方法；无业务源调用/白名单 |
| `DownloadEngine.access$getInstance$cp` | `()Lcom/deepseekharness/app/transfer/DownloadEngine;` | public static final synthetic (0x1019) | True | 仅生成代码访问原 private 状态/方法；无业务源调用/白名单 |
| `DownloadEngine.access$setInstance$cp` | `(Lcom/deepseekharness/app/transfer/DownloadEngine;)V` | public static final synthetic (0x1019) | True | 仅生成代码访问原 private 状态/方法；无业务源调用/白名单 |
| `DownloadEngine.<init>` | `(Landroid/content/Context;Lkotlin/jvm/internal/DefaultConstructorMarker;)V` | public synthetic (0x1001) | True | 仅 Companion 到原 private 构造的桥；无外部源调用/白名单 |
| `DownloadEngine.<clinit>` | `()V` | package-private static (0x0008) | False | 仅 JVM 类初始化；无显式调用/反射白名单 |
| `DownloadService.<clinit>` | `()V` | package-private static (0x0008) | False | 仅 JVM 类初始化；无显式调用/反射白名单 |
| `DownloadTask.Status.getEntries` | `()Lkotlin/enums/EnumEntries;` | public static (0x0009) | False | 原业务源未使用；未新增反射白名单；不承诺稳定 API |
| `TransferArtifact.Companion` | `Lcom/deepseekharness/app/transfer/TransferArtifact$Companion;` | public static final (0x0019) | False | 仅编译器互调/wrapper；无业务源字段调用/生产反射白名单 |
| `TransferArtifact.<clinit>` | `()V` | package-private static (0x0008) | False | 仅 JVM 类初始化；无显式调用/反射白名单 |
| `TransferFiles.Companion` | `Lcom/deepseekharness/app/transfer/TransferFiles$Companion;` | public static final (0x0019) | False | 仅编译器互调/wrapper；无业务源字段调用/生产反射白名单 |
| `TransferProtocol.Companion` | `Lcom/deepseekharness/app/transfer/TransferProtocol$Companion;` | public static final (0x0019) | False | 仅编译器互调/wrapper；无业务源字段调用/生产反射白名单 |
| `TransferProtocol.access$getRANGE$cp` | `()Ljava/util/regex/Pattern;` | public static final synthetic (0x1019) | True | 仅生成代码访问原 private 状态/方法；无业务源调用/白名单 |
| `TransferPublisher.Companion` | `Lcom/deepseekharness/app/transfer/TransferPublisher$Companion;` | public static final (0x0019) | False | 仅编译器互调/wrapper；无业务源字段调用/生产反射白名单 |
| `TransferPublisher.<clinit>` | `()V` | package-private static (0x0008) | False | 仅 JVM 类初始化；无显式调用/反射白名单 |
| `TransferPublisher.Result.component1` | `()Landroid/net/Uri;` | public final (0x0011) | False | 原业务源未使用；未新增反射白名单；不承诺稳定 API |
| `TransferPublisher.Result.component2` | `()Ljava/lang/String;` | public final (0x0011) | False | 原业务源未使用；未新增反射白名单；不承诺稳定 API |
| `TransferPublisher.Result.component3` | `()Ljava/lang/String;` | public final (0x0011) | False | 原业务源未使用；未新增反射白名单；不承诺稳定 API |
| `TransferPublisher.Result.component4` | `()Ljava/lang/String;` | public final (0x0011) | False | 原业务源未使用；未新增反射白名单；不承诺稳定 API |
| `TransferPublisher.Result.copy` | `(Landroid/net/Uri;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Lcom/deepseekharness/app/transfer/TransferPublisher$Result;` | public final (0x0011) | False | 原业务源未使用；未新增反射白名单；不承诺稳定 API |
| `TransferPublisher.Result.copy$default` | `(Lcom/deepseekharness/app/transfer/TransferPublisher$Result;Landroid/net/Uri;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ILjava/lang/Object;)Lcom/deepseekharness/app/transfer/TransferPublisher$Result;` | public static synthetic (0x1009) | True | 原业务源未使用；未新增反射白名单；不承诺稳定 API |

测试夹具 [DownloadEngineNetworkTest](../../../app/src/test/java/com/deepseekharness/app/transfer/DownloadEngineNetworkTest.java) 的 className shadow 明确指向 `TransferFiles$Companion`，仅用于原 Java 静态 write 与 Kotlin write 的平台边界对照；它是本次唯一新增的测试辅助类名依赖，不是生产反射白名单。测试没有对新增的 copy/componentN/getEntries 建立业务调用。

## 原入口逐项名/descriptor/异常对照

| 原成员 | descriptor | 原修饰符 | 新修饰符 | checked exception（两侧相同） |
|---|---|---|---|---|
| `DownloadEngine.get` | `(Landroid/content/Context;)Lcom/deepseekharness/app/transfer/DownloadEngine;` | public static synchronized (0x0029) | public static final (0x0019) | `—` |
| `DownloadEngine.state` | `()Landroidx/lifecycle/LiveData;` | public (0x0001) | public final (0x0011) | `—` |
| `DownloadEngine.isBusy` | `()Z` | public (0x0001) | public final (0x0011) | `—` |
| `DownloadEngine.onRuntimeAvailable` | `()V` | public (0x0001) | public final (0x0011) | `—` |
| `DownloadEngine.enqueue` | `(Lcom/deepseekharness/app/transfer/TransferArtifact;Ljava/lang/String;)Ljava/lang/String;` | public (0x0001) | public final (0x0011) | `java/lang/Exception` |
| `DownloadEngine.enqueueLocal` | `(Ljava/io/File;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;` | public (0x0001) | public final (0x0011) | `java/lang/Exception` |
| `DownloadEngine.resume` | `(Ljava/lang/String;)V` | public (0x0001) | public final (0x0011) | `java/lang/Exception` |
| `DownloadEngine.provideSaveTarget` | `(Ljava/lang/String;Landroid/net/Uri;)V` | public (0x0001) | public final (0x0011) | `java/lang/Exception` |
| `DownloadEngine.pause` | `(Ljava/lang/String;)V` | public (0x0001) | public final (0x0011) | `—` |
| `DownloadEngine.cancel` | `(Ljava/lang/String;)V` | public (0x0001) | public final (0x0011) | `—` |
| `DownloadEngine.pauseAll` | `(Ljava/lang/String;)V` | public (0x0001) | public final (0x0011) | `—` |
| `DownloadEngine.remove` | `(Ljava/lang/String;)V` | public (0x0001) | public final (0x0011) | `java/lang/Exception` |
| `DownloadEngine.snapshot` | `(Ljava/lang/String;)Lorg/json/JSONObject;` | public (0x0001) | public final (0x0011) | `java/lang/Exception` |
| `DownloadEngine.runQueued` | `()J` | public (0x0001) | public final (0x0011) | `—` |
| `DownloadEngine.serviceGone` | `(JLjava/lang/String;)V` | public (0x0001) | public final (0x0011) | `—` |
| `DownloadEngine.State.tasks` | `Ljava/util/List;` | public final (0x0011) | public final (0x0011) | `—` |
| `DownloadEngine.State.busy` | `Z` | public final (0x0011) | public final (0x0011) | `—` |
| `DownloadEngine.State.activeId` | `Ljava/lang/String;` | public final (0x0011) | public final (0x0011) | `—` |
| `DownloadEngine.State.<init>` | `(Ljava/util/List;ZLjava/lang/String;)V` | package-private (0x0000) | public (0x0001) | `—` |
| `DownloadService.<init>` | `()V` | public (0x0001) | public (0x0001) | `—` |
| `DownloadService.onCreate` | `()V` | public (0x0001) | public (0x0001) | `—` |
| `DownloadService.onStartCommand` | `(Landroid/content/Intent;II)I` | public (0x0001) | public (0x0001) | `—` |
| `DownloadService.onTimeout` | `(II)V` | public (0x0001) | public (0x0001) | `—` |
| `DownloadService.onDestroy` | `()V` | public (0x0001) | public (0x0001) | `—` |
| `DownloadService.onBind` | `(Landroid/content/Intent;)Landroid/os/IBinder;` | public (0x0001) | public (0x0001) | `—` |
| `DownloadTask.id` | `Ljava/lang/String;` | public final (0x0011) | public final (0x0011) | `—` |
| `DownloadTask.destination` | `Ljava/lang/String;` | public final (0x0011) | public final (0x0011) | `—` |
| `DownloadTask.message` | `Ljava/lang/String;` | public final (0x0011) | public final (0x0011) | `—` |
| `DownloadTask.uri` | `Ljava/lang/String;` | public final (0x0011) | public final (0x0011) | `—` |
| `DownloadTask.displayName` | `Ljava/lang/String;` | public final (0x0011) | public final (0x0011) | `—` |
| `DownloadTask.location` | `Ljava/lang/String;` | public final (0x0011) | public final (0x0011) | `—` |
| `DownloadTask.mime` | `Ljava/lang/String;` | public final (0x0011) | public final (0x0011) | `—` |
| `DownloadTask.artifact` | `Lcom/deepseekharness/app/transfer/TransferArtifact;` | public final (0x0011) | public final (0x0011) | `—` |
| `DownloadTask.status` | `Lcom/deepseekharness/app/transfer/DownloadTask$Status;` | public final (0x0011) | public final (0x0011) | `—` |
| `DownloadTask.downloaded` | `J` | public final (0x0011) | public final (0x0011) | `—` |
| `DownloadTask.total` | `J` | public final (0x0011) | public final (0x0011) | `—` |
| `DownloadTask.speedBytesPerSecond` | `J` | public final (0x0011) | public final (0x0011) | `—` |
| `DownloadTask.createdAt` | `J` | public final (0x0011) | public final (0x0011) | `—` |
| `DownloadTask.<init>` | `(Ljava/lang/String;Lcom/deepseekharness/app/transfer/TransferArtifact;Ljava/lang/String;Lcom/deepseekharness/app/transfer/DownloadTask$Status;JJLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;J)V` | package-private (0x0000) | public (0x0001) | `—` |
| `DownloadTask.Status.QUEUED` | `Lcom/deepseekharness/app/transfer/DownloadTask$Status;` | public static final enum (0x4019) | public static final enum (0x4019) | `—` |
| `DownloadTask.Status.DOWNLOADING` | `Lcom/deepseekharness/app/transfer/DownloadTask$Status;` | public static final enum (0x4019) | public static final enum (0x4019) | `—` |
| `DownloadTask.Status.VERIFYING` | `Lcom/deepseekharness/app/transfer/DownloadTask$Status;` | public static final enum (0x4019) | public static final enum (0x4019) | `—` |
| `DownloadTask.Status.PUBLISHING` | `Lcom/deepseekharness/app/transfer/DownloadTask$Status;` | public static final enum (0x4019) | public static final enum (0x4019) | `—` |
| `DownloadTask.Status.WAITING_DESTINATION` | `Lcom/deepseekharness/app/transfer/DownloadTask$Status;` | public static final enum (0x4019) | public static final enum (0x4019) | `—` |
| `DownloadTask.Status.PAUSED` | `Lcom/deepseekharness/app/transfer/DownloadTask$Status;` | public static final enum (0x4019) | public static final enum (0x4019) | `—` |
| `DownloadTask.Status.FAILED` | `Lcom/deepseekharness/app/transfer/DownloadTask$Status;` | public static final enum (0x4019) | public static final enum (0x4019) | `—` |
| `DownloadTask.Status.COMPLETE` | `Lcom/deepseekharness/app/transfer/DownloadTask$Status;` | public static final enum (0x4019) | public static final enum (0x4019) | `—` |
| `DownloadTask.Status.CANCELLED` | `Lcom/deepseekharness/app/transfer/DownloadTask$Status;` | public static final enum (0x4019) | public static final enum (0x4019) | `—` |
| `DownloadTask.Status.values` | `()[Lcom/deepseekharness/app/transfer/DownloadTask$Status;` | public static (0x0009) | public static (0x0009) | `—` |
| `DownloadTask.Status.valueOf` | `(Ljava/lang/String;)Lcom/deepseekharness/app/transfer/DownloadTask$Status;` | public static (0x0009) | public static (0x0009) | `—` |
| `DownloadTask.Status.<clinit>` | `()V` | package-private static (0x0008) | package-private static (0x0008) | `—` |
| `PendingFileAction.<init>` | `(Ljava/lang/String;Ljava/lang/String;)V` | public (0x0001) | public (0x0001) | `—` |
| `PendingFileAction.id` | `()Ljava/lang/String;` | public (0x0001) | public final (0x0011) | `—` |
| `PendingFileAction.action` | `()Ljava/lang/String;` | public (0x0001) | public final (0x0011) | `—` |
| `PendingFileAction.retire` | `()V` | public (0x0001) | public final (0x0011) | `—` |
| `PendingFileAction.consume` | `(Ljava/lang/String;Ljava/lang/String;Lcom/deepseekharness/app/transfer/DownloadTask$Status;Z)Ljava/lang/String;` | public (0x0001) | public final (0x0011) | `—` |
| `TransferArtifact.MAX_BYTES` | `J` | public static final (0x0019) | public static final (0x0019) | `—` |
| `TransferArtifact.id` | `Ljava/lang/String;` | public final (0x0011) | public final (0x0011) | `—` |
| `TransferArtifact.name` | `Ljava/lang/String;` | public final (0x0011) | public final (0x0011) | `—` |
| `TransferArtifact.mime` | `Ljava/lang/String;` | public final (0x0011) | public final (0x0011) | `—` |
| `TransferArtifact.sha256` | `Ljava/lang/String;` | public final (0x0011) | public final (0x0011) | `—` |
| `TransferArtifact.version` | `Ljava/lang/String;` | public final (0x0011) | public final (0x0011) | `—` |
| `TransferArtifact.bytes` | `J` | public final (0x0011) | public final (0x0011) | `—` |
| `TransferArtifact.<init>` | `(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JLjava/lang/String;Ljava/lang/String;)V` | public (0x0001) | public (0x0001) | `java/io/IOException` |
| `TransferArtifact.safeName` | `(Ljava/lang/String;)Z` | public static (0x0009) | public static final (0x0019) | `—` |
| `TransferArtifact.fromJson` | `(Lorg/json/JSONObject;)Lcom/deepseekharness/app/transfer/TransferArtifact;` | public static (0x0009) | public static final (0x0019) | `java/lang/Exception` |
| `TransferArtifact.toJson` | `()Lorg/json/JSONObject;` | public (0x0001) | public final (0x0011) | `java/lang/Exception` |
| `TransferArtifact.requireSame` | `(Lcom/deepseekharness/app/transfer/TransferArtifact;)V` | public (0x0001) | public final (0x0011) | `java/io/IOException` |
| `TransferFiles.FS` | `Lcom/deepseekharness/app/backup/BackupFileSystem;` | package-private static final (0x0018) | public static final (0x0019) | `—` |
| `TransferFiles.directory` | `(Ljava/io/File;Ljava/lang/String;)Ljava/io/File;` | package-private static (0x0008) | public static final (0x0019) | `java/io/IOException` |
| `TransferFiles.size` | `(Ljava/io/File;)J` | package-private static (0x0008) | public static final (0x0019) | `java/io/IOException` |
| `TransferFiles.read` | `(Ljava/io/File;)Ljava/io/InputStream;` | package-private static (0x0008) | public static final (0x0019) | `java/io/IOException` |
| `TransferFiles.write` | `(Ljava/io/File;J)Ljava/io/OutputStream;` | package-private static (0x0008) | public static final (0x0019) | `java/io/IOException` |
| `TransferFiles.<clinit>` | `()V` | package-private static (0x0008) | package-private static (0x0008) | `—` |
| `TransferProtocol.CONNECT_TIMEOUT_MS` | `I` | public static final (0x0019) | public static final (0x0019) | `—` |
| `TransferProtocol.SOURCE_VERIFY_READ_TIMEOUT_MS` | `I` | public static final (0x0019) | public static final (0x0019) | `—` |
| `TransferProtocol.CONTENT_READ_TIMEOUT_MS` | `I` | public static final (0x0019) | public static final (0x0019) | `—` |
| `TransferProtocol.RELEASE_TIMEOUT_MS` | `I` | public static final (0x0019) | public static final (0x0019) | `—` |
| `TransferProtocol.responseOffset` | `(IJJLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)J` | public static (0x0009) | public static final (0x0019) | `java/io/IOException` |
| `TransferProtocol.requiredSpace` | `(JJZ)J` | public static (0x0009) | public static final (0x0019) | `java/io/IOException` |
| `TransferProtocol.<clinit>` | `()V` | package-private static (0x0008) | package-private static (0x0008) | `—` |
| `TransferPublisher.publish` | `(Landroid/content/Context;Ljava/io/File;Lcom/deepseekharness/app/transfer/TransferArtifact;Ljava/lang/String;Ljava/lang/String;Landroid/net/Uri;Lcom/deepseekharness/app/backup/BackupControl;Ljava/lang/Object;Lcom/deepseekharness/app/transfer/TransferPublisher$Journal;)V` | package-private static (0x0008) | public static final (0x0019) | `java/lang/Exception` |
| `TransferPublisher.require` | `(Lcom/deepseekharness/app/transfer/TransferArtifact;Lcom/deepseekharness/app/util/FileIntegrity$Result;)V` | package-private static (0x0008) | public static final (0x0019) | `java/io/IOException` |
| `TransferPublisher.Result.<init>` | `(Landroid/net/Uri;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V` | package-private (0x0000) | public (0x0001) | `—` |
| `TransferPublisher.Result.toString` | `()Ljava/lang/String;` | public final (0x0011) | public final (0x0011) | `—` |
| `TransferPublisher.Result.hashCode` | `()I` | public final (0x0011) | public (0x0001) | `—` |
| `TransferPublisher.Result.equals` | `(Ljava/lang/Object;)Z` | public final (0x0011) | public (0x0001) | `—` |
| `TransferPublisher.Result.uri` | `()Landroid/net/Uri;` | public (0x0001) | public final (0x0011) | `—` |
| `TransferPublisher.Result.name` | `()Ljava/lang/String;` | public (0x0001) | public final (0x0011) | `—` |
| `TransferPublisher.Result.mime` | `()Ljava/lang/String;` | public (0x0001) | public final (0x0011) | `—` |
| `TransferPublisher.Result.location` | `()Ljava/lang/String;` | public (0x0001) | public final (0x0011) | `—` |
| `TransferPublisher.Journal.pending` | `(Landroid/net/Uri;Ljava/lang/String;)V` | public abstract (0x0401) | public abstract (0x0401) | `java/lang/Exception` |
| `TransferPublisher.Journal.complete` | `(Lcom/deepseekharness/app/transfer/TransferPublisher$Result;)V` | public abstract (0x0401) | public abstract (0x0401) | `java/lang/Exception` |

## private canonical 构造及锁

下列原 private canonical 构造均保持 private；DefaultConstructorMarker 桥另列基础设施，不把桥等同放宽原构造。RuntimeSource 原 private 的 base/cookie/identity 字段也保持 private。

| 原 private 构造 | descriptor | 新可见性 |
|---|---|---|
| `DownloadEngine.<init>` | `(Landroid/content/Context;)V` | private |
| `TransferFiles.<init>` | `()V` | private |
| `TransferProtocol.<init>` | `()V` | private |
| `TransferPublisher.<init>` | `()V` | private |
| `DownloadEngine.Record.<init>` | `()V` | private |
| `DownloadEngine.RuntimeSource.<init>` | `(Ljava/lang/String;Ljava/lang/String;Lcom/deepseekharness/app/util/PreviewPageSession$Identity;)V` | private |

`DownloadEngine.get(Context)` 原为 public static synchronized（0x0029），现为 public static final（0x0019），在方法体内显式使用 DownloadEngine.class 的 monitor。真实 Java 回归验证另一线程在持有该类锁时被阻塞、解锁后取得同一实例，且同线程可重入。其它 gate 临界区沿原代码保留。

`TransferPublisher.Result` 两侧均继承 java.lang.Record，具有四个 canonical 组件 uri/name/mime/location；Java 正控实际反射 RecordComponents、调用 canonical 构造/原 accessor 并比较 nullable/equals/hashCode/toString。

原始对照文件为仓库外 `/tmp/transfer-api-final-2e0bfb12.json`、`/tmp/transfer-api-final-2e0bfb12.javap.json`；编译输入/测试哈希闭环见迁移回执。此文不是受管 runtime descriptor 或固定输入证明。
