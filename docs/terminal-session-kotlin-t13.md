# T13 终端会话 Kotlin 等价迁移冻结与回执

基线：开工时 `origin/main` = `127ae7bfc72b0e36ba0b26dd2e8f6a7ebf62b8db`。本工作包先在原 Java 实现上冻结 API 与黄金行为，再整文件迁移；最终运行时身份由 main 串行重算。本文件不代表 CI 或设备验收通过。

## 冻结范围

仅迁移同 FQCN 的 7 类：`core.TerminalSessionOwner`、`core.TerminalRuntimeProtection`、`core.SimpleTerminalBackend`、`runtime.TerminalProcessCloser`、`util.TerminalTabs`、`util.TerminalSession`、`util.TerminalExitPolicy`。既有 Java 调用方与测试 FQCN 不改；新增 Java 契约测试同时用于迁移前后。

不改 PTY 原生握手、信号与出生身份判据、Web 生命周期、UI、构建模块、依赖、版本及描述符。原 Java 源与对应测试由基线提交永久保留；编译 API 快照与实际执行结果待串行槽位补入。

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

## 验证记录（待实际执行填写）

- Java 黄金基线：JVM#1，2026-11-17 00:37:43–00:37:55 UTC，JDK17 新编译 7 份原 Java 与 7 个测试类，**102 运行 / 0 失败 / 0 忽略 / 0 assumption**，exit 0；包括新增 12 项 Java API/黄金契约与既有迟到回调、重复关闭、取消、UNKNOWN 回归。没有改动既有测试。
- 依赖来源：只读复用 main `127ae7bf` 的既有混编依赖，逐一核对 receipt 的 **1078 份实际主源码 SHA-256 全相等**；7 个被测类在本轮新输出目录重新编译。该 harness 的手工 R/SDK37 类型与 Robolectric34 不是 AGP 资源或 APK 证据。
- 冻结证据（忽略/仓库外，不随交付泄露本机日志）：`/tmp/t13-java-bd37aff6/frozen-inputs.json` SHA-256 `bff9faf356c5c142c770a92b78facfac3ba21ad7b9dd3914f6b9f606d0376d46`；`/tmp/t13-java-bd37aff6/java-api.txt` SHA-256 `036ecaff4192be6dcfa4d9c9b1281ee1dadb4863bfff6763a40fe3a5caff7b87`；日志 `/tmp/t13-bd37aff6-java-baseline.log`。执行后再次核源码冻结摘要无变，job 已收 exit 0，实际 JVM argv 为 0 后归还 main 槽位。
- Kotlin 编译 / Java 互操作 / API 比对：待执行。
- Kotlin / Java 格式门禁：待 main 排槽。
- 定向测试与原 XML 统计：待执行。
- 全 diff 独立审查：待候选冻结后执行。
- 未验：完整 CI、Lint、APK、Android/JNI/真实 /proc、设备终端与 UI；本 worker 不安装或操作手机。

## 回退

由 main 在集成时 revert 本工作包的迁移提交即可恢复原 Java；黄金测试可以独立保留。不涉及数据格式、包名、权限、签名或环境清理；main 若已重算运行时身份，须同时按真实 Java 输入重算并验证描述符，不能直接沿用候选身份。
