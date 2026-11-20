use serde::{de::DeserializeOwned, Deserialize, Serialize};

use crate::model::ActivityResult;

#[derive(Debug, Clone)]
pub enum ClientError {
    Transport(String),
    Api { status: u16, code: String },
    InvalidResponse(String),
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AuthTokens {
    pub session_id: String,
    pub user_id: String,
    pub access_token: String,
    pub refresh_token: String,
    pub access_expires_at: String,
    pub refresh_expires_at: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct VoiceGrant {
    pub grant_id: String,
    pub activity_id: String,
    pub member_generation: u64,
    pub lease_id: String,
    #[serde(rename = "sfuUrl", alias = "voiceUrl")]
    pub voice_url: String,
    pub voice_token: String,
    pub expires_at: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NetworkGrant {
    pub grant_id: String,
    pub activity_id: String,
    pub member_generation: u64,
    pub lease_id: String,
    pub network_ticket: String,
    pub policy_revision: u64,
    pub allowed_paths: Vec<String>,
    pub virtual_ipv4: String,
    pub activity_cidr: String,
    pub expires_at: String,
}

#[derive(Clone)]
pub struct ControlPlaneClient {
    http: reqwest::Client,
    base_url: String,
}

pub(crate) fn control_plane_origin(base: &str) -> Result<reqwest::Url, &'static str> {
    let url = reqwest::Url::parse(base).map_err(|_| "CONTROL_PLANE_URL_INVALID")?;
    let loopback = matches!(url.host_str(), Some("127.0.0.1" | "localhost" | "[::1]"));
    if url.host_str().is_none()
        || !(url.scheme() == "https" || url.scheme() == "http" && loopback)
        || !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
        || url.path() != "/"
    {
        return Err("CONTROL_PLANE_URL_INVALID");
    }
    Ok(url)
}

impl ControlPlaneClient {
    pub async fn sync_cursor(&self, access_token: &str) -> Result<u64, ClientError> {
        #[derive(Deserialize)]
        struct Cursor {
            cursor: u64,
        }
        let response = self
            .http
            .get(format!("{}/api/v1/sync/cursor", self.base_url))
            .bearer_auth(access_token)
            .send()
            .await
            .map_err(transport)?;
        Ok(decode::<Cursor>(response).await?.cursor)
    }

    pub async fn connect_sync(
        &self,
        access_token: &str,
        cursor: u64,
    ) -> Result<crate::sync::SyncConnection, &'static str> {
        crate::sync::SyncConnection::connect(&self.base_url, access_token, cursor).await
    }

    pub async fn product_request(
        &self,
        token: Option<&str>,
        method: &str,
        path: &str,
        body: serde_json::Value,
    ) -> Result<serde_json::Value, ClientError> {
        let method = reqwest::Method::from_bytes(method.as_bytes())
            .map_err(|error| ClientError::InvalidResponse(error.to_string()))?;
        let mut request = self
            .http
            .request(method, format!("{}{}", self.base_url, path));
        if let Some(token) = token {
            request = request.bearer_auth(token);
        }
        if !body.is_null() {
            request = request.json(&body);
        }
        decode(request.send().await.map_err(transport)?).await
    }

    pub fn new(base_url: impl Into<String>) -> Result<Self, ClientError> {
        let origin = control_plane_origin(&base_url.into())
            .map_err(|code| ClientError::InvalidResponse(code.into()))?;
        let http = reqwest::Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            .connect_timeout(std::time::Duration::from_secs(5))
            .timeout(std::time::Duration::from_secs(15))
            .build()
            .map_err(|error| ClientError::Transport(error.to_string()))?;
        Ok(Self {
            http,
            base_url: origin.origin().ascii_serialization(),
        })
    }

    pub async fn login(
        &self,
        email: &str,
        password: &str,
        device_id: &str,
    ) -> Result<AuthTokens, ClientError> {
        self.post(
            "/api/v1/auth/login",
            None,
            &LoginRequest {
                email,
                password,
                device_id,
            },
        )
        .await
    }

    pub async fn groups(&self, access_token: &str) -> Result<Vec<serde_json::Value>, ClientError> {
        let response = self
            .http
            .get(format!("{}/api/v1/groups", self.base_url))
            .bearer_auth(access_token)
            .send()
            .await
            .map_err(transport)?;
        decode(response).await
    }

    pub async fn refresh(&self, refresh_token: &str) -> Result<AuthTokens, ClientError> {
        self.post(
            "/api/v1/auth/refresh",
            None,
            &serde_json::json!({"refreshToken": refresh_token}),
        )
        .await
    }

    pub async fn rooms(
        &self,
        access_token: &str,
        group_id: &str,
    ) -> Result<Vec<serde_json::Value>, ClientError> {
        let response = self
            .http
            .get(format!("{}/api/v1/groups/{group_id}/rooms", self.base_url))
            .bearer_auth(access_token)
            .send()
            .await
            .map_err(transport)?;
        decode(response).await
    }

    pub async fn network_grant(
        &self,
        access_token: &str,
        activity_id: &str,
        public_key: &str,
    ) -> Result<NetworkGrant, ClientError> {
        self.post(
            &format!("/api/v1/activities/{activity_id}/network-grants"),
            Some(access_token),
            &serde_json::json!({ "publicKey": public_key }),
        )
        .await
    }

    pub async fn voice_grant(
        &self,
        access_token: &str,
        activity_id: &str,
    ) -> Result<VoiceGrant, ClientError> {
        self.post(
            &format!("/api/v1/activities/{activity_id}/voice-grants"),
            Some(access_token),
            &serde_json::json!({}),
        )
        .await
    }

    pub async fn join(
        &self,
        access_token: &str,
        room_id: &str,
        device_id: &str,
        password: Option<&str>,
        operation_id: &str,
    ) -> Result<ActivityResult, ClientError> {
        self.post(
            "/api/v1/room-joins",
            Some(access_token),
            &JoinRequest {
                room_id,
                device_id,
                password,
                operation_id,
            },
        )
        .await
    }

    pub async fn leave(
        &self,
        access_token: &str,
        activity_id: &str,
        lease_id: &str,
    ) -> Result<(), ClientError> {
        let _: serde_json::Value = self
            .post(
                &format!("/api/v1/activities/{activity_id}/leave"),
                Some(access_token),
                &serde_json::json!({"leaseId": lease_id}),
            )
            .await?;
        Ok(())
    }

    pub async fn heartbeat(
        &self,
        access_token: &str,
        activity: &crate::model::ActivityContext,
    ) -> Result<(), ClientError> {
        let _: serde_json::Value = self
            .post(
                &format!("/api/v1/activities/{}/heartbeat", activity.activity_id),
                Some(access_token),
                &serde_json::json!({
                    "deviceId": activity.device_id, "leaseId": activity.lease_id,
                    "activityGeneration": activity.activity_generation,
                    "memberGeneration": activity.member_generation,
                }),
            )
            .await?;
        Ok(())
    }

    async fn post<T: DeserializeOwned, B: Serialize>(
        &self,
        path: &str,
        token: Option<&str>,
        body: &B,
    ) -> Result<T, ClientError> {
        let mut request = self
            .http
            .post(format!("{}{}", self.base_url, path))
            .json(body);
        if let Some(token) = token {
            request = request.bearer_auth(token);
        }
        let response = request.send().await.map_err(transport)?;
        decode(response).await
    }
}

async fn decode<T: DeserializeOwned>(response: reqwest::Response) -> Result<T, ClientError> {
    let status = response.status();
    if !status.is_success() {
        let body = response.text().await.map_err(transport)?;
        let code = serde_json::from_str::<serde_json::Value>(&body)
            .ok()
            .and_then(|value| value["error"]["code"].as_str().map(str::to_string))
            .unwrap_or_else(|| "HTTP_ERROR".to_string());
        return Err(ClientError::Api {
            status: status.as_u16(),
            code,
        });
    }
    response
        .json::<ApiEnvelope<T>>()
        .await
        .map(|envelope| envelope.data)
        .map_err(|error| ClientError::InvalidResponse(error.to_string()))
}

fn transport(error: reqwest::Error) -> ClientError {
    ClientError::Transport(error.to_string())
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct LoginRequest<'a> {
    email: &'a str,
    password: &'a str,
    device_id: &'a str,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct JoinRequest<'a> {
    room_id: &'a str,
    device_id: &'a str,
    password: Option<&'a str>,
    operation_id: &'a str,
}

#[derive(Deserialize)]
struct ApiEnvelope<T> {
    data: T,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn http_and_sync_reject_invalid_origins_before_connecting() {
        for base in [
            "http://control.example.test",
            "http://192.0.2.1",
            "ws://localhost",
            "https://user:private@example.test",
            "https://example.test/api",
            "https://example.test?key=private",
            "https://example.test/#private",
            "not a URL",
        ] {
            assert!(
                matches!(ControlPlaneClient::new(base), Err(ClientError::InvalidResponse(code)) if code == "CONTROL_PLANE_URL_INVALID")
            );
            assert!(matches!(
                crate::sync::SyncConnection::connect(base, "test-only", 0).await,
                Err("SYNC_ENDPOINT_INVALID")
            ));
        }
        for base in [
            "http://localhost:8080",
            "http://127.0.0.1:8080/",
            "http://[::1]:8080",
            "https://control.example.test:8443/",
        ] {
            assert!(ControlPlaneClient::new(base).is_ok());
        }
    }

    #[tokio::test]
    async fn login_returns_redirect_without_contacting_second_listener() {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let first = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let other = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let base = format!("http://{}", first.local_addr().unwrap());
        let destination = other.local_addr().unwrap();
        let serve = tokio::spawn(async move {
            let (mut socket, _) = first.accept().await.unwrap();
            let mut buffer = [0; 2048];
            assert!(socket.read(&mut buffer).await.unwrap() > 0);
            socket.write_all(format!("HTTP/1.1 307 Temporary Redirect\r\nLocation: http://{destination}/other\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").as_bytes()).await.unwrap();
        });
        let client = ControlPlaneClient::new(base).unwrap();
        let result = tokio::time::timeout(
            std::time::Duration::from_secs(3),
            client.login("sample@example.test", "test-only", "local"),
        )
        .await
        .unwrap();
        assert!(matches!(result, Err(ClientError::Api { status: 307, .. })));
        serve.await.unwrap();
        assert!(
            tokio::time::timeout(std::time::Duration::from_millis(100), other.accept())
                .await
                .is_err()
        );
    }

    #[test]
    fn voice_grant_accepts_the_control_plane_sfu_url() {
        let json = serde_json::json!({
            "grantId": "grant", "activityId": "activity", "memberGeneration": 1,
            "leaseId": "lease", "sfuUrl": "ws://127.0.0.1:7880",
            "voiceToken": "scoped-token", "expiresAt": "2026-11-17T00:00:00Z"
        });
        let grant: VoiceGrant = serde_json::from_value(json).unwrap();
        assert_eq!(grant.voice_url, "ws://127.0.0.1:7880");
    }
}
