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
        let base = reqwest::Url::parse(&base_url).map_err(|_| "CONTROL_PLANE_URL_INVALID")?;
        let loopback = matches!(base.host_str(), Some("localhost" | "127.0.0.1" | "[::1]"));
        if (base.scheme() != "https" && !(base.scheme() == "http" && loopback))
            || !base.username().is_empty()
            || base.password().is_some()
            || base.query().is_some()
            || base.fragment().is_some()
            || base.path() != "/"
            || management_key.trim().is_empty()
        {
            return Err("CONTROL_PLANE_CONFIGURATION_INVALID".into());
        }
        let http = reqwest::blocking::Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            .connect_timeout(std::time::Duration::from_secs(1))
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
