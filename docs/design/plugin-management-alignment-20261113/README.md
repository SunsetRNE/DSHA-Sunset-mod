# DSH 插件管理模块评估：安装、卸载、更新、读取、守护与壳侧状态缓冲

> 基线：build362 / `origin/main` `c2e7aec1`；内置 DSH `0.2.2-rc.1`（锁定运行时，含 DSHA 受管补丁）。
> 性质：只读源码评估与设计基线；未改生产代码、未跑 Gradle/cargo、未安装 APK、未操作手机。
> 结论只覆盖本基线源码与锁定上游说明，不外推其他版本、其他机型或真机行为。

## 0. 结论摘要

1. **定义对齐停在“包契约 + 事务”层，没有进入“实体 + 状态”层。** 原生壳能校验 `dsh.bundle.patch`、可选 `main` 与壳近似的 `pkg.cordis.entry` 入口、合法 npm 名/semver、实际依赖与摘要，也能用 `package.json.lock` 与 DSH 的 `withFileLock` 共用 profile 写锁；但原生列表把 **bundle（profile 选择项）与 plugin entry（Cordis loader 条目）压成一个 `Item`**，没有 DSH 的 `entryId` / `moduleName` / `patchId` / `availability` / `application` / `restartReason` 等语义字段。
2. **“缓冲”确实存在且是刻意设计的。** 壳侧读取链是“guest 目录快照 → `PluginRegistry` 展示快照 → 心跳运行投影 → 操作回执 → UI 卡片”的投影栈，窗口包括心跳 2 s、投影有效期 30 s、投影重读 10 s、目录缓存 TTL 60 s、自动查询暂停 120 s 等。它让原生页在 Web 未启动、维护中或查询昂贵时仍可工作，但也产生了多个“状态副本”和词表冲突。
3. **当前缓冲已具备基本不变量，但缺少契约文本与几个关键字段。** 已有“意图 / 安装 / 选择 / 已加载 / 需重启”分层、代次与来源身份、陈旧标记、失败保留旧快照、UNKNOWN 不重放；缺的是统一的证据等级、字段级来源、entry 级证据、兼容性阻断态、受管只读态和补丁链状态。
4. **五个 P1 级对齐缺口**（详见 §4）：
   - **entry 级启停不可见**：Web 的 `set_plugin` 写 `cordis.patch.yml` 的 `disabled` override，原生列表只读 `dsh.profile.bundles` + `.disabled` 标记，忽略 patch 层；
   - **bundle/entry 粒度混同**：一次刷新返回的是“包”列表，而 DSH 是“bundle 列表 + entry 列表”两个面；
   - **运行证据词表与 DSH 不一致**：DSH `fiberPhase` 为 `pending/loading/active/failed/unloading/null`，sampler 折叠成 `pending/loading/loaded/failed/unloaded/unknown`，原生 UI 还接受 `waiting/disabled`；
   - **更新语义未对齐**：DSH 是“精确版本 `listVersions`/`updateBundle` + 安装前兼容性阻断 + `application/restartReason/stage`”，原生是“来源最新版检查 → 预览 → 覆盖安装并保留上一版”，且 `compatibility` 只作字符串、不阻断；
   - **守护状态不可归因**：DSH 能给出 fiber 失败诊断、兼容性跳过/禁用、`management-required`/`unaddressable`；原生只有 `加载失败` 与启动诊断，且 `failed-load` 会把本轮所有 `attempted` 条目统一标记失败（`plugin-lifecycle.py:481-530`）。
5. **建议以“先契约、后字段、再行为”的切片推进**（§7）：P0 冻结壳侧状态缓冲契约与纯逻辑映射（零维护）；P1 补 entry/patch 读取字段；P2 补心跳 entry 级证据；P3 对齐更新语义与兼容性阻断；P4 补守护归因与受管只读态。本文仅设计基线，未实施。

## 1. 范围、方法与证据

- 评估对象：DSHA 安卓壳的插件管理模块，以及它与锁定 DSH `0.2.2-rc.1` 自带的 PluginManager / PluginInventory / App Boot 机制的衔接。
- 阅读对象：
  - 壳侧：`app/src/main/java/com/deepseekharness/app/plugins/`、`core/PluginRepository.java`、`ui/PluginFragment.java`、`util/Plugin*.java`、`backup/Plugin*.java`、`runtime/GuestPluginScripts.java`、`core/PluginActivationHooks.java`；
  - guest：`app/src/main/assets/plugin-manager.py`、`plugin-lifecycle.py`、`plugin-dependencies.py`、`plugin-transactions.py`、`register-builtin-plugins.py`、`startup-observer.cjs`、`app-integration/`、相关 `*-patch.json`；
  - 上游：`/usr/local/lib/node_modules/@deepseek-ai/dsh/` 的 `README.md`、`@deepseek-ai/dsh-plugin-manager`、`@deepseek-ai/dsh-host-plugin-inventory`、`@deepseek-ai/dsh-app-boot`、`@deepseek-ai/dsh-client-ui-plugin-manager` 与相关 `README`。
- 证据等级：源码调用链（B 级）；不含设备日志、真实启动计时、OEM 文件事件或正式 APK 结果。
- 术语：
  - **bundle**：profile 依赖中声明 `dsh.bundle` 的 npm 包，提供补丁层；`dsh.profile.bundles` 决定选择。
  - **plugin entry**：Cordis Loader 中的一个条目（`entryId` / `moduleName` / fiber phase），一个 bundle 可以插入多个 entry。
  - **壳 / 原生壳**：本仓库 Android Java 侧。
  - **缓冲区（shell-side state buffer）**：壳为了离线可用与响应性，在 DSH 实际状态之外维护的展示/调度投影栈。

## 2. DSH 对插件的定义与管理机制

DSH 的权威入口是锁定运行时的包说明与实现，而不是 DSHA 文档的转述。以下把“上游语义”与“DSHA 受管补丁”分开标注：`lib/types/*` 与包 README 作为上游语义，`lib/index.js` 中含 `DSHA_*` marker 的段落作为本机受管差异。

### 2.1 实体定义

| 实体 | 定义 | 关键事实 |
|---|---|---|
| profile | `$DSH_HOME/profiles/<name>`；`package.json` 保存 `dsh.profile.bundles` 顺序与外部依赖，`cordis.patch.yml` 是用户 patch 层 | `dsh` 启动器把 profile 视为“有序的 plugin-bundle patch 层栈 + 用户覆盖”；无 HMR 时改动重启生效（`@deepseek-ai/dsh/README.md:5,34-46`） |
| bundle | 声明 `dsh.bundle`（patch 文件或列表）的 npm 包；安装到 profile 依赖 | bundle 的 patch 按 `dsh.profile.bundles` 顺序叠加，然后才是 profile patch 与 home patch（同上 `:42-46`） |
| plugin entry | Cordis Loader 非 group 条目；`entryId`、`moduleName`、有效启用、root fiber phase | inventory 每次调用直接读 Loader，不保留第二份生命周期真相；`disposed` 折叠为 `null`，不区分“从未启动”或“已销毁”（`dsh-host-plugin-inventory/README.md:32,42,56,62`）；`cordis.entry` 是 Loader entry 的 symbol，不是 package.json 字段（`cordis-plugin-loader/lib/index.js:439`） |
| 客户端插件 | `dsh.client{platform,inject,external,immediately}`，由 client-modules 扫描宿主 Loader entries 组装 | 不是 bundle，不受插件管理器启停；原生只能看到 bundle 侧 |
| 兼容性 | `engines.dsh` 优先，缺省时逐个 `@deepseek-ai/dsh*` peer；`workspace:*` 视为当前版本；精确版本豁免在 profile 的 `compatibility.json` | 安装前/启动期两处独立检查；豁免只表示用户接受风险（`plugin-manager/README.md:74-83`） |
| 动态/会话插件 | 会话内 dynamic Cordis runner 管理的临时条目 | 不是 npm 持久安装，不能被当安装包导出或恢复（`plugin-runtime-and-permissions.md:62-66`） |
| 官方/内置 | `OFFICIAL_BUNDLES`、`OPTIONAL_BUNDLES`、`PROTECTED_PLUGINS`、Official on-demand catalog | 受管/受保护条目有 `readOnlyReason`，插件管理不能禁用自身或改动其他 profile（`plugin-manager/README.md:44,56,178`） |

### 2.2 操作矩阵（DSH）

| 操作 | 入口 | 语义要点 |
|---|---|---|
| 列表（plugins） | `plugin_manager list_plugins` / `pluginInventory` / Web 插件页 | entry 级：`entryId`、模块名、有效启用（含祖先 group 禁用）、fiber phase、失败诊断、展示 meta、`patchId` 或 `readOnlyReason`；点读、无缓存（`plugin-manager/lib/index.js:2271-2297`；inventory README `:30-44`） |
| 列表（bundles） | `list_bundles` / `listBundles` | bundle 级：`availability`（profile/installation/missing）、`installed`、`enabled`、`official`、`optional`、`removable`、`readOnlyReason`、`listedByDefault`、`installTarget`、`source`、`error`、`rows`/`overrides`、`updatable`；`enabled` 只表示保存的选择，不表示加载成功（`plugin-manager/README.md:44-48`） |
| 安装 | `install_bundle` / `installBundle` | 校验 spec（registry/git/tarball/本地目录）、bundle 声明、平台与 DSH 版本/peer 兼容；pnpm 运行前检查 npm/GitHub 可达；失败/取消恢复 `package.json` 与 `pnpm-lock.yaml`；可带 `enabled:false`；构建脚本默认需批准（`pendingBuilds`/`approvedBuilds`）（`plugin-manager/README.md:44,54,60,68,72,77`） |
| 卸载 | `remove_bundle` / `removeBundle` | 仅可移除的 bundle；先从 `dsh.profile.bundles` 取消选择并卸载运行插件，再 `pnpm remove`；受管 bundle 拒绝；失败保留已完成步骤、不自动重启用（`plugin-manager/README.md:54,178,195`） |
| 启停（bundle） | `set_bundle` / `setBundleEnabled` | 改 `package.json` 的有序 `bundles`；禁用保留依赖；启用追加到末尾会改变配置优先级；管理 bundle 受保护（`plugin-manager/README.md:40,44`） |
| 启停（entry） | `set_plugin` / `setPluginEnabled` | 只改 `cordis.patch.yml` 中最后一个匹配 override 的 `disabled`，没有则追加；当 bundle 层与剩余 patch 已经隐含目标状态时删除该最小 override；home/launch patch 不参与该比较（`plugin-manager/README.md:40`；`lib/types/patch.js:20-77`） |
| 版本检查/更新 | `listVersionExemptions` / `listVersions` / `updateBundle` | 精确已发布更高版本；先 `pnpm view` 检查存在、声明 bundle、兼容；HMR 下“移除 → 安装 → 失败回装”，无 HMR 覆盖安装并报告 `restart-required`；更新状态事件 `checking/removing/installing/restoring`（`plugin-manager/README.md:56-58,173`） |
| 版本豁免 | `set_version_exemption` / `version-exemptions` CLI | `compatibility.json` 精确 `package@version` ↔ 精确 DSH 版本；授权需 `acceptRisk`；不改变依赖/选择/patch；启动期仍独立预检（`plugin-manager/README.md:74-83`） |

### 2.3 结果与状态语义

DSH 把一次管理操作表达为“已保存变化 + 应用结果”，而不是一个布尔成功：

- `application`：`applied` / `restart-required` / `failed` / `cancelled` / `overridden`；`restartReason`：`no-hmr` / `package-moved` / `host-module` / `unmanaged-plugin` / `module-comparison`（`plugin-manager/lib/types/tools.js:35-80`）。
- `stage`：`install` / `enable` / `remove` / `check` / `restore` 等；失败保留已完成阶段与真实剩余状态（`plugin-manager/README.md:130,190-195`）。
- 兼容性拒绝码 `incompatible-version` 携带每包的 `name/version/runtimeVersion/requirements`；平台拒绝 `unsupported-platform`；安装失败码含 `already-installed`、`not-bundle`、`not-removable`、`management-required`、`unaddressable` 等（`plugin-manager/lib/types/tools.js:9-30`；README `:44,56-58,77-83`）。
- **“选择了 / 安装了 / 启用了 / 加载了 / 需要重启”是五个不同事实**：`enabled` 是保存的选择；`availability`/`installed` 是内容事实；`application` 是本轮操作结果；fiber phase 是运行时事实；`restart-required` 是生效条件（`plugin-manager/README.md:44,48,81,130,195`）。

### 2.4 守护机制

| 守护面 | DSH 机制 | 证据 |
|---|---|---|
| 版本兼容 | 安装前按 `engines.dsh`（缺省时全部 DSH peer 范围）判定；不兼容在 pnpm 前拒绝；启动期独立预检并 fail-safe 跳过/禁用；精确版本豁免 | `plugin-manager/README.md:77-83`；`runtime-data-plugin-architecture.md:344-350` |
| 平台 | bundle 平台声明保存/安装/自动激活前检查，拒绝 `unsupported-platform` | `plugin-manager/README.md:42` |
| 受管/只读 | `PROTECTED_PLUGINS`、管理 bundle、preset 行不可管理 | `plugin-manager/README.md:31,44,178` |
| 写锁 | profile `package.json` 的 `withFileLock`；原子替换；死持有者接管 | `dsh-atomic-write` 实现；`plugin-manager/lib/index.js:3413` |
| 构建批准 | pnpm 构建脚本默认需显式批准；`pendingBuilds` + `approvedBuilds`；批准持久化于 profile | `plugin-manager/README.md:72` |
| 失败不重放 | 安装失败恢复 manifest/lockfile；取消、超时、未知退出保留真实现场；不自动重试有副作用步骤 | `plugin-manager/README.md:130,183-197` |
| 运行时加载 | Loader 自身维护 fiber；inventory 点读；HMR 串行重放配置；无 HMR 报告重启 | inventory README `:42,56-62`；`plugin-manager/README.md:14,124` |

## 3. DSHA 壳的现状

### 3.1 代码链路

```text
PluginFragment / PluginInstallActivity
  → PluginManagementEngine（应用级调度：probe、TTL、投影、前后台）
    → PluginRepository（单线程队列、guest 调用、回执、LiveData）
      → PluginBackendAdapter（宿主路径/来源身份/FileObserver 目标）
      → PluginRegistry（不可变展示快照）
      → PluginRuntimeProjection（心跳运行证据）
      → GuestPluginScripts / ProotBootstrap
        → plugin-manager.py（list/install/delete/update/export/safe-mode…）
          → plugin-lifecycle.py / plugin-transactions.py / plugin-dependencies.py
          → 锁定 DSH profile + pnpm + 运行时
```

- `PluginFragment` 只 observe `PluginRepository.State` 并提交意图（`ui/PluginFragment.java:326-343,681-689`）。
- `PluginManagementEngine` 不直接读写插件：它调度 probe、目录查询、运行投影、前后台与监听（`plugins/PluginManagementEngine.java:45-154,169-217,524-880`）。
- `PluginRepository` 串行执行；查询共享环境租约，变更独占；每次请求有 operationId、进度/取消票据（`core/PluginRepository.java:966-1014,1081-1139`）。
- guest 统一 `PLUGIN_RESULT` 末行 JSON 作为唯一结果（`plugin-manager.py:310-312`）。

### 3.2 原生操作矩阵

| 操作 | 入口 | 完成证据 | 与 DSH 对齐的要点 |
|---|---|---|---|
| 刷新/读取 | `refresh` / `refreshAutomatic` / `probeSource` | guest `refresh`/`list` 回执 + 来源代次复核；失败保留旧列表标 stale | 目录快照守卫（revision 双读）；不把文件事件当证明 |
| 安装 | `install(PluginSource)` / `importArchive(Uri)` | 预览 `plugin-previews/<32hex>` → 确认 `install-preview` → 回执 `committed` | 校验 bundle patch/入口/路径/依赖/摘要；事务回切；生命周期脚本按现行策略开放 |
| 更新 | `checkUpdates` / `prepareUpdate` / `rollback` | `plugin-updates.json` 精确绑定 installedVersion；预览实际版本必须 `> installed`；回滚走 `plugin-history` | 版本检查与回滚能力有，但语义与 DSH 的 `listVersions/updateBundle` 不同（见 §4.4） |
| 卸载 | `delete(Item)` | `removed` + `deletionVerified` 读回；原件进保留区 | NOFOLLOW 对象摘要；共享 store/外部源不删 |
| 启停 | `setEnabled(Item, boolean)` | `name/enabled/intentVerified` 读回；先发布意图，再独立刷新 | 只写“意图”，不承诺组件 ready 或 loader 已卸载 |
| 导出 | `exportArchives` | guest `export` ok + 拉回文件成功 | 剔除凭据/运行时；系统插件字节不冒充用户源码 |
| 安全模式 | `safeMode` | 批量停用 + 指纹；恢复按指纹 | 用户显式禁用不被批量恢复覆盖 |
| 守护 | `PluginChangeDetector`、心跳、`PluginActivationHooks`、维护屏障 | 见 §3.5 | 以“失效/证据”而非“成功”建模 |

### 3.3 壳侧状态缓冲的六层

| 层 | 生产者 | 内容 | 权威性/窗口 | 证据 |
|---|---|---|---|---|
| L0 profile 事实 | DSH / guest 写者 | `package.json`（bundles/dependencies）、`cordis.patch.yml`、`node_modules/*.disabled`、`compatibility.json`、pnpm 锁/CAS | 最终落盘事实；读取需按文件语义解释 | `register-builtin-plugins.py:456-486,741-826`；`plugin-manager.py:1074-1161` |
| L1 目录快照 | guest `plugin-manager.py list/refresh` | `items`（name/version/enabled/builtin/official/runtimeProvided/updatable/loadState/update…）+ `safeMode`/`pendingReviews` | 一次请求的只读快照；revision 双读，不一致即失败保留旧列表 | `plugin-manager.py:1015-1071,1074-1161` |
| L2 展示快照 | `PluginRegistry` + `PluginRepository` | `version/invalidationSeq/verifiedAtMillis/source/known/loading/stale/error/items/pendingReviews/safeMode` | 进程内；目录 TTL 60 s；失败保留旧行并标 stale | `plugins/PluginRegistry.java:12-44,167-223`；`PluginManagementEngine.java:25,826-846` |
| L3 运行心跳 | `startup-observer.cjs` + `app-integration/runtime-plugins.js` | `.dsha-web-activity.json`：generation/instance/at/idle/`installedPlugins[{name,version,profile,state}]`/动态 `plugins` | 每 2 s 覆盖；同代/实例/30 s 内才采纳；过期→unknown/stale | `app-integration/index.js:19-52`；`runtime-plugins.js:63-117`；`PluginRuntimeProjection.java:22,38-155` |
| L4 激活账本 | `plugin-lifecycle.py` + `PluginActivationHooks` | `plugin-activations.json`：`queued/attempted/loaded/failed/changed/disabled`、fingerprint、version、startup | 跨启动持久；版本匹配才可作历史证据；不代表当前 Web | `plugin-lifecycle.py:460-530`；`PluginActivationHooks.java:77-150` |
| L5 操作回执 | `PluginRepository` / `PluginOperationQueue` | operationId、type、desired、expectedVersion、phase、percent、transactionId | 进程内；只更新精确目标；UNKNOWN 保留 | `PluginOperationQueue.java:30-95`；`PluginRegistry.java:226-270` |
| L6 UI 卡片 | `PluginCardState` / `PluginFragment` | 来源 kind、配置/运行徽章、重启/更新/消息提示条 | 纯推导，不读盘；不新增事实 | `util/PluginCardState.java:41-133`；`ui/PluginFragment.java:1090-1135,1293-1320` |

**读/写不总是同一份数据**：`State.items` 与 `catalog.items` 通常同源，但 `PluginRepository` 另有 volatile `items` 字段用于目标核验；查询提交窗口内两副本可能短暂不一致，`adoptCatalog` 负责回填（`core/PluginRepository.java:56-66,1020-1057,1328-1337`）。这是“缓冲区”在代码层的直接表现。

### 3.4 时序窗口

| 窗口/阈值 | 值 | 作用 | 证据 |
|---|---|---|---|
| 心跳写入 | 2 s | 采样器只枚举 Loader，不重读目录 | `app-integration/index.js:48` |
| 运行投影采纳上限 | 30 s | 过期 → unknown；不把卡顿当结论 | `PluginRuntimeProjection.java:22,144-155` |
| 运行投影重读 | 10 001 ms | 到期只重读投影，不触发目录查询 | `PluginManagementEngine.java:526,673-701` |
| 目录快照 TTL/失败重试 | 60 s | 插件页可见时周期核对；失败 60 s 后才重试同源 | `PluginManagementEngine.java:25,826-862` |
| 变更合并 | 200 ms 首次、最长 2 s、最短 750 ms 扫描间隔 | 文件事件只标失效，不推导成功 | `plugin-management-modularization-20261025.md:47`；`PluginChangeCoalescer.java` |
| 自动查询暂停 | 120 s | 用户启动/重启优先，避免自动查询插队 | `PluginManagementEngine.java:205-221` |
| 启动让路等待 | 3 s | 有界等待自动查询退出 | 同上 `:208-216` |
| 协作查询预算 | 30 s（单次操作另有长预算） | 查询/短意图命令的协作取消；不承诺硬实时 | `plugin-runtime-and-permissions.md:54-56`；`GuestPluginScripts.java` |

### 3.5 守护清单（原生）

- **环境租约与维护屏障**：查询共享租约；安装/删除/更新/回滚/加载确认独占；维护未完成直接拒绝（`PluginRepository.java:1129-1139,1188-1200,1564-1589`；`PluginActivationHooks.java:107-129`）。
- **事务与保留**：`PluginTask` 进度/取消；`PluginInstallJournals` 未提交日志进维护门禁；`PluginJournalRecovery` 恢复需维护所有权与固定 proof；删除 NOFOLLOW 并保留原件（`backup/PluginInstallJournals.java:22-80`；`backup/PluginJournalRecovery.java:21-40`；`plugin-transactions.py:49-283`）。
- **来源身份**：逐段 NOFOLLOW、marker SHA-256、ino/dev/代次双读（`plugins/PluginBackendAdapter.java:126-165,176-303`）。
- **变更检测**：FileObserver 只报失效；前台观察、后台释放；有目录/深度/枚举上限（`plugins/PluginChangeDetector.java:17-22,87,164-165`）。
- **意图/实际分离**：启停只写意图并读回；加载只由“同代 + 实例 + 版本匹配”的心跳确认；`restartRequired` 独立维护（`PluginRegistry.java:272-336`；`PluginRuntimeProjection.java:57-85`）。
- **安全模式**：批量停用第三方插件 + 指纹；关闭时只恢复指纹未变的插件（`plugin-lifecycle.py:722-774`）。

### 3.6 DSHA 对 DSH 的受管补丁（对齐面）

锁定运行时本身带有 DSHA 受管补丁，它们也是“壳 ↔ DSH”的缓冲层，必须与上游版本一起评估：

- `plugin-manager-policy-patch.json`：`DSHA_PLUGIN_UNLOCKED_PNPM_V2`（生命周期脚本策略）、`DSHA_PLUGIN_HTTPS_GITHUB_V1`、`DSHA_PLUGIN_SINGLE_INSTALL_V1`、`DSHA_PLUGIN_PROVENANCE_V2`。
- `plugin-bundle-proof-workers-patch.json`：`DSHA_PLUGIN_BUNDLE_READ_WORKERS_V2`（两路有界 proof 读取）、`DSHA_AUTO_REVIEW_CANONICAL_CARD_V1`。
- `plugin-inventory-entries-patch.json` / `plugin-manager-entries-patch.json`：`DSHA_PLUGIN_ENTRIES_PROJECTION_V1` / `DSHA_PLUGIN_LIST_ENTRIES_ONLY_V1`（只投影 live Loader entries，不做 preset 审计）。
- `plugin-inventory-management-*.json`：`DSHA_PLUGIN_MANAGEMENT_*_V1`（零参数 `pluginInventory/management()` 能力探针）。
- `plugin-manager-pending-patch.json`：`DSHA_PLUGIN_PENDING_VIEW_V2` / `DSHA_PLUGIN_PENDING_V2` / `DSHA_PLUGIN_REFRESH_REUSE_V1` / `DSHA_PLUGIN_REFRESH_BARRIER_V1`（Web 端变更的 pending/refresh 边界）。
- `plugin-manager-auto-enable-patch.json`、`plugin-pnpm-argv-patch.json`、`plugin-manager-management-probe-patch.json`。

这些补丁把 Web 插件页的“变更中/刷新屏障/只读探测”与 DSHA 的原生管理面接在一起；**壳侧状态补全不能假设未打补丁的 DSH 语义**，必须把补丁 marker、版本与降级路径一起纳入。

另外，两层 wire 不是同一 schema：DSHA 给宿主 `BundleInfo` 注入的 `origin`（`dsh-managed|user`）不在 Typert codec 中，Web 客户端解码时会丢弃；proof/受管来源只存在于原生/宿主面。反过来，Web 的 `fiberPhase`/`patchId`/`readOnlyReason` 也没有进入原生 `Item`。补全时必须显式标注字段属于哪一面，不能假设两边同名即同义。

## 4. 对齐矩阵与差距

评级：✅ 已对齐；🟡 部分对齐（语义可用但字段/证据不完整）；🔴 未对齐（会误报或无法表达）。

### 4.1 定义与实体

| 维度 | DSH | DSHA 现状 | 评级 | 证据 |
|---|---|---|---|---|
| bundle 定义 | `dsh.bundle` + patch 文件、profile 选择 | guest 校验 patch/入口/依赖；原生不识别 bundle | 🟡 | `plugin-manager.py:433-464`；`plugins/PluginRepository.java:149-260` |
| plugin entry | `entryId/moduleName/patchId/fiberPhase/diagnostic` | 无 entry 实体；按包聚合 | 🔴 | `PluginRuntimeProjection.java:38-117`；`runtime-plugins.js:63-117` |
| 来源分层 | installation/profile/official/user；proof | 原生按签名清单与 proof 重算 `builtin/official/runtimeProvided/runtimeLocated/origin` | ✅（分层）/🟡（字段） | `PluginRepository.java:191-200`；`plugin-manager.py:1120-1145` |
| profile | 多 profile；当前 profile 由管理面注入 | 只处理 web；`Item.profile` 默认 `web`，guest list 不产出 profile | 🟡（范围受限） | `PluginRepository.java:216`；`PluginRuntimeProjection.java:49,65` |
| 动态/会话插件 | 会话内 dynamic runner，只读摘要 | 心摘要 + 只读卡片；不导出/不恢复 | ✅ | `PluginRuntimeProjection.java:86-115`；`app-integration/runtime-plugins.js:120-136` |

### 4.2 安装

| 维度 | DSH | DSHA 现状 | 评级 | 缺口 |
|---|---|---|---|---|
| spec 解析 | registry/git/tarball/本地目录 | npm/GitHub/tree/blob/HTTPS 归档/本地归档；无绝对本地目录（走导入） | 🟡 | 来源面不同，需在文档/UI 明示 |
| 包契约 | bundle patch、平台、兼容性、`main`（声明时） | 合法名/semver、bundle patch、`main`/`pkg.cordis.entry` 存在、路径/摘要/依赖（`pkg.cordis.entry` 是壳的近似检查，上游无该 package 字段） | ✅（包）/🔴（兼容性阻断） | 兼容性只作提示，不阻止安装 |
| 预览/确认 | `install_bundle` + `pendingBuilds` 批准 | 预览 + 摘要确认 + `pendingReviews` | ✅（事务）/🟡（构建批准） | 现行策略允许生命周期脚本，`pendingBuilds` 无对应状态 |
| 安装后启用 | 默认启用；可 `enabled:false` | 默认启用；无“安装即禁用”入口 | 🟡 | 显式选择保留能力缺失 |
| 失败恢复 | 恢复 manifest/lockfile，保留已完成步骤 | 事务回切 + 未提交日志门禁 + 未知退出保留 | ✅ | — |
| 平台检查 | `unsupported-platform` | 无（Android 固定 arm64，但无 DSH 平台声明检查） | 🟡 | 若插件声明不支持 linux-arm64，原生不阻断 |

### 4.3 卸载

| 维度 | DSH | DSHA 现状 | 评级 | 缺口 |
|---|---|---|---|---|
| 可移除判定 | `removable`、`readOnlyReason=management-required`、受管 bundle 拒绝 | `deletable` 由 guest 计算；official/system/runtimeLocated 不可删 | 🟡 | 无 `readOnlyReason` 原因码；管理 bundle 依赖 builtin 名单，不是 DSH 语义 |
| 行级/包级 | `remove_bundle` 移除包 + bundle；不能删 entry | 删除包安装 + 登记；保留其他插件与共享依赖 | ✅（方向）/🟡（粒度） | entry 级无独立删除 |
| 原件保留 | 失败保留已完成步骤；诊断日志保留 | 移到保留区 + NOFOLLOW；用户可复核 | ✅ | — |
| 外部源 | 不删除外部工作区 | 删除只断开私有引用 | ✅ | — |

### 4.4 更新

| 维度 | DSH | DSHA 现状 | 评级 | 缺口 |
|---|---|---|---|---|
| 版本发现 | `listVersions`：最多 N 个更高版本，带 dist-tag/时间/预发布/弃用/兼容性 | `check-updates`：按来源取最新版（npm/GitHub），semver 比较，写 `plugin-updates.json` | 🟡 | 不能列版本、不能选版本；来源语义不同 |
| 目标选择 | `updateBundle(name, exactVersion)`；旧版拒绝 | `prepare-update` 下载来源最新包，实际版本必须 `> installed` | 🟡 | 不能固定版本；不能从 registry 选历史版本 |
| 兼容性阻断 | 更新前检查 engines/peer，不兼容停止且 profile 不变 | `compatibility` 字段只是提示；`prepare-update` 不阻断 | 🔴 | 可能安装后在启动期被跳过 |
| 版本豁免 | `compatibility.json` 精确豁免 | 无 | 🔴 | 不兼容插件无法在原生侧显式豁免 |
| 结果语义 | `application/restartReason/stage`；HMR 下 remove+install+restore | `restartRequired` 布尔 + 文案；覆盖安装保留上一版 | 🟡 | 缺 stage/reason/回滚结果结构 |
| 回滚 | 更新失败自动回装；手动回滚走安装/版本 | 上一版 `plugin-history`，手动回滚预览 | ✅（能力）/🟡（语义） | 回滚不按 DSH 失败恢复语义表达 |

### 4.5 读取

| 维度 | DSH | DSHA 现状 | 评级 | 缺口 |
|---|---|---|---|---|
| 库存事实 | 每次读 Loader，点读、无缓存 | 目录快照 + 心跳投影 + 激活账本；有 TTL/合并 | 🟡（有意缓冲） | 缺契约与证据等级；词表不同 |
| entry 状态 | `fiberPhase` + 失败诊断 | bundle 级 `runtimeState`（取最坏子状态） | 🔴 | 无法定位具体 entry/reason |
| 配置意图 | bundle 选择 + entry patch override | 只读 bundle 选择 + `.disabled` marker；忽略 `cordis.patch.yml` | 🔴 | Web entry 禁用原生不可见 |
| 更新状态 | `updatable`/`listVersions`/兼容性 | `updatable`/`updateAvailable`/`latestVersion`/`compatibility`（UI 未消费 compatibility） | 🟡 | 字段有但缺消费者/语义 |
| 失败历史 | fiber 诊断；inventory 不保留历史 | `plugin-activations.json` 历史 + 启动诊断 | ✅（历史）/🔴（归因） | `failed-load` 统一标记本轮所有 attempted |
| 管理能力 | `managementAvailable` 探针 | 未消费 | 🔴 | 原生不知道 Web 管理面是否可用 |

### 4.6 守护

| 维度 | DSH | DSHA 现状 | 评级 | 缺口 |
|---|---|---|---|---|
| 兼容性 | 启动/安装/更新预检；跳过/禁用；精确豁免 | 仅更新提示字符串；启动期由 DSH 自行跳过，壳不记结构化状态 | 🔴 | 缺 `compatibility_blocked` 状态与原因 |
| 受管保护 | `PROTECTED_PLUGINS`、`readOnlyReason` | builtin/official/deletable 近似 | 🟡 | 缺 `management-required`/`unaddressable` |
| 构建批准 | `pendingBuilds`/`approvedBuilds` | 现行策略允许脚本；无批准状态 | 🟡（策略差异需明示） | 用户无法看到“脚本已运行”的证据 |
| 锁/事务 | profile 写锁 + 原子替换 + 失败恢复 | 共用 `package.json.lock`；事务/NOFOLLOW/保留区 | ✅ | — |
| 运行时归因 | fiber 诊断、`overridden`、`unmanaged-plugin` | 只有启动诊断/文案；无 per-entry 诊断 | 🔴 | 见 §5.4 S7 |
| HMR | 有 HMR 时 `applied`；无 HMR `restart-required/no-hmr` | profile 写 `patchReload: startup`，但 0.2.2-rc.1 无读取该字段的代码；一律重启的根因是未启用 HMR | ✅（策略）/🟡（结果结构） | 不能把 `patchReload` 当作已生效开关；`restartReason` 缺省为布尔 |

## 5. 缓冲区调查

### 5.1 为什么必须保留缓冲

去掉壳侧缓冲会让原生页在以下场景不可用或高成本：

1. Web 未启动/维护中/停止后，原生仍要显示插件与启停意图；
2. guest 全量目录查询要读 profile、proof、node_modules、activation/update 文件并有 30 s 协作预算，不适合每次 UI 重建都执行；
3. 运行证据必须绑定 generation/instance，不能靠“文件存在”推断；
4. 用户启动/重启 DSH 时，自动查询要主动让路。

另一个方向也不通：DSH 只暴露 Typert RPC（`POST /api/<namespace>/<method>`，需 loopback cookie），没有 REST；原生壳不能直接调用 `pluginManager/*` 或 `pluginInventory/*`。现有跨面通道是 app-integration 写的心跳文件与受管补丁，因此缓冲区是通道约束下的产物，不是随意加缓存。

因此目标不是“去掉缓冲”，而是把缓冲变成**有契约的投影**。

### 5.2 状态副本与权威

| 编号 | 副本 | 回答的问题 | 不能回答的问题 |
|---|---|---|---|
| S0 | profile 文件 | 安装/选择/禁用意图/依赖是什么 | 当前进程是否已加载 |
| S1 | guest list | 目录事实 + 更新状态 + 历史激活状态 | 当前 Loader 的实时 phase |
| S2 | `PluginRegistry` | 本轮看到的目录快照（带版本/陈旧/错误） | 快照之后的变化 |
| S3 | 心跳 `.dsha-web-activity.json` | 当前 Web 进程的 bundle 级运行状态 | 未运行的 Web、entry 级身份、失败原因 |
| S4 | `plugin-activations.json` | 上次启动尝试的历史回执 | 当前 Web 是否加载 |
| S5 | operation queue | 本进程内的意图/进度/回执 | 跨进程持久结果 |
| S6 | UI 卡片 | 把以上投影成人类可读状态 | 新事实 |

### 5.3 词表不一致

| 语义 | DSH `fiberPhase` | DSHA sampler | 原生 `confirmedState`/UI | 问题 |
|---|---|---|---|---|
| 等待加载 | `pending` | `pending` | `pending`/`waiting` → 等待服务 | `waiting` 是旧协议残留，sampler 不产 |
| 加载中 | `loading` | `loading` | `loading` → 加载中 | 基本一致 |
| 正在卸载 | `unloading` | `loading` | `loading` → 加载中 | DSH 区分卸载中，壳折叠为加载中 |
| 运行 | `active` | `loaded` | `loaded/running/active` → 已加载 | 别名可接受，但需显式映射表 |
| 失败 | `failed` + diagnostic | `failed` | `failed` → 加载失败 | 缺诊断原因 |
| 无活根 | `null`（disposed，原因不区分） | `unknown` / `unloaded`（按 attempt/literal disabled） | `unknown`/`unloaded`/`disabled` | 壳比 DSH 多推断了“unloaded” |
| 配置禁用 | entry `disabled` | `unloaded`（literal） | `disabled` → 未加载 | 需要与 patch override 对齐 |
| 管理只读 | `readOnlyReason` | 无 | builtin/official 近似 | 无原因码 |

### 5.4 冲突场景（调查清单）

| 场景 | 触发 | 当前行为 | 风险/结论 |
|---|---|---|---|
| S1 Web 关闭某 entry | Web `plugin_manager set_plugin` 写 `cordis.patch.yml` | 原生 list 不读 patch，仍显示“已启用”；心跳可能显示“未加载” | **误报“已启用 + 未加载”**；用户无法知道是 entry 被 patch 禁用 |
| S2 原生禁用后 Web 再启用 | 原生写 `.disabled`（`DSHA_WEB_BUILTIN_DISABLED_V1` 等） | 受管补丁的 `setBundleEnabled` 检测 marker 并拒绝 Web 启用；原生 `marker_path` 读回 | ✅ 已对齐（有意保护），但需文档化为“唯一原生入口优先” |
| S3 更新检查与 Web 更新并发 | 原生 `check-updates` 写 `plugin-updates.json`；Web `updateBundle` 改 profile | 原生 `updateAvailable` 以 `installedVersion` 相等为条件；并发后可能变旧/消失 | 显示旧更新状态；需重读并标 timestamp |
| S4 兼容性启动跳过 | 插件 engines/peer 不满足当前 DSH | DSH 启动期有结构化 `skippedBundles`（bundle 级）与行级禁用警告；壳未消费，原生可能显示“已启用”，运行态 unknown/未加载 | **缺 `compatibility_blocked` 与原因**；用户只看到“未加载” |
| S5 多 entry bundle | 一个 bundle 插入多个 entry，其中部分禁用/失败 | sampler 取最坏子状态（unknown > failed > unloaded > pending > loading > loaded） | 一个坏 entry 会把整包显示“未加载/失败”；缺 entry 级展开 |
| S6 心跳过期 | Web 事件循环长任务 >30 s | 运行态 unknown/stale；UI 待核验 | ✅ 已对齐（不把卡顿当结论） |
| S7 启动失败归因 | Web 启动失败，`failed-load` 执行 | guest 把本轮所有 `attempted` 条目标记 `failed/LOADER_OR_BROWSER_FAILED` | **误伤**：未失败的插件也显示加载失败；需 per-entry 归因或 unknown |
| S8 同版本内容替换 | 插件源码/依赖被替换但版本未变 | 运行证据只比 version/origin；同版本可沿用 loaded | 可能误报已加载新内容；需内容摘要/TTL 补偿 |
| S9 非 web profile | 其他 profile 心跳/目录 | `Item.profile` 默认 web；心跳按 profile 键匹配 | 原生不管理非 web；需明确非目标 |
| S10 受管补丁降级 | DSH 升级后补丁链不匹配 | 启动失败/诊断；`pluginInventory/management()` 探针不可用 | 原生无“管理面不可用”状态；需补丁链状态/降级提示 |

### 5.5 根因

1. **实体模型缺一层**：DSHA 用“包”承载 bundle + entry + 动态条目三种语义，导致状态只能聚合。
2. **证据没有携带来源与等级**：`Item` 有 `runtimeStale`/`runtimeGeneration`，但没有 `evidence=loader|heartbeat|ledger|none`、`observedAt`、`entryId`；UI 无法区分“从未运行”和“运行证据过期”。
3. **忽略 patch 层**：`plugin-manager.py` 的 revision 与 list 都不含 `cordis.patch.yml`；原生 Java 也没有该文件的读取模型。
4. **结果语义是自定义的**：`PluginOperationQueue.Phase` 与文案替代了 DSH 的 `application/stage/restartReason`，让“恢复/回滚/覆盖/失败”难以和 DSH 对齐。
5. **守护归因粗**：启动失败按轮次统一标记，兼容性跳过没有结构化回执；DSH 的 per-entry fiber 诊断没有进入壳的模型。

## 6. 状态补全设计

### 6.1 原则

1. **缓冲是投影，不是第二权威**：任何新增字段都必须标注来源层与证据等级；壳不写 profile，除非是已有事务入口。
2. **五个事实分开**：安装内容、profile 选择、禁用意图、当前运行 phase、需要重启/生效结果，分别建模，不合并成一个 `enabled`/`loaded` 布尔。
3. **未知必须显式**：`UNKNOWN`/`STALE` 保留旧值与原因，不降级为空列表或“未安装”。
4. **证据必须可过期**：所有运行时结论带 generation/instance/observedAt；跨代作废。
5. **词表以 DSH 为母集**：壳可以有更少的状态，但不能有无法映射回 DSH 的状态名；别名必须集中映射。
6. **只更新精确目标**：操作回执只覆盖对应 item/entry；目录刷新失败不回滚已确认意图，成功刷新也不抹掉更新鲜的运行证据（现有 `retainRuntime` 方向正确）。

### 6.2 统一视图字段（建议）

在现有 `Item` 之外增加/补齐（字段名暂定，实施时再定）：

| 组 | 字段 | 来源 | 说明 |
|---|---|---|---|
| 身份 | `kind` = BUNDLE / ENTRY / SESSION_ENTRY | L1/L3 | 区分包与条目 |
| | `bundleName`, `entryId`, `moduleName`, `patchId` | L1 + 心跳扩展 | entry 身份；bundle 可为空 |
| | `profile`, `scope` | L1 | 目前只有 web/application |
| 来源 | `origin`, `availability`, `source`, `proof` | L1/L0 | availability: profile/installation/missing；proof: verified/user-unverified/unavailable |
| 意图 | `selected`（bundles） | L0 | 保存的 bundle 选择 |
| | `entryDisabled`（tri-state: true/false/unset） | L0 patch | 只解析字面 `disabled`；`!!js` → unset |
| | `nativeMarker`（none/user-disabled/safe-mode/web-disabled/review-required） | L0 markers | 现有 4 种 marker 内容 |
| | `effectiveIntent` = ENABLED / DISABLED / BLOCKED | 推导 | 带 `intentBlockedReason` |
| 内容 | `installed`, `version`, `installedVersion` | L0/L1 | 区分“依赖声明”与“实际可读版本” |
| | `compatibility` = COMPATIBLE / INCOMPATIBLE / UNKNOWN + requirements/exempted | L1 扩展 / DSH 语义 | 采用 DSH 的 engines+peer 评估；缺省时可标 unknown |
| | `management` = NORMAL / READ_ONLY_MANAGEMENT / UNADDRESSABLE / PROTECTED | L1 扩展 | 对齐 `readOnlyReason` |
| 运行 | `phase` = PENDING / LOADING / UNLOADING / ACTIVE / FAILED / ABSENT / UNKNOWN | L3 | 与 DSH `fiberPhase` 一一映射；`ABSENT` 对应 null |
| | `phaseEvidence` = LOADER / HEARTBEAT / LEDGER / NONE | L3/L4 | 证据等级 |
| | `observedAt`, `generation`, `instance`, `stale`, `staleReason` | L2/L3 | 过期原因（EXPIRED/GENERATION/INSTANCE/NO_ACTIVITY/…) |
| | `diagnostic`（脱敏、短） | L3 扩展 | fiber 失败原因 |
| 操作 | `application`, `restartReason`, `stage`, `errorCode` | L5 | 对齐 DSH 结果结构 |
| 缓冲 | `revision`, `sourceEpoch`, `verifiedAtMillis` | L2 | 现成 Registry 字段 |

### 6.3 映射表（必须集中实现并配单测）

**运行 phase**：

| DSH | 统一 phase | 说明 |
|---|---|---|
| `pending` | PENDING | — |
| `loading` | LOADING | — |
| `unloading` | UNLOADING | 不再折叠为 LOADING |
| `active` | ACTIVE | UI 文案“已加载” |
| `failed` | FAILED | 带 diagnostic（若有） |
| `null` | ABSENT | 不推断原因；若配置明确 disabled 且有 literal 证据，另由 intent 表达 |

**应用结果**：

| DSH | 统一字段 | 说明 |
|---|---|---|
| `applied` | application=APPLIED | 无 HMR 时几乎不出现 |
| `restart-required` | RESTART_REQUIRED + restartReason | DSHA 未启用 HMR，默认 `no-hmr`（`patchReload` 字段当前无消费者）；安装/更新/回滚要尽量给出原因 |
| `failed` | FAILED + stage + errorCode | 失败阶段与码 |
| `cancelled` | CANCELLED | 取消并恢复 |
| `overridden` | OVERRIDDEN | 更高优先级层覆盖（若未来启用 preset） |

**配置意图优先级**（建议显式表）：

```text
effectiveEnabled = selected(bundle)
  AND NOT entryDisabled(entry)
  AND NOT anyNativeMarker(name)          // user-disabled / safe-mode / web-disabled
  AND NOT compatibilityBlocked
```

- `review-required` 是历史兼容标记，单独映射为 `BLOCKED(review-required)`，不并入 user-disabled。
- `web-disabled` 与 `user-disabled` 的差别只在来源，展示都应是“已禁用”，但可给原因 chip。
- `compatibilityBlocked` 必须以 DSH 启动期的结构化回执为准，不能仅凭版本字符串。

### 6.4 缓冲契约（建议写入文档 + 单测）

1. **B1 证据等级**：`phase` 只有在 `phaseEvidence=HEARTBEAT` 且 `stale=false` 时可显示“已加载”；`LEDGER` 只能显示“上次激活记录”。
2. **B2 单一来源身份**：每次快照绑定 `source`（dataRoot/rootfs/marker digest/generation）；任一变化 → 旧展示只保留非运行时字段并标 stale。
3. **B3 窗口语义**：心跳 2 s 是写频率，不是新鲜度；投影采纳 ≤30 s；目录快照 ≤60 s；UI 不得把三者混成“现在”。
4. **B4 正交徽章**：配置徽章（intent）与运行徽章（phase）永远分开；冲突时两个都显示，并给原因 chip，不做“自动纠正”。
5. **B5 失败保留**：目录失败保留旧行并标 stale + error；不回滚已确认意图；不把失败当空列表。
6. **B6 精确更新**：操作回执只更新目标 key；`updateRuntimeItems` 不凭空发现新安装；`recordConfirmedItems` 只用于已确认的 delete 列表。
7. **B7 entry 级证据**：只有 entryId+moduleName 匹配的 loader 证据才能驱动 entry 状态；bundle 聚合状态必须标注“聚合（最坏子项）”。
8. **B8 停止/无 Web**：Web 停止后 `ABSENT` ≠ “未启用”；没有当前心跳时运行态只能 `UNKNOWN/STALE`，除非有明确的进程停止回执。

### 6.5 补全点（按操作）

**读取**
- guest `cmd_list` 增加：`patchDisabled`（只解析字面 override，无法解析时 `null`）、`available` 枚举、`readOnlyReason`（若可判定）、`compatibility` 结构化（requirements/exempted）、`entryStates`（可选，来自账本/心跳而不是再读 loader）。
- 把 `cordis.patch.yml` 纳入 `list_state_revision` 的哈希输入（只读、无写锁）；并在 `observedPaths` 已有 `profiles` 递归观察的基础上，保证 patch 写入会触发失效。
- 原生 `Item` 增加 entry/patch/evidence 字段；`PluginCardState` 增加“配置：已启用（entry 被禁用）/运行：未加载”这种组合文案。

**安装**
- 预览阶段复用 DSH 兼容性规则：`engines.dsh` 优先，否则全部 `@deepseek-ai/dsh*` peer；不兼容时**阻止提交**或至少要求显式确认（与 DSH 的“拒绝”不同需要 Lead/用户裁决）。
- 平台声明检查（`dsh.bundle.platforms`）；不匹配时明确失败，而不是等启动期跳过。
- 记录构建脚本执行证据（当前策略允许脚本时，至少记录“已执行/跳过”），不引入 DSH 的 pendingBuilds 语义也可，但要让用户看到。

**卸载**
- 在 `Item` 增加 `readOnlyReason`（management-required / unaddressable / protected）与 `removable` 的 DSH 对齐判定；删除仍保留现有事务与 NOFOLLOW。
- 明确“entry 不可单独删除”，删除只针对 bundle/包。

**更新**
- 把 `check-updates` 的输出升级为 DSH 兼容结构：可列版本、目标精确版本、兼容性、`updatable` 原因；原生 UI 至少给出“可更新/不可更新原因/兼容性”。
- `prepare-update` 前做与 DSH 等价的兼容性检查；不兼容时停止且 profile 不变（当前会安装后由 DSH 跳过）。
- 结果带 `application/restartReason/stage`；失败/取消按阶段保留现场，不自动重放。
- 版本豁免是否进入原生：建议 P3 评估，默认不暴露写入口，避免绕过 DSH 的 `acceptRisk` 语义；至少读取 `compatibility.json` 并在 UI 显示“已豁免/未豁免”。

**守护**
- 启动观察器输出结构化 `failedPlugins[{name, entryId?, reason, missing}]`；`PluginActivationHooks` 只把有明确证据的 entry 标 `failed`，其余标 `unknown`/`failed-to-attribute`，不再整轮统一 `failed-load`。
- 捕获 DSH 启动期结构化 `skippedBundles` 与行级禁用回执（无法结构化时至少保留原文到诊断），映射为 `compatibility_blocked`；不要依赖 stderr 文案解析。
- 心跳扩展 entry 级 `{entryId, moduleHash, phase, diagnostic}`（有界、脱敏）；原生用 `phaseEvidence=LOADER` 显示 entry 状态，bundle 只做聚合。
- 把补丁链/管理面状态（`pluginInventory/management()`、runtime patches applied/retired）纳入诊断页的“插件管理面”小节；不得把 `managementAvailable=false` 当成“没有插件”。

### 6.6 明确不做

- 不把壳的 `PluginRegistry` 变成新的持久权威；不写 `cordis.patch.yml`/`compatibility.json`，除非复用 DSH 的明确入口。
- 不把“已安装/已启用”写成“已加载”；不把 `restart-required` 写成普通成功。
- 不因为心跳缺失就清空旧列表或自动重放安装/更新。
- 不管理非 web profile，也不试图替代 DSH 的 profile 机制。
- 不在原生侧放宽 DSH 的受管/只读/兼容性保护；策略差异（生命周期脚本）必须在文档中显式声明，不冒充已对齐。

## 7. 分切片计划（建议）

| 切片 | 内容 | 改动面 | 维护影响 | 验收 |
|---|---|---|---|---|
| **P0 契约**（零维护） | 本文 + `PluginStateView`/映射表纯逻辑 + 单测；UI 只读新映射 | `util/`、`ui/`、测试、docs | 不进 `launcherInputs`（`ui/PluginFragment.java`、`util/PluginCardState.java` 当前均不在） | JVM/Robolectric 定向测试；链接/格式门禁 |
| **P1 读取补全** | guest list 增加 patchDisabled/availability/readOnlyReason/compatibility；revision 纳入 patch；原生字段 + 卡片原因 chip | `plugin-manager.py`、`plugins/*`、`core/PluginRepository.java`、`ui/` | 进入 `launcherInputs`/`inputs` → 一次维护页 + runtimeId 更新 | 单测覆盖 patch override tri-state、别名、revision 变化、旧包兼容 |
| **P2 运行证据** | 心跳扩展 entryId/moduleHash/phase/diagnostic；原生 phaseEvidence/ABSENT；失败归因修正 | `startup-observer.cjs`、`app-integration/runtime-plugins.js`、`plugins/`、`PluginActivationHooks`、`plugin-lifecycle.py` | 运行资产 → 一次维护页 | 负控：过期/代次/实例/entry 不匹配→unknown；失败归因不误伤 |
| **P3 更新/兼容** | 兼容性阻断、版本列表/精确版本（至少字段与阻断）、application/restartReason/stage、豁免只读展示 | `plugin-lifecycle.py`、`plugin-manager.py`、`plugins/`、`ui/` | 运行资产 → 一次维护页 | 不兼容不提交；失败/取消阶段保留；回滚语义 |
| **P4 守护/补丁链** | 启动跳过/禁用结构化回执、补丁链/管理面状态展示、受管只读原因码 | `plugin-lifecycle.py`、`startup-observer.cjs`、`core/PluginActivationHooks.java`、`ui/` | 运行资产 + Java → 一次维护页 | 启动跳过→blocked；managementAvailable=false 可解释；不误判 |

- 每个切片都必须带“不重放 UNKNOWN”“旧快照保留”“意图/运行分离”的负向回归。
- P1 之后，修改 `plugin-manager.py`/`plugins/*`/`core/PluginRepository.java` 都会触发一次环境维护（它们已在 `runtime-descriptor.launcherInputs` 中；`app/src/main/assets/runtime-descriptor.json` 当前列出 `plugins/PluginRuntimeProjection.java`、`core/PluginRepository.java`、`plugin-manager.py` 等）。P0 只改 `ui/`/`util/` 文档可零维护，但**新增 util 文件仍需在实施时核对描述符生成规则**，不能从本表直接推断。
- 发布需用户当次授权；本设计不承诺 build363 排期。

## 8. 风险、未决问题与验证边界

### 8.1 风险

1. **同版本内容替换**：现有运行证据只按 version/origin 确认；若插件内容被替换而版本不变，可能沿用 `loaded`（`PluginRegistry.java:295-315`；`PluginRuntimeProjection.java:71-82`）。P2 需内容摘要或更严新鲜度。
2. **证据超限静默**：心跳 `installedPlugins>512`、动态 `>256` 会被视为“无证据”（`PluginRuntimeProjection.java:24,45,56`），用户只看到 unknown；需要诊断原因。
3. **UNKNOWN 靠文案**：`PluginRepository` 用 `message.contains("UNKNOWN"/"未确认"/…)` 分类（`core/PluginRepository.java:1235-1245`），guest 文案一改就可能把 unknown 降为 failed。P0/P1 应改为错误码。
4. **观察预算不可见**：FileObserver 有 128 路径/1024 目录/32768 项/深度 48 上限（`PluginChangeDetector.java:87,164-165`）；超限静默降级，`coverageLimited` 未接 UI。
5. **TTL 叠加**：目录 60 s + 心跳 30 s + 自动查询暂停 120 s 可让“刚在 Web 改动”在原生页最长约 2 分钟不刷新；需在 UI 显示“最近核对时间/陈旧”，而不是只显示状态词。
6. **补丁链脆弱性**：12 个插件相关 patch 与上游版本精确绑定；DSH 升级或补丁失配时，`pluginInventory/management()`、pending/refresh 屏障都会变化，壳必须能降级并解释。
7. **范围溢出**：把非 web profile、marketplace、preset composition 纳入会显著扩大维护面；建议明确非目标。

### 8.2 待裁决

- **Q1**：本文是否只作为设计基线（docs-only），还是批准 P0/P1 实施？
- **Q2**：安装/更新时遇到 DSH 不兼容，原生应“阻止提交”（贴近 DSH）还是“显式确认后仍安装”（保留 Android 离线修复能力）？前者对齐度高，后者用户可自救；建议默认阻止，保留“先安装再修复”的显式路径。
- **Q3**：生命周期脚本策略是否维持现状（允许）？若维持，是否需要把“脚本已执行”的证据补进 UI/诊断，而不引入 DSH `pendingBuilds` 批准链？
- **Q4**：版本豁免是否只在 DSH Web/CLI 提供，原生只读展示？建议是；原生写 `compatibility.json` 需另行安全评审（会绕过 `acceptRisk` 的双重确认语义）。
- **Q5**：entry 级状态是否必须（P2），还是先做 bundle 级原因 chip（P1）即可？若用户主要用 Web 管理，P2 可降优先级。
- **Q6**：是否最终把原生 Python 管理链收敛到 DSH `PluginManager`（Web 在线时）？这涉及离线可用与并发锁，建议另立设计，不在本次范围。

### 8.3 验证边界

本文未运行 Gradle、cargo、Python 回归或任何设备操作；`docs-only` 分析不构成构建或真机结论。实施时最低验证：

- P0：`PluginStateView`/映射纯 JVM 单测；`PluginCardState`/`PluginListPresenter` 既有测试；文档链接与格式门禁。
- P1：guest `test-plugin-*` 相关回归；`PluginRepository`/`PluginRegistry` 生命周期测试；patch override tri-state 负控；旧包（无新字段）兼容。
- P2：`test-plugin-runtime-evidence.mjs` 扩展；心跳过期/代次/实例/entry 不匹配负控；启动失败归因不误伤。
- P3：更新预览阻断、失败/取消、回滚与并发锁；不兼容负控；`test-plugin-transactions.py`。
- P4：启动跳过/禁用结构化回执；`managementAvailable=false` 展示；`test-plugin-management-patch-chain.py` 的补丁链正/负控。

## 9. 证据索引

| 主题 | 文件 | 关键位置 |
|---|---|---|
| DSH profile/bundle 定义 | `@deepseek-ai/dsh/README.md` | 5, 34-46 |
| DSH 管理操作与状态 | `@deepseek-ai/dsh-plugin-manager/README.md` | 14, 40, 44-48, 54-58, 72, 77-83, 130, 173-178, 190-197 |
| DSH inventory 点读 | `@deepseek-ai/dsh-host-plugin-inventory/README.md` | 32, 42, 44, 56, 62, 104-106 |
| DSH entry 列表 | `@deepseek-ai/dsh-plugin-manager/lib/index.js` | 2271-2297 |
| DSH bundle 列表 | 同上 | 2298-2500 |
| DSH 更新/版本 | 同上 | 2590-2780 |
| DSH 启停 patch | `@deepseek-ai/dsh-plugin-manager/lib/types/patch.js` | 20-77 |
| DSH 结果枚举 | `@deepseek-ai/dsh-plugin-manager/lib/types/tools.js` | 35-80 |
| DSH profile 写锁 | `@deepseek-ai/dsh-plugin-manager/lib/index.js` | 3413 |
| DSH 受管 marker 互操作 | 同上 | 3270-3330 |
| 壳模块链路 | `app/src/main/java/com/deepseekharness/app/plugins/`、`core/PluginRepository.java` | 类头与 149-260, 966-1139, 1434-1441, 2005-2065 |
| 壳展示快照 | `app/src/main/java/com/deepseekharness/app/plugins/PluginRegistry.java` | 12-44, 167-223, 226-270, 272-336 |
| 壳运行投影 | `app/src/main/java/com/deepseekharness/app/plugins/PluginRuntimeProjection.java` | 22, 38-155 |
| 壳调度/TTL | `app/src/main/java/com/deepseekharness/app/plugins/PluginManagementEngine.java` | 25-26, 197-221, 524-701, 826-862 |
| 壳路径/观察 | `app/src/main/java/com/deepseekharness/app/plugins/PluginBackendAdapter.java` | 126-165, 176-264, 274-303 |
| 壳变更检测 | `app/src/main/java/com/deepseekharness/app/plugins/PluginChangeDetector.java` | 17-22, 87, 164-165 |
| 壳操作队列 | `app/src/main/java/com/deepseekharness/app/plugins/PluginOperationQueue.java` | 30-95 |
| 壳激活确认 | `app/src/main/java/com/deepseekharness/app/core/PluginActivationHooks.java` | 77-150 |
| UI 状态词 | `app/src/main/java/com/deepseekharness/app/ui/PluginFragment.java` | 1090-1135, 1293-1345, 1690-1810 |
| 卡片推导 | `app/src/main/java/com/deepseekharness/app/util/PluginCardState.java` | 41-133 |
| guest 列表 | `app/src/main/assets/plugin-manager.py` | 310-312, 1015-1071, 1074-1161 |
| guest 启停 | 同上 | 1164-1198 |
| guest 更新/回滚 | `app/src/main/assets/plugin-lifecycle.py` | 161-180, 460-530, 627-721 |
| guest 安全模式 | 同上 | 722-774 |
| guest 事务/锁 | `app/src/main/assets/register-builtin-plugins.py` | 419-486, 741-826 |
| 启动观察器 | `app/src/main/assets/startup-observer.cjs` | 185-220 |
| 心跳/采样 | `app/src/main/assets/app-integration/index.js` / `runtime-plugins.js` | `index.js:19-52`；`runtime-plugins.js:29-117` |
| 受管补丁清单 | `app/src/main/assets/plugin-*-patch.json` | 见 §3.6 |
| 既有机制文档 | `docs/runtime-data-plugin-architecture.md` / `docs/plugin-runtime-and-permissions.md` / `docs/plugins.md` | 插件五状态、系统/用户分层、事务与恢复 |

---

### 附：一句话结论

**壳已经有一台“有界、可陈旧、不会自动重放”的状态缓冲器，但还没有一份“每个状态从哪来、能声称到哪一步、过期后显示什么”的契约；本次评估把它拆成了六层副本、五个 P1 缺口和四个可独立验收的补全切片。**
