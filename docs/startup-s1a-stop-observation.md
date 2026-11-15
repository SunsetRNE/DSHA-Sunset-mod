# 启动 S1a：停止事实与影子交接

本片以 `f9e8d867b695f8f862288fae00f2c0a89455a87f` 为基线。它只在原停止链上采集事实并计算纯影子结果；恢复、普通启动、看门狗、维护与强停仍走原停止链。没有新增信号执行端、生命周期 owner、持久化票据、偏好键、桥协议或数据格式。

## 生产接缝

- `HarnessSessionState` 在既有 `lifecycle` 锁内分配 `StopOperationId`，绑定 owner、操作序号、被停 generation/instance、本次 stop generation 与既有 cancellation revision。`StopReceipt` 将本次操作号与独立 typed completion future 关联，保留原 `submit(Runnable)` 和 `Future<?>` 调用形态；展示用的 `stopCompletion`/`lastStopResult` 不作交接来源。
- `WebProcessSession` 在入队前捕获精确 run、Process handle 与 recovered record；worker 在停止前读取原保存证明，补入同一采样。退出后仍核对被捕获对象，不从 prune 后的空 map 推导退出。`currentRun` 即使暂不在 map 也单独纳入。
- `WebProcessManager.StopTrace` 是一次调用内的 Java 平台账本。它沿 `stopResult`、`WebRecordedStop.Io`、trial 遍历、原 residual scan 和最终 `confirmStopped` 收集来源；不保存在全局/ThreadLocal，不增加停止事务或为差分重复发信号。
- `WebRecordedStop.Io.exitConfirmed` 只在原算法已有明确内核退出、出生变化或 GONE/FOREIGN_UID 结论后报告。signal SENT、缺记录/根、不可见记录退休不升格为新 guest 退出事实。旧无参 Java 入口及信号/等待/升级策略保留。
- `WebStopCoordinator.stopObserved` 执行一次真实旧 coordinator，再完成原桥释放、存档退休、自动备份/清理调度和 LAN 停止。`StopCompletion` 复制 scope 事实并保持不可变；同步收尾失败进入 FAILED。lifecycle 收尾与状态回调也结束后才发布本次 receipt；异常不得发布可交接结果。

五个独立范围为 guest、owned launcher、recovered/retained launcher、trial（目录/marker、payload guest、登记 profile 进程）和原策略范围内的 residual。范围未执行或无法闭环时缺项/UNKNOWN 保留。trial 的 EXITED 表示本次已查停止范围，不表示试运行记录已 CLOSED、事务可删或允许维护。

本次 root/boot 包络使用 NOFOLLOW 目录设备/inode 与当前 boot；输入摘要来自本次实际观察过的记录、出生身份和 trial 范围。guest 终态带不可变 `ProcessKey(boot, pid, started)`，由 Kotlin `bindGuest` 比对全部捕获来源；磁盘另一 guest 的退出或缺少原实例关联都降为 UNKNOWN。trial 最终回核实际 marker/payload 身份、marker 字节和目录成员；marker 读取通过 `BackupFileSystem.read(path, 最初 expected)` 绑定原 inode，并限制为 128 字节，不允许 `small()` 为替换 inode 重新签发身份。

这些仍只描述历史观察，不证明同 UID 外部写者在之后不会替换文件或创建进程。强停尚未采集完整来源时，明确返回未知影子观察，不用通用 STOPPED 填满。

## 纯判据和差分

`StopHandoff.evaluate` 接收同一次 completion、预期/最新 operation、原 owner、当前 stop generation/cancellation revision、环境包络与原前台保护票据的只读状态。结果只可供观察，不发信号、不消费票据、不启动，也不启用 S1b 的快路径。

| 相同 IO 事务中的事实 | 旧投影 | 新纯投影 |
| --- | --- | --- |
| 所有范围有最终退出事实、同步收尾完成、对象/意图/保护未变 | STOPPED | ELIGIBLE |
| guest 信号错误后，同一 guest 最终已确认退出，其他范围也完整 | STOPPED | ELIGIBLE |
| guest/launcher/trial/residual 任一范围未知或缺项 | 可能 STOPPED / Future 正常返回 | INCOMPLETE |
| Stop A 的 completion 被配到 Stop B，或 owner 不同 | 共享展示状态可能仍是 STOPPED | WRONG_OPERATION / WRONG_OWNER |
| 同 generation 的重复显式 Stop 或取消修订改变 | 原恢复票据撤销 | STALE_INTENT |
| 原对象被同 generation/instance 的新对象替换，或空 map 仍有被捕获活 handle | 可能 STOPPED | INCOMPLETE |
| guest 退出后桥释放或末次同步收尾抛错 | FAILED | 不可交接 |
| 服务销毁、无保护、冷启动/看门狗/停用故障插件路径 | 保持原链 | UNPROTECTED_PATH |

差分只重复计算纯投影；测试检查单次 guest/launcher/confirm/finish 调用序列，未双跑停止。具体执行数、失败与验证边界由交付回执填写，不能从本表推导线上命中率或性能收益。

## S1b 接口与前置条件

S1b 必须捕获本次 `StopReceipt`，等待其 typed completion，并核对 receipt.operation 与 expected/latest operation 的精确身份；不能先等共享 Void Future 再读全局最后结果。显式重复 Stop 仍撤销既有票据，即使它复用正在执行的停止任务。

S1a 的 `observesWaitingStop` 只是原 `WebStartProtection.Ticket` 的只读快照，不调用 active/reject、不给票据续期，不是第二套 RestartAdmission。S1b 仍要在 continuation 中验证精确 waitingStop 与服务保护，再使用原 `resumeAfterStop`/`beginStart`/`claimQueuedStart`；`gR → gS → gN` 分开绑定，不能要求新代次等于旧 stop generation。

S1b 在原受保护、`disabledPlugin == null` 的恢复路径才可消费，并在 worker 派发前重新核对 root/boot/记录和新鲜 trial/residual 范围，保留环境门禁、停止哨兵解锁、LAN 停止与旧桥释放。当前 `Environment.inputs` 是历史范围版本，不能拿它直接冒充未来新鲜扫描。进入交接后出现未知、对象变化或许可失效必须阻断，不能当 cache miss 重放停止/启动。

## 输入与回退

受管变更登记见 [S1a 输入增量](startup-s1a-input-delta.json)。三个 util Kotlin 新源必须加入 `launcherSources`；七个 Java 生产源已在原受管集合。正式 manifest/descriptor 由 main 在串行集成树统一重算，本片不覆盖两份共享 JSON。构建命中旧 descriptor 字节门禁属于尚待集成，不报告通过。

## 本地验证回执

| 范围 | 实际结果 | 边界 |
| --- | --- | --- |
| 精确 f9 基线的既有纯逻辑 | 5 类 / 70 项通过 | 冻结原源码；不等于 Android 基线全量单测 |
| C2 当前生产源码全新编译 | 1085 个显式输入（1081 Java、4 Kotlin，含真实生成输入）；Java/Kotlin 产物均 major 61 | 固定 Kotlin 2.2.10 / JDK17；复用 572 项字节一致的真实 API34 资源输入，使用 SDK37 类型；无旧生产 class 替代 |
| C3 API30 兼容修订 | 仅重编最终 `WebProcessSession` 与全部 7 个内部/主 class，major 61；其余 1084 项编译输入与 C2 SHA 相同 | 固定 desugar 2.0.4 不提供 `Stream.toList`，改为逐项捕获与既有 `List.copyOf`；字节码核验移除该新 API 调用 |
| C2 与 C3 定向 JVM/Robolectric | 13 类 / 190 项，0 failure / ignored / assumption | 包含真实 coordinator、真实 session + 注入 IO、输入替换反例，以及原保护/取消/插件队列回归；无真实信号或手机操作 |
| 受管来源登记 | 7 个已登记源变更、3 个新增 Kotlin 来源待 main 串行加入 | 本片两份正式 JSON 保持基线字节，最终 runtimeId 未生成 |
| 固定 DSH 归档配方 | 实算 91 项，差异 0 | 不需要因这次 launcher 来源变化重建 DSH 归档，不改 provenance |
| 常规 Gradle 请求 | 三次均退出 1，未执行到目标编译/测试 | 首次缺离线资产；一次跨树软链被资产边界拒绝；内部字节副本还原后由旧 descriptor 的受管源码摘要不匹配阻止；不记 Gradle 通过 |

C2/C3 各自运行同一目标集合，精确测试集合及执行数：`WebStopCoordinatorTest` 3、`WebStopObservationIntegrationTest` 8、`StopHandoffTest` 9、`RecoveredWebStopBarrierTest` 6、`WebLifecycleStartProtectionTest` 16、`WebLifecycleResponsivenessTest` 13、`WebProcessSessionStopObservationTest` 6、`PluginRepositoryQueryLifecycleTest` 50、`WebRecordedStopTest` 20、`WebStopInputObservationTest` 7、`WebSessionExitEvidenceTest` 8、`WebResidualForceCandidateTest` 5、`WebStartProtectionTest` 39。

较早一轮补充运行有 181 项 / 1 失败，原日志保留。插件 EXPORT 的失败以当前和 f9 七类原版覆盖分别复现，均为 JDK17 拒绝反射 `FileDescriptor.fd`；只给独立测试 JVM 加 `--add-opens=java.base/java.io=ALL-UNNAMED` 后两组原方法均通过。C2 在该参数下重新编译并运行 190 项，C3 对唯一兼容修订再编译后重跑同一 190 项；不修改仓库测试来掩盖失败，不将重复执行相加为独特覆盖。

格式与边界：Kotlin 全量 5 文件 0 需格式，Java 全量 1831 文件 0 需格式，C3 唯一修订文件另检查 0 需格式；core 边界 25 项、运行时输入契约夹具 18 项与源码卫生通过。一次最后的未分片 `git diff --check` 触发本机已知 proroot SIGSEGV（exit139），之前格式/边界/文档步骤均已完成；保留该回执，改用 git_guard 和每片不超过 6 路径完成空白检查，不重放崩溃的大 diff。

两轮只读代码审查发现并修复原 guest key 错配、trial 输入身份遗漏与 stat→read 间替换窗口，最终结论 APPROVE。测试与审查分别记录，审查没有代替运行。尚未跑通完整 AGP 目标、Gradle 全量单测、Lint、APK/签名、安装或真机；这些不在本片通过声明内。

回退按交付列出的提交逆序 revert，恢复 Java 适配层及格式清单，移除三个 Kotlin 纯职责。无数据迁移或偏好键回退；main 按回退后的受管树重算 descriptor。无需清数据、安装或操作手机。
