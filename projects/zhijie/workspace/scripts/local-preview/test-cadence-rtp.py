import json
import unittest

from cadence_rtp import summarize_rtp


def observations(windows=1):
    rows = []
    for receiver in range(12):
        for speaker in range(12):
            for sample in range(windows + 1):
                row = dict(mode="synthetic", receiver=receiver, speaker=f"cadence-{speaker}:1", sample=sample,
                           elapsed_ms=sample * 15000 + 10, request_us=100, rtp_id=f"audio-{receiver}-{speaker}",
                           ssrc=speaker + 1, rtc_timestamp_us=1_000_000 + sample * 15_000_000,
                           packets=sample * 750, bytes=sample * 50000,
                           direction="outbound" if receiver == speaker else "inbound")
                if receiver != speaker:
                    row.update(packets_lost=0, packets_discarded=0, jitter_seconds=0.001,
                               jitter_buffer_delay=sample * 10.0, jitter_buffer_emitted=sample * 720000,
                               total_samples=sample * 720000, concealed=0, silent_concealed=0,
                               samples_duration=sample * 15.0)
                rows.append(row)
    return rows


def log(rows):
    return "\n".join("RTP_CADENCE " + json.dumps(row) for row in rows)


class RtpSummaryTest(unittest.TestCase):
    def test_all_directions_and_windows_summarized(self):
        result = summarize_rtp(log(observations(7)), "synthetic", 7)
        self.assertEqual(result["playoutCount"], 132)
        self.assertEqual(result["sourceCount"], 12)
        self.assertEqual(result["samplesPerTrack"], 8)
        self.assertEqual(len(result["windows"]), 7)
        self.assertEqual(result["windows"][6]["inbound"]["packetsPerSecondMin"], 50)
        self.assertEqual(result["windows"][6]["inbound"]["total_samplesMin"], 720000)

    def test_packet_rate_uses_report_clock_not_query_completion(self):
        rows = observations()
        for row in rows:
            if row["sample"] == 1:
                row["elapsed_ms"] += 125
                row["request_us"] += 125000
        summary = summarize_rtp(log(rows), "synthetic", 1)
        self.assertEqual(summary["maxRequestUs"], 125100)
        for direction in ("inbound", "outbound"):
            window = summary["windows"][0][direction]
            self.assertEqual(window["packetsPerSecondMin"], 50)
            self.assertEqual(window["completionIntervalMsMax"], 15125)
            self.assertEqual(window["rtcIntervalUsMin"], 15000000)

    def test_missing_and_duplicate_boundaries_rejected(self):
        rows = observations()
        for invalid in (rows[1:], rows + [rows[0]]):
            with self.subTest(length=len(invalid)), self.assertRaises(ValueError):
                summarize_rtp(log(invalid), "synthetic", 1)

    def test_bad_identity_direction_and_timing_rejected(self):
        for name, value in (("speaker", "unexpected"), ("direction", "inbound"),
                            ("elapsed_ms", 6000), ("rtc_timestamp_us", -1), ("receiver", True),
                            ("ssrc", -1), ("packets", "750")):
            rows = observations()
            rows[0][name] = value
            with self.subTest(name=name), self.assertRaises(ValueError):
                summarize_rtp(log(rows), "synthetic", 1)

    def test_counter_reset_and_stream_replacement_rejected(self):
        for name, value in (("rtp_id", "replacement"), ("ssrc", 99), ("packets", 0),
                            ("rtc_timestamp_us", 1_000_000), ("bytes", 0)):
            rows = observations(2)
            rows[2][name] = value
            with self.subTest(name=name), self.assertRaises(ValueError):
                summarize_rtp(log(rows), "synthetic", 2)

    def test_non_finite_inbound_statistics_rejected(self):
        for value in (float("nan"), float("inf"), -1):
            rows = observations()
            rows[2]["jitter_seconds"] = value
            with self.subTest(value=value), self.assertRaises(ValueError):
                summarize_rtp(log(rows), "synthetic", 1)

    def test_stalled_decode_does_not_become_quality_pass(self):
        rows = observations()
        for row in rows:
            if row["direction"] == "inbound":
                row["total_samples"] = row["sample"] * 400000
                row["packets_lost"] = -1
        result = summarize_rtp(log(rows), "synthetic", 1)
        self.assertEqual(result["windows"][0]["inbound"]["total_samplesMax"], 400000)
        self.assertEqual(result["windows"][0]["inbound"]["packetsPerSecondMin"], 50)


if __name__ == "__main__":
    unittest.main()
