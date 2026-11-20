# 真实 HTTP 失联下的 QUIC 中继强制隔离

## 范围

在 `ddf0029` 基线上补充 AC-026、AC-042 的开发回归。已有中继单测使用受控 GrantValidator 阻塞，本回归组合实际 PostgreSQL、Spring 签票与离房事务、生产 ControlPlaneValidator、实际 HTTP 黑洞、生产 QUIC 中继和管理撤销接口。

本次仅新增测试与说明，不修改生产实现、依赖、需求、架构或工作流状态。不据此完成正式任务、F/G/H/I 或整体交付。

## 验证边界

Java 创建三名实际成员，经匿名标准输入将短期票据和随机管理密钥传给独立 Rust 集成入口；不把凭据写入配置、文件或进程参数。测试 HTTP 代理只转发生产校验请求，不自行生成授权响应。初始四个实际 QUIC 交付方向是 A→B、B→A、A→C、C→A。

代理只对 B 的后续校验请求保持实际 HTTP 连接且不返回响应，A/C 仍请求真实控制面。确认 HTTP 请求实际挂起后，父端执行 B 的真实离房事务，返回后开始独立五秒截止。Rust 确认旧连接当时仍存活，所有发送任务继续每 20 ms 调用生产 RelayClient.send，不执行 Stop、授权轮询或客户端主动断开。

- `offline`：不发送管理撤销。要求 HTTP 挂起期间仍有至少十次本地发送被 QUIC 接受、两个相关方向均实际交付，随后服务端按有界授权失效关闭 B；不是刚遇到阻塞即中断正常流量。
- `push-offline`：依次调用生产同步 Outbox 和 NETWORK 外部派发，数据库必须记录该房间 `network.revoked` 已送达。要求父端撤销确认后一秒内观测服务端关闭，且 PUSH_APPLIED 后 Rust 等待最多 500 ms，区分主动撤销与 HTTP 超时自然关闭。
- 两种模式确认后 3.1 秒检查 B 连接已关闭、A/C 未关闭，再持续观测 350 ms：B 作为来源和目标均零新增交付；A/C 两方向各至少五次实际交付。旧发送任务必须继续尝试至少一百次，并收到服务端断开后的发送拒绝。
- 恢复实际 HTTP 转发后，旧票重新建立 QUIC 必须失败，代理确实收到真实控制面的 403，不能只以仍在黑洞时连接超时代替旧票拒绝。
- 发送成功仅统计本地 QUIC 接受；实际交付另在远端 receive 计数，二者不混同。异步观测任务结束时检查 panic，避免后台断言失败被吞掉。

新增 Rust 入口独立位于 `network/relay/tests/authority_loss.rs`，默认忽略，仅由具备真实数据库与匿名输入的 Java 父测试精确选中，不影响原 `control_plane`、`revocation` 入口。

## 证据

本机运行入口、脱敏日志均位于 Git 忽略的 `workspace/target/relay-authority-ddf0029/`，临时目录、Java home、Maven/Cargo 缓存与构建输出均定向到 D 盘；复用现有工具，不安装或下载依赖。

| 检查 | 证据 | 结果 |
| --- | --- | --- |
| 专项实际 HTTP/QUIC | `focused-proof.log` | Java 1 项通过、无跳过，offline 和 push-offline 两个 Rust 子进程实际执行 |
| Rust 组合 | `rust.log` | `cargo test --locked --offline -p zhijie-helper -p zhijie-relay -p zhijie-shell`：127 通过、10 个专用入口忽略、无失败；新增入口由 Java 实际执行 |
| Java 真实控制面组合 | `java.log` | `NetworkPeerServiceTest,NetworkPeerIntegrationTest,RelayOutboxIntegrationTest,RelayAuthorityIntegrationTest`：7 项通过、无失败、无跳过 |

最终 Java 组合的 offline 子日志为 `target/relay-authority-7682791941155957147/scenario.log`：撤销确认后旧端持续尝试 172 次，本地 QUIC 接受 99 次、拒绝 73 次；最终隔离窗口零新增交付，健康方向合计 36 次实际交付。五秒独立截止内完成，父端观测整个窗口为 3467 ms。

push-offline 子日志为 `target/relay-authority-13749127384870341060/scenario.log`：父端撤销确认后 109 ms 观测实际服务端关闭；旧端仍继续尝试 173 次，本地接受 5 次、拒绝 168 次；最终隔离窗口零新增交付，健康方向合计 36 次实际交付，父端观测整个窗口为 3473 ms。两种模式恢复 HTTP 后均记录旧票 403 和重连拒绝。以上发送接受数不是远端交付数，最终窗口用时也不是精确停流延迟。

原有十二端 direct、relay、mixed 生产 Helper/WireGuard/真实控制面各五轮续票组合通过，日志分别为 `target/network-room-13221836796614897989/rust-network.log`、`target/network-room-2878429521742564354/rust-network.log`、`target/network-room-14230307392023172734/rust-network.log`，用时 82.56、82.61、87.49 秒。原有持续恶意直连旧端、真实中继撤销和晚到旧 Outbox 场景也在该 Java 组合中通过。

结束日志 SHA-256：`java.log` 为 `937252ad078de3e3e7637fd42edf1161f08a9dab3ae1a37a281d685bd985de42`；`rust.log` 为 `a20e803502602d42bbdb0870e5176db609a6a33d1b1a52e8a7c2ec248641b6f8`。

仅含本次三项变更的暂存树 `4aa219efff8fd92c689a148ca082cef346508d29` 已归档并展开，在没有历史未提交变更的快照上通过工作空间、依赖清单、兼容矩阵、发布契约、性能夹具、三类 Cargo 许可材料检查及实际 Java/Rust 专项复验。`snapshot.log` SHA-256 为 `b563cd5b2844e599cc663040b193c0c12771cf87057cf3cb1cfea19503ec1ff4`。之后仅在本文追加本段结果，测试源码与已验证快照一致。

快照子日志另保存在 `target/relay-authority-ddf0029/snapshot-evidence/`，源归档 ZIP 与全部成败日志保留。确认进程退出、绝对路径未越界且无重解析点后，清理本轮 `temp`、`snapshot-temp` 和展开快照，释放 225,529,716 字节；保留共享缓存、交付物和既有文件。结束时 C 盘剩余约 10.16 GiB，D 盘约 38.55 GiB，清理记录为 `cleanup.log`。

首次组合探针的 offline 场景通过，push-offline 在派发前未运行同步 Outbox，数据库送达断言失败。实际生产查询只选择已同步事件；补齐测试编排的同步前置后复验通过，没有修改生产查询、绕过派发、放宽期限或覆盖失败日志。保留 `focused.log`，复验为 `focused-retest.log`，加入主动关闭时限和实际交付断言后的专项复验为 `focused-proof.log`。

## 限制与原则

本回归是同机真实 HTTP/QUIC 的授权、传输与撤销组合验证。载荷为测试字节串，中继不解析其内容，不等于真实 TUN、跨机 NAT、WireGuard 数据包、游戏或音频验收；不将最终观测窗口当作精确最后停流时间。既有真实十二端 Helper/WireGuard 组合仍需保留回归。未运行原生桌面、安装器，不修改防火墙或安装驱动。

KISS/YAGNI：不引入生产故障开关、协议或依赖。DRY：复用生产签票、HTTP 校验、QUIC、Outbox 及管理撤销，不在测试代理复制授权规则。单一职责：Java 负责事务、真实 HTTP、独立期限和脱敏；Rust 负责持续流量、连接关闭、双向实际交付及重连观测。
