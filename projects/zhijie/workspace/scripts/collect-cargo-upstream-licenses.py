"""按已校验 crate 中的精确 Git 提交补收 GitHub 仓库根许可材料。"""

import argparse
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import sys
import tarfile
import tomllib
import urllib.error
import urllib.request

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("cargo_license_materials", Path(__file__).with_name("collect-cargo-license-materials.py"))
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)

LIMIT = 4 * 1024 * 1024
ROOT_CANDIDATES = ("LICENSE", "LICENSE-MIT", "LICENSE-APACHE", "LICENSE.md", "LICENSE.txt", "COPYING", "COPYRIGHT", "NOTICE")


class RecordedFailure(Exception):
    pass


def github_repository(value):
    match = re.fullmatch(r"https://github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\.git)?/?", value or "")
    if not match or any(part in (".", "..") for part in match.groups()):
        return None
    return "/".join(match.groups())


def plan(evidence, registry):
    raw = evidence.read_bytes()
    material = json.loads(raw)
    rows = []
    for item in material["dependencies"]:
        if any("file" in document for document in item["documents"]):
            continue
        name, version = item["name"], item["version"]
        if not all(base.COMPONENT.fullmatch(value) for value in (name, version)):
            raise ValueError("CARGO_UPSTREAM_COORDINATE_INVALID")
        candidates = list((registry / "cache").glob(f"*/{name}-{version}.crate"))
        if len(candidates) != 1:
            raise ValueError("CARGO_UPSTREAM_CACHE_MISSING_OR_AMBIGUOUS")
        data = base.safe_read(registry, candidates[0].relative_to(registry))
        if base.sha(data) != item["archiveSha256"]:
            raise ValueError("CARGO_UPSTREAM_ARCHIVE_CHANGED")
        prefix = f"{name}-{version}/"
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
            package = tomllib.loads(archive.extractfile(prefix + "Cargo.toml").read().decode())["package"]
            if (package["name"], package["version"], package.get("license")) != (name, version, item["declaredLicense"]):
                raise ValueError("CARGO_UPSTREAM_DECLARATION_CHANGED")
            vcs_member = next((member for member in archive.getmembers() if member.name == prefix + ".cargo_vcs_info.json"), None)
            vcs_raw = archive.extractfile(vcs_member).read() if vcs_member and vcs_member.isfile() else None
            vcs = json.loads(vcs_raw) if vcs_raw else {}
        commit = vcs.get("git", {}).get("sha1")
        repository = github_repository(package.get("repository"))
        eligible = bool(repository and re.fullmatch(r"[0-9a-f]{40}", commit or "") and not vcs.get("git", {}).get("dirty"))
        rows.append({"name": name, "version": version, "archiveSha256": item["archiveSha256"],
                     "declaredLicense": item["declaredLicense"], "repositoryDeclaration": package.get("repository"),
                     "githubRepository": repository, "commit": commit, "pathInVcs": vcs.get("path_in_vcs"),
                     "vcsEntrySha256": base.sha(vcs_raw) if vcs_raw else None,
                     "status": "exact_commit_available" if eligible else "exact_source_unresolved"})
    return base.sha(raw), rows


def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": "Zhijie-License-Material-Collector/1", "Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(request, timeout=20) as response:
        if response.url != url:
            raise ValueError("CARGO_UPSTREAM_REDIRECT_REJECTED")
        data = response.read(LIMIT + 1)
        if len(data) > LIMIT:
            raise ValueError("CARGO_UPSTREAM_RESPONSE_TOO_LARGE")
        return data


def failure(error):
    if isinstance(error, RecordedFailure):
        return str(error)
    return "http_" + str(error.code) if isinstance(error, urllib.error.HTTPError) else type(error).__name__


def collect(evidence, registry, download=fetch):
    digest, rows = plan(evidence, registry)
    files, trees, requests = {}, {}, {}
    for row in rows:
        row.update({"reviewStatus": "unreviewed", "documents": []})
        if row["status"] != "exact_commit_available":
            continue
        repository, commit = row["githubRepository"], row["commit"]
        tree_url = f"https://api.github.com/repos/{repository}/git/trees/{commit}"
        if tree_url not in trees:
            try:
                raw = download(tree_url)
                tree = json.loads(raw)
                if tree.get("truncated") or not isinstance(tree.get("tree"), list):
                    raise ValueError("CARGO_UPSTREAM_TREE_INVALID")
                trees[tree_url] = (tree, raw)
            except Exception as error:
                trees[tree_url] = failure(error)
        response = trees[tree_url]
        row["treeUrl"] = tree_url
        if isinstance(response, str):
            row.update({"status": "tree_retrieval_failed", "errorCategory": response})
            members = [{"path": name, "mode": "100644", "type": "blob", "sha": None} for name in ROOT_CANDIDATES]
        else:
            tree, raw = response
            tree_file = "trees/" + base.sha(raw) + ".json"
            files[tree_file] = raw
            row.update({"treeFile": tree_file, "treeSha256": base.sha(raw)})
            members = tree["tree"]
        row["status"] = "root_materials_collected"
        for member in members:
            name = member.get("path", "")
            if not re.match(r"^(LICENSE|LICENCE|NOTICE|COPYING|COPYRIGHT)([._-]|$)", name.upper()):
                continue
            if member.get("type") != "blob" or member.get("mode") != "100644" or "/" in name or "\\" in name:
                continue
            if not re.fullmatch(r"[A-Za-z0-9_.+-]+", name) or (member.get("sha") is not None and not re.fullmatch(r"[0-9a-f]{40}", member["sha"])):
                raise ValueError("CARGO_UPSTREAM_MEMBER_INVALID")
            url = f"https://raw.githubusercontent.com/{repository}/{commit}/{name}"
            document = {"path": name, "sourceUrl": url, "gitBlobSha1": member["sha"], "reviewStatus": "unreviewed"}
            if url not in requests:
                try:
                    content = download(url)
                    blob = hashlib.sha1(b"blob " + str(len(content)).encode() + b"\x00" + content).hexdigest()
                    if member["sha"] is not None and blob != member["sha"]:
                        raise ValueError("CARGO_UPSTREAM_BLOB_MISMATCH")
                    requests[url] = content
                except Exception as error:
                    requests[url] = failure(error)
            content = requests[url]
            if isinstance(content, str):
                document.update({"status": "retrieval_failed", "errorCategory": content})
            else:
                path = "texts/" + base.sha(content) + ".txt"
                files[path] = content
                document.update({"status": "collected_not_reviewed", "file": path, "sha256": base.sha(content), "bytes": len(content)})
            row["documents"].append(document)
        if not any("file" in doc for doc in row["documents"]):
            row["status"] = "root_materials_missing"
    summary = {"dependencies": len(rows), "withUpstreamOriginals": sum(any("file" in doc for doc in row["documents"]) for row in rows),
               "documentReferences": sum("file" in doc for row in rows for doc in row["documents"]),
               "uniqueOriginals": sum(path.startswith("texts/") for path in files), "reviewed": 0}
    manifest = {"schemaVersion": 1, "scope": "exact_commit_root_license_supplement",
                "inputEvidenceSha256": digest, "releaseApproved": False, "reviewStatus": "unreviewed",
                "summary": summary, "dependencies": rows}
    return manifest, files


def index(manifest):
    lines = ["# Cargo 精确提交上游许可补充", "", "全部未审核，不是最终第三方声明或发布批准。", "",
             "来源为锁摘要匹配 crate 内的 repository 和 .cargo_vcs_info.json；只收精确提交根目录的独立许可文件。", "",
             "GitHub tree 与 Git blob SHA-1 仅核对响应一致性，不是独立签名或许可证适用性判断。", "",
             "tree 获取失败时仅尝试固定根文件名；gitBlobSha1=null 表示只有精确提交 URL 和已保存字节摘要，没有 tree/blob 交叉核对。", "",
             "子目录例外、版权、NOTICE、原生库及分发义务继续审核；不把仓库根许可自动应用到每个包。", ""]
    for row in manifest["dependencies"]:
        lines.extend([f"## {row['name']} {row['version']}", "", f"状态：{row['status']}；未审核。", ""])
        if row.get("treeUrl"):
            lines.extend([f"[精确提交来源]({row['treeUrl']})", ""])
        for document in row["documents"]:
            lines.append(f"- [{document['path']}]({document['file']})" if "file" in document else f"- {document['path']}：获取失败")
        lines.append("")
    return "\n".join(lines).rstrip("\n") + "\n"


def write(output, manifest, files):
    output.mkdir(exist_ok=False)
    (output / "texts").mkdir()
    (output / "trees").mkdir()
    rendered = index(manifest).encode()
    manifest["indexSha256"] = base.sha(rendered)
    files = {**files, "README.md": rendered, "manifest.json": (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode()}
    for name, data in files.items():
        with (output / name).open("xb") as stream:
            stream.write(data)


def check(output, evidence, registry):
    # 用保存的响应重新走收集流程，绑定实际归档来源与树/成员摘要，无网络。
    recorded = json.loads((output / "manifest.json").read_bytes())
    responses = {}
    for row in recorded["dependencies"]:
        if "treeFile" in row:
            path = row["treeFile"]
            if not re.fullmatch(r"trees/[0-9a-f]{64}\.json", path):
                raise ValueError("CARGO_UPSTREAM_PATH_INVALID")
            data = base.safe_read(output, path)
            if base.sha(data) != row["treeSha256"] or path != "trees/" + base.sha(data) + ".json":
                raise ValueError("CARGO_UPSTREAM_TREE_CHANGED")
            responses[row["treeUrl"]] = data
        elif "treeUrl" in row:
            responses[row["treeUrl"]] = row["errorCategory"]
        for doc in row["documents"]:
            if "file" in doc:
                path = doc["file"]
                if not re.fullmatch(r"texts/[0-9a-f]{64}\.txt", path):
                    raise ValueError("CARGO_UPSTREAM_PATH_INVALID")
                data = base.safe_read(output, path)
                if base.sha(data) != doc["sha256"] or path != "texts/" + base.sha(data) + ".txt" or len(data) != doc["bytes"]:
                    raise ValueError("CARGO_UPSTREAM_DOCUMENT_CHANGED")
                responses[doc["sourceUrl"]] = data
            else:
                responses[doc["sourceUrl"]] = doc["errorCategory"]
    def replay(url):
        value = responses[url]
        if isinstance(value, str):
            # 保留原失败类别；复核不重试网络或把失败升级为通过。
            if value.startswith("http_"):
                raise urllib.error.HTTPError(url, int(value[5:]), "", {}, None)
            raise RecordedFailure(value)
        return value
    current, files = collect(evidence, registry, replay)
    current["indexSha256"] = base.sha(index(current).encode())
    if current != recorded or (output / "README.md").read_bytes() != index(current).encode():
        raise ValueError("CARGO_UPSTREAM_MATERIALS_CHANGED")
    expected = set(files) | {"README.md", "manifest.json"}
    actual = {p.relative_to(output).as_posix() for p in output.rglob("*") if p.is_file()}
    if expected != actual:
        raise ValueError("CARGO_UPSTREAM_FILES_CHANGED")
    return current["summary"]


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--registry-directory", type=Path, default=Path(os.environ.get("CARGO_HOME", Path.home() / ".cargo")) / "registry")
    parser.add_argument("--evidence", type=Path, default=Path("docs/engineering/cargo-license-materials/manifest.json"))
    parser.add_argument("--output-directory", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    if args.check:
        result = check(args.output_directory, args.evidence, args.registry_directory)
    else:
        if args.output_directory.exists():
            raise FileExistsError(args.output_directory)
        manifest, files = collect(args.evidence, args.registry_directory)
        write(args.output_directory, manifest, files)
        result = manifest["summary"]
    print(json.dumps(result))
    print("Supplemental material integrity only; license review and release approval remain incomplete.")
