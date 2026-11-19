import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import sys


spec = importlib.util.spec_from_file_location("cold_backup", Path(__file__).with_name("cold-backup.py"))
backup = importlib.util.module_from_spec(spec)
sys.dont_write_bytecode = True
spec.loader.exec_module(backup)


class ColdBackupTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "source"
        (self.source / "global").mkdir(parents=True)
        (self.source / "PG_VERSION").write_text("17\n")
        (self.source / "global/pg_control").write_bytes(b"fixture-only")
        self.destination = self.root / "backup"

    def test_verified_copy_rejects_tampering_and_preserves_source(self):
        original = backup.inventory(self.source)
        self.assertEqual(backup.snapshot(self.source, self.destination), 2)
        self.assertEqual(backup.verify(self.destination), 2)
        self.assertEqual(backup.inventory(self.source), original)
        (self.destination / "data/global/pg_control").write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "摘要不一致"):
            backup.verify(self.destination)
        self.assertEqual(backup.inventory(self.source), original)

    def test_running_source_is_rejected_without_creating_destination(self):
        (self.source / "postmaster.pid").write_text("123\n")
        with self.assertRaisesRegex(ValueError, "正常停止"):
            backup.snapshot(self.source, self.destination)
        self.assertFalse(self.destination.exists())

    def test_existing_destination_is_never_overwritten(self):
        self.destination.mkdir()
        marker = self.destination / "owned.txt"
        marker.write_text("keep")
        with self.assertRaisesRegex(ValueError, "目标已存在"):
            backup.snapshot(self.source, self.destination)
        self.assertEqual(marker.read_text(), "keep")

    def test_missing_or_extra_empty_directory_invalidates_backup(self):
        (self.source / "pg_wal/archive_status").mkdir(parents=True)
        backup.snapshot(self.source, self.destination)
        archived = self.destination / "data/pg_wal/archive_status"
        archived.rmdir()
        with self.assertRaisesRegex(ValueError, "目录"):
            backup.verify(self.destination)
        archived.mkdir()
        self.assertEqual(backup.verify(self.destination), 2)
        (self.destination / "data/unexpected").mkdir()
        with self.assertRaisesRegex(ValueError, "目录"):
            backup.verify(self.destination)

    def test_legacy_manifest_remains_file_only(self):
        backup.snapshot(self.source, self.destination)
        manifest = self.destination / "manifest.json"
        metadata = json.loads(manifest.read_text(encoding="utf-8"))
        metadata["format"] = "zhijie-postgres-cold-copy-v1"
        metadata.pop("directories", None)
        manifest.write_text(json.dumps(metadata), encoding="utf-8")
        self.assertEqual(backup.verify(self.destination), 2)
        with self.assertRaisesRegex(ValueError, "旧版清单"):
            backup.verify(self.destination, require_directories=True)

    def test_nested_destination_is_rejected_before_mutation(self):
        nested = self.source / "nested"
        with self.assertRaisesRegex(ValueError, "源数据目录内"):
            backup.snapshot(self.source, nested)
        self.assertFalse(nested.exists())

    def test_extra_or_missing_files_are_detected(self):
        backup.snapshot(self.source, self.destination)
        extra = self.destination / "data/extra"
        extra.write_text("unexpected")
        with self.assertRaises(ValueError):
            backup.verify(self.destination)
        extra.unlink()
        (self.destination / "data/PG_VERSION").unlink()
        with self.assertRaises(ValueError):
            backup.verify(self.destination)


if __name__ == "__main__":
    unittest.main()
