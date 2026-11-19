"""检查桌面麦克风配置，或在 Mac 上只读检查已构建并签名的 .app。"""
import argparse
import json
from pathlib import Path
import plistlib
import subprocess
import sys


def validate(info, entitlements):
    purpose = info.get('NSMicrophoneUsageDescription')
    if not isinstance(purpose, str) or not purpose.strip():
        raise ValueError('桌面缺少非空的麦克风用途说明')
    if entitlements.get('com.apple.security.device.audio-input') is not True:
        raise ValueError('桌面缺少布尔值为真的麦克风授权')


def check(shell, app=None):
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
                packaged.get('LSMinimumSystemVersion') != mac['minimumSystemVersion'] or
                packaged.get('NSMicrophoneUsageDescription') != info['NSMicrophoneUsageDescription']):
            raise ValueError('实际桌面包的身份、最低版本或用途说明与配置不符')
        subprocess.run(['/usr/bin/codesign', '--verify', '--strict', app], check=True)
        signed = subprocess.run(['/usr/bin/codesign', '-d', '--entitlements', '-', '--xml', app],
                                stdout=subprocess.PIPE, check=True)
        validate(packaged, plistlib.loads(signed.stdout))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--app', type=Path)
    args = parser.parse_args()
    try:
        check(Path(__file__).resolve().parents[2] / 'desktop/shell', args.app)
    except (OSError, ValueError, KeyError, subprocess.SubprocessError, plistlib.InvalidFileException) as error:
        parser.exit(1, f'麦克风打包检查失败：{error}\n')
    print('实际包元数据与签名授权检查通过；未测试录音。' if args.app else '源码配置检查通过；未构建或验证 macOS 安装包。')
