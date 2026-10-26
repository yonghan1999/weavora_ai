from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from workflow.engine import Engine, HUMAN_CONFIRMATION, SOURCE_ROOT, WorkflowError
from workflow.gates import check_installation


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
        self.assertEqual(state["stage"], "C_DESIGN")
        self.assertEqual(state["status"], "in_progress")
        self.assertTrue(any(item["to"] == "approved" and item["human_authorization"] for item in state["history"]))

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
