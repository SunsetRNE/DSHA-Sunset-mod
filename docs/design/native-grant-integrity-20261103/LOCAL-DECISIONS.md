# T44 原生授权决策 · build308

本地审查基线为 `92618f09947081ea9be991b595f75756ceca29f5`。上游 T44 是基于 build304 写成的设计稿。本地策略按下表执行。build308 的改动范围和未来需要实施的授权机制分别说明。[上游 T44 固定原文](https://github.com/SunsetRNE/DSHA-Sunset-mod/blob/deab1a0338ce33dd107e9f84854be3a5985c6e6b/docs/design/native-grant-integrity-20261103/README.md)

| 上游问题 | 本地决定 | 对用户和实现的具体影响 |
|---|---|---|
| Q1 密钥的保护边界 | 接受 Keystore MAC 作为持久记录的完整性检查。E5 单独验证调用者实际能做什么。 | 校验覆盖能力、类型、值、revision、安装代次、签名身份和格式版本。状态与 MAC 应放进同一份可原子替换的记录。读失败不创建替代密钥。没有可信单调计数器时，进程重启后的合法旧快照仍可能通过校验。 |
| Q2 高权限执行 | ADB、Root、Shizuku 和外接目标的高风险动作，每次派发消费一张短时、进程内、一次性的票据。 | 在当前会话中，用户预先同意的具体范围可以减少重复弹窗。票据绑定任务、能力、通道、目标及其代次、实际参数摘要和单调时钟截止时间。新票据的设计截止时间为原生确认或当前会话许可作出后 30 秒，使用单调时钟，覆盖首次派发。已经派发的操作按执行器回执区分成功、失败和结果未知。执行前重新采样系统授权和当前目标。换目标、改变参数、撤销、超时或重启均作废。派发后结果未知不自动重试。设备管理器另有用户授权时，原生票据只控制 DSHA 自己的入口。 |
| Q3 无法证明状态时 | 立即撤销该能力已有的内存授权，拒绝新的派发。只能在原生设置页重新建立有效同意。 | 未配置、明确关闭、暂时无法读取、需要重新确认分别显示。用户随时能停止当前能力。旧 ON、损坏记录、来自备份的值不会静默转换成新 ON。诊断仅保留原因码和代次。 |
| Q4 同 UID 风险 | 将“受管入口完整性”和“运行任意同 UID 代码”分开评估。 | Root 管理器、Shizuku 或系统授予该 UID 的能力仍按各自的授权规则执行。若以后要求安全抵抗任意 guest 代码，需另立能隔离身份的架构任务，并限定跨边界接口和执行器。仅换到同包另一进程不会产生不同 UID。 |
| Q5 第一批 | 先收敛已确认的非识屏高风险入口，优先外接目标与命令绑定，再接 L3 和非识屏 L2，例如短信。L0 界面偏好继续按现有默认值处理。 | 本地 `effectiveScreenPolicy()` 使用全局同意。首批不改这个共享读写路径，也不引入上游 `ScreenMode`。否则即使没改识屏工具，也会改变已有屏幕确认行为。 |
| Q6 LAN | 单独按 L1 迁移。区分对外监听意图、口令验证器、token 和运行中的 Web cookie。 | 异常时停止本机持有的对外监听，失效已有会话，不影响本机回环工作台。恢复入口认证后才重新监听。LAN 不与 Root、ADB 共用授权票据。 |
| Q7 真机取证 | 权限实验限定在单独包名、单独 UID、单独签名的测试包内，自建一次性密钥和样本。 | 第一项先比较同 UID 的两个 Android 进程。只报告身份、是否成功和错误类别。guest `app_process`、TLS、Root 管理器等路径需要各自独立的证据，不能由兄弟进程的结果代替。正式 DSHA 的授权值、配对材料和历史数据不作为试写对象。 |

## 为什么合法回滚需要单独处理

在宿主上构造过一个最小反例。修改已签记录的布尔值而沿用其原 MAC，校验失败。把同一安装里以前合法签过的整份 ON 记录连同 MAC 一起放回，校验成功。只有记得更高 revision 的验证者才能据此拒绝旧记录。冷启动后，对 L3 的正向授权必须有本次进程中的有效依据。这个实验使用临时内存密钥和合成记录，验证的是设计性质。

## build307 需要区别处理的事实

- 本地 `DeviceGrants` 有严格的全局记录解析、进程内已见 revision 和失败后的恢复流程，短信预授权仍是独立的布尔值。读取一个合法编码并不能证明写入来自用户。`effectiveScreenPolicy()` 将全局同意连接到屏幕确认逻辑，首批改动必须避开这条路径。[DeviceGrants.java](../../../app/src/main/java/com/deepseekharness/app/core/DeviceGrants.java)
- `CapabilityBroker` 已经有单次派发、撤销、超时、运行实例和授权代次校验，`BridgeConfirmations` 已有独立确认身份。实施时应补齐具体调用者的目标和动作绑定，在真正的副作用处分配和消费票据。存在这些类本身不能证明所有入口都接入了它们。[CapabilityBroker.java](../../../app/src/main/java/com/deepseekharness/app/capabilities/CapabilityBroker.java) · [BridgeConfirmations.java](../../../app/src/main/java/com/deepseekharness/app/util/BridgeConfirmations.java)
- 备份按实际导出白名单审查。`confirm_shell` 是可迁移的工作台设置，不能当作恢复后颁发原生 Root、ADB、短信等授权的证据。读取状态异常时，也不能把“缺少字段”解释为用户主动授权。[PortablePreferenceProjection.java](../../../app/src/main/java/com/deepseekharness/app/backup/PortablePreferenceProjection.java) · [ConfigStore.java](../../../app/src/main/java/com/deepseekharness/app/core/ConfigStore.java)
- T42 记载的 E1–E4 是上游设备的只读观察。打开目录成功和目录可写性检查，不等于已经改写本地平板上的某个授权键。Keystore 的不可导出属性保护密钥材料，具备相应权限的代码仍可能调用该密钥。设备是否支持 StrongBox、实验实际使用的安全级别、同 UID 调用成功与否必须分别记录。[Android Keystore](https://developer.android.com/privacy-and-security/keystore) · [Android 应用沙箱](https://source.android.com/docs/security/app-sandbox)

## 接入和迁移

新安装的缺省状态关闭。升级遇到没有 MAC 的旧 ON，可以展示上次选择，重新启用须由原生页确认。安装代次应跨正常覆盖升级保持稳定。Web 会话用于失效控制的身份含升级时间，不能直接拿来充当这个代次。Keystore 暂不可用、记录损坏、系统权限被撤销须给出不同的原因。撤销先使受影响的当前内存许可失效，再处理持久记录和本应用持有的连接。备份或恢复不会签发新许可。

Q2–Q6 是后续生产接入的约束。build308 先处理外接调试的两个已发现入口问题。一次涉及原生确认显示的目标与后续派发目标之间的竞态，一次涉及只读诊断命令的参数校验。高风险授权的全套 MAC 存储、Root/Shizuku/ADB 统一票据、短信与 LAN 的迁移仍是独立工作。普通文件工具沿用现有工作区权限，不引入设备授权弹窗。

外接调试原有命令风险编号与本设计的 L0–L3 能力分类用途不同。具体命令必须先通过执行器对参数的正向校验。已经获准读取日志、显示尺寸或密度，不会因此获准清日志、修改日志缓冲区或改变显示设置。

## 本机 E5 已验证的范围

2026-11-04 在获准的 M367FC / Android 17 上安装独立的 `dsh.android.client.t44.test`，只在用户点击后生成测试包专用的临时 HMAC 密钥。测试包主进程，`getEncoded()` 返回 `null`。与主进程具有相同 UID 的普通服务，Binder 回执身份匹配。服务独立通过 Android Keystore 取到密钥句柄，对本轮随机挑战的 MAC 计算结果与主进程匹配。就这台设备和这类未要求逐次用户认证的密钥而言，同 UID 的不同普通 Android 进程可以使用密钥。

具有独立 UID 的隔离服务 在密钥查找阶段得到 `IllegalStateException`，未观察到 MAC；该异常不足以确定内部拒绝原因。实验结束确认自有密钥不存在，仅卸载本次新增的测试包。正式应用的包摘要、UID 与首次安装时间保持。本次仅覆盖普通 Android 服务进程和隔离服务对照，尚无 guest `app_process`、TLS 私钥、锁屏和 StrongBox 分项结果。

[build308 的实验、修复和验收报告](../../releases/v0.2.1-rc.5-build308-native-grants-20261104.md)
