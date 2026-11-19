# 群消息历史分页（REQ-019）

群成员通过 `GET /api/v1/groups/{groupId}/messages?limit=50` 读取最新一页。结果按创建时间、消息 ID 降序排列。读取更早的消息时，将上一页最后一条消息的 `id` 作为 `beforeId`；即使多条消息的时间戳相同也不会跳过。没有更多记录时返回空数组。

游标只在当前群内有效；未知和其他群的消息 ID 都返回空页。已删除消息保留占位记录，因此两次请求之间删除游标消息不会中断翻页。既有 `before` 仍表示严格早于指定时间，不能与 `beforeId` 混用。所有请求仍须具有当前群成员资格。

`MessageHistoryPaginationIntegrationTest` 使用回环 HTTP、真实 PostgreSQL、生产查询和软删除逻辑验证。此测试只覆盖控制面 API，不代表桌面历史翻页或多平台验收通过。
