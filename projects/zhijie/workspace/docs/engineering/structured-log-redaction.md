# 结构化日志中的凭据脱敏

## 复现与修复

AC-033 要求普通日志不记录密码、验证码或敏感令牌。代码审计发现 `LogSanitizer` 原规则只匹配紧邻分隔符的裸字段名；JSON 的带引号键完全漏过，`networkTicket`、服务 API/中继管理密钥也未覆盖，带转义引号的密码值还可能仅被部分遮蔽。这是测试合成日志中确定复现的缺陷，没有据此认定既有用户凭据已出现在真实日志。

`target/structured-log-redaction-07f3633/before.log` 保留首次结果：四项测试中两项失败，分别确认 JSON 密码值和网络票据/API/信令/中继密钥值仍可见。测试只使用固定合成标记，没有真实凭据。

修复继续使用同一个编译后的字段匹配器，识别成对引号键、驼峰/下划线/连字符的密码、令牌、票据、私钥及本项目服务密钥字段；有引号值按转义字符消费，防止在转义引号处提前结束。字段边界避免从 `errorCode` 尾部匹配 `code`，保留故障码、状态和非敏感元数据。Bearer 与 Argon2 脱敏继续沿用。没有引入 JSON 解析器、日志缓存或新依赖（KISS、DRY、YAGNI），脱敏仍由原日志转换器统一负责（单一职责）。

新增测试覆盖 JSON/单引号键及转义值、网络授权记录、服务密钥和验证码；同时用真实 Jackson 输出结构化字段，交给生产 `logback-spring.xml` 的 ConsoleAppender 编码器，核对消息和原始异常/原因/抑制异常均不泄漏合成秘密，安全错误码和关联 ID 保留。测试不会往正式业务日志注入真实请求体。

## 直接开发验证

- 修复后定向 `LogSanitizerTest`：四项通过、零失败；`target/structured-log-redaction-07f3633/fixed.log`。
- 最终定向回归 `LogSanitizerTest,IdentityServiceTest,SmtpEmailDispatchAdapterTest,SyncWebSocketHandlerTest,SyncServiceTest,SyncSessionIntegrationTest,NetworkPeerIntegrationTest`：共三十项，二十九通过、一项十二端媒体环境夹具条件跳过，零失败/错误；`target/structured-log-redaction-07f3633/regression.log`。包含真实 PostgreSQL/Spring、SMTP 注册/重置/退出、同步会话及网络票据检查。

本轮 Java 临时文件、Maven 依赖和 Cargo 缓存均位于 D 盘忽略目录，仅修改当前进程环境。保留必要日志和复现脚本，不读根目录 `tmp/`、不删除用户数据或其他运行的缓存。首次失败与后续通过均保留，不用成功记录覆盖失败。

上述测试证明所列字段格式的日志边界，不保证任意未知格式或无标签字符串可以被识别；应用仍不得主动记录凭据或请求体。未代替 TLS、数据库权限、所有日志通道及最终组合后的秘密扫描，不能单独关闭 AC-033、TASK-023 或端到端交付。

## 配套试用制品

从已提交的 `6885213` 使用 Git 源码归档独立离线编译，没有混入当前工作区其他改动。后端 `target/trial-kit-6885213/preview-backend.zip` 为 83,809,906 字节，SHA-256 `9c5ff3c54eb0f5548bd7fd6d2c73ec60e879bf42a1a6ed5c1ac93f2e22ed29fa`；编译及类路径日志 `backend-build.log`。与 `59056e8` 对比，328 个后端条目仅本次脱敏类变化。

实际 `test-backend-zip.ps1 -CheckLogPrivacy -CheckSyncAuth -CheckRoomReopen` 通过登录、消息、重启、两种同步认证、房外重新开放及普通日志/管理记录不含本次凭据，见 `privacy-restart-retest.log`。首次运行因继承 `JAVA_TOOL_OPTIONS` 后 javac 的标准错误提示触发 PowerShell Stop 而中断，记录 `privacy-restart.log` 保留；移除该环境变量后重测，Java 入口自身仍显式设置 D 盘临时目录和禁用 PerfData。没有修改生产日志或删除失败记录。

配套桌面仍为 `399db61`。完整 ZIP 为 93,048,472 字节，SHA-256 `5a8615cff3b740dbb483007207a77dfa0c1d2ea083a28286421e877ea852ab97`，同目录 `comparison-final.log` 核对 340 个条目仅后端脱敏类变化。整包 `relocation.log` 证明首次启动、重启、1,110 文件冷备及恢复后的登录/消息、旧设备被拒签语音与网络票据、当前设备获票、按租约退房均通过。四项后端打包测试、两项整包打包测试通过，日志 `pack-backend-unit.log`、`pack-kit-unit.log`。

仅更新候选索引与说明，不替换运行中的用户目录或数据库；未启动桌面窗口、安装器、Helper 或创建网卡，未将邮件/语音默认关闭误记成真实能力通过。

确认本轮 Java/Postgres 等进程退出后，逐一校验 D 盘项目内的固定清理路径，删除本轮两个临时目录和独立构建源码副本，共 103,672,988 字节；保留源码 ZIP、必要日志、两份交付 ZIP 和共享缓存。不处理其他轮次的缓存或用户目录。
