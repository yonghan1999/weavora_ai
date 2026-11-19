# 双端房间资格与通道故障回归

`GroupBrowserIntegrationTest` 使用两个独立的无头浏览器上下文和两个 Rust Shell 测试进程。页面的 invoke 请求经测试管道送到 Tauri 测试运行时，由正式命令表解码并执行；不在夹具中重写登录、语音、网络或活动命令。事件仍使用测试管道传送，WebView2 原生窗口和系统事件桥不在本项覆盖范围内。

以 `ZHIJIE_TEST_GROUP_BROWSER=true` 在 `control-plane` 目录运行 `../mvnw.cmd -o -Dtest=GroupBrowserIntegrationTest test`。需要预先准备本项目的离线 Rust、JDK、Maven、Node 和无头浏览器环境。测试创建临时 PostgreSQL、回环控制面和临时账号，不用现有试用库。两客户端使用不存在的专用 Helper 地址，语音网关显式关闭：预期语音为 `VOICE_CAPACITY_LIMITED`、网络为 `HELPER_UNAVAILABLE`，不加载驱动、不采集麦克风。

- 两名成员加入同一活动，各自取得不同租约；两种失败均通过正式桌面命令读回，并在页面分别显示，成员发出的群消息仍能到达另一页面。
- 群主按成员代次确认踢出，成员无需刷新即返回大厅。相同账号重入同一房间获得新租约和更高代次；向两个连接命令提交旧租约都返回 `CURRENT_GRANT_REQUIRED`。
- 成员主动离开后，由数据库核对两代租约均已撤销、第一代网络票据已撤销、第二代成员状态为 `LEFT`、不再占用虚拟地址；群主仍保有自己的原租约。

开发验证：`target/room-channel-ipc/browser-regression.log` 的三项浏览器集成通过；首次用例因刷新期间点击而停在确认框之前，记录在 `integration.log`，修正为等待实际成员代次后再点击。增强后的 `target/room-channel-ipc/leave-final.log` 单项通过，从群主提交确认到成员退回大厅用时 1429 毫秒。这不是正在传输的媒体或 TUN 撤销时延，也不证明真实双机、设备听感、安装或满载通过。
