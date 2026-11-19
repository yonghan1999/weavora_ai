"""仅复制已正常停止的独立 PostgreSQL 数据目录；不会停止服务或覆盖目录。"""

import argparse
import hashlib
import json
from pathlib import Path
import shutil


def inventory(root):
    files = {}
    directories = []
    pending = [root]
    while pending:
        for path in pending.pop().iterdir():
            if path.is_symlink() or path.is_junction():
                raise ValueError("不支持符号链接、目录联接或外部表空间")
            relative = path.relative_to(root).as_posix()
            if path.is_dir():
                directories.append(relative)
                pending.append(path)
            elif path.is_file():
                with path.open("rb") as stream:
                    files[relative] = hashlib.file_digest(stream, "sha256").hexdigest()
            else:
                raise ValueError("数据目录含不支持的文件类型")
    return {"files": files, "directories": sorted(directories)}


def stopped_source(path):
    if path.is_symlink() or path.is_junction():
        raise ValueError("数据目录不能是符号链接或目录联接")
    if not (path / "PG_VERSION").is_file() or not (path / "global/pg_control").is_file():
        raise ValueError("源目录不是完整 PostgreSQL 数据目录")
    if (path / "postmaster.pid").exists():
        raise ValueError("源库仍有 postmaster.pid；必须先正常停止并由管理员核实，工具不会删除锁文件")


def verify(backup, *, require_directories=False, restored_data=None):
    metadata = json.loads((backup / "manifest.json").read_text(encoding="utf-8"))
    version = metadata.get("format")
    if version not in ("zhijie-postgres-cold-copy-v1", "zhijie-postgres-cold-copy-v2"):
        raise ValueError("备份格式不支持")
    if (require_directories or restored_data is not None) and version == "zhijie-postgres-cold-copy-v1":
        raise ValueError("旧版清单未记录目录结构，只能核对文件")
    data = backup / "data"
    stopped_source(data)
    actual = inventory(data)
    if version == "zhijie-postgres-cold-copy-v2" and actual["directories"] != metadata.get("directories"):
        raise ValueError("备份目录缺失或额外增加")
    if actual["files"] != metadata["files"]:
        raise ValueError("备份文件缺失、额外增加或摘要不一致")
    if restored_data is not None:
        restored = restored_data.resolve(strict=True)
        archive = backup.resolve(strict=True)
        if restored.is_relative_to(archive) or archive.is_relative_to(restored):
            raise ValueError("恢复目录必须独立于备份目录")
        stopped_source(restored_data)
        if inventory(restored_data) != actual:
            raise ValueError("恢复副本与备份不一致；不要启动数据库")
    return len(metadata["files"])


def snapshot(source, destination):
    # 目标必须不存在且不在源目录内；不覆盖或删除任何已有文件。
    if destination.exists() or destination.is_symlink():
        raise ValueError("目标已存在；请选择全新备份目录")
    source = source.absolute()
    destination = destination.absolute()
    stopped_source(source)
    source = source.resolve()
    destination = destination.resolve()
    if destination == source or source in destination.parents:
        raise ValueError("备份目标不能位于源数据目录内")
    before = inventory(source)
    destination.mkdir(parents=True, exist_ok=False)
    shutil.copytree(source, destination / "data", symlinks=True)
    stopped_source(source)
    if inventory(source) != before or inventory(destination / "data") != before:
        raise ValueError("复制期间数据发生变化；保留不完整目标供检查，不得用于恢复")
    metadata = {"format": "zhijie-postgres-cold-copy-v2", "postgresVersion": (source / "PG_VERSION").read_text().strip(), **before}
    # 只有完整复制并核对成功后才产生清单；中途失败的目录没有有效完成标记。
    (destination / "manifest.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return verify(destination)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    copy = commands.add_parser("snapshot")
    copy.add_argument("--source", type=Path, required=True)
    copy.add_argument("--destination", type=Path, required=True)
    check = commands.add_parser("verify")
    check.add_argument("--backup", type=Path, required=True)
    check.add_argument("--require-directories", action="store_true", help="要求清单可验证空目录；拒绝旧版仅文件清单")
    check.add_argument("--restored-data", type=Path, help="启动前只读比对已复制的独立数据目录，包括空目录；要求 v2 清单")
    args = parser.parse_args()
    try:
        count = (snapshot(args.source, args.destination) if args.command == "snapshot"
                 else verify(args.backup, require_directories=args.require_directories, restored_data=args.restored_data))
    except (OSError, ValueError, KeyError) as error:
        parser.exit(1, f"冷备校验失败：{error}\n")
    label = "COLD_RESTORE_COPY_OK" if args.command == "verify" and args.restored_data is not None else "COLD_BACKUP_OK"
    print(f"{label} files={count}")


if __name__ == "__main__":
    main()
