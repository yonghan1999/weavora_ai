import argparse
import json
import math
from pathlib import Path


CORE = ("heartbeat", "message-read", "message-write", "friends-presence")


def audit(summary, rows, seconds):
    if (seconds not in (90, 3600) or summary.get("requestedSeconds") != seconds
            or summary.get("execution") != "completed" or summary.get("accounts") != 100
            or summary.get("scope") != "local-control-plane-smoke"
            or not math.isfinite(summary["durationSeconds"]) or summary["durationSeconds"] < seconds):
        raise ValueError("completed local run of requested duration required")
    previous_completion = 0
    latencies = []
    last = {}
    largest_gap = 0
    rotations = [0] * 100
    for row in rows:
        actor = row.get("actor")
        operation = row.get("operation")
        if (type(actor) is not int or not 0 <= actor < 100 or operation not in (*CORE, "session-refresh")
                or row.get("status") != 200 or "code" not in row or row["code"] is not None):
            raise ValueError(f"unexpected or failed operation at row {len(latencies) + 1}")
        offset = row.get("workloadElapsedMs")
        if type(offset) not in (int, float) or not math.isfinite(offset):
            raise ValueError("missing or invalid monotonic workload clock")
        elapsed = offset / 1000
        if not previous_completion <= elapsed <= summary["durationSeconds"]:
            raise ValueError("timestamp outside ordered workload")
        previous_completion = elapsed
        latency = row.get("latencyMs")
        if type(latency) not in (int, float) or not math.isfinite(latency) or latency < 0:
            raise ValueError(f"missing or invalid latency at row {len(latencies) + 1}")
        latencies.append(latency)
        if operation == "session-refresh":
            rotations[actor] += 1
            continue
        key = (actor, operation)
        previous = last.get(key)
        if previous is None:
            previous = 68 if operation == "heartbeat" and actor >= 90 else 0
            if previous and elapsed < previous:
                raise ValueError(f"missing initial presence pause: actor={actor}")
        gap = max(0, elapsed - previous)
        if gap > 60:
            raise ValueError(f"activity gap {gap:.2f}s: actor={actor}, operation={operation}")
        largest_gap = max(largest_gap, gap)
        last[key] = elapsed
    count = len(latencies)
    if count != summary["requests"] or min(rotations) != summary["rotationsPerAccountMin"]:
        raise ValueError("raw operation count or minimum refresh count differs from summary")
    if seconds == 3600 and min(rotations) < 4:
        raise ValueError("four refreshes required for every actor")
    for actor in range(100):
        for operation in CORE:
            elapsed = last.get((actor, operation))
            if elapsed is None or summary["durationSeconds"] - elapsed > 60:
                raise ValueError(f"missing activity near end: actor={actor}, operation={operation}")
            largest_gap = max(largest_gap, summary["durationSeconds"] - elapsed)
    latencies.sort()
    metrics = {"failures": 0, "operationSuccessRatePercent": 100, "p95LatencyMs": latencies[math.ceil(count * 0.95) - 1]}
    for name, observed in metrics.items():
        value = summary.get(name)
        if type(value) not in (int, float) or value != observed:
            raise ValueError(f"{name} differs from raw HTTP operations")
    return {"scope": "local-http-activity-only", "requestedSeconds": seconds, "accounts": 100,
            "httpOperations": count, **metrics, "minimumRefreshes": min(rotations), "maxCoreGapSeconds": round(largest_gap, 3)}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("directory", type=Path)
    parser.add_argument("--seconds", type=int, required=True, choices=(90, 3600))
    args = parser.parse_args()
    summary = json.loads((args.directory / "summary.json").read_text(encoding="utf-8"))
    with (args.directory / "operations.jsonl").open(encoding="utf-8") as records:
        print(json.dumps(audit(summary, map(json.loads, records), args.seconds)))
