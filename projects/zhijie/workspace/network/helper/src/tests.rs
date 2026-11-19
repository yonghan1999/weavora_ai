use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

use base64::Engine;
use time::OffsetDateTime;

use crate::model::{HelperRequest, NetworkPath, NetworkStart, NetworkState};
use crate::service::{HelperService, NetworkBackend};

#[derive(Default)]
struct RecordingBackend {
    calls: Mutex<Vec<String>>,
    fail_start: AtomicBool,
}

impl NetworkBackend for RecordingBackend {
    fn connected(&self) -> bool {
        true
    }
    fn renew(&self, _request: &NetworkStart) -> Result<(), String> {
        self.calls.lock().unwrap().push("renew".into());
        Ok(())
    }
    fn start(&self, _request: &NetworkStart) -> Result<(), String> {
        self.calls.lock().unwrap().push("start".into());
        if self.fail_start.load(Ordering::Relaxed) {
            return Err("TUN_PERMISSION_OR_CREATE_FAILED".into());
        }
        Ok(())
    }
    fn update_peers(
        &self,
        activity_id: &str,
        _peers: Vec<crate::model::NetworkPeer>,
    ) -> Result<(), String> {
        self.calls
            .lock()
            .unwrap()
            .push(format!("peers:{activity_id}"));
        Ok(())
    }
    fn migrate_path(&self, activity_id: &str, path: NetworkPath) -> Result<(), String> {
        self.calls
            .lock()
            .unwrap()
            .push(format!("migrate:{activity_id}:{path:?}"));
        Ok(())
    }
    fn stop(&self, activity_id: &str) -> Result<(), String> {
        self.calls
            .lock()
            .unwrap()
            .push(format!("stop:{activity_id}"));
        Ok(())
    }
}

pub(crate) fn start() -> NetworkStart {
    NetworkStart {
        protocol_version: 1,
        request_id: "request".into(),
        ticket: "ticket".into(),
        activity_id: "activity".into(),
        activity_generation: 1,
        member_generation: 2,
        device_id: "device".into(),
        lease_id: "lease".into(),
        policy_revision: 3,
        allowed_paths: vec![NetworkPath::Direct, NetworkPath::Relay],
        private_key: base64::engine::general_purpose::STANDARD.encode([7u8; 32]),
        virtual_ipv4: "10.231.0.1".into(),
        activity_cidr: "10.231.0.0/27".into(),
        expires_at: (OffsetDateTime::now_utc() + time::Duration::minutes(1))
            .format(&time::format_description::well_known::Rfc3339)
            .unwrap(),
    }
}

fn request(method: &str) -> HelperRequest {
    HelperRequest {
        protocol_version: 1,
        request_id: "request".into(),
        method: method.into(),
        activity_id: Some("activity".into()),
        member_generation: Some(2),
        policy_revision: Some(3),
        start: None,
        peers: Some(Vec::new()),
        target_path: None,
        ticket: Some("ticket".into()),
    }
}

#[test]
fn valid_start_and_stop_have_real_backend_lifecycle() {
    let backend = Arc::new(RecordingBackend::default());
    let service = HelperService::new(backend.clone());
    let mut start_request = request("network.start");
    start_request.start = Some(start());
    assert!(service.handle(start_request).accepted);
    assert_eq!(service.status().state, NetworkState::Connected);
    assert!(service.handle(request("network.stop")).accepted);
    assert_eq!(service.status().state, NetworkState::Disconnected);
    assert_eq!(
        backend.calls.lock().unwrap().as_slice(),
        ["start", "stop:activity"]
    );
}

#[test]
fn default_route_expired_grant_and_invalid_key_never_reach_system_backend() {
    let backend = Arc::new(RecordingBackend::default());
    let service = HelperService::new(backend.clone());
    let mut invalid = start();
    invalid.activity_cidr = "0.0.0.0/0".into();
    let mut request = request("network.start");
    request.start = Some(invalid);
    assert_eq!(
        service.handle(request).error_code.as_deref(),
        Some("ROUTE_NOT_ALLOWED")
    );
    assert!(backend.calls.lock().unwrap().is_empty());
}

#[test]
fn stale_generation_and_forbidden_path_are_rejected() {
    let backend = Arc::new(RecordingBackend::default());
    let service = HelperService::new(backend.clone());
    let mut start_request = request("network.start");
    start_request.start = Some(start());
    service.handle(start_request);
    let mut stale = request("network.update_peers");
    stale.member_generation = Some(1);
    assert_eq!(
        service.handle(stale).error_code.as_deref(),
        Some("GENERATION_STALE")
    );

    let mut restricted = start();
    restricted.allowed_paths = vec![NetworkPath::Direct];
    assert!(service.handle(request("network.stop")).accepted);
    let mut start_request = request("network.start");
    start_request.start = Some(restricted);
    service.handle(start_request);
    let mut migrate = request("network.migrate_path");
    migrate.target_path = Some(NetworkPath::Relay);
    assert_eq!(
        service.handle(migrate).error_code.as_deref(),
        Some("PATH_NOT_ALLOWED")
    );
}

#[test]
fn backend_failure_is_reported_without_connected_state() {
    let backend = Arc::new(RecordingBackend {
        calls: Mutex::new(Vec::new()),
        fail_start: AtomicBool::new(true),
    });
    let service = HelperService::new(backend);
    let mut initial = request("network.start");
    initial.start = Some(start());
    assert_eq!(
        service.handle(initial).error_code.as_deref(),
        Some("TUN_PERMISSION_OR_CREATE_FAILED")
    );
    assert_eq!(service.status().state, NetworkState::Failed);
    assert_eq!(
        service
            .handle(request("network.status"))
            .data
            .unwrap()
            .error_code
            .as_deref(),
        Some("TUN_PERMISSION_OR_CREATE_FAILED")
    );
}

#[test]
fn failed_start_keeps_authorized_retry_and_clears_error_after_recovery() {
    let backend = Arc::new(RecordingBackend {
        fail_start: AtomicBool::new(true),
        ..RecordingBackend::default()
    });
    let service = HelperService::new(backend.clone());
    let mut initial = request("network.start");
    initial.start = Some(start());
    assert!(!service.handle(initial).accepted);
    let mut stale = request("network.retry");
    stale.member_generation = Some(1);
    assert_eq!(
        service.handle(stale).error_code.as_deref(),
        Some("GENERATION_STALE")
    );
    let mut wrong_ticket = request("network.retry");
    wrong_ticket.ticket = Some("other".into());
    assert_eq!(
        service.handle(wrong_ticket).error_code.as_deref(),
        Some("NETWORK_TICKET_REQUIRED")
    );
    assert_eq!(backend.calls.lock().unwrap().as_slice(), ["start"]);
    assert_eq!(
        service
            .handle(request("network.retry"))
            .error_code
            .as_deref(),
        Some("TUN_PERMISSION_OR_CREATE_FAILED")
    );
    assert_eq!(
        service.status().error_code.as_deref(),
        Some("TUN_PERMISSION_OR_CREATE_FAILED")
    );
    backend.fail_start.store(false, Ordering::Relaxed);
    let recovered = service.handle(request("network.retry"));
    assert!(recovered.accepted);
    let status = recovered.data.unwrap();
    assert_eq!(status.state, NetworkState::Connected);
    assert!(status.error_code.is_none());
    assert_eq!(
        backend.calls.lock().unwrap().as_slice(),
        ["start", "stop:activity", "start", "stop:activity", "start"]
    );
    assert!(service.handle(request("network.stop")).accepted);
    assert_eq!(service.status().state, NetworkState::Disconnected);
    assert!(service.status().error_code.is_none());
}

#[test]
fn failed_start_cannot_be_replaced_without_cleanup_and_shutdown_still_stops_it() {
    let backend = Arc::new(RecordingBackend {
        fail_start: AtomicBool::new(true),
        ..RecordingBackend::default()
    });
    let service = HelperService::new(backend.clone());
    let mut initial = request("network.start");
    initial.start = Some(start());
    assert!(!service.handle(initial).accepted);
    let mut replacement = request("network.start");
    replacement.start = Some(NetworkStart {
        ticket: "replacement".into(),
        ..start()
    });
    assert_eq!(
        service.handle(replacement).error_code.as_deref(),
        Some("NETWORK_ALREADY_STARTED")
    );
    assert_eq!(
        service.status().error_code.as_deref(),
        Some("TUN_PERMISSION_OR_CREATE_FAILED")
    );
    assert_eq!(backend.calls.lock().unwrap().as_slice(), ["start"]);
    service.shutdown().unwrap();
    assert_eq!(
        backend.calls.lock().unwrap().as_slice(),
        ["start", "stop:activity"]
    );
    assert_eq!(service.status().state, NetworkState::Disconnected);
    assert!(service.status().error_code.is_none());
}

#[test]
fn stale_stop_and_retry_cannot_control_the_current_network() {
    let backend = Arc::new(RecordingBackend::default());
    let service = HelperService::new(backend.clone());
    let mut initial = request("network.start");
    initial.start = Some(start());
    assert!(service.handle(initial).accepted);
    for method in ["network.stop", "network.retry"] {
        let mut stale = request(method);
        stale.member_generation = Some(1);
        assert_eq!(
            service.handle(stale).error_code.as_deref(),
            Some("GENERATION_STALE")
        );
    }
    assert_eq!(backend.calls.lock().unwrap().as_slice(), ["start"]);
    assert!(service.handle(request("network.retry")).accepted);
    assert_eq!(
        backend.calls.lock().unwrap().as_slice(),
        ["start", "stop:activity", "start"]
    );
}

#[test]
fn ipc_server_performs_a_real_length_delimited_local_socket_round_trip() {
    use interprocess::local_socket::prelude::*;
    use std::io::{Read, Write};

    #[cfg(windows)]
    let path = std::path::PathBuf::from(format!(r"\\.\pipe\zhijie-helper-{}", std::process::id()));
    #[cfg(not(windows))]
    let path = std::env::temp_dir().join(format!("zhijie-helper-{}.sock", std::process::id()));
    let listener = crate::ipc::bind(&path).unwrap();
    let service = Arc::new(HelperService::new(Arc::new(RecordingBackend::default())));
    let server = crate::ipc::HelperIpcServer::new(service);
    let worker = std::thread::spawn(move || server.serve_once(&listener));
    let name = path
        .clone()
        .to_fs_name::<interprocess::local_socket::GenericFilePath>()
        .unwrap();
    let mut stream = interprocess::local_socket::Stream::connect(name).unwrap();
    let body = serde_json::to_vec(&request("network.status")).unwrap();
    stream
        .write_all(&(body.len() as u32).to_be_bytes())
        .unwrap();
    stream.write_all(&body).unwrap();
    let mut length = [0u8; 4];
    stream.read_exact(&mut length).unwrap();
    let mut response = vec![0u8; u32::from_be_bytes(length) as usize];
    stream.read_exact(&mut response).unwrap();
    let response: crate::model::HelperResponse<crate::model::NetworkStatus> =
        serde_json::from_slice(&response).unwrap();
    assert!(response.accepted);
    worker.join().unwrap().unwrap();
    let _ = std::fs::remove_file(path);
}

#[test]
fn ipc_client_matches_response_and_other_process_is_rejected() {
    #[cfg(windows)]
    let path = std::path::PathBuf::from(format!(
        r"\\.\pipe\zhijie-helper-client-{}",
        std::process::id()
    ));
    #[cfg(not(windows))]
    let path =
        std::env::temp_dir().join(format!("zhijie-helper-client-{}.sock", std::process::id()));
    let service = Arc::new(HelperService::new(Arc::new(RecordingBackend::default())));
    let allowed =
        crate::ipc::HelperIpcServer::for_client(service.clone(), std::process::id()).unwrap();
    let listener = allowed.bind(&path).unwrap();
    // 另一个真实存活进程，避免拿不存在的 PID 跳过进程身份绑定。
    let child = TestClientProcess::spawn();
    let denied = crate::ipc::HelperIpcServer::for_client(service, child.0.id()).unwrap();
    let worker = std::thread::spawn(move || {
        allowed.serve_once(&listener).unwrap();
        assert_eq!(
            denied.serve_once(&listener).unwrap_err(),
            "IPC_PEER_NOT_ALLOWED"
        );
    });
    assert!(
        call_test_server(&path, &request("network.status"))
            .unwrap()
            .accepted
    );
    assert!(call_test_server(&path, &request("network.status")).is_err());
    worker.join().unwrap();
    let _ = std::fs::remove_file(path);
}

fn call_test_server(
    path: &std::path::Path,
    request: &HelperRequest,
) -> Result<crate::model::HelperResponse<crate::model::NetworkStatus>, String> {
    #[cfg(windows)]
    {
        crate::ipc::call_with_server_image(path, request, &std::env::current_exe().unwrap())
    }
    #[cfg(not(windows))]
    {
        crate::ipc::call(path, request)
    }
}

#[cfg(windows)]
#[test]
fn ipc_rejects_same_account_impostor_before_sending_any_secret() {
    use interprocess::local_socket::prelude::*;
    use std::io::Read;
    for scenario in 0..2 {
        let path = std::path::PathBuf::from(format!(
            r"\\.\pipe\zhijie-helper-impostor-{}-{scenario}",
            std::process::id()
        ));
        let listener = crate::ipc::bind(&path).unwrap();
        listener
            .set_nonblocking(interprocess::local_socket::ListenerNonblockingMode::Both)
            .unwrap();
        let (accepted, connected) = std::sync::mpsc::channel();
        let worker = std::thread::spawn(move || {
            let deadline = std::time::Instant::now() + std::time::Duration::from_secs(3);
            let mut stream = loop {
                match listener.accept() {
                    Ok(stream) => break stream,
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {}
                    Err(error) => panic!("accept: {error}"),
                }
                assert!(
                    std::time::Instant::now() < deadline,
                    "impostor connection deadline"
                );
                std::thread::sleep(std::time::Duration::from_millis(5));
            };
            accepted.send(()).unwrap();
            let deadline = std::time::Instant::now() + std::time::Duration::from_millis(200);
            loop {
                let mut byte = [0u8; 1];
                match stream.read(&mut byte) {
                    Ok(count) => {
                        assert_eq!(count, 0, "untrusted server must receive no request bytes")
                    }
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {}
                    Err(_) => break,
                }
                if std::time::Instant::now() >= deadline {
                    break;
                }
                std::thread::sleep(std::time::Duration::from_millis(5));
            }
        });
        let mut sensitive = request("network.start");
        sensitive.start = Some(start());
        let name = path
            .to_fs_name::<interprocess::local_socket::GenericFilePath>()
            .unwrap();
        let stream = interprocess::local_socket::Stream::connect(name).unwrap();
        connected
            .recv_timeout(std::time::Duration::from_secs(3))
            .unwrap();
        let error = if scenario == 0 {
            // 预期 Helper 映像缺失时拒绝发送。
            crate::ipc::call_connected(
                stream,
                &sensitive,
                &std::env::current_exe()
                    .unwrap()
                    .with_file_name("missing-helper-image"),
            )
            .err()
        } else {
            // 存在的预期文件与实际映像不同，也必须零字节拒绝。
            let expected = std::env::current_exe()
                .unwrap()
                .with_file_name("expected-impostor-image");
            let file = std::fs::OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(&expected)
                .unwrap();
            let error = crate::ipc::call_connected(stream, &sensitive, &expected).err();
            drop(file);
            std::fs::remove_file(expected).unwrap();
            error
        };
        worker.join().unwrap();
        assert_eq!(
            error.as_deref(),
            Some(if scenario == 0 {
                "IPC_SERVER_IDENTITY_UNAVAILABLE"
            } else {
                "IPC_SERVER_NOT_ALLOWED"
            })
        );
    }
}

struct TestClientProcess(std::process::Child);

impl TestClientProcess {
    fn spawn() -> Self {
        let mut command = std::process::Command::new(std::env::current_exe().unwrap());
        command
            .args([
                "--ignored",
                "--nocapture",
                "--exact",
                "tests::ipc_test_client_process",
            ])
            .env("ZHIJIE_IPC_CHILD_TEST", "1")
            .stdin(std::process::Stdio::piped())
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::null());
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            command.creation_flags(0x08000000); // CREATE_NO_WINDOW，测试不弹出控制台。
        }
        Self(command.spawn().unwrap())
    }
}

impl Drop for TestClientProcess {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

#[test]
#[ignore = "subprocess fixture invoked only by IPC identity tests"]
fn ipc_test_client_process() {
    if std::env::var("ZHIJIE_IPC_CHILD_TEST").as_deref() == Ok("1") {
        use std::io::{BufRead, Read, Write};
        let mut path = String::new();
        std::io::stdin().lock().read_line(&mut path).unwrap();
        if let Some(path) = path.trim().strip_prefix("PARTIAL_RESPONSE ") {
            let listener = crate::ipc::bind(std::path::Path::new(path)).unwrap();
            println!("IPC_CHILD_LISTENING");
            std::io::stdout().flush().unwrap();
            use interprocess::local_socket::prelude::*;
            let mut stream = listener.accept().unwrap();
            let mut length = [0; 4];
            stream.read_exact(&mut length).unwrap();
            let mut body = vec![0; u32::from_be_bytes(length) as usize];
            stream.read_exact(&mut body).unwrap();
            stream.write_all(&[0]).unwrap();
            println!("IPC_CHILD_RESPONSE_PARTIAL");
            std::io::stdout().flush().unwrap();
            let _ = std::io::stdin().read(&mut [0]);
            return;
        }
        if let Some(path) = path.trim().strip_prefix("PARTIAL ") {
            use interprocess::local_socket::prelude::*;
            let name = std::path::Path::new(path)
                .to_fs_name::<interprocess::local_socket::GenericFilePath>()
                .unwrap();
            let mut stream = interprocess::local_socket::Stream::connect(name).unwrap();
            stream.write_all(&[0]).unwrap();
            println!("IPC_CHILD_PARTIAL");
            std::io::stdout().flush().unwrap();
            let mut buffer = [0; 1];
            let _ = std::io::stdin().read(&mut buffer);
            return;
        }
        if !path.trim().is_empty() {
            for _ in 0..2 {
                assert!(
                    call_test_server(
                        std::path::Path::new(path.trim()),
                        &request("network.status")
                    )
                    .unwrap()
                    .accepted
                );
            }
            println!("IPC_CHILD_ACCEPTED");
            std::io::stdout().flush().unwrap();
        }
        let mut buffer = [0; 1];
        let _ = std::io::stdin().read(&mut buffer);
    }
}

#[cfg(windows)]
#[test]
fn ipc_client_detects_original_server_exit_during_partial_response() {
    use std::io::{BufRead, Write};
    let mut child = TestClientProcess::spawn();
    let path = std::path::PathBuf::from(format!(
        r"\\.\pipe\zhijie-helper-partial-response-{}",
        std::process::id()
    ));
    writeln!(
        child.0.stdin.as_mut().unwrap(),
        "PARTIAL_RESPONSE {}",
        path.display()
    )
    .unwrap();
    let output = child.0.stdout.take().unwrap();
    let (signals, received) = std::sync::mpsc::channel();
    let reader = std::thread::spawn(move || {
        for line in std::io::BufReader::new(output).lines() {
            let line = line.unwrap();
            if line.starts_with("IPC_CHILD_") {
                let _ = signals.send(line);
            }
        }
    });
    assert_eq!(
        received
            .recv_timeout(std::time::Duration::from_secs(3))
            .unwrap(),
        "IPC_CHILD_LISTENING"
    );
    let (done, result) = std::sync::mpsc::channel();
    let worker = std::thread::spawn(move || {
        let _ = done.send(call_test_server(&path, &request("network.status")));
    });
    let partial = received.recv_timeout(std::time::Duration::from_secs(3));
    std::thread::sleep(std::time::Duration::from_millis(100));
    let exited = std::time::Instant::now();
    drop(child);
    reader.join().unwrap();
    partial.unwrap();
    let outcome = result.recv_timeout(std::time::Duration::from_secs(2));
    worker.join().unwrap();
    assert_eq!(outcome.unwrap().err().as_deref(), Some("IPC_SERVER_EXITED"));
    assert!(exited.elapsed() < std::time::Duration::from_secs(2));
}

#[cfg(windows)]
#[test]
fn ipc_bound_process_exit_during_partial_request_cleans_session_promptly() {
    let mut child = TestClientProcess::spawn();
    let backend = Arc::new(RecordingBackend::default());
    let service = Arc::new(HelperService::new(backend.clone()));
    let mut command = request("network.start");
    command.start = Some(start());
    assert!(service.handle(command).accepted);
    let server = crate::ipc::HelperIpcServer::for_client(service.clone(), child.0.id()).unwrap();
    let path = std::path::PathBuf::from(format!(
        r"\\.\pipe\zhijie-helper-partial-{}",
        std::process::id()
    ));
    let listener = server.bind(&path).unwrap();
    let (done, result) = std::sync::mpsc::channel();
    let worker = std::thread::spawn(move || {
        let _ = done.send(server.serve(&listener));
    });
    use std::io::{BufRead, Write};
    writeln!(
        child.0.stdin.as_mut().unwrap(),
        "PARTIAL {}",
        path.display()
    )
    .unwrap();
    let output = child.0.stdout.take().unwrap();
    let (ready, received) = std::sync::mpsc::channel();
    let reader = std::thread::spawn(move || {
        for line in std::io::BufReader::new(output).lines() {
            if line.unwrap().contains("IPC_CHILD_PARTIAL") {
                let _ = ready.send(());
                break;
            }
        }
    });
    let accepted = received.recv_timeout(std::time::Duration::from_secs(3));
    // 让生产循环实际进入未完成帧的读取；退出不能等待原有五秒读超时。
    std::thread::sleep(std::time::Duration::from_millis(100));
    drop(child);
    reader.join().unwrap();
    accepted.unwrap();
    let outcome = result.recv_timeout(std::time::Duration::from_secs(2));
    worker.join().unwrap();
    assert_eq!(outcome.unwrap().unwrap_err(), "IPC_CLIENT_EXITED");
    assert_eq!(
        backend.calls.lock().unwrap().as_slice(),
        ["start", "stop:activity"]
    );
    assert_eq!(service.status().state, NetworkState::Disconnected);
}

#[cfg(windows)]
#[test]
fn ipc_bound_process_exit_stops_idle_server_and_cleans_session() {
    let mut child = TestClientProcess::spawn();
    let backend = Arc::new(RecordingBackend::default());
    let service = Arc::new(HelperService::new(backend.clone()));
    let mut command = request("network.start");
    command.start = Some(start());
    assert!(service.handle(command).accepted);
    let server = crate::ipc::HelperIpcServer::for_client(service.clone(), child.0.id()).unwrap();
    let path = std::path::PathBuf::from(format!(
        r"\\.\pipe\zhijie-helper-lifecycle-{}",
        std::process::id()
    ));
    let listener = server.bind(&path).unwrap();
    assert!(crate::ipc::HelperIpcServer::for_client(service.clone(), u32::MAX).is_err());
    let (done, result) = std::sync::mpsc::channel();
    let worker = std::thread::spawn(move || {
        let _ = done.send(server.serve(&listener));
    });
    use std::io::{BufRead, Write};
    writeln!(child.0.stdin.as_mut().unwrap(), "{}", path.display()).unwrap();
    let child_output = child.0.stdout.take().unwrap();
    let (output, received) = std::sync::mpsc::channel();
    let reader = std::thread::spawn(move || {
        for line in std::io::BufReader::new(child_output).lines() {
            if line.unwrap().contains("IPC_CHILD_ACCEPTED") {
                let _ = output.send(());
                break;
            }
        }
    });
    let accepted = received.recv_timeout(std::time::Duration::from_secs(3));
    drop(child);
    reader.join().unwrap();
    accepted.expect("bound child must complete two real requests through production serve loop");
    assert_eq!(
        result
            .recv_timeout(std::time::Duration::from_secs(2))
            .unwrap()
            .unwrap_err(),
        "IPC_CLIENT_EXITED"
    );
    worker.join().unwrap();
    assert_eq!(
        backend.calls.lock().unwrap().as_slice(),
        ["start", "stop:activity"]
    );
    assert_eq!(service.status().state, NetworkState::Disconnected);
}

#[test]
fn renewal_requires_the_current_secret_and_preserves_key_identity_and_policy() {
    let backend = Arc::new(RecordingBackend::default());
    let service = HelperService::new(backend.clone());
    let initial = start();
    let mut command = request("network.start");
    command.start = Some(initial.clone());
    assert!(service.handle(command).accepted);
    let mut wrong_secret = request("network.stop");
    wrong_secret.ticket = Some("wrong".into());
    assert_eq!(
        service.handle(wrong_secret).error_code.as_deref(),
        Some("NETWORK_TICKET_REQUIRED")
    );
    let mut altered = initial.clone();
    altered.private_key = base64::engine::general_purpose::STANDARD.encode([8; 32]);
    let mut command = request("network.renew");
    command.start = Some(altered);
    assert_eq!(
        service.handle(command).error_code.as_deref(),
        Some("GENERATION_STALE")
    );
    let mut fresh = initial.clone();
    fresh.ticket = "fresh-ticket".into();
    let mut command = request("network.renew");
    command.start = Some(fresh);
    assert!(service.handle(command).accepted);
    assert_eq!(
        service
            .handle(request("network.stop"))
            .error_code
            .as_deref(),
        Some("NETWORK_TICKET_REQUIRED")
    );
    let mut stop = request("network.stop");
    stop.ticket = Some("fresh-ticket".into());
    assert!(service.handle(stop).accepted);
    assert_eq!(
        backend.calls.lock().unwrap().as_slice(),
        ["start", "renew", "stop:activity"]
    );
}
