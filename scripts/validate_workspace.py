#!/usr/bin/env python3
"""执行可重复的安装、单元和端到端验证并保留真实结果。"""
from __future__ import annotations

import json
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def run(command: list[str]) -> dict:
    completed = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace")
    print(completed.stdout, end="")
    if completed.stderr:
        print(completed.stderr, end="", file=sys.stderr)
    return {
        "command": command,
        "exit_code": completed.returncode,
        "stdout": completed.stdout,
        "stderr": completed.stderr,
    }


def main() -> int:
    commands = [
        [sys.executable, "-m", "compileall", "-q", "zhijie.py", "workflow", "tests"],
        [sys.executable, "zhijie.py", "self-check"],
        [sys.executable, "-m", "unittest", "discover", "-s", "tests", "-v"],
    ]
    results = [run(command) for command in commands]
    report = {
        "schema_version": 1,
        "run_at": datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
        "overall": "passed" if all(item["exit_code"] == 0 for item in results) else "failed",
        "results": results,
    }
    destination = ROOT / "var/validation/latest-run.json"
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"验证报告：{destination}")
    return 0 if report["overall"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
