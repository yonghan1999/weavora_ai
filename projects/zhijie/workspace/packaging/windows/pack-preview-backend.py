"""将干净 Windows 构建中的本机试用后端打包，不包含账号、数据库或 Maven 缓存路径。"""
import argparse
import os
from pathlib import Path
from zipfile import ZIP_STORED, ZipFile


def pack(workspace, repository, output):
    workspace = workspace.resolve(strict=True)
    repository = repository.resolve(strict=True)
    output = output.absolute()
    if os.name != 'nt' or output.parent.resolve(strict=True) != output.parent or output.drive.upper() == os.environ.get('SystemDrive', 'C:').upper():
        raise ValueError('输出必须是非系统盘已有目录下的新文件')
    if output.suffix.lower() != '.zip':
        raise ValueError('输出文件须为 .zip')
    build = workspace / 'control-plane/target'
    classes = build / 'classes'
    resources = workspace / 'control-plane/src/main/resources'
    scripts = workspace / 'scripts/local-preview'
    files = {}

    def add(source, name, root):
        if not source.is_file() or source.resolve(strict=True) != source or not source.is_relative_to(root):
            raise ValueError(f'构建输入不是预期位置的普通文件：{name}')
        if name.casefold() in files:
            raise ValueError(f'重复的包内文件：{name}')
        files[name.casefold()] = (source, name)

    for name in ('LocalPreview.java', 'CleanCmd.cs', 'start.ps1', 'stop.ps1'):
        add(scripts / name, f'scripts/local-preview/{name}', workspace)
    app = classes / 'app/weavora/zhijie/controlplane'
    if not (app / 'ControlPlaneApplication.class').is_file():
        raise ValueError('未找到已编译的控制面入口')
    for source in sorted(app.rglob('*.class')):
        add(source, source.relative_to(workspace).as_posix(), workspace)
    sources = [resources / name for name in ('application.yml', 'application-runtime.yml', 'logback-spring.xml')]
    for folder, pattern in [('db/migration', '*.sql'), ('mappers', '*.xml')]:
        entries = sorted((resources / folder).glob(pattern))
        if not entries:
            raise ValueError(f'缺少控制面资源：{folder}')
        sources.extend(entries)
    for source in sources:
        packed = classes / source.relative_to(resources)
        add(packed, packed.relative_to(workspace).as_posix(), workspace)
        if source.read_bytes() != packed.read_bytes():
            raise ValueError(f'编译后资源与源码不同：{source.name}')
    dependencies = []
    for entry in (build / 'local-preview-classpath.txt').read_text(encoding='utf-8-sig').strip().split(';'):
        jar = Path(entry)
        if jar.suffix.lower() != '.jar' or not jar.is_absolute():
            raise ValueError('依赖清单必须由 Maven 仓库内的绝对 JAR 路径组成')
        name = f'lib/{jar.name}'
        add(jar, name, repository)
        dependencies.append(name)
    with output.open('xb') as stream:
        try:
            with ZipFile(stream, 'w', compression=ZIP_STORED) as archive:
                for source, name in files.values():
                    archive.write(source, name)
                archive.writestr('control-plane/target/local-preview-classpath.txt', ';'.join(dependencies))
        except BaseException:
            stream.close()
            output.unlink()
            raise


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--workspace', type=Path, required=True)
    parser.add_argument('--repository', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        pack(args.workspace, args.repository, args.output)
    except (OSError, ValueError) as error:
        parser.exit(1, f'后端试用包生成失败：{error}\n')
