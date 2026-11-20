# Windows x64 未签名候选包

## 本机试用（不安装）

**本机试用入口**：`target/trial-kit-0dcaf88/zhijie-windows-x64-trial.zip`（93,054,538 字节，SHA-256：`533c441ce0df03fa1c5aa7d34e550b88c3b018fb7940fc7a98d80b03750b0477`）。后端来自 `0dcaf88`：在 V15 独立同步完成标记基础上，V16 将外部撤销与同步拆为独立定时器和事务，并持久化有界指数退避，避免慢外部目标阻塞下一轮同步/会话复核或失败队首永久饿死后续外部事件。保留原固定字段发送诊断及注销/重连保护。外部失败仍待重试，不代表渠道已接入或五秒撤销 SLA 已验收。桌面继续使用与之配套且无需改动的 `399db61` 构建。核对摘要后解压到 D 盘全新目录，按包内 `README.md` 启动；如需保留旧数据，先停库、冷备并恢复，不覆盖原目录或在同一数据库混跑旧新版。包内不含 JDK、Python、WebView2、安装器、试用数据库或凭据。

新包首次启动、停库重启和冷备恢复后均通过实际接口登录、读取原消息、双设备接管和按租约退房；旧设备申请语音和网络票据均被拒绝，当前设备获得与租约匹配的网络票据。日志：`target/trial-kit-0dcaf88/relocation.log`。实际离线后端还通过登录、重启、两种同步认证、房间重开以及普通日志/管理记录的秘密扫描，见同目录 `backend-lifecycle.log`。与 `9ba3623` 整包逐条比对，345 个条目中新增三个派发/状态类和 V16，变更七个后端条目，桌面和依赖不变，见 `comparison.log`；其中 `DispatchResult` 内部类仅行号变化，`javap` 指令/描述符及过滤行号后的完整元数据一致，见 `inner-class.log`。源码失败复现、迁移边界及五十四项完整相关回归见 `docs/engineering/external-revocation-isolation.md`。历史发送告警和网络偶发退出仍开放。桌面内嵌界面的 170 项检查记录仍在 `target/trial-kit-399db61/embedded-browser.json`，本轮未重新执行界面检查。未启动原生窗口，未实测真实通话或跨机网卡。

从本机 `workspace` 复建：`python -B "packaging/windows/pack-trial-kit.py" --output "target/新的试用包.zip"`。输入路径和摘要锁定在 `packaging/windows/trial-kit.json`，输出文件必须不存在；此步骤只打包已有候选，不负责构建、签名或发布。

复测命令（从 `workspace` 运行）：`python -B "packaging/windows/test-trial-kit-relocation.py" "target/trial-kit-0dcaf88/zhijie-windows-x64-trial.zip" --sha256 533c441ce0df03fa1c5aa7d34e550b88c3b018fb7940fc7a98d80b03750b0477 --java-home "D:/Program Files/jdk-25" --check-grants --check-restore`。测试使用临时数据库和随机本机端口，不接触已有试用数据。旧整包 `target/trial-kit-9ba3623/zhijie-windows-x64-trial.zip` 保留，不包含 V16 和本轮调度/退避修复。

本轮六项打包单元检查通过，整包使用包内冷备工具恢复 1,112 个文件后再次登录、读取消息和核验设备资格通过；独立后端的 V15 旧库升级/原库回退/新版库重新启用见 `upgrade-roundtrip.log` 与[后端说明](preview-backend.md)。`release-final.log` 发布契约通过，但真实 macOS 签名检查跳过，安装器及容量仍未验收。所有新增输出和临时文件在 D 盘，结束后删除本轮构建副本与临时目录共 1,256,744 字节，生命周期测试的一次性解压/数据库目录由测试在停库后清理；保留两个 ZIP、源码归档、日志和字节码比对证据，不删除旧试用包或共享缓存。源码测试临时目录另清理 102,446,035 字节。清理后 C/D 可用约 10.19/45.84 GiB，未清理 C 盘其他程序文件。

文档更新后 `post-doc-release.log` 再次验证发布契约通过，仍跳过真实 macOS 签名检查。该次复核的 D 盘 `target/trial-kit-0dcaf88/verification-temp/` 清理请求被执行安全策略拒绝，保留原目录，不改用其他方式删除；前述已清理字节数不包含它。

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
