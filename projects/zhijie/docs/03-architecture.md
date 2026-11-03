# 架构与详细设计：织界

> 状态：C 阶段架构草案，待人工审批。依据当前已批准的 `docs/02-requirements.md`（REQ-001～REQ-028）、`docs/02a-prototype.md` 和 `docs/02b-ui.md`，以及 `workspace/design/prototype/index.html`、`workspace/design/ui/index.html`。
>
> 本文中的容量、音质、时延和恢复数值均为验收目标，不是实测结果。当前未运行架构 PoC；Tauri/Rust 客户端、Windows/macOS/Linux 的音频与 TUN、12 人容量、100 人在线、直连/中继、邮件投递、签名安装与自动更新均须在对应验证完成后才能宣称支持。

## 架构概述

### 1. 目标与边界

织界首版采用“Rust 客户端核心与网络辅助逻辑 + 跨平台 WebView 界面 + 单控制面 + 自托管语音媒体面 + 可选房间密文中继”的客户端/服务端架构。持久群组和房间属于控制面；一次游戏联机属于房间内的临时活动。用户加入活动后，客户端并行发起语音和虚拟网络连接，两者使用不同凭据、状态机和故障域。

首版必须形成以下真实链路，不能用界面状态或模拟传输替代：

1. 好友、群组、邀请、消息、权限和持久房间均由服务端权威管理并持久化。
2. 语音经 WebRTC 进入自托管 SFU，支持最多 12 个账号的真实双向音频；LiveKit 是首选候选，最终 SDK、音频处理链和部署形态由 SP-03/SP-07/SP-10 验证后锁定。
3. 虚拟网络由独立 Rust 特权辅助进程创建真实 TUN，并采用经安全与维护性评审的 Rust WireGuard 实现建立端到端密文通信；路径由运营策略决定，支持直连限定、直连优先可回退和强制中继，不再默认全量中继。
4. 中继只转发同房、同活动代次、有效设备租约之间的密文；直连只允许当前授权 Peer。两种路径都不设置默认路由、不代理普通互联网、不转发物理局域网其他设备。
5. 退出、踢出、失权、结束活动和删除房间均由服务端产生权威撤销，语音、直连、中继和内容同步分别执行撤销，迟到结果不能恢复旧资格。
6. 初期部署受单台 2 核 CPU、2 GB 内存、Debian、5 Mbit/s 限制。100 个账号在线、12 人满房语音和游戏中继是不同容量目标，任何一档通过都不能替代其他档。

### 2. 逻辑拓扑

```text
桌面客户端（Windows / macOS / Linux）
├─ Tauri 候选容器 + 现有 HTML/CSS/JS 产品 UI
│  └─ 经版本化 Command/Event 调用 Rust 核心，不直接持有系统特权
├─ Rust Client Core
│  ├─ 会话、活动状态机、连接编排、设备状态、更新与本地安全存储
│  ├─ Voice Adapter：候选媒体 SDK + AEC/降噪/AGC/VAD
│  └─ Network Controller：密钥、公钥、策略、直连/中继状态机
└─ Rust 特权 Network Helper
   └─ 候选 Rust WireGuard、真实 TUN、/27 路由、直连与 Relay 传输

控制面与数据面（首台 2 核 / 2 GB / Debian / 5 Mbit/s）
├─ Java 控制面（模块化单体，资源门槛待 P0/P1）
│  ├─ 认证、好友、群组、房间、活动、运营网络策略
│  ├─ IP / 租约、撤销、消息、审计
│  └─ PostgreSQL + Outbox 事件
├─ 自托管 WebRTC SFU（LiveKit 首选候选）
│  └─ 真实多人语音媒体，与游戏网络路径分开计量
└─ Rust Room Relay
   └─ WireGuard 密文转发，不持有成员私钥

连接关系
1. 产品 UI -> Rust Client Core：Tauri Command/Event 候选 IPC
2. Rust Client Core -> Java 控制面：HTTPS / WebSocket
3. Voice Adapter -> 自托管 SFU：WebRTC
4. Network Helper -> 当前授权 Peer：WireGuard 直连；或 -> Rust Relay：加密中继会话
5. Java 控制面 -> SFU、Rust Relay：管理 API / 授权、策略与撤销事件
6. Java 控制面 -> PostgreSQL：JDBC / 事务
```

Tauri、媒体 SDK、Rust WireGuard 和 Relay 传输均为有明确验证出口的架构候选，不表示已完成三平台适配。若候选未通过，必须保留外部状态/命令、授权代次和安全边界，提交替代 ADR 后重新评审，不能静默回到 Electron、Go 或固定全量中继旧方案。

### 3. 权威状态与一致性

- PostgreSQL 是账号、群籍、角色、房间、活动资格、IP 分配、连接租约、运营网络策略和消息的唯一权威数据源；客户端缓存和 WebSocket 事件均不是授权依据。
- 所有敏感接口在执行时重新校验账号状态、群籍、角色、房间和活动资格，不能仅凭访问令牌中的旧声明授权。
- 加入、切房、设备转移和恢复使用事务、行锁、唯一约束及幂等键。账号的活动指针全局唯一，房间占用按“不同账号 + 活动中/断线保留”计数，第 12 个不同账号可占额，第 13 个被拒绝。
- `activities.activity_generation` 标识活动生命周期代次，`activity_members.member_generation` 标识成员资格代次；以下合称“代次”。加入、退出、切房、失权、结束和删除使相应旧代次失效。
- `network_policy_revisions` 保存运营策略版本；活动授权快照记录 `policy_revision` 和允许路径。策略变化产生新修订并写入 Outbox，客户端不能自行提高权限或选择被禁路径。
- 权威变更与待投递事件在同一数据库事务内提交；Outbox 投递可重试，消费者按事件 ID 幂等。撤销事件的优先级高于普通状态同步。
- 客户端连接、恢复、Helper 请求及媒体/直连/中继接入均携带活动 ID、两类代次、设备 ID、租约 ID 和策略修订；任一不匹配即拒绝或丢弃。

### 4. 关键运行链路

**加入/切房/设备转移**：先锁定账号活动指针并校验旧活动，再校验目标房间、当前群籍、密码或管理员豁免、开放状态、12 人容量及目标地址；目标可预留后在事务内原子替换活动资格并增加代次。目标校验或预留失败且旧活动仍有效时不改变旧活动；旧活动已结束则保持未入房。提交后才签发两种独立凭据，任何迟到回调均须通过代次校验。

**断线恢复**：租约断线后保留 60 秒名额、设备归属和原 IP。保留期内仅同账号、同设备、同活动及有效代次可恢复；超时由租约清理任务释放。主动退出、切房、失权、结束或删除立即取消恢复，不等待 60 秒。

**重新加入与 IP**：重入不承诺原 IP。系统先查该账号在该房间的历史地址；只有地址在当前活动可用、无冲突且通过房间隔离和当前安全校验时才优先恢复，否则分配新地址。任何地址复用都创建新成员代次、设备租约、语音凭据和 WireGuard 会话，不继承旧密钥、旧连接或旧访问权限。

**网络路径建立**：控制面签发绑定当前策略修订的短时网络票据。直连限定模式只下发直连候选与 Peer，失败后返回 `DIRECT_UNAVAILABLE`；直连优先模式在直连超时或失败后，只有票据明确允许时才可回退中继；强制中继模式不发起直连。UI 只显示连接、受限、失败和可重试状态，不提供路径选择。

## 技术选型及依据

| 层次 | 架构决定或候选 | 依据与限制 |
| --- | --- | --- |
| 客户端核心 | Rust，独立领域状态机和连接编排 | 符合团队能力和 REQ-028；页面不直接处理授权、密钥、路由或系统命令。核心与 UI、媒体、Helper 之间使用版本化契约。 |
| 桌面壳与 UI | Tauri 候选容器，增量复用现有 HTML/CSS/JS 和视觉组件 | 避免为语言偏好重做已批准 UI；Tauri 的系统 WebView 差异、真实音频、三平台签名更新尚未验证。Electron + React 仅作对照候选，不是默认回退。 |
| 语音 | 自托管 WebRTC SFU，LiveKit 首选候选；媒体访问经 Voice Adapter 隔离 | 复用成熟 SFU 的发布/订阅、房间和重连能力，不自研 SFU。具体 Rust/JS/原生 SDK 组合须验证设备、AEC/降噪、多人叠音、CPU、弱网和许可证；SFU 可接触媒体明文，不宣称语音端到端加密。 |
| 客户端音频处理 | 成熟 WebRTC Audio Processing 或等价库的 Rust 绑定候选 | 客户端优先完成 AEC、降噪、AGC/限幅和 VAD/静音不传输，不重写成熟算法；算法、默认值和资源开销由真实设备 A/B 测试决定。 |
| 控制面 | Java 21+、Spring Boot 模块化单体、Spring MVC、Maven Wrapper，暂保留为工作基线 | 原服务端决定不因客户端 Rust 评估自动变更；显式事务适合复杂权限与状态机。2 GB 下单机资源是硬性入口风险，P0/P1 不达标须提交独立 ADR，不得靠 swap 或未授权扩容掩盖。 |
| 持久化 | PostgreSQL + MyBatis + Spring 事务 + 显式 SQL | MyBatis Mapper/XML 直接表达行锁、条件更新、代次校验和 Outbox 同事务写入；唯一约束、部分索引和外键由 PostgreSQL 兜底。关键并发路径不依赖自动生成 SQL 或隐式 ORM 行为。 |
| 数据库迁移 | Flyway + 单向版本化 SQL | 表结构、约束和索引通过可审计迁移管理；MyBatis 不负责 DDL 版本。发布前备份并验证迁移与回滚预案，不在已执行生产迁移上原地改历史脚本。 |
| 客户端 API | REST + WebSocket | REST 承担命令和查询；WebSocket 承担状态、消息、策略和撤销通知。命令结果以 REST/数据库为准，WebSocket 重连后按游标补同步。 |
| 虚拟网络 | Rust Network Helper + 候选 Rust WireGuard 实现 + 真实 TUN | Rust 优先匹配团队能力并隔离管理员/root 权限。候选库必须审查维护状态、安全、许可证及自定义传输能力；三平台 TUN、路由、权限、直连和崩溃清理仍需 PoC，不默认引入 Go。 |
| 游戏转发 | 独立 Rust Relay，按运营策略启用 | 与控制面进程和资源隔离，统一执行房间隔离和撤销；不持有成员私钥、不创建明文转发网卡。吞吐、内存和撤销时延必须实测。 |
| 直连与中继传输 | 版本化 Rust transport trait；UDP WireGuard 直连，QUIC Datagram 中继首选候选 | 同一授权/代次语义覆盖两种路径。QUIC 库与 WireGuard 传输组合未验证；不可用时评估其他 UDP 封装或 TLS 流及队头阻塞，不预先宣称成功。 |
| 安装与更新 | Tauri bundler/updater 候选；平台签名安装包 | 候选 Windows NSIS/MSI、macOS DMG/ZIP、Linux AppImage/deb，最终目标由 SP-06 确定。macOS 更新依赖签名；Helper 随签名包安装、升级、回滚和卸载，三平台均须实测。 |
| 服务间投递 | PostgreSQL Transactional Outbox + 幂等消费者 | 避免“数据库已撤销但媒体/中继未收到事件”的双写不一致；首版由控制面内后台任务投递，无需独立消息集群。 |
| 设计资产 | 保留已批准原型、UI、组件与视觉风格 | 仅通过适配层替换模拟数据和定时器；不复制 `domain.js` 的可信授权、故障注入或模拟设备逻辑到正式产品。 |

### 客户端候选对比与定案门槛

| 方案 | 团队维护与资产复用 | 主要风险 | 当前结论 |
| --- | --- | --- | --- |
| Tauri + Rust 核心 + 现有 UI | Rust 主逻辑统一，UI 改动最小，候选包体和内存较低 | 系统 WebView 差异；音频设备/DSP、Helper 签名安装和更新组合未验证 | 首选垂直切片；SP-03/06/10 全部满足后才锁定 |
| Electron + Rust 核心 + 现有 UI | Chromium 媒体路径较成熟，仍可复用 UI | 包体/内存和双运行时 IPC；仍须接入 Rust 网络与真实 DSP | 对照方案，仅在 Tauri 关键项失败且收益有证据时提交替代 ADR |
| 原生 Rust UI | 本地逻辑一致 | 重做已批准 UI、可访问性和三平台工作量最大 | 首版不采用 |
| Go Helper | wireguard-go 生态可作参考 | 团队相对不熟悉，增加第二语言和发布链 | 非默认；确有不可替代必要性时按 AC-043 单独说明成本并评审 |

最小定案证据为：三平台构建与签名安装/更新、真实音频采集播放与处理、真实 TUN/TCP/UDP、直连和中继、撤销清理、包体/CPU/RSS、依赖许可证及团队可维护性记录。任何一项未执行都保持“候选/未验证”。

### 官方技术依据

- Rust：<https://doc.rust-lang.org/book/>
- Tauri 安全与更新：<https://v2.tauri.app/security/>、<https://v2.tauri.app/plugin/updater/>
- Java 21 虚拟线程：<https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html>
- Spring Boot：<https://docs.spring.io/spring-boot/>
- MyBatis：<https://mybatis.org/mybatis-3/>、<https://mybatis.org/spring-boot-starter/mybatis-spring-boot-autoconfigure/>
- Flyway：<https://documentation.red-gate.com/fd/>
- LiveKit 自托管 WebRTC SFU：<https://docs.livekit.io/intro/about/>
- LiveKit 客户端 SDK：<https://docs.livekit.io/reference/client-sdk-js/>
- 自托管令牌撤销限制：<https://docs.livekit.io/frontends/reference/tokens-grants/>
- LiveKit RemoveParticipant 等管理 API：<https://docs.livekit.io/intro/basics/rooms-participants-tracks/participants/>
- WebRTC 音频处理：<https://webrtc.googlesource.com/src/+/refs/heads/main/modules/audio_processing/>
- BoringTun 候选参考：<https://github.com/cloudflare/boringtun>
- PostgreSQL 行锁：<https://www.postgresql.org/docs/current/explicit-locking.html>

依赖具体版本在开发规划阶段根据 Cargo/npm/Maven 锁文件和兼容性验证固定；候选库还需记录维护状态、安全公告与许可证，本文不虚构已验证版本。

## 模块划分与职责

### 1. 桌面端

| 模块 | 职责 | 明确不负责 |
| --- | --- | --- |
| Tauri Shell（候选） | 创建受限 WebView 窗口、最小能力白名单、签名校验与更新、调用 Rust 核心 | 不直接执行任意系统命令，不直接创建 TUN，不把管理员/root 权限授予 UI |
| 现有产品 UI + UI Adapter | 账号、好友、群组、房间、群文字消息、成员/IP、权限和真实状态展示；把已批准 Command/Event 映射到界面 | 不自行判定授权，不伪造在线/连接/兼容状态，不复用原型模拟域作为生产授权 |
| Rust Client Core | 会话、活动代次、命令幂等、连接编排、统一错误、状态快照、操作系统安全存储和更新协调 | 不在 UI 线程执行阻塞网络或特权操作，不以本地状态覆盖服务端权威结果 |
| Voice Controller + Voice Adapter | 获取语音凭据、候选 SFU 连接、独立重试、参与者/说话状态、静音与接收开关 | 不控制虚拟网络生命周期，不将本地静音或 SDK 已连接当作服务端授权和音质通过 |
| Audio Device & Processing | 输入输出选择、设备测试、总音量、成员音量、AEC/降噪/AGC/VAD、权限拒绝/拔出/切换提示 | 不修改其他成员的本地音量，不在设备或处理失败时停止虚拟网络，不用 SDK 名称替代音质结果 |
| Network Controller | 获取网络凭据、生成/保护本机密钥、应用运营策略、编排直连/回退/中继、调用 Helper、独立重试 | 不依赖语音成功，不提供普通用户路径开关，不设置默认路由，不转发物理 LAN |
| Local Helper Client | 使用固定协议调用 Helper，校验响应、代次、策略修订与事件，处理超时/断连和取消 | 不提供任意路由、任意命令或任意文件写接口，不发送秘密到普通日志 |
| Rust Network Helper | 创建和销毁 TUN、配置仅活动网段路由、运行候选 Rust WireGuard、建立直连或 Relay 会话、应用成员 Peer、上报真实错误 | 不决定群/房间权限或运营策略，不持久保存服务端刷新令牌，不接受未白名单请求 |

### 2. Java 控制面

控制面按业务模块组织 Spring Boot 单体，模块间通过明确 Java 接口和领域事件协作，禁止跨模块直接修改他人数据。事务边界放在应用服务层，并发关键路径使用 MyBatis 执行显式 SQL；REST、WebSocket、Outbox、SFU 管理和 Relay 管理客户端均通过接口隔离，以便候选替换不改变业务语义。

持久化按以下边界实现：

- 每个业务模块只调用自己的 Mapper 接口；加入、切房、设备转移、撤销等应用服务使用 Spring `@Transactional` 管理同一数据库事务，业务变更与 Outbox 写入不得跨事务。
- 关键并发 SQL 放在可审查的 MyBatis XML 中，显式使用 `SELECT ... FOR UPDATE`、统一锁顺序、带代次条件的 `UPDATE`、唯一冲突转换和幂等响应查询；不使用自动生成 SQL 代替这些路径。
- PostgreSQL 唯一约束、部分索引、外键和检查约束是最终一致性兜底。12 人容量计数与成员插入、全局单活动指针替换、活跃 IP 分配必须在相同锁/事务范围内执行。
- Mapper 参数统一使用 `#{}` 绑定；`${}` 只允许服务端白名单中的列名或排序方向，禁止拼接用户输入。时间映射为 UTC，ID 保持字符串契约，数据库错误转换为稳定领域错误且不泄露 SQL/秘密。
- Flyway 管理单向版本化 DDL、约束和索引；应用启动校验迁移版本。迁移回滚通过备份恢复或前向补偿预案处理，不假定 MyBatis 能回滚 DDL。
- 持久化测试使用真实 PostgreSQL 及相同迁移脚本，覆盖第 13 人拒绝、并发切房/设备争抢、IP 冲突、重复幂等键、事务回滚和 Outbox 原子性；Mock Mapper 结果不能替代这些证据。

虚拟线程只用于阻塞式 I/O 的并发简化，不改变数据库锁、代次或授权语义；连接池、事务和 ThreadLocal 等兼容性必须经验证，不能因使用虚拟线程而取消背压、超时或资源上限。P0/P1 须分别测量控制面和整机资源，JVM 参数不得挤占 SFU、PostgreSQL、Relay 和操作系统余量。

| 模块 | 职责与关键规则 |
| --- | --- |
| Identity & Session | 邮箱注册验证、登录/退出、找回密码、会话轮换、账号失效；未验证账号仅开放必要账号接口 |
| Social | 账号搜索、好友申请/接受/拒绝/删除、在线状态；好友关系不授予群或房间权限，不实现私聊 |
| Group & Invitation | 群资料、成员、邀请、角色和解散；邀请接受幂等，邀请人失群籍时使待接受邀请失效，已接受成员不随之退出 |
| Ownership | 群主转让接受流程和人工恢复申请；完成前原群主不变，证据不足或争议不自动继任 |
| Room Administration | 持久房间 CRUD、密码、开放状态、房主、活动开始/结束；房主失群籍或账号失效时由群主接管 |
| Activity Coordinator | 加入、切房、设备转移、退出、60 秒保留、12 人容量、代次和唯一活动资格；采用统一锁顺序避免并发死锁与双活动 |
| Address & Lease | `/27` 地址分配、历史 IP、冲突检测、活跃地址唯一、租约心跳/过期及重入优先恢复 |
| Voice Grant | 校验当前资格后签发房间限定、短 TTL 的 SFU token；失权后不再续签 |
| Network Grant | 接收本机 WireGuard 公钥，签发一次性短时网络接入票据及活动成员/地址/候选路径描述；不接收或保存成员私钥 |
| Network Policy | 仅受控运营配置接口维护全局 `DIRECT_ONLY / DIRECT_WITH_RELAY_FALLBACK / RELAY_ONLY` 及修订；普通用户、群主和群管理员无写权限 |
| Path Coordination | 为当前成员分发授权 Peer、直连候选、Relay 票据和策略修订；处理路径迁移、回退与旧路径撤销，不依赖客户端自愿遵守 |
| Revocation Coordinator | 消费权威撤销事件，关闭同步、移除 SFU 参与者、更新 Relay 拒绝集合、通知授权 Peer 撤销、取消租约；接口层同步执行当前资格校验 |
| Message | 群文字历史消息、作者删除、管理员代删及 tombstone；群解散后产品内不可读 |
| Audit | 只追加管理记录，记录操作者、目标、时间、操作、结果和关联 ID；不记录秘密，运营策略变更另留受控审计 |
| Event & Sync | Outbox、WebSocket 事件、断线游标和全量校正；旧代次和旧策略修订事件对客户端无效 |
| Capacity & Admission | 分离在线、在房、发言和中继指标；依据实测阈值实施背压/受控拒绝并返回通道级错误，不自动降低 12 人房间需求 |
| Compatibility & Diagnostics | 返回候选/已验证支持矩阵及脱敏诊断标识；未执行项目保持未验证 |

### 3. SFU、直连与 Rust Relay

Relay 是独立 Rust 数据面进程，管理连接、数据报、背压和资源上限，不属于业务微服务拆分。控制面只通过版本化 Relay 管理协议下发授权、策略和撤销；Relay 不直接修改业务表。协议包含版本、活动/成员代次、租约、策略修订、目标映射和过期时间。

- SFU 房间名使用不可猜测的活动映射，不使用群名称；参与者身份绑定账号、设备和成员代次。控制面仅通过服务端管理 API 创建/结束房间和移除参与者。
- 自托管 SFU 的参与者移除不等同于旧 token 全面失效，因此撤销必须组合“移除参与者 + 短 TTL + 失权后绝不重新签发 + 活动/成员代次校验”。刷新凭据接口每次重新查权威资格。
- 直连授权和控制信令经控制面签发；Peer 只接受当前公钥、代次和租约对应的握手。直连降低服务器游戏流量但不消除撤销风险，失权时由服务端通知仍在线的授权 Peer 删除旧 Peer，并测量对继续发送旧端点的实际强制边界。
- Relay 会话由控制面签发的一次性票据激活，绑定 `activity_id + activity_generation + member_generation + device_id + lease_id + policy_revision + 本端虚拟 IP`。
- 外层转发头包含目标虚拟 IP/目标成员映射；Relay 验证来源租约、目标租约、活动、代次和策略后按目标转发。目标映射必须由服务端下发并与当前成员匹配，客户端不能任意指定跨活动目标。
- WireGuard 私钥仅在本机生成和保存，成员之间进行端到端加密；Relay 看到外层路由元数据和 WireGuard 密文，不持有解密密钥，也不读取内层明文来选择目标。
- Relay 不配置系统 IP forwarding，不桥接物理网卡，不提供默认路由或互联网出口。撤销时同时拒绝来源和目标相关转发并关闭活动会话；直连和中继都随后移除 Peer，最后一个有效路径消失后清理 TUN。
- 游戏中继策略不影响语音 SFU 路径。禁用游戏中继不表示语音直连，容量报表分别记录两类流量。

## 核心数据模型

所有表使用不可猜测 ID、UTC 时间和必要的外键/检查约束；下列为核心字段而非最终 DDL。软删除状态不等于物理删除。所有唯一约束、索引和锁在数据库迁移中实现并测试。

| 表/对象 | 核心字段与约束 |
| --- | --- |
| `users` | `id, email_normalized UNIQUE, status(unverified/active/disabled), created_at`；邮箱规范化规则固定，账号失效是授权终止状态 |
| `credentials` | `user_id, password_hash, email_verified_at, verification/reset_secret_hash, expires_at, attempts`；只存密码和验证码派生哈希，不存明文秘密 |
| `auth_sessions` | `id, user_id, device_id, refresh_token_hash, expires_at, revoked_at, generation`；刷新令牌轮换并检测重放，登出/失权按范围撤销 |
| `devices` | `id, user_id, platform, arch, public_metadata, created_at, last_seen_at`；设备标识不等于房间活动授权 |
| `friendships` / `friend_requests` | 好友对使用规范化账号顺序唯一；申请具有 pending/accepted/rejected/cancelled 状态和幂等约束 |
| `groups` | `id, name, avatar, description, owner_user_id, status(active/dissolved), dissolved_at`；解散后所有产品查询拒绝，不声称立即物理删除 |
| `group_members` | `group_id, user_id, role(owner/admin/member), joined_at, left_at, status`；当前有效成员唯一，重入默认普通成员 |
| `invitations` | `id, group_id, inviter_id, invitee_id/code_hash, status, accepted_at, revoked_at`；默认不过期，接受时复查邀请人及群状态 |
| `owner_transfers` | `id, group_id, from_user, to_user, status, created_at, completed_at`；同群最多一个有效转让，目标须为当前成员 |
| `owner_recovery_requests` | `id, group_id, requester, evidence_ref, status, reviewer, decision_reason`；仅人工有证据处理，争议时保持归属 |
| `rooms` | `id, group_id, name, description, game_name, password_hash, owner_user_id, open_state, lifecycle, activity_id`；仅属一群，删除为终态 |
| `activities` | `id, room_id, activity_generation, state(active/ending/ended), started_at, ended_at`；每房最多一个当前活动，结束不删除房间 |
| `activity_members` | `activity_id, user_id, device_id, member_generation, state(active/grace/revoked/left), joined_at, grace_expires_at`；当前活动中账号唯一，active/grace 均占 12 人名额 |
| `account_activity` | `user_id UNIQUE, activity_id, device_id, member_generation, operation_id`；全局单活动指针，切换只能在事务内替换 |
| `connection_leases` | `id, activity_id, user_id, device_id, generations, policy_revision, voice_state, network_state, expires_at, revoked_at`；两类连接状态分开记录，撤销立即失效 |
| `network_policy_revisions` | `revision UNIQUE, mode(direct_only/direct_with_relay_fallback/relay_only), existing_connection_action, effective_at, actor_ref, created_at`；全局首版单 active 修订，写操作仅受控运营通道 |
| `activity_network_grants` | `id, activity_id, member_generations, policy_revision, allowed_paths, expires_at, revoked_at`；授权快照不允许客户端扩大路径 |
| `network_peer_sessions` | `id, grant_id, peer_device_id, public_key_ref, path(direct/relay), state, established_at, closed_at`；只存公钥和路径状态，不存私钥，撤销与代次联动 |
| `ip_allocations` | `activity_id, user_id, ipv4, allocated_at, released_at`；`(activity_id, ipv4)` 活跃唯一，用户当前地址唯一 |
| `ip_history` | `room_id, user_id, last_ipv4, last_activity_id, updated_at`；仅作为重入偏好，不构成地址或权限保留 |
| `messages` | `id, group_id, author_id, body, created_at, deleted_at, deleted_by, delete_kind`；群文字消息，删除为 tombstone，列表展示删除提示 |
| `audit_logs` | `id, group_id, actor_id, target_type/id, action, result, occurred_at, correlation_id`；只追加，产品无更新/删除/清空接口，秘密字段禁止写入 |
| `outbox_events` | `event_id UNIQUE, aggregate_id, type, payload_minimal, generation, occurred_at, delivered_at, attempts`；与业务事务同提交，消费者按 event_id 幂等 |
| `idempotency_keys` | `actor/device, operation, key, request_hash, response_ref, expires_at` 唯一；同键不同请求体返回冲突 |

### 地址与容量规则

1. 每个活动建议使用独立 IPv4 `/27`，可覆盖 12 名成员及中继/保留地址；具体可用地址数、保留地址、MTU 和冲突处理由 SP-01/SP-02 验证后锁定。
2. 每个客户端只配置自身虚拟 IP 和该活动 `/27` 路由，不修改系统默认网关、DNS 或物理 LAN 路由。
3. 活跃分配由数据库唯一约束兜底；直连 Peer 与 Relay 都检查来源/目标、当前分配、代次和策略，防止伪造或跨房通信。
4. 同活动有效恢复直接保持原分配；新加入/重入先锁地址空间，尝试历史地址，冲突则选择空闲地址。历史 IP 不跨房间授予连通性或身份。
5. 第 12 个不同账号可占额，第 13 个被拒绝；并发加入必须在同一事务锁范围内完成计数与插入。
6. 12 人是单房账号配额，不是服务器容量结论。服务器另行记录在线账号、在房账号、实际发言者、直连 Peer、中继会话和各方向字节数。

### 删除与持久化

- 消息作者可删除本人消息，群主/管理员可删除本群任意消息；事务内设置 tombstone，并为管理员代删追加审计。原内容的数据库保留/清理策略在生产数据政策中另行确定。
- 群解散使群、消息、邀请、房间和管理记录在产品内不可查看或恢复，同时撤销全部活动；物理删除、备份保留与法定留存不在首版自动承诺。
- 管理记录仅当前群主/管理员按群只读。数据库角色和迁移流程限制应用更新/删除；后台防篡改和归档是生产准备事项，不提前宣称不可篡改。

## 接口契约

### 1. 通用约定

- 基础路径 `/api/v1`；写请求使用 JSON 和 `Idempotency-Key`，时间使用 RFC 3339 UTC，ID 使用字符串。
- 成功响应包含 `data` 和可选 `correlation_id`；错误包含稳定 `code`、可理解 `message`、是否可重试及 `correlation_id`，不返回堆栈或秘密。
- 常用错误：`401 AUTH_REQUIRED`、`403 CURRENT_GRANT_REQUIRED`、`404 RESOURCE_NOT_AVAILABLE`、`409 GENERATION_STALE / CAPACITY_FULL / ROOM_PAUSED / IP_CONFLICT / OPERATION_CONFLICT / POLICY_STALE`、`410 ACTIVITY_ENDED`、`422 VALIDATION_FAILED`、`429 RATE_LIMITED`、`503 VOICE_CAPACITY_LIMITED / NETWORK_CAPACITY_LIMITED / DIRECT_UNAVAILABLE / RELAY_DISABLED`。
- 为避免资源枚举，跨群/无资格资源可按安全策略返回 404；容量、暂停和密码错误仅对已通过基础群籍校验者返回。资源受限不暴露服务器规格，不错误显示为“房间 12 人已满”。
- 访问令牌短时有效，桌面刷新令牌存操作系统安全存储；所有资格敏感操作即使令牌有效也重新查当前状态。
- 活动命令响应返回 `activity_id, activity_generation, member_generation, device_id, lease_id, policy_revision, voice_state, network_state`；客户端不能用本地推测替代服务端结果。

### 2. HTTP API

| 领域 | 方法与路径 | 契约要点 |
| --- | --- | --- |
| 注册/登录 | `POST /auth/register`、`POST /auth/verify-email`、`POST /auth/login`、`POST /auth/refresh`、`POST /auth/logout` | 真实/受控邮件验证；登录允许多端，但不自动获得房间活动；刷新令牌轮换，登出撤销当前会话 |
| 找回 | `POST /auth/password/forgot`、`POST /auth/password/reset` | 只向已验证邮箱发起；响应防账号枚举，重置后按设计撤销旧会话 |
| 用户/好友 | `GET /users/search`、`GET /friends`、`POST /friend-requests`、`POST /friend-requests/{id}/accept|reject`、`DELETE /friends/{user_id}` | 仅已验证账号；好友不改变群权限；重复命令幂等或明确冲突 |
| 群组 | `POST /groups`、`GET/PATCH /groups/{id}`、`POST /groups/{id}/dissolve`、`GET /groups/{id}/members` | 修改/解散执行时校验群主或对应管理权限；解散同事务撤销全部活动资格 |
| 邀请 | `POST /groups/{id}/invitations`、`POST /invitations/{id}/accept`、`DELETE /groups/{id}/invitations/{id}` | 当前成员可邀请；仅群主/管理员撤销；接受时复查邀请人、群、被邀请者和重复成员 |
| 成员/权限 | `PATCH /groups/{id}/members/{user_id}`、`DELETE /groups/{id}/members/{user_id}`、`POST /groups/{id}/leave` | 角色矩阵由服务端执行；群主离开前须完成转让或解散；移出触发撤销 |
| 群主治理 | `POST /groups/{id}/owner-transfers`、`POST /owner-transfers/{id}/accept|cancel`、`POST /groups/{id}/owner-recovery-requests` | 接受前原群主不变；人工恢复无自动继任接口 |
| 房间 | `POST /groups/{id}/rooms`、`GET/PATCH/DELETE /rooms/{id}`、`POST /rooms/{id}/open|pause` | 配置持久化；密码修改不清退现有成员；暂停阻止所有新加入，删除终止全部会话和恢复 |
| 活动管理 | `POST /rooms/{id}/activities`、`POST /activities/{id}/end` | 开始/结束增加活动代次；结束清退成员、终止语音与网络但保留房间配置和暂停状态 |
| 加入/切换 | `POST /room-joins` | 请求含目标房间、设备、可选密码和幂等键；事务内执行目标预检、地址预留及原子切换；响应含新代次和独立连接描述 |
| 恢复/退出 | `POST /activities/{id}/resume`、`POST /activities/{id}/leave` | 恢复校验同账号/设备/代次/60 秒租约及当前策略；退出立即撤销旧恢复，重复退出幂等 |
| 活动视图 | `GET /activities/{id}`、`GET /activities/{id}/members` | 仅当前有效成员；返回真实成员、自己的/成员虚拟 IP 和独立状态，不返回任何秘密私钥 |
| 语音凭据 | `POST /activities/{id}/voice-grants` | 每次重新校验资格；返回 SFU URL、房间限定短 TTL token、代次；失权后拒绝刷新 |
| 网络凭据 | `POST /activities/{id}/network-grants` | 请求含设备、本机 WireGuard 公钥和代次；返回一次性票据、允许路径、策略修订、直连候选/Relay 地址、自身 IP、活动网段及成员 Peer；私钥不上传 |
| 运营网络策略 | 受控管理通道 `GET/PUT /ops/network-policy`、`POST /ops/network-policy/{revision}/rollback` | 与普通 `/api/v1` 用户接口和凭据隔离；校验独立运营身份、修订和预期旧值，变更/回滚写 Outbox 与审计；生产启用另行授权 |
| 消息 | `GET/POST /groups/{id}/messages`、`DELETE /messages/{id}` | 群文字消息，分页游标稳定；作者或群管理员按规则删除；失权后停止后续读取和同步 |
| 管理记录 | `GET /groups/{id}/audit-logs` | 仅当前群主/管理员只读分页；无产品内写改删接口 |
| 支持矩阵 | `GET /compatibility-matrix` | 分开返回候选、已验证和未验证项及验证证据引用，不将游戏互通与系统互通混同 |

`|accept|reject` 等表示对应独立动作端点，实际路由在 OpenAPI 中展开，不使用竖线作为字面 URL。

### 3. WebSocket 同步

- 连接：`GET /api/v1/ws?cursor=...`，使用短期访问凭据；服务端每条连接持续校验会话和同步范围。
- 事件信封：`event_id, group_id?, activity_id?, activity_generation?, member_generation?, policy_revision?, sequence, type, occurred_at, payload`。
- 事件类型至少包括 `message.created/deleted`、`member.changed`、`room.changed`、`activity.changed`、`network_policy.changed`、`network_path.changed`、`lease.revoked`、`voice.revoked`、`network.revoked`、`resync.required`。
- 客户端按活动代次、策略修订和 sequence 去重；旧代次/旧策略事件丢弃。游标不可用或服务端无法证明连续时执行 `resync.required`，重新拉取权威快照。
- WebSocket 断线不自动等于活动退出；60 秒租约由独立心跳和连接状态判定。失权后服务端关闭同步，重连仍须通过当前权限检查。
- 100 人在线压测固定心跳、状态变更、消息读写、重连和补同步频率；不能以空连接或无事件同步冒充业务在线容量。

### 4. UI 与 Rust Client Core 契约

Tauri Command/Event 是首选候选，序列化 schema 带 `protocol_version, request_id, activity_id?, member_generation?, device_id?`。命令异步返回明确回执；核心在应用任何迟到结果前重新核对当前活动、设备、请求和代次。

| 命令/事件 | 契约要点 |
| --- | --- |
| `session.login/logout/restore`、`social.*`、`group.*`、`room.*` | UI 只提交用户输入；核心调用服务端并以权威快照更新，秘密不进入事件日志 |
| `activity.join/leave/switch/resume`、`channel.retry` | 携带目标、设备、请求和幂等键；语音/网络独立状态，不因一个失败取消另一个 |
| `voice.set_mute/receive/volume`、`audio.select/test/start/stop`、`audio.set_processing` | 返回设备权限、处理可用性、测试和失败状态；UI 不直接访问麦克风或声明 DSP 成功 |
| `message.send/delete` | 只支持群文字；返回发送中/权威成功/失败及 tombstone，不添加附件队列 |
| `state.snapshot/changed`、`channel.failed/recovered` | 状态包含可信原因、可重试性、当前策略修订的公开结果，不暴露秘密或基础设施细节 |

普通 UI 无 `network.set_path` 或运营策略命令；复核工具、模拟计时器和故障注入不编译进正式产品。

### 5. 本地 Network Helper IPC

传输使用 Windows 命名管道、macOS/Linux 仅本机受限 Unix Socket；对端进程/用户校验、文件权限和协议版本检查全部通过后才可用。消息采用长度受限的版本化 JSON 或等价 schema，不使用 shell 命令。

| 方法/事件 | 输入与结果 | 安全限制 |
| --- | --- | --- |
| `helper.get_capabilities` | 返回 Helper/协议版本、TUN/直连/中继能力、权限和平台错误 | 不将“可调用”显示为“三平台已验证” |
| `network.start` | 一次性票据、活动/代次/租约、策略修订、允许路径、自身 IP、`/27`、直连候选/Relay、成员 Peer、本机私钥的安全内存引用；返回真实启动状态 | 校验 schema、长度、过期时间、网段和目标白名单；只允许活动网段和票据路径，不接受默认路由/自定义命令 |
| `network.update_peers` | 当前代次、策略修订及新增/移除 Peer、候选地址或 Relay 映射 | 仅接受控制面已授权成员和允许路径，旧代次/旧策略拒绝 |
| `network.migrate_path` | 当前授权、源/目标路径和 request_id | 仅允许策略规定迁移；禁止回退不能转 Relay，强制中继不能直连；迟到结果无效 |
| `network.stop` | 活动、代次、租约和 request_id | 幂等停止并清理 TUN、路由、Peer、直连和 Relay；清理失败返回可诊断错误 |
| `network.status` / `network.retry` | 返回未连接/连接中/已连接/策略受限/断开/重试中/失败及错误码 | 状态来自 Helper 和实际传输，不由 UI 假定，不提供终端路径选择 |
| `network.event` | 上报地址冲突、权限不足、TUN/路由失败、直连失败、Relay 断开、策略/代次失效 | 事件不包含私钥、令牌或完整敏感配置 |

Helper 崩溃或 IPC 断开时，UI 将网络标为失败/未知并可独立重试，不中断语音；重启 Helper 必须重新验证当前票据、代次和策略，不能自动复活已撤销会话。

## 安全与权限设计

### 1. 客户端与服务端基线

- Tauri 候选容器只开放最小白名单能力，WebView 不加载远程产品内容；校验 Command 来源、参数、导航、新窗口和权限请求，使用严格 CSP。若改用 Electron 对照方案，也必须启用 `contextIsolation`/sandbox、关闭 Node integration 并只暴露最小 preload API。
- 密码使用 Argon2id 等适配风险的慢哈希及独立盐；房间密码独立哈希，不写入日志。验证码和找回令牌短时、限次、只存哈希并限流。
- 外部流量全程 TLS；服务间 API 使用独立服务凭据和最小权限。语音 token、网络票据、刷新令牌、SFU/Relay 管理密钥和运营策略凭据分权保管，不能共用。
- 日志默认结构化脱敏，禁止记录密码、验证码、完整令牌、私钥和房间密码；网络诊断只保留必要 IP/设备/错误关联信息。生产密钥管理和日志保留期待专项配置。
- 登录、验证码、找回、邀请、加入、信令、凭据签发和运营策略接口均限流；错误不泄露不必要账号存在性或跨群资源信息。

### 2. 授权矩阵

| 操作 | 普通成员 | 房间房主 | 群管理员 | 群主 |
| --- | --- | --- | --- | --- |
| 邀请当前好友/用户 | 可以 | 按其群角色 | 可以 | 可以 |
| 撤销待接受邀请 | 不可以 | 不可以 | 可以 | 可以 |
| 修改群资料/管理普通成员 | 不可以 | 不可以 | 按矩阵可以，不能管理群主或同级 | 可以 |
| 查看只读管理记录 | 不可以 | 不可以 | 可以 | 可以 |
| 创建房间 | 仅按已批准群管理权限 | 不额外获得创建权 | 可以 | 可以 |
| 管理本人拥有的房间 | 若被指定为房主则可 | 可以 | 可以管理本群房间 | 可以 |
| 指定房主 | 不可以 | 不因此获得群权限 | 按群管理规则 | 可直接指定当前有效成员 |
| 转让/解散群 | 不可以 | 不可以 | 不可以 | 可以 |
| 管理员免房间密码 | 不适用 | 房主本人按当前规则 | 可以 | 可以 |
| 修改运营网络策略或选择连接路径 | 不可以 | 不可以 | 不可以 | 不可以 |
| 绕过 12 人、暂停、群籍、策略或账号状态 | 不可以 | 不可以 | 不可以 | 不可以 |

所有表格权限仍需执行时检查；房间权限不反向授予群权限，好友关系不进入该矩阵。运营策略写接口不在普通用户 API 和群管理界面中暴露。

### 3. 撤销与隔离

1. 权威事务先取消成员资格、增加代次、撤销租约并写入 Outbox；后续 REST、WebSocket、语音凭据和网络凭据立即因当前资格失败。
2. Revocation Coordinator 并行调用 SFU RemoveParticipant、Relay deny/disconnect、授权 Peer 撤销通知和 WebSocket close；失败按指数退避重试并报警，不能因一次投递失败把资格恢复。
3. Relay 每个数据报均使用内存中的当前租约/代次/策略/目标映射；收到撤销立即拒绝来源和目标通信。直连 Peer 只接受当前授权握手，收到撤销删除旧 Peer；服务端无法直接接触离线对端时，必须把这一限制写入威胁模型和实测结果，不能宣称仅靠客户端自愿断开即完成强制撤销。
4. SFU 使用短 TTL、参与者移除和禁止续签组合；客户端收到撤销后停止发布/订阅并清理音频，但服务端措施不依赖客户端配合。
5. 服务端确认失权后 5 秒内完成语音、网络和同步撤销是验收目标。Outbox 极端积压、媒体服务不可用、离线直连 Peer 等情况必须暴露失败和告警，不能虚报达成。
6. 不同活动使用独立 SFU 房间、直连授权集合、Relay 会话集合、地址空间和授权代次；即使虚拟 IP 数值相同，也不能跨活动转发。
7. Helper 只配置活动 `/27`；操作系统防火墙是否允许本机服务经该接口被访问由平台配置和用户授权决定，产品不宣称“仅限游戏流量”。

### 4. 密钥和地址生命周期

- 每台设备在本地生成 WireGuard 私钥，优先使用操作系统安全存储或受限文件；仅公钥上传。撤销/切换时删除旧 Peer，重入可轮换密钥并必须建立全新会话。
- 服务端、Relay 和日志不接触 WireGuard 私钥。SFU/Relay 管理密钥仅服务端持有，桌面端只获取最小范围短期凭据。
- IP 不是身份或权限。所有 Peer 更新与转发均同时校验账号、设备、活动、成员代次、租约和策略修订，不能仅凭曾拥有某 IP 获得访问。

## 部署及运行方案

### 1. 首版环境拓扑

- 首台 2 核/2 GB/Debian/5 Mbit/s 上候选部署一套 Java/Spring Boot 控制面、一套 PostgreSQL、一套单节点 SFU 和一套独立 Rust Relay；反向代理统一终止外部 TLS。该共置是待验证的资源约束，不是已证明可行的容量结论；首版不承诺高可用。
- Java 进程固定支持的运行时版本、堆上限和 CPU/内存限制；PostgreSQL、SFU、Relay、代理和操作系统也分别设置资源预算并保留峰值余量。禁止依赖开发机默认 JVM 参数、持续 swap 或未授权第二台服务器。
- 单节点 SFU 不引入 Redis；未来多节点媒体或 Relay 水平扩展需另做一致性、资源和容量设计，并获得运营方决定。
- 控制面与 PostgreSQL 仅通过受限内网/本机访问；仅公开必要 HTTPS、WebSocket、WebRTC、直连信令和 Relay 端口。具体端口、防火墙、NAT 类型、STUN/TURN/连通性在部署 PoC 验证。
- 生产部署、服务器购买、带宽升级、域名、证书、邮件账号和签名证书均需另行授权；当前不执行采购或生产部署。

### 2. 桌面发布

| 平台 | 候选安装/更新 | 权限与验证要求 |
| --- | --- | --- |
| Windows 10 22H2/11 x64 | Tauri NSIS/MSI 候选 + 签名 updater | 安装/升级 Helper 需要管理员授权，普通运行 UI 不提升；验证 WebView2 前置条件、TUN、路由、杀软兼容、卸载清理 |
| macOS 13+ Intel/Apple Silicon | DMG + ZIP；签名/公证后 updater | Helper 通过受限 LaunchDaemon/授权安装运行；自动更新需有效签名，WebView、utun 和权限流程待实测 |
| Ubuntu 22.04/24.04 x86_64 | AppImage/deb 候选 + 对应更新路径 | Helper 由受限 systemd 服务运行；WebKitGTK、桌面环境、安装授权、TUN 和更新差异记入支持矩阵 |

- 客户端与 Helper 必须版本兼容；安装器进行签名校验和原子升级，升级失败可回滚，Helper 不得因 UI 更新留下无权限孤儿进程。
- 卸载清理本机 TUN、路由、Helper 和按产品政策清理本地凭据；卸载不等于删除服务端账号、群或消息。
- 支持矩阵记录系统版本、架构、Linux 发行版/桌面环境、WebView 依赖、权限条件、客户端/Helper 版本及已测游戏版本。候选项不得标成通过。

### 3. 容量模型与验证方案

5 Mbit/s 按十进制约 625 kB/s，仅用于模型；实际出网、入网或共享口径须在 P0 核实。暂留建议 20% 出网余量后，约 4 Mbit/s 由业务同步、语音、游戏中继及协议开销共享；安装包和更新单独限速、计量，不默认存在外部 CDN。

语音 SFU 出网模型为 `Σ s × (n−1) × r_online`，其中 `n` 为房间听说成员、`s` 为实际发言者、`r_online` 是含 RTP/UDP/IP、加密、FEC、重传和 DTX 实测结果的每路线上码率。仅以 32 kbit/s Opus 载荷举例：12 人 1 人发言为 352 kbit/s，2 人发言为 704 kbit/s，12 人同时发言为 4.224 Mbit/s；这些不是实测值，也未包含游戏、同步和协议开销。

| 负载级别 | 固定场景与测量 | 通过结论边界 |
| --- | --- | --- |
| C0 环境基线 | 核实 Debian/地域/带宽方向；空载与固定业务负载下记录各进程 RSS/CPU、网络、文件句柄和数据库连接 | 只证明环境及测量方法有效 |
| C1 100 人在线 | 100 个已验证账号持续建议 60 分钟，固定心跳、在线变化、群消息读写、断线补同步；建议核心成功率≥99%、p95≤1 秒 | 只证明控制面在线容量，不证明语音或游戏中继 |
| C2 单房极限 | 12 人、多人及 12 人同时发言，游戏并行，直连和强制中继分别测试建议 60 分钟 | 给出单房语音/网络实际上限；超预算不得降低 12 人要求后宣称通过 |
| C3 100 人分房 | 8 个 12 人房和 1 个 4 人房，分别固定每房发言者、直连比例、代表游戏中继流量与更新开销 | 给出全站混合负载边界；不能从 C1 推断全功能 100 人 |
| C4 背压与降级 | 达到实测阈值时限制新语音/中继授权、限速更新并保留控制面和另一健康通道 | 验证受控拒绝、准确错误和恢复，不静默降低音质或绕过策略 |

游戏流量按每成员实际上下行、包率、重传、WireGuard/QUIC 开销和中继双向放大计量；直连只减少服务器 Relay 流量，不减少客户端带宽。测试保存负载脚本、原始指标、上下行抓包/计数、错误率、时延、CPU/RSS/OOM、GC 和媒体质量。结果若不足，提交“实测活动上限、增加带宽、调整媒体/中继部署”选项给运营方，不自动扩容或缩减目标。

### 4. 语音质量设计

- 采集后候选处理顺序为设备回声参考对齐 → AEC → 降噪 → 高通/必要滤波 → AGC 与限幅 → VAD/DTX → Opus 编码；播放侧处理成员独立音量、总音量与输出限幅。实际顺序和参数由 SDK 能力与 A/B 录音调整，避免重复开启客户端和 SDK 的同类处理。
- 能力可用时降噪默认开启；麦克风权限、设备采集、处理链和 SFU 连接分开报告。处理不可用不得阻止用户获知真实状态，也不得伪称降噪已生效。
- 真实设备覆盖安静、键盘、风扇、游戏外放和双人重叠发言，边运行代表游戏边通话，对降噪开关保留同步参考录音；建议安静关键词听辨≥95%、噪声≥90%，无持续吞字、金属音、削波或啸叫。
- 正常受控网络端到端口到耳 p95 建议≤200 ms；另测 1%/3% 丢包和 20/50 ms 抖动，记录清晰度、卡顿、延迟分布、客户端 CPU/RSS 和游戏资源竞争。门槛沿用已批准 AC-040，样本和听辨方法在测试计划中固定。
- 音质不达标时先定位采集、回声参考、处理链、抖动缓冲和编码，不以盲目低码率掩盖问题；12 人多人叠音和弱网结果与容量报告关联。

### 5. 运营策略与迁移语义

首版全局候选默认 `DIRECT_WITH_RELAY_FALLBACK`，但最终默认模式、代表 NAT 范围和是否启用 `RELAY_ONLY` 须运营方确认，当前不视为已选定生产策略。

1. `DIRECT_ONLY`：票据不含可用 Relay 授权；直连失败准确返回 `DIRECT_UNAVAILABLE`，禁止任何游戏载荷经 Relay。
2. `DIRECT_WITH_RELAY_FALLBACK`：先直连，只有当前票据允许且直连达到固定超时/失败条件才回退；回退建立后撤销旧直连授权，避免双路径重复流量。
3. `RELAY_ONLY`：只签发 Relay 路径，不尝试直连； Relay 不可用时真实失败，不私自回退直连。
4. 策略变更生成单调递增修订。新握手立即使用新修订；新策略禁止的已有路径立即停止并清理。仍被允许的路径可保留，但后续 Peer/票据刷新使用新修订；需要迁移时先建立新路径再原子切换，失败按新策略保持可用旧路径或明确断开。
5. 回滚同样生成新修订，不复活旧代次、旧票据或已撤销 Peer。迁移期间失权优先于路径建立，任何迟到直连/中继结果均不能恢复资格。
6. 运营策略仅控制游戏虚拟网络；语音 SFU 路径单独记录和验收。普通客户只看到可信连接结果、受限原因和重试能力。

### 6. 配置、观测与备份

- 配置通过环境变量/受限密钥存储注入，仓库不保存真实秘密。Flyway 数据库迁移单向版本化并在发布前备份；记录迁移版本、执行结果和前向补偿/恢复预案。
- 指标至少包括 API 错误、在线/在房/发言/直连/中继分档容量、活动占额、代次/策略冲突、Outbox 延迟、撤销耗时、JVM 堆/线程/GC 停顿、各进程 RSS/CPU、数据库连接池、SFU 参与者/音频质量、Relay 会话/吞吐/丢包/拒绝、客户端音频处理和 Helper 启动/路由错误。
- 日志使用 `correlation_id + activity_id + user/device 伪标识` 关联，不记录秘密；针对撤销失败、Relay/SFU 不可达、容量饱和和 Outbox 积压配置告警。
- PostgreSQL 备份、恢复演练、物理删除和保留政策在生产准备阶段批准。首版只承诺产品重启后的持久化，不将“有数据库”写成“已有灾难恢复”。
- 可用性目标：网络恢复且资格有效时，可用服务于 15 秒内自动恢复或明确失败并可手动重试；语音和网络分别计时，不要求同时成功。

## 技术风险和待验证事项

> 下表所有 SP 当前均为“未执行/待验证”。PoC 通过只证明对应原型条件，不替代三平台安装、12 人 60 分钟、100 人在线、游戏兼容、真实音质或安全验收。

| 标识 | 风险与待验证问题 | 最小验证与通过证据 | 失败处理 |
| --- | --- | --- | --- |
| SP-01 三平台 TUN/权限 | 候选 Rust WireGuard、自定义传输、真实 TUN、`/27` 路由及 Helper 权限在 Windows/macOS/Linux 未实测 | 各候选系统创建网卡，完成真实 TCP/UDP，确认默认路由/DNS/物理 LAN 未改变；记录管理员/root 授权、安装、卸载和崩溃清理 | 分平台修复 Helper/安装方式；未通过平台标未验证，不得缩减交付范围后仍宣称全平台 |
| SP-02 Rust Relay/隔离/撤销/IP | Rust Relay 外层目标映射与端到端 WireGuard、中继转发、12 地址规划、冲突、即时撤销和历史 IP 优先恢复未验证 | 两端及多成员经 Rust Relay 互通；跨房/伪造目标失败；Relay 无私钥；撤销后已有/新建转发失败；恢复与重入地址符合 REQ-015；记录吞吐、CPU/RSS 和时延 | 调整传输、转发索引、地址锁、背压和拒绝集合；QUIC 候选不可用时评估其他 UDP 封装或 TLS 流及队头阻塞 |
| SP-03 真实音频、设备与音质 | Tauri/Rust 核心、候选 SFU SDK 与音频处理链在真实设备、游戏并行和多人叠音下未验证 | 真实音频发布/收听；AEC/降噪 A/B 录音与听辨；静音、设备测试、音量、权限、拔出和切换；完成 AC-040 场景并记录延迟、CPU/RSS、弱网质量 | 修复设备/处理边界或替换具体 SDK；必要时比较 Electron 对照，但不能用模拟音频、SDK 名称或低码率替代验收 |
| SP-04 独立故障与恢复 | 语音/网络故障隔离、60 秒保留、15 秒恢复目标、迟到连接和双活动未验证 | 分别停止 SFU、Relay、Helper、直连和网络；另一项继续；并发切房/设备争抢仅一个活动；保留/超时和旧代次行为符合需求 | 修正状态机、心跳和事务；记录目标未达项，不以手动刷新掩盖失败 |
| SP-05 真实邮件 | 外部邮件账号、域名、投递、限流和找回链路未接入 | 在受控真实邮箱完成注册验证和找回，检查过期、错误次数、防枚举与日志脱敏；保存脱敏投递证据 | 邮件保持待接入/未验证；不能用自动显示验证码或模拟邮件宣称通过 |
| SP-06 发布与更新 | Tauri 候选安装包、Helper 签名安装、macOS 签名公证和各平台更新未验证 | 每个标为通过的平台实际安装、更新、卸载，更新后数据与 Helper 兼容，权限和失败提示有证据；记录包体、WebView 依赖与许可证 | 缺少证书或设备则标待接入/未验证；容器关键能力失败时提交对照 ADR，不发布伪造通过矩阵 |
| SP-07 容量与撤销时延 | 12 人 60 分钟、100 人在线/分房、5 秒撤销、15 秒恢复、Relay 带宽、整机 CPU/RSS 和 SFU 资源尚无数据 | 按 C0～C4 在目标 2 核/2 GB/Debian/5 Mbit/s 运行真实语音、虚拟网络、游戏/测试流量和业务负载，保存资源、媒体、丢包、上下行、时延和撤销证据 | 依据数据调参并提交实测上限和资源选项；未达目标如实报告，不自动扩容、降配或修改证据 |
| SP-08 Relay 元数据与媒体信任 | Relay 可见外层目标元数据，SFU 可接触媒体明文；直连暴露对端网络地址 | 威胁模型、配置检查和抓包证明无默认路由/明文虚拟网络泄漏；记录直连地址暴露、日志秘密扫描和撤销边界 | 最小化外层元数据和日志；安全文案准确描述信任边界，不宣称未实现的端到端语音加密 |
| SP-09 Java/MyBatis 控制面集成与资源 | Java 21+ 虚拟线程、MyBatis、Flyway、连接池、SFU 管理 API 在 2 GB 共置环境的兼容性和余量未验证 | 运行控制面 PoC 和 C0/C1；以真实 PostgreSQL 验证显式锁、事务连接边界、第 13 人拒绝、单活动/IP 唯一、回滚及 Outbox 原子性；检查 `#{}` 绑定、超时/背压、ThreadLocal、迁移版本和 SFU 撤销；保存各进程峰值和 OOM 数据 | 固定受支持运行时和依赖；修正 SQL/锁/映射或停用虚拟线程，服务端实质替换须独立 ADR；不以 Mock 或降低一致性要求代替真实数据库验证 |
| SP-10 Rust 客户端垂直切片 | Rust 核心、Tauri Command/Event、媒体、Helper IPC、状态恢复和三平台构建组合未验证 | 完成登录、入房、真实音频、真实 TUN、双通道状态、退出清理的最小切片；比较候选包体、CPU/RSS、许可证和团队维护记录，满足 AC-043 | 保持候选状态并修复；若引入 Go 或改用 Electron，说明必要性、替代成本与证据后重新评审 |
| SP-11 直连/中继策略 | NAT 穿透、禁止回退、强制中继、在途策略迁移、回滚和旧端点强制撤销未验证 | 覆盖 AC-041/042 全矩阵，以两端及服务器流量证明路径；变更、断网、失权和迟到握手均符合代次/策略；记录无法强制的离线直连边界 | 调整信令、票据和 Peer 撤销；禁止路径有流量即失败，不依赖客户端自觉，不把语音路径混入游戏策略结论 |
| SP-12 设计资产生产适配 | 现有 UI 的模拟域、定时成功和复核工具可能误进正式产品 | 保留原型/UI 文件摘要与视觉回归；命令/事件差异清单覆盖 CA-01～CA-12，正式构建不含模拟授权、故障注入和设备假数据 | 仅修复适配层；必要 UI 变化单独追踪并重审，不重做视觉风格或复用旧审批 |

### 验证环境缺口

当前没有已接入的外部邮件账号、代码签名/公证证书及已记录的 macOS/Linux 实机验证结果；也没有 Tauri/Rust 垂直切片、Java 控制面/Rust Relay PoC、三平台 TUN、真实音频、12 人容量、100 人在线或直连/中继证据。这些项目全部保持“待接入/未验证”，不得以单元测试、Mock、原型浏览器测试或仅 Windows 结果替代。

## 关键技术决策

| ADR | 决策 | 理由与后果 |
| --- | --- | --- |
| ADR-001 | Rust Client Core + Tauri 候选容器 + 复用现有 UI | 符合团队 Rust 能力和 REQ-028，避免重做设计资产；系统 WebView、媒体、包体、更新与三平台组合须 SP-03/06/10 定案，Electron 仅作有证据的对照 |
| ADR-002 | 自托管 WebRTC SFU，LiveKit 首选候选，不自研 SFU | 复用成熟多人媒体能力；具体 SDK/DSP 由真实音质定案，接受 SFU 运维和媒体信任边界，撤销采用组合措施 |
| ADR-003 | Java 21+、Spring Boot 模块化单体 + MyBatis + PostgreSQL + Flyway | 原服务端不因客户端评估自动重写；按用户决定以 MyBatis XML 显式控制事务 SQL/行锁，Flyway 管理 DDL 版本，覆盖单活动、12 人容量、地址和 Outbox；接受 SQL/映射维护成本，2 GB 资源及真实并发由 SP-07/09 验证 |
| ADR-004 | 持久房间与临时活动分表分状态 | 结束活动不丢配置；活动代次使旧凭据和迟到事件失效，删除房间才终止全部恢复 |
| ADR-005 | 语音和网络双控制通道、双状态机 | 加入后并行自动连接，可独立失败和重试；共同受权威活动撤销约束 |
| ADR-006 | 独立 Rust 特权 Helper + 候选 Rust WireGuard + 真实 TUN | UI 不持有管理员/root 权限；窄 IPC 限制攻击面并匹配团队能力，候选库维护、安全、许可证及平台适配须验证，不默认引入 Go |
| ADR-007 | Rust Relay + 运营控制的直连/回退/强制中继 | 直连降低服务器流量，中继提供受限网络路径；三种模式均由服务端票据和策略修订约束，普通客户无开关，撤销和 NAT 风险由 SP-11 验证 |
| ADR-008 | WireGuard 端到端密文，外层显式目标映射 | Relay 无需私钥或明文转发网卡；外层目标元数据和直连地址暴露成为明确隐私边界，映射必须由服务端授权 |
| ADR-009 | 每活动 `/27` + 历史 IP 优先恢复 | 满足 12 人地址和最小路由；有效恢复必须保持，普通重入仅在不冲突且安全时优先恢复，复用不继承权限 |
| ADR-010 | 事务 + 行锁 + 唯一约束 + Outbox + 代次/策略修订 | 防止双活动、超额、地址冲突、旧路径和撤销丢事件；消费者必须幂等并处理重试/积压 |
| ADR-011 | 管理记录只追加，群文字消息 tombstone，群解散仅产品不可见 | 满足审计和删除展示；不将逻辑状态夸大为物理删除或后台不可篡改，不引入媒体附件范围 |
| ADR-012 | 三平台同为发布目标，证据决定支持矩阵 | 不用单平台测试推断其他平台；缺设备、证书或邮件渠道时明确未验证，不伪造发布结论 |
| ADR-013 | 在线、在房、发言和中继分档容量，受控背压 | 在 2 核/2 GB/5 Mbit/s 下分别验证 100 人在线、12 人满房和分房混合负载；一档不能替代另一档，资源不足提交运营决策而不暗改目标 |
| ADR-014 | 客户端成熟音频处理链 + 真实设备质量门禁 | 优先 AEC/降噪/AGC/VAD 并避免重复处理；以 AC-040 听辨、延迟、弱网和资源结果定案，不以 SDK 或连接成功替代音质 |
| ADR-015 | 原型/UI 资产保留，生产仅复用呈现和状态契约 | 保留视觉风格和已批准交互；模拟授权、故障注入和定时器不进入正式构建，必要增量单独追踪和评审 |

## 需求追踪

| 需求标识 | 设计模块/决策 | 验证方式 |
| --- | --- | --- |
| REQ-001 | Identity & Session、Auth API、凭据安全设计；SP-05 | AC-001：真实/受控邮件注册、验证、登录退出、已验证邮箱找回及无绕进入口 |
| REQ-002 | `users/auth_sessions/account_activity`、Activity Coordinator、ADR-003/004 | AC-002、AC-037：未验证接口拒绝，多端登录但并发仅单活动单设备 |
| REQ-003 | Social、好友 API、`friendships/friend_requests` | AC-003：好友全流程、无私聊、好友不授予群/房权限 |
| REQ-004 | Group & Invitation、Message、`groups/group_members/messages` | AC-004、AC-029：群资料/群文字消息/房间重启保持，仅邀请入群且无公开搜索 |
| REQ-005 | Group & Invitation、邀请事务及状态约束 | AC-005、AC-006：邀请撤销/失效、幂等接受、邀请人退群和重入角色边界 |
| REQ-006 | Ownership、`owner_transfers/owner_recovery_requests` | AC-007：转让接受前不变，群主退出限制，争议恢复不自动继任 |
| REQ-007 | 授权矩阵、执行时校验、Revocation Coordinator | AC-008：逐角色接口测试、跨权限/旧页面/旧会话拒绝 |
| REQ-008 | Room Administration、`rooms`、房主接管规则 | AC-009：配置持久化、失群籍接管、普通离线不接管、有效指定 |
| REQ-009 | Activity Coordinator、12 人容量事务、开放/密码规则 | AC-010、AC-011、AC-013：12/13 人、保留占额、暂停、免密码边界及重入 |
| REQ-010 | Room/Activity 状态机、`rooms/activities`、ADR-004 | AC-012：结束清退但保房，删除终止会话与恢复，空房不自动删除 |
| REQ-011 | Activity Coordinator、`account_activity/connection_leases`、代次与 Outbox | AC-014～AC-016、AC-035、AC-037：切房/恢复/争抢、60 秒保留和迟到结果拒绝 |
| REQ-012 | 候选 SFU、Voice Controller、Audio Processing、ADR-002/014 | AC-017、AC-034、AC-035、AC-040：12 端真实音频、多人发言、状态、扰动、长会话与真实音质 |
| REQ-013 | Audio Device & Processing、Voice Adapter、UI 设备状态 | AC-018、AC-036、AC-040：设备选择/测试/音量/静音及权限、拔出、切换和处理质量 |
| REQ-014 | Rust Network Helper、候选 Rust WireGuard、TUN、Address & Lease、直连/Relay | AC-020、AC-023、AC-031、AC-034：真实 TCP/UDP、IP 展示复制、三平台与容量 |
| REQ-015 | 活动隔离、`ip_allocations/ip_history`、代次、ADR-008/009 | AC-021、AC-022：跨房失败、有效恢复保 IP、安全可用时重入优先恢复及全新授权 |
| REQ-016 | Helper 路由白名单、直连/Relay 无转发/默认路由、ADR-006/008 | AC-023、AC-024：本机服务可达，互联网不代理、物理 LAN 不转发及错误提示 |
| REQ-017 | Voice/Network Controller 双状态机、独立 Grant、ADR-005 | AC-019、AC-025：分别注入故障，另一项保持并独立重试 |
| REQ-018 | Revocation Coordinator、SFU 移除、直连 Peer/Relay deny、租约/代次 | AC-026、AC-042：各失权场景下已有/新建语音、直连、中继、同步和恢复在目标内撤销 |
| REQ-019 | Message、群文字 `messages` tombstone、Audit | AC-027：历史保持、作者/管理员删除边界、提示和代删记录 |
| REQ-020 | Audit、`audit_logs`、只读 API | AC-028：字段完整、当前管理员可读、普通/失权/跨群及写改删失败 |
| REQ-021 | PostgreSQL 持久化、群解散状态、备份边界、ADR-011 | AC-029：重启保持，解散产品内不可见且不声称未验证物理删除 |
| REQ-022 | Tauri bundler/updater 候选、Helper 安装、支持矩阵 | AC-030、AC-031、AC-043：按矩阵实际安装/更新/卸载与三平台互通，记录权限、游戏版本和选型证据 |
| REQ-023 | Event & Sync、双状态机、Helper 事件、Compatibility & Diagnostics | AC-024、AC-032、AC-035：真实分域状态、错误、自动/手动恢复及未验证标识 |
| REQ-024 | 安全基线、密钥分权、脱敏日志、最小 UI/Helper IPC | AC-033：日志/管理记录秘密扫描，传输、存储和越权安全检查 |
| REQ-025 | Capacity & Admission、C0～C4 分层容量模型、分域指标和背压；ADR-013、SP-07/09 | AC-038、AC-039：目标单机 100 人在线、12 人满房和 100 人分房分别压测，记录协议/重传/同步/更新及真实资源边界 |
| REQ-026 | Audio Device & Processing、客户端 AEC/降噪/AGC/VAD、语音质量设计；ADR-014、SP-03 | AC-040：真实设备游戏并行、降噪 A/B、听辨、多人叠音、延迟、CPU 和弱网测试 |
| REQ-027 | Network Policy、Path Coordination、策略修订、直连/Relay 票据和迁移；ADR-007、SP-11 | AC-041、AC-042：运营三模式、无客户开关、路径抓包、禁止回退、策略切换/回滚及撤销隔离 |
| REQ-028 | Rust Client Core/Helper/Relay、Tauri 候选对比、现有 UI 资产适配；ADR-001/006/015、SP-06/10/12 | AC-043、AC-044：候选比较与三平台最小验证、依赖许可证/维护成本；保留资产摘要和差异清单，必要变更重新评审 |

### 验收入口

- 功能与接口测试必须沿用 REQ/AC 标识；安全测试增加水平越权、跨活动、旧代次、旧策略、重复请求和并发争抢。
- SP-01～SP-12 的输出是架构风险验证证据；AC-001～AC-044 的通过仍需后续完整测试与发布证据，两者不能相互替代。
- 开发规划优先安排 SP-10 垂直切片及 SP-01/02/03/06/09 的阻塞性 PoC，再锁定具体依赖；C1 在线容量与 C2/C3 媒体/网络容量分别设门禁。
- 任何架构变更影响已批准需求、权限边界或验收目标时，必须返回需求影响评估；只改变实现且保持基线摘要时，按工作流重新提交当前架构审批。
