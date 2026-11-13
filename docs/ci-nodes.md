# CI 节点图（GitHub Actions）

更新：2026-11-13 / R1（Rust `digest` 旁路 + 真实 JVM 差分；`_node-rust.yml` 四种模式与 `--expect-lock`）。本文说明 `.github/workflows/` 的节点拆分、
复用方式与耗时依据；正式发布的参数、Secrets 与产物核验仍以 [CI 构建与发布](ci-release.md) 为准、Rust 设计与冻结决定见
[R1 Rust 工具链与首期旁路接入方案](design/kotlin-rust-rearchitecture-20261112/r1-rust-toolchain-plan-20261113.md)。

## 1. 结构

可复用节点（`workflow_call`，只读、不读 Secrets、不设并发组）：

| 节点 | 作用 | 产出 |
|---|---|---|
| `_node-contracts.yml` | 描述符、声明、内置插件、凭据路径、测试清单、源码整洁、i18n、changelog、文档链接 | — |
| `_node-java-format.yml` | `tools/format-java-tree.py --check`（失败时列出文件并上传报告） | `java-format-reports`（仅失败） |
| `_node-kotlin-format.yml` | `tools/format-kotlin-tree.py --check`（固定 ktfmt 0.64；失败时列出文件并上传报告） | `kotlin-format-reports`（仅失败） |
| `_node-inputs.yml` | 成对解析固定输入 → `ci-assets.py` 按 SHA-256 还原 → 可选重建受管运行时并重算描述符 → `ci-handoff.py pack` | 交接 artifact（无压缩、1 天） |
| `_node-unit-tests.yml` | 先按 `tools/robolectric-sdk.lock.json` 准备 android-all（缓存 + 逐个 sha256 核对 + 有界重试），再以 `robolectric.offline` 跑 `:app:testStandardDebugUnitTest`，`full` / `source-only`，可选 `--tests` 与 fork 数；R1 新增 `rust_host`：`full` 调用方先构建主机目标 `.so`，再以 `-Pdsha.rust.hostLib` + `-Pdsha.rust.required=true` 让 Rust 差分测试真实执行（0 skipped） | JUnit XML + `out/unit-tests.json` |
| `_node-lint.yml` | `:app:lintStandardRelease`（未签名模式） | lint 报告 |
| `_node-host-tests.yml` | `run-host-tests.py` 的一个阶段或 `--only` 子集；可选夹具、toolchains、Playwright | `app/build/host-tests` |
| `_node-rust.yml` | 单一 job、按 `mode` 切换（R1）：`check` 源码门禁 + 主机 `.so` 符号 + `--expect-lock`；`repro` 有网重生成 vendor + 不同目录字节复现；`native` NDK 26.3 交叉编译 arm64 `.so` + 同 run 两次构建比对 + ELF/16K/体积/receipt + `--expect-lock`；`differential` 主机 `.so` + JDK17 JVM 真实 digest 对拍（`-Xcheck:jni`） | `native` 上传 `.so` + `rust-receipt.json`；`differential` 上传差分回执（均 14 天） |
| `_node-test-packages.yml` | 三种模式：默认（构建 + 测试证书签名 + 逐项核对）；`unsigned_only`（无 Secrets 编译、核验未签名身份 / 受管资产并交接）；`unsigned_artifact_prefix`（下载同 run 未签名产物，只签名与核对，不跑 Gradle） | 每个 variant 的 APK + 证据 artifact（含公开测试证书），或未签名交接 artifact |

`_node-*.yml` 默认只读、不读 Secrets；唯一例外是 `_node-test-packages.yml`：证书模式需要测试证书 Secrets
（`SUNSET_TEST_KEYSTORE_B64` / `SUNSET_TEST_STORE_PASSWORD`），正式签名 Secrets 仍然绝不进入。
持证书的节点不写 Gradle 共享缓存；只有 `unsigned_only`（不接收任何证书 Secrets）允许缓存，
表达式直接把 `gradle-cache` 绑定成 `inputs.unsigned_only`，调用方无法既传证书又写缓存。

组合 action（`.github/actions/`）：`setup-toolchain`（锁定 JDK/Node + 可选 Gradle 依赖缓存）、
`android-sdk`（锁定 platform/build-tools，可选 NDK；先按实际文件判断是否已预装，缺失才下载，
`timeout 600` 且最多 2 次重试，防止 build353 第 2 次 run 的 sdkmanager 44 分钟挂起）、
`restore-handoff`（下载交接、核对后写入、可选准备 raw/managed 夹具）。

交接（[ci-handoff.py](../tools/ci-handoff.py)）只包含 `ci-assets.py` 白名单与两份重算文件
（`managed-package-proofs.json`、`runtime-descriptor.json`）；打包时拒绝白名单外的已跟踪源码改动，
解包时核对 commit、成员集合与逐文件 SHA-256，全部通过后才覆盖工作区。

## 2. 入口工作流

| 工作流 | 触发 | 节点 |
|---|---|---|
| `ci-fast.yml`（Source checks） | push / PR / 手动 | contracts ‖ java-format ‖ kotlin-format ‖ rust-check ‖ rust-differential ‖ inputs → (unit-source ‖ host-fast) |
| `release-production.yml` | 手动，`main` | bind → (contracts ‖ java-format ‖ kotlin-format ‖ host-regression ‖ inputs → (unit ‖ lint ‖ rust-native ‖ build-unsigned ‖ test-packages-unsigned) → rust-differential) → (sign-verify ‖ test-packages) → publish；`dry_run=true` 时 publish 跳过、由 dry-run-summary 出报告；R1 的 `rust-native` 仍不进入 `build-unsigned`（R1 只接桥与差分，不接 APK/生产调用点） |
| `ci-package.yml` | 手动，显式输入包 | inputs → (native-repro ‖ rust-repro ‖ rust-native ‖ package) |
| `unit-tests.yml` | 手动 | inputs → unit（可选 `--tests`、`source-only`、fork 数） |
| `host-tests.yml` | 手动 | inputs → host（`fast` / `runtime`，可选 `--only`、Playwright） |
| `test-package.yml` | 手动 | contracts ‖ inputs → (patch-chains ‖ unit ‖ lint) → package-sign |
| `docs-check.yml` | 文档路径 push / PR / 手动 | links ‖ changelog ‖ i18n |
| `inputs-health.yml` | 每周一 03:17 UTC / 手动 | 下载并核对锁定的固定输入，不写任何东西 |

发布链的安全边界：正式签名 Secrets 只出现在 `sign-verify`，测试证书 Secrets 只出现在 `test-packages`；
两个节点都不写 Gradle 共享缓存、也不在签名阶段运行 Gradle。构建节点（`build-unsigned` /
`test-packages-unsigned`）没有任何 Secrets，只依赖 bind + inputs，与门禁并行，因此可以读写共享缓存。
`contents: write` 只在 `publish`，它不 checkout、不签名，只核对
`SHA256SUMS` 与 `build-info.json` 的 commit/run 绑定，合并（可选）测试包证据后创建 tag 与 prerelease。
任一前置节点失败都不会签名；未签名产物以 `unsigned-receipt.json` 绑定 commit/run/摘要，签名节点复核后才签名，
签名后再逐成员证明载荷未被改动。签名包与测试包证据经 1 天保留的 artifact 交给 `publish`，正式交付物以 Release
资产为准；`dry_run=true` 时 `dry-run-summary` 用同一套证据合并与校验并打印发布计划，不写仓库。

### Rust 工具链节点（R1）

`_node-rust.yml` 是一个 job、四种 `mode`；调用方 job id 分别是 `rust-check` / `rust-repro` / `rust-native` /
`rust-differential`。四者都只读源码、无 Secrets、不写仓库，`.so` 只作为 artifact 上传：

| mode | 网络 | 做什么 | 产物 |
|---|---|---|---|
| `check` | 否（vendor 离线） | `cargo fmt --check`、`clippy -D warnings`（含 `unwrap_used`/`expect_used`）、`cargo test --offline --locked`、边界扫描 + 主机 `.so` 符号白名单 | 无（仅日志） |
| `repro` | 是 | `cargo vendor --locked --versioned-dirs` 从 crates.io 重生成后与入库树逐字节比对；主机目标两次构建（不同源码目录 + 固定 `--remap-path-prefix`）字节一致 | receipts（日志） |
| `native` | 是（NDK 26.3） | 交叉构建 `aarch64-linux-android` 的 `libdsha_rs.so`；同一 run 内两次构建字节比对；ELF AArch64/16 KB/RELRO/DT_NEEDED/体积/receipt | `libdsha_rs.so` + `rust-receipt.json` |
| `differential` | 是（JDK17 已由 setup-toolchain 提供） | 主机目标 `.so` + `tools/rust/host-jvm-differential.py`（`-Xcheck:jni`）；**R1 起直接编译生产桥 `RustNative`/`RustDigest`/`RustBackends` 并与 JDK `MessageDigest` 对拍**：SHA-256/SHA-1、offset/跨块/32 MiB、空内容拒绝、EOF 短读、非法 offset/length/algorithm、负 fd/巨大 fd/只写 fd/关闭 fd、非 direct/过小 direct 输出、运行层开关默认 off 与一键回退。CI `rust-differential` 现在每次 push 都真实执行（不再只在发布链手动运行） | `rust-differential.json`（含逐项 check + 0 skipped/failures 计数） |

边界：**R1 仍不把 `rust-native` artifact 接进 `build-unsigned`**，`build-unsigned` 的 `needs` 仍是 `[bind, inputs]`，
APK 内没有 `libdsha_rs.so`；`tools/check-rust-native-apk.py` 只作为 APK 级门禁骨架（只跑预算配置或显式
`--rust-optional`）。Gradle 侧只在 `app/build.gradle` 增加 `main.jniLibs.srcDir(build/rust-jniLibs)` 注入目录，
目录不存在时 APK 内容不变；`abiFilters`/`ndkVersion`/签名/版本不动。

`_node-unit-tests.yml` 的 `rust_host`：`release-production` / `release-upstream` / `test-package` / `test-package-variants`
的 full 单测与手动 `unit-tests.yml`（mode=full）先构建主机 `.so` 并传 `-Pdsha.rust.hostLib` +
`-Pdsha.rust.required=true`；source-only 的 `unit-source` 不构建，差分用例按 `Assume` 跳过（source-only
模式允许跳过）。这样 full 的 0 skipped 门禁不需要放宽。

`rust/artifact.lock.json`（R1 新增）：只登记可由 (源码 + `Cargo.lock` + vendor + rustc + target + linker +
linkArgs + profile) 重现的 `libdsha_rs.so` 大小/SHA-256；commit/runId/路径/mtime 等跨 runner 不可复现字段
不入锁。target 键是三元组：Android 固定 `aarch64-linux-android`，宿主按 `rustc -vV` 的 host 分开登记。
按方案 §2.3，`status=pending-cross-run` 时 `--expect-lock` 只告警；连续 3 次不同 runner 实例一致后改为
`pinned` 并写入数值，此后不一致即构建失败（R1 首轮 CI 证据出来前不写死数值）。

离线边界（如实）：工具链与 NDK **不在仓库**，`cargo build --offline --locked` 只保证 crate 来源是入库 vendor；
从零、完全断网的新机器不能构建。CI 的 `native`/`repro`/`differential` 模式需要网络安装工具链/NDK。

稳定性：所有 cargo 命令设 `CARGO_BUILD_JOBS=2` 且各 job 串行构建；本机验证时同样一次只跑一个目标
（host 与 aarch64 不并行），构建结束不残留 cargo/Gradle daemon（Gradle 走 `dsha-gradle`）。

`test-package.yml` 产出 `io.github.sunsetrne.dsha.test`（`-SunsetTest`）。配置 `SUNSET_TEST_KEYSTORE_B64`
（可选 `SUNSET_TEST_STORE_PASSWORD`）时使用该测试证书，便于覆盖升级；否则每次生成一次性密钥。证书不得等于正式证书。

## 3. 耗时依据与压缩

拆分前（build274，run `41450649159` / `41450644843`）：发布 28.1 分钟、Source checks 25.8 分钟，均为单 job 串行。
最大单项是 Java 格式门禁：1385 个文件逐个起两个 JVM（格式化 + token 校验），约 2770 次冷启动，
发布中 895 秒、Source checks 的 contracts 步骤 941 秒。

本次调整：

- `format-java.py` 每批只起一个格式化 JVM（在 `app/build` 私有副本上 `-i`）和一个 token JVM（`--pairs`），
  批次间并行（`--jobs`，默认 min(4, CPU)）；写入模式仍逐批串行。输出与逐文件 stdin 模式逐字节一致
  （40 个打乱文件含 CRLF/去缩进/合并行对照），本机全树 70 秒（原约 15 分钟）。解析失败仍逐文件定位。
- 节点并行：门禁、格式、宿主回归与固定输入同时开始；单测与 Lint 并行。
- `setup-java` Gradle 依赖缓存：依赖字节仍由 `gradle/verification-metadata.xml` 核对；签名节点不使用缓存。
- 单测 fork：`ci-fast` 与手动单测默认 2 个 fork；发布保持 1 个 fork，等 CI 证据稳定后再决定是否放开。

### 实测基线（build356 正式发布 run `42799176202`，成功，15:05）

| 节点 / 步骤 | 耗时 | 说明 |
|---|---|---|
| bind | 10 s | tag / commit / 输入锁核验 |
| inputs | 97 s | 固定输入还原 6 s、受管运行时重建 65 s、打包与上传 9 s |
| contracts / host-fast / java-format | 23 s / 38 s / 124 s | 与 inputs 并行 |
| kotlin-format | 未在本机 CI 实测 | 首次含约 71 MB ktfmt 下载，命中 actions/cache 后只跑格式化；失败列出文件 |
| unit（full） | 7:02 | NDK 安装 24 s、交接还原 41 s、测试本体 5:35，要求 0 failure / 0 skip |
| lint | 3:41 | SDK 24 s、交接 45 s、lint 2:09 |
| build-signed | 5:29 | assemble 4:13（含 Gradle 冷依赖 + v1/v2/v3 签名）、证据打包 11 s、artifact 上传 20 s |
| test-packages（并行） | 5:18 / 5:06 | dev / debug 各自构建 3:19 / 3:28、签名 16–19 s、上传证据约 10 s |
| publish | 35 s | 下载 bundle 13 s、合并、创建 tag 与上传 |

关键路径 = bind 10 s + inputs 97 s + unit 7:02 + build-signed 5:29 + publish 35 s ≈ 15:05。

### T141 拆分后的预计（首次 run 缓存 miss / 其后稳态）

- `build-unsigned` 与 `test-packages-unsigned` 在 inputs 完成后立即开始，与 unit / lint 并行；
  构建节点无 Secrets，可复用 `setup-java` 的 Gradle 依赖缓存（稳态命中），并省掉构建期 v1/v2/v3 签名
  （移到签名节点）。
- 门禁结束（unit 7:02）后：`sign-verify` ≈ 下载未签名 APK + 签名（v1+v2+v3）+ 复验 + 证据 + 上传 ≈ 1:30–2:10；
  `test-packages` 只签名 ≈ 1:00–1:30（并行，不占关键路径）。预计总时长 **10:30–11:10**（−4 分钟，约 −27%）。
- 证据包 298 MB → 约 13 MB（不再重复内嵌 APK）；release-bundle artifact 约 614 MB → 约 330 MB，
  publish 下载与上传各快约 5–7 s。
- 首次 run（Gradle / npm 缓存未命中）收益预计少 30–60 s；`inputs` 的固定输入还原实测仅 6–15 s，
  因此没有为 684 MB 固定输入加 actions/cache（避免 10 GB 配额与 700 MB 缓存读写倒挂）。
- 下一瓶颈是 `unit`（7:02，单 fork）：`forks=2` 与 Gradle `--build-cache` 是后续候选，
  需要单独验证测试隔离与构建缓存正确性，本次不实施。

Robolectric android-all 不经 Gradle 依赖解析，原先在单测中途从 Maven 临时下载，runner 网络抖动时整批 sdk=N 测试类以 `MavenArtifactFetcher` IOException 失败（build273、build277 正式发布各一次）。现由单测节点在测试前准备锁定的 5 个 jar；缺失或摘要不符时单测前即失败，单测中不再联网取 android-all。锁定新 SDK 时同时更新锁文件里的 `size` 与 `sha256`，并与 Maven Central 的 `.sha1` 交叉核对。

## 4. 已删除

私有仓库 `DSHA-Sunset-mod-3090-Q` 专用的 `sunset-3090-q.yml`、`sunset-release.yml`、`terminal-regression.yml`
及 `sunset-release-delivery.py`、`sunset-plugin-release.py`、`ci/terminal-package.py`、`verify-terminal-test-scope.py`
与对应测试已于 2026-11-02 删除，历史记录见 [终端 CI 合同](terminal-ci-20261025.md)。
`tools/sunset-ci-inputs.py` 与 `ci/sunset-inputs.lock.json` 仍是当前固定输入的解析入口，保留。

## 5. 契约测试

- [test-ci-workflows.py](../tools/test-ci-workflows.py)：节点集合与依赖、Secrets/写权限隔离、外部 action 固定、Standard 范围；R1 断言四个 Rust 模式、`--expect-lock` 接线、`rust-differential` 进入 Source checks、`artifact.lock.json` 口径（target 键/状态/不写不可复现字段）、`build-unsigned` 不被接入、Rust 节点无 Secrets。
- [test-ci-handoff.py](../tools/test-ci-handoff.py)：交接白名单、commit 绑定、篡改与路径穿越拒绝；单测回执的七夹具与计数核对。
