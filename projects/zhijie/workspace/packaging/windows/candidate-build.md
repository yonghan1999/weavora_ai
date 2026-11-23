# Windows x64 未签名候选包

## 当前本机试用（不安装）

当前 Windows x64 未签名试用包：`target/audit-kit-edb4006/zhijie-windows-x64-trial.zip`（79,544,751 字节，SHA-256：`64658fbb66b094aff4325e61c68dc4912fc82a9bb320dcdfeade03d44c151582`）。解压到 D 盘全新短目录，按包内 `README.md` 启动。不要覆盖已用过的目录。

桌面来自 `edb4006`。管理员被降权后刷新或翻页，若服务端拒绝读取，页面会清除旧管理记录并说明原因；暂时限流不会清空已有页面。逐条比对新旧 ZIP 的 369 个条目，仅 `target/desktop/app/zhijie-shell.exe` 和 `target/desktop/manifest.json` 变化；后端、Helper、驱动均未变。

新包同目录的 `payload.json` 记录安装包中八个产品文件的核对结果；`embedded-assets.json` 记录从 ZIP 的桌面程序提取的十项页面资源。旧版在手动刷新失权断言失败（`before.log`）；新版成品页面 181 项通过、无脚本异常（`embedded-browser.json`）。同一组成品页面连接两个 Rust 测试客户端、真实后端和独立 PostgreSQL，验证降权时关闭记录、仍可群聊及重新邀请（`embedded-social.log`，1 项通过、零跳过）。`lifecycle.log` 记录整包三次启停、登录和消息跨重启保留，以及冷备恢复后登录。上述测试没有运行安装器或原生桌面窗口，不证明跨机通话或虚拟网卡通过。

**Windows 原生窗口补测（同一 ZIP，未运行安装器）：** 本机普通用户从 D 盘一次性解压目录启动配套后端和桌面；健康端点返回 `UP`。桌面可执行文件 SHA-256 为 `137872bd0714d4e16a28e63d48807ab5708f43765eaeb46279d27e56eb6d56f5`。Windows 报告了标题为“织界”的主窗口、非零窗口句柄和响应状态；其 WebView2 子进程使用该次解压目录的独立配置。发送正常关闭请求后桌面退出码为 0，随后正常停库，一次性目录已清除。观察记录：`target/native-window-4ae93e0/window.json`、`target/native-window-4ae93e0/lifecycle.log`。

此项只证明包内原生进程创建窗口、加载 WebView2 并能正常退出。窗口自动化连接不可用，未验证页面实际绘制、蓝边外观、原生登录或双窗口交互；未启动 Helper、修改防火墙或试音。

### 历史整包：试用数据库停库检查

前一版：`target/preview-stop-verified-5d3a651/zhijie-windows-x64-trial.zip`（79,544,833 字节，SHA-256：`d88795eedde8787dd3a49d7f933a02bc183cb58f3cdd72205dc235fbc408dd52`）。

这版只改了包内 `backend/scripts/local-preview/stop.ps1` 和 `README.md`；其余 367 个条目字节不变。停止脚本遇到残留的 `postmaster.pid` 或观察到 Java 异常退出时返回失败，不删锁、不强杀。真实试用库三次启停、只读健康检查、原账号和消息跨重启保留，以及离线备份恢复后登录均已验证；日志在新包同目录的 `lifecycle.log`。没有安装软件、启动桌面、试音或改变支持矩阵。

### 历史整包：PostgreSQL 扩展声明

前一版：`target/extension-origins-62e4fa0/zhijie-windows-x64-trial.zip`（79,544,544 字节，SHA-256：`900ca6cff6d65bba76e5f13733ab7d0ca5942bddb1df0a9f996a5e1b688e7701`）。

`comparison.json` 记录 369 个条目：相对上一版仅更新说明书与来源索引，增加 4 份原文。逐一比对嵌套数据库载荷中 27 个第三方 DLL、14 个组件和关联声明的 SHA-256，没有未归属的条目。后三个 DLL 的精确源码版本仍未确认；这不是可复现构建或正式分发批准。发行脚本、项目声明及证据边界见 `postgres-origin.md`。

### 历史整包：ICU 工具及 libxslt 声明

Windows x64 未签名试用包：`target/native-materials-78c5cf4/zhijie-windows-x64-trial.zip`（79,536,751 字节，SHA-256：`ff10cab06690851547fd833b27cf19e189643edc98f3110c9b878a7960897e6f`）。解压到 D 盘全新短目录，按包内 `README.md` 启动。

`comparison.json` 记录旧包 364 个条目仅说明书、来源索引有改动，新包多一份 libxslt 原文。数据库嵌套载荷内的 24 个 DLL 已逐一重算摘要，比对了其引用的声明文件；仍有 3 个文件未配齐材料，不能作为正式分发批准。依据见 `postgres-origin.md`。`lifecycle.log` 记录新包在独立目录首次启动、原目录重启和冷备恢复后的登录、消息与健康检查；未启动原生桌面或安装器。

### 历史整包：跨群返回房间

Windows x64 未签名试用包：`target/cross-group-kit-7f95699/zhijie-windows-x64-trial.zip`（79,535,489 字节，SHA-256：`69de4dc0fecb8cc356a847cdfb7a3798e0399346588c8c218a243109f859514b`）。桌面由 `7f95699` 离线构建：在甲群房间浏览乙群时，即使乙群被解散，仍可返回甲群原房间。

`comparison.json` 核对了 364 个条目，只有桌面程序及其清单变化；`embedded-assets.json` 核对从 ZIP 中的程序解出的 10 个页面资源，`embedded-browser.json` 记录 180 项页面检查通过、无脚本异常。按[成品页面核对](verify-package.md)用旧包和新包页面连接同版本控制面及独立数据库，从聊天页和管理弹窗分别验证：旧包均丢失入口，新包均能返回、保留原租约并续租。对照日志在 `target/room-ownership-82014e1/kit-group-7f95699-old-1.log`、`kit-group-7f95699-extracted-1.log`。整包 `lifecycle.log` 另行记录原目录重启、恢复至新目录后的登录和消息、设备接管、会话清理、暂停入房和健康检查。测试未启动原生桌面、安装器、真实音频或 TUN。

### 历史整包：满房与暂停入房提示

Windows x64 未签名试用包：`target/full-room-kit-6d0288a/zhijie-windows-x64-trial.zip`（79,535,442 字节，SHA-256：`7c4a7e92ecab83aa46974058c5a48ce798d66f38fb47e36340bf475c1ae007b0`）。桌面由 `6d0288a` 离线构建：确认入房时目标房间已满或暂停加入，弹窗会说明原因，仍可重试。

同目录 `comparison.json` 记录新旧包 364 个条目仅桌面程序及清单有变化，原后端与 Helper 未变。`embedded-assets.json` 逐项核对了从新包桌面程序提取的 10 个页面资源；`embedded-browser.json` 的 178 项页面检查全部通过、无脚本错误，旧包对照在 `before.err` 的满房提示断言失败。成品页面使用受控接口响应；真实满房切换及原租约验证见 `desktop/ui/tests/browser-run.md`。

整包回归 `packaging/windows/test-trial-kit-relocation.py` 的 `--check-paused` 在首次启动、原目录重启、冷备恢复副本三次启动中调用真实包内后端。每次均验证暂停期间连群主新加入也返回 `409 ROOM_PAUSED`，房外重新开放后可加入原活动并按租约退出。结果与设备接管、会话撤销及健康检查同记于 `lifecycle.log`。没有启动原生桌面、安装器或真实音频、虚拟网卡。

### 历史整包：房主管理与退房

Windows x64 未签名试用包：`target/room-owner-kit-eb37fda/zhijie-windows-x64-trial.zip`（79,535,587 字节，SHA-256：`c3251d9d7bd56684c46926b03181598221c8f7d93a8c531f8c2e373af4afb496`）。桌面由 `eb37fda` 离线构建：保存房间变更后的刷新阶段不再接受退房点击；同步连接恢复时，客户端重新核对活动租约。

同目录 `payload.log` 核对 NSIS 中八个产品文件；`comparison.json` 逐项比对试用 ZIP，364 个条目中只有桌面程序及其清单变化。按[成品页面核对](verify-package.md)从新 ZIP 的桌面程序中解出十个页面资源（`embedded-assets.json`）；用这些资源跑 176 项页面检查，无脚本异常（`embedded-browser.json`）。旧包页面未通过同一刷新断言，见 `target/room-ownership-82014e1/old-final.err`。`lifecycle.log` 验证解压后的登录、消息、重启、冷备恢复、设备接管、会话撤销及健康检查。原生租约复核的同提交开发证据见 `desktop/ui/tests/browser-run.md`；本次未启动原生桌面或安装器，不能把页面测试当作原生多机验收。

### 历史整包：退群时关闭删除确认框

历史 Windows x64 未签名试用包：`target/history-removal-kit-752ccb8/zhijie-windows-x64-trial.zip`（79,535,221 字节，SHA-256：`d806190d9a77902266020efc33a64264b561b679e0a464cc4f9843d53946f6bd`）。包含 `752ccb8` 退群修复：群成员确认删除消息时被移出，会自动回大厅，不会提交删除。

同目录 `comparison.json` 记录 364 个条目中仅 `target/desktop/app/zhijie-shell.exe` 与 `target/desktop/manifest.json` 改变；后端、Helper、驱动和第三方材料逐项相同。`payload.log` 核对 NSIS 的八个产品文件，`embedded-assets.json` 记录从 ZIP 内程序解出的十个页面资源。旧程序的页面在确认框回归断言失败（`prior-browser-error.log`）；以新程序实际嵌入的资源运行同一测试，174 项通过且无脚本异常（`embedded-browser.json`）。`lifecycle.log` 记录整包后端的登录、消息、重启、冷备恢复、设备接管、票据撤销及停服健康检查。未运行原生窗口或安装器，不代表正式发布或跨平台验收。

### 历史整包：Chromium FFmpeg 署名

历史 Windows x64 整包：`target/ffmpeg-review-8ac0e84/zhijie-windows-x64-trial.zip`（79,534,635 字节，SHA-256：`78df6b26990c620dcba082aa528bc657e4f8fd0f52e1614ee2aa28d9c7f67a90`）。相较当时上一版只更新 README、补入 Chromium FFmpeg 的 `CREDITS.chromium`；编解码行为及程序字节不变。构建开关、与当时可执行文件的对应证据和剩余审查项见[Windows FFmpeg 材料核对](ffmpeg-build-review.md)。该核对不改变阶段或发布批准。

### 历史整包：预编译 WebRTC 总声明

历史整包：`target/webrtc-origin-81487ad/zhijie-windows-x64-trial.zip`（79,521,354 字节，SHA-256：`ca0b8530d81aa271aa63cb8abb94a44bdf27969b24936c2b74b3b684ca395cb4`）。相较上一版只更新 README，新增 `third-party/desktop/WebRTC-win-x64-a545de4-LICENSE.txt`。整包其他文件（包括桌面、Helper 和后端）逐项字节相同。

**原生库的材料来源。** Windows release 的 `webrtc-sys` 链接搜索路径指向工作区 `target/sdk/lib`；实际 `webrtc.lib` 为 89,350,204 字节，SHA-256 `3e5959b5320579814706848a0641224db22f03a374493e90cbd235948977f930`。其同目录 SDK 的 `LICENSE.md` 有 26 节，196,641 字节，SHA-256 `118329b312ed24efd1ae1a7b16c1ce51f7890d30ddc7e7075a04a9cd30c096c2`。已从 [LiveKit 对应 release](https://github.com/livekit/rust-sdks/releases/tag/webrtc-a545de4) 的 `webrtc-win-x64-release.zip` 下载响应中读到完整的 LICENSE、args.gn 和两个 ninja 文件，解压后都与本机 SDK 对应文件完全相同。总声明保留全文，只将 CRLF 规范为 LF；随包文本 SHA-256 `f26ccdf9dc9ca9f6dce44a170346239eb9192526e91b865a6b40ff7170b2e8af`。这不同于上一版仅提供的桥接和构建工具 NOTICE。

上游 API 列出的完整 ZIP 为 114,348,165 字节，摘要 `862403fcb248c7d8803487e4c22b8821c6130e89100cb9c69be95ef45ff46146`。本次连接超时，仅收到前 7,699,391 字节；不能声称已独立验证整个上游 ZIP 或其中的静态库。使用的是已匹配的完整许可成员，不是将截断下载作为构建输入。

**分发前仍需审查。** 从本地静态库按 COFF 对象取无重定位的完整代码段，在当前 shell 的 PE `.text` 中分别找到与 Opus 多通道封装、libaom、OpenH264、FFmpeg、libvpx 和 dav1d 对象相同的片段；对象路径、片段摘要与 EXE 偏移在同目录 `comparison.json`。这不是完整链接图，也不代表用户实际启用这些编码。GN 参数含 `rtc_use_h264=true` 和 `ffmpeg_branding="Chrome"`，发行总声明 FFmpeg 节含 GPL 文本。正式分发前须由负责人核对实际编入的源文件、构建选项、源码供应和专利条件；不得据本材料收集直接批准发布。实际试用包后端回归见 `lifecycle.log`，未运行原生桌面或安装器。

### 历史整包：桌面 SDK 和桥接层声明

历史整包：`target/desktop-materials-fd4eeec/zhijie-windows-x64-trial.zip`（79,476,944 字节，SHA-256：`b965b616853f1c6068b297f591e0ba0f220822170c3cae9729fa317b49322647`）。相较上一包仅更新 README，在 `third-party/desktop/` 增加五份材料。桌面、Helper、Wintun、后端及所有其他既有文件未变。

- **WebView2 SDK 加载器**：锁定的 `webview2-com-sys 0.39.1` crate（SHA-256 `b3a07132775117d6065853d9d1178157b8c90e228de47129d6bce2c7edebedfb`）的 x64 静态库、DLL 和导入库均与 [Microsoft.Web.WebView2 1.0.3800.47](https://www.nuget.org/packages/Microsoft.Web.WebView2/1.0.3800.47) 的对应文件字节相同（NuGet 包 SHA-256 `56c9f26bdd07916a2d1949fb58a5c7e434dfa1173577dca879206050c4e718db`）。MSVC 绑定声明链接 `WebView2LoaderStatic.lib`，库 SHA-256 `89c6b872783b8f6c3cedbff618adb42082d455c615453ae10cfc753f1e8f25d8`。其 `loader_impl.obj` 的一段 80 字节代码与当前 shell 的 `.text` 字节相同（文件偏移 29435286，片段 SHA-256 `893feeb95d7cecdd3a08a57be43024e6923a8baac05764c1cafa4a37cb5f03bc`），这是链接配置之外的制品旁证，不是完整链接图。SDK NOTICE 字节不变，LICENSE 仅补末尾换行。不要把静态加载器与需另行安装的 WebView2 Runtime 混为一谈。
- **WebRTC 桥接**：当前 shell 中可见 `libwebrtc` 标记；包中保留本项目固定的 Apache 2.0 正文及 `webrtc-sys 0.3.52` NOTICE，与已核对归档中的 NOTICE 字节一致。它包含补丁、桥接和构建工具声明，不替代完整 WebRTC 预编译库及其第三方编解码器的材料。
- **Helper**：可执行文件中可见 `boringtun` 标记，项目锁定 BoringTun 0.7.1，包中附[精确上游提交的 Cloudflare 许可](https://github.com/cloudflare/boringtun/blob/051c9d47dc9c5cb36e461b7d36dcd673820dc98b/LICENSE.md)。沿用仓库内已收集的正文，不把 Helper 所有依赖都归入此许可。Wintun 声明仍与驱动同目录。

同目录 `comparison.json` 记录整包差异及 SDK 文件摘要，`lifecycle.log` 记录实际整包后端回归。上述检查没有启动原生桌面、安装程序或授予正式发布批准。

### 历史整包：内嵌数据库原生组件

历史整包：`target/postgres-native-abe2b71/zhijie-windows-x64-trial.zip`（79,464,268 字节，SHA-256：`2cd7967ebef69a23aa71ae53059770de032809438838ebb71f07ec3df881dc87`）。仅更新说明、材料索引和 12 份许可材料，不改程序、数据库文件或迁移。

在实际试用包的 Windows PostgreSQL JAR 内只读遍历 `postgres-windows-x86_64.txz`，找到 121 个 EXE/DLL、60 个扩展控制文件（不表示 60 个扩展已启用）。同目录 `inventory.json` 记录路径、字节、SHA-256 及可读的 PE 版本资源。94 个文件自报 PostgreSQL，21 个文件的产品/版本资源可关联到 ICU、OpenSSL、curl、libiconv、gettext、LZ4、libxml2、Zstandard、wxWidgets 和 zlib。版本字段不是构建来源的独立证明。包内 `third-party/sources.json` 绑定这 21 个 DLL 的字节与补充材料，另保留 6 个没有产品名称的原生文件摘要；打包时校验嵌套归档及所列成员，不解压执行 DLL。

上述 6 个文件缺少产品名称，但不等于发行来源未知。后续[数据库发行包比对](postgres-origin.md)确认：包括这 6 个 DLL 在内的 1,478 个普通文件与 EDB 17.5-1 Windows x64 原包逐文件相同，并有额外证据区分 ICU、libxslt 和调试扩展。试用包未改版。发行来源核查不能代替组件源码、许可义务、桌面原生依赖审核或正式批准。

### 历史整包：顶层 Maven 许可材料

历史整包：`target/notice-sources-884e40f/zhijie-windows-x64-trial.zip`（79,411,840 字节，SHA-256：`d5956c1ff38b938ecb2767a555f095dfba439682d3a11ee056398080d45332e0`）。与上一版对比，桌面、后端、76 个 JAR 以及数据库迁移字节完全一致；只更新了 README 并新增 17 项第三方材料或索引。

对实际整包逐项复核：62 个 JAR 内有 120 个已索引许可或声明条目；余下 14 个通过 `third-party/sources.json` 绑定版本、JAR 摘要和 18 份去重原文。包括 Logback 的两种许可选项、MyBatis NOTICE、PostgreSQL 数据库本体与 Zonky 打包项目的不同声明。精确来源随包可查，正文可离线读取。缺少 JAR 记录、补充材料或摘要不符会中止打包。整包同目录 `comparison.json` 是字节比对记录，`lifecycle.log` 是本地后端回归记录。

这是顶层 Maven 依赖的**材料覆盖**，不是逐项许可审核、完整 SBOM、第三方分发批准或跨平台验收。仍须核查嵌套二进制和桌面原生组件。

### 历史整包：仅附带试用运行依赖

历史整包：`target/runtime-deps-0f82702/zhijie-windows-x64-trial.zip`（79,370,107 字节，SHA-256：`cb58c11e5987c060dee2954e85f770acedd74e2b53dc5948a4594096e5717679`）。相较上一包减少 13,723,886 字节：移除 28 个仅由测试框架引入的 JAR，只改启动 classpath，未改桌面、后端类、迁移、保留的依赖和许可原文。

Maven 离线解析得到 69 个常规运行依赖与 7 个 Windows 本机数据库专用组件（Zonky、Windows 数据库载荷、XZ 和四个 Commons 库）。默认构建不启用试用 profile；两组运行依赖均已独立解析。76 个随包 JAR 均与索引 SHA-256 相符；62 个已有包内许可原文，另两项有上游补充材料（PostgreSQL 材料仅覆盖数据库本体），其余 12 项仍缺原文。此结果不是正式发布批准。

同目录 `distribution.json` 列出保留和移除的文件，`lifecycle.log` 记录实际整包登录、消息、重启、冷备恢复及会话撤销，`backend.log` 记录独立后端同步、房间重开及日志隐私回归。均使用 D 盘隔离目录，没有安装或启动原生桌面。

### 历史整包：首次附带数据库与连接池许可原文

历史整包：`target/trial-license-fab4f23/zhijie-windows-x64-trial.zip`（93,093,993 字节，SHA-256：`d222a373c56c23e722041309783725a3fd0684037293831366a303127c256ffb`）。相较上一包，353 个既有条目字节不变，只更新 README，新增两份可离线查看的原文；桌面、后端、数据库和所有 JAR 字节不变。

已把整包内的 104 个 JAR 与启动 classpath 一一对应，并逐包比对 Maven 二进制材料清单的 SHA-256：64 个 compile、5 个 runtime、35 个 test。test 范围并不意味着未分发。81 个有已索引的 JAR 内原文，每份均与实际包内字节匹配；其余 23 个在现有二进制和源码 JAR 材料目录中均无独立原文。清单另有 3 个不随 Windows 包分发的异平台数据库 JAR。逐项结果在整包同目录 `maven-distribution.json`。

此次为其中两项补充了上游材料，未经正式许可审核：

- HikariCP：[`HikariCP-6.3.3/LICENSE`](https://raw.githubusercontent.com/brettwooldridge/HikariCP/refs/tags/HikariCP-6.3.3/LICENSE)，原文按字节放在 `third-party/HikariCP-6.3.3-LICENSE.txt`。
- Windows 内嵌数据库：已从载荷 `bin/postgres.exe` 确认 PostgreSQL 17.5；[`REL_17_5/COPYRIGHT`](https://raw.githubusercontent.com/postgres/postgres/refs/tags/REL_17_5/COPYRIGHT) 放在 `third-party/postgresql-17.5-COPYRIGHT.txt`。仅对应数据库本体，不覆盖整个 Zonky JAR、其中的 DLL 或扩展。

打包器按 JAR 和原文的双重摘要绑定上述来源。余下 21 项尚无此次补充材料；现有材料仍需审阅，包括嵌套组件、桌面和原生依赖。此盘点不是完整第三方声明或发布许可。

### 历史整包：编译器启动修复

历史整包：`target/trial-compiler-1e00dd2/zhijie-windows-x64-trial.zip`（93,089,065 字节，SHA-256：`0e513ff689b1d0503a9bc0fb93123b34921af1934e3ef54862564eab2494073a`）。与上一整包逐项比对，354 项中只有后端启动脚本改变。保留 `JAVA_TOOL_OPTIONS=-XX:-UsePerfData` 时已验证实际登录、重启与冷备恢复；脚本仍拒绝编译错误。日志在该整包同目录的 `comparison.log`、`backend.log`、`lifecycle.log`。

### 历史整包：首次包含 V18

历史整包：`target/trial-kit-3998fcb/zhijie-windows-x64-trial.zip`（93,088,944 字节，SHA-256：`ba97960287999b340cfe233114257123e5b58dce4316d25223a2f194e9836031`）。

后端更新为 V18，桌面和 Helper 与上一包字节相同，保留群聊历史页角色同步修复。新整包共 354 项：351 项未变，更新了运行权限检查类与包内说明，新增一项迁移。`target/trial-kit-3998fcb/kit-compare.log` 记录逐项比对；`lifecycle.log` 记录实际解压包的登录、消息、重启、冷备恢复、设备资格和会话撤销验证。未运行原生桌面或安装器。后端 V17 升级和回退记录见 `preview-backend.md`。

### 历史整包：群聊历史页角色同步

历史 Windows x64 整包：`target/history-role-kit-32016b8/zhijie-windows-x64-trial.zip`（93,088,073 字节，SHA-256：`fd367af21c3d50309f54e0304ddb7e1baf04e19d2133ee12996bb0c7c2844ac2`）。此未签名包包含 `32016b8` 的群聊历史页修复：成员无需关闭历史页，管理删除按钮随升权和降权更新。该行为的双端验证见 `desktop/ui/tests/browser-run.md`。

以 Git 已提交的构建输入离线生成程序，复用已核对的 Helper、驱动与后端。与上一整包逐条比对，353 项中只有桌面程序及清单改变（`zip-compare.log`）。从新 ZIP 的程序实际提取 10 个嵌入资源，其中 `app.js` 与修复版本字节一致（`embedded-assets.log`）；用这些资源跑浏览器回归，172 项通过、无脚本错误（`embedded-browser.json`）。`payload.log` 核对了 NSIS 内八个产品文件；`lifecycle.log` 记录在独立解压目录验证登录、消息、服务重启、离线恢复、设备接管、会话撤销和健康检查。以上日志均位于新整包所在目录；未运行安装器或原生桌面，不代表签名、真实语音或跨机网卡验收。

### 历史整包：Windows 创建窗口前关闭阴影

历史整包：`target/window-shadow-startup-25fdb8d/zhijie-windows-x64-trial.zip`（93,088,465 字节，SHA-256：`3859cb814023058cfd5d5bdf4faebddb66bf1369ba7e65002debdcec3cf718fc`）。Windows 主窗口从创建时即禁用原生阴影；旧程序是在窗口创建后才关闭，不能排除刚显示时出现强调色细边。此项仅修正启动顺序，不移除键盘焦点提示，不修改系统主题；原生窗口蓝边、拖动和边缘缩放仍待目视与操作确认。

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

## Windows 首次使用：联机授权

机器级 NSIS 包提供桌面端、Helper 和驱动。组织者应在**构建时**为当前进程设置 `ZHIJIE_PACKAGED_CONTROL_PLANE_ORIGIN` 为本轮实际可达的 HTTPS 源（不含路径、凭据和末尾斜杠），并先验证邮件、语音及中继；不向玩家分发服务器配置或命令。未设置时只连接本机回环开发服务，不能把这种包用作双机验收包。不要使用仅供开发的 currentUser 预览包测试提权。

普通玩家从安装包启动应用、进房，在「联机」页点击「授权并连接」→「继续系统授权」。只对受保护安装目录内的配套 Helper 请求 Windows 授权，桌面本身不提权。拒绝后可返回房间继续文字和语音，或再次授权；驱动或目录损坏时应使用机器级安装包修复，不手工替换 EXE/DLL。升级前正常退出桌面，让绑定的 Helper 清理退出后再运行下一候选安装包。自动化 UI 测试不触发或批准 UAC；真机验收需分别记录系统授权、拒绝、升级和双端游戏结果。
