use std::sync::{Arc, Mutex};

use base64::Engine;
use ipnet::Ipv4Net;
use time::OffsetDateTime;

use crate::model::{
    HelperRequest, HelperResponse, NetworkPath, NetworkRoute, NetworkStart, NetworkState,
    NetworkStatus,
};

pub trait NetworkBackend: Send + Sync {
    fn start(&self, request: &NetworkStart) -> Result<(), String>;
    fn update_peers(
        &self,
        activity_id: &str,
        peers: Vec<crate::model::NetworkPeer>,
    ) -> Result<(), String>;
    fn migrate_path(&self, activity_id: &str, path: NetworkPath) -> Result<(), String>;
    fn stop(&self, activity_id: &str) -> Result<(), String>;
    fn health(&self) -> Result<(), String> {
        Ok(())
    }
    fn connected(&self) -> bool {
        false
    }
    fn udp_port(&self) -> Option<u16> {
        None
    }
    fn active_path(&self) -> Option<NetworkRoute> {
        None
    }
    fn renew(&self, _request: &NetworkStart) -> Result<(), String> {
        Err("NETWORK_RENEW_UNAVAILABLE".into())
    }
}

pub struct HelperService {
    operations: Mutex<()>,
    backend: Arc<dyn NetworkBackend>,
    status: Mutex<NetworkStatus>,
    current_request: Mutex<Option<NetworkStart>>,
}

impl HelperService {
    pub(crate) fn shutdown(&self) -> Result<(), String> {
        let _operation = self.operations.lock().expect("helper operation lock");
        let mut current = self.current_request.lock().expect("helper request lock");
        if let Some(request) = current.as_ref() {
            self.backend.stop(&request.activity_id)?;
        }
        *current = None;
        *self.status.lock().expect("helper status lock") = NetworkStatus::default();
        Ok(())
    }

    pub fn new(backend: Arc<dyn NetworkBackend>) -> Self {
        Self {
            operations: Mutex::new(()),
            backend,
            status: Mutex::new(NetworkStatus::default()),
            current_request: Mutex::new(None),
        }
    }

    pub fn handle(&self, request: HelperRequest) -> HelperResponse<NetworkStatus> {
        let _operation = self.operations.lock().expect("helper operation lock");
        let result = self.dispatch(&request);
        match result {
            Ok(status) => HelperResponse {
                protocol_version: 1,
                request_id: request.request_id,
                accepted: true,
                error_code: None,
                data: Some(status),
            },
            Err(code) => HelperResponse {
                protocol_version: 1,
                request_id: request.request_id,
                accepted: false,
                error_code: Some(code.clone()),
                data: Some(self.status_with_error(code)),
            },
        }
    }

    pub fn status(&self) -> NetworkStatus {
        let mut status = self.status.lock().expect("helper status lock").clone();
        if matches!(
            status.state,
            NetworkState::Connected | NetworkState::Connecting
        ) {
            if let Err(code) = self.backend.health() {
                status.state = NetworkState::Failed;
                status.error_code = Some(code);
            } else {
                status.state = if self.backend.connected() {
                    NetworkState::Connected
                } else {
                    NetworkState::Connecting
                };
                status.udp_port = self.backend.udp_port();
                if let Some(path) = self.backend.active_path() {
                    status.active_path = Some(path);
                }
            }
        }
        status
    }

    fn dispatch(&self, request: &HelperRequest) -> Result<NetworkStatus, String> {
        if request.protocol_version != 1 {
            return Err("PROTOCOL_VERSION_UNSUPPORTED".into());
        }
        if request.request_id.is_empty() {
            return Err("REQUEST_ID_REQUIRED".into());
        }
        match request.method.as_str() {
            "helper.get_capabilities" => Ok(self.status()),
            "network.start" => self.start(request.start.as_ref().ok_or("START_REQUEST_REQUIRED")?),
            "network.update_peers" => {
                self.authorize(request)?;
                let peers = request.peers.clone().ok_or("PEERS_REQUIRED")?;
                self.backend
                    .update_peers(request.activity_id.as_ref().unwrap(), peers)?;
                Ok(self.status())
            }
            "network.migrate_path" => {
                self.authorize(request)?;
                let path = request.target_path.ok_or("TARGET_PATH_REQUIRED")?;
                let current = self.current_request.lock().expect("helper request lock");
                let current = current.as_ref().ok_or("NETWORK_NOT_STARTED")?;
                if !current.allowed_paths.contains(&path) {
                    return Err("PATH_NOT_ALLOWED".into());
                }
                self.backend
                    .migrate_path(request.activity_id.as_ref().unwrap(), path)?;
                let mut status = self.status.lock().expect("helper status lock");
                status.active_path = Some(path.into());
                Ok(status.clone())
            }
            "network.stop" => {
                if self
                    .current_request
                    .lock()
                    .expect("helper request lock")
                    .is_some()
                {
                    self.authorize(request)?;
                }
                let activity_id = request.activity_id.as_deref().ok_or("ACTIVITY_REQUIRED")?;
                self.backend.stop(activity_id)?;
                self.current_request
                    .lock()
                    .expect("helper request lock")
                    .take();
                *self.status.lock().expect("helper status lock") = NetworkStatus::default();
                Ok(self.status())
            }
            "network.status" => Ok(self.status()),
            "network.renew" => {
                self.authorize(request)?;
                let updated = request.start.as_ref().ok_or("START_REQUEST_REQUIRED")?;
                validate_start(updated)?;
                let current = self
                    .current_request
                    .lock()
                    .expect("helper request lock")
                    .clone()
                    .ok_or("NETWORK_NOT_STARTED")?;
                if updated.activity_id != current.activity_id
                    || updated.activity_generation != current.activity_generation
                    || updated.member_generation != current.member_generation
                    || updated.device_id != current.device_id
                    || updated.lease_id != current.lease_id
                    || updated.policy_revision != current.policy_revision
                    || updated.private_key != current.private_key
                    || updated.virtual_ipv4 != current.virtual_ipv4
                    || updated.activity_cidr != current.activity_cidr
                    || updated.allowed_paths != current.allowed_paths
                {
                    return Err("GENERATION_STALE".into());
                }
                self.backend.renew(updated)?;
                *self.current_request.lock().expect("helper request lock") = Some(updated.clone());
                Ok(self.status())
            }
            "network.retry" => {
                self.authorize(request)?;
                let current = self
                    .current_request
                    .lock()
                    .expect("helper request lock")
                    .clone()
                    .ok_or("NETWORK_NOT_STARTED")?;
                validate_start(&current)?;
                self.backend.stop(&current.activity_id)?;
                self.current_request
                    .lock()
                    .expect("helper request lock")
                    .take();
                self.start(&current)
            }
            _ => Err("METHOD_NOT_ALLOWED".into()),
        }
    }

    fn start(&self, start: &NetworkStart) -> Result<NetworkStatus, String> {
        validate_start(start)?;
        if self
            .current_request
            .lock()
            .expect("helper request lock")
            .is_some()
        {
            return Err("NETWORK_ALREADY_STARTED".into());
        }
        {
            let mut status = self.status.lock().expect("helper status lock");
            *status = NetworkStatus {
                activity_id: Some(start.activity_id.clone()),
                member_generation: Some(start.member_generation),
                policy_revision: Some(start.policy_revision),
                state: NetworkState::Connecting,
                active_path: start.allowed_paths.first().copied().map(Into::into),
                virtual_ipv4: Some(start.virtual_ipv4.clone()),
                error_code: None,
                udp_port: None,
            };
        }
        // 启动失败也保留已校验请求，重试和清理仍必须核对原票据与代次。
        *self.current_request.lock().expect("helper request lock") = Some(start.clone());
        if let Err(code) = self.backend.start(start) {
            let mut status = self.status.lock().expect("helper status lock");
            status.state = NetworkState::Failed;
            status.error_code = Some(code.clone());
            return Err(code);
        }
        Ok(self.status())
    }

    fn authorize(&self, request: &HelperRequest) -> Result<(), String> {
        let current = self.current_request.lock().expect("helper request lock");
        let current = current.as_ref().ok_or("NETWORK_NOT_STARTED")?;
        if request.activity_id.as_deref() != Some(&current.activity_id)
            || request.member_generation != Some(current.member_generation)
            || request.policy_revision != Some(current.policy_revision)
        {
            return Err("GENERATION_STALE".into());
        }
        let supplied = request.ticket.as_deref().unwrap_or("");
        if supplied.len() != current.ticket.len()
            || supplied
                .bytes()
                .zip(current.ticket.bytes())
                .fold(0u8, |difference, (a, b)| difference | (a ^ b))
                != 0
        {
            return Err("NETWORK_TICKET_REQUIRED".into());
        }
        Ok(())
    }

    fn status_with_error(&self, code: String) -> NetworkStatus {
        let mut status = self.status();
        status.error_code = Some(code);
        status
    }
}

fn validate_start(start: &NetworkStart) -> Result<(), String> {
    if start.protocol_version != 1 || start.ticket.is_empty() || start.allowed_paths.is_empty() {
        return Err("START_REQUEST_INVALID".into());
    }
    let expires = OffsetDateTime::parse(
        &start.expires_at,
        &time::format_description::well_known::Rfc3339,
    )
    .map_err(|_| "GRANT_EXPIRY_INVALID".to_string())?;
    if expires <= OffsetDateTime::now_utc() {
        return Err("GRANT_EXPIRED".into());
    }
    let network: Ipv4Net = start
        .activity_cidr
        .parse()
        .map_err(|_| "ACTIVITY_CIDR_INVALID".to_string())?;
    let address: std::net::Ipv4Addr = start
        .virtual_ipv4
        .parse()
        .map_err(|_| "VIRTUAL_IP_INVALID".to_string())?;
    if network.prefix_len() != 27
        || !network.contains(&address)
        || address == network.network()
        || address == network.broadcast()
    {
        return Err("ROUTE_NOT_ALLOWED".into());
    }
    let key = base64::engine::general_purpose::STANDARD
        .decode(&start.private_key)
        .map_err(|_| "PRIVATE_KEY_INVALID".to_string())?;
    if key.len() != 32 {
        return Err("PRIVATE_KEY_INVALID".into());
    }
    Ok(())
}
