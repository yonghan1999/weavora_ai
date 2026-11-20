"""隔离的回调与队列诊断；临时凭据只保留在进程内，不访问试用账号。"""
import argparse
import base64
import ctypes
from ctypes import wintypes
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


class ResourceSampler:
    def __init__(self, processes):
        self.processes = processes
        self.kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        self.kernel.GetSystemTimes.argtypes = [ctypes.POINTER(wintypes.FILETIME)] * 3
        self.kernel.GetSystemTimes.restype = wintypes.BOOL
        self.kernel.GetProcessTimes.argtypes = [wintypes.HANDLE] + [ctypes.POINTER(wintypes.FILETIME)] * 4
        self.kernel.GetProcessTimes.restype = wintypes.BOOL
        self.previous = None
        self.started = time.monotonic()

    @staticmethod
    def ticks(value):
        return (value.dwHighDateTime << 32) | value.dwLowDateTime

    def sample(self):
        idle, kernel, user = (wintypes.FILETIME() for _ in range(3))
        if not self.kernel.GetSystemTimes(ctypes.byref(idle), ctypes.byref(kernel), ctypes.byref(user)):
            raise ctypes.WinError(ctypes.get_last_error())
        total = self.ticks(kernel) + self.ticks(user)
        idle_ticks = self.ticks(idle)
        current = {"time": time.monotonic(), "total": total, "idle": idle_ticks}
        for name, process in self.processes.items():
            created, exited, system, application = (wintypes.FILETIME() for _ in range(4))
            if not self.kernel.GetProcessTimes(process._handle, ctypes.byref(created), ctypes.byref(exited),
                                               ctypes.byref(system), ctypes.byref(application)):
                raise ctypes.WinError(ctypes.get_last_error())
            current[name] = self.ticks(system) + self.ticks(application)
        row = {"elapsedSeconds": current["time"] - self.started, "unixSeconds": time.time()}
        if self.previous is not None:
            delta = current["total"] - self.previous["total"]
            row["hostBusyPercent"] = 100 * (1 - (current["idle"] - self.previous["idle"]) / delta) if delta else 0
            elapsed = current["time"] - self.previous["time"]
            for name in self.processes:
                row[name + "CpuCores"] = (current[name] - self.previous[name]) / 10_000_000 / elapsed
        self.previous = current
        return row


def summarize(log, mode, signal="silence", windows=1):
    starts = re.findall(rf"^CADENCE_STARTED mode={mode} unix_ms=(\d+) observation_ms={windows * 15000}\s*$",
                        log, re.MULTILINE)
    if len(starts) != 1:
        raise ValueError("missing or duplicate measurement start")
    rows = {"SOURCE_CADENCE": {}, "PLAYOUT_CADENCE": {}}
    for kind, receiver, speaker, frames, samples, nonzero, peak, gap in re.findall(
            rf"^(SOURCE_CADENCE|PLAYOUT_CADENCE) mode={mode} receiver=(\d+) speaker=cadence-(\d+):1 observation_ms={windows * 15000} "
            r"frames=(\d+) samples=(\d+) nonzero=(\d+) peak_abs=(\d+) max_gap_us=(\d+)\s*$", log, re.MULTILINE):
        pair = (int(receiver), int(speaker))
        if pair in rows[kind]:
            raise ValueError("duplicate cadence observation")
        rows[kind][pair] = tuple(map(int, (frames, samples, nonzero, peak, gap)))
    result = {"measurementStartedUnixMs": int(starts[0])}
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
    queues = {}
    for receiver, speaker, measured, received, samples, dropped, consumed, initial_frames, initial_dropped in re.findall(
            rf"^AUDIO_QUEUE mode={mode} receiver=(\d+) speaker=cadence-(\d+):1 measured_ms=(\d+) "
            r"native_frames=(\d+) native_samples=(\d+) dropped=(\d+) consumed=(\d+) "
            r"initial_native_frames=(\d+) initial_dropped=(\d+)\s*$", log, re.MULTILINE):
        pair = (int(receiver), int(speaker))
        if pair in queues:
            raise ValueError("duplicate queue observation")
        queues[pair] = dict(zip(("measuredMs", "nativeFrames", "nativeSamples", "dropped", "consumed",
                                "initialNativeFrames", "initialDropped"),
                               map(int, (measured, received, samples, dropped, consumed, initial_frames, initial_dropped))))
    if set(queues) != {(receiver, speaker) for receiver in range(12) for speaker in range(12)}:
        raise ValueError("incomplete callback queue observations")
    for diagonal, label in [(True, "source"), (False, "playout")]:
        observations = [value for (receiver, speaker), value in queues.items() if (receiver == speaker) == diagonal]
        result[label]["minNativeFrames"] = min(value["nativeFrames"] for value in observations)
        result[label]["maxNativeFrames"] = max(value["nativeFrames"] for value in observations)
        result[label]["maxDroppedFrames"] = max(value["dropped"] for value in observations)
        result[label]["totalDroppedFrames"] = sum(value["dropped"] for value in observations)
        result[label]["maxInitialDroppedFrames"] = max(value["initialDropped"] for value in observations)
        for (receiver, speaker), value in queues.items():
            if (receiver == speaker) == diagonal and value["consumed"] != rows[
                    "SOURCE_CADENCE" if diagonal else "PLAYOUT_CADENCE"][(receiver, speaker)][0]:
                raise ValueError("queue and cadence consumption disagree")
    signals = {}
    for receiver, speaker, active, frequency in re.findall(
            rf"^PCM_SIGNAL mode={mode} signal={signal} receiver=(\d+) speaker=cadence-(\d+):1 "
            r"active_frames=(\d+) frequency_hz=([\d.]+)\s*$", log, re.MULTILINE):
        pair = (int(receiver), int(speaker))
        if pair in signals:
            raise ValueError("duplicate signal observation")
        signals[pair] = (int(active), float(frequency))
    if set(signals) != set(queues):
        raise ValueError("incomplete signal observations")
    intervals = {}
    for receiver, speaker, callbacks, active, native, dropped in re.findall(
            rf"^CADENCE_WINDOWS mode={mode} receiver=(\d+) speaker=cadence-(\d+):1 "
            r"callbacks=(\[[\d, ]+\]) active=(\[[\d, ]+\]) native=(\[[\d, ]+\]) dropped=(\[[\d, ]+\])\s*$",
            log, re.MULTILINE):
        pair = (int(receiver), int(speaker))
        if pair in intervals:
            raise ValueError("duplicate window observation")
        intervals[pair] = [json.loads(value) for value in (callbacks, active, native, dropped)]
        if any(len(value) != windows for value in intervals[pair]):
            raise ValueError("incorrect observation window count")
        if sum(intervals[pair][0]) != queues[pair]["consumed"] or sum(intervals[pair][1]) != signals[pair][0]:
            raise ValueError("window and total counts disagree")
        if sum(intervals[pair][2]) != queues[pair]["nativeFrames"] or sum(intervals[pair][3]) != queues[pair]["dropped"]:
            raise ValueError("native window and total counts disagree")
    if set(intervals) != set(queues):
        raise ValueError("incomplete window observations")
    for diagonal, label in [(True, "source"), (False, "playout")]:
        values = [value[0] for (receiver, speaker), value in signals.items() if (receiver == speaker) == diagonal]
        result[label]["minActiveFrames"] = min(values)
        result[label]["maxActiveFrames"] = max(values)
        result[label]["minWindowActiveFrames"] = min(
            min(value[1]) for (receiver, speaker), value in intervals.items() if (receiver == speaker) == diagonal)
        result[label]["minWindowNativeFrames"] = min(
            min(value[2]) for (receiver, speaker), value in intervals.items() if (receiver == speaker) == diagonal)
        if signal == "tone":
            result[label]["maxFrequencyErrorHz"] = max(
                abs(value[1] - (300 + speaker * 100)) for (receiver, speaker), value in signals.items()
                if (receiver == speaker) == diagonal)
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--media-address", required=True, type=ipaddress.IPv4Address)
    parser.add_argument("--server", type=Path)
    parser.add_argument("--mode", choices=("synthetic", "platform", "both"), default="synthetic")
    parser.add_argument("--signal", choices=("silence", "tone"), default="silence")
    parser.add_argument("--windows", type=int, choices=range(1, 8), default=1)
    args = parser.parse_args()
    if args.signal == "tone" and args.mode != "synthetic":
        parser.error("controlled tones require synthetic mode; never send tones to host output")
    media_address = str(args.media_address)
    workspace = Path(__file__).resolve().parents[2]
    runtime = workspace / "target" / ("playout-cadence-" + uuid.uuid4().hex)
    runtime.mkdir()
    server_path = (args.server or workspace / "target/local-preview/livekit/bin/livekit-server.exe").resolve(strict=True)
    build = subprocess.run([
        "cargo", "test", "--offline", "--locked", "-p", "zhijie-voice", "--test",
        "playout_cadence", "--no-run", "--message-format=json",
    ], cwd=workspace, capture_output=True, timeout=120)
    (runtime / "build.jsonl").write_bytes(build.stdout)
    (runtime / "build.log").write_bytes(build.stderr)
    if build.returncode:
        print("failed build directory:", runtime.relative_to(workspace), flush=True)
        raise RuntimeError("cadence diagnostic build failed; inspect build logs")
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
                  "exp": now + args.windows * 15 + 120, "video": {"room": room, "roomJoin": True,
                  "canPublish": True, "canSubscribe": True, "canPublishData": False,
                  "canPublishSources": ["microphone"]}}
        body = encode(b'{"alg":"HS256","typ":"JWT"}') + b"." + encode(json.dumps(claims).encode())
        return (body + b"." + encode(hmac.new(secret, body, hashlib.sha256).digest())).decode()

    results = []
    credentials = [key, secret.decode()]
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
                env["ZHIJIE_CADENCE_SIGNAL"] = args.signal
                env["ZHIJIE_CADENCE_WINDOWS"] = str(args.windows)
                env["ZHIJIE_TEST_VOICE_URL"] = "ws://127.0.0.1:17880"
                room = "silent-" + uuid.uuid4().hex
                for index in range(12):
                    env[f"ZHIJIE_TEST_VOICE_TOKEN_{index}"] = token(room, f"cadence-{index}:1")
                    credentials.append(env[f"ZHIJIE_TEST_VOICE_TOKEN_{index}"])
                log = runtime / (mode + ".log")
                process = None
                resources = []
                with log.open("wb") as output:
                    try:
                        process = subprocess.Popen([
                            executable, "--ignored", "--exact",
                            "silent_full_room_playout_cadence", "--nocapture",
                        ], cwd=workspace, env=env, stdout=output, stderr=subprocess.STDOUT,
                            creationflags=subprocess.CREATE_NO_WINDOW)
                        sampler = ResourceSampler({"client": process, "sfu": server})
                        expires = time.monotonic() + args.windows * 15 + 50
                        resources.append(sampler.sample())
                        while process.poll() is None:
                            if time.monotonic() >= expires:
                                raise TimeoutError("cadence diagnostic exceeded observation and setup deadline")
                            time.sleep(0.5)
                            resources.append(sampler.sample())
                    finally:
                        if process is not None and process.poll() is None:
                            process.kill()
                            process.wait(timeout=5)
                        (runtime / (mode + "-resources.json")).write_text(
                            json.dumps(resources, indent=2) + "\n", encoding="utf-8")
                summary = {"mode": mode, "signal": args.signal, "windows": args.windows, "exitCode": process.returncode}
                if not process.returncode:
                    summary.update(summarize(log.read_text(encoding="utf-8", errors="replace"), mode, args.signal, args.windows))
                results.append(summary)
                print(json.dumps(summary), flush=True)
                if process.returncode:
                    break
        finally:
            if server is not None and server.poll() is None:
                server.terminate()
                try:
                    server.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    server.kill()
                    server.wait(timeout=5)
            for path in runtime.iterdir():
                if path.is_file() and path.suffix in (".log", ".json", ".jsonl"):
                    content = path.read_text(encoding="utf-8", errors="replace")
                    if any(credential in content for credential in credentials):
                        raise RuntimeError("temporary credential appeared in diagnostic output; do not publish evidence")
    summary = {"scope": "same-host callback and queue diagnostic only; no network helper load or microphone; not quality acceptance",
               "results": results, "serverStopped": server is not None and server.poll() is not None}
    (runtime / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print("diagnostic directory:", runtime.relative_to(workspace), flush=True)
    return 0 if len(results) == len(modes) and all(x["exitCode"] == 0 for x in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
