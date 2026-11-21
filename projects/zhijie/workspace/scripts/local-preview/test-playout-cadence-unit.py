import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from cadence_clients import combined_log, participant_env, run_clients


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
    @unittest.skipUnless(sys.platform == "win32", "Windows diagnostic launcher")
    def test_partial_launch_reaps_already_started_client(self):
        children = []
        spawn = subprocess.Popen

        def fail_second(*args, **kwargs):
            if children:
                raise OSError("second client not launched")
            children.append(spawn(*args, **kwargs))
            return children[0]

        with tempfile.TemporaryDirectory() as directory, patch("cadence_clients.subprocess.Popen", side_effect=fail_second):
            root = Path(directory)
            env = {**os.environ, "ZHIJIE_TEST_VOICE_TOKEN_0": "local", "ZHIJIE_TEST_VOICE_TOKEN_1": "local"}
            with self.assertRaisesRegex(OSError, "second client"):
                run_clients([sys.executable, "-B", "-c", "import time; time.sleep(30)"], root, root, None, env, None, 1)
            self.assertIsNotNone(children[0].poll())
            self.assertTrue(children[0].stdin.closed)

    def test_split_process_receives_only_its_token(self):
        env = {f"ZHIJIE_TEST_VOICE_TOKEN_{index}": str(index) for index in range(12)}
        env["zhijie_test_voice_token_stale"] = "stale"
        for index in range(12):
            self.assertEqual(participant_env(env, index), {f"ZHIJIE_TEST_VOICE_TOKEN_{index}": str(index),
                                                           "ZHIJIE_CADENCE_PARTICIPANT": str(index)})

    def test_split_process_requires_one_matching_boundary_per_process(self):
        header = "CADENCE_STARTED mode=synthetic unix_ms=10000 observation_ms=15000"
        with tempfile.TemporaryDirectory() as root:
            paths = [Path(root) / str(index) for index in range(12)]
            for index, path in enumerate(paths):
                path.write_text(f"{header}\nvalue {index}\n", encoding="utf-8")
            lines = combined_log(paths, 10000, 1).splitlines()
            self.assertEqual(lines, [header] + [f"value {index}" for index in range(12)])
            for invalid in ("", header + "\n" + header, header.replace("10000", "10001")):
                paths[-1].write_text(invalid, encoding="utf-8")
                with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                    combined_log(paths, 10000, 1)

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
