# 容器 SSH（可选调试入口）

> 作用：让外部终端（电脑上的 `ssh`、VS Code Remote、`scp`）直接进到 App 的 Ubuntu 容器里，
> 不用在手机上点来点去。**默认关闭**，且默认只监听 `127.0.0.1`。

## 一、威胁模型（先看这段再决定要不要开）

| 问题 | 事实 |
|---|---|
| 谁能连 | 默认只有设备本机（`127.0.0.1`）。打开「局域网」后，同一 WiFi 下任何人可达 `设备IP:22222` |
| 怎么认证 | 单一账号 `dsha` + 密码；`PasswordAuthentication yes`，公钥认证**关闭** |
| 密码存哪 | `MODE_PRIVATE` 的 SharedPreferences。**未加密**，靠应用沙箱隔离；设备已 root 时可读 |
| 能做什么 | 得到容器内的完整 shell（容器本身是 `danger-full-access`），与 App 自己的终端等价 |
| 失败限速 | 单连接 `MaxAuthTries 3`；并发未认证连接 `MaxStartups 3:50:10`；`MaxSessions 2`；`LoginGraceTime 20` |
| 明确禁止 | `PermitRootLogin no`、`AllowTcpForwarding no`、`PermitTunnel no`、`GatewayPorts no`、`X11Forwarding no` |
| 关了会怎样 | 停止服务、进程被强杀；密码保留，下次开启继续用（可手动清除） |

**不要在公共 WiFi 下打开局域网监听。** 密码一旦泄露，等同于把容器 shell 交给对方，
而容器里跑着 DSH 的凭据与用户数据。

## 二、怎么开

1. 设置里打开「容器 SSH」，设一个 **≥8 位**的密码（`ContainerSshPolicy.validPassword` 会拒绝太短或含控制字符的）；
2. 需要从别的设备连时再打开「局域网」；这会要求「局域网访问」权限（`LocalNetworkAccess.granted`），没授权直接拒绝启动；
3. 连接：`ssh -p 22222 dsha@127.0.0.1`（局域网模式换成设备 IP）。

## 三、实现边界（别把它当安全隔离）

- 启动脚本在 `util/ContainerSshPolicy`，密码**只从 stdin 读一行**，不进 argv、不进日志、不进脚本文件；
- sshd 配置由脚本生成到容器的 `/run/dsha-container-ssh/sshd_config`，`sshd -t` 先自检再 `exec`；
- 监听成功的判定读 child 的 stdout，看到预期地址才算起来，否则视为失败并清理；
- 服务是前台服务 + `RuntimeTasks` 会话租约，宿主退出时一起收尾。

## 四、未验证（如实记录）

- **没有真机/容器实测**：本 PR 只到「编译 + 脚本约束单测」；连接、密码错误计数、并发丢弃行为需要一台能跑容器的设备复验；
- 容器内 `sshd` 由 `ContainerSshAssets` 从 `assets/container-ssh/payload-*.bin` 解开，解包与架构校验（ELF `e_machine`）有单测级校验，但真实解包未在本次验证。

## 五、为什么单独一个 PR

它把容器命令行暴露给外部工具，与「模块平台」不是一回事：风险面、验收方式（要真机连一次）、
后续维护者都不同。按审查意见独立提交，方便单独回滚。
