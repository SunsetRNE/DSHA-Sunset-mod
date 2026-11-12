# 评审 D：旧版覆盖更新兼容与迁移路径（草稿）

> 基线 build359 / `0.2.1-rc.5` / `io.github.sunsetrne.dsha` / 单一 Standard。只读源码与文档，未构建、未安装、未跑测试；结论以 `file:line` 为据。
> 主源：`docs/overwrite-upgrade-and-maintenance.md`（I1–I10）、`util/{EnvironmentIdentity,RuntimeUpgradeState,MaintenanceFooterPolicy,WebProofStop}.java`、`core/{EnvironmentAccess,MaintenancePreStop,WebLifecycleController}.java`、`runtime/{WebProcessManager,RuntimeInstallationState}.java`、`backup/RuntimeDescriptor.java`、`assets/runtime-descriptor.json`、`HttpShellService.java:2623`。

## 一、结论摘要

1. **I1–I10 可全部原样存活，前提是把它们当契约而非实现。** I1/I2/I3/I4/I6/I7 的判定体是纯函数（只吃布尔与字符串），逐行移植 Kotlin 即可；I5/I9/I10 依赖进程观察、文件证据与 UI 渲染，必须重写，判据不是"代码等价"而是"**证据链等价**"：同一身份字段、同一双次核验、同一失败文案。
2. **新壳首启必然"不是当前候选"，是设计而非缺陷。** 描述符 `inputs` 有 205 个键，`launcherInputs` 直接含 `HttpShellService.java`、`jniLibs/**/libproot.so` 等源码与 ELF 摘要；Kotlin/Rust 化必改它们 → `runtimeId`（现 `4d55973d…`）变 → `latest` 为假 → I1/I6 触发补丁级维护。
3. **最大风险不是迁移，而是把"换包名/换签名/清数据"当捷径。** 覆盖安装只认「同 applicationId + 签名集合一致 + 更大 versionCode」，任一被改，老用户得到的不是升级而是第二个应用或被迫卸载重装。

## 二、不变式保留表（I1–I10）

分类：**纯判定**＝可直接移植，只需单测；**需重写**＝依赖 Android API/`/proc`/序列化，实现重建但证据语义不变。

| # | 不变式（压缩） | 现实现 | 新壳负责模块 | 分类 |
|---|---|---|---|---|
| I1 | 已装运行时非当前候选 ⇒ 必须维护（即使兼容健康） | `EnvironmentAccess:60-85`、`EnvironmentIdentity.shouldBlockRuntimeStart:61-73` | update-gate（Kotlin） | 纯判定 |
| I2 | `isEnvironmentReady()` ≠ 无事可做 | `RuntimeInstallationState:82-93`、`RuntimeUpgradeState.upToDate:18` | host-ui 状态投影 | 纯判定 |
| I3 | 覆盖后必须整屏维护页 + 一键更新 | `RuntimeUpgradeState.openMaintenancePage:34`、`ExtractActivity`、`MaintenanceFooterPolicy:66` | shell-ui | 判定纯，渲染需重写 |
| I4 | 未确认旧 Web 只拦自动开始，不拦入口 | `EnvironmentIdentity.shouldAutoStart:43-58` | update-gate + 会话证据 | 纯判定 |
| I5 | "旧树+新 APK" 必须拒绝 Web 启动 | `WebLifecycleController.requestStart` 的 `!ready \|\| requiresMaintenance`（文档 §4） | web-supervisor（Rust）+ admission | 需重写 |
| I6 | UI-only 不重建；受管 inputs 变化必须补丁级维护 | `RuntimeDescriptor.latest:122`、`compatible:128` | runtime-install（摘要可下放 Rust） | 纯判定 |
| I7 | 同候选只自动尝试一次；受限入口不写就绪 | `EnvironmentIdentity.shouldAutoStart:27-35`、`EnvironmentAccess.candidateAttemptKey:43-47` | update-gate + limitedEntry | 纯判定（`lastUpdateTime` 需 Kotlin） |
| I8 | 插件只依赖自有属性+实测几何 | `tools/test-mobile-gear-contract.mjs`、`DSHA_GEAR_FIT_V1` | 插件字节与补丁链 | 需整组重跑 |
| I9 | 维护动作自行完全停止；只许一次身份核验强制；不按端口/名称/进程组 | `MaintenancePreStop:52-67`（45s/15s）、`WebProofStop.run:54-94` | maintenance-pre-stop（Kotlin 顺序）+ proc-identity（Rust） | 顺序纯；观察/信号需重写 |
| I10 | 弃旧版：guest→旧启动器各自双次核验，任一不符不发信号 | `WebLifecycleController.previousBuildInstance:1644`、`WebProcessManager.prepareOverwriteRetire:271`、`observeRecordedLauncher:299`、`WebProofStop.runRetiringLauncher:103` | 同上，证据改读 `StoredWebSession.Record.proof` | 判定纯；其余需重写 |

**移植纪律**：I1/I2/I6 的输入必须仍是「已装描述符 + 期望描述符 + 健康回执 + attempted 键 + busy/pending」，不许用"启动成功"或"版本号相等"替代。任一处自己用 `isEnvironmentReady()` 代替"已是最新"，就会重演 build323→324 事故。

## 三、包名 / 签名 / versionCode / 变体约束

| 项 | 现值 | 新壳硬约束 | 违反后果 |
|---|---|---|---|
| applicationId | `io.github.sunsetrne.dsha`（`build.gradle:86`，`:240` 强制等于身份文件） | 不得改 | **换包名不是升级**：新包是第二个应用，`files/linux`、`noBackupFilesDir`、Keystore 别名均不共享；只能靠 v5 导出/导入，且桥 token、设备授权、本机会话记录不随归档生效 |
| 签名 | Mod 自签 `a4b0413c…5d5d`，v1+v2+v3（`build.gradle:147-149`、`verifyPublishCertificate:224-243`） | 同证书、同开三 scheme | **换签名不是升级**：系统 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`；应用内更新器也拒（`UpdatePolicy` 要求签名集合完全一致）。唯一出路是卸载重装＝丢 Keystore 密钥、会话记录、`noBackup` 数据 |
| versionCode | 359（`build.gradle:96`、`ci/release-identity.json`） | 单调递增；新壳首发建议 360 | 相等/更小被系统拒装；回滚只能发"更高 code 的旧代码" |
| versionName | `0.2.1-rc.5-20261111.1200-dsh0.2.2-rc.1` | 语义不变 | 仅诊断用，不得据文件名推数据格式 |
| 变体 | `standard` + `.test`/`.debug`/`.dev` 互斥，测试包必须 unsigned + 测试证书（`:41-49,111-121,226-227`） | 保持互斥与"测试包禁正式证书" | 会产出可覆盖正式包的测试签名包 |

补充：`appIdentity()` = `包名:lastUpdateTime:VERSION_NAME`（`StoredWebSession:57-65`），覆盖安装后必变 —— T91 的前提，新壳必须继续写入，不能改成随机安装 ID。

## 四、数据兼容矩阵

总规则：**双格式读、单格式写、未知数据保留并报告**；新字段走旁路文件或可选字段，不抬既有容器版本号。

| 数据 | 现状 | 新壳首启策略 | 旧版读新版 | 新版读旧版 |
|---|---|---|---|---|
| 运行时描述符 | `assets/runtime-descriptor.json` + `files/base/.runtime-descriptor.json`，`version=1` 严格校验（`RuntimeDescriptor:20-49`） | 主文件仍写 `version=1`，新增字段放 `runtime-descriptor.v2.json` | 主文件升 v2 → 旧版抛 `RUNTIME_DESCRIPTOR` → 判"未就绪" → 误触发重建，**禁止** | 必须读 v1 |
| v5 备份/恢复 | `DSHA-data-v5-<UUID>.tar.gz` + `DSHADATA` v5 + 清单；`LegacyBackupImporter` 读 v1–v4 | 读 v5 + legacy；只写 v5 | `DshVersionPolicy` 对更高写入版本返回 `INSTALLED_NEWER` → 强制维护、拒绝自动预检，不静默覆盖 | 走 legacy 导入 + 同一预检；未知 scope 拒绝而非猜全量 |
| 格式声明 | `dataRead=['dsh-0.2.1-rc.5','dsh-0.2.2-rc.1']`、`dataWrite='dsh-0.2.2-rc.1'` | 首版**不得**提升 `dataWrite`（否则旧版必拒读整棵树） | fail-closed 进维护页，可接受 | 新版必须读 `dsh-0.2.1-rc.5` |
| 配置快照 | `ConfigurationSnapshots`：rootfs 外 3 健康 + 3 修复前 | 双读格式标记；不认识的快照原样保留并报告 | 忽略新目录即可 | 按内容识别，不按目录名猜 |
| 插件状态 | `builtin-plugins.json` 受管证明 + `.disabled`/`.enabled` marker + profile manifest | 系统插件字节**必须由新 APK 精确证明重建**；用户插件源码不动；`.disabled` 意图优先 | 忽略新 marker | 读旧 marker，语义保持 |
| `StoredWebSession` | `noBackupFilesDir/web-session.enc`，`schema=1`，KeyVault 别名 `dsha_web_session`，AES/GCM AndroidKeyStore | 双读 `schema=1`；新字段可选且回写仍带完整 schema 1；**别名与算法不得改** | 升 `schema=2` → 旧版抛 `WEB_SESSION_SCHEMA` → 保守 UNKNOWN（安全但丢后台恢复） | 必须读 `schema=1`，否则每次覆盖都"找不到旧会话"，T91 失效 |
| 维护事务日志 | `plugin-install-operations/` + `plan.json format 1–3` + `committed`/`rolled-back`（`PluginInstallJournals:20-42`） | 只读旧日志进维护门禁；新格式另开目录/旁路 | `format>3` → 旧版抛 `PLUGIN_JOURNAL_PLAN` fail-closed，灰度期**不可写**新格式 | 读 1–3；pending 一律进维护页、不自动重放 |
| attempted 键 | `dsha_environment_upgrade`/`attempted_identity` | 键名语义不变，只增不删 | 忽略新键 | 读旧键，否则违反 I7 |
| API Key | KeyVault 别名 `dsh_api_key`，AES/GCM | 别名与格式不变 | — | 解不开即 `NEEDS_ATTENTION`，不得新建替代密钥 |

## 五、首次启动与维护页

1. 覆盖安装 → `lastUpdateTime` 变 → `candidateAttemptKey` 变（`EnvironmentAccess:43-47`），且 `inputs` 变使 `latest` 为假。
2. `requiresMaintenanceBeforeStart` 为真 → 主界面门禁打开 `ExtractActivity` 整屏维护页（I3）。
3. 同一判定让 `requestStart` 返回 `REJECTED`（I5）→ 看门狗不会用"兼容但过期"的旧树拉起 Web。**主界面 Web 空白是门禁的正常后果，不是 WebView/网络故障。**
4. 旧 Java Web 仍存活 → 记录 `app` ≠ 新 `appIdentity()` → `hasUnresolvedWebSession()` 真 → `shouldAutoStart` 为 false（I4）→ 维护不自动开始。

正确产品行为（缺一不可）：① 仍开整屏维护页；② 文案写明"已装入的运行时仍是上一版，本次内置补丁尚未落地"；③ 主操作为"更新运行时"（`MaintenanceFooterPolicy` 必须传 `ready=false`，否则退化为 `DEMOTED`+`PRIMARY` 即事故形态）；④ 允许进受限主界面看日志，但不写就绪标记、Web/插件仍受门禁。

新壳责任划分：Rust 只提供三份只读事实（已装描述符、期望描述符、健康回执）+ 会话观察结论；开不开维护页、按钮主次、受限入口身份全由 Kotlin 契约判定。用户手点后由维护任务自行完成完全停止（I9/I10）。

## 六、T91「覆盖更新弃旧版」的等价实现

证据链（逐字段保留）：`Record.proof` = boot、generation、instance、nonce、`webPid/webStarted/webCommand`、`launcherPid/launcherStarted/launcherCommand`（`WebSessionProof.Saved`），加当前 UID、`WebProcSel.maySignalWeb`、`trialProfile` 为空。`prepareOverwriteRetire` 对 guest 与 launcher 各连读两次，任一 UNKNOWN 即抛 `RECORDED_SESSION_UNCONFIRMED` 不发信号；`runRetiringLauncher` 严格 guest 确认退出后才碰 launcher，SIGTERM → 3s（50ms 轮询）→ 复核 → SIGKILL。

| 条件 | 要求 |
|---|---|
| 记录读取 | 仍由 Kotlin 解密（Keystore 只在 Android 侧），`Saved` 结构经 FFI 交 Rust；Rust 不得自解析 `web-session.enc` |
| 身份原语 | Rust 读 `/proc/<pid>/stat` starttime + `cmdline` + uid，与 Java 逐字段一致；"读不了"≠"已退出"，只有 boot 变化、PID 退休、starttime/cmdline/uid 变化可作 EXITED/UNKNOWN |
| 双次核验 | 两次观察同窗口内完成，结论不同即 UNKNOWN；维护与恢复共用同一实现 |
| 顺序 | guest 先、launcher 后；guest 未确认退出绝不 kill launcher |
| 信号语义 | 保留 `SENT/GONE/FOREIGN_UID/DENIED` 四态；`DENIED` 保留现场不重试 |
| 明令禁止 | 端口反查、进程名/pgid、`killpg`、`/proc` 全表扫描、pidfd 批量收割 |
| 差分回归 | 用现有 `WebOverwriteRetireTest`/`WebProofStopTest`/`WebSessionProofTest` 夹具做「Java vs Rust」同输入对比（PID 复用、出生时间变化、命令行变化、boot 变化、他人 UID、记录不可读 6 类），结论必须一致 |
| 失败出口 | 与原实现同一出口：保留原环境 + `MaintenancePreStop` 既有可重试文案，不得放宽为"按端口杀" |

## 七、桥协议与插件契约

| 契约 | 现状 | 新壳必须保持 |
|---|---|---|
| `BRIDGE_PROTOCOL` | **实测 3**（`HttpShellService.java:2623`）；`/app/version` 返回 protocol/app 版本/包名（`:2636`），帮助文本明写"compare protocol with >=" | 只增不减；3→4 仅在有新能力时；客户端 `>=` 检测，禁 `==`；端点集合、`x-token` 头、`/root/.dsh/.bridge_headers` 位置不变。注：`AGENTS.md` 写 2 已过期，应顺手修正 |
| 桥安全语义 | 凭据区拒绝（`BridgePathPolicy` 双重核验）、查询串预算 ≥8192 | Rust 实现逐条保留，不因"内部调用"放宽 token 门控 |
| 系统插件重建 | 由当前签名 APK 的受管证明重建（`ManagedPackageProof`/`SystemPluginProjection`） | 证明表基于新 APK 实际字节重算，不复用旧树 `node_modules` 里的系统插件字节 |
| `.disabled` 意图 | 存在即只清自己拥有的启用记录 | 只读意图，不做"自动恢复默认启用" |
| 未提交插件事务 | `PluginInstallJournals.pending` → `HostMaintenancePending` 的 `plugin-install` 域 | pending 即进维护页、不自动重放，不得绕过该域 |
| 插件 UI 依赖 | I8：只依赖自有属性 + 实测几何 | 新壳若改 Web 容器/注入 DOM/加原生指示条，整组重跑 `tools/test-mobile-gear-contract.mjs`；不回放已退役补丁 |

## 八、灰度与回滚

| 阶段 | 内容 | 退出判据 | 回滚点 |
|---|---|---|---|
| 0 壳内并行 | 同 APK 内新壳与旧 Java 路径共存，开关选择；code 360 | I1–I10 回归全绿；Java↔Kotlin 判定差分一致；覆盖安装真机 7 项（文档 §真机验收）通过；`runtimeId` 前后值符合预期 | 切回旧路径，**不清数据、不降 code** |
| 1 默认新壳、保留旧路径 | 默认走新壳，旧路径只在显式降级时可用（非自动重放）；code 361–362 | 连续两个候选真机覆盖：无白屏、无侧栏重复、插件默认启用、维护页成功率 100%、无新增"强制停止失败" | 发更高 code 的旧实现 rollback build；数据仍 v1 双向读 |
| 2 移除 Java 实现 | 删实现但保留旧格式**读**能力；code 363+ | 灰度窗口零回退；旧格式读路径仍有测试 | 阶段 1 的 rollback build 仍可覆盖（同包名同签名）；阶段 2 写出的格式必须能被阶段 1 读 |

**不允许的捷径**：换包名、换签名、清数据/`pm clear`/删 `noBackupFilesDir`、"一次性迁移后删旧格式"、把"卸载重装"当升级、用"禁用内置插件"掩盖白屏、放宽 I5 门禁、按端口/名称杀进程绕过停止屏障。

## 九、破坏性变更清单

| 变更 | 影响 | 可否接受 |
|---|---|---|
| 新壳首发触发一次补丁级维护 | 多一次点按；`/root`、`/opt`、工作区不动 | **接受**（I6 设计） |
| `launcherContract` V2→V3 | 旧树 `compatible()` 假 → 必须维护；受管更新可交付 | 接受（若确需新系统库） |
| `baseVersion=10`→`11` | **整树重建**：`/opt` 按数据复制（实测 3.5GB / 48,294 条目），超 `ENTRIES=100000` 会失败 | **不接受**随架构切换默认发生 |
| `bridgeProtocol` 3→4 | 旧客户端 `>=3` 仍通过；`==3` 判断失效 | 接受（须先确认仓库无 `==`） |
| 插件 journal `format=4` | 旧版 fail-closed | 灰度期**不接受**，阶段 2 才可 |
| `web-session.enc` `schema=2` | 旧版读失败 → 保守 UNKNOWN，丢后台恢复 | 灰度期**不接受**，改用可选字段 |
| 描述符主文件 `version=2` | 旧版构造失败 → 误判未就绪 → 可能不必要重建 | **不接受**，用旁路文件 |
| 改 Keystore 别名/算法 | 旧密文永久不可读 | **绝不接受** |
| 改动态端口 `--port 0` 策略 | 破坏鉴权/停止证明与 LAN 前提 | **不接受** |
| 提升 `dataWrite` 到新格式 id | 旧版拒读新版数据（进维护页） | 接受（既有策略）；新版必须能读 `0.2.1-rc.5`/`0.2.2-rc.1` |
| 受管路径变化 | 替换 guest 受管文件；误放用户文件会被覆盖 | 接受，保持"`/usr/local/share/dsha/**` 不放用户文件" |

## 十、给 Lead 的一句话结论

**I1–I10 可以一条不丢地活到新壳：判定逻辑原样移植并做 Java↔Kotlin/Rust 差分回归，进程与文件实现重写但证据链逐字段等价；包名、签名、versionCode 三者不许动，数据只许"双读单写 + 新格式走旁路"，灰度按"壳内并行 → 默认新壳 → 移除 Java"三段推进，回滚永远靠更高 versionCode 的旧代码，绝不靠清数据。**
