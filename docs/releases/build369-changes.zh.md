# DSHA 0.2.1-rc.5-20261116.0740-dsh0.2.2-rc.1 / build369

- Kotlin 整域迁移：T6 的 capabilities 6 类、T5 的 runtime 命令与挂载组装 8 类，以及 plugins 管理 11 类由 Java 整体迁为 Kotlin，共 25 类。保持既有能力注册、启动命令、环境变量、挂载清单、插件事务与用户禁用语义；本版不承诺性能提升，A8-P3 多模块切片不在本版。
- 集成修复：受管动态读取器改为 ProotBootstrap.kt，同步命令环境、运行库和发布验收契约的源路径，再刷新受管 descriptor，防止迁移后仍读取旧 Java 文件或使用旧生成器摘要。
- 覆盖升级影响：runtimeId 为 **1d42f79d5fea6bad5caaa2409822aa5949691c89e6e230b95011ae35db8a5757**（517 个启动输入、205 个资产输入；启动源码含 414 Java + 43 Kotlin）。baseVersion 10、内置 DSH 0.2.2-rc.1 与数据格式不变。**368→369 覆盖升级后首次启动预期进入一次整屏运行环境维护页**，个人数据保留；这项升级预期尚未做本版真机验收。
- Rust 与插件默认值：Rust 装配继续使用 prebuilt、运行时后端默认启用；同 run 产物与来源证明保持。库不可用按类型化原因使用 JVM，已选中 Rust 后的失败不重放、不用 JVM 掩盖双实现分歧。内置 dsh-web-mobile 3.3.5 及默认启用、自动默认禁用与用户主动禁用意图保持。
- 构建与兼容范围：沿用 fixed-inputs-20261113 / dsha-a-su-fixed-inputs-20261113-build364.zip（716,787,167 B；SHA-256 2c8dc3fa494a5faf6f65cafa966b47d234a3331c30396198005cedfccca82cad）。SAF“跨应用文件访问”、contract 边界、安全模式、UNKNOWN 保守处理与恢复语义不变；Android 11+ / arm64、单一 Standard、包名 io.github.sunsetrne.dsha 与 Mod 签名保持。
- 版本与验证：版本码提升为 369，基于已发布 build368。集成候选 c243b7a0 的 dry-run 43823137009 已通过完整单测、Lint、格式、正式包与 .debug / .dev 测试包签名检查；正式发布流水线按 build369 的最终提交重新核验并绑定产物。本版尚未真机验收，不把 build368 的设备回执当作本版通过证据，不宣称广泛 ROM/管理器或 16 KB 页内核兼容。
