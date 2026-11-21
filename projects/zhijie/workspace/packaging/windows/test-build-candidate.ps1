$ErrorActionPreference = "Stop"
$workspace = (Resolve-Path -LiteralPath "$PSScriptRoot/../..").Path
$fixtureParent = "$workspace/target"
if ([IO.Path]::GetPathRoot($workspace) -ne 'D:\') {
    $fixtureParent = [IO.Path]::GetTempPath()
    if ([IO.Path]::GetPathRoot($fixtureParent) -ne 'D:\') {
        throw "请将当前进程 TEMP 指向 D 盘项目临时目录后运行候选构建测试。"
    }
}
$root = Join-Path $fixtureParent "candidate-environment-$([guid]::NewGuid().ToString('N'))"
$variables = @("TEMP", "TMP", "TMPDIR", "CARGO_HOME", "CARGO_TARGET_DIR", "RUSTUP_AUTO_INSTALL", "npm_config_cache", "PYTHONDONTWRITEBYTECODE")
$original = @{}
foreach ($name in $variables) { $original[$name] = [Environment]::GetEnvironmentVariable($name, "Process") }
$location = (Get-Location).Path
$fixtureState = @{ Calls = 0; FailAt = 0 }

function Invoke-FixtureCommand {
    $fixtureState.Calls++
    foreach ($name in @("TEMP", "TMP", "TMPDIR")) {
        $value = [Environment]::GetEnvironmentVariable($name, "Process")
        if ($value -ne $env:TEMP -or !(Test-Path -LiteralPath "$value" -PathType Container) -or !(Split-Path -Leaf "$value").StartsWith("candidate-temp-")) {
            throw "临时环境未按本次构建隔离：$name"
        }
    }
    if ($env:CARGO_HOME -ne "$root/target/local-native/cargo" -or $env:CARGO_TARGET_DIR -ne "$root/target" -or
            $env:npm_config_cache -ne "$root/target/npm-cache" -or $env:RUSTUP_AUTO_INSTALL -ne "0" -or
            $env:PYTHONDONTWRITEBYTECODE -ne "1") {
        throw "构建缓存或自动安装设置不符"
    }
    [IO.File]::WriteAllText("$env:TEMP/fixture.txt", "temporary")
    $global:LASTEXITCODE = if ($fixtureState.Calls -eq $fixtureState.FailAt) { 7 } else { 0 }
}
function node { Invoke-FixtureCommand }
function cargo { Invoke-FixtureCommand }
function python { Invoke-FixtureCommand }

try {
    foreach ($directory in @("packaging/windows", "desktop/ui", "desktop/shell", "target/release", "target/windows-bundle", "target/local-native/cargo", "target/npm-cache")) {
        New-Item -ItemType Directory -Path "$root/$directory" -Force | Out-Null
    }
    Copy-Item -LiteralPath "$PSScriptRoot/build-candidate.ps1" -Destination "$root/packaging/windows/build-candidate.ps1"
    [IO.File]::WriteAllText("$root/desktop/shell/tauri.conf.json", '{"productName":"fixture","version":"0.1.0"}')
    [IO.File]::WriteAllText("$root/target/release/zhijie-helper.exe", "fixture")
    [IO.File]::WriteAllText("$root/cli.js", "fixture")
    foreach ($directory in @("local-native/cargo", "npm-cache")) {
        [IO.File]::WriteAllText("$root/target/$directory/preserved.txt", "shared")
    }
    foreach ($failure in @(0, 1, 2, 5, 6)) {
        $fixtureState.Calls = 0
        $fixtureState.FailAt = $failure
        $before = @{}
        foreach ($name in $variables) {
            $value = if ($name -eq "TMPDIR") { $null } else { "D:/candidate-original-$name" }
            [Environment]::SetEnvironmentVariable($name, $value, "Process")
            $before[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
        }
        $failed = $false
        try { & "$root/packaging/windows/build-candidate.ps1" -TauriCli "$root/cli.js" } catch {
            if ($_.Exception.Message -notmatch "退出码 7") { throw }
            $failed = $true
        }
        if ($failed -ne ($failure -ne 0) -or $fixtureState.Calls -ne $(if ($failure) { $failure } else { 6 })) {
            throw "构建成功或失败传播不符"
        }
        foreach ($name in $variables) {
            if ([Environment]::GetEnvironmentVariable($name, "Process") -cne $before[$name]) { throw "环境未恢复：$name" }
        }
        if ((Get-Location).Path -ne $location) { throw "构建后工作目录未恢复" }
        if (Get-ChildItem -LiteralPath "$root/target" -Directory -Filter "candidate-temp-*") { throw "本次临时目录未清理" }
        foreach ($directory in @("local-native/cargo", "npm-cache")) {
            if ([IO.File]::ReadAllText("$root/target/$directory/preserved.txt") -ne "shared") { throw "共享缓存被删除" }
        }
        Write-Output "PASS candidate environment restored and temporary data removed; failure_step=$failure"
    }
} finally {
    foreach ($name in $variables) { [Environment]::SetEnvironmentVariable($name, $original[$name], "Process") }
    if (Test-Path -LiteralPath "$root") {
        $resolved = (Resolve-Path -LiteralPath "$root").Path
        if ($resolved -ne [IO.Path]::GetFullPath($root) -or (Get-Item -LiteralPath "$resolved").Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw "测试临时目录路径变化，保留现场：$root"
        }
        Remove-Item -LiteralPath "$resolved" -Recurse -Force
    }
}
