import argparse
import json
import math
import os
from datetime import datetime, timezone
from pathlib import Path
import sys
import time

import psutil


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def write_json(path, value):
    with path.open("x", encoding="utf-8") as output:
        json.dump(value, output, ensure_ascii=False, allow_nan=False, indent=2)
        output.write("\n")


def snapshot(processes, interface, start):
    counters = psutil.net_io_counters(pernic=True, nowrap=False).get(interface)
    if counters is None:
        raise ValueError("selected interface not found")
    readings = []
    for process in processes:
        with process.oneshot():
            cpu = process.cpu_times()
            readings.append({"pid": process.pid, "created": process.create_time(),
                             "cpuSeconds": cpu.user + cpu.system, "rssBytes": process.memory_info().rss,
                             "openHandlesOrFds": process.num_handles() if os.name == "nt" else process.num_fds()})
        if not process.is_running() or process.status() == psutil.STATUS_ZOMBIE:
            raise ValueError("selected process exited or changed")
    return {"timeUtc": utc_now(), "elapsedSeconds": time.monotonic() - start, "processes": readings,
            "bytesSent": counters.bytes_sent, "bytesReceived": counters.bytes_recv}


def interval_metrics(previous, current):
    seconds = current["elapsedSeconds"] - previous["elapsedSeconds"]
    if seconds <= 0:
        raise ValueError("nonpositive sampling interval")
    before, after = previous["processes"], current["processes"]
    if [(item["pid"], item["created"]) for item in before] != [(item["pid"], item["created"]) for item in after]:
        raise ValueError("selected process identity changed")
    cpu = [new["cpuSeconds"] - old["cpuSeconds"] for old, new in zip(before, after)]
    sent = current["bytesSent"] - previous["bytesSent"]
    received = current["bytesReceived"] - previous["bytesReceived"]
    if min(*cpu, sent, received) < 0:
        raise ValueError("resource counter decreased")
    return {"cpuPeakPercent": sum(cpu) * 100 / seconds,
            "upstreamPeakKbitPerSecond": sent * 8 / 1000 / seconds,
            "downstreamPeakKbitPerSecond": received * 8 / 1000 / seconds}


def tree_memory(root, excluded):
    for attempt in range(3):
        try:
            if not root.is_running():
                raise psutil.NoSuchProcess(root.pid)
            members = [root, *root.children(recursive=True)]
            if any(process.pid in excluded for process in members):
                raise ValueError("memory tree overlaps selected PIDs")
            rows = [{"pid": process.pid, "created": process.create_time(), "rssBytes": process.memory_info().rss}
                    for process in members]
            if root.is_running() and all(process.is_running() for process in members):
                return rows
        except psutil.NoSuchProcess:
            if attempt == 2 or not root.is_running():
                raise
    raise ValueError("memory tree did not stabilize during sample")


def collect(pids, interface, duration, interval, directory, stop_file=None, memory_tree_pid=None):
    if not pids or len(set(pids)) != len(pids) or any(pid <= 0 for pid in pids):
        raise ValueError("provide distinct positive PIDs")
    if not (math.isfinite(interval) and math.isfinite(duration) and 0 < interval <= duration):
        raise ValueError("require 0 < interval <= duration")
    if stop_file is not None and stop_file.exists():
        raise ValueError("stop file already exists")
    if memory_tree_pid is not None and (memory_tree_pid <= 0 or memory_tree_pid in pids):
        raise ValueError("memory tree requires a distinct positive root PID")
    directory.mkdir(exist_ok=False)
    result = {"schemaVersion": 1, "status": "failed", "acceptance": "unverified", "pids": pids,
              "interface": interface, "platform": sys.platform, "logicalCpuCount": psutil.cpu_count(),
              "hostMemoryBytes": psutil.virtual_memory().total, "intervalSeconds": interval,
              "requestedDurationSeconds": duration, "startedAt": utc_now(), "sampleCount": 0,
              "handleUnit": "handles" if os.name == "nt" else "file-descriptors"}
    try:
        processes = [psutil.Process(pid) for pid in pids]
        tree = None
        if memory_tree_pid is not None:
            result["memoryTreeRootPid"] = memory_tree_pid
            tree = psutil.Process(memory_tree_pid)
        start = time.monotonic()
        previous = None
        peaks = {}
        with (directory / "samples.jsonl").open("x", encoding="utf-8") as raw:
            while True:
                current = snapshot(processes, interface, start)
                if tree is not None:
                    current["memoryTree"] = tree_memory(tree, pids)
                raw.write(json.dumps(current, allow_nan=False) + "\n")
                raw.flush()
                result["sampleCount"] += 1
                metrics = {"memoryPeakMiB": sum(item["rssBytes"] for item in current["processes"]) / 2**20,
                           "openHandlesOrFdsPeak": sum(item["openHandlesOrFds"] for item in current["processes"])}
                if tree is not None:
                    metrics["memoryTreePeakMiB"] = sum(item["rssBytes"] for item in current["memoryTree"]) / 2**20
                    metrics["combinedMemoryPeakMiB"] = metrics["memoryPeakMiB"] + metrics["memoryTreePeakMiB"]
                if previous is None:
                    first = current["elapsedSeconds"]
                else:
                    metrics.update(interval_metrics(previous, current))
                for key, value in metrics.items():
                    peaks[key] = max(peaks.get(key, 0), value)
                elapsed = current["elapsedSeconds"] - first
                if previous is not None and stop_file is not None and stop_file.is_file():
                    break
                if elapsed >= duration:
                    if stop_file is not None:
                        raise TimeoutError("load did not signal completion before deadline")
                    break
                previous = current
                time.sleep(min(interval, duration - elapsed))
        result.update(status="completed", measuredDurationSeconds=elapsed, sampledPeaks=peaks,
                      endCondition="stop-file" if stop_file is not None else "duration",
                      observedInterfaceTraffic=any(peaks[key] > 0 for key in ("upstreamPeakKbitPerSecond", "downstreamPeakKbitPerSecond")))
    except (psutil.Error, OSError, ValueError) as error:
        result["errorType"] = type(error).__name__
        raise
    finally:
        result["finishedAt"] = utc_now()
        write_json(directory / "summary.json", result)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Collect read-only process and interface samples; no capacity verdict.")
    parser.add_argument("--pid", type=int, action="append", required=True)
    parser.add_argument("--memory-tree-pid", type=int, help="Additionally sample RSS of this separate root and its current descendants")
    parser.add_argument("--interface", required=True)
    parser.add_argument("--duration", type=float, required=True)
    parser.add_argument("--interval", type=float, default=1)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--stop-file", type=Path, help="Stop after this initially absent file appears; duration becomes a hard deadline")
    args = parser.parse_args()
    collect(args.pid, args.interface, args.duration, args.interval, args.output, args.stop_file, args.memory_tree_pid)
