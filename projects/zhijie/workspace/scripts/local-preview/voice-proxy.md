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

看到 `VOICE_PROXY_READY` 后，用另一个普通 PowerShell 按本目录 `README.md` 的便携试用命令手动启动候选桌面；若更改后端端口，传入相同的 `--http-port`。客户端登录后加入房间并获取凭据；签发地址应为 7881，7880 仅供本机后端管理。代理仅接受 `/rtc` 系列信令，通过控制面鉴权，不转发管理 API。本机明文回环不替代正式 TLS 入口；同机不可信用户和进程不在单机试用的信任边界内。

结束时先关闭客户端，回到代理窗口按回车，等待后端和 SFU 退出，再关闭专用 PowerShell。若启动中断，也只停止本次实际启动的服务。正常退出删除代理私密配置，试用账号与数据库保留。自定义后端端口时，`start.ps1 -HttpPort` 与 `voice-proxy.py --control-port` 须一致；自定义代理 `--port` 时同步修改 `ZHIJIE_VOICE_URL`。
## 同一成员立即重试

成员在同一租约内重试语音时保持原参与者身份、房间权限和有效期上限。每次签发使用独立的 JWT 标识（`jti`），即使两次请求落在同一秒且到期时间相同，也不会因票据摘要重复与数据库唯一约束冲突。离房撤销仍按成员代次处理，不依赖客户端逐张交回票据。

开发回归：`LiveKitVoiceGatewayTest` 用固定时钟检查同秒签发，`VoiceSignalingIntegrationTest` 用真实 PostgreSQL、nginx 和 LiveKit 检查同一租约的两张票据均可通过信令验证，离房后均返回 403。修复前和修复后日志分别为 `target/voice-retry-before.log` 和 `target/voice-retry-regression.log`。此回归只验证签发与回环信令，不涉及麦克风、播放、听感或实际容量。
