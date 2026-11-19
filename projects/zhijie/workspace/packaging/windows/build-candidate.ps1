param([string]$TauriCli = "")
$ErrorActionPreference = "Stop"
function Invoke-Checked([string]$File, [string[]]$Arguments) {
    $ErrorActionPreference = "Continue"
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) { throw "命令执行失败：$File；退出码 $LASTEXITCODE" }
}
$workspace = (Resolve-Path -LiteralPath "$PSScriptRoot/../..").Path
if (!$TauriCli) { $TauriCli = "$workspace/target/packaging-tools/node_modules/@tauri-apps/cli/tauri.js" }
$TauriCli = (Resolve-Path -LiteralPath $TauriCli).Path
Push-Location -LiteralPath $workspace
try {
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
} finally { Pop-Location }
Write-Output "未签名候选包位于 target/release/bundle/nsis；未安装、未启动、未签名发布。"
