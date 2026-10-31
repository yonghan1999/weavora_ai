# WEAVORA UI 视觉设计复核

日期：2026-10-30 UTC。工作流 WF-zhijie，阶段 B2_UI，任务 UI-B2-004（客户端直传改基线）。结果：`waiting_human`。

## 输入与范围

依据 han 人工批准的需求 APR-B-0ebffa236a4e 与 B1 APR-B1-15e3bfc1a414，覆盖 REQ-001～REQ-029、AC-001～AC-048。B1 摘要为 `c07383892d558fd21859ef0c6a2c9c943868144e507cf3a50951869163e144d4`。同一 AI 顺序承担 `ui_designer` 与 `design_reviewer` 职责，未调用独立子代理，不将自测称为人类复核。

仅控制面及 Lighthouse 在线、客户端保存和直传、无在线副本暂不可用是当前基线。本轮保留已有视觉系统，更新语音等待隧道、底层失败归因、副本状态条、待同步/部分送达/对端接收收据、组件工作台及交接契约。B1 业务逻辑继续复用，已批准源文件不修改。

## 产物

- UI 规范与 29 项 REQ 追踪：`docs/02b-ui.md`；交接契约：`docs/02b-ui-handoff.md`。
- 产品入口：`workspace/design/ui/index.html`；呈现与语义：`presentation.js`、`ui.css`。
- 组件工作台：`system.html`、`system.css`、`system.js`；网络依赖及三种副本模式可操作演示，不串改产品数据。
- 构建/回归：`build-share.cjs`、`test-ui.cjs`，无新增第三方依赖。构建统一换行符，避免 Windows 下分享版残留外部样式链接。
- 测试通过 `WEAVORA_UI_OUTPUT_DIR` 定向到工作区本次临时目录，在其中验证产品/工作台内联版本，结束后检查路径并清理。默认分享另生成根 `tmp/weavora-ui.html`、`tmp/weavora-design-system.html`，本轮不读取根 tmp 内容。
- UI 截图包含 `ui-peer-offline-600.png`、`ui-peer-partial-600.png`、`ui-network-failure.png`、`ui-media-selected.png` 及既有页面/媒体状态截图。

## 实际验证

在 `projects/zhijie/workspace/design/ui/` 执行：

1. `node --check presentation.js`、`node --check system.js`、`node --check build-share.cjs`、`node --check test-ui.cjs` 均退出码 0。
2. 最终 `npm test` 退出码 0：共享交互 179 项、UI 检查 69 项，共 248 项通过，`consoleErrors: []`。共享回归比 B1 入口多两项单文件检查。
3. 覆盖 1440/900/600 px、指定色彩对比度、键盘焦点、字段反馈、16 人滚动、媒体预览/查看/重试、少动态、无副本本机待同步、部分送达、未入房群同步、共享隧道失败/恢复及工作台。单文件在浏览器模拟断网后验证，不证明真实网络能力。
4. 首轮在工作台分享版外部资源检查失败，定位为构建脚本按 LF 匹配而系统文件含 CRLF；统一构建输入换行符后完整重跑 242 项通过。目视检查随后发现成员卡仍显示旧连接/说话状态；在 UI 层派生有效语音状态并移除不实说话高亮，新增三种宽度下成员卡及成员列表断言，再次完整重跑。
5. 已目视检查无副本与部分送达的 600 px 群聊、直连失败与媒体选择截图：副本通知不被标题遮挡，收据含文字及模拟标记，操作区可见；最终重跑后再次目视检查 `ui-network-failure.png`，成员卡均显示语音等待，已移除旧说话高亮；另检查 `ui-room-600.png` 与 `ui-design-system.png`，未发现本次范围内的裁切或横向溢出。截图不是人工审美或真实设备验收的替代品。
6. `python zhijie.py validate zhijie`：B2 文档结构、REQ—UI 追踪、可点击入口、本地链接及前置基线一致性通过，0 错误、0 警告；提交前再次运行。
7. `npm run share` 退出码 0，生成两份默认分享文件；未读取根 tmp。`git diff --check` 限定本轮 UI 文档和 UI 目录，退出码 0，仅有换行符转换提示；无 Git 提交、推送或分支操作。

共享回归不更新 B1 截图，UI 截图只写 UI 目录。B2 不伪造 F/G/H 执行证据或审批文件，门禁与审批请求均由 CLI 生成。

## 人工复核与风险

走查入口：大厅 → 入房 → 隧道失败/语音等待 → 重试恢复 → 群/房间文字和媒体 → 本机待同步/部分送达/副本恢复 → 图片查看和焦点返回 → 设置/退出。组件工作台另演示无副本、部分成员、在线副本状态。重点检查状态是否一致、无副本提示是否清楚、窄窗口及错误修复是否可操作。

未验证：真实邮件/账号、点对点音频和游戏、虚拟网卡、NAT/禁 UDP/CGNAT 逐成员矩阵、服务器流量与内容零存储证明、接收端校验落盘 ACK、磁盘满、重复乱序收敛、短期授权、重连先同步 tombstone、5 秒撤销及临时文件清理。当前 Object URL 和定时器不是 AC-038～AC-048 的真实验收证据。macOS/Linux 实机、16 人长会话、全部辅助技术/颜色合成、200% 缩放亦未验证。

无在线副本时缺失内容暂不可用，最后副本丢失不保证恢复；服务器无透明回退。控制请求仍有流量，不能称零服务器流量。B2 人工批准后才进入 C 重写架构，旧架构中的中心媒体服务不能沿用为当前方案。按 `workflow/spec/WORKFLOW.md` 第 4 节等待人工决定，不自动部署或执行 approve。
