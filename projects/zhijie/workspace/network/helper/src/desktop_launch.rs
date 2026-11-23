//! 只能由机器级安装的桌面请求系统授权，不执行脚本、不接收外部命令或路径。
use std::os::windows::io::{AsRawHandle, FromRawHandle, OwnedHandle};
use widestring::U16CString;
use windows_sys::Win32::{
    Foundation::{ERROR_CANCELLED, WAIT_TIMEOUT},
    System::Threading::WaitForSingleObject,
    UI::Shell::{
        ShellExecuteExW, SEE_MASK_FLAG_NO_UI, SEE_MASK_NOASYNC, SEE_MASK_NOCLOSEPROCESS,
        SHELLEXECUTEINFOW,
    },
};

pub struct HelperProcess(OwnedHandle);
impl HelperProcess {
    pub fn running(&self) -> bool {
        unsafe { WaitForSingleObject(self.0.as_raw_handle(), 0) == WAIT_TIMEOUT }
    }
}

fn arguments(origin: &str, pid: u32) -> Result<String, String> {
    crate::authority::validate_base_url(origin)?;
    let parsed = reqwest::Url::parse(origin).map_err(|_| "CONTROL_PLANE_URL_INVALID")?;
    if origin != parsed.origin().ascii_serialization() || parsed.host().is_none() || pid == 0 {
        return Err("HELPER_LAUNCH_INVALID".into());
    }
    Ok(format!(
        "--client-pid {pid} --control-plane-url {origin} --socket {}",
        r"\\.\pipe\zhijie-helper-v1"
    ))
}

pub fn request_consent(origin: &str) -> Result<HelperProcess, String> {
    let exe = std::env::current_exe().map_err(|_| "HELPER_INSTALLATION_UNPROTECTED")?;
    if exe.file_name().and_then(|n| n.to_str()) != Some("zhijie-shell.exe") {
        return Err("HELPER_INSTALLATION_UNPROTECTED".into());
    }
    let dir = exe.parent().ok_or("HELPER_INSTALLATION_UNPROTECTED")?;
    let _guard = crate::install_windows::verify_installation(&exe)?;
    let wide =
        |s: &std::ffi::OsStr| U16CString::from_os_str(s).map_err(|_| "HELPER_LAUNCH_INVALID");
    let file = wide(dir.join("zhijie-helper.exe").as_os_str())?;
    let dir = wide(dir.as_os_str())?;
    let params = U16CString::from_str(arguments(origin, std::process::id())?)
        .map_err(|_| "HELPER_LAUNCH_INVALID")?;
    let mut info = SHELLEXECUTEINFOW {
        cbSize: std::mem::size_of::<SHELLEXECUTEINFOW>() as u32,
        fMask: SEE_MASK_NOASYNC | SEE_MASK_NOCLOSEPROCESS | SEE_MASK_FLAG_NO_UI,
        lpVerb: windows_sys::w!("runas"),
        lpFile: file.as_ptr(),
        lpDirectory: dir.as_ptr(),
        lpParameters: params.as_ptr(),
        nShow: 0,
        ..Default::default()
    };
    if unsafe { ShellExecuteExW(&mut info) } == 0 {
        return Err(
            if std::io::Error::last_os_error().raw_os_error() == Some(ERROR_CANCELLED as i32) {
                "HELPER_CONSENT_DECLINED"
            } else {
                "HELPER_LAUNCH_FAILED"
            }
            .into(),
        );
    }
    if info.hProcess.is_null() {
        return Err("HELPER_LAUNCH_FAILED".into());
    }
    Ok(HelperProcess(unsafe {
        OwnedHandle::from_raw_handle(info.hProcess)
    }))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn only_canonical_origins_and_the_bound_process_are_passed() {
        assert_eq!(
            arguments("https://voice.example", 7).unwrap(),
            r"--client-pid 7 --control-plane-url https://voice.example --socket \\.\pipe\zhijie-helper-v1"
        );
        for bad in [
            "https://voice.example/redirect",
            "http://remote.example",
            "https://voice.example --socket other",
            "https://user@voice.example",
            "https://voice.example\n",
        ] {
            assert!(arguments(bad, 7).is_err());
        }
    }
}
