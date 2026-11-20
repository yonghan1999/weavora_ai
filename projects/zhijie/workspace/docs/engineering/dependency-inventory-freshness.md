# 依赖交付清单过期修复

## 发现与修复

本机真实 Maven dependency:list 与已登记快照不一致。旧清单漏记 SMTP、Actuator 及其传递依赖共十一项；Jakarta Activation 的实际 scope 已从 test 变为 compile。原生成器只读取旧 maven-dependencies.txt，工作区检查只断言三个生态存在，无法发现这类漏项。

generate-license-inventory.mjs 现在每次通过 Wrapper 离线解析当前控制面依赖，再读取锁定 Cargo metadata 和 npm 锁；先完成解析再写出快照及 JSON。--check 只核对，不修改清单，工作区检查调用同一入口。临时依赖结果放于工作区 target 唯一目录，finally 只删除本次文件和空目录。Maven 坐标统一为无 ANSI/模块显示信息的文本，保留范围，支持可选 classifier；未知许可证仍明确 unreviewed，不把坐标补全等同法律审核。

Windows 使用系统 cmd.exe 执行固定 Wrapper 与生成参数，Node 按批处理入口添加 /d 禁用 AutoRun；不修改系统注册表。Maven 输出路径使用相对模块路径，避免本机 CleanCmd 兼容入口处理绝对路径时出错。首次路径/启动失败日志保留于 target/dependency-inventory-before.log、stale-check.log、launch-diagnostic.log 和 current-before.log（后几个均带 dependency-inventory- 前缀），当时未得到有效清单检查结果。

实际 target/dependency-inventory-stale-final.log 退出码一，明确拒绝旧 Maven 快照。随后 target/dependency-inventory-generated.log 退出码零，登记 690 项：Cargo 582、Maven 107、npm 1。较此前工作区 679 项增加十一项：micrometer-core、micrometer-jakarta9、angus-activation、jakarta.mail、HdrHistogram、LatencyUtils、spring-boot-actuator、spring-boot-actuator-autoconfigure、spring-boot-starter-actuator、spring-boot-starter-mail、spring-context-support。没有更改 POM、Cargo.lock 或 npm 依赖版本。

## 验证及范围

target/dependency-inventory-workspace.log 退出码零，工作区检查重新解析并核对所有清单；发布和兼容性契约检查通过。脚本语法及相关文件空白检查通过。最终检查日志 target/dependency-inventory-final-check.log 对最后代码再次 --check 退出码零。保留 Node 批处理调用弃用警告，不把正常 stderr 当成功或失败判据，仍根据实际退出码判断。

复用一个生成入口完成更新和只读核对（DRY、KISS）；构建验证、坐标记录和许可证审核职责分开（单一职责）；不新增依赖或许可证推断逻辑（YAGNI）。本轮只有工具/清单/开发文档变更，现有试用数据、邮件关闭和后端保留。媒体组合失败、真实安装/签名、三平台与目标容量及正式 F/G/H/I 仍开放，不提升整体交付状态。

## 提交快照补充核验

后续 Relay 撤销开发前核对发现，上述生成器、完整清单和工作区入口仍未提交，但已提交的 Cargo 许可材料实际绑定其 690 项完整清单。将此强关联前置维护单独整理，不混入后续新增 `httpdate`。清单 SHA-256 为 `060549a1f11202e5663be464dec6359f93c056db8289d4f63b5fa13c866015c5`，与原归档材料的 `inventorySha256` 完全匹配；Maven 坐标文本与原 HEAD 字节相同。

在 D 盘 `target/relay-revocation-280bca4/inventory-snapshot/` 从暂存区独立检出当前候选快照，验证入口与 `--check` 对该快照重解析 690 项，而非使用包含新 Relay 代码的工作树证明旧快照。首次 `inventory-snapshot.log` 因嵌套快照未显式绑定 Git 根而找不到其相对设计资产，兼容检查失败；仅修正验证进程的 `GIT_DIR/GIT_WORK_TREE`，不改变资产、基线或校验脚本。`inventory-snapshot-recheck.log` 的工作区、完整清单只读核对、兼容与发布契约及三层实际 Cargo 材料重建检查全部通过；真实 macOS 签名检查显式跳过，未当作平台验收。源码、缓存及临时目录全部位于 D 盘，许可证仍未批准。
