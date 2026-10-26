from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

from .engine import Engine, HUMAN_CONFIRMATION, WorkflowError
from .gates import check_installation, check_stage


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(prog="zhijie", description="织界通用 AI 软件研发工作流")
    result.add_argument("--root", default=str(Path(__file__).resolve().parent.parent), help="工作流数据根目录")
    sub = result.add_subparsers(dest="command", required=True)

    command = sub.add_parser("new", help="创建研发项目并提交初始需求")
    command.add_argument("--id", dest="project_id", required=True)
    command.add_argument("--name", required=True)
    command.add_argument("--request", required=True)

    command = sub.add_parser("status", help="查看一个或全部项目状态")
    command.add_argument("project_id", nargs="?")

    for name, help_text in [
        ("submit", "执行当前阶段门禁并提交"),
        ("revise", "撤回待审批或驳回后重新进入修改"),
        ("migrate", "将符合条件的既有项目显式迁移到当前流程"),
        ("pause", "暂停并持久化状态"),
        ("resume", "恢复暂停的工作流"),
        ("fail", "记录执行失败"),
        ("retry", "重试失败任务"),
        ("cancel", "取消工作流"),
        ("archive", "最终验收后生成交付归档"),
    ]:
        command = sub.add_parser(name, help=help_text)
        command.add_argument("project_id")
        command.add_argument("--actor", required=True)
        if name not in {"archive"}:
            command.add_argument("--role", default="workflow_operator")
        if name in {"revise", "migrate", "pause", "fail", "retry", "cancel"}:
            command.add_argument("--reason", required=True)
        if name == "fail":
            command.add_argument("--retryable", action="store_true")

    command = sub.add_parser("approve", help="【仅限人类】批准、驳回或要求修改")
    command.add_argument("project_id")
    command.add_argument("--approval-id", required=True)
    command.add_argument("--decision", choices=["approved", "rejected", "changes_requested"], required=True)
    command.add_argument("--actor", required=True, help="具体人类决策者姓名或标识")
    command.add_argument("--human-confirm", required=True, help=f"人工确认值：{HUMAN_CONFIRMATION}")
    command.add_argument("--reason", default="", help="驳回或要求修改时必填")

    command = sub.add_parser("add-task", help="添加可执行开发任务")
    command.add_argument("project_id")
    command.add_argument("--task-id", required=True)
    command.add_argument("--title", required=True)
    command.add_argument("--requirements", required=True, help="逗号分隔 REQ 标识")
    command.add_argument("--depends-on", default="", help="逗号分隔任务标识")
    for argument in ["input", "output", "acceptance", "test", "rollback"]:
        command.add_argument(f"--{argument}", required=True)

    command = sub.add_parser("task-run", help="真实执行开发/修复任务命令")
    command.add_argument("project_id")
    command.add_argument("task_id")
    command.add_argument("--actor", required=True)
    command.add_argument("--role", default="developer")
    command.add_argument("--command", required=True)
    command.add_argument("--timeout", type=int, default=120)

    command = sub.add_parser("add-check", help="配置阶段自动化检查")
    command.add_argument("project_id")
    command.add_argument("--check-id", required=True)
    command.add_argument("--stage", required=True)
    command.add_argument("--command", required=True)
    command.add_argument("--optional", action="store_true")

    command = sub.add_parser("verify", help="真实运行构建或测试检查")
    command.add_argument("project_id")
    command.add_argument("--check-id", required=True)
    command.add_argument("--actor", required=True)
    command.add_argument("--role", required=True)
    command.add_argument("--case", dest="case_id")
    command.add_argument("--timeout", type=int, default=120)

    command = sub.add_parser("add-test", help="添加独立测试用例")
    command.add_argument("project_id")
    command.add_argument("--case-id", required=True)
    command.add_argument("--requirements", required=True)
    command.add_argument("--steps", required=True)
    command.add_argument("--expected", required=True)

    command = sub.add_parser("release-set", help="记录真实发布版本和制品哈希")
    command.add_argument("project_id")
    command.add_argument("--version", required=True)
    command.add_argument("--artifact", action="append", default=[])

    command = sub.add_parser("validate", help="执行当前阶段质量门禁")
    command.add_argument("project_id")

    sub.add_parser("self-check", help="检查工作流安装完整性")
    return result


def _csv(value: str) -> list[str]:
    return [item.strip() for item in value.split(",") if item.strip()]


def dispatch(args: argparse.Namespace) -> Any:
    engine = Engine(args.root)
    if args.command == "self-check":
        return check_installation().to_dict()
    if args.command == "new":
        return engine.new_project(args.project_id, args.name, args.request)
    if args.command == "status":
        return engine.status(args.project_id)
    if args.command == "submit":
        return engine.submit(args.project_id, args.actor, args.role)
    if args.command == "approve":
        return engine.approve(args.project_id, args.approval_id, args.decision, args.actor, args.human_confirm, args.reason)
    if args.command == "revise":
        return engine.revise(args.project_id, args.actor, args.role, args.reason)
    if args.command == "migrate":
        return engine.migrate(args.project_id, args.actor, args.role, args.reason)
    if args.command == "pause":
        return engine.pause(args.project_id, args.actor, args.role, args.reason)
    if args.command == "resume":
        return engine.resume(args.project_id, args.actor, args.role)
    if args.command == "fail":
        return engine.fail(args.project_id, args.actor, args.role, args.reason, args.retryable)
    if args.command == "retry":
        return engine.retry(args.project_id, args.actor, args.role, args.reason)
    if args.command == "cancel":
        return engine.cancel(args.project_id, args.actor, args.role, args.reason)
    if args.command == "add-task":
        task = {
            "id": args.task_id,
            "title": args.title,
            "requirement_ids": _csv(args.requirements),
            "depends_on": _csv(args.depends_on),
            "input": args.input,
            "output": args.output,
            "acceptance": args.acceptance,
            "test": args.test,
            "rollback": args.rollback,
        }
        return engine.add_task(args.project_id, task)
    if args.command == "task-run":
        return engine.run_task(args.project_id, args.task_id, args.actor, args.role, args.command, args.timeout)
    if args.command == "add-check":
        return engine.add_check(args.project_id, args.check_id, args.stage, args.command, not args.optional)
    if args.command == "verify":
        return engine.verify(args.project_id, args.check_id, args.actor, args.role, args.case_id, args.timeout)
    if args.command == "add-test":
        return engine.add_test(args.project_id, {
            "id": args.case_id,
            "requirement_ids": _csv(args.requirements),
            "steps": args.steps,
            "expected": args.expected,
        })
    if args.command == "release-set":
        return engine.set_release(args.project_id, args.version, args.artifact)
    if args.command == "validate":
        with engine.lock(args.project_id):
            return check_stage(engine, args.project_id, engine.load(args.project_id)["stage"]).to_dict()
    if args.command == "archive":
        return engine.archive(args.project_id, args.actor)
    raise WorkflowError(f"未知命令：{args.command}")


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    try:
        result = dispatch(args)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        if args.command in {"self-check", "validate"} and not result.get("ok", False):
            return 1
        if args.command in {"task-run", "verify"} and result.get("exit_code") != 0:
            return 1
        return 0
    except WorkflowError as exc:
        print(json.dumps({"ok": False, "error": str(exc)}, ensure_ascii=False, indent=2), file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        print("操作已中断；持久化状态可在下次恢复。", file=sys.stderr)
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
