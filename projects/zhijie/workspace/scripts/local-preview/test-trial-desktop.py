import hashlib
from http.server import BaseHTTPRequestHandler, HTTPServer
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
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
        self.helper = app / 'zhijie-helper.exe'
        self.helper.write_bytes(b'fixture helper')
        self.manifest = self.root / 'manifest.json'
        self.record_digest()

    def record_digest(self):
        self.manifest.write_text(json.dumps({'productFiles': {path.name: hashlib.sha256(path.read_bytes()).hexdigest()
                                                             for path in (self.executable, self.helper)}}), encoding='utf-8')

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

    def test_backend_check_only_accepts_healthy_loopback_without_redirects_or_credentials(self):
        requests = []

        class Handler(BaseHTTPRequestHandler):
            def do_GET(handler):
                requests.append((handler.path, handler.headers.get('Authorization'), handler.headers.get('Cookie')))
                handler.send_response(status)
                if status == 302:
                    handler.send_header('Location', '/another-service')
                handler.end_headers()
                handler.wfile.write(body)

            def log_message(handler, *args):
                pass

        with HTTPServer(('127.0.0.1', 0), Handler) as server:
            worker = threading.Thread(target=server.serve_forever, daemon=True)
            worker.start()
            try:
                for status, body, expected in [(200, b'{"status":"UP"}', 0), (503, b'{"status":"UP"}', 1),
                                               (200, b'{"status":"DOWN"}', 1), (200, b'not json', 1),
                                               (200, b' ' * 8192 + b'{"status":"UP"}', 1), (302, b'', 1)]:
                    with self.subTest(status=status, body=body[:20]):
                        requests.clear()
                        result = self.run_trial('--check-backend', '--http-port', str(server.server_port),
                                                HTTP_PROXY='http://127.0.0.1:1', NO_PROXY='')
                        self.assertEqual(result.returncode, expected, result.stderr)
                        self.assertEqual(requests, [('/actuator/health', None, None)])
            finally:
                server.shutdown()
                worker.join(timeout=3)
        self.assertFalse((self.root / 'webview').exists())
        self.assertFalse((self.root / 'temp').exists())

    def test_check_covers_companions_and_exact_app_contents(self):
        self.helper.write_bytes(b'changed')
        self.assertNotEqual(self.run_trial('--check-only').returncode, 0)
        self.helper.unlink()
        self.assertNotEqual(self.run_trial('--check-only').returncode, 0)
        self.helper.write_bytes(b'fixture helper')
        (self.helper.parent / 'extra.dll').write_bytes(b'extra')
        self.assertNotEqual(self.run_trial('--check-only').returncode, 0)
        self.assertFalse((self.root / 'webview').exists())
        self.assertFalse((self.root / 'temp').exists())

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
