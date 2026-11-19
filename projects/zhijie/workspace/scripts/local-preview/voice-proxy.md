# Windows 单机语音试用

默认聊天试用不启用语音。这里供同一台电脑上的桌面客户端连接真实后端，不开放局域网或公网入口。先按本目录 README 准备 JDK、控制面类路径，并准备已校验的 LiveKit 和含 `auth_request` 模块的 nginx。若已有试用后端，先正常停止；不要删除数据库。

从 `workspace` 打开一个专用 PowerShell，在该窗口运行（把两个 exe 路径改成自己的实际路径）。以下随机值只在本次会话和需要它们的子进程中使用，不要打印或发给客户端：

```powershell
$env:ZHIJIE_VOICE_API_KEY = 'local-trial'
$env:ZHIJIE_VOICE_API_SECRET = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
$env:ZHIJIE_VOICE_SIGNALING_KEY = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
$env:ZHIJIE_VOICE_ENABLED = 'true'
$env:ZHIJIE_VOICE_URL = 'ws://127.0.0.1:7881'
& ./scripts/local-preview/start-voice.ps1 -LiveKitExe 'D:/tools/livekit-server.exe'
try {
    ./scripts/local-preview/start.ps1
    try {
        python -B ./scripts/local-preview/voice-proxy.py --nginx 'D:/tools/nginx.exe'
    } finally {
        ./scripts/local-preview/stop.ps1
    }
} finally {
    ./scripts/local-preview/stop-voice.ps1
}
```

看到 `VOICE_PROXY_READY` 后，在另一个普通 PowerShell 从 `workspace` 执行 `python -B "scripts/local-preview/trial-desktop.py" "target/trial-40bc36b"` 手动打开候选桌面；若更改后端端口，传入相同的 `--http-port`。客户端登录后加入房间并获取凭据；签发地址应为 7881，7880 仅供本机后端管理。代理仅接受 `/rtc` 系列信令，通过控制面鉴权，不转发管理 API。本机明文回环不替代正式 TLS 入口；同机不可信用户和进程不在单机试用的信任边界内。

结束时先关闭客户端，回到代理窗口按回车，等待后端和 SFU 退出，再关闭专用 PowerShell。若启动中断，也只停止本次实际启动的服务。正常退出删除代理私密配置，试用账号与数据库保留。自定义后端端口时，`start.ps1 -HttpPort` 与 `voice-proxy.py --control-port` 须一致；自定义代理 `--port` 时同步修改 `ZHIJIE_VOICE_URL`。
