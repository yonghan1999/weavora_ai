use futures_util::{SinkExt, StreamExt};
use serde::Deserialize;
use tokio::net::TcpStream;
use tokio_tungstenite::tungstenite::{client::IntoClientRequest, http::header::AUTHORIZATION};
use tokio_tungstenite::{connect_async, tungstenite::Message, MaybeTlsStream, WebSocketStream};

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SyncEvent {
    pub sequence: u64,
    pub group_id: Option<String>,
    pub activity_id: Option<String>,
    pub member_generation: Option<u64>,
    pub r#type: String,
    pub payload: String,
}

impl SyncEvent {
    fn decode(text: &str) -> Result<Self, &'static str> {
        let event: Self = serde_json::from_str(text).map_err(|_| "SYNC_EVENT_INVALID")?;
        let payload: serde_json::Value =
            serde_json::from_str(&event.payload).map_err(|_| "SYNC_EVENT_INVALID")?;
        if event.sequence == 0 || event.r#type.is_empty() || !payload.is_object() {
            return Err("SYNC_EVENT_INVALID");
        }
        // 撤销必须有可核对的目标，不能把无法应用的事件交给调用者推进游标。
        if event.r#type.ends_with(".revoked")
            && (event.activity_id.as_deref().is_none_or(str::is_empty)
                || event.member_generation.is_none_or(|value| value == 0)
                || payload["user_id"].as_str().is_none_or(str::is_empty))
        {
            return Err("SYNC_EVENT_INVALID");
        }
        Ok(event)
    }

    pub fn revokes(&self, user: &str, activity: &crate::ActivityContext) -> bool {
        self.r#type.ends_with(".revoked")
            && self.activity_id.as_deref() == Some(activity.activity_id.as_str())
            && self.member_generation == Some(activity.member_generation)
            && serde_json::from_str::<serde_json::Value>(&self.payload)
                .ok()
                .and_then(|value| value["user_id"].as_str().map(str::to_owned))
                .as_deref()
                == Some(user)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    async fn wire_event(text: String) -> Result<Option<SyncEvent>, &'static str> {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let server = tokio::spawn(async move {
            let (stream, _) = listener.accept().await.unwrap();
            use tokio_tungstenite::tungstenite::handshake::server::Request;
            let mut socket =
                tokio_tungstenite::accept_hdr_async(stream, |request: &Request, response| {
                    assert_eq!(
                        request.uri().path_and_query().unwrap().as_str(),
                        "/api/v1/ws?cursor=1"
                    );
                    assert_eq!(request.headers()[AUTHORIZATION], "Bearer test-token");
                    Ok(response)
                })
                .await
                .unwrap();
            socket.send(Message::Text(text.into())).await.unwrap();
        });
        let mut client = SyncConnection::connect(&format!("http://{address}"), "test-token", 1)
            .await
            .unwrap();
        let result = tokio::time::timeout(std::time::Duration::from_secs(2), client.next())
            .await
            .unwrap();
        server.await.unwrap();
        result
    }

    #[tokio::test]
    async fn wire_rejects_malformed_event_before_emitting_a_cursor() {
        let valid = serde_json::json!({"sequence": 2, "type": "lease.revoked",
            "activityId": "activity", "memberGeneration": 3, "payload": r#"{"user_id":"user"}"#});
        for (field, value) in [
            ("sequence", serde_json::json!(0)),
            ("type", serde_json::json!("")),
            ("payload", serde_json::json!("private-invalid-json")),
            ("payload", serde_json::json!("[]")),
            ("payload", serde_json::json!("null")),
            ("payload", serde_json::json!("{}")),
            ("payload", serde_json::json!(r#"{"user_id":""}"#)),
            ("payload", serde_json::json!(r#"{"user_id":1}"#)),
            ("activityId", serde_json::Value::Null),
            ("activityId", serde_json::json!("")),
            ("memberGeneration", serde_json::Value::Null),
            ("memberGeneration", serde_json::json!(0)),
        ] {
            let mut event = valid.clone();
            event[field] = value;
            assert!(
                matches!(
                    wire_event(event.to_string()).await,
                    Err("SYNC_EVENT_INVALID")
                ),
                "invalid {field} must not be emitted as a successfully received event"
            );
        }
        for payload in ["private-invalid-json", "[]", "null", "1"] {
            let event =
                serde_json::json!({"sequence": 2, "type": "user.changed", "payload": payload});
            assert!(matches!(
                wire_event(event.to_string()).await,
                Err("SYNC_EVENT_INVALID")
            ));
        }
    }

    #[tokio::test]
    async fn wire_accepts_current_and_future_notifications_and_targeted_revocation() {
        for kind in ["user.changed", "message.created", "future.notification"] {
            let event = serde_json::json!({"sequence": 2, "type": kind, "payload": "{}"});
            let received = wire_event(event.to_string()).await.unwrap().unwrap();
            assert_eq!(received.sequence, 2);
            assert_eq!(received.r#type, kind);
        }
        let event = serde_json::json!({"sequence": 3, "type": "lease.revoked",
            "activityId": "current", "memberGeneration": 2, "payload": r#"{"user_id":"user"}"#});
        let received = wire_event(event.to_string()).await.unwrap().unwrap();
        let activity = crate::ActivityContext {
            activity_id: "current".into(),
            activity_generation: 1,
            member_generation: 2,
            device_id: "device".into(),
            lease_id: "lease".into(),
            virtual_ipv4: None,
            voice: crate::ChannelStatus::Connected,
            network: crate::ChannelStatus::Connected,
        };
        assert!(received.revokes("user", &activity));
        assert!(!received.revokes("other", &activity));
    }

    #[test]
    fn revocation_requires_current_user_activity_and_generation() {
        let activity = crate::ActivityContext {
            activity_id: "current".into(),
            activity_generation: 1,
            member_generation: 2,
            device_id: "device".into(),
            lease_id: "lease".into(),
            virtual_ipv4: None,
            voice: crate::ChannelStatus::Connected,
            network: crate::ChannelStatus::Connected,
        };
        let mut event = SyncEvent {
            sequence: 1,
            group_id: None,
            activity_id: Some("current".into()),
            member_generation: Some(2),
            r#type: "lease.revoked".into(),
            payload: r#"{"user_id":"user"}"#.into(),
        };
        assert!(event.revokes("user", &activity));
        assert!(!event.revokes("other", &activity));
        event.member_generation = Some(1);
        assert!(!event.revokes("user", &activity));
        event.member_generation = Some(2);
        event.activity_id = Some("old".into());
        assert!(!event.revokes("user", &activity));
        event.activity_id = Some("current".into());
        event.payload = "invalid".into();
        assert!(!event.revokes("user", &activity));
    }
}

pub struct SyncConnection(WebSocketStream<MaybeTlsStream<TcpStream>>);

impl SyncConnection {
    pub async fn connect(base: &str, token: &str, cursor: u64) -> Result<Self, &'static str> {
        let mut address =
            crate::api::control_plane_origin(base).map_err(|_| "SYNC_ENDPOINT_INVALID")?;
        let scheme = match address.scheme() {
            "http" => "ws",
            "https" => "wss",
            _ => return Err("SYNC_ENDPOINT_INVALID"),
        };
        address
            .set_scheme(scheme)
            .map_err(|_| "SYNC_ENDPOINT_INVALID")?;
        address.set_path("/api/v1/ws");
        address.set_query(None);
        address.set_fragment(None);
        address
            .query_pairs_mut()
            .append_pair("cursor", &cursor.to_string());
        let mut request = address
            .as_str()
            .into_client_request()
            .map_err(|_| "SYNC_ENDPOINT_INVALID")?;
        let mut bearer: tokio_tungstenite::tungstenite::http::HeaderValue =
            format!("Bearer {token}")
                .parse()
                .map_err(|_| "SYNC_CREDENTIAL_INVALID")?;
        bearer.set_sensitive(true);
        request.headers_mut().insert(AUTHORIZATION, bearer);
        let (socket, _) =
            tokio::time::timeout(std::time::Duration::from_secs(5), connect_async(request))
                .await
                .map_err(|_| "SYNC_CONNECT_TIMEOUT")?
                .map_err(|_| "SYNC_CONNECT_FAILED")?;
        Ok(Self(socket))
    }

    pub async fn next(&mut self) -> Result<Option<SyncEvent>, &'static str> {
        match self.0.next().await {
            Some(Ok(Message::Text(text))) => SyncEvent::decode(&text).map(Some),
            Some(Ok(Message::Close(Some(frame)))) if frame.reason == "RESYNC_REQUIRED" => {
                Err("RESYNC_REQUIRED")
            }
            Some(Ok(Message::Close(Some(frame)))) if frame.reason == "ACCESS_TOKEN_INVALID" => {
                Err("ACCESS_TOKEN_INVALID")
            }
            Some(Ok(Message::Close(_))) | None => Err("SYNC_CLOSED"),
            Some(Ok(Message::Ping(_))) => {
                self.0.flush().await.map_err(|_| "SYNC_CLOSED")?;
                Ok(None)
            }
            Some(Ok(Message::Pong(_))) => Ok(None),
            _ => Err("SYNC_CLOSED"),
        }
    }

    pub async fn ping(&mut self) -> Result<(), &'static str> {
        tokio::time::timeout(
            std::time::Duration::from_secs(5),
            self.0.send(Message::Ping(Vec::new().into())),
        )
        .await
        .map_err(|_| "SYNC_CLOSED")?
        .map_err(|_| "SYNC_CLOSED")
    }
}
