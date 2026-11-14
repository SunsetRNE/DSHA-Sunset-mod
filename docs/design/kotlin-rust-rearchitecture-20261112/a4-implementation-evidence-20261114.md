# A4 实现回执：终端与进程身份（2026-11-14）

工作树 `.agents/worktrees/feat/a4-terminal`，分支 `feat/a4-terminal`；代码基线 `5b547868`，任务基线提交 `b9d78170`。Lead 于 2026-11-14 批准[任务基线](a4-task-baseline-20261114.md)的 Q1–Q4 与 S1→S2→S3 顺序。本文按切片保留真实验证范围；**A4 出口尚未完成**，未安装或操作手机。

## S1：退出纯策略、重扫与创建前身份预留

独立提交：`4ae22009`（父 `b9d78170`，10 文件）。

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

既有受管源文件已变化，**预计 runtimeId 变化，覆盖安装一次维护页**。当前已提交 descriptor 仍是旧身份，本工作会话未 `--write`、未手改其字段；最终由 Lead 在合并树重算。Lead 已明确裁决：新 [TerminalExitPolicy](../../../app/src/main/java/com/deepseekharness/app/util/TerminalExitPolicy.java) 应与 closer 一起纳管，在合并时按受管输入合同加入精确 `util/TerminalExitPolicy.java` 路径并重算；本会话不修改输入枚举器/描述符。数据格式、桥协议与基础环境版本不变。

S1 独立提交后可单片回退；回退要恢复 owner/reservation、Tabs 与 closer/纯策略这一整组，防止混用半套身份预留或退出接口。发布回退仍由主会话构建更高 versionCode 的同签名包，不清数据、不降级覆盖。

## S2：PTY C 握手 host 证据

新增 [测试入口](../../../tools/test-pty-birth-handshake.py)、[host C fixture](../../../tools/fixtures/pty-birth/pty-birth-host.c)和[范围说明](../../../tools/fixtures/pty-birth/README.md)，并登记 [host manifest](../../../tools/host-tests.manifest.json) 的 Linux/JDK17 需求。直接编译当前生产 C 与其头文件，fake JNI table 只提供明确的 callback 接受/拒绝/异常边界；真实执行 fork、setsid、self-stat、socketpair、ACK、exec、kill/waitpid。每次另保留一个无关兄弟进程，要求失败清理没有影响它。

13 项覆盖成功登记后 exec、callback 拒绝、返回 true 但 JNI 异常、class/method/string 失败、ACK 写失败、错误 ACK、parent disconnect、parent 通道保持开放却不 ACK 的 child 3 秒截止、child 不报告 stat 的 parent 3 秒截止，以及交付前缀缺末尾换行的截断/超长报文。ACK 前和 callback 中必须没有 guest marker；成功后读取实际 shell exec 的固定打印；失败后 owned child 必须已被 reap，兄弟仍存活。

证据边界：fixture 的 JNI table 不执行 NativeProcess Java/JVM，不证明 Android loadLibrary、SELinux、非调试 `/proc` 或 Termux Java/JNI 完整连接。malformed 超长用例验证的是 4096 字节无换行报文被截断后的非换行前缀，不宣称原生层检测了任意“有效换行前缀 + 多余尾部”；不把 private self-stat 通道当成同进程恶意代码隔离。fixture 的 PDEATHSIG 只用于驱动超时 containment，不是生产 PTY 新增能力。

| 检查 | 真实输出 / 结果 |
|---|---|
| 直接入口 | `Ran 13 tests in 7.865s` / `OK`，exit 0 |
| manifest 清单 | `PASS_MANIFEST`，`tests: 255`（登记数，不是执行 255 个测试） |
| manifest 实际接线 | `--stage linux --only test-pty-birth-handshake.py --jdk /opt/jdk17` 选 1 个入口 PASS，内部 `Ran 13 tests in 7.735s / OK`；runner exitCode=0 |
| source/fixture/binary 核对 | `INPUTS_UNCHANGED verifiedCases=13`；当前 C SHA-256 `0e6831f187b0e8c7c7c5b154dfac3a1d88cabf3ac5305b10746af457f04d7ebc`；fixture `64e9c8fb…f6d16`；本次 host binary `82deff52…c6f7d`，不宣称跨编译器可复现 |
| S1 定向回归 | 当前 20 类 `OK (141 tests)`；`run=141 failures=0 ignored=0 assumptionSkipped=0`，`Time: 4.676`；adapter 当前 source javac 通过 |
| native-session / 边界 | `Ran 2 tests in 0.298s / OK`；`Ran 25 tests in 9.485s / OK` |
| Java / Kotlin 格式 | Java `1769 files, 0 need formatting`（manifest 1769/1769、missing/extra=0）；Kotlin `2 files, 0 need formatting` |
| 卫生 / 当前文档 | hygiene PASS；默认入口 `56 documents / 1309 ok / 0 warning / 0 error` |
| 本轮显式文档 / 暂存 diff | 基线+本文+fixture README：`3 documents / 127 ok / 0 warning / 0 error`；`git_guard diff --check` PASS；生产 native 目录与 libtermux.so 对 S1 `git diff --exit-code` 为 0 |

S2 未改生产 C/头文件/build 脚本/libtermux.so，也未新增 util/runtime 受管文件、未改 descriptor。测试工具/夹具不改变运行行为；整轮维护影响仍来自 S1 的受管源变化。S2 可独立回退 host 测试与 manifest 条目，不影响设备进程/数据格式。

本轮未跑：真实 Java/JVM JNI 回调、Android PTY、APK/ELF 重建、Gradle 全量/Robolectric/Lint、安装与手机矩阵。S1 的 Android owner 与跨域回归未跑项继续保留，未将这 13 个 host 测试扩写为设备验收。


## S3：当前 lifecycle 验收入口

S3 采用**显式退役**旧 ThemeInstrumentation 终端 `lifecycle` 分支：入口在访问 ConfigStore/启动页面前返回 RESULT_CANCELED 和说明，移除失效的静态 session 反射与全量 shutdown。该夹具保留的主题检查启动目标改为实际 targetContext 包名，不改主题逻辑。当前终端验收集中到 MultiTerminalLanguageAudit。

三份源码变更：

1. [debug Manifest](../../../app/src/debug/AndroidManifest.xml)：Lead 追加批准的范围是**全部**旧 targetPackage，仅将 30 个 `com.dsh.client` 属性值精确替换为 `${applicationId}`，其余 instrumentation 注册/属性及 provider/Activity 不变。
2. [ThemeInstrumentation](../../../app/src/androidTest/java/com/deepseekharness/app/ui/ThemeInstrumentation.java)：退役早返回与当前包名启动，避免旧入口生成“成功”或结束用户终端。
3. [MultiTerminalLanguageAudit](../../../app/src/debug/java/com/deepseekharness/app/ui/MultiTerminalLanguageAudit.java)：只针对本夹具创建的标签，准备离页重进/真实方向切换/zh↔en 的 epoch、类型、ID、当前选择、PID/birth/SID/PGID 保留检查。系统没有重建/没有执行方向变化、身份读不到或不符时失败；没有以 Activity.recreate 冒充旋转。PTY 使用保存的出生身份与当前内核再核验，简易会话捕获当前独立 SID/birth；语言重建也验证 owner 表未变。关闭前保存精确标签对象/出生证据，移除后检查 VERIFIED、空会话与 PTY pending 已退；清理异常使最终结果失败/取消，不能留下 PASS 文本。PTY 本次不可变 launcher 路径按 `runtime=proot|proroot`（缺省当前配置）核对，发现 fallback 不把 proot 当 proroot 成功。该信息只证明 PTY 保存的本次启动路径，**不声称已取证简易 shell 的实际引擎或完整 ROM 矩阵**。

附带同步[任务基线](a4-task-baseline-20261114.md)的追加写域与[终端契约](../../terminal.md)的 ID 作用域、退出双扫边界及现行验收入口。没有修改生产 Fragment/Activity/Service/native，未新增 util/runtime 受管文件；debug/AndroidTest/文档不改变 runtimeId，整轮一次维护页仍来自 S1，descriptor 仍由 Lead 纳管新策略后统一重算。

### S3 本地验证

| 项目 | 真实结果 | 证据范围 |
|---|---|---|
| 定向 JUnit | 20 类 `OK (141 tests)`，`Time: 4.213`；`run=141 failures=0 ignored=0 assumptionSkipped=0` | 当前 S1 纯策略/终端/停止回归；**不含 Android 仪器测试** |
| PTY host manifest 接线 | 入口 PASS、exitCode=0，内部 13 项；source/fixture/binary 未变 | 当前 C + fake JNI 回调边界，非 Android |
| native-session / 边界 | `Ran 2 tests in 0.255s / OK`；`Ran 25 tests in 6.655s / OK` | 前者仍是独立 session-launcher；架构 baseline 未增 |
| host 清单 | `PASS_MANIFEST tests=255` | 登记数，不是执行 255 项 |
| Manifest 精确写域 | `S3_MANIFEST_EXACT_ATTRIBUTE_ONLY=30 PASS` | 与 S2 父版本逐字节比对：只有批准的属性替换 |
| AGP 真实合并 | `ManifestMerger2` 32.1.1 对当前 main + debug Manifest，Mod、`.debug`、Rebirth 各 `targets=30 PASS` | 独立 Java 调用固定 AGP 合并器，验证 placeholder；**未运行 Gradle variant/资源/打包/安装** |
| Java 语法/静态接线 | JDK17 对两份变更 Java `S3_JAVA_PARSE_ONLY=2`；5 组静态入口/证据顺序检查 PASS | 只 parse/源码检查，**不是 Android 完整类型检查**。第一次静态 harness 用格式前的 shell 字符串锚点未找到，改为格式无关包名表达式锚点后通过，未据此改产品行为 |
| Java/Kotlin 全树格式 | `1769 files, 0 need formatting`、`2 files, 0 need formatting`；Java manifest missing/extra=0 | 同 S3 源码代次；末尾清理结果文案改动另跑两文件定向格式 |
| source hygiene / 最终文档 | hygiene PASS；默认入口 `56 documents / 1315 ok / 0 warning / 0 error`；基线+本文+host README 显式 `3 documents / 155 ok / 0 warning / 0 error` | 两类发现范围分别记录；最终暂存 diff 在提交前核验 |

独立 ManifestProbe 再调用时曾受新 shell 的 US-ASCII 默认编码影响，Java parse 报中文不可映射；临时 harness 显式指定 UTF-8 后当前源码 parse 与三种身份合并重新通过，未修改产品源码绕过检查。暂存前另修正文档末尾一处多余空行；这些前置失败不算 Android 测试失败，也不合并成一次连续通过。

未跑：变更仪器类的完整 Android 类型/资源编译、Gradle 全量、Robolectric、Lint、APK/ELF 构建、安装、真机识屏与 proot/proroot 配置变更/正常关闭矩阵。当前工作树没有 R.jar/BuildConfig 等完整资源编译产物；没有复制历史 APK/生成 Java 替身来冒充通过。本机 aarch64/aapt2 限制按基线保留。本轮的 Manifest 合并和 Java parse 不扩写成测试包已可安装。

### 其他旧包名只读排查（未修改）

按 Lead 要求，对 debug 与 androidTest 两棵树只读搜索 `com.dsh.client` 和 `dsh.android.client`。S3 白名单内的 ThemeInstrumentation 与 debug Manifest 修正后，剩余 **debug 14 文件/17 处，androidTest 4 文件/5 处**；其中 debug 一处是注释，其余为启动字符串或 provider authority。未命中 `dsh.android.client`。只记录字面命中，不声称执行过或判定全部夹具可用；**这些文件不在本轮写域，没有修改**。

| 源集 | 文件与行 | 字面用途 |
|---|---|---|
| debug | [WebBrowserAudit:170](../../../app/src/debug/java/com/deepseekharness/app/ui/WebBrowserAudit.java#L170) | 旧包 Main 启动 |
| debug | [UiMotionAudit:39](../../../app/src/debug/java/com/deepseekharness/app/ui/UiMotionAudit.java#L39) | 旧包 Main 启动 |
| debug | [StartupRecoveryAudit:53](../../../app/src/debug/java/com/deepseekharness/app/ui/StartupRecoveryAudit.java#L53) | 旧包 Main 启动 |
| debug | [FragmentSessionInstrumentation:36](../../../app/src/debug/java/com/deepseekharness/app/ui/FragmentSessionInstrumentation.java#L36)、[同文件:178](../../../app/src/debug/java/com/deepseekharness/app/ui/FragmentSessionInstrumentation.java#L178) | targetPackage 注释、旧包 Main 启动 |
| debug | [LogPanelAudit:222](../../../app/src/debug/java/com/deepseekharness/app/ui/LogPanelAudit.java#L222) | 旧包 Main 启动 |
| debug | [DiagnosticPtyAudit:82](../../../app/src/debug/java/com/deepseekharness/app/ui/DiagnosticPtyAudit.java#L82) | 旧包 Main 启动 |
| debug | [InstallAuditInstrumentation:275](../../../app/src/debug/java/com/deepseekharness/app/ui/InstallAuditInstrumentation.java#L275) | 旧包 limited entry 启动 |
| debug | [LaunchLogAudit:219](../../../app/src/debug/java/com/deepseekharness/app/ui/LaunchLogAudit.java#L219) | 旧包 Main 启动 |
| debug | [DocumentsAudit:22](../../../app/src/debug/java/com/deepseekharness/app/DocumentsAudit.java#L22) | 旧 Documents authority |
| debug | [DeviceCapabilityAudit:33](../../../app/src/debug/java/com/deepseekharness/app/DeviceCapabilityAudit.java#L33)、[同文件:47](../../../app/src/debug/java/com/deepseekharness/app/DeviceCapabilityAudit.java#L47) | 旧包启动、旧 Shizuku authority |
| debug | [LayoutAuditInstrumentation:314](../../../app/src/debug/java/com/deepseekharness/app/ui/LayoutAuditInstrumentation.java#L314)、[同文件:445](../../../app/src/debug/java/com/deepseekharness/app/ui/LayoutAuditInstrumentation.java#L445) | 两处旧包 Main 启动 |
| debug | [BridgeAuditInstrumentation:505](../../../app/src/debug/java/com/deepseekharness/app/BridgeAuditInstrumentation.java#L505) | 旧包 Main 启动 |
| debug | [WebPortAudit:77](../../../app/src/debug/java/com/deepseekharness/app/core/WebPortAudit.java#L77) | 旧包 Main 启动 |
| debug | [BrowserCompatibilityAudit:136](../../../app/src/debug/java/com/deepseekharness/app/ui/BrowserCompatibilityAudit.java#L136) | 旧包 Main 启动 |
| androidTest | [RealWebInstrumentation:39](../../../app/src/androidTest/java/com/deepseekharness/app/ui/RealWebInstrumentation.java#L39) | 旧包 Main 启动 |
| androidTest | [OptimizationInstrumentation:196](../../../app/src/androidTest/java/com/deepseekharness/app/core/OptimizationInstrumentation.java#L196)、[同文件:225](../../../app/src/androidTest/java/com/deepseekharness/app/core/OptimizationInstrumentation.java#L225) | 两处旧包 Main 启动 |
| androidTest | [RecoveryInstrumentation:169](../../../app/src/androidTest/java/com/deepseekharness/app/core/RecoveryInstrumentation.java#L169) | 旧包 Main 启动 |
| androidTest | [UpdateProcessInstrumentation:67](../../../app/src/androidTest/java/com/deepseekharness/app/core/UpdateProcessInstrumentation.java#L67) | 旧包 Main 启动 |

S3 独立回退仅撤销这三份验收源码与对应文档，不触碰用户会话/数据。A4 保持 ACTIVE/不可合并，等待 Lead 统一处理受管输入、CI/设备出口与非实现者评审；没有 push/合并/安装或手机动作。
