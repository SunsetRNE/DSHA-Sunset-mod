# build368 官方包覆盖升级验收回执（2026-11-16）

状态：**通过（第二台手机，官方包 366→368 覆盖升级）**。已完成一次运行环境维护页、回主界面 READY，并实际启动 DSH 到鉴权就绪。本回执只记录这一台设备与这一次覆盖升级，不扩写到其它 ROM、机型或 Android 版本。

## 对象与来源

- 被测包：GitHub Release `dsha-a-su-build368-20261116-052738` 的正式资产 `dsha-a-su-e74f9e78.apk`（317,707,282 B，SHA-256 `616c2c9bdb175aa1fe0b863ccb7295991f58e44f2913da3376bffbadbf35f5ea`；与 Release `SHA256SUMS`、GitHub asset digest、`build-info.json` 一致）。
- 设备：OnePlus **PJD110**，Android **16**（API 36），`arm64-v8a`，内核页 **4096 B**。
- 升级前（第二台手机既有官方包）：versionCode **366** / versionName `0.2.1-rc.5-20261114.1717-dsh0.2.2-rc.1`；`firstInstallTime=2026-10-30 22:16:48`；`dataDir=/data/user/0/io.github.sunsetrne.dsha`。
- 安装：`adb -s f55ffc45 install -r dsha-a-su-e74f9e78.apk` → `Success`；升级后 versionCode **368** / versionName `0.2.1-rc.5-20261116.0500-dsh0.2.2-rc.1`，`lastUpdateTime=2026-11-16 14:21:56`，`dataDir` 与 `firstInstallTime` 保持不变。**本机（DSH 宿主）未安装官方包。**

## 过程与结果

1. 启动 `MainActivity` 进入**运行环境维护**页（「更新运行环境 / 只更新 dsh 与内置插件，个人数据保留」）：观察到第 3/5 步「准备内置插件与界面适配」（14:22:28）与第 4/5 步「保留并切换受管文件」（14:23:14）。
2. 维护完成回到主界面：运行环境 **READY**、顶部「**运行时已更新**」徽标，工作台「未运行」；**未出现引导页**，说明既有配置/数据被保留（14:24:14）。
3. 点「启动 DSHA」后：**DSH 已就绪，可进入**；运行环境 READY、工作台「可进入」、实际 Web 端口 **42117**；启动日志 `BACKEND_AUTH_READY 10.2s`、`STARTUP_AUTH_TIMING nodeToAuthMs=4489`、完成「加载 DSH 和已启用插件」4.3s（14:26:18）。
4. 稳定性：`logcat -b crash` 为空；升级后进程存活（pid 18176），前台为 `MainActivity`。
5. 结论：366→368 覆盖升级成功；一次整屏维护页符合预期；个人数据保留（`dataDir`/`firstInstallTime` 不变、无引导）；升级后 DSH Web 可正常启动到鉴权就绪。

## 证据

仓库外目录 `/tmp/build368-release/official-acceptance/`：`baseline.txt`、`install.txt`、`s1.png`（维护 3/5）、`s2.png`（维护 4/5）、`s3.png`（READY + 运行时已更新）、`s4.png`、`s5.png`（DSH 就绪/端口 42117）、`s6.png`、`logcat.txt`、`logcat2.txt`。不提交截图与原始日志。

## 边界与未验

- 仅本台 OnePlus PJD110 / Android 16 / 4096 B 页；**16 KB 页内核未验**。
- 未做端到端备份/恢复、SAF 传输、双屏敏感页与云端会话验收；本回执只覆盖覆盖升级、维护页、数据保留与 DSH 启动到鉴权就绪。
- Rust 运行时后端的 instrumentation 证据来自**同 commit** 的 `.debug` 测试包（见 [build368 设备验收回执](build368-acceptance-20261116.md)）；本回执只确认官方包能升级启动，不重复断言官方包内的 Rust 探针。
- 本机（DSH 宿主）不装官方包；本回执的安装只发生在第二台手机。
