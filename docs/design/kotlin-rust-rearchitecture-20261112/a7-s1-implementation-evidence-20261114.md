# A7 S1 实施回执：授权组合与通道事实

日期：2026-11-14。状态：**S1 软件切片待 Lead 独立审查；A7 整轮未完成**。
基线：`903cbbfc09ae89d26f7b787ec516ac969cfd0806`。本机工作树 `feat/a7-device`；用户/Lead 的范围与锁序裁决见[任务基线 §9](a7-task-baseline-20261114.md#91-s1-授权快照锁序)。主线后续版本变化不改写本回执的测试基线。

## 1. 本片行为

- 新增纯逻辑 [DeviceAuthorization](../../../app/src/main/java/com/deepseekharness/app/util/DeviceAuthorization.java)：原偏好仍是唯一持久事实源。Root 允许、Root 档位与 ADB 允许从一次原 `getAll` 组合读取，锁外取值、锁内核验 revision/writer；读取与变更交错只返回 CHANGING，不用新采样继续执行旧请求。
- 原生 [ConfigStore](../../../app/src/main/java/com/deepseekharness/app/core/ConfigStore.java) 的 Root/档位方法委托同一围栏；ADB 两处写入口经同一 setter，保留设备页原 `commit` 与失败提示、配对页原 `apply`、既有键名和默认值。开关只接受真实 Boolean，不把字符串/数字转换成新授权。
- [DeviceGrants](../../../app/src/main/java/com/deepseekharness/app/core/DeviceGrants.java) 的短信 setter 仍先撤销再 `commit`，共用变更围栏但保持短信独立存储与布尔语义。没有新增 ASK 档，也没有填补慢 su 启动后的实时短信撤销缺口。
- [RootShell](../../../app/src/main/java/com/deepseekharness/app/RootShell.java) 与 [devicePlan](../../../app/src/main/java/com/deepseekharness/app/HttpShellService.java) 各使用一份授权组合，并在返回/派发前核 current；[ScreenCapabilities](../../../app/src/main/java/com/deepseekharness/app/screen/ScreenCapabilities.java) 用同一 Root/ADB 组合采样通道选择。HostDeviceCommandGuard 外层硬拒、三档、短信、执行侧守卫均保留；公开端点、参数、wire 与协议号不变。
- 新增纯 [DeviceChannelFacts](../../../app/src/main/java/com/deepseekharness/app/util/DeviceChannelFacts.java)，区分配置允许、后端可见、绑定、TCP/鉴权证据、实际 UID 和本次业务授权。Root/Shizuku/ADB 的状态入口及[既有诊断](../../../app/src/main/java/com/deepseekharness/app/device/DeviceChannelDiagnostics.java) 消费真实采样；普通历史响应、requestedRoot 或 connected 文案不补齐 UID，未证仍为 `-1/unverified`，状态采样不产生业务许可。

本片不新增/扩大权限，不改白名单、不迁移授权/密钥、不改 T40/T42，不执行设备命令、权限申请、手机操作或安装。双屏动作凭据类型和完整能力/结果投影留给 S2/S3；F07 仍需在 S4 开工前审定固定来源/路径/版本表；本片不宣称完成 A7 身份或功能全闭环。

## 2. 复核修正与反例

只读完整差异复核发现并闭环了一项 P2：ADB 旧服务鉴权 proof 可能与新服务的全局 connected 文案拼接。现在 [DeviceBridgeService](../../../app/src/main/java/com/deepseekharness/app/DeviceBridgeService.java) 使用纯 `ConnectionLedger` 原子绑定服务 owner、意图 epoch、观察对象身份；探活发布、创建/退休、撤销及全部非连接状态发布都接入。`recordPairResult` 即使返回 CONNECTED 也只更新旧文案，不能借用旧 proof。返前复核原 owner/running/epoch/authority/观察引用。

[DeviceChannelFactsTest](../../../app/src/test/java/com/deepseekharness/app/util/DeviceChannelFactsTest.java) 除类型事实反例外，用实际生产 ledger 验证 A AUTH→B TCP 后 A 的迟到 publish/disconnect/retire 不污染 B、epoch 不回退、TCP 不继承 AUTH、unknown 不借用旧时间和跨账本观察不能冒充。增量只读复核结论 APPROVE，未见新增阻断；此为内部复核，不替代 Lead 安排的独立审查。

[DeviceAuthorizationTest](../../../app/src/test/java/com/deepseekharness/app/util/DeviceAuthorizationTest.java) 覆盖开关×档位×forceRoot×候选状态×命令类别差分，以及撤销前围栏、读写交错、写入失败/异常、第二 writer、source/invalidator/persist 不在锁内。阻塞持久化不阻塞其他读取或 writer；并发 writer 未全部结束前无 READY 快照。

[真实 DeviceGrants 测试](../../../app/src/test/java/com/deepseekharness/app/core/DeviceGrantsTest.java) 新增两项：现有短信 invalidator 运行时旧设备快照已失效且尚未写原偏好；`commit=false` 仍保留原返回及 SharedPreferences 可已更新内存的语义，不发布另一份授权。

## 3. 当前软件证据

| 检查 | 实际结果 / 边界 |
|---|---|
| 纯 JVM 定向 | 当前生产纯源码 + JDK17/JUnit4.13.2，13 类 **102 项，0失败/错误/跳过**；包含白名单、三档、短信、凭据、脱敏、通道选择和新增围栏/owner反例 |
| 授权适配器 | 当前完整生产类编译后运行真实 DeviceGrants + SharedPreferences 注入夹具，**9 项，0失败/错误/跳过**；共计上述两组14类111项。不是 Android SharedPreferences 磁盘/设备运行证据 |
| 原生策略 dry-plan | 当前策略 production Java **35 项**，不启动 Android 进程或设备命令 |
| guest 策略 | **38 项，0失败/错误/跳过**；假设备元数据和记录发送器，无设备派发 |
| 架构边界 | **25 项通过**，未改边界基线 |
| 源码类型 | 从真实 Gradle JavaCompile 任务只读取实际61个依赖 JAR/SDK/R类路径；以当前 main+standard 及真实生成的 BuildConfig/AIDL/UI 字典共 **1018 个 Java 源文件**，JDK17 `javac` exit0；当前 ShellQuote 由固定 Kotlin2.2.10编译，exit0 |
| Kotlin格式 | 全树 **2/2**、需格式化0，exit0 |
| Java格式 | 纳管清单只增本片4个新增文件、无其它条目变动；最终全树 **1782/1782**，需格式化0，exit0；中途一文件待格式化已按固定工具修正后重跑 |
| diff / 文档 | `git diff --check` 通过；基线+本回执显式集 **2文档/75引用目标/0 warning/0 error** |

长 classpath 在本机首次启动 Kotlin/JUnit 时遗漏尾部依赖，分别报 CoroutineScope / Hamcrest 缺类；换为经 SHA 校验的相同短路径工具副本和标准 JVM 参数文件后通过。未改产品代码或测试期望解决加载问题，不把这些启动错误算成测试失败/通过。

## 4. Gradle 与临时描述符证据

按 Lead 明确批准，分支描述符保持原 `8598a8dbf15d48773a41ff41e89935a9effcb5faaa0edc2b82f17dda223b5193`，不 `-x` 跳任何门禁。固定输入由仓库锁定 ZIP 先验总 SHA/成员摘要后恢复，仅为忽略的构建资产。

1. 分支第一次 Gradle 在 `prepareBackupAssets` 因缺固定 `dsh-runtime.bin` 停止，未进入用例；补齐后第二次在 `verifyRuntimeDescriptorInputs` 因源码已变、descriptor 未重算而拒绝，未记绿。
2. 在独立 `/tmp/a7-s1-verify` 副本复制并逐文件验证7369个源码/元数据输入，只在副本重算 descriptor。阶段副本 `b56d32e3…` 的定向 Gradle 通过受管输入/资产/凭据/身份/Manifest 等前置，随后在 `processStandardDebugResources` 被 Maven x86_64 AAPT2 的 `bad machine` 拒绝，**未进入 Android Java/Kotlin 编译及 JUnit 任务，不记 Gradle 测试绿**。
3. 最后静态失败路径修正后只同步已验证的 DeviceBridgeService 到副本，再重算并运行真实 `test-runtime-descriptor-inputs.py`，最终副本 `runtimeId=80a1b5eeed6f6441a221e1c283db6da832bfc2a2a9f2f1bbe826e677b04c4534`，103安装项/205资产/491启动输入，exit0。该副本字节不回带分支；AAPT2架构未变，不重复无效资源构建。
4. 独立 `javac` 使用实际 Gradle 生成/转换的依赖和当前源码补充类型证据，没有绕过原 Gradle 门禁，也不替代资源链接、Robolectric、lint、APK/签名/ELF/JNI 或真机。

主要本地回执：`app/build/a7-s1-host/{junit-final,adapter-test-junit-argfile,android-typecheck,kotlin-compile-short-path}.log`；输入 SHA 表为同目录 `android-typecheck-inputs.json`。固定输入/Gradle/临时 descriptor 日志在 `/tmp/a7-s1-*20261114.log`；不提交机器路径/日志或工具副本作为公开取证。

## 5. main 合并前与后续

- 本片新增生产类 DeviceAuthorization/DeviceChannelFacts 尚未由 worker 加入总 launcherSources；**main 合并树须把这两类精确纳管，再统一重算最终 descriptor/runtimeId**。临时80a1…只用于验证，不是最终身份。
- 未跑/待CI：完整 Gradle 单测、Android/Robolectric 真正服务owner/SharedPreferences磁盘链、lint、资源链接、APK/ELF/JNI重建。
- 待用户授权：三通道实际UID与撤权、双屏/UNKNOWN不重放、F07真实管理器范围、敏感交接与一次维护页矩阵，见[基线 §8.2](a7-task-baseline-20261114.md#82-需用户另行授权的真机动作本会话不执行)。本会话不操作手机。
- S1 独立提交报 Lead 排审；S2→S5 按已批准窗口继续。F07路径/版本审定另交，T40/T42及已知同UID/短信边界未解决；不沿用“基线已合入”当成产品变更已合入。
