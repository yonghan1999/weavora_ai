from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

from workflow.engine import Engine, HUMAN_CONFIRMATION, SOURCE_ROOT, WorkflowError, atomic_json


REQUIREMENTS = """# 需求规格说明书：模拟加法工具

## 产品目标
交付最小整数加法函数，仅用于工作流模拟验证。

## 用户故事或使用场景
调用者传入两个整数并取得和。

## 功能需求
| 需求标识 | 描述 | 优先级 | 关联验收标准 |
| --- | --- | --- | --- |
| REQ-001 | 实现两个整数相加 | 必须 | AC-001 |

## 非功能需求
无外部服务依赖，结果确定。

## 业务流程及关键状态
接收输入、计算、返回结果。

## 异常场景与边界条件
覆盖负数相加。

## 验收标准
| 验收标识 | 需求标识 | 可验证条件与预期结果 |
| --- | --- | --- |
| AC-001 | REQ-001 | 自动化测试断言 add(2,3)==5 且 add(-1,1)==0 |

## 需求追踪
REQ-001 → AC-001。
"""

ARCHITECTURE = """# 架构与详细设计

## 架构概述
单文件纯函数，最小可行实现。

## 技术选型及依据
模拟需求仅需要纯函数；当前 Python 可用，故模拟项目选择 Python 标准库，不构成未来项目预设。

## 模块划分与职责
calculator.py 提供 add；tests 提供自动化验证。

## 核心数据模型
两个整数输入和一个整数输出，无持久化数据。

## 接口契约
add(a: int, b: int) -> int。

## 安全与权限设计
无网络、文件写入、认证或权限边界。

## 部署及运行方案
以源码制品交付，使用本地 Python 运行测试。

## 技术风险和待验证事项
通过自动化测试验证运算正确性。

## 关键技术决策
ADR-001：不为单函数引入框架或外部依赖。

## 需求追踪
REQ-001 → calculator.add → 自动化测试。
"""

PLAN = """# 开发计划

## 开发里程碑
M1 完成 REQ-001 实现与测试。

## 任务列表
TASK-001：实现加法函数和单元测试。

## 任务依赖关系
TASK-001 无前置依赖。

## 完成定义
代码存在，任务命令真实执行且退出码为 0。

## 测试要求
覆盖正数与负数，失败必须修复并回归。

## 风险和回退策略
实现错误时保留失败证据并回退本任务代码。

## 需求追踪
REQ-001 → TASK-001 → 执行证据。
"""


class EndToEndTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir=SOURCE_ROOT / "var/tmp")
        self.root = Path(self.temporary.name)
        self.engine = Engine(self.root)
        self.project_id = "simulated-e2e"
        self.commands = []
        command = [
            sys.executable, str(SOURCE_ROOT / "zhijie.py"), "--root", str(self.root),
            "new", "--id", self.project_id, "--name", "模拟加法工具",
            "--request", "【模拟】实现最小整数加法函数，用于验证研发工作流。",
        ]
        completed = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", check=True)
        self.commands.append({"command": "zhijie.py new", "exit_code": completed.returncode})
        self.base = self.engine.project_dir(self.project_id)
        self.workspace = self.base / "workspace"

    def tearDown(self):
        self.temporary.cleanup()

    def write(self, relative: str, content: str):
        path = self.base / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    def approve(self):
        request = self.engine.current_approval_request(self.project_id)
        self.engine.approve(self.project_id, request["approval_id"], "approved", "模拟人类评审", HUMAN_CONFIRMATION)

    def test_complete_simulated_workflow(self):
        self.write("docs/02-requirements.md", REQUIREMENTS)
        self.engine.submit(self.project_id, "analyst", "requirements_analyst")
        with self.assertRaises(WorkflowError):
            self.engine.submit(self.project_id, "analyst", "requirements_analyst")
        approval_blocked = True
        self.approve()
        self.assertEqual(self.engine.load(self.project_id)["stage"], "C_DESIGN")

        self.write("docs/03-architecture.md", ARCHITECTURE)
        self.engine.submit(self.project_id, "architect", "system_architect")
        self.approve()

        self.engine.add_task(self.project_id, {
            "id": "TASK-001", "title": "实现加法", "requirement_ids": ["REQ-001"], "depends_on": [],
            "input": "REQ-001 和接口契约", "output": "calculator.py 与单元测试",
            "acceptance": "AC-001 自动化测试通过", "test": "python -m unittest discover -s tests -v",
            "rollback": "恢复本任务修改前的 calculator.py",
        })
        self.write("docs/04-development-plan.md", PLAN)
        self.engine.submit(self.project_id, "tech-lead", "tech_lead")

        self.write("workspace/calculator.py", "def add(a, b):\n    return a - b  # 模拟初始缺陷\n")
        self.write("workspace/tests/test_calculator.py", """import unittest
from calculator import add

class CalculatorTests(unittest.TestCase):
    def test_add(self):
        self.assertEqual(add(2, 3), 5)
        self.assertEqual(add(-1, 1), 0)

if __name__ == '__main__':
    unittest.main()
""")
        failed = self.engine.run_task(self.project_id, "TASK-001", "developer", "developer", "python -m unittest discover -s tests -v")
        self.assertNotEqual(failed["exit_code"], 0)
        self.assertEqual(self.engine.load(self.project_id)["status"], "awaiting_retry")
        self.engine.retry(self.project_id, "developer", "developer", "修正减法误用")
        self.write("workspace/calculator.py", "def add(a, b):\n    return a + b\n")
        passed = self.engine.run_task(self.project_id, "TASK-001", "developer", "developer", "python -m unittest discover -s tests -v")
        self.assertEqual(passed["exit_code"], 0, passed["stderr"])
        self.engine.submit(self.project_id, "developer", "developer")

        self.engine.add_check(self.project_id, "self-test", "F_SELF_TEST", "python -m unittest discover -s tests -v")
        self_evidence = self.engine.verify(self.project_id, "self-test", "self-tester", "self_tester", None)
        self.write("docs/05-self-test.md", f"""# 开发自测报告

## 构建或运行检查
纯源码可由测试导入，证据 {self_evidence['path']}。

## 自动化测试
正数和负数用例通过，证据 {self_evidence['path']}。

## 异常与边界检查
负数边界通过。

## 设计符合性
接口与已批准设计一致。

## 安全检查
无网络、外部输入或敏感数据。

## 未完成实现检查
无占位实现；初始缺陷已修复。

## 结论与证据
当前代码自测通过，证据 {self_evidence['path']}。
""")
        self.engine.submit(self.project_id, "self-tester", "self_tester")

        self.engine.add_test(self.project_id, {"id": "TC-001", "requirement_ids": ["REQ-001"], "steps": "运行 unittest 加法测试", "expected": "两个断言均通过，退出码 0"})
        self.engine.add_check(self.project_id, "independent-test", "G_INDEPENDENT_TEST", "python -m unittest discover -s tests -v")
        self.write("workspace/calculator.py", "def add(a, b):\n    return a * b  # 模拟独立测试发现的回归缺陷\n")
        independent_failure = self.engine.verify(self.project_id, "independent-test", "tester", "test_engineer", "TC-001")
        self.assertNotEqual(independent_failure["exit_code"], 0)
        defects = json.loads((self.base / "tests/defects.json").read_text(encoding="utf-8"))["defects"]
        self.assertEqual(defects[0]["status"], "open")
        self.engine.retry(self.project_id, "developer", "developer", "修复独立测试缺陷")
        self.write("workspace/calculator.py", "def add(a, b):\n    return a + b\n")
        independent_pass = self.engine.verify(self.project_id, "independent-test", "tester", "test_engineer", "TC-001")
        self.assertEqual(independent_pass["exit_code"], 0)
        tasks = json.loads((self.base / "tasks/tasks.json").read_text(encoding="utf-8"))["tasks"]
        self.assertTrue(any(item["id"] == "FIX-TC-001" and item["status"] == "completed" for item in tasks))
        self.write("docs/06-test-plan.md", """# 独立测试计划

## 测试范围
功能、边界和回归；无网络、兼容性或性能需求，故不适用于本模拟。

## 测试环境
隔离目录中的本地 Python 标准库环境。

## 测试用例
TC-001：执行正数和负数加法断言。

## 需求覆盖
REQ-001 → TC-001，功能与边界回归。

## 准入与准出标准
实现可导入；所有用例通过且无开放缺陷方可准出。
""")
        self.write("docs/07-test-report.md", f"""# 独立测试报告

## 实际执行记录
TC-001 初次失败并生成 DEF-TC-001；修复后通过。最终证据 {independent_pass['path']}。

## 失败用例与缺陷
DEF-TC-001 已解决，失败证据 {independent_failure['path']}，解决证据 {independent_pass['path']}。

## 需求覆盖结果
REQ-001：TC-001 通过，证据 {independent_pass['path']}。

## 回归测试
修复后重新执行完整 unittest，退出码 0。

## 未验证事项
性能、兼容性和安全渗透不适用于此无接口纯函数模拟，未执行。

## 测试结论
REQ-001 在当前代码版本通过；其他类别未验证，不作扩张结论。
""")
        self.engine.submit(self.project_id, "tester", "test_engineer")

        self.write("workspace/dist/calculator.py", "def add(a, b):\n    return a + b\n")
        release = self.engine.set_release(self.project_id, "0.1.0", ["dist/calculator.py"])
        self.engine.add_check(self.project_id, "release-regression", "H_RELEASE", "python -m unittest discover -s tests -v")
        release_evidence = self.engine.verify(self.project_id, "release-regression", "tester", "test_engineer", "TC-001")
        self.assertEqual(release_evidence["exit_code"], 0)
        self.write("docs/08-release.md", f"""# 发布准备

## 发布版本及变更说明
0.1.0：模拟加法函数首次发布准备。

## 构建产物或制品获取方式
workspace/dist/calculator.py，SHA-256 已写入 release.json。

## 部署和配置说明
仅准备源码制品；未部署生产，无配置。

## 数据迁移方案
无持久化数据，数据迁移不适用。

## 安全检查结果
独立检查通过；未进行外部安全渗透，未声称覆盖。

## 已知问题列表
无开放缺陷；非功能扩展未验证。

## 回滚或恢复方案
停止使用 0.1.0 制品并恢复上一已验证制品；本模拟无上一版本。

## 发布检查清单
版本 {release['version']}、代码 {release['code_version']}、制品哈希和测试证据已核验。
""")
        self.engine.submit(self.project_id, "release-manager", "release_manager")
        self.approve()

        self.write("docs/09-acceptance.md", f"""# 最终验收报告

## 交付范围
REQ-001、源码、测试、证据和 0.1.0 源码制品。

## 需求验收结果
REQ-001 / AC-001 通过独立测试。

## 质量门禁结果
需求、设计、任务、测试、发布和审批门禁通过；不适用类别已披露。

## 已知问题和遗留风险
仅模拟最小函数，不代表完整业务产品；无开放缺陷。

## 最终验收结论
建议人类验收版本 0.1.0，代码版本 {release['code_version']}。
""")
        self.write("docs/10-delivery-index.md", f"""# 交付物索引

## 需求与设计基线
docs/02-requirements.md；docs/03-architecture.md；含人工批准记录。

## 任务与代码版本
tasks/tasks.json；代码版本 {release['code_version']}。

## 构建与测试证据
tests/evidence/；docs/05-self-test.md；docs/07-test-report.md。

## 缺陷处理记录
tests/defects.json；DEF-TC-001 已解决。

## 发布与回滚说明
docs/08-release.md；release.json；未部署生产。

## 交付物索引
docs/、workspace/、tasks/、tests/、approvals/、state.json、audit.jsonl。

## 版本信息
发布版本 0.1.0；代码版本 {release['code_version']}。
""")
        self.engine.submit(self.project_id, "quality-reviewer", "quality_reviewer")
        self.approve()
        self.assertEqual(self.engine.load(self.project_id)["status"], "completed")
        archive = self.engine.archive(self.project_id, "release-archivist")
        archive_path = Path(archive["archive"])
        self.assertTrue(archive_path.is_file())
        with zipfile.ZipFile(archive_path) as bundle:
            self.assertIsNone(bundle.testzip())
            names = bundle.namelist()
            self.assertTrue(any(name.endswith("docs/02-requirements.md") for name in names))
            self.assertTrue(any(name.endswith("deliverables/archive-manifest.json") for name in names))

        evidence_count = len(list((self.base / "tests/evidence").glob("*.json")))
        report = {
            "schema_version": 1,
            "simulation": True,
            "description": "隔离临时目录中的最小加法工具，不是实际业务项目成果",
            "result": "passed",
            "stages_verified": self.engine.stage_order,
            "approval_block_verified": approval_blocked,
            "development_failure_and_retry_verified": True,
            "independent_defect_and_regression_verified": True,
            "archive_verified": True,
            "archive_sha256": archive["sha256"],
            "archive_file_count": archive["files"],
            "real_command_evidence_count": evidence_count,
            "entry_command_exit_codes": self.commands,
            "temporary_project_cleaned_on_teardown": True,
        }
        atomic_json(SOURCE_ROOT / "var/validation/end-to-end.json", report)


if __name__ == "__main__":
    unittest.main()
