from __future__ import annotations

import hashlib
import json
import os
import re
import shlex
import shutil
import subprocess
import time
import uuid
import zipfile
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

SOURCE_ROOT = Path(__file__).resolve().parent.parent
PROJECT_ID_RE = re.compile(r"^[a-z0-9][a-z0-9-]{2,63}$")
TASK_ID_RE = re.compile(r"^(?:TASK|FIX)-[A-Za-z0-9-]+$")
CHECK_ID_RE = re.compile(r"^[a-z][a-z0-9-]{1,63}$")
CASE_ID_RE = re.compile(r"^TC-[A-Za-z0-9-]+$")
HUMAN_CONFIRMATION = "I_CONFIRM_HUMAN_DECISION"


class WorkflowError(RuntimeError):
    """用户可修正的工作流错误。"""


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def read_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise WorkflowError(f"缺少文件：{path}") from exc
    except json.JSONDecodeError as exc:
        raise WorkflowError(f"JSON 格式错误：{path}：{exc}") from exc


def atomic_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    try:
        with temporary.open("w", encoding="utf-8", newline="\n") as handle:
            handle.write(content)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


def atomic_json(path: Path, value: Any) -> None:
    atomic_text(path, json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def tree_digest(root: Path) -> str:
    digest = hashlib.sha256()
    if not root.exists():
        return digest.hexdigest()
    for path in sorted(item for item in root.rglob("*") if item.is_file()):
        if "__pycache__" in path.parts or ".git" in path.parts:
            continue
        digest.update(path.relative_to(root).as_posix().encode("utf-8"))
        digest.update(b"\0")
        digest.update(sha256_file(path).encode("ascii"))
    return digest.hexdigest()


@contextmanager
def file_lock(path: Path, timeout: float = 10.0):
    path.parent.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    handle = None
    while handle is None:
        try:
            handle = path.open("x")
            handle.write(str(os.getpid()))
        except FileExistsError:
            if time.monotonic() - started >= timeout:
                raise WorkflowError(f"等待工作流锁超时：{path}")
            time.sleep(0.05)
    try:
        yield
    finally:
        handle.close()
        path.unlink(missing_ok=True)


class Engine:
    def __init__(self, root: str | Path):
        self.root = Path(root).resolve()
        self.projects = self.root / "projects"
        self.stage_config = read_json(SOURCE_ROOT / "workflow/config/stages.json")
        self.state_config = read_json(SOURCE_ROOT / "workflow/config/state_machine.json")
        self.stages = {item["id"]: item for item in self.stage_config["stages"]}
        self.stage_order = [item["id"] for item in self.stage_config["stages"]]

    def stage_order_for(self, state: dict[str, Any]) -> list[str]:
        """旧项目保持创建时的阶段路径，新项目启用完整产品设计流程。"""
        if state.get("workflow_version", 1) < 2:
            return [item for item in self.stage_order if item not in {"B1_PROTOTYPE", "B2_UI"}]
        return self.stage_order

    def project_dir(self, project_id: str) -> Path:
        if not PROJECT_ID_RE.fullmatch(project_id):
            raise WorkflowError("项目标识须为 3–64 位小写字母、数字或连字符。")
        return self.projects / project_id

    def lock(self, project_id: str):
        return file_lock(self.root / "var/locks" / f"{project_id}.lock")

    def load(self, project_id: str) -> dict[str, Any]:
        return read_json(self.project_dir(project_id) / "state.json")

    def save(self, project_id: str, state: dict[str, Any]) -> None:
        state["updated_at"] = utc_now()
        atomic_json(self.project_dir(project_id) / "state.json", state)

    def audit(self, project_id: str, event: dict[str, Any]) -> None:
        path = self.project_dir(project_id) / "audit.jsonl"
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("a", encoding="utf-8", newline="\n") as handle:
            handle.write(json.dumps(event, ensure_ascii=False) + "\n")
            handle.flush()
            os.fsync(handle.fileno())

    def transition(
        self,
        project_id: str,
        state: dict[str, Any],
        target: str,
        actor: str,
        role: str,
        reason: str,
        evidence: list[str] | None = None,
        human_authorization: bool = False,
    ) -> None:
        source = state["status"]
        allowed = self.state_config["transitions"].get(source, [])
        if target not in allowed:
            raise WorkflowError(f"非法状态迁移：{source} → {target}；允许目标：{allowed}")
        state["status"] = target
        event = {
            "event": "state_transition",
            "workflow_id": state["workflow_id"],
            "project_id": project_id,
            "task_id": state.get("active_task_id"),
            "stage": state["stage"],
            "from": source,
            "to": target,
            "at": utc_now(),
            "actor": actor,
            "role": role,
            "reason": reason,
            "evidence": evidence or [],
            "human_authorization": human_authorization,
        }
        state.setdefault("history", []).append(event)
        state["history"] = state["history"][-200:]
        self.save(project_id, state)
        self.audit(project_id, event)

    def _copy_templates(self, project_id: str, name: str) -> None:
        destination = self.project_dir(project_id) / "docs"
        for template in (SOURCE_ROOT / "workflow/templates").glob("*.md"):
            content = template.read_text(encoding="utf-8")
            content = content.replace("{{PROJECT_NAME}}", name).replace("{{PROJECT_ID}}", project_id)
            (destination / template.name).write_text(content, encoding="utf-8", newline="\n")

    def new_project(self, project_id: str, name: str, request: str) -> dict[str, Any]:
        if not name.strip() or not request.strip():
            raise WorkflowError("项目名称和初始需求不能为空。")
        directory = self.project_dir(project_id)
        with self.lock(project_id):
            if directory.exists():
                raise WorkflowError(f"项目已存在，未覆盖：{directory}")
            for child in ["docs", "tasks", "tests/evidence", "approvals", "workspace", "deliverables"]:
                (directory / child).mkdir(parents=True, exist_ok=True)
            self._copy_templates(project_id, name)
            now = utc_now()
            state = {
                "schema_version": 1,
                "workflow_version": 2,
                "workflow_id": f"WF-{project_id}",
                "project_id": project_id,
                "name": name,
                "stage": "A_INTAKE",
                "status": "pending",
                "created_at": now,
                "updated_at": now,
                "retry_counts": {},
                "max_retries": 3,
                "approvals": [],
                "history": [],
                "resume_status": None,
                "active_task_id": None,
            }
            self.save(project_id, state)
            intake = f"""# 需求接收：{name}

## 初始想法与背景

{request.strip()}

## 当前已知条件

- 项目名称：{name}。
- 项目标识：{project_id}。
- 上述初始需求由用户在 {now} 提交；其他业务事实尚未确认。

## 待澄清问题

- 需求分析师须识别影响目标、范围、合规性和验收的歧义，并向用户提问。
- 非关键假设须明确标注为“假设”，不得写成用户已确认的事实。

## 初步范围及风险

- 初步范围：仅包含上述请求的后续分析，具体范围以批准的需求基线为准。
- 初步风险：需求、技术约束和验收口径尚未形成基线。
"""
            atomic_text(directory / "docs/01-intake.md", intake)
            for filename, value in [
                ("tasks/tasks.json", {"schema_version": 1, "tasks": []}),
                ("checks.json", {"schema_version": 1, "checks": []}),
                ("tests/cases.json", {"schema_version": 1, "cases": []}),
                ("tests/defects.json", {"schema_version": 1, "defects": []}),
                ("release.json", {"schema_version": 1, "version": None, "code_version": None, "artifacts": []}),
            ]:
                atomic_json(directory / filename, value)
            self.audit(project_id, {"event": "project_created", "at": now, "actor": "user", "request": request.strip()})
            self.transition(project_id, state, "in_progress", "system", "product_owner", "开始需求接收")
            self.transition(project_id, state, "approved", "system", "product_owner", "需求记录已生成", ["docs/01-intake.md"])
            state["stage"] = "B_REQUIREMENTS"
            self.transition(project_id, state, "in_progress", "system", "requirements_analyst", "进入需求分析")
            return state

    def status(self, project_id: str | None = None) -> Any:
        if project_id:
            state = self.load(project_id)
            current = self.current_approval_request(project_id, required=False)
            return {"state": state, "current_approval_request": current}
        if not self.projects.exists():
            return []
        result = []
        for path in sorted(self.projects.glob("*/state.json")):
            state = read_json(path)
            result.append({key: state[key] for key in ["project_id", "name", "stage", "status", "updated_at"]})
        return result

    def current_approval_request(self, project_id: str, required: bool = True) -> dict[str, Any] | None:
        path = self.project_dir(project_id) / "approvals/current.json"
        if not path.exists():
            if required:
                raise WorkflowError("当前没有审批请求。")
            return None
        return read_json(path)

    def approval_files(self, project_id: str, stage_id: str) -> list[str]:
        stage = self.stages[stage_id]
        state = self.load(project_id)
        modern = state.get("workflow_version", 1) >= 2
        files = list(stage.get("documents", {})) + list(stage.get("data", []))
        if modern and stage_id == "B1_PROTOTYPE":
            files.append("docs/02-requirements.md")
        elif modern and stage_id == "B2_UI":
            files.extend(self.approval_files(project_id, "B1_PROTOTYPE"))
        elif stage_id == "C_DESIGN":
            if modern:
                files.extend(self.approval_files(project_id, "B2_UI"))
            else:
                files.append("docs/02-requirements.md")
        elif stage_id == "H_RELEASE":
            files.extend(["docs/07-test-report.md", "tests/cases.json", "tests/defects.json"])
        elif stage_id == "I_ACCEPTANCE":
            documents = [
                "02-requirements.md", "03-architecture.md", "04-development-plan.md",
                "05-self-test.md", "06-test-plan.md", "07-test-report.md",
                "08-release.md", "09-acceptance.md", "10-delivery-index.md",
            ]
            if modern:
                documents[1:1] = ["02a-prototype.md", "02b-ui.md"]
                files.extend(["workspace/design/prototype/index.html", "workspace/design/ui/index.html"])
            files.extend(f"docs/{name}" for name in documents)
            files.extend(["tasks/tasks.json", "checks.json", "tests/cases.json", "tests/defects.json", "release.json"])
        return sorted(set(files))

    def baseline_digest(self, project_id: str, stage_id: str) -> str:
        base = self.project_dir(project_id)
        digest = hashlib.sha256()
        for relative in self.approval_files(project_id, stage_id):
            path = base / relative
            digest.update(relative.encode("utf-8"))
            if path.exists():
                digest.update(sha256_file(path).encode("ascii"))
            else:
                digest.update(b"MISSING")
        return digest.hexdigest()

    def submit(self, project_id: str, actor: str, role: str) -> dict[str, Any]:
        from .gates import check_stage

        with self.lock(project_id):
            state = self.load(project_id)
            if state["status"] == "awaiting_approval":
                raise WorkflowError("已处于待人工审批状态；重复提交不能绕过审批。")
            if state["status"] != "in_progress":
                raise WorkflowError(f"只有执行中阶段可提交，当前为 {state['status']}。")
            result = check_stage(self, project_id, state["stage"])
            if not result.ok:
                raise WorkflowError("质量门禁未通过：\n- " + "\n- ".join(result.errors))
            stage_id = state["stage"]
            if self.stages[stage_id]["approval"]:
                approval_id = f"APR-{stage_id.split('_')[0]}-{uuid.uuid4().hex[:12]}"
                request = {
                    "schema_version": 1,
                    "approval_id": approval_id,
                    "workflow_id": state["workflow_id"],
                    "project_id": project_id,
                    "stage": stage_id,
                    "stage_name": self.stages[stage_id]["name"],
                    "requested_at": utc_now(),
                    "requested_by": actor,
                    "baseline_digest": self.baseline_digest(project_id, stage_id),
                    "decision": None,
                    "documents": self.approval_files(project_id, stage_id),
                    "summary": f"请审批{self.stages[stage_id]['name']}基线；批准仅适用于当前文件摘要。",
                    "important_decisions": "以所列需求、设计、发布或验收文档中的决策为准。",
                    "risks_and_impacts": "风险、已知问题和未验证事项见所列文档；基线变化后须重新审批。",
                    "acceptance_and_checks": result.to_dict(),
                    "allowed_decisions": ["approved", "rejected", "changes_requested"],
                    "human_command": f"python zhijie.py approve {project_id} --approval-id {approval_id} --decision approved --actor <姓名> --human-confirm {HUMAN_CONFIRMATION}",
                }
                atomic_json(self.project_dir(project_id) / "approvals/current.json", request)
                atomic_json(self.project_dir(project_id) / f"approvals/{approval_id}.request.json", request)
                self.transition(project_id, state, "awaiting_approval", actor, role, "提交人工审批", request["documents"])
                return {"approval_required": True, "request": request, "gate": result.to_dict()}
            self.transition(project_id, state, "approved", actor, role, "自动质量门禁通过", evidence=result.evidence)
            self._advance(project_id, state, actor, role)
            return {"approval_required": False, "state": state, "gate": result.to_dict()}

    def _advance(self, project_id: str, state: dict[str, Any], actor: str, role: str) -> None:
        current = state["stage"]
        order = self.stage_order_for(state)
        if current == order[-1]:
            self.transition(project_id, state, "completed", actor, role, "最终验收批准，工作流完成", human_authorization=True)
            return
        state["stage"] = order[order.index(current) + 1]
        self.transition(project_id, state, "in_progress", actor, role, f"进入{self.stages[state['stage']]['name']}")

    def approve(
        self,
        project_id: str,
        approval_id: str,
        decision: str,
        actor: str,
        confirmation: str,
        reason: str = "",
    ) -> dict[str, Any]:
        if confirmation != HUMAN_CONFIRMATION:
            raise WorkflowError("缺少明确人工确认。该命令只能由人类决策者执行。")
        if not actor.strip() or actor.strip().lower() in {"ai", "agent", "assistant", "system", "bot"}:
            raise WorkflowError("审批人必须是具体的人类决策者，不能是 AI 或系统角色。")
        if decision not in {"approved", "rejected", "changes_requested"}:
            raise WorkflowError("审批决定无效。")
        if decision != "approved" and not reason.strip():
            raise WorkflowError("驳回或要求修改时必须填写具体原因。")
        with self.lock(project_id):
            state = self.load(project_id)
            if state["status"] != "awaiting_approval":
                raise WorkflowError("当前不在待人工审批状态。")
            request = self.current_approval_request(project_id)
            if request["approval_id"] != approval_id or request["stage"] != state["stage"]:
                raise WorkflowError("审批标识与当前请求不匹配。")
            current_digest = self.baseline_digest(project_id, state["stage"])
            if current_digest != request["baseline_digest"]:
                raise WorkflowError("审批基线已变化，旧审批请求失效；请重新提交审批。")
            record = {
                **request,
                "decision": decision,
                "decision_reason": reason.strip() or "批准当前基线",
                "decided_at": utc_now(),
                "decided_by": actor,
                "decision_source": "explicit_human_cli",
                "verified_baseline_digest": current_digest,
            }
            atomic_json(self.project_dir(project_id) / f"approvals/{approval_id}.decision.json", record)
            state.setdefault("approvals", []).append(record)
            if decision == "approved":
                self.transition(project_id, state, "approved", actor, "human_approver", "人工明确批准", [f"approvals/{approval_id}.decision.json"], True)
                self._advance(project_id, state, actor, "human_approver")
            else:
                self.transition(project_id, state, "rejected", actor, "human_approver", f"人工决定：{decision}；原因：{reason.strip()}", [f"approvals/{approval_id}.decision.json"], True)
            (self.project_dir(project_id) / "approvals/current.json").unlink(missing_ok=True)
            return record

    def revise(self, project_id: str, actor: str, role: str, reason: str) -> dict[str, Any]:
        with self.lock(project_id):
            state = self.load(project_id)
            self.transition(project_id, state, "in_progress", actor, role, reason or "按审批意见修改")
            return state

    def pause(self, project_id: str, actor: str, role: str, reason: str) -> dict[str, Any]:
        with self.lock(project_id):
            state = self.load(project_id)
            state["resume_status"] = state["status"]
            self.transition(project_id, state, "paused", actor, role, reason)
            return state

    def resume(self, project_id: str, actor: str, role: str) -> dict[str, Any]:
        with self.lock(project_id):
            state = self.load(project_id)
            target = state.get("resume_status") or "in_progress"
            self.transition(project_id, state, target, actor, role, "从持久化状态恢复")
            state["resume_status"] = None
            self.save(project_id, state)
            return state

    def fail(self, project_id: str, actor: str, role: str, reason: str, retryable: bool = False) -> dict[str, Any]:
        with self.lock(project_id):
            state = self.load(project_id)
            target = "awaiting_retry" if retryable else "failed"
            self.transition(project_id, state, target, actor, role, reason)
            return state

    def retry(self, project_id: str, actor: str, role: str, reason: str) -> dict[str, Any]:
        with self.lock(project_id):
            state = self.load(project_id)
            stage = state["stage"]
            count = state.setdefault("retry_counts", {}).get(stage, 0) + 1
            if count > state.get("max_retries", 3):
                self.transition(project_id, state, "blocked", actor, role, "重试次数耗尽，需要人工介入")
                raise WorkflowError("重试次数耗尽，工作流已阻塞。")
            state["retry_counts"][stage] = count
            self.transition(project_id, state, "in_progress", actor, role, f"第 {count} 次重试：{reason}")
            return state

    def cancel(self, project_id: str, actor: str, role: str, reason: str) -> dict[str, Any]:
        with self.lock(project_id):
            state = self.load(project_id)
            self.transition(project_id, state, "cancelled", actor, role, reason)
            return state

    def add_task(self, project_id: str, task: dict[str, Any]) -> dict[str, Any]:
        with self.lock(project_id):
            if not TASK_ID_RE.fullmatch(task["id"]):
                raise WorkflowError("任务标识格式无效。")
            path = self.project_dir(project_id) / "tasks/tasks.json"
            data = read_json(path)
            if any(item["id"] == task["id"] for item in data["tasks"]):
                raise WorkflowError("任务标识已存在。")
            task.update({"status": "pending", "attempts": 0, "max_attempts": 3, "evidence": []})
            data["tasks"].append(task)
            atomic_json(path, data)
            self.audit(project_id, {"event": "task_created", "at": utc_now(), "task": task})
            return task

    def _enter_failure(self, project_id: str, state: dict[str, Any], actor: str, role: str, reason: str, evidence: list[str]) -> None:
        count = state.setdefault("retry_counts", {}).get(state["stage"], 0)
        target = "awaiting_retry" if count < state.get("max_retries", 3) else "blocked"
        self.transition(project_id, state, target, actor, role, reason, evidence)

    def run_task(self, project_id: str, task_id: str, actor: str, role: str, command: str, timeout: int = 120) -> dict[str, Any]:
        with self.lock(project_id):
            state = self.load(project_id)
            if state["stage"] not in {"E_IMPLEMENTATION", "G_INDEPENDENT_TEST"} or state["status"] != "in_progress":
                raise WorkflowError("当前阶段或状态不允许执行开发/修复任务。")
            tasks_path = self.project_dir(project_id) / "tasks/tasks.json"
            data = read_json(tasks_path)
            task = next((item for item in data["tasks"] if item["id"] == task_id), None)
            if not task:
                raise WorkflowError("任务不存在。")
            incomplete = [dep for dep in task.get("depends_on", []) if not any(t["id"] == dep and t["status"] == "completed" for t in data["tasks"])]
            if incomplete:
                raise WorkflowError(f"依赖任务尚未完成：{incomplete}")
            if task["attempts"] >= task.get("max_attempts", 3):
                self._enter_failure(project_id, state, actor, role, f"任务 {task_id} 重试次数耗尽", task.get("evidence", []))
                raise WorkflowError("任务重试次数耗尽。")
            evidence = self._execute(project_id, command, actor, role, timeout, {"task_id": task_id})
            relative = evidence["path"]
            task["attempts"] += 1
            task["evidence"].append(relative)
            task["status"] = "completed" if evidence["exit_code"] == 0 else "failed"
            task["last_evidence"] = relative
            state["active_task_id"] = task_id
            atomic_json(tasks_path, data)
            self.audit(project_id, {"event": "task_executed", "at": utc_now(), "task_id": task_id, "evidence": relative, "exit_code": evidence["exit_code"]})
            if evidence["exit_code"] == 0:
                state["active_task_id"] = None
                self.save(project_id, state)
            else:
                self._enter_failure(project_id, state, actor, role, f"任务 {task_id} 命令失败", [relative])
            return evidence

    def add_check(self, project_id: str, check_id: str, stage: str, command: str, required: bool = True) -> dict[str, Any]:
        if not CHECK_ID_RE.fullmatch(check_id) or stage not in self.stages:
            raise WorkflowError("检查标识或阶段无效。")
        path = self.project_dir(project_id) / "checks.json"
        data = read_json(path)
        check = {"id": check_id, "stage": stage, "command": command, "required": required}
        existing = next((item for item in data["checks"] if item["id"] == check_id), None)
        if existing:
            existing.update(check)
            existing.pop("last_evidence", None)
            existing.pop("last_status", None)
        else:
            data["checks"].append(check)
        atomic_json(path, data)
        return check

    def add_test(self, project_id: str, case: dict[str, Any]) -> dict[str, Any]:
        if not CASE_ID_RE.fullmatch(case["id"]):
            raise WorkflowError("测试用例标识格式无效。")
        path = self.project_dir(project_id) / "tests/cases.json"
        data = read_json(path)
        if any(item["id"] == case["id"] for item in data["cases"]):
            raise WorkflowError("测试用例已存在。")
        case.update({"status": "pending", "evidence": []})
        data["cases"].append(case)
        atomic_json(path, data)
        return case

    def _execute(self, project_id: str, command: str, actor: str, role: str, timeout: int, extra: dict[str, Any]) -> dict[str, Any]:
        started = utc_now()
        workspace = self.project_dir(project_id) / "workspace"
        before = tree_digest(workspace)
        if os.name == "nt":
            # /d 禁止注册表 AutoRun 干扰命令结果；仍支持用户显式提供的 shell 语法。
            invocation: str | list[str] = ["cmd.exe", "/d", "/s", "/c", command]
            use_shell = False
            shell_mode = "cmd.exe /d /s /c"
        else:
            invocation = command
            use_shell = True
            shell_mode = "default shell"
        try:
            completed = subprocess.run(
                invocation,
                cwd=workspace,
                shell=use_shell,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=timeout,
            )
            exit_code = completed.returncode
            stdout, stderr = completed.stdout, completed.stderr
            timed_out = False
        except subprocess.TimeoutExpired as exc:
            exit_code = 124
            stdout = exc.stdout or ""
            stderr = exc.stderr or ""
            timed_out = True
        evidence_id = f"EXEC-{utc_now().replace(':', '').replace('-', '')}-{uuid.uuid4().hex[:8]}"
        relative = f"tests/evidence/{evidence_id}.json"
        evidence = {
            "schema_version": 1,
            "evidence_id": evidence_id,
            "project_id": project_id,
            "started_at": started,
            "finished_at": utc_now(),
            "actor": actor,
            "role": role,
            "command": command,
            "cwd": "workspace",
            "shell_mode": shell_mode,
            "exit_code": exit_code,
            "timed_out": timed_out,
            "stdout": stdout,
            "stderr": stderr,
            "tree_digest_before": before,
            "tree_digest_after": tree_digest(workspace),
            **extra,
        }
        atomic_json(self.project_dir(project_id) / relative, evidence)
        evidence["path"] = relative
        return evidence

    def verify(self, project_id: str, check_id: str, actor: str, role: str, case_id: str | None, timeout: int = 120) -> dict[str, Any]:
        with self.lock(project_id):
            state = self.load(project_id)
            if state["status"] != "in_progress":
                raise WorkflowError("只有执行中工作流可运行检查。")
            checks_path = self.project_dir(project_id) / "checks.json"
            checks = read_json(checks_path)
            check = next((item for item in checks["checks"] if item["id"] == check_id), None)
            if not check:
                raise WorkflowError("检查未配置。")
            if check["stage"] != state["stage"]:
                raise WorkflowError(f"检查属于 {check['stage']}，当前为 {state['stage']}。")
            cases_path = self.project_dir(project_id) / "tests/cases.json"
            cases = read_json(cases_path)
            case = None
            if case_id:
                case = next((item for item in cases["cases"] if item["id"] == case_id), None)
                if not case:
                    raise WorkflowError("测试用例不存在。")
            evidence = self._execute(project_id, check["command"], actor, role, timeout, {"check_id": check_id, "case_id": case_id})
            relative = evidence["path"]
            check["last_evidence"] = relative
            check["last_status"] = "passed" if evidence["exit_code"] == 0 else "failed"
            atomic_json(checks_path, checks)
            if case:
                case["evidence"].append(relative)
                case["last_evidence"] = relative
                case["status"] = "passed" if evidence["exit_code"] == 0 else "failed"
                atomic_json(cases_path, cases)
                self._update_defect(project_id, state, case, evidence)
            if evidence["exit_code"] != 0:
                self._enter_failure(project_id, state, actor, role, f"检查 {check_id} 失败", [relative])
            return evidence

    def _update_defect(self, project_id: str, state: dict[str, Any], case: dict[str, Any], evidence: dict[str, Any]) -> None:
        path = self.project_dir(project_id) / "tests/defects.json"
        data = read_json(path)
        defect_id = f"DEF-{case['id']}"
        defect = next((item for item in data["defects"] if item["id"] == defect_id), None)
        if evidence["exit_code"] != 0:
            fix_task_id = f"FIX-{case['id']}"
            if not defect:
                defect = {
                    "id": defect_id,
                    "case_id": case["id"],
                    "requirement_ids": case["requirement_ids"],
                    "status": "open",
                    "reproduction": case["steps"],
                    "expected": case["expected"],
                    "evidence": [],
                    "fix_task_id": fix_task_id,
                }
                data["defects"].append(defect)
            defect["status"] = "open"
            defect["evidence"].append(evidence["path"])
            tasks_path = self.project_dir(project_id) / "tasks/tasks.json"
            tasks = read_json(tasks_path)
            if not any(item["id"] == fix_task_id for item in tasks["tasks"]):
                tasks["tasks"].append({
                    "id": fix_task_id,
                    "title": f"修复 {case['id']} 失败缺陷",
                    "requirement_ids": case["requirement_ids"],
                    "depends_on": [],
                    "input": defect["reproduction"],
                    "output": "修复后的实现及回归证据",
                    "acceptance": f"{case['id']} 及必要回归测试通过",
                    "test": case["steps"],
                    "rollback": "保留失败证据，修复不可用时回退代码变更",
                    "status": "pending",
                    "attempts": 0,
                    "max_attempts": 3,
                    "evidence": [],
                })
                atomic_json(tasks_path, tasks)
        elif defect:
            defect["status"] = "resolved"
            defect["resolved_evidence"] = evidence["path"]
            defect["resolved_at"] = utc_now()
            tasks_path = self.project_dir(project_id) / "tasks/tasks.json"
            tasks = read_json(tasks_path)
            fix_task = next((item for item in tasks["tasks"] if item["id"] == defect["fix_task_id"]), None)
            if fix_task:
                fix_task["status"] = "completed"
                fix_task["last_evidence"] = evidence["path"]
                fix_task["evidence"].append(evidence["path"])
                atomic_json(tasks_path, tasks)
        atomic_json(path, data)

    def set_release(self, project_id: str, version: str, artifacts: list[str]) -> dict[str, Any]:
        if not re.fullmatch(r"[0-9A-Za-z][0-9A-Za-z.+_-]{0,63}", version):
            raise WorkflowError("版本号格式无效。")
        with self.lock(project_id):
            state = self.load(project_id)
            if state["stage"] != "H_RELEASE":
                raise WorkflowError("只有发布准备阶段可设置发布清单。")
            workspace = self.project_dir(project_id) / "workspace"
            records = []
            for item in artifacts:
                path = (workspace / item).resolve()
                if workspace.resolve() not in path.parents or not path.is_file():
                    raise WorkflowError(f"制品必须位于工作区且真实存在：{item}")
                records.append({"path": Path(item).as_posix(), "sha256": sha256_file(path), "bytes": path.stat().st_size})
            release = {
                "schema_version": 1,
                "version": version,
                "code_version": f"tree-{tree_digest(workspace)}",
                "artifacts": records,
                "prepared_at": utc_now(),
                "production_deployment": "not_performed",
            }
            atomic_json(self.project_dir(project_id) / "release.json", release)
            self.audit(project_id, {"event": "release_prepared", "at": utc_now(), "version": version, "artifacts": records})
            return release

    def archive(self, project_id: str, actor: str) -> dict[str, Any]:
        from .gates import check_stage

        with self.lock(project_id):
            state = self.load(project_id)
            if state["stage"] != "I_ACCEPTANCE" or state["status"] != "completed":
                raise WorkflowError("只有最终验收完成后才能归档。")
            result = check_stage(self, project_id, "I_ACCEPTANCE", for_archive=True)
            if not result.ok:
                raise WorkflowError("归档门禁未通过：\n- " + "\n- ".join(result.errors))
            release = read_json(self.project_dir(project_id) / "release.json")
            base = self.project_dir(project_id)
            manifest_path = base / "deliverables/archive-manifest.json"
            files = []
            for path in sorted(item for item in base.rglob("*") if item.is_file()):
                relative = path.relative_to(base)
                if relative.parts[0] == "deliverables" or relative.name == "archive-manifest.json":
                    continue
                files.append({"path": relative.as_posix(), "sha256": sha256_file(path), "bytes": path.stat().st_size})
            manifest = {
                "schema_version": 1,
                "project_id": project_id,
                "version": release["version"],
                "code_version": release["code_version"],
                "archived_at": utc_now(),
                "archived_by": actor,
                "files": files,
                "gate": result.to_dict(),
            }
            atomic_json(manifest_path, manifest)
            archive_path = base / f"deliverables/{project_id}-{release['version']}.zip"
            with zipfile.ZipFile(archive_path, "w", compression=zipfile.ZIP_DEFLATED) as bundle:
                for item in files + [{"path": "deliverables/archive-manifest.json"}]:
                    bundle.write(base / item["path"], arcname=f"{project_id}/{item['path']}")
            report = {"archive": str(archive_path), "sha256": sha256_file(archive_path), "files": len(files) + 1, "manifest": str(manifest_path)}
            atomic_json(base / "deliverables/archive-result.json", report)
            self.audit(project_id, {"event": "archived", "at": utc_now(), **report})
            return report
