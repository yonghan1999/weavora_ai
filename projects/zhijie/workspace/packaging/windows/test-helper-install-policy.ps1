$ErrorActionPreference = "Stop"
Import-Module "$PSScriptRoot/../../scripts/local-preview/HelperInstallPolicy.psm1" -Force
$admin = [Security.Principal.SecurityIdentifier]::new("S-1-5-32-544")
$users = [Security.Principal.SecurityIdentifier]::new("S-1-5-32-545")
function New-TestAcl([Security.Principal.SecurityIdentifier]$Owner = $admin) {
    $acl = [Security.AccessControl.DirectorySecurity]::new()
    $acl.SetOwner($Owner)
    $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($admin, "FullControl", "Allow"))
    return $acl
}
function Assert-Result([bool]$Value, [string]$Name) {
    if (!$Value) { throw "检查失败：$Name" }
    Write-Output "PASS $Name"
}
$safe = New-TestAcl
$safe.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($users, "ReadAndExecute", "Allow"))
Assert-Result (Test-InstallAcl $safe) "trusted owner and user read"
Assert-Result (!(Test-InstallAcl (New-TestAcl $users))) "untrusted owner"
foreach ($right in @("WriteData", "AppendData", "WriteAttributes", "WriteExtendedAttributes", "Delete", "DeleteSubdirectoriesAndFiles", "ChangePermissions", "TakeOwnership", "Modify", "FullControl")) {
    $acl = New-TestAcl
    $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($users, $right, "Allow"))
    Assert-Result (!(Test-InstallAcl $acl)) "deny untrusted $right"
}
$parent = New-TestAcl
$parent.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($users, "AppendData", "Allow"))
Assert-Result (Test-InstallAcl $parent -Ancestor) "ancestor may create unrelated subdirectories"
$inheritOnly = New-TestAcl
$inheritOnly.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($users, "Modify", "ContainerInherit,ObjectInherit", "InheritOnly", "Allow"))
Assert-Result (Test-InstallAcl $inheritOnly) "inherit-only rule does not apply to current node"
$denied = New-TestAcl
$denied.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($users, "Modify", "Allow"))
$denied.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($users, "Modify", "Deny"))
Assert-Result (!(Test-InstallAcl $denied)) "complex deny does not hide unsafe allow"
$programFiles = [Environment]::GetFolderPath("ProgramFiles")
Assert-Result (Test-InstallAcl (Get-Acl -LiteralPath $programFiles)) "actual Program Files ACL"
$workspace = (Resolve-Path -LiteralPath "$PSScriptRoot/../..").Path
$rejected = $false
try { Assert-ProtectedHelperDirectory -Directory "$workspace/target/debug" } catch { $rejected = $true }
Assert-Result $rejected "actual user-writable development directory rejected"
$testRoot = Join-Path $workspace ("target/install-policy-" + [guid]::NewGuid().ToString("N"))
$targetDirectory = Join-Path $testRoot "original"
$junction = Join-Path $testRoot "junction"
[IO.Directory]::CreateDirectory($targetDirectory) | Out-Null
try {
    New-Item -ItemType Junction -Path $junction -Target $targetDirectory | Out-Null
    $junctionRejected = $false
    try { Assert-ProtectedHelperDirectory -Directory $junction } catch {
        $junctionRejected = $_.Exception.Message -match "重解析"
    }
    Assert-Result $junctionRejected "actual directory junction rejected"
} finally {
    # 只删除本次创建的联接本身和两个空目录，不递归删除或跟随联接。
    if ([IO.Directory]::Exists($junction)) { [IO.Directory]::Delete($junction) }
    [IO.Directory]::Delete($targetDirectory)
    [IO.Directory]::Delete($testRoot)
}
Write-Output "No ACLs changed; no executable launched; no elevation or adapter creation."
