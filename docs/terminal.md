# 终端：PTY、简易模式与会话关闭

源码更新：2026-10-26。终端工作台基于 build197 `02f80ab` 落地，build198 补上悬浮球/菜单的会话抽屉；本页说明源码合同，不是设备验收报告。构建、测试与交付状态见[终端回归说明](<terminal-regression-20261025.md>)和[CI 合同](<terminal-ci-20261025.md>)，旧审计 APK 不作为操作入口。

## 入口与选择

Main 的“终端”是打开同 task、非导出的 [TerminalActivity](<../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java>) 的动作，不替换原根页或子页；通知可复用该终端宿主。默认极简黑底，页面内菜单球可拖动吸边；无需系统悬浮窗权限。点击悬浮球或工作台顶栏“菜单”打开会话侧栏：侧栏提供 PTY/简易分组、新建、切换、重命名、结束核验，以及返回主界面、显示设置、键盘、粘贴、回到输入和说明。工作台在当前窗口达到 840dp 时把同一侧栏内联常驻；低于该宽度时仍用抽屉。返回键先关闭对话框和抽屉，再交给终端选择/IME，最后才关闭终端页面。

选择保存在本机 `term_pty`；切换模式保留另一种模式的既有会话，并非把同一 shell 转换成另一种协议。两组标签由进程级 [TerminalSessionOwner](<../app/src/main/java/com/deepseekharness/app/core/TerminalSessionOwner.java>) 持有，Fragment 只附着观察者；切页、旋转、语言重建和 Main 普通销毁均不会请求结束终端。应用进程被系统结束后不承诺恢复原 shell；旧 Bundle 的 ownerEpoch 不能认领新进程中的同号标签，不重放输入。

首次进入每种模式最多发起一次默认创建；取消、失败或所有标签已结束后再次进入保持空态，需用户明确新建。侧栏的“返回主界面”和返回键仅关闭页面，与“结束进程”分开。抽屉开合状态随 Activity 保存，但不会持久化会话或命令。

| 模式 | 适合的操作 | 实际行为与限制 |
|---|---|---|
| PTY | vim、htop、tmux、交互提示、逐键输入 | [PtySession](../app/src/main/java/com/deepseekharness/app/PtySession.java) 接 Termux terminal-emulator/JNI；扩展键提供 Ctrl、Alt、Esc、Tab、方向键，支持字号调整与缩放 |
| 简易 | 按行提交命令、查看文字结果 | 管道 shell，命令排队并串行发送；不模拟完整终端，需交互输入/TUI 时切 PTY |

入口实现为 [PtyTerminalFragment](../app/src/main/java/com/deepseekharness/app/ui/PtyTerminalFragment.java) 与 [TerminalFragment](../app/src/main/java/com/deepseekharness/app/ui/TerminalFragment.java)。新标签使用当前环境准备与运行方式；已有标签继续持有出生时的进程身份。运行方式的选择与兼容边界见[运行引擎](runtime-engines.md)。

## 会话、编号与命令状态

[TerminalTabs](../app/src/main/java/com/deepseekharness/app/util/TerminalTabs.java) 的内部 ID 永久递增，显示编号复用已成功关闭的空缺。关闭中或关闭失败的标签继续占号；不能根据显示编号向新会话投递旧回调。PTY 与简易模式各自保存标签与当前选择。

简易终端的 [TerminalSession](../app/src/main/java/com/deepseekharness/app/util/TerminalSession.java) 状态为 `STOPPED → STARTING → READY/BUSY → STOPPING`，失败进入 `FAILED`。启动握手先核验固定 bootstrap 的 PID、会话与出生身份，才允许发送用户命令。尚未发送的命令可等待就绪；初始化超时不表示执行过用户命令。

“中止”会终止本次简易 shell 的进程组，确认退出后创建新 shell；`cd`、`export` 等 shell 内状态随之清除。尚未发送的命令保留，已经发送的命令不会自动重放。部分写入失败属于结果未知，界面提示先核查结果，不能以“重试”自动重复可能有副作用的操作。关闭标签会取消未发送队列。

简易输出经过 ANSI 清理和敏感文本脱敏；缓冲超过 300000 字符时保留后 100000 字符。这只是界面输出上限，不是完整命令审计归档。单条简易命令按 Java 字符串长度限制为 16384，空值及 NUL 被拒绝。

## 关闭、维护与失败

“结束进程”先明确确认目标类型与 ID，标记 `CLOSING`，再在工作线程等待退出。只有 [TerminalSessionOwner](<../app/src/main/java/com/deepseekharness/app/core/TerminalSessionOwner.java>) 核验成功才移除标签；失败持久保存 `UNCONFIRMED`、脱敏错误及原 ID，停止接受新输入，不自动重复结束请求。用户可显式选择“核验并结束”。PTY 自然退出后经真实清理核验可释放保护但保留历史；仅启动器退出仍展示清理待核验，不能画成成功。环境维护仍先关闭 PTY（含未发布 pending）及简易会话，再停止 Web 并取得维护屏障；遇错即止。

### 独立后台保护

[TerminalRuntimeService](<../app/src/main/java/com/deepseekharness/app/TerminalRuntimeService.java>) 与 Web 前台服务独立。可见页面请求新建，只有真实 `startForeground` 成功、精确 `(ownerEpoch, kind, ticket)` 被一次性消费后才允许创建。准备、fork、运行及未确认清理均持有逻辑票；视图离开不释放。服务丢失展示 `LOST`、拒绝新输入，不暗中重新提升、不重放 shell；显式结束仍可核验。通知不是退出证明，也不保证 ROM 永不回收。

PTY 在底层核验 guest 为空后释放保护。简易标签的 `STOPPED/FAILED` 不能证明整个生命周期已清理，保守保留至用户显式结束核验成功；该状态与“页面已返回”不同。原有 RuntimeTasks 维护与 CPU 锁策略保留，本次没有新增整段 CPU 锁或宣称功耗改善。停止 Web 不改变终端服务原因。

[TerminalProcessCloser](../app/src/main/java/com/deepseekharness/app/runtime/TerminalProcessCloser.java) 使用保存的进程身份与会话关系检查退出。不能把 `/proc` 暂时不可读、超时或启动器退出等同于整个 guest 已退出；核验失败继续保留环境占用，不按裸 PID、端口或名称强杀。现用环境不能在该屏障未解除时移动或重建。

终端、插件和 DSH 处于应用的 Android UID 运行域。原生终端允许执行 shell；设备桥的命令白名单不构成终端的强沙箱。输入命令前应按真实工作目录和权限判断影响，详见[安全模型](security-model.md)。

## 核验入口

修改后至少选择与变更相关的 [TerminalTabsTest](../app/src/test/java/com/deepseekharness/app/util/TerminalTabsTest.java)、[TerminalSessionTest](../app/src/test/java/com/deepseekharness/app/util/TerminalSessionTest.java)、[TerminalSessionOwnerTest](../app/src/test/java/com/deepseekharness/app/core/TerminalSessionOwnerTest.java)、[TerminalRuntimeTest](../app/src/test/java/com/deepseekharness/app/runtime/TerminalRuntimeTest.java)。它们分别覆盖编号/关闭、队列与未知结果、所有权和启动输入；测试源码存在不表示本轮已重新执行。真实 PTY、IME、ROM `/proc` 行为仍须精确正式 APK 的设备记录。
