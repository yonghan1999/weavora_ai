use std::sync::{Arc, Mutex};

use base64::Engine;
use ipnet::Ipv4Net;
use time::OffsetDateTime;

use crate::model::{
    HelperRequest, HelperResponse, NetworkPath, NetworkStart, NetworkState, NetworkStatus,
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
}

pub struct HelperService {
    backend: Arc<dyn NetworkBackend>,
    status: Mutex<NetworkStatus>,
    current_request: Mutex<Option<NetworkStart>>,
}

impl HelperService {
    pub fn new(backend: Arc<dyn NetworkBackend>) -> Self {
        Self {
            backend,
            status: Mutex::new(NetworkStatus::default()),
            current_request: Mutex::new(None),
        }
    }

    pub fn handle(&self, request: HelperRequest) -> HelperResponse<NetworkStatus> {
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
        self.status.lock().expect("helper status lock").clone()
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
                status.active_path = Some(path);
                Ok(status.clone())
            }
            "network.stop" => {
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
            "network.retry" => {
                let current = self
                    .current_request
                    .lock()
                    .expect("helper request lock")
                    .clone()
                    .ok_or("NETWORK_NOT_STARTED")?;
                self.start(&current)
            }
            _ => Err("METHOD_NOT_ALLOWED".into()),
        }
    }

    fn start(&self, start: &NetworkStart) -> Result<NetworkStatus, String> {
        validate_start(start)?;
        {
            let mut status = self.status.lock().expect("helper status lock");
            *status = NetworkStatus {
                activity_id: Some(start.activity_id.clone()),
                member_generation: Some(start.member_generation),
                policy_revision: Some(start.policy_revision),
                state: NetworkState::Connecting,
                active_path: start.allowed_paths.first().copied(),
                virtual_ipv4: Some(start.virtual_ipv4.clone()),
                error_code: None,
            };
        }
        if let Err(code) = self.backend.start(start) {
            self.status.lock().expect("helper status lock").state = NetworkState::Failed;
            return Err(code);
        }
        *self.current_request.lock().expect("helper request lock") = Some(start.clone());
        self.status.lock().expect("helper status lock").state = NetworkState::Connected;
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
