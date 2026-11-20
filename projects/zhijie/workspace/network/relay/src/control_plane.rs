use std::io::Read;
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
            .map_err(|_| "CONTROL_PLANE_CONFIGURATION_INVALID")?;
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
        let response = self
            .http
            .post(&self.url)
            .header("X-Relay-Key", &self.management_key)
            .json(&RelayValidationRequest {
                ticket,
                client_endpoint: endpoint.to_string(),
            })
            .send()
            .map_err(|_| "CONTROL_PLANE_UNAVAILABLE".to_string())?;
        if response.status() != reqwest::StatusCode::OK {
            return Err("RELAY_GRANT_INVALID".into());
        }
        let mut bytes = Vec::new();
        response
            .take(4097)
            .read_to_end(&mut bytes)
            .map_err(|_| "RELAY_GRANT_INVALID")?;
        if bytes.len() > 4096 {
            return Err("RELAY_GRANT_INVALID".into());
        }
        serde_json::from_slice::<RelayGrant>(&bytes).map_err(|_| "RELAY_GRANT_INVALID".into())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use std::net::TcpListener;

    #[test]
    fn response_requires_exact_success_bounded_body_and_versioned_snapshot_fields() {
        let valid = serde_json::json!({"version": 1, "ticket": "ticket", "activityId": "activity",
            "activityGeneration": 1, "memberGeneration": 1, "leaseId": "lease", "deviceId": "device",
            "userId": "user", "sourceIpv4": "10.231.0.1", "policyRevision": 1,
            "allowRelay": true, "expiresAtUnixMillis": 12345});
        let mut legacy = valid.clone();
        legacy
            .as_object_mut()
            .unwrap()
            .remove("expiresAtUnixMillis");
        for (status, body, accepted) in [
            (200, valid.to_string(), true),
            (201, valid.to_string(), false),
            (302, valid.to_string(), false),
            (403, valid.to_string(), false),
            (200, legacy.to_string(), false),
            (200, "x".repeat(4097), false),
        ] {
            let listener = TcpListener::bind("127.0.0.1:0").unwrap();
            let address = listener.local_addr().unwrap();
            let worker = std::thread::spawn(move || {
                let (mut stream, _) = listener.accept().unwrap();
                stream
                    .set_read_timeout(Some(std::time::Duration::from_secs(2)))
                    .unwrap();
                let mut request = Vec::new();
                let mut buffer = [0u8; 1024];
                loop {
                    let count = stream.read(&mut buffer).unwrap();
                    if count == 0 {
                        break;
                    }
                    request.extend_from_slice(&buffer[..count]);
                    if request.windows(4).any(|value| value == b"\r\n\r\n") {
                        break;
                    }
                }
                let response = format!("HTTP/1.1 {status} Test\r\nContent-Length: {}\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n{body}", body.len());
                let _ = stream.write_all(response.as_bytes());
            });
            let validator = ControlPlaneValidator::new(
                format!("http://{address}"),
                "private-validation-key".into(),
            )
            .unwrap();
            let result = validator.validate("ticket", "127.0.0.1:10000".parse().unwrap());
            assert_eq!(result.is_ok(), accepted);
            if let Err(error) = result {
                assert_eq!(error, "RELAY_GRANT_INVALID");
            }
            worker.join().unwrap();
        }
    }
}
