param(
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{40}$')][string] $Commit,
    [Parameter(Mandatory)][ValidateSet('assemble', 'windows', 'targeted')][string] $Stage,
    [ValidatePattern('^r[0-9]+$')][string] $Attempt = 'r1',
    [string[]] $Tests = @(),
    [ValidateSet('.', 'final2', 'final3', 'final4')][string] $Proofs = '.'
)
$ErrorActionPreference = 'Stop'
$env:PYTHONUTF8 = '1'
$gitShell = 'C:/Program Files/Git/bin'
if (Test-Path -LiteralPath "$gitShell/sh.exe") { $env:Path = $gitShell + ';' + $env:Path }
$workspace = 'F:/DSHA_RESTART'
$source = "$workspace/source/build377-local"
$evidence = "$workspace/artifacts/build377-local"
$prior = "$workspace/artifacts/build375-local/tests"
$output = "$evidence/$Stage-$Attempt"
New-Item -ItemType Directory -Path $output | Out-Null

$head = (git -C $source rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $head -ne $Commit) { throw 'Unexpected source commit' }
git -C $source diff --quiet HEAD --
if ($LASTEXITCODE -ne 0) { throw 'Tracked source has uncommitted changes' }

$rust = if ($Proofs -eq '.') { "$evidence/rust-arm64" } else { "$evidence/$Proofs/rust-arm64" }
$receipt = Get-Content "$rust/rust-receipt.json" -Raw | ConvertFrom-Json
$rustDigest = (Get-FileHash "$rust/libdsha_rs.so").Hash.ToLowerInvariant()
if ($receipt.commit -ne $Commit -or $receipt.artifact.sha256 -ne $rustDigest) {
    throw 'Rust receipt does not match active source and artifact'
}

$gradle = @('--offline', '--no-daemon', '--no-build-cache', '--console=plain', '--max-workers=2',
    '-Pdsha.identity=upstream', '-Pdsha.unsignedPackage=true', '-Pdsha.sourceOnlyTests=false',
    '-Pdsha.rust.mode=prebuilt', "-Pdsha.rust.artifactDir=$rust",
    '-Pkotlin.compiler.execution.strategy=in-process')
if ($Stage -eq 'assemble') {
    $gradle += @(':app:assembleStandardRelease')
} else {
    if ($Stage -eq 'targeted' -and $Tests.Count -eq 0) { throw 'Test selectors are required' }
    if ($Stage -eq 'windows' -and $Tests.Count -gt 0) { throw 'Full Windows suite must use the whole matrix' }
    $priorMatrix = Get-Content "$prior/production-method-matrix-r14.json" -Raw | ConvertFrom-Json
    $matrix = [ordered]@{
        sourceCommit = $Commit
        inheritedExclusionsFrom = "$prior/production-method-matrix-r14.json"
        inheritedExclusionsSha256 = (Get-FileHash "$prior/production-method-matrix-r14.json").Hash.ToLowerInvariant()
        windowsExclusions = $priorMatrix.windowsExclusions
        linuxMethods = $priorMatrix.linuxMethods
    }
    $matrix | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath "$output/method-matrix.json" -Encoding utf8
    $gradle += @('--init-script', "$prior/windows-tests.gradle",
        "-Ddsha.testEvidenceDir=$output", "-Ddsha.localTestMatrix=$output/method-matrix.json",
        "-Ddsha.testCommit=$Commit", '-Ddsha.testForks=2', ':app:testStandardDebugUnitTest', '--rerun')
    if ($Stage -eq 'windows') {
        $gradle += @(':app:lintStandardRelease', '--continue')
    }
    foreach ($filter in $Tests) { $gradle += @('--tests', $filter) }
}

$invocation = [ordered]@{
    commit = $Commit
    tree = (git -C $source rev-parse 'HEAD^{tree}').Trim()
    sourceRuntimeSha256 = (Get-FileHash "$source/app/src/main/assets/dsh-runtime.bin").Hash.ToLowerInvariant()
    rustSha256 = $rustDigest
    rustReceiptSha256 = (Get-FileHash "$rust/rust-receipt.json").Hash.ToLowerInvariant()
    fVolumeAvailableBytes = (Get-Volume -DriveLetter F).SizeRemaining
    startedAt = [DateTimeOffset]::UtcNow.ToString('o')
    gradle = $gradle
}
$invocation | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath "$output/invocation.json" -Encoding utf8
py -3.12 -B "$workspace/artifacts/nov19-merge/build-prep/run_build.py" --worktree $source --log "$output/gradle.log" -- gradle @gradle
$code = $LASTEXITCODE
$invocation['finishedAt'] = [DateTimeOffset]::UtcNow.ToString('o')
$invocation['exitCode'] = $code
$invocation['finishedFVolumeAvailableBytes'] = (Get-Volume -DriveLetter F).SizeRemaining
if ($Stage -ne 'assemble') {
    $junit = "$source/app/build/test-results/testStandardDebugUnitTest"
    if (Test-Path -LiteralPath "$output/method-results.jsonl") {
        Copy-Item -LiteralPath $junit -Destination "$output/junit" -Recurse
    }
    $reports = "$source/app/build/reports"
    if ($Stage -eq 'windows' -and (Test-Path -LiteralPath $reports)) {
        Get-ChildItem $reports -Filter 'lint-results-standardRelease.*' -File |
            Copy-Item -Destination $output
    }
}
$invocation | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath "$output/invocation.json" -Encoding utf8
exit $code
