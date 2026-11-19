import importlib.util
from pathlib import Path
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


if __name__ == '__main__':
    unittest.main()
