# 内置 DSH 0.2.2-rc.5 升级实现与补丁处置

本实现基于 `2cc4e646f8ab232eef0070c9d844b806241caf6e`，工作分支 `task/dsh-rc5`，面向 build370 开发候选，未发布。只更新内置正式 DSH 的依赖、功能补丁和专项回归；独立 recovery-runtime 锁与归档保持 rc.1。Android 版本/身份、最终 runtime-descriptor、受管资产证明及固定输入发布锁由主代理集成，不属于本提交。

## 精确输入和产物

- npm 根包：`@deepseek-ai/dsh@0.2.2-rc.5`，官方 tarball `https://registry.npmjs.org/@deepseek-ai/dsh/-/dsh-0.2.2-rc.5.tgz`。
- 根包 integrity：`sha512-cNbp12tLYLUTXaVli7zwwMjzmmEVCD1PRldg4oKtXM4+lFYckcaliMS2PU/FoThB4iAN/fKOSID0nrtRZU4Gjw==`。
- `tools/dsh-runtime/package-lock.json` SHA-256：`846261e1dc2248197d6b442218539c00629e66b5c012f2eb7a4a62355829df84`；全部直接 DSH 包精确锁 rc.5。
- 四个上游已不发布 rc.5 的 agent-team 实验包从直接依赖移除：`dsh-experimental-agent-team`、`dsh-experimental-agent-team-profile`、`dsh-experimental-client-ui-agent-team`、`dsh-experimental-tool-agent-team`。这是包退休，不计为功能补丁退休。
- Node 24.19.0、npm 11.17.0；候选在本工作树 `app/build/rc5-candidate` 通过 `npm ci --ignore-scripts --no-audit --no-fund --os=linux --cpu=arm64 --libc=glibc` 安装，未执行生命周期脚本。
- 最终正式运行时候选：`app/src/main/assets/dsh-runtime.bin`，186,783,624 字节；SHA-256 `f85d88b86a2bc1698225f4f7f6814e5ab639301950a3f206bba3b48ae03c439c`；49,453 文件、758,645,027 展开字节。来源记录在相邻 `dsh-runtime.inputs.json`。二进制按仓库惯例不入 Git。

## API 与功能变化

rc.5 客户端改用紧凑 JavaScript 打包（包含模板字符串），许多旧锚点失配只是格式变化。本次仅在离线审计时使用 token/AST 帮助定位；生产补丁仍保存唯一的完整 `before/after` 原字节，`runtime_stable_patches.py` 的严格匹配没有放宽。全部 active 配方绑定真实 raw 模块 SHA-256、包版本和 npm integrity；完整链重复应用与源漂移拒绝也实测。

真实 API 变化分别处理：

- `Tools.checkGuards()` 返回结构化拒绝 `{reason, info}`，保留新拒绝信息，只在允许分支前调用必要的 Android 原生 consent 准备。
- `PluginManager.listBundles()` 的升级能力由旧 `updatable` 改为 `update: target.kind`。异步受管来源核验保留该 rc.5 返回形状；有界双 worker 使用当前 rc.5 完整行投影，不把旧循环覆盖回去。
- MCP 连接由旧 transport 调用改为 `record.wire.connect(resolved, remaining())`；计时包装保留 resolver、剩余启动预算和 `listTools` 的 signal。
- `dsh-deepseek-account-platform` 已合并到 `dsh-deepseek-account`。诊断与同一凭据/headers/lifetime 的 profile single-flight 移到实际新模块，不创建旧包别名。
- Web 搜索新增官方集中凭据解析和 account token rejection callback。适配保留官方解析器与拒绝回调，不自行重排凭据优先级；解析器内部错误只给固定的 credential-service 诊断类别，不冒称已观察具体存储源。
- rc.5 上传客户端仍存在 Uint8Array→base64 RPC、无界流和 worker postMessage 抛错泄漏，故保留并迁移上传适配。新图片 `originalDimensions` / `name` 与 receipt 生命周期保持上游实现。
- rc.5 工具卡片可从原 block 推导 read/diff/web 等语义卡片；仅换工具名哨兵不能阻止动作。新增投影原始 identity 标记，在 `String(id)` 前记录非法来源；`ToolCall` 与 `GenericToolCard` 显式使用纯文本只读渲染，禁止 named slot、typed card、inspect、openFile 和 image loader。合法记录继续沿用原 block、slot、inspect 与子调用行为。

## 原 74 项登记补丁逐项处置

`retain` 表示功能仍必要且严格原始语义锚点可用；`adapt` 表示为 rc.5 原字节或 API 重写；`retire` 表示不再进入当前执行链；`historical-only` 保留已退役记录，不恢复执行。下表不把静态入链检查冒充全部行为测试。

| 原配方/专项 | 处置 | 功能与证据/保留原因 |
|---|---|---|
| web-fetch-http-surrogate | retain | raw 仍按 UTF-16 slice 截断；真实 HTTP body surrogate 边界回归通过 |
| web-fetch-output-surrogate | retain | 正文/输出/截断页脚仍可能切开代理对；真实函数回归通过 |
| permission-full-access | adapt | 保留用户显式当前会话+新会话默认持久化与回读；新 minified picker 精确适配，Android 授权独立 |
| retire-guarded-preset | retain | 历史 preset 可读但不能新选；不能抹掉旧数据 |
| startup-mcp-timing | adapt | rc5 resolver/startup budget/signal 全保留，计时只观察不重试 |
| mcp-image-base64 | retain | 大图正则避免重复组导致的堆栈问题，长度/解码校验仍由原链承担 |
| mcp-version-negotiation | retain | 默认 auto 不变，显式 legacy 避免 stdio discover sibling |
| welcome-notice | adapt | 上游仍无有界核实与session-only退出等待，保留未知写入不重发 |
| composer-enter | adapt | 普通回车换行、IME DOM提交及显式Ctrl/Cmd发送仍为移动宿主需求 |
| conversation-stream-cancel | retain | 真实raw watchdog未唤醒用户abort；managed正负控通过 |
| deepseek-stream-cancel | retain | raw SSE response pipe不接收signal；managed取消body与原reason实测通过 |
| session-interaction | adapt | 保留触控/拖动取消/键盘单次打开边界；不恢复旧双击策略 |
| queue-agent | retain | idle的next-turn队列仍需followup唤醒，running时保留steer |
| queue-dock | adapt | 与host队列策略配套，busy门槛保留 |
| persona-compat | retain | legacy text→prefix仍无上游兼容，保留用户原文 |
| subagent-navigation | adapt | coarse pointer不触发悬停，明确点击/原导航保留 |
| plugin-manager-policy | adapt | 受管来源证明、禁止删除内置包、一次安装不自动重放；保留rc5 update.kind |
| plugin-pnpm-argv | retire | 原先先把flag塞到options.args再搬回args的双层绕行已移除；新版policy直接追加command args，不声称上游吸收 |
| plugin-manager-auto-enable | adapt | 新安装默认启用，显式selection=false仍保持 |
| office-fonts | retain | Android只读/system/fonts路径与普通Linux分支保持分离 |
| deepseek-messages-compat | retain | Messages工具调用/结果结构投影仍需；真实serialize和历史拒绝回归通过 |
| rc1-settings-migration | retain | 旧settings文档事务迁移仍必要，历史用户数据不可因新版本删除 |
| language | adapt | 原生语言与网页语言桥接，明确用户选择仍写回 |
| frontend-static-cache | retain | 上游拥有ETag/HEAD，DSHA只补列表/通配弱比较及动态HTML no-store |
| sidebar-layout-recovery | adapt | 错误布局保留原件后降级，避免纯渲染失败丢失布局 |
| tool-stream-identity | retain | 晚到工具ID/name仍需修复；真实adapter与assembler测试通过 |
| tool-block-identity | retain | 空delta不擦有效身份，不伪造可执行ID；真实回归通过 |
| tool-dispatch-validation | retain | malformed step落诊断并在派发前停止；真实recovery测试通过 |
| tool-dispatch-validation-pipeline | retain | 全批次identity预检在首事件/副作用前；真实混合批次零派发通过 |
| tool-messages-history | retain | 历史坏协议只读拒绝，不改写保存记录；真实serializer通过 |
| tool-history-display | adapt | 新typed fallback不能靠name哨兵防护，改明确无动作纯文本路径；精确bundle正负控通过 |
| guard-tools | retain | 强制策略owner绑定；实际ToolsService路径回归通过 |
| guard-tools-pipeline | adapt | 新structured denial信息保留；17个真实consent pipeline用例通过 |
| guard-bash | retain | 捕获环境/cwd与派发前原生授权事实复核；真实受控shell adapter回归通过 |
| guard-fs | retain | 写/编辑intent绑定实际调用、保留原文件；真实回归通过 |
| guard-editor | retain | create/edit intent拒绝不被编辑器入口绕过 |
| retired-market-optional | retain | 本Mod不重新提供已撤下market入口，非上游移除声明 |
| retired-market-catalog | retain | 合并catalog来源不能重新带回旧market |
| auto-review-native-disabled | retain | 原生禁用标记优先，official/wrapper只显示同一控制项 |
| deepseek-native-oauth | adapt | embedded页面保持，固定原生授权导航；普通浏览器popup流程原样 |
| directory-picker-service | retain | picker独立生命周期，保留真实后端能力和Gateway授权 |
| config-editor-lock-order | retain | 上游仍HMR→file ABBA风险；新HMR runExclusive合同审读，实际本机补测因native SIGSEGV未通过 |
| plugin-manager-pending | adapt | mutation完成前不让旧read当确认，状态/通知顺序与新page保留 |
| agent-preset-pending | adapt | 保存/回读/当前空白任务同步分离；失败不复用旧default |
| plugin-inventory-entries | retain | listPlugins只读entries不额外跑preset审计，failed fiber诊断保留 |
| plugin-manager-entries | retain | 消费新独立entries投影，仍用同一Loader事实 |
| plugin-inventory-management | retain | 零参数严格capability RPC，list全量契约仍保留 |
| plugin-inventory-management-host-codec | retain | host严格codec匹配新增方法，不放宽unknown输出 |
| plugin-inventory-management-remote-codec | retain | remote严格codec与host一致 |
| plugin-inventory-management-api-codec | adapt | rc5 aggregate符号$11→$12，精确包命名锚点更新 |
| plugin-manager-management-probe | adapt | 页面只需管理能力时不触发全量审计 |
| plugin-bundle-proof-workers | adapt | 当前rc5行投影+最多2完整proof读取；实际本机补测native SIGSEGV未通过 |
| deepseek-account-requests | adapt | 旧platform模块入口退役，迁入新account真实模块；不改变令牌/HTTP格式 |
| deepseek-account-client-diagnostic | adapt | 嵌入控制台仅固定字段单字符串，不泄露凭据 |
| deepseek-web-search-headers | adapt | 保留rc5官方resolver、reject callback；本地Header验证与HTTP未确认分类保留 |
| file-upload-capacity | retain | 服务端单文件5e9、真实字节计数、临时清理、原imageLimits不变 |
| file-upload-binary-client | adapt | 真实rc5负控证明仍需要；二进制、5e9流拒绝、worker清理正控通过 |
| file-deliverables | adapt | 原生下载需准确session/path，普通browser保留上游行为 |
| file-image-actions | adapt | 仅原生同源blob才显示保存相册动作 |
| user-only-skill-approval | retain | user-only技能需本次明确审批，不由全局mode冒充授权 |
| first-connect-indicator | adapt | 页面首连静默，真实断开/恢复仍显示 |
| pdf-compat | adapt | rc5 minified module/resource URL锚点更新；WebView116兼容仍宿主需要 |
| browser-bootstrap | retain | 文档/worker最早注入ES兼容与宿主桥，未安装当前运行客户端 |
| client-combo | retain | 当前实际combo构建锚点仍精确命中，内容身份缓存不改变响应 |
| lan-settings-persistence | historical-only | 当前rc5上游Host describe/mutate承担持久化；新proof与Java代次门由独立集成线负责，不复用旧SHA |
| workspace-directory-policy | retain | 仅匹配DSHA环境事实时默认/root/Documents，显式目录优先 |
| agent-preset（已退役） | historical-only | 上游只读preset session heading继续由上游拥有 |
| models-navigation（已退役） | historical-only | 模型设置导航由上游拥有，不恢复rc1 UI |
| web-integration/tooltip（已退役） | historical-only | 0.1.5组件替换不进入当前runtime |
| lexical-claim（已退役） | historical-only | rc5仍atomic CommandChipNode；新raw完整SHA已绑定，不恢复splitText/TOKEN_STYLE |
| file-upload-resolver-lifecycle（已退役） | historical-only | resolver稳定data对象及当前receipt生命周期留给上游 |
| conversation-large-image-file（已退役） | historical-only | 图片仍走二进制uploadImage+imageLimits，不恢复20MiB降级 |
| tooltip-interaction（已退役） | historical-only | 上游pointer/touch tooltip模型仍负责，不应用旧entry补丁 |
| tooltip-entry（已退役） | historical-only | 已退役tooltip脚本的cache-buster不再使用 |

新增 `tool-history-projection-identity-patch.json`：在原始事件ID被字符串化前记录validity，属于新的 rc.5 专项；和 renderer只读分支共同防护，不重写持久事件、不伪造ID。

## Builder 内专项（不漏算成 registry 项）

| 专项 | 处置 | 依据 |
|---|---|---|
| storage-json直达表/记录提示V3 | retain | rc5仍withFileTypes且有getdents边界；严格投影成功，格式版本/空目录抑制legacy语义保留 |
| session-persistence-jsonl提示与排他发布 | retain | 当前后端仍需要Android发布器与创建后直接路径提示；不删除锁或回退重放 |
| fs-local原子发布 | retain | SELinux/proot硬链接不可靠，保留copy/fsync/原子publish语义 |
| attachment-local原子发布 | retain | 保留不可变源与EEXIST，对private对象使用已有协作锁回退 |
| session-format-v2-to-v3兼容wrapper | retain | 历史legacy输入读取仍是升级义务，不能因rc5版本变更放弃 |
| conversation materialized withdrawal | adapt | rc5仍对暂时withdraw抛错，精确minified分支保留稳定key隐藏节点 |
| destructive-policy helper | retain | 原生事实、scope/identity、cwd和单次审批绑定不变，实际pipeline17用例通过 |
| workflow-worker-thread历史别名 | retain | 仅目标PTC存在且旧名未安装时建立受管alias，不遮蔽用户包 |

## 实际验证与限制

已执行并通过：

1. `python3 tools/verify-dsh-rc5-patches.py --modules app/build/rc5-candidate/node_modules --report app/build/rc5-upgrade/exact-admission-final.json`：61 active、47真实模块的完整source/package pin、完整链语法、幂等及改坏锚点拒绝。该检查明确标 `behaviorVerified:false`。
2. `node tools/test-dsh-rc5-browser-regressions.mjs --managed` 及不带 `--managed` 的 raw负控：执行真实发布client.js，合成数据与UI carrier，无网络。二进制上传、5GB+流计数拒绝与cancel、DataCloneError清理、image新字段、六类非法identity只读动作防护及合法记录原行为均验证。
3. `node tools/test-dsh-rc5-stream-regressions.mjs`：真实timeout/SSE reader配原生AbortController/ReadableStream，raw两负控与managed两正控通过；用户取消reason保持。
4. `DSHA_TEST_RUNTIME=<rc5-candidate> node tools/test-tool-call-identity.mjs`：晚到ID、空白/截断identity、混合批次零副作用、真实ToolCallRecovery、Messages/PI历史协议回归通过。
5. `DSHA_TEST_RUNTIME=<rc5-candidate> node tools/test-consent-tool-pipeline.mjs`：17/17通过，真实ToolsService/native executor/Bash/FS模块，原生HTTP与shell过程受控；不等于真实设备授权测试。
6. `python3 tools/test-web-fetch-unicode.py`：真实provider/output函数UTF16边界回归通过。
7. 运行时归档完整构建成功，固定源码在构建前后再核验；独立recovery未改。

8. welcome/account/web-search/upload 四个旧generator的 rc.5 `--check` 现在明确委派到精确审核配方校验（仅声明配方族并核对所在模块完整链）；不执行旧模板、不冒称重新生成。非check写入在修改任何文件前拒绝并提示 `RC5_RECIPE_REVIEW_REQUIRED`。pnpm旧generator同样检查新版policy和retired登记，`--write`拒绝。五组check及写入负控均通过。

明确未通过/未验：

- 旧 `test-conversation-stop.mjs` 引用仓库缺失的 `tools/qa-rc21/conversation-stop-runtime.mjs`，不能执行；新增真实rc5流回归覆盖当前取消问题，不冒称旧整套通过。
- ConfigEditor/HMR和plugin proof worker回归在本机返回Node SIGSEGV（无JavaScript失败正文）；未删断言、未把native崩溃标为业务通过。HMR rc5精确模块已审读并绑定新SHA，嵌套交易立即拒绝逻辑保留。
- 管理候选数据写入审计曾在过长物理路径触发原生`flock` SIGSEGV：同一原生模块256字符路径崩溃、151字符短路径通过。此为当前proroot native loader路径边界，未改数据格式/锁算法、未捕获后重放；短路径最终数据矩阵由主代理独立记录，只有其真实通过证据才能增加descriptor读取边。
- 旧welcome/account/web-search/upload生成模板保持historical-only，rc5入口现已分流为上述只读严格校验；相关完整浏览器generator回归尚未全量迁移。
- pnpm真实负控已复现 `ERR_PNPM_RECURSIVE_EXEC_FIRST_FAIL` 且没有npm元数据流量（Linux exit254，与历史Windows exit1不同）；后续真实registry/postinstall/Git prepare套件尚未完成。rc5 `viewProfilePackage` 新增必填 `fields`，测试调用已按实际签名补齐，不能把旧调用抛错当作产品通过。
- 不含完整浏览器机型矩阵、真实账户/OAuth、模型请求、Android安装、Gradle、远端CI、发布签名。未修改当前运行中的DSH或GUI38473。

所有本机回执在本工作树 `app/build/rc5-upgrade/`，尤其 `initial-regressions.json`、`additional-regressions.json`、`regressions/*browser*.json`、`logs/stream-regressions.log` 与 `logs/build-runtime-final.log`。完整raw输入按npm-ci证明复核，追加回归之后再次进行整树字节核验，未把测试修改回灌raw树。

## 集成接口

1. 主代理纳入新增history projection JSON及测试入口，生成最终descriptor/proofs；本线不改共享输入清单。
2. 使用本地新归档及来源JSON，不能把旧公开bundle的DSH runtime当作rc5输入。旧bundle只能作为不变Ubuntu/独立recovery等基础输入来源。
3. 新LAN proof/Java版本门与dataRead由各专线/主代理基于实测整合。
4. 旧pnpm argv JSON保持历史原件并在registry retired；不再应用。用户插件是否执行scripts仍由现有显式DSHA_PLUGIN_UNLOCKED策略决定，本次候选npm安装始终ignore-scripts。
