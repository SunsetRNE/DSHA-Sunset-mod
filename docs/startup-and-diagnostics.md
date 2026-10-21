# 启动、恢复工作台与诊断导出

> 核验日期：2026-10-21。按本工作树 build190/F12 的入口与数据流核验，F10 `087724c` 及 F11 保留参考历史归属。本页更新只核对源码，未运行构建、软件测试、设备、模型对话或长时验收；最终版本、正式执行数量和发布状态以 [README](../README.md) 与 [F12记录](releases/v0.2.1-rc.2-build190-fix12-20261021.md)为准。

先在 Main 判断失败发生在哪一层，再选择检查、恢复或导出。环境就绪、后端鉴权、网页就绪分别有自己的证据；某一层完成不能替代后一层。安装操作见[安装与维护](install-and-maintenance.md)，配置修复见[配置快照](config-snapshots.md)。

## 启动门禁与状态

| 入口/状态 | 当前行为 | 失败或限制 |
|---|---|---|
| 未完成欢迎 | Main 和恢复工作台先进入 Welcome，再到环境准备 | “跳过介绍”仍进入准备页，不绕过环境合同 |
| 环境未就绪或维护未完成 | Main 转入安装/维护页 | 有明确受限入口时可看原生工具；不把受限进入等同 Web 正常 |
| 首次准备完成 | 先显示 Main，再异步提交一次正常 Web 启动 | 手动 Start/Stop 可覆盖尚在排队的首次请求；旋转不重复提交 |
| Web 启动中 | 当前运行代次记录阶段、时间线和插件观察 | 60 秒提示不是强杀时限；慢启动、超时和未知执行不自动重放业务 |
| 已捕获后端鉴权地址 | 后端输出提供当前本机服务地址与鉴权资料 | 不等于网页 bundle、插件或页面已经就绪 |
| 网页已就绪 | 当前页面观察器报告 ready，写入启动状态 | 仍不证明未使用的插件、外部模型或所有设备能力成功 |

入口为[MainActivity](../app/src/main/java/com/deepseekharness/app/ui/MainActivity.java)、[WelcomeActivity](../app/src/main/java/com/deepseekharness/app/ui/WelcomeActivity.java)、[FirstRunWebStart](../app/src/main/java/com/deepseekharness/app/ui/FirstRunWebStart.java)。[StartupPipeline](../app/src/main/java/com/deepseekharness/app/core/StartupPipeline.java)处理正常启动准备，[StartupDiagnostics](../app/src/main/java/com/deepseekharness/app/core/StartupDiagnostics.java)整合后端与网页观察。页面事件仍校验宿主、运行代次、当前同源文档、nonce 与顺序，见[StartupPageGate](../app/src/main/java/com/deepseekharness/app/util/StartupPageGate.java)；nonce 不是同源脚本之间的安全隔离。

停止与重启必须遵循真实进程身份及退出屏障；不能按端口、名字或裸 PID 强杀。proroot 鉴权前明确失败后的窄回退条件见[运行引擎](runtime-engines.md)，不能把慢启动和插件错误当成换引擎重放理由。

## 宿主重建与网页首次说明

应用宿主进程重建时，[StoredWebSession](../app/src/main/java/com/deepseekharness/app/runtime/StoredWebSession.java)提供noBackup中的加密本机记录；[WebSessionRecovery](../app/src/main/java/com/deepseekharness/app/core/WebSessionRecovery.java)只读识别已经活着的同一个Web。接入要求APK/descriptor/root兼容、boot/PID出生/UID/命令与环境代次实例nonce相符、实际HTTP鉴权成功，并在接入前再次核对进程。它不创建新Web、不恢复或重放旧任务。

严格接入完成第二次内核身份核验并adopt后，[WebLifecycleController.publishRecoveredLan](../app/src/main/java/com/deepseekharness/app/core/WebLifecycleController.java)将本次严格验证取得的cookie交给[LanAuthBridge.publishVerified](../app/src/main/java/com/deepseekharness/app/core/LanAuthBridge.java)。发布前再次核对当前generation、完整URL与实际端口，不再做第二次HTTP exchange，也不重跑`afterAuthenticated`的启动后任务。LAN关闭时不监听；LAN绑定失败记录警告，不撤销已确认的同一Web接入。局域网的完整开关与鉴权边界见[端口与网络](ports-and-networking.md)。

记录缺失但仍有旧PID、证据不兼容、认证失败或身份不可读时保守UNKNOWN并保留屏障；没有记录也没有旧PID的正常冷启动不受此限制。不能把宿主被系统回收推断成guest退出，也不能在UNKNOWN时自动跳解压重建。关联回归为[WebSessionProofTest](../app/src/test/java/com/deepseekharness/app/util/WebSessionProofTest.java)与[WebSessionRecoveryTest](../app/src/test/java/com/deepseekharness/app/core/WebSessionRecoveryTest.java)，不代表真实ROM后台恢复已验。

Main环境门禁由[MainEntryGate](../app/src/main/java/com/deepseekharness/app/ui/MainEntryGate.java)在后台检查，pause/destroy/newIntent或新代次使旧交付票据失效；慢磁盘不取得UI导航权。进入当前Web的鉴权等待有15秒可见完成边界，旧结果不得导航新页面。该UI等待失败不是后端已退出证明，不能据此自动启动第二个后端。测试入口为[MainEntryGateTest](../app/src/test/java/com/deepseekharness/app/ui/MainEntryGateTest.java)。

后端鉴权与网页挂载仍分层。startup观察器晚注入、首次说明或空会话尚无composer时，只用锁定rc2实际布局的`shell-bottom`与`shell-overlay`组合确认挂载，任意非空root不够。首次说明只在官方Host配置的真实视图确认精确`welcomeNoticeVersion`已持久化后结束，不以按钮点击、mutate调用已返回或任意非空值代替确认。

F12对应的两网页内核真实Host负控覆盖了失败响应先到、后台稍后保存而旧页面仍显示错误，以及revision冲突；这些是软件行为记录，不能据手机截图推断某个具体Host错误码。本页未重跑负控，原始对象与执行结果由[F12记录](releases/v0.2.1-rc.2-build190-fix12-20261021.md)绑定。当前[WelcomeNoticeStore](../tools/welcome-notice/WelcomeNoticeStore.js)通过官方共享mirror的`ensure/load`触发Host describe核实，单次界面读等待最多8秒；等待结束不取消已经提交的Host写入。保存响应未知或出现异常时先有界读回，不自动重发写入；仍未保存就保留错误，不伪造已读。自动失败后继续订阅同一mirror，后台稍后出现精确持久确认可结束说明；用户明确返回说明、临时进入或页面销毁才使旧等待分离，临时进入仅对本会话有效。

只有明确拒绝后的官方恢复读显示revision确实变化、当前仍ready且可写，才有限重试同一个确认字段，最多两次写尝试；传输未知不走此分支。Host先持久化并广播、原mutate回答尚未返回时可消费已确认镜像，晚到拒绝不擦除既成确认。其它字段的推送不能重置saving或制造成功。相关挂载源码为[startup.js](../app/src/main/assets/web-integration/startup.js)，回归入口为[test-welcome-notice.mjs](../tools/test-welcome-notice.mjs)。这些机制不表示手机、真实账号或模型验收已完成。

## 原生恢复工作台

[StartupRecoveryActivity](../app/src/main/java/com/deepseekharness/app/ui/StartupRecoveryActivity.java)可在 Web 无法加载时使用，提供最近启动记录、配置快照、插件检查，以及以下动作。

| 动作 | 何时生效 | 当前边界 |
|---|---|---|
| 重试启动 | 环境就绪且无 pending 配置修复后，请求一次正常恢复启动 | 不越过维护/进程门禁，不承诺失败业务自动续跑 |
| 安全启动基础界面 | 本次以独立基础配置启动 | 保留原插件与配置；安全启动不是从系统上沙箱隔离第三方代码 |
| 新建配置文件 | 用户选择 Web/settings/patch 并确认后 | 停止 Web/终端、先保留修复前配置，再替换所选目标 |
| 恢复中断配置修复 | 明确确认后处理所属事务 | 不将读取页面失败自动重放成写修复 |
| 环境安装与修复/兼容运行时回退 | 进入计划与维护链后执行 | 持有停止屏障，试运行/兼容检查与个人数据原位契约仍有效 |
| 宿主备份、只读救援、独立应急 DSH | 打开独立原生入口 | 导出/修复按自己的范围和授权执行；不会因浏览工作台取得写许可 |

[StartupRecoveryModel](../app/src/main/java/com/deepseekharness/app/core/StartupRecoveryModel.java)的读取任务随页面重建保留。环境票据竞争只自动重试“读取记录”，失败的修复和插件操作不重放。多个 pending、数据身份变化或退出未确认时保留原件；不要清理日志以跳过门禁。

原生插件启停经[PluginRepository](../app/src/main/java/com/deepseekharness/app/core/PluginRepository.java)核对`status/name/enabled/intentVerified`，先呈现实际读回的启停意图；完整列表刷新独立合并调度，不让后续扫描遮住已确认结果或错误。意图保存成功表示按该设置重启Web后生效，不代表当前进程已经加载、卸载或ready。列表与短启停脚本共用[GuestScriptBudget](../app/src/main/java/com/deepseekharness/app/runtime/GuestScriptBudget.java)的30秒协作总预算，涵盖排队、准备边界和执行；单次系统I/O、monitor或Binder等待不可被该预算抢占，超时也不是进程已退出证明。结果未知不自动重发，未确认退出继续保留屏障；完整插件语义见[插件运行与权限](plugin-runtime-and-permissions.md)。

## 诊断与日志是不同操作

“生成诊断报告”由[DiagnosticRepository](../app/src/main/java/com/deepseekharness/app/core/DiagnosticRepository.java)取得环境票据后执行有限探针：设备/版本、Bash 与加载器、工具文件、所选运行方式 smoke、Node/npm/Python 实际响应、受管身份、最近试运行、可用空间、当前鉴权/插件观察和宿主资源。它不遍历对话或个人文件，不读取完整 logcat，不在打开诊断页时自动运行耗时探针。

“修复证书与 npm”是明确写操作，只修该范围并再次收集结果；维护 pending 或环境竞争时拒绝。修复失败会保留失败结果，不能因为后续只读报告能生成就显示整体修复成功。宿主 FD/PSS/线程样本仅覆盖当前原生进程，排除 Ubuntu 和独立浏览器子进程；比较同种操作后的多个样本才有意义。

“下载错误日志/另存为”由[ErrorLogRepository](../app/src/main/java/com/deepseekharness/app/core/ErrorLogRepository.java)独立生成有界 `DSHA-diagnostics-…tar.gz`，不依赖 guest、Python 或模型凭据。默认含结构化状态、摘要和现有脱敏操作尾部；`manifest.json`记录每项摘要和采集限制，明确 `dataBackup=false`、`automaticUpload=false`。它不是数据备份。

| 保存/附加选项 | 何时生效 | 失败与隐私边界 |
|---|---|---|
| 下载日志 | 点击后后台生成并保存到 Download/DSHA | 写入/读回失败明确报告，可另存；保留此前可用的导出位置 |
| 另存为 | 选定系统文档目标后写入并读回核对 | 取消选择不提交；Provider 错误不提升为已保存 |
| 仅本次附加说明 | 当前动作勾选后，仅包含用户主动填写的文本 | 不持久化或恢复该选择；上限 32768 字符；不自动捕获业务正文 |
| 复制/导出文本诊断 | 用户主动选择后输出当前报告及说明 | 脱敏是有限格式规则，公开反馈前仍应查看内容 |

默认故障包不采集原始进程输出、对话正文、凭据或完整异常链；已经轮换的历史不能重建。[DiagnosticExportArchive](../app/src/main/java/com/deepseekharness/app/backup/DiagnosticExportArchive.java)限制最多 32 项、单项 256 KiB、总内容预算 1 MiB，标准 tar/gzip 封装并含逐项 SHA-256。当前没有自动上传报告。

## 记录的限额与解读

| 记录 | 当前限额/行为 | 可以证明什么 |
|---|---|---|
| 启动时间线 | [StartupTrace](../app/src/main/java/com/deepseekharness/app/util/StartupTrace.java)：约 48 Ki 字符与 300 行，单行截到 3000 字符，最多 20 个问题 | 本轮保留窗口内的阶段与观察；错误关键词只是相关日志，不自动归责插件 |
| 最近启动 | [StartupHistoryStore](../app/src/main/java/com/deepseekharness/app/util/StartupHistoryStore.java)：最近 5 次，原生私有目录 | 各自代次的 ready/failed/stopped/interrupted，不是全历史 |
| 应用事件 | [DiagnosticHistory](../app/src/main/java/com/deepseekharness/app/core/DiagnosticHistory.java)：32 KiB 尾部；[DiagnosticLog](../app/src/main/java/com/deepseekharness/app/core/DiagnosticLog.java)单条最多约 800 字符 | 脱敏的操作阶段；不是模型消息/终端输入采集 |
| 冷安装记录 | [ColdInstallDiagnostics](../app/src/main/java/com/deepseekharness/app/core/ColdInstallDiagnostics.java)：追加前读取有界尾部，展示再压缩到约 8000 字符 | 受限 ROM 上仍可读取的安装阶段与失败；不承诺完整原始输出 |

后端鉴权时间和网页就绪时间不能直接称为纯加载测速；用户在 Main 停留、手动进入网页及浏览器观察器触发时机会影响后者。旧 APK 的成功日志不能证明新 APK；没有记录到错误也不等于零崩溃或全部插件通过。

## 修改与验证入口

关联测试为[FirstRunWebStartTest](../app/src/test/java/com/deepseekharness/app/ui/FirstRunWebStartTest.java)、[WebLifecycleResponsivenessTest](../app/src/test/java/com/deepseekharness/app/core/WebLifecycleResponsivenessTest.java)、[StartupRecoveryLifecycleTest](../app/src/test/java/com/deepseekharness/app/core/StartupRecoveryLifecycleTest.java)、[StartupDiagnosticsTest](../app/src/test/java/com/deepseekharness/app/core/StartupDiagnosticsTest.java)、[DiagnosticHistoryTest](../app/src/test/java/com/deepseekharness/app/core/DiagnosticHistoryTest.java)、[DiagnosticExportArchiveTest](../app/src/test/java/com/deepseekharness/app/backup/DiagnosticExportArchiveTest.java)。本文仅核对链接与实现，未重新运行这些测试。
