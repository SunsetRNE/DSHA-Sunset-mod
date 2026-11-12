# 评审 A：Kotlin 模块化外壳可行性

对象：build359 / `0.2.1-rc.5` 的 Java 单 `:app` → Kotlin 多模块外壳（Rust 侧见评审 B）。本文只回答「Kotlin/Gradle 怎么走、代价在哪」。所有数字为本工作树实测；`AGENTS.md` 与 `docs/` 的目录数量是 build170 历史快照，与实测有偏差（见文末「未知」）。

## 结论摘要

1. **可行，但真阻碍不在 Kotlin。** 964 个 `.java`、0 个 `.kt`（`app/build.gradle:1-3` 只应用 `com.android.application`），搬目录最先撞上的是 **12 对包级 import 循环**与 **3 处按源码路径读文件的构建脚本**（`app/build.gradle:18`、`:36`、`:381`）。
2. **10 个用户模块没有 10 个 Gradle 模块。** 只有 M1→`:core:grants`、M5→`:runtime`、M6→`:ui`、M8+M10→`:device` 有真实内聚；M7/M9 二期独立；M2/M4 无独立状态、M3 横跨 5 个包，三者只做同级 package。同时必须补上用户清单没列的 4 个基础模块（`:core:policy`、`:platform:android`、`:bridge`、`:data`），否则判定层、Android 适配和桥没有归属。
3. **UI 保留 Material3 + Fragment/XML 并 Kotlin 化，不引 Compose。** `LayoutAuditInstrumentation` 的验收是 View 树 + `R.id` + 像素断言（`app/src/debug/java/com/deepseekharness/app/ui/LayoutAuditInstrumentation.java:157`、`:185-192`、`:207-230`），Compose 会让现行视觉门禁整体失明。

## 现状事实

| 事实 | 实测值 | 证据 |
|---|---|---|
| 主源码 | 964 个 `.java`、206,983 行、**0 个 `.kt`** | `app/src/main/java` 统计；`com/termux/terminal` 占其中 14 个（vendored） |
| 包规模 | ui 169、util 404、backup 83、runtime 63、core 60、根包 36、vscreen 36、screen 19、bridge 12、plugins 9 | 目录统计；`AGENTS.md`「目录地图」的 69/118/27 是 build170 历史值 |
| Android 耦合 | util 0/404、capabilities 0/6、diagnostics 0/3、skills 0/2 文件有 `import android.`；ui 165/169、runtime 36/63、core 29/60 | `grep -rl '^import android\.'` |
| 测试 | 716 个 `.java`，其中 706 个 `*Test.java`（util 385、backup 71、ui 78、core 50、runtime 44） | `app/src/test/java` 统计 |
| 资源 | 536 个 XML；`values`/`values-b+zh+Hant`/`raw-*` 等 10+ 语言目录 | `app/src/main/res` |
| Manifest | 273 行、50 个 `activity/service/receiver/provider` 组件；1 个 AIDL | `app/src/main/AndroidManifest.xml`；`app/src/main/aidl/.../IShellService.aidl` |
| 原生 | 14 个 `.so`，含必须保符号的 `libproot.so`/`libprootloader.so` | `app/src/main/jniLibs`；`app/build.gradle:171-186` |
| 外部依赖 | 12 个坐标（10 编译/运行期 + 2 测试），无 Kotlin、无 Compose | `app/build.gradle:509-530` |
| 自定义 Gradle 逻辑 | 19 个 `tasks.register`，多数 `Exec` + `--check` | `app/build.gradle` 全文 |

**构建脚本耦合（真阻碍，不是工作量）**

| 位置 | 现状 | 一旦移目录的后果 |
|---|---|---|
| `app/build.gradle:18-30` | 正则读 `util/Constants.java` 断言 `DSH_VERSION` | `Constants.java` 换路径即 `BUNDLED_DSH_NATIVE_VERSION_ANCHOR` 失败 |
| `app/build.gradle:381` | `launcherSources` 按 `src/main/java/com/deepseekharness/app/${it}` 拼路径 | 启动器源码下沉模块后 `prepareRuntimeDescriptor --check` 失败 |
| `app/build.gradle:215-217` | `main.assets`/`main.java` 指向 `build/generated/*` | 模块化后生成源集归属须重新指定，否则 i18n 与 assets 静默缺失 |
| `app/build.gradle:224-246` | 证书/包名/`applicationId` 三重核对 | 只能留 `:app`，不能被库模块分担 |
| `app/build.gradle:41-50`、`:106-122` | 3 种测试变体互斥 + 单一 `standard` flavor | flavor 在 `:app`；`standardImplementation` 下沉到模块后变体矩阵翻倍 |
| `tools/test-architecture-boundaries.py:10-16` | 按 `app/src/main/java/.../util/*.java` 路径正则 | 路径变化即失守，须同步改写（顺带可升级为真依赖检查） |

**包级 import 循环（必须先破，Gradle 不允许模块环）**

12 对：`util↔backup`（15/24）、`util↔core`（1/111）、`backup↔core`（9/26）、`backup↔runtime`（2/38）、`core↔plugins`（1/6）、`<root>↔core`（11/13）、`<root>↔ui`（4/133）、`<root>↔bridge`（2/1）、`ui↔transfer`、`ui↔recovery`、`ui↔vscreen`、`vscreen↔screen`。关键点：**util 不是叶子**——14 个文件 `import com.deepseekharness.app.backup`（如 `util/MaintenanceTransaction.java`、`util/RuntimeUpdateTransaction.java`）、`util/ScreenSessionGrant.java:4` import `core.RuntimeSnapshot`。所谓「util 全包零 Android import」只对 `import android`/`androidx` 成立（正则强制），不代表它可独立成模块。

## 模块切分建议

| Gradle 模块 | 包路径 | 主要来源 | 允许依赖 | 禁止依赖 |
|---|---|---|---|---|
| `:app` | `com.deepseekharness.app` | 根包 36、Manifest、assets、签名、变体、生成源集 | 全部 | — |
| `:core:policy` | `…app.core.policy` | util 的零 Android 子集（`ShellQuote`/`HttpProtocol`/`UpdatePolicy`…） | 无（唯一叶子） | 一切模块 |
| `:core:grants` | `…app.core.grants` | `capabilities/` 6 个（0 Android import）+ `core/DeviceGrants` | `:core:policy`、`:platform:android` 端口 | `:ui`、`:device` 实现 |
| `:platform:android` | `…app.platform` | 通知、SAF、Keystore、SharedPreferences、SystemLanguage 适配 | `:core:policy` | `:ui`/`:device`/`:runtime` |
| `:bridge` | `…app.bridge` | `bridge/` 12 + `HttpShellService` + LAN | `:core:policy`、`:platform:android`、`:runtime`、`:device`（现状 `bridge/AdbBridge.java:5`、`bridge/AdbVirtualScreenStart.java:3-4`） | `:ui` |
| `:device` | `…app.device`/`.screen`/`.vscreen` | device 6 + screen 19 + vscreen 36 + `DeviceBridgeService` 等 | `:core:policy`、`:core:grants`、`:platform:android` | `:ui`、`:data`、`:runtime`、`:bridge` 实现（运输契约放 `:core:policy`） |
| `:runtime` | `…app.runtime` | runtime 63、`ProotBootstrap`/`ContainerRuntime` | `:core:policy`、`:platform:android`、`:data` 契约 | `:ui`、`:device` 实现 |
| `:data` | `…app.data`/`.backup` | backup 83 + data 4 | `:core:policy`、`:platform:android` | `:ui`、`:runtime` 实现（现状 38 处 import 需反转） |
| `:ui` | `…app.ui` | ui 169、31 Activity、20 Fragment | 各模块 `*Api` 契约 + `:core:policy` | 被任何非 `:app` 模块依赖 |

规则一句话：**依赖方向单向，`:core:policy` 是唯一叶子，`:ui` 只许被 `:app` 依赖**。注意根包 36 个文件必须**按文件拆**而不是整包搬：`DshaApp` 属 `:app`、`HttpShellService` 属 `:bridge`、`DeviceBridgeService`/`ShellService` 属 `:device`，其中 28 个文件有 Android import。现状 `recovery→ui`、`transfer→ui`、`assistant→ui`、`vscreen→ui` 各 1–2 处、以及 `BridgeAskDialog`/`HttpShellService` 引用 `ui/DshaDialogBuilder`（`grep DshaDialogBuilder`）都属反向边，必须换成端口注入。

**10 模块映射**

| 用户模块 | Kotlin 侧形态 | 落点 | 判据 |
|---|---|---|---|
| 1 安卓权限控制 | 一等 | `:core:grants` | `capabilities/` 6 文件零 Android import，已是可移植判定层 |
| 2 网络请求 | **同级 package** | `:bridge` + `nethealth/`（2 文件） | 无独立状态与生命周期，独立模块只会与 bridge 互相 import |
| 3 更新与覆盖 | **不独立，拆两处** | APK 自更新→`:app`；受管更新→`:runtime` | `Update*` 散在根包/core/ui/util 5 个包，无内聚边界 |
| 4 日志输出 | 同级 package | `:platform:android` + `:core:policy` | 无独立日志包；44 个文件直接用 `android.util.Log`，`DiagnosticLog` 在 core |
| 5 容器生命周期与挂载 | 一等 | `:runtime` | 63 文件，启动/停止身份核验集中于此 |
| 6 外壳 UI 渲染框架 | 一等 | `:ui` | 169 文件、536 XML |
| 7 终端 | **二期**一等 | 先 `:ui` 内 package，后 `:feature:terminal` | 依赖 Termux AAR + `libtermux.so`（`app/build.gradle:516-522`） |
| 8 Root/ADB 集成 | 一等 | `:device` | `ShellService`/`ShizukuShell`/`RootShell`/`DeviceBridgeService` |
| 9 插件热更新热检查 | **二期**一等 | 先 `:data`+`:runtime` package，后 `:feature:plugin` | `plugins/` 9 + `core/PluginRepository`，依赖 CAS 与事务 |
| 10 ADB 识屏 + 无障碍 | 一等（`:device` 子边界） | `screen/` 19 + `vscreen/` 36 | 两者已互相 import（6/1），必须先合再分 |

## UI 路线对比

| 维度 | A：保留 Material3 + Fragment/XML，Kotlin 化（推荐） | B：Compose 重写 |
|---|---|---|
| 改造面 | 169 类、536 XML 原地演化，按批 Kotlin 化 | 全量重写页面 + 主题 + 组件 |
| 视觉验收 | `LayoutAuditInstrumentation` 的 View/`R.id`/43.5dp 与 1.5dp 居中容差继续有效（`:157`、`:207-230`） | 断言对象消失，需先建 Compose 等价门禁，期间无门禁 |
| i18n | 生成物继续落 `util` 包 + `values-*`（`tools/prepare-ui-languages.py:12`；3763 条） | 需新增 CompositionLocal/资源读取路径，`verifyUiLanguageResources` 同步改 |
| WebView 外壳 | 现状 View 层直接可用 | 需 `AndroidView` 互操作 |
| 回退 | 单文件级可回退 | 一次性切换，无中间态 |
| 风险 | 低：只增加语言与模块边界 | 高：验收盲区 + 构建新增 Kotlin/Compose 工具链（当前依赖表无此坐标，`app/build.gradle:509-530`） |

**推荐 A。** Compose 仅允许在「新页面、且不替换已有页」的前提下单独评审，不作为模块化载体。

## 冲突与保留不变式

| 不变式 | 现状证据 | 模块化的正确做法 | 破坏后的表现 |
|---|---|---|---|
| util 零 Android import | `tools/test-architecture-boundaries.py:10-12` 正则；`util/SystemLanguage.java:98-132` 用 `Class.forName` 绕开 import | 门禁随路径改写，规则收紧为「`:core:policy` 同时不许依赖任何模块」；反射例外需登记 | 规则变口号，纯逻辑层静默依赖框架 |
| 706 个测试类 | `app/build.gradle:206-212`、`:318-323`（`dsha.assetsDir`）、`:532-538`（Robolectric 离线目录） | 用 convention plugin 统一注入三处配置；测试目录随被测类迁移，禁止「先删测试」 | 模块化后测试大面积跳过，且 `sourceOnlyTests` 掩盖 |
| `LayoutAuditInstrumentation` | 在 `app/src/debug`（非 androidTest），断言类名/尺寸/配色 | 只搬包不搬语义；`am start -n com.dsh.client/...`（`:314`）这类硬编码包名一并清理 | 视觉门禁失真或直接跑不起来 |
| `DshaDialogBuilder` | `ui/` 定义，被根包 `BridgeAskDialog`、`HttpShellService` 引用 | 弹窗能力下沉 `:platform:android` 或注入端口，服务层不 import `:ui` | 层级倒挂固化进 Gradle 图 |
| `UiStateText` 语言重渲染 | `util/UiStateText.java`（零 Android import） | 留在 `:core:policy`，保持无状态纯函数 | 文案被缓存，切语言后旧串残留 |
| 系统语言尽早锁存 | `DshaApp.java:152` 早于 `:154 LanguageController.apply`、`:157 ThemeController.apply` | Application 只留 `:app`；合并 Manifest 后核对是否引入早于 `onCreate` 的 `ContentProvider`（库模块自动合并） | 「跟随系统」一次切换即自我锁死 |

## 分批策略

**不要一次全量重写**：706 个测试类 + 50 个 Manifest 组件 + 19 个自定义任务，任何一批出错都无法靠「能编译」发现。

| 批 | 范围 | 退出判据 | 回退点 |
|---|---|---|---|
| B0 破环（不动 Gradle） | util 拆 `util-policy`/`util-host`；反转 util 的 17 处反向 import（backup 14 / capabilities 2 / core 1）；切 `runtime↔backup`、`core↔plugins`、`ui↔<root>` 单点 | `tools/test-architecture-boundaries.py` PASS + 全量单测 + `assembleStandardRelease` | 分支内 revert，无产物变化 |
| B1 建判定层（Java） | `:core:policy`、`:platform:android`、`:core:grants` | Lint + 单测 + APK 内容比对（不新装） | 单提交 revert |
| B2 运行时与数据 | `:runtime`、`:data` | 启动/停止/维护路径不变；runtime 与 backup 定向测试 | tag 回切 |
| B3 设备与桥 | `:device`、`:bridge`（识屏只统一接口，不改凭据语义） | 桥协议 `BRIDGE_PROTOCOL` 不变、设备审计用例 | tag 回切 |
| B4 UI 模块化 + Kotlin 化 | `:ui`，先 Fragment/Activity 类，再布局逻辑 | `LayoutAuditInstrumentation` style 中英/日夜/短屏/1.3× | 单文件级 |

Kotlin 引入规则：**按模块切换，不按文件混写**——新模块可以是 Kotlin，老文件在被改动前保持 Java；禁止同一文件内 Java/Kotlin 互改。语言插件与格式化工具（现锁 google-java-format，`tools/format-java.py:264`）必须同批加锁，否则仓库格式门禁失效。

## 风险与未知

| 项 | 状态 | 说明 |
|---|---|---|
| Kotlin 插件离线可得性 | **UNKNOWN** | 本容器 `/root/.gradle` 不存在、本轮禁止运行 Gradle，无法核验 `dsha-gradle --offline` 能否解析 KGP；`gradle.properties:4` 为 `caching=false` |
| AGP 9.1.1 × KGP 版本兼容 | **UNKNOWN** | 仓库当前无任何 Kotlin 坐标（`app/build.gradle:1-3`），兼容矩阵未核验 |
| 模块化对 APK 体积/增量编译的收益 | **UNKNOWN** | 无构建数据，本文不作数字承诺 |
| R8/资源压缩影响 | 低但未测 | `minifyEnabled false`（`app/build.gradle:165`），多模块 R8 只能在启用 minify 后验证 |
| Manifest 合并顺序 | 已知风险 | 库模块的 `ContentProvider` 早于 `Application.onCreate`，威胁 `DshaApp.java:152` 的语言锁存 |
| androidTest 门禁可用性 | 已知缺口 | `docs/design/hide-from-recents-20261106/README.md:49` 记录既有编译错误，B4 不能把 `LayoutAuditInstrumentation` 当唯一门禁 |
| 文档数量漂移 | 已知 | 任务书「ui 69」、`AGENTS.md` 118 util，实测 169/404；迁移前须先修文档，否则评审基准错位 |

## 给 Lead 的一句话结论

**可行但应降级为「两阶段工程」：先破依赖环并落 7 个 Java 模块（B0–B3），再按模块增量 Kotlin 化 UI（B4）；Compose 重写与「十个模块各一个 Gradle 模块」都应否决，前者会打掉唯一视觉门禁，后者会把现有循环固化成不可编译的模块图。**
