use super::*;
use std::future::Future;
use std::io::Write;
use std::net::TcpListener;
use std::sync::Mutex;
use zhijie_core::{ActivityResult, AuthTokens, ChannelStatus, ClientCore, ControlPlaneClient};

fn tokens(expiring: bool) -> AuthTokens {
    AuthTokens {
        session_id: "session".into(),
        user_id: "user".into(),
        access_token: "old-access".into(),
        refresh_token: "refresh".into(),
        access_expires_at: if expiring {
            "2000-01-01T00:00:00Z"
        } else {
            "2099-01-01T00:00:00Z"
        }
        .into(),
        refresh_expires_at: "2099-01-01T00:00:00Z".into(),
    }
}

fn state(base: String, expiring: bool, activity: bool) -> AppState {
    let core = ClientCore::default();
    core.session_changed(Some("user".into()));
    if activity {
        core.begin_activity("join".into(), "activity".into(), "device".into());
        assert!(core.apply_activity_result(
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
    }
    AppState {
        client: ControlPlaneClient::new(base).unwrap(),
        core,
        voice: Default::default(),
        network: Default::default(),
        session: Mutex::new(Some(tokens(expiring))),
        activity_operations: Default::default(),
    }
}

use crate::test_support::server;

fn fresh() -> serde_json::Value {
    let mut fresh = tokens(false);
    fresh.access_token = "fresh-access".into();
    serde_json::json!({"data":fresh})
}
fn ok() -> serde_json::Value {
    serde_json::json!({"data":{"recorded":true}})
}
fn denied() -> serde_json::Value {
    serde_json::json!({"error":{"code":"ACCESS_TOKEN_INVALID"}})
}

#[test]
fn logout_clears_local_session_and_attempts_remote_logout_after_leave_failure() {
    for (leave_status, logout_status) in [(200, 200), (503, 200), (401, 503)] {
        tauri::async_runtime::block_on(async {
            let (base, worker) = server(vec![
                (
                    "/api/v1/activities/activity/leave",
                    Some("old-access"),
                    leave_status,
                    if leave_status == 200 { ok() } else { denied() },
                ),
                (
                    "/api/v1/auth/logout",
                    None,
                    logout_status,
                    if logout_status == 200 { ok() } else { denied() },
                ),
            ]);
            let state = state(base, false, true);
            state.voice.fail("OLD_FAILURE".into());
            let _operation = state.activity_operations.lock().await;
            let snapshot = logout_with_cleanup(&state, || async {
                assert!(state.session.lock().unwrap().is_none());
                assert!(state.core.snapshot().activity.is_none());
                assert_eq!(
                    state.voice.snapshot().state,
                    zhijie_voice::VoiceConnectionState::Disconnected
                );
                state.network.stop().await
            })
            .await;
            worker.join().unwrap();
            assert!(snapshot.user_id.is_none() && snapshot.activity.is_none());
            assert_eq!(
                snapshot.last_error_code.is_some(),
                leave_status != 200 || logout_status != 200
            );
            let mut renewed = Some(("lease".into(), std::time::Instant::now()));
            tick(&state, &mut renewed).await;
            assert!(renewed.is_none());
        });
    }
}

#[test]
fn logout_without_credentials_still_cleans_local_channels() {
    tauri::async_runtime::block_on(async {
        let state = state("http://127.0.0.1:1".into(), false, true);
        state.session.lock().unwrap().take();
        let _operation = state.activity_operations.lock().await;
        let snapshot = logout(&state).await;
        assert!(snapshot.user_id.is_none() && snapshot.activity.is_none());
        assert!(snapshot.last_error_code.is_none());
    });
}

#[test]
fn successful_login_clears_old_activity_even_for_the_same_user() {
    for user in ["user", "another-user"] {
        tauri::async_runtime::block_on(async {
            let mut credentials = tokens(false);
            credentials.session_id = "new-session".into();
            credentials.user_id = user.into();
            credentials.access_token = "new-access".into();
            let (base, worker) = server(vec![(
                "/api/v1/auth/login",
                None,
                200,
                serde_json::json!({"data":credentials}),
            )]);
            let state = state(base, false, true);
            state.voice.fail("OLD_FAILURE".into());
            state.network.fail("OLD_FAILURE".into());
            let _operation = state.activity_operations.lock().await;
            let snapshot = login(&state, "fixture@example.com", "fixture", "device")
                .await
                .unwrap();
            worker.join().unwrap();
            assert_eq!(snapshot.user_id.as_deref(), Some(user));
            assert!(snapshot.activity.is_none());
            assert_eq!(
                state.session.lock().unwrap().as_ref().unwrap(),
                &credentials
            );
            assert_eq!(
                state.voice.snapshot().state,
                zhijie_voice::VoiceConnectionState::Disconnected
            );
            assert_eq!(state.network.snapshot()["status"]["state"], "DISCONNECTED");
        });
    }
}

#[test]
fn rejected_login_preserves_current_activity_credentials_and_channels() {
    tauri::async_runtime::block_on(async {
        let (base, worker) = server(vec![("/api/v1/auth/login", None, 401, denied())]);
        let state = state(base, false, true);
        state.voice.fail("OLD_FAILURE".into());
        state.network.fail("OLD_FAILURE".into());
        let before = state.core.snapshot();
        let _operation = state.activity_operations.lock().await;
        let result =
            login_with_cleanup(&state, "fixture@example.com", "wrong", "device", || async {
                panic!("rejected login must not clean up current channels");
            })
            .await;
        worker.join().unwrap();
        assert_eq!(result.err().as_deref(), Some("ACCESS_TOKEN_INVALID"));
        assert_eq!(state.core.snapshot(), before);
        assert_eq!(
            state.session.lock().unwrap().as_ref().unwrap(),
            &tokens(false)
        );
        assert_eq!(
            state.voice.snapshot().failure_code.as_deref(),
            Some("OLD_FAILURE")
        );
        assert_eq!(
            state.network.snapshot()["status"]["errorCode"],
            "OLD_FAILURE"
        );
    });
}

#[test]
fn refresh_before_expiry_uses_new_credential_and_preserves_activity() {
    tauri::async_runtime::block_on(async {
        let (base, worker) = server(vec![
            ("/api/v1/auth/refresh", None, 200, fresh()),
            (
                "/api/v1/activities/activity/heartbeat",
                Some("fresh-access"),
                200,
                ok(),
            ),
            (
                "/api/v1/presence/heartbeat",
                Some("fresh-access"),
                200,
                ok(),
            ),
        ]);
        let state = state(base, true, true);
        let mut renewal = None;
        let _operation = state.activity_operations.lock().await;
        tick(&state, &mut renewal).await;
        worker.join().unwrap();
        assert!(state.access_token().unwrap() == "fresh-access");
        assert!(state.core.snapshot().activity.is_some());
        assert!(renewal.is_some());
    });
}

#[test]
fn idle_invalid_access_recovers_with_refresh_and_next_tick_uses_new_token() {
    tauri::async_runtime::block_on(async {
        let (base, worker) = server(vec![
            (
                "/api/v1/presence/heartbeat",
                Some("old-access"),
                401,
                denied(),
            ),
            ("/api/v1/auth/refresh", None, 200, fresh()),
            (
                "/api/v1/presence/heartbeat",
                Some("fresh-access"),
                200,
                ok(),
            ),
        ]);
        let state = state(base, false, false);
        let mut renewal = None;
        let _operation = state.activity_operations.lock().await;
        tick(&state, &mut renewal).await;
        tick(&state, &mut renewal).await;
        worker.join().unwrap();
        assert!(state.access_token().unwrap() == "fresh-access");
        assert!(state.core.snapshot().user_id.is_some());
    });
}

#[test]
fn explicit_refresh_rejection_clears_idle_and_active_sessions() {
    for active in [false, true] {
        tauri::async_runtime::block_on(async {
            let mut steps = Vec::new();
            if active {
                steps.push((
                    "/api/v1/activities/activity/heartbeat",
                    Some("old-access"),
                    200,
                    ok(),
                ));
            }
            steps.extend([
                (
                    "/api/v1/presence/heartbeat",
                    Some("old-access"),
                    403,
                    denied(),
                ),
                ("/api/v1/auth/refresh", None, 401, denied()),
            ]);
            let (base, worker) = server(steps);
            let state = state(base, false, active);
            let mut renewal = None;
            let _operation = state.activity_operations.lock().await;
            tick(&state, &mut renewal).await;
            worker.join().unwrap();
            assert!(state.session.lock().unwrap().is_none());
            assert!(state.core.snapshot().user_id.is_none());
            assert!(state.core.snapshot().activity.is_none());
            assert!(renewal.is_none());
        });
    }
}

#[test]
fn temporary_refresh_failure_preserves_session_for_retry() {
    tauri::async_runtime::block_on(async {
        let (base, worker) = server(vec![
            (
                "/api/v1/presence/heartbeat",
                Some("old-access"),
                401,
                denied(),
            ),
            ("/api/v1/auth/refresh", None, 503, denied()),
            ("/api/v1/presence/heartbeat", Some("old-access"), 200, ok()),
        ]);
        let state = state(base, false, false);
        let mut renewal = None;
        let _operation = state.activity_operations.lock().await;
        tick(&state, &mut renewal).await;
        assert_eq!(state.core.snapshot().service, ChannelStatus::Retrying);
        tick(&state, &mut renewal).await;
        worker.join().unwrap();
        assert!(state.access_token().unwrap() == "old-access");
        assert!(state.core.snapshot().user_id.is_some());
        assert_eq!(state.core.snapshot().service, ChannelStatus::Connected);
    });
}

#[test]
fn proactive_refresh_rejection_clears_active_state_before_heartbeat() {
    tauri::async_runtime::block_on(async {
        let (base, worker) = server(vec![("/api/v1/auth/refresh", None, 401, denied())]);
        let state = state(base, true, true);
        let mut renewal = Some(("lease".into(), std::time::Instant::now()));
        let _operation = state.activity_operations.lock().await;
        tick(&state, &mut renewal).await;
        worker.join().unwrap();
        assert!(state.session.lock().unwrap().is_none());
        assert!(state.core.snapshot().activity.is_none() && renewal.is_none());
    });
}

#[test]
fn cleanup_failure_does_not_skip_signed_in_presence_or_clear_valid_session() {
    tauri::async_runtime::block_on(async {
        let (base, worker) = server(vec![(
            "/api/v1/presence/heartbeat",
            Some("old-access"),
            200,
            ok(),
        )]);
        let state = state(base, false, false);
        let _operation = state.activity_operations.lock().await;
        let mut renewed = None;
        let mut attempts = 0;
        tick_with_cleanup(&state, &mut renewed, || {
            attempts += 1;
            std::future::ready(Err("IPC_TIMEOUT".into()))
        })
        .await;
        worker.join().unwrap();
        assert_eq!(attempts, 1);
        assert!(state.access_token().unwrap() == "old-access");
        assert_eq!(
            state.network.snapshot()["status"]["errorCode"],
            "IPC_TIMEOUT"
        );
    });
}

#[test]
fn transient_activity_failure_retains_lease_until_local_renewal_deadline() {
    for expired in [false, true] {
        tauri::async_runtime::block_on(async {
            let (base, worker) = server(vec![
                (
                    "/api/v1/activities/activity/heartbeat",
                    Some("old-access"),
                    503,
                    denied(),
                ),
                ("/api/v1/presence/heartbeat", Some("old-access"), 200, ok()),
            ]);
            let state = state(base, false, true);
            let age = if expired { 61 } else { 1 };
            let mut renewal = Some((
                "lease".into(),
                std::time::Instant::now() - std::time::Duration::from_secs(age),
            ));
            let _operation = state.activity_operations.lock().await;
            tick(&state, &mut renewal).await;
            worker.join().unwrap();
            assert_eq!(state.core.snapshot().activity.is_none(), expired);
            assert!(state.core.snapshot().user_id.is_some());
        });
    }
}

#[test]
fn sync_recovery_refreshes_once_before_next_maintenance() {
    tauri::async_runtime::block_on(async {
        let (base, worker) = server(vec![
            ("/api/v1/auth/refresh", None, 200, fresh()),
            (
                "/api/v1/activities/activity/heartbeat",
                Some("fresh-access"),
                200,
                ok(),
            ),
            (
                "/api/v1/presence/heartbeat",
                Some("fresh-access"),
                200,
                ok(),
            ),
        ]);
        let state = state(base, true, true);
        let old = state.session.lock().unwrap().clone().unwrap();
        crate::sync::recover(&state, &old, "ACCESS_TOKEN_INVALID").await;
        let _operation = state.activity_operations.lock().await;
        let mut renewal = None;
        tick(&state, &mut renewal).await;
        worker.join().unwrap();
        assert!(state.access_token().unwrap() == "fresh-access");
        assert!(state.core.snapshot().activity.is_some() && renewal.is_some());
    });
}

#[test]
fn sync_recovery_keeps_temporary_failure_and_clears_explicit_denial() {
    for status in [503, 401] {
        tauri::async_runtime::block_on(async {
            let (base, worker) = server(vec![("/api/v1/auth/refresh", None, status, denied())]);
            let state = state(base, false, true);
            let old = state.session.lock().unwrap().clone().unwrap();
            crate::sync::recover(&state, &old, "ACCESS_TOKEN_INVALID").await;
            worker.join().unwrap();
            assert_eq!(state.session.lock().unwrap().is_some(), status == 503);
            assert_eq!(state.core.snapshot().activity.is_some(), status == 503);
        });
    }
}

#[test]
fn stale_sync_recovery_cannot_touch_replacement_session() {
    tauri::async_runtime::block_on(async {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        listener.set_nonblocking(true).unwrap();
        let state = state(
            format!("http://{}", listener.local_addr().unwrap()),
            false,
            true,
        );
        let old = state.session.lock().unwrap().clone().unwrap();
        let mut replacement = old.clone();
        replacement.session_id = "replacement".into();
        replacement.access_token = "replacement-access".into();
        *state.session.lock().unwrap() = Some(replacement);
        for code in ["ACCESS_TOKEN_INVALID", "SYNC_CLOSED"] {
            crate::sync::recover(&state, &old, code).await;
        }
        assert!(state.access_token().unwrap() == "replacement-access");
        assert!(state.core.snapshot().activity.is_some());
        assert!(
            matches!(listener.accept(), Err(error) if error.kind() == std::io::ErrorKind::WouldBlock)
        );
    });
}

#[test]
fn ordinary_sync_close_rechecks_activity_without_rotating_credentials() {
    for status in [200, 503, 404] {
        tauri::async_runtime::block_on(async {
            let (base, worker) = server(vec![(
                "/api/v1/activities/activity/heartbeat",
                Some("old-access"),
                status,
                ok(),
            )]);
            let state = state(base, false, true);
            let current = state.session.lock().unwrap().clone().unwrap();
            crate::sync::recover(&state, &current, "SYNC_CLOSED").await;
            worker.join().unwrap();
            assert!(state.access_token().unwrap() == "old-access");
            assert_eq!(state.core.snapshot().activity.is_some(), status != 404);
        });
    }
}

#[test]
#[ignore = "requires parent PostgreSQL/control-plane fixture and anonymous stdin"]
fn real_control_plane_refresh_and_disabled_cleanup() {
    fn input() -> serde_json::Value {
        let mut line = String::new();
        assert!(
            std::io::stdin().read_line(&mut line).unwrap() > 0,
            "Parent fixture input missing"
        );
        serde_json::from_str(&line).expect("Parent fixture JSON")
    }
    fn signal(value: &str) {
        println!("SESSION_PROBE_{value}");
        std::io::stdout().flush().unwrap();
    }
    tauri::async_runtime::block_on(async {
        let config = input();
        let base = config["base"].as_str().unwrap();
        assert!(base.starts_with("http://127.0.0.1:"), "Local fixture only");
        let mut credentials: AuthTokens =
            serde_json::from_value(config["tokens"].clone()).expect("Fixture credentials");
        let original = credentials.access_token.clone();
        let real_time = config["realTime"].as_bool().unwrap_or(false);
        let sync_race = config["syncRace"].as_bool().unwrap_or(false);
        let old_credentials = credentials.clone();
        let original_expiry = time::OffsetDateTime::parse(
            &credentials.access_expires_at,
            &time::format_description::well_known::Rfc3339,
        )
        .expect("Fixture expiry");
        if real_time {
            let remaining = original_expiry - time::OffsetDateTime::now_utc();
            assert!(remaining.whole_seconds() > 840 && remaining.whole_seconds() <= 900);
        } else {
            // 快速用例提前进入刷新分支；长测保留服务端真实签发的到期时间。
            credentials.access_expires_at = "2000-01-01T00:00:00Z".into();
        }
        let state = state(base.into(), false, false);
        state
            .core
            .session_changed(Some(credentials.user_id.clone()));
        *state.session.lock().unwrap() = Some(credentials);
        let activity: ActivityResult =
            serde_json::from_value(config["activity"].clone()).expect("Fixture activity");
        let original_lease = activity.lease_id.clone();
        let original_ip = activity.virtual_ipv4.clone();
        state.core.begin_activity(
            "real-join".into(),
            activity.activity_id.clone(),
            activity.device_id.clone(),
        );
        assert!(state.core.apply_activity_result("real-join", activity));
        if config["logout"].as_bool().unwrap_or(false) {
            let _operation = state.activity_operations.lock().await;
            let snapshot = logout(&state).await;
            assert!(
                snapshot.user_id.is_none() && snapshot.activity.is_none(),
                "Local logout cleared"
            );
            assert!(
                snapshot.last_error_code.is_none(),
                "Remote logout confirmed"
            );
            assert!(
                state.session.lock().unwrap().is_none(),
                "Credentials cleared"
            );
            assert!(
                matches!(
                    state.client.groups(&original).await,
                    Err(ClientError::Api { status: 401, .. })
                ),
                "Logged-out access rejected"
            );
            assert!(
                matches!(
                    state.client.refresh(&old_credentials.refresh_token).await,
                    Err(ClientError::Api {
                        status: 401 | 403,
                        ..
                    })
                ),
                "Logged-out refresh rejected"
            );
            let mut renewal = None;
            tick(&state, &mut renewal).await;
            assert!(
                renewal.is_none() && state.session.lock().unwrap().is_none(),
                "Maintenance cannot restore logout"
            );
            signal("LOGGED_OUT");
            return;
        }
        let mut old_socket = if sync_race {
            let cursor = state
                .client
                .sync_cursor(&original)
                .await
                .expect("Real sync cursor");
            Some(
                state
                    .client
                    .connect_sync(&original, cursor)
                    .await
                    .expect("Real sync connection"),
            )
        } else {
            None
        };
        let mut renewal = None;
        if real_time {
            let started = std::time::Instant::now();
            let mut interval = tokio::time::interval(std::time::Duration::from_secs(15));
            interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
            loop {
                interval.tick().await;
                assert!(
                    started.elapsed().as_secs() < 1000,
                    "Real-time probe deadline"
                );
                {
                    let _operation = state.activity_operations.lock().await;
                    tick(&state, &mut renewal).await;
                }
                let active = state
                    .core
                    .snapshot()
                    .activity
                    .expect("Active throughout renewal");
                assert!(
                    active.lease_id == original_lease
                        && active.virtual_ipv4.as_deref() == Some(original_ip.as_str())
                );
                assert!(renewal.is_some(), "Heartbeat retained throughout renewal");
                signal("TICK");
                if time::OffsetDateTime::now_utc() > original_expiry + time::Duration::seconds(15) {
                    break;
                }
            }
        } else {
            let _operation = state.activity_operations.lock().await;
            if sync_race {
                let recovery =
                    crate::sync::recover(&state, &old_credentials, "ACCESS_TOKEN_INVALID");
                tokio::pin!(recovery);
                // 确认恢复请求已实际等待操作锁，再由持锁的心跳先完成刷新。
                std::future::poll_fn(|context| {
                    assert!(
                        recovery.as_mut().poll(context).is_pending(),
                        "Recovery waits for operation lock"
                    );
                    std::task::Poll::Ready(())
                })
                .await;
                tick(&state, &mut renewal).await;
                drop(_operation);
                recovery.await;
            } else {
                tick(&state, &mut renewal).await;
            }
        }
        assert!(
            state.access_token().is_ok_and(|value| value != original),
            "Real credential rotation"
        );
        assert!(
            state.core.snapshot().activity.is_some() && renewal.is_some(),
            "Real lease retained"
        );
        assert!(
            state
                .client
                .groups(&state.access_token().unwrap())
                .await
                .is_ok(),
            "Fresh credential accepted"
        );
        assert!(
            matches!(
                state.client.groups(&original).await,
                Err(ClientError::Api { status: 401, .. })
            ),
            "Original credential rejected after rotation"
        );
        if let Some(socket) = old_socket.as_mut() {
            signal("ROTATED");
            let code = tokio::time::timeout(std::time::Duration::from_secs(5), async {
                loop {
                    if let Err(code) = socket.next().await {
                        break code;
                    }
                }
            })
            .await
            .expect("Old socket close deadline");
            assert!(
                code == "ACCESS_TOKEN_INVALID",
                "Real old socket invalidated"
            );
            crate::sync::recover(&state, &old_credentials, code).await;
            let current = state.access_token().unwrap();
            let cursor = state
                .client
                .sync_cursor(&current)
                .await
                .expect("Fresh sync cursor");
            let _socket = state
                .client
                .connect_sync(&current, cursor)
                .await
                .expect("Fresh sync connection");
            assert!(
                state.core.snapshot().activity.is_some(),
                "Old socket cannot clear current activity"
            );
        }
        signal("REFRESHED");
        assert!(
            input()["disabled"].as_bool() == Some(true),
            "Parent disable stage"
        );
        if sync_race {
            let current = state.session.lock().unwrap().clone().unwrap();
            crate::sync::recover(&state, &current, "ACCESS_TOKEN_INVALID").await;
            let _operation = state.activity_operations.lock().await;
            tick(&state, &mut renewal).await;
        } else {
            let _operation = state.activity_operations.lock().await;
            tick(&state, &mut renewal).await;
        }
        assert!(
            state.session.lock().unwrap().is_none(),
            "Disabled credentials cleared"
        );
        assert!(
            state.core.snapshot().user_id.is_none() && state.core.snapshot().activity.is_none(),
            "Disabled core cleared"
        );
        assert!(renewal.is_none(), "Disabled renewal cleared");
        signal("CLEARED");
    });
}
