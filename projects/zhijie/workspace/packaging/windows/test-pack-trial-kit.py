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

    def test_reproducible_allowlist_excludes_local_data(self):
        (self.program.parent / 'unexpected.dll').write_bytes(b'unrelated')
        (self.manifest.parent / 'webview').mkdir()
        (self.manifest.parent / 'webview/session').write_bytes(b'local')
        kit.pack(self.root, self.output)
        with ZipFile(self.output) as archive:
            self.assertEqual(set(archive.namelist()), {'README.md', 'ops/database/cold-backup.py', 'scripts/local-preview/trial-desktop.py',
                             'target/desktop/manifest.json', 'target/desktop/app/zhijie-shell.exe', 'backend/lib/test.jar'})
        other = self.target / 'second.zip'
        kit.pack(self.root, other)
        self.assertEqual(self.output.read_bytes(), other.read_bytes())
        with self.assertRaises(FileExistsError):
            kit.pack(self.root, self.output)
        self.assertEqual(self.output.read_bytes(), other.read_bytes())

    def test_changed_input_leaves_no_kit(self):
        for path in (self.manifest, self.backend, self.program):
            original = path.read_bytes()
            with self.subTest(path=path.name):
                path.write_bytes(original + b'changed')
                with self.assertRaises(ValueError):
                    kit.pack(self.root, self.output)
                self.assertFalse(self.output.exists())
            path.write_bytes(original)


if __name__ == '__main__':
    unittest.main()
