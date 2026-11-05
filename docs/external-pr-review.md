# 外部 PR / 外部合并的强制审查流程

> 核验日期：2026-11-05。基线：build325（`f5c63d1a`）。本流程由用户明确要求，适用于**所有来自外部贡献者的 PR**与**任何来自外部的合并请求**（fork PR、patch、外部分支的 merge / rebase / cherry-pick，以及随之变化的生成文件）。它是发布门禁的一部分，不能由 CI 绿灯、作者自述或“看起来没问题”替代。

## 硬性规则

1. **专职审查会话**：每个外部 PR / 外部合并都必须由一个**专门的 AI 审查会话**逐行审阅**全部 diff**。不是抽样，不是只看 CI 结果，不是只看变更摘要。
2. **两种结论**：审查结论只能是 **APPROVE**（确认无误才允许合并）或 **REQUEST_CHANGES**（打回）。没有“有条件通过”或“先合并后补”。
3. **CI 全绿不等于可合并**：CI 只能证明已跑过的检查通过，不能证明安全边界、逻辑完整性、测试覆盖与仓库不变式。
4. **冲突不等于放过内容问题**：`CONFLICTING` 必须先 rebase（在审查会话自己的临时 worktree 中进行），再由审查会话**重新审阅生成文件与受影响 diff**；不得直接把“冲突已解决”当作通过。
5. **审查会话不合并、不 push 作者分支**：审查会话只读、只跑检查、只提交 review 意见；合并仍需**用户在主会话明确同意**，由主会话执行。
6. **修完再审**：作者每次 push 后，审查会话必须重看新增 diff 与受影响上下文；不得只审增量就沿用旧 APPROVE。

## 流程

1. **固定审查对象**：记录 PR 号、head sha、base sha、提交范围与作者；核对 base 是否为当前 `origin/main`。若已落后，先要求作者 rebase，或在审查会话的临时 worktree 中 rebase 后重看**全部生成文件差异**（不 push 作者分支）。
2. **获取完整 diff**：`git diff <base>...<head>`（含重命名、删除、二进制与生成文件）。禁止只看 GitHub 的默认折叠视图或 `--stat`。本机 proroot 下 `git show/diff --stat` / `--numstat` 对某些提交或 ≥8 路径组合会 exit 139 且 stdout 为空（`[proroot] SIGSEGV`）；必须检查 rc / stderr，不要用 `| head` 吞 rc，改用 `--name-status` / `--dirstat` / ≤7 路径分片，或 `python3 tools/git_guard.py -- <git 参数>`，详见[运行引擎](runtime-engines.md)。
3. **逐行审阅**：对每一处改动说明“改了什么、为什么、边界在哪、失败会怎样”。生成文件（`runtime-descriptor.json`、`*.inputs.json`、`managed-package-proofs.json`、`THIRD_PARTY_NOTICES.md`、`ci/sunset-inputs.lock.json`、`tools/host-tests.manifest.json`、`CHANGELOG.md`、i18n、`docs/**` 索引）必须**按仓库脚本重算并对比**，不能接受手改结果。
4. **跑门禁**：至少运行 `python3 tools/run-host-tests.py --check-manifest`、`python3 tools/check-source-hygiene.py`、`python3 tools/generate-changelog.py --check`、`python3 tools/check-document-links.py`；涉及 Java 时加 `python3 tools/format-java.py --check --files changed` 与定向单测；涉及受管补丁 / 内置插件时按[覆盖升级与维护契约](overwrite-upgrade-and-maintenance.md)的发布前清单完整执行。门禁结果只是审查输入，不能替代逐行判断。
5. **按清单检查**（见下节），把每个问题定位到 `file:line`。
6. **出结论**：APPROVE 或 REQUEST_CHANGES。REQUEST_CHANGES 的意见必须直接可执行；APPROVE 也必须写明实际审阅范围与未验证项。
7. **合并**：只有用户在主会话明确同意后才合并。审查会话在 review 中声明“未合并、未 push 作者分支”。

## 审查清单模板

| 维度 | 逐项检查 |
|---|---|
| 安全边界 | 凭据 / token / 密钥是否进入仓库、日志或备份；路径是否可逃逸（软链接、`..`、绝对路径）；设备命令是否绕过 `DeviceShellPolicy`；权限是否默认最小、可撤销；网络来源、重定向与 TLS 是否收窄；签名与包名是否被削弱 |
| 桥接口 | 3090 端点、鉴权、`BRIDGE_PROTOCOL` 特性检测、参数解析（逐参数匹配、空值 ≠ 缺失）、请求/响应上限、LAN 与 ADB 边界 |
| 资源与并发 | 锁顺序、线程 / 协程、进程与 FD 泄漏、退出屏障、`RuntimeTasks` / 维护锁、超时后是否误判退出、旋转 / 重建是否重复提交 |
| 错误处理与降级 | 失败是否保留原件；UNKNOWN 是否被当成成功；是否自动重放未知动作；降级路径是否越权；失败文案是否误导 |
| i18n 与 UI | 文案是否走 `messages.json` / `UiText`，中英是否对应，长度是否超限，是否有硬编码 UI 字面量，短屏 / 大字体 / 横屏是否可滚动 |
| 测试覆盖 | 新增行为是否有单测 / 契约测试；失败与负控是否覆盖；测试是否登记 `tools/host-tests.manifest.json`；是否只测手写函数而没跑真实产物 |
| 仓库不变式 | 是否触碰 [AGENTS.md](../AGENTS.md) 的锁定不变式；是否破坏覆盖安装 / 维护门禁 / 停止与出生身份 / 备份范围 / 文档链接等既有契约 |
| 生成文件与锁定 | `runtime-descriptor`、inputs、proofs、notices、fixed-inputs 锁、测试清单、CHANGELOG 是否由脚本重算且相互一致；固定输入变化是否按流程重新打包 / 上传 / 更新锁 |
| 文档一致性 | 索引与专题页是否同步；版本身份是否与实际 `app/build.gradle` / `ci/release-identity.json` 一致；历史记录是否被改写；中英文是否两边都写 |
| 构建与发布 | 包名、签名、`versionCode`、minSdk、arm64、flavor 是否保持；是否引入调试包 / 卸载重装 / 数据清除；Release 资产与摘要是否匹配 |
| 规模与边界 | 是否有无关改动、格式化噪声、历史原文改写、生成文件手改、应拆分的混合提交；是否把测试包 / 本机路径 / 私有材料带进仓库 |

## 打回意见必须包含什么

每条意见至少写清：

- **位置**：`file:line`（精确到行或函数；跨文件问题列出全部位置）。
- **问题**：事实与影响（会触发什么故障、越什么权、丢什么数据、破坏哪条不变式）。
- **修法**：可执行的最小修复建议；涉及生成文件的给出重算命令与期望摘要 / 差异。
- **严重级别**：
  - **P0**：阻断合并。安全问题、数据丢失 / 损坏、不可启动、凭据或签名边界被破坏、未知动作被自动重放。
  - **P1**：合并前必须修。必要逻辑缺失、边界错误、无测试覆盖的新行为、生成文件不一致、破坏仓库不变式。
  - **P2**：建议修。可合并但应记录或后续跟进；不能吞掉不写。
- **验证方式**：对应门禁命令或需要补的测试 / 真机项；未验证的范围明确标注。

## APPROVE 的最低条件

- 全部 diff（含生成文件、二进制、重命名与删除）已逐行审阅，并在结论中列出审阅范围。
- 没有未解决的 P0 / P1；P2 已记录且用户或主会话明确接受。
- 契约整组、Java 格式（如改动 Java）、定向单测通过；涉及受管补丁 / 内置插件时完成发布前清单。
- 真机 / 外部服务 / 多机型未验证的部分如实写“未验证”，不得写成通过。
- 声明审查会话**未合并 PR、未 push 作者分支**；合并仍需用户在主会话明确同意。

## 明确禁止

- 用“CI 全绿”“作者说测过了”“改动不大”代替逐行审查。
- 抽样审查，或只看新增文件而不看被修改的既有逻辑。
- 审查会话自行合并、push 作者分支、强推、关闭 PR 或改写作者提交。
- 冲突时直接重置分支或忽略被自动合并的生成文件差异。
- 把外部 PR 的“已合并”当成已审查；合并动作本身不能倒推审查已发生。

## 相关文档

- [覆盖升级、运行时维护与插件依赖契约](overwrite-upgrade-and-maintenance.md)（发布前必跑清单、真机验收、受管补丁 / 内置插件变更）
- [贡献流程](../CONTRIBUTING.md)、[工程规范](engineering-standard.md)、[安全模型](security-model.md)
- [CI 构建与发布](ci-release.md)、[固定构建输入](fixed-build-inputs.md)
- 当前已知的外部 PR（PR #3 SAF、PR #5 横屏 Dock）同样必须走本流程；具体审查会话由主会话分派，本文不记录会话语义。
