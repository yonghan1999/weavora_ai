"""验证预览驱动的来源、架构、签名与拒绝覆盖行为。"""

import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest


spec = importlib.util.spec_from_file_location("prepare_wintun", Path(__file__).with_name("prepare-wintun.py"))
driver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(driver)


class DriverPreparationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cargo_home = Path(os.environ.get("CARGO_HOME", Path.home() / ".cargo"))
        candidates = list((cargo_home / "registry/cache").glob(f"*/wintun-bindings-{driver.VERSION}.crate"))
        if len(candidates) != 1:
            raise RuntimeError("测试需要真实锁定的 Cargo 缓存归档")
        cls.archive = candidates[0]

    def test_real_signed_archive_stage_and_idempotence(self):
        with tempfile.TemporaryDirectory(prefix="zhijie-wintun-") as root:
            output = Path(root) / "with spaces"
            manifest = driver.stage(self.archive, output)
            self.assertEqual(driver.stage(self.archive, output), manifest)
            self.assertEqual(json.loads((output / "wintun-source.json").read_text()), manifest)
            for name, digest in manifest["files"].items():
                self.assertEqual(driver.sha256((output / name).read_bytes()), digest)

    def test_modified_archive_rejected(self):
        with tempfile.TemporaryDirectory(prefix="zhijie-wintun-") as root:
            archive = Path(root) / "modified.crate"
            archive.write_bytes(self.archive.read_bytes() + b"modified")
            with self.assertRaisesRegex(ValueError, "归档摘要"):
                driver.read_archive(archive)

    def test_lock_drift_rejected(self):
        with tempfile.TemporaryDirectory(prefix="zhijie-wintun-") as root:
            lock = Path(root) / "Cargo.lock"
            lock.write_text((driver.WORKSPACE / "Cargo.lock").read_text().replace(
                driver.ARCHIVE_SHA256, "0" * 64), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "来源变化"):
                driver.validate_lock(lock)

    def test_wrong_architecture_and_unsigned_file_rejected(self):
        data = bytearray(driver.read_archive(self.archive)["wintun.dll"])
        offset = int.from_bytes(data[0x3C:0x40], "little")
        data[offset + 4:offset + 6] = b"\x4c\x01"
        with self.assertRaisesRegex(ValueError, "仅支持 Windows x64"):
            driver.validate_x64(data)
        with tempfile.TemporaryDirectory(prefix="zhijie-wintun-") as root:
            path = Path(root) / "wintun.dll"
            path.write_bytes(b"not a signed driver")
            with self.assertRaisesRegex(ValueError, "签名校验失败"):
                driver.verify_signature(path)

    def test_existing_file_is_preserved(self):
        with tempfile.TemporaryDirectory(prefix="zhijie-wintun-") as root:
            output = Path(root)
            target = output / "wintun.dll"
            target.write_bytes(b"user file")
            with self.assertRaisesRegex(ValueError, "拒绝覆盖"):
                driver.stage(self.archive, output)
            self.assertEqual(target.read_bytes(), b"user file")
            self.assertEqual(list(output.iterdir()), [target])


if __name__ == "__main__":
    unittest.main()
