use std::os::windows::io::{AsRawHandle, FromRawHandle, OwnedHandle};

use interprocess::os::windows::security_descriptor::SecurityDescriptor;
use windows_sys::Win32::{
    Foundation::{LocalFree, WAIT_TIMEOUT},
    Security::{
        Authorization::ConvertSidToStringSidW, GetTokenInformation, TokenUser, TOKEN_QUERY,
        TOKEN_USER,
    },
    System::Threading::{
        OpenProcess, OpenProcessToken, QueryFullProcessImageNameW, WaitForSingleObject,
        PROCESS_QUERY_LIMITED_INFORMATION, PROCESS_SYNCHRONIZE,
    },
};

pub(crate) struct ClientIdentity {
    process: OwnedHandle,
    sid: String,
}

pub(crate) struct ServerIdentity {
    process: OwnedHandle,
}

impl ServerIdentity {
    pub fn verify(pid: u32, expected_image: &std::path::Path) -> Result<Self, String> {
        let process = unsafe {
            OpenProcess(
                PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_SYNCHRONIZE,
                0,
                pid,
            )
        };
        if process.is_null() {
            return Err("IPC_SERVER_IDENTITY_UNAVAILABLE".into());
        }
        // 持有原进程对象，不信任响应中的 PID 或进程自报路径。
        let process = unsafe { OwnedHandle::from_raw_handle(process) };
        let identity = Self { process };
        identity.check_alive()?;
        let mut buffer = vec![0u16; 32768];
        let mut length = buffer.len() as u32;
        if unsafe {
            QueryFullProcessImageNameW(
                identity.process.as_raw_handle(),
                0,
                buffer.as_mut_ptr(),
                &mut length,
            )
        } == 0
        {
            return Err("IPC_SERVER_IDENTITY_UNAVAILABLE".into());
        }
        use std::os::windows::ffi::OsStringExt;
        let actual =
            std::path::PathBuf::from(std::ffi::OsString::from_wide(&buffer[..length as usize]));
        let actual =
            std::fs::canonicalize(actual).map_err(|_| "IPC_SERVER_IDENTITY_UNAVAILABLE")?;
        let expected =
            std::fs::canonicalize(expected_image).map_err(|_| "IPC_SERVER_IDENTITY_UNAVAILABLE")?;
        if actual != expected {
            return Err("IPC_SERVER_NOT_ALLOWED".into());
        }
        identity.check_alive()?;
        Ok(identity)
    }

    pub fn check_alive(&self) -> Result<(), String> {
        if unsafe { WaitForSingleObject(self.process.as_raw_handle(), 0) } == WAIT_TIMEOUT {
            Ok(())
        } else {
            Err("IPC_SERVER_EXITED".into())
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use interprocess::os::windows::security_descriptor::{
        AsSecurityDescriptorExt, BorrowedSecurityDescriptor,
    };
    use windows_sys::Win32::Security::{
        Authorization::{GetSecurityInfo, SE_KERNEL_OBJECT},
        DACL_SECURITY_INFORMATION,
    };

    #[test]
    fn actual_named_pipe_has_protected_dacl_for_system_and_bound_account_only() {
        use std::sync::Arc;
        let identity = ClientIdentity::open(std::process::id()).unwrap();
        let service = Arc::new(crate::service::HelperService::new(Arc::new(
            crate::system::SystemNetworkBackend::new(),
        )));
        let server = crate::ipc::HelperIpcServer::for_client(service, std::process::id()).unwrap();
        let path = std::path::PathBuf::from(format!(
            r"\\.\pipe\zhijie-helper-dacl-{}",
            std::process::id()
        ));
        let _listener = server.bind(&path).unwrap();
        let stream = std::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .open(&path)
            .unwrap();
        let mut descriptor = std::ptr::null_mut();
        // SAFETY: Windows 写入独立分配的描述符；借用只活到 LocalFree 之前。
        let status = unsafe {
            GetSecurityInfo(
                stream.as_raw_handle(),
                SE_KERNEL_OBJECT,
                DACL_SECURITY_INFORMATION,
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                &mut descriptor,
            )
        };
        assert_eq!(status, 0);
        let actual = unsafe { BorrowedSecurityDescriptor::from_ptr(descriptor) }
            .serialize(DACL_SECURITY_INFORMATION, |value| value.to_string_lossy());
        unsafe { LocalFree(descriptor) };
        let actual = actual.unwrap();
        assert!(actual.starts_with("D:P"), "DACL must reject inheritance");
        let accounts: Vec<_> = actual
            .split(";;;")
            .skip(1)
            .map(|ace| ace.split(')').next().unwrap())
            .collect();
        assert_eq!(accounts.len(), 3);
        assert!(accounts
            .iter()
            .all(|sid| *sid == "SY" || *sid == identity.sid));
        assert!(accounts.contains(&"SY"));
        assert!(accounts.contains(&identity.sid.as_str()));
    }
}

impl ClientIdentity {
    pub fn open(pid: u32) -> Result<Self, String> {
        // 持有原始进程对象，PID 数值被复用后也不能替换已绑定的桌面。
        let process = unsafe {
            OpenProcess(
                PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_SYNCHRONIZE,
                0,
                pid,
            )
        };
        if process.is_null() {
            return Err("IPC_CLIENT_PROCESS_UNAVAILABLE".into());
        }
        // SAFETY: OpenProcess 返回当前函数独占的有效句柄，由 OwnedHandle 关闭。
        let process = unsafe { OwnedHandle::from_raw_handle(process) };
        let mut token = std::ptr::null_mut();
        if unsafe { OpenProcessToken(process.as_raw_handle(), TOKEN_QUERY, &mut token) } == 0 {
            return Err("IPC_CLIENT_IDENTITY_UNAVAILABLE".into());
        }
        // SAFETY: OpenProcessToken 成功返回独占句柄。
        let token = unsafe { OwnedHandle::from_raw_handle(token) };
        let mut length = 0;
        unsafe {
            GetTokenInformation(
                token.as_raw_handle(),
                TokenUser,
                std::ptr::null_mut(),
                0,
                &mut length,
            );
        }
        if length == 0 || length > 65536 {
            return Err("IPC_CLIENT_IDENTITY_UNAVAILABLE".into());
        }
        // usize 对齐足以存放 TOKEN_USER 及其内联 SID，缓冲在转换完成前一直存活。
        let mut buffer = vec![0usize; (length as usize).div_ceil(std::mem::size_of::<usize>())];
        if unsafe {
            GetTokenInformation(
                token.as_raw_handle(),
                TokenUser,
                buffer.as_mut_ptr().cast(),
                length,
                &mut length,
            )
        } == 0
        {
            return Err("IPC_CLIENT_IDENTITY_UNAVAILABLE".into());
        }
        let user = unsafe { &*buffer.as_ptr().cast::<TOKEN_USER>() };
        let mut sid_text = std::ptr::null_mut();
        if unsafe { ConvertSidToStringSidW(user.User.Sid, &mut sid_text) } == 0 {
            return Err("IPC_CLIENT_IDENTITY_UNAVAILABLE".into());
        }
        // SAFETY: Windows 返回以 NUL 结束的 LocalAlloc 字符串，复制后使用 LocalFree 释放。
        let sid = unsafe { widestring::U16CStr::from_ptr_str(sid_text).to_string_lossy() };
        unsafe { LocalFree(sid_text.cast()) };
        let identity = Self { process, sid };
        identity.check_alive()?;
        Ok(identity)
    }

    pub fn check_alive(&self) -> Result<(), String> {
        // 仅超时代表原进程仍活着；已退出或查询失败都拒绝。
        if unsafe { WaitForSingleObject(self.process.as_raw_handle(), 0) } == WAIT_TIMEOUT {
            Ok(())
        } else {
            Err("IPC_CLIENT_EXITED".into())
        }
    }

    pub fn security_descriptor(&self) -> Result<SecurityDescriptor, String> {
        let helper = Self::open(std::process::id())?;
        // 只给 SYSTEM、Helper 账号和绑定桌面账号权限，禁止默认的其他用户读取。
        // 同账号服务器创建后续实例仍需 FILE_CREATE_PIPE_INSTANCE，不能靠 DACL 隔离同账号进程。
        let sddl = widestring::U16CString::from_str(format!(
            "D:P(A;;GA;;;SY)(A;;GA;;;{})(A;;GA;;;{})",
            helper.sid, self.sid
        ))
        .map_err(|_| "IPC_SECURITY_INVALID")?;
        SecurityDescriptor::deserialize(&sddl).map_err(|_| "IPC_SECURITY_INVALID".into())
    }
}
