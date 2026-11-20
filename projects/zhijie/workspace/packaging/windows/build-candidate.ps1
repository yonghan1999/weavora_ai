param([string]$TauriCli = "")
$ErrorActionPreference = "Stop"
function Invoke-Checked([string]$File, [string[]]$Arguments) {
    $ErrorActionPreference = "Continue"
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) { throw "命令执行失败：$File；退出码 $LASTEXITCODE" }
}
$workspace = (Resolve-Path -LiteralPath "$PSScriptRoot/../..").Path
if ([IO.Path]::GetPathRoot($workspace).TrimEnd('\') -ne "D:") {
    throw "请将候选构建工作区放在 D 盘，避免写满系统盘。"
}
$target = "$workspace/target"
if ((Test-Path -LiteralPath "$target") -and ((Get-Item -LiteralPath "$target").Attributes -band [IO.FileAttributes]::ReparsePoint)) {
    throw "构建输出目录不得重定向到其他位置。"
}
if (!$TauriCli) { $TauriCli = "$workspace/target/packaging-tools/node_modules/@tauri-apps/cli/tauri.js" }
$TauriCli = (Resolve-Path -LiteralPath "$TauriCli").Path
$variables = @("TEMP", "TMP", "TMPDIR", "CARGO_HOME", "CARGO_TARGET_DIR", "RUSTUP_AUTO_INSTALL", "npm_config_cache", "PYTHONDONTWRITEBYTECODE")
$previous = @{}
foreach ($name in $variables) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, "Process") }
$temporary = "$target/candidate-temp-$([guid]::NewGuid().ToString('N'))"
Push-Location -LiteralPath $workspace
try {
    $directories = @("$temporary", "$target/local-native", "$target/local-native/cargo", "$target/npm-cache")
    foreach ($directory in $directories) {
        if ((Test-Path -LiteralPath "$directory") -and ((Get-Item -LiteralPath "$directory").Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "缓存目录不得重定向到其他位置：$directory"
        }
        New-Item -ItemType Directory -Path "$directory" -Force | Out-Null
    }
    $env:TEMP = $temporary
    $env:TMP = $temporary
    $env:TMPDIR = $temporary
    $env:CARGO_HOME = "$target/local-native/cargo"
    $env:CARGO_TARGET_DIR = $target
    $env:RUSTUP_AUTO_INSTALL = "0"
    $env:npm_config_cache = "$target/npm-cache"
    $env:PYTHONDONTWRITEBYTECODE = "1"
    Invoke-Checked "node" @("scripts/test-release.mjs", "--contract")
    Push-Location -LiteralPath "$workspace/desktop/ui"
    try { Invoke-Checked "node" @("scripts/build.mjs") } finally { Pop-Location }
    Invoke-Checked "cargo" @("build", "--release", "--offline", "--locked", "-p", "zhijie-helper")
    Invoke-Checked "python" @("$PSScriptRoot/prepare-wintun.py", "--output-directory", "$workspace/target/windows-bundle")
    Copy-Item -LiteralPath "$workspace/target/release/zhijie-helper.exe" -Destination "$workspace/target/windows-bundle/zhijie-helper.exe" -Force
    Push-Location -LiteralPath "$workspace/desktop/shell"
    try {
        Invoke-Checked "node" @($TauriCli, "build", "--bundles", "nsis", "--ci", "--no-sign", "--no-binary-patching", "--", "--offline", "--locked")
    } finally { Pop-Location }
    $app = Get-Content -LiteralPath "$workspace/desktop/shell/tauri.conf.json" -Raw -Encoding UTF8 | ConvertFrom-Json
    $installer = "$workspace/target/release/bundle/nsis/$($app.productName)_$($app.version)_x64-setup.exe"
    Invoke-Checked "python" @("$PSScriptRoot/verify-package.py", $installer, "--binaries", "$workspace/target/release", "--scripts", "$workspace/scripts/local-preview", "--driver", "$workspace/target/windows-bundle")
} finally {
    foreach ($name in $variables) { [Environment]::SetEnvironmentVariable($name, $previous[$name], "Process") }
    Pop-Location
    if (Test-Path -LiteralPath "$temporary") {
        $resolved = (Resolve-Path -LiteralPath "$temporary").Path
        if ([IO.Path]::GetFullPath($temporary) -ne $resolved -or (Get-Item -LiteralPath "$resolved").Attributes -band [IO.FileAttributes]::ReparsePoint) {
            Write-Warning "临时目录路径变化，未清理：$temporary"
        } else {
            Remove-Item -LiteralPath "$resolved" -Recurse -Force -ErrorAction Continue
        }
    }
}
Write-Output "未签名候选包位于 target/release/bundle/nsis；未安装、未启动、未签名发布。"
