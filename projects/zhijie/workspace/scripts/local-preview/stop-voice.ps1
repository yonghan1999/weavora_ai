$ErrorActionPreference = "Stop"
$workspace = (Resolve-Path "$PSScriptRoot/../..").Path
$runtime = "$workspace/target/local-preview/livekit"
if (!(Test-Path -LiteralPath "$runtime/server.pid")) { Write-Output "未记录本机 LiveKit 进程。"; return }
$voiceProcessId = [int](Get-Content -LiteralPath "$runtime/server.pid")
$process = Get-Process -Id $voiceProcessId -ErrorAction SilentlyContinue
if (!$process) { Write-Output "本机 LiveKit 已停止。"; return }
$command = (Get-CimInstance Win32_Process -Filter "ProcessId=$voiceProcessId").CommandLine
$configPath = [System.IO.Path]::GetFullPath("$runtime/local.yaml")
$configPattern = '\s--config\s+"' + [regex]::Escape($configPath) + '"\s*$'
if ($process.ProcessName -ne "livekit-server" -or !$command -or $command.Replace('/', '\') -notmatch $configPattern) {
    throw "无法确认该进程使用本工作区的 LiveKit 配置，未停止。"
}
$process.Kill()
if (!$process.WaitForExit(10000)) { throw "等待本机 LiveKit 退出超时。" }
Remove-Item -LiteralPath "$runtime/server.pid"
Write-Output "本机 LiveKit 已停止。"
