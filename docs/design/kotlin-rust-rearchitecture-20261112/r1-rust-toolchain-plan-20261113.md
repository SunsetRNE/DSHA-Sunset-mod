# R1 Rust 工具链与首期旁路接入方案（A3 前期设计）

> 状态：**R0 已合入 `main`（`75924add`，build362）；R1 已实施（`feat/rust-r1`：`digest` 只读摘要 + JVM 桥/差分/一键回退，默认关闭、不接生产调用点；不可合并，待 Lead 逐行评审、非实现者评审与用户授权）**。设计评审完成，Q1–Q6 已决。日期：2026-11-13（Lead 决定 2026-11-13，见 §0；R0 回执见 §0.4、R1 回执见 §0.5）。
> 基线：`origin/main` = `2c879f1b`（build360）；起草期间主线已推进到 `faab36c5`（build361），差异仅为版本号与 A1 文档，`app/build.gradle` 的 NDK/ABI/jniLibs 段无变化（本文件所有实测数字仍以 `2c879f1b` 为准）。
> 依据：[ADR-0004 决定四/决定二/失败域矩阵](../../adr/0004-kotlin-rust-direction.md)、[可行性评审主稿 §6](README.md)、[评审 B 草稿](drafts/02-rust-core.md)、[迭代路径 §4/§5/§8/§9](iteration-roadmap-20261112.md)、[AGENTS 技术约束](../../../AGENTS.md)、[BUILD](../../../BUILD.md)、[CI 发布](../../ci-release.md)、[固定构建输入](../../fixed-build-inputs.md)。
> 边界：本文只写设计与实测依据。**不修改构建文件、代码、CI、版本文件、文档索引**；索引与看板条目在回报里交给 Lead 统一添加（避开 build361 冲突）。本文件**不等同于 A3 开工或 A3 出口**。

---

## 0. 先决决定与 R0 解锁条件（2026-11-13 Lead 答复后冻结）

> 本节优先于正文里“待 Lead 决定”的占位。正文保留理由、实测与实施细节；若两者冲突，以本节为准。

### 0.1 决定表（Q1–Q6，Lead 2026-11-13）

| # | 决定 | 结论 | 对本文的影响 |
|---|---|---|---|
| Q1 | 产物边界 | **接受**：`.so` 走 `app/build/rust-jniLibs` 注入 + 独立 APK receipt/体积/字节门禁；**摘要不进 `runtime-descriptor`**，不让每次 Rust 变更触发维护页。理由：既有边界是“原生库随 APK 走、受管运行时只管启动树” | §3.3 的推荐方案成为定稿；备选“摘要进描述符”不采用 |
| Q2 | 本机交叉链 | **接受**：R0 证据 = CI 构建 + 本机 `cargo check --target aarch64-linux-android` + 主机 JVM 差分 + 真机加载签名 `.dev` 冒烟；**暂不做** rust-lld/qemu spike（1 人日留给 A3 真实缺口时再评估） | §2.6 路径 A 为默认；B/C 暂缓，不在 R0 范围 |
| Q3 | vendor/许可 | **有条件批准**：16 crate / 7.4 MB 可入库；条件见 §0.2；R0 提交由 Lead 逐行评审 `rust/` 与 CI diff | §0.2 新增 vendor 清单、build.rs 审查结论、profile/ABI 锁 |
| Q4 | 默认开关 | **按建议**：R0/R1/R2 默认关；R3 灰度验证后 A3 出口转 on | §5.4 定稿 |
| Q5 | 真机冒烟 | **按建议**：R3 用签名 `.dev` 包；用户已授权手机操作（一加 12 已连接），由 Lead 执行 | §6 R3 验收、§5.1 设备层定稿 |
| Q6 | `verify_plan` 范围 | **按建议**：首期绑定 A3 恢复计划（`NativeRestorePlan` 的规范化投影） | §4.6/§6 R2 定稿 |

### 0.2 Q3 条件的落地内容

#### 0.2.1 vendor 清单与许可摘要（2026-11-12 按 `cargo vendor --locked` 实测）

依赖集：`flate2`（`rust_backend`）+ `sha2` + `sha1`，解析出 16 个 registry crate；vendor 树 **782 文件 / 7,384,177 B**（约 7.0 MiB；`du -sh` 磁盘占用约 11 MiB）。R0 必须在 `Cargo.lock` 中锁定同一版本集；若解析结果变化，重新走本节审查，不得直接放行。

| crate | 版本 | 上游许可（`cargo metadata`） | vendor 内许可文件 |
|---|---|---|---|
| `adler2` | 2.0.1 | `0BSD OR MIT OR Apache-2.0` | `LICENSE-0BSD`、`LICENSE-APACHE`、`LICENSE-MIT` |
| `block-buffer` | 0.10.4 | `MIT OR Apache-2.0` | `LICENSE-APACHE`、`LICENSE-MIT` |
| `cfg-if` | 1.0.5 | `MIT OR Apache-2.0` | `LICENSE-APACHE`、`LICENSE-MIT` |
| `cpufeatures` | 0.2.17 | `MIT OR Apache-2.0` | `LICENSE-APACHE`、`LICENSE-MIT` |
| `crc32fast` | 1.5.5 | `MIT OR Apache-2.0` | `LICENSE-APACHE`、`LICENSE-MIT` |
| `crypto-common` | 0.1.7 | `MIT OR Apache-2.0` | `LICENSE-APACHE`、`LICENSE-MIT` |
| `digest` | 0.10.7 | `MIT OR Apache-2.0` | `LICENSE-APACHE`、`LICENSE-MIT` |
| `flate2` | 1.1.11 | `MIT OR Apache-2.0` | `LICENSE-APACHE`、`LICENSE-MIT` |
| `generic-array` | 0.14.7 | `MIT` | `LICENSE` |
| `libc` | 0.2.191 | `MIT OR Apache-2.0` | `LICENSE-APACHE`、`LICENSE-MIT` |
| `miniz_oxide` | 0.9.1 | `MIT OR Zlib OR Apache-2.0` | `LICENSE`、`LICENSE-APACHE.md`、`LICENSE-MIT.md`、`LICENSE-ZLIB.md` |
| `sha1` | 0.10.7 | `MIT OR Apache-2.0` | `LICENSE-APACHE`、`LICENSE-MIT` |
| `sha2` | 0.10.9 | `MIT OR Apache-2.0` | `LICENSE-APACHE`、`LICENSE-MIT` |
| `simd-adler32` | 0.3.10 | `MIT` | `LICENSE.md` |
| `typenum` | 1.20.1 | `MIT OR Apache-2.0` | `LICENSE`、`LICENSE-APACHE`、`LICENSE-MIT` |
| `version_check` | 0.9.5 | `MIT/Apache-2.0`（写法需规范化） | `LICENSE-APACHE`、`LICENSE-MIT` |

落地要求：
- `THIRD_PARTY_NOTICES.md` 增加“Rust vendor”段：上表逐 crate 的版本/许可/来源（crates.io + 版本）+ 指向 `app/src/main/assets/licenses/rust/**` 的许可全文；`tools/generate-third-party-notices.py` 从 `Cargo.lock` + vendor 元数据生成，`--check` 必须能复现。
- 许可全文按 crate 目录保存到 `app/src/main/assets/licenses/rust/<crate>-<version>/`（Apache-2.0 的 `NOTICE` 若上游有则一并保留）；`verify-third-party-apk.py` 增加“APK 内 `lib/arm64-v8a/libdsha_rs.so` 必须在上表/notice 中有条目”的核验。
- `version_check` 的 `MIT/Apache-2.0` 归一化为 `MIT OR Apache-2.0` 后再进白名单；不许把“宽松许可”扩写成“可删许可文本”。

#### 0.2.2 三个 `build.rs` 的逐项审查（结论：可用）

审查对象是 R0 拟入库的 16 crate 中全部 `build.rs`（另有 `version_check` 被间接调用，其一并审查）。行为审查只覆盖构建期，不涉及运行时 ABI。

| 文件（crate/版本） | SHA-256（2026-11-12 解析版本） | 构建期行为 | 结论 |
|---|---|---|---|
| `crc32fast/build.rs`（1.5.5） | `deb6052c4a586e8875ef677fe2d9e9dcfafebfa949803455ea8eff6e7dbde436` | 读 `RUSTC`；执行 `rustc --version` 探测 minor；按版本输出 `cargo:rustc-cfg`/`check-cfg` | 无网络、无文件写、无凭据读取；仅探测编译器版本 |
| `generic-array/build.rs`（0.14.7） | `08fa30c4a2c1ad24fe5f987e721dfb20131f45ea5b5dc3e836dcf88a8e33248c` | 调 `version_check::is_min_version("1.41.0")`，后者执行 `rustc --version --verbose`；输出一个 cfg | 同上；间接命令为 `RUSTC`（见下） |
| `libc/build.rs`（0.2.191） | `e70593206df4d2a3138eab463567e0ae8f91c1bea565da4ab5370ea8986840ac` | 读 `RUSTC`/`RUSTC_WRAPPER`、`CARGO_CFG_*`、`CARGO_FEATURE_*`、`RUST_LIBC_UNSTABLE_*`；执行 `rustc --version`；FreeBSD 目标才执行 `freebsd-version`，Emscripten 目标才执行 `emcc`；仅输出 cfg | 无网络、无文件写、无凭据读取；Android 目标不触达 FreeBSD/Emscripten 分支 |
| `version_check/src/lib.rs`（0.9.5，间接） | `01bb86088ba281d511ae002aa939bb30b747f47ace5ea13a46de554a3117806e` | 构建期只执行 `rustc --verbose --version`；`File::open`/`fs::metadata` 位于 `#[cfg(test)] mod tests`，不进入构建 | 同上；测试代码不参与 R0 构建产物 |

审查结论与 R0 约束：
1. **不得网络**：三个 `build.rs` 与 `version_check` 未使用 `std::net`/HTTP 客户端/URL 请求（仅有文档注释里的链接）。
2. **不得写仓库外**：未发现 `File::create`/`fs::write`/`OpenOptions`/目录创建；只向 stdout 输出 `cargo:` 指令，cargo 自身写 `target/` 与指纹（`target` 位置由构建入口指定，CI 仍在工作区内）。
3. **不得读凭据**：只读 `RUSTC`/`RUSTC_WRAPPER`/`CARGO_CFG_*`/`CARGO_FEATURE_*`/`RUST_LIBC_UNSTABLE_*`/`WIND_RELEASE_ID` 等构建变量，未读取 `HOME`、token、keystore、SSH 等凭据路径。
4. **`RUSTC_WRAPPER` 是唯一外部命令入口**：由构建环境提供；R0 的 `tools/rust/build.py` 显式设置 `RUSTC` 为锁定 rustc，并在 CI 清除 `RUSTC_WRAPPER`（不设置或置空），`tools/check-rust-boundaries.py` 把“vendor build.rs 清单 + SHA-256 + 环境变量集合”写入 gate；出签名节点不跑 Rust、不暴露任何签名 Secrets。
5. **R0 提交给 Lead 逐行评审**：`rust/` 与 CI diff；审查不通过不得进入 R1。

#### 0.2.3 R0 即锁定的构建约束（R3 前不改 ABI）

R0 必须在 crate 里锁定以下内容，并写进 `tools/rust/build.py` 与 `tools/check-rust-boundaries.py`：

```toml
# rust/Cargo.toml（workspace）
[profile.dev]
panic = "unwind"

[profile.release]
opt-level = "z"
lto = "fat"
codegen-units = 1
panic = "unwind"          # 禁止 abort：Android 上 abort 会杀整个 App 进程
strip = "symbols"
```

- **ABI 冻结**：`ABI_VERSION = 1`、§4.2 的 Java 方法名/参数/返回类型、错误码表、§4.6 `PlanView` v1 布局、§4.5 gzip 语义在 R0 锁定；R1/R2/R3 不得改；任何变化 = 新 ABI 版本 + 新评审（不是“顺手加参数”）。
- **16 KB 页对齐**：链接参数固定为 `-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=4096 -Wl,-z,relro -Wl,-z,now -Wl,-z,noexecstack`；`audit-standard-apk.py` + 新 APK 门禁逐 `PT_LOAD` 校验；参数改动必须评审。
- **工具链**：`rust-toolchain.toml` 固定 1.100.0；R0 不升级；升级另立评审并重跑复现/差分。
- **构建回执**：receipt 记录 profile 值、链接参数摘要、vendor/Cargo.lock 摘要、导出的符号集合；CI 和 APK 门禁比对。

### 0.3 R0 解锁条件（当前锁定）

- **Kotlin 工具链批次合入 `main` 后解锁**：`feat/kotlin-toolchain` 正在同批修改 `.github/workflows/*` 与 `app/build.gradle`；Rust R0 现在开工会与之撞车，因此**R0 暂不开工**。
- 解锁动作：Kotlin 批次合入且 CI 绿 → Lead 另派**新的**工作树实施 R0（不复用本设计工作树）；R0 先 rebase 到最新 `main`，重读 §2.5/§3.2 的集成点与 `tools/test-ci-workflows.py` 期望集合。
- 解锁前本批次不产生 `rust/` 目录、不提交 vendor、不改 workflow、不改 `app/build.gradle`；本文件只作为 R0 的施工单。
- 本设计工作树在本次交付后改为 `.SESSION-DONE`，状态写明“设计稿完成，其余结论已定，不可合并，待 Lead 在 Kotlin 合入后统一安排 R0 实施”。

### 0.4 R0 实施回执（2026-11-13，`feat/rust-r0`）

> 工作树：`.agents/worktrees/feat/rust-r0`（基线 `1fbdff34`）。提交：`07ad83a7`（crate/vendor/工具链锁）、
> `33af13c3`（构建/复现/边界/APK 门禁 + JVM 探针）、`6b472d31`（许可全文与 notices）、
> `7f54608e`（CI 节点/接线 + docs/ci-nodes）；末尾文档提交记录 CI 回执。**不可合并**：待 Lead 逐行评审、
> 非实现者评审与用户授权；未 push main、未打 tag、未安装。

已完成的本地证据（2026-11-12/13，本机 aarch64 Android 容器）：

| 项 | 结果 |
|---|---|
| vendor | 16 crate / 782 文件 / `7,384,177 B`（`du -sb`），与 §0.2.1 清单逐版本一致；3 个 `build.rs` + `version_check` 的 SHA-256 与 §0.2.2 一致 |
| 工具链 | rustc/cargo `1.100.0`（commit `0ef1bc386` / `3acc79c0c`），`--target aarch64-linux-android` 已装；`cargo check --target aarch64-linux-android` PASS |
| 构建 | `tools/rust/build.py --host` PASS；`cargo build/test/fmt/clippy` PASS（6 个单元测试，0 failed） |
| 离线 vendor | 空 `CARGO_HOME` + `--offline --locked` 全量构建 PASS（仅依赖入库 vendor） |
| 字节复现 | `--check`：仓库目录与仓库外副本（不同源码目录）各构建一次，`--remap-path-prefix` 后 **逐字节一致**；宿主 `.so` 250,384 B / SHA-256 `c3dea827936f69dcd4aab179c9f9d21ac03a554964a03fabf9085c974150f81a` |
| 主机 JVM | JDK 17.0.21 + `-Xcheck:jni`：`System.load` → `abiVersion()=1`、`archivePackCreate(6)=-26`（NOT_IMPLEMENTED），3 项 PASS |
| 边界 | 源码禁区/unsafe 白名单/依赖白名单/构建输入字节与 env 集合/产物 11 个导出符号白名单 PASS |
| 许可 | `assets/licenses/rust/**` 34 文件 / 170,896 B，字节与 vendor 一致；`generate-third-party-notices.py --check` 与 `check-third-party-notices.py` PASS |
| Gradle | 仅 `sourceSets.main.jniLibs.srcDir(build/rust-jniLibs)`；`dsha-gradle :app:help` BUILD SUCCESSFUL（配置校验，未打包），随后停 daemon |
| 仓库门禁 | `check-source-hygiene.py`、`test-ci-workflows.py`（55）、`check-document-links.py` PASS |

CI（主题分支 push）：Source checks run
[`43326450467`](https://github.com/SunsetRNE/DSHA-Sunset-mod/actions/runs/43326450467)（commit `7f54608e`）
7 个 job 全绿：contracts / java-format / kotlin-format / rust-check / inputs / unit-source / host-fast；
rust-check 的工具链安装、静态门禁、源码门禁（fmt/clippy/test/边界 + 主机 `.so` 符号）均为 success。
Documentation checks run
[`43326450435`](https://github.com/SunsetRNE/DSHA-Sunset-mod/actions/runs/43326450435) 3 job 全绿。

**明确未跑（不得当作通过）**：① 本机交叉链接（官方 NDK 仅 linux-x86_64，本机无 qemu/binfmt，按 §0.1 Q2 不强制）；
② CI 的 `rust-native`/`rust-differential` 只在 `ci-package`/`release-production` 手动流程运行，本次 push 未触发，
arm64 `.so` 的首次真实链接、`DT_NEEDED` 实测、Android 体积与 APK 内成员核验仍待首次完整包/发布运行；
③ 签名 `.dev` 包真机加载与 16 KB 真机页验证（R3）；④ APK 体积（`check-rust-native-apk.py` 骨架用合成 APK 自测，
真实 APK 未含 `.so`）。R1 开工前建议 Lead 先跑一次 `ci-package` 或发布 dry-run，把 `rust-native` 的
`androidNeededAllowed` 按实际 `DT_NEEDED` 锁定，再宣布“交叉链接端到端通过”。

### 0.5 R1 实施回执（2026-11-13，`feat/rust-r1`）

> 工作树：`.agents/worktrees/feat/rust-r1`（基线 `origin/main` = `75924add`，build362，Kotlin/Rust R0/A2 slice1+2 已合入）。
> 默认关闭、不接生产调用点；**不可合并**：待 Lead 逐行评审、非实现者评审与用户授权；未 push main、未打 tag、未安装。

#### 0.5.1 已实现（范围严格限定在 R1）

| 项 | 位置 | 说明 |
|---|---|---|
| 只读 fd 边界 | `rust/crates/dsha-core/src/fd.rs` | 唯一新 `unsafe` 点：`ManuallyDrop<File>::from_raw_fd` + `FileExt::read_at`（pread）。不 open/stat/close、不接收路径、不创建进程/线程；EINTR 重试；EBADF/EINVAL/ENXIO/EOVERFLOW/EISDIR → `INVALID_ARGUMENT`，其它 I/O → `INTERNAL`；无效 fd 不 UB。 |
| 摘要实现 | `rust/crates/dsha-core/src/digest.rs` | SHA-256/SHA-1 有界只读摘要。固定 256 KiB 分块循环（`digest_range` 与 fd 解耦，便于无文件系统单测）；`length` 1..=32 MiB、offset 非负且 `offset+length` 不溢出 `off_t`；短读/提前 EOF → `ERR_SHORT_READ`（不写半成品）；未知算法 → `ERR_UNSUPPORTED_ALGORITHM`；超上限按 §4.2 的 `INVALID_ARGUMENT` 行（不借用归档会话的 `INPUT_BOUND`）。 |
| JNI 入口 | `rust/crates/dsha-core/src/jni.rs` | 按 R0 冻结符号实现 `Java_..._digest`：`GetDirectBufferAddress` + `GetDirectBufferCapacity`，每次 JNI 调用后 `ExceptionCheck`（pending → `ERR_JNI_PENDING`，不再调 JNI）；非 direct/null → `INVALID_ARGUMENT`，容量 < 摘要长度 → `OUTPUT_BOUND`；摘要先算到本地定长数组，成功后才一次写入，任何错误不写 `out`；`catch_unwind` + panic → `ERR_PANIC`；`panic="unwind"`；不写 stdout/stderr/logcat、不保存 JNI 引用。JNI 函数表按标准布局只声明到 231 槽，并在编译期用 `size_of`/`offset_of!` 断言槽位。 |
| JVM 桥 | `app/src/main/java/com/deepseekharness/app/util/{RustNative,RustDigest,RustBackends}.java` | 纯 Java、零 Android import，不在类初始化自动加载：`RustNative.ensureLoaded()` 安全 `System.loadLibrary("dsha_rs")`，失败类型化为 `LIBRARY_MISSING`/`LOAD_FAILED`/`ABI_MISMATCH`，不重试、不抛给调用方；包内 `loadFromPath` 仅供主机差分。`RustDigest` 提供 Rust 后端、JVM `MessageDigest` 后端与错误码映射；只有 `INTERNAL/PANIC` 允许 JVM 重做一次并标 `fellBack=true`，输入非法/短读/JNI pending 不重试；`RustBackends` 默认 **off**，`setEnabled(false)` 即一键回 JVM。 |
| Rust 单测 | `digest.rs`/`fd.rs` 的 `#[cfg(test)]` | SHA-256/SHA-1 已知向量、空输入（分块层）、CHUNK±1/跨块与 sha2 一次性摘要对照、短读/读取错误、参数边界（负 fd/负 offset/0/超限/溢出/未知算法）；`fd.rs` 真实临时 fd 的 offset/EOF/部分读/负 fd/巨大 fd 测试。EINTR 分支无法在不注入信号的前提下单测，按代码审查 + JVM 差分覆盖其余路径。 |
| JVM 测试 | `app/src/test/java/com/deepseekharness/app/util/{RustDigestTest,RustBackendsTest,RustDigestDifferentialTest}.java` | 纯 JVM 测试覆盖 JVM 后端/共享契约/默认关闭；差分测试用 host `.so` 对拍 JVM `MessageDigest`：SHA-256/SHA-1、0 字节拒绝、EOF 短读、offset/中间区间、跨块、单次 32 MiB、非法 offset/length/algorithm、负 fd/巨大 fd/只写 fd/关闭 fd、非 direct/过小 direct 缓冲、开关 on/off 与一键回退。`app/build.gradle` 新增 `-Pdsha.rust.hostLib` / `-Pdsha.rust.required` 测试属性；缺库默认跳过，`required=true` 直接失败（CI 0 skipped 口径）。 |
| 主机差分 | `tools/rust/host-jvm-differential.py` + `tools/rust/host-jvm/RustHostProbe.java` | 直接编译**生产桥**（不再是 R0 的探针副本）并与 JDK `MessageDigest` 对拍；`-Xcheck:jni`；探针路径改 `com.deepseekharness.app.util.RustHostProbe`，`--add-opens java.base/java.io` 只用于宿主测试读取 `FileDescriptor.fd`。 |
| 构建/门禁 | `tools/rust/build.py`、`rust/artifact.lock.json`、`tools/check-rust-boundaries.py`、`ci/rust-toolchain.lock.json` | `build.py` 新增 `--expect-lock`：按 target 三元组比较可复现的 size/SHA-256；`pending-cross-run` 只告警，`pinned` 不一致即失败（方案 §2.3）。`artifact.lock.json` 只登记 Android + 两种宿主三元组，不写 runId/commit/路径/mtime。边界脚本为 `fd.rs` 增加逐条允许命中（`std::fs`/`File::`/`std::io::`，附理由）并新增**非测试行**严格负向检查（禁 open/create/OpenOptions/write/close/日志）。`unsafe` 白名单仍是 `jni.rs` + `fd.rs`，依赖集不变（sha2/sha1 已在 R0 vendor，无新增 crate），因此 `ci/rust-toolchain.lock.json` 无需改字段。 |
| CI | `.github/workflows/{ci-fast,_node-rust,_node-unit-tests}.yml` 与 full 单测调用方 | `ci-fast` 新增 `rust-differential` job（每次 push 真实跑主机 JVM 差分）；`_node-rust` 的 host/android 构建加 `--expect-lock`；`_node-unit-tests.yml` 新增 `rust_host`，full 调用方（release-production / release-upstream / test-package / test-package-variants / 手动 unit-tests mode=full）先构建主机 `.so` 并用 `-Pdsha.rust.hostLib` + `-Pdsha.rust.required=true` 跑差分，避免 full 的 0-skip 门禁被放宽。R1 仍不接 `build-unsigned`：APK 内容与用户可见行为不变。 |
| 测试契约 | `tools/test-ci-workflows.py` | 更新 Source checks 节点集合/依赖，新增 `artifact.lock.json` 口径断言（target 键、pending/pinned 字段、不得写不可复现字段）。 |

#### 0.5.2 失败域（与 ADR-0004 矩阵一致）

| 域 | R1 处理 |
|---|---|
| 可展开 panic | 每个 JNI 入口 `catch_unwind` → `ERR_PANIC`；不跨 FFI unwind；静默 panic hook（G8 不写 stderr）。 |
| abort/OOM | 不可捕获；`panic="unwind"`、固定 256 KiB 分块、无按输入长度的无界分配、单次调用只分配 32 B 输出缓冲。 |
| SIGSEGV/UB | `unsafe` 只有 JNI 胶水与 fd 边界；`from_raw_parts` 前校验 null；不缓存裸指针；无效 fd 由 `pread` 返回 EBADF。 |
| JNI 异常 pending | 每次 JNI 调用后 `ExceptionCheck`；pending → `JNI_PENDING` 并立即返回。 |
| 内存生命周期 | 不保存 `JNIEnv`/引用；每次调用独立帧；不缓存 direct buffer 地址（只在该次调用内使用）。 |
| fd 生命周期 | Rust 不 close；Kotlin 提前 close → `EBADF` → `INVALID_ARGUMENT`（不 UB）；JVM 差分包“关闭的 fd”用例。 |
| 回退失败 | 开关关闭/库不可用 → JVM 并记录类型化原因；Rust `INTERNAL/PANIC` 才允许 JVM 重做一次（`fellBack=true`）；输入非法不重试；JVM 也失败则整体失败。 |

#### 0.5.3 默认关闭与回退

- `RustBackends.enabled` 进程内默认 `false`；只有显式 `setEnabled(true)` 才尝试 Rust，且必须 `RustNative.ensureLoaded()` 成功（`abiVersion()==1`）才真正使用。
- 一键回退 = `setEnabled(false)`；不需要清数据、不改变已写数据格式；R1 无生产调用点，回退不影响任何现有路径。
- 加载失败/ABI 不符不重试、不抛异常，回退原因写入结果 `fallbackReason`（类型化，不含路径）；成功时 `backend` 字段如实标注 JVM/Rust，Rust 失败不得写成“Rust 成功”。

#### 0.5.4 未做 / 未跑（不得当作通过）

- 未接任何生产调用点（`backup/` 等不动）；未接 `build-unsigned`、未把 `.so` 放进 APK、未改 `runtimeId`/版本/签名/数据格式。
- Rust `archive_pack/unpack`、`verify_plan` 仍是 `NOT_IMPLEMENTED`（R2/R3）；`PlanView` 与 gzip 未实现。
- 本机交叉链接未跑（NDK 仅 linux-x86_64，按 §0.1 Q2 仍由 CI `rust-native` 负责）；arm64 `.so` 的首次真实链接/`DT_NEEDED`/APK 成员核验仍待 `ci-package` 或发布 dry-run。
- Android 真机加载/16 KB 页验证属 R3（签名 `.dev` 包），R1 不声明。
- 本机证据见 §0.5.5，未跑/受限项见 §0.5.6。
- `rust/artifact.lock.json` 的跨 run pin：R1 首轮先保持 `pending-cross-run`；只有连续 3 次不同 runner 实例一致才 pin（方案 §2.3）。Android 条目需 `rust-native` 手动流程证据。

#### 0.5.5 本机证据（2026-11-13，真实回执；未跑项见下）

> 宿主：Android 14 / Ubuntu 24.04 aarch64 容器（PRoot），rustc/cargo 1.100.0，JDK 17.0.21；
> 内存紧张期间按 Lead 要求 `CARGO_BUILD_JOBS=1 nice -n 10` 串行、一次一个构建，**本机不跑 Gradle**。

| 项 | 结果 |
|---|---|
| `cargo fmt --check` | PASS（0 diff） |
| `cargo clippy --locked --offline --all-targets -- -D warnings -D clippy::unwrap_used -D clippy::expect_used` | PASS（0 warning；期间修掉 `duplicated_attributes`：fd.rs 内层 allow 与 lib.rs 模块 allow 重复） |
| `cargo test --locked --offline` | **17 passed / 0 failed**（R0 6 → R1 17；新增 digest 已知向量/空输入/分块/边界 + fd 真实 offset/EOF/负 fd + 1 个 offset 回归测试） |
| `cargo build --release --locked --offline`（host） | PASS（直接构建 `target/release/libdsha_rs.so` 266,768 B） |
| `cargo check --locked --offline --target aarch64-linux-android` | PASS（仅类型检查；本机无 NDK，链接按 §0.1 Q2 交 CI） |
| `tools/rust/build.py --host --check --expect-lock --jobs 1` | PASS：不同源码目录两次构建**逐字节一致**；宿主 `libdsha_rs.so` 266,768 B / SHA-256 `675fcac09ec03fb1404499c154408ea218146796d7821c9bb09069739462721c`；体积 PASS；`--expect-lock` 按设计告警 `PENDING`（aarch64 宿主未跨 runner 固定） |
| `tools/rust/host-jvm-differential.py --lib out/rust-host/libdsha_rs.so` | **PASS 32/32 checks**（-Xcheck:jni；SHA-256/SHA-1 已知向量、offset/跨块/单次 32 MiB、空内容拒绝、EOF 短读、非法 offset/length/algorithm、负 fd/巨大 fd/只写 fd/关闭 fd、非 direct/过小/null 输出、switch on/off/一键回退、MessageDigest 参照） |
| `tools/check-rust-boundaries.py` | PASS（7 源 / 11 符号 / 16 依赖 / 4 构建输入；fd.rs 允许清单只含 `std::fs`/`File::`/`std::io::`/测试 `open(`，非测试行严格禁 open/create/write/close/日志） |
| `tools/check-rust-toolchain.py` / `tools/check-rust-vendor.py` | PASS（16 crate / 7,384,177 B / 782 文件） |
| `tools/test-ci-workflows.py` | **57 tests PASS**（新增 rust_host 接线、ci-fast rust-differential、artifact.lock 口径） |
| `tools/format-java-tree.py --check --jobs 1` | 首轮 FAIL（新增 6 个 Java 文件不在显式 adoption 清单；已补 `tools/java-format-files.json` 并用固定格式化器写回 3 个文件），复跑 PASS（0 need formatting） |
| `tools/generate-third-party-notices.py --check` | PASS（Rust vendor 许可字节未变；无新增 crate） |
| `tools/check-source-hygiene.py` / `tools/check-document-links.py` | PASS（LF/钉住 action/依赖身份；56 文档 / 1295 链接 / 0 error） |
| `docs-check` CI | run `43363307113` success（本机推送后） |

**本机首轮差分发现并修复的真实缺陷**：`digest_fd` 曾把 `digest_range` 给的区间内相对偏移直接传给 `pread`，`offset > 0` 时会从 0 开始读。主机差分 offset/中间区间用例捕获（JVM `MessageDigest` 对照不一致），已改为 `offset + position`（checked_add）并补 `fd.rs` 真实 fd 回归测试；修复后 32/32 checks 全 PASS。同时修掉 `RustHostProbe` 的 `MessageDigest.digest()` 重置导致的 detail 误报（预期摘要显示为空哈希，实际比对值正确）。

#### 0.5.6 未跑 / 受限（不得当作通过）

- **本机 Gradle**：按 Lead 2026-11-13 内存紧急要求未跑 `dsha-gradle :app:testStandardDebugUnitTest --tests '*Rust*'`；该差分用例的 CI 执行路径为 `_node-unit-tests.yml` 的 `rust_host`（release/package/full 调用方）与 `ci-fast` 的 `rust-differential`（Python 探针）。Gradle 编译/单测结果以 CI 回执为准。
- **本机 Android 链接**：无 NDK（`/opt/android-sdk/ndk` 不存在），`tools/rust/build.py --android` 未跑；arm64 `.so` 首次链接/`DT_NEEDED`/APK 成员核验仍交 `ci-package`/发布 dry-run（与 R0 相同）。
- **跨 run 产物 pin**：`rust/artifact.lock.json` 仍为 `pending-cross-run`；需要连续 3 次不同 runner 实例的 size/SHA-256 一致后才 pin（x86_64 宿主与 Android 产物分别等待 CI 证据）。
- **真机/16 KB 页**：R3 签名 `.dev` 包范围，R1 不声明。
- **format-kotlin-tree**：本批无 `.kt` 变更，未在本机跑；CI `kotlin-format` 仍会执行。

#### 0.5.7 风险与 R2 建议

- **长尾风险**：`catch_unwind` 不覆盖 abort/SIGSEGV/OOM；`pread` 在特殊文件类型上的错误码集合依赖内核，已按“调用方错误/内部错误”分裂，真机异常文件类型仍需 R3 证据。
- **JVM 回退的双读成本**：`digest` 的 JVM 重做只对 `INTERNAL/PANIC` 开放；A3 接线时应保证区间可重读、目标可丢弃（本函数无输出目标）。
- **R2 建议**：`verify_plan` 走同一套“纯 Rust 实现 + JVM 第二实现 + 集合差分”骨架，但 `PlanView` 契约需 A3 冻结后再动；不得复用本批的 fd/JNI 模板去加执行路径。

---

## 1. 结论摘要

### 1.1 结论

1. **Rust 可以按 ADR-0004 决定四开工准备（R0），但不能把 R0 当成 A3 开工。** R0 只新增 `rust/` 源码树、vendor、工具链锁与 CI 节点，不改变 APK 运行行为、不进入数据路径；R1–R3 必须与 A3 的备份契约、夹具和 A2 文件事务排序，R3 是 A3 的最后一个增量。
2. **本机（aarch64 手机容器）能跑 Rust 主机工具链，也能做主机侧 JNI 差分测试；但官方 NDK r26 只有 `linux-x86_64` 预编译工具链，本机没有 qemu/binfmt，原生 Android 交叉链接在本机跑不起来。** 证据与三条备选路径见 §2.6 与 §7。**CI（ubuntu-24.04 x86_64）是当前唯一确定的 arm64 `.so` 构建环境**；Lead 2026-11-13 已决定暂不做 rust-lld/qemu spike（§0.1 Q2），本机交叉链接不作为 R0 门禁。
3. **首期只做三类封闭纯函数，没有 `execute(...)`、没有 `argv`、Rust 不接收路径字符串、不自行 `open`、不碰进程/信号/Android API。** `PlanEnvelope`/`permitMac` 是将来执行路径的占位，**不进入首期 ABI**；[安全评审草稿](drafts/05-safety-permissions.md)里的 `execute(PlanEnvelope)` 形状与 ADR 决定四冲突，以 ADR 为准，将来若要做必须另立 ADR。
4. **构建按 ADR 决定四执行**：NDK `26.3.11579264`（r26）+ Rust stable `1.100.0` + `aarch64-linux-android`；`cargo vendor` 入库、`Cargo.lock` 入库、字节复现 `--check`、`max-page-size=16384`、`.so` 只作 CI 产物不入库。
5. **`.so` 不进 `app/src/main/jniLibs`（已跟踪树），改由 `app/build/rust-jniLibs/` 注入 Gradle。** 现有 `managed-runtime-inputs.json` 把 `app/src/main/jniLibs` 整棵树列为 `launcherInputs`；把未跟踪的 `.so` 放进去会让干净检出无法通过 `prepare-runtime-descriptor.py --check`，且违反 D6。代价是 Rust 产物不进入 `runtimeId`，由**独立的 APK 级 receipt + 体积/字节门禁**绑定（§3.3、R8 风险的处理）。Lead 2026-11-13 已接受该边界（§0.1 Q1）。
6. **差分测试放在主机 JVM（不是设备）**：同一 crate 同时产出 `aarch64-linux-android`（进 APK）与主机目标（进 JVM 单测）；本机已实测“Rust 主机 `.so` + JDK 17 JVM”可加载并调用 JNI。设备侧只做 R3 的签名 `.dev` 包冒烟与 16 KB 页真机验证。
7. **构建与签名节点不受 Rust 影响**：Rust 交叉编译在独立 `rust-native` job 里完成并上传 artifact；`build-unsigned` 下载 artifact 后连同来源 receipt 一并核验；`sign-verify` 只做签名与二次核对（不跑 Rust/Gradle）；Gradle 的 `verification-metadata.xml` **不需要**改动。

### 1.2 开工门槛（Gate）

| 门禁 | 内容 | 当前状态 | 谁批准 |
|---|---|---|---|
| G-R0-A | 接受“`.so` 走 `app/build/` 注入、不进 `launcherInputs`/`runtimeId`”的边界，以及由此产生的 R8 补偿门禁 | **已接受**（§0.1 Q1） | Lead |
| G-R0-B | 接受“本地 aarch64 不做强制 Android 链接”，CI 交叉构建 + 设备加载作为 R0 证据；本机只要求 `cargo check --target` + 主机 JNI 差分 | **已接受**（§0.1 Q2；不做 rust-lld/qemu spike） | Lead |
| G-R0-C | 接受新增离线依赖面：`rust/vendor/` 约 7.4 MB、16 个 crate、3 个 `build.rs` 审查；调整 `THIRD_PARTY_NOTICES`/APK 原生成员门禁 | **有条件批准**（§0.1 Q3；vendor 清单/build.rs 审查/profile 锁见 §0.2） | Lead + 用户 |
| G-R1/R2/R3 | A2 文件事务完成、A3 备份格式/夹具冻结、A3 的 `backup/` 改动进入主线后再动调用点 | 未满足（A2 未开始） | Lead |
| G7/G8 | CI 边界脚本证明“无执行入口/无裸 `exec`/无 `argv`/Rust 不写 logcat”；FFI 评审由非实现者执行 | 本文件给出方案，R0 落地 | 独立评审 |

### 1.3 首期范围（只三类封闭纯函数）

| 函数 | 做什么 | 明确不做什么 |
|---|---|---|
| `archive_pack` / `archive_unpack` | RFC 1952 **单成员 gzip 编解码**：deflate、CRC32、ISIZE、严格头/尾校验；输入输出都是有界字节流 | 不写 tar/容器格式（tar/v5 语义留在 JVM）；不接收路径、不做文件 I/O、不做多成员、不解析清单 |
| `digest(fdRange, algorithm)` | 对 Kotlin 已核验并移交的 `fd + offset + length` 做 `pread` 流式摘要；SHA-256 / SHA-1；返回定长摘要与实际读取长度 | 不 `open`/`stat`/`close` fd；不接收路径；不决定 fd 是否可信；不碰 Keystore/凭据 |
| `verify_plan(view)` | 对 Kotlin 生成的规范化 `PlanView` 二进制投影做只读校验（结构、字段范围、策略版本、有效期），返回全部原因的**集合** | 不解析 JSON、不执行计划、不产生副作用、不判断“用户已授权”、不接收 argv/路径/命令 |

补充：`archive_pack`/`archive_unpack` 承担“归档 deflate”这一唯一真实 CPU 热点中的 gzip 层；`BackupArchive` 的 v5 容器格式与 `PortableBackupEnvelope` 的 tar 框架仍由 JVM 负责。因此**对拍比的是“解压后字节 + 摘要 + 清单”，不是 gzip 字节**（gzip 头/压缩器差异允许存在）。

### 1.4 明确不做项（G7 的负清单）

- 不加 `execute`/`exec`/`system`/`spawn`/`fork`/`kill`/信号/PTY/进程身份/停止证明/3090 桥/proot 启动器/日志脱敏/guest 插件事务。
- Rust 不解密、不读 Keystore/SharedPreferences/Binder/Android API、不联网、不写 logcat/stdout/stderr（G8）、不安装信号处理器、不创建线程、不做异步回调。
- 不提交 `.so`、不在 APK 外分发 `.so`、不把 Rust 放进 `runtimeId`（Lead 已接受，§0.1 Q1），不改数据格式/`dataWrite`/包名/签名。
- 不做引擎进程化（另立 ADR）、不承诺性能倍数、不为“引擎”扩大范围。
- 不在本机覆盖安装正式包（测试包 `.dev`/`.debug` 才可装；设备验收需用户授权）。

---

## 2. 工具链方案

### 2.1 版本与依据（实测/锁定）

| 输入 | 选值 | 依据 |
|---|---|---|
| NDK | **26.3.11579264（r26d）** | 仓库三处一致：`app/build.gradle` 的 `ndkVersion '26.3.11579264'`、[`ci/toolchain.lock.json`](../../../ci/toolchain.lock.json) 的 `ndk` 字段、`BUILD.md` 环境表。Google `repository2-3.xml` 中 `ndk;26.3.11579264` 的 Linux 归档为 `android-ndk-r26d-linux.zip`，**668,556,491 B**，SHA-1 `fcdad75a765a46a9cf6560353f480db251d14765`（2026-11-12 核对） |
| Rust | **1.100.0 stable**（rustc `1.100.0 (0ef1bc386 2026-11-09)`，cargo `0.101.0 (3acc79c0c 2026-11-05)`） | `https://static.rust-lang.org/dist/channel-rust-stable.toml`（manifest 日期 `2026-11-12`）实测读取；`rustup-init 1.29.3 (edbc831ce 2026-11-02)`，`aarch64-unknown-linux-gnu` 安装包 20,172,120 B / SHA-256 `9f608c142e0e38a78957d0661ba1dfaf0b6fb37184cfaa4b9a0aa57af6184282`；本机已安装并跑通（§2.6） |
| target | `aarch64-linux-android` | ADR 决定四；`rust-std-1.100.0-aarch64-linux-android` 在 stable manifest 中可用（SHA-256 前缀 `2562eb09…`，2026-11-12 读取）；本机 `rustup target add` + `cargo check` 实测通过 |
| linker / API | **`aarch64-linux-android23-clang`**（NDK r26） | 与 `tools/native-session/build.py` 的 `aarch64-linux-android23`、`tools/proroot-compat/build.py` 的锁定 API 保持可核对；App `minSdk 30` 只表示运行下限，不要求链接 API 30。R0 在 receipt 里记录三者（NDK 版本、clang triple、target） |
| edition / profile | 2021；`opt-level="z"`、`lto="fat"`、`codegen-units=1`、`panic="unwind"`、`strip="symbols"` | `panic="unwind"` 是 ADR 硬约束；`strip="symbols"` 避免依赖 NDK 的 x86_64 `llvm-strip`（本机 aarch64 无法执行），同时满足“strip 后体积预算”；`opt-level/lto` 用于体积而不是性能承诺 |

Rust 工具链由 `rust-toolchain.toml` 固定（不依赖 `rustup default`）：

```toml
[toolchain]
channel = "1.100.0"
profile = "minimal"
components = ["rustfmt", "clippy"]
targets = ["aarch64-linux-android", "x86_64-unknown-linux-gnu", "aarch64-unknown-linux-gnu"]
```

说明：宿主机目标用于主机侧差分测试（本地 aarch64、CI x86_64），**不进入 APK**；CI 只安装当前 runner 的主机目标 + `aarch64-linux-android`。

### 2.2 仓库布局与锁定

新增（R0 实施，不在本文件改动）：

```text
rust/
├── Cargo.toml / Cargo.lock           # workspace；锁文件入库
├── rust-toolchain.toml               # 固定 1.100.0
├── .cargo/config.toml                # 只做 vendor 源替换；linker 由构建脚本按机器设置
├── crates/dsha-core/
│   ├── Cargo.toml
│   └── src/{lib.rs,jni.rs,gzip.rs,digest.rs,plan.rs}  # R0 只落 lib.rs/jni.rs 骨架
├── vendor/                           # cargo vendor 产物，入库
└── artifact.lock.json                # R1 起：CI 产物 size/sha256，供 APK 门禁比对（见 §2.3）
ci/rust-toolchain.lock.json           # 工具链锁：channel/date/rustc commit/target 组件/rustup-init sha256
tools/rust/build.py                   # 构建 android / 主机 / --check / --expect；输出 receipt
tools/check-rust-toolchain.py         # 核对 rust-toolchain.toml ↔ lock ↔ CI 值
tools/check-rust-boundaries.py        # G7/G8 源码与依赖边界扫描（见 §4.8）
tools/check-rust-native-apk.py        # APK 内 Rust 成员、ABI、体积、receipt 一致性（见 §3.5）
rust/native-budget.json               # 体积预算：基线、artifact 上限、ABI/文件清单
```

`ci/toolchain.lock.json` **不改**：只有 NDK/JDK/Gradle 等既有字段，`tools/ci-toolchain.py` 会拒绝未知字段。Rust 工具链另用 `ci/rust-toolchain.lock.json`，避免打散 Kotlin 批次对 `ci-toolchain` 的依赖。

### 2.3 字节复现 `--check`

`tools/rust/build.py` 的三段语义（与 `tools/native-session/build.py` 同形）：

1. `--check`：把当前产物重新构建到临时目录，与给定产物/锁文件逐字节比对；不一致即失败，不替换文件。
2. `--expect <sha256>`：与 `rust/artifact.lock.json` 的期望值比对；用于**跨 runner/跨 run** 的复现门禁。
3. 写出 `receipt`（JSON）：commit、runId、工具链、`Cargo.lock`/vendor/源文件摘要、target/linker、构建参数、产物 size/sha256、测试计数。

关键参数：

```text
cargo build --locked --offline --release --target aarch64-linux-android
RUSTFLAGS = --remap-path-prefix=<repo>=/src --remap-path-prefix=<cargo-home>=/cargo
            --remap-path-prefix=<rustup-home>=/rustup --remap-path-prefix=<ndk>=/ndk
CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=<ndk>/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android23-clang
RUSTFLAGS += -C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=4096
             -C link-arg=-Wl,-z,relro -C link-arg=-Wl,-z,now -C link-arg=-Wl,-z,noexecstack
# 候选（R0 实测后定）：-C link-arg=-Wl,--build-id=none；若影响符号化则保留 lld 的确定性 build-id
```

实测依据（本机 aarch64，宿主目标；Android 目标的链接部分受 §2.6 限制）：

- 同一目录重复构建，字节稳定；不同目录**不 remap 时字节不同**（`9f7d52…` vs `537f2b…`），**加 `--remap-path-prefix=<项目>=/src` 后两目录产物完全一致**（`a6ac3e3d…`）。这证明了必须把 remap 写进构建脚本，不能只依赖“本机看着一样”。
- 用空 `CARGO_HOME` + `[source.vendored-sources]` + `--offline --locked` 在 16 个 crate 的 vendor 树上构建成功；证明“入库 vendor 是离线构建的充分来源”，CI 不需要 crates.io。
- 主机 `.so` 实测（含 gzip+SHA-256 实际代码路径、`opt-level=z`/LTO/strip）**332,424 B**，`DT_NEEDED` 只有 `libgcc_s.so.1`/`libc.so.6`（Android 侧预期为 bionic 对应库）。这是 §3.5 体积预算的依据上界，不是 Android 实测值。

`--check` 与 `artifact.lock.json` 的启用顺序：R0 只做“同 job 内两次构建比对 + receipt”；R1 起若连续 3 次 CI run（不同 runner 实例）字节一致，再把期望值写进 `rust/artifact.lock.json` 并纳入 `--expect` 门禁。若跨 run 不稳定，**如实降级**为“源锁 + 单 run receipt 绑定”，不宣称跨环境字节复现。

`max-page-size=16384` 的静态验证：链接后由 `tools/audit-standard-apk.py` 的 ELF 检查器逐 `PT_LOAD` 校验（`alignment >= 16384` 且 `(file_offset - address) % 16384 == 0`）并检查 RELRO 区间落在 4K/16K 页映射内；新 `.so` 加入后同一门禁自动覆盖。真机 16 KB 页加载属 R3 设备证据（现有文档明确“静态对齐 ≠ 16 KB 真机实测”）。

### 2.4 离线可复现边界

| 层 | 是否在仓库 | 可复现性 | 约束 |
|---|---|---|---|
| Rust 源码、`Cargo.toml`、`Cargo.lock`、`rust-toolchain.toml`、vendor（7,384,177 B / 782 文件 / 16 crate）、构建脚本、工具链锁 | 是 | 字节可核验 | `.gitattributes` 设 `rust/vendor/** -text`，禁止行尾规范化；`check-source-hygiene.py` 为 vendor 开白名单；`cargo vendor --locked` 重新生成后与入库树比对（R0 门禁） |
| Rust 工具链（rustc/cargo/std，本机实测约 681 MB） | 否 | 版本固定、**非离线** | CI 用 rustup 安装并缓存；`ci/rust-toolchain.lock.json` 记录 channel/date/commit/rustup-init sha256；工具链不入库、不入固定输入包 |
| NDK（668,556,491 B 压缩，解压约 3 GB 量级） | 否 | 版本固定、**非离线** | 复用现有 `.github/actions/android-sdk` 的 `ndk: 'true'` 安装与超时/重试逻辑；CI runner 预装 NDK 27/29/30，26.3 仍需下载 |
| Gradle/JDK/Node、固定运行时输入 | 否 | 已有闭环 | 不因 Rust 改变；`ci/rust-toolchain.lock.json` 不并入 `ci/toolchain.lock.json` |
| Android 系统 loader/libc、真机页大小/ABI、deflate 性能 | — | 不可复现 | 只声明设备证据范围；不承诺性能倍数，不把静态对齐当 16 KB 真机通过 |

**准确的结论**：`cargo build --offline --locked` 在拉取过一次工具链/NDK 的机器与 CI 上不需要网络；**从零、完全断网的新机器无法构建**（工具链与 NDK 不在仓库）。这一点必须写进 A3 出口的“已知边界”，不能把“离线 vendor”扩写成“整条链离线可复现”。

### 2.5 CI job 设计

新增可复用节点 `_node-rust.yml`（在 `ci-fast`、`ci-package`、`release-production` 复用），单一职责：

| job | needs | 做什么 | 产物 / 失败行为 |
|---|---|---|---|
| `rust-check`（ci-fast） | 无（source-only） | 校验工具链锁、vendor 新鲜度、`Cargo.lock --locked`、`cargo fmt --check`、`cargo clippy -D warnings`（仅纯 Rust 部分）、`cargo test --locked --offline`（主机单元/属性测试） | 失败即阻断 PR；不上传 `.so` |
| `rust-native`（ci-package / release） | `bind`（release）/ 无（ci-package 由 inputs 触发） | 安装 rustup 1.100.0 + NDK 26.3 → 再验 vendor → `cargo test` → 构建 `aarch64-linux-android` `.so` → strip → 静态检查（ELF/ABI/16K/依赖/边界）→ 写 `rust-receipt.json` | 上传 `rust-native` artifact（`.so` + receipt，retention 1–14 天）；超预算/边界失败直接 fail；**不上传 crates 缓存到签名节点** |
| `rust-differential` | `bind`, `inputs`, `rust-native` | 用主机目标构建 `.so` → 跑 JUnit `*Rust*` 差分测试（`-Xcheck:jni`）→ 汇总 0 skipped 回执 | 上传 `rust-differential.json`；与 `unit` 的 0-skip 门禁联动（见下） |
| `rust-repro`（ci-package） | `inputs` | 在 `native-repro` 同层新增：`cargo vendor --locked` 重生成比对、`--check` 两次构建比对、`rust/artifact.lock.json` 期望值比对、`cargo tree` 依赖白名单 | 失败即阻断完整包 |

与现有节点的关系：

- **bind/contracts/java-format/host-regression/inputs/unit/lint**：函数与安全语义不变。`contracts` 的 `generate-third-party-notices.py --check` 在 R0 完成后必须能生成 Rust 段（见 §7 Q3）；`unit` 若要保持“full 0 skipped”，必须能拿到主机 `.so`（给 `_node-unit-tests.yml` 增加可选输入 `rust_host: true|false`，release/package 传 true，source-only 传 false）。这是 R0 的 CI 改动之一。
- **build-unsigned**：`needs: [bind, inputs, rust-native]`；新增步骤“下载 rust-native artifact → 校验 receipt 绑定本 commit/run → 复制到 `app/build/rust-jniLibs/arm64-v8a/`”；Gradle 仍只跑 `assembleStandardRelease`（不跑 cargo）；构建后跑 `tools/check-rust-native-apk.py`，把 `rust-receipt.json` 放入 `out/unsigned-handoff`。
- **sign-verify**：`needs` 不新增 Rust job（Rust 已在 unsigned 阶段完成且无 Secrets）；它下载 `release-unsigned-apk`（含 receipt）后**再次**核对 APK 内 `.so` 摘要 == receipt，并把 receipt 并入 `out/` 证据包。签名不改变 `lib/` 载荷。
- **test-packages-unsigned / test-packages**：R3 才接入 Rust artifact（`_node-test-packages.yml` 增可选输入 `rust_artifact`），用于真机 `.dev`/`.debug` 冒烟；R0/R1 不接，避免影响既有测试包调用方。
- **publish / dry-run-summary**：不新增 job；`publish` 把 `out/rust-receipt.json` 计入 `SHA256SUMS` 与 `build-info.json` 的 `rust` 对象（§5.5），Release 资产不新增裸 `.so`。
- **ci-package**：R0 的 `package` job 不接 Rust artifact（APK 不含 `.so`，与 R0“无运行行为”一致）；R1 起 `package` job 需要 `rust-native` artifact，否则“完整包”缺 `.so`；`native-repro` 从 R0 起增加 Rust 复现/许可检查。
- **结构性测试**：`tools/test-ci-workflows.py` 断言 release-production 的 job 集合与 `needs`；加 job 必须同批更新期望集合（R0），否则 `contracts` 门禁失败。`tools/verify-ci-package.py` 对 run 路径的绑定同样要纳入新 job 产物。

CI 实现细节：

- 安装：`actions/checkout` 固定 commit；rustup 用官方 `rustup-init`（SHA-256 来自 `ci/rust-toolchain.lock.json`）或 runner 预装 rustup + `rustup toolchain install 1.100.0 --profile minimal`；目标 `aarch64-linux-android`；`~/.rustup` 用 `actions/cache`，key 含 `ci/rust-toolchain.lock.json` 哈希（不缓存到签名节点）。
- NDK：复用现有 composite action（`ndk: 'true'`），不新增第三方 action；`sdkmanager` 10 分钟超时/2 次重试已由该 action 处理。
- 无签名 Secrets 的 job 才允许写缓存（与现有 Gradle 缓存策略一致）；`sign-verify` 不跑 Rust，也不下载 `.so`（它从 unsigned handoff 拿已核验的 APK）。
- 依赖缓存：由于 vendor 入库，**不缓存 crates.io**；只缓存工具链与 Cargo 的编译缓存可选（不缓存也能在 1–3 分钟内完成 16 crate 的构建，本机实测约 6 秒）。
- 回执：见 §5.5；每个 `.so` 只允许由本 run 的 `rust-native` 产生，按 `runId + runAttempt + commit + receipt.sha256` 绑定，禁止跨 run 复用 artifact。

### 2.6 本机（aarch64 手机容器）实测结论

| 项 | 实测 | 结论 |
|---|---|---|
| Rust 主机工具链 | `rustup-init 1.29.3` 安装 stable 1.100.0；`rustc -vV` → host `aarch64-unknown-linux-gnu`、LLVM 23.1.5；`.rustup` 约 651 MB | **可以**；但工具链不入库，需一次性下载 |
| Android target | `rustup target add aarch64-linux-android` 成功；`cargo check --target aarch64-linux-android` 通过（无链接） | **std/type-check 可以** |
| Android 链接 | `cargo build` 失败于 `linker cc not found`；本机原先无 `cc`；补装 `gcc 13.3` 后主机目标可链接，但**NDK 不存在**，Android 仍无法链接 | **需要 NDK clang（x86_64）或替代 linker** |
| NDK 可用性 | 本机无 NDK；官方 r26d Linux 归档仅 `linux-x86_64` 工具链；无 `qemu-x86_64`、`/proc/sys/fs/binfmt_misc` 为空 | **不能在本机直接执行 NDK clang** |
| 下载成本 | 到 dl.google.com 实测约 0.47 MiB/s；668 MB 单流约 23 分钟（未在本轮执行） | R0 spike 需单列时间盒与磁盘（解压约 3 GB 量级） |
| 主机 JNI | Rust cdylib（`extern "system"`）经 `System.load(absPath)` 在 JDK 17 aarch64 加载并调用成功（`JNI_OK 42 1234`） | **主机差分 harness 可行** |
| vendor/离线 | 16 crate / 7,384,177 B / 782 文件；空 `CARGO_HOME` + `--offline` 构建成功；3 个 `build.rs`（`crc32fast`、`generic-array`、`libc`）只用 `rustc --version` 探测，无网络 | **vendor 方案可行**；`build.rs` 入审查清单 |

三条备选路径（已决：A 为默认、B/C 暂缓；见 §0.1 Q2）：

- **A（推荐，CI 为主）**：本机只做 `cargo check --target` + 主机 JNI 差分；Android `.so` 由 CI 产出，设备通过 R3 签名 `.dev` 包加载。R0 出口用“CI 产物 + 静态门禁 + 真机加载”证明，不宣称本机交叉链接。
- **B（本机 rust-lld + NDK sysroot）**：不执行 NDK 的 x86_64 clang，用 Rust 自带的 aarch64 `rust-lld` + NDK 的 sysroot/crt/builtins 直接链接；需要写 linker wrapper 并验证 panic/unwind、`libgcc`/`builtins`、`DT_NEEDED`。技术可行但高风险，限定 1 人日 spike；失败即回 A。
- **C（qemu-user + binfmt）**：在容器内跑 x86_64 NDK clang（`DSHA_FORCE_AIDL` 场景的同类做法）；本机当前没有 qemu，需 apt 安装并注册 binfmt（内核/容器权限未验证），不作为默认路径。

---

## 3. 与 Gradle 集成点

### 3.1 选型：预编译 `.so` 打包，不用 `externalNativeBuild`

| 维度 | 预编译 `.so` + `jniLibs`（推荐） | `externalNativeBuild` + CMake 包装 cargo |
|---|---|---|
| 工具链锁定 | Cargo/NDK 由 `tools/rust/build.py` + 两个锁文件管 | 需新增 CMake 版本到 `ci/toolchain.lock.json`（当前无此字段），并改 `ci-toolchain.py` |
| 离线/vendor | `--locked --offline` 直接可控 | CMake 自定义命令里跑 cargo，Gradle 缓存把 cargo target 当黑盒，vendor 漂移易被缓存掩盖 |
| 构建图 | 仓库仍是“预编译 JNI + 现有 14 份 `.so`”的既有形态 | 首次引入真正的原生构建阶段，构建图与 CI 都要重排 |
| 签名/回执 | CI 产物 + receipt 显式绑定 APK 内字节 | AGP 会把中间产物藏进 build/，回执仍需额外脚本 |
| ABI 校验 | 由 `abiFilters` + 新 APK 门禁完成 | AGP 自带部分校验，但不覆盖“解压后字节/16 KB/回执” |

结论：**不添加 `externalNativeBuild`/`CMakeLists.txt`**，沿用仓库现有的“预编译 JNI 入库/注入”形态，只是 Rust 产物不入库、由 CI 注入。

### 3.2 jniLibs 与打包

- 产物名：`libdsha_rs.so`（`System.loadLibrary("dsha_rs")`）；只放 `app/build/rust-jniLibs/arm64-v8a/`，**不放** `assets/`、`filesDir`、`src/main/jniLibs/`。
- Gradle（R0 实施）：

```groovy
android {
    // R0 实现时以 AGP 9.1.1 实测为准：srcDir(layout.buildDirectory.dir("rust-jniLibs"))
    // 或 srcDirs += [new File(buildDir, "rust-jniLibs")]，只加这一处，不动其余 native 配置。
    sourceSets.main.jniLibs.srcDir(layout.buildDirectory.dir("rust-jniLibs"))
}
```

- 现有 `packagingOptions.jniLibs` 不动：`useLegacyPackaging = true`（沿用现有 `.so` 的安装期解压语义）、`keepDebugSymbols` 仍只保护 `libproot.so`/`libprootloader.so`（新 `.so` 用 `strip="symbols"`，不得误入该名单）、`pickFirsts += ['**/libtermux.so']` 与 `excludes`（x86/armeabi 等）保持不变。
- `abiFilters 'arm64-v8a'` 不变；Rust 只产 arm64；CI 门禁断言 APK 中不存在其它 ABI 目录。
- 本地开发者：`-Pdsha.rustNative=off|prebuilt|build`（默认 `off`）：
  - `off`：不注入、不打包；JVM 回退路径运行，单测中 Rust 用例按 `Assume` 跳过（full 的 0-skip 只在 CI/显式 `-Pdsha.rustNative=prebuilt` 跑）。
  - `prebuilt`：把已有 `.so` 复制到 `app/build/rust-jniLibs/arm64-v8a/` 后打包；release 必须显式使用此模式并带 receipt。
  - `build`：调用 `tools/rust/build.py --android` 后打包（需要本机 NDK + Rust；本机 aarch64 只在路径 A/B 成立时可用）。
- `build.gradle` 只增加 `sourceSets` 与一个 `Exec`/`Copy` 任务；**不修改** `ndkVersion`/`minSdk`/`abiFilters`/签名/身份。

### 3.3 `launcherInputs` / `runtimeId` 的边界（Lead 已接受，2026-11-13）

现状：`managed-runtime-inputs.json` 的 `launcherTrees` 包含 `app/src/main/jniLibs`，`prepare-runtime-descriptor.py` 会遍历该树的全部 `.so` 生成 `launcherInputs`（481 项），任何缺失都会让 `--check` 失败。因此：

- 若把 Rust `.so` 放进 `src/main/jniLibs`（已跟踪树）：干净检出（不含 `.so`）无法运行 `prepare-runtime-descriptor --check`/Gradle 构建，除非全仓所有人都装 NDK+Rust；且违反 D6“`.so` 走 CI 不入库”。**不采用。**
- 若放在 `app/build/rust-jniLibs`（Lead 2026-11-13 接受；理由：既有边界是“原生库随 APK 走、受管运行时只管启动树”）：`launcherInputs` 不含 Rust 产物，`runtimeId` 不因 Rust 改动而变化；`runtimeId` 仍只会因 A3 的 `backup/` Java 改动而变化（I6 的一次维护页，符合 roadmap A3 预期）。
- R0/R1 的 Java 端口（`util/RustNative`、`util/RustDigest`、`util/RustBackends`、`util/RustGzip*`）放 `util/` 且不加入 `launcherSources`，因此 R0/R1 不改变 `runtimeId`；只有 R3/A3 在 `backup/` 的调用点（或 A3 自身改动）才触发那一次维护页。
- 补偿：Rust 产物由**发布回执 + APK 级门禁**绑定（`rust/artifact.lock.json`、`rust-receipt.json`、`check-rust-native-apk.py`、`THIRD_PARTY_NOTICES`、`audit-standard-apk` 16 KB 检查）。这与评审主稿 R8“`.so` 未进锁文件”的目标一致，只是把“摘要进描述符”换成“摘要进 release 证据 + 审查过的 artifact lock”。
- 备选（不推荐）：给描述符加 `rustNative` 字段并从 `rust/artifact.lock.json` 读取摘要——可把摘要放进描述符且干净检出可校验，但会让每次 Rust 变更触发一次维护页（R8 的“摘要/体积”要求与 I6 的“运行时身份”语义混叠）。若 Lead 坚持“摘要必须在描述符里”，需要单独决定并接受这个维护页代价。

### 3.4 ABI 校验

新增 `tools/check-rust-native-apk.py`（R0 骨架，R1 完整）：
1. ZIP 内 `lib/` 只允许 `arm64-v8a/` 与既有 14 个名字 + `libdsha_rs.so`；出现其它 `.so`/ABI 目录即失败。
2. `libdsha_rs.so` 的 SHA-256/大小 == `rust-receipt.json.artifact`，且 receipt 的 `commit/runId/runAttempt` 匹配当前 run。
3. ELF64 / `EM_AARCH64`（183）；复用 `tools/audit-standard-apk.py` 的 `inspect_elf` 检查 `p_align >= 16384`、`(file_offset-address) % 16384 == 0`、RELRO 映射；`audit-standard-apk.py` 本身也已覆盖所有 `lib/` 成员。
4. 体积：见 §3.5。
5. `.so` 不在 `assets/`/`res/` 重复出现；APK 仍 `native-code: 'arm64-v8a'`。

设备侧（R3）：签名 `.dev` 包安装后，`RustNative.version()` 与“实际后端=rust”写入脱敏诊断回执；`System.loadLibrary` 失败自动回 JVM 并记录 `LIB_LOAD_FAILED`（不得静默假装成功）。

### 3.5 APK 体积预算与核查脚本

现状基线（`2c879f1b` 实测，`app/src/main/jniLibs/arm64-v8a/` 14 个 `.so`）：

| 项 | 数值 |
|---|---:|
| 文件名数 | 14 |
| 合计字节 | **1,157,940 B ≈ 1.10 MiB** |
| 最大单件 | `libproroot-runtime.so` 375,120 B |
| 现有预算语义 | 评审主稿 §6.4：“以 1.10 MiB 为零增量基线；CI 记录 strip 后增量，超预算即失败” |

推荐预算（`rust/native-budget.json`，R1 首次实测后可调，调整必须评审）：

```json
{
  "schema": 1,
  "baselineNativeBytes": 1157940,
  "baselineNativeFiles": 14,
  "rustArtifactBytes": 524288,
  "rustWarnBytes": 393216,
  "apkGrowthWarnBytes": 262144,
  "allowedRustName": "libdsha_rs.so",
  "abi": "arm64-v8a"
}
```

依据：主机等效代码（gzip+SHA-256、`opt-level=z`、LTO、strip）实测 332,424 B；Android/bionic 预期同量级，预留到 512 KiB 硬上限、384 KiB 警戒线，超限先做 `opt-level=z`/LTO/`codegen-units=1`/关闭 SHA asm 优化，再超即回到评审，不静默加预算。

`tools/check-rust-native-apk.py` 与 `rust/native-budget.json` 联动：
- `libdsha_rs.so` ≤ `rustArtifactBytes`（硬失败），> `rustWarnBytes` 时 `::warning::` 并写入回执；
- `sum(lib/*.so) ≤ baselineNativeBytes + rustArtifactBytes`；
- 记录 APK 总大小与 `lib/` 解压/压缩字节，供 release 回执对比；`apkGrowthWarnBytes` 只警告不阻断（APK 总量含代码/资源变化）；
- 接入点：`verify-stability.py --stage package` 每个 flavor 的 `audit-standard-apk` 之后、`release-production.yml` 的 `build-unsigned` 核验步骤、`ci-package` 的 `package` 节点。超预算 = 构建失败，不是“警告后继续发布”。

### 3.6 Gradle dependency verification 不受 Rust 影响

- `gradle/verification-metadata.xml` 只校验 Maven/AAR/Plugin 坐标；Rust crate 不是 Gradle 依赖，不新增坐标、不改该文件。
- Rust 供应链由四条独立锁约束：`Cargo.lock`（crate 版本）+ `vendor/` 字节（`cargo vendor --locked` 重生成比对）+ `ci/rust-toolchain.lock.json`（rustc/cargo/target）+ `rust/artifact.lock.json`/`rust-receipt.json`（产物）。不要把 crates 塞进 Gradle verification，也不要给 Gradle 加 cargo 插件。
- Kotlin 批次同时在改 `verification-metadata.xml` 与 `app/build.gradle`；Rust 批次**不碰**该文件，`app/build.gradle` 只加 §3.2 的最小块，降低与 `feat/kotlin-toolchain` 的冲突面。

---

## 4. 首期 ABI

### 4.1 总原则（不可协商）

- **无执行入口**：导出的符号只能是 §4.2 列出的纯函数；没有 `execute`/`system`/`Command`/`spawn`/`fork`/`kill`/`argv`/信号/路径参数。
- **无路径字符串**：Rust 不接收 `String` 路径，不调用 `std::fs::open`/`File::open`；唯一的 fd 访问是 §4.4 的 `read_at`（由 Kotlin 打开并核验，Rust 不 close）。
- **有界**：所有输入/输出有硬上限（§4.2）；单次调用缓冲 ≤ 256 KiB（输入）+ ≤ 256 KiB（输出）+ 固定开销，会话 ≤ 4；不按输入长度分配无界 `Vec`。
- **同步、无线程、无回调**：不引 tokio/rayon；不创建线程；不回调 JVM；不安装信号处理器；不写 stdout/stderr/logcat（G8）。
- **每个入口 `catch_unwind`**；`panic="unwind"`；`unsafe` 只允许集中在 JNI 胶水与 §4.4 fd 读取的两处，crate 级 `#![deny(unsafe_op_in_unsafe_fn)]`，FFI 模块外 `#![forbid(unsafe_code)]`。
- **JNI 卫生**：不保存 `JNIEnv*`/`jobject`/`jclass`/全局引用；每次调用是一个独立 JNI 帧；不跨调用复用 `ByteBuffer` 地址；任何 JNI 调用后 `ExceptionCheck`，pending 即返回错误码、不再调 JNI。
- **不默认启用**：加载失败、config 关闭、CI 未带 artifact 时回 JVM（§5.4）；`verify_plan` 分歧禁止回退（fail-closed）。

### 4.2 通用约定、错误码、结果投影

Java 入口类（R0 落名，A3 可随模块边界移动，但**符号名不可变**）：

```java
package com.deepseekharness.app.util;

public final class RustNative {
  public static final int ABI_VERSION = 1;
  public static native int abiVersion();                    // 返回 1；其它值→拒绝加载
  public static native long digest(int fd, long offset, long length, int algorithm, ByteBuffer out);
  public static native long archivePackCreate(int level);   // >0 会话；<0 错误
  public static native long archivePackUpdate(long session, ByteBuffer in, ByteBuffer out);
  public static native long archivePackFinish(long session, ByteBuffer out);
  public static native int  archivePackAbort(long session);
  public static native long archiveUnpackCreate();
  public static native long archiveUnpackWrite(long session, ByteBuffer in);
  public static native long archiveUnpackRead(long session, ByteBuffer out);
  public static native int  archiveUnpackAbort(long session);
  public static native int  verifyPlan(ByteBuffer view, int viewLength, ByteBuffer reasons, int maxReasons);
}
```

JNI 符号按类名生成：`Java_com_deepseekharness_app_util_RustNative_<method>`；`extern "system"`；导出符号白名单在 `tools/check-rust-boundaries.py` 里逐项核对（多一个导出即失败）。故障注入只在 `#[cfg(test)]`/测试 feature 的 crate 测试构建中存在，**production ABI 的导出符号白名单不含它**；release 产物由符号白名单与 `strings` 扫描双重证明。

**错误码**（i32，0 = 成功；负数错误；纯函数不返回 Java 异常）：

| 码 | 名称 | 触发 |
|---:|---|---|
| 0 | `OK` | 成功 |
| -1 | `INVALID_ARGUMENT` | 空指针/负 offset/非法长度/超 ABI 上限 |
| -2 | `INPUT_BOUND` | 单次/累计输入超上限 |
| -3 | `OUTPUT_BOUND` | 输出缓冲不足（调用方必须重试更大缓冲；不产生半成品） |
| -4 | `STALE_HANDLE` | 会话不存在/已结束/代次不符 |
| -5 | `SESSION_LIMIT` | 并发会话超上限（建议 4） |
| -6 | `INTERNAL` | 内部不变量失败（不泄露细节） |
| -7 | `PANIC` | `catch_unwind` 捕获（会话标记失败） |
| -8 | `JNI_PENDING` | JNI 异常 pending，立即返回 |
| -9 | `SHORT_READ` | `pread` 读到的字节 < 请求长度 |
| -10 | `UNSUPPORTED_ALGORITHM` | algorithm 不在 {SHA-256, SHA-1} |
| -11 | `GZIP_HEADER` | magic/CM/flags 非法 |
| -12 | `GZIP_HEADER_CRC` | FHCRC 不符 |
| -13 | `GZIP_METADATA_LIMIT` | FNAME/FCOMMENT > 8192 |
| -14 | `GZIP_TRUNCATED` | 头/体/尾提前 EOF |
| -15 | `GZIP_DEFLATE` | deflate 数据非法 |
| -16 | `GZIP_DICTIONARY` | 需要预设字典（拒绝） |
| -17 | `GZIP_STALLED` | inflate 无进展（防御） |
| -18 | `GZIP_TRAILER` | CRC32/ISIZE 不符 |
| -19 | `GZIP_TRAILING_DATA` | 单成员结束后仍有字节 |
| -20 | `PLAN_STRUCTURE` | magic/长度/字段顺序/类型 |
| -21 | `PLAN_FIELD_RANGE` | 字段值越界 |
| -22 | `PLAN_POLICY_VERSION` | 策略版本不在允许集合 |
| -23 | `PLAN_EXPIRED` | `nowMs >= expiresAt` |
| -24 | `PLAN_LIMIT` | 字段数/字符串/计数超上限 |
| -25 | `PLAN_UNKNOWN_FIELD` | 未知关键字段 |
| -26 | `NOT_IMPLEMENTED` | 预留（ABI 版本不匹配时也用它） |

`GZIP_*` 与现有 [`StrictGzipInputStream`](../../../app/src/main/java/com/deepseekharness/app/backup/StrictGzipInputStream.java) 的 `IOException` 消息逐一对应（`GZIP_HEADER`、`GZIP_FLAGS` 合入 header 码；`STREAM_CLOSED` 由 Kotlin 管，不跨 FFI）。

**正交结果字段投影**（ADR 决定四“结果不是枚举，枚举只是投影”的落实）：

首期纯函数没有“派发/执行/效果”，不得把多个含义折成一个 `DONE/FAILED`。每个操作包装为：

```
attempted:  NOT_ATTEMPTED | ATTEMPTED
completed:  NOT_COMPLETED | COMPLETED | UNKNOWN
output:     NONE | PARTIAL | COMPLETE | UNKNOWN
verified:   NOT_CHECKED | VERIFIED | MISMATCH | UNKNOWN | NOT_APPLICABLE
retry:      SAFE | NEW_SESSION | NOT_SAFE | UNKNOWN
```

- `pack`：`attempted` 由是否进入 Rust 决定；`output` 区分“没产出/产出一部分/完整”；`verified` 只表示流尾部 CRC/长度是否核验（pack 恒 `NOT_APPLICABLE` 或 `VERIFIED`，不得与“输入校验通过”混用）。
- `unpack`：`verified=VERIFIED` 只在 CRC/ISIZE/无尾随数据都通过时成立；`output=PARTIAL` 与 `retry=NOT_SAFE` 不得互相代替。
- `digest`：`output=NONE`（只返回摘要与长度），`verified=VERIFIED` 表示长度与 fd 读取一致。
- `verify_plan`：`VALID` 不写成“成功”，`INVALID` 带**原因集合**（最多 8 条，按字段序）；JVM 与 Rust 都返回集合，差分比较集合而不是首错。
- 投影到旧三态（仅日志/界面，禁止用于控制流）：`DONE` = `completed=COMPLETED && output=COMPLETE && verified in {VERIFIED, NOT_CHECKED}`；`CANCELLED` = `attempted=ATTEMPTED && completed=NOT_COMPLETED && output=PARTIAL`；其余 `UNKNOWN`。投影表随 A3 契约冻结，任何字段语义变化必须改表并评审。ADR 的 `dispatched/executed/effect` 五字段属于将来的执行路径，本批不变；将来实现执行路径时另立 ADR，不能复用本投影。

### 4.3 `digest(fdRange, algorithm)`

输入契约：

| 参数 | 约束 | 谁保证 |
|---|---|---|
| `fd` | Kotlin 打开并 `fstat` 过的私有文件/受控流；调用期间保持打开；Rust 不 close | A2/A3 的文件事务层；Rust 只读 |
| `offset` | `>= 0`；`offset + length` 不溢出；Kotlin 已核验 `offset+length <= stat.size` | Kotlin；Rust 仍做非负/上限检查 |
| `length` | `1..=33,554,432`（32 MiB，沿用现有单次推/拉上限）；大文件由 Kotlin 分段循环 | 两侧 |
| `algorithm` | `1=SHA-256`，`2=SHA-1`（与代码中仅有的两种 `MessageDigest` 一致） | Kotlin 映射；未知值返回 `UNSUPPORTED_ALGORITHM` |
| `out` | direct `ByteBuffer`，`capacity >= 32`；摘要写入 `[0, size)` | Kotlin |

返回：`bytesRead`（0 ≤ n ≤ length）；`n < length` → `SHORT_READ`；任何错误返回负码，`out` 不写或写 0（调用方必须丢弃）。Rust 内部用 256 KiB 缓冲循环 `read_at`，不 `stat`、不 `mmap`、不 `open`、不 `close`；`fd` 通过 `ManuallyDrop<File>::from_raw_fd` + `FileExt::read_at` 使用（不引入 `libc` 依赖），因此“无效 fd”返回 `EBADF`（映射 `INVALID_ARGUMENT`），不会 UB。

### 4.4 `archive_pack`

`archive_pack` 是**流式 gzip 编码会话**；tar/容器框架仍由 JVM 写：

```text
create(level) -> handle
update(handle, in, out) -> (consumed << 32) | produced   // 两者都 <= 2^31-1
finish(handle, out)     -> produced (或负错误码)
abort(handle)           -> 0 / 负错误码
```

- `level`：0–9（与 `Deflater` 兼容）；0 为 store；默认 6；越界 `INVALID_ARGUMENT`。
- `in`：direct buffer，单次 `1..=262,144`（256 KiB，沿用现有 SAF 读块）；`out`：direct buffer，调用方必须 ≥ `packBound(in.remaining()) = n + n/8 + 128`（覆盖 deflate 最坏膨胀）；不足即 `OUTPUT_BOUND`，不产生半块状状态（会话内保留未消费输入，可重试）。
- 输出必须是**单成员 RFC 1952 gzip**：magic `1f 8b`、CM=8、FLG=0、MTIME=0、XFL=0、OS=0（与 Java `GZIPOutputStream` 的固定头一致；JVM 严格读取器不校验 OS/XFL，但仍按同一形状输出，便于逐字段诊断），尾部 CRC32 + ISIZE（mod 2^32）。
- `finish` 输出最终块 + 尾部；之后会话失效；`abort` 可随时调用，不要求输出完整。
- **不比 gzip 字节**：与 JVM 对拍的是“Rust pack → JVM `StrictGzipInputStream` 解出的字节 + JVM 再 pack 的载荷摘要”，以及“JVM pack → Rust unpack 的字节”。理由：压缩器实现不同，gzip 字节天然不同；格式兼容性由“JVM 严格读取器接受 + 尾部校验”证明。

### 4.5 `archive_unpack`

镜像 [`StrictGzipInputStream`](../../../app/src/main/java/com/deepseekharness/app/backup/StrictGzipInputStream.java) 的语义，单成员、严格：

```text
create() -> handle
write(handle, in)  -> consumed (或负错误码)          // 喂压缩字节
read(handle, out)  -> (produced << 32) | flags       // flags: 0x1 EOF, 0x2 NEED_INPUT, 0x4 NEED_OUTPUT
abort(handle)      -> 0 / 负错误码
```

- 头：`1f 8b 08`；`FLG & 0xe0` 必须为 0；FHCRC（FLG bit1）按现有 Java 实现校验；FEXTRA 长度、FNAME/FCOMMENT 各 ≤ 8192（含终止 0）；`FLG` 的 FTEXT 位允许但忽略；不允许多成员与尾随字节（解到尾部后输入仍有字节 → `GZIP_TRAILING_DATA`）。
- 体：raw deflate（`Decompress::new(false)`）；`needs_dictionary` → `GZIP_DICTIONARY`；无进展 → `GZIP_STALLED`；输出累计 ≤ `BackupLimits.BYTES + 256 MiB`（16 GiB + 256 MiB，与现有读取器一致），单次 `read` 输出 ≤ 256 KiB。
- 尾：CRC32 与 ISIZE 都必须匹配；截断 → `GZIP_TRUNCATED`；先校验尾、再拒绝尾随字节（与 Java 顺序一致）。
- 状态机：`write` 返回实际消费的输入字节数（内部输入缓冲满时可只消费一部分）；`read` 用 `NEED_INPUT`/`NEED_OUTPUT` 指示推进条件，调用方循环至 `EOF`；`EOF` 只在尾部校验通过后返回；任何错误后会话进入失败态，`abort`/重开新会话，不重放已消费输入。
- 对拍：同一 gzip 语料上 JVM 与 Rust 的“输出字节 + 错误码”逐一相等；`write` 分段方式（1 B/64 KiB/随机切分）不得改变结果。

### 4.6 `verify_plan(view)`

Rust **不解析 JSON**。Kotlin 侧（A3）把计划候选投影为规范化 `PlanView`，同时用 JVM 校验器校验同一字节；Rust 是第二实现：

```text
verifyPlan(view, viewLength, reasons, maxReasons) -> 0 VALID / 1 INVALID(看 reasons) / 负错误码
reasons: direct buffer，写入 u32 count + count 个 u32 原因码（按字段序/码序），count <= maxReasons
```

`PlanView` v1 布局（小端，字段按 id 升序，无重复；总长 ≤ 2 MiB = `BackupLimits.MANIFEST`）：

| 偏移 | 类型 | 字段 |
|---:|---|---|
| 0 | bytes[4] | magic `DSHP`（`44 53 48 50`） |
| 4 | u16 | viewVersion = 1 |
| 6 | u16 | policyVersion（允许集合由 A3 契约冻结，首期只 {1}） |
| 8 | u32 | totalLength（必须等于 viewLength） |
| 12 | u32 | fieldCount（≤ 64） |
| 16 | u32 | flags（bit0=hasExpiry） |
| 20 | u64 | generatedAtMs（Kotlin 时钟） |
| 28 | u64 | expiresAtMs（0=无） |
| 36 | u64 | nowMs（Kotlin 传入的“当前时间”，使函数保持纯） |
| 44 | u32 | reserved（必须 0） |
| 48… | — | 字段区：`u16 id, u8 type, u8 flags, u32 length, value[length]` |

字段 id（首期，不含路径/命令）：`1 formatVersion(u32)=5`、`2 operation(u8)`、`3 integrity(u8)`、`4 rootCount(u32)≤64`、`5 entryCount(u32)≤100000`、`6 byteCount(u64)≤16 GiB`、`7 createdAtMs(u64)`、`8 sourceDigest(bytes32)`、`9 selectedMask(u32)`、`10 targetMappingKind(u8)`、`11 appVersion(utf8≤256)`、`12 dataFormat(utf8≤64)`、`13 sensitivePolicy(utf8≤64)`、`14 pluginCount(u32)≤4096`、`15 planDigest(bytes32)`。

校验规则：magic/长度/字段顺序/类型/重复；范围按上表；`policyVersion` 只接受允许集合；`hasExpiry` 时 `expiresAtMs > generatedAtMs` 且 `nowMs < expiresAtMs`（否则 `PLAN_EXPIRED`）；未知关键字段拒绝（`PLAN_UNKNOWN_FIELD`）；字符串必须 UTF-8、无 NUL、非空；摘要长度固定。**多原因**：不因首错提前返回，返回所有可判定原因（最多 8 条），JVM/Rust 差分比较集合。**无副作用**：不写日志、不写文件、不改变入参；`nowMs` 只是输入，Rust 不读系统时钟。

需要强调：`verify_plan` 只做“结构/范围/策略版本/有效期”的只读校验，**不证明计划安全、不替代 Kotlin 的授权判断**；`PlanEnvelope`/`permitMac` 不在本 ABI（ADR 决定四）。

### 4.7 `panic=unwind`、`catch_unwind`、JNI 规则

```rust
#[no_mangle]
pub extern "system" fn Java_com_deepseekharness_app_util_RustNative_digest(
    env: *mut jni_sys::JNIEnv, _class: *mut jni_sys::jclass, fd: jint, offset: jlong,
    length: jlong, algorithm: jint, out: *mut jni_sys::jobject,
) -> jlong {
    match std::panic::catch_unwind(AssertUnwindSafe(|| {
        // 只做参数校验 + read_at；不调用可能抛 Java 异常的 JNI 函数
        inner_digest(env, fd, offset, length, algorithm, out)
    })) {
        Ok(value) => value,
        Err(_) => ERR_PANIC as jlong,
    }
}
```

> 代码中的 `jni_sys` 仅表示 JNI 类型；实现使用 NDK 的 `jni.h` 常量与 `std::ffi` 基元类型，**不引入 jni-rs**。

- **每个入口**（包括 `create/update/read/finish/abort/verifyPlan/abiVersion`）都包 `catch_unwind`；`catch_unwind` 只覆盖可展开 panic，不覆盖 abort/SIGSEGV/OOM（失败域见 §5.3）。
- 不使用 `panic!`/`unwrap`/`expect`/数组索引越界路径（clippy `unwrap_used`/`expect_used` deny；测试代码例外）；所有错误走 `Result`。
- 不在 Rust 里打日志（G8）：首次 native 调用前装一个**不输出任何内容**的 panic hook（只计数/置全局标记），由 `catch_unwind` 返回错误码；release 中 `std::io::{stdout,stderr}` 禁止出现；`dbg!`/`println!` 由边界脚本拦截。
- **JNI 调用后 `ExceptionCheck`**：虽然本设计只调用 `GetDirectBufferAddress`/`GetDirectBufferCapacity`（不抛异常，可能返回 null），仍在每次 JNI 调用后检查；pending → 立即返回 `JNI_PENDING`，不清理异常、不再调 JNI；由 JVM 在 native 返回后抛出/处理。
- **不跨调用持 `JNIEnv`/引用**：不保存 `env`、不建全局/弱全局引用、不把 `jobject` 存进会话；每个 native 方法是一次独立 JNI 帧，返回后局部引用自动释放；Kotlin 包装器若连续调用，每次都是一次新的 native 调用（必要时在 Kotlin 侧用 `PushLocalFrame`/`PopLocalFrame` 包裹循环，Rust 不参与）。
- **直接缓冲生命周期**：Kotlin 必须在调用期间保持 `ByteBuffer` 强引用且不做并发修改；Rust 只在该次调用内使用地址；不缓存指针。
- **不加信号处理器、不接管崩溃处理**：SIGSEGV/abort 让进程按 Android 原生方式崩溃（会带走 App，见 §5.3），Rust 不得吞掉或改写。
- **无 TLS/无静态可变状态**：会话表是 `OnceLock<Mutex<BTreeMap<u64, Session>>>`，只保存纯数据；`handle = 单调 u64 + 代次`，不把 JVM 指针当句柄（JVM 误用 → `STALE_HANDLE`，不是 UB）。
- **线程**：Rust 不建线程；会话表用 `Mutex` 保护，Kotlin 包装器仍约定“一会话一调用线程”，并发会话 ≤ 4；不依赖 JVM `AttachCurrentThread`（没有回调线程）。

### 4.8 边界证明（G7/G8 的可执行检查）

`tools/check-rust-boundaries.py`（R0，CI `rust-check`/`rust-native` 双跑）：

1. **导出符号白名单**：`readelf --dyn-symbols`/`nm -D` 只允许 `Java_com_deepseekharness_app_util_RustNative_<§4.2 方法>`；出现其它 `glob` 符号（`system`、`exec*`、`fork`、`kill`、`open*`、`posix_spawn` 等）即失败。测试 feature 的故障注入符号不得进入 release 产物。
2. **源码禁用面**：对 `rust/crates/**` 扫描 `std::process`、`std::fs`（`File::open`/`OpenOptions`）、`std::net`、`Command`、`unsafe {`（仅允许 `src/jni.rs` 与 `src/fd.rs` 两个文件）、`println!`/`eprintln!`/`dbg!`/`log::`/`android_logger`、`libc::` 等；每条命中要么在允许清单（附理由与文件:行），要么失败。
3. **依赖白名单**：`cargo tree --locked` 只允许 `flate2/miniz_oxide/crc32fast/sha2/sha1` 及其传递依赖（R0 基线 16 个 crate）；新增 crate 必须同时更新 `rust/Cargo.lock`、vendor、`ci/rust-toolchain.lock.json` 的白名单与许可段，并走评审。
4. **构建脚本审查**：`vendor/**/build.rs` 必须在 `ci/rust-toolchain.lock.json` 中逐个登记（路径 + SHA-256 + 允许行为）；CI 比对字节；当前 3 个只做 `rustc --version` 探测，不得出现网络/写文件/执行参数。
5. **G8**：release 产物 `strings` 扫描不允许出现 `println`/panic 文本中的路径/敏感值；crate 无日志依赖；FFI 错误只走返回码。
6. **接口反例**（评审用）：新增任何 `execute/argv/path` 形状的 JNI 方法都必须在同一次评审中被拒绝——脚本没有“方便开关”，只有 ADR 才能改边界。

---

## 5. Java↔Rust 差分测试与回退开关

### 5.1 测试层次

| 层 | 跑在哪 | 证明什么 | 何时 |
|---|---|---|---|
| Rust 单元/属性测试（`cargo test --offline --locked`） | 主机（本地 aarch64 / CI x86_64） | gzip 状态机、CRC/ISIZE、边界、错误码、never-panic（故障注入仅测试 feature） | R0 起每 PR |
| JVM 差分测试（JUnit + 主机 `.so`） | 主机 JVM（`-Xcheck:jni`） | 同一输入下 JVM 与 Rust 的字节/摘要/原因集合 100% 一致；主机 `.so` 与 App 同源 | R1 起，`rust-differential` job；full 单测 0 skipped |
| Android 静态门禁 | CI | `.so` ABI/16K/NEEDED/大小/receipt/APK 成员 | R0 只对 artifact；R1 起（build-unsigned） |
| Android 真机冒烟 | 签名 `.dev` 包 + 用户授权 | `System.loadLibrary`、实际备份/恢复/导出闭环、16 KB 页设备 | R3（A3 证据） |

**为什么差分在主机 JVM**：Java 单测（Robolectric/JUnit）跑在 x86_64 CI/本地 JVM，不能加载 arm64 `.so`。同一 crate 为主机目标再构建一份 `.so`（同样的 `extern "system"` 符号）供 JVM `System.load(absPath)` 使用；本机已实测可行。**注意**：这证明的是“同一源码的主机实现”，不等于“Android `.so` 已加载”；后者由 R3 设备冒烟与 APK 静态门禁覆盖，二者不能互相冒充。

### 5.2 对拍矩阵

| 函数 | 同一输入 | 必须一致 | 允许不同 |
|---|---|---|---|
| `archive_pack` | 同一载荷 + level + 分块方式 | 解压后字节、载荷 SHA-256、JVM 严格读取器接受、尾部 CRC/ISIZE 正确 | gzip 字节（压缩器实现不同）；压缩率 |
| `archive_unpack` | 同一 gzip 语料（合法 + 19 类非法） | 输出字节、错误码、EOF 时机、拒绝尾随/多成员/坏 CRC/坏 ISIZE/字典/截断 | 错误消息文本（不跨 FFI，只有码） |
| `digest` | 同一 fd 范围 + 算法 + 分块 | SHA-256/SHA-1 十六进制、`bytesRead`、错误码 | 无 |
| `verify_plan` | 同一 `PlanView` 字节 | `VALID/INVALID` + 原因码集合（含字段序）、边界行为 | 无（分歧 = fail-closed 缺陷） |

夹具：`tools/rust/fixtures.json`（小样本，十六进制/JSON 描述）+ `tools/rust/make-fixtures.py`（确定性生成大样本到 `app/build/`，不入库）。语料至少覆盖：空载荷、1 B、64 KiB、256 KiB、level 0/1/6/9、FNAME/FCOMMENT 上限、FHCRC、保留 FLG、坏 magic/CM、截断在头/体/尾、坏 CRC32、坏 ISIZE、trailing 1 B、双成员、需要字典、inflate 无进展、非法长度；digest 覆盖 offset 0/中间/末尾、length 1 B/64 KiB/32 MiB、短读、越界、SHA-1/SHA-256、分块等价；plan 覆盖 §4.6 每条规则与边界值（100000/100001、16 GiB±1、expiry 前后 1 ms）。

随机化：确定性种子（固定 `seed` 写入测试报告）生成 256 组随机分块/载荷，失败可复现；不引入 proptest/cargo-fuzz 依赖（控制 vendor 面）。

### 5.3 失败域（与 ADR 失败域矩阵一一对应）

| 域 | 触发 | 可否捕获 | 具体措施 | 测试方式 |
|---|---|---|---|---|
| 可展开 panic | 逻辑错误/显式 `panic!`（仅测试 feature） | 是 | 每入口 `catch_unwind` → `ERR_PANIC`；不跨 FFI unwind；会话标记失败 | `cargo test` 故障注入（测试 feature，release 无此代码） |
| abort（OOM/双 panic） | 分配失败、`panic=abort`（禁止） | 否 | `panic="unwind"`；缓冲/会话硬上限；不在 FFI 内做大分配；不 `Vec::with_capacity(input_len)` | 不能安全单测；评审 + 上限测试；设备禁止故障注入 |
| SIGSEGV/BUS/UB | 非法指针/越界/栈溢出 | 否 | `unsafe` 仅 JNI 胶水 + `read_at`；`from_raw_parts` 前校验 null/len；无递归；不缓存裸指针 | 评审 + Miri（可选，主机）；`-Xcheck:jni` |
| 栈溢出 | 递归/大栈帧 | 否 | 无递归；固定缓冲在堆/栈上限内；`opt-level` 不改变 `main` 线程栈假设 | 代码审查 + 边界语料 |
| JNI 异常 pending | JNI 函数抛异常 | 可检测 | 每次 JNI 调用后 `ExceptionCheck`；pending → `JNI_PENDING`，不再调 JNI | `-Xcheck:jni` + 无效参数测试 |
| 内存生命周期 | 跨线程 JNIEnv/全局引用/局部帧 | 设计避免 | 不存 `JNIEnv`/引用；每调用新帧；直接缓冲不缓存地址 | 代码审查 + `-Xcheck:jni` |
| 取消/超时 | Kotlin deadline | 协作式 | Rust 只做单次有界块；Kotlin 停止喂数据并 `abort`；不把“没做完”当“没做” | 测试 `abort` 后新会话可从 0 开始 |
| fd 生命周期 | Kotlin 提前 close | 否（EBADF 安全） | Rust 不 close；无效 fd 返回错误，不 UB | 测试在调用中 close（JVM 侧）→ 错误码 |

**明确不测**：abort/OOM/SIGSEGV 不在生产数据/真机上做故障注入（AGENTS 禁止）；这些域的接受是“设计预防 + 评审 + 可选主机 Miri”，不是“测试通过”。

### 5.4 回退开关（一键回 JVM）

两层开关，职责不同：

1. **构建层**（决定 APK 是否含 `.so`）：`-Pdsha.rustNative=off|prebuilt|build`；release 必须 `prebuilt` 且 receipt 校验通过，否则构建失败。这样“不含 Rust 的包”不会被误发布成“Rust 已启用”。
2. **运行层**（决定实际走哪个实现）：`RustBackends.enabled`，持久化在现有配置命名空间（A3 实现时决定 key；`ConfigStore` 是唯一入口，不改历史键名）。默认值：R1–R2 默认 `false`；R3 灰度默认 `false`，A3 出口且设备证据通过后翻为 `true`（一次布尔值改动的“一键回退”）。诊断/维护回执记录有效值与来源。

回退矩阵：

| 场景 | 行为 | 证据 |
|---|---|---|
| 构建未含 `.so` | 全走 JVM；`attempted=NOT_ATTEMPTED`；无错误 | 构建 receipt 记录 `rustArtifact=none` |
| `System.loadLibrary` 失败（UnsatisfiedLinkError/ABI 不符） | 全走 JVM，记录 `LIB_LOAD_FAILED`（类型化，不含路径细节）；不重试加载 | 诊断回执；`RustBackends` 状态 |
| 运行层开关关闭 | 全走 JVM | 配置值写入回执 |
| Rust 返回确定性错误（输入非法/边界） | **不重试**，直接按错误失败（输入非法不是“后端失败”，重试 JVM 只会掩盖调用方错误） | 错误码 |
| Rust 返回内部错误/panic（`ERR_INTERNAL/ERR_PANIC`） | 丢弃本会话全部输出，**JVM 重做一次**（纯函数、可重算），结果标 `fellBack=true`，不得写成“Rust 成功”；若 JVM 也失败则整体失败。**前提**：本次输出目标是可丢弃的私有临时文件（A3 的写入事务满足），先截断/换新目标再重做；**不得对已提交或已追加的目标续写** | 回执 `fallbackReason` |
| `verify_plan` 不一致 | **fail-closed，禁止回退**：视为第二实现/契约缺陷，阻断操作并上报；不得用 JVM 结果“覆盖”以让操作继续 | 差分失败即 CI/运行时硬失败 |
| 进程被 abort/SIGSEGV 杀死 | 没有回退机会；App 重启后按正常启动处理；不得声称“已回退成功” | 启动诊断/崩溃记录（脱敏） |

“一键”的语义：用户/维护侧只需改一个布尔（或发一版构建层 `off` 的包）即可回到全 JVM；**回退不需要清数据、不改变已写备份格式**。`archive_pack` 的 gzip 输出由 JVM 读取器验证；回退后所有备份仍可读。

### 5.5 证据如何进 release 回执

`rust-receipt.json`（由 `rust-native` job 生成，随 unsigned handoff 传给 sign/publish）：

```json
{
  "schema": 1,
  "kind": "dsha-rust-native",
  "commit": "<40位>", "runId": "...", "runAttempt": "...",
  "toolchain": {"rustc": "1.100.0 (0ef1bc386 2026-11-09)", "cargo": "1.100.0",
                 "target": "aarch64-linux-android", "androidApi": 23,
                 "ndk": "26.3.11579264", "linker": "aarch64-linux-android23-clang"},
  "sources": {"cargoLockSha256": "...", "vendorTreeSha256": "...", "crateSha256": "...",
               "buildScripts": ["crc32fast/build.rs", "generic-array/build.rs", "libc/build.rs"]},
  "artifact": {"file": "libdsha_rs.so", "size": 0, "sha256": "...", "strip": "symbols"},
  "checks": {"elfMachine": 183, "aligned16k": true, "relroMapped": true,
              "needed": ["<R0 实测后锁定的 bionic 库白名单>"], "boundaryScan": "PASS", "budget": "PASS"},
  "tests": {"cargo": 0, "differential": 0, "skipped": 0},
  "differential": {"reportSha256": "...", "status": "PASS"}
}
```

> `needed` 在 R0 首次 Android 链接后按实际 `DT_NEEDED` 锁定；只允许 bionic 系统库（如 `libc.so`/`libdl.so`），出现 app 私有路径或多余依赖即失败。

进入 Release 的位置：
- `build-unsigned` 的 `out/unsigned-handoff/rust-receipt.json`；
- `sign-verify` 复核并复制到 `out/`，`SHA256SUMS` 包含它；
- `build-info.json` 增加 `"rust": {"artifactId": ..., "sha256": ..., "size": ..., "rustc": ..., "target": ..., "differential": "PASS", "fallbackDefault": false}`；
- `docs/releases/` 的 A3 验收稿引用 receipt 摘要与设备证据，**不**把 receipt 当“真机已验证”（设备证据单独记录）；
- 回退/未启用时，`rust` 对象明确写 `artifact=none`/`enabled=false`，不省略。

---

## 6. 任务拆分 R0 → R1 → R2 → R3

每一步都必须满足：可独立回退、不动数据格式、不扩 Android 权限、CI 全绿、非实现者评审。R0 可在 A2/A3 之前做，但**必须等 Kotlin 工具链批次合入 main 后才解锁**（§0.3）；R1–R3 与 A3 排序（见 §8）。

### R0 工具链骨架（无运行行为）

| 项 | 内容 |
|---|---|
| 目标 | 证明“离线 vendor + 固定工具链 + CI arm64 `.so` + 主机 JNI 差分 + 静态门禁”闭环；**不接入任何生产路径** |
| 改动文件（预计） | `rust/**`（骨架、vendor、Cargo.lock、rust-toolchain.toml、.cargo/config.toml）、`ci/rust-toolchain.lock.json`、`tools/rust/build.py`、`tools/check-rust-toolchain.py`、`tools/check-rust-boundaries.py`、`tools/check-rust-native-apk.py`（stub）、`rust/native-budget.json`、`.github/workflows/_node-rust.yml` + `ci-fast.yml`/`ci-package.yml`/`release-production.yml`（仅 rust-native job，不接 build-unsigned）、`tools/test-ci-workflows.py`、`tools/check-source-hygiene.py`（vendor 白名单）、`.gitattributes`（vendor `-text`）、`tools/generate-third-party-notices.py`（Rust 段）、`THIRD_PARTY_NOTICES.md`（生成）、`app/src/main/assets/licenses/rust/**` |
| 验收 | ① `cargo vendor --locked` 重生成与入库树一致；② `cargo test --locked --offline` 通过；③ CI `rust-native` 产出 arm64 `.so` + receipt，ELF/16K/边界/体积 PASS；④ 本机 `cargo check --target aarch64-linux-android` + 主机 JNI smoke PASS；⑤ 全量单测/文档/边界/格式不回归；⑥ **APK 与用户可见行为完全不变**（Rust artifact 尚未接入 build-unsigned） |
| 依赖 | 无（不需要 A3/A2）；G-R0-A/B/C 已由 Lead 决定（§0）；**R0 解锁需等 Kotlin 工具链批次合入 main**（§0.3） |
| 回退点 | 删除 `rust/` 与新增 CI job/tools，恢复 `check-source-hygiene`/`.gitattributes`/notices 生成器；无数据/版本变化 |

### R1 digest

| 项 | 内容 |
|---|---|
| 目标 | `digest(fdRange, algorithm)` 生产级入口 + JVM 回退 + 差分（SHA-256/SHA-1、offset/limit/短读/分块） |
| 改动文件（预计） | `rust/crates/dsha-core/src/{digest.rs,jni.rs,lib.rs}`、`rust/crates/dsha-core/tests/*`、`app/src/main/java/com/deepseekharness/app/util/{RustNative.java,RustDigest.java,RustBackends.java}`（纯 Java、零 Android import；**不放在 `backup/`**，避免 R1 提前改变 `launcherInputs`）、`app/src/main/java/com/deepseekharness/app/backup/BackupArchive.java`（单个调用点，仅在 A3 分支就绪后）、`app/src/test/java/com/deepseekharness/app/util/RustDigestDifferentialTest.java`（主机 `.so` 差分）、`app/build.gradle`（test property `dsha.rust.hostLib`/`dsha.rust.required`）、`_node-unit-tests.yml`（`rust_host` 输入）、`_node-rust.yml`（`rust-differential`） |
| 验收 | ① 与 `MessageDigest` 的 SHA-256/SHA-1 对拍 100%（含 32 MiB 单次与分块）；② 短读/越界/fd 关闭/算法未知返回规定码；③ full 单测 0 skipped（带 `rust_host: true`）；④ 运行层开关默认关闭也能跑全部 JVM 路径；⑤ 回退 receipt 记录 `fallbackReason` |
| 依赖 | R0；A3 备份代码合入后接调用点（否则只落桥和测试，不改生产路径） |
| 回退点 | 运行层开关 false + 删除调用点；数据格式不变；tag |

### R2 verify_plan

| 项 | 内容 |
|---|---|
| 目标 | `PlanView` 编解码 + JVM 校验器（从 `NativeRestorePlan`/`BackupArchive.validateSummary` 抽取的**同一投影**）+ Rust 第二实现 + 集合差分 |
| 改动文件（预计） | `rust/crates/dsha-core/src/{plan.rs,jni.rs}`、`tests/plan_*`、`app/src/main/java/com/deepseekharness/app/util/PlanViewCodec.java`/`PlanViewValidator.java`（纯逻辑；A3 若按模块边界移动再评审；R2 不提前改 `backup/`）、`app/src/test/java/.../PlanViewDifferentialTest.java`、`tools/rust/fixtures.json`/`make-fixtures.py` |
| 验收 | ① 每条 §4.6 规则都有正/反例；② JVM/Rust `VALID/INVALID` 与原因集合 100% 一致；③ 分歧 fail-closed（测试断言“不得回退继续”）；④ 不解析 JSON、不接收路径/命令；⑤ 无副作用（同输入重复调用结果相同） |
| 依赖 | R0；A3 的恢复计划契约冻结（字段 id/范围/策略版本） |
| 回退点 | Rust 开关 false；JVM 校验器保留；不改数据格式 |

### R3 archive（pack/unpack）与 A3 集成

| 项 | 内容 |
|---|---|
| 目标 | gzip 编解码接入 `PortableBackupEnvelope`（pack）与 `StrictGzipInputStream` 的替代/包装（unpack），差分 + 设备冒烟 + release 证据 |
| 改动文件（预计） | `rust/crates/dsha-core/src/{gzip.rs,jni.rs}`、`app/src/main/java/com/deepseekharness/app/util/{RustGzipOutputStream.java,RustGzipInputStream.java,RustArchiveCodec.java}`（纯 Java、零 Android import）、`app/src/main/java/com/deepseekharness/app/backup/PortableBackupEnvelope.java`、`StrictGzipInputStream.java`（或并存选择器；**这两个是 A3 分支的调用点，R3 只改端口，不与 A3 抢写**）、`app/src/test/java/.../RustArchiveDifferentialTest.java`、`release-production.yml`（test-packages 接 rust artifact）、`_node-test-packages.yml`（可选输入）、`docs/releases/*`（A3 证据）、`THIRD_PARTY_NOTICES.md`（最终 Rust 段） |
| 验收 | ① Rust pack 输出被 JVM `StrictGzipInputStream` 与 Rust unpack 都接受；② 同一语料 JVM/Rust 解压字节、摘要、清单 100% 一致；③ v5 备份/恢复/导出闭环在开关两种模式下都通过；④ 签名 `.dev` 包真机加载 `.so`，强制 Rust 模式完成一次备份+恢复（非破坏性；用户已授权，Lead 执行，§0.1 Q5）；⑤ 16 KB 页设备（若有）加载与运行；⑥ 体积 ≤ 预算；⑦ 回退开关一键回 JVM，数据不变 |
| 依赖 | R0/R1；A3 备份核心、A2 文件事务、A3 夹具冻结；设备验收授权 |
| 回退点 | 运行层开关回 JVM + tag；`.so` 可在下一版 build 中移除（构建层 off）；数据不变 |

**R2/R3 可否并行**：crate 内模块互不依赖，可以并行开发；但 `backup/` 调用点共享写域，必须按 A3 的实际文件归属串行合入（R1 的 `BackupArchive` 调用点 → R3 的 envelope 端口），避免同一文件双写。

---

## 7. 风险与未决

| # | 风险/未决 | 证据/现状 | 缓解 | 止损 |
|---|---|---|---|---|
| Q1 | **本机 aarch64 无法直接跑 NDK clang**（官方 `linux-x86_64` 工具链；无 qemu/binfmt） | §2.6 实测；`tools/native-session/build.py`/`proroot-compat` 同样映射 `linux-x86_64` | **已决（§0.1 Q2）**：路径 A 为默认，暂不做 B/C spike（1 人日留给 A3 真实缺口） | 本机不做强制交叉；R0 出口为“CI 产物 + 主机差分 + 设备加载” |
| Q2 | **CI runner**：`ubuntu-24.04` 预装 NDK 27/29/30，26.3 需 `sdkmanager` 下载 668 MB；10 分钟超时/2 次重试 | `.github/actions/android-sdk/action.yml` 已实现“缺才装 + 超时重试” | 复用该 action；`rust-native` 与 `build-unsigned` 并行，不阻塞门禁；必要时缓存 NDK 目录（注意缓存体积） | 安装失败即 job 失败；不用“跳过 Rust”发布 |
| Q3 | **vendor 体积/许可审计**：7,384,177 B / 16 crate；3 个 `build.rs`（`crc32fast`/`generic-array`/`libc`，均有 `Command` 探测 rustc）；`check-source-hygiene` 会误报 13 个 CRLF 文件 | 本机 `cargo vendor`/`cargo metadata` 实测；全部 crate 许可为 MIT/Apache-2.0/0BSD/Zlib（`version_check` 写作 `MIT/Apache-2.0`，需规范化） | **Lead 有条件批准（§0.2）**：vendor 清单/许可摘要已写入；3 个 `build.rs` 与 `version_check` 审查结论=无网络/无仓库外写/不读凭据；R0 锁 profile+16KB；`check-source-hygiene`/notices/APK `lib/` 门禁按原计划 | 超 8 MiB 或出现非宽松许可即回退该 crate/放弃 Rust 旁路（保留 JVM） |
| Q4 | **16 KB 页对齐**：静态对齐检查不等于真机装载 | `audit-standard-apk.py` 已逐 ELF 检查；`docs/runtime-engines.md` 明确边界 | 链接参数 + CI 静态门禁 + R3 真机（有 16 KB 设备才声明）；不把静态检查写成真机通过 | 真机失败 → 回退 JVM；不发布声称支持 |
| Q5 | **APK 体积**：主机等效 332,424 B；Android 未知 | §2.3/§3.5 实测 | 512 KiB 硬上限 + 384 KiB 警戒；超限优化（size/LTO/asm off）或评审；`check-rust-native-apk.py` 阻断 | 回退 `.so`（构建层 off） |
| Q6 | **与 A3 的排序**：A3 未开始（A2 未开始）；`backup/` 是 A3 最大改区 | roadmap §0.1/§5 A3；A3 预计 8–12 人日 | **R0 待 Kotlin 批次合入后开工（§0.3）**；R1 只落桥+测试，调用点等 A3 分支；R2 等 A3 计划契约冻结；R3 是 A3 最后增量 | 若 A3 延期，Rust 停在 R0/R1，不影响 A3 出口 |
| Q7 | **数据格式/维护页**：Rust 不进 `runtimeId`，但 A3 改 `backup/` 会触发一次维护页 | 描述符 launcherInputs 481 项含 `backup` 树；§3.3 | 明确 A3 那一次维护页是 I6 设计，不是 Rust 引入；Rust 产物本身不改变 `runtimeId` | 若误判为“Rust 导致维护页”，以 `prepare-runtime-descriptor --check` 的 runtimeId 对比澄清 |
| Q8 | **Gradle 依赖验证/供应链**：新 Rust 依赖不走 Gradle verification | `gradle/verification-metadata.xml` 仅 Maven | 四条独立锁（Cargo.lock/vendor/toolchain lock/artifact lock）；不把 crates 塞进 Gradle | 供应链审计失败 → 移除对应 crate |
| Q9 | **性能未知**：Rust deflate 可能不优于 Java zlib；禁止预设倍数 | 评审主稿明确 UNKNOWN | 只承诺可验证性，不承诺速度；benchmark 只记录不回退（除非正确性/体积问题） | 不因慢而放宽正确性；不因“快”跳过差分 |
| Q10 | **回退掩盖缺陷**：Rust 返回错误后 JVM 重做可能掩盖调用方错误 | §5.4 | 只有 `INTERNAL/PANIC` 允许重做并标 `fellBack`；输入非法不重试；`verify_plan` 分歧禁止回退 | 回执可审计；差分测试锁行为 |
| Q11 | **THIRD_PARTY_NOTICES/审计 JSON 白名单**：新增许可文本可能触及 `CONTRIBUTING` 的 9 项审计白名单与 `docs/audits` 规则 | `CONTRIBUTING.md`/`check-third-party-notices.py` | R0 只新增 `assets/licenses/rust/**` 与生成器段落，不改历史审计 JSON；如需新增审计源，单独评审 | 许可门禁失败即阻断 |
| Q12 | **R8/ProGuard 混淆**：未来若启用 `minifyEnabled`，JNI 类/方法被改名会加载失败 | 当前 `minifyEnabled false`；`proguard-rules.pro` 可预留 | 加 `-keepclasseswithmembers class com.deepseekharness.app.util.RustNative { native <methods>; }`（R0） | 加载失败回退 JVM；release 门禁抓 `System.loadLibrary` 失败 |

---

## 8. 与 A3（数据保护）的关系

1. **A3 是 Rust 首个用户可见闭环出现的地方**：A3 交付“一键备份（无密码 v5）、恢复与迁移、自动计划、保留数据管理”，Rust 只在这些数据路径里做旁路（pack/unpack/digest/verify_plan）。**Rust 本身不是用户可见功能**；用户看到的是 A3 的备份/恢复能力，Rust 是其可回退的旁路实现。
2. **本文件只是准备**：R0 是工具链与门禁骨架，不改变 APK 行为；R1–R3 的代码合入顺序嵌入 A3 的实施（A3 的 `backup/` 改动是主线，Rust 只新增桥/端口 + 少量调用点）。本文件**不等同于 A3 开工**；A3 的开工仍按 roadmap §0.1.1/§11.1 的 Gate（A2 完成、A3 契约与夹具冻结等）。
3. **A3 出口判据不因 Rust 改变**：v5 备份跨版本可恢复；“双读单写”验证；Rust/JVM 解压后字节、摘要、清单 100% 一致；体积增量在 1.10 MiB 基线的预算内；旧格式（v1–v4）导入路径有测试；回退点可回 JVM；不改 `dataWrite`。Rust 批次为这些判据**补充证据**，不替换任何一条。
4. **覆盖更新/维护页**：A3 改 `backup/`（在 `launcherInputs` 内）→ `runtimeId` 变 → 覆盖安装进一次维护页，这是 roadmap §5 A3 的预期（I6），不是 Rust 引入；Rust `.so` 放 `app/build/` 不改变 `launcherInputs`（§3.3），因此 Rust 自身的迭代（R0/R1）不会额外增加维护页。
5. **数据格式**：gzip 只是传输层，v5 容器格式、`formatVersion=5`、`dataWrite` 均不变；Rust pack 的 gzip 字节可以不同于 JVM，但必须被现有严格读取器接受。回退到 JVM 后已写文件仍可读（同一格式）。
6. **A3 之后的扩展**：A3 之后 Rust 保持旁路，不因“引擎”一词扩范围；执行路径（`PlanEnvelope`/`permitMac`/进程化）都要另立 ADR，且不在本路径内。

---

## 附录 A：本次实测记录（环境与命令）

> 环境：Android 14 内核（`6.1.124-android14-11`）+ Ubuntu 24.04 aarch64 容器，`uid=0`（proot），JDK 17（`/opt/jdk17`），SDK `/opt/android-sdk`（`platforms/android-37.0`，**无 NDK**）。以下为设计核对临时操作，**不进入仓库**；仓库改动仅为新增本设计文档（写入前 `git status` 干净），另在容器内临时安装 Rust 工具链与 `gcc` 作实测，均不属于仓库。

| 检查 | 命令/结果 |
|---|---|
| Rust stable 版本 | `channel-rust-stable.toml`：manifest date `2026-11-12`；`[pkg.rustc] 1.100.0 (0ef1bc386 2026-11-09)`；`[pkg.cargo] 0.101.0 (3acc79c0c 2026-11-05)` |
| rustup 安装 | `rustup-init 1.29.3`，`aarch64-unknown-linux-gnu` 20,172,120 B，SHA-256 `9f608c14…`；安装后 `rustc -vV` host `aarch64-unknown-linux-gnu`，LLVM 23.1.5；`~/.rustup` 651 MB |
| Android target | `rustup target add aarch64-linux-android` → `cargo check --target aarch64-linux-android` PASS；`cargo build` 仅失败于 `linker cc not found` |
| NDK | 本机无；Google `repository2-3.xml`：`ndk;26.3.11579264` → `android-ndk-r26d-linux.zip` 668,556,491 B / SHA-1 `fcdad75a…`；Linux 归档仅 `linux-x86_64`；本机无 `qemu-x86_64`、binfmt 为空 |
| 下载速度 | `curl` 取前 2 MiB 用 4.285 s ≈ 0.47 MiB/s；668 MB 单流约 23 min（未执行） |
| 主机 JNI | `cargo build --release` 产 `libjnismoke.so` 72,112 B；`java -cp /tmp JniSmoke` → `JNI_OK 42 1234`（JDK 17 aarch64） |
| vendor 规模 | 16 crate（flate2 1.1.11、miniz_oxide 0.9.1、crc32fast 1.5.5、sha2 0.10.9、sha1 0.10.7、libc 0.2.191 等），782 文件，7,384,177 B；3 个 `build.rs`；全部宽松许可 |
| 离线构建 | 空 `CARGO_HOME` + `[source.vendored-sources]` + `--locked --offline` PASS |
| 体积探针 | 等效 gzip+SHA-256 代码，`opt-level=z`+LTO+`strip="symbols"` → 主机 `.so` 332,424 B，`DT_NEEDED` 仅 `libgcc_s.so.1`/`libc.so.6` |
| 字节复现 | 不同目录不 remap：`9f7d52…` vs `537f2b…`；`--remap-path-prefix=<dir>=/src` 后两目录一致 `a6ac3e3d…`；同目录重复构建稳定 |
| 本机补装 | 为 host JNI smoke 临时 `apt-get install gcc libc6-dev binutils`（gcc 13.3）；**不是**仓库改动；NDK/交叉链接未在本轮执行 |

## 附录 B：给 Lead 的索引/看板建议（不自行修改）

- `docs/documentation-index.md`「当前功能与架构」表，在 [外壳 Kotlin 模块化 + Rust 引擎可行性评审](README.md) 与 [DSHA 新生迭代路径](iteration-roadmap-20261112.md) 行之后加：

```text
| [R1 Rust 工具链与首期旁路接入方案](design/kotlin-rust-rearchitecture-20261112/r1-rust-toolchain-plan-20261113.md) | NDK r26 + Rust 1.100.0、vendor/锁/字节复现、`.so` 作为 CI 产物与 Gradle 注入、三类封闭纯函数 ABI、JVM 差分与回退、R0–R3 拆分；仅设计，未实现 |
```

- `iteration-roadmap-20261112.md` §5 A3 的「覆盖模块/交付物」末尾加一句：`Rust 旁路施工准备见 [R1 方案](r1-rust-toolchain-plan-20261113.md)（R0 可先行，R1–R3 随本轮落地）`；§0.1 看板**不要**为 Rust 增加轮次行（Rust 不是 A 轮），在 A3 行证据列引用即可。
- 若 Lead 决定把 `.so` 摘要放进 `runtime-descriptor`（§3.3 备选），需在 A3 契约里单列一条决策并接受每次 Rust 变更触发维护页；本文件推荐不采用。

## 附录 C：先决决定清单（2026-11-13 Lead 已决，见 §0）

1. **Q1 产物边界**：已接受 `app/build/rust-jniLibs` + 独立 APK receipt 门禁；摘要不进 `runtime-descriptor`（§0.1）。
2. **Q2 本机交叉链**：已接受“CI 构建 + 本机 check/主机差分 + 设备加载”为 R0 证据；暂不做 rust-lld/qemu spike（§0.1）。
3. **Q3 vendor 与许可**：有条件批准；vendor 清单/license 摘要/build.rs 结论/profile 与 ABI 锁见 §0.2；R0 提交由 Lead 逐行评审。
4. **Q4 默认开关**：已按建议接受：R0/R1/R2 默认关；R3 灰度验证后 A3 出口转 on（§0.1）。
5. **Q5 设备验收载体**：已接受：R3 用签名 `.dev` 包；用户已授权手机操作（一加 12 已连接），由 Lead 执行（§0.1）。
6. **Q6 范围确认**：已接受：`verify_plan` 首期绑定 A3 恢复计划（`NativeRestorePlan` 的规范化投影）（§0.1）。
7. **Q7 版本/维护**：确认 Rust `.so` 进 APK 但不进 `runtimeId`，A3 仍按一次维护页；与“覆盖更新零丢失”一致，不额外增加维护页。
