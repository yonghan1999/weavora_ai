function Get-TrustedInstallSids {
    @("S-1-5-18", "S-1-5-32-544",
        "S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464")
}

function Test-InstallAcl {
    param($Acl, [switch]$Ancestor)
    $trusted = @(Get-TrustedInstallSids)
    $owner = $Acl.GetOwner([Security.Principal.SecurityIdentifier]).Value
    if ($owner -notin $trusted) { return $false }
    # 上级目录允许创建无关子目录，但不能删除/改权已有安装目录。
    $unsafe = [int64]0x500D0152 # 通用写/完全控制、删除/改权、写数据/属性/扩展属性
    if (!$Ancestor) { $unsafe = $unsafe -bor 0x00000004 } # 安装目录也禁止普通用户追加子项
    foreach ($rule in $Acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier])) {
        if ($rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow) { continue }
        if (($rule.PropagationFlags -band [Security.AccessControl.PropagationFlags]::InheritOnly) -ne 0) { continue }
        if ($rule.IdentityReference.Value -in $trusted) { continue }
        # 不依赖 Deny 抵消 Allow；复杂自定义 ACL 保守拒绝。
        if (([int64]$rule.FileSystemRights -band $unsafe) -ne 0) { return $false }
    }
    return $true
}

function Assert-ProtectedHelperDirectory {
    param([Parameter(Mandatory=$true)][string]$Directory)
    $directoryInfo = Get-Item -LiteralPath $Directory -Force -ErrorAction Stop
    if (!$directoryInfo.PSIsContainer) { throw "Helper 安装目录无效。" }
    $current = $directoryInfo
    $ancestor = $false
    while ($null -ne $current) {
        if (($current.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Helper 安装路径不能经过重解析目录。"
        }
        if (!(Test-InstallAcl (Get-Acl -LiteralPath $current.FullName) -Ancestor:$ancestor)) {
            throw "Helper 管理员启动要求受保护安装目录；当前目录或上级目录可由普通用户替换。请使用机器级测试包并核对安装权限。"
        }
        $current = $current.Parent
        $ancestor = $true
    }
    foreach ($name in @("zhijie-helper.exe", "zhijie-shell.exe", "wintun.dll")) {
        $file = Get-Item -LiteralPath (Join-Path $directoryInfo.FullName $name) -Force -ErrorAction Stop
        if ($file.PSIsContainer -or ($file.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or
            !(Test-InstallAcl (Get-Acl -LiteralPath $file.FullName))) {
            throw "Helper 配套映像不是受保护的常规文件。"
        }
    }
}

Export-ModuleMember -Function Assert-ProtectedHelperDirectory, Test-InstallAcl
