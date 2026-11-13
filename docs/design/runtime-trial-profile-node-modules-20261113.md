# 运行时 trial 首次失败后重试成功（build363 一加 12 覆盖验收注记）

状态：缺陷记录与源码级初步分析；**docs-only，不改生产代码**。
基线：build363（`d0bc1b0b`，受管 `runtimeId` `5fd17eac…`），`origin/main` `7e1f5060`；内置 DSH `0.2.2-rc.1`。
证据来源：2026-11-13 一加 12（PJD110 / Android 16）官方包 362→363 覆盖验收的 root adb（KSU）只读检查；设备序列号脱敏。工作机（TB321FU 平板）**没有**复现这条路径，本机数据不作为本问题的证据。
范围：只覆盖该设备、该维护路径与 build363 的受管运行时组合；不扩写为全部机型 / 系统版本。

## 0. 结论摘要

- **现象**：受管运行时更新（`DSHA_UPDATE_RUNTIME_V1`）的隔离 trial 第一次以 **proroot** 启动，在鉴权前退出（子进程 exit 1），Node 报 `ENOENT: scandir '<guest>/home/profiles/dsha-recovery-<...>/node_modules'`；同一维护任务内随后一条 trial 成功关闭（`runtimeId=5fd17eac`）。用户侧表现为维护页停留在「更新插件」阶段后继续成功、维护多一次 trial。
- **影响面**：不是数据损坏或回滚；维护最终 `SUCCEEDED`、`runtimeId` 已迁移、维护后 `READY`、`logcat -b crash` 无本包记录。问题集中在 **proroot 试运行的首启路径 + 失败语义/诊断**。
- **初步判断（未定根因）**：失败发生在 dsh 启动早期，对**宿主刚创建、随后经 proroot 暴露给 guest 的试运行 profile `node_modules`** 做目录枚举时。它与仓库已记录的 proroot 类问题——「新进程 / 新目录的目录枚举可能返回 `ENOENT`，而固定路径直读仍可用」——同属一类现象；现有证据不足以确认到具体抛出点或修复方式。
- **证据缺口**：失败回执只有 `IO_FAILURE` + 一行 Node 文本（完整 `output` 未采集）；成功 trial 的 `runtime-health` 回执未读取，`runtimeMode` / `fallbackFrom` 未证实。
- **分级**：主 **P1**（系统化发生、每次更新多一次失败现场与等待、proroot 试运行覆盖实际降级为 proot 回退）；并记 **P2**（错误归类与诊断展示）。理由与反向考虑见 §5。
- **修复顺序**：先补证据与语义（S1），再验证 proroot 下的目录可见性（S2，先诊断后试修），再处理回退语义/诊断（S3），最后才考虑改 dsh 运行时（S4，会改 `runtimeId`）。见 §6。

## 1. 症状与用户可见影响

1. **维护页停留**：用户观察维护页停在「更新插件」阶段（阶段名按用户描述）后继续并最终成功。代码里兼容回退被触发时会显示「proroot 已确认退出，正在使用 proot 进行一次兼容验证…」（`core/EnvironmentMaintenance.java:447-451`）；若用户当时停留在维护页，这与「停留后成功」的观察相容（未确认用户是否看到该文案）。
2. **多一次 trial**：一次维护产生「失败现场 + 成功现场」两条 trial 记录，失败记录按既有轮换策略保留（最近 5 条失败，`backup/RuntimeTrialRecords.java:14-15, 70-71`）。
3. **诊断页残留“需要关注”**：`RuntimeTrial.latestFailure()` 只要存在已闭合的失败记录就返回非空，诊断页据此显示「最近隔离运行试验：失败证据已保留 / NEEDS_ATTENTION」（`core/DiagnosticRepository.java:540-553`）；错误日志摘要同时记录 `runtimeTrialFailureRecorded=true`（`core/ErrorLogRepository.java:325-327`）。所以**维护已成功之后，诊断仍可能提示有 trial 失败**。
4. **无数据丢失证据**：失败 trial 使用隔离 payload；收尾确认 guest 退出后才删除 payload（`runtime/RuntimeTrial.java:420-431`），维护失败时事务走 `recover()`（`core/EnvironmentMaintenance.java:154-166`）。本次没有观察到数据风险，但这不等于逐文件核对。
5. **成功依赖一次回退**：兼容回退只允许一次，且要求没有其它运行任务（`runtime/RuntimeTrial.java:70-81`、`util/WebRuntimeFallback.java:7-20`）；回退也失败时维护整体失败并保留原件（安全，但用户会看到失败）。

## 2. 证据

时间均为设备本地 CST（UTC+8）。以下只保留字段与关键行，不贴整段日志。

| 时间 | trial id | 结果 | 关键字段 |
|---|---|---|---|
| 18:16 | `d716c710-5b33-4b1b-8730-f704723c66f9` | 失败 | `failure.json`：`error=IO_FAILURE`、`runtimeMode=proroot`、子进程 exit 1；Node `ENOENT: scandir '/root/.dsha-runtime-trial-<nonce>/home/profiles/dsha-recovery-<nonce>/node_modules'` |
| 18:17 | `14d9f43d-92db-494d-ac88-aa98e3fee036` | 成功 | `closed` 标记；guest 已确认退出 |
| 18:46 | `3abff5e6-91f0-499b-b73f-be8ea78060f6` | 失败 | 与 18:16 同形态：`IO_FAILURE` + 同一 `ENOENT` 目录枚举，exit 1 |
| 18:46 | `9775ce8f-0c51-47dd-8d9b-e0df55957a95` | 成功 | `closed` 标记；对应 `runtimeId=5fd17eac` |

- **不是 build363 新增**：更早的 trial 也有同形态失败记录——`97ca71f8-d3ac-41dd-b117-166b290a34a5`（2026-11-11）、`fc25c2f6-df3c-4813-9a69-9fe132af1fb6`（06:54）、`db86e1fe-51db-4fb1-94f9-fc8343411d84`（11:50）。
- **同一维护任务内续跑**：18:45:07 创建的数据任务（id `1794566707873`）最终 `SUCCEEDED`，之后没有新任务 id；任务 id 取 `max(System.currentTimeMillis(), id+1)`、每个任务都会换新（`util/BackupTaskState.java:20-28`）。因此两次 trial 的「失败 → 成功」最符合**同一维护任务内的自动兼容回退**，而不是用户手动重试。
- **成功 trial 的实际模式待补证**：成功 trial 本身不落 `runtimeMode`；需要读 `files/runtime-health/<runtimeId>.json` 才能确认 `runtimeMode=proot` / `fallbackFrom=proroot` / `fallbackExitCode=1`。本次验收没有采集该文件。
- **失败发生在鉴权前**：失败回执没有 auth URL；`RuntimeTrial` 在等待鉴权循环里发现子进程退出才抛已闭合的 `TrialExit`（`runtime/RuntimeTrial.java:211-219, 474-484`），这与 `WebRuntimeFallback.shouldRetry` 的「proroot + 未鉴权 + 无插件失败 + 已退出」条件吻合。
- **失败记录能闭合**：若上一次 trial 未确认 guest 退出，下一次 `prepareForNew` 会抛 `TRIAL_RECOVERY_REQUIRED`（`backup/RuntimeTrialRecords.java:30-33`）；实际能继续下一次 trial，说明失败记录的收尾已确认。

## 3. 只读代码定位

### 3.1 试运行 profile 的创建（宿主侧，先创建、后启动 guest）

`app/src/main/java/com/deepseekharness/app/runtime/RuntimeTrial.java`：

| 位置 | 行为 |
|---|---|
| `:97-104` | 生成 trial id / 32 位 nonce / guest 目录 `/root/.dsha-runtime-trial-<nonce>` / profile `dsha-recovery-<nonce 前 16>`，创建 `runtime-trials/<id>/payload` |
| `:127-146` | 写入 `home/profiles/<profile>/package.json`（`dsh.profile.bundles` + `dependencies` 的 `link:` 依赖） |
| `:147-155` | 写入 profile 的 `cordis.patch.yml`、`pnpm-workspace.yaml`、`home/settings.yaml` |
| `:156` | **创建 `home/profiles/<profile>/node_modules`**（失败信息中的路径） |
| `:158-164` | 在 `node_modules` 下建立 `dsha-runtime-check`、`@deepseek-ai` 的相对/绝对符号链接，以及 `plugin/node_modules` 链接 |
| `:165-167` | 创建 `WebProcessManager(proot, payload)` 与工作锁 |
| `:176-203` | 组装 guest 命令（`DSH_HOME=<guest>/home`、`--profile <profile>`）并 `execRootfsForTrial(...)` |
| `:203` | `proot.execRootfsForTrial(command, root, guest, runtimeMode)`：**按所选 runtimeMode 启动** |

`app/src/main/java/com/deepseekharness/app/runtime/RuntimeExecution.java:217-235`：把 `payload` 绑定到 guest 路径（`privateData.getAbsolutePath()` → `guest`），并按 `expectedMode` 选择 proot / proroot。也就是说，失败路径里的 `node_modules` 是宿主刚物化、随即经 proroot bind 暴露给 guest 的**新目录**；不是既有用户 profile 的目录。

### 3.2 启动退出、失败记录与错误归类

| 位置 | 行为 |
|---|---|
| `runtime/RuntimeTrial.java:211-219` | 等鉴权；`ProcessTermination.exited(process)` → `output.exited()`（`TrialExit`） |
| `runtime/RuntimeTrial.java:318-343` | 捕获失败 → `detail.error = BackupErrorCode.from(failure)`（:322）、`detail.output = captured.diagnostics()`（:332）、`exitCode`（:333-337）、写 `failure.json`（:339） |
| `runtime/RuntimeTrial.java:474-484` | `TrialExit` 的消息为 `TRIAL_PROCESS_EXITED: exit=<n>\n<output tail>` |
| `backup/BackupErrorCode.java:10-36` | 只识别 `[A-Z_0-9]{2,100}` 单行码、`InterruptedIOException`、`SecurityException`、`EOFException`、errno 等；**长多行 `TrialExit` 消息匹配不上**，最终兜底为 `IO_FAILURE`（:35）。所以 `error=IO_FAILURE` 不代表文件系统本身的 errno，真实原因只在 `failure.json.output` |
| `runtime/RuntimeTrial.java:344-441` | 收尾：停 guest、确认退出、写 `closed`、删除 payload、轮换旧记录；失败时把收尾异常加为 suppressed，变更不替换主失败 |
| `runtime/RuntimeTrial.java:420-431` | `closeRecord`：`closed` 标记 + `removeOwned(operation, "payload")` + `RuntimeTrialRecords.pruneClosed` |

### 3.3 一次性 proot 兼容回退

- `runtime/RuntimeTrial.java:69-81`：`verifyOnce` 抛 `TrialExit`（`closed=true`）后，若 `WebRuntimeFallback.shouldRetry(requested, false, hadAuth, false, !cancelled, code)` 成立且没有其它任务，就 `report("TRIAL_COMPATIBILITY_RETRY")` 并以 `runtimeMode="proot"` 再跑一次 `verifyOnce`，成功 proof 补 `fallbackFrom` / `fallbackExitCode`（:78-80）。
- `util/WebRuntimeFallback.java:14-19`：仅 `proroot`、未重试、未鉴权、无插件失败、未被取消、有退出码时返回 true。本次失败（proroot、鉴权前、exit 1、无插件失败）正好落在该条件内。
- `core/EnvironmentMaintenance.java:135-153`：更新事务提交时调用 `RuntimeTrial.verify`，成功后 `confirmRuntimeHealth(proof)`；没有对 proof 的 `runtimeMode` 再校验。
- `runtime/RuntimeHostPorts.java:254-296`：冷安装路径对「proroot 选择 + proot 实际成功 + `fallbackFrom=proroot`」有显式校验；更新路径不经过这里。
- `runtime/ManagedRuntimeAssets.java:81-94`：健康回执原样保存 proof（含 `runtimeMode` / `fallbackFrom`）到 `files/runtime-health/<runtimeId>.json`。
- `backup/RuntimeDescriptor.java:144-168`：`healthy()` 只校验 runtimeId、nonce、port、时间戳与各检查布尔，**不检查 `runtimeMode`**。因此「以 proot 回退验证的 proof」仍可作为该 runtimeId 的健康回执（这是既有设计语义，不是本次新增）。

### 3.4 记录轮换与诊断

- `backup/RuntimeTrialRecords.java:30-33`：新 trial 前先轮换已关闭记录；有未关闭现场则要求先恢复。
- `backup/RuntimeTrialRecords.java:38-73`：只轮换已确认关闭的现场，保留最近 3 条成功 + 5 条失败；未关闭的不删除。
- `backup/RuntimeTrialRecords.java:75-107, 118-181`：`latestFailure` / `latestDiagnostic` 只读宿主小 JSON，不读 payload。
- `core/DiagnosticRepository.java:540-553`、`core/ErrorLogRepository.java:325-327`：成功维护后只要旧失败记录还在保留窗口内，诊断页/错误日志仍会显示「失败证据已保留」。
- `runtime/RuntimeInstallationState.java:82-93, 111-122, 140-142`：就绪判断与健康回执读取走 `RuntimeDescriptor` / `ManagedRuntimeAssets`，不校验 `runtimeMode`；它不创建 trial profile，不是本次失败的直接路径。

### 3.5 仓库内已有的 proroot 目录枚举证据（用于假设，不代表已定案）

- `app/src/main/assets/runtime-trial-plugin.js:31`（trial 插件的 fresh-process 检查）：注释明确写「proroot may return ENOENT for directory enumeration in a new process even though a direct record path is readable」；`:84-91` 用 `/usr/local/bin/node` 重新安装 guest 映射来绕开该边界。**本次失败发生在 trial 主 dsh 进程启动早期，尚无同类 workaround。**
- `tools/build-dsh-runtime.py:180-192`：0.2.2-rc.1 的 storage-json 投影注释记录「proroot 在部分 bind/内核组合上不仅会把 `getdents` 的 `d_type` 报成 `DT_UNKNOWN`，刚刚 `mkdir/rename` 的项还可能暂时不出现在上级目录枚举中」。
- `docs/releases/v0.1.6-alpha2-build140.md:7-9` 与 `docs/runtime-data-compatibility-0.2.2-rc.1.md:44-55`：历史上 proroot 目录枚举 / 新记录可见性导致 trial 数据重开失败，已用 `DSHA_PROROOT_DIRENT_FALLBACK_V3` 等投影处理。
- `docs/backup-upgrade-acceptance.md:35`：此前已有一次「proroot 真实失败（139）→ 只重试一次 proot」的通过回执，并明确记录 `runtimeMode=proot`、`fallbackFrom=proroot`、`fallbackExitCode=139`。说明回退是既有允许路径，不能把 proot 成功当成 proroot 原生成功。
- **可能的具体抛出点（待证）**：0.2.2-rc.1 的 `@deepseek-ai/dsh-app-boot` 在 `loadProfile()` 中无条件调用 `removeLinkProjections(dir)`；其 `symlinksUnder(node_modules)` 先 `existsSync()` 再 `readdirSync()`。若 proroot 下 `stat` 可见而目录枚举返回 `ENOENT`，就会以本次形态在鉴权前退出。该包不在本仓库内（锁定运行时），行号随发布构建变化，故只列符号名；需要失败 trial 的完整 `output`（stage 行与堆栈）来确认。

## 4. 初步假设与验证方法

> 以下均为假设，不把未验证的推断写成根因。

### H1（主假设）：proroot 对新目录/目录枚举的可见性异常

宿主动作：新建 `home/profiles/<profile>` 与其中的 `node_modules`，再通过 proroot bind 给 guest。guest 内 dsh 启动早期扫描该 `node_modules` 时得到 `ENOENT`。这能解释：

- 为什么错误是 `scandir` 一个**刚创建**的目录，而不是既有用户目录；
- 为什么固定路径直读（如 `package.json`）能读到，只有目录枚举失败；
- 为什么 proot 回退成功（proot 的 bind/目录语义不同），而维护后正常 proroot 启动可用（正常启动走 `dsh web` 的既有 profile，其 `node_modules` 早已物化，不是本次新建的 trial 目录）。
- 与 §3.5 的既有记录同属「新进程 / 新目录的 proroot 目录枚举返回 ENOENT」类。

反证/边界：现有证据没有失败 trial 的完整 stage 序列；不能排除 dsh 在别的 `readdir` 点失败。`error=IO_FAILURE` 也不提供 errno。

### H2：dsh 启动期的 check-then-readdir 窗口（H1 的具体抛出点）

若 `symlinksUnder()` 的 `existsSync()` 通过而紧接着的 `readdirSync()` 得到 `ENOENT`，则是 proroot 可见性/缓存与宿主物化之间的窗口问题，而不是目录真的不存在。验证需要确认 0.2.2-rc.1 的准确调用栈。

### H3：宿主清理与启动并发（低概率）

`RuntimeTrialRecords.prepareForNew` 会删除已关闭记录的 payload；`fs.removeOwned` 只删除本记录自己的目录。正常路径下新 trial 的 profile 目录是独立的，关闭也只在该 guest 退出确认后发生（`RuntimeTrial.java:420-431`）。要成立需要额外的并发证据（例如同一目录被其它事务或旧 guest 引用），目前没有。

### H4：0.2.2-rc.1 启动期新逻辑回归（与 H2 同源，需对照旧运行时）

若 `removeLinkProjections` / profile 标准化是 0.2.2 新增逻辑，0.2.1 运行时可能不触发。但更早的失败记录（11-11 等）也在 0.2.2-rc.1 上，无法只凭版本区分；需要与旧运行时做同一 payload 的对照实验。

### 验证方法（按成本从低到高）

1. **读成功 trial 的健康回执**：root adb 只读 `files/runtime-health/5fd17eac….json`，确认 `runtimeMode`、`fallbackFrom`、`fallbackExitCode`；同时读失败 trial 的 `failure.json.output` 全文（含 `DSHA_TRIAL_NATIVE_STAGE` 最后阶段与 Node 调用栈）。这两项能直接确认「是否是自动回退」与「失败在 dsh 启动的哪一步」。
2. **重复一次维护（测试包 / 非正式数据）**：观察 proroot trial 是否 100% 失败。若每次维护都复现，按系统化问题处理；若偶发，记录设备状态（可用空间、后台任务、USB/root 状态）与时间。
3. **proroot 下的目录探针**：在同一 payload 上，宿主创建一个一次性 guest 命令，在 proroot 内打印 `lstat` / `existsSync` / `readdirSync` 结果与 `/proc/self/mountinfo` 中的 payload bind；对比宿主侧 `ls`。目标是区分「guest 根本看不到」与「`stat` 可见但枚举失败」。
4. **对照 dsh 版本**：用同一 payload 在 0.2.2-rc.1 与上一锁定运行时上各跑一次，确认是否由 `removeLinkProjections` 一类启动逻辑引入。
5. **只在实验分支验证试修**：宿主在创建节点后对目录/父目录做显式同步（`symlink` 当前不做 `syncDirectory`），或在启动 dsh 前用 guest 只读 `ls` 预热；如果失败消失才考虑成为修复。**没有复现前不要直接改生产逻辑。**

## 5. 分级建议

### P1（主）：proroot 试运行的系统性降级 + 每次更新多一次失败现场

理由：

1. 本次设备两轮维护都出现「proroot 失败 → 回退成功」，且更早三条记录同形态；至少在本机不是偶发，**本机的每次受管运行时更新都会多一次失败 trial、多一段等待**，并在诊断里留下失败记录。
2. 契约要求 trial 证明**所选**运行时；回退是被允许的，但连续发生意味着在本机这两轮里 proroot 路径实际上**没有被 trial 验证**，按代码路径成功 proof 会记录 `runtimeMode=proot` / `fallbackFrom=proroot`（待补证）。如果 proroot 的选择还要作为默认运行模式，这是一个真实的验证覆盖缺口。
3. 回退只允许一次且要求没有其它运行任务；若回退也失败，维护会失败回滚（安全但用户可见）。把首因留在「未知」会持续把这种失败概率留给每次更新。
4. 失败原因目前被归为 `IO_FAILURE`，诊断页把成功维护后的旧失败记录显示为「失败证据已保留 / NEEDS_ATTENTION」；这属于合并门槛里的「错误语义 / 诊断」问题，不能长期不记录。

### P2（同批记录）：错误归类与诊断展示

- `BackupErrorCode.from` 丢弃 `TrialExit` 的真实阶段与 stderr，只给 `IO_FAILURE`；失败记录没有 stage 字段。
- 成功维护后 `latestFailure` 仍非空，诊断页与错误日志显示「失败证据已保留」；文案「运行时更新通过后不会生成失败记录」（`DiagnosticRepository.java:549-552`）在本场景已不准确。

### 反向考虑（什么情况下可降为 P2）

- 兼容回退是既有设计，最终成功、无数据损坏、正常 proroot 启动可用（18:20 实测 5.1s 鉴权）；如果维护者认定「proroot 首启回退一次」是预期工作方式，且本问题只在单台设备出现，可降为 P2 跟进修，但**在宣称 proroot trial 覆盖前**仍应把原因解释清楚。

### 重试是否总是成功 / 等待与数据风险

- 本次证据只有 2/2 轮在同一维护任务内成功；更早的失败记录没有对应的成功配对信息，**不能宣称“重试总是成功”**。
- 回退是一次性的（`WebRuntimeFallback.shouldRetry` 的 `!retried`）；回退再失败时维护失败并保留原件/回滚（`EnvironmentMaintenance.java:154-166`）。
- 用户等待：失败 trial 在鉴权前退出，本身较快；主要成本是成功 trial 的完整时长与一次失败记录/诊断噪音（本次两轮约多 1 分钟内）。
- 数据风险：未观察到。失败 trial 的 payload 是隔离目录，且 guest 退出确认后才删除（`RuntimeTrial.java:420-431`）；维护失败走 `transaction.recover()`。但本次没有逐文件核对，不能宣称“零风险”。

## 6. 修复切片建议（docs-only 建议，未实现）

| 切片 | 最小改动 | 测试 / 验证 | runtimeId / 维护页影响 | 回退 |
|---|---|---|---|---|
| **S1（P1，先做）** 失败语义与证据 | `RuntimeTrial` 从已捕获的 `output` 提取最后一个 `DSHA_TRIAL_NATIVE_STAGE` 与首个 Node 错误行，写入 `failure.json` 的 `stage` / `reason`，并映射成专属错误码（如 `TRIAL_PROFILE_ENUMERATION_IO`）；维护成功 detail 在 proof 带 `fallbackFrom` 时注明「本次以兼容方式验证」 | 针对 `BackupErrorCode` / `RuntimeTrial` 的定向单测（纯字符串分类可抽到 `util/`，配单测）；设备上重跑维护确认失败记录可读 | 只改宿主 Java，**runtimeId 不变、零维护页** | 回退代码即可；不影响数据 |
| **S2（P1，试验）** proroot 目录可见性 | 先在维护里加只读 preflight（宿主/guest 各打印 profile 与 `node_modules` 的 stat/readdir）；确认 H1/H2 后，尝试在物化末尾对 `node_modules` 及父目录显式同步（`AndroidBackupFileSystem.symlink` 目前未 `syncDirectory`），或在起 dsh 前做一次 guest 只读枚举预热 | 复现设备上 A/B：有 preflight / 无 preflight、预热 / 不预热；至少两轮维护；失败仍在则不能把预热带进生产 | 宿主/资产改动，**runtimeId 不变、零维护页**（以 `prepare-runtime-descriptor.py --check` 为准） | 回退代码；无数据格式变化 |
| **S3（P2）** 回退语义与诊断 | 健康回执保留 `fallbackFrom`（已保留）并在维护结果/诊断页区分「兼容回退验证」与「原生验证」；修正 `DiagnosticRepository` 关于「更新通过后不会生成失败记录」的文案，明确已闭合失败记录是历史证据 | `RuntimeDescriptor` / 诊断文案单测；设备上确认成功维护后诊断显示可解释 | 只改宿主与文案，**runtimeId 不变**；旧健康回执保持可读（只加字段/文案，不改 `healthy()` 的必填项） | 回退代码；旧回执兼容 |
| **S4（P2，后续）** 改 dsh 运行时启动逻辑 | 在锁定运行时补丁里让 `removeLinkProjections` 对 `ENOENT` 容错，或避免对刚物化的 `node_modules` 做无谓枚举；需要重新生成锁定运行时与受管投影 | 重跑 `tools/build-dsh-runtime.py` 相关投影测试、受管运行时摘要检查；真机复验 proroot trial | **runtimeId 会变化**，覆盖升级后多一次维护页；需要 fixed-inputs / 发布流程配合 | 用既有运行时事务回切上一受管 runtime（`EnvironmentMaintenance.rollbackRuntime`） |

建议顺序：S1 先让下次现场可诊断；S2 以实验确认是否宿主物化/可见性问题；只有 S2 证明必须改 dsh 时才做 S4。S1/S2 都不应改变 trial 的接受条件或回退语义。

## 7. 未验证 / 限制

- 未在另一台设备复现；工作机（TB321FU）是不同机型，且本机不覆盖安装正式包，未做本路径实测。
- 失败回执的完整 `output`（stage 序列、Node 调用栈）与成功 trial 的 `runtime-health` 未采集，根因未定案。
- 只覆盖一加 12 / Android 16 / build363 / 0.2.2-rc.1 这一组合；未验证 proot 模式是否永远成功（只有两轮回退成功）。
- 未做数据格式、逐文件数据完整性核对；本文不宣称数据风险为零。
- 未修改任何生产代码；所有行号以 `origin/main` `7e1f5060` 的树为准，后续提交可能移动。

## 8. 复核命令（脱敏）

```bash
adb -s <serial> shell su -c 'ls /data/data/io.github.sunsetrne.dsha/files/runtime-trials'
adb -s <serial> shell su -c 'cat /data/data/io.github.sunsetrne.dsha/files/runtime-trials/<trial-id>/failure.json'
adb -s <serial> shell su -c 'ls /data/data/io.github.sunsetrne.dsha/files/runtime-trials/<trial-id>'
adb -s <serial> shell su -c 'cat /data/data/io.github.sunsetrne.dsha/files/runtime-health/5fd17eacd0597e478bb52b01f42e7659f084f90b30cf98566deb62cdac36a5fe.json'
adb -s <serial> shell su -c 'cat /data/data/io.github.sunsetrne.dsha/shared_prefs/dsha-data-task.xml'
adb -s <serial> shell su -c 'grep -i "runtime-trial\|TRIAL_" /data/data/io.github.sunsetrne.dsha/files/diagnostics.log 2>/dev/null' # 文件名按实际诊断导出
```

> 说明：健康回执 JSON 里的 `runtimeMode` / `fallbackFrom` / `fallbackExitCode` 是判定「自动回退」的直接证据；`failure.json.output` 的 stage 行是定位 dsh 启动阶段的直接证据。两者都属应用私有数据，按仓库规则不进入仓库。
