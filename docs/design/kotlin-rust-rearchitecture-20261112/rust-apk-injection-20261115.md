# Rust 预构建产物接入 APK：施工契约与验证回执

日期：2026-11-15。独立分支 `feat/rust-apk-injection`，开工 fetch/ls-remote 核定基线 `7ab808a3c38a29117f306c597b684e3d3fd14969`。最后发布仍为 build366 / `5ccc8aa3db4c961553a348a8eb35cbfa815be11c`，tag `dsha-a-su-build366-20261114-180900`。本批只交付本地提交；主线存在与发布成功是两个事实。

依据：[ADR-0004](../../adr/0004-kotlin-rust-direction.md)、[R1 产物边界与复现方案](r1-rust-toolchain-plan-20261113.md)、[A3 slice4 业务接线回执](a3-slice4-contract-evidence-20261115.md)。本批不重新开发 gzip，不修改 Rust 算法、vendor、JNI ABI、backup 调用链、偏好键、数据格式或运行时业务开关。

## 接线与默认行为

装配只接受两个模式：

| 模式 | 行为 | 失败处理 |
|---|---|---|
| `-Pdsha.rust.mode=off`（默认） | 不注入库和 receipt；正常干净树保持既有 APK 内容，`RustBackends` 默认仍为 false | 若专用 staging 或源码 JNI/资产目录里出现残留 Rust 产物，明确拒绝；不悄悄删除或沿用 |
| `-Pdsha.rust.mode=prebuilt -Pdsha.rust.artifactDir=<目录>` | 只消费该目录的 `libdsha_rs.so` 和 `rust-receipt.json`；完整验证后复制到 `app/build/rust-jniLibs/arm64-v8a/`，receipt 独立进入 `app/build/generated/rustAssets/` | 缺库、缺 receipt、来源/提交/字节/ABI/时效不符均停止，不回落为 off |

[Gradle 接线](../../../app/build.gradle) 保留既有 sourceSets 结构，新增 `prepareRustNative` 并使 preBuild、native/asset merge 依赖它。任务每次重新验证时效和实际字节。cargo 不进入 Gradle 或签名节点。Rust 库已由固定 release profile `strip=symbols` 生成；仅对这个成员加 `keepDebugSymbols`，防止 AGP 二次 strip 改写 receipt 绑定的完整字节，不改既有 JNI 的保护、依赖、ABI 或预算。

正式发布流程默认 `off`。在 [release-production](../../../.github/workflows/release-production.yml) 请求 `prebuilt` 时，bind 强制同时要求 `dry_run=true` 且生成 debug 候选；不允许凭这次工程接线把正式默认打开。`RustBackends.setEnabled(true)` 未加入任何生产路径。

## 产物与来源契约

[builder](../../../tools/rust/build.py) 生成 schema 2 receipt；旧 schema 1 缺完整来源，拒绝作为可注入产物。来源 manifest 覆盖 Cargo 清单与锁、crate、完整 vendor、Rust 配置/工具链、产物锁与预算、builder/ELF/验证器、app 构建接线和 workflow。它保留逐文件摘要及稳定 aggregate 指纹，不信任 receipt 自己给出的总摘要。

真实构建和注入验证要求这些输入的 regular blob、路径集合及字节属于指定 HEAD；未提交、未跟踪或 ignored 的构建输入不能借用旧提交号。无关文档修改不阻止构建。构建前后再次比较 HEAD 和完整输入，拒绝构建期间漂移。

CI 消费端显式传入 commit、runId、runAttempt、repository，生产者与消费者只能读取本 workflow run 的 artifact。receipt 记录 workflow SHA/ref、runner 信息和 UTC 生成时间；workflow SHA 必须属于同提交。时效上限为 24 小时，未来时钟偏移至多 5 分钟。非 CI 的本地预构建使用明确的 local 来源，不能混作 GitHub Actions 来源。

这是可信 CI 生产者与消费者间的来源及字节绑定，不是可对抗任意同 UID 代码的密码学构建证明。receipt 与库同时来自同 run 的无 Secrets 生产节点；签名节点只验证和签名。

[共享验证器](../../../tools/rust/artifact.py) 在写入前检查 ELF64 little-endian、ET_DYN、AArch64、非空 PT_LOAD/RELRO、16KB 对齐和映射、完整可见 JNI 符号集合、DT_NEEDED 白名单及体积。拒绝链接路径、额外输入成员、错误 ABI 和重复 ZIP 成员。库只进入 `lib/arm64-v8a/libdsha_rs.so`；APK 内不得在 assets 或其它目录重复携带原生库。

## 可执行入口

本机 Gradle 一律使用共享缓存的 `dsha-gradle`；一次只跑一个重构建。以下是源码冻结并已本地提交后的复跑入口，完整构建仍须具备固定离线输入和适用的 Android 工具链：

```bash
# 显式关闭正控，不运行 cargo。
dsha-gradle :app:prepareRustNative -Pdsha.rust.mode=off

# Android 产物只由固定 NDK/Rust 构建；本机 aarch64 不执行 x86_64 NDK。
python3 tools/rust/build.py --android --ndk "$ANDROID_HOME/ndk/26.3.11579264" \
  --check --expect-lock --out out/rust-native --receipt out/rust-native/rust-receipt.json

# 仅本地来源的 staging；CI 还须显式传入下文三个运行身份参数。
python3 tools/rust/artifact.py stage --mode prebuilt --artifact-dir out/rust-native \
  --commit "$(git rev-parse HEAD)" --out app/build/rust-jniLibs \
  --assets-out app/build/generated/rustAssets

dsha-gradle :app:assembleStandardDebug :app:assembleStandardDebugAndroidTest \
  -Pdsha.debugPackage=true -Pdsha.unsignedPackage=true \
  -Pdsha.rust.mode=prebuilt -Pdsha.rust.artifactDir=out/rust-native \
  -Pdsha.testRunner=com.deepseekharness.app.util.RustNativeInstrumentation
```

[APK 校验入口](../../../tools/check-rust-native-apk.py) 对未签名、签名前输入、最终签名 APK 使用同一个验证器，并要求 APK 内 `assets/rust-native-receipt.json` 与外部 receipt 逐字节相同：

```bash
python3 tools/check-rust-native-apk.py --mode prebuilt --apk "$APK" \
  --receipt out/rust-native/rust-receipt.json --commit "$GITHUB_SHA" \
  --run-id "$GITHUB_RUN_ID" --run-attempt "$GITHUB_RUN_ATTEMPT" \
  --repository "$GITHUB_REPOSITORY" --output out/rust-apk.json

python3 tools/check-rust-native-apk.py --mode off --apk "$APK" --output out/rust-apk-off.json
python3 tools/rust/test_apk_injection.py
python3 tools/rust/test_android_candidate.py
python3 tools/test-ci-workflows.py
```

无 `--apk` 的预算入口只报告 `PASS_BUDGET_ONLY` / `apkVerified=false`。APK 静态门禁报告 `androidLoadTested=false`，不会把解析通过写成 Android 加载、APK 签名或设备验收通过。

## Android 加载候选

完整候选入口是 `release-production`：精确 `expected_sha`、`dry_run=true`、`test_packages=debug` 或 `debug+dev`、`rust_native_mode=prebuilt`。它在同 run 生成包含真实 Android Rust 库的 debug 目标 APK 与 instrumentation APK，并在无 Secrets 的编译阶段验证静态载荷，再由固定测试证书给二者签名。产物通过来源与 SHA 绑定；这段接线在本批尚未远端执行。

[测试包节点](../../../.github/workflows/_node-test-packages.yml) 的签名模式不运行 Gradle/cargo，不写共享缓存；目标与 instrumentation 证书必须一致。候选的独立 artifact 与 evidence 含执行命令、提交/run 绑定和 `execution=NOT_RUN`。安装与执行需由后续获授权会话完成，本批未执行以下命令：

```text
am instrument -w -r \
  -e commit <完整源码SHA> -e runId <CI运行ID> -e runAttempt <attempt> \
  -e repository SunsetRNE/DSHA-Sunset-mod \
  io.github.sunsetrne.dsha.debug.test/com.deepseekharness.app.util.RustNativeInstrumentation
```

[Instrumentation](../../../app/src/androidTest/java/com/deepseekharness/app/util/RustNativeInstrumentation.java) 不改变默认 runner；只在构建参数中显式选择。它比较目标 APK、安装后 nativeLibraryDir 及已映射库的来源，走生产 `RustNative.ensureLoaded()` / `status()` / `statusCode()`，真实调用 `abiVersion()==1` 和 `digest()`（私有新建文件、PFD、已知 `abc` SHA-256）。任何加载/ABI/摘要错误均失败，禁止 skip、host 路径替代或 JVM 回退。它记录实际设备 ABI、SDK/Build、页大小和公开来源；4KB 设备的成功不能扩写为 16KB 内核成功。

`ci-package` 可显式选择 prebuilt，消费并检查真实未签名 APK；该工作流仅有未签名软件证据，不是可安装或 Android 已加载候选。

## 字节复现、受管输入和回退

保留 `--check` 的不同源码目录、同 run 两次构建；它不证明跨 runner 复现。只有连续三个不同 runner 实例的独立 job/回执与实际库字节一致，才能由后续集成批次更新 artifact pin。不能仅以可能复用的 runner 名称去重；须连同真实 job/run 元数据核定。本批不修改任何产物 pin，不提交 `.so`。

Rust `.so` 与独立 receipt 不加入 launcherInputs/runtimeId，沿用 R1 已接受的 APK 层边界。未修改受管 JSON、runtime-descriptor、managed-runtime-inputs、固定输入锁或 provenance；main 最终在串行集成树核对所有受管输入。本批唯一 JSON 编辑是经 main 批准给 Java 格式全覆盖清单追加一个 instrumentation 路径；没有改旧条目或放宽格式政策。Java/Kotlin 生产和单测源零修改、零迁移；仅新增一个 Java Android instrumentation，Kotlin 零变化。

在未分发的构建树内回退：选 off 并清理自己的 build 输出后重建，即恢复不含 Rust 的 APK；不处理用户数据或改变 v5 格式。源码回退按本批提交逆序 revert；main 若已产生集成期受管证明，应在同一串行树核对恢复相应输入。不得通过降级安装、清除应用数据或修改旧 receipt 来“回退”。

## 本批实际执行与未执行

实现已本地提交 `8da0df9a99839757967a9e06ee3b483861a81b6e`。以下最终验证针对该实现与最后一处测试依赖图断言修正；该修正只要求依赖精确包含 rust-native，没有修改验证器或产品行为。共三组最终实际执行 135、9、16 项，不叠加前轮或子任务重复执行数。证据仅描述本机与静态作用域：

| 项 | 实际结果 |
|---|---|
| 开工基线 | workflow 58、package 9、test-package evidence 16 项通过 |
| 最终定向回归 | `python3 -B tools/test-ci-workflows.py`：135 tests / 29.446s / OK，含 57 项产物/真实 Git blob/合成 ELF 与 ZIP 负控、17 项候选构建与 XML 树/签名交接回归；没有执行 Android |
| 包/证据回归 | `test-ci-package.py` 9 tests OK；`test-release-test-packages.py` 16 tests OK |
| JNI 候选编译 | JDK17 `javac --release 17`，缓存 API37 android.jar，仅新 instrumentation 与生产 RustNative/RustBackends；exit 0。类文件位于仓库外临时目录，不是 APK/设备证据 |
| 格式 | Java 1832 文件 / 60 批、Kotlin 5 文件 / 2 批，均 0 need formatting；清单旧条目、顺序和政策字段未变 |
| 工具链/vendor/预算 | 固定 Rust 1.100.0、16 crate、7,384,177 B vendor；预算输出 `PASS_BUDGET_ONLY` / `apkVerified=false`，未修改产物 pin |
| Gradle off | `:app:prepareRustNative`，1 个真实任务执行成功，未运行 Test/assemble |
| Gradle prebuilt 缺参 | 配置阶段按 `RUST_ARTIFACT_DIRECTORY` 拒绝；没有运行测试或 APK 任务 |
| 输入契约 | 103 安装项、205 资产、515 启动输入一致；6 份离线归档本树缺失，完整包核验待补 |
| 测试登记/源码 | 原 host manifest 259 入口、Rust 边界 7 源/11 符号/16 依赖/4 buildInputs、源码卫生通过 |
| 真实 host ELF 只读兼容性 | 既有 A3 产物 348736 B / `04f07e7a2adb64e034ba66dfce2467ba369af11110f876b10b6a5aef59156bed` 被静态 host 检查接受；当 Android 产物输入时因 glibc DT_NEEDED 被拒绝；没有加载或重编译，不是 Android 证据 |

拒绝用例包含缺库/receipt、错 SHA/大小/提交/run/attempt/repository/workflow SHA/ABI、过期与未来 receipt、完整来源 manifest 篡改、Git 未提交/删除/未跟踪/ignored 输入、index flags 隐藏修改、错误/隐藏 JNI 符号、未知 DT_NEEDED、空 native ABI、坏 ELF、超预算、链接/重复 ZIP/资产回执不符及 off 残留。源输入检查的 SHA1/SHA256 Git 仓库均是临时独立 fixture；没有修改当前工作树或他树来制造反例。

首轮产物 43 项回归的最后一个子例因并行编辑 workflow 正确触发 SOURCE_FINGERPRINT，与该子例当时预期的 ELF_MACHINE 原因不同；之后先冻结、提交输入再测试，没有放宽来源校验。最终统一入口首轮为 135 tests / 1 failure，原因是旧 `needs: [bind, inputs]` 断言未同步；修正为精确 `{bind, inputs, rust-native}` 后复跑 135/135 OK。候选回归首轮多行命令提取假设已修正；官方 AAPT2 XMLPrinter 使用完整 namespace URI，两处 manifest 校验已改用 xmltree，接受/拒绝原解析 AST 的夹具已执行。格式门禁没有豁免。

独立逐行审查覆盖 base→实现提交全部 13 路径，初审发现 1P1/2P2：HEAD 标签未证明输入已提交、JNI 可见性漏审、off 的非空 ABI 门禁遗漏。三项全部补实现与负控；最终静态 `APPROVE` / P0=P1=P2=0，并追加审阅最后一行测试断言修正。审查没有运行构建或设备，不能代替本表执行证据。AAPT2 格式核对依据为 [AOSP Android 16 Debug.cpp](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/tools/aapt2/Debug.cpp)；workflow SHA 含义依据 [GitHub Actions 变量文档](https://docs.github.com/en/actions/reference/workflows-and-actions/variables)。

主要本机日志位于仓库外 `/tmp/rust-apk-590662fd-*.log`：最终 `final-workflows-r2`、`final-package`、`final-test-packages`、`final-toolchain`、`final-vendor`、`final-budget`、`java-format`、`kotlin-format`、`gradle-off`、`gradle-missing`。首轮失败日志 `final-workflows` 保留。Javac 与子任务 13/17 项回归使用工具内联回执；未伪造单独日志或计入 Gradle/JUnit 测试结果。

## 精确文件清单

| 职责 | 本批路径 |
|---|---|
| Gradle 装配 | [app/build.gradle](../../../app/build.gradle) |
| 无 Secrets 原生生产者 | [_node-rust.yml](../../../.github/workflows/_node-rust.yml) |
| 测试候选与签名交接 | [_node-test-packages.yml](../../../.github/workflows/_node-test-packages.yml) |
| 发布候选与最终包复核 | [release-production.yml](../../../.github/workflows/release-production.yml) |
| 未签名完整软件包 | [ci-package.yml](../../../.github/workflows/ci-package.yml) |
| 产物构建/来源/静态验证 | [build.py](../../../tools/rust/build.py)、[artifact.py](../../../tools/rust/artifact.py)、[check-rust-native-apk.py](../../../tools/check-rust-native-apk.py) |
| 回归与既有测试入口 | [test_apk_injection.py](../../../tools/rust/test_apk_injection.py)、[test_android_candidate.py](../../../tools/rust/test_android_candidate.py)、[test-ci-workflows.py](../../../tools/test-ci-workflows.py) |
| 唯一新增应用测试源 | [RustNativeInstrumentation.java](../../../app/src/androidTest/java/com/deepseekharness/app/util/RustNativeInstrumentation.java) |
| 经 main 批准的格式登记 | [java-format-files.json](../../../tools/java-format-files.json)，只加上行源路径 |
| 交付文档 | [本回执](rust-apk-injection-20261115.md) |

明确未执行：本批 Android Rust 链接、完整 Gradle 单测、Lint、真实 APK assemble、正式/测试签名、完整远端 CI、安装、Android instrumentation、4KB/16KB 真机加载、SAF/覆盖升级/性能验收。完整离线归档缺失和本机 x86_64 Android 工具不可直接执行均保留，不降级门禁。本地合成 ELF/ZIP 只验证接受与拒绝路径，不冒充可安装 APK 或密码学签名。
