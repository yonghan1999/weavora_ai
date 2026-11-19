use base64::Engine;
use boringtun::x25519::{PublicKey, StaticSecret};
use serde::Deserialize;
use time::OffsetDateTime;

use crate::model::{NetworkPath, NetworkPeer, NetworkStart};

#[derive(Clone)]
pub(crate) struct NetworkAuthority {
    http: reqwest::Client,
    url: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct AuthorizedNetwork {
    activity_id: String,
    activity_generation: u64,
    member_generation: u64,
    device_id: String,
    lease_id: String,
    policy_revision: u64,
    allowed_paths: Vec<NetworkPath>,
    virtual_ipv4: String,
    activity_cidr: String,
    public_key: String,
    expires_at: String,
    pub peers: Vec<NetworkPeer>,
}

impl NetworkAuthority {
    pub async fn validate_until(
        &self,
        request: &NetworkStart,
        expires: OffsetDateTime,
    ) -> Result<AuthorizedNetwork, String> {
        let remaining = expires - OffsetDateTime::now_utc();
        if remaining <= time::Duration::ZERO {
            return Err("GRANT_EXPIRED".into());
        }
        let result = tokio::time::timeout(remaining.unsigned_abs(), self.validate(request))
            .await
            .map_err(|_| "GRANT_EXPIRED".to_string())?;
        // 截止与响应同时就绪时仍以授权到期为准。
        if OffsetDateTime::now_utc() >= expires {
            return Err("GRANT_EXPIRED".into());
        }
        result
    }

    pub fn new(base_url: &str) -> Result<Self, String> {
        validate_base_url(base_url)?;
        Ok(Self {
            http: reqwest::Client::builder()
                .redirect(reqwest::redirect::Policy::none())
                .connect_timeout(std::time::Duration::from_secs(1))
                .timeout(std::time::Duration::from_secs(2))
                .build()
                .map_err(|_| "CONTROL_PLANE_UNAVAILABLE")?,
            url: format!(
                "{}/api/v1/network-access/validate",
                base_url.trim_end_matches('/')
            ),
        })
    }

    pub async fn validate(&self, request: &NetworkStart) -> Result<AuthorizedNetwork, String> {
        let response = self
            .http
            .post(&self.url)
            .json(&serde_json::json!({"ticket": request.ticket}))
            .send()
            .await
            .map_err(|_| "CONTROL_PLANE_UNAVAILABLE")?;
        if !response.status().is_success() {
            return Err(if response.status().is_client_error() {
                "NETWORK_GRANT_INVALID"
            } else {
                "CONTROL_PLANE_UNAVAILABLE"
            }
            .into());
        }
        let current: AuthorizedNetwork = response
            .json()
            .await
            .map_err(|_| "NETWORK_AUTHORITY_RESPONSE_INVALID")?;
        current.verify(request)?;
        Ok(current)
    }
}

pub(crate) fn validate_base_url(base_url: &str) -> Result<(), String> {
    let url = reqwest::Url::parse(base_url).map_err(|_| "CONTROL_PLANE_URL_INVALID")?;
    let loopback = matches!(url.host_str(), Some("127.0.0.1" | "localhost" | "[::1]"));
    if (url.scheme() != "https" && !(url.scheme() == "http" && loopback))
        || !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
        || url.path() != "/"
    {
        return Err("CONTROL_PLANE_URL_INVALID".into());
    }
    Ok(())
}

impl AuthorizedNetwork {
    pub fn verify(&self, request: &NetworkStart) -> Result<(), String> {
        let private: [u8; 32] = base64::engine::general_purpose::STANDARD
            .decode(&request.private_key)
            .map_err(|_| "PRIVATE_KEY_INVALID")?
            .try_into()
            .map_err(|_| "PRIVATE_KEY_INVALID")?;
        let public = base64::engine::general_purpose::STANDARD
            .encode(PublicKey::from(&StaticSecret::from(private)).to_bytes());
        if self.activity_id != request.activity_id
            || self.activity_generation != request.activity_generation
            || self.member_generation != request.member_generation
            || self.device_id != request.device_id
            || self.lease_id != request.lease_id
            || self.policy_revision != request.policy_revision
            || self.virtual_ipv4 != request.virtual_ipv4
            || self.activity_cidr != request.activity_cidr
            || self.public_key != public
            || self.allowed_paths != request.allowed_paths
            || self.peers.len() > 11
        {
            return Err("NETWORK_GRANT_MISMATCH".into());
        }
        let expiry = OffsetDateTime::parse(
            &self.expires_at,
            &time::format_description::well_known::Rfc3339,
        )
        .map_err(|_| "GRANT_EXPIRY_INVALID")?;
        let requested_expiry = OffsetDateTime::parse(
            &request.expires_at,
            &time::format_description::well_known::Rfc3339,
        )
        .map_err(|_| "GRANT_EXPIRY_INVALID")?;
        if requested_expiry > expiry || expiry <= OffsetDateTime::now_utc() {
            return Err("GRANT_EXPIRED".into());
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};
    use std::net::TcpListener;

    fn request() -> NetworkStart {
        NetworkStart {
            protocol_version: 1,
            request_id: "request".into(),
            ticket: "short-lived-ticket".into(),
            activity_id: "activity".into(),
            activity_generation: 1,
            member_generation: 2,
            device_id: "device".into(),
            lease_id: "lease".into(),
            policy_revision: 3,
            allowed_paths: vec![NetworkPath::Direct],
            private_key: base64::engine::general_purpose::STANDARD.encode([7; 32]),
            virtual_ipv4: "10.231.0.1".into(),
            activity_cidr: "10.231.0.0/27".into(),
            expires_at: (OffsetDateTime::now_utc() + time::Duration::seconds(30))
                .format(&time::format_description::well_known::Rfc3339)
                .unwrap(),
        }
    }

    fn reply(request: &NetworkStart) -> serde_json::Value {
        serde_json::json!({
            "activityId": request.activity_id, "activityGeneration": 1, "memberGeneration": 2,
            "deviceId": "device", "leaseId": "lease", "policyRevision": 3, "allowedPaths": ["DIRECT"],
            "virtualIpv4": request.virtual_ipv4, "activityCidr": request.activity_cidr,
            "publicKey": base64::engine::general_purpose::STANDARD.encode(PublicKey::from(&StaticSecret::from([7; 32])).to_bytes()),
            "expiresAt": request.expires_at, "peers": [],
        })
    }

    #[test]
    fn altered_identity_keys_routes_policy_and_expiry_are_rejected() {
        let original = request();
        let mut mismatches = Vec::new();
        let mut item = original.clone();
        item.member_generation += 1;
        mismatches.push(item);
        let mut item = original.clone();
        item.activity_generation += 1;
        mismatches.push(item);
        let mut item = original.clone();
        item.policy_revision += 1;
        mismatches.push(item);
        let mut item = original.clone();
        item.device_id = "other-device".into();
        mismatches.push(item);
        let mut item = original.clone();
        item.lease_id = "other-lease".into();
        mismatches.push(item);
        let mut item = original.clone();
        item.private_key = base64::engine::general_purpose::STANDARD.encode([8; 32]);
        mismatches.push(item);
        let mut item = original.clone();
        item.virtual_ipv4 = "10.231.0.2".into();
        mismatches.push(item);
        let mut item = original.clone();
        item.allowed_paths.push(NetworkPath::Relay);
        mismatches.push(item);
        let authorized: AuthorizedNetwork = serde_json::from_value(reply(&original)).unwrap();
        authorized.verify(&original).unwrap();
        for item in mismatches {
            assert!(authorized.verify(&item).is_err());
        }
        let mut late = original.clone();
        late.expires_at = (OffsetDateTime::now_utc() + time::Duration::hours(1))
            .format(&time::format_description::well_known::Rfc3339)
            .unwrap();
        assert_eq!(authorized.verify(&late).unwrap_err(), "GRANT_EXPIRED");
    }

    #[tokio::test]
    async fn real_http_validation_accepts_current_access_and_rejects_revocation_without_redirect() {
        let request = request();
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let body = reply(&request).to_string();
        let worker = std::thread::spawn(move || {
            for index in 0..3 {
                let (mut socket, _) = listener.accept().unwrap();
                socket
                    .set_read_timeout(Some(std::time::Duration::from_secs(3)))
                    .unwrap();
                let mut bytes = Vec::new();
                let mut buffer = [0; 1024];
                loop {
                    let count = socket.read(&mut buffer).unwrap();
                    assert!(count > 0);
                    bytes.extend_from_slice(&buffer[..count]);
                    if let Some(end) = bytes.windows(4).position(|part| part == b"\r\n\r\n") {
                        let headers = String::from_utf8_lossy(&bytes[..end]);
                        let length: usize = headers
                            .lines()
                            .find_map(|line| {
                                line.to_lowercase()
                                    .strip_prefix("content-length:")
                                    .map(|value| value.trim().parse().unwrap())
                            })
                            .unwrap();
                        if bytes.len() >= end + 4 + length {
                            break;
                        }
                    }
                }
                assert!(bytes.starts_with(b"POST /api/v1/network-access/validate HTTP/1.1"));
                assert!(!String::from_utf8_lossy(&bytes).contains("privateKey"));
                let (status, content) = match index {
                    0 => ("200 OK", body.as_str()),
                    1 => ("403 Forbidden", "{}"),
                    _ => ("302 Found\r\nLocation: http://127.0.0.1:1", "{}"),
                };
                write!(socket, "HTTP/1.1 {status}\r\nContent-Length: {}\r\nConnection: close\r\nContent-Type: application/json\r\n\r\n{content}", content.len()).unwrap();
            }
        });
        let authority = NetworkAuthority::new(&url).unwrap();
        assert!(authority.validate(&request).await.is_ok());
        assert!(
            matches!(authority.validate(&request).await, Err(code) if code == "NETWORK_GRANT_INVALID")
        );
        assert!(authority.validate(&request).await.is_err());
        worker.join().unwrap();
        assert!(NetworkAuthority::new("http://example.test").is_err());
        assert!(NetworkAuthority::new("https://user:secret@example.test").is_err());
    }
}
