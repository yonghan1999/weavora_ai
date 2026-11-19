import hashlib
import json
import math
from pathlib import Path
import random
import struct
import subprocess
import sys
from tempfile import TemporaryDirectory
import unittest


class SoftwareNoiseComparisonTest(unittest.TestCase):
    def invoke(self, source, output):
        return subprocess.run([self.executable, source, output], capture_output=True, timeout=30)

    def test_stationary_noise_runs_both_native_processors(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "noise.s16le"
            output = root / "comparison"
            generator = random.Random(20261119)
            raw = struct.pack("<144000h", *(generator.randint(-5000, 5000) for _ in range(144000)))
            source.write_bytes(raw)
            result = self.invoke(source, output)
            self.assertEqual(result.returncode, 0, result.stderr.decode(errors="replace"))
            off, on = [(output / name).read_bytes() for name in ("ns-off.s16le", "ns-on.s16le")]
            self.assertEqual((len(off), len(on)), (len(raw), len(raw)))
            self.assertEqual(source.read_bytes(), raw)

            def rms(data):
                tail = struct.unpack("<48000h", data[-96000:])
                return math.sqrt(sum(sample * sample for sample in tail) / len(tail))

            self.assertGreater(rms(on), 0)
            self.assertLess(rms(on), rms(off))
            self.assertNotEqual(self.invoke(source, output).returncode, 0)
            self.assertEqual((output / "ns-on.s16le").read_bytes(), on)
            print(json.dumps({"scope": "synthetic-stationary-noise-only", "frames": len(raw) // 960,
                              "tailRmsOff": rms(off), "tailRmsOn": rms(on),
                              "inputSha256": hashlib.sha256(raw).hexdigest(), "offSha256": hashlib.sha256(off).hexdigest(),
                              "onSha256": hashlib.sha256(on).hexdigest()}))

    def test_invalid_or_partial_frame_rejected_without_output(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            for size in (0, 1, 959, 961):
                with self.subTest(size=size):
                    source = root / "partial.s16le"
                    source.write_bytes(bytes(size))
                    self.assertNotEqual(self.invoke(source, root / "out").returncode, 0)
                    self.assertFalse((root / "out").exists())


if __name__ == "__main__":
    SoftwareNoiseComparisonTest.executable = Path(sys.argv.pop(1)).resolve(strict=True)
    unittest.main()
