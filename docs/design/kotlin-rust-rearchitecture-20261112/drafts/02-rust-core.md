# 评审 B：Rust 核心引擎与 FFI 边界可行性（草稿）

> 基线 build359 / 0.2.1-rc.5。本文只读源码与文档，未跑构建、未装 APK、未测任何性能数据。凡"更快/更省"均为判断，不是测量。

## 结论摘要

1. **现有"处理运算"里没有值得用 Rust 重写的 CPU 热点，只有一个半。** 全部候选逐个看下来，真正的 CPU 密集段只有 gzip deflate（`PortableBackupEnvelope.java:81`、`DiagnosticExportArchive.java:92` 用 `GZIPOutputStream` 默认级别压缩）与自由 ustar 写头（同文件 `:222`）；其余是 `/proc` 读、`kill`、Provider I/O、JNI 已有的内核 PTY、以及薄薄一层 Android API 包装。把后者搬进 Rust 只增加 FFI 往返与双实现漂移，不减少任何字节搬运。
2. **进程监督、出生身份、信号不进 Rust。** 这套契约现在由纯 Java 逻辑钉住（`WebProofStop.java:118` 的 `verifyTwice`、`WebProcSel.java:86` 的收件人判据、`PtySession.java:364-374` 的 proot SIGQUIT），背后是 11 个单测类（I9/I10 见 `docs/overwrite-upgrade-and-maintenance.md:29-30`）。搬到原生层后这些断言全部失去 JVM 覆盖，正是历史上"停止改坏过三轮"的复发路径（`WebProcSel.java:9-11`）。
3. **FFI 只推荐手写 JNI + `catch_unwind` 边界。** 不使用 JNA；`jni-rs`、UniFFI 在"离线可复现构建"这一条上都要额外付账。Rust 产物必须以 `jniLibs` 库形式随包（`app/build.gradle:171-173` 的 `useLegacyPackaging = true` 才会解压到可执行的 nativeLibraryDir），并显式加 16 KB 页对齐链接参数。

## 一、Rust / Kotlin 职责分界

| # | 候选 | 现状证据 | 真实瓶颈 | 判定 |
|---|---|---|---|---|
| 1 | 文件摘要 / 事务 / 原子写 | 37 个类用 `MessageDigest`；SAF 单次读 256 KiB、写/推/拉 ≤32 MiB、并发 2（`docs/security-model.md:28`） | Provider I/O + 哈希；哈希走 ARM 加速指令，不在 App 侧 | 不承接（仅"私有目录大文件流式摘要"可旁路试，收益待测） |
| 2 | 归档打包解包 | `PortableBackupEnvelope.java:81`、`DiagnosticExportArchive.java:92`；`StrictGzipInputStream.java:6` 校验 CRC/ISIZE | **deflate 是唯一真实的 CPU 热点**（默认级别、单线程） | **条件承接**（先做差分对拍，不承诺倍数） |
| 3 | 进程出生身份与停止证明 | `WebProofStop.java:118`、`WebProcSel.java:86/256`、`PtySession.java:364-374` | `/proc` 读 + `kill`，纯 syscall/I/O；延迟由 3 s 退出窗口决定（`WebProofStop.java:45`） | 不承接 |
| 4 | proot / proroot 启动器 | `ContainerRuntime.java:105-108`（`-L --kill-on-exit -0 --rootfs=`）、`:215-219`（nativeLibraryDir） | 上游二进制本体（GPL-2.0 / 闭源 vendor），不可重写 | 不承接；Rust 最多做 argv 组装，等于复制 `RuntimeLauncher` |
| 5 | PTY 与终端流 | `libtermux.so` + `tools/termux-jni/dsha-pty.c:61` 出生握手、`PtySession.java:186-188` | 内核 PTY 与终端仿真；原生侧已是 C | 不承接 |
| 6 | 3090 HTTP 桥 | `HttpShellService.java` 4889 行；`BRIDGE_PROTOCOL = 3`（`:2623`）、X-Token 常量时间比较（`:850`）、帮助文本列 ≥37 个唯一个 `/app/*` 路径（`:2704`） | 网络 I/O + 每个端点都是 Android API 薄包装 | 不承接（换 Rust 只增多一次 FFI 跨边界调用 Android） |
| 7 | 日志轮转与脱敏 | `SensitiveData.java:13-25`（正则表）、`:142` `redact`；`WebOutputSession.java:26-43` 行流扫描 | 文本量小，CPU 可忽略 | 不承接 |
| 8 | 插件摘要与事务脚本 | `plugin-transactions.py`（guest 域，`docs/runtime-data-plugin-architecture.md:308` 记录固定目标/候选/前后摘要） | 执行域在容器内 Python，不在 App 进程 | 不承接（迁移会把事务证据搬出 guest 域） |
| 9 | 插件热检查 | `plugin-lifecycle.py:630-702` 读写 `plugin-updates.json` | 小 JSON + 网络 | 不承接 |
| 10 | 环境就绪探测（ELF/加载器） | `RootfsBootstrap.java:33-75`：读 64 B 头 + ≤64 个 56 B phdr + ≤512 B interp | 最多约 4 KiB 读，零 CPU | 不承接 |

**不能进 Rust 的 Android API 面**（这些是"壳"的地基，Rust 侧无法调用）：

| 面 | 证据 | 结论 |
|---|---|---|
| 权限/授权、设备命令策略 | `DeviceShellExecutor.java:25-33`（UID 与策略）、`util/DeviceShellPolicy` | 留 Kotlin |
| Service / 前台服务 / 看门狗 | `HarnessService.java:29`、`DeviceBridgeService.java:33`、`TerminalRuntimeService.java:25`、`UpdateDownloadService.java:18` | 留 Kotlin |
| WebView、浏览器麦克风 | `ui/WebPreviewActivity`、`BrowserMicrophone` | 留 Kotlin |
| 无障碍 / 识屏 | `DshaAccessibilityService.java:16`、`ui/sense` 路由（`HttpShellService.java:1734-1736`） | 留 Kotlin |
| SAF / DocumentsProvider | `DshaDocumentsProvider.java:35`、`docs/security-model.md:22-29` | 留 Kotlin |
| 通知 / 悬浮窗 / 原生确认 | `notify` 端点、`OverlayController`、`ConfirmReceiver.java:11` | 留 Kotlin |
| Keystore | `data/KeyVault.java:47` | 留 Kotlin |
| Shizuku Binder | `ShizukuShell.java:14/26`（`rikka.shizuku.Shizuku`、`UserServiceArgs`） | 留 Kotlin |
| Root 通道 | `RootShell.java:508-515`：`su -c "CLASSPATH=… app_process … RootShellMain"` | 留 Kotlin；Rust 替换等于重写整个 Java entry 与看门狗（`:616` 65 s） |

## 二、FFI 选型

| 方案 | 类型安全 | Kotlin 互操作 | 构建复杂度 | 调试 | 崩溃可控性 | 建议 |
|---|---|---|---|---|---|---|
| 手写 JNI（`#[no_mangle] extern "C"` + cbindgen 生成头 + Kotlin 侧薄包装） | 3 | 4 | 5 | 4 | 5（可逐函数 `catch_unwind`） | **推荐** |
| jni-rs | 4 | 4 | 4 | 3 | 3（`JNIEnv` 生命周期、attach、panic 跨边界语义更绕） | 备选，第二版再评估 |
| UniFFI | 5 | 5 | 2（uniffi-bindgen + Kotlin 运行时 + 版本配对） | 3 | 3 | 不推荐（离线生成链最脆） |
| cbindgen + 纯 `extern "C"`（不经 JNI） | 2 | 2 | 5 | 5 | 5 | 仅适用于"独立进程 + pipe/socket"，见第四节 |
| JNA | 1 | 2 | 3 | 2 | 1 | **不建议**（反射调用 + 无 ABI 校验 + 每调用开销） |

现成先例就在仓库里：`tools/termux-jni/` 是手写 JNI（`dsha-pty.c`、`dsha-process.c`）打进 `libtermux.so`，Java 侧只有声明（`NativeProcess.java:8`、`NativeStorage.java:21`）。新 Rust 层应沿用同一形状，不要引入第二套绑定范式。

## 三、运行时与错误模型

- **同步阻塞 FFI 为主，不引 tokio。** 承接面只有批处理（deflate/摘要），Kotlin 侧已有线程池与 deadline；tokio 会带来跨 runtime 回调 JVM 的 attach 问题，并在空闲期多占一批线程。若确实要异步，Rust 侧**不回调** JVM：返回句柄，由 Kotlin 轮询/阻塞取结果。
- **JNI attach 规则**：任何 Rust 回调（进度、日志）必须在入口线程 `AttachCurrentThread` 并在返回前 `DetachCurrentThread`；禁止把 `JNIEnv*` 存进跨线程结构体，禁止在 tokio/工作线程上持有 JVM 引用越过调用返回。
- **取消/超时/背压**：延续现有"请求停止 ≠ 确认完成"语义（`docs/runtime-engines.md:174`）。Rust 侧只用协作式取消标志，结果三态 `DONE / CANCELLED / UNKNOWN`；`CANCELLED` 后仍须由 Kotlin 读回真实产物状态，不得当"没做"重放。背压：Rust 只接受有界缓冲（复用现有 256 KiB / 32 MiB 边界），不自行扩容。
- **panic 策略：`panic = "unwind"` + 边界 `catch_unwind`，不用 `abort`。** Android 上 `panic=abort` 直接杀掉整个 App 进程，等价于 DSH、终端与全部会话一起断——这与"杀到 proot 启动器"同级（`WebProcSel.java` 顶部注释）。注意 `catch_unwind` 拦不住 SIGSEGV/越界 UB，也拦不住 `OOM`；因此 Rust 侧仍须零 `unsafe` 或最小 `unsafe` 面。独立 `.so` 进程方案（`IsolatedInstallProcess.java:44` 的既有范式）能隔离 abort，但要重付 IPC、出生身份、退出证明与双份停止逻辑，成本高于本阶段收益。
- **错误映射**：`i32` 码 + 固定枚举 → Kotlin `sealed class`/枚举；**不要把字符串消息当判据**。现有 `[POLICY_BLOCKED]`/`[EXIT=126]` 文本协议是历史包袱，不要复制到 FFI 层。

## 四、构建与分发

| 项 | 现状证据 | 对 Rust 的要求 |
|---|---|---|
| 目标与工具链 | `ci/toolchain.lock.json`（`ndk 26.3.11579264`）、`app/build.gradle:81/101`（`minSdk 30`、`abiFilters 'arm64-v8a'`） | `aarch64-linux-android` + NDK r26 clang 作 linker；API 与既有 `tools/native-session/build.py:28`（`aarch64-linux-android23`）保持可核对 |
| 16 KB 页 | 静态门禁 `tools/audit-standard-apk.py:81-86`（`alignment >= 16384` + 4K/16K 双页 RELRO 映射），由 `tools/verify-stability.py:309` 逐变体调用 | 链接参数必须加 `max-page-size=16384`、`common-page-size=4096`（同 `tools/native-session/build.py:32-33`），否则 16 KB 设备装载失败 |
| .so 体积 | `app/src/main/jniLibs/arm64-v8a/` 14 份合计约 1.10 MiB（`ls` 字节实测，最大 `libproroot-runtime.so` 375 120 B） | 以此为零增量预算；CI 记录 strip 后增量，超预算即失败 |
| strip | `tools/native-session/build.py:36`（`llvm-strip --strip-unneeded`） | 沿用；注意 `app/build.gradle:175` 的 `keepDebugSymbols` 只保护两份被字节核验的 proot ELF，不要让 Rust 产物误入该名单 |
| 可复现 | `tools/native-session/build.py:38-40` 已实现"重编字节比对 `--check`"，并带 `-ffile-prefix-map` | Rust 必须给 `Cargo.lock` + 固定 toolchain + `--remap-path-prefix`，接入同一 `--check` 门禁 |
| 离线/CI | 本机离线、`--offline`、共用 `/root/.gradle`（AGENTS.md「Gradle 只走 `dsha-gradle`」）；仓库内 `Cargo.toml` 数量为 0（实测） | **cargo 需要 registry**：要么 `cargo vendor` 入库，要么预置 crate 缓存。这是与现有构建链最大的冲突点，也是体积/许可审计的新增面 |

## 五、进程、fork/exec 与 W^X

- 可执行目录只有 nativeLibraryDir（`ContainerRuntime.java:215-219`；Android 10+ 禁从可写目录执行，见 `docs/runtime-engines.md:108`）。Rust 若要做 supervisor，必须以 `jniLibs` 库形式随包 + `useLegacyPackaging = true`（`app/build.gradle:171-173`）才会被解压成可执行文件；**不能放 assets/filesDir**。
- 现成范式是 `libdsha-session.so`：`IsolatedInstallProcess.java:44` 从 nativeLibraryDir 取用，`tools/native-session/session-launcher.c` 建 `setsid` + 父死信号 + 出生身份握手，宿主二次核验后才放行 guest 派发（`docs/runtime-engines.md:163`）。Rust 版本要复刻的是**同一套握手语义**，而不是另发明一套。
- 三条红线在任何语言里都成立：①不按裸 PID / 端口 / 名称发信号（`docs/ports-and-networking.md:114`、`WebProcSel.java:86`）；②出生身份连读两次一致才发信号（`WebProofStop.java:117-122`）；③`UNKNOWN` 不重放（`docs/runtime-engines.md:174`）。proroot 的 SIGTERM→SIGQUIT→SIGKILL 升级还依赖精确 argv 判定（`WebProcSel.java:256`、`docs/runtime-engines.md:159`），Rust 侧照抄就是第二份判据。

## 六、替换顺序（低风险 → 高风险）

| 顺序 | 内容 | 方式 |
|---|---|---|
| 1 | 归档 deflate / 解压流水线 | **旁路双实现 + 差分测试**：同一输入对拍"解压后字节 + 摘要 + 清单"，不比 gzip 字节（压缩器不同必然不同）；开关可一键回 Kotlin |
| 2 | 批量 stat / 小文件批量读 | 旁路；`NativeStorage` 已有 JNI 批量先例可对拍 |
| 3 | 大文件流式摘要 | 旁路；必须与 Java `MessageDigest` 逐文件比对全部摘要 |
| 4 | 其余 8 项 | **不迁移** |
| — | PTY 出生握手、停止/信号、桥协议面、root 通道 | **若迁移必须一次切换、不做双实现**（两套并存时的"谁在管进程"本身就是事故源） |

## 七、风险与未知

**风险前三**：①`panic=abort` 会连同 DSH 与全部会话杀掉整个 App 进程；②停止/信号搬进原生层后，11 个单测类（`WebProofStopTest`、`WebSessionProofTest`、`WebForceStopTest`、`WebRecordedStopTest`、`WebOverwriteRetireTest` 等）失去覆盖，双实现漂移直接复现已发生过的停止回归；③离线 cargo 链会让"本机能编、CI 编不出"，而 CI 是当前唯一可复现字节证明。

**明确 UNKNOWN（不做推测）**：Rust deflate 相对本机 Java 的真实倍数（无 arm64 基准数据，**禁止预设 2×/3×**）；备份归档的实际字节分布与最大体积（无测量）；Rust 在 16 KB 页真机的装载与运行（现有只有静态对齐检查，`docs/runtime-engines.md:80` 明确"对齐检查不等于全部 16 KiB 手机实测"）；本容器能否离线取得 crate（未见缓存证据）；CI 是否接受新增 native 构建阶段（未验证）；Rust 产物是否允许入库 `jniLibs`（与"生成文件不提交"政策的冲突未裁决）。

**待用户裁决**：①是否接受"Rust 只做旁路字节流水线"这一收敛范围；②是否允许 vendor crate 进仓库；③Rust `.so` 是否入库并纳入字节核验链。

## 给 Lead 的一句话

Rust 不是这套架构的性能解药：把范围收敛到归档/摘要旁路、FFI 一手写 JNI + `catch_unwind`、进程与 Android 面全部留在 Kotlin，是唯一能在现有安全不变式与离线构建链下站得住的方案。
