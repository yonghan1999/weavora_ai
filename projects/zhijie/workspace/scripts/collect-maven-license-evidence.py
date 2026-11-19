"""从精确坐标的本地缓存收集声明和原文索引，不作许可证批准。"""

import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import xml.etree.ElementTree as ET
import zipfile


NS = {"m": "http://maven.apache.org/POM/4.0.0"}
COMPONENT = re.compile(r"[A-Za-z0-9_+-][A-Za-z0-9_.+-]*\Z")


def digest(data):
    return hashlib.sha256(data).hexdigest()


def coordinates(line):
    parts = line.strip().split(":")
    if len(parts) not in (5, 6) or not all(COMPONENT.fullmatch(part) for part in parts):
        raise ValueError("MAVEN_COORDINATE_INVALID")
    group, artifact, kind = parts[:3]
    version, scope = parts[-2:]
    if kind != "jar" or scope not in ("compile", "runtime", "test", "provided", "system"):
        raise ValueError("MAVEN_COORDINATE_UNSUPPORTED")
    return group, artifact, version, parts[3] if len(parts) == 6 else None, scope


def artifact_path(group, artifact, version, suffix):
    if not all(COMPONENT.fullmatch(value) for value in (group, artifact, version)):
        raise ValueError("MAVEN_PARENT_COORDINATE_UNRESOLVED")
    return PurePosixPath(*group.split("."), artifact, version, f"{artifact}-{version}{suffix}")


def cached_bytes(repository, relative):
    path = repository.joinpath(*relative.parts)
    # 缓存中的链接也不能把读取引到指定仓库之外。
    if not path.resolve().is_relative_to(repository.resolve()):
        raise ValueError("MAVEN_CACHE_PATH_OUTSIDE_REPOSITORY")
    return path.read_bytes()


def declarations(repository, group, artifact, version):
    chain = []
    seen = set()
    for _ in range(32):
        identity = group, artifact, version
        if identity in seen:
            return [], chain, "parent_cycle"
        seen.add(identity)
        try:
            relative = artifact_path(group, artifact, version, ".pom")
        except ValueError:
            return [], chain, "parent_coordinate_unresolved"
        try:
            data = cached_bytes(repository, relative)
        except FileNotFoundError:
            return [], chain, "pom_missing"
        try:
            root = ET.fromstring(data)
        except ET.ParseError:
            return [], chain, "pom_invalid"
        if root.tag == "project":
            namespace = {}
            prefix = ""
        elif root.tag == f"{{{NS['m']}}}project":
            namespace = NS
            prefix = "m:"
        else:
            return [], chain, "pom_namespace_unsupported"
        chain.append({"path": str(relative), "sha256": digest(data)})
        licenses = []
        for item in root.findall(f"{prefix}licenses/{prefix}license", namespace):
            license = {field: (item.findtext(f"{prefix}{field}", "", namespace) or "").strip()
                       for field in ("name", "url", "distribution", "comments")}
            if any("${" in value for value in license.values()):
                return [], chain, "license_property_unresolved"
            if not license["name"]:
                return [], chain, "license_name_missing"
            licenses.append({key: value for key, value in license.items() if value})
        if licenses:
            return licenses, chain, "declared" if len(chain) == 1 else "inherited_declaration"
        parent = root.find(f"{prefix}parent", namespace)
        if parent is None:
            return [], chain, "license_not_declared"
        group, artifact, version = (
            (parent.findtext(f"{prefix}{field}", "", namespace) or "").strip()
            for field in ("groupId", "artifactId", "version")
        )
    return [], chain, "parent_depth_exceeded"


def jar_documents(repository, group, artifact, version, classifier):
    suffix = (f"-{classifier}" if classifier else "") + ".jar"
    relative = artifact_path(group, artifact, version, suffix)
    try:
        data = cached_bytes(repository, relative)
    except FileNotFoundError:
        return {"path": str(relative), "status": "missing", "documents": []}
    result = {"path": str(relative), "sha256": digest(data), "bytes": len(data), "documents": []}
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as jar:
            for entry in sorted(jar.infolist(), key=lambda value: value.filename):
                name = PurePosixPath(entry.filename).name.upper()
                if entry.is_dir() or not re.match(r"^(LICENSE|LICENCE|NOTICE|COPYING)([._-]|$)", name):
                    continue
                document = {"entry": entry.filename, "bytes": entry.file_size}
                # 只读索引，不向文件系统提取归档路径；过大成员仍保留缺口。
                if entry.file_size > 2 * 1024 * 1024:
                    document["status"] = "too_large_unread"
                else:
                    content = jar.read(entry)
                    document.update({"sha256": digest(content), "status": "indexed_not_reviewed"})
                result["documents"].append(document)
        result["status"] = "indexed" if result["documents"] else "no_embedded_license_documents"
    except (zipfile.BadZipFile, RuntimeError):
        result["status"] = "jar_unreadable"
    return result


def collect(snapshot, repository):
    data = snapshot.read_bytes()
    dependencies = []
    for line in data.decode("utf-8").splitlines():
        if not line.strip():
            continue
        group, artifact, version, classifier, scope = coordinates(line)
        licenses, sources, status = declarations(repository, group, artifact, version)
        dependencies.append({"coordinate": line, "scope": scope,
                             "declarationStatus": status, "declaredLicenses": licenses,
                             "pomSources": sources,
                             "jar": jar_documents(repository, group, artifact, version, classifier),
                             "reviewStatus": "unreviewed"})
    if not dependencies or len({row["coordinate"] for row in dependencies}) != len(dependencies):
        raise ValueError("MAVEN_SNAPSHOT_EMPTY_OR_DUPLICATED")
    return {"schemaVersion": 1, "scope": "offline_maven_license_evidence",
            "snapshotSha256": digest(data), "reviewStatus": "unreviewed",
            "releaseApproved": False,
            "limitations": ["POM declarations are not a legal determination",
                            "Parent declarations are inherited without executing Maven model interpolation",
                            "JAR documents are indexed, not interpreted or approved",
                            "Shaded dependencies, native archives and full distribution obligations require separate review",
                            "Missing local cache inputs remain explicit; no network downloads"],
            "summary": {"dependencies": len(dependencies),
                        "withDeclarations": sum(bool(row["declaredLicenses"]) for row in dependencies),
                        "withEmbeddedDocuments": sum(bool(row["jar"]["documents"]) for row in dependencies),
                        "reviewed": 0}, "dependencies": dependencies}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--snapshot", type=Path, default=Path("docs/engineering/maven-dependencies.txt"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    result = collect(args.snapshot, args.repository)
    output = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.check:
        if args.output.read_text(encoding="utf-8") != output:
            raise ValueError("MAVEN_LICENSE_EVIDENCE_STALE")
    else:
        # 不覆盖既有材料；刷新前须保留旧版并选择新路径。
        with args.output.open("x", encoding="utf-8", newline="\n") as stream:
            stream.write(output)
    print(json.dumps(result["summary"], ensure_ascii=False))
    print("Evidence collected; license review and release approval remain incomplete.")


if __name__ == "__main__":
    main()
