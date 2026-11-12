# A7 设备能力设计核查：Root 权限映射与内置 ADB 客户端

状态：**核查稿**（只读源码与文档；未改产品代码，未执行设备命令，未操作手机）。
基线：`faab36c5`（main，build361 / `0.2.1-rc.5-20261113.0000-dsh0.2.2-rc.1`）。
日期：2026-11-13。
范围：A7「设备能力与识屏」中的 Root 权限映射与内置 ADB 客户端（T42），以及它们与 A1 契约、T40、覆盖更新和备份的交叉点。
依据：[权限架构](../device-permissions-architecture.md)、[设备通道](../device-channels.md)、[安全模型](../security-model.md)、[T42 设计稿](./adb-native-20261103/README.md)、[T40 设计稿](./device-exec-confirm-20261103/README.md)、[迭代路径 A7](./kotlin-rust-rearchitecture-20261112/iteration-roadmap-20261112.md)、[ADR-0004](../adr/0004-kotlin-rust-direction.md)、[安全评审 E](./kotlin-rust-rearchitecture-20261112/drafts/05-safety-permissions.md)。

---

## 0. 结论

1. **Root 权限映射的“运行时链路”成立**：用户开关 / 档位 → `DeviceShellPolicy` 判定 → 通道只选一次 → 本机 su（`uid=0`）、Shizuku 或 ADB（shell `uid=2000`，`su=true` 时 `uid=0`）→ 执行侧复核。三档语义与文档一致：`strict` 默认白名单；`relaxed` / `full` 只对 root 身份生效；Shizuku 与普通 ADB 始终 `strict`；短信闸门三档都保留。
2. **但没有 A7 可执行设计**：Root 权限映射目前只散落在权限架构文档、路线图一句话和代码里；没有能力枚举、授权 state owner、失败码、凭据类型与验收清单。F07「Root 模块管理（只读先行）」只有一句范围（清单 + 管理器握手/能力探测），**没有设计稿、没有代码、没有失败域与写入口关闭的验收项**。
3. **授权状态没有单一 owner**：Root 开关与档位在 `ConfigStore`/SharedPreferences，ADB 开关在 `DeviceBridgeService` 的 `adb_enabled`，短信与屏幕在 `DeviceGrants`。这与评审 E 的「M05 唯一写入点」目标不一致；A7 若不先定归属，所谓“映射”只是把现有分散事实画成流程图，不能作为模块化契约。
4. **`--su` 的 root 身份没有被证明**：本机 `su` 路径在 `RootShellMain` 里检查 `myUid()==0`；ADB `su=true` 路径没有在发送前/回执里核验实际 UID，只把 `su -c` 的输出当结果。这会把“请求了 root”与“实际是 root”混在一起，T42 原生化时应补成显式核验或明确失败码。
5. **同 UID 绕过仍是已坐实的边界，且没有 owner**：T42 的 E2 已证明容器裸系统调用可打开宿主 `shared_prefs` / `no_backup` 且 `access(W_OK)` 为可写；E3 只证明 `su` 文件存在（未执行）；E4 证明 `app_process` 可从容器启动。R2（直接 su）与 R5（改原生偏好）会让“关闭 Root/ADB 开关”“档位”只约束随包入口。A7 文档与验收不能写成隔离；需要单独立项或明确接受。
6. **T42 的目标仍成立，但设计稿已与 build361 漂移 4 处**：① 设计稿基线是 build301，S1–S11 引用的行号基本全部过期；② `BRIDGE_PROTOCOL` 已是 3，设计稿“递增到 3”的前提不成立；③ `§7a` 的备份 / 导出排除 ADB 私钥已由 `1bb1c164` 落地（旧备份恢复不落地），设计稿仍写成“现状 / 是否先修见 Q9”；④ `AGENTS.md` 已改成“AdbBridge 当前经 guest Python 执行，原生化见 T42”，设计稿 S4 的“AGENTS 写法与代码不符”不再成立。实施前必须先更新设计稿状态与回执，否则会按旧前提实现。
7. **T42 的关键未决项没有减少**：E5–E8 未做（`app_process` 调 keystore2、系统「已配对设备」、平台 TLS 用 Keystore RSA 私钥、`exportKeyingMaterial`），K1 可行性与 SPAKE2 实现选型仍未定；`AdbChannelOwner` 的单连接 / 串行 / 总时限 / epoch 关闭没有数值与状态机；Q5（`WRITE_SECURE_SETTINGS` 改为可选、默认不授权）当前实现仍是配对/验证后自动 `pm grant`；迁移 M1–M3（旧密钥不导入、重新配对、系统撤销指引）没有代码；Q4 的降级错误码 `DEVICE_CHANNEL_FORCE_DEPRECATED` / `UNSUPPORTED` 在代码中不存在。
8. **T42 与 A7 是两条不同性质的线**：A7 是功能闭环（三通道可用性、类型化执行、识屏、F07 只读），T42 是授权边界改动（私钥、原生客户端、迁移、协议）。T42 的 Q8 已定“排在 T40 之后”，T40 仍未实现；**建议不要把 T42 原生客户端整包压进 A7 出口判据**，否则 A7 会被一个需要用户重新裁决、重新取证的任务卡死。本文在 §5 给出拆分建议。

---

## 1. 术语与核验口径

本文把用户说的两件事拆成可核对的对象：

| 说法 | 本文核验的内容 | 不包含 |
|---|---|---|
| Root 权限映射 | ① guest 的“容器 root”（proroot `-0`）与 Android 真实 UID 的边界；② 用户授权的 Root 能力 → 授权存储 → 通道选择 → 实际执行身份 → 命令档位 → 执行侧复核这条链；③ F07 只读 Root 模块管理的映射扩展 | 提权内核实现、任意 ROM / Root 管理器兼容矩阵、用户管理器设置 |
| 内置 ADB 客户端 | 当前随包 Python 客户端（assets → rootfs）的真实形态；T42 把它移到 App 原生侧的既定设计、已落地项、漂移项与未决项 | 外接设备 T3（`extdev/`，root 子进程 + 专用 HOME，独立链路） |

核验方法：在 build361 树上读文档与源码、逐条对照行号；不编译、不跑单测、不连设备。因此本文只能证明“文档与代码事实一致/不一致”，不能证明真机行为、协议握手或权限结果。凡涉及真机的结论都标为“未取证”。

---

## 2. Root 权限映射

### 2.1 已存在的设计文本

| 文档 | 已经定义的内容 | 状态 |
|---|---|---|
| [权限架构](../device-permissions-architecture.md) §1.1、§3、§6、§6.2 | 容器 root ≠ Android UID 0；su 发现 / 管理器授权 / UID 0 执行三步；三处守卫；短信独立闸门与递归 READ 缺口 | 维护文档，事实核对到 `140c7ba`，部分内容明确标“未修 / 未真机” |
| [设备通道](../device-channels.md) | 三通道用户准备、确认成功与失败语义；Root 三档；通道顺序；结果码 124/125/126 | 维护文档，与代码一致 |
| [安全模型](../security-model.md):49、:55、:57 | 三档与短信闸门；递归 READ 缺口；su 发现/授权/UID 0 不同事实 | 维护文档，含已知弱点 |
| [评审 E](./kotlin-rust-rearchitecture-20261112/drafts/05-safety-permissions.md) “三通道权限归属”表 | 必须留 Kotlin / 可下沉 Rust / 最终拒绝权；ADB 用户开关、配对、API37 门禁、epoch；argv 判定唯一 | 评审稿，非 A7 任务基线 |
| [迭代路径 A7](./kotlin-rust-rearchitecture-20261112/iteration-roadmap-20261112.md):242 | 三通道可用性、身份与类型化执行；统一能力枚举 + 失败码 + 通道声明；F07 只读先行 | 只有一段范围，无设计稿 / 任务基线 |
| [ADR-0004](../adr/0004-kotlin-rust-direction.md):89 | F07 只读先行；写入口等 Root 写入口封闭/降权后再开 | 决定记录，无验收 |
| [对 18 模块的回复](./kotlin-rust-rearchitecture-20261112/reply-to-18-modules-20261112.md):67 | 同意 F07 边界；先只读清单 + 管理器识别/能力握手 | 待双方追认的边界 |

**结论**：基础权限架构是完整且诚实的；缺的是 A7 自己的“可执行设计”——能力名、状态归属、凭据类型、失败码、F07 只读契约与验收。

### 2.2 现行实现链路（build361）

1. **用户授权**：Root 开关与档位在 [ConfigStore.java](../../app/src/main/java/com/deepseekharness/app/core/ConfigStore.java):281-299（`KEY_ALLOW_ROOT_SHELL`、`KEY_ROOT_POLICY_MODE`，非法值回落 `strict`）；ADB 开关在 [DeviceBridgeService.java](../../app/src/main/java/com/deepseekharness/app/DeviceBridgeService.java):112-115（`adb_enabled`）；短信 / 屏幕 / 剪贴板在 [DeviceGrants.java](../../app/src/main/java/com/deepseekharness/app/core/DeviceGrants.java)。**三处存储，无 M05 单一写入点。**
2. **su 发现**：[RootShellCandidates.java](../../app/src/main/java/com/deepseekharness/app/util/RootShellCandidates.java):8-19 固定九个路径；[RootShell.java](../../app/src/main/java/com/deepseekharness/app/RootShell.java):55-79 用 `Os.stat` 核对 root 属主、常规文件、组/其他不可写与可执行。发现不执行 su，也不识别管理器身份。
3. **档位判定**：[DeviceShellPolicy.java](../../app/src/main/java/com/deepseekharness/app/util/DeviceShellPolicy.java):22-46 定义 `STRICT/RELAXED/FULL`；:163-180 先跑严格判定，短信读取永远保留原生授权，`relaxed` 再过不可逆操作文本黑名单，`full` 不按内容拦截。三档都要求 root 通道与真实 su 身份（由调用方保证）。
4. **通道选择**：[DeviceChannelOrder.java](../../app/src/main/java/com/deepseekharness/app/util/DeviceChannelOrder.java):16-24 顺序 Root →（API37 且 ADB 已连接时 ADB 先于 Shizuku）→ Shizuku / ADB；:53-100 按 `NORMAL/SMS/SHELL` 类别过滤，先严格可用、再“已授权待就绪”，只选一次，失败不换通道；ADB 的 `su` 由 `Kind.SHELL || forceRoot` 决定。
5. **原生入口**：[HttpShellService.java](../../app/src/main/java/com/deepseekharness/app/HttpShellService.java):1083-1133 `deviceExecute`；:1192-1289 `devicePlan`。`devicePlan` 的档位规则是 `root || relaxedRoot ? mode : STRICT`，其中 `relaxedRoot` 要求 Root 开关开且 `RootShell.present()` 为真；`--su` 且 Root 开关关闭时直接 `DENY`；`SHELL` 计划带 `mode`、`rootOnly=true`、`command`；短信计划带 `capability=sms.read` 与 `authorization=remembered`。
6. **执行身份**：
   - Root：[RootShell.java](../../app/src/main/java/com/deepseekharness/app/RootShell.java):102-124 → `su -c` 启动 [RootShellMain.java](../../app/src/main/java/com/deepseekharness/app/RootShellMain.java):72（`myUid()!=0` 直接拒绝）、:78（stdin JSON 上限 64 KiB）、:93（原始命令上限 `MAX_SHELL_COMMAND`）、结果带 `DSHA_ROOT_RESULT=` 标记与看门狗。
   - Shizuku：[DeviceShellExecutor.java](../../app/src/main/java/com/deepseekharness/app/DeviceShellExecutor.java):25 始终用严格档；短信 [DeviceShellExecutor.java](../../app/src/main/java/com/deepseekharness/app/DeviceShellExecutor.java):28-30 拒绝；读路径 :33、:99-117 再核短信 provider 与 canonical 目标。
   - ADB：`deviceExecute` 返回 `state:"adb"` + 计划（:1114-1116），guest [adb-shell.py](../../app/src/main/assets/adb-shell.py) 连接本机 adbd；`su=true` 时在 guest 内拼 `su -c`（:328）；执行前由 [device-shell-policy.py](../../app/src/main/assets/device-shell-policy.py) 复核计划、文件路径、停止目标与短信。
7. **虚拟屏**：只接受 `uid=0` 或 `uid=2000`（[DeviceShellExecutor.java](../../app/src/main/java/com/deepseekharness/app/DeviceShellExecutor.java):83-86）；ADB 路径另有一次 `nativeTicket` + commit。

### 2.3 与设计一致、可保留的部分

- **“找到 su”“开关打开”“真实 uid 0”是三个事实**：文档与代码一致；RootShellMain 拒绝非 0 UID；不把 Shizuku 的 root/shell 混合身份写成 root。
- **档位只对 root 身份生效**：`SHELL` 计划只在 `mode != strict` 且 `su=true` 时由 guest 执行（[device-shell-policy.py](../../app/src/main/assets/device-shell-policy.py):359-368）；Shizuku 的通用执行器不识别 `SHELL`。
- **短信闸门三档都在**：`inspect` 先返回 `SENSITIVE_READ`，放宽不会把短信掺进脚本（[DeviceShellPolicy.java](../../app/src/main/java/com/deepseekharness/app/util/DeviceShellPolicy.java):166-174）；guest 也拒绝混入短信面。
- **只选一次通道、UNKNOWN 不重放**：`deviceExecute` 的 ADB 计划只在连接前返回；连接后异常是 `EXECUTION_UNKNOWN`/125，不换地址（[adb-shell.py](../../app/src/main/assets/adb-shell.py):298-330、:536-553）。
- **容器 root 不是 Android root**：proroot `-0` 只是 guest 视图；文档 §1.1 明确不因此获得 UID 0。本次核查未发现代码把它写成真实 root。

### 2.4 缺口与风险

| # | 级别 | 事实 | 影响 / 建议 |
|---|---|---|---|
| R-1 | **P0（A7 开工前）** | F07「Root 模块管理」只有一句话范围（A7 交付物、ADR 决定与回复）。无设计稿、无代码、无失败码、无“写入口仍关闭”的验收项。 | A7 的出口判据包含“Root 清单”，但当前无法评审。需先写 `f07` 只读设计：管理器识别命令、模块清单来源、身份要求、只读边界、失败映射、默认关闭、诊断脱敏。 |
| R-2 | **P0（契约）** | 授权状态分散在 `ConfigStore`（root 开关/档位）、`DeviceBridgeService`（ADB 开关）、`DeviceGrants`（短信/屏幕）。评审 E 要求 M05 是唯一写入点，A1 只落了契约类，授权状态机未迁移。 | A7 若按“M05 授权 / M06 通道”拆，必须先定谁持有 root 档位与 ADB 开关；否则通道层会继续直接读 prefs，模块边界形同虚设。 |
| R-3 | P1 | 档位判定在两个入口重复：`RootShell.exec` 重新读 `ConfigStore` 判档，`devicePlan` 又算一次 `relaxedRoot`。 | 迁移到 M06/单一判定点时必须保持语义；建议加差分单测（同一命令、同一开关组合，两条路径结论相同），避免“原生判 strict、guest 判 relaxed”这类分叉。 |
| R-4 | **P1（安全）** | ADB `su=true` 路径没有在发送前或回执中核验实际 UID；只把 `su -c` 的输出与退出码当结果。RootShell 路径有 `myUid()==0`，两者不对称。 | 会混淆“已请求 root”和“实际是 root”。T42 原生客户端应定义：发送前是否核验（如 `id -u`）、失败码（`ROOT_NOT_GRANTED` / `ROOT_IDENTITY_MISMATCH`），以及是否允许在 `UNKNOWN` 时重试（不得）。 |
| R-5 | **P0（对外表述）** | 同 UID 绕过没有 owner：E2 已坐实宿主 prefs 可写；E3 只证 `su` 存在（未执行，未证明 uid0）；E4 证明 `app_process` 可从容器启动。T42 §2.2 R2/R5 明确超出 T42。 | 关闭 Root/ADB 开关、改档位，只约束随包入口。A7 文档、提示词、设置页文案不能写成“关闭即失效/隔离”。需要用户决定：单独立项修 R5，还是明确接受并写进安全模型。 |
| R-6 | P1 | 递归 READ 的子链接目标未核验（[security-model](../security-model.md):55、[权限架构](../device-permissions-architecture.md) §6.2）。F07 只读模块清单若复用通用 `READ`（`ls/cat/grep -R`），会继承该缺口；ADR 又禁止 F07 写入口。 | F07 应使用**专用只读计划**（固定命令、固定路径、不跟随链接、有界输出），不复用任意 READ；并补“跟随链接的递归 READ”反例。 |
| R-7 | P1 | `RootShell.present()` 只回答“已知路径上有可执行文件”，不是管理器身份；F07 的“管理器握手/能力探测”不能拿它当识别。 | F07 设计需分别定义：发现（present）、身份（`ksud -V` / `apd -V` / `magisk -V` 与授权后的 `id -u`）、能力（哪些模块目录可读）；三者不合并。 |
| R-8 | P2 | `--su` 只检查 Root 开关，不检查 su 是否可用；若用户开了开关但本机没有可用 su，ADB+su 会在远端失败，回执可能是普通退出码而不是“未发送/无 root”。 | 至少要在文案与提示里保留“开=允许，不等于可用”；T42 原生路径应把它映射成明确失败码，不能把“su 不存在”写成命令执行成功。 |

### 2.5 F07 只读设计的建议输入（不是定案）

- **识别**：只做只读握手，优先 `ksud -V`、`apd -V`、`magisk -V`（可复用 [ExternalInstallPlan.java](../../app/src/main/java/com/deepseekharness/app/util/ExternalInstallPlan.java):103-117 的检测形状），顺序与失败都要有稳定结果；不得用 `present()` 冒充。
- **清单**：只读固定目录（如 `/data/adb/modules`、管理器自己的模块根）的元数据；不做任意路径拼接；不跟随符号链接；单次输出上限、条目上限、字段白名单（名称 / 版本 / 启用状态 / 更新时间），不读脚本正文。
- **身份**：只在真实 `uid=0` 下执行；Root 开关关闭、无 su、管理器拒绝时返回不可用，不换 Shizuku/ADB 兜底。ADB 路径要单独定义 `su` 身份核验。
- **写入口**：本轮不提供安装/更新/卸载/禁用；即使 UI 出现按钮也必须禁用并说明条件（ADR-0004 决定）。
- **失败映射**：`NO_MANAGER` / `NO_ROOT` / `UNSUPPORTED_MANAGER_VERSION` / `LIST_PARTIAL` / `UNKNOWN`；区分“读不到”与“没有模块”。
- **数据与隐私**：清单可含模块名与版本，不得进入公开诊断；R5 未修前，原生授权偏好本身仍可能被同 UID 改写，文案照实写。

### 2.6 Root 映射的回归清单（A7 用）

- `DeviceShellPolicyTest` / `DeviceShellPolicyDeleteTest`：strict 白名单、`[POLICY_BLOCKED]`+126、三档与短信闸门。
- `RootShellCandidatesTest`：九候选的 owner / 类型 / 权限位判定；不执行 su。
- `DeviceChannelOrderTest` / `DeviceChannelSnapshotTest`：Root→Shizuku→ADB 顺序、API37、`SHELL` 只允许 root、`--su` 语义。
- `DeviceAppPolicyTest`：停止目标按包名、完整清单。
- 新增：ADB+su 身份核验（发送前/回执）、档位判定单点差分、F07 只读计划反例（路径穿越、链接、输出溢出）。

---

## 3. 内置 ADB 客户端（T42）

### 3.1 设计状态

[T42 设计稿](./adb-native-20261103/README.md) 是**设计稿、未实现**（用户 2026-11-03 已定 Q1–Q9；E1–E4 已在真机只读完成；E5–E8 未做）。目标：

1. 配对、私钥、连接与发命令全部移到 App 原生侧；容器只经 3090 桥提交命令文本。
2. 私钥优先 Android Keystore（K1，不可导出）；E7 不通过则退 K2（软件密钥 + Keystore AES 包装，`no_backup/`）。
3. 去掉容器强制 ADB 入口：`/device/execute?adb=1`、`adb-shell.py --host/--port`；通道只由原生按统一顺序选。
4. 文件 / 停止 / 短信 / `su -c` / 虚拟屏 commit 的判定只留 Java 一份，迁移完成后删除 `device-shell-policy.py`。
5. 旧容器密钥不导入，强制重新配对一次，删除 `/root/.dsh/adbkeys/adbkey*`，提示用户在系统「无线调试 → 已配对设备」撤销旧配对。
6. 原生化只降低“读走私钥 / 事后复用 / 随包脚本自拼命令”的风险；同 UID 仍可通过 `app_process` + Binder 使用 Keystore 密钥或直接 `su`，不能写成隔离。

### 3.2 当前“内置客户端”的真实形态（build361）

- 客户端不是 App 内的 Java 实现，而是随包 Python 脚本 + 锁定 wheel：`adb-pair.py`、`adb-shell.py`、`adb-setup.sh`、`device-shell-policy.py`、`adb-wheels.lock.json` 经 [managed-runtime-inputs.json](../../app/src/main/assets/managed-runtime-inputs.json):121-122、:395-401 注入 rootfs；[RuntimeTools.java](../../app/src/main/java/com/deepseekharness/app/runtime/RuntimeTools.java):431-482 同步脚本与 `root/dsh-bin/adb-shell` 包装。
- 依赖 wheel（`adb_shell_wifi`、`spake2_cffi`、`cryptography`、`zeroconf` 等）由 [adb-wheels.lock.json](../../app/src/main/assets/adb-wheels.lock.json) 锁定；TLS / SPAKE2 来自 guest wheel，不是 Android 平台 TLS。
- 私钥在 rootfs：`/root/.dsh/adbkeys/adbkey(.pub)`、`connect_host`、`connect_port`、`connect_port_history`（[AdbBridge.java](../../app/src/main/java/com/deepseekharness/app/bridge/AdbBridge.java):429、:547-549；[adb-shell.py](../../app/src/main/assets/adb-shell.py):189-204）。
- 连接：guest 先向原生 `/device/execute` 取计划（[adb-shell.py](../../app/src/main/assets/adb-shell.py):468-503、:379），拿到 `state:"adb"` 后自己连本机 adbd；`--host/--port` 存在时强制 ADB（:509）。
- 保活：配对/验证成功后自动 `python3 adb-pair.py --grant-keepalive`，由 shell 身份执行 `pm grant <app> WRITE_SECURE_SETTINGS`（[AdbBridge.java](../../app/src/main/java/com/deepseekharness/app/bridge/AdbBridge.java):461-537；[adb-pair.py](../../app/src/main/assets/adb-pair.py):112-121）。
- 探活：`AdbBridge.probe` 通过 proot 跑 `adb-shell.py --port <saved>` 执行 `id`，防止 Root/Shizuku 通道冒充 ADB（[AdbBridge.java](../../app/src/main/java/com/deepseekharness/app/bridge/AdbBridge.java):555-600）；`DeviceBridgeService` 从 rootfs 读 `connect_port`（[DeviceBridgeService.java](../../app/src/main/java/com/deepseekharness/app/DeviceBridgeService.java):592）。
- 覆盖更新：这些脚本属于受管输入；改/删它们会改变 `runtimeId` → 覆盖安装进一次维护页（第 4 节）。

### 3.3 设计稿 vs build361 对照

| T42 声明 | build361 事实 | 判定 |
|---|---|---|
| S1 原生对 ADB 返回 `state:"adb"` 计划 | [HttpShellService.java](../../app/src/main/java/com/deepseekharness/app/HttpShellService.java):1114-1116 仍返回 | 未变 |
| S2 `adb=1` / `--host` / `--port` 可强制 ADB | 路由 [HttpShellService.java](../../app/src/main/java/com/deepseekharness/app/HttpShellService.java):262-264 传 `adb`；guest :479 / :509 | 未变 |
| S3 私钥在 rootfs，guest 生成并配对 | [AdbBridge.java](../../app/src/main/java/com/deepseekharness/app/bridge/AdbBridge.java) 仍调 `adb-pair.py` / `adb-shell.py`；key 路径不变 | 未变 |
| S4 Java `AdbBridge` 无 ADB 协议实现 | 仍是 Python 执行器（类注释 :27-31） | 未变；但“AGENTS 写法与代码不符”已过时（[AGENTS.md](../../AGENTS.md):72 已更正） |
| S5 执行期判定都在 guest | [device-shell-policy.py](../../app/src/main/assets/device-shell-policy.py) 仍判 FILE / STOP / 短信 / `su -c` / SHELL 档位 / 虚拟屏 | 未变 |
| S6 原生 `devicePlan` 的 root/短信只体现在计划 | [HttpShellService.java](../../app/src/main/java/com/deepseekharness/app/HttpShellService.java):1192-1289 | 未变 |
| S7 `DeviceBridgeService` 读 rootfs `connect_port`，探活经 proot | :592；`probe` 仍走 Python | 未变 |
| S8 完整备份 / 导出带 ADB 私钥 | **已修**：`credential-paths.json` 的 `backupMachine.additionalExact` 含 `adbkeys`（[credential-paths.json](../../app/src/main/assets/credential-paths.json):43 → [CredentialPathRules.java](../../app/src/main/java/com/deepseekharness/app/util/CredentialPathRules.java):13）；生产备份引擎 :76 注释、恢复侧不落地；测试 [test-backup-engine.py](../../tools/test-backup-engine.py):232-244、:548-560。**注意**：本机环境重建仍保留 `root/.dsh/adbkeys`（维护快照不排除它，以保留本机配对；[MaintenanceDataSnapshot.java](../../app/src/main/java/com/deepseekharness/app/backup/MaintenanceDataSnapshot.java):123-131）——“备份/导出不含私钥”不等于“rootfs 没有私钥” | **已落地，设计稿需更新** |
| S9 首次配对后 `pm grant WRITE_SECURE_SETTINGS` | 仍在配对/验证成功后自动 grant（[AdbBridge.java](../../app/src/main/java/com/deepseekharness/app/bridge/AdbBridge.java):468、:495、:519） | 未变；Q5 未落地 |
| S10 文档写“关闭 ADB 保留配对密钥” | [权限架构](../device-permissions-architecture.md):156 仍这样写 | 未变；M6 文案未改 |
| S11 关 ADB 只递增 epoch、停探活，密钥不动 | [DeviceBridgeService.java](../../app/src/main/java/com/deepseekharness/app/DeviceBridgeService.java):41-55、:112-115 | 未变 |
| 设计稿“BRIDGE_PROTOCOL 递增到 3” | 已是 3（[HttpShellService.java](../../app/src/main/java/com/deepseekharness/app/HttpShellService.java):2623；[runtime-descriptor.json](../../app/src/main/assets/runtime-descriptor.json) 的 `bridgeProtocol=3`） | **前提失效，需要新决定** |
| 设计稿“`/device/plan`、`/device/vscreen/*` 删除” | 仍在路由表（[BridgeRoutes.java](../../app/src/main/java/com/deepseekharness/app/util/BridgeRoutes.java):10-13、:87-90）；虚拟屏已有 `nativeTicket` + commit | 未变；T42 实施时要与现有 T40 过渡票务对齐 |

### 3.4 已落地 / 可保留的部分

- **P0 备份泄露已修**（`1bb1c164`，build303 之后）：新备份与用户导出的 `DSHA-data-v5-*.tar.gz` 不含 `.dsh/adbkeys`；旧归档恢复时跳过归档内密钥、保留本机现有配对。设计稿 §7a / Q9 应改成“已完成”，并保留该排除规则直到原生化完成后的下一版。
- **权限语义可保留**：配对 ≠ 连接 ≠ 操作批准；`PAIR_OK` / `CONNECT_OK` / `CONNECT_WARN` / `DEPS_MISSING` 分开；API37 `ACCESS_LOCAL_NETWORK` 门（[LocalNetworkAccess.java](../../app/src/main/java/com/deepseekharness/app/bridge/LocalNetworkAccess.java):13-16）；探活必须真的来自 ADB（T38）；UNKNOWN 不重放。
- **失败边界**：连接前失败 `CONNECT_FAIL`/124（未发送），连接后异常 `EXECUTION_UNKNOWN`/125；`policy.Blocked`/126 在发送前。T42 应原样搬到原生。

### 3.5 实现前必须补齐的设计 / 决定

| # | 级别 | 缺口 | 建议 |
|---|---|---|---|
| A-1 | P1（文档） | 设计稿基线 build301、行号与事实已漂移；S8/S9/S10/协议四处需要改；状态仍是“未实现”但没有“已完成先行项”的回执 | 先出一版 `T42 设计稿 r2`：更新状态、把 §7a 标为已完成、把 AGENTS 更正写进勘误、把行号改成“以 build361 符号为准”；再进入实现 |
| A-2 | **P1（兼容）** | `BRIDGE_PROTOCOL` 已是 3，设计稿想升到 3。`/device/execute` 从 `state:"adb"` 改为 `state:"completed"` 是行为变更；仅靠 `>=3` 无法特性检测 | 新决定：升 4，或保留 3 但增加显式能力字段（如 `/app/version` 的 `deviceExecute` 能力）；写明旧插件 `state=="adb"` 分支的处理与警告窗口 |
| A-3 | **P1（依赖）** | T40 仍是设计稿、未实现；Q8 定 T42 排在 T40 之后；T42 §8 依赖 T40 的 `DeviceRefusal` / `nextAction` 格式 | 先落 T40 或明确“T42 先于 T40 时确认只能挡 Shizuku / 随包入口”，不能在文档里写成 ADB 确认已生效 |
| A-4 | **P1（可行性）** | E5–E8 未做；K1 的“平台 TLS 用 AndroidKeyStore RSA 私钥做 TLS1.3 客户端认证”未验证；SPAKE2 实现选型未定（随包 BoringSSL `spake25519` / 纯 Java），涉及来源、许可、16 KB 页、离线构建与摘要 | 在 `.test` 包做 E7/E8 探针；先冻结 SPAKE2 选型与来源（[references-and-provenance.md](../references-and-provenance.md)），再动代码；E7 失败按 Q1 退 K2，但 K2 的解密后内存暴露要照实写 |
| A-5 | P1（状态机） | `AdbChannelOwner` 只有职责描述：单连接、串行、总时限、epoch 关闭；没有数值、队列上限、与 `DeviceBridgeService` 探活/保活、API37 授权、虚拟屏 ticket 的交互 | 补状态机：连接生命周期（未配对/已配对/连接中/已连接/关闭中）、命令串行与排队上限、总时限与单命令时限、epoch 变化、用户关闭开关、进程重启；探活复用同一 owner，不能另开连接 |
| A-6 | P1（迁移） | M1–M3 无代码：检测旧 key、置“需重新配对”、新配对后删旧文件、UI 指引系统撤销；E6 未做（系统「已配对设备」是否可区分新旧） | 先定 UI 与文案：状态、原因、下一步；删除只针对 `adbkey` / `adbkey.pub` 两个常规文件（NOFOLLOW、不递归）；旧备份恢复已由 P0 保证不落地 |
| A-7 | **P1（授权）** | Q5 决定“保留功能、改为用户可选、默认不授权、新装不自动 `pm grant`”，当前仍是自动授权（S9） | Q5 可以独立于原生客户端先做：默认不 grant、设置页显示状态与说明、老用户不自动撤销；这与 T42 无冲突，且能立刻降低 R4 暴露 |
| A-8 | P1（身份） | ADB+su 的 root 身份未核验（见 R-4） | 原生客户端定义发送前/回执核验与失败码；保留“未发送”和“已发送后未知”的区分 |
| A-9 | P1（覆盖更新） | T42 Phase 3/4 会改受管输入（脚本、wheels、descriptor、`RuntimeTools`、`ManagedRuntimeLayout`），必然改 `runtimeId` → 一次维护页；若同一版再改描述符主文件或协议，风险叠加 | 把“维护页一次、协议/资产变更分版本”写进实施顺序；Phase 3 与 Phase 4 不要挤进同一个 build；每步保留回退点 |

### 3.6 验收补充（在 T42 §10 之外）

- **身份**：Root / Shizuku / ADB（含 `su=true`）各跑一次 `id`，回执必须能证明实际 UID；`su` 被拒绝时不能报成普通命令成功。
- **不做**：不把“私钥移出 rootfs”写成“同 UID 拿不到 ADB”；R1（Binder 用 Keystore）、R2（直接 su）、R5（改偏好）各写一条未修事实。
- **迁移**：旧 key 存在 → 升级后 ADB 不可用且显示“需重新配对”；重新配对后旧 key 被删；旧备份恢复不还原 key；系统侧旧配对撤销由用户完成。
- **覆盖更新**：T42 版本从上一版覆盖安装后，维护页只出现一次且成功；插件 `state=="adb"` 分支与 `/app/help` 同步更新；LAN / API37 授权不丢。
- **回归**：`AdbEnvironmentTaskTest`、`AdbWheelBundleTest`、`test-adb-flow.py`、`test-backup-engine.py`、`BridgeLifecycleTest`、`VirtualScreenStartOutcomeTest`、`AdbVirtualScreenStartTest`。

---

## 4. 交叉不变式

| # | 不变式 | 当前证据 | A7/T42 要求 |
|---|---|---|---|
| X1 | 只选一次通道，失败不换通道 / 不换屏重放 | [DeviceChannelOrder.java](../../app/src/main/java/com/deepseekharness/app/util/DeviceChannelOrder.java):53-100；`adb-shell.py` 连接前/后边界 | 原生客户端保持同一语义；探活不冒充业务执行 |
| X2 | UNKNOWN 与 NOT_SENT 分开 | `adb-shell.py`:536-553；`RootShell` 完整结果标记 | 原生 `AdbShell` 带同样的退出码标记与错误码；重试只在“新观察 + 新凭据”后由 Kotlin 发起 |
| X3 | 策略只留一份 | 设计：Java 判定，guest 消费计划；现状：Java + `device-shell-policy.py` 双份 | 迁移顺序：先逐条对齐、用例移植，再删 guest；不能先删后补 |
| X4 | 私钥不进 rootfs / 备份 | 备份已排除；rootfs 仍有 key（K1 目标未实现） | T42 完成前，文档必须同时写“备份不含”与“rootfs 仍含” |
| X5 | Root / ADB 开关只约束随包入口 | E2/E3/E4；T42 §2.2 R1–R5 | F07 与 A7 文案不得写“关闭即隔离”；R5 需 owner |
| X6 | 受管输入变化 = 维护页 | `runtime-descriptor.json` `bridgeProtocol=3`、`launcherInputs` 覆盖 `RuntimeTools`/`runtime`/`vscreen`/根包 | T42 分版本；每次只引入一次维护页；回退 tag |
| X7 | 设备写边界与短信闸门 | [DeviceShellPolicy.java](../../app/src/main/java/com/deepseekharness/app/util/DeviceShellPolicy.java):101-138；`DeviceShellExecutor` | 原生 ADB 文件路径继续走 `app_process … DeviceFileCore`（shell 身份），argv 由原生拼；短信在原生现场复核授权 |

---

## 5. 对 A7 任务基线的建议

**建议拆成两条线，不要合并出口判据：**

### A7-a：设备能力契约与只读 Root（本轮可发布）

- **范围**：三通道可用性与类型化执行契约；能力枚举 / 失败码 / 通道声明；Root 档位的单一判定点与 `su` 身份核验；F07 只读模块清单（本机、真实 uid0、固定命令与路径、只读、默认关闭）。
- **不改**：ADB 客户端实现形态（仍 guest Python）；不删 `adb=1` / `--host/--port`；不改私钥位置；不改桥协议；不改 F07 写入口（保持关闭）。
- **文件边界（建议）**：`util/DeviceShellPolicy*`、`util/DeviceChannelOrder*`、`util/RootShellCandidates`、`RootShell*`、`DeviceShellExecutor`、`DeviceBridgeService`（只读状态）、`HttpShellService` 的 device 端点、`ui/DeviceChannelsFragment`、`docs/design/kotlin-rust-rearchitecture-20261112/a7-task-baseline-20261113.md`（新）。
- **出口判据**：白名单/126 反例；`SHELL` 只走 root/su；`su` 身份核验反例；F07 只读计划反例（路径穿越、链接、输出溢出）；三通道各一次 `.test` 真机只读执行；文档与提示词同步；`runtimeId` 变化只触发一次维护页。
- **不在本轮**：T42 原生客户端、私钥迁移、`WRITE_SECURE_SETTINGS` 默认值（可独立先做）、R5 修复。

### A7-b：T42 原生化（独立任务，按 Q8 排在 T40 之后）

- 前置：T42 r2 设计稿更新；E5–E8 取证与 SPAKE2 选型；T40 落地或用户重新裁决顺序；协议版本新决定；Q5 落地。
- 出口：T42 §10 + 本文 §3.6；必须有一次完整的“旧 key → 重新配对 → 删除旧 key → 旧备份不还原”真机链路。

这样拆的好处：A7 可以在不触碰授权边界的前提下交付设备能力闭环；T42 的每个决定（Keystore、SPAKE2、协议、迁移）仍然按“整稿经用户确认后写代码”的规则走。

---

## 6. 需要用户 / 主会话裁决的问题

| # | 问题 | 可选方向 | 影响 |
|---|---|---|---|
| D1 | A7 是否接受本文的 A7-a / A7-b 拆分？ | 接受拆分 / 合并（合并需先完成 T42 前置） | 决定 A7 何时能开工、T42 是否阻塞发布节奏 |
| D2 | T42 的桥协议怎么标记“不再返回 ADB 计划”？ | 升 4 / 保留 3 加能力字段 / 只依赖文档迁移 | 旧插件 `state=="adb"` 分支与警告窗口 |
| D3 | Q5（`WRITE_SECURE_SETTINGS` 默认不授权）是否先做？ | 先做独立小修 / 等原生化一并做 | R4 暴露面；老用户状态展示 |
| D4 | R5（同 UID 改宿主 prefs）与 R2（直接 su）是否单独立项？ | 单独立项修 / 明确接受并写安全模型 | 决定“关闭开关”的对外表述边界 |
| D5 | F07 只读的管理器范围？ | Magisk / KernelSU / APatch / 还含 LSPosed / 仅检测不列模块 | 设计面与验收面大小；LSPosed 模块不是 root 管理器模块，需单独定义 |
| D6 | E5–E8 真机探针何时做？ | `.test` 包下一轮 / 与 A7 并行 / 等 T40 后 | K1 是否可行；SPAKE2 选型能否冻结 |

---

## 附录 A：证据索引（本次逐条核对）

- 权限与通道：[device-permissions-architecture.md](../device-permissions-architecture.md):26-31、:67-102、:156、:183-220；[device-channels.md](../device-channels.md)；[security-model.md](../security-model.md):49、:55、:57。
- Root 策略：[DeviceShellPolicy.java](../../app/src/main/java/com/deepseekharness/app/util/DeviceShellPolicy.java):22-46、:128-138、:163-180；[RootShell.java](../../app/src/main/java/com/deepseekharness/app/RootShell.java):55-79、:102-124；[RootShellCandidates.java](../../app/src/main/java/com/deepseekharness/app/util/RootShellCandidates.java):8-19；[RootShellMain.java](../../app/src/main/java/com/deepseekharness/app/RootShellMain.java):72、:78、:93。
- 通道与执行：[DeviceChannelOrder.java](../../app/src/main/java/com/deepseekharness/app/util/DeviceChannelOrder.java):16-24、:53-100；[HttpShellService.java](../../app/src/main/java/com/deepseekharness/app/HttpShellService.java):1083-1133、:1192-1289、:2623；[DeviceShellExecutor.java](../../app/src/main/java/com/deepseekharness/app/DeviceShellExecutor.java):25-34、:83-117；[ConfigStore.java](../../app/src/main/java/com/deepseekharness/app/core/ConfigStore.java):281-299；[DeviceBridgeService.java](../../app/src/main/java/com/deepseekharness/app/DeviceBridgeService.java):41-55、:112-121、:592。
- ADB 客户端：[AdbBridge.java](../../app/src/main/java/com/deepseekharness/app/bridge/AdbBridge.java):27-31、:429、:461-537、:555-600、:607-731；[adb-shell.py](../../app/src/main/assets/adb-shell.py):6-8、:298-330、:379、:468-503、:536-553；[adb-pair.py](../../app/src/main/assets/adb-pair.py):112-121；[device-shell-policy.py](../../app/src/main/assets/device-shell-policy.py):335-400；[BridgeRoutes.java](../../app/src/main/java/com/deepseekharness/app/util/BridgeRoutes.java):10-13、:87-90。
- 备份与覆盖更新：[credential-paths.json](../../app/src/main/assets/credential-paths.json):43；[CredentialPathRules.java](../../app/src/main/java/com/deepseekharness/app/util/CredentialPathRules.java):13；[MaintenanceDataSnapshot.java](../../app/src/main/java/com/deepseekharness/app/backup/MaintenanceDataSnapshot.java):123-131；[backup-engine.py](../../app/src/main/assets/backup-engine.py):76；[test-backup-engine.py](../../tools/test-backup-engine.py):232-244、:548-560；[managed-runtime-inputs.json](../../app/src/main/assets/managed-runtime-inputs.json):121-122、:395-401；[runtime-descriptor.json](../../app/src/main/assets/runtime-descriptor.json)。
- 设计依赖：[T42](./adb-native-20261103/README.md)、[T40](./device-exec-confirm-20261103/README.md)（状态：设计稿，未实现）、[A7 路线图](./kotlin-rust-rearchitecture-20261112/iteration-roadmap-20261112.md):242-247、[ADR-0004](../adr/0004-kotlin-rust-direction.md):89、[评审 E](./kotlin-rust-rearchitecture-20261112/drafts/05-safety-permissions.md)、[18 模块回复](./kotlin-rust-rearchitecture-20261112/reply-to-18-modules-20261112.md):67。

## 附录 B：本次核查没有做的事

- 没有操作手机、没有执行 `su` / ADB / 设备命令；E1–E4 的结论与 E5–E8 的缺口全部转引自 T42，不新增真机证据。
- 没有编译、没有跑单测、没有跑 lint；本文的“一致”指源码与文档的字面事实一致，不是运行时验证。
- 没有评估任意 ROM / Root 管理器 / Shizuku 版本 / ADB 实现兼容性，也没有证明 K1 / K2、SPAKE2 或 TLS 在目标机可行。
- 没有裁决 R2/R5 的修复方案；这需要用户决定是否立项。
