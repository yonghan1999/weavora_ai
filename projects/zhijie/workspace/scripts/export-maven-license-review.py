"""导出可离线审阅的 Maven JAR 许可原文，保留来源及未解决项。"""

import argparse
import importlib.util
import io
import json
from pathlib import Path
import re
import sys
import zipfile

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("maven_license_evidence", Path(__file__).with_name("collect-maven-license-evidence.py"))
collector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collector)


def review_rows(recorded):
    rows = []
    for row in recorded["dependencies"]:
        result = {"coordinate": row["coordinate"], "scope": row["scope"],
                  "declaredLicenses": row["declaredLicenses"], "pomSources": row["pomSources"],
                  "jarPath": row["jar"]["path"], "jarSha256": row["jar"].get("sha256"),
                  "reviewStatus": "unreviewed", "documents": []}
        readable = [document for document in row["jar"]["documents"] if document["status"] == "indexed_not_reviewed"]
        for document in readable:
            digest = document["sha256"]
            if not re.fullmatch(r"[0-9a-f]{64}", digest):
                raise ValueError("MAVEN_REVIEW_PATH_INVALID")
            result["documents"].append({**document, "file": "texts/" + digest + ".txt"})
        result["materialStatus"] = "originals_exported" if result["documents"] else "originals_missing"
        rows.append(result)
    return rows


def summarize(rows):
    return {"dependencies": len(rows), "withOriginals": sum(bool(row["documents"]) for row in rows),
            "documentReferences": sum(len(row["documents"]) for row in rows),
            "uniqueOriginals": len({doc["file"] for row in rows for doc in row["documents"]}), "reviewed": 0}


def export(evidence, snapshot, repository, output):
    raw = evidence.read_bytes()
    recorded = json.loads(raw)
    current = collector.collect(snapshot, repository)
    if current != recorded:
        raise ValueError("MAVEN_LICENSE_EVIDENCE_STALE")
    rows = review_rows(recorded)
    # 先验证来源，再创建全新目录；失败不生成完成清单。
    output.mkdir(exist_ok=False)
    (output / "texts").mkdir()
    for result in rows:
        if result["documents"]:
            jar_path = result["jarPath"]
            data = collector.cached_bytes(repository, Path(jar_path))
            if collector.digest(data) != result["jarSha256"]:
                raise ValueError("MAVEN_JAR_CHANGED_DURING_EXPORT")
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                for document in result["documents"]:
                    content = archive.read(document["entry"])
                    digest = collector.digest(content)
                    if digest != document["sha256"] or len(content) != document["bytes"]:
                        raise ValueError("MAVEN_LICENSE_DOCUMENT_CHANGED")
                    destination = output / document["file"]
                    if not destination.exists():
                        with destination.open("xb") as stream:
                            stream.write(content)
    summary = summarize(rows)
    manifest = {"schemaVersion": 1, "scope": "maven_binary_license_review_materials",
                "inputEvidenceSha256": collector.digest(raw), "reviewStatus": "unreviewed",
                "releaseApproved": False, "summary": summary, "dependencies": rows}
    index = render_index(rows)
    (output / "README.md").write_text(index, encoding="utf-8", newline="\n")
    manifest["indexSha256"] = collector.digest(index.encode())
    with (output / "manifest.json").open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(manifest, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    return summary


def render_index(rows):
    lines = ["# Maven 许可原文审阅目录", "", "全部未审核。此目录不是最终第三方声明或发布批准。", "",
             "原文按 SHA-256 去重，字节不变；归档成员路径只作来源信息，不作为输出路径。", "",
             "compile/runtime 仅表示 Maven 依赖范围，不自动证明最终制品包含该组件；test 单列。", ""]
    for scope, title in [("compile", "编译依赖"), ("runtime", "运行依赖"), ("test", "测试依赖"), ("provided", "外部提供"), ("system", "系统依赖")]:
        selected = [row for row in rows if row["scope"] == scope]
        if not selected:
            continue
        lines.extend([f"## {title}（{len(selected)} 项）", ""])
        for row in selected:
            lines.extend(["### " + row["coordinate"], ""])
            lines.append("POM 声明：" + "; ".join(item["name"] for item in row["declaredLicenses"]))
            lines.append("")
            if row["documents"]:
                for document in row["documents"]:
                    lines.append(f"- [{document['sha256'][:12]}]({document['file']})：`{document['entry']}`（{document['bytes']} 字节）")
            else:
                lines.append("缺少 JAR 内独立原文；不能据 POM 名称视为审核通过。")
            lines.append("")
    return "\n".join(lines).rstrip("\n") + "\n"


def verify(output, evidence):
    manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
    if manifest["inputEvidenceSha256"] != collector.digest(evidence.read_bytes()):
        raise ValueError("MAVEN_REVIEW_INPUT_CHANGED")
    if (manifest["schemaVersion"] != 1 or manifest["scope"] != "maven_binary_license_review_materials"
            or manifest["releaseApproved"] is not False or manifest["reviewStatus"] != "unreviewed"):
        raise ValueError("MAVEN_REVIEW_STATUS_INVALID")
    original = json.loads(evidence.read_bytes())
    expected_rows = review_rows(original)
    for row in manifest["dependencies"]:
        if row["reviewStatus"] != "unreviewed":
            raise ValueError("MAVEN_REVIEW_STATUS_INVALID")
        for document in row["documents"]:
            expected = "texts/" + document["sha256"] + ".txt"
            if document["file"] != expected or not re.fullmatch(r"[0-9a-f]{64}", document["sha256"]):
                raise ValueError("MAVEN_REVIEW_PATH_INVALID")
            path = output / expected
            if not path.resolve().is_relative_to(output.resolve()):
                raise ValueError("MAVEN_REVIEW_PATH_INVALID")
            data = path.read_bytes()
            if collector.digest(data) != document["sha256"] or len(data) != document["bytes"]:
                raise ValueError("MAVEN_REVIEW_DOCUMENT_CHANGED")
    # 导出清单不能通过删除原文或改写来源来与自身校验通过。
    if manifest["dependencies"] != expected_rows:
        raise ValueError("MAVEN_REVIEW_SOURCES_CHANGED")
    summary = summarize(expected_rows)
    if manifest["summary"] != summary:
        raise ValueError("MAVEN_REVIEW_SUMMARY_CHANGED")
    expected_files = {doc["file"] for row in expected_rows for doc in row["documents"]}
    actual_files = {path.relative_to(output).as_posix() for path in (output / "texts").rglob("*")}
    if actual_files != expected_files:
        raise ValueError("MAVEN_REVIEW_FILES_CHANGED")
    index = (output / "README.md").read_bytes()
    if index != render_index(expected_rows).encode() or collector.digest(index) != manifest["indexSha256"]:
        raise ValueError("MAVEN_REVIEW_INDEX_CHANGED")
    return summary


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, default=Path("docs/engineering/maven-license-evidence.json"))
    parser.add_argument("--snapshot", type=Path, default=Path("docs/engineering/maven-dependencies.txt"))
    parser.add_argument("--repository", type=Path)
    parser.add_argument("--output-directory", type=Path, required=True)
    parser.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    if args.verify:
        result = verify(args.output_directory, args.evidence)
    else:
        if not args.repository:
            parser.error("--repository is required for export")
        result = export(args.evidence, args.snapshot, args.repository, args.output_directory)
    print(json.dumps(result))
    print("Material integrity checked; license review and release approval remain incomplete.")
