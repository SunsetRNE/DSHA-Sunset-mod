# build369 发布与资产复核回执（2026-11-16）

状态：**已发布并完成下载复核；尚未进行本版设备验收。**

## 发布身份与来源

- [GitHub Release](https://github.com/SunsetRNE/DSHA-Sunset-mod/releases/tag/dsha-a-su-build369-20261116-080147)：`dsha-a-su-build369-20261116-080147`，由正式发布流水线创建的非 draft prerelease，发布时间 **2026-11-16 08:19:19 UTC（UTC+8：16:19:19）**。
- tag 指向 `82eb958db6a7d8f0c402b1fc4d41c43be94ed624`；已用 `git ls-remote` 核验。发布后文档收尾提交不改变此 tag 或发布资产。
- 正式发布 [Actions run 43828110344](https://github.com/SunsetRNE/DSHA-Sunset-mod/actions/runs/43828110344)，attempt 1，`expected_sha=82eb958d…`、`test_packages=debug+dev`、`rust_native_mode=prebuilt`、`dry_run=false`，整轮 success，publish 节点 success。只触发一次正式 dispatch。
- 版本 `0.2.1-rc.5-20261116.0740-dsh0.2.2-rc.1` / versionCode 369 / `io.github.sunsetrne.dsha` / DSH-A-Su / Android 11+（API 30）arm64 / 单一 Standard。
- 本版包含 capabilities 6 类、runtime 命令与挂载组装 8 类、plugins 管理 11 类，共 25 个生产类的 Kotlin 整域迁移；A8-P3 多模块首切不在本版。

## 实际下载与摘要

7 项资产已实际下载，逐文件大小与 SHA-256 同时匹配 GitHub API `digest` 和发布的 `SHA256SUMS`；其中校验和文件自身由 GitHub API digest 复核。未修改任何已发布资产。

| 资产 | 字节数 | SHA-256 |
|---|---:|---|
| 正式 APK `dsha-a-su-82eb958d.apk` | 317,768,722 | `6c4337fc1f87547be4a76d156f386a9d259df1045315594bf8f41bfc167cec28` |
| Debug APK `dsha-a-su-debug-82eb958d.apk` | 323,486,020 | `767e1812ea1fd89d69a738e5efdfe86e7b7b6a512fb72a7e6b0a3eb2fc08555f` |
| Dev APK `dsha-a-su-dev-82eb958d.apk` | 317,764,626 | `84e20d007b96be17e0a62cbd56ab3d892631e12f56fff743a444cbbf7683017b` |
| `dsha-a-su-build-evidence.zip` | 702,202 | `bad73784bf7c1cfb02dc5409198071d51499a4abe25019bf0edfa4c4c617bba0` |
| `build-info.json` | 6,546 | `5670ce99931052f33c261a5976c42845154aee2699183bd826b95508121953e5` |
| `dsha-a-su-test-cert.pem` | 1,559 | `cc18c4e994898a673dafc629566b674192d979af93d782469d293dbf192f8980` |
| `SHA256SUMS` | 544 | `266a4309052485e659b10d4b1f33a95e040bd80073cea996560b833bbeb46c4b` |

## 软件、签名与原生来源复核

- 发布证据与独立读取的 JUnit XML 一致：**Standard / `:app` 5,338 项，0 failure / 0 error / 0 skipped**；七个规定外部夹具各执行一次并通过。当前 source-layout 仅有 `:app`。`build-info.json` 的单测字段为 `dsha-unit-summary`、`aggregate=false`，本回执不将它表述为跨模块全仓聚合证明。
- 生产与两个测试 APK 均使用本机 JDK 17 的 `apksigner.jar verify --min-sdk-version 23 --verbose --print-certs` 独立复核，**v1 / v2 / v3 全部 true**。23 仅用于实际核验 JAR 签名，APK 自身最低系统仍为 API 30；直接按包的 minSdk=30 验证会只采用 v3，不能将输出中 v1/v2 的 false 误判成签名缺失。
- 正式证书 SHA-256：`a4b0413c67fa46dd30dc7e2bce3ad56151ed8bfde2a3315fff685baf786b5d5d`，与发布身份和实际 APK 一致。
- 两个测试包分别为 `io.github.sunsetrne.dsha.debug`（debuggable）与 `.dev`（非 debuggable），同 commit/run、同版本主体，测试证书 SHA-256 `ed9693a6779a92fd94f2f0f42ad4a3e60086b3c3aea2853beff1ed6fcff8c141`；公开 PEM 的 DER 摘要与两包实际签名一致，并与正式证书分离。
- 三包内的 runtimeId 均为 `1d42f79d5fea6bad5caaa2409822aa5949691c89e6e230b95011ae35db8a5757`。517 个启动输入 / 205 个资产输入，含 414 Java + 43 Kotlin；baseVersion 10、DSH 0.2.2-rc.1、数据格式保持。
- 三包均用仓库的 Rust APK 静态验证器重新核验：嵌入回执与证据包回执逐字节一致，绑定 commit `82eb958d…` / run `43828110344` / attempt 1 / 本仓库。arm64 `libdsha_rs.so` 为 360,920 B，SHA-256 `65aea50e7d34b8adfda28836248b2486b4d5481abb55d7bb80037a8adb459c6d`，ABI/ELF/依赖/符号/预算检查通过；这只证明静态内容与来源，不证明 Android 装载。
- 固定输入 bundle SHA-256 `2c8dc3fa494a5faf6f65cafa966b47d234a3331c30396198005cedfccca82cad`，与本版锁和构建回执一致。

## 证据位置与范围

仓库外 `/tmp/dsha-build369-release-6313fce2/` 保留下载资产、GitHub Release/run 元数据及 `verification-verified/post-release-verification.json`、三包签名输出与 Rust 静态回执。本地 `gh release download` 无法写入容器临时目录，未下载资产；随后使用容器内 curl 下载并逐 SHA-256 复核。无结果未知的发布动作被重放。

本版未安装或操作手机。368→369 首次启动预期一次整屏运行环境维护页并保留个人数据，但覆盖升级、维护页/READY、Rust Android 装载与回退、备份/恢复、SAF、双屏敏感页及 16 KB 页内核均未完成本版真机验收。build368 的设备回执不作为 build369 的通过证据。

准备范围与本地门禁见 [build369 发布准备回执](build369-preparation-20261116.md)。
