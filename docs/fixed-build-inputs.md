# 固定构建输入（公开仓库预发布资产）

更新：2026-10-31（build216 / 内置 DSH 0.2.1-rc.5，独立应急 DSH 同为 rc.5）。当前资产 tag [`fixed-inputs-20261031`](https://github.com/SunsetRNE/DSHA-Sunset-mod/releases/tag/fixed-inputs-20261031)（GitHub 预发布 / prerelease），
锁文件为 [`ci/sunset-inputs.lock.json`](../ci/sunset-inputs.lock.json)。

## 为什么固定输入不放进 git

7 个大型固定二进制/压缩包（`offline-rootfs.bin` 最大 304 MB）加上 2 个独立应急归档，合计约 664 MiB。
放进 git 会超过仓库与 git 单文件限制、永久膨胀历史，且这些字节可由受管 recipe 重新生成、以 SHA-256 固定校验；
它们不是手写源码，不需要随每次提交分发。因此它们只作为**GitHub Release 资产**发布：

- 仓库里保留锁文件（URL、SHA-256、format、release、asset）与每个输入在 [`app/src/main/assets/runtime-descriptor.json`](../app/src/main/assets/runtime-descriptor.json)、
  [`tools/ci-build-inputs.lock.json`](../tools/ci-build-inputs.lock.json)、[`app/src/main/assets/adb-wheels.lock.json`](../app/src/main/assets/adb-wheels.lock.json)、
  [`tools/recovery-runtime/lock.json`](../tools/recovery-runtime/lock.json) 中的 pin；
- 大文件本体放在预发布资产里，克隆后按锁还原，任何字节不一致都会在任何写入前拒绝。

2 个 provenance `*.inputs.json` 是小文本，仍以 tracked 文件形式保留在仓库中；它们同时也被打进资产包，
使恢复集合保持 11 项、单一来源。还原后的字节与仓库内文件一致（`--source-archive` 恢复同样受 pin 流程约束）。

`.gitignore` 仍列出 9 个不入库输入；两个 `*.inputs.json` 因为已被 git 跟踪，`.gitignore` 不会生效，属于预期状态。

## 资产内容（11 项，STORED zip）

| ZIP 成员 | 字节 | 当前 pin 来源 |
|---|---:|---|
| `app/src/main/assets/offline-rootfs.bin` | 304037588 | `runtime-descriptor.json` |
| `app/src/main/assets/dsh-runtime.bin` | 127674426 | `runtime-descriptor.json` |
| `app/src/main/assets/ubuntu-tools.bin` | 29919475 | `runtime-descriptor.json` |
| `app/src/main/assets/pnpm-runtime.bin` | 3837861 | `runtime-descriptor.json` |
| `app/src/main/assets/python-support.bin` | 904360 | `tools/ci-build-inputs.lock.json`（大小 + 摘要） |
| `app/src/main/assets/glibc-python.tar.gz` | 6565414 | `runtime-descriptor.json` |
| `app/src/main/assets/adb-wheels.tar.gz` | 9951074 | `app/src/main/assets/adb-wheels.lock.json` |
| `app/src/main/assets/dsh-runtime.inputs.json` | 11490 | provenance（不单独做二进制 pin） |
| `app/src/main/assets/ubuntu-tools.inputs.json` | 592 | provenance（不单独做二进制 pin） |
| `tools/recovery-runtime/archives/recovery-rootfs.bin` | 85783057 | `tools/recovery-runtime/lock.json` |
| `tools/recovery-runtime/archives/recovery-dsh-runtime.bin` | 127674426 | `tools/recovery-runtime/lock.json` |

覆盖 Ubuntu 离线 rootfs、DSH runtime、ubuntu tools、pnpm runtime、Python 支持、glibc Python、ADB wheels，
以及独立应急 DSH 的 rootfs / runtime 归档。正式归档为内置 DSH `0.2.1-rc.5`
（127,674,426 B / SHA-256 `91aa4685721ce569e32953295dc6b01b63fe2ac5e1eac8173440656896acdcb2`）；
build215 起独立应急锁经用户授权同步到同一 `0.2.1-rc.5` 归档字节，APK 内两者共用一份（`recovery-asset-locations.json` 映射），
锁与验证合同仍相互独立，正式升级不会自动改动应急锁。

## 锁定与还原

[`ci/sunset-inputs.lock.json`](../ci/sunset-inputs.lock.json) 的字段：

```json
{
  "schema": 1,
  "url": "https://github.com/SunsetRNE/DSHA-Sunset-mod/releases/download/fixed-inputs-20261031/dsha-a-su-fixed-inputs-20261031.zip",
  "sha256": "a1225b84d3c147727f27c92bf0dd684ed3be905d0a72f376c98dae7fef085726",
  "format": "bundle",
  "release": "fixed-inputs-20261031",
  "asset": "dsha-a-su-fixed-inputs-20261031.zip",
  "bytes": 696361527
}
```

[`tools/sunset-ci-inputs.py`](../tools/sunset-ci-inputs.py) 在没有显式 `SUNSET_DISPATCH_*` / `SUNSET_REPOSITORY_*` 成对覆盖时读取该锁，
输出 `url` / `sha256` / `format`；CI 只在 `format == "source-archive"` 时给 [`tools/ci-assets.py`](../tools/ci-assets.py) 加 `--source-archive`，
`bundle` 直接按 11 项固定成员集合恢复。手动覆盖必须 URL + SHA 成对，半对即失败。

还原命令（由锁下载，CI 使用的路径）：

```bash
python3 tools/ci-assets.py --url "<lock.url>" --sha256 "<lock.sha256>"
```

已有本地归档时：

```bash
python3 tools/ci-assets.py --archive dsha-a-su-fixed-inputs-20261031.zip \
  --sha256 a1225b84d3c147727f27c92bf0dd684ed3be905d0a72f376c98dae7fef085726
```

`ci-assets.py` 的校验顺序：整包 SHA-256 → 成员白名单/路径/类型/重复与总大小 → 解压到暂存目录后逐项对照
`runtime-descriptor.inputs`、`ci-build-inputs.lock.json`、`adb-wheels.lock.json`、`recovery-runtime/lock.json` 的 pin →
全部通过后才原子替换目标文件。任一环节失败都不会覆盖已有输入，更不会执行归档中的任何内容。

## 当前资产

| 项目 | 值 |
|---|---|
| release tag | `fixed-inputs-20261031`（prerelease，target `main`） |
| release id | `429323356` |
| asset | `dsha-a-su-fixed-inputs-20261031.zip` |
| bytes | `696361527` |
| SHA-256 | `a1225b84d3c147727f27c92bf0dd684ed3be905d0a72f376c98dae7fef085726` |
| 下载直链 | `https://github.com/SunsetRNE/DSHA-Sunset-mod/releases/download/fixed-inputs-20261031/dsha-a-su-fixed-inputs-20261031.zip` |

> 上传状态以 `gh api repos/SunsetRNE/DSHA-Sunset-mod/releases/tags/fixed-inputs-20261031` 返回的资产 `name`/`size`/`state` 为准：
> 只有 `size=696361527` 且 `state=uploaded` 才算上传完成。直链由 tag + 资产文件名确定，不依赖 asset id；
> 2026-10-31 实测：release id `429323356`，asset id `692916456`，上传回执 `state=uploaded`（`digest=sha256:a1225b84d3c147727f27c92bf0dd684ed3be905d0a72f376c98dae7fef085726`）。
> 若后续在同 tag 内 `--clobber` 替换资产，文件名不变但内容与 SHA-256 会变，必须同步更新锁。

## 升级流程（新预发布资产）

1. 输入或 pin 变化后重新打包（STORED，成员集合由 `ci-assets.py` 的 `allowed()` 决定）：

   ```bash
   python3 tools/ci-assets.py --pack /root/DSHA/build-inputs/dsha-a-su-fixed-inputs-YYYYMMDD.zip
   ```

   记录输出的 `sha256` 与 `bytes`；`--pack` 会先按当前 pin 校验 11 项输入，失败则不生成资产。
2. 在公开仓库新建预发布（tag 用新日期，例如 `fixed-inputs-YYYYMMDD`），notes 写明成员清单、bytes、SHA-256 与用途；
   上传 STORED bundle：

   ```bash
   gh release create fixed-inputs-YYYYMMDD --repo SunsetRNE/DSHA-Sunset-mod \
     --title '固定构建输入' --prerelease --notes-file <notes>
   gh release upload fixed-inputs-YYYYMMDD dsha-a-su-fixed-inputs-YYYYMMDD.zip \
     --repo SunsetRNE/DSHA-Sunset-mod
   ```

   大文件上传耗时长，建议后台执行并核对上传后 API 的 `size`。旧资产保留，便于追溯；常规升级不复用旧 tag。
3. 核对上传结果（资产名/大小/状态；asset id 只留作审计，不写进锁）：

   ```bash
   gh api repos/SunsetRNE/DSHA-Sunset-mod/releases/tags/fixed-inputs-YYYYMMDD \
     --jq '.assets[] | {id, name, size, state}'
   ```

4. 更新 [`ci/sunset-inputs.lock.json`](../ci/sunset-inputs.lock.json)：`url` 用确定性直链
   `https://github.com/SunsetRNE/DSHA-Sunset-mod/releases/download/<tag>/<asset>`（由 tag 与文件名确定，不依赖 asset id），
   并更新 `sha256`、`format`（`bundle`）、`release`、`asset`、`bytes`；同步更新
   [`tools/test-sunset-ci-inputs.py`](../tools/test-sunset-ci-inputs.py) 中针对该锁的断言。
5. 验证：`python3 tools/test-sunset-ci-inputs.py` 通过，且用 `ci-assets.py` 实际还原一次（或至少核对资产 `size` 与本地 bundle 一致，
   并抽样下载校验 SHA-256）。`ci-assets.py` 还原时会逐项校验 `runtime-descriptor.json`、`ci-build-inputs.lock.json`、
   `adb-wheels.lock.json`、`recovery-runtime/lock.json` 的 descriptor/输入 pin；pin 需要变化时由对应生成器/评审流程单独处理，
   不能靠改锁迁就。

### 同一 tag 内替换（clobber）

只有修错（例如上传中断留下坏资产、notes 写错）才允许对**同一 tag** 使用 `gh release upload --clobber`；
替换会改变 asset id，必须同步更新锁，并在提交说明里记录替换原因。正常输入升级应使用新 tag + 新日期文件名，
保留旧资产与旧 SHA-256 证据。

## 相关文件

- [`ci/sunset-inputs.lock.json`](../ci/sunset-inputs.lock.json)：当前资产锁。
- [`tools/ci-assets.py`](../tools/ci-assets.py)：打包 / 下载 / 校验 / 还原唯一入口。
- [`tools/sunset-ci-inputs.py`](../tools/sunset-ci-inputs.py)：CI 输入解析（锁与显式成对覆盖）。
- [`tools/test-sunset-ci-inputs.py`](../tools/test-sunset-ci-inputs.py)：锁与解析行为的宿主回归。
- [`tools/test-ci-assets.py`](../tools/test-ci-assets.py)：打包/恢复成员集合与 pin 校验回归。
- [`app/src/main/assets/runtime-descriptor.json`](../app/src/main/assets/runtime-descriptor.json)、[`tools/ci-build-inputs.lock.json`](../tools/ci-build-inputs.lock.json)、
  [`app/src/main/assets/adb-wheels.lock.json`](../app/src/main/assets/adb-wheels.lock.json)、[`tools/recovery-runtime/lock.json`](../tools/recovery-runtime/lock.json)：逐项 pin 来源。
