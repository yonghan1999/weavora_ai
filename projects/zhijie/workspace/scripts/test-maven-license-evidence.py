import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

sys.dont_write_bytecode = True
SCRIPT = Path(__file__).with_name("collect-maven-license-evidence.py")
spec = importlib.util.spec_from_file_location("license_evidence", SCRIPT)
evidence = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evidence)


class MavenLicenseEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repository = self.root / "repository"
        self.repository.mkdir()
        self.snapshot = self.root / "dependencies.txt"
        self.snapshot.write_text("org.example:child:jar:1:compile\n", encoding="utf-8")

    def pom(self, name, content):
        path = self.repository / f"org/example/{name}/1/{name}-1.pom"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('<project xmlns="http://maven.apache.org/POM/4.0.0">' + content + '</project>', encoding="utf-8")
        return path

    def jar(self):
        path = self.repository / "org/example/child/1/child-1.jar"
        path.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("META-INF/LICENSE", "license fixture")
            archive.writestr("META-INF/NOTICE.txt", "notice fixture")
            archive.writestr("unrelated.class", "not a license")
        return path

    def test_inherited_declarations_and_exact_jar_documents_keep_hashes_and_scope(self):
        parent = '<parent><groupId>org.example</groupId><artifactId>parent</artifactId><version>1</version></parent>'
        child = self.pom("child", parent)
        self.pom("parent", '<licenses><license><name>Example License</name><url>https://example.test/license</url></license></licenses>')
        self.jar()
        result = evidence.collect(self.snapshot, self.repository)
        row = result["dependencies"][0]
        self.assertEqual(row["declarationStatus"], "inherited_declaration")
        self.assertEqual(row["declaredLicenses"][0]["name"], "Example License")
        self.assertEqual(row["pomSources"][0]["sha256"], evidence.digest(child.read_bytes()))
        self.assertEqual([item["entry"] for item in row["jar"]["documents"]], ["META-INF/LICENSE", "META-INF/NOTICE.txt"])
        self.assertEqual(row["scope"], "compile")
        self.assertFalse(result["releaseApproved"])
        self.assertEqual(result["summary"]["reviewed"], 0)

    def test_missing_cache_never_becomes_a_license_conclusion(self):
        result = evidence.collect(self.snapshot, self.repository)
        row = result["dependencies"][0]
        self.assertEqual(row["declarationStatus"], "pom_missing")
        self.assertEqual(row["jar"]["status"], "missing")
        self.assertEqual(row["reviewStatus"], "unreviewed")

    def test_parent_cycles_and_unresolved_properties_remain_explicit(self):
        self.pom("child", '<parent><groupId>org.example</groupId><artifactId>child</artifactId><version>1</version></parent>')
        self.assertEqual(evidence.collect(self.snapshot, self.repository)["dependencies"][0]["declarationStatus"], "parent_cycle")
        self.pom("child", '<licenses><license><name>${license.name}</name></license></licenses>')
        row = evidence.collect(self.snapshot, self.repository)["dependencies"][0]
        self.assertEqual(row["declarationStatus"], "license_property_unresolved")
        self.assertEqual(row["declaredLicenses"], [])

    def test_malformed_coordinates_and_duplicate_snapshots_are_rejected(self):
        for value in ["../escape:artifact:jar:1:compile", "group:..:jar:1:compile", "group:artifact:jar:1:unknown", "group:artifact:pom:1:compile"]:
            with self.assertRaises(ValueError):
                evidence.coordinates(value)
        self.snapshot.write_text("org.example:child:jar:1:compile\n" * 2)
        with self.assertRaisesRegex(ValueError, "EMPTY_OR_DUPLICATED"):
            evidence.collect(self.snapshot, self.repository)

    def test_classifier_jar_is_not_confused_with_main_artifact(self):
        self.snapshot.write_text("org.example:child:jar:tests:1:test\n")
        self.pom("child", "")
        self.jar()
        row = evidence.collect(self.snapshot, self.repository)["dependencies"][0]
        self.assertTrue(row["jar"]["path"].endswith("child-1-tests.jar"))
        self.assertEqual(row["jar"]["status"], "missing")

    def test_namespace_free_pom_is_supported_without_accepting_unknown_namespace(self):
        path = self.pom("child", "")
        path.write_text('<project><licenses><license><name>Exact declared name</name></license></licenses></project>')
        row = evidence.collect(self.snapshot, self.repository)["dependencies"][0]
        self.assertEqual(row["declaredLicenses"][0]["name"], "Exact declared name")
        path.write_text('<project xmlns="https://unrecognized.example.test/pom"/>')
        self.assertEqual(evidence.collect(self.snapshot, self.repository)["dependencies"][0]["declarationStatus"], "pom_namespace_unsupported")

    def test_cli_refuses_overwrite_and_detects_document_tampering(self):
        self.pom("child", '<licenses><license><name>Example License</name></license></licenses>')
        jar = self.jar()
        output = self.root / "evidence.json"
        command = [sys.executable, str(SCRIPT), "--repository", str(self.repository), "--snapshot", str(self.snapshot), "--output", str(output)]
        def run(*args):
            return subprocess.run(command + list(args), capture_output=True, text=True)
        self.assertEqual(run().returncode, 0)
        original = output.read_bytes()
        self.assertEqual(run("--check").returncode, 0)
        self.assertNotEqual(run().returncode, 0)
        self.assertEqual(output.read_bytes(), original)
        with zipfile.ZipFile(jar, "a") as archive:
            archive.writestr("META-INF/COPYING", "changed obligation fixture")
        self.assertNotEqual(run("--check").returncode, 0)
        self.assertEqual(output.read_bytes(), original)
        self.assertEqual(json.loads(original)["reviewStatus"], "unreviewed")


if __name__ == "__main__":
    unittest.main()
