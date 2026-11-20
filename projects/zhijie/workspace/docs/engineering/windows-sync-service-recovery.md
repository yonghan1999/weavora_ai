# Windows 试用包纳入同步恢复状态修复

## 来源与制品

仅从已推送 `cc65e0d616b6562a87493d49d58b3769866c96f8` 干净归档构建，不把当前工作区的历史未提交更改混入产品。此次生产变化是同步成功时及时更新服务状态，并在操作锁内复核凭据，旧连接不得污染新会话。真实失败复现、三种断链、凭据旋转与撤销回归见 `sync-service-recovery.md`。

- 整包：`target/trial-kit-cc65e0d/zhijie-windows-x64-trial.zip`，93,080,457 字节，SHA-256 `58f953e1c9cbe478c956e6160739adb10a0166ad5706e10320b565df60706171`。
- 未签名安装器：`target/trial-cc65e0d/织界_0.1.0_x64-setup.exe`，12,095,221 字节，SHA-256 `c92dc4e35c46005c4e4ba6113d32da5b81847d7b741f8e121497461bf84f2d98`。只构建并只读核对，不安装或启动。
- 桌面 SHA-256 `4229e44c6762878e5a389ff14a387dede53a10fad6d17439fb233ec89db59a03`；便携清单 SHA-256 `aa5d17c9d94b4745c8c1c0104989550e7c981f380333fdbe0868b15b9394a5ee`。

构建前核对后端生产源码、Maven 输入、启动脚本及后端打包入口与 `684e0a9` 无变化，直接复用已验证的 `target/trial-kit-684e0a9/preview-backend.zip`，83,840,569 字节，SHA-256 `2628e32733850f00804698f791b42b3fb93a197fc82b460e7cb20c41cefb4ddb`。Helper 生产源码、依赖及锁文件与 `8da8814` 无变化，复用其二进制，SHA-256 `5ab71d304b3b53162da0b2df73c5e7d1e29d8d4c70fa779d36ecbaed640de6c2`。未把重新编译库依赖表述成重建 Helper 运行文件。Wintun 来自既有锁定离线归档，签名核验有效；本轮不安装驱动、不创建适配器。

## 已实际执行

`target/windows-sync-recovery-cc65e0d/` 保存离线构建入口、源码 ZIP、日志及只读核对结果；`target/trial-kit-cc65e0d/` 保存整包及制品回归证据。新增 temp、cache、编译输出均在 D 盘项目忽略目录，使用已有工具链和共享离线缓存，仅更改当前进程环境，没有安装或大量下载到 C 盘。

- `ui-unit.log`：13 项界面单测通过。
- `release-tests.log`：实际优化版 Core 15 项、Shell 40 项通过，零失败；3 个父夹具专用入口默认忽略，不把忽略计为通过。
- `desktop-release.log` 与 `payload.json`：优化版桌面及 NSIS 构建成功，八个产品文件与指定构建输入逐项核对，便携副本摘要一致。未启动原生桌面、生产 Helper 或安装器。
- `comparison.log`：1,340 个归档源文件未被构建改写，348 个整包条目仅桌面程序和清单变化；336 个后端条目与复用 ZIP 逐项相同，未夹入缓存、浏览器数据或数据库。
- `embedded-binary.log` 与 `embedded-browser.json`：从新桌面二进制实际提取 10 份嵌入界面文件，字节与旧版一致，170 项无头浏览器检查通过、无脚本错误。该检查不是原生窗口或音频实机验收。
- `backend-unit.log` 与 `kit-unit.log`：六项打包单测通过。
- `relocation.log`：整包在一次性 D 盘目录通过密码策略、登录、群消息、重启、1,112 文件冷备/恢复、设备接管、旧资格拒绝与当前资格可用；未读取或迁移既有用户试用数据库。
- `backend-lifecycle.log`：复用后端本轮重新通过登录/消息历史、同版重启、Header 与旧查询式两种同步认证、房间重开和普通日志/管理记录秘密扫描。后端和数据库无变化，不重复执行旧库升级/回退，不冒充新增升级证据。

关键日志 SHA-256：`release-tests.log` 为 `bc8bdc5cb0ffa5414a161eabbecec5b35c2bfbff5020b7f994906f0668256833`；`desktop-release.log` 为 `c4bc0d426daffdfe51ed910e74d24557d583e03b39484f43fbd28ff91e7fd871`；`comparison.log` 为 `a98ed1cd1bf330ee584f286339dbb36ca00fff640afe24422f7ddd5e410fb882`；`embedded-browser.json` 为 `23682b5c36616d292a851961ed87b7004d7278b1e2e5e983f428629bebfb4737`；`relocation.log` 为 `feb9203ffb575116103a646ddb98d102ff4aeef18ad0c15e278ad974a29bb140`；`backend-lifecycle.log` 为 `363baf0b923b0edfe813b7d801e4e48f580677957fa6c43d449953e40c9169c1`。

四文件暂存树 `78107a32c0d8f79a2e21db3c53c62dc095b07a9c` 已独立导出完整仓库，工作空间、依赖清单、兼容矩阵、发布契约、性能夹具检查及六项打包单测通过；Linux dpkg-deb 和真实 macOS 签名专项因环境缺失跳过，不当作平台制品通过。`snapshot.log` SHA-256 为 `b5c34cc0049ee20fb883ffb502fa3c84cae16350ae3e1310b0c182972e9f0dc1`。此后只追加结果说明，未改产品、测试或打包实现。

本轮相关进程完全退出后，核验 D 盘绝对路径、逐级重解析点、Git 忽略及进程引用，清理构建源码、展开快照和临时目录共 44,280,754 字节（约 42 MiB），见 `cleanup.log`。保留源码 ZIP、提取界面、脱敏日志、新旧交付物、共享缓存及用户数据，未清理 C 盘其他程序文件。结束 C/D 可用约 10.63/33.75 GiB；C 盘可用空间与开始一致（按 0.01 GiB 计）。

## 使用和未完成范围

先核对整包 SHA-256，再解压到 D 盘全新目录并遵照包内 `README.md`，不覆盖既有文件和数据库。跨版本迁移需先正常停库并核验冷备，回退使用匹配旧包及升级前原库，不合并两份数据库。历史 ZIP、交付物及共享缓存保留；仅在进程完全退出并核验绝对路径、逐级重解析点、Git 忽略及进程引用后清理本轮不用的临时目录。

独立 Relay、目标证书及管理配置不在 ZIP 内，邮件、外部语音及主动外部撤销默认关闭。本轮没有人工试音、管理员 TUN、跨机 TCP/UDP/游戏、真实十二端一小时、三平台安装签名或目标 2 核 2GB/5Mbit 容量验收。许可人工审核、TASK-019～023、正式 F/G/H/I 与人工验收仍开放，正式阶段保持 E；不降低原需求或将打包成功替代完整交付。

KISS/DRY：只重建变化的桌面，复用经源码差异和摘要确认的 Helper/后端、既有打包及验证入口。单一职责：制品核对、界面检查和后端回归分别留证，不以其中一项替代其余验证。
