#![cfg(windows)]

use std::io::{BufRead, Write};
use std::os::windows::process::CommandExt;
use std::process::{Child, Command, Stdio};
use std::time::{Duration, Instant};

struct Process(Child);

impl Drop for Process {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

#[test]
fn production_client_accepts_actual_sibling_helper_and_helper_exits_with_client() {
    let directory = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../target")
        .join(format!("helper-identity-{}", std::process::id()));
    std::fs::create_dir(&directory).unwrap();
    let result = std::panic::catch_unwind(|| {
        let client_image = directory.join("identity-client.exe");
        let helper_image = directory.join("zhijie-helper.exe");
        std::fs::copy(std::env::current_exe().unwrap(), &client_image).unwrap();
        std::fs::copy(env!("CARGO_BIN_EXE_zhijie-helper"), &helper_image).unwrap();
        let socket = format!(
            r"\\.\pipe\zhijie-identity-production-{}",
            std::process::id()
        );
        let mut client = Process(
            Command::new(client_image)
                .args([
                    "--ignored",
                    "--exact",
                    "production_client_fixture",
                    "--nocapture",
                ])
                .env("ZHIJIE_IDENTITY_TEST_SOCKET", &socket)
                .stdin(Stdio::piped())
                .stdout(Stdio::piped())
                .stderr(Stdio::null())
                .creation_flags(0x08000000)
                .spawn()
                .unwrap(),
        );
        let mut helper = Process(
            Command::new(helper_image)
                .args([
                    "--client-pid",
                    &client.0.id().to_string(),
                    "--socket",
                    &socket,
                    "--control-plane-url",
                    "http://127.0.0.1:8080",
                ])
                // 参数必须覆盖继承环境；错误环境不能改变这次启动绑定。
                .env("ZHIJIE_HELPER_CLIENT_PID", "0")
                .env("ZHIJIE_HELPER_SOCKET", "invalid inherited socket")
                .env("ZHIJIE_CONTROL_PLANE_URL", "http://invalid.example.test")
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .creation_flags(0x08000000)
                .spawn()
                .unwrap(),
        );
        writeln!(client.0.stdin.as_mut().unwrap(), "start").unwrap();
        let deadline = Instant::now() + Duration::from_secs(10);
        let status = loop {
            if let Some(status) = client.0.try_wait().unwrap() {
                break status;
            }
            assert!(Instant::now() < deadline, "production client deadline");
            std::thread::sleep(Duration::from_millis(20));
        };
        assert!(
            status.success(),
            "actual production client rejects valid sibling Helper"
        );
        let deadline = Instant::now() + Duration::from_secs(3);
        while helper.0.try_wait().unwrap().is_none() {
            assert!(
                Instant::now() < deadline,
                "Helper must exit when bound client exits"
            );
            std::thread::sleep(Duration::from_millis(20));
        }
    });
    for name in ["identity-client.exe", "zhijie-helper.exe"] {
        let path = directory.join(name);
        if path.exists() {
            std::fs::remove_file(path).unwrap();
        }
    }
    std::fs::remove_dir(&directory).unwrap();
    if let Err(error) = result {
        std::panic::resume_unwind(error);
    }
}

#[test]
fn production_startup_check_binds_live_process_without_creating_listener() {
    let socket = format!(r"\\.\pipe\zhijie-startup-check-{}", std::process::id());
    let mut command = Command::new(env!("CARGO_BIN_EXE_zhijie-helper"));
    command
        .args(["--check-startup", "--socket", &socket])
        .env("ZHIJIE_HELPER_CLIENT_PID", std::process::id().to_string())
        .env("ZHIJIE_CONTROL_PLANE_URL", "http://127.0.0.1:8080")
        .creation_flags(0x08000000);
    let output = command.output().unwrap();
    assert!(output.status.success());
    assert!(String::from_utf8_lossy(&output.stdout).contains("HELPER_STARTUP_OK"));
    use interprocess::local_socket::{prelude::*, GenericFilePath, Stream};
    assert!(Stream::connect(socket.as_str().to_fs_name::<GenericFilePath>().unwrap()).is_err());
    for args in [
        vec!["--client-pid", "0", "--check-startup"],
        vec!["--client-pid", "4294967295", "--check-startup"],
        vec![
            "--control-plane-url",
            "http://remote.example.test",
            "--check-startup",
        ],
    ] {
        let output = Command::new(env!("CARGO_BIN_EXE_zhijie-helper"))
            .args(args)
            .env("ZHIJIE_HELPER_CLIENT_PID", std::process::id().to_string())
            .creation_flags(0x08000000)
            .output()
            .unwrap();
        assert!(!output.status.success());
        assert!(!String::from_utf8_lossy(&output.stdout).contains("HELPER_STARTUP_OK"));
    }
}

#[test]
#[ignore = "subprocess fixture invoked only by server identity integration test"]
fn production_client_fixture() {
    let socket = std::env::var("ZHIJIE_IDENTITY_TEST_SOCKET").expect("parent test socket");
    let mut start = String::new();
    std::io::stdin().lock().read_line(&mut start).unwrap();
    let request = zhijie_helper::model::HelperRequest {
        protocol_version: 1,
        request_id: "identity-check".into(),
        method: "network.status".into(),
        activity_id: None,
        member_generation: None,
        policy_revision: None,
        ticket: None,
        start: None,
        peers: None,
        target_path: None,
    };
    let deadline = Instant::now() + Duration::from_secs(3);
    loop {
        match zhijie_helper::ipc::call(std::path::Path::new(&socket), &request) {
            Ok(response) => {
                assert!(response.accepted);
                assert!(response.data.is_some());
                break;
            }
            Err(error) if error == "HELPER_UNAVAILABLE" && Instant::now() < deadline => {
                std::thread::sleep(Duration::from_millis(20))
            }
            Err(error) => panic!("production Helper identity: {error}"),
        }
    }
}
