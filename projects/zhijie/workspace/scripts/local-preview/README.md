# 本地试用环境

仅在本机使用：后端 `127.0.0.1:8080`，独立 PostgreSQL `127.0.0.1:15432`。数据库使用嵌入式开发依赖，数据保存在忽略目录 `target/local-preview/postgres`，重启保留。不要向局域网或公网开放。

## 使用已有环境

本机另已从提交 `649e830` 独立构建 release 候选：`target/trial-649e830`。这是未签名的 Windows x64 试用包，不是正式发布。安装包共 12,079,035 字节，SHA-256 为 `4627b40bbc4a2e7f1679483d4c97af96be47744fa1c296e81b559938303880e7`。安装包内的八个文件已经与同次构建输入和便携副本核对。本人试用无需运行安装器。在本机的 `workspace` 开一个普通 PowerShell，执行：

```powershell
& "./target/win-649e830/source/scripts/local-preview/start.ps1" -JavaHome "D:/Program Files/jdk-25" -HttpPort 18829 -DatabasePort 15439
```

此脚本使用候选源码目录下的独立数据库，并会切换当前目录。首次运行时在本机输入自己的试用密码。另开一个位于 `workspace` 的普通 PowerShell，执行 `python -B "scripts/local-preview/trial-desktop.py" "target/trial-649e830" --http-port 18829 --check-only`；确认后去掉 `--check-only` 手动启动桌面。用后从 `workspace` 执行 `& "./target/win-649e830/source/scripts/local-preview/stop.ps1"` 停止本候选的后端，保留数据。独立测试库已验证登录、入房、心跳、退出和重启；不代表已人工试音或已验证安装升级。上述 `target` 路径仅存在于这台构建机，其他机器需自行准备候选。

对于已准备的 Windows 便携试用目录，先在 `workspace` 执行 `python -B "scripts/local-preview/trial-desktop.py" "target/trial-36a2e08-local" --check-only`；也可替换为其他候选目录。检查会逐个核对 `app/` 中的文件名和 SHA-256；缺失、多余或与清单不符均不会启动。确认后去掉 `--check-only` **手动**启动桌面。这是普通用户试用入口，不运行安装器、Helper 或后端。后端使用非默认端口时传 `--http-port`。入口向桌面子进程传入回环地址、候选目录中的 WebView2 用户数据目录和每次独立的临时目录，不传登录密码、SFU/代理密钥，也不修改系统环境变量。程序退出后清理本次临时目录，保留 `webview/`；不保证 Windows 自身的系统记录零写入。清单比对不是签名或来源认证。

在 `projects/zhijie/workspace` 打开普通 PowerShell，检查后端：

```powershell
(Invoke-RestMethod -Uri 'http://127.0.0.1:8080/actuator/health' -TimeoutSec 5).status
```

返回 `UP` 表示进程健康，不表示它已经加载最近编译的接口。如已更新后端代码，请按下节正常停库、备份并重启。按上述方式打开候选桌面后，用 `trial@example.test` 和首次设置的密码登录。在「本地试用群」的群聊发一条消息，再退出并重新登录，检查消息是否保留。群聊满 50 条时会出现「更早的消息」。示例群和房间只在首次初始化时创建；已改名时使用现有名称。这是试用步骤，不是桌面验收记录。

基础试用无需邮件、麦克风、管理员 Helper 或防火墙规则，不验证跨机联机。连接失败时再检查下节的启动条件；若 8080 已被占用，不要停止未知进程或删除已有数据库。

## 初始化或启动后端

从 `workspace` 使用 JDK 25 执行，按本机安装位置设置 `JAVA_HOME`。桌面试用沿用默认端口；在独立工作区做隔离验证时，可用 `-HttpPort` 和 `-DatabasePort` 指定不同的空闲端口，两者仍只监听回环地址。

```powershell
$env:JAVA_HOME = "D:/Program Files/jdk-25"
$list = "local-preview-classpath-$([guid]::NewGuid().ToString('N')).txt"
./mvnw.cmd -pl control-plane test-compile dependency:build-classpath "-Dmdep.includeScope=test" "-Dmdep.outputFile=target/$list"
if ($LASTEXITCODE -ne 0) { throw "Maven 准备失败，保留原清单。" }
$candidate = "control-plane/target/$list"
$entries = (Get-Content -LiteralPath $candidate -Raw -ErrorAction Stop).Trim() -split ';'
if (@($entries | Where-Object { !$_ -or !(Test-Path -LiteralPath $_ -PathType Leaf) }).Count) { throw "依赖文件缺失。" }
Copy-Item -LiteralPath $candidate -Destination "control-plane/target/local-preview-classpath.txt" -ErrorAction Stop
./scripts/local-preview/start.ps1
```

每次生成独立文件名，避免 Maven 沿用已存在的旧清单。如果后端正在使用，先正常停止并确认两个监听端口已释放，再用 `ops/database/cold-backup.py snapshot` 将 `target/local-preview/postgres` 备份到限本人访问的新目录，以 `verify --require-directories` 核对后再更新；不要删除数据目录。首次启动交互输入至少 12 位的试用密码；账号为 `trial@example.test`。入口只在独立试用数据库中初始化已激活账号、群组、房间和欢迎消息，不改正式认证流程，不包含邮件模拟成功。嵌入式数据库仅用于可信的单用户开发机，不是多人共享服务器的隔离方案。

试用账号、群、房间、活动和欢迎消息在同一个事务中创建。若初始化中途失败，排除日志所示原因后重新运行启动脚本即可；即使数据库目录已经存在，只要试用账号还没创建，启动器仍会安全询问密码（已配置密码则不询问）。已有试用账号及其数据不会被覆盖；旧版本留下的残缺数据不会自动修复，不要为此删库。

后端就绪后启动 `target/debug/zhijie-shell.exe`。未构建时先在 `desktop/ui` 运行 `node scripts/build.mjs`，再从 `workspace` 运行 `cargo build --offline -p zhijie-shell --features tauri/custom-protocol`。

停止：先关闭桌面程序，再在 PowerShell 执行下列命令，正常关闭后端及其嵌入式数据库：

```powershell
./scripts/local-preview/stop.ps1
```

可体验登录、群组、房间、群聊及已实现的操作。邮件、外部 SFU、真实音频及 TUN/Relay 不包含在此环境。试用验证不代表发布基线已重新验收。

本机 `Command Processor/AutoRun` 存在异常值，`CleanCmd.cs` 仅为 PostgreSQL 子进程传入 `cmd /d`，不修改注册表或全局环境。启动入口使用测试作用域的嵌入式数据库依赖，与正式服务构建隔离（单一职责）；数据目录、停止信号及日志统一放在忽略目录，不写入工作流审批和历史证据。

准备好类路径后，可运行 `./scripts/local-preview/test-preview.ps1`。它在 `target/preview-test-*` 创建独立试用库，重启前后都通过 HTTP 登录、读取欢迎消息和历史游标、加入房间、发送心跳并离房。群、消息、房间和活动标识须保持不变，再次入房须获得新租约及更高代次。默认使用 18828/15438，不读取原试用库或使用原账号密码；不启动桌面，也不验证语音或实际联机传输。

## 受控语音联调

桌面单机试用现在可以直接启动受鉴权的回环入口：[启动步骤与停止方式](voice-proxy.md)。不需要语音时保持默认配置即可。

已补充可选本机 SFU，默认试用后端仍不启用。先从 LiveKit 官方 release 下载并校验服务端，将 `livekit-server.exe` 放在 `target/local-preview/livekit/bin/`。通过当前进程环境设置 `ZHIJIE_VOICE_API_KEY` 和至少 32 字符的 `ZHIJIE_VOICE_API_SECRET`，随后运行 `./scripts/local-preview/start-voice.ps1` 和 `python scripts/local-preview/test-voice.py`。HTTP 信令限定回环，媒体 UDP 绑定本机默认出口 IPv4 的 7882 端口，可通过 `-MediaAddress` 指定另一块本机网卡；不改防火墙。Rust WebRTC 默认忽略回环网卡，纯回环媒体在本机连接超时，不能据此宣称媒体可用。

需要后端签发语音凭据时，先启动受鉴权代理，并把 `ZHIJIE_VOICE_URL` 设为客户端可访问的代理地址（正式环境使用 `wss://`，仅本机开发允许回环 `ws://`），然后设置 `ZHIJIE_VOICE_ENABLED=true` 再启动后端。`ZHIJIE_VOICE_ADMIN_URL` 仅供控制面访问 SFU；不要把 7880 直接发给客户端。SFU 启动脚本不会设置或覆盖客户端地址；缺少入口或与管理端点相同会阻止启用语音的后端启动。默认关闭语音不需要这些配置。结束后运行 `./scripts/local-preview/stop-voice.ps1`。

鉴权入口组合测试：SFU 就绪后设置 `ZHIJIE_TEST_NGINX` 为本机 nginx 可执行文件绝对路径，设置 `ZHIJIE_TEST_VOICE_MEDIA=true`，从 `workspace` 执行 `./mvnw.cmd -pl control-plane -Dtest=VoiceSignalingIntegrationTest test`。测试独立启动 PostgreSQL、控制面随机端口和 nginx，签发两名成员凭据，经入口执行双向 Rust PCM/移除，再检查退出后的旧凭据及新代次。凭据仅通过子进程环境传递，结束删除含密钥配置。SFU 须由调用者停止。已在本机通过，不能替代目标服务器、TLS 或跨机验证。完整配置见 `docs/engineering/external-services.md`。
