# 容量测试的进程与网卡采样

`scripts/sample-capacity-resources.py` 是测试机工具，不进入产品发行包。它只读所选 PID 的 CPU 用户/内核累计秒、RSS、文件描述符（Windows 为句柄），以及指定网卡累计发送/接收字节。依赖 Python 3 和 psutil；本机用 3.13.5 / 7.2.2 验证。无需账号、数据库密码或读进程命令行，不配置网卡、限速、路由或防火墙。

## 独立采样

通过 `scripts/run-performance.mjs` 或 `scripts/run-compatibility.mjs` 记录一次运行时，每次请传入新的 `--output` 文件名。报告、同名 `.stdout.log` 或 `.stderr.log` 中只要有一个已存在，运行器就拒绝启动适配器，保留原始记录。遇到中断留下的空文件也应换名重跑，而不是覆盖前一次结果。

先在对应非生产测试机核实需要监测的实际 PID 和承载负载的接口名，可用 `python -c "import psutil; print(list(psutil.net_io_counters(pernic=True)))"` 枚举接口。下例 PID 和接口仅为占位；`--pid` 可重复，不能重复计入同一 PID。使用已存在的父目录和新的输出目录，避免覆盖历史结果：

```sh
python "scripts/sample-capacity-resources.py" --pid 1234 --pid 5678 --interface "eth0" --duration 3600 --interval 1 --output "target/c1-host-run-001"
```

每次采样立即写 `samples.jsonl`，含 UTC、单调时间及各 PID 累计值。`summary.json` 的 CPU 使用一个满核 = 100%；RSS 是所选进程 RSS 之和（不去重共享页）；速率为指定接口两次累计字节之差 × 8 / 1000 / 实际经过秒数。峰值是采样区间峰值，不是瞬时峰值。PID 退出/身份变更、计数倒退或读取异常时退出非零，保留已写记录，不生成成功峰值。计数器未变化时，不能用记录中的 0 证明业务没有网络流量。接口统计还包含无关流量，不是业务分项或物理链路总开销。

## 与本机百账号用例协同

设置 `ZHIJIE_TEST_CONTROL_LOAD=true` 及 `ZHIJIE_TEST_RESOURCE_INTERFACE`（非空接口名），从 workspace 运行 `./mvnw.cmd -o -pl control-plane "-Dtest=ControlPlaneLoadIntegrationTest#hundredVerifiedAccountsRunRealHttpAndSyncWithPresenceRecovery" test`。可选 `ZHIJIE_TEST_PYTHON` 指向已安装 psutil 的解释器。不设接口时保持原测试流程，无新增 Python 环境要求。台架先确认采样器已写首条样本，再启动业务流量；负载结束后写停止信号，检查子进程、截止原因及覆盖时长。`host-observation/` 与负载 `summary.json`、`operations.jsonl`、`resources.jsonl` 同属于一个新运行目录。

这里的 PID 是运行 Spring 和测试框架的 JVM，不包括 PostgreSQL 子进程或 Node 压测客户端；数据库连接数和堆数据仍在原 `resources.jsonl`。目标机应单独核对进程范围、容器限制、实际限速方向，并由外部负载机通过已核实的接口产生流量。采样器成功只说明采样完成，不能授予 AC-038/039 通过。

## 已运行的开发验证

- `target/preview-resource-sample-029802f01e96414784ee5f1c6b3b176e`：仅观察既有试用后端 JVM，60.01 秒、61 个样本；不是 C0 完整基线。首次从测试外部手工采样负载 JVM 时进程先结束，`target/load-resource-sample-4e451bc5e0a8424f9be049706806389d` 明确失败为 `NoSuchProcess`，93 条已写样本保留，未用于成功汇总。
- 开始和结束均由负载夹具协调后，最终运行 `target/control-load-1985818837874019563`，Maven 日志 `target/profiled-control-load-final-134c1aba47e140a2b3ec16d910e6ed30.log`，指定集成用例 1 项通过、零跳过。100 个并发同步连接，90.35 秒、3,706 个请求、零失败，p95 20.79 ms；49,840 次同步交付、十次指定离线消息补发，在线过期与恢复均观测到。98 条采样跨 98.39 秒，经 UTC 首末记录核对，覆盖首尾业务操作。JVM 采样峰值 CPU 198.35%、RSS 567.19 MiB、句柄 997 个，未计数据库和压测器。
- 本机 Windows 回环接口读数前后不变，`observedInterfaceTraffic=false`；由于同时存在真实业务收发，**本轮不能给出有效链路带宽值**，不可把 0 Kbit/s 或应用正文大小作为满足 5 Mbit/s 的证据。该机器不是已限额的目标服务器，持续时间也不是一小时。
- `python "scripts/test-capacity-resource-sampler.py"` 七项通过，覆盖实际间隔与方向、PID/计数器变化、只读实采、禁止覆盖、异常部分结果及停止信号/超时。原同步诊断、容量契约、运行器错误传播检查通过；试用健康 UP，未改变产品制品或试用数据。
## 从干净源码复跑

从 `workspace` 运行 `node "scripts/test-load-sync-diagnostics.mjs"` 可单独检查同步错误分类、游标和重连诊断。百账号命令需同时具备 Java 测试入口、`scripts/load-control-plane.mjs` 和 `scripts/load-websocket-handshake.mjs`；测试创建自己的输出目录和 PostgreSQL，不应依赖其他试用环境预先生成 `target/`。

本轮在 D 盘单独导出 Git 中的控制面源码、构建文件和已跟踪脚本，只补入本次提交的四个测试源文件。复用本地离线 Maven 依赖，未复制编译产物。按上文 workspace 命令从零编译 149 个生产源文件和 49 个测试源文件；选定用例 1 项通过、无跳过。100 个并发同步连接，90.05 秒内完成 3,710 个 HTTP 请求，p95 为 19.91 ms，HTTP 和同步均零失败；10 次重连均收到指定离线消息，观测到离线及恢复。证据：`target/load-source-run.log`、`target/load-source-41b10a4/target/control-load-46357500962409498/summary.json`。采样持续 97.80 秒，但回环接口计数未变化，不据此报告带宽。此结果不是一小时、目标服务器或双通道满载验收。
