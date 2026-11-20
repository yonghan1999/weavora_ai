# Relay Outbox 主动撤销

## 已实现与边界

控制面原 `UnavailableExternalRevocationTargets.Relay` 对所有网络撤销报未配置，Rust QUIC Relay 只能逐包和空闲定期回查权威服务。本轮接通已批准架构中的 Outbox → Relay deny/disconnect，默认仍关闭；不把新增代码等同于当前试用包或目标服务器已启用。

`RelayRevocationTarget` 只处理 `network.revoked`，发送版本、事件、活动、用户和成员代次，不发送票据、设备或撤销原因。仅 HTTP 200 且版本、事件和 `applied` 状态严格匹配才完成外部投递；重定向、拒绝、超大回执和无效代次均失败并沿用 V16 持久化退避。错误只使用固定分类，不附带原始异常、URL 或凭据。连接限一秒、请求限两秒、回执限 4,096 字节。

Rust 管理入口仅监听回环地址，使用独立密钥、单一认证头和常量时间比较；精确匹配 POST 路径、JSON 内容类型、版本与字段，最多 32 个并发连接、8 KiB 头缓冲、4,096 字节正文和三秒连接总期限，不支持 keep-alive。错误响应不回显输入。

deny 以活动/用户为范围保留最大成员代次，立即关闭并移除该代次及更旧代次的会话；重复或迟到事件不能撤销更高代次，其他活动不受影响。deny、会话注册和最终转发检查共用一把锁，防止已在权威校验中的旧握手越过撤销。保留原逐包来源/目标及空闲一秒权威验权，不使用本地 deny 表放宽授权。

deny 最多 16,384 个范围、保留 65 秒，覆盖当前 60 秒票据寿命；满时返回 503，不逐出尚有效的 deny。表只在内存中，重启不持久化；过期和重启后的安全性仍依赖权威验权与原票据期限。该表不是完整授权缓存，不能据此声称控制面离线可用或端到端五秒 SLA 已验收。本轮旧实现的外部批内仍顺序投递；后续语音/网络隔离与尚存的单通道积压边界见 [外部撤销通道并行](external-revocation-channels.md)。

## 配置与回退

只在明确准备好 Relay 与控制面配置后启用；不要将凭据写入仓库、命令行或对话。

| 进程 | 环境变量 | 用途 |
| --- | --- | --- |
| 控制面与 Relay | `ZHIJIE_RELAY_MANAGEMENT_KEY` | 原 Relay → 控制面票据验证凭据 |
| 控制面与 Relay | `ZHIJIE_RELAY_REVOCATION_KEY` | 新控制面 → Relay 撤销凭据，32～256 个可打印非空格 ASCII 字符，必须与验证凭据不同 |
| Relay | `ZHIJIE_RELAY_MANAGEMENT_LISTEN` | 撤销入口默认 `127.0.0.1:40001`，只接受回环 IP |
| 控制面 | `ZHIJIE_RELAY_ADMIN_URL` | 默认 `http://127.0.0.1:40001`；仅回环 HTTP 或 HTTPS，根路径且无用户信息、查询及片段 |
| 控制面 | `ZHIJIE_RELAY_REVOCATION_ENABLED` | 默认 `false`；配置妥当后设 `true` |

Relay 未设置撤销密钥时不启动管理入口，仍保留原权威校验。控制面关闭主动撤销时继续使用真实失败目标，网络 Outbox 保持待投递而非伪成功。启用一侧而另一侧未就绪会留下重试和固定告警，不应误判为已完成。跨机管理须使用受控 TLS 反向代理转发到 Relay 回环入口，不开放裸 HTTP 管理端口；本轮未部署远程反向代理或服务器。

本轮无数据库迁移，仍为 V16；仅修改新启动进程配置，不改当前试用数据库、系统环境、防火墙或网卡。需要撤回时停止对应新进程，恢复此前匹配的程序及配置；不得将未投递事件手动标为完成。既有已验证试用包仍是 `target/trial-kit-0dcaf88/zhijie-windows-x64-trial.zip`，不包含本轮主动撤销实现。

复用现有 `RevocationTarget` 与派发退避职责，QUIC 会话共用最小有界撤销状态，不增加队列、数据库或新调度器（KISS、YAGNI、DRY、单一职责）。Hyper 等直接依赖复用已锁定版本，仅新增 `httpdate 1.0.3` 锁条目；完整清单为 691 项，归档新增两份原文及关联索引，仍 `unreviewed`、`releaseApproved=false`，未替产品选择许可分支。

## 开发证据

以下均在 D 盘 `target/relay-revocation-280bca4/`，属于开发验证而非 CLI 正式任务证据或 F/G/H/I 门禁。

- `before.log`：真实 Spring/PostgreSQL/HTTP 接收端复现未配置目标无法完成网络 Outbox，一项失败。
- `configured-target.log`、`targeted.log`、`real-outbox.log`：配置目标一项、定向八项、真实 Rust 组合一项通过；原始 Rust 编译 API 不匹配失败保留于 `rust.log`，修正后通过。
- `rust-safety.log`：八项 Relay 单测通过，覆盖主动关闭来源/目标、旧票拒绝、其他活动及新代次不受影响、重复/迟到事件、握手竞态、无效管理请求、有界表满及过期回收。
- `rust-regression.log`：Helper/Relay/Shell 共 94 项通过、8 项需专用环境夹具的测试跳过，零失败；其中真实 Outbox 夹具由 Java 显式运行，不把普通 `cargo test` 跳过当通过。
- `regression.log`：首次综合 23 项，一失败一错误。十二端探针日志只到构建目录锁等待，未进入端点就绪；同时另一套 Cargo 正在编译。同步重连夹具在等待原 WebSocket 关闭处超时，尚无确定根因，不将它归因为已修复的生产缺陷。失败日志保留。
- `sync-isolation-recheck.log`：该同步隔离定向三项通过，未改超时或断言。
- `regression-sequential.log`：在 Rust 编译结束后顺序重跑，23 项通过，零失败/错误/跳过，耗时六分五十五秒。包括真实三种模式十二端全向数据泵、五轮续期、旧票过期、成员离开与策略撤销，低权限/V16、派发/退避/隔离，以及严格 JSON 回执与事件类型检查。
- `target/relay-outbox-2832944455568465511/scenario.log`：真实 PostgreSQL → 业务离开 → Outbox → Rust HTTP 管理 → QUIC 断开和旧票拒绝；同 IP 重新加入更高代次后重放旧事件，新连接仍可双向收发。凭据经 stdin 传递，日志秘密扫描通过。早期同场景通过记录位于 `target/relay-outbox-6349876490951118532/`。
- `workspace.log`、`compatibility.log`、`release.log`、`performance.log`：当前工作树契约通过，真实 macOS 签名测试显式跳过；许可证材料新增两份原文及三层摘要关联由原收集器重建核验，不降低检查。
- `native-build.log`：真实离线锁定构建 Relay 与 Helper 成功；`package.log` 真实构建 Spring Boot JAR，`packaged-migration.log` 使用该 JAR 的独立迁移进程完成一项低权限回归，零失败/错误/跳过。打包时跳过测试不替代上述回归。
- `relay-snapshot.log`：从暂存区独立检出本轮候选，工作区、691 项完整清单、兼容/发布契约和三层实际许可材料重建全部通过；真实 macOS 签名检查仍显式跳过。该快照不包含历史未提交的兼容脚本改动。

确认本轮 Java/PostgreSQL/Cargo 进程退出后，核验绝对路径和重解析点，清理本轮两份源码检出副本、测试临时目录和重复许可材料共 140,995,975 字节。保留验证日志、复建脚本、JAR、Rust 构建、真实组合证据及共享缓存；清理后 C/D 可用约 10.20/43.36 GiB。历史清理被拒目录仍保留，未重试删除，未处理其他程序文件。

当前正式阶段仍 `E_IMPLEMENTATION/in_progress`，不据此完成 TASK-019～023 或提升整体交付。人工试音按用户要求搁置；管理员 TUN、跨机网络、三平台安装/签名、目标服务器容量、许可证审核及 F/G/H/I 继续开放。
