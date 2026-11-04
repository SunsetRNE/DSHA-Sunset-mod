# build308 固定构建输入

`codex/build308-rc5` 使用与正式 APK 对应的十一项构建输入。CI 从固定提交下载 Git LFS 中的 ZIP，再根据源码里的锁校验所有归档。下载 URL、整包 SHA-256 和字节数见[分支输入锁](../ci/sunset-inputs.lock.json)。上游历史包 `fixed-inputs-20261103c` 中的 DSH 归档摘要为 `5c24b1f2…`，与 build308 不同。

| 输入 | build308 固定值 |
|---|---|
| [构建输入 ZIP](../ci/fixed-inputs/dsha-build308.zip) | 696,376,035 字节，SHA-256 `ee87cc2d538bec7a4302feccc5b0831b6e10324cba88c4095646cf5208dbbd3d` |
| 正式及独立应急 `dsh-runtime.bin` | 两份均为 127,680,523 字节，SHA-256 `fb5b3f96f6905d7a50353dd395b8601d873b4aacb9bf4f81a19ac00f37a1f16e` |
| 正式及应急根文件系统 | 分别按[正式运行时描述符](../app/src/main/assets/runtime-descriptor.json)与[应急锁](../tools/recovery-runtime/lock.json)验证，两份根文件系统的摘要不同 |
| Python、ADB 及构建来源记录 | 使用[构建输入锁](../tools/ci-build-inputs.lock.json)、[ADB 依赖锁](../app/src/main/assets/adb-wheels.lock.json)，以及 DSH 和 Ubuntu 工具各自的 `*.inputs.json` |

## 本地恢复

在 Git checkout 根目录执行以下命令。第一步取回 ZIP，第二步核对整包摘要、十一项成员的集合与类型，再在暂存目录逐项验证二进制文件。全部通过后才将文件写入构建目录。

```bash
git lfs pull --include="ci/fixed-inputs/dsha-build308.zip"
python3 tools/ci-assets.py --archive ci/fixed-inputs/dsha-build308.zip \
  --sha256 ee87cc2d538bec7a4302feccc5b0831b6e10324cba88c4095646cf5208dbbd3d
```

CI 使用同一校验器的 `--url` 入口和锁内的完整 SHA-256。随后运行 DSH 准备、备份资产生成、运行时描述符验证，生成供单元测试和宿主测试使用的交接归档。手动指定其他输入时仍须成对提供 HTTPS URL 和整包 SHA-256。[CI 输入节点](../.github/workflows/_node-inputs.yml) · [归档校验器](../tools/ci-assets.py)

## 更新输入

当实际构建归档或来源记录发生变化，用 `python3 tools/ci-assets.py --pack <新文件.zip>` 从通过源码校验的输入生成整个 ZIP。先将新对象提交到本分支的 Git LFS，再把锁指向该提交的不可变下载 URL，同时更新摘要、字节数、锁测试和本说明。[输入锁测试](../tools/test-sunset-ci-inputs.py)
