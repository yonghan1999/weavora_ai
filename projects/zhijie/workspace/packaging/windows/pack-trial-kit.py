"""从已核对的两个候选制品制作可移位的本机试用包，不读取试用数据库或浏览器配置。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo


TRIAL_NOTICES = {
    'HikariCP-6.3.3-LICENSE.txt': ('lib/HikariCP-6.3.3.jar',
        '709f378c05756280939ce50fc1b1f1a53bb8e1899dc1b249f21f12703640b48b',
        '73ba74dfaa520b49a401b5d21459a8523a146f3b7518a833eea5efa85130bf68'),
    'postgresql-17.5-COPYRIGHT.txt': ('lib/embedded-postgres-binaries-windows-amd64-17.5.0.jar',
        '06ced3b3267c569ffb3f85544aac0075926dd81bf78bd5247abb1aa9fe994d6b',
        'e3822c4797fadcab31a3fc73f75c28ac20c73d72b565da91e9974cf9398ef4d2'),
}


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
                    for notice, (jar, jar_digest, notice_digest) in TRIAL_NOTICES.items():
                        if backend.namelist().count(jar) != 1:
                            raise ValueError(f'许可材料对应的依赖不唯一或不存在：{jar}')
                        with backend.open(jar) as member:
                            if hashlib.file_digest(member, 'sha256').hexdigest() != jar_digest:
                                raise ValueError(f'许可材料对应的依赖摘要不符：{jar}')
                        include(archive, 'third-party/' + notice, checked_bytes(packaging / 'trial-notices' / notice, notice_digest))
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
