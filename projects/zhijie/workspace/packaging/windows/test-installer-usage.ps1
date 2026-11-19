param([string]$NsisCompiler = "")
$ErrorActionPreference = "Stop"
$workspace = (Resolve-Path -LiteralPath "$PSScriptRoot/../..").Path
if (!$NsisCompiler) { $NsisCompiler = "$env:LOCALAPPDATA/tauri/NSIS/makensis.exe" }
if (!(Test-Path -LiteralPath $NsisCompiler -PathType Leaf)) { throw "缺少 NSIS 编译器。" }
$fixtureDirectory = Join-Path $workspace ("target/installer-usage-" + [guid]::NewGuid().ToString("N"))
[IO.Directory]::CreateDirectory($fixtureDirectory) | Out-Null
$hooks = (Resolve-Path -LiteralPath "$PSScriptRoot/installer-hooks.nsh").Path
$hookSource = Get-Content -LiteralPath $hooks -Raw -Encoding UTF8
if ($hookSource -match 'RmShutdown|TerminateProcess|taskkill|Stop-Process|ExecWait|ExecShell') {
    throw "占用检查不得结束进程或执行外部命令。"
}
$source = @"
Unicode true
RequestExecutionLevel user
Name "Usage check compilation fixture"
OutFile "$fixtureDirectory/compile-only.exe"
; 模拟 Tauri 工具宏；项目 include 必须替换此终止分支。
!macro CheckIfAppIsRunning executablePath productName
  System::Call 'RSTRTMGR::RmShutdown(p 0, i 1, p 0)'
!macroend
!include "$hooks"
Section
  StrCpy `$INSTDIR "$fixtureDirectory"
  !insertmacro NSIS_HOOK_PREINSTALL
  !insertmacro CheckIfAppIsRunning "`$INSTDIR\zhijie-shell.exe" "fixture"
  WriteUninstaller "$fixtureDirectory/compile-only-uninstall.exe"
SectionEnd
Section Uninstall
  !insertmacro NSIS_HOOK_PREUNINSTALL
  !insertmacro CheckIfAppIsRunning "`$INSTDIR\zhijie-shell.exe" "fixture"
SectionEnd
"@
$fixtureSource = Join-Path $fixtureDirectory "compile-only.nsi"
[IO.File]::WriteAllText($fixtureSource, $source, [Text.UTF8Encoding]::new($false))
$ErrorActionPreference = "Continue"
& $NsisCompiler /INPUTCHARSET UTF8 /V2 $fixtureSource
$compilerExit = $LASTEXITCODE
$ErrorActionPreference = "Stop"
if ($compilerExit -ne 0) { throw "NSIS 双钩子编译失败：$compilerExit" }
Write-Output "PASS both installer hooks compile; fixture executable was not run"

$preprocessedSource = Join-Path $fixtureDirectory "expanded.nsi"
$ErrorActionPreference = "Continue"
& $NsisCompiler /INPUTCHARSET UTF8 /PPO $fixtureSource > $preprocessedSource
$preprocessExit = $LASTEXITCODE
$ErrorActionPreference = "Stop"
if ($preprocessExit -ne 0) { throw "NSIS 宏展开失败：$preprocessExit" }
$expanded = Get-Content -LiteralPath $preprocessedSource -Raw
if ($expanded -match 'RmShutdown|TerminateProcess') { throw "后续应用检查仍包含终止进程调用。" }
if ([regex]::Matches($expanded, 'RmGetList').Count -ne 8) { throw "四个入口未全部展开只读占用检查。" }
Write-Output "PASS installer/uninstaller follow-up checks replace the shutdown branch with read-only queries"

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class ZhijieUsageFixture {
    [DllImport("rstrtmgr.dll", CharSet=CharSet.Unicode)]
    static extern int RmStartSession(out uint session, uint flags, char[] key);
    [DllImport("rstrtmgr.dll", CharSet=CharSet.Unicode)]
    static extern int RmRegisterResources(uint session, uint files, string[] names,
        uint applications, IntPtr processes, uint services, IntPtr serviceNames);
    [DllImport("rstrtmgr.dll")]
    static extern int RmGetList(uint session, out uint needed, ref uint count,
        IntPtr applications, ref uint reasons);
    [DllImport("rstrtmgr.dll")]
    static extern int RmEndSession(uint session);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    public static extern IntPtr LoadLibraryW(string path);
    [DllImport("kernel32.dll", SetLastError=true)]
    public static extern bool FreeLibrary(IntPtr module);
    public static bool Unused(string path) {
        uint session;
        if (RmStartSession(out session, 0, new char[33]) != 0)
            throw new Exception("RmStartSession failed");
        try {
            if (RmRegisterResources(session, 1, new[] {path}, 0, IntPtr.Zero, 0, IntPtr.Zero) != 0)
                throw new Exception("RmRegisterResources failed");
            uint needed, count = 0, reasons = 0;
            int result = RmGetList(session, out needed, ref count, IntPtr.Zero, ref reasons);
            return result == 0 && needed == 0 && count == 0;
        } finally {
            if (RmEndSession(session) != 0) throw new Exception("RmEndSession failed");
        }
    }
}
'@
# 仅在本测试进程加载 Windows 自带 version.dll 的副本，不加载产品或驱动。
$libraryPath = Join-Path $fixtureDirectory "usage-fixture.dll"
Copy-Item -LiteralPath "$env:WINDIR/System32/version.dll" -Destination $libraryPath
if (![ZhijieUsageFixture]::Unused($libraryPath)) { throw "空闲资源误判。" }
Write-Output "PASS idle module resource allowed"
$module = [ZhijieUsageFixture]::LoadLibraryW($libraryPath)
if ($module -eq [IntPtr]::Zero) { throw "测试模块加载失败。" }
try {
    if ([ZhijieUsageFixture]::Unused($libraryPath)) { throw "加载中的模块未被拒绝。" }
    Write-Output "PASS actual loaded module is blocked by read-only Restart Manager query"
} finally {
    if (![ZhijieUsageFixture]::FreeLibrary($module)) { throw "测试模块释放失败。" }
}
if (![ZhijieUsageFixture]::Unused($libraryPath)) { throw "释放后的模块未恢复空闲。" }
Write-Output "PASS released module resource allowed"
Write-Output "No installer executed; no process stopped; no driver, adapter, registry or system ACL changed."
