# 双端房间资格与通道故障回归

`GroupBrowserIntegrationTest` 使用两个独立的无头浏览器上下文和两个 Rust Shell 测试进程。页面的 invoke 请求经测试管道送到 Tauri 测试运行时，由正式命令表解码并执行；不在夹具中重写登录、语音、网络或活动命令。事件仍使用测试管道传送，WebView2 原生窗口和系统事件桥不在本项覆盖范围内。

以 `ZHIJIE_TEST_GROUP_BROWSER=true` 在 `control-plane` 目录运行 `../mvnw.cmd -o -Dtest=GroupBrowserIntegrationTest test`。需要预先准备本项目的离线 Rust、JDK、Maven、Node 和无头浏览器环境。测试创建临时 PostgreSQL、回环控制面和临时账号，不用现有试用库。两客户端使用不存在的专用 Helper 地址，语音网关显式关闭：预期语音为 `VOICE_CAPACITY_LIMITED`、网络为 `HELPER_UNAVAILABLE`，不加载驱动、不采集麦克风。

- 两名成员加入同一活动，各自取得不同租约；两种失败均通过正式桌面命令读回，并在页面分别显示，成员发出的群消息仍能到达另一页面。
- 群主按成员代次确认踢出，成员无需刷新即返回大厅。相同账号重入同一房间获得新租约和更高代次；向两个连接命令提交旧租约都返回 `CURRENT_GRANT_REQUIRED`。
- 成员主动离开后，由数据库核对两代租约均已撤销、第一代网络票据已撤销、第二代成员状态为 `LEFT`、不再占用虚拟地址；群主仍保有自己的原租约。

Helper 缺席时，原生 IPC 现在区分「未发送」与「可能已发送」：只有本次启动在写入前即失败，才丢弃对应的本地待清理票据；开始写入、应答丢失或任务异常仍保留票据，不冒充已停网卡。修复前，此场景在第二代重入后仍显示第一代联机状态。回归要求手动重试后网络快照属于新代次，且数据库存在至少两把属于新代次、离房时已撤销的不同网络公钥；不把发票记为连通。复现实测分别见 `target/room-channel-ipc/unstarted-before.log` 和 `target/room-channel-ipc/unstarted-fixed.log`。

本机 Windows 命名管道回归还发现，整段非阻塞写入较大的启动请求可导致对端只收到长度头。收发双方沿用原长度帧和五秒绝对截止时间，Windows 写端以最多 512 字节分段，遇到零字节写入时在同一截止时间内重试。实际管道测试验证大请求和大响应保持请求标识一致，以及对端完整读到请求但未回复时仍保留清理资格。使用记录调用的后端，不启动驱动；见 `target/room-channel-ipc/delivery-final.log`。

开发验证：`target/room-channel-ipc/browser-regression.log` 的三项浏览器集成通过；首次用例因刷新期间点击而停在确认框之前，记录在 `integration.log`，修正为等待实际成员代次后再点击。增强后的 `target/room-channel-ipc/leave-final.log` 单项通过，从群主提交确认到成员退回大厅用时 1429 毫秒。这不是正在传输的媒体或 TUN 撤销时延，也不证明真实双机、设备听感、安装或满载通过。
## 同一租约内的联机换钥

桌面重试可在租约和虚拟地址不变时生成新 WireGuard 公钥。控制面签发时在同一事务内撤销该租约不同公钥的有效网络票据；如果签发失败，撤销随事务回滚。同钥续期仍允许新旧票据短时重叠。按公钥字节而非 Base64 文本判断是否换钥，新签发的数据统一使用带填充的 Base64。复用现有授权与 Peer 查询，不另建端点状态：提交换钥后，队友下一次读取时看不到旧端点；新端点注册后才返回新公钥。

开发验证：`target/network-key-rotation/regression.log`。在 `control-plane` 目录设置 `ZHIJIE_TEST_RELAY_MEDIA=true`、`ZHIJIE_TEST_GROUP_BROWSER=true`，执行 `../mvnw.cmd -o -Dtest=NetworkPeerIntegrationTest,GroupBrowserIntegrationTest#roomMembershipSurvivesChannelFailuresAndKickInvalidatesOnlyTheOldLease,RelayGrantValidationServiceTest,AdmissionServiceTest package`，6 项通过，无跳过。真实 PostgreSQL 核对同租约续期、换钥、端点交接与其他成员不受影响。真实回环十二端分别在直连、强制中继和直连优先策略下完成五轮同钥续期，原票过期后仍能在 132 个方向传输。此处没有测量已建数据通路在换钥时的中断时延，也没有验证跨机 TUN 或同时满载。
