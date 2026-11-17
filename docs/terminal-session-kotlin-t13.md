# T13 终端会话 Kotlin 等价迁移冻结与回执

基线：开工时 `origin/main` = `127ae7bfc72b0e36ba0b26dd2e8f6a7ebf62b8db`。本工作包先在原 Java 实现上冻结 API 与黄金行为，再整文件迁移；最终运行时身份由 main 串行重算。本文件不代表 CI 或设备验收通过。

## 冻结范围

仅迁移同 FQCN 的 7 类：`core.TerminalSessionOwner`、`core.TerminalRuntimeProtection`、`core.SimpleTerminalBackend`、`runtime.TerminalProcessCloser`、`util.TerminalTabs`、`util.TerminalSession`、`util.TerminalExitPolicy`。既有 Java 调用方与测试 FQCN 不改；新增 Java 契约测试同时用于迁移前后。

不改 PTY 原生握手、信号与出生身份判据、Web 生命周期、UI、构建模块、依赖、版本及描述符。原 Java 源与对应测试由基线提交永久保留。配套格式清单只删除这 7 个原 Java 路径并新增黄金 Java 测试路径，main 在并行包集成时合并清单。

Kotlin 编译器会新增 `Companion`、枚举 `getEntries()` 和 synthetic 访问器；这些不是旧 Java 成员的替换。既有 package-private 入口按仓库惯例映射为 `internal + @JvmName`（JVM public），保留 Java 同包测试的原调用身份，源码分层扫描继续约束引用。原 private 的 `Ticket` / `SimpleTab` 构造签名仍为 private；inner 类的额外构造桥须在原构造器初始化之前核验外层私有唯一 key 的对象身份，错误 key 不能创建会话或消费票代次。静态嵌套的 `Tab` / `Reservation` / `CloseSnapshot` / `Failure` 继续用 private 构造器和 synthetic 工厂。


## Java API 冻结

- 所有公开/同包构造器、方法名、重载、参数/返回类型、泛型和声明的 checked exceptions 保持 Java 可调用形态；静态入口保留 static，字段保留真实字段。
- `TerminalSession.Backend` 的 `beginLifetime`、`onReady`、`write` 必须仍是真正 JVM default 方法；`open`、`terminate` 仍抽象。Java 的 `Backend.super.write` 无需改动。
- `TerminalTabs` 的 `Tab.id/number/value`、`CloseSnapshot.state/generation/error`、`TerminalRuntimeProtection.Snapshot` 的五个字段、`SimpleTab.session/buffer/draft/cursorStart/cursorEnd` 维持字段名、类型与 final/可变性。
- `Ticket`、`SimpleTab` 维持非静态 inner 所有权。枚举顺序不变。策略类不改成 data class，不引入值相等替代对象身份。
- 保留原 `synchronized` 方法与显式 `synchronized(this/owner/closing/protection/tab)` 监视器；锁内/锁外观察者回调位置不变。保留 `volatile` 发布点与 closeSnapshot 原子投影。
- 测试反射使用的 `TerminalSessionOwner.pty/simple`、`TerminalTabs.next/identityExhausted` 保持名称与类型；Kotlin internal 测试入口须用 JVM 名保持同包 Java 编译，不以改调用方掩盖 mangling。
- Java `String.trim()` 使用 <= U+0020 的原判据；不扩大为空白字符集合。UTF-16 字符截断与 code point 截断分别保留原位置。

## 黄金行为冻结

1. 精确 owner/epoch/kind/票对象与当前服务同锁校验；创建票仅消费一次，取消/超时/迟到提升不复活；fork 前 retain，create 的 finally 结束前仍有准备保护。
2. 永久会话 ID 只增不退，取消预留也消耗身份；显示编号在发布时复用最小空缺。关闭中/未知退出继续占编号；耗尽在 fork 前拒绝。
3. 关闭失败/中断/UNKNOWN 保留标签、同一 ID、原 hold 和环境工作锁；只有确切退出后才 remove/release。重复关闭与旧回调不能删除后来复用显示编号的会话。
4. 简易终端单 control 线程串行状态；输出背压、跨包 UTF-8/协议解析不变；旧 Run 的读取/EOF/DONE 不能影响新 Run。未发送命令保留；可能部分发送的命令不重放。
5. cancel 等 READY 获得身份再停止；停止失败保留旧 Run，不并发启动。UncertainStart 无句柄不释放环境、不重启；永久 dispose 仅在 verified exit 后关闭控制线程。
6. 退出策略保留原身份与 UID 二次核验、双扫描、观察间隔、预算、STOP/CONTINUE/KILL/QUIT 顺序、suppressed 异常与中断标记；null/拒读/截断与无快照不作为退出证明。

## 实际验证记录

- Java 黄金基线：JVM#1，2026-11-17 00:37:43–00:37:55 UTC，JDK17 新编译 7 份原 Java 与 7 个测试类，**102 运行 / 0 失败 / 0 忽略 / 0 assumption**，exit 0；包括新增 12 项 Java API/黄金契约与既有迟到回调、重复关闭、取消、UNKNOWN 回归。没有改动既有测试。
- 依赖来源：只读复用 main `127ae7bf` 的既有混编依赖，逐一核对 receipt 的 **1078 份实际主源码 SHA-256 全相等**；7 个被测类在本轮新输出目录重新编译。该 harness 的手工 R/SDK37 类型与 Robolectric34 不是 AGP 资源或 APK 证据。
- 冻结证据（忽略/仓库外，不随交付泄露本机日志）：`/tmp/t13-java-bd37aff6/frozen-inputs.json` SHA-256 `bff9faf356c5c142c770a92b78facfac3ba21ad7b9dd3914f6b9f606d0376d46`；`/tmp/t13-java-bd37aff6/java-api.txt` SHA-256 `036ecaff4192be6dcfa4d9c9b1281ee1dadb4863bfff6763a40fe3a5caff7b87`；日志 `/tmp/t13-bd37aff6-java-baseline.log`。执行后再次核源码冻结摘要无变，job 已收 exit 0，实际 JVM argv 为 0 后归还 main 槽位。
- Kotlin 后验：main 分配的候选槽内，01:08:43–01:19:37 UTC 串行运行。KGP 2.2.10 / JDK17 新编译全部 7 个 Kotlin 实现；再重新编译 **1003 份既有 Java 调用方及生成符号**，最后重新编译原 7 个 Java 测试类。首轮及格式化后终轮均为 **102/0/0/0，exit 0**，不把两次运行相加为 204 个不同用例。原未改依赖 1071 份源码 SHA 再核相等，全部新 class 的 major version 为 61。
- 黄金测试强化：初轮独审指出 inner 私有构造器不能直接扩大成无票 Java 创建入口，已恢复 `SimpleTab` / `Ticket` 的 private 原签名并为额外桥加私有 key 校验。补入同一 Java API 测试的 private 反射断言和未知 key 拒绝断言；强化后的同一 **12 项 API 测试在冻结原 Java class 上先复验通过**，再随候选 102 项执行。错误 key 不创建标签、不调用时钟、不消费票代次；未修改既有测试。
- API 对拍：按 class 文件逐一比对 **31 类型 / 206 个原非 private、非 synthetic 成员**；成员名、描述符、泛型签名、checked exceptions 顺序和静态/抽象/同步标记、字段 final/volatile 均无丢失或漂移。新增可见成员仅为 Kotlin Companion、枚举 entries 及两个受私有 key 保护的构造桥；`internal` 的 JVM 可见性映射另见前文，不宣称整个 class 文件逐字节相同。
- 格式：ktfmt 0.64 的 7 文件预检均需格式化，Java 新测试预检也需格式化（两次 rc=1 是格式发现，非执行未知）；按锁定工具写入并验幂等/Java token 不变后，**Kotlin 全树 106 文件 / 18 批 / 0 需格式化**，**Java 全树 1769 文件 / 55 批 / 0 需格式化，missing=0、extra=0**。只定向写这 7 个 Kotlin 和新增黄金 Java 测试，未写其他生产源。
- 静态门禁：源码边界 **25/0**，source hygiene **PASS**。定向 JVM harness 使用真实 7 类与原测试；没有生成 Gradle JUnit XML，不把结构化 JUnit 计数冒称 CI 原 XML。
- 终轮证据：`/tmp/t13-kotlin-bd37aff6-r2/` 的 `frozen-inputs.json` SHA-256 `741a9214e7ed130bb95e1a1a21109e2ccc02e8060b8dc1779a2f57769fb46bf6`、`receipt.json` 为 `8a07bd34fe73c3d705cead2ecd3327de98c2ef43e2921526c045616979151c02`、`api-comparison.json` 为 `b24bb1e14155421bd0248a3c10984388516dbd4f237a16740158c1e44ce1dc07`、`kotlin-api.txt` 为 `44e68abf8066d150be3021a4f6c81e3aa7f6534c2b6437bd886d7f39f2a6f044`。最后再次核 7 生产源和 7 测试源共 14 个 SHA 不变，所有 job 已收 exit 0，实核 `/proc` 中实际 Java/javac/javap argv 为 0 后交还 main。
- 复现入口（仓库外定向工具，不提交私有依赖与日志）：`/tmp/t13-bd37aff6-java-baseline.py`、`/tmp/t13-bd37aff6-candidate.py r2`、`/tmp/t13-bd37aff6-compare-api.py /tmp/t13-kotlin-bd37aff6-r2`。实际 argv、源摘要及 classpath 在对应输出目录的 args/receipt；不重跑已存在的输出目录，复现须换新目录并先取得 main JVM 槽。
- 全 diff 独立审查：初轮审查后端异常结束，收到的具体 P1 构造入口意见已修复。修后完整审查的精确提交、范围和最终结论由本工作包结束声明及 main 看板单独记录；不能用初轮局部意见、编译或测试通过代替完整 APPROVE。
- 未验：本轮未调用 dsha-gradle/AGP 资源链，未重试本机已知 AAPT2 限制；完整 Gradle Kotlin 重编译/单测、CI、Lint、APK、Android/JNI/真实 `/proc`、proot/proroot 和设备终端/UI 均不作为本轮通过证据。`TerminalSessionTest` 的 Linux POSIX 实进程取消用例实际执行且未 assumption，不能扩写成 Android 信号/出生身份矩阵；本 worker 不安装或操作手机。最终运行时输入身份仍由 main 统一重算并验证。

## 回退

由 main 在集成时 revert 本工作包的迁移提交即可恢复原 Java；黄金测试可以独立保留。不涉及数据格式、包名、权限、签名或环境清理；main 若已重算运行时身份，须同时按真实 Java 输入重算并验证描述符，不能直接沿用候选身份。
