# 网络健康 Kotlin 迁移：JVM ABI 冻结与对照（2026-11-15）

最终验证基线 `823fd4c83184c65ec7311357d8e3b53595fe8da7`（已含 SAF/transfer），Java 测试冻结 `f1ad2bc03003694466741a31bac5f0e4981c6888`，生产复验源快照 `8920acc41881cc31aafe6cf711b64ba6d0ad8635`。使用 JDK 17 `javap -p -s`，原 Java 与替换 Kotlin 分别从最终基线新编译产物重新读取；不是手写签名或测试 stub。两侧新取原文与 e368e6d8 中间复验、初轮 b3602555 输出逐字节相等，以下保留完整原文。比较忽略编译器 lambda 名、枚举内部数组构建器和 clinit；源级类型/字段/方法/构造逐项核对。最终文档收口提交不改被测生产/测试源，其 HEAD 完整 SHA 见 `.SESSION-DONE` 与同轮 ready 回报；本节明确绑定实际被编译的源提交，不伪填文档自身尚未生成的 SHA。

- 原类型：8；逐项核对成员：93；缺失、原 private 放宽、字段 static/final/volatile 改变、原声明异常改变：0。
- 已批准的原可见性差异：1 类型 + 6 成员，共 7 项，列于下表。
- 新增方法 ACC_FINAL：32，owner 原先全部为 final，原可覆写边界变化 0。
- 外层差异记录 20 条 = 原 8 类型内新增成员 17 条 + 新增 Companion 类型本体 3 条；不是全部新增成员总数，也不是 20 项原成员可见性放宽。
- 3 个 Companion 内另有 16 个 public 非 synthetic 方法、3 个 public synthetic 构造桥；还包含 3 个 private 构造、3 个 private lambda helper，分型计数见后表。
- 本轮同时从实际 Gradle Kotlin Compile 目录读取 11 个类型（原 8 + 3 Companion），完整 `javap -p -s` 与本轮直接编译的 Kotlin 输出逐字节一致；原/新 ABI 机器结果与 Gradle 原文在 `/tmp/nethealth-final-8211ba69/abi-result.json`、`gradle-abi.json`。
- 本文件只记录本域 ABI，不属于受管运行时证明或 descriptor。

## 原类型可见性差异

| 类型 | JVM descriptor | 原 | 新 | 原因 |
|---|---|---|---|---|
| `com.deepseekharness.app.nethealth.NetworkHealthProbe` | `Lcom/deepseekharness/app/nethealth/NetworkHealthProbe;` | package-private final | JVM public final / Kotlin internal | Kotlin 无 package-private；经主会话批准的封闭域例外 |

## 原成员可见性差异

| Owner / 成员 | descriptor | 原 | 新 | 原因 |
|---|---|---|---|---|
| `com.deepseekharness.app.nethealth.NetworkHealthMonitor$Report.<init>` | `(Lcom/deepseekharness/app/util/NetworkHealthPolicy$Verdict;Ljava/lang/String;Ljava/lang/String;)V` | package-private | JVM public / Kotlin internal | internal 构造保留同包 Java new |
| `com.deepseekharness.app.nethealth.NetworkHealthProbe.HOST` | `Ljava/lang/String;` | package-private | JVM public / Kotlin internal | internal const 保留静态常量 |
| `com.deepseekharness.app.nethealth.NetworkHealthProbe.run` | `()Lcom/deepseekharness/app/util/NetworkHealthPolicy$Probe;` | package-private | JVM public / Kotlin internal | internal + @JvmStatic + @JvmName 保留原静态 JVM 名 |
| `com.deepseekharness.app.util.NetworkHealthPolicy.known` | `(Ljava/lang/String;Ljava/util/List;)Ljava/lang/String;` | package-private | JVM public / Kotlin internal | internal + @JvmStatic + @JvmName 保留原静态 JVM 名 |
| `com.deepseekharness.app.util.NetworkHealthPolicy.errorName` | `(Ljava/lang/String;)Ljava/lang/String;` | package-private | JVM public / Kotlin internal | internal + @JvmStatic + @JvmName 保留原静态 JVM 名 |
| `com.deepseekharness.app.util.NetworkHealthPolicy.safeToken` | `(Ljava/lang/String;)Ljava/lang/String;` | package-private | JVM public / Kotlin internal | internal + @JvmStatic + @JvmName 保留原静态 JVM 名 |

不声称原 package-private 的异包负控仍成立。其余原 private 声明仍 private；原公开字段均保留直接字段 ABI。所有原非枚举数据模型仍为普通 final 类，保留身份相等，不增加 data class 值语义。

## 新增 ACC_FINAL 方法逐项核对

下列每个 owner 在 Java 基线中已是 final；没有把原可继承类型或可覆写入口封闭。

| Owner / 方法 | descriptor | 原 owner |
|---|---|---|
| `com.deepseekharness.app.nethealth.NetworkHealthMonitor.checkNow` | `(Ljava/util/function/Consumer;)V` | final |
| `com.deepseekharness.app.nethealth.NetworkHealthMonitor.collect` | `()Lcom/deepseekharness/app/util/NetworkHealthPolicy$Signals;` | final |
| `com.deepseekharness.app.nethealth.NetworkHealthMonitor.ensureStarted` | `(Landroid/content/Context;)Lcom/deepseekharness/app/nethealth/NetworkHealthMonitor;` | final |
| `com.deepseekharness.app.nethealth.NetworkHealthMonitor.evaluate` | `()V` | final |
| `com.deepseekharness.app.nethealth.NetworkHealthMonitor.events` | `()Ljava/util/List;` | final |
| `com.deepseekharness.app.nethealth.NetworkHealthMonitor.harnessForeground` | `()Z` | final |
| `com.deepseekharness.app.nethealth.NetworkHealthMonitor.heartbeat` | `()V` | final |
| `com.deepseekharness.app.nethealth.NetworkHealthMonitor.lastVerdict` | `()Lcom/deepseekharness/app/util/NetworkHealthPolicy$Verdict;` | final |
| `com.deepseekharness.app.nethealth.NetworkHealthMonitor.load` | `()Ljava/util/List;` | final |
| `com.deepseekharness.app.nethealth.NetworkHealthMonitor.netReport` | `()Ljava/lang/String;` | final |
| `com.deepseekharness.app.nethealth.NetworkHealthMonitor.onAppBackground` | `()V` | final |
| `com.deepseekharness.app.nethealth.NetworkHealthMonitor.record` | `(Lcom/deepseekharness/app/util/NetworkHealthPolicy$Verdict;Ljava/lang/String;)V` | final |
| `com.deepseekharness.app.nethealth.NetworkHealthMonitor.save` | `(Ljava/util/List;)V` | final |
| `com.deepseekharness.app.nethealth.NetworkHealthMonitor.schedule` | `(J)V` | final |
| `com.deepseekharness.app.nethealth.NetworkHealthMonitor.start` | `()V` | final |
| `com.deepseekharness.app.nethealth.NetworkHealthMonitor.webActive` | `()Z` | final |
| `com.deepseekharness.app.nethealth.NetworkHealthProbe.run` | `()Lcom/deepseekharness/app/util/NetworkHealthPolicy$Probe;` | final |
| `com.deepseekharness.app.util.NetworkHealthPolicy.append` | `(Ljava/util/List;JLcom/deepseekharness/app/util/NetworkHealthPolicy$Verdict;Ljava/lang/String;)Ljava/util/List;` | final |
| `com.deepseekharness.app.util.NetworkHealthPolicy.classify` | `(Lcom/deepseekharness/app/util/NetworkHealthPolicy$Signals;)Lcom/deepseekharness/app/util/NetworkHealthPolicy$Verdict;` | final |
| `com.deepseekharness.app.util.NetworkHealthPolicy.errorName` | `(Ljava/lang/String;)Ljava/lang/String;` | final |
| `com.deepseekharness.app.util.NetworkHealthPolicy.evidence` | `(Lcom/deepseekharness/app/util/NetworkHealthPolicy$Signals;)Ljava/lang/String;` | final |
| `com.deepseekharness.app.util.NetworkHealthPolicy.frozenGap` | `(JJ)J` | final |
| `com.deepseekharness.app.util.NetworkHealthPolicy.known` | `(Ljava/lang/String;Ljava/util/List;)Ljava/lang/String;` | final |
| `com.deepseekharness.app.util.NetworkHealthPolicy.parse` | `(Ljava/lang/String;)Ljava/util/List;` | final |
| `com.deepseekharness.app.util.NetworkHealthPolicy.recent` | `(Ljava/util/List;I)Ljava/util/List;` | final |
| `com.deepseekharness.app.util.NetworkHealthPolicy.redactNetReport` | `(Ljava/lang/String;)Ljava/lang/String;` | final |
| `com.deepseekharness.app.util.NetworkHealthPolicy.resolvState` | `(Ljava/lang/String;)Ljava/lang/String;` | final |
| `com.deepseekharness.app.util.NetworkHealthPolicy.safeToken` | `(Ljava/lang/String;)Ljava/lang/String;` | final |
| `com.deepseekharness.app.util.NetworkHealthPolicy.serialize` | `(Ljava/util/List;)Ljava/lang/String;` | final |
| `com.deepseekharness.app.util.NetworkHealthPolicy.shouldProbe` | `(Lcom/deepseekharness/app/util/NetworkHealthPolicy$Signals;)Z` | final |
| `com.deepseekharness.app.util.NetworkHealthPolicy.totals` | `(Ljava/util/List;)Ljava/util/Map;` | final |
| `com.deepseekharness.app.util.NetworkHealthPolicy$Probe.ok` | `()Z` | final |

## Kotlin 编译器新增脚手架

这些不与七项原可见性差异混计：Companion 类/字段、访问私有成员的 synthetic access、synthetic 构造桥、lambda 实现、枚举 entries。它们不是新的业务控制接口；原 private 声明、构造和状态字段未放宽。完整 javap 保留其具体可见性，不把它们全部声称为 synthetic。

| Owner | 类别 / 名称 | descriptor |
|---|---|---|
| `com.deepseekharness.app.nethealth.NetworkHealthMonitor` | `field Companion` | `Lcom/deepseekharness/app/nethealth/NetworkHealthMonitor$Companion;` |
| `com.deepseekharness.app.nethealth.NetworkHealthMonitor` | `method <init>` | `(Landroid/content/Context;Lkotlin/jvm/internal/DefaultConstructorMarker;)V` |
| `com.deepseekharness.app.nethealth.NetworkHealthMonitor` | `method access$getInstance$cp` | `()Lcom/deepseekharness/app/nethealth/NetworkHealthMonitor;` |
| `com.deepseekharness.app.nethealth.NetworkHealthMonitor` | `method access$getSTART$cp` | `()Ljava/lang/Object;` |
| `com.deepseekharness.app.nethealth.NetworkHealthMonitor` | `method access$setInstance$cp` | `(Lcom/deepseekharness/app/nethealth/NetworkHealthMonitor;)V` |
| `com.deepseekharness.app.nethealth.NetworkHealthMonitor` | `method access$start` | `(Lcom/deepseekharness/app/nethealth/NetworkHealthMonitor;)V` |
| `com.deepseekharness.app.nethealth.NetworkHealthMonitor` | `method checkNow$lambda$3` | `(Lcom/deepseekharness/app/nethealth/NetworkHealthMonitor;Ljava/util/function/Consumer;)V` |
| `com.deepseekharness.app.nethealth.NetworkHealthMonitor` | `method schedule$lambda$4` | `(Lcom/deepseekharness/app/nethealth/NetworkHealthMonitor;J)V` |
| `com.deepseekharness.app.nethealth.NetworkHealthMonitor` | `method start$lambda$1` | `(Lcom/deepseekharness/app/nethealth/NetworkHealthMonitor;)V` |
| `com.deepseekharness.app.nethealth.NetworkHealthMonitor` | `method start$lambda$2` | `(Lcom/deepseekharness/app/nethealth/NetworkHealthMonitor;Lcom/deepseekharness/app/device/NetworkStateMonitor$Event;Lcom/deepseekharness/app/util/NetworkSnapshot;)V` |
| `com.deepseekharness.app.nethealth.NetworkHealthMonitor` | `method worker$lambda$0` | `(Ljava/lang/Runnable;)Ljava/lang/Thread;` |
| `com.deepseekharness.app.nethealth.NetworkHealthMonitor$Companion` | `type ` | `—` |
| `com.deepseekharness.app.nethealth.NetworkHealthProbe` | `field Companion` | `Lcom/deepseekharness/app/nethealth/NetworkHealthProbe$Companion;` |
| `com.deepseekharness.app.nethealth.NetworkHealthProbe$Companion` | `type ` | `—` |
| `com.deepseekharness.app.util.NetworkHealthPolicy` | `field Companion` | `Lcom/deepseekharness/app/util/NetworkHealthPolicy$Companion;` |
| `com.deepseekharness.app.util.NetworkHealthPolicy` | `method access$getRESOLV_STATES$cp` | `()Ljava/util/List;` |
| `com.deepseekharness.app.util.NetworkHealthPolicy` | `method access$getTRANSPORTS$cp` | `()Ljava/util/List;` |
| `com.deepseekharness.app.util.NetworkHealthPolicy$Companion` | `type ` | `—` |
| `com.deepseekharness.app.util.NetworkHealthPolicy$Verdict` | `field $ENTRIES` | `Lkotlin/enums/EnumEntries;` |
| `com.deepseekharness.app.util.NetworkHealthPolicy$Verdict` | `method getEntries` | `()Lkotlin/enums/EnumEntries;` |

## Companion 类型内部成员（独立于外层 20 条）

外层 20 条只统计原 8 类型的 17 个新增成员和 3 个类型本体，未展开新类型内部；因此不能把 20 当作全部新增成员数。3 个新类型内部经本轮 `javap -p -v` 逐项盘点如下：

| Companion owner | public 非 synthetic 方法 | public synthetic 构造桥 | private 构造 | private lambda helper |
|---|---:|---:|---:|---:|
| `NetworkHealthMonitor$Companion` | 1 | 1 | 1 | 0 |
| `NetworkHealthProbe$Companion` | 1 | 1 | 1 | 1 |
| `NetworkHealthPolicy$Companion` | 14 | 1 | 1 | 2 |
| 合计（内部成员共 25） | **16** | **3** | **3** | **3** |

16 个普通公共方法的 flags 均为 `ACC_PUBLIC, ACC_FINAL`，没有 `ACC_SYNTHETIC`：Monitor 的 `ensureStarted`、Probe 的 `run`，以及 Policy 的 `classify/shouldProbe/frozenGap/evidence/append/recent/serialize/parse/resolvState/redactNetReport/totals/known/errorName/safeToken`。它们是实存的 JVM public 方法，不隐藏为 synthetic，也不混计到七项原可见性差异。

3 个公共构造桥的 descriptor 均为 `(Lkotlin/jvm/internal/DefaultConstructorMarker;)V`，flags 为 `ACC_PUBLIC, ACC_SYNTHETIC`；3 个 private 无参构造的 descriptor 均为 `()V`。另有 Probe 的 `run$lambda$0`、Policy 的 `totals$lambda$1` 与 `totals$lambda$2` 三个 private static final helper。完整签名仍保留在下面 Kotlin 原文；最终轮 flags 原始输出和机器计数位于 `/tmp/nethealth-final-8211ba69/after/*Companion.javap-v.txt` 与 `/tmp/nethealth-final-8211ba69/companion.json`；e368 中间轮的 `/tmp/nethealth-p1-after-8211ba69/*Companion.javap-v.txt` 和 `/tmp/nethealth-p1-companion-8211ba69.json` 仍保留。

## Java 冻结：完整 javap -p -s

### `com.deepseekharness.app.nethealth.NetworkHealthMonitor`

```text
Compiled from "NetworkHealthMonitor.java"
public final class com.deepseekharness.app.nethealth.NetworkHealthMonitor {
  private static final java.lang.String TAG;
    descriptor: Ljava/lang/String;
  private static final long NETWORK_SETTLE_MS;
    descriptor: J
  private static final long BACKGROUND_SETTLE_MS;
    descriptor: J
  private static final java.lang.String HARNESS_SERVICE;
    descriptor: Ljava/lang/String;
  private static final java.lang.Object START;
    descriptor: Ljava/lang/Object;
  private static volatile com.deepseekharness.app.nethealth.NetworkHealthMonitor instance;
    descriptor: Lcom/deepseekharness/app/nethealth/NetworkHealthMonitor;
  private final android.content.Context app;
    descriptor: Landroid/content/Context;
  private final java.util.concurrent.ScheduledExecutorService worker;
    descriptor: Ljava/util/concurrent/ScheduledExecutorService;
  private final java.io.File store;
    descriptor: Ljava/io/File;
  private java.util.List<com.deepseekharness.app.util.NetworkHealthPolicy$Event> events;
    descriptor: Ljava/util/List;
  private long lastBeat;
    descriptor: J
  private long pendingGap;
    descriptor: J
  private java.util.concurrent.ScheduledFuture<?> settle;
    descriptor: Ljava/util/concurrent/ScheduledFuture;
  private volatile java.util.List<com.deepseekharness.app.util.NetworkHealthPolicy$Event> published;
    descriptor: Ljava/util/List;
  private volatile com.deepseekharness.app.util.NetworkHealthPolicy$Verdict lastVerdict;
    descriptor: Lcom/deepseekharness/app/util/NetworkHealthPolicy$Verdict;
  private com.deepseekharness.app.nethealth.NetworkHealthMonitor(android.content.Context);
    descriptor: (Landroid/content/Context;)V

  public static com.deepseekharness.app.nethealth.NetworkHealthMonitor ensureStarted(android.content.Context);
    descriptor: (Landroid/content/Context;)Lcom/deepseekharness/app/nethealth/NetworkHealthMonitor;

  private void start();
    descriptor: ()V

  public void onAppBackground();
    descriptor: ()V

  public java.util.List<com.deepseekharness.app.util.NetworkHealthPolicy$Event> events();
    descriptor: ()Ljava/util/List;

  public com.deepseekharness.app.util.NetworkHealthPolicy$Verdict lastVerdict();
    descriptor: ()Lcom/deepseekharness/app/util/NetworkHealthPolicy$Verdict;

  public void checkNow(java.util.function.Consumer<com.deepseekharness.app.nethealth.NetworkHealthMonitor$Report>);
    descriptor: (Ljava/util/function/Consumer;)V

  private void schedule(long);
    descriptor: (J)V

  private void heartbeat();
    descriptor: ()V

  private void evaluate();
    descriptor: ()V

  private com.deepseekharness.app.util.NetworkHealthPolicy$Signals collect();
    descriptor: ()Lcom/deepseekharness/app/util/NetworkHealthPolicy$Signals;

  private boolean webActive();
    descriptor: ()Z

  private java.lang.String netReport();
    descriptor: ()Ljava/lang/String;

  private boolean harnessForeground();
    descriptor: ()Z

  private void record(com.deepseekharness.app.util.NetworkHealthPolicy$Verdict, java.lang.String);
    descriptor: (Lcom/deepseekharness/app/util/NetworkHealthPolicy$Verdict;Ljava/lang/String;)V

  private java.util.List<com.deepseekharness.app.util.NetworkHealthPolicy$Event> load();
    descriptor: ()Ljava/util/List;

  private void save(java.util.List<com.deepseekharness.app.util.NetworkHealthPolicy$Event>);
    descriptor: (Ljava/util/List;)V

  private void lambda$schedule$4(long);
    descriptor: (J)V

  private void lambda$checkNow$3(java.util.function.Consumer);
    descriptor: (Ljava/util/function/Consumer;)V

  private void lambda$start$2(com.deepseekharness.app.device.NetworkStateMonitor$Event, com.deepseekharness.app.util.NetworkSnapshot);
    descriptor: (Lcom/deepseekharness/app/device/NetworkStateMonitor$Event;Lcom/deepseekharness/app/util/NetworkSnapshot;)V

  private void lambda$start$1();
    descriptor: ()V

  private static java.lang.Thread lambda$new$0(java.lang.Runnable);
    descriptor: (Ljava/lang/Runnable;)Ljava/lang/Thread;

  static {};
    descriptor: ()V
}
```
### `com.deepseekharness.app.nethealth.NetworkHealthMonitor$Report`

```text
Compiled from "NetworkHealthMonitor.java"
public final class com.deepseekharness.app.nethealth.NetworkHealthMonitor$Report {
  public final com.deepseekharness.app.util.NetworkHealthPolicy$Verdict verdict;
    descriptor: Lcom/deepseekharness/app/util/NetworkHealthPolicy$Verdict;
  public final java.lang.String evidence;
    descriptor: Ljava/lang/String;
  public final java.lang.String text;
    descriptor: Ljava/lang/String;
  com.deepseekharness.app.nethealth.NetworkHealthMonitor$Report(com.deepseekharness.app.util.NetworkHealthPolicy$Verdict, java.lang.String, java.lang.String);
    descriptor: (Lcom/deepseekharness/app/util/NetworkHealthPolicy$Verdict;Ljava/lang/String;Ljava/lang/String;)V
}
```

### `com.deepseekharness.app.nethealth.NetworkHealthProbe`

```text
Compiled from "NetworkHealthProbe.java"
final class com.deepseekharness.app.nethealth.NetworkHealthProbe {
  static final java.lang.String HOST;
    descriptor: Ljava/lang/String;
  private static final int TIMEOUT_MS;
    descriptor: I
  private com.deepseekharness.app.nethealth.NetworkHealthProbe();
    descriptor: ()V

  static com.deepseekharness.app.util.NetworkHealthPolicy$Probe run();
    descriptor: ()Lcom/deepseekharness/app/util/NetworkHealthPolicy$Probe;

  private static java.net.InetAddress[] lambda$run$0() throws java.lang.Exception;
    descriptor: ()[Ljava/net/InetAddress;
}
```

### `com.deepseekharness.app.util.NetworkHealthPolicy`

```text
Compiled from "NetworkHealthPolicy.java"
public final class com.deepseekharness.app.util.NetworkHealthPolicy {
  public static final long HEARTBEAT_MS;
    descriptor: J
  public static final long FROZEN_GAP_MS;
    descriptor: J
  public static final long DEDUP_MS;
    descriptor: J
  public static final int MAX_EVENTS;
    descriptor: I
  private static final java.util.List<java.lang.String> TRANSPORTS;
    descriptor: Ljava/util/List;
  private static final java.util.List<java.lang.String> RESOLV_STATES;
    descriptor: Ljava/util/List;
  private com.deepseekharness.app.util.NetworkHealthPolicy();
    descriptor: ()V

  public static com.deepseekharness.app.util.NetworkHealthPolicy$Verdict classify(com.deepseekharness.app.util.NetworkHealthPolicy$Signals);
    descriptor: (Lcom/deepseekharness/app/util/NetworkHealthPolicy$Signals;)Lcom/deepseekharness/app/util/NetworkHealthPolicy$Verdict;

  public static boolean shouldProbe(com.deepseekharness.app.util.NetworkHealthPolicy$Signals);
    descriptor: (Lcom/deepseekharness/app/util/NetworkHealthPolicy$Signals;)Z

  public static long frozenGap(long, long);
    descriptor: (JJ)J

  public static java.lang.String evidence(com.deepseekharness.app.util.NetworkHealthPolicy$Signals);
    descriptor: (Lcom/deepseekharness/app/util/NetworkHealthPolicy$Signals;)Ljava/lang/String;

  public static java.util.List<com.deepseekharness.app.util.NetworkHealthPolicy$Event> append(java.util.List<com.deepseekharness.app.util.NetworkHealthPolicy$Event>, long, com.deepseekharness.app.util.NetworkHealthPolicy$Verdict, java.lang.String);
    descriptor: (Ljava/util/List;JLcom/deepseekharness/app/util/NetworkHealthPolicy$Verdict;Ljava/lang/String;)Ljava/util/List;

  public static java.util.List<com.deepseekharness.app.util.NetworkHealthPolicy$Event> recent(java.util.List<com.deepseekharness.app.util.NetworkHealthPolicy$Event>, int);
    descriptor: (Ljava/util/List;I)Ljava/util/List;

  public static java.lang.String serialize(java.util.List<com.deepseekharness.app.util.NetworkHealthPolicy$Event>);
    descriptor: (Ljava/util/List;)Ljava/lang/String;

  public static java.util.List<com.deepseekharness.app.util.NetworkHealthPolicy$Event> parse(java.lang.String);
    descriptor: (Ljava/lang/String;)Ljava/util/List;

  public static java.lang.String resolvState(java.lang.String);
    descriptor: (Ljava/lang/String;)Ljava/lang/String;

  public static java.lang.String redactNetReport(java.lang.String);
    descriptor: (Ljava/lang/String;)Ljava/lang/String;

  public static java.util.Map<com.deepseekharness.app.util.NetworkHealthPolicy$Verdict, java.lang.Integer> totals(java.util.List<com.deepseekharness.app.util.NetworkHealthPolicy$Event>);
    descriptor: (Ljava/util/List;)Ljava/util/Map;

  static java.lang.String known(java.lang.String, java.util.List<java.lang.String>);
    descriptor: (Ljava/lang/String;Ljava/util/List;)Ljava/lang/String;

  static java.lang.String errorName(java.lang.String);
    descriptor: (Ljava/lang/String;)Ljava/lang/String;

  static java.lang.String safeToken(java.lang.String);
    descriptor: (Ljava/lang/String;)Ljava/lang/String;

  static {};
    descriptor: ()V
}
```
### `com.deepseekharness.app.util.NetworkHealthPolicy$Event`

```text
Compiled from "NetworkHealthPolicy.java"
public final class com.deepseekharness.app.util.NetworkHealthPolicy$Event {
  public final long at;
    descriptor: J
  public final com.deepseekharness.app.util.NetworkHealthPolicy$Verdict verdict;
    descriptor: Lcom/deepseekharness/app/util/NetworkHealthPolicy$Verdict;
  public final int count;
    descriptor: I
  public final java.lang.String evidence;
    descriptor: Ljava/lang/String;
  public com.deepseekharness.app.util.NetworkHealthPolicy$Event(long, com.deepseekharness.app.util.NetworkHealthPolicy$Verdict, int, java.lang.String);
    descriptor: (JLcom/deepseekharness/app/util/NetworkHealthPolicy$Verdict;ILjava/lang/String;)V
}
```

### `com.deepseekharness.app.util.NetworkHealthPolicy$Probe`

```text
Compiled from "NetworkHealthPolicy.java"
public final class com.deepseekharness.app.util.NetworkHealthPolicy$Probe {
  public final boolean dnsOk;
    descriptor: Z
  public final boolean httpsOk;
    descriptor: Z
  public final long dnsMs;
    descriptor: J
  public final long httpsMs;
    descriptor: J
  public final java.lang.String error;
    descriptor: Ljava/lang/String;
  public com.deepseekharness.app.util.NetworkHealthPolicy$Probe(boolean, boolean, long, long, java.lang.String);
    descriptor: (ZZJJLjava/lang/String;)V

  public boolean ok();
    descriptor: ()Z
}
```

### `com.deepseekharness.app.util.NetworkHealthPolicy$Signals`

```text
Compiled from "NetworkHealthPolicy.java"
public final class com.deepseekharness.app.util.NetworkHealthPolicy$Signals {
  public boolean webActive;
    descriptor: Z
  public boolean foregroundService;
    descriptor: Z
  public boolean connected;
    descriptor: Z
  public boolean blocked;
    descriptor: Z
  public boolean validated;
    descriptor: Z
  public boolean vpn;
    descriptor: Z
  public java.lang.String transport;
    descriptor: Ljava/lang/String;
  public java.lang.String resolvState;
    descriptor: Ljava/lang/String;
  public com.deepseekharness.app.util.NetworkHealthPolicy$Probe probe;
    descriptor: Lcom/deepseekharness/app/util/NetworkHealthPolicy$Probe;
  public long frozenGapMs;
    descriptor: J
  public com.deepseekharness.app.util.NetworkHealthPolicy$Signals();
    descriptor: ()V
}
```

### `com.deepseekharness.app.util.NetworkHealthPolicy$Verdict`

```text
Compiled from "NetworkHealthPolicy.java"
public final class com.deepseekharness.app.util.NetworkHealthPolicy$Verdict extends java.lang.Enum<com.deepseekharness.app.util.NetworkHealthPolicy$Verdict> {
  public static final com.deepseekharness.app.util.NetworkHealthPolicy$Verdict OK;
    descriptor: Lcom/deepseekharness/app/util/NetworkHealthPolicy$Verdict;
  public static final com.deepseekharness.app.util.NetworkHealthPolicy$Verdict FGS_MISSING;
    descriptor: Lcom/deepseekharness/app/util/NetworkHealthPolicy$Verdict;
  public static final com.deepseekharness.app.util.NetworkHealthPolicy$Verdict SYSTEM_BLOCKED;
    descriptor: Lcom/deepseekharness/app/util/NetworkHealthPolicy$Verdict;
  public static final com.deepseekharness.app.util.NetworkHealthPolicy$Verdict DNS_STALE;
    descriptor: Lcom/deepseekharness/app/util/NetworkHealthPolicy$Verdict;
  public static final com.deepseekharness.app.util.NetworkHealthPolicy$Verdict EGRESS_FAILED;
    descriptor: Lcom/deepseekharness/app/util/NetworkHealthPolicy$Verdict;
  public static final com.deepseekharness.app.util.NetworkHealthPolicy$Verdict SUSPECT_FROZEN;
    descriptor: Lcom/deepseekharness/app/util/NetworkHealthPolicy$Verdict;
  private static final com.deepseekharness.app.util.NetworkHealthPolicy$Verdict[] $VALUES;
    descriptor: [Lcom/deepseekharness/app/util/NetworkHealthPolicy$Verdict;
  public static com.deepseekharness.app.util.NetworkHealthPolicy$Verdict[] values();
    descriptor: ()[Lcom/deepseekharness/app/util/NetworkHealthPolicy$Verdict;

  public static com.deepseekharness.app.util.NetworkHealthPolicy$Verdict valueOf(java.lang.String);
    descriptor: (Ljava/lang/String;)Lcom/deepseekharness/app/util/NetworkHealthPolicy$Verdict;

  private com.deepseekharness.app.util.NetworkHealthPolicy$Verdict();
    descriptor: (Ljava/lang/String;I)V

  private static com.deepseekharness.app.util.NetworkHealthPolicy$Verdict[] $values();
    descriptor: ()[Lcom/deepseekharness/app/util/NetworkHealthPolicy$Verdict;

  static {};
    descriptor: ()V
}
```

## Kotlin 产物：完整 javap -p -s

### `com.deepseekharness.app.nethealth.NetworkHealthMonitor`

```text
Compiled from "NetworkHealthMonitor.kt"
public final class com.deepseekharness.app.nethealth.NetworkHealthMonitor {
  public static final com.deepseekharness.app.nethealth.NetworkHealthMonitor$Companion Companion;
    descriptor: Lcom/deepseekharness/app/nethealth/NetworkHealthMonitor$Companion;
  private final android.content.Context app;
    descriptor: Landroid/content/Context;
  private final java.util.concurrent.ScheduledExecutorService worker;
    descriptor: Ljava/util/concurrent/ScheduledExecutorService;
  private final java.io.File store;
    descriptor: Ljava/io/File;
  private java.util.List<com.deepseekharness.app.util.NetworkHealthPolicy$Event> events;
    descriptor: Ljava/util/List;
  private long lastBeat;
    descriptor: J
  private long pendingGap;
    descriptor: J
  private java.util.concurrent.ScheduledFuture<?> settle;
    descriptor: Ljava/util/concurrent/ScheduledFuture;
  private volatile java.util.List<com.deepseekharness.app.util.NetworkHealthPolicy$Event> published;
    descriptor: Ljava/util/List;
  private volatile com.deepseekharness.app.util.NetworkHealthPolicy$Verdict lastVerdict;
    descriptor: Lcom/deepseekharness/app/util/NetworkHealthPolicy$Verdict;
  private static final java.lang.String TAG;
    descriptor: Ljava/lang/String;
  private static final long NETWORK_SETTLE_MS;
    descriptor: J
  private static final long BACKGROUND_SETTLE_MS;
    descriptor: J
  private static final java.lang.String HARNESS_SERVICE;
    descriptor: Ljava/lang/String;
  private static final java.lang.Object START;
    descriptor: Ljava/lang/Object;
  private static volatile com.deepseekharness.app.nethealth.NetworkHealthMonitor instance;
    descriptor: Lcom/deepseekharness/app/nethealth/NetworkHealthMonitor;
  private com.deepseekharness.app.nethealth.NetworkHealthMonitor(android.content.Context);
    descriptor: (Landroid/content/Context;)V

  private final void start();
    descriptor: ()V

  public final void onAppBackground();
    descriptor: ()V

  public final java.util.List<com.deepseekharness.app.util.NetworkHealthPolicy$Event> events();
    descriptor: ()Ljava/util/List;

  public final com.deepseekharness.app.util.NetworkHealthPolicy$Verdict lastVerdict();
    descriptor: ()Lcom/deepseekharness/app/util/NetworkHealthPolicy$Verdict;

  public final void checkNow(java.util.function.Consumer<com.deepseekharness.app.nethealth.NetworkHealthMonitor$Report>);
    descriptor: (Ljava/util/function/Consumer;)V

  private final void schedule(long);
    descriptor: (J)V

  private final void heartbeat();
    descriptor: ()V

  private final void evaluate();
    descriptor: ()V

  private final com.deepseekharness.app.util.NetworkHealthPolicy$Signals collect();
    descriptor: ()Lcom/deepseekharness/app/util/NetworkHealthPolicy$Signals;

  private final boolean webActive();
    descriptor: ()Z

  private final java.lang.String netReport();
    descriptor: ()Ljava/lang/String;

  private final boolean harnessForeground();
    descriptor: ()Z

  private final void record(com.deepseekharness.app.util.NetworkHealthPolicy$Verdict, java.lang.String);
    descriptor: (Lcom/deepseekharness/app/util/NetworkHealthPolicy$Verdict;Ljava/lang/String;)V

  private final java.util.List<com.deepseekharness.app.util.NetworkHealthPolicy$Event> load();
    descriptor: ()Ljava/util/List;

  private final void save(java.util.List<com.deepseekharness.app.util.NetworkHealthPolicy$Event>);
    descriptor: (Ljava/util/List;)V

  private static final java.lang.Thread worker$lambda$0(java.lang.Runnable);
    descriptor: (Ljava/lang/Runnable;)Ljava/lang/Thread;

  private static final void start$lambda$1(com.deepseekharness.app.nethealth.NetworkHealthMonitor);
    descriptor: (Lcom/deepseekharness/app/nethealth/NetworkHealthMonitor;)V

  private static final void start$lambda$2(com.deepseekharness.app.nethealth.NetworkHealthMonitor, com.deepseekharness.app.device.NetworkStateMonitor$Event, com.deepseekharness.app.util.NetworkSnapshot);
    descriptor: (Lcom/deepseekharness/app/nethealth/NetworkHealthMonitor;Lcom/deepseekharness/app/device/NetworkStateMonitor$Event;Lcom/deepseekharness/app/util/NetworkSnapshot;)V

  private static final void checkNow$lambda$3(com.deepseekharness.app.nethealth.NetworkHealthMonitor, java.util.function.Consumer);
    descriptor: (Lcom/deepseekharness/app/nethealth/NetworkHealthMonitor;Ljava/util/function/Consumer;)V

  private static final void schedule$lambda$4(com.deepseekharness.app.nethealth.NetworkHealthMonitor, long);
    descriptor: (Lcom/deepseekharness/app/nethealth/NetworkHealthMonitor;J)V

  public static final com.deepseekharness.app.nethealth.NetworkHealthMonitor ensureStarted(android.content.Context);
    descriptor: (Landroid/content/Context;)Lcom/deepseekharness/app/nethealth/NetworkHealthMonitor;

  public static final com.deepseekharness.app.nethealth.NetworkHealthMonitor access$getInstance$cp();
    descriptor: ()Lcom/deepseekharness/app/nethealth/NetworkHealthMonitor;

  public static final java.lang.Object access$getSTART$cp();
    descriptor: ()Ljava/lang/Object;

  public com.deepseekharness.app.nethealth.NetworkHealthMonitor(android.content.Context, kotlin.jvm.internal.DefaultConstructorMarker);
    descriptor: (Landroid/content/Context;Lkotlin/jvm/internal/DefaultConstructorMarker;)V

  public static final void access$start(com.deepseekharness.app.nethealth.NetworkHealthMonitor);
    descriptor: (Lcom/deepseekharness/app/nethealth/NetworkHealthMonitor;)V

  public static final void access$setInstance$cp(com.deepseekharness.app.nethealth.NetworkHealthMonitor);
    descriptor: (Lcom/deepseekharness/app/nethealth/NetworkHealthMonitor;)V

  static {};
    descriptor: ()V
}
```
### `com.deepseekharness.app.nethealth.NetworkHealthMonitor$Companion`

```text
Compiled from "NetworkHealthMonitor.kt"
public final class com.deepseekharness.app.nethealth.NetworkHealthMonitor$Companion {
  private com.deepseekharness.app.nethealth.NetworkHealthMonitor$Companion();
    descriptor: ()V

  public final com.deepseekharness.app.nethealth.NetworkHealthMonitor ensureStarted(android.content.Context);
    descriptor: (Landroid/content/Context;)Lcom/deepseekharness/app/nethealth/NetworkHealthMonitor;

  public com.deepseekharness.app.nethealth.NetworkHealthMonitor$Companion(kotlin.jvm.internal.DefaultConstructorMarker);
    descriptor: (Lkotlin/jvm/internal/DefaultConstructorMarker;)V
}
```

### `com.deepseekharness.app.nethealth.NetworkHealthMonitor$Report`

```text
Compiled from "NetworkHealthMonitor.kt"
public final class com.deepseekharness.app.nethealth.NetworkHealthMonitor$Report {
  public final com.deepseekharness.app.util.NetworkHealthPolicy$Verdict verdict;
    descriptor: Lcom/deepseekharness/app/util/NetworkHealthPolicy$Verdict;
  public final java.lang.String evidence;
    descriptor: Ljava/lang/String;
  public final java.lang.String text;
    descriptor: Ljava/lang/String;
  public com.deepseekharness.app.nethealth.NetworkHealthMonitor$Report(com.deepseekharness.app.util.NetworkHealthPolicy$Verdict, java.lang.String, java.lang.String);
    descriptor: (Lcom/deepseekharness/app/util/NetworkHealthPolicy$Verdict;Ljava/lang/String;Ljava/lang/String;)V
}
```

### `com.deepseekharness.app.nethealth.NetworkHealthProbe`

```text
Compiled from "NetworkHealthProbe.kt"
public final class com.deepseekharness.app.nethealth.NetworkHealthProbe {
  public static final com.deepseekharness.app.nethealth.NetworkHealthProbe$Companion Companion;
    descriptor: Lcom/deepseekharness/app/nethealth/NetworkHealthProbe$Companion;
  public static final java.lang.String HOST;
    descriptor: Ljava/lang/String;
  private static final int TIMEOUT_MS;
    descriptor: I
  private com.deepseekharness.app.nethealth.NetworkHealthProbe();
    descriptor: ()V

  public static final com.deepseekharness.app.util.NetworkHealthPolicy$Probe run();
    descriptor: ()Lcom/deepseekharness/app/util/NetworkHealthPolicy$Probe;

  static {};
    descriptor: ()V
}
```
### `com.deepseekharness.app.nethealth.NetworkHealthProbe$Companion`

```text
Compiled from "NetworkHealthProbe.kt"
public final class com.deepseekharness.app.nethealth.NetworkHealthProbe$Companion {
  private com.deepseekharness.app.nethealth.NetworkHealthProbe$Companion();
    descriptor: ()V

  public final com.deepseekharness.app.util.NetworkHealthPolicy$Probe run();
    descriptor: ()Lcom/deepseekharness/app/util/NetworkHealthPolicy$Probe;

  private static final java.net.InetAddress[] run$lambda$0();
    descriptor: ()[Ljava/net/InetAddress;

  public com.deepseekharness.app.nethealth.NetworkHealthProbe$Companion(kotlin.jvm.internal.DefaultConstructorMarker);
    descriptor: (Lkotlin/jvm/internal/DefaultConstructorMarker;)V
}
```

### `com.deepseekharness.app.util.NetworkHealthPolicy`

```text
Compiled from "NetworkHealthPolicy.kt"
public final class com.deepseekharness.app.util.NetworkHealthPolicy {
  public static final com.deepseekharness.app.util.NetworkHealthPolicy$Companion Companion;
    descriptor: Lcom/deepseekharness/app/util/NetworkHealthPolicy$Companion;
  public static final long HEARTBEAT_MS;
    descriptor: J
  public static final long FROZEN_GAP_MS;
    descriptor: J
  public static final long DEDUP_MS;
    descriptor: J
  public static final int MAX_EVENTS;
    descriptor: I
  private static final java.util.List<java.lang.String> TRANSPORTS;
    descriptor: Ljava/util/List;
  private static final java.util.List<java.lang.String> RESOLV_STATES;
    descriptor: Ljava/util/List;
  private com.deepseekharness.app.util.NetworkHealthPolicy();
    descriptor: ()V

  public static final com.deepseekharness.app.util.NetworkHealthPolicy$Verdict classify(com.deepseekharness.app.util.NetworkHealthPolicy$Signals);
    descriptor: (Lcom/deepseekharness/app/util/NetworkHealthPolicy$Signals;)Lcom/deepseekharness/app/util/NetworkHealthPolicy$Verdict;

  public static final boolean shouldProbe(com.deepseekharness.app.util.NetworkHealthPolicy$Signals);
    descriptor: (Lcom/deepseekharness/app/util/NetworkHealthPolicy$Signals;)Z

  public static final long frozenGap(long, long);
    descriptor: (JJ)J

  public static final java.lang.String evidence(com.deepseekharness.app.util.NetworkHealthPolicy$Signals);
    descriptor: (Lcom/deepseekharness/app/util/NetworkHealthPolicy$Signals;)Ljava/lang/String;

  public static final java.util.List<com.deepseekharness.app.util.NetworkHealthPolicy$Event> append(java.util.List<com.deepseekharness.app.util.NetworkHealthPolicy$Event>, long, com.deepseekharness.app.util.NetworkHealthPolicy$Verdict, java.lang.String);
    descriptor: (Ljava/util/List;JLcom/deepseekharness/app/util/NetworkHealthPolicy$Verdict;Ljava/lang/String;)Ljava/util/List;

  public static final java.util.List<com.deepseekharness.app.util.NetworkHealthPolicy$Event> recent(java.util.List<com.deepseekharness.app.util.NetworkHealthPolicy$Event>, int);
    descriptor: (Ljava/util/List;I)Ljava/util/List;

  public static final java.lang.String serialize(java.util.List<com.deepseekharness.app.util.NetworkHealthPolicy$Event>);
    descriptor: (Ljava/util/List;)Ljava/lang/String;

  public static final java.util.List<com.deepseekharness.app.util.NetworkHealthPolicy$Event> parse(java.lang.String);
    descriptor: (Ljava/lang/String;)Ljava/util/List;

  public static final java.lang.String resolvState(java.lang.String);
    descriptor: (Ljava/lang/String;)Ljava/lang/String;

  public static final java.lang.String redactNetReport(java.lang.String);
    descriptor: (Ljava/lang/String;)Ljava/lang/String;

  public static final java.util.Map<com.deepseekharness.app.util.NetworkHealthPolicy$Verdict, java.lang.Integer> totals(java.util.List<com.deepseekharness.app.util.NetworkHealthPolicy$Event>);
    descriptor: (Ljava/util/List;)Ljava/util/Map;

  public static final java.lang.String known(java.lang.String, java.util.List<java.lang.String>);
    descriptor: (Ljava/lang/String;Ljava/util/List;)Ljava/lang/String;

  public static final java.lang.String errorName(java.lang.String);
    descriptor: (Ljava/lang/String;)Ljava/lang/String;

  public static final java.lang.String safeToken(java.lang.String);
    descriptor: (Ljava/lang/String;)Ljava/lang/String;

  public static final java.util.List access$getTRANSPORTS$cp();
    descriptor: ()Ljava/util/List;

  public static final java.util.List access$getRESOLV_STATES$cp();
    descriptor: ()Ljava/util/List;

  static {};
    descriptor: ()V
}
```
### `com.deepseekharness.app.util.NetworkHealthPolicy$Companion`

```text
Compiled from "NetworkHealthPolicy.kt"
public final class com.deepseekharness.app.util.NetworkHealthPolicy$Companion {
  private com.deepseekharness.app.util.NetworkHealthPolicy$Companion();
    descriptor: ()V

  public final com.deepseekharness.app.util.NetworkHealthPolicy$Verdict classify(com.deepseekharness.app.util.NetworkHealthPolicy$Signals);
    descriptor: (Lcom/deepseekharness/app/util/NetworkHealthPolicy$Signals;)Lcom/deepseekharness/app/util/NetworkHealthPolicy$Verdict;

  public final boolean shouldProbe(com.deepseekharness.app.util.NetworkHealthPolicy$Signals);
    descriptor: (Lcom/deepseekharness/app/util/NetworkHealthPolicy$Signals;)Z

  public final long frozenGap(long, long);
    descriptor: (JJ)J

  public final java.lang.String evidence(com.deepseekharness.app.util.NetworkHealthPolicy$Signals);
    descriptor: (Lcom/deepseekharness/app/util/NetworkHealthPolicy$Signals;)Ljava/lang/String;

  public final java.util.List<com.deepseekharness.app.util.NetworkHealthPolicy$Event> append(java.util.List<com.deepseekharness.app.util.NetworkHealthPolicy$Event>, long, com.deepseekharness.app.util.NetworkHealthPolicy$Verdict, java.lang.String);
    descriptor: (Ljava/util/List;JLcom/deepseekharness/app/util/NetworkHealthPolicy$Verdict;Ljava/lang/String;)Ljava/util/List;

  public final java.util.List<com.deepseekharness.app.util.NetworkHealthPolicy$Event> recent(java.util.List<com.deepseekharness.app.util.NetworkHealthPolicy$Event>, int);
    descriptor: (Ljava/util/List;I)Ljava/util/List;

  public final java.lang.String serialize(java.util.List<com.deepseekharness.app.util.NetworkHealthPolicy$Event>);
    descriptor: (Ljava/util/List;)Ljava/lang/String;

  public final java.util.List<com.deepseekharness.app.util.NetworkHealthPolicy$Event> parse(java.lang.String);
    descriptor: (Ljava/lang/String;)Ljava/util/List;

  public final java.lang.String resolvState(java.lang.String);
    descriptor: (Ljava/lang/String;)Ljava/lang/String;

  public final java.lang.String redactNetReport(java.lang.String);
    descriptor: (Ljava/lang/String;)Ljava/lang/String;

  public final java.util.Map<com.deepseekharness.app.util.NetworkHealthPolicy$Verdict, java.lang.Integer> totals(java.util.List<com.deepseekharness.app.util.NetworkHealthPolicy$Event>);
    descriptor: (Ljava/util/List;)Ljava/util/Map;

  public final java.lang.String known(java.lang.String, java.util.List<java.lang.String>);
    descriptor: (Ljava/lang/String;Ljava/util/List;)Ljava/lang/String;

  public final java.lang.String errorName(java.lang.String);
    descriptor: (Ljava/lang/String;)Ljava/lang/String;

  public final java.lang.String safeToken(java.lang.String);
    descriptor: (Ljava/lang/String;)Ljava/lang/String;

  private static final java.lang.Integer totals$lambda$1(java.lang.Integer, java.lang.Integer);
    descriptor: (Ljava/lang/Integer;Ljava/lang/Integer;)Ljava/lang/Integer;

  private static final java.lang.Integer totals$lambda$2(kotlin.jvm.functions.Function2, java.lang.Object, java.lang.Object);
    descriptor: (Lkotlin/jvm/functions/Function2;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Integer;

  public com.deepseekharness.app.util.NetworkHealthPolicy$Companion(kotlin.jvm.internal.DefaultConstructorMarker);
    descriptor: (Lkotlin/jvm/internal/DefaultConstructorMarker;)V
}
```

### `com.deepseekharness.app.util.NetworkHealthPolicy$Event`

```text
Compiled from "NetworkHealthPolicy.kt"
public final class com.deepseekharness.app.util.NetworkHealthPolicy$Event {
  public final long at;
    descriptor: J
  public final com.deepseekharness.app.util.NetworkHealthPolicy$Verdict verdict;
    descriptor: Lcom/deepseekharness/app/util/NetworkHealthPolicy$Verdict;
  public final int count;
    descriptor: I
  public final java.lang.String evidence;
    descriptor: Ljava/lang/String;
  public com.deepseekharness.app.util.NetworkHealthPolicy$Event(long, com.deepseekharness.app.util.NetworkHealthPolicy$Verdict, int, java.lang.String);
    descriptor: (JLcom/deepseekharness/app/util/NetworkHealthPolicy$Verdict;ILjava/lang/String;)V
}
```

### `com.deepseekharness.app.util.NetworkHealthPolicy$Probe`

```text
Compiled from "NetworkHealthPolicy.kt"
public final class com.deepseekharness.app.util.NetworkHealthPolicy$Probe {
  public final boolean dnsOk;
    descriptor: Z
  public final boolean httpsOk;
    descriptor: Z
  public final long dnsMs;
    descriptor: J
  public final long httpsMs;
    descriptor: J
  public final java.lang.String error;
    descriptor: Ljava/lang/String;
  public com.deepseekharness.app.util.NetworkHealthPolicy$Probe(boolean, boolean, long, long, java.lang.String);
    descriptor: (ZZJJLjava/lang/String;)V

  public final boolean ok();
    descriptor: ()Z
}
```

### `com.deepseekharness.app.util.NetworkHealthPolicy$Signals`

```text
Compiled from "NetworkHealthPolicy.kt"
public final class com.deepseekharness.app.util.NetworkHealthPolicy$Signals {
  public boolean webActive;
    descriptor: Z
  public boolean foregroundService;
    descriptor: Z
  public boolean connected;
    descriptor: Z
  public boolean blocked;
    descriptor: Z
  public boolean validated;
    descriptor: Z
  public boolean vpn;
    descriptor: Z
  public java.lang.String transport;
    descriptor: Ljava/lang/String;
  public java.lang.String resolvState;
    descriptor: Ljava/lang/String;
  public com.deepseekharness.app.util.NetworkHealthPolicy$Probe probe;
    descriptor: Lcom/deepseekharness/app/util/NetworkHealthPolicy$Probe;
  public long frozenGapMs;
    descriptor: J
  public com.deepseekharness.app.util.NetworkHealthPolicy$Signals();
    descriptor: ()V
}
```

### `com.deepseekharness.app.util.NetworkHealthPolicy$Verdict`

```text
Compiled from "NetworkHealthPolicy.kt"
public final class com.deepseekharness.app.util.NetworkHealthPolicy$Verdict extends java.lang.Enum<com.deepseekharness.app.util.NetworkHealthPolicy$Verdict> {
  public static final com.deepseekharness.app.util.NetworkHealthPolicy$Verdict OK;
    descriptor: Lcom/deepseekharness/app/util/NetworkHealthPolicy$Verdict;
  public static final com.deepseekharness.app.util.NetworkHealthPolicy$Verdict FGS_MISSING;
    descriptor: Lcom/deepseekharness/app/util/NetworkHealthPolicy$Verdict;
  public static final com.deepseekharness.app.util.NetworkHealthPolicy$Verdict SYSTEM_BLOCKED;
    descriptor: Lcom/deepseekharness/app/util/NetworkHealthPolicy$Verdict;
  public static final com.deepseekharness.app.util.NetworkHealthPolicy$Verdict DNS_STALE;
    descriptor: Lcom/deepseekharness/app/util/NetworkHealthPolicy$Verdict;
  public static final com.deepseekharness.app.util.NetworkHealthPolicy$Verdict EGRESS_FAILED;
    descriptor: Lcom/deepseekharness/app/util/NetworkHealthPolicy$Verdict;
  public static final com.deepseekharness.app.util.NetworkHealthPolicy$Verdict SUSPECT_FROZEN;
    descriptor: Lcom/deepseekharness/app/util/NetworkHealthPolicy$Verdict;
  private static final com.deepseekharness.app.util.NetworkHealthPolicy$Verdict[] $VALUES;
    descriptor: [Lcom/deepseekharness/app/util/NetworkHealthPolicy$Verdict;
  private static final kotlin.enums.EnumEntries $ENTRIES;
    descriptor: Lkotlin/enums/EnumEntries;
  private com.deepseekharness.app.util.NetworkHealthPolicy$Verdict();
    descriptor: (Ljava/lang/String;I)V

  public static com.deepseekharness.app.util.NetworkHealthPolicy$Verdict[] values();
    descriptor: ()[Lcom/deepseekharness/app/util/NetworkHealthPolicy$Verdict;

  public static com.deepseekharness.app.util.NetworkHealthPolicy$Verdict valueOf(java.lang.String);
    descriptor: (Ljava/lang/String;)Lcom/deepseekharness/app/util/NetworkHealthPolicy$Verdict;

  public static kotlin.enums.EnumEntries<com.deepseekharness.app.util.NetworkHealthPolicy$Verdict> getEntries();
    descriptor: ()Lkotlin/enums/EnumEntries;

  private static final com.deepseekharness.app.util.NetworkHealthPolicy$Verdict[] $values();
    descriptor: ()[Lcom/deepseekharness/app/util/NetworkHealthPolicy$Verdict;

  static {};
    descriptor: ()V
}
```
