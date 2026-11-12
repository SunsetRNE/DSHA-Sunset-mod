# DSHA 0.2.1-rc.5-20261112.1800-dsh0.2.2-rc.1 / build360

- 发布工程维护版（G0）：build359 之后 `main` 上只有 Rebirth 发行身份工程装配、模块化线文档与 CI 变体，**没有应用源码 / 资源 / 文案改动**。build360 与 build359 的用户可见功能完全相同，升级只是版本身份与发布工程变化。
- Rebirth 隔离实验线工程装配：新增 `dsh.android.client.rebirth` 独立发行身份文件、`-Pdsha.rebirthCandidate=true` 未签名候选开关、独立 `signingConfigs.rebirth`（只读 `DSHA_REBIRTH_*`，不回落正式密钥）与候选核验器；证书尚未创建，只产 `UNSIGNED_NOT_FOR_INSTALLATION` 候选，不签名、不可安装、不是发布验收。首个候选（CI run 43188205334）12 项身份 / 清单 / 未签名核验全部 PASS。
- 模块化线文档：收敛表 v0.2（21 个状态模块的职责与失败域自拟补全）、A1 契约草案（C01/C02 + 结果五字段 + 投影表）、ADR-0004 决定八与 G0–G5 门禁状态同步。只改文档，不改运行行为。
- 受管运行时不变：本次改动不在 `runtime-descriptor` 的 `launcherInputs` 内，`runtimeId` 仍为 **`4d55973d`**（205 个输入）。**359→360 覆盖升级不会进入运行环境维护页**；如遇维护页按提示完成即可。fixed-inputs 冻结沿用锁定的 `fixed-inputs-20261105e`，内置 `dsh-web-mobile` 3.3.5 与默认启用不变。
- 版本与范围：版本码提升为 360（基于已发布的 build359 / `73890983`）；DSHA 修订号保持 `0.2.1-rc.5`，内置 DSH 为 `0.2.2-rc.1`。设备 shell 白名单与执行期护栏不放宽，授权 fail-closed 语义、密码文本硬阻断与敏感界面策略不变。build359 已作为正式包发布（tag `dsha-a-su-build359-20261111-120000` @ `73890983`）；目标 tag `dsha-a-su-build360-20261112-180000` 由 release 工作流在用户确认后创建。
- **未验证 / 未验收**：build360 只通过单测、lint 与仓库门禁；本版无应用行为变化，但仍未做真机覆盖安装验收。G0 门禁未通过：Rebirth 证书未创建、无 signed 候选、无真机安装与同线覆盖证据。验收窗口待用户安排，只允许 `.debug` / `.dev` 包，本机不装正式包、不动正式数据。
- 验证范围：发布身份与前置检查（versionCode 360 / versionName 与 `ci/release-identity.json` 一致；`check-release-prerequisites.py` PASS）、宿主 contracts 整组、`prepare-runtime-descriptor.py --check`（`4d55973d`，205 个输入）、全树 Java 格式、十语文案与文档链接检查、`UpdateVersionPageTest`；全量单测与 lint 由本轮发布工作流执行；正式签名 assemble 不在本机执行（无发布 keystore）。
