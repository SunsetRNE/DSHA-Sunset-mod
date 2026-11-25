# 在 app/build/tmp 的假 SDK 中测试 ARM AIDL 替身，不修改真实 Android SDK。
$ErrorActionPreference = 'Stop'
$name = 'aidl-shim-fixture-' + [guid]::NewGuid().ToString('N')
$parent = Join-Path (Get-Location) 'app\build\tmp'
$fixture = Join-Path $parent $name
$python = (Get-Command python -CommandType Application | Select-Object -First 1).Source
$lockedVersion = & $python 'tools/ci-toolchain.py' --field buildTools
if ($LASTEXITCODE -ne 0 -or $lockedVersion -notmatch '^\d+\.\d+\.\d+$') { throw '无法取得锁定的 build-tools 版本' }
$sdkTools = Join-Path $fixture "sdk\build-tools\$lockedVersion"
$newerTools = Join-Path $fixture 'sdk\build-tools\999.0.0'
$aidl = Join-Path $sdkTools 'aidl'
$backup = $aidl + '.x86_64.disabled'
$bash = 'C:\Program Files\Git\bin\bash.exe'
New-Item -ItemType Directory -Force -Path $sdkTools, $newerTools | Out-Null
$env:DSHA_AIDL_FIXTURE_NAME = $name
$env:DSHA_AIDL_FIXTURE_PYTHON = $python.Replace('\', '/')
$env:DSHA_AIDL_FIXTURE_BUILD_TOOLS = $lockedVersion
try {
    $script = Get-Content -LiteralPath 'build.sh' -Raw
    $start = $script.IndexOf('aidl_build_tools_dir() {')
    if ($start -lt 0) { throw '无法找到 build.sh 的 build-tools 选择函数' }
    $shim = $script.IndexOf('AIDL_SHIM_BIN=""', $start)
    $end = $script.IndexOf('if [ -z "${DSHA_FORCE_AIDL:-}" ]; then', $start)
    if ($shim -le $start -or $end -le $shim) { throw '无法完整提取 build.sh 中的 AIDL 函数' }
    Set-Content -LiteralPath (Join-Path $fixture 'functions.sh') -Value $script.Substring($start, $end - $start) -Encoding utf8
    $original = "#!/usr/bin/env bash`necho original-aidl`n"
    Set-Content -LiteralPath $aidl -Value $original -NoNewline -Encoding utf8
    $newerAidl = Join-Path $newerTools 'aidl'
    Set-Content -LiteralPath $newerAidl -Value 'newer but not selected' -NoNewline -Encoding utf8
    $runner = 'set -euo pipefail; ROOT="$(pwd)"; SDK="$ROOT/app/build/tmp/$DSHA_AIDL_FIXTURE_NAME/sdk"; DSHA_PYTHON="$DSHA_AIDL_FIXTURE_PYTHON"; source "$ROOT/app/build/tmp/$DSHA_AIDL_FIXTURE_NAME/functions.sh"; declare -F aidl_build_tools_dir restore_aidl_on_exit install_aidl_shim >/dev/null; bt="$SDK/build-tools/$DSHA_AIDL_FIXTURE_BUILD_TOOLS"; test "$(aidl_build_tools_dir)" = "$bt"; install_aidl_shim; test -f "$bt/aidl.x86_64.disabled"; head -2 "$bt/aidl" | grep -q "由 build.sh 生成的 aidl 替身"'

    & $bash -c $runner
    if ($LASTEXITCODE -ne 0) { throw '首次安装替身失败' }
    if ((Get-Content -LiteralPath $aidl -Raw) -ne $original -or (Test-Path -LiteralPath $backup)) {
        throw '正常退出没有恢复原始 aidl'
    }

    # Simulate a write/interruption window after the original has moved away.
    & $bash -c ($runner + '; rm "$bt/aidl"; exit 7') 2>$null
    if ($LASTEXITCODE -ne 7 -or (Get-Content -LiteralPath $aidl -Raw) -ne $original -or
            (Test-Path -LiteralPath $backup)) {
        throw '替身文件缺失时没有恢复原始 aidl'
    }

    Move-Item -LiteralPath $aidl -Destination $backup
    Set-Content -LiteralPath $aidl -Value "#!/usr/bin/env bash`n# 由 build.sh 生成的 aidl 替身`n" -NoNewline -Encoding utf8
    & $bash -c $runner
    if ($LASTEXITCODE -ne 0) { throw '中断现场的在位替身恢复失败' }
    if ((Get-Content -LiteralPath $aidl -Raw) -ne $original -or (Test-Path -LiteralPath $backup)) {
        throw '中断现场退出后没有恢复原始 aidl'
    }

    Set-Content -LiteralPath $backup -Value 'unrelated backup' -NoNewline -Encoding utf8
    & $bash -c $runner 2>$null
    if ($LASTEXITCODE -eq 0 -or (Get-Content -LiteralPath $aidl -Raw) -ne $original -or
            (Get-Content -LiteralPath $backup -Raw) -ne 'unrelated backup') {
        throw '未知原件冲突没有拒绝并保留现场'
    }
    if ((Get-Content -LiteralPath $newerAidl -Raw) -ne 'newer but not selected' -or
            (Test-Path -LiteralPath ($newerAidl + '.x86_64.disabled'))) {
        throw '测试期间修改了非锁定 build-tools 的 AIDL'
    }
    Write-Output 'AIDL shim restore fixture: PASS'
} finally {
    Remove-Item Env:DSHA_AIDL_FIXTURE_NAME, Env:DSHA_AIDL_FIXTURE_PYTHON, Env:DSHA_AIDL_FIXTURE_BUILD_TOOLS -ErrorAction SilentlyContinue
    $allowed = [IO.Path]::GetFullPath($parent).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    $resolved = [IO.Path]::GetFullPath($fixture)
    if (-not $resolved.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase) -or
            -not (Split-Path -Leaf $resolved).StartsWith('aidl-shim-fixture-')) {
        throw '拒绝清理工作区外的 AIDL 夹具'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
