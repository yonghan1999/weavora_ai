# 正式运行配置：同步授权缺失时拒绝启动

## 审计授权回归

在独立 PostgreSQL 上撤销运行账号对 `audit_logs` 的 `SELECT`，旧启动策略仍通过（`target/runtime-audit-3238efb/before.log`）。修复后分别撤销 `SELECT`、`INSERT`，两次均拒绝启动；缺少 `INSERT` 时还用真实可执行 JAR 独立启动，要求非零退出、输出审计错误码、不输出测试密码且不报告 HTTP 开始服务。补回配套授权后，低权限业务事务、审计追加、同步和人工恢复工具的集成回归通过。未更改数据库授权清单，也不在生产环境执行。

复查日志：`target/runtime-audit-3238efb/package.log`、`target/runtime-audit-3238efb/runtime-regression.log`。两个集成用例零失败、零跳过，测试使用的 JAR SHA-256 为 `91d5c075a14e177e2c54ff08cfb9923f447dd3989f10dc83bdaf52400a27f7ee`。此包用于验证正式运行配置，不是 Windows 试用包的更新。

## 同步授权回归

之前 `runtime` 只检查权限过大及迁移历史，丢失同步授权也能通过启动策略。真实 PostgreSQL 上撤掉 `outbox_events` 的读取权限后，原检查没有抛错（`target/runtime-sync-293eb6f/baseline.log`）。这可能让服务在无法正常写消息或分配同步游标时接收流量。

修复在 Flyway 校验后只读检查运行用户对 outbox 的 SELECT/INSERT/UPDATE、对同步序号映射的 SELECT/INSERT 和对两条序列的 USAGE。没有写入探测业务、推进数据库序列、修改授权脚本或自动修复生产库。缺一项就报固定错误码；操作指引见 `ops/database/migrate.md`。首次打包验证发现，异常码会被安全日志过滤器隐藏，现单独记录常量错误码，不输出连接配置或恢复异常堆栈。

**验证范围：** 在专用临时 PostgreSQL 上依次移除、恢复七项授权；运行进程拒绝每个缺权状态，授权恢复后以低权限角色完成群、房间、消息、审计与同步业务。最终 JAR 以独立进程执行迁移并验证重复运行不再应用迁移。缺少同步序列 USAGE 时，同一 JAR 的 runtime 进程非零退出、未报告 Tomcat 启动，日志包含正确错误码且未出现测试密码。另以同一 JAR 复测消息重启持久性（此项使用原测试的普通配置，不是低权限测试），以及 V17 迁移回归。

最终 Maven 三项集成测试全部通过，无跳过。证据：`target/runtime-sync-293eb6f/final.log`（SHA-256 `9b2b5b141e3b2c63fbf92bfe629e22dd6fd053024060008a6b634ca65d985063`）；验证制品：同目录 `control-plane-final.jar`（SHA-256 `585452b87471b92110df44cfa82b96d79ecbc1e3685fb69ef19516b188f7869c`）。旧失败及修复后首次制品日志保留。没有连接目标服务器、变更生产授权、重打本机 Windows 试用包或执行语音与网络组合重试。本项不证明完整部署或容量验收。
