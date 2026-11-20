import argparse
import configparser
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import tarfile
from contextlib import contextmanager


def check_control(stream):
    seen = set()
    with tarfile.open(fileobj=stream, mode="r|") as archive:
        for entry in archive:
            path = PurePosixPath(entry.name)
            if path == PurePosixPath(".") and entry.isdir():
                continue
            if path not in (PurePosixPath("control"), PurePosixPath("md5sums")) or not entry.isfile() or path in seen:
                raise ValueError(f"unexpected control archive entry: {entry.name}")
            seen.add(path)
    if PurePosixPath("control") not in seen:
        raise ValueError("missing package control file")


@contextmanager
def package_stream(package, option):
    with subprocess.Popen(["dpkg-deb", option, package], stdout=subprocess.PIPE) as process:
        with process.stdout as stream:
            yield stream
            while stream.read(65536):
                pass
        if process.wait() != 0:
            raise ValueError(f"dpkg-deb failed to read {option}")


def check_payload(stream, localized_name):
    menus, executables, icons = {}, set(), set()
    seen = set()
    with tarfile.open(fileobj=stream, mode="r|") as archive:
        for entry in archive:
            path = PurePosixPath(entry.name)
            if path.is_absolute() or ".." in path.parts or path in seen:
                raise ValueError(f"ambiguous package path: {entry.name}")
            seen.add(path)
            if path.name == "zhijie-helper" or ("systemd" in path.parts and path.suffix == ".service"):
                raise ValueError(f"unexpected helper or service: {path}")
            if path.parent == PurePosixPath("usr/share/applications") and path.suffix == ".desktop":
                if not entry.isfile() or entry.size > 65536:
                    raise ValueError(f"invalid desktop entry: {path}")
                config = configparser.ConfigParser(interpolation=None, delimiters=("=",))
                config.optionxform = str
                config.read_string(archive.extractfile(entry).read().decode("utf-8"))
                menus[path] = config["Desktop Entry"]
            elif path.parent == PurePosixPath("usr/bin") and entry.isfile() and entry.mode & 0o111 == 0o111:
                header = archive.extractfile(entry).read(64)
                if (len(header) == 64 and header[:7] == b"\x7fELF\x02\x01\x01"
                        and header[16:18] in (b"\x02\x00", b"\x03\x00") and header[18:20] == b"\x3e\x00"):
                    executables.add(str(path))
            elif len(path.parts) == 7 and path.match("usr/share/icons/hicolor/*/apps/*.png") and entry.isfile():
                if archive.extractfile(entry).read(8) == b"\x89PNG\r\n\x1a\n":
                    icons.add(path.stem)
    if len(menus) != 1:
        raise ValueError(f"expected one desktop entry, found {len(menus)}")
    menu, fields = next(iter(menus.items()))
    if fields.get("Type") != "Application" or fields.get("Name[zh_CN]") != localized_name:
        raise ValueError(f"incorrect menu type or localized name: {menu}")
    command = fields.get("Exec", "")
    if not re.fullmatch(r"(?:/usr/bin/)?[A-Za-z0-9_.+-]+", command):
        raise ValueError(f"unexpected menu Exec: {command}")
    binary = f"usr/bin/{PurePosixPath(command).name}"
    if binary not in executables or fields.get("Icon") not in icons:
        raise ValueError(f"missing executable ELF64/x86-64 or matching PNG icon for {menu}")
    return menu, binary, fields["Icon"]


def check_package(package):
    shell = Path(__file__).resolve().parents[2] / "desktop" / "shell"
    base = json.loads((shell / "tauri.conf.json").read_text(encoding="utf-8"))
    expected = {"Package": "zhijie", "Version": base["version"], "Architecture": "amd64"}
    for field, value in expected.items():
        actual = subprocess.check_output(["dpkg-deb", "--field", package, field], encoding="utf-8").strip()
        if actual != value:
            raise ValueError(f"{field}: expected {value!r}, found {actual!r}")
    with package_stream(package, "--ctrl-tarfile") as stream:
        check_control(stream)
    with package_stream(package, "--fsys-tarfile") as stream:
        return check_payload(stream, base["productName"])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Read-only checks for the Zhijie Ubuntu desktop candidate")
    parser.add_argument("deb", type=Path)
    args = parser.parse_args()
    try:
        menu, binary, icon = check_package(args.deb.resolve(strict=True))
    except (OSError, ValueError, KeyError, configparser.Error, tarfile.TarError, subprocess.SubprocessError) as error:
        sys.exit(f"FAIL: {error}")
    print(f"OK: {menu} -> {binary}, icon={icon} (contents only; not installed)")
