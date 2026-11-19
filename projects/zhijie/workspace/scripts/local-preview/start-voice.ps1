param([string]$LiveKitExe = "", [string]$MediaAddress = "")
$ErrorActionPreference = "Stop"
$workspace = (Resolve-Path "$PSScriptRoot/../..").Path
$runtime = "$workspace/target/local-preview/livekit"
if (!$LiveKitExe) { $LiveKitExe = "$runtime/bin/livekit-server.exe" }
$executable = (Resolve-Path -LiteralPath "$LiveKitExe").Path
if (!(Test-Path -LiteralPath "$executable" -PathType Leaf)) { throw "请先安装官方 LiveKit 服务端。" }
if (!$env:ZHIJIE_VOICE_API_KEY -or !$env:ZHIJIE_VOICE_API_SECRET -or $env:ZHIJIE_VOICE_API_SECRET.Length -lt 32) {
    throw "请在当前进程环境配置 ZHIJIE_VOICE_API_KEY 和至少 32 字符的 ZHIJIE_VOICE_API_SECRET。"
}
if ($env:ZHIJIE_VOICE_API_KEY -notmatch '^[a-zA-Z0-9_-]+$' -or $env:ZHIJIE_VOICE_API_SECRET -match '[\r\n]') {
    throw "本地 LiveKit 配置格式无效。"
}
if (Get-NetTCPConnection -State Listen -LocalPort 7880 -ErrorAction SilentlyContinue) { throw "7880 已被占用。" }
if (Get-NetUDPEndpoint -LocalPort 7882 -ErrorAction SilentlyContinue) { throw "媒体 UDP 7882 已被占用。" }
if (Test-Path -LiteralPath "$runtime/server.pid") {
    $recordedId = [int](Get-Content -LiteralPath "$runtime/server.pid")
    if (Get-Process -Id $recordedId -ErrorAction SilentlyContinue) { throw "记录的进程仍在运行；请先核实，不覆盖现有状态。" }
}
New-Item -ItemType Directory -Path "$runtime" -Force | Out-Null
if (!$MediaAddress) {
    $MediaAddress = Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway -and $_.IPv4Address } | ForEach-Object { $_.IPv4Address.IPAddress } | Select-Object -First 1
}
$parsedAddress = $null
if (![System.Net.IPAddress]::TryParse($MediaAddress, [ref]$parsedAddress) -or $parsedAddress.AddressFamily -ne [System.Net.Sockets.AddressFamily]::InterNetwork) {
    throw "请指定本机有效 IPv4 网卡地址：-MediaAddress。"
}
if (!(Get-NetIPAddress -AddressFamily IPv4 -IPAddress $MediaAddress -ErrorAction SilentlyContinue)) {
    throw "媒体地址必须属于本机网卡。"
}
$configuration = @"
port: 7880
bind_addresses: [127.0.0.1]
logging:
  level: warn
rtc:
  node_ip: $MediaAddress
  tcp_port: 0
  udp_port: 7882
  use_external_ip: false
  enable_loopback_candidate: true
  ips:
    includes: [$MediaAddress/32]
"@
[System.IO.File]::WriteAllText("$runtime/local.yaml", $configuration)
$previousKeys = $env:LIVEKIT_KEYS
$previousTemp = $env:TEMP
$previousTmp = $env:TMP
try {
    $env:TEMP = (New-Item -ItemType Directory -Path "$runtime/temp" -Force).FullName
    $env:TMP = $env:TEMP
    $quotedSecret = ConvertTo-Json -InputObject $env:ZHIJIE_VOICE_API_SECRET -Compress
    $env:LIVEKIT_KEYS = "$($env:ZHIJIE_VOICE_API_KEY): $quotedSecret"
    $process = Start-Process -FilePath "$executable" -ArgumentList @("--config", "`"$runtime/local.yaml`"") -WindowStyle Hidden -PassThru -RedirectStandardOutput "$runtime/server.out.log" -RedirectStandardError "$runtime/server.err.log"
} finally {
    $env:LIVEKIT_KEYS = $previousKeys
    $env:TEMP = $previousTemp
    $env:TMP = $previousTmp
}
$process.Id | Set-Content -LiteralPath "$runtime/server.pid"
for ($attempt = 0; $attempt -lt 20; $attempt++) {
    if ($process.HasExited) { throw "LiveKit 启动失败，请检查本地日志。" }
    $signal = Get-NetTCPConnection -State Listen -LocalAddress 127.0.0.1 -LocalPort 7880 -OwningProcess $process.Id -ErrorAction SilentlyContinue
    $media = Get-NetUDPEndpoint -LocalPort 7882 -OwningProcess $process.Id -ErrorAction SilentlyContinue
    if ($signal -and $media) {
        $env:ZHIJIE_VOICE_ADMIN_URL = "http://127.0.0.1:7880"
        Write-Output "本机 LiveKit 已就绪，进程 $($process.Id)；信令仅回环，媒体 UDP $($MediaAddress):7882。"
        return
    }
    Start-Sleep -Milliseconds 250
}
if (!$process.HasExited) { $process.Kill(); $process.WaitForExit() }
throw "等待本机信令和媒体端口就绪超时；已停止本次启动的进程，请检查日志。"
