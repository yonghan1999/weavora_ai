# Helper 首次中继连接与控制命令并行

## 缺陷与实现范围

基于 `b0c185f` 修复强制中继模式在数据泵循环之前直接等待首次 QUIC 连接的问题。真实 UDP 黑洞收到至少 1200 字节的 QUIC Initial 后发送 Stop，原代码未能在 300 ms 内退出并释放受控 TunnelDevice，失败日志保留在 `target/relay-startup-b0c185f/before.log`。

这是既有网络撤销、隔离与恢复范围的缺陷修复，不修改需求、架构、协议、依赖或配置，不代表 TASK-019～023 验收完成或正式 F/G/H/I 阶段通过。

## 行为与边界

- 首次连接复用数据泵现有的 owned `relay_connect` Future，与 Stop、命令通道关闭、每秒 HTTP 验权、票据到期和授权快照截止并行；退出时丢弃在途连接，不新增后台任务。
- 强制中继仍先将路由设置为 Relay；缺失配置立即保留 `RELAY_PATH_UNAVAILABLE`。等待连接不能向 Peer 的直连 UDP endpoint 发送数据。
- 强制中继且连接未就绪时不读取 TunnelDevice、不推进路由握手计时，避免生成的 WireGuard 首次握手无人发送、随后等待重试。控制命令、HTTP 校验和硬截止继续正常推进；有可用直连路径时保留原有转发行为。
- QUIC 黑洞保持既有三秒超时。持续验权成功且票据仍有效时返回 `RELAY_CONNECTION_TIMEOUT`；更早的授权快照、票据截止或明确拒绝优先结束会话并释放设备。
- 300 ms 是本机数据泵测试边界，不是整个同步后端或 IPC Stop 的服务承诺；原有后端互斥锁与同步回复等待不在本次修改范围。

## 实际验证

命令、完整日志和本轮临时文件均位于 D 盘 Git 忽略的 `workspace/target/relay-startup-b0c185f/`，下表路径相对于该目录。测试证书由 rcgen 临时生成，不使用真实业务凭据。

| 检查 | 日志 | 结果 |
| --- | --- | --- |
| 原代码复现 | `before.log` | Stop 300 ms 预期失败，保留原始记录 |
| 首版完整回归 | `rust-regression.log` | 68 通过、1 失败、3 忽略；正常强制中继首包等待超时，未提交该版本 |
| 正常传输修复复验 | `policy-retest.log` | 既有直连失败回落、强制中继传输及撤销回归通过，未放宽期限 |
| 黑洞与失败边界 | `boundaries-retest.log` | 8 项通过：Stop、命令所有者退出、快照截止、票据截止、HTTP 403、周期验权且禁止直连旁路、原超时错误、缺失配置 |
| Helper/Relay/Shell | `final-rust-regression.log` | `cargo test --locked --offline -p zhijie-helper -p zhijie-relay -p zhijie-shell`：127 通过、8 个专用集成入口忽略、0 失败 |
| Helper/Relay 可执行程序 | `final-build.log` | `cargo build --locked --offline -p zhijie-helper -p zhijie-relay` 成功 |
| 真实控制面组合 | `java-regression.log` | `NetworkPeerServiceTest,NetworkPeerIntegrationTest,RelayOutboxIntegrationTest` 且 `ZHIJIE_TEST_RELAY_MEDIA=true`：5 项通过，无失败、无跳过 |

控制面组合在 Cargo 回归和构建完全结束后串行运行。三个十二端真实控制面子测试分别覆盖 direct、relay、mixed，完成五轮续票、原票期限后传输、成员退出隔离及策略撤销。每轮检查十二端 132 个方向各 10 包；移除一端后检查十一端的 110 个方向。子测试日志分别为 `target/network-room-12320904995010862299/rust-network.log`、`target/network-room-7788823043394049054/rust-network.log`、`target/network-room-8346636908115712574/rust-network.log`，用时 82.68、82.71、87.38 秒。另有真实 Relay 已建立连接撤销及旧 Outbox 事件隔离测试。

结束日志的 SHA-256：

- `before.log`：`0a9fa2c8f04dc24ddada14e72852c3259f0601b25383e91775bfd4330681e939`。
- `rust-regression.log`：`d77c8fff8a7e5a12fde36a6649f427a562090afdcf98cbbb5eb31b88322575a1`。
- `final-rust-regression.log`：`a20b80f988641a160fc20cdc57c67aadad6c02696b82ad2274769ce9b4f23ca6`。
- `final-build.log`：`eabfdb247596b4b51deca1c836c318925ebf067a13f39ea2602e7e580e292f48`。
- `java-regression.log`：`5fb6cd064818de2940891438581fd1457bf022817fd7e4e4a26324307009485a`。

## 未替代的验收与制品

同机受控 TunnelDevice、真实 UDP/QUIC、数据库与 HTTP 组合不是跨机真实 TUN、NAT、游戏、弱网、目标容量或端到端失权时限验收。主动 Peer 撤销及失联边界、人工语音、三平台安装更新卸载与签名、许可人工审核和正式流程继续开放。

原 `target/trial-kit-0ce6df5/zhijie-windows-x64-trial.zip` 不包含本次修复。后续已从 `8da8814` 干净归档实际重建桌面/Helper，优化版 Helper 69 项单测通过，成套内容与 170 项提取界面检查通过，并完成新整包登录、重启、冷备恢复与设备资格回归。新入口为 `target/trial-kit-8da8814/zhijie-windows-x64-trial.zip`，SHA-256：`3abd8fe20686ae595f45228844e55ce050b28ccb4386a2d9af6bfc825c8135c6`；见 `packaging/windows/candidate-build.md`。没有安装或启动原生桌面，不将这些制品检查当作本节未完成验收。

## 原则应用

KISS/YAGNI：复用原连接 Future 和时钟，不新增调度器、队列、配置或重试协议。DRY：黑洞测试共用原 HTTP、设备释放和数据泵夹具，正常交付复用既有端到端开发回归。单一职责：传输就绪状态只控制数据输入和握手，授权与控制命令保持独立可推进。
