use super::*;
use zhijie_core::{ActivityResult, AuthTokens, ChannelStatus, ClientCore, ControlPlaneClient};

fn state() -> AppState {
    let core = ClientCore::default();
    core.session_changed(Some("user".into()));
    core.begin_activity("join".into(), "activity".into(), "device".into());
    assert!(core.apply_activity_result(
        "join",
        ActivityResult {
            activity_id: "activity".into(),
            activity_generation: 1,
            member_generation: 2,
            device_id: "device".into(),
            lease_id: "lease".into(),
            policy_revision: 1,
            voice_state: ChannelStatus::Disconnected,
            network_state: ChannelStatus::Disconnected,
            virtual_ipv4: "10.231.0.1".into(),
            activity_cidr: "10.231.0.0/27".into(),
        }
    ));
    AppState {
        client: ControlPlaneClient::new("http://127.0.0.1:1").unwrap(),
        core,
        voice: Default::default(),
        network: Default::default(),
        session: std::sync::Mutex::new(Some(AuthTokens {
            session_id: "session".into(),
            user_id: "user".into(),
            access_token: "old-access".into(),
            refresh_token: "refresh".into(),
            access_expires_at: "2099-01-01T00:00:00Z".into(),
            refresh_expires_at: "2099-01-01T00:00:00Z".into(),
        })),
        activity_operations: Default::default(),
    }
}

fn event() -> zhijie_core::sync::SyncEvent {
    serde_json::from_value(serde_json::json!({"sequence": 11, "type": "lease.revoked",
        "activityId": "activity", "memberGeneration": 2, "payload": r#"{"user_id":"user"}"#}))
    .unwrap()
}

#[test]
fn only_new_group_messages_send_a_scoped_notice() {
    let full = serde_json::json!({"userId": "user"});
    assert_eq!(notice("user", None), full);
    let mut event = event();
    event.group_id = Some("group".into());
    for kind in ["message.deleted", "lease.revoked", "group.updated"] {
        event.r#type = kind.into();
        assert_eq!(notice("user", Some(&event)), full);
    }
    event.r#type = "message.created".into();
    assert_eq!(
        notice("user", Some(&event)),
        serde_json::json!({"userId": "user", "messageGroupId": "group"})
    );
    for missing in [Some(String::new()), None] {
        event.group_id = missing;
        assert_eq!(notice("user", Some(&event)), full);
    }
}

#[test]
fn credential_rotation_while_revocation_waits_does_not_acknowledge_the_event() {
    tauri::async_runtime::block_on(async {
        let state = state();
        state.core.set_service_available(false);
        let old = state.session.lock().unwrap().clone().unwrap();
        let event = event();
        let mut cursor = Some(10);
        let mut fresh = old.clone();
        fresh.access_token = "fresh-access".into();
        {
            let operation = state.activity_operations.lock().await;
            let pending = apply_event(&state, &old, &event, &mut cursor);
            tokio::pin!(pending);
            assert!(
                tokio::time::timeout(Duration::from_millis(30), pending.as_mut())
                    .await
                    .is_err()
            );
            *state.session.lock().unwrap() = Some(fresh.clone());
            drop(operation);
            assert!(
                !pending.await,
                "stale event must reconnect without acknowledgement"
            );
        }
        assert_eq!(cursor, Some(10));
        assert!(state.core.snapshot().activity.is_some());
        assert_eq!(state.core.snapshot().service, ChannelStatus::Retrying);
        assert!(apply_event(&state, &fresh, &event, &mut cursor).await);
        assert_eq!(cursor, Some(11));
        assert!(state.core.snapshot().activity.is_none());
        assert_eq!(state.core.snapshot().service, ChannelStatus::Connected);
    });
}

#[test]
fn successful_connection_waits_for_operations_and_rejects_stale_credentials() {
    tauri::async_runtime::block_on(async {
        let state = state();
        state.core.set_service_available(false);
        let old = state.session.lock().unwrap().clone().unwrap();
        let mut fresh = old.clone();
        fresh.access_token = "fresh-access".into();
        let operation = state.activity_operations.lock().await;
        let pending = restore_service(&state, &old);
        tokio::pin!(pending);
        assert!(
            tokio::time::timeout(Duration::from_millis(30), pending.as_mut())
                .await
                .is_err()
        );
        *state.session.lock().unwrap() = Some(fresh.clone());
        drop(operation);
        assert!(!pending.await);
        assert_eq!(state.core.snapshot().service, ChannelStatus::Retrying);
        assert!(restore_service(&state, &fresh).await);
        assert_eq!(state.core.snapshot().service, ChannelStatus::Connected);
        assert!(state.core.snapshot().activity.is_some());
    });
}

#[test]
fn reconnect_rechecks_the_current_lease_before_reporting_service_restored() {
    use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};

    tauri::async_runtime::block_on(async {
        for (status, retained) in [(200, true), (409, false), (503, true)] {
            let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            let mut state = state();
            state.client =
                ControlPlaneClient::new(format!("http://{}", listener.local_addr().unwrap()))
                    .unwrap();
            let tokens = state.session.lock().unwrap().clone().unwrap();
            let server = async {
                let (socket, _) = listener.accept().await.unwrap();
                let mut socket = BufReader::new(socket);
                let mut request = String::new();
                socket.read_line(&mut request).await.unwrap();
                assert_eq!(
                    request,
                    "POST /api/v1/activities/activity/heartbeat HTTP/1.1\r\n"
                );
                let body = r#"{"data":{}}"#;
                let reply = format!("HTTP/1.1 {status} Test\r\nConnection: close\r\nContent-Length: {}\r\n\r\n{body}", body.len());
                socket.get_mut().write_all(reply.as_bytes()).await.unwrap();
                socket.get_mut().shutdown().await.unwrap();
            };
            let (restored, ()) = tokio::time::timeout(Duration::from_secs(5), async {
                tokio::join!(restore_service(&state, &tokens), server)
            })
            .await
            .unwrap();
            assert!(restored);
            assert_eq!(
                state.core.snapshot().activity.is_some(),
                retained,
                "HTTP {status}"
            );
            assert_eq!(state.core.snapshot().user_id.as_deref(), Some("user"));
        }
    });
}

#[test]
fn old_connection_cannot_restore_a_replacement_or_signed_out_session() {
    tauri::async_runtime::block_on(async {
        let state = state();
        let old = state.session.lock().unwrap().clone().unwrap();
        let mut replacement = old.clone();
        replacement.session_id = "replacement".into();
        replacement.user_id = "other-user".into();
        replacement.access_token = "replacement-access".into();
        state
            .core
            .session_changed(Some(replacement.user_id.clone()));
        *state.session.lock().unwrap() = Some(replacement.clone());
        state.core.set_service_available(false);
        assert!(!restore_service(&state, &old).await);
        assert_eq!(state.core.snapshot().service, ChannelStatus::Retrying);
        assert!(restore_service(&state, &replacement).await);
        assert_eq!(state.core.snapshot().service, ChannelStatus::Connected);
        *state.session.lock().unwrap() = None;
        state.core.session_changed(None);
        assert!(!restore_service(&state, &replacement).await);
        assert_eq!(state.core.snapshot().service, ChannelStatus::Disconnected);
        assert!(state.core.snapshot().user_id.is_none());
        assert!(state.core.snapshot().activity.is_none());
    });
}

#[test]
fn replacement_session_and_old_member_events_preserve_current_activity() {
    tauri::async_runtime::block_on(async {
        let state = state();
        let old = state.session.lock().unwrap().clone().unwrap();
        let mut replacement = old.clone();
        replacement.session_id = "replacement".into();
        replacement.access_token = "replacement-access".into();
        *state.session.lock().unwrap() = Some(replacement.clone());
        let mut event = event();
        let mut cursor = Some(10);
        assert!(!apply_event(&state, &old, &event, &mut cursor).await);
        assert_eq!(cursor, Some(10));
        assert!(state.core.snapshot().activity.is_some());
        event.member_generation = Some(1);
        assert!(apply_event(&state, &replacement, &event, &mut cursor).await);
        assert_eq!(cursor, Some(11));
        assert!(state.core.snapshot().activity.is_some());
        event.sequence = 12;
        event.r#type = "user.changed".into();
        assert!(apply_event(&state, &replacement, &event, &mut cursor).await);
        assert_eq!(cursor, Some(12));
        assert!(state.core.snapshot().activity.is_some());
        *state.session.lock().unwrap() = None;
        event.sequence = 13;
        assert!(!apply_event(&state, &replacement, &event, &mut cursor).await);
        assert_eq!(cursor, Some(12));
    });
}
