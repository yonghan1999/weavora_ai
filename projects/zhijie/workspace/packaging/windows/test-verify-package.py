import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest


spec = importlib.util.spec_from_file_location('bundle', Path(__file__).with_name('verify-package.py'))
bundle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bundle)


class PackageInventoryTest(unittest.TestCase):
    def setUp(self):
        self.inputs = bundle.expected_inputs(Path('release'), Path('scripts'), Path('driver'))

    def listing(self, names, kind='Nsis'):
        return '\r\n'.join([f'Type = {kind}', '----------', *(f'Path = {name}' for name in names)])

    def test_checks_each_product_and_ignores_installer_plugins(self):
        self.assertEqual(len(self.inputs), 8)
        self.assertEqual(self.inputs['zhijie-helper.exe'], Path('release/zhijie-helper.exe'))
        bundle.check_listing(self.listing([*self.inputs, '$PLUGINSDIR/System.dll']), self.inputs)

    def test_missing_extra_duplicate_and_nested_payloads_rejected(self):
        names = list(self.inputs)
        for changed in (names[:-1], [*names, 'extra.dll'], [*names, names[0]], [*names[1:], 'nested/' + names[0]]):
            with self.subTest(changed=changed), self.assertRaises(ValueError):
                bundle.check_listing(self.listing(changed), self.inputs)

    def test_non_installer_format_rejected(self):
        with self.assertRaises(ValueError):
            bundle.check_listing(self.listing(self.inputs, 'zip'), self.inputs)


@unittest.skipUnless(os.name == 'nt', 'Windows portable candidate')
class PortableCandidateTest(unittest.TestCase):
    def setUp(self):
        target = Path(__file__).resolve().parents[2] / 'target'
        target.mkdir(exist_ok=True)
        self.scratch = tempfile.TemporaryDirectory(dir=target, prefix='portable-test-')
        self.addCleanup(self.scratch.cleanup)
        self.root = Path(self.scratch.name)
        inputs = self.root / 'inputs'
        inputs.mkdir()
        self.inputs = bundle.expected_inputs(inputs, inputs, inputs)
        for name, source in self.inputs.items():
            source.write_bytes(name.encode())
        self.report = {'productFiles': {name: bundle.file_digest(source) for name, source in self.inputs.items()}}

    def prepare(self, name='trial-test'):
        return bundle.prepare_portable(self.root, name, self.inputs, self.report)

    def test_complete_candidate_existing_profile_never_overwritten(self):
        candidate = self.prepare()
        self.assertEqual({item.name for item in candidate.iterdir()}, {'manifest.json', 'app'})
        self.assertEqual(json.loads((candidate / 'manifest.json').read_text(encoding='utf-8')), self.report)
        self.assertEqual({item.name: bundle.file_digest(item) for item in (candidate / 'app').iterdir()}, self.report['productFiles'])
        profile = candidate / 'webview'
        profile.mkdir()
        with self.assertRaises(FileExistsError):
            self.prepare()
        self.assertTrue(profile.is_dir())

    def test_changed_input_leaves_no_candidate_or_staging_data(self):
        self.inputs['wintun-source.json'].write_bytes(b'changed after verification')
        with self.assertRaises(ValueError):
            self.prepare()
        self.assertEqual([path.name for path in self.root.iterdir()], ['inputs'])

    def test_refuses_paths_and_reserved_names(self):
        for name in ('../trial-test', 'trial-test/app', 'trial-test.', 'CON', 'C:/trial-test', ''):
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.prepare(name)


if __name__ == '__main__':
    unittest.main()
