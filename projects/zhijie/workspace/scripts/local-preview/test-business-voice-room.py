import importlib.util
from pathlib import Path
import tempfile
import unittest


spec = importlib.util.spec_from_file_location("business_room", Path(__file__).with_name("business-voice-room.py"))
room = importlib.util.module_from_spec(spec)
spec.loader.exec_module(room)


class BusinessAudioEvidenceTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.speakers = [f"user-{index}:2" for index in range(12)]
        self.paths = [Path(directory.name) / f"client-{index}.log" for index in range(12)]
        for receiver, path in enumerate(self.paths):
            lines = [f"BUSINESS_STARTED participant={receiver} unix_ms=4321"]
            lines.extend(f"PCM_DIRECTION receiver={receiver} speaker={identity} frequency_hz={300 + speaker * 100} "
                         f"non_silent_frames={[1200] * 5}" for speaker, identity in enumerate(self.speakers) if speaker != receiver)
            path.write_text("\n".join(lines), encoding="utf-8")

    def test_exact_business_mesh(self):
        self.assertEqual(len(room.validate(self.paths, self.speakers, 4321, 5)), 132)

    def test_reject_wrong_identity_window_or_boundary(self):
        path = self.paths[-1]
        lines = path.read_text(encoding="utf-8").splitlines()
        for invalid in (lines[1:], lines[:-1], lines + [lines[1]],
                        [lines[0].replace("4321", "4320"), *lines[1:]],
                        [*lines[:-1], lines[-1].replace("user-10:2", "user-10:1")],
                        [*lines[:-1], lines[-1].replace("1300", "1200")],
                        [*lines[:-1], lines[-1].replace("1200", "1199", 1)]):
            path.write_text("\n".join(invalid), encoding="utf-8")
            with self.subTest(end=invalid[-1]), self.assertRaises(ValueError):
                room.validate(self.paths, self.speakers, 4321, 5)


if __name__ == "__main__":
    unittest.main()
