# Windows x64 未签名候选包

## 本机试用（不安装）

本机首次试用请搭配**桌面 `3bbc939` 和[离线后端](preview-backend.md) `6a908d1`**。新版桌面通过认证请求头同步消息，不再把访问令牌放入 WebSocket URL。旧后端不支持这种握手方式：已有试用数据时，先保留原桌面和后端组合，或按后端说明停库、备份并核验迁移后再换桌面。不要覆盖数据库，或在同一端口启动两套后端。旧桌面 `target/trial-ab8d325` 保留；新便携目录不共享旧版 WebView 数据，可能需要重新登录。

桌面安装包 `target/trial-3bbc939/织界_0.1.0_x64-setup.exe` 为 12,080,333 字节，SHA-256：`f84e6d376d23d745632103b92e5cceeee307dbecdcd0e68d5460dfeaa62b7546`。普通用户无需运行这个未签名安装器。包内八个产品文件与构建输入、便携副本逐一匹配。从该提交导出的源码通过 11 项界面单测和 156 项无头浏览器检查；15 项 Rust 核心测试包含对握手 URL 和认证头的断言。使用同版 Rust 同步探针连接独立解压的后端包，验证了实时接收、断线补收和令牌轮换后重连。记录在 `target/win-3bbc939/`：`unit.log`、`browser.json`、`core.log`、`package-sync.log`、`payload.json` 和 `portable.log`。这些检查没有启动图形桌面、运行安装器或验证实际通话。

以下命令从本机 `projects/zhijie/workspace` 的普通 PowerShell 窗口执行。

```powershell
# 首次试用：在本机提示中设置密码，等待“试用环境已就绪”
& "./target/trial-backend-6a908d1/scripts/local-preview/start.ps1" -JavaHome "D:/Program Files/jdk-25" -HttpPort 18829 -DatabasePort 15439
```

```powershell
# 先核对便携目录，再启动桌面，使用 trial@example.test 登录
python -B "scripts/local-preview/trial-desktop.py" "target/trial-3bbc939" --http-port 18829 --check-only
python -B "scripts/local-preview/trial-desktop.py" "target/trial-3bbc939" --http-port 18829
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
