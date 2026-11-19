# Maven 许可证材料索引

## 已完成范围

当前 107 项 Maven 精确依赖坐标已从本机离线仓库收集 POM 许可证声明、声明来源及原始 JAR 中 LICENSE/LICENCE/NOTICE/COPYING 类文件索引，保存于 maven-license-evidence.json。范围含 compile 64、runtime 5、test 38；82 项直接声明、25 项继承父 POM，81 项 JAR 含相关文件，26 项没有内嵌此类文件。没有把测试依赖当成产品已分发组件，也没有省略它们的审核线索。

每条 POM 来源、JAR 与已读取归档成员均附 SHA-256，仓库内路径为相对路径，不记录用户绝对目录。归档只索引成员，不提取文件，不访问网络或下载依赖；父 POM 仅按精确坐标追溯，忽略 relativePath，不执行模型属性插值。不支持的命名空间、未解析属性、缺失缓存、循环父链或超大文件会明确留下缺口，不以猜测填充许可证。

首次收集发现 Bouncy Castle 1.81 的 POM 没有 XML 命名空间，原工具明确标为 unsupported；核对实际文件后支持 Maven 常见的无命名空间格式，重收集后 107 项均有声明。初版保留于 target/maven-license-evidence-before-namespace-support.json。对未知命名空间继续拒绝，不把任意 XML 当成 Maven POM。

使用 Python 标准库和现有文本快照，无新依赖；只做离线材料索引，不引入许可证名称自动批准映射或通用合规系统（KISS/YAGNI）。精确坐标解析、父链解析、JAR 文件索引各自负责一项职责；--check 复用同一收集流程核对当前缓存，避免用旧 JSON 自证一致（DRY/单一职责）。

## 重现与检查

从 workspace 执行，替换为实际本地 Maven 仓库绝对路径：

```powershell
python "scripts/collect-maven-license-evidence.py" --repository "C:/Users/Worker/.m2/repository" --output "docs/engineering/maven-license-evidence.json" --check
python "scripts/test-maven-license-evidence.py"
```

首次生成去掉 --check，并指定不存在的新输出文件；工具拒绝覆盖已有文件。刷新时先输出新文件、检查差异并保留旧版，再按正常文档变更更新索引。--check 会再次读取并核对 POM、JAR 与索引成员；依赖、声明、原文或输入快照变化时非零退出。

target/maven-license-evidence-collect-final.log 与 target/maven-license-evidence-check.log 实际退出零。七项工具测试退出零，target/maven-license-evidence-tests-final.log：覆盖父链来源与摘要、缺失缓存、父链循环、未解析属性、非法/重复坐标、classifier 隔离、无命名空间/未知命名空间、拒绝覆盖和实际 JAR 文件篡改后的检查失败。它们验证材料工具，不代表依赖许可已审核。

兼容契约现在核对该索引与当前 Maven 坐标快照相同、摘要格式有效并保留未审核结论；完整缓存核对仍通过上述 Python --check 执行，不用结构检查替代真实来源检查。

最终七项检查和实际缓存 --check 再次通过，target/maven-license-evidence-tests-complete.log、target/maven-license-evidence-check-complete.log；兼容/发布契约与工作区核对通过，target/maven-license-evidence-compatibility.log、target/maven-license-evidence-release.log、target/maven-license-evidence-workspace.log。变更空白检查通过，开发材料摘要见 target/maven-license-evidence-development-summary.json。最新 release 包与普通桌面摘要仍与 sync-cursor-operation-lock.md 相同，后端健康 UP；未启动桌面、安装或访问试用凭据。

## 离线原文审阅目录

已从上述精确 JAR 导出 81 项依赖的 154 条许可/NOTICE 引用，按 SHA-256 去重为 60 份原始字节，入口为 [maven-binary-license-originals/README.md](maven-binary-license-originals/README.md)。目录按 compile/runtime/test 分类；26 项无内嵌原文继续显示缺失。manifest.json 保留 POM 声明及来源、JAR 路径和摘要、成员路径和摘要，不把归档成员路径当成输出路径。

```powershell
python "scripts/export-maven-license-review.py" --verify --output-directory "docs/engineering/maven-binary-license-originals"
python "scripts/test-export-maven-license-review.py"
```

导出入口另需 --repository 和不存在的 --output-directory；它先重新收集实际缓存并与证据比较，拒绝陈旧来源和覆盖。离线 --verify 无需本机仓库或网络，逐条与输入证据绑定来源及文档，重算统计、目录和索引；文本篡改、删除文档、修改来源、自报摘要/统计、非法路径均不能通过。输入证据的可信性仍需 collector --check 对实际缓存复核，离线校验不能替代该步骤。

十项工具测试及实际 107 项目录核对通过，日志 target/maven-binary-license-tests.log、target/maven-binary-license-verify.log；兼容契约已调用同一 Python 校验入口，避免另写一套较弱规则（DRY）。只使用标准库及已有收集器，行投影、摘要统计和索引渲染分开且共用（KISS/单一职责），未引入自动许可批准系统（YAGNI）。导出目录全部保持 unreviewed、releaseApproved=false；这不是最终第三方声明，不代表审核或完整交付通过。

最终实际缓存复核、兼容/发布契约、工作区及变更空白检查退出零，日志分别为 target/maven-binary-license-cache-check.log、target/maven-binary-license-compatibility.log、target/maven-binary-license-release.log、target/maven-binary-license-workspace.log。开发摘要见 target/maven-binary-license-development-summary.json；release 包和普通桌面 SHA-256 与 sync-cursor-operation-lock.md 一致，试用后端健康 UP。没有启动桌面、安装或修改试用数据；CLI 仍 E_IMPLEMENTATION/in_progress，无活动任务，此开发摘要不替代正式任务执行证据。

## 仍需审核

后续已检查 26 项精确版本源码包，均无独立许可成员；补收四份官方/项目许可原文及两条 Logback 源码许可头，另两项下载失败明确保留。材料、来源与复核入口见 maven-source-license-materials/README.md。它们补充原始材料，不改变本节审核结论。

全部记录保持 reviewStatus=unreviewed、releaseApproved=false，原 dependency-inventory.json 的 107 项 Maven 许可证未改为通过。声明中的多个名称没有推断为 OR/AND、双重许可选择或产品例外；例如 Logback 的 EPL/LGPL、Jakarta 的 EDL/EPL/GPL Classpath exception 需要结合精确版本原文和实际发布方式决定。

26 项无内嵌文件的坐标可直接从 JSON 的 jar.status=no_embedded_license_documents 定位，须获取并核对相应上游原文；POM URL 本身不是许可证原文。嵌入的数据库原生归档、shaded 依赖、Cargo/npm、WebRTC 预编译库和驱动均未由本工具完整审核。维护负责人、审核日期、原文与 NOTICE/源码义务、产品分发清单和适用例外仍需补齐。收集材料不产生法律判断，也不代替正式发布审批。

本轮仅修改工具、索引和开发文档，没有产品库行为变化，不需重建桌面/安装包。实机、签名安装、目标容量和人工音频缺口继续保留，未推进 TASK-019～023 或 F/G/H/I。
