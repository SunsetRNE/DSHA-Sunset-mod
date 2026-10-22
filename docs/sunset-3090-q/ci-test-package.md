# CI 测试包与 GitHub Actions 变更

> 本文件定义 `Sunset-3090-Q` 的 CI 契约：测试包身份、测试签名、APK 产物、固定输入包，以及 2026 年 GitHub Actions 的两项相关变更。
>
> 命名以 [naming.md](naming.md) 为准；分支只用于测试，**不打 tag、不创建 Release、不公开上传**。

## 1. 2026 年 GitHub Actions 相关变更

### 1.1 非压缩 artifact（2026-02-26）

GitHub 于 2026-02-26 支持上传/下载**非 zip** artifact（changelog 关键词：`2026-02-26-github-actions-now-supports-uploading-and-downloading-non-zipped-artifacts`）。此前 `actions/upload-artifact` 会把上传内容自动打成一个 zip；现在可以在 `upload-artifact` 上使用 `archive: false`，让 APK 以**.apk 原文件**形式存储和下载，不再被二次压缩成 `.zip`。

对本分支的意义：

- APK 本身已是压缩包，再 zip 一次既浪费时间又让下载后多一步解压；
- 测试同学需要直接拿到 `.apk` 安装，不能拿到 `artifact.zip` 里再套一个 `apk`；
- 因此 APK artifact 使用 `archive: false`，并同时上传 `SHA256SUMS`、测试证书、`build-info.json`（同样非压缩）。

### 1.2 Node 20 退出（2026-09-23）

GitHub 已从 2026-09-23 起移除 Node 20，JavaScript action 统一运行在 Node 24；旧的 `actions/checkout@v4`、`actions/setup-node@v4`、`actions/upload-artifact@v4` 等 Node 20 action 会直接失败。

本仓库现有 `ci-fast.yml` / `ci-package.yml` 仍 pin 在 v4（Node 20）。**2026-10-22 之后这些 workflow 已经不能可靠运行**，因此本分支同时把它们升级到 Node 24 兼容版本：

| action | 新 pin | 版本 |
|---|---|---|
| `actions/checkout` | `3d3c42e5aac5ba805825da76410c181273ba90b1` | v7.0.1 |
| `actions/setup-java` | `b843ebf9b913146b38da2eaa7aeddf76074ae1ad` | v6.1.0 |
| `actions/setup-node` | `949feb2413d6458794dcd2491c4babbbce0c15c1` | v7.1.0 |
| `actions/upload-artifact` | `cf430e030ddbb5b0abf93d22962f4752f3646cd9` | v7.0.2 |

> 版本/提交以 2026-10-22 的 `git ls-remote` 为准；升级时重新核对 SHA，不直接改用浮动 tag。

### 1.3 仍然保留的约束

- `ubuntu-24.04` 托管 runner；Java 17、Node 24、Python 3.12 由 `tools/ci-toolchain.py` 从 `ci/toolchain.lock.json` 读取。
- 所有 action 继续按 SHA 固定，注释版本号；不使用 `@main`、`@latest`。
- workflow 只有 `contents: read`，不请求写权限，不创建 Release/tag。
- 不使用 `ACTIONS_ALLOW_USE_UNSECURE_NODE_VERSION` 之类的回退开关。

## 2. 测试包身份

| 项 | 值 |
|---|---|
| applicationId | `dsh.android.client.SunsetRNE` |
| versionCode | `190` |
| versionName | `0.2.1-rc.2-20261022.0243-dsh0.2.1-rc.2-SunsetTest`（Standard） |
| 可调试 | `false` |
| 签名 | 测试 keystore，证书 SHA-256 ≠ E7E3 |
| 安装 | 只覆盖同包名、同测试签名的旧测试包；不覆盖正式包 |

构建开关：

```bash
./gradlew :app:assembleStandardRelease \
  -Pdsha.sunsetTest=true \
  -Pdsha.unsignedPackage=true
```

- `dsha.sunsetTest=true`：applicationId 加 `.SunsetRNE`，versionName 加 `-SunsetTest`。
- `dsha.unsignedPackage=true`：跳过 E7E3 正式签名，产出 unsigned APK；随后用测试 keystore 签名。
- 不传这两个参数时，行为与基线一致，不会生成测试包身份。

## 3. 测试签名

### 3.1 keystore

| 项 | 值 |
|---|---|
| 文件 | `sunset-test.jks`（PKCS12） |
| alias | `sunset-test` |
| DN | `CN=DSHA Sunset Test (NOT FOR RELEASE), OU=Sunset-3090-Q, O=SunsetRNE, C=CN` |
| 算法 | RSA 3072，3650 天 |
| 口令 | `sunset-test-only`（测试口令，不是秘密；CI 环境变量 `SUNSET_TEST_STORE_PASSWORD` 可覆盖） |
| 证书 | 上传 `sunset-test-cert.pem`；CI 打印 SHA-256 |

### 3.2 来源

优先级：

1. GitHub Actions secret `SUNSET_TEST_KEYSTORE_B64`（base64 的 PKCS12）—— 需要跨 run 覆盖安装时配置；
2. `actions/cache` 缓存的 `sunset-test.jks`（cache key 固定，7 天保留期内可复用）；
3. 本次 run 用 `keytool` 临时生成。

无论来源，CI 都必须：

```bash
apksigner verify --print-certs app-signed.apk
# 断言证书 SHA-256 != e7e3a31a75946f2669194c972b3dd0c9aea3fc7c50a8b885d2dee710b22a53f5
```

测试私钥**不提交 Git**；临时 keystore 可作为 artifact 上传（14 天），仅供本人复用，不得用于任何正式产物。

## 4. Workflow 设计

文件：`.github/workflows/sunset-3090-q.yml`

```yaml
name: Sunset-3090-Q test package
on:
  push:
    branches: [Sunset-3090-Q]
  workflow_dispatch:
    inputs:
      asset_bundle_url: { required: false, type: string }
      asset_bundle_sha256: { required: false, type: string }
permissions:
  contents: read
concurrency:
  group: sunset-3090-q-REF
  cancel-in-progress: true
```

### 4.1 `source` job

1. checkout（`persist-credentials: false`）
2. `python3 tools/ci-toolchain.py --github-output`
3. setup-java（Temurin 17）、setup-node（24）
4. 源码契约检查：

```bash
python3 tools/prepare-runtime-tools.py --check
python3 tools/verify-runtime-descriptor-source.py
python3 tools/generate-third-party-notices.py --check
python3 tools/check-third-party-notices.py
python3 tools/generate-builtin-plugins.py --verify
python3 tools/generate-credential-paths.py --check
python3 tools/prepare-semver-reference.py
python3 tools/format-java-tree.py --check
python3 tools/run-host-tests.py --check-manifest
python3 tools/check-source-hygiene.py
python3 tools/check-ui-i18n.py
python3 tools/check-document-links.py
```

5. 分支宿主回归：只跑与设备桥/consent/shell 直接相关、且不依赖离线输入包或上游夹具的用例：

   ```bash
   python3 tools/run-host-tests.py --stage fast --jdk "$JAVA_HOME" \
     --only test-native-consent-transport.mjs \
     --only test-bridge-token-compat.mjs \
     --only test-bridge-token-transport.py \
     --only test-destructive-guard.mjs \
     --only test-destructive-full-access.mjs \
     --only test-vscreen-anchored.mjs \
     --only test-device-shell-policy.py \
     --only test-credential-paths.py \
     --only test-runtime-descriptor-inputs.py \
     --only test-runtime-descriptor-source.py \
     --only test-plugin-capabilities.py \
     --only test-architecture-boundaries.py
   ```

   完整 `--stage fast` 里的 `test-asset-deployment`、`test-mobile-server-patches` 需要
   离线输入包/上游夹具，`test-dns-compat` 依赖宿主 DNS；这些由 `ci-fast`/package 流程或
   固定输入包覆盖，不在本分支的推送门禁里伪造通过。
6. Android 单测（不生成离线包）：

```bash
sdkmanager "platforms;android-37.0" "build-tools;36.0.0"
./gradlew :app:testStandardDebugUnitTest :app:testLowDebugUnitTest \
  -x :app:prepareStandardAssets -x :app:prepareRecoveryAssets \
  --no-daemon --max-workers=2
```

7. 上传测试结果（默认 zip 即可）。

超时：35 分钟。

### 4.2 `apk` job

只有在能取得固定输入包时才执行；否则输出明确 notice 并跳过，不伪造 APK。

输入包来源优先级：

1. `workflow_dispatch` 的 `asset_bundle_url` + `asset_bundle_sha256`；
2. 仓库变量 `SUNSET_ASSET_BUNDLE_URL` + `SUNSET_ASSET_BUNDLE_SHA256`。

步骤：

1. checkout、toolchain、setup-java/Node（同上）。
2. 下载/校验固定输入包：

```bash
python3 tools/ci-assets.py --url "$URL" --sha256 "$SHA"
```

该脚本只允许固定文件名、拒绝路径穿越、逐文件核对 SHA；失败即终止。

3. 安装 Android SDK/NDK：

```bash
sdkmanager "platforms;android-37.0" "build-tools;36.0.0" "ndk;26.3.11579264"
```

4. 构建 unsigned 测试包：

```bash
./gradlew :app:assembleStandardRelease \
  -Pdsha.sunsetTest=true -Pdsha.unsignedPackage=true \
  --no-daemon
```

5. 测试签名：

- 解析 `SUNSET_TEST_KEYSTORE_B64` → `sunset-test.jks`；
- 否则从 cache 恢复；
- 否则 `keytool` 生成；
- `apksigner sign --ks sunset-test.jks --ks-key-alias sunset-test ...`

6. 校验身份：

```bash
aapt2 dump badging app-signed.apk | grep "package: name='dsh.android.client.SunsetRNE'"
apksigner verify --print-certs app-signed.apk
# debuggable=false；证书 != E7E3；versionName 含 -SunsetTest
```

7. 生成 `SHA256SUMS`、`build-info.json`（commit、run id、versionName、证书 SHA-256、端口设计版本）。
8. 上传 artifact（**非压缩**）：

```yaml
- uses: actions/upload-artifact@cf430e030ddbb5b0abf93d22962f4752f3646cd9 # v7.0.2
  with:
    name: DSHA-Sunset-3090-Q-RUN_NUMBER-SHORT_SHA
    archive: false
    retention-days: 14
    path: |
      out/*.apk
      out/SHA256SUMS
      out/sunset-test-cert.pem
      out/build-info.json
```

说明：`archive: false` 是 2026-02-26 的非压缩 artifact 能力；下载得到的就是 `.apk`，不是 `.zip`。

9. 可选：上传测试 keystore（仅当本次生成；14 天；文件名带 `TEST-ONLY` 前缀）。

超时：120 分钟。

### 4.3 为什么 APK job 需要输入包

`offline-rootfs.bin`、`dsh-runtime.bin`、`ubuntu-tools.bin` 等固定输入合计约 738 MB，不在 Git 中（GitHub 单文件 100 MB 限制，且仓库明确不提交大生成归档）。没有输入包时：

- `source` job 仍完整运行（源码/单测/宿主回归）；
- `apk` job 跳过并给出 `APK_SKIPPED_NO_ASSET_BUNDLE` 的 summary；
- 不生成假的/残缺 APK。

配置方式（二选一）：

- 仓库 Settings → Variables：`SUNSET_ASSET_BUNDLE_URL`、`SUNSET_ASSET_BUNDLE_SHA256`；
- 手动 `workflow_dispatch` 时填两个输入。

## 5. 产物契约

| 文件 | 说明 |
|---|---|
| `dsha-0.2.1-rc.2-sunset3090q-standard-VERSION-SHORT_SHA-signed.apk` | 测试 APK |
| `SHA256SUMS` | 对 APK/证书的 SHA-256 |
| `sunset-test-cert.pem` | 测试证书（公开材料） |
| `build-info.json` | commit、run、包名、versionName、证书 SHA-256、输入包 SHA-256、构建时间 |
| `TEST-ONLY-sunset-test.jks` | 可选；仅当本次生成，14 天，禁止用于生产 |

artifact 名：`DSHA-Sunset-3090-Q-RUN_NUMBER-SHORT_SHA`。

## 6. 验证命令（下载 artifact 后）

```bash
sha256sum -c SHA256SUMS
aapt2 dump badging *.apk | head -5
apksigner verify --print-certs *.apk
# 确认：
# - package name = dsh.android.client.SunsetRNE
# - versionName 含 -SunsetTest
# - application-debuggable 不出现
# - 证书 SHA-256 != e7e3a31a...
```

## 7. 安全与合规

- 只使用测试签名；E7E3 只属于正式包。
- 不创建 tag/Release；workflow 不请求 `contents: write`。
- 输入包 URL 必须是 HTTPS 且带固定 SHA-256；不使用可变分支 URL。
- 不在日志打印 token/API key/用户数据；APK 中不包含测试 keystore。
- 测试 keystore 即使上传，也标注 `TEST-ONLY`、14 天过期；不得用于任何正式分发。
- 不把本 workflow 的成功当成正式发布证明；它只证明测试包可构建、可签名、可安装。

## 8. 本地等价命令（无 CI 时）

```bash
# 需要固定输入包已展开到 app/src/main/assets/
./gradlew :app:assembleStandardRelease \
  -Pdsha.sunsetTest=true -Pdsha.unsignedPackage=true --no-daemon

keytool -genkeypair -keystore sunset-test.jks -storetype PKCS12 \
  -alias sunset-test -keyalg RSA -keysize 3072 -validity 3650 \
  -dname "CN=DSHA Sunset Test (NOT FOR RELEASE), OU=Sunset-3090-Q, O=SunsetRNE, C=CN" \
  -storepass sunset-test-only -keypass sunset-test-only

apksigner sign --ks sunset-test.jks --ks-key-alias sunset-test \
  --ks-pass pass:sunset-test-only --key-pass pass:sunset-test-only \
  --out dsha-sunset.apk app/build/outputs/apk/standard/release/*-unsigned.apk

apksigner verify --print-certs dsha-sunset.apk
```

## 9. 回滚

- 删除/禁用 `sunset-3090-q.yml` 即停止测试包流水线；
- 代码回滚不影响正式包；
- 已上传的测试 artifact 14 天后自动过期；测试 keystore 如泄露只影响测试包，不涉及 E7E3；
- 不删除历史正式 Release/资产。
