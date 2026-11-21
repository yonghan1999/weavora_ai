# Windows 试用数据库的发行来源

**结论：** 整包 SHA-256 `2cd7967ebef69a23aa71ae53059770de032809438838ebb71f07ec3df881dc87` 中，Zonky JAR 的 `postgres-windows-x86_64.txz` 含 1,478 个普通文件、74 个目录。全部普通文件都能在 EDB PostgreSQL **17.5-1 Windows x64** 原包的 `pgsql/` 下找到唯一的同路径文件，长度、SHA-256 全部相同。比较的是随包子集，不声称分发了整个 EDB ZIP，也不将一次 HTTPS 下载的摘要当成厂商签名。

**来源链：** Zonky [`v17.5.0` 构建配置](https://github.com/zonkyio/embedded-postgres-binaries/blob/v17.5.0/build.gradle)及[重打包脚本](https://github.com/zonkyio/embedded-postgres-binaries/blob/v17.5.0/scripts/repack-postgres.sh)指向 EDB 下载服务。核对用[原始 ZIP](https://get.enterprisedb.com/postgresql/postgresql-17.5-1-windows-x64-binaries.zip)为 321,800,244 字节，SHA-256 `795196df1b2855fd0c7fb52629c6cc16acaa85819912e732bd4c46863e77eb30`。比对过程中不运行上游脚本、DLL 或安装器。

| 载荷文件 | 从同一原包或实际文件获得的进一步证据 | 仍不能证明 |
| --- | --- | --- |
| `bin/libxslt.dll` | DLL 的 PDB 路径含 `libxslt-v1.1.43`，同一发行包 `pgsql/include/libxslt/xsltconfig.h` 的 `LIBXSLT_DOTTED_VERSION` 也是 `1.1.43`。 | 精确源提交及发行商补丁。 |
| `bin/icutu67.dll`、`bin/testplug.dll` | 两个 DLL 的调试路径均含 `src/icu4c/lib64`，分别指向 `icutu.pdb` 和 `testplug.pdb`。同包 `pgsql/include/unicode/uvernum.h` 声明 ICU `67.1`。 | 头文件是发行级旁证，并非各 DLL 的版本资源，也不能单独证明使用了哪一提交。 |
| `lib/plugin_debugger.dll` | DLL 自含 `pldebugger` 字符串；同包 `pgsql/doc/README-pldebugger.md` 明确把 `plugin_debugger` 指定为该扩展加载的库，并给出 Artistic Licence v2.0 声明。 | `pldbgapi.control` 的 `1.1` 是扩展 SQL/API 版本，不是调试器源码版本；文档的声明不是分发审查结论。 |
| `bin/libwinpthread-1.dll` | DLL 内有 `mingw-w64-libraries/winpthreads/src` 源路径。 | GCC 构建标识不代表 winpthreads 版本。 |
| `lib/system_stats.dll` | DLL 自带 `postgresql-packaging-foundation` 下的 `system_stats.pdb` 构建路径。 | 没有精确上游标签或补丁级别。 |

复核用的同包旁证摘要：`xsltconfig.h` 为 `b6a73d49e1de60548dfc9cafb32cda024348478992c4b4b7bf544db2ae8a80f8`，`uvernum.h` 为 `8fcea2888d2f25ddc9d33f9ee8888129a283eace600260563273d2e739321e0a`，`README-pldebugger.md` 为 `618b0ec6b4522bd2b0704b7acbbd629f5abb7db2356148b4b2426b9a4adc0873`。

2026-11-21 对 `cross-group-kit-7f95699` 整包中的三个 DLL 重新核对摘要、PDB 路径及标识。`icutu67.dll` 含 `icuuc67.dll` 和 `icuin67.dll` 名称；ICU `release-67-1` 的 `icu4c/source/tools/toolutil/toolutil.vcxproj` 和 `icu4c/source/tools/icuinfo/testplug.vcxproj` 分别给出对应输出。两个工具库现关联既有 `native-ICU.txt`；这不是根据文件名推断精确源码版本。`libxslt.dll` 还含 `xsltEngineVersion` 与 `xsltLibxsltVersion`。上游 `v1.1.43` 标签解析为 `71be7a32637760d769b193d95e0c745ecb86b679`，分别按标签和不可变提交读取 `Copyright`，两份 2,968 字节原文的 SHA-256 均为 `7e48e290b6bfccc2ec1b297023a1d77f2fd87417f71fbb9f50aabef40a851819`。试用包保留完整原文；不把来源交叉核对当作厂商二进制可复现证明。`plugin_debugger.dll`、`system_stats.dll`、`libwinpthread-1.dll` 仍单列，未给它们套用其他库的声明。

**后三个 DLL 的归属与材料：** EDB 的 `REL-17_5-1-Win` 对应提交 `8eb581e5828f0c6985624c4b52226f313c8df00c`。其 `versions.sh` 指明 PostgreSQL 17.5；`server/build.sh` 从 EnterpriseDB 的 `pldebugger`、`system_stats` 仓库克隆或更新源码并打印提交号，但没有锁定提交。未取得构建当时打印的记录，索引不填这两个 DLL 的版本。

- `plugin_debugger.dll` 中有 `pldbg_wait_for_breakpoint`。EnterpriseDB 调试器源码快照 `696619729ff7145a9f957932d7c57aeb87061e21` 的 README 换为 CRLF 后正好匹配 EDB 原包 README 的已核摘要 `618b0e…c0873`。保留这份项目说明及其指定的 Artistic 2.0 全文，不以 EDB 材料目录中仅称“Artistic License”的另一份文档替代。
- `system_stats.dll` 中有 `pg_sys_cpu_info` 和 `system_stats/x64/Release/system_stats.pdb` 路径；同版 EDB `server/scripts/windows/compile-system_stats.ps1` 从相同 Release 目录复制 DLL 到 `pgsql/lib`。附上该上游截至发行标签提交日期可查的源码快照 `a4f2c9b089aa4c2f0b3144dc5a49f95ad5214d2b` 的 `LICENSE`，不推断其扩展版本。
- `libwinpthread-1.dll` 中同时出现 `pthread_create` 和 `mingw-w64-libraries/winpthreads/src/{barrier,rwlock}.c` 的实际源路径。附上 mingw-w64 参考提交 `819a6ec2ea87c19814b287e21d65e0dc7f05abba` 在该子目录的完整 `COPYING`，包括 Lockless 声明；不据此将 DLL 标为 v12 或宣称已复现发行商的编译。

**离线复核：** 自行准备上述两个可信 ZIP 后，从 `workspace` 运行以下命令。工具先核对外层摘要，再比对嵌套载荷所有普通文件。缺失、同名歧义、内容不同或特殊成员会失败；成功时在标准输出给出逐文件 JSON，不写入 ZIP 或创建解压副本。

```powershell
python -B "packaging/windows/verify-postgres-origin.py" "target/postgres-native-abe2b71/zhijie-windows-x64-trial.zip" "D:/待核对/postgresql-17.5-1-windows-x64-binaries.zip" --kit-sha256 2cd7967ebef69a23aa71ae53059770de032809438838ebb71f07ec3df881dc87
```

本次逐项结果在 `target/postgres-origin-96f785f/origin-report.json`。不需要为本次核查更换试用包。后续仍需追溯未确定的源版本、核对适用声明及源码提供义务；不能据此宣布正式可分发。
