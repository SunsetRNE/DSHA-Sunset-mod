# DSHA 0.2.1-rc.5-20261116.0500-dsh0.2.2-rc.1 / build368

- Rust 发布装配（默认 prebuilt）：release-production 的 rust_native_mode 默认改为 prebuilt，把同一 run 构建并核验的 arm64 libdsha_rs.so 与 rust-native-receipt.json 随正式包装配；off 仍保留为不随包选项。同 run 产物绑定、提交与 run 回执核对、签名后逐成员重核照旧，绑定失败即中止；正式签名仍由 release 工作流用 Mod 自有证书执行。
- Rust 运行时后端默认启用：DshaApp.onCreate() 在语言与设置初始化后显式开启 RustBackends，库随包时 digest、archive 与 verify_plan 默认走原生。进程内字段仍默认 false，普通 JVM 单测不受原生装载影响；setEnabled(false) 一键回 JVM。.so 未随包、加载失败或 ABI 不符时按类型化原因留在 JVM；verify_plan 一旦选中 Rust，内部错误或双实现分歧仍 fail-closed 中止，不用 JVM 结果覆盖。
- Android Rust 探针适配：RustNativeInstrumentation 先核验 App 启动后的默认值，APK 内含收据时要求后端启用；随后临时关闭开关跑原有 native 差分与失败清理探针，finally 恢复初始状态并断言恢复成功。新增 BACKENDS_NOT_DEFAULT_ON 与 BACKENDS_RESTORE_FAILED；无收据的 .so 未随包构建只记录默认值，不误报。
- 发布流水线修复：修正 dry-run/publish 单变体测试包证据下载布局（bf491c77），只请求 debug 测试包时不再因证据目录合并失败；补齐 androidTest 的 refreshPowerMode(Context) 调用（872d48ac），恢复 prebuilt debug 构建的 androidTest 编译。
- 覆盖升级影响：runtimeId 更新为 **52624be1ad386017c68c2fc986c0e834eaeec5364e9539bd0f3aa8dc74e1daba**（517 个启动输入、205 个资产输入；启动源码含 439 Java + 18 Kotlin），原因是 DshaApp 与 Rust 后端/桥进入受管启动输入。baseVersion 10、dataWrite dsh-0.2.2-rc.1 不变。**367→368 覆盖升级后首次启动会进入一次整屏运行环境维护页**，个人数据保留；版本号提升本身不改 runtimeId。
- 构建输入与兼容范围：沿用 fixed-inputs-20261113，资产 dsha-a-su-fixed-inputs-20261113-build364.zip（716,787,167 B；SHA-256 2c8dc3fa494a5faf6f65cafa966b47d234a3331c30396198005cedfccca82cad）。内置 dsh-web-mobile 3.3.5、自动默认禁用语义、SAF“跨应用文件访问”、A8-P1 工程基座、APK 更新（M17）、内置插件启用（T2）、S1a/S1b 与既有 UNKNOWN 保守处理保持不变；contract 边界、数据格式、恢复语义与恢复计划 fail-closed 不回退。
- 版本与范围：版本码提升为 368，基于已发布 build367（tag dsha-a-su-build367-20261115-230955 @ f3f8fc03）。DSHA 修订保持 0.2.1-rc.5，包名 io.github.sunsetrne.dsha、Mod 自有证书、单一 Standard 与签名方案保持；本候选尚未发布，计划由用户批准后的 Android release 工作流构建，并附带 .debug / .dev 共存测试包。
- 验证范围与限制：build368 之前的 main（bf491c77）prebuilt dry-run 43804700097 已全绿（含 debug/androidTest 装配、测试包签名与 Dry-run release plan），该历史结果只证明流水线，不等于 build368 提交的完整门禁。**build368 完整 Gradle 单测、Lint、APK/测试包与正式签名仍待本版发布工作流实跑；设备验收待第二台手机用 .debug / .dev 测试包与 ADB 日志完成，不在本机安装官方包**。维护页/READY、Rust 原生加载与回退、备份/恢复与双屏敏感页待设备核对，不宣称广泛 ROM/管理器兼容。
- 十语种更新说明见 [build368 更新说明](build368-changes.zh.md)。
