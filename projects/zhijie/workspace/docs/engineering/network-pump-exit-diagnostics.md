# 十二端数据泵退出诊断

## 背景与边界

`target/network-room-17424031317188456584/rust-network.log` 曾在直连第二轮续票后报告 `renewal must preserve every pump`，42.27 秒失败。当时未记录退出成员、原因或时间，无法据此判断是授权、控制面可达性还是数据链路问题。后续复跑成功不代表原偶发故障已修复，根因仍开放。

本次仅修改 Helper 的测试夹具，不改变生产数据泵、授权轮询、票据截止、撤销时限、网络策略或测试超时；不重新打包试用制品，不推进阶段或关闭 TASK-019～023。

## 实现

`full_room_control_plane_tests.rs` 为每个真实生产数据泵增加测试专用的 `PumpDiagnostic`。退出时输出单行 `REAL_NETWORK_PUMP_EXIT`，包含经过白名单校验的模式、零起始成员编号、当前传输阶段、最近请求的续票轮次、从数据泵初始化开始的毫秒数和固定退出分类。

正常返回保留其原始结果；异常取消或展开时以 `TASK_DROPPED` 标识，不将清理导致的取消误认为原始故障。诊断由析构触发，不额外创建监控任务或改变调度、重试与失败判定。轮次标记在通知父进程前更新，因此等待新票期间发生的退出也能定位。

`full_room_diagnostics.rs` 集中白名单与格式化（单一职责、DRY）。未知错误一律输出 `UNCLASSIFIED_ERROR`；不记录原始错误、请求、地址、身份、票据或密钥。新增测试检查阶段与轮次更新、已知分类、正常结束、取消分类以及含凭据或换行的未知错误不会进入日志。无新增依赖或生产行为调整（KISS、YAGNI）。

## 本机开发验证

所有运行新增临时文件均位于 D 盘 `target/network-pump-diagnostic-d8ffab9/temp`，Cargo/Maven 复用 D 盘已有缓存；仅设置当前进程环境。正式执行证据仍须由工作流 CLI 生成，本节是直接开发回归记录，不替代阶段门禁。

- `cargo fmt -p zhijie-helper -- --check`：通过；日志 `target/network-pump-diagnostic-d8ffab9/format.log`。
- `cargo test --offline -p zhijie-helper --lib`：45 通过、0 失败、3 默认忽略；日志 `target/network-pump-diagnostic-d8ffab9/helper.log` 及最终复核 `helper-final.log`。默认忽略的真实控制面夹具由下项父测试显式启用。
- 离线 Maven `-pl control-plane -Dtest=NetworkPeerIntegrationTest test`，启用 `ZHIJIE_TEST_RELAY_MEDIA=true`：3 通过、0 失败/错误/跳过，294.2 秒；日志 `target/network-pump-diagnostic-d8ffab9/real-control-plane.log`。
- 真实控制面直连：`target/network-room-11531091910020401800/rust-network.log`，83.64 秒。
- 真实控制面中继：`target/network-room-17582346913356031204/rust-network.log`，87.18 秒。
- 真实控制面混合：`target/network-room-17698256328600991075/rust-network.log`，87.37 秒。

三种模式各五轮续票、十二端 132 方向交付、原票过期拒绝、退房后十一端 110 方向交付及策略变更五秒停止均通过；混合模式关闭 Relay 后继续直连。各日志均有十二条退出诊断，全部发生在预期撤销阶段，没有阶段 0～5 的提前退出。原故障本轮未复现，不据此关闭缺陷，也不进行无证据的生产重试或放宽授权边界。

测试完成后已尝试清理本轮专用临时目录，但删除命令被工具安全策略拦截，未改用其他方式绕过；约 98 MiB 临时文件仍保留在上述 D 盘目录。测试日志、可复现环境脚本、共享缓存及交付物均保留，未删除其他运行的目录。以上使用真实 Spring、Postgres、UDP、QUIC、WireGuard 和生产数据泵，但网卡仍为受控通道，不能替代跨机真实 TUN、真实通话、多平台安装或目标服务器验收。
