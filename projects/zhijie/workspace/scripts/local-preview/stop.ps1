$ErrorActionPreference = "Stop"
$workspace = (Resolve-Path "$PSScriptRoot/../..").Path
$runtime = "$workspace/target/local-preview"
if (!(Test-Path -LiteralPath "$runtime/server.pid" -PathType Leaf)) { Write-Output "没有本工作区的试用进程记录"; return }
$previewProcessId = [int](Get-Content -LiteralPath "$runtime/server.pid")
$process = Get-Process -Id $previewProcessId -ErrorAction SilentlyContinue
if (!$process) { Write-Output "本地后端已停止"; return }
$command = (Get-CimInstance Win32_Process -Filter "ProcessId=$previewProcessId").CommandLine
$runtimePath = [System.IO.Path]::GetFullPath($runtime)
$classpathPattern = '\s-cp\s+"' + [regex]::Escape($runtimePath) + ';'
if ($process.ProcessName -ne "java" -or !$command -or $command.Replace('/', '\') -notmatch $classpathPattern -or $command -notmatch '\sLocalPreview(?:\s+\d+\s+\d+)?\s*$') {
    throw "无法确认该进程是本工作区的试用服务，未执行停止操作"
}
New-Item -ItemType File -Path "$runtime/stop" -Force | Out-Null
if (!$process.WaitForExit(20000)) { throw "等待退出超时，请检查日志；未强制终止进程" }
Write-Output "本地后端与数据库已停止，试用数据已保留"
