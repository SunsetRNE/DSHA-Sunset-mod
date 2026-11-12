# A1 契约草案：C01/C02 最小接口 + 结果五字段 wire + 投影表（G3）

> 版本：v0.1　日期：2026-11-12　基线：build359 `92acaa04` / `0.2.1-rc.5`
> 状态：**v0.1 内部冻结为我方基线（2026-11-13，G3 自冻结）**；O1–O5 挂起、待对方追认，不再阻塞 G2/A1。本文是 G3 的产出物，按路线图 §11.1「可先行」范围编写；**不代表 A1 业务编码可以开工**（G0–G5 仍缺一不开工）。
> 性质：只定接口**形状**与判定语义；不含实现、不建代码模块、不改 APK 行为/数据格式/权限、不提交、不推送。
> 用途：一页接口表供评审；冻结后作为 A1 任务单的契约依据，并作为反例测试向量的来源。
> 配套：[ADR-0004](../../adr/0004-kotlin-rust-direction.md) · [A1–A8 迭代路径](iteration-roadmap-20261112.md) · [对 18 模块方案的回复](reply-to-18-modules-20261112.md) · [修订建议](18-modules-revision-notes-20261112.md) · [G0–G1 合并回执](g0-g1-merge-receipt-20261112.md) · [可行性评审主稿](README.md)

本草案沿用对方 18 模块方案的编号：**C01 = 模块协议，C02 = 宿主核心，C03 = 授权核心**。这些编号是职责编号，不是模块计数；21 状态模块 / 9 Gradle 模块 / 1 Rust crate 的口径见 ADR-0004 决定二，三者只做映射、禁止相减（[路线图 §2](iteration-roadmap-20261112.md)）。

---

## 0. 一页速览

### 0.1 一页接口表

| # | 契约面 | 最小形状（一屏可读完） | 详细 | 状态 |
|---|---|---|---|---|
| C01 | 模块协议 | `describe()` 声明 → `register()` 注册 → `install()` 贡献 → `setEnabled()` 可选启停 → `close()` / `revoke()` 停用清理；旧代次回调一律失效 | §2 | 待冻结 |
| C02 | 宿主核心 | `ModuleRegistry`（注册/快照/启停）、`ContributionCatalog`（贡献登记/撤销/稳定排序）、`RouteTable`（路由 + fallback）、`LayoutStore`（布局保存恢复）、`SafeModeGate`（安全模式/恢复入口）、`AboutLicense`（法定许可聚合）、`DiagnosticsSink`（唯一脱敏出口） | §3 | 待冻结 |
| R1 | 结果 wire | 五个**必填**正交字段 `dispatched` / `executed` / `partial` / `effect` / `retry` + `resultVersion` + `reasonCode`；`events[]` 记录取消等事件，**不参与事实判定** | §4.2–§4.4 | 待冻结 |
| P1 | 投影表 | 五字段（+ `reasonCode` 归类）→ 旧五态 `NOT_SENT / REJECTED / COMPLETED / PARTIAL / UNKNOWN`；只用于日志/界面；默认 **UNKNOWN** | §4.6 | 待冻结 |
| X | 反例 | 未知 tag、取消后可能已执行、panic/原生崩溃逃逸、UNKNOWN 却断言远端已退出 | §5 | 待冻结 |
| B | Rust 边界 | 首期只有 `archive_pack/archive_unpack`、`digest`、`verify_plan` 三类只读/有界纯函数；**无 `execute`/`argv`/路径字符串**；`PlanEnvelope`/`permitMac` 仅类型占位、不进 ABI | §6 | 已定（ADR-0004 决定四/七） |

### 0.2 五条结论

1. **C01/C02 只到"编译期清单 + 最小运行时登记"**：随包可信内置模块；禁止第三方动态代码加载、模块市场、模块级签名/权限、从网络下载可执行模块（ADR-0004 决定六；回复 §12.2）。
2. **布局保存与恢复不是可插拔能力**：无论是否做可安装宿主，重启恢复、排序、隐藏、卡片位置都必须由宿主实现并持久化；不能跟着"可插拔宿主"一起裁掉（修订建议 §6）。
3. **五字段是事实，五态是投影**：旧 `PARTIAL` 同时承担"效果未验证"与"取消后可能已执行"两种含义，本草案把它拆回 `effect` 与 `executed`；取消只作为**事件**（ADR-0004 决定四；回复 §5.1、§10 第 3 条；修订建议 §2）。
4. **投影有损，且必须 fail-closed**：五字段无法单独区分 `NOT_SENT` 与 `REJECTED`（两者都是未派发、未执行），需要 `reasonCode` 归类；判定字段（`dispatched`/`executed`/`partial`）出现未知 tag、缺字段或非法值时必须投影 `UNKNOWN`，绝不默认 `COMPLETED`（评审稿 §8.3；现有 `CapabilityResult` 语义）。
5. **Rust 首期没有执行入口**：不接收 `argv`、不接收路径字符串、不自行 `open`、不提供 fd 执行与进程/信号操作；`catch_unwind` 只兜住可展开 panic，abort/原生崩溃/OOM 会杀死进程，调用方必须把"没有结果"当 UNKNOWN 而非 NOT_EXECUTED（ADR-0004 决定四失败域矩阵；评审稿 §6.3、§8.2）。

### 0.3 本草案不裁决的事

- 不定义 A1 页面布局、视觉、文案；不定义"关于/许可"入口的具体控件。
- 不重定义 C03 授权状态机：授权判定、`policyRevision`/一次性派发许可归 M05，本草案只冻结"授权在 Kotlin、Rust 只更严"的边界（评审稿 §3.2 硬边界 2）。
- 不定义布局的存储文件格式（见开放项 O3）。
- 不把 `PlanEnvelope`/`permitMac` 写成已实现或已冻结的执行路径；将来接执行路径须另立 ADR（ADR-0004 决定四）。

### 0.4 来源代号（全文表格引用）

| 代号 | 文档 | 用途 |
|---|---|---|
| A | [ADR-0004](../../adr/0004-kotlin-rust-direction.md) | 已批准决策（决定一…八、Q1–Q8） |
| R | [迭代路线图](iteration-roadmap-20261112.md) | 轮次、G0–G5、跨轮不变式、A1 出口判据 |
| P | [回复：18 模块对照](reply-to-18-modules-20261112.md) | 双方已对齐的接口语义（§5.1、§10、§12.2） |
| N | [修订建议](18-modules-revision-notes-20261112.md) | 对 C02/C03 的修订点（§2、§6） |
| E | [可行性评审主稿](README.md) | FFI/失败域/安全边界（§6.3、§8.2–§8.4） |
| M | [G0–G1 合并回执](g0-g1-merge-receipt-20261112.md) | G3 的出处与"可先行"边界 |
| C | 现有代码 | 五态枚举等事实（文件:行号） |
| D | 本草案推导 | 未决或需双方确认，**不得当作已定决策** |

---

## 1. 边界：内部工程模块化 + 随包可信内置模块

### 1.1 两层表述（ADR-0004 决定六）

| 层 | 内容 | 本草案落点 |
|---|---|---|
| **允许**（随包可信内置模块） | 编译期清单、最小运行时登记、贡献登记、可选功能启停、布局保存与恢复、旧回调失效、资源清理；法定许可与安全恢复入口由宿主兜底 | C01 §2、C02 §3 |
| **禁止**（第三方可安装模块） | 动态代码加载、模块市场、模块级签名/权限、从网络下载可执行模块、模块停用后失去安全兜底 | C01-I6、C02 §3.4 |

**判断标准**：本草案的注册表只登记**编译期已存在的类型实例**；任何"从字节流装载代码"的入口都越界。C01/C02 先落为编译期接口 + 最小登记表；"可安装模块"若将来要做，需单独立项并解决停用后安全兜底、法定许可入口、旧回调失效与恢复页（ADR-0004 决定六末段）。

### 1.2 与三条既有硬边界的关系（不可让路）

| 硬边界 | 本草案怎么守 | 来源 |
|---|---|---|
| 状态单点 | 注册表/目录是**派生视图**；启停意图持久化归 M01，布局持久化归 M01，授权归 M05，诊断出口归 M03；C02 不新增第二份权威状态 | E §3.2；收敛表 M01/M03/M05/M18 |
| 授权在 Kotlin、执行可下沉 | 模块只能**声明** `requestedCapabilities`，是否派发由 M05 判定；Rust 只接受已判定输入 | E §3.2；R §2.1 |
| 证据链等价 | 五字段带 `retry` 与"未知不重放"约束；投影只用于日志/界面，不反推重试 | A 决定五；R §4 G4/G7 |

### 1.3 与 A1 其它模块的关系

- A1 的用户可见交付是「关于与许可」入口（R §5 A1）；C01/C02 是它的骨架，F01 是第一个真实闭环（R §3）。
- A1 出口判据包含"停用卡片后法定许可入口仍可达"与"无新增敏感能力"（R §5 A1）；因此"法定许可聚合"必须由宿主持有，不能只做模块贡献（见 §3.3 C02-8）。
- A1 同时覆盖 C03 授权核心与 M05，但本草案不重定义授权状态机（§0.3）。

---

## 2. C01：模块协议（声明 → 注册 → 贡献 → 可选启停 → 停用清理）

### 2.1 生命周期五步

| 步 | 动作 | 由谁发起 | 产物 | 失败/幂等语义 | 来源 |
|---|---|---|---|---|---|
| ① 声明 | `describe(): ModuleDescriptor` | 宿主（启动早期） | 模块身份、版本、贡献声明、能力声明、启停策略 | **纯函数**：不读文件/网络/配置，不产生副作用；重复调用结果一致 | A-6；D |
| ② 注册 | `ModuleRegistry.register(descriptor, factory)` | 宿主 | `Registration.Registered` 或 `Registration.Rejected(code)` | **无部分登记**；id/贡献 key 冲突、`contractVersion` 不支持 → 拒绝并诊断 | A-6；D |
| ③ 贡献 | `install(host): ModuleInstance` + `contributions()` | 宿主（模块启用后） | 贡献句柄清单（带 `generation`） | 贡献 key 全局唯一；未知 `kind` 忽略 + 诊断，不崩溃 | R §5 A1；D |
| ④ 可选启停 | `setEnabled(id, enabled)` | 用户/宿主策略 | 新启用态；贡献挂载/卸载 | 只有 `OPTIONAL_USER` 可被用户停用；`ALWAYS_ON` 拒绝停用；意图写 M01 | A-6；N §6 |
| ⑤ 停用清理 | `close(reason)` → 强制 `revoke()` | 宿主 | 贡献撤销、回调失效、资源释放、布局记录保留 | `close`/`revoke` 必须幂等；异常被宿主捕获，不得冒泡 | A-6；P §4 Q4；N §6 |

### 2.2 形状示意（Kotlin，非实现）

```kotlin
// 形状示意，不代表实现；Kotlin 化按 ADR-0004 决定一（按模块切换、不按文件混写）
@JvmInline
value class ModuleId(val value: String)            // 编译期常量；只增不复用、不从文件/网络读取

data class ModuleDescriptor(
    val id: ModuleId,
    val implementationVersion: Int,                // 模块实现版本，单调递增
    val contractVersion: Int,                      // 宿主只接受已冻结版本
    val contributions: List<ContributionDeclaration>,
    val requestedCapabilities: Set<CapabilityId>,  // 只是"会请求"；授权归 M05
    val enablement: Enablement,                    // ALWAYS_ON / OPTIONAL_USER
)

data class ContributionDeclaration(
    val key: String,             // 模块内唯一；全局键 = "$id#$key"
    val kind: ContributionKind,  // NAV_ENTRY / SETTINGS_ENTRY / HOME_CARD / ABOUT_LICENSE / DIAGNOSTIC_SECTION
    val orderHint: Int,          // 稳定排序提示；不得依赖注册顺序
)

enum class Enablement { ALWAYS_ON, OPTIONAL_USER }

interface ModuleProtocol {
    fun describe(): ModuleDescriptor                     // ① 声明
    fun register(registry: ModuleRegistry): Registration // ② 注册
    fun install(host: ModuleHost): ModuleInstance        // ③ 贡献入口
}

interface ModuleInstance {
    val generation: Long                                  // 回调租约；旧代次回调一律丢弃
    fun contributions(): List<ContributionHandle>
    fun close(reason: CloseReason)                        // ⑤ 停用清理（幂等）
}

interface ContributionHandle {
    val key: String
    fun revoke()                                          // 宿主调用；幂等、立即失效
}

sealed interface Registration {
    data class Registered(val instance: ModuleInstance) : Registration
    data class Rejected(val code: String) : Registration  // fail-closed，无部分注册
}
```

### 2.3 接口表

| ID | 接口 | 归属 | 必须保证 | 失败语义 | 来源 |
|---|---|---|---|---|---|
| C01-1 | `describe()` | 模块 | 纯函数、无 I/O、无副作用；结果稳定 | 抛异常 = 声明无效，模块不注册并诊断 | D |
| C01-2 | `register()` | 宿主注册表 | 校验 id 唯一、`contractVersion` ∈ 已冻结集合、贡献 key 全局唯一 | `Rejected(code)`；不得部分登记 | A-6；D |
| C01-3 | `contributions()` | 模块 + 宿主目录 | 每个句柄可撤销；句柄绑定当前 `generation` | 空贡献合法（纯后台模块）；无句柄不得留悬空回调 | R §5 A1；D |
| C01-4 | `setEnabled()` | 宿主 | 仅 `OPTIONAL_USER` 可停用；`ALWAYS_ON` 恒启用；意图写 M01 | 写入失败 → 回到原态并报 `EnableResult.Rejected` | A-6；N §6 |
| C01-5 | `close(reason)` | 模块 | 幂等；释放自己的资源；不依赖被撤销的句柄 | 异常被捕获；宿主继续强制清理 | P §4 Q4；D |
| C01-6 | `revoke()` | 宿主 | 幂等、立即失效；撤销后旧回调不得再被调用 | 重复撤销返回成功语义 | A-6（旧回调失效/资源清理） |
| C01-7 | `generation` | 宿主 | 每次 `install`/`close` 单调提升；旧代次回调丢弃并计一次诊断 | 不补发、不重放旧回调 | A-6；D |

### 2.4 不变式（冻结前逐条核对）

| ID | 不变式 | 来源 |
|---|---|---|
| C01-I1 | **旧回调失效**：每次 `install`/`close` 提升 `generation`；带旧 `generation` 的回调被丢弃并记诊断，不补发、不重放 | A-6；P §4 Q4 |
| C01-I2 | **资源句柄撤销**：`revoke()` 撤销贡献登记；模块持有的 fd/观察者/广播/线程由 `ModuleHost` 提供的 scope 统一关闭；模块自行申请的资源自行释放 | A-6；P §0 第 3 条；P §2 C01 行 |
| C01-I3 | **布局保存与恢复由宿主兜底**：停用只隐藏贡献，不删布局记录；重新启用恢复原位置（详见 §3.3） | N §6 |
| C01-I4 | **法定许可与安全恢复入口由宿主兜底**：不可被任何模块停用或注册失败移除；安全模式只保留 `ALWAYS_ON` 贡献与宿主兜底入口；A1 出口按"停用卡片后入口仍可达"验收 | A-6；R §5 A1；P §4 Q4 |
| C01-I5 | **能力声明 ≠ 授权**：`requestedCapabilities` 只是声明，实际派发与撤销栅栏由 M05 判定 | R §2.1；E §3.2 |
| C01-I6 | **禁止项**：不做动态代码加载、模块市场、模块级签名/权限、从网络下载可执行模块 | A-6 |
| C01-I7 | **注册原子性**：注册失败无部分登记；未知贡献类型忽略 + 诊断，不得崩溃 | D |
| C01-I8 | **清理不可逃避**：`close` 抛异常时宿主强制 `revoke` 全部句柄、保留布局记录、报一次脱敏诊断；异常不得冒泡到 UI | D |

### 2.5 停用清理顺序（宿主视角）

1. `setEnabled(id, false)` 持久化用户意图到 M01；失败则中止并回原态。
2. 提升 `generation`，旧回调**立即**失效（C01-I1）。
3. 调用 `instance.close(CloseReason.USER_DISABLED)`；捕获并记录异常。
4. 强制 `revoke()` 全部 `ContributionHandle`（幂等兜底）。
5. 目录移除贡献；**布局记录保留**（C01-I3）。
6. 复核宿主兜底入口：法定许可、安全/恢复入口仍可达（C01-I4）。
7. 向 `DiagnosticsSink` 报一次脱敏诊断（§3.2 C02-9）。

> 步骤依据：A-6（旧回调失效、资源清理、停用后安全兜底）、P §4 Q4（旧回调失效/宿主恢复页）、N §6（布局保留）；**顺序本身是本草案推导（D）**。

### 2.6 模块生命周期失败域

| 失败域 | 可捕获 | 处理边界 | 来源 |
|---|---|---|---|
| 注册冲突（重复 id / 贡献 key） | 是 | `Rejected(code)`，无部分登记，不影响其它模块 | D |
| `install` / `close` 抛异常 | 是（宿主 try/catch） | 强制撤销句柄 + 诊断；不冒泡；不自动重装 | D |
| 旧 `generation` 回调 | 是（租约） | 丢弃 + 诊断；不重放 | A-6；D |
| 宿主进程死亡 / 崩溃循环 | 否（进程级） | 下次启动进安全模式候选；恢复入口恒可达；未完成动作按 UNKNOWN 处理，不重放 | A-4 失败域；R §4 G4；D |

> 说明：上表最后一行与 ADR-0004 的失败域矩阵同构——"能捕获"与"必须预防"分开写，不把 `catch_unwind` 当成万能兜底（A 决定四；E §6.3）。

---

## 3. C02：宿主核心最小接口

### 3.1 接口表

| ID | 接口 | 职责 | 状态归属 | 失败语义 | 来源 |
|---|---|---|---|---|---|
| C02-1 | `ModuleRegistry.register` | 校验并登记编译期模块；不加载外部代码 | 派生表（可重建） | `Rejected(code)`；无部分登记 | A-6；D |
| C02-2 | `ModuleRegistry.snapshot` | 给 UI 的只读快照（启用态、贡献清单） | 派生；UI 侧归 M18 | 快照过期不得推断"模块已停用"；以新快照为准 | E §3.2；收敛表 M18；D |
| C02-3 | `ModuleRegistry.setEnabled` | 用户意图 → M01；触发 `install`/`close`，并按 §2.5 编排停用清理（提升代次 → close → 强制 revoke → 布局保留 → 诊断） | **M01**（唯一配置入口） | 写入失败回原态；不吞错 | M01；N §6；D |
| C02-4 | `ContributionCatalog` | 贡献登记/撤销/按 `kind` 查询；`orderHint` + 全局键稳定排序 | 派生 | 未知 `kind`/重复 key → 忽略或拒绝注册 + 诊断 | R §5 A1；D |
| C02-5 | `RouteTable.resolve` | 页面路由（模块贡献的入口 → 宿主页面/模块页面） | M18 | 未命中 → 宿主 fallback 页 + 诊断，不崩溃 | R §5 A1 |
| C02-6 | `LayoutStore` | 布局快照的保存/恢复（排序、显隐、卡片位置） | 持久化 **M01**；渲染快照 M18 | 损坏 → 默认布局 + 诊断一次；未知 key 保留不删 | N §6；D |
| C02-7 | `SafeModeGate` | 安全模式判定；恢复入口可用性 | 宿主 | `recoveryEntryAvailable()` **恒 true** | A-6；P §4 Q4 |
| C02-8 | `AboutLicense` | 法定许可与第三方许可聚合、外部项目链接 | 宿主静态资产（`THIRD_PARTY_NOTICES.md` 等） | 模块停用、注册失败、安全模式均不影响可达 | A-6；R §5 A1 |
| C02-9 | `DiagnosticsSink` | 唯一脱敏出口；模块不得直写 logcat/日志文件 | **M03** | 脱敏失败拒绝导出；不落凭据/正文 | R §5 A1；收敛表 M03；D |

### 3.2 形状示意（Kotlin，非实现）

```kotlin
interface ModuleRegistry {
    fun register(descriptor: ModuleDescriptor, factory: () -> ModuleInstance): Registration
    fun snapshot(): List<ModuleRecord>                 // 只读派生视图
    fun setEnabled(id: ModuleId, enabled: Boolean): EnableResult // 仅 OPTIONAL_USER
    fun safeMode(): SafeModeState
}

interface ContributionCatalog {
    fun add(declaration: ContributionDeclaration, handle: ContributionHandle)
    fun revoke(handle: ContributionHandle)
    fun byKind(kind: ContributionKind): List<ContributionView> // 稳定排序
    fun resolve(globalKey: String): ContributionView?          // 未知 → null + 诊断
}

interface RouteTable { fun resolve(routeKey: String): RouteTarget? } // null → 宿主 fallback

interface LayoutStore { fun load(): LayoutSnapshot; fun save(snapshot: LayoutSnapshot) } // 持久化在 M01

interface SafeModeGate {
    fun forcedSafeMode(): Boolean
    fun recoveryEntryAvailable(): Boolean                       // 恒 true（C01-I4）
}

interface AboutLicense { fun mandatoryNotices(): List<Notice> } // 宿主静态资产，F01 只做呈现

interface DiagnosticsSink { fun report(event: DiagnosticEvent) } // 唯一脱敏出口（M03）
```

> `ModuleRecord` / `ContributionView` / `RouteTarget` / `LayoutSnapshot` / `SafeModeState` / `Notice` / `DiagnosticEvent` / `CloseReason` / `EnableResult` 是**占位类型名**，冻结时补最小字段定义；本草案不展开，避免把实现细节写进契约。

### 3.3 布局保存与恢复（不能跟着可插拔一起裁）

- **定位**：这是真实用户功能（重启恢复、排序、隐藏、桌面卡片位置），**不是可插拔能力**（N §6）。
- **持久化边界**：写入归 M01（唯一配置读写入口）；内存快照归 M18；注册表**不持久化**（编译期清单每次可重建）（D）。
- **迁移边界**：旧布局键只读经 M20；新键进 A1 独立命名空间；不抬既有容器版本号、不改历史键名（A 决定五；R §4 G3）。
- **未知贡献 key**：保留记录但不可达（避免降级后丢布局），在诊断中报一次（D）。
- **损坏/不可读**：fail-closed 回默认排序与默认显隐，不阻塞启动，诊断一次（D）。
- **恢复默认入口**：宿主固定入口，不属于任何模块贡献；不可被停用移除（C01-I4）。
- **停用语义**：停用只隐藏贡献，布局记录保留；重新启用后恢复原位置（C01-I3）。

### 3.4 "编译期清单 + 最小运行时登记" 与 "可安装模块宿主" 的边界

| 能力 | 本草案（内部工程模块化） | 可安装模块宿主需要 | 结论 | 来源 |
|---|---|---|---|---|
| 模块来源 | 随包编译期清单 | 运行时发现/校验（路径、摘要、签名） | **不做** | A-6 |
| 装载方式 | 编译期类型引用 + 最小登记 | 动态代码加载（DexClassLoader/下载） | **禁止** | A-6 |
| 版本化 | 单一已冻结 `contractVersion`；不匹配即拒绝 | 稳定公开 API + 兼容矩阵 | **不做** | P §9 第 1 项 |
| 权限 | 无模块级权限；能力声明≠授权 | 模块级签名/权限/沙箱 | **不做** | A-6 |
| 生命周期 | `ALWAYS_ON` / `OPTIONAL_USER` 启停 | 安装/升级/卸载/回滚 | **不做** | A-6 |
| 分发 | 随 APK | 模块市场 / 网络下发可执行模块 | **禁止** | A-6 |

> 结论：本草案的"注册表"是**编译期清单的运行时索引**，不是插件运行时。任何新增"运行时装载"的提案都必须先解决停用后安全兜底、法定许可入口、旧回调失效与恢复页四项（A-6；P §9 第 1 项）。
>
> 与 ADR-0004 决定一/二的关系：决定一要求"先破依赖环、再建编译模块、最后按模块切换语言"，所以本草案先定契约形状、不预拆工程；决定二要求 21/9/1 只做映射、禁止相减，所以 C01/C02 **不是新增状态模块**——它们的落点是 A1 已有的 M18（UI 组合根/呈现）、M01（配置与布局持久化）、M03（诊断出口）、M05（授权派发），只是这些模块之间的协作者接口（R §2.1）。

---

## 4. 结果模型：正交五字段 wire（ResultV1）

### 4.1 为什么不是枚举

旧五态 `NOT_SENT / REJECTED / COMPLETED / PARTIAL / UNKNOWN`（[CapabilityResult.java:7-12](../../../app/src/main/java/com/deepseekharness/app/capabilities/CapabilityResult.java)）用**一个维度**表达**五个独立问题**：是否派发、执行是否确定、是否部分完成、效果是否验证、能否重试。歧义的具体位置：

| 场景 | 旧五态 | 旧语义被混在一起的两个问题 |
|---|---|---|
| 执行成功但界面没更新 | `COMPLETED` 或 `PARTIAL` | "执行确定" ↔ "效果未验证" |
| 取消后可能已执行 | `PARTIAL` | "部分完成" ↔ "执行未知/取消" |
| 策略拒绝 | `REJECTED` | "未派发" ↔ "拒绝原因" |

本草案按 ADR-0004 决定四改为正交五字段；旧五态降级为**投影**，只用于日志/界面。注意：[drafts/05-safety-permissions.md:58](drafts/05-safety-permissions.md) 曾要求 K↔R 传五态枚举，该表述**已被 ADR-0004 决定四取代**，实现与评审不得再按旧草案执行。

### 4.2 wire 结构

```json
{
  "resultVersion": 1,
  "dispatched": "DISPATCHED",
  "executed": "UNKNOWN",
  "partial": "UNKNOWN",
  "effect": "UNKNOWN",
  "retry": "NEEDS_NEW_OBSERVATION",
  "reasonCode": "CANCELLED_AFTER_DISPATCH",
  "events": [
    { "kind": "CANCELLED", "at": "2026-11-12T08:00:00Z" }
  ]
}
```

**编码边界**：上表是**逻辑 wire**。Kotlin↔Rust 的 JNI 侧用 `i32` 码 + 固定枚举映射为 Kotlin sealed 类型，不传 JSON、不把字符串当判据（E §6.3）；JSON 形态只用于诊断/日志/桥回执，字段语义与 JNI 侧等价，投影只做一次。

### 4.3 字段表（字段名 / 取值 / 必填 / 语义）

| 字段 | 类型与取值 | 必填 | 回答的问题 | UNKNOWN 语义 | 来源 |
|---|---|---|---|---|---|
| `resultVersion` | 整数，首期 `1` | 是 | wire 版本 | 缺失/非法 → 整条结果 invalid（fail-closed） | D |
| `dispatched` | `NOT_DISPATCHED` / `DISPATCHED` / `UNKNOWN` | 是 | 是否派发 | 无法证明"没派发"也无法证明"已派发" | A-4 |
| `executed` | `NOT_EXECUTED` / `EXECUTED` / `UNKNOWN` | 是 | 执行是否确定 | 无法证明"未执行"或"已执行" | A-4 |
| `partial` | `NONE` / `PARTIAL` / `UNKNOWN` | 是 | 是否部分完成 | 无法判断完成度 | A-4 |
| `effect` | `VERIFIED` / `UNVERIFIED` / `UNKNOWN` / `NOT_APPLICABLE` | 是 | 效果是否验证 | 无法判断效果 | A-4 |
| `retry` | `SAFE` / `NEEDS_NEW_OBSERVATION` / `NOT_SAFE` / `UNKNOWN` | 是 | 能否重试 | 不得自动重试 | A-4 |
| `reasonCode` | 有界机器码，≤128 字符，非空 | 是 | 为什么未派发 / 失败类别 | 缺失 → 投影降为 `UNKNOWN` | P §5.1；C `CapabilityResult`:18-21 |
| `events[]` | `{kind, at, note?}`；`kind` 如 `CANCELLED` / `TIMED_OUT` / `RETRY_ATTEMPTED` | 否（有取消/超时则必填） | 事件流水（何时被取消/超时） | 事件**不是**事实维度，不能改变五字段 | P §10 第 3 条；N §2 |

**字段间约束（本草案推导 D，判定前先校验；违者整条 invalid → 投影 `UNKNOWN`）**：

| # | 约束 | 理由 |
|---|---|---|
| J1 | `dispatched=NOT_DISPATCHED` ⇒ `executed=NOT_EXECUTED` ∧ `partial=NONE` | 没派发不可能执行 |
| J2 | `executed=EXECUTED` ⇒ `dispatched=DISPATCHED` | 执行了必然派发过 |
| J3 | `partial=PARTIAL` ⇒ `executed ∈ {EXECUTED, UNKNOWN}` | 部分完成意味着有执行 |
| J4 | `effect=VERIFIED` ⇒ `executed=EXECUTED`，且必须有**新观察**作为证据 | 平台接受输入/调用返回都不等于业务效果已验证（[ADR-0003](../../adr/0003-virtual-screen-lifecycle-and-input.md)）；效果验证需要新观察（A-4） |
| J5 | `executed=UNKNOWN` ⇒ `retry≠SAFE`；`retry=SAFE` ⇒ `executed=NOT_EXECUTED` 或 `effect=NOT_APPLICABLE` | 未知不得自动重试（R §4 G4）；只有"确认未执行"或"无副作用的只读计算"可安全重试 |
| J6 | `retry=NOT_SAFE` 时不得自动重试；`retry=NEEDS_NEW_OBSERVATION` 必须先取得新观察 + 新凭据 | 重试只能由 Kotlin 在新观察/新凭据后显式发起（E §8.3） |

### 4.4 兼容策略

1. **版本**：`resultVersion` 单调整数。**只允许加字段**（旧 reader 忽略未知字段，但不得因字段缺失判成功）；**加枚举取值同版本允许**，旧 reader 必须 fail-closed 映射 `UNKNOWN`（评审稿 §8.3）；**改字段语义 / 删字段 / 改必填性必须升版本**，旧版本进只读兼容层。
2. **缺失/非法/未知 tag**：字段缺失或取值非法 → 整条结果 invalid，投影 `UNKNOWN`；**未知枚举 tag** → 该字段降为 `UNKNOWN`（fail-closed），若降级的是判定字段（`dispatched`/`executed`/`partial`）则整条投影 `UNKNOWN`。**禁止默认值补齐**，禁止默认 `COMPLETED`，也禁止把未知 tag 猜成"同前缀/近义值"（评审稿 §8.3；A-4）。
3. **字符串不是判据**：`reasonCode` / `detail` 只用于投影归类与展示，不参与 `retry`/`executed` 判定；`detail` 不得含凭据、正文、路径（E §6.3；R §4 G8）。
4. **布尔不是状态**：不得用 `success: true/false` 代替五字段；布尔无法表达"可能已执行"。
5. **单向投影**：五字段 → 旧五态只用于日志/界面；禁止用旧五态反推重试（过渡期只读适配按 §4.7 最保守映射）（A-4；D）。
6. **投影是纯函数**：同一 `ResultV1` 必须永远得到同一投影；投影实现进单测，反例（§5）即测试向量（D）。

### 4.5 取消是事件，不是结果态

- 取消记录为 `events[]` 里的 `CANCELLED` 事件；**不进入** `partial`，也不作为独立结果态（P §10 第 3 条；N §2）。
- 取消本身只能证明"发出了取消请求/观察到了取消"，**不能证明没有副作用**；因此取消后仍须由 Kotlin 读回真实状态（E §6.3）。
- 取消后的合法组合（示例）：`dispatched=DISPATCHED` + `executed=UNKNOWN` + `partial=UNKNOWN` + `effect=UNKNOWN` + `retry=NEEDS_NEW_OBSERVATION`。
- 取消后**不得**出现：`executed=NOT_EXECUTED` + `retry=SAFE`（除非有本机类型化证据证明未发送；`NOT_SENT` 只能由类型化本机事实建立，见 [ADR-0003](../../adr/0003-virtual-screen-lifecycle-and-input.md)、[设备权限架构 §10](../../device-permissions-architecture.md)）。

### 4.6 投影表：五字段 → 旧五态（只用于日志/界面）

> 来源：字段取值见 A-4；"取消后可能已执行"的组合见 P §5.1；旧五态定义见 C `CapabilityResult`:7-12。**投影判据本身是本草案推导（D），待冻结。**

| 旧五态 | 判据（五字段 + `reasonCode` 归类） | 保留的语义 | 必须避免 |
|---|---|---|---|
| `NOT_SENT` | `dispatched=NOT_DISPATCHED` ∧ `executed=NOT_EXECUTED` ∧ `reasonCode` 属"本地未发送"类 | 可安全重试（`retry=SAFE`）；不得声称远端收到 | 不得因 `reasonCode` 文本相似就投影成 `REJECTED` |
| `REJECTED` | `dispatched=NOT_DISPATCHED` ∧ `executed=NOT_EXECUTED` ∧ `reasonCode` 属"策略拒绝"类 | 拒绝不可被"仍然允许"覆盖 | 不得投影成 `NOT_SENT`（会诱导换通道重试） |
| `COMPLETED` | `dispatched=DISPATCHED` ∧ `executed=EXECUTED` ∧ `partial=NONE` | 执行确定；`effect` 不参与该投影 | **不得**把 `effect=UNVERIFIED` 改投影成 `UNKNOWN`；旧枚举本就不表达效果验证 |
| `PARTIAL` | `partial=PARTIAL` ∧ `executed ∈ {EXECUTED, UNKNOWN}` | 部分完成（真实的部分完成） | **不得**把"取消后可能已执行"投影成 `PARTIAL` |
| `UNKNOWN` | 其余全部：`dispatched`/`executed`/`partial` 任一为 `UNKNOWN`、字段缺失/非法、未知 tag、`reasonCode` 缺失 | fail-closed；可能已执行 | 不得默认 `COMPLETED`；不得把 `UNKNOWN` 当失败自动重试 |

**投影的有损点（必须写进日志/界面，不准静默）**：

1. `NOT_SENT` 与 `REJECTED` 在五字段上完全相同，只能靠 `reasonCode` 归类区分（→ 开放项 O1）。
2. `COMPLETED` 不表示效果已验证；界面文案必须能读到 `effect` 字段，不得用投影代替（A-4；[ADR-0003](../../adr/0003-virtual-screen-lifecycle-and-input.md) "平台接受输入也不等于应用业务效果已验证"）。
3. `UNKNOWN` 与 `PARTIAL` 都可能"已产生副作用"；两者都不得自动重放（R §4 G4）。

### 4.7 逆向投影（过渡期只读适配，最保守）

| 旧状态 | 过渡期五字段 | 限制 | 来源 |
|---|---|---|---|
| `NOT_SENT` | `NOT_DISPATCHED` / `NOT_EXECUTED` / `NONE` / `NOT_APPLICABLE` / `SAFE` | 仅当旧产生方确有"本机未发送"证据 | C:7-12、46-53 |
| `REJECTED` | `NOT_DISPATCHED` / `NOT_EXECUTED` / `NONE` / `NOT_APPLICABLE` / `NOT_SAFE` | 拒绝理由进 `reasonCode` | C:7-12 |
| `COMPLETED` | `DISPATCHED` / `EXECUTED` / `NONE` / `UNVERIFIED` / `NOT_SAFE` | `effect` 只能记 `UNVERIFIED`，不得伪造 `VERIFIED` | C:46-48 |
| `PARTIAL` | `DISPATCHED` / `UNKNOWN` / `UNKNOWN` / `UNKNOWN` / `NEEDS_NEW_OBSERVATION` | 旧 `PARTIAL` 歧义，按"可能已执行"处理 | C:46-48 |
| `UNKNOWN` | `UNKNOWN` / `UNKNOWN` / `UNKNOWN` / `UNKNOWN` / `NEEDS_NEW_OBSERVATION` | 不得自动重试 | C:46-48 |

> 逆向投影只允许出现在"旧调用点尚未迁移"的只读适配层；**不得**成为新的状态源，也不得用它驱动派发/重试决策。

---

## 5. 反例（契约验收向量，≥3）

> 每条反例同时是单测向量：投影函数、JNI 映射、日志脱敏各自至少一条。反例的期望结果不是"最方便"的结果，而是**fail-closed** 结果。

### CE-1 未知 tag

- **输入**：`{"resultVersion":1,"dispatched":"DISPATCH_ACCEPTED","executed":"EXECUTED","partial":"NONE","effect":"VERIFIED","retry":"SAFE","reasonCode":"X"}`
- **错误做法**：把 `DISPATCH_ACCEPTED` 当 `DISPATCHED`（"看起来像成功"）；或丢弃整条结果直接返回成功。
- **正确做法**：未知枚举值 → 该字段按 `UNKNOWN` 处理（不猜近义值）；本例降级的是 `dispatched`，且与 `executed=EXECUTED` 冲突（违反 J2），整条 invalid → 投影 `UNKNOWN`；`retry` 必须降为 `NEEDS_NEW_OBSERVATION`（J5），不得保持 `SAFE`；`unknownTag` 只进诊断，不成为新状态、不回写。
- **断言**：`project(...) == UNKNOWN`；`retry != SAFE`；不得产生派发/重试动作。
- **来源**：E §8.3（未知 tag 一律 fail-closed 映射 UNKNOWN）；A-4（封闭取值）。

### CE-2 取消后可能已执行

- **时序**：派发 → 用户取消 → 取消回执到达 → 原操作无响应。
- **输入**：`dispatched=DISPATCHED`、`executed=UNKNOWN`、`partial=UNKNOWN`、`effect=UNKNOWN`、`retry=NEEDS_NEW_OBSERVATION`、`events=[{kind:CANCELLED}]`。
- **错误做法**：投影 `PARTIAL`（把取消塞回旧枚举）；或报 `NOT_SENT`/`executed=NOT_EXECUTED` 后自动重试；或换通道/换屏重试。
- **正确做法**：明确"可能已执行"；由 Kotlin 取得**新观察 + 新凭据**后才决定是否重试；期间不重放、不换通道。
- **断言**：`executed != NOT_EXECUTED`；`retry != SAFE`；`events` 不得改变五字段；投影为 `UNKNOWN` 而非 `PARTIAL`。
- **来源**：A-4；P §5.1（原文："取消后可能已执行" = `dispatched=DISPATCHED` + `executed=UNKNOWN/EXECUTED` + `retry=NEEDS_NEW_OBSERVATION`）；P §10 第 3 条；R §4 G4。

### CE-3 panic / 原生崩溃逃逸

- **场景 A（可展开 panic）**：Rust 的 `archive_unpack` 在 `catch_unwind` 内 panic → 映射固定错误码返回。此时可能已写入部分字节，**不得**报"未执行"。
- **场景 B（abort/SIGSEGV/OOM）**：`catch_unwind` 兜不住，进程死亡，JNI 调用**不返回**。调用方不得把"没有结果"当 `NOT_EXECUTED`，也不得当 `COMPLETED`；重启后对在途任务按 `UNKNOWN` 处理，不自动重放。
- **错误做法**：用 `catch_unwind` 覆盖全部失败域；把异常/无返回改写成 `Err` 后自动重试；把 `UNKNOWN` 当失败忽略。
- **正确做法**：按 ADR-0004 失败域矩阵分域处理；FFI 入口只捕获可展开 panic，绝不把 unwind 抛出边界；JNI pending 异常在每次调用后 `ExceptionCheck` 并转 Kotlin 异常；abort/原生崩溃/OOM 只能预防（零/最小 `unsafe`、有界输入、FFI 内不做大分配）。
- **断言**：不存在把"无返回"映射为 `NOT_EXECUTED`/`COMPLETED` 的代码路径；panic 返回时写入类结果为 `executed=UNKNOWN` + `partial=UNKNOWN` + `effect=UNKNOWN`；`retry=NEEDS_NEW_OBSERVATION`。
- **来源**：A-4 失败域矩阵；E §6.3；P §10 第 4 条。

### CE-4 UNKNOWN 却断言"远端已退出"

- **输入**：`executed=UNKNOWN` 同时携带 `remoteExitConfirmed=true`（或等价字段）。
- **错误做法**：接受该组合，进而认为"远端已退出 = 没执行"。
- **正确做法**：直接判为非法结果（fail-closed），记录 `INVALID_RESULT`；不得降级成 `NOT_EXECUTED`。
- **断言**：构造该组合即抛/拒绝；投影 `UNKNOWN`。
- **来源**：[DeviceTransportResult.java:31](../../../app/src/main/java/com/deepseekharness/app/device/DeviceTransportResult.java)（`UNKNOWN_CANNOT_PROVE_REMOTE_EXIT`）；E §8.3。

---

## 6. Rust 边界：首期只读/有界纯函数

### 6.1 三类封闭纯函数（四个函数，首期 ABI 的全部）

| 函数 | 输入 | 输出 | 副作用 | 结果建议 | 来源 |
|---|---|---|---|---|---|
| `archive_pack(spec)` | 有界字节流，或 Kotlin 已核验并移交的 fd + 偏移/长度 | 定长结果 + 已读/已写字节数 | 只写宿主移交的 fd | 写入类：取消/panic 后 `executed=UNKNOWN`、`partial=UNKNOWN`、`effect=UNKNOWN`，产物由 Kotlin 回读 | A-4 |
| `archive_unpack(spec)` | 同上 | 同上 | 只写宿主移交的 fd | 同上 | A-4 |
| `digest(fdRange, algorithm)` | 已核验 fd + 范围 | 定长摘要 + 实际读取长度 | 只读 | `effect=NOT_APPLICABLE`；只读幂等，重试可 `SAFE` | A-4 |
| `verify_plan(view)` | 只读视图（结构、字段范围、策略版本、有效期） | `VALID` / `INVALID(reason)` | 无 | `effect=NOT_APPLICABLE`；重试可 `SAFE` | A-4 |

> 上表"结果建议"列是本草案推导（D），用于把三类函数纳入统一的 ResultV1；**不是** ADR 原文。

### 6.2 明确不做（首期）

- 没有 `execute(...)`、不接收 `argv`、不接收路径字符串、不自行 `open`、不提供 fd 执行、不提供进程/信号操作（A-4；R §4 G7）。
- `PlanEnvelope` 与 `permitMac` 只是"将来若要做执行路径"的**类型占位**，不进首期 ABI；将来接执行路径须另立 ADR 重新评审（A-4；P §10 第 1 条）。
- HMAC 定位如实写：只能验证计划未被**无钥输入**篡改，不是同进程恶意代码隔离；同 UID 同进程代码拥有密钥即可自造合法计划；独立进程也不等于独立 UID 权限隔离（A-4；P §10 第 2 条）。
- crate 不访问 SharedPreferences / Keystore / Binder；唯一输入面是字节流、已核验 fd 与只读视图（E §8.2 第 4 条）。
- 最终拒绝权永远在 Kotlin；Rust 只能更严，不能更松（E §8.2 第 5 条）。

### 6.3 失败域矩阵（决定 panic 反例的期望）

| 域 | 可捕获 | 处理边界 | 来源 |
|---|---|---|---|
| 可展开 panic（`panic=unwind`） | 是 | 每个 FFI 入口 `catch_unwind`，映射固定错误码；绝不把 unwind 抛出 FFI 边界 | A-4；E §6.3 |
| abort（OOM、双 panic、`panic=abort`） | 否 | 进程死亡；对策：全局 unwind、有界输入、FFI 内不做大分配 | A-4 |
| 原生崩溃（SIGSEGV/BUS、栈溢出、UB） | 否 | 进程死亡；对策：零/最小 `unsafe`、有界输入；高风险任务若需隔离，另立"独立进程"ADR | A-4 |
| JNI 异常 pending | 可检测 | 每次 JNI 调用后 `ExceptionCheck`，转 Kotlin 异常；Rust 不得在 pending 异常下继续调用 | A-4 |
| 内存生命周期（JNIEnv 跨线程、全局引用、局部帧） | 设计避免 | 不存 `JNIEnv`、不跨调用持引用、每次调用新局部帧 | A-4 |

### 6.4 取消三态与五字段的映射（草案，待冻结）

FFI 协作式取消返回三态 `DONE / CANCELLED / UNKNOWN`（E §6.3）。映射建议（来源：E §6.3 三态 + A-4 五字段；**映射表为本草案推导 D**）：

| Rust 返回 | 能证明什么 | Kotlin 侧五字段 | 禁止 |
|---|---|---|---|
| `DONE` | 调用正常结束 | `executed=EXECUTED`；写入类须回读产物后才能 `effect=VERIFIED` | 不得直接 `effect=VERIFIED` |
| `CANCELLED` | 取消标志被看到；**不证明没有副作用** | 写入类：`executed=UNKNOWN`、`partial=UNKNOWN`、`retry=NEEDS_NEW_OBSERVATION`；只读类：`executed ∈ {NOT_EXECUTED, EXECUTED}`、`effect=NOT_APPLICABLE`、`retry=SAFE` | 不得把 `CANCELLED` 写成 `executed=NOT_EXECUTED` 后自动重试 |
| `UNKNOWN` | 无法判断 | `executed=UNKNOWN`、`partial=UNKNOWN`、`retry=NEEDS_NEW_OBSERVATION` | 不得重放 |

### 6.5 其它禁止项（安全边界）

- 不发信号（只接受 Kotlin 核验后的进程句柄，不接收裸 pid）、不监听 socket（除受管 Core 既有回环端口）、不直写 logcat、不访问 Android API 面（权限/Service/WebView/无障碍/SAF/Keystore/Shizuku/Root）（E §6.1、§8.4）。
- 不复制历史 `[POLICY_BLOCKED]` 文本协议到 FFI 层；错误用 `i32` 码 + 固定枚举，Kotlin 侧 sealed 化（E §6.3）。

---

## 7. 待双方冻结的开放项（≤5）

| ID | 问题 | 我方建议 | 不冻结的后果 | 谁确认 |
|---|---|---|---|---|
| O1 | `NOT_SENT` 与 `REJECTED` 在五字段上不可区分：靠 `reasonCode` 文本归类，还是新增类型化辅助字段 `notDispatchedReason`（`NEVER_ATTEMPTED` / `POLICY_REFUSED` / `PRECONDITION_FAILED` / `CANCELLED_BEFORE_DISPATCH`）？ | 新增类型化辅助枚举；`reasonCode` 只做展示。理由：评审稿 §6.3 明确不把字符串消息当判据；[设备权限架构 §10](../../device-permissions-architecture.md) 也要求 NOT_SENT 只能由类型化本机事实建立 | 投影表无法冻结；日志/界面把拒绝误当"可换通道重试" | 双方 |
| O2 | 模块身份与契约版本的稳定性：`ModuleId` 格式/命名空间、`contractVersion` 兼容策略、模块 id 能否重命名（影响布局迁移） | 编译期常量；`contractVersion` 首期只接受 `1`；id **只增不复用**；重命名=新 id + 显式布局迁移 | 布局记录与日志无法对账；后续升级只能整体废弃 | 双方 |
| O3 | 布局保存与恢复的载体与迁移边界：持久化在 M01 的哪个命名空间、旧键如何只读、未知贡献 key 保留多久、恢复默认入口归属 | M01 独立命名空间；旧键只读经 M20；未知 key 保留不删并诊断；恢复默认入口归宿主 | "重启后布局丢失"会变成 A1 用户可见回归（N §6 的担忧） | 双方 |
| O4 | 纯函数取消（`DONE/CANCELLED/UNKNOWN`）→ 五字段的映射由谁定：Rust 返回三态 + 字节数就够，还是 Rust 也报 `partial`？ | 只由 Kotlin 定；Rust 只返回三态与已读/已写字节数，产物由 Kotlin 回读 | 写入类"取消后可能已写一半"会被误报成未执行，触发重放 | 双方 |
| O5 | 停用后兜底入口的形态：法定许可/安全恢复是"宿主固定入口 + 模块只做呈现"，还是允许模块贡献后由宿主降级接管？ | 宿主固定入口；模块贡献只是增益，不得成为唯一入口 | 停用 F01 后许可入口消失，直接违反 A1 出口判据（R §5 A1） | 双方 |

> 冻结规则：O1–O5 逐条给出"冻结/暂不冻结 + 理由"；暂不冻结的项必须在 A1 任务单里写明临时口径与失效条件，不得沉默带过。

---

## 8. G3 出口检查单

- [ ] C01/C02 接口表（§2.3、§3.1）双方逐条确认或改注。
- [ ] 五字段字段表（§4.3）、兼容策略（§4.4）、投影表（§4.6）逐条确认；`NOT_SENT`/`REJECTED` 的区分方案（O1）有结论。
- [ ] 反例 CE-1…CE-4 被接受为契约测试向量，进入 A1 任务单。
- [ ] 开放项 O1–O5 全部关闭或明确"暂不冻结 + 临时口径"。
- [ ] 投影表被指定为**唯一**投影依据；仓库内不得出现第二份五态映射。
- [ ] 非实现者评审（R §9 R1：A1 出口由非实现者逐条核对"停用后安全兜底"与"未知状态语义"）。
- [ ] `python3 tools/check-document-links.py` 0 error（本文链接按 2026-11-12 工作树校验）。

> 本检查单只覆盖 G3。A1 的其它开工条件（G0、G1、G2、G4、G5）与出口判据（7 步闭环、全量单测、边界脚本）仍按路线图 §11.1 与国家/仓库门禁执行；**契约草案通过不等于 A1 可开工**（R §11.1；M §5）。

---

## 9. 来源索引（本文引用到的原文位置）

| 引用 | 位置 | 本文用到的结论 |
|---|---|---|
| ADR-0004 决定一 | [ADR-0004](../../adr/0004-kotlin-rust-direction.md) | 重切 + 增量 Kotlin；按模块切换、不按文件混写 |
| ADR-0004 决定二 | 同上 | 21 / 9 / 1 三个口径只做映射、禁止相减 |
| ADR-0004 决定四 | 同上 | 五字段取值、枚举仅投影、失败域矩阵、Rust 三类纯函数、无 `execute`/`argv` |
| ADR-0004 决定五 | 同上 | 数据"双读单写"、覆盖更新 I1–I10 不变量（本文只引用"不抬既有容器版本号"） |
| ADR-0004 决定六 | 同上 | 允许/禁止两层表述；C01/C02 先落编译期接口 + 最小登记表 |
| 路线图 §2 / §2.1 | [迭代路径](iteration-roadmap-20261112.md) | 18/21/9/1 映射；A1 覆盖 C01/C02/C03/F01/A06、M05/M18/M01/M03/M20 |
| 路线图 §4 G4/G7/G8 | 同上 | 未知结果不重放；Rust 无执行入口；日志统一脱敏出口 |
| 路线图 §5 A1 | 同上 | A1 交付物与出口判据（停用后许可入口仍可达；无新增敏感能力） |
| 路线图 §9 R1 | 同上 | 契约定错波及后续；A1 出口由非实现者核对 |
| 路线图 §11.1 | 同上 | G0–G5 "缺一不开工"；G3 定义；可先行只含文档/评审与 G0 工程装配 |
| 回复 §5.1 | [回复](reply-to-18-modules-20261112.md) | 五字段表；取消后可能已执行的显式组合；`PARTIAL` 不再承担双义 |
| 回复 §10 第 1–4 条 | 同上 | 删除 `execute`/`argv`；HMAC 定位；五字段采纳；失败域矩阵 |
| 回复 §12.2 | 同上 | 产品形态允许/禁止两层表述 |
| 修订建议 §2 | [修订建议](18-modules-revision-notes-20261112.md) | C03 六态自相矛盾；五字段；取消作为事件 |
| 修订建议 §6 | 同上 | 布局保存与恢复不能跟着可插拔一起裁 |
| 评审稿 §3.2 | [评审主稿](README.md) | 三条硬边界（状态单点、授权在 Kotlin、证据链等价） |
| 评审稿 §6.3 | 同上 | 取消三态；panic 策略；错误映射不把字符串当判据 |
| 评审稿 §8.2–§8.4 | 同上 | Rust 接口边界；未知 tag fail-closed；Rust 持 fd/发信号/监听风险 |
| 合并回执 §2 / §5 | [G0–G1 合并回执](g0-g1-merge-receipt-20261112.md) | G3 纳入我方门禁；契约草案属"可先行"、不属于 A1 业务编码 |
| 收敛表 v0.2 | [收敛表](convergence-table-20261112.md) | M01/M03/M05/M18 的状态 owner 与编译落点；C02 不新增权威状态 |
| 代码 `CapabilityResult` | [CapabilityResult.java:7-12](../../../app/src/main/java/com/deepseekharness/app/capabilities/CapabilityResult.java) | 现有五态枚举；`executionMayHaveOccurred`（:46-48） |
| 代码 `DeviceTransportResult` | [DeviceTransportResult.java:31](../../../app/src/main/java/com/deepseekharness/app/device/DeviceTransportResult.java) | `UNKNOWN_CANNOT_PROVE_REMOTE_EXIT` |
| ADR-0003 | [ADR-0003](../../adr/0003-virtual-screen-lifecycle-and-input.md) | UNKNOWN 不重放；NOT_SENT 只能由类型化本机事实建立；平台接受输入不等于效果已验证 |
| 旧草案（已过时） | [drafts/05-safety-permissions.md:58](drafts/05-safety-permissions.md) | 曾要求 K↔R 传五态；已被 ADR-0004 决定四取代 |

---

*本文只做契约形状与判定语义，不改代码、不改数据、不承诺排期。所有数字与行号以 2026-11-12 工作树（`92acaa04` 基线）为准；冻结前以 PR 逐行评审为准。*
