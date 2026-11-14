# A4 任务基线：终端与进程身份（2026-11-14）

状态：**第一阶段待 Lead 审批；仅调查与本文档，不授权第二阶段实现**。

负责人：`@a4-terminal`（`session-74b7cfc3-f8c1-49c2-a6de-87996a01c3ed`）。
代码基线：`origin/main=5b547868`，2026-11-14 已 `git fetch origin` 核实；工作树 `.agents/worktrees/feat/a4-terminal`，分支 `feat/a4-terminal`。当前已发布 build364 与当前 main 分开记账，不用发布 tag 代替代码基线。

依据：[迭代路径 §2.1 / §4 / A4 / §7](iteration-roadmap-20261112.md)、[ADR-0004](../../adr/0004-kotlin-rust-direction.md)、[收敛表 M04/M14](convergence-table-20261112.md)、[终端契约](../../terminal.md)、[运行引擎](../../runtime-engines.md)、[端口与网络](../../ports-and-networking.md)。路线图的历史计数不是本轮执行回执；进度看板和文档总索引由 Lead 更新。

## 1. 目标、非目标与交付门禁

A4 覆盖 F03、M14 终端和 M04 进程身份/退出证据：在现有两种终端闭环上补齐可测试边界与退出证据，不重写终端模拟器。

目标：

1. 切页、旋转、语言切换只替换视图；进程、cwd/export、未发送草稿与当前选择继续属于进程级 owner。
2. PTY 与简易终端各有标签、会话和选择；身份不复用，显示编号在**已核验关闭**后复用最小空缺。关闭中/未确认仍占号。
3. PTY 保留原生 fork/exec 出生握手：子进程自读 stat，父进程登记并 ACK 后才 exec；禁止改成 Java 启动后单次读取。
4. 关闭只针对本次会话；信号前核验出生身份、会话与归属，不按端口、名称、裸 PID 或旧 PGID 强停。launcher 退出不等于 guest 已退。
5. proroot 按该次独立会话回收 guest；核验 launcher 与整个会话退出后才能释放工作锁、移除标签或让维护移动运行树。未知保持阻塞，不自动重放输入或结束请求。
6. 具名冻结本轮停止回归集合，补生产回收策略与 PTY 原生握手的行为证据；测试 fixture、真实 host、Android 设备证据分别记账。

非目标：Gradle 拆模块、批量 Java→Kotlin 翻译、Compose、Rust 进程执行器、Web 生命周期/端口策略重写、设备 Shell 权限改造、插件/备份/数据格式迁移、恢复旧 shell 或自动重放命令。现有文件维持 Java；纯判据继续抽到零 Android import 的 `util/` 并配单测。不创建新持久化会话 schema，不修改历史 SharedPreferences 键。

门禁：本文先独立提交并以 `peer_report ready` 交 Lead。**Lead 批准本文范围与未决口径后，才改应用/测试/工具源码**。基线审批与最终代码合并审批分开；本会话不 push、合并、tag、Release、安装或操作手机，也不发起真机故障注入。

## 2. 现状核对：满足、需改与仅缺证据

以下行号均绑定 `5b547868`。源码静态满足表示调用链已经存在，不表示本轮实测过 Android 运行行为。

| 项 | 现状和依据 | 分类 / A4 处理 |
|---|---|---|
| 状态所有者 | [TerminalSessionOwner:19](../../../app/src/main/java/com/deepseekharness/app/core/TerminalSessionOwner.java#L19) 为进程级 shared，`:21` 独立 ownerEpoch，`:34` 两套 TerminalTabs，`:104`/`:112` attach/detach 只管理观察者 | **已满足**，保持唯一 owner；不在 Fragment 再建进程状态机 |
| 返回与重建 | [TerminalActivity:106](../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L106) 区分旧进程 Bundle，`:143` 返回只离页，`:147` 固定命令只消费一次；[PtyTerminalFragment:849](../../../app/src/main/java/com/deepseekharness/app/ui/PtyTerminalFragment.java#L849) 和 [TerminalFragment:600](../../../app/src/main/java/com/deepseekharness/app/ui/TerminalFragment.java#L600) 仅取消尚未开始的创建票、解绑视图；两个 onDestroyView 不结束进程 | **源码满足 / 设备证据待补**；不能将 FakePane 生命周期测试写成真实 PID 保活 |
| 终端专属前台保护 | [TerminalRuntimeService:36](../../../app/src/main/java/com/deepseekharness/app/TerminalRuntimeService.java#L36) 要求可见 Activity，`:103` 真实前台提升后才准入，`:117` START_NOT_STICKY；[TerminalRuntimeProtection:199](../../../app/src/main/java/com/deepseekharness/app/core/TerminalRuntimeProtection.java#L199) 精确 epoch/kind/ticket 单次消费，`:306` 服务丢失保留 LOST | **已满足**；服务/通知不当作退出证明，不暗中重启 shell |
| ID 与显示编号 | [TerminalTabs:116](../../../app/src/main/java/com/deepseekharness/app/util/TerminalTabs.java#L116) 每套表 next 递增，`:126` 选择最小空缺，关闭失败仍留表；[TerminalSessionOwner:61](../../../app/src/main/java/com/deepseekharness/app/core/TerminalSessionOwner.java#L61) 用 epoch 区分进程 | **现有进程内语义满足**；“永久”是否要求跨进程落盘须先裁决，见 Q1；long 耗尽目前无拒绝分支，作为边界补强候选 |
| 单标签关闭 | [TerminalSessionOwner:373](../../../app/src/main/java/com/deepseekharness/app/core/TerminalSessionOwner.java#L373) PTY finishAndWait 成功后才 remove；`:400` 简易 disposeAndWait 必须 true；`:383`/`:411` 失败保留 UNCONFIRMED；[TerminalActivity:1706](../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L1706) 确认精确类型/ID/epoch 后才提交 | **已满足**，补错误分支与跨 detach 的证据，不改为先删 UI 再清理 |
| PTY 出生握手 | [构建补丁:43](../../../tools/termux-jni/build.ps1#L43) 注入 socketpair/fork/setsid/父握手；[dsha-pty.c:34](../../../tools/termux-jni/dsha-pty.c#L34) child 自读 stat，`:47` 等 ACK；`:52` parent 调登记，`:70` 无 JNI exception 且接受才 ACK；[NativeProcess:33](../../../app/src/main/java/com/deepseekharness/app/runtime/NativeProcess.java#L33) 核对 fork PID、PPID、ownsSession；[PtySession:186](../../../app/src/main/java/com/deepseekharness/app/PtySession.java#L186) receiver 保存身份 | **已满足原生登记/ACK 契约，缺 PTY 专属行为测试**；父端未再读一份内核 stat，不得误报成“没有父确认”，见 Q2 |
| 未发布 PTY 的工作锁 | [PtySession:155](../../../app/src/main/java/com/deepseekharness/app/PtySession.java#L155) 先登记 detached work/pending，再准备与 fork；`:194` 分未 fork、已登记但 Java PID 未返回、已 fork 三路处理；[TerminalSessionOwner:228](../../../app/src/main/java/com/deepseekharness/app/core/TerminalSessionOwner.java#L228) pending 失败对象发布为可显式核验的标签 | **已有防线，测试不完整**；factory 假失败不能替代 JNI 失败释放时序 |
| PTY/proroot 退出证明 | [PtySession:312](../../../app/src/main/java/com/deepseekharness/app/PtySession.java#L312) 调 closer，等待 isRunning=false，`:340` requireSessionEmpty 后才 releaseWork；自然退出回调 `:425` 也走同一核验 | **末端防误释放已满足**；JNI waitFor 或 launcher 退出单独不构成证明 |
| guest-first 回收 | [TerminalProcessCloser:63](../../../app/src/main/java/com/deepseekharness/app/runtime/TerminalProcessCloser.java#L63) 核验/STOP leader，扫描同 UID/SID guest，逐个核验/结束后才结束 launcher；`:178` signal 内再读出生身份；`:118` leader 已退仅确认会话空 | **已实现保守回收 / 仍有枚举竞态与测试缺口**，见 §3；不同 job PGID 仍按同 SID 处理，不使用负 PGID kill |
| 读取失败与信号错误 | [TerminalProcessCloser:147](../../../app/src/main/java/com/deepseekharness/app/runtime/TerminalProcessCloser.java#L147) stat 暂不可读最多等 500ms，getsid 区分 ESRCH、离开原 SID 与未知错误；`:185` 仅 ESRCH 可接受，其余抛出 | **已有 fail-closed 分支，未有直接行为测试**；抽纯策略/端口后锁住这些结果 |
| 简易终端寿命 | [SimpleTerminalBackend:24](../../../app/src/main/java/com/deepseekharness/app/core/SimpleTerminalBackend.java#L24) 独立 detached lifetime；`:62` 核验 READY birth，`:72` 先 closeSimple/guest 再确认 launcher；[TerminalSession:455](../../../app/src/main/java/com/deepseekharness/app/util/TerminalSession.java#L455) 只有 terminate 成功才 releaseLifetime，`:433` 部分写入不重发 | **已满足**；[TerminalRuntime:73](../../../app/src/main/java/com/deepseekharness/app/runtime/TerminalRuntime.java#L73) 的内层 Work 仅跟 launcher，不能孤立拿它证明整个 guest 寿命；外层 lifetime 仍是必要屏障 |
| 维护关闭顺序 | [MaintenanceCoordinator:193](../../../app/src/main/java/com/deepseekharness/app/core/MaintenanceCoordinator.java#L193) 调 owner.closeAllAndConfirm；[TerminalSessionOwner:479](../../../app/src/main/java/com/deepseekharness/app/core/TerminalSessionOwner.java#L479) PTY pending/已发布→简易；[MaintenanceGate:92](../../../app/src/main/java/com/deepseekharness/app/util/MaintenanceGate.java#L92) terminals→Web→maintenance fence，失败即止 | **已满足**；A4 不复制/改写 A5 的维护状态机 |
| RootShellMain / ShellService | [RootShellMain:111](../../../app/src/main/java/com/deepseekharness/app/RootShellMain.java#L111) 为 root 设备请求构建 ShellService，`:165` 外接执行、`:212` 虚拟屏/原始 root shell；[ShellService:28](../../../app/src/main/java/com/deepseekharness/app/ShellService.java#L28) 走 DeviceShellExecutor，`:41` 回收自己登记的特权子进程 | **已调查，属于 M06 边界**；PTY/简易 backend 未经过它们。路线图用 RootShellMain 举 runtimeId 例子不代表 A4 必须修改它；本轮不改其权限、看门狗或设备命令结果文本 |
| 真机旧夹具 | [ThemeInstrumentation:244](../../../app/src/androidTest/java/com/deepseekharness/app/ui/ThemeInstrumentation.java#L244) 硬编码旧包名，`:268` 反射已不存在的 PTY 静态 session，`:304` 全量 shutdown；[MultiTerminalLanguageAudit:69](../../../app/src/debug/java/com/deepseekharness/app/ui/MultiTerminalLanguageAudit.java#L69) 已用 shared owner，`:118` 只关闭自己创建的标签，`:207`/`:336` 语言切换 | **确认的验收工具缺陷，需修或显式退役旧 lifecycle 分支**；不能将旧夹具列为现行可直接运行的验收 |

## 3. 需要改的最小切片

### S1：把退出判据变成可执行、可反例验证的 M04/M14 边界

从现有 [TerminalProcessCloser](../../../app/src/main/java/com/deepseekharness/app/runtime/TerminalProcessCloser.java#L63) 抽出纯策略协作者（建议新 `util/TerminalExitPolicy.java`）：只消费类型化 proc/会话/UID/信号观察与 clock/port，不直接读取 Android、记录业务日志或持有页面；Android adapter 保留在原 runtime 文件。owner 仍是标签移除与寿命释放的唯一出口，不建立第二套持久状态。

新增行为测试覆盖：PID/birth/SID 在捕获与信号前变化、错误 UID、独立 job group、stat 截断、EPERM 与 ESRCH、超时/中断、信号拒绝、KILL 后延迟退出、最后一次扫描仍有 guest、扫描期间新后代、launcher 先自然退出及最终会话不空。每种未知分支必须断言不移除标签/不释放持有，且不得向未核验身份发信号。可用受控 fake proc/clock/signal 做确定性反例；不能用测试调用计数代替真实 ROM 证明。

现有 `:80` 的 `/proc` 单次快照与 `:105` 的 active=false 存在窗口：最后 guest 在快照之后 fork、自己在遍历前退出，新 child 可能未在快照中，随即进入 `:110` launcher KILL。末端 session-empty 会挡住错误释放，但不能将这一轮写成“所有 guest 严格先退出”。抽取时必须保留最后核验，并补重扫/身份复查与预算耗尽的显式未确认结果；遇持续派生、leader 消失或无法完整观察时保守停在未确认，**不承诺有限扫描能原子证明任意 daemon 全部退出**。是否进一步改变 guest-first 协议按 Q3 裁决，不能顺手引入裸 SID/PGID 强杀兜底。

编号逻辑只做边界补强：保持现有 ownerEpoch/kind/id 身份形状、最小空缺规则和既有普通路径；为 long 耗尽定义明确拒绝，禁止溢出到 0/负值或复用旧 ID。跨进程落盘不在默认方案内，见 Q1。

### S2：补 PTY 本体的原生握手证据

[现有 host handshake 测试:152](../../../tools/test-native-session-handshake.py#L152) 编译的是独立 session-launcher，**没有执行 dsha-pty.c/JNI socketpair 握手**。[TerminalPtyThreadContractTest:21](../../../app/src/test/java/com/deepseekharness/app/core/TerminalPtyThreadContractTest.java#L21) 明说不 fork/JNI，[PtySessionLockContractTest:37](../../../app/src/test/java/com/deepseekharness/app/PtySessionLockContractTest.java#L37) 是源码锁形状检查。

补 host PTY 握手测试（建议新 `tools/test-pty-birth-handshake.py` 与专用 host fixture）：直接编译当前 dsha-pty.c，验证 ACK 前 guest 不执行、父拒绝/抛错/连接关闭/超时不放行且只回收本次未 reap 的 fork child、成功登记先于 exec、stat 过长/截断拒绝。host fixture 的 JNI callback/环境边界必须写清，不冒充 Android JNI 与非调试 APK。默认不改原生协议/二进制；若反例证实 native 缺陷，先把具体修复及 ELF 重建/摘要证据追加到基线，再与 Lead 确认。

### S3：更新当前可用验收入口，保持页面只有呈现职责

修正或显式退役旧 ThemeInstrumentation lifecycle 反射路径；优先复用已有 MultiTerminalLanguageAudit 的 owner 接线，避免两个夹具各自维护真实会话。设备矩阵扩充到返回/重新进入、旋转、zh↔en，以及 proot/proroot 各自的多标签与正常关闭；只核验夹具自己新建的标签/身份，不全量结束用户既有会话。

设备端不制造 PID 复用、信号拒绝或强杀故障；这些错误反例在纯策略/host fixture 中验证。实际设备验收留给 Lead 在有授权的独立测试包上执行，本会话只准备和编译检查夹具。生产 UI 在未发现实质缺陷前保持原样；若纯策略引入稳定失败码，文案仍在 UI 显示边界翻译/脱敏。

## 4. 第二阶段逐文件写域与 A5 交接

本提交**只写本文**。下表是获批后的候选白名单；新增范围或跨域修改先更新基线并报 Lead，未列者不因所在目录相同就取得写入权。

| 切片 | 候选文件 | 允许的改动 |
|---|---|---|
| S1 | 新 `util/TerminalExitPolicy.java` / 对应新单测 | 纯观察/顺序/失败判据与确定性错误反例；不 import Android |
| S1 | [TerminalProcessCloser](../../../app/src/main/java/com/deepseekharness/app/runtime/TerminalProcessCloser.java) / 新 `runtime/TerminalProcessCloserTest.java` | 把现有 proc/getsid/UID/Os.kill 接到纯策略；末端空会话确认不能删 |
| S1 | [PtySession](../../../app/src/main/java/com/deepseekharness/app/PtySession.java)、[SimpleTerminalBackend](../../../app/src/main/java/com/deepseekharness/app/core/SimpleTerminalBackend.java)、[TerminalSessionOwner](../../../app/src/main/java/com/deepseekharness/app/core/TerminalSessionOwner.java)；[PtySessionLockContractTest](../../../app/src/test/java/com/deepseekharness/app/PtySessionLockContractTest.java)、[TerminalSessionOwnerTest](../../../app/src/test/java/com/deepseekharness/app/core/TerminalSessionOwnerTest.java)，按接线需要新增 `core/SimpleTerminalBackendTest.java` | 必要的类型化结果/释放接线与失败保留断言；禁止 UI 析构停止进程 |
| S1 | [TerminalTabs](../../../app/src/main/java/com/deepseekharness/app/util/TerminalTabs.java)、[TerminalTabsTest](../../../app/src/test/java/com/deepseekharness/app/util/TerminalTabsTest.java) | 耗尽拒绝/编号/旧回调边界，普通 ID 规则不变 |
| S2 | 新 `tools/test-pty-birth-handshake.py`、专用 `tools/fixtures/pty-birth/` | 当前 native C 的 host 行为测试，不复制一份生产算法 |
| S2 | [host-tests.manifest.json](../../../tools/host-tests.manifest.json) | 只登记新测试的明确需求/范围/限时，不改既有分组豁免 |
| S1/S2/S3 | [java-format-files.json](../../../tools/java-format-files.json) | 只增删本轮新增/退休 Java 源文件的格式清单条目，不全仓重排；受管输入清单交 Lead 同步 |
| S3 | [ThemeInstrumentation](../../../app/src/androidTest/java/com/deepseekharness/app/ui/ThemeInstrumentation.java)、[MultiTerminalLanguageAudit](../../../app/src/debug/java/com/deepseekharness/app/ui/MultiTerminalLanguageAudit.java) | 精确包名/owner/本夹具会话接线；正常配置变更矩阵 |
| 证据 | [本文](a4-task-baseline-20261114.md)、[终端文档](../../terminal.md)，后续 A4 出口记录（新文件） | 同步合同、真实执行数、未跑项与回退；不改共享进度索引 |

只读保留、不计划改动：[TerminalRuntimeService](../../../app/src/main/java/com/deepseekharness/app/TerminalRuntimeService.java)、[TerminalRuntimeProtection](../../../app/src/main/java/com/deepseekharness/app/core/TerminalRuntimeProtection.java)、[NativeProcess](../../../app/src/main/java/com/deepseekharness/app/runtime/NativeProcess.java)、[TerminalRuntime](../../../app/src/main/java/com/deepseekharness/app/runtime/TerminalRuntime.java)、[RootShellMain](../../../app/src/main/java/com/deepseekharness/app/RootShellMain.java)、[ShellService](../../../app/src/main/java/com/deepseekharness/app/ShellService.java)、终端 Fragment/Activity。native C、build 脚本和 libtermux.so 同样默认只读；不能仅为补测试重建受管 ELF。

与 A5 交接（**A4 不写**）：[HarnessController](../../../app/src/main/java/com/deepseekharness/app/core/HarnessController.java)、[ProotBootstrap](../../../app/src/main/java/com/deepseekharness/app/runtime/ProotBootstrap.java)、[ContainerRuntime](../../../app/src/main/java/com/deepseekharness/app/runtime/ContainerRuntime.java)、[InstallPipeline](../../../app/src/main/java/com/deepseekharness/app/runtime/InstallPipeline.java)、[WebProcessManager](../../../app/src/main/java/com/deepseekharness/app/runtime/WebProcessManager.java)、[RuntimeTasks](../../../app/src/main/java/com/deepseekharness/app/core/RuntimeTasks.java)、[ExtractActivity](../../../app/src/main/java/com/deepseekharness/app/ui/ExtractActivity.java)、[UpdateDownloadService](../../../app/src/main/java/com/deepseekharness/app/UpdateDownloadService.java)。其中 RuntimeTasks 实际在 core 包，版本线所用 runtime/ 名称按此澄清。保留它们现有接口；A5 复用 A4 的退出结果时不得删掉 terminals→Web→fence 顺序。若 API 必须变更，只提最小签名/调用方清单给 Lead 协调。

A3 的 `backup/`、R3 的 `rust/` 与 `util/RustArchive*` 不写；共享文档总索引/路线图、版本号/签名/发布身份、descriptor 由 Lead 收口。本轮无任务看板文件，不自行初始化或编辑看板。

## 5. runtimeId、维护页与回退

[当前 descriptor:3](../../../app/src/main/assets/runtime-descriptor.json#L3) 为 `bd0016c33e807a8e60cec8b3267e78fef0d003f618c083227cb651bd274eaabf`。`:17` PtySession、`:150` SimpleTerminalBackend、`:155`/`:156` 保护与 owner、`:250` NativeProcess、`:281` TerminalProcessCloser、`:428` TerminalTabs 均已进入 launcherInputs。`:19` RootShellMain 与 `:20` ShellService 也在，但不是本轮必改项。`ui/`、debug/androidTest 和本文不在受管身份输入。

- **第一阶段 docs-only：runtimeId 不变，零维护影响**；不执行 descriptor `--write`。
- **第二阶段按 S1 修改既有受管文件：runtimeId 必变，覆盖安装看到一次整屏维护页是预期**。不通过删 launcherInputs 或手改 runtimeId 规避维护。
- 新纯协作者的受管覆盖必须由 Lead 核对：[输入枚举器:117](../../../tools/runtime_input_contract.py#L117) 显式 launcherSources 与 launcherTrees 的归属不同；新 util 文件不能假定自动进入。建议随受管 adapter 一起纳入精确输入合同，最终由 Lead 在合并树重算。[生成器:80](../../../tools/prepare-runtime-descriptor.py#L80) 将资产与 launcher 摘要一起算 ID。
- `version=1`、基础环境版本、桥协议 3、[dataWrite:503](../../../app/src/main/assets/runtime-descriptor.json#L503) `dsh-0.2.2-rc.1` 均不随 A4 改动。终端运行标签不写新数据格式，不迁移用户 shell 状态。

回退按切片保留独立提交。docs-only 可直接回退该文档提交；实现回退到 `5b547868` 对应终端算法或逐切片 revert，由 Lead 结合在途 R3/A3 的新主线取准确父提交并重算 descriptor。不降 versionCode、不换签名、不清数据；要分发回退版本由主会话构建更高 versionCode 的旧行为包。本会话不创建回退 tag，也不拿丢弃未确认会话/工作锁当成回退。

## 6. 验证矩阵与具名停止测试

### 6.1 “11 个停止测试”的计数口径

[收敛表:34](convergence-table-20261112.md#L34) 只给省略清单，[ADR:119](../../adr/0004-kotlin-rust-direction.md#L119) 和[路线图:177](iteration-roadmap-20261112.md#L177) 未冻结命令/完整类名。[Rust 草稿](drafts/02-rust-core.md) 还提到 runtime 包的 WebRecordedStopTest/WebOverwriteRetireTest。**本轮没有找到唯一可恢复的历史 11 类全集**；不把历史条数或若干 Test 文件数冒充执行范围。

建议在本基线获批时，将下列集合明确命名为 **A4 核心停止 11 类**。它是本次重建的选择，不声称等于原文省略的历史全集。以下路径统一位于 `app/src/test/java/com/deepseekharness/app/`；首行号为关键方法，数量是本次读取源码的 @Test 数，执行结果另见 §7。

| 测试 | 源码方法数 | 能证明 / 不能推出 |
|---|---:|---|
| [ProcessIdentityTest:8](../../../app/src/test/java/com/deepseekharness/app/util/ProcessIdentityTest.java#L8) | 7 | PID/PPID/birth/SID/PGID、proot 区分；不执行平台信号 |
| [ProcessBirthHandshakeTest:15](../../../app/src/test/java/com/deepseekharness/app/util/ProcessBirthHandshakeTest.java#L15) | 3 | 字节行/父/session/birth、坏行/限额/超时；不执行 PTY JNI |
| [ProcessTerminationTest:58](../../../app/src/test/java/com/deepseekharness/app/util/ProcessTerminationTest.java#L58) | 6 | 假 Process 的真实退出等待、回调顺序和中断；不证明 UID/出生判据 |
| [WebProcSelTest:17](../../../app/src/test/java/com/deepseekharness/app/util/WebProcSelTest.java#L17) | 16 | 命令/PID 选择，排除 launcher；真实采样字符串仍是 fixture |
| [WebPidIdentityTest:12](../../../app/src/test/java/com/deepseekharness/app/util/WebPidIdentityTest.java#L12) | 4 | 出生变化/坏 stat/退出态；不证明平台读取权限 |
| [WebSessionProofTest:42](../../../app/src/test/java/com/deepseekharness/app/util/WebSessionProofTest.java#L42) | 7 | boot/UID/实例/nonce/launcher 与 guest 分离；不执行实际 environ 读取 |
| [WebProofStopTest:43](../../../app/src/test/java/com/deepseekharness/app/util/WebProofStopTest.java#L43) | 9 | UNKNOWN 不发信号、guest 先于 launcher、强制一次；Signal/Clock 为 fixture |
| [WebForceStopTest:24](../../../app/src/test/java/com/deepseekharness/app/util/WebForceStopTest.java#L24) | 3 | 精确候选/二次读取/owner/一次消费；不执行 Os.kill |
| [WebStopEvidenceTest:9](../../../app/src/test/java/com/deepseekharness/app/util/WebStopEvidenceTest.java#L9) | 5 | UID/拒绝/出生变化的证据分类；不证明 errno 映射 |
| [WebStopDiagnosticTest:9](../../../app/src/test/java/com/deepseekharness/app/util/WebStopDiagnosticTest.java#L9) | 2 | 脱敏、不可变、限长、语言无关 |
| [WebStopBudgetTest:11](../../../app/src/test/java/com/deepseekharness/app/util/WebStopBudgetTest.java#L11) | 3 | 预算和时钟回拨；不证明实际耗时/清理完成 |

合计 **65 个源码测试方法**。ProcessBirthHandshake 仍缺 running=false、读取 EOF/异常/中断反例；WebProofStop 的 IOException、KILL DENIED、中断和 GONE/FOREIGN_UID 分支也不因 happy path 绿就自动覆盖。若 A4 修改对应判据，按实际触及补足；A4 不为凑测试数扩大 Web 实现域。

扩展必跑：

- 终端身份/关闭：[ProcStatTest](../../../app/src/test/java/com/deepseekharness/app/util/ProcStatTest.java)、[TerminalTabsTest](../../../app/src/test/java/com/deepseekharness/app/util/TerminalTabsTest.java)、[TerminalReadyTest](../../../app/src/test/java/com/deepseekharness/app/util/TerminalReadyTest.java)、[TerminalSessionTest](../../../app/src/test/java/com/deepseekharness/app/util/TerminalSessionTest.java)、[TerminalSessionOwnerTest](../../../app/src/test/java/com/deepseekharness/app/core/TerminalSessionOwnerTest.java)、[TerminalRuntimeProtectionTest](../../../app/src/test/java/com/deepseekharness/app/core/TerminalRuntimeProtectionTest.java)、[PtySessionLockContractTest](../../../app/src/test/java/com/deepseekharness/app/PtySessionLockContractTest.java)、[TerminalPtyThreadContractTest](../../../app/src/test/java/com/deepseekharness/app/core/TerminalPtyThreadContractTest.java)、[TerminalRuntimeTest](../../../app/src/test/java/com/deepseekharness/app/runtime/TerminalRuntimeTest.java)，加 S1 新行为测试。
- 界面与旧回调：[TerminalActivityLifecycleTest](../../../app/src/test/java/com/deepseekharness/app/ui/TerminalActivityLifecycleTest.java)、[TerminalNavigationTest](../../../app/src/test/java/com/deepseekharness/app/ui/TerminalNavigationTest.java)、[TerminalInputContractTest](../../../app/src/test/java/com/deepseekharness/app/ui/TerminalInputContractTest.java)。前者为真实 Activity + FakePane、不启动 guest；后者含源码断言，不替代运行中 PID。
- A5 交接屏障：[WebRecordedStopTest](../../../app/src/test/java/com/deepseekharness/app/runtime/WebRecordedStopTest.java)、[WebOverwriteRetireTest](../../../app/src/test/java/com/deepseekharness/app/runtime/WebOverwriteRetireTest.java)、[MaintenanceGateTest](../../../app/src/test/java/com/deepseekharness/app/util/MaintenanceGateTest.java)、[MaintenancePreStopTest](../../../app/src/test/java/com/deepseekharness/app/core/MaintenancePreStopTest.java)、[WebStopCoordinatorTest](../../../app/src/test/java/com/deepseekharness/app/core/WebStopCoordinatorTest.java)。不能为凑 11 删掉这些跨域回归。

### 6.2 场景、验收与运行位置

| 场景 | 验收条件 | 运行位置 / 限制 |
|---|---|---|
| 进程身份/信号 | 两次身份不一致、UID 不符或观察未知时零信号；已发后不冒充未发 | 纯逻辑 + S1 proc/signal fixture；平台 errno 另记 |
| PTY 出生 | ACK 前无 guest；拒绝、超时、JNI 异常不 exec；父保存后才 ACK | S2 编译当前 C 的 host 测试；Android 非调试 JNI 仍待设备 |
| 多标签/编号 | PTY/简易独立；失败关闭保 ID/占号；成功后最小空缺可用；旧 epoch/id 回调不投新会话 | util/owner 单测 + 正常设备矩阵 |
| 切页/旋转/语言 | 同一 owner/session/PID/birth、cwd/export/未发草稿/选择保持；不重复固定命令 | Robolectric 验证调用边界；真实 PID 必须另用测试包取证 |
| 单标签关闭 | 核验 guest+launcher 退后才移除；其他标签/Web 存活 | S1 owner 集成反例 + 设备正常关闭 |
| proroot 后代 | 同 SID 不同 PGID guest 被纳入；身份变化/枚举不全/残留留锁；launcher 退不单独放行 | 确定性 fixture；proroot 正常设备路径单独记录，不能用 proot 成功代签 |
| 维护 | pending PTY/已发布 PTY→简易→Web→fence；任一未确认不移动树 | owner/MaintenanceGate 回归 + A5 交接复核 |
| 全仓门禁 | 定向单测、Java/Kotlin 格式、架构边界、卫生、文档链接 | 下列命令；新源码不提高边界 baseline |

第二阶段命令以精确测试类选择运行，日常定向，全量留 CI/合并前串行：

```sh
dsha-gradle :app:testStandardDebugUnitTest --tests '*ProcessIdentityTest' --tests '*ProcessBirthHandshakeTest' --tests '*ProcessTerminationTest' --tests '*WebProcSelTest' --tests '*WebPidIdentityTest' --tests '*WebSessionProofTest' --tests '*WebProofStopTest' --tests '*WebForceStopTest' --tests '*WebStopEvidenceTest' --tests '*WebStopDiagnosticTest' --tests '*WebStopBudgetTest'
dsha-gradle :app:testStandardDebugUnitTest --tests '*Terminal*Test' --tests '*PtySessionLockContractTest' --tests '*ProcStatTest' --tests '*MaintenanceGateTest' --tests '*MaintenancePreStopTest' --tests '*WebRecordedStopTest' --tests '*WebOverwriteRetireTest' --tests '*WebStopCoordinatorTest'
JAVA_HOME=/opt/jdk17 python3 tools/format-java-tree.py --check --jobs 1
JAVA_HOME=/opt/jdk17 python3 tools/format-kotlin-tree.py --check --jobs 1
python3 tools/test-core-boundaries.py
python3 tools/check-source-hygiene.py
python3 tools/check-document-links.py
python3 tools/check-document-links.py docs/design/kotlin-rust-rearchitecture-20261112/a4-task-baseline-20261114.md
git diff --check
```

新 host PTY 测试、编译任务与格式 manifest 按 S2 实际落地补入，不在基线假写为已有命令。本机 aarch64 无可用 aapt2，不能把手工 javac/Kotlin 测试升级成全量 Gradle/资源/Robolectric/打包通过。现有 [run-unit-tests.py:220](../../../tools/run-unit-tests.py#L220) 只枚举 Java，不能直接当作已兼容真实 ShellQuote.kt 的全量 runner；本轮局部验证真实编译 Kotlin 依赖，没有生成 Java 替身。

## 7. 第一阶段真实回执

2026-11-14，本工作树，无产品源码改动：

| 检查 | 实际结果 | 范围 |
|---|---|---|
| fetch / 基线 / 声明 | `origin/main=5b547868`，工作树独立；初始工作树干净 | 无 push/合并/设备操作 |
| core boundaries | `Ran 25 tests in 6.229s` / `OK` | 当前架构源码门禁，不是进程行为测试 |
| source hygiene | `PASS tracked text LF or verified exact managed byte pins, pinned Actions, explicit Maven/Gradle identities, shared UUID validation and history byte policy` | 不改 pin/豁免 |
| native-session host | `Ran 2 tests in 0.395s` / `OK` | 实际编译当前独立 session-launcher；不包含 PTY JNI |
| 定向纯 JVM | JUnit 4.13.2：`OK (79 tests)`，`Time: 0.456`，15 类 | §6.1 核心 11 + ProcStat、TerminalTabs、TerminalReady、PtySessionLockContract；真实 Kotlin 2.2.10 ShellQuote + javac17，生成当前 5743×10 文案字典到临时目录；无 Android/JNI 执行 |
| 文档链接 | 现行入口 `56 documents / 1309 ok / 0 warning / 0 error`；本文显式选择 `1 document / 105 ok / 0 warning / 0 error` | 默认入口不自动覆盖新基线，故另查本文 |
| 暂存 diff / 写域 | `git diff --cached --check` 通过；暂存列表仅本文 | 首次暂存检查发现两处 Markdown 行尾双空格，已改成空段落并复检；无 app/src/tools 变更 |
| Java 格式 | `owned Java: 1767; manifest: 1767; missing: 0; extra: 0; batches: 58`；`pinned Java formatter: 1767 files, 0 need formatting` | 串行 `--check --jobs 1`，未改源码 |
| Kotlin 格式 | `owned Kotlin: 2; batches: 2`；`pinned Kotlin formatter: 2 files, 0 need formatting` | Java 检查后串行执行，整项 exit 0，未改源码 |

局部 JVM harness 的前置失败如实保留：首次 `javac` 不在 PATH；改用 `/opt/jdk17` 后发现 Java-only 编译漏真实 ShellQuote.kt；加入 Kotlin 编译器后，长 classpath 直接 argv 出现 `CoroutineScope` 加载失败。按仓库已记录的本机长参数问题，改为 JDK `@argfile` 传参、真实编译 Kotlin 依赖后，15 类 79 项才通过。以上三次失败均发生在执行 JUnit 之前，未改生产源码、未伪造替身，也不合并成一次连续通过。

未运行：Gradle 全量/资源处理/Robolectric、Lint、APK/ELF 重建、测试包安装、Android 配置变更/IME/ROM `/proc`/proot-proroot 真机矩阵、故障注入、远端 CI。第二阶段结果与设备出口须绑定最终提交/APK，不沿用历史绿灯。两个只读调查子代理的结论仅用于交叉核对，不单列为测试通过。

## 8. 风险与待 Lead 裁决

| 问题 | 推荐基线 | 影响 |
|---|---|---|
| Q1：“永久递增”的作用域 | 保持现有 `(ownerEpoch, kind, id)`：同一进程/同一类型内永不复用，跨进程 epoch 换新；耗尽拒绝。不落盘恢复旧 shell，不合并两类型编号 | 若要求安装寿命内持久计数，须另增受管存储/迁移与回退设计，不能默默改键/schema |
| Q2：父进程“确认”的证据定义 | 保留 child self-stat + parent 解析/登记 + ACK 的现有契约，不强加 fork/exec 窗口父侧 `/proc` 再读 | [dsha-pty.c:11](../../../tools/termux-jni/dsha-pty.c#L11) 明确为规避该窗口的父侧读取权限；与有界会话 parent 再读不同但不违背原生出生握手。双次身份复查继续用于停止信号 |
| Q3：回收补强深度 | 批准 S1 纯策略抽取、枚举竞态反例/重扫与最终空会话核验；无法完备观测时保留未确认/工作锁，不新增 orphan 强杀协议 | 缺少 leader 的旧 SID 不足以授权任意残留 kill；若要求任意 daemon 绝对可回收，需单列 native supervisor 协议，不在小幅重构中暗加 |
| Q4：“11 类”清单与写域 | 接受 §6.1 新具名核心 11 与额外终端/维护集；批准 §4 逐文件白名单；旧 ThemeInstrumentation 只修终端 lifecycle 接线或显式退役 | 不宣称恢复了文档原始省略清单；不扩大 A5/A7 的代码域 |

主要风险：原生 ACK 时序改坏会影响 fork；terminal closer 抽取后异常/中断/末端空会话分支可能被削弱；source-only/JVM 假对象测试容易掩盖平台行为；launcherInputs 新 helper 漏纳入会破坏身份覆盖。止损依次是保留现有握手、先冻结错误反例、保留未确认标签/工作锁、Lead 统一 descriptor 重算和非实现者审查。

本基线提交可用于评审范围和判据；**不等于 A4 已完成、代码可合并或设备验收通过**。待 Lead 批准后按 S1→S2→S3 分片，每片报告实际测试、未跑项与可回退提交。
