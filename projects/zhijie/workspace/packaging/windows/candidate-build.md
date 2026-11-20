# Windows x64 未签名候选包

## 当前本机试用（不安装）

最新整包：`target/trial-kit-d9a9dfe/zhijie-windows-x64-trial.zip`（93,074,365 字节，SHA-256：`1bb626d926daf265994a20b5a59ec5bf60ebfcd2664bc9d0a314d4504a8e5ca1`）。桌面、Helper 和后端均从已推送 `d9a9dfe` 干净归档重新构建，不混入工作区历史改动。Helper 将 Peer 会话绑定账号、活动代次及租约，并在直连、中继、出站和定时处理时执行授权到期检查；后端下发票据/租约较早期限。正常同身份续票可复用会话，换身份不能继承旧会话密文。源码回归与边界见 `docs/engineering/peer-authorization-identity.md`；无新依赖或迁移，仍为 V16。

未签名安装器保留在 `target/trial-d9a9dfe/织界_0.1.0_x64-setup.exe`（12,093,575 字节，SHA-256：`d339ffe8a3ba6159a4cc38a09c02ac9597d9c589186bbb5c665b83de12bd638f`），本机便携试用无需运行。首次桌面编译通过后，NSIS 下载超时；保留失败日志，核对已有 D 盘 NSIS 和 Tauri 插件固定摘要后复用缓存，仅重跑打包成功。见 `target/windows-peer-d9a9dfe/desktop-release.log`、`desktop-bundle-retry.log`。八个产品文件与本轮构建输入一致，见 `payload.json`；未运行安装器、桌面或 Helper。

本轮 13 项界面单测通过；从新桌面二进制提取的一份 HTML、六个脚本和三份样式均与上一已验证桌面一致，其无头浏览器 170 项检查通过，见 `target/windows-peer-d9a9dfe/ui-unit.log`、`target/trial-kit-d9a9dfe/embedded-binary.log`、`embedded-browser.json`。四项后端打包及两项整包单测通过；整包实际登录、消息、重启、1,112 文件冷备恢复和设备资格拒绝通过，见同目录 `backend-unit.log`、`kit-unit.log`、`relocation.log`。

`comparison.log` 首次严格差异检查发现内部类行号变化后停止，未放宽未知变化；`target/windows-peer-d9a9dfe/inner-class.log` 核对 `NetworkPeerService$Peer` 除调试行号外的完整 `javap` 元数据相同。最终 `comparison-final.log` 逐条核对 348 个整包条目与 336 个后端条目：后端只变更 `NetworkPeerService.class` 及上述内部类；整包另变更桌面、Helper 和清单，其余依赖、脚本、驱动、许可及资源字节不变。

独立后端同版重启、两种同步认证、房间重开与普通日志/管理记录秘密扫描通过，见 `target/trial-kit-d9a9dfe/backend-lifecycle.log`。`f86cef2`→新版独立副本→原库回退→新版再次启用的四次启动通过，分别核对旧/新版停库快照 1,111/1,112 文件，见 `upgrade-roundtrip.log`；没有覆盖已有用户数据或在新版库运行旧程序。

全部验证进程退出后，核对 D 盘绝对路径、逐级无重解析点和进程引用，仅清理本轮源码副本及构建/生命周期临时目录 25,624,436 字节，见 `target/windows-peer-d9a9dfe/cleanup-build.log`。保留制品、源码归档、提取的界面、构建日志和共享编译/打包缓存；release 首次完整编译产生的共享缓存仍在 D 盘，本轮末 C/D 可用约 10.17/39.71 GiB。未清理其他程序文件、未跟踪源码或历史被拒目录。

仅本轮三个文件的暂存区独立候选通过工作区、691 项完整依赖、兼容/发布/性能契约和三层许可材料完整性检查，见 `target/windows-peer-d9a9dfe/package-snapshot.log`；实际 macOS 签名一项明确跳过，容量验收与许可人工审核未完成。验证退出后另清候选快照与临时目录 18,559,372 字节，见 `cleanup-snapshot.log`，累计清理 44,183,808 字节；制品和共享缓存保留。

核对摘要后解压到 D 盘全新目录，按包内 `README.md` 启动，不覆盖原目录或数据库。必须成套替换桌面、Helper 和后端；只换后端不能获得客户端 Peer 保护。独立 Relay 不在 ZIP 内，匹配服务、证书和管理配置须另外部署；主动撤销默认关闭，邮件与外部语音不自动启用。持有同一私钥的旧端重新握手仍无法与新端区分，换设备应使用新密钥；不代表真实音频、管理员 TUN、跨机游戏、完整五秒 SLA、三平台安装签名、目标容量或许可证人工审核完成。正式阶段仍 E。

## 上一版本试用（f86cef2）

最新整包：`target/trial-kit-f86cef2/zhijie-windows-x64-trial.zip`（93,061,790 字节，SHA-256：`588d1bf04507adf9dc842adf7d5526670806e5661bf23d145c9303435f233929`）。后端从已推送 `f86cef2` 干净归档独立构建，桌面仍为 `399db61`。本版后端提供版本化 Relay 授权快照字段及票据/租约较早期限；匹配的新 Relay 将逐包远程验权改为有界内存快照、独立续验和硬截止，保留主动撤销。源码验证见 `docs/engineering/relay-authorization-snapshot.md`：104 项原生测试通过、8 项夹具跳过，21 项 Java 回归及新 JAR 独立迁移角色检查通过。无新依赖或迁移，仍为 V16。

**独立 Relay 不在试用包内。** 包内只有支持新快照响应的控制面；要使用新的 Relay 数据面行为，需另行部署匹配 `f86cef2` 的 Relay 二进制、证书和管理配置。新 Relay 对旧控制面缺少字段安全拒绝，不把混版当成已验收；成套停机升级/回退，不覆盖既有库。邮件/外部语音未自动启用，主动 Relay Outbox 撤销仍默认关闭。

六项打包单元测试、整包实际登录/消息/重启、1,112 文件冷备恢复、设备接管及旧资格拒绝通过，见 `target/trial-kit-f86cef2/backend-unit.log`、`kit-unit.log`、`relocation.log`。独立后端同版重启、两种同步认证、房间重开及普通日志/管理记录秘密扫描通过，见 `backend-lifecycle.log`。`a039409`→新版独立副本→原库回退→新版再次启用的四次启动通过，旧/新版分别核对 1,111/1,112 文件，见 `upgrade-roundtrip.log`。`comparison.log` 逐项核对 348 个整包条目与 336 个后端条目，仅两个 Relay 响应/验证类变化，桌面、依赖及其余内容不变。

`target/relay-revocation-280bca4/authorization-cache-package-snapshot.log` 对暂存区独立候选验证工作区、691 项依赖、兼容/发布契约及三层许可材料完整性通过；实际 macOS 签名一项明确跳过。验证进程退出后检查 D 盘绝对路径、逐级无重解析点和进程引用，清理本轮源码副本、临时目录与验证快照 19,806,671 字节，见 `target/trial-kit-f86cef2/cleanup.log`；本轮源码验证另清 120,965,947 字节，累计 140,772,618 字节。保留两个 ZIP、源码归档和日志，未触碰历史被拒清理目录、共享缓存或未跟踪源码。清理后 C/D 盘可用约 10.17/42.74 GiB。

核对摘要后解压到 D 盘全新目录，按包内 `README.md` 启动；数据迁移和回退见 `preview-backend.md`。没有运行桌面、Helper 或安装器，不代表人工音频、管理员 TUN、跨机、三平台签名安装、目标容量或许可证人工审核完成。正式阶段仍 E。

## 上一版本试用（a039409）

最新整包：`target/trial-kit-a039409/zhijie-windows-x64-trial.zip`（93,061,381 字节，SHA-256：`c5428b09b092964a08145332a427b414d5472b30b6929b806256f65a2ddca7fa`）。后端从已推送 `a039409` 干净归档独立构建，桌面继续沿用 `399db61`。新增语音/网络撤销的固定分区调度和独立批次事务，避免一个通道阻塞另一个；不增加依赖、数据库迁移或自动启用外部服务。实现、源码回归及尚存的同通道积压边界见 `docs/engineering/external-revocation-channels.md`。独立 Relay、SFU、证书及密钥仍须另行配置，包内不提供或自动部署。

整包实际登录、消息、停库重启、1,112 文件冷备恢复、设备接管及旧资格拒绝通过，见 `target/trial-kit-a039409/relocation.log`。独立后端同版重启、两种同步认证、房间重开与普通日志/管理记录隐私检查通过，见 `backend-lifecycle.log`；四项后端打包及两项整包测试通过。`comparison.log` 逐条核对 348 个整包条目与 336 个后端条目，仅新增两个通道/调度类并变更八个相关条目，其余依赖、桌面及后端内容不变。

`e45156f` 旧库→新版独立副本→原库回退→新版重新启用的四次启动均通过，旧/新版停库快照分别核对 1,111/1,112 个文件，见 `target/trial-kit-a039409/upgrade-roundtrip.log`。没有覆盖现有用户数据，也没有在新版库上直接运行旧程序。

`target/relay-revocation-280bca4/channel-package-snapshot.log` 对暂存区独立候选的工作区、691 项完整依赖、兼容/发布契约及三层许可材料检查通过，macOS 实际签名测试明确跳过。全部进程退出后核验 D 盘路径与重解析点，清理本轮源码副本、临时目录及打包验证快照共 19,768,296 字节，见 `target/trial-kit-a039409/cleanup.log`；保留两个 ZIP、源码归档和日志。此前源码验证已清理 120,797,173 字节，本轮累计 140,565,469 字节。生命周期临时库由测试停库后自行清理，历史清理被拒目录未重试，未删除共享缓存或其他程序文件。清理后 C/D 可用约 10.20/43.02 GiB。

核对摘要后解压到 D 盘全新目录，按包内 `README.md` 启动。不要覆盖已有试用目录或数据库；保留数据和回退步骤见 `preview-backend.md`。没有启动原生桌面、Helper 或安装器，不代表人工音频、管理员 TUN、跨机、三平台签名安装、目标容量或许可证审核已完成。正式阶段仍 E。

## 上一版本试用（e45156f）

最新整包：`target/trial-kit-e45156f/zhijie-windows-x64-trial.zip`（93,058,786 字节，SHA-256：`bf0bb6deef8382fb1eebca5564cd6de80a9b22f01944fb68f029323c35246f26`）。从已推送 `e45156f` 干净归档独立构建后端，桌面沿用 `399db61`。后端新增可选 Relay 主动撤销适配与配置，默认关闭；整包不包含独立 Relay 服务、其证书或管理密钥。真实控制面/Relay 组合已在源码测试验证，启用方法与边界见 `docs/engineering/relay-outbox-revocation.md`。

核对摘要后解压到 D 盘全新目录，按包内 `README.md` 启动。新包的首次登录、消息、停库重启、冷备恢复、双设备接管及旧设备票据拒绝通过，见 `target/trial-kit-e45156f/relocation.log`；冷备恢复核对 1,112 个文件。独立后端同版生命周期及 `0dcaf88`→新版→原库回退→新版重新启用通过，见 `backend-lifecycle.log`、`upgrade-roundtrip.log`；两种同步认证、房间重开、普通日志和管理记录秘密扫描均通过。四项后端打包和两项整包检查通过。未覆盖用户现有试用目录。

`comparison.log` 逐条核对 346 个整包条目与 334 个后端条目：只新增 `RelayRevocationTarget.class`，变更默认配置及未配置 Relay 目标的条件注解；其余依赖、桌面与后端字节不变。保留两个 ZIP、源码归档、构建和验证日志；全部进程退出后核验路径及重解析点，清理独立构建副本与临时目录共 1,270,582 字节，生命周期测试的一次性数据库副本由测试停库后清理。清理后 C/D 可用约 10.20/43.19 GiB。历史清理被拒目录继续保留。正式阶段仍 E，真实音频、TUN/跨机、三平台安装签名、目标容量和许可证审核待完成。

## 前一版本试用记录

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
