# Kotlin formatter

`tools/ktfmt.lock.json` pins **ktfmt 0.64**（Apache-2.0）及其 fat JAR 的精确字节，
style 固定为 `--kotlinlang-style`（Kotlin 官方代码风格，4 空格块缩进），并带上
`--do-not-remove-unused-imports`，避免格式动作顺手删除 import。

选择 0.64 的依据：

- Maven Central `com.facebook:ktfmt:0.64` 是当时最新已发布版本；独立下载的 thin JAR
  与 Maven 公布的 SHA-1 一致，可用于版本身份交叉核对。
- GitHub 官方 Release `Kotlin/ktfmt`（原 `facebook/ktfmt` 重定向）提供
  `ktfmt-0.64-with-dependencies.jar`，CLI 只需 JDK 17，不需要额外解析约 71 MB 的依赖图。
- 更新的 v0.65 只有 GitHub Release、Maven Central 无对应目录；本仓库需要可交叉核对的
  官方字节，故固定 0.64。升级时必须同时更新 version / size / sha256 和锁文件说明。

命令：

```sh
python3 tools/format-kotlin.py --check --files app/src/main/java/com/deepseekharness/app/util/ShellQuote.kt
python3 tools/format-kotlin.py --write --files app/src/main/java/com/deepseekharness/app/util/ShellQuote.kt
python3 tools/format-kotlin-tree.py --check
python3 tools/format-kotlin-tree.py --write          # 只在格式批次评审后使用
python3 tools/format-kotlin-tree.py --plan           # 只列清单，不下载/格式化
```

- `--files changed` 只选 Git 中已暂存、未暂存与新增未忽略的 `.kt` / `.kts`；不做全树隐式写入。
- 所有选择先过路径、符号链接、所有权、`protectedFiles` / `generatedFiles` 与 UTF-8 校验，
  全部批次通过后才写源码；写入前复核源文件未被并发修改。
- 格式器输出会再跑一遍，要求严格幂等（`FORMAT_NOT_IDEMPOTENT`），避免固定版本工具
  对同一文件产生不稳定结果。
- JAR 只缓存在被忽略的 `app/build/kotlin-format`；每次使用都核对 `size` / `sha256`。
  `--offline` 在缓存缺失时直接失败，不联网。
- Java 门禁的 token 指纹与 adoption manifest 不适用于 Kotlin 首版：Kotlin 是 greenfield，
  `format-kotlin-tree.py` 扫描五个受管源集的全部 `.kt` / `.kts`，没有 adoption 列表；
  首版也没有独立 lexer token 指纹，语义门禁由 Kotlin 编译 + 单测承担。ktfmt 会整理
  import 顺序（已禁止删除未使用 import），因此比较按格式化结果而不是 token 序列。
  ktfmt 会重排 KDoc 段落；中文注释请用空行分段，避免它把整段拼成长行。
- 退出码：0 成功；1 需要格式化；2 选择、路径、JDK、校验和、解析或幂等失败。

CI 由 [`.github/workflows/_node-kotlin-format.yml`](../../.github/workflows/_node-kotlin-format.yml)
执行 `python3 tools/format-kotlin-tree.py --check`；失败时上传 `app/build/kotlin-format` 报告。
