from __future__ import annotations

import tempfile
from pathlib import Path


def temporary_project_directory(source_root: Path) -> tempfile.TemporaryDirectory:
    """创建隔离项目目录，并支持干净克隆中缺少 var/tmp 的情况。"""
    temporary_root = source_root / "var" / "tmp"
    temporary_root.mkdir(parents=True, exist_ok=True)
    return tempfile.TemporaryDirectory(dir=temporary_root)
