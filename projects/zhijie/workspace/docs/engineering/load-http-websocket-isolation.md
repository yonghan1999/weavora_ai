# 百账号压测的 HTTP 与 WebSocket 连接池隔离

## 实际失败与确定性复现

`target/sync-delivery-diagnostics-cc4cc7d/rotation.log` 在未改变负载行为时失败。`target/control-load-729547659389729238/` 记录 3,785 次成功 HTTP、51,225 次同步、两次同步错误，指定补发仅 9/10。账号索引 1 刷新返回 200 后，七毫秒内重连失败；客户端记录 `opened=false`、`httpStatus=null`、`transportCode=OTHER`、1006。服务端同端口 41569 的记录有 99 次 HTTP 200，随后第 100 次请求返回 101；因此不能将该次失败解释为限流、凭据拒绝或连接未到服务端。

当前本机 Node v24.20.0 / 内置 Undici 7.29.0 的 `fetch` 与 `WebSocket` 使用同一默认传输池。`SyncKeepAliveIntegrationTest` 启动真实 Spring/Postgres，匿名 stdin 将临时凭据交给 `test-sync-keepalive.mjs`：先独立核对同一 TCP 连接的第 100 次升级返回 `Connection: Upgrade` 及 `Connection: close`；再直接提取并执行负载器现有 HTTP 函数，连续 99 次请求后启动原生 WebSocket。

临时恢复原 `fetch` 实现时，`shared-pool-before.log` 稳定失败，子目录 `target/sync-keepalive-12085326297561036940/probe.log` 为固定 `ISOLATED_UPGRADE_FAILED`。隔离后同一检查通过，既不增加握手重试，也不更改服务端默认 100 次 keep-alive 边界。`keepalive-before.log` 是早期“升级不应包含 close”假设检查的失败记录，不能当作产品已修；最终解决的是压测客户端混用传输池，不是修改 Tomcat 或生产同步协议。

独立本机重复响应试验 `probe-upgrade.log` 同样得到握手失败与 DOM `AbortError` 数值 20；原诊断将其归为 `OTHER`。最终仅为这个已观察到的错误增加固定 `ABORT_ERROR` 分类，未知错误仍为 `OTHER`，不输出异常正文、URL、请求头或凭据。试验自身服务退出等待未完成的警告保留，不能称为完整生命周期验收。

## 修复范围

负载器的 HTTP 请求改用 Node 内置 `http.Agent`，仍使用 keep-alive；WebSocket 保留原生实现。HTTP 和 WebSocket 不再共享连接，符合现有 Rust 产品客户端 HTTP/同步分别建连的边界。复用既有请求分类、五秒信号期限、逐账号工作、序号严格递增、续期、指定消息补发及零错误断言；没有放宽限流、授权、服务端超时或容量阈值，没有切换生产客户端或新增依赖。

`LoadHttpTransport` 只负责 HTTP 请求、完整 UTF-8 正文收集及关闭本轮连接池；测量和业务断言仍归负载器（单一职责）。最小封装避免在负载器和测试重复传输实现（KISS、DRY），不增加后台重试、无限授权或第三方客户端（YAGNI）。

`test-load-http-transport.mjs` 实测 HTTP 状态保留、UTF-8 POST、keep-alive 复用、WebSocket 独立端口、超时和中断响应。`test-load-sync-diagnostics.mjs` 保留原有事件、回放、刷新、并发 403/429/断流检查，并验证 `AbortError` 分类及未知值脱敏。

## 验证记录与边界

### 一小时实跑中发现的 HTTP 诊断缺口

从 `d54f025` 导出的测试源码在 `target/hour-audit-d54f025/source/target/control-load-15831610034985500261/` 写入一小时负载记录。以下仅描述该次运行前 255 秒：10,491 个请求中，20 个在约第 112～116 秒记为 `TRANSPORT_FAILED`；另有 7 个消息读取在约第 195 秒收到 HTTP 429、`RATE_LIMITED`。前一组附近有连续十个 JVM 样本为数据库活动连接 10、空闲 0。这是时间相关性，不足以认定慢 SQL、连接池配置或客户端超时是根因；后一组有明确服务端响应，不能与前者合并归因。本轮不调整生产限流、连接池或五秒请求期限。

后续版本仅在原始记录中区分已证实的本机 HTTP 请求期限触发（`HTTP_DEADLINE_EXCEEDED`）和连接重置（`HTTP_CONNECTION_RESET`）。未知异常及普通主动取消仍为 `TRANSPORT_FAILED`，不写入异常原文；三者均算失败。`node "scripts/test-load-http-transport.mjs"` 覆盖真实回环请求和脱敏边界。另直接执行负载器的 `request()`，得到上述两类传输错误及 `429/RATE_LIMITED`，三次均计为失败，记录见 `target/hour-audit-d54f025/http-failure-diagnostics.log`。旧记录没有原始异常，不能回填新分类；此项修复的是可观测性，不是容量问题。

### 此前的连接池隔离回归

开发证据位于 D 盘 `target/sync-delivery-diagnostics-cc4cc7d/`；未修改工作流受控元数据，不代替正式 F/G/H/I。

- `shared-pool-before.log`：原共享池函数在真实服务下失败；`keepalive-isolated.log` 隔离后通过。最终综合回归再次运行该测试。
- `http-unit-final.log`、`client-unit-minimal.log`：HTTP 传输及客户端分类检查通过。
- `regression.log`：二十七项通过，零失败/错误/跳过，总耗时六分二十五秒，覆盖三类百账号、连接池边界、服务端诊断、同步服务/Handler、真实会话、回放及提交顺序。
- 续期 `target/control-load-15025243912784987624/`：九十秒、3,809 次 HTTP、51,440 次同步、十次指定补发，每账号至少一次刷新，HTTP/同步错误均零，p95 5.64 毫秒。
- 十轮重连 `target/control-load-6629937955346664257/`：九十秒、3,797 次 HTTP、60,540 次同步、一百次指定补发，HTTP/同步错误均零，p95 5.68 毫秒。
- 普通 `target/control-load-7415012376759792380/`：九十秒、3,708 次 HTTP、51,840 次同步、十次指定补发，HTTP/同步错误均零，p95 3.93 毫秒。
- 三类 `rotation-audit.log`、`churn-audit.log`、`ordinary-audit.log` 对逐账号核心操作独立核对，最大间隔 20.065、20.066、20.064 秒；`activity-unit.log` 五项通过。
- 本轮百账号未出现实时发送告警；三条分类日志均位于 `SyncDeliveryDiagnosticsTest` 的故障注入期间，不据此关闭历史发送告警问题。
- `native-and-pool-final.log`：最终最小错误分类代码下，真实 Rust 客户端实时接收、断线补发、刷新后重连及连接池边界两项通过，零失败/错误/跳过，未启动桌面窗口。
- `performance.log`：容量契约检查通过，仅校验要求，不是容量验收。
- `workspace.log`、`compatibility-final.log`、`release.log`：工作区、兼容和发布契约通过，真实 macOS 签名检查仍跳过。初次 `compatibility.log` 缺少 `--validate-matrix` 而失败，修正命令后重跑，没有修改契约规避。

本轮一次明确的握手故障已能由共享池/第 100 次请求边界解释，不能据此回溯断言所有历史握手失败都同因。服务端历史发送告警、旧 429、网络 pump 不可达及其他交付缺口仍独立开放。原生桌面与安装器未启动，邮件默认关闭，用户搁置的音频问题不处理；同机九十秒不能替代目标二核/2 GB/5 Mbit/s 一小时、跨机 TUN、三平台及最终人工验收。现有试用 ZIP 不含本轮新增服务端诊断，其业务行为不受本测试工具修复影响，不重复打包。
