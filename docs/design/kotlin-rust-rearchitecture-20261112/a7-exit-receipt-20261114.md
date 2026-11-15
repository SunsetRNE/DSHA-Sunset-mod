# A7 整轮出口回执

日期：2026-11-14。状态：**整轮候选；`.SESSION-DONE` 合并栏写“不可合并”，待新 main rebase/纳管/重算 descriptor 后由用户批准**。
会话：`session-7a92f991-ee77-4fd0-a67f-185a14a60f8b`（@a7-takeover），接手 `8a45a1db`（上下文预算，用户已确认 takeover）。
分支：`feat/a7-device`；本回执对应 HEAD 见文末“当前 HEAD”，未随主线自动 rebase。

## 1. 分片与复审

| 分片 | 提交 | 独立复审 | P2 处置 |
|---|---|---|---|
| 基线/裁决 | `e4c3cabc`、`903cbbfc` | — | — |
| S1 设备授权围栏/通道事实 | `c92f8495` | **APPROVE** | 通道事实组合/`recordPairResult` 适配器组合、CHANGING/UNAVAILABLE 压 false 状态等 → 出口窗口保留 |
| S2 实体屏动作凭据 | `39ddef7a` | **APPROVE** | input/key 不借像素凭据的真实 fixture → 出口窗口保留 |
| S3 虚拟屏动作证据 | `b6dcdc4d` | **APPROVE** | actionEvidence/legacy coordinate 仅测试消费 → 出口窗口保留 |
| S4 管理器表 | `c8794163` | 已随 S4 实施审阅 | — |
| S4 固定 FD 探测/只读清单 | `5a0a3ec3`、`bea574cd` | **APPROVE**（无 P0/P1，5 条非阻塞 P2） | ①② `28a94dcc`；③ `bbb60c5c`；⑤ `02923563`；④ `1b8ba6c2` |
| S5 十语文案/source pin | `cf13d360` | 已冻结并合并入整轮候选 | 无新增 P2 |

S4 复审 5 条 P2 的逐项说明见 [S4 实施回执 §独立复审与 P2 处置](a7-s4-implementation-evidence-20261114.md)。本回合**不改** S1–S3 已 APPROVE 代码，只做出口记账。

## 2. 本回合实际执行（补充夹具，不是 Gradle 门禁）

以下均为真实当前生产源码 + 真实 Gradle 转换依赖/SDK/R/生成入口的内存夹具；**不是**完整 Gradle、资源链接或设备证据。

| 检查 | 结果 | 日志（不提交） |
|---|---|---|
| 全生产 Java 类型编译 | **1026 源，exit 0** | `app/build/a7-takeover-verify/production.log` |
| F07 纯策略/parser/fd/协调器 | **70 项，0 失败/错误/skip** | `app/build/a7-takeover-verify/pure-tests.log` |
| Root 只读页状态/围栏 | **9 项，0 失败** | `app/build/a7-takeover-verify/RootModulesFragmentTest.log` |
| `RootShell.readRootModules` ROOT_DISABLED 门禁 | **1 项，0 失败** | `app/build/a7-takeover-verify/RootShellTest.log` |
| Java 固定全树格式 | **1798 文件，0 需格式化，exit 0** | `app/build/java-format/tree-summary.json` |
| 架构/核心边界 | **25 项通过** | 终端输出 |
| UI i18n 词表/静态检查 | **newFindings 0 / stale 0**；`test-ui-i18n.py` **9 项通过** | 终端输出 |
| source hygiene | **PASS** | 终端输出 |
| 文档链接 | **56 文档 / 1315 链接 / 0 warning / 0 error** | 终端输出 |
| 双屏回归（S4 期间证据） | physical **26**、Manager/anchor **15** | `app/build/a7-screen-fixtures/`，本回合未重跑 |

## 3. 未跑项（不得写成“已验证”）

- **构建链**：完整 Gradle `:app:compileStandardDebugUnitTestJavaWithJavac`、`:app:testStandardDebugUnitTest`（AndroidUnit）、lint、资源链接、`assembleStandardRelease` APK/签名/ELF/JNI 全部**未跑**；本机仍受 x86 AAPT2 `bad machine` 阻断。
- **真实 Android/设备端口**：真实 `android.system.Os.open/fstat/fcntl/execv`、`/proc/self/fd`、`fdexec`、真实 UID0 helper、真实 su 授权与 Root 管理器（Magisk/KernelSU/APatch）版本探测、Shizuku、ADB、模块目录读取全部**未跑**。
- **Robolectric 宿主路径**：真实 `AppCompatActivity`/`FragmentActivity` 视图创建与 `isResumed`、`DeviceAuthorization.current` 端到端路径在本机补充夹具中**未跑**（缺少 AAPT 编译资源/AppCompat R）；P2③ 只给出 `acceptsResult` 纯围栏、授权变化 UI、ROOT_DISABLED 与实际门禁测试的静态证据。
- **真机/视觉/安装**：双屏真机矩阵、敏感页 ASK/HANDOFF、覆盖安装一次维护页、中英/日夜/短屏/1.3× 字体、PiP 遮挡、I1–I10 全部**未跑**；本会话**未安装、未操作手机**。
- **descriptor/CI**：本分支未改 `runtime-descriptor.json`/`launcherSources`/`managed-runtime-inputs`/contract，也未跑 descriptor source/inputs/source-bytes 检查；runtimeId 仍为分支旧值，最终值由新 main 重算。

## 4. 已知窗口/降级项（不阻断 APPROVE，合并后仍需保留）

- S1：`channelFacts` 组装、`setAdbState`/`recordPairResult` 与实际适配器组合测试；`bound/connected/requestedRoot` 语义；CHANGING/UNAVAILABLE 压 false 状态。
- S1/S2/S3 公共：core 绑定注释/显式参数、枚举 vs 字面量分类漂移、新测试是否进 JVM harness 由 main 合并树/follow-up 决定；legacy coordinate 私有兼容入口、`actionEvidence` 仅测试消费。
- S2：input/key 不借像素凭据的真实 fixture。
- S4：P2③ 的宿主 `isResumed`/授权快照端到端路径未跑（见 §3）；P2④ 仅记账导航按钮复用 S5 `m9503`；P2⑤ 只修文档措辞。
- 真机仍未证明：管理器安装/授权/自定义版本、模块目录隐藏模式、SELinux/profile 不可见、Root 三档与 F07 只读组合。

## 5. 合并前置清单（交给新 main）

1. 等用户批准后，在新 main 的绿基线上 rebase 本分支；不要在本工作树自动 rebase（会改写已提交证据）。
2. 处理可能与 A6 合并后 i18n 词表 union 的冲突：本分支只追加 S5 的 14 个新 id（m9503–m9516）与 8 语译文，**不重复追加 A6 的 messages-extra**。
3. 把下列新增/改动生产类纳入 `launcherSources`/启动输入并核验：`util/DeviceAuthorization`、`util/DeviceChannelFacts`、`util/PhysicalActionRequest`、`util/VscreenActionEvidence`、`ShizukuShell`、`util/RootModulePolicy`、`util/RootModuleInventory`，以及 `device/RootModuleFiles`、`device/RootModuleReadCore`、`device/RootModuleProbeMain`、`ui/RootModulesFragment` 与设备/屏幕适配改动。
4. 统一重算 descriptor/runtimeId，跑 descriptor source/inputs/source-bytes、Source/Docs CI；合并后首次启动预期一次维护页。
5. 不把本回执的补充夹具当成 CI 绿；完整 Gradle/AndroidUnit/lint/APK 由合并窗口执行并另记。
6. 用户明确批准 merge/push 前，本分支保持 `不可合并`。

## 6. 安全与范围声明

本会话只做本地提交：**未 push、未 merge、未 tag、未发 Release、未安装或降级任何包、未操作手机、未改 descriptor/launcherSources/managed-runtime-inputs/contract**。未新增权限、桥端点或任意命令入口；F07 仍只读、显式刷新、固定样本。本回执不声明任何真机或管理器兼容结论。

## 7. 当前 HEAD

`1b8ba6c2`（S4 P2 回执）+ 本回执提交；最终 HEAD 以 `.SESSION-DONE` 记录的 `git rev-parse HEAD` 为准。
