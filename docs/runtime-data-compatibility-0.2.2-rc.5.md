# DSH 0.2.2-rc.5 数据读取与配置变化证据

本批验证的直接前向边是 **受管 0.2.2-rc.1 → 受管 0.2.2-rc.5**。测试只使用隔离合成数据，不读取当前 profile，不改用户数据，不构成完整降级或设备验收许可。

## 冻结输入

| 输入 | 大小 | SHA-256 |
|---|---:|---|
| 旧 rc.1 发布运行时归档 | 131,056,363 B | `c1bd68330a10776f304a92a19d8f82c184c1abf523275a9e28dbb2c2e764e4fe` |
| 新 rc.5 受管运行时归档 | 186,783,624 B | `f85d88b86a2bc1698225f4f7f6814e5ab639301950a3f206bba3b48ae03c439c` |
| rc.5 npm lock | — | `846261e1dc2248197d6b442218539c00629e66b5c012f2eb7a4a62355829df84` |

新 DSH npm integrity 为 `sha512-cNbp12tLYLUTXaVli7zwwMjzmmEVCD1PRldg4oKtXM4+lFYckcaliMS2PU/FoThB4iAN/fKOSID0nrtRZU4Gjw==`。两侧运行根都来自归档实际字节，不使用当前已安装运行时。运行根内旧版 16,590 / 新版 49,453 个普通文件及各 20 个内部链接在测试前后与归档逐项核对，没有缺失、额外或改写文件。

## 实际直接边验证

[可移植回归入口](../tools/test-dsh-rc5-data-compat.mjs)执行 **23 个检查项**：归档和运行树前后核验 4 项，三组各五阶段读写共 15 项，以及旧/新 profile 矩阵和退休包反例 4 项。最终归档执行结果为 `PASS_FINITE_MANAGED_DATA_COMPAT`，23/23 PASS；9 个只读阶段的持久树路径、模式、链接及文件摘要均未变化。

每组五阶段为：旧版写入 → 新版只读 → 新版写入 → 新进程重新打开 → 旧版有限只读。覆盖：

- JSON single/per-record 存储、嵌套全局和记录字段。
- V4 none/zstd 会话追加、新会话和真实 V3 codec 迁移，旧原件保留。
- system/user/assistant 消息、reasoning、两次 tool-call、成功/失败 tool-result、错误与 meta、文件/图片引用、未知可忽略插件记录，以及 `Session.fromRestore().deriveMessages()` 语义。
- 文件、流式和真实 Sharp 图片附件，合成凭据引用，profile YAML 读取和组合。
- 发布包真实 `scheduleDomain` 的 v1/v2 存储信封；不是 ScheduleService 投递业务测试。
- profile 自定义配置、disabled/group/isolate、缺失 bundle、精确 headless 旧元组，以及退休项处理。

新增 runner 拒绝当前安装运行时、现用 profile 与它们的路径别名，拒绝把工作目录或报告写入被测运行树。所有业务数据由新 `mkdtemp` 建立；子进程禁网，不执行 SettingsForms 的绝对路径 legacy bootstrap。超时、signal 或收集错误即使子进程退出码为 0 仍按失败记录。[边界回归](../tools/test-dsh-data-compat-guards.mjs)用真实 timeout 与路径别名负控覆盖这些条件。

## 本轮新增的一向配置变化

rc.5 的真实 `loadProfileDirectory()` 会从 `dsh.profile.bundles` 删除已退休的 `@deepseek-ai/dsh-experimental-agent-team-profile` 并重写 profile `package.json`。旧 rc.1 对相同输入保留该项且不写。两侧实测保留依赖声明、未知字段及用户 YAML 原字节。因此不能宣称任意 profile 首次读取都绝不写入，回退也不会自动恢复已删除的 bundle 选择。

schedule v1 首次写变为 v2 的规则在 rc.1 已存在，并非本轮新增。旧 raw-only 历史会话测试中有两个原本预期拒绝的形状被受管版迁移：独立旧 rc.1/new rc.5 managed 对照显示迁移事件和派生消息完全相同，原文件完整保留，且 `dsha-session-compat` 字节相同。这是既有受管兼容行为，不通过修改 raw 断言伪造新版通过。

## 运行时身份与升级边界

[描述符生成器](../tools/prepare-runtime-descriptor.py)只新增 `0.2.2-rc.5` 对 `dsh-0.2.2-rc.1` 的直接读取声明，最终 `dataRead` 为：

```text
dsh-0.2.2-rc.1
dsh-0.2.2-rc.5
```

不把历史 `0.2.1-rc.5 → 0.2.2-rc.1` 证据传递为本轮直接读取所有旧发行标签的许可。更早版本直接升级若被门禁拒绝，应先经过已验证的 rc.1 中转，或另取得原归档直接证据；不得自动补写版本证明。有限 `old-read-new` 成功不授权整体回退。基础 Ubuntu baseVersion 和独立应急 rc.1 锁保持不变。

## 宿主限制与未验范围

- 本机 proroot 在一份 `system.node` 物理路径长度为 256 字符时于原生加载阶段 SIGSEGV；同字节短路径 151 字符及旧/raw 的 249/248 字符对照通过。整个运行树原字节复制到短目录并核对后，真实锁与上述写后重开成功。没有删除锁、替换 native binding 或撤去补丁。
- 完整旧 Settings/ConfigEditor `boot()` 在本机进入准备回调前崩溃，未执行该完整生命周期写入。LAN 专项的真实 SettingsForms 方法/客户端 mirror 加受控磁盘 publication 已另验证，范围见 [LAN 契约](dsh-rc5-lan-settings.md)，不能把它扩写为完整 Loader 启动通过。
- SQLite 派生查询重建、任意用户插件、完整历史数据矩阵、ScheduleService 投递、真实设备和完整降级未验证。
- 首候选到最终归档全文件对比只有 PluginManager 实现改变，其余 37 组 reader/helper 字节保持；最终归档仍实际重跑了上述 23 项。插件安装、并发与生命周期不由数据矩阵证明，按升级专项回归单独记录。

## 复验命令

两份 runtime 必须先安全解出为有自身 `package.json` 和嵌套 `node_modules` 的短根；不能传 raw npm-ci 树。

```bash
node tools/test-dsh-rc5-data-compat.mjs \
  --old /short/rc1 --new /short/rc5 \
  --old-archive /artifacts/rc1.bin \
  --old-sha256 c1bd68330a10776f304a92a19d8f82c184c1abf523275a9e28dbb2c2e764e4fe \
  --new-archive /artifacts/rc5.bin \
  --new-sha256 f85d88b86a2bc1698225f4f7f6814e5ab639301950a3f206bba3b48ae03c439c \
  --work-dir /short/data-fixtures --report /reports/unique-result.json
```

本机独立审计原始回执保存在仓库外 `data-compat-audit` 目录，含 `final-audit-summary.json`、`final-portable-run.json`、`candidate1-to-final-diff.json`、路径对照和 legacy 原始失败记录。它们不是远端 CI 或设备回执，不提交真实日志到源码仓库。
