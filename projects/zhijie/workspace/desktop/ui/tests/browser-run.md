# 桌面页面回归

### 满房切换与原窗重试

在工作区设置 `ZHIJIE_TEST_GROUP_BROWSER=true`，运行 `./mvnw.cmd -o -pl control-plane "-Dtest=GroupBrowserIntegrationTest#memberRetriesProtectedRoomWithoutLosingOriginalLease" test`。测试使用独立 PostgreSQL、实际控制面、两个页面和 Rust 客户端。11 个夹具成员先入目标房，群主在页面加入占满第 12 席；原房成员输错密码后改正，看到满房原因，仍持原活动和租约且能续租。群主从页面退房后，成员在同一弹窗重试成功，服务端原租约撤销、目标房恢复 12 人。

2026-11-21 修复前的浏览器记录 `target/group-browser-protected-switch-6741564722205495427/scenario.log` 停在满房提示断言；修复后 `target/room-ownership-82014e1/full-switch-7cf9628-corrected-1.log` 中切房、房主管理及活动协调回归共 9 项通过，无跳过。`target/full-switch-7cf9628/ui.json` 中 178 项页面检查通过，无脚本错误；其中暂停提示使用受控响应，不是跨机实测。本组结果来自源码，不是已分发 ZIP 的验证。

在 `desktop/ui` 执行 `node scripts/build.mjs`、`node --test "tests/*.test.js"`，再执行 `node tests/browser.mjs`。页面回归与 `GroupBrowserIntegrationTest` 共用浏览器查找逻辑：Windows 会检查 Chrome/Edge 的常见安装位置，macOS 检查 `/Applications`，Linux 检查常见的 Chrome、Chromium、Edge 路径。自定义安装目录请用进程环境变量 `ZHIJIE_TEST_CHROME` 指定可信的浏览器可执行文件绝对路径（不是 `.app` 目录，也不是带参数的命令）。显式路径无效会报错，不会换用其他浏览器。

页面回归使用独立的浏览器配置目录和本地 HTTP 服务；其中的桌面桥接是测试夹具。群组集成用例另需真实控制面、PostgreSQL 和测试用 Rust 客户端。两者都不能代替 Tauri 原生窗口、系统授权、真实音频或安装升级的目标平台验收。

### 房主退房与退群的区别

启用 `ZHIJIE_TEST_GROUP_BROWSER=true` 后运行 `GroupBrowserIntegrationTest#assignedRoomOwnerKeepsRoomOnLeaveButGroupOwnerTakesOverOnRemoval`。两个页面分别登录群主和普通成员；群主指定新房主，新房主能改房间资料但不能再次指定房主。退房再加入不改变房主。被群主移出群后，核对成员列表回执、该客户端原生活动快照为空、大厅不再显示房间或通话栏，再核对群主接管的是原房间与原活动。最后从服务端核对房间介绍、所有者和指定房主审计记录。

旧版偶发的两种超时须区分：`target/room-ownership-82014e1/observed-2.log` 在保存后无法打开退房确认框；`confirmed-2.log` 中房主用例已看到成员列表更新，却仍等不到本机活动快照清空。前者由受控页面测试固定在保存成功、后续读取尚未结束的窗口；后者由 Rust 回环测试分别返回成功、租约冲突和临时故障，核对重连时的租约处置。修复后 `renewed-1.log` 至 `renewed-3.log` 分别用新数据库跑房主与双通道两项回归，均无失败、错误和跳过。场景日志中的 `elapsed_ms` 是同一浏览器测试进程的观察时间，不是服务端事务时间，也不是跨机撤销 SLA。

同目录 `full-suite-1.log` 记录完整双客户端集成套件 8 项通过，无跳过；`rust-shell-all.log` 为 42 项通过、3 项需外部夹具的探针默认忽略。`ui-final.json` 记录 176 项页面检查通过。上述结果来自本机源码构建，不是现有 Windows ZIP 或真实音频/TUN 的验收结果。

### 群资料的双端同步与重启持久化

在 `workspace` 启用 `ZHIJIE_TEST_GROUP_BROWSER=true`，运行 `./mvnw.cmd -o -pl control-plane "-Dtest=GroupBrowserIntegrationTest#twoPagesRevokeRemoveAndReinviteThroughTheControlPlane+groupProfileSurvivesServiceRestartAndFreshBrowser" test`。前者在群主转让并转回后从群主页面修改名称与简介，要求仍打开群聊的普通成员无需刷新或操作就看到两个新值，并独立查询数据库核对。后者在同一临时数据库上重启控制面，用新页面重新登录，核对名称、简介和头像。两项使用独立夹具，不把它们描述为同一条长会话。

实跑日志 `target/group-profile-live-9991829/integration.log`：2 项通过，零失败或跳过。成员端检查见 `target/group-browser-social-2183235030775891767/scenario.log`。覆盖实际页面、Rust 测试客户端、WebSocket、控制面与 PostgreSQL，不等于两个原生窗口的跨设备验收。

### 已打开的历史页随群角色变化

在与双端群资料相同的环境下启用 `ZHIJIE_TEST_GROUP_BROWSER=true`，运行 `./mvnw.cmd -o -pl control-plane "-Dtest=GroupBrowserIntegrationTest#twoPagesDeleteHistoricalMessagesAsAuthorAndModerator" test`。成员保持群聊历史页打开，群主先提升、再撤销其管理员角色。测试要求成员无需操作，对他人消息的删除按钮随之出现、消失，而自己的未删消息始终保留删除入口；随后验证实际删除、两端占位、数据库记录和事件。按钮变化不代替服务端授权。

`target/history-role-655625e/before.log` 及 `target/group-browser-history-delete-10666207909424737445/scenario.log` 记录修复前升权未反映到历史页。修复后 `target/history-role-655625e/after.log` 记录同一用例通过，`target/group-browser-history-delete-6910613686922879330/scenario.log` 包含角色同步与删除成功标记。此处验证的是本机双页面与真实控制面，不是跨设备原生窗口验收。

### 群成员被移出时关闭群聊历史与删除确认

同一历史消息用例还覆盖两次在线移除。第一次成员停在有原文的历史页，移出后不操作即回大厅，看不到原文、删除按钮或群主新发的消息。成员用同一会话再次接受邀请，可读历史，但没有管理员删除权，也不会恢复前次成员身份的未发送草稿。第二次群主先提升其角色，等对方打开删除确认后再移出；确认框应自动消失，数据库仍须保留未确认删除的消息。

修复前 `target/history-removal-2f44504/confirmation-before.log` 和 `target/group-browser-history-delete-1161869050233885914/scenario.log` 记录删除确认未自行消失。修复后 `target/history-removal-2f44504/confirmation-after.log` 记录历史与社交两个双页面用例通过（无跳过）；`target/group-browser-history-delete-8472106359251783938/scenario.log` 记录两次移除。`ui-unit.log` 与 `ui-browser.log` 在同一专项目录，分别记录 13 项单测和 172 项页面检查。这些是本机源码测试，不证明旧试用包已包含修复。

### Windows 试用包的页面边框与键盘焦点

登录页通过浏览器实际派发 Tab 按键，检查获得键盘焦点的控件仍有轮廓线，并检查 `html`、`body`、`#app`、`.desktop-app` 没有整页边框或轮廓线。这检查的是页面 CSS，不是 Windows 窗口管理器绘制的外框。

`f6a49f3` 试用包实测：从已校验摘要的 ZIP 读取桌面程序，在程序内定位并解出 10 个页面资源，与上个已核对的嵌入页面逐字节一致。使用解出的实际资源和受控桌面桥接，172 项页面检查通过，脚本错误为零。证据位于工作区 `target/window-shadow-startup-25fdb8d/`：`embedded-assets.log` 含各资源在程序中的偏移和内容摘要，`embedded-browser.json` 列出通过项。对两个独立临时副本分别添加整页内描边、隐藏控件焦点环，两项新增断言分别按预期失败，见 `embedded-negative-controls.json`。未启动产品桌面，不能据此宣称原生蓝边已消失。
