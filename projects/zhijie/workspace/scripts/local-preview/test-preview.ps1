param([string]$JavaHome = $env:JAVA_HOME, [int]$HttpPort = 18828, [int]$DatabasePort = 15438)
$ErrorActionPreference = "Stop"
$workspace = (Resolve-Path "$PSScriptRoot/../..").Path
$fixture = "$workspace/target/preview-test-$([guid]::NewGuid().ToString('N'))"
$classpath = (Get-Content -Raw -LiteralPath "$workspace/control-plane/target/local-preview-classpath.txt").Trim()
if ($HttpPort -eq $DatabasePort -or (Get-NetTCPConnection -LocalPort $HttpPort,$DatabasePort -State Listen -ErrorAction SilentlyContinue)) { throw "测试端口不可用" }
New-Item -ItemType Directory -Path "$fixture/scripts/local-preview","$fixture/control-plane/target" | Out-Null
foreach ($name in 'start.ps1','stop.ps1','CleanCmd.cs','LocalPreview.java') {
    Copy-Item -LiteralPath "$PSScriptRoot/$name" -Destination "$fixture/scripts/local-preview/$name"
}
Set-Content -LiteralPath "$fixture/control-plane/target/local-preview-classpath.txt" -Encoding UTF8 -Value "$workspace/control-plane/target/classes;$classpath"
$password = [guid]::NewGuid().ToString('N')
$url = "http://127.0.0.1:$HttpPort"
function Read-Seed {
    $body = @{email='trial@example.test';password=$password;deviceId='preview-test'} | ConvertTo-Json
    $login = Invoke-RestMethod "$url/api/v1/auth/login" -Method Post -ContentType 'application/json' -Body $body
    $headers = @{Authorization=('Bearer ' + $login.data.accessToken)}
    $groups = (Invoke-RestMethod "$url/api/v1/groups" -Headers $headers).data
    if ($groups.Count -ne 1) { throw "示例群数量不符" }
    $history = "$url/api/v1/groups/$($groups[0].id)/messages"
    $rows = (Invoke-RestMethod $history -Headers $headers).data
    if ($rows.Count -ne 1) { throw "欢迎消息数量不符" }
    $earlier = (Invoke-RestMethod "${history}?beforeId=$($rows[0].id)" -Headers $headers).data
    if ($null -eq $earlier -or $earlier.Count -ne 0) { throw "游标未排除已读取消息" }
    $rooms = (Invoke-RestMethod "$url/api/v1/groups/$($groups[0].id)/rooms" -Headers $headers).data
    if ($rooms.Count -ne 1 -or !$rooms[0].currentActivityId) { throw "试用房间或活动缺失" }
    $body = @{roomId=$rooms[0].id;deviceId='preview-test';operationId=[guid]::NewGuid().ToString('N')} | ConvertTo-Json
    $joined = (Invoke-RestMethod "$url/api/v1/room-joins" -Headers $headers -Method Post -ContentType 'application/json' -Body $body).data
    if ($joined.activityId -ne $rooms[0].currentActivityId -or !$joined.leaseId -or !$joined.virtualIpv4) { throw "试用活动加入结果不符" }
    $activity = "$url/api/v1/activities/$($joined.activityId)"
    $heartbeat = @{deviceId=$joined.deviceId;leaseId=$joined.leaseId;activityGeneration=$joined.activityGeneration;memberGeneration=$joined.memberGeneration} | ConvertTo-Json
    if (!(Invoke-RestMethod "$activity/heartbeat" -Headers $headers -Method Post -ContentType 'application/json' -Body $heartbeat).data.renewed) { throw "试用活动心跳失败" }
    if (!(Invoke-RestMethod "$activity/leave" -Headers $headers -Method Post).data.left) { throw "试用活动退出失败" }
    return [pscustomobject]@{seed="$($groups[0].id):$($rows[0].id):$($rooms[0].id):$($joined.activityId)";lease=$joined.leaseId;generation=$joined.memberGeneration}
}
$savedPassword = $env:ZHIJIE_PREVIEW_PASSWORD
$savedMail = $env:ZHIJIE_MAIL_ENABLED
$savedVoice = $env:ZHIJIE_VOICE_ENABLED
$savedTemp = $env:TEMP
$savedTmp = $env:TMP
Push-Location -LiteralPath $fixture
try {
    $env:ZHIJIE_MAIL_ENABLED = 'false'
    $env:ZHIJIE_VOICE_ENABLED = 'false'
    $env:ZHIJIE_PREVIEW_PASSWORD = $password
    & "$fixture/scripts/local-preview/start.ps1" -JavaHome $JavaHome -HttpPort $HttpPort -DatabasePort $DatabasePort
    if ($env:TEMP -ne $savedTemp -or $env:TMP -ne $savedTmp) { throw "启动器改变了调用者的临时目录" }
    if (!(Test-Path -LiteralPath "$fixture/target/local-preview/temp/embedded-pg" -PathType Container)) { throw "临时 PostgreSQL 未使用项目目录" }
    $before = Read-Seed
    & "$fixture/scripts/local-preview/stop.ps1"
    & "$fixture/scripts/local-preview/start.ps1" -JavaHome $JavaHome -HttpPort $HttpPort -DatabasePort $DatabasePort
    $after = Read-Seed
    if ($after.seed -ne $before.seed) { throw "重启后的群、消息、房间或活动标识改变" }
    if ($after.lease -eq $before.lease -or $after.generation -le $before.generation) { throw "重启后重新入房没有得到新租约和代次" }
    Write-Output 'PREVIEW_SEED_CURSOR_RESTART_OK'
    Write-Output 'PREVIEW_ROOM_HEARTBEAT_LEAVE_REJOIN_OK'
} finally {
    try { & "$fixture/scripts/local-preview/stop.ps1" } finally {
        $env:ZHIJIE_PREVIEW_PASSWORD = $savedPassword
        $env:ZHIJIE_MAIL_ENABLED = $savedMail
        $env:ZHIJIE_VOICE_ENABLED = $savedVoice
        Pop-Location
    }
}
