# A2 / N4：SAF 首次迁移失败的模式一致性（2026-11-16）

本轮由用户指定 `session-8ad28be2-ced7-484a-96bf-a387a82312e8` 接手，仅做本地实现、定向测试、提交与回执。基线为重新 fetch 后的 `origin/main=46f4970369c796c3a7cb9acca6cff58961778840`；分支 `fix-a2-n4-ledger`。当前发布仍由 main 的 build369 版本线管理，本任务不生成发行身份。

## 旧成果与本轮剩余范围

已核对 [A2 基线及 N4 历史窗口](a2-task-baseline-20261113.md)、[SAF Kotlin 迁移回执](saf-kotlin-migration-evidence-20261115.md)、[Kotlin 工具链](../../kotlin-toolchain.md) 与 [SAF 安全边界](../../security-model.md#跨应用文件访问saf)。旧结束工作树只读保留，未重置、清理或复用。

| 旧交付 | 与本轮基线的关系 | 结论 |
|---|---|---|
| `452239f114054cc891f0b79fa5ed8a7e5353c9fd`，A2 授权簿 | `merge-base --is-ancestor` exit0 | 旧授权簿已合入，不重做 |
| `09ffb1f4486595ef5e59124caf2c735b69433117`，工作台及测试桩收尾 | 同上 exit0 | 工作台已合入，不重写 |
| `c06d8a8ba4432b64374c0871846ff97b9929d288`，整域 Kotlin 实现 | 同上 exit0；三个核心 Kotlin blob 与基线完全相同 | 继续 Kotlin，不恢复 Java |
| `5591633196ffc0a4161b7e8d8c0f8b164823d045` / `1a7a51778c1cd2e2551dafda84ab8406eb97fc3d`，迁移回执及注记 | 两者均 exit0 | 历史验证不充作本轮通过 |

旧 `feat/a2-storage`、`feat/a2-workbench`、`migrate-saf-kotlin` 三树均有结束声明，开工 `git status --short` 为空，声明无后台任务；旧日志和产物保留。本次先向 main 确认 SAF 写域并认领 T9；与 T3 工程、T7 contract 生命周期无文件重叠。

剩余项仅为 N4：首次迁移保存失败后，原有 `ledgerReady()` 在锁内返回模式、调用方在锁外选择 legacy 分支，后续操作可能使用已经退休的旧授权/偏好。工作台旧有的局部失败关闭不能代替桥与全局路径的修复。

## 确定性证据与实现

[新增回归](../../../app/src/test/java/com/deepseekharness/app/saf/SafMigrationRaceTest.kt) 使用两类控制点：同步 prefs 提交回调重入，精确安排“第一次保存返回失败前，另一授权操作已完成迁移”；`CountDownLatch` 阻塞名称 Provider，确保另一线程完成 list/lookup/add/remove/setWritable 后才返回。断言业务结果和持久化内容，不用 sleep 争抢调度。

原生产实现在最初 11 项反例上 **11 运行 / 11 失败**，包括：

- lookup/list 重新读取 legacy 的可写偏好，忽略新账本已关闭的偏好；真实桥 `safTree` 因而错误放行写请求。
- add 报成功，但新账本没有该授权；真实工作台 `prepare` 返回 `NO_GRANT`。
- remove 修改旧记录并释放系统位，却在新账本留下 ACTIVE；setWritable 写入已退休命名空间。
- 慢 Provider 返回后，把查询前系统位与旧偏好拼接，并回写旧缓存。
- `commit(false)` 已更新内存的 schema/ACTIVE 被当作已提交；take 后系统券不可读时按请求位推定成功。

[SafGrants](../../../app/src/main/java/com/deepseekharness/app/saf/SafGrants.kt) 在同一授权锁内选择模式并完成操作或取不可变快照。名称 Provider 仍在锁外查询；回调按原账本 epoch 或进程内 legacy 代次核对，模式切换/撤销/重授后丢弃旧缓存结果，列表整体重新取一份包含当前偏好和系统位的快照。桥 lookup 和工作台 capabilitySnapshot 均绑定同一临界区内的账本、代次与系统事实。

[SafLedgerStore](../../../app/src/main/java/com/deepseekharness/app/saf/SafLedgerStore.kt) 跨适配器实例隔离同一 SharedPreferences 的在途/失败提交内存值，保留提交前已确认快照，成功重试后才恢复读取当前 map。同步监听器重入的提交序号防止旧失败覆盖较新的成功。该记录仅在内存中，不新增偏好键或数据字段。

未知系统券不再退回 take 请求位：返回 `PERMISSION_STATE_UNAVAILABLE`，保留 PENDING 的 before/intent，交后续 cleanup 按实际系统位释放增量。未完成清理的 PENDING 不允许新 take 或 legacy 移除覆盖其归属证据；legacy list/lookup 同样隐藏未清理的 PENDING，不把在途新增位当作 ACTIVE。[SafGrantLedger](../../../app/src/main/java/com/deepseekharness/app/saf/SafGrantLedger.kt) 原有状态转换和编码保持不变；ACTIVE、Previous 恢复与 RELEASING 重试继续由原账本规则处理。

## 本轮验证记录

最终本地结果：**219 运行 / 0 失败 / 0 ignored / 0 assumptions**，19 项新增反例在原基线字节码上全部失败。下表各阶段均保留原始失败与成功记录，不把中间结果累加为最终测试数。最终整批作业 `bash-8db43f5979ef4c87` exit0 已收集，JVM 槽已交还 main，无仍在运行的测试或格式作业。

| 阶段 | 实际结果 | 证据 |
|---|---|---|
| `dsha-gradle --offline --no-daemon --max-workers=1 :app:testStandardDebugUnitTest`，定向五类 SAF | exit1 / 17s，`prepareBackupAssets` 缺固定 `dsh-runtime.bin`，未到编译/JUnit | `/tmp/saf-n4-8ad28be2/baseline-gradle.log` |
| 原基线完整 Java/Kotlin 混编及 SAF + BridgePathPolicy | exit0，**200/0** | 同目录 `baseline/`，receipt 记录 1009 Java（含生成符号）+77 Kotlin；17 Java 测试+1 Kotlin 测试 |
| 最初 11 项确定性反例，使用原基线生产字节码 | 编译 exit0，JUnit exit1，**11/11 失败** | `red/` 与 `red-run.log` |
| 第一修复增量编译 | 首次临时编译未声明 friend path，对既有 internal API 拒绝，exit1，未到 JUnit | `fixed-r1/`，没有为此放宽源码可见性 |
| 修正临时编译边界后的第一修复回归 | exit0，**211/0** | `fixed-r2/` |
| 扩展后的全部 19 反例，仍用原基线字节码 | 编译 exit0，JUnit exit1，**19/19 失败** | `red-final/`，包含失败写偏好、在途提交重入、账本/legacy PENDING 保护、重授后迟到元数据 |
| 最终整批真实源码混编及定向回归 | 全部编译 exit0，**219/0/0/0** | `final/receipt.json`、`final/junit.log`；996 个仓库 Java（含既有 AIDL stub）+77 Kotlin，另 14 个临时生成 Java/计数 runner；17 Java+2 Kotlin 测试源，17 个测试类 |
| Kotlin 格式 | 3 个改动文件格式化；全树 **87 文件 / 0 待格式化** | `final-batch/kotlin-tree-check.log`，`--check --offline --jobs 1` |
| Java 格式 | 全树 **1775 文件 / 0 待格式化** | `final-batch/java-tree-check.log`，同样 jobs1，无 Java 生产改动 |
| 源码卫生 / 架构边界 / host 清单 / 回执链接 / diff | PASS；边界 **25/25**，清单 **264**，链接 **1 文档 / 8 有效 / 0 错误** | `final-batch/` 分项日志及完整 argv/exit 的 receipt |
| JVM 接口只读对照 | **568 个非 private、非 access$ 成员**，无未解释的丢失或修改 | `api-comparison-final.json`；原 77 类中 76 类同名，私有 legacy helper 的 inline 比较器随 helper 重命名并显式映射，构造名变化、compare descriptor 保持；增加 ListSnapshot/CommitState 两个私有实现类型 |

全部本机证据根为 `/tmp/saf-n4-8ad28be2/`，保留 baseline、red、编译器 internal 失败及最终结果；最初未映射私有比较器的 API 名称差异也保留在 `api-comparison.json`，不声称所有编译器类型的 FQCN 原样不变。

本机直接编译固定使用缓存 Kotlin 2.2.10 / JDK17，Robolectric SDK34、离线依赖、Conscrypt OFF 与 `--add-opens=java.base/java.io=ALL-UNNAMED`。临时 R/BuildConfig 编译符号由真实 XML/当前身份生成，文案走真实生成器，计数 runner 只采集 JUnit 结果；SAF、桥、工作台和域外 Java 消费者均编译真实源码。它不是 AAPT、APK、完整 Gradle 或默认 CI 运行。各 JVM 命令串行使用 main 分配的共享槽位。

## 交付边界与回退

实现/测试提交：`6ce3dbf90e5fd390e76e66f01ecce3442953720e`（`fix(saf): 绑定迁移模式快照并隔离失败提交`），直接父提交为 `46f4970369c796c3a7cb9acca6cff58961778840`。旧 SHA 没有被重写：`452239f1 / c06d8a8b / 55916331 / 1a7a5177 / 09ffb1f4 → 已包含于 46f49703 → 本次增量 6ce3dbf9`。本回执另行文档提交，不修改已冻结实现。

执行期间 main 按其独立授权集成了 T7 的 `4ce4064b`；只读核对该提交的 SAF blob 与本轮基线相同。本树按 main 指示保留基线 46，不在活跃现场 reset/rebase，不把 N3 的集成或验证结果归入 N4。

两次只读审查子代理均在 product-error 终止，没有审查结论，不宣称 APPROVE；冻结提交后的独立全 diff 审查、统一身份及集成验证由 main 另行安排。本地实现和定向回归完成，不代表 A2 整轮或本版本设备验收完成。

保留 `saf_grants` / `saf_capability_ledger` 命名空间、schema=1、旧键、桥协议3、权限默认值和 App/Android 用户身份边界。没有 Manifest、ConfigStore、文件工作台 UI、传输格式或运行时停止算法改动。原桥文件发布限制及同 UID 边界不是本任务的修复范围。

未运行完整固定输入 Gradle 全量、Lint、AAPT/APK、外部 Provider/设备矩阵、LayoutAudit、安装或手机操作；本地定向不能代替这些出口。受管启动成员 **517→517**，只改变 `saf/SafGrants.kt`、`saf/SafLedgerStore.kt` 两个内容摘要；`runtime-input-impact-final.json` 记录最终字节哈希，既有 descriptor 完全未修改。最终 descriptor/runtimeId/recipe 由 main 在集成树统一生成，本分支不改受管证明或版本号。

本地代码回退命令为 `git revert 6ce3dbf90e5fd390e76e66f01ecce3442953720e`；集成时若发生 cherry-pick，由 main 对应实际集成 SHA 回退并重算受管身份。保留 SAF Kotlin 实现，不需要清数据、删除旧授权、改 schema 或恢复 Java。push、合并 main、发布、安装、手机操作及清理旧树均未执行。
