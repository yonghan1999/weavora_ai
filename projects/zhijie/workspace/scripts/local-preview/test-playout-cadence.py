"""隔离的静音播放时钟对照；临时凭据只保留在进程内，不访问试用账号。"""
import argparse
import base64
import hashlib
import hmac
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import time
import urllib.error
import urllib.request
import uuid


def summarize(log, mode):
    rows = {"SOURCE_CADENCE": {}, "PLAYOUT_CADENCE": {}}
    for kind, receiver, speaker, frames, samples, nonzero, peak, gap in re.findall(
            rf"^(SOURCE_CADENCE|PLAYOUT_CADENCE) mode={mode} receiver=(\d+) speaker=cadence-(\d+):1 observation_ms=15000 "
            r"frames=(\d+) samples=(\d+) nonzero=(\d+) peak_abs=(\d+) max_gap_us=(\d+)\s*$", log, re.MULTILINE):
        pair = (int(receiver), int(speaker))
        if pair in rows[kind]:
            raise ValueError("duplicate cadence observation")
        rows[kind][pair] = tuple(map(int, (frames, samples, nonzero, peak, gap)))
    result = {}
    for kind, label in [("SOURCE_CADENCE", "source"), ("PLAYOUT_CADENCE", "playout")]:
        expected = {(receiver, speaker) for receiver in range(12) for speaker in range(12)
                    if (receiver == speaker) == (label == "source")}
        if set(rows[kind]) != expected:
            raise ValueError(f"incomplete {label} cadence observations")
        values = rows[kind].values()
        result[label] = dict(count=len(values), minFrames=min(x[0] for x in values), maxFrames=max(x[0] for x in values),
                             minSamples=min(x[1] for x in values), maxSamples=max(x[1] for x in values),
                             maxNonzeroFrames=max(x[2] for x in values), peakAbs=max(x[3] for x in values),
                             maxGapUs=max(x[4] for x in values))
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--media-address", required=True, type=ipaddress.IPv4Address)
    parser.add_argument("--server", type=Path)
    parser.add_argument("--mode", choices=("synthetic", "platform", "both"), default="synthetic")
    args = parser.parse_args()
    media_address = str(args.media_address)
    workspace = Path(__file__).resolve().parents[2]
    runtime = workspace / "target" / ("playout-cadence-" + uuid.uuid4().hex)
    runtime.mkdir()
    server_path = (args.server or workspace / "target/local-preview/livekit/bin/livekit-server.exe").resolve(strict=True)
    build = subprocess.run([
        "cargo", "test", "--offline", "--locked", "-p", "zhijie-voice", "--test",
        "playout_cadence", "--no-run", "--message-format=json",
    ], cwd=workspace, capture_output=True, timeout=120, check=True)
    executable = None
    for line in build.stdout.decode("utf-8-sig").splitlines():
        artifact = json.loads(line)
        if artifact.get("executable") and artifact.get("target", {}).get("name") == "playout_cadence":
            executable = artifact["executable"]
    if executable is None:
        raise RuntimeError("diagnostic test executable not found")
    # 使用专用回环端口，不改原 SFU 配置或日志。
    for kind, port in [(socket.SOCK_STREAM, 17880), (socket.SOCK_DGRAM, 17882)]:
        with socket.socket(socket.AF_INET, kind) as probe:
            probe.bind(("127.0.0.1" if kind == socket.SOCK_STREAM else media_address, port))
    config = runtime / "server.yaml"
    config.write_text(f"""port: 17880
bind_addresses: [127.0.0.1]
logging:
  level: warn
rtc:
  node_ip: {media_address}
  tcp_port: 0
  udp_port: 17882
  use_external_ip: false
  enable_loopback_candidate: true
  ips:
    includes: [{media_address}/32]
""", encoding="utf-8")
    key = "cadence-" + uuid.uuid4().hex
    secret = secrets.token_urlsafe(36).encode()
    server_env = os.environ.copy()
    server_env["LIVEKIT_KEYS"] = key + ": " + secret.decode()

    def token(room, identity):
        def encode(value):
            return base64.urlsafe_b64encode(value).rstrip(b"=")
        now = int(time.time())
        claims = {"iss": key, "sub": identity, "iat": now, "nbf": now - 5,
                  "exp": now + 120, "video": {"room": room, "roomJoin": True,
                  "canPublish": True, "canSubscribe": True, "canPublishData": False,
                  "canPublishSources": ["microphone"]}}
        body = encode(b'{"alg":"HS256","typ":"JWT"}') + b"." + encode(json.dumps(claims).encode())
        return (body + b"." + encode(hmac.new(secret, body, hashlib.sha256).digest())).decode()

    results = []
    server = None
    with (runtime / "server.log").open("wb") as server_log:
        try:
            server = subprocess.Popen([str(server_path), "--config", str(config)],
                                      env=server_env, stdout=server_log, stderr=subprocess.STDOUT,
                                      creationflags=subprocess.CREATE_NO_WINDOW)
            for _ in range(80):
                if server.poll() is not None:
                    raise RuntimeError("isolated SFU exited; inspect server.log")
                try:
                    with urllib.request.urlopen("http://127.0.0.1:17880", timeout=0.5):
                        break
                except (urllib.error.URLError, TimeoutError):
                    time.sleep(0.25)
            else:
                raise RuntimeError("isolated SFU readiness timeout")
            modes = ["synthetic", "platform"] if args.mode == "both" else [args.mode]
            for mode in modes:
                env = os.environ.copy()
                env["ZHIJIE_CADENCE_MODE"] = mode
                env["ZHIJIE_TEST_VOICE_URL"] = "ws://127.0.0.1:17880"
                room = "silent-" + uuid.uuid4().hex
                for index in range(12):
                    env[f"ZHIJIE_TEST_VOICE_TOKEN_{index}"] = token(room, f"cadence-{index}:1")
                log = runtime / (mode + ".log")
                with log.open("wb") as output:
                    result = subprocess.run([
                        executable, "--ignored", "--exact",
                        "silent_full_room_playout_cadence", "--nocapture",
                    ], cwd=workspace, env=env, stdout=output, stderr=subprocess.STDOUT,
                        timeout=65, creationflags=subprocess.CREATE_NO_WINDOW)
                summary = {"mode": mode, "exitCode": result.returncode}
                if not result.returncode:
                    summary.update(summarize(log.read_text(encoding="utf-8", errors="replace"), mode))
                results.append(summary)
                print(json.dumps(summary), flush=True)
                if result.returncode:
                    break
        finally:
            if server is not None and server.poll() is None:
                server.terminate()
                try:
                    server.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    server.kill()
                    server.wait(timeout=5)
    summary = {"scope": "same-host silent cadence only; no network helper load or microphone",
               "results": results, "serverStopped": server is not None and server.poll() is not None}
    (runtime / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print("diagnostic directory:", runtime.relative_to(workspace), flush=True)
    return 0 if len(results) == len(modes) and all(x["exitCode"] == 0 for x in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
