# DSHA 0.2.1-rc.5-20261113.1340-dsh0.2.2-rc.1 / build364

- 数据安全与启动观测版（一处用户可见的数据正确性修复）：build364 在 build363 之后合入 RC1 迁移 P1a/P1b 与非实现者评审 P0/P1/P2 修复、K0 Kotlin 受管输入与架构边界门禁、S0 启动只读观测基线，以及外接 adb/fastboot 路径与首次 proroot 试运行修复。**用户可见的是 RC1 迁移的保守失败关闭语义**；K0 / S0 为构建与诊断基础设施，其余为设备路径修复与文档。
- RC1 迁移（数据安全，用户可见）：结果协议升级为 `resultVersion=3`，区分“本次内容副本读回”“历史完成”“写入结果未知”。只有本次流程完整读回副本才允许迁移写入；历史完成只授予无迁移写入的普通启动，且必须已有 committed 完成回执与完整 settings 账本；缓存命中绑定 generation + sourceSha256 + 期望分节集合，不再由旧布尔合成成功。
- RC1 迁移（继续）：history-only 核对不创建或更新设置分节、不覆盖用户后来修改的值，源分节缺失或 pending/writing/unknown 只返回 pending/unknown；已派发但终态未知的写入不再自动重放；保留数据清单按真实 current 形状投影历史完成 / 待处理 / 未知，未知记录仍可只读查看，不授予自动恢复或删除。
- 启动只读观测基线（工程）：S0 为停止残留、扫描、guest helper、准备命中与 Node→鉴权链路增加只读计数观测，只进入启动诊断与既有日志；不改变启动顺序、等待时间与权限门禁。
- Kotlin 受管输入（工程）：K0 让受管源树中的 `.kt` 进入 `runtimeId` 与架构边界门禁，并补充 Kotlin 编译 / 格式与重名负控；不改变运行行为。
- 设备路径与维护试运行修复：外接设备库搜索补充 `usr/lib/aarch64-linux-gnu/android`，adb / fastboot 在外接环境可被找到；首次 proroot 受管运行时试运行不再因 profile 枚举 ENOENT 崩溃，维护页不再多一次失败试运行。
- 文档与验收（仅文档）：build363 真机覆盖验收回执、运行时 trial 首次失败分析、RC1 迁移设计与对外回复、启动链路评估纳入文档索引。
- 覆盖升级影响：受管启动输入与受管运行时变化，`runtimeId` 更新为 **`bd0016c33e807a8e60cec8b3267e78fef0d003f618c083227cb651bd274eaabf`**（489 个启动输入、205 个资产输入）；**363→364 覆盖升级后首次启动会进入一次运行环境维护页**并执行受管运行时试运行，属预期，个人数据保留。内置 `dsh-web-mobile` 3.3.5 与默认启用不变，数据格式不升级。
- 固定构建输入：锁保持 `fixed-inputs-20261105e`。K0 修改了 `tools/runtime_input_contract.py`（dsh 运行时 recipe input），旧 provenance 在 `ci-package` 的 `rebuild_runtime:false` 下会按设计 fail-closed；本版已用本机锁重建运行时，归档字节仍为 `c1bd6833…`，仅 `dsh-runtime.inputs.json` 的 recipe 摘要更新。release-production 使用 `rebuild_runtime:true`，会在还原固定输入后按锁重建并重算描述符，因此不受旧包 provenance 影响；`ci-package` 需在发布新的固定输入 bundle 后以显式 URL + SHA-256 触发（本分支候选 bundle `sha256 2c8dc3fa…`，尚未上传，故不更新锁）。
- 版本与范围：版本码提升为 364（基于已发布的 build363，tag `dsha-a-su-build363-20261113-170000` @ `d0bc1b0b`）；DSHA 修订号保持 `0.2.1-rc.5`，内置 DSH 为 `0.2.2-rc.1`；包名 `io.github.sunsetrne.dsha`、Mod 自有证书与签名方案不变。设备 shell 白名单与执行期护栏不放宽，授权 fail-closed 语义、密码文本硬阻断与敏感界面策略不变。目标 tag `dsha-a-su-build364-20261113-134000` 由 release 工作流在用户确认后创建。
- **未验证 / 未验收**：build364 发布准备只通过本地静态门禁与定向测试；全量单测、lint、未签名构建、测试包与正式签名由 release 工作流执行，结果以 CI / Release 回执为准；**未做真机覆盖安装验收**。验收项是 363→364 覆盖安装成功、首次启动进入一次运行环境维护页并完成、数据完好、到达 READY，以及 RC1 迁移在普通启动下的提示与保留数据清单符合预期。验收只允许 `.debug` / `.dev` 包，本机不装正式包、不动正式数据。
- 验证范围：发布身份与前置检查（versionCode 364 / versionName 与 `ci/release-identity.json` 一致；`check-release-prerequisites.py` PASS）、三身份交叉核对（`test-release-identity.py`）、`generate-changelog.py --check`（十语 raw changelog 与本说明一致）、`UpdateVersionPageTest` 断言更新、`prepare-runtime-descriptor.py --check`（`bd0016c3…`，489 个启动输入）与 descriptor 源核验、全树 Java 与 Kotlin 格式、源卫生、UI i18n、文档链接、`test-ci-workflows.py`、`test-release-signing.py`、`test-core-boundaries.py`，以及 RC1 的 Python 与真实 SettingsForms CJS 回归；全量单测、lint、未签名构建与正式签名由本轮 release 工作流执行。正式签名 assemble 不在本机执行（无发布 keystore）。
- 十语种更新说明见 [build364 更新说明](build364-changes.zh.md)。
