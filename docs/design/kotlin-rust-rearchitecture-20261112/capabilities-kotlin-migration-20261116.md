# capabilities 域整域 Kotlin 迁移回执（2026-11-16）

本批次是 T6（会话 `session-853f7f26-d05a-454c-b91b-33db75586c0f`，@853f7f26）的本地实现与验证回执，
不是发布、安装或手机验收。分支 `dsh-853f7f26-a315`，基线为开工 fetch 后的 `origin/main`：
`bf491c77e79dc14a8c280d37caf4309b15df098e`。提交号以分支 HEAD 与工作树 `.SESSION-DONE` 为准。
已发布版本仍为 build367 / `f3f8fc03`；build368 与本批无关。

## 1. 实现范围与 owner

生产源根仍为 `app/src/main/java`。以下 6 个生产 Java 已删除，由同名 Kotlin 实现整域替换，FQCN 不变：

- [CapabilityBroker](../../../app/src/main/java/com/deepseekharness/app/capabilities/CapabilityBroker.kt)
- [CapabilityRequest](../../../app/src/main/java/com/deepseekharness/app/capabilities/CapabilityRequest.kt)
- [CapabilityResult](../../../app/src/main/java/com/deepseekharness/app/capabilities/CapabilityResult.kt)
- [TargetEvidence](../../../app/src/main/java/com/deepseekharness/app/capabilities/TargetEvidence.kt)
- [WindowTargetEvidence](../../../app/src/main/java/com/deepseekharness/app/capabilities/WindowTargetEvidence.kt)
- [ScreenTargetEvidence](../../../app/src/main/java/com/deepseekharness/app/capabilities/ScreenTargetEvidence.kt)

权限判据与结果模型保持原实现：`CapabilityBroker` 仍是应用级代次围栏 + 单一原生确认槽（无队列），
确认/派发仍分离（`DispatchPermit` 一次性消费，`CapabilityResult` 只表达执行事实）；锁内不调用适配器、
平台、UI 回调或 I/O；撤销、换代、过期、目标绑定、`RuntimeSnapshot.Epoch` 接缝、安装实例身份与
bridge run 身份判据逐条保留。没有修改实体屏/虚拟屏凭据实现、三通道执行策略、系统服务、数据格式、
包名、权限或启动顺序。

## 2. 冻结判据与结果模型

先冻结、后迁移：原 Java 基线上先跑既有测试，迁移后同一份未改测试再跑，覆盖关系如下。

| 判据 | 既有 [CapabilityBrokerTest](../../../app/src/test/java/com/deepseekharness/app/capabilities/CapabilityBrokerTest.java) 用例 |
|---|---|
| 撤销 | `revokeWakesOldWaiterWithoutPretendingUserDenied`、`revocationIsScopedButRunStopFencesEverything` |
| 换代 | `noRunningInstanceOrWrongInstallationCannotBorrowAuthority`（generation=0 / instanceId 空 / owner 不符） |
| 过期 | `deadlineExpiresWithoutApprovingOrDispatching`（严格 `now < deadline`） |
| 目标变化 | `targetComparisonNeverUsesPackageAlone`（display/window/package 三项） |
| 拒绝 | `denialAndCancellationCannotBeReauthorizedByLateCallback` |
| UNKNOWN / PARTIAL | `onlyKnownNotSentResultIsEligibleForExplicitAlternateChoice` |
| 重复消费 | `dispatchClaimIsOneUseAndNeverWaitsForAdapter`、`simultaneousDispatchClaimsHaveOneWinnerAndStopDoesNotWaitOnIt` |

另新增 [CapabilityBrokerContractTest](../../../app/src/test/java/com/deepseekharness/app/capabilities/CapabilityBrokerContractTest.java)（8 项），
补上既有用例未钉住的边界：跨能力单槽占用、无决议 `finish` 立即撤销且重复收尾不复活、拒绝后
`await`/`answer()` 语义、撤销其它能力不打扰待决确认、`matches(null)`、代次/期限严格边界、
请求与结果的长度/字符/负数校验和 `State` 顺序、领取后撤销不可重放。
该测试同时对照原 Java 与 Kotlin 两套实现各跑 8/8 通过（见 §4），因此是行为冻结，不是迁移专用断言。

## 3. Java/Kotlin 兼容结果

对 6 个原具名类型与 3 个嵌套类型（`CapabilityBroker$DispatchPermit`、`CapabilityBroker$Confirmation`、
`CapabilityResult$State`）做 `javap -p -s` 对照（原 Java class 与迁移后 class 各一套，JDK 17）：

- 原公开/受保护成员的 descriptor 差异 **0**，字段名/类型 **0** 变化，接口方法签名 **0** 变化；
- 原 `public final` 实例字段保留为 `@JvmField`，Java 直接字段访问（`request.capability`、`result.state`、
  `evidence.displayId` 等）不变；嵌套类型仍为静态嵌套类，Java 类型名不变；
- 静态工厂（`CapabilityRequest.create`、`CapabilityResult.notSent/rejected/completed/partial/unknown`）
  以 `companion object` + `@JvmStatic` 保留原静态方法形态；
- `Confirmation.await` 保留 `throws InterruptedException`（`@Throws`），Java 的 `catch (InterruptedException)`
  继续成立；`Confirmation.answer()` 保持 `Boolean`（可空）语义：`null` 表示无人回答，不是用户拒绝；
- 构造参数在 Kotlin 侧声明为可空并保留原校验顺序与异常类型/文案（`INVALID_REQUEST_*`、`INVALID_SCREEN_TARGET`、
  `NullPointerException("runtime"/"target")` 等），Java 传 null 时的行为与迁移前一致；
- 匿名 `WindowTargetEvidence` 实现（`HttpShellService` 与测试）不改即编译；`ScreenTargetEvidence`
  同时保留直接字段与接口函数（`displayId` 字段 + `displayId()` 方法），两者并存已验证。

本批接受以下**编译器基础设施/可见性例外**，不声称位级完全一致，也不声称无新增公开成员：

1. `CapabilityBroker.Confirmation` 与 `CapabilityBroker.DispatchPermit` 的构造器由 `private` 变为
   `internal`（JVM 上 public）：Kotlin 不允许外部类调用嵌套类的 private 构造器；二者都不进入 broker 的
   确认槽/派发路径，外部构造不会获得权限。
2. `internal` 成员在 JVM 上带模块后缀：`Confirmation.waiting$main()`、`Confirmation.settle$main(Boolean)`、
   `DispatchPermit.getOwner$main()`。方法名不可由 Java 源码直接书写，仅反射可见；用于让 broker 继续独占
   latch 与 answer 状态。
3. `Confirmation` 私有字段 `answer` 改名 `answerValue`（private，不改变公开 API）。
4. Kotlin 编译器产物：`CapabilityRequest`/`CapabilityResult` 的 `Companion` 字段与类、`static {}`、
   `CapabilityResult$State` 的 `$ENTRIES`/`getEntries()`，以及 `CapabilityResult` 的
   `(State, String, String, DefaultConstructorMarker)` 构造器（`ACC_PUBLIC|ACC_SYNTHETIC`，javac 不可见）。
5. 原 Java 方法在 final 类上新增 `ACC_FINAL` 标志；原类本就 final，可继承边界不变。

`CapabilityRequest` 的 capability 正则仍用 `Regex("[a-z][a-z0-9._-]*")`（与 `String.matches` 等价，含尾点放行），
`text()` 的 256/64/8192 上限与 NUL 拒绝、`CapabilityResult` 的 128 上限与 `detail` 缺省空串均原样保留。

## 4. 真实编译与测试（本机实测）

本机 aarch64，JDK 17.0.21；K2JVMCompiler 2.2.10 + javac 17，目标字节码 17。以下编译与测试在
`01da530afcd0810d65cd9c03c8ec0cae397f6987` 的干净工作树上执行；本回执的后续文档提交只改文档，
不把文档提交冒称被测源码 HEAD。

源码 SHA-256：`CapabilityBroker.kt fb3d6225…`、`CapabilityRequest.kt 1d902cc0…`、
`CapabilityResult.kt 39a81ef9…`、`ScreenTargetEvidence.kt d107ea89…`、`TargetEvidence.kt dd307b38…`、
`WindowTargetEvidence.kt 30577f4c…`、`CapabilityBrokerContractTest.java 32790290…`。

| 阶段 | 实际输入/结果 |
|---|---|
| 原 Java 基线（迁移前） | 6 原 Java + 未改 `CapabilityBrokerTest`：**11/11 通过** |
| 迁移后同输入对照 | 6 Kotlin + 同一未改 `CapabilityBrokerTest`：**11/11 通过** |
| 新增冻结测试对照 | 同一 `CapabilityBrokerContractTest` 对原 Java **8/8**、对 Kotlin **8/8** |
| 全生产编译 | **58 Kotlin + 1028 Java，0 error**（main 972 Java + standard 3 + debug 39 + tools/aidl-stub 1 + 生成 13；R/BuildConfig/UiMessages 仅作编译桩，不冒充 AAPT/资源验证） |
| 定向单测 | 7 个纯 JVM 类 **50/50**（CapabilityBroker 11、ContractTest 8、ScreenAuthorityBindings、ScreenSessionGrant、BridgeConfirmations、AdbVirtualScreenStart、VscreenTargetEvidence）+ `ScreenFailureMetadataTest` **3/3** |
| Java 消费方 | 全生产 javac 覆盖 `HttpShellService`、`DshaAccessibilityService`、`screen/`、`vscreen/`、`control/`、`util/BridgeConfirmations` 等调用方，零改动通过 |

手工 harness 只替代 Gradle 的编译/执行入口；本机未跑 Robolectric、Android 仪器测试、Lint、APK/Dex、
真机与系统服务路径，不把手工结果写成 CI 或设备通过。

## 5. 格式、边界与受管输入

- Kotlin 格式门禁：`python3 tools/format-kotlin-tree.py --check --offline --jobs 1` → **64 文件 / 0 need**；
  本域 6 文件改动 ktfmt 0.64 幂等（`--kotlinlang-style`）。
- Java 格式门禁：`python3 tools/format-java-tree.py --check --offline --jobs 1` → **1793 文件 / 0 need**；
  清单只做 **6 删 1 增**：删除本域 6 个旧 Java adoption，新增冻结测试
  `app/src/test/java/com/deepseekharness/app/capabilities/CapabilityBrokerContractTest.java`。
- 边界/卫生：`check-source-hygiene.py` PASS；`test-core-boundaries.py` 25/0/0；
  `test-architecture-boundaries.py` PASS；`test-unit-test-contract.py` **64/64 OK**（新增 Java 测试不破坏测试身份守恒）；
  `test-source-layout.py` 16 项在提交后通过（未提交前 `git ls-files` 只有旧 Java，属工作区态差异）。
- `test-runtime-input-contract.py` 18 项中 17 过、1 ERROR：新工作树缺固定归档 `dsh-runtime.bin`
  （`RUNTIME_INPUT_MISSING_OR_OUTSIDE`），与本域源码判定无关；未用绕过方式改证明。
- `tools/test-physical-screen-flow.py`（support 阶段、非 CI 默认执行）同步适配：能力层源码已不在 `*.java`，
  改为从 `app/build/intermediates/built_in_kotlinc/<flavor>/compile<Flavor>Kotlin/classes` 取 Kotlin 编译产物并
  要求 `CapabilityBroker.class`/`CapabilityRequest.class` 存在，同时把 6 个 `.kt` 记入输入摘要。本机未执行
  该 harness（需要 Gradle 构建产物与依赖引用回执）。

### 受管运行时输入（最终 descriptor 由 main 集成树重算）

`capabilities/` 在 `app/src/main/assets/managed-runtime-inputs.json` 的 `launcherTrees` 中，扩展名替换会改变
`launcherInputs` 键名与摘要，因此**必须重算 descriptor**；本批不手改
[`runtime-descriptor.json`](../../../app/src/main/assets/runtime-descriptor.json)。用同一生成算法与资产清单
（缺失的 6 个固定归档沿用当前 descriptor 中已核验摘要）得到的确定差异为：

- `launcherInputs`：517 → 517，`capabilities/*.java` 删 6 / `capabilities/*.kt` 增 6，其余 505 项摘要 **0 变化**；
- `inputs`：205 项成员集合与摘要 **0 变化**；
- 只有 `launcherInputs` 与 `runtimeId` 变化：`63adce0a3df6f0c46494dc6adf030f07f80724557f139b3598755caa1607e2ad`
  → `147ccc82b15e456d6ce68981637ec673a3a843c05f15e54f5ee84567508d059b`（预期值；以 main 集成树真实资产
  重算为准，覆盖升级预期一次维护页）。

`python3 tools/verify-runtime-descriptor-source.py` 与 `tools/test-runtime-descriptor-inputs.py` 当前如实失败
`DESCRIPTOR_LAUNCHER_MEMBER_SET`，正是在等待上述串行重算；本批不把该失败写成通过。

## 6. 未验范围、合入与回退

未验证：Gradle 默认任务图。本树 `dsha-gradle :app:compileStandardDebugKotlin` 在 `:app:prepareBackupAssets`
即因新工作树缺固定归档 `dsh-runtime.bin` 失败（未到 Kotlin 编译/测试）；且本机 `aapt2` 为 x86_64 ELF
（`/opt/android-sdk/build-tools/36.0.0/aapt2`），在 aarch64 上 `processStandardDebugResources` 的 aapt2 daemon
同样无法启动（同批其它会话实测）。因此本域在 CI x86_64 完整 Gradle 前不宣称 Gradle 单测通过。
未验证还包括 Robolectric 与 Android 仪器测试（含 `LocalTaskConfirmationAndroidTest` 与
vscreen 链式测试）、Lint、APK/Dex/签名/安装、真实设备上的确认/撤销/换代时序、physical-screen support harness
实际运行、最终 descriptor/runtimeId。

不自行 push、合并、发布、安装或操作手机。合入前由 main 基于当时 main 重核/必要时 rebase，串行重算
descriptor 与 runtimeId（并处理与 T5/plugins 迁移共享的 `tools/java-format-files.json` 与设计目录重叠），
独立审查 §3 的可见性/编译器基础设施例外，并经用户当次批准。

回退：revert 本批提交，恢复 6 个 Java 并删除同名 Kotlin 与新增冻结测试，恢复 Java 格式清单条目与
`tools/test-physical-screen-flow.py`；descriptor/runtimeId 回到 `63adce0a…`。不改用户数据、偏好键、
协议、包名或数据格式，不需要清数据或重新安装。
