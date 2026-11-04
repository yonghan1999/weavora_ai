use serde::{Deserialize, Serialize};

pub const COMMAND_PROTOCOL_VERSION: u16 = 1;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CommandRequest<T> {
    pub protocol_version: u16,
    pub request_id: String,
    pub activity_id: Option<String>,
    pub member_generation: Option<u64>,
    pub device_id: Option<String>,
    pub payload: T,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CommandResponse<T> {
    pub protocol_version: u16,
    pub request_id: String,
    pub accepted: bool,
    pub error_code: Option<String>,
    pub data: Option<T>,
}

impl<T> CommandRequest<T> {
    pub fn validate(&self) -> Result<(), &'static str> {
        if self.protocol_version != COMMAND_PROTOCOL_VERSION {
            return Err("PROTOCOL_VERSION_UNSUPPORTED");
        }
        if self.request_id.is_empty() {
            return Err("REQUEST_ID_REQUIRED");
        }
        Ok(())
    }
}
