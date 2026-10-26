from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from .engine import PROJECT_ID_RE, SOURCE_ROOT, WorkflowError, read_json, sha256_file, tree_digest

REQ_RE = re.compile(r"REQ-\d{3,}")


@dataclass
class GateResult:
    stage: str
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    checks: list[str] = field(default_factory=list)
    evidence: list[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not self.errors

    def to_dict(self) -> dict[str, Any]:
        return {
            "stage": self.stage,
            "ok": self.ok,
            "errors": self.errors,
            "warnings": self.warnings,
            "checks": self.checks,
            "evidence": self.evidence,
        }


def _section(text: str, heading: str) -> str:
    match = re.search(rf"^##\s+{re.escape(heading)}\s*$", text, re.MULTILINE)
    if not match:
        return ""
    next_heading = re.search(r"^##\s+", text[match.end():], re.MULTILINE)
    end = match.end() + next_heading.start() if next_heading else len(text)
    return text[match.end():end]


def requirement_ids(requirements_text: str) -> list[str]:
    return re.findall(r"^\|\s*(REQ-\d{3,})\s*\|", _section(requirements_text, "功能需求"), re.MULTILINE)


def _check_documents(engine, project_id: str, stage: dict[str, Any], result: GateResult) -> dict[str, str]:
    base = engine.project_dir(project_id)
    contents: dict[str, str] = {}
    for relative, sections in stage.get("documents", {}).items():
        path = base / relative
        if not path.is_file():
            result.errors.append(f"缺少文档：{relative}")
            continue
        text = path.read_text(encoding="utf-8")
        contents[relative] = text
        for section in sections:
            if not re.search(rf"^##\s+{re.escape(section)}\s*$", text, re.MULTILINE):
                result.errors.append(f"{relative} 缺少章节：{section}")
        if "{{" in text or "}}" in text:
            result.errors.append(f"{relative} 仍含未填写模板占位符")
        result.checks.append(f"文档结构：{relative}")
    for relative in stage.get("data", []):
        path = base / relative
        if not path.is_file():
            result.errors.append(f"缺少结构化数据：{relative}")
            continue
        try:
            read_json(path)
            result.checks.append(f"JSON 格式：{relative}")
        except WorkflowError as exc:
            result.errors.append(str(exc))
    return contents


def _check_requirements(text: str, result: GateResult) -> list[str]:
    ids = requirement_ids(text)
    if not ids:
        result.errors.append("功能需求中没有 REQ-nnn 定义")
        return []
    duplicates = sorted({item for item in ids if ids.count(item) > 1})
    if duplicates:
        result.errors.append(f"需求标识重复定义：{duplicates}")
    acceptance = _section(text, "验收标准")
    rows = re.findall(r"^\|\s*(AC-[A-Za-z0-9-]+)\s*\|\s*(REQ-\d{3,})\s*\|", acceptance, re.MULTILINE)
    acceptance_ids = [row[0] for row in rows]
    covered = {row[1] for row in rows}
    if len(acceptance_ids) != len(set(acceptance_ids)):
        result.errors.append("验收标准标识重复")
    missing = sorted(set(ids) - covered)
    if missing:
        result.errors.append(f"需求缺少可验证验收标准：{missing}")
    unknown = sorted(covered - set(ids))
    if unknown:
        result.errors.append(f"验收标准引用未知需求：{unknown}")
    result.checks.append("需求唯一标识与验收标准")
    return ids


def _load_tasks(engine, project_id: str, result: GateResult) -> list[dict[str, Any]]:
    try:
        tasks = read_json(engine.project_dir(project_id) / "tasks/tasks.json")["tasks"]
    except (WorkflowError, KeyError, TypeError) as exc:
        result.errors.append(f"任务数据无效：{exc}")
        return []
    if not tasks:
        result.errors.append("开发任务列表为空")
        return []
    ids = [item.get("id") for item in tasks]
    if len(ids) != len(set(ids)):
        result.errors.append("任务标识重复")
    required = ["id", "title", "requirement_ids", "depends_on", "input", "output", "acceptance", "test", "rollback", "status"]
    for task in tasks:
        for key in required:
            if key not in task or task[key] in (None, "", []):
                if key == "depends_on" and task.get(key) == []:
                    continue
                result.errors.append(f"任务 {task.get('id', '?')} 缺少字段：{key}")
        unknown = set(task.get("depends_on", [])) - set(ids)
        if unknown:
            result.errors.append(f"任务 {task.get('id')} 依赖未知任务：{sorted(unknown)}")
        if task.get("status") == "completed":
            incomplete = [dep for dep in task.get("depends_on", []) if not any(other.get("id") == dep and other.get("status") == "completed" for other in tasks)]
            if incomplete:
                result.errors.append(f"任务 {task['id']} 在依赖未完成时被标记完成：{incomplete}")
    visiting: set[str] = set()
    visited: set[str] = set()
    by_id = {item["id"]: item for item in tasks if item.get("id")}

    def visit(task_id: str):
        if task_id in visiting:
            result.errors.append(f"任务依赖存在循环：{task_id}")
            return
        if task_id in visited or task_id not in by_id:
            return
        visiting.add(task_id)
        for dependency in by_id[task_id].get("depends_on", []):
            visit(dependency)
        visiting.remove(task_id)
        visited.add(task_id)

    for task_id in by_id:
        visit(task_id)
    result.checks.append("任务契约、依赖及完成条件")
    return tasks


def _check_task_trace(tasks: list[dict[str, Any]], requirements: list[str], result: GateResult) -> None:
    known = set(requirements)
    covered: set[str] = set()
    for task in tasks:
        refs = set(task.get("requirement_ids", []))
        unknown = refs - known
        if unknown:
            result.errors.append(f"任务 {task.get('id')} 引用未知需求：{sorted(unknown)}")
        covered |= refs & known
    missing = known - covered
    if missing:
        result.errors.append(f"需求未分解到开发任务：{sorted(missing)}")
    result.checks.append("需求—任务追踪")


def _validate_evidence(base: Path, relative: str, current_tree: str | None, role: str | None, result: GateResult) -> bool:
    if not relative:
        result.errors.append("缺少执行证据路径")
        return False
    path = base / relative
    if not path.is_file():
        result.errors.append(f"执行证据不存在：{relative}")
        return False
    try:
        evidence = read_json(path)
    except WorkflowError as exc:
        result.errors.append(str(exc))
        return False
    if evidence.get("exit_code") != 0 or evidence.get("timed_out"):
        result.errors.append(f"执行证据未通过：{relative}")
        return False
    if "command" not in evidence or "started_at" not in evidence or "finished_at" not in evidence:
        result.errors.append(f"执行证据字段不完整：{relative}")
        return False
    if current_tree is not None and evidence.get("tree_digest_after") != current_tree:
        result.errors.append(f"执行证据已过期（代码在测试后变化）：{relative}")
        return False
    if role is not None and evidence.get("role") != role:
        result.errors.append(f"执行角色不符合要求（应为 {role}）：{relative}")
        return False
    result.evidence.append(relative)
    return True


def _check_configured(engine, project_id: str, stage_id: str, role: str, result: GateResult, require_one: bool) -> None:
    base = engine.project_dir(project_id)
    checks = read_json(base / "checks.json")["checks"]
    required = [item for item in checks if item.get("stage") == stage_id and item.get("required")]
    if require_one and not required:
        result.errors.append(f"{stage_id} 未配置任何必需自动化检查")
    current = tree_digest(base / "workspace")
    for check in required:
        if check.get("last_status") != "passed":
            result.errors.append(f"必需检查未通过：{check['id']}")
            continue
        _validate_evidence(base, check.get("last_evidence", ""), current, role, result)
    result.checks.append(f"{stage_id} 必需构建/自动化测试")


def _check_cases(engine, project_id: str, requirements: list[str], result: GateResult) -> None:
    base = engine.project_dir(project_id)
    cases = read_json(base / "tests/cases.json")["cases"]
    if not cases:
        result.errors.append("独立测试用例为空")
        return
    ids = [item.get("id") for item in cases]
    if len(ids) != len(set(ids)):
        result.errors.append("测试用例标识重复")
    current = tree_digest(base / "workspace")
    covered: set[str] = set()
    for case in cases:
        refs = set(case.get("requirement_ids", []))
        unknown = refs - set(requirements)
        if unknown:
            result.errors.append(f"用例 {case.get('id')} 引用未知需求：{sorted(unknown)}")
        covered |= refs & set(requirements)
        for key in ["steps", "expected"]:
            if not case.get(key):
                result.errors.append(f"用例 {case.get('id')} 缺少 {key}")
        if case.get("status") != "passed":
            result.errors.append(f"测试用例未通过：{case.get('id')}")
        else:
            _validate_evidence(base, case.get("last_evidence", ""), current, "test_engineer", result)
    missing = set(requirements) - covered
    if missing:
        result.errors.append(f"需求缺少独立测试覆盖：{sorted(missing)}")
    defects = read_json(base / "tests/defects.json")["defects"]
    unresolved = [item["id"] for item in defects if item.get("status") != "resolved"]
    if unresolved:
        result.errors.append(f"存在未解决缺陷：{unresolved}")
    result.checks.append("需求—用例—执行—缺陷追踪")


def _check_previous_approvals(engine, project_id: str, stage_id: str, result: GateResult, include_current: bool) -> None:
    state = engine.load(project_id)
    index = engine.stage_order.index(stage_id)
    required = [item for item in engine.stage_order[:index] if engine.stages[item]["approval"]]
    if include_current and engine.stages[stage_id]["approval"]:
        required.append(stage_id)
    approvals = state.get("approvals", [])
    for required_stage in required:
        matching = [item for item in approvals if item.get("stage") == required_stage and item.get("decision") == "approved"]
        if not matching:
            result.errors.append(f"缺少人工批准记录：{required_stage}")
            continue
        latest = matching[-1]
        if latest.get("decision_source") != "explicit_human_cli" or not latest.get("decided_by"):
            result.errors.append(f"审批来源不是明确人工操作：{required_stage}")
        current = engine.baseline_digest(project_id, required_stage)
        if latest.get("verified_baseline_digest") != current:
            result.errors.append(f"批准后基线发生变化，审批失效：{required_stage}")
    result.checks.append("人工审批状态与基线一致性")


def check_stage(engine, project_id: str, stage_id: str, for_archive: bool = False) -> GateResult:
    result = GateResult(stage_id)
    stage = engine.stages[stage_id]
    base = engine.project_dir(project_id)
    documents = _check_documents(engine, project_id, stage, result)
    requirements_text = (base / "docs/02-requirements.md").read_text(encoding="utf-8") if (base / "docs/02-requirements.md").exists() else ""
    requirements = requirement_ids(requirements_text)

    if stage_id == "B_REQUIREMENTS":
        requirements = _check_requirements(documents.get("docs/02-requirements.md", ""), result)
    elif stage_id == "C_DESIGN":
        architecture = documents.get("docs/03-architecture.md", "")
        missing = [item for item in requirements if item not in architecture]
        if missing:
            result.errors.append(f"架构未追踪需求：{missing}")
        result.checks.append("需求—架构追踪")
    elif stage_id == "D_PLANNING":
        tasks = _load_tasks(engine, project_id, result)
        _check_task_trace(tasks, requirements, result)
        plan = documents.get("docs/04-development-plan.md", "")
        for task in tasks:
            if task["id"] not in plan:
                result.errors.append(f"开发计划未列出任务：{task['id']}")
        missing = [item for item in requirements if item not in plan]
        if missing:
            result.errors.append(f"开发计划未追踪需求：{missing}")
    elif stage_id == "E_IMPLEMENTATION":
        tasks = _load_tasks(engine, project_id, result)
        for task in tasks:
            if task.get("status") != "completed":
                result.errors.append(f"开发任务未完成：{task['id']}")
            elif not task.get("last_evidence"):
                result.errors.append(f"完成任务没有真实证据：{task['id']}")
            else:
                _validate_evidence(base, task["last_evidence"], None, "developer", result)
        result.checks.append("任务完成定义与开发证据")
    elif stage_id == "F_SELF_TEST":
        _check_configured(engine, project_id, stage_id, "self_tester", result, require_one=True)
    elif stage_id == "G_INDEPENDENT_TEST":
        _check_cases(engine, project_id, requirements, result)
        _check_configured(engine, project_id, stage_id, "test_engineer", result, require_one=True)
        report = documents.get("docs/07-test-report.md", "")
        missing = [item for item in requirements if item not in report]
        if missing:
            result.errors.append(f"测试报告未覆盖需求：{missing}")
    elif stage_id == "H_RELEASE":
        release = read_json(base / "release.json")
        if not release.get("version") or not release.get("code_version"):
            result.errors.append("发布版本或代码版本缺失")
        current = tree_digest(base / "workspace")
        if release.get("code_version") != f"tree-{current}":
            result.errors.append("代码版本与当前工作区不一致")
        artifacts = release.get("artifacts", [])
        if not artifacts:
            result.errors.append("发布制品清单为空")
        for artifact in artifacts:
            path = base / "workspace" / artifact.get("path", "")
            if not path.is_file():
                result.errors.append(f"发布制品不存在：{artifact.get('path')}")
            elif sha256_file(path) != artifact.get("sha256"):
                result.errors.append(f"发布制品哈希不一致：{artifact.get('path')}")
        _check_cases(engine, project_id, requirements, result)
        _check_configured(engine, project_id, stage_id, "test_engineer", result, require_one=True)
        result.checks.append("发布制品、版本、最终回归及回滚材料")
    elif stage_id == "I_ACCEPTANCE":
        release = read_json(base / "release.json")
        version, code_version = release.get("version"), release.get("code_version")
        acceptance = documents.get("docs/09-acceptance.md", "")
        index = documents.get("docs/10-delivery-index.md", "")
        for label, text in [("验收报告", acceptance), ("交付索引", index)]:
            if version not in text or code_version not in text:
                result.errors.append(f"{label}与发布清单版本不一致")
        _load_tasks(engine, project_id, result)
        _check_cases(engine, project_id, requirements, result)
        result.checks.append("归档版本一致性与交付完整性")

    _check_previous_approvals(engine, project_id, stage_id, result, include_current=for_archive)
    return result


def check_installation() -> GateResult:
    result = GateResult("INSTALLATION")
    required = [
        "zhijie.py", "AGENTS.md", "README.md", "workflow/config/stages.json",
        "workflow/config/state_machine.json", "workflow/spec/WORKFLOW.md",
        "workflow/spec/ROLES.md", "workflow/spec/CONTRACTS.md", "workflow/spec/ADAPTERS.md",
        "workflow/schemas/agent-task.schema.json", "workflow/schemas/agent-result.schema.json",
    ]
    for relative in required:
        if not (SOURCE_ROOT / relative).is_file():
            result.errors.append(f"缺少基础文件：{relative}")
    try:
        stages = read_json(SOURCE_ROOT / "workflow/config/stages.json")["stages"]
        machine = read_json(SOURCE_ROOT / "workflow/config/state_machine.json")
        if len(stages) != 9 or [item["id"][0] for item in stages] != list("ABCDEFGHI"):
            result.errors.append("工作流必须按 A–I 定义九个阶段")
        statuses = set(machine["statuses"])
        for source, targets in machine["transitions"].items():
            if source not in statuses or not set(targets) <= statuses:
                result.errors.append(f"状态机引用未知状态：{source}")
        for status in statuses:
            if status not in machine["transitions"]:
                result.errors.append(f"状态缺少迁移定义：{status}")
        for template in (SOURCE_ROOT / "workflow/templates").glob("*.md"):
            if not template.read_text(encoding="utf-8").strip():
                result.errors.append(f"空模板：{template.name}")
        result.checks.append("目录、配置、模板和状态迁移")
    except (WorkflowError, KeyError, TypeError) as exc:
        result.errors.append(f"基础配置无效：{exc}")
    return result
