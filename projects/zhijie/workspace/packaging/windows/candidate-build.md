# Windows x64 未签名候选包

## 本机试用（不安装）

本机首次试用请搭配**桌面 `36e5d7a` 和[离线后端](preview-backend.md) `6a908d1`**。房间暂停时，大厅和群页都会阻止新加入，但已在房内的成员可以直接返回；从另一个群返回时，聊天和草稿按群区分。可用一个房主账号暂停房间，返回大厅后从房卡回去，再从群页返回，均不应要求再次加入。

已经使用 `6a908d1` 后端的用户不用迁移数据库或重设密码。旧桌面 `target/trial-3bbc939` 保留；新便携目录不共享旧版 WebView 数据，可能需要重新登录。更早的后端不支持新版桌面的同步认证头，已有数据请按后端说明停库、备份并核验恢复副本，再换桌面。不要覆盖数据库或同时占用同一端口。

未签名安装包 `target/trial-36e5d7a/织界_0.1.0_x64-setup.exe` 为 12,078,797 字节，SHA-256：`ebc9cae4e34e40b62c28b716c50e795d5056adfdff4983c36e49f2190cefd410`。便携试用不需要运行安装器。八个产品文件与构建输入及便携副本逐一匹配；从桌面程序内取出的六个脚本、三份样式与该提交逐字节一致。界面单测 11 项通过，用这九份打包资源运行无头浏览器检查 162 项通过。记录见 `target/win-36e5d7a/` 的 `unit.log`、`embedded.log`、`embedded-browser.json`、`payload.json` 和 `portable.log`。同版源码另用两个原生测试客户端连独立后端验证了暂停后保留当前租约，见 `target/group-browser-room-channels-1330634412420445968/scenario.log`。以上不代表已运行图形桌面或已验收安装、真实语音和虚拟网卡。

以下命令从本机 `projects/zhijie/workspace` 的普通 PowerShell 窗口执行。

```powershell
# 首次试用：在本机提示中设置密码，等待“试用环境已就绪”
& "./target/trial-backend-6a908d1/scripts/local-preview/start.ps1" -JavaHome "D:/Program Files/jdk-25" -HttpPort 18829 -DatabasePort 15439
```

```powershell
# 先核对便携目录，再启动桌面，使用 trial@example.test 登录
python -B "scripts/local-preview/trial-desktop.py" "target/trial-36e5d7a" --http-port 18829 --check-only
python -B "scripts/local-preview/trial-desktop.py" "target/trial-36e5d7a" --http-port 18829
# 退出桌面后，仍从 workspace 停止本试用后端；数据库保留
& "./target/trial-backend-6a908d1/scripts/local-preview/stop.ps1"
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
