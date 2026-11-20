param([Parameter(Mandatory)][string]$Package, [string]$JavaHome = $env:JAVA_HOME,
      [int]$HttpPort = 18831, [int]$DatabasePort = 15441, [string]$PreviousPackage, [switch]$CheckSyncAuth, [switch]$CheckRoomReopen,
      [switch]$CheckLogPrivacy, [string]$ControlTlsNginx, [string]$ControlTlsRustProbe)
$ErrorActionPreference = 'Stop'
if ($ControlTlsRustProbe -and !$ControlTlsNginx) { throw 'Rust TLS 检查需要指定 nginx' }
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
function Assert-NoCredential($Text, $Values, $Source) {
    foreach ($value in $Values) {
        if ([string]::IsNullOrEmpty($value)) { throw 'Missing privacy test credential' }
        if ($Text.Contains($value)) { throw "Credential found in $Source" }
    }
}
function Assert-RoomReopen($Room, $Headers) {
    $endpoint = "$url/api/v1/rooms/$($Room.id)"
    $join = @{roomId=$Room.id;deviceId='relocation-test';operationId=[guid]::NewGuid().ToString('N')} | ConvertTo-Json
    $lease = (Invoke-RestMethod "$url/api/v1/room-joins" -Method Post -Headers $Headers -ContentType 'application/json' -Body $join).data
    if (!$lease.leaseId -or $lease.activityId -ne $Room.currentActivityId) { throw '试用账号未加入原活动' }
    $paused = (Invoke-RestMethod "$endpoint/pause" -Method Post -Headers $Headers).data
    if ($paused.openState -ne 'PAUSED') { throw '暂停房间失败' }
    Add-Type -AssemblyName System.Net.Http
    $client = [Net.Http.HttpClient]::new()
    try {
        $client.DefaultRequestHeaders.Add('Authorization', $Headers.Authorization)
        $response = $client.PostAsync("$endpoint/open", $null).GetAwaiter().GetResult()
        try {
            $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
            if ([int]$response.StatusCode -ne 409 -or $body.error.code -ne 'ROOM_REOPEN_OUTSIDE_REQUIRED') { throw '未明确拒绝房内重新开放' }
        } finally { $response.Dispose() }
    } finally { $client.Dispose() }
    $ended = (Invoke-RestMethod "$url/api/v1/activities/$($lease.activityId)/end" -Method Post -Headers $Headers).data
    $outside = (Invoke-RestMethod $endpoint -Headers $Headers).data
    if (!$ended.ended -or $outside.openState -ne 'PAUSED' -or $outside.currentActivityId) { throw '结束活动未保留暂停状态' }
    $null = Invoke-RestMethod "$endpoint/open" -Method Post -Headers $Headers
    $reopened = (Invoke-RestMethod $endpoint -Headers $Headers).data
    if ($reopened.id -ne $Room.id -or $reopened.openState -ne 'OPEN' -or $reopened.currentActivityId) { throw '重新开放未持久化或意外创建活动' }
    Write-Host 'PACKAGED_ROOM_REOPEN_OUTSIDE_WITHOUT_START_OK'
}
function Assert-DeleteEvent($Socket, $Mode, $MessageId, $GroupId, $Cursor, $Cancellation) {
    $buffer = New-Object byte[] 4096
    while (!$Cancellation.IsCancellationRequested) {
        $frame = [IO.MemoryStream]::new()
        try {
            do {
                $part = $Socket.ReceiveAsync([ArraySegment[byte]]::new($buffer), $Cancellation).GetAwaiter().GetResult()
                if ($part.MessageType -eq 'Close') {
                    $reason = if ($part.CloseStatusDescription -in 'RESYNC_REQUIRED','ACCESS_TOKEN_INVALID') { $part.CloseStatusDescription } else { 'unspecified' }
                    throw "Loopback sync closed before delivery: mode=$Mode code=$([int]$part.CloseStatus) reason=$reason"
                }
                if ($part.MessageType -ne 'Text' -or $frame.Length + $part.Count -gt 65536) { throw 'Invalid sync event' }
                $frame.Write($buffer, 0, $part.Count)
            } until ($part.EndOfMessage)
            $event = [Text.Encoding]::UTF8.GetString($frame.ToArray()) | ConvertFrom-Json
            if ($event.type -eq 'message.deleted' -and $event.groupId -eq $GroupId -and $event.sequence -gt $Cursor -and
                ($event.payload | ConvertFrom-Json).message_id -eq $MessageId) { return }
        } finally { $frame.Dispose() }
    }
    throw 'Expected sync event not received'
}
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
    $upgradeMarker = [guid]::NewGuid().ToString('N')
    $secrets = @($password)
    $caller = (Get-Location).Path
    $portRejected = $false
    try {
        & "$current/scripts/local-preview/start.ps1" -JavaHome $JavaHome -HttpPort $HttpPort -DatabasePort $HttpPort | Out-Host
    } catch {
        if ($_.Exception.Message -notlike '*试用端口重复或已占用*') { throw }
        $portRejected = $true
    }
    if (!$portRejected -or (Get-Location).Path -ne $caller) { throw '拒绝重复端口后未保留调用者目录' }
    $attemptCount = if ($previous) { 4 } else { 2 }
    $snapshots = foreach ($attempt in 1..$attemptCount) {
        & "$trial/scripts/local-preview/start.ps1" -JavaHome $JavaHome -HttpPort $HttpPort -DatabasePort $DatabasePort | Out-Host
        if ($trial -eq $current -and (Get-Location).Path -ne $caller) { throw '启动后未保留调用者目录' }
        if ((Invoke-RestMethod "$url/actuator/health").status -ne 'UP') { throw '试用后端未就绪' }
        $login = Invoke-RestMethod "$url/api/v1/auth/login" -Method Post -ContentType 'application/json' -Body (@{email='trial@example.test';password=$password;deviceId='relocation-test'} | ConvertTo-Json)
        $headers = @{Authorization=('Bearer ' + $login.data.accessToken)}
        if ($CheckLogPrivacy) { $secrets += @($login.data.accessToken, $login.data.refreshToken) }
        $groups = (Invoke-RestMethod "$url/api/v1/groups" -Headers $headers).data
        if ($groups.Count -ne 1) { throw '群数量不符' }
        $rooms = (Invoke-RestMethod "$url/api/v1/groups/$($groups[0].id)/rooms" -Headers $headers).data
        if ($rooms.Count -ne 1) { throw '预期的唯一试用房间不存在' }
        if ($previous -and $attempt -eq 1) {
            $room = $rooms[0]
            $originalRoomName = 'Upgrade-' + $marker
            $oldRoomPassword = [guid]::NewGuid().ToString('N')
            $secrets += $oldRoomPassword
            $settings = @{name=$originalRoomName;description=$room.description;gameName=$room.gameName;password=$oldRoomPassword} | ConvertTo-Json
            $configured = (Invoke-RestMethod "$url/api/v1/rooms/$($room.id)" -Method Patch -Headers $headers -ContentType 'application/json' -Body $settings).data
            if ($configured.passwordProtected -ne $true -or $configured.name -ne $originalRoomName) { throw '旧包设置密码房失败' }
        } elseif ($previous) {
            $expectedName = if ($attempt -eq 4 -and $CheckLogPrivacy) { 'Privacy test' } else { $originalRoomName }
            if ($rooms[0].passwordProtected -ne $true -or $rooms[0].name -ne $expectedName) { throw '恢复后的房间配置不符' }
        }
        $history = "$url/api/v1/groups/$($groups[0].id)/messages"
        if ($attempt -eq 1) {
            $sent = (Invoke-RestMethod $history -Headers $headers -Method Post -ContentType 'application/json' -Body (@{body=$marker} | ConvertTo-Json)).data
        }
        $messages = (Invoke-RestMethod $history -Headers $headers).data
        if ($attempt -eq 1) { $welcome = $messages | Where-Object { $_.id -ne $sent.id } | Select-Object -First 1 }
        if (!$welcome.id -or !($messages | Where-Object { $_.id -eq $welcome.id -and $_.body -eq $welcome.body })) { throw '原欢迎消息缺失或变化' }
        if ($attempt -eq 4) {
            if ($messages.Count -ne 3 -or !($messages | Where-Object { $_.id -eq $added.id -and $_.body -eq $upgradeMarker }) -or
                !($messages | Where-Object { $_.id -eq $sent.id -and $_.deleted -and $null -eq $_.body })) { throw '重新启用新版后，新版消息或删除标记丢失' }
        } elseif ($messages.Count -ne 2 -or !($messages | Where-Object { $_.id -eq $sent.id -and $_.body -eq $marker })) { throw '原试用消息缺失或已改变' }
        "$($login.data.userId):$($groups[0].id):$($rooms[0].id)"
        if ($attempt -eq 2) {
            $sockets = @()
            $deadline = [Threading.CancellationTokenSource]::new(15000)
            try {
                if ($CheckSyncAuth) {
                    $cursor = (Invoke-RestMethod "$url/api/v1/sync/cursor" -Headers $headers).data.cursor
                    foreach ($mode in 'header','legacy') {
                        $socket = [Net.WebSockets.ClientWebSocket]::new()
                        $sockets += @{mode=$mode;client=$socket}
                        $endpoint = "ws://127.0.0.1:$HttpPort/api/v1/ws?cursor=$cursor"
                        if ($mode -eq 'header') { $socket.Options.SetRequestHeader('Authorization', $headers.Authorization) }
                        else { $endpoint += '&access_token=' + [uri]::EscapeDataString($login.data.accessToken) }
                        try { $null = $socket.ConnectAsync([uri]$endpoint, $deadline.Token).GetAwaiter().GetResult() }
                        catch { throw "Loopback sync handshake failed: $mode" }
                    }
                }
                $removed = (Invoke-RestMethod "$url/api/v1/messages/$($sent.id)" -Headers $headers -Method Delete).data
                if (!$removed.deleted -or $removed.deleteKind -ne 'AUTHOR' -or $removed.id -ne $sent.id) { throw '恢复后删除本人消息失败' }
                foreach ($entry in $sockets) { Assert-DeleteEvent $entry.client $entry.mode $sent.id $groups[0].id $cursor $deadline.Token }
                if ($CheckSyncAuth) { Write-Host 'PACKAGED_SYNC_HEADER_AND_LEGACY_EVENT_OK' }
            } finally {
                foreach ($entry in $sockets) { $entry.client.Dispose() }
                $deadline.Dispose()
            }
            if ($CheckRoomReopen) { Assert-RoomReopen $rooms[0] $headers }
            if ($previous) {
                $added = (Invoke-RestMethod $history -Headers $headers -Method Post -ContentType 'application/json' -Body (@{body=$upgradeMarker} | ConvertTo-Json)).data
                $updated = (Invoke-RestMethod $history -Headers $headers).data
                if (!$added.id -or !($updated | Where-Object { $_.id -eq $added.id -and $_.body -eq $upgradeMarker }) -or
                    !($updated | Where-Object { $_.id -eq $sent.id -and $_.deleted -and $null -eq $_.body })) { throw '未读到新版独立的新增消息和删除标记' }
            }
        } elseif ($attempt -eq 3) {
            if ($messages | Where-Object { $_.id -eq $added.id -or $_.body -eq $upgradeMarker -or ($_.id -eq $sent.id -and $_.deleted) }) {
                throw '切回旧包后错误读到了新版的写入'
            }
        }
        if ($CheckLogPrivacy -and $attempt -eq 2) {
            $room = $rooms[0]
            $roomPassword = [guid]::NewGuid().ToString('N')
            $secrets += $roomPassword
            $update = @{name='Privacy test';description='';gameName='Fixture';password=$roomPassword} | ConvertTo-Json
            $protected = (Invoke-RestMethod "$url/api/v1/rooms/$($room.id)" -Headers $headers -Method Patch -ContentType 'application/json' -Body $update).data
            $records = (Invoke-RestMethod "$url/api/v1/groups/$($groups[0].id)/audit-logs" -Headers $headers).data
            if (!$protected.passwordProtected -or !($records | Where-Object { $_.action -eq 'ROOM_UPDATE' -and $_.targetId -eq $room.id })) { throw 'Missing protected room or audit record' }
            Assert-NoCredential (ConvertTo-Json -InputObject $records -Depth 10) $secrets 'group audit records'
        }
        if ($ControlTlsNginx -and $attempt -eq 2) {
            $probeArgs = if ($ControlTlsRustProbe) { '--rust-probe'; (Resolve-Path -LiteralPath $ControlTlsRustProbe).Path }
            $password | python -B "$workspace/ops/test-control-plane-tls.py" --nginx $ControlTlsNginx --upstream-port $HttpPort @probeArgs | ForEach-Object { Write-Host $_ }
            if ($LASTEXITCODE) { throw '隔离试用包 HTTPS/WSS 验证失败' }
        }
        & "$trial/scripts/local-preview/stop.ps1" | Out-Host
        if ($CheckLogPrivacy) {
            foreach ($nameOfLog in 'server.log','server-error.log') {
                $text = [IO.File]::ReadAllText("$trial/target/local-preview/$nameOfLog")
                if ($nameOfLog -eq 'server.log' -and !$text.Contains('LOCAL_PREVIEW_READY')) { throw 'Missing startup log' }
                Assert-NoCredential $text $secrets $nameOfLog
            }
        }
        if ($trial -eq $current -and (Get-Location).Path -ne $caller) { throw '停止后未保留调用者目录' }
        if ($previous -and $attempt -eq 1) {
            Set-Location -LiteralPath $caller
            $backup = Join-Path $fixture 'snapshot'
            & python -B "$workspace/ops/database/cold-backup.py" snapshot --source "$trial/target/local-preview/postgres" --destination $backup | Out-Host
            if ($LASTEXITCODE) { throw '旧包停库快照失败' }
            if (Test-Path -LiteralPath "$current/target/local-preview/postgres") { throw '新版测试目录已存在数据库，拒绝覆盖' }
            $runtime = New-Item -ItemType Directory -Path "$current/target/local-preview" -Force
            Copy-Item -LiteralPath "$backup/data" -Destination "$($runtime.FullName)/postgres" -Recurse -ErrorAction Stop
            & python -B "$workspace/ops/database/cold-backup.py" verify --backup $backup --restored-data "$($runtime.FullName)/postgres" | Out-Host
            if ($LASTEXITCODE) { throw '恢复副本核验失败，拒绝启动' }
            $trial = $current
        } elseif ($previous -and $attempt -eq 2) {
            $newBackup = Join-Path $fixture 'new-snapshot'
            & python -B "$workspace/ops/database/cold-backup.py" snapshot --source "$current/target/local-preview/postgres" --destination $newBackup | Out-Host
            if ($LASTEXITCODE) { throw '新版停库快照失败' }
            $trial = Join-Path $fixture 'previous'
            & python -B "$workspace/ops/database/cold-backup.py" verify --backup $backup --restored-data "$trial/target/local-preview/postgres" | Out-Host
            if ($LASTEXITCODE) { throw '旧库或原快照在新版使用期间已改变，拒绝回退' }
        } elseif ($previous -and $attempt -eq 3) {
            & python -B "$workspace/ops/database/cold-backup.py" verify --backup $newBackup --restored-data "$current/target/local-preview/postgres" | Out-Host
            if ($LASTEXITCODE) { throw '新版数据在回退期间已改变，拒绝启动' }
            $trial = $current
        }
    }
    if ($snapshots.Count -ne $attemptCount -or @($snapshots | Select-Object -Unique).Count -ne 1) { throw '重启或回退后原始业务标识改变' }
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
if ($CheckLogPrivacy) { Write-Output 'PACKAGED_LOGIN_ROOM_AUDIT_AND_SERVER_LOG_PRIVACY_OK' }
if ($previous) { Write-Output 'BACKEND_PACKAGE_UPGRADE_USER_DATA_OK'; Write-Output 'BACKEND_PACKAGE_ROLLBACK_ORIGINAL_DATA_OK'; Write-Output 'BACKEND_PACKAGE_RESUME_UPGRADED_DATA_OK' }
