# 容器 SSH（可选调试入口）

> 作用：让外部终端（电脑上的 `ssh`、VS Code Remote、`scp`）直接进到 App 的 Ubuntu 容器里，
> 不用在手机上点来点去。**默认关闭**，且默认只监听 `127.0.0.1`。

## 一、威胁模型（先看这段再决定要不要开）

| 问题 | 事实 |
|---|---|
| 谁能连 | 默认只有设备本机（`127.0.0.1`）。打开「局域网访问」后，同一 WiFi 下任何人可达 `设备IP:22222` |
| 怎么认证 | 单一账号 `dsha` + 密码；`PasswordAuthentication yes`，公钥认证**关闭** |
| 密码存哪 | 设备 Keystore（专用 alias `dsha_container_ssh`）AES/GCM 加密后才进 SharedPreferences，偏好里只有密文；备份/导出里同样只有密文，换设备无法解密。已 root 且已解锁的宿主进程仍能在本机解出明文 —— 这是应用沙箱边界，不是内核隔离 |
| 能做什么 | 得到容器内的完整 shell（容器本身是 `danger-full-access`），与 App 自己的终端等价；`internal-sftp` 同时可用，能读写容器文件 |
| 失败限速 | 单连接 `MaxAuthTries 3`；并发未认证连接 `MaxStartups 3:50:10`；`MaxSessions 2`；`LoginGraceTime 20` |
| 明确禁止 | `PermitRootLogin no`、`AllowTcpForwarding no`、`PermitTunnel no`、`GatewayPorts no`、`X11Forwarding no` |
| 关了会怎样 | 停止服务、proot 进程被强杀；租约由退出监听持有到 guest 确认退出、且脚本记录的 sshd.pid 消失后才释放维护屏障；密码保留，下次开启继续用（可手动清除） |
| 清除密码 ≠ 锁账号 | 手动清除只删本机密文；容器 `/etc/shadow` 里 dsha 的旧密码哈希仍在（下次设新密码会覆盖）。要彻底失效需重建环境或在容器内 `passwd -l dsha` |
| 局域网授权的适用范围 | `LocalNetworkAccess` 的系统授权只存在于 Android 17+（API 37+）；更低版本 `granted()` 恒为 true，「没授权直接拒绝启动」只在 37+ 成立，低版本靠用户不开 LAN 开关 |

**不要在公共 WiFi 下打开局域网监听。** 密码一旦泄露，等同于把容器 shell 交给对方，
而容器里跑着 DSH 的凭据与用户数据。

## 二、怎么开

1. **设置 → 设备能力授权** → 设备通道卡末行的「容器 SSH（实验性）」：
   点「设密码」输入 **≥12 位**密码（`ContainerSshPolicy.validPassword` 拒绝过短或含控制字符的；审查 P2-1：LAN 暴露下密码是唯一边界，故从 8 位提到 12 位），
   再打开开关；开关联动前台监督服务（通知含「停止」动作）；
2. 需要从别的设备连时再打开「允许局域网访问」；这会要求系统「局域网访问」授权
   （`LocalNetworkAccess.granted`），没授权启动直接拒绝；改动在重启 SSH 后生效；
3. 连接：`ssh -p 22222 dsha@127.0.0.1`（局域网模式换成设备 IP）。

状态文案口径（UI 基准线 ⑩/⑬）：首屏状态行只给 ≤5 字结论（运行中 / 启动中 / 停止中 / 已停止 /
未设置密码 / 监听未确认 / 启动失败），端口、可达范围、失败原因与下一步都在状态行旁的 InfoHint 里；
`SSH_*` 诊断码不出现在用户可见文本中（映射集中在 `util/ContainerSshStatus`）。
失败与「监听未确认」会保留一条可读的通知（含原因与下一步、不带「停止」动作），
不会再出现「详见通知」但通知已被撤掉的情况。

## 三、实现边界（别把它当安全隔离）

- 启动脚本在 `util/ContainerSshPolicy`（已纳入运行时描述符 `launcherSources`，改动会变更 `runtimeId`）；
  密码**只从 stdin 读一行**，宿主侧用 `char[]` 直接编码后清零，不进 argv、不进日志、不进脚本文件；
- sshd / ssh-keygen / libwrap 由 `runtime/ContainerSshAssets.bind` 在启动前核验并绑定进容器：
  清单逐字节 SHA-256、ELF 头（class/字节序/`e_machine`/`e_type`）、目标架构三重校验，任一不符整体拒绝；
- 监听成功的判定读 child 的 stdout，看到预期地址才算起来，否则视为失败并清理；
- 服务是前台服务（`specialUse`），`stopWithTask=false`：划掉 App 任务不中断 SSH，直到用户显式停止。

## 四、验证状态（如实记录）

- **单测**：脚本约束（`ContainerSshPolicyTest`）、偏好永不落明文（`ContainerSshSettingsTest`）、
  绑定契约与损坏拒绝（`ContainerSshAssetsTest`）、Manifest 注册与启动门控（`ContainerSshWiringTest`）；
- **未做真机/容器实测**：连接、密码错误计数、并发丢弃、LAN 权限分支需要一台能跑容器的 arm64 设备
  复验（`ssh -p 22222 dsha@127.0.0.1`，错 3 次密码、开关 LAN、停止后核对进程与租约释放）；
- 三个 payload 的来源登记在 [references-and-provenance.md](references-and-provenance.md) 第 4 节：
  Ubuntu 24.04 的 OpenSSH 1:9.6p1 与 libwrap0 二进制，精确修订号与可复现构建配方仍未闭合。

## 五、为什么单独一个 PR

它把容器命令行暴露给外部工具，与「模块平台」不是一回事：风险面、验收方式（要真机连一次）、
后续维护者都不同。按审查意见独立提交，方便单独回滚。
