"""离线比对 Windows 试用包内 PostgreSQL 的全部普通文件与 EDB 原始发行包；不解压或执行程序。"""
import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import tarfile
from zipfile import BadZipFile, ZipFile


JAR = 'backend/lib/embedded-postgres-binaries-windows-amd64-17.5.0.jar'
PAYLOAD = 'postgres-windows-x86_64.txz'
EDB_SHA256 = '795196df1b2855fd0c7fb52629c6cc16acaa85819912e732bd4c46863e77eb30'


def compare(payload, upstream):
    members = {}
    for item in upstream.infolist():
        if item.filename in members:
            raise ValueError(f'上游 ZIP 成员重复：{item.filename}')
        members[item.filename] = item
    files = {}
    seen = set()
    with tarfile.open(fileobj=payload, mode='r|xz') as archive:
        for item in archive:
            name = item.name
            path = PurePosixPath(name)
            if (not path.parts or path.is_absolute() or '..' in path.parts or ':' in name or '\\' in name
                    or path.as_posix() != name or name.casefold() in seen):
                raise ValueError(f'载荷成员路径异常或重复：{name}')
            seen.add(name.casefold())
            if item.isdir():
                continue
            origin = members.get('pgsql/' + name)
            if not item.isfile() or origin is None or origin.is_dir() or origin.file_size != item.size:
                raise ValueError(f'缺失或不匹配的普通文件：{name}')
            with archive.extractfile(item) as source, upstream.open(origin) as reference:
                digest = hashlib.file_digest(source, 'sha256').hexdigest()
                if digest != hashlib.file_digest(reference, 'sha256').hexdigest():
                    raise ValueError(f'文件内容与发行包不符：{name}')
            files[name] = digest
    if not files:
        raise ValueError('数据库载荷不含普通文件')
    return files


def verify(kit_path, edb_path, kit_sha256):
    with kit_path.open('rb') as kit_file, edb_path.open('rb') as edb_file:
        for stream, digest in [(kit_file, kit_sha256), (edb_file, EDB_SHA256)]:
            if hashlib.file_digest(stream, 'sha256').hexdigest() != digest:
                raise ValueError('试用包或原始发行包摘要不符')
            stream.seek(0)
        with ZipFile(kit_file) as kit, ZipFile(edb_file) as upstream:
            if kit.namelist().count(JAR) != 1:
                raise ValueError('整包中的数据库 JAR 不唯一或不存在')
            raw = kit.read(JAR)
            with ZipFile(io.BytesIO(raw)) as jar:
                if jar.namelist().count(PAYLOAD) != 1:
                    raise ValueError('JAR 中的数据库归档不唯一或不存在')
                with jar.open(PAYLOAD) as payload:
                    files = compare(payload, upstream)
    return {'kitSha256': kit_sha256, 'edbSha256': EDB_SHA256, 'jarSha256': hashlib.sha256(raw).hexdigest(),
            'matchedFiles': len(files), 'files': files}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('kit', type=Path)
    parser.add_argument('edb', type=Path)
    parser.add_argument('--kit-sha256', required=True)
    args = parser.parse_args()
    try:
        print(json.dumps(verify(args.kit, args.edb, args.kit_sha256), indent=2, sort_keys=True))
    except (ValueError, OSError, BadZipFile, tarfile.TarError) as error:
        parser.exit(1, f'发行来源核查失败：{error}\n')
