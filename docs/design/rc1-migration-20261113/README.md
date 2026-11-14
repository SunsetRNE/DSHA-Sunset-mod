# RC1 迁移问题吸收：内容副本、历史复用与本次核验

> 日期：2026-11-13（UTC）；核对基线：`d0bc1b0b1e5404539c24da7b8becc3484b5f5f08`，开工时的 `origin/main` / build363。
> 状态：**设计评估，待 Lead 评审；未实施生产修复**。本记录与[回复草稿](reply-20261113.md)只回答外部材料 §5 的两问，并列出本仓可证实的关联缺口。不是外部分支合并评审，也不是发布、设备事故或真机验收结论。

## 1. 决定与保证边界

1. **Q1：同意把产品保证收敛为内容副本保护。** 对明确列入迁移清单的文件，保护的是迁移前已读取、写入独立副本并读回核验的字节；不承诺跨进程仍是绝对同一文件对象。`dev/ino`、mtime、ctime、链接映射可作为变化检测线索，不足以授予绝对身份；后者明确为 `unknown`。本轮不引入 statx/btime、跨进程持 fd 或新迁移框架。
2. **Q2：历史完成可以复用，必须显式标明历史作用域。** Java 和 Python 两条快路径一起收敛，消费方一起迁移。缓存命中不直接合成本次 `protectionComplete=true` / `sourcePreserved=true` / `settingsImported=true`。当前内容读回、当前输入/代次检查、历史导入账本各自有证据来源。
3. **未知不等于失败，更不等于尚未执行。** 解析异常、超时、结果丢失、写入中断与确定未派发分别处理；失败/未知不能自动再做一次迁移或配置写入。允许只读核对既有状态，不能借缓存失效重建 generation、重导已完成设置或恢复历史值。
4. 本仓已经存在结果语义与错误分支的正确性缺口，列在 §6。**未发现能证明真实用户数据损坏的证据**；外部 mock 日志不是真机 inode 复用事故，本轮也未运行故障注入。

“内容副本保护”有明确范围：现有清单包括两种 settings 输入、profile 的五类配置文件、旧 preset 树和 sessions；不等于整个用户目录、外置工作区或所有插件数据的全量备份，范围由 [inventory()](../../../app/src/main/assets/rc1-migration.py#L167-L183) 决定。V4 会话允许继续写入，不能承诺活动内容永远等于迁移前快照。私有存储与 NOFOLLOW 也不构成对同 UID 任意代码的内核隔离，见[安全模型](../../security-model.md#L9-L18)。

## 2. 材料身份与核对方式

外部材料题名为《给 sunset：rc1 迁移问题的代码原文与具体卡点》，日期 2026-11-13，共 231 行；接收材料实算 SHA-256 为 `16405d10078d77e09fbeefb127b34d20a83b9d6e06d44313ffc4a6a206f3989b`。保留必要摘要与代码事实，不将整篇私信或附件私有路径收入仓库。

| 对象 | 身份与证据性质 |
|---|---|
| 外部已提交候选 | [133563825as-ai/DSHA-god](https://github.com/133563825as-ai/DSHA-god/tree/8b42e20bea9a0049761fad7d0cd9948e48838e4a)，分支 `feat/rebirth-identity`，提交 `8b42e20bea9a0049761fad7d0cd9948e48838e4a`；该身份来自来件，未另行拉取整分支 |
| 外部暂停 Python 草稿 | 来件声明 SHA-256 `592771559dfaba932e853b1406158692010fd14aa7481b1222b3a79109494e19`；未提交、不属于上述提交，只有节选可核对，未取得完整草稿重新计算摘要 |
| 外部暂停测试草稿 | 来件声明 SHA-256 `be7f6905be5d01ebe3c558ece83bca2d8ab0f5f40e9143474907114123591550`；保留日志为 46 项、45 过/1 败，**不当作本仓测试结果** |
| 本仓发布 Python | [rc1-migration.py](../../../app/src/main/assets/rc1-migration.py)，实算 SHA-256 `7fc60c22e6ff2a5c40870e93b9b91d1da157488b0f7a0275635b6cc5b9f68047` |
| 本仓 Python 测试 | [test-rc1-migration.py](../../../tools/test-rc1-migration.py)，实算 SHA-256 `f2442a0232046323c81c69896f83a7f46a9bac42b28bd65002f7e7c828cdb806`；本轮只读测试内容，未运行该套件 |
| Java 对照 | 来件 [Rc1MigrationCache.output()](https://github.com/133563825as-ai/DSHA-god/blob/8b42e20bea9a0049761fad7d0cd9948e48838e4a/app/src/main/java/com/deepseekharness/app/runtime/Rc1MigrationCache.java#L70-L82) 与 [GuestPluginScripts.migration()](https://github.com/133563825as-ai/DSHA-god/blob/8b42e20bea9a0049761fad7d0cd9948e48838e4a/app/src/main/java/com/deepseekharness/app/runtime/GuestPluginScripts.java#L248-L280) 的节选，与本仓下文所引实现相同；不能由此推断全部分支相同 |

本轮结论来自静态控制流、字段写者/读者核对与既有测试清单。下文行号固定对应本节基线；后续变更应按函数重新定位。

## 3. 卡点一：本仓 Python 实际证明了什么

### 3.1 身份、字节与映射逐项比较

| 环节 | 本仓实现 | 与外部草稿的差异/含义 |
|---|---|---|
| 输入小指纹 | [quick():115–129](../../../app/src/main/assets/rc1-migration.py#L115-L129) 哈希 settings、imported、restore 标记；数据根记录 path/device/inode | 不遍历历史会话或 snapshot；数据根元数据用于定位/失效，不是绝对出生身份 |
| 完整 inventory | [inventory():174–182](../../../app/src/main/assets/rc1-migration.py#L174-L182) 的 `identity=[str(dev),str(ino),size,mtime_ns,ctime_ns]`；读取前后用路径 stat 对比，连同 path/mapping/links/sha/size 算 `inputDigest` | 不是草稿的七项 `source_stat`；没有 mode/nlink，没有草稿所述统一同 fd 稳定读取 |
| snapshot 建立 | [snapshot_row():146–164](../../../app/src/main/assets/rc1-migration.py#L146-L164)：打开源后 fstat 验证普通文件与 dev/ino；写独立编号文件、fsync、对目标重新 digest；路径前后比较 dev/ino/size/mtime | 持久 `sourceIdentity` **只有字符串 dev/ino 两项**；另存 size、sha256、mapping、links、snapshot。没有持久七项 source_stat；没有源 fd 读后 fstat 与映射复核的完整组合 |
| 失败副本复用 | [reusable_rows():186–196](../../../app/src/main/assets/rc1-migration.py#L186-L196)、[prepare():265–290](../../../app/src/main/assets/rc1-migration.py#L265-L290) 要求相同完整 inventory 指纹，再查副本类型、size、sha 与来源 dev/ino/links | 这是未完成保护的副本复用；与 Java 的历史 committed 缓存不同，不应混为一个“缓存” |
| 首次/非 cached finalize | [finalize():380–396](../../../app/src/main/assets/rc1-migration.py#L380-L396) 先读回每个 snapshot 的 SHA；一般来源只比较 dev/ino 与当前 SHA | 并不调用来件的 `verify_source` / `evidence_stat` / `stable_read`；解出的 `links` 和记录的 `mapping` **没有作等值比较**，sourceIdentity 缺失时该项比较还会跳过 |
| 可变来源例外 | [finalize():388–391](../../../app/src/main/assets/rc1-migration.py#L388-L391) 的 profile 与 V4 session 在 snapshot 核验后跳过来源 identity/digest 比较 | `sourcePreserved` 现有含义已经混合“副本字节保存”与“源观察连续”，不能解释为所有原文件对象仍在 |

外部草稿 §2.1 先校验映射与链接、同 fd 稳定读，再对七项 stat 比较；仅 direct rename 分支执行 `expected[4] = observed[4]` 放宽 ctime。**本仓没有该语句，也没有 ctime 最终比较**，不能把草稿行号或检查能力套到发布脚本。

### 3.2 settings → imported 的三层规则

- 小输入归一化：[same_input():139–143](../../../app/src/main/assets/rc1-migration.py#L139-L143) 把单个同摘要 settings/imported 视为同一内容输入；一旦任一侧两项都存在，就必须逐项完全相等。Java 的 [sameInputs():82–100](../../../app/src/main/java/com/deepseekharness/app/util/Rc1MigrationReuse.java#L82-L100) 同样如此。这是内容规则，不是 rename 发生证明。
- 非 cached finalize：[finalize():385–395](../../../app/src/main/assets/rc1-migration.py#L385-L395) 只要 `settings.yaml` 不存在就改读 `.imported`，不要求原映射是 direct file，不检查原清单是否已经含独立 imported 项，也不做草稿的 mapping/link 调整与冲突拒绝。随后只用 dev/ino 与 digest。
- 实际导入器：[rc1-settings-migration.cjs:63–80](../../../app/src/main/assets/rc1-settings-migration.cjs#L63-L80) 从独立 snapshot 取内容、读回 SHA、在 settings/imported 中寻找同 SHA 输入并校验账本 generation/sourceSha256；[115–123](../../../app/src/main/assets/rc1-settings-migration.cjs#L115-L123) 仅在活动正文匹配且 imported 不冲突时 rename。不要用这个写者的正常路径反推其他路径必然经过合法 rename。

### 3.3 外部替换反例在本仓是否成立

**相同的判据缺口成立，且本仓最终比较更少。** 静态推导如下：

1. prepare 保存 settings 的独立副本、SHA 与旧 dev/ino。
2. 另一同内容文件成为 imported，原 settings 路径消失。
3. 用来件的确定性 mock 让 imported 的 `os.stat` 返回旧 dev/ino；本仓 finalize 没有 mtime/ctime/mode/nlink 最终判定，mock 的 ctime 差异与恢复 mtime 对此分支都不重要。
4. snapshot 仍完好，来源 digest 相同，其他来源不变，于是 `preserved` 保持 true，返回 0。未提供 settings 读回账本时 status 为 **pending**、`settingsImported=false`；并非必然 committed。
5. [receipt 写者:397–407](../../../app/src/main/assets/rc1-migration.py#L397-L407) 仍会写 `sourcePreserved=true`、`protectionComplete=true`。若把这两个字段解释成“原来那个对象仍存在”，便是假肯定；若改成明确范围的内容副本语义，副本可验证而对象身份应为 unknown。

这不是本轮执行结果，也不声称 Android 已发生 inode 复用。现有 [同字节替换测试:109–110](../../../tools/test-rc1-migration.py#L109-L110) 依赖实际 inode 改变，不覆盖 mock 复用 dev/ino。未来应保留“来源替换已观测”检测，新增“副本字节仍可验证 + 对象身份 unknown”的断言；不能只把旧失败预期改成功却继续输出原件身份证明。

### 3.4 `protectionComplete` / `sourcePreserved` 的全部关键写者

| 写者/路径 | 当前证据与输出 |
|---|---|
| 新 prepare | [277–337](../../../app/src/main/assets/rc1-migration.py#L277-L337)：起初 false；逐条 snapshot、候选处理成功后写 `prepared/protectionComplete=true`，发布私有 current 与公开兼容索引。不是绝对对象证明 |
| Python prepare 快路径 | [253–257](../../../app/src/main/assets/rc1-migration.py#L253-L257)：version=2、small input 相同、旧 prepare 的 protectionComplete 为真，就更新 current.startupId，输出 `already/protectionComplete=true`；**不读回全部副本，也未要求 committed receipt** |
| 首次/未完成 finalize | [380–407](../../../app/src/main/assets/rc1-migration.py#L380-L407)：snapshot + 部分源检查合成 preserved；把同一布尔同时赋给 sourcePreserved/protectionComplete；settings 另读账本 |
| Python committed 快路径 | [377–379](../../../app/src/main/assets/rc1-migration.py#L377-L379)：旧 v2 receipt 同 generation、三个成功布尔为真，直接输出 `committed/sourcePreserved=true/settingsImported=true/cached=true`，不重扫来源/副本；该输出甚至**不含 protectionComplete** |
| Java 快路径 | [output():70–82](../../../app/src/main/java/com/deepseekharness/app/runtime/Rc1MigrationCache.java#L70-L82) 直接合成 protectionComplete=true；finalized 时再合成 sourcePreserved/settingsImported=true。见下一节 |

settings 的业务读回由 [migrate():101–114](../../../app/src/main/assets/rc1-settings-migration.cjs#L101-L114) 调用 settings.update 后 describe/contains，逐节写 `verified` 并合并总状态。Python 非 cached finalize [398–399](../../../app/src/main/assets/rc1-migration.py#L398-L399) **只看账本 generation 与 status=verified**，没有重新核对 sourceSha256、逐节内容、startupId；无 settings 来源时 imported 也为 true，实际表示“不需要导入”。Java cache 则只读旧总 receipt，不读该账本。这些证据不能都叫“本次导入已核验”。

## 4. 卡点二：Java 缓存的真实范围与失败分支

### 4.1 `reusableGeneration` 来自哪里

[GuestPluginScripts 的生产 Preparation:121–130](../../../app/src/main/java/com/deepseekharness/app/runtime/GuestPluginScripts.java#L121-L130) 使用 AndroidBackupFileSystem，经 ColdInstallPackages 绑定本机私有 files，取 UserDataLayout.current()，调用 Rc1MigrationCache.generation，最后再核验 host。不是取用户归档中的任意成功 JSON。

| 检查 | 实际范围与限制 |
|---|---|
| 私有路径/根 | [Rc1MigrationCache:16–29](../../../app/src/main/java/com/deepseekharness/app/runtime/Rc1MigrationCache.java#L16-L29) 要求 home 在 files 下，逐父级 child 检查，目录类型与 device/inode key；[BackupFileSystem.child/small](../../../app/src/main/java/com/deepseekharness/app/backup/BackupFileSystem.java#L60-L73) 提供窄边界。NOFOLLOW 不证明跨进程绝对原件身份 |
| 缓存读取的私有记录 | [30–36、60–67](../../../app/src/main/java/com/deepseekharness/app/runtime/Rc1MigrationCache.java#L30-L67) 读私有 current、`generations/<uuid>/reuse.json`、receipt，各 ≤64 KiB；局部变量 prepared 实际指 **reuse.json**，不是完整 prepare.json；要求 reuse 的 dshVersion 等于 Constants.DSH_VERSION |
| 当前输入 | [37–49](../../../app/src/main/java/com/deepseekharness/app/runtime/Rc1MigrationCache.java#L37-L49) 对 settings/imported（各 ≤4 MiB）、restore 标记（≤4096）NOFOLLOW 小读、类型与前后 stat 检查、SHA；数据根再 stat。空输入 map 表示三项都检查过不存在 |
| 跨记录一致性 | [50–57](../../../app/src/main/java/com/deepseekharness/app/runtime/Rc1MigrationCache.java#L50-L57) 再读三个记录并比较；[Rc1MigrationReuse.matches:28–50](../../../app/src/main/java/com/deepseekharness/app/util/Rc1MigrationReuse.java#L28-L50) 要求 v2、UUID generation 三者相同、dshHome、prepared/committed 状态与旧成功布尔、版本一致、current/reuse 的根与小输入匹配 |
| 没有检查 | 不读完整 prepare.sources/inputDigest/snapshotInventory，不枚举或读回所有 snapshot，不复核当前来源完整清单，不读 settings-result 逐节状态，也不将 current/receipt 的旧 startupId 绑定为本次流程 |

Python 的 [publish_reuse():347–365](../../../app/src/main/assets/rc1-migration.py#L347-L365) 仅在同 v2 generation、prepare/receipt 的 dshVersion 等于当前 runtime、prepared 完整、receipt committed 三个布尔为真时发布小 checkpoint；内容只含 version/generation/dshVersion/dshHome/dataRoot/inputs/status/protectionComplete，**不含完整 inventory**。这是一条有条件的历史证明，不是本次全量内容核验。

### 4.2 两阶段内存绑定与回退

[GuestPluginScripts.migration():248–280](../../../app/src/main/java/com/deepseekharness/app/runtime/GuestPluginScripts.java#L248-L280) 的控制流：

1. rootPresent 为 false，直接准备失败。
2. 尝试 reusableGeneration；IOException/RuntimeException 被捕获，按未取得缓存继续。
3. prepare 命中：内存 `preparedReuse[startupId]=generation`，最多 8 条；直接 localCompletion。**不写 durable current.startupId，也不执行 Python。**
4. finalize 命中：先 remove 本 startupId 条目；本次查出的 generation 与内存值相等才 localCompletion。不同 startupId、无 prepare、代次变化都不能走此成功分支。
5. 其余情况移除条目，执行 preparation.assets，再 runner.run(prepare/finalize 命令，600000ms)。本函数没有第二次 runner 重试，但把“不匹配”“不可读”“异常”都折叠为 guest 路径。

[localCompletion():53–60](../../../app/src/main/java/com/deepseekharness/app/util/BoundedProcessRunner.java#L53-L60) 返回 exitCode=0、timedOut=false、truncated=false；只表示宿主本地步骤完成，没有 guest 派发/退出。现有 [GuestCommandOutcome:9–16](../../../app/src/main/java/com/deepseekharness/app/util/GuestCommandOutcome.java#L9-L16) 校验退出/超时/截断，不能区分这个本地结果是否重新核验了保护内容。

**不得把回退描述成必然重新导入。** Python prepare/finalize 自身也有历史短路；真正的 settings 写入在受管 [settings 补丁](../../../app/src/main/assets/rc1-settings-migration-patch.json#L7-L8) 调用的 CJS 中。[verified 节:80–87](../../../app/src/main/assets/rc1-settings-migration.cjs#L80-L87) 在账本完整时跳过，不覆盖用户后改值。现有[跨 runtime 测试:37–55](../../../tools/test-rc1-migration.py#L37-L55) 与 [settings 跨 runtime 测试:76–114](../../../tools/test-rc1-settings-migration.mjs#L76-L114) 明确要求保留旧 generation/账本，不把旧证明改标成新 runtime 成功。必须保留这些行为。

但要分清丢失对象：仅缺优化用 reuse checkpoint 且其余权威/分节记录完整，不等于重导；**settings-result 账本缺失**时 CJS [79–81](../../../app/src/main/assets/rc1-settings-migration.cjs#L79-L81) 将 ENOENT 当新账本，旧 verified 防重放条件消失，而 Java cache 没有检查它，仍可命中。若 **current/整套宿主状态缺失**，Python [252–277](../../../app/src/main/assets/rc1-migration.py#L252-L277) 还会新建 generation，[inventory:168](../../../app/src/main/assets/rc1-migration.py#L168) 把 imported 也作为来源，随后在新的 ledger 下写入旧值存在静态可达路径。成立前提包括原 snapshot/来源正文仍匹配、目标 section 可用、实际设置模块执行；并非任意 miss 都发生。

同一次 migration 调用中，runner 位于 cache-read 的 catch 之外，runner 异常不会触发本方法再派发；进程门禁也拒绝 timeout/非零/截断。但内存表只保存成功 prepare 的 generation，没有 migration 级持久 attempted/UNKNOWN 终态，不能外推跨调用/重启的 exactly-once 保证。

## 5. 消费与展示：历史结果是否当成了本次核验

结论是：**消费规则合并了两种成功，缺少本次核验作用域；但没有证据表明当前 UI 明文向用户承诺了“所有原文件本次逐一重验”。**

| 消费位置 | 当前行为与影响 |
|---|---|
| [Rc1MigrationResult.parse:15–26](../../../app/src/main/java/com/deepseekharness/app/util/Rc1MigrationResult.java#L15-L26) | 只允许一条 `DSHA_RC1_MIGRATION=` 完整 JSON；多条/缺失会报错。未解析 cached、phase、startupId、证据作用域为强类型契约 |
| [allowsStart/isRoutineReceipt:29–44](../../../app/src/main/java/com/deepseekharness/app/util/Rc1MigrationResult.java#L29-L44) | prepared/already + protectionComplete=true 即允许启动；committed + sourcePreserved/settingsImported=true 即常规静默结果；忽略 cached，finalize 甚至不要求 protectionComplete |
| [StartupPipeline:147–150、281–292](../../../app/src/main/java/com/deepseekharness/app/core/StartupPipeline.java#L281-L292) | 先 requireCompleted，解析后将上述布尔用于迁移前启动门禁；routine 不进入可见日志。历史成功在这里可授予与本次 prepared 相同的启动许可 |
| [WebReadyTasks:55–71](../../../app/src/main/java/com/deepseekharness/app/core/WebReadyTasks.java#L55-L71) | 鉴权后延迟 finalize；仅凭 committed + 两个布尔压低注意提示，不区分历史/当前。异常只写 `RC1_MIGRATION_VERIFICATION_UNAVAILABLE`，未调用 migrationNeedsAttention |
| [WebLifecycleController:1405–1440](../../../app/src/main/java/com/deepseekharness/app/core/WebLifecycleController.java#L1405-L1440) | startupId 来自本次诊断；finalize 先过 requireCompleted。需要处理时提示“网页已就绪；部分旧数据仍待处理”，不是清理或删除许可 |
| [RetainedCatalogue:546–583](../../../app/src/main/java/com/deepseekharness/app/backup/RetainedCatalogue.java#L546-L583) | 宿主/guest 两处迁移记录只作保留数据清单，按 marker.status 展示 RETAINED/PENDING_RETRY/COMMITTED/UNREADABLE，固定 `READ_ONLY_RESCUE_NO_AUTOMATIC_DELETION`。未用 sourcePreserved/protectionComplete 自动清理 |

因此应修正的是**生产与消费的证据契约**，不能只改一句 UI 文案，也不能声称现有成功布尔已经触发删除原件。保留数据列表的“COMMITTED”还须标注历史语义，不能升级为当前健康。

## 6. 需修 P1：本仓实际缺口与最小修法

以下均为静态可达结论；严重性指结果/恢复正确性，非数据损坏事故定级。本轮不改生产代码。四项 P1 与两项应随契约修复的 P2 分开列出。

| 编号 | 问题、证据与触发 | 最小修法 |
|---|---|---|
| P1-1 结果作用域失真 | [Java output:70–78](../../../app/src/main/java/com/deepseekharness/app/runtime/Rc1MigrationCache.java#L70-L78) 与 [Python 历史快路径](../../../app/src/main/assets/rc1-migration.py#L377-L379) 将旧完成合成无作用域成功；[消费:29–44](../../../app/src/main/java/com/deepseekharness/app/util/Rc1MigrationResult.java#L29-L44) 忽略 cached。非 cached Python 的 profile/V4 豁免与 dev/ino 复用反例也不支持绝对原件声明 | Java/Python 同时写版本化、带 scope 的内容证据；原对象身份 unknown；消费侧单独识别历史完成。历史成功不能单靠改字段名变成本次核验 |
| P1-2 权威/分节账本丢失可重导旧值 | Java [29–55](../../../app/src/main/java/com/deepseekharness/app/runtime/Rc1MigrationCache.java#L29-L55) 不读 settings-result，旧 committed 可继续 cache hit；CJS [79–104](../../../app/src/main/assets/rc1-settings-migration.cjs#L79-L104) 对 ENOENT 建新 ledger 并可能 update。current 缺失时 Python [252–277](../../../app/src/main/assets/rc1-migration.py#L252-L277) 新建 generation，也可将 imported 再作来源。前提见 §4.2 | 完成缓存绑定 generation/sourceSha256/逐节终态的稳定业务摘要；权威/ledger 缺失与真正首次迁移区分。既有迁移迹象下只读对账、unknown，不自动新建 ledger/代次后重导。保留用户后改设置 |
| P1-3 settings 不确定写入仍可能再派发 | CJS [96–110](../../../app/src/main/assets/rc1-settings-migration.cjs#L96-L110) 先写 writing，再调用 update；所有 update 异常都变 pending。下一次若脱敏 safeCurrent 与 before 相同，会再次调用 update。相同可见值不能证明前次从未派发，崩溃残留 writing 也落入同一路径 | 对已进入 update 的异常/中断保持 unknown 或 writing，不自动恢复为可写 pending；先只读核对既有目标与账本，能证明既有完成才补记录，无法证明则保留待用户处理。确定未派发的 SECTION_UNAVAILABLE 与写后结果未知分开；失败后的重新写入须显式新意图 |
| P1-4 宿主 RC1 救援清单与生产记录形状不符 | Python [332–333](../../../app/src/main/assets/rc1-migration.py#L332-L333) 的 current 不写 status；[RetainedCatalogue:559–570](../../../app/src/main/java/com/deepseekharness/app/backup/RetainedCatalogue.java#L559-L570) 优先 current 并强读 status，[BackupJson:142–145](../../../app/src/main/java/com/deepseekharness/app/backup/BackupJson.java#L142-L145) 因缺字段抛错，标为 UNREADABLE；[resolveAll:667–669](../../../app/src/main/java/com/deepseekharness/app/backup/RetainedCatalogue.java#L667-L669) 拒绝选择，影响宿主 snapshot 救援入口。guest 公开 receipt 可读不能替代宿主内容副本导出 | 按已验证 generation 定位 prepare/receipt，区分索引不可判状态与内容不可读取。保留只读救援边界；对可安全读取的未知记录可导出事实/副本，但不授予恢复或删除。用真实 Python 产物代替人工带 status 的测试夹具 |
| P2-1 快路径失效后的 startup 绑定断裂 | Java [259–280](../../../app/src/main/java/com/deepseekharness/app/runtime/GuestPluginScripts.java#L259-L280) cached prepare 只写内存；finalize 若缓存 miss/IO 异常便执行 Python finalize。后者 [372–373](../../../app/src/main/assets/rc1-migration.py#L372-L373) 校验 durable startupId，通常仍是上一启动，返回 stale-startup。requireCompleted 抛错，后续仅记 unavailable | prepare 绑定不可变的本次证据票据（startupId/generation/输入与记录摘要）。finalize 绑定失效直接 unknown + 可见待处理；不得自动补一次 prepare 或沿用旧 durable startupId。只有显式同代只读核对可重建观察 |
| P2-2 协议重复回执 | reuse JSON 损坏、同 runtime 版本且 committed receipt 尚好时，[publish_reuse:360–363](../../../app/src/main/assets/rc1-migration.py#L360-L363) emit `reuse-checkpoint-rebuilt`，接着 [finalize:378–379](../../../app/src/main/assets/rc1-migration.py#L378-L379) emit committed；[parse:20](../../../app/src/main/java/com/deepseekharness/app/util/Rc1MigrationResult.java#L20) 必然拒绝两条结果。旧单测只检查返回 0/文件修好，未经过 Java parser | 将 checkpoint 修复诊断并入唯一最终结果的 warnings 或普通非协议日志；保持一请求一结构化回执。未知时可读，不自动重做业务写入 |

这些问题都不能用“再调用一次就好”修补。尤其 [WebReadyTasks 的异常分支](../../../app/src/main/java/com/deepseekharness/app/core/WebReadyTasks.java#L68-L71) 应把 unknown 持久化/投影为待处理，而不把日志出现等同于用户已知悉。收紧消费者必须与写者同时交付：仅加 `protectionComplete==true` 会误拒当前不带该字段的 Python cached 输出。

## 7. Q1：可复用能力与必须改变的语义

| 能力 | 已有且可复用的部分 | 边界/改动 |
|---|---|---|
| RC1 原有副本 | [snapshot_row 的独立创建/fsync/读回](../../../app/src/main/assets/rc1-migration.py#L146-L164)、完整 sources 清单、编号副本、失败保留、preset 惰性候选 | 首期保留存储布局；把 `sourcePreserved` 拆解为内容证据与 unknown 对象身份。当前新写入前须重新验证所需副本及清单，不能借历史布尔跳过 |
| backup 窄文件系统 | [BackupFileSystem](../../../app/src/main/java/com/deepseekharness/app/backup/BackupFileSystem.java#L6-L41) 的类型/身份/读边界与 [small/atomic](../../../app/src/main/java/com/deepseekharness/app/backup/BackupFileSystem.java#L103-L150)；Java RC1 cache 已直接使用 | 可复用同操作内 NOFOLLOW/fstat/字节读回和原子记录。它们不让持久 dev/ino 成为绝对出生证明；也不能说 Python 已全面使用该宿主实现 |
| PrivateRoots | [PrivateRoots:13–47](../../../app/src/main/java/com/deepseekharness/app/backup/PrivateRoots.java#L13-L47) 已包含 `rc1-migration-state` | 保持宿主权威记录与用户可导入数据分离；隐藏目录不是完整性签名，不是同 UID 沙箱 |
| RetainedCatalogue | [migrationRecords:546–583](../../../app/src/main/java/com/deepseekharness/app/backup/RetainedCatalogue.java#L546-L583) 已有 MIGRATION 类、只读来源与不自动删除政策 | 复用前先修 §6 P1-4 的真实 current 形状；不能宣称宿主 snapshot 已具备完整救援导出闭环。沿用既有入口展示历史/当前/unknown、reason、代次与时点，不由目录存在推定副本健康 |
| HostDataTransaction | [commit/recover:197–305](../../../app/src/main/java/com/deepseekharness/app/backup/HostDataTransaction.java#L197-L305)：提交前 before/after 校验、先记 switching、保留 previous、写后读回、可恢复条件不满足则保留现场 | 适用于**宿主显式恢复/替换树**，不要求为 RC1 读副本把整条事务再套一层；RC1/SettingsForms 当前没有直接跑在该事务里，不能宣称已获得其全部保证。借用阶段/不确定性规则，不直接替换正在运行的配置树 |
| A3 数据保护基线 | [A3 §2.8、§5–6](../kotlin-rust-rearchitecture-20261112/a3-task-baseline-20261113.md#L152-L157)：维护屏障、既有 v5/宿主事务、PlanView 只读投影、JVM/Rust 差分与失败关闭 | A3 主线接线尚未完成；R1/R2 默认关闭，PlanView 不是运行结果证明。此修复无须等待 Rust、新备份格式或模块重构；可与 A3 后续展示/投影衔接，但不共享未落实的成功承诺 |

必须保留：原件/副本/未知现场不自动删除，旧 committed settings 不覆盖用户后改值，restore 标记与输入变化能明确区分新意图，所有外置路径只按既有批准映射处理。跨进程 dev/ino 变化只能形成来源变化线索，不能把仍可核验的内容副本一并判坏，也不能仅凭元数据变化自动新建导入代次；同次打开/复制中的类型、链接和变化守卫仍须保留。对“来源替换已检测”可另外记 reason；即使副本仍有效，也不能把原对象身份写成 false 后解释为“数据丢失”。

## 8. Q2：最小 cache/result 契约（设计建议）

### 8.1 字段与消费规则

沿用 `DSHA_RC1_MIGRATION=`、单条 JSON、现有私有目录与 generation；不新建执行框架。建议新增 **resultVersion=3**（wire 版本，与旧持久记录 `version=2`、v5 备份格式分开），迁移期读旧记录但只赋历史/unknown 作用域。下面是设计字段名，尚未实现。

| 字段 | 最小取值/规则 |
|---|---|
| `resultVersion`、`phase` | `3`；`prepare` / `finalize` / `verify-existing`。未知版本/未知枚举不默认成功 |
| `generation`、`startupId` | 当前请求与选定旧/新代次必须完全匹配；历史 evidence 的原始 startupId/verifiedAt 另行保留，不伪造为本次 |
| `status` | `prepared` / `committed` / `historical-complete` / `pending` / `failed` / `unknown` / `skipped`；只读核对旧代完成用 historical-complete，不制造新 committed 迁移事件 |
| `cached` | 只是路径信息；true 不授予写入或“本次核验”权限。缓存元数据可参与只读核对，但不得替代字节检查 |
| `contentCopyStatus` | `verified` / `failed` / `unknown`；含义仅为声明清单中的独立副本，必须与下列 scope 同时解释 |
| `verificationScope` | `current` / `historical` / `none`。只有当前流程完整读回该清单副本、检查数量/字节/SHA/映射范围后才能写 current+verified；历史记录仍可写 historical+verified，表示**原时点**核验，不承诺副本现在健康 |
| `sourceObjectIdentity` | 首期固定 `unknown`；不引入生造的“七项 stat 相同=绝对原件”成功值 |
| `settingsImportStatus` | `verified-current` / `verified-historical` / `not-required` / `pending` / `failed` / `unknown`。历史 verified 要绑定 generation+sourceSha256+已完成节账本；当前 verified 需本流程实际写后读回/对已派发结果完成只读核对；无 settings 单列 not-required |
| `reason` / `warnings` | 有界结构化原因；记录缺失、版本不支持、输入改变、读取失败、写入结果未知分别编码；进度不能再输出第二条最终协议记录 |

执行事实沿用进程结果/现有阶段账本：本地历史复用标明 **guest 未派发**；已派发但没有可靠终态为 UNKNOWN，不由 exit=0 字符串、超时后等待结束或缓存 miss 推断完成。若需要在 JSON 传播，加一项 `executionState=NOT_SENT/COMPLETED/UNKNOWN`，其含义只针对本 phase，不是历史 settings 操作；缓存本地检查完成仍是 guest NOT_SENT。

旧布尔过渡规则：新 v3 写者停止用 `sourcePreserved` 代表对象身份，消费侧不再读它授予许可。若为过渡暂留 `protectionComplete`，只能在 `verificationScope=current && contentCopyStatus=verified` 的当前保护分支写 true；缓存历史分支省略，不能以 false 伪装成已验证损坏。`settingsImported=true` 也不能替代上述明确的当前/历史/not-required 状态。缺字段、null、非法类型、未知枚举统一 unknown，禁止 `bool("unknown")` / “非空即真”。

### 8.2 缓存命中时复用什么、重新检查什么

| 情况 | 必须本次检查 | 允许复用/结果 | 不允许做的事 |
|---|---|---|---|
| 普通启动，已有完整历史导入，无新增迁移意图 | 私有根/记录类型与稳定读取、generation、规则/运行契约、小输入 SHA/restore 标记；settings 完成账本的 generation/sourceSha256/节状态与总 receipt 一致；本次票据绑定 | 历史 snapshot manifest、原核验时点、已完成节；输出 historical-complete、historical scope，允许**无迁移写入**的普通启动 | 不扫描活动会话全量；不输出本次全部副本已读回；不因当前配置后来变化重新灌入旧值 |
| 新输入/显式恢复标记、新迁移且确定无未决写入 | 当前来源清单、稳定读取、独立副本完整读回；当前输入与根绑定；空间/范围/类型检查；导入前再次确认使用的内容与指纹 | 只复用逐项重新核验的副本；当前保护成功才许可写入；settings 完成须真实读回 | 不由 APK/DSH 版本变化独自创建新业务 generation，不借旧成功布尔免检查 |
| 需要用旧副本做显式恢复/继续写入，或用户要求“现在核验” | 同 generation 的**副本字节读回**、清单范围/数量/摘要、输入与 settings 账本；恢复另走原有维护屏障/事务 | 可使用既有副本，不必重新复制来源；无副作用的 verify-existing 可产生本次内容核验结果 | 不把只读核对变成重新导入；不覆盖旧 receipt 的原核验时间与版本 |
| 只有优化用 reuse checkpoint 缺失，权威完整记录可读 | 先只读核对 current/prepare/receipt/账本、旧内容证据范围；确认无未决业务写入 | 可以返回历史作用域结果；如要补 checkpoint，单独原子写元数据且保留唯一结果 | 不因 checkpoint 缺失直接重跑 prepare/导入，也不重新创建业务 generation |
| 私有记录损坏/矛盾、内容副本缺失、读取/解析失败、版本未知 | 只读诊断；能确认副本坏则 failed，不可判断则 unknown；保留现场 | 展示待处理与明确 reason；不授予新的迁移写入许可 | 不把“异常”折叠成缓存 miss；不删除旧代、不自动补一次真实写流程 |
| 已派发、writing、超时、取消、断连、部分结果或终态未知 | 只读核对既有账本/实际目标；确认已有完成才能补充终态证据 | 保留 UNKNOWN/noReplay；必要时用户显式选择后另建意图 | 不把“目标值看似未变”当未派发，不因缓存失效/启动重试再次 update |

普通历史复用省去全量哈希，代价是诚实标明“本次未重新核验全部副本”。**凡将结果用作迁移写入或恢复许可，就必须当次读回所需内容保护集合**。这条区分同时防止每次启动扫描历史会话，又不让优化绕过必要的新检查。

### 8.3 最小代码落点与绑定

- [Rc1MigrationCache.generation/output](../../../app/src/main/java/com/deepseekharness/app/runtime/Rc1MigrationCache.java#L16-L82)：把 String/null 改成窄结果 `REUSABLE_HISTORY / NEW_INPUT / ABSENT / UNAVAILABLE`，带 reason 与同次记录摘要；output 序列化历史字段，不造成功布尔。UNKNOWN 不归入 ABSENT。
- [Rc1MigrationReuse.matches](../../../app/src/main/java/com/deepseekharness/app/util/Rc1MigrationReuse.java#L21-L50)：保留根/小输入/双 settings 比较，增加结果契约版本、账本 source 绑定。现有 dshVersion 约束保留为兼容性条件，不能变成迁移新意图。
- [GuestPluginScripts.migration/preparedReuse](../../../app/src/main/java/com/deepseekharness/app/runtime/GuestPluginScripts.java#L248-L280)：同一次 prepare 绑定 `(startupId,generation,inputFingerprint,receipt/checkpoint/ledgerDigest,contractVersion)`，其中 ledgerDigest 应对 generation/sourceSha256/逐节终态做稳定业务摘要，排除纯观察时间等每次启动可变字段；finalize 消费一次并保留终态/UNKNOWN，重复 finalize 不再派 guest。绑定失效返回明确未知；不偷偷补派 prepare/finalize。先核验 command 合法，再选 cache/guest 路径。
- [Python prepare/finalize/publish_reuse](../../../app/src/main/assets/rc1-migration.py#L244-L407)：两个历史短路与 Java 使用同一语义；提供同代 verify-existing 的只读核验路径，保留原 receipt；current 证据只在完整内容读回后生成；checkpoint 诊断归入最终 warnings。旧 v2 记录只能作为历史事实，不能就地抬升证明等级。
- [settings migrate](../../../app/src/main/assets/rc1-settings-migration.cjs#L77-L114)：保留已 verified 节不写；区分未派发与已派发未知，记录/消费 generation+sourceSha256+节状态；不因应用启动自动重试失败/未知写入。Python finalize 与缓存核对该账本，不仅看总状态。必须在实际 update 前消费同次 `startupId/generation/输入指纹` 绑定的导入模式：`history-only` 只核对旧完成账本，缺失/矛盾立即 unknown，不能执行 `result ||= 新账本`；`write-allowed` 只由本次副本保护通过且无未决写入的 prepare 产生。
- 最小跨进程交接可在现有私有 current 中加版本化 `startupContext={startupId,generation,inputFingerprint,mode,proofDigest}`（设计名），经既有 fs.atomic 写后回读；历史 cache 更新的只是本次交接元数据，不改旧业务 receipt/verifiedAt。Python/CJS 都核对该 context。若 context 过期、读取失败、代次替换或 ledger 在 cache 探测后丢失，禁止配置 update。要验证两个消费者实际使用该交接，不能只加 Java 内存字段后宣称已阻断重导。
- [Rc1MigrationResult](../../../app/src/main/java/com/deepseekharness/app/util/Rc1MigrationResult.java)、[StartupPipeline](../../../app/src/main/java/com/deepseekharness/app/core/StartupPipeline.java#L281-L292)、[WebReadyTasks](../../../app/src/main/java/com/deepseekharness/app/core/WebReadyTasks.java#L55-L71)：版本/字段强校验，将“允许普通无迁移启动”与“可执行迁移写入”分开。历史完成不触发重新导入；unknown 不静默。超时/非零/截断的现有进程结果拒绝规则继续保留。
- [RetainedCatalogue](../../../app/src/main/java/com/deepseekharness/app/backup/RetainedCatalogue.java#L546-L583)：沿用只读入口投影 scope、原时点与 reason，避免把 PENDING_RETRY 文案变成自动再试许可；不改删除策略。

## 9. 吸收切片、验收门槛、运行时与回退

P0/P1/P2 是落地顺序；§6 的 P1 是缺陷优先级，两者不要混淆。实施需另行派单，本轮只有文档提交。

| 切片 | 写域与依赖 | 可执行出口/测试门槛 | runtimeId 与维护页 | 回退 |
|---|---|---|---|---|
| **P0：冻结结果与无重放矩阵** | 本设计目录、现有 RC1 测试夹具/预期契约（后续实施）；不接生产。先确定 §8 枚举、历史启动规则、旧 v2 兼容 | Lead 同意 Q1/Q2；固定本文反例、cache true/false、unknown 与 legacy fixture；对方草稿完整字节未到不合其分支 | 本轮 docs-only：不改 runtimeId、零维护页 | 文档可撤回；不更改用户数据/迁移记录 |
| **P1a：生产语义原子对齐** | runtime 的 cache/GuestPluginScripts，util 的 Rc1MigrationReuse/Result，core 启动与鉴权后消费者，Python 两条快路径，CJS ledger，受管输入清单/描述符与相关测试。P0 后，同一可发布增量交付 | cache 真命中与 miss 均测；新写前副本读回；同 startupId/不同 startupId/换 generation；不可读、writing/抛错/超时 noReplay；snapshot 破坏；双 settings；单条协议；旧 committed 用户后改值不变。不能只过 Python 测试 | Python/CJS 是受管 assets，runtime/backup 树与有关 core/util 是 launcher 输入；需更新受管描述符，预期一次维护页；不改基础 Ubuntu/dataWrite/v5 | 优先前向修正；保留 v2+新记录，不能删新账本再回旧布尔缓存。未派发前可取消；已派发未知保留现场。禁止按旧自动恢复语义覆盖用户数据 |
| **P1b：补错误投影与只读核对入口** | 继续使用 core/backup/保留数据 UI；最小 verify-existing 入口与有界诊断，不另造框架；依赖 P1a | stale-startup/重复结果/坏记录明确可见；核对只读、同代、无 settings.update；坏副本不成功、原件/未知目录不删；safe mode 与受限入口不误写 ready | 如分开发布，所改 backup/core 可能再次改变 runtimeId；建议与 P1a 同批一次维护，不以“只是文案”规避描述符 | 关闭主动核对入口仍须保留 unknown/fail-closed 消费规则，不退回自动再导入 |
| **P2：A3 投影/性能与设备补证** | backup/保留数据投影及测试；与 A3 owner 协调；Rust 仍为可选旁路，非此修复前置 | 扩大清单/软链接/并发变化/大数据上限测试；统计历史启动小读与显式重验成本；随后隔离测试包验证覆盖更新、一次维护、旧代不重导 | 若只测试/文档则零维护；生产接线按 managed 输入实际变化评估。维护次数以同一候选合并发布控制，不预写设备通过 | Rust 可回 JVM；数据语义不回退。仍不迁移/删除旧原件来“修复”证据 |

受管输入依据：[managed-runtime-inputs 的 Python/CJS 安装项:90–98](../../../app/src/main/assets/managed-runtime-inputs.json#L90-L98)、[launcherTrees/launcherSources](../../../app/src/main/assets/managed-runtime-inputs.json#L623-L755) 包含 runtime/backup、StartupPipeline、WebReadyTasks、WebLifecycleController、Rc1MigrationReuse。**Rc1MigrationResult 当前没有登记在该清单**；P1a 改其安全语义时应一并补入 launcherSources 并做负向输入测试，不能笼统声称全部 RC1 Java 已覆盖。实施时须对实际修改集合运行描述符检查，不从文件后缀猜“零维护”。本轮不修改受管描述符、构建版本、CI 或 CHANGELOG。

私有控制记录的新增 proof/context 版本须独立于 v5 用户归档版本明确双读单写：旧 v2 只读作历史事实，新执行许可不得由旧字段凑出。旧可执行版本可能不识别 history-only/context，所以不能把“记录仍能解析”当回退兼容；P1 交付前须验证旧运行时不会绕过新限制，不能证实时只允许保留现场与前向修正。运行时回切还须满足既有直接健康前代/数据可读规则，内容副本存在不构成降级许可。

### 9.1 现有测试资产与必须补的门槛

下表只代表已读到的覆盖，不代表本轮执行通过。

| 资产 | 现有覆盖 | 缺口/新增验收 |
|---|---|---|
| [Python RC1 测试](../../../tools/test-rc1-migration.py) | 新输入/restore 代次、pending、空间/I/O、损坏 snapshot、同内容不同 inode、旧 runtime 不重导、已完成普通启动不遍历 | dev/ino 复用 mock；合法 rename 与两 settings 冲突；profile/V4 的内容/身份区别；旧快路径损坏副本不冒充 current；checkpoint 修复输出经 Java parser |
| [真实 SettingsForms 测试](../../../tools/test-rc1-settings-migration.mjs) | 已完成节保持用户后改值、schema 拒绝、pending 目标冲突、snapshot/source 改变、跨 runtime 账本保持 | update 已执行后抛错/返回丢失、writing 崩溃后不再次 update；脱敏值相同不证明未派发；明确新意图后才重试；ledger 缺失/错 sourceSha 不历史成功 |
| [Rc1MigrationCacheTest](../../../app/src/test/java/com/deepseekharness/app/runtime/Rc1MigrationCacheTest.java)、[Rc1MigrationReuseTest](../../../app/src/test/java/com/deepseekharness/app/util/Rc1MigrationReuseTest.java) | fresh 小输入、version/root/generation/receipt 限定、单 rename 与双 settings | snapshot/prepare/ledger 未读不能 current；记录读异常区别于新输入；本次票据绑定；不借不支持版本自动重导 |
| [GuestPluginScriptsTest](../../../app/src/test/java/com/deepseekharness/app/runtime/GuestPluginScriptsTest.java) | 注入固定 generation 的缓存分支（stub）、prepare/finalize 配对和 guest 回退的现有契约 | 命中 prepare 后 miss finalize 的 stale-startup；未知派发只一次；两次 finalize 不自动发第二条 guest；typed history/unknown 输出 |
| [Rc1MigrationResultTest](../../../app/src/test/java/com/deepseekharness/app/util/Rc1MigrationResultTest.java)、[StartupPipelineTest](../../../app/src/test/java/com/deepseekharness/app/core/StartupPipelineTest.java)、[WebReadyTasksTest](../../../app/src/test/java/com/deepseekharness/app/core/WebReadyTasksTest.java) | 单结果、退出门禁、prepared/committed 布尔与鉴权后处理 | 缺字段/未知 enum/`"unknown"` 非真值；历史结果只许无迁移启动；错误/unknown 可见、无重放；fake cached success 不得当 current |
| [HostDataTransactionTest](../../../app/src/test/java/com/deepseekharness/app/backup/HostDataTransactionTest.java)、[RetainedCatalogueTest](../../../app/src/test/java/com/deepseekharness/app/backup/RetainedCatalogueTest.java)、[PrivateRootsTest](../../../app/src/test/java/com/deepseekharness/app/backup/PrivateRootsTest.java) | 既有事务/保留/私有根边界可复用；[RC1 清单测试:205–217](../../../app/src/test/java/com/deepseekharness/app/backup/RetainedCatalogueTest.java#L205-L217) 人工给 current 加了 status，不能当真实产物已可导出的证明 | 必须以真实 Python current/prepare/receipt 形状走 catalogue→sources；显式补 rc1 私有根断言；history/unknown 不授予恢复/删除权限；改动触及对应写域才做定向事务回归 |

实施验收必须同时覆盖真实 Java cache 分支、真实 guest Python 分支和实际 CJS 导入器；不得仅用 `Preparation` stub 或 Python 单测替代跨层结果链。定向测试先行，合入前由 Lead 串行组织所需 Java 单测/格式/lint/主机链测试；本轮按派单限制不运行 Gradle/cargo，也不借生产数据做注入。

## 10. 本轮交付、风险与待裁决事项

- 交付：[本设计记录](README.md)、[对外回复草稿](reply-20261113.md)、[文档索引条目](../../documentation-index.md)。未直接联系对方，草稿不承诺合并、实施时间或未验证能力。
- 文档检查结果（2026-11-13 UTC）：默认链接检查 56 篇 / 1303 个目标通过；本目录两篇与索引专项检查 3 篇 / 306 个目标通过，均 warning=0/error=0。`git diff --check` 与包含新文件的 `git diff --cached --check` 通过。链接检查只证明本地目标存在，不验证 URL/片段或语义；没有运行生产单测/构建。
- 未验证：完整外部草稿、对方仓库全部 diff、mock 在本仓的执行日志、Android inode 复用、设备安装/覆盖、真实进程中断恢复、当前 APK 全链回执、任何生产数据事故。
- 待 Lead 裁决：接受 Q1 的范围及 §8 历史普通启动规则；确认 P1a/P1b 同批发布以控制一次维护；排定 CJS unknown 不重试与现有“pending 自动补节”行为的产品变更。默认建议均按本文收敛，不能把本记录状态写为已经获准实施。
- 与 build363 发布独立：本文固定核对其发布源码 d0bc1b0b，不追随其它会话随后合入的文档重写证据基线。本轮不改 main、不推送、不合并、不安装、不操作手机；这些缺口应进入后续修复评审，不能把尚未验证的问题描述成已发布修复或用户数据损坏通报。

## 11. 实现状态（2026-11-13，分支 feat/rc1-fix）

- P1a/P1b 已实现并以 `resultVersion=3` 落地：Java/Python 统一历史/当前作用域、settings 账本绑定（generation + sourceSha256 + 期望分节集合）、prepare/finalize 同次票据与 startupId 绑定、单条协议回执、CJS history-only 只读核对、`RetainedCatalogue` 真实 current 投影、`verify-existing` 内部只读入口。分支提交与最终 runtimeId 见该分支 `.SESSION-DONE` 与合并回执。
- 非实现者评审 REQUEST_CHANGES 的吸收：① CJS 在 history-only 下禁止创建/更新分节、禁止写 `startupId`/`verifiedAt`/atomic 与 rename，账本缺节或 pending/writing/unknown 只返回 unknown/pending；② Python prepare 历史快路径必须已有 committed receipt 且 settings 账本 verified/not-required，`protectionComplete` 不再代替完成回执；③ 重复 finalize 返回原失败或 unknown，不再本地报告成功；④ warnings 严格 32 条；⑤ current 缺失投影为 UNKNOWN，账本 sourceSha256 绑定 current 输入摘要。
- Lead 裁决：Q1 接受保守 unknown（不自动补导入，需显式意图）；Q2 维持仅“明确未派发”的 schema/目标缺失节可在新票据下重试，writing/派发后异常冻结 unknown；Q3 `verify-existing` 暂只作内部只读入口，UI 入口另派单。
- 测试边界：CI Source 阶段不执行 `tools/test-rc1-migration.py` / `tools/test-rc1-settings-migration.mjs`；合并前须在 release/runtime 或等价的宿主夹具阶段补跑这两套（含本目录评审补的 history-only 只读与历史完成门槛回归）。

## 12. 收口补丁 A/B/E（2026-11-14，分支 fix/rc1-hardening）

外部静态审计 §2 的三处收口按“不扩范围”实施，均带会先失败的反例测试；本批不改
`StartupPipeline` 门禁、不改 DSH 版本 / 数据写标志 / v5 归档格式 / 包名 / 签名 / CHANGELOG，
descriptor 与 runtimeId 由 Lead 在合并树按最终输入重算。

- **A：update 之后不能按错误文字判未派发。** 删除 `/No configurable plugin entry/`、
  `/volatile|schema|invalid/i` 这类文字分类。只有调用 `settings.update` 之前完成的校验拒绝
  （分节不是映射、顶层字段未被 form schema 声明）才记 pending
  （`SECTION_VALUE_FORMAT` / `SECTION_SCHEMA_REJECTED`）；一旦进入 update，异常或读回不符一律
  unknown + `WRITE_OUTCOME_UNKNOWN`，同一票据不重试，catch 也不再绑定/记录错误消息。
- **B：按真实 schema 引用图投影秘密字段。** 锁定 `SettingsForms.describe()` 的
  `descriptor.schema` 是 schemastery 引用图 `{ uid, refs }`（uid 为数字，`dict`/`inner`/
  `list` 用 uid 引用子节点，秘密只由节点 `meta.role === 'secret'` 决定），不是 `dict`/`meta`
  树。现网实现按后者读取，真实 schema 上会漏掉 `providers.alpha.key` 这类 secret（正文写进
  账本 source/before/verified），同时用名称正则误删声明的普通字段 `maxTokens`。改为按图逐节点
  投影：`object`/`dict`/`array`/`tuple`/`transform`/`lazy` 递归，秘密节点不落账；`union`/
  `intersect` 分支歧义、schema 缺失、悬空 uid、未建模类型、形状不符时该节
  `SECTION_SCHEMA_UNKNOWN` 且不调用 update。名称正则只对 schema 未声明的键做“额外不复制”
  兜底，不决定已声明字段。读回核验在内存用含秘密的完整投影做 `contains`，账本只存去秘密投影。
- **E：旧账本缺期望分节集合不得自证完成。** `ledger_state` 的期望集合只接受账本自己的
  `expectedSections`，或同代次、同 settings 摘要的 `reuse.json settingsSections`；两者都缺时
  返回 unknown + `SETTINGS_EXPECTED_SECTIONS_MISSING`，不再用当前账本键拼出“全部完成”。Java
  历史缓存 `Rc1MigrationCache` 同口径拒绝（`SETTINGS_EXPECTED_SECTIONS_MISSING`），本批不新增
  用户确认放行旁路。

**已知行为边界（需产品裁决）**：命中面是“提交代次的账本写于 d278c742 之前（没有
`expectedSections`），且同代次 `reuse.json` 同样早于该提交（没有 `settingsSections`）”——
典型为从 ≤build363 直接升级的机器；它们首次启动的 Python prepare 会返回 unknown，
[StartupPipeline](../../../app/src/main/java/com/deepseekharness/app/core/StartupPipeline.java#L316)
对非 `allowsStart` 结果阻断启动，用户看到“迁移未完成或结果未知/检查保留数据”而不是被自动重导，
而且被拦后 finalize 不再运行，无法自愈。从 build364 升级且已启动过一次的机器，会在 finalize
快路径把 `settingsSections` 写进 `reuse.json`，因此可经同代次 checkpoint 证据继续判 verified；
新代次账本自带 `expectedSections` 不受影响。若产品希望改为“可见待处理但允许无写入启动”，需要
另立用户显式确认不导入的受控路径，不在本批范围。

测试（2026-11-14 UTC，本机真实锁定运行时）：`tools/test-rc1-settings-migration.mjs` 23 项通过
（含 4 项修复前失败的真实 schema/未知 schema 反例、3 项修复前失败的 A 反例，以及非映射分节在
update 前拒绝）；
`tools/test-rc1-migration.py` 48 项通过（含 3 项修复前失败、1 项修复前因缺新函数不可用的 E 反例）;
`Rc1MigrationCacheTest` 8 项通过（javac + JUnitCore；新增“旧账本与 checkpoint 都缺期望集合 →
UNAVAILABLE”负例，保留账本自带 `expectedSections` 的历史命中正例）。另有
`format-java-tree.py --check`（1775 文件 0 需格式化）、`check-source-hygiene.py`、
`check-document-links.py`（56 篇/1309 目标）、`run-host-tests.py --check-manifest` 通过。
未跑：Gradle 单测任务（本机缺 `app/src/main/assets/dsh-runtime.bin` 等固定输入，任务在
`prepareBackupAssets` 失败）、设备/覆盖安装/真机迁移、完整 Ubuntu 门禁；
`verify-runtime-descriptor-source.py` 因未重算 descriptor 预期失败（`DESCRIPTOR_SOURCE_BYTES:
rc1-migration.py`），由 Lead 在合并树重算。
