use std::{ffi::OsString, path::PathBuf};

pub struct StartupConfig {
    pub client_pid: u32,
    pub socket: PathBuf,
    pub control_plane_url: String,
    pub check_only: bool,
}

impl StartupConfig {
    pub fn load() -> Result<Self, String> {
        Self::parse(
            std::env::args_os().skip(1).collect(),
            std::env::var("ZHIJIE_HELPER_CLIENT_PID").ok(),
            std::env::var_os("ZHIJIE_HELPER_SOCKET"),
            std::env::var("ZHIJIE_CONTROL_PLANE_URL").ok(),
        )
    }

    fn parse(
        arguments: Vec<OsString>,
        mut client_pid: Option<String>,
        mut socket: Option<OsString>,
        mut control_plane_url: Option<String>,
    ) -> Result<Self, String> {
        let mut arguments = arguments.into_iter();
        let mut seen = std::collections::HashSet::new();
        let mut check_only = false;
        while let Some(flag) = arguments.next() {
            let flag = flag.to_str().ok_or("HELPER_ARGUMENT_INVALID")?;
            if !seen.insert(flag.to_owned()) {
                return Err("HELPER_ARGUMENT_DUPLICATE".into());
            }
            if flag == "--check-startup" {
                check_only = true;
                continue;
            }
            if !matches!(flag, "--client-pid" | "--socket" | "--control-plane-url") {
                return Err("HELPER_ARGUMENT_UNKNOWN".into());
            }
            let value = arguments.next().ok_or("HELPER_ARGUMENT_VALUE_REQUIRED")?;
            match flag {
                "--socket" if !value.is_empty() => socket = Some(value),
                "--client-pid" => {
                    client_pid = Some(value.into_string().map_err(|_| "HELPER_ARGUMENT_INVALID")?)
                }
                "--control-plane-url" => {
                    control_plane_url =
                        Some(value.into_string().map_err(|_| "HELPER_ARGUMENT_INVALID")?)
                }
                _ => return Err("HELPER_ARGUMENT_INVALID".into()),
            }
        }
        let client_pid = client_pid
            .ok_or("ZHIJIE_HELPER_CLIENT_PID is required")?
            .parse::<u32>()
            .ok()
            .filter(|pid| *pid > 0)
            .ok_or("ZHIJIE_HELPER_CLIENT_PID is invalid")?;
        let control_plane_url = control_plane_url.unwrap_or_else(|| "http://127.0.0.1:8080".into());
        crate::authority::validate_base_url(&control_plane_url)?;
        let socket = socket.map(PathBuf::from).unwrap_or_else(|| {
            #[cfg(windows)]
            {
                PathBuf::from(r"\\.\pipe\zhijie-helper-v1")
            }
            #[cfg(not(windows))]
            {
                PathBuf::from("/run/zhijie/helper-v1.sock")
            }
        });
        validate_socket(&socket)?;
        Ok(Self {
            client_pid,
            socket,
            control_plane_url,
            check_only,
        })
    }
}

fn validate_socket(socket: &std::path::Path) -> Result<(), String> {
    #[cfg(windows)]
    {
        let socket = socket.to_str().ok_or("IPC_PATH_INVALID")?;
        let name = socket
            .strip_prefix(r"\\.\pipe\")
            .ok_or("IPC_PATH_INVALID")?;
        if name.is_empty() || name.contains(['\\', '/', '"', '\r', '\n', '\0']) {
            return Err("IPC_PATH_INVALID".into());
        }
    }
    #[cfg(not(windows))]
    if !socket.is_absolute() {
        return Err("IPC_PATH_INVALID".into());
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn parse(args: &[&str], pid: Option<&str>) -> Result<StartupConfig, String> {
        StartupConfig::parse(
            args.iter().map(OsString::from).collect(),
            pid.map(String::from),
            None,
            None,
        )
    }

    #[test]
    fn explicit_launch_overrides_environment_and_preserves_unicode_socket() {
        #[cfg(windows)]
        let socket = r"\\.\pipe\测试-helper";
        #[cfg(not(windows))]
        let socket = "/run/测试-helper.sock";
        let config = parse(
            &[
                "--client-pid",
                "42",
                "--socket",
                socket,
                "--control-plane-url",
                "https://control.example.test",
                "--check-startup",
            ],
            Some("7"),
        )
        .unwrap();
        assert_eq!(config.client_pid, 42);
        assert_eq!(config.socket, PathBuf::from(socket));
        assert_eq!(config.control_plane_url, "https://control.example.test");
        assert!(config.check_only);
        let inherited = parse(&[], Some("9")).unwrap();
        assert_eq!(inherited.client_pid, 9);
        assert!(!inherited.check_only);
    }

    #[test]
    fn ambiguous_or_insecure_launch_is_rejected() {
        for args in [
            vec!["--client-pid", "0"],
            vec!["--client-pid", "-1"],
            vec!["--client-pid"],
            vec!["--unknown"],
            vec!["--socket", ""],
            vec!["--socket", "relative/socket"],
            vec!["--client-pid", "1", "--client-pid", "2"],
            vec!["--control-plane-url", "http://example.test"],
            vec!["--control-plane-url", "https://user:secret@example.test"],
            vec!["--control-plane-url", "https://example.test/path"],
        ] {
            assert!(parse(&args, Some("1")).is_err());
        }
        assert!(parse(&[], None).is_err());
    }
}
