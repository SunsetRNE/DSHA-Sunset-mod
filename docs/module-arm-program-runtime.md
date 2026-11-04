# 模块受管 ARM 程序运行入口 —— 契约、实现现状与验收边界

> 状态：**已实现（x86_64 模拟器验收；ARM 真机未验）**，2026-11-02。
> 本文 2026-11-01 的版本是设计前置文档；五项前置契约现已全部落地：
> 逐程序 SHA-256、ELF ABI 门禁、独立逐程序授权、应用私有目录闭环、容器内有界执行与退出证明。
> 边界仍然不变：模块程序只能随模块包导入并通过校验，不能在网络下载；不是任意代码执行，
> 不是安全隔离；x86_64 静态 ELF 已在模拟器跑通，aarch64 真机程序尚未验收。

## 0. 实现落点（2026-11-02）

| 契约 | 实现 |
|---|---|
| `module.program` 扩展点 | `ModuleCard.Program` 解析 `provides.module.program.programs`，支持 `id/file/sha256/abi/entry/args/timeout`，`entry` 必须等于 `file` |
| 逐程序摘要 | `ModuleProgram.validate` 导入时与每次运行前重算 SHA-256（上限 64MB） |
| ABI 门禁 | 校验 ELF magic、64 位 class、`machine`（AArch64 `0xb7` / x86_64 `0x3e`）并对照设备 `SUPPORTED_ABIS` |
| 独立授权 | `ModuleProgramAuth` 用 `<cardId>.<programId>` 独立键，默认关闭；模块中心可单独撤销；移除卡片时 `revokeAll` |
| 私有目录闭环 | `ModuleCardStore` 只接受卡片声明的程序素材；整目录事务、NOFOLLOW/lstat、旧版备份随程序文件一起迁移 |
| 有界执行 | `ModuleProgramRunner` 落 guest `/run/dsha-module-program/<cardId>`；`RuntimeExecution.collectBound` 只读 bind + 固定 argv + `BoundedProcessRunner` 限时限长 + `BoundedGuestLifetime` 退出证明 |
| 包导入 | `ModuleImport.importPackage` 只接受扁平 ZIP；程序/图片/签名随包导入，路径穿越与重复文件名拒绝 |
| UI | 模块中心列出程序文件名、摘要短形、ABI 与独立授权/撤销按钮 |

模拟器证据：`build/verify/module-platform/14-program-permission.png`、`15-program-result.png`；
运行输出 `MODULE_PROGRAM_OK argc=2 arg=demo`。ARM 真机未验证，不得扩写为真机通过。

---

## 以下为 2026-11-01 设计前置分析（保留作历史与验收清单）

## 1. 现有接缝（已核实，逐条给出代码位置）

### 1.1 宿主 → 容器执行缝（命令级，已存在并被模块使用）

- `ProotBootstrap.execRootfs(String, Map)`（`app/src/main/java/com/deepseekharness/app/runtime/ProotBootstrap.java:293-300`）
  → `RuntimeExecution.exec`（`runtime/RuntimeExecution.java:182-190`）：先 `requireReady()`，
  再选运行时（proot/proroot/root，`RuntimeExecution.runtime()` :54-91），准备工具，交给 `RuntimeLauncher` 启动。
- 模块命令执行已走这条缝：`ModuleExec.run`（`module/ModuleExec.java:65-106`）调用
  `controller.proot().execRootfs("bash -lc " + ShellQuote.arg(trimmed))`（ModuleExec.java:78）。
- 授权门：每张卡片单独的布尔授权（`ModuleExec.isAllowed/setAllowed/revoke`，ModuleExec.java:46-62），
  由模块中心授予/撤销；能力总线所有写能力都先过这道门（`ModuleServices.call`，`module/ModuleServices.java:211-231`）。

### 1.2 有界收集与 guest 退出证据缝（宿主代码用，模块未用）

- `RuntimeExecution.collect`（`runtime/RuntimeExecution.java:283-319`）：
  `RuntimeWorkPort.begin` 起工作票据 → `BoundedProcessRunner.collect(process, timeoutMs, 256KB, Compat::destroy)`
  限时限长收集 → `BoundedGuestLifetime.finish(…)`（`runtime/BoundedGuestLifetime.java:10`）收口退出证据。
- proroot 有界会话：`RuntimeExecution.bounded`（RuntimeExecution.java:192-212）先
  `IsolatedInstallProcess.bundledLauncher(context)` 非空检查，再
  `BoundedGuestSessions.begin(context.getFilesDir())` 登记 guest lease（`runtime/BoundedGuestSessions.java:75`），
  失败路径 `record.uncertain()`。
- 绑定缝：`LaunchSpec.Builder.bind(host, guest)`（`runtime/LaunchSpec.java:101`），
  guest 路径静态校验（必须以 `/` 开头、拒绝 `/../`、拒绝 NUL，LaunchSpec.java:30-37）；
  `RuntimeTrial` 用严格目录检查 + 绑定隔离试运行（RuntimeExecution.java:227-246）。

### 1.3 模块资产缝（只认图片，程序文件进不来——这是刻意的）

- 卡片素材名校验只接受 `png/jpg/jpeg/webp` 文件名（`ModuleCard.requireImageName`，`module/ModuleCard.java:723-726`）；
  `assetNames()` 只收集图片名（ModuleCard.java:729-750）。
- 存储层素材只接受卡片声明过的图片、单图上限 4MB（`ModuleCardStore.importCard`，`module/ModuleCardStore.java:207-216`；`MAX_IMAGE_BYTES` :37）。
- 结论：**今天没有任何路径能把一个 ELF 送进模块目录**。要开程序能力，第一步必须是新增
  独立的程序清单与落盘路径，而不是放宽图片校验。

### 1.4 ABI / W^X 现状

- 代码里唯一的 ABI 判断在更新策略（`util/UpdatePolicy.java:52`，`"arm64-v8a".equals(abi)`）；
  模块体系没有 ABI 字段、没有 ELF 头校验。
- 项目铁律（AGENTS.md「运行时与更新」）：proot 必须从 `nativeLibraryDir` 执行，
  **不能移到 filesDir 绕过 Android W^X**。因此「宿主直接 exec 模块目录里的 ELF」
  在 targetSdk 30+（standard minSdk 30）上会被 W^X 挡住，也不允许绕。
  唯一可行路线是**容器内**执行：程序文件落应用私有目录 → 以只读绑定进 rootfs 固定 guest 路径 →
  走 proot 既有加载链执行。这与 dsh/node 的运行方式同源。

## 2. 五项前置契约的差距清单（逐项核对结果）

| # | 前置 | 现状 | 差距 |
|---|---|---|---|
| 1 | **逐程序摘要** | 卡片摘要只覆盖 card.json 原始字节（`ModuleCard.digestOf`，ModuleCard.java:692-710） | 没有「程序文件 SHA-256 在导入时记录、每次运行前重算比对」的契约；也没有摘要不符时的拒绝路径 |
| 2 | **ABI 门禁** | 无模块 ABI 字段；无 ELF magic/class/machine 校验 | 需要在导入与运行前各验一次：ELF 魔数 `7f 45 4c 46`、64 位（class=2）、`machine=0xb7`（AArch64），并对照设备 `SUPPORTED_ABIS` |
| 3 | **显式逐模块授权** | 只有一个笼统的卡片 exec 授权（ModuleExec.java:46-62） | 「运行原生程序」必须是与命令执行分开的、默认关闭、单独弹窗、可单独撤销的授权；不能复用命令授权连坐 |
| 4 | **应用私有目录闭环** | 模块目录已有 NOFOLLOW/lstat 防护与整目录事务（`ModuleCardStore.isSymlink` :565-573、`deleteTree` :576-586、`importCard` :200-280） | 但程序文件的落盘子路径（如 `modules/<id>/programs/`）、大小上限、导入时摘要落盘记录、运行前 NOFOLLOW 复核均不存在 |
| 5 | **退出证明** | `ModuleExec.run` 是裸 `Process` + 手工 `destroyForcibly`（ModuleExec.java:93-117），不登记 `BoundedGuestSessions`，也不走 `BoundedGuestLifetime.finish`；proroot 下经 `execRootfs`→`execution.exec`→`start`（非 isolated 路径）运行 | 模块可用执行路径**没有** guest lease 与退出证据；开程序能力必须先换成有界收集路径（见 §3），命令执行路径本身也留有同样的缺口 |

另有一条如实记录：`ModuleExec.run` 的授权挡的是「未授权模块静默执行」，
容器本身是 danger-full-access（ModuleExec.java:22-25 的注释），它不是安全隔离；
程序能力更不能以「沙箱」为卖点写进文档。

## 3. 安全下一步（实现时按此顺序，缺一步不开下一层）

1. **契约先行**：卡片新增独立 provides surface（例如 `module.program`），逐程序声明
   `{ "file": 文件名, "sha256": 64 位十六进制, "abi": "arm64-v8a", "entry": "固定 argv 模板" }`；
   只接受文件名（与 `requireImageName` 同款正则思路），拒绝路径与 NUL。
2. **导入层**：`ModuleCardStore` 新增程序落盘分支——独立子目录、大小上限、
   lstat NOFOLLOW、导入时计算并落盘摘要记录；图片白名单不放宽。
3. **ABI 门禁**：导入与每次运行前各验一次 ELF 头（magic/class/machine）与设备 ABI；
   校验不过拒绝并进 `issues()` 可见诊断。
4. **授权层**：新增与命令授权独立的程序授权位（默认关、单独弹窗、单独撤销）；
   能力总线新增服务时归入该授权档。
5. **执行层**：仿 `RuntimeExecution.collect` 增加有界、lease 支撑的入口：
   `BoundedGuestSessions.begin` 登记 → `LaunchSpec.Builder.bind(宿主程序目录, 固定 guest 路径)`
   → 固定 argv 模板执行（模块字符串只作参数，永不拼进 shell）→
   `BoundedProcessRunner.collect` 限时限长 → `BoundedGuestLifetime.finish` 收退出证据。
   不复用 `ModuleExec.run` 的裸 Process 路径。
6. **UI 层**：模块中心展示程序身份（文件名 / SHA-256 短形 / ABI）与独立的「运行程序」授权开关；
   移除卡片时程序文件与授权一并清理（对齐本轮「删除卡片须清理其调度任务」的做法）。

## 4. 明确不做

- 不放宽 `requireImageName` / 素材白名单让 ELF 混进图片通道。
- 不从宿主直接 exec 应用私有目录里的 ELF（W^X，见 §1.4）。
- 不接受模块提供的 shell 字符串直接进入容器 shell；入口必须固定 argv。
- 不实现网络下载程序；程序只能随卡片导入通道进入并过全部校验。
- 不动 `HarnessController` 启动链与 `RuntimeExecution` 既有路径语义。
