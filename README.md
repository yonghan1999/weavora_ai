# 织界：通用 AI 自主软件研发工作空间

这是一套已可执行的本地研发控制平面：需求接收 → 需求分析 → 产品原型/评审 → UI 视觉设计/评审 → 架构设计/评审 → 开发规划 → 编码 → 自测 → 独立测试 → 缺陷修复 → 发布准备 → 验收归档。它不限定未来业务项目的技术栈，也不绑定特定 Coding Agent。

## 环境与安装

- Python 3.10+，仅使用标准库，无需安装依赖。
- 在终端进入本目录，所有命令以 `python zhijie.py` 开头。
- 每个研发项目独立保存在 `projects/<项目标识>/`，业务代码在其 `workspace/`。
- 当前是交互式工作流，不是后台自主守护服务；跨会话通过文件状态恢复。

## 启动第一个研发项目

```bash
python zhijie.py new --id my-app --name "我的应用" --request "在这里描述目标、背景、限制和期望结果"
python zhijie.py status my-app
```

创建后自动完成需求接收并进入 `B_REQUIREMENTS`。让 AI 按 `AGENTS.md` 和 `workflow/spec/` 工作，或由人工填写 `docs/02-requirements.md`。

## 阶段提交与人工审批

```bash
python zhijie.py submit my-app --actor requirements-analyst --role requirements_analyst
python zhijie.py status my-app
```

B、B1、B2、C、H、I 阶段会生成 `approvals/current.json` 并暂停。审批请求包含阶段、文档、基线哈希和审批标识。AI 必须在此等待。

**以下命令只能由人类决策者执行：**

```bash
python zhijie.py approve my-app --approval-id APR-实际标识 --decision approved --actor "你的姓名" --human-confirm I_CONFIRM_HUMAN_DECISION
```

也可将 `--decision` 改为 `rejected` 或 `changes_requested`。驳回后修改文档，再执行：

```bash
python zhijie.py revise my-app --actor requirements-analyst --role requirements_analyst --reason "按审批意见完成修改"
python zhijie.py submit my-app --actor requirements-analyst --role requirements_analyst
```

审批绑定文件哈希；批准后修改基线会使审批失效。

## 产品原型与 UI 设计

新项目在需求批准后依次进入 `B1_PROTOTYPE` 和 `B2_UI`，两者均需人工审批：

1. B1 填写 `docs/02a-prototype.md`，并在 `workspace/design/prototype/index.html` 提供使用模拟数据的低保真可点击原型。确认功能、页面结构、核心流程、权限和异常状态后提交审批。
2. B2 填写 `docs/02b-ui.md`，并在 `workspace/design/ui/index.html` 提供高保真可点击 UI。确认视觉规范、核心页面、组件状态和适配要求后提交审批。
3. 原型和 UI 均须追踪全部 REQ。自动门禁检查交互元素及本地链接，但完整操作路径仍需人工走查，未验证事项须如实记录。

```bash
python zhijie.py submit my-app --actor product-designer --role product_designer
# 人类批准 B1 后，完成 UI 文档与高保真原型
python zhijie.py submit my-app --actor ui-designer --role ui_designer
```

既有项目保持创建时的九阶段路径，不会被自动迁移或使在途审批失效。开发交接必须包含已批准需求、原型、UI 和架构基线。

## 开发任务与真实执行

D 阶段先持久化任务，再完善开发计划：

```bash
python zhijie.py add-task my-app --task-id TASK-001 --title "实现核心功能" --requirements REQ-001 --input "已批准需求和设计" --output "实现与测试" --acceptance "AC-001 通过" --test "项目测试命令" --rollback "回退本任务变更"
python zhijie.py submit my-app --actor tech-lead --role tech_lead
```

E 阶段真实执行命令并保存证据：

```bash
python zhijie.py task-run my-app TASK-001 --actor developer --role developer --command "项目测试命令"
python zhijie.py submit my-app --actor developer --role developer
```

`--command` 必须与任务登记的 `--test` 完全一致，且只能由 `developer` 角色执行；运行前修改命令会被拒绝。任务只能在 D 阶段添加，检查只能在当前执行中的 F/G/H 阶段配置，用例只能在 G 阶段添加。

命令在 `workspace/` 内运行，但这不是操作系统安全沙箱。失败会保存输出并进入等待重试；超时时间为 1–3600 秒，超时会终止进程树。标准输出和错误输出分别最多保留 1,000,000 字节，截断标志写入证据。不要把含秘密或高影响副作用的命令交给执行器。

## 自测与独立测试

F 阶段配置并执行检查，然后填写自测报告：

```bash
python zhijie.py add-check my-app --check-id self-test --stage F_SELF_TEST --command "在此填写测试命令"
python zhijie.py verify my-app --check-id self-test --actor self-tester --role self_tester
python zhijie.py submit my-app --actor self-tester --role self_tester
```

G 阶段添加用例、配置独立检查并逐项执行：

```bash
python zhijie.py add-test my-app --case-id TC-001 --requirements REQ-001 --steps "可复现步骤" --expected "可观察预期结果"
python zhijie.py add-check my-app --check-id independent-test --stage G_INDEPENDENT_TEST --command "在此填写独立测试命令"
python zhijie.py verify my-app --check-id independent-test --case TC-001 --actor tester --role test_engineer
python zhijie.py submit my-app --actor quality-reviewer --role quality_reviewer
```

失败用例自动生成 DEF 缺陷和 FIX 修复任务。修复后须重跑失败用例和必要回归；代码变化会使旧 F/G/H 证据失效。证据文件 SHA-256、登记命令和上下文也必须一致；旧格式证据缺少完整性字段时需要重跑。

## 发布准备、验收与归档

H 阶段用真实文件生成制品清单，对包含制品的最终工作区执行回归，再填写发布文档：

```bash
python zhijie.py release-set my-app --version 0.1.0 --artifact "相对 workspace 的制品路径"
python zhijie.py add-check my-app --check-id release-regression --stage H_RELEASE --command "在此填写最终回归命令"
python zhijie.py verify my-app --check-id release-regression --case TC-001 --actor tester --role test_engineer
python zhijie.py submit my-app --actor release-manager --role release_manager
```

最终回归会刷新所有关联用例的当前代码证据；有多个用例时须逐项重跑并执行必要回归。

人工批准 H 只表示发布材料就绪，**不会也不授权生产部署**。I 阶段填写验收报告和交付索引，提交并由人类最终验收，然后：

```bash
python zhijie.py archive my-app --actor release-archivist
```

归档包含文件哈希清单和 ZIP，结果在 `deliverables/`。

## 暂停、恢复、重试和取消

```bash
python zhijie.py pause my-app --actor operator --role workflow_operator --reason "等待外部条件"
python zhijie.py resume my-app --actor operator --role workflow_operator
python zhijie.py fail my-app --actor operator --role workflow_operator --reason "记录无法自动检查的执行失败"
python zhijie.py retry my-app --actor operator --role workflow_operator --reason "失败原因已修复"
python zhijie.py cancel my-app --actor operator --role workflow_operator --reason "用户取消"
```

新终端或新会话先运行 `status`，无需依赖旧对话。框架元数据由项目级回滚日志保护，异常或崩溃会在下次带锁访问时恢复；外部命令副作用和业务工作区文件不会自动回滚。重试次数耗尽会阻塞并需要人工介入。

## Git 提交与推送

本地提交默认放在需求/设计批准、开发规划通过、单个任务验证通过、缺陷回归通过、自测/独立测试通过、发布批准和最终验收之后。待审批、测试失败、未验证或混入无关变更时不提交。本地 commit 与远程 push 分开授权，详细节点、提交前检查和提交说明格式见 `workflow/spec/GIT.md`。

## 质量检查与测试

```bash
python zhijie.py self-check
python zhijie.py validate my-app
python -m unittest discover -s tests -v
python scripts/validate_workspace.py
```

最后一条命令执行全部初始化验证，并将真实退出码与输出保存到 `var/validation/latest-run.json`。

`validate` 检查当前阶段；模板未填完时失败是预期行为。门禁包括文档结构、REQ/AC、REQ—设计—任务—测试追踪、任务依赖和证据、真实构建测试、人工审批、发布制品及归档版本一致性。

## 目录职责

```text
AGENTS.md                  当前 Coding Agent 规则
zhijie.py                  统一命令入口
workflow/config/           十一阶段与状态机配置（兼容既有九阶段项目）
workflow/spec/             工具无关规范、角色、契约、适配说明
workflow/schemas/          Agent 输入输出 JSON Schema
workflow/templates/        阶段文档模板
workflow/*.py              执行器、门禁与 CLI
projects/<id>/state.json   可恢复状态
projects/<id>/audit.jsonl  审计记录
projects/<id>/docs/        需求到交付文档
projects/<id>/tasks/       任务与依赖
projects/<id>/tests/       用例、缺陷和真实执行证据
projects/<id>/approvals/   人工审批请求与决定
projects/<id>/workspace/   未来业务代码
projects/<id>/deliverables/交付归档
tests/                     工作流自身自动化验证
```

## 已知边界

未接入外部模型 API、多 Agent 后台调度、Web UI 或生产部署。人工审批是本地显式命令，不含操作系统身份认证或数字签名；共享环境需另行保护审批权限。详见 `workflow/spec/ADAPTERS.md`。
