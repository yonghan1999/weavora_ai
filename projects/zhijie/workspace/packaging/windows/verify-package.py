"""只读核对 NSIS 包中的产品文件与指定构建输入，不执行安装器。"""

import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile


def file_digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def expected_inputs(binaries, scripts, driver):
    return {name: directory / name for directory, names in (
        (binaries, ('zhijie-shell.exe', 'zhijie-helper.exe')),
        (scripts, ('start-helper.ps1', 'HelperInstallPolicy.psm1')),
        (driver, ('wintun.dll', 'WINTUN-LICENSE.txt', 'wintun.h', 'wintun-source.json')),
    ) for name in names}


def check_listing(listing, expected):
    header, separator, members = listing.replace('\r\n', '\n').partition('\n----------\n')
    if not separator or 'Type = Nsis' not in header.splitlines():
        raise ValueError('不是可识别的 NSIS 安装包')
    names = [line.removeprefix('Path = ').replace('/', '\\') for line in members.splitlines() if line.startswith('Path = ')]
    product = Counter(name for name in names if not name.startswith('$PLUGINSDIR\\'))
    if product != Counter(expected.keys()):
        raise ValueError('包内产品文件清单不符（缺失、多余、重复或位置错误）')


def verify(package, expected, seven_zip):
    package = package.resolve(strict=True)
    initial = file_digest(package)
    result = subprocess.run([seven_zip, 'l', '-slt', '-sccUTF-8', '--', package],
                            capture_output=True, check=True, timeout=120)
    check_listing(result.stdout.decode('utf-8-sig'), expected)
    digests = {}
    for name, source in expected.items():
        with subprocess.Popen([seven_zip, 'x', '-so', '-spd', '--', package, name], stdout=subprocess.PIPE) as process:
            with process.stdout as stream:
                digest = hashlib.file_digest(stream, 'sha256').hexdigest()
            if process.wait() != 0:
                raise ValueError(f'无法读取包内文件：{name}')
        if digest != file_digest(source):
            raise ValueError(f'包内文件与构建输入不一致：{name}')
        digests[name] = digest
    if file_digest(package) != initial:
        raise ValueError('检查期间安装包发生变化')
    return {'package': package.name, 'sha256': initial, 'productFiles': digests, 'scope': 'payload comparison only'}


def prepare_portable(target, name, expected, report):
    if os.name != 'nt' or not re.fullmatch(r'trial-[a-z0-9][a-z0-9_-]{0,63}', name):
        raise ValueError('便携目录仅支持 Windows，名称应为 trial- 加小写字母、数字、下划线或连字符')
    if target.resolve(strict=True) != target or target.drive.upper() == os.environ.get('SystemDrive', 'C:').upper():
        raise ValueError('便携目录必须位于非系统盘且未经重定向的项目 target 下')
    destination = target / name
    if os.path.lexists(destination):
        raise FileExistsError(f'不覆盖已有试用目录：{destination}')
    with tempfile.TemporaryDirectory(dir=target, prefix='portable-stage-') as scratch:
        candidate = Path(scratch) / 'candidate'
        app = candidate / 'app'
        app.mkdir(parents=True)
        for filename, source in expected.items():
            copied = app / filename
            shutil.copyfile(source, copied)
            if file_digest(copied) != report['productFiles'][filename]:
                raise ValueError(f'试用文件复制后与安装包不一致：{filename}')
        (candidate / 'manifest.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        candidate.rename(destination)
    return destination


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('package', type=Path)
    parser.add_argument('--binaries', required=True, type=Path, help='同一配置构建的桌面程序和 Helper 所在目录')
    parser.add_argument('--scripts', required=True, type=Path)
    parser.add_argument('--driver', required=True, type=Path)
    parser.add_argument('--portable-name', help='核对成功后，在本项目 target 下新建具名便携目录，不安装或启动')
    parser.add_argument('--seven-zip', default=shutil.which('7z') or 'C:/Program Files/7-Zip/7z.exe')
    args = parser.parse_args()
    try:
        expected = expected_inputs(args.binaries, args.scripts, args.driver)
        result = verify(args.package, expected, args.seven_zip)
        if args.portable_name is not None:
            prepare_portable(Path(__file__).resolve().parents[2] / 'target', args.portable_name, expected, result)
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        parser.exit(1, f'安装包检查失败：{error}\n')
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
