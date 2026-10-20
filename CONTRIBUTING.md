# 构建、验证与提交

先读 [README](README.md)、[AGENTS](AGENTS.md) 和[本轮预发布范围](docs/releases/v0.2.1-rc.2-build190-prerelease-20261020.md)。历史审计资料的另一会话决策不能覆盖这里的用户要求。

本轮最新决定以[私有预发布范围](docs/releases/v0.2.1-rc.2-build190-prerelease-20261020.md)为准：全部设备/ADB/手机对话暂停。F8/2054/code190正式单轮225.98秒通过，实际两版各107类/684项0失败/错误（Std3、Low76skip），Lint0Error/Fatal、247/162警告；实际APK7门禁PASS。1933/a777及F7失败保留历史归属，F8 host22正控和2预期负控不是手机实测。源码ZIP与Standard APK待发布到新私有`SunsetRNE/DSHA-Sunset-mod-3090-Q`，预定tag`v0.2.1-rc.2-20261020.2054`，原仓库/网站不变。

项目特有操作见[现行接手指南](docs/接手指南.md)，职责见[模块图](docs/module-map.md)，约束见[工程规范](docs/engineering-standard.md)与[ADR 0001](docs/adr/0001-build154-boundaries.md)。退休工具与文档原件见 `tools/history` 和对应保存清单；它们不进入APK或自动测试执行。

## 源码与工具身份

Java 17，单模块 `:app`。Standard 使用系统 WebView、最低 API 30；Low 最低 API 23、Gecko 备用内核。两版包名均为 `com.dsh.client`，仅 arm64。

- 应用版本及固定文件名规则：`app/build.gradle`。
- DSH 版本及 npm 内容：`tools/dsh-runtime/package.json`、`package-lock.json`。
- 随包工具：`tools/runtime-tools.lock.json`。
- 内置插件：`app/src/main/assets/builtin-plugins.json`。
- 运行期补丁 active/specialized/retired：`app/src/main/assets/runtime-patches.json`。
- 独立应急归档：`tools/recovery-runtime/lock.json`。正式环境更新不能擅自替换独立应急锁。
- CI 工具与历史发布证书：`ci/toolchain.lock.json`、`ci/release-identity.json`。

可规范化源码使用实际LF，19项必要小型二进制及历史原件保持原字节。`.gitattributes`列出的10项managed/canonical精确bytepin不做行尾转换；不要全库改写或通过忽略换行的哈希掩盖包内内容变化。生成的Java、描述符和证明由对应脚本生成，四处UUID调用补齐共享Ids依赖，保持历史UUID格式。

## 准备输入

大型rootfs、DSH和recovery生成归档不入Git，代码、recipe和锁保留。Git clone可运行源码-only检查；完整打包使用固定输入包`locked-current-build-inputs.zip`中经`tools/ci-assets.py --pack`逐SHA核对的原始输入文件；Release源码ZIP将这些文件按项目路径直接展开，不嵌套该输入包。当前738,099,607 bytes，包括原rootfs约304MB、DSH约148MB等，不是源码ZIP最终大小。前包738,099,619 bytes的12字节差异仅为`ubuntu-tools.inputs.json` CRLF转LF；JSON语义和归档二进制保持，不称整packet全部字节相同。配置工具链后按recipe构建，无需独立bundle，不用未知latest/任意旧缓存或删减内容绕过门禁。

F8当前999 Java证明为996项精确未变与3项重新formatter检查0needs，完整adoption999继续约束；之前38批读取、20变更实查/sourceSHA复用、43独立style文件token等价和1处重复注释fixed point均保留。F7正式构建发生宿主Core重绑定竞态并失败，原始日志/XML保留；三文件修复已有22项宿主正控通过，旧Core同socket seam/旧构造缺清理两负控各1项/1预期失败。仅作宿主证据，不称手机复现，不估算F8正式测试数；最终源码与APK在全部修复后对应重建。

APK的约85MB offline-rootfs是经过打包变换的层，不能还原约304MB原始rootfs；本轮不提供“从APK恢复全部构建输入”的helper或承诺。完整源码ZIP的最终SHA-256写在Release正文，不放进该ZIP自身README形成自引用。

完整源码保留构建必需、已有跟踪且具备notice/锁的小型JNI/vendor/解释器预编译依赖；来源未知或闭源限制照实披露，这不等于所有vendor native均可从源码复现。按实际材料说明来源、许可与构建路径，不为“纯源码”口号删除构建消费者依赖。保留清单还包括27个必需历史构建源码和唯一website测试夹具，不进行网站部署。

不上传私钥、用户数据、设备截图/原日志或历史审计原材料。大生成归档不入Git，Release源码ZIP只收登记并核验的原始大构建输入。`docs/audits/`仅保留以下6个构建/CI实际消费的JSON，维持原路径；这不是开放整个历史审计目录的例外：

- `docs/audits/build154/retired-audit-sources.json`
- `docs/audits/build154/engineering-retired-sources.json`
- `docs/audits/build156/retired-test-control-sources.json`
- `docs/audits/build156/third-party-license-sources.json`
- `docs/audits/build156/retired-asset-sources.json`
- `docs/audits/build156/retired-environment-data-sources.json`

完整生成入口：

```bash
python3 tools/prepare-dsh-runtime.py
python3 tools/build-standard-runtime.py
python3 tools/prepare-ubuntu-tools.py
python3 tools/prepare-runtime-tools.py
python3 tools/generate-builtin-plugins.py
python3 tools/generate-credential-paths.py
node tools/prepare-web-compat.mjs
python3 tools/prepare-backup-assets.py --write
python3 tools/prepare-runtime-descriptor.py --write
python3 tools/prepare-test-runtime.py
```

基础 Ubuntu 原始归档仍是单独固定输入，不能把依赖安装或 dsh 升级误当成基础环境版本升级。独立应急的历史 pinned 副本要保留，不能因正式归档变化自动解除摘要核对。

## 验证

源码快速层不依赖私钥：

```bash
python3 tools/run-host-tests.py --check-manifest
python3 tools/run-host-tests.py --stage fast
python3 tools/check-source-hygiene.py
```

完整输入准备后，运行当前正式源集的单测和 Release Lint：

```bash
./gradlew :app:testStandardDebugUnitTest :app:testLowDebugUnitTest \
  :app:lintStandardRelease :app:lintLowRelease
python3 tools/verify-stability.py --stage software
python3 tools/run-host-tests.py --stage runtime
```

不能运行 AGP/aapt2 的宿主可以使用 `tools/run-unit-tests.py` 作为纯逻辑后备；它编译测试辅助类，但不替代 Android 构建。`-O` 测试被入口拒绝，发布核验的检查也不会被 Python 优化删去。

`ci-fast` 执行源码和协议检查。完整包 `ci-package` 需要核对过的二进制输入，并且只生成未签名包；不得用于安装。仓库保护、线上工作流完成和实际 Linux/Android 矩阵必须以真实运行证据记录，不由配置文件推导。

## 正式打包

显式配置 `DSHA_KEYSTORE`、`DSHA_KEYSTORE_PASSWORD`、`DSHA_KEY_PASSWORD`，可另设历史 `DSHA_KEY_ALIAS`。缺少口令不再隐式使用缺省值。只允许已核对的 E7E3 历史证书，不换成 debug、A3 或临时 CI 证书。

```bash
./gradlew :app:assembleStandardRelease
```

本轮发布Standard，实际包必须核对`com.dsh.client`、code190、E7E3、`debuggable=false`、minSdk30、arm64、资产、许可、ELF、插件与独立应急门禁；两版受影响测试与Release Lint结果分别记录。若后续另行构建Low，仍需明确核对v1/v2/v3与minSdk23；本轮没有新Low发布或手机验收结论。

本轮暂停所有手机操作，不安装、启动、停止、输入或对话，不为补证重新连接ADB。历史同签名覆盖记录保留原包归属，不转记新包；不卸载、不清数据、不注入故障、不生成额外审计APK。

本地正式Standard路径为 `<local-workspace>`；其他历史版本与旧Low原字节保持。最终APK、源码commit/附件和软件证据精确绑定，检查选择器、缓存/增量、警告、跳过及未跑范围如实记录，不称完整冷网络已验。新私有仓库已创建，Release只附正式APK与完整源码ZIP；没有实际tag/上传结果前不声称发布完成，原仓库与dsha.cc不变。

## 修改与评审

分支默认 `codex/`。保持旧 SharedPreferences 键、数据格式读取、文档 ID、原件与未知进程停止屏障。明确记录更改的职责、持久化字段、协议和验证证据。

修缺陷要用实际生产入口或隔离真实依赖重现；源码约束检查和设备运行证据分别记录。原始数据只读，历史记录标版本与时间；没有硬件证据的情况不写成已验证。所有审计项都有处置和残余，不能用一个白名单或未经校准的分数宣称债务已清。
