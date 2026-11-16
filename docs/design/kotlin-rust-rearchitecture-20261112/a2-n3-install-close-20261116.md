# A2 历史保留项 N3：安装与关闭并发修复回执（2026-11-16）

本轮基线为 `origin/main=46f4970369c796c3a7cb9acca6cff58961778840`（build369 发布后的文档收尾；生产代码与发布提交 `82eb958d` 相同）。用户直接指定 `session-7b257182-620a-40a0-b7d9-2029f7130d80` 接手 N3，仅授权本地实现、测试与提交。分支 `fix-a2-n3-install-close`；实现提交 `9b4d70eade38b28adb1d0b53fda389428cd3abc0`，独立回执提交的最终 SHA 记在本树结束声明和交付回报中。

## 旧成果已完成：只核对，不重做

依据 [A2 任务基线](a2-task-baseline-20261113.md#12-非实现者评审-request_changes-修复2026-11-13) 与 [contract Kotlin 迁移回执](contract-kotlin-migration-20261115.md)。旧 `a2-storage`、`a2-workbench`、`migrate-contract-kotlin` 工作树均有结束声明，首次及交付前只读 `git status` 均干净；旧提交、声明、构建失败/成功日志与产物全部保留，未重置、清理或重放旧动作。

| 旧交付 | 主线事实 | 本轮核对 |
|---|---|---|
| A2 存储及 A1 锁序/清理修复 `452239f1` | 同 SHA 已在主线祖先链 | `merge-base --is-ancestor` 为 0 |
| A2 工作台及夹具修复 `09ffb1f4` | 同 SHA 已在主线祖先链 | `merge-base --is-ancestor` 为 0；旧回执仍明确保留 N3/N4 |
| contract 迁移 `86a8c5e3` | rebase 后 `9f1b70ca` | 映射提交在主线；不仅比较旧 SHA |
| contract 文档 `7e9862a3` | rebase 后 `9b9e6e75` | 映射提交在主线 |
| 第二遍空项清理修复 `30ef32e7` | rebase 后 `c8bb2ef3` | 两个提交的整个 contract 生产/测试目录 diff 为空 |

从 `c8bb2ef3` 到本轮基线的 contract 目录差异只有 main 删除不适合 Android 测试编译链的 `ContractJavaBoundaryTest`；本轮不恢复它。既有 dispatch 的 registry/lease 锁序修复、close 的首错/继续清理、布局恢复、Java 互调与 Kotlin 域边界均为已完成成果。历史文档中的旧 build、runtimeId、未验状态不作为当前身份或本轮通过证据。

初始自动工作树仍在旧 `0bf3eec9`，没有在其上续写。新树先从当时的 `origin/main=82eb958d` 建立，在任何源码修改前快进到更新后的 `46f49703`。main 已确认本轮 contract 写域没有其它活跃占用；独立登记并认领 T7，不接管 T3/A8 的工程域或旧树。

## 本次剩余工作及实现

只关闭 N3：慢 factory/贡献回调持 registry 锁，使 close、disable、安全模式不能及时作废安装。生产改动集中在 [ModuleRegistry.kt](../../../app/src/main/java/com/deepseekharness/app/contract/ModuleRegistry.kt)，测试修改 [ModuleRegistryTest.java](../../../app/src/test/java/com/deepseekharness/app/contract/ModuleRegistryTest.java) 与 [ContractLifecycleInteropTest.java](../../../app/src/test/java/com/deepseekharness/app/contract/ContractLifecycleInteropTest.java)。

1. 每个 Entry 使用独占 `InstallAttempt`。锁内只预约及提交；factory、`contributions()`、模块返回 List 的遍历/复制、候选及已发布实例的 `close` 都在锁外。为保留既有第二遍空项的 NPE/清理边界，两遍读取均留在锁外；第二遍结果重新校验并复制为宿主不可变快照后才登记两表和发布 instance/lease。
2. close 在尚无 instance 时也取消预约并记录原因；停用使用 `USER_DISABLED`，安全模式使用 `SAFE_MODE`，null close reason 仍为 `HOST_CLOSE`。进入安全模式在同一个 registry 临界区设置 gate、作废所有 optional 预约并摘取当时的关闭快照，退出不会复活旧预约。`setEnabled(true)` 的 install 调用移到外层锁外。
3. 旧安装线程只按自身 token 清理未发布候选，不能无条件清掉新预约，也不按候选的裸 key 撤销贡献。新代可在旧 factory 仍未返回时使用不同实例完成安装。
4. `IdentityHashMap<ModuleInstance, InstallAttempt>` 从工厂返回后的认领起，持续到候选/已发布实例关闭完成。工厂可复用对象；没有获得归属的迟到别名不能关闭新代正在使用或准备的同一个对象，跨模块别名同样拒绝。身份表不会调用模块自定义 equals/hashCode。
5. 同模块已有预约或已发布实例正在关闭时，重复安装立即复用现有 `MODULE_INSTALL_FAILED` 拒绝；尚未发布不返回成功。已安装的幂等 install 仍返回成功并保留同一 lease。关闭快照持有 `CloseWork` 门禁到强制撤销完成，防止旧清理误撤新代同键贡献/路由。
6. 正常 close 保持“invalidate → 模块 close → 逐贡献撤销 → 诊断”的次序，第一份 Throwable 决定 Error 原对象重抛或 `MODULE_CLOSE_FAILED`；诊断自身的 Throwable 不掩盖首错。候选清理新增的次错不覆盖已在传播的首错；所有退出都归还自身预约/实例归属。安全模式批量关闭遇 Error 仍清完其它已摘实例，最后重抛第一个 Error。

保留 ALWAYS_ON 的禁停用与安全模式存活规则、进程内唯一单调 generation、旧代回调拒绝及布局记录。没有新增机器码、公开方法、持久化 enabled 位或 contractVersion 规则；布局仍用 `dsha_module_layout` 的 `order`/`hidden` 和既有 `v1:` 格式。未修改权限、进程身份、协议、配置/数据格式、SAF、版本或发布输入。

## 复现与实际验证

测试使用 CountDownLatch 明确进入阻塞点，Future 的有界超时只用于证明停止可在 release 之前完成；所有新并发测试都在 finally 放行并等待测试线程归还，没有用 sleep 猜测交错。

| 阶段 | 实际结果 |
|---|---|
| 当前主线 8 类 contract 基线 | Kotlin 2.2.10 / JVM17 + javac17 / JUnit4.13.2：68 运行、0 失败 |
| 旧生产实现 + 首批五个新增反例 | 73 运行、5 失败；close/disable/safeMode 在 factory/贡献门闩放行前全部 TimeoutException，原 68 项通过 |
| 首版锁修复 | 73 运行、0 失败 |
| 新增首错/实例归属反例加载保留的首版 class | 89 运行、5 失败；明确检出一次首错覆盖和四种同实例归属错误 |
| 最终 8 类 contract 回归 | 89 运行、0 失败、0 ignored、0 assumptions；比基线新增 21 项，不将各阶段重复次数相加 |
| 实际 Gradle 第一次 | 仅 `dsha-gradle`、串行/单 worker/1 GiB；`prepareBackupAssets` 因新树缺 dsh-runtime.bin 停止，exit 1；未编译 Kotlin、未执行 JUnit |
| 还原固定输入后的实际 Gradle | 从本机发布树按已提交 descriptor 的 SHA-256 复制并回读核对 6 个缺失输入（489,078,186 字节），不改 descriptor；输入集合 517/205 与 descriptor 只读检查通过。随后 `prepareStandardAssets` 因本机缺 python-support.bin 停止，exit 1；仍未执行 Kotlin/Gradle JUnit，不冒称 Gradle 绿 |

新增 21 项覆盖：慢 factory、setEnabled 外锁、慢 contributions、慢 List.iterator、safeMode enter→exit、旧 finally/新预约、新代同键发布后的迟到清理、并发唯一发布与不同模块代次、alwaysOn、重入关闭、factory/贡献 RuntimeException 和 Error 后重试、首错与清理次错、已发布 close 的同模块门禁及 Error 后释放、安全模式先批量失效/清完所有实例，以及四种对象别名归属反例。旧 68 项继续覆盖 lease 等待与旧代丢弃、布局、路由、关闭异常和 Java 互调。

宿主验证重新编译本树全部 18 个 contract Kotlin 生产源、当前全部 contract Java 测试以及当前 SensitiveData；Android 类路径仅使用 SDK 接口，UI 文案依赖取自保留现场的真实生产 class，且类路径中明确排除所有旧 contract class。旧 class 仅在两项有意的负控中按阶段使用。该纯 JVM 验证不等于 Android/Robolectric、实际 Gradle 单测或完整 CI。

最终串行门禁 13 项全部 exit 0（含 JUnit 计数 runner 编译、实跑及两份 API 读取）；结果如下。精确 argv、源码/测试冻结摘要、退出码与日志见本机 `app/build/a2-n3/final-checks/receipt.json` 及同目录文件。

| 门禁 | 实跑结果 |
|---|---|
| 23 类公开 JVM API | 所有公开成员名称/descriptor 一致；唯一标志变化为 ModuleRegistry.install 的 synchronized 改为显式短临界区 |
| Kotlin 全树格式 | 86 文件、0 待格式化，固定 ktfmt 0.64 |
| Java 全树格式 | 1,775 文件、0 待格式化；adoption 缺失/额外均 0，单 jobs，固定 google-java-format 1.22.0 |
| 核心边界 | 25/25 PASS |
| 运行时输入契约 | 18/18 PASS |
| 受管输入枚举 | 103 安装项 / 205 资产 / 517 启动输入，Gradle 共享映射集合一致 |
| descriptor 只读检查 | PASS，现有 runtimeId 未变化；未执行 --write |
| 源码卫生 / diff | PASS / PASS |
| 专项回执链接 | 1 文档、5 个有效链接、0 警告、0 错误；最终文档修改后另行复验 |


## 独立复核与证据

非实现者 `ed660cc6-9b74-46f5-bb58-65723ab27d19` 只读逐行审阅相对 `46f49703` 的三文件完整 diff，首轮指出候选清理 Error 覆盖首错与同对象别名清理权两项问题；均追加修复并以负控证明测试能检出。复审结论 **APPROVE，无范围内未解决 P0/P1/P2**。其未写实现、未运行 Gradle/测试、未合并或 push；89/0 为本会话执行证据，不伪称独立执行。

本机证据位于本工作树 ignored 的 `app/build/a2-n3/`：`takeover-audit.json`（旧树状态及 SHA 内容映射）、`baseline/`、`negative-control/`、`fix-first-pass/`、`negative-ownership/`、`extended-regressions/`、`restored-fixed-inputs.json`、两份 `gradle-*.log`、`final-checks/`；验证入口保留于 `/tmp/a2-n3-verify-7b257182.py` 及 `/tmp/a2-n3-final-verify-7b257182.py`。旧 A2 与 contract 的日志/产物未覆盖；本轮所有后台任务均在结束声明中登记并收集。

## 边界、未验项与 main 接收事项

- close 能立即取消慢安装的发布资格；不强制中断 factory 或贡献线程。迟到实例由原安装线程在返回后清理。已运行 lease 回调仍按原语义等待完成；其它既有 LayoutStore.save/诊断同步转发路径未全域重构，不承诺任意回调或调用者自行持 monitor 时 close 都不等待。
- 对已经 Closing 的 Entry 再 close/enterSafeMode 保持幂等成功，不等待原线程的 CloseWork；这个成功不是所有并发资源清理已结束的证明。实例归属保护限同一 registry 中已返回的对象，不提供跨 registry 或工厂内部全局资源仲裁。
- N4 SAF 模式切换窗口、动态模块平台、启用意图持久化、ModuleHost scope、contractVersion/describe 校验均未触达。历史文档的 N3 保留语句仍作为当时事实，不追写旧验证结果。
- 未完成实际 Gradle Kotlin/单测、AboutFragment 的 Robolectric、全量 CI、Lint、APK、签名、安装、手机、真机/布局或系统版本矩阵；现有成功的 build369 CI 只属于其发布提交，不用作本分支证据。
- contract 不在当前 launcherSources/launcherTrees，本轮未改任何受管输入或生成器。现有 descriptor 在已还原输入上的只读检查仍为 `1d42f79d5fea6bad5caaa2409822aa5949691c89e6e230b95011ae35db8a5757`；最终 descriptor/runtimeId/recipe 与跨域实际 app 验证仍由 main 的串行集成树统一处理，worker 不生成候选身份。
- 本轮交付本地提交后结束写域，不 push、不合并 main、不发布、不安装、不操作手机、不清理旧工作树。main 如需集成或外部动作，按用户当次授权执行。

## 回退

本轮无数据迁移。对本地实现提交执行 `git revert 9b4d70eade38b28adb1d0b53fda389428cd3abc0`，再按需撤销独立回执提交，即恢复本轮基线的已知 N3 窗口；不要回退已合入的 Kotlin 迁移、旧锁序修复或其它会话提交。回退后复验原 contract 基线；如集成树并入了其它受管改动，其 descriptor 仍由 main 按真实输入统一处理。无需清数据、改偏好、降低权限、安装或删除任何工作树。
