# A4 实现回执：终端与进程身份（2026-11-14）

工作树 `.agents/worktrees/feat/a4-terminal`，分支 `feat/a4-terminal`；代码基线 `5b547868`，任务基线提交 `b9d78170`。Lead 于 2026-11-14 批准[任务基线](a4-task-baseline-20261114.md)的 Q1–Q4 与 S1→S2→S3 顺序。本文按切片保留真实验证范围；**A4 出口尚未完成**，未安装或操作手机。

## S1：退出纯策略、重扫与创建前身份预留

实现：

- 新 [TerminalExitPolicy](../../../app/src/main/java/com/deepseekharness/app/util/TerminalExitPolicy.java) 承担 M04/M14 纯判据；[TerminalProcessCloser](../../../app/src/main/java/com/deepseekharness/app/runtime/TerminalProcessCloser.java) 保留真实 `/proc`、getsid、UID、Os.kill 与错误呈现适配。纯策略无 Android import，也不持有 UI/租约状态。
- 每次 guest 信号前核验原 leader，再复查该 guest 的出生身份、会话和 UID。guest 两次独立扫描未见活成员才请求结束 launcher；launcher KILL 后仍等待两次空会话观察。自然退出路径也双扫确认。拒读、截断、信号失败、残留、时钟回拨、超时或中断均不返回成功；失败时只尝试恢复仍能核验的暂停 leader，不自动重放失败信号。
- 不新增 orphan 强杀：leader 已消失时只核验会话为空，发现残留保持未确认。有限双扫缩小已构造的快照窗口，**不是对任意持续 fork/逃离会话 daemon 的原子证明**。PTY 上层原有 isRunning/最终空会话检查、简易终端的外层 detached lifetime、owner 的“核验成功后移除/释放”保持。
- [TerminalTabs](../../../app/src/main/java/com/deepseekharness/app/util/TerminalTabs.java) 以 opaque Reservation 在创建前消费 ID；最后一个正 long 可留给已预留的待发布对象，随后明确拒绝分配，不溢出、不复用。取消预留只放弃它，不回退 ID；显示编号仍在发布时独立选择最小空缺。
- [TerminalSessionOwner](../../../app/src/main/java/com/deepseekharness/app/core/TerminalSessionOwner.java) 在任何 PTY fork/简易 backend 启动前预留。已 fork 的未确认对象沿同一 reservation 发布；耗尽时在工厂/后端前失败，防止“先 fork 再分 ID”产生无法关闭的隐藏对象。类型与 epoch 作用域不变，不落盘恢复 shell。

新增/补充反例：

- [TerminalExitPolicyTest](../../../app/src/test/java/com/deepseekharness/app/util/TerminalExitPolicyTest.java)：同 SID 不同作业组、首次空快照之后出生的新 child、最终退出观察之间出现 child、leader/birth/SID/UID 变化、leader 在成员捕获时消失、EPERM/截断、空/坏快照、暂停/成员信号拒绝、KILL 后延迟或不退出、中断与回拨、自然退出只查空、不按旧 SID 强停。fixture 只提供可控事实，不发送宿主 PID 信号。
- [TerminalTabsTest](../../../app/src/test/java/com/deepseekharness/app/util/TerminalTabsTest.java)：最后 long 的 reservation 仍可发布、耗尽不再分配、取消与他表 reservation 不可发布、较晚创建先发布时 ID/显示号仍独立。
- [TerminalSessionOwnerTest](../../../app/src/test/java/com/deepseekharness/app/core/TerminalSessionOwnerTest.java)：耗尽在任何工厂/后端前拒绝且不留逻辑 hold；原 pending PTY 失败夹具改用最后 long，验证同一 ID 仍能发布并显式核验关闭。这组有完整 Android 依赖，本机未执行，交 CI。

### S1 验证

当前真实来源的 Kotlin 2.2.10 ShellQuote、javac17、JUnit 4.13.2 通过 `@argfile` 在临时目录编译/运行；只用 Android SDK 37 与 annotation JAR 作编译依赖，**没有 Java 替身、Android/JNI 调用或全量资源编译**。本机临时 harness 放在忽略的 `app/build/a4/`，不进入源码交付；CI 仍走官方 Gradle 任务。

本地选择 20 类：核心 11（[基线 §6.1](a4-task-baseline-20261114.md#61-11-个停止测试的计数口径)）+ ProcStat、TerminalTabs、TerminalReady、PtySessionLockContract、TerminalExitPolicy、TerminalSession、MaintenanceGate、MaintenancePreStop、WebStopCoordinator。该选择已覆盖当前新纯策略；当前 Android 回收适配器及其真实依赖也完成 javac 编译，但没有运行平台读口/信号。

| 检查 | 真实结果 | 限制 |
|---|---|---|
| 定向 JUnit | `OK (141 tests)`，20 类；补记录 listener 后 `Time: 4.557`，`run=141 failures=0 ignored=0 assumptionSkipped=0` | 为核对唯一真实 POSIX 会话用例没有被 Assume 跳过，追加监听后重跑当前选择；零忽略/零 assumption 跳过 |
| 当前 adapter 编译 | `A4_CURRENT_ADAPTER_COMPILED=TerminalProcessCloser` | SDK 仅编译依赖，不表示 Android 已执行 |
| native-session host | `Ran 2 tests in 0.369s` / `OK` | 编译独立 session-launcher；PTY C 握手属于 S2 |
| core boundaries | `Ran 25 tests in 6.727s` / `OK` | 不增加边界 baseline |
| 定向 Java 整理 | `google-java-format 1.22.0: 7 files, 4 changed; exact Java tokens preserved` | 初次 report 路径不在格式器缓存而拒绝，改用其规定缓存目录后执行；未扩大写域 |
| Java/Kotlin 全树检查 | Java `owned/manifest=1769/1769`、`missing=0 extra=0`、`1769 files, 0 need formatting`；Kotlin `2 files, 0 need formatting` | 串行 `--check --jobs 1`，不靠格式检查代替行为验证 |
| source hygiene / 文档链接 / diff | hygiene PASS；现行文档 `56 documents / 1309 ok / 0 warning / 0 error`；基线+本文显式 `2 documents / 117 ok / 0 warning / 0 error`；`git_guard diff --check` PASS | 默认当前文档入口不包含这两份新证据，故另作显式选择；提交前再核对暂存写域 |

未执行：完整 Android 依赖的 TerminalSessionOwnerTest、TerminalPtyThreadContractTest、TerminalRuntimeTest、TerminalActivityLifecycleTest/Navigation/InputContract、WebRecordedStop/OverwriteRetire 等跨域 Android 集、全量 Gradle/Robolectric/Lint/打包、设备矩阵。新增 owner 测试已落盘但不计入本地 141 项。本机 aapt2 的限制按任务基线保留，不用 source-only 或临时 harness 代替 CI 全量/真实 Android 证据。

### S1 写域与受管影响

实际文件：新纯策略/测试、Android closer、TerminalTabs/测试、TerminalSessionOwner/测试、[Java 格式清单](../../../tools/java-format-files.json)的两项增量、[任务基线](a4-task-baseline-20261114.md)审批状态与本文。未改 UI/Fragment/Activity、native C/build/ELF、A5 排除域、backup/Rust、共享索引或版本身份。

既有受管源文件已变化，**预计 runtimeId 变化，覆盖安装一次维护页**。当前已提交 descriptor 仍是旧身份，本工作会话未 `--write`、未手改其字段；最终由 Lead 在合并树重算。新 `util/TerminalExitPolicy.java` 是否列入 launcherSources 需要 Lead 显式核对，不能只靠 adapter 引用就视为已覆盖。数据格式、桥协议与基础环境版本不变。

S1 独立提交后可单片回退；回退要恢复 owner/reservation、Tabs 与 closer/纯策略这一整组，防止混用半套身份预留或退出接口。发布回退仍由主会话构建更高 versionCode 的同签名包，不清数据、不降级覆盖。

## S2：PTY C 握手 host 证据

待 S1 提交后实施；生产 native C/build/ELF 默认只读。

## S3：当前 lifecycle 验收入口

待 S2 提交后实施；旧 ThemeInstrumentation 的终端 lifecycle 接线修复或退役选择将在本节明确。只准备/编译夹具，不安装或操作手机。
