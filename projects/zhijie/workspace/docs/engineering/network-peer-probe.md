# 两端真实虚拟 IP 的 TCP/UDP 验证入口

## 使用条件

两台隔离测试机已安装配套桌面/Helper，加入同一活动并分别获得虚拟 IPv4，真实网卡已显示。此工具只测试指定 IP 上的普通套接字，不创建网卡、配置路由、修改防火墙或启动 Helper，也不能单独证明经过了虚拟网卡。需要 Python 3.10+ 标准库，无第三方依赖；本轮仅在 Windows 执行工具自测，macOS/Linux 未执行。

先核对两端当前活动 IP、/27、Helper 版本和实际接口。两台机器用同一个随机、至少 32 字符的临时校验码设置进程环境 `ZHIJIE_NETWORK_PROBE_TOKEN`；不要使用账号密码、网络短票或其他产品凭据，不把校验码写入命令、结果或仓库。可在一台机器用 Python `secrets.token_hex(24)` 本地生成后通过测试人员可用的安全途径共享。运行器不会输出环境值，也不会修改防火墙；如果系统询问放行，应由测试人员按隔离环境范围处理。

## 正向与反向

以下示例将 A 的活动地址设为 10.231.0.1，B 为 10.231.0.2，活动为 10.231.0.0/27，实际执行必须替换。每个输出目录必须不存在；失败日志也保留，重试使用新目录。

在 A 先启动：

```powershell
python "scripts/network-peer-probe.py" serve --bind 10.231.0.1 --peer 10.231.0.2 --cidr 10.231.0.0/27 --port 39871 --count 10 --timeout 60 --output-directory "target/peer-A-server-01"
```

看到 ready 后，在 B 执行：

```powershell
python "scripts/network-peer-probe.py" run --bind 10.231.0.2 --peer 10.231.0.1 --cidr 10.231.0.0/27 --port 39871 --count 10 --timeout 30 --output-directory "target/peer-B-client-01"
```

每种协议应完成 30 个请求/响应，载荷依次为 64、512、1000 字节，逐包检查随机二进制内容、序号及 HMAC；记录 TCP/UDP 数量与客户端 RTT。UDP 不自动重传以掩盖丢包；超时、内容错误、签名错误均非零退出。服务端只响应指定对端 IP 且校验有效的请求，响应方向独立标识，拒绝请求反射成成功响应。限制报文长度、轮数和总执行时间。接着交换 A/B 的 serve/run 身份，用新目录重跑，验证双端主动建立连接。一个请求/响应已覆盖双向传输，但交换角色还检查另一端监听/防火墙条件。

可通过既有兼容运行器保存子命令 stdout/stderr，例如在 B：

```powershell
node "scripts/run-compatibility.mjs" --platform windows --output "target/peer-B-adapter-01.json" -- python "scripts/network-peer-probe.py" run --bind 10.231.0.2 --peer 10.231.0.1 --cidr 10.231.0.0/27 --output-directory "target/peer-B-client-02"
```

包装结果仍只是 adapter_recorded_not_acceptance_verified，不自动修改支持矩阵或正式阶段证据。探针结果始终 acceptanceVerified=false，需结合两端接口/路由、Helper 状态、服务器实际流量与人员审阅，不能据此宣称游戏或三平台通过。

## 结果与边界

配对 A/B 的同一次执行时，检查两份 `result.json` 的执行角色相反、绑定与对端地址互换、工具摘要和参数一致、预期包数全部收到，且各自的 `results.transcriptSha256.tcp` 和 `results.transcriptSha256.udp` 分别相等。摘要来自实际成功交换的带随机载荷的请求，TCP 与 UDP 分开计算；不要用相同包数配对不同轮次。失败报告不提供完整摘要。这个检查不能证明流量经过 TUN，也不是报告签名。

每端 result.json 保存工具 SHA-256、UTC 时间、系统/架构、绑定/对端 IP、载荷大小、数量、耗时及失败状态；不保存临时校验码、载荷和产品凭据。只有两端结果、接口/路由证据和实际版本均核对才构成该轮工具互通证据。Windows 可在测试前后用 Get-NetIPAddress、Get-NetRoute、Get-NetAdapter 只读记录实际接口与活动网段归属；默认路由和物理 LAN 路由应保持，正常退出后只删除产品 owned 网卡/路由。尚未提供自动清理检查，不猜测归属或删除其他接口。

接着分别覆盖直连限定、允许回退与强制中继的运营配置；从两端与服务器流量确认实际路径。退出、撤销、禁用和换房后，应验证旧连接停止及重新主动连接拒绝，不能只在停止探针后声称撤销通过。实际游戏、十二端并行、一小时长测、真实可听语音、跨系统和安装升级卸载仍是独立验收项。

## 本轮验证

`python -B "scripts/test-network-peer-probe.py" -v`：本机八项测试通过。覆盖 TCP/UDP 各九次随机载荷往返、独立运行的传输摘要不能混配、错误共享码、非预期 UDP 源、空闲/半帧超时、不可达及旧目录拒绝覆盖、篡改/反射/非法帧、回环标记与活动地址边界。测试使用 127.0.0.1/2/3，标记 `loopback_tool_test`；没有安装驱动、提权或修改网卡、路由和防火墙。

工具采用标准库和显式命令，复用现有兼容入口（KISS/DRY）；传输/结果与网络安装分开（单一职责），只实现当前需要的 TCP/UDP 字节校验，不引入吞吐框架或后台服务（YAGNI）。

专项日志保存在 `target/peer-transcripts-25f60d8/`：`before.log` 记录旧报告缺少传输摘要的失败，`verified.log` 记录八项回归，`matrix.log` 记录兼容性契约检查。本轮不修改正式证据索引、支持矩阵或任务状态，也不重建产品安装包。
