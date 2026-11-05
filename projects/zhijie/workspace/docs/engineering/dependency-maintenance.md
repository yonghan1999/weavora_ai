# 依赖许可证与维护记录

## 当前结论

- `docs/engineering/dependency-inventory.json` 汇总 Cargo、npm 和 Maven 坐标；其中 `unreviewed` 项尚未完成许可证审核，不能解释为已获发布许可。
- 当前仅核对锁文件、依赖坐标和维护风险，不代替三平台构建、真实媒体/TUN、签名更新或法律审查。
- 维护负责人、审核日期、许可证原文、NOTICE/源码义务及例外批准均待补齐；缺失项在发布材料中保持开放风险。

## 候选与维护成本

| 范围 | 当前候选 | 维护记录与待验证事项 |
| --- | --- | --- |
| 客户端核心、Helper、Relay | Rust workspace | 复用核心状态与安全边界；真实 TUN、音频、撤销和三平台组合仍需实测 |
| 桌面容器 | Tauri 候选 | 包体、系统 WebView、媒体设备、Helper 签名和更新未形成三平台证据；Electron 只作有数据的对照 |
| 媒体 | LiveKit 候选 | 具体 SDK、AEC/降噪/AGC/VAD/DTX、许可证及 SFU 运维成本待 SP-03/07 定案 |
| 控制面 | Java 21+、Spring Boot、MyBatis、PostgreSQL、Flyway | 锁文件和 Maven 坐标已记录；许可证文本、目标机资源及 SFU 管理 API 兼容性待核验 |
| UI | 现有 Web UI 与 npm 锁文件 | 保留已批准资产；生产适配差异、依赖许可证及可访问性继续追踪 |

## 更新与审查规则

依赖升级须重新生成 inventory 和 release manifest，审查安全公告、维护活跃度、许可证义务与平台影响。新增 Go、替换桌面容器、SFU 或传输实现必须提交 ADR 和具体成本证据，不以依赖名称推断维护性或许可证合规。
