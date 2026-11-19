# Windows 候选包只读核对

从项目的 `workspace` 运行。检查对象为本机生成的 NSIS 安装包，不需要管理员权限，不运行安装器、桌面、Helper 或驱动。先保留与**同一次构建**对应的二进制和脚本；之后重编译再比对可能得到不同结果。

```powershell
python "packaging/windows/verify-package.py" "target/release/bundle/nsis/织界_0.1.0_x64-setup.exe" --binaries "target/release" --scripts "scripts/local-preview" --driver "target/windows-bundle"
```

使用 Python 3.12+、7-Zip（可用 `--seven-zip` 指定程序路径）。调试包须将 `--binaries` 指向对应的 `target/debug` 或其留存副本。不要用共用暂存目录里的 Helper 代替构建输出：该文件可能是另一个配置的版本。

检查器将包内的桌面、Helper、启动脚本、权限模块、Wintun DLL、许可、头文件和来源清单逐一与外部输入比对，并输出包及文件的 SHA-256。缺失、重复、额外产品文件或任一摘要不符时退出非零。7-Zip 展示的 NSIS 插件目录不属于这八项产品输入。驱动应先通过 `prepare-wintun.py` 独立核对锁文件、归档、架构及签名；本工具不认证安装器签名、插件和安装逻辑，也不验证安装、升级、卸载或跨机互通。
