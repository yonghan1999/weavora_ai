"""在已连接的虚拟 IP 间验证 TCP/UDP；不安装驱动或修改网络配置。"""

import argparse
from datetime import datetime, timezone
import hashlib
import hmac
import ipaddress
import json
import os
from pathlib import Path
import platform
import secrets
import socket
import struct
import threading
import time


SIZES = (64, 512, 1000)
MAX_PACKET = 1200
TOKEN_ENV = "ZHIJIE_NETWORK_PROBE_TOKEN"


def packet(key, sequence, payload, response=False):
    body = (b"ZJP1R" if response else b"ZJP1Q") + struct.pack("!I", sequence) + payload
    return body + hmac.digest(key, body, "sha256")


def decode(key, data, response=False):
    if not 41 <= len(data) <= MAX_PACKET:
        raise ValueError("packet length invalid")
    body, signature = data[:-32], data[-32:]
    if body[:5] != (b"ZJP1R" if response else b"ZJP1Q") or not hmac.compare_digest(
        signature, hmac.digest(key, body, "sha256")
    ):
        raise ValueError("packet authentication invalid")
    return struct.unpack("!I", body[5:9])[0], body[9:]


def receive_exact(stream, count, deadline):
    result = bytearray()
    while len(result) < count:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("TCP frame deadline")
        stream.settimeout(min(2, remaining))
        chunk = stream.recv(count - len(result))
        if not chunk:
            raise ValueError("TCP frame truncated")
        result.extend(chunk)
    return bytes(result)


def receive_frame(stream, deadline=None):
    deadline = deadline if deadline is not None else time.monotonic() + 2
    length = struct.unpack("!H", receive_exact(stream, 2, deadline))[0]
    if not 41 <= length <= MAX_PACKET:
        raise ValueError("TCP frame length invalid")
    return receive_exact(stream, length, deadline)


def send_frame(stream, data):
    stream.sendall(struct.pack("!H", len(data)) + data)


def transcripts():
    return {name: hashlib.sha256(f"ZJP1:{name}:".encode("ascii")) for name in ("tcp", "udp")}


def fingerprints(digests):
    return {name: digest.hexdigest() for name, digest in digests.items()}


def open_sockets(args):
    tcp = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        # 不共享监听地址；显式绑定用户指定的虚拟 IPv4，不监听所有接口。
        tcp.bind((args.bind, args.port if args.mode == "serve" else 0))
        udp.bind((args.bind, args.port if args.mode == "serve" else 0))
        return tcp, udp
    except BaseException:
        tcp.close()
        udp.close()
        raise


def serve(args, key, tcp, udp):
    tcp.listen(2)
    tcp.settimeout(0.2)
    udp.settimeout(0.2)
    deadline = time.monotonic() + args.timeout
    expected = args.count * len(SIZES)
    results = {"tcp": 0, "udp": 0, "rejected": 0}
    digests = transcripts()
    errors = []
    stop = threading.Event()

    def record(data, seen):
        sequence, payload = decode(key, data)
        if sequence >= expected or sequence in seen or len(payload) != SIZES[sequence % len(SIZES)]:
            raise ValueError("sequence or payload length invalid")
        return sequence, packet(key, sequence, payload, response=True)

    def tcp_worker():
        seen = set()
        while not stop.is_set() and time.monotonic() < deadline and len(seen) < expected:
            try:
                stream, address = tcp.accept()
            except socket.timeout:
                continue
            with stream:
                if address[0] != args.peer:
                    results["rejected"] += 1
                    continue
                stream.settimeout(min(2, max(0.01, deadline - time.monotonic())))
                try:
                    while len(seen) < expected and time.monotonic() < deadline:
                        request = receive_frame(stream, min(deadline, time.monotonic() + 2))
                        sequence, response = record(request, seen)
                        send_frame(stream, response)
                        digests["tcp"].update(request)
                        seen.add(sequence)
                        results["tcp"] += 1
                except (OSError, ValueError):
                    results["rejected"] += 1

    def udp_worker():
        seen = set()
        while not stop.is_set() and time.monotonic() < deadline and len(seen) < expected:
            try:
                data, address = udp.recvfrom(MAX_PACKET + 1)
            except socket.timeout:
                continue
            if address[0] != args.peer:
                results["rejected"] += 1
                continue
            try:
                sequence, response = record(data, seen)
                udp.sendto(response, address)
                digests["udp"].update(data)
                seen.add(sequence)
                results["udp"] += 1
            except (OSError, ValueError):
                results["rejected"] += 1

    def guarded(worker):
        try:
            worker()
        except OSError:
            errors.append("socket operation failed")
            stop.set()

    workers = [threading.Thread(target=guarded, args=(worker,)) for worker in (tcp_worker, udp_worker)]
    for worker in workers:
        worker.start()
    print(json.dumps({"ready": True, "bind": args.bind, "port": args.port}), flush=True)
    for worker in workers:
        worker.join()
    if errors or results["tcp"] != expected or results["udp"] != expected:
        raise ProbeFailure("server incomplete or timed out", results)
    results["transcriptSha256"] = fingerprints(digests)
    return results


class ProbeFailure(Exception):
    def __init__(self, message, results=None):
        super().__init__(message)
        self.results = results or {}


def run(args, key, tcp, udp):
    deadline = time.monotonic() + args.timeout
    samples = {"tcp": [], "udp": []}
    digests = transcripts()

    def remaining():
        value = deadline - time.monotonic()
        if value <= 0:
            raise TimeoutError("probe deadline")
        return min(2, value)

    tcp.settimeout(remaining())
    tcp.connect((args.peer, args.port))
    udp.connect((args.peer, args.port))
    for sequence in range(args.count * len(SIZES)):
        payload = secrets.token_bytes(SIZES[sequence % len(SIZES)])
        request = packet(key, sequence, payload)
        for protocol, stream in (("tcp", tcp), ("udp", udp)):
            stream.settimeout(remaining())
            started = time.monotonic()
            try:
                if protocol == "tcp":
                    send_frame(stream, request)
                    response = receive_frame(stream, min(deadline, time.monotonic() + 2))
                else:
                    stream.send(request)
                    response = stream.recv(MAX_PACKET + 1)
                actual_sequence, actual_payload = decode(key, response, response=True)
                if actual_sequence != sequence or actual_payload != payload:
                    raise ValueError("response content mismatch")
            except (OSError, ValueError) as exc:
                raise ProbeFailure(f"{protocol} exchange failed", {
                    name: len(values) for name, values in samples.items()
                }) from exc
            samples[protocol].append((time.monotonic() - started) * 1000)
            digests[protocol].update(request)
    results = {name: {"packets": len(values), "maxRttMs": round(max(values), 3),
                      "meanRttMs": round(sum(values) / len(values), 3)} for name, values in samples.items()}
    results["transcriptSha256"] = fingerprints(digests)
    return results


def configuration(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["serve", "run"])
    parser.add_argument("--bind", required=True)
    parser.add_argument("--peer", required=True)
    parser.add_argument("--cidr", required=True)
    parser.add_argument("--port", type=int, default=39871)
    parser.add_argument("--count", type=int, default=10)
    parser.add_argument("--timeout", type=float, default=60)
    parser.add_argument("--output-directory", required=True, type=Path)
    parser.add_argument("--allow-loopback", action="store_true",
                        help="仅测试工具自身；结果明确标记为回环测试")
    args = parser.parse_args(argv)
    try:
        network = ipaddress.IPv4Network(args.cidr, strict=True)
        local, peer = ipaddress.IPv4Address(args.bind), ipaddress.IPv4Address(args.peer)
        if network.prefixlen != 27 or local == peer:
            raise ValueError("requires distinct peer addresses in one /27")
        for address in (local, peer):
            if address not in network or address in (network.network_address, network.broadcast_address):
                raise ValueError("address must be a usable member of the activity /27")
            if not address.is_private or address.is_multicast or address.is_unspecified:
                raise ValueError("private activity IPv4 required")
        if (local.is_loopback or peer.is_loopback) and not (
                args.allow_loopback and local.is_loopback and peer.is_loopback):
            raise ValueError("loopback requires explicit test-only flag")
        if not 1024 <= args.port <= 65535 or not 1 <= args.count <= 1000 or not 0.2 <= args.timeout <= 3600:
            raise ValueError("port/count/timeout outside allowed bounds")
        token = os.environ.get(TOKEN_ENV, "")
        if not 32 <= len(token) <= 256 or not token.isascii() or not token.isprintable():
            raise ValueError("set a shared 32-256 character ASCII probe token in environment")
        # 目录必须新建，避免覆盖先前执行结果；凭据不写入报告。
        args.output_directory.mkdir(parents=True, exist_ok=False)
    except (ValueError, OSError) as exc:
        parser.error(str(exc))
    return args, token.encode("ascii")


def main():
    args, key = configuration()
    started = time.time()
    report = {
        "schemaVersion": 1, "mode": args.mode, "bind": args.bind, "peer": args.peer,
        "activityCidr": args.cidr, "port": args.port, "sizes": SIZES, "rounds": args.count,
        "system": platform.system(), "architecture": platform.machine(),
        "startedAt": datetime.fromtimestamp(started, timezone.utc).isoformat(),
        "scope": "loopback_tool_test" if args.allow_loopback else "explicit_ip_socket_probe",
        "acceptanceVerified": False,
        "note": "Requires separate interface/route, peer, Helper and game evidence; this tool cannot prove TUN or path selection",
        "toolSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
    }
    try:
        tcp, udp = open_sockets(args)
        with tcp, udp:
            report["results"] = (serve if args.mode == "serve" else run)(args, key, tcp, udp)
        report["status"] = "passed_socket_probe"
        code = 0
    except (OSError, ValueError, ProbeFailure) as exc:
        report.update(status="failed", error=(str(exc) if isinstance(exc, ProbeFailure) else "socket setup or IO failed"))
        if isinstance(exc, ProbeFailure):
            report["results"] = exc.results
        code = 1
    report["elapsedSeconds"] = round(time.time() - started, 3)
    path = args.output_directory / "result.json"
    with path.open("x", encoding="utf-8") as stream:
        json.dump(report, stream, indent=2)
        stream.write("\n")
    print(json.dumps({"status": report["status"], "result": str(path)}), flush=True)
    return code


if __name__ == "__main__":
    raise SystemExit(main())
