from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from workflow.engine import Engine, HUMAN_CONFIRMATION, SOURCE_ROOT, WorkflowError
from workflow.gates import check_installation, check_stage


REQUIREMENTS = """# 需求规格说明书

## 产品目标
实现可验证的整数加法。

## 用户故事或使用场景
用户调用加法函数获得结果。

## 功能需求
| 需求标识 | 描述 | 优先级 | 关联验收标准 |
| --- | --- | --- | --- |
| REQ-001 | 两个整数相加 | 必须 | AC-001 |

## 非功能需求
无网络依赖，结果确定。

## 业务流程及关键状态
输入两个整数，输出和。

## 异常场景与边界条件
覆盖负数。

## 验收标准
| 验收标识 | 需求标识 | 可验证条件与预期结果 |
| --- | --- | --- |
| AC-001 | REQ-001 | add(2, 3) 返回 5，add(-1, 1) 返回 0 |

## 需求追踪
REQ-001 映射 AC-001。
"""

PROTOTYPE = """# 产品原型设计

## 设计目标与范围
验证整数加法录入与结果展示。

## 功能清单
| 功能/模块 | 页面或处理 | 关联需求 | 说明 |
| --- | --- | --- | --- |
| 加法 | 计算页 | REQ-001 | 输入并显示结果 |

## 页面结构
计算页包含输入区与结果区。

## 核心流程
输入两个整数，点击计算，查看结果。

## 角色与权限
所有演示用户均可计算。

## 关键状态与异常
覆盖初始、输入不完整和计算结果状态。

## 可点击原型
入口为 workspace/design/prototype/index.html；人工走查待审批人执行。

## 需求追踪
| 需求标识 | 页面/交互或后台处理 | 验收关联 |
| --- | --- | --- |
| REQ-001 | 计算页与计算按钮 | AC-001 |

## 设计假设与待确认事项
仅验证流程，未验证真实浏览器兼容性。
"""

UI = """# UI 视觉设计

## 视觉设计目标
清晰、简洁的演示界面。

## 设计系统
使用系统字体、蓝色主按钮和统一间距。

## 高保真页面
计算页对应 REQ-001 与已批准原型。

## 组件与交互状态
定义输入框、按钮默认和禁用状态。

## 响应式与可访问性
窄屏纵向排列；仅提出规则，未执行设备验证。

## 可点击 UI 原型
入口为 workspace/design/ui/index.html；人工走查待审批人执行。

## 需求追踪
| 需求标识 | 高保真页面/组件或不适用说明 | 验收关联 |
| --- | --- | --- |
| REQ-001 | 高保真计算页 | AC-001 |

## 未验证事项
未验证真实浏览器、设备及辅助技术兼容性。
"""

INTERACTIVE_HTML = """<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><title>计算原型</title></head>
<body><main><label>整数 <input type="number"></label><button type="button">计算</button></main></body></html>
"""


class WorkflowTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir=SOURCE_ROOT / "var/tmp")
        self.root = Path(self.temporary.name)
        self.engine = Engine(self.root)
        self.project_id = "demo-project"
        self.engine.new_project(self.project_id, "演示项目", "模拟整数加法需求")

    def tearDown(self):
        self.temporary.cleanup()

    def write_requirements(self):
        (self.engine.project_dir(self.project_id) / "docs/02-requirements.md").write_text(REQUIREMENTS, encoding="utf-8")

    def submit_requirements(self):
        self.write_requirements()
        return self.engine.submit(self.project_id, "analyst", "requirements_analyst")["request"]

    def approve(self, approval_id):
        self.engine.approve(self.project_id, approval_id, "approved", "人类评审", HUMAN_CONFIRMATION)

    def write_design(self, stage):
        base = self.engine.project_dir(self.project_id)
        if stage == "B1_PROTOTYPE":
            document, html = "docs/02a-prototype.md", "workspace/design/prototype/index.html"
            content = PROTOTYPE
        else:
            document, html = "docs/02b-ui.md", "workspace/design/ui/index.html"
            content = UI
        (base / document).write_text(content, encoding="utf-8")
        (base / html).parent.mkdir(parents=True, exist_ok=True)
        (base / html).write_text(INTERACTIVE_HTML, encoding="utf-8")

    def test_installation_and_json_contracts(self):
        result = check_installation()
        self.assertTrue(result.ok, result.errors)
        for name in ["agent-task.schema.json", "agent-result.schema.json"]:
            schema = json.loads((SOURCE_ROOT / "workflow/schemas" / name).read_text(encoding="utf-8"))
            self.assertEqual(schema["type"], "object")
            self.assertTrue(schema["required"])

    def test_template_gate_and_human_approval(self):
        with self.assertRaisesRegex(WorkflowError, "占位符"):
            self.engine.submit(self.project_id, "analyst", "requirements_analyst")
        request = self.submit_requirements()
        state = self.engine.load(self.project_id)
        self.assertEqual(state["status"], "awaiting_approval")
        with self.assertRaisesRegex(WorkflowError, "重复提交"):
            self.engine.submit(self.project_id, "analyst", "requirements_analyst")
        with self.assertRaisesRegex(WorkflowError, "人工确认"):
            self.engine.approve(self.project_id, request["approval_id"], "approved", "人类评审", "wrong")
        self.engine.approve(self.project_id, request["approval_id"], "approved", "人类评审", HUMAN_CONFIRMATION)
        state = self.engine.load(self.project_id)
        self.assertEqual(state["workflow_version"], 2)
        self.assertEqual(state["stage"], "B1_PROTOTYPE")
        self.assertEqual(state["status"], "in_progress")
        self.assertTrue(any(item["to"] == "approved" and item["human_authorization"] for item in state["history"]))

    def test_prototype_then_ui_require_separate_human_approvals(self):
        request = self.submit_requirements()
        self.approve(request["approval_id"])
        self.write_design("B1_PROTOTYPE")
        prototype_request = self.engine.submit(self.project_id, "designer", "product_designer")["request"]
        self.assertEqual(prototype_request["stage"], "B1_PROTOTYPE")
        self.assertIn("workspace/design/prototype/index.html", prototype_request["documents"])
        self.approve(prototype_request["approval_id"])
        self.assertEqual(self.engine.load(self.project_id)["stage"], "B2_UI")

        self.write_design("B2_UI")
        ui_request = self.engine.submit(self.project_id, "designer", "ui_designer")["request"]
        self.assertIn("docs/02a-prototype.md", ui_request["documents"])
        self.assertIn("workspace/design/ui/index.html", ui_request["documents"])
        self.approve(ui_request["approval_id"])
        self.assertEqual(self.engine.load(self.project_id)["stage"], "C_DESIGN")

    def test_design_gate_rejects_missing_trace_and_static_mockup(self):
        request = self.submit_requirements()
        self.approve(request["approval_id"])
        self.write_design("B1_PROTOTYPE")
        base = self.engine.project_dir(self.project_id)
        document = base / "docs/02a-prototype.md"
        document.write_text(document.read_text(encoding="utf-8").replace("REQ-001 | 计算页", "REQ-999 | 计算页"), encoding="utf-8")
        (base / "workspace/design/prototype/index.html").write_text("<html><body>静态图</body></html>", encoding="utf-8")
        result = check_stage(self.engine, self.project_id, "B1_PROTOTYPE")
        self.assertFalse(result.ok)
        self.assertTrue(any("REQ-001" in error for error in result.errors))
        self.assertTrue(any("REQ-999" in error for error in result.errors))
        self.assertTrue(any("交互元素" in error for error in result.errors))

    def test_legacy_stage_order_does_not_force_new_design_stages(self):
        order = self.engine.stage_order_for({"workflow_version": 1})
        self.assertNotIn("B1_PROTOTYPE", order)
        self.assertNotIn("B2_UI", order)
        self.assertEqual(order[order.index("B_REQUIREMENTS") + 1], "C_DESIGN")

    def test_baseline_change_invalidates_approval(self):
        request = self.submit_requirements()
        path = self.engine.project_dir(self.project_id) / "docs/02-requirements.md"
        path.write_text(path.read_text(encoding="utf-8") + "\n审批后新增范围。\n", encoding="utf-8")
        with self.assertRaisesRegex(WorkflowError, "基线已变化"):
            self.engine.approve(self.project_id, request["approval_id"], "approved", "人类评审", HUMAN_CONFIRMATION)
        self.assertEqual(self.engine.load(self.project_id)["status"], "awaiting_approval")

    def test_rejection_revision_and_resubmission(self):
        first = self.submit_requirements()
        with self.assertRaisesRegex(WorkflowError, "具体原因"):
            self.engine.approve(self.project_id, first["approval_id"], "changes_requested", "人类评审", HUMAN_CONFIRMATION)
        self.engine.approve(self.project_id, first["approval_id"], "changes_requested", "人类评审", HUMAN_CONFIRMATION, "补充负数边界")
        self.assertEqual(self.engine.load(self.project_id)["status"], "rejected")
        self.engine.revise(self.project_id, "analyst", "requirements_analyst", "补充边界说明")
        second = self.engine.submit(self.project_id, "analyst", "requirements_analyst")["request"]
        self.assertNotEqual(first["approval_id"], second["approval_id"])
        decisions = list((self.engine.project_dir(self.project_id) / "approvals").glob("*.decision.json"))
        self.assertEqual(len(decisions), 1)
        self.assertIn("负数边界", decisions[0].read_text(encoding="utf-8"))

    def test_explicit_failure_and_retry(self):
        self.engine.fail(self.project_id, "operator", "workflow_operator", "外部执行失败")
        self.assertEqual(self.engine.load(self.project_id)["status"], "failed")
        self.engine.retry(self.project_id, "operator", "workflow_operator", "外部条件已恢复")
        self.assertEqual(self.engine.load(self.project_id)["status"], "in_progress")

    def test_pause_resume_persists_status(self):
        self.submit_requirements()
        self.engine.pause(self.project_id, "operator", "workflow_operator", "等待评审时间")
        self.assertEqual(Engine(self.root).load(self.project_id)["status"], "paused")
        self.engine.resume(self.project_id, "operator", "workflow_operator")
        self.assertEqual(self.engine.load(self.project_id)["status"], "awaiting_approval")

    def test_invalid_transition_is_rejected(self):
        state = self.engine.load(self.project_id)
        state["status"] = "pending"
        with self.assertRaisesRegex(WorkflowError, "非法状态迁移"):
            self.engine.transition(self.project_id, state, "approved", "agent", "developer", "尝试越权")

    def test_incomplete_dependency_blocks_execution(self):
        base = {"requirement_ids": ["REQ-001"], "input": "输入", "output": "输出", "acceptance": "验收", "test": "测试", "rollback": "回退"}
        self.engine.add_task(self.project_id, {**base, "id": "TASK-001", "title": "前置", "depends_on": []})
        self.engine.add_task(self.project_id, {**base, "id": "TASK-002", "title": "后置", "depends_on": ["TASK-001"]})
        state = self.engine.load(self.project_id)
        state["stage"] = "E_IMPLEMENTATION"
        state["status"] = "in_progress"
        self.engine.save(self.project_id, state)
        with self.assertRaisesRegex(WorkflowError, "依赖任务尚未完成"):
            self.engine.run_task(self.project_id, "TASK-002", "developer", "developer", "python --version")


if __name__ == "__main__":
    unittest.main()
