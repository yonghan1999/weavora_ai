param([string]$JavaHome = $env:JAVA_HOME, [int]$HttpPort = 18828, [int]$DatabasePort = 15438)
$ErrorActionPreference = "Stop"
$workspace = (Resolve-Path "$PSScriptRoot/../..").Path
$scriptDirectory = $PSScriptRoot
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
    $leave = @{leaseId=$joined.leaseId} | ConvertTo-Json
    if (!(Invoke-RestMethod "$activity/leave" -Headers $headers -Method Post -ContentType 'application/json' -Body $leave).data.left) { throw "试用活动退出失败" }
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
    $temp = (New-Item -ItemType Directory -Path "$fixture/target/local-preview/temp" -Force).FullName
    $env:TEMP = $temp
    $env:TMP = $temp
    $oldShell = $env:ComSpec
    $shim = "$fixture/fixture-cmd.exe"
    $shimSource = [IO.Path]::GetFullPath("$fixture/scripts/local-preview/CleanCmd.cs")
    try {
        & "C:/Windows/Microsoft.NET/Framework64/v4.0.30319/csc.exe" /nologo "/out:$shim" $shimSource
        if ($LASTEXITCODE) { throw "测试命令行入口编译失败" }
        $env:ComSpec = $shim
        $testClasspath = "$workspace/control-plane/target/classes;$classpath"
        function Invoke-SeedFixture([string]$Mode) {
            & "$JavaHome/bin/java.exe" "-Djava.io.tmpdir=$temp" -XX:-UsePerfData -cp $testClasspath "$scriptDirectory/PreviewSeedFixture.java" $Mode $DatabasePort
            if ($LASTEXITCODE) { throw "初始化事务夹具失败：$Mode" }
        }
        Invoke-SeedFixture 'prepare'
        $env:ZHIJIE_PREVIEW_PASSWORD = $password
        $failed = $false
        try { & "$fixture/scripts/local-preview/start.ps1" -JavaHome $JavaHome -HttpPort $HttpPort -DatabasePort $DatabasePort } catch { $failed = $true }
        if (!$failed -or !(Select-String -LiteralPath "$fixture/target/local-preview/server-error.log" -SimpleMatch 'preview_test_reject_seed' -Quiet)) { throw "未观察到预期的欢迎消息写入失败" }
        Invoke-SeedFixture 'verify'
        Write-Output 'PREVIEW_PARTIAL_SEED_ROLLBACK_OK'
    } finally {
        $env:ComSpec = $oldShell
        $env:TEMP = $savedTemp
        $env:TMP = $savedTmp
    }
    $previewPromptState = [pscustomobject]@{ Calls = 0; Secret = $password }
    function Read-Host([string]$Prompt, [switch]$AsSecureString) {
        if (!$AsSecureString -or $previewPromptState.Calls -ne 0) { throw "非预期的密码请求" }
        $previewPromptState.Calls++
        ConvertTo-SecureString -String $previewPromptState.Secret -AsPlainText -Force
    }
    & "$fixture/scripts/local-preview/start.ps1" -JavaHome $JavaHome -HttpPort $HttpPort -DatabasePort $DatabasePort
    if ($previewPromptState.Calls -ne 1) { throw "已建库但未建账号时应只安全询问一次密码" }
    Write-Output 'PREVIEW_SEED_PASSWORD_RECOVERY_OK'
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
