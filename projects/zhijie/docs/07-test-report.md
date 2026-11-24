# 独立测试报告：织界

## 测试结论

**本地独立回归通过；本轮 Windows 玩家现场验收尚未完成，不提请发布评审。**

五项必需检查均以 `test_engineer` 角色重新执行，退出码全部为 0。各次执行前后代码树摘要均为 `4c4c855c7f4ba22afde5c9e01d3fa7559360264c92e35a22668263657dd55d9c`。下表的证据文件均已与 `checks.json` 的 SHA-256 核对。

为便于异机评审，本轮回环制品的两个文件清单和两份成品原生测试观察结果已归档到 `docs/qa/windows-first-use-local/`。其中 `registration.json`、`protected-switch.json` 是本机自动化补测，不是两对玩家的现场记录，也不是工作流执行证据。

| 独立检查 | 实际结果 | 工作流证据（相对项目） |
| --- | --- | --- |
| `independent-full-regression` | 完整回归退出 0。Rust 171 项通过、20 项显式忽略；UI 单测 16 项通过；双页面及垂直切片共 16 项通过、零跳过。 | `tests/evidence/EXEC-20261124T054309Z-5270f4fa.json` |
| `room-invitation` | 三种受邀身份均进入指定房间，3 项通过、零跳过。 | `tests/evidence/EXEC-20261124T055216Z-a5849412.json` |
| `independent-first-use-browser` | 195 项通过，无页面脚本异常。 | `tests/evidence/EXEC-20261124T055648Z-6fe54a93.json` |
| `external-room-link` | 外部分享页到客户端确认，1 项通过、零跳过。 | `tests/evidence/EXEC-20261124T054824Z-6a40cad8.json` |
| `invitation-https-edge` | 两个分享资源通过 HTTPS 只读比对；非产品路径仍为 404。 | `tests/evidence/EXEC-20261124T044314Z-8dff55ae.json` |

`DEF-TC-ROOM-INVITATION`、`DEF-TC-EXTERNAL-ROOM-LINK` 和 `DEF-TC-LOCAL-REGRESSION` 均已由独立复测关闭；历史失败及修复过程保留在下文。此结论仅覆盖本地自动化，不是发布准入。当前未获得玩家可达的 HTTPS 测试源，也没有两对首次玩家用同一最终包完成安装或升级、真实双向听辨和同局十分钟的记录；不提交 H。

## G 当前轮次：Windows 首次体验与房间邀请

此前的 G 结论均对应历史代码树。首次执行 `TC-ROOM-INVITATION` 时，三种身份的发送端均未能生成指定房间的邀请码，工作流建立 `DEF-TC-ROOM-INVITATION`。原始失败证据：`tests/evidence/EXEC-20261124T030814Z-d63b7655.json`。

修复任务：`tests/evidence/EXEC-20261124T032740Z-fe94eb6a.json`；独立复测：`tests/evidence/EXEC-20261124T033141Z-45e6d849.json`。两次均退出 0，三项执行、零跳过，代码树均为 `0bc64dba4178133806b3ed4c2ec326b36c58afafab636ebe8bd77d3c318ff76f`。两个产品页面通过 Rust 客户端连接控制面和 PostgreSQL：已注册非成员、已有管理员、发码时未注册的玩家都进入了指定的第二个房间，没有误入列表首项或降级管理员。注册前获邀场景使用受控测试收件端，不是外部邮件到达证明。

独立浏览器检查：191 项通过、无脚本错误，证据 `tests/evidence/EXEC-20261124T033610Z-e139706c.json`。新增断言核对复制的正是当前邀请码、剪贴板被拒时可手动选中，以及关闭弹窗后页面不保留复制框。

已独立验证的 G 回环包实现的是按验证邮箱核销的**邀请码文本**：玩家在大厅输入后，客户端请求进入邀请指定的房间。该包未实现由外部链接直接打开桌面端。未进行两对新玩家的干净安装／升级、实际邮件、双向复述、静音恢复和同局十分钟测试；`06a-windows-first-use-field-test.md` 仍为空白现场记录单。本轮重建了未签名的 Windows x64 回环包（SHA-256 `cb2c023e7fdba6a98efa056b1db6c68f5bf403783fa62ab7b9736c21082b1e64`），八个内嵌产品文件均与本次输入一致；比对清单和制品在 `workspace/target/first-use-qa-tools/packages/G-loopback-cb2c023e7fdba6a98efa056b1db6c68f5bf403783fa62ab7b9736c21082b1e64.json` 及同名 `.exe`。未运行安装器或申请提权，本包不发给玩家，不提交 H 评审。

### 首次完整回归失败与修复经过

`TC-LOCAL-REGRESSION` 全量独立证据 `tests/evidence/EXEC-20261124T040659Z-7b0d6beb.json` 退出 1，工作流建立 `DEF-TC-LOCAL-REGRESSION`。双页面测试 13 项中 1 项失败、0 跳过：历史消息场景在成员被移除并退回大厅后，仍从大厅直接寻找群邀请按钮。该入口现用于定向入房码；普通群邀请在「群组」页。已修正场景的实际点击路径，保留被移除后清空历史、重新入群和权限断言；开发侧重跑原失败方法 `twoPagesDeleteHistoricalMessagesAsAuthorAndModerator`：1 项通过、零跳过（`workspace/target/first-use-qa-tools/g-history-readmission.log`）。后续工作流全量复测通过，见文首，不以专项结果代替。

### 回环包中的成品页面与外部邀请入口

从上述 G 回环包对应且已校验摘要的桌面 EXE 提取 10 项内嵌页面资源并逐项比对。在这些资源上重新运行浏览器夹具：191 项通过，脚本错误 0。另以隔离的受控账号走登录、入房、邀请队友、生成邀请；实际弹窗只给出只读文本码，没有可从应用外打开的邀请，专项预期断言失败，脚本错误 0。该失败与已通过的文本码核销是两个不同的验收项。未启动安装器，未证明操作系统唤起或已登录窗口接管。

可复核记录：`workspace/target/first-use-qa-tools/packages/embedded-cb2c023e/` 下 `embedded-assets.json`、`embedded-browser.log`、`link-baseline.mjs` 与 `link-baseline.log`。不含真实邀请码。此项缺口关闭前，不将文本码专项的通过记为「打开邀请」通过。

### 外部邀请修复（当前开发树，尚非该回环包）

已登记 `TC-EXTERNAL-ROOM-LINK`。旧实现的独立失败证据为 `tests/evidence/EXEC-20261124T041438Z-0fd57260.json`（1 项实际执行、零跳过，发送端无可外部打开的链接）。新开发树添加同源邀请分享页、Windows 协议配置、只接收规范邀请的单实例入口，以及不自动核销的应用内确认。手动邀请码入口保留。开发侧浏览器 195 项通过、无脚本异常，Rust 邀请参数及一次领取测试 2 项通过；它们不是安装器注册或实际浏览器唤起的证据。开发侧 `node scripts/test-e2e.mjs --external-room-link` 已通过（1 项、零跳过，`workspace/target/first-use-qa-tools/external-room-invite-draft/link-integrated-2.log`）：从真实发送页面取链接，在浏览器沙箱加载控制面的分享页，将其生成的协议 URL 交给 Rust 测试接收端；确认前没有活动，确认一次后进入 B 并核对数据库占位。沙箱不调用 Windows 协议处理器，故不作为安装版唤起证据。旧包不含此修复，需重新构建、核验并在工作流和真实安装环境复测。

### 新回环包、原生打开与 HTTPS 分享入口

- **Windows x64 成品**：`workspace/target/first-use-qa-tools/packages/G-link-loopback-6e66e60565f7a80d3b2bba38fab24b142cfb782fbd1d5dcbff390badd71c369c.exe`，SHA-256 `6e66e60565f7a80d3b2bba38fab24b142cfb782fbd1d5dcbff390badd71c369c`。同名 `.json` 记录八个包内文件。已从相同摘要的桌面 EXE 提取并核对 10 个页面资源，成品页面浏览器检查 195 项通过、脚本错误 0（`packages/embedded-link-6e66e605/`，相对 `workspace/target/first-use-qa-tools/`）。它是未签名回环包，不给玩家。
- **原生窗口**：以包内同一桌面 EXE、真实 WebView2、专用本机账号／PostgreSQL 测试。用合成协议参数冷启动，登录前不呈现入房确认；登录后出现「打开房间邀请」。在已登录窗口关闭上一次弹窗后再次启动同一 EXE，第二实例退出码 0，原窗口出现新的确认框；取消后不再显示。没有点击加入、录音、创建网卡或提权。`native-link-journey-3c136a6d/` 下 `cold-observation.json`、`ui-observation.json`、`warm-process.json` 对应本轮记录。`nsis-registration.txt` 是同次生成的安装脚本片段，包含协议注册和仅在处理命令仍属本程序时清理；**未执行注册表分派，不能据此判安装、升级或实际浏览器的系统确认通过**。
- **HTTPS 入口**：同版前后端从直连进入 B 的工作流独立证据为 `tests/evidence/EXEC-20261124T042744Z-a42117b0.json`，原邀请码复测 `tests/evidence/EXEC-20261124T043136Z-6d2b1ae5.json`。随后在实际 nginx 回环 TLS 测试复现：原配置将两个分享资源均返回 404（Spring 直连均 200）。修复后两个资源 GET 200 且内容逐字节一致，HEAD 200、POST 403，根路径、未知资源与私有端点仍为 404。没有把 `/join/` 整段或后端根路径放行。记录：`native-link-journey-3c136a6d/edge/{before,after}/result.json`、`edge/https-wss.log`；API、WSS 和按文档 feature 构建的 Rust 同步探针亦在隔离服务通过。使用临时测试证书，未部署公网或改系统信任。
- **组织者后端制品**：离线 Maven 打包得到 `packages/G-link-control-plane-bf93bd5fa65d3356f7907523d13c05eb5d0094460efb9e56f6a4464817e1e45e.jar`（SHA-256 `bf93bd5fa65d3356f7907523d13c05eb5d0094460efb9e56f6a4464817e1e45e`）。已核对可执行 JAR 的入口，以及分享页、脚本和 V19 迁移与源文件逐字节一致；本节的 TLS 运行证据来自同版编译类和真实临时数据库。归档 JAR 的直接启动和首次注册补测见下节。该后端包不是玩家安装包。

本节的原生准入与 HTTPS 修复均不替代真实邀请的系统唤起、真实 HTTPS 测试源及两对首次玩家的现场记录。HTTPS 分享入口已由 G 独立检查 `tests/evidence/EXEC-20261124T044314Z-8dff55ae.json` 对新代码树 `4c4c855c7f4ba22afde5c9e01d3fa7559360264c92e35a22668263657dd55d9c` 复测通过；同树完整回归的最终通过证据见文首。

### 成品后端与原生桌面：首次注册后使用原邀请

本机补测直接用 `java -jar` 运行上述归档 JAR；不从 Maven 测试上下文启动。桌面 EXE 与上述回环包内摘要相同，使用全新 WebView2 配置，连接隔离 PostgreSQL。先为**尚未注册**的收件人签发指向第二个房间 B 的真实邀请，再将协议参数交给新桌面进程，全程未把房间 ID 填进玩家界面。

- 玩家从原生窗口点击注册。正式邮件适配器向受控回环 SMTP 接收端投递 MIME 邮件；校验收件人及主题后，在内存中把邮件验证码送回原生验证表单。邮箱验证前直接尝试登录被拒绝，验证后由用户界面正常登录。
- 无需重新发码或粘贴邀请码。登录后的待确认弹窗出现时，用只读数据库查询受邀人在 A、B 的有效占位分别为 **0、0**；点击一次「加入房间」后，原生页面标题为 B，占位分别为 **0、1**。桌面正常退出，临时数据库、邮件接收器和打包服务已停止。
- 本地观察结果及辅助脚本在 `workspace/target/first-use-qa-tools/packaged-mail-first-use/`（`observation.json`、`check.py`、`player.ps1`、`FirstUseDatabase.java`）。不记录密码、验证码、邀请码或会话令牌。这是**独立于工作流的成品补测**，不替代 G 全量回归。自动化启动到进房的耗时不计作“五分钟内双方听清”；没有外部邮箱、浏览器到已安装处理器的分派、真实听辨、双机游戏或玩家现场记录。

**已有房间切换到密码房：** 对同样的两个制品另起全新库与桌面配置，先用首次玩家的邀请进入 A，再向已登录的原生窗口转交 B 的定向邀请。第二实例正常退出。玩家输入错误房间密码，原窗口明确提示「房间密码不正确」，数据库中该玩家在 A、B 的有效占位保持 **1、0**；在同一确认框输入正确密码后变为 **0、1**；主动离开 B 后为 **0、0**，桌面正常关闭。没有绕过密码、误进列表第一项或产生双重占位。记录：`workspace/target/first-use-qa-tools/packaged-invite-protected-switch/observation.json`。本轮未验证实际音频或游戏流量是否持续。

## 2026-11-22 本机候选复验

`TC-LOCAL-REGRESSION` 在 G 阶段由 `test_engineer` 对代码树 `776ad5115025c7729893827c7719b1bbbe51b04c52c1fd58cffeac0b0f6d66f7` 重新执行，前后摘要一致，退出码 0。证据为 `tests/evidence/EXEC-20261121T194639Z-0df1fc38.json`。本轮执行 Maven/PostgreSQL、Cargo、UI 单测、真实后端双页面场景、版本配置、容量工具夹具和兼容性清单校验。REQ-001 至 REQ-028 的用例映射见 `tests/cases.json`。在本机运行的两组桌面端到端测试共 12 项，零失败、零跳过；整包另行复核的操作见交付索引。

开放工作流缺陷为零。跨机、真实听辨、目标容量、外部邮件、三平台安装更新与签名均保留未验证。以下为 2026-11-05 的原基线回归和逐项范围记录。

## 实际执行记录

2026-11-05，`test_engineer` 在 G 阶段对当前代码树执行 `TC-LOCAL-REGRESSION`，完整运行 Maven、真实 PostgreSQL、Cargo、UI、垂直切片、发布配置、性能和兼容性检查，退出码 0。证据：`tests/evidence/EXEC-20261105T011815Z-f0b369f0.json`。执行前后代码树摘要均为 `b070236aa74142612a11f05ec05279acebd693ae2edad770233808e61582982d`。

## 失败用例与缺陷

TASK-015 此前两次登记失败（Logback 转换器 API、Authorization/Bearer 脱敏顺序）均保留原始证据并在修复后通过；E 阶段重试耗尽由人类通过审计命令解除。G 阶段 `TC-LOCAL-REGRESSION` 未失败，`tests/defects.json` 无开放缺陷。

## 需求覆盖结果

| 需求标识 | 用例结果 | 证据路径 |
| --- | --- | --- |
| REQ-001 | 本地契约通过 | tests/evidence/EXEC-20261105T011815Z-f0b369f0.json |
| REQ-002 | 本地契约通过 | 同上 |
| REQ-003 | 本地契约通过 | 同上 |
| REQ-004 | 本地契约通过 | 同上 |
| REQ-005 | 本地契约通过 | 同上 |
| REQ-006 | 本地契约通过 | 同上 |
| REQ-007 | 本地契约通过 | 同上 |
| REQ-008 | 本地契约通过 | 同上 |
| REQ-009 | 本地容量事务通过；目标机容量未验证 | 同上 |
| REQ-010 | 本地契约通过 | 同上 |
| REQ-011 | 本地契约通过 | 同上 |
| REQ-012 | 适配/工具通过；真实语音、长会话和音质未验证 | 同上 |
| REQ-013 | 设备配置/模板通过；真实设备未验证 | 同上 |
| REQ-014 | Helper/Relay/矩阵契约通过；实机互通未验证 | 同上 |
| REQ-015 | 本地隔离与恢复通过 | 同上 |
| REQ-016 | 路由边界契约通过；实机路由未验证 | 同上 |
| REQ-017 | 双通道本地契约通过 | 同上 |
| REQ-018 | 本地撤销与代次通过 | 同上 |
| REQ-019 | 本地消息契约通过 | 同上 |
| REQ-020 | 本地审计与脱敏通过 | 同上 |
| REQ-021 | 真实临时 PostgreSQL 迁移/重启通过；生产备份恢复未验证 | 同上 |
| REQ-022 | 发布配置通过；三平台安装签名更新未验证 | 同上 |
| REQ-023 | 本地状态与诊断通过 | 同上 |
| REQ-024 | 本地安全契约通过；外部渗透未验证 | 同上 |
| REQ-025 | 背压与负载工具通过；真实容量未验证 | 同上 |
| REQ-026 | A/B 工具通过；真实听辨与弱网音质未验证 | 同上 |
| REQ-027 | 本地策略契约通过；真实流量路径未验证 | 同上 |
| REQ-028 | 锁文件、维护记录、证据索引与资产摘要通过；许可证审核未完成 | 同上 |

## 回归测试

G 使用与 F 相同的完整检查集合重新取证，而非引用任务或自测结论。发布配置清单无陈旧差异，59 个已跟踪设计资产摘要保持 `unchanged`，证据索引中的哈希均匹配。

## 未验证事项

生产邮件、外部 SFU、真实设备音频与 A/B 听辨、真实 TUN/直连/Relay 组合、三平台安装/签名/更新/卸载、游戏版本与跨平台互通、目标 2 核/2 GB/5 Mbit/s 下 C0～C4 容量、12 人 60 分钟和 100 人在线/分房均未执行。生产数据迁移、外部安全渗透和许可证法律审核亦未完成。上述项目均保留未验证，不由本次本地通过结果推断。

## 历史基线测试结论（不适用于本轮 G）

当前代码树的本地独立回归门禁通过，REQ-001～REQ-028 已映射到 `TC-LOCAL-REGRESSION` 且无开放缺陷，可准备 H 发布材料。结论仅覆盖实际执行的本地自动化范围，不表示 AC-001～AC-044 全部验收、生产就绪或已发布。
