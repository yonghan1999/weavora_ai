# Windows 本机试用后端（离线包）

**首次试用**：与[当前桌面](candidate-build.md)配套的后端来自 `038145c`。本机已解压到全新目录 `target/trial-backend-038145c`，未初始化数据库。离线包 `target/backend-038145c/preview-backend.zip` 为 140,640,047 字节，SHA-256：`e79323869d70d8f3530bf664038ae83448be104b012cde3bae0532b10f870847`。与 `6a908d1` 的 330 项包内容逐项比对，仅 `RoomService.class` 不同。

需另行复制或解压时，可改用 Windows 精简包 `target/backend-winonly-a5e476f/preview-backend.zip`（83,803,127 字节，SHA-256：`6da742951ba9a0b625bc4920e79ffa76858cb160e01ee5654dcd7738ef2a1453`）。与上述旧包逐文件比对，仅移除三个非 Windows PostgreSQL 二进制 JAR 并更新类路径；应用、迁移和 Windows PostgreSQL 文件字节不变。独立解压副本已通过登录、消息持久化、停库重启、房间重开和两种同步认证回归。已在试用的目录不需替换，切勿覆盖数据。此精简只影响 Windows 分发，不改变源码测试依赖。

在独立解压副本中，同版重启后验证了房内成员重新开放返回 `409 / ROOM_REOPEN_OUTSIDE_REQUIRED`；结束活动后仍暂停，房外开放后再次读取为 `OPEN` 且没有活动。另从上一版 `6a908d1` 的独立试用库停库冷备迁入，核对了 1,109 个文件和目录结构，恢复后可登录、读取原群/房间/消息并删除原消息。两种场景均验证了新旧同步认证客户端收到删除事件。记录在新 zip 旁的 `reopen-final.log` 和 `upgrade.log`。

**已有试用数据**：要体验新的房外开放规则，请换用配套后端。不要直接覆盖旧包或把旧库与新库合并。若旧试用目录已有数据，按下文冷备、迁入并逐项核验；暂不迁移可保留原桌面与后端继续使用。下述 `649e830` 是更早的留存候选。

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

给回归命令添加 `-PreviousPackage "<旧试用包.zip>"` 即可验证升级：用独立旧包写消息并停库，核对冷备与恢复副本，在新包校验账号、群、房间和消息，再实际删除测试消息。不加此参数则验证同版重启。新版可选 `-CheckSyncAuth` 来验证两个订阅者收到删除事件，或用 `-CheckRoomReopen` 来验证房内拒绝和结束活动后的房外开放；旧版不启用不支持的检查。回归不接触用户的试用库。

生成器不覆盖同名文件。它只复制试用启动脚本、控制面类、与源码一致的应用配置/映射/迁移和类路径所列 JAR，保留依赖顺序，清单中仅有包内相对路径。回归只针对自行生成的可信包，使用独立目录和回环端口；用例验证本机移位解压后的登录与重启，不代表第二台机器或其他平台已验收。
