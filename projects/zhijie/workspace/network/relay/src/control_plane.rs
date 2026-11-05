use std::net::SocketAddr;

use crate::model::{RelayGrant, RelayValidationRequest};
use crate::server::GrantValidator;

pub struct ControlPlaneValidator {
    http: reqwest::blocking::Client,
    url: String,
    management_key: String,
}

impl ControlPlaneValidator {
    pub fn new(base_url: String, management_key: String) -> Result<Self, String> {
        let http = reqwest::blocking::Client::builder()
            .timeout(std::time::Duration::from_secs(2))
            .build()
            .map_err(|error| error.to_string())?;
        Ok(Self {
            http,
            url: format!(
                "{}/internal/relay/v1/validate",
                base_url.trim_end_matches('/')
            ),
            management_key,
        })
    }
}

impl GrantValidator for ControlPlaneValidator {
    fn validate(&self, ticket: &str, endpoint: SocketAddr) -> Result<RelayGrant, String> {
        self.http
            .post(&self.url)
            .header("X-Relay-Key", &self.management_key)
            .json(&RelayValidationRequest {
                ticket,
                client_endpoint: endpoint.to_string(),
            })
            .send()
            .map_err(|_| "CONTROL_PLANE_UNAVAILABLE".to_string())?
            .error_for_status()
            .map_err(|_| "RELAY_GRANT_INVALID".to_string())?
            .json::<RelayGrant>()
            .map_err(|_| "RELAY_GRANT_INVALID".to_string())
    }
}
