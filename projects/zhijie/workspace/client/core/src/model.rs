use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "UPPERCASE")]
pub enum ChannelStatus {
    Disconnected,
    Connecting,
    Connected,
    Failed,
    Retrying,
    Revoked,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ActivityResult {
    pub activity_id: String,
    pub activity_generation: u64,
    pub member_generation: u64,
    pub device_id: String,
    pub lease_id: String,
    pub policy_revision: u64,
    pub voice_state: ChannelStatus,
    pub network_state: ChannelStatus,
    pub virtual_ipv4: String,
    pub activity_cidr: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ActivityContext {
    pub activity_id: String,
    pub activity_generation: u64,
    pub member_generation: u64,
    pub device_id: String,
    pub lease_id: String,
    pub virtual_ipv4: Option<String>,
    pub voice: ChannelStatus,
    pub network: ChannelStatus,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ClientSnapshot {
    pub user_id: Option<String>,
    pub activity: Option<ActivityContext>,
    pub service: ChannelStatus,
    pub last_error_code: Option<String>,
}
