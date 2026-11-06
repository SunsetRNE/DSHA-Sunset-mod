# x86_64 模拟器适配（独立调试线）

> 目标：在一台普通电脑的 Android 模拟器上跑通完整容器链路（部署 → DSH 启动 → 工作台 → 容器工具 → 容器投放模块），
> 用于没有 arm64 真机时的功能验证。**不加开关时构建产物与上游完全一致**，正式线不受任何影响。

## 一、隔离原则

- 只有显式 `-Pdsha.emulatorX64=true` 才进入模拟器目标：
  - 构建目录切到 `app/build/emulator-target`（不污染正常输出，Windows 上也不会和正式构建抢文件锁）；
  - `abiFilters=x86_64`、`applicationIdSuffix=.emulator`、`versionNameSuffix=-EmulatorX64Debug`、`BuildConfig.EMULATOR_X64=true`；
  - `sourceSets` 整体切到模拟器专属资产与原生库目录；
  - release 变体被 `beforeVariants` 停用（只出 debug）；
  - 与 `dsha.sunsetTest` 互斥，防止测试身份混包。
- 不加开关时：`abiFilters=arm64-v8a`，资产与 JNI 与上游一致，`packagingOptions` 继续排除 x86/x86_64。

## 二、四个脚本（产物均不入 Git）

| 脚本 | 产物 | 作用 |
|---|---|---|
| `tools/build-emulator-native.py` | `app/build/emulator-native/jniLibs/x86_64/`（7 个 ELF） | 用 NDK 从锁定的 proot/talloc/shmem 源码编译 x86_64 版 proot、loader 与 termux JNI |
| `tools/prepare-emulator-assets.py` | `app/build/generated/emulatorAssets/`（479 项） | 以标准资产为底，逐项替换为 x86_64 内容：x86_64 dpkg 配置根、x86_64 sshd/ssh-keygen、`dsh-runtime.bin` 按 linux-x64 重建覆盖层，并生成独立回执 `emulator-assets-receipt.json` |
| `tools/prepare-emulator-platform-packages.py` | 向模块树补齐 linux-x64 可选依赖 | Windows 上 `npm install` 只装 win32 平台包；缺 `sharp`/`koffi`/`require-builtin`/`cua`/`ripgrep`/`sherpa` 的 linux-x64 变体时，容器内 DSH 试运行会以 `TRIAL_NATIVE_MODULES_FAILED` 失败 |
| `tools/verify-emulator-assets.py` | 校验回执与 ELF 架构 | 挂在 `preBuild`，缺资产或口径不符时**直接失败**，不会静默打出没有 x86_64 JNI 的坏包 |

## 三、怎么用（三步）

### 第 0 步：备四样输入

| 输入 | 要求 |
|---|---|
| NDK | **r26d / 26.3.11579264**（脚本读 `source.properties` 校验） |
| 三个源码归档 | `proot.zip`、`talloc.tar.gz`、`shmem.tar.gz`，上游分别是 termux/proot 5.1.107.92、samba talloc、termux libandroid-shmem；脚本按 `SOURCES` 表里的 SHA-256 校验。每个归档单独一个目录，**目录下必须有解包一层后的 `source/`** |
| x86_64 Ubuntu 根 | 一份 x86_64 Ubuntu 根的 tar 包，且：① 有 `var/lib/dpkg/status`；② 有 `usr/sbin/sshd`、`usr/bin/ssh-keygen`、`usr/lib/x86_64-linux-gnu/libwrap.so.0`；③ 里面所有 ELF 都必须是 x86_64；④ 绝对软链只能指向 `/data/local/tmp/dsha-x86-20261031/rootfs/`。**路径必须在 `app/build/` 下面** |
| 模块树 | `npm ci --prefix tools/dsh-runtime` 之后，用 `prepare-emulator-platform-packages.py` 补 linux-x64 可选包 |

> 自建 x86_64 Ubuntu 根：拿官方 `ubuntu-base-*-amd64.tar.gz` 解开，在 x86_64 机器上 chroot 进去
> `apt install openssh-server libwrap0`，再 `tar czf` 打包。脚本会自动剔除 `etc/ssh/ssh_host_*`、
> `var/cache/apt`、`root/.ssh`、`etc/machine-id` 以及 `node_modules` 里除 npm 外的包。

### 第 1 步：编 x86_64 原生库

```bash
python3 tools/build-emulator-native.py \
  --ndk    /path/to/android-ndk-r26d \
  --proot  app/build/emulator-src/proot \
  --talloc app/build/emulator-src/talloc \
  --shmem  app/build/emulator-src/shmem
```

### 第 2 步：生成模拟器资产

```bash
python3 tools/prepare-emulator-platform-packages.py --node-modules tools/dsh-runtime/node_modules
python3 tools/prepare-emulator-assets.py \
  --configured-rootfs app/build/x86-rootfs.tar.gz \
  --node-modules tools/dsh-runtime/node_modules
```

### 第 3 步：构建并安装

```bash
# Linux/macOS
./gradlew :app:assembleStandardDebug -Pdsha.emulatorX64=true
# Windows PowerShell：-P 参数必须加引号
.\gradlew.bat :app:assembleStandardDebug "-Pdsha.emulatorX64=true"
adb install -r app/build/emulator-target/outputs/apk/standard/debug/app-standard-debug.apk
```

包名 = 正式身份 + `.emulator`（如 `io.github.sunsetrne.dsha.emulator`）。`tools/configure-user-emulator.cmd`
是给 `emulator-5580` 做中文 / 1080x1920 / 120Hz 配置并拉起主页的辅助脚本，按需改端口与设备号。

## 四、边界（必须知道）

1. **产物与输入都不入库**：7 个 ELF、479 项资产、四个输入归档全在 `app/build/` 下，由脚本现场生成。
   别人 clone 后要重建，必须自己备齐上面四样 —— 仓库里没有，也不应该有。
2. **`verifyEmulatorAssets` 挂 `preBuild`**：缺资产或回执会当场失败，不会静默打出坏包。
3. **CI 不覆盖这条线**：Source checks 的五个 job 都不带 `-Pdsha.emulatorX64=true`，
   x86 的 Gradle 接线、原生库编译、资产打包在 CI 里没有编译验证，改完必须本地跑一遍。
4. **只用于调试**：不要用它出正式包；arm64 正式线的产物、资产摘要、包名与签名都不受影响。

## 五、验证记录（2026-11-05，Android 15 x86_64 模拟器）

- 冷安装 → 引导 → 部署 8 阶段全过；容器内真实输出 `DSHA_COLD_RUNTIME_READY` / `DSHA_UBUNTU_TOOLS_READY`；隔离试运行通过；
- `proot · dsh 0.2.1-rc.5` 就绪（约 23s），工作台 WebView 加载真实前端；
- 容器工具真实执行（日志落容器 `/tmp/dsha-tool-<id>.log`），运行中/已退出状态上报正确；
- 容器写 `~/.dsha-modules/*.json` → 宿主自动收取 → 主页出现原生卡片 → 按钮执行脚本返回结果。

> 该记录基于 build316 时代的源码；本分支从当前 main 重放接线，未重新跑模拟器（手上无可用模拟器实例）。
