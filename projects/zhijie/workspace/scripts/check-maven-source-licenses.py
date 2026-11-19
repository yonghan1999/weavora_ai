"""复核已收集材料、来源关联和可选本机源码包，不执行网络下载。"""

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import zipfile


def sha(data):
    return hashlib.sha256(data).hexdigest()


def check(root, evidence, cache=None):
    def read(name):
        return json.loads((root / name).read_text(encoding="utf-8"))
    sources = read("manifest.json")
    assert sources["inputSha256"] == sha(evidence.read_bytes()), "input evidence changed"
    assert sources["releaseApproved"] is False and sources["reviewStatus"] == "unreviewed"
    previous = json.loads(evidence.read_bytes())
    expected = [row["coordinate"] for row in previous["dependencies"] if not row["jar"]["documents"]]
    assert [row["coordinate"] for row in sources["dependencies"]] == expected
    count = 0
    for manifest in (sources, read("official-texts.json"), read("logback-source-headers.json")):
        assert manifest["releaseApproved"] is False
        rows = manifest.get("documents", manifest.get("dependencies"))
        for row in rows:
            assert row["reviewStatus"] == "unreviewed"
            documents = row.get("documents", []) if "coordinate" in row and "sourceUrl" in row else [row]
            for document in documents:
                if "file" not in document:
                    continue
                relative = PurePosixPath(document["file"])
                assert relative.parts[0] == "texts" and len(relative.parts) == 2
                path = root.joinpath(*relative.parts)
                assert path.resolve().is_relative_to(root.resolve())
                data = path.read_bytes()
                assert len(data) == document["bytes"] and sha(data) == document["sha256"], "material hash mismatch"
                count += 1
    if cache:
        for row in sources["dependencies"]:
            if "centralChecksum" not in row:
                continue
            data = (cache / (row["sourceSha256"] + ".jar")).read_bytes()
            assert sha(data) == row["sourceSha256"] and len(data) == row["sourceBytes"]
            checksum = row["centralChecksum"]
            assert hashlib.new(checksum["algorithm"], data).hexdigest() == checksum["value"]
        for row in read("logback-source-headers.json")["documents"]:
            with zipfile.ZipFile(cache / (row["sourceSha256"] + ".jar")) as archive:
                data = archive.read(row["entry"])
                assert sha(data[:data.index(b"*/") + 2]) == row["sha256"]
    return {"dependencies": len(expected), "materialReferencesChecked": count,
            "cachedSourcesChecked": bool(cache), "releaseApproved": False}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, default=Path("docs/engineering/maven-source-license-materials"))
    parser.add_argument("--evidence", type=Path, default=Path("docs/engineering/maven-license-evidence.json"))
    parser.add_argument("--cache-directory", type=Path)
    args = parser.parse_args()
    print(json.dumps(check(args.directory, args.evidence, args.cache_directory)))
