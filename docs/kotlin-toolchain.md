# Kotlin 工具链（ADR-0004 决定一）

状态：**已接入，批次待 lead 复核与非实现者评审**（2026-11-12）。
依据：[ADR-0004 决定一](adr/0004-kotlin-rust-direction.md#决定一重切--增量-kotlin-化不做全量重写)
与[工程规范](engineering-standard.md)。本页记录**当前实际可运行的版本、依赖校验、格式门禁、迁移规则与回退**；
不把「能编译」写成「已完成 Kotlin 化」——语言切换按模块推进，旧文件在被改动前保持 Java。

## 1. 版本事实

| 项 | 值 | 依据 / 位置 |
|---|---|---|
| Gradle | 9.3.1 | `gradle/wrapper/gradle-wrapper.properties`（含 distribution SHA-256） |
| Android Gradle Plugin | 9.1.1 | 根 `build.gradle` |
| JDK | 17 | `build.sh` / CI `setup-toolchain`；Kotlin 编译任务与 Java 同为 17 字节码 |
| Kotlin Gradle Plugin（KGP） | **2.2.10（固定）** | `settings.gradle` 的 `gradle.ext.kotlinVersion`；根 `build.gradle` buildscript classpath 显式 pin |
| Kotlin 编译器 | 2.2.10（随 KGP） | AGP 9.1.1 的 built-in Kotlin 运行时依赖 |
| Kotlin 标准库 | 2.2.10（AGP 自动添加） | 不在 `app/build.gradle` 重复声明 `kotlin-stdlib` |
| jvmTarget | 17 | `app/build.gradle` 的 `kotlin { compilerOptions { jvmTarget = ... JVM_17 } }` |
| 格式工具 | ktfmt 0.64（Apache-2.0，`--kotlinlang-style`） | `tools/ktfmt.lock.json`、`tools/ktfmt/README.md` |

### 1.1 为什么不是 `org.jetbrains.kotlin.android` 插件

AGP 9.0 起提供 **built-in Kotlin**：AGP 自己注册 Kotlin 编译任务、添加 `kotlin-stdlib`
依赖，并禁止再应用 `org.jetbrains.kotlin.android` / `kotlin-android`
（新 DSL 下会报 `Cannot add extension with name 'kotlin'`）。官方迁移文档也明确该插件与新 DSL
不兼容；临时回退需要同时 `android.builtInKotlin=false` + `android.newDsl=false`，且 AGP 10
会移除该开关，属于不可维护的降级，**本仓库不采用**。

因此本批次的「固定版本」落在两处且必须一致：

1. `settings.gradle`：`gradle.ext.kotlinVersion = '2.2.10'`（唯一版本事实，禁止动态版本 / range / `-SNAPSHOT`）；
2. 根 `build.gradle`：`buildscript { dependencies { classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:${gradle.ext.kotlinVersion}" } }`
   （AGP 官方支持的 KGP 升级/固定方式；与 AGP 9.1.1 POM 中的运行时依赖同为 2.2.10）。

`app/build.gradle` 只启用 AGP 已注册的 Kotlin 编译与 `jvmTarget=17` 配置，不应用 KGP 插件。
任务名以实际为准：`compileStandardDebugKotlin`（`compile<Variant>Kotlin`）。

### 1.2 stdlib 不重复

built-in Kotlin 自动为模块添加一次 `kotlin-stdlib`（2.2.10）。`app/build.gradle` 不声明
`implementation 'org.jetbrains.kotlin:kotlin-stdlib...'`；依赖报告里同一坐标只出现一次。
新增 Kotlin 代码不需要手动加 stdlib。

### 1.3 AGP / Gradle 升级检查点

升级 AGP 或 Gradle 时必须逐项确认，不能只看版本号：

- AGP 9.x 仍提供 built-in Kotlin；若未来版本改变机制，以官方迁移文档为准，不盲目恢复插件；
- AGP POM 的 KGP 运行时依赖是否仍为 `gradle.ext.kotlinVersion`；若 AGP 自带版本更高，
  要么把本仓 pin 同步到同一版本（显式 classpath），要么经评审接受升级；
- `:app:compileStandardDebugKotlin` 实际任务名、输入输出与 `compileStandardDebugJavaWithJavac` 的
  交叉编译仍成立；
- `gradle/verification-metadata.xml` 增量条目仍通过严格校验；Kotlin 编译器/daemon 相关
  artifact 缺失时构建必须失败而不是跳过校验；
- `python3 tools/format-kotlin-tree.py --check` 与 `tools/ktfmt.lock.json` 的版本/sha256 仍一致。

## 2. 依赖校验（`gradle/verification-metadata.xml`）

仓库开启依赖校验（`verify-metadata=true`、`verify-signatures=false`）。本批次只**新增**
Kotlin 工具链相关组件与 artifact 的 SHA-256，不删除旧条目、不重排已有条目；人工复核新增项
只来自 KGP / Kotlin 编译器 / stdlib 及其构建期依赖。

生成/复核方式：

```sh
dsha-gradle --write-verification-metadata sha256 :app:compileStandardDebugKotlin
dsha-gradle :app:compileStandardDebugKotlin        # 严格校验下重跑
```

- 若 Gradle 自动写入会大改格式或丢失人工来源说明，则改为**从该次生成结果中提取新增
  component/artifact 条目，手工补进原文件**，并在提交说明与本页记录来源；不整文件替换。
- 新增条目的 origin 统一说明生成方式（Gradle `--write-verification-metadata sha256` 生成，
  或官方 Maven/GitHub 字节核对）；不伪造来源。
- 离线：KGP、Kotlin 编译器、stdlib 首次解析后位于 `GRADLE_USER_HOME`；`dsha-gradle`
  可直接复用。干净缓存且无网络时无法构建；CI 有网，因此不受影响。格式工具另见 §3 的
  `app/build/kotlin-format` 缓存与 `--offline`。

## 3. 格式门禁（ktfmt）

- 工具与版本：ktfmt **0.64**，`--kotlinlang-style`（Kotlin 官方 4 空格风格），
  `--do-not-remove-unused-imports`；JAR 为 GitHub 官方 Release 的
  `ktfmt-0.64-with-dependencies.jar`，只在 JDK 17 下运行。
- 字节锁：`tools/ktfmt.lock.json` 记录版本、大小、SHA-256、官方 URL、许可与来源核对说明；
  Maven Central 的 thin JAR 用于版本身份交叉核对（SHA-1 已核对）。运行时只使用 fat JAR。
- 缓存：`app/build/kotlin-format/ktfmt-0.64-with-dependencies.jar`（不提交）；每次使用都核对
  `size` / `sha256`。`--offline` 在缓存缺失时失败，不联网。
- 覆盖范围：五个受管源集（`main` / `standard` / `debug` / `androidTest` / `test`）下的
  `java/` 与 `kotlin/` 目录中全部 `.kt` / `.kts`。Kotlin 是 greenfield，因此**没有 Java 那样的
  adoption manifest**，从第一个文件起全量受门禁；`tools/format-kotlin-tree.py` 遍历并在
  包批次（每批 ≤128）内检查。
- 保护 / 生成文件：`protectedFiles` 与 `generatedFiles` 规则与 Java 门禁一致（`tools/ktfmt.lock.json`，
  当前为空）；`history`、`upstream`、`vendor`、`third_party`、`generated` 目录拒绝。
- 幂等：每批格式结果会再格式化一次并要求字节不变（`FORMAT_NOT_IDEMPOTENT`）。
- 与 Java 门禁的差异：首版不加独立 lexer token 指纹（语义门禁由 Kotlin 编译 + 单测承担）；
  ktfmt 会整理 import 顺序，因此不按 Java 的 token 序列比较。中文 KDoc 会被 ktfmt 重排，
  注释请用空行分段。

```sh
python3 tools/format-kotlin-tree.py --check
python3 tools/format-kotlin-tree.py --write        # 只在评审格式 diff 后使用
python3 tools/format-kotlin.py --check --files <显式 .kt/.kts 路径>
JAVA_HOME=/opt/jdk17 python3 tools/format-java-tree.py --check
```

CI：可复用节点 `.github/workflows/_node-kotlin-format.yml`，由 `ci-fast.yml`
（Source checks）、`release-production.yml`、`release-upstream.yml` 调用；失败时上传
`app/build/kotlin-format` 报告。

## 4. 构建与验证命令

```sh
dsha-gradle :app:compileStandardDebugKotlin
dsha-gradle :app:testStandardDebugUnitTest --tests '*ShellQuoteTest'
dsha-gradle :app:testStandardDebugUnitTest --tests '*ShellQuoteKotlinTest'
dsha-gradle :app:assembleStandardDebug          # 若 aarch64/aapt2 受限则见下
```

- Kotlin 单测与 Java 单测都进入 `:app:testStandardDebugUnitTest`；Kotlin 测试文件放在
  `app/src/test/java/...`（与迁移文件同包）。本机 Gradle 测试任务在 `processStandardDebugResources`
  被 aapt2（x86_64 Linux 构建，aarch64 主机 `bad machine`）阻断，未跑到测试编译/执行；同一测试在
  手工 `kotlinc 2.2.10 + javac + JUnitCore` 下 12 项全过（见「验证回执」），CI 的 x86_64 runner
  走完整 Gradle 单测。
- 本机 aarch64 的 `assemble` 同样受 aapt2 限制；**以实际能否运行为准**，
  不把跳过写成通过（详见本页末尾「验证回执」）。
- 校验 meta 与编译的严格顺序：先 `--write-verification-metadata sha256` 生成增量，再不带该参数
  重跑编译；后者失败即说明校验没有真正放行。

## 5. 迁移规则（ADR-0004 决定一）

- **按模块切换，不按文件混写**：同一 `.kt` 文件不得夹带 Java 源码；同一职责的类不会同时保留
  `.java` 与 `.kt` 两个实现。本批次的 `ShellQuote` 是整文件迁移，删除原 `.java`。
- **Java/Kotlin 互调边界**：从 Java 调 Kotlin `object` 的方法用 `@JvmStatic` 保留静态方法形态；
  从 Kotlin 调 Java 时保持现有公开 API。迁移后 Java 调用方与既有测试必须不改（或只改 import）通过。
- **新类优先用 Kotlin 的判定**：只有在模块边界已确定、且该文件被整体改动时才迁移；不先翻译
  全仓再补测试。`util` 的「零 Android import」规则不变，Kotlin 迁移后由同一单测与源码检查约束。
- **格式工具同批加锁**：引入任何 Kotlin 源文件前必须先有 `tools/ktfmt.lock.json` 与
  `format-kotlin-tree.py` 门禁；不允许「先写 Kotlin，格式工具下次再补」。
- **可回退**：Kotlin 试点只改变实现语言与构建输入，不改变公开行为；回退为 revert 对应提交。
  已安装用户不受影响（无数据格式、无权限、无包名变化）。

## 6. 回退

1. revert 本批次提交（Kotlin 试点、工具链、CI、文档可分别 revert）；
2. 恢复 `ShellQuote.java`、删除 `.kt` 与 Kotlin 格式/CI 文件，恢复 `settings.gradle` /
   `build.gradle` / `app/build.gradle` / `verification-metadata.xml`；
3. `dsha-gradle :app:compileStandardDebugJavaWithJavac` 与 `:app:testStandardDebugUnitTest`
   验证 Java 路径恢复；依赖缓存中的 Kotlin 组件不影响 Java 构建。

回退不需要清数据、不需要改 `applicationId` / 签名 / 版本号；不需要重新安装。

## 7. 已知限制

- 本批次只迁移 `util/ShellQuote` 一个纯逻辑类，不代表其他模块已 Kotlin 化；`core` / `ui` /
  `runtime` 等仍在 Java，按 ADR-0004 的模块顺序推进。
- Kotlin 格式门禁首版没有独立 token 指纹；若将来要得到与 Java 同等级的「格式前后 token 完全一致」
  证明，需要补一个基于 Kotlin 编译器的 lexer 指纹工具并单独评审。
- 本机 aarch64 的完整 `assemble` / aapt2 能力有限；APK 体积影响（stdlib）与真机行为需在可运行
  环境或 CI 上补测，本页不把未跑项写成通过。

## 验证回执

2026-11-12 本机（aarch64，JDK 17.0.21，Gradle 9.3.1，AGP 9.1.1）：

| 项 | 命令 | 结果 |
|---|---|---|
| Kotlin 编译（严格校验） | `dsha-gradle :app:compileStandardDebugKotlin` | **PASS**（BUILD SUCCESSFUL 1m55s；`ShellQuote.class` major version 61，`public static String arg(String)`） |
| Gradle 单测 | `dsha-gradle :app:testStandardDebugUnitTest --tests '*ShellQuoteTest' --tests '*ShellQuoteKotlinTest'` | **受限**：`processStandardDebugResources` 因 aapt2 `bad machine` 失败；未执行到测试编译/运行，不能记作通过 |
| 测试编译任务 | `dsha-gradle :app:compileStandardDebugUnitTestKotlin` | **受限**：同一 aapt2 资源任务先失败 |
| 手工等价测试 | kotlinc 2.2.10 + javac 17 + JUnitCore 4.13.2 | **PASS 12/12**（`ShellQuoteTest` 5 + `ShellQuoteKotlinTest` 7，0 failures；手工 harness 不替代 CI 完整单测） |
| APK | `dsha-gradle :app:assembleStandardDebug` | **未跑**：同一 aapt2 阻断；未生成 APK，未做体积/真机验证 |
| 依赖校验 | `--write-verification-metadata sha256` 后恢复原文件再严格编译 | **增量 0 条**：Kotlin 2.2.10 的 KGP/stdlib/编译器组件在基线中已由 build190 起的 AGP 运行时 classpath 记录；自动写入只做了重排，未采用，原文件字节未变 |
| stdlib | `:app:dependencies --configuration standardDebugCompileClasspath` / `standardDebugRuntimeClasspath` | **单一解析版本 2.2.10**；`app/build.gradle` 未声明 stdlib |
| Kotlin 格式 | `JAVA_HOME=/opt/jdk17 python3 tools/format-kotlin-tree.py --check` | **PASS 0 changed**（2 个 Kotlin 文件，2 个包批次） |
| Java 格式 | `JAVA_HOME=/opt/jdk17 python3 tools/format-java-tree.py --check` | 结果见提交说明；删除的 `ShellQuote.java` 已从 adoption manifest 移除 |

未运行 / 未覆盖：Gradle 完整单测、Robolectric 测试、Lint、APK 打包与安装、真机行为；这些需要
x86_64 CI 或真机环境，不写成本批次通过。
