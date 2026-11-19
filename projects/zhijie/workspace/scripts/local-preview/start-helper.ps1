param(
    [Parameter(Mandatory = $true)][ValidateRange(1, 2147483647)][int]$ClientProcessId,
    [string]$HelperExe = "",
    [string]$ControlPlaneUrl = "http://127.0.0.1:8080",
    [string]$Socket = "\\.\pipe\zhijie-helper-v1",
    [switch]$CheckOnly
)
$ErrorActionPreference = "Stop"
if (!$HelperExe) {
    if (Test-Path -LiteralPath "$PSScriptRoot/zhijie-helper.exe" -PathType Leaf) {
        $HelperExe = "$PSScriptRoot/zhijie-helper.exe"
    } else {
        $workspace = (Resolve-Path -LiteralPath "$PSScriptRoot/../..").Path
        $HelperExe = "$workspace/target/debug/zhijie-helper.exe"
    }
}
$helperPath = (Resolve-Path -LiteralPath $HelperExe).Path
if ([System.IO.Path]::GetFileName($helperPath) -ne "zhijie-helper.exe") {
    throw "必须使用配套的 zhijie-helper.exe。"
}
$client = Get-Process -Id $ClientProcessId -ErrorAction Stop
$clientPath = $client.Path
if (!$clientPath -or [System.IO.Path]::GetFileName($clientPath) -ne "zhijie-shell.exe") {
    throw "指定进程不是可查询的织界桌面。"
}
$clientPath = (Resolve-Path -LiteralPath $clientPath).Path
if ([System.IO.Path]::GetDirectoryName($clientPath) -ne [System.IO.Path]::GetDirectoryName($helperPath)) {
    throw "桌面与 Helper 必须来自同一制品目录；不能跨目录替换。"
}
foreach ($value in @($helperPath, $Socket, $ControlPlaneUrl)) {
    if (!$value -or $value -match '["\r\n]') { throw "启动参数格式无效。" }
}
if ($Socket -notmatch '^\\\\\.\\pipe\\[^\\/]+$') { throw "仅支持本机命名管道。" }
if ($ControlPlaneUrl -match '\\') { throw "控制面地址不能包含反斜杠。" }
$launchArguments = @("--client-pid", [string]$ClientProcessId, "--socket", $Socket,
    "--control-plane-url", $ControlPlaneUrl)
$principal = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
$isAdministrator = $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (!$CheckOnly -or $isAdministrator) {
    $policyModule = Join-Path $PSScriptRoot "HelperInstallPolicy.psm1"
    Import-Module -Name $policyModule -Force -ErrorAction Stop
    Assert-ProtectedHelperDirectory -Directory ([System.IO.Path]::GetDirectoryName($helperPath))
}
& $helperPath @launchArguments "--check-startup"
if ($LASTEXITCODE -ne 0) { throw "Helper 启动前检查失败，未启动网络服务。" }
if ($CheckOnly) {
    Write-Output "启动参数与绑定进程检查通过；未监听管道、未创建网卡。"
    return
}
if (!$isAdministrator) {
    throw "实际启动需由你在管理员 PowerShell 执行；不要以管理员运行整个桌面。可先用 -CheckOnly。"
}
$existing = @(Get-Process -Name "zhijie-helper" -ErrorAction SilentlyContinue)
if ($existing.Count -gt 0) { throw "已有 Helper 进程；请先正常关闭原绑定桌面并确认清理完成。" }
# Start-Process 使用单个参数字符串；上述检查拒绝引号和换行，路径不经过命令解释器。
$argumentLine = ($launchArguments | ForEach-Object { '"' + $_ + '"' }) -join ' '
$process = Start-Process -FilePath $helperPath -ArgumentList $argumentLine `
    -WorkingDirectory ([System.IO.Path]::GetDirectoryName($helperPath)) -WindowStyle Hidden -PassThru
Start-Sleep -Milliseconds 500
if ($process.HasExited) { throw "Helper 启动后已退出，退出码 $($process.ExitCode)。" }
Write-Output "Helper 进程 $($process.Id) 已启动，绑定桌面 $ClientProcessId；实际网络连接仍以桌面状态为准。关闭绑定桌面后 Helper 会清理退出。"
