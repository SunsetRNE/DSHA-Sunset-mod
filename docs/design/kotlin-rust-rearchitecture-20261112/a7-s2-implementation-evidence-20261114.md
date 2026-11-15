# A7 S2 实施回执：实体屏显式动作凭据

日期：2026-11-14。状态：S2 软件切片待 Lead 独立审查；A7 整轮未完成。S1 提交 `c92f8495`，本片从其后继工作树实施，未随主线推进自动 rebase。授权范围见[任务基线](a7-task-baseline-20261114.md)。

新增 [PhysicalActionRequest](../../../app/src/main/java/com/deepseekharness/app/util/PhysicalActionRequest.java) 的 Pixel、Node、FocusedInput、Key 四条显式数据支路，连同查询/动作共用的 Operation 命名表。类型只提取原支路字段，不签发或续期凭据，不借另一类型 ID 或最近截图补缺失值。额外字段保留旧入口忽略语义；旧 tapText 明确是原有临时文本查找，不能携带/冒充 node 租约。

[ScreenActionExecutor](../../../app/src/main/java/com/deepseekharness/app/screen/ScreenActionExecutor.java) 真正消费 typed pixel/node/key 字段，随后仍进入原 screenshot ledger/node lease/焦点复核与唯一平台派发路径；[MainScreenActions](../../../app/src/main/java/com/deepseekharness/app/screen/MainScreenActions.java) 以同一枚举区分观察和动作。不改变外部 JSON、拒绝码、字段容错、display0/任务 owner、连接 epoch、30秒/单次、最新截图/几何、零 bounds、supportedActions、claimDispatch、效果未验证或 UNKNOWN/noReplay；不启用 T130，不跨屏 fallback。

[纯类型测试](../../../app/src/test/java/com/deepseekharness/app/util/PhysicalActionRequestTest.java) 10项验证命名表、缺凭据不借ID、foreign字段不改变原支路、focused/key不获取像素/节点许可与非法重标拒绝。父会话合并 S3 文法集统一运行时，4类33项全部通过，其中本片10项无失败/错误/skip。

[真实物理编排夹具](../../../tools/fixtures/physical-screen/com/deepseekharness/app/screen/PhysicalScreenFlowTest.java) 新增4组：treeId/nodeId/虚拟frame/anchor冒充pixel；缺observationId不借最新缓存；pixel ID不能替代tree/node任何一段；有效请求附无关字段的兼容正控。负控均断言原 NOT_SENT/FRAME_NOT_OBSERVED 或 STALE_NODE，gesture/global/semantic派发计数均为0；正控仍只提交一次动作，不宣称业务效果已验证。

真实夹具运行经历：首轮父会话启动漏传 fixture要求的 `physical.classes`，setup拒绝，未进行为；补来源参数后26项中3失败。排查证明两项fixture只设clickable但未声明ACTION_CLICK，实际supportedActions门正确拒绝；另一旧fixture用expanded标志猜全屏遮挡，实际窗口仅覆盖局部。仅修fixture：显式声明点击action；先确认局部非焦点自有窗仍留可用面积，再把真实窗口与登记bounds设全目标，保留坐标不可用并加无observationId/OWNED_CONTROL_COVERS_TARGET断言。未删负控或放宽生产。原26/3日志按相同SHA保留，修正后**26项全过**；随后当前真实VirtualScreenAnchoredChain回归**15项全过**。

验证使用当前实际生产 Java 源码、真实 Gradle 转换依赖/SDK与生成R/BuildConfig/AIDL/UI字典，不读取其它工作树已编译App类；当前1020个生产Java源码类型编译与夹具编译exit0。Robolectric只有内存OS/像素事实，无手机。新类型格式清单仅按Lead窗口增加本片生产/测试两条；与S3合并验证时全树 **1786/1786、零missing/extra、需格式化0**。边界 **25项通过**，`git diff --check`通过；S2/S3回执与S4表显式集3文档18本地引用目标、零错误。

独立宿主夹具不代替完整Gradle AndroidUnit、资源链接、lint、APK/ELF/JNI或真机权限矩阵。本机Gradle仍受x86 AAPT2 bad machine阻断，未绕过门禁，不记这些项目为通过。分支descriptor/launcherSources未改；main合并树需精确纳管新 PhysicalActionRequest 后统一重算最终 runtimeId，一次维护页与用户授权真机矩阵仍待验。

本片可独立提交与审查；S3保持独立凭据域，S4源码仍等S1独立APPROVE，未 push/merge/tag/install/操作手机。
