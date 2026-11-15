# A7 S4 管理器路径、版本与只读布局审定表

日期：2026-11-14。状态：**Lead 已按本表有限样本与固定 FD probe 方案批准，附 §4 条件；S4 源码等 S1 独立审查 APPROVE 后才开工**。
前置：[任务基线 Q3](a7-task-baseline-20261114.md#9-lead-q1q6-已裁决2026-11-14) 要求 S4 前提交固定来源/版本/布局表；[既有来源范围](../../references-and-provenance.md#51-root管理器源码固定核查范围)原本只核 su，不是模块管理兼容认证。本稿新增固定源的只读补查，不执行上游代码、su、设备命令或手机操作。

## 1. 拟支持的有限样本

管理器枚举保留 `MAGISK/KERNELSU/APATCH`，**不含 LSPosed**。固定路径只用于真实 UID0 的专用 helper；必须逐级 fd/NOFOLLOW 核验父目录与可执行 leaf。可执行 leaf 要求常规文件、UID0、无 group/other 写位；父目录只接纳 root/Android system 的可信 UID/GID 和无应用/other 写入的权限组合（`/data` 常由system持有，不能错误要求所有祖先UID0）。发现 su、Root 开关或可执行文件本身都不是管理器身份；缺失/链接/不可核验时返回 unsupported/unknown，不自动搜索 PATH、自定义路径或另一通道。

| 管理器 / 固定源码 | 唯一首批候选与参数 | 精确版本样本 / 推导 | 模块只读布局 / 未证范围 |
|---|---|---|---|
| Magisk `af8c2566b07cfa0c2d4b145b88316a07f7d2bb6b` | 候选 `/system/bin/magisk`，仅在该固定位置实际是通过 NOFOLLOW 的常规文件时使用；不遍历 PATH、`/sbin` 或猜测 `/data/adb/magisk/magisk`。探测只允许 `-c/-v/-V`，不调用 `--daemon/--list/--path` 或模块命令 | 固定[构建配置][m-props]的 code `31000`；[build.py][m-build]默认 name 是短8位 `af8c2566`，可被自定义配置覆盖，因此 code31000 不代表所有31.x。`-c` 的 release 输出限定 `af8c2566:MAGISK:R (31000)`；[daemon 固定实现][m-daemon]配合[CLI][m-cli]给出 `-v` 精确 `af8c2566:MAGISK:R`、`-V` 精确 `31000`（均接受 0 或 1 个终止 LF/CRLF，多余尾随空白或额外行拒绝，不 trim 正文）。三项不一致或本机自定义/debug构建均 unsupported | [consts][m-defs]的 `/data/adb/modules`；`module.prop`及 disable/remove/update 标记只取 metadata。不能调用[模块启动收集流程][m-module]，其中会删除标记、执行卸载等；未证明任意安装位置、隐藏模式或所有31.x兼容 |
| KernelSU `6d31a61370838380177dc5c46fb838f6ba7d639a` | 唯一 `/data/adb/ksud`、参数 `-V`；[defs][k-defs]另定义 `/data/adb/ksu/bin/ksud` 为 daemon link，不因该链接可见而跟随。以 `ksud` 真实 argv0 调用，不伪装 su/resetprop、不调用 debug 或 module 子命令 | [build.rs][k-build]：30000 + `rev-list --count`。固定提交祖先数2702，故 code32702；`git describe --tags --always`=`v3.3.1-22-g6d31a613`；[UAPI][k-uapi]固定6。限定 clap版本输出 `ksud 3.3.1-22-g6d31a613 (uapi: 6)`，不接受所有3.3.x/Next/其他fork | [defs][k-defs]的 `/data/adb/modules`、`module.prop`、disable/update/remove；不读 modules_update、metamodule 或 profile，不切 mount namespace。内核profile/SELinux导致不可见须拒绝，不修改profile或补权限 |
| APatch `e8e4651c16fb5075882a25813c8732285fdb3505` | 唯一 `/data/adb/apd`、参数 `-V`；不用 `/data/adb/ap/bin/su` 识别 apd；[CLI][a-cli]的版本退出在 superkey/模块分发前，**不传 superkey** | [version.properties][a-epoch] epoch10200 + [build.rs][a-build]的固定提交祖先数1082 = **11282**。`git describe`返回标签11282，`11282^{commit}`精确等于本固定提交。限定 clap输出 `apd 11282`，不接受其它数字或分支自述 | [defs][a-defs]的 `/data/adb/modules`、`module.prop`、disable/update/remove；不读 modules_update/metamodule，不调用[内置 module list][a-module]。该版本名不证明手机所装KernelPatch组合、SELinux或 namespace 已验收 |

只有单一管理器匹配固定路径、版本指纹与已核布局，才进入 DSHA 自己的受限 reader。多个不同管理器探测成功为 `MANAGER_AMBIGUOUS`，三者共用 modules 根不能证明归属，不任选第一个。没有匹配、未知版本、发布标签/输出不符、路径为链接或探测失败均不能当“零模块”。

版本输出是协议/布局的有限匹配证据，**不是二进制真实性证明，也不替代 UID0 与本机 Root 授权**。本稿不声称通过版本字符串完成来源认证；恶意已获 Root 的代码和同 UID 代码边界仍见[安全模型](../../security-model.md)。

## 2. 元数据与版本证据

2026-11-14 只读获取公开 Git 元数据，未 checkout/构建/执行上游源。固定提交的结果为：

```text
KernelSU commit = 6d31a61370838380177dc5c46fb838f6ba7d639a
rev-list --count = 2702
describe --tags --always = v3.3.1-22-g6d31a613
VERSION_CODE = 30000 + 2702 = 32702
FULL_VERSION = 3.3.1-22-g6d31a613 (uapi: 6)
APatch commit = e8e4651c16fb5075882a25813c8732285fdb3505
rev-list --count = 1082
describe --tags --always = 11282
rev-parse 11282^{commit} = e8e4651c16fb5075882a25813c8732285fdb3505
VERSION_CODE = 10200 + 1082 = 11282
```

这些是本次固定源码图与默认配方的事实，不是手机输出。字符串解析须限定 ASCII/长度、零退出码及完整输出，截断/额外行/控制字符/多个身份均拒绝；不能因为 `-V` exit0 就接受未知管理器。自定义版本配置、浅克隆生成0.0.0、未知tag/fork都保持 unsupported，不补猜版本。

## 3. S4 拟实现的受管只读边界

- **窄入口**：原生页用户显式刷新、当前页面 owner 与新鲜授权快照；仅本机 Root helper。请求只含已知枚举/操作，不收 command/path/argv/ZIP。UI、助手或三种 Root 档位不能借此提交安装/更新/启停/删除等写操作。
- **固定探测**：版本探测本身单命令输出≤4KiB、单次有界；仅上表参数，不自动启动 daemon、不执行 module list/debug。候选必须从 root 逐级 fd 锚定；不得“先stat路径、后按可替换路径执行”。若 Java ProcessBuilder 无法保持已核执行对象，使用同 APK 的固定专用 probe 子入口：在该 UID0 子入口内核验固定 candidate，`Os.execv(/proc/self/fd/<fd>, argv)` 由内核在 CLOEXEC 前打开原 fd 对象；不开放任意类名、路径或参数。helper安装身份/UID0/输出/退出分别核验，超时与无法确认关闭不得冒称未发送。
- **专用 reader**：从 root fd逐级 O_PATH/NOFOLLOW，最终目录/模块子目录/`module.prop`/标记分别核 fstat/lstat；不递归、不跟链接、不读脚本、SQL、root配置或模块webroot。只读取 `/data/adb/modules`；借鉴[现有fd锚定实现](../../../app/src/main/java/com/deepseekharness/app/backup/AndroidBackupFileSystem.java)的技术形状但不修改/依赖备份写域。
- **流式枚举**：最多256模块；迭代器逐项读取目录，不先无界 `File.list()` 聚合再检查数量。每份 `module.prop`≤16KiB、总metadata≤256KiB、单字段≤256码点；字段只含 id/name/version/versionCode 与已核 disable/remove标记/mtime。只把常规文件作为 prop，拒绝 hardlink、FIFO、设备文件、symlink和换链；标记只读存在性/类型，不写入或删除。
- **结果**：完整枚举前后目录/文件身份一致才可显示 `complete=true`，完整且零项才是空清单。中途换链/读失败/超预算为 `LIST_PARTIAL` 或明确拒绝，正文带 `complete=false`；UNKNOWN 不自动重放，不转 Shizuku/ADB，不把权限拒绝折成不存在。上游 KSU/APatch list 实现会跟随路径且可能把 read_dir 失败当空向量，不能复用它们承担这些保证。
- **时限与撤销**：版本探测与清单阶段整体预算10秒、并发1；su管理器授权启动等待另计，不能把原65秒结果收集上限当全请求时限。撤销/离开页面先围栏，迟到结果不更新新页面；只结束自己持有的 helper/child，不按进程名/端口或裸PID清理。
- **隐私**：模块清单只在原生页显示，不新增助手桥端点，不进公开诊断/取证；公开回执只有类别、状态、complete与有限计数，不包含模块名、版本、脚本、用户路径或管理器密钥。

## 4. Lead 已审定条件（2026-11-14）

main `177cb2f8` 已按表批准有限支持与固定 FD probe，条件如下：

1. KernelSU 只限固定 `6d31a613…` / code32702 / `3.3.1-22-g6d31a613` / UAPI6 与 `/data/adb/ksud -V`；不跟 daemon link。APatch 只限固定 `e8e4651c…` / tag11282 / `/data/adb/apd -V`，不传 superkey，不用su识别。
2. Magisk 只限 `/system/bin/magisk`、`-c/-v/-V` 和精确 `af8c2566:MAGISK:R (31000)`；daemon版本/版本码指纹不闭合或自定义构建一律unsupported；不搜索其它路径。
3. helper 以 O_PATH/NOFOLLOW 打开并核 fstat 的 dev+inode+mode+uid，父目录逐级 fd 锚定；执行固定 `/proc/self/fd/<fd>` 对象，不得先stat再按可替换路径exec。参数只能由枚举内部生成，不开放任意类名/路径/argv，不新增泛用exec、权限或Root写边界。
4. 安装身份、UID0、输出≤4KiB与退出分别核验；超时/无法确认关闭保持UNKNOWN，不能冒称NOT_SENT。三者只读 `/data/adb/modules` 与prop/标记metadata，禁止module list/debug/namespace切换与任何写操作；partial/unknown不折空、不重放、不换通道。
5. 本表作为docs提交；**S4源码必须等S1独立审查APPROVE后再开工**，S2/S3继续按批准窗口。实施时对表重新核一次上游事实，任何不一致返回unsupported；不以审表代替真机或二进制认证。

本稿不证明真机可用、管理器已授权或任何手机输出。无管理器安装/授权、设备命令或截图证据；合并与最终descriptor/runtimeId仍由main处理。

[m-props]: https://raw.githubusercontent.com/topjohnwu/Magisk/af8c2566b07cfa0c2d4b145b88316a07f7d2bb6b/app/gradle.properties
[m-build]: https://raw.githubusercontent.com/topjohnwu/Magisk/af8c2566b07cfa0c2d4b145b88316a07f7d2bb6b/build.py
[m-defs]: https://raw.githubusercontent.com/topjohnwu/Magisk/af8c2566b07cfa0c2d4b145b88316a07f7d2bb6b/native/src/include/consts.rs
[m-cli]: https://raw.githubusercontent.com/topjohnwu/Magisk/af8c2566b07cfa0c2d4b145b88316a07f7d2bb6b/native/src/core/magisk.rs
[m-daemon]: https://raw.githubusercontent.com/topjohnwu/Magisk/af8c2566b07cfa0c2d4b145b88316a07f7d2bb6b/native/src/core/daemon.rs
[m-module]: https://raw.githubusercontent.com/topjohnwu/Magisk/af8c2566b07cfa0c2d4b145b88316a07f7d2bb6b/native/src/core/module.rs
[k-defs]: https://raw.githubusercontent.com/tiann/KernelSU/6d31a61370838380177dc5c46fb838f6ba7d639a/userspace/ksud/src/defs.rs
[k-build]: https://raw.githubusercontent.com/tiann/KernelSU/6d31a61370838380177dc5c46fb838f6ba7d639a/userspace/ksud/build.rs
[k-cli]: https://raw.githubusercontent.com/tiann/KernelSU/6d31a61370838380177dc5c46fb838f6ba7d639a/userspace/ksud/src/cli.rs
[k-uapi]: https://raw.githubusercontent.com/tiann/KernelSU/6d31a61370838380177dc5c46fb838f6ba7d639a/uapi/supercall.h
[a-defs]: https://raw.githubusercontent.com/bmax121/APatch/e8e4651c16fb5075882a25813c8732285fdb3505/apd/src/defs.rs
[a-build]: https://raw.githubusercontent.com/bmax121/APatch/e8e4651c16fb5075882a25813c8732285fdb3505/apd/build.rs
[a-epoch]: https://raw.githubusercontent.com/bmax121/APatch/e8e4651c16fb5075882a25813c8732285fdb3505/version.properties
[a-cli]: https://raw.githubusercontent.com/bmax121/APatch/e8e4651c16fb5075882a25813c8732285fdb3505/apd/src/cli.rs
[a-module]: https://raw.githubusercontent.com/bmax121/APatch/e8e4651c16fb5075882a25813c8732285fdb3505/apd/src/module.rs
