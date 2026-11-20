import io
import json
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch

from check_deb import check_control, check_package, check_payload


MENU = "usr/share/applications/Zhijie Client.desktop"
BIN = "usr/bin/zhijie-shell"
ICON = "usr/share/icons/hicolor/128x128/apps/zhijie.png"
ENTRY = "[Desktop Entry]\nType=Application\nName[zh_CN]=织界\nExec=zhijie-shell\nIcon=zhijie\n".encode()
ELF = (b"\x7fELF\x02\x01\x01".ljust(16, b"\x00") + b"\x03\x00\x3e\x00").ljust(64, b"\x00")
FILES = [(MENU, ENTRY, 0o644), (BIN, ELF, 0o755), (ICON, b"\x89PNG\r\n\x1a\n", 0o644)]
CONFIG = Path(__file__).resolve().parents[2] / "desktop/shell/tauri.conf.json"


def archived(files):
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode="w") as archive:
        for name, data, mode in files:
            entry = tarfile.TarInfo("./" + name)
            entry.size, entry.mode = len(data), mode
            archive.addfile(entry, io.BytesIO(data))
    stream.seek(0)
    return stream


def payload(files):
    return check_payload(archived(files), "织界")


class PayloadTests(unittest.TestCase):
    def test_package_checks_control_before_payload(self):
        package = Path("fixture.deb")
        version = json.loads(CONFIG.read_text(encoding="utf-8"))["version"]
        control = [("control", b"Package: zhijie\n", 0o644)]
        for extra in ([], [("postinst", b"", 0o755)]):
            with self.subTest(extra=bool(extra)), patch("check_deb.subprocess.check_output", side_effect=["zhijie", version, "amd64"]), \
                    patch("check_deb.package_stream", side_effect=[archived(control + extra), archived(FILES)]) as streams:
                if extra:
                    with self.assertRaisesRegex(ValueError, "unexpected control"):
                        check_package(package)
                    streams.assert_called_once_with(package, "--ctrl-tarfile")
                else:
                    self.assertEqual(check_package(package)[1], BIN)
                    self.assertEqual([call.args[1] for call in streams.call_args_list], ["--ctrl-tarfile", "--fsys-tarfile"])

    def test_control_contains_only_declared_metadata(self):
        control = ("control", b"Package: zhijie\n", 0o644)
        check_control(archived([control]))
        check_control(archived([control, ("md5sums", b"", 0o644)]))
        with self.assertRaisesRegex(ValueError, "missing package control"):
            check_control(archived([]))
        for name in ("preinst", "postinst", "prerm", "postrm", "config", "triggers", "control"):
            with self.subTest(entry=name), self.assertRaisesRegex(ValueError, "unexpected control"):
                check_control(archived([control, (name, b"", 0o755)]))

    def test_discovers_menu_and_independent_binary_and_icon_names(self):
        self.assertEqual(payload(reversed(FILES)), (PurePosixPath(MENU), BIN, "zhijie"))

    def test_rejects_broken_payloads(self):
        cases = {
            "missing menu": FILES[1:], "missing binary": FILES[::2], "missing icon": FILES[:2],
            "wrong machine": [FILES[0], (BIN, ELF[:18] + b"\xb7\x00" + ELF[20:], 0o755), FILES[2]],
            "not executable": [FILES[0], (BIN, ELF, 0o644), FILES[2]],
            "wrong name": [(MENU, ENTRY.replace("织界".encode(), b"Zhijie"), 0o644), *FILES[1:]],
            "wrong Exec": [(MENU, ENTRY.replace(b"zhijie-shell", b"missing"), 0o644), *FILES[1:]],
            "wrong icon": [(MENU, ENTRY.replace(b"Icon=zhijie", b"Icon=missing"), 0o644), *FILES[1:]],
            "second menu": [*FILES, (MENU + ".desktop", ENTRY, 0o644)],
            "duplicate path": [*FILES, FILES[1]], "parent path": [*FILES, ("../file", b"", 0o644)],
            "service": [*FILES, ("usr/lib/systemd/system/zhijie-helper.service", b"", 0o644)],
            "helper": [*FILES, ("usr/lib/zhijie/zhijie-helper", ELF, 0o755)],
        }
        for name, files in cases.items():
            with self.subTest(name=name), self.assertRaises(ValueError):
                payload(files)

    @unittest.skipUnless(shutil.which("dpkg-deb"), "requires dpkg-deb")
    def test_dpkg_deb_round_trip(self):
        version = json.loads(CONFIG.read_text(encoding="utf-8"))["version"]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "package"
            for name, data, mode in FILES:
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
                path.chmod(mode)
            control = root / "DEBIAN/control"
            control.parent.mkdir()
            deb = Path(tmp) / "fixture.deb"
            for arch in ("amd64", "arm64"):
                control.write_text(f"Package: zhijie\nVersion: {version}\nArchitecture: {arch}\n"
                                   "Maintainer: Fixture <fixture@example.invalid>\nDescription: test fixture\n")
                subprocess.run(["dpkg-deb", "--build", root, deb], check=True, stdout=subprocess.DEVNULL)
                if arch == "amd64":
                    self.assertEqual(check_package(deb)[1], BIN)
                    hook = root / "DEBIAN/postinst"
                    hook.write_text("#!/bin/sh\nexit 0\n")
                    hook.chmod(0o755)
                    subprocess.run(["dpkg-deb", "--build", root, deb], check=True, stdout=subprocess.DEVNULL)
                    with self.assertRaisesRegex(ValueError, "unexpected control archive entry.*postinst"):
                        check_package(deb)
                    hook.unlink()
                else:
                    with self.assertRaisesRegex(ValueError, "Architecture"):
                        check_package(deb)


if __name__ == "__main__":
    unittest.main()
