# DSHA 现行构建说明

先读 [README](README.md)、[AGENTS](AGENTS.md)、[贡献流程](CONTRIBUTING.md) 与[接手指南](docs/接手指南.md)。应用版本以 `app/build.gradle` 为准，DSH 依赖以 `tools/dsh-runtime/package.json`/锁文件为准，完整交付和未验证范围以本轮报告为准。

F10 `0.2.1-rc.2-20261021.0140-dsh0.2.1-rc.2` / code190：键盘动画改为稳定网页布局与合成位移；移动UI更新3.2.1（上游main `10e58ce0c4ed424c7fbd9c91d48db06c6061b4e4`）；新增直接保存Download/DSHA的一键备份。两版各 124 类/785 项 JUnit，0失败/错误（Standard 3、Low 76 跳过）；Release Lint 0错误，249/162 警告；实际APK七项门禁通过。首轮11个Standard旧系统测试受minSdk清单拦截，修正测试环境后重跑，原始失败保留。暂停设备/ADB/对话和长时验收。仅现有私有仓库，完整源码ZIP仍按项目路径直接展开固定构建输入；下载与最终远端状态以[关联Release](https://github.com/SunsetRNE/DSHA-Sunset-mod-3090-Q/releases/tag/v0.2.1-rc.2-20261021.0140)及本地独立发布回执为准。 详细范围见[F10追加记录](docs/releases/v0.2.1-rc.2-build190-prerelease-20261020.md)。

本机本轮源码在 `<local-workspace>`；`<local-workspace>`的旧173检出不是当前构建源。其他机器使用新私有仓库最终tag/完整commit或对应源码附件。

## 环境与 flavor

| 输入 | 现行要求 |
|---|---|
| JDK / 原生语言 | Java 17，无 Kotlin |
| Gradle / AGP | 仓库 Wrapper 9.3.1 / AGP 9.1.1 |
| Android SDK | `platforms;android-37.0`、build-tools 36.0.0；compile/target API 37 |
| NDK | 26.3.11579264；终端与 Low proot 按各自受管入口使用 API 23 与真实 ELF 对齐检查 |
| Python / Node | 生成器 Python 3.9+，CI 锁定版本见 `ci/toolchain.lock.json`；Node 24 |
| Standard | minSdk 30，系统 WebView，arm64-v8a |
| Low | minSdk 23，额外 Gecko 备用内核，arm64-v8a |

两版共用 `com.dsh.client` 与用户数据，不能并排安装。最低 API、ELF 16 KiB 对齐与实际 Android 6/7/16 KiB 设备可用性是不同证据。Windows 可以开发和构建；Linux 特有用例不能用 Windows 跳过结果代替。

配置本机 `local.properties` 的 `sdk.dir`，并将 JAVA_HOME、ANDROID_SDK_ROOT/ANDROID_HOME、DSHA_PYTHON 指向已有工具。不要复制开发者的绝对工具链目录。版本与固定 APK 文件名由 `app/build.gradle` 的 `versionCode`、`versionName` 和 `dshaApkNameVersion` 定义，不在本页再维护另一组当前数字。

## 输入准备与证明

Git保留完整应用、资源、锁定生成/校验脚本，以及构建必需且有notice/锁的小型JNI/vendor/解释器预编译依赖；大型rootfs、DSH和recovery生成归档不入Git。来源未知或闭源的vendor限制按实际材料保留，不能宣称所有vendor native均可从源码复现。精确边界与6个必需审计JSON白名单见[CONTRIBUTING](CONTRIBUTING.md)。

Release完整源码ZIP在上述源码之外，额外包含由`tools/ci-assets.py --pack`逐SHA核对的`locked-current-build-inputs.zip`中的原始输入文件；这些文件按项目路径直接展开，不嵌套该ZIP。当前输入包738,099,607 bytes，包括约304MB原rootfs、约148MB DSH输入等；不是完整源码ZIP大小。前包738,099,619 bytes与其仅差`ubuntu-tools.inputs.json`行尾12字节，归档二进制不变、JSON语义一致，不称两个packet全部原字节相同。配置工具链后按现recipe构建，无需独立bundle。Git clone可做源码-only检查，完整打包需ZIP固定输入；APK约85MB offline-rootfs不能逆还原约304MB原rootfs，不提供APK恢复所有输入的helper。

可分发源码写LF原字节；`.gitattributes`中的10项managed/canonical bytepin及历史原件保留精确原字节，不能全库规范化已签名输入。小型二进制输入19项按清单保留，不以文本行尾规则改写。共享UUID校验由Ids提供，补齐四处调用依赖并保持历史格式。

F8当前999 Java为996文件精确未变、3个重新formatter检查0needs，完整adoption999约束继续；此前38批读取、20变更实查/sourceSHA复用及43独立style文件token全等/fixed point记录保留，不当作JUnit数量。F7正式构建曾因真实宿主Core重绑定竞态失败；F8三文件最小修复有22项宿主正控通过、两个各1项/1预期失败负控，正式XML/Lint与7项实际APK门禁另按本轮结果记录。宿主不代表手机复现。原失败日志保留，最终包在所有源码与元数据修复后重建。

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
./gradlew :app:testStandardDebugUnitTest :app:testLowDebugUnitTest \
  :app:lintStandardRelease :app:lintLowRelease
python3 tools/run-host-tests.py --stage runtime
python3 tools/verify-stability.py --stage software
```

Windows 使用 `gradlew.bat`，并以真实 Python 可执行文件代替不可用的系统别名。无法运行 AGP/aapt2 时，`tools/run-unit-tests.py` 是纯逻辑后备，不替代 Android 全编译、APK 或完整资源门禁。平台跳过、未运行项与 Lint 警告如实分类，不写成通过。

`tools/verify-plugin-upgrade-gate.py` 检查当前自动提交/启用、用户禁用与安全模式、实际依赖、插件事务及受管资产。正式插件允许未锁定解析、生命周期脚本和 pnpmfile；不能把历史“原生审阅/依赖冻结”的测试口径复活为当前要求。

## 正式打包与签名

显式提供已有 `DSHA_KEYSTORE`、`DSHA_KEYSTORE_PASSWORD`、`DSHA_KEY_PASSWORD`；可配置历史 `DSHA_KEY_ALIAS`。签名身份以 `ci/release-identity.json` 为准，保持 E7E3，不以 debug、A3 或新证书替代。密钥文件名含 debug 不表示正式 APK 可调试；必须核对实际 `debuggable=false`。

```bash
./gradlew :app:assembleStandardRelease
```

本轮发布Standard APK，软件验证覆盖受影响的两版测试与Release Lint，并核对实际Standard APK/资产/ELF、签名、许可、插件/应急门禁及精确摘要。JUnit选择类、跳过、缓存复用与未跑范围分别记录，不冒称全库clean或完整冷网络构建。JNI静态审计使用对应Release APK，不推荐旧Debug产物；旧Low原件保持，不把Low软件检查写成新Low交付或设备验收。

本地Standard正式产物为 `<local-workspace>`，对应摘要在本地核对并随发布记录。历史版本和旧Low字节保留，`app/build`中间包不冒充交付。现有私有仓库Git不收大生成归档；Release只附正式APK与含固定构建输入的完整源码ZIP，SHA-256写在Release正文，源码ZIP自身README不写该ZIP摘要以免自引用。私钥、用户数据、设备截图/日志和历史取证材料不上传。必要6个审计元数据、27个历史构建源码与唯一website测试夹具按交付清单保留，不等于上传历史现场。2054与2330/F9为已发布历史，本页F10的软件与APK核验已完成；当前源码、两附件和远端状态以README关联Release及独立发布回执为准。

`ci-package` 的未签名完整包只用于软件验证，不用于手机安装或 release。配置文件不能证明受保护环境、签名线上运行和仓库保护已配置；实际部署与发布需要相应证据。

## 手机与历史范围

本轮全部手机操作已暂停，不进行覆盖安装、启停、输入或模型对话，也不为补证恢复ADB。保留用户数据、会话、配置、插件与未知运行状态；不通过“正常Stop”文案猜测已退出。旧设备回执绑定各自APK和时点，不能移用为新包实测。

旧 build130、build142 及早期调试/独立审计包的步骤与设备结果属于原日期/产物。修改前本页精确原字节保存在 `tools/history/documents/BUILD-build155.md`，摘要见 [本轮保留清单](docs/audits/build156/source-docs-retained.json)；该原件不参与部署或测试发现。历史备份、配置、旧数据与未知原件保护继续维护，退休工具不重新激活。

Linux tmpfs 发布回归不证明持久磁盘/掉电/Android FUSE，Android 6/7、厂商 Root/Shizuku、16 KiB 和外部模型范围依实际回执声明。更多职责与控制见[模块图](docs/module-map.md)和[安全模型](docs/security-model.md)。

维护源码前先看[项目总手册](docs/project-architecture-handbook.md)和[文档总索引](docs/documentation-index.md)；引擎、端口、DNS的当前默认值与历史迁移分别有专页。
