# DSHA 包名与发布签名



| 项 | 当前配置 | 权威来源 |
|---|---|---|
| 应用名称 | `DSHA` | `app/build.gradle` |
| 测试名称 | `DSHA Test` | 显式 `-Pdsha.sunsetTest=true` |
| 正式包名 | `dsh.android.client` | Gradle 与 `ci/release-identity.json` |
| 测试包名 | `dsh.android.client.SunsetRNE` | `applicationIdSuffix` |
| namespace | `com.deepseekharness.app` | Gradle；保持 Java/JNI 契约 |
| versionCode | `294` | Gradle 与 release identity |
| versionName | `0.2.1-rc.5-20261103.1300-dsh0.2.1-rc.5` | Gradle 与 release identity |
| 正式证书 SHA-256 | `e7e3a31a75946f2669194c972b3dd0c9aea3fc7c50a8b885d2dee710b22a53f5` | `ci/release-identity.json` |
| 发布配置 | Standard / 非调试 / v1+v2+v3 | Gradle 与 release identity |
| 最低系统 | Android 11（API 30）/ arm64-v8a | Gradle |

现有 `dsh.android.client` 用户继续使用同包名、同历史 E7E3 签名身份。`io.github.sunsetrne.dsha`（上游 Mod）、更早的 `com.dsh.client` 以及显式测试变体属于独立数据域，不会因为源码合并而自动共享私有目录、系统授权或 Keystore 内容。

## 发布证书门禁

`verifyPublishCertificate` 在 Standard Release 组装、打包和签名前验证：

1. 已通过 `DSHA_KEYSTORE` 或 `-Pdsha.keystore` 提供 keystore。
2. `DSHA_KEY_ALIAS`（默认 `androiddebugkey`）的实际证书 SHA-256 等于 release identity 的历史 E7E3。
3. 正式 `applicationId` 等于 release identity 的 `packageName`。

任一不符立即停止正式打包。别名名称不决定 APK 是否调试；正式 `release` 的 `debuggable` 明确为 false。单测和 Lint 不等于已验证一个实际 APK 的证书。

## 密钥与现有数据

密钥和口令保存在仓库之外，经既有受控环境注入；不把明文材料写入源码或报告。本次不生成新签名、不轮换证书、不迁移 Mod 私有数据域或导入授权。正式 bridge、应急 bridge、provider 与任务 affinity 使用实际包名。

上游和本地应用可因不同包名并存，但系统授权各自独立。备份/恢复仍按现有格式、来源、路径与事务门禁执行，不能从包名适配推导为任意版本数据可互相导入。

本次授权本地 Standard APK 交付；手机验收仍暂停，不安装/操作设备、不运行 Low、不推送或发布 GitHub。对最终 APK 核对包名、版本、非调试状态和实际签名，结果由本轮交付报告记录，文档配置本身不代表核验通过。

未来 CI 发布仅允许私有 `ym2025szz/DSHA-prerelase` 的 `codex/prerelease-190` 分支显式触发。复用验证节点全部通过后才进入 `build-signed`；历史别名默认 `androiddebugkey`，签名 Secrets 不进入共享缓存。`publish` 只核对同 run/commit 的成品交接并发布，不 checkout、不签名；详见 [CI 构建与发布](ci-release.md)。

## 相关入口

- [BUILD.md](../BUILD.md)：工具链、输入与打包门禁。
- [固定输入](fixed-build-inputs.md)：上游固定资产和锁定摘要。
