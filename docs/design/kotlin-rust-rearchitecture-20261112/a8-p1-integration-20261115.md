# A8 P1 串行集成回执（2026-11-15）

状态：**集成候选完成，未 push / 未合并 / 未 dispatch；P1 出口仍未通过。** 本机已通过构建配置、Standard Lint 与宿主门禁；完整新鲜单测聚合和 unsigned APK 内容/身份检查受本机 x86_64 AAPT2 限制无法执行，必须在用户批准后由 main 合入并 dispatch [`release-production.yml`](../../../.github/workflows/release-production.yml) 的 `dry_run` 完成。P2–P7 与首个真实生产模块提取继续锁定。

依据：[A8-P1 工程门禁回执](a8-p1-engineering-20261115.md)、[A8 基线](a8-task-baseline-20261114.md)、[输入差异登记](../../../ci/a8-p1-input-delta.json)。

## 基线与提交

- 开工 `origin/main`：`b3602555b39056279bc5131e7eaefc1a6be114ae`（build366 线；最后发布仍 `5ccc8aa3` / 原 tag）。
- A8-P1 原交付：`cc042273`（base `f9e8d867`，四提交，43 路径；独审 APPROVE）。
- 集成树 `merge/a8-p1`：`1022dd63` → `c6521b8a` → `1ee7da5b` → `a874d770`（A8-P1 四提交 cherry-pick）→ `a61c5b8e`（合成根测试清单）→ `21ca5e1e`（受管身份收口）；本回执再追加一提交。相对 `b3602555` 共 47 路径。
- 保留 main 已合入的 Rust 入包 `prepareRustNative`、contract Kotlin 迁移、M17、S1b、T2 与固定输入/签名门禁；未改主检出。

## 冲突与集成取舍

- `app/build.gradle`：保留 main 的 `rustNativeMode` / `prepareRustNative` / `rust-jniLibs` 装配块；删除 `kotlin { compilerOptions { jvmTarget = JVM_17 } }` 重复块，因为 A8-P1 已把它移入 [`build-logic/android.gradle`](../../../build-logic/android.gradle) 应用的 [`kotlin-target.gradle`](../../../build-logic/kotlin-target.gradle)。`:app:compileStandardDebugKotlin` 实际通过，产物 class major 61。
- [`tools/host-tests.manifest.json`](../../../tools/host-tests.manifest.json)：T2 的 `test-backup-signed-builtin-files.py` 与 A8-P1 的 `test-ci-toolchain.py` 均保留；`test-host-test-runner` 10/10。
- [`tools/test-ci-workflows.py`](../../../tools/test-ci-workflows.py)：自动合并后 135/135；Rust 图、`rust-native`/`rust-differential` 断言保留。
- 追加测试修正 [`tools/test-runtime-descriptor-source.py`](../../../tools/test-runtime-descriptor-source.py)：A8-P1 起启动输入统一经 `SourceLayout` 解析，合成根补最小 `ci/source-layout.json`；生产校验仍 fail-closed。
- 主线 S1b 的 [`WebStopHandoffRecheckTest.kt`](../../../app/src/test/java/com/deepseekharness/app/runtime/WebStopHandoffRecheckTest.kt) 使用 `@get:Rule`；A8-P1 的 Kotlin 声明解析拒绝未登记 use-site 注解，full plan 因此失败。改为等价方法级 `@Rule`（`c988f51c`），测试类/方法身份与 TemporaryFolder 行为不变，plan 已通过；该文件已用 ktfmt 0.64 格式化（`560010cb`）。

## 受管输入、归档与描述符（main 串行）

- [`managed-runtime-inputs.json`](../../../app/src/main/assets/managed-runtime-inputs.json)：`generatorSources` 新增 `ci/source-layout.json`、`tools/source_layout.py`（44→46）。descriptor 的 `launcherInputs` 515→517，仅此两项新增，无删除、无既有值变化。
- [`dsh-runtime.inputs.json`](../../../app/src/main/assets/dsh-runtime.inputs.json)：配方 91→93。用修复后的 Builder 真实重建（`prepare-dsh-runtime.py --offline`，exit0），归档字节仍为 `c1bd68330a10776f304a92a19d8f82c184c1abf523275a9e28dbb2c2e764e4fe`（与重建前一致）；不把旧 91 项证明当新回执。
- 归档配方摘要：`tools/build-dsh-runtime.py` = `2f918843…d4e2a`；`tools/runtime_input_contract.py` = `8176ad1f…2fc0`；`tools/source_layout.py` = `585abbc8…4113`；`ci/source-layout.json` = `4a3ddda2…ac`。
- [`runtime-descriptor.json`](../../../app/src/main/assets/runtime-descriptor.json)：`runtimeId` `a50a81c7…37a0` → `c4f17521…2923`；`baseVersion` 仍 `10`、`dataWrite` 仍 `dsh-0.2.2-rc.1`、`inputs` 仍 205；未提升数据格式。
- `prepareBackupAssets` 655；`prepareStandardAssets` 成功（rootfs 289.95 → 81.81 MiB），生成资产未被提交。

## 本机验证

通过：

| 门禁 | 结果 |
|---|---|
| `:app:compileStandardDebugKotlin` | BUILD SUCCESSFUL；Standard Kotlin class major 61 |
| `test-unit-test-contract.py` / `test-source-layout.py` / `test-build-conventions.py` / `test-ci-toolchain.py` / `test-ci-workflows.py` | 64 / 16 / 25 / 6 / 135 全过 |
| `test-runtime-input-contract.py` / `test-runtime-descriptor-inputs.py` / `test-runtime-descriptor-source.py` | 18 全过 / 通过 / 4 全过 |
| `test-dsh-runtime-packaging.py` / `test-runtime-proof.py` / `test-ci-assets.py` | 6 / 16 / 25 全过 |
| `test-core-boundaries.py` / `test-architecture-boundaries.py` / `test-host-test-runner.py` | 25 全过 / PASS / 10 全过 |
| `test-backup-signed-builtin-files.py` / `test-unit-test-runner.py` | 25 全过 / 6 全过（JDK17 在 PATH） |
| `tools/rust/test_apk_injection.py` / `test_android_candidate.py` | 57 / 17 全过 |
| `check-source-hygiene.py` / `check-ui-i18n.py` | PASS / newFindings=0 |
| `check-document-links.py` | 56 文档 / 1328 链接 / 0 error |
| `format-java-tree.py --check` / `format-kotlin-tree.py --check --offline` | Java 1813/1813、Kotlin 29/29，0 需格式化 |
| `unit_test_contract.py plan` | PASS：777 测试源 / 5197 声明 / 593 资源输入；冻结基线 5107 身份均有声明源覆盖（32 个 `BrowserTasksTest[23]` 是参数化 XML 后缀，不是缺失） |
| `:app:lintStandardRelease`（`-Pdsha.unsignedPackage=true`） | BUILD SUCCESSFUL；617 issues = 609 Warning / 8 Hint / 0 Error |

阻塞（本机环境，不是集成失败）：

- `:app:testStandardDebugUnitTest` 在 `:app:processStandardDebugResources` 失败：`AAPT2 Daemon startup failed`；`:app:compileStandardDebugUnitTestKotlin` 同样在该 AAR transform 前被阻断，`:app:assembleStandardRelease` 在 `Aapt2CompileRunnable` 失败。本机 aapt2 与 Gradle 缓存 aapt2 均为 x86_64（ELF `e_machine=62`），宿主为 aarch64（`183`），daemon 无法启动；仓库 [`tools/run-unit-tests.py`](../../../tools/run-unit-tests.py) 已把该限制写成既有事实。因此 **5107 身份的新鲜 full 聚合、unsigned APK 内容/身份检查未在本机完成**，不写成通过；`lintStandardRelease` 不走该 daemon 路径，已实际通过。
- 主机 Rust 库由 `cargo` 在 `/tmp` 目标目录构建后复制为 `out/rust-host/libdsha_rs.so`（348736 B / `04f07e7a…`）；`rust/artifact.lock.json` 的主机项仍为 `pending-cross-run`（未 pin size/sha），不声称跨 runner 固定。仅用于本机测试，不改变仓库锁。

## P1 出口所需的 CI（待用户批准）

1. 用户批准后 push `merge/a8-p1`，快进 `main`（候选 SHA 以最终回执提交为准）。
2. `release-production.yml` 的 `dry_run=true`：`expected_sha=<候选>`、`release_tag=dsha-a-su-build367-20261115-<HHMMSS>`（dry-run 不建 tag/Release）、`rust_native_mode=off`、`test_packages=none`。该 run 的 `inputs` 以 `rebuild_runtime=true` 重建运行时与描述符；`unit` 走 A8-P1 full 契约，`lint` 走 `lintStandardRelease`，`build-unsigned` 做 APK 清单/受管资产/未签名与 Rust off 检查。
3. 收集 full XML/5107 基线聚合、Lint、unsigned APK 回执；任一步失败都不算 P1 出口。

## P1 出口 CI 首轮与修复（2026-11-15）

- `43705093134`（dry_run、未显式传 `rust_native_mode`）：`bind` 失败；workflow_dispatch 的 `rust_native_mode` 选项 `off` 未被 API 接受。未改 main。
- `43705255618`（head `f9bac8e8`，dry_run、`rust_native_mode=prebuilt`、`test_packages=debug`）：`bind` 与 Kotlin 格式通过；`contracts` 在 `generate-third-party-notices.py --check` 报 `THIRD_PARTY_NOTICES_STALE`；`host-regression` 仅 `test-ci-package.py` 失败，其余宿主测试通过。
- 原因：A8-P1 把 `coreLibraryDesugaring` 移入 [`build-logic/android.gradle`](../../../build-logic/android.gradle)；notices 生成器只扫 `app/build.gradle`，会漏掉 `desugar_jdk_libs`；`ci-toolchain` 从该约定读取 Java 目标，`test-ci-package` 合成根缺该文件。
- 修复：`4c2f9d0f` 让 [`generate-third-party-notices.py`](../../../tools/generate-third-party-notices.py) 扫描 `build-logic/*.gradle` 并补测试（`THIRD_PARTY_NOTICES.md` 重生成无差异）；`a2c87b5b` 给 [`test-ci-package.py`](../../../tools/test-ci-package.py) 合成根补 `build-logic/android.gradle`。本机复验 notices 5/5、ci-package 9/9、ci-toolchain 6/6，完整 contracts 12 项 exit0。
- 同轮 `build-unsigned` / `test-packages` 在 **prebuilt** 路径失败：`app/build.gradle` 的 `if (rustNativeMode == 'prebuilt') android.sourceSets.main.assets.srcDir(rustAssetsDirectory)` 把 `Provider` 加入 Android SourceSet，AGP 9.1.1 拒绝；该行在 `b3602555` 已存在，属已合入 Rust 入包路径的既有缺陷，不是 A8-P1 引入。P1 出口按 off 重跑，prebuilt 缺陷另记 Rust 后续。
- `3d4fed03` 把 `release-production.yml` 的 `options: [off, prebuilt]` / `default: off` 改为带引号的字符串，并同步 [`test-ci-workflows.py`](../../../tools/test-ci-workflows.py)（135/135）；`workflow_dispatch` 现在可按 `off` 运行。
- `unit`（full 契约）在 `:app:testStandardDebugUnitTest` 的 task-begin 退出 1；job log 未带出 Python stderr。通过 API+token 取得 `release-unit-results` artifact（绕过旧 ZIP 403）：run/plan/proof 与 4 个 compile-execution receipt。receipt 含 `:app:compileStandardDebugJavaWithJavac`（1059 源）及主 Kotlin/测试 Java/测试 Kotlin；但 compile proof 只有 3 行，缺主 Java。validator 的 :app 主 Java 上下文会以 `COMPILE_TASK_MISSING` 拒绝（CI stderr 未随日志输出；本机对 CI proof 复验先因本地 BuildConfig 生成差异在 `COMPILE_FILE_CHANGED` 停下，未把该本地差异算作 CI 结果）。
- `165bce6b` 在 proof 生成中补入同一 state 内已执行且有 receipt 的本模块 Compile 任务，保证主 Java 编译行入证，不放宽源码/编译证明。该 unit 修复待用户批准追加。
- 三处修复（notices `4c2f9d0f`、ci-package `a2c87b5b`、off `3d4fed03`）已获用户批准（将推送 `1d8b3c39`）；unit 修复 `165bce6b` 待批准；当前 main 仍为 `f9bac8e8`，P1 出口未通过。

## 边界与回退

- 未 push、未合并、未打 tag、未发 Release、未 dispatch CI、未安装、未操作手机；未把其它会话的批准扩大解释。
- SAF #1、transfer #2 等迁移候选须在 A8-P1 落地后 rebase 到新 main 并重算受管身份；本候选未带入它们。
- 回退：按依赖逆序 revert 本候选追加提交与四个 A8-P1 提交；受管 JSON、`dsh-runtime.inputs.json`、descriptor 同批回退并重算，安装用户回退由 main 安排更高 versionCode，不能降级安装或清数据。
