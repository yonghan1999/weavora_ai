# Windows x64 未签名候选包

## 本机试用（不安装）

本机首次试用请搭配**同版桌面和[离线后端](preview-backend.md) `038145c`**。验证「房外重新开放」：用试用账号进入种子房间，在「房间设置」暂停加入，再结束活动。进入该群的群页，在「群内房间」点击「重新开放」并确认。房间应恢复可加入，但仍等待开启活动，不会替操作人重新入房。普通成员没有重新开放入口。

此前的桌面 `target/trial-36e5d7a` 与后端 `target/trial-backend-6a908d1` 保留。已有试用数据时，请先在旧目录正常停库，按后端说明冷备并核验恢复副本，再启动新后端；暂不迁移可继续用旧组合。不要覆盖旧数据库或同时占用同一端口。新版便携桌面使用独立 WebView 数据目录，首次需要重新登录。

未签名安装包 `target/trial-038145c/织界_0.1.0_x64-setup.exe` 为 12,081,539 字节，SHA-256：`dff808155d44399913351f8b69b17ac1f222314dc76950d20d6c9074952458d4`。便携试用不需要运行安装器。八个产品文件与构建输入及便携副本逐一匹配；使用从桌面程序中提取的一份 HTML、六个脚本和三份样式运行无头浏览器检查，165 项通过，另有 12 项界面单测通过。证据见 `target/win-038145c/` 的 `unit.log`、`embedded.log`、`embedded-browser.json`、`payload.json` 和 `portable.log`。同版源码另用两个原生测试客户端验证了房间状态同步，见 `target/group-browser-room-channels-17502457677188402546/scenario.log`。以上不是原生图形窗口、安装器、实际通话或虚拟网卡验收。

以下命令从本机 `projects/zhijie/workspace` 的普通 PowerShell 窗口执行。

```powershell
# 首次试用：在本机提示中设置密码，等待“试用环境已就绪”
& "./target/trial-backend-038145c/scripts/local-preview/start.ps1" -JavaHome "D:/Program Files/jdk-25" -HttpPort 18829 -DatabasePort 15439
```

```powershell
# 先核对便携目录，再启动桌面，使用 trial@example.test 登录
python -B "scripts/local-preview/trial-desktop.py" "target/trial-038145c" --http-port 18829 --check-only
python -B "scripts/local-preview/trial-desktop.py" "target/trial-038145c" --http-port 18829
# 退出桌面后，仍从 workspace 停止本试用后端；数据库保留
& "./target/trial-backend-038145c/scripts/local-preview/stop.ps1"
```

## 从源码重新构建

在干净检出的 `projects/zhijie/workspace` 中运行。需要 Windows x64、Node/npm、仓库指定的 Rust 工具链、MSVC C++ 构建工具、Windows SDK、Python 3.12+ 和 7-Zip。首次准备依赖需要联网；离线编译前应已取得当前锁文件对应的 Cargo 包、匹配的 Windows x64 release WebRTC 原生库和 Tauri NSIS 工具。不要用其他版本或架构的 WebRTC 库代替。

```powershell
npm install --prefix "target/packaging-tools" --no-audit --no-fund --ignore-scripts "@tauri-apps/cli@2.12.5"
cargo fetch --locked
node "scripts/test-release.mjs" --contract
& "./packaging/windows/build-candidate.ps1"
```

已有 CLI 可通过 `-TauriCli` 指定 `tauri.js` 的绝对路径。使用仓库默认 `target` 目录；不要同时运行预览与候选构建，两者共享 `target/windows-bundle`。在干净检出中若发布配置哈希不符，先检查 `.gitattributes` 和检出文件，不要重新生成清单来跳过错误。

脚本先编译 UI 和 release Helper，再校验并暂存锁定的 Wintun 驱动及许可，随后构建桌面并生成 `target/release/bundle/nsis/` 下的安装器。最后只读比对包内八个产品文件与同次构建输入的 SHA-256；缺失、多余、重复、路径或内容不符均失败。配置清单不是安装器签名，也不承诺两次构建的安装器字节相同。

此流程不执行安装器、启动桌面、提权或创建网卡。安装、升级、卸载、真实设备互通和正式签名需要单独验收。
