# 交付物索引：织界

以下为项目相对路径。D 盘交付目录通过 `workspace/target/delivery-20261122/` 在本检出中访问。

## 需求与设计基线

- 需求与架构见 `docs/02-requirements.md`、`docs/03-architecture.md`。
- 产品原型、视觉设计及对应入口见 `docs/02a-prototype.md`、`docs/02b-ui.md`、`workspace/design/prototype/index.html` 和 `workspace/design/ui/index.html`。

## 任务与代码版本

任务及证据摘要见 `tasks/tasks.json`。返工规划提交为 `0616333`，TASK-018 的已验证节点为 `38c7133`。当前候选在本次开发自测后由发布 CLI 绑定代码树。

## 构建与测试证据

- `tests/evidence/EXEC-20261121T180755Z-65a33aa8.json` 记录 UI 构建、13 项桌面单测与 11 项容量审计测试。
- `workspace/target/delivery-20261122/kit-relocation.log` 记录试用包解压、登录、重启和冷备恢复。
- `workspace/target/delivery-20261122/embedded-assets.json` 和 `embedded-browser.json`（同目录）记录成品内嵌资源与浏览器检查。
- `workspace/target/delivery-20261122/revocation-focused.log` 记录撤销与限流专项 23 项通过。

## 缺陷处理记录

独立测试的用例与缺陷记录见 `tests/cases.json`、`tests/defects.json`。开发自测中的失败尝试保存在 `tests/evidence/`。

## 发布与回滚说明

发布记录见 `release.json`、`docs/08-release.md`。试用启动与停止步骤随包置于 `README.md`；冷备恢复工具随包置于 `ops/database/cold-backup.py`。

## 交付物索引

`workspace/target/delivery-20261122/zhijie-windows-x64-trial.zip`，79,541,580 字节，SHA-256 为 `6795f6a7794b43fd8560e29629501ed29eb8a36f0e5ba3190dcd058274db206b`。本机试用需要 JDK 25、Python 3.12+、Windows PowerShell 和 WebView2。

## 版本信息

Windows 本机候选对应产品版本 `0.1.0`。H 阶段登记代码版本为 `tree-776ad5115025c7729893827c7719b1bbbe51b04c52c1fd58cffeac0b0f6d66f7`。
