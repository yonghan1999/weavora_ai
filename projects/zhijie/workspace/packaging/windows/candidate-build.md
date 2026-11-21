# Windows x64 未签名候选包

## 当前本机试用（不安装）

当前 Windows x64 整包：`target/window-shadow-startup-25fdb8d/zhijie-windows-x64-trial.zip`（93,088,465 字节，SHA-256：`3859cb814023058cfd5d5bdf4faebddb66bf1369ba7e65002debdcec3cf718fc`）。解压到 D 盘全新短路径，按包内 `README.md` 操作，不覆盖旧目录。Windows 主窗口现在从创建时即禁用原生阴影；旧程序是在窗口创建后才关闭，不能排除刚显示时出现强调色细边。此项仅修正启动顺序，不移除键盘焦点提示，不修改系统主题；原生窗口蓝边、拖动和边缘缩放仍待目视与操作确认。

新旧整包的 353 个条目中，仅 `zhijie-shell.exe` 与桌面清单内容改变；后端仍为 `edb856b`。同目录 `shell-test.log` 记录 Windows release 模式 Shell 回归 41 项通过、3 项需外部夹具的测试未运行，包含创建前无阴影、无装饰、可见和可缩放的配置检查。`payload.log` 记录未运行安装器的八项文件验证；`lifecycle.log` 记录整包后端登录、重启、冷备恢复、设备接管及会话撤销。未启动产品桌面或授予正式验收。

### 历史整包 edb856b

历史整包：`target/trial-kit-edb856b/zhijie-windows-x64-trial.zip`（93,089,313 字节，SHA-256：`e080c44fe6a049e7d5a55848414ab58f953882510d18572287ec8e21d6bd884f`）。此包包含 `edb856b` 的 Bearer 鉴权优化；202 个控制面类逐字节匹配完成百账号一小时复测的类文件。长测在测试环境执行，不是在解压后的试用包上执行；指标与告警见 `docs/engineering/capacity-resource-sampling.md`。

与 `bff9649` 的整包比对：349 个条目内容不变，仅修改 3 个鉴权类并新增 1 个嵌套类。`target/trial-kit-edb856b/lifecycle.log` 记录首次登录写消息、正常重启、停库冷备恢复 1,113 个文件后从独立目录再次登录；三轮均核对消息、房间密码策略、退房、设备接管及会话撤销，停服前后健康检查符合预期。六项打包测试通过，发布配置契约通过；Windows 上未验证 macOS 实际签名。没有安装或启动原生桌面、创建网卡、验证实际通话或部署生产。

### 历史整包 bff9649

历史 Windows x64 整包：`target/trial-kit-bff9649/zhijie-windows-x64-trial.zip`（93,088,105 字节，SHA-256：`27003f7884b8a5ba4e2703281df029916ca31140d3bc89a1cec88b14370c62b1`）。相比 `1ccaf96` 的 352 个条目，仅后端 `RuntimeMigrationConfiguration.class` 变化：正式 `runtime` 配置缺少同步数据库授权时拒绝启动。桌面、Helper、依赖和 V17 迁移没有变化。

`target/trial-kit-bff9649/lifecycle.log` 记录解压后的三次真实启动：首次设密登录与写消息、正常停服再启动、停库冷备恢复到独立副本再登录。三轮均核对原账号、群、房间配置、消息、设备接管及会话撤销；后端只读探测在启动前和停服后拒绝、运行中通过。冷备恢复后的服务也已正常停止。六项打包测试通过，制品逐项比较及 201 个控制面类与已测 JAR 的核对见同目录 `comparison.log`、`package-preflight.log`。这是本机试用，不代表实机音频、TUN、目标服务器容量或正式发布验收。

### 历史整包 1ccaf96

历史制品：`target/trial-kit-1ccaf96/zhijie-windows-x64-trial.zip`（93,087,811 字节，SHA-256：`7cc6b9efae4a54033472c9987063cc1324cdb90bbf918fa2a53d541d98e94cb6`）。从全新解压目录按包内 `README.md` 启动后端，再运行 `python -B "scripts/local-preview/trial-desktop.py" "target/desktop" --http-port 18829 --check-backend`；端口须与启动时一致。它直接读取回环健康端点，不发送凭据、不走代理或重定向，不创建 WebView 目录。`--check-only` 仍仅离线检查桌面文件。健康检查不识别进程身份，不替代登录或媒体验收。

逐项比较前后两个 ZIP 的 352 个条目，只有包内说明和桌面启动脚本变化。打包及启动器 12 项测试通过；`target/trial-kit-1ccaf96/lifecycle.log` 记录该整包首次启动、停止与重启的两轮完整检查，包括密码登录、消息保留、设备接管、注销与刷新重放后的资格拒绝以及启动前/运行中/停服后的只读检查。仅启动隔离的试用后端和数据库，未运行原生桌面、安装器、Helper 或组合测试。

### 上版制品及共用后端

上版整包：`target/trial-kit-8471c58/zhijie-windows-x64-trial.zip`（93,087,117 字节，SHA-256：`5f1892ca7b8eca6f62ffbf8c11349afa2ed8b1b5c57618cfe1e1f21bd6e69cdb`）。后端从已推送 `8471c58` 干净归档构建，包含活动绑定具体登录与失效清退修复，数据库升级为 V17。注销、刷新重放及密码重置不再仅使账号凭据失效，旧活动权威资格同样拒绝；同设备另一登录必须显式加入，不继承旧活动，旧注销不能误伤接管后的新租约。源码证据和范围见 `docs/engineering/activity-auth-session-revocation.md`。

桌面和 Helper 继续使用 `cc65e0d` 便携清单与八个产品文件；差异仅测试专用 Rust 探针，不影响 release 产品，无需重复构建安装器。清单 SHA-256 为 `aa5d17c9d94b4745c8c1c0104989550e7c981f380333fdbe0868b15b9394a5ee`。新后端为 `target/trial-kit-8471c58/preview-backend.zip`（83,855,552 字节，SHA-256：`7dc40ac0b68e3ca32cab0f7373ce11f56266997178fec0e0a3b2bb15d9c3246d`）。340 个后端条目相较 V16 只新增三个会话清退类和 V17，变更九个相关顶层类、三个相关内部类和活动 mapper；352 个整包条目仅上述后端内容及包内升级说明变化。其余依赖、桌面和 Helper 字节一致，逐条核对见 `target/trial-kit-8471c58/comparison.log`。

新包在 D 盘一次性目录实际通过首次登录、消息、重启、停库冷备恢复、设备资格拒绝，以及三次启动后的注销、同设备接管和刷新重放旧票据拒绝；后端同版重启、两种同步认证、房间重开及普通日志/管理记录秘密扫描通过。另以 V16 包真实创建正在参加的活动和未到期票据，停库复制到新版，V17 拒绝旧资格且显式重入生成新租约；回退仍使用原 V16 数据，再启 V17 保留各自消息与配置。详见 `docs/engineering/windows-activity-session-revocation.md`。

核对摘要后解压到 `D:/zhijie-trial` 这类全新、短路径、可写目录，按包内 `README.md` 启动；深层嵌套会使嵌入 PostgreSQL 无法启动，不能直接在多层项目目录内解压试用。V17 不猜测历史活动属于哪个登录，升级会清退旧活动，账号、群、房间和消息保留，用户重新加入；迁移和回退必须先停库并核验冷备，不在同一数据库混跑旧新版。新包不包含独立 Relay、SFU、证书或密钥；邮件、外部语音及主动外部撤销默认关闭。没有启动原生桌面、生产 Helper 或安装器，不代表人工音频、TUN/跨机游戏、三平台安装签名、目标服务器容量、许可人工审核、全通道五秒或正式 F/G/H/I 与人工验收完成，阶段仍 E。

## 上一版本试用（cc65e0d）

最新整包：`target/trial-kit-cc65e0d/zhijie-windows-x64-trial.zip`（93,080,457 字节，SHA-256：`58f953e1c9cbe478c956e6160739adb10a0166ad5706e10320b565df60706171`）。桌面从已推送 `cc65e0d` 干净归档实际构建，包含同步恢复后及时恢复服务状态的修复；旧凭据及注销后的迟到连接不能修改新会话。源码真实断链复现与回归见 `docs/engineering/sync-service-recovery.md`，不把服务状态恢复等同于语音、TUN 或全通道撤销完成。

优化版 Core/Shell 共 55 项通过、3 个父夹具专用入口默认忽略；13 项界面单测、桌面优化构建及 NSIS 打包成功。八个产品文件与同版输入逐项核对；未签名安装器为 `target/trial-cc65e0d/织界_0.1.0_x64-setup.exe`（12,095,221 字节，SHA-256：`c92dc4e35c46005c4e4ba6113d32da5b81847d7b741f8e121497461bf84f2d98`）。桌面 SHA-256 为 `4229e44c6762878e5a389ff14a387dede53a10fad6d17439fb233ec89db59a03`，便携清单为 `aa5d17c9d94b4745c8c1c0104989550e7c981f380333fdbe0868b15b9394a5ee`。没有安装或启动桌面、生产 Helper 或安装器。

后端生产源码及构建输入与 `684e0a9` 一致，直接复用已验证的 `target/trial-kit-684e0a9/preview-backend.zip`（83,840,569 字节，SHA-256：`2628e32733850f00804698f791b42b3fb93a197fc82b460e7cb20c41cefb4ddb`）。Helper 及其生产依赖、锁文件与 `8da8814` 一致，直接复用该版二进制，SHA-256 为 `5ab71d304b3b53162da0b2df73c5e7d1e29d8d4c70fa779d36ecbaed640de6c2`。没有新增依赖、数据库迁移或默认外部服务开关。

`target/trial-kit-cc65e0d/comparison.log` 确认 1,340 个归档源文件未被构建改写，348 个整包条目仅桌面二进制及清单变化，336 个后端条目与复用 ZIP 字节一致。新二进制中提取的 10 份界面文件与 `8da8814` 一致，实际通过 170 项无头浏览器检查、无脚本错误。六项打包单测通过；整包在一次性 D 盘目录实际通过登录、消息、重启、1,112 文件冷备恢复、设备接管及旧设备语音/网络资格拒绝。复用后端本轮重新验证同版重启、两种同步认证、房间重开及普通日志和管理记录秘密扫描。详细证据与范围见 `docs/engineering/windows-sync-service-recovery.md`。

核对整包摘要后解压到 D 盘全新目录，按包内 `README.md` 启动；不覆盖既有目录或用户数据。迁移前正常停库并核验冷备，回退使用匹配旧包及升级前原库。独立 Relay、证书及管理配置不在 ZIP 内，邮件、外部语音及主动外部撤销默认关闭。真实音频/TUN/跨机游戏、三平台安装签名、目标服务器容量、许可人工审核及正式 F/G/H/I 和人工验收仍未完成，阶段保持 E，不将本次试用打包当作正式发布或验收。

四文件暂存树 `78107a32c0d8f79a2e21db3c53c62dc095b07a9c` 的独立完整仓库快照通过工作空间、依赖、兼容/发布/性能契约及六项打包单测，缺失 Linux/Mac 工具的专项明确跳过。`target/windows-sync-recovery-cc65e0d/snapshot.log` SHA-256 为 `b5c34cc0049ee20fb883ffb502fa3c84cae16350ae3e1310b0c182972e9f0dc1`，其后仅补充结果说明。核验进程退出及路径安全后清理本轮构建和临时目录 44,280,754 字节（约 42 MiB），保留源码 ZIP、日志、提取界面、共享缓存、新旧交付物和用户数据；结束 C/D 可用约 10.63/33.75 GiB。

## 上一版本试用（684e0a9）

最新整包：`target/trial-kit-684e0a9/zhijie-windows-x64-trial.zip`（93,079,280 字节，SHA-256：`ecac7e215dafab1e3ad121ede76caafbbb334406d51d2bf72ab1bbc1b6e254cd`）。后端从已推送 `684e0a9` 干净归档构建，包含同步交付与生命周期调度隔离，以及有效会话复核不等待发送锁的修复；慢广播不再占用默认生命周期调度线程。真实四慢端复现、68 项相关回归通过与一项未启用、两组百账号短测及 34 项独立快照专项见 `docs/engineering/sync-lifecycle-scheduler-isolation.md`；不把约 0.78 秒本机空闲 logout 关闭写成全通道五秒 SLA。

新后端：`target/trial-kit-684e0a9/preview-backend.zip`（83,840,569 字节，SHA-256：`2628e32733850f00804698f791b42b3fb93a197fc82b460e7cb20c41cefb4ddb`）。336 个后端条目与 `f35980f` 逐项比较，仅 `SchedulingConfiguration.class`、`SyncOutboxDispatcher.class`、`SyncService.class` 与内部 `SyncService$Connection.class` 不同，四项均与干净归档编译结果一致；348 个整包条目也仅上述后端条目变化。内部连接类的反汇编指令、签名与常量一致，见 `target/windows-lifecycle-684e0a9/inner-class.log`；差异与摘要见 `target/trial-kit-684e0a9/comparison.log`。依赖、数据库 V16、启动脚本与外部服务默认配置未变。

桌面与 Helper 直接复用 `8da8814` 便携文件，构建前已核对相关源码、启动和打包输入无变化；整包打包时逐个核验产品摘要。便携清单 SHA-256 仍为 `8f337ab2ec814e8b0ee5aa22cd07154a318cbceffe4a7a2c1a8c178ad33cbd8c`。本轮未重建或复制安装器，未启动原生桌面、生产 Helper 或安装器，也未重跑 UI 检查。

六项打包单测通过。新整包实际通过登录、消息、重启、1,112 文件冷备恢复、设备接管与旧资格拒绝，见 `target/trial-kit-684e0a9/relocation.log`；新后端同版重启、两种同步认证、房间重开与普通日志/管理记录秘密扫描通过，见 `backend-lifecycle.log`。从 `f35980f` 旧版独立测试库停库迁入新版副本、回退旧原库、再启新版共四次启动通过；旧/新版冷备逐文件核验 1,111/1,112 文件，各自消息、删除标记与配置保留，两份数据不合并，见 `upgrade-roundtrip.log`。

验证日志 SHA-256：`relocation.log` 为 `ec5efc48c0879e01c0a38fee23fef2c9fc925b2ae9adb73ba4752f3ef8c1b7ef`；`backend-lifecycle.log` 为 `1e56c7959b03cdd0353b05771f69b35248d2ab62284148a86d2d2e1fd4069cea`；`upgrade-roundtrip.log` 为 `197d4f08b6fffe7a317a3c1d0285c78f4b7e89a83ac25fa902cc9d69ebc79018`。

四文件暂存树 `02ac5bbd9f55d221753033883eb5782e0c4dd0c6` 已独立导出完整仓库，通过工作空间、依赖清单、兼容矩阵、发布契约、性能夹具检查及六项打包单测。发布契约内 Linux `dpkg-deb` 实跑因本机缺少工具而跳过，不是 Linux 制品验收。`target/windows-lifecycle-684e0a9/snapshot.log` SHA-256 为 `680a75b1eb3c504181272de0b811f2e95facb6c2bb37d69fa9bbd0b57896a3fd`，此后仅追加结果记录。

本轮验证进程退出后，核验 D 盘绝对路径、逐级重解析点、Git 忽略及进程引用，清理构建源码、展开快照和临时目录共 37,086,138 字节（约 35 MiB），见 `target/windows-lifecycle-684e0a9/cleanup.log`。日志、源码 ZIP、共享缓存、新旧交付物及用户数据保留；结束时 C/D 可用约 10.64/37.96 GiB。

本轮构建和验证使用 D 盘项目专用目录与现有离线工具，仅设置当前进程环境，不覆盖已有试用数据。核对整包摘要后解压到 D 盘全新目录并按包内 `README.md` 启动；迁移前正常停库并核验冷备，回退使用匹配旧包及升级前原库。旧 ZIP 保留，禁止将两份数据库自动合并。

独立 Relay、证书与管理配置不在 ZIP 内，主动 Relay 撤销、邮件和外部语音默认关闭。真实跨机 TUN/游戏、音频、三平台安装签名、目标服务器容量、许可人工审核及正式 F/G/H/I 和人工验收仍未完成，阶段保持 E。本轮制品验证不代替这些验收。

## 上一版本试用（f35980f）

最新整包：`target/trial-kit-f35980f/zhijie-windows-x64-trial.zip`（93,079,191 字节，SHA-256：`68923d552521e11d6cc4374ae0fe2ffb7120c91d014b42472514eb8b8b7f66e0`）。后端从已推送 `f35980f` 干净归档构建，包含同步撤销先关闭目标账号、再向其余连接广播的修复；无关慢接收者不再成为该投递入口关闭目标连接的前置条件。真实三慢端缺陷复现、51 项相关回归及 26 项独立快照专项见 `docs/engineering/sync-revocation-before-fanout.md`；不宣称更早 Outbox 积压、调度等待或全通道五秒目标已经解决。

新后端：`target/trial-kit-f35980f/preview-backend.zip`（83,840,318 字节，SHA-256：`97eecba0a154857caa0eeeee68eaebe096a1fd1f7472d6ac4b4d1821589f84f5`）。336 个后端条目和 348 个整包条目与 `2302a84` 逐项比较，仅 `SyncRevocationTarget.class` 不同；该类与本轮干净归档实际编译结果一致，SHA-256 为 `3fb3f67c4e5dc832279294007bb90ee53c3d6dcdef4516c1243b691cc602f640`。依赖、数据库 V16、启动脚本、桌面和 Helper 均未变化，详见 `target/trial-kit-f35980f/comparison.log`。

桌面与 Helper 直接复用 `8da8814` 便携文件；构建前已核对相关源码、启动与打包输入无变化，打包时逐个核验产品摘要。便携清单 SHA-256 仍为 `8f337ab2ec814e8b0ee5aa22cd07154a318cbceffe4a7a2c1a8c178ad33cbd8c`。本轮未重建或复制安装器，未启动原生桌面、生产 Helper 或安装器，也未重跑 UI 检查。

六项打包单测通过。新整包实际通过登录、消息、重启、1,112 文件冷备恢复、设备接管及旧设备资格拒绝，见 `target/trial-kit-f35980f/relocation.log`；新后端的同版重启、两种同步认证、房间重开及普通日志/管理记录秘密扫描通过，见 `backend-lifecycle.log`。从 `2302a84` 旧版独立测试库停库迁入新版副本、回退旧原库、重新启用新版共四次启动通过，旧/新版冷备核验 1,111/1,112 个文件，配置、消息和删除标记各自保留且两份数据未合并，见 `upgrade-roundtrip.log`。

验证日志 SHA-256：`relocation.log` 为 `13fc3ced92a2d566998ecfaed24bbd5fd3b15db2d20a0927fa94483bce7c01cc`；`backend-lifecycle.log` 为 `8474010b763d7c92a45affd3d27850aef49ee6ece2fb26b9eb567549eb33d6c8`；`upgrade-roundtrip.log` 为 `bff4bb86228da45cee17d2a26196c568e29775f4b19802a1e416bf4a9a31a09e`。

四文件暂存树 `bf6b847bff2c6678361d95ba73801df1511e6a51` 已独立导出完整仓库，通过工作空间、依赖、兼容矩阵、发布契约、性能夹具检查及六项打包单测。发布契约中的 Linux `dpkg-deb` 专项因本机未提供该工具而跳过，不能据此宣称 Linux 制品通过。`target/windows-sync-f35980f/snapshot.log` SHA-256 为 `18f1269bbf7d6916b1c032341f9f0cb92ed5d2cc600aa1924cf94cecd96fab51`，其后仅补充结果记录。

本轮验证进程结束后，核对 D 盘绝对路径、逐级重解析点、Git 忽略和进程引用，清理构建源码、展开快照和临时目录共 37,030,909 字节（约 35 MiB），见 `target/windows-sync-f35980f/cleanup.log`。证据、源码 ZIP、共享缓存、新旧制品和用户数据均保留；结束时 C/D 剩余约 10.64/38.15 GiB。

构建和验证全程使用 D 盘项目专用目录与现有离线工具，仅设置当前进程环境，未访问或覆盖用户试用数据。核对整包摘要后解压到 D 盘全新目录，按包内 `README.md` 启动。旧用户迁移前先停库并核验冷备；回退使用匹配旧包及升级前原库，两份数据库不自动合并。旧 ZIP 保留。

独立 Relay、证书与管理配置不在 ZIP 内；主动 Relay 撤销、邮件和外部语音默认关闭。真实跨机 TUN/游戏、音频、三平台安装签名、目标容量、许可人工审核及 F/G/H/I 与人工验收仍未完成，正式阶段保持 E。本轮制品验证不代替上述验收。

## 上一版本试用（2302a84）

最新整包：`target/trial-kit-2302a84/zhijie-windows-x64-trial.zip`（93,079,192 字节，SHA-256：`7452872fcc8b16aa46e47373d6a768172a86eaf3ff1173bffe613f2dda417dfc`）。新后端从已推送 `2302a84` 干净归档构建，包含同步连接单次阻塞写入两秒期限；真实 TCP 慢接收端不再令该连接的注销清理持续等待默认二十秒写入预算。实际复现、33 项相关回归及两组百账号九十秒短测见 `docs/engineering/sync-blocking-write-deadline.md`；不宣称历史掉线根因、多个慢端全局时限或完整五秒撤销验收已解决。

新后端：`target/trial-kit-2302a84/preview-backend.zip`（83,840,318 字节，SHA-256：`15da96f8a617b262a9bdccfeac38c6bc1de85adcaaf5d2905611c737c7db8b21`）。336 个后端条目与原 `d9a9dfe` 比较，仅 `SyncWebSocketHandler.class` 不同；348 个整包条目与 `8da8814` 比较也仅此后端类不同，日志 `target/trial-kit-2302a84/comparison.log`。依赖、数据库 V16、启动脚本及默认外部服务开关不变。

桌面与 Helper 直接复用已验证 `8da8814` 便携文件，构建前已核对相关源码及输入无变化，未复制或重建安装器。便携清单 SHA-256 仍为 `8f337ab2ec814e8b0ee5aa22cd07154a318cbceffe4a7a2c1a8c178ad33cbd8c`，整包生产文件打包时逐个核验摘要。桌面、Helper 摘要仍分别为 `70ca542d3e7ac7b084f627381889e4b6c6661deb9da9e15249812e874ac8b651`、`5ab71d304b3b53162da0b2df73c5e7d1e29d8d4c70fa779d36ecbaed640de6c2`；本轮没有再次运行原生窗口、UI 浏览器检查、生产 Helper 或安装器，不将历史结果冒充本轮执行。

本轮六项打包单测通过；新整包在一次性 D 盘目录实际通过登录、消息、重启、1,112 文件冷备恢复、设备接管及旧资格拒绝，见 `target/trial-kit-2302a84/relocation.log`。新后端的同版生命周期、两种同步认证、房间重开、普通日志/管理记录秘密扫描通过，见 `backend-lifecycle.log`。另从 `d9a9dfe` 旧包的独立测试库停库迁入新版副本、使用原库回退、再启新版共四次启动通过；旧/新冷备分别核验 1,111/1,112 个文件，各自配置及消息保留，不合并两份数据，见 `upgrade-roundtrip.log`。

所有进程退出后核对 D 盘绝对路径、逐级重解析点和进程引用，仅清理本轮构建源码、暂存快照和临时目录，共 243,943,842 字节；见 `target/windows-sync-2302a84/cleanup.log`。保留源码 ZIP、脱敏证据、交付物、共享缓存及既有试用数据。构建与验证全程复用离线工具，新增缓存和输出在 D 盘，不修改全局环境、不清理 C 盘其他程序文件。

交付索引的暂存树 `419b728c9e1b5bd7b9c3233df5df469c516c8b79` 已独立归档检查。首次只导出 workspace，兼容矩阵检查因缺少工作区外的已索引正式历史证据而失败，保留 `target/windows-sync-2302a84/snapshot.log`；改为导出完整暂存树后，工作空间、依赖、兼容/发布/性能契约及六项打包单测通过，未修改检查或复制未跟踪证据。成功日志 `final-snapshot.log` SHA-256 为 `abc5f4354c4ef2f716743b51e20582912971498bb46c8c29d2bb3eb237f613d6`；其后仅追加本段记录。两份展开快照及临时目录另清理 36,081,903 字节，总计清理 280,025,745 字节，见 `cleanup-snapshots.log`。结束 C/D 剩余约 10.65/38.35 GiB。

核对整包摘要后解压到 D 盘全新目录并按包内 `README.md` 启动，不覆盖已有目录或数据库。旧用户迁移须先停库并核验冷备，回退使用匹配旧包及升级前原库。独立 Relay、证书及管理配置仍不在 ZIP 内，主动撤销、邮件和外部语音默认关闭。三平台安装/签名、真实音频/TUN/游戏、目标容量、许可人工审核及正式 F/G/H/I 仍未完成，正式阶段保持 E。

## 上一版本试用（8da8814）

最新整包：`target/trial-kit-8da8814/zhijie-windows-x64-trial.zip`（93,078,936 字节，SHA-256：`3abd8fe20686ae595f45228844e55ce050b28ccb4386a2d9af6bfc825c8135c6`）。桌面与 Helper 从已推送 `8da8814` 干净归档实际构建，包含 `5f37ddd` 强制中继首次连接并行修复。首次 QUIC 等待与 Stop、每秒 HTTP 验权、票据/授权快照截止并行；中继未就绪时不提前消耗 TUN 输入或推进握手，避免丢失首包而等待重试。缺配置仍立即失败，Relay-only 不旁路直连，三秒连接超时保持。

没有新增依赖、迁移或默认外部服务开关。已核对后端生产源码、Maven 输入、配套启动脚本与后端打包入口未变化；控制面测试新增不进入后端运行包。因此直接复用 `target/trial-kit-d9a9dfe/preview-backend.zip`（83,839,806 字节，SHA-256：`f953073bd2606beac6532ab217adf9558a0d61ab47acf980aaaa945557d1b653`），不重复复制或编译后端，数据库仍为 V16。

干净归档的优化版 Helper 单测 69 通过、4 个专用子进程入口忽略；13 项界面单测、实际桌面构建和 NSIS 打包通过。证据位于 `target/windows-relay-startup-8da8814/`：`helper-release-tests.log`、`ui-unit.log`、`desktop-release.log`、`payload.json`。未签名安装器为 `target/trial-8da8814/织界_0.1.0_x64-setup.exe`（12,093,663 字节，SHA-256：`ff9860e56892aba1e910c8e52e33f0988f8b52dd6dc804dc042e4560adab4d3b`）。本轮没有运行桌面、生产 Helper 入口或安装器。

八个产品文件与同次构建输入及便携副本逐项核对。桌面 SHA-256 为 `70ca542d3e7ac7b084f627381889e4b6c6661deb9da9e15249812e874ac8b651`，Helper 为 `5ab71d304b3b53162da0b2df73c5e7d1e29d8d4c70fa779d36ecbaed640de6c2`，便携清单为 `8f337ab2ec814e8b0ee5aa22cd07154a318cbceffe4a7a2c1a8c178ad33cbd8c`。`target/trial-kit-8da8814/comparison.log` 确认 1,324 个归档源码文件未被构建改写、348 个整包条目仅桌面/Helper/清单变化、336 个后端条目与复用 ZIP 完全一致，其余依赖、驱动、脚本、许可和资源不变。

六项打包单测、从新桌面二进制提取的 10 份界面文件及 170 项无头浏览器检查通过；内嵌界面与 `0ce6df5` 字节一致、无脚本错误。日志位于 `target/trial-kit-8da8814/`：`backend-unit.log`、`kit-unit.log`、`embedded-binary.log`、`embedded-browser.json`。新整包在 D 盘一次性目录实际完成登录、消息、重启、1,112 文件冷备恢复和旧设备资格拒绝，见 `relocation.log`。复用后端另行通过同版重启、两种同步认证、房间重开和普通日志/管理记录秘密扫描，见 `backend-lifecycle.log`。本轮未重复执行旧库升级/回退，历史记录不冒充新执行。

源码层 127 项 Rust 回归、真实控制面三种十二端/五轮续票及持续旧端发送、接收端控制面失联隔离通过，见 `docs/engineering/network-relay-startup-responsiveness.md`、`docs/engineering/network-hostile-peer-isolation.md`。源码和制品测试范围分别记载，不把源码成功冒充安装后运行验收。

所有验证进程结束后检查 D 盘绝对路径、逐级重解析点和进程引用，仅清理本轮构建源码副本及临时目录共 25,311,291 字节，见 `target/windows-relay-startup-8da8814/cleanup-build.log`。保留源码 ZIP、提取界面、制品、证据和共享缓存；清理后 C/D 可用约 10.15/39.37 GiB。

所有构建缓存、临时目录和制品位于 D 盘，复用已有工具链及本地 NSIS 缓存，没有自动下载到 C 盘。核对整包摘要后解压到 D 盘全新目录，按包内 `README.md` 启动，不覆盖既有用户目录、数据库或混用版本。独立 Relay、证书和管理配置不在 ZIP 内，主动撤销默认关闭，邮件与外部语音不会自动启用。本轮不是原生窗口、实际音频、管理员 TUN、跨机游戏、完整五秒 SLA、三平台安装签名、目标容量、许可人工审核或正式 F/G/H/I 验收；正式阶段仍 E。

## 上一版本试用（0ce6df5）

最新整包：`target/trial-kit-0ce6df5/zhijie-windows-x64-trial.zip`（93,078,848 字节，SHA-256：`310f9c998f2061934750b81dbab8851b78138bbbf7b24c903b1971e158b80581`）。桌面和 Helper 从已推送 `0ce6df5` 干净归档构建；后端复用已验证 `d9a9dfe` 的原始 ZIP，未重复复制或构建。构建前已核对控制面、Maven 输入、配套启动脚本和后端打包入口无源码变化，后端摘要仍为 `f953073bd2606beac6532ab217adf9558a0d61ab47acf980aaaa945557d1b653`。

Helper 将 HTTP 权威轮询及续票与数据转发并行推进，避免慢响应暂停整个数据泵。已验证快照的硬截止从请求建立时起最多三秒，原票据和 Peer 到期继续约束；当前票据明确失权立即停止，成功续票丢弃旧票在途结果。不是无限离线缓存，也不承诺整个 IPC Stop 调用达到数据泵单测的响应时限。缺陷复现、119 项原生回归及真实控制面三种十二端测试见 `docs/engineering/network-authority-responsiveness.md`。无新依赖、迁移或默认外部服务变化，数据库仍为 V16。

本轮对干净归档实际运行优化版 Helper 单测：61 通过、3 个专用入口忽略，见 `target/windows-responsive-0ce6df5/helper-release-tests-retry.log`。首次执行缺少归档下的本地 `target` 目录，两个文件夹具失败；只创建 D 盘工作目录后重跑，没有改源码或跳过用例，原失败日志 `helper-release-tests.log` 保留。桌面构建及 NSIS 打包成功，八个产品文件与本轮输入一致，见 `desktop-release.log`、`payload.json`。未签名安装器：`target/trial-0ce6df5/织界_0.1.0_x64-setup.exe`（12,093,123 字节，SHA-256：`c67a27a57de012a742ce788d3b7b6a4ea171906be544191de2c3e5bf560e0583`）；便携试用不需要运行安装器，本轮没有启动桌面、Helper 或安装器。

13 项界面单测、从新二进制提取的 10 份界面文件及 170 项无头浏览器检查通过；提取界面与 `d9a9dfe` 字节一致。见 `target/windows-responsive-0ce6df5/ui-unit.log`、`target/trial-kit-0ce6df5/embedded-binary.log`、`embedded-browser.json`。六项打包单测通过；`comparison.log` 确认 1,319 个归档源码文件未被构建修改，348 个整包条目仅桌面、Helper 和清单变化，336 个后端条目与复用原包逐一相同，其余驱动、脚本、许可和依赖均不变。

整包在一次性 D 盘目录通过实际登录、消息、重启、1,112 文件冷备恢复、设备接管及旧资格拒绝，见 `target/trial-kit-0ce6df5/relocation.log`。同一复用后端重新执行同版重启、两种同步认证、房间重开及普通日志/管理记录秘密扫描通过，见 `backend-lifecycle.log`。后端 ZIP 未变化，本轮没有重复执行旧库升级/回退；`d9a9dfe` 原包的历史四次启动记录继续保留，不冒充本轮新执行。

所有测试进程退出后核对 D 盘绝对路径、逐级重解析点与进程引用，仅清理本轮构建源码副本、临时目录共 24,702,792 字节，见 `target/windows-responsive-0ce6df5/cleanup-build.log`；保留源码 ZIP、产品、提取界面、日志和共享缓存。清理后 C/D 可用约 10.17/39.54 GiB。提交前四文件暂存区独立快照检查记录为同目录 `snapshot.log`，仅核验工作区、依赖、兼容/发布/性能契约及许可材料完整性，不代替许可人工审核或正式验收。

核对摘要后解压到 D 盘全新目录，按包内 `README.md` 启动；不要覆盖既有目录、数据库或混用桌面/Helper。独立 Relay 不在 ZIP 内，证书和目标服务仍须另行配置；主动撤销默认关闭，邮件和外部语音不会自动启用。本轮不代表真实音频、管理员 TUN、跨机游戏、完整五秒 SLA、三平台签名安装、目标容量或正式 F/G/H/I 完成，正式阶段仍 E。

## 上一版本试用（d9a9dfe）

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
