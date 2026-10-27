# DSHA 0.2.1-rc.3-20261026.0735-dsh0.2.1-rc.3 / build198

- 与原生插件页共用同一张卡片的 `dsh-auto-review` 别名对，运行证据改为报告补丁实际加载的官方版本：只有补丁插入的 `peerDependencies` 模块真实解析、且安装版本与锁定值一致时才替换；解析不到或版本不一致仍保留包装版本，交给原生侧判未确认。其它包装（如 `dsh-computer-use-android`）在列表里展示包装自己的版本，不做替换。修复 Auto review 卡片永远停在“加载状态未确认”。
- 原生插件身份归一化收敛到 `PluginIdentity`，条目 `stableKey` 与运行投影共用同一别名规则，避免两处各自维护。
- Web 活动快照读取新增 `BackupFileSystem.currentSnapshot`：只校验已打开描述符内的字节，容忍写方每 2 秒用 rename 发布新快照；Android 实现仍使用 NOFOLLOW 与大小上限，不要求路径 inode 在读取期间不变。
- 运行证据新鲜度窗口由 10 秒放宽到 30 秒（写方心跳 2 秒，代次与实例已单独核对），长任务占住事件循环时不再把正常心跳判成未确认。
- `PluginRuntimeProjection.rejection` 返回拒绝原因码；`PluginRepository` 只在结论变化时记录 `PLUGIN_RUNTIME`（身份缺失、代次不符、读取失败、快照过期或仍有未确认项），不再静默失败。
- 新增回归：JVM 快照读取在原子替换下的容忍与字节上限；投影拒绝原因码；包装 bundle 运行证据报告锁定官方版本（含锁定与实际不一致的负向分支）。

版本码提升到 198；包名、历史签名、常规文件名及内置 DSH rc.3 保持。本分支只跑 Standard；本轮由 CI 编译与单测验证，未做设备验收。
