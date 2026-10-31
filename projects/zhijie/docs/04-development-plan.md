# 开发计划：织界

> WF-zhijie；D_PLANNING；PLAN-004。用户要求先完成开发流程，不直接实现代码。本轮仅规划、CLI登记任务和多人协作开发规范：不创建业务实现、不安装工具链、不启动服务、不执行任务测试。Windows先行，macOS/Linux环境后续补齐，三平台交付范围不缩减。当前32项任务全部pending。

## 开发里程碑

| 里程碑 | 任务 | 出口 |
| --- | --- | --- |
| M0 环境与契约 | TASK-001～003 | Windows项目级工具链、协议和真实数据库基础 |
| M1 控制业务与授权 | TASK-004～008 | 账号/群治理/单活动事务和短租约签发屏障 |
| M2 Windows网络风险验证 | TASK-009～012 | 真实直连、TUN、跨房隔离和旧端停止后新端激活 |
| M3 内容与语音 | TASK-013～019 | 元数据/本机存储、真实文字媒体头像及Opus音频 |
| M4 桌面与完整受控路径 | TASK-020～024 | 批准UI接真实状态，全路径撤销和两服务无正文证明 |
| M5 Windows容量与交付准备 | TASK-025～028 | NAT矩阵、16人60分钟、恢复安全、Windows安装更新卸载 |
| M6 待补平台与集成准出 | TASK-029～032 | macOS/Linux实机及三平台互通后完整准出 |

## 增量开发批次与停点

本计划不采用“一次性完成全部编码”的方式。E/F/G 仍是正式工作流阶段，但在阶段内部按以下小批次推进；每个批次都必须完成“编码 → 开发自测 → 独立测试 → 缺陷回归 → 批次评审”闭环，未达到本批出口不得进入下一批。所有参与者必须遵守 [04a-development-standards.md](04a-development-standards.md)；批次评审只记录在对应阶段文档和执行证据中，不新增审批门禁，也不改变现有 CLI 状态机。

| 批次 | 任务范围 | 批次目标 | 进入条件 |
| --- | --- | --- | --- |
| B0 环境与契约 | TASK-001～003 | 工具链、协议契约、控制面/数据库基础可复现 | D 规划完成；无任务执行证据要求 |
| B1 身份与群治理 | TASK-004～006 | 账号、好友/群、角色治理和审计闭环 | B0 独立测试通过；依赖任务 completed |
| B2 活动、网络与授权 | TASK-007～012 | 房间活动、TUN、隔离、租约和撤销 PoC | B1 通过；TASK-009 可与 B1 并行但必须在 B2 评审前完成 |
| B3 消息与本机存储 | TASK-013～015 | 元数据、客户端持久化、文字同步和收据 | B2 通过；TASK-014 的独立存储验证完成 |
| B4 媒体与语音 | TASK-016～019 | 媒体安全、附件/头像、Opus 语音和 Windows 音频设备 | B3 通过；网络授权和本机存储已稳定 |
| B5 桌面壳与 UI 接入 | TASK-020～022 | 安全 IPC、账号/活动 UI、聊天媒体 UI | B4 通过；UI 只接真实状态，不保留模拟成功路径 |
| B6 撤销与两服务证明 | TASK-023～024 | 全路径撤销、离线副本规则、服务器无正文/转发证明 | B5 通过；所有内容路径可追踪到客户端直传 |
| B7 Windows 验证与制品 | TASK-025～028 | NAT、16 人 60 分钟、恢复安全、Windows 制品 | B6 通过；Windows 实机/受控拓扑和工具链可用 |
| B8 跨平台与互通 | TASK-029～031 | macOS/Linux 适配、三平台互通及游戏矩阵 | B7 通过；各平台真实环境或明确的非零未验证证据 |
| B9 完整回归 | TASK-032 | 全量构建、REQ/AC 覆盖和发布准备入口 | B8 通过；无开放缺陷、各批证据完整 |

### 每批次的固定执行顺序

1. **编码出口（E / developer）**：先阅读 `04a-development-standards.md`，只实现本批登记任务及其必要测试/工具；每项任务按依赖顺序通过 `task-run` 执行登记的测试命令。出口要求：任务状态为 `completed`，最近证据退出码为 0，代码树哈希与证据一致，接口/迁移/配置和已知限制可供审阅。
2. **开发自测出口（F / self_tester）**：针对本批当前代码执行构建、单元/集成、异常边界、权限/安全和未完成扫描；真实环境缺口必须非零并标记“未验证”。出口要求：本批自测报告、命令和证据齐全，失败项已修复并重跑，不能用开发者自述或 MOCK/SKIP 代替。
3. **独立测试出口（G / test_engineer）**：仅依据需求、验收标准和本批测试用例执行黑盒/系统测试；覆盖成功、失败、并发、越权、旧代次、断网/崩溃及平台边界。失败用例创建 DEF 与 FIX 任务，修复后由独立测试人员回归并关闭。出口要求：本批范围内无开放阻断缺陷，未覆盖项有明确原因和非零证据。
4. **批次评审停点**：记录批次完成任务、F/G 证据、缺陷清单、遗留风险、回退点和下一批进入条件。只有评审记录完整且依赖任务均 `completed`，才开始下一批；任何失败均停在当前批次修复，不跨批“带病前进”。

批次执行的统一路径为：

```text
实现本批 → developer task-run → F/self_tester verify
→ 修复并回归 → G/test_engineer 用例
→ DEF/FIX 修复回归 → 批次评审记录 → 下一批
```

最终 B9 完成后，仍需再执行一次全量 F 自测和完整 G 回归；只有全量回归、发布清单和制品一致性检查完成，才能进入 H。H/I 的人工批准和验收规则保持不变。

输入批准：需求APR-B-0ebffa236a4e、B1 APR-B1-15e3bfc1a414、B2 APR-B2-9fb61c0498c2、C APR-C-de7690895f4e。C摘要 `addabfe11a58f52d7057ca166d5c0d953d5eda394f85de17388bd3512c022114`。

交接文件：`docs/02-requirements.md`、`02a-prototype.md`、`02b-ui.md`、`03-architecture.md`；交互入口：`workspace/design/prototype/index.html`、`workspace/design/ui/index.html`。已批准输入不因本轮规划而修改。

仅Java控制面和Lighthouse两类在线业务服务，DB为控制面内部依赖。客户端保存与直传；无在线副本暂不可用，最后副本丢失不保证恢复；群同步不得开放跨房游戏/语音，无Relay/SFU/TURN/服务器内容缓存回退。D不改变批准架构或新增产品范围。

## 任务列表

任务已通过 `python zhijie.py add-task` 持久化；详细输入/输出/验收/回退位于 `tasks/tasks.json`，不直接编辑该任务数据。下列命令是未来E阶段执行契约，不代表脚本已经存在或运行通过。

| 任务 | 工作内容 | 依赖 | 登记测试命令 |
| --- | --- | --- | --- |
| TASK-001 | Windows 工具链与可复现构建准备 | 无 | `python "scripts/verify.py" --suite environment-windows` |
| TASK-002 | 协议契约与验证入口 | TASK-001 | `python "scripts/verify.py" --suite protocol-contracts` |
| TASK-003 | Java 控制面及 PostgreSQL 事务基础 | TASK-002 | `python "scripts/verify.py" --suite control-foundation` |
| TASK-004 | 邮箱账号与设备会话 | TASK-003 | `python "scripts/verify.py" --suite identity-sessions` |
| TASK-005 | 好友与私密群邀请 | TASK-004 | `python "scripts/verify.py" --suite social-groups` |
| TASK-006 | 角色、群主治理与只读审计 | TASK-005 | `python "scripts/verify.py" --suite governance-audit` |
| TASK-007 | 房间活动与单设备并发事务 | TASK-006 | `python "scripts/verify.py" --suite activity-transactions` |
| TASK-008 | 短期租约签发与激活屏障 | TASK-007 | `python "scripts/verify.py" --suite lease-authority` |
| TASK-009 | Nebula 私有传输网栈直连 PoC | TASK-002 | `python "scripts/verify.py" --suite private-overlay-poc` |
| TASK-010 | Windows 活动 TUN 与地址恢复 | TASK-007、TASK-009 | `python "scripts/verify.py" --suite windows-tun` |
| TASK-011 | 群应用通道与跨房隔离 | TASK-008、TASK-010 | `python "scripts/verify.py" --suite scope-isolation` |
| TASK-012 | 真实隧道租约与设备切换 PoC | TASK-011 | `python "scripts/verify.py" --suite tunnel-revocation-poc` |
| TASK-013 | 消息元数据、删除及逻辑配额 | TASK-008、TASK-012 | `python "scripts/verify.py" --suite message-metadata` |
| TASK-014 | 客户端持久存储与崩溃恢复 | TASK-002 | `python "scripts/verify.py" --suite local-store` |
| TASK-015 | 点对点文字同步及持久收据 | TASK-011、TASK-013、TASK-014 | `python "scripts/verify.py" --suite peer-text-sync` |
| TASK-016 | 收发端独立媒体安全校验 | TASK-014 | `python "scripts/verify.py" --suite media-safety` |
| TASK-017 | 附件、头像直传与生命周期 | TASK-015、TASK-016 | `python "scripts/verify.py" --suite peer-media-transfer` |
| TASK-018 | 真实 Opus 语音链路与网状协议 | TASK-012 | `python "scripts/verify.py" --suite voice-protocol` |
| TASK-019 | Windows 音频设备与控制 | TASK-018 | `python "scripts/verify.py" --suite windows-audio-devices` |
| TASK-020 | Electron 应用壳及安全 IPC | TASK-012、TASK-014 | `python "scripts/verify.py" --suite desktop-shell` |
| TASK-021 | 账号、社交及活动 UI 接入 | TASK-019、TASK-020、TASK-006 | `python "scripts/verify.py" --suite ui-control-integration` |
| TASK-022 | 聊天媒体 UI 与受控本机读取 | TASK-017、TASK-021 | `python "scripts/verify.py" --suite ui-content-integration` |
| TASK-023 | 全路径撤销、离线副本与切换回归 | TASK-022 | `python "scripts/verify.py" --suite end-to-end-revocation` |
| TASK-024 | 两服务约束与内容流量证明 | TASK-023 | `python "scripts/verify.py" --suite two-service-audit` |
| TASK-025 | Windows NAT 可达与故障矩阵 | TASK-024 | `python "scripts/verify.py" --suite nat-matrix-windows` |
| TASK-026 | 16人并行60分钟与背压容量 | TASK-025 | `python "scripts/verify.py" --suite capacity-16x60` |
| TASK-027 | 持久化恢复与秘密扫描 | TASK-023、TASK-003 | `python "scripts/verify.py" --suite recovery-security` |
| TASK-028 | Windows 安装更新卸载与制品准备 | TASK-026、TASK-027 | `python "scripts/verify.py" --suite packaging-windows` |
| TASK-029 | macOS 网络音频安装适配与验证 | TASK-023 | `python "scripts/verify.py" --suite platform-macos` |
| TASK-030 | Linux 网络音频安装适配与验证 | TASK-023 | `python "scripts/verify.py" --suite platform-linux` |
| TASK-031 | 三平台互通及已测游戏矩阵 | TASK-028、TASK-029、TASK-030 | `python "scripts/verify.py" --suite cross-platform-matrix` |
| TASK-032 | 完整业务构建与验收回归入口 | TASK-031、TASK-026、TASK-027 | `python "scripts/verify.py" --suite full-regression` |

### 产出与验收边界

- **TASK-001**：Java 21、固定 Go/Nebula、Node 和原生音频工具链的项目级版本锁、来源/哈希/许可清单；Windows 构建入口和预检。验收：本机实际编译运行最小 Java/Go/原生程序；缺少工具明确失败；不改变系统默认 Java、不覆盖原型。
- **TASK-002**：REST/IPC/Peer/事件及错误码 schema、兼容规则、测试运行器与分层测试夹具。验收：拒绝未知字段、跨 scope/旧版本/长度越界；验证入口缺真实环境必须非零，不能将 skipped 当通过。
- **TASK-003**：模块化单体、数据库迁移、事务/锁序、Outbox/游标、脱敏日志基础。验收：真实 PostgreSQL 上迁移/回滚演练、重启持久化、并发冲突及 Outbox 幂等；不提供正文/上传 DTO。
- **TASK-004**：注册验证/找回/登录退出、Argon2id、凭据轮换、设备公钥与未验证白名单。验收：AC-001/002/033；受控测试邮箱收发、错误/过期/重放/限流，旧密码与刷新凭据失效；日志无秘密。
- **TASK-005**：好友申请/搜索/删除、群配置和成员、邀请失效/撤销/幂等接受。验收：AC-003～006/008；好友不授群权、无公开群搜索/私聊；邀请人退群不影响已接受成员。
- **TASK-006**：权限矩阵、转让接受、恢复申请、房主接管及只追加管理记录。验收：AC-007～009/028/029；失权/跨群拒绝，普通离线不接管，人工恢复不自动继任。
- **TASK-007**：持久房间/临时活动、统一加入/切房/设备转移、16 人占额、地址历史与60秒恢复。验收：AC-010～016/022/037；真 DB 并发17人、暂停/免密码边界、目标失败保旧活动；新端仅pending不提前激活。
- **TASK-008**：3秒租约/1秒刷新、签发截止持久上界、撤销版本及到期后激活、快照屏障。验收：SP-04/09；真 DB 签发撤销竞争、迟到响应、服务崩溃恢复；无旧版本补发或双有效资格。
- **TASK-009**：固定 Nebula 1.12.0、用户态网栈适配、设备身份和 Lighthouse 配置；直连收发探测。验收：SP-01/08；两个隔离客户端真实虚拟 TCP/UDP，显式禁Relay、无全网OS路由；不能仅用内存模拟。
- **TASK-010**：Windows TUN窄特权服务、活动/27路由、游戏包代次封装、IP冲突与恢复。验收：AC-020～024；真实接口双端TCP/UDP、默认路由/DNS不改、物理LAN不转发；安全可用时历史IP优先。
- **TASK-011**：Helper收发Gate、当前scope应用协议、用户态内容端点及游戏源目标校验。验收：SP-02；同群未入房可应用同步，跨房游戏/语音、跨群内容及伪造/旧代次/分片失败；旧连接不绕Gate。
- **TASK-012**：真实Helper接控制面授权、包级期限/看门狗、故障注入与时间测量。验收：SP-04；既有/新建TCP/UDP均在5秒目标停用；丢WSS、时钟倒退/休眠、双设备切换和60秒保留不双活。
- **TASK-013**：稳定消息ID/序号、随机化commitment、15分钟预留、10GiB逻辑预算、tombstone/最小收据。验收：真DB并发配额/取消/删除/完成竞争；同键一条、旧代次拒绝，无正文/文件名入参；代删审计和范围正确。
- **TASK-014**：SQLite/对象文件库、OS密钥保护、fsync/原子提交、账号隔离、本机水位和清理记账。验收：SP-05/06；崩溃点后无假ACK、重启可读、本机满拒收不删历史；15分钟及累计24小时/启动先清理。
- **TASK-015**：双向TLS应用传输、作者信封、元数据缺口拉取、群/活动文字及ACK与部分送达。验收：AC-038/045/047/048；双端真实传输落盘、无副本暂不可用、丢ACK/重复/乱序只一条、上线先tombstone。
- **TASK-016**：低权限探测Worker、图片重编码/元数据移除、MP4探测、头像同等安全路径。验收：AC-041；恶意发送端绕过自检接收仍拒绝脚本/坏文件/动画/超像素/时长/路径越界/超时，资源与网络隔离有效。
- **TASK-017**：chunk/Range续传、最多2出站任务、速率背压、预留/取消/重试、头像引用与缺失占位。验收：AC-039～043；两端字节/commitment/落盘一致，边界精确验证、并发不重复占额，失败不生成幽灵消息。
- **TASK-018**：非特权PortAudio/libopus Worker、RTP代次/序号、混音/抖动/FEC与真实媒体状态。验收：SP-03；至少双端真实采集收听和多人同时发言；隧道断语音停、音频失败隧道保持，无服务端转发。
- **TASK-019**：静音/关闭接收、总/成员音量、输入输出切换/测试、权限及热插拔。验收：AC-018/036；真实或受控Windows设备权限拒绝/拔出/恢复不崩溃；成员音量仅本地，不伪报连接。
- **TASK-020**：React/TypeScript壳、窄preload/Helper IPC、CSP/sandbox、账号隔离及活动Store。验收：路由切换不重建活动、任意路由/路径/命令/导航被拒，Helper崩溃关Gate；批准UI令牌与键盘行为保持。
- **TASK-021**：账号/好友/群邀请管理/房间/设置页面连接真实API与音频网络事件。验收：SP-12；真实成功/失败/超时/失权、busy/取消/字段焦点、创建后入房失败/退出确认；无模拟成功状态。
- **TASK-022**：群/活动聊天、逐成员收据、缺失占位、图片查看/视频显式播放、受控media协议/Range。验收：真实落盘ACK才已接收，待同步/部分/失败准确；600/900/1440窗口与焦点，Renderer不能直读file路径。
- **TASK-023**：整合删除/踢出/退出/退群/结束/解散/账号失效的网络/音频/读取/播放撤销。验收：AC-026/040/042/047；提交到最后可用<=5秒目标；丢事件、旧授权/游标/播放缓冲、重连快照及设备争抢均验证。
- **TASK-024**：仅控制面及Lighthouse运行清单、两端/服务器抓包和DB/磁盘/日志白名单检查。验收：AC-044；真实文字/头像/图片/视频/音频/游戏完成时服务器无正文/业务转发；控制流量单列、Relay全禁。
- **TASK-025**：同LAN/常见NAT/双端受限NAT或CGNAT/禁UDP防火墙矩阵与可复现夹具。验收：AC-024/032/035/046；逐成员真实结果、部分失败不伪全连、15秒恢复目标或明确失败，无隐式中继。
- **TASK-026**：16个真实/受控客户端音频/游戏TCP-UDP/附件并行与控制租约压测。验收：AC-017/034/035；60分钟、每端硬件/带宽/RTT/丢包/混音资源、控制尾延迟及附件让行；不可缩为短演示。
- **TASK-027**：DB备份恢复纪元/tombstone连续性、本地备份导入屏障、诊断/审计秘密扫描。验收：AC-027～029/033/045/047；旧备份不复活删除、全副本离线及最后副本丢失诚实反馈，日志无凭据/正文。
- **TASK-028**：NSIS签名/版本兼容、Helper/Worker安装更新卸载、许可证/SBOM/制品哈希及回退说明。验收：AC-030；Windows实装升级卸载及失败回退、数据保持策略验证；无签名渠道不声称签名通过，不执行生产部署。
- **TASK-029**：macOS Intel/Apple Silicon TUN/音频权限与签名公证/安装更新适配、系统版本矩阵。验收：AC-018～026/030/031/036；真实macOS设备验证，缺设备/证书非零且未验证，不用Windows代替。
- **TASK-030**：Ubuntu22.04/24.04网络/音频权限、桌面环境、AppImage安装更新卸载适配。验收：AC-018～026/030/031/036；真实Linux记录内核/桌面/音频栈/权限，缺环境非零且未验证。
- **TASK-031**：Windows/macOS/Linux组合真实音频及TCP/UDP、手动IP游戏版本验证矩阵。验收：AC-020/023/030/031；工具互通与游戏兼容分别记录，不把未测组合标通过，缺平台不得完成。
- **TASK-032**：当前工作区可复现构建、REQ/AC全覆盖回归入口、发布准备清单与阶段交接。验收：AC-001～048；实际构建/异常/安全/容量/平台检查均有真实结果，无SKIP冒充完成；F/G/H各角色仍需独立执行。

## 任务依赖关系

依赖图以任务契约为准；CLI task-run拒绝依赖未completed的任务。TASK-009不依赖完整业务控制面，可尽早暴露Nebula私有网栈风险；TASK-011合并真实授权/TUN，TASK-012实测撤销之后再推进真实业务整合。TASK-014存储可独立准备，但不能据此宣称网络同步完成。

Windows先行执行TASK-001～028的就绪任务；TASK-029/030等待各自平台环境、互不依赖；TASK-031等待三个平台，TASK-032等待跨平台及容量/恢复。单机多进程测试不等于跨物理网络NAT或真实跨系统通过。本轮仅制定可并行依赖，不启动代理或实现。

| 架构风险 | 对应任务与真实验证 |
| --- | --- |
| SP-01 私有网栈/TUN | TASK-001/009/010/029/030：工具链、真实网卡及TCP/UDP |
| SP-02 群/活动隔离 | TASK-011/012/031：同群应用通道、跨房/跨群拒绝和旧代次/IP复用 |
| SP-03 音频/设备 | TASK-018/019/029/030：实际采集播放、多发言/混音和热插拔 |
| SP-04 租约/撤销/屏障 | TASK-008/012/023：3秒授权、1秒刷新、5秒撤销与设备切换 |
| SP-05 落盘/ACK/副本 | TASK-014/015/017/027：崩溃点、丢ACK、全部副本离线和最后副本丢失 |
| SP-06 媒体/配额/清理 | TASK-013/014/016/017：接收端独立校验、10GiB逻辑预算、磁盘满/15分钟/24小时 |
| SP-07 NAT/容量 | TASK-025/026/031：逐成员可达、家庭带宽、16人连续60分钟 |
| SP-08 两服务/无正文 | TASK-009/024：显式禁Relay、两端/服务器抓包与存储检查 |
| SP-09 Java/DB并发 | TASK-003/007/008/013/027：真实数据库锁、签发/撤销/预算竞争和恢复 |
| SP-10 邮件/秘密 | TASK-004/027：受控邮箱、凭据及日志扫描 |
| SP-11 平台/制品 | TASK-028～031：签名/许可、安装更新卸载和互通 |
| SP-12 真实UI | TASK-020～023：收据/缺口、逐成员状态、焦点/取消及实际事件 |

## 完成定义

1. 业务代码仅放项目workspace。E阶段建议按职责创建control、helper、desktop、workers、scripts、tests、ops目录；D不创建实现目录，不覆盖批准原型/UI或用户文件，不读取根tmp。
2. 实现及必要测试完备，含失败/边界/并发/越权/旧代次，依赖先完成。单元测试不能替代真实IO、落盘、网卡、音频和平台验证。
3. 登记入口 `python "scripts/verify.py" --suite <suite>` 在E实现：TASK-001先建立环境子命令，TASK-002扩展测试调度与协议子命令，避免相互依赖。入口必须调用真实构建/测试驱动、透传退出码，不能只检查文件存在、读取旧报告、返回固定JSON或用人工自述代替执行。
4. 缺工具/设备/邮箱/证书、未实现脚本、跳过的必需检查必须报非零及明确未验证；不得将SKIP/MOCK记为核心能力完成。已知外部缺口先记录并做无依赖工作，避免无意义消耗task-run重试次数。
5. task-run以developer角色真实执行登记test原文，CLI记录退出码0和证据后才标completed。证据由CLI写tests/evidence，含角色/命令/时间/代码树hash，禁止手写/修改执行证据、状态/审计/审批文件。
6. ACK必须在接收端独立校验和持久提交后；元数据登记不是送达。旧副本重连先同步tombstone/当前资格；既有连接、在途流、本机读取与播放均受撤销Gate约束。
7. 单任务通过不等于产品交付通过，F/G/H对当前代码和最终制品仍必须真实构建与回归。平台缺口不能用D文档门禁或B2模拟测试补齐。

## 测试要求

CLI根命令从仓库根调用，登记test由执行器在项目workspace中运行；参数字面须完全一致。本轮不执行下列示例：

```powershell
python zhijie.py task-run zhijie TASK-001 --actor codex-developer --role developer --command 'python "scripts/verify.py" --suite environment-windows' --timeout 300
```

任务超时按测试选择：环境/单元300秒、集成900秒、平台/容量最多3600秒。16人60分钟验证须有连续3600秒真实会话，不能将短会话相加。考虑CLI单次执行上限包含启动开销，E先通过普通工具启动并监测完整受控长会话，保持进度反馈；采集结束后由登记的task-run命令对当前制品、原始连续时序、拓扑/端数及采集进程结果作实际校验，并执行必要短回归。校验须绑定源码/二进制hash、唯一采集ID、实时握手nonce和各端原始计数，拒绝旧报告、手工摘要或不匹配制品。CLI输出包含实际检查及退出码；原始采集不是手写的workflow证据。TASK-002在E实现这些验证基础，TASK-026实现采集与准出，不为适应超时修改工作流框架。

| 层次 | 环境与通过依据 |
| --- | --- |
| 契约/纯逻辑 | 确定性版本/长度/身份/scope/幂等/重放边界；不冒充真实IO |
| 控制集成 | 独立真实测试PostgreSQL的事务、锁、迁移、Outbox和恢复；DTO只接允许元数据 |
| 网络/撤销 | 两个隔离客户端与Lighthouse，真实虚拟IP TCP/UDP、TUN/Gate/权限和丢事件/时钟故障 |
| 音频/媒体 | 实际或受控系统音频端、真实编码与传输；接收端安全探测、字节完整性和持久化 |
| NAT/容量 | 可复现受控拓扑/网络、16端、每端上下行预算、逐成员结果及连续60分钟时序 |
| 桌面/交付 | Windows先行，后补真实macOS/Linux；UI错误/焦点/取消，安装升级卸载/签名和游戏版本分别记录 |

当前尚无Java21/Go环境验证、macOS/Linux实机、真实邮件渠道或签名证书接入。受控SMTP测试夹具只用于开发验证，不新增生产业务通讯服务；不要求用户在对话提供密钥。运行时、驱动、网络权限与版本均须在E记录。

### 完整阶段推进流程

| 阶段 | 出口与角色 | 人工节点 |
| --- | --- | --- |
| D 开发规划 | tech_lead；持久任务、依赖/REQ追踪、完成定义与风险回退门禁 | 默认无人工门禁；按用户当前要求先完成规划并停留供审阅 |
| E 编码实现 | developer；按 B0～B9 批次实现，逐批 task-run、停点和有限修复；全部批次完成后才可提交E出口 | 不绕过缺环境，不把代码存在当验证通过；批次未闭环不得进入下一批 |
| F 开发自测 | self_tester；每批执行构建/测试/异常/设计/安全/未完成扫描并保存证据；B9后再做一次全量自测 | 无默认人工门禁，仍必须真实验证；本批失败不得交给G或下一批 |
| G 独立测试 | test_engineer；每批按对应AC和用例独立测试、建缺陷并回归；B9后执行完整REQ/AC回归 | 角色职责独立，不能直接采信开发自述；有开放阻断缺陷不得过批次出口 |
| H 发布准备 | 最终制品/hash、配置/迁移/回退、最终制品回归、发布清单 | 必须人工批准，批准不授权生产部署 |
| I 验收归档 | 需求/任务/证据/缺陷/版本/制品索引及验收，CLI生成归档ZIP | 必须最终人工验收 |

同一AI可顺序承担不同角色但不能冒充多人独立验证；同一批次的 developer、self_tester、test_engineer 证据必须按角色分别记录，即使由同一执行者顺序完成。用户已授权 AI 在开发过程中按 `04a-development-standards.md` 小步进行本地 commit；该授权不包含远程 push、合并到 `main`、改写历史、标签或生产部署。E/F/G不是人工审批节点，不额外捏造工作流审批要求；当前停留D是遵循用户“先不要直接实现代码”的明确指示。

## 风险和回退策略

- 工具链采用项目级隔离，E再安装/配置，不改系统默认Java、不覆盖已有用户文件或批准原型。Windows路由/TUN/驱动有所有权标记与退出清理，只删除本项目创建的对象，不做泛化网络重置。
- Nebula私有网栈集成及短租约撤销是阻断风险，TASK-009～012不通过则停止依赖任务。核心路线改变必须重评审，不能暗加Relay/SFU、开放全网TUN或延长授权TTL。
- Windows优先不缩减三平台承诺，TASK-029～032无环境保持pending/未验证。没有平台、NAT或真实设备证据不能宣称E全部完成、三平台支持或发布就绪。
- CLI每任务最多3次执行，失败进入awaiting_retry；定位修复后通过retry恢复，保留所有失败证据。次数耗尽或需改基线时人工介入，不删证据/任务重置计数。
- 批次采用“当前批次内修复优先”策略：F/G 发现缺陷时只回到相关任务和当前批次回归，不跳过出口；同一缺陷连续达到CLI重试上限、影响已批准基线或需要改任务契约时暂停，请人工介入后再决定是否重规划。
- 回退只针对本任务拥有实现/配置，保留用户内容、审计、tombstone及授权版本。DB迁移在独立测试库演练；恢复前关闭签发、核验删除连续性并更新恢复纪元，不把旧备份直接开放成可读。
- 协议/制品不兼容拒绝接入，Helper/UI/Worker版本一致后才开放Gate；更新失败关闭网络并保留数据。未签名/未实装制品明确未验证，不自动部署生产。
- KISS/YAGNI限定两类业务服务及当前需求；DRY统一加入/授权/幂等/信封/错误码；SOLID分离控制事务、Helper、存储、媒体/音频和呈现。原则不能被用来删掉真实安全验收。

## 需求追踪

| 需求 | 持久任务 | 验收与预期证据 |
| --- | --- | --- |
| REQ-001 | TASK-004、TASK-021、TASK-027；TASK-032完整回归 | AC-001；后续task-run/verify实际证据，当前无 |
| REQ-002 | TASK-003、TASK-004、TASK-007、TASK-008、TASK-012、TASK-020、TASK-021、TASK-023；TASK-032完整回归 | AC-002/037；后续task-run/verify实际证据，当前无 |
| REQ-003 | TASK-005、TASK-021；TASK-032完整回归 | AC-003；后续task-run/verify实际证据，当前无 |
| REQ-004 | TASK-005、TASK-013、TASK-015、TASK-016、TASK-017、TASK-021、TASK-022；TASK-032完整回归 | AC-004/029/045；后续task-run/verify实际证据，当前无 |
| REQ-005 | TASK-005、TASK-021；TASK-032完整回归 | AC-005/006；后续task-run/verify实际证据，当前无 |
| REQ-006 | TASK-006、TASK-021；TASK-032完整回归 | AC-007；后续task-run/verify实际证据，当前无 |
| REQ-007 | TASK-002、TASK-003、TASK-005、TASK-006、TASK-008、TASK-011、TASK-021、TASK-023；TASK-032完整回归 | AC-008；后续task-run/verify实际证据，当前无 |
| REQ-008 | TASK-006、TASK-007、TASK-021；TASK-032完整回归 | AC-009；后续task-run/verify实际证据，当前无 |
| REQ-009 | TASK-007、TASK-021、TASK-026；TASK-032完整回归 | AC-010/011/013；后续task-run/verify实际证据，当前无 |
| REQ-010 | TASK-007、TASK-021、TASK-023；TASK-032完整回归 | AC-012；后续task-run/verify实际证据，当前无 |
| REQ-011 | TASK-002、TASK-007、TASK-008、TASK-010、TASK-012、TASK-020、TASK-021、TASK-023；TASK-032完整回归 | AC-014～016/035/037；后续task-run/verify实际证据，当前无 |
| REQ-012 | TASK-018、TASK-019、TASK-024、TASK-025、TASK-026、TASK-029、TASK-030、TASK-031；TASK-032完整回归 | AC-017/034/035/044/046；后续task-run/verify实际证据，当前无 |
| REQ-013 | TASK-019、TASK-026、TASK-029、TASK-030；TASK-032完整回归 | AC-018/036；后续task-run/verify实际证据，当前无 |
| REQ-014 | TASK-007、TASK-009、TASK-010、TASK-011、TASK-018、TASK-024、TASK-025、TASK-026、TASK-029、TASK-030、TASK-031；TASK-032完整回归 | AC-020/023/031/034；后续task-run/verify实际证据，当前无 |
| REQ-015 | TASK-007、TASK-010、TASK-011、TASK-029、TASK-030、TASK-031；TASK-032完整回归 | AC-021/022；后续task-run/verify实际证据，当前无 |
| REQ-016 | TASK-009、TASK-010、TASK-011、TASK-025、TASK-029、TASK-030、TASK-031；TASK-032完整回归 | AC-023/024；后续task-run/verify实际证据，当前无 |
| REQ-017 | TASK-012、TASK-018、TASK-019、TASK-023、TASK-025、TASK-026、TASK-029、TASK-030、TASK-031；TASK-032完整回归 | AC-019/025/046；后续task-run/verify实际证据，当前无 |
| REQ-018 | TASK-002、TASK-008、TASK-012、TASK-013、TASK-017、TASK-023、TASK-027、TASK-029、TASK-030；TASK-032完整回归 | AC-026；后续task-run/verify实际证据，当前无 |
| REQ-019 | TASK-013、TASK-014、TASK-015、TASK-022、TASK-023、TASK-027；TASK-032完整回归 | AC-027/038～043/045/047；后续task-run/verify实际证据，当前无 |
| REQ-020 | TASK-003、TASK-006、TASK-013、TASK-021、TASK-027；TASK-032完整回归 | AC-028；后续task-run/verify实际证据，当前无 |
| REQ-021 | TASK-003、TASK-006、TASK-007、TASK-013、TASK-014、TASK-015、TASK-022、TASK-023、TASK-027；TASK-032完整回归 | AC-029/038～040/045/047；后续task-run/verify实际证据，当前无 |
| REQ-022 | TASK-001、TASK-020、TASK-028、TASK-029、TASK-030、TASK-031；TASK-032完整回归 | AC-030/031；后续task-run/verify实际证据，当前无 |
| REQ-023 | TASK-008、TASK-010、TASK-012、TASK-018、TASK-019、TASK-020、TASK-021、TASK-025、TASK-026、TASK-029、TASK-030、TASK-031；TASK-032完整回归 | AC-024/032/035；后续task-run/verify实际证据，当前无 |
| REQ-024 | TASK-001、TASK-002、TASK-003、TASK-004、TASK-008、TASK-009、TASK-012、TASK-014、TASK-016、TASK-020、TASK-024、TASK-027、TASK-028、TASK-029、TASK-030；TASK-032完整回归 | AC-033；后续task-run/verify实际证据，当前无 |
| REQ-025 | TASK-011、TASK-013、TASK-014、TASK-015、TASK-022、TASK-023；TASK-032完整回归 | AC-038/040/042/045；后续task-run/verify实际证据，当前无 |
| REQ-026 | TASK-014、TASK-016、TASK-017、TASK-022、TASK-023、TASK-024；TASK-032完整回归 | AC-039/040/043/045；后续task-run/verify实际证据，当前无 |
| REQ-027 | TASK-013、TASK-014、TASK-016、TASK-017、TASK-022、TASK-023、TASK-026；TASK-032完整回归 | AC-040～043/033/047；后续task-run/verify实际证据，当前无 |
| REQ-028 | TASK-001、TASK-003、TASK-009、TASK-011、TASK-018、TASK-024、TASK-025、TASK-026、TASK-031；TASK-032完整回归 | AC-044/046；后续task-run/verify实际证据，当前无 |
| REQ-029 | TASK-002、TASK-011、TASK-013、TASK-014、TASK-015、TASK-017、TASK-022、TASK-023、TASK-024、TASK-027；TASK-032完整回归 | AC-045/047/048；后续task-run/verify实际证据，当前无 |

## 本轮实际检查与交接

- CLI确认D_PLANNING/in_progress，原任务列表为空；32次add-task全部退出0，任务均pending、attempts=0、无执行证据。
- `python zhijie.py validate zhijie`退出0：文档/任务JSON/契约/依赖/REQ追踪和前置审批一致性均通过，0错误、0警告。独立Python一致性检查确认32任务、29需求、12项风险覆盖、依赖无环、命令唯一、全pending且无执行证据。`git diff --check`退出0，无空白错误。上述仅规划验证，不是业务实现或验收通过。
- 当前停留D，计划完成后供用户审阅；本轮不submit到E，不执行task-run、不创建测试入口或业务代码、不自动开始编码。
