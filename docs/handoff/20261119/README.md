# 2026-11-19 本地开发进度交接

本分支 `handoff-rc5-kotlin-20261119` 按用户要求保存本地进度，供其他开发者接手。**这是未完成、未通过完整集成验收的 WIP 快照，不可直接合并发布。** 用户已暂停在原设备继续开发。

快照基于本地集成提交 `12f96168ee3bae48544ced7895966e6345e655b2`，其祖先包含本批所有 Kotlin、DSH rc.5、LAN 和数据兼容开发提交；再按原字节保存三个工作树的 14 个未提交文件。文件来源、原分支/HEAD、Git base blob、大小和 SHA-256 见 [快照清单](snapshot-manifest.json)。原工作树、原分支和未提交文件均保留，本分支不修改主线、发布标签或当前安装运行时。

资料来源为“还原源码工作区与编译容器”“排查 DSHA 网页加载卡顿”两段会话、用户追加的五项待办，以及本机已有报告/日志的只读核对。以下历史验证不是本次交接重新执行的结果；本次没有继续开发或运行 Android 构建。

## 1. 接手先看

- 主线/原开发基线：`2cc4e646f8ab232eef0070c9d844b806241caf6e`；交接前实时查询的远端 `main` 与其一致。
- 上一正式版：build369，`0.2.1-rc.5-20261116.0740-dsh0.2.2-rc.1`，发布提交 `82eb958d`。旧发布 CI、签名与测试不能当作本批结果。
- 当前源码候选：build370，`0.2.1-rc.5-20261118.1400-dsh0.2.2-rc.5`；尚未发布和设备验收。
- 原设备实际安装的 DSH 仍为 `0.2.2-rc.1`；本批正式运行时候选升级至 rc.5，独立应急仍固定 rc.1。
- 目标仍为 Android 11+ / API30+、arm64、单一 Standard、系统 WebView；旧 low/GeckoView 线不恢复。
- **生成证明可能落后于此次保存的未完成补丁。** 描述符、package proofs、语言资产和运行时归档必须在修复完成后统一重新生成、核验，不能靠旧摘要宣布当前快照可发布。

获取分支（远端推送完成后）：

```bash
git fetch origin handoff-rc5-kotlin-20261119
git switch --create continue-rc5-kotlin --track origin/handoff-rc5-kotlin-20261119
```

建议顺序：先关闭第 4 节的当前集成阻塞，再按第 5 节推进整体剩余任务。遵守 [AGENTS.md](../../../AGENTS.md)、[BUILD.md](../../../BUILD.md) 和 [Kotlin 工具链](../../kotlin-toolchain.md)。

## 2. 已保存的开发历史和续修现场

| 原工作线 | 已有 HEAD | 状态 |
|---|---|---|
| toolchain-arm64 | `2cc4e646` | 环境已验证；工具链位于原设备仓库外，无产品源码改动 |
| kotlin-backup-policy | `a242dabc` | 8 类迁移及 JVM flags 续修已进入集成历史 |
| kotlin-rust-bridge | `5fca5696` | 5 类迁移、黄金测试及干净 CI 编译器供给已进入集成历史 |
| rc5-lan-contract | `26fa2998` | LAN Host mirror 精确契约已进入集成历史 |
| dsh-rc5 | `a1dbd574` | 原交付已进入集成历史；本次另保存 5 个未提交文件 |
| rc5-web-languages | `12f96168` | 本次保存 2 个未提交的语言生成器文件 |
| rc5-kotlin-integration | `12f96168` | 本次保存 7 个未提交的文档/生成清单文件 |

14 个文件逐字节复制并复核，没有改写原工作树，也没有把原活跃声明改成已结束。该快照把续修材料集中到同一分支，**不代表三个工作树的合并后行为已经验证**。

```text
rc5-kotlin-integration（7）:
  AGENTS.md
  CONTRIBUTING.md
  app/src/main/assets/managed-package-proofs.json
  app/src/main/assets/managed-runtime-inputs.json
  app/src/main/assets/runtime-descriptor.json
  tools/asset-deployment.json
  tools/host-tests.manifest.json

dsh-rc5（5）:
  app/src/main/assets/plugin-bundle-proof-workers-patch.json
  app/src/main/assets/tool-history-projection-identity-patch.json
  tools/test-dsh-rc5-browser-regressions.mjs
  tools/test-plugin-pnpm-argv.mjs
  tools/test-dsh-rc5-proof-cancellation.mjs（原未跟踪文件）

rc5-web-languages（2）:
  tools/prepare-web-languages.py
  tools/web_language_catalog.py
```

## 3. 已完成和已有证据

### 源码恢复与编译环境

公开 Git 主线已完整恢复。原手机另有声明来源 `ed3bad17` 的独立源码快照，7,624 个源码文件和 305 个静态技能文件，共 7,929 个原文件，历史摘要核对通过。它没有 Git 历史，公开仓库未找到该提交；该归档不是本候选的开发基线，没有用它覆盖主线，也未把静态技能导入 profile。

固定输入包 `fixed-inputs-20261113` 为 716,787,167 B，SHA-256 `2c8dc3fa494a5faf6f65cafa966b47d234a3331c30396198005cedfccca82cad`。包中含 Ubuntu、正式旧 rc.1、工具及独立应急等输入。两份源码的输入恢复、来源检查、描述符检查历史通过；每份测试清单列出 266 项，清单检查不等于执行这些测试。

原设备 ARM64 容器已配置 JDK17、Gradle9.3.1、SDK37.0、build-tools36.0.0、AGP9.1.1/KGP2.2.10、Java SDK Manager22、QEMU8.2.2、私有 x86 sysroot 和五份 Robolectric 离线库。AAPT2/AIDL/zipalign 使用官方 x86 工具，经显式 QEMU 入口处理容器路径与 `aapt2 m` daemon。真实 AIDL、资源链接、完整生产 Java/Kotlin 编译以及 ShellQuote 的 12 项定向测试历史通过。

原设备专用入口为 `dsha-gradle`：全局串行锁、一个 worker、无常驻 daemon、Gradle 堆1536MiB；实际入口不默认加 `--offline`。Conscrypt 本机覆盖位于仓库外。**这些工具/缓存不随 Git 分支复制，新机器按构建文档准备，不能假定有原设备绝对路径。** 本机构建适配经验不等于 Android NDK 全链或正式签名发布已经完成。

### Kotlin 与 Rust 本批

整体 Java→Kotlin／Rust 运行处理内核任务仍未完成；以下只是一个批次：

- Rust 桥 5 类、原 Java 1,312 行：`RustArchiveCodec`、`RustBackends`、`RustDigest`、`RustGzipInputStream`、`RustGzipOutputStream`。
- 备份策略/状态 8 类、原 Java647行：`BackupExportGuidance`、`BackupFileNames`、`BackupHealth`、`BackupScope`、`BackupScopes`、`BackupTaskKinds`、`BackupTaskState`、`ScheduledBackupOwner`。
- 合计13类、1,959行原Java；[RustNative.java](../../../app/src/main/java/com/deepseekharness/app/util/RustNative.java) 11个JNI入口保留原字节，Rust核心和权限职责未扩大。
- Rust桥有Java/Kotlin两侧33项JVM/API回归、6项真实原生黄金、最终24项组合回归和候选66项host探针；集合重叠，不能累计为总数。
- 备份批次最终53项通过；13个JVM类型、123个原成员/private构造器对拍，原44项方法ACC_FINAL差异已消除。少数包级接缝、Snapshot构造器可见性及Kotlin辅助成员差异如实登记，不声称class文件字节相同。
- GNU ARM64主机内核由Rust/Cargo1.100.0构建，48项Cargo测试通过；不代表Android JNI装载或最终发布产物证明通过。
- 已接入干净CI七JAR Kotlin编译闭包、source-only原生黄金真实库供给，缺库硬失败约束保留；本次没有触发远端发布CI。

详见 [Rust 桥批次](../../design/kotlin-rust-rearchitecture-20261112/kotlin-rust-bridge-20261118.md)、[备份策略批次](../../design/kotlin-rust-rearchitecture-20261112/kotlin-backup-policy-20261118.md)、[集成记录](../../design/kotlin-rust-rearchitecture-20261112/rc5-kotlin-integration-20261118.md)。两个子批次历史独立审查 APPROVE 不自动覆盖后续续修快照。

### DSH rc.5 与数据兼容

直接依赖已精确锁到rc.5，移除上游不再发布的四个agent-team实验包；原74项补丁按保留/适配/退休/历史留档逐项处置，另审计builder专项。严格源字节、包版本、integrity与漂移拒绝没有放宽。上传、工具身份只读投影、结构化工具拒绝、插件update.kind、MCP新连接契约、账户/搜索等均有专项适配；旧pnpm参数搬运补丁退休，LAN改为独立rc.5 Host mirror契约。

阶段候选归档为186,783,624B，SHA-256 `f85d88b86a2bc1698225f4f7f6814e5ab639301950a3f206bba3b48ae03c439c`；npm lock SHA-256 `846261e1dc2248197d6b442218539c00629e66b5c012f2eb7a4a62355829df84`。**该归档早于此处未提交续修，不能作为当前快照的最终归档。**

真实受管rc.1→rc.5隔离合成数据矩阵历史23/23通过，含存储、会话/消息、附件、凭据引用、schedule信封、profile和重开；9个只读阶段持久树未变。rc.5会从特定旧profile的bundle选择中删除退休agent-team-profile并重写包描述，回退不会自动恢复选择。有限旧版回读不授权整体降级，也不证明任意用户数据/插件、SQLite重建、调度投递和真机通过。

读取边只登记 `dsh-0.2.2-rc.1` 与 `dsh-0.2.2-rc.5`；独立应急及Ubuntu基础代次不变。长路径原生加载/锁崩溃经同字节短路径对照后完成数据验证，没有删除锁或放宽原生门禁。

详见 [补丁逐项处置](../../dsh-0.2.2-rc.5-upgrade.md)、[本轮数据兼容证据](../../runtime-data-compatibility-0.2.2-rc.5.md)、[LAN契约](../../dsh-rc5-lan-settings.md)。名称没有 `0.2.2` 的旧rc5数据文档属于历史版本，勿混用。

## 4. 当前必须闭环的事项

| 项目 | 当前事实 | 接手要求 |
|---|---|---|
| 网页语言 | 原集成被仍绑定rc.1的语言门禁拒绝；本次保存了两个生成器的未完成修改 | 核对rc.5真实消息提取、四种附加语言和精确补丁，重生成并通过verifyWebLanguages |
| Kotlin测试编译 | 最近保存的Gradle运行在compileStandardDebugUnitTestKotlin失败，自动备份黄金测试11处可空Ticket传给非空参数 | 审查迁移后的可空性与调用闭包，保留原异常/生命周期行为，不删测试或跳过 |
| 中断工具身份 | 独立审查指出中断记录丢失非法identity标记；此快照含projection/browser测试续修 | 对原始、中断、派生卡片路径回归并复审；不能仅因已保存修改就标已修复 |
| proof取消reason | 独立审查指出null/false/0/空串取消原因被吞；此快照含补丁和新测试 | 验证原值传播、停止和worker收尾，并登记新增测试 |
| ConfigEditor/proof-worker | 短路径一次补测：ConfigEditor240秒超时exit124；proof-worker exit1，断言0!==2 | 分开核对测试与产品的rc.5契约；不能继续一概归因于原生SIGSEGV |
| 最终生成证明 | 续修资产与生成清单未经完整统一重建 | 重新生成runtime、来源、descriptor、package proofs、测试/部署清单并核验实际字节 |
| 全项目验收 | 当前没有最终完整集成通过回执 | 格式、编译、定向回归、完整测试/Lint、原生产物与发布门禁分别记录；本批不具备发布结论 |

最近Gradle历史运行：北京时间2026-11-19 01:05:28–01:08:54，exit1。命令跳过了verifyWebLanguages；生产编译任务为UP-TO-DATE，资源处理完成后，测试Kotlin编译失败，因此该轮列出的Backup/Rust/LAN等filters并未进入测试执行。错误见 [AutomaticBackupServiceGoldenTest.kt](../../../app/src/test/java/com/deepseekharness/app/backup/AutomaticBackupServiceGoldenTest.kt)，行218、252、268、284、296、305、332、349、352、365、366。原日志留在原设备，本文仅转述已读取的结果。

短路径pnpm专项比旧说明更新：exit0，四项通过，含真实registry preflight、postinstall、Git prepare和错误参数负控；范围是隔离宿主，不是Android或生产profile验收。对应测试续修已随本快照保存。

## 5. 用户追加的五项未完成开发任务

| 编号 | 任务 | 接手范围与建议验收 |
|---|---|---|
| 1 | 继续Java→Kotlin／Rust运行处理内核 | 13类只是一个批次；盘点剩余职责域、Rust接入覆盖与Android原生验证，形成分批清单，保留薄Java JNI边界和行为/API对拍 |
| 2 | 目标插件内置网页增加返回壳主页入口 | 从启动目标插件的内置网页直接返回DSHA原生Android壳主页；核对入口、导航栈/返回键/重入、横竖屏和大字体，返回不意外停止服务或任务 |
| 3 | 高刷／网页卡顿五项问题 | 按第6节取得同场景基线、单变量对照和回归，区分代码事实与实际卡顿因果 |
| 4 | 完善容器存储管理 | 核对占用统计、可回收缓存、日志/构建残留、备份与运行时副本、空间不足和回收时机；回收前后占用可核对，保护用户数据、外置工作区、运行中事务和健康恢复副本 |
| 5 | 基于ADB框架的内存管理／强化保活 | 研究在内存压力下结束选定其他普通用户APP以降低DSHA被回收概率；评估通道可用性、压力触发、实际释放内存、重启回弹、DSHA存活和使用影响 |

第4项是容器磁盘与数据生命周期，第5项是Android RAM与应用存活。已有清理、备份或页面生命周期机制不因此变成“全部缺失”，需专项审计实际缺口。

ADB方案尚未实现或验证，不能保证“结束其他APP就永不被杀”。设计需要用户可见且可撤销的目标名单、压力阈值/冷却/回弹检测；仅结束允许的普通用户应用，排除系统关键进程、DSHA及依赖通道并保护当前交互应用。动作前刷新应用分类、使用完整包名或明确PID，不模糊匹配/整组结束，不绕过设备策略；UNKNOWN先核对状态，不自动重放。同时测量DSHA/Node/WebView/构建任务自身峰值。

## 6. 高刷与网页加载五项主线

原任务要求只读排查，尚未实施性能修复或真机采样。当前系统WebView已有创建/恢复/焦点高刷请求，Android15+使用帧率分类API，读取不到峰值设置时默认最高请求120Hz；代码请求不等于系统实际采用高帧率。不能认定“没有高刷适配”或“锁死60Hz”。

| 问题 | 已知事实 | 优先场景 |
|---|---|---|
| 主线程同步诊断落盘 | 正常回调也获取全局日志锁、读旧日志、重写及文件/目录fsync | 创建、路由、恢复；测UI线程磁盘等待与锁竞争，120Hz单帧约8.3ms |
| ES兼容包双注入 | 约114KiB/116,791B同时进入HTML与document-start，新内核也有解析/执行成本；原生同步读取拼接 | 首次打开与真正刷新；同页会话切换不等同整页重载 |
| 路由DOM探针 | 约2.2秒、5.7秒读取innerText、querySelectorAll('*')；遍历完成后才截断结果数字 | 加载后顿挫、长对话；测长任务与layout |
| Android14+页面回收 | keeper依赖的RUNNING_LOW/RUNNING_CRITICAL/COMPLETE及onLowMemory自API34起不再派发 | 长时间/后台返回/压力；显式停止和换代清理仍存在 |
| 已安装rc.1静态服务 | 判304前仍读取资源并计算SHA-1；两个入口JS磁盘合计约1.63MiB，还有入口预加载 | 测资源等待和容器I/O；磁盘大小不是传输量，combo仍有immutable缓存与正文复用 |

原生定位：[WebFrameRate](../../../app/src/main/java/com/deepseekharness/app/ui/WebFrameRate.java)、[FrameRatePolicy](../../../app/src/main/java/com/deepseekharness/app/util/FrameRatePolicy.java)、[WebPreviewActivity](../../../app/src/main/java/com/deepseekharness/app/ui/WebPreviewActivity.java)、[DiagnosticHistory](../../../app/src/main/java/com/deepseekharness/app/core/DiagnosticHistory.java)、[AndroidBackupFileSystem](../../../app/src/main/java/com/deepseekharness/app/backup/AndroidBackupFileSystem.java)、[RuntimeTools](../../../app/src/main/java/com/deepseekharness/app/runtime/RuntimeTools.java)、[WebPageScripts](../../../app/src/main/java/com/deepseekharness/app/ui/WebPageScripts.java)、[WebRouteProbe](../../../app/src/main/java/com/deepseekharness/app/util/WebRouteProbe.java)、[BrowserPageKeeper](../../../app/src/main/java/com/deepseekharness/app/ui/BrowserPageKeeper.java)。原生风险最初核对于main/build369；本分支行号可能变化。

静态服务结论来自原设备安装的 `@deepseek-ai/dsh-host-frontend-static@0.2.2-rc.1` 与 `@deepseek-ai/dsh-client-modules@0.2.2-rc.1`，原入口为各包 `lib/index.js`，需在rc.5上重新核对。平台依据：[AOSP内存通知](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/content/ComponentCallbacks2.java)、[AOSP onLowMemory](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/content/ComponentCallbacks.java)。

附加次级风险：保留WebView时消息监听可能暂时持有旧Activity，重新接管/替换监听/销毁后解除；不计入五项主线，不是无限累积泄漏或已确认首次加载主因。性能采样需要相同设备、WebView、数据量、缓存和实际刷新率，并排除并发构建资源竞争。

## 7. 干净环境恢复与复验入口

大归档不放进Git。公开基础输入可从 [fixed-inputs-20261113](https://github.com/SunsetRNE/DSHA-Sunset-mod/releases/tag/fixed-inputs-20261113) 获取；按 [当前固定输入流程](../../fixed-build-inputs.md) 使用 `--base-only`，再按当前npm锁重建正式DSH。不要让旧包内rc.1覆盖rc.5候选。尚未发布包含本次续修的完整输入bundle。

```bash
# 在仓库根目录；先按BUILD.md准备所用机器的工具链。
python3 tools/ci-assets.py --archive /path/to/dsha-a-su-fixed-inputs-20261113-build364.zip \
  --sha256 2c8dc3fa494a5faf6f65cafa966b47d234a3331c30396198005cedfccca82cad --base-only

# 语言/功能修复完成后再统一重建；当前WIP不承诺这些步骤已通过。
python3 tools/prepare-dsh-runtime.py
python3 tools/prepare-backup-assets.py --write
python3 tools/prepare-runtime-descriptor.py --write
python3 tools/verify-runtime-descriptor-source.py
python3 tools/format-kotlin-tree.py --check
python3 tools/format-java-tree.py --check
python3 tools/run-host-tests.py --check-manifest
```

Android编译与定向测试命令按 [BUILD.md](../../../BUILD.md) 和子批次记录执行；原手机统一用 `dsha-gradle`，新机器不要直接复制该机器专用路径。Rust黄金需真实本轮宿主库及 `-Pdsha.rust.required=true`；数据矩阵需实际受管归档解出的隔离短根，禁止指向正在使用的profile或运行时。

## 8. 此次快照验证与未分发材料

已做：14个文件源/目标字节核对，七条本地工作线HEAD的祖先覆盖检查，7个JSON解析、2个Python AST语法检查、3个JavaScript `node --check`。这些不执行产品行为测试。

本机整体 `git diff --check` 触发已知proroot SIGSEGV并退出139，未把它当作通过；独立逐新增行的尾随空白检查通过。此次涉及的4份文档经仓库链接检查器核对97个本地目标，0错误/0警告；提交历史与快照共195个blob经过大文件/私钥及常见凭据模式检查，未发现命中，最大blob为506,790B。这些检查不代替功能审查或正式发布门禁。没有为交接修复产品、跑Gradle、安装APK、替换运行时或修改用户数据。

未推入源码：大固定输入/运行时归档、构建输出与依赖缓存、用户profile与凭据、原设备诊断/完整日志、独立ed3bad17手机归档和原工作树占用声明。原材料在本机保留；公共基础输入可下载，最终候选必须从已修复源码重新生成。文档中的历史结果是范围受限的交接证据，接手者应按最终字节复验。
