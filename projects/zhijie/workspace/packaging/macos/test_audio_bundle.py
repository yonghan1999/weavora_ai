import importlib.util
import json
from pathlib import Path
import plistlib
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location('checker', Path(__file__).with_name('check-audio-bundle.py'))
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)
SHELL = Path(__file__).resolve().parents[2] / 'desktop/shell'


class MacCandidateTest(unittest.TestCase):
    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        self.app = Path(folder.name).resolve() / 'Trial.app'
        self.executable = self.app / 'Contents/MacOS/trial'
        self.executable.parent.mkdir(parents=True)
        self.executable.touch()
        base = json.loads((SHELL / 'tauri.conf.json').read_bytes())
        self.info = plistlib.loads((SHELL / 'Info.plist').read_bytes())
        self.info.update(CFBundleExecutable='trial', CFBundleIdentifier=base['identifier'],
                         CFBundleShortVersionString=base['version'], LSMinimumSystemVersion='13.0', CFBundlePackageType='APPL')
        self.plist = self.app / 'Contents/Info.plist'
        self.plist.write_bytes(plistlib.dumps(self.info))
        self.rights = (SHELL / 'Entitlements.plist').read_bytes()

    def test_matching_single_architecture(self):
        for arch in ('arm64', 'x86_64'):
            with self.subTest(arch=arch), patch.object(subprocess, 'run'), patch.object(subprocess, 'check_output', side_effect=[arch, self.rights]) as read:
                checker.check_architectures(self.app, self.info, arch)
                self.assertEqual(read.call_count, 2)

    def test_wrong_cpu_or_missing_slice_fails_before_checking_rights(self):
        for declared, output in [('arm64', 'x86_64'), ('universal', 'arm64'), ('universal', 'arm64 arm64')]:
            with self.subTest(declared=declared, output=output), patch.object(subprocess, 'check_output', return_value=output), self.assertRaisesRegex(ValueError, '架构'):
                checker.check_architectures(self.app, self.info, declared)

    def test_universal_requires_microphone_right_in_both_slices(self):
        for last in (self.rights, plistlib.dumps({})):
            with patch.object(subprocess, 'run') as verify, patch.object(subprocess, 'check_output', side_effect=['x86_64 arm64', self.rights, last]) as read:
                if last == self.rights:
                    checker.check_architectures(self.app, self.info, 'universal')
                else:
                    with self.assertRaisesRegex(ValueError, '麦克风授权'):
                        checker.check_architectures(self.app, self.info, 'universal')
                verify.assert_called_once_with(['/usr/bin/codesign', '--verify', '--strict', '--all-architectures', self.app], check=True)
                self.assertEqual([call.args[0][3] for call in read.call_args_list[1:]], ['x86_64', 'arm64'])

    def test_rejects_unpaired_arguments_wrong_version_and_external_executable(self):
        with self.assertRaisesRegex(ValueError, '--arch'):
            checker.check(SHELL, self.app)
        with self.assertRaisesRegex(ValueError, '--arch'):
            checker.check(SHELL, expected_arch='arm64')
        self.plist.write_bytes(plistlib.dumps({**self.info, 'CFBundleShortVersionString': '0.0.0'}))
        with patch.object(sys, 'platform', 'darwin'), self.assertRaisesRegex(ValueError, '版本'):
            checker.check(SHELL, self.app, 'universal')
        with self.assertRaisesRegex(ValueError, '文件名'):
            checker.check_architectures(self.app, {**self.info, 'CFBundleExecutable': '../other'}, 'arm64')

    @unittest.skipUnless(sys.platform == 'darwin', 'requires macOS, Xcode command line tools and codesign')
    def test_real_signed_universal_fixture(self):
        subprocess.run(['/usr/bin/xcrun', 'clang', '-arch', 'arm64', '-arch', 'x86_64', '-mmacosx-version-min=13.0',
                        '-x', 'c', '-', '-o', self.executable], input=b'int main(void){return 0;}\n', check=True)
        subprocess.run(['/usr/bin/codesign', '--force', '--sign', '-', '--entitlements', SHELL / 'Entitlements.plist', self.app], check=True)
        checker.check(SHELL, self.app, 'universal')
        with self.assertRaisesRegex(ValueError, '架构'):
            checker.check(SHELL, self.app, 'arm64')


if __name__ == '__main__':
    unittest.main()
