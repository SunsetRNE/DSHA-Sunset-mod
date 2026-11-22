# plugins 整域 Kotlin 迁移回执（2026-11-16）

状态：**已交付本地提交，待主会话集成**。本页记录 `app/src/main/java/com/deepseekharness/app/plugins/`
11 个 Java 类的整域 Kotlin 迁移：改了什么、如何验证、哪些项未验证、如何回退。

- 分支：`migrate/plugins-kotlin`
- 基线：`bf491c77`（origin/main，build368 前一提交）
- 交付提交：`5bd453cd`（单提交，11 Java 删除 + 11 Kotlin 新增 + Java 格式清单 11 条删除）
- 依据：[ADR-0004 决定一](../../adr/0004-kotlin-rust-direction.md)、[Kotlin 工具链](../../kotlin-toolchain.md)

## 1. 迁移清单

| 类（同包同名） | 原 Java 行 | 迁移后 Kotlin 行 | 职责 |
|---|---:|---:|---|
| `PluginIdentity` | 30 | 29 | 插件身份归一化与历史别名 |
| `PluginSourceIdentity` | 84 | 74 | 已观察数据根/运行根/Web 代次身份 |
| `PluginExitProofGate` | 96 | 145 | A5 退出证明消费者（record Binding/Evidence） |
| `PluginChangeCoalescer` | 106 | 104 | 文件失效信号状态合并 |
| `PluginOperationResult` | 197 | 291 | 命令结果一次性解释（record Detail/Receipt、Failure） |
| `PluginOperationQueue` | 449 | 431 | 操作排队/取消/进度/历史模型 |
| `PluginRegistry` | 480 | 527 | 目录版本快照、查询票据、mutation 票据门 |
| `PluginRuntimeProjection` | 400 | 512 | Web 运行证据只读投影 |
| `PluginBackendAdapter` | 316 | 353 | 宿主只读来源适配（NOFOLLOW/身份核验） |
| `PluginChangeDetector` | 824 | 850 | 文件观察/规则匹配/扫描预算 |
| `PluginManagementEngine` | 955 | 975 | 页面/查询/调度/取消/维护边界协调 |
| 合计 | 3937 | 4291 | |

迁移后 `app/src/main/java` 为 967 Java + 63 Kotlin；`app/src/test/java` 785 个 Java 测试源
（未新增/未改测试）。`tools/java-format-files.json` 同步删除 11 个已迁移 Java 条目
（1798 → 1787，Java 格式门禁通过）。

本迁移只换实现语言：**不改 writer 锁、插件启用政策、guest 执行端、错误码与数据格式**；
未新增/删除依赖，未改 build 脚本、descriptor、受管证明或 `ci/source-layout.json`。

## 2. Java 可见契约

Java 调用点全部保持原形态（`compileStandardDebugJavaWithJavac` 通过），并按整域规则保留：

- **字段**：Java 读取的 public final 字段用 `@JvmField` 保留同名字段；private 字段仍私有。
- **静态成员**：companion + `@JvmStatic`；常量用 `const val`（Java 侧仍是 `PluginX.CONST`）。
- **Java record**：`PluginExitProofGate.Binding/Evidence`、`PluginOperationResult.Detail/Receipt`
  用 `@JvmRecord` 保留 Record 父类、组件名/顺序与 record 访问器；归一化仍在 canonical
  constructor（`VAL_REASSIGNMENT` 局部抑制，Kotlin 固定 2.2.10）；`equals/hashCode/toString`
  显式覆盖为原 Java record 语义（`Objects.hash` 与 `Type[a=..., b=...]` 文本）。
- **异常**：原 `throws IOException/Exception/Failure` 用 `@Throws` 保留，Java 检查异常
  catch 可编译；`Failure` 仍是 `IOException` 子类，`exitUnknown` 语义不变。
- **同步**：原 `synchronized` 方法用 `@Synchronized`（同一监视器）；原 `synchronized(lock)`
  块原样保留；`volatile` 字段用 `@Volatile`。
- **空值/集合**：原可空参数保持可空；`Objects.requireNonNull` 保留；`Collections.unmodifiableList`/
  `ArrayList`/`LinkedHashMap`/`ArrayDeque` 等类型与顺序不变。`PluginOperationQueue` 的
  `operationId`/`phase` 在原 Java 里允许传 null 并返回 false（测试显式覆盖），Kotlin 保持可空
  参数与 false 语义，不用 `!!` 改变失败时机。
- **可见性放宽**：Kotlin 外层类不能访问嵌套类 private 构造，少量构造器改为
  `internal`（字节码 public，测试/主源可用），属放宽不属变窄；`Coordinator` 等原包级
  方法用 `internal` + `@JvmName` 保持 Java 可调用名。Kotlin 生成物（Companion、枚举
  `$ENTRIES`、record `copy`、`DefaultConstructorMarker` 构造、lambda 桥）是新增字节码，
  不承诺为新业务 API。

## 3. 行为冻结与验证

本机 aarch64 没有可运行的 aapt2：`processStandardDebugResources` 报
`AAPT2 ... bad machine / proroot-ldso`，Gradle 单测整链无法启动（与
[Kotlin 工具链 §7](../../kotlin-toolchain.md) 记录一致）。因此用**仓库外等价 harness** 冻结行为：
固定 Kotlin 2.2.10 + JDK17 读取真实混合源（`-Xjava-source-roots`）+ `javac` 编译真实
Java 生产/测试源 + JUnit4；只为资源引用用当前 Gradle 生成的 `R.jar`/`BuildConfig`/
`UiMessages`。不是 AAPT/APK/Gradle 完整构建。

| 项 | 命令（摘要） | 结果 |
|---|---|---|
| 基线行为冻结 | harness：5 个纯逻辑测试类 × 迁移前树 | **101 tests / 0 failures** |
| 迁移后行为 | harness：同一组测试 × 交付树 | **101 tests / 0 failures** |
| Kotlin 编译 | `dsha-gradle :app:compileStandardDebugKotlin -x verifyRuntimeDescriptorInputs -x prepareRuntimeDescriptor` | **PASS**（exit 0） |
| Java 调用点编译 | `dsha-gradle :app:compileStandardDebugJavaWithJavac -x verifyRuntimeDescriptorInputs -x prepareRuntimeDescriptor` | **PASS**（exit 0） |
| 测试源编译 | harness `javac` 全部 785 个测试源（含 5 个 Robolectric 测试类与所有 Java 调用点） | **PASS** |
| JVM 成员对照 | `javap -p -s` 迁移前后 33 个类型（11 类 + 嵌套类型/枚举） | 非 private 成员 **零缺失、零 descriptor 变化**；`static`/字段 `final` 一致 |
| record 语义 | 真实 classpath 运行 `Detail/Receipt/Binding` | `toString=Type[a=...]`、`hashCode=Objects.hash`、非法 token→`unknown` |
| Kotlin 格式 | `python3 tools/format-kotlin-tree.py --check` | **PASS 69 files / 0 need** |
| Java 格式 | `python3 tools/format-java-tree.py --check` | **PASS 1787 files / 0 need** |
| 架构边界 | `python3 tools/test-core-boundaries.py` | **PASS 25 tests** |
| 源码卫生 | `python3 tools/check-source-hygiene.py` | **PASS** |
| 运行时输入契约 | `python3 tools/test-runtime-input-contract.py` | **PASS 18 tests** |
| descriptor 源核验 | `python3 tools/test-runtime-descriptor-source.py` | **PASS 4 tests** |
| 文档链接 | `python3 tools/check-document-links.py` | **PASS 56 docs / 1340 links / 0 error** |

行为冻结覆盖任务要求：队列/取消/进度（`PluginOperationQueueTest` 27）、超时/UNKNOWN/
exitUnknown/截断（`PluginOperationResultTest` 8 + `PluginManagementEngineTest` 50 的
取消、迟到回调、UNKNOWN 保留、不自动重放）、合并窗口与扫描串行（`PluginChangeCoalescerTest` 11）、
退出证明一次性消费（`PluginExitProofGateTest` 5）。测试身份/数量与基线一致，无丢失或改名。

## 4. 受管输入与集成动作

`plugins/*.java → *.kt` 改变 `runtime-descriptor.json` 的 launcher 成员集（517 启动输入中
11 个成员换扩展名），本地 `verifyRuntimeDescriptorInputs`/`prepareRuntimeDescriptor`
因此按预期拒绝；本提交**未改** descriptor、受管证明、`ci/source-layout.json` 或固定输入。
按仓库约定由集成侧（main）从当前 `bf491c77` 建集成树后重算 descriptor/runtimeId 与
managed-package proofs，再跑 `verifyRuntimeDescriptorInputs`；本地编译门禁用
`-x verifyRuntimeDescriptorInputs -x prepareRuntimeDescriptor` 排除这两项，不把这次
排除写成 descriptor 通过。

## 5. 未验证项与风险

- **Gradle 单测 / Robolectric 未在本机运行**：aapt2 不可用；harness 只跑 5 个纯逻辑测试类。
  `PluginRegistryTest`(30)、`PluginBackendAdapterTest`(23)、`PluginRuntimeProjectionTest`(26)、
  `PluginChangeDetectorTest`(22)、`PluginChangeDetectorCoverageTest`(11) 在本机仅编译通过，
  未执行，需 CI x86_64 runner 补跑。
- **Robolectric shadow 风险（重点）**：`PluginChangeDetectorTest.HostStamp` 以
  `@Implements(PluginChangeDetector.class)` 静态 `stamp(Path)` 影子注入。Kotlin 内无法在
  自身类发出指向 `@JvmStatic` 桥的 `invokestatic`（会走 Companion），故 `stamp/safeDirectory`
  改为 private 实例方法；依赖 Robolectric 4.15.1 ShadowWrangler 对“static shadow 影子
  instance original”的支持。CI 未跑前不能声明 Windows 语义/shadow 用例通过；若失败，
  回退方案是接受 Linux 真实 NIO 或在单独提交调整注入点（不在本迁移内改测试）。
- **未做**：APK/assemble、Lint、Robolectric、真机/运行时行为、CI full、descriptor 重算、
  受管运行时维护页/安装验证。不把编译与 harness 结果写成这些项通过。
- **Kotlin 编译器行为**：`Detail`/`Binding`/`receipt` 归一化使用
  `@Suppress("VAL_REASSIGNMENT")`（与既有 `SafGrantRecord.kt` 同法），kotlinc 2.2.10 会提示
  UNSPECIFIED；Kotlin 升级时必须重验构造字节码、组件顺序、归一化与 record 值语义。

## 6. 回退

单提交可整笔回退：

```sh
git revert --no-edit 5bd453cd
```

回退恢复 11 个 `.java`、删除 11 个 `.kt`、恢复 `tools/java-format-files.json` 的 11 条；
随后重跑 `format-java-tree.py --check` 与 `compileStandardDebugJavaWithJavac` 即回到 Java 实现。
无数据格式、包名、权限或签名变化，不需要清数据/重新安装；若已由 main 重算 descriptor，
回退时同样由 main 重算回旧 runtimeId。

## 7. 证据位置（仓库外，不提交）

- harness：`/tmp/plugins-harness-76065c2b/`（`run.py`、`before/`、`final/`、`before.log`、`final.log`）
- 迁移前后源码树：`/tmp/plugins-baseline-76065c2b/`、`/tmp/plugins-final-76065c2b/`
- JVM 对照：`/tmp/api-diff-final.log`（33 个类型全部 PASS）
- Gradle 编译日志：`/tmp/plugins-kotlin-compile-final-{k,j}.log`
- 静态门禁日志：`/tmp/gate-*.log`
