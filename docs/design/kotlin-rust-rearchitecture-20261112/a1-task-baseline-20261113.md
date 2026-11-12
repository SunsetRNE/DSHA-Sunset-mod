# A1 任务基线（G2）：契约底座 + 关于/许可

> 版本：v0.1　日期：2026-11-13　基线：build360 / `f053b02f` / `0.2.1-rc.5-20261112.1800-dsh0.2.2-rc.1`
> 状态：**A1 已开工**（用户 2026-11-13 批准：证书由我方创建、G3 自冻结、A1 不再等对方 G1 runtime）。
> 依据：[A1 契约草案](a1-contract-draft-20261112.md)（v0.1，内部冻结）、[ADR-0004](../../adr/0004-kotlin-rust-direction.md)、[迭代路径](iteration-roadmap-20261112.md) §5 A1。

## 1. 范围

| 项 | 内容 |
|---|---|
| 用户可见 | 设置 →「关于」入口内的版本/包身份/许可/外部链接；**新增第三方许可列表与正文查看**、THIRD_PARTY_NOTICES 在线链接 |
| 工程 | C01 模块协议 + C02 宿主核心的**编译期清单 + 最小运行时登记**：ModuleId/Descriptor/Factory/Contribution/generation、ModuleRegistry、ContributionCatalog、RouteTable、LayoutStore、SafeModeGate、DiagnosticsSink、BuiltinModules |
| 不在范围 | 第三方动态加载/模块市场、模块级签名/权限、新敏感能力、Kotlin/Compose、Gradle 拆模块、数据格式改动 |

## 2. 改动文件（本任务只允许这些）

| 路径 | 性质 |
|---|---|
| `app/src/main/java/com/deepseekharness/app/contract/**` | 新增（不在 `launcherInputs` 内） |
| `app/src/test/java/com/deepseekharness/app/contract/**` | 新增测试 |
| `app/src/main/java/com/deepseekharness/app/ui/AboutFragment.java` / `AboutDialog.java` / 新增 `AboutLicenses.java` | 修改/新增（`ui/` 不在 `launcherInputs` 内） |
| `app/src/test/java/com/deepseekharness/app/ui/AboutFragmentTest.java` | 新增测试 |
| 文档（本节、契约草案状态、路线图看板） | 修改 |

**零维护判据**：`launcherInputs` 实测不含 `ui/`；新包 `contract/` 不在 `launcherTrees`（runtime/backup/capabilities/screen/vscreen/device/diagnostics/plugins/saf）内。A1 不改 `util/core/runtime/DshaApp` 等已列入清单的文件，目标 `runtimeId` 不变（仍 `4d55973d`）。

## 3. 实现约束

- **语言**：本轮用 Java 17，2 空格、google-java-format；不混写 Kotlin。Kotlin 工具链（plugin/stdlib/ktfmt/离线锁）作为独立批次另立任务，不在 A1 内夹带。
- **模块协议**：只登记编译期已存在的类型实例；`ALWAYS_ON` 不可停用；停用后贡献撤销、旧 generation 回调失效；safeMode 只保留宿主兜底（含法定许可与恢复入口）。
- **布局与持久化**：`LayoutStore` 接口 + 独立 SharedPreferences 命名空间 `dsha_module_layout`（不修改 `ConfigStore`，保零维护）；未知 key 保留、坏值回默认；M01 迁移在 A8 收口时另评。
- **诊断**：`DiagnosticsSink` 只做唯一脱敏出口的接口，默认委托现有 `util/SensitiveData`；不新增日志出口、不把许可文本/路径写日志。
- **许可**：读取 `app/src/main/assets/licenses/*.txt`，单文件上限 256 KiB，UTF-8，失败类型化提示；外部链接只指向仓库内 `THIRD_PARTY_NOTICES.md`。

## 4. 验证方法

| 层 | 判据 | 谁 |
|---|---|---|
| 本地静态 | `format-java-tree.py --check`、`check-source-hygiene.py`、`check-ui-i18n.py`、`check-document-links.py` | 实现者 |
| Java 测试 | 新增 contract 单测（7 步闭环 + 反例）、AboutFragment Robolectric 测试；CI `unit-source`/full unit 执行 | CI |
| 覆盖更新 | 构建后 `prepare-runtime-descriptor.py --check` 仍为 `4d55973d`；359/360→A1 覆盖安装**不应**进维护页（若进，按 A1 出口缺陷处理） | CI + 真机 |
| A1 出口 | 契约层 7 步闭环由单测证明；**宿主固定 About/许可入口不依赖卡片渲染**（卡片隐藏/停用后入口仍可达）；无新增敏感能力；全量单测绿（full 固定输入 0 skipped）；边界脚本 PASS；文档链接 PASS；**非实现者逐条评审 APPROVE**。卡片/排序/页面接线归 A2/A8 | Lead + 独立评审 |

## 5. 回退点

- 代码回退：删除 `contract/` 新包与 About 许可区，回到 build360 行为；无数据格式变化。
- 发布回退：tag/Release；A1 目标零维护，若实测 `runtimeId` 变化则回退本轮并重新切分文件范围。
- 开关：许可区为只读展示，无运行时开关需求。

## 6. G0/G3 前提与当前事实

- G0：Rebirth 固定测试证书已于 2026-11-13 创建（SHA-256 `912ca1c1…9faec0`，alias `dsha-rebirth`），私钥只进 GitHub Secrets（`REBIRTH_*`）与本机仓库外目录，公钥 `ci/rebirth-test-cert.pem` 入库；签名候选 job 在落，**仍无 signed 候选/真机安装**，G0 门禁未通过。
- G3：契约草案 v0.1 于 2026-11-13 **内部冻结为我方基线**（O1–O5 挂起，待对方追认）；不再阻塞 G2/A1。
- G5：build360 release run 43199896721 提供全量单测 4495/0/0/0、签名构建与干净工作树，作为代码基线回执。

## 7. Owner

| 角色 | owner |
|---|---|
| 契约层实现 | a1-core（子任务；A1 出口复审 **APPROVE**） |
| 关于/许可 UI | a1-ui（子任务） |
| 集成、文档、CI、发布 | Lead |
| A1 出口非实现者评审 | contract（只写草案、未参与实现）— **APPROVE 2026-11-13** |

**A1 出口证据（2026-11-13）**：实现提交 `017dd008`，非实现者复审 **APPROVE**（P1-1/P1-2/P1-3 关闭，剩余 P2 非阻塞）；full 门禁 [dry run 43304710980](https://github.com/SunsetRNE/DSHA-Sunset-mod/actions/runs/43304710980) 全部成功——`unit (full)` 固定输入 0 skipped，含 contract 35 项与 AboutFragmentTest 15 项；contracts/java-format/host-fast/lint/build-unsigned/sign 全 PASS。非实现者复审在途（第一轮 REQUEST_CHANGES 已修复）。

**A1 收敛口径（2026-11-13 评审后）**：契约形状中 `contractVersion`、`describe()`、`ModuleHost` scope、`setEnabled` 持久化、AboutLicense 聚合五项**明确 deferred 到 A2/A8**；A1 只保证现有实现的 7 步闭环、安全模式与清理语义。卡片/排序/页面渲染接线归 A2（首个真实模块）/A8（宿主工程化）。

**A1 已知 P2（转 A2/A8 跟进，不影响出口）**：① registry→lease 与 lease→registry 反向锁序（A1 无生产 callback，latent）；② close 的 finally 中二次异常可能中断剩余清理；③ `hiddenAboutCardKeepsHostLicenseRows` 写隐藏态未在 finally 恢复（测试隔离）；④ `contractVersion`/`describe()`/`ModuleHost`/`AboutLicense` 聚合 deferred。

*本任务基线可评审；不含未定契约。契约一旦在 A1 出口冻结，A2 不得再改形状。*
