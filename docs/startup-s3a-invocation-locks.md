# S3a：插件调用上下文、写者矩阵与锁互操作

本批基线为 `origin/main=4ce4064b56211dd41600a0f9fa5df6a0a2bc41ff`，通过 fetch 和远端引用双重核对；main 已确认 T11 独立写域。发行身份沿用 build369。本批只交旁路协议适配和隔离竞争夹具，生产仍运行既有 guest 写事务；不启用 Kotlin profile writer、不合并三个脚本、不新增免扫描缓存。最终 descriptor/runtimeId/recipe 由 main 集成树统一生成。

## 已完成的旧成果与本次剩余

- [原方案](startup-kotlin-implementation-plan.md#L148) 已合入 `6e2b1362`；其 S3a 是实施前契约，不是已交付运行实现。
- 旧插件整域迁移提交 `ec3db280bdc45982f020678d47463655c558a58f` 不在当前主线祖先链；对应集成提交 `5ac64738` 在主线中。对两提交的整个插件域执行内容比较为零差异，不能因旧 SHA 不同而重复迁移。
- [旧迁移回执](design/kotlin-rust-rearchitecture-20261112/plugins-kotlin-migration-evidence-20261116.md) 的 11 类保持只读。旧树状态干净、结束声明与日志位置保留；历史测试数不计作本次通过。
- 本次只新增 [InvocationContext](../app/src/main/java/com/deepseekharness/app/plugins/PluginInvocationContext.kt)、[单阶段协议](../app/src/main/java/com/deepseekharness/app/plugins/PluginPreparationProtocol.kt)、[guest 锁探针适配](../app/src/main/java/com/deepseekharness/app/plugins/PluginGuestLockAdapter.kt)、定向测试及本文。S2/T10 的运行工具准备身份与缓存文件不修改。

## 冻结的调用值与执行边界

[InvocationContext](../app/src/main/java/com/deepseekharness/app/plugins/PluginInvocationContext.kt) 冻结 host root/data、各自 dev/inode、按原顺序复制的绑定、guest DSH_HOME/profile、startup/operation/generation/cancel revision、可选旧 taskId、工具 SHA256 和逐阶段预算。集合复制后只读，全部字段进入同一内容指纹。新指纹是本次值协议的身份，不是可写内容不变、环境就绪或维护准入证明。

- guest profile 首批只接受 `web`，因为既有注册模块在 import 时固定它。其它 profile 不冒用本协议。
- 每阶段重新核对当前指纹；同一线程 owner 执行。代次、取消、工具/绑定或来源不再匹配即终止本对象。
- CAS、register、legacy 独立 argv、独立环境、独立预算；环境必须在新 Python 进程 import 之前固定。不能通过中途改 `os.environ` 把一个已导入模块复用于另一 home。
- 标记 attempted 在派发之前。退出未知、丢回执、异常、PARTIAL 或 UNKNOWN 都封闭本次对象；不得补发写事务。阶段完成还须保留旧执行端对该阶段回执及真实退出的校验。
- `NODE_LOCKED_CALLBACK` 在派发前拒绝，不提供同步“持 profile 锁→停当前 Web→等待自己退出”路径。真实 Node 的 HMR/loaded/service/MCP 状态继续由 Node 负责；本批没有第二个 loaded 布尔值。
- 私有管道值协议为 `DSHA_PLUGIN_INVOCATION_V1`：固定字段名、逐值 base64、END 边界、完整内容指纹。它不改变既有 `PLUGIN_RESULT`、注册回执、用户配置、激活 ledger 或 SharedPreferences 键；路径不进入公开诊断。

| 阶段 | 既有执行端及固定预算 | 仍由谁判完成 |
|---|---|---|
| CAS | [RuntimeExecution.collectCasLinks](../app/src/main/java/com/deepseekharness/app/runtime/RuntimeExecution.kt#L295)，900000 ms | [CAS 入口](../app/src/main/assets/materialize-cas-links.py#L84) 与 [唯一 CAS 回执核验](../app/src/main/java/com/deepseekharness/app/core/StartupPipeline.java#L457) |
| register | [GuestPluginScripts.runRegister](../app/src/main/java/com/deepseekharness/app/runtime/GuestPluginScripts.java#L567)，正常90000/首次或未知240000 ms，选择仍按[旧身份判据](../app/src/main/java/com/deepseekharness/app/util/RegisterBudget.java#L19) | 真实退出、未截断输出与 `BUILTIN_REGISTER_OK`；超时后的旧锁清理不等于注册成功 |
| legacy | [Startup.legacy](../app/src/main/java/com/deepseekharness/app/runtime/GuestPluginScripts.java#L263)，3000000 ms | 旧 manager/lifecycle 回执；PARTIAL、取消和未知保留 |

这不是生产准备快路接线；当前启动与插件 UI 的真实调用点仍按旧流程执行。新协议提供可以用真实执行端实现的单阶段端口，竞争夹具实际运行 Kotlin→Python 锁探针；不以纯端口单测冒充生产 writer 已接替。

## 真实写者与锁顺序

| 写者 | 真实范围、锁与顺序 | 本批结论 |
|---|---|---|
| 宿主插件 UI/导入 | [PluginRepository.runMutationMaintenance](../app/src/main/java/com/deepseekharness/app/core/PluginRepository.java#L1617) 先绑定 mutation 票据，经过[维护屏障](../app/src/main/java/com/deepseekharness/app/core/MaintenanceCoordinator.java#L66)与 Web/终端退出，再派 guest | 宿主队列/维护屏障是应用进程协作机制，不能称为 Linux flock 或 Node profile 锁 |
| 正常启动 CAS | [materialize-cas-links.main](../app/src/main/assets/materialize-cas-links.py#L94) 调用共享 operation_lock，再进入扫描和事务 | data flock → profile PID 文件锁 → CAS；仍完整扫描，changed=false 不能证明无日志归档副作用 |
| 内置注册/显式启停 | [register 主入口](../app/src/main/assets/register-builtin-plugins.py#L1560) 外层 operation_lock；[锁体](../app/src/main/assets/register-builtin-plugins.py#L456) | data flock → profile PID 文件锁；锁不可重入。不能在外层持有后再同步调用该主入口 |
| guest manager、旧标记、安装/回滚/安全模式 | [manager](../app/src/main/assets/plugin-manager.py#L642)、[legacy migrate](../app/src/main/assets/plugin-lifecycle.py#L84) 等在明确提交段使用 operation_lock | 同一双锁只覆盖这些合作提交段，不覆盖所有下载/脚本副作用；`locked=True` 只用于明确已有锁的内部调用 |
| 终端 `dsha-plugin` | [CLI 包装](../app/src/main/assets/dsha-plugin.sh#L1) 直接 exec manager；不经过宿主 Kotlin 队列 | 独立写者。普通 npm/pnpm、编辑器及用户脚本还可改文件，不能用宿主队列或 dirty 位视为已收编 |
| Node PluginManager / Settings | 固定 DSH `@deepseek-ai/dsh-atomic-write@0.2.2-rc.1` 的 withFileLock，以 package.json.lock 独占创建 PID 文件；[ConfigEditor 补丁](../app/src/main/assets/config-editor-lock-order-patch.json) 明确 profile lock → HMR | 不取得 Python data flock。Node 可在 Python 仅持 data flock 时取得 profile 锁，夹具必须实测这个未统一的范围。不得从持锁回调进入同步停 Web 维护 |
| 宿主备份恢复/受管修复 | [HostDataTransaction](../app/src/main/java/com/deepseekharness/app/backup/HostDataTransaction.java)、[BackupFileSystem](../app/src/main/java/com/deepseekharness/app/backup/BackupFileSystem.java) 与维护/环境租约 | 事务记录和宿主独占屏障不等于 guest profile 锁；不宣称所有恢复均取得相同双锁 |
| 同 UID 自写程序、外部可写别名、未合作终端写者 | 不受上述合作锁强制约束 | 仍使内容/来源观察失效；不授生产 writer 或跨启动免扫描权 |

Python data lock 是 `flock(LOCK_EX|LOCK_NB)`，锁对象由已打开文件的实际 inode 决定；进程退出由内核释放，文件本身保留。profile lock 是 `O_CREAT|O_EXCL`/Node `wx` 的 `<manifest>.lock`，内容为 `<pid>\n`。仅 `ESRCH`/明确退出才接管；空、半写、不可读、权限不明、PID 仍活着都保留。接管 claim 为 `.takeover-<旧记录SHA256前16位>`，取 claim 后重新核记录与旧 PID，再移除旧锁。

PID 文件协议只支持同一宿主/PID 命名空间。它不是 Web 停止的 PID 出生身份凭证；PID 被活进程复用时保守等待。跨主机/跨 PID namespace 共享文件不在互操作承诺内。不引入 Kotlin Mutex 或 FileChannel.lock。

## 宿主/guest 路径是否为同一锁对象

[UserDataLayout.binds](../app/src/main/java/com/deepseekharness/app/backup/UserDataLayout.java#L73) 在 STABLE 时只把选定数据目录绑定到 `/root/.dsh`，并逐文件覆盖受管脚本；[UserDataBindings](../app/src/main/java/com/deepseekharness/app/runtime/UserDataBindings.kt#L18) 没有把该目录的父目录一并绑定。

| 路径对象 | LEGACY | STABLE（数据绑定到 `/root/.dsh`） |
|---|---|---|
| guest DSH_HOME | host rootfs/root/.dsh | host files/user-data-v5/dsh |
| guest `/root/.dsha-data.lock` | host rootfs/root/.dsha-data.lock | **仍是 host rootfs/root/.dsha-data.lock** |
| guest profile PID lock | host rootfs/root/.dsh/profiles/web/package.json.lock | host files/user-data-v5/dsh/profiles/web/package.json.lock |
| 从 hostData.parent 拼接 data lock | LEGACY 恰好可能一致 | host files/user-data-v5/.dsha-data.lock，**不是上述 guest 对象** |

因此适配器按 guest 路径和实际绑定计划解析锁位置。路径计划只用于找候选，不能替代实际 stat 与竞争。探针在隔离目录中用实际别名复核 dev/inode，再竞争同一锁；STABLE 负控必须证明“profile 相同但 naive data-parent 锁不同”。本机夹具不经 Android proot/proroot，不据此宣称所有 Android 绑定层都已验收；本批未获手机操作授权。

## 运行入口与验证范围

- [Kotlin 协议测试](../app/src/test/java/com/deepseekharness/app/plugins/PluginPreparationProtocolTest.kt)：冻结/指纹失效、顺序/预算/环境、错误 owner、NODE 回调、异常/UNKNOWN/PARTIAL 不重放。
- [Kotlin 竞争入口](../app/src/test/java/com/deepseekharness/app/plugins/PluginLockFixture.kt)：编译真实三份生产 Kotlin 源，通过私有管道送入冻结值，再使用生产锁探针适配器；不是复制锁实现。
- [跨语言竞争测试](../tools/test-plugin-lock-interop.py)：固定 Node 包取自[锁定依赖](../tools/dsh-runtime/package-lock.json)，先核归档 SHA512，再只选择包元数据与真实锁模块。不会 npm install 或执行生命周期脚本。
- [Python 探针](../tools/fixtures/plugin-lock-worker.py) 每进程只 import 一次既有注册模块；[Node 探针](../tools/fixtures/plugin-lock-worker.mjs) 导入归档真实模块。它们只写临时锁文件，不运行插件 writer。探针工具摘要覆盖所有 `plugin-*.py`、注册/CAS 入口与内置清单，等待前后重核；不只比较单个脚本的名字或版本。
- [纯 JVM 复现工具](../tools/run-plugin-invocation-jvm.py) 使用既有 Kotlin 2.2.10 缓存，编译本次真实生产源与测试，并输出带源码/JAR摘要的私有回执；它不执行 Gradle、Robolectric 或 Android 验收。
- [测试清单](../tools/host-tests.manifest.json) 仅新增一条已协调的 support 登记，明确要求先编译 Kotlin fixture 并单独传 classpath；默认 CI 集合保持原样，登记不等于执行。

```sh
# CI/具备固定输入的集成树：仍由 main 生成 descriptor 后运行
# 本机只用 dsha-gradle，并取得 main 的串行 JVM 槽
dsha-gradle :app:testStandardDebugUnitTest --tests '*PluginPreparationProtocolTest'

# Kotlin fixture 的 classpath 须包含本次编译类与 Kotlin 2.2.10 stdlib。
# 不提供 classpath 会失败，不把 Kotlin 分支静默 skip 掉。
python3 tools/test-plugin-lock-interop.py --kotlin-classpath "$S3A_CLASSPATH" --java "$S3A_JAVA" -v
```

验证实际结果、失败修正、证据路径和未验项由本批交付回执补充。此处不预写通过。

## S3b 只读规则对拍的精确入口

只在本批协议/互操作定向验证通过后进入 S3b；它不以宿主/guest 路径计划取代后续 writer 前置条件。

1. 沿用上述冻结 Context 与新的同次只读输入快照，另建 Kotlin 规则模块；生产的 `PluginPreparationProtocol.dispatch` 不双派写入。Oracle 在每份隔离复制的临时树中执行旧实现，候选只生成计划；比较目标树、标记、输出与副作用分类。
2. 旧审阅入口固定为 [Lifecycle.migrate_legacy_reviews](../app/src/main/assets/plugin-lifecycle.py#L84)。先冻结依赖键值、精确 `DSHA_REVIEW_REQUIRED\n` 字节、用户空 disabled/safe-mode、link 源、包身份/兼容性与真实 dependencies.current 对照；计划须包含 queue_activation 和 enable 读回的预期，不能退化为删除标记。
3. 内置注册入口为 [register](../app/src/main/assets/register-builtin-plugins.py#L1370)，纯规则从 [selection_aliases](../app/src/main/assets/register-builtin-plugins.py#L912)、[new_manifest/merge_manifest](../app/src/main/assets/register-builtin-plugins.py#L959) 开始；entity/内容证明、禁用优先与 APK 代次、模板、旧实体 quarantine、共享依赖链接、缺实体 PARTIAL 均独立列计划。不要把 register 整体命名为纯函数。
4. 黄金夹具依次用 [旧审阅测试](../tools/test-plugin-review.py)、[内置默认意图测试](../tools/test-builtin-plugin-default-off.py)、[启动恢复测试](../tools/test-startup-recovery.py) 与 [运行时链接测试](../tools/test-plugin-runtime-links.py)；依赖图保留真实 Python 读取作 oracle。比较只限副本，不在正式 profile 上双跑。
5. CAS 仍留到 S3d：[Transactions.materialize_cas_links](../app/src/main/assets/plugin-transactions.py) 与完整 FD/NOFOLLOW/祖先发布验证、journal/原件保留保持；S3b 不宣称已替代动态依赖、Node loaded 或免扫描。

回退只需撤回本批新增协议/夹具及清单登记，不改用户数据。若 main 后续已经为这些源码生成新的受管身份，回退时由 main 同步重算；不得降级安装正式包或清理旧证据树。
