# runtime 命令与挂载组装层 Kotlin 迁移回执（2026-11-16）

任务 T5（session-90dbb857）。基线 `bf491c77`，分支 `migrate/runtime-assembly-kotlin`。
先把 Java 实现的 argv/env/BINDS 结果冻结为测试数据（`af92d761`），再整文件迁移 8 类（`c0bb1491`），
迁移后同一份冻结数据断言字节一致。本交付**未**重算受管 descriptor；`runtime/` 属 `launcherTrees`，
`.java`→`.kt` 键变化必然改变 runtimeId，按主会话 P3 裁决由集成树重算（本分支相关门禁如实失败）。

## 1. 范围

生产源根仍为 `app/src/main/java`，下列 8 个 Java 删除、同名 Kotlin 整域替换，FQCN 不变：

| 类型 | 角色 |
|---|---|
| [LaunchSpec](../../../app/src/main/java/com/deepseekharness/app/runtime/LaunchSpec.kt) | 启动选择（runtime/hardlinks/isolated/coldTrace/pipedInput/dataDomain/record/coldMode/env/binds）+ 校验 |
| [ContainerRuntime](../../../app/src/main/java/com/deepseekharness/app/runtime/ContainerRuntime.kt) | proot/proroot 的 argv/环境/准备与共用 BINDS 清单 |
| [UserDataBindings](../../../app/src/main/java/com/deepseekharness/app/runtime/UserDataBindings.kt) | 数据域、受管脚本覆盖与 rc1 迁移状态挂载 |
| [RuntimeLauncher](../../../app/src/main/java/com/deepseekharness/app/runtime/RuntimeLauncher.kt) | 单条 argv/env/ProcessBuilder 组装 + start 顺序 |
| [ProotBootstrap](../../../app/src/main/java/com/deepseekharness/app/runtime/ProotBootstrap.kt) | 兼容门面（安装/执行/插件/维护/终端入口） |
| [RuntimeLauncherAssets](../../../app/src/main/java/com/deepseekharness/app/runtime/RuntimeLauncherAssets.kt) | APK preload 覆盖层的原子发布与校验 |
| [RuntimeHardlinks](../../../app/src/main/java/com/deepseekharness/app/runtime/RuntimeHardlinks.kt) | link2symlink 判定用的独占硬链接探针 |
| [RuntimeExecution](../../../app/src/main/java/com/deepseekharness/app/runtime/RuntimeExecution.kt) | 运行方式选择、启动租约、probe、collect/read/checked |

未迁移（按任务边界）：`RuntimeHostPorts`（端口/配置）、`RuntimeTools`、`RuntimeApkAssets`、
`RuntimeNativeFiles`、`TerminalRuntime`、`RecoveryToolchain`、`IsolatedInstallProcess`、
`BoundedGuestSessions`、停止算法、PTY 实现、插件 writer、backup、Rust。`TerminalRuntime`/
`RecoveryToolchain`/`Cold*` 等 Java 消费者保持原源码，仅随生产编译验证。

## 2. 黄金结果（迁移前冻结）

`af92d761` 在未迁移的 Java 实现上生成并提交：

- [runtime-assembly-golden.txt](../../../app/src/test/resources/runtime-assembly-golden.txt)（SHA-256
  `f12296adea8c7c234a6a835f54a7fbbebcfafc876f0a536bec18aee0266850b3`）：BINDS 清单（含公共存储开关）、
  proot argv（hardlinks 开/关、自镜像、`.l2s`、含空格/单双引号路径）、proot 环境（seccomp 开/关、
  loader32 缺失、三项失败分支）、proroot 环境（静态/动态 loader、缺 proxy/entry）、LaunchSpec 状态与
  9 个非法输入/不可变分支、launch builder argv/环境顺序（coldMode、spec 覆盖、APK proof 剔除）、
  start 顺序（builder→beforeStart→进程创建）、runtimeMode 键、UserDataBindings 成功/失败与 stable 绑定。
- [runtime-assembly-android-golden.txt](../../../app/src/test/resources/runtime-assembly-android-golden.txt)
  （SHA-256 `9b91a312e7949b71a6e70521e993fa818ca89b99328392b49bf9db9b6777174f`）：Robolectric SDK34 下的
  proroot argv（静态/动态/含空格引号路径/$CACHE shm）、proot 数据域绑定与 rc1 迁移目录、stdin 重定向
  （常规 file:/dev/null、isolated/piped pipe）、运行方式选择（proroot 可用/不可用回退/forceProot/关闭）、
  硬链接探针、隔离记录约束、execution 数据域。

生成方式：`-Ddsha.runtime.golden.dir=<目录>` 写出；提交后同一测试改为读取资源并逐字节比较。
机器相关临时根归一为 `$ROOT`；进程继承的宿主环境不进入冻结数据，只渲染组装层自己写出的键；
系统 bind 是否存在于宿主由 `binds()` 用例单独钉死，argv 用例只比较运行方式自身追加的参数。
迁移提交只把测试夹具的 record 访问器语法改为 Kotlin 属性、并把假运行时的返回类型改为
`MutableList`（生产 argv 需要可变列表）；**冻结数据文件未改动**。

## 3. 迁移后验证

本机 aarch64，JDK 17，Kotlin 2.2.10，Robolectric 4.15.1 + android-all-instrumented 14。
真实 Gradle 任务在配置期被固定输入阻断（见 §5），按仓库既有做法用直接编译 + JUnitCore 做等价验证，
**不冒充 Gradle Test/CI**。

| 项 | 实际输入/结果 |
|---|---|
| 生产编译 | K2JVMCompiler 2.2.10（jvmTarget 17）+ javac 17，真实 main/standard/debug 源集与 SDK37 android.jar；`1026 Java + 60 Kotlin` 生产源，kotlinc exit0、javac exit0（含全部 Java 调用方与 debug 子类） |
| 定向测试（纯 JVM） | JUnitCore：`RuntimeAssemblyGoldenTest`（冻结数据断言）+ 既有 `RuntimeLauncherTest`、`ContainerRuntimeEnvironmentTest`、`ContainerRuntimeSelfMirrorTest`、`UserDataBindingsTest`、`TerminalRuntimeTest` → **OK (13 tests)** |
| 定向测试（Robolectric SDK34） | JUnitCore：`RuntimeAssemblyAndroidGoldenTest` + 既有 `RuntimeLauncherAssetsAndroidTest` + `WebLifecycleResponsivenessTest`（真实 `extends ProotBootstrap` 覆写 isEnvironmentReady/expectedRuntimeDescriptor/installedRuntimeDescriptor/setPluginEnabled）→ **OK (21 tests)** |
| 迁移前对照 | 同一套测试与冻结数据在 Java 实现上同样 OK（plain 13、android 21） |
| API 对照 | `javap -s -p` 19 个具名边界类型：原成员丢失 0（仅 1 个原 private 5 参 `collect` 帮助重载并入 6 参，无外部调用者）；checked exception 差异 0；新增 33 项 = Companion/INSTANCE 5、`$default`/构造器标记 4、Record 编译器辅助 14、lambda/内部访问器 5、其他编译器基础设施 5；类头可见性/`final` 差异见 §4 |

主路径等价点（逐段对照）：argv 顺序（`--link2symlink`/`-L`/`--kill-on-exit`/`-0`/rootfs/cwd/自镜像/
binds/shm/dataDomain）；BINDS 列表与存在性跳过分支；`PROOT_*`/`PROROOT_*`/`LD_LIBRARY_PATH` 与
`PROOT_L2S_DIR`；HOME/PATH/TMPDIR/DEBIAN_FRONTEND → RuntimeTools 环境 → NODE_OPTIONS bridge →
coldMode → spec 环境 → proof 剔除/重注；`RuntimeHostPorts.open()` 租约与记录出口；
`RuntimeWorkPort`/`BoundedGuestSessions` lease 与 finally 收尾；runtime() 的 proroot 优先/回退记录；
`beforeStart.verify()` 在 builder 之后、进程创建之前；`nativeLibraryDir`（W^X）执行路径。

## 4. 兼容例外（须审查）

1. **包内可见性**：Kotlin 无 package-private。原包内类/成员在 JVM 上为 `public`，名称不变；
   包括 8 个类型中原本包内可见的类（RuntimeLauncher/RuntimeLauncherAssets/RuntimeHardlinks/
   RuntimeExecution/UserDataBindings）、ProotBootstrap 的 `ctx/rootfsDir/forceProot/coldCandidate/
   preparedColdRuntime/findNativeLib/readAssetString/launch/startRootfs/extractionStage/
   requireCold*/extractOfflineBundleInternal/installBundled*`、LaunchSpec.Builder 构造器与方法。
2. **构造器可见性**：`ProotBootstrap.StartupPlugins`/`PtyLaunch`/`SmokeResult` 的私有构造器改为
   `internal`（JVM public）；`LaunchSpec.Builder` 私有构造器改为 `internal`（JVM public）。
   生产不新增调用者。
3. **Record**：`ContainerRuntime.Bind`、`RuntimeExecution.Probe` 用 `@JvmRecord`，保持
   `java.lang.Record`、canonical 构造器与 `host()/guest()/runtimeMode()/runtimeDetail()/command()`
   访问器、equals/hashCode/toString；新增 `componentN/copy/copy$default` 属编译器基础设施。
4. **null 分量**：`Bind.host/guest` 为非空类型；原 Java 允许 `new Bind(null, ...)` 后在 build()
   抛 `LAUNCH_BIND_FORMAT`，现在在构造边界 NPE。生产 `binds()` 与 `Builder.bind` 不产生 null；
   对应校验分支仍保留（对非空输入不可达）。`Proroot(Context?, ...)` 保留 null 容忍以匹配原 Java
   在仅 applyEnv/prepare 场景的行为（2 参构造器仍按原样 NPE）。
5. **类与成员修饰符**：`ProotBootstrap` 保持可继承（`open`），被 debug/androidTest 子类覆盖的
   `getRootfsDir/isEnvironmentReady/expectedRuntimeDescriptor/installedRuntimeDescriptor/
   setPluginEnabled/ensureBundledPython/execAndReadWithProot/registerBuiltinPlugins/ensureRuntimeFiles()/
   execRootfs(String)/execRootfsForInstall/execRootfsForColdInstall/ptyArgv(vararg)` 保持 `open`；
   其余未被覆盖的方法成为 `final`（原类中非 final；仓库内无子类覆盖，全部生产/测试源码编译通过）。
   `ContainerRuntime.Proot/Proroot` 成为 `final`（原非 final，仓库内无子类）。
   `execRootfsForColdInstall` 原为包内、现 JVM public 且 `open`。
6. **必需 Java 适配（1 处，仅可见性）**：debug `RuntimeStartupAudit` 的匿名
   `execRootfsForColdInstall` 覆写由包内改为 `public`，以匹配原包内方法在 JVM 上只能 public 的
   新事实；行为不变，属该 instrumentation 源码的可见性适配。
7. **删除的 private 帮助重载**：`RuntimeExecution.collect(String,long,boolean,ContainerRuntime,
   RuntimeCommandTiming)` 原为 private 二传手，迁移后直接调用 6 参实现；无行为差异与外部调用者。

以上适用 ADR-0004 的"按模块切换、不按文件混写"与差分回归要求；不声明位级完全一致或"无新增公开成员"。

## 5. 门禁与 Gradle 实际状态

| 项 | 命令 | 结果 |
|---|---|---|
| Kotlin 格式 | `JAVA_HOME=/opt/jdk17 python3 tools/format-kotlin-tree.py --check --offline` | **PASS** 69 文件 / 0 need |
| Java 格式 | `JAVA_HOME=/opt/jdk17 python3 tools/format-java-tree.py --check --offline` | **PASS** 1791 文件 / 0 need；清单 8 删、0 增 |
| 边界门禁 | `python3 tools/test-core-boundaries.py` | **PASS** 25 tests |
| 源码卫生 | `python3 tools/check-source-hygiene.py` | **PASS** |
| 源码布局 | `python3 tools/test-source-layout.py` | **PASS** 16 tests（提交后；未提交时按设计报 inventory 差异） |
| 运行时输入契约 | `python3 tools/test-runtime-input-contract.py` | 17 过 / **1 ERROR**：`RUNTIME_INPUT_MISSING_OR_OUTSIDE: app/src/main/assets/dsh-runtime.bin`（干净工作树缺固定输入，非本域源码判定） |
| 受管 descriptor 输入 | `python3 tools/test-runtime-descriptor-inputs.py` | **预期失败** `DESCRIPTOR_LAUNCHER_MEMBER_SET`：descriptor 仍记 `.java` 键，源码树已是 `.kt`；按 P3 由集成树重算 |
| Gradle Kotlin 编译 | `dsha-gradle :app:compileStandardDebugKotlin` | **受限**：配置期 `:app:prepareBackupAssets` 报 `app/src/main/assets/dsh-runtime.bin` 输入不存在，BUILD FAILED 12s；未执行到 Kotlin 编译/测试任务，不能记作通过 |
| Gradle 定向单测 | （未执行到） | 同上阻断；本回执只使用直接编译/JUnitCore 证据，明确不等同 Gradle Test/CI |

本地格式工具缓存（`app/build/kotlin-format`、`app/build/java-format`）从同仓其它工作树复制并离线使用，
不改锁文件；Gradle 仅以 `dsha-gradle` 串行调用一次，未并发构建。

## 6. 受管输入与 descriptor

`app/src/main/assets/managed-runtime-inputs.json` 未改；`runtime/` 仍是 `launcherTrees` 成员，
8 个键由 `runtime/X.java` 变为 `runtime/X.kt`，517 个 `launcherInputs` 中 8 个键改名，
**runtimeId 必变**。本分支未重算 `runtime-descriptor.json`、未改 `baseVersion`/`dataWrite`，
`prepare-runtime-descriptor.py --check`/`verifyRuntimeDescriptorInputs` 预期失败。集成时由 main
在包含其它迁移的集成树上统一重算并接受一次维护页（I6 设计）。

## 7. 未验范围

未运行 Gradle 完整单测/Robolectric 默认配置、Lint、APK/Dex/签名/安装与真机；未验证 Android 真机上
`Os.lstat`/`Os.link`（非 Robolectric）分支、真实 proroot/proot 启动、容器退出与停止路径、`link2symlink`
真实文件系统差异；`RuntimeStartupAudit`/`ProotCommandAuditInstrumentation` 等 instrumentation 只编译
未执行；`PluginRepositoryQueryLifecycleTest` 的 `Shadow.newInstanceOf(ProotBootstrap)` 路径未运行
（该类无无参构造器，本机同条件对 Java 基线同样报 `NoSuchMethodException`，属既有测试环境限制，
未在迁移中改变）。设备与 CI 结论以 main 的集成/验收为准。

## 8. 回退

revert `c0bb1491` 恢复 8 个 Java 并删除同名 Kotlin，再 revert `af92d761`（黄金测试/资源）与
`tools/java-format-files.json` 的 8 删与 debug 可见性适配；不改用户数据/偏好/协议/包名/数据格式。
若 main 已按集成树重算 descriptor，回退时需串行恢复受管输入并重新生成 descriptor。

## 9. 证据

本地（仓库外）`/tmp/runtime-mig-90dbb857/`：`base/abi.json`（迁移前 javap）、`kmig/abi.json`（迁移后）、
`api-diff.txt`、`kmig/{kotlinc-main,javac-main,kotlinc-test,javac-test,junit-plain,junit-android}.log`、
`base/golden-output/`（冻结生成物）、`compile.py`、`api-diff.py`。
源码字节 SHA-256 见 `.SESSION-DONE`。未 push、未合并、未发版、未安装、未操作手机。
