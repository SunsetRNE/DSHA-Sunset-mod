# /relay 交接草稿（2026-11-13）

> 用途：本会话（`01f8b66d`）上下文已接近 relay 阈值，交接给下一会话继续。**这是草稿，不是验收记录。**
> 配套：[handoff-20261113.md](handoff-20261113.md)（更细的证书/证据/命令）、[迭代路径看板](iteration-roadmap-20261112.md)、`.agents/worktrees/dsh-c0dbe864-8767/.SESSION-DONE`。

## 1. 现在到哪了

| 项 | 状态 |
|---|---|
| main | `51955558`（文档） / 代码基线 `017dd008`；已推送 |
| build360 | ✅ 已发布：tag `dsha-a-su-build360-20261112-180000`，APK `28b186ad…`，release run 43199896721 |
| G0 Rebirth | ✅ 通过：证书 `912ca1c1…9faec0`、signed 候选 run 43294241607、一加 12 安装/共存/首启成功；E05 无前代豁免 |
| A1 契约底座 + 关于/许可 | ✅ 出口：full dry run 43304710980（0 skipped）、非实现者 APPROVE、路线图 1/8 |
| G1-B 本仓 host runtime | ⚠ 94/128 PASS（run 43292602074），未全绿；不阻塞已开工的 A1/A2 |
| build361 | ⬜ 未做（A1 代码尚未进入正式包） |
| A2 存储与文件 | ⬜ 未启动 |

## 2. 下一步（建议顺序）

1. **build361 发布准备**（把 A1 带进正式包）：
   - versionCode 361、versionName `0.2.1-rc.5-<时间戳>-dsh0.2.2-rc.1`；
   - 三份身份文件 + `ci/release-identity.json` 同步，`docs/releases/build361-changes.<10 语>.md`，`generate-changelog.py` 生成 raw；
   - `UpdateVersionPageTest` 断言同步（build361 文案）；
   - full 门禁用 `release-production.yml dry_run=true` 预跑，再正式 dispatch（需用户当次授权）。
2. **A2 存储与文件**：SAF 授权簿 + 文件工作台 + 受控流/限额；契约层复用 A1 的 registry/route/layout，不再改形状（A1 已冻结）。
3. **A1 已知 P2**（非阻塞）：registry→lease 反向锁序、close finally 二次异常、About 测试 hidden 状态恢复——建议 A2 首提交顺手修。
4. **web-search `BLOCKED_SEMANTIC_DIFF`**：独立立项评审，不在 A1/A2 关键路径。
5. **Kotlin 工具链批次**：ADR-0004 已批准但 A1 用 Java 先行；引入 Kotlin 时需同批锁 plugin/stdlib/ktfmt 并改格式门禁，单独立任务。

## 3. 已定决策（不要重开）

- 产品形态：内部工程模块化 + 随包可信内置模块（无第三方动态加载/市场）。
- Rebirth：独立 `applicationId` + 我方固定测试证书 + 与正式包共存；E05 无前代豁免。
- G3 契约 v0.1 内部冻结（五项形状差异 deferred 到 A2/A8）；不再等对方 5%。
- A1 出口口径：契约单测 + 宿主固定 About/许可入口；卡片/排序接线归 A2/A8。
- 发布红线：正式包同包名/同签名/versionCode 单调；build360 已发，build361 必须 >360。

## 4. 环境/工具（本机特有）

- 一加 12（PJD110/Android 16）经 **USB 主机**连本机；root 下用 Termux adb：
  `/data/local/tmp/termux-usr/bin/adb`，环境 `HOME=/data/local/tmp/adbhome LD_LIBRARY_PATH=/data/local/tmp/termux-usr/lib ANDROID_ADB_SERVER_PORT=5038`，设备序列 `f55ffc45`。
- 本机 aarch64 无法执行 x86_64 aapt2：APK 构建/签名/真实核验只能走 GitHub x86_64 CI；本机只做 Gradle 配置检查与 Python/静态门禁。
- `gh` 下载 artifact/log 的 Azure SAS 可能 403：从 `gh api .../logs|.../zip` 的报错里取 URL，再用 `curl` 直连可成功（已在会话中验证）。
- 证书私钥只在 GitHub Secrets `REBIRTH_*` + 本机 `/root/.dsha-rebirth/`（0700，仓库外）；公钥 `ci/rebirth-test-cert.pem`。

## 5. 需要用户拍板的（下一会话开头问）

1. build361 什么时候发、是否同轮做十语说明与 full dry run；
2. A2 的 owner/排期，以及是否先修 A1 的 3 个 P2；
3. 对方 5% 线是否继续完全忽略（建议：是，只在 G3 追认时回执）。

*下一会话接手：先读本文件 + handoff-20261113.md + `.SESSION-DONE`，再 `git log -5` 与 `gh run list` 核对实际状态，不要重放任何 UNKNOWN 的推送/发布动作。*
