# CI 构建与发布（私有维护仓库正式成品）


更新：2026-11-04 / build308，版本 `0.2.1-rc.5-20261104.1412-dsh0.2.1-rc.5`。工作流：[`.github/workflows/release-production.yml`](../.github/workflows/release-production.yml)。
目标仓库：`ym2025szz/DSHA-prerelase`（`codex/prerelease-190` 分支）。本文说明如何用 GitHub Actions 产出并发布
**正式签名成品**（`dsh.android.client`），以及需要哪些仓库 Secrets、产物如何核验。

## 1. 边界

- 只做 **Standard（Android 11+ / arm64）正式包**；不运行 low/Gecko 兼容线，不再有独立上岛包。
- 只由 `workflow_dispatch` 显式触发；运行必须绑定精确 40 位 commit，且该 commit 就是 `codex/prerelease-190` 分支当前 HEAD。
- `bind` 节点同时检查仓库为 `ym2025szz/DSHA-prerelase`、仓库实际为 private、分支为 `refs/heads/codex/prerelease-190`；其他仓库、公开 fork 或分支不能进入签名链。
- 普通验证节点只读且不接收签名 Secrets。只有 `build-signed` 读取历史 E7E3 私钥；只有 `publish` 拥有 `contents: write`，且不 checkout、不签名。
- 发布目标是 **tag + GitHub prerelease**（预发布），不覆盖历史 Release，不创建正式 stable release。
- 固定输入（约 664 MiB，11 项）不进 git，由 [固定构建输入](fixed-build-inputs.md) 的预发布资产按 SHA-256 还原。
- 未验证范围：工作流通过只证明该 commit 在 GitHub 托管的 `ubuntu-24.04` 上编译、门禁、单测、签名与发布流程成功；
  不证明真机安装、覆盖升级、厂商 ROM 兼容、功耗或外部模型服务。

## 2. 触发参数

| 参数 | 要求 | 示例 |
|---|---|---|
| `release_tag` | 新 tag，格式 `dsha-build<版本码>-YYYYMMDD-HHMMSS` | `dsha-build308-20261103-130000` |
| `expected_sha` | `codex/prerelease-190` 上待发布的完整 40 位 commit | `0123abcd…` |

`release_tag` 对应的日历时间必须合法；tag 已存在时工作流在任何构建前失败，不会覆盖已有发布。

```bash
gh workflow run release-production.yml \
  --repo ym2025szz/DSHA-prerelase \
  --ref codex/prerelease-190 \
  -f release_tag=dsha-build308-20261103-130000 \
  -f expected_sha=<40位commit>
```

## 3. 仓库 Secrets

正式签名私钥只保存在仓库 Actions Secrets（写入后不可读回），不进仓库、不进日志：

| Secret | 内容 |
|---|---|
| `DSHA_KEYSTORE_B64` | 历史 E7E3 APK 发布 keystore（不是更新清单签名密钥）的 base64 |
| `DSHA_KEYSTORE_PASSWORD` | store password |
| `DSHA_KEY_PASSWORD` | key password（通常与 store password 相同） |
| `DSHA_KEY_ALIAS` | 默认 `androiddebugkey` |

```bash
base64 -w0 dsha-release.jks | gh secret set DSHA_KEYSTORE_B64 \
  --repo ym2025szz/DSHA-prerelase
gh secret set DSHA_KEYSTORE_PASSWORD --repo ym2025szz/DSHA-prerelase --body "$PW"
gh secret set DSHA_KEY_PASSWORD --repo ym2025szz/DSHA-prerelase --body "$PW"
gh secret set DSHA_KEY_ALIAS --repo ym2025szz/DSHA-prerelase --body androiddebugkey
```

runner 把 keystore 解码到 `$RUNNER_TEMP`（权限 077），构建后 `if: always()` 删除；口令用 `::add-mask::` 遮蔽。
证书的公开 SHA-256 记录在 [`ci/release-identity.json`](../ci/release-identity.json)，私钥本身不公开。
工作流与 Gradle 均保留默认 alias `androiddebugkey`；历史别名不表示正式 APK 可调试。持有签名 Secrets 的节点通过 `gradle-cache: 'false'` 禁止写入共享 Gradle 缓存。

## 4. 工作流步骤

流程为 `bind → (contracts ‖ java-format ‖ host-regression ‖ inputs → (unit ‖ lint)) → build-signed → publish`。这些是工作流的执行要求；本轮没有在线运行这些节点。

1. **bind**：执行上述私有仓库/分支守卫，核对 `expected_sha == GITHUB_SHA`、tag 格式及日期、固定输入锁与 `dsh.android.client` 身份；任何构建前拒绝已存在的 tag。
2. **contracts / java-format / host-regression**：分别复用 `_node-contracts.yml`、`_node-java-format.yml`、`_node-host-tests.yml`，检查源契约、固定版本格式化及明确选择的无大输入宿主回归。
3. **inputs**：复用 `_node-inputs.yml`，按 URL + SHA-256 还原固定输入、按锁重建受管运行时并更新描述符。`ci-handoff.py` 只打包允许的输入与证明；还原时核对 commit、成员集合和逐项摘要。
4. **unit / lint**：依赖同一 inputs 交接并分别复用 `_node-unit-tests.yml` / `_node-lint.yml`。发布单测为 full、`require_fixtures: true`、单 fork；测试前准备锁定的 Robolectric SDK，单测离线运行。Lint 使用正式身份配置，不签名。
5. **build-signed**：只有 bind、contracts、format、host、inputs、unit、lint 全部成功才开始。还原同 commit 的输入和测试/Lint报告，用 `verify-unit-results.py --mode full --require-fixtures` 重新核对实际 XML 后生成 `out/full-unit-tests.json`；随后才读取 Secrets、执行 `:app:assembleStandardRelease`，不带测试变体。
6. **精确成品与证据**：`apksigner` 的证书必须等于 release identity，`aapt2` 核对包名、版本、minSdk 30、arm64、非调试及 `DSHA` 标签；生成 `build-info.json`、单测 XML、Lint、签名/badging证据 ZIP 与 `SHA256SUMS`，经仅保留一天的 `release-bundle` artifact 交接。
7. **publish**：不 checkout、不读签名私钥；下载成品，重新核对 SHA256SUMS、commit、run ID/attempt 和版本绑定，再复查 tag 不存在，创建指向精确 commit 的 tag + prerelease 并上传四类交付资产。

旧 `sunset-3090-q.yml`、`sunset-release.yml`、`terminal-regression.yml` 及其发布脚本已按上游退休，不再作为本轮发布入口。节点职责见 [CI 节点图](ci-nodes.md)；该上游说明中的历史 `main`/Mod 测试包示例不覆盖本页的私有分支守卫、`dsh.android.client.SunsetRNE` 测试身份或 E7E3 签名。

## 5. 产物与核验

| 产物 | 说明 |
|---|---|
| `dsha-<commit8>.apk` | 正式签名成品，包名 `dsh.android.client` |
| `dsha-build-evidence.zip` | 单测 XML、Lint、签名输出、badging、`build-info.json` |
| `build-info.json` | commit、run ID、APK/固定输入 SHA-256、证书、版本、单测汇总 |
| `SHA256SUMS` | APK/证据/构建信息的 SHA-256 |

安装或分发前至少核对：

```bash
sha256sum -c SHA256SUMS
apksigner verify --print-certs dsha-<commit8>.apk   # 证书 SHA-256 必须为 e7e3a31a…53f5
aapt2 dump badging dsha-<commit8>.apk | head -3      # dsh.android.client / 294
```

`build-info.json` 的 `assetBundleSha256` 必须等于 [固定构建输入](fixed-build-inputs.md) 记录的 bundle SHA-256，
`certificateSha256` 必须等于 `ci/release-identity.json`，`commit` 必须与 tag 指向的 commit 一致。

## 6. 失败与重试

- tag 已存在、`expected_sha` 与运行 commit 不符、固定输入 SHA-256 不符、证书不符、单测有失败/跳过、
  Lint/格式/文档链接失败：工作流在创建任何 tag 或 Release 前失败，现场日志与上传证据保留。
- 失败后**不要**使用同一 tag 重跑：修正源码后换新 commit 与新 tag；旧 tag 若已创建需人工确认后再处理。
- 上传资产失败时 Release 可能只创建了 tag：核对 `gh release view <tag> --json assets`，不要盲目 `--clobber`。
- 本工作流不下载/安装到手机，不做真机验收；设备侧结论必须另附实际 APK、操作与回执。

## 7. 与本地构建的关系

本地 `./gradlew :app:assembleStandardRelease` 与 CI 使用相同的 `verifyPublishCertificate` 与
`ci/release-identity.json`，但 CI 额外要求固定输入、完整单测、Lint、格式与证据归档全部通过。
本地中间包、未签名 CI 包（`-Pdsha.unsignedPackage=true`）不得当作正式交付。
