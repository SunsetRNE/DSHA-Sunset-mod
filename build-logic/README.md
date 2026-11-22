# A8 P1 共享构建约定

这些 applied Groovy 脚本直接复用根工程已经加载的 AGP 9.1.1 与 KGP 2.2.10。
本目录不是 Gradle 产品模块，也不是 included build；不另建插件构建、不解析另一套 KGP，
不引入空产品模块。当前实际应用者是 `:app`。

`android.gradle` 共享 Java/Kotlin 17、desugar 2.0.4、JUnit 4.13.2、Robolectric 4.15.1
和 `includeAndroidResources=true`。`test.gradle` 共享测试资产的模块绝对根、
`dsha.sourceOnlyTests` 的 JVM 属性和任务输入、Rust hostLib/required、java.io opens、
离线 Robolectric SDK，以及 2048m / 单 fork 默认值。应用的发行身份、签名、SDK/NDK、
资源、生成资产、测试 HTML 资产和受管运行时生成任务继续留在应用脚本中。

## 首个 Android library 的实际入口

先在布局清单与 settings 同批登记真实模块，然后使用以下完整模块脚本：

```groovy
apply from: rootProject.file('build-logic/android-library.gradle')

android {
    namespace 'com.deepseekharness.example'
}
```

入口从根已经加载的 AGP classpath 应用 `com.android.library`，设置真实的
`compileSdk=37` / `minSdk=30` 默认值，随后应用同一份 `android.gradle`。
应用原有的 SDK 声明保留原位；配置审计读取两类实际 Android 扩展的 SDK getter 核值。
AGP 自带 Kotlin；不应用 `org.jetbrains.kotlin.android`，不手动声明
stdlib。模块自己的源码、资源、资产和依赖仍由模块持有，约定不会引用应用资产或发行身份。
测试任务通常为 `testDebugUnitTest`，必须把实际任务写进布局清单的 `testTask`。

## 首个 JVM library 的实际入口

纯 Java：

```groovy
apply from: rootProject.file('build-logic/jvm-library.gradle')
```

Kotlin JVM：

```groovy
apply from: rootProject.file('build-logic/kotlin-jvm-library.gradle')
```

两者都应用 Gradle 内置 `java-library`、JDK 17 toolchain 和同一份 JUnit/Test 约定，
测试任务为 `test`。Kotlin 入口再从根 buildscript 已 pin 的 KGP 2.2.10 classpath
应用 `org.jetbrains.kotlin.jvm`，设置 `jvmToolchain(17)` 和 `JvmTarget.JVM_17`。
它只用于纯 JVM 模块；Android 模块仍用 AGP built-in Kotlin。入口不再声明插件版本或
下载插件 marker，依赖校验继续使用根工程既有的 verification metadata。

上述接口已有可执行脚本；没有把“文档示例”或缓存存在写成离线编译已通过。
Kotlin 目标由 `kotlin-target.gradle` 从已经应用的 Kotlin 扩展 classloader 获取枚举并设为
`JVM_17`；applied script 自身无需能静态解析插件的 Kotlin 类型。

## 测试资产和夹具的归属

默认 `dsha.assetsDir` 是当前模块 `src/main/assets` 的绝对路径，保持应用原有行为。
没有 Android 资产的纯 JVM 模块不需要创建该目录，也不会因此依赖应用工程。
迁移已有测试前，先确认其读取的是哪棵源资产树，并在应用约定之前显式登记具体所有者：

```groovy
// 只读测试夹具；File 或绝对路径字符串，指向存在的具体源资产/夹具目录。
ext.dshaTestAssetsDirectory = rootProject.file('tools/fixtures/web-state')
apply from: rootProject.file('build-logic/jvm-library.gradle')
```

也可以将模块自己的测试资产放在 `src/test/fixtures`，显式设为
`file('src/test/fixtures')`。此接口拒绝相对字符串、不存在的显式目录、仓库根和模块根。
模块搬迁时需要同批登记 fixture 归属；不要复制生产资产所有者，也不要从 module kind
自动推断应该读取应用资产。真实配置审计按该显式值核对每个 Test 的 `dsha.assetsDir`。

## 验证分层与离线编译夹具

结构门禁不运行 Gradle：

```sh
python3 tools/test-build-conventions.py
```

`verifyDshaBuildConventions` 是独立的真实 Gradle 配置审计。它要求实际产品模块集合等于
布局清单，每个模块目录、插件、真实 Test 任务和 XML 目录均相符，并检查实际的
Java/Kotlin 17、测试依赖、资源、JVM 属性、输入、内存和单 fork 设置。完整 CI 还要求
任务图中的实际 Test 集合等于布局预期，防止漏跑模块。压力验证需要多 fork 时使用
filtered/source-only 的独立回执，不能产生完整基线 PASS。

主会话负责串行执行 `dsha-gradle`。首次 library/JVM 接入前，可生成仓库外的真实夹具：

```sh
python3 tools/test-build-conventions.py --emit-gradle-fixtures /tmp/a8-conventions-unique
# DIR 必须不存在。设置本机已有 JDK17 和已锁定的 Robolectric SDK 目录后执行：
PATH=/opt/jdk17/bin:$PATH \
DSHA_ROBOLECTRIC_DEPENDENCY_DIR=/absolute/path/to/robolectric-deps \
dsha-gradle --offline --no-parallel --max-workers=1 -I /tmp/a8-conventions-unique/include.init.gradle \
  :conventionJava:test :conventionKotlin:test \
  :conventionAndroid:testDebugUnitTest verifyDshaBuildConventions
```

这会临时登记仓库外的三个有真实源码和 JUnit 用例的工程：Java 编译/JDK17、
Kotlin JVM 2.2.10 编译/JDK17、Android library built-in Kotlin/Robolectric 真资源。
不修改 settings 或布局清单。夹具显式带 `dshaConventionFixture=true`，审计只允许该标记
用于仓库外项目；任何仓库内产品模块都不能用它绕过清单核验。
`--offline`、既有严格依赖校验和真实编译测试必须全过，才可登记该环境的离线接口验收。
缓存缺项要如实报告，不加仓库、不放宽校验、不为 library 另加 Kotlin Android 插件。

## 实际编译输入证明

[source-inputs.gradle](source-inputs.gradle) 将共享布局与真实 Android/JVM Java/Kotlin 源根、
官方 AGP component 静态源集、JavaCompile/KotlinCompile 有效文件逐项对等。额外、替换或过滤
输入直接拒绝，生成源只允许 UiLanguage、BuildConfig、AIDL 的精确 producer、输出根和文件。
配置期仅允许尚未完成的已知 generator provider 延后；编译前和 Test 前必须完整解析，不能
以 pending 进入完整测试。

编译前调用 `source_layout.py --verify-compile-head`，完整运行的每个非空编译阶段还保存本次
before/after 同字节执行回执。Test 前重读模型和回执，独占写入 `compile-proofs/<task-sha>.json`；
Python 校验器独立核运行身份、库存、根、生产/测试源全集合、生成 owner、执行时间和原始摘要。
archived 模式仍要求这些证据文件闭环，只免除旧 runner 的库和生成文件物理路径重读。

`test-build-conventions.py --emit-full-contract-fixture NEWDIR --with-kotlin` 可生成仓库外合成
JVM 门禁项目。它含真实 Java/Kotlin 编译和 JUnit 用例，但 opaque 假 so 与 fake CI baseline
明确只用于门禁接线，不证明 JNI、产品夹具或产品完整 CI。先运行生成项目的 prepare-synthetic，
再经 `dsha-gradle` 串行执行，已消费的 state 不复用；详细实测见 P1 回执。

## CI 行为门禁

`unit-test-contract.gradle` 与上述结构抽取分开，由根工程应用。只有完整未过滤 CI 设置
`DSHA_UNIT_TEST_STATE` 后才启用：每个真实 Test 的 `doFirst` 传实际 task path、
source-only/Rust 属性、Gradle filters、文件 includes/excludes、scanForTestClasses 和
实际编译输出目录给 `unit_test_contract.py task-begin`；成功的 `doLast` 才写 task-end。
任务失败、UP-TO-DATE、NO-SOURCE 或 SKIPPED 不会产生完整成功证据。

共享工作流由 `source_layout.py --test-tasks` 枚举所有任务和报告目录。完整运行准备新状态、
强制重跑、检查真实模块与任务图，再调用全仓 report contract。校验只接受
`ci/unit-test-baseline.json` 的真实 CI 冻结基线；缺失即失败，不自动 bootstrap 或从旧汇总
推导 testcase。过滤与 source-only 逐模块运行既有计数验证器，输出明确的独立汇总，
不写 full 凭据。上传覆盖全仓 `**/build/test-results/**`、计划、任务证据与汇总。

结构抽取（Android/Test/JVM 脚本及 app 接入）与新鲜报告/身份门禁分开提交。
回退按依赖逆序撤销对应提交，不删除测试或用户数据。P1 不授权模块提取，实际验证与尚未关闭的
出口见 [P1 实现与验证回执](../docs/design/kotlin-rust-rearchitecture-20261112/a8-p1-engineering-20261115.md)。
