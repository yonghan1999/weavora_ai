from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

from _support import temporary_project_directory
from workflow.engine import Engine, HUMAN_CONFIRMATION, SOURCE_ROOT, WorkflowError, atomic_json, file_lock, sha256_file
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


class CleanCheckoutTests(unittest.TestCase):
    def test_missing_temporary_root_is_created(self):
        with tempfile.TemporaryDirectory() as clean_checkout:
            source_root = Path(clean_checkout)
            temporary_root = source_root / "var" / "tmp"
            self.assertFalse(temporary_root.exists())

            with temporary_project_directory(source_root) as project_directory:
                self.assertTrue(temporary_root.is_dir())
                self.assertTrue(Path(project_directory).is_dir())
                self.assertEqual(Path(project_directory).parent, temporary_root)


class FileLockTests(unittest.TestCase):
    def test_lock_excludes_other_processes_and_is_reusable(self):
        with tempfile.TemporaryDirectory() as directory:
            lock_path = Path(directory) / "project.lock"
            code = """
import sys
from pathlib import Path
from workflow.engine import WorkflowError, file_lock
try:
    with file_lock(Path(sys.argv[1]), timeout=0.1):
        pass
except WorkflowError:
    sys.exit(23)
"""
            with file_lock(lock_path, timeout=0.1):
                blocked = subprocess.run(
                    [sys.executable, "-c", code, str(lock_path)], cwd=SOURCE_ROOT, timeout=10,
                )
                self.assertEqual(blocked.returncode, 23)

            acquired = subprocess.run(
                [sys.executable, "-c", code, str(lock_path)], cwd=SOURCE_ROOT, timeout=10,
            )
            self.assertEqual(acquired.returncode, 0)

    def test_crashed_process_does_not_leave_stale_lock(self):
        with tempfile.TemporaryDirectory() as directory:
            lock_path = Path(directory) / "project.lock"
            crash_code = """
import os
import sys
from pathlib import Path
from workflow.engine import file_lock
with file_lock(Path(sys.argv[1]), timeout=0.1):
    os._exit(0)
"""
            crashed = subprocess.run(
                [sys.executable, "-c", crash_code, str(lock_path)], cwd=SOURCE_ROOT, timeout=10,
            )
            self.assertEqual(crashed.returncode, 0)
            self.assertTrue(lock_path.exists())
            with file_lock(lock_path, timeout=0.1):
                pass


class WorkflowTests(unittest.TestCase):
    def setUp(self):
        self.temporary = temporary_project_directory(SOURCE_ROOT)
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

    def test_migrate_legacy_architecture_progress_to_current_flow(self):
        requirement_request = self.submit_requirements()
        self.approve(requirement_request["approval_id"])
        state = self.engine.load(self.project_id)
        state.pop("workflow_version")
        state["stage"] = "C_DESIGN"
        state["status"] = "in_progress"
        self.engine.save(self.project_id, state)

        base = self.engine.project_dir(self.project_id)
        sections = self.engine.stages["C_DESIGN"]["documents"]["docs/03-architecture.md"]
        architecture = "# 架构与详细设计\n\n" + "\n\n".join(f"## {section}\n\n已设计 REQ-001。" for section in sections)
        architecture_path = base / "docs/03-architecture.md"
        architecture_path.write_text(architecture, encoding="utf-8")
        architecture_request = self.engine.submit(self.project_id, "architect", "architecture_reviewer")["request"]
        request_path = base / f"approvals/{architecture_request['approval_id']}.request.json"

        migrated = self.engine.migrate(self.project_id, "operator", "workflow_operator", "用户明确决定启用原型和 UI 阶段")
        self.assertEqual(migrated["workflow_version"], 2)
        self.assertEqual(migrated["stage"], "B1_PROTOTYPE")
        self.assertEqual(migrated["status"], "in_progress")
        self.assertEqual(migrated["migration"]["invalidated_approval_id"], architecture_request["approval_id"])
        self.assertEqual(architecture_path.read_text(encoding="utf-8"), architecture)
        self.assertTrue(request_path.is_file())
        self.assertIsNone(self.engine.current_approval_request(self.project_id, required=False))
        self.assertTrue((base / "docs/02a-prototype.md").is_file())
        self.assertTrue((base / "docs/02b-ui.md").is_file())
        self.assertTrue((base / "workspace/design/prototype").is_dir())
        self.assertTrue((base / "workspace/design/ui").is_dir())
        self.assertEqual([item["stage"] for item in migrated["approvals"]], ["B_REQUIREMENTS"])
        with self.assertRaisesRegex(WorkflowError, "无需迁移"):
            self.engine.migrate(self.project_id, "operator", "workflow_operator", "重复迁移")

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

    def test_withdraw_pending_approval_for_revision(self):
        first = self.submit_requirements()
        self.engine.revise(self.project_id, "analyst", "requirements_analyst", "用户补充原 IP 优先恢复规则")
        self.assertEqual(self.engine.load(self.project_id)["status"], "in_progress")
        self.assertIsNone(self.engine.current_approval_request(self.project_id, required=False))
        second = self.engine.submit(self.project_id, "analyst", "requirements_analyst")["request"]
        self.assertNotEqual(first["approval_id"], second["approval_id"])

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
        state = self.engine.load(self.project_id)
        state["stage"] = "D_PLANNING"
        self.engine.save(self.project_id, state)
        base = {"requirement_ids": ["REQ-001"], "input": "输入", "output": "输出", "acceptance": "验收", "test": "python --version", "rollback": "回退"}
        self.engine.add_task(self.project_id, {**base, "id": "TASK-001", "title": "前置", "depends_on": []})
        self.engine.add_task(self.project_id, {**base, "id": "TASK-002", "title": "后置", "depends_on": ["TASK-001"]})
        state["stage"] = "E_IMPLEMENTATION"
        self.engine.save(self.project_id, state)
        with self.assertRaisesRegex(WorkflowError, "依赖任务尚未完成"):
            self.engine.run_task(self.project_id, "TASK-002", "developer", "developer", "python --version")

    def test_task_execution_requires_developer_and_registered_command(self):
        state = self.engine.load(self.project_id)
        state["stage"] = "D_PLANNING"
        self.engine.save(self.project_id, state)
        self.engine.add_task(self.project_id, {
            "id": "TASK-001", "title": "验证命令边界", "requirement_ids": ["REQ-001"], "depends_on": [],
            "input": "输入", "output": "输出", "acceptance": "验收", "test": "python --version", "rollback": "回退",
        })
        state["stage"] = "E_IMPLEMENTATION"
        self.engine.save(self.project_id, state)

        with self.assertRaisesRegex(WorkflowError, "developer"):
            self.engine.run_task(self.project_id, "TASK-001", "tester", "test_engineer", "python --version")
        with self.assertRaisesRegex(WorkflowError, "预先登记"):
            self.engine.run_task(self.project_id, "TASK-001", "developer", "developer", "python -c \"print('unreviewed')\"")
        self.assertEqual(list((self.engine.project_dir(self.project_id) / "tests/evidence").glob("*.json")), [])

        evidence = self.engine.run_task(self.project_id, "TASK-001", "developer", "developer", "python --version")
        self.assertEqual(evidence["exit_code"], 0)
        self.assertEqual(evidence["role"], "developer")

    def test_configuration_and_checks_are_bound_to_stage_and_role(self):
        with self.assertRaisesRegex(WorkflowError, "开发规划阶段"):
            self.engine.add_task(self.project_id, {
                "id": "TASK-001", "title": "越阶段任务", "requirement_ids": ["REQ-001"], "depends_on": [],
                "input": "输入", "output": "输出", "acceptance": "验收", "test": "python --version", "rollback": "回退",
            })
        with self.assertRaisesRegex(WorkflowError, "当前执行中"):
            self.engine.add_check(self.project_id, "self-test", "F_SELF_TEST", "python --version")
        with self.assertRaisesRegex(WorkflowError, "独立测试阶段"):
            self.engine.add_test(self.project_id, {"id": "TC-001", "requirement_ids": ["REQ-001"], "steps": "步骤", "expected": "结果"})

        state = self.engine.load(self.project_id)
        state["stage"] = "F_SELF_TEST"
        self.engine.save(self.project_id, state)
        self.engine.add_check(self.project_id, "self-test", "F_SELF_TEST", "python --version")
        with self.assertRaisesRegex(WorkflowError, "self_tester"):
            self.engine.verify(self.project_id, "self-test", "tester", "test_engineer", None)
        with self.assertRaisesRegex(WorkflowError, "G/H"):
            self.engine.verify(self.project_id, "self-test", "self-tester", "self_tester", "TC-001")
        with self.assertRaisesRegex(WorkflowError, "1–3600"):
            self.engine.verify(self.project_id, "self-test", "self-tester", "self_tester", None, timeout=0)

    def test_evidence_hash_command_and_context_are_enforced(self):
        state = self.engine.load(self.project_id)
        state["stage"] = "D_PLANNING"
        self.engine.save(self.project_id, state)
        self.engine.add_task(self.project_id, {
            "id": "TASK-001", "title": "证据完整性", "requirement_ids": ["REQ-001"], "depends_on": [],
            "input": "输入", "output": "输出", "acceptance": "验收", "test": "python --version", "rollback": "回退",
        })
        state["stage"] = "E_IMPLEMENTATION"
        self.engine.save(self.project_id, state)
        evidence = self.engine.run_task(self.project_id, "TASK-001", "developer", "developer", "python --version")
        evidence_path = self.engine.project_dir(self.project_id) / evidence["path"]
        content = json.loads(evidence_path.read_text(encoding="utf-8"))
        content["stdout"] = "tampered"
        evidence_path.write_text(json.dumps(content), encoding="utf-8")

        result = check_stage(self.engine, self.project_id, "E_IMPLEMENTATION")
        self.assertTrue(any("SHA-256" in error for error in result.errors), result.errors)

        content["command"] = "python -c \"print('replacement')\""
        evidence_path.write_text(json.dumps(content), encoding="utf-8")
        tasks_path = self.engine.project_dir(self.project_id) / "tasks/tasks.json"
        tasks = json.loads(tasks_path.read_text(encoding="utf-8"))
        tasks["tasks"][0]["last_evidence_sha256"] = sha256_file(evidence_path)
        tasks_path.write_text(json.dumps(tasks), encoding="utf-8")
        result = check_stage(self.engine, self.project_id, "E_IMPLEMENTATION")
        self.assertTrue(any("命令与登记命令不一致" in error for error in result.errors), result.errors)

    def test_case_evidence_must_come_from_required_stage(self):
        state = self.engine.load(self.project_id)
        state["stage"] = "G_INDEPENDENT_TEST"
        self.engine.save(self.project_id, state)
        self.engine.add_check(self.project_id, "independent-test", "G_INDEPENDENT_TEST", "python --version")
        self.engine.add_test(self.project_id, {"id": "TC-001", "requirement_ids": ["REQ-001"], "steps": "步骤", "expected": "结果"})
        self.engine.verify(self.project_id, "independent-test", "tester", "test_engineer", "TC-001")
        state["stage"] = "H_RELEASE"
        self.engine.save(self.project_id, state)

        result = check_stage(self.engine, self.project_id, "H_RELEASE")
        self.assertTrue(any("缺少 H_RELEASE 阶段证据" in error for error in result.errors), result.errors)

    def test_approval_request_and_decision_files_are_cross_checked(self):
        request = self.submit_requirements()
        base = self.engine.project_dir(self.project_id)
        archived_path = base / f"approvals/{request['approval_id']}.request.json"
        archived = json.loads(archived_path.read_text(encoding="utf-8"))
        archived["summary"] = "tampered"
        archived_path.write_text(json.dumps(archived), encoding="utf-8")
        with self.assertRaisesRegex(WorkflowError, "已归档请求不一致"):
            self.engine.approve(self.project_id, request["approval_id"], "approved", "人类评审", HUMAN_CONFIRMATION)

        archived_path.write_text(json.dumps(request), encoding="utf-8")
        self.approve(request["approval_id"])
        decision_path = base / f"approvals/{request['approval_id']}.decision.json"
        decision = json.loads(decision_path.read_text(encoding="utf-8"))
        decision["decided_by"] = "其他人"
        decision_path.write_text(json.dumps(decision), encoding="utf-8")
        result = check_stage(self.engine, self.project_id, "B1_PROTOTYPE")
        self.assertTrue(any("独立决策文件不一致" in error for error in result.errors), result.errors)

    def test_command_output_is_bounded_in_evidence(self):
        state = self.engine.load(self.project_id)
        state["stage"] = "D_PLANNING"
        self.engine.save(self.project_id, state)
        command = "python output.py"
        workspace = self.engine.project_dir(self.project_id) / "workspace"
        (workspace / "output.py").write_text("print('x' * 1100000)\n", encoding="utf-8")
        self.engine.add_task(self.project_id, {
            "id": "TASK-001", "title": "输出限制", "requirement_ids": ["REQ-001"], "depends_on": [],
            "input": "输入", "output": "输出", "acceptance": "验收", "test": command, "rollback": "回退",
        })
        state["stage"] = "E_IMPLEMENTATION"
        self.engine.save(self.project_id, state)

        evidence = self.engine.run_task(self.project_id, "TASK-001", "developer", "developer", command)
        self.assertEqual(evidence["exit_code"], 0)
        self.assertTrue(evidence["stdout_truncated"])
        self.assertEqual(evidence["capture_limit_bytes"], 1000000)
        self.assertLessEqual(len(evidence["stdout"].encode("utf-8")), 1000000)

    def test_timeout_terminates_child_process_tree(self):
        state = self.engine.load(self.project_id)
        state["stage"] = "D_PLANNING"
        self.engine.save(self.project_id, state)
        workspace = self.engine.project_dir(self.project_id) / "workspace"
        (workspace / "child.py").write_text(
            "import pathlib, time\ntime.sleep(3)\npathlib.Path('child-finished').write_text('finished')\n",
            encoding="utf-8",
        )
        (workspace / "runner.py").write_text(
            "import subprocess, sys, time\nsubprocess.Popen([sys.executable, 'child.py'])\ntime.sleep(30)\n",
            encoding="utf-8",
        )
        self.engine.add_task(self.project_id, {
            "id": "TASK-001", "title": "进程树超时", "requirement_ids": ["REQ-001"], "depends_on": [],
            "input": "输入", "output": "输出", "acceptance": "验收", "test": "python runner.py", "rollback": "回退",
        })
        state["stage"] = "E_IMPLEMENTATION"
        self.engine.save(self.project_id, state)

        evidence = self.engine.run_task(self.project_id, "TASK-001", "developer", "developer", "python runner.py", timeout=1)
        self.assertEqual(evidence["exit_code"], 124)
        self.assertTrue(evidence["timed_out"])
        time.sleep(4)
        self.assertFalse((workspace / "child-finished").exists())

    def test_exception_rolls_back_state_audit_and_tasks(self):
        base = self.engine.project_dir(self.project_id)
        tracked = [base / "state.json", base / "audit.jsonl", base / "tasks/tasks.json"]
        before = {path: path.read_bytes() for path in tracked}
        with self.assertRaisesRegex(RuntimeError, "模拟异常"):
            with self.engine.lock(self.project_id):
                state = self.engine.load(self.project_id)
                self.engine.transition(self.project_id, state, "paused", "operator", "workflow_operator", "模拟部分写入")
                tasks_path = base / "tasks/tasks.json"
                tasks = json.loads(tasks_path.read_text(encoding="utf-8"))
                tasks["tasks"].append({"id": "TASK-PARTIAL"})
                atomic_json(tasks_path, tasks)
                raise RuntimeError("模拟异常")

        for path in tracked:
            self.assertEqual(path.read_bytes(), before[path])
        self.assertFalse(self.engine.transaction_path(self.project_id).exists())

    def test_crashed_transaction_is_recovered_on_status(self):
        base = self.engine.project_dir(self.project_id)
        state_path = base / "state.json"
        audit_path = base / "audit.jsonl"
        before_state = state_path.read_bytes()
        before_audit = audit_path.read_bytes()
        code = """
import os
import sys
from workflow.engine import Engine
engine = Engine(sys.argv[1])
with engine.lock(sys.argv[2]):
    state = engine.load(sys.argv[2])
    engine.transition(sys.argv[2], state, "paused", "operator", "workflow_operator", "crash")
    os._exit(0)
"""
        crashed = subprocess.run(
            [sys.executable, "-c", code, str(self.root), self.project_id], cwd=SOURCE_ROOT, timeout=10,
        )
        self.assertEqual(crashed.returncode, 0)
        self.assertTrue(self.engine.transaction_path(self.project_id).exists())
        self.assertNotEqual(state_path.read_bytes(), before_state)

        status = self.engine.status(self.project_id)
        self.assertEqual(status["state"]["status"], "in_progress")
        self.assertEqual(state_path.read_bytes(), before_state)
        self.assertEqual(audit_path.read_bytes(), before_audit)
        self.assertFalse(self.engine.transaction_path(self.project_id).exists())

    def test_crashed_project_creation_can_be_retried_safely(self):
        project_id = "crash-create"
        code = """
import os
import sys
from workflow.engine import Engine
engine = Engine(sys.argv[1])
def crash(*args):
    os._exit(0)
engine._copy_templates = crash
engine.new_project(sys.argv[2], "崩溃创建", "模拟创建中断")
"""
        crashed = subprocess.run(
            [sys.executable, "-c", code, str(self.root), project_id], cwd=SOURCE_ROOT, timeout=10,
        )
        self.assertEqual(crashed.returncode, 0)
        self.assertTrue(self.engine.project_dir(project_id).exists())
        self.assertTrue(self.engine.transaction_path(project_id).exists())

        state = self.engine.new_project(project_id, "崩溃创建", "模拟创建中断")
        self.assertEqual(state["stage"], "B_REQUIREMENTS")
        self.assertFalse(self.engine.transaction_path(project_id).exists())
        with self.assertRaisesRegex(WorkflowError, "项目已存在"):
            self.engine.new_project(project_id, "重复创建", "不得覆盖")
        self.assertEqual(self.engine.load(project_id)["name"], "崩溃创建")


if __name__ == "__main__":
    unittest.main()
