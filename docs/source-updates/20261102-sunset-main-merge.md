# 2026-11-02 Sunset 主线整合

本次工作基于本地 `490ba973a30486e0a76d599faf4d9657da61a355` 与 Sunset Mod 主线 `b2d253b7852203928cdcaef7f1a5b62b3e7d94ae`。最终采用上游 build278 实现并恢复 DSHA 正式身份。双方历史经过上游重写，不存在共同 Git 祖先；已核验本地 `490ba97` 与上游 `475cb00` 的 app 树完全一致。第一步真实双父合并为 `97f0a146b0871277c71a44a6db94270f90d5750c`，父节点为本地490ba97与上游b2d253b；执行期间主线新增build278，再正常三方合并 `a3309c62ff82d96abdd7d93ebcf49193d83cb475`。最终历史包含双方祖先，不把改写历史的第一步冒称自动三方合并。

范围：源码整合、身份适配、写文件 justification 参数兼容、软件回归。内置 DSH rc5、单 Standard、上岛整合与最新 UI 按上游保留。原有包名 dsh.android.client、namespace、历史 E7E3 与非调试发布配置保持。旧电脑连接、QuickChat 与完整格式化不恢复。

## 身份、历史与运行时

- 正式配置：DSHA / `dsh.android.client` / namespace `com.deepseekharness.app` / 历史 E7E3，默认签名 alias `androiddebugkey`。`versionCode=278`，`versionName=0.2.1-rc.3-20261102.1400-dsh0.2.1-rc.5`。只保留上游 Standard 实现，未回退内置 DSH。
- 测试包仍需显式开关，包名 `dsh.android.client.SunsetRNE`，不覆盖正式包。正式/应急桥均使用实际包名；provider、任务 affinity、持久数据目录和 Keystore alias 沿用现有契约，没有从 Mod 私有数据域继承授权。
- 本地与上游改写历史对应点的应用树均为 `44bb47991ff481266198a6a2610abe5fe31ee8ac`。旧 FIFO、目标读屏围栏、LAN 密码、未知结果保护及恢复事务没有因历史重写而丢失。首次合并保留本地与上游两个父节点，随后合并上游新增的两笔build278提交。
- 固定输入 ZIP 大小 `696361671`，SHA-256 `ef66a2b501558bec7a13521bdd9d27b2bece37077cdb9b6cb8abe517e5149c2e`。七项本地输入先按新 pin 核验；缺失归档以 HTTP Range 读取原 ZIP 的 STORED 成员，校验范围、CRC32 与锁定 SHA，重构原 ZIP 后再次通过整包摘要及 `ci-assets.py` 全部11项恢复验证。未更改下载锁。
- 本次重新生成正式 rc5 归档：`127675196` bytes / SHA-256 `4381dea9c7b3c060cc43ff87086a471398e8ee4fcc5c2a93613b295571ad1a12`。独立应急归档继续固定 `127674498` bytes / `2316d6d9051455a6ed1507b887e5c30fb2ac9fe4b204d97e179ef34c5f692dc0`；两者不再共用归档字节，运行根本来就独立。
- 受管输入清单新增 fs 补丁和生成器来源。Gradle 已通过共享清单声明其输入；`prepareStandardAssets` 验证真实归档的 recipe/摘要，生产注册表安装器应用完整补丁链。没有另加一个要求构建机器必须保有宿主 npm raw 的隐式打包前提。

## 首次写文件 justification 报错

精确 rc5 的 `dsh-tool-fs` 在读取本次会话的实际权限前，无条件调用共享 `validateEscalationArgs`。普通 write/edit 若携带空理由，即使请求模式与当前权限相同，也会在 I/O 前产生 `invalid justification: expected a non-empty sentence`。已使用官方模块真实复现两种操作的这一错误。

补丁明确工具参数说明，并仅在没有请求更宽权限时，将缺省/空白理由作为普通操作处理。真正扩大权限仍要求非空理由并经过原批准服务；类型错误、只读限制、陈旧观察、拒批和取消仍拒绝，没有伪造批准或增加写入重放。该版本的工具 schema DSL 不支持 `minLength/pattern`，因此未添加会使工具注册失败的字段。

62项宿主用例通过：原始 write/edit 负对照、修复后首调用落盘、真实 schema、权限升级前零写入、批准仅一次、动态降权、只读、陈旧版本、错误参数、取消及丢失回执后不重放。行为测试为官方 rc5 加本补丁；完整生产链另核验文本应用、解析、重复应用、漂移拒绝及 destructive guard 保留，没有在宿主伪造原生确认 UI。

未获得用户故障手机的实际工具参数，因此不能将已复现路径视为所有现场报错的唯一原因；真正权限升级且理由为空时仍应拒绝。

## 配置快照保留修复

审查发现当前快照目录索引损坏时，上游回退旧 `catalog.json.previous`，并依据该旧索引整理快照，可能删除它没有记录的新健康快照。现将备用索引限定为只读查看；写入、提交、恢复和整理必须读到当前完整索引，无法确认时保留原件并拒绝写操作。

新增回归分别模拟当前索引损坏和丢失、备用索引遗漏新引用，检查六个写入口拒绝、两份快照保留及整棵夹具树摘要不变。

## build278 增量及当前入口

最后固定主线为 `a3309c62ff82d96abdd7d93ebcf49193d83cb475`，新增后台任务页、环境提示横幅、同进程重建策略。九处身份/版本文档冲突保留本地 DSHA 配置并采用278版本；业务源码按上游合入。进程令牌变更仍走冷启检查，任务详情使用review_only，未发现新增自动执行/重放入口。

Main维护归属目前沿用上游按域busy近似判断；无关同类只读任务可能短暂影响残留横幅，但实际执行入口仍受原维护门禁保护。此次没有将该UI近似判断宣称为精确operation归属，也没有进行手机重建/横幅矩阵验收。

当前日常源码入口仍为 `F:/DSHA_RESTART/source/current` → `F:/DSHA_RESTART/source/build198`，分支 `codex/build198-f-drive`。独立整合/软件验证工作树为 `F:/DSHA_RESTART/source/sunset-main-20261102`，保留其真实运行时与浏览器夹具以支持复核。原AGENTS/README两处未提交路径说明按原字节片段恢复，完整原件与diff另存；C盘UI原型未改。

## 验证与边界

本轮跨2026-11-02至11-03完成。验证为完整套件执行加修复/新增部分定向复验，不声称一次最终冷网络全绿：

- build277整合输入的完整 Standard JUnit：542类 / 3339项，初轮18失败、0错误、15跳过。完整XML和失败原件保留于 `artifacts/merge-sunset-20261102/junit-full-build277/`（F盘工作区根）。
- 最终build278与测试适配定向复验：19类 / 138项，0失败、0错误、12跳过；覆盖新增重建/维护/任务模型、关联入口与全部五个初轮失败测试类。两轮包含重复用例，不将数量相加冒充独立测试总数。
- 最终Standard Release Lint：0 error/fatal、533 warning；已完成实际生产与测试源码编译，没有执行APK组装。
- rc5真实写入/编辑工具62项通过，真实生产补丁注册/安装链另由Android宿主测试验证。实际归档中的filesystem模块SHA为 `f90303604714b46f8dc1009979179e1c3126ddba1b2d2cd62f154197f473091d`，新marker1处、原destructive guard2处。
- 原rc3与本次rc5实际API跨数据5阶段全部通过（旧写、新读、新写、新重开、旧读新），覆盖本次夹具的会话、文件、凭据与配置；只证明有限宿主兼容，不授权任意版本回退，不等同手机现用数据升级。
- LAN宿主90项、四项真实浏览器宿主入口（安全区、网页来源恢复、移动路由、IME）通过。发布身份/CI宿主、输入契约/负对照、运行时打包、资产部署、十语、文档链接及源码字节门禁已核验。
- 当前1399个清单Java文件均逐字节匹配锁定formatter输出；增量格式调整保持精确Java token一致。上游锁定的CRLF语言/历史材料保留，不为清除旧空白警告而重写其摘要。

初轮18项失败的分类和处理：

1. JvmBackupFileSystemTest 的Windows测试writer在原子替换打开文件时AccessDenied：仅测试夹具增加2秒有界重试、起跑同步、至少两次真实替换和全部4096字节一致性断言，不改Android生产读写。原方法Linux ext4连续20次加字节上限1次通过；候选相同Windows JDK17和Linux各21次通过（是两方法重复调用，不是21个独立方法）。
2. PluginRuntimeProjectionTest的1项、GuestRegularFileTest的8项均在进入生产逻辑前创建symlink失败，实测reason为“客户端没有所需的特权”：使用现有窄能力规则，仅该Windows权限原因产生assumption，其它错误继续失败。Plugin原方法在ext4按JUnit rule和原字节码运行通过；该方法级补验未初始化Robolectric。Guest原整类和修改后整类在ext4均11项通过；Windows为2通过/9能力assumption，其中额外1项将原先POSIX权限API不支持时直接return的假通过改为明确skip。宿主补验不冒称Android syscall验收。
3. RuntimeToolsRegistryAndroidTest 旧固定值60：实际生产AssetManager已成功返回45模块组/61recipe，更新数量并明确断言guard-fs后接fs-justification，以及真实生产安装后marker存在；没有放宽生产准入。
4. ExternalToolPolicyTest的7项使用POSIX固定根和Windows文件语义混合，提前在根目录边界失败：仅测试改用当前盘的真实临时rootfs及可证明等价的POSIX argv，保留所有原有允许/拒绝断言并新增相邻目录越界负对照。原整类POSIX 8项通过；最终Windows和POSIX各9项通过、无skip。生产策略继续仅接受Android/POSIX路径。

初次CI宿主GBK读取错误指定PYTHONUTF8后26项通过；LAN宿主缺生成BuildConfig的夹具已修复并90项复验。固定输入下载失败、Gradle参数错误及中间描述符尚未生成等日志均保留。完整套件过程中Robolectric还自主获取了API35 compile-time资源，Gradle --offline本身不约束该组件；最终补验使用已核验离线framework目录。

独立应急DSH仍为原锁定归档，未应用fs-justification；其工具名单严格为五个dsha_*诊断/提案工具，不开放普通write/edit，修复提案仍经原生确认和宿主事务。本次修复覆盖普通DSH会话。

不生成/安装/发布APK，不推送任何仓库，不运行Low，不操作手机；历史交付文件保持，手机验收继续暂停。真实手机首次写入、覆盖升级、后台任务视觉布局、同进程/跨进程重建及完整设备矩阵尚未验，不能从宿主结果推导为手机问题已全部解决。
