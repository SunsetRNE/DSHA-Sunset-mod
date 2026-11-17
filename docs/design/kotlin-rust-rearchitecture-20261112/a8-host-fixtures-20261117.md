# A8 Source CI：多模块夹具收口（2026-11-17）

状态：**本地修复候选；已完成受限 Python/mock 回归，原真实 JVM 用例尚待 main 派槽。未 push、未触发新 CI、未合入 main，P4 未开始。** 当前分支 `fix/a8-host-fixtures-65152e2a`，开工 `git fetch origin main --tags` 实核基线 `127ae7bfc72b0e36ba0b26dd2e8f6a7ebf62b8db`，T3 原 owner `65152e2a`。

## 原失败与修复范围

main 下载的 Source run `43982296590` 原始回归日志显示两项 host-fast 失败：

- `test-format-java-tree.py`：原 4 项中盘点夹具报错。临时根复制真实双模块 layout，却只创建 app 的五个格式源根，生产 helper 正确拒绝缺失的 `core/policy/src/main/java/com/deepseekharness/app`（`FORMAT_SOURCE_SET_MISSING`）。
- `test-unit-test-runner.py`：原 6 项中的两个单模块纯 Java 正例继承真实多模块 layout，生产 helper 正确触发 `JAVA_ONLY_RUNNER_REQUIRES_GRADLE`，未运行其原定 Java 后备路径。

这不是生产 guard 应放宽的场景。修改限定 [formatter 夹具测试](../../../tools/test-format-java-tree.py)、[Java runner 夹具测试](../../../tools/test-unit-test-runner.py) 与本文；没有修改 [formatter 生产盘点](../../../tools/format-java-tree.py)、[Java runner 生产入口](../../../tools/run-unit-tests.py) 或 [布局解析器](../../../tools/source_layout.py)。不删、skip 或改名任何原测试方法。

formatter 夹具继续使用真实产品 layout，创建**每个声明模块的每个 formatSourceSet**；原保护/生成/上游排除与 128 文件批次断言保留。增加全模块 Java 盘点与 Kotlin 不混入 Java 清单、按模块隔离批次的检查；每次故意漏一个声明格式源根，要求明确拒绝该精确路径，不能忽略 policy 或把缺根当空模块。

runner 的两个真实 Java 正例改用显式受控的单 app Java layout，保留原真实 javac/helper 调用、空白/中文/`#` 路径、资源二进制 JAR 与 classifier 排除的断言。真实编译失败清理、锁定依赖字节和 Kotlin 测试拒绝等旧用例保持。新增：

1. mock 正例只验证 Python 的源清单、两阶段编译调度和 launcher argfile，原真实 JVM 正例仍另行执行；占位 class 明确为不可执行 mock 字节。
2. 使用当前真实双模块 layout 的**全 Java**负例，确认不能因没有 Kotlin 就绕过多模块拒绝。
3. 单模块 Kotlin 生产源与 Kotlin 测试源分别拒绝。

后三种拒绝都检查 `_javac` 和 launcher 没有被调用，并要求原生产 guard 文本；没有通过 mock 替代 SourceLayout 或生产门禁。

## 最新主线上的完整重放

旧 ee 源树、main 已推的 `29b838d291b88abe3e21ddff9636efde23907cff` 树均只读保留。新树直接从 127 基线创建，完整 `-x` 重放原 A8 16 个来源（14 链、重放回执、fixture 审计返修），无冲突，重放点为 `2358fca2`。**没有重放旧 29 的生成身份提交**。

所有 127 中已合 T11/T10 的生产和测试字节保留。唯一自动合并为 `tools/host-tests.manifest.json`：保留 main 新增的 plugin-lock support 登记及原序/全部字段，仅接入 A8 对 source-layout 测试的一处 reason 文案。新夹具修复不修改该清单。

本次没有改 native session builder、原生源码/脚本/锁/ELF、资源处理、Rust、插件 writer、T10 缓存；旧 package run `43982490603` 的 `NATIVE_SESSION_REBUILD_MISMATCH` 仍由 main 对应域诊断，Lint/APK 被跳过的问题没有被本次 Python 结果核销。旧 29 的 full success 也不能抵消 Source 两项失败或该 package 失败；本回执不重新宣称已验证旧 full 的 21 个导航 XML。

## 实际本地验证

| 本轮层级 | 实证 |
|---|---|
| 旧夹具失败复现 | 从重放点读取未修改测试代码，真实 formatter inventory 与 runner guard 重新触发相同缺根/多模块拒绝；整个执行阶段 `subprocess.Popen` 被拦截，未启动 JVM |
| formatter Python | 原 4 项保留，加全模块盘点与逐缺根拒绝，共 **6 项通过**；只盘点/分批，不执行格式器 |
| runner Python/mock | **7 项通过**：原 3 个纯 Python 用例、新 mock 正例及 3 个多模块/Kotlin guard 负例；所有 `subprocess.Popen` 被禁止 |
| 合计 | **13 tests / 0 failures / 0 errors / 0 skipped**，属于 Python/mock，不是 Java 编译或 JUnit 执行证据 |
| 原真实 JVM runner 用例 | 仍有 **3 项待执行**：helper/路径真实编译调用、资源二进制依赖、真实编译失败的临时目录清理；没有删去、过滤产品 CI 或改成 mock |
| 生产 helper/身份 | formatter、runner、layout helper 与 127 逐字节相同；descriptor、managed inputs 和 runtime recipe 也保持 127 字节，由 main 后续集成树重算最终身份 |

JVM 仍由 main 排槽。T13 独占期间本树未执行 javac/java、格式器、混编或 Gradle；本地 Python 集合只选择已核不启动 JVM 的用例，并额外阻断 Popen。完整默认测试入口没有 skip：

```sh
# 首条只运行 Python inventory/batching，第二条包含真实 javac/java，须 main 明确 JVM 槽。
python3 tools/test-format-java-tree.py
python3 tools/test-unit-test-runner.py
```

修后默认入口预期分别枚举 6 与 10 项。mock 的 13 项绿不能代替第二条完整 10 项入口；需要取得槽后补真实结果，再由 main 给新精确候选取得独立 push/CI 批准。旧 29 的批准不覆盖本修复。

未跟踪证据保存于本树：

```text
out/a8-host-fixtures/non-jvm-tests-r2.log
out/a8-host-fixtures/original-fixture-reproduction.log
out/a8-host-fixtures/replay-preservation.json
out/a8-host-fixtures/replay-preservation-r2.log
```

源码回退只需撤销本次两个测试及回执提交；既有 A8 来源链、主线 T11/T10 以及旧失败现场全部保留。没有安装、手机操作、清数据或删除旧树；不通过删除 native/身份/资源守卫换取通过。
