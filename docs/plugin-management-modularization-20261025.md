# 插件管理模块化与列表响应性改造

## 基线与授权

本轮在 Linux/aarch64 工作区 `DSHA-Sunset-mod-3090-Q` 原分支实施。2026-10-25 核实：分支 `Sunset-3090-Q`，HEAD `fddc062442b0b79a33be00dad92078e79e48ec8f`；源码 versionCode 196、versionName `0.2.1-rc.3-20261024.1835-dsh0.2.1-rc.3`，正式默认 applicationId `dsh.android.client`，Sunset CI 使用 `.SunsetRNE` 独立测试签名身份。开始时已跟踪文件无修改；未跟踪 `app/libs/`、`artifacts/` 保留。只运行 Standard。未获得本轮设备安装/操作授权，不沿用历史设备验收。用户允许修改代码及当前私有分支 CI/tag 历史发布能力。

## 已核实事实（源码调用链，B 级证据）

- 原生插件管理是 `PluginFragment`；DSH Web 插件管理有独立 list/management 协议与运行插件，不把两页当同一 UI。
- `DshaApp` 已持有应用级 `PluginRepository`，已有单线程 IO 和内存列表，没有本轮新增的持久目录缓存。
- `GuestScriptQueue` 已串行调度底层脚本；用户第二个 mutation 仍因 `working` CAS 被拒绝，两者不是同一个队列。
- 启停已有严格意图回执检查、目标更新和合并刷新；列表已有纯进度不重算优化，但仍用全量 adapter 通知。
- Fragment 本地刷新修订号随重建丢失；导出/能力查询成功也会追加全量刷新；提交前获取环境凭据、进度读文件、取消写文件和完成清理存在主线程 I/O。
- 旧 PluginListAudit/PluginSortAudit/PluginUiInstrumentation 仍取 Activity 仓储，与生产不一致。列表 position 0 是页头。旧“只解析”设备审计实际可能自动提交安装，不能按只读执行。
- 实际数据位置须经 Android authority、UserDataLayout 与 NOFOLLOW 路径解析；Web generation 只表示 Web 意图代次，不能替代数据树出生身份。

## 设计与阶段

使用现有 Android 工程内的 `plugins` 包：当前协作者依赖 Android 生命周期和已有执行后端，暂不增加 Gradle module。

1. **目录与入口**：`PluginManagementEngine` 统一前台/READY 预取及按过期状态核对；`PluginRegistry` 保存不可变、带版本、来源身份和失效序号的展示投影；`PluginBackendAdapter` 解析真实宿主数据位置与源身份。`PluginRepository` 保留 guest 兼容接口和现有事务实现。
2. **列表渲染**：保留页头、条目、页脚布局与入口；条目采用明确稳定身份及内容版本，DiffUtil/payload 发出增加、变更和移除。保存搜索、过滤、排序、展开与滚动锚点，合法重排按现有排序规则执行。
3. **变更检测**：FileObserver 只标记可能变化，经有截止时间和最小扫描间隔的合并器调度；扫描期间的新失效保留。恢复前台、环境重绑、原生完成及加载核验事件补偿。文件通知不作为成功或健康证明。动态运行心跳不每两秒触发全量扫描。
4. **用户任务队列**：有界单消费者，任务有 operationId、目标身份、desired、来源和前提、阶段及结果。等待不持环境/运行凭据，队首后台复核后获取。只合并未开始的同目标启停；安装/删除/更新/回退不覆盖。进度、取消文件和清理均在后台；回执成功与后续扫描失败分别记录。

## 状态与兼容边界

observed 是最近完整查询或有效执行回执确认的安装/配置事实；desired 是队列中的用户意图；operation 是排队/执行/提交/成功/失败/待核验；runtime 依据实际代次内加载证据，不能从 enabled 或文件事件推断。

持久条目按 profile/scope/origin/明确规范名区分；会话临时插件保留完整 generation/sessionId/pluginId。仅沿用现有明确官方 alias 归并规则。Registry 不写 profile、禁用标记或事务日志，不成为新权威。

保留 EnvironmentTaskGate → RuntimeTasks → 维护屏障顺序、NOFOLLOW、原件保留、提交前完整校验、取消边界及 UNKNOWN 不重放。预取与列表刷新不初始化环境，不启动/重启 Web。安装后原有恢复 Web 行为需额外核对用户停止意图/代次。

首版目录和用户队列为进程内状态，不承诺跨进程自动执行；进程死亡后重新核验事实，已有 guest 事务/恢复门禁保持，不自动重放未知安装、删除或更新。SAF 输入执行时重新打开验证可读性；不保存凭据或敏感源正文。

## 验证与回滚计划

按阶段运行确定性单测/真实 Repository 生命周期测试及相关 guest 事务/恢复回归。Standard Android 编译、JUnit、Lint 和实际 APK 范围绑定本轮提交与 CI 产物，旧 artifacts 仅作历史。记录失败原件与修正后的结果，不能把 source-only 或宿主测试写成真机验证。

性能只记录实际扫描/guest 调用/列表重绑与主线程工作证据；区分首次可见、缓存展示、扫描完成、提交核验、实际加载，不编提升比例。

回滚以本轮分阶段提交的逆序 revert 为单位，保留原基线和用户文件；本轮不迁移真实插件数据格式，不自动写入新的持久缓存或恢复队列。

## 实施记录

- 基线调查完成；实现进行中。最终验证、提交与限制将在完成后更新。
