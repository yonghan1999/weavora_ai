"""从已核对的两个候选制品制作可移位的本机试用包，不读取试用数据库或浏览器配置。"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import tarfile
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo


def checked_bytes(path, digest):
    if path.resolve(strict=True) != path or not path.is_file():
        raise ValueError('候选文件不是普通本地文件')
    data = path.read_bytes()
    if hashlib.sha256(data).hexdigest() != digest:
        raise ValueError(f'候选摘要不符：{path.name}')
    return data


def include(archive, name, data):
    entry = ZipInfo(name)
    entry.create_system = 3
    entry.external_attr = 0o100644 << 16
    archive.writestr(entry, data, compress_type=ZIP_DEFLATED, compresslevel=5)


def verify_native_payload(jar, payload, materials):
    expected = {}
    available = {material['file'] for material in materials}
    for component in payload['components']:
        if not component['documents'] or not set(component['documents']) <= available or not component['members']:
            raise ValueError('原生组件材料缺失')
        if expected.keys() & component['members'].keys():
            raise ValueError('原生组件文件重复归属')
        expected.update(component['members'])
    for item in payload['unidentified']:
        if item['file'] in expected:
            raise ValueError('原生文件状态冲突')
        expected[item['file']] = item['sha256']
    with ZipFile(jar) as wrapper:
        if wrapper.namelist().count(payload['archive']) != 1:
            raise ValueError('数据库原生归档缺失或重复')
        content = wrapper.read(payload['archive'])
    if hashlib.sha256(content).hexdigest() != payload['sha256']:
        raise ValueError('数据库原生归档摘要不符')
    seen = set()
    with tarfile.open(fileobj=io.BytesIO(content), mode='r|xz') as archive:
        for member in archive:
            if member.name not in expected:
                continue
            if member.name in seen or not member.isfile():
                raise ValueError('数据库原生归档条目不是唯一普通文件')
            with archive.extractfile(member) as source:
                if hashlib.file_digest(source, 'sha256').hexdigest() != expected[member.name]:
                    raise ValueError('数据库原生文件摘要不符')
            seen.add(member.name)
    if seen != expected.keys():
        raise ValueError('数据库原生文件缺失')


def include_notices(archive, backend, workspace, source_digest):
    directory = workspace / 'packaging/windows/trial-notices'
    data = checked_bytes(directory / 'sources.json', source_digest)
    sources = json.loads(data)
    binary_index = workspace / 'docs/engineering/maven-binary-license-originals/manifest.json'
    indexed = {row['jarSha256']: row for row in json.loads(binary_index.read_bytes())['dependencies']}
    required = set()
    seen = set()
    for item in backend.infolist():
        if not item.filename.lower().endswith('.jar'):
            continue
        name = Path(item.filename).name
        if item.filename != 'lib/' + name or name.casefold() in seen:
            raise ValueError(f'依赖路径重复或不符：{name}')
        seen.add(name.casefold())
        with backend.open(item) as member:
            digest = hashlib.file_digest(member, 'sha256').hexdigest()
        row = indexed.get(digest)
        if not row or Path(row['jarPath']).name != name:
            raise ValueError(f'实际 JAR 与许可索引不符：{name}')
        if not row['documents']:
            required.add(name)
            entry = sources['jars'].get(name)
            if not entry or entry['sha256'] != digest or not entry['materials']:
                raise ValueError(f'缺少与依赖对应的补充材料：{name}')
            if 'nativePayload' in entry:
                with backend.open(item) as jar:
                    verify_native_payload(jar, entry['nativePayload'], entry['materials'])
    if required != sources['jars'].keys():
        raise ValueError('补充材料与实际随包依赖集合不符')
    referenced = {material['file'] for entry in sources['jars'].values() for material in entry['materials']}
    if referenced != sources['documents'].keys():
        raise ValueError('补充原文与来源索引不符')
    for name, digest in sources['documents'].items():
        if Path(name).name != name or not name.endswith('.txt'):
            raise ValueError('补充原文路径不符')
        include(archive, 'third-party/' + name, checked_bytes(directory / name, digest))
    include(archive, 'third-party/sources.json', data)


def pack(workspace, output):
    output = output.absolute()
    target = (workspace / 'target').resolve(strict=True)
    if (os.name != 'nt' or target not in output.parents or output.suffix.lower() != '.zip'
            or output.parent.resolve(strict=True) != output.parent or output.drive.upper() == os.environ.get('SystemDrive', 'C:').upper()):
        raise ValueError('输出须位于非系统盘工作区 target 的已有目录下')
    packaging = workspace / 'packaging/windows'
    spec = json.loads((packaging / 'trial-kit.json').read_bytes())
    manifest = workspace / spec['desktopManifest']
    data = checked_bytes(manifest, spec['desktopManifestSha256'])
    product = json.loads(data)['productFiles']
    if any(Path(name).name != name for name in product):
        raise ValueError('桌面清单路径不符')
    with (workspace / spec['backendZip']).open('rb') as source:
        if hashlib.file_digest(source, 'sha256').hexdigest() != spec['backendZipSha256']:
            raise ValueError('后端包摘要不符')
        with ZipFile(source) as backend, output.open('xb') as stream:
            try:
                with ZipFile(stream, 'w') as archive:
                    include_notices(archive, backend, workspace, spec['noticeSourcesSha256'])
                    include(archive, 'README.md', (packaging / 'trial-kit-guide.md').read_bytes())
                    include(archive, 'ops/database/cold-backup.py', (workspace / 'ops/database/cold-backup.py').read_bytes())
                    include(archive, 'scripts/local-preview/trial-desktop.py', (workspace / 'scripts/local-preview/trial-desktop.py').read_bytes())
                    include(archive, 'target/desktop/manifest.json', data)
                    for name, digest in sorted(product.items()):
                        include(archive, 'target/desktop/app/' + name, checked_bytes(manifest.parent / 'app' / name, digest))
                    for name in sorted(backend.namelist()):
                        include(archive, 'backend/' + name, backend.read(name))
            except BaseException:
                stream.close()
                output.unlink()
                raise


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    pack(Path(__file__).resolve().parents[2], args.output)
