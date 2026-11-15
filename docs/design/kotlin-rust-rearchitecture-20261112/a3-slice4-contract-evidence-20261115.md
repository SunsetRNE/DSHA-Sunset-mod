# A3 slice4：envelope 流接线契约与验证证据

日期：2026-11-15（Asia/Shanghai）。基线：`5ccc8aa3db4c961553a348a8eb35cbfa815be11c`（build366）。本片复用已经合入的 Rust archive/JNI/JVM 流桥；不重做 gzip，不修改 ABI、Rust 执行/权限/进程入口。

## 1. 接线前冻结的契约

依据：[A3 基线 §6](a3-task-baseline-20261113.md#6-a3-实施切片与写域)、[R1 方案 §4.4–4.5](r1-rust-toolchain-plan-20261113.md#44-archive_pack)。本节先于实现冻结；后续证据不得把默认开关、APK 随包或设备加载写成已完成。

| 项目 | 冻结行为 |
|---|---|
| 读写职责 | JVM 继续产生/检查 tar 成员、v5 容器、完整 SHA-256 和旧格式转换；Rust 仅接收有界字节块进行单成员 gzip。v5 magic、版本、清单、原密码认证与恢复预检顺序不变。 |
| 后端选择 | 在 gzip 消费源或写目标之前固定。开关关闭、库不可用/ABI 不符、范围未知或超过 ABI 能力均选 JVM。已选 Rust 的会话创建/构造/读写/finish 失败直接上抛，不在原源或目标换后端重放。切换开关只影响新流。 |
| pack 预算 | 使用 payload 的已核验 stat 长度，加全部 tar header/padding、README、可选诊断成员、两块结束符计算完整明文长度。恰好 `MAX_TOTAL_BYTES` 可进 Rust，超过则 JVM；不得只看 payload 长度、截断数据或故意隐藏 tar 开销。全部容器摘要仍流式由 JVM 计算，不拼接单段摘要。 |
| unpack 预算 | envelope 传入同一次 `fs.stat`/`fs.read(expected)` 的压缩文件长度；仅已知且不超过 `MAX_TOTAL_BYTES` 的输入可进 Rust。未知长度、超上限及普通旧 tar 入口保留原 JVM 路径。gzip 的明文累计限额和 tar 的成员限额仍分别生效。 |
| 严格读取 | 成功必须遍历 tar 结束块并读到 gzip 真 EOF，完整检查头/可选字段/FHCRC、CRC32、ISIZE、截断、多成员和尾随字节。保留直接 `new StrictGzipInputStream` 的 JVM 参考实现。Rust 构造可能已消费 body/尾并失败，不能把构造失败当作尚未读取。 |
| FD/流所有权 | 文件仍由既有 `BackupFileSystem` / Android PFD 自动关闭流打开、核验和关闭。Rust archive 不接 fd/path、不 open/close。外层 gzip 工厂借用输入流，词法资源 owner 在构造失败/成功/中途失败均关闭底层；输出 gzip 只借用目标，envelope 在 gzip 创建成功后负责最终关闭目标。参数/取消预检或 gzip 构造失败时目标仍属调用方。不会关闭并重开同一 FD 续写。 |
| 取消 | 沿用 `BackupControl.check()` / 线程中断；创建前、底层源读取前后、每个复制块和最终完成前检查。协作式取消不证明没有输出。失败后封住目标写门，再关闭 gzip/释放会话，关闭清理不能继续向原目标输出尾部。 |
| 部分输出/失败 | 源变化、摘要不符、gzip 错误、取消、目标写入/flush/close 失败均不返回成功。目标可能已有前缀甚至完整字节；仅正常返回才可交上层独立回读核验后发布。源或目标失败不自动删除用户文件、不自动 truncate/重写或换后端。私有残留由原有作业清理/事务 owner 处理。 |
| 结果优先级 | 保留最初 IOException；清理失败以 suppressed 附带，不覆盖为后来的 STREAM_CLOSED/STALE_HANDLE。`unpack OUTPUT_BOUND` 是 ARCHIVE_LIMIT 硬失败；不能按 pack 缓冲不足重试。 |
| 旧格式路由 | 未识别首成员时仍返回 false、不创建 envelope payload；历史导入随后经原 JVM 路径完整检查/转换。passwordProtected 只作 UI 路由，未知长度保持 JVM，不提升为完整核验。 |
| 读取产物 | unwrap 的输出只在私有编号槽位。失败时允许有部分 payload，但不能进恢复预检/提交；gzip 通过仍不代表 v5 合法，后续 `BackupArchive.read` / AEAD / 整份摘要继续作为权威核验。 |

实现范围：[PortableBackupEnvelope](../../../app/src/main/java/com/deepseekharness/app/backup/PortableBackupEnvelope.java)、[StrictGzipInputStream](../../../app/src/main/java/com/deepseekharness/app/backup/StrictGzipInputStream.java)、[RustArchiveCodec](../../../app/src/main/java/com/deepseekharness/app/util/RustArchiveCodec.java)，以及经 main 协调的 [LegacyTarReader](../../../app/src/main/java/com/deepseekharness/app/backup/LegacyTarReader.java) 窄长度适配。现有 Java 模块沿用 Java；本片不做逐文件 Kotlin 语言迁移。

## 2. 验证口径

- 先跑现有 envelope/tar/codec 相关测试建立基线；注入按当前 Rust 源码构建的主机库，`dsha.rust.required=true`，开启 `-Xcheck:jni`。
- 走真实 `PortableBackupEnvelope.writeUnencrypted/write → LegacyTarReader → unwrapIfPresent → BackupArchive.read`，比较两后端的 payload 全字节、整份 SHA-256、清单和 scope。gzip 压缩字节不要求相同。
- 使用真实 native 会话耗尽负控证明 envelope 确实调用 pack/unpack；失败必须保留 `SESSION_LIMIT`，不可悄悄 JVM 成功。正常输出固定 Rust 头及 JVM 严格读取作为补充证据。
- 损坏/截断/尾随/多成员、坏 v5 摘要、旧 v1–v4 路由、成员/诊断/ABI 限额、取消前/中/结束、源/目标失败和 close 清理均覆盖；特别确认部分写失败后没有追加、原目标重开或第二次源读取。
- Rust crate、JNI 符号及 gzip 单测不是本片业务出口；Android `.so` 加载、APK 随包、16 KB 真机、SAF Provider 真机与正式默认启用单独记账。大限额采用纯 long 边界与故障源负控，不把它写成实际读写 16 GiB。

## 3. 开关与发布账本

| 项目 | 本片状态 |
|---|---|
| 生产 gzip 调用点 | 已接：写侧经 `RustArchiveCodec.packStream`；envelope 读侧经窄长度适配和 `StrictGzipInputStream.open`；直接 JVM 构造器与旧入口保留 |
| `RustBackends` 默认值 | 保持 false；没有新持久化键或 UI 授权，切换只影响新流 |
| 注入主机库真实调用 | 已在同源码 host `.so` + JVM 完成实际链差分和会话耗尽负控；证据见 §4 |
| `.so` 正式随包/CI 打包窗口 | 本片未改打包/CI，不据 host 结果宣称随包；正式启用仍单独安排 |
| Android / 16 KB 真机 | 未执行；未操作设备。host ELF 的静态页对齐不等于 Android 装载或 16 KB 真机 |
| `runtimeId` | backup 字节变化须 main 在串行合并树重算；本片不改 descriptor/pins |
| push / 合入 main / Release | 本片未执行，由 main 按用户当次授权处理 |

## 4. 实测证据

环境：aarch64 Linux 容器，JDK `17.0.21+9`；现有锁定 Rust `1.100.0`（`0ef1bc386059596dada8fb65b50425aab2ea637a`）与 cargo `1.100.0`。本片没有更改 Rust 源、vendor、ABI、依赖或产物锁。

| 项目 | 命令/范围 | 实际结果 |
|---|---|---|
| 基线 | JDK17 `javac` + JUnitCore：既有 envelope、LegacyTarReader、BackupArchive、RustArchiveCodec 四套 | **30 tests OK**（接线前） |
| host 库 | `PATH=/opt/jdk17/bin:/root/.cargo/bin:$PATH python3 tools/rust/build.py --host --jobs 1 --out app/build/rust-host --receipt app/build/a3-slice4/native-build.json` | **exit 0**；aarch64-unknown-linux-gnu，348736 B，SHA-256 `04f07e7a2adb64e034ba66dfce2467ba369af11110f876b10b6a5aef59156bed`；11 导出符号。未重跑字节复现/跨 runner pin，源码未变 |
| 最终实际链 + archive 差分 | 本树源码编译后，`-Xcheck:jni -Ddsha.rust.hostLib=<本树>/app/build/rust-host/libdsha_rs.so -Ddsha.rust.required=true`，上述四套再加 [RustArchiveDifferentialTest](../../../app/src/test/java/com/deepseekharness/app/util/RustArchiveDifferentialTest.java) | **53 tests OK**；required 原生加载成功，0 failure/0 error/0 skipped；本轮不是 Gradle 任务级结果 |
| 旧格式/自动备份回归 | 本树完整 Java 编译产物，JUnitCore 执行 [LegacyBackupImporterTest](../../../app/src/test/java/com/deepseekharness/app/backup/LegacyBackupImporterTest.java)、[AutomaticBackupPrunerTest](../../../app/src/test/java/com/deepseekharness/app/backup/AutomaticBackupPrunerTest.java)、[AutomaticBackupPolicyTest](../../../app/src/test/java/com/deepseekharness/app/util/AutomaticBackupPolicyTest.java) | **10 tests OK**；含合成无清单/v1–v4 转 v5、原 scope 及自动计划/保留回归；不冒充真实用户升级数据 |
| 既有 R1–R3 探针 | `python3 tools/rust/host-jvm-differential.py --lib app/build/rust-host/libdsha_rs.so --report app/build/a3-slice4/r1-r3-host-differential.json` | **66/66 checks PASS**；PlanView corpus333/native349；archive19 checks/native31；库 SHA 与上行一致 |
| Java 主机编译 | 仓库外 `/tmp/a6-ci-javac.py <本树>`：生成主机用 R/BuildConfig/文案，编译本树全部 main Java 和测试 Java；只有既有 ShellQuote Kotlin 随其编译 | **MAIN_COMPILE_RC=0；TEST_COMPILE_RC=0，767 个测试 Java 源**。末次新增测试再由上方定向 javac 验证；不是完整 Kotlin/Gradle/AAPT2/资源打包证明 |
| 架构/源码 | `check-rust-boundaries.py`、`test-core-boundaries.py`、`check-source-hygiene.py` | **PASS**；Rust 7源/11符号/16依赖/4构建输入；core **25 tests OK**；卫生通过 |
| Kotlin 格式 | `python3 tools/format-kotlin-tree.py --check --java /opt/jdk17/bin/java --jobs 1` | **PASS**：2 个既有文件，0 need formatting；本片无 Kotlin 改动 |
| Java 全树格式 | `python3 tools/format-java-tree.py --check --jobs 1 --java /opt/jdk17/bin/java --offline` | **PASS**：1827 个文件，0 need formatting；清单无缺失/多余，无登记变更 |
| 工具链/vendor | `check-rust-toolchain.py`、`check-rust-vendor.py` | **PASS**：锁定 1.100.0 / 16 crates / 7,384,177 B |
| 链接/差异卫生 | `check-document-links.py` 默认范围及本专用文档；`git diff --check` | **PASS**：默认56文档/1315链接、本文10链接，均0 error；差异无空白错误 |

实际链夹具采用固定种子的 `1,048,597 B` 高熵正文，通过真实 `BackupArchive.Writer` 生成 v5，再走四组 JVM/Rust writer×reader 组合。比对 payload 全字节、SHA-256、完整清单、`requestedScope=sessions`、`formatVersion=5`；旧加密原件原样包裹并用原密码认证，错误密码继续失败。

证明进入 native 的核心负控是同时占满四个真实 archive 会话：此时开启开关调用 envelope 写/读分别得到 `SESSION_LIMIT`，写端零输出、构造失败仍属调用方关闭，读端 source 只打开/关闭各一次、未创建 payload；同样满会话时关闭开关，envelope JVM 路径与旧 `LegacyTarReader.read/readTrustedAssets` 正常成功。该负控不以 gzip 头或“开关为 on”冒充调用证据。

失败矩阵覆盖 CRC/ISIZE、flags、截断前缀、尾随字节、多成员、gzip 合法但 v5 尾摘要非法、超成员/README/诊断预算、未知/超 ABI 长度、完整 tar 恰好 MAX/MAX+padding/long 溢出。带假定大 stat 的故障源在四个 native 会话占满时仍先选 JVM，随后只读一次并报 `SOURCE_READ_REACHED`，证明 payload=MAX 时没有漏算 tar overhead；该用例不会分配或写入 16 GiB。

写入失败分别覆盖 update 的部分写、最终 finish 的部分写、源 close、复制 SHA 变化，以及同时出现目标 close 异常：首个原因保持，close 异常保留为 suppressed，真实目标失败后新增 codec 写调用为 0，源无第二次打开；失败后可再次占满四会话证明原生资源已释放。取消覆盖预取消零输出/零源打开、复制进度中取消、gzip 构造喂入期间取消、target.close 后取消、source.close 后取消。close 仍必须执行；封写门只保证不再交付新的 codec 字节，底层 owner 的 close 可能刷出此前已接受的缓存，不能据此宣称完全没有副作用。

压缩/解压超 ABI 时留 JVM 是选择策略，不放宽既有严格明文累计上限。tar 超过 MAX 时，JVM 严格读取器仍会拒绝；完整 tar 未超限也不能保证高熵压缩结果未超压缩输入限额。原作业的“私有 envelope 落盘 → 完整 unwrap → payload 摘要回读 → 对外导出”顺序保持，任何长度预测不能替代这次回读。

### 4.1 首轮失败与修正记录

1. 首轮 host 构建 `exit 1`：会话 PATH 未含已有 rustup/JDK，日志 `RUST_TOOLCHAIN_MISSING`。确认本机已有锁定工具链后只补 PATH，新任务构建 `exit 0`；没有安装新依赖或对缺失条件反复重跑。
2. 初次直接 javac 夹具漏带 Gson/BouncyCastle；纳入锁定 `gson:2.13.1`、`bcprov-jdk15to18:1.85.2` 后基线 30 项通过。首次把 LegacyBackupImporter 纳入纯 Java sourcepath 时引出 Android 类型闭包，故改为单独编译本树真实 Java 全树，再执行旧格式回归；没有用桩替换本片生产类。
3. 仓库外 Python runner 直接 argv classpath 触发本机已知末尾依赖不可见（BouncyCastle）；改为 `@argfile` 后编译通过。Kotlin 格式首次缺 JDK 查找路径，明确传 `--java /opt/jdk17/bin/java` 后通过。这些准备失败未记作业务测试通过。
4. 首轮新增矩阵 **53 tests / 1 failure** 是测试时机假设错误：含明文的小 tar 成员可能先交付明文，后续 read 才报 CRC 错误，不能断言 payload 一定不存在。修正为“空 gzip 成员坏 CRC”，并先显式断言 `StrictGzipInputStream.open` 的构造立即失败，再走实际 unwrap 验证 owner 关闭/无重开；大载荷读期 CRC 拒绝与私有残留语义仍保留。修正后新任务 **53/53 OK**。生产实现未因该失败放宽校验。

### 4.2 未执行及后续集成要求

- 未在本机运行 Gradle、AAPT2、Android 链接、APK 打包/安装、真机加载、16 KB 真机、SAF Provider 真机或覆盖升级。已知本机 x86 AAPT2 不可在 aarch64 执行，不据同一限制重跑 Gradle；完整 CI/full/lint/APK 由 main 集成窗口安排。
- 现有 Gradle 注入路径可直接复用：`dsha-gradle :app:testStandardDebugUnitTest --tests '*PortableBackupEnvelopeTest' --tests '*LegacyTarReaderTest' --tests '*BackupArchiveTest' --tests '*RustArchiveCodecTest' --tests '*RustArchiveDifferentialTest' -Pdsha.rust.hostLib=<host .so> -Pdsha.rust.required=true`。本片未改任何构建/CI 文件。
- 本地回执和合成产物在本树 `app/build/a3-slice4/`，仓库外验证入口 `/tmp/a3-slice4-verify.py`（使用 JDK17 `@argfile`；仅验证夹具，不入生产）；不能依赖临时目录永久保留。上述提交内测试与运行参数才是可复跑入口。
- 独立只读审查已对 7 个 Java 文件逐行给出 **APPROVE / P1=0 / P2=0**，另复审了构造期夹具修正；该审查没有运行测试，测试结论来自上表实际执行。最终证据文档亦已独立只读复核，**APPROVE / P1=0 / P2=0**。
