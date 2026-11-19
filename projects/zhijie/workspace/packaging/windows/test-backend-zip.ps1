param([Parameter(Mandatory)][string]$Package, [string]$JavaHome = $env:JAVA_HOME,
      [int]$HttpPort = 18831, [int]$DatabasePort = 15441, [string]$PreviousPackage)
$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path -LiteralPath "$PSScriptRoot/../..").Path
$target = (Resolve-Path -LiteralPath "$workspace/target").Path
if ([IO.Path]::GetPathRoot($target).TrimEnd('\') -eq $env:SystemDrive) { throw '测试目录不得位于系统盘' }
$archive = (Resolve-Path -LiteralPath $Package).Path
$name = "backend zip test $([guid]::NewGuid().ToString('N'))"
$fixture = Join-Path $target $name
if (Test-Path -LiteralPath $fixture) { throw '回归目录已存在' }
$previous = if ($PreviousPackage) { (Resolve-Path -LiteralPath $PreviousPackage).Path }
$trial = Join-Path $fixture 'current'
$current = $trial
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
    if ($previous) {
        $trial = Join-Path $fixture 'previous'
        & python -B -m zipfile -e $previous $trial
        if ($LASTEXITCODE) { throw '旧试用包解压失败' }
    }
    $env:ZHIJIE_MAIL_ENABLED = 'false'
    $env:ZHIJIE_VOICE_ENABLED = 'false'
    $env:ZHIJIE_PREVIEW_PASSWORD = $password
    $marker = [guid]::NewGuid().ToString('N')
    $caller = (Get-Location).Path
    $portRejected = $false
    try {
        & "$current/scripts/local-preview/start.ps1" -JavaHome $JavaHome -HttpPort $HttpPort -DatabasePort $HttpPort | Out-Host
    } catch {
        if ($_.Exception.Message -notlike '*试用端口重复或已占用*') { throw }
        $portRejected = $true
    }
    if (!$portRejected -or (Get-Location).Path -ne $caller) { throw '拒绝重复端口后未保留调用者目录' }
    $snapshots = foreach ($attempt in 1,2) {
        & "$trial/scripts/local-preview/start.ps1" -JavaHome $JavaHome -HttpPort $HttpPort -DatabasePort $DatabasePort | Out-Host
        if ($trial -eq $current -and (Get-Location).Path -ne $caller) { throw '启动后未保留调用者目录' }
        if ((Invoke-RestMethod "$url/actuator/health").status -ne 'UP') { throw '试用后端未就绪' }
        $login = Invoke-RestMethod "$url/api/v1/auth/login" -Method Post -ContentType 'application/json' -Body (@{email='trial@example.test';password=$password;deviceId='relocation-test'} | ConvertTo-Json)
        $headers = @{Authorization=('Bearer ' + $login.data.accessToken)}
        $groups = (Invoke-RestMethod "$url/api/v1/groups" -Headers $headers).data
        if ($groups.Count -ne 1) { throw '群数量不符' }
        $rooms = (Invoke-RestMethod "$url/api/v1/groups/$($groups[0].id)/rooms" -Headers $headers).data
        $history = "$url/api/v1/groups/$($groups[0].id)/messages"
        if ($attempt -eq 1) {
            $sent = (Invoke-RestMethod $history -Headers $headers -Method Post -ContentType 'application/json' -Body (@{body=$marker} | ConvertTo-Json)).data
        }
        $messages = (Invoke-RestMethod $history -Headers $headers).data
        if ($rooms.Count -ne 1 -or $messages.Count -ne 2 -or !($messages | Where-Object { $_.id -eq $sent.id -and $_.body -eq $marker })) { throw '用户消息、欢迎消息或房间缺失' }
        "$($groups[0].id):$($rooms[0].id):$($messages.id -join ':')"
        if ($attempt -eq 2) {
            $removed = (Invoke-RestMethod "$url/api/v1/messages/$($sent.id)" -Headers $headers -Method Delete).data
            if (!$removed.deleted -or $removed.deleteKind -ne 'AUTHOR' -or $removed.id -ne $sent.id) { throw '恢复后删除本人消息失败' }
        }
        & "$trial/scripts/local-preview/stop.ps1" | Out-Host
        if ($trial -eq $current -and (Get-Location).Path -ne $caller) { throw '停止后未保留调用者目录' }
        if ($previous -and $attempt -eq 1) {
            Set-Location -LiteralPath $caller
            $backup = Join-Path $fixture 'snapshot'
            & python -B "$workspace/ops/database/cold-backup.py" snapshot --source "$trial/target/local-preview/postgres" --destination $backup | Out-Host
            if ($LASTEXITCODE) { throw '旧包停库快照失败' }
            & python -B "$workspace/ops/database/cold-backup.py" verify --backup $backup --require-directories | Out-Host
            if ($LASTEXITCODE) { throw '快照核验失败' }
            if (Test-Path -LiteralPath "$current/target/local-preview/postgres") { throw '新版测试目录已存在数据库，拒绝覆盖' }
            $runtime = New-Item -ItemType Directory -Path "$current/target/local-preview" -Force
            Copy-Item -LiteralPath "$backup/data" -Destination "$($runtime.FullName)/postgres" -Recurse -ErrorAction Stop
            $trial = $current
        }
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
$resolved = (Resolve-Path -LiteralPath $fixture).Path
if ((Split-Path -Parent $resolved) -ne $target -or (Split-Path -Leaf $resolved) -ne $name -or
    ((Get-Item -LiteralPath $resolved).Attributes -band [IO.FileAttributes]::ReparsePoint) -or
    (Get-ChildItem -LiteralPath $resolved -Recurse -Force | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint })) {
    throw '不清理路径异常的测试目录'
}
Remove-Item -LiteralPath $resolved -Recurse -Force
Write-Output 'RELOCATED_PACKAGE_LOGIN_HISTORY_RESTART_OK'
if ($previous) { Write-Output 'BACKEND_PACKAGE_UPGRADE_USER_DATA_OK' }
