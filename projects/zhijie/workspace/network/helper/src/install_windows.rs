use std::fs::{File, OpenOptions};
use std::os::windows::{fs::OpenOptionsExt, io::AsRawHandle};
use std::path::Path;
use std::ptr::null_mut;

use windows_sys::Win32::{
    Foundation::LocalFree,
    Security::{
        Authorization::{ConvertSidToStringSidW, GetSecurityInfo, SE_FILE_OBJECT},
        GetAce, IsValidAcl, ACCESS_ALLOWED_ACE, ACE_HEADER, DACL_SECURITY_INFORMATION,
        INHERIT_ONLY_ACE, OWNER_SECURITY_INFORMATION,
    },
};

const ERROR: &str = "HELPER_INSTALLATION_UNPROTECTED";
const TRUSTED_SIDS: [&str; 3] = [
    "S-1-5-18",
    "S-1-5-32-544",
    "S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464",
];

fn trusted_sid(sid: *mut core::ffi::c_void) -> Result<bool, String> {
    let mut text = null_mut();
    if sid.is_null() || unsafe { ConvertSidToStringSidW(sid, &mut text) } == 0 {
        return Err(ERROR.into());
    }
    let value = unsafe { widestring::U16CStr::from_ptr_str(text).to_string_lossy() };
    unsafe { LocalFree(text.cast()) };
    Ok(TRUSTED_SIDS.contains(&value.as_str()))
}

fn unsafe_mask(ancestor: bool) -> u32 {
    // 通用写/完全控制、删除、改权、接管、删除子项；祖先可创建无关子目录。
    0x500d0152 | if ancestor { 0 } else { 0x4 }
}

fn verify_acl(file: &File, ancestor: bool) -> Result<(), String> {
    let mut owner = null_mut();
    let mut dacl = null_mut();
    let mut descriptor = null_mut();
    let result = unsafe {
        GetSecurityInfo(
            file.as_raw_handle(),
            SE_FILE_OBJECT,
            OWNER_SECURITY_INFORMATION | DACL_SECURITY_INFORMATION,
            &mut owner,
            null_mut(),
            &mut dacl,
            null_mut(),
            &mut descriptor,
        )
    };
    if result != 0 {
        return Err(ERROR.into());
    }
    // Windows 分配的描述符持有 SID/ACL；全部解析结束后统一释放。
    let checked = check_acl(owner, dacl, ancestor);
    unsafe { LocalFree(descriptor) };
    checked
}

fn check_acl(
    owner: *mut core::ffi::c_void,
    dacl: *mut windows_sys::Win32::Security::ACL,
    ancestor: bool,
) -> Result<(), String> {
    if !trusted_sid(owner)? || dacl.is_null() || unsafe { IsValidAcl(dacl) } == 0 {
        return Err(ERROR.into());
    }
    for index in 0..unsafe { (*dacl).AceCount } {
        let mut pointer = null_mut();
        if unsafe { GetAce(dacl, index.into(), &mut pointer) } == 0 {
            return Err(ERROR.into());
        }
        let header = unsafe { &*pointer.cast::<ACE_HEADER>() };
        if u32::from(header.AceFlags) & INHERIT_ONLY_ACE != 0 {
            continue;
        }
        match header.AceType {
            1 => continue, // 不依赖拒绝规则抵消危险允许规则。
            0 if usize::from(header.AceSize) >= std::mem::size_of::<ACCESS_ALLOWED_ACE>() => {
                let ace = unsafe { &*pointer.cast::<ACCESS_ALLOWED_ACE>() };
                let sid = std::ptr::addr_of!(ace.SidStart).cast_mut().cast();
                if ace.Mask & unsafe_mask(ancestor) != 0 && !trusted_sid(sid)? {
                    return Err(ERROR.into());
                }
            }
            _ => return Err(ERROR.into()), // 未支持的对象/条件 ACE 保守拒绝。
        }
    }
    Ok(())
}

fn protected_handle(path: &Path, directory: bool, ancestor: bool) -> Result<File, String> {
    // 从句柄读取安全描述符；不跟随重解析点。禁止删除共享以保持已检查路径节点。
    let file = OpenOptions::new()
        .access_mode(0x00020080) // READ_CONTROL | FILE_READ_ATTRIBUTES
        .share_mode(if directory { 3 } else { 1 })
        .custom_flags(0x02200000) // BACKUP_SEMANTICS | OPEN_REPARSE_POINT
        .open(path)
        .map_err(|_| ERROR)?;
    use std::os::windows::fs::MetadataExt;
    let metadata = file.metadata().map_err(|_| ERROR)?;
    if metadata.file_attributes() & 0x400 != 0
        || metadata.is_dir() != directory
        || (!directory && !metadata.is_file())
    {
        return Err(ERROR.into());
    }
    verify_acl(&file, ancestor)?;
    Ok(file)
}

pub(crate) fn verify_installation(executable: &Path) -> Result<Vec<File>, String> {
    let directory = executable.parent().ok_or(ERROR)?;
    if !directory.is_absolute() {
        return Err(ERROR.into());
    }
    let mut handles = Vec::new();
    // 从根向安装目录锁定各节点，再检查配套文件，避免先检查子项再被替换祖先。
    let ancestors: Vec<_> = directory.ancestors().collect();
    for path in ancestors.iter().rev() {
        handles.push(protected_handle(path, true, *path != directory)?);
    }
    for name in ["zhijie-helper.exe", "zhijie-shell.exe", "wintun.dll"] {
        handles.push(protected_handle(&directory.join(name), false, false)?);
    }
    Ok(handles)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sddl_allowed(sddl: &str, ancestor: bool) -> bool {
        use windows_sys::Win32::Security::{
            Authorization::ConvertStringSecurityDescriptorToSecurityDescriptorW,
            GetSecurityDescriptorDacl, GetSecurityDescriptorOwner,
        };
        let text = widestring::U16CString::from_str(sddl).unwrap();
        let mut descriptor = null_mut();
        assert_ne!(
            unsafe {
                ConvertStringSecurityDescriptorToSecurityDescriptorW(
                    text.as_ptr(),
                    1,
                    &mut descriptor,
                    null_mut(),
                )
            },
            0
        );
        let mut owner = null_mut();
        let mut dacl = null_mut();
        let mut defaulted = 0;
        let mut present = 0;
        assert_ne!(
            unsafe { GetSecurityDescriptorOwner(descriptor, &mut owner, &mut defaulted) },
            0
        );
        assert_ne!(
            unsafe {
                GetSecurityDescriptorDacl(descriptor, &mut present, &mut dacl, &mut defaulted)
            },
            0
        );
        let accepted = check_acl(owner, dacl, ancestor).is_ok();
        unsafe { LocalFree(descriptor) };
        accepted
    }

    #[test]
    fn native_acl_parser_rejects_dangerous_or_unsupported_permissions() {
        assert!(sddl_allowed("O:BAD:(A;;FA;;;BA)(A;;FRFX;;;BU)", false));
        assert!(!sddl_allowed("O:BUD:(A;;FA;;;BA)", false));
        assert!(!sddl_allowed("O:BAD:NO_ACCESS_CONTROL", false));
        for mask in [
            2, 4, 0x10, 0x100, 0x10000, 0x40000, 0x80000, 0x40, 0x40000000, 0x10000000,
        ] {
            assert!(!sddl_allowed(&format!("O:BAD:(A;;0x{mask:x};;;BU)"), false));
        }
        assert!(sddl_allowed("O:BAD:(A;;0x4;;;BU)", true));
        assert!(!sddl_allowed("O:BAD:(A;;0x100;;;BU)", true));
        assert!(sddl_allowed("O:BAD:(A;CIIO;FA;;;BU)", false));
        assert!(!sddl_allowed("O:BAD:(D;;FA;;;BU)(A;;FA;;;BU)", false));
        assert!(!sddl_allowed("O:BAD:(OA;;FA;;;BU)", false));
    }

    #[test]
    fn actual_protected_system_directory_is_accepted() {
        let directory = std::env::var_os("ProgramFiles").unwrap();
        let directory = Path::new(&directory);
        let paths: Vec<_> = directory.ancestors().collect();
        let handles: Vec<_> = paths
            .iter()
            .rev()
            .map(|path| protected_handle(path, true, *path != directory).unwrap())
            .collect();
        assert_eq!(handles.len(), paths.len());
        let canonical = std::fs::canonicalize(directory).unwrap();
        let paths: Vec<_> = canonical.ancestors().collect();
        let _canonical_handles: Vec<_> = paths
            .iter()
            .rev()
            .map(|path| protected_handle(path, true, *path != canonical).unwrap())
            .collect();
    }

    #[test]
    fn user_writable_build_is_rejected_and_dangerous_masks_are_distinct() {
        assert!(verify_installation(&std::env::current_exe().unwrap()).is_err());
        assert_eq!(unsafe_mask(true) & 0x4, 0);
        assert_eq!(unsafe_mask(true) & 0x112, 0x112);
        assert_eq!(unsafe_mask(false) & 0x116, 0x116);
        for right in [0x10000, 0x40000, 0x80000, 0x40, 0x40000000, 0x10000000] {
            assert_ne!(unsafe_mask(true) & right, 0);
        }
    }
}
