import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("cargo_materials", Path(__file__).with_name("collect-cargo-license-materials.py"))
materials = importlib.util.module_from_spec(spec)
spec.loader.exec_module(materials)


class CargoLicenseMaterialsTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.registry = self.root / "registry"
        self.output = self.root / "materials"
        self.original = b"original\r\n\x00\xff"
        self.archive = self.registry / "cache/test/example-1.0.0.crate"
        self.archive.parent.mkdir(parents=True)
        manifest = self.registry / "src/test/example-1.0.0/Cargo.toml"
        manifest.parent.mkdir(parents=True)
        self.package = {"name": "example", "version": "1.0.0", "source": materials.SOURCE,
                        "manifest_path": str(manifest), "license": "MIT OR Apache-2.0", "license_file": None}
        self.metadata = {"packages": [self.package, {"name": "local", "version": "1", "source": None}]}
        self.declaration = b'[package]\nname="example"\nversion="1.0.0"\nlicense="MIT OR Apache-2.0"\n'
        directory = self.root / "docs/engineering"
        directory.mkdir(parents=True)
        self.inventory = directory / "dependency-inventory.json"
        self.inventory.write_text(json.dumps({"dependencies": [
            {"ecosystem": "cargo", "name": "example", "version": "1.0.0", "license": "MIT OR Apache-2.0"},
            {"ecosystem": "cargo", "name": "local", "version": "1", "license": "Proprietary"}]}))
        self.build_archive()

    def build_archive(self, extra=None):
        content = {"example-1.0.0/Cargo.toml": self.declaration,
                   "example-1.0.0/LICENSE-MIT": self.original,
                   "example-1.0.0/LICENSE-APACHE": self.original}
        content.update(extra or {})
        with tarfile.open(self.archive, "w:gz") as archive:
            for name, data in content.items():
                item = tarfile.TarInfo(name)
                item.size = len(data)
                archive.addfile(item, io.BytesIO(data))
        self.write_lock(hashlib.sha256(self.archive.read_bytes()).hexdigest())

    def write_lock(self, checksum):
        (self.root / "Cargo.lock").write_text(f'[[package]]\nname="example"\nversion="1.0.0"\nsource="{materials.SOURCE}"\nchecksum="{checksum}"\n')

    def collect(self):
        return materials.collect(self.root, self.registry, self.metadata)

    def test_exact_archive_bytes_deduplication_and_local_exclusion(self):
        manifest, texts = self.collect()
        self.assertEqual(manifest["summary"], {"registryPackages": 1, "localPackagesExcluded": 1, "withOriginals": 1,
                                               "documentReferences": 2, "uniqueOriginals": 1,
                                               "unselectedLockedPackages": 0, "unselectedDocumentReferences": 0, "reviewed": 0})
        self.assertEqual(list(texts.values()), [self.original])
        self.assertEqual(manifest["excludedLocalPackages"][0]["name"], "local")
        self.assertEqual(manifest["dependencies"][0]["declaredLicense"], "MIT OR Apache-2.0")
        self.assertFalse(manifest["releaseApproved"])
        self.assertNotIn(str(self.root), json.dumps(manifest))
        materials.write_or_check(self.output, manifest, texts)
        materials.write_or_check(self.output, *self.collect(), check=True)

    def test_archive_checksum_mismatch_fails_before_output(self):
        self.archive.write_bytes(self.archive.read_bytes() + b"tamper")
        with self.assertRaisesRegex(ValueError, "CHECKSUM_MISMATCH"):
            self.collect()
        self.assertFalse(self.output.exists())

    def test_lock_dependency_changes_invalidate_materials_even_when_archives_are_unchanged(self):
        manifest, texts = self.collect()
        materials.write_or_check(self.output, manifest, texts)
        original = (self.output / "manifest.json").read_bytes()
        lock = self.root / "Cargo.lock"
        lock.write_bytes(lock.read_bytes() + b'dependencies = ["local"]\n')

        refreshed, refreshed_texts = self.collect()

        self.assertNotEqual(manifest["cargoLockSha256"], refreshed["cargoLockSha256"])
        self.assertEqual(refreshed["cargoLockSha256"], hashlib.sha256(lock.read_bytes()).hexdigest())
        self.assertEqual(manifest["dependencies"], refreshed["dependencies"])
        self.assertEqual(texts, refreshed_texts)
        with self.assertRaisesRegex(ValueError, "OUTPUT_CHANGED: manifest.json"):
            materials.write_or_check(self.output, refreshed, refreshed_texts, check=True)
        self.assertEqual((self.output / "manifest.json").read_bytes(), original)

        materials.write_or_check(self.root / "refreshed", refreshed, refreshed_texts)
        materials.write_or_check(self.root / "refreshed", *self.collect(), check=True)

    def test_archive_declaration_is_checked_against_metadata(self):
        self.package["license"] = "changed"
        data = json.loads(self.inventory.read_text())
        data["dependencies"][0]["license"] = "changed"
        self.inventory.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError, "DECLARATION_MISMATCH"):
            self.collect()

    def test_inventory_coverage_and_license_changes_are_rejected(self):
        self.metadata["packages"].pop()
        with self.assertRaisesRegex(ValueError, "INVENTORY_STALE"):
            self.collect()
        self.metadata["packages"].append({"name": "local", "version": "1", "source": None})
        self.package["license"] = "changed"
        with self.assertRaisesRegex(ValueError, "INVENTORY_DECLARATION_STALE"):
            self.collect()

    def test_archive_traversal_is_rejected_without_extraction(self):
        self.build_archive({"example-1.0.0/../../LICENSE": b"outside"})
        with self.assertRaisesRegex(ValueError, "ARCHIVE_PATH_INVALID"):
            self.collect()
        self.assertFalse((self.root / "LICENSE").exists())

    def test_missing_cache_and_unsupported_source_do_not_succeed(self):
        self.archive.unlink()
        with self.assertRaises(FileNotFoundError):
            self.collect()
        self.package["source"] = "git+https://example.test/repo"
        with self.assertRaisesRegex(ValueError, "SOURCE_UNSUPPORTED"):
            self.collect()

    def test_explicit_license_file_is_collected(self):
        self.declaration = b'[package]\nname="example"\nversion="1.0.0"\nlicense-file="legal/terms.txt"\n'
        self.package.update({"license": None, "license_file": "legal/terms.txt"})
        data = json.loads(self.inventory.read_text())
        data["dependencies"][0]["license"] = "unreviewed"
        self.inventory.write_text(json.dumps(data))
        self.build_archive({"example-1.0.0/legal/terms.txt": b"custom terms"})
        manifest, texts = self.collect()
        self.assertEqual(manifest["summary"]["documentReferences"], 3)
        self.assertIn(b"custom terms", texts.values())
        missing = materials.archive_documents(self.archive.read_bytes(), "example", "1.0.0", None, "legal/terms.txt")
        self.assertTrue(any(row["entry"].endswith("terms.txt") for row in missing))

    def test_unselected_lock_packages_are_explicit_and_missing_originals_not_approved(self):
        extra = self.archive.with_name("unselected-2.crate")
        with tarfile.open(extra, "w:gz") as archive:
            for name, data in {"unselected-2/Cargo.toml": b'[package]\nname="unselected"\nversion="2"\nlicense="MIT"\n',
                               "unselected-2/LICENSE": b"unselected original"}.items():
                item = tarfile.TarInfo(name)
                item.size = len(data)
                archive.addfile(item, io.BytesIO(data))
        with (self.root / "Cargo.lock").open("a") as stream:
            stream.write(f'[[package]]\nname="unselected"\nversion="2"\nsource="{materials.SOURCE}"\nchecksum="' + materials.sha(extra.read_bytes()) + '"\n')
        manifest, texts = self.collect()
        self.assertEqual(manifest["unselectedLockedPackages"][0]["name"], "unselected")
        self.assertEqual(manifest["summary"]["unselectedDocumentReferences"], 1)
        self.assertIn(b"unselected original", texts.values())
        data = io.BytesIO()
        with tarfile.open(fileobj=data, mode="w:gz") as archive:
            item = tarfile.TarInfo("example-1.0.0/Cargo.toml")
            item.size = len(self.declaration)
            archive.addfile(item, io.BytesIO(self.declaration))
        self.assertEqual(materials.archive_documents(data.getvalue(), "example", "1.0.0", self.package["license"], None), [])

    def test_output_cannot_be_overwritten_and_checks_rebuild_sources(self):
        manifest, texts = self.collect()
        materials.write_or_check(self.output, manifest, texts)
        with self.assertRaises(FileExistsError):
            materials.write_or_check(self.output, manifest, texts)
        original = (self.output / "manifest.json").read_bytes()
        changed = json.loads(original)
        changed["dependencies"][0]["documents"] = []
        (self.output / "manifest.json").write_text(json.dumps(changed))
        with self.assertRaisesRegex(ValueError, "OUTPUT_CHANGED"):
            materials.write_or_check(self.output, *self.collect(), check=True)
        (self.output / "manifest.json").write_bytes(original)
        (self.output / next(iter(texts))).write_bytes(b"tamper")
        with self.assertRaisesRegex(ValueError, "OUTPUT_CHANGED"):
            materials.write_or_check(self.output, *self.collect(), check=True)

    def test_nonregular_documents_are_not_followed(self):
        stream = io.BytesIO()
        with tarfile.open(fileobj=stream, mode="w:gz") as archive:
            manifest = tarfile.TarInfo("example-1.0.0/Cargo.toml")
            manifest.size = len(self.declaration)
            archive.addfile(manifest, io.BytesIO(self.declaration))
            link = tarfile.TarInfo("example-1.0.0/LICENSE")
            link.type = tarfile.SYMTYPE
            link.linkname = "../../secret"
            archive.addfile(link)
        result = materials.archive_documents(stream.getvalue(), "example", "1.0.0", self.package["license"], None)
        self.assertEqual(result[0]["status"], "non_regular_unread")
        self.assertNotIn("file", result[0])


if __name__ == "__main__":
    unittest.main()
