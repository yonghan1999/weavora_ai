use serde::{Deserialize, Serialize};

#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RelayGrant {
    pub version: u16,
    pub ticket: String,
    pub activity_id: String,
    pub activity_generation: u64,
    pub member_generation: u64,
    pub lease_id: String,
    pub device_id: String,
    pub user_id: String,
    pub source_ipv4: String,
    pub policy_revision: u64,
    pub allow_relay: bool,
    pub expires_at_unix_millis: u64,
}

impl RelayGrant {
    pub(crate) fn same_session(&self, other: &Self) -> bool {
        self.version == 1
            && other.version == 1
            && self.ticket == other.ticket
            && self.activity_id == other.activity_id
            && self.activity_generation == other.activity_generation
            && self.member_generation == other.member_generation
            && self.lease_id == other.lease_id
            && self.device_id == other.device_id
            && self.user_id == other.user_id
            && self.source_ipv4 == other.source_ipv4
            && self.policy_revision == other.policy_revision
            && self.allow_relay
            && other.allow_relay
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RelayValidationRequest<'a> {
    pub ticket: &'a str,
    pub client_endpoint: String,
}
