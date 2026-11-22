# T2：竖屏插件默认禁用与原生启用修复回执

基线：`30c28cd633f1907f225f44dd0201886f30155d0f`。本次是源码候选；没有修改正式 profile、插件标记或安装内容，没有启停 Web、安装、push 或合并。

## 已复现的实际故障

隔离根目录中放置正常的移动插件实体、旧空禁用标记，并移除 profile 加载别名后，真实 Python CLI `plugin-manager.py enable-state dsh-web-mobile` 原来失败，返回“系统插件只随签名 APK 更新，不支持用第三方归档覆盖”。原因是状态切换的兼容性检查把已安装内置组件送进了第三方导入校验。

修复位于[插件管理脚本](../../app/src/main/assets/plugin-manager.py)：共用格式检查与导入策略拆开，普通 `plugin_package` 仍拒绝系统组件导入覆盖；独立 `installed_state_package` 只供当前安装态切换。签名组件必须位于当前声明实体且通过启动器摘要绑定的文件证明，官方 DSH 核心仍使用既有受管包证明。兼容性查询后再次验证，避免沿用查询前的旧内容。

回归执行真实 Python CLI：旧空标记、无别名场景启用成功并返回 `intentVerified:true`，随后重复 ON/OFF、重新启用、跨三次模拟 APK 代次注册均保持用户选择，组件文件原字节不变。这个证据来自临时合成根，不是手机重启或生产插件加载验收。

## 默认策略迁移

[注册器](../../app/src/main/assets/register-builtin-plugins.py)保留旧 `.disabled` / `.enabled` 的名称、格式与现有默认关闭组件的代次规则。新来源使用单独的 `node_modules/<name>.default-disabled.json`，内容为版本、`builtin-default` 来源和原空标记的 dev/inode/mode/size/mtime_ns/ctime_ns。

- 仅在本工具以 O_EXCL 新建自动默认禁用标记后，才创建来源记录；不收养已有空标记。
- 恢复默认启用的扫描在默认关闭列表为空的提前返回之前执行，仅扫描签名注册项。
- 只迁移来源格式、原空标记身份与当前实体均匹配的状态；用户或外部重写、替换、改变模式/时间后均保留禁用。
- 原生显式启用/禁用先失效自动来源；空旧标记、未知内容、Web 关闭和安全模式优先保留。
- 来源断写、JSON 重复字段、超限、链接或身份变化不提供自动恢复依据。删除/收尾失败明确失败，可重入；残余来源不能删除后来新建的用户标记。
- 没有给旧空标记猜测来源。当前这类旧状态通过修好的正常原生启用入口恢复，不能批量清除。

## 签名安装文件证明

[证明生成器](../../tools/prepare-backup-assets.py)在原 `version` / `archiveSha256` / `packages` 之外追加 `signedBuiltinFiles`；每个签名组件记录受管安装行对应的 `path`、SHA-256、mode、size。旧运行时包消费者保持原字段与摘要算法。

[运行时核验](../../app/src/main/assets/register-builtin-plugins.py)要求文件集合、普通文件类型、字节、模式与大小全部一致；缺失证明或旧表明确拒绝，不能降级为名字/路径判断。普通导入、同名第三方包、非当前实体、字节或 mode 改动、缺文件、多文件、文件被空目录替换和兼容查询期间变更都有拒绝负控。

只排除组件根 `node_modules`：它是共享运行时依赖修复的生成子树，另由依赖/加载链核验。不排除任意嵌套目录，不扫描该运行时子树。路径上限 1024 字符/32 段，每组件 2048 文件、64 MiB、8192 非根文件/目录节点；文件哈希每块 64 KiB，支持取消，不缓存跨请求结果。

原生新装非执行文件模式是 `0600`，执行文件是 `0755`，依据[资产写入实现](../../app/src/main/java/com/deepseekharness/app/runtime/RuntimeAssetFiles.java)和[底层文件创建](../../app/src/main/java/com/deepseekharness/app/backup/AndroidBackupFileSystem.java)。不能用源码检出的 `0644` 代替安装模式；已有相同字节的历史文件可能保留不同 mode，证明不符时保守拒绝，修复代码本身不改权限或内容。

## 验证记录

```bash
python3 -B tools/test-builtin-plugin-default-off.py
# 26 tests / 0 failure / 0 error
python3 -B tools/test-plugin-runtime-links.py
# 7 tests / 0 failure / 0 error
python3 -B tools/test-native-auto-review-projection.py
# 11 tests / 0 failure / 0 error
python3 -B tools/test-plugin-discovery.py
# 60 tests / 0 failure / 0 error
python3 -B tools/test-backup-signed-builtin-files.py
# 25 tests / 0 failure / 0 error
python3 -B tools/run-host-tests.py --check-manifest
# PASS_MANIFEST / 260 个已登记入口
```

上述定向回归共 **129 项通过，无跳过**。生成器集成正控使用实际 11 个签名组件的 57 个受管文件（1,217,960 字节）在临时 guest 中按安装模式落盘、认证临时证明表，再由真实核验函数逐个接受；移动插件为其中 11 个文件。没有导入插件代码或启动 Web。

串行完整输出位于本机私有日志，未把日志、真实 profile 或设备数据提交仓库。新增生成器测试已加入[测试清单](../../tools/host-tests.manifest.json)；两篇修复文档的 23 个本地链接零错误。整张证明表还受运行时相同的 2 MiB UTF-8 字节上限约束，超限不写部分 JSON。

[默认策略测试](../../tools/test-builtin-plugin-default-off.py)覆盖干净默认启用、自动默认 off→on、重复幂等、旧空不收养、显式禁用、Web/安全模式/未知标记、同 inode 重写、替换与链接、来源损坏/重复键、失败收尾重入和其他 profile/第三方不变。[原生切换测试](../../tools/test-plugin-discovery.py)覆盖实际 CLI 恢复、证明正负控与内容不变。

## 合入与验收边界

- 本树按协调约定不修改受管证明 JSON、受管输入清单或 runtime descriptor。合入前主会话须在完整合并树运行生成器与 descriptor 重算，再验证产物；新脚本要求 `signedBuiltinFiles`，不能单独把脚本拷到仍使用旧证明表的正式环境。
- 本树 `check-source-hygiene.py` 的 `PINNED_BYTE_DIGEST: plugin-manager.py` 是尚未重算 descriptor 的预期未通过项，不报告为全绿；其余源码卫生项未报错。脚本在基线就是 CRLF 且 `.gitattributes` 标注精确字节保留，使用 `git -c core.whitespace=cr-at-eol diff --check` 核真正的空白问题，不归一化整份受管字节。
- 证明表及随包脚本变化会改变 runtimeId，下一次覆盖安装需要正常的一次运行环境维护；代码候选不等于已发布。
- 未运行完整 APK/Lint/归档重建；未验证真实重启后的移动插件加载、竖屏布局、浏览器长会话翻页或分页/投影交互；这些应单独验收。
- profile writer 锁约束受管原生/Web 写者。来源迁移和文件核验不能宣传为对同 Android UID 恶意外部无锁写者的原子安全隔离。
