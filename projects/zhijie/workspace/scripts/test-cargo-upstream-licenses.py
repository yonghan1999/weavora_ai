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
spec = importlib.util.spec_from_file_location("cargo_upstream", Path(__file__).with_name("collect-cargo-upstream-licenses.py"))
upstream = importlib.util.module_from_spec(spec)
spec.loader.exec_module(upstream)


class CargoUpstreamTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.registry = self.root / "registry"
        self.archive = self.registry / "cache/test/example-1.crate"
        self.archive.parent.mkdir(parents=True)
        self.output = self.root / "output"
        self.evidence = self.root / "evidence.json"
        self.commit = "a" * 40
        self.content = b"original\r\n\xff"
        self.blob = hashlib.sha1(b"blob " + str(len(self.content)).encode() + b"\x00" + self.content).hexdigest()
        self.tree_url = f"https://api.github.com/repos/example/repo/git/trees/{self.commit}"
        self.raw_url = f"https://raw.githubusercontent.com/example/repo/{self.commit}/LICENSE"
        self.tree = {"sha": "b" * 40, "truncated": False, "tree": [
            {"path": "LICENSE", "mode": "100644", "type": "blob", "sha": self.blob}]}
        self.build_archive()

    def build_archive(self, repository="https://github.com/example/repo", vcs=True):
        package = f'[package]\nname="example"\nversion="1"\nlicense="MIT"\nrepository="{repository}"\n'.encode()
        files = {"example-1/Cargo.toml": package}
        if vcs:
            files["example-1/.cargo_vcs_info.json"] = json.dumps({"git": {"sha1": self.commit}, "path_in_vcs": "sub"}).encode()
        with tarfile.open(self.archive, "w:gz") as archive:
            for name, data in files.items():
                member = tarfile.TarInfo(name)
                member.size = len(data)
                archive.addfile(member, io.BytesIO(data))
        self.evidence.write_text(json.dumps({"dependencies": [{"name": "example", "version": "1",
            "archiveSha256": upstream.base.sha(self.archive.read_bytes()), "declaredLicense": "MIT", "documents": []}]}))

    def download(self, url):
        return {self.tree_url: json.dumps(self.tree).encode(), self.raw_url: self.content}[url]

    def collect(self):
        return upstream.collect(self.evidence, self.registry, self.download)

    def write(self):
        manifest, files = self.collect()
        upstream.write(self.output, manifest, files)
        return manifest

    def test_exact_commit_original_bytes_and_offline_reconstruction(self):
        manifest = self.write()
        self.assertEqual(manifest["summary"]["withUpstreamOriginals"], 1)
        document = manifest["dependencies"][0]["documents"][0]
        self.assertEqual((self.output / document["file"]).read_bytes(), self.content)
        self.assertEqual(document["gitBlobSha1"], self.blob)
        self.assertFalse(manifest["releaseApproved"])
        self.assertEqual(upstream.check(self.output, self.evidence, self.registry), manifest["summary"])

    def test_git_blob_mismatch_stays_failed_and_roundtrips(self):
        self.tree["tree"][0]["sha"] = "c" * 40
        manifest = self.write()
        self.assertEqual(manifest["summary"]["withUpstreamOriginals"], 0)
        self.assertEqual(manifest["dependencies"][0]["documents"][0]["status"], "retrieval_failed")
        upstream.check(self.output, self.evidence, self.registry)

    def test_unresolved_commit_does_not_fetch_latest(self):
        self.build_archive(vcs=False)
        def reject(url):
            self.fail("unexpected network: " + url)
        manifest, _ = upstream.collect(self.evidence, self.registry, reject)
        self.assertEqual(manifest["dependencies"][0]["status"], "exact_source_unresolved")

    def test_repository_host_and_path_are_restricted(self):
        for value in ("https://github.com.evil/repo/a", "https://github.com/../a", "http://github.com/a/b", "https://github.com/a/b/extra"):
            self.assertIsNone(upstream.github_repository(value))
        self.assertEqual(upstream.github_repository("https://github.com/a/b.git"), "a/b")

    def test_archive_and_output_tampering_are_rejected(self):
        manifest = self.write()
        document = manifest["dependencies"][0]["documents"][0]
        (self.output / document["file"]).write_bytes(b"tamper")
        with self.assertRaisesRegex(ValueError, "DOCUMENT_CHANGED"):
            upstream.check(self.output, self.evidence, self.registry)
        self.archive.write_bytes(self.archive.read_bytes() + b"changed")
        with self.assertRaisesRegex(ValueError, "ARCHIVE_CHANGED"):
            self.collect()

    def test_removed_documents_changed_sources_or_summary_do_not_self_verify(self):
        original = self.write()
        for field, value in [("documents", []), ("commit", "b" * 40), ("declaredLicense", "approved")]:
            manifest = json.loads(json.dumps(original))
            manifest["dependencies"][0][field] = value
            (self.output / "manifest.json").write_text(json.dumps(manifest))
            with self.assertRaises(ValueError):
                upstream.check(self.output, self.evidence, self.registry)
        manifest = {**original, "summary": {}}
        (self.output / "manifest.json").write_text(json.dumps(manifest))
        with self.assertRaisesRegex(ValueError, "MATERIALS_CHANGED"):
            upstream.check(self.output, self.evidence, self.registry)

    def test_tree_failures_and_nonregular_members_remain_explicit(self):
        def reject(url):
            raise TimeoutError()
        manifest, files = upstream.collect(self.evidence, self.registry, reject)
        upstream.write(self.output, manifest, files)
        self.assertEqual(manifest["dependencies"][0]["status"], "root_materials_missing")
        self.assertEqual(manifest["dependencies"][0]["errorCategory"], "TimeoutError")
        upstream.check(self.output, self.evidence, self.registry)
        self.tree["tree"][0]["mode"] = "120000"
        manifest, _ = self.collect()
        self.assertEqual(manifest["summary"]["withUpstreamOriginals"], 0)

    def test_paths_and_overwriting_are_rejected(self):
        manifest = self.write()
        with self.assertRaises(FileExistsError):
            upstream.write(self.output, *self.collect())
        manifest["dependencies"][0]["treeFile"] = "../outside.json"
        (self.output / "manifest.json").write_text(json.dumps(manifest))
        with self.assertRaisesRegex(ValueError, "PATH_INVALID"):
            upstream.check(self.output, self.evidence, self.registry)

    def test_tree_rate_limit_falls_back_only_to_fixed_commit_urls(self):
        def fallback(url):
            if url == self.tree_url:
                raise upstream.urllib.error.HTTPError(url, 403, "rate limit", {}, None)
            if url == self.raw_url:
                return self.content
            raise upstream.urllib.error.HTTPError(url, 404, "missing", {}, None)
        manifest, files = upstream.collect(self.evidence, self.registry, fallback)
        upstream.write(self.output, manifest, files)
        self.assertEqual(manifest["summary"]["withUpstreamOriginals"], 1)
        self.assertEqual(manifest["dependencies"][0]["errorCategory"], "http_403")
        self.assertIsNone(manifest["dependencies"][0]["documents"][0]["gitBlobSha1"])
        upstream.check(self.output, self.evidence, self.registry)


if __name__ == "__main__":
    unittest.main()
