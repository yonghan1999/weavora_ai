import importlib.util
import io
from pathlib import Path
import tarfile
import unittest
from zipfile import ZipFile


spec = importlib.util.spec_from_file_location('origin', Path(__file__).with_name('verify-postgres-origin.py'))
origin = importlib.util.module_from_spec(spec)
spec.loader.exec_module(origin)


class OriginTest(unittest.TestCase):
    def compare(self, names, upstream):
        payload = io.BytesIO()
        with tarfile.open(fileobj=payload, mode='w:xz') as archive:
            for name in names:
                item = tarfile.TarInfo(name)
                item.size = 4
                archive.addfile(item, io.BytesIO(b'data'))
        payload.seek(0)
        reference = io.BytesIO()
        with ZipFile(reference, 'w') as archive:
            for name, data in upstream.items():
                archive.writestr(name, data)
        with ZipFile(reference) as archive:
            return origin.compare(payload, archive)

    def test_upstream_superset_is_expected(self):
        files = self.compare(['bin/a'], {'pgsql/bin/a': b'data', 'pgsql/doc/more': b'not packaged'})
        self.assertEqual(set(files), {'bin/a'})

    def test_rejects_wrong_bytes_path_or_ambiguous_payload(self):
        for names, reference in [(['bin/a'], {'pgsql/bin/a': b'fake'}), (['bin/a'], {'pgsql/lib/a': b'data'}),
                                 (['bin/a', 'BIN/A'], {'pgsql/bin/a': b'data', 'pgsql/BIN/A': b'data'}),
                                 (['../a'], {'pgsql/../a': b'data'}), ([], {})]:
            with self.subTest(names=names, reference=reference), self.assertRaises(ValueError):
                self.compare(names, reference)


if __name__ == '__main__':
    unittest.main()
