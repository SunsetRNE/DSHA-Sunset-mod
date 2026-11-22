# S3a 本地交付回执（2026-11-16）

本回执属于 T11，基线为 `4ce4064b56211dd41600a0f9fa5df6a0a2bc41ff`。范围与 [S3a 契约、写者矩阵及 S3b 入口](startup-s3a-invocation-locks.md) 一致：旁路可运行协议、真实锁竞争夹具和本地验证；生产 guest 事务、已有 11 类、用户数据/权限/偏好键/进程身份边界保持原实现。未 push、合并、发布、安装、操作手机或清理旧树。

## 旧成果与本次交付

| 旧提交/声明 | 当前主线内容核对 | 本次处理 |
|---|---|---|
| 插件 Kotlin 整域迁移 `ec3db280bdc45982f020678d47463655c558a58f` | 旧 SHA 非主线祖先；集成 `5ac64738` 为主线祖先。两提交整个插件域字节差异为零 | 不重复翻译、不修改既有 11 类。旧树干净并保留结束声明/日志 |
| 启动实施方案 `6e2b1362` | 主线祖先；S3a 在原文中仍是待实现切片 | 本次交协议/锁适配与竞争证明，不把设计当旧实现 |
| 本次本地提交 | SHA 由 Git 提交与本会话结束声明记录，父提交为上述基线 | 新增三个 Kotlin 协作者、两个 Kotlin 测试/夹具、四个 tools 入口、两份文档；共享测试清单只新增一条 support |

[InvocationContext](../app/src/main/java/com/deepseekharness/app/plugins/PluginInvocationContext.kt) 冻结宿主 root/data/身份/绑定、guest home/profile、startup/operation/cancel、工具摘要与阶段预算；集合只读且全部值纳入指纹。[单阶段适配](../app/src/main/java/com/deepseekharness/app/plugins/PluginPreparationProtocol.kt) 在派发前记 attempted，阶段间重核，不重放 UNKNOWN/PARTIAL/异常，不从持锁 Node 回调等待停 Web。[锁探针](../app/src/main/java/com/deepseekharness/app/plugins/PluginGuestLockAdapter.kt) 使用短命 Python 的既有后端，不使用 Kotlin Mutex 或 Java FileLock，不提供 profile 写回调。

## 实际验证

全部 JVM、格式与密集跨进程任务经 main 逐批分配槽位，单进程串行；日志和未知现场保留。相同用例重复运行不累加成更多独立测试。

| 验证入口 | 结果 | 证据边界 |
|---|---|---|
| 既有 [启动导入基线](../tools/test-plugin-startup-imports.py) | 4/0 | 改动前运行；仅 guest 启动导入基线 |
| [源码边界](../tools/test-core-boundaries.py) | 25/0 | Java/Kotlin 既有分层检查 |
| [清单覆盖](../tools/run-host-tests.py) `--check-manifest` | PASS，265 条登记 | 是登记完整性，不是 265 个测试已执行；新锁矩阵仅 support |
| [Kotlin 格式](../tools/format-kotlin-tree.py) `--check --jobs 1` | 91 文件，0 需格式化 | 本次 5 个新增 Kotlin 文件已定向格式化并验幂等 |
| [Java 格式](../tools/format-java-tree.py) `--check --jobs 1` | 1775 文件，0 需格式化 | 清单缺失/多余均 0；本批未修改 Java 源 |
| [真实源码 JVM 复现](../tools/run-plugin-invocation-jvm.py) | 编译成功，JUnit **12/0** | Kotlin 2.2.10 + JDK17 + JUnit4.13.2，直接编译三个真实生产源与两个真实测试/夹具；无 Android stub、非 Gradle/Robolectric |
| [锁矩阵](../tools/test-plugin-lock-interop.py) 第一轮完整运行 | **16/0**，10.272 秒 | Kotlin→真实 Python 后端，与真实固定 Node 模块竞争 |
| 同一锁矩阵补 data-only 双向隔离断言后单次复验 | **16/0**，10.702 秒 | 先核已编译 5 份 Kotlin 源摘要未变；不重编译、不修改生产源，不把两次 16/0 合称 32 个不同用例 |
| 两份交付文档链接及差异空白 | 链接55/0 warning/0 error；差异空白检查随最终暂存执行 | 链接/格式检查不代替语义或设备验收 |

最终矩阵覆盖：

- Kotlin broker 持有 data+profile 时，Python 的 **data-only** flock、Python 双锁、Node profile 锁均被实际阻塞。
- Python **data-only** flock 持有时，Kotlin broker 在 profile 取锁之前超时，未产生 profile 锁；Node 仍可取得 profile 锁，实证 Node 未纳入 data flock 范围。
- Python/Node 双向 profile 竞争与释放；进程死亡后的 flock 释放、PID 旧锁接管；Kotlin 父进程退出后 broker 管道 EOF 闭环。
- data/profile 等待中的取消在观察到真实竞争后的再次取锁检查时触发，不靠固定睡眠猜测等待状态。
- 活 PID、半写/未知锁记录、遗留 takeover claim 均保留；Python/Node 同时争抢旧锁后不会删新持有者记录。
- 新 Python 进程按冻结 home 导入，不共享路径单例；工具变化在取锁前拒绝。
- STABLE 的 profile 宿主/guest 别名 stat 为同一 inode；guest data lock 与从 hostData.parent 猜出的锁 inode 不同。路径计划与实际对象/竞争分别核验。

Node 后端为锁定 npm 归档的 `@deepseek-ai/dsh-atomic-write@0.2.2-rc.1`。归档先核 [依赖锁](../tools/dsh-runtime/package-lock.json) 中 SHA512，只选择真实模块与包元数据，不安装依赖/运行生命周期脚本。实际模块 SHA256：

```text
872d7c1e6e9b267775199bdd3632870f82780d0a5ba047febc93fde94fde53f7
```

最终 Python 矩阵 SHA256：

```text
2c467754a913b4522cd63092ddebb49ee6552ccdddcdc117262e4eaa1f3e6975
```

## 失败记录与未验项

最初两次独立编译夹具分别退出 1：先报 `kotlinx.coroutines.CoroutineScope`，后报 `kotlin.jvm.internal.markers.KMappedMarker` 无法加载。已核原缓存 ZIP 中类存在，且 javap 能读取；把相同字节依赖放入短 `/tmp` 路径后第三次编译成功并完成 JUnit/竞争矩阵。没有修改共享 Gradle 缓存、权限、guard 或 descriptor；未把前两次失败计作测试通过，也不据此声称已修复 JDK/proot 根因。没有任何生产插件写事务被重放。

未执行：本批 Gradle 编译/单测、Robolectric、Lint、APK/安装、Android proot/proroot 真实绑定下的跨宿主竞争、真机启动/性能和完整 CI。main 有界槽优先完成定向编译与真实锁矩阵，未另开 Gradle 请求；不能把“未请求”写成“Gradle 配置失败”。生产全部写者尚未统一到一份内核锁协议，故本批不启用 Kotlin writer、生产准备复用或免扫描；设备后端与 writer 接替仍为后续门槛。

受管 descriptor/runtimeId/recipe 未生成或提交；新增三个 `plugins/` 生产 Kotlin 输入进入集成树时，须由 main 统一重算并履行相应维护核验。没有改变旧协议、用户配置/激活数据格式、偏好键或权限开关。

独立审查后端两次启动均 product-error，无有效复核结论；本批代码经本会话自审与上述实际测试，不能冒称独立 APPROVE。合入前由 main 按流程另做完整审查。

## 复现、证据和交付

```sh
# 只在 main 分配的串行 JVM 槽运行；路径由调用环境提供
python3 tools/run-plugin-invocation-jvm.py --jdk "$S3A_JDK" --gradle-cache "$S3A_GRADLE_CACHE"
# 从下面私有 JSON 的 classpath/java 字段取得参数
python3 tools/test-plugin-lock-interop.py --kotlin-classpath "$S3A_CLASSPATH" --java "$S3A_JAVA" -v
```

仓库外/忽略目录的本轮证据（均保留，不提交私有日志）：

```text
/tmp/s3a-cd4a20b4-validation.log                # 首次编译依赖加载失败；含Kt格式91/0
/tmp/s3a-cd4a20b4-validation-r2.log             # 第二次依赖加载失败
/tmp/s3a-cd4a20b4-validation-r3.log             # 编译/JUnit12/0 + 首轮锁16/0
/tmp/s3a-cd4a20b4-java-format.log               # Java1775/0
/tmp/s3a-cd4a20b4-data-only-recheck.log         # data-only双向隔离后的16/0
app/build/plugin-lock-interop/jvm-proof.json   # 五份源码/JAR摘要、实际classpath和JUnit输出
app/build/kotlin-format/tree-summary.json     # Kotlin91/0及工具摘要
```

S3b 精确入口已冻结在 [规则对拍入口](startup-s3a-invocation-locks.md#s3b-只读规则对拍的精确入口)：先 legacy 标记纯计划与内置注册 alias/manifest 规则，在隔离副本上对照旧 Python，保留真实依赖图 oracle、禁用/安全模式/模板/quarantine/PARTIAL。当前16项锁矩阵通过只允许交付该只读入口，不授权 S3c writer 或 S4 免扫描。

回退：对本批本地提交执行 `git revert`，撤回新增协作者、测试/夹具、两文档及单条 support 登记；不清数据、不安装、不改旧树。如果 main 后续已更新受管身份，回退时由 main 同步重算。结束声明记录最终完整 SHA、基线与同一批验证结果；本地提交并不授权 push/合入/发布。
