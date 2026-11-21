# Windows 本机试用后端（离线包）

当前后端：`target/trial-kit-3998fcb/preview-backend.zip`（83,860,827 字节，SHA-256：`e789b6f178d78110f6e396e4e80974eeae24c1b050c3b3487cea17deaf2616e5`）。包内 233 个应用类和资源与通过 V18 集成回归的可执行 JAR 字节相同。与上一后端包比对，仅新增 V18 群主审核同步迁移、修改 runtime 审计权限启动检查；试用依赖和启动程序不变。完整桌面套件见 `candidate-build.md`。

`target/trial-kit-3998fcb/roundtrip.log`：在独立测试目录用离线包按 V17 → V18 → V17 → V18 顺序启动。账号、群、房间标识一致，旧库回退时不含新版写入，恢复运行新库后仍有新版消息和删除标记。两种同步认证、房间重开与日志隐私检查通过。首次演练因 PowerShell 将 `javac` 的环境提示当成错误而提前结束，见 `v17-v18-roundtrip.log`；清除该测试环境选项后通过。此处未升级用户数据库或操作生产服务。

## edb856b 后端历史记录

历史后端：`target/trial-kit-edb856b/preview-backend.zip`（83,859,580 字节，SHA-256：`717c76caaa6a5dfe9616b4011eb8f274386c1db26bb70abf2c440bbd980c7256`）。与上一包相比，仅 `IdentityService` 及其嵌套类变化；依赖、启动脚本与 V17 数据库迁移保持不变。共 341 个条目。

**旧版试用库升级和回退（Windows 本机）**：使用 `684e0a9`（V16）和 `edb856b`（V17）的后端离线包，在独立目录按“旧版 → 新版 → 旧版 → 新版”四次启动。演练现在调用试用说明中的 `cold-backup.py restore` 恢复到新目录，不再用 PowerShell 手动复制。原库 1,111 个文件、新库 1,113 个文件分别用停库快照核对；回退时只启动原库，不对升级后的数据库运行旧程序。

四次登录的账号、群和房间标识相同。回到旧版时仍能看到原消息，没有新版写入；再次启动新版时，新消息和旧消息的删除标记都还在。升级后，尚未到期的旧网络票据被拒，显式重入取得了新租约。两种 WebSocket 认证方式均收到预期删除事件。实跑日志：`target/trial-kit-edb856b/v16-upgrade-roundtrip.log`；冷备工具的 10 项回归：`target/trial-kit-edb856b/upgrade-cold-copy-tests.log`。本次验证使用临时账号与数据库，不涉及桌面安装器升级、真实网卡、用户数据或生产环境。

## bff9649 后端历史记录

历史后端：`target/trial-kit-bff9649/preview-backend.zip`（83,856,519 字节，SHA-256：`92bfa5597f11b684e6e8492eca209e71273d767d0d0bc5a6d6357f20eb75be57`）。与上一后端逐项核对 340 个条目，仅正式运行配置的同步授权检查类不同。未引入依赖或数据库迁移；实际本机整包登录、同版重启和停库冷备恢复记录见[本机试用入口](candidate-build.md)。试用配置不是正式低权限部署；后者的拒绝启动与权限恢复验证见 `docs/engineering/runtime-sync-startup.md`。

## 8471c58 后端历史记录

当前后端：`target/trial-kit-8471c58/preview-backend.zip`（83,855,552 字节，SHA-256：`7dc40ac0b68e3ca32cab0f7373ce11f56266997178fec0e0a3b2bb15d9c3246d`），配套整包与启动边界见[本机试用入口](candidate-build.md)。从已推送 `8471c58` 干净归档构建，V17 将活动绑定到实际登录会话；认证注销、刷新重放和其他会话失效不能保留原活动准入，普通令牌刷新不会清退活动。同设备新登录需显式接管，旧登录注销不误伤新活动。实现与源码验证见 `docs/engineering/activity-auth-session-revocation.md`，实际包验证见 `docs/engineering/windows-activity-session-revocation.md`。

本轮完成整包首次启动/重启/冷备恢复，实际注销、接管及刷新重放拒绝；后端同版生命周期、两种同步认证、房间重开、日志隐私，以及 V16 旧活动升级拒绝、新版重入、原库回退和新版再次启用。旧/新版独立冷备分别核验 1,111/1,113 文件，账号、群、配置、消息及删除标记保留，两份数据不合并。升级前必须停库并备份，V17 不回填猜测旧活动归属，清退后用户显式重新加入；回退必须配套旧包及升级前原库，不让 V16 访问 V17 数据库。

新包默认不启用邮件、外部语音或主动外部撤销，不含独立 Relay、证书和密钥。不将控制面拒绝当作真实媒体/TUN 停流或全通道五秒验收；三平台安装签名、真实跨机/音频、目标容量、许可人工审核及正式验收仍未完成。

## 684e0a9 后端历史记录

当前后端：`target/trial-kit-684e0a9/preview-backend.zip`（83,840,569 字节，SHA-256：`2628e32733850f00804698f791b42b3fb93a197fc82b460e7cb20c41cefb4ddb`），配套整包与启动边界见[本机试用入口](candidate-build.md)。从已推送 `684e0a9` 干净归档构建，将同步 Outbox 交付放到独立单线程调度器，有效会话复核不再等待发送锁；失效连接仍锁内复核并关闭。保持原发送顺序、权限校验与事务确认，未新增依赖或数据库迁移，仍为 V16。

336 个后端条目与 `f35980f` 比较只差四个相关类，内部连接类的反汇编指令、签名和常量一致，详见配套整包记录。新整包实际通过登录、消息、重启、1,112 文件冷备恢复及设备资格检查；后端同版重启、两种同步认证、房间重开与日志隐私检查通过。另从 `f35980f` 旧测试库停库迁入新版独立副本、回退旧原库、再启新版四次启动通过，旧/新版冷备核验 1,111/1,112 个文件，两份数据各自保留、不自动合并。日志位于 `target/trial-kit-684e0a9/`，摘要见配套整包记录。

升级前先正常停库、保留已核验冷备并恢复到新数据目录；回退用匹配旧包及升级前原库，不能让旧程序混跑活跃新版库。独立 Relay 不在 ZIP 内，主动 Relay 撤销、邮件及外部语音默认关闭。目标自身慢连接、数据库等待、Outbox 积压及全通道五秒目标仍有验证边界；真实跨机、音频、三平台签名、目标容量和正式验收未完成。

## f35980f 后端构建与验证记录

当前后端：`target/trial-kit-f35980f/preview-backend.zip`（83,840,318 字节，SHA-256：`97eecba0a154857caa0eeeee68eaebe096a1fd1f7472d6ac4b4d1821589f84f5`），配套整包与启动边界见[本机试用入口](candidate-build.md)。从已推送 `f35980f` 干净归档构建，336 个后端条目与 `2302a84` 比较仅 `SyncRevocationTarget.class` 不同；撤销入口先关闭目标账号，再向其他连接广播，避免无关慢接收者累积拖延目标关闭。保留先前单次阻塞写入两秒期限，没有新增依赖、迁移、线程或队列。

新整包实际通过登录、消息、重启、冷备恢复及设备资格回归；后端同版重启、两种同步认证、房间重开和普通日志/管理记录秘密扫描通过。从 `2302a84` 旧测试库停库迁入新版独立副本、回退旧原库、再启新版共四次启动通过，旧/新版冷备逐文件核验 1,111/1,112 个文件，消息、删除标记和配置按各自版本保留，数据不合并。证据位于 `target/trial-kit-f35980f/relocation.log`、`backend-lifecycle.log`、`upgrade-roundtrip.log`，摘要见配套整包记录。

升级前正常停库并核验冷备，恢复到全新数据目录；回退使用匹配旧包和升级前原库，不让旧程序混跑活跃新版库。旧 ZIP 与用户数据保留，数据库仍为 V16。独立 Relay 不随包部署，主动 Relay 撤销、邮件与外部语音默认关闭；本机结果不证明更早 Outbox 积压或全通道五秒目标，真实跨机、音频、三平台签名、目标容量及正式验收仍开放。

## 2302a84 后端构建与验证记录

当前后端：`target/trial-kit-2302a84/preview-backend.zip`（83,840,318 字节，SHA-256：`15da96f8a617b262a9bdccfeac38c6bc1de85adcaaf5d2905611c737c7db8b21`），配套整包及启动边界见[本机试用入口](candidate-build.md)。从已推送 `2302a84` 干净归档构建，336 个条目仅 `SyncWebSocketHandler.class` 相较 `d9a9dfe` 不同，单次阻塞 WebSocket 写入设置两秒期限；不增加依赖、迁移、队列或生产重试。具体缺陷、真实 TCP 复现及 33 项回归见 `docs/engineering/sync-blocking-write-deadline.md`，不解释为历史所有同步故障已解决。

本轮实际执行同版登录/消息/重启、两种同步认证、房间重开及普通日志/管理记录秘密扫描，通过记录为 `target/trial-kit-2302a84/backend-lifecycle.log`。从旧 `d9a9dfe` 测试库停库迁入新版独立副本、原库回退、再启新版共四次启动通过；旧/新版冷备逐文件核对 1,111/1,112 文件，配置、消息和删除标记各自保留，见 `upgrade-roundtrip.log`。整包还通过 1,112 文件冷备恢复及设备资格拒绝，见 `relocation.log`。

升级前正常停库并核验冷备，恢复到新数据目录；回退用匹配旧程序及升级前原库，不在活跃新版库混跑旧程序，不自动合并两份数据。旧 ZIP 和原用户目录保留。数据库仍为 V16，主动 Relay 撤销、邮件与外部语音默认关闭，独立 Relay 不随包部署。完整实机、三平台签名、目标容量及正式验收仍开放。

## 8da8814 配套验证记录

当前配套整包已更新为 `8da8814`，见[本机试用入口](candidate-build.md)。本轮将 `5f37ddd` 首次中继连接并行修复实际纳入桌面/Helper；后端生产源码、Maven 输入、启动及打包入口无变化，测试源码变化不属于运行制品。继续直接使用 `target/trial-kit-d9a9dfe/preview-backend.zip`，大小 83,839,806 字节、SHA-256 `f953073bd2606beac6532ab217adf9558a0d61ab47acf980aaaa945557d1b653`，没有生成重复副本。新整包内 336 个后端条目逐一相同。

新整包的登录、消息、重启、1,112 文件冷备恢复和设备资格回归见 `target/trial-kit-8da8814/relocation.log`；复用后端的同版重启、两种同步认证、房间重开及普通日志/管理记录秘密扫描见同目录 `backend-lifecycle.log`，均通过。旧库升级/回退未在本轮重跑；依赖、V16 与默认外部服务开关不变。成套使用新包，不能仅换后端获得 Helper 修复。独立 Relay 仍不包含在 ZIP 内，完整实机、签名、容量、许可及正式验收缺口不变。

## 0ce6df5 配套验证记录

当前配套整包已更新为 `0ce6df5`，见[本机试用入口](candidate-build.md)。本次只重建桌面和 Helper，将有界并行验权修复纳入产品；后端仍直接使用 `target/trial-kit-d9a9dfe/preview-backend.zip`（83,839,806 字节，SHA-256：`f953073bd2606beac6532ab217adf9558a0d61ab47acf980aaaa945557d1b653`），没有生成重复后端副本。已核对相关后端源码无变更，并逐条确认新整包 336 个后端条目与原 ZIP 相同。

新版整包的实际登录、消息、停库重启、1,112 文件冷备恢复及设备资格检查通过，见 `target/trial-kit-0ce6df5/relocation.log`；本轮对复用后端重新执行同版生命周期、两种同步认证、房间重开和普通日志/管理记录隐私检查通过，见同目录 `backend-lifecycle.log`。旧库升级/回退没有在本轮重跑，以下历史记录保留其原执行语境。只换后端不会更新旧 Helper，须成套使用新整包。

客户端修复细节和三秒快照边界见 `docs/engineering/network-authority-responsiveness.md`；不把开发测试或本机后端生命周期等同于跨机、真实 TUN/音频、安装签名和五秒端到端撤销验收。依赖、V16 数据库和默认外部服务开关不变，独立 Relay 仍不包含在试用 ZIP 内。

## d9a9dfe 后端构建与原验证记录

当前后端：`target/trial-kit-d9a9dfe/preview-backend.zip`（83,839,806 字节，SHA-256：`f953073bd2606beac6532ab217adf9558a0d61ab47acf980aaaa945557d1b653`），配套[最新整包](candidate-build.md)。桌面、Helper 和后端均从已推送 `d9a9dfe` 干净归档构建。后端 Peer 到期时间取票据/租约较早值；客户端会话绑定账号、活动代次和租约并执行到期失效。只换后端不会更新旧 Helper，须成套使用新版桌面与 Helper。无新依赖、迁移或默认外部服务开关变化，数据库仍为 V16。

相较 `f86cef2` 的 336 个后端条目，仅 `NetworkPeerService.class` 与内部 `Peer` 类变化；内部类只差调试行号，指令及其余完整元数据一致，见 `target/trial-kit-d9a9dfe/comparison-final.log`、`target/windows-peer-d9a9dfe/inner-class.log`。包内依赖、启动脚本和其余资源字节不变。六项打包单测及整包登录/重启、冷备恢复和设备资格检查通过，详见最新整包记录。独立 Relay 仍不包含在 ZIP 内，默认不启用主动撤销、邮件或外部语音。

本轮同版重启、两种同步认证、房间重开及普通日志/管理记录隐私检查通过，见 `target/trial-kit-d9a9dfe/backend-lifecycle.log`。从 `f86cef2` 旧库迁入新版独立副本、原库回退、再启新版共四次启动通过；旧/新版快照分别逐文件核对 1,111/1,112 文件，见 `upgrade-roundtrip.log`。新旧库各自消息、删除标记和设置按预期保留，没有合并数据。验证进程退出后清理本轮源码副本与临时产物 25,624,436 字节，保留证据、制品与 D 盘共享缓存，详见整包清理记录。

升级前正常停库、保留已核验冷备，恢复到全新数据目录；回退使用匹配旧程序和升级前原库，不在活跃新版库直接运行旧程序。新旧库不自动合并。客户端换设备应使用新私钥，本轮不证明旧私钥持有者重新握手可被区分，也不替代跨机/TUN/真实媒体或正式阶段验收。技术边界见 `docs/engineering/peer-authorization-identity.md`。

## 上一版本（f86cef2）

当前后端：`target/trial-kit-f86cef2/preview-backend.zip`（83,839,495 字节，SHA-256：`ed2670e7181156583aee8a37472c7c48d3ba068215b33181fd46af598575a17e`），配套[最新整包](candidate-build.md)。从已推送 `f86cef2` 干净归档构建，新增 Relay 验权响应版本、活动代次、设备标识及票据/租约较早的绝对期限；无新依赖或迁移，仍为 V16。相较 `a039409` 的 336 个后端条目，仅 `RelayGrantValidationService.class` 和 `RelayValidationResponse.class` 变化，见 `target/trial-kit-f86cef2/comparison.log`。

新 Relay 的内存快照/独立续验/硬截止需要另行部署匹配二进制；试用 ZIP 不包含该独立服务、证书或密钥，不自动开启主动撤销。新 Relay 拒绝旧控制面缺字段，混版未验收，升级/回退须停止相关进程并成套使用匹配程序、配置与停库备份。实现、缓存窗口和实际源码回归见 `docs/engineering/relay-authorization-snapshot.md`；不承诺权威提交后首包零窗口拒绝，也不代表跨机五秒 SLA 已完成。

同版登录/消息/重启、两种同步认证、房间重开及普通日志/管理记录隐私通过，见 `target/trial-kit-f86cef2/backend-lifecycle.log`。`a039409` 旧库停库迁入新版独立副本、原库回退、再启新版的四次启动通过，旧/新版快照分别核对 1,111/1,112 文件，见 `upgrade-roundtrip.log`；不覆盖现有用户数据，两份库不自动合并。四项后端打包和两项整包单测通过，整包额外冷备恢复及设备资格检查通过。清理后只保留制品、归档和证据，详见最新整包记录；完整交付仍须正式阶段和实机验收。

## 上一版本（a039409）

当前后端：`target/trial-kit-a039409/preview-backend.zip`（83,838,457 字节，SHA-256：`cdca5192fa62aa50357dab48cbec8ba7ca3b198249e3605f432878a1385d9acc`），配套[最新整包](candidate-build.md)。从 `a039409` 干净归档构建，语音、网络与其他外部事件分区调度、独立提交，保留 V16 持久化退避；无新依赖或数据库迁移，主动 Relay 撤销仍默认关闭。实现和实际源码回归见 `docs/engineering/external-revocation-channels.md`，不代表真实媒体或跨机 SLA 验收。

本版实际同版登录/消息/重启、两种同步认证、房间重开、普通日志及管理记录隐私通过，见 `target/trial-kit-a039409/backend-lifecycle.log`。相较 `e45156f`，336 个后端条目中仅新增两个类、变更八个相关条目，依赖不变，见 `comparison.log`。不覆盖既有目录；升级与回退继续使用下述成套冷备及独立数据目录要求。

从 `e45156f` 旧版停库迁入新版副本、使用原库回退、再启新版的四次启动通过，见 `target/trial-kit-a039409/upgrade-roundtrip.log`。旧/新版冷备分别核对 1,111/1,112 个文件，原库与新版库各自消息、删除标记和配置按预期保留，两份数据不自动合并。

## 上一版本（e45156f）

当前后端：`target/trial-kit-e45156f/preview-backend.zip`（83,832,502 字节，SHA-256：`8cbebdc023ad7aad0461e6e458317bcecdda5c3cc376e2483adba44700aab421`），配套[最新整包](candidate-build.md)。从 `e45156f` 干净归档构建，新增可选 Relay Outbox 撤销适配，仍使用 V16、默认关闭主动撤销。独立 Relay 服务及其证书/密钥须另行配置，见 `docs/engineering/relay-outbox-revocation.md`，包内不提供或自动部署。

本机实际验证了同版登录/消息/重启、两种同步认证、房间重开与日志隐私，以及 `0dcaf88` 旧版停库迁入、新版使用、原库回退和新版重新启用。旧/新版快照分别核对 1,111/1,112 个文件，见 `target/trial-kit-e45156f/backend-lifecycle.log`、`upgrade-roundtrip.log`。对前版 333 条内容核对，仅新增一类、修改两条，依赖不变，见 `comparison.log`。无新数据库迁移，仍按下文冷备与成套回退要求操作，不覆盖已有目录。

以下保留前版入口及升级边界；当前试用包大小、摘要和验证记录以上述新版本为准。

**首次试用**：优先使用[桌面与后端整包](candidate-build.md)，桌面 `399db61` 配套后端 `0dcaf88`。单独的后端包在 `target/trial-kit-0dcaf88/preview-backend.zip`（83,823,788 字节，SHA-256：`a8ff2d4699cdcb31db3aa898b85349e34693e291cc56a1c17519d275c7837ae2`）。相较 `9ba3623` 新增三个派发/状态类和 V16、变更七个后端条目，共 333 条；外部撤销与同步使用独立定时器及事务，持久化有界指数退避，避免慢目标拖延默认调度或失败队首永久饥饿，保留原同步隔离、固定字段诊断、注销/重连和设备资格保护。`DispatchResult` 内部类仅行号变化，已核对指令及其余元数据一致，见同目录 `comparison.log`、`inner-class.log`。独立包已实测登录、消息、重启、同步认证、房间重开及普通日志/管理记录隐私，见同目录 `backend-lifecycle.log`。历史发送告警仍开放，外部失败仍待重试，不等于外部渠道接入或五秒 SLA 验收。不要覆盖已有数据目录；既有日志不会被追溯修改。

V15 在原 Outbox 表新增 `sync_delivered_at`，V16 再新增独立外部尝试计数和下次重试时间，不重置事件或同步游标，外部退避跨重启保留。迁移前停止旧控制面及写入进程，保留已核验的升级前停库备份；以迁移角色执行迁移及现有运行授权脚本，不混跑旧新版。从 V14 或更早版本升级仍可能对历史未完成撤销再次同步，不能宣称严格一次投递。回退用匹配旧程序和原升级前备份，不在已升级活跃库直接启动旧程序；技术边界、失败复现及五十四项完整源码回归见 `docs/engineering/external-revocation-isolation.md` 与 V15 的 `docs/engineering/sync-outbox-isolation.md`。旧后端 `target/trial-kit-9ba3623/preview-backend.zip` 保留，不包含本轮 V16 变更。

已在一次性库实际执行 `9ba3623` 首次启动→停库快照迁入 `0dcaf88` 并应用 V16→使用原旧库回退→重新启用已升级新版库；四次均通过登录和各自应保留的消息/配置核验，新版还通过两种同步认证及房间重开检查，普通日志/管理记录未发现本轮凭据。旧库 1,110 个文件和新版 1,111 个文件的停库快照均复核，记录在 `target/trial-kit-0dcaf88/upgrade-roundtrip.log`。没有覆盖现有试用数据，没有在 V16 数据库上运行旧程序，不代表桌面安装器升级/降级验收。前轮 `6523e6a`→V15 的独立升级/回退记录保留在 `target/trial-kit-9ba3623/upgrade-roundtrip.log`。

**旧版目录**：`target/trial-backend-b44c82c5` 来自 `target/log-boundary-f5bbaac/preview-backend.zip`（83,803,971 字节，SHA-256：`b44c82c537d6cd6c99316e4dff672087ea19dbc23e3c3a577c26009aab71725b`）。该版较上一精简包仅修改控制台日志转换器，不附加原始异常消息、原因链或堆栈。已有目录和历史日志不会自动改变。

用 `test-backend-zip.ps1` 的 `-CheckLogPrivacy -CheckSyncAuth -CheckRoomReopen` 选项可在隔离试用库复测：两次登录、消息和重启、同步删除通知、房间重开与设置密码后，核查普通日志和房间管理记录未出现本次明文密码及登录令牌。该检查不证明生产 TLS、数据库存储或所有日志通道满足 AC-033。

**历史候选**：旧后端 `038145c` 的离线包 `target/backend-038145c/preview-backend.zip` 为 140,640,047 字节，SHA-256：`e79323869d70d8f3530bf664038ae83448be104b012cde3bae0532b10f870847`。与 `6a908d1` 的 330 项包内容逐项比对，仅 `RoomService.class` 不同。

上一 Windows 精简包 `target/backend-winonly-a5e476f/preview-backend.zip`（83,803,127 字节，SHA-256：`6da742951ba9a0b625bc4920e79ffa76858cb160e01ee5654dcd7738ef2a1453`）仅移除三个非 Windows PostgreSQL 二进制 JAR 并更新类路径，不含本次日志修复。

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

快照成功后，用 `restore` 校验备份并复制到**尚不存在**的数据目录。以下示例针对单独后端包；整包的目标路径须在 `backend/` 下，详见包内说明。

```powershell
python -B "ops/database/cold-backup.py" restore --backup "<备份目录>" --destination "<新目录>/target/local-preview/postgres"
```

只有出现 `COLD_RESTORE_COPY_OK` 才启动新包。这一步把备份和实际恢复目录的每个文件摘要及目录结构都与清单核对；验证失败不要启动。保留旧目录和原备份；切回旧目录不会带回升级后才写入的数据。这不是跨 PostgreSQL 主版本升级的方法。

**需要回退时**：用新目录的 `scripts/local-preview/stop.ps1` 正常停库，确认两个端口已释放，保留新目录；若已有新消息等需要保留的变更，先另做一份新版停库快照。对升级前保留的旧目录执行 `python -B "ops/database/cold-backup.py" verify --backup "<升级前备份目录>" --restored-data "<旧目录>/target/local-preview/postgres"`。仅当出现 `COLD_RESTORE_COPY_OK`，才从旧目录启动旧包并用原账号登录。若校验不符，停止操作，不合并或覆盖数据库，也不要让旧程序打开新版数据目录。

**回退后再次使用新版**：停止旧包；若回退期间产生了需要保留的旧版写入，先为旧库另做停库快照。用新版停库时的备份核对新版数据目录，确认 `COLD_RESTORE_COPY_OK` 后，从新版原目录启动。两份数据库不会自动合并：回退期间在旧库新增的内容不会出现在新版，新版独有的消息和删除标记则应保留。

**复建与验证。** 对可信的干净 Windows 源码先执行本地试用说明中的 `test-compile` 和 `dependency:build-classpath`。然后从工具所在工作区运行：

```powershell
python -B "packaging/windows/pack-preview-backend.py" --workspace "<已构建的 workspace>" --repository "<对应的 Maven 仓库>" --output "<D 盘已有目录>/preview-backend.zip"
& "./packaging/windows/test-backend-zip.ps1" -Package "<D 盘已有目录>/preview-backend.zip" -JavaHome "D:/Program Files/jdk-25"
```

给回归命令添加 `-PreviousPackage "<旧试用包.zip>"` 即可依次验证旧版、新版、回退旧版、重新启用新版。旧版先设置密码房并发送消息；新版在独立副本中删除原消息并写入另一条。回退旧版应仍能读到原消息；再次启用新版应读到新版消息和删除标记。每次用原账号登录，核对群、房间、欢迎消息和房间配置。不加此参数只验证同版重启。可选 `-CheckSyncAuth` 验证两个订阅者收到删除事件，`-CheckRoomReopen` 验证房间重新开放，`-CheckLogPrivacy` 验证普通服务日志与管理记录不含测试凭据。回归不接触现有试用数据。

生成器不覆盖同名文件。它只复制试用启动脚本、控制面类、与源码一致的应用配置/映射/迁移和类路径所列 JAR，保留依赖顺序，清单中仅有包内相对路径。回归只针对自行生成的可信包，使用独立目录和回环端口；用例验证本机移位解压后的登录与重启，不代表第二台机器或其他平台已验收。

旧后端 `b44c82c5` 与新版 `c5868af` 已在本机执行上述四次启动：两份停库快照各有 1,109 个文件，恢复副本、回退前的旧库、重新启用前的新版库均通过逐文件摘要和目录结构比对。日志：`target/trial-kit-c5868af/upgrade-roundtrip-1be223b.log`；同版两次启动对照：`target/trial-kit-c5868af/same-version-1be223b.log`。这是便携后端数据验证，不是桌面安装器更新、降级或卸载验证。
