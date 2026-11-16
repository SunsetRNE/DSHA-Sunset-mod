# 启动链路 S2：同次描述符准备复用

本地任务 T10；建树基线 `46f4970369c796c3a7cb9acca6cff58961778840`，远端后续 `4ce4064b` 的 T7 改动仅在 contract 域，本域源码无差异。本文状态为**实现已写、JVM 验证排队中**；测试结果将按实际回执更新，不继承 build369 或旧任务的通过结论。

## 旧成果与本次剩余工作

旧成果已经完成并进入主线，本次不重复实现：

| 前置成果 | 旧提交与主线映射 | 本次核对 |
|---|---|---|
| K0/S0 | `a40ccc9c` / `c117fdb1` / `8fa184ea`，主线 `d312f707` | 原工作树已结束、无未提交改动；Kotlin 输入与 timing 出口在当前源码中 |
| S1a | `b64541c2` / `8b910929`，集成 `7ab808a3` | 原工作树已结束；停止事实与 Kotlin 模型保留，不重做停止交接 |
| S1b | `e1a7673f` → `ccf7ffa7`，`494b3aae` → `ea59b2d0` | 前置提交稳定 patch-id 相同；第二提交 12 路径逐路径 patch-id 全部一致；原树已结束、干净 |
| runtime 组装 Kotlin | `af92d761` → `532d05ed`，`c0bb1491` → `02b68815`，`ffe43732` → `7da5fbbd` | 8 个 Kotlin 文件的旧提交、主线映射与本次基线 SHA-256 全部一致；不再翻译 runtime/core |

旧工作树、提交、日志和声明均保持原样。核对 S1b 大 diff 时发生本机已知 Git/proroot exit 139；未重放大 diff，后续用单路径 `git_guard` 比较完成内容核对。它是只读核对失败，无未知写动作。

本次只实现现有入口的剩余重复解析：`isEnvironmentReady` 的已装 descriptor/expected/health 读取中，health 会再次读取已装 descriptor；插件准备与最终执行也会重走这些读取。复用仅作用于不可变 `RuntimeDescriptor` 的 JSON、身份摘要和冻结解析。Bash/loader、health 回执、pending 盘点、RuntimeTools 准备、原始 descriptor 字节均继续重读；没有持久化 ready=true。

## 实现与失效边界

结构提交 `1065e638` 独立接入 [RuntimeDescriptorPreparation](../app/src/main/java/com/deepseekharness/app/runtime/RuntimeDescriptorPreparation.kt) 和真实调用计数，仍每次解析；行为提交 `4a8c568a` 单独启用同次复用。后续 `1ab70dbe` 修补跨 kind 的 A→B→A 输入历史失效，并增强线程测试异常回传；交付必须包含该修补，不能单独采用 `4a8c568a`。

自查与只读独审共同确认：原 C2 只在当前 kind 已有条目时清缓存，先 EXPECTED(A)→空槽 INSTALLED(B)→EXPECTED(A) 可能复活最早 expected 对象。原始字节仍相等、未见执行许可绕过，但违反本轮绑定/失效要求，按 P2 修补。新实现每次观察都以 scope.observed/align 对齐全 scope 输入，任何变化清两槽；所有观察完成后才取缓存条目，避免局部旧引用穿过失效。新负控枚举两个交错方向；将以旧 C2 真实代码运行该负控得到失败，再对修补后源码完整混编复验。

- scope 由 [WebLifecycleController](../app/src/main/java/com/deepseekharness/app/core/WebLifecycleController.java) 的排队后 worker 打开，在原 `RuntimeHostPorts` settings invocation 内运行；同一 stable/selected bootstrap 只消费外层 scope，结束清空，不跨线程和下一次操作。
- [RuntimePreparationInputs](../app/src/main/java/com/deepseekharness/app/runtime/RuntimePreparationInputs.kt) 绑定既有 APK/split/AssetManager 验证返回的 Snapshot 对象、当前 settings 对象、root/固定文件 `RuntimeAssetStamp`、工具摘要与失效观察修订。APK 不可验证时既有 get 每次返回新 Snapshot，前后身份无法匹配，不能复用。
- 同路径替换、inode、size、mode、mtime/ctime 纳秒、root、APK/split/AssetManager、settings invocation、操作代次、取消修订或工具身份变化都会使旧解析失效。原始字节每次重读并逐字节比较，元数据不变也不能掩盖不同 JSON。
- 未知输入、读取/解析失败、取消或中断会退休本次复用范围；后来恢复旧身份不会复活旧对象。维护/修复/写安装标记/确认健康/stage 显式撤销本次范围。
- [ManagedRuntimeAssets](../app/src/main/java/com/deepseekharness/app/runtime/ManagedRuntimeAssets.java) 的健康回执始终按原路径读取；缺失、坏回执和异常撤销范围。`compatible`、`latest`、`healthy` 与 pending 始终分别判断。
- [RuntimeTools](../app/src/main/java/com/deepseekharness/app/runtime/RuntimeTools.java) 的新增内容仅为同 LOCK 的只读投影和原失效点的观察修订。投影复用原 cacheMiss 的 root/APK/requireNpm/维护 owner/preparedStamp/当前 RuntimeAssetStamp 条件；不更改工具准备/修复顺序和判据，不增加新的全局缓存。
- 原五处 `preparedStamp=null`（两条 prepare miss、显式 invalidate、设备脚本、managed overlay）及 prepare 失败只推进观察修订。观察修订不是工具准备次数或执行许可。
- [RuntimeExecution](../app/src/main/java/com/deepseekharness/app/runtime/RuntimeExecution.kt) 的 readiness 门与 [RuntimeLauncher](../app/src/main/java/com/deepseekharness/app/runtime/RuntimeLauncher.kt) 在 builder 完成后的末次 LaunchCheck 保持原字节。没有修改线程调度、DNS/端口、鉴权等待、proot/proroot 回退、插件写入、协议、数据格式、旧偏好键或进程身份边界。

新增收益不包含 RuntimeApkAssets 已有可信 APK 字节缓存、RuntimeTools 已有进程内/准备证明命中，以及同次 builder 已有 preload 复用。固定 proot helper 仍走 install 路径，未把四个 helper 误计成四次完整 prepareTools。

## 验证记录

- 非 JVM：架构边界 25 项通过；源码卫生通过；输入契约 18 项通过；源码布局 16 项通过（首次因 4 个新文件未登记 git 失败，登记后同一断言通过）；host manifest 264 项仅为登记核验；本文 11 链接无错误/警告。基线和结构源码已冻结，旧提交映射核对完成。
- 原基线 `46f49703`：1010 个 Java 编译输入（996 仓库含 AIDL stub、14 个临时生成 Java）+77 个 Kotlin，全新编译；16 类、151 项通过，failure/ignored/assumption 均 0。
- C1 `1065e638`：同 1010 Java+78 Kotlin 全新编译；17 类、156 项通过，failure/ignored/assumption 均 0；结构计数夹具的两次准备链实际为 6 次读取、6 次解析。
- C2 首次：同 1010 Java+79 Kotlin 全新编译及所有选中测试编译通过；7 个 Kotlin、5 个 Java 的定向格式门禁通过。20 类 176 项中 175 通过、1 个新集成夹具失败，ignored/assumption 均 0：`ApplicationInfo.nativeLibraryDir=null` 在构造 ProotBootstrap 时 NPE，尚未进入该项 pending/末次门断言。仅补夹具私有 native 目录，保留原日志；复验尚待下一轮串行槽。
- 已执行的调用计数/负控：两次真实 ManagedRuntimeAssets 读取链仍做 4 次已装 descriptor、2 次 APK descriptor 和 2 次 health 读取；descriptor 解析从 C1 的 6 次降为 C2 的 2 次（每类各一次），其余同路径替换、恢复 mtime、原字节变化、APK/AssetManager/settings/root/tools/操作改变、未知后恢复旧身份、跨线程/新 scope 和异常不复活旧成功的断言均通过。既有 RuntimeApkAssets/RuntimeTools 命中未重复计功。
- 本机验证为锁定 JDK17/Kotlin2.2.10 的直接混编与离线 Robolectric API34/JUnit，编译类型用 SDK37；R 符号从当前真实 XML/resValue 生成，未运行 AAPT/AGP 资源合并，不能称 API37 资源构建通过。三个阶段覆盖重叠，不能把 151+156+176 相加成独特覆盖。
- Gradle 与全树 Kotlin 格式：尚待下一轮串行槽。七份固定/派生输入共 489982546 字节已从 main 验证树复制并按 pin/SHA 回读，不生成最终 descriptor/runtimeId/recipe；所有本机 Gradle 只用 dsha-gradle。
- 新用例：[计数结构](../app/src/test/java/com/deepseekharness/app/runtime/RuntimeDescriptorPreparationTest.kt)、[复用与负控](../app/src/test/java/com/deepseekharness/app/runtime/RuntimeDescriptorReuseTest.kt)、[真实读取/health/pending/末次门](../app/src/test/java/com/deepseekharness/app/runtime/RuntimeDescriptorReadIntegrationTest.kt)、[工具投影与元数据负控](../app/src/test/java/com/deepseekharness/app/runtime/RuntimeToolsProjectionTest.kt)。
- 运行回执边界：上述 175 项通过不计为 `1ab70dbe` 修补后的运行证据。当前源码需要全新混编与 177 项复验，不能只复用旧生产 class；旧 C2 的交错失效负控也待执行。
- 前两个只读审查后端 product-error，无有效结论；后续 fork 审查 `11fc4fa4` 已完整核对 8 生产文件、4 测试及 ABA 修补，最终源码 **APPROVE**，随后线程 Future.get 小差异复核保持 APPROVE。该审查未运行 JVM，不代替测试或合入授权。
- 五个原 `preparedStamp=null` 分支均已逐处静态核对；直接执行的修订负控是显式 invalidate 与准备失败，不能扩写成每个修复分支均已执行。生产投影的 hit 需两次 observe、miss 通常三次，每次复核固定工具集 lstat、摘要和 APK 身份；6→2 的集成计数注入固定 Identity，仅证明解析减少。

设备耗时、真实 Android/proot/proroot 启动、完整 Gradle/CI/Lint/APK/签名、安装/覆盖维护及 ROM 矩阵均未验。本机 fixture 的解析调用数即使下降，也不能据此宣称设备启动耗时下降。新增输入复核本身有成本，设备净收益仍需实测。

## 受管身份与回退

本轮不改版本源、受管输入格式、descriptor 或 recipe。[来源增量与逐文件 SHA](startup-s2-input-delta.json) 核对启动输入 **517→519**：新增 `runtime/RuntimeDescriptorPreparation.kt`、`runtime/RuntimePreparationInputs.kt`，6 个既有受管源码摘要变化，无删除；205 个资产的成员和摘要均与基线 pin 一致。最终身份由 main 在集成树串行生成和验证。

回退顺序：撤优化时按 `1ab70dbe` → `4a8c568a` 逆序回退，恢复每次完整解析并保留计数；不要单独撤 ABA 修补后继续启用旧复用。需要全部撤回时再撤结构提交 `1065e638`。C1 的部分格式整理随 C2 完成，单独回退行为后应再跑当前 Kotlin 格式门禁并只做必要格式整理。集成后按实际重放 SHA 操作，并由 main 对回退后的来源统一重算身份。无需清用户数据、改偏好/权限、删除旧树或降级安装。

本任务授权仅本地实现、验证和提交；不包含 push、合并 main、发布、安装、手机操作或旧工作树清理。
