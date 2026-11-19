import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile


spec = importlib.util.spec_from_file_location('backend', Path(__file__).with_name('pack-preview-backend.py'))
backend = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backend)


@unittest.skipUnless(os.name == 'nt', 'Windows local preview')
class BackendPackageTest(unittest.TestCase):
    def setUp(self):
        target = Path(__file__).resolve().parents[2] / 'target'
        target.mkdir(exist_ok=True)
        temporary = tempfile.TemporaryDirectory(dir=target, prefix='backend-package-')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.repository = self.root / 'repo'
        self.output = self.root / 'bundle.zip'
        self.build = self.root / 'control-plane/target'
        self.classpath = self.build / 'local-preview-classpath.txt'

        def create(path):
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(path.name.encode())
            return path

        for name in ['LocalPreview.java', 'CleanCmd.cs', 'start.ps1', 'stop.ps1']:
            create(self.root / 'scripts/local-preview' / name)
        create(self.build / 'classes/app/weavora/zhijie/controlplane/ControlPlaneApplication.class')
        for name in ['application.yml', 'application-runtime.yml', 'logback-spring.xml', 'db/migration/V1.sql', 'mappers/Test.xml']:
            create(self.build / 'classes' / name)
            create(self.root / 'control-plane/src/main/resources' / name)
        create(self.root / 'target/local-preview/postgres/PG_VERSION')
        create(self.build / 'classes/local-credentials.txt')
        self.jar = create(self.repository / 'component/one.jar')
        self.classpath.write_text(str(self.jar), encoding='utf-8')

    def pack(self):
        backend.pack(self.root, self.repository, self.output)

    def test_relative_classpath_and_allowlisted_payload_only(self):
        self.pack()
        with ZipFile(self.output) as archive:
            self.assertEqual(archive.read('control-plane/target/local-preview-classpath.txt'), b'lib/one.jar')
            self.assertEqual(archive.read('lib/one.jar'), self.jar.read_bytes())
            self.assertFalse(any('PG_VERSION' in name or 'credentials' in name for name in archive.namelist()))
        original = self.output.read_bytes()
        with self.assertRaises(FileExistsError):
            self.pack()
        self.assertEqual(self.output.read_bytes(), original)

    def test_rejects_external_duplicate_or_missing_dependency(self):
        for invalid in [str(self.root / 'private.jar'), f'{self.jar};{self.jar}', 'relative.jar', str(self.repository / 'missing.jar')]:
            (self.root / 'private.jar').write_bytes(b'not a dependency')
            self.classpath.write_text(invalid, encoding='utf-8')
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                self.pack()
            self.assertFalse(self.output.exists())

    def test_rejects_local_resource_override(self):
        (self.build / 'classes/application.yml').write_bytes(b'local settings')
        with self.assertRaises(ValueError):
            self.pack()
        self.assertFalse(self.output.exists())

    def test_windows_bundle_filters_only_other_platform_postgres_binaries(self):
        offline = self.repository / 'io/zonky/test/postgres'
        def binary(parent, platform):
            artifact = f'embedded-postgres-binaries-{platform}'
            path = parent / artifact / '17.5.0' / f'{artifact}-17.5.0.jar'
            path.parent.mkdir(parents=True)
            path.write_bytes(b'fixture')
            return path

        windows = binary(offline, 'windows-amd64')
        other_group = binary(self.repository / 'other', 'linux-arm64')
        foreign = [binary(offline, platform) for platform in ('darwin-amd64', 'linux-amd64', 'linux-amd64-alpine')]
        self.classpath.write_text(';'.join(map(str, [self.jar, *foreign, windows, other_group])), encoding='utf-8')
        self.pack()
        with ZipFile(self.output) as archive:
            expected = [f'lib/{jar.name}' for jar in (self.jar, windows, other_group)]
            self.assertEqual([name for name in archive.namelist() if name.startswith('lib/')], expected)
            self.assertEqual(archive.read('control-plane/target/local-preview-classpath.txt').decode(), ';'.join(expected))


if __name__ == '__main__':
    unittest.main()
