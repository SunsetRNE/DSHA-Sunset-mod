# DSH 0.2.2-rc.5 的 LAN 设置 Host mirror 契约

本批基于 `2cc4e646f8ab232eef0070c9d844b806241caf6e`，只适配 LAN 设置契约选择与对应回归。DSH 0.2.2-rc.5 继续通过 Host 的 `settings.describe` / `settings.mutate` 保存浏览器设置；前端改为紧凑 bundle 后，原证明的字节摘要和四个文本锚点均不再适用。解决方式是增加独立的 rc.5 proof，不重新安装旧的 loopback persistence 补丁，也不放宽源码摘要。

## 精确版本与证明族

[RuntimeTools.java](../app/src/main/java/com/deepseekharness/app/runtime/RuntimeTools.java) 的版本表只允许以下映射：

| 运行时版本 | proof 的 `dshVersion` | APK 资产 |
|---|---|---|
| `0.2.1-rc.5` | `0.2.1-rc.5` | [旧 Host mirror 契约](../app/src/main/assets/lan-settings-upstream-contract.json) |
| `0.2.2-rc.1` | `0.2.1-rc.5` | [旧 Host mirror 契约](../app/src/main/assets/lan-settings-upstream-contract.json) |
| `0.2.2-rc.5` | `0.2.2-rc.5` | [rc.5 Host mirror 契约](../app/src/main/assets/lan-settings-upstream-contract-rc5.json) |

旧 proof 文件保持原字节，文件 SHA-256 为 `f59d612fa3724a99c9574e4b5485e0837162a0a4cd380fa15862a373ecdba62d`。其绑定的旧客户端 SHA-256 仍为 `0555eec2df1ca392a1167e637f287ac9963dbd2b0a230089c2a41be079c490e3`。

rc.5 proof 绑定 `@deepseek-ai/dsh-client-ui-settings/lib/client.js` 的精确 SHA-256：

```text
ebf76f56c79225a3bf484345305ee042f89ed13edd73cc76f8b16766628a17e6
```

选择 proof 后继续检查 schema、版本族、模块路径、整文件摘要、四个 required anchors 的唯一性及两个 forbidden anchors。未登记的版本，包括 `0.2.2-rc.4`、`0.2.2-rc.6`、`0.2.2-rc.5+local`，仍返回 `LAN_SETTINGS_VERSION`；错族、错模块和损坏 proof 仍拒绝，不写回客户端字节。`0.2.0-rc.2` 原有的精确替换与幂等路径保持不变。

## 核对的 rc.5 行为

冻结的 rc.5 raw 树来自 `npm ci --ignore-scripts`，依赖锁 SHA-256 为 `846261e1dc2248197d6b442218539c00629e66b5c012f2eb7a4a62355829df84`。客户端 source map SHA-256 为 `de518545902de48e20ae510889f98eefb3ab5f8923ac6a9fd8dd07f934b4eb1e`。通过其中原始源内容核对：

- `config-form.ts:129–147`：`ConfigFormController.mutate` 复制操作参数、串行排队，取 `expectedRevision ?? pendingRevision ?? snapshot.revision` 后调用 Host `mutate`。被新请求替代的成功写入仍保存其 revision，后继写入不会重用过期 revision。
- `settings-mirror.ts:110–147,158–199`：`SettingsDescribeMirror` 串行执行 `describe`，将成功写入的 namespace view 折回镜像，并以 generation 拒绝过期读结果。
- `index.ts:31–53`：注入 `remote` / `remote.settings`，订阅设置文档变化和连接重置；所有已鉴权浏览器共用 Host document，没有 loopback-only memory store。
- rc.5 `SettingsForms.describe` 从 `fiber.effectiveConfig` 取得有效配置；这是测试夹具必须同步的实际 API 差异。Host 仍在 `configEditor.edit` 的事务回调内核对 `expectedRevision`，在磁盘发布成功后更新 namespace 描述。

新 proof 的 required anchors 对应 revision 选择、Host mutate、Host describe 和注入声明。禁止锚点直接拒绝 `ctx.remote.$host.isLoopback` 选择器以及旧 DSHA 强制 persistence 标记。source map 用于核对原始语义；生产 admission 只绑定实际执行的客户端 bundle 字节。

## 回归与隔离

[Node 回归](../tools/test-lan-settings-host.mjs) 继续通过标准 `testRuntime('raw')` 入口核对当前完整 fixture。测试增加导出的注册函数，供精确锁定的跨版本隔离 runner 调用相同测试；没有放宽共享 fixture 的版本、摘要或内容检查。

测试执行真实 `ConfigFormController`、`SettingsDescribeMirror`、Store 和 Host `SettingsForms` 方法。仅对 bundle 的唯一 factory return 注入测试导出，不改写 controller/mirror 实现；rc.5 的 minified return 与旧 bundle 均可精确定位。Store 的四个依赖 import 绑定到[独立锁定的依赖夹具](../tools/fixtures/lan-store-deps/package-lock.json)。

Host 通过 `Object.create(SettingsForms.prototype)` 和受控文件 publication 边界构造；不调用会启动 Loader / legacy bootstrap 的构造器。每个 case 使用自己创建的临时目录和合成设置，`profileContext.home` 只指向该目录。旧族的 `fiber.config` 与 rc.5 的 `fiber.effectiveConfig` 在夹具中指向同一受控配置。没有读取、写入或恢复真实用户 profile，没有启动当前 Harness 或监听端口。

2026-11-18 本子线实际执行结果：

| 版本输入 | tests | pass | fail | skipped | cancelled |
|---|---:|---:|---:|---:|---:|
| `0.2.2-rc.5` 精确 raw 安装树 | 5 | 5 | 0 | 0 | 0 |
| `0.2.2-rc.1` 已核验发布归档的只读视图 | 5 | 5 | 0 | 0 | 0 |

每组包含：loopback true/false 下 Host 与客户端重建后的持久化；连续排队写入的 revision `[0,1]`；两个客户端间过期 revision 写入被拒绝、磁盘原值保留及客户端重新 describe；schema-invalid 值拒绝。所有测试的 loopback getter 读取次数为零。测试前后实际参与的客户端、Host 与 Store 文件摘要一致。旧归档不含 source map，旧版回执按其真实发布内容登记，没有补造 map。

本轮本机回执目录：`/opt/dsha-toolchain/reports/rc5-kotlin/lan-contract/`。其中 `rc5-host-tests.log`、`rc1-host-tests-final.log` 与两份版本 receipt 分别记录命令结果、依赖锁/归档摘要、模块摘要、测试文件摘要和隔离范围。初次旧版 runner 因要求已在发布中移除的 map 失败；调整为只核验旧归档实际存在的发布文件后，五项真实行为测试全部执行通过，不计入跳过。

[Android admission 测试](../app/src/test/java/com/deepseekharness/app/runtime/RuntimeToolsRegistryAndroidTest.java) 增补三版本 proof 族选择/幂等、错族、rc.5 拒绝旧字节、漂移摘要、错误 schema/module、缺失 revision 锚点和禁止 loopback 锚点负控；原当前受管 fixture 的真实链路测试保留。新 admission 样本明确是合成源码，仅测试 proof admission 和失败时不写原件，不冒充真实客户端行为证据。

## 集成步骤与未验范围

本子线未修改 DSH 依赖锁、APK 版本/身份、共享资产清单或 runtime descriptor。主集成会话需将新 proof 加入[受管输入清单](../app/src/main/assets/managed-runtime-inputs.json)的 `assetFiles`，再用最终受管输入重生成证明和 descriptor；旧 proof 继续随包，以保留旧族的精确门禁。

完成 rc.5 主线 fixture 准备后，标准回归命令为：

```bash
node tools/test-lan-settings-host.mjs
```

两份 Java 文件仅用仓库锁定的 google-java-format 1.22.0、128 MiB JVM 整理改动行段。本子线没有运行 Gradle、Android 单测或设备/LAN 网络验收；最终 Java 编译与 `RuntimeToolsRegistryAndroidTest` 由主集成会话串行执行。本轮 Node 通过不表示 Android AssetManager admission 已执行，也不替代最终 managed overlay 与真机远程设置保存验收。
