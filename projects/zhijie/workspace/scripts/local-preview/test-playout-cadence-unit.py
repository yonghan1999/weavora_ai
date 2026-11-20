import importlib.util
from pathlib import Path
import subprocess
import sys
import unittest


spec = importlib.util.spec_from_file_location(
    "playout_cadence", Path(__file__).with_name("test-playout-cadence.py"))
cadence = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cadence)


def observations(windows=1):
    lines = [f"CADENCE_STARTED mode=synthetic unix_ms=123456789 observation_ms={windows * 15000}"]
    count = 1500 * windows
    for receiver in range(12):
        for speaker in range(12):
            common = f"mode=synthetic receiver={receiver} speaker=cadence-{speaker}:1"
            kind = "SOURCE_CADENCE" if receiver == speaker else "PLAYOUT_CADENCE"
            lines.append(f"{kind} {common} observation_ms={windows * 15000} frames={count} "
                         f"samples={count * 480} nonzero={count} peak_abs=6000 max_gap_us=10000")
            lines.append(f"AUDIO_QUEUE {common} measured_ms={windows * 15000} native_frames={count} "
                         f"native_samples={count * 480} dropped=0 consumed={count} "
                         "initial_native_frames=10 initial_dropped=0")
            lines.append(f"PCM_SIGNAL mode=synthetic signal=tone receiver={receiver} "
                         f"speaker=cadence-{speaker}:1 active_frames={count} frequency_hz={300 + speaker * 100}.000")
            lines.append(f"CADENCE_WINDOWS {common} callbacks={[1500] * windows} active={[1500] * windows} "
                         f"native={[1500] * windows} dropped={[0] * windows}")
    return lines


class CadenceSummaryTest(unittest.TestCase):
    def test_complete_seven_window_observation(self):
        result = cadence.summarize("\n".join(observations(7)), "synthetic", "tone", 7)
        self.assertEqual(result["source"]["count"], 12)
        self.assertEqual(result["playout"]["count"], 132)
        self.assertEqual(result["playout"]["minWindowNativeFrames"], 1500)
        self.assertEqual(result["playout"]["maxFrequencyErrorHz"], 0)
        self.assertEqual(result["measurementStartedUnixMs"], 123456789)

    def test_duplicate_observations_rejected(self):
        lines = observations()
        for index in range(5):
            with self.subTest(index=index), self.assertRaises(ValueError):
                cadence.summarize("\n".join(lines + [lines[index]]), "synthetic", "tone")

    def test_missing_observations_rejected(self):
        lines = observations()
        for index in range(5):
            with self.subTest(index=index), self.assertRaises(ValueError):
                cadence.summarize("\n".join(lines[:index] + lines[index + 1:]), "synthetic", "tone")

    def test_window_count_and_totals_must_match(self):
        log = "\n".join(observations(7))
        for invalid in (
            log.replace("callbacks=[1500,", "callbacks=[1499,", 1),
            log.replace("active=[1500,", "active=[1499,", 1),
            log.replace("native=[1500,", "native=[1499,", 1),
            log.replace("dropped=[0,", "dropped=[1,", 1),
            log.replace("native=[1500, 1500,", "native=[1500,", 1),
        ):
            with self.subTest(invalid=invalid[:150]), self.assertRaises(ValueError):
                cadence.summarize(invalid, "synthetic", "tone", 7)

    def test_queue_and_consumer_totals_must_match(self):
        with self.assertRaises(ValueError):
            cadence.summarize("\n".join(observations()).replace("consumed=1500", "consumed=1499", 1),
                              "synthetic", "tone")

    def test_diagnostic_does_not_turn_quality_failure_into_acceptance(self):
        log = "\n".join(observations()).replace("active_frames=1500", "active_frames=800")
        log = log.replace("active=[1500]", "active=[800]")
        result = cadence.summarize(log, "synthetic", "tone")
        self.assertEqual(result["playout"]["minWindowActiveFrames"], 800)

    def test_controlled_tone_refuses_host_output(self):
        result = subprocess.run([
            sys.executable, "-B", str(Path(__file__).with_name("test-playout-cadence.py")),
            "--media-address", "127.0.0.1", "--mode", "platform", "--signal", "tone",
        ], capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 2)
        self.assertIn("never send tones to host output", result.stderr)


if __name__ == "__main__":
    unittest.main()
