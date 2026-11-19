# Windows 本机试用后端（离线包）

**更新候选**：`target/backend-f67715c/preview-backend.zip` 包含并发删除群消息修复，大小为 140,633,165 字节，SHA-256 为 `1b24c669645ed6101ec9b23f87068c9750ffdfc13406537a5a9c881ac27c50b1`。这是后端更新，没有重建桌面。下述 `649e830` 是留存的旧候选。

在这台构建机的 `workspace` 下，`target/trial-649e830/preview-backend.zip` 是与桌面候选 `649e830` 同版的后端。它不是生产发布包。压缩包 SHA-256：`f8fd92f5be33f09e78082468cab5eb712254565c2ff688b91215cdb80fe98702`。只使用来源可信、摘要匹配的包；摘要本身不是签名。包内没有 JDK、账号、密码、试用数据库或桌面程序。

在已配备 **Windows x64、JDK 25、Windows PowerShell 和系统 .NET Framework 4.x 编译器** 的普通用户环境，把包解压到 D 盘可写的全新目录（路径不要含分号）。从解压目录执行：

```powershell
& "./scripts/local-preview/start.ps1" -JavaHome "D:/Program Files/jdk-25" -HttpPort 18829 -DatabasePort 15439
```

按本机位置替换 JDK 路径。首次启动设置 `trial@example.test` 的密码；数据库只在解压目录内创建，重启保留。使用同一台机器上的桌面，控制面指向 `http://127.0.0.1:18829`。退出桌面后从解压目录执行 `& "./scripts/local-preview/stop.ps1"`。不需要 Maven、原构建目录或联网下载依赖。不要把已经启动过的目录（含数据库）当作干净包分发。邮件和语音沿用默认关闭配置，不为异机桌面开放端口。

**保留旧试用数据。** 本机已验证从 `649e830` 升级到 `f67715c`。不要覆盖旧目录：先正常停止旧包并确认后端和数据库端口已释放，再从 `workspace` 执行 `python -B "ops/database/cold-backup.py" snapshot --source "<旧目录>/target/local-preview/postgres" --destination "<本人专用的新备份目录>"`。用 `python -B "ops/database/cold-backup.py" verify --backup "<备份目录>" --require-directories` 核对通过后，将备份内的 `data` 整个目录（含空目录）复制为全新解压目录下的 `target/local-preview/postgres`，再首次启动新包。若目的数据目录已存在则停止，不要合并两份数据库。保留旧目录和原备份；切回旧目录不会带回升级后才写入的数据。这不是任意 PostgreSQL 大版本间的升级办法。

**复建与验证。** 对可信的干净 Windows 源码先执行本地试用说明中的 `test-compile` 和 `dependency:build-classpath`。然后从工具所在工作区运行：

```powershell
python -B "packaging/windows/pack-preview-backend.py" --workspace "<已构建的 workspace>" --repository "<对应的 Maven 仓库>" --output "<D 盘已有目录>/preview-backend.zip"
& "./packaging/windows/test-backend-zip.ps1" -Package "<D 盘已有目录>/preview-backend.zip" -JavaHome "D:/Program Files/jdk-25"
```

给回归命令添加 `-PreviousPackage "<旧试用包.zip>"` 即可验证升级：用独立旧包写消息并停库，核对冷备份，在新包恢复并校验账号、群、房间和消息，再实际删除测试消息。不加此参数则验证同版重启，两种模式都不接触现有试用库。

生成器不覆盖同名文件。它只复制试用启动脚本、控制面类、与源码一致的应用配置/映射/迁移和类路径所列 JAR，保留依赖顺序，清单中仅有包内相对路径。回归只针对自行生成的可信包，使用独立目录和回环端口；用例验证本机移位解压后的登录与重启，不代表第二台机器或其他平台已验收。
