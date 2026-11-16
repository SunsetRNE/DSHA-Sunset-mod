# build368 第二台手机设备验收回执（2026-11-16）

状态：**通过（设备验收仅限 `.debug` 测试包 + Rust instrumentation）**。官方正式包未在本机或第二台手机安装；367→368 覆盖升级维护页未做设备验收。本回执只记录实际执行项与结果，不扩写到其它机身、ROM 或管理器。

## 对象与来源

- 候选：`e74f9e78d556b70323c03f9152ebe53f1d75f815`（build368 / versionCode 368 / versionName `0.2.1-rc.5-20261116.0500-dsh0.2.2-rc.1`），来自 `release-production` dry-run [43812641769](https://github.com/SunsetRNE/DSHA-Sunset-mod/actions/runs/43812641769)（runAttempt 1，`test_packages=debug+dev`、`rust_native_mode=prebuilt`、`dry_run=true`，全部节点 success，无 tag/Release）。
- 被测包（SHA-256 与同 run artifact 摘要逐一核对一致）：
  - `dsha-a-su-debug-e74f9e78.apk`（323,416,388 B，`c2a4ff94625902e413a284de1a8736c4ca20197f2c330913ff7b9bf8b5139327`）
  - `dsha-a-su-rust-instrumentation-e74f9e78.apk`（201,557 B，`c9296fbf1cb4569ac9c11a52806f3dc4cb508ab26531858d91d35cb076eada98`）
- 设备：OnePlus **PJD110**，Android **16**（API 36），`arm64-v8a`，构建指纹 `OnePlus/PJD110/OP5929L1:16/BP2A.250605.015/U.fb5f63_156f6fc_156f378:user/release-keys`，64 位进程，内核页大小 **4096（4 KB）**。
- 安装方式：USB ADB（`adb -s f55ffc45 install -r`），先 `.debug` 再 instrumentation test APK，均 `Success`；安装后 `versionCode=368`。**未安装官方包**（第二台手机既有官方包保持原样，未升级到 368）。

## 执行

1. `am instrument -w -r -e commit e74f9e78… -e runId 43812641769 -e runAttempt 1 -e repository SunsetRNE/DSHA-Sunset-mod io.github.sunsetrne.dsha.debug.test/com.deepseekharness.app.util.RustNativeInstrumentation`
2. 结果 `INSTRUMENTATION_CODE: -1`（RESULT_OK），`result=PASS`、`failure=NONE`、`stage=complete`。
3. 证据文件（仓库外，不提交）：`/tmp/build368-release/run-43812641769/instrumentation-final.txt`、`logcat.txt`。

## 关键通过项（同一份输出）

- **运行时后端默认启用**：`rustBackendsEnabledBefore=true`、`rustBackendsDefaultExpectation=ENABLED_WITH_RECEIPT`；探针期间临时 `rustBackendsDisabledForProbes=true`；`rustBackendsEnabledAfter=true`、`rustBackendsRestored=true`。
- **收据与来源绑定**：`rustNativeReceiptPresent=true`；嵌入收据 `embeddedReceiptSha256=70830a24…`；commit/runId/runAttempt/repository 与命令行绑定一致；`apkLibraryVerified=true`、`installedLibraryVerified=true`、`mappedLibraryVerified=true`；`librarySource=nativeLibraryDir`；库 SHA-256 `65aea50e7d34b8adfda28836248b2486b4d5481abb55d7bb80037a8adb459c6d` / 360,920 B，与同 run `release-rust-native` 摘要一致。
- **原生加载与 ABI**：`ensureLoaded=true`、`nativeStatus=LOADED`、`nativeAbiVersion=1`、`nativeAvailable=true`。
- **digest**：`nativeFdDigestVerified=true`，已知向量 SHA-256 `ba7816bf…`（"abc"）。
- **archive 桥**：roundtrip 262,217 → 262,280 B 通过；坏 header / 坏 trailer / 两成员 / 截断 / trailing data 均被拒绝并清理；abort / close 失败路径、`archiveFailureNoReplayVerified=true`、`archiveSessionCapacityChecks=13`。
- **PlanView**：valid / invalid / malformed / heap / null / output-bound / length 各类返回与原因码核验通过；`nativePlanCalls=13`、`planDeterministicRepeatsVerified=true`、`planInputUnchanged=true`、`planErrorOutputUntouched=true`。
- **清理**：`ownedTemporaryRemoved=true`。

## 失败与修复记录（同一验收过程）

- 首次运行缺 `-e commit/runId/runAttempt/repository`，`failure=ARGUMENT_BINDING_INVALID`（接收侧按设计拒绝）。
- 补齐绑定参数后暴露 `BACKENDS_NOT_DEFAULT_ON`：`Instrumentation.onStart()` 早于目标 App `Application.onCreate` 执行，读到默认 false。已在 `e74f9e78` 修复：`onStart()` 先 `runOnMainSync(() -> {})` 等主线程完成 App 初始化再读 `initialEnabled`，finally 恢复并断言。修复后本回执的 PASS 为修复后实测结果。

## 未验 / 边界

- **367→368 覆盖升级与维护页**：未做（第二台手机只装 `.debug`，官方包未升级；本机不装官方包）。
- **16 KB 页内核**：未验（本机 4096 B 页；ELF 16 KB 对齐是构建侧证据，不等于 16 KB 内核实测）。
- **端到端用户路径**：instrumentation 直接调用原生库与生产流桥，不等同于完整备份/恢复 UI、掉电恢复、SAF 传输或双屏敏感页验收。
- 不宣称其它 ROM、Root/Shizuku 管理器或全部 Android 版本兼容。
