import hashlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile


spec = importlib.util.spec_from_file_location('kit', Path(__file__).with_name('pack-trial-kit.py'))
kit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(kit)


@unittest.skipUnless(os.name == 'nt', 'Windows trial kit')
class TrialKitTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[2] / 'target')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.target = self.root / 'target'
        app = self.target / 'desktop/app'
        app.mkdir(parents=True)
        self.program = app / 'zhijie-shell.exe'
        self.program.write_bytes(b'desktop')
        self.manifest = app.parent / 'manifest.json'
        self.manifest.write_text(json.dumps({'productFiles': {self.program.name: hashlib.sha256(b'desktop').hexdigest()}}))
        self.backend = self.target / 'backend.zip'
        with ZipFile(self.backend, 'x') as backend:
            backend.writestr('lib/test.jar', b'library')
        for directory, name in [('packaging/windows', 'trial-kit-guide.md'), ('scripts/local-preview', 'trial-desktop.py'),
                                ('ops/database', 'cold-backup.py')]:
            parent = self.root / directory
            parent.mkdir(parents=True)
            (parent / name).write_text('fixture')
        (self.root / 'packaging/windows/trial-kit.json').write_text(json.dumps({
            'desktopManifest': 'target/desktop/manifest.json', 'desktopManifestSha256': hashlib.sha256(self.manifest.read_bytes()).hexdigest(),
            'backendZip': 'target/backend.zip', 'backendZipSha256': hashlib.sha256(self.backend.read_bytes()).hexdigest()}))
        self.output = self.target / 'kit.zip'
        self.notice = self.root / 'packaging/windows/trial-notices/test-LICENSE.txt'
        self.notice.parent.mkdir()
        self.notice.write_bytes(b'upstream notice\n')
        digest = hashlib.sha256(b'library').hexdigest()
        self.sources = self.notice.parent / 'sources.json'
        self.notices = {'documents': {self.notice.name: hashlib.sha256(self.notice.read_bytes()).hexdigest()},
                        'jars': {'test.jar': {'sha256': digest, 'materials': [{'file': self.notice.name, 'source': 'https://example.org/LICENSE'}]}}}
        self.index = self.root / 'docs/engineering/maven-binary-license-originals/manifest.json'
        self.index.parent.mkdir(parents=True)
        self.index.write_text(json.dumps({'dependencies': [{'jarSha256': digest, 'jarPath': 'test.jar', 'documents': []}]}))
        self.pin_sources()

    def pin_sources(self):
        self.sources.write_text(json.dumps(self.notices))
        config = self.root / 'packaging/windows/trial-kit.json'
        spec = json.loads(config.read_bytes())
        spec['noticeSourcesSha256'] = hashlib.sha256(self.sources.read_bytes()).hexdigest()
        config.write_text(json.dumps(spec))

    def test_reproducible_allowlist_excludes_local_data(self):
        (self.program.parent / 'unexpected.dll').write_bytes(b'unrelated')
        (self.manifest.parent / 'webview').mkdir()
        (self.manifest.parent / 'webview/session').write_bytes(b'local')
        kit.pack(self.root, self.output)
        with ZipFile(self.output) as archive:
            self.assertEqual(set(archive.namelist()), {'README.md', 'ops/database/cold-backup.py', 'scripts/local-preview/trial-desktop.py',
                             'target/desktop/manifest.json', 'target/desktop/app/zhijie-shell.exe', 'backend/lib/test.jar',
                             'third-party/test-LICENSE.txt', 'third-party/sources.json'})
            self.assertEqual(archive.read('third-party/test-LICENSE.txt'), self.notice.read_bytes())
        other = self.target / 'second.zip'
        kit.pack(self.root, other)
        self.assertEqual(self.output.read_bytes(), other.read_bytes())
        with self.assertRaises(FileExistsError):
            kit.pack(self.root, self.output)
        self.assertEqual(self.output.read_bytes(), other.read_bytes())

    def test_changed_input_leaves_no_kit(self):
        for path in (self.manifest, self.backend, self.program, self.notice, self.sources):
            original = path.read_bytes()
            with self.subTest(path=path.name):
                path.write_bytes(original + b'changed')
                with self.assertRaises(ValueError):
                    kit.pack(self.root, self.output)
                self.assertFalse(self.output.exists())
            path.write_bytes(original)

    def test_notice_rejects_wrong_dependency(self):
        entry = self.notices['jars']['test.jar']
        for jars in [{}, {'missing.jar': entry}, {'test.jar': dict(entry, sha256='0' * 64)},
                     {'test.jar': dict(entry, materials=[])}, {'test.jar': entry, 'extra.jar': entry}]:
            self.notices['jars'] = jars
            self.pin_sources()
            with self.subTest(jars=jars), self.assertRaises(ValueError):
                kit.pack(self.root, self.output)
            self.assertFalse(self.output.exists())

    def test_unknown_binary_and_unbound_document_fail_closed(self):
        self.notices['documents']['orphan.txt'] = self.notices['documents'][self.notice.name]
        self.pin_sources()
        with self.assertRaises(ValueError):
            kit.pack(self.root, self.output)
        self.assertFalse(self.output.exists())
        del self.notices['documents']['orphan.txt']
        self.pin_sources()
        self.index.write_text(json.dumps({'dependencies': []}))
        with self.assertRaises(ValueError):
            kit.pack(self.root, self.output)
        self.assertFalse(self.output.exists())


if __name__ == '__main__':
    unittest.main()
