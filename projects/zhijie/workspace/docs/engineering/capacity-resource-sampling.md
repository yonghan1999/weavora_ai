# 容量测试的进程与网卡采样

`scripts/sample-capacity-resources.py` 是测试机工具，不进入产品发行包。它只读所选 PID 的 CPU 用户/内核累计秒、RSS、文件描述符（Windows 为句柄），以及指定网卡累计发送/接收字节。依赖 Python 3 和 psutil；本机用 3.13.5 / 7.2.2 验证。无需账号、数据库密码或读进程命令行，不配置网卡、限速、路由或防火墙。

## 独立采样

容量比较使用十进制：5 Mbit/s = 5,000 kbit/s = 625,000 字节/秒。C0～C4 配置原用 5,120 kbit/s，与需求及采样器单位不一致，可能放行最多超出 2.4% 的样本。修正后，上下行分别比较，不取平均或相加；等于 5,000 不超限，任一方向 5,000.001 即超限。此处是候选预算，不是目标服务器已实测的上下行配额。实际限速方向仍须核对，回环或应用正文不能替代网卡证据，不回填历史测量。

通过 `scripts/run-performance.mjs` 或 `scripts/run-compatibility.mjs` 记录一次运行时，每次请传入新的 `--output` 文件名。报告、同名 `.stdout.log` 或 `.stderr.log` 中只要有一个已存在，运行器就拒绝启动适配器，保留原始记录。遇到中断留下的空文件也应换名重跑，而不是覆盖前一次结果。

兼容性运行器必须在目标系统上执行。`--platform` 与运行器所在系统不符时，不启动适配器，也不生成报告或日志。报告的 `host` 记录本地 Node 平台与架构、系统报告的机器架构及内核版本；它不证明远端设备、特定发行版或安装升级已通过验收，也不改变支持矩阵。

先在对应非生产测试机核实需要监测的实际 PID 和承载负载的接口名，可用 `python -c "import psutil; print(list(psutil.net_io_counters(pernic=True)))"` 枚举接口。下例 PID 和接口仅为占位；`--pid` 可重复，不能重复计入同一 PID。使用已存在的父目录和新的输出目录，避免覆盖历史结果：

```sh
python "scripts/sample-capacity-resources.py" --pid 1234 --pid 5678 --interface "eth0" --duration 3600 --interval 1 --output "target/c1-host-run-001"
```

每次采样立即写 `samples.jsonl`，含 UTC、单调时间及各 PID 累计值。`summary.json` 的 CPU 使用一个满核 = 100%；RSS 是所选进程 RSS 之和（不去重共享页）；速率为指定接口两次累计字节之差 × 8 / 1000 / 实际经过秒数。峰值是采样区间峰值，不是瞬时峰值。PID 退出/身份变更、计数倒退或读取异常时退出非零，保留已写记录，不生成成功峰值。计数器未变化时，不能用记录中的 0 证明业务没有网络流量。接口统计还包含无关流量，不是业务分项或物理链路总开销。

## 本机 C0：零账号空闲与固定请求

需要 Windows 或 Linux 本地测试机、可用的回环接口和已安装 psutil 的 Python。设置 `ZHIJIE_TEST_CONTROL_BASELINE=true` 和 `ZHIJIE_TEST_RESOURCE_INTERFACE`（本机为 `Loopback Pseudo-Interface 1`），可用 `ZHIJIE_TEST_PYTHON` 指定解释器。从 workspace 执行 `./mvnw.cmd -o -pl control-plane "-Dtest=ControlPlaneBaselineIntegrationTest" test`；不开启该测试时会跳过。场景使用临时 PostgreSQL、随机本地 HTTP 端口和零账号，不接触试用数据库。等首条资源样本落盘后，前 30 秒没有业务 HTTP；后 30 秒每秒一次 `GET /actuator/health`，要求 HTTP 200 且状态为 UP。两段均每秒查询当前数据库的连接数，该查询本身也占用连接，所以“空闲”并非完全没有数据库活动。

本机记录：`target/control-baseline-12757177290913580339/control.jsonl`（60 条），同目录 `host-observation/samples.jsonl`（61 条、60.47 秒）；Maven 日志 `target/baseline-validation-20261120-031435/maven.log`（1 项通过、0 跳过）。30 次健康请求成功，按延迟升序取第 29 个样本的 p95 为 7.76 ms；60 次数据库连接观测均为 10。测试 JVM（含 Maven 和 Spring）的区间 CPU 峰值为单核的 58.96%，RSS 峰值 528.95 MiB，句柄峰值 718；这些值不包括 PostgreSQL 子进程。回环网卡双向计数均未变化，不能推导流量为零。本结果用于对比同机后续负载，不是目标服务器基线或任何容量验收结论。

## 与本机百账号用例协同

设置 `ZHIJIE_TEST_CONTROL_LOAD=true`，从 workspace 运行 `./mvnw.cmd -o -pl control-plane "-Dtest=ControlPlaneLoadIntegrationTest#hundredVerifiedAccountsRunRealHttpAndSyncWithPresenceRecovery" test`。所有百账号用例都要用 Python 3 标准库审计实际 HTTP 活动；默认使用 `python`，可用 `ZHIJIE_TEST_PYTHON` 指定解释器。只有设置 `ZHIJIE_TEST_RESOURCE_INTERFACE` 启用网卡采样时才需要 psutil。启用后，台架先确认采样器已写首条样本，再启动业务流量；负载结束后检查采样子进程、截止原因及覆盖时长。`host-observation/` 与负载 `summary.json`、`operations.jsonl`、`resources.jsonl` 同属于一个新运行目录。

### 逐账号复核 HTTP 活动

**一小时失败基线（`d54f025`）：** `target/hour-audit-d54f025/source/target/control-load-15831610034985500261/` 的 `summary.json` 记录 3,600.036 秒、149,677 次 HTTP、27 次失败、p95 9.255 ms。20 次无响应的传输失败和 7 次 HTTP 429，根因不能合并；虽然每账号至少续票四次、十次指定消息补发成功，但本机零错误门槛失败。`operations.jsonl` SHA-256：`e1b01d6b6f12d2f21753f1b7b206ac1dc8e130b616d5d58f8e310ded0861bf68`。逐秒核对 `body-traffic.jsonl` 三列之和与汇总一致；以客户端收到的 HTTP 响应正文与同步正文相加，全程均值 1.761 Mbit/s，前 3,600 个完整秒中有 156 秒超过 5 Mbit/s，峰值 7.219 Mbit/s。这是正文在客户端被计入的时刻，不是物理链路发送速率或限速下的实测。

**鉴权查询回归：** 消息读写等 HTTP 入口在一次 Bearer 鉴权内曾重复读取同一账号，专门的回归测试先复现实际 2 次、预期 1 次。改为返回本次已校验的会话和账号，不跨请求缓存；下一请求仍重新检查账号是否禁用。`target/auth-read-27d2e15/before.log` 和 `regression.log` 分别记录复现与修复后的 22 项测试（无跳过）。其中 `target/control-load-6180951133290078989/summary.json` 记录本机百账号 90.026 秒续票，3,806 次 HTTP、HTTP/同步均零失败，p95 6.274 ms；每账号至少一次续票、十次补发成功。这证明消除了冗余查询，不证明它导致旧一小时失败或目标服务器已达标。

在 workspace 执行 `python -B "scripts/audit-control-load.py" "target/<本次运行目录>" --seconds 90`；一小时用例改为 `--seconds 3600`。审计按与负载同源的单调时钟检查每个账号的心跳、消息读取、消息写入和好友在线状态请求，包括开头与结尾的空档；最后十个账号的心跳扣除计划内的前 68 秒离线模拟。一小时用例还要求每账号至少续票四次。旧记录缺少 `requestedSeconds` 或 `workloadElapsedMs` 时不能据此复核，不回填推算时间。

本机 90 秒续票用例的记录位于 `target/control-load-11847643715168560359/`。`operations.jsonl` 共 3,809 条，SHA-256 为 `333c972a050cf191083dbb1ee7b4080a275566df9f18054987f9daffee2dc7d0`；`http-activity-audit.json` 显示 100 个账号每人至少续票一次，四类活动的最长空档为 20.065 秒。本审计只证明本机 HTTP 活动，不替代同步通道断言、目标服务器一小时容量、语音或虚拟网络验收。

启用宿主采样时，C0 和百账号夹具都通过临时库的 `data_directory/postmaster.pid` 确定主进程，并与当前 SQL 会话的操作系统父 PID 核对。`--pid` 的 CPU、句柄、RSS 仍只计测试 JVM；可选 `--memory-tree-pid` 在每个采样周期独立枚举指定根及当时可见的后代，只读 RSS。所选 PID 与进程树重叠时失败；子进程在读取期间消失可有限重试，根进程退出或无法完成该样本时不汇总成功。原始 `processes` 和 `memoryTree` 都含 PID、创建时间及 RSS。`combinedMemoryPeakMiB` 取每个周期两组 RSS 之和的最大值，不叠加不同周期的峰值。RSS 可能重复计算共享页，短于采样间隔的子进程可能未被观测；它不是整机或容器的实际内存占用。Node 压测客户端不计入，数据库连接数和堆仍见 `resources.jsonl`。

同机实测：`target/baseline-and-load-tree-20261120-032058/maven.log` 两项通过、零跳过。下列数值均由各目录 `host-observation/samples.jsonl` 复核：

| 本机场景（原始记录在 `target/`） | 宿主采样 | JVM RSS 峰值 | PostgreSQL 树 RSS 峰值 | 同周期合计峰值 |
| --- | --- | ---: | ---: | ---: |
| 零账号，`control-baseline-12206853101682162654` | 60.85 秒 / 61 条 | 543.37 MiB | 265.74 MiB | 795.68 MiB |
| 百账号，`control-load-6661463172665070913` | 97.48 秒 / 97 条 | 593.04 MiB | 303.57 MiB | 895.01 MiB |

百账号实际负载 90.03 秒，100 个同步连接，3,706 次 HTTP 请求、p95 19.84 ms，HTTP 和同步均零失败，10 次重连均确认补发。宿主采样还覆盖负载前后的协调时间。两次运行的回环接口计数都未变化，不能报告带宽。测试机未经二核/2 GiB/5 Mbit/s 限制，不能从这些 RSS 或时延数字推断目标机容量或授予 AC-038/039 通过。

## 已运行的开发验证

- `target/preview-resource-sample-029802f01e96414784ee5f1c6b3b176e`：仅观察既有试用后端 JVM，60.01 秒、61 个样本；不是 C0 完整基线。首次从测试外部手工采样负载 JVM 时进程先结束，`target/load-resource-sample-4e451bc5e0a8424f9be049706806389d` 明确失败为 `NoSuchProcess`，93 条已写样本保留，未用于成功汇总。
- 开始和结束均由负载夹具协调后，最终运行 `target/control-load-1985818837874019563`，Maven 日志 `target/profiled-control-load-final-134c1aba47e140a2b3ec16d910e6ed30.log`，指定集成用例 1 项通过、零跳过。100 个并发同步连接，90.35 秒、3,706 个请求、零失败，p95 20.79 ms；49,840 次同步交付、十次指定离线消息补发，在线过期与恢复均观测到。98 条采样跨 98.39 秒，经 UTC 首末记录核对，覆盖首尾业务操作。JVM 采样峰值 CPU 198.35%、RSS 567.19 MiB、句柄 997 个，未计数据库和压测器。
- 本机 Windows 回环接口读数前后不变，`observedInterfaceTraffic=false`；由于同时存在真实业务收发，**本轮不能给出有效链路带宽值**，不可把 0 Kbit/s 或应用正文大小作为满足 5 Mbit/s 的证据。该机器不是已限额的目标服务器，持续时间也不是一小时。
- `python "scripts/test-capacity-resource-sampler.py"` 七项通过，覆盖实际间隔与方向、PID/计数器变化、只读实采、禁止覆盖、异常部分结果及停止信号/超时。原同步诊断、容量契约、运行器错误传播检查通过；试用健康 UP，未改变产品制品或试用数据。
## 从干净源码复跑

### `edb856b` 百账号一小时复测

`target/hour-auth-edb856b/hour.log` 记录指定集成测试 1 项通过、零跳过；原始报告位于同目录 `source/target/control-load-13071661655118780413/`。100 个账号持续 3,600.032 秒，完成 149,727 次 HTTP 请求，零失败，p95 为 5.128 ms；服务端访问日志对应 149,727 个 200 和 510 个 WebSocket 握手 101。客户端收到 1,803,040 次同步事件，零同步错误，10/10 次指定断线消息补发成功。独立重跑逐账号审计，所有账号至少续票 4 次，核心操作最大空档 20.21 秒。`operations.jsonl` SHA-256：`32a46a628cdef9639f50bce3264518701456f00e1a9d1c0a2d0924f81af567ba`。

服务端仍有 1 条 `operation=send cause=NONE socket_open=true` 告警。最近一次成功续票响应在告警后 4 ms，但日志不能把二者归于同一连接。客户端零错误不能证明告警无影响，本轮通过也不能解释上一轮 27 次失败的根因。

宿主采样覆盖 3,608.18 秒、3,530 个样本，JVM 与数据库进程树在同一采样时刻的合计 RSS 峰值为 1,152.52 MiB。应用层接收正文平均 1.762 Mbit/s；3,600 个完整秒中 44 秒超过 5 Mbit/s，峰值 7.815 Mbit/s。回环接口没有可用的流量计数，宿主未限制为目标服务器规格，也没有媒体或游戏负载；本轮只通过本机控制面长测，不授予 AC-038/039 通过。

### 既有短测复跑方法

从 `workspace` 运行 `node "scripts/test-load-sync-diagnostics.mjs"` 可单独检查同步错误分类、游标和重连诊断。百账号命令需同时具备 Java 测试入口、`scripts/load-control-plane.mjs` 和 `scripts/load-websocket-handshake.mjs`；测试创建自己的输出目录和 PostgreSQL，不应依赖其他试用环境预先生成 `target/`。

本轮在 D 盘单独导出 Git 中的控制面源码、构建文件和已跟踪脚本，只补入本次提交的四个测试源文件。复用本地离线 Maven 依赖，未复制编译产物。按上文 workspace 命令从零编译 149 个生产源文件和 49 个测试源文件；选定用例 1 项通过、无跳过。100 个并发同步连接，90.05 秒内完成 3,710 个 HTTP 请求，p95 为 19.91 ms，HTTP 和同步均零失败；10 次重连均收到指定离线消息，观测到离线及恢复。证据：`target/load-source-run.log`、`target/load-source-41b10a4/target/control-load-46357500962409498/summary.json`。采样持续 97.80 秒，但回环接口计数未变化，不据此报告带宽。此结果不是一小时、目标服务器或双通道满载验收。
