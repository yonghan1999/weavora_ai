"""保存剩余 Cargo 许可缺口的精确归档源码头/说明声明，不作适用性判断。"""

import argparse
import importlib.util
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import sys
import tarfile

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("cargo_license_materials", Path(__file__).with_name("collect-cargo-license-materials.py"))
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)

LEGAL = re.compile(rb"licen[sc]e|copyright|copyleft|permission is hereby|mozilla public|do what the", re.I)


def leading_comment(data):
    # 只保留从文件开头起的完整注释，不把代码中的关键词当成许可头。
    if data.startswith(b"/*"):
        end = data.find(b"*/", 2)
        return data[:end + 2] if end >= 0 else b""
    lines = data.splitlines(keepends=True)
    count = 0
    for line in lines:
        if not line.startswith(b"//"):
            break
        count += len(line)
    return data[:count]


def collect(evidence, registry):
    raw = evidence.read_bytes()
    upstream = json.loads(raw)
    dependencies, texts = [], {}
    for package in upstream["dependencies"]:
        if any("file" in document for document in package["documents"]):
            continue
        name, version = package["name"], package["version"]
        if not all(base.COMPONENT.fullmatch(value) for value in (name, version)):
            raise ValueError("CARGO_HEADER_COORDINATE_INVALID")
        candidates = list((registry / "cache").glob(f"*/{name}-{version}.crate"))
        if len(candidates) != 1:
            raise ValueError("CARGO_HEADER_CACHE_MISSING_OR_AMBIGUOUS")
        data = base.safe_read(registry, candidates[0].relative_to(registry))
        if base.sha(data) != package["archiveSha256"]:
            raise ValueError("CARGO_HEADER_ARCHIVE_CHANGED")
        row = {"name": name, "version": version, "archiveSha256": package["archiveSha256"],
               "declaredLicense": package["declaredLicense"], "reviewStatus": "unreviewed", "documents": []}
        prefix = f"{name}-{version}"
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
            seen = set()
            for member in sorted(archive.getmembers(), key=lambda value: value.name):
                path = PurePosixPath(member.name)
                if path.is_absolute() or ".." in path.parts or not path.parts or path.parts[0] != prefix:
                    raise ValueError("CARGO_HEADER_ARCHIVE_PATH_INVALID")
                if member.name in seen:
                    raise ValueError("CARGO_HEADER_DUPLICATE_MEMBER")
                seen.add(member.name)
                if not member.isfile() or member.size > 2 * 1024 * 1024:
                    continue
                is_source = path.suffix == ".rs"
                is_document = path.name.upper() in ("README", "README.MD", "README.TXT", "CHANGELOG.MD", "AUTHORS", "AUTHORS.MD", "AUTHORS.TXT")
                if not (is_source or is_document):
                    continue
                original = archive.extractfile(member).read()
                content = leading_comment(original) if is_source else original
                if not LEGAL.search(content):
                    continue
                digest = base.sha(content)
                relative = "texts/" + digest + ".txt"
                texts[relative] = content
                row["documents"].append({"entry": member.name, "entrySha256": base.sha(original), "entryBytes": len(original),
                                         "kind": "leading_source_comment" if is_source else "full_context_document",
                                         "offset": 0, "bytes": len(content), "sha256": digest, "file": relative,
                                         "status": "declaration_collected_not_reviewed"})
        row["status"] = "declarations_collected" if row["documents"] else "no_matching_declarations"
        dependencies.append(row)
    manifest = {"schemaVersion": 1, "scope": "remaining_cargo_archive_declaration_supplement",
                "inputEvidenceSha256": base.sha(raw), "releaseApproved": False, "reviewStatus": "unreviewed",
                "summary": {"dependencies": len(dependencies), "withDeclarations": sum(bool(row["documents"]) for row in dependencies),
                            "documentReferences": sum(len(row["documents"]) for row in dependencies),
                            "uniqueOriginals": len(texts), "reviewed": 0}, "dependencies": dependencies}
    return manifest, texts


def index(manifest):
    lines = ["# Cargo 剩余缺口的归档声明", "", "全部未审核；此目录不是完整许可证、最终第三方声明或发布批准。", "",
             "源码仅保存从字节零开始的完整首注释；README/CHANGELOG/AUTHORS 保存全文，保留上下文。", "",
             "每份材料绑定已校验 crate、成员全文摘要和字节范围；不会将单个文件声明自动应用于整个包或内嵌原生库。", "",
             "关键词匹配仅筛选审核材料，匹配结果不代表条款完整或法律适用性。双许可表达式未替产品选择。", ""]
    for row in manifest["dependencies"]:
        lines.extend([f"## {row['name']} {row['version']}", "", f"状态：{row['status']}；未审核。", ""])
        for document in row["documents"]:
            lines.append(f"- [{document['sha256'][:12]}]({document['file']})：`{document['entry']}`（{document['kind']}，偏移 0，{document['bytes']} 字节）")
        if not row["documents"]:
            lines.append("未找到匹配声明，保留缺口。")
        lines.append("")
    return "\n".join(lines).rstrip("\n") + "\n"


def write_or_check(output, manifest, texts, check=False):
    rendered = index(manifest).encode()
    manifest = {**manifest, "indexSha256": base.sha(rendered)}
    files = {**texts, "README.md": rendered, "manifest.json": (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode()}
    if check:
        actual = {path.relative_to(output).as_posix() for path in output.rglob("*") if path.is_file()}
        if actual != set(files):
            raise ValueError("CARGO_HEADER_FILES_CHANGED")
        for path, data in files.items():
            if base.safe_read(output, path) != data:
                raise ValueError("CARGO_HEADER_MATERIALS_CHANGED: " + path)
    else:
        output.mkdir(exist_ok=False)
        (output / "texts").mkdir()
        for path, data in files.items():
            with (output / path).open("xb") as stream:
                stream.write(data)
    return manifest["summary"]


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--registry-directory", type=Path, default=Path(os.environ.get("CARGO_HOME", Path.home() / ".cargo")) / "registry")
    parser.add_argument("--evidence", type=Path, default=Path("docs/engineering/cargo-upstream-license-materials/manifest.json"))
    parser.add_argument("--output-directory", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    manifest, texts = collect(args.evidence, args.registry_directory)
    print(json.dumps(write_or_check(args.output_directory, manifest, texts, args.check)))
    print("Declaration material integrity only; full license and distribution review remains incomplete.")
