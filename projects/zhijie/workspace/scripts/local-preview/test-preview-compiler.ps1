param([string]$JavaHome = $env:JAVA_HOME, [int]$HttpPort = 18832, [int]$DatabasePort = 15442)
$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path -LiteralPath "$PSScriptRoot/../..").Path
$target = (Resolve-Path -LiteralPath "$workspace/target").Path
if ([IO.Path]::GetPathRoot($target).TrimEnd('\') -eq $env:SystemDrive) { throw '需要非系统盘工作区' }
$fixture = Join-Path $target "compiler test $([guid]::NewGuid().ToString('N'))"
$scripts = "$fixture/scripts/local-preview"
New-Item -ItemType Directory -Path "$scripts","$fixture/control-plane/target" | Out-Null
foreach ($name in 'start.ps1','CleanCmd.cs') {
    Copy-Item -LiteralPath "$PSScriptRoot/$name" -Destination "$scripts/$name"
}
Set-Content -LiteralPath "$fixture/control-plane/target/local-preview-classpath.txt" -Value '.'
$savedOptions = $env:JAVA_TOOL_OPTIONS
$savedPassword = $env:ZHIJIE_PREVIEW_PASSWORD
try {
    $env:JAVA_TOOL_OPTIONS = '-XX:-UsePerfData'
    foreach ($valid in $true,$false) {
        $source = if ($valid) { 'public class LocalPreview {}' } else { 'public class LocalPreview {' }
        Set-Content -LiteralPath "$scripts/LocalPreview.java" -Value $source -Encoding ASCII
        $env:ZHIJIE_PREVIEW_PASSWORD = 'short'
        $expected = if ($valid) { '首次试用密码至少需要 12 位，未启动数据库。' } else { '本地启动入口编译失败' }
        $message = $null
        try { & "$scripts/start.ps1" -JavaHome $JavaHome -HttpPort $HttpPort -DatabasePort $DatabasePort *> "$fixture/compiler-$valid.log" }
        catch { $message = $_.Exception.Message }
        if ($message -ne $expected) { throw "Unexpected launcher outcome (valid=$valid): $message" }
        if (Test-Path -LiteralPath "$fixture/target/local-preview/server.pid") { throw '不应启动服务' }
    }
    Write-Output 'PREVIEW_COMPILER_WARNING_AND_NONZERO_EXIT_OK'
} finally {
    $env:JAVA_TOOL_OPTIONS = $savedOptions
    $env:ZHIJIE_PREVIEW_PASSWORD = $savedPassword
}
