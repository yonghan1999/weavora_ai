# 同步发送失败的脱敏分类

历史 `target/sync-unregister-race-0845bc2/regression.log` 有十一条 `Sync delivery failed: category=IllegalStateException`。该类别同时可能来自编码失败、IOException 包装或传输自身的状态异常，仅凭类别无法定位；客户端零错误也不证明服务端未失败。

实时广播失败保留原类别，并追加固定 `operation=encode|send`、直接原因 `cause=NONE|IO|OTHER` 及失败后的 `socket_open` 布尔值。编码与发送使用同一原有 try/catch，鉴权异常仍独立处理；不记录异常正文、堆栈、URI、账号、事件正文或凭据，不改变游标推进、失败连接注销/关闭、重试或权限。

`SyncDeliveryDiagnosticsTest` 注入编码异常、开放连接写 IOException、已关闭传输状态异常，确认分类、脱敏、连接表清理和原 1011 关闭行为。`target/sync-delivery-diagnostics-cc4cc7d/unit.log` 十三项通过，含新增三项及原十项同步服务检查；后续真实会话/回放/提交顺序和百账号回归另见同目录 `regression.log`。

`regression.log` 最终二十七项通过，零失败/错误/跳过；三个百账号场景无发送告警，三条失败分类均由新增测试刻意注入。`workspace.log`、`compatibility-final.log`、`release.log` 通过，真实 macOS 签名检查跳过。历史问题保持开放，没有声称无法复现即修复。现有 `trial-kit-6523e6a` 试用制品保留，不包含本次新增诊断。

仅扩展既有固定格式日志，不新增异常上传、连接缓存或依赖（KISS、YAGNI）；三种失败沿用原捕获和清理路径（DRY），诊断不承担恢复或授权职责（单一职责）。这是排查能力，不是发送异常根因修复，也不证明历史告警无影响。
