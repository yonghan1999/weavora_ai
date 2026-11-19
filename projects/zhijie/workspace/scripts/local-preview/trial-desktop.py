"""从非系统盘的已准备候选目录启动单机试用桌面，不安装或启动后端。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile


def inspect_candidate(target, candidate):
    target = target.resolve(strict=True)
    root = candidate.resolve(strict=True)
    if target not in root.parents or root.drive.upper() == os.environ.get('SystemDrive', 'C:').upper():
        raise ValueError('请选择非系统盘工作区 target 下的试用目录。')
    executable = root / 'app' / 'zhijie-shell.exe'
    manifest = root / 'manifest.json'
    profile = root / 'webview'
    for path in (manifest, executable, profile, root / 'temp'):
        if path.resolve() != path:
            raise ValueError('试用文件或缓存路径经过重定向。')
    for directory in (profile, root / 'temp'):
        if directory.exists() and not directory.is_dir():
            raise ValueError('试用缓存路径已被同名文件占用。')
    expected = json.loads(manifest.read_text(encoding='utf-8-sig'))['productFiles']
    files = list(executable.parent.iterdir())
    if not isinstance(expected, dict) or executable.name not in expected or set(expected) != {path.name for path in files}:
        raise ValueError('app 目录与候选清单的文件名不符。')
    for path in files:
        if path.resolve(strict=True) != path or not path.is_file():
            raise ValueError(f'试用文件不是普通本地文件：{path.name}')
        with path.open('rb') as stream:
            if hashlib.file_digest(stream, 'sha256').hexdigest() != expected[path.name]:
                raise ValueError(f'试用文件与候选清单摘要不符：{path.name}')
    return executable, profile


def client_environment(parent, profile, temporary, port):
    allowed = {'SYSTEMROOT', 'WINDIR', 'SYSTEMDRIVE', 'COMPUTERNAME', 'USERDOMAIN', 'USERNAME',
               'USERPROFILE', 'LOCALAPPDATA', 'APPDATA', 'PROGRAMDATA', 'PROGRAMFILES', 'PROGRAMFILES(X86)',
               'PROGRAMW6432', 'PROCESSOR_ARCHITECTURE', 'NUMBER_OF_PROCESSORS', 'OS'}
    environment = {key: value for key, value in parent.items() if key.upper() in allowed}
    environment.update(WEBVIEW2_USER_DATA_FOLDER=str(profile), TEMP=str(temporary), TMP=str(temporary),
                       TMPDIR=str(temporary), ZHIJIE_CONTROL_PLANE_URL=f'http://127.0.0.1:{port}')
    return environment


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('candidate', type=Path, help='含 manifest.json 和 app 子目录的本机候选目录')
    parser.add_argument('--http-port', type=int, default=8080)
    parser.add_argument('--check-only', action='store_true', help='只读核对 app 文件清单、SHA-256 和目录，不启动程序')
    args = parser.parse_args()
    if os.name != 'nt' or not 1 <= args.http_port <= 65535:
        parser.error('仅支持 Windows 本机，HTTP 端口须在 1 至 65535。')
    try:
        executable, profile = inspect_candidate(Path(__file__).resolve().parents[2] / 'target', args.candidate)
        if args.check_only:
            print(f'app 文件清单及全部摘要匹配；WebView2 试用目录：{profile}；未启动。')
            return 0
        profile.mkdir(exist_ok=True)
        temp_root = profile.parent / 'temp'
        temp_root.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='desktop-', dir=temp_root) as temporary:
            environment = client_environment(os.environ, profile, temporary, args.http_port)
            return subprocess.run([executable], cwd=executable.parent, env=environment, stdin=subprocess.DEVNULL).returncode
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(1, f'试用桌面未能正常启动或收尾：{error}\n')


if __name__ == '__main__':
    raise SystemExit(main())
