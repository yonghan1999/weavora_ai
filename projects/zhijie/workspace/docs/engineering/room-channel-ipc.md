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
## 联机满额时的续期

`zhijie.capacity.network` 约束的是持有有效联机授权的不同租约数，不是短时重叠的票据张数。修复前，上限为 2 时，一个成员拿原票再续期一次就能让其他房间的两个申请同时被拒绝。`ChannelCapacityIntegrationTest` 用三个独立群组、房间和账号复现，记录在 `target/channel-capacity-before.log`。

联机签发事务先取得 PostgreSQL 事务级联机准入锁，再统计其他仍持有效票据的租约，锁持续到该事务提交或回滚。这样，同一租约的同钥续期或换钥无需第二个名额；不同房间并发申请最后一个名额只会放行一个。达到上限后拒绝者可以在名额释放后再次申请。未更改语音准入规则或引入额外名额表。

开发回归：在工作区根目录运行 `mvnw.cmd -o -pl control-plane -Dtest=ChannelCapacityIntegrationTest,AdmissionServiceTest,RuntimeDatabasePrivilegesIntegrationTest,RelayGrantValidationServiceTest,NetworkPeerIntegrationTest package`，6 项通过、1 项真实数据泵测试默认跳过（`target/channel-capacity-regression.log`）。另在 `control-plane` 目录设置 `ZHIJIE_TEST_RELAY_MEDIA=true`、`ZHIJIE_CAPACITY_NETWORK=12`，运行 `../mvnw.cmd -o -Dtest=NetworkPeerIntegrationTest#realFullRoomDataPumpsUseIssuedTicketsAndStopOnlyTheDepartingMember test`。这项测试独立通过：直连、强制中继、直连优先分别在满额时五轮续期，首批票据到期后 132 个方向均有传输（`target/channel-capacity-12.log`）。这些结果只验证控制面授权与本机回环数据泵，不证明目标服务器的实际带宽、语音和联机同时满载，或跨机 TUN 验收。
## 语音满额时的重连

语音接入票的有效期不是通话时长。`zhijie.capacity.voice` 按已签发过未撤销语音票据、仍有效的不同房间租约计数。同一成员的心跳续租、重试或重新签票不额外占位；原票过期不释放仍有效的租约。离房、撤销或租约过期后释放。该计数是控制面的保守预留，不是实际正在发言或在线的 SFU 人数：只离开语音但仍续租房间，也会保留该名额。语音与联机使用独立的事务锁和限额。

`ChannelCapacityIntegrationTest` 在真实 PostgreSQL 中对两个通道各设两个名额。修复前，同一成员重复签票后，其他两个房间争抢最后一个语音名额均被拒（`target/voice-capacity-before.log`）。修复后，并发申请恰有一个成功；所有原入房票过期后，其他房间仍不能抢占该成员的有效语音租约；该成员离房后等待者可获得资格。控制面回归 10 项通过，日志 `target/voice-capacity-regression.log`。

另设 `ZHIJIE_CAPACITY_VOICE=12`、`ZHIJIE_TEST_VOICE_MEDIA=true`、`ZHIJIE_TEST_COMBINED_CHANNELS=false` 运行 `VoiceSignalingIntegrationTest`。同机真实 LiveKit、nginx、PostgreSQL 和十二个 SDK 端完成五轮心跳与重新签票；初始票过期后信令验证返回 403。75 秒、五个观察窗口共核实 132 个音频方向（`target/voice-capacity-12.log`、`target/voice-entry-3133454918333054278/rust-full-room-media.log`）。不替代真实设备听感、跨机或目标服务器上的双通道满载验收。
