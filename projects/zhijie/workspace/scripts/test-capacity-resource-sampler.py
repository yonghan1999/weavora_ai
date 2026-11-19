import importlib.util
import json
import os
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

import psutil


spec = importlib.util.spec_from_file_location("sampler", Path(__file__).with_name("sample-capacity-resources.py"))
sampler = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sampler)


def sample(elapsed, cpu, sent, received, created=123):
    return {"elapsedSeconds": elapsed, "bytesSent": sent, "bytesReceived": received,
            "processes": [{"pid": 123, "created": created, "cpuSeconds": cpu, "rssBytes": 2048,
                           "openHandlesOrFds": 5}]}


class ResourceSamplerTest(unittest.TestCase):
    def test_cpu_and_interface_rates_use_actual_interval(self):
        rates = sampler.interval_metrics(sample(1, 2, 20, 100), sample(3, 5, 2020, 1100))
        self.assertEqual(rates, {"cpuPeakPercent": 150, "upstreamPeakKbitPerSecond": 8,
                                 "downstreamPeakKbitPerSecond": 4})

    def test_counter_regression_pid_reuse_and_invalid_time_rejected(self):
        previous = sample(1, 2, 20, 100)
        for current in (sample(3, 1, 20, 100), sample(3, 2, 19, 100), sample(3, 2, 20, 99),
                        sample(3, 2, 20, 100, created=456), sample(1, 2, 20, 100)):
            with self.subTest(current=current), self.assertRaises(ValueError):
                sampler.interval_metrics(previous, current)

    def test_interrupted_observation_keeps_raw_and_no_pass(self):
        with TemporaryDirectory() as temp:
            output = Path(temp) / "run"
            with patch.object(sampler, "snapshot", side_effect=[sample(0, 1, 0, 0), psutil.NoSuchProcess(123)]), \
                    self.assertRaises(psutil.NoSuchProcess):
                sampler.collect([os.getpid()], "test", .01, .01, output)
            summary = json.loads((output / "summary.json").read_text())
            self.assertEqual((summary["status"], summary["sampleCount"], summary["errorType"]), ("failed", 1, "NoSuchProcess"))
            self.assertNotIn("sampledPeaks", summary)
            self.assertEqual(len((output / "samples.jsonl").read_text().splitlines()), 1)

    def test_live_sample_and_no_overwrite(self):
        interface = next(iter(psutil.net_io_counters(pernic=True)))
        with TemporaryDirectory() as temp:
            output = Path(temp) / "run"
            sampler.collect([os.getpid()], interface, .05, .02, output)
            summary = json.loads((output / "summary.json").read_text(encoding="utf-8"))
            raw = (output / "samples.jsonl").read_bytes()
            self.assertEqual((summary["status"], summary["acceptance"]), ("completed", "unverified"))
            self.assertGreaterEqual(summary["measuredDurationSeconds"], .05)
            self.assertEqual(summary["sampleCount"], len(raw.splitlines()))
            self.assertGreater(summary["sampledPeaks"]["memoryPeakMiB"], 0)
            with self.assertRaises(FileExistsError):
                sampler.collect([os.getpid()], interface, .05, .02, output)
            self.assertEqual(raw, (output / "samples.jsonl").read_bytes())

    def test_invalid_configuration_does_not_create_output(self):
        with TemporaryDirectory() as temp:
            output = Path(temp) / "run"
            for pids, duration, interval in (([1, 1], 1, 1), ([0], 1, 1), ([1], float("inf"), 1), ([1], 1, 0)):
                with self.assertRaises(ValueError):
                    sampler.collect(pids, "test", duration, interval, output)
                self.assertFalse(output.exists())

    def test_stop_signal_requires_new_file_and_final_sample(self):
        with TemporaryDirectory() as temp:
            stop = Path(temp) / "stop"
            output = Path(temp) / "run"
            stop.touch()
            with self.assertRaises(ValueError):
                sampler.collect([os.getpid()], "test", 1, .1, output, stop)
            self.assertFalse(output.exists())
            stop.unlink()
            with patch.object(sampler, "snapshot", side_effect=[sample(0, 1, 0, 0), sample(.1, 1, 1, 2)]), \
                    patch.object(sampler.time, "sleep", side_effect=lambda _: stop.touch()):
                sampler.collect([os.getpid()], "test", 1, .1, output, stop)
            summary = json.loads((output / "summary.json").read_text())
            self.assertEqual((summary["endCondition"], summary["sampleCount"], summary["observedInterfaceTraffic"]), ("stop-file", 2, True))

    def test_absent_stop_signal_fails_at_deadline(self):
        with TemporaryDirectory() as temp:
            output = Path(temp) / "run"
            with patch.object(sampler, "snapshot", side_effect=[sample(0, 1, 0, 0), sample(.1, 1, 1, 2)]), \
                    patch.object(sampler.time, "sleep"), self.assertRaises(TimeoutError):
                sampler.collect([os.getpid()], "test", .1, .1, output, Path(temp) / "stop")
            self.assertEqual(json.loads((output / "summary.json").read_text())["status"], "failed")


if __name__ == "__main__":
    unittest.main()
