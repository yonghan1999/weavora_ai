import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
import subprocess
from datetime import datetime, timedelta, timezone

spec = importlib.util.spec_from_file_location("render_voice", Path(__file__).with_name("render-voice-signaling.py"))
renderer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(renderer)


class VoiceConfigTest(unittest.TestCase):
    def setUp(self):
        self.template = Path(__file__).with_name("voice-signaling.nginx.conf.template").read_text(encoding="utf-8-sig")
        self.environment = {
            "ZHIJIE_VOICE_PUBLIC_HOST": "voice.example.test",
            "ZHIJIE_VOICE_TLS_CERT": "/etc/zhijie/tls/server cert.pem",
            "ZHIJIE_VOICE_TLS_KEY": "/etc/zhijie/tls/server key.pem",
            "ZHIJIE_VOICE_SIGNALING_KEY": "test-only-" + "x" * 32,
        }

    def test_runtime_variables_and_authority_boundary_are_preserved(self):
        output = renderer.render(self.template, self.environment)
        for variable in ["$http_upgrade", "$http_authorization", "$arg_access_token", "$zhijie_voice_connection"]:
            self.assertIn(variable, output)
        self.assertIn('ssl_certificate "/etc/zhijie/tls/server cert.pem";', output)
        self.assertIn("auth_request /_voice_authorize;", output)
        self.assertIn("location / { return 404; }", output)
        self.assertNotIn("${ZHIJIE_", output)

    def test_injection_missing_and_unknown_values_are_rejected(self):
        for name, value in [
            ("ZHIJIE_VOICE_PUBLIC_HOST", "voice.test; return 200"),
            ("ZHIJIE_VOICE_TLS_CERT", "/tmp/$hostname.pem"),
            ("ZHIJIE_VOICE_TLS_KEY", 'C:/tls/"key.pem'),
            ("ZHIJIE_VOICE_TLS_KEY", "relative/key.pem"),
            ("ZHIJIE_VOICE_SIGNALING_KEY", "short"),
            ("ZHIJIE_VOICE_SIGNALING_KEY", "x" * 32 + "\n"),
        ]:
            with self.subTest(name=name, value=value):
                with self.assertRaises(ValueError):
                    renderer.render(self.template, self.environment | {name: value})
        with self.assertRaises(ValueError):
            renderer.render(self.template + "${UNEXPECTED}", self.environment)
        with self.assertRaises(ValueError):
            renderer.render(self.template, {})

    def test_output_is_private_and_existing_directory_is_never_overwritten(self):
        root = Path(__file__).resolve().parents[1] / "target"
        root.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="voice-render-test-", dir=root) as temporary:
            directory = Path(temporary) / "private"
            path = renderer.write_private_directory(directory, "test-only configuration\n")
            self.assertEqual(path.read_text(encoding="utf-8"), "test-only configuration\n")
            if os.name != "nt":
                self.assertEqual(directory.stat().st_mode & 0o777, 0o700)
                self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            else:
                script = "param([string]$Target); $acl=Get-Acl -LiteralPath $Target; if (!$acl.AreAccessRulesProtected) { exit 2 }; $current=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value; foreach ($rule in $acl.Access) { $sid=$rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value; if ($sid -notin @($current,'S-1-5-18')) { exit 3 } }"
                check = subprocess.run(["powershell", "-NoProfile", "-Command", "& { " + script + " }", str(directory)], capture_output=True)
                self.assertEqual(check.returncode, 0, "output ACL must exclude unrelated identities")
            with self.assertRaises(FileExistsError):
                renderer.write_private_directory(directory, "overwrite")
            self.assertEqual(path.read_text(encoding="utf-8"), "test-only configuration\n")

    @unittest.skipUnless(os.environ.get("ZHIJIE_TEST_NGINX"), "requires explicit local nginx and cryptography test dependency")
    def test_rendered_tls_fragment_passes_real_nginx_syntax_check(self):
        from cryptography import x509
        from cryptography.x509.oid import NameOID
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import rsa
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory(prefix="voice-nginx-render-", dir=root / "target") as temporary:
            directory = Path(temporary) / "private"
            certificate_path, key_path = directory / "server cert.pem", directory / "server key.pem"
            environment = self.environment | {"ZHIJIE_VOICE_TLS_CERT": str(certificate_path), "ZHIJIE_VOICE_TLS_KEY": str(key_path)}
            fragment = renderer.write_private_directory(directory, renderer.render(self.template, environment))
            key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
            subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "voice.example.test")])
            now = datetime.now(timezone.utc)
            certificate = x509.CertificateBuilder().subject_name(subject).issuer_name(subject).public_key(key.public_key()).serial_number(x509.random_serial_number()).not_valid_before(now - timedelta(minutes=1)).not_valid_after(now + timedelta(hours=1)).sign(key, hashes.SHA256())
            key_path.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
            certificate_path.write_bytes(certificate.public_bytes(serialization.Encoding.PEM))
            (directory / "logs").mkdir()
            (directory / "temp").mkdir()
            (directory / "nginx.conf").write_text('error_log stderr warn;\nevents { worker_connections 64; }\nhttp { include "' + fragment.as_posix() + '"; }\n', encoding="utf-8")
            result = subprocess.run([os.environ["ZHIJIE_TEST_NGINX"], "-t", "-p", directory.as_posix() + "/", "-c", "nginx.conf"], capture_output=True, timeout=15)
            self.assertEqual(result.returncode, 0, result.stderr.decode("utf-8", errors="replace").replace(environment["ZHIJIE_VOICE_SIGNALING_KEY"], "[REDACTED]"))



if __name__ == "__main__":
    unittest.main()
