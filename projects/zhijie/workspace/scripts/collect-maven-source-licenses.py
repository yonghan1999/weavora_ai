"""从 Maven Central 精确版本源码包补收未内嵌的许可证材料，保留未审核状态。"""

import argparse
import concurrent.futures
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import urllib.error
import urllib.request
import zipfile


BASE = "https://repo.maven.apache.org/maven2/"
LIMIT = 16 * 1024 * 1024


def fetch(url, limit=LIMIT):
    with urllib.request.urlopen(url, timeout=15) as response:
        if not response.url.startswith(BASE):
            raise ValueError("SOURCE_REDIRECT_OUTSIDE_CENTRAL")
        data = response.read(limit + 1)
        if len(data) > limit:
            raise ValueError("SOURCE_SIZE_LIMIT")
        return data


def obtain(row):
    parts = row["coordinate"].split(":")
    group, artifact = parts[:2]
    version = parts[-2]
    if not all(re.fullmatch(r"[A-Za-z0-9_+-][A-Za-z0-9_.+-]*", value) for value in (group, artifact, version)):
        raise ValueError("SOURCE_COORDINATE_INVALID")
    path = "/".join([*group.split("."), artifact, version, f"{artifact}-{version}-sources.jar"])
    url = BASE + path
    result = {"coordinate": row["coordinate"], "scope": row["scope"],
              "declaredLicenses": row["declaredLicenses"], "sourceUrl": url,
              "reviewStatus": "unreviewed", "documents": []}
    try:
        archive = fetch(url)
    except urllib.error.HTTPError as error:
        result["status"] = f"source_http_{error.code}"
        return result, None, []
    except (OSError, ValueError):
        result["status"] = "source_fetch_failed"
        return result, None, []
    result.update({"sourceSha256": hashlib.sha256(archive).hexdigest(), "sourceBytes": len(archive)})
    # Central 的旁路校验和只证明传输一致，不等价于上游签名或独立来源认证。
    for algorithm, width in [("sha256", 64), ("sha1", 40)]:
        try:
            checksum = fetch(url + "." + algorithm, 4096).decode("ascii").strip().split()[0].lower()
        except urllib.error.HTTPError as error:
            if error.code == 404:
                continue
            result["status"] = "checksum_fetch_failed"
            return result, None, []
        except (OSError, UnicodeError, IndexError, ValueError):
            result["status"] = "checksum_fetch_failed"
            return result, None, []
        if not re.fullmatch(f"[0-9a-f]{{{width}}}", checksum) or hashlib.new(algorithm, archive).hexdigest() != checksum:
            result["status"] = "checksum_mismatch"
            return result, None, []
        result["centralChecksum"] = {"algorithm": algorithm, "value": checksum, "url": url + "." + algorithm}
        break
    else:
        result["status"] = "checksum_missing"
        return result, None, []
    contents = []
    try:
        with zipfile.ZipFile(io.BytesIO(archive)) as source:
            for entry in sorted(source.infolist(), key=lambda item: item.filename):
                if entry.is_dir() or not re.match(r"^(LICENSE|LICENCE|NOTICE|COPYING)([._-]|$)", PurePosixPath(entry.filename).name.upper()):
                    continue
                item = {"entry": entry.filename, "bytes": entry.file_size}
                if entry.file_size > 2 * 1024 * 1024:
                    item["status"] = "too_large_unread"
                else:
                    data = source.read(entry)
                    sha = hashlib.sha256(data).hexdigest()
                    item.update({"sha256": sha, "file": f"texts/{sha}.txt", "status": "collected_not_reviewed"})
                    contents.append((sha, data))
                result["documents"].append(item)
        result["status"] = "documents_collected" if contents else "no_source_license_documents"
    except (zipfile.BadZipFile, RuntimeError):
        result["status"] = "source_archive_invalid"
        contents = []
    return result, archive, contents


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, default=Path("docs/engineering/maven-license-evidence.json"))
    parser.add_argument("--output-directory", type=Path, required=True)
    parser.add_argument("--cache-directory", type=Path, required=True)
    args = parser.parse_args()
    raw = args.evidence.read_bytes()
    rows = [row for row in json.loads(raw)["dependencies"] if not row["jar"]["documents"]]
    # 新目录专用；不覆盖、替换或清理用户既有文件。
    args.output_directory.mkdir(parents=False, exist_ok=False)
    args.cache_directory.mkdir(parents=False, exist_ok=False)
    texts = args.output_directory / "texts"
    texts.mkdir()
    results = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
        for result, archive, documents in executor.map(obtain, rows):
            if archive is not None:
                cache_file = args.cache_directory / (result["sourceSha256"] + ".jar")
                if not cache_file.exists():
                    with cache_file.open("xb") as stream:
                        stream.write(archive)
            for sha, content in documents:
                path = texts / (sha + ".txt")
                if not path.exists():
                    with path.open("xb") as stream:
                        stream.write(content)
            results.append(result)
            print(json.dumps({"coordinate": result["coordinate"], "status": result["status"]}), flush=True)
    summary = {"dependencies": len(results), "withCollectedDocuments": sum(result["status"] == "documents_collected" for result in results),
               "uniqueTexts": len(list(texts.iterdir())), "reviewed": 0}
    manifest = {"schemaVersion": 1, "scope": "maven_central_source_license_materials", "inputSha256": hashlib.sha256(raw).hexdigest(),
                "reviewStatus": "unreviewed", "releaseApproved": False,
                "limitations": ["Exact-version sources are supplementary; binary obligations and shaded/native components require review",
                                "Central sidecar checksums are not independent signatures or legal approval",
                                "Missing source packages or license members remain unresolved"],
                "summary": summary, "dependencies": results}
    with (args.output_directory / "manifest.json").open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(manifest, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print(json.dumps(summary))


if __name__ == "__main__":
    main()
