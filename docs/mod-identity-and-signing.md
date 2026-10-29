# Mod 身份、包名与签名（build209 起）

本文记录 DSH-A-Su（Sunset Mod）与原版 DSHA 的身份分离方案，以及 `verifyPublishCertificate` 的校验来源。历史 E7E3 证书的来历见 [BUILD.md](../BUILD.md) 顶部说明与 `docs/releases/` 历史回执。

## 1. 身份表

| 项 | 值 | 权威来源 |
|---|---|---|
| 应用显示名 | `DSH-A-Su` | `app/build.gradle` 的 `resValue app_name` |
| 测试变体显示名 | `DSH-A-Su Test` | 同上（`-Pdsha.sunsetTest=true`） |
| 正式包名 | `io.github.sunsetrne.dsha` | `app/build.gradle` `applicationId` + `ci/release-identity.json` `packageName` |
| 测试包名 | `io.github.sunsetrne.dsha.test` | `applicationIdSuffix '.test'` |
| versionCode / versionName | `210` / `0.2.1-rc.3-20261030.0214-dsh0.2.1-rc.3` | `app/build.gradle` |
| 发布证书 SHA-256 | `a4b0413c67fa46dd30dc7e2bce3ad56151ed8bfde2a3315fff685baf786b5d5d` | `ci/release-identity.json` `certificateSha256` |
| 签名方案 | v1 + v2 + v3 | `ci/release-identity.json` `schemes` |
| 最低系统 | Android 11（API 30）/ arm64-v8a | `app/build.gradle` `minSdk` / `abiFilters` |

原版 DSHA 使用 `dsh.android.client` 与历史 E7E3 证书。本 Mod 的包名与原版不同，不存在覆盖安装关系，因此**不复用 E7E3**：新包名首次安装即全新数据目录，旧版应用、旧授权与旧签名链都保持原样。

## 2. `verifyPublishCertificate` 现在校验什么

`app/build.gradle` 中该任务在 `assembleStandardRelease` / `packageStandardRelease` / `validateSigningStandardRelease` 之前执行，逐项检查：

1. 已通过 `DSHA_KEYSTORE`（或 `-Pdsha.keystore`）提供 keystore 文件；
2. keystore 中 `DSHA_KEY_ALIAS`（默认 `dsha-a-su`）对应证书的 SHA-256 等于 `ci/release-identity.json` 的 `certificateSha256`；
3. `android.defaultConfig.applicationId` 等于 `ci/release-identity.json` 的 `packageName`。

任一不符直接抛 `GradleException` 并停止打包。debug 构建、单测与 Lint 不依赖该任务；未配置 keystore 时正式打包会被阻止。

## 3. 私钥与口令

- 私钥与口令**不进入仓库**，也不写入任何文档或 CI 变量明文。
- 本机开发环境的密钥材料保存在仓库之外（例如 `keys/` 目录，权限 600，单独备份）；文件名、口令与别名由持有者保管。
- 构建时通过环境变量注入：

```bash
export DSHA_KEYSTORE=/secure/path/dsha-a-su-release.jks
export DSHA_KEYSTORE_PASSWORD=...
export DSHA_KEY_PASSWORD=...
export DSHA_KEY_ALIAS=dsha-a-su
./gradlew :app:assembleStandardRelease
```

- 增量更新清单（若启用）使用独立 keystore，不与 APK 发布证书混用。

## 4. 轮换与覆盖升级

- 同一包名的 Android 应用只允许同签名覆盖升级。一旦公开分发后更换发布证书，已安装用户必须卸载重装（数据需先导出备份），因此正常情况下**不要更换**。
- 确需轮换时：生成新 keystore → 计算证书 SHA-256 → 更新 `ci/release-identity.json` → 更新本文与 BUILD.md 的记录 → 在 Release 说明中明确要求用户导出备份并重装。
- 测试包（`io.github.sunsetrne.dsha.test`）可以换用独立测试证书；测试证书哈希不得等于正式证书，CI 中有对应断言。

## 5. 与原版并存的后果

| 项目 | 是否共享 |
|---|---|
| 应用数据目录 | 否（包名不同） |
| 无障碍开关 | 否（组件名含包名，可各自启用） |
| 通知 / 悬浮窗 / 存储等系统授权 | 否 |
| 局域网端口 | 否；默认 3082，被占用时自动回退随机端口 |
| 设备桥端口 | 否；`3091→3099→3090` 依次尝试 |
| 备份文件 | 可互相导入（v5 格式），迁移用它 |

## 6. 相关文件

- `ci/release-identity.json`：包名 + 证书哈希 + 签名方案。
- `ci/sunset-inputs.lock.json`：固定构建输入的公开资产地址与 SHA-256。
- [docs/fixed-build-inputs.md](fixed-build-inputs.md)：固定输入还原与升级流程。
- [BUILD.md](../BUILD.md)：环境、构建、签名与门禁命令。
