import json
import math
import re


def summarize_rtp(log, mode, windows):
    rows = {}
    for line in log.splitlines():
        if not line.startswith("RTP_CADENCE "):
            continue
        row = json.loads(line.removeprefix("RTP_CADENCE "))
        identity = re.fullmatch(r"cadence-(\d+):1", row["speaker"])
        if row["mode"] != mode or identity is None:
            raise ValueError("unexpected RTP mode or identity")
        receiver, speaker, sample = row["receiver"], int(identity[1]), row["sample"]
        if type(receiver) is not int or type(sample) is not int:
            raise ValueError("invalid RTP participant or sample index")
        pair = (receiver, speaker, sample)
        if pair in rows:
            raise ValueError("duplicate RTP observation")
        direction = "outbound" if receiver == speaker else "inbound"
        if row["direction"] != direction or not isinstance(row["rtp_id"], str) or not row["rtp_id"]:
            raise ValueError("incorrect RTP direction or missing stream identity")
        unsigned = ["elapsed_ms", "request_us", "ssrc", "rtc_timestamp_us", "packets", "bytes"]
        if direction == "inbound":
            unsigned += ["packets_discarded", "jitter_buffer_emitted", "total_samples", "concealed", "silent_concealed"]
            if type(row["packets_lost"]) is not int:
                raise ValueError("invalid RTP loss count")
            for name in ("jitter_seconds", "jitter_buffer_delay", "samples_duration"):
                if type(row[name]) not in (int, float) or not math.isfinite(row[name]) or row[name] < 0:
                    raise ValueError("invalid RTP timing field")
        if any(type(row[name]) is not int or row[name] < 0 for name in unsigned):
            raise ValueError("invalid RTP counter")
        if not sample * 15000 <= row["elapsed_ms"] <= sample * 15000 + 5000:
            raise ValueError("RTP sample outside scheduled interval")
        rows[pair] = row
    expected = {(receiver, speaker, sample) for receiver in range(12) for speaker in range(12)
                for sample in range(windows + 1)}
    if set(rows) != expected:
        raise ValueError("incomplete RTP directions or sample boundaries")
    result = {"sourceCount": 12, "playoutCount": 132, "samplesPerTrack": windows + 1,
              "maxRequestUs": max(row["request_us"] for row in rows.values()), "windows": []}
    for window in range(windows):
        values = {"outbound": [], "inbound": []}
        for receiver in range(12):
            for speaker in range(12):
                before, after = (rows[(receiver, speaker, sample)] for sample in (window, window + 1))
                if before["rtp_id"] != after["rtp_id"] or before["ssrc"] != after["ssrc"]:
                    raise ValueError("RTP stream changed within observation")
                if after["elapsed_ms"] <= before["elapsed_ms"] or after["rtc_timestamp_us"] <= before["rtc_timestamp_us"]:
                    raise ValueError("RTP snapshot timestamps did not advance")
                names = ["packets", "bytes"]
                if after["direction"] == "inbound":
                    names += ["packets_discarded", "jitter_buffer_emitted", "total_samples", "concealed", "silent_concealed"]
                delta = {name: after[name] - before[name] for name in names}
                if any(value < 0 for value in delta.values()):
                    raise ValueError("cumulative RTP counter moved backwards")
                delta["rtcIntervalUs"] = after["rtc_timestamp_us"] - before["rtc_timestamp_us"]
                delta["completionIntervalMs"] = after["elapsed_ms"] - before["elapsed_ms"]
                delta["packetsPerSecond"] = delta["packets"] * 1_000_000 / delta["rtcIntervalUs"]
                if after["direction"] == "inbound":
                    delta["packetsLost"] = after["packets_lost"] - before["packets_lost"]
                    delta["jitterSeconds"] = after["jitter_seconds"]
                values[after["direction"]].append(delta)
        summary = {"window": window}
        for direction, observations in values.items():
            summary[direction] = {"count": len(observations)}
            for name in observations[0]:
                summary[direction][name + "Min"] = min(row[name] for row in observations)
                summary[direction][name + "Max"] = max(row[name] for row in observations)
        result["windows"].append(summary)
    return result
