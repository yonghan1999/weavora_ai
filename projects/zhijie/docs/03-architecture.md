# 架构与详细设计：织界

> 状态：C 阶段架构草案，待人工审批。依据已批准的 `docs/02-requirements.md`（REQ-001～REQ-024）。
>
> 本文中的容量、时延和恢复数值均为验收目标，不是实测结果。当前未运行架构 PoC；Windows、macOS、Linux 的 TUN、真实语音、跨平台互通、16 人容量、邮件投递、签名安装与自动更新均须在对应验证完成后才能宣称支持。

## 架构概述

### 1. 目标与边界

织界首版采用“跨平台桌面客户端 + 单控制面 + 自托管语音媒体面 + 房间密文中继”的客户端/服务端架构。持久群组和房间属于控制面；一次游戏联机属于房间内的临时活动。用户加入活动后，客户端并行发起语音和虚拟网络连接，两者使用不同凭据、状态机和故障域。

首版必须形成以下真实链路，不能用界面状态或模拟传输替代：

1. 好友、群组、邀请、消息、权限和持久房间均由服务端权威管理并持久化。
2. 语音经 WebRTC 进入自托管 LiveKit SFU，支持最多 16 个账号的真实双向音频。
3. 虚拟网络由本机特权辅助进程创建真实 TUN，并通过 wireguard-go 建立端到端 WireGuard 密文通信；所有首版流量经房间中继，不做 P2P。
4. 中继只转发同房、同活动代次、有效设备租约之间的密文，不设置默认路由，不代理普通互联网，不转发物理局域网其他设备。
5. 退出、踢出、失权、结束活动和删除房间均由服务端产生权威撤销，语音、网络和内容同步分别执行撤销，迟到结果不能恢复旧资格。

### 2. 逻辑拓扑

```text
桌面客户端（Windows / macOS / Linux）
├─ Electron 主进程
│  └─ 窗口、安全 IPC、更新、受限本地 Helper 客户端
├─ React 渲染进程
│  └─ 产品 UI、LiveKit JS SDK、音频设备与状态展示
└─ 特权 Network Helper
   └─ wireguard-go、真实 TUN、/27 路由、Relay 连接

控制面与数据面
├─ Java 控制面（模块化单体）
│  ├─ 认证、好友、群组、房间、活动
│  ├─ IP / 租约、撤销、消息、审计
│  └─ PostgreSQL + Outbox 事件
├─ LiveKit SFU
│  └─ 真实多人语音媒体
└─ Java / Netty Room Relay
   └─ WireGuard 密文转发，不持有成员私钥

连接关系
1. 桌面客户端 -> Java 控制面：HTTPS / WebSocket
2. 桌面客户端 -> LiveKit SFU：WebRTC
3. Network Helper -> Room Relay：自定义 Bind / 加密中继会话
4. Java 控制面 -> LiveKit SFU、Room Relay：管理 API / 授权与撤销事件
5. Java 控制面 -> PostgreSQL：JDBC / 事务
```

### 3. 权威状态与一致性

- PostgreSQL 是账号、群籍、角色、房间、活动资格、IP 分配、连接租约和消息的唯一权威数据源；客户端缓存和 WebSocket 事件均不是授权依据。
- 所有敏感接口在执行时重新校验账号状态、群籍、角色、房间和活动资格，不能仅凭访问令牌中的旧声明授权。
- 加入、切房、设备转移和恢复使用事务、行锁、唯一约束及幂等键。账号的活动指针全局唯一，房间占用按“不同账号 + 活动中/断线保留”计数。
- `activities.activity_generation` 标识活动生命周期代次，`activity_members.member_generation` 标识成员资格代次；以下合称“代次”。加入、退出、切房、失权、结束和删除使相应旧代次失效。
- 权威变更与待投递事件在同一数据库事务内提交；Outbox 投递可重试，消费者按事件 ID 幂等。撤销事件的优先级高于普通状态同步。
- 客户端连接、恢复、Helper 请求及媒体/中继接入均携带活动 ID、两类代次、设备 ID 和租约 ID；任一不匹配即拒绝或丢弃。

### 4. 关键运行链路

**加入/切房/设备转移**：先锁定账号活动指针并校验旧活动，再校验目标房间、当前群籍、密码或管理员豁免、开放状态、16 人容量及目标地址；目标可预留后在事务内原子替换活动资格并增加代次。目标校验或预留失败且旧活动仍有效时不改变旧活动；旧活动已结束则保持未入房。提交后才签发两种独立凭据，任何迟到回调均须通过代次校验。

**断线恢复**：租约断线后保留 60 秒名额、设备归属和原 IP。保留期内仅同账号、同设备、同活动及有效代次可恢复；超时由租约清理任务释放。主动退出、切房、失权、结束或删除立即取消恢复，不等待 60 秒。

**重新加入与 IP**：重入不承诺原 IP。系统先查该账号在该房间的历史地址；只有地址在当前活动可用、无冲突且通过房间隔离和当前安全校验时才优先恢复，否则分配新地址。任何地址复用都创建新成员代次、设备租约、语音凭据和 WireGuard 会话，不继承旧密钥、旧连接或旧访问权限。

## 技术选型及依据

| 层次 | 选型 | 依据与限制 |
| --- | --- | --- |
| 桌面壳 | Electron + React + TypeScript | 三平台共用 UI 与 Chromium 媒体能力；便于复用 LiveKit JS SDK 的音频、设备和重连能力。主进程不因此获得网络特权，网络特权独立放在 Helper。 |
| 语音 | 自托管 LiveKit SFU + LiveKit JS SDK | 提供真实 WebRTC 音频发布/订阅、房间、参与者、轨道和连接事件，降低自研 SFU 风险。首版单节点；LiveKit 可接触媒体明文，不能宣称语音端到端加密。 |
| 控制面 | Java 21+、Spring Boot 模块化单体、Spring MVC、Maven Wrapper | 适合复杂权限、活动状态机、事务和工程化维护；以 Java 21+ 虚拟线程简化并发 I/O，但是否启用及框架兼容性须在开发 PoC 验证。首版不引入业务微服务、消息集群或多控制面。 |
| 持久化 | PostgreSQL + Spring Data JDBC + 显式 SQL | 事务、行锁、唯一约束和部分索引可表达全局单活动、16 人占额、地址唯一及 Outbox；关键并发路径使用显式 SQL 和事务边界，不依赖应用内存检查或隐式 ORM 行为。 |
| 客户端 API | REST + WebSocket | REST 承担命令和查询；WebSocket 承担状态、消息和撤销通知。命令结果以 REST/数据库为准，WebSocket 重连后按游标补同步。 |
| 虚拟网络 | Go Network Helper + wireguard-go + 真实 TUN | wireguard-go 提供跨平台用户态 WireGuard 实现；Helper 隔离管理员/root 权限。三平台网卡创建、路由、权限和自定义 Bind 仍需 PoC。 |
| 首版转发 | Java + Netty 独立 Relay，中继优先，不做 P2P | Netty 提供异步网络编程基础，中继可在服务端统一执行房间隔离和即时撤销。Relay 不持有成员私钥、不创建明文转发网卡；其吞吐、GC 停顿和撤销时延必须实测。 |
| Relay 传输 | 自定义 Go Bind + Java Relay 加密会话；QUIC Datagram 为首选候选 | 外层只携带转发所需的活动、租约、目标和认证元数据，内层 WireGuard 保持端到端密文。Java Relay 的 QUIC 能力及其与 wireguard-go Bind 的组合未验证；不可用时再评估 TLS 流封装及队头阻塞，不预先宣称成功。 |
| 安装与更新 | electron-builder + electron-updater | Windows NSIS、macOS DMG/ZIP、Linux AppImage。macOS 自动更新依赖有效签名；Linux 不使用 Electron 内置 autoUpdater。Helper 随签名安装包安装和升级。 |
| 服务间投递 | PostgreSQL Transactional Outbox + 幂等消费者 | 避免“数据库已撤销但媒体/中继未收到事件”的双写不一致；首版由控制面内后台任务投递，无需独立消息集群。 |
| 后续组件演进 | 保持版本化 API、Relay 协议和 Helper IPC；允许以 Rust 替换独立组件 | Rust 只作为后续候选，不属于首版承诺。必须由性能测量、安全评估和 PoC 证明收益，替换时保持外部契约和撤销语义，不预设换语言必然提升性能或安全。 |

### 官方技术依据

- Java 21 虚拟线程：<https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html>
- Spring Boot：<https://docs.spring.io/spring-boot/>
- Spring Data JDBC：<https://docs.spring.io/spring-data/jdbc/reference/>
- Netty：<https://netty.io/wiki/>
- LiveKit 自托管 WebRTC SFU：<https://docs.livekit.io/intro/about/>
- LiveKit JS SDK 连接、轨道与事件：<https://docs.livekit.io/reference/client-sdk-js/>
- 自托管令牌撤销限制：<https://docs.livekit.io/frontends/reference/tokens-grants/>
- LiveKit RemoveParticipant 等管理 API：<https://docs.livekit.io/intro/basics/rooms-participants-tracks/participants/>
- Electron 安全基线：<https://electronjs.org/docs/latest/tutorial/security>
- electron-builder 更新目标与限制：<https://www.electron.build/auto-update>
- wireguard-go 平台说明：<https://git.zx2c4.com/wireguard-go/about/>
- PostgreSQL 行锁：<https://www.postgresql.org/docs/current/explicit-locking.html>
- Go 系统最低要求：<https://go.dev/wiki/MinimumRequirements>

依赖具体版本在开发规划阶段根据 Maven 锁文件/依赖锁定、前端锁文件和兼容性验证固定，本文不虚构已验证版本。

## 模块划分与职责

### 1. 桌面端

| 模块 | 职责 | 明确不负责 |
| --- | --- | --- |
| Electron Main | 创建窗口、限制导航、校验渲染进程 IPC 调用方、操作系统安全存储、更新、调用本地 Helper | 不直接执行任意系统命令，不直接创建 TUN，不把管理员/root 权限授予 UI |
| React 产品 UI | 账号、好友、群组、房间、消息、成员/IP、权限和真实状态展示 | 不自行判定授权，不伪造在线/连接/兼容状态 |
| Voice Controller | 获取语音凭据、LiveKit 连接、独立重试、参与者/说话状态、静音与接收开关 | 不控制虚拟网络生命周期，不将本地静音当作服务端撤销 |
| Audio Device Manager | 输入输出选择、设备测试、总音量、成员音量、权限拒绝/拔出/切换提示 | 不修改其他成员的本地音量，不在设备失败时停止虚拟网络 |
| Network Controller | 获取网络凭据、生成/保护本机密钥、调用 Helper、展示网卡/IP/中继状态及独立重试 | 不依赖语音成功，不设置默认路由，不转发物理 LAN |
| Local Helper Client | 使用固定协议调用 Helper，校验响应与事件，超时/断连处理 | 不提供任意路由、任意命令或任意文件写接口 |
| Network Helper | 创建和销毁 TUN、配置仅活动网段路由、运行 wireguard-go、建立 Relay 会话、应用成员 Peer、上报真实错误 | 不决定群/房间权限，不持久保存服务端刷新令牌，不接受未白名单请求 |

### 2. Java 控制面

控制面按业务模块组织 Spring Boot 单体，模块间通过明确 Java 接口和领域事件协作，禁止跨模块直接修改他人数据。事务边界放在应用服务层，并发关键路径使用 Spring Data JDBC 执行显式 SQL；REST、WebSocket、Outbox 和 LiveKit 管理客户端均通过接口隔离，以便后续独立替换实现。

虚拟线程只用于阻塞式 I/O 的并发简化，不改变数据库锁、代次或授权语义；连接池、事务和 ThreadLocal 等兼容性必须经验证，不能因使用虚拟线程而取消背压、超时或资源上限。

| 模块 | 职责与关键规则 |
| --- | --- |
| Identity & Session | 邮箱注册验证、登录/退出、找回密码、会话轮换、账号失效；未验证账号仅开放必要账号接口 |
| Social | 账号搜索、好友申请/接受/拒绝/删除、在线状态；好友关系不授予群或房间权限，不实现私聊 |
| Group & Invitation | 群资料、成员、邀请、角色和解散；邀请接受幂等，邀请人失群籍时使待接受邀请失效，已接受成员不随之退出 |
| Ownership | 群主转让接受流程和人工恢复申请；完成前原群主不变，证据不足或争议不自动继任 |
| Room Administration | 持久房间 CRUD、密码、开放状态、房主、活动开始/结束；房主失群籍或账号失效时由群主接管 |
| Activity Coordinator | 加入、切房、设备转移、退出、60 秒保留、容量、代次和唯一活动资格；采用统一锁顺序避免并发死锁与双活动 |
| Address & Lease | `/27` 地址分配、历史 IP、冲突检测、活跃地址唯一、租约心跳/过期及重入优先恢复 |
| Voice Grant | 校验当前资格后签发房间限定、短 TTL 的 LiveKit token；失权后不再续签 |
| Network Grant | 接收本机 WireGuard 公钥，签发一次性短时网络接入票据及活动成员/地址描述；不接收或保存成员私钥 |
| Revocation Coordinator | 消费权威撤销事件，关闭同步、移除 LiveKit 参与者、更新 Relay 拒绝集合、取消租约；接口层同步执行当前资格校验 |
| Message | 历史消息、作者删除、管理员代删及 tombstone；群解散后产品内不可读 |
| Audit | 只追加管理记录，记录操作者、目标、时间、操作、结果和关联 ID；不记录秘密 |
| Event & Sync | Outbox、WebSocket 事件、断线游标和全量校正；旧代次事件对客户端无效 |
| Compatibility & Diagnostics | 返回候选/已验证支持矩阵及脱敏诊断标识；未执行项目保持未验证 |

### 3. LiveKit 与 Relay

Relay 是独立 Java 数据面进程，使用 Netty 管理连接、数据报、背压和资源上限，不属于业务微服务拆分。控制面只通过版本化 Relay 管理协议下发授权和撤销；Relay 不直接修改业务表。协议包含版本、活动/成员代次、租约、目标映射和过期时间，为未来 Rust Relay 替换保留相同契约。

- LiveKit 房间名使用不可猜测的活动映射，不使用群名称；参与者身份绑定账号、设备和成员代次。控制面仅通过服务端管理 API 创建/结束房间和移除参与者。
- 自托管 LiveKit 的 RemoveParticipant 不等同于 Cloud 的自动旧 token 全面失效，因此撤销必须组合“移除参与者 + 短 TTL + 失权后绝不重新签发 + 活动/成员代次校验”。刷新凭据接口每次重新查权威资格。
- Relay 会话由控制面签发的一次性票据激活，绑定 `activity_id + activity_generation + member_generation + device_id + lease_id + 本端虚拟 IP`。
- 外层转发头包含目标虚拟 IP/目标成员映射；Relay 验证来源租约、目标租约、活动和代次后按目标转发。目标映射必须由服务端下发并与当前成员匹配，客户端不能任意指定跨活动目标。
- WireGuard 私钥仅在本机生成和保存，成员之间进行端到端加密；Relay 看到外层路由元数据和 WireGuard 密文，不持有解密密钥，也不读取内层明文来选择目标。
- Relay 不配置系统 IP forwarding，不桥接物理网卡，不提供默认路由或互联网出口。撤销时同时拒绝来源和目标相关转发并关闭活动会话；客户端随后移除 Peer 和 TUN。

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
| `activity_members` | `activity_id, user_id, device_id, member_generation, state(active/grace/revoked/left), joined_at, grace_expires_at`；当前活动中账号唯一，active/grace 均占 16 人名额 |
| `account_activity` | `user_id UNIQUE, activity_id, device_id, member_generation, operation_id`；全局单活动指针，切换只能在事务内替换 |
| `connection_leases` | `id, activity_id, user_id, device_id, generations, voice_state, network_state, expires_at, revoked_at`；两类连接状态分开记录，撤销立即失效 |
| `ip_allocations` | `activity_id, user_id, ipv4, allocated_at, released_at`；`(activity_id, ipv4)` 活跃唯一，用户当前地址唯一 |
| `ip_history` | `room_id, user_id, last_ipv4, last_activity_id, updated_at`；仅作为重入偏好，不构成地址或权限保留 |
| `messages` | `id, group_id, author_id, body, created_at, deleted_at, deleted_by, delete_kind`；删除为 tombstone，列表展示删除提示 |
| `audit_logs` | `id, group_id, actor_id, target_type/id, action, result, occurred_at, correlation_id`；只追加，产品无更新/删除/清空接口，秘密字段禁止写入 |
| `outbox_events` | `event_id UNIQUE, aggregate_id, type, payload_minimal, generation, occurred_at, delivered_at, attempts`；与业务事务同提交，消费者按 event_id 幂等 |
| `idempotency_keys` | `actor/device, operation, key, request_hash, response_ref, expires_at` 唯一；同键不同请求体返回冲突 |

### 地址与容量规则

1. 每个活动建议使用独立 IPv4 `/27`，可覆盖 16 名成员及中继/保留地址；具体可用地址数、保留地址、MTU 和冲突处理由 SP-01/SP-02 验证后锁定。
2. 每个客户端只配置自身虚拟 IP 和该活动 `/27` 路由，不修改系统默认网关、DNS 或物理 LAN 路由。
3. 活跃分配由数据库唯一约束兜底；Relay 再检查数据包外层来源/目标与当前分配一致，防止伪造。
4. 同活动有效恢复直接保持原分配；新加入/重入先锁地址空间，尝试历史地址，冲突则选择空闲地址。历史 IP 不跨房间授予连通性或身份。
5. 第 16 个不同账号可占额，第 17 个被拒绝；并发加入必须在同一事务锁范围内完成计数与插入。

### 删除与持久化

- 消息作者可删除本人消息，群主/管理员可删除本群任意消息；事务内设置 tombstone，并为管理员代删追加审计。原内容的数据库保留/清理策略在生产数据政策中另行确定。
- 群解散使群、消息、邀请、房间和管理记录在产品内不可查看或恢复，同时撤销全部活动；物理删除、备份保留与法定留存不在首版自动承诺。
- 管理记录仅当前群主/管理员按群只读。数据库角色和迁移流程限制应用更新/删除；后台防篡改和归档是生产准备事项，不提前宣称不可篡改。

## 接口契约

### 1. 通用约定

- 基础路径 `/api/v1`；写请求使用 JSON 和 `Idempotency-Key`，时间使用 RFC 3339 UTC，ID 使用字符串。
- 成功响应包含 `data` 和可选 `correlation_id`；错误包含稳定 `code`、可理解 `message`、是否可重试及 `correlation_id`，不返回堆栈或秘密。
- 常用错误：`401 AUTH_REQUIRED`、`403 CURRENT_GRANT_REQUIRED`、`404 RESOURCE_NOT_AVAILABLE`、`409 GENERATION_STALE / CAPACITY_FULL / ROOM_PAUSED / IP_CONFLICT / OPERATION_CONFLICT`、`410 ACTIVITY_ENDED`、`422 VALIDATION_FAILED`、`429 RATE_LIMITED`。
- 为避免资源枚举，跨群/无资格资源可按安全策略返回 404；容量、暂停和密码错误仅对已通过基础群籍校验者返回。
- 访问令牌短时有效，桌面刷新令牌存操作系统安全存储；所有资格敏感操作即使令牌有效也重新查当前状态。
- 活动命令响应返回 `activity_id, activity_generation, member_generation, device_id, lease_id, voice_state, network_state`；客户端不能用本地推测替代服务端结果。

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
| 恢复/退出 | `POST /activities/{id}/resume`、`POST /activities/{id}/leave` | 恢复校验同账号/设备/代次/60 秒租约；退出立即撤销旧恢复，重复退出幂等 |
| 活动视图 | `GET /activities/{id}`、`GET /activities/{id}/members` | 仅当前有效成员；返回真实成员、自己的/成员虚拟 IP 和独立状态，不返回任何秘密私钥 |
| 语音凭据 | `POST /activities/{id}/voice-grants` | 每次重新校验资格；返回 LiveKit URL、房间限定短 TTL token、代次；失权后拒绝刷新 |
| 网络凭据 | `POST /activities/{id}/network-grants` | 请求含设备、本机 WireGuard 公钥和代次；返回一次性票据、Relay 地址、自身 IP、活动网段及成员 Peer 描述；私钥不上传 |
| 消息 | `GET/POST /groups/{id}/messages`、`DELETE /messages/{id}` | 分页游标稳定；作者或群管理员按规则删除；失权后停止后续读取和同步 |
| 管理记录 | `GET /groups/{id}/audit-logs` | 仅当前群主/管理员只读分页；无产品内写改删接口 |
| 支持矩阵 | `GET /compatibility-matrix` | 分开返回候选、已验证和未验证项及验证证据引用，不将游戏互通与系统互通混同 |

`|accept|reject` 等表示对应独立动作端点，实际路由在 OpenAPI 中展开，不使用竖线作为字面 URL。

### 3. WebSocket 同步

- 连接：`GET /api/v1/ws?cursor=...`，使用短期访问凭据；服务端每条连接持续校验会话和同步范围。
- 事件信封：`event_id, group_id?, activity_id?, activity_generation?, member_generation?, sequence, type, occurred_at, payload`。
- 事件类型至少包括 `message.created/deleted`、`member.changed`、`room.changed`、`activity.changed`、`lease.revoked`、`voice.revoked`、`network.revoked`、`resync.required`。
- 客户端按活动代次和 sequence 去重；旧代次事件丢弃。游标不可用或服务端无法证明连续时执行 `resync.required`，重新拉取权威快照。
- WebSocket 断线不自动等于活动退出；60 秒租约由独立心跳和连接状态判定。失权后服务端关闭同步，重连仍须通过当前权限检查。

### 4. 本地 Network Helper IPC

传输使用 Windows 命名管道、macOS/Linux 仅本机受限 Unix Socket；对端进程/用户校验、文件权限和协议版本检查全部通过后才可用。消息采用长度受限的版本化 JSON 或等价 schema，不使用 shell 命令。

| 方法/事件 | 输入与结果 | 安全限制 |
| --- | --- | --- |
| `helper.get_capabilities` | 返回 Helper/协议版本、TUN 能力、权限和平台错误 | 不将“可调用”显示为“三平台已验证” |
| `network.start` | 一次性票据、活动/代次/租约、自身 IP、`/27`、Relay、成员 Peer、本机私钥的安全内存引用；返回真实启动状态 | 校验 schema、长度、过期时间、网段和目标白名单；只允许活动网段，不接受默认路由/自定义命令 |
| `network.update_peers` | 当前代次及新增/移除 Peer | 仅接受控制面已授权成员，旧代次拒绝 |
| `network.stop` | 活动、代次、租约和 request_id | 幂等停止并清理 TUN、路由、Peer 和 Relay；清理失败返回可诊断错误 |
| `network.status` / `network.retry` | 返回未连接/连接中/已连接/受限/断开/重试中/失败及错误码 | 状态来自 Helper 和实际传输，不由 UI 假定 |
| `network.event` | 上报地址冲突、权限不足、TUN/路由失败、Relay 断开、代次失效 | 事件不包含私钥、令牌或完整敏感配置 |

Helper 崩溃或 IPC 断开时，UI 将网络标为失败/未知并可独立重试，不中断语音；重启 Helper 必须重新验证当前票据和代次，不能自动复活已撤销会话。

## 安全与权限设计

### 1. 客户端与服务端基线

- Electron 渲染进程启用 `contextIsolation` 和 sandbox，关闭 Node integration；仅通过最小 preload API 暴露能力。校验所有 IPC sender，限制导航、新窗口、权限请求和远程内容，使用严格 CSP。
- 密码使用 Argon2id 等适配风险的慢哈希及独立盐；房间密码独立哈希，不写入日志。验证码和找回令牌短时、限次、只存哈希并限流。
- 外部流量全程 TLS；服务间 API 使用独立服务凭据和最小权限。语音 token、网络票据、刷新令牌和 LiveKit/Relay 管理密钥分权保管，不能共用。
- 日志默认结构化脱敏，禁止记录密码、验证码、完整令牌、私钥和房间密码；网络诊断只保留必要 IP/设备/错误关联信息。生产密钥管理和日志保留期待专项配置。
- 登录、验证码、找回、邀请、加入、信令和凭据签发均限流；错误不泄露不必要账号存在性或跨群资源信息。

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
| 绕过 16 人、暂停、群籍或账号状态 | 不可以 | 不可以 | 不可以 | 不可以 |

所有表格权限仍需执行时检查；房间权限不反向授予群权限，好友关系不进入该矩阵。

### 3. 撤销与隔离

1. 权威事务先取消成员资格、增加代次、撤销租约并写入 Outbox；后续 REST、WebSocket、语音凭据和网络凭据立即因当前资格失败。
2. Revocation Coordinator 并行调用 LiveKit RemoveParticipant、Relay deny/disconnect 和 WebSocket close；失败按指数退避重试并报警，不能因一次投递失败把资格恢复。
3. Relay 每个数据报均使用内存中的当前租约/代次/目标映射；收到撤销立即拒绝来源和目标通信。首版无 P2P，因而不存在绕过 Relay 的既有直连路径。
4. LiveKit 使用短 TTL、参与者移除和禁止续签组合；客户端收到撤销后停止发布/订阅并清理音频，但服务端措施不依赖客户端配合。
5. 服务端确认失权后 5 秒内完成语音、网络和同步撤销是验收目标。Outbox 极端积压、媒体服务不可用等情况必须暴露失败和告警，不能虚报达成。
6. 不同活动使用独立 LiveKit 房间、Relay 会话集合、地址空间和授权代次；即使虚拟 IP 数值相同，也不能跨活动转发。
7. Helper 只配置活动 `/27`；操作系统防火墙是否允许本机服务经该接口被访问由平台配置和用户授权决定，产品不宣称“仅限游戏流量”。

### 4. 密钥和地址生命周期

- 每台设备在本地生成 WireGuard 私钥，优先使用操作系统安全存储或受限文件；仅公钥上传。撤销/切换时删除旧 Peer，重入可轮换密钥并必须建立全新会话。
- 服务端、Relay 和日志不接触 WireGuard 私钥。LiveKit/Relay 管理密钥仅服务端持有，桌面端只获取最小范围短期凭据。
- IP 不是身份或权限。所有 Peer 更新与转发均同时校验账号、设备、活动、成员代次和租约，不能仅凭曾拥有某 IP 获得访问。

## 部署及运行方案

### 1. 首版环境拓扑

- 一套 Java/Spring Boot 控制面进程、一套 PostgreSQL、一套单节点 LiveKit 和一套独立 Java/Netty Relay；可由反向代理统一终止外部 TLS。首版单控制面是明确简化，不承诺高可用。Java 进程固定支持的运行时版本、堆上限和容器/服务资源限制，禁止依赖开发机默认 JVM 参数。
- LiveKit 单节点不引入 Redis；未来多节点媒体或 Relay 水平扩展需另做一致性和容量设计。
- 控制面与 PostgreSQL 仅通过受限内网/本机访问；仅公开必要 HTTPS、WebSocket、WebRTC 和 Relay 端口。具体端口、防火墙和 TURN/中继连通性在部署 PoC 验证。
- 生产部署、服务器购买、域名、证书、邮件账号和签名证书均需另行授权；当前不执行采购或生产部署。

### 2. 桌面发布

| 平台 | 候选安装/更新 | 权限与验证要求 |
| --- | --- | --- |
| Windows 10 22H2/11 x64 | NSIS；electron-updater | 安装/升级 Helper 需要管理员授权，普通运行 UI 不提升；验证 TUN、路由、杀软兼容、卸载清理 |
| macOS 13+ Intel/Apple Silicon | DMG + ZIP；签名后 electron-updater | Helper 通过受限 LaunchDaemon/授权安装运行；自动更新需有效签名，公证、utun 和权限流程待实测 |
| Ubuntu 22.04/24.04 x86_64 | AppImage；electron-updater 的 AppImage 路径 | Helper 由受限 systemd 服务运行；桌面环境、安装授权、TUN 和更新差异记入支持矩阵 |

- 客户端与 Helper 必须版本兼容；安装器进行签名校验和原子升级，升级失败可回滚，Helper 不得因 UI 更新留下无权限孤儿进程。
- 卸载清理本机 TUN、路由、Helper 和按产品政策清理本地凭据；卸载不等于删除服务端账号、群或消息。
- 支持矩阵记录系统版本、架构、Linux 发行版/桌面环境、权限条件、客户端/Helper 版本及已测游戏版本。候选项不得标成通过。

### 3. 配置、观测与备份

- 配置通过环境变量/受限密钥存储注入，仓库不保存真实秘密。数据库迁移单向版本化并在发布前备份。
- 指标至少包括 API 错误、活动占额、代次冲突、Outbox 延迟、撤销耗时、JVM 堆/线程/GC 停顿、数据库连接池、LiveKit 参与者/连接质量、Relay 会话/吞吐/丢包/拒绝、Helper 启动与路由错误。
- 日志使用 `correlation_id + activity_id + user/device 伪标识` 关联，不记录秘密；针对撤销失败、Relay/LiveKit 不可达和 Outbox 积压配置告警。
- PostgreSQL 备份、恢复演练、物理删除和保留政策在生产准备阶段批准。首版只承诺产品重启后的持久化，不将“有数据库”写成“已有灾难恢复”。
- 可用性目标：网络恢复且资格有效时，可用服务于 15 秒内自动恢复或明确失败并可手动重试；语音和网络分别计时，不要求同时成功。

## 技术风险和待验证事项

> 下表所有 SP 当前均为“未执行/待验证”。PoC 通过只证明对应原型条件，不替代三平台安装、16 人 60 分钟、游戏兼容或安全验收。

| 标识 | 风险与待验证问题 | 最小验证与通过证据 | 失败处理 |
| --- | --- | --- | --- |
| SP-01 三平台 TUN/权限 | wireguard-go、自定义 Bind、真实 TUN、`/27` 路由及 Helper 权限在 Windows/macOS/Linux 未实测 | 各候选系统创建网卡，完成真实 TCP/UDP，确认默认路由/DNS/物理 LAN 未改变；记录管理员/root 授权、安装、卸载和崩溃清理 | 分平台修复 Helper/安装方式；未通过平台标未验证，不得缩减交付范围后仍宣称全平台 |
| SP-02 Java Relay/隔离/撤销/IP | Java/Netty 外层目标映射与端到端 WireGuard、中继转发、16 地址规划、冲突、即时撤销和历史 IP 优先恢复未验证 | 两端及多成员经 Java Relay 互通；跨房/伪造目标失败；Relay 无私钥；撤销后已有/新建转发失败；恢复与重入地址符合 REQ-015；记录 JVM 吞吐、GC 和资源数据 | 调整 Bind、转发索引、地址锁、背压和拒绝集合；若 Java QUIC 不可用再评估 TLS 流及队头阻塞，记录实测权衡 |
| SP-03 真实音频与设备 | Electron + LiveKit 在真实麦克风/输出、多人发言和设备异常下未验证 | 真实音频发布/收听；多人同时发言；静音、关闭接收、设备选择/测试、成员音量；权限拒绝、拔出和切换均准确提示且不崩溃 | 修复设备热插拔/权限流程；必要时调整音频捕获边界，但不能用模拟音频替代验收 |
| SP-04 独立故障与恢复 | 语音/网络故障隔离、60 秒保留、15 秒恢复目标、迟到连接和双活动未验证 | 分别停止 LiveKit、Relay、Helper 和网络；另一项继续；并发切房/设备争抢仅一个活动；保留/超时和旧代次行为符合需求 | 修正状态机、心跳和事务；记录目标未达项，不以手动刷新掩盖失败 |
| SP-05 真实邮件 | 外部邮件账号、域名、投递、限流和找回链路未接入 | 在受控真实邮箱完成注册验证和找回，检查过期、错误次数、防枚举与日志脱敏；保存脱敏投递证据 | 邮件保持待接入/未验证；不能用自动显示验证码或模拟邮件宣称通过 |
| SP-06 发布与更新 | NSIS/DMG/ZIP/AppImage、Helper 签名安装、macOS 签名公证和各平台更新未验证 | 每个标为通过的平台实际安装、更新、卸载，更新后数据与 Helper 兼容，权限和失败提示有证据 | 缺少证书或设备则标待接入/未验证，不发布伪造通过矩阵 |
| SP-07 容量与撤销时延 | 16 人 60 分钟、5 秒撤销、15 秒恢复、Relay 带宽、JVM GC 和 SFU 资源尚无数据 | 在明确硬件、带宽、JVM 参数和受控网络下并行运行真实语音、虚拟网络与游戏/测试流量，保存资源、GC、媒体、丢包和撤销证据 | 依据数据调参或扩容；未达目标如实报告，不修改证据或宣称通过 |
| SP-08 Relay 元数据与媒体信任 | Relay 可见外层目标元数据，LiveKit SFU 可接触媒体明文 | 威胁模型、配置检查和抓包证明无默认路由/明文虚拟网络泄漏；确认日志不含秘密 | 最小化外层元数据和日志；安全文案准确描述信任边界，不宣称未实现的端到端语音加密 |
| SP-09 Java 控制面集成 | Java 21+ 虚拟线程、Spring Data JDBC 显式事务、连接池、LiveKit 管理 API 与 Netty QUIC 的组合未验证 | 运行控制面与 Relay PoC；并发加入/切房保持单活动和地址唯一；验证事务连接边界、超时/背压、ThreadLocal 使用及 LiveKit 撤销；保存依赖版本与测试证据 | 固定受支持运行时和依赖；存在不兼容时停用虚拟线程或替换具体集成，不降低一致性与撤销要求 |

### 验证环境缺口

当前没有已接入的外部邮件账号、代码签名/公证证书及已记录的 macOS/Linux 实机验证结果；也没有 Java 控制面/Relay PoC、三平台 TUN、真实音频或 16 人容量证据。这些项目全部保持“待接入/未验证”，不得以单元测试、Mock 或仅 Windows 结果替代。

## 关键技术决策

| ADR | 决策 | 理由与后果 |
| --- | --- | --- |
| ADR-001 | 使用 Electron + React + TypeScript | 优先复用 Chromium/WebRTC 与 LiveKit 生态以降低真实音频风险；代价是包体和内存，需要严格进程安全及容量测试 |
| ADR-002 | 自托管 LiveKit，不自研 SFU | 快速获得真实多人 WebRTC 能力；接受 SFU 运维和媒体信任边界，撤销采用组合措施而非假设 token 即时失效 |
| ADR-003 | Java 21+、Spring Boot 模块化单体 + PostgreSQL | 首版利用 Java 业务与事务生态实现复杂权限和状态机；以显式事务/锁覆盖单活动、容量和地址，暂不引入业务微服务或消息集群；接受 JVM 资源和框架复杂度 |
| ADR-004 | 持久房间与临时活动分表分状态 | 结束活动不丢配置；活动代次使旧凭据和迟到事件失效，删除房间才终止全部恢复 |
| ADR-005 | 语音和网络双控制通道、双状态机 | 加入后并行自动连接，可独立失败和重试；共同受权威活动撤销约束 |
| ADR-006 | 独立特权 Helper + wireguard-go + 真实 TUN | UI 不持有管理员/root 权限；以窄 IPC 限制系统攻击面，代价是安装、签名和平台适配复杂度 |
| ADR-007 | 首版使用 Java/Netty Relay，中继优先，不做 P2P | NAT 行为可控，Relay 可统一隔离和撤销，且避免离线 Peer 未收撤销；代价是中继带宽、JVM 资源和单点容量，需要 SP-02/SP-07 |
| ADR-008 | WireGuard 端到端密文，外层显式目标映射 | Relay 无需私钥或明文转发网卡；外层目标元数据成为明确隐私边界，映射必须由服务端授权 |
| ADR-009 | 每活动 `/27` + 历史 IP 优先恢复 | 满足 16 人地址和最小路由；有效恢复必须保持，普通重入仅在不冲突且安全时优先恢复，复用不继承权限 |
| ADR-010 | 事务 + 行锁 + 唯一约束 + Outbox + 代次 | 防止双活动、超额、地址冲突和撤销丢事件；消费者必须幂等并处理重试/积压 |
| ADR-011 | 管理记录只追加，消息 tombstone，群解散仅产品不可见 | 满足审计和删除展示；不将逻辑状态夸大为物理删除或后台不可篡改 |
| ADR-012 | 三平台同为发布目标，证据决定支持矩阵 | 不用单平台测试推断其他平台；缺设备、证书或邮件渠道时明确未验证，不伪造发布结论 |
| ADR-013 | 首版使用 Java，保留 Rust 组件级替换能力 | 以版本化 REST/WebSocket、Relay 管理/传输协议和 Helper IPC 隔离实现；可优先评估独立 Relay，但必须用基准、安全审计、兼容测试和单独 ADR 证明收益，替换 Helper 或控制面须另行评审，不在首版承诺 |

## 需求追踪

| 需求标识 | 设计模块/决策 | 验证方式 |
| --- | --- | --- |
| REQ-001 | Identity & Session、Auth API、凭据安全设计；SP-05 | AC-001：真实/受控邮件注册、验证、登录退出、已验证邮箱找回及无绕进入口 |
| REQ-002 | `users/auth_sessions/account_activity`、Activity Coordinator、ADR-003/004 | AC-002、AC-037：未验证接口拒绝，多端登录但并发仅单活动单设备 |
| REQ-003 | Social、好友 API、`friendships/friend_requests` | AC-003：好友全流程、无私聊、好友不授予群/房权限 |
| REQ-004 | Group & Invitation、Message、`groups/group_members/messages` | AC-004、AC-029：群资料/消息/房间重启保持，仅邀请入群且无公开搜索 |
| REQ-005 | Group & Invitation、邀请事务及状态约束 | AC-005、AC-006：邀请撤销/失效、幂等接受、邀请人退群和重入角色边界 |
| REQ-006 | Ownership、`owner_transfers/owner_recovery_requests` | AC-007：转让接受前不变，群主退出限制，争议恢复不自动继任 |
| REQ-007 | 授权矩阵、执行时校验、Revocation Coordinator | AC-008：逐角色接口测试、跨权限/旧页面/旧会话拒绝 |
| REQ-008 | Room Administration、`rooms`、房主接管规则 | AC-009：配置持久化、失群籍接管、普通离线不接管、有效指定 |
| REQ-009 | Activity Coordinator、容量事务、开放/密码规则 | AC-010、AC-011、AC-013：16/17 人、保留占额、暂停、免密码边界及重入 |
| REQ-010 | Room/Activity 状态机、`rooms/activities`、ADR-004 | AC-012：结束清退但保房，删除终止会话与恢复，空房不自动删除 |
| REQ-011 | Activity Coordinator、`account_activity/connection_leases`、代次与 Outbox | AC-014～AC-016、AC-035、AC-037：切房/恢复/争抢、60 秒保留和迟到结果拒绝 |
| REQ-012 | LiveKit SFU、Voice Controller、ADR-002 | AC-017、AC-034、AC-035：16 端真实音频、多人发言、状态、扰动与长会话 |
| REQ-013 | Audio Device Manager、LiveKit JS SDK | AC-018、AC-036：设备选择/测试/音量/静音及权限、拔出、切换 |
| REQ-014 | Network Helper、wireguard-go、TUN、Address & Lease、Relay | AC-020、AC-023、AC-031、AC-034：真实 TCP/UDP、IP 展示复制、三平台与容量 |
| REQ-015 | 活动隔离、`ip_allocations/ip_history`、代次、ADR-008/009 | AC-021、AC-022：跨房失败、有效恢复保 IP、安全可用时重入优先恢复及全新授权 |
| REQ-016 | Helper 路由白名单、Relay 无转发/默认路由、ADR-006/008 | AC-023、AC-024：本机服务可达，互联网不代理、物理 LAN 不转发及错误提示 |
| REQ-017 | Voice/Network Controller 双状态机、独立 Grant、ADR-005 | AC-019、AC-025：分别注入故障，另一项保持并独立重试 |
| REQ-018 | Revocation Coordinator、LiveKit 移除、Relay deny、租约/代次 | AC-026：各失权场景下已有/新建语音、网络、同步和恢复在目标内撤销 |
| REQ-019 | Message、`messages` tombstone、Audit | AC-027：历史保持、作者/管理员删除边界、提示和代删记录 |
| REQ-020 | Audit、`audit_logs`、只读 API | AC-028：字段完整、当前管理员可读、普通/失权/跨群及写改删失败 |
| REQ-021 | PostgreSQL 持久化、群解散状态、备份边界、ADR-011 | AC-029：重启保持，解散产品内不可见且不声称未验证物理删除 |
| REQ-022 | electron-builder/updater、Helper 安装、支持矩阵 | AC-030、AC-031：按矩阵实际安装/更新/卸载与三平台互通，记录权限和游戏版本 |
| REQ-023 | Event & Sync、双状态机、Helper 事件、Compatibility & Diagnostics | AC-024、AC-032、AC-035：真实分域状态、错误、自动/手动恢复及未验证标识 |
| REQ-024 | 安全基线、密钥分权、脱敏日志、最小 IPC | AC-033：日志/管理记录秘密扫描，传输、存储和越权安全检查 |

### 验收入口

- 功能与接口测试必须沿用 REQ/AC 标识；安全测试增加水平越权、跨活动、旧代次、重复请求和并发争抢。
- SP-01～SP-09 的输出是架构风险验证证据；AC-001～AC-037 的通过仍需后续完整测试与发布证据，两者不能相互替代。
- 任何架构变更影响已批准需求、权限边界或验收目标时，必须返回需求影响评估；只改变实现且保持基线摘要时，按工作流重新提交当前架构审批。
