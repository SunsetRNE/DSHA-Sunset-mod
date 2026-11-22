# T1：竖屏插件与分段加载补丁的加载核验（2026-11-15）

本次结论：**当前运行的 Web profile 没有加载竖屏插件；插件的全部受管部署文件与仓库一致，禁用标记阻断了加载。会话历史分页、分页节点容错补丁、前端 combo 缓存和资源 chunk 服务是宿主的独立链路，当前实例已有对应代码和实际资源响应。** 本次没有操作手机屏幕、启停插件、重启 Web、安装、修改正式配置或读取会话正文。

## 范围和身份

- 源码核验基线：`625b73aeb74891ab0ec0964b3bcccd64a9f716d0`；调查期间主线继续前进，本报告绑定该基线，不据此宣称更新后的主线已发布。
- 原生 `/app/version` 自报：应用 `io.github.sunsetrne.dsha`、build366、版本 `0.2.1-rc.5-20261114.1717-dsh0.2.2-rc.1`。
- 当前安装 DSH 自报 `0.2.2-rc.1`。应用版本、安装文件、当前 Loader 和 HTTP 响应分别取证，没有仅凭版本号推导运行时内容。
- 对当前服务 `http://127.0.0.1:43425` 进行只读 HTTP 核验。未登录根页面返回 401；未提取登录 token 或浏览器 cookie。插件脚本资源由服务正常提供，可以独立核验。
- 采集日期：2026-11-15，Asia/Shanghai。结果是当次实例状态，后续用户启停或刷新需要重新取证。

本次容器中的位置如下，供同机复核；下文的仓库链接均指调查基线内的源码。

```text
profileDirectory = /root/.dsh/profiles/web
mobileSource = /root/dsha-web-mobile
mobileDisabledMarker = /root/.dsh/profiles/web/node_modules/dsh-web-mobile.disabled
mobileEnabledAlias = /root/.dsh/profiles/web/node_modules/dsh-web-mobile
runtimeNodeModules = /usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules
```

## 结果矩阵

| 对象 | 源码/部署核验 | 当前实例证据 | 可下的结论 |
|---|---|---|---|
| `dsh-web-mobile 3.3.5` | 11 个受管安装文件全部逐字节一致，含齿轮几何/单实例、安全区、文件传输和多语言补丁 | 存在空禁用标记；无启用别名；当前 226 条 Loader 插件记录没有该插件；单插件资源返回 404 | 已部署，当前未加载 |
| 会话历史分页 | DSH 0.2.2 的 `Session` 窗口和 `loadOlder` / `loadThrough` 实现 | API Session Controller 与 Chat 的当前单插件脚本均 HTTP 200，按宿主正常打包转换后与磁盘产物逐字节一致 | 当前服务提供分页实现；尚未验收浏览器实际翻页 |
| `DSHA_CONVERSATION_MATERIALIZED_WITHDRAWAL_V1` | 现行 recipe 与构建入口已接线 | 实际 Conversation 脚本 HTTP 200；recipe 的完整替换段唯一存在，旧 throw 消失；隔离行为核验通过 | 当前服务确实提供分页相关容错补丁 |
| `DSHA_COMBO_CACHE_V1` | 8 处替换段均在已安装服务端模块；helper 与仓库逐字节一致 | 当前模块服务可正常返回版本化脚本和 chunk | 缓存实现已部署；本次未测缓存命中率或提速幅度 |
| 包内按需 chunk | 宿主 ClientModuleRegistry 的版本校验和按需读取路径 | Terminal 与 Excel 的独立 chunk 均 HTTP 200，响应与磁盘经过正常打包转换后的字节完全一致 | 按需资源服务可用；未推导为浏览器已触发或执行 |

## 竖屏插件的阻断点

### 默认策略与实际状态

[内置注册表](../../app/src/main/assets/builtin-plugins.json#L11)包含移动插件，没有 `defaultDisabled`。版本固定为 3.3.5，上游 main 提交为 `646fe837440f9939228bdba0024d407eaac01128`，见[部署 manifest](../../app/src/main/assets/builtin-plugins/dsh-web-mobile/package.json#L118)与[上游锁](../../tools/mobile-plugin-upstream.lock.json#L3)。

按照[受管安装表](../../app/src/main/assets/managed-runtime-inputs.json#L211)筛选移动插件的全部安装行，共 **11** 个文件，当前容器目标与本工作树资产逐字节一致：manifest、Cordis patch、图标、两份插件元信息 locale、四份原有运行 JS、LICENSE，以及本地追加的文件传输 JS。源码树的 243 个发布成员与这 11 个运行安装项属于不同集合，不能把未安装 TypeScript 源码或 source map 当成运行资源遗漏。

关键摘要：

```text
lib/client.js: 776505 bytes
sha256: 3ddfe212c83e79f3c4eca75c670af291ee7ac9c8cc0a6d6d48e43d334e946cf8
lib/index.js sha256:
dcddbce73ec1052b7c02e961bac1389d227f72f1ef49f62a2f16f692e8f930ab
```

实际状态相互印证：

1. 禁用标记是普通文件，长度 0；启用别名与历史 enabled 记录均不存在。
2. `plugin_manager list_plugins` 完整读取 226 条当前记录，没有移动插件的活动 row。
3. `plugin_manager list_bundles` 显示该 bundle `enabled=false`、`installed=true`、`availability=missing`、`rows=[]`。其声明来源仍指向正常存在的移动插件目录。这里的 `missing` 与禁用后摘除加载别名相符，不能直接据此认定源码缺失或要求重装。
4. `/app/plugins` 上次上报中没有移动插件，FAILED/PENDING 为空。该接口是历史上报快照，只作辅助证据，不能替代当前 Loader 查询。
5. 使用宿主 `artifactRevision` 规则计算当前部署文件的 revision 后，请求移动插件的独立脚本资源返回 404。其它活动插件的同类请求返回 200，见下表。

### 空禁用标记不能追溯为某一次用户操作

[注册器 is_disabled](../../app/src/main/assets/register-builtin-plugins.py#L746)按标记是否为文件判断；[注册主流程](../../app/src/main/assets/register-builtin-plugins.py#L1183)跳过禁用项，随后[优先移除旧加载路径](../../app/src/main/assets/register-builtin-plugins.py#L1211)。恢复默认启用不会清理已有标记。

[原生显式禁用](../../app/src/main/assets/register-builtin-plugins.py#L1078)和[历史默认禁用持久化](../../app/src/main/assets/register-builtin-plugins.py#L1127)都会生成空文件。**当前证据无法区分用户主动禁用与 build327 等历史策略留下的标记。** Web 自身专用禁用标记有独立内容，见[精确标记判别](../../app/src/main/assets/register-builtin-plugins.py#L754)，当前空文件不属于该格式。

[插件说明](../plugins.md#L86)中“全新安装与覆盖更新都默认启用”应结合后续禁用优先规则理解：默认值适用于没有既有禁用标记的 profile；不能据此保证旧 profile 自动恢复启用。本次不更改该意图。

### 现行与退役补丁

[移动客户端补丁生成器](../../tools/apply-mobile-client-patches.mjs#L44)保留宿主集成、文件传输与多语言；已部署客户端包含 `DSHA_GEAR_FIT_V1`、`DSHA_GEAR_SINGLETON_V1`、安全区变量和文件传输/locale 标记。

会话输入 V4、旧手机搜索/快捷键弹层、字体族行、遮罩染色等旧补丁已经按策略退役，见[现行补丁取舍](../web-mobile-patches.md#L107)。缺少这些旧标记属于预期，不作为加载失败证据。重复齿轮应遵守[单实例约定](../mobile-sidebar-settings-gear.md#L19)，不通过重复登记或禁用插件处理。

## “分段加载”的三种不同机制

### 会话历史窗口

当前服务提供的 `@deepseek-ai/dsh-api-session-controller` 客户端代码中：

```js
const HISTORY_PAGE_OPTIONS = {
  maxMessages: 500,
  turnWindow: { minMessages: 50, minTurns: 2 }
};
const JUMP_PAGE_OPTIONS = {
  ...HISTORY_PAGE_OPTIONS,
  turnWindow: { ...HISTORY_PAGE_OPTIONS.turnWindow, minMessages: 200 }
};
```

这些是分页请求的上限与对齐目标，不等于“每页固定 50 条”，也不保证内容不足或触及上限时仍能满足目标。`loadOlder()` 仅在 session 已 open、`hasMore` 为真且没有进行中的加载时调用 `events.prepend({ beforeSeq: baseSeq, ...HISTORY_PAGE_OPTIONS })`；`loadThrough(seq)` 为跳转逐页补齐，并核对 generation 和页边界是否前进。

当前 Chat 脚本包含 `hasMore`、`loadingOlder` 与 `loadEarlier` 绑定；这些实现由宿主提供，移动插件未加载不会删除它们。本次未读取真实会话历史，也未在浏览器中点击“加载更早内容”，因此不声称特定长会话已完整翻页、滚动锚点已验收或所有历史故障均已修复。

### 分页期间节点撤回的容错

[Conversation recipe](../../app/src/main/assets/conversation-materialized-patch.json#L3)处理 definition 曾有节点、下一次暂时返回 null 的情形：保留相同 key 并改为 hidden，避免旧实现抛出 `withdrew materialized target` 后中止 event-feed 订阅。它本身不制定分页大小。

[离线运行时 builder](../../tools/build-dsh-runtime.py#L151)核对目标模块、DSH 版本和唯一锚点后替换；该补丁烘焙在离线归档中，未列在通用 active overlay 中不代表漏接线。[专项测试](../../tools/test-conversation-materialized.mjs#L24)原本还会实际解出归档检查 marker。

本次已安装产物与实际 HTTP 响应均包含替换后的代码。另对 recipe 的实际代码段执行隔离核验：旧段在节点撤回时抛错；新段保留 stable key、写 hidden 且不修改旧 visible 对象；已 hidden 对象复用身份；初始 null 不产生 upsert；正常新节点仍被发布。这是局部行为控制，不替代完整浏览器事件链验收。

### 资源 combo 缓存与按需 chunk

[Combo recipe](../../app/src/main/assets/client-combo-patch.json#L5)与[缓存 helper](../../app/src/main/assets/client-combo-cache/index.js#L9)复用相同 revision、记录顺序、bundle、source map 和 clientPath 的上轮结果，覆盖 bootstrap、application 与单插件脚本。它不新增会话分页或切换移动端布局。[RuntimeTools](../../app/src/main/java/com/deepseekharness/app/runtime/RuntimeTools.java#L1298)还会安装 helper 并幂等重放该补丁。

当前服务端模块中的 8 处 recipe 替换段全部存在，helper 与仓库摘要一致：

```text
sha256: 982c0cdce192d71274b679983ee19549d0089f88a22bdd6177857124ab6de0e7
```

包内 chunk 的按需读取、revision 校验与路由是宿主 0.2.2 的既有实现。Terminal 和 Excel 两个 chunk 的真实 HTTP 200 证明当前服务可提供它们；没有在用户页面触发导入，也没有据此报告首屏性能提升、缓存命中率或页面内存数据。

## 当前 HTTP 取证

响应按宿主 `prepareSource` / `buildComboScript` / `comboScript` 的正常转换核对：移除原调试尾注、补结尾换行、追加 `;\n` 与当前 sourceMappingURL。下面 5 项 HTTP 200 响应均与预期字节完全一致，Cache-Control 均为 `public, max-age=31536000, immutable`；移动插件的 404 无响应正文，不计入字节一致的 5 项。resource revision 来自当前文件的 mtime/ctime/size，与宿主规则一致；它不是 SHA-256。

| 资源 | revision | HTTP | 响应 bytes | 响应 SHA-256 |
|---|---|---|---|---|
| 移动插件单插件脚本 | `e5c5a3d5e4b7` | 404 | 0 | — |
| Conversation 单插件脚本 | `6a7b3a0bbb2d` | 200 | 941641 | `2b7c6551f50b1b5fcc1142a5d35c9ddf15fd6eb7502d0239634c48cb198de68a` |
| API Session Controller 单插件脚本 | `5925144f48d5` | 200 | 168195 | `84ef370f12d447d5d7f454b9156655c5eabbb288b655279d7196b8e45c0a1f4a` |
| Chat 单插件脚本 | `84a664493d63` | 200 | 688976 | `e1932c2ab457a6baa9dac049dde8522c1349bdda3a3115dd0572fd5b87e55f16` |
| Terminal chunk | `e650d99365f5` | 200 | 686941 | `517d532c3249264c3c5eaf8fd1271ffebd031465a6220b89c79453e5a2cac002` |
| Excel chunk | `11a81c0651d3` | 200 | 7060590 | `e2063794a0e56e6ce26e21f3ae027924e561ac6ed757819a01206cd12318ebaf` |

当次资源路径如下；revision 会随文件身份改变，不应把旧地址作为将来的固定探测 URL。

```text
/plugins/??dsh-web-mobile/client.js&rev=e5c5a3d5e4b7
/plugins/??@deepseek-ai/dsh-client-ui-conversation/client.js&rev=6a7b3a0bbb2d
/plugins/??@deepseek-ai/dsh-api-session-controller/client.js&rev=5925144f48d5
/plugins/??@deepseek-ai/dsh-client-ui-chat/client.js&rev=84a664493d63
/plugins/@deepseek-ai/dsh-client-ui-sidebar-terminal/client.terminal.js?rev=e650d99365f5
/plugins/@deepseek-ai/dsh-client-ui-sidebar-documentpreview/client.excel.js?rev=11a81c0651d3
```

## 已执行验证与边界

```bash
node --test --test-concurrency=1 \
  tools/test-mobile-client-patches.mjs \
  tools/test-mobile-gear-contract.mjs \
  tools/test-mobile-update.mjs
# 20/20 通过，无跳过

node --test --test-concurrency=1 \
  tools/test-mobile-server-patches.mjs \
  tools/test-mobile-locales.mjs
# 8/8 通过，无跳过

python3 -B tools/generate-builtin-plugins.py --verify
# 通过

python3 -B tools/verify-runtime-descriptor-source.py
# 只读子任务执行通过：199 assets、515 launchers；6 个固定归档 deferred
```

只读子任务另对 builder 的 `recipe_inputs()` 与现有输入回执核对：91 项输入、版本 `0.2.2-rc.1` 一致。Conversation 与 Combo recipe 均进入受管身份，见[资产清单](../../app/src/main/assets/managed-runtime-inputs.json#L530)。未发现本范围 recipe 漏接线或部署资源遗漏的证据。

本工作树未还原离线运行时归档，也没有准备 raw/managed npm 测试夹具，因此**没有运行**需要这些输入的完整 Conversation 归档测试与 Combo 上游对照测试。归档 deferred 不写成归档内容已验；当前实例的实际 HTTP 核验也不代替 APK 完整构建、所有机型或已打开浏览器页面的执行验收。没有运行 Gradle、制作 APK 或覆盖安装。

## 最小后续处置

1. 若用户希望恢复移动壳，通过原生插件管理页正常启用该插件；协调各活跃会话后再正常重启 Web，并整页刷新客户端。该步骤涉及正式运行状态，本次调查未执行，也不手工删除禁用标记或重装正常源码。
2. 恢复后核对当前 Loader 只存在一个移动插件入口、boot graph 只存在一个移动插件 entry、版本化脚本 HTTP 200；再验收竖屏布局、抽屉/文件入口、键盘安全区与同一 footer 行最多一颗齿轮。
3. 用用户选定的长会话另做交互验收：首次窗口、加载更早内容、跳转旧回合、分页与投影同时更新、刷新后的状态恢复；只记录脱敏结果。保留本次确定的宿主分页链，按具体现象进一步定位。

本任务交付调查证据，无产品代码或正式运行配置变更。
