use serde::{Deserialize, Serialize};

#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RelayGrant {
    pub ticket: String,
    pub activity_id: String,
    pub member_generation: u64,
    pub lease_id: String,
    pub user_id: String,
    pub source_ipv4: String,
    pub policy_revision: u64,
    pub allow_relay: bool,
}

#[derive(Clone, PartialEq, Eq)]
pub struct RelaySession {
    pub activity_id: String,
    pub member_generation: u64,
    pub lease_id: String,
    pub user_id: String,
    pub source_ipv4: String,
    pub policy_revision: u64,
    pub endpoint: std::net::SocketAddr,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RelayValidationRequest<'a> {
    pub ticket: &'a str,
    pub client_endpoint: String,
}
