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

impl ControlPlaneClient {
    pub fn new(base_url: impl Into<String>) -> Result<Self, ClientError> {
        let http = reqwest::Client::builder()
            .connect_timeout(std::time::Duration::from_secs(5))
            .timeout(std::time::Duration::from_secs(15))
            .build()
            .map_err(|error| ClientError::Transport(error.to_string()))?;
        Ok(Self {
            http,
            base_url: base_url.into().trim_end_matches('/').to_string(),
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

    pub async fn leave(&self, access_token: &str, activity_id: &str) -> Result<(), ClientError> {
        let response: serde_json::Value = self
            .post(
                &format!("/api/v1/activities/{activity_id}/leave"),
                Some(access_token),
                &serde_json::json!({}),
            )
            .await?;
        let _ = response;
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
