# 控制面同步入口

在已获准的部署窗口中先更新控制面，再更新桌面。新版桌面在 `/api/v1/ws?cursor=…` 的 WebSocket 升级请求上发送 `Authorization: Bearer …`；不把令牌写进 URL。旧后端不支持该请求头，不能先单独更新桌面。回退顺序相反。

对外使用 HTTPS/WSS；反向代理应保留 `Authorization`、WebSocket 升级和 `cursor` 查询参数，并将此入口转发到回环控制面。不要记录认证头、完整请求行或查询串。旧桌面仍可能在查询串中传递令牌，新后端仅在没有认证头时兼容这种方式；认证头不合法时不会改用查询令牌。

这只涉及控制面同步，不改变语音信令的认证协议，也不要求对外暴露内部授权或管理端点。

## 候选配置

`control-plane.nginx.conf.example` 是 nginx `http` 上下文中的独立虚拟主机示例。使用独立控制面域名，替换 `control.example.test` 和两个 PEM 路径，并核对回环上游端口。仅转发 `/api/v1/`；`/api/v1/ws` 单独处理协议升级，其他路径返回 404。不要改成转发根路径，不要把控制面本身绑定到公网。此示例不配置证书签发、续期、负载均衡、其他虚拟主机或主机防火墙，也不提供按外部客户端地址的限流验证。

该虚拟主机禁用访问日志；同步路径额外丢弃 nginx 错误日志，避免上游故障时把旧桌面含令牌的请求行写盘。这也会减少该路径的代理诊断信息。部署前还须核查外层网关和全局日志策略、证书链和后端监听，使用实际配置执行 `nginx -t`；本仓库不自动安装、重载或部署。

## 本机验证

从 Windows `workspace` 对可信的本机后端包运行以下命令。需要 JDK 25、nginx（包含 SSL 模块）以及装有 `aiohttp`、`cryptography` 的 Python 3.12+，路径按实际位置替换。

```powershell
& "./packaging/windows/test-backend-zip.ps1" -Package "<后端试用包.zip>" -JavaHome "<JDK 25>" -ControlTlsNginx "<nginx.exe 的绝对路径>"
```

测试仅在隔离试用库中写一条消息。临时代理绑定回环高端口，使用短时证书，不修改系统信任库。`PACKAGED_CONTROL_HTTPS_WSS_AND_PRIVATE_ROUTES_OK` 表示默认信任拒绝证书、仅此测试客户端信任该证书后通过 HTTPS 登录和发消息、WSS 收到对应消息 ID 的同步事件，且健康、内部观测和运维路径返回 404。这不是生产域名、外网或真实桌面客户端验收。

要验证桌面复用的 Rust 客户端核心，在 `workspace` 运行 `cargo build --locked -p zhijie-core --example sync-probe --features sync-probe-test-roots`，再给上述命令加上 `-ControlTlsRustProbe "<实际 Cargo 输出目录>/debug/examples/sync-probe.exe"`。该非默认 feature 仅用于测试构建，勿用于分发。测试先确认 Rust 的 HTTPS 与 WSS 均拒绝未信任的回环证书，再仅向另一个探针进程提供临时根，不更改机器或已打包桌面的证书信任。

此模式额外写三条消息。`PACKAGED_RUST_TLS_SYNC_LIVE_CATCHUP_ROTATION_OK` 要求 Rust 使用 HTTPS 读取游标，并在三次 WSS 连接中分别收到实时消息、离线消息和刷新令牌后的消息，逐条核对 ID 和递增游标。登录、发消息及刷新令牌由测试驱动通过 HTTPS 执行；没有打开图形窗口或验证生产证书链。
