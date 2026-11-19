use crate::AppState;
use zhijie_core::VoiceGrant;
use zhijie_voice::VoiceSnapshot;

pub async fn connect(
    state: &AppState,
    activity_id: &str,
    lease_id: &str,
) -> Result<VoiceSnapshot, String> {
    connect_with(
        state,
        activity_id,
        lease_id,
        |generation, grant| async move {
            state
                .voice
                .connect_reserved(generation, &grant.voice_url, &grant.voice_token)
                .await
        },
    )
    .await
}

async fn connect_with<F, Fut>(
    state: &AppState,
    activity_id: &str,
    lease_id: &str,
    connector: F,
) -> Result<VoiceSnapshot, String>
where
    F: FnOnce(u64, VoiceGrant) -> Fut,
    Fut: std::future::Future<Output = Result<VoiceSnapshot, String>>,
{
    let (generation, member_generation, token) = {
        let _activity = state.activity_operations.channel().await;
        let activity = state
            .core
            .snapshot()
            .activity
            .filter(|activity| activity.activity_id == activity_id && activity.lease_id == lease_id)
            .ok_or("CURRENT_GRANT_REQUIRED")?;
        let token = state.access_token()?;
        (
            state.voice.begin_connection(),
            activity.member_generation,
            token,
        )
    };
    let grant = match state.client.voice_grant(&token, activity_id).await {
        Ok(grant)
            if grant.activity_id == activity_id
                && grant.lease_id == lease_id
                && grant.member_generation == member_generation =>
        {
            grant
        }
        Ok(_) => {
            return Err(state
                .voice
                .fail_connection(generation, "CURRENT_GRANT_REQUIRED".into()))
        }
        Err(error) => {
            let code = crate::error_code(error);
            return Err(state.voice.fail_connection(generation, code));
        }
    };
    connector(generation, grant).await
}

pub async fn disconnect(state: &AppState) -> VoiceSnapshot {
    let _operation = state.activity_operations.lock().await;
    state.voice.disconnect().await
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::server;
    use std::future::{poll_fn, Future};
    use std::task::Poll;
    use zhijie_core::{ActivityResult, AuthTokens, ChannelStatus, ControlPlaneClient};

    fn state(base: String) -> AppState {
        let state = AppState::new(ControlPlaneClient::new(base).unwrap());
        *state.session.lock().unwrap() = Some(AuthTokens {
            session_id: "session".into(),
            user_id: "user".into(),
            access_token: "access".into(),
            refresh_token: "refresh".into(),
            access_expires_at: "2099-01-01T00:00:00Z".into(),
            refresh_expires_at: "2099-01-01T00:00:00Z".into(),
        });
        state.core.session_changed(Some("user".into()));
        state
            .core
            .begin_activity("join".into(), "activity".into(), "device".into());
        assert!(state.core.apply_activity_result(
            "join",
            ActivityResult {
                activity_id: "activity".into(),
                activity_generation: 1,
                member_generation: 1,
                device_id: "device".into(),
                lease_id: "lease".into(),
                policy_revision: 1,
                voice_state: ChannelStatus::Disconnected,
                network_state: ChannelStatus::Disconnected,
                virtual_ipv4: "10.231.0.1".into(),
                activity_cidr: "10.231.0.0/27".into(),
            }
        ));
        state
    }

    #[test]
    fn disconnect_invalidates_pending_authorization_without_blocking_other_operations() {
        tauri::async_runtime::block_on(async {
            let (base, worker) = server(vec![(
                "/api/v1/activities/activity/voice-grants",
                Some("access"),
                200,
                serde_json::json!({"data": {
                    "grantId":"grant", "activityId":"activity", "memberGeneration":1,
                    "leaseId":"lease", "sfuUrl":"wss://fixture.invalid", "voiceToken":"fixture",
                    "expiresAt":"2099-01-01T00:00:00Z"
                }}),
            )]);
            let state = state(base);
            let mut connecting = Box::pin(connect(&state, "activity", "lease"));
            poll_fn(|cx| {
                assert!(connecting.as_mut().poll(cx).is_pending());
                Poll::Ready(())
            })
            .await;
            assert!(state.activity_operations.0.try_write().is_ok());
            let snapshot = disconnect(&state).await;
            assert_eq!(connecting.await.unwrap_err(), "VOICE_CONNECTION_SUPERSEDED");
            worker.join().unwrap();
            assert_eq!(
                snapshot.state,
                zhijie_voice::VoiceConnectionState::Disconnected
            );
            assert!(snapshot.failure_code.is_none());
            assert!(state.core.snapshot().activity.is_some());
            assert!(state.session.lock().unwrap().is_some());
        });
    }

    #[test]
    fn rejected_grant_releases_operation_for_disconnect() {
        tauri::async_runtime::block_on(async {
            let (base, worker) = server(vec![(
                "/api/v1/activities/activity/voice-grants",
                Some("access"),
                403,
                serde_json::json!({"error":{"code":"CURRENT_GRANT_REQUIRED"}}),
            )]);
            let state = state(base);
            let result = connect_with(&state, "activity", "lease", |_, _| async {
                panic!("denied grant must not connect media")
            })
            .await;
            worker.join().unwrap();
            assert_eq!(result.err().as_deref(), Some("CURRENT_GRANT_REQUIRED"));
            assert_eq!(
                disconnect(&state).await.state,
                zhijie_voice::VoiceConnectionState::Disconnected
            );
            assert!(state.core.snapshot().activity.is_some());
        });
    }

    #[test]
    fn stale_caller_and_grant_for_another_lease_cannot_start_media() {
        tauri::async_runtime::block_on(async {
            let (base, worker) = server(vec![(
                "/api/v1/activities/activity/voice-grants",
                Some("access"),
                200,
                serde_json::json!({"data":{"grantId":"grant","activityId":"activity","memberGeneration":1,
                    "leaseId":"other","sfuUrl":"wss://fixture.invalid","voiceToken":"fixture","expiresAt":"2099-01-01T00:00:00Z"}}),
            )]);
            let state = state(base);
            for lease in ["old", "lease"] {
                let result = connect_with(&state, "activity", lease, |_, _| async {
                    panic!("stale lease must not start media")
                })
                .await;
                assert_eq!(result.unwrap_err(), "CURRENT_GRANT_REQUIRED");
            }
            worker.join().unwrap();
        });
    }

    #[test]
    fn slow_network_operation_does_not_block_voice_authorization() {
        tauri::async_runtime::block_on(async {
            let (base, worker) = server(vec![(
                "/api/v1/activities/activity/voice-grants",
                Some("access"),
                403,
                serde_json::json!({"error":{"code":"CURRENT_GRANT_REQUIRED"}}),
            )]);
            let state = state(base);
            let _network = state.network.operations.lock().await;
            let _activity = state.activity_operations.channel().await;
            assert!(state.activity_operations.0.try_write().is_err());
            let result = tokio::time::timeout(
                std::time::Duration::from_secs(3),
                connect(&state, "activity", "lease"),
            )
            .await
            .unwrap();
            assert_eq!(result.unwrap_err(), "CURRENT_GRANT_REQUIRED");
            worker.join().unwrap();
        });
    }
}
