# A2 任务基线（G2）：存储与文件（SAF 授权簿 + 文件工作台）

> 版本：v0.2　日期：2026-11-13　基线：`faab36c5`（build361 发布准备；A1 已合入）
> 状态：**slice 1 CI 全绿（run 43322817926）**；**slice 2（账本接入+旧命名空间只读迁移+回退反例）CI 全绿（run 43328387842）**；slice 3–4 未开始。
> 依据：[迭代路径](iteration-roadmap-20261112.md) §4 跨轮不变式 + §5 A2、[A1 任务基线](a1-task-baseline-20261113.md) 模板与已知 P2、[A1 契约草案](a1-contract-draft-20261112.md)（形状内部冻结）、[安全模型](../../security-model.md) SAF 一节。
> 注意：build361 发布准备（`faab36c5`）与本轮写范围不重叠；分支已 rebase 到它，合入由 Lead 处理。

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
| slice 3 | `app/src/main/java/com/deepseekharness/app/ui/SafAccessFragment.java` / `SafBrowseFragment.java` / `WorkspaceFilesActivity.java`、必要的新增 UI 类与 `app/src/main/res/layout/` 布局；文案走 `UiText`（不新增 raw changelog） | 工作台/授权页 |
| slice 4 | 本文件、`app/src/main/assets/runtime-descriptor.json`（最终重算）、必要的文档与验收证据（新文档索引入口报告 Lead，由 Lead 统一加） | 收口 |

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
- **开关**：能力层在 slice 2 才接线；未接线时没有运行时行为，可单独回退。workbench 入口可用设置项/路由回退（slice 3 定义）。
- **发布回退**：tag/Release；回退不需要清数据（本任务不升级任何数据格式）。
- **旧授权兼容**：slice 2 迁移只读旧 `saf_grants`，失败时保持旧记录并回退到旧路径，不静默丢授权。

## 6. Owner

| 角色 | owner |
|---|---|
| slice 1 能力层 + A1 P2 | `a2-storage`（本会话） |
| slice 2 接入 / slice 3 工作台 / slice 4 收口 | `a2-storage`（待 Lead 排期） |
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
- slice 2 改 `SafGrants`/`SafTree` 并新增记录状态字段 → 现行 `63f946ac620638fc980dda18929b73f33a288500cd3c93f0ddbda2553f1d3b57`（本地按固定输入 `--write`；后续 slice 每次改 `saf/` 都要重算）。
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
| 3 | 跨应用文件工作台：授权页状态、浏览/导入/导出、传输记录（复用 `transfer/`，不重写下载引擎）；长生命周期能力句柄在 workspace 操作层接入 | 2 | 待排期 |
| 4 | 受控流/限额收口、端点与凭据区反例、descriptor 重算、真机与出口证据 | 2、3 | 待排期 |

**slice 2 接入语义（已实现）**

- **迁移**：`SafGrants` 首次访问时把旧 `saf_grants` 一次性只读导入账本（saved/pending/acquired/writable/label）；旧键此后不再写回，降级版仍能读到自己那份。初始化用 `schema` 标记，不用「记录为空」判断，避免移除全部授权后旧记录被反复迁移回来。迁移 `store.save` 失败 → 本次调用回退旧路径，授权不丢。
- **生命周期**：add 先落 PENDING；take/remember 成功后 ACTIVE；崩溃残留在下次 `cleanupPending` 按 before/intent 释放新增位后再删除或恢复旧 ACTIVE（`Previous` 快照）。撤销时系统拒绝释放 → `RELEASING`（列表/桥不可见），下次重试成功后删除。
- **桥**：`/app/saf/*` 的每次 lookup 都从账本按 ACTIVE 记录解析，并现场读系统系统券；撤销/重授权后旧 id 与旧 tree URI 立即 `NO_GRANT`。桥协议没有长生命周期 handle 字段，故长生命周期能力句柄留给 slice 3 工作台操作层；桥侧等价保证是「每请求重新解析 + epoch 校验」。
- **限额单一来源**：`SafGrants.MAX_TREES` 与 `SafTree.MAX_ENTRIES/MAX_READ/MAX_WRITE/DEADLINE_MS` 都引用 `SafCapabilityLimits`。

## 11. Deferred / 不做

- **明确 deferred**：A1 收敛口径中的 `contractVersion`/`describe()`/`ModuleHost` scope/`setEnabled` 持久化/AboutLicense 聚合（A2/A8）；本轮的模块卡片与排序接线（归 A8 宿主工程化）。
- **不做**：`MANAGE_DOCUMENTS`、所有文件访问、系统级文件管理器替代品；Provider 内容扫描/凭据防复制；跨设备/跨用户授权迁移；把授权对象交给插件或动态模块；重写 `DownloadEngine`；数据格式升级；Android 10 及以下；短信/屏幕/root 能力变更。
- **已知限制**：`cp/mv` 新建 inode，无法按 inode 识别「复制凭据内容」；`push` 覆盖可能留下半截目标（Provider 超时/取消）；Provider 是信任边界。
