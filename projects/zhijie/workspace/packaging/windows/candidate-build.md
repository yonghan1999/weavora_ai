# Windows x64 未签名候选包

## 本机试用（不安装）

本机首次试用请搭配**桌面 `d26c0f9` 和[离线后端](preview-backend.md) `038145c`**。要试一个新活动：在房间设置中暂停加入并结束原活动，到群页点击「重新开放」。此时房间开放但没有活动；房主、群主或群管理员可以在群页点击「开启活动」并确认。开启不会替操作人加入；在活动开始后，普通成员和管理者都可以按原准入规则点击「加入房间」。

如果已使用 `target/trial-backend-038145c`，只换桌面即可，不需要迁移数据库或重设账号密码。旧桌面 `target/trial-038145c` 保留；新桌面使用独立 WebView 数据目录，可能需要重新登录。若从更早的后端升级且希望保留数据，请按后端说明先停库、冷备并核验恢复副本。不要覆盖旧库或在同一端口重复启动。

未签名安装包 `target/trial-d26c0f9/织界_0.1.0_x64-setup.exe` 为 12,082,070 字节，SHA-256：`71af406d4d980eee00395fb2e3f37e9b6a006babbbd41c461a8438f15495040a`。便携试用不需要运行安装器。八个产品文件与构建输入及便携副本逐一匹配；使用从桌面程序中提取的一份 HTML、六个脚本和三份样式运行无头浏览器检查，168 项通过，另有 13 项界面单测通过。证据见 `target/win-d26c0f9/` 的 `unit.log`、`embedded.log`、`embedded-browser.json`、`payload.json` 和 `portable.log`。同版源码使用两个原生测试客户端连接真实后端，验证了重新开放、由管理者开启新活动、普通成员收到更新并加入；见 `target/group-browser-room-channels-18126295515710723184/scenario.log`。以上不是原生图形窗口、安装器、实际通话或虚拟网卡验收。

以下命令从本机 `projects/zhijie/workspace` 的普通 PowerShell 窗口执行。

```powershell
# 首次试用：在本机提示中设置密码，等待“试用环境已就绪”
& "./target/trial-backend-038145c/scripts/local-preview/start.ps1" -JavaHome "D:/Program Files/jdk-25" -HttpPort 18829 -DatabasePort 15439
```

```powershell
# 先核对便携目录，再启动桌面，使用 trial@example.test 登录
python -B "scripts/local-preview/trial-desktop.py" "target/trial-d26c0f9" --http-port 18829 --check-only
python -B "scripts/local-preview/trial-desktop.py" "target/trial-d26c0f9" --http-port 18829
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
