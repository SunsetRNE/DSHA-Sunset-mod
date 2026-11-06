# 模块平台与 x86_64 模拟器适配（对比上游 build316 / rc.5 的变更说明）

> 给维护者的说明。本分支在上游 `SunsetRNE/DSHA-Sunset-mod` build316（0.2.1-rc.5）之上做了两块工作：
>
> - **A 模块平台**：纯新增能力，全部集中在独立的 `module/` 包与 `ui/` 新增类里，不改 ARM 产线任何输入；
> - **B x86_64 模拟器适配**：一条与 ARM 完全隔离的调试构建线（显式开关才启用），让电脑上的 Android 模拟器能跑完整部署与真实容器。
>
> 两块互不交叉；B 对 ARM 产线唯一的触碰是一个跨架构 bug 修复（`O_DIRECTORY` 常量），这个修复对 ARM 真机同样是修复潜伏问题（见 §3.4）。

## 一、模块平台（万物皆模块）

### 1.1 设计原则

- 扩展点声明式：模块在 `module.json` 里声明 `provides.<surface>`，宿主按声明挂载，不给模块隐式权限。
- 原生自绘而不是网页盖壳：主页卡片、底栏、状态条都是原生 View，模块只提供数据与素材；`module.web` 是唯一的受控 WebView 面。
- 能力总线：`ModuleServices` 把宿主能力（读布局、改文案、登记容器工具、定时任务）按能力点暴露，未声明即不可用。
- 签名与安全：Ed25519 模块签名；危险动作（脚本执行、容器命令）每次弹窗确认；`ModuleCrashGuard` 记录归因，连续崩溃自动停用并在下次启动提示。

### 1.2 主要文件（全部为新增，独立成包）

| 位置 | 内容 |
|---|---|
| `app/src/main/java/com/deepseekharness/app/module/` | `ModuleCard`（声明解析）、`ModuleCardStore`（安装/启停/容器收取）、`ModuleServices`（能力总线）、`ModuleImport`（zip/json 导入）、`ModuleExec`（受控脚本执行）、`ModuleProgram`（受管 ELF 程序）、`ContainerTools`（容器工具登记）、`ModuleCrashGuard`（崩溃看门狗）等 |
| `app/src/main/java/com/deepseekharness/app/ui/` | `HomeCanvas` / `HomeTile` / `HomeMasonryLayout`（主页画布与编辑态）、`HomeCardSkin`（卡片换肤）、`ModuleCenterActivity` / `ModulePageActivity`（模块中心）、`ModuleAppHost`（`ui.app` 全屏接管）、`ModuleThemeLayer`、`EditableBottomNavigationView` + `BottomNavDragController`（可编辑底栏） |

### 1.3 能力面（`provides.*`）

`ui.app`（主页整卡/全屏接管）、`ui.overlay`（悬浮立绘）、`ui.theme`（调色）、`ui.nav`（底栏外观）、`ui.skin.home.cards`（原生卡片换肤：底色/背景图/圆角/文字/图标色）、`ui.skin.home.progress`（波浪状态条换肤）、`module.program`（容器内受管 ELF）、`module.web`（受控网页）、`module.container.tool`（容器工具）。

### 1.4 容器 → 壳的通道（模型可自助装机）

- 容器里的 dsh 把 `card.json` 写到 `~/.dsha-modules/`，宿主回主页自动收取（同 id 幂等更新，成功后原件改名 `.done`）；
- 模块格式说明书 `HOW-TO.md` 随首次部署投放到容器同一目录，容器内的模型能直接读懂格式并自己写模块；
- 容器工具：`~/.dsha-launch/<id>.json` 登记（id/名称/容器内命令/端口），壳内「启动」键直接执行；壳内模块中心也能新建/编辑/删除同一份登记，两边看同一张列表；
- 启动卡副标题与主按钮随真实入口变化：选中容器工具后按钮变成「启动 <工具名>」，运行中显示「重启」。

### 1.5 可编辑主页与底栏

- 编辑态：长按或顶栏铅笔进入；卡片拖动换位、四角 L 形手柄改宽高、每卡「恢复默认/收起」、全部恢复/完成四条退出路径；
- 底栏：单指拖动换位（跨格即时持久化）、双指捏合缩放按钮、轻点按钮退出编辑（`EditableBottomNavigationView` 分发完整多指流）；
- 全部恢复：同时复位卡片布局与底栏顺序/缩放。

## 二、x86_64 模拟器适配（与 ARM 完全隔离）

### 2.1 隔离原则

- 不加开关时，构建产物与上游完全一致（arm64-v8a 正式线）；
- 只有显式 `-Pdsha.emulatorX64=true` 才进入模拟器目标，此时：
  - 构建目录切到 `app/build/emulator-target`（不污染正常输出）；
  - `abiFilters=x86_64`、`applicationIdSuffix=.emulator`、`versionNameSuffix=-EmulatorX64Debug`、`BuildConfig.EMULATOR_X64=true`；
  - `sourceSets` 整体切换到模拟器专属资产与原生库目录；
  - 与 `dsha.sunsetTest` 互斥，防止测试身份混包。

### 2.2 资产与原生库（均不入 Git，由脚本生成）

| 脚本 | 产物 | 说明 |
|---|---|---|
| `tools/build-emulator-native.py` | `app/build/emulator-native/jniLibs/x86_64/`（7 个 ELF） | 用 NDK 从锁定的 proot/talloc/shmem 源码编 x86_64 版 proot/loader/termux JNI 等 |
| `tools/prepare-emulator-assets.py` | `app/build/generated/emulatorAssets/`（479 项） | 以标准资产为底，逐项替换为 x86_64 内容：x86_64 dpkg 配置根、x86_64 sshd/ssh-keygen、`dsh-runtime.bin` 按 linux-x64 重建覆盖层，并生成独立回执 `emulator-assets-receipt.json`（含 runtimeId 与源码指纹） |
| `tools/prepare-emulator-platform-packages.py`（本分支新增） | 向模块树补齐 linux-x64 可选依赖 | Windows 上 `npm install` 只装 win32 平台包；缺 `@img/sharp-linux-x64`、`@koromix/koffi-linux-x64`、`@deepseek-ai/node-addon-system-linux-x64`、`node-addon-require-builtin-linux-x64-gnu`、`@trycua/cua-driver-linux-x64-gnu`、`@vscode/ripgrep-linux-x64`、`sherpa-onnx-linux-x64` 时，容器内 dsh 试运行会以 `TRIAL_NATIVE_MODULES_FAILED` 失败。脚本按 `optionalDependencies` 自动找齐 glibc 变体，`--check` 可复核 |

### 2.3 模拟器验证结果（Android 15 x86_64 模拟器实测）

- 冷安装 → 引导 → 部署 8 阶段全过；容器内真实输出 `DSHA_COLD_RUNTIME_READY` / `DSHA_UBUNTU_TOOLS_READY`；隔离试运行通过；
- `proot · dsh 0.2.1-rc.5` 就绪（约 23s），工作台 WebView 加载真实前端（「探索未至之境」）；
- 容器工具真实执行（日志落容器 `/tmp/dsha-tool-<id>.log`），运行中/已退出状态上报正确；
- 容器写 `~/.dsha-modules/*.json` → 宿主自动收取 → 主页出现原生卡片 → 按钮执行脚本返回结果，全链路打通；
- 运行时受管更新（runtimeId 变化触发）后，容器内模块与工具登记完整保留。

### 2.4 怎么用：在自己的机器上重建 x86 包

代码全在仓库里，产物和构建输入不在（`app/build/` 下，按设计不入 Git）。照着下面三步即可重建。

**第 0 步：备四样输入**

| 输入 | 要求 | 放哪 |
|---|---|---|
| NDK | **r26d / 26.3.11579264**（脚本会读 `source.properties` 校验） | 任意路径，传 `--ndk` |
| 三个源码归档 | `proot.zip`、`talloc.tar.gz`、`shmem.tar.gz`；上游分别是 termux/proot 5.1.107.92、samba talloc、termux libandroid-shmem。脚本按 `SOURCES` 表里的 SHA-256 校验（`29385d1d…` / `dc46c40b…` / `1e5ff845…`），对不上会报 `EMULATOR_SOURCE_HASH` | 每个归档单独一个目录，**目录下必须有解包一层后的 `source/`**，如 `app/build/emulator-src/proot/{proot.zip, source/…}` |
| x86_64 Ubuntu 根 | 一份 x86_64 Ubuntu 根目录的 tar 包，且：① 有 `var/lib/dpkg/status`（脚本拿它算 package_lock）；② 有 `usr/sbin/sshd`、`usr/bin/ssh-keygen`、`usr/lib/x86_64-linux-gnu/libwrap.so.0`；③ 里面所有 ELF 都必须是 x86_64（否则 `EMULATOR_FOREIGN_ELF`）；④ 绝对软链只能指向 `/data/local/tmp/dsha-x86-20261031/rootfs/`（否则 `EMULATOR_HOST_LINK`）。**路径必须在 `app/build/` 下面**（脚本强制 `relative_to`） | 例如 `app/build/x86-rootfs.tar.gz` |
| 模块树 | `npm ci --prefix tools/dsh-runtime` 之后，用 `prepare-emulator-platform-packages.py` 补 linux-x64 可选包 | `tools/dsh-runtime/node_modules` |

> 自建 x86_64 Ubuntu 根：拿官方 `ubuntu-base-*-amd64.tar.gz` 解开，在 x86_64 机器上 chroot 进去
> `apt install openssh-server libwrap0`，再 `tar czf` 打包。脚本会自动剔除 `etc/ssh/ssh_host_*`、
> `var/cache/apt`、`root/.ssh`、`etc/machine-id` 以及 `node_modules` 里除 npm 外的包，所以不用手工清理。

**第 1 步：编 x86_64 原生库**（7 个 ELF → `app/build/emulator-native/jniLibs/x86_64/`）

```bash
python3 tools/build-emulator-native.py \
  --ndk   /path/to/android-ndk-r26d \
  --proot  app/build/emulator-src/proot \
  --talloc app/build/emulator-src/talloc \
  --shmem  app/build/emulator-src/shmem
```

**第 2 步：生成模拟器资产**（479 项 → `app/build/generated/emulatorAssets/`）

```bash
python3 tools/prepare-emulator-platform-packages.py --node-modules tools/dsh-runtime/node_modules
python3 tools/prepare-emulator-assets.py \
  --configured-rootfs app/build/x86-rootfs.tar.gz \
  --node-modules tools/dsh-runtime/node_modules
```

**第 3 步：构建 x86 调试包**

```bash
# Linux/macOS
./gradlew :app:assembleStandardDebug -Pdsha.emulatorX64=true
# Windows PowerShell：-P 参数必须加引号
.\gradlew.bat :app:assembleStandardDebug "-Pdsha.emulatorX64=true"
```

产物在 `app/build/emulator-target/outputs/apk/standard/debug/`。开关效果：`abiFilters=x86_64`、
`applicationIdSuffix=.emulator`、`versionNameSuffix=-EmulatorX64Debug`、`BuildConfig.EMULATOR_X64=true`，
构建目录与 ARM 线完全隔离（`app/build/emulator-target`），不加开关时产物与上游一致。

**第 4 步：装到模拟器**

```bash
adb install -r app/build/emulator-target/outputs/apk/standard/debug/app-standard-debug.apk
```

包名是正式身份 + `.emulator`（如 `dsh.android.client.emulator`）；`tools/configure-user-emulator.cmd`
是给 `emulator-5580` 做中文/1080x1920/120Hz 配置并拉起主页的辅助脚本，按需改端口与设备号。

**三点边界**：

1. `verifyEmulatorAssets` 挂在 `preBuild`，缺资产或回执会当场失败（不会静默打出没有 x86_64 JNI 的坏包）。
2. 五个 CI job **都不带** `-Pdsha.emulatorX64=true`，x86 这条线在 CI 里没有编译覆盖，改完必须本地跑一遍。
3. 这个目标只用于调试模拟器，不要用它出正式包；ARM 正式线不受任何影响。

## 三、顺带修复的上游相关 bug

1. **`O_DIRECTORY` 跨架构常量错误**（`AndroidBackupFileSystem` / `AndroidDeviceFiles`）：原值 `040000` 是 arm32 的 `O_DIRECTORY`，在 64 位 ABI（arm64 与 x86_64 共用 asm-generic）上是 `O_DIRECT`——用 `O_DIRECT` 打开目录内核必返 `EINVAL`。x86_64 上表现为部署中断 `FILESYSTEM_22`；**对 ARM 真机这是同一个潜伏 bug**，已改为 `0x10000`（含测试常量同步）。
2. **模拟器打包接线缺失**：`packagingOptions` 无条件排除 `**/x86_64/**`、`sourceSets` 未按模拟器目标切换、`beforeVariants` 停用 release 变体后 `verifyBackupDependencies` 引用了不存在的配置——三处已修，排除规则按目标 ABI 分流。
3. **`CardPage` 系统栏 inset**：Android 15 `EDGE_TO_EDGE_ENFORCED` 下，独立 Activity 页面（模块中心/诊断/各设置子页）页脚按钮压在导航手势区里，点「返回」可能无效；已按 inset 让页脚避让。
4. **编辑态工具条对齐**：选中卡片的「收起/恢复默认」工具条改为右对齐并夹在卡片宽度内（此前左对齐会溢出压到隔壁卡片，视觉上像「框与卡片错位」）。
5. **顶栏按钮一致性**：编辑/下载/关于三枚按钮图标统一 20dp + 同色调（原为 20/22/18dp 且编辑无色调）；编辑按钮补 `ui2_edit_home` 十语文案。
6. **设置页模块中心入口**：`ShellCard`（实底）改 `ShellGroup`（透明底细边），与同页其他分组一致。
7. **关于页架构显示**：写死 `arm64-v8a` 改为跟 `BuildConfig.EMULATOR_X64`（模拟器显示 `x86_64`）。

## 四、本分支未包含的内容

- 本地工作日志（`交接文件.md`、`AGENTS.md` 轮次记录）不在本分支提交范围；
- 未做：ARM 真机验收、推送原私有仓库；
- 模拟器资产与原生库均为脚本生成物，不入 Git（与上游离线 rootfs 同一策略），配置好 NDK 后按 §2.2 脚本重建即可。
