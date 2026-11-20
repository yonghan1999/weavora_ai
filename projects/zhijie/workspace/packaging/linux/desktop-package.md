# Linux 桌面候选包

Linux 使用 `zhijie` 作为稳定的 Debian 包名，窗口标题和 Debian 中文菜单名仍为「织界」。此配置不安装特权 Helper，不创建网卡或系统服务，不改变 Windows/macOS 包名。AppImage 和 Debian 包均为候选，未在 Ubuntu 上实测。

在目标 Ubuntu x86_64 机器上构建后，从项目 `workspace/` 对实际 `.deb` 做只读核对（用实际路径替换示例；需要 Python 3 和 `dpkg-deb`）：

```sh
deb="target/release/bundle/deb/实际文件名.deb"
python3 -B packaging/linux/check_deb.py "$deb"
```

检查器核对包名 `zhijie`、架构 `amd64`、项目版本；从包内发现唯一菜单项，核对中文名及 `Exec` 对应程序的权限和 ELF64/x86-64 文件头、`Icon` 对应图标的 PNG 签名。若载荷夹带 `zhijie-helper` 或 systemd 服务文件会报错。当前桌面候选不需要安装维护脚本：控制归档只允许普通 `control` 和可选的 `md5sums` 文件；发现脚本、触发器或其他条目时拒绝候选并请求审查，不执行它们。检查不会安装软件或验证其运行时行为。

`python3 -B "packaging/linux/test_check_deb.py" -v` 运行检查器回归；没有 `dpkg-deb` 时只验证内存中的归档，真实 `.deb` 工具链回归会明确跳过。在 Ubuntu 上需确认该项未跳过，然后另行对实际候选运行上述命令。测试夹具不是候选制品。

分别在支持矩阵中的 Ubuntu 22.04/24.04 记录实际候选包的安装、菜单启动、升级后配置保留和卸载；不要以包内容检查代替这些操作，也不要以桌面启动代替真实语音或 TUN 验收。
