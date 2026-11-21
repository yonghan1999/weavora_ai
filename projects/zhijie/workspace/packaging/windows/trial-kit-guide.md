# 织界 · Windows 本机试用

桌面内的 FFmpeg 另附其 Chromium 源码版本指定的 `third-party/desktop/FFmpeg-ad41607-CREDITS.chromium.txt`，与 WebRTC 总声明同时保留。它提供署名和许可文本，不是正式发行授权。

`third-party/desktop/` 含桌面使用的 WebView2 SDK 加载器的许可与 NOTICE、LiveKit/WebRTC 桥接层声明，以及 Helper 使用的 BoringTun 许可。其中 `WebRTC-win-x64-a545de4-LICENSE.txt` 是 Windows x64 预编译 WebRTC 发行包自己的 26 节声明，包含编解码器材料，区别于桥接层 NOTICE。Wintun 的许可仍位于 `target/desktop/app/WINTUN-LICENSE.txt`。这里不附 WebView2 Runtime。随附材料不表示已审定编解码器专利、源码提供或正式分发条件。

后端依赖中，62 个 JAR 内含已索引的许可材料，另 14 个的补充原文在 `third-party/`。打开 `third-party/sources.json`，可按 JAR 文件名查到原文、上游来源和 SHA-256。Windows 内嵌数据库 JAR 的 `nativePayload` 将 27 个第三方 DLL 对应到 14 个组件及参考声明。调试器、system_stats、winpthreads 的精确二进制版本尚未证实，故保留为空；声明文件的提交号不是产生 DLL 的提交号。PostgreSQL 数据库本体与 Zonky 打包项目的声明分列。这不是正式分发批准。

这是未签名的 Windows x64 试用包，不是安装器。需要 WebView2 Runtime、Python 3.12+、JDK 25 和 Windows PowerShell（含系统 .NET Framework 4.x 编译器）。不需要 Git、Maven、Rust 或管理员权限。仅供本机试用，不开放防火墙，也不安装驱动。试用账号不能用于真实服务。

先核对压缩包 SHA-256 与提供者给出的值，只使用可信来源。将整个包解压到 `D:/zhijie-trial` 这类**全新、短路径、可写目录**（路径不要含分号），不要嵌套在多层项目目录或与旧版合并；嵌入 PostgreSQL 在过深路径下可能无法启动。本版桌面在退房时会向后端提交本次入房的租约；升级或回退都要成套使用同一包内的桌面与后端，否则旧桌面可能无法正常退房。分别从解压目录打开两个普通 PowerShell 窗口。

1. 第一个窗口启动后端，按提示设置至少 12 位的试用密码，等到“试用环境已就绪”。请把 JDK 路径换成本机实际路径。

   ```powershell
   & "./backend/scripts/local-preview/start.ps1" -JavaHome "D:/Program Files/jdk-25" -HttpPort 18829 -DatabasePort 15439
   ```

2. 第二个窗口先核对桌面文件，并检查所选本机端口的后端是否报告 `UP`。这个检查不登录、不启动程序，也不能证明该端口一定属于本包；请使用第一步启动时显示的端口。核对通过后执行第二条命令启动，使用 `trial@example.test` 和刚设置的密码登录。

   ```powershell
   python -B "scripts/local-preview/trial-desktop.py" "target/desktop" --http-port 18829 --check-backend
   python -B "scripts/local-preview/trial-desktop.py" "target/desktop" --http-port 18829
   ```

   后端尚未启动时，可改用 `--check-only` 单独校验桌面文件。若健康检查失败，先核对两个窗口的端口与第一步的就绪提示，不要因此重置数据库或停止其他进程。

3. 在「本地试用群」发送一条消息。退出桌面后，从解压目录正常停止后端。下次从同一目录启动，使用原密码即可查看保留的消息。

   ```powershell
   & "./backend/scripts/local-preview/stop.ps1"
   ```

数据保存在 `backend/target/local-preview/postgres`，桌面配置在 `target/desktop/webview`。不要删除唯一的数据副本，也不要分发已使用过的解压目录。如果端口已占用，不要停止未知进程；可在启动命令中选用空闲端口，并使桌面 `--http-port` 与后端 `-HttpPort` 一致。邮件默认关闭；本步骤不验证真实通话或跨机虚拟局域网。

## 已有试用数据：迁入全新目录

如果希望保留旧包里的账号、群、房间和消息，先不要启动新包。仅适用于相同 PostgreSQL 主版本的 Windows 试用后端；不迁移桌面缓存。备份含账号与消息数据，只放在本人可访问的 D 盘目录，不上传。预留备份和新数据目录两份副本的空间。

1. 退出旧桌面，运行**旧包**的 `backend/scripts/local-preview/stop.ps1`。即使脚本提示已停止，若 `backend/target/local-preview/postgres/postmaster.pid` 仍存在，不能确认数据库正常退出：先检查该试用库的进程和日志，不要备份、迁移或手动删除锁文件。确认正常停库后，迁移期间不要重启。
2. 在**新包解压根目录**打开 PowerShell，把 `$old` 改为旧包的解压路径，`$backup` 改为尚不存在的备份目录：

   ```powershell
   $old = "D:/织界旧版"
   $backup = "D:/织界私有备份/升级前"
   python -B "ops/database/cold-backup.py" snapshot --source "$old/backend/target/local-preview/postgres" --destination "$backup"
   ```

3. 只有看到 `COLD_BACKUP_OK` 才执行恢复。目标必须不存在，工具不会覆盖、合并或删除原库：

   ```powershell
   python -B "ops/database/cold-backup.py" restore --backup "$backup" --destination "backend/target/local-preview/postgres"
   ```

4. 看到 `COLD_RESTORE_COPY_OK` 后，按本文开头的命令启动新包，用**旧账号密码**登录并核对原消息。如果校验失败或意外提示初始化账号密码，停止操作，保留旧目录和快照，不要继续初始化。

本版后端包含 V18。从 V17 升级时，新增的是群主人工审核结果的同步通知：审核提交后，在线群成员可以收到更新；不重新处理旧申请，也不自动恢复群主。V18 本身不清退活动。

若从 V16 或更早版本升级，还会经过 V17：当时正在参加、无法可靠归属某次登录的活动会被安全清退，账号、群、房间和消息保留；登录后显式重新加入即可。V17 起活动绑定具体登录，注销或刷新重放使该登录的旧资格失效，普通令牌刷新不应掉房。不要用旧程序打开已升级的数据库，回退只用升级前原库。

**要切回旧版？** 先停新包，为新版新增数据另做一份冷备。仍从新包根目录运行 `python -B "ops/database/cold-backup.py" verify --backup "$backup" --restored-data "$old/backend/target/local-preview/postgres"`；核对通过后再从旧目录启动。切回旧库不会带回新版写入，两份数据库不会自动合并。
