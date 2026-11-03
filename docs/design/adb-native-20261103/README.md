# ADB 通道原生化（T42 设计稿）

状态：**设计稿，未实现**。基线 build301（`3d370ac1`），只读源码取证，**未做真机取证**（§3 全部待取证）。
来源：主会话权限归属审计 `.agents/claims/T42/perm-audit-20261103.md` 第 1、3、11 条（基线 build300，本稿已在 build301 上复核行号）。
这是授权边界改动：整稿经用户确认后才能写代码。不放宽 `DeviceShellPolicy`、UNKNOWN 不重放、执行失败不换通道重放。

## 0. 结论

1. ADB 的配对、私钥、连接和发命令全部移到 App 原生侧；容器只经 3090 桥提交命令文本，拿回已完成的结果。`/device/execute` 不再返回 `state:"adb"` 计划。
2. 私钥优先放 Android Keystore，**不可导出**（K1）；若真机证明平台 TLS 用不了 Keystore 私钥，退到「软件密钥 + Keystore AES 包装，放 `no_backup/`」（K2）。私钥任何时候都不进 rootfs、不进备份。
3. 去掉容器强制走 ADB 的入口：`/device/execute` 的 `adb=1`、`adb-shell.py` 的 `--host/--port`。走哪条通道只由原生按 T41 的统一顺序选。
4. 写文件 / 结束应用 / 短信复核 / `su -c` / 虚拟屏 commit 的判定只留 Java 一份，`device-shell-policy.py` 在迁移完成后删除。
5. 旧容器密钥不导入：新版本生成新密钥、要求重新配对一次，然后删除 `/root/.dsh/adbkeys/adbkey*`，并提示用户在系统「无线调试 → 已配对设备」里撤销旧配对。
6. 必须照实写：原生化堵住的是「读走私钥文件、离线或事后复用」和「随包脚本自己拼命令」。同 UID 代码仍可能通过 `app_process` + Binder 使用 Keystore 里的不可导出密钥，或直接执行 `su`（§2.2）。这是降低风险，不能写成隔离。
7. **可先行的小修 P0（§7a）**：已发布版本的完整备份和用户导出（无需密码）都带着 ADB 私钥。这条泄露比原生化更急，可以不等原生化单独先修：备份与恢复都排除 `.dsh/adbkeys`。是否先修见 Q9。

## 1. 现状（源码事实，build301）

调用链：`/root/dsh-bin/adb-shell`（`dsha-device-shell.sh` → `python3 /root/.dsh/adb-shell.py`）→ `GET /device/execute?cmd=&su=&adb=` → `HttpShellService.deviceExecute`。

| # | 事实 | 位置 |
|---|---|---|
| S1 | 原生只在 Root / Shizuku 时自己执行；走 ADB 时返回 `{"state":"adb","plan":…}`，由 guest 连 adbd 发送 | `HttpShellService.java:1065-1109`（1081-1083 原始 shell、1102-1103 普通命令） |
| S2 | `adb=1` 让原生跳过 Root/Shizuku 直接给 ADB 计划；`adb-shell.py` 只要带 `--host` 或 `--port` 就置 `force_adb` | `HttpShellService.java:253-257`；`adb-shell.py:509` |
| S3 | 私钥 `/root/.dsh/adbkeys/adbkey(.pub)` 在 rootfs 内，guest 生成并配对；连接端口、主机、历史端口也在同目录 | `adb-shell.py:27-29,189-204`；`adb-pair.py:34-40` |
| S4 | **Java `AdbBridge` 没有任何 ADB 协议实现**：配对、校验、探活、虚拟屏启动、`--grant-keepalive` 全是 `execOwned(proot, "python3 /root/.dsh/adb-*.py …")`。TLS / SPAKE2 来自容器 wheel `adb_shell_wifi`、`spake2_cffi`、`cryptography`（`adb-wheels.lock.json`）。AGENTS.md 里「AdbBridge（无线配对 TLS1.3-PSK + SPAKE2）」的写法与代码不符 | `AdbBridge.java:180,429,463,491,521,547,566` |
| S5 | ADB 路径的执行期判定都在 guest：FILE 路径（`validate_files`）、STOP 现场清单（`stop_targets`）、短信路径（`validate_root_reads`）、短信发送前复核、`su -c` 拼接、SHELL 档位形态、虚拟屏 commit | `device-shell-policy.py:285-352`；`adb-shell.py:298-319` |
| S6 | 原生 `devicePlan` 的 root 开关、短信开关只体现在计划里；拿到私钥的代码不需要计划 | `HttpShellService.java:1168-1265` |
| S7 | `DeviceBridgeService` 读 rootfs 里的 `connect_port`；探活经 proot 跑 Python，容器没就绪时 ADB 也不可用 | `DeviceBridgeService.java:435-600` |
| S8 | **完整备份带上 ADB 私钥**：备份引擎测试断言 `.dsh/adbkeys/adbkey` 保留；`BackupScope.dshPaths` 全量为整个 `.dsh`。用户导出 v5 无需密码 | 生产引擎 `assets/backup-engine.py`（`BackupManager.java:362-393` 调用）；其测试 `tools/test-backup-engine.py:532-542`；`BackupScope.java:158` |
| S9 | 首次配对后经 ADB `pm grant` 给 App UID `WRITE_SECURE_SETTINGS`，`BootReceiver` / 保活据此写 `adb_wifi_enabled` | `adb-pair.py --grant-keepalive`；`BootReceiver.java:38-53`；`DeviceBridgeService.java:628-640` |
| S10 | 文档写「关闭 ADB 保留配对密钥」 | `docs/device-permissions-architecture.md:149-150` |
| S11 | 关 ADB 开关只递增意图 epoch、停探活；密钥文件不动，容器照样能用 | `DeviceBridgeService.adbIntentChanging` 42 行 |

对照：Root 执行器在 `RootShell` → `RootShellMain`，Shizuku 在 `ShellService` → `DeviceShellExecutor.execute`，判定都在原生或特权进程（审计 B 类）。外接设备调试（`extdev/ExternalDeviceHost`）用自己的 root 子进程 + 专用 HOME 的 adb server，不经本通道，不在本稿范围。

## 2. 威胁与边界

### 2.1 现在能做到的绕过

同 UID 的助手、插件或终端只要读 `/root/.dsh/adbkeys/adbkey`，就能用任意 ADB 客户端以 shell 身份（uid 2000）连本机 adbd 执行任意命令。原生的 ADB 开关、root 开关与档位、短信开关、FILE / STOP 保护、T40 将来的确认都拦不住。私钥还会随完整备份离开设备（S8），被拿到另一台机器上，只要用户没撤销配对、无线调试开着，就能远程用。

### 2.2 原生化之后仍然存在的（写进安全模型）

| 残留 | 说明 | 能做的 |
|---|---|---|
| R1 同 UID 使用 Keystore 密钥 | Keystore 按 UID 授权。容器能 `exec` `/system/bin/app_process`（虚拟屏 Core 就是这么起的）时，可以用 DSHA 的 APK 当 classpath 在 App UID 下拿 Binder，调 keystore2 用密钥签名。拿不走密钥，但能**用** | 关闭 ADB 即删除别名（Q2），使用窗口限于开关打开期间；E5 取证 |
| R2 直接执行 `su` | root 管理器按 UID 授权时，容器可绕过 `RootShell` 档位（审计 #3、未核实 1） | 不属于本任务；E3 取证后写进安全模型 |
| R3 直接调 Shizuku Binder | 同 R1 的 `app_process` 路线；Shizuku 按调用 UID 授权 | 不属于本任务；E4 取证 |
| R4 `WRITE_SECURE_SETTINGS` | 授给 App UID 后同 UID 代码都能改安全设置（不止 `adb_wifi_enabled`，例如无障碍服务列表） | Q5 |
| R5 改写原生偏好 | 若 proot 内能越界写 `shared_prefs`，所有原生授权都可伪造（审计未核实 3） | E1/E2；结论超出本任务时另立 |

结论：本任务把 ADB 从「拿文件即可」提到「必须在本机、开关打开期间、走 Binder 用 Keystore」，并让所有随包入口都经原生策略。R1–R5 要照实写，文档和提示词不能写「ADB 命令必须经原生策略才能执行」。

## 3. 待取证（未执行，须用户在本会话明确同意，且只读）

当前会话本身运行在容器里，E1–E6 的容器侧检查无需操作手机屏幕，但仍属于真机取证，未经同意不做。E7–E8 要在测试包里跑探针代码，属于实现阶段的可行性验证。

| # | 问题 | 只读做法 | 影响 |
|---|---|---|---|
| E1 | proot 内能否看到宿主 App 私有目录（`/data/data/<pkg>/shared_prefs`、`no_backup`、`databases`、`files/` 以外） | `ls -ld`、`stat` 宿主绝对路径；`cat /proc/self/mountinfo`、`/proc/self/status` 的 TracerPid；不读文件内容 | 决定 K2 是否还有意义 |
| E2 | 绕过 proot 路径翻译：静态二进制直接 `openat` 宿主路径；proroot 与 proot 两种引擎分别看 | 只 `open(O_RDONLY)` 后立刻关闭、不读内容；只针对 DSHA 自己的目录 | 同上；也决定 R5 的严重度 |
| E3 | 容器能否直接执行 `/system/bin/su` 拿到 root | 只看 `ls -l` / `which su` 和管理器授权记录；**不实际执行 su**，除非用户另行同意执行 `su -c id` | R2 |
| E4 | 容器能否打开 `/dev/binder`、执行 `app_process` | `ls -l /dev/binder`；`app_process` 只跑 `--help` 类无副作用调用，不加载任何类 | R1、R3 |
| E5 | `app_process` 在 App UID 下能否调 keystore2 | 需要探针类，放实现阶段在 `.test` 包验证 | R1 |
| E6 | 系统「已配对设备」能否列出 / 撤销旧配对；`/data/misc/adb/adb_keys` 是否 shell 可读 | 手机设置页只读查看（需 `android_control begin`） | 迁移文案 |
| E7 | 平台 TLS（非 Conscrypt 独立包）能否用 AndroidKeyStore RSA 私钥做 TLS 1.3 客户端认证（`rsa_pss_rsae_sha256`）对 adbd 握手 | `.test` 包探针 | K1 是否可行 |
| E8 | `android.net.ssl.SSLSockets.exportKeyingMaterial`（API 29+）在目标机上可用，配对能完成 | `.test` 包探针 | 配对实现 |

## 4. 私钥位置方案

| 方案 | 做法 | 防「读走文件」 | 防「同 UID 使用」 | 风险 |
|---|---|---|---|---|
| **K1（推荐）** | `KeyPairGenerator("RSA","AndroidKeyStore")`，2048 位，`PURPOSE_SIGN`，摘要 `SHA-256`+`NONE`，填充 PSS+PKCS1；有 StrongBox 用 StrongBox。公钥按 adbd 格式编码后用于配对 | 是，硬件或 TEE 内 | 否（R1） | E7 不通过就不可用 |
| K2（退路） | App 进程内生成 RSA，PKCS#8 用 Keystore AES-GCM 密钥加密后存 `no_backup/adb/key.bin`（Shizuku 的做法） | 依 E1/E2：proot 看不到则基本是 | 否；且解密后在内存里，可被导出 | 比 K1 弱一档 |
| K3（现状） | rootfs 明文文件 | 否 | 否 | 本任务要消除 |

两种方案的共同规则：
- 别名 / 文件只在原生；`BridgePathPolicy`、`DshaDocumentsProvider`、备份全部看不到。
- `android:allowBackup` / 数据提取规则排除 `no_backup/adb`（K2）；Keystore 本身不随备份走。
- 关闭 ADB 开关：删除别名（K1）或密文 + AES 别名（K2），见 Q2。
- 公钥指纹（SHA-256 前 8 字节）显示在设备能力授权页，方便用户和系统「已配对设备」对照。

## 5. 原生 ADB 客户端与桥接口

### 5.1 原生组件（新包 `adb/`，纯逻辑进 `util/` 配单测）

| 组件 | 职责 |
|---|---|
| `adb/AdbIdentity` | 生成 / 读取 / 删除密钥（K1 或 K2），产出 adbd 公钥串与 TLS `KeyManager` |
| `adb/AdbPairing` | 无线配对：TLS 1.3 + SPAKE2（口令混入导出密钥材料），交换 PeerInfo。实现前逐条对照 AOSP `packages/modules/adb/pairing_auth`、`pairing_connection` 源码。SPAKE2 需自带实现（平台 BoringSSL 的 SPAKE2 不对应用开放）：候选是随包 `.so` 编进 BoringSSL `spake25519`，或纯 Java 实现，选型时补来源与许可证到 `references-and-provenance.md` |
| `adb/AdbConnection` | CNXN → STLS → TLS → 认证完成；只连本机地址（沿用 `AdbBridge.localAddresses`）；发现用现有 NsdManager（`AdbBridge.discover`），不再用 zeroconf wheel |
| `adb/AdbShell` | 单条 argv 的 shell 流，带退出码标记（沿用 `__DSHA_EXIT_` 设计）；连接成功前的失败是「未发送」，之后的任何异常都是 UNKNOWN |
| `adb/AdbChannelOwner` | 唯一连接持有者：同一时刻最多 1 条连接、命令串行、总时限、ADB 意图 epoch 变化即关闭；与 T38 的进程 / 连接上限一起设计 |
| `adb/AdbDeviceRunner` | 实现 `DeviceShellExecutor.Runner`，让 ADB 走和 Shizuku 同一个 `DeviceShellExecutor.execute`；FILE 在 ADB 路径继续经 `app_process … DeviceFileCore` 在 shell 身份下执行（argv 由原生拼，不再由 guest 拼） |

`DeviceShellExecutor` 要补的点（现在只为 Shizuku UserService 写）：
- 短信：ADB 路径允许，`SmsQuery.forUser` 的 argv 在原生现场再取一次授权后发送（替代 `adb-shell.py:298-307`）。
- 原始 SHELL：只有 `devicePlan` 判定 root 允许且档位合法时，由原生加 `su -c` + `ShellQuote`；guest 不再能提出 `su`。
- 虚拟屏：`VirtualScreenManager.commitAdbLaunch(ticket)` 在原生发送前一刻直接调用，`/device/vscreen/commit` 路由删除。Core 进程是否依赖 adb shell 流保活需在实现时核对 `AdbVirtualScreenStart`，结论写进稿子再动。

### 5.2 桥接口变化

| 接口 | 现在 | 改后 |
|---|---|---|
| `/device/execute` | 可能返回 `state:"adb"` + 计划 | 只返回 `state:"completed"`（含 T40 的 `refusal`）；ADB 由原生执行 |
| `/device/execute?adb=1` | 强制 ADB | 拒绝，`DEVICE_CHANNEL_FORCE_UNSUPPORTED`，`actionExecuted:false`（Q4） |
| `/device/plan` | guest 发送前取计划 | 删除（唯一使用方是 guest ADB 客户端） |
| `/device/vscreen/start`、`/commit` | guest 发送 | 删除；虚拟屏启动全在原生 |
| `adb-shell.py` | 完整 ADB 客户端 | 薄包装：提交命令、打印结果；去掉 `--host/--port`；传入时报 `INVALID_ARGUMENT`（Q4） |
| `adb-pair.py`、`adb-setup.sh`、`device-shell-policy.py`、ADB wheels | 容器里 | 迁移完成的下一版删除，同时从 `managed-runtime-inputs.json`、`asset-deployment.json`、`runtime-descriptor.json`、`ManagedRuntimeLayout`、`RuntimeTools` 移除 |

旧版本插件判 `state=="adb"` 的分支会自然不再命中；`BRIDGE_PROTOCOL` 递增到 3，插件按 `>=` 特性检测。

### 5.3 不变量（写成单测）

- 原生 ADB 只在 `deviceExecute` 选出 ADB 通道后才发送，Root / Shizuku 已执行（无论成败）不再转 ADB。
- 连接建立后的任何异常 → `[EXECUTION_UNKNOWN]`/125，不换地址、不换通道重发（`adb-shell.py:296-297` 的语义原样搬过来）。
- 连接前失败 → `DEVICE_CONNECT_FAILED`/124，`actionExecuted:false`。
- ADB 开关关闭、epoch 变化、密钥删除后，旧连接在下一条命令前关闭，不复用。

## 6. 去掉强制入口

1. `BridgeRoutes` 的 `DEVICE_EXECUTE` 不再读 `adb` 参数；带了就按 §5.2 拒绝。
2. `deviceExecute(command, forceRoot, forceAdb)` 去掉 `forceAdb`，选通道调用 T41 的统一顺序函数（T41 未进 main 时保持现有 Root → Shizuku → ADB）。
3. T38 分支 `AdbBridge.probe` 为了不被 Root/Shizuku 冒充而加了 `--port`（`fix/adb-channel-pileup` 53938150）。原生探活直接用 `AdbChannelOwner` 跑 `id`，这条强制路径一起退役。
4. `AccessibilityRepairOwner` 等「必须是 ADB」的内部调用走原生 API，不再借桥参数强制。

## 7. 旧密钥迁移与撤销

| 步 | 做法 |
|---|---|
| M1 | 新版本首次启动检测 rootfs 有 `adbkeys/adbkey`：**不导入**（容器早已知道这把钥，导入等于保留漏洞）。ADB 状态置「需要重新配对」，设备能力授权页提示原因 |
| M2 | 用户重新配对成功（新 Keystore 密钥）后，原生删除 `root/.dsh/adbkeys/adbkey`、`adbkey.pub`（NOFOLLOW、只删这两个常规文件，不递归）；`connect_port*` 改存原生偏好后一并删除 |
| M3 | 提示用户到系统「开发者选项 → 无线调试 → 已配对设备」撤销旧条目（E6 确认能否区分新旧，靠 §4 的指纹）；DSHA 不能替用户撤销 |
| M4 | 备份（可先行，见 §7a）：今后备份排除 `.dsh/adbkeys`；恢复旧备份时 `adbkeys/` 进隔离区不落地（与「本机授权状态不能从用户归档生效」一致）；`test-backup-engine.py:532-542` 的断言反过来 |
| M5 | 用户不重新配对：ADB 通道不可用，返回 `DEVICE_ADB_REPAIR_REQUIRED`，Root / Shizuku 不受影响 |
| M6 | 关闭 ADB：删除密钥（Q2）；`docs/device-permissions-architecture.md:149-150`「关闭 ADB 保留配对密钥」改写 |

规则对齐：迁移前先把 `device-shell-policy.py` 与 Java（`DeviceShellExecutor`、`DeviceAppPolicy`、`DeviceFileOperations`、`validateRootReadPaths`）逐条对照（审计未核实 7），把 `tools/test-device-shell-policy.py`、`test-adb-flow.py` 的用例移植为 Java 单测，差异逐条列出由用户定取哪边（不放宽）。之后只留 Java 一份。

## 7a. 先行小修 P0：备份 / 导出排除 ADB 密钥（不依赖原生化）

现状（build301 已发布版本即存在）：
- 生产备份引擎 `assets/backup-engine.py` 只排除 `SKIP`、缓存和 `credential-paths.json` 里的本机状态；第 75 行注释明确写「插件内同名目录与配对密钥均属于备份内容」，`tools/test-backup-engine.py:532-542` 断言保留 `.dsh/adbkeys/adbkey`。
- 用户导出 v5（`DSHA-data-v5-<UUID>.tar.gz`）无需密码，私钥随文件离开设备；拿到文件的人在用户未撤销配对、无线调试开着时可连这台手机的 adbd。
- 对照：桥 token 按 `credential-paths.json` 的 `externalMachine` 整文件排除。

修法（最小改动，和桥 token 同一套机制）：
1. `app/src/main/assets/credential-paths.json` 的 `backupMachine.additionalExact` 加 `adbkeys`，用 `tools/generate-credential-paths.py` 重新生成 `CredentialPathRules.java`（源摘要随之更新）。这一项同时被备份引擎（`LOCAL_DEVICE_FILES`，第 57-62 行）和恢复侧 `DataRootPolicy.machine`（`DEVICE_SPECIFIC_STATE`）使用，所以新备份不带、旧备份恢复时也不落地。
2. 实现时核对该集合在 `.dsh` 子目录层级是否生效；若只作用于顶层，就在 `.dsh` 枚举处补一条按名排除，不新增第二套规则。
3. 改第 75 行注释和 `test-backup-engine.py` 的断言（改为 `assertNotIn`），并加一条「旧归档含 `adbkeys/` 时恢复不还原」的测试；Java 侧 `DataRootPolicy` 补对应单测。
4. 文档：`docs/security-model.md` 已知弱点里写明「build301 及以前的完整备份 / 导出含 ADB 私钥」，并给用户处理建议：删除旧导出文件，或在系统「无线调试 → 已配对设备」撤销 DSHA 的配对后重新配对。

影响：在同一台手机上用新备份恢复后，ADB 需要重新配对一次（密钥本来就在原 rootfs 里，正常覆盖更新不受影响）。不改 ADB 执行路径，不碰 `AdbBridge` / `DeviceBridgeService`，与 T38 不冲突。原生化（§9）落地后，这条排除规则继续保留，防旧密钥目录残留。

## 8. 与其它任务的边界

| 任务 | 交叉点 | 约定 |
|---|---|---|
| T38（@t38-adb，`fix/adb-channel-pileup`） | 同改 `AdbBridge`、`DeviceBridgeService`；探活加 `--port` | T38 先合入；T42 在其上变基。T38 的探活强制端口在 T42 Phase 3 退役；`AdbChannelOwner` 的连接上限 / 回收沿用 T38 取证结论，有冲突先 peer 协调 |
| T40（@t40-devexec，DeviceGuard） | T40 §5.3 为 ADB 设计了 `nativeTicket` + `/device/commit` 发送前核销；§7 表把 ADB 写成「确认挡不住」 | T42 原生执行后确认在原生阻塞完成，**不需要 ADB 票据**。若 T42 实现排在 T40 之后，T40 的票据作过渡，T42 删除；若同期，T40 跳过票据。T40 §7 的 ADB 行改为「随包入口可强制，同 UID 见 R1」 |
| T41（选通道顺序） | `deviceExecute` 的顺序 | T42 不改顺序，只删 `forceAdb`；顺序函数归 T41，T42 调用它 |
| T39（@t39-guard，AgentNextAction 与错误格式） | 新错误码 | 不新增 nextAction 词。新增 error：`DEVICE_ADB_NOT_PAIRED`、`DEVICE_ADB_REPAIR_REQUIRED`、`DEVICE_CHANNEL_FORCE_UNSUPPORTED`，均 `nextAction: REPORT_TO_USER`，格式用 T40 `DeviceRefusal`；登记进同一张表 |
| T3 外接设备 | 也用 adb | 独立 root 子进程与 HOME，不并入本通道 |

## 9. 实施顺序（确认后）

1. **Phase 0 取证**：E1–E6（用户同意后，只读）；E7–E8 在 `.test` 包做探针，决定 K1 / K2。
2. **Phase 1 规则对齐**：Python 与 Java 规则逐条对照 + 用例移植，不改生产行为。
3. **Phase 2 原生客户端**：`adb/` 组件 + 单测（协议帧、退出码标记、未发送 / UNKNOWN 边界、epoch 关闭）；配对页、探活、保活授权改走原生；容器路径暂留作对照，默认走原生。
4. **Phase 3 切换**：`deviceExecute` 不再返回 ADB 计划；删 `adb=1`、`--host/--port`、`/device/plan`、`/device/vscreen/*`；迁移 M1–M6；`BRIDGE_PROTOCOL=3`；改提示词（同步 `tools/test-prompt-language.mjs`）、`/app/help`、`docs/security-model.md`、`docs/device-permissions-architecture.md`、AGENTS.md 中 `AdbBridge` 描述。
5. **Phase 4 清理（下一版）**：删容器 Python 客户端、策略与 wheels，更新受管资产清单与摘要。

## 10. 验收

- 容器内无 ADB 私钥：rootfs 与备份里都没有 `adbkey`；新导出的备份不含 `.dsh/adbkeys`。
- 随包入口全部经原生：`adb-shell`、`/exec`、`/device/execute` 的 ADB 执行都由原生发出；`adb=1`、`--host/--port` 被拒。
- 关闭 ADB 后，原生不再能连接，Keystore 别名已删除（Q2 选删除时），重新开启须重新配对。
- 迁移：老版本升级后提示重新配对，旧密钥文件被删除，旧备份恢复不还原密钥。
- 真机（`.test` 包）：Root / Shizuku / ADB 三通道各跑只读命令、Download 下临时文件写入、结束一个普通用户应用、短信关闭 / 开启各一次；UNKNOWN 与未发送两种失败各造一次，确认不重放、不换通道。
- R1–R5 的取证结论写进安全模型，不扩写成「已隔离」。

## 11. 需要用户决定

- **Q1 私钥方案**：采用 K1（Keystore 不可导出），E7 不通过时自动退到 K2？还是只接受 K1，不行就保持现状另议？
- **Q2 关闭 ADB 开关**：删除密钥（下次开启要重新配对，推荐，因为不删的话同 UID 仍可用，见 R1），还是保留密钥只由原生拒用（方便，但关闭不等于失效）？
- **Q3 迁移**：按 §7 强制重新配对一次并删除旧密钥文件（推荐）？还是允许导入旧密钥免重配（不推荐：容器已持有这把钥）？
- **Q4 旧入口**：`adb=1` 与 `--host/--port` 直接返回错误码（推荐），还是一个版本内忽略并警告？
- **Q5 `WRITE_SECURE_SETTINGS` 保活授权**：保留现状、改为用户可选且默认不授，还是取消？它给整个 App UID，同 UID 代码可改其他安全设置。
- **Q6 备份**：今后备份排除 `.dsh/adbkeys`，恢复时隔离旧备份里的密钥（推荐）？（具体做法见 §7a，是否先行见 Q9）
- **Q7 取证授权**：是否允许我在本会话做 §3 的 E1–E4（容器内只读，`su` 不执行）和 E6（手机设置页只读查看，需先 `android_control begin`）？
- **Q8 排期**：实现等 T38 合入后开始，与 T40 同期（T40 跳过 ADB 票据）？
- **Q9 先修备份泄露**：是否同意不等原生化，先按 §7a 单独做一个小修（排除 `adbkeys` + 测试 + 安全模型说明）排一个版本号？代价是在同一台机上从新备份恢复后要重新配对 ADB 一次。
