"""真实本机 TCP/UDP 验证运行器；不将回环结果写为 TUN 通过。"""

import importlib.util
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import tempfile
import time
import unittest


TOOL = Path(__file__).with_name("network-peer-probe.py")
spec = importlib.util.spec_from_file_location("network_probe", TOOL)
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class PeerProbeTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="zhijie-peer-probe-")
        self.root = Path(self.directory.name)
        self.key = secrets.token_hex(24)
        self.env = {**os.environ, probe.TOKEN_ENV: self.key}

    def tearDown(self):
        self.directory.cleanup()

    def command(self, mode, port, output, timeout="4"):
        local, peer = ("127.0.0.1", "127.0.0.2") if mode == "serve" else ("127.0.0.2", "127.0.0.1")
        return [sys.executable, str(TOOL), mode, "--bind", local, "--peer", peer,
                "--cidr", "127.0.0.0/27", "--allow-loopback", "--port", str(port),
                "--count", "3", "--timeout", timeout, "--output-directory", str(output)]

    def free_port(self):
        with socket.socket() as stream:
            stream.bind(("127.0.0.1", 0))
            return stream.getsockname()[1]

    def exchange(self, wrong_key=False):
        port = self.free_port()
        server_dir, client_dir = self.root / "server", self.root / "client"
        with subprocess.Popen(self.command("serve", port, server_dir, "1.5" if wrong_key else "6"),
                              env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True) as server:
            try:
                ready = json.loads(server.stdout.readline())
                self.assertTrue(ready["ready"])
                env = {**self.env, probe.TOKEN_ENV: secrets.token_hex(24)} if wrong_key else self.env
                client = subprocess.run(self.command("run", port, client_dir), env=env,
                                        capture_output=True, text=True, timeout=8)
                output, error = server.communicate(timeout=8)
            finally:
                if server.poll() is None:
                    server.kill()
                    server.communicate()
        server_report = json.loads((server_dir / "result.json").read_text())
        client_report = json.loads((client_dir / "result.json").read_text())
        for report in (server_report, client_report):
            self.assertFalse(report["acceptanceVerified"])
            self.assertEqual(report["scope"], "loopback_tool_test")
            self.assertNotIn(self.key, json.dumps(report))
        self.assertNotIn(self.key, client.stdout + client.stderr + output + error)
        return server.returncode, client.returncode, server_report, client_report

    def test_real_tcp_udp_round_trip_and_binary_integrity(self):
        server_code, client_code, server, client = self.exchange()
        self.assertEqual((server_code, client_code), (0, 0))
        self.assertEqual(server["results"]["tcp"], 9)
        self.assertEqual(server["results"]["udp"], 9)
        self.assertEqual(client["results"]["tcp"]["packets"], 9)
        self.assertEqual(client["results"]["udp"]["packets"], 9)

    def test_transcripts_match_only_the_corresponding_protocol_and_run(self):
        def paired_transcripts():
            server_code, client_code, server, client = self.exchange()
            self.assertEqual((server_code, client_code), (0, 0))
            fingerprints = server["results"]["transcriptSha256"]
            self.assertEqual(fingerprints, client["results"]["transcriptSha256"])
            self.assertEqual(set(fingerprints), {"tcp", "udp"})
            for digest in fingerprints.values():
                self.assertRegex(digest, r"^[0-9a-f]{64}$")
            self.assertNotEqual(fingerprints["tcp"], fingerprints["udp"])
            return fingerprints

        first = paired_transcripts()
        self.root = self.root / "second-run"
        second = paired_transcripts()
        for protocol in first:
            self.assertNotEqual(first[protocol], second[protocol])

    def test_wrong_shared_key_fails_and_preserves_reports(self):
        server_code, client_code, server, client = self.exchange(wrong_key=True)
        self.assertNotEqual(server_code, 0)
        self.assertNotEqual(client_code, 0)
        self.assertEqual(server["status"], "failed")
        self.assertEqual(client["status"], "failed")
        for report in (server, client):
            self.assertNotIn("transcriptSha256", report.get("results", {}))

    def test_server_rejects_unexpected_udp_source_without_reflection_then_completes(self):
        port = self.free_port()
        server_dir = self.root / "server-rejection"
        with subprocess.Popen(self.command("serve", port, server_dir, "6"), env=self.env,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True) as server:
            try:
                self.assertTrue(json.loads(server.stdout.readline())["ready"])
                with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as unexpected:
                    unexpected.bind(("127.0.0.3", 0))
                    unexpected.settimeout(0.3)
                    unexpected.sendto(probe.packet(self.key.encode(), 0, bytes(64)), ("127.0.0.1", port))
                    with self.assertRaises(socket.timeout):
                        unexpected.recvfrom(1200)
                client = subprocess.run(self.command("run", port, self.root / "client-rejection"),
                                        env=self.env, capture_output=True, timeout=8)
                server.communicate(timeout=8)
                self.assertEqual(client.returncode, 0)
                self.assertEqual(server.returncode, 0)
            finally:
                if server.poll() is None:
                    server.kill()
                    server.communicate()
        report = json.loads((server_dir / "result.json").read_text())
        self.assertGreaterEqual(report["results"]["rejected"], 1)

    def test_idle_server_deadline_is_failure(self):
        output = self.root / "idle-server"
        result = subprocess.run(self.command("serve", self.free_port(), output, "0.3"),
                                env=self.env, capture_output=True, timeout=5)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(json.loads((output / "result.json").read_text())["status"], "failed")

    def test_unreachable_peer_fails_and_existing_output_is_preserved(self):
        output = self.root / "failed"
        result = subprocess.run(self.command("run", self.free_port(), output, "0.5"),
                                env=self.env, capture_output=True, timeout=5)
        self.assertNotEqual(result.returncode, 0)
        original = (output / "result.json").read_bytes()
        retry = subprocess.run(self.command("run", self.free_port(), output),
                               env=self.env, capture_output=True, timeout=5)
        self.assertNotEqual(retry.returncode, 0)
        self.assertEqual((output / "result.json").read_bytes(), original)

    def test_tampered_packet_response_reflection_and_invalid_frame_rejected(self):
        request = probe.packet(b"test key", 42, b"payload")
        self.assertEqual(probe.decode(b"test key", request), (42, b"payload"))
        with self.assertRaises(ValueError):
            probe.decode(b"test key", request, response=True)
        with self.assertRaises(ValueError):
            probe.decode(b"test key", request[:-1] + bytes([request[-1] ^ 1]))
        first, second = socket.socketpair()
        with first, second:
            first.sendall(b"\xff\xff")
            with self.assertRaises(ValueError):
                probe.receive_frame(second)
        first, second = socket.socketpair()
        with first, second:
            first.sendall(b"\x00")
            with self.assertRaises(TimeoutError):
                probe.receive_frame(second, time.monotonic() + 0.05)

    def test_loopback_requires_explicit_flag_and_peer_must_be_in_activity(self):
        command = self.command("run", self.free_port(), self.root / "invalid")
        command.remove("--allow-loopback")
        result = subprocess.run(command, env=self.env, capture_output=True, timeout=5)
        self.assertNotEqual(result.returncode, 0)
        command = self.command("run", self.free_port(), self.root / "outside")
        command[command.index("--peer") + 1] = "192.168.1.2"
        result = subprocess.run(command, env=self.env, capture_output=True, timeout=5)
        self.assertNotEqual(result.returncode, 0)


if __name__ == "__main__":
    unittest.main()
