# Rust prebuilt：Gradle 路径修复与 Android 验证候选（2026-11-16）

本批是 T4 的本地实现与验证交付，基线 `f3f8fc03882ff7fecd32caac5c537b157395fbaa`（开工 `fetch` / `ls-remote` 核实最新 `origin/main`）。使用已自动新建的独立工作树 `dsh-1bc715e8-9cb0`；主检出旧 main 和其他活跃工作树未改。正式装配默认 `off`、`RustBackends` 默认 false，不切换生产后端。

本批不声称已产出新的 Android APK 或设备成功证据。远端构建、测试签名候选和真机执行由 main 对精确集成 SHA 安排；本 worker 未 push、合并、发布、安装或操作手机。历史 build367 的 off APK 不能充当本批 prebuilt 通过证据。

依据：[ADR-0004](../../adr/0004-kotlin-rust-direction.md)、[Kotlin 工具链](../../kotlin-toolchain.md)、[原 APK 注入契约与回执](rust-apk-injection-20261115.md)。本批无 Java→Kotlin 迁移，无生产 JVM API/ABI 变更；仅增补既有 Java instrumentation。没有改数据格式、偏好键、Rust JNI 符号或原生算法。

## 复现与修复

在基线上实际运行：

```bash
dsha-gradle :app:prepareRustNative -Pdsha.rust.mode=prebuilt \
  -Pdsha.rust.artifactDir=/tmp/rust-prebuilt-1bc715e8-missing --console=plain
```

命令 exit1，配置阶段在 [app/build.gradle](../../../app/build.gradle) 原第312行报 `You cannot add Provider instances to the Android SourceSet API`，尚未执行 `prepareRustNative`。AGP 9.1.1 拒绝直接给 SourceSet 传 `Provider<Directory>`。

修复只将 Rust receipt 的 assets 目录在配置期解析为 `File`；原有 JNI 目录同样采用这种方式，Rust 的 preBuild、assets/JNI merge 任务显式依赖保持。没有设置 `android.sourceset.disallowProvider=false`，没有减弱来源或产物校验。修复后按原命令实际重跑 exit1，已进入 `:app:prepareRustNative FAILED`，由 Gradle 的目录输入校验以 `doesn't exist` 拒绝；Provider 拒绝已消失。

## 实际 Gradle 回归

[独立 runner](../../../tools/rust/check_gradle_prebuilt.py) 串行启动实际 Gradle，使用独立 init script 将 `:app` build 输出定位到本次新建目录；不改仓库源文件，不删除现有构建输出，不污染正式 off staging。其 consumer 探针依赖生产 `prepareRustNative`，读取实际 Android SourceSet JNI/assets 目录并核对 preBuild、三个实际 merge 任务的生产依赖。探针不执行 APK merge/assemble，因此单独通过不能推导 APK 成功。

覆盖：默认/显式 off、未知模式、prebuilt 缺参、off 带输入、缺目录、有效 prebuilt 配置与 stage、同输入再次执行、库字节/commit/run/attempt 篡改和 off 残留拒绝。负控只改独立副本；每次失败后核验原件及最后通过的 staging 字节。最后重新以原件执行 stage 和 consumer 探针，防止拒绝污染后续有效请求。

独立逐行审查发现报告路径若与原 artifact 目录重叠会先写坏原件（1项P2）。已单独修复：任何 mkdir/mkdtemp/报告写入前，拒绝报告位于输入目录内、等于输入目录或是其祖先；报告通过已有原子临时文件替换写入，外部硬链接别名不会截断原 inode。新增3项CLI负控/6子例，刻意提供不存在的 Gradle 路径，要求先命中 `RUST_GRADLE_REPORT_INPUT_OVERLAP`，并逐字节比较原库/receipt与整个临时目录成员不变。复审结论 `APPROVE` / 无未决P0/P1/P2；复审仅只读，执行证据由本批测试提供。

本机入口：

```bash
python3 -B tools/rust/check_gradle_prebuilt.py --synthetic-fixture \
  --output /tmp/rust-prebuilt-1bc715e8-gradle-final/report.json
```

本机只允许 `dsha-gradle`。显式合成 ELF 模式标记为 `SYNTHETIC_ELF_REAL_GRADLE` / `PASS_GRADLE_WITH_SYNTHETIC_ELF`，不冒充 Android 库；CI 禁用此模式。报告始终含 `androidLoadTested=false`、`apkAssembled=false`，逐例保留真实退出码、预期错误码、命令、日志和摘要。

[测试候选 workflow](../../../.github/workflows/_node-test-packages.yml) 只在 `prebuilt + debug + unsigned_only + unsigned_artifact_prefix为空` 的编译分支调用 runner：同 run native artifact 下载之后、实际 assemble 之前。CI 必须传原始 `out/rust-native`，且保留 builder 不同源码目录的字节复现 PASS；不重新签发 receipt。执行报告与逐例日志由独立 artifact 留存，失败时也上传。纯签名节点不运行 Gradle。

回归后，原候选路径仍实际执行 debug 目标包和 instrumentation assemble。原有 unsigned / pre-sign / signed 三处完整 APK 内容门禁、库与独立/内嵌 receipt 的字节核对、签名/Manifest/同证书核验继续执行；不能用回归报告代替这些结果。16KB ELF 对齐、JNI 导出集合、同提交/同 run/attempt/workflow SHA、24小时来源时效、锁和预算均沿用生产验证器。

## Android 验证候选

[既有 instrumentation](../../../app/src/androidTest/java/com/deepseekharness/app/util/RustNativeInstrumentation.java) 延续生产 `RustNative.ensureLoaded()` / status / ABI / fd digest 入口、目标 APK→安装目录→映射库字节来源核验。新增 archive 和 PlanView 调用及失败清理探针；不调 host-only `loadFromPath`，不开启全局后端开关。选择 Rust 后的流错误继续向调用方传播，不自动改走 JVM 重读或重写。

保留原 probe 身份 `dsha-rust-android-jni-v1`，新增 `probeEvidenceRevision=2`。完整通过时应观察到：跨 CHUNK 的 gzip roundtrip/头尾/EOF/closed-use，非空大成员坏CRC的 read 期失败，空成员坏头/尾/截断/尾随/双成员的构造期失败，source/target close 与 finish 写失败原异常保持及不重放；每条路径重获4个共享 session 槽，abort/finish 后旧句柄返回 STALE_HANDLE，`archiveSessionCapacityChecks=13`。PlanView 实际 JNI 对照 VALID/[]、INVALID/[21,22,23]、短头 INVALID/[20]，验证 OUTPUT_BOUND/INVALID_ARGUMENT、不改输入/输出哨兵和错误后的新有效校验，`nativePlanCalls=13`；原因输出为实际解析的数组文本。

这覆盖 gzip/JNI 与只读 PlanView 契约，不宣称完整 v5 备份恢复/Provider 链通过，也不宣称 fatal abort/OOM/SIGSEGV 可恢复。Rust 解码构造期可能提前暴露尾部错误、unpack 到EOF仍需 close 释放会话；JNI 按 direct buffer 容量和显式长度处理，不按 position/limit 推断范围。这些既有语义没有改变。

构建与执行必须绑定 main 最终集成后的精确提交。候选仍由现有 `release-production` 入口选择 `dry_run=true`、`test_packages=debug`（或 `debug+dev`）、`rust_native_mode=prebuilt`，并传入准确 `expected_sha`。这段是交 main 的执行说明，本 worker 没有 dispatch。

候选生成后 main 应先核对两 APK 的 SHA、库/receipt、三处内容门禁、签名与目标 Manifest，再按已有具体授权安装隔离 debug 包和同证书 instrumentation。运行命令由同 run 的候选证据生成：

```text
am instrument -w -r \
  -e commit <精确集成SHA> -e runId <同一CI运行ID> -e runAttempt <attempt> \
  -e repository SunsetRNE/DSHA-Sunset-mod \
  io.github.sunsetrne.dsha.debug.test/com.deepseekharness.app.util.RustNativeInstrumentation
```

设备结果须逐项核对 load/ABI/digest/archive/PlanView/失败清理、原生开关前后仍 off、精确来源绑定和临时文件清理。4KB 内核成功不能写成16KB内核验证成功；ELF 16KB 对齐和真机页大小分别记录。

## 本批验证记录

| 类别 | 实际执行结果 |
|---|---|
| 修前实际 Gradle | 基线 `f3f8fc03`，exit1，SourceSet Provider 配置拒绝；未到任务 |
| 修后缺目录实际 Gradle | exit1，实际 `prepareRustNative` 输入校验拒绝缺目录 |
| 首轮完整 Gradle 接线回归 | 实现提交 `75c033b6a1d32ebda753d91a9fd0a4a1f1658908`，14/14 / exit0；显式合成ELF，未组装APK |
| 最终15例（含拒绝后有效重核） | 实现提交 `8d7431a0b23d98741c154f1c4df3633ce971cff1`，15/15 / runner exit0；固定本机堆1024MiB、串行 `dsha-gradle`。真实配置/prepareRustNative/consumer探针，显式合成ELF，`apkAssembled=false` / `androidLoadTested=false` |
| 原产物测试基线 | `test_apk_injection.py` 57 tests / 24.833s / OK，未改旧身份 |
| 最终 workflow / 候选回归 | `test-ci-workflows.py` 145 tests / 31.328s / OK（含57产物、27候选）；package 9、test-package 16项各自exit0，分别计数不叠加前轮。AST比对原17个候选test方法身份与方法体完全一致，新增10项；未删改旧测试 |
| 工具链/vendor/ABI边界 | `check-rust-toolchain.py` / `check-rust-vendor.py` / `check-rust-boundaries.py` exit0：Rust 1.100.0、16 crate / 7,384,177 B；7 源 / 11 JNI符号 / 16 依赖 / 4 buildInputs |
| 预算 | `PASS_BUDGET_ONLY`、`apkVerified=false`、`androidLoadTested=false`；非APK验证 |
| 源码/旧测试清单 | source hygiene exit0；host manifest 264入口通过；未删测试、未缩扫描或改冻结基线 |
| Java/Kotlin 格式与相关边界 | Java1798文件/57批、Kotlin58文件/14批，全量0需格式化；首次Java1处换行需格式化已精确改正。core边界25 tests OK，architecture边界exit0；文档门禁56文档/1340链接/0警告或错误 |
| instrumentation 手工编译 | `/opt/jdk17/bin/javac --release 17 -cp /opt/android-sdk/platforms/android-37.0/android.jar`，探针+7个生产协作者exit0；只证明手工API编译，未执行D8/ART/JNI。另有Android bootclasspath/source-target8的独立API编译exit0，分别记录，不当作Android/Gradle出口 |
| 既有纯JVM测试（手工） | JDK17/JUnit4.13.2，RustBackendsTest/RustDigestTest/RustArchiveCodecTest/PlanViewCodecTest/PlanViewValidatorTest共37项 / 0.125s / OK；加载的是本树生产Java类，不加载任何历史Rust库，不冒充Rust↔JVM原生差分 |
| 主机差分 | 当前 PATH、`~/.cargo/bin`、`~/.rustup` 没有可用 Rust 工具链；本批未重建 host 库。历史 `/tmp/r3host-check` 库与其 schema1 receipt 字节相符，但 crateSha 与本树不符，未加载、不移用为本批证据 |
| 定向Gradle Test | 实际请求 `:app:testStandardDebugUnitTest`、五类 `--tests '*RustBackendsTest' / '*RustDigestTest' / '*RustArchiveCodecTest' / '*PlanViewCodecTest' / '*PlanViewValidatorTest'`，exit1 / 5s / 1任务执行；`prepareBackupAssets` 的固定输入 `app/src/main/assets/dsh-runtime.bin` 本树缺失，尚未到Test，不能计Gradle/JUnit通过。未跳过资产任务、伪造R类或更改测试图；也没有把未到达的AAPT2阶段写成本轮失败原因 |
| source-only Gradle / 主机原生差分 | 未另跑source-only Gradle，不把37项手工纯JVM测试当作source-only或full任务回执；本批无当前源码对应host库，Rust↔JVM原生差分未执行 |
| 完整 Android/Gradle | 新 prebuilt Android ELF 链接、完整APK/签名和设备执行由main安排；本批未执行，未计通过 |

仓库外修前/修后日志：`/tmp/rust-prebuilt-1bc715e8-baseline-gradle.log`、`/tmp/rust-prebuilt-1bc715e8-fixed-missing.log`。首轮14例报告 `/tmp/rust-prebuilt-1bc715e8-gradle/report.json` 与其同目录逐例日志完整保留。最终workflow记录 `/tmp/rust-prebuilt-1bc715e8-final-workflows-r3.log`；输入保护 `/tmp/rust-prebuilt-1bc715e8-report-overlap.log`；手工JDK17编译/37项JUnit/最终探针API编译的完整命令和输出在 `/tmp/rust-prebuilt-1bc715e8-manual-javac-junit-r2.log`。手工首轮仅因漏列 RustGzipOutputStream 依赖编译exit1、尚未执行JUnit，补齐完整生产依赖闭包后exit0；没有修改生产代码或改测试预期。Android bootclasspath探索的 `--system none` 因找不到java.lang exit3也保留为失败，不计通过。原始构建日志和任何设备资料不提交。

最终15例报告 `/tmp/rust-prebuilt-1bc715e8-gradle-final/report.json` 和逐例日志、定向Test日志 `/tmp/rust-prebuilt-1bc715e8-targeted-gradle-test.log` 均完整保留。正式默认off、候选的NOT_RUN状态、全部旧测试和共享受管输入未因本机限制放宽。

## 交付与回退

精确变更域：[Gradle Rust SourceSet](../../../app/build.gradle)、[真实 Gradle runner](../../../tools/rust/check_gradle_prebuilt.py)、[Rust 候选编译步骤](../../../.github/workflows/_node-test-packages.yml)、[instrumentation](../../../app/src/androidTest/java/com/deepseekharness/app/util/RustNativeInstrumentation.java)、[候选回归](../../../tools/rust/test_android_candidate.py) 与本回执。共享文件只改已向 main/A8 协调的 Rust 片段；没有改 settings、source-layout、build-logic、依赖验证元数据、格式清单或受管 JSON。

最终 descriptor/runtimeId 留给 main 在集成树串行核对/生成。本批不重冻结 launcherInputs 或测试基线，不更新 Rust artifact pin。

本地实现按职责分成三笔提交：

| 提交 | 内容 |
|---|---|
| `75c033b6a1d32ebda753d91a9fd0a4a1f1658908` | Provider修复、真实Gradle runner与Rust候选CI增量 |
| `399b6edf5094ebb0d2750dbe51c7fe24905acf77` | Android archive/PlanView/失败清理候选与拒绝后重新stage |
| `8d7431a0b23d98741c154f1c4df3633ce971cff1` | 独立审查发现的报告覆盖输入缺陷修复与CLI负控 |

源码回退顺序：`git revert 8d7431a0`、`git revert 399b6edf`、`git revert 75c033b6`；本回执文档提交可单独回退。每个提交均不含用户数据或迁移。未分发的构建若选择 off，必须使用干净的自有 build 目录；已有 Rust staging 时按设计明确拒绝，不能默默删除或回落 off。不需要清数据、降级安装或更改签名；不修改历史 receipt。
