"""渲染专用 nginx 信令片段；不部署、不重载，密钥只从环境读取。"""
import argparse
import ctypes
from ctypes import wintypes
import csv
import io
import os
from pathlib import Path, PurePosixPath, PureWindowsPath
import re
import subprocess


def render_entry(template, listener, control_port, key):
    values = {'VOICE_LISTENER': listener, 'CONTROL_PORT': str(control_port), 'ZHIJIE_VOICE_SIGNALING_KEY': key}
    if set(re.findall(r"\$\{([^}]+)\}", template)) != set(values):
        raise ValueError("VOICE_TEMPLATE_PLACEHOLDERS_INVALID")
    if not re.fullmatch(r"[A-Za-z0-9_+=/.-]{32,256}", key):
        raise ValueError("VOICE_SIGNALING_KEY_INVALID")
    return re.sub(r"\$\{([^}]+)\}", lambda match: values[match[1]], template)


def render_preview(template, environment, port, control_port):
    if (not all(type(value) is int and 1 <= value <= 65535 for value in (port, control_port))
            or len({port, control_port, 7880, 7882}) != 4):
        raise ValueError("VOICE_PREVIEW_PORT_INVALID")
    entry = render_entry(template, f'listen 127.0.0.1:{port};\n    server_name localhost;',
                         control_port, environment.get('ZHIJIE_VOICE_SIGNALING_KEY', ''))
    return 'daemon off;\nworker_processes 1;\nerror_log stderr crit;\nevents { worker_connections 128; }\nhttp {\n' + entry + '\n}\n'


def render(template, environment):
    names = {"ZHIJIE_VOICE_PUBLIC_HOST", "ZHIJIE_VOICE_TLS_CERT",
             "ZHIJIE_VOICE_TLS_KEY", "ZHIJIE_VOICE_SIGNALING_KEY"}
    values = {}
    for name in names:
        value = environment.get(name, "")
        if not value or any(ord(char) < 32 or ord(char) == 127 for char in value):
            raise ValueError("VOICE_CONFIGURATION_INVALID")
        values[name] = value
    host = values["ZHIJIE_VOICE_PUBLIC_HOST"]
    if len(host) > 253 or not all(re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?", label)
                                  for label in host.split(".")):
        raise ValueError("VOICE_PUBLIC_HOST_INVALID")
    for name in ["ZHIJIE_VOICE_TLS_CERT", "ZHIJIE_VOICE_TLS_KEY"]:
        path = values[name]
        if (not (PurePosixPath(path).is_absolute() or PureWindowsPath(path).is_absolute())
                or any(char in path for char in '$;{}"') or path.startswith(("\\\\", "//"))):
            raise ValueError("VOICE_TLS_PATH_INVALID")
        # nginx 引号支持含空格路径；Windows 分隔符转为正斜杠，拒绝变量插值。
        values[name] = '"' + path.replace("\\", "/") + '"'
    listener = (f'listen 443 ssl;\n    server_name {host};\n'
                f'    ssl_certificate {values["ZHIJIE_VOICE_TLS_CERT"]};\n'
                f'    ssl_certificate_key {values["ZHIJIE_VOICE_TLS_KEY"]};')
    return render_entry(template, listener, 8080, values['ZHIJIE_VOICE_SIGNALING_KEY'])


def write_private_directory(directory, content):
    directory = Path(directory).absolute()
    # 不修改已有目录/ACL；新目录未写入秘密前先限制继承权限。
    directory.mkdir(mode=0o700)
    try:
        if os.name == "nt":
            restrict_windows_directory(directory)
        else:
            directory.chmod(0o700)
        path = directory / "voice-signaling.conf"
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as output:
            output.write(content)
        return path
    except Exception:
        # 不自动删除失败目录，避免覆盖失败现场；不能将其当作成功配置。
        raise ValueError("VOICE_PRIVATE_OUTPUT_FAILED") from None


def restrict_windows_directory(directory):
    identity = subprocess.run(["whoami", "/user", "/fo", "csv", "/nh"],
                              capture_output=True, check=True, text=True)
    sid = next(csv.reader(io.StringIO(identity.stdout)))[1]
    if not re.fullmatch(r"S-1-(?:\d+-)+\d+", sid):
        raise ValueError("VOICE_OUTPUT_IDENTITY_INVALID")
    security = ctypes.WinDLL("advapi32", use_last_error=True)
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    pointer = ctypes.c_void_p
    security.ConvertStringSecurityDescriptorToSecurityDescriptorW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, ctypes.POINTER(pointer), pointer]
    security.GetSecurityDescriptorDacl.argtypes = [pointer, ctypes.POINTER(wintypes.BOOL), ctypes.POINTER(pointer), ctypes.POINTER(wintypes.BOOL)]
    security.SetNamedSecurityInfoW.argtypes = [wintypes.LPWSTR, wintypes.DWORD, wintypes.DWORD, pointer, pointer, pointer, pointer]
    security.SetNamedSecurityInfoW.restype = wintypes.DWORD
    kernel.LocalFree.argtypes = [pointer]
    descriptor = pointer()
    sddl = f"D:P(A;OICI;FA;;;{sid})(A;OICI;FA;;;SY)"
    if not security.ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl, 1, ctypes.byref(descriptor), None):
        raise ValueError("VOICE_OUTPUT_ACL_INVALID")
    try:
        present, defaulted, dacl = wintypes.BOOL(), wintypes.BOOL(), pointer()
        if not security.GetSecurityDescriptorDacl(descriptor, ctypes.byref(present), ctypes.byref(dacl), ctypes.byref(defaulted)) or not present.value:
            raise ValueError("VOICE_OUTPUT_ACL_INVALID")
        # 只写受保护 DACL，不请求 SACL/所有者权限。
        if security.SetNamedSecurityInfoW(str(directory), 1, 0x80000004, None, None, dacl, None):
            raise ValueError("VOICE_OUTPUT_ACL_FAILED")
    finally:
        kernel.LocalFree(descriptor)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-directory", required=True,
                        help="必须不存在；生成含秘密配置的专用目录")
    args = parser.parse_args()
    try:
        template = Path(__file__).with_name("voice-signaling.nginx.conf.template").read_text(encoding="utf-8-sig")
        content = render(template, os.environ)
        path = write_private_directory(args.output_directory, content)
    except (ValueError, OSError, subprocess.SubprocessError):
        parser.exit(1, "信令配置生成失败；检查环境、模板和新目录权限。未输出配置值。\n")
    print(f"已生成受限配置：{path}；尚未执行 nginx 检查或重载。")


if __name__ == "__main__":
    main()
