# 网络健康封闭切片 Kotlin 等价迁移回执（2026-11-15）

## 最终交付基线与验证快照

本分支已在 SAF、transfer 合入后，重基线到 main 指定的 `823fd4c83184c65ec7311357d8e3b53595fe8da7`。本轮 Java 测试冻结提交为 `f1ad2bc03003694466741a31bac5f0e4981c6888`，生产/测试验证执行的源提交为 `8920acc41881cc31aafe6cf711b64ba6d0ad8635`。三份生产 Kotlin 与七份测试源码相对主审完成的版本逐字节相等；回执收口提交只调整本文件及 ABI 回执。最终可审 HEAD 的完整 SHA 记录在 `.SESSION-DONE` 和同轮 `peer_report ready`，与本节验证源提交明确分开。

主会话此前已独立确认代码、ABI、59 项身份与复跑通过；两项 docs P2（基线/回退指向、Companion 成员计数）已在 `9945bd6b` 修正。`b3602555/fcea9e41/67428782`、`e368e6d8/79fa4772/f0809ddc/9945bd6b` 均仅作前轮历史，不能作为当前分支按字面回退的 SHA。下文保留 Java 输入 SHA-256 表及两轮旧日志，最终基线的重新执行结果见「SAF/transfer 合入后最终复验」，不将旧树成果当作本轮验证。

## 初轮范围与基线（b3602555）

开工 `git fetch origin main` 后基线为 `b3602555b39056279bc5131e7eaefc1a6be114ae`，分支 `migrate-nethealth-kotlin`。发布仍是 build366 / `5ccc8aa3db4c961553a348a8eb35cbfa815be11c`，本任务不推送、合并、发布、安装或操作手机。

Java 测试冻结为独立本地提交 `fcea9e41`（只含四份新测试及格式清单三个新增测试条目；该提交仍保留原三个 Java 生产实现）。后续生产迁移提交以它为父提交，两个提交均从上述开工基线连续推进。

整域替换三份生产源，删除对应 Java，实现保持原 FQCN：

- [NetworkHealthMonitor.kt](../../app/src/main/java/com/deepseekharness/app/nethealth/NetworkHealthMonitor.kt)
- [NetworkHealthProbe.kt](../../app/src/main/java/com/deepseekharness/app/nethealth/NetworkHealthProbe.kt)
- [NetworkHealthPolicy.kt](../../app/src/main/java/com/deepseekharness/app/util/NetworkHealthPolicy.kt)

没有改 Gradle 工程图或建立空模块。没有改 A8-P1、S1b、T2，或 HarnessController、ConfigStore、NetworkStateMonitor、NetworkDiagnostics、NetworkSnapshot 实现；只调用其原有只读 API。

原 Java 输入 SHA-256：

| 原相对源名 | SHA-256 |
|---|---|
| `nethealth/NetworkHealthMonitor.java` | `804e5507647189ab38bc50326bb7edcd33b64cfd32105645cdd72086d1846bb2` |
| `nethealth/NetworkHealthProbe.java` | `3e35bdf4a1d2726bad04592639a99d5c7264cbc5c44d022172cf3dfb21de87f7` |
| `util/NetworkHealthPolicy.java` | `3df13d6f1d8e151daa0e21bf6001273d931fba54c0287e3d4d6b1ec365a776c6` |

Java 原源归档 `/tmp/nethealth-java-inputs-8211ba69/`；原/新混编目录 `/tmp/nethealth-before-8211ba69/`、`/tmp/nethealth-after-8211ba69/`。原既有测试身份与源码保持，新增测试先在原 Java 生产实现上通过，再运行于 Kotlin。三份新 Java 测试仅经过 google-java-format，工具证明 exact Java tokens preserved；Kotlin 消费者格式写入前后字节不变。

## 行为与异常对照

- 事件仍为 `DSHA_NET_HEALTH_V1` / `at|VERDICT|count|evidence`；解析仍接受 header 前缀，逐行跳过坏数据，保留最新 200 条。10 分钟去重保留旧时间、替换证据、累加计数，保留 Java 整数溢出语义。
- Signals 保留可赋 null 的直接可变字段；Event/Probe/Report 是普通 final 类，保留身份相等。Event 的 null evidence 归一为空串、count 下限 1；Report 三字段和 Event.verdict 仍可 null。静态方法保留原 JVM 名和描述符，无新增 checked throws 声明。
- Policy 的 append/recent/parse/totals 仍返回可变集合，包括空结果；浅拷贝保留 Event 身份，recent 是反转副本的可变 subList，totals 保留 encounter order 与 null 键。Monitor 的公开 events 则保留 `List.copyOf` 的不可变发布快照。
- 单例保持原 START 锁和 volatile 双检，start 完成才发布；单 daemon worker 名仍为 `dsha-nethealth`。心跳初延迟和 fixed-delay 都为 60 秒，网络等待 5 秒、后台等待 3 秒，共用 settle，取消不打断运行任务。迟到执行读取当时状态；不增加 generation、重试或额外调度行为。
- 保留 180 秒冻结阈值、eco/不活跃清 lastBeat、collect 成功后消费 pendingGap；启动中免报 FGS 缺失；断连才同步 query，服务查询异常按存在处理。OK 只更新结论，不替换快照、不落盘；异常事件先发布，再保存。
- 保存仍用私有 `nethealth/events.txt`，读取上限 256 KiB，UTF-8、坏输入降级；同目录临时文件 write / FD.sync / close / rename，失败清 temp，不回滚已发布内存。
- 手动检查连接时总探测；自动检查仍按原信号预算。`RuntimeException` 检查失败回调 null，回调在 worker 上、位于检测 catch 外且发布之后，只调用一次；execute 拒绝与回调异常保持外抛。
- 探测仍只发起一次显式 `InetAddress.getAllByName`，Future.get 等待 5 秒并 finally shutdownNow，超时只弃结果、不重试，迟到 DNS 不会发 HEAD。DNS 空数组原样得到空 error；等待中断仍返回 `InterruptedException`，保持原不重新置位的中断契约。
- HTTPS 仍为 `HEAD https://api.deepseek.com/`，Connection close、不跟随重定向、不缓存、不读写正文、不加凭据。连接/读取预算各 5 秒，任意正 HTTP 状态码（含 301/401/403）都算通；错误只记异常类名，disconnect 的异常仍外逸。两段耗时仍取 elapsedRealtime，包含原 finally 清理边界。
- 报告保留 `DSHA_NET_HEALTH_CHECK_V1` 字段顺序、枚举优先级与 DNS 脱敏。换行仍用 Java Pattern `\\R`，trim 仍限于 `<= U+0020`；没有用 Kotlin Unicode trim/lines 改变输入边界。网络结论没有接入 Web 重启、通知、凭据或自动修复。

## Java/Kotlin ABI 与可见性

完整原/新 `javap -p -s`、七项差异、32 项 ACC_FINAL 和编译器脚手架清单见 [ABI 冻结与对照](nethealth-kotlin-20261115-abi.md)。

原 8 类型、93 源级成员逐项核对，无缺失、原 private 放宽、字段 static/final/volatile 或原声明异常变化。32 个方法新增 ACC_FINAL 的 owner 在 Java 中均已 final，原可覆写边界变化为 0。

原主会话 `6fdfa3d4` 批准的例外仅为 **1 类型 + 6 成员，共 7 项**：

| 类别 | 名称 | 原可见性 | 新可见性 |
|---|---|---|---|
| 类型 | `NetworkHealthProbe` | package-private final | JVM public final / Kotlin internal |
| 字段 | `NetworkHealthProbe.HOST` | package-private static final | JVM public static final / Kotlin internal const |
| 方法 | `NetworkHealthProbe.run()` | package-private static | JVM public static / Kotlin internal，稳定 JvmName |
| 构造 | `NetworkHealthMonitor.Report(Verdict,String,String)` | package-private | JVM public / Kotlin internal |
| 方法 | `NetworkHealthPolicy.known(String,List)` | package-private static | JVM public static / Kotlin internal，稳定 JvmName |
| 方法 | `NetworkHealthPolicy.errorName(String)` | package-private static | JVM public static / Kotlin internal，稳定 JvmName |
| 方法 | `NetworkHealthPolicy.safeToken(String)` | package-private static | JVM public static / Kotlin internal，稳定 JvmName |

不宣称原 package-private 的异包负控仍成立。同包 Java 正控直接构造 Report、读 HOST、调用三个 helper；既有 Java 消费者和测试不需要改调用接口。`@JvmStatic`、`@JvmField`、const、null、集合与身份语义均有运行或字节码证据。

编译器新增结构分层计数：**原 8 类型内新增成员 17 项 + 3 个 Companion 类型本体 = 20 条外层差异记录**。这个 20 不是全部新增成员的总数，也不混计为上述七项原可见性差异。3 个 Companion 内另有 **16 个 public、非 synthetic 方法 + 3 个 public synthetic 构造桥**，此外有 3 个 private 构造与 3 个 private lambda helper；按类型的完整计数见 ABI 回执。尤其 `Verdict.getEntries(): kotlin.enums.EnumEntries` 是 Kotlin enum 自动生成的 **public static、非 synthetic** 接口；三处 Companion 类型和其 16 个普通公共方法也真实存在。访问原 private 状态/白名单的 access 方法及带 DefaultConstructorMarker 的构造桥为 synthetic，原同名 private 声明保留。完整 javap 公开这些细节，不声称“JVM 零新增接口”。

## 真实消费者与测试

[ForegroundActivity.java](../../app/src/main/java/com/deepseekharness/app/ForegroundActivity.java) 在恢复界面时 ensureStarted、全界面退后台时 onAppBackground；[NetworkHealthSheet.java](../../app/src/main/java/com/deepseekharness/app/ui/NetworkHealthSheet.java) 调用 events/lastVerdict/checkNow、读取 Report/Event 字段、调用 recent/serialize。两份生产调用方未改并进入真实 Java 编译。UI 原先自行 runOnUiThread 的边界不变。

原 [NetworkHealthPolicyTest.java](../../app/src/test/java/com/deepseekharness/app/util/NetworkHealthPolicyTest.java)、[NetworkEpochPolicyTest.java](../../app/src/test/java/com/deepseekharness/app/util/NetworkEpochPolicyTest.java)、[NetworkSnapshotTest.java](../../app/src/test/java/com/deepseekharness/app/util/NetworkSnapshotTest.java) 共 20 项保持。新增 36 项 Java 契约/负控及 3 项 Kotlin 消费者：

- [NetworkHealthMonitorTest.java](../../app/src/test/java/com/deepseekharness/app/nethealth/NetworkHealthMonitorTest.java)：真实 Monitor 调度、单例、线程/发布、取消/迟到、回调、坏持久化、不可写/rename 失败。
- [NetworkHealthProbeTest.java](../../app/src/test/java/com/deepseekharness/app/nethealth/NetworkHealthProbeTest.java)：真实 Probe 的 DNS/HEAD、超时/中断/迟到/错误/断开契约。
- [NetworkHealthPolicyContractTest.java](../../app/src/test/java/com/deepseekharness/app/util/NetworkHealthPolicyContractTest.java)：直接字段、null/NPE、身份、可变集合、Java 文本边界、溢出与持久化坏行。
- [NetworkHealthKotlinInteropTest.kt](../../app/src/test/java/com/deepseekharness/app/nethealth/NetworkHealthKotlinInteropTest.kt)：Kotlin 直接字段/静态调用、同包构造、nullable 与集合消费者。

Monitor 测试只替换共享协作者的只读边界；outer static 和 Companion.run 同时受影子控制，避免 Kotlin 迁移绕过假边界。Probe 测试没有替换 Probe 本身，只在测试 JVM 内替换 JDK DNS/URL，不访问外网；finally 先等待/回收 lookup 线程，再恢复全局 DNS/cache/URL 对象。相关夹具只用于定向 JDK17 主机验证，没有增加生产测试接缝。

## 初轮实际验证（b3602555）

| 检查 | 结果与边界 |
|---|---|
| 原生产混编 | Kotlin 2.2.10 + javac17；1060 Java / 26 Kotlin 编译输入成功，包含真实 main/standard/debug 源码、既有 AIDL stub 和仅用于编译的生成符号 |
| 原定稿测试 | 6 Java / 1 Kotlin 测试源编译，JUnitCore **59/59，0 failures** |
| 替换后混编 | 1057 Java / 29 Kotlin，同一套真实生产输入；6 Java / 1 Kotlin 测试源编译成功 |
| 替换后测试 | 同组 JUnitCore **59/59，0 failures** |
| javap | 原 8 类型 / 93 成员；仅列明的 7 项原可见性差异；32 新 ACC_FINAL 全部原 final owner |
| Java 全树格式 | **1813 files，0 need**；清单 missing/extra 都为 0，单批串行检查 |
| Kotlin 全树格式 | **33 files，0 need**；ktfmt 0.64，幂等 |
| core 职责边界 | **25 tests PASS** |
| 源码卫生 | 暂存最终源码后 **PASS** |
| 文档入口链接 | **56 documents / 1324 links / 0 warning / 0 error** |
| 暂存 diff 空白检查 | **PASS**；ABI 生成回执的末尾空行已清理 |
| Gradle 原/新入口 | 均仅用 dsha-gradle、共享缓存串行。两次都在 prepareBackupAssets 因缺 dsh-runtime.bin **exit1**，没有进入生产/测试编译，不写作 Gradle PASS |
| runtime input contract | 18 项运行，17 通过、1 因同一缺失固定输入报错，**exit1**；未改固定输入/清单来掩盖 |
| 本地只读源审 | 子代理 b7be0566 逐行对照三源和 tests、读取既有字节码，**APPROVE**；未独立重跑测试，不替代 main 独立逐行审/用户合入批准 |

主机混编脚本 `/tmp/nethealth-compile-8211ba69.py`，ABI 核验 `/tmp/nethealth-abi-8211ba69.py`；编译/测试参数与依赖路径分别保留在原/新目录的 args 与 receipt.json。主机编译复用缓存依赖/AAR classes，源码 XML 生成 R/BuildConfig/UI 语言编译符号，使用既有 AIDL stub；不冒充 AAPT 资源处理或完整 Gradle 固定 classpath。Robolectric 使用 SDK34、离线框架、本机 conscrypt OFF。它们不替代 CI 默认环境或完整 Gradle 单测。

原阶段夹具调试曾出现测试扫描时点、未初始化 JDK 静态缓存、Robolectric 实例化边界导致的红测，全部在冻结原 Java 定稿基线前修正；未修改 Kotlin 测试期望来放行迁移。基线成功日志 `/tmp/nethealth-before-r5-8211ba69.log`，迁移成功日志 `/tmp/nethealth-after-8211ba69.log`；对应 junit.log 明确各为 59 项。两次 Gradle 失败和输入契约失败分别保留在 `/tmp/nethealth-gradle-before-8211ba69.log`、`/tmp/nethealth-gradle-after-8211ba69.log`、`/tmp/nethealth-runtime-input-contract-8211ba69.log`。

未验：完整 Gradle/CI 单测、真实资源编译、Lint、APK/签名、安装/真机、多系统/外部服务矩阵；未做真实外网/TLS 探测。

## 格式清单、受管输入与交接

[Java 格式清单](../../tools/java-format-files.json) 的窗口已获原主会话批准，严格 3 删 + 3 增：移除原三个生产 Java 路径，新增上述 MonitorTest、ProbeTest、PolicyContractTest；其余条目相对顺序、政策字段与原测试条目不动。Kotlin 自动受全树门禁覆盖。

初轮在 b3602555 读取 [managed-runtime-inputs.json](../../app/src/main/assets/managed-runtime-inputs.json) 和 [输入枚举规则](../../tools/runtime_input_contract.py)，当时 `launcher_paths` 返回 515 个 key，其中本域 0 项：三个旧 Java 与新 Kotlin 路径均不在 launcherSources，nethealth 与 util/NetworkHealthPolicy 也不受 launcherTrees 覆盖。**本任务生产源码是 3 删 3 增，受管源码 key 增删为 0。** A8-P1 后布局生成器纳管使总 key 变为 517，本域仍为 0，见下面的新树实测。不改受管 JSON/proof/固定输入/descriptor；完整摘要由 main 串行收口，本回执不声称替 main 重算了 descriptor。

回退只针对本轮最终三提交栈、工作树干净且未叠加新工作时：先 `git revert HEAD` 撤回最终文档收口；再 `git revert 8920acc41881cc31aafe6cf711b64ba6d0ad8635` 撤回生产迁移，恢复三 Java、三个生产格式条目并删除三 Kotlin/迁移回执，保留可在 Java 上运行的基线测试；需要全部撤回时，最后 `git revert f1ad2bc03003694466741a31bac5f0e4981c6888` 撤回测试冻结。前轮历史 SHA 不用于本轮按字面回退。无设置、数据格式、包名或运行时数据迁移，无需清数据；main 合并后的回退由 main 对已合入提交及身份配套安排。

## A8-P1 后复验（e368e6d8 / 79fa4772 / f0809ddc）

本节验证执行绑定 `f0809ddc` 的生产/测试源码；Java 对照从 `79fa4772` 精确导出原三 Java 与同组测试，工具/依赖一并按新树处理。两边真实生产编译输入仍分别是 1060 Java + 26 Kotlin、1057 Java + 29 Kotlin，测试均为 6 Java + 1 Kotlin。源码枚举改由 A8-P1 的 SourceLayout 提供；没有把旧 app-only 枚举器输出当新布局证据。三份生产 Kotlin 与 rebase 前 `67428782` 字节逐一相等，7 个测试源的身份与内容均保持。

| 新树检查 | 实际结果 |
|---|---|
| `source_layout.py --verify-compile-head` | PASS，编译输入与执行时 HEAD 字节匹配 |
| Java / Kotlin 格式 | Java **1813/1813**，Kotlin **33**，0 need |
| core / architecture | **25 tests PASS** / PASS |
| A8 测试计划 | **exit0**；781 测试源、5236 声明、593 资源；本域精确 59 声明 |
| Java 原实现与 Kotlin 替换 | 两边真实生产/测试混编均 exit0；JUnit 各 **59/59、0 failures** |
| 原始执行身份 | 记录实际 `(classname, method)`，前后各 59、无缺失/新增/重复，并与新计划的 59 声明逐项相等 |
| ABI 重取 | 原 8 类型 / 93 成员、7 项原可见性例外、32 项 final owner 说明保持；新旧两侧完整 javap 与初轮逐字节相等 |
| Companion 追加盘点 | `javap -p -v`：16 public 非 synthetic 方法、3 synthetic 构造桥；另含 3 private 构造和 3 private helper |
| 固定输入恢复与输入契约 | 先按当前 pin 核验 main 已验收树，仅恢复/验证 **10 个 ignored 资产**；不复制或改写 tracked proof/清单。恢复后 **18/18 PASS** |
| Gradle 运行输入检查 | `verifyRuntimeDescriptorInputs` 实际 PASS：103 安装项、205 资产、517 启动输入；本域 key 仍无增删 |
| Gradle Kotlin 任务 | 组合命令中实际执行 `compileStandardDebugKotlin`；随后单独 `--offline` exit0、BUILD SUCCESSFUL（编译任务 UP-TO-DATE），不把复用写成第二次新鲜编译 |
| Gradle 定向测试 | exit1 于 `processStandardDebugResources`：x86_64 AAPT2 在 aarch64 报 `bad machine`，未执行到 Test；不记为 Gradle 单测通过 |
| 卫生 / 文档 | PASS / **56 docs、1328 links、0 warning、0 error** |

本轮证据目录：`/tmp/nethealth-p1-before-r3-8211ba69/`、`/tmp/nethealth-p1-after-8211ba69/`；基线生产编译日志 `/tmp/nethealth-p1-before-r3-8211ba69.log`，定稿基线执行 `/tmp/nethealth-p1-before-r5-8211ba69.log`，替换执行 `/tmp/nethealth-p1-after-8211ba69.log`；实际身份对照 `/tmp/nethealth-p1-identities-8211ba69.json`、Companion flags `/tmp/nethealth-p1-companion-8211ba69.json`。原 Java 输入 SHA-256 表和初轮旧日志保留，不覆盖成当前验证结果。

新版外部观测器仅在测试 JVM 里记录 JUnit 描述，不替换生产或测试逻辑。源归档最初遗漏两个生成器 Python helper、观测器初次长 classpath 参数未用 args 文件、第一次成功观测后未显式退出 JVM，均是仓库外验证脚本问题：按同一基线补齐工具、改用参数文件及与 JUnitCore.main 等价的退出后，定稿进程 exit0；没有改产品代码、测试期望或身份。所有真正编译/运行均使用共享构建锁串行；无后台任务残留后才结束本轮。

A8-P1 全量入口的 plan 通过只证明计划可构建；本域手工 59 项与主会话已通过的 P1 CI 不冒充本候选完整 full 运行。完整 Gradle/CI 单测、Lint/APK/设备验收仍由后续 main 集成承担。本节仅保留 e368e6d8 中间轮证据；SAF/transfer 已合入后的最终轮结果另列如下。

## SAF/transfer 合入后最终复验（823fd4c8 / f1ad2bc0 / 8920acc4）

最终 main 已包含 SAF #1、transfer #2 及其测试夹具修复。两提交无冲突重基线为 `f1ad2bc03003694466741a31bac5f0e4981c6888`（Java 原实现 + 本域测试冻结）→ `8920acc41881cc31aafe6cf711b64ba6d0ad8635`（Kotlin 替换与前轮文档）；以下新鲜执行均绑定该基线和源提交。最终只追加两份回执收口，不改 3 个生产和 7 个相关测试源；三生产、七测试与 `9945bd6b` 及主审源快照均逐字节相等。当前回退入口使用本节新提交，不使用前轮 SHA。

| 最终树检查 | 实际结果 |
|---|---|
| SourceLayout / HEAD | PASS；共享布局枚举与当前被编译源字节相符 |
| 原 Java 实现真实混编 | 从 `f1ad2bc0` 精确导出；**1037 Java + 49 Kotlin**，包含当前 SAF/transfer；6 Java + 1 Kotlin 本域测试源；全部编译 exit0 |
| 替换后真实混编 | **1034 Java + 52 Kotlin**，同样包含当前 SAF/transfer；6 Java + 1 Kotlin 本域测试源；全部编译 exit0 |
| 前后定向执行 | Java/Kotlin 两侧各 **59/59，0 failure、0 ignore**；不是把两次累加为 118 个覆盖 |
| 精确身份对照 | 前后 59 个 `(classname, method)` 逐项相同，无新增/缺失/重复，且与新计划本域 59 声明完全一致 |
| JVM ABI | 原 8 类型/93 成员、7 例外，32 个新增 ACC_FINAL 均为原 final owner；本轮两侧完整 javap 与前轮逐字节相等 |
| 真实 Gradle ABI | 从本树真实 Kotlin Compile 产物读取 11 类型（含 3 Companion），与本轮直接编译 javap 逐字节相等；原契约和 Companion 分层计数保持 |
| Java 格式 | **1798/1798 文件、57 批、0 need**；manifest missing=0/extra=0；本域仍仅 3 删/3 增，其他条目相对顺序与政策字段不变 |
| Kotlin 格式 | **58 文件、14 批、0 need** |
| core / architecture | **25/25 PASS** / PASS |
| 运行时输入契约 | **18/18 PASS**；103 安装项、205 资产、517 启动输入，本域 keys=0 |
| 新 full 静态计划 | **exit0**：791 测试源、5296 声明、593 资源，本域仍 59 声明；不是 full 执行或 baseline matched 的证明 |
| 卫生 / 文档 | PASS / **56 documents、1328 links、0 warning、0 error**；最终提交前再次核对 |
| 真实 Gradle 生产任务 | `dsha-gradle :app:compileStandardDebugKotlin :app:compileStandardDebugJavaWithJavac :app:verifyRuntimeDescriptorInputs`（另带仓库外只读测试图导出任务）**BUILD SUCCESSFUL**；29 任务、17 executed/12 up-to-date，生产 Kotlin 与 Java Compile 本轮均实际执行 |
| Gradle 定向 Test 尝试 | 指定同域过滤实际运行，**exit1** 于 `processStandardDebugResources`：本机 x86_64 AAPT2 `bad machine`；没有运行到 Test，不记作 Gradle Test 成功 |
| 实际 Android 测试编译图 | 只读导出本树 Gradle Java/Kotlin 测试源码、依赖和 `--system` jdkImage 后，**全部 785 Java + 6 Kotlin 测试源直接编译 exit0**；编译图未添加 SDK34/android-all/宿主 JDK 模块 |
| 实际解析运行依赖中的本域测试 | 从上述真实测试图产物、本树实际生产输出和已解析运行依赖执行同身份 **59/59**，0 failure/ignore；仍保留本机 SDK34 runtime、Conscrypt OFF/opens，属于直接 JUnit 执行，不是 Gradle Test/full proof |

Gradle 生产编译日志中的 SAF/transfer/更新域既有弃用、nullable 和 `VAL_REASSIGNMENT` 抑制警告保持原状，没有修共享实现；没有 nethealth 新编译错误。本域 Kotlin、Java 真实调用方和全部测试图编译已经实际成功，不再写为“生产/测试源码未编译”。受限项准确为 Gradle Test/资源链接、CI full/正式 `unit-run` proof、Lint/APK/签名/设备矩阵，均未由本轮证明。

真实 Android 测试图只读导出任务来自仓库外 `/tmp/nethealth-final-test-inputs-8211ba69.init.gradle`，没有修改 Gradle 工程或过滤设置。AAPT2 未生成的 resource-linked R.jar 和 app classes JAR 只在仓库外直接编译消费者中映射为**同树真实 Gradle 已生成的 compile R.jar 与已编译生产 Java/Kotlin 目录**；测试 Kotlin 输出映射到本次直接编译目录。映射逐项进入 `test-compile-receipt.json`，没有伪造 R/BuildConfig 或用未声明 SDK/JDK 模块补齐测试编译图，不能据此声称 AAPT2/资源打包通过。

最终机器证据根为 `/tmp/nethealth-final-8211ba69/`：`binding.json` 绑定完整 base、Java 冻结和被测生产源 HEAD；`before-compile.log`、`after-compile.log`、`before/`、`after/` 保存真实参数与 59 项日志；`identities.json` 保存精确身份对照；`abi-result.json`、`companion.json`、`gradle-abi.json` 保存字节码核验；`gates.json` 和各门禁日志保存最终格式/边界/输入结果。`gradle-production.log`、`gradle-test.log` 保留成功编译与资源阻断的不同结果；`gradle-test-inputs.json`、`android-tests/{test-compile-receipt.json,receipt.json,identities.tsv}` 绑定实际测试图的 785/6 编译与第三次同身份 59 执行。原 Java SHA-256 表、初轮和 e368 旧日志继续保留。

相对最终 base 的 [受管清单](../../app/src/main/assets/managed-runtime-inputs.json)、[descriptor](../../app/src/main/assets/runtime-descriptor.json)、[固定归档证明](../../app/src/main/assets/dsh-runtime.inputs.json)、[源码布局](../../ci/source-layout.json) 均逐字节未变。本域受管 key 仍增删 0，累计 Java 格式条目精确 3 删/3 增，记录在 `input-delta.json`。真实 Gradle 校验读到 main 已提交的 `63adce0a3df6f0c46494dc6adf030f07f80724557f139b3598755caa1607e2ad`；它是 SAF/main 的现有身份，本任务没有自行写入新 descriptor、proof 或手工证明。最终合并时身份收口继续由 main 执行。
