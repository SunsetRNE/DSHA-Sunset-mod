# 插件 S3b：只读准备快照与计划（T12）

用户于 2026-11-17（Asia/Shanghai）直接指派，任务面板登记为 T12。分支
`refactor/plugin-s3b-0720168e`，独立工作树基线为
`fb68097f1b7dc5d55daa3935f5b4ddd63d779366`（当时的 `origin/main`）。
T11 当时尚未进入 main；本批只实现规则、快照与隔离夹具，不引用其侧分支 API。

## 范围与状态

新增三类：

- [PluginPreparationSnapshot](../../../app/src/main/java/com/deepseekharness/app/plugins/PluginPreparationSnapshot.kt)：不可变的同次只读事实，保留 marker 原字节、文件种类、六字段 stat、签名目录、实际依赖图与兼容结果、APK/受管代次。JSON 每次返回副本，集合与字节数组作防御复制。
- [PluginLegacyReviewPlan](../../../app/src/main/java/com/deepseekharness/app/plugins/PluginLegacyReviewPlan.kt)：解释已退休 review gate 的迁移条件，生成有序的 `QUEUE_ACTIVATION → ENABLE_PLUGIN` 描述。外部执行回执独立分类；没有回执不代表成功。
- [PluginBuiltinRegistrationPlan](../../../app/src/main/java/com/deepseekharness/app/plugins/PluginBuiltinRegistrationPlan.kt)：normal-web 的默认意图、missing/disabled/present、manifest、alias、quarantine 和注册回执计划。已有 profile 的辅助文件补全仍是显式步骤，每次仍要求运行时依赖扫描。

生产接线、旧 Python 资产、PluginRepository、StartupPipeline 均未改。没有新增 writer、锁 owner、CAS、脚本合批、免扫描或生产双跑，没有启用任何 Kotlin 计划的执行器。
计划不是提交许可；未来接线仍须复用 T11/既有唯一 owner，在执行前重新核验原件、代次与内容。
本批添加的三类位于 `launcherTrees` 的 plugins 域，最终集成必须由 main 重算 descriptor/runtimeId；本分支不生成描述符，不复用侧分支身份。

## 保留的规则

| 场景 | 规则 |
|---|---|
| legacy marker | 只认普通文件的完整 `DSHA_REVIEW_REQUIRED\n` 二进制字节，长度也必须完全一致；空、长短、同长不同字节、安全模式与符号链接都不获得激活计划 |
| 原件来源 | 精确 `link:<plugin-src>/<name>`，实际目录不能为符号链接，包名、metadata、真实依赖图均须有事实；不把 manifest 声明当作已解析依赖 |
| 已知缺依赖 | 原件保持停用，归 skipped；实际图因为已知 missing 而 complete=false 不冒充观察未知 |
| 兼容阻断 | 保留 PolicyError 的 name/code/message，旧 partial/1、application=failed、stage=enable；普通损坏或 enable 返回非零仍是 ok+skipped |
| 激活顺序 | 先持久化 queue_activation，再 enable；enable 失败时旧账本可仍为 queued。计划自身不写账本 |
| 未知事实 | 不可读、读期间变化、未采样来源/代次或缺回执保持 unavailable/unknown，不能由 unknown 推断 absent/ok |
| 系统来源 | 只使用本次签名注册表给出的 APK 目录；同名 profile 实体、plugin-src 和全局包不能顶替 |
| 禁用与 Web OFF | 用户空标记、安全模式、未知原字节保持；只有旧文本 reader 接受的精确 Web OFF 标记保留 dormant alias。Web 文本 universal-newline 与 legacy 二进制精确规则分开 |
| 默认禁用与代次 | 当前 APK 代次的精确 `.enabled` 才保持默认关闭项启用；已知无效/缺失 APK marker 按旧 runtime/unmanaged fallback，观察未知不提供代次授权 |
| 自动恢复默认 | 普通空 marker 与独立来源记录的六字段 stat 完全相同、且当前签名实体存在才清除；保留已有 `.enabled`。来源损坏、重复字段、非整数、软链或身份改变不恢复 |
| 已存在非文件 marker | `O_EXCL` 遇目录或悬空链接沿用旧 no-op，不投影成新空文件，也不凭空制造默认禁用来源 |
| alias 与 quarantine | 遮挡当前 APK 的非链接旧实体进入 quarantine，原字节保留；不能删除旧实体冒充缓存清理 |
| FAIL 与 PARTIAL | runtime/default/disabled 校准失败为 FAIL；missing 或 present 链接失败为 PARTIAL。present 的失败累计后仍继续健康项，缺健康项回执保持 unknown，不写成功注册身份 |
| manifest | 保留用户字段与官方 bundle 的既有选择；auto-review 双别名按旧规则去重；changed=false 未落盘时不声称 patchReload 已改 |

## 隔离 oracle

[测试驱动](../../../tools/test-plugin-s3b-oracle.py)只把七个旧资产复制进 tempfile，设置独立 `DSHA_TEST_ROOT` 与 DSH_HOME 后导入真实旧代码。
候选入口包含会立即抛错的源码，不加载插件、不启动 Web、不运行 Node 或包管理器。
真实 `Dependencies.current` 扫描隔离目录；兼容报告为明确注入的外部事实，metadata 和 `require_compatible` 仍调用旧实现采样，实际迁移时再调用旧判定。

[冻结夹具](../../../app/src/test/resources/plugin-s3b-oracle.json)共 59 例：23 migrate、36 register。
每例保存输入 manifest/registry、原始 marker、metadata、兼容结果、真实依赖图、APK marker、alias、profile 辅助文件，以及旧执行后的 manifest/账本/有序事件/quarantine 原字节与分类。
registry 子集、兼容报告、固定 activation 时间，以及特定 enable/alias/source-unlink 故障均逐例写在 `injections`，不把故障注入当正常生产结果。

随机 tempfile 路径在对拍时重定位到冻结根；先断言真实 current() 摘要与真实图相符，再对已扫描图重定位编码。
六字段 stat 与来源文件原字节保留冻结证据；check 仅映射本轮实际身份到对应冻结身份槽，保持相等/不等关系，不 patch 旧 stat 判定。
这些归一化只服务于测试，不能作为生产证明或缓存命中依据。

```sh
python3 -B tools/test-plugin-s3b-oracle.py --check
# 有意更新旧行为基准时才使用：
python3 -B tools/test-plugin-s3b-oracle.py --write
```

[参数化 Kotlin 对拍](../../../app/src/test/java/com/deepseekharness/app/plugins/PluginPreparationOracleTest.kt)读取输入事实生成计划，逐例比较旧分类、activated/skipped/blocked、queue 顺序与 fingerprint、manifest 成功前缀、alias canonical/package/absolute、marker、profile 文件及 quarantine 字节。
四个 Kotlin 测试类包含不可变快照、未知事实、默认来源、代次、source 与 missing 依赖、healthy sibling 回执等边界；
[真实 Java 调用夹具](../../../app/src/test/resources/plugin-s3b/PluginPreparationJavaCaller.java)由 javac 编译，核验静态工厂、事实与 unknown 结果的跨语言调用。

## 验证回执

验证在 main 明确分配的独占 JVM 槽内串行运行，首个 JVM 为
`2026-11-16T17:00:30.942793Z`；预算截至 `17:20:30Z`。未运行 Gradle、Android、APK 或设备验证。

- 旧 Python review 基线：26 tests，0 failures；默认禁用基线：26 tests，0 failures。
- 隔离 Python oracle：59/59，七资产 SHA-256 与冻结结果匹配。
- 核心边界门禁：25 tests，0 failures。
- 输入契约：实际运行 18 tests，其中 1 error：本工作树缺未提交离线输入 `dsh-runtime.bin`，未到该项完整解析；不记录为通过，不借此修改固定输入或描述符。
- 首批 JVM：Kotlin 全树格式 95 files / 0 need；Java 夹具格式 1 / 0，token 保持；三生产类与四测试 Kotlin 编译、javac、真实 Java 调用通过。JUnit 82 项有 1 失败：已知 missing 的真实依赖图 complete=false 被过早视作 unknown；修正为先保留已知 missing 的 skipped 分类，并增加来源/APK 未知与 O_EXCL 冲突回归。
- 修正后最终 JVM：`2026-11-16T17:06:21.361512Z` 至 `17:07:30.463098Z`，全部阶段 exit 0。JUnit **85/0/0/0**（执行/失败/忽略/假设跳过，含 59 个 oracle 用例）；三生产/四测试 Kotlin、真实 javac 与 Java 调用通过；四个核对 class 的 major 均为 61。Kotlin 全树格式 95 / 0，Java 夹具 1 / 0 且 token 保持。
- 最终 10 个输入的 SHA-256 与当前字节逐项一致；首批失败日志与最终全新 class/proof 输出分开保留。实际 `/proc` argv 核验 JVM 已全部退出，已交还 main 槽。
- Source hygiene 通过；文档链接门禁 56 文档 / 1340 链接 / 0 warning / 0 error。
- 非实现者独立只读复审最终 **APPROVE**；其读取最终代码、补充测试/oracle 和实际日志，没有自行运行测试。完整机器可核对记录见[验证回执](plugin-s3b-verification-20261117.json)。

本机窄验证使用 JDK17、Kotlin2.2.10、Gson2.13.1、JUnit4.13.2；依赖从既有缓存复制至短临时路径并逐 SHA 回读，未改共享缓存。
本树 ignored 驱动与日志位于 `app/build/`，最终 proof 绑定格式后实际三生产/四测试/Java caller/oracle 字节与所有依赖摘要，输出目录全新；不使用旧 class 作为通过证据。

完整 Gradle 单测、Lint、完整 CI、APK、安装、真机行为、生产执行器和 writer 接线均未验证或未在本批实施。
集成后 main 应以最终主线重算受管身份并安排相应完整验证；本批只读规则通过不等于生产启动接线已经迁移。
