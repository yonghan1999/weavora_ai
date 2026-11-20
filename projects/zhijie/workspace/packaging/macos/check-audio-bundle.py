"""检查桌面麦克风配置，或在 Mac 上只读检查已构建并签名的 .app。"""
import argparse
import json
from pathlib import Path
import plistlib
import subprocess
import sys

ARCHITECTURES = {'x86_64': ['x86_64'], 'arm64': ['arm64'], 'universal': ['arm64', 'x86_64']}


def validate(info, entitlements):
    purpose = info.get('NSMicrophoneUsageDescription')
    if not isinstance(purpose, str) or not purpose.strip():
        raise ValueError('桌面缺少非空的麦克风用途说明')
    if entitlements.get('com.apple.security.device.audio-input') is not True:
        raise ValueError('桌面缺少布尔值为真的麦克风授权')


def check_architectures(app, packaged, expected):
    name = packaged.get('CFBundleExecutable')
    if not isinstance(name, str) or not name or name in ('.', '..') or '/' in name or '\\' in name:
        raise ValueError('应用未声明有效的主程序文件名')
    contents = app.resolve(strict=True) / 'Contents'
    executable = contents / 'MacOS' / name
    if executable.resolve(strict=True) != executable or not executable.is_file():
        raise ValueError('包内主程序不是普通本地文件')
    actual = subprocess.check_output(['/usr/bin/lipo', '-archs', executable], text=True).split()
    if sorted(actual) != ARCHITECTURES[expected]:
        raise ValueError(f'候选架构应为 {expected}，实际为 {actual}')
    subprocess.run(['/usr/bin/codesign', '--verify', '--strict', '--all-architectures', app], check=True)
    for arch in actual:
        signed = subprocess.check_output(['/usr/bin/codesign', '-d', '--architecture', arch, '--entitlements', '-', '--xml', app])
        validate(packaged, plistlib.loads(signed))


def check(shell, app=None, expected_arch=None):
    if (app is None) != (expected_arch is None):
        raise ValueError('--app 与 --arch 必须一起使用')
    base = json.loads((shell / 'tauri.conf.json').read_text(encoding='utf-8'))
    config = json.loads((shell / 'tauri.macos.conf.json').read_text(encoding='utf-8'))['bundle']
    mac = config['macOS']
    if config['targets'] != ['dmg'] or mac['minimumSystemVersion'] != '13.0':
        raise ValueError('候选构建类型或最低系统版本与支持矩阵不符')
    info = plistlib.loads((shell / mac.get('infoPlist', 'Info.plist')).read_bytes())
    rights = plistlib.loads((shell / mac['entitlements']).read_bytes())
    validate(info, rights)
    if app is not None:
        if sys.platform != 'darwin' or app.suffix != '.app':
            raise ValueError('实际成品检查仅接受 Mac 上的 .app 目录')
        packaged = plistlib.loads((app / 'Contents/Info.plist').read_bytes())
        if (packaged.get('CFBundleIdentifier') != base['identifier'] or
                packaged.get('CFBundleShortVersionString') != base['version'] or
                packaged.get('LSMinimumSystemVersion') != mac['minimumSystemVersion'] or
                packaged.get('NSMicrophoneUsageDescription') != info['NSMicrophoneUsageDescription']):
            raise ValueError('实际桌面包的身份、应用版本、最低系统版本或用途说明与配置不符')
        check_architectures(app, packaged, expected_arch)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--app', type=Path)
    parser.add_argument('--arch', choices=ARCHITECTURES, help='交付物声明的架构，不从运行检查的主机推断')
    args = parser.parse_args()
    try:
        check(Path(__file__).resolve().parents[2] / 'desktop/shell', args.app, args.arch)
    except (OSError, ValueError, KeyError, subprocess.SubprocessError, plistlib.InvalidFileException) as error:
        parser.exit(1, f'麦克风打包检查失败：{error}\n')
    print(f'实际包版本、{args.arch} 架构与各架构签名授权检查通过；未测试录音。' if args.app else '源码配置检查通过；未构建或验证 macOS 安装包。')
