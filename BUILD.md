# DSHA 现行构建说明

> 当前身份（build212）：`DSH-A-Su` / `io.github.sunsetrne.dsha` / `0.2.1-rc.3-20261030.1506-dsh0.2.1-rc.3` / versionCode 212。
> 单一 Standard 变体：minSdk 30（Android 11+）、arm64-v8a、系统 WebView。原 `low`/GeckoView 兼容线与独立上岛包已删除。
> 签名：Mod 自有新建证书（SHA-256 `a4b0413c67fa46dd30dc7e2bce3ad56151ed8bfde2a3315fff685baf786b5d5d`），不再使用原版 E7E3。
> 固定构建输入：公开仓库预发布 Release 资产，SHA-256 锁定；流程见 [docs/fixed-build-inputs.md](docs/fixed-build-inputs.md)。
> 历史构建回执与旧身份数字保留在 [CHANGELOG.md](CHANGELOG.md) 与 [docs/releases/](docs/releases/)，不作为当前构建或签名输入。


先读 [README](README.md)、[AGENTS](AGENTS.md)、[贡献流程](CONTRIBUTING.md) 与[接手指南](docs/接手指南.md)。应用版本以 `app/build.gradle` 为准，DSH 依赖以 `tools/dsh-runtime/package.json`/锁文件为准，完整交付和未验证范围以本轮报告为准。

> 更早的 F13/1500/1640 等回执只描述各自当时的源码、APK 与设备状态，属于历史记录；不要用旧包名 `dsh.android.client`、历史 E7E3 证书或旧版本号来构建当前 Mod。

本机源码位置以实际 clone 为准；`app/build.gradle`、`ci/release-identity.json` 与 `ci/sunset-inputs.lock.json` 是当前构建身份的三个权威来源。

## 环境与 flavor

| 输入 | 现行要求 |
|---|---|
| JDK / 原生语言 | Java 17，无 Kotlin |
| Gradle / AGP | 仓库 Wrapper 9.3.1 / AGP 9.1.1 |
| Android SDK | `platforms;android-37.0`、build-tools 36.0.0；compile/target API 37 |
| NDK | 26.3.11579264（仅在有原生构建时使用；仓库已带 arm64 预编译 JNI） |
| Python / Node | 生成器 Python 3.9+，CI 锁定版本见 `ci/toolchain.lock.json`；Node 24 |
| Standard | minSdk 30，系统 WebView，arm64-v8a（唯一变体） |

当前只有一条 Standard 构建线；测试变体仅通过 `-Pdsha.sunsetTest=true` 改包名/版本名（`io.github.sunsetrne.dsha.test`），与正式包并存、数据隔离。原 `dsh.android.client` 与更早的 `com.dsh.client` 均为独立包名，本 Mod 与之互不覆盖。

配置本机 `local.properties` 的 `sdk.dir`，并将 JAVA_HOME、ANDROID_SDK_ROOT/ANDROID_HOME、DSHA_PYTHON 指向已有工具。不要复制开发者的绝对工具链目录。版本与固定 APK 文件名由 `app/build.gradle` 的 `versionCode`、`versionName` 和 `dshaApkNameVersion` 定义，不在本页再维护另一组当前数字。

## 输入准备与证明

Git保留完整应用、资源、锁定生成/校验脚本，以及构建必需且有notice/锁的小型JNI/vendor/解释器预编译依赖；大型rootfs、DSH和recovery生成归档不入Git。来源未知或闭源的vendor限制按实际材料保留，不能宣称所有vendor native均可从源码复现。精确边界与9个显式审计JSON白名单见[CONTRIBUTING](CONTRIBUTING.md)：原6项构建输入元数据加3项历史文档/来源元数据，不开放整个audit目录。

当前固定输入（build209 起）以公开仓库的**预发布 Release 资产**分发，`ci/sunset-inputs.lock.json` 记录 URL、SHA-256、字节数、格式与 Release/asset 名；还原命令见 [docs/fixed-build-inputs.md](docs/fixed-build-inputs.md)。更新固定输入时先上传新资产，再更新 lock 并在干净 clone 上重新还原核对，不能只改 lock 不核对字节。

Release完整源码ZIP额外包含固定输入包`locked-current-build-inputs.zip`中的原始文件，按项目路径直接展开，不嵌套该ZIP。F13固定输入包为738,101,893 bytes，SHA-256 `da3a87e9782469136f08d37143582fec845af1a69406b9a534f8cb7f68249ed8`，由`tools/ci-assets.py --pack`逐SHA核对；该大小不是完整源码ZIP大小。旧738,099,607/738,099,619-byte输入包及12字节行尾差异属于F10及更早历史，不能代替本轮输入身份。配置工具链后按当前recipe构建，无需独立bundle；Git clone可做source-only检查，完整打包仍需固定原始输入。APK中的变换层不能逆还原全部原始rootfs，不提供从APK恢复所有输入的保证。

可分发源码写LF原字节；`.gitattributes`中的12项managed/canonical bytepin及历史原件保留精确原字节，不能全库规范化已签名输入。小型二进制输入19项按清单保留，不以文本行尾规则改写。共享UUID校验由Ids提供，补齐四处调用依赖并保持历史格式。

F13冻结源码2215成员/200篇Markdown，Java adoption 1029项。分发/格式证明与JUnit执行计数分开。固定输入包738,101,893 bytes，SHA-256 `da3a87e9782469136f08d37143582fec845af1a69406b9a534f8cb7f68249ed8`，原始输入直接展开到项目路径；不是完整源码ZIP的大小或摘要。精确继承SHA证明与变更叶fresh检查的实际范围见本批冻结格式回执。

历史F8的999项Java证明、此前分批读取/style等价证明及F7宿主Core重绑定失败/修复正负控仍保留原记录；它们不是F13现行adoption范围或测试数。旧软件、APK与设备结论各属原源码/摘要，最终F13软件与精确APK结果由统一回执绑定。

Ubuntu基础环境身份独立于DSH与UI版本。`offline-rootfs.bin` / `dsh-runtime.bin`为split-runtime-v1，独立应急pinned输入由`tools/recovery-runtime/lock.json`约束，不能用正式环境作为救援依赖。没有大归档的源码检出不能直接当成完整APK，也不能跳过完整性门禁。

受管输入发生变化时，按实际生成入口更新资产，再显式生成证明：

```bash
python3 tools/prepare-backup-assets.py --write
python3 tools/prepare-runtime-descriptor.py --write
python3 tools/prepare-test-runtime.py
```

生成的证明需与真实字节核对；普通 Gradle 构建使用 check，不能靠旧缓存跳过新输入。当前夹具由 `app/build/test-runtimes/current.json` 及源/内容指纹约束，不能把任一旧目录称为当前运行时。

## 软件验证

```bash
python3 tools/run-host-tests.py --check-manifest
python3 tools/run-host-tests.py --stage fast
python3 tools/check-source-hygiene.py
python3 tools/check-ui-i18n.py
python3 tools/localize-android-ui.py --resources-only --check
python3 tools/generate-changelog.py --check
./gradlew :app:testStandardDebugUnitTest :app:lintStandardRelease
python3 tools/run-host-tests.py --stage runtime
python3 tools/verify-stability.py --stage software
```

Windows 使用 `gradlew.bat`，并以真实 Python 可执行文件代替不可用的系统别名。无法运行 AGP/aapt2 时，`tools/run-unit-tests.py` 是纯逻辑后备，不替代 Android 全编译、APK 或完整资源门禁。平台跳过、未运行项与 Lint 警告如实分类，不写成通过。

`tools/verify-plugin-upgrade-gate.py` 检查当前自动提交/启用、用户禁用与安全模式、实际依赖、插件事务及受管资产。正式插件允许未锁定解析、生命周期脚本和 pnpmfile；不能把历史“原生审阅/依赖冻结”的测试口径复活为当前要求。

## 正式打包与签名

显式提供 `DSHA_KEYSTORE`、`DSHA_KEYSTORE_PASSWORD`、`DSHA_KEY_PASSWORD`，别名默认 `dsha-a-su`（可用 `DSHA_KEY_ALIAS` 覆盖）。签名身份以 `ci/release-identity.json` 为准：`packageName = io.github.sunsetrne.dsha`，`certificateSha256 = a4b0413c67fa46dd30dc7e2bce3ad56151ed8bfde2a3315fff685baf786b5d5d`。`verifyPublishCertificate` 会在 `assembleStandardRelease`、`packageStandardRelease`、`validateSigningStandardRelease` 之前核对 keystore 证书与 applicationId，任一不符即停止打包；不再允许历史 E7E3 或 debug 证书出正式包。私钥不进仓库，换钥会使本 Mod 已安装版本无法覆盖升级。

```bash
./gradlew :app:assembleStandardRelease
```

产物位置 `app/build/outputs/apk/standard/release/dsha-<dshaApkNameVersion>.apk`，必须同时生成并核对 `.sha256`。实际 APK 需核对包名、versionCode/versionName、`debuggable=false`、arm64、证书 SHA-256 与受管资产/恢复归档摘要。`app/build` 中间包、未签名 CI 包（`-Pdsha.unsignedPackage=true`）都不冒充正式交付。

发布交付只包含当前 Mod 的正式 APK 与 `.sha256`（源码走 Git，固定输入走预发布资产）。SHA-256 必须由实际文件计算并写进 Release 或独立回执，源码 ZIP 不自引用自身摘要。私钥、用户数据、设备截图与历史取证材料不上传；历史构建与测试数字保留在各自回执里，不用来替代本轮结果。

`ci-package` 的未签名完整包只用于软件验证，不用于手机安装或 release。配置文件不能证明受保护环境、签名线上运行和仓库保护已配置；实际部署与发布需要相应证据。

## CI 构建与发布（公开仓库）

正式成品可以通过公开仓库的 Actions 工作流 [release-production.yml](.github/workflows/release-production.yml)
在 GitHub 托管的 `ubuntu-24.04` 上构建并发布：显式 `workflow_dispatch`、绑定精确 commit、
从公开预发布资产还原固定输入、跑完整单测/格式/Lint/文档门禁，用仓库 Secrets 里的 Mod 发布证书
签名 `io.github.sunsetrne.dsha` 正式包，核验证书 SHA-256 后创建 tag + prerelease。
触发参数、Secrets、产物与核验命令见 [CI 构建与发布](docs/ci-release.md)；固定输入资产见
[固定构建输入](docs/fixed-build-inputs.md)。CI 结果不替代真机安装、覆盖升级与长时间运行验收。

## 手机与历史范围

本轮构建改造不包含手机覆盖安装、启停、输入或模型对话验收；保留用户数据、会话、配置、插件与未知运行状态，不通过“正常Stop”文案猜测已退出。旧设备回执绑定各自 APK 和时点，不能移用为新包实测。

旧 build130、build142 及早期调试/独立审计包的步骤与设备结果属于原日期/产物。修改前本页精确原字节保存在 `tools/history/documents/BUILD-build155.md`，摘要见 [本轮保留清单](docs/audits/build156/source-docs-retained.json)；该原件不参与部署或测试发现。历史备份、配置、旧数据与未知原件保护继续维护，退休工具不重新激活。

Linux tmpfs 发布回归不证明持久磁盘/掉电/Android FUSE；Android 10 及以下已不在支持范围，Android 6/7、厂商 Root/Shizuku、16 KiB 等旧矩阵只作为历史记录，本版不声明支持。更多职责与控制见[模块图](docs/module-map.md)和[安全模型](docs/security-model.md)。

维护源码前先看[项目总手册](docs/project-architecture-handbook.md)和[文档总索引](docs/documentation-index.md)；引擎、端口、DNS的当前默认值与历史迁移分别有专页。
