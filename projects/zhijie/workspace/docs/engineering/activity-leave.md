# 退房与设备接管

桌面主动退房和注销时都向 `POST /api/v1/activities/{activityId}/leave` 提交离开前保存的 `leaseId`。控制面从登录会话取得账号和设备，不从请求体读取设备身份。在同一账号锁内，只有活动、设备和当前成员代次的租约都匹配时才释放名额、地址和通道资格。

- 首次释放：HTTP 200，`data.left` 为 `true`。
- 已经离开、其他设备接管或同一设备重新加入：迟到的旧请求返回 HTTP 200，`data.left` 为 `false`，不改变当前成员。
- 旧桌面发送空对象时，新后端返回 HTTP 422 / `VALIDATION_FAILED`。请将桌面和后端作为同一版本升级或回滚；不要只替换其中一端。此变更不需要数据库迁移。

入房、恢复和心跳请求中的设备必须与登录会话一致。语音与网络票据只签发给当前活动设备，检查与签发处于同一事务及账号锁内；其他登录设备仍可使用群聊。

`RoomGovernanceIntegrationTest.onlyTheCurrentDeviceCanRecoverRenewObtainGrantsOrReleaseItsLease` 验证设备接管、签发权限和同设备重新加入。`VerticalSliceIntegrationTest` 使用真实 Rust 客户端退房，并检查数据库中不再占位。
