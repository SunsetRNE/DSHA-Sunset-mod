# build370 本机固定输入

在最终 build370 提交的干净 Git 检出中运行。需要 Python 3.11、Node.js 24.9 或以上版本和 npm 11。本机验证使用 Python 3.11.9、zlib 1.3.1、Node.js 24.18.0 和 npm 11.16.0。

`ci/sunset-inputs.lock.json` 固定了公开的 `fixed-inputs-20261113`。其中的 DSH 为 `0.2.2-rc.1`。本机准备流程核对上游 ZIP 的大小和 SHA-256，提取原始 Ubuntu rootfs 和工具，再用当前 Git 提交的 npm 锁执行 Linux ARM64/glibc `npm ci`。仓库构建器根据实际安装树生成 `0.2.2-rc.5` 归档。正式和应急 DSH 使用同一份归档字节。

重建的 DSH 必须同时符合 `dsh-runtime.inputs.json`、`runtime-descriptor.json` 和 `tools/recovery-runtime/lock.json`。固定 ZIP 保留原始 Ubuntu rootfs 字节，并用正式 APK 的生成逻辑派生应急 XZ rootfs，核对其应急锁摘要。脚本复用仓库的固定输入打包与校验逻辑，生成后逐成员读回。构建和校验都在临时目录中执行，源码只读。固定 ZIP 含白名单内的构建输入，正式和应急运行时的描述文件由对应 Git 提交提供。

## 生成与核验

从源码检出根目录运行。输出路径指向检出外的新文件，标准输出为带 SHA-256 的 JSON 回执。

```powershell
python tools/prepare-local-fixed-inputs.py --output ../build370-fixed-inputs.zip |
    Set-Content -LiteralPath ../build370-fixed-inputs.json -Encoding utf8
if ($LASTEXITCODE -ne 0) { throw 'build370 fixed-input preparation failed' }

$zipHash = (Get-Content -LiteralPath ../build370-fixed-inputs.json -Raw | ConvertFrom-Json).sha256
python tools/prepare-local-fixed-inputs.py --verify ../build370-fixed-inputs.zip --sha256 $zipHash
```

可用 `--commit <完整提交SHA>` 绑定指定版本。已有上游 ZIP 时可传入 `--base-bundle <路径>`。已有 npm 缓存时可传入 `--npm-cache <路径>`。上游 ZIP 和缓存均可用时还可传入 `--offline`。

## 将固定输入还原到同一提交的检出

```powershell
python tools/ci-assets.py --archive ../build370-fixed-inputs.zip --sha256 $zipHash
python tools/prepare-runtime-descriptor.py --check
```

本机固定输入 ZIP 面向 build370 的本地交付。公开上游的原 ZIP 继续供其对应版本使用。
