# build368 发布准备回执（2026-11-16）

状态：**本地软件候选已提交 `938b94a2`（分支 `release/build368`，基于 `bf491c77` / origin/main）；适用静态、格式、工作流契约与版本页字面断言门禁已通过；定向 Gradle 结果见下节。未 push、未合并 main、未打 tag/Release、未 dispatch、未安装或操作手机。本文所在收尾提交只改文档与回执，不改变被测软件输入。**

## 身份与范围

- 候选：`0.2.1-rc.5-20261116.0500-dsh0.2.2-rc.1` / versionCode `368` / `io.github.sunsetrne.dsha` / 单一 Standard，Mod 发布证书 SHA-256 `a4b0413c67fa46dd30dc7e2bce3ad56151ed8bfde2a3315fff685baf786b5d5d`；四份身份文件（`release-identity.json` 与 `.mod` / `.rebirth` / `.upstream`）同步为 `368` / 同 versionName。
- 基线：已发布 build367（tag `dsha-a-su-build367-20261115-230955` @ `f3f8fc03`）；本工作树 HEAD 起点 `bf491c77`（origin/main），含 `f3161211`（Rust prebuilt Gradle 修复）、`872d48ac`（androidTest Context 修复）与 `bf491c77`（CI dry-run 单变体证据下载修复）。
- 本版改动：正式发布装配默认 `prebuilt`（同 run arm64 `.so` + 收据入包，`off` 仍可选）；`DshaApp.onCreate()` 默认启用 Rust 运行时后端（进程内字段仍默认 false，`.so` 未随包 / 加载失败按类型化原因留在 JVM，`verify_plan` 仍 fail-closed）；`RustNativeInstrumentation` 适配新的默认值并临时关闭开关跑原有原生差分探针、finally 恢复并断言；版本页与十语说明同步。发布流水线 bind 不再硬拒 prebuilt 非 dry-run，旧 `RUST_PREBUILT_REQUIRES_DRY_RUN_DEBUG_CANDIDATE` 护栏删除。
- 发布链：正式签名仍由 `release-production.yml` 用 Mod 证书执行，目标 tag 建议 `dsha-a-su-build368-20261116-050000`，由用户批准后的工作流创建；设备验收计划在第二台手机用 `.debug` / `.dev` 测试包与 ADB 日志完成，不在本机安装官方包。

## 受管身份与输入

- [runtime descriptor](../../app/src/main/assets/runtime-descriptor.json)：`52624be1ad386017c68c2fc986c0e834eaeec5364e9539bd0f3aa8dc74e1daba`，517 launcher inputs / 205 asset inputs，含 439 Java + 18 Kotlin 启动源码；`baseVersion=10`、`dataWrite=dsh-0.2.2-rc.1`。变化来自 `DshaApp.java` 与 `util/RustBackends.java` / `RustArchiveCodec.java` / `RustGzipInputStream.java` / `RustGzipOutputStream.java` 进入受管启动输入；输入数量与语言分布不变。
- 固定输入沿用 `fixed-inputs-20261113`，归档 `dsha-a-su-fixed-inputs-20261113-build364.zip` / 716,787,167 B / SHA-256 `2c8dc3fa494a5faf6f65cafa966b47d234a3331c30396198005cedfccca82cad`；本轮没有重新上传、替换或删除固定输入。
- 预期 build367→368 覆盖后首次启动一次整屏运行环境维护页；这属于源码推导的升级预期，**不是已完成设备验收**。

## 本地门禁

日志保存在仓库外 `/tmp/build368-gates/`；不提交原始日志、设备资料或大资产。

| 门禁 | 实际结果 |
|---|---|
| `python3 -B tools/release-identity.py --check` | PASS |
| `python3 -B tools/test-release-identity.py` | PASS |
| `python3 -B tools/test-release-layout.py` | **FAIL（既有问题，非本版引入）**：8 项中 1 error，`RELEASE_LAYOUT_FILE_MISSING`（`migrate-release-layout.py:133` → `release_layout.py:39`）。在 `bf491c77` 的干净归档上复跑同样失败，未修改该测试或迁移脚本。 |
| `python3 -B tools/test-release-signing.py` | PASS |
| `python3 -B tools/test-release-evidence.py` | PASS |
| `python3 -B tools/test-release-names.py` | PASS |
| `python3 -B tools/test-ci-workflows.py` | 首轮 145 项中 30 failure / 9 error，全部为 `RUST_INPUT_NOT_COMMITTED: modified .github/workflows/release-production.yml`（工作流改动尚未提交的预期守卫）；软件候选 `938b94a2` 提交后按原命令复跑 **145 项全部通过（69.983s）**。未修改或绕过该守卫。 |
| `python3 -B tools/generate-changelog.py --check` | PASS（`0.2.1-rc.5-20261116.0500-dsh0.2.2-rc.1 / build368`） |
| `python3 -B tools/check-document-links.py` | 首次运行仅缺 `releases/build368-preparation-20261116.md` 链接；本回执提交后复跑 **PASS：56 文档 / 1340 有效链接 / 0 warning / 0 error**。 |
| `python3 -B tools/check-source-hygiene.py` | PASS |
| `python3 -B tools/check-ui-i18n.py` | PASS：`newFindings 0`、`UI_I18N_EXPLICIT_NONFINDINGS 2`、`stale declarations 0`（生产源码词法扫描）。 |
| `python3 -B tools/run-host-tests.py --check-manifest` | PASS：`PASS_MANIFEST`，264 项。 |
| `python3 -B tools/prepare-runtime-descriptor.py --check` | PASS：`52624be1ad386017c68c2fc986c0e834eaeec5364e9539bd0f3aa8dc74e1daba` |
| `python3 -B tools/test-runtime-descriptor-inputs.py` | PASS |
| `JAVA_HOME=/opt/jdk17 python3 tools/format-java.py --check --offline --files <8 个改动 Java 文件>` | PASS：8 files, 0 changed，token 保真。首轮因树内 formatter 缓存缺失 exit2；按 `tools/java-format.lock.json` 核对本机既有 JAR 的 size=3,612,615 与 SHA-256 `16b2a1ee…` 后复制到被忽略的 `app/build/java-format/`，未提交该缓存。 |
| 版本页正文静态字面断言 | PASS：对 `UpdateVersionPageTest` 两个正文方法实际 358（zh）+ 361（en）共 719 条 `assertTrue` / `assertFalse` 字面包含与排除断言，与当前 raw changelog 全部吻合。该对照不替代 Robolectric。 |

## 定向 Gradle

- 命令：`dsha-gradle :app:testStandardDebugUnitTest --tests '*RustBackendsTest' --tests '*RestorePlanProjectionTest'`。开始执行时 `dsha-gradle --status` 为 busy（其他会话持有 Gradle 槽位），按协调器排队，未并行启动第二个构建。
- 实际结果：**exit 1 / BUILD FAILED（1m 50s，5 tasks executed），未到 Test，不计 JUnit 通过**。失败点为 `:app:prepareStandardAssets` 的 `ASSET_DEPLOYMENT_MISSING:python-support.bin`：`prepare-standard-assets.py` → `asset_deployment.selected_assets` 要求生成资产 `python-support.bin`（及同类由 `tools/build-standard-runtime.py` 生成的 support 归档），本工作树只恢复了六份固定输入，未生成该派生资产。按“环境失败如记录、不重试到绿”的要求只尝试一次，未伪造 R 类、未改资产清单、未以静态断言冒充 Gradle Test。

## 收尾复跑

- `python3 -B tools/check-document-links.py`：PASS，56 文档 / 1340 有效链接 / 0 warning / 0 error（本回执写入后复跑）。
- `python3 -B tools/check-source-hygiene.py`：PASS（最终树复跑）。
- `python3 -B tools/generate-changelog.py --check`：PASS（最终树复跑）。
- 未跑项：完整 Gradle 单测、Lint、APK/测试包构建与正式签名、`release-production` 工作流、设备验收。本机不执行正式签名 assemble（无发布 keystore），也不在正式数据上做故障注入。

## 未验范围

- **build368 完整 Gradle 单测、Lint、APK/测试包与正式签名仍待本版 release 工作流实跑；尚未做设备验收。**
- 维护页/READY、Rust `.so` 在真实 Android 的加载与类型化回退、备份/恢复、SAF 传输、双屏敏感页与插件启用待设备核对；不宣称广泛 ROM/管理器兼容。
- build368 之前的 main（`bf491c77`）prebuilt dry-run 43804700097 已全绿（含 debug/instrumentation assemble、测试包签名与 Dry-run release plan）；该历史结果只证明流水线，不证明 build368 提交的完整门禁。
