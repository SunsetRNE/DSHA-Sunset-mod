# T3 / A8 P2–P4 接手续作回执（2026-11-16）

状态：**P2 剩余本地实现已提交，等待 main 独立身份树的实际 Android / app / P3 集成验证。P2、P3 完整出口尚未通过，P4 本轮只交精确切片和前置核销，不编码。** 本地分支 `a8-followup-65152e2a`，实现基线 `82eb958db6a7d8f0c402b1fc4d41c43be94ed624`。接手会话 `session-65152e2a-f3be-4761-a009-8817fa9f75d3`；main 仍为 `6313fce2`。

用户授权只覆盖正文范围的本地实现、测试、提交；没有 push、合并 main、发布、安装、手机操作或清理旧树。本树未生成或绕过最终 descriptor/runtimeId/recipe，也未重写原 owner、通知票据或 NetworkHealthPolicy。

## 1. 交接、基线与已完成旧成果

原实际持有者 `@a8-mod / badee4e5` 已停止写入和新构建，封存 P3 声明；main 通过插件把 T3 claim 从旧记录持有者 `6c3c6226` handoff 到本会话。P2、P3 旧树均以 `.SESSION-DONE` 保留，源码无未提交改动，只有未跟踪 `out/` 证据；原 worker 已确认后台任务均收集、无待重放 UNKNOWN。新树直接从 `origin/main=82eb958d` 建立，没有 reset、rebase 或清理旧树。

续作期间重新 fetch 得 `origin/main=46f4970369c796c3a7cb9acca6cff58961778840`。`82eb958d..46f49703` 只有六个发布回执文档变化，没有生产、构建或测试变化；本实现基线仍明确保留 82eb，不复制旧 build367/368 的版号或测试状态。main 的验证树须从届时最新 origin/main 建立。

原六个 P2 提交和三个 P3 提交的 ancestor 检查均为未合入；`git cherry origin/main <candidate> <base>` 对各提交均为 `+`，没有发现按相同补丁重放的成果。另核了主线没有 P2 的应用 owner 接线和 P3 的 `:core:policy` 产品模块。因此只接收已有成果，不重做它们的业务设计。

| 原提交 | 本树接收提交 | 原成果，本次不重写 |
|---|---|---|
| `33e0b012` | `03421f6c` | P1 历史软件出口与后续门禁回执 |
| `5b7fc74d` | `116ee601` | Java/Kotlin 共享布局下的 i18n 扫描 |
| `bd0362e3` | `c09bcf94` | Application 持有唯一任务窗口 owner、退休回调 |
| `42076302` | `0dd1f902` | i18n 嵌套模板、隐式 receiver 扫描修复 |
| `2202cde2` | `1e4d58a2` | STOP/REPLY/SHOW 通知身份及冻结发送目标 |
| `11279918` | `468604cf` | P2 原回执及原基线输入差异 |
| `96abb5d6`、`63a8912f`、`2fa414d1` | main 验证树待接收 | P3 真实 policy 模块、隐式分组门禁和跨模块报告聚合 |

接收使用逐提交 `cherry-pick -x`，完整来源写入提交 trailer；DshaApp 和 Java 格式清单以当前主线内容自动合并。旧 [P2 回执](a8-p2-progress-20261116.md) 的 SHA/输入摘要/测试数绑定原基线，不能套成本次结果。

旧成果的真实证据分别是：P2 手工生产/测试编译、纯 JUnit 32/0，新增 Android 负控只编译未执行；P3 `:core:policy:test` 23/0/0/0、conventions PASS、三种隐式分组真 Gradle 负控拒绝，但 app 停在 descriptor 门禁。两者不是同一个完整候选，不能相叠后宣布完整出口通过。

## 2. 本次剩余实现

| 提交 | 修复与边界 |
|---|---|
| `de5c1fbd` | OPEN 正文从 Kotlin 导航协作者构造并在 Activity 接收端复核已发布票据的完整地址、当前 service/controller/turn/epoch；旧票据结束入口，不回退到当前选中会话。Activity 重建/onNewIntent、旧视图回调和输入呈现交接均绑定原对象/视图代次。 |
| `88a1ad48` | 在同一 controller monitor 内复核 OPEN 编辑器动作；Send 复用已有 `sendFromNotification`，Continue 加同等冻结入口，排队期间换轮不派发到后继。旧 pause 只有仍持当前输入呈现且原通知有效时才 collapse，不消费后继 deferredFinish；输入来源按 Activity 实例区分，旧释放不放开后继输入闸。语音接收端继承同一 OPEN 地址，迟到启动/权限回调/识别结果不得写新轮草稿。 |

第三项修补已提交为 **`9cb67d5100851799a7fcc2c9b4759a11bab3b0c0`**：Voice 对本次识别结果在 finish/stop/destroy 时失效、按原 OPEN 和输入来源一次消费；showBound/onResume 的最终呈现发布在 controller monitor 内重新核对；Continue 排队夹具通过真实 worker/poll 的 idle 回执推进，不依赖 900ms 周期。新增同 turn 语音停止后旧结果、重复识别结果反例。完整增量静态复审结论 **APPROVE**，没有剩余明确 P1/P2；复审不等于编译或测试通过。main 已在 T9 交还后提供独占格式槽：两 Java/一 Kotlin 的 write/check 均 0 changed、exit0，第三修补提交后立即交还，没有串接全树编译或测试。main 将按届时最新主线（包括其协调的 T7 合入）与本业务 SHA 接入 P3，统一生成身份后执行实际 app 验证；`88a1ad48` 只作历史点。

新职责在 [Kotlin 导航协作者](../../../app/src/main/java/com/deepseekharness/app/assistant/AssistantNotificationNavigation.kt) 中，现有 Java Service/Activity/controller 只接入已有平台或发送职责。没有第二个任务 owner、后台连接、默认最近会话、自动 begin 或未知动作重放。

通知票据的编码、完整 session/host/connection/generation/turn/epoch/action 身份保持；OPEN 不替代手机任务授权。发行身份、主 Manifest、SharedPreferences 键、持久化格式、进程出生身份、Root/Shizuku/ADB 与两块屏凭据边界均未改变。麦克风仍仅由用户显式语音入口经 Android 权限请求，识别结果只进入草稿，不自动发送。

新增 [Android 接收端回归](../../../app/src/test/java/com/deepseekharness/app/assistant/AssistantNotificationNavigationAndroidTest.kt) 有 **21 项源码声明**，包含当前票据/重建正控、旧轮、同短尾不同完整 session、同地址不同 epoch、坏 data/错误 action、onNewIntent、不同/相同 controller 的 Activity 交接、旧输入和按钮、显式会话选择与权限页、旧 pause/deferredFinish、排队发送/继续反例与当前发送正控、语音旧启动/旧回调及草稿正控。后端与录音回调是受控端口夹具，不是真机或真实系统语音识别证据。

## 3. 本树验证和集成接口

| 层级 | 本轮实际结果 |
|---|---|
| 新增测试可发现性 | `unit_test_contract.py plan` 已实际枚举新 Kotlin 类的 21 方法；相对 build369 源计划，测试源 **796→798**、声明身份 **5,306→5,346**，新增 40（原 P2 新增 19、本次 21）、丢失 0；原测试未改名/过滤/Ignore，冻结基线未重写 |
| 工程门禁 | 接收原链后 Java 全树 1,778 文件、Kotlin 全树 88 文件格式通过；i18n 26 项、扫描 newFindings=0/stale=0；core 边界 25 项、architecture、unit contract 64 项、source hygiene 均 PASS |
| 最后实现的定向格式 | `9cb67d51` 的两 Java/一 Kotlin 以锁定工具离线 write/check 均 PASS、0 changed；Java exact tokens 守恒、Kotlin 幂等，`git diff --check` 通过。job `bash-13360ae7e4704f2e` 已收集 exit0；未串接编译/测试 |
| Android / Gradle / app | 交 main 独立身份树后实际执行，当前不记 PASS。不得以 21 个源码声明或静态审查冒充 21 项执行 |
| full / Lint / APK / merged Manifest / 受管身份 | 尚待 main 集成后真实验证；build369 的单 app CI 不覆盖本次新候选或跨模块集合 |
| 设备 / 视觉 / 覆盖更新 | 未执行，不在本轮授权范围 |

本地日志定位（未入库）：

```text
本树 out/a8-followup/local-checks.log
本树 out/a8-followup/plan-final.json
本树 out/a8-followup/third-format.log（首轮已知 exit2）
本树 out/a8-followup/third-format-r2.log（定向格式及第三提交 exit0）
本树 app/build/java-format/a8-third-{write,check}.json
本树 app/build/kotlin-format/a8-third-{write,check}.json
旧 P2 树 out/a8-p2/ 与 /tmp/dsha-a8-6c3c6226-*
旧 P3 树 out/a8-p3/ 与 /tmp/dsha-a8-p3-*
```

最后定向格式首轮把 `--report` 指向 out/，工具以 `FORMAT_REPORT_OUTSIDE_CACHE` 明确 exit2，未改源码、未提交；改为工具允许的 app/build 格式缓存报告路径后，原三文件 write/check exit0。首轮 job `bash-a4600cb7c7074396` 和修正后的 job 均已收集，无待核实 UNKNOWN；原失败日志没有覆盖。

全树格式首轮之后继续发现并修复了同步/排队竞态，首轮日志不冒充最终源码的 Android 证据；最后全树/文档复验及 main 实际结果在本回执后续提交中补记。一个只读审查子任务误写的 UI 交接增量已由本会话逐项审阅接收并纳入本地提交；其自行启动的重复门禁被取消，没有计入 PASS。所有旧树和未知现场保持不变。

受管输入逐键实核仍 **517→517，新增/删除均 0**；相对本树未修改的 descriptor，唯一变化是：

```text
DshaApp.java
before 567434c0e01742c1781acf4d7abcf047e6286871ea58758dbc6cc1c6c52d2f8e
after  72d6dca621790d69b0d5e2d0fb6cb061b33a5c6ded48580a15c9946857f37227
```

原 descriptor 的 runtimeId 仍为 `1d42f79d5fea6bad5caaa2409822aa5949691c89e6e230b95011ae35db8a5757`；这个旧值是保留的构建门禁，不是可用于新 app 验证的身份。P3 合入验证树时还会改变 layout generator 摘要，逻辑成员不应丢失。main 在其持有的独立验证树逐提交接本候选和 P3，统一生成 descriptor/runtimeId/93 项 recipe，提交干净集成 SHA 后再跑真实任务。本树没有临时重算后恢复，也没有跳过核验。

优先实际任务为 `:core:policy:test`、app Java/Kotlin 编译与 `:app:testStandardDebugUnitTest` 定向八类：AssistantNotificationNavigationAndroidTest、AssistantTaskAttachmentAndroidTest、AssistantInputAndroidTest、AssistantNotificationTicketTest、AssistantBindingTest、AssistantSessionStateTest、ControlTaskAuthorityTest、OwnedScreenOverlayTest。之后按实际布局枚举新鲜 full 任务/XML、基线身份与七夹具，再做 Release Lint/APK/merged Manifest/资产/ELF/身份检查。所有 Gradle 只用 dsha-gradle、串行执行；资源或工具链阻断时记准确阶段，不降格冒充 app 出口。

## 4. 已满足的前置与仍未满足的出口

以下核销针对 `origin/main=46f49703` 的内容/提交，不把历史 CI 或设计表的旧状态当新候选证据。

| 前置 | 已核销的主线内容与映射 | 仍不能核销 |
|---|---|---|
| A4 | `63b57efe` 是祖先；旧 `4ae22009→9c989c4a` 的 TerminalExitPolicy/TerminalSessionOwner/TerminalProcessCloser 三文件字节等价，后续 `b25ca08a` 评审修复已在主线 | 当前版 PTY/Java-JNI、proot/proroot、旋转/语言/真实退出与设备矩阵 |
| A5 | S1 `1d675b3b`、M17 `7d11e3cb/765ccc08` 已在祖先；旧 `9ae87881→765ccc08` 四个 M17 Kotlin 文件字节等价 | S1 Reader 到 owner 映射及用途收窄、S3/S4、真实退出 producer |
| A6 | 旧 `22f90135` squash 为 `498b343b`，plugins/PluginRepository/两个脚本的相关路径字节等价；`aaaf408e/b7257a1a` 与 `5ac64738` Kotlin 迁移均在主线 | PluginRepository 仍没有 startupId/opaque 执行实例的默认真实 producer；unknown mutation 必须保留租约/屏障。真实 Loader/升级/视觉不由宿主结果代替 |
| A7 | `c92f8495→c366acc1`、`39ddef7a→83a710b5`、`b6dcdc4d→e11977ad` 的授权、实体屏、虚拟屏相关路径字节等价；整轮回执 `a6b7e0ab` 是祖先 | 当前实际 Os/fdexec/su/管理器/Shizuku/ADB、双屏敏感 ASK/HANDOFF、设备视觉与覆盖升级 |
| P1 | `f3f8fc03` 及 P1 主链为祖先；历史 run43773301154 的 archived 5,328/0/0/0、七夹具、5,107 冻结身份匹配仍是 P1 历史软件证据 | 本次 P2/P3 新源码、新模块与新鲜 full 聚合不能用旧结果抵扣 |
| build369 | main 回执绑定 82eb/run43828110344 的 app 5,338/0/0/0、Lint/APK/签名/静态资产 | 其 aggregate=false、布局仍单 app，不是本候选跨模块执行结果 |
| P2/P3 | 原本地成果按第 1 节接收；旧 P3 policy 23 项真实执行保留 | 本次新增 Android、真实 app 编译、跨模块 full、Lint/APK 和最终身份仍待集成 |

A1 contractVersion/describe/ModuleHost/启停持久化 deferred、LayoutSnapshot 的 Kotlin internal 跨模块接缝、Gecko 独立评审、语言/日夜/短屏/1.3×视觉与覆盖升级仍需对应 owner 的真实合同/证据。不能继续把已合入的 A4/A6/A7 写成“待合”，也不能据此宣布整轮未验出口已完成。

## 5. P4 最小真实模块切片与精确窗口

建议首个新 Android 模块是**本机语言/i18n 平台适配的完整职责闭包**，拆为两个独立提交。不是宣称全仓不存在更小孤立类；选择此闭包是为了保留现存生产与测试、包私有生成字典的同一所有权。

### P4a：先扩展现有 policy

```text
app/src/main/java/com/deepseekharness/app/util/BackupTaskKinds.java
  → core/policy/src/main/kotlin/com/deepseekharness/app/util/BackupTaskKinds.kt
app/src/test/java/com/deepseekharness/app/util/BackupTaskKindsTest.java
  → core/policy/src/test/java/com/deepseekharness/app/util/BackupTaskKindsTest.java
```

BackupTaskKinds 是 UiStateText 的业务域依赖，只迁稳定 ID/旧标签的纯映射，不迁备份执行/状态，不改变历史文本。NetworkHealthPolicy 保持不动。

### P4b：真实 `:platform:android`

新增 `platform/android/build.gradle`（已有 Android library convention、独立 namespace、SDK37/min30/JVM17）与无组件/Provider/权限的最小库 Manifest。六个完整生产文件整体转 Kotlin，保留 FQCN：

```text
app/src/main/java/com/deepseekharness/app/util/
  SystemLanguage.java
  UiLanguagePreference.java
  UiMessageFormat.java
  UiText.java
  UiStateText.java
  AppLocaleDispatch.java
→ platform/android/src/main/kotlin/com/deepseekharness/app/util/ 同名 .kt

app/src/test/java/com/deepseekharness/app/util/
  UiLanguagePreferenceTest.java
  UiMessageFormatTest.java
  UiTextTest.java
  UiStateTextTest.java
  AppLocaleDispatchTest.java
→ platform/android/src/test/java/com/deepseekharness/app/util/ 同名 .java
```

SystemLanguage 的现存锁存/迟到 probe 测试就在 UiLanguagePreferenceTest，不另造测试身份。prepareUiLanguages 及输出 `platform/android/build/generated/uiLanguage` 一并归平台，11 个包私有 UiMessages/各语言生成类与 UiText 同模块；生成器已有 `--output`，词表、翻译、生成字节不为搬迁而重写。主 XML 资源、verifyUiLanguageResources、主 Manifest、发行身份留 app。

精确共享文件窗口：

```text
settings.gradle
ci/source-layout.json
app/build.gradle
build-logic/source-inputs.gradle
build-logic/verify.gradle
tools/unit_test_contract.py
tools/test-unit-test-contract.py
tools/test-build-conventions.py
tools/test-source-layout.py
tools/java-format-files.json
```

source-inputs 的生成根/producer 校验、unit_test_contract 的 i18n 生成所有者仍硬编码 app。先把布局扩为**显式唯一生成 owner**，保留精确 11 文件/producer/root 证明，加错 owner、漏 producer、重复/额外 generated 负控；不能泛放行任意模块 generated 目录。原 unit-contract 夹具同步移属，所有原身份保留。

保留 app→policy，加 app→platform、platform→policy，使 policy 仍为唯一无生产项目依赖叶子；platform 不反向依赖 app/R/BuildConfig。布局增加 `kind=android-library`、main/test 与实际 `testDebugUnitTest`/XML 路径，验证真实 variant 匹配。库编译和五个随行测试只是前置，仍需按布局新鲜 full、零 skip/身份守恒、Lint/APK/merged Manifest 才能提取下一模块。

layout generator 字节必变；BackupTaskKinds 是 managed launcherSource，`.java→.kt` 必须显式对齐成员键，不能删成员回避身份变化。六个 i18n 当前不在显式 launcher 键中，也不能因此承诺零维护。main 统一生成最终身份，预期维护行为以该集成证明为准。

不优先整搬 contract（Android store/registry、LayoutSnapshot internal 接缝）、DeviceGrants（构造反向调用 HttpShellService）或 NativeFileShare（现存测试还依赖 UI/DownloadTask）。不得为凑模块数创建空壳或把 app 测试反向挂到 platform。

## 6. 回退

新修复先逆序 revert `9cb67d51`、`88a1ad48`、`de5c1fbd`；原 P2 若需整组撤销，再按 `1e4d58a2` 通知、`c09bcf94` owner 及其配套测试逆序处理，i18n 的 `0dd1f902/116ee601` 成对撤销，文档单独回退。旧原提交链和树继续保留。

main 若已经接入 P3 并生成受管证明，回退必须在其独立树一并恢复布局、源/测试/生成路径和 descriptor/recipe；不能留下与源码不符的摘要。P4 未来回退先平台原子提交、再 policy 前置，整组恢复测试/生成任务/布局/门禁清单后由 main 重算证明。所有回退只处理源码与构建证明，不回滚用户数据、不清数据、不降级安装。
