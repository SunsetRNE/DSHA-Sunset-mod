# ADR 0004：外壳模块化、Kotlin 引入与 Rust 引擎方向

状态：**Accepted**。记录日期：2026-11-12。适用范围：build359 之后的开发线（C0 起）。
决策来源：5 条并行评审线（外壳、Rust、模块、覆盖更新、安全）+ 用户 2026-11-12 对全部推荐项的批准（逐条见文末决策记录，Q7 与 D5 为同一条）。
配套阅读：[可行性评审主稿](../design/kotlin-rust-rearchitecture-20261112/README.md)、[A1–A8 迭代路径](../design/kotlin-rust-rearchitecture-20261112/iteration-roadmap-20261112.md)、[覆盖安装与维护契约](../overwrite-upgrade-and-maintenance.md)。

本记录改变的是**开发方向与工程约束**，不改变 APK 运行行为、不改变包名/签名、不改变数据格式、不新增运行权限。它同时修订 [`docs/engineering-standard.md`](../engineering-standard.md) 第 13 行「Java 17，不引入 Kotlin」的旧约束——该行由本 ADR 取代，其余约束不变。

## 背景与问题

现行实现是一个 Java 17、单 `:app` 模块的 Android 宿主：964 个 `.java`、206,983 行、0 个 `.kt`、706 个 `*Test.java`、19 个自定义 Gradle 任务、50 个 Manifest 组件。它内部运行 proot/proroot Ubuntu + Node + DSH Web，并承载 3090 桥、无障碍与虚拟屏、备份与维护事务。

在这一规模上做"重写为 Kotlin 模块化框架 + Rust 核心引擎"有两个已知陷阱：

1. **把"模块化"做成"重写"**：全量重写没有中间态，706 个测试与 10 条覆盖更新不变式（I1–I10）会在过程中失去对照物，而"能编译"发现不了这些丢失。
2. **把"Rust 引擎"做成"接管权限与进程"**：现有白名单、停止证明、出生身份都由纯 Java 逻辑 + 单测钉住；搬进原生层会同时失去覆盖与离线可复现构建，并把拒绝点降级为惯例。

评审还发现：真正阻碍模块化的是 12 对包级 import 循环与 3 处按源码路径读文件的构建脚本，而不是语言本身；现有代码里也没有值得用 Rust 重写的 CPU 热点（唯一真实的 CPU 段是 gzip deflate）。

## 决定一：重切 + 增量 Kotlin 化，不做全量重写

- 先破依赖环、再建编译模块、最后按模块切换语言；禁止"先翻译全仓再补测试"。
- Kotlin 引入规则：**按模块切换、不按文件混写**；同一文件禁止 Java/Kotlin 互改；新模块可以是 Kotlin，旧文件在被改动前保持 Java。
- 格式与静态检查必须同批加锁（现锁 `google-java-format`；Kotlin 侧同批加 ktfmt/ktlint 并进离线依赖锁），否则仓库格式门禁失效。
- `util` 零 Android import 的现有规则保留，并在 Kotlin 模块下改写为等价表述（`:core:policy` 同时不得依赖任何其他模块）。

## 决定二：三种计数口径，禁止相减

| 口径 | 数量 | 含义 |
|---|---|---|
| 状态模块 | 21（15 必须 + 4 建议 + 2 强制隔离） | 职责、依赖、状态所有权、回归归属 |
| Gradle 编译模块 | 9（+2 二期） | 编译器边界；受依赖环与构建脚本约束 |
| Rust crate | 1（首期） | 纯计算 + 计划校验，无 Android 依赖 |

模块数不等于 Gradle 工程数，也不等于 APK 数；三者只做映射，不许用"21−18=3"这类算式推导功能差异。详见评审主稿 §4。

## 决定三：UI 保留 Material3 + Fragment/XML，不引入 Compose

`LayoutAuditInstrumentation` 的视觉门禁建立在 View 树、`R.id` 与像素/尺寸断言上；Compose 会让唯一视觉门禁在切换期间失明。Compose 仅允许用于"新页面且不替换已有页"，且需单独评审，不作为模块化载体。

## 决定四：Rust 首期只做旁路计算与计划校验

- 承接：归档 deflate、大文件流式摘要、计划校验第二实现（与 Kotlin 判定同输入对拍）。
- 不承接：进程出生身份与停止证明、信号、PTY、3090 桥、proot/proroot 启动器、日志脱敏、guest 内插件事务、**任何执行入口**。
- FFI：手写 JNI；`panic = "unwind"`，禁止 `abort`（会杀整个 App 进程）。
- **首期接口只有三类封闭纯函数，没有 `execute(...)`，不接收 `argv`**：
  1. `archive_pack(spec)` / `archive_unpack(spec)`：输入输出都是有界字节流，或 Kotlin 已核验并移交的 fd + 偏移/长度；Rust 不接收路径字符串、不自行 `open`。
  2. `digest(fdRange, algorithm)`：只读，返回定长摘要与实际读取长度。
  3. `verify_plan(view)`：只读校验（结构、字段范围、策略版本、有效期），返回 `VALID / INVALID(reason)`；**不执行、不产生副作用**。
- `PlanEnvelope` 与 `permitMac` 只是"将来若要做执行路径"的类型占位，**不进首期 ABI**；将来接执行路径须另立 ADR 重新评审——那正是"旁路计算悄悄变成第二套命令执行器"的风险点。
- HMAC 的定位如实写：它只能验证计划未被**无钥输入**篡改，**不是同进程恶意代码隔离**；同 UID 同进程代码仍可绕过（拥有密钥即可自造合法计划）。独立进程也不自动等于独立 UID 权限隔离。
- 结果模型：**正交字段，不是枚举**——`dispatched`（NOT_DISPATCHED/DISPATCHED/UNKNOWN）、`executed`（NOT_EXECUTED/EXECUTED/UNKNOWN）、`partial`（NONE/PARTIAL/UNKNOWN）、`effect`（VERIFIED/UNVERIFIED/UNKNOWN/NOT_APPLICABLE）、`retry`（SAFE/NEEDS_NEW_OBSERVATION/NOT_SAFE/UNKNOWN）。旧五态枚举只作**投影**用于日志/界面，投影表随契约冻结；`PARTIAL` 不再同时承担"效果未验证"与"取消后可能已执行"两种含义。
- 构建：NDK r26 + `aarch64-linux-android`；`max-page-size=16384`；`cargo vendor` 入库 + `Cargo.lock` + 字节复现 `--check`；`.so` 作为 CI 产物，不入库。

**失败域**（`catch_unwind` 只覆盖第一类；其余必须预防或接受进程死亡）：

| 域 | 可捕获 | 处理边界 |
|---|---|---|
| 可展开 panic（`panic=unwind`） | 是 | 每个 FFI 入口 `catch_unwind`，映射错误码；绝不把 unwind 抛出 FFI 边界 |
| abort（OOM、双 panic、`panic=abort`） | 否 | 进程死亡；对策：全局 unwind、有界输入、FFI 内不做大分配 |
| 原生崩溃（SIGSEGV/BUS、栈溢出、UB） | 否 | 进程死亡；对策：零/最小 `unsafe`、有界输入；高风险任务若需隔离，另立"独立进程"ADR |
| JNI 异常 pending | 可检测 | 每次 JNI 调用后 `ExceptionCheck`，转 Kotlin 异常；Rust 不得在 pending 异常下继续调用 |
| 内存生命周期（JNIEnv 跨线程、全局引用、局部帧） | 设计避免 | 不存 `JNIEnv`、不跨调用持引用、每次调用新局部帧 |

"引擎级隔离"的唯一站得住形态是**独立进程**（但仍非 UID 隔离）；本 ADR 不做，若需要另立 ADR（参考未实施的 `:web` 独立进程设计）。

## 决定五：旧版覆盖更新兼容不可让路

> **适用范围**：本节适用于**同一 `applicationId` 的发行线内部覆盖**。若 Rebirth 使用独立 applicationId（隔离实验发行线），跨线不构成覆盖安装，I1–I10 仍约束该线内部的版本迭代；跨线数据继承须另议（Keystore 别名、会话记录、`noBackup` 不共享）。

- 三条红线：`applicationId` = `io.github.sunsetrne.dsha`、Mod 自有证书（v1+v2+v3）、`versionCode` 单调递增。换签名即 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，只能卸载重装。
- I1–I10 一条不丢：判定逻辑原样移植并做 Java↔Kotlin/Rust 差分回归；进程与文件实现可重写，但**证据链逐字段等价**（出生身份、双次核验、guest 先于 launcher、四态信号语义、禁端口/名称/进程组）。
- 数据「双读单写」：新格式只写旁路文件或可选字段，不抬既有容器版本号；`dataWrite` 首版不提升。
- 每轮发布（A1 除外）会因 `launcherInputs` 摘要变化触发一次受管运行时维护页，这是 I6 的设计而非缺陷。

## 决定六：产品形态是"内部工程模块化"，不是"第三方可安装模块宿主"

区分两件事，不能一起裁掉：

- **允许（随包可信内置模块）**：编译期清单 + 最小运行时登记、贡献登记、可选功能启停、布局保存与恢复、旧回调失效、资源清理；法定许可与安全恢复入口由宿主兜底。它们不随"动态安装平台"一起被裁。
- **禁止（第三方可安装模块）**：动态代码加载、模块市场、模块级签名/权限、从网络下载可执行模块、模块停用后失去安全兜底。

C01/C02 先落为编译期接口 + 最小登记表；"可安装模块"若将来要做，需单独立项，并解决停用后安全兜底、法定许可入口、旧回调失效与恢复页。

## 决定七：范围裁剪（同期批准）

- **不恢复 low 构建线，也不在 A1–A8 引入 GeckoView 依赖**：A06 先收窄为"系统 WebView + 浏览器宿主接口"。注意二者不同——"不恢复 low"是砍掉构建变体；"给单包加 GeckoView"是新增可选引擎依赖（体积/内存/ABI/版本锁/双内核回退/测试矩阵），需独立评审。**Gecko 是确定产品需求，不得以"未来预留接口"无限后置**：由 Rebirth 线执笔评审，在浏览器宿主接口冻结与 A8 之前完成；实现可作为独立交付，但不进 A1–A8 编码。高刷同理登记为需求，不单列模块。
- **自更新只停自动段**：停自动检查/下载/安装；保留应用内覆盖安装与维护门禁（否则既有用户无升级路径）。
- **Root 模块管理只读先行**：先做模块清单与管理器识别/能力握手；写入口等 Root 写入口封闭/降权后再开。
- **短信递归 READ 缺口先单独修**（`security-model.md` 已声明的已知缺口），不等架构迁移。
- **差分回归列为 P0 硬门槛**：Java↔Kotlin/Rust 的 6 类身份夹具必须一致。

## 决定八：发行身份是 Rebirth 独立实验线（Q8）

- `applicationId` = `dsh.android.client.rebirth`（**2026-11-12 对方确认：有意选定，不是笔误；不改成 Mod 命名空间**）；应用名 DSHA 新生；项目 新生计划；代号 嫩芽 / Sprout。
  - 注意：该包名位于**上游命名空间** `dsh.android.client.*`，不是 Mod 的 `io.github.sunsetrne.dsha`。Mod 的发行身份文件、证书与核验链**不复用**于本线；Rebirth 需要独立身份文件与签名配置。
- 签名：独立、固定的 Rebirth 候选测试证书；不使用 DSHA 正式发布证书，也不使用 Mod 发布私钥；证书创建后只共享公钥与 SHA256。**同一 `applicationId` 只能有一个签名身份**——若两个仓库/构建线都产出该包，必须共用同一张证书，否则互相覆盖安装会因签名不一致失败。
- 与正式包 / Mod 包**可共存**，互不覆盖，各自维持升级连续性。
- `versionCode` 首个候选沿用 359（隔离候选首包编号，不冒充官方版本），同线递增，不与其他发行线同步。
- 跨线数据**默认不继承、不自动迁移**；正式数据不直接交给实验线。若需导入，另行验证备份格式、内容范围、密钥与授权边界；不预承诺 v5 可完整迁移所有会话；系统授权不随导入继承。
- 因此：A1–A8 以 **Rebirth 线**为发布目标；正式 Mod 线在本路径内不动；I1–I10 分别约束各线**内部**的覆盖安装。

**G0 工程装配（2026-11-12，unsigned only，不等于 G0 通过）**：

- `ci/release-identity.rebirth.json` 记录包名 `dsh.android.client.rebirth`、显示名、`versionCode` / `versionName`、测试/开发后缀、tag/APK 前缀与证书指纹。**证书指纹 64 个 0 = 未固定**；Mod/upstream 身份出现占位视为损坏，Rebirth 出现占位时任何签名门禁必须失败。
- 构建入口 `-Pdsha.rebirthCandidate=true`：只能与 `-Pdsha.unsignedPackage=true` 组合（证书未固定前），与 `-Pdsha.identity=mod|upstream`、`.test/.debug/.dev` 互斥；`signingConfigs.rebirth` 只读取 `DSHA_REBIRTH_*`，绝不回落到 `DSHA_KEYSTORE`。
- `verifyPublishCertificate` 的候选分支：占位证书 → 拒绝；复用 Mod/upstream 证书 → 拒绝；applicationId 或指纹不符 → 拒绝。
- 候选核验器与 CI 变体只产出 `UNSIGNED_NOT_FOR_INSTALLATION`，不上传正式 Secrets、不创建 tag/Release；核验器不替代真机安装与覆盖验证。
- 以上只关闭 G0 的“工程装配”；**证书创建/保管方与首发产包仓仍未定**（见 G0–G1 合并回执 §7），G0 门禁未通过。

## 后果

**正面**：模块边界与状态所有权先于语言切换落地；每轮可发布、可回退；覆盖更新路径不被重构打断；Rust 以可差分验证的方式引入，失败可一键回退。

**负面/代价**：Kotlin 工具链需新增离线依赖并重新锁版本；`cargo vendor` 增加仓库体积与许可审计面；每轮发布多一次维护页点按；21 个状态模块需要持续用边界门禁防止腐化。

**风险**：契约定错会波及后续轮次（A1 出口评审由非实现者核对）；模块化后测试可能静默跳过（需 convention plugin 与测试数门禁）；覆盖更新白屏与停止回归是历史高发区，I1–I10 与 11 个停止相关测试必须常跑。

## 决策记录（2026-11-12 批准）

| # | 决策 | 结论 |
|---|---|---|
| Q1 | 产品形态 | 内部工程模块化 + 随包可信内置模块的最小登记/启停（不做第三方可安装宿主，决定六） |
| Q2 | Gecko | 不在本路径引入 GeckoView 依赖（与是否恢复 low 线无关）；A06 收窄为系统 WebView + 接口预留，恢复/新增另立评审 |
| Q3 | 自更新范围 | 只停自动段，保留覆盖安装与维护门禁 |
| Q4 | 执行结果态 | 正交五字段（派发/执行/部分/效果/重试），枚举仅作投影 |
| Q5 | Root 模块管理 | 只读先行 |
| Q6 | Kotlin 引入 | 批准（决定一、三） |
| Q7 | 每轮维护页 | 接受（A1 目标零维护除外） |
| D1 | 重写方式 | 重切 + 增量 |
| D2 | Rust 范围 | 旁路 only |
| D3 | UI | 不引 Compose |
| D4 | 模块口径 | 21 / 9 / 1 冻结 |
| D5 | 首发维护页 | 接受 |
| D6 | cargo 依赖 | vendor 入库，`.so` 走 CI |
| D7 | 灰度期新格式 | 禁写 |
| D8 | 差分回归 | P0 硬门槛 |
| D9 | 短信缺口 | 先单独修 |
| Q8 | Rebirth 发行身份 | `dsh.android.client.rebirth` + 独立测试证书；可共存；versionCode 359 起；跨线不继承（决定八） |

## 关联

- [可行性评审主稿](../design/kotlin-rust-rearchitecture-20261112/README.md)（含 5 条评审线与证据）
- [A1–A8 迭代路径与进度看板](../design/kotlin-rust-rearchitecture-20261112/iteration-roadmap-20261112.md)
- [职责/状态 owner/编译模块/测试恢复/覆盖更新 收敛表（初稿）](../design/kotlin-rust-rearchitecture-20261112/convergence-table-20261112.md)
- [覆盖安装、运行时维护与内置插件依赖契约](../overwrite-upgrade-and-maintenance.md)
- [安全模型](../security-model.md)、[ADR 0001](0001-build154-boundaries.md)、[ADR 0002](0002-current-threat-model.md)、[ADR 0003](0003-virtual-screen-lifecycle-and-input.md)
