# Helper 权威校验与数据转发并行

## 缺陷与范围

基于 `6a5f187` 修复 Helper 数据泵在每秒轮询、Peer 更新和续票分支内直接等待 HTTP 的问题。原实现等待最长两秒，期间不能处理 TUN/UDP/QUIC、Stop 和定时器。修复前的两个回归真实失败：等待 HTTP 响应时，300 ms 内不能输出实际 WireGuard 握手 UDP，也不能完成 Stop 并释放持有的受控 TunnelDevice。

本次是已批准网络隔离、撤销和独立恢复范围内的实现修复，不修改需求、设计基线或验收目标，不把开发回归记作 F/G/H/I 正式完成。

## 行为与安全边界

- 将普通权威检查和续票检查保存在数据泵内，由同一个 `select!` 与转发、Stop 并行推进；不创建脱离会话生命周期的后台请求。
- 最多一个普通检查和一个续票检查同时进行。Peer 命令复用普通检查，只保留一个等待回复；重复 Peer/续票命令有界返回既有错误，不无限积压 HTTP。
- 使用严格有界的已验证快照：单调时钟截止为该次验证请求建立时加三秒，不能按响应完成时间重新计时。初次验证也从请求前开始计时，创建网卡和等待 Relay 不延长窗口。
- 数据泵入口、结果应用前、等待和逐包写入均受快照截止约束；原票据墙钟到期、Peer 自身到期和整个写入批次最多 100 ms 的限制继续有效。HTTP 仍保持一秒连接、两秒总超时。
- 当前票据轮询或 Peer 检查明确拒绝、失败时停止数据泵并释放持有设备，不等快照窗口耗尽。仅在 HTTP 尚未返回且旧快照仍有效时继续转发，不宣称“等待时完全不使用缓存”或离线无限可用。
- 新票续签被拒不代表旧票必然已被撤销，沿用原行为返回续票错误；旧票仍须独立轮询并受原到期和快照截止约束，续票失败不延长任何旧窗口。
- 续票成功取消旧票的未完成轮询，之后仅验证新票，迟到旧结果不能覆盖新票。旧票到期之前未成功完成的续票不能使会话复活。
- 本地 Peer 参数仍不能覆盖服务端权威列表。数据泵收到 `Command::Stop` 后丢弃在途请求，不等待 HTTP；启动期首次 Relay 连接仍需单独等待。同步后端接口原有的互斥锁和回复等待未在本次改动中调整，不将数据泵的 300 ms 测试结果扩展为整个 IPC/后端 Stop 调用的时限承诺。

## 自动化验证

所有路径相对于项目 `workspace/`，日志与临时文件位于 D 盘 Git 忽略的 `target/authority-responsive-6a5f187/`，不包含真实业务凭据。

| 检查 | 命令或日志 | 结果 |
| --- | --- | --- |
| 修复前复现 | `before.log` | 两个预期失败，保留原始记录 |
| 初步修复 | `fixed.log`、`boundaries.log` | 两项及五项边界测试通过，仅代表当时版本 |
| 最终边界回归 | `final-boundaries.log` | 11 项通过；随后对纯截止测试的夹具做了隔离调整，以最终全量结果为准 |
| Helper/Relay/Shell 全量 | `cargo test --locked --offline -p zhijie-helper -p zhijie-relay -p zhijie-shell`；`final-rust-regression-retest.log` | 119 通过、8 个专用集成入口忽略、0 失败 |
| Helper/Relay 可执行程序 | `cargo build --locked --offline -p zhijie-helper -p zhijie-relay`；`final-build.log` | 成功 |
| 真实控制面组合回归 | `NetworkPeerServiceTest,NetworkPeerIntegrationTest,RelayOutboxIntegrationTest`，`ZHIJIE_TEST_RELAY_MEDIA=true`；`final-java-regression-retest.log` | 5 项 Java 测试通过，含 3 种十二端 Helper 子测试及真实 Relay 撤销 |

11 项新测试覆盖 HTTP 等待期间真实握手 UDP 输出、Stop 与设备释放、HTTP 超时终止、明确拒绝立即终止、Peer 参数权威性与重复请求上限、续票等待时转发和 Stop、旧票迟到拒绝隔离、初始硬截止、慢成功响应不延长窗口，以及拒绝续票不能延长原快照。

扩展夹具最初因 Windows 非阻塞 accepted socket 读取失败，显式切回阻塞后重跑通过。首次最终全量回归另发现纯时间测试的未握手 Peer 会触发独立的 `DIRECT_UNAVAILABLE`，已让该时间测试使用空 Peer 列表，避免混淆两种截止；原有真实 UDP、完整十二端和撤销测试保留。失败日志分别保存在 `expanded-boundaries.log` 和 `final-rust-regression.log`，未覆盖或删除。

最终 Java 集成第一次执行与 Cargo 回归、构建并发，子进程等候共享构建锁，未能在原有端点就绪期限内启动，记为失败并保留 `final-java-regression.log`。没有放宽测试期限或跳过用例，待构建全部结束后独立重跑。

最终串行集成重跑退出码为 0，5 项 Java 测试无失败、无跳过。真实控制面签票、数据库和 HTTP 验权配合 Helper 数据泵及 QUIC Relay，分别在 direct、relay、mixed 十二端场景完成五轮续票、跨原票期限继续传输、退出成员隔离及策略撤销；每轮十二端检查 132 个方向各 10 包，移除一端后检查其余十一端的 110 个方向。三个 Helper 子测试分别用时 82.61、82.67 和 87.41 秒，日志为 `target/network-room-8190536397669029451/rust-network.log`、`target/network-room-10876863260443421946/rust-network.log`、`target/network-room-16731421999884673723/rust-network.log`。另有真实 Relay 已建立连接撤销及旧 Outbox 事件隔离回归，不能仅按 Java 测试项数理解覆盖范围。

已结束日志的 SHA-256：

- `before.log`：`afe94e49ebffffe25ae42232cfaabec5960f7fafc10c71ccfec578fa592b1e49`。
- `final-rust-regression-retest.log`：`22d246f9e07c0f9948b79b922affddd4300b84488f3df4f2f344a71353a7c7ed`。
- `final-build.log`：`fe3216681895e65c8f280af585f0d2d131ec035aca4d49608447a4c09e8259a5`。
- `final-java-regression-retest.log`：`02fd79b041297a63db23a6337a046ed4b26aea1c345b27e79c188251f023dc9e`。

## 尚未替代的验收

- 同机受控 TunnelDevice 和实际 UDP/QUIC 测试不等于真实 TUN、跨机 NAT、游戏、弱网或目标容量验证。
- 三秒快照上限不等于已经证明所有环境都满足服务端确认失权后五秒的端到端目标；主动撤销通知、失联、系统挂起与真实设备场景仍需验收。
- Windows/macOS/Linux 安装更新卸载、签名、音质、许可人工审核和正式 F/G/H/I 流程仍保持开放。
- `d9a9dfe` Windows 试用包不包含本次修复。后续已从 `0ce6df5` 干净归档实际构建新桌面/Helper，并通过优化版 61 项 Helper 单测、制品内容核对、170 项提取界面浏览器检查及新整包登录/重启/冷备恢复。新入口为 `target/trial-kit-0ce6df5/zhijie-windows-x64-trial.zip`，SHA-256：`310f9c998f2061934750b81dbab8851b78138bbbf7b24c903b1971e158b80581`；未变化的后端直接复用原包，完整记录见 `packaging/windows/candidate-build.md`。这些验证不替代本节列出的实机和正式阶段验收。

## 原则应用

KISS/YAGNI：不引入通用调度器、后台服务或新的配置协议。DRY：普通和续票请求共用 `AuthorityCheck`，Peer 刷新复用普通检查。单一职责：请求等待状态与数据路由职责分离，继续使用现有授权验证和路由校验入口，不复制安全策略。
