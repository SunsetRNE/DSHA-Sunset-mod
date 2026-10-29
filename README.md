# DSHA Sunset Mod

> 基于 [DSH-APP/DSHA](https://github.com/DSH-APP/DSHA) 的第三方 **Mod 版本**，不是 DSHA 官方发行版。
> 在 Android 手机上运行完整的 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)：免 ROOT、免 Termux，装完即用。

## 当前状态

| 项目 | 值 |
|---|---|
| 版本 | `0.2.1-rc.3-20261029.1500-dsh0.2.1-rc.3` |
| versionCode | 207 |
| 主线分支 | `Sunset-3090-Q` |
| 包名 | `dsh.android.client`（可与旧 `com.dsh.client` 并排安装） |
| 系统 | Android 11+ / arm64 |
| 许可 | MIT（沿用原版） |

## 这是什么

DeepSeek Harness 是 DeepSeek 官方的 agent harness，为 glibc Linux 编写；直接跑在 Android 上会撞上原生模块编译、SELinux 拦截、沙箱启动与桌面排版等问题。

DSHA 把这些封进一个 APK：内置完整的 Ubuntu 环境，`apt`、交互式 PTY 与需要编译的原生模块都能用；首次启动解压并校验离线环境，随后在 Web UI 中配置提供商、模型与 API Key，不需要 Termux、ROOT 或命令行。

本仓库是在原版基础上维护的 Mod，改动集中在终端、设备能力与移动端界面。

## Mod 主要改动

- **Root 命令三态拦截（build207）**：严格（默认白名单）、放宽（拦截不可逆操作）、完全放宽；入口在 设置 → 设备能力授权 → Root 命令拦截。仅作用于 Root 通道，Shizuku / ADB 仍按严格档，短信读取始终需要原生授权。
- **终端增强**：独立全屏终端、会话侧栏（三向态与横屏压缩）、附加键、输出“更多…”面板、输出预览 / 复制 / 分享 / 保存与响应式侧栏。
- **移动界面 v5**：插件页卡片与筛选、设置二级页组件化、页面与弹窗动效、启动状态与悬浮球细节统一。
- **设备能力**：无障碍读屏与点按、ADB / Shizuku / Root 三条命令通道、短信读取闸门、虚拟屏、通知、剪贴板与传感器等宿主能力。
- **Web UI 与访问**：移动端适配界面、局域网访问与鉴权、动态端口。
- **备份与恢复**：加密备份（v5 格式）、升级与恢复、外置工作区保护。
- **插件生态**：内置插件与插件包安装、权限与状态管理。
- **可靠性与应急**：最小权限模型、自检诊断、应急恢复入口、后台 Web 恢复。

## 分支说明

本仓库保留 5 条工作分支；`main` 当前指向 `Sunset-3090-Q` 的内容。

| 分支 | 定位 | 最近提交 |
|---|---|---|
| `Sunset-3090-Q` | 主线：终端、界面、Root 三态拦截（build207） | 2026-10-29 |
| `Sunset-zhongdua-G` | 终端与设备回归线（横屏 / 附加键） | 2026-10-26 |
| `codex/prerelease-190` | 预发布基底（上游同步起点） | 2026-10-25 |
| `plugin-state-fix-20261025` | 插件状态与执行器修复线 | 2026-10-25 |
| `v5-base-build201` | UI v5 基线 | 2026-10-26 |

各版本的详细改动与验证边界见 [CHANGELOG.md](CHANGELOG.md) 与 [docs/releases/](docs/releases/)。

## 安装与迁移

1. 获取与目标签名一致的 APK（本仓库不提供公开 APK 资产，构建方式见下节）。
2. 首次启动会解压并校验内置离线环境；完成后进入主界面，Web 服务在后台启动。
3. 打开 Web UI，在设置中配置提供商、模型与 API Key。
4. 迁移旧版本时先导出备份再恢复；不同包名的数据与系统授权互不继承。

## 从源码构建

需要 JDK 17、Node 24、Python 3.9+、Android SDK 37 与 NDK 26。先按 [CONTRIBUTING.md](CONTRIBUTING.md) 准备锁定资产，工具链与签名配置见 [BUILD.md](BUILD.md)。

```bash
python3 tools/run-host-tests.py --check-manifest
python3 tools/run-host-tests.py --stage fast
./gradlew :app:testStandardDebugUnitTest :app:testLowDebugUnitTest \
  :app:lintStandardRelease :app:lintLowRelease
./gradlew :app:assembleStandardRelease
```

Windows 使用 `gradlew.bat`。测试任务名中的 Debug 只表示测试变体，不要求生成或安装调试 APK。签名私钥、用户数据、设备截图与日志不进入仓库。

## 文档

| 文档 | 内容 |
|---|---|
| [AGENTS.md](AGENTS.md) | 项目结构、启动契约与技术约束（新贡献者入口） |
| [docs/documentation-index.md](docs/documentation-index.md) | 完整文档索引 |
| [docs/security-model.md](docs/security-model.md) | 安全模型：权限边界与已知弱点 |
| [docs/plugins.md](docs/plugins.md) | 插件安装与打包要求 |
| [docs/virtual-screen-architecture.md](docs/virtual-screen-architecture.md) | 虚拟屏架构与前提 |
| [BUILD.md](BUILD.md) / [CONTRIBUTING.md](CONTRIBUTING.md) | 构建、签名与贡献流程 |
| [CHANGELOG.md](CHANGELOG.md) | 完整更新记录 |

## 兼容性与已知限制

- 仅 arm64、Android 11+；不同 ROM 的权限、后台与省电策略差异较大。
- 虚拟屏、Root 档位与短信读取依赖具体系统版本和原生授权状态。
- 部分 vendor 原生依赖不保证完全从源码复现；首次解压离线环境耗时较长。
- 电脑端连接能力已从当前版本撤下；手机控制与本机 / 局域网能力保留。

## 致谢与许可

本项目是 [DSH-APP/DSHA](https://github.com/DSH-APP/DSHA) 的衍生 Mod，沿用原版 [MIT](LICENSE) 许可（Copyright (c) 2026 qiannianhuanxiang），保留原版版权与许可声明。

上游本体为 [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness)；容器层使用 [proot](https://github.com/termux/proot) / [proroot](https://github.com/coderredlab/proroot)，终端基于 [Termux terminal-view](https://github.com/termux/termux-app)，移动端适配基于 [dsh-web-mobile](https://github.com/mexiaosqwq/dsh-web-mobile)，设备命令备用通道为 [Shizuku](https://shizuku.rikka.app/)。完整第三方许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

感谢原版作者 [@qiannianhuanxiang](https://github.com/qiannianhuanxiang) 与所有贡献者。
