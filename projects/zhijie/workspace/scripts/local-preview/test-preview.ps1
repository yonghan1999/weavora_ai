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
    return "$($groups[0].id):$($rows[0].id)"
}
$savedPassword = $env:ZHIJIE_PREVIEW_PASSWORD
$savedMail = $env:ZHIJIE_MAIL_ENABLED
$savedVoice = $env:ZHIJIE_VOICE_ENABLED
Push-Location -LiteralPath $fixture
try {
    $env:ZHIJIE_MAIL_ENABLED = 'false'
    $env:ZHIJIE_VOICE_ENABLED = 'false'
    $env:ZHIJIE_PREVIEW_PASSWORD = $password
    & "$fixture/scripts/local-preview/start.ps1" -JavaHome $JavaHome -HttpPort $HttpPort -DatabasePort $DatabasePort
    $before = Read-Seed
    & "$fixture/scripts/local-preview/stop.ps1"
    & "$fixture/scripts/local-preview/start.ps1" -JavaHome $JavaHome -HttpPort $HttpPort -DatabasePort $DatabasePort
    if ((Read-Seed) -ne $before) { throw "重启后的群或消息标识改变" }
    Write-Output 'PREVIEW_SEED_CURSOR_RESTART_OK'
} finally {
    try { & "$fixture/scripts/local-preview/stop.ps1" } finally {
        $env:ZHIJIE_PREVIEW_PASSWORD = $savedPassword
        $env:ZHIJIE_MAIL_ENABLED = $savedMail
        $env:ZHIJIE_VOICE_ENABLED = $savedVoice
        Pop-Location
    }
}
