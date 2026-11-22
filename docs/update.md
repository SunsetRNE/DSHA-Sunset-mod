# 应用更新与受管运行时更新

> 文档边界：本文的 APK 更新行为描述基于已发布 build367 / `f3f8fc03` 之上的 build368 候选源码（`0.2.1-rc.5-20261116.0500-dsh0.2.2-rc.1` / versionCode 368）；候选尚未发布，完整 CI 与设备验收待完成，不能据此称已发布 build367 具备这些新行为。已发布身份为 `io.github.sunsetrne.dsha` / 单一 Standard / build367。F10–F13/build190 记录属于历史；本文未检查实时远端清单、安装 APK 或执行设备验收。

应用 APK 更新和 Ubuntu 内受管组件更新是两条链。APK 先通过下载与包身份检查，再交系统安装器；受管组件先审阅计划、正常停止运行任务、准备候选并试运行，再事务切换。两者都保留具体失败状态，不能用版本文案替代实际结果。

## 用户发起 APK 检查

本地 A5 S2 候选中，[MainActivity](../app/src/main/java/com/deepseekharness/app/ui/MainActivity.java) 启动、旋转、恢复前台或重新获得焦点均不自动检查 APK 更新，也不弹更新 Snackbar；[UpdateActivity](../app/src/main/java/com/deepseekharness/app/ui/UpdateActivity.kt) 首次进入和重建同样不隐式检查。[UpdateEngine](../app/src/main/java/com/deepseekharness/app/core/UpdateEngine.kt) 保留 Java 调用形态的 `checkOnStartup`、`startupNotice`、`markStartupNoticeShown` 兼容入口，但它们不发起任务或提示，`startupNotice` 恒返回 `null`。用户点击检查、切换通道触发的检查仍保留。

[ConfigStore](../app/src/main/java/com/deepseekharness/app/core/ConfigStore.java) 的旧 `check_update` 键仍可读写、导入和导出；旧值 `true` 不授权新的自动检查。[配置页](../app/src/main/java/com/deepseekharness/app/ui/ConfigFragment.java) 隐藏旧开关，不借此重置历史偏好。

唯一 [UpdateEngine](../app/src/main/java/com/deepseekharness/app/core/UpdateEngine.kt) 由 [DshaApp](../app/src/main/java/com/deepseekharness/app/DshaApp.java) 持有；检查、下载与安装前校验共用应用级串行队列。[UpdateRepository](../app/src/main/java/com/deepseekharness/app/core/UpdateRepository.kt) 保留原请求的权限交接与安装结果单次领取状态，页面观察、呈现和领取，不因离页或重建另起一套引擎。

用户发起的检查读取固定 HTTPS 清单 `https://dsha.cc/api/updates.json`，要求 `schemaVersion=1`，清单上限 1 MiB。当前来源策略在 [UpdateLocations](../app/src/main/java/com/deepseekharness/app/util/UpdateLocations.java) 中，不因 README 的私有预发布下载链接而自动改为私有仓库。

清单请求使用 `DSHA/<VERSION_NAME>` User-Agent；接收服务会看到正常网络请求信息。该检查链未附加对话、终端命令或 API Key。它不是自动诊断上报；实时服务隐私政策、可用性与当前清单内容不由本次源码核验确认。

## 通道与候选

| 选项/操作 | 何时生效 | 当前规则与失败 |
|---|---|---|
| 稳定/预览通道 | 用户切换即检查；检查中切换作废旧检查并按新通道重查，下载/校验时锁定 | 稳定通道只收 stable；预览可收 stable/preview；按更大的 versionCode 选择，不能按 rc 文件名猜先后 |
| 默认通道 | 无已保存选择时初始化 | 版本名含 `-` 默认 preview，否则 stable |
| 重新检查 | 用户点击；首次进入或重建更新页不隐式触发 | 网络失败保留当前通道上次结果；来源不明的旧候选不能直接安装 |
| 下载/继续下载 | 用户选择候选后启动应用范围的前台下载任务 | 离开页面/旋转不重新开始；取消入口在下载通知栏（页面不放取消），取消保留部分进度，继续须再次明确请求 |
| 安装更新 | 完整校验后由前台页面领取一次结果 | 校验期间通道或候选改变则拒绝；进程重建中断权限交接或校验后要求用户重试 |

页面只有一个主按钮，它本身就是进度条，宽高与位置不变：检查中流光 → 下载中从起始边填充并显示百分比与字节数 → 核验中填满 → “安装更新”；进行中主按钮不可点，检查中可切通道，下载/校验与安装交接时锁定通道。显示规则由 [UpdateActionPolicy](../app/src/main/java/com/deepseekharness/app/util/UpdateActionPolicy.java) 推导。平板横屏（≥ 840dp）为两栏：左栏主卡与管理，右栏“候选版本 / 本版本”更新记录。

[UpdatePolicy](../app/src/main/java/com/deepseekharness/app/util/UpdatePolicy.java) 还检查当前 flavor、minSdk、`arm64-v8a`、版本码、有效 HTTPS 来源、64 位十六进制 SHA-256 与大小（至多 1 GiB）。旧候选记录的“查询通道”与发布自身通道分开保存，不能从当前偏好推断来源。

同为 code190 的历史 F10/F11 修订即使版本名更晚，也不会被应用内更新器当作更高版本选中；更新器要求候选 `versionCode > BuildConfig.VERSION_CODE`。当前公开预发布 Release 的附件与摘要请从 [README](../README.md) 和实际 Release 核查；附件状态按实际 Release 及独立外部回执核验，不能由“暂无更新”推断远端没有预发布附件。

## 下载、校验与系统安装

固定来源只允许 dsha.cc 的下载路径与 DSH-APP/DSHA 的 GitHub Release APK 路径；重定向仍逐跳检查，GitHub 资产 CDN 有受限例外。域名允许列表不等于任意同名包受信任。下载使用 [ResumableDownload](../app/src/main/java/com/deepseekharness/app/util/ResumableDownload.java) 核对实际字节和 SHA-256，安装前重新核对，随后检查：

- 与当前安装的包名相同，且 `debuggable=false`。
- 实际 versionCode 与清单相符且更大，Android 版本兼容。
- versionName 与当前单一 Standard 变体对应；Low 已删除，不再接受 Low 候选。
- 候选完整当前签名集合与已安装应用完全一致，不能只匹配一个子集。

当前正式交付使用 `io.github.sunsetrne.dsha` 和 Mod 自有发布证书（见 [release-identity.json](../ci/release-identity.json)）；签名比较实际针对已安装证书集合，不凭文档中的指纹缩写放行。摘要证明字节一致，同签名证明安装身份延续，两者都不证明插件代码无害。手动 APK 核验见 [下载校验](download-verification.md)。

[UpdateDownloadService](../app/src/main/java/com/deepseekharness/app/UpdateDownloadService.kt) 可续接已发起且记录为 `downloading` 的任务；`cancelled`、`paused` 或未知状态不会因打开页面、重建或旧回调自动产生新任务。若 APK 已完成 rename、但 `ready` 回执尚未保存，恢复路径先观察已有 APK，不再次下载已完成的字节；文件存在只提供安装前复核入口，不代替完整校验。

权限交接使用唯一、单调的 16 位 request code。`dsha-updates` 中新增字符串 `permission_request_code` 作为本机计数，成功保存后才派发请求，不复用旧编号；计数损坏、保存失败或耗尽时拒绝新请求。一次性 token 绑定原 release 对象及其 SHA-256、通道、APK 与动作代次（action revision）；命中请求号后先消费，再核对原请求是否仍有效。取消、旧回调、空或未知结果、进程重建丢失 token 都不能重放原动作，也不能借旧回调领取新候选。Bundle 只保留用于呈现中断的标记，不保存或补造可执行 token。

Android 13+ 下载前仍请求通知权限。明确授予或明确拒绝通知的有效回执只可续接原先用户发起的前台服务（FGS）下载一次；空或未知回执不启动下载、不重新申请。通知权限不授予安装权限。

Android 8+ 未允许此应用安装未知来源时，页面以动态请求号调用 `startActivityForResult` 打开系统设置，再由 `onActivityResult` 实查 `canRequestPackageInstalls()`；设置页可能返回 `RESULT_CANCELED`，不以 `RESULT_OK` 为前提。有效原请求才可继续安装前校验，不靠 `onResume` 重新发起安装。进程重建丢失 token 时呈现中断并等待用户重新操作；系统安装器仍由用户确认，不做静默安装。

**本候选的安装派发仍是 FileProvider URI + `ACTION_VIEW`。** [UpdateActivity.dispatchInstall](../app/src/main/java/com/deepseekharness/app/ui/UpdateActivity.kt) 没有历史 build173 R09 的 PackageInstaller 会话围栏，也没有在这里调用维护停止链。因此覆盖更新前应在应用内正常停止 DSH 和终端，并确认停止完成；外部 ADB/文件管理器覆盖同样不能继承应用内受管组件的停止证明。历史安装器验收不能代替本候选的安装证据。

若系统取消安装，原应用仍在；打开系统安装器或完成 APK 安装派发都不能证明覆盖完成，也不等于运行时 ready。本次 M17 调整不改变 M11 的同候选自动维护一次或其他维护门禁。更新后应核对实际版本并检查启动状态。无需为签名不符卸载原应用；卸载/清数据可能丢失应用私有数据、本机密钥和副本。重要数据先按 [备份与恢复](backup-and-restore.md) 导出。

## Ubuntu 内受管更新

更新页的“检查更新计划”进入环境维护页，比较当前环境和包内组件，确认后走[EnvironmentMaintenance](../app/src/main/java/com/deepseekharness/app/core/EnvironmentMaintenance.java)。同一 Ubuntu base 下只更新受管组件；个人数据保持原位，UI-only APK 变化不应直接重建环境。基础不适合局部更新时，进入保数据重建计划。

受管提交先经过[MaintenanceCoordinator](../app/src/main/java/com/deepseekharness/app/core/MaintenanceCoordinator.java)正常停止 Web/终端和退出屏障，再准备候选、记录摘要及前代、进行[RuntimeTrial](../app/src/main/java/com/deepseekharness/app/runtime/RuntimeTrial.java)，核验真实端口/鉴权、会话与存储、网页握手和进程退出后提交。失败保留原件，未知退出不能切换、清理或重放。兼容运行时回退用当前启动器重新试运行，不回退用户对话快照；详见[安装与维护](install-and-maintenance.md)。

覆盖安装后补丁级更新是否进维护页、自动开始被旧 Web 会话拦住时如何保留用户入口、以及发布前必跑清单与真机覆盖验收步骤，见[覆盖升级、运行时维护与插件依赖契约](overwrite-upgrade-and-maintenance.md)。

## 修改与验证入口

选择/来源/签名规则的回归入口为 [UpdatePolicyTest](../app/src/test/java/com/deepseekharness/app/util/UpdatePolicyTest.java) 与 [UpdateRedirectsTest](../app/src/test/java/com/deepseekharness/app/util/UpdateRedirectsTest.java)，停止和事务边界见 [MaintenanceGateTest](../app/src/test/java/com/deepseekharness/app/util/MaintenanceGateTest.java)。A5 S2 的结构提交、行为候选、验证范围与未跑项分别见 [候选证据回执](design/kotlin-rust-rearchitecture-20261112/a5-s2-apk-kotlin-evidence-20261115.md)；结构验证不能移记为后续行为候选通过。本文不预报候选验证结果，也不宣称完整应用内安装确认/取消、远端清单、外部覆盖或各 Android 版本已经复验。
