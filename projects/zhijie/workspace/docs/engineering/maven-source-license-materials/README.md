# Maven 缺失许可原文的补充材料

本目录不是最终产品第三方声明或发布批准。全部材料保持未审核；适用性、双重许可选择、NOTICE/源码与分发义务仍需逐项决定。

## 真实收集结果

上一轮 26 项二进制 JAR 未内嵌独立 LICENSE/NOTICE。实际从 Maven Central 下载全部 26 项精确版本 sources.jar，并核对 Central 旁路校验和；全部源码包也没有独立 LICENSE/NOTICE/COPYING 成员。manifest.json 保存坐标、URL、SHA-256、大小、校验算法与结果，不将无文件改写成有许可证原文。源码包保留在忽略目录 target/maven-license-source-archives，未加入产品或依赖仓库。

official-texts.json 记录六次明确原文获取：Apache 官方 Apache 2.0、Eclipse 官方 EPL 1.0、ASM 项目当前许可页面、XZ Java v1.11 标签 COPYING 均已保存原始字节；GNU LGPL 2.1 与 Creative Commons CC0 下载失败，状态 retrieval_failed 保留。固定版本 XZ 原文是 0BSD 风格授权；ASM 页面含 BSD 三条款及版权归属。通用原文不自动证明对每个依赖适用，当前项目页面也不是不可变的版本授权。

logback-source-headers.json 保存 Logback classic/core 1.5.18 精确源码包内首个 Java 文件的完整开头注释，明确写明 EPL v1.0 或按被许可人选择 LGPL v2.1。该源码注释补充 POM 里的多许可关系，但没有在这里代产品选择许可证，也不声称两个文件代表所有文件/附带组件。原始字节按 SHA-256 去重存于 texts，不修改上游原文或运行 HTML 脚本。

## 可复核性与范围

从 workspace 离线复核已存材料：

```powershell
python "scripts/check-maven-source-licenses.py" --cache-directory "target/maven-license-source-archives"
python "scripts/test-maven-source-licenses.py"
```

无源码缓存时省略 --cache-directory，仍核对已保存文本、哈希、输入清单与状态，不能声称已复核源码归档。当前实际缓存复核通过，26 包 SHA-256/旁路校验和一致，六个材料引用（四项下载文本及两条共用源码头）摘要与大小一致。Central 旁路校验和仅检验传输一致，不是独立签名或来源信任链。

collect-maven-source-licenses.py 只读取上一轮未内嵌材料的精确坐标，固定 Maven Central HTTPS 入口、四个并发下载、十五秒请求超时与大小上限；不执行源码、不安装依赖，不把归档内路径用于输出。输出和缓存目录必须全新，文本按内容摘要命名，避免任意归档路径写入和覆盖用户文件。失败分类保持明确，不自动降低为审核通过。collector 与 checker 各负收集/验证职责，复用固定材料模式，没有扩展为自动法律审批系统（KISS/YAGNI/单一职责）。

工具检查覆盖精确 URL、SHA-256 与显式 SHA-1 回退、旁路不匹配、缺包/缺校验和、非法归档、超大成员、归档路径不作输出路径，以及已保存文本被篡改/越界路径的拒绝。实际日志 target/maven-source-license-collect.log、target/maven-source-license-check.log、target/maven-source-license-tests-final.log。缺失原文问题仍未完全解决；嵌套原生数据库、shaded 组件、Cargo/npm/WebRTC 与驱动未由此完成审核。

最终六项测试与实际来源核对退出零，target/maven-source-license-tests-final.log、target/maven-source-license-check-final.log；兼容、发布和 workspace 检查退出零，target/maven-source-license-compatibility.log、target/maven-source-license-release.log、target/maven-source-license-workspace.log。新增工具/文档空白检查通过，上游原文字节保持原样。target/maven-source-license-development-summary.json 保存本轮材料摘要；产品包及普通桌面哈希仍与 sync-cursor-operation-lock.md 相同，后端 UP。

这是发布准备材料，未修改 dependency-inventory 的未审核结论、支持矩阵、正式任务/执行证据、审批或已批准资产。无产品行为变化，不重建或启动桌面/安装器，人工音频继续搁置，跨机/三平台/目标容量与正式交付仍待完成。
