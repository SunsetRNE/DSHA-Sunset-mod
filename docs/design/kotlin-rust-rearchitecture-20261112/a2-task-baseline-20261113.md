# A2 任务基线（G2）：存储与文件（SAF 授权簿 + 文件工作台）

> 版本：v0.6　日期：2026-11-14　slice 3/4 集成基线：`1d675b3b`（初始实现来自 278a446a；P2 修复后按 Lead 新要求 rebase，无冲突）
> 状态：slice 1/2 已合入；slice 3/4 初次代码层逐行审查 **APPROVE（8×P2）**，P2-1..8 修复已落实，待增量复核和合并树 descriptor/固定输入 CI。**实现按切片集成；A2 整轮出口与 N3/N4、真机证据仍未闭环，不宣称整轮可合并或已验收。**
> 依据：[迭代路径](iteration-roadmap-20261112.md) §4 跨轮不变式 + §5 A2、[A1 任务基线](a1-task-baseline-20261113.md) 模板与已知 P2、[A1 契约草案](a1-contract-draft-20261112.md)（形状内部冻结）、[安全模型](../../security-model.md) SAF 一节。
> 历史：slice 1/2 曾 rebase 到 `3a8204fe`（Source checks 43332430587 / Doc 43332430557 绿）；当前轮不继承旧 CI 作为新实现通过证明。main 的 Kotlin/Rust 改动保持，合入与 descriptor 由 Lead 处理。

## 1. 范围

| 项 | 内容 |
|---|---|
| 用户可见 | 跨应用文件访问授权簿：撤销后旧访问立即失效、系统侧撤销后条目明确降级（可移除）、目录/文档限额与类型化错误；跨应用文件工作台：浏览、导入、导出、传输记录；`/app/saf/*` 行为保持 |
| 工程 | SAF 授权簿能力层（纯逻辑账本 + 代次 + 能力句柄 + 独立命名空间持久化 + 限额 + 边界判据）；接入既有 `saf/` 与桥（slice 2）；工作台 UI/记录（slice 3）；受控流与反例收口（slice 4） |
| 不在范围 | `MANAGE_DOCUMENTS` / 「所有文件访问」/ 申请新的 Android 存储权限；Provider 内容级凭据扫描与「防复制」承诺；跨设备/跨用户授权迁移；把系统授权交给插件或第三方模块；Kotlin/Compose、Gradle 拆模块、Rust；数据格式升级；模块卡片/排序接线；短信、屏幕、root |

**默认关闭/可撤销不放松**：SAF 仍只来自用户经系统 DocumentsUI 的逐个目录/文档授权（不绑定「所有文件访问」）；
授权只在本 App 内生效，系统设置可随时撤销；本层不新增任何敏感能力，也不改变既有设备授权默认值。

## 2. 改动文件（本任务只允许这些）

| 批次 | 路径 | 性质 |
|---|---|---|
| slice 1 | `app/src/main/java/com/deepseekharness/app/saf/SafGrantRecord.java` / `SafGrantLedger.java` / `SafLedgerCodec.java` / `SafLedgerStore.java` / `SafCapabilityHandles.java` / `SafCapabilityLimits.java` / `SafCapabilityPolicy.java` | 新增纯逻辑能力层（`SafLedgerStore` 只依赖 `SharedPreferences` 接口与 `Context`） |
| slice 1 | `app/src/test/java/com/deepseekharness/app/saf/SafTestSupport.java` + `SafGrantLedgerTest` / `SafLedgerCodecTest` / `SafLedgerStoreTest` / `SafCapabilityHandlesTest` / `SafCapabilityPolicyTest` | 新增单测（纯 JVM，不依赖 Robolectric） |
| slice 1 | `tools/java-format-files.json` | 登记新增 Java（格式门禁要求显式清单） |
| slice 1 | `app/src/main/assets/runtime-descriptor.json` | 生成物：`saf/` 在 `launcherTrees`，新增 7 个类后重算 `runtimeId`（见 §8） |
| 首提交（A1 P2） | `app/src/main/java/com/deepseekharness/app/contract/ModuleRegistry.java`、`app/src/test/java/com/deepseekharness/app/contract/ModuleRegistryTest.java`、`app/src/test/java/com/deepseekharness/app/ui/AboutFragmentTest.java` | 只做 §7 三条 P2 修复，不改契约形状 |
| slice 2 | `app/src/main/java/com/deepseekharness/app/saf/SafGrants.java`（迁移到账本；旧 `saf_grants` 只读兼容）、`SafTree.java`（限额常量单一来源），以及 slice 1 账本的扩展（`Previous`/`RELEASING`/metadata；仍在上述 7 个文件内）；`app/src/test/.../saf/SafGrantsTest.java` / `SafBridgeTest.java` | 接入与迁移 |
| slice 2 | `HttpShellService.java` / `util/BridgeRoutes.java` **本轮未改**：`/app/saf/*` 端点与协议号保持；桥只消费新的 `SafGrants.lookup/listLight` 语义 | 桥面（行为保持） |
| slice 3 | `app/src/main/java/com/deepseekharness/app/ui/SafAccessFragment.java` / `SafBrowseFragment.java` / `WorkspaceFilesActivity.java`、新增 `SafWorkbenchModel.java` / `SafWorkbenchUi.java` 与两份 SAF 布局；文案走 `UiText` | 工作台/授权页、旋转期间的传输状态与只读记录视图 |
| slice 3/4 | `saf/SafWorkbench.java` / `SafTransferHistory.java` / `SafTransferGate.java`；`SafGrants.capabilitySnapshot` / `SafCapabilityHandles.discard`；`SafPaths` / `HttpShellService` 的 SAF 限额引用 | 复用授权簿/短期句柄、固定身份本地 IO 与 `SafTree` 流；桥与工作台共用两个槽位，不新增桥凭据字段 |
| slice 3/4 | `SafTransferHistoryTest` / `SafWorkbenchTest`；既有 `SafCapabilityHandlesTest` / `SafPathsTest` / `SafBridgeTest` / `util/BridgePathPolicyTest` | 记录、撤销/重授权/系统撤销、未登记树、软链接与端点凭据区反例 |
| slice 3/4 | `tools/i18n/messages-extra.json` 与八份 `translations-extra`；`tools/check-source-hygiene.py` 的 ar/fr/ja/ko/pt/ru 六条语言 SHA；Java 格式清单 | 新文案 29 条×10 语；六条 SHA 经 Lead 2026-11-14 批准同步，不改校验逻辑或豁免 |
| slice 4 | 本文件及必要文档；`app/src/main/assets/runtime-descriptor.json` 仅由 Lead 在合并树按固定输入重算 | 实现者不改 descriptor，A2 出口证据由下文区分 |

**禁止触碰**：`app/build.gradle`、`ci/**`、`CHANGELOG.md`、`README*`、`AGENTS*`、`BUILD*`、`CONTRIBUTING*`、`docs/releases/**`、`app/src/main/res/raw*/changelog.md`、`docs/documentation-index.md`（与 build361 冲突，统一由 Lead 处理）。

## 3. 实现约束

- **语言/风格**：Java 17、2 空格、google-java-format；不混写 Kotlin/Compose。
- **持久化**：独立命名空间 `saf_capability_ledger`；不写 `ConfigStore`、不改历史 `saf_grants` 键、不碰 `dsha_module_layout`。坏标量回安全默认并给短码警告；未知 key/未知字段原样保留；结构损坏（缺 URI、id 与 URI 摘要不符、state 未知）整组保留但不激活（fail-closed）。
- **撤销语义**：每个授权带单调递增 epoch；`forget` 或重新授权换 epoch，旧记录/旧句柄立即失效；系统撤销后记录保留为「不可用」供用户移除，句柄使用前复查当前读/写位。能力句柄只存进程内存，60 秒 TTL、按树/全局限额、一次性消费用于删除。
- **限额（单一来源）**：`MAX_TREES=8`、列目录 500、单读 256 KiB、单写 32 MiB、文本直写 16 KiB、路径 32 段、并发传输 2、单操作 15 秒。slice 1 只在 `SafCapabilityLimits` 定义；slice 2 让 `SafTree` 引用同一常量，避免两套数。
- **边界**：tree URI 只接受 `content://authority/tree/…`；拒绝自己的 DocumentsProvider；容器侧导入/导出沿用 `BridgePathPolicy` 的凭据区判据并在打开后用 canonical 真实路径复核（堵软链接）；Provider 是信任边界，不承诺内容级凭据防复制。
- **桥协议**：`BRIDGE_PROTOCOL=3` 不变；只允许加法端点，不改既有端点语义/查询串预算；`/app/export`、`/app/readfile` 的凭据区边界保持不变。
- **下载续传**：只信 `TransferProtocol.responseOffset` 已核验的偏移（Range/Content-Range/ETag/长度）；A2 只做回归与证据，不改协议语义。
- **A1 契约形状冻结**：不改 `contractVersion`/`describe()`/`ModuleHost`/`setEnabled` 持久化/AboutLicense 聚合；A2 只修 §7 的 3 条 P2。
- **无新增敏感能力**：不碰短信、屏幕/识屏、root/Shizuku/ADB、悬浮窗；不申请存储权限；不引入 `MANAGE_DOCUMENTS`。

## 4. 验证方法

| 层 | 判据 | 谁 |
|---|---|---|
| 本地静态 | `format-java-tree.py --check`、`check-source-hygiene.py`、`check-ui-i18n.py`、`check-document-links.py`；`prepare-runtime-descriptor.py --check`（生成物一致） | 实现者 |
| 本地纯 JVM | 新增 saf 单测 + `ModuleRegistryTest`：`javac` + `JUnitCore`（本机 aapt2 为 x86_64，Gradle 资源任务不可跑）；记录用例数与失败 | 实现者 |
| CI | `Source checks`：contracts / java-format / inputs / unit-source / host-fast；合并前 full `testStandardDebugUnitTest`（0 skipped）与边界脚本 | Lead + CI |
| 覆盖更新 | 覆盖安装后 `runtimeId` 变化 → 进一次维护页；数据完好、无白屏、无侧栏重复；桥协议仍 3 | CI + 真机 |
| 真机 | DocumentsUI 逐目录授权；移除后旧访问失败；系统设置撤销后条目降级；Provider 离线/超时返回类型化错误；限额拒绝；传输记录正确 | 用户/Lead |
| 安全回归 | `/app/export`、`/app/readfile` 凭据区反例（既有测试）+ 未授权树/软链接/凭据区/限额反例（本轮新增） | 实现者 + 评审 |

**已跑/未跑如实记录**：
- slice 1：5 个纯 JVM 测试类 + `ModuleRegistryTest` 共 61 项本机 `javac`/JUnitCore 通过；`Source checks` run 43322817926 全绿（含 unit-source/host-fast）。
- slice 2：纯 JVM 68 项（新增账本 Previous/RELEASING/metadata 用例）与 `saf` 包 Robolectric 68 项（`SafGrantsTest`/`SafBridgeTest`/`SafLocalFilesTest`/`SafPathsTest`/`SafTreeTest`）在本机通过；Robolectric 使用本机专用 `-Drobolectric.conscryptMode=OFF --add-opens=java.base/java.io=ALL-UNNAMED`，仅为 aarch64 本机绕开 Conscrypt/反射限制，**不替代 CI 默认配置**。回退反例（迁移写失败）只在本机 `javac` 编译，由 CI unit-source 执行（run 43328387842）。
- 未跑：`AboutFragmentTest`（Robolectric，交 CI）、Gradle 资源链/全量单测（本机 aapt2 为 x86_64）；Lead 已要求本机暂停 Robolectric/全量，后续以 CI 为准。

## 5. 回退点

- **代码回退**：slice 1 全是新增文件 + 一个生成描述符；删除新包与描述符重算即可回到 build360 行为，无数据格式变化。
- **开关**：设置 → 设备能力授权 → 跨应用文件访问 →「文件工作台（导入与导出）」；独立 `saf_workbench_ui.enabled`，默认 false。关闭隐藏导入/导出入口，保留授权管理与只读浏览，不更改任何系统授权或助手写入偏好。作品文件页有同一原生授权/浏览路由与记录入口。
- **发布回退**：tag/Release；回退不需要清数据（本任务不升级任何数据格式）。
- **旧授权兼容**：slice 2 迁移只读旧 `saf_grants`，失败时保持旧记录并回退到旧路径，不静默丢授权。

## 6. Owner

| 角色 | owner |
|---|---|
| slice 1 能力层 + A1 P2 | `a2-storage`（本会话） |
| slice 2 接入 | `a2-storage`（已合入） |
| slice 3 工作台 / slice 4 本地实现与证据 | `a2-workbench`（session-26229003；基于 278a446a；出口仍待 Lead/CI/评审/用户） |
| 集成、descriptor、CI、发布、文档索引 | Lead |
| A2 出口非实现者评审 | 待指定（不得为实现者） |
| 真机验收 | 用户/Lead |

## 7. A1 已知 P2（首提交修复）

| # | 问题 | 修法 | 测试 |
|---|---|---|---|
| ① | `ModuleRegistry.dispatch` 持 registry 锁进入 `GenerationLease.runIfActive`；回调若回头调用 registry，与 `close` 形成 registry→lease / lease→registry 反向锁序 | `dispatch` 在 registry 锁内只取租约与代次，锁外调用 `runIfActive`；失效由租约自带的 active 复检兜底 | `dispatchDoesNotHoldRegistryLockWhileEnteringLease`（三线程锁序复现） |
| ② | `close` 的 `finally` 中若撤销/诊断二次抛异常，会中断剩余清理并掩盖首个失败 | 清理改为逐步独立捕获、保留首个失败；诊断走 `reportQuietly`；所有贡献撤销完后再重抛 `Error` / 返回类型化失败 | `closeCleanupContinuesWhenDiagnosticsThrowsDuringCloseFailure` + 既有 `closeCleansUpThenRethrowsErrorFromInstanceClose` |
| ③ | `hiddenAboutCardKeepsHostLicenseRows` 写隐藏态后未恢复，污染同 JVM 后续用例 | 记录原隐藏态，`try/finally` 恢复 | `AboutFragmentTest` 自身（CI Robolectric） |

## 8. 覆盖更新影响

`app/src/main/assets/managed-runtime-inputs.json` 的 `launcherTrees` 已包含 `app/src/main/java/com/deepseekharness/app/saf`。slice 1 新增 7 个主类 → `launcherInputs` 481→488，**`runtimeId` 必变**：

- 基线 `4d55973d407c171909e76d07f0b681de4069613944fb05e29cd38908e0984a26` → slice 1 `cf279a36dc72b608c95d352673b86115324b308defb57dbaaac46bee1c8d352f`（已推送，CI run 43322817926 全绿）。
- slice 2 改 `SafGrants`/`SafTree` 并新增记录状态字段 → 复审 N1/N2 修复后现行 `a2847544e7032bdd0eb7394438455521c120a25c0d54c280eb32db7a3e3e6139`（本地按固定输入 `--write`；后续 slice 每次改 `saf/` 都要重算）。
- 覆盖安装后按设计进**一次**整屏维护页（I1/I6）；数据格式不变、桥协议 `3` 不变；回退不需要清数据。
- `app/src/main/assets/runtime-descriptor.json` 是生成物，随批次提交；不得手改摘要。

## 9. 出口判据（A2）

1. 能力层正/反例由单测证明：PENDING→ACTIVE、满 8 树、撤销/重授后旧句柄失效、系统撤销降级、一次性消费、TTL、未知条目保留、坏值回默认、未授权目录/软链接/凭据区/限额反例。
2. 接入后：撤销后旧 id/旧 tree URI 立即失效；`/app/saf/*` 行为保持；`/app/export`、`/app/readfile` 边界用例通过；`BRIDGE_PROTOCOL` 仍为 3。
3. 无新增敏感能力；默认关闭/可撤销语义不放松；文档不把 Provider 边界写成内容级安全。
4. full 固定输入全量单测 0 skipped 绿；`test-core-boundaries.py` 基线只减不增；格式/文档链接 PASS；覆盖安装真机证据（一次维护页、数据完好、无白屏）。
5. 非实现者逐行评审 **APPROVE**；`CHANGELOG`/版本号/说明由 Lead 统一处理。

## 10. Slice 拆分与顺序

| # | 内容 | 依赖 | 状态 |
|---|---|---|---|
| 1 | 授权簿能力层（账本/编解码/存储/句柄/限额/边界）+ 单测；A1 P2 | — | ✅ 已推送，CI 全绿（run 43322817926） |
| 2 | `SafGrants` 迁移到账本（旧 `saf_grants` 只读兼容、迁移失败回退旧路径）；桥 lookup 走账本；撤销后旧 id/URI 失效；`SafTree` 常量单一来源；反例测试 | 1 | ✅ 已推送，CI 全绿（run 43328387842） |
| 3 | 跨应用文件工作台：授权页状态、浏览/导入/导出、记录；复用 `transfer/` 状态/文件名判据及现有 SAF 流，不重写下载引擎；workspace 操作层接入 60 秒内存能力票据 | 2 | 代码与本地编译完成；106 项纯 JVM 定向回归通过，Android 行为/布局待 CI 与真机 |
| 4 | 共享传输槽位、限额来源、端点与凭据区反例、出口证据 | 2、3 | 本地收口完成；descriptor/固定输入 full CI、非实现者评审和真机证据未完成，不可合并 |

**slice 2 接入语义（已实现）**

- **迁移**：`SafGrants` 首次访问时把旧 `saf_grants` 一次性只读导入账本（saved/pending/acquired/writable/label）；旧键此后不再写回，降级版仍能读到自己那份。初始化用 `schema` 标记，不用「记录为空」判断，避免移除全部授权后旧记录被反复迁移回来。迁移 `store.save` 失败 → 本次调用回退旧路径，授权不丢。
- **生命周期**：add 先落 PENDING；take/remember 成功后 ACTIVE；崩溃残留在下次 `cleanupPending` 按 before/intent 释放新增位后再删除或恢复旧 ACTIVE（`Previous` 快照）。撤销时系统拒绝释放 → `RELEASING`（列表/桥不可见），下次重试成功后删除。
- **桥**：`/app/saf/*` 的每次 lookup 都从账本按 ACTIVE 记录解析，并现场读系统系统券；撤销/重授权后旧 id 与旧 tree URI 立即 `NO_GRANT`。桥协议没有长生命周期 handle 字段，故长生命周期能力句柄留给 slice 3 工作台操作层；桥侧等价保证是「每请求重新解析 + epoch 校验」。
- **限额单一来源**：`SafGrants.MAX_TREES` 与 `SafTree.MAX_ENTRIES/MAX_READ/MAX_WRITE/DEADLINE_MS` 都引用 `SafCapabilityLimits`。

## 11. Deferred / 不做

- **明确 deferred**：A1 收敛口径中的 `contractVersion`/`describe()`/`ModuleHost` scope/`setEnabled` 持久化/AboutLicense 聚合（A2/A8）；本轮的模块卡片与排序接线（归 A8 宿主工程化）。
- **不做**：`MANAGE_DOCUMENTS`、所有文件访问、系统级文件管理器替代品；Provider 内容扫描/凭据防复制；跨设备/跨用户授权迁移；把授权对象交给插件或动态模块；重写 `DownloadEngine`；数据格式升级；Android 10 及以下；短信/屏幕/root 能力变更。
- **已知限制**：`cp/mv` 新建 inode，无法按 inode 识别「复制凭据内容」；`push` 覆盖可能留下半截目标（Provider 超时/取消）；Provider 是信任边界。

## 12. 非实现者评审 REQUEST_CHANGES 修复（2026-11-13）

| 编号 | 位置 | 修法 | 回归测试 |
|---|---|---|---|
| P1 | `SafGrants.forget` / `cleanupLedgerLocked` / `add` 的 RELEASING 分支 | 释放集合改为「记录 acquired ∩ 当前系统券」：交集为 0 时直接 `finishRelease`/`forget`，不再对已撤销系统授权反复 `release`（旧实现抛 SecurityException → 永久 RELEASING 占槽）；`persisted == null` 保留记录重试；forget 遇到 PENDING 先 `abortTake` 保留 before/intent；add 遇到交集为 0 的 RELEASING 先完成撤销再重加 | `systemRevokedForgetRemovesLedgerRecordAndAllowsReAdd`、`staleReleasingRecordWithNoHeldBitsIsFinishedByCleanup`、`releaseSucceededButSaveFailedDoesNotStick`（自定义 ContentResolver + ContextWrapper 反例） |
| P2-1 | `SafLedgerStore.save` / `SafLedgerCodec` | 磁盘 schema != SCHEMA 时 save 直接拒绝（不覆盖未来版本）；`decode(current).preserved` 参与保留，结构损坏记录的已知字段不再被保存时删除 | `saveRefusesFutureSchemaWithoutTouchingDisk`、`saveKeepsStructurallyCorruptKnownFields` |
| P2-2 | `contract/ModuleRegistry.close`（及 `setEnabled`/`enterSafeMode` 调用路径） | registry 锁内只取/置空 instance/lease/contributions，出锁后 `lease.invalidate()` 并清理，消除 registry→lease ABBA；安全模式先置标志再锁内收集、锁外 close | `closeDoesNotHoldRegistryLockWhileInvalidatingLease`、`setEnabledDisableDoesNotHoldRegistryLockWhileInvalidatingLease` |
| P2-3 | `SafCapabilityPolicy.localCanonicalProblem` | rootfsCanonical 空/缺时直接 `LOCAL_OUTSIDE_ROOTFS`，不再依赖 `deniedGuestView` 对 /sdcard、/tmp 的 fail-open 结果 | `canonicalPathCheckBlocksSymlinksThatResolveToCredentialsOrOutsideRootfs`（新增 /sdcard、/tmp 反例） |
| P2-4 | `SafGrants.list/lookup/add/forget/setWritable/cleanupPending` | ledger 就绪判定单独加锁；未就绪时出锁后再走会查询 Provider 的 `legacy*` 路径（新增 `ledgerReady`） | `fallbackListDoesNotHoldLockDuringProviderQuery`（慢 Provider 期间 listLight 不被 LOCK 卡住） |
| P2-5 | `SafGrants.add` beginTake / `SafGrantRecord.Previous` | 重新授权 beginTake 传入 existing app/label 并纳入 `Previous`/codec；abort/cleanup 恢复旧显示名 | `abortingRetakeRestoresPreviousMetadata`、`failedRetakeKeepsCachedAppAndLabel` |
| 顺手 | `SafGrants.lookup` | 补 `documentsTree(uri)` 过滤，与 legacyLookup 一致 | 现有 bridge 反例覆盖 |
| N1（复审新增，已修） | `SafGrants.add` RELEASING 分支 | 分支前统一取一次 `persisted` 并判空，系统券 API 抛异常→`PERMISSION_STATE_UNAVAILABLE`，不再 NPE | `SafGrantsTest.addOnReleasingWithUnreadablePersistedStateReturnsTypedFailure` |
| N2（复审新增，已修） | `SafGrants.forget` PENDING 分支 | 先按 `flags ∩ ~pendingBefore ∩ pendingIntent` 释放新增位；persisted 不可读/释放失败保留 PENDING 等 cleanup；成功后才 abort，恢复旧 ACTIVE 且用户仍要移除时继续正常 forget | `forgetOnPendingWithUnreadableStateKeepsPendingForCleanup`、`forgetOnPendingWithReleaseFailureKeepsPendingForCleanup`、`forgetAfterCrashPendingReleasesDeltaAndRemovesRestoredGrant` |
| slice 4 已实现 | `SafPaths.MAX_SEGMENTS`、`HttpShellService` SAF 槽位与文本上限 | 路径 32 段、文本 16 KiB 仍引用 `SafCapabilityLimits` 原数值；桥与工作台共用 `SafTransferGate` 的两个传输槽位 | `SafPathsTest.lastAllowedPathSegmentWorksInBothPathAndCapabilityLayer`、`SafWorkbenchTest.bridgeAndWorkbenchShareTheSameTwoTransferSlots` |

**已知窗口（N3/N4 保留，未在本轮关闭）**

- **N3 install vs close**：`ModuleRegistry.install` 的慢 factory 仍可能阻塞 close；修复需要修改冻结的 `contract/` 生命周期，本轮遵守禁止改动，交 Lead 另定窗口。现有 `ModuleRegistryTest` 仅作回归，不能用它声称 N3 已解决。
- **N4 ledgerReady→legacy split-brain**：首次迁移保存失败后，既有读写的模式切换窗口仍在。本轮不改变旧桥/迁移回退语义；工作台在同一授权锁内读取 `lookup` 与账本代次/现场系统位，迁移未就绪时拒绝签发工作台能力，不能把这一局部失败关闭写成 N4 全局修复。

## 13. Slice 3/4 本地交付与出口证据（2026-11-14）

- 入口：授权页新增默认关闭的工作台开关，作品文件页新增同一原生授权/浏览路由和记录入口。授权列表后台消费 `list`（保留带截止的 Provider 缺失标签补查）；写偏好与移除在后台执行，完成后按当前状态重新渲染。
- 操作：浏览/预览/传输均按授权短 id 重新验证；面板票据绑定树/操作/路径/epoch，沿用 60 秒 TTL 和现有限额，只在内存保留，不放入 Bundle、Intent、桥协议或传输记录。关闭面板只释放对应票据；传输消费票据，失败不自动重放。
- 流与边界：复用 `SafTree.read/writeStream`、`SafLocalFiles` 的 NOFOLLOW/fd 真实路径复核与原子发布；容器侧凭据和 rootfs 越界拒绝。传输中检查当前系统位、epoch、线程取消与总截止。Provider 仍是信任边界，外部原地覆盖失败可能留下不完整文件，未承诺内容级防复制。
- 记录：独立 `saf_workbench_transfers` 命名空间，最多 40 项；复用 `DownloadTask.Status` 与 `TransferArtifact.safeName`，只保存文件名/方向/时间/字节/结果短码。活跃记录不被轮换；进程重启后在途项显示中断，不自动续传；不保存 URI、完整路径、文件内容或凭据。
- 实际纯 JVM 回归：JDK 17 `javac` + `JUnitCore`，11 类 **106 项通过**，包括五类能力层、路径、传输记录、桥路径/快照、`ModuleRegistryTest` 与 `TransferProtocolTest`。初始五类能力层 58 项及中间九类 78 项是子集，不重复累加。
- 源码编译：`python3 /tmp/a2-compile.py <本工作树>`，缓存依赖 + JDK 17/现有 Kotlin 2.2.10，按真实 XML 与 Gradle 生成项构造临时 R/BuildConfig 符号；**1021 个生产/生成 Java 源与现有 ShellQuote Kotlin、14 个 SAF 测试源编译通过**。临时编译器类路径及 `app_name` 符号缺失已修正；这不等于 AAPT、APK 或 Robolectric 执行通过。
- 门禁：`python3 tools/run-host-tests.py --check-manifest` **254**；`check-source-hygiene.py` PASS（仅同步获准的六条语言 SHA）；`generate-changelog.py --check` PASS；`check-ui-i18n.py` **newFindings=0**；`check-document-links.py` **56 文档 / 1309 有效 / 0 警告 / 0 错误**；`test-core-boundaries.py` **25/25**，未扩大基线；`format-java-tree.py --check --offline --jobs 1` **1783 文件 / 0 待格式化**；`git diff --check` PASS。
- 固定输入检查：运行 `prepare-runtime-descriptor.py --check`，在 `RUNTIME_INPUT_MISSING_OR_OUTSIDE: app/src/main/assets/dsh-runtime.bin` 停止；本工作树未还原大体积固定输入。descriptor 完全未修改，runtimeId/输入摘要待 Lead 在合并树按固定输入重算，不能沿用本分支旧 pin。
- 未跑：依照既有 AAPT2 限制与 Lead 暂停本机 Robolectric/全量的要求，未启动 Gradle 全量、Robolectric 执行、Lint、APK、LayoutAuditInstrumentation 或任何手机操作；本轮未改 Kotlin 文件。新增 10 个 `SafWorkbenchTest` 与两项 `SafBridgeTest` 反例已编译，行为执行待 CI。
- 出口映射：§9.1 的纯逻辑/文件系统反例有本地执行证据，Android 接入部分待 CI；§9.2 已补端点与工作台反例且桥协议仍 3，但不继承旧 CI；§9.3 无新增权限、默认值或全局数据格式变化；§9.4 只有本地门禁部分完成，固定输入 full/0 skipped 与覆盖安装证据待补；§9.5 初次代码层非实现者逐行评审已获 **APPROVE（8×P2）**，P2 修复见本节后续回执，增量复核仍待 Lead。**A2 整轮出口未完成；切片集成与整轮验收分开。**

### 13.1 独立审查 P2 修复（2026-11-14）

- **P2-1**：浏览页后台按 `lookup().grant` 的 valid/readable/writable 判定导出入口；无写能力隐藏导出按钮。写能力拒绝的 `HANDLE_NOT_PERMITTED` 映射为只读说明；读能力丢失单独返回 `PERMISSION_DENIED`，不把正常只读误报成授权过期。
- **P2-2（选择明确失败关闭）**：迁移失败时桥保留 legacy 回退，原生工作台浏览/预览/传输都要求可验证 epoch，统一 `STATE_UNAVAILABLE`。已纠正 `SafGrants.capabilitySnapshot` 注释；专用文案提示检查存储后重试，不要求移除仍有效的系统券。新增回退/工作台两类语义的反例。
- **P2-3**：只在已锚定目录下清理超过一小时、符合 `.saf-workbench-<UUID>.part` 命名的普通单链接文件，不递归、不跟随符号链接，不动新临时文件和其它名字。导入发布前读取既有普通目标 mode，并像桥 pull 一样把权限位复制到临时文件；失败保持安全默认权限。已补旧/新/其它前缀/链接清理及 mode 复制反例。
- **P2-4**：Provider 夹具新增单次读/写打开钩子；首次读取时分别 forget、系统 release、推进总截止、线程 interrupt，断言旧目标字节保留、未 rename 发布且本次临时文件清理。模型调用的真实流程抽到纯逻辑 `SafTransferJob`；8 项单测覆盖 retarget、记录 begin/finish、失败收尾、短码映射与不重放。
- **P2-5**：授权页恢复后台 executor 中的 `SafGrants.list(ctx)`，保留既有 Provider 缺失名称补查及 15 秒单查询截止，没有把显示降级成只读缓存。
- **P2-6（静态核查，非设备验收）**：导出/记录合并同一横行、等权 wrap_content 高度、无固定行高与单行截断，工作台关闭时隐藏整行。继续使用共用 ShellTextButton（13sp、最小触控尺寸、无额外字体 padding）；列表保留 weight=1，输入表单和记录弹窗可滚动。按 320dp 短屏、1.3× 字号核对约束，没有以静态检查冒充 LayoutAudit；十语长文案实际几何与日夜/短屏/字体仪器验收仍未跑。
- **P2-7**：push 对本层输入流守卫抛出的 `HANDLE_REVOKED` / `TIMEOUT` / `CANCELLED` / `PERMISSION_DENIED` 保留原码，不被 SafTree 的通用 IO_ERROR 包装遮蔽；只在 `RuntimeWorkPort.begin` 的 IllegalStateException 处映射 `MAINTENANCE_BUSY`，另有维护态专用文案和传输槽位释放反例。
- **P2-8**：文本 16 KiB 引用 `SafCapabilityLimits.MAX_TEXT_BYTES` 保持（Lead 已追认）；删除未使用的 Toast import。新增只读/账本/维护三条文案×10 语，同步获准的六份语言 pin，不改检查逻辑。
- 第一轮修复验证：新增模型纯逻辑 8/8；生产与生成 Java **1022**、SAF 测试源 **15** 编译通过，未执行 Android/Robolectric。基线同步后最终门禁见后续集成回执；descriptor/CI/真机与 N3/N4 的未跑、未关闭范围仍按上述声明保留。
