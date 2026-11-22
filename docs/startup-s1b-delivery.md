# S1b：受保护恢复的一次性停止交接

本批从当时真实 `origin/main=7ab808a3c38a29117f306c597b684e3d3fd14969` 建立 `feat/startup-s1b`。续工时远端为 `e15989a4`，未重基、未吸收其它候选；最后发布仍是 build366 / `5ccc8aa3`，本批不属于该发布。实现依据是[实施方案 §3](startup-kotlin-implementation-plan.md#3-s1-可直接交给实现会话的契约)和 [ADR-0004](adr/0004-kotlin-rust-direction.md)。

本批仅交付本地代码和提交。受管 JSON、版本线、构建文件没有修改；没有合并、push、CI dispatch、发布、安装或手机操作。

## 行为与接线

只有 `recoverWeb` 的 `protection != null && disabledPlugin == null` 可以选择交接。准入、前台服务原票据、共享环境租约、原 IO 队列和 `HarnessSessionState.lifecycle` 的 owner 均保留。

1. 入队前在原锁内捕获精确 receipt；停止结果从该 receipt 的 typed completion 获取，不用 Future 成功或全局展示结果补证据。continuation 核对 owner、操作引用、`gS`、取消修订、原 waitingStop 票据及同步收尾结果。
2. 完整证明和本次现场复核对象都具备时，原票据 `resumeAfterStop → beginStart`，交接绑定精确 `gN == gS + 1`；worker 先执行原 `claimQueuedStart`，再一次性消费交接。每次显式 Stop，包括不推进 generation 的重复 Stop，仍撤销原票据。
3. 无信号复核保留清理前的原 run、launcher handle、recovered launcher 出生身份；允许旧字段被清空或对象退休，拒绝同代次替换、新 handle、非本次 `gN` 的新 run、保护丢失、未知 guest 和新服务记录。不会用空 map 代替原对象退出证明。
4. 平台复核保留原 root/boot、trial 目录与成员、marker 节点及字节、payload 身份，并冻结停止后的记录节点。主服务及 trial 的活动 PID/identity 文件必须仍缺失；completion 后新出现的记录不被签成新基线。每次复核都重新扫描真实内核进程；应用内 spawn 计数不作为全域证明。
5. pipeline 只跳过重复的 guest/launcher 停止及相应等待。`stopLan`、旧桥租约及对象释放、工具和环境门禁、数据事务、迁移、插件准备、安全配置、unlock sentinel、快照次序保留。
6. `ProotBootstrap.LaunchCheck` 经原执行器传到启动器，在 builder 的 resolver/preload/env 等准备完成后、真正创建进程之前执行新鲜复核，再在原锁内核 `gN`、取消修订和已 claim 的原票据，完成一次性 dispatch。旧重载和其它入口传空检查，保留原调用形态。

尚未选择且仅缺历史完整证明或可复用范围时，继续原停止链。完整证明已经进入现场交接后，UNKNOWN、超限、记录或对象变化、许可失效均阻断本次续启并留下诊断；不按 cache miss 重新 stop/launch。交接路径不再无条件清除实例存档，新增存档必须保留并阻断。普通冷启动、watchdog、无票据恢复、停用故障插件、维护、应急和强停继续原链。

这些检查维持现有身份与同 UID 可见性边界，不是对任意同 UID 写者的内核隔离或未来不可变保证；不修改信号算法，也不把进程身份迁入 Rust。

## 文件与语言范围

生产修改 10 个 Java、1 个 Kotlin；没有生产类语言转换、删除、搬迁或重复实现。新增 2 个 Kotlin 集成测试，修改 1 个旧 Java 测试夹具以明确其只覆盖“缺完整证明时走原链”。旧偏好键、协议、数据格式、Java 调用形态、Fragment/XML、依赖和门禁保持。

| 范围 | 文件 |
|---|---|
| 原 owner / receipt / 本次交接载荷 | [HarnessSessionState](../app/src/main/java/com/deepseekharness/app/core/HarnessSessionState.java) |
| 窄入口、原票据续接、claim 与最终派发 | [WebLifecycleController](../app/src/main/java/com/deepseekharness/app/core/WebLifecycleController.java) |
| pipeline 与资源释放 | [StartupEnvironmentActions](../app/src/main/java/com/deepseekharness/app/core/StartupEnvironmentActions.java)、[StartupPipeline](../app/src/main/java/com/deepseekharness/app/core/StartupPipeline.java) |
| 原句柄、恢复证明与记录核验 | [WebProcessSession](../app/src/main/java/com/deepseekharness/app/core/WebProcessSession.java) |
| 平台只读复核 | [WebProcessManager](../app/src/main/java/com/deepseekharness/app/runtime/WebProcessManager.java) |
| 可选末次执行检查接缝 | [ProotBootstrap](../app/src/main/java/com/deepseekharness/app/runtime/ProotBootstrap.java)、[RuntimeExecution](../app/src/main/java/com/deepseekharness/app/runtime/RuntimeExecution.java)、[RuntimeLauncher](../app/src/main/java/com/deepseekharness/app/runtime/RuntimeLauncher.java) |
| 原票据只读状态与纯消费判据 | [WebStartProtection](../app/src/main/java/com/deepseekharness/app/util/WebStartProtection.java)、[StopHandoff](../app/src/main/java/com/deepseekharness/app/util/StopHandoff.kt) |
| 真实控制器/coordinator/pipeline 集成反例 | [WebLifecycleStopHandoffTest](../app/src/test/java/com/deepseekharness/app/core/WebLifecycleStopHandoffTest.kt) |
| 真实文件系统与内核读取 IO 反例 | [WebStopHandoffRecheckTest](../app/src/test/java/com/deepseekharness/app/runtime/WebStopHandoffRecheckTest.kt) |
| 旧准入夹具的 typed 缺证据投影 | [WebLifecycleStartProtectionTest](../app/src/test/java/com/deepseekharness/app/core/WebLifecycleStartProtectionTest.java) |

## 实际验证

| 证据 | 实际结果与限制 |
|---|---|
| 原始基线 | `7ab808a3` 通过 `git archive` 冻结，3002 个原文件 blob 核验；原生产源码全新 Kotlin/Javac 编译后，14 类 / 210 项通过，failure/ignored/assumption 均 0 |
| 最终候选 C4 | 全新编译 1085 项生产输入：1081 Java、4 Kotlin；生产 Java 2564 class、Kotlin 12 class、测试 79 class 均 JVM major 61；编译后逐输入摘要不变 |
| 最终候选测试 | 17 类 / **254 项通过**，failure/ignored/assumption 均 **0**。包含新控制器 22 项、新平台反例 19 项和既有启动/停止/插件续启回归；不与基线或中间轮次叠加成独特覆盖数 |
| 真实 Android 生成输入 | 复用并逐摘要核对 572 项真实 API34 资源输入、真实 R/AIDL/BuildConfig，SDK37 仅用于类型编译；不是完整 AGP/API37 资源构建，也没有旧生产 class 替代当前源编译 |
| 依赖 | JDK17、Kotlin2.2.10、原 Maven 字节锁；未 force 依赖、未修改校验元数据、未放宽测试/资源输入门禁 |
| 架构/输入/卫生 | core 边界 25 项、runtime 输入契约夹具 18 项、源码卫生通过 |
| 全树格式 | Java 1831 文件 / 59 批、Kotlin 7 文件 / 4 批检查通过，均 0 个需格式化；Java adoption 清单不增删 |
| 可选接缝独立回归 | 仅叠加三个 runtime 源文件的冻结基线重新全编译，14 类 / 210 项通过，failure/ignored/assumption 均 0；可选回调未接生产前保持旧行为 |
| Gradle 基线 | `dsha-gradle` 14 类定向请求 exit1，缺本树离线资产，停于 `prepareBackupAssets`，未执行到测试 |
| Gradle 最终请求 | 按基线 descriptor 摘要复制并回读 6 份固定资产（489078186 字节）后，`compileStandardDebugKotlin` 加 4 类定向单测请求 exit1；`prepareBackupAssets` 通过，`verifyRuntimeDescriptorInputs` 正确拒绝 `DESCRIPTOR_LAUNCHER_BYTES: core/HarnessSessionState.java`。未到 Kotlin 编译或 Test，不记 Gradle 通过 |
| 完整 CI / Lint / APK / 真机 | 本批未跑、未 dispatch、未打包、未签名或安装、未做真机信号/覆盖更新/视觉验收；不继承其它 main 提交的 CI 或历史设备证据 |

控制器用例实际运行生产队列、`WebStopCoordinator.stopObserved`、原 `WebStartProtection`、`StartupEnvironmentActions` 和 pipeline，再到注入的派发 IO。成功分支停止事务只提交一次；LAN、桥释放、工具/迁移/unlock 仍运行。成功派发由受控内存 Process 承接，真实 drainer 等待输出，收尾先旧化 generation 再释放；没有启动设备 guest。平台用例使用真实临时目录、inode/字节和注入的内核观察，运行生产 `prepareStopRecheck`，不是仅测纯 helper。

反例覆盖重复 Stop、真实 A/B 两次 coordinator 操作错配、owner/operation 引用错配、`gR/gS/gN`、已排队服务销毁、claim 后保护丢失、原对象替换、新 run、新 guest/trial、root/boot 改变、主/trial 新 PID/identity 在 freeze 前与后出现、marker 相同 inode/mtime 不同字节及替换 inode、读取失败、超长 stale、末次同步收尾失败、pipeline 新存档保留、启动器准备中的服务/guest 变化。真实 builder 用例在 `applyEnv` 完成后由最终检查抛错，验证不会进入进程创建。

原失败日志保留：C1 的 1 项旧 synthetic fixture 缺 typed completion；C2 新夹具缺宿主 provider 导致未进入 pipeline；C3 的 5 项失败均为 normal-path 故意抛 exec 错误引发真实插件失败收尾、夹具未等待的租约清理。分别修 typed 夹具、安装原宿主 provider、使用受控内存 Process 后，C4 17 类 254 项全过，未删除或降级断言。

本机补充证据（不入库）：

```text
/tmp/s1b-baseline-gradle-55c56cf8.log
/tmp/s1b-baseline-55c56cf8/compiled/junit.log
/tmp/s1b-candidate-c1-55c56cf8/junit.log
/tmp/s1b-candidate-c2-55c56cf8/junit.log
/tmp/s1b-candidate-c3-55c56cf8/junit.log
/tmp/s1b-candidate-c4-55c56cf8/{source-inputs.json,resource-comparison.json,final-summary.json,junit.log}
/tmp/s1b-final-gradle-55c56cf8.log
/tmp/s1b-final-{core,input-contract,hygiene,java-format,kotlin-format}-55c56cf8.log
/tmp/s1b-seam-55c56cf8/runner.log
```

## 受管输入与主会话集成

[受管清单](../app/src/main/assets/managed-runtime-inputs.json)和[descriptor](../app/src/main/assets/runtime-descriptor.json)保留基线字节；基线 `runtimeId=74d6a4bb7ebbbb4c461c094cc3f8185f82fb8b5bed86baacd502dd49ee228ad6`。当前 515 个 launcher 键不增删，11 个既有来源改变摘要，见下表。没有新增生产文件需登记，没有改归档 recipe/generator、资产字节、基础版本或 dataWrite。

main 应在最终串行集成树核这 11 项源码、重算 descriptor 并执行常规 Gradle/完整 CI；本批 guard 的拒绝不能当作重算后验证通过。集成其它候选后的身份由 main 确认，预期一次受管维护页，不从本批本地预览推导正式 runtimeId。

| 已纳管来源 | 最终 SHA-256 |
|---|---|
| [HarnessSessionState](../app/src/main/java/com/deepseekharness/app/core/HarnessSessionState.java) | `3a66a25c2923c3b63e16909bc673671dff32a1733dc74d08332c2c5a190a5a57` |
| [StartupEnvironmentActions](../app/src/main/java/com/deepseekharness/app/core/StartupEnvironmentActions.java) | `58f858b07eafb0e5c238d83135d38a1ae43dffd4fb993ebd271793480acebc43` |
| [StartupPipeline](../app/src/main/java/com/deepseekharness/app/core/StartupPipeline.java) | `5c2bb79d7c779cb43ca0340148929b4a478eb3832493ed4877286382b7906e78` |
| [WebLifecycleController](../app/src/main/java/com/deepseekharness/app/core/WebLifecycleController.java) | `4caf25e1bff74b5df71f1aa63c15f2f56918ab3bd1457281d7fd081ab9a848e4` |
| [WebProcessSession](../app/src/main/java/com/deepseekharness/app/core/WebProcessSession.java) | `5c8e6818112d78c92a49ad5003c388c138e7e03d6a19d98d59e49ab99acbcf45` |
| [ProotBootstrap](../app/src/main/java/com/deepseekharness/app/runtime/ProotBootstrap.java) | `4fb0f286730bdb08215e5d343e84e448e1d3a682e23fa3f78efc9546f405b466` |
| [RuntimeExecution](../app/src/main/java/com/deepseekharness/app/runtime/RuntimeExecution.java) | `7572cc20db225a293f2ff747da3fb1c4c1aa4799524678b1f382d5430ff6cb53` |
| [RuntimeLauncher](../app/src/main/java/com/deepseekharness/app/runtime/RuntimeLauncher.java) | `9e579b286ad5b1ffee53e3bbbf7c410bb8299ae031774c980e3bfece931d55be` |
| [WebProcessManager](../app/src/main/java/com/deepseekharness/app/runtime/WebProcessManager.java) | `4e6392cdee0314385a09048d50b5b42273c68d79938a62fe0750b699b586334f` |
| [StopHandoff](../app/src/main/java/com/deepseekharness/app/util/StopHandoff.kt) | `4e3f937bfdcebfd90736422adcb2813413df50434260890d3f1753525e0739e8` |
| [WebStartProtection](../app/src/main/java/com/deepseekharness/app/util/WebStartProtection.java) | `2b2124f76773ceaa99abdd21088f348badb1a424d19e42dde567ef5dfbd46749` |

## 审查、提交与回退

独立只读审查逐项覆盖全部 14 个代码/测试路径及必要依赖，初审 REQUEST_CHANGES 的 3 项 P1 和 1 项 P2 全部实修并补反例，最终 **APPROVE，剩余 P0/P1/P2 均 0**。审查未代替测试，也不构成合入或推送授权。

结构接缝与启用行为分开本地提交：`e1a7673f` 先添加原重载默认不启用的可选末次检查接缝，后续行为提交启用 S1b 并交付测试与本文。结构接缝使用独立冻结基线仅叠加三个 runtime 源文件重新编译，14 类 210 项全过。最终 HEAD 由分支本地提交及会话完成声明记录。

回退先 revert S1b 行为提交，再按需要 revert 可选接缝提交。main 若已生成配套 descriptor，应在同一串行回退树按恢复后的受管来源重算并重验；不需要清用户数据、改偏好键、降级安装或操作手机。
