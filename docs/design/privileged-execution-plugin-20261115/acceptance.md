# 验收、示例和实施前待定项

状态：**Draft / 设计验收向量，未执行设备验收**。基线、范围与用户已确认约束见[主稿](README.md)。[Schema](protocol.schema.json) 只验证数据形状与静态关系；[示例](examples.json) 中的身份、时间、指纹、工具名和能力均为虚构，不是当前设备或 APK 回执。

## 1. 预算候选

下列数值用于本稿给出可评审的边界，不是用户已批准配置，也不修改现有执行器上限。

| 项目 | 候选值 | 约束 |
|---|---|---|
| 工具入口 | 1 个，暂名 privileged | 权限变化不反复卸载它；插件被用户主动停用仍保持其停用意图 |
| 单个请求 | UTF-8 ≤64 KiB | JSON 解析前限制字节；拒绝重复键、未知参数、非有限数值 |
| 单个回执 | ≤256 KiB | 先去除秘密再限制输出；截断不能报完整成功 |
| 组合步骤 | ≤8 | 固定能力定义展开，不接收任意步骤程序 |
| 能力目录 | ≤64 项；通道 ≤8 | 支持集合与当前可用性分开；超限返回有界错误，不无限分配 |
| in-flight / 排队 | 全局 4 / 8 | 实际取既有 owner 更小的上限；不扩 Root helper 的现有槽位 |
| admission | 60 秒 | 只限制开始派发；超时不等于在途进程退出 |
| 请求等待预算 | 1–120 秒，示例 30 秒 | 本次等待截止不证明底层所有调用可打断；未收齐结果保留未知并继续受控清理 |
| 状态读取 | 建议 2 秒 | 不触发 su/ADB 探测；缺桥不阻塞插件加载或 DSH 启动 |
| 事件批次 | ≤20 | 实例内游标；gap 触发只读重新同步，不触发业务动作 |
| 结案记录正文 | 建议 7 天后可清正文 | 不直接删除去重 tombstone；需满足退休域和全部 admission 失效等条件 |

准入域、tombstone、未决资源的物理保存位置、完整性/回退检测、容量上限与保留策略需实施前审定。不得把“7 天”解释成第 8 天可把旧执行当首次执行。

## 2. 故障与恢复矩阵

每项除业务断言外，都必须验证：**普通 DSH 对话/终端可继续；下一次普通启动不要求 Root/ADB/Shizuku；不进入无关 rootfs 维护或自动清数据。** 设备动作只在独立测试包和当次用户授权后验收，本轮不执行。

| ID | 输入/时序 | 必须结果 | 禁止行为 |
|---|---|---|---|
| F01 | 插件加载时没有壳桥 | 稳定工具仍可返回 HOST_UNAVAILABLE | import 阻塞、未捕获拒绝导致注册失败、自动重启 Web |
| F02 | 壳 wire 版本不兼容 | CONTRACT_UNSUPPORTED，保留普通工具 | 因此宣称 Linux 环境损坏 |
| F03 | status 查询 Root 关闭/未验证 | 只读已有事实，明确 disabled/unverified | 自动 su、自动弹授权、把 guest UID0 当实际 UID0 |
| F04 | 授权正变更或读失败 | enabled=null、CHANGING/UNAVAILABLE | 投影成用户关闭并写回偏好 |
| F05 | 工具描述仍是旧目录/旧参数 | prepare 返回 SCHEMA_STALE 或 UNSUPPORTED_OPERATION，设备派发 0 次 | 自动翻译旧参数并执行 |
| F06 | prepare 同 requestRef 同 payload 并发两次 | 同一原计划/admission 或同一过期状态 | 延长有效期、生成两个执行计划 |
| F07 | prepare 同 requestRef 不同 payload | REQUEST_ID_CONFLICT，原记录保持 | 覆盖原目标、参数或身份要求 |
| F08 | prepare 落盘失败/容量满 | 本域新副作用不可准入；有界诊断 | 返回一个只在内存存在的可执行 admission |
| F09 | execute 重复或两个线程同时提交 | 原子消费；同计划只启动一次、每步至多派发一次 | 第二次生成 permit、重选通道 |
| F10 | execute 偷加 arguments/command/forceRoot | Schema/协议拒绝，零派发 | 对已准备计划追加模型传入代码 |
| F11 | prepare 后、派发闸口前撤权 | 当前 owner 拒绝；本步 NOT_DISPATCHED | 旧 admission 复活授权 |
| F12 | 派发已越过闸口后撤权 | 停止后续步骤；当前步骤按证据完成或 UNKNOWN | 把已派发请求改成未发送、声称回滚 |
| F13 | su 很慢，期间授权/目标改变 | 真正发送业务前重新核 owner/目标；失效不发新业务 | 用准备时的快照放行 |
| F14 | 实际 UID 未知或与能力要求不符 | 保留未知/拒绝业务派发 | 按通道名称补 UID 0/2000 |
| F15 | 连接只是 TCP 可达 | connectionEvidence=TCP_REACHABLE | 当作应用鉴权、执行 UID 或本次业务授权 |
| F16 | 正常执行器退出 125 | 按真实退出与业务事实记录，非自动 UNKNOWN | 用数字 125 代替类型化判断 |
| F17 | 命令已启动后超时，旧门面返回 124 | 根据已派发事实保留 UNKNOWN | 124 一律当 NOT_SENT 后自动重试 |
| F18 | execute 写出后连接断开/无完整尾标 | 插件保留原 requestRef，QUERY_REQUEST | 自动重复 execute、自动改走 ADB/Shizuku |
| F19 | 排队取消，可信账本证明尚未派发 | 未派发结案，释放本请求资源 | 影响其他会话的任务/进程 |
| F20 | 已派发取消，执行器只确认收到了取消 | CANCEL_ACCEPTED 事件，执行/退出仍按证据 | “取消成功”当作已终止或无副作用 |
| F21 | root helper 拒绝 App 信号或未退出 | cleanup=PENDING/UNKNOWN，保留 owner 记录 | 按裸 PID、名称、端口猜测强杀；无限创建 helper |
| F22 | 前两步完成，第三步明确未发送 | 保留逐步结果，整体已知 PARTIAL | 将整组记未执行或默认补跑 |
| F23 | 前一步完成，下一步结果未知 | 整体 UNCERTAIN、executed/partial=UNKNOWN | 用“部分成功”掩盖未知 |
| F24 | effect=VERIFIED 但无新观察 | 拒绝该回执，保守未知/未验证 | exit0、平台 ACK 或任意字符串充当效果证明 |
| F25 | 插件热重载时任务 ACTIVE | 新实例 status→result→events，壳任务继续受控 | execute 重发、把 JS 连接断开当业务取消 |
| F26 | 旧插件回调晚于新订阅 | 按实例/订阅代次/recordRevision 丢旧通知 | 用旧数据覆盖新结果、静默续签旧观察 |
| F27 | 事件丢失/重复/游标过期 | gap=true；只读 describe/result 重新同步 | 把没收到完成事件当作未执行 |
| F28 | 壳在 prepare 记录后、intent 前重启 | 新 instanceId；旧 admission 失效；可信记录可证明未发送 | 重建/续跑旧准备计划 |
| F29 | 壳在 intent 后、结果持久化前重启 | requestEpoch 不变；原请求 UNKNOWN，先核对 | 自动继续动作或仅凭 App 重启标 helper 已退出 |
| F30 | 准入域退休时仍有未消费 admission | 原子废止、拒绝旧 prepare/execute；旧结果可查 | 为清记录而移动/复制未知业务到新域 |
| F31 | 查不到 requestRef/正文已清/记录损坏 | REQUEST_UNKNOWN/RESULT_EXPIRED；没有未发送证明 | 以不存在反证未执行，自动重新 prepare |
| F32 | 重装、重置或账本回退无法证明连续性 | 安装/请求域隔离；本能力关闭并可诊断，旧请求不可恢复执行 | 从用户备份恢复许可/旧 admission、影响 DSH 基础启动 |
| F33 | 资源回收仍未确认 | 对应能力/资源保持屏障 | 为保持 DSH 可运行而强行清除屏幕/进程保护 |
| F34 | 未知请求记录/存储预算耗尽 | 有界拒绝新能力请求，保留未知记录 | 静默淘汰仍可能被重试的记录或阻塞全局启动 |
| F35 | 用户停用包装插件 | 旧订阅失效；原生恢复/诊断仍可达 | 停用导致已派发任务失去监督，或强制重新启用插件 |
| F36 | Android 系统杀 App / JNI 崩溃 | 不承诺进程存活；下次无特权启动并恢复事实 | 把 try/catch、协程或 JS 插件当全故障隔离 |
| F37 | 用户更改目标或切到另一个屏幕 | 原业务 owner 再核目标与原凭据 | 将单工具参数统一成可互换的 pixel/node/anchor 凭据 |
| F38 | 定时任务到期但权限已撤销 | 每次 occurrence 新请求、重新授权或记未执行 | 用登记时的 admission 当永久 Root 授权 |
| F39 | 步骤已执行或明确未执行，汇总却声称未发送/全成功 | Schema 拒绝矛盾摘要，执行时再核计划的完整步骤集合 | 用顶层摘要隐藏已知步骤 |
| F40 | 某步骤标 VERIFIED 却缺少该步骤效果证据 | 拒绝该步骤回执，原 owner 核验有效观察 | 只校验顶层 effectEvidenceId |
| F41 | 执行未知，后来独立证明完整资源集已释放 | 保留原 UNKNOWN 传输快照；cleanup=RELEASED 带独立证据；不重放 | 业务未知阻塞无关资源，或资源退出冒充业务完成 |
| F42 | 已确认执行但业务效果未知 | SETTLED + business/effect UNKNOWN 可表达；未知摘要保留，retry 不转 SAFE | 把效果未知误作执行未发生，或按成功 TTL 丢弃未知事实 |

## 3. Schema 能证明什么

本稿 Schema 使用 JSON Schema 2020-12，静态约束包括：七个 op 的字段分支；execute 不收新参数；安全整数；未知身份不得填 UID0；CHANGING/UNAVAILABLE 不伪装用户 off；A1 五字段关系；未知执行不可 SAFE；原 UNKNOWN 传输快照不附远端退出证明、后续资源 RELEASED 另附独立证据引用；顶层和步骤效果已验证均需证据引用；整体成功不能覆盖未执行步骤；未知步骤不能被结案摘要掩盖。

Schema **不能证明**：caller 真身份、授权仍有效、UID 回执可信、evidenceId 指向新观察、admission 未过期/未消费、请求落盘成功、去重域未回退、具体系统接口无副作用。上述均是原生 owner、传输与存储实现的义务。能力 `arguments` 还必须按 describe 返回的该能力 inputSchema 再验证，通用请求 Schema 允许 object 并不表示任意参数可执行。

[示例文件](examples.json) 的 `valid` 仅表示本稿形状是否应被验证器接受。相同样本 ID/值在两种语言的解析与适配结果必须一致。未知响应判定 tag/非法字段在运行时保持 fail-closed；不能因反序列化失败抛出插件启动链，亦不能丢弃未知事实后补 success。

## 4. 本机验证方式与本轮回执

本机现有 Ajv 8.20.0 支持 2020-12；没有安装任何依赖。下面的绝对路径只是本次本机验证器位置，不成为插件运行依赖或可发布路径。

```bash
AJV_2020_MODULE=/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/ajv/dist/2020.js node <<'JS'
const fs = require('node:fs');
const Ajv = require(process.env.AJV_2020_MODULE).default;
const base = 'docs/design/privileged-execution-plugin-20261115/';
const schema = JSON.parse(fs.readFileSync(base + 'protocol.schema.json', 'utf8'));
const examples = JSON.parse(fs.readFileSync(base + 'examples.json', 'utf8'));
const ajv = new Ajv({strict:true, allErrors:true, coerceTypes:false,
  useDefaults:false, removeAdditional:false});
ajv.addSchema(schema);
let failures = 0;
for (const item of examples.cases) {
  const validate = ajv.getSchema(schema.$id + (item.schemaRef === '#' ? '' : item.schemaRef));
  if (!validate || validate(item.value) !== item.valid) {
    failures++;
    console.error(item.id, validate && validate.errors);
  }
}
console.log({cases: examples.cases.length, failures});
process.exitCode = failures ? 1 : 0;
JS
```

本轮回执：

- Ajv 8.20.0 / JSON Schema 2020-12，strict/allErrors 开启，不转换类型、不补默认、不删除字段：**49 个向量全部符合预期（27 个合法、22 个反例，0 mismatch）**。
- 两个合法 prepare 的能力参数另按各自 inputSchema 校验；两个合法目录中的自包含 inputSchema 严格编译，均通过。
- 文档链接：2 篇 / 17 个目标，0 warning、0 error；7 个源码/文档行锚点均在实际文件行数内；两个 JSON 文件没有重复键。
- 两条独立只读复核检查 owner/既有契约与撤权/重载恢复；初稿发现组合汇总、步骤效果证据和未知结案约束不足，已修复并加入反例。原传输快照与后续独立资源核对分层，补“执行未知但资源已释放”和“已知执行但效果未知”正例。修订后两条独立复核结论均为 **APPROVE（仅 D0 数据稿）**，各自独立重跑 49 向量全部符合预期，并分别完成 8 项与 4 项定向检查；不把该结论扩写为生产实现、协议冻结或设备稳定性验收。
- 初稿严格编译发现 payload 联合 type，改为 anyOf；证据字段拆分时还发现 required 引用未同步，已修正。最终没有通过放宽 strict 规则消除问题。

以上只证明数据形状、约束向量和引用闭合，不涉及生产执行器或设备可用性。

本轮不运行 Gradle、Android 单测、插件加载、真实通道探测或手机动作。不把 Schema 样本通过写成撤权后不崩溃、持久化正确、权限安全或全 ROM 可用的证据。

## 5. 实施前待确认的设计选择

| 项 | 本稿候选 | 冻结前需要的证据/决定 |
|---|---|---|
| 工具/op 命名 | privileged + 七个 op | 与 DSH 既有命名和工具 schema 生命周期对齐；保持单工具用户目标 |
| prepare/admission 是否作为首个可执行版本必需 | 推荐 | 明确原生当前授权检查、跨请求幂等与每步派发闸口的实现 owner |
| capability 目录 | 壳声明、插件有界适配 | 第一项真实开放能力、输入/结果 schema、既有授权映射；不默认开放 F07 或裸 shell |
| Java/Kotlin 落点 | 当前 Java 适配，按模块切换 | D1 方法级写域、Java↔Kotlin 结果差分、无双份授权存储 |
| 通信与兼容 | 复用本安装已鉴权桥的原则 | 新端点/特性检测、旧插件兼容；不擅自复用 protocol=3 宣称已支持新 wire |
| 指纹算法 | 规范化不可变计划再摘要 | 跨 Java/Kotlin/JS 字节向量；禁止依赖对象遍历顺序/普通 JSON.stringify |
| 持久请求域 | 不用 hostInstance 去重；退休域拒绝新执行 | 实际文件/数据库布局、回退检测、原子 intent、tombstone、容量和关闭策略 |
| 预算/保留数值 | §1 候选 | 与现有 Root helper/原生确认/队列上限对齐；资源压力下不影响 DSH |
| 插件 detach/unload | 不隐式取消已派发任务 | 原生 owner 持续监督、是否撤销未来步骤准入、重新 attach 的认证范围 |
| 调度/全屏/Root Linux runner | 不在 D0 | 各自另立能力定义和授权/窗口/生命周期验收，不因本稿直接开工 |

用户已确认的是主稿中的核心约束与产品方向；表中细节均为可评审建议。本稿可作为下一阶段讨论和差分向量的起点，不是执行代码、权限开放或发布授权。
