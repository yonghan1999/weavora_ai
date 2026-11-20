"""在 D 盘一次性目录验证完整试用包；不启动桌面、Helper 或安装器。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import tempfile
from urllib.request import ProxyHandler, Request, build_opener
from zipfile import ZipFile


def verify(package, digest, java_home, check_restore=False):
    workspace = Path(__file__).resolve().parents[2]
    target = workspace / 'target'
    if os.name != 'nt' or target.drive.upper() == os.environ.get('SystemDrive', 'C:').upper() or target.resolve(strict=True) != target:
        raise ValueError('需要非系统盘的普通工作区 target 目录')
    with package.open('rb') as stream, tempfile.TemporaryDirectory(dir=target, prefix='relocated trial ') as directory:
        if hashlib.file_digest(stream, 'sha256').hexdigest() != digest.lower():
            raise ValueError('试用包摘要不符，未解压或执行')
        root = Path(directory)
        stream.seek(0)
        with ZipFile(stream) as archive:
            archive.extractall(root, members=archive.namelist())
            if check_restore:
                restored = root / 'second kit'
                archive.extractall(restored)
        with socket.socket() as http, socket.socket() as database:
            for listener in (http, database):
                listener.bind(('127.0.0.1', 0))
            http_port, database_port = http.getsockname()[1], database.getsockname()[1]
        temporary = root / 'test temp'
        temporary.mkdir()
        env = {**os.environ, 'TEMP': str(temporary), 'TMP': str(temporary), 'TMPDIR': str(temporary),
               'ZHIJIE_MAIL_ENABLED': 'false', 'ZHIJIE_VOICE_ENABLED': 'false'}
        subprocess.run([sys.executable, '-B', 'scripts/local-preview/trial-desktop.py', 'target/desktop',
                        '--check-only', '--http-port', str(http_port)], cwd=root, env=env, check=True, timeout=30)
        password = secrets.token_hex(24)
        room_password = secrets.token_hex(18)
        marker = secrets.token_hex(12)
        opener = build_opener(ProxyHandler({}))

        def api(path, *, token=None, body=None, method='GET'):
            headers = {'Content-Type': 'application/json'}
            if token:
                headers['Authorization'] = 'Bearer ' + token
            request = Request(f'http://127.0.0.1:{http_port}/api/v1/{path}', method=method, headers=headers,
                              data=json.dumps(body).encode() if body is not None else None)
            with opener.open(request, timeout=10) as response:
                return json.load(response)['data']

        command = ['powershell', '-NoProfile', '-NonInteractive', '-File']
        launches = [root, root, restored] if check_restore else [root, root]
        for index, kit in enumerate(launches):
            first = index == 0
            if index == 2:
                database_path = Path('backend/target/local-preview/postgres')
                tool = [sys.executable, '-B', str(kit / 'ops/database/cold-backup.py')]
                archive_path = root / 'private snapshot'
                subprocess.run([*tool, 'snapshot', '--source', root / database_path, '--destination', archive_path],
                               cwd=kit, env=env, check=True, timeout=120)
                subprocess.run([*tool, 'restore', '--backup', archive_path, '--destination', kit / database_path],
                               cwd=kit, env=env, check=True, timeout=120)
            scripts = kit / 'backend/scripts/local-preview'
            try:
                startup = {**env, 'ZHIJIE_PREVIEW_PASSWORD': password} if first else env.copy()
                if not first:
                    startup.pop('ZHIJIE_PREVIEW_PASSWORD', None)
                subprocess.run([*command, scripts / 'start.ps1', '-JavaHome', java_home, '-HttpPort', str(http_port),
                                '-DatabasePort', str(database_port)], cwd=kit, env=startup, check=True, timeout=120)
                login = api('auth/login', method='POST', body={'email': 'trial@example.test', 'password': password, 'deviceId': 'relocation'})
                token = login['accessToken']
                group, = api('groups', token=token)
                room, = api(f'groups/{group["id"]}/rooms', token=token)
                history = f'groups/{group["id"]}/messages'
                if first:
                    original_user, original_group = login['userId'], group['id']
                    original_room = room['id']
                    room = api(f'rooms/{original_room}', token=token, method='PATCH', body={
                        'name': room['name'], 'description': room['description'], 'gameName': room['gameName'], 'password': room_password})
                    message_id = api(history, token=token, method='POST', body={'body': marker})['id']
                if login['userId'] != original_user or group['id'] != original_group:
                    raise ValueError('账号或群标识发生变化')
                if room['id'] != original_room or room['passwordProtected'] is not True or room['passwordRequired'] is not False:
                    raise ValueError('房间密码配置或群主免密状态不符')
                if not any(item['id'] == message_id and item['body'] == marker for item in api(history, token=token)):
                    raise ValueError('未读到本次试用消息')
            finally:
                subprocess.run([*command, scripts / 'stop.ps1'], cwd=kit, env=env, check=True, timeout=45)
    print('RELOCATED_TRIAL_KIT_PASSWORD_POLICY_LOGIN_MESSAGE_RESTART_OK')
    if check_restore:
        print('TRIAL_KIT_OFFLINE_BACKUP_RESTORE_LOGIN_OK')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('package', type=Path)
    parser.add_argument('--sha256', required=True, help='可信来源提供的整包摘要')
    parser.add_argument('--java-home', required=True, type=Path)
    parser.add_argument('--check-restore', action='store_true', help='用包内工具恢复到另一解压目录，再登录核对数据')
    args = parser.parse_args()
    verify(args.package, args.sha256, args.java_home, args.check_restore)
