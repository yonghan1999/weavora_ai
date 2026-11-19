# 容量测试的进程与网卡采样

`scripts/sample-capacity-resources.py` 是测试机工具，不进入产品发行包。它只读所选 PID 的 CPU 用户/内核累计秒、RSS、文件描述符（Windows 为句柄），以及指定网卡累计发送/接收字节。依赖 Python 3 和 psutil；本机用 3.13.5 / 7.2.2 验证。无需账号、数据库密码或读进程命令行，不配置网卡、限速、路由或防火墙。

## 独立采样

通过 `scripts/run-performance.mjs` 或 `scripts/run-compatibility.mjs` 记录一次运行时，每次请传入新的 `--output` 文件名。报告、同名 `.stdout.log` 或 `.stderr.log` 中只要有一个已存在，运行器就拒绝启动适配器，保留原始记录。遇到中断留下的空文件也应换名重跑，而不是覆盖前一次结果。

先在对应非生产测试机核实需要监测的实际 PID 和承载负载的接口名，可用 `python -c "import psutil; print(list(psutil.net_io_counters(pernic=True)))"` 枚举接口。下例 PID 和接口仅为占位；`--pid` 可重复，不能重复计入同一 PID。使用已存在的父目录和新的输出目录，避免覆盖历史结果：

```sh
python "scripts/sample-capacity-resources.py" --pid 1234 --pid 5678 --interface "eth0" --duration 3600 --interval 1 --output "target/c1-host-run-001"
```

每次采样立即写 `samples.jsonl`，含 UTC、单调时间及各 PID 累计值。`summary.json` 的 CPU 使用一个满核 = 100%；RSS 是所选进程 RSS 之和（不去重共享页）；速率为指定接口两次累计字节之差 × 8 / 1000 / 实际经过秒数。峰值是采样区间峰值，不是瞬时峰值。PID 退出/身份变更、计数倒退或读取异常时退出非零，保留已写记录，不生成成功峰值。计数器未变化时，不能用记录中的 0 证明业务没有网络流量。接口统计还包含无关流量，不是业务分项或物理链路总开销。

## 本机 C0：零账号空闲与固定请求

需要 Windows 或 Linux 本地测试机、可用的回环接口和已安装 psutil 的 Python。设置 `ZHIJIE_TEST_CONTROL_BASELINE=true` 和 `ZHIJIE_TEST_RESOURCE_INTERFACE`（本机为 `Loopback Pseudo-Interface 1`），可用 `ZHIJIE_TEST_PYTHON` 指定解释器。从 workspace 执行 `./mvnw.cmd -o -pl control-plane "-Dtest=ControlPlaneBaselineIntegrationTest" test`；不开启该测试时会跳过。场景使用临时 PostgreSQL、随机本地 HTTP 端口和零账号，不接触试用数据库。等首条资源样本落盘后，前 30 秒没有业务 HTTP；后 30 秒每秒一次 `GET /actuator/health`，要求 HTTP 200 且状态为 UP。两段均每秒查询当前数据库的连接数，该查询本身也占用连接，所以“空闲”并非完全没有数据库活动。

本机记录：`target/control-baseline-12757177290913580339/control.jsonl`（60 条），同目录 `host-observation/samples.jsonl`（61 条、60.47 秒）；Maven 日志 `target/baseline-validation-20261120-031435/maven.log`（1 项通过、0 跳过）。30 次健康请求成功，按延迟升序取第 29 个样本的 p95 为 7.76 ms；60 次数据库连接观测均为 10。测试 JVM（含 Maven 和 Spring）的区间 CPU 峰值为单核的 58.96%，RSS 峰值 528.95 MiB，句柄峰值 718；这些值不包括 PostgreSQL 子进程。回环网卡双向计数均未变化，不能推导流量为零。本结果用于对比同机后续负载，不是目标服务器基线或任何容量验收结论。

## 与本机百账号用例协同

设置 `ZHIJIE_TEST_CONTROL_LOAD=true` 及 `ZHIJIE_TEST_RESOURCE_INTERFACE`（非空接口名），从 workspace 运行 `./mvnw.cmd -o -pl control-plane "-Dtest=ControlPlaneLoadIntegrationTest#hundredVerifiedAccountsRunRealHttpAndSyncWithPresenceRecovery" test`。可选 `ZHIJIE_TEST_PYTHON` 指向已安装 psutil 的解释器。不设接口时保持原测试流程，无新增 Python 环境要求。台架先确认采样器已写首条样本，再启动业务流量；负载结束后写停止信号，检查子进程、截止原因及覆盖时长。`host-observation/` 与负载 `summary.json`、`operations.jsonl`、`resources.jsonl` 同属于一个新运行目录。

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

从 `workspace` 运行 `node "scripts/test-load-sync-diagnostics.mjs"` 可单独检查同步错误分类、游标和重连诊断。百账号命令需同时具备 Java 测试入口、`scripts/load-control-plane.mjs` 和 `scripts/load-websocket-handshake.mjs`；测试创建自己的输出目录和 PostgreSQL，不应依赖其他试用环境预先生成 `target/`。

本轮在 D 盘单独导出 Git 中的控制面源码、构建文件和已跟踪脚本，只补入本次提交的四个测试源文件。复用本地离线 Maven 依赖，未复制编译产物。按上文 workspace 命令从零编译 149 个生产源文件和 49 个测试源文件；选定用例 1 项通过、无跳过。100 个并发同步连接，90.05 秒内完成 3,710 个 HTTP 请求，p95 为 19.91 ms，HTTP 和同步均零失败；10 次重连均收到指定离线消息，观测到离线及恢复。证据：`target/load-source-run.log`、`target/load-source-41b10a4/target/control-load-46357500962409498/summary.json`。采样持续 97.80 秒，但回环接口计数未变化，不据此报告带宽。此结果不是一小时、目标服务器或双通道满载验收。
