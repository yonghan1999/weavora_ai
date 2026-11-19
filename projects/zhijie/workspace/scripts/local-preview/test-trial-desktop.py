import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


launcher = Path(__file__).with_name('trial-desktop.py').resolve()
spec = importlib.util.spec_from_file_location('trial_desktop', launcher)
trial = importlib.util.module_from_spec(spec)
spec.loader.exec_module(trial)


@unittest.skipUnless(os.name == 'nt', 'Windows trial launcher')
class DesktopTrialTest(unittest.TestCase):
    def setUp(self):
        target = launcher.parents[2] / 'target'
        target.mkdir(exist_ok=True)
        self.scratch = tempfile.TemporaryDirectory(prefix='desktop-launch-test-', dir=target)
        self.addCleanup(self.scratch.cleanup)
        self.root = Path(self.scratch.name)
        app = self.root / 'app'
        app.mkdir()
        self.executable = app / 'zhijie-shell.exe'
        self.executable.write_bytes(b'not a runnable desktop')
        self.manifest = self.root / 'manifest.json'
        self.record_digest()

    def record_digest(self):
        self.manifest.write_text(json.dumps({'productFiles': {self.executable.name:
            hashlib.sha256(self.executable.read_bytes()).hexdigest()}}), encoding='utf-8')

    def run_trial(self, *arguments, **variables):
        return subprocess.run([sys.executable, '-B', launcher, self.root, *arguments], capture_output=True,
                              env=dict(os.environ, **variables), timeout=20)

    def test_read_only_check_and_mismatched_binary(self):
        self.assertEqual(self.run_trial('--check-only').returncode, 0)
        self.assertFalse((self.root / 'webview').exists())
        self.executable.write_bytes(b'changed')
        self.assertNotEqual(self.run_trial('--check-only').returncode, 0)
        self.assertFalse((self.root / 'temp').exists())

    def test_refuse_outside_target_and_invalid_port(self):
        with self.assertRaises(ValueError):
            trial.inspect_candidate(self.root / 'app', self.root)
        self.assertNotEqual(self.run_trial('--check-only', '--http-port', '0').returncode, 0)

    def test_check_rejects_unusable_profile_and_does_not_replace_it(self):
        profile = self.root / 'webview'
        profile.write_bytes(b'keep')
        self.assertNotEqual(self.run_trial('--check-only').returncode, 0)
        self.assertEqual(profile.read_bytes(), b'keep')

    def test_real_child_inherits_only_client_settings_and_temp_is_cleaned(self):
        source = self.root / 'Probe.cs'
        source.write_text('''using System;
using System.IO;
class Probe {
    static int Main() {
        var env = new Func<string,string>(Environment.GetEnvironmentVariable);
        var candidate = Directory.GetParent(Environment.CurrentDirectory).FullName;
        if (env("WEBVIEW2_USER_DATA_FOLDER") != Path.Combine(candidate, "webview")) return 2;
        var temp = env("TEMP");
        if (Directory.GetParent(temp).FullName != Path.Combine(candidate, "temp") || env("TMP") != temp) return 3;
        if (env("ZHIJIE_CONTROL_PLANE_URL") != "http://127.0.0.1:18080") return 4;
        foreach (var key in new[]{"ZHIJIE_VOICE_SIGNALING_KEY", "LIVEKIT_KEYS", "WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS", "UNRELATED_SECRET"}) {
            if (env(key) != null) return 5;
        }
        File.WriteAllText(Path.Combine(temp, "probe.txt"), "test");
        return 0;
    }
}''', encoding='utf-8')
        compiler = Path(os.environ['SystemRoot']) / 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
        subprocess.run([compiler, '/nologo', '/out:' + str(self.executable), source], cwd=self.root, check=True,
                       env=dict(os.environ, TEMP=str(self.root), TMP=str(self.root)), timeout=20)
        result = self.run_trial('--http-port', '18080', ZHIJIE_VOICE_SIGNALING_KEY='test-only', LIVEKIT_KEYS='test-only',
                                WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS='test-only', UNRELATED_SECRET='test-only')
        self.assertNotEqual(result.returncode, 0)
        self.record_digest()
        result = self.run_trial('--http-port', '18080', ZHIJIE_VOICE_SIGNALING_KEY='test-only', LIVEKIT_KEYS='test-only',
                                WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS='test-only', UNRELATED_SECRET='test-only')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.root / 'webview').is_dir())
        self.assertEqual(list((self.root / 'temp').iterdir()), [])


if __name__ == '__main__':
    unittest.main()
