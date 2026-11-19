# 织界 · Windows 本机试用

这是未签名的 Windows x64 试用包，不是安装器。需要 WebView2 Runtime、Python 3.12+、JDK 25 和 Windows PowerShell（含系统 .NET Framework 4.x 编译器）。不需要 Git、Maven、Rust 或管理员权限。仅供本机试用，不开放防火墙，也不安装驱动。试用账号不能用于真实服务。

先核对压缩包 SHA-256 与提供者给出的值，只使用可信来源。将整个包解压到 D 盘**全新可写目录**（路径不要含分号），不要与旧版合并。分别从解压目录打开两个普通 PowerShell 窗口。

1. 第一个窗口启动后端，按提示设置至少 12 位的试用密码，等到“试用环境已就绪”。请把 JDK 路径换成本机实际路径。

   ```powershell
   & "./backend/scripts/local-preview/start.ps1" -JavaHome "D:/Program Files/jdk-25" -HttpPort 18829 -DatabasePort 15439
   ```

2. 第二个窗口先核对桌面文件。核对通过后执行第二条命令启动，使用 `trial@example.test` 和刚设置的密码登录。

   ```powershell
   python -B "scripts/local-preview/trial-desktop.py" "target/desktop" --http-port 18829 --check-only
   python -B "scripts/local-preview/trial-desktop.py" "target/desktop" --http-port 18829
   ```

3. 在「本地试用群」发送一条消息。退出桌面后，从解压目录正常停止后端。下次从同一目录启动，使用原密码即可查看保留的消息。

   ```powershell
   & "./backend/scripts/local-preview/stop.ps1"
   ```

数据保存在 `backend/target/local-preview/postgres`，桌面配置在 `target/desktop/webview`。不要删除唯一的数据副本，也不要分发已使用过的解压目录。如果端口已占用，不要停止未知进程；可在启动命令中选用空闲端口，并使桌面 `--http-port` 与后端 `-HttpPort` 一致。邮件默认关闭；本步骤不验证真实通话或跨机虚拟局域网。
