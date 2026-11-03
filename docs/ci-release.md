# CI 构建与发布（公开仓库正式成品）

更新：2026-11-02 / build300（节点化）。工作流：[`.github/workflows/release-production.yml`](../.github/workflows/release-production.yml)。
目标仓库：`SunsetRNE/DSHA-Sunset-mod`（`main` 分支）。本文说明如何用 GitHub Actions 产出并发布
**正式签名成品**（`io.github.sunsetrne.dsha`），以及需要哪些仓库 Secrets、产物如何核验。

## 1. 边界

- 只做 **Standard（Android 11+ / arm64）正式包**；不运行 low/Gecko 兼容线，不再有独立上岛包。
- 只由 `workflow_dispatch` 显式触发；运行必须绑定精确 40 位 commit，且该 commit 就是 `main` 分支当前 HEAD。
- 发布目标是 **tag + GitHub prerelease**（预发布），不覆盖历史 Release，不创建正式 stable release。
- 固定输入（约 664 MiB，11 项）不进 git，由 [固定构建输入](fixed-build-inputs.md) 的预发布资产按 SHA-256 还原。
- 未验证范围：工作流通过只证明该 commit 在 GitHub 托管的 `ubuntu-24.04` 上编译、门禁、单测、签名与发布流程成功；
  不证明真机安装、覆盖升级、厂商 ROM 兼容、功耗或外部模型服务。

## 2. 触发参数

| 参数 | 要求 | 示例 |
|---|---|---|
| `release_tag` | 新 tag，格式 `dsha-a-su-build<版本码>-YYYYMMDD-HHMMSS` | `dsha-a-su-build300-20261101-203000` |
| `expected_sha` | `main` 上待发布的完整 40 位 commit | `0123abcd…` |

`release_tag` 对应的日历时间必须合法；tag 已存在时工作流在任何构建前失败，不会覆盖已有发布。

```bash
gh workflow run release-production.yml \
  --repo SunsetRNE/DSHA-Sunset-mod \
  --ref main \
  -f release_tag=dsha-a-su-build300-20261101-203000 \
  -f expected_sha=<40位commit>
```

## 3. 仓库 Secrets

正式签名私钥只保存在仓库 Actions Secrets（写入后不可读回），不进仓库、不进日志：

| Secret | 内容 |
|---|---|
| `DSHA_KEYSTORE_B64` | Mod 发布 keystore（`dsha-a-su-release.jks`）的 base64 |
| `DSHA_KEYSTORE_PASSWORD` | store password |
| `DSHA_KEY_PASSWORD` | key password（通常与 store password 相同） |
| `DSHA_KEY_ALIAS` | 默认 `dsha-a-su` |

```bash
base64 -w0 dsha-a-su-release.jks | gh secret set DSHA_KEYSTORE_B64 \
  --repo SunsetRNE/DSHA-Sunset-mod
gh secret set DSHA_KEYSTORE_PASSWORD --repo SunsetRNE/DSHA-Sunset-mod --body "$PW"
gh secret set DSHA_KEY_PASSWORD --repo SunsetRNE/DSHA-Sunset-mod --body "$PW"
gh secret set DSHA_KEY_ALIAS --repo SunsetRNE/DSHA-Sunset-mod --body dsha-a-su
```

runner 把 keystore 解码到 `$RUNNER_TEMP`（权限 077），构建后 `if: always()` 删除；口令用 `::add-mask::` 遮蔽。
证书的公开 SHA-256 记录在 [`ci/release-identity.json`](../ci/release-identity.json)，私钥本身不公开。

## 4. 工作流节点

工作流拆为节点（结构与耗时依据见 [CI 节点图](ci-nodes.md)），每个节点都 checkout `expected_sha`：

1. **bind**：`expected_sha == GITHUB_SHA`、tag 格式与日历时间、固定输入锁（`ci/sunset-inputs.lock.json`）解析、
   `ci/release-identity.json` 的包名检查；tag 或 Release 已存在时在任何构建前失败。
2. **并行门禁**（都依赖 bind）：
   - **contracts**：固定输入/描述符/第三方声明/内置插件/凭据路径/测试清单/源码整洁/UI i18n/changelog/文档链接。
   - **java-format**：`tools/format-java-tree.py --check`。
   - **host-regression**：设备桥、shell 策略、虚拟屏 anchor、插件事务、发布与 CI 脚本、宿主测试清单等子集。
   - **inputs**：按锁 URL + SHA-256 还原 11 项、按 npm lock 重建受管 DSH 运行时并重算描述符，打包为同 run 交接。
3. **unit** 与 **lint**（依赖 inputs，二者并行）：
   完整单测 `-Pdsha.sunsetTest=true -Pdsha.sourceOnlyTests=false`，要求 0 failure / 0 error / 0 skipped，
   且七个外部夹具用例各执行一次并通过；`:app:lintStandardRelease`。
4. **build-signed**（依赖以上全部）：还原交接、对下载的 JUnit XML 重新核对并写出 `out/full-unit-tests.json`，
   用 Secrets 配置 `DSHA_KEYSTORE*` 执行 `:app:assembleStandardRelease`（不带 `-Pdsha.sunsetTest`）；
   `apksigner` 证书 SHA-256 必须等于 `ci/release-identity.json`，`aapt2 dump badging` 核对包名、
   versionCode/versionName、minSdk 30、arm64、非 debug、标签 `DSH-A-Su`；生成证据 zip、`build-info.json`、
   `SHA256SUMS` 与发布说明，`if: always()` 删除私钥，再以 1 天保留的 artifact 交给 publish。
5. **publish**（唯一有 `contents: write` 的节点，不 checkout、不接触 Secrets）：核对 `SHA256SUMS` 与
   `build-info.json` 的 commit / run / attempt，再创建 tag（指向该 commit）+ prerelease 并上传 APK、证据 zip、
   `build-info.json`、`SHA256SUMS`。

## 5. 产物与核验

| 产物 | 说明 |
|---|---|
| `dsha-a-su-<commit8>.apk` | 正式签名成品，包名 `io.github.sunsetrne.dsha` |
| `dsha-a-su-build-evidence.zip` | 单测 XML、Lint、签名输出、badging、`build-info.json` |
| `build-info.json` | commit、run ID、APK/固定输入 SHA-256、证书、版本、单测汇总 |
| `SHA256SUMS` | APK/证据/构建信息的 SHA-256 |

安装或分发前至少核对：

```bash
sha256sum -c SHA256SUMS
apksigner verify --print-certs dsha-a-su-<commit8>.apk   # 证书 SHA-256 必须为 a4b0413c…5d5d
aapt2 dump badging dsha-a-su-<commit8>.apk | head -3      # io.github.sunsetrne.dsha / 209
```

`build-info.json` 的 `assetBundleSha256` 必须等于 [固定构建输入](fixed-build-inputs.md) 记录的 bundle SHA-256，
`certificateSha256` 必须等于 `ci/release-identity.json`，`commit` 必须与 tag 指向的 commit 一致。

## 6. 失败与重试

- tag 已存在、`expected_sha` 与运行 commit 不符、固定输入 SHA-256 不符、证书不符、单测有失败/跳过、
  Lint/格式/文档链接失败：工作流在创建任何 tag 或 Release 前失败，现场日志与上传证据保留。
- 失败后**不要**使用同一 tag 重跑：修正源码后换新 commit 与新 tag；旧 tag 若已创建需人工确认后再处理。
- publish 只接受本 attempt 的 build-signed 产物：单独重跑 publish 会因 attempt 不符被拒绝（避免对结果未知的发布自动重放）。
  先核对远端 tag/Release 实际状态，再用 Re-run all jobs 或换新 tag 重新触发。
- 上传资产失败时 Release 可能只创建了 tag：核对 `gh release view <tag> --json assets`，不要盲目 `--clobber`。
- 本工作流不下载/安装到手机，不做真机验收；设备侧结论必须另附实际 APK、操作与回执。

## 7. 与本地构建的关系

本地 `./gradlew :app:assembleStandardRelease` 与 CI 使用相同的 `verifyPublishCertificate` 与
`ci/release-identity.json`，但 CI 额外要求固定输入、完整单测、Lint、格式与证据归档全部通过。
本地中间包、未签名 CI 包（`-Pdsha.unsignedPackage=true`）不得当作正式交付。
