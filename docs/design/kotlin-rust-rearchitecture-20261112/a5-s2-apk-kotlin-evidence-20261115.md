# A5 S2 / M17：APK 更新 Kotlin 迁移与 owner 收口回执

> 本地候选，基于已发布 build366 的 `origin/main=5ccc8aa3db4c961553a348a8eb35cbfa815be11c`。本会话不合并、不 push、不发布、不安装、不操作手机。本文不将新行为记入已发布 build366，也不宣布 A5 整轮完成。

## 1. 提交边界

- 结构提交：`27196840b2bbc8534d31977e4c1f86efd647fe6e`。完整迁移四个 M17 类，Application 装配唯一 engine/队列；此提交仍保留原自动检查行为。
- 行为软件验证快照：`9ae8788137a335ae24af544ab278b62206ccc8a1`。停止无人触发的新检查/提示，绑定原授权请求及回调身份，保留用户主动路径与已发起下载的续接。最终交付 HEAD 另在完成声明记载；软件验证后若只补文档，以源码逐文件摘要一致证明对应关系。
- 任务板无真实任务，未创建或认领虚构项。新建独立分支 `feat/a5-s2-apk-kotlin`，没有接管旧 A5 工作树，没有修改主检出。

## 2. 完整职责与 Java 互操作

| 文件 | 最终职责 |
|---|---|
| [DshaApp](../../../app/src/main/java/com/deepseekharness/app/DshaApp.java) | `ApplicationOwner<UpdateEngine>` 懒装配一个实例和一个串行执行器；检查、下载、安装前校验借用此队列 |
| [UpdateEngine](../../../app/src/main/java/com/deepseekharness/app/core/UpdateEngine.kt) | 清单、通道、持久任务、可取消传输、完整 APK 校验、权限回调代次；`get(Context)` 只委托 Application，不保留静态实例/IO 队列 |
| [UpdateRepository](../../../app/src/main/java/com/deepseekharness/app/core/UpdateRepository.kt) | ViewModel 呈现、用户请求和结果单次领取；不创建第二条生产校验队列；可控执行器/校验端口只供既有测试注入 |
| [UpdateDownloadService](../../../app/src/main/java/com/deepseekharness/app/UpdateDownloadService.kt) | 前台通知、用户取消、系统重投递、配额超时与唤醒锁；续接仍由同一 engine 决定 |
| [UpdateActivity](../../../app/src/main/java/com/deepseekharness/app/ui/UpdateActivity.kt) | 原 Fragment/View/XML 体系中的页面呈现、系统权限请求和前台一次性安装派发；不持有另一份执行状态 |

四份原 Java 生产实现已删除，没有 Java/Kotlin 双实现。相对 base 的文件变化为生产 Java −4、生产 Kotlin +4、测试 Java +1；另修改 9 份既有 Java 的组合根/入口或直接回归夹具。Java `UpdateEngine.get`、`channelName`、`FEED`、既有构造形态、仓库方法及嵌套 `State`/`InstallState` 字段形态保留；字段使用 `@JvmField`，静态方法使用 `@JvmStatic`，checked exception 声明保留。Java `new UpdateEngine(app, null)` 不产生重载歧义；`setChannel(null)`、可空 message/stage 的旧行为有真实 Java 回归。Application 使用三参构造注入队列。

生产入口仍是设置页打开更新页、页面主动检查/选通道、下载服务取得 engine，以及前台页面领取安装结果。两份旧设备夹具改为替换 Application owner 槽位，不再反射已删除的静态 singleton；后续移除自动段的夹具不再写 `startupChecked`。M17 夹具中的旧硬编码启动包名改为实际目标包名；这些设备夹具未在手机执行。

## 3. 自动段与原授权续接

1. Main 不再订阅/请求 APK 启动检查，不再在启动、返回、聚焦或旋转时弹更新 Snackbar。更新页首次打开或重建也不自行检查。旧 `checkOnStartup` / `startupNotice` / `markStartupNoticeShown` Java 入口保留为无副作用兼容口。
2. 用户点击检查、切换通道、选择下载、重新校验和系统 `FileProvider` URI + `ACTION_VIEW` 安装路径保留。旧 `check_update` 偏好仍由 ConfigStore 原键读写、导入/导出；旧值 true 不授权新检查，配置开关隐藏，不重置旧值。
3. 权限票绑定原 Release 对象（包含摘要）、查询通道、已验证 APK（安装请求）、action revision 和唯一系统请求号。取消、重新检查/换候选、开始下载或安装请求会退休旧权限代次；匹配回调先消费，再执行。重复或错号回调不消费后来请求，不用错误回执撤销新请求。
4. 系统请求号在原 `dsha-updates` 偏好中新增一个字符串计数 `permission_request_code`，先同步保存再派发，范围 `0x4000..0xfffe`。旧键和 task JSON/schema 不变。耗尽、损坏或保存失败均拒绝分配，不回绕复用号码。
5. 通知权限明确允许或拒绝都可续接已经点按的前台下载；空、缺项、非目标或未知回执只退休原票。未知来源设置使用带请求号的结果回调，返回后实查 `canRequestPackageInstalls()`，不要求 `RESULT_OK`，不靠 `onResume` 自动重新发起安装。
6. 页面旋转/语言重建复用原 ViewModel 票；进程重建后只有旧 Bundle 标记而没有票时，只提示中断。旧标记不补造授权。`installFailed` 增加请求代次，迟到校验不能覆盖失败或新的明确请求。
7. 已明确发起且持久阶段为 downloading 的任务仍可由服务续接；重复服务投递只排一份传输，取消/暂停不自动重放。若重命名 APK 后、ready 回执落盘前进程终止，恢复先观察已命名文件，不重新下载该份字节；文件存在只提供安装前复核入口，摘要、包名、版本、非调试属性和完整签名集合仍必须重验。
8. 安装领取先消费，再派发 `ACTION_VIEW`。页面重建、旧回调、派发后未知结果不会自动再次领取或打开安装器。

APK ready、权限允许、安装器打开都不是受管运行时 healthy/latest/ready 证明。`RuntimeMaintenanceStarter`、`EnvironmentAccess`、`EnvironmentIdentity` 与维护/试运行/事务执行代码未修改；同候选自动维护一次保持原规则。没有新增 Web 生命周期、维护锁或 Rust/备份/终端/设备执行入口。

## 4. 受管输入登记

基线 descriptor 的 runtimeId 为 `f540d7f1988b4854b65660993a9d3f5743b0dca78bce8e51997c45b9773de7be`，205 个资产输入、507 个启动输入。成员没有增删，四个 M17 类仍是原有的 APK 更新职责，不因改后缀扩大运行时启动清单。

唯一改变的既有受管源码为 `DshaApp.java`：基线 SHA-256 `f1042cf7de0baa8c93f3134f123ccebbf2c1347ab583da68a1a700bdee12e5c8`，当前 `52647485e5a73a9455134e54480f65ba7a2898b08376bac9e8b2399016ba8e32`。实际枚举回执在本机 `/tmp/a5-s2-managed-input-diff-aa68a2e5.json`。未修改 managed-runtime-inputs、descriptor、版本号、Gradle/KGP 配置或依赖锁。

因此不主张零维护：main 应在最终串行合并树重算 descriptor 并核源/包字节，预期新候选需要一次整屏维护。worker 不为取得编译绿灯跳过门禁或提交临时 descriptor。

## 5. 实际验证与边界

| 检查 | 实际结果 |
|---|---|
| 首次 Gradle 定向基线 | `dsha-gradle :app:testStandardDebugUnitTest` 指定 UpdateEngineCheck/UpdateVersionPage/UpdatePolicy/UpdateManifest/ResumableDownload/UpdateActionPolicy；exit 1，prepareBackupAssets 缺 dsh-runtime.bin，未到测试 |
| 固定输入还原 | 本地已核验包 `a6-fixed-inputs-20261113-build364.zip`，SHA-256 `2c8dc3fa494a5faf6f65cafa966b47d234a3331c30396198005cedfccca82cad`；ci-assets 还原 exit 0，未改跟踪输入证明 |
| Kotlin Gradle 尝试 | `dsha-gradle :app:compileStandardDebugKotlin`；prepareBackupAssets 成功，verifyRuntimeDescriptorInputs 因 `DESCRIPTOR_LAUNCHER_BYTES: DshaApp.java` exit 1，未到 Kotlin 编译/单测；未使用 `-x` |
| 当前源纯 JVM 基线 | 锁定 JUnit 4.13.2 / Hamcrest 1.3 / Gson 2.13.1，javac17 新编真实源码/文案；8 类 **63 passed / 0 failure / 0 ignored / 0 assumption** |
| 结构提交独立编译 | 冻结 27196840；固定 Kotlin 2.2.10/JVM17 的 5 个生产 kt + 1064 个生产 Java + 真实 AIDL/AGP BuildConfig/11 份 UiMessages，exit 0；全部 class major=61，输入摘要前后一致 |
| 结构提交 Robolectric | API34；UpdateEngineCheck 6 + UpdateOwnership 10 + UpdateVersionPage 10 = **26 passed / 0 failure / 0 ignored / 0 assumption** |
| 行为候选编译/回归 | 冻结 9ae87881；5 kt + 1064 生产 Java 新编 exit 0，全部 class major=61，1072 条输入摘要前后同；EngineCheck 7 + Ownership 19 + VersionPage 12 = **38 passed / 0 failure / 0 ignored / 0 assumption** |
| 边界、输入契约、源卫生、登记 | 最终重跑：core boundaries 25 通过；runtime input contract 18 通过；source hygiene PASS；host manifest PASS，259 项登记（仅登记完整，不等于执行全部测试） |
| 格式、链接与 diff | Java 全树 **1824/1824，0 待格式化**；Kotlin 全树 **6 文件，0 待格式化**，均 exit 0；默认文档 **56 文档/1324 链接、0 error/warning**；5 份指南/任务/证据 **285 链接、0 error/warning**；6 份显式检查保留 page-map 三个已证实的基线死链，不记为全绿；提交 diff 无新增空白问题 |

纯 JVM 8 类逐项为 UpdatePolicy 9、UpdateManifest 8、UpdateFailures 7、UpdateLocations 5、UpdateRedirects 5、ResumableDownload 6、UpdateActionPolicy 15、EnvironmentIdentity 8。编译前后真实源码摘要一致，不使用历史 app class 或 Android 替身。

独立 Android 编译使用仓库外临时解包的 Ubuntu ARM aapt2 2.19、真实 aidl 和 AGP9.1.1 BuildConfigGenerator，所有真实应用/依赖资源编译，没有手写 R 或占位生产类。旧 aapt2 无法读取 SDK37 resources.arsc，API37 link exit 1 原样保留；改用现有 Robolectric API34 的真实 framework 资源后，32 组资源 compile/link 成功并生成 48 包 R。Java 类型检查仍使用真实 SDK37 android.jar。这条路径不等于通过 AGP merger、API37 完整资源、资产/descriptor、Lint、APK 或签名门禁。

Robolectric 的 EngineCheck/Ownership 测试运行真实 engine/owner/仓库。版本页沿用 Engine 边界 shadow；新增一次派发回归还隔离 URI 提供者，实际 Activity 生命周期、ViewModel 请求/领取和 `ACTION_VIEW` 派发仍运行页面代码。这些测试不称真实系统安装器验收。

主编译/单测依赖按当前固定声明及缓存 POM/module 约束收敛，不拿缓存最高版本替换 pin。唯一补下载的测试依赖 androidx.tracing:tracing:1.1.0 来自官方 Google Maven，并核发布 module 的 SHA；它不在当前仓库 byte lock 中，未伪称已纳入仓库锁。

本机详细日志与实际 argfile 根目录为 `/tmp/a5-s2-verify-aa68a2e5/`：结构编译 `m17-20261114T194941078227Z`，结构测试 `robolectric-20261114T195513570673Z`；API37 失败 `android-inputs-20261114T194609293184Z/resource-link.log`；API34 成功 `android-inputs-20261114T194830560985Z`。父会话 Gradle 日志为 `/tmp/a5-s2-baseline-aa68a2e5.log`、`/tmp/a5-s2-kotlin-compile-aa68a2e5.log`。初次临时驱动 XML namespace 解析失败、原生工具路径映射失败及后续修复均保留，不计入测试通过数。

行为候选编译日志为 `/tmp/a5-s2-verify-aa68a2e5/m17-20261114T200705114412Z/run.log`，三类 38 项原始回执为 `/tmp/a5-s2-verify-aa68a2e5/robolectric-20261114T200807314009Z/junit.log`。C2 产出 18 个 Kotlin class、2533 个 Java class；`app/src/main/java` Git tree 为 `35854c15530e20a3a4c8ad43c42ca50bc349dd20`，`app/src/test/java` 为 `85c09f1d875bd146bb088499a3201274d41025b2`，最终 docs-only amend 后据此及 2414 条源/资源 SHA 证明验证对应关系。完整命令、argfile 与依赖来源分别保存在该目录的 `verification-report.md` 和 `behavior-verification-report.md`。

显式 6 文档检查为 316 个有效目标、3 个错误（exit 1）：page-map 原有的 OnboardingPrepareActivity、WelcomeActivity、SkillsActivity 链接在 5ccc8aa3 已不存在；本次该文档唯一变化为 UpdateActivity 导航改后缀，没有新增错误，也没有扩大窗口修其它导航。证明在 `/tmp/a5-s2-preexisting-doc-links-aa68a2e5.json`。A5 基线仅 74/75/89 三行中的 9 个历史链接固定到原完整 d9b75bc5 SHA；5 份原 Java 远端文件实际 HTTP 200 下载，逐字节与 Git blob 对照，9 个原行号有效，回执在 `/tmp/a5-s2-historical-links-aa68a2e5.json`。两份当前指南、page-map 和 A4 的现行 M17 导航指向 Kotlin，不改 A5 的历史结论或任务状态。

## 6. 未跑项与回退

未跑完整 Gradle 单测、Lint、APK/签名、实际系统安装器确认/取消、真实前台服务/配额与进程终止、Android/ROM 矩阵、覆盖安装/维护页、中英日夜短屏大字体/PiP。真实安装后的版本和环境就绪必须另行观察，不能由本回执推导。

S1 基线 §12 的 UI Reader owner 映射/用途命名待办、S3/S4 和 A5 整轮出口不在本次 M17 代码写域，未冒称完成。最终受管 descriptor、CI 与独立完整审查交 main；手机验收由用户另行安排。

回退自动段行为时 revert 第二提交，保留第一提交的 Kotlin/Application owner；全部回退按第二 → 第一提交顺序。额外本机请求号字符串可由旧实现忽略，不重命名或清空旧偏好、已下载包或用户数据。若以后正式发行回退，由 main 使用更高 versionCode 重新发布，不降级安装、不恢复用户数据快照。
