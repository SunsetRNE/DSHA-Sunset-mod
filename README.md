# DSHA（重构版）

**DeepSeek Harness 安卓启动器** —— 在手机上免 ROOT、免 Termux 跑官方 `@deepseek-ai/dsh`。

这是从零重写、但**保留原 DSHA 主要框架与内容**的重构版：分层结构重新设计，
二进制（proot/proroot）与自愈脚本等「内容」原样搬运，`dsh` 采用上游最新
**0.1.2-rc.1**（`https://github.com/deepseek-ai/deepseek-harness`）。

> ⚠️ 当前为**预发布版（v1.2.0-rc1）**，主要用于收集使用者意见，可能存在不稳定。
> 反馈可加 QQ 群 **975836806**（测试版、问题反馈、插件交流），或提 issue。

---

## 架构

```
┌──────────────────────── APK ──────────────────────────┐
│ 原生 Android 层（纯 Java 17 · Material3）             │
│   ui: 启动/终端/插件/设置   core: 编排   data: 加密    │
│   bridge: 3090 桥   runtime: proot 启动 + LAN 代理     │
├───────────────────────────────────────────────────────┤
│ proroot（默认，零 ptrace）/ proot（兜底）              │
├───────────────────────────────────────────────────────┤
│ Ubuntu 24.04 arm64 · Node.js 24 · pnpm                 │
│   └ @deepseek-ai/dsh 0.1.2-rc.1 → Web UI :3080         │
└───────────────────────────────────────────────────────┘
```

- **语言**：纯 Java 17，无 Kotlin，单 Gradle 模块 `:app`；`applicationId com.dsh.client`。
- **系统要求**：仅 arm64-v8a；build.gradle minSdk 23（Android 6 可装），欢迎页标注 Android 8.0+。
- **离线环境**：内置 Ubuntu rootfs（`assets/offline-rootfs.bin`，**不提交 git，CI 生成**），
  首次启动解压到私有目录；覆盖安装靠 `.offline-version` 版本比对强制干净重解压。

## 现在能做什么

装 APK → 欢迎页点「开始使用」→「启动」页点「启动」：

1. 首次启动解压内置 Ubuntu 24.04 rootfs（几分钟，只一次）；
2. 用真实 proot（`libproot.so`）chroot 进 rootfs，跑 `dsh web --no-open --host 127.0.0.1 --port 3080`；
3. 点「进入对话」：应用内 WebView（GeckoView 兜底）加载鉴权链接，自动种 Cookie 进入对话 UI。

已实现能力：

- **插件管理**：4 个内置插件 + 2 个官方核心，开关即用（`PluginFragment`）；
- **局域网访问**：`LAN 模式` 开启后 3081 代理自动绑定，主页直接显示完整地址，同 WiFi 设备可访问；
- **无障碍屏幕操作**：读屏 / 点按 / 输入 / 按键 / 滑动 / 截屏（配置页「屏幕操作权限」，截屏需 Android 11+）；
- **ADB 无线配对**、危险命令守卫、流式悬浮条、内置终端 PTY、备份恢复、MT 文件提供器、
  API key 经 Android Keystore（AES/GCM）加密。

## 快速开始

```bash
# 依赖：JDK 17 + Gradle 8.5 + Android SDK 34 + NDK 26（本工作区 _toolchains 已备好）
./build.sh :app:testDebugUnitTest   # 纯逻辑单元测试（57 条断言）
./build.sh :app:assembleDebug       # 完整 APK（内置 rootfs，几百 MB）
./build.sh -Pslim :app:assembleDebug   # 精简包（不含内置环境，仅用于已解压设备升级）
```

## 端口契约

| 端口 | 用途 |
|---|---|
| 3080 | dsh WebUI（可配） |
| 3090 | App 能力桥（agent 调 Android） |
| 3081 | 局域网反向代理（LAN 模式） |

## 已知限制（暂未开发）

- **插件市场**：暂不支持从市场安装第三方插件（占位说明）；
- 分步安装器、自愈脚本热更新 + 离线验签、升级数据保护、启动接管等原版成熟模块待回填；
- bash 沙箱隔离不可用（bubblewrap 需要 unprivileged user namespace，Android sepolicy 不给）。

详见 [AGENTS.md](AGENTS.md)。
