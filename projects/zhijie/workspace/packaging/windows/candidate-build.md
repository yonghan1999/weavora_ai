# Windows x64 未签名候选包

## 本机试用（不安装）

**本机试用入口**：`target/trial-kit-ea42650/zhijie-windows-x64-trial.zip`（93,048,574 字节，SHA-256：`beaabbd7d25d12a0fe5a9ad957ff6e7671b1d7c9cb79816790bc86d3bcbb7135`）。后端来自 `ea42650`：修复关闭旧同步连接时误删新连接登记的问题，保留 `6885213` 的结构化日志脱敏及设备接管保护。桌面继续使用与之配套且无需改动的 `399db61` 构建。核对摘要后解压到 D 盘全新目录，按包内 `README.md` 启动；如需保留旧数据，先停库、冷备并恢复，不覆盖原目录。包内不含 JDK、Python、WebView2、安装器、试用数据库或凭据。

新包首次启动、停库重启和冷备恢复后均通过实际接口登录、读取原消息、双设备接管和按租约退房；旧设备申请语音和网络票据均被拒绝，当前设备获得与租约匹配的网络票据。日志：`target/trial-kit-ea42650/relocation.log`。实际离线后端还通过登录、重启、两种同步认证、房间重开以及普通日志/管理记录的秘密扫描，见同目录 `backend-lifecycle.log`。与 `6885213` 整包逐条比对，340 个条目仅 `SyncService.class` 及其 `Connection` 内部类变化，见 `comparison-final.log`；源码竞态复现与二十五项相关回归见 `docs/engineering/sync-close-registration-race.md`。桌面内嵌界面的 170 项检查记录仍在 `target/trial-kit-399db61/embedded-browser.json`。未启动原生窗口，未实测真实通话或跨机网卡。

从本机 `workspace` 复建：`python -B "packaging/windows/pack-trial-kit.py" --output "target/新的试用包.zip"`。输入路径和摘要锁定在 `packaging/windows/trial-kit.json`，输出文件必须不存在；此步骤只打包已有候选，不负责构建、签名或发布。

复测命令（从 `workspace` 运行）：`python -B "packaging/windows/test-trial-kit-relocation.py" "target/trial-kit-ea42650/zhijie-windows-x64-trial.zip" --sha256 beaabbd7d25d12a0fe5a9ad957ff6e7671b1d7c9cb79816790bc86d3bcbb7135 --java-home "D:/Program Files/jdk-25" --check-grants --check-restore`。测试使用临时数据库和随机本机端口，不接触已有试用数据。

## 旧版试用目录

以下是桌面 `d26c0f9` 和旧[离线后端](preview-backend.md)的入口，仅供已在使用这些目录的人参考。要试一个新活动：在房间设置中暂停加入并结束原活动，到群页点击「重新开放」。此时房间开放但没有活动；房主、群主或群管理员可以在群页点击「开启活动」并确认。开启不会替操作人加入；在活动开始后，普通成员和管理者都可以按原准入规则点击「加入房间」。

如果已使用 `target/trial-backend-038145c`，原有数据仍在旧目录，切换到新目录不会自动迁入。需要保留数据时，请按后端说明先停旧包、冷备并核验恢复副本；不要覆盖旧库或在同一端口重复启动。仅换后端无需重新安装桌面。

未签名安装包 `target/trial-d26c0f9/织界_0.1.0_x64-setup.exe` 为 12,082,070 字节，SHA-256：`71af406d4d980eee00395fb2e3f37e9b6a006babbbd41c461a8438f15495040a`。便携试用不需要运行安装器。八个产品文件与构建输入及便携副本逐一匹配；使用从桌面程序中提取的一份 HTML、六个脚本和三份样式运行无头浏览器检查，168 项通过，另有 13 项界面单测通过。证据见 `target/win-d26c0f9/` 的 `unit.log`、`embedded.log`、`embedded-browser.json`、`payload.json` 和 `portable.log`。同版源码使用两个原生测试客户端连接真实后端，验证了重新开放、由管理者开启新活动、普通成员收到更新并加入；见 `target/group-browser-room-channels-18126295515710723184/scenario.log`。以上不是原生图形窗口、安装器、实际通话或虚拟网卡验收。

以下命令从本机 `projects/zhijie/workspace` 的普通 PowerShell 窗口执行。

```powershell
# 首次试用：在本机提示中设置密码，等待“试用环境已就绪”
& "./target/trial-backend-b44c82c5/scripts/local-preview/start.ps1" -JavaHome "D:/Program Files/jdk-25" -HttpPort 18829 -DatabasePort 15439
```

```powershell
# 先核对便携目录，再启动桌面，使用 trial@example.test 登录
python -B "scripts/local-preview/trial-desktop.py" "target/trial-d26c0f9" --http-port 18829 --check-only
python -B "scripts/local-preview/trial-desktop.py" "target/trial-d26c0f9" --http-port 18829
# 退出桌面后，仍从 workspace 停止本试用后端；数据库保留
& "./target/trial-backend-b44c82c5/scripts/local-preview/stop.ps1"
```

## 从源码重新构建

在干净检出的 `projects/zhijie/workspace` 中运行。需要 Windows x64、Node/npm、仓库指定的 Rust 工具链、MSVC C++ 构建工具、Windows SDK、Python 3.12+ 和 7-Zip。首次准备依赖需要联网；离线编译前应已取得当前锁文件对应的 Cargo 包、匹配的 Windows x64 release WebRTC 原生库和 Tauri NSIS 工具。不要用其他版本或架构的 WebRTC 库代替。

```powershell
$env:CARGO_HOME = "$PWD/target/local-native/cargo"
$env:npm_config_cache = "$PWD/target/npm-cache"
$env:RUSTUP_AUTO_INSTALL = "0"
npm install --prefix "target/packaging-tools" --no-audit --no-fund --ignore-scripts "@tauri-apps/cli@2.12.5"
cargo fetch --locked
node "scripts/test-release.mjs" --contract
& "./packaging/windows/build-candidate.ps1"
```

已有 CLI 可通过 `-TauriCli` 指定 `tauri.js` 的绝对路径。使用仓库默认 `target` 目录；不要同时运行预览与候选构建，两者共享 `target/windows-bundle`。在干净检出中若发布配置哈希不符，先检查 `.gitattributes` 和检出文件，不要重新生成清单来跳过错误。

候选构建工作区必须位于 D 盘。脚本将 Cargo 缓存固定到 `target/local-native/cargo`、输出固定到 `target`、npm 缓存固定到 `target/npm-cache`；使用每次独立的临时目录，结束后仅删除本次临时目录并恢复调用进程环境。Tauri 的 Windows 配置启用 `useLocalToolsDir`，打包工具缓存在 `target/.tauri`，不再使用系统盘用户缓存。共享缓存和交付物不会自动删除。已有 Rust 工具链可以读取，但脚本禁用缺失工具链的自动安装；缺失时先在 D 盘准备所需工具链，不自动写入 C 盘。

上述准备命令只设置当前 PowerShell 环境，不修改系统或用户环境；关闭该窗口即可还原。首次 D 盘 Cargo 缓存为空时需先完成依赖准备；不会自动复制或删除 C 盘旧缓存。环境与失败收尾回归可单独运行 `powershell -NoProfile -NonInteractive -File "packaging/windows/test-build-candidate.ps1"`，该测试使用假子命令，不实际编译或运行安装器。

脚本先编译 UI 和 release Helper，再校验并暂存锁定的 Wintun 驱动及许可，随后构建桌面并生成 `target/release/bundle/nsis/` 下的安装器。最后只读比对包内八个产品文件与同次构建输入的 SHA-256；缺失、多余、重复、路径或内容不符均失败。配置清单不是安装器签名，也不承诺两次构建的安装器字节相同。

此流程不执行安装器、启动桌面、提权或创建网卡。安装、升级、卸载、真实设备互通和正式签名需要单独验收。
