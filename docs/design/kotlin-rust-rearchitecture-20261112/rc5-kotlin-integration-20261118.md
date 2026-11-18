# Kotlin 批量迁移与 DSH rc.5 集成工作记录

日期：2026-11-18。基线提交 `2cc4e646f8ab232eef0070c9d844b806241caf6e`，本地集成分支 `rc5-kotlin-integration`。这是未发布的开发批次；不安装 APK，不将开发运行时注入当前应用。

## 批次边界

- Rust 调用桥：迁移 `RustArchiveCodec`、`RustBackends`、`RustDigest`、`RustGzipInputStream`、`RustGzipOutputStream`，原 Java 合计 1,312 行。
- 备份策略与状态：迁移 `BackupExportGuidance`、`BackupFileNames`、`BackupHealth`、`BackupScope`、`BackupScopes`、`BackupTaskKinds`、`BackupTaskState`、`ScheduledBackupOwner`，原 Java 合计 647 行。
- 合计 13 个生产类、1,959 行原 Java；同一职责域保持既有调用闭包与测试。`RustNative.java` 的 JNI owner、11 个 native/static 方法及类监视器保留原字节。
- 正式 DSH 运行时从 `0.2.2-rc.1` 升级至 `0.2.2-rc.5`；原清单包含 61 active、5 specialized、8 retired，逐项按实际功能和上游源码核对。独立应急运行时不跟随正式依赖自动升级。

原 Java 来源摘要保存为本轮仓库外核验回执，不把工作树路径或测试数量当成源码身份证明。JVM 层的 Kotlin 编译器新增成员、原 package-private 测试缝的可见性变化分别登记在子批次 API 回执；不宣称类文件逐字节等价。

## 已验证的 Rust 基线

使用仓库锁定 Rust/Cargo 1.100.0，host `aarch64-unknown-linux-gnu`。`tools/rust/build.py --host --check --jobs 1` 在两个不同源码目录重建通过，产物 348,736 B，SHA-256：

```text
04f07e7a2adb64e034ba66dfce2467ba369af11110f876b10b6a5aef59156bed
```

ELF 为 GNU AArch64 / ET_DYN，11 个冻结 JNI 导出、RELRO、16 KiB LOAD 对齐、体积与来源边界检查通过。真实 `cargo test --locked --offline` 执行 **48 tests，0 failed，0 ignored**。这些结果只属于主机内核与 JVM 对拍前置条件，不代表 Android 装载、设备页大小或正式 CI 同 run 原生产物通过。

当前 proroot 将 Cargo `link(2)` 产物表示为软链接，但 `lstat` 呈现为硬链接；`O_NOFOLLOW` 仍正确拒绝。为本机验证使用仓库外的窄 Cargo 适配器：只在真实 Cargo 成功后，将本轮构建目录内指向 `build/dsha-core/.../out/libdsha_rs.so` 的产物复制为普通文件，核对源与目标字节一致，再交由原仓库校验器验证。没有移除 `O_NOFOLLOW`、放宽来源门禁或修改 Rust 核心算法。

## 集成状态

本记录暂为开发进度快照，最终提交需补：各迁移分支提交、全项目 Java/Kotlin 编译和定向回归、最终 rc.5 归档摘要、完整补丁处置表、数据兼容测试和未验范围。此前版本的发布/设备记录不计为本批次证据。
