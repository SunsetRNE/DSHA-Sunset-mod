# 更新日志

## v1.2.0-rc1（重构版 · 预发布）

从零重写、保留原版框架与内容的干净骨架。`dsh` 升级到上游最新 **0.1.2-rc.1**。

> ⚠️ 本版本为**预发布版**，主要用于收集使用者意见/反馈，可能存在不稳定之处。
> 反馈可加 QQ 群 **975836806**（测试版、问题反馈、插件交流），或在该 Release 下提 issue。

### 新增 / 改进

- **dsh 升级到 0.1.2-rc.1**：内置 Ubuntu 24.04 rootfs + Node 24 + pnpm + `@deepseek-ai/dsh` Web UI（:3080）。
- **覆盖安装不再丢插件/配置**：rootfs 版本比对机制（`.offline-version`），内置包版本变化时先清旧环境再重解压，杜绝新旧文件混装。
- **koffi-linux-arm64 原生模块补齐**：修复 Windows npm 只装 win32 原生导致 rc.1 WebUI 起不来的问题（注入 koffi/sharp/libvips/ripgrep/require-builtin 五个 linux-arm64 包）。
- **插件管理**：4 个内置插件 + 2 个官方核心放进「插件管理」页，支持开关（注册脚本幂等、尊重禁用标记）。
- **局域网通道**：3081 代理自动绑定 + BrowserAuth cookie 自动交换 + settings 持久化补丁；主页直接显示完整局域网地址（点按复制）。
- **无障碍屏幕操作**：读屏 / 点按 / 输入 / 按键 / 滑动 / 截屏（`canTakeScreenshot`，Android 11+），agent 免 ADB 操作手机。
- **ADB 无线配对**、危险命令守卫、流式悬浮条、内置终端 PTY、备份恢复、MT 文件提供器、KeyVault 加密 API key。

### 修复

- WebUI 起不来 / 看门狗无限重启（koffi 原生模块缺失，见上）。
- 局域网连接失败、LAN token 鉴权、settings 不可用（强制 host 持久化）。
- 插件开关级联误触发、切换页签丢列表、打开即显示状态（无动画）。

### 已知限制（暂未开发）

- **插件市场**：暂不支持从市场安装新插件（占位说明）。
- 分步安装器、自愈脚本热更新 + 离线验签、升级数据保护、启动接管等原版成熟模块待按 seam 回填。
- 截屏需 Android 11+；仅 arm64-v8a；build.gradle minSdk 23（Android 6 可装，欢迎页标注 8.0+）。
