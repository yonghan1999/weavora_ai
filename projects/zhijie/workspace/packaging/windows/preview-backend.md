# Windows 本机试用后端（离线包）

**首次试用**：当前后端来自提交 `6a908d1`，已在本机解压到全新目录 `target/trial-backend-6a908d1`，未创建账号或数据库。离线包 `target/backend-6a908d1/preview-backend.zip` 为 140,638,964 字节，SHA-256：`0ce0c21e2d7af97539374fa3496acf197a2f6738bb418ecb88cc0ff3316fb4f4`。330 项包内容与上一版对比仅三个应用类新增或变化，依赖未变。启动后可在同一窗口按[桌面试用说明](candidate-build.md)继续。

已分别验证同版重启，以及从上一版 `target/backend-cwd-20261120-035936/preview-backend.zip` 停库冷备迁入。两种情况下，恢复后均能登录、读取原有群/房间/消息，且使用认证头和旧查询参数的两个 WebSocket 客户端均收到同一条消息删除事件。记录见新 zip 旁的 `restart.log` 和 `upgrade.log`。已使用上一版的用户不必更换正在使用的目录；新版桌面使用认证头时，须先更新后端。

**已有试用数据**：`target/backend-f67715c/preview-backend.zip` 为 140,633,165 字节，SHA-256 为 `1b24c669645ed6101ec9b23f87068c9750ffdfc13406537a5a9c881ac27c50b1`。继续使用旧桌面和旧库时，启动和停止都应使用旧解压目录里的脚本。换用桌面 `3bbc939` 时，先将旧库迁入后端 `6a908d1`。下述 `649e830` 是更早的留存候选。

在这台构建机的 `workspace` 下，`target/trial-649e830/preview-backend.zip` 是与桌面候选 `649e830` 同版的后端。它不是生产发布包。压缩包 SHA-256：`f8fd92f5be33f09e78082468cab5eb712254565c2ff688b91215cdb80fe98702`。只使用来源可信、摘要匹配的包；摘要本身不是签名。包内没有 JDK、账号、密码、试用数据库或桌面程序。

在已配备 **Windows x64、JDK 25、Windows PowerShell 和系统 .NET Framework 4.x 编译器** 的普通用户环境，把包解压到 D 盘可写的全新目录（路径不要含分号）。从解压目录执行：

```powershell
& "./scripts/local-preview/start.ps1" -JavaHome "D:/Program Files/jdk-25" -HttpPort 18829 -DatabasePort 15439
```

按本机位置替换 JDK 路径。首次启动设置 `trial@example.test` 的密码；数据库只在解压目录内创建，重启保留。使用同一台机器上的桌面，控制面指向 `http://127.0.0.1:18829`。退出桌面后从解压目录执行 `& "./scripts/local-preview/stop.ps1"`。不需要 Maven、原构建目录或联网下载依赖。不要把已经启动过的目录（含数据库）当作干净包分发。邮件和语音沿用默认关闭配置，不为异机桌面开放端口。

**保留旧试用数据。** 正常停止旧包，确认后端和数据库端口已释放，且迁移期间不会重新启动。从 `workspace` 执行：

```powershell
python -B "ops/database/cold-backup.py" snapshot --source "<旧目录>/target/local-preview/postgres" --destination "<本人专用的新备份目录>"
```

快照成功后，将备份内的 `data` 整个目录（含空目录）复制为全新解压目录下的 `target/local-preview/postgres`。若目的数据目录已存在则停止，不要覆盖或合并。**首次启动新包前**执行：

```powershell
python -B "ops/database/cold-backup.py" verify --backup "<备份目录>" --restored-data "<新目录>/target/local-preview/postgres"
```

只有出现 `COLD_RESTORE_COPY_OK` 才启动新包。这一步把备份和实际恢复目录的每个文件摘要及目录结构都与清单核对；验证失败不要启动。保留旧目录和原备份；切回旧目录不会带回升级后才写入的数据。这不是跨 PostgreSQL 主版本升级的方法。

**复建与验证。** 对可信的干净 Windows 源码先执行本地试用说明中的 `test-compile` 和 `dependency:build-classpath`。然后从工具所在工作区运行：

```powershell
python -B "packaging/windows/pack-preview-backend.py" --workspace "<已构建的 workspace>" --repository "<对应的 Maven 仓库>" --output "<D 盘已有目录>/preview-backend.zip"
& "./packaging/windows/test-backend-zip.ps1" -Package "<D 盘已有目录>/preview-backend.zip" -JavaHome "D:/Program Files/jdk-25"
```

给回归命令添加 `-PreviousPackage "<旧试用包.zip>"` 即可验证升级：用独立旧包写消息并停库，核对冷备份，在新包恢复并校验账号、群、房间和消息，再实际删除测试消息。不加此参数则验证同版重启，两种模式都不接触现有试用库。验证支持两种同步认证的候选包时再添加 `-CheckSyncAuth`，要求两个订阅者都收到实际删除事件；旧版候选不启用此选项。

生成器不覆盖同名文件。它只复制试用启动脚本、控制面类、与源码一致的应用配置/映射/迁移和类路径所列 JAR，保留依赖顺序，清单中仅有包内相对路径。回归只针对自行生成的可信包，使用独立目录和回环端口；用例验证本机移位解压后的登录与重启，不代表第二台机器或其他平台已验收。
