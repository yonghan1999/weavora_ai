"""从锁定的 crates.io 本地归档收集许可材料，不下载、不批准许可证。"""

import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import tarfile
import tomllib


SOURCE = "registry+https://github.com/rust-lang/crates.io-index"
COMPONENT = re.compile(r"[A-Za-z0-9_+-][A-Za-z0-9_.+-]*\Z")


def sha(data):
    return hashlib.sha256(data).hexdigest()


def safe_read(root, relative):
    path = root / relative
    if not path.resolve().is_relative_to(root.resolve()):
        raise ValueError("CARGO_LICENSE_PATH_OUTSIDE_ROOT")
    return path.read_bytes()


def archive_documents(data, name, version, declared, license_file):
    prefix = f"{name}-{version}"
    documents = []
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
        members = archive.getmembers()
        manifests = [item for item in members if item.name == prefix + "/Cargo.toml"]
        if len(manifests) != 1 or not manifests[0].isfile():
            raise ValueError("CARGO_LICENSE_MANIFEST_INVALID")
        package = tomllib.loads(archive.extractfile(manifests[0]).read().decode())["package"]
        if (package["name"], package["version"], package.get("license"), package.get("license-file")) != (name, version, declared, license_file):
            raise ValueError("CARGO_LICENSE_DECLARATION_MISMATCH")
        seen = set()
        for member in sorted(members, key=lambda item: item.name):
            relative = PurePosixPath(member.name)
            if relative.is_absolute() or ".." in relative.parts or not relative.parts or relative.parts[0] != prefix:
                raise ValueError("CARGO_LICENSE_ARCHIVE_PATH_INVALID")
            if member.name in seen:
                raise ValueError("CARGO_LICENSE_ARCHIVE_DUPLICATE")
            seen.add(member.name)
            inner = PurePosixPath(*relative.parts[1:]).as_posix()
            if not (re.match(r"^(LICENSE|LICENCE|NOTICE|COPYING|COPYRIGHT)([._-]|$)", relative.name.upper())
                    or inner == license_file):
                continue
            if member.isdir():
                continue
            row = {"entry": member.name, "bytes": member.size}
            if not member.isfile():
                row["status"] = "non_regular_unread"
            elif member.size > 2 * 1024 * 1024:
                row["status"] = "too_large_unread"
            else:
                content = archive.extractfile(member).read()
                row.update({"status": "collected_not_reviewed", "sha256": sha(content)})
                row["file"] = "texts/" + row["sha256"] + ".txt"
                row["content"] = content
            documents.append(row)
    return documents


def collect(root, registry, metadata):
    lock_raw = (root / "Cargo.lock").read_bytes()
    inventory_raw = (root / "docs/engineering/dependency-inventory.json").read_bytes()
    locks = {(row["name"], row["version"], row.get("source")): row for row in tomllib.loads(lock_raw.decode())["package"]}
    inventory = [row for row in json.loads(inventory_raw)["dependencies"] if row["ecosystem"] == "cargo"]
    expected = sorted((row["name"], row["version"]) for row in inventory)
    if sorted((row["name"], row["version"]) for row in metadata["packages"]) != expected:
        raise ValueError("CARGO_LICENSE_INVENTORY_STALE")
    declarations = {(row["name"], row["version"]): row["license"] for row in inventory}
    rows, excluded, texts = [], [], {}
    for package in sorted(metadata["packages"], key=lambda row: (row["name"], row["version"])):
        name, version = package["name"], package["version"]
        if not package.get("source"):
            excluded.append({"name": name, "version": version, "reason": "local_package_separate_review", "reviewStatus": "unreviewed"})
            continue
        if package["source"] != SOURCE or not all(COMPONENT.fullmatch(value) for value in (name, version)):
            raise ValueError("CARGO_LICENSE_SOURCE_UNSUPPORTED")
        locked = locks.get((name, version, SOURCE))
        if not locked or not re.fullmatch(r"[0-9a-f]{64}", locked.get("checksum", "")):
            raise ValueError("CARGO_LICENSE_LOCK_MISSING")
        if declarations[(name, version)] != (package.get("license") or "unreviewed"):
            raise ValueError("CARGO_LICENSE_INVENTORY_DECLARATION_STALE")
        manifest = Path(package["manifest_path"])
        relative = manifest.resolve().relative_to((registry / "src").resolve())
        if len(relative.parts) != 3 or relative.parts[1:] != (f"{name}-{version}", "Cargo.toml"):
            raise ValueError("CARGO_LICENSE_CACHE_LAYOUT_INVALID")
        cache = Path("cache") / relative.parts[0] / f"{name}-{version}.crate"
        data = safe_read(registry, cache)
        if sha(data) != locked["checksum"]:
            raise ValueError("CARGO_LICENSE_ARCHIVE_CHECKSUM_MISMATCH")
        documents = archive_documents(data, name, version, package.get("license"), package.get("license_file"))
        for document in documents:
            if "content" in document:
                texts[document["file"]] = document.pop("content")
        rows.append({"name": name, "version": version, "source": SOURCE,
                     "declaredLicense": package.get("license"), "declaredLicenseFile": package.get("license_file"),
                     "archiveSha256": locked["checksum"], "archiveBytes": len(data),
                     "reviewStatus": "unreviewed", "documents": documents})
    locked_registry = {(name, version) for name, version, source in locks if source == SOURCE}
    selected_registry = {(row["name"], row["version"]) for row in rows}
    if not selected_registry.issubset(locked_registry):
        raise ValueError("CARGO_LICENSE_LOCK_COVERAGE_MISMATCH")
    cache_names = {Path(package["manifest_path"]).resolve().relative_to((registry / "src").resolve()).parts[0]
                   for package in metadata["packages"] if package.get("source") == SOURCE}
    unselected = []
    for name, version in sorted(locked_registry - selected_registry):
        checksum = locks[(name, version, SOURCE)]["checksum"]
        if not all(COMPONENT.fullmatch(value) for value in (name, version)) or not re.fullmatch(r"[0-9a-f]{64}", checksum):
            raise ValueError("CARGO_LICENSE_LOCK_INVALID")
        candidates = [Path("cache") / cache_name / f"{name}-{version}.crate" for cache_name in cache_names
                      if (registry / "cache" / cache_name / f"{name}-{version}.crate").is_file()]
        if len(candidates) != 1:
            raise ValueError("CARGO_LICENSE_UNSELECTED_CACHE_MISSING_OR_AMBIGUOUS")
        data = safe_read(registry, candidates[0])
        if sha(data) != checksum:
            raise ValueError("CARGO_LICENSE_ARCHIVE_CHECKSUM_MISMATCH")
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
            package = tomllib.loads(archive.extractfile(f"{name}-{version}/Cargo.toml").read().decode())["package"]
        documents = archive_documents(data, name, version, package.get("license"), package.get("license-file"))
        for document in documents:
            if "content" in document:
                texts[document["file"]] = document.pop("content")
        unselected.append({"name": name, "version": version, "source": SOURCE,
                           "reason": "not_in_current_metadata_resolution", "reviewStatus": "unreviewed",
                           "declaredLicense": package.get("license"), "declaredLicenseFile": package.get("license-file"),
                           "archiveSha256": checksum, "archiveBytes": len(data), "documents": documents})
    summary = {"registryPackages": len(rows), "localPackagesExcluded": len(excluded),
               "withOriginals": sum(any("file" in doc for doc in row["documents"]) for row in rows),
               "documentReferences": sum("file" in doc for row in rows for doc in row["documents"]),
               "uniqueOriginals": len(texts), "unselectedLockedPackages": len(unselected),
               "unselectedDocumentReferences": sum("file" in doc for row in unselected for doc in row["documents"]),
               "reviewed": 0}
    result = {"schemaVersion": 1, "scope": "locked_cargo_registry_license_materials",
              "cargoLockSha256": sha(lock_raw), "inventorySha256": sha(inventory_raw),
              "reviewStatus": "unreviewed", "releaseApproved": False,
              "summary": summary, "dependencies": rows, "excludedLocalPackages": excluded,
              "unselectedLockedPackages": unselected}
    return result, texts


def render_index(manifest):
    lines = ["# Cargo 归档许可原文", "", "全部未审核；不是最终第三方声明或发布批准。", "",
             "只读取 Cargo.lock 校验和匹配的本地 crates.io 归档，不下载或执行包代码。", "",
             "当前 metadata 解析含开发、构建及其他平台依赖；锁文件中未选中的包另列，此目录不证明最终分发制品包含每个包。", "",
             "自有包与本地补丁包另行审核；WebRTC 等预编译原生库及归档内嵌套依赖不由本工具完整覆盖。", ""]
    for row in manifest["dependencies"]:
        lines.extend([f"## {row['name']} {row['version']}", "", "声明：" + (row["declaredLicense"] or "未声明；检查 license-file"), ""])
        for doc in row["documents"]:
            if "file" in doc:
                lines.append(f"- [{doc['sha256'][:12]}]({doc['file']})：`{doc['entry']}`（{doc['bytes']} 字节）")
            else:
                lines.append(f"- 未读取：`{doc['entry']}`（{doc['status']}）")
        if not any("file" in doc for doc in row["documents"]):
            lines.append("缺少已读取原文；不能据声明推定审核通过。")
        lines.append("")
    lines.extend(["## 单独审核的本地包", ""])
    lines.extend(f"- {row['name']} {row['version']}" for row in manifest["excludedLocalPackages"])
    lines.extend(["", "## 锁定但未进入当前 metadata 解析的包", ""])
    for row in manifest["unselectedLockedPackages"]:
        lines.extend([f"### {row['name']} {row['version']}", "", "声明：" + (row["declaredLicense"] or "未声明"), ""])
        for doc in row["documents"]:
            if "file" in doc:
                lines.append(f"- [{doc['sha256'][:12]}]({doc['file']})：`{doc['entry']}`（{doc['bytes']} 字节）")
            else:
                lines.append(f"- 未读取：`{doc['entry']}`（{doc['status']}）")
        if not any("file" in doc for doc in row["documents"]):
            lines.append("缺少已读取原文；未审核。")
        lines.append("")
    return "\n".join(lines).rstrip("\n") + "\n"


def write_or_check(output, manifest, texts, check=False):
    index = render_index(manifest).encode()
    manifest = {**manifest, "indexSha256": sha(index)}
    files = {**texts, "README.md": index,
             "manifest.json": (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode()}
    if check:
        actual = {path.relative_to(output).as_posix() for path in output.rglob("*") if path.is_file()}
        if actual != set(files):
            raise ValueError("CARGO_LICENSE_OUTPUT_FILES_CHANGED")
        for name, data in files.items():
            if safe_read(output, name) != data:
                raise ValueError("CARGO_LICENSE_OUTPUT_CHANGED: " + name)
    else:
        output.mkdir(exist_ok=False)
        (output / "texts").mkdir()
        for name, data in files.items():
            with (output / name).open("xb") as stream:
                stream.write(data)
    return manifest["summary"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--registry-directory", type=Path, default=Path(os.environ.get("CARGO_HOME", Path.home() / ".cargo")) / "registry")
    parser.add_argument("--output-directory", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    root = Path.cwd()
    result = subprocess.run(["cargo", "metadata", "--format-version", "1", "--locked", "--offline"],
                            cwd=root, capture_output=True, check=True)
    manifest, texts = collect(root, args.registry_directory, json.loads(result.stdout))
    print(json.dumps(write_or_check(args.output_directory, manifest, texts, args.check)))
    print("Archive integrity checked; license obligations and release approval remain unreviewed.")


if __name__ == "__main__":
    main()
