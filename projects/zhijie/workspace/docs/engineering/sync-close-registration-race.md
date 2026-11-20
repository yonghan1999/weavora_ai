# 同步关闭与重连登记竞态

## 问题与确定性复现

原 `SyncService.closeUser` 遍历账号连接并关闭，最后再按账号移除整个连接表。关闭旧连接期间，新连接可完成鉴权并登记到同一账号；最后的整表移除会把新连接一起删除。客户端仍有打开的 WebSocket，但后续事件不再送达。该路径由撤销事件触发，不表示账号本身已永久禁用。

本轮先增加可控关闭回调和并发发送用例。首次 `target/sync-close-race-c5e173f/before.log` 中，重连登记数由预期一变为零；另一个用例因测试 ObjectMapper 未注册时间模块，未进入发送回调。修正夹具并恢复原生产实现后，`before-fixture-corrected.log` 八项中两项失败，分别证明重连被误删及关闭未等待当前发送。首次修复测试的 `fixed.log` 同样保留夹具失败，不作为产品通过证据。

`SyncSessionIntegrationTest` 进一步使用真实 PostgreSQL、Spring HTTP/WebSocket、会话鉴权与提交消息；仅旧服务端关闭回调受控，以固定重连发生在关闭中的顺序。对原实现执行新用例，`integration-before.log` 一项失败：真实新连接已握手，但最终登记数仍为零。没有用整套网络随机时序冒充确定性复现，也没有声称旧端关闭回调是真实慢速网络。

## 修复范围

- 先原子移除本次要关闭的连接表，再逐一关闭其中旧连接；之后登记的新连接进入独立连接表，不被旧回调或最后清理误删。
- 关闭与原有回放/广播共用每连接的监视锁，等待已在进行的发送结束；已关闭连接标志阻止持有旧引用的后续广播或定时校验再次使用它。
- 新连接仍须通过真实会话鉴权，逐次消息的会话和资源权限校验不变。未增加重试、放宽权限、修改协议、限流、票据期限或同步超时。

复用现有连接对象、ConcurrentHashMap 和连接锁，没有全局 IO 锁、新依赖或另一套登记机制（KISS、DRY、YAGNI）。连接生命周期仍集中在 SyncService，鉴权仍由 IdentityService 负责（单一职责）。本修复不修正历史未送达事件，客户端仍须按原快照/游标恢复流程补齐。

## 直接开发验证

所有新增临时文件和日志在 D 盘忽略目录 `target/sync-close-race-c5e173f`，Maven/Cargo 复用 D 盘已有缓存，仅设置当前进程环境。不访问根目录 `tmp/`，不读取或修改既有试用数据库。

- `focused.log`：SyncServiceTest、SyncSessionIntegrationTest 共十项通过，零失败/错误/跳过；随后补充了旧连接 unregister 回调和真实握手后的登记等待，最终版本以下项为准。
- `regression.log`：离线 Maven 对 SyncServiceTest、SyncWebSocketHandlerTest、SyncSessionIntegrationTest、SyncReplayIntegrationTest、SyncCommitOrderIntegrationTest、RevocationDispatcherTest、RevocationIntegrationTest、DisabledAccountIntegrationTest 及三个百账号方法执行回归，共二十五项通过，零失败/错误/跳过，约七分三秒。
- 同一回归的百账号续期：`target/control-load-15757151845821364000`，90.025 秒、3,805 次 HTTP、每账号一次真实凭据续期、51,940 次同步交付、十条指定补发；HTTP/同步错误均零。
- 百账号十轮重连：`target/control-load-6768052781845611391`，90.053 秒、3,798 次 HTTP、60,840 次同步交付、一百条指定补发；HTTP/同步错误均零。
- 百账号普通负载：`target/control-load-16009140265749365909`，90.018 秒、3,707 次 HTTP、51,740 次同步交付、十条指定补发；HTTP/同步错误均零。
- 三组逐账号业务连续性只读复核通过：`rotation-audit.log`、`churn-audit.log`、`ordinary-audit.log`，最大业务间隔分别 20.133、20.088、20.091 秒。它们不证明 WebSocket 始终不间断。
- `node scripts/test-release.mjs --contract` 通过，`release-contract.log`；其中 macOS 实机/签名依赖项仍显式跳过，不算平台验收通过。相关源码空白检查通过。

`regression.log` 的十轮负载期间仍出现一条脱敏 `Sync delivery failed: category=IllegalStateException`；虽然该场景客户端记录零同步错误，现有类别不足以确定发送异常根因，不据此关闭历史间歇同步故障。末尾 `Sync setup failed` 是既有 Handler 刻意注入检查，与此条发送告警区分记录。

额外的 `node scripts/test-compatibility.mjs --validate-matrix` 未通过，`compatibility-contract.log` 报 Cargo 许可材料清单不一致。只读比较 `compatibility-difference.log` 证实唯一不同的顶层字段是 `cargoLockSha256`；`compatibility-lock.log` 证实当前 Cargo.lock 与 HEAD 字节摘要一致，记录的许可摘要不匹配。相关依赖、锁文件、收集工具和许可材料不是本轮改动，不为得到绿灯而改写或跳过该检查；该发布准备缺口继续保留。

## 交付边界

这是已复现的独立同步登记/关闭缺陷，不足以解释历史百账号握手失败或十二端网络数据泵提前退出。真实旧端关闭回调、原生桌面调度、媒体/TUN 停流、跨机、三平台安装、目标服务器和完整 AC-026/032/035 仍须验证。未执行安装器、启动原生桌面或提权，没有重打包/替换现有试用 ZIP；邮件保持默认关闭，用户要求暂不处理的音频问题保持开放。

正式仍为 E_IMPLEMENTATION/in_progress，TASK-019～023 未完成；本记录是直接开发证据索引，不是 CLI 正式执行证据，不推进 F/G/H/I，也不将端到端交付标为完成。

## 后续配套试用制品

源码修复提交后，从已提交 `ea42650` 的 Git 归档独立离线编译后端，不混入工作区历史改动。`target/trial-kit-ea42650/backend-build.log` 构建成功；与上一 `6885213` 包逐条比较，328 项后端和 340 项整包均只有 SyncService 及 Connection 两个类变化，构建输入与包内字节相同，`comparison-final.log`。

- 后端 ZIP：83,810,172 字节，SHA-256 `994a7e99a1b7c4c7f632de67f8c55bba87b47d767a92b0b1932cc9b1d696111d`。
- 完整试用 ZIP：93,048,574 字节，SHA-256 `beaabbd7d25d12a0fe5a9ad957ff6e7671b1d7c9cb79816790bc86d3bcbb7135`。
- `backend-lifecycle.log`：实际独立包登录、消息、重启、两种同步认证、房间重开及日志/管理记录秘密扫描通过。
- `relocation.log`：整包首次启动、停库重启、1,110 文件冷备/恢复后登录及原消息、两设备接管、旧设备拒签语音/网络票据、新设备获票和按租约退房通过。
- `pack-backend-unit.log` 四项、`pack-kit-unit.log` 两项通过；只更新候选索引和入口说明，没有替换用户原目录。桌面仍为原 `399db61`，未启动原生窗口、Helper、安装器或真实 TUN。

本次独立构建和包检查同样只使用 D 盘临时目录。包验证自己的临时库已由原测试入口正常停止并清理；源码归档、日志和两份交付 ZIP 保留。先前许可索引不一致已另在 `069746d` 真实重建核验后修复，见 cargo-license-index-refresh.md，不改写本记录的历史失败。许可审核、历史同步间歇告警、实机和正式阶段缺口仍开放。

后端/数据库退出后，清理本轮 `target/trial-kit-ea42650/source` 与 `temp` 的命令被安全策略拦截，未改用其他工具绕过；两目录暂保留在 D 盘，不宣称已清理。它们与已由测试正常清理的临时试用库是不同目录。
