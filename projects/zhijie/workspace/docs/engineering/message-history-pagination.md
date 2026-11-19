# 群消息历史分页（REQ-019）

群成员通过 `GET /api/v1/groups/{groupId}/messages?limit=50` 读取最新一页。结果按创建时间、消息 ID 降序排列。读取更早的消息时，将上一页最后一条消息的 `id` 作为 `beforeId`；即使多条消息的时间戳相同也不会跳过。没有更多记录时返回空数组。

游标只在当前群内有效；未知和其他群的消息 ID 都返回空页。已删除消息保留占位记录，因此两次请求之间删除游标消息不会中断翻页。既有 `before` 仍表示严格早于指定时间，不能与 `beforeId` 混用。所有请求仍须具有当前群成员资格。

`MessageHistoryPaginationIntegrationTest` 使用回环 HTTP、真实 PostgreSQL、生产查询和软删除逻辑验证。此测试只覆盖控制面 API，不代表桌面历史翻页或多平台验收通过。

桌面群聊与房间内的群聊使用同一入口：最新一页有 50 条时，点击「更早的消息」向前翻页。历史页从早到晚显示，已删除的消息只显示占位。临时读取失败可在原页重试；「返回最新消息」保留当前群的草稿。桌面通过原生层传递群 ID 和消息 ID，不向页面提供令牌或任意请求地址。

本机联调使用独立 PostgreSQL、回环 Spring HTTP、Rust Shell 测试进程和无头浏览器页面。创建 103 条消息，实际点击群聊入口，逐条核对最新 50 条、历史 50 条、末页 3 条及删除占位，并检查返回后的草稿。日志 `target/history-baseline-z5mb5v4d/history-integration.log` 记录 `HISTORY_HTTP_POSTGRES_NATIVE_BROWSER_OK 50+50+3`。这条路径使用测试桥接，不覆盖实际 Tauri 窗口 IPC 或跨设备验收。
