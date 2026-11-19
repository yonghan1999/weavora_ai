# Windows x64 未签名候选包

## 本机试用（不安装）

桌面候选 `ab8d325` 区分服务断连和登录失效。已登录时，服务暂不可达会显示自动重连提示，并允许手动重试；只有确认会话失效才要求重新登录。安装包 `target/trial-ab8d325/织界_0.1.0_x64-setup.exe` 为 12,079,507 字节，SHA-256：`0a555225b666834c8fdac867d48a9591eb44b2f3e0e92efc38d723dbd9f5ba29`。这是未签名候选包；普通用户试用无需运行安装器。

从该提交导出的源码通过 11 项界面单测和 156 项无头浏览器检查，包括窄屏断连提示和恢复时保留输入焦点。安装包内八个产品文件与构建输入逐个匹配，便携目录通过只读自检；六份界面脚本和 `desktop.css` 的压缩字节均在便携桌面程序内找到，解压后与 Git 内容逐字节一致。记录位于 `target/win-ab8d325/` 的 `browser.json`、`payload.json`、`portable.log` 和 `embedded.log`。这些不是实际安装或通话验收。旧便携目录 `target/trial-fb56078` 保留；两个版本不共享 WebView 试用目录，新版本可能需要重新登录。

以下路径是本构建机 D 盘上的本地文件，不随 Git 下发。从 `projects/zhijie/workspace` 的一个普通 PowerShell 窗口依次执行即可。新版[离线后端](preview-backend.md)已解压到 `target/trial-backend-cwd`，供首次试用；它不会读取旧目录的数据库。若已在 `target/trial-backend-f67715c` 创建账号，可继续使用原后端，只运行下方桌面命令（旧启动脚本会切换目录，桌面命令需从 workspace 执行）。若要将旧数据带入新目录，先按离线后端说明停库、备份并验证。已有后端占用端口时不要重复启动。

```powershell
# 首次试用：在本机提示中设置密码，等待“试用环境已就绪”
& "./target/trial-backend-cwd/scripts/local-preview/start.ps1" -JavaHome "D:/Program Files/jdk-25" -HttpPort 18829 -DatabasePort 15439
```

```powershell
# 先核对便携目录，再启动桌面，使用 trial@example.test 登录
python -B "scripts/local-preview/trial-desktop.py" "target/trial-ab8d325" --http-port 18829 --check-only
python -B "scripts/local-preview/trial-desktop.py" "target/trial-ab8d325" --http-port 18829
# 退出桌面后，仍从 workspace 停止本试用后端；数据库保留
& "./target/trial-backend-cwd/scripts/local-preview/stop.ps1"
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
