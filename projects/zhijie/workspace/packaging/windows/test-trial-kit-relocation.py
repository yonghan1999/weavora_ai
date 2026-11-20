"""在 D 盘一次性目录验证完整试用包；不启动桌面、Helper 或安装器。"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import tempfile
from urllib.error import HTTPError
from urllib.request import ProxyHandler, Request, build_opener
from zipfile import ZipFile
import time


def expect_api_error(api, path, status, code, **arguments):
    try:
        api(path, **arguments)
    except HTTPError as error:
        with error:
            if error.code != status or json.load(error)['error']['code'] != code:
                raise ValueError(f'资格拒绝结果不符：{path}') from error
    else:
        raise ValueError(f'失效资格意外通过：{path}')


def check_session_revocation(api, password, room):
    device = 'packaged-session'
    activity = f'activities/{room["currentActivityId"]}'
    network = {'publicKey': 'AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA='}

    def login():
        return api('auth/login', method='POST', body={
            'email': 'trial@example.test', 'password': password, 'deviceId': device})

    def join(tokens):
        return api('room-joins', token=tokens['accessToken'], method='POST', body={
            'roomId': room['id'], 'deviceId': device, 'operationId': secrets.token_hex(16)})

    def issue(tokens):
        return api(f'{activity}/network-grants', token=tokens['accessToken'], method='POST', body=network)

    def validate(grant):
        current = api('network-access/validate', method='POST', body={'ticket': grant['networkTicket']})
        if current['leaseId'] != grant['leaseId']:
            raise ValueError('试用包网络准入租约不符')

    def denied(grant):
        if datetime.now(timezone.utc) >= datetime.fromisoformat(grant['expiresAt'].replace('Z', '+00:00')):
            raise ValueError('旧票据已经自然到期，不能作为主动撤销证据')
        expect_api_error(api, 'network-access/validate', 403, 'NETWORK_GRANT_INVALID',
                         method='POST', body={'ticket': grant['networkTicket']})

    original, preserved = login(), login()
    first = join(original)
    first_grant = issue(original)
    validate(first_grant)
    rotated = api('auth/refresh', method='POST', body={'refreshToken': original['refreshToken']})
    validate(first_grant)
    api('auth/logout', method='POST', body={'accessToken': rotated['accessToken']})
    denied(first_grant)
    expect_api_error(api, 'groups', 401, 'ACCESS_TOKEN_INVALID', token=rotated['accessToken'])
    api('groups', token=preserved['accessToken'])
    expect_api_error(api, f'{activity}/resume', 409, 'GENERATION_STALE',
                     token=preserved['accessToken'], method='POST', body={'deviceId': device})
    time.sleep(1.1)
    denied(first_grant)
    replacement = join(preserved)
    if replacement['leaseId'] == first['leaseId'] or replacement['memberGeneration'] <= first['memberGeneration']:
        raise ValueError('认证失效后显式重入未创建新租约与成员代次')
    replacement_grant = issue(preserved)
    validate(replacement_grant)
    takeover = login()
    expect_api_error(api, f'{activity}/network-grants', 403, 'CURRENT_GRANT_REQUIRED',
                     token=takeover['accessToken'], method='POST', body=network)
    current = join(takeover)
    if current['leaseId'] == replacement['leaseId'] or current['memberGeneration'] <= replacement['memberGeneration']:
        raise ValueError('同设备新登录接管未隔离旧登录租约')
    current_grant = issue(takeover)
    denied(replacement_grant)
    if api(f'{activity}/leave', token=preserved['accessToken'], method='POST', body={'leaseId': current['leaseId']})['left']:
        raise ValueError('旧登录影响了同设备新登录活动')
    api('auth/logout', method='POST', body={'accessToken': preserved['accessToken']})
    time.sleep(1.1)
    validate(current_grant)
    refreshed = api('auth/refresh', method='POST', body={'refreshToken': takeover['refreshToken']})
    validate(current_grant)
    expect_api_error(api, 'auth/refresh', 401, 'REFRESH_TOKEN_INVALID',
                     method='POST', body={'refreshToken': takeover['refreshToken']})
    denied(current_grant)
    expect_api_error(api, 'groups', 401, 'ACCESS_TOKEN_INVALID', token=refreshed['accessToken'])
    print('TRIAL_KIT_SESSION_LOGOUT_TAKEOVER_REFRESH_REPLAY_OK')


def check_device_leave(api, password, token, room, check_grants):
    other = api('auth/login', method='POST', body={
        'email': 'trial@example.test', 'password': password, 'deviceId': 'takeover'})['accessToken']

    def join(current, device):
        return api('room-joins', token=current, method='POST', body={
            'roomId': room['id'], 'deviceId': device, 'operationId': secrets.token_hex(16)})

    old = join(token, 'relocation')
    current = join(other, 'takeover')
    activity = room['currentActivityId']
    if (old['activityId'] != activity or current['activityId'] != activity or old['leaseId'] == current['leaseId']
            or current['memberGeneration'] <= old['memberGeneration']):
        raise ValueError('两台设备未接管同一活动')
    if check_grants:
        network = {'publicKey': 'AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA='}
        for channel, body in [('voice', {}), ('network', network)]:
            try:
                api(f'activities/{activity}/{channel}-grants', token=token, method='POST', body=body)
            except HTTPError as error:
                with error:
                    if error.code != 403 or json.load(error)['error']['code'] != 'CURRENT_GRANT_REQUIRED':
                        raise ValueError(f'旧设备的 {channel} 授权未按设备资格拒绝') from error
            else:
                raise ValueError(f'旧设备取得了 {channel} 授权')
        grant = api(f'activities/{activity}/network-grants', token=other, method='POST', body=network)
        if grant['leaseId'] != current['leaseId'] or not grant['networkTicket']:
            raise ValueError('当前设备未取得与新租约匹配的网络票据')
    path = f'activities/{activity}/leave'
    for caller, lease in ((token, old['leaseId']), (token, current['leaseId']), (other, old['leaseId'])):
        if api(path, token=caller, method='POST', body={'leaseId': lease})['left'] is not False:
            raise ValueError('过期或非本设备的退房请求影响了当前活动')
    resumed = api(f'activities/{activity}/resume', token=other, method='POST', body={'deviceId': 'takeover'})
    if resumed['leaseId'] != current['leaseId']:
        raise ValueError('接管设备未保留原租约')
    for expected in (True, False):
        if api(path, token=other, method='POST', body={'leaseId': current['leaseId']})['left'] is not expected:
            raise ValueError('当前设备退房或重复退房结果不符')


def verify(package, digest, java_home, check_restore=False, check_leave=False, check_grants=False, check_session=False):
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
                document = json.load(response)
                return document.get('data', document)

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
                if check_leave or check_grants:
                    check_device_leave(api, password, token, room, check_grants)
                if check_session:
                    check_session_revocation(api, password, room)
            finally:
                subprocess.run([*command, scripts / 'stop.ps1'], cwd=kit, env=env, check=True, timeout=45)
    print('RELOCATED_TRIAL_KIT_PASSWORD_POLICY_LOGIN_MESSAGE_RESTART_OK')
    if check_restore:
        print('TRIAL_KIT_OFFLINE_BACKUP_RESTORE_LOGIN_OK')
    if check_leave or check_grants:
        print('TRIAL_KIT_DEVICE_BOUND_LEAVE_OK')
    if check_grants:
        print('TRIAL_KIT_DEVICE_BOUND_GRANTS_OK')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('package', type=Path)
    parser.add_argument('--sha256', required=True, help='可信来源提供的整包摘要')
    parser.add_argument('--java-home', required=True, type=Path)
    parser.add_argument('--check-restore', action='store_true', help='用包内工具恢复到另一解压目录，再登录核对数据')
    parser.add_argument('--check-leave', action='store_true', help='每次启动后验证设备接管和按租约退房；只用于支持该协议的包')
    parser.add_argument('--check-grants', action='store_true', help='同时验证接管前的设备不能取得语音或网络票据')
    parser.add_argument('--check-session', action='store_true', help='验证具体登录的注销、同设备接管及刷新重放撤销；需要 V17 后端')
    args = parser.parse_args()
    verify(args.package, args.sha256, args.java_home, args.check_restore, args.check_leave, args.check_grants, args.check_session)
