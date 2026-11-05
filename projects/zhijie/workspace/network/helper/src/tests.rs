use std::sync::{Arc, Mutex};

use base64::Engine;
use time::OffsetDateTime;

use crate::model::{HelperRequest, NetworkPath, NetworkStart, NetworkState};
use crate::service::{HelperService, NetworkBackend};

#[derive(Default)]
struct RecordingBackend {
    calls: Mutex<Vec<String>>,
    fail_start: bool,
}

impl NetworkBackend for RecordingBackend {
    fn start(&self, _request: &NetworkStart) -> Result<(), String> {
        if self.fail_start {
            return Err("TUN_PERMISSION_OR_CREATE_FAILED".into());
        }
        self.calls.lock().unwrap().push("start".into());
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

fn start() -> NetworkStart {
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
        fail_start: true,
    });
    let service = HelperService::new(backend);
    let mut request = request("network.start");
    request.start = Some(start());
    assert_eq!(
        service.handle(request).error_code.as_deref(),
        Some("TUN_PERMISSION_OR_CREATE_FAILED")
    );
    assert_eq!(service.status().state, NetworkState::Failed);
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
