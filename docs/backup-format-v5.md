# 宿主数据归档 v5

build 131 增加向后兼容的 `dataCompatibility` 元数据：记录模型 schema 与所观察的运行时/读写实现摘要；没有可靠格式声明时 `formatId`、`formatEpoch` 为空，状态为 `unknown`。APK 版本、运行时版本和数据格式不是同一代次。发件人的声明不构成本机验证；预检继续显示格式未确认，不据此盲目降级或自动删除原件。已有明确范围的宿主恢复仍适用。保留副本和旧树入口见 [稳定性验收](stability-acceptance.md)。

实现位于 `backup/BackupArchive.java` 和 `backup/PortableBackupCrypto.java`。集成与验证状态见 [专项验收记录](backup-upgrade-acceptance.md)。v5 不修改历史 v1–v4 的版本含义；现有 Python 引擎实际写入格式为 4。

## 便携文件

便携文件名为 `DSHA-data-v5-<UUID>.tar.gz`。外层 tar/gzip 中的 `portable.dshbak` 包含实际数据，`README.txt` 说明如何导入。build377 的一键备份要求设置备份密码，自定义导出和救援导出也默认使用密码。用户明确选择明文兼容格式后，内层使用带完整尾摘要的 `DSHADATA`。加密格式的内层为 `DSHABAK5`。
导入按内容识别格式，完整核验 gzip、tar 成员和内层归档。除了载荷和说明，明文救援导出可以有一份有界的 `diagnostics.txt`。加密救援导出省略外层诊断。历史裸 `.dshbak` 与 `.tar.gz`、`.tgz`、`.tar` 继续按原格式读取。


导入加密包时输入创建备份的密码。重导已验证的手动加密副本时，应用核对源密文摘要并原样导出密文，恢复时仍用原密码。明文副本和本机自动副本重导时设置新密码。自动副本先用本机密钥完整认证，重封后的清单标记为 `PASSWORD_ENCRYPTED`，实际范围和完整性等级继承自原件。

所有多字节整数按大端存储。加密 v5 容器的头部共 48 字节。

| 偏移 | 长度 | 含义 |
|---:|---:|---|
| 0 | 8 | ASCII `DSHABAK5` |
| 8 | 4 | 格式版本，值为 5 |
| 12 | 4 | PBKDF2 迭代次数，新文件为 600000 |
| 16 | 4 | 算法套件，值为 1 |
| 20 | 16 | 随机 salt |
| 36 | 12 | 随机 GCM nonce |

套件 1 使用 PBKDF2-HMAC-SHA256，输出 32 字节 AES 密钥，再用 AES-256-GCM 加密。完整头部作为 AAD；头后为密文，最后 16 字节是 GCM 认证标签。内部加密副本每次独立生成 salt 和 nonce。读取只接受 600000–1200000 次迭代；不可信参数在派生前拒绝。

密码直接编码为 UTF-8，不执行 Unicode 归一化。当前接口接收 12–1024 个 Java UTF-16 单元。密码、派生密钥与中间缓冲不写入任务记录或偏好；任务结束清理可清理的内存数组，但不承诺消除虚拟机或闪存中的所有历史副本。

解密流在认证标签核验前可能产生明文，因此只写入本任务的私有隔离文件。认证成功后才解析归档、列出冲突并等待用户确认。认证失败会删除解密暂存。选择明文兼容格式时，取得文件的人能读取其中全部对话、附件、项目与配置。

派生参数依据 [OWASP 的 PBKDF2-HMAC-SHA256 建议](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)。密码学实现使用锁定的 Bouncy Castle 轻量 API，不替换系统 Provider。库版本、摘要和许可见 `tools/backup-dependencies.lock.json` 与 `THIRD_PARTY_NOTICES.md`。

## 明文容器

容器为流式、未压缩的记录序列：

1. 8 字节 ASCII `DSHADATA`，随后 4 字节版本号 5。
2. 每条记录为：4 字节 JSON 元数据长度、UTF-8 JSON、8 字节载荷长度、载荷。
3. 4 字节 `-1` 表示记录结束，随后为 4 字节清单长度和 UTF-8 清单。
4. 32 字节 SHA-256 覆盖此前所有容器字节，包括结束标记、清单长度和清单内容。摘要之后必须立即 EOF。

记录必需字段为 `root`、`path`、`kind`、`scope`、`size`、`sha256`。`mode` 为可选的 0–0777 权限值。`FILE` 带逐文件 SHA-256 和实际载荷；其他种类必须为零载荷、空摘要。种类包含 `DIRECTORY`、`LINK`、`MISSING`、`UNREADABLE`、`EXCLUDED`；因此明确的空目录、缺失目录和读取失败不会混同。链接带 `target`，失败/排除记录可带 `reason`。

范围为 `application`、`sessions`、`settings`、`plugins`、`projects`。`application` 逻辑根可包含不同分类的记录；声明为某个具体范围的根必须只含该范围。Android 导出按数据子根拆分，恢复目标由本机映射决定。未知逻辑根或范围不会自动升级成应用全量恢复。

清单必需包含格式、条目数、载荷字节数、创建时间、应用版本、运行时来源、操作类型、数据格式声明、敏感内容策略、插件依赖信息、逻辑根描述及完整性等级。根描述与实际载荷的根集合必须一致；重复成员、冲突路径、错误尺寸、摘要、截断或尾随字节均拒绝。

完整性等级：

- `QUIESCENT`：已停止应用写任务，并对来源二次核验。
- `EXTERNAL_CHECKED`：应用写任务已停，对仍可能被外部应用修改的源重复读取校验；不宣称冻结了其他应用。
- `BEST_EFFORT`：只读救援，没有应用级静止保证。
- `PARTIAL`：存在缺失、不可读或变化项，具体条目/来源在清单中记录。

## 上限和路径

载荷总量最多 16 GiB，记录最多 100000，逻辑根最多 512。单条元数据最多 16 KiB、尾清单最多 2 MiB、累计记录元数据最多 32 MiB，路径长度最多 2048 字符、层数最多 64、链接深度最多 40。容器/加密流另保留最多 256 MiB 结构开销。计数依据实际读取/写入量。

拒绝绝对写入路径、盘符、反斜杠、NUL、空路径段、`.`、`..` 和换行。根内空相对路径代表逻辑根本身。冲突键采用 NFC 加大小写折叠，以保守拒绝跨文件系统可能碰撞的名称；遇到不支持的命名组合会报错，不悄悄丢掉条目。

解析器将载荷写到 `payload/<序号>` 私有槽位，归档路径不直接作为活跃写入位置。Android 目标映射对白名单角色/名称重新校验：原生设置经白名单导入；插件、可执行脚本、共享 Cordis 补丁进入隔离区；PID、环境标记、桥凭据和旧事务状态保留在输入副本中，不应用到当前设备。

## 历史读取

`LegacyTarReader` 校验 tar 头、PAX/GNU 长名限额、成员类型与外层 gzip CRC/ISIZE，并读到完整 EOF。它拒绝特殊文件、稀疏扩展、路径逃逸、重复成员、拼接 gzip 和尾随数据。旧软/硬链接只能引用包内已验证载荷，不能读取归档声称的宿主路径。

`LegacyBackupImporter` 将识别的 v1–v4 私有转换为 v5，再走同一预检流程。v3/v4 必须核对清单 inventory 与载荷双向一致。旧包缺 scope 只有在识别出明确历史命名时才进入需要确认的导入流程；未知 scope 拒绝。当前历史覆盖仍以合成样本为主，不能据此宣称所有旧用户路径已验证。

## 独立固定向量

`tools/generate-backup-vector.mjs` 使用 Node/OpenSSL 生成 `app/src/test/resources/backups/v5-aesgcm-vector.json`。其中的密码、salt、nonce、明文和密钥全是公开合成测试数据，不用于生产。`PortableBackupCryptoTest.independentOpenSslFixedVectorAndJceInteroperate` 验证 Bouncy Castle 解密和 JCE 派生/解密均得到同样结果。

## 导出完成边界

导出作业完整核验私有产物，在新建的 SAF 或 Downloads 目标写入 tar.gz，并以读回摘要对照实际输出。SAF 目标不支持读回时，界面显示“已写入，目标读回未验证”，留存私有验证产物供重试。成功备份记录只在读回核验通过后更新。

`VerifiedBackupCopy` 根据原有 `verified.json` 复核私有源文件的类型、大小、标记和 SHA-256。`NativeBackupJobs.reexportPortable` 保留已有手动加密副本的密文，对其他可读来源生成新密码保护的载荷，沿用原范围和完整性等级。操作与失败边界见[备份与恢复](backup-and-restore.md)。
