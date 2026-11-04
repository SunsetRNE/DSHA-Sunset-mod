# DSHA 0.2.1-rc.5 · build308

此分支收录 DSHA 的 Android 源码、正式签名 APK 和修复报告。应用包名为 `dsh.android.client`，签名沿用 DSHA 的 E7E3 证书。Sunset 主线应用使用独立包名，二者拥有各自的数据目录。

**[下载正式 APK](release/0.2.1-rc.5/dsha-0.2.1-rc.5.apk) · [详细修复与实测报告](docs/releases/v0.2.1-rc.5-build308-native-grants-20261104.md) · [查看校验数据](docs/releases/build308-verification.json)**

| 项目 | 值 |
|---|---|
| 版本 | `0.2.1-rc.5-20261104.1412-dsh0.2.1-rc.5` / 308 |
| 设备范围 | Android 11 及以上，arm64-v8a，Standard |
| APK 大小 | 299,238,103 字节（285.38 MiB），比 build307 增加 4,063 字节 |
| SHA-256 | `1deb2744d0ec29004c89186bcfe0e1f6526579e83346d19e4347385e56e9b221` |
| 内置组件 | DSH 0.2.1-rc.5、移动 UI 3.2.3、系统 WebView |

从 GitHub 文件页使用 **Download raw file** 可取得 APK。克隆本分支后运行 `git lfs pull` 可下载同一文件。安装前请将已在运行的 DSHA 环境正常停止。包名与签名相符的现有 DSHA 安装可用这个版本覆盖升级。

build308 绑定外接设备确认时的目标，收紧只读调试命令的参数范围，并保留已有识屏行为。指定 Android 17 平板的实测包括覆盖升级、新运行时身份、真实模型写入与读回、备份完整性和正常停止。首次与热启动分别为 3.8 秒和 3.0 秒，计时终点为后端鉴权就绪。

[构建说明](BUILD.md) · [固定构建输入](docs/fixed-build-inputs.md) · [T44 授权决定与实验](docs/design/native-grant-integrity-20261103/LOCAL-DECISIONS.md) · [English](README.en.md)
