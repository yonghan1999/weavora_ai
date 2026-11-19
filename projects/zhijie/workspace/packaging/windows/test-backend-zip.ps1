param([Parameter(Mandatory)][string]$Package, [string]$JavaHome = $env:JAVA_HOME,
      [int]$HttpPort = 18831, [int]$DatabasePort = 15441)
$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path -LiteralPath "$PSScriptRoot/../..").Path
$target = (Resolve-Path -LiteralPath "$workspace/target").Path
if ([IO.Path]::GetPathRoot($target).TrimEnd('\') -eq $env:SystemDrive) { throw '测试目录不得位于系统盘' }
$archive = (Resolve-Path -LiteralPath $Package).Path
$name = "backend zip test $([guid]::NewGuid().ToString('N'))"
$trial = Join-Path $target $name
if (Test-Path -LiteralPath $trial) { throw '回归目录已存在' }
$password = [guid]::NewGuid().ToString('N')
$savedPassword = $env:ZHIJIE_PREVIEW_PASSWORD
$savedMail = $env:ZHIJIE_MAIL_ENABLED
$savedVoice = $env:ZHIJIE_VOICE_ENABLED
$url = "http://127.0.0.1:$HttpPort"
Push-Location -LiteralPath $workspace
try {
    & python -B -m zipfile -e $archive $trial
    if ($LASTEXITCODE) { throw '试用包解压失败' }
    $dependencies = (Get-Content -LiteralPath "$trial/control-plane/target/local-preview-classpath.txt" -Raw).Trim().Split(';')
    foreach ($dependency in $dependencies) {
        if ($dependency -notmatch '^lib/[^/\\:;]+\.jar$' -or !(Test-Path -LiteralPath "$trial/$dependency" -PathType Leaf)) { throw '依赖未随包提供' }
    }
    $env:ZHIJIE_MAIL_ENABLED = 'false'
    $env:ZHIJIE_VOICE_ENABLED = 'false'
    $env:ZHIJIE_PREVIEW_PASSWORD = $password
    $snapshots = foreach ($attempt in 1,2) {
        & "$trial/scripts/local-preview/start.ps1" -JavaHome $JavaHome -HttpPort $HttpPort -DatabasePort $DatabasePort | Out-Host
        if ((Invoke-RestMethod "$url/actuator/health").status -ne 'UP') { throw '试用后端未就绪' }
        $login = Invoke-RestMethod "$url/api/v1/auth/login" -Method Post -ContentType 'application/json' -Body (@{email='trial@example.test';password=$password;deviceId='relocation-test'} | ConvertTo-Json)
        $headers = @{Authorization=('Bearer ' + $login.data.accessToken)}
        $groups = (Invoke-RestMethod "$url/api/v1/groups" -Headers $headers).data
        if ($groups.Count -ne 1) { throw '群数量不符' }
        $rooms = (Invoke-RestMethod "$url/api/v1/groups/$($groups[0].id)/rooms" -Headers $headers).data
        $messages = (Invoke-RestMethod "$url/api/v1/groups/$($groups[0].id)/messages" -Headers $headers).data
        if ($rooms.Count -ne 1 -or $messages.Count -ne 1) { throw '房间或消息数量不符' }
        "$($groups[0].id):$($rooms[0].id):$($messages[0].id)"
        & "$trial/scripts/local-preview/stop.ps1" | Out-Host
    }
    if ($snapshots.Count -ne 2 -or $snapshots[0] -ne $snapshots[1]) { throw '重启后数据改变' }
} finally {
    try {
        if (Test-Path -LiteralPath "$trial/target/local-preview/server.pid") { & "$trial/scripts/local-preview/stop.ps1" | Out-Host }
    } finally {
        Pop-Location
        $env:ZHIJIE_PREVIEW_PASSWORD = $savedPassword
        $env:ZHIJIE_MAIL_ENABLED = $savedMail
        $env:ZHIJIE_VOICE_ENABLED = $savedVoice
    }
}
$resolved = (Resolve-Path -LiteralPath $trial).Path
if ((Split-Path -Parent $resolved) -ne $target -or (Split-Path -Leaf $resolved) -ne $name -or
    ((Get-Item -LiteralPath $resolved).Attributes -band [IO.FileAttributes]::ReparsePoint) -or
    (Get-ChildItem -LiteralPath $resolved -Recurse -Force | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint })) {
    throw '不清理路径异常的测试目录'
}
Remove-Item -LiteralPath $resolved -Recurse -Force
Write-Output 'RELOCATED_PACKAGE_LOGIN_HISTORY_RESTART_OK'
