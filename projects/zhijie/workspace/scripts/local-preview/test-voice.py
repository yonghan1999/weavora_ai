"""对本机受控 LiveKit 执行媒体测试；测试凭据只经子进程环境传递。"""
import base64
import hashlib
import hmac
import json
import os
import subprocess
import time
import uuid


def main():
    key = os.environ["ZHIJIE_VOICE_API_KEY"]
    secret = os.environ["ZHIJIE_VOICE_API_SECRET"].encode()
    room = "media-smoke-" + uuid.uuid4().hex

    def token(identity, admin=False):
        now = int(time.time())
        grants = {"room": room, "roomAdmin": True} if admin else {
            "room": room, "roomJoin": True, "canPublish": True,
            "canSubscribe": True, "canPublishData": False,
            "canPublishSources": ["microphone"],
        }
        def encode(data):
            return base64.urlsafe_b64encode(data).rstrip(b"=")
        body = encode(b'{"alg":"HS256","typ":"JWT"}') + b"." + encode(json.dumps({
            "iss": key, "sub": identity, "iat": now, "nbf": now - 5,
            "exp": now + 60, "video": grants,
        }).encode())
        return (body + b"." + encode(hmac.new(secret, body, hashlib.sha256).digest())).decode()

    env = os.environ.copy()
    env.update(
        ZHIJIE_TEST_VOICE_URL="ws://127.0.0.1:7880",
        ZHIJIE_TEST_VOICE_TOKEN_A=token("test-a:1"),
        ZHIJIE_TEST_VOICE_TOKEN_B=token("test-b:1"),
        ZHIJIE_TEST_VOICE_ADMIN_TOKEN=token("test-admin", True),
    )
    result = subprocess.run([
        "cargo", "test", "--locked", "--offline", "-p", "zhijie-voice", "--test", "livekit_media",
        "bidirectional_audio_and_server_removal", "--", "--ignored", "--exact", "--nocapture",
    ], env=env, timeout=120)
    return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
