use crate::AppState;
use zhijie_core::VoiceGrant;
use zhijie_voice::VoiceSnapshot;

pub async fn connect(state: &AppState, activity_id: &str) -> Result<VoiceSnapshot, String> {
    connect_with(state, activity_id, |grant| async move {
        state
            .voice
            .connect(&grant.voice_url, &grant.voice_token)
            .await
    })
    .await
}

async fn connect_with<F, Fut>(
    state: &AppState,
    activity_id: &str,
    connector: F,
) -> Result<VoiceSnapshot, String>
where
    F: FnOnce(VoiceGrant) -> Fut,
    Fut: std::future::Future<Output = Result<VoiceSnapshot, String>>,
{
    let _operation = state.activity_operations.lock().await;
    if !state
        .core
        .snapshot()
        .activity
        .is_some_and(|activity| activity.activity_id == activity_id)
    {
        return Err("CURRENT_GRANT_REQUIRED".into());
    }
    let grant = match state
        .client
        .voice_grant(&state.access_token()?, activity_id)
        .await
    {
        Ok(grant) => grant,
        Err(error) => {
            let code = crate::error_code(error);
            state.voice.fail(code.clone());
            return Err(code);
        }
    };
    connector(grant).await
}

pub async fn disconnect(state: &AppState) -> VoiceSnapshot {
    // 包括授权请求在内，与连接串行，避免断开后迟到授权重新建立会话。
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
    fn disconnect_waits_for_pending_authorization_and_finishes_after_connect() {
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
            let mut connecting = Box::pin(connect_with(&state, "activity", |_| async {
                // 不打开音频设备；标记连接阶段完成，以验证后续断开覆盖它。
                Ok(state.voice.fail("CONNECT_COMPLETED".into()))
            }));
            poll_fn(|cx| {
                assert!(connecting.as_mut().poll(cx).is_pending());
                Poll::Ready(())
            })
            .await;
            assert!(state.activity_operations.try_lock().is_err());
            let mut disconnecting = Box::pin(disconnect(&state));
            poll_fn(|cx| {
                assert!(disconnecting.as_mut().poll(cx).is_pending());
                Poll::Ready(())
            })
            .await;
            assert_eq!(
                connecting.await.unwrap().failure_code.as_deref(),
                Some("CONNECT_COMPLETED")
            );
            let snapshot = disconnecting.await;
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
            let result = connect_with(&state, "activity", |_| async {
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
}
