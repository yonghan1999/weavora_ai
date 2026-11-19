"""仅针对 Windows 离线包隔离回归：从标准输入读取测试密码，不安装证书。"""
import argparse
import asyncio
from datetime import datetime, timedelta, timezone
import importlib.util
from ipaddress import ip_address
import json
import os
from pathlib import Path
import socket
import ssl
import subprocess
import sys
import tempfile
import time
import uuid

import aiohttp
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID


def prepare(directory, port, upstream):
    ops = Path(__file__).resolve().parent
    spec = importlib.util.spec_from_file_location('voice_renderer', ops / 'render-voice-signaling.py')
    renderer = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(renderer)
    directory.mkdir()
    renderer.restrict_windows_directory(directory)
    for name in ('logs', 'temp'):
        (directory / name).mkdir()
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'control.example.test')])
    now = datetime.now(timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(subject).issuer_name(subject).public_key(key.public_key())
            .serial_number(x509.random_serial_number()).not_valid_before(now - timedelta(minutes=1))
            .not_valid_after(now + timedelta(hours=1))
            .add_extension(x509.SubjectAlternativeName([x509.IPAddress(ip_address('127.0.0.1'))]), critical=False)
            .sign(key, hashes.SHA256()))
    (directory / 'cert.pem').write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    (directory / 'key.pem').write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                                        serialization.NoEncryption()))
    fragment = (ops / 'control-plane.nginx.conf.example').read_text(encoding='utf-8')
    for old, new in [('listen 443 ssl;', f'listen 127.0.0.1:{port} ssl;'), ('/etc/zhijie/tls/fullchain.pem', 'cert.pem'),
                     ('/etc/zhijie/tls/privkey.pem', 'key.pem'), ('/dev/null', 'nul'), ('127.0.0.1:8080', f'127.0.0.1:{upstream}')]:
        if old not in fragment:
            raise ValueError('Expected proxy setting missing')
        fragment = fragment.replace(old, new)
    (directory / 'nginx.conf').write_text('daemon off;\nevents { worker_connections 64; }\nhttp {\n' + fragment + '\n}', encoding='utf-8')


async def exercise(directory, port, password):
    address = f'https://127.0.0.1:{port}'
    trust = ssl.create_default_context(cafile=str(directory / 'cert.pem'))
    async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=10)) as client:
        try:
            async with client.get(address + '/api/v1/groups'):
                raise AssertionError('Untrusted test certificate accepted')
        except aiohttp.ClientConnectorCertificateError:
            pass
        for route in ['/actuator/health', '/internal/observability/summary', '/ops/network-policy']:
            async with client.get(address + route, ssl=trust) as response:
                if response.status != 404:
                    raise AssertionError('Non-product endpoint exposed')
        async def api(method, path, headers=None, body=None):
            async with client.request(method, address + '/api/v1/' + path, headers=headers, json=body, ssl=trust) as response:
                if not 200 <= response.status < 300:
                    raise AssertionError(f'Product {method} {path} failed: {response.status}')
                return (await response.json())['data']
        login = await api('POST', 'auth/login', body={'deviceId': 'tls-fixture', 'email': 'trial@example.test', 'password': password})
        headers = {'Authorization': 'Bearer ' + login['accessToken']}
        group = (await api('GET', 'groups', headers))[0]['id']
        cursor = (await api('GET', 'sync/cursor', headers))['cursor']
        async with client.ws_connect(f'wss://127.0.0.1:{port}/api/v1/ws?cursor={cursor}', headers=headers, ssl=trust) as stream:
            sent = await api('POST', f'groups/{group}/messages', headers, {'body': uuid.uuid4().hex})
            async with asyncio.timeout(12):
                while True:
                    frame = await stream.receive()
                    if frame.type != aiohttp.WSMsgType.TEXT:
                        raise AssertionError(f'Expected a sync event, received {frame.type.name} ({stream.close_code})')
                    event = json.loads(frame.data)
                    if (event['type'] == 'message.created' and event['groupId'] == group and event['sequence'] > cursor
                            and json.loads(event['payload'])['message_id'] == sent['id']):
                        break


def run(nginx, upstream, password):
    if not password or os.name != 'nt' or not 1 <= upstream <= 65535:
        raise ValueError('Requires a Windows preview test instance')
    with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[1] / 'target', prefix='control-tls-') as scratch:
        directory = Path(scratch) / 'private'
        with socket.socket() as reservation:
            reservation.bind(('127.0.0.1', 0))
            port = reservation.getsockname()[1]
            prepare(directory, port, upstream)
        command = [str(nginx.resolve(strict=True)), '-p', directory.as_posix() + '/', '-c', 'nginx.conf']
        options = dict(cwd=directory, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                       creationflags=subprocess.CREATE_NO_WINDOW)
        subprocess.run([*command, '-t'], **options, check=True, timeout=10)
        process = subprocess.Popen(command, **options)
        try:
            for attempt in range(100):
                if process.poll() is not None:
                    raise RuntimeError('Proxy exited before readiness')
                pid = directory / 'logs/nginx.pid'
                if pid.exists() and pid.read_text().strip() == str(process.pid):
                    break
                time.sleep(.1)
            else:
                raise TimeoutError('Proxy not ready')
            asyncio.run(exercise(directory, port, password))
        finally:
            if process.poll() is None:
                subprocess.run([*command, '-s', 'stop'], **options, check=True, timeout=10)
                process.wait(timeout=10)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--nginx', required=True, type=Path)
    parser.add_argument('--upstream-port', required=True, type=int)
    args = parser.parse_args()
    try:
        password = sys.stdin.buffer.readline().decode('utf-8-sig').rstrip('\r\n')
        run(args.nginx, args.upstream_port, password)
    except Exception as error:
        detail = str(error) if isinstance(error, AssertionError) else type(error).__name__
        print(f'Control TLS check failed: {detail}', flush=True)
        sys.exit(1)
    print('PACKAGED_CONTROL_HTTPS_WSS_AND_PRIVATE_ROUTES_OK')
