# 独立测试计划：织界

## 测试范围

执行当前代码树的完整本地自动化回归：控制面单元与真实嵌入式 PostgreSQL 迁移/并发测试、Rust 全 workspace、UI、双通道垂直切片、发布配置、容量/音质工具及跨平台证据索引。用例用于核验已实现的本地契约与需求追踪，不把模拟适配、配置检查或工具校验扩张为 AC 全部通过。

生产邮件、真实 SFU 音频、真实 TUN/直连/Relay 设备组合、三平台安装签名更新、游戏互通、目标服务器 C0～C4 容量和 AC-040 听辨不在本轮可执行环境内，全部保留未验证。

## 测试环境

Windows 当前工作区；OpenJDK 25（`D:\Program Files\jdk-25`）、Maven Wrapper 3.9.9、rust-toolchain 固定工具链和 Node 24。控制面集成测试启动临时真实 PostgreSQL 17.5 并执行 Flyway V1～V8。无生产服务、生产数据、外部邮件、签名证书或 macOS/Linux 实机。

## 测试用例

- `TC-LOCAL-REGRESSION`：REQ-001～REQ-028 的本地实现与契约回归。执行登记的完整 Maven、Cargo、UI 和 Node 检查，预期退出码为 0、证据绑定当前代码树，且所有外部能力与实测缺口保持未验证。

## 需求覆盖

| 需求标识 | 测试用例 | 测试类型 |
| --- | --- | --- |
| REQ-001 | TC-LOCAL-REGRESSION | 注册/认证本地测试 |
| REQ-002 | TC-LOCAL-REGRESSION | 会话与设备归属测试 |
| REQ-003 | TC-LOCAL-REGRESSION | 好友与在线状态测试 |
| REQ-004 | TC-LOCAL-REGRESSION | 群组与持久化测试 |
| REQ-005 | TC-LOCAL-REGRESSION | 邀请与幂等测试 |
| REQ-006 | TC-LOCAL-REGRESSION | 群主转让与恢复测试 |
| REQ-007 | TC-LOCAL-REGRESSION | 权限与撤销测试 |
| REQ-008 | TC-LOCAL-REGRESSION | 房间管理测试 |
| REQ-009 | TC-LOCAL-REGRESSION | 12 人容量与并发测试 |
| REQ-010 | TC-LOCAL-REGRESSION | 活动生命周期测试 |
| REQ-011 | TC-LOCAL-REGRESSION | 代次、租约与恢复测试 |
| REQ-012 | TC-LOCAL-REGRESSION | 语音适配及长会话工具契约；真实音质未验证 |
| REQ-013 | TC-LOCAL-REGRESSION | 设备控制与 A/B 模板契约；真实设备未验证 |
| REQ-014 | TC-LOCAL-REGRESSION | Helper/Relay 与跨平台证据契约；实机未验证 |
| REQ-015 | TC-LOCAL-REGRESSION | 地址恢复与隔离测试 |
| REQ-016 | TC-LOCAL-REGRESSION | 路由与转发边界契约；实机未验证 |
| REQ-017 | TC-LOCAL-REGRESSION | 双通道独立状态与恢复测试 |
| REQ-018 | TC-LOCAL-REGRESSION | 撤销与旧代次拒绝测试 |
| REQ-019 | TC-LOCAL-REGRESSION | 消息历史与 tombstone 测试 |
| REQ-020 | TC-LOCAL-REGRESSION | 审计、日志脱敏与只读权限测试 |
| REQ-021 | TC-LOCAL-REGRESSION | PostgreSQL 迁移和重启持久化测试 |
| REQ-022 | TC-LOCAL-REGRESSION | 安装更新配置与支持矩阵；三平台实机未验证 |
| REQ-023 | TC-LOCAL-REGRESSION | 状态、错误、诊断与恢复测试 |
| REQ-024 | TC-LOCAL-REGRESSION | 凭据、脱敏、IPC 与秘密扫描测试 |
| REQ-025 | TC-LOCAL-REGRESSION | 背压与 C0～C4 工具契约；真实容量未验证 |
| REQ-026 | TC-LOCAL-REGRESSION | 音频配置与 A/B 工具契约；真实听辨未验证 |
| REQ-027 | TC-LOCAL-REGRESSION | 运营路径策略与禁止回退契约测试 |
| REQ-028 | TC-LOCAL-REGRESSION | 锁文件、维护记录、证据索引与资产摘要检查 |

## 准入与准出标准

准入：E 阶段任务全部完成且证据有效，工作流处于 G 执行中。准出：完整本地回归以 `test_engineer` 角色对当前代码树执行并退出 0，REQ-001～REQ-028 均可追踪，无开放缺陷，报告明确列出未验证事项。任一项失败形成缺陷并修复回归；不得通过减少测试、修改验收阈值或伪造平台/容量/音质结果准出。
