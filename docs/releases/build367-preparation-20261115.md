# build367 发布准备与主会话接手回执（2026-11-15）

状态：**本地候选 `21a4925a23a97c6ee11c394a825f86dea1a3d4a6` 已提交，适用静态、格式与工作流门禁已通过；版本页 Gradle 在 AAPT2 资源前置失败，未到 Test，不计 JUnit 通过。未推送、未合并 main、未创建 tag/Release、未 dispatch、未安装或操作手机。** 当前会话的远端操作须用户明确授权，前任会话的批准不沿用。本文最终收尾提交只改此回执，不改变被测软件输入。

## 身份与接手事实

- 候选：`0.2.1-rc.5-20261115.1335-dsh0.2.2-rc.1` / versionCode `367` / `io.github.sunsetrne.dsha` / 单一 Standard，Mod 发布证书 SHA-256 `a4b0413c67fa46dd30dc7e2bce3ad56151ed8bfde2a3315fff685baf786b5d5d`。
- 接手会话：`f182f2ce`，用户确认接替 TRANSPORT 中断的 `e6d1166a`，并承接原持有者 `7738cafd` 的 `merge/build367`。规范 `.ACTIVE-SESSION` 已改为当前持有者；误用下划线的旧声明保留为兼容记录，不入提交。
- 实查远端 `main=c41ca738d5887f24ea2be98d1ec5ea2919d3da89`；SAF、transfer、nethealth 均已合入，三份 `.SESSION-DONE` 已有对应合入记录，未重复合并。
- 主检出本地 `main=15fec672`，落后远端 75 个提交，按只读约定保留。检查全部 54 个登记工作树；8 棵无声明树及旧 transfer / build364 残缺现场均未修改、恢复、重置或删除。
- 最新发布仍是 [build366](https://github.com/SunsetRNE/DSHA-Sunset-mod/releases/tag/dsha-a-su-build366-20261114-180900)，tag 指向 `5ccc8aa3db4c961553a348a8eb35cbfa815be11c`；本次核对未发现 build367 tag/Release，未重放历史推送或发布。
- 接手时 GitHub 无运行/排队 workflow，`dsha-gradle --status` 为 idle；继承声明没有存活后台任务。本轮自行启动的本地门禁另列如下。

## 说明修正与变更范围

原草稿只写 A8-P1 和 SAF/transfer/nethealth。按 `5ccc8aa3..c41ca738` 的完整 54 条提交核对，补齐 APK 主动更新检查、内置插件启用修复、S1a 影子观测与 S1b 窄路径一次性交接、A3 envelope 流桥、contract/M17 Kotlin 迁移、显式 Rust APK 候选，以及 T1/特权插件草案的只读或设计边界。contract 的七个原 Java record 不保留 Record 反射元数据；不概括为所有迁移位级 ABI 一致。版本页测试同步当前正文、版本和 runtimeId，保留旧版本内容排除检查。

候选说明与当前身份文档明确区分未发布候选、历史 CI 和设备验收。`439 Java + 18 Kotlin` 只描述受管启动输入中的源码子集，不是整个生产源码总量。固定离线输入与运行时依赖保持；构建期校验元数据增加 Error Prone 2.36.0，不能写成所有依赖锁完全不变。

## 受管身份与输入

- [runtime descriptor](../../app/src/main/assets/runtime-descriptor.json)：`63adce0a3df6f0c46494dc6adf030f07f80724557f139b3598755caa1607e2ad`，517 launcher inputs / 205 asset inputs，含 439 Java + 18 Kotlin 启动源码；`baseVersion=10`、`dataWrite=dsh-0.2.2-rc.1`。
- 本轮 `release-identity.py --check` 与 `prepare-runtime-descriptor.py --check` 实际通过；发布准备不再重写 descriptor，也不把版本提升算成运行时变化。
- 固定输入沿用 `fixed-inputs-20261113`，归档 `dsha-a-su-fixed-inputs-20261113-build364.zip` / 716,787,167 B / SHA-256 `2c8dc3fa494a5faf6f65cafa966b47d234a3331c30396198005cedfccca82cad`。本轮没有重新上传或替换固定输入。
- 预期 build366→367 覆盖后首次启动一次整屏运行环境维护页；这属于源码推导的升级预期，**不是已完成设备验收**。

## 本地门禁

日志保存在仓库外 `/tmp/build367-prep-f182f2ce/`；不提交原始日志或任何设备资料。

- 首轮串行 18 项：15 项通过。发布前置身份、三身份回归、descriptor 源、runtime inputs、runtime tools、第三方声明生成/核验、内置证明、凭据路径、host manifest、源卫生、UI i18n、发布签名脚本、core/architecture 边界均 exit0。
- 首轮 `test-ci-workflows.py` 实际 135 项，30 failure / 9 error，全部指向未提交的 `app/build.gradle` 被 `RUST_INPUT_NOT_COMMITTED` 拒绝；此轮不算通过。形成本地候选 `21a4925a` 后按原命令复跑，**135 项全部通过（28.123s）**，未修改或绕过该守卫；两轮日志分别保留。
- 首轮 Java/Kotlin 格式因 PATH 与 JAVA_HOME 未配置 JDK17，均 exit2；指定已实测的 `/opt/jdk17/bin/java`（Temurin 17.0.21）后，第二轮发现本树缺 formatter 缓存。按锁核对本机既有两份 JAR 的 size/SHA-256 后复制到忽略缓存，最终串行复验 **Java 1798/1798（manifest missing0/extra0、57 批）和 Kotlin 58/58（14 批），0 需格式化**；前两轮失败日志保留，无全局工具安装。
- 版本页正文静态对照首轮发现两个过时中文负断言（默认禁用、网络健康），已修正为本版内容断言，并补上本版英文 network-health 断言。最终中文 355、英文 358，共 **713 条字面包含/排除断言全部匹配**；该对照不替代 Robolectric 或 Gradle Test。
- 在软件候选 `21a4925a` 实际执行 `dsha-gradle :app:testStandardDebugUnitTest --tests '*UpdateVersionPageTest'`，exit1 / 128.969s，39 个任务实际执行。受管证明 655、103/205/517 输入契约、descriptor、独立应急归档、Rust off、十语生成与 Kotlin 生产编译等前置已运行；`:app:processStandardDebugResources` 的固定 x86_64 AAPT2 在 aarch64 报 `bad machine`，未到 Test。完整日志保留编译器关于 SAF `VAL_REASSIGNMENT` 的既有不保证未来行为警告；未为绕过资源处理而伪造 R 类或更换测试图。只尝试一次，不重放该环境失败。
- 十语说明均为正确的 build367/.1335 标题、13 条、末尾本语言自链接，UTF-8 5,144–12,207 B，低于 16,384 B 上限；统一运行 `generate-changelog.py` 后 `--check` 通过，十份 raw 与源说明逐字节一致。独立子代理全文复核十语与中英测试断言，无 P0/P1/P2。
- `check-document-links.py` 实际 56 文档 / 1340 有效链接 / 0 warning / 0 error；48 路径与 build366 发布模板精确对齐（47 项模板 + 本准备回执），按最多 7 路径分片 `git diff --check` 全过，额外 UTF-8/行尾空白检查通过。只提交版本、测试断言、十语资源和文档；固定资产、原始日志、两份本地声明不入库。

## 已核 CI 与未验范围

- 已发布来源后的 `c41ca738` [Source checks](https://github.com/SunsetRNE/DSHA-Sunset-mod/actions/runs/43719571328) 与 [Documentation checks](https://github.com/SunsetRNE/DSHA-Sunset-mod/actions/runs/43719571339) 均 completed/success；不移用为本次版本/说明变更的 full 验证。
- [历史 A8-P1 出口 dry-run](https://github.com/SunsetRNE/DSHA-Sunset-mod/actions/runs/43706531639) 的 API 实查 head 为 `e368e6d8a0b8679faa138feb51dd70f2447860e1`、conclusion=success，full unit、Lint、unsigned build 与正式签名核验节点成功；测试包与 publish 按输入跳过。历史 5229/7 口径不作为 build367 本轮执行数。本轮下载该历史 unit log 分别遇到 gh cache 路径只读和重定向日志 403，未重新下载复核其逐项计数，也未重跑该 CI。
- **本版完整 Gradle 单测、Lint、unsigned/signed APK、测试包、最终签名/证据包和设备验收待执行。** 本机 aarch64 不能执行固定 x86 AAPT2，不以静态文案对照或作者后备 JUnit 运行冒充 Gradle Test。
- Rust 使用 `off`；prebuilt Provider SourceSet 的既有缺陷未修，无可用 Android prebuilt 成功或设备加载结论。`check-ui-i18n` 的 Kotlin 文案扫描仍为后续项。

## 待当前会话授权的具体链路

形成可审阅的本地提交并完成适用门禁后，用户可选择批准将候选快进/推送到远端 main，再触发 Android `release-production.yml`，精确绑定候选 SHA，`test_packages=debug+dev`、`rust_native_mode=off`、`dry_run=true`。试构建全部通过并核对产物后，创建 tag/Release 仍按本会话明确批准的范围执行。`.debug/.dev` 为共存测试包，不在本机覆盖安装正式包。
