# 真实控制面下持续旧端发送与失联隔离

## 范围与动机

基于 `5f37ddd` 增加 AC-021、AC-026、AC-042 相关开发回归。原真实控制面十二端回归会等待失权 Helper 自行退出；本回归不运行旧端的授权轮询或 Stop，保留其真实 WireGuard 会话、UDP socket 和旧密钥持续发送，避免将客户端自愿停机误当接收侧强制隔离。

本次仅修改测试与对应工程说明，不修改生产实现、需求、架构、配置或协议；不据此完成正式任务或 F/G/H/I 验收。

## 真实运行路径

Java 使用实际 PostgreSQL、Spring 控制面、成员加入、签票、端点登记与离房事务。两份短期票据和测试私钥经匿名标准输入传给 Rust 子进程，不落入文件；公开端点、阶段标志和脱敏日志留在 D 盘 Git 忽略的 `workspace/target/`。

Rust 接收侧运行生产 `run_data_plane`，设备使用可观测释放的受控 TunnelDevice；发送侧使用生产 TunnelRouter、boringtun 与真实 UDP，但故意不轮询、不接收退出命令。两端先完成真实双向加密载荷交付，然后父端通过实际 `ActivityCoordinator.leave` 撤销发送端，事务返回后才开始五秒独立截止并发出确认标志。

- `online`：接收端继续实际 HTTP 验权并更新 Peer。撤销后确认接收数据泵仍运行、设备仍持有、正常读取本地输入，但旧端持续发送的 30 个实际会话密文不能到达受控设备；反向目标数据也不能交给旧端。
- `offline`：初始授权仍来自真实控制面，接收数据泵的后续 HTTP 改接真实 TCP 黑洞。黑洞保留已接收连接、不返回响应。撤销确认时数据泵尚在运行，随后必须在有界授权窗口内以 `CONTROL_PLANE_UNAVAILABLE` 退出、释放设备并关闭输入；旧端仍持续发送，不能交付。
- 两种情况随后重建旧端 WireGuard 状态，统计真实新握手已发送；旧资格不能重建通信。在线模式最后正常 Stop 并检查设备释放。
- Rust 要求确认后三秒一百毫秒起检查隔离、四秒五百毫秒内完成旧会话与新握手观测；Java 独立要求真实服务端撤销确认后五秒内收到隔离标志。期限未放宽，发送量来自实际 UDP 发送成功计数而非仅入队。

## 验证结果

主日志位于 `target/hostile-peer-5f37ddd/`，路径相对于项目 `workspace/`。

| 检查 | 证据 | 结果 |
| --- | --- | --- |
| Helper 测试编译 | `compile.log` | `cargo test --locked --offline -p zhijie-helper --lib --no-run` 通过 |
| 单独真实控制面探针 | `focused.log` | 1 项 Java 测试、online/offline 两个实际 Rust 子测试通过 |
| 组合真实控制面 | `java-regression.log` | `NetworkPeerServiceTest,NetworkPeerIntegrationTest,RelayOutboxIntegrationTest`，`ZHIJIE_TEST_RELAY_MEDIA=true`：6 项通过、无失败、无跳过 |
| 首次 Rust 完整回归 | `rust-regression.log` | 127 通过、9 个专用子进程入口忽略、无失败；之后仅增加撤销确认时设备仍活跃的测试断言，最终代码另行完整复验 |
| 最终 Rust 完整回归 | `final-rust-regression-retest.log` | `cargo test --locked --offline -p zhijie-helper -p zhijie-relay -p zhijie-shell`：127 通过、9 个专用子进程入口忽略、无失败 |

最终 Rust 复验第一次从命令行向 PowerShell `-File` 传递逗号数组，被当作单个 Cargo 子命令并在测试前失败，保留 `final-rust-regression.log`。改由本地脚本传递明确参数数组后执行成功，没有跳过测试、放宽期限或覆盖失败日志。

最终组合的旧端 online 日志为 `target/network-hostile-11976398725670058272/rust-network.log`：30 个旧会话数据报、新握手实际发出、零交付，隔离观测用时 4059 ms。offline 日志为 `target/network-hostile-6133127737242711544/rust-network.log`：相同发送与拒绝条件通过，用时 3791 ms。时间是观测整个测试窗口的用时，不是精确最后停流时间。

原有 direct、relay、mixed 十二端真实控制面回归也通过，各五轮续票，覆盖原票期限后 132 方向交付、移除成员后 110 方向交付、旧策略会话五秒退出及混合模式 Relay 退出后直连继续。日志依次为 `target/network-room-2264312045552143715/rust-network.log`、`target/network-room-12274484229340780268/rust-network.log`、`target/network-room-9102298796890237365/rust-network.log`，用时 82.61、82.60、87.36 秒。组合中另含真实 Relay 已建立连接撤销与旧 Outbox 隔离。

结束日志 SHA-256：

- `java-regression.log`：`a071dee4fbb70c2a9f307955dacef7c11d132a9eff644975c7aaabc0eaa3ce6f`。
- `final-rust-regression-retest.log`：`beaab53e9bc576cfca7812023ee17a3826d511b10d0979d824b12f75346116d5`。
- online 子测试：`5bbaf75b9afe09da41ad44a3702f342b81a7f94c808a48ca109bfe9bd2a86b9b`。
- offline 子测试：`15b6cb19ed9a186a165fa29ee11fabd149be73d8a356f8f020d46a370ca9538e`。

## 不足与后续

本回归只证明同机实际控制面与遵守接收侧校验的生产数据泵，在持续旧端发包及受控 HTTP 黑洞条件下的隔离，不保证两个均恶意的直连端能由服务器强制擦除其已知密钥或停止私下通信。不等于真实 TUN/跨机 NAT、所有撤销原因、主动通知、系统挂起、弱网、真实游戏或媒体联合五秒目标验收。

本次测试提交没有重建试用包。后续已从 `8da8814` 干净归档实际重建桌面/Helper，将 `5f37ddd` 首连修复纳入新整包 `target/trial-kit-8da8814/zhijie-windows-x64-trial.zip`，SHA-256：`3abd8fe20686ae595f45228844e55ce050b28ccb4386a2d9af6bfc825c8135c6`；优化版测试、包内容、提取界面及后端生命周期回归记录见 `packaging/windows/candidate-build.md`。三平台实机、人工试音、目标服务器容量、许可审核及正式工作流继续开放。

## 原则应用

KISS/YAGNI：不为测试引入生产故障开关、协议或依赖。DRY：复用已有 ControlledTunnel、IPv4/UDP 报文、Pumps 退出保护和生产路由；测试编排与生产授权策略分离，不复制服务端鉴权逻辑。单一职责：Java 负责真实撤销事务与独立截止，Rust 负责实际密文、流量与设备生命周期观测。
