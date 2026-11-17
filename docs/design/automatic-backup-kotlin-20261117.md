# T14 自动备份 Kotlin 等价迁移专项回执（2026-11-17）

状态：Java golden 已冻结，尚未执行；生产仍为原 Java。验证必须由 main59161579 串行派槽，不能把本文计划写成通过。

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

已核对的环境 JAR SHA-256（只读校验，尚未启动 JVM）：

| 输入 | 版本 / SHA-256 |
|---|---|
| kotlin-compiler-embeddable | 2.2.10 / `79628fd5d64b4692a62f98962baab00e9076a37e2cffd6ace33cd0b7be7d1cdf` |
| junit | 4.13.2 / `8e495b634469d64fb8acfa3495a065cbacc8a0fff55ce1e31007be4c16dc57d3` |
| Robolectric Android | 14-robolectric-10818077-i7 / `3fdac2d463f431c6b8d83b9d0ca739e795de75fffedee648c6761bd4bd864701` |
| Android SDK | android-37.0 / `bf1b4387cc7ca94fc6ef684f040d9d16fbf16248e181819f020736ea2053f177` |

## Java API 与行为 golden

先提交以下五个 Kotlin 测试文件，全部对原 Java 实现运行；原有 Java 的 [AutomaticBackupPolicyTest](../../app/src/test/java/com/deepseekharness/app/util/AutomaticBackupPolicyTest.java)、[AutomaticBackupPrunerTest](../../app/src/test/java/com/deepseekharness/app/backup/AutomaticBackupPrunerTest.java) 和 [ScheduledBackupOwnerTest](../../app/src/test/java/com/deepseekharness/app/util/ScheduledBackupOwnerTest.java) 保留并复跑。

- [AutomaticBackupApiGoldenTest](../../app/src/test/java/com/deepseekharness/app/backup/AutomaticBackupApiGoldenTest.kt)：同 FQCN、Java 静态方法名/参数/返回类型、IOException 声明、9137/9138、工具类私有构造、Service 无参构造与框架覆盖。允许 Kotlin 的合成成员；不把反射 API 兼容扩写成全字节码相同。
- [AutomaticBackupPolicyGoldenTest](../../app/src/test/java/com/deepseekharness/app/util/AutomaticBackupPolicyGoldenTest.kt)：1–168 小时边界、anchor/last 最大值、clock rollback、停止事件单次消费、默认/未知/null mode、每日分钟钳位、纽约 gap/overlap、Lord Howe 半小时 gap、调用方所传时区。
- [AutomaticBackupsAndroidGoldenTest](../../app/src/test/java/com/deepseekharness/app/backup/AutomaticBackupsAndroidGoldenTest.kt)：真实旧偏好名/键与默认开启；周期 15 分钟/持久化/无网络；立即请求 1500ms 与 manual；关闭/格式化取消；stop 模式与 completed；`recordError`/`completed` 已有门禁差异；`password`/error/completed 共用原 Java 类监视器。
- [AutomaticBackupServiceGoldenTest](../../app/src/test/java/com/deepseekharness/app/backup/AutomaticBackupServiceGoldenTest.kt)：真实 Service 与可控主线程队列；替换宿主状态/归档边界，不实际备份。繁忙时自动延期、手动 retry；取消后迟到准备不提交、不关闭下一票据；secret 清零和 lease 释放；只取消精确 owned ID；销毁与错误只完成一次。
- [AutomaticBackupPrunerGoldenTest](../../app/src/test/java/com/deepseekharness/app/backup/AutomaticBackupPrunerGoldenTest.kt)：真实临时加密副本；只有 boolean true 且 COMPLETE/插件警告成功计入；保留3份/稳定并列时间、手动/未知/partial/best-effort/忙碌/未关闭/额外文件原件保持；全量预核验失败前不删；删除前二次核验。

## 等价要求与已知 JVM 差异

`AutomaticBackups.class` 是既有 Java 外部协作者可持有的监视器。迁移必须显式使用该同一 Class 对象，不能用 companion/object monitor 替换。`password` 的原始同步边界整段保留，不修改密钥文件、随机源、加解密、验证或错误码。

Kotlin 没有 Java package-private；原包内静态入口拟用 `internal` + `@JvmName` + `@JvmStatic` 保留不带模块后缀的 Java 名称，JVM 可见性会放宽为 public。它们的调用范围仍限本包，不新增业务入口。Java 的 static synchronized 标志在显式类锁实现下可变，但共享锁行为必须由 golden 验证；此差异必须在最终独审明确核对。

## 验证状态

尚未执行：新旧定向单测、Java/Kotlin 交叉编译、双语格式、Gradle、Lint、APK、完整 CI、设备。未启动任何 JVM；无 UNKNOWN 动作；无推送/合入/安装/手机操作。

main 排队为 FIFO JVM#2；需先交 golden 精确 SHA 与唯一命令/时限，再等明确授槽。

## 回退与交还

Java golden 与 Kotlin 生产迁移分提交。回退仅 revert 生产迁移及后续专项更正，恢复这4份 Java、删除同名 Kotlin；保留行为 golden 继续验证。无需用户数据/偏好迁移、无需改包名/签名/版本、无需操作手机；最终身份由 main 在集成或回退后统一重算。

独立审查：待最终 diff 完成后审查全部生产/测试/文档变更，结论精确绑定提交；本次未宣称 APPROVE。
