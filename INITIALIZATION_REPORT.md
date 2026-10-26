# 织界通用 AI 软件研发工作空间初始化报告

- 初始化日期：2026-10-26
- 工作目录：`D:\claude\weavora_ai`
- 总体结果：**当前环境支持范围内的初始化已完成并通过验证**
- 原始任务说明：`任务说明.txt`，已保留，未覆盖
- 当前业务项目：无；端到端验证使用的是已清理的隔离模拟项目

## 1. 已建立的项目结构

```text
AGENTS.md                       当前 Coding Agent 项目规则
README.md                       启动、审批、开发、测试、恢复和归档指南
INITIALIZATION_REPORT.md        本报告
zhijie.py                       统一可执行入口
workflow/
  config/stages.json            A–I 阶段、文档和审批配置
  config/state_machine.json     状态及合法迁移
  engine.py                     持久化、审批、执行、恢复和归档
  gates.py                      可执行质量门禁
  cli.py                        命令行适配
  spec/                         工具无关规范
  schemas/                      Agent 输入输出 JSON Schema
  templates/                    十类研发文档模板
projects/                       独立研发项目运行时数据
scripts/validate_workspace.py   一键验证入口
tests/                          单元及端到端测试
var/validation/                 保留的验证报告
var/tmp/                        隔离临时测试目录，已清空
```

初始化前目录不是 Git 仓库，也没有既有项目规则或业务文件。本实现未擅自初始化 Git；代码版本采用可验证的工作区树 SHA-256。

## 2. 各目录和关键文件职责

- `workflow/config/`：声明九阶段和全部合法状态迁移，不依赖自然语言流程图。
- `workflow/spec/`：定义角色职责、任务边界、输入输出契约、状态机和工具适配方式。
- `workflow/templates/`：提供需求、设计、计划、自测、独立测试、发布、验收和交付模板。
- `projects/<id>/docs/`：未来项目的阶段文档与基线。
- `projects/<id>/tasks/`：持久化任务、依赖、完成状态和执行证据引用。
- `projects/<id>/tests/`：测试用例、缺陷及真实命令执行记录。
- `projects/<id>/approvals/`：审批请求、基线哈希和人类决定。
- `projects/<id>/workspace/`：未来业务代码，与工作流基础设施分离。
- `projects/<id>/deliverables/`：最终归档清单、ZIP 和哈希结果。

## 3. 已实现并验证的工作流阶段

A 需求接收、B 需求分析与评审、C 架构与详细设计、D 开发规划、E 编码实现、F 开发自测、G 独立测试与质量验收、H 发布准备、I 最终验收与归档均已实现，并在隔离模拟项目完整执行。

支持状态：待开始、执行中、待人工审批、已批准、审批驳回、执行失败、等待重试、被阻塞、已暂停、已取消、已完成。非法迁移会被执行器拒绝。

## 4. 已实现并验证的人工审批机制

- B 需求、C 设计、H 发布准备、I 最终验收强制人工审批。
- AI 项目规则禁止执行 `approve`；命令要求具体人类审批人和明确确认值。
- 审批请求包含阶段、事项摘要、重要决策、文档路径、风险影响、验收检查和可选决定。
- 驳回或要求修改必须填写原因；修改后可重新提交，历史决定保留。
- 审批绑定基线 SHA-256；批准后修改基线会使旧审批失效。
- H 批准只表示材料就绪，不代表或授权生产部署。

已验证：重复提交无法绕过审批、错误确认值被拒绝、驳回原因留痕、基线篡改后审批失败。

## 5. 已实现并验证的状态持久化与恢复

- `state.json` 保存当前阶段、状态、重试次数、审批和历史摘要。
- `audit.jsonl` 追加记录每次状态变化的时间、角色、原因、证据和人工授权标志。
- 文件采用同目录临时文件原子替换；项目锁用于避免并发写入。
- `pause` 保存恢复前状态，`resume` 可从新引擎实例恢复。
- `fail`、`retry`、`revise`、`cancel` 和终态均有明确迁移限制。

已验证暂停后重新读取状态并恢复到待审批，以及显式失败后的重试。

## 6. 已实现并验证的质量门禁

1. 必需文档章节及未填写占位符检查。
2. REQ 唯一定义、AC 关联及未知引用检查。
3. REQ—架构—任务—测试用例—报告追踪检查。
4. 任务契约、依赖、循环依赖和完成条件检查。
5. 真实命令退出码、角色、时间和代码树哈希证据检查。
6. F/G/H 当前代码构建与自动化测试检查；代码变化使旧证据失效。
7. 人工审批来源和基线一致性检查。
8. 发布版本、制品存在性和制品哈希检查。
9. 最终制品回归、归档版本一致性和交付完整性检查。

测试失败自动创建 DEF 缺陷和 FIX 任务；成功回归后记录缺陷解决及修复任务完成证据。

## 7. 已实现并验证的 Agent 接入方式

当前适配为“交互式 Coding Agent 或人工终端 + 本地 Python CLI”。Agent 按 `AGENTS.md`、角色规范和 Schema 执行非审批工作；人类独立执行审批。CLI 不绑定特定模型或 Agent 产品。

未声称实现后台自主唤醒。新会话可通过 `status`、文档、状态和审计日志继续工作。

## 8. 如何启动第一个软件研发项目

```bash
python zhijie.py new --id my-app --name "我的应用" --request "描述目标、背景、限制和期望结果"
```

该命令真实创建项目、需求记录和全部阶段模板，完成 A 阶段并进入 B 需求分析。随后让 AI 按项目规则分析并填写需求，或人工编辑对应文档。

## 9. 提交需求、查看状态和处理审批

```bash
python zhijie.py status my-app
python zhijie.py submit my-app --actor requirements-analyst --role requirements_analyst
```

到达审批节点后，查看 `projects/my-app/approvals/current.json`。人类决策者执行：

```bash
python zhijie.py approve my-app --approval-id APR-实际标识 --decision approved --actor "你的姓名" --human-confirm I_CONFIRM_HUMAN_DECISION
```

完整任务、测试、发布和归档命令见 `README.md`。

## 10. 恢复失败或中断任务

```bash
python zhijie.py status my-app
python zhijie.py resume my-app --actor operator --role workflow_operator
python zhijie.py retry my-app --actor operator --role workflow_operator --reason "失败原因已修复"
```

暂停使用 `resume`；审批驳回使用 `revise`；失败或等待重试使用 `retry`。重试耗尽进入阻塞，需要人工判断。不能通过直接修改状态文件恢复或越权。

## 11. 已执行验证命令和实际结果

| 命令 | 实际结果 |
| --- | --- |
| `python -m compileall -q zhijie.py workflow tests` | 退出码 0 |
| `python zhijie.py self-check` | 退出码 0，`ok: true` |
| `python -m unittest discover -s tests -v` | 退出码 0，9 项测试全部通过 |
| `python scripts/validate_workspace.py` | 退出码 0，汇总 `overall: passed` |
| `python zhijie.py status` | 退出码 0，返回空项目列表，确认模拟数据未残留 |
| `python zhijie.py --help` | 退出码 0，全部命令入口可用 |

完整原始输出保存在 `var/validation/latest-run.json`。

验证过程中修复了两个实际问题：Windows 命令壳 AutoRun 干扰退出码，以及新增发布制品后最终回归证据过期。修复后全部测试重新执行通过。

## 12. 端到端验证结果

隔离模拟需求为一个最小整数加法工具，明确不是实际业务成果。验证覆盖：

1. CLI 创建项目并生成需求记录。
2. 需求审批暂停，重复提交被阻止，模拟人类明确批准后继续。
3. 架构批准、开发任务持久化和追踪。
4. 初始错误实现真实测试失败，保存证据并进入等待重试。
5. 修复后真实单元测试通过。
6. 开发自测和独立测试角色证据分离。
7. 独立测试发现回归缺陷，自动创建缺陷与修复任务，修复后回归通过。
8. 发布版本和制品哈希生成，最终工作区回归通过。
9. 发布准备和最终验收人工审批。
10. 交付完整性门禁、归档清单和 ZIP  CRC 检查通过。

结果：9 个阶段全部通过；产生 6 份真实命令证据；验证归档包含 37 个文件。归档 SHA-256 为 `86b57d68d6c01b773c5a0fc65d9e9dcaad324568089c2e725e6651c583be8412`。临时项目和归档已清理，仅保留 `var/validation/end-to-end.json` 报告。

## 13. 尚未实现或尚未验证的能力

### 已实现但未在当前环境完整验证

- 非 Windows 操作系统的实际运行；代码包含 POSIX 默认 shell 路径，但未实机测试。
- 高并发写入压力测试；已实现项目锁和原子写入，但未进行大规模并发基准验证。
- JSON Schema 已解析并检查基本结构，未引入第三方 Schema 验证器进行全量实例验证。

### 仅有设计或文档

- 其他 Coding Agent 的接入规范和人工身份适配边界。
- 生产部署、生产数据迁移和不可逆操作的授权流程；未执行任何生产操作。

### 需要外部工具、权限或服务

- 外部模型 API 自动调用、多 Agent 后台调度和新需求自动唤醒。
- Web 操作界面、消息队列、远程执行集群。
- 操作系统级审批身份认证、数字签名和集中权限控制。
- 未来业务项目特有的构建、部署、性能、兼容性或安全测试服务。

## 14. 后续建议

直接用一个真实但范围较小的软件需求启动首个项目，在 B 阶段确认需求基线后再选择技术栈。若后续需要无人值守运行，再单独接入带真实人工身份审批的调度服务，不要通过自动批准绕过当前门禁。
