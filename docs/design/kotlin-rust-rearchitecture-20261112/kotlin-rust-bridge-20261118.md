# Rust/JNI 调用桥 Kotlin 等价迁移子批次（2026-11-18）

本批基线为 `2cc4e646f8ab232eef0070c9d844b806241caf6e`，独立分支 `task/kotlin-rust-bridge`。只迁移宿主桥的实现语言，Rust 核心、JNI ABI v1、11 个导出符号、依赖锁和执行能力边界不变。阶段提交 `8c3a9fde` 保存了生产迁移和两组黄金测试；最终提交还包含主机混合语言探针与本文。

## 范围与冻结源码

| Java 基线 | 行数 | SHA-256 | 处理 |
|---|---:|---|---|
| RustArchiveCodec.java | 285 | `6b2afcce1524bd06de8fbfb502e534e0469c02b0378618aa1f65905d27824a55` | 迁移为 [RustArchiveCodec.kt](../../../app/src/main/java/com/deepseekharness/app/util/RustArchiveCodec.kt) |
| RustBackends.java | 120 | `ab7c5ae8e879f1e684db5eb972a809f74a32704639b2126e39145487c615f5b4` | 迁移为 [RustBackends.kt](../../../app/src/main/java/com/deepseekharness/app/util/RustBackends.kt) |
| RustDigest.java | 405 | `eafe060baf893c9d38830bddf6fd96db980ff76daab198872c9ba7871172712e` | 迁移为 [RustDigest.kt](../../../app/src/main/java/com/deepseekharness/app/util/RustDigest.kt) |
| RustGzipInputStream.java | 297 | `57e1ca138c2c204f8227f71c61e484f5a71a80901ff9a64fa132c6d4ea2f45b2` | 迁移为 [RustGzipInputStream.kt](../../../app/src/main/java/com/deepseekharness/app/util/RustGzipInputStream.kt) |
| RustGzipOutputStream.java | 205 | `6eb119badd30ec16977d81d09492ccecd8bfbba88f5c8ade7fe75f1ffc8f44ec` | 迁移为 [RustGzipOutputStream.kt](../../../app/src/main/java/com/deepseekharness/app/util/RustGzipOutputStream.kt) |
| [RustNative.java](../../../app/src/main/java/com/deepseekharness/app/util/RustNative.java) | 161 | `942faa0c43594216223c0c62db6e88613b415ccddc6a0b5d2b8b2677623d11d3` | 原字节保留 |

实际迁移五类，共 1,312 行 Java 基线。没有更改其它 util、备份算法、DSH 依赖/补丁或 Rust 目录。

静态交接技能 `dsha-kotlin-equivalence`、`dsha-rust-jni-boundary` 的 scope 脚本在本工作树均返回 `applicable:true`；它们仅作开发指导，没有安装或启用技能。

## JNI 薄 Java 边界为何保留

[RustNative.java](../../../app/src/main/java/com/deepseekharness/app/util/RustNative.java) 包含 11 个 `public static native` 声明，以及 `ensureLoaded`、`loadFromPath`、`status` 的 `static synchronized` 方法。声明 owner、static/native 标记、参数/返回描述符决定 JNI 符号；ACC_STATIC + ACC_SYNCHRONIZED 决定锁是 `RustNative.class`。包可见的 `loadFromPath` 又是显式测试接缝。

Kotlin object/companion 会增加对象、成员及访问桥，不能仅凭同名源码断言 JNI/监视器形态完全相同。因此本批保留这 161 行 Java 的全部字节；ABI 版本、库名、加载失败粘滞、不重试生产加载、已注册库后拒绝换库的状态机均不动。Rust 仍只承担归档、摘要及只读计划校验，不引入 jni-rs/UniFFI、RegisterNatives 或执行入口。

## Java API 与行为等价

以本轮六类 Java 小闭包新生成的 class 为基线，对五类 Kotlin + 原 Java native 层重新编译，并逐类保存 `javap -p -s -v`。没有拿别的工作树旧 class 当候选结果。

保留项：

- 所有原 Java 公有方法/字段的调用名、参数/返回 JVM descriptor 与 static/native/synchronized/volatile/final 标记。
- final 工具类的 private 无参构造器，两个嵌套只读结果的原 private 构造器；`Selection`、`Result` 保持对象身份语义，没有 data class/equals/hashCode 改写。
- Kotlin 默认会给 `@JvmStatic` 桥加 ACC_FINAL；本批显式 `open fun` 并局部抑制 final-owner 诊断，保留原 `public static` 非 final 形态。final 类内原非 final 实例方法也显式保留。
- `RustBackends.enabled` 仍是 private static volatile 字段，默认 false，写入/读取点不变。
- `@Throws(IOException::class)` 保留原构造器与流操作的 checked exception 属性；`available()` 原本无 throws，本批仍无。
- Java 显式 null 检查使用可空参数并保留原检查顺序与消息：closed/finished 优先于 bytes null；target null 优先于 level；零长度优先于 EOF。编译器辅助 NPE 文本不作为跨 JVM 版本契约。
- direct buffer 容量、slice 与显式范围、整数转换/溢出拒绝、digest 防写越界哨兵、FileChannel 绝对读及借用所有权均不改变。
- pack 工厂只借用目标流；finish 消费 native 句柄后先标记本地完成再写尾部，目标异常后不再次 finish/重放；close 保留首个 IOException、关闭最多一次。
- unpack 构造期推进头的错误时机、失败清理/抑制异常、pending 压缩字节、尾随数据拒绝、close 幂等和 close 后的校验优先级保持。

经主代理批准，下列原包可见接缝因 Kotlin internal 的 JVM 形态扩大为 public；原名字/descriptor/static（以及常量的 final）不变：

| 接缝 | descriptor |
|---|---|
| `RustArchiveCodec.RUST_ERROR_PREFIX` | `Ljava/lang/String;` |
| `RustDigest.digestBytes` | `(I)I` |
| `RustDigest.shouldRedoOnJvm` | `(Lcom/deepseekharness/app/util/RustDigest$Failure;)Z` |
| `RustDigest.settleRustResult` | `(Lcom/deepseekharness/app/util/RustDigest$Result;Ljava/nio/channels/FileChannel;JJI)Lcom/deepseekharness/app/util/RustDigest$Result;` |
| `RustDigest.Result.simulatedFailure` | `(Lcom/deepseekharness/app/util/RustDigest$Failure;I)Lcom/deepseekharness/app/util/RustDigest$Result;` |

方法用 `@JvmName` 避免 internal 模块后缀改变既有 Java 调用名。没有将这种可见性变化扩散到 JNI、装载、权限或执行入口。

Kotlin 新增的元数据/辅助形态如实保留：四个外层类的 Companion（OutputStream 桥没有 Companion）、Selection/Result 的 Companion、三个 enum 的 `getEntries()`、私有状态的 synthetic 访问器，以及私有嵌套构造器的 `DefaultConstructorMarker` synthetic 重载。外层需要访问嵌套 private 构造器时，使用 `@JvmSynthetic internal` 工厂/解释桥；这些不是新 native 方法，Java 源码不应调用。Class.getDeclared* 的完整成员集合因此不是逐字节相同，不能称“完全相同 class 文件”。

## 黄金测试与真实库验证

新增 [RustBridgeApiGoldenTest.java](../../../app/src/test/java/com/deepseekharness/app/util/RustBridgeApiGoldenTest.java) 8 项与 [RustBridgeNativeGoldenTest.java](../../../app/src/test/java/com/deepseekharness/app/util/RustBridgeNativeGoldenTest.java) 6 项。它们先在 Java 基线上编译/验证，再对候选运行；原测试未减弱、删断言或增加跳过。Native 黄金缺库直接失败，没有 assumption。

使用主代理当轮构建的 GNU ARM64 `libdsha_rs.so`：348,736 bytes，SHA-256 `04f07e7a2adb64e034ba66dfce2467ba369af11110f876b10b6a5aef59156bed`。该库来源仍绑定 `2cc4e646` 基线；Rust 核心未改，所以适用于本子批 JNI 行为对拍，但不是最终集成提交的发布产物证明。最终集成需重构产物与 receipt。

| 本轮执行 | 基线 | 候选 | 证明范围 |
|---|---:|---:|---|
| API 黄金 + 原 ArchiveCodec/Backends/Digest 纯 JVM 回归 | 33/33 | 33/33 | API、错误映射、只读摘要与后端选择 |
| 新 native 黄金 | 6/6 | 6/6 | 真库流关闭/构造失败/finish 异常不重放 |
| 最终 API 黄金 + native 黄金 + 原 RustDigestDifferential | 24/24 | 24/24 | 8 + 6 + 10；含原 fd/direct-buffer 差分 |
| 原 host-jvm 三探针 | 未在本子批重新执行 | 66/66 | digest + PlanView + archive 固定检查集合 |

各行存在重复用例，不相加为“总测试数”。Native/JVM 均启用 `-Xcheck:jni`、`dsha.rust.required=true`，没有把缺库 skip 计为通过。host probe 固定 66 项，PlanView 333 语料/349 次 native 调用；archive 19 检查/31 次 native 调用；seed=20261113，随机视图 256。新增黄金 6 项与最终 24 项均为 0 failures/errors/assumptions。

主机编译使用 JDK17、仓库 KGP2.2.10：javac 上限 128 MiB、Kotlin 编译器 256 MiB、JUnit JVM 128 MiB，顺序执行，没有启动 Gradle。完整原 [RustArchiveDifferentialTest.java](../../../app/src/test/java/com/deepseekharness/app/util/RustArchiveDifferentialTest.java) 依赖完整备份闭包，留给主代理集成 Gradle 执行；本批不能以 host probe 替代其“已运行”记录。

## 主机夹具适配

[host-jvm-differential.py](../../../tools/rust/host-jvm-differential.py) 的硬编码 Java 路径改为通过现有 SourceLayout 按逻辑类名解析。缺失、双 Java/Kotlin 实现都拒绝；Java-only 闭包仍走 javac。混合闭包新增显式 `--kotlin-libs DIR`，要求编译器版本与 settings 的固定 KGP 一致，逐个 JAR 对照 Gradle verification metadata 的 SHA-256；不联网、不更新锁、不修改缓存。探针输出新增语言、编译器 JAR 摘要与本轮生产源 SHA。

[test_host_jvm_sources.py](../../../tools/rust/test_host_jvm_sources.py) 的 7 项 Python 用例通过：双语发现、双实现/缺失拒绝、缺编译器拒绝、Java-only 路径、命名空间 XML 摘要读取、编译器篡改/版本/多候选负控。

[check-rust-boundaries.py](../../../tools/check-rust-boundaries.py) 只扫描 Rust 源码、锁与产物，没有本批 Java 路径假设，因此保持原字节。实际结果为 7 Rust 源、11 符号、16 依赖、4 构建输入 PASS；未放宽 Rust 门禁。

## 主代理集成清单与精确命令

本批不修改全仓共享清单/最终 descriptor/证明。主代理需要统一：

1. Java adoption 清单移除上述五个 `.java`，加入两个新 GoldenTest Java。
2. host-tests manifest 登记新 Python 专用测试；若 CI 运行 host-jvm 脚本，为其传入经锁核验的 Kotlin 编译器闭包。
3. 核对 launcher logical mapping 将同名 Java→Kotlin 变更纳入单源登记；没有擅改源码布局或 descriptor。
4. 集成提交后生成最终 runtime descriptor/受管证明、Rust receipt，并跑完整编译及原/新回归。

```bash
# 基线与候选同一过滤器；由主代理统一占用 Gradle 槽位。
dsha-gradle :app:testStandardDebugUnitTest \
  --tests '*RustArchiveCodecTest' --tests '*RustBackendsTest' \
  --tests '*RustDigestTest' --tests '*RustDigestDifferentialTest' \
  --tests '*RustArchiveDifferentialTest' --tests '*RustBridgeApiGoldenTest' \
  --tests '*RustBridgeNativeGoldenTest' \
  -Pdsha.rust.hostLib=<本轮宿主库绝对路径> -Pdsha.rust.required=true

python3 tools/check-rust-boundaries.py
python3 tools/rust/test_host_jvm_sources.py
python3 tools/rust/host-jvm-differential.py \
  --lib <本轮宿主库绝对路径> --kotlin-libs <已核验编译器JAR目录> \
  --report <本轮回执JSON>
```

生产最小闭包为六个 Rust 桥类（五 Kotlin + 原 RustNative Java），只依赖 JDK 和 Kotlin stdlib。完整 host probe 再含 PlanViewCodec、PlanViewValidator、StrictGzipInputStream、BackupLimits 及三个原探针。需 javap 的类为六桥类，以及 `RustBackends$Selection/$SelectionReason`、`RustDigest$Result/$Backend/$Failure`、`RustNative$Status`；额外检查 Kotlin Companion/synthetic 增量。

## 格式与本机回执

- `format-kotlin-tree.py --check`：127 个 owned Kotlin 文件、20 批通过；本批格式以锁定 ktfmt0.64 写入且幂等。
- 新增两 Java 黄金测试显式格式门禁通过，google-java-format1.22.0 验证 token 不变。
- `format-java-tree.py --check`：按预期 exit2，`FORMAT_MANIFEST_INCOMPLETE`，missing2（新黄金）、extra5（已迁移Java）。清单由主代理统一维护，本批没有篡改或跳过门禁。
- 工作树忽略目录 `app/build/rust-bridge-migration/` 保存 baseline/candidate 源摘要、class、javap、API diff、编译/JUnit/host探针与格式日志；主代理可读取核验，但这些本机日志不提交到源码仓库。

未执行 Gradle、APK 构建/安装、Android JNI 真机、4/16KiB 设备验证、发布或 push。GNU host JNI 成功不冒称 Android 上已加载。

## 干净 CI 编译器闭包续修

初版 `c287634f` 要求显式 `--kotlin-libs`，但现有 Rust differential 工作流未准备该目录，干净 runner 会失败。续修新增 [prepare-kotlin-host.py](../../../tools/rust/prepare-kotlin-host.py)，与 host 探针共用校验，不通过 Gradle 解析整个 APK。

- 四个主 Kotlin JAR 版本取自 settings 的固定 `2.2.10`；compiler POM 对应的 reflect `1.6.10`、coroutines `1.8.0` 以及 JVM 注解 `13.0` 组成七 JAR 闭包。未来 KGP 变化必须明确审阅闭包，不静默复用旧辅助版本。
- 按 group/name/version/filename 精确读取 Gradle verification metadata 中唯一 SHA-256。固定下载源为 `https://repo.maven.apache.org/maven2/`；拒绝 HTTP、非官方域名、凭据/查询参数与外域重定向。逐个验证有界下载内容后独占创建目标，不覆盖已存在坏文件。
- `--check` 只读校验，不创建目录或联网。已有坏 JAR、符号链接、重复版本拒绝并保留原件；部分输出遇中断后也留作证据，后续调用拒绝而非悄悄替换。
- [_node-rust.yml](../../../.github/workflows/_node-rust.yml) differential 顺序为专用测试 → prepare → check → host 构建 → `host-jvm-differential.py --kotlin-libs out/kotlin-host`。默认本机目录为 `app/build/kotlin-host`，缺输入明确打印准备命令；消费者不暗中联网。
- 已核对现有可执行调用：CI 只有该共享节点；主 `run-host-tests.py` 没有直接 host-jvm 调用。现有 RustGraph 的 9 项测试通过。
- 原子目录测试保留 [test_host_jvm_sources.py](../../../tools/rust/test_host_jvm_sources.py)，新增可被主测试发现的根入口 [test-rust-host-jvm-sources.py](../../../tools/test-rust-host-jvm-sources.py)。主代理应登记根入口，移除误登记的子目录入口；本批不改共享 manifest。

续修专用测试共 14 项通过，含下载流/内容核验、坏原件保留、只读 check、篡改/外域 redirect、同坐标/版本拒绝与 CI 顺序。实际空目录官方下载及紧随的 `--check` 回执在本机 `app/build/rust-bridge-migration/kotlin-host-clean-{download,check}.json`，只作为本轮获取字节证据，不冒充远端 CI 执行。

```bash
python3 tools/test-rust-host-jvm-sources.py
python3 tools/rust/prepare-kotlin-host.py --dir out/kotlin-host
python3 tools/rust/prepare-kotlin-host.py --dir out/kotlin-host --check
python3 tools/rust/host-jvm-differential.py --lib <本轮host.so> \
  --kotlin-libs out/kotlin-host --report out/rust-differential.json
```
