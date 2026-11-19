"""仅本机语音试用：前台管理鉴权代理，回车、关闭标准输入或 Ctrl+C 停止。"""
import argparse
import importlib.util
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import threading
import time


def serve(executable, port, control_port):
    workspace = Path(__file__).resolve().parents[2]
    ops = workspace / 'ops'
    spec = importlib.util.spec_from_file_location('voice_renderer', ops / 'render-voice-signaling.py')
    renderer = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(renderer)
    template = (ops / 'voice-signaling.nginx.conf.template').read_text(encoding='utf-8-sig')
    config = renderer.render_preview(template, os.environ, port, control_port)
    with socket.socket() as reservation:
        reservation.bind(('127.0.0.1', port))
    target = workspace / 'target'
    target.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(dir=target, prefix='trial-voice-proxy-') as temporary:
        path = renderer.write_private_directory(Path(temporary) / 'private', config)
        prefix = path.parent
        (prefix / 'logs').mkdir()
        (prefix / 'temp').mkdir()
        command = [str(executable), '-p', prefix.as_posix() + '/', '-c', path.name]
        environment = {key: value for key, value in os.environ.items() if not key.startswith(('ZHIJIE_', 'LIVEKIT_'))}
        environment.update(TEMP=str(prefix / 'temp'), TMP=str(prefix / 'temp'), TMPDIR=str(prefix / 'temp'))
        options = dict(cwd=prefix, env=environment, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        subprocess.run(command + ['-t'], **options, check=True, timeout=10)
        process = subprocess.Popen(command, **options)
        try:
            for attempt in range(100):
                if process.poll() is not None:
                    raise RuntimeError('nginx 未能启动')
                pid = prefix / 'logs/nginx.pid'
                if pid.exists() and pid.read_text().strip() == str(process.pid):
                    try:
                        with socket.create_connection(('127.0.0.1', port), timeout=0.2):
                            break
                    except OSError:
                        pass
                time.sleep(0.1)
            else:
                raise RuntimeError('等待 nginx 回环监听超时')
            print(f'VOICE_PROXY_READY ws://127.0.0.1:{port} (Enter to stop)', flush=True)
            stopped = threading.Event()
            def wait_for_input():
                try:
                    os.read(sys.stdin.fileno(), 1)
                finally:
                    stopped.set()
            threading.Thread(target=wait_for_input, daemon=True).start()
            while not stopped.wait(0.25):
                if process.poll() is not None:
                    raise RuntimeError('nginx 意外退出')
        finally:
            if process.poll() is None:
                subprocess.run(command + ['-s', 'stop'], **options, check=True, timeout=10)
                process.wait(timeout=10)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--nginx', required=True, type=Path)
    parser.add_argument('--port', default=7881, type=int)
    parser.add_argument('--control-port', default=8080, type=int)
    args = parser.parse_args()
    try:
        serve(args.nginx.resolve(strict=True), args.port, args.control_port)
    except KeyboardInterrupt:
        pass
    except (ValueError, OSError, subprocess.SubprocessError, RuntimeError):
        parser.exit(1, '本机语音代理失败，请检查密钥、端口和 nginx 可执行文件。\n')
