"""从锁定的 Cargo 缓存准备 Windows x64 预览驱动，不加载 DLL。"""

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import struct
import subprocess
import tarfile
import tomllib


WORKSPACE = Path(__file__).resolve().parents[2]
VERSION = "0.7.40"
ARCHIVE_SHA256 = "e4316764300a7eb4aecf4770c81ae629ff1161b54905d48721a6832fd296ce5f"
MEMBERS = {
    "wintun/bin/amd64/wintun.dll": (
        "wintun.dll", "e5da8447dc2c320edc0fc52fa01885c103de8c118481f683643cacc3220dafce"
    ),
    "wintun/LICENSE.txt": (
        "WINTUN-LICENSE.txt", "183adac21e7d96c508c8fd34d394b7b6708bc81564ad1bad61ab66143a008cd2"
    ),
    "wintun/include/wintun.h": (
        "wintun.h", "510a5984fbf73efd21a61ada60edfe05e1a38a77c8c47f6d62e0ab1cdbdd460f"
    ),
}


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def validate_lock(path):
    packages = tomllib.loads(path.read_text(encoding="utf-8"))["package"]
    matches = [p for p in packages if p["name"] == "wintun-bindings"]
    if len(matches) != 1 or any(
        matches[0].get(key) != value for key, value in {
            "version": VERSION,
            "checksum": ARCHIVE_SHA256,
            "source": "registry+https://github.com/rust-lang/crates.io-index",
        }.items()
    ):
        raise ValueError("Cargo.lock 的 Wintun 来源变化，需要重新核对驱动及许可")


def validate_x64(data):
    if len(data) < 64 or data[:2] != b"MZ":
        raise ValueError("Wintun 不是 PE 文件")
    offset = struct.unpack_from("<I", data, 0x3C)[0]
    if offset + 26 > len(data) or data[offset:offset + 4] != b"PE\0\0":
        raise ValueError("Wintun PE 头无效")
    if (struct.unpack_from("<H", data, offset + 4)[0] != 0x8664
            or struct.unpack_from("<H", data, offset + 24)[0] != 0x20B):
        raise ValueError("预览包仅支持 Windows x64 Wintun")


def read_archive(path):
    raw = path.read_bytes()
    if sha256(raw) != ARCHIVE_SHA256:
        raise ValueError("Cargo 缓存归档摘要不匹配")
    files = {}
    with tarfile.open(fileobj=io.BytesIO(raw), mode="r:gz") as archive:
        for relative, (name, digest) in MEMBERS.items():
            member = archive.getmember(f"wintun-bindings-{VERSION}/{relative}")
            if not member.isfile() or member.size > 1024 * 1024:
                raise ValueError("归档驱动/许可成员无效")
            data = archive.extractfile(member).read()
            if sha256(data) != digest:
                raise ValueError("归档驱动/许可摘要不匹配")
            files[name] = data
    validate_x64(files["wintun.dll"])
    return files


def verify_signature(path):
    # 通过环境变量传路径，不拼接 PowerShell 代码；只查签名，不加载 DLL。
    env = os.environ.copy()
    env["ZHIJIE_WINTUN_SIGNATURE_PATH"] = str(path.resolve())
    result = subprocess.run([
        "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
        "$ErrorActionPreference='Stop'; "
        "$s=Get-AuthenticodeSignature -LiteralPath $env:ZHIJIE_WINTUN_SIGNATURE_PATH; "
        "if ($s.Status -ne 'Valid' -or "
        "$s.SignerCertificate.GetNameInfo([System.Security.Cryptography.X509Certificates.X509NameType]::SimpleName,$false) "
        "-cne 'WireGuard LLC') { throw 'Wintun signature invalid' }; "
        "Write-Output 'Valid: WireGuard LLC'",
    ], env=env, capture_output=True, text=True, timeout=60)
    if result.returncode != 0:
        raise ValueError("Wintun Authenticode 签名校验失败")


def stage(archive, output):
    validate_lock(WORKSPACE / "Cargo.lock")
    files = read_archive(archive)
    manifest = {
        "component": "Wintun prebuilt binary",
        "architecture": "windows-x86_64",
        "upstream": "https://www.wintun.net/",
        "distribution": f"https://crates.io/api/v1/crates/wintun-bindings/{VERSION}/download",
        "crate_sha256": ARCHIVE_SHA256,
        "files": {name: sha256(data) for name, data in files.items()},
        "license": "WINTUN-LICENSE.txt; redistribution exception in section 3(d)",
        "signature_requirement": "Authenticode Valid; WireGuard LLC",
        "scope": "Build preparation only; no installation, DLL loading or adapter creation",
    }
    files["wintun-source.json"] = (json.dumps(manifest, indent=2) + "\n").encode()
    # 不覆盖不同内容或重解析点；重复构建只复用摘要一致的已生成文件。
    if any(p.is_symlink() or p.is_junction() for p in (output, *output.parents)):
        raise ValueError("驱动输出不能经过重解析目录")
    if output.exists() and not output.is_dir():
        raise ValueError("驱动输出目录无效")
    for name, data in files.items():
        target = output / name
        if target.is_symlink() or (target.exists() and (
                not target.is_file() or target.read_bytes() != data)):
            raise ValueError("驱动输出已有不同内容，拒绝覆盖")
    output.mkdir(parents=True, exist_ok=True)
    for name, data in files.items():
        target = output / name
        if not target.exists():
            with target.open("xb") as stream:
                stream.write(data)
    verify_signature(output / "wintun.dll")
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--crate", type=Path)
    parser.add_argument("--output-directory", type=Path,
                        default=WORKSPACE / "target/debug/wintun-runtime")
    args = parser.parse_args()
    if os.name != "nt":
        parser.error("该预览打包入口只支持 Windows")
    archive = args.crate
    if archive is None:
        cargo_home = Path(os.environ.get("CARGO_HOME", Path.home() / ".cargo"))
        candidates = list((cargo_home / "registry/cache").glob(f"*/wintun-bindings-{VERSION}.crate"))
        if len(candidates) != 1:
            parser.error("请先准备锁定的离线 Cargo 依赖，或用 --crate 指定归档")
        archive = candidates[0]
    try:
        manifest = stage(archive, args.output_directory)
    except (OSError, ValueError, tarfile.TarError, subprocess.SubprocessError) as exc:
        parser.exit(1, f"Wintun 准备失败：{exc}\n")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
