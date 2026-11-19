param([string]$JavaHome = $env:JAVA_HOME, [ValidateRange(1,65535)][int]$HttpPort = 8080, [ValidateRange(1,65535)][int]$DatabasePort = 15432)
$ErrorActionPreference = "Stop"
$workspace = (Resolve-Path "$PSScriptRoot/../..").Path
Set-Location -LiteralPath $workspace
$runtime = "$workspace/target/local-preview"
New-Item -ItemType Directory -Path $runtime -Force | Out-Null
$classpathFile = "$workspace/control-plane/target/local-preview-classpath.txt"
if (!(Test-Path -LiteralPath $classpathFile)) { throw "请先按 README 生成 Maven 依赖类路径。" }
if (Test-Path -LiteralPath "$runtime/server.pid" -PathType Leaf) {
    $recordedId = [int](Get-Content -LiteralPath "$runtime/server.pid")
    if (Get-Process -Id $recordedId -ErrorAction SilentlyContinue) { throw "本工作区记录的进程仍在运行；请先检查，不得重复启动。" }
}
if ($HttpPort -eq $DatabasePort -or (Get-NetTCPConnection -State Listen -LocalPort $HttpPort,$DatabasePort -ErrorAction SilentlyContinue)) {
    throw "试用端口重复或已占用；请先检查现有服务。"
}
if (!$JavaHome -or !(Test-Path -LiteralPath "$JavaHome/bin/javac.exe" -PathType Leaf)) { throw "请通过 JAVA_HOME 或 -JavaHome 指定 JDK 25。" }
$classpath = "$workspace/control-plane/target/classes;" + (Get-Content -LiteralPath $classpathFile -Raw).Trim()
& "$JavaHome/bin/javac.exe" -encoding UTF-8 -cp $classpath -d $runtime "$PSScriptRoot/LocalPreview.java"
if ($LASTEXITCODE -ne 0) { throw "本地启动入口编译失败" }
$shimOutput = [System.IO.Path]::GetFullPath("$runtime/clean-cmd.exe")
$shimSource = [System.IO.Path]::GetFullPath("$PSScriptRoot/CleanCmd.cs")
& "C:/Windows/Microsoft.NET/Framework64/v4.0.30319/csc.exe" /nologo "/out:$shimOutput" $shimSource
if ($LASTEXITCODE -ne 0) { throw "本机命令行兼容入口编译失败" }
$originalComSpec = $env:ComSpec
$env:ComSpec = "$runtime/clean-cmd.exe"
try {
    if (!(Test-Path -LiteralPath "$runtime/postgres/PG_VERSION")) {
        if (!$env:ZHIJIE_PREVIEW_PASSWORD) {
            $secure = Read-Host "首次试用账号密码（至少 12 位）" -AsSecureString
            $env:ZHIJIE_PREVIEW_PASSWORD = [System.Net.NetworkCredential]::new("", $secure).Password
        }
        if ($env:ZHIJIE_PREVIEW_PASSWORD.Length -lt 12) { throw "首次试用密码至少需要 12 位，未启动数据库。" }
    }
    if (Test-Path -LiteralPath "$runtime/stop") { Remove-Item -LiteralPath "$runtime/stop" }
    $process = Start-Process -FilePath "$JavaHome/bin/java.exe" -ArgumentList @("-cp", "`"$runtime;$classpath`"", "LocalPreview", $HttpPort, $DatabasePort) -WorkingDirectory $workspace -WindowStyle Hidden -PassThru -RedirectStandardOutput "$runtime/server.log" -RedirectStandardError "$runtime/server-error.log"
} finally {
    $env:ComSpec = $originalComSpec
    Remove-Item Env:ZHIJIE_PREVIEW_PASSWORD -ErrorAction SilentlyContinue
}
$process.Id | Set-Content -LiteralPath "$runtime/server.pid"
Write-Output "后端进程：$($process.Id)；日志：$runtime/server.log"
for ($attempt = 0; $attempt -lt 45; $attempt++) {
    if ($process.HasExited) { throw "后端启动失败，请检查 server.log 和 server-error.log" }
    if (Select-String -LiteralPath "$runtime/server.log" -Pattern "LOCAL_PREVIEW_READY" -Quiet) {
        Write-Output "试用环境已就绪：http://127.0.0.1:$HttpPort；账号：trial@example.test"
        return
    }
    Start-Sleep -Seconds 1
}
throw "等待后端就绪超时，请检查日志；进程可能仍在启动。"
