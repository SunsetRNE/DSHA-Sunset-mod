# A7 S3 实施回执：虚拟屏独立动作证据

日期：2026-11-14。状态：S3 软件切片待 Lead 独立审查；A7 整轮未完成。授权范围与不变式见[任务基线](a7-task-baseline-20261114.md)，实体屏接线另见[S2回执](a7-s2-implementation-evidence-20261114.md)。

新增纯 [VscreenActionEvidence](../../../app/src/main/java/com/deepseekharness/app/util/VscreenActionEvidence.java) 五个相互独立载体：RawFrame、Node、Editor、ObservedAnchor、AnchoredClick。都保留调用方原 coreInstance/generation；raw/anchor带原帧；node帧可缺省（null）且不要求像素静止；editor只带当前焦点身份，没有像素帧或新造的30秒一次票；选锚与锚点点击分别保存observationId/anchorId。

[真实文法入口](../../../app/src/main/java/com/deepseekharness/app/util/VscreenBridgeRequest.java) 在原validate之后仅解析一次证据，真实execute调用typed Actions重载。[Manager dispatch](../../../app/src/main/java/com/deepseekharness/app/vscreen/VirtualScreenManager.java) 显式消费各类型的原字段，再调用原scope-bound raw/node/editor/observed路径；原coreInstance目标复核仍在bridge前置，不能用status替换旧Core/代次/帧。旧签名通过default委托兼容现有适配器和夹具，不把新载体变成备用派发路径。

未改公开JSON/允许字段/错误码顺序、协议号、Core最新已观察帧门、节点refresh/单次消费、editor当前焦点/密码硬拒、anchor原图/遮挡/几何/先消费再核验。没有静默tap/node/anchor回退、不重放UNKNOWN、不新增权限、不改设备策略；无新桥端点。Editor get原文法允许但忽略的editorId仍按原规则忽略，不因类型构造顺便收紧旧wire。

软件证据：

- [新类型测试](../../../app/src/test/java/com/deepseekharness/app/util/VscreenActionEvidenceTest.java)、[真实dispatcher测试](../../../app/src/test/java/com/deepseekharness/app/util/VscreenBridgeRequestTest.java)、[锚点文法测试](../../../app/src/test/java/com/deepseekharness/app/util/VscreenAnchoredGrammarTest.java)共**23项**；父会话与物理类型10项统一执行为4类**33项，0失败/错误/skip**。验证五类矩阵、对象携原ID/帧实际进入execute、node帧可选/不静止、editor三态无帧、异类ID/未知字段拒绝且typed/legacy Actions派发计数都0、anchor错误不补观察或换raw、旧签名兼容。
- 当前真实生产源码1020个Java源，以实际Gradle SDK/依赖和生成入口独立类型编译exit0；当前真实physical fixture26项与VirtualScreenAnchoredChain15项均通过。后者经过真实文法/Manager/认证Core/无障碍语义owner，证明typed重载已在实际链中执行；不把纯DTO构造测试当完整编排证据。
- 子任务首轮javac因未加入现有生产Gson依赖未能编译；按固定缓存补齐依赖后通过，没有改生产代码/测试期望消除环境错误。父会话最终33项使用真实当前依赖重新执行。
- 格式清单仅按Lead窗口登记本片新增生产/测试两条；S2/S3合计只增4条，无其它清单条目变化，不碰launcherSources/descriptor/校验逻辑。双屏最终全树 **1786/1786、零missing/extra、需格式化0**；边界 **25项通过**，diff检查通过；S2/S3回执与S4表显式集3文档18本地目标、零错误。

未跑与集成要求：完整Gradle AndroidUnit/资源链接/lint/APK/ELF/JNI与真机均未通过本机门禁（x86 AAPT2 bad machine），独立Robolectric夹具不能替代这些项目；未安装或操作手机。分支descriptor保持8598a8db；main合并树须精确纳管新增VscreenActionEvidence及之前新增util，统一重算最终runtimeId并做一次维护页矩阵。

本片独立提交/审查，不把S1或基线入main扩写为A7整轮已合入。S4保留先前门禁、有限管理器/版本/只读要求，T40/T42风险与同UID边界未解决。
