# build369 发布准备回执（2026-11-16）

状态：用户已在当前主会话批准发布；独立工作树 `release/build369` 基于 `c243b7a07e4a07f8d09e01e941d0bdc72fa398f4`。本地 18 项串行发布门禁全部通过（exit 0）。最终版本提交 `82eb958d` 已由正式 CI 43828110344 核验并发布；发布后七资产/三包独立复核通过，详见 [发布回执](build369-release-20261116.md)。本回执不表示设备验收通过。

## 身份与本版范围

- `0.2.1-rc.5-20261116.0740-dsh0.2.2-rc.1` / versionCode 369 / `io.github.sunsetrne.dsha` / DSH-A-Su / Android 11+（API 30）arm64 / 单一 Standard；四份身份文件同步，Mod 证书 SHA-256 `a4b0413c67fa46dd30dc7e2bce3ad56151ed8bfde2a3315fff685baf786b5d5d`。
- 发布基线 build368（tag `dsha-a-su-build368-20261116-052738` @ `e74f9e78`）。本版包含 T6 capabilities 6 类、T5 runtime 命令与挂载组装 8 类、plugins 管理 11 类，共 25 个生产类的 Kotlin 整域迁移；受管动态读取器、结构测试与发布验收源路径同步。
- 集成提交链：T6 `c5f7052d` / `7b7bb5b7` / `0bf3eec9`，T5 `532d05ed` / `02b68815` / `7da5fbbd` / `dfc4f289`，plugins `5ac64738` / `edd42ee1`，受管源修复 `e2796a3e`，生成器变更后 descriptor 刷新 `c243b7a0`。
- 本次发布提交仅同步版本身份、当前文档、十语更新说明与版本页断言，不修改迁移业务逻辑。A8-P3 多模块首切不在本版；Rust 默认 prebuilt、运行时后端默认启用、插件启用与用户禁用、安全模式、UNKNOWN、授权及数据语义保持。

## 受管身份与构建输入

- runtimeId `1d42f79d5fea6bad5caaa2409822aa5949691c89e6e230b95011ae35db8a5757`；517 launcher inputs / 205 asset inputs，其中 414 Java + 43 Kotlin；`baseVersion=10`、`dshVersion=0.2.2-rc.1`、`dataWrite=dsh-0.2.2-rc.1`。
- 固定输入沿用 `fixed-inputs-20261113` / `dsha-a-su-fixed-inputs-20261113-build364.zip`（716,787,167 B；SHA-256 `2c8dc3fa494a5faf6f65cafa966b47d234a3331c30396198005cedfccca82cad`）。六份固定资产由已验证集成树复制并逐 SHA-256 读回核对，未加入 Git。
- 版本号与更新说明本身不改变 runtimeId；`prepare-runtime-descriptor.py --check` 已通过，未无故重算或修改描述符。
- 368→369 覆盖升级后首次启动预期一次整屏运行环境维护页并保留个人数据；该预期来自受管输入变化，尚非 build369 设备实测。

## 已有集成 CI 证据

[43823137009](https://github.com/SunsetRNE/DSHA-Sunset-mod/actions/runs/43823137009) 绑定 `c243b7a0`，`dry_run=true`、`test_packages=debug+dev`、`rust_native_mode=prebuilt`，整轮 success。格式、契约、主机回归、固定输入、完整单测、Lint、Rust 原生产物/主机差分、正式包与共存测试包构建及签名全部通过；publish 按 dry-run 跳过。该 run 不证明最终升版提交，也不创建 build369 tag 或 Release。

## 本地门禁与发布清单

本地日志位于仓库外 `/tmp/dsha-build369-gates-6313fce2/`，`summary.json` 记录每项命令、退出码与耗时；串行任务 `bash-56c6a460f06f4e99` 已收集，exit 0。实际通过：

- `check-release-prerequisites.py`、`release-identity.py --check` 与身份/命名/签名/发布证据测试。
- `test-generate-changelog.py`、`generate-changelog.py --check`，十语源说明与 APK raw 资源一致。
- `prepare-runtime-descriptor.py --check`、descriptor 输入及源码两项测试；runtimeId 保持 `1d42f79d…`。
- 源码卫生、UI i18n、文档链接、宿主测试清单 `--check-manifest`。
- 全树 Java 与 Kotlin 格式检查，以及 `git diff --check`。Java 格式器仅规范版本页测试中新增断言的缩进，精确 Java token 保持。
- 额外静态预检：版本页现有正文包含/排除字面断言共 725 条（zh 361 / en 364）与实际本版说明一致；没有执行 Robolectric，不将其计为 JUnit。

本轮未在手机宿主启动 Gradle、构建 APK 或读取发布私钥。完整 Gradle 单测、Lint、APK/测试包、正式签名由最终版本的发布 CI 实跑；不以集成基线或本地静态检查代替。

发布流程按 [CI 构建与发布](../ci-release.md)：

1. 提交本版身份和说明；核对远端 `main` 仍为预期基线后，仅 fast-forward 推送版本提交。
2. 查询新 tag/Release 不存在，显式 `workflow_dispatch` 绑定最终 40 位 commit；`test_packages=debug+dev`、`rust_native_mode=prebuilt`、`dry_run=false`。
3. CI 全部门禁与正式签名通过后，由 publish 创建 tag + prerelease；主会话登记 watch，不重复 dispatch 未知结果。
4. 发布后核对 tag 指向、Release 资产、`build-info.json` 的 commit/run/version/证书/固定输入/测试包与 SHA256SUMS；实际下载核验。

## 未验范围

本版未安装或操作手机，尚无 build369 覆盖升级、维护页/READY、Rust Android 装载/回退、备份恢复、SAF、双屏敏感页或 16 KB 页内核验收。build368 的设备回执只属于其原始 APK，不借用为 build369 通过证据。不宣称全 Android/ROM/Root 管理器兼容。
