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

## 再次提前退出与 HTTP 诊断

从 `5156c86` 工作区进行一次有退出诊断的定向复跑，`target/network-pump-followup-5156c86/real-control-plane.log` 一项失败、无跳过。直连 `target/network-room-734348471800023582/rust-network.log`（83.41 秒）和中继 `target/network-room-2680951670342978971/rust-network.log`（88.82 秒）完成五轮续票；混合模式 `target/network-room-1623909744320736910/rust-network.log` 在 65.49 秒失败。

混合模式成员 1、0 首先于 65,100 / 65,102 毫秒报告 `CONTROL_PLANE_UNAVAILABLE`，均为阶段 5、最近请求续票轮次 4，早于夹具因通道关闭而终止。其余 `TASK_DROPPED`、`TUN_WRITE_FAILED` 和 `OK` 发生在夹具清理期间，不能倒因为最初退出原因。这次证据将范围缩小到控制面 HTTP 请求失败或非成功响应，尚不能区分超时、连接问题或服务端错误；也不能追溯认定此前直连 42.27 秒故障具有同一根因。

新增 `authority_diagnostics.rs`，仅由 `cfg(test)` 加载。十二端夹具给各自 `NetworkAuthority` 设置零起始成员编号；发送失败、HTTP 非成功状态和正文解码失败分别输出 `REAL_NETWORK_AUTHORITY_FAILURE`，仅包含成员编号、固定操作/错误分类、数值状态码及本次 HTTP 耗时。未知传输错误统一分类，不格式化原始异常、URL、请求、响应正文、票据或私钥。未设置诊断编号的其他测试保持静默；生产编译不包含诊断字段、计时或输出。

HTTP 默认连接一秒、总两秒、每秒轮询、旧授权截止和失权即停均不改变；没有增加重试、宽限、缓存授权或错误成功化。原有真实 HTTP 测试增加 429 和 503 响应，验证仍分别返回 `NETWORK_GRANT_INVALID` 和 `CONTROL_PLANE_UNAVAILABLE`。新测试以包含敏感查询参数及非 JSON 正文的本机 HTTP 服务验证输出只含白名单，覆盖解码、超时、429 和 503。首两次 Helper 开发运行因额外假定关闭端口必定得到特定连接错误而失败，保留 `helper.log`、`helper-fixed.log`；移除该不稳定端口假设后重跑，不把它当产品故障或以修改生产错误分类规避测试。

诊断重跑 `target/network-pump-followup-5156c86/diagnosed-control-plane.log` 一项通过、零失败/错误/跳过（286.6 秒）。对应直连 `target/network-room-2166050524723295587/rust-network.log`（83.20 秒）、中继 `target/network-room-1265678375399260079/rust-network.log`（85.86 秒）、混合 `target/network-room-2457959672142893724/rust-network.log`（87.71 秒）。每种模式五轮续票、原票过期拒绝、单端退出、剩余端交付及策略撤销全部执行；三份各十二条退出，阶段 0～5 提前退出均为零。HTTP 诊断仅出现预期撤销时的 403，没有复现不可达。

同轮仅测试进程启用脱敏 Tomcat 访问日志（时间、状态、耗时）和 GC/安全点日志，均落 D 盘。`access-diagnosed-control-plane.log/access_log.2026-11-20.log` 共 30,150 个响应：30,112 个 200、38 个 403，无 429/5xx；HTTP p99 为 32,762 微秒、最大 1,796,012 微秒；`gc-,level,tags` 最大安全点停顿 97.4067 毫秒。GC 文件名因一次性脚本变量边界误写而使用上述实际名称，内容有效，脚本已修正后续名称。这些只描述成功重跑，不能排除失败轮次中的控制面停顿或连接故障。

`helper-final.log`：Helper 46 通过、0 失败、3 默认忽略；原有慢权威、迟到续票与授权截止测试仍通过。`production-check.log`：无测试诊断的 Helper 库检查通过。`format-final.log`、`workspace.log`、`compatibility.log`：格式、工作区和兼容契约通过；兼容契约仍明确平台、游戏、签名、音频和容量未验收。以上日志均位于 `target/network-pump-followup-5156c86/`，复用 D 盘共享缓存。新模块负责分类与脱敏，权威校验保留原职责（单一职责）；复用原请求与真实夹具，不增加依赖、协议或生产恢复策略（KISS、DRY、YAGNI）。

本轮完成的是诊断能力及可重复验证，不是上述偶发故障修复；不更新试用包、不关闭缺陷、不推进 TASK-019～023 或 F/G/H/I，不处理用户已搁置的人工试音。

最终固定源码回归 `final-control-plane.log`：`NetworkPeerIntegrationTest` 三项和 `RateLimitFilterTest` 两项，共五项通过、零失败/错误/跳过。三模式子日志为 `target/network-room-12272455701883607968/rust-network.log`（直连 83.55 秒）、`target/network-room-8406211374543101546/rust-network.log`（中继 85.65 秒）、`target/network-room-2516153671959931437/rust-network.log`（混合 87.44 秒），均完成五轮续票、原票过期、退房及策略撤销；没有阶段 0～5 的提前退出。最终访问日志共 30,070 个响应：30,027 个 200、43 个 403，HTTP p99 28,480 微秒、最大 279,234 微秒；`gc-final-control-plane.log` 最大安全点 87.4769 毫秒。成功回归仍不消除首次提前退出风险。

`release.log` 发布配置契约通过，但真实 macOS 签名夹具明确跳过，安装器仍未验收。测试进程全部退出后，经解析绝对路径核对仅删除本轮专用 `target/network-pump-followup-5156c86/temp`，实际清理 102,438,303 字节；日志、访问/GC 记录、环境脚本及共享缓存保留。未重试清理此前被策略拒绝的其他目录，未读取根目录 `tmp/`，未操作用户试用数据库或系统网卡。
