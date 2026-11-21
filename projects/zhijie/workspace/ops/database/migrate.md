# 独立迁移控制面数据库

仅在获准的发布窗口使用。先停止旧版写入进程，核实目标库并验证升级前备份。首次部署须先在专用 PostgreSQL 库执行 `bootstrap-roles.sql`，另行为 `zhijie_migrator` 配置登录认证。不要针对运行中的试用库练习。

1. 仅向一次性进程注入 `ZHIJIE_MIGRATION_DATABASE_URL`（目标库的 PostgreSQL JDBC URL，不含凭据）和 `ZHIJIE_MIGRATION_DATABASE_PASSWORD`。没有默认目标，不使用运行账号密码。
2. 执行 `java -jar "control-plane.jar" --migrate-database`，将示例路径替换为本次发布的实际 JAR。命令固定使用 `zhijie_migrator`，检查实际连接身份，运行包内 Flyway 迁移并校验，不启动 HTTP 服务或调度器。
3. 成功时退出码为零，输出 `DATABASE_MIGRATION_OK applied=N`。重复运行相同版本应为 `applied=0`。失败时停止发布：不要修补迁移历史、跳过校验或改用超级用户重试。
4. 迁移成功后，以迁移角色执行本次发布的 `grant-runtime.sql`。长期运行进程使用 `runtime` 配置和独立的 `zhijie_app` 凭据，不继承迁移进程环境。数据库恢复应使用已验证的备份，而不是依赖该命令降级。

若启动报 `RUNTIME_DATABASE_SYNC_GRANTS_MISSING`，表示运行账号缺少消息同步所需的表或序列权限，不能接流量。先核对使用的数据库、运行账号和版本；由获授权的迁移人员检查并按第 4 步重新应用配套授权，再启动控制面。启动检查只读取数据库权限，不写入探测消息、不推进游标、不自动提权。这项检查不代替其他业务权限或目标环境的验收。
