# contract 职责域 Kotlin 等价迁移回执（2026-11-15）

基线：`origin/main=5ccc8aa3db4c961553a348a8eb35cbfa815be11c`（已发布 build366）。分支：`migrate-contract-kotlin`。本批只做语言与互调适配，不改变产品行为、不提取 Gradle 模块、不调整版本、偏好、协议或数据格式。代码提交为 `86a8c5e325bbc0291054cbc94c256f254e0cce69`；本回执/纯导航文档另行提交，最终 HEAD 见工作树结束声明。本回执不是发布/安装/真机验收记录。

## 范围与源码清单

生产源码位置仍为 `app/src/main/java/com/deepseekharness/app/contract/`。以下 18 个旧 `.java` 全部删除，由同目录同名 `.kt` 承担唯一生产实现；没有双份实现。

| 迁移前 | 迁移后 | 职责 |
|---|---|---|
| `BuiltinModules.java` | [BuiltinModules.kt](../../../app/src/main/java/com/deepseekharness/app/contract/BuiltinModules.kt) | 内置注册与按 SharedPreferences 实例身份复用缓存 |
| `ContractCodes.java` | [ContractCodes.kt](../../../app/src/main/java/com/deepseekharness/app/contract/ContractCodes.kt) | 稳定机器码常量 |
| `ContractResult.java` | [ContractResult.kt](../../../app/src/main/java/com/deepseekharness/app/contract/ContractResult.kt) | 失败关闭结果和值语义 |
| `Contribution.java` | [Contribution.kt](../../../app/src/main/java/com/deepseekharness/app/contract/Contribution.kt) | 贡献声明及静态工厂 |
| `ContributionCatalog.java` | [ContributionCatalog.kt](../../../app/src/main/java/com/deepseekharness/app/contract/ContributionCatalog.kt) | 稳定排序、隐藏叠加、保存失败回退 |
| `ContributionView.java` | [ContributionView.kt](../../../app/src/main/java/com/deepseekharness/app/contract/ContributionView.kt) | 只读呈现快照 |
| `DiagnosticsSink.java` | [DiagnosticsSink.kt](../../../app/src/main/java/com/deepseekharness/app/contract/DiagnosticsSink.kt) | 唯一脱敏出口及 Event 值对象 |
| `GenerationLease.java` | [GenerationLease.kt](../../../app/src/main/java/com/deepseekharness/app/contract/GenerationLease.kt) | 代次、volatile active、回调互斥与失效 |
| `LayoutSnapshot.java` | [LayoutSnapshot.kt](../../../app/src/main/java/com/deepseekharness/app/contract/LayoutSnapshot.kt) | 防御复制、未知合法键保留 |
| `LayoutStore.java` | [LayoutStore.kt](../../../app/src/main/java/com/deepseekharness/app/contract/LayoutStore.kt) | Java 可实现的布局后端接口 |
| `ModuleDescriptor.java` | [ModuleDescriptor.kt](../../../app/src/main/java/com/deepseekharness/app/contract/ModuleDescriptor.kt) | 声明校验、能力集合复制 |
| `ModuleFactory.java` | [ModuleFactory.kt](../../../app/src/main/java/com/deepseekharness/app/contract/ModuleFactory.kt) | 保留 Java SAM 与 FunctionalInterface 注解 |
| `ModuleId.java` | [ModuleId.kt](../../../app/src/main/java/com/deepseekharness/app/contract/ModuleId.kt) | 模块身份、构造和静态 of |
| `ModuleInstance.java` | [ModuleInstance.kt](../../../app/src/main/java/com/deepseekharness/app/contract/ModuleInstance.kt) | Java default close、贡献及关闭原因 |
| `ModuleRegistry.java` | [ModuleRegistry.kt](../../../app/src/main/java/com/deepseekharness/app/contract/ModuleRegistry.kt) | 安装、启停、安全模式、清理顺序与 dispatch |
| `RouteTable.java` | [RouteTable.kt](../../../app/src/main/java/com/deepseekharness/app/contract/RouteTable.kt) | 路由登记、宿主 fallback、Target 值对象 |
| `SafeModeGate.java` | [SafeModeGate.kt](../../../app/src/main/java/com/deepseekharness/app/contract/SafeModeGate.kt) | volatile 安全模式状态与宿主恢复入口 |
| `SharedPreferencesLayoutStore.java` | [SharedPreferencesLayoutStore.kt](../../../app/src/main/java/com/deepseekharness/app/contract/SharedPreferencesLayoutStore.kt) | 现有 Android 适配器及 v1 布局编解码 |

本域生产 Java：18→0；生产 Kotlin：0→18。既有测试源 7 个保持原样；新增 3 个 Java 测试源，因此本批全仓 Java 文件净变化为 -15、Kotlin +18。新增测试：

- [ContractValueInteropTest.java](../../../app/src/test/java/com/deepseekharness/app/contract/ContractValueInteropTest.java)：18 项，Java 构造/静态工厂/包内 helper、record 值语义参照、空值与校验先后、Unicode 空白/UTF-16 长度、集合复制和只读/null 查询。
- [ContractLifecycleInteropTest.java](../../../app/src/test/java/com/deepseekharness/app/contract/ContractLifecycleInteropTest.java)：11 项，Java SAM/default、空工厂/贡献、锁持有边界、关闭顺序、代次失效、RuntimeException/Error、Event/Target 值语义，以及两次遍历间出现空贡献时的拒绝清理次序。
- [ContractJavaBoundaryTest.java](../../../app/src/test/java/com/deepseekharness/app/contract/ContractJavaBoundaryTest.java)：2 项，真实 javac 验证异包公开调用正控和 7 个原包内入口的访问负控。

共享格式登记只在 [java-format-files.json](../../../tools/java-format-files.json) 删除这 18 个已删除生产 Java 条目、增加上述 3 个 Java 测试条目。其它职责域条目字节不变。未改变构建文件、工具链锁、Gradle 模块、输入清单或 descriptor。

纯导航修订单列：经 main 协调，只将 [A6 基线:43](a6-task-baseline-20261114.md#L43) 的 ContributionCatalog/BuiltinModules 两个现行源码链接，以及 [A7 基线:257](a7-task-baseline-20261114.md#L257) 的 module-desc/contract-result 两个引用目标改到对应 Kotlin 文件及实际行号。历史叙述、任务状态、验收结论、计数和 roadmap 均保持。

## Java/Kotlin API 与语义对照

逐类 `javap -p -s` 对照覆盖 18 个外层公开类型及 5 个公开嵌套类型，共 23 类、197 个原非 private 成员（包含字段、构造器、枚举成员和原包内 helper）。原成员的名称/JVM descriptor 全部存在；static、synchronized 和 default 标志差异均为 0。公开原成员仍为 public。final 外层类保持 final；Kotlin 生成的额外 final 方法不扩大可继承范围。

| 原类型 | 原非 private 成员数 | 保留的 Java 调用与特别处理 |
|---|---:|---|
| BuiltinModules | 9 | 6 个静态字段、Context/registry 两个公开静态 ensureRegistered 重载、SharedPreferences 包内重载 |
| ContractCodes | 24 | 原 static final String 常量及 ConstantValue |
| ContractResult | 9 | 构造、accepted/code/rejected、静态 ok/rejected、equals/hashCode/toString |
| Contribution | 17 | 原八参数构造、八个访问器、globalKey、card/route 各两个静态重载和值语义 |
| Contribution.Kind | 4 | CARD/ROUTE、values/valueOf；枚举顺序保持 |
| ContributionCatalog | 12 | 原构造及 add/revoke/contains/size/contributions/visibleContributions/byKind/find/layout/setHidden/setOrder |
| ContributionView | 14 | 原九参数构造、九个访问器、包内 of 及值语义 |
| DiagnosticsSink | 4 | 静态 discarding/forwarding、report 两个重载；下游异常边界保持 |
| DiagnosticsSink.Event | 6 | 允许 null 的双参数构造、code/detail、值语义 |
| GenerationLease | 6 | 原构造、owner/generation/active、同步 runIfActive/invalidate |
| LayoutSnapshot | 12 | 原 List/Set 构造、order/hidden/isHidden/withHidden/mergedOrder、empty/validKey/包内 validateKey、值语义 |
| LayoutStore | 2 | load/save；Java 后端可返回 null，由 catalog 保留原回退 |
| ModuleDescriptor | 10 | 原五参数构造、五个访问器、alwaysOn、值语义 |
| ModuleDescriptor.Enablement | 4 | ALWAYS_ON/OPTIONAL_USER、values/valueOf；枚举顺序保持 |
| ModuleFactory | 1 | create；fun interface 保留 Java lambda，允许返回 null 并进入原安装失败路径 |
| ModuleId | 6 | 构造、静态 of、value、equals/hashCode/toString |
| ModuleInstance | 2 | contributions 和真正的 Java default close，Java 实现者无需新增方法 |
| ModuleInstance.CloseReason | 5 | USER_DISABLED/HOST_CLOSE/SAFE_MODE、values/valueOf；枚举顺序保持 |
| ModuleRegistry | 21 | 原构造与 20 个公开方法；同步成员/显式锁段边界保持 |
| RouteTable | 7 | HOST_FALLBACK_KEY、构造、register/revoke/contains/resolve/size |
| RouteTable.Target | 8 | 原构造、key/hostFallback、静态 host/module、值语义 |
| SafeModeGate | 6 | 两个构造、forcedSafeMode/recoveryEntryAvailable、原包内 enter/exit |
| SharedPreferencesLayoutStore | 8 | 三常量、两个构造、静态 open、load/save |

### record 的明确边界

7 个原 Java record（ContractResult、Contribution、ContributionView、ModuleDescriptor、LayoutSnapshot、DiagnosticsSink.Event、RouteTable.Target）使用普通 final 不可变 Kotlin 类：private final 字段、原 record-style 访问器、显式构造校验与手写 equals/hashCode/toString。没有 bean getter、componentN、copy 或额外公开生产数据模型。集合仍调用 Java `List.copyOf` / `Set.copyOf`，包括空/单/多元素集合的只读和 contains(null) 异常语义；没有换成 Kotlin toList/toSet。

这不是 record 反射元数据的无损迁移：上述类型不再继承 java.lang.Record，isRecord/getRecordComponents 与赋值给 Record 的源码用途不再成立。197 个原成员的 descriptor 无缺失不覆盖这项变化。仓库中没有对 contract 使用这些能力的生产调用；现有其它 record 的反射测试不在本批范围。选择普通类是为了保留紧凑构造中的防御复制和现有 Java 成员形态，并避免 data class 自动 API。新增测试使用嵌套 Java record 作 hash/toString 参照，不依赖两种实现共享一份 equals/hash 实现。此兼容取舍必须在合入审批明示；main 的内部消费者核查不代表用户已接受内部调用范围之外的兼容变化。

额外 JVM 形状仅来自 Kotlin 编译器：Metadata、Companion、枚举 getEntries、default 兼容桥、访问私有状态所需的 synthetic 桥/构造。它们不属于稳定契约 API。本批没有承诺任意反射消费者、任意手写字节码或外部二进制插件兼容；本系统也没有第三方动态模块加载。

### 原 package-private 的明确边界

Kotlin 没有 Java package-private。以下原名/descriptor 保持不变，并在 final 类上发出 protected JVM 成员：

1. BuiltinModules.ensureRegistered(SharedPreferences)
2. ContributionView.of(Contribution, boolean, long)
3. GenerationLease(ModuleId, long, DiagnosticsSink)
4. GenerationLease.invalidate()（同步标志保留）
5. LayoutSnapshot.validateKey(String)
6. SafeModeGate.enterSafeMode()
7. SafeModeGate.exitSafeMode()

Java/JVM 的 protected 仍允许同运行时包调用；外层和 Companion 均为 final，异包不能以继承扩大这些入口。既有同包 Java 源码和迁移前字节码均直接通过原入口。异包真实 javac 的 7 个负控在迁移前后都拒绝，公开入口的正控都通过。

Kotlin 跨类调用不具有 Java 的 protected 同包规则，因此本域使用 internal + JvmSynthetic 委派桥；原入口本体仍承担校验/锁/状态写入，桥不另建实现。internal 的编译模块边界不是包边界，synthetic 在字节码通常仍 public，因此不把它描述为反射隔离。内部桥仅在 contract 调用，不用其放宽宿主生命周期入口。

### 运行语义

- 所有原显式 NPE/IllegalArgumentException 消息及检查顺序保留。可从 Java 传 null 的参数使用 nullable + 原校验，避免 Kotlin 自动参数断言抢先改变机器码或异常。原隐式空解引用只保持 NPE 类型，不承诺不同 JVM/Android 的 helpful-NPE 字符串逐字相同。
- Java String.isBlank 用 codePoints + Character.isWhitespace 等价实现；NBSP、U+2007、U+202F 仍可作非空白标题/能力；长度仍按 UTF-16。
- registry 的 install/factory 仍在 registry monitor；dispatch 在 registry 锁外进入 lease monitor；close 先锁内摘除实例/租约/贡献，再锁外 invalidate、instance.close、逐项撤销和诊断。第一份 Throwable、Error 重新抛出与继续清理的原逻辑保持。
- generation 的共享 AtomicLong、lease.active 和 SafeModeGate.forced 的 volatile、ALWAYS_ON/OPTIONAL_USER、安全模式路由 fallback 均保持。
- 偏好命名空间仍为 `dsha_module_layout`，键仍为 `order` / `hidden`，顺序格式仍为 `v1:` + 换行；尾空项继续拒绝，未知合法键继续保留。没有配置/数据迁移。

## 调用方与验证

唯一域外生产调用方为 [AboutFragment.java](../../../app/src/main/java/com/deepseekharness/app/ui/AboutFragment.java)（BuiltinModules、ModuleRegistry、ContributionView）。域外测试为 [AboutFragmentTest.java](../../../app/src/test/java/com/deepseekharness/app/ui/AboutFragmentTest.java)。这两份文件和全部既有 contract Java 测试均不修改。全仓其它 Java 生产/测试源码的编译结果与其限制见下表。

| 验证 | 实际结果 |
|---|---|
| 迁移前既有 Java contract 单测 | javac17 + 真实生产 SensitiveData/UiText/语言字典 + JUnit4：39 tests / 0 failure |
| 迁移前已编译的 Java 测试字节码直接加载 Kotlin | 39 tests / 0 failure；不是先重编译调用方后才运行 |
| 全部既有及新增 Java 测试重新编译并加载 Kotlin | 70 tests / 0 failure（既有39 + 新增18/11/2，包含首轮69项及审查新增回归） |
| 同一组新增回归及既有测试加载原 Java 基线 | 70 tests / 0 failure；证明新断言约束原行为 |
| 固定 AGP built-in Kotlin / 2.2.10 / JVM17 | `dsha-gradle :app:compileStandardDebugKotlin --max-workers=1`：首轮1m22s、P2修复后18s，均 BUILD SUCCESSFUL / exit0；修复后实际 Gradle class（major61）通过70项 Java 回归及39项旧字节码调用，次数不叠加成新的测试覆盖数 |
| JVM API | 23 类 / 197 原非 private 成员，descriptor 零缺失，static/synchronized/default 零差异；7 个包内入口改 protected，7 个 record 元数据边界如上 |
| 全仓 Java 生产及测试调用方编译 | 首轮主动编译全部 main Kotlin 后，1025 份生产/生成 Java 源编译 exit0；770 份 Java 测试源编译 exit0（含 AboutFragment 与其测试；未执行 Robolectric）。P2不改API，仅追加已有测试源的方法，该源已在新旧实现分别重编译运行 |
| Kotlin/Java 格式 | Kotlin tree：20 files / 0 need；Java tree：1812 files / 0 need，adoption 缺失/额外均0；两个命令 exit0 |
| 核心边界 | `tools/test-core-boundaries.py`：25 tests，PASS |
| 输入契约 | `tools/test-runtime-input-contract.py`：18 tests，PASS；descriptor-inputs：103 安装 / 205 资产 / 507 启动输入，Gradle tree 集合一致260文件 |
| descriptor 只读核验 | `prepare-runtime-descriptor.py --check`：f540d7f1988b4854b65660993a9d3f5743b0dca78bce8e51997c45b9773de7be，与基线一致 |
| 源码卫生 | 暂存新增源文件后 `check-source-hygiene.py`：PASS |
| 文档与 diff | 两任务基线及本回执的显式链接校验、默认56文档1315链接均0错误；`git diff --check` 及暂存 diff 检查通过 |

本机第一次完整 Gradle 单测尝试因新树缺 dsh-runtime.bin 在 prepareBackupAssets 失败，未执行测试；随后 source-only 尝试确认 AAPT2 x86_64 在 aarch64 的 bad machine 限制，也未执行测试（迁移文件写入中的双声明错误已通过删除全部旧实现和最终编译消除）。固定输入后来用主会话提供的已核验归档恢复；严格 Kotlin 编译与 descriptor 只读检查成功。不重复启动同一 AAPT2 失败条件。

宿主验证通过参数文件调用 Kotlin 2.2.10/javac17/JUnit，避开本机长 classpath argv 截断。全 Java 编译使用本批调整的仓库外夹具，主动分析/编译全部 main Kotlin；不沿用“仅编译 ShellQuote”就声称本域通过。该夹具的资源/BuildConfig 是生成 stub，编译通过不等于 Android/Robolectric 运行或完整 Gradle/CI 成功。

复现正式测试入口（应在支持 AAPT2 的环境执行）：

```sh
dsha-gradle :app:testStandardDebugUnitTest \
  --tests 'com.deepseekharness.app.contract.*' --tests '*AboutFragmentTest'
dsha-gradle :app:compileStandardDebugKotlin --max-workers=1
JAVA_HOME=/opt/jdk17 python3 tools/format-kotlin-tree.py --check
JAVA_HOME=/opt/jdk17 python3 tools/format-java-tree.py --check --jobs 1
python3 tools/test-core-boundaries.py
python3 tools/test-runtime-input-contract.py
python3 tools/test-runtime-descriptor-inputs.py
python3 tools/prepare-runtime-descriptor.py --check
```

本机证据与夹具位于工作树未入库的 `app/build/contract-migration/`：baseline/api.txt、post-api.txt、api-comparison.json、verify-host.py、binary-callers.log、java-callers.log、baseline-new-tests.log、gradle-kotlin.log、all-java-callers.log。结束声明会登记已结束任务与退出码。

未跑：完整 Gradle 单测执行、AboutFragment Robolectric 运行、Lint、APK 打包/签名/安装、真机/布局/语言/SAF 矩阵、完整 CI。没有合并、push、发布、安装或手机操作。

## 独立审查 P2 追加修复

main 对首轮 `7e9862a3` 全 43 文件审查为 REQUEST_CHANGES（P0=0/P1=0/P2=1）。新增确定性回归位于 [生命周期互调测试](../../../app/src/test/java/com/deepseekharness/app/contract/ContractLifecycleInteropTest.java)：CopyOnWriteArrayList 包含首个 ROUTE 和第二个 CARD，在第二次 iterator() 调用时将第二项改为 null，无线程竞态。

实跑审查前的实际 Gradle Kotlin class：11 项生命周期测试中 1 项失败，清理指标（closed / catalog size / route fallback / installed）为 `[0, 1, false, false]`；同一测试源码在原 Java 基线 11/0，指标为 `[1, 0, true, false]`。问题为第二轮在 catalog.add 前的非空断言跳过了拒绝清理。

追加修复只恢复原 Java 次序：先将 nullable item 交给 catalog.add；被拒时撤销已加贡献和路由、关闭实例，然后才执行原诊断表达式的空解引用；仅接受后才取非空 item 登记后续键和路由。原 Java 在这条坏输入路径清理后抛 NPE 的行为继续保留，没有顺便改为结构化返回。回归同时断言实例关闭次数、catalog 清空、路由回宿主、未安装及后续 close 幂等，不只断言异常类型。原两提交不重写，本修复本地追加提交。

修复后全套原 Java 70/0、Kotlin 70/0（包含原69项）、旧39项 Java 字节码直接运行通过。负控脚本与审查前 class 已保存在未入库的 `app/build/contract-migration/review-p2-negative-control.py` / `review-p2-pre-fix-classes/`；两种实现的实跑日志在 `review-p2-java-baseline/junit.log` 与 `review-p2-kotlin-before-fix/junit.log`。最终 Gradle 编译产物与格式/输入复核结果记录于本表及结束声明。

## runtimeId、遗留与回退

contract 不在受管 launcherSources/launcherTrees。实测本批改变的受管输入集合为空，descriptor 与基线相同；未重算或提交 descriptor。主会话仍须在串行合并树统一核验/重算，不能从单 worker 的零变化推导并行其它职责域也零变化。

- **N3 保留**：慢 factory 在 ModuleRegistry.install 持锁运行，close 仍可能等待；测试明确保留这个 monitor 边界，本批不宣称解决可取消性。
- **N4 保留**：SAF ledgerReady→legacy 迁移窗口不在本域改动内，未修改 SAF，也不宣称已关闭。
- contractVersion/describe 生命周期校验、ModuleHost scope、启停意图持久化、AboutLicense 聚合等原 deferred 项继续保留；本批不补协议或新行为。

回退时先 revert 本轮追加的 P2 等价修复，再 revert 原文档 `7e9862a3` 与代码 `86a8c5e3`，恢复 18 个原 Java 文件、删除 18 个 Kotlin 实现和新测试，并恢复本域格式登记。仅撤销 P2 修复会回到已知清理差异的旧 Kotlin 候选，不应作为可合入状态。无需清数据、改偏好键/数据格式、改包名/签名或降级已安装环境。回退后重新执行本域 Java 测试与输入检查；main 在自己的串行树处理最终 descriptor。不得重置或清理其它活跃工作树。

## main 集成修订（2026-11-15）

GitHub Source CI（run 43687532114）的 `unit-source` 与 `host-fast` 在 `ContractJavaBoundaryTest.java` 处编译失败：该测试使用 `javax.tools`（java.compiler），而 AGP 的 Android 单元测试编译类路径不含该模块。main 在串行集成树删除该测试文件，并从 `tools/java-format-files.json` 移除其条目；因此本批新增 Java 测试源由 3 个变为 2 个，全仓 Java 净变化由 -15 变为 -16。197 个原成员与 7 个包内入口的 javac 正/负控证据保留为作者本机验证，未进入仓库自动门禁；host 侧 javac 边界检查列为后续待办（可放入 tools/ 的 host 测试）。
