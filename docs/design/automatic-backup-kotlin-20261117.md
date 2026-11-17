# T14 自动备份 Kotlin 等价迁移专项回执（2026-11-17）

状态：四类同 FQCN Kotlin 等价迁移与本机定向验证已完成。原 Java、新 Kotlin 使用相同测试源，分别完整通过39/0/0/0；格式与边界门禁通过。生产/格式提交为 `359223be5eebcaa43515aa022ba5d1ab0bffacaf`，最终独审结论绑定交还声明中的提交，不由作者预填批准。

## 基线与边界

本树 `kotlin-automatic-backups` 基于已刷新 `origin/main`：`127ae7bfc72b0e36ba0b26dd2e8f6a7ebf62b8db`。
唯一生产域为 backup 的 `AutomaticBackups`、`AutomaticBackupService`、`AutomaticBackupPruner` 与 util 的 `AutomaticBackupPolicy`，整文件迁为同 FQCN Kotlin。
对应测试和本文属于专项范围；归档、恢复事务、格式、密码/Keystore 行为、Rust、UI、共享调用方均保持只读。最终受管身份由 main 统一生成。

原 Java 的 SHA-256（冻结于生产改动前）：

| 类 | SHA-256 |
|---|---|
| AutomaticBackups | `0aebbf0c8024812ac5f0dabae4808871b9583916ed589d5fdbda2c6cae881c0f` |
| AutomaticBackupService | `ef6dc7ef60a417ee7360723d6b7fc004443e3c2c9e1ef65b91fdd3de13e6ccdf` |
| AutomaticBackupPruner | `47f1578c5306c7a1336e0840e74c84ad8708ffa7f044f6f987dd06481748d003` |
| AutomaticBackupPolicy | `8b932d4217e78d6e87a36d5d921dae469d6dc28d3d48fd6d69ea2d2bdee49c7a` |

已核对的环境 JAR SHA-256（编译与执行均使用同一固定字节）：

| 输入 | 版本 / SHA-256 |
|---|---|
| kotlin-compiler-embeddable | 2.2.10 / `79628fd5d64b4692a62f98962baab00e9076a37e2cffd6ace33cd0b7be7d1cdf` |
| junit | 4.13.2 / `8e495b634469d64fb8acfa3495a065cbacc8a0fff55ce1e31007be4c16dc57d3` |
| Robolectric Android | 14-robolectric-10818077-i7 / `3fdac2d463f431c6b8d83b9d0ca739e795de75fffedee648c6761bd4bd864701` |
| Android SDK | android-37.0 / `bf1b4387cc7ca94fc6ef684f040d9d16fbf16248e181819f020736ea2053f177` |

## Java API 与行为 golden

Java golden 先提交以下五个 Kotlin 测试文件，全部对原 Java 实现运行；原有 Java 的 [AutomaticBackupPolicyTest](../../app/src/test/java/com/deepseekharness/app/util/AutomaticBackupPolicyTest.java)、[AutomaticBackupPrunerTest](../../app/src/test/java/com/deepseekharness/app/backup/AutomaticBackupPrunerTest.java) 和 [ScheduledBackupOwnerTest](../../app/src/test/java/com/deepseekharness/app/util/ScheduledBackupOwnerTest.java) 保留并复跑。

- [AutomaticBackupApiGoldenTest](../../app/src/test/java/com/deepseekharness/app/backup/AutomaticBackupApiGoldenTest.kt)：同 FQCN、Java 静态方法名/参数/返回类型、IOException 声明、9137/9138、工具类私有构造、Service 无参构造与框架覆盖。允许 Kotlin 的合成成员；不把反射 API 兼容扩写成全字节码相同。
- [AutomaticBackupPolicyGoldenTest](../../app/src/test/java/com/deepseekharness/app/util/AutomaticBackupPolicyGoldenTest.kt)：1–168 小时边界、anchor/last 最大值、clock rollback、停止事件单次消费、默认/未知/null mode、每日分钟钳位、纽约 gap/overlap、Lord Howe 半小时 gap、调用方所传时区。
- [AutomaticBackupsAndroidGoldenTest](../../app/src/test/java/com/deepseekharness/app/backup/AutomaticBackupsAndroidGoldenTest.kt)：真实旧偏好名/键与默认开启；周期 15 分钟/持久化/无网络；立即请求 1500ms 与 manual；关闭/格式化取消；stop 模式与 completed；`recordError`/`completed` 已有门禁差异；`password`/error/completed 共用原 Java 类监视器。
- [AutomaticBackupServiceGoldenTest](../../app/src/test/java/com/deepseekharness/app/backup/AutomaticBackupServiceGoldenTest.kt)：真实 Service 与可控主线程队列；替换宿主状态/归档边界，不实际备份。繁忙时自动延期、手动 retry；取消后迟到准备不提交、不关闭下一票据；secret 清零和 lease 释放；只取消精确 owned ID；销毁与错误只完成一次。
- [AutomaticBackupPrunerGoldenTest](../../app/src/test/java/com/deepseekharness/app/backup/AutomaticBackupPrunerGoldenTest.kt)：真实临时加密副本；只有 boolean true 且 COMPLETE/插件警告成功计入；保留3份/稳定并列时间、手动/未知/partial/best-effort/忙碌/未关闭/额外文件原件保持；全量预核验失败前不删；删除前二次核验。

## 等价要求与已知 JVM 差异

`AutomaticBackups.class` 是既有 Java 外部协作者可持有的监视器。迁移必须显式使用该同一 Class 对象，不能用 companion/object monitor 替换。`password` 的原始同步边界整段保留，不修改密钥文件、随机源、加解密、验证或错误码。

Kotlin 没有 Java package-private；原包内静态入口采用 `internal` + `@JvmName` + `@JvmStatic` 保留不带模块后缀的 Java 名称，JVM 可见性放宽为 public。它们的生产调用范围仍限本包，不新增业务入口。`javap -p` 已核各静态签名、原始构造可见性和 IOException 声明；Kotlin 新增合成 Companion/accessor 不作为业务 API。Java `password` 的 static synchronized 标志由显式类锁替代；真实类监视器探针已在两种实现上通过，整段密钥准备与原空屏障均锁相同 Class 对象。

## 验证状态

Java 基线（`8965d15b` → 补强 `6ea83741` → 夹具修复 `0ce7d0d3`）：main 授独占槽，首 JVM 命令记录 `2026-11-17T00:47:55Z`，00:50:55Z 收槽并核 `/proc` JVM=0。原4 Java、新5 Kotlin golden、既有3 Java测试及真实文件系统 helper 均新编译成功。首轮实际39项/3失败/0忽略/0assumption；失败是动态 Java caller 临时 classpath 漏独立协作者目录（1项）和回调 shadow 未继承 ShadowService（2项销毁用例）。只修两个测试夹具后，在新测试输出窄重跑受影响12项，12/0/0/0；其余27项在首轮通过。没有把它写成同次39项全绿。

原始日志与输入/依赖 proof 保留在 `/tmp/t14-b26c9fdc/baseline/`、`/tmp/t14-b26c9fdc/baseline-fix/`；两次驱动日志为 `/tmp/t14-b26c9fdc/baseline-driver.log`、`/tmp/t14-b26c9fdc/baseline-fix-driver.log`；后台作业分别 `bash-b5b7646cdedb4519`（exit1）与 `bash-500d9057d7f940a6`（exit0），都已收集。

基线独审指出的两项 P1 已实修。两项 P2 又补强原 API 的 public/static/final/私有构造断言，真实 poll 的 busy/disable/原生error完成，以及真实 onStartJob→准备线程→主队列 handoff 和已有票据拒绝；原39个方法身份保留。Kotlin 调用伴生实例而 Java 调用外层 static，服务测试为这两个入口提供同一 fake 状态，避免迁移后绕过原测试隔离边界。

最终后验由 main30ff6029 明确授独占槽：从 `127ae7bf` Git 对象取出并逐 SHA 核验原4 Java，放入全新输出；将新4 Kotlin 放另一全新输出；两组各自真编译同5 Kotlin golden、原3 Java测试及文件系统 helper。借用经 `compiled-source-binding.json` 绑定原127ae的既有混编产物作为未改协作者依赖，不把它写成重新编译全部应用调用方。classpath 均先放本组主产物，API golden 用 canonical codeSource 强制四类加载自新主输出，字节码 major=61；Java caller 在测试内实际 javac 编译，checked exceptions/原Class锁探针均通过。

| 组 / 门禁 | 结果 |
|---|---|
| 原 Java 参考组 | 39 run / 0 failures / 0 ignored / 0 assumptions |
| 新 Kotlin 修正组 | 39 run / 0 failures / 0 ignored / 0 assumptions |
| 同源与新鲜性 | 两组9份测试/helper源 SHA 全等；输出目录预先不存在，拒绝旧输出复用；4新主class必需存在；运行前后源码摘要不变 |
| Kotlin 格式 | ktfmt 0.64，9文件显式 write；全树108文件/20批/0需格式化，类型修复后复核同样108/0 |
| Java 格式 | google-java-format 1.22.0，全树1771文件/55批/0需格式化；Kotlin单行修复不重复Java检查 |
| API / 互操作 | 两组 javap、Java caller 真编译、静态方法/IOException/9137与9138/构造/public-final断言通过；4新Kt class major61 |

最终首作业 `bash-0552be43bdf84c10` 保留 exit1：双格式、原 Java39/0/API 先通过，候选 K2 在 `postDelayed(poll, 300)` 因 Runnable? 不能传入SDK37非空参数而停止，尚未运行候选测试。唯一窄修为 `poll!!`，保留“当前有效票据已登记poll”的不变式；不以空值分支跳过轮询。新目录 `/tmp/t14-b26c9fdc/candidate-fixed/` 窄续作业 `bash-baaf32c65b324ca4` exit0，重新执行 Kotlin格式/4Kt编译/同39/API。还原这一行的 `!!` 后源摘要精确等于首轮格式后的同文件，证明窄修仅此一行。其余三生产文件和9测试/helper源与原Java参考组摘要一致。

首 JVM 命令记录为 `2026-11-17T01:35:12.421468Z`，末命令结束为 `01:44:39.230728Z`；未采到首末具体 JVM 进程的 `/proc` 时刻，字段均如实为 null。`01:45:53.224696Z` 独立扫描 `/proc` 无 java/javac/javap 进程，两作业全收后交还槽位，未续占或重跑已通过的原Java组。完整同源双39的原始日志、JAR/源码/主class/日志摘要在 `/tmp/t14-b26c9fdc/final/` 与上述 candidate-fixed 下。

参考组 proof SHA-256：`8a427bcb5e59d8fd541cf2b604978115da6ff8ea23c4671686bb42fde3c87e6f`；修正候选 proof SHA-256：`b1562b5c730cc1d35ba616d940fad2a8d73b5bff62ce9dfd484a6e287b29a6eb`。本树汇总为 `app/build/t14/final-verification.json`，原日志/proof备份保留在 `app/build/t14/evidence/`；这些是本机 ignored 取证，不冒充受管完整CI单测回执。

非 JVM 门禁：源码卫生通过；core-boundaries 首跑25项中1失败，原因仅旧边登记仍写 AutomaticBackups.java，实际同 FQCN 两个宿主协调边改成 .kt 后被识别成新边。现 main30ff6029 明确许可仅两条 file 后缀同步；JSON 深比较证明仍为17边、type/schema/其它边完全一致，未修改 checker。修正后25项全通过。

尚未执行：真实 AGP 交叉编译/Gradle UnitTest、完整 CI、Lint、资源打包、APK、安装与设备验收。直接 Robolectric34+SDK37类型/R符号不是 AGP资源或APK证据；真实 ROM 的 JobScheduler、Keystore成功读写和设备运行态未验。未在本树生成/检查最终 runtime descriptor、managed recipe 或版本身份；由 main 集成后统一处理，不用现有主线CI结果替代本分支证据。无 UNKNOWN 动作；无推送/合入/安装/手机操作。

原 main59161579 已批准格式清单仅移除本轮4个已删 Java 路径；JSON 集合差异证明 removed=4、added=0、schema/purpose及存活成员顺序不变。旧→新映射为 `backup/AutomaticBackupPruner.java→.kt`、`backup/AutomaticBackupService.java→.kt`、`backup/AutomaticBackups.java→.kt`、`util/AutomaticBackupPolicy.java→.kt`（均在原包目录）。main30ff6029 另批准备份说明只改这三个 backup 类的源码链接 target 后缀；与父版本逐字节替换比较，仅3处 `.java→.kt`，叙述和其它链接不变，文档链接门禁通过。

## 回退与交还

Java golden 与 Kotlin 生产迁移分提交。完整回退可按依赖逆序 revert 本包提交链；若保留 golden，则精确恢复原4份 Java、删除同名 Kotlin，并恢复这4条 Java格式登记与两条架构边的 `.java` 后缀，保留其它 worker 的登记增量。不要整份覆盖共享 JSON 或回退 main 的其它改动。无需用户数据/偏好迁移、无需改包名/签名/版本、无需操作手机；最终身份由 main 在集成或回退后统一重算，复跑原Java同39项验证。

独立审查会话：`ac15b4f2-eef6-44d3-a98e-dfb8f281d60d`。原Java黄金 `0ce7d0d3` 已获 APPROVE；生产草稿审查打回过伴生测试适配、两条旧路径登记和上述非空类型错误，全部修复后须再核最终格式/单行diff与双39原证据。作者不代填批准，最终全17路径审查结论、精确绑定SHA与交还状态记录在树根 `.SESSION-DONE`，再以 peer_report 交main。无合入/推送授权。
