use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "UPPERCASE")]
pub enum NetworkPath {
    Direct,
    Relay,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "UPPERCASE")]
pub enum NetworkState {
    Disconnected,
    Connecting,
    Connected,
    Failed,
    Retrying,
    Revoked,
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NetworkStart {
    pub protocol_version: u16,
    pub request_id: String,
    pub ticket: String,
    pub activity_id: String,
    pub activity_generation: u64,
    pub member_generation: u64,
    pub device_id: String,
    pub lease_id: String,
    pub policy_revision: u64,
    pub allowed_paths: Vec<NetworkPath>,
    pub private_key: String,
    pub virtual_ipv4: String,
    pub activity_cidr: String,
    pub expires_at: String,
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NetworkPeer {
    pub user_id: String,
    pub virtual_ipv4: String,
    pub public_key: String,
    pub endpoint: Option<String>,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HelperRequest {
    pub protocol_version: u16,
    pub request_id: String,
    pub method: String,
    pub activity_id: Option<String>,
    pub member_generation: Option<u64>,
    pub policy_revision: Option<u64>,
    pub start: Option<NetworkStart>,
    pub peers: Option<Vec<NetworkPeer>>,
    pub target_path: Option<NetworkPath>,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HelperResponse<T> {
    pub protocol_version: u16,
    pub request_id: String,
    pub accepted: bool,
    pub error_code: Option<String>,
    pub data: Option<T>,
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NetworkStatus {
    pub activity_id: Option<String>,
    pub member_generation: Option<u64>,
    pub policy_revision: Option<u64>,
    pub state: NetworkState,
    pub active_path: Option<NetworkPath>,
    pub virtual_ipv4: Option<String>,
    pub error_code: Option<String>,
}

impl Default for NetworkStatus {
    fn default() -> Self {
        Self {
            activity_id: None,
            member_generation: None,
            policy_revision: None,
            state: NetworkState::Disconnected,
            active_path: None,
            virtual_ipv4: None,
            error_code: None,
        }
    }
}
