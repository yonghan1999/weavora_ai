use super::*;
use crate::test_support::server;
use std::sync::Arc;
use zhijie_helper::service::{HelperService, NetworkBackend};

#[derive(Default)]
struct LifecycleBackend(Mutex<Vec<&'static str>>);

impl NetworkBackend for LifecycleBackend {
    fn start(&self, _: &NetworkStart) -> Result<(), String> {
        self.0.lock().unwrap().push("start");
        Ok(())
    }
    fn renew(&self, _: &NetworkStart) -> Result<(), String> {
        self.0.lock().unwrap().push("renew");
        Ok(())
    }
    fn stop(&self, _: &str) -> Result<(), String> {
        self.0.lock().unwrap().push("stop");
        Ok(())
    }
    fn update_peers(
        &self,
        _: &str,
        _: Vec<zhijie_helper::model::NetworkPeer>,
    ) -> Result<(), String> {
        Ok(())
    }
    fn migrate_path(&self, _: &str, _: NetworkPath) -> Result<(), String> {
        Ok(())
    }
}

fn handle(service: &HelperService, request: HelperRequest) -> Result<NetworkStatus, String> {
    let response = service.handle(request);
    if response.accepted {
        response.data.ok_or("IPC_RESPONSE_INVALID".into())
    } else {
        Err(response.error_code.unwrap())
    }
}

fn lifecycle() -> (
    NetworkManager,
    Arc<LifecycleBackend>,
    HelperService,
    NetworkStart,
) {
    let backend = Arc::new(LifecycleBackend::default());
    let service = HelperService::new(backend.clone());
    let manager = NetworkManager::default();
    let initial = current();
    handle(&service, request("network.start", Some(&initial))).unwrap();
    *manager.current.lock().unwrap() = Some(initial.clone());
    let updated = NetworkStart {
        ticket: "fresh-ticket".into(),
        ..initial
    };
    (manager, backend, service, updated)
}

#[test]
fn logout_keeps_failed_helper_cleanup_after_credentials_are_cleared() {
    tauri::async_runtime::block_on(async {
        let (manager, backend, service, _) = lifecycle();
        let (base, worker) = server(vec![(
            "/api/v1/auth/logout",
            None,
            503,
            serde_json::json!({"error":{"code":"SERVICE_UNAVAILABLE"}}),
        )]);
        let state = crate::AppState {
            client: ControlPlaneClient::new(base).unwrap(),
            core: Default::default(),
            voice: Default::default(),
            network: manager,
            session: Mutex::new(Some(zhijie_core::AuthTokens {
                session_id: "session".into(),
                user_id: "user".into(),
                access_token: "access".into(),
                refresh_token: "refresh".into(),
                access_expires_at: "2099-01-01T00:00:00Z".into(),
                refresh_expires_at: "2099-01-01T00:00:00Z".into(),
            })),
            activity_operations: Default::default(),
        };
        state.core.session_changed(Some("user".into()));
        let _operation = state.activity_operations.lock().await;
        let snapshot = crate::session::logout_with_cleanup(&state, || {
            state
                .network
                .stop_with(|_| std::future::ready(Err("IPC_TIMEOUT".into())))
        })
        .await;
        worker.join().unwrap();
        assert!(snapshot.user_id.is_none());
        assert_eq!(
            snapshot.last_error_code.as_deref(),
            Some("LOGOUT_CLEANUP_PENDING")
        );
        assert!(state.session.lock().unwrap().is_none());
        assert!(state.network.current.lock().unwrap().is_some());
        assert!(state.network.stop_requested.load(Ordering::Relaxed));
        let mut renewed = None;
        crate::session::tick_with_cleanup(&state, &mut renewed, || {
            state
                .network
                .retry_cleanup_with(|request| std::future::ready(handle(&service, request)))
        })
        .await;
        assert_eq!(backend.0.lock().unwrap().as_slice(), ["start", "stop"]);
        assert_eq!(state.network.snapshot()["status"]["state"], "DISCONNECTED");
        assert!(!state.network.stop_requested.load(Ordering::Relaxed));
    });
}

#[test]
fn login_replacement_retains_failed_stop_for_maintenance_without_old_session_recovery() {
    tauri::async_runtime::block_on(async {
        let (manager, backend, service, _) = lifecycle();
        let credentials = zhijie_core::AuthTokens {
            session_id: "new-session".into(),
            user_id: "new-user".into(),
            access_token: "new-access".into(),
            refresh_token: "new-refresh".into(),
            access_expires_at: "2099-01-01T00:00:00Z".into(),
            refresh_expires_at: "2099-01-01T00:00:00Z".into(),
        };
        let old = zhijie_core::AuthTokens {
            session_id: "old-session".into(),
            user_id: "old-user".into(),
            access_token: "old-access".into(),
            ..credentials.clone()
        };
        let (base, worker) = server(vec![
            (
                "/api/v1/auth/login",
                None,
                200,
                serde_json::json!({"data":credentials}),
            ),
            (
                "/api/v1/presence/heartbeat",
                Some("new-access"),
                200,
                serde_json::json!({"data":{}}),
            ),
        ]);
        let state = crate::AppState {
            client: ControlPlaneClient::new(base).unwrap(),
            core: Default::default(),
            voice: Default::default(),
            network: manager,
            session: Mutex::new(Some(old.clone())),
            activity_operations: Default::default(),
        };
        state.core.session_changed(Some("old-user".into()));
        {
            let _operation = state.activity_operations.lock().await;
            let snapshot = crate::session::login_with_cleanup(
                &state,
                "fixture@example.com",
                "fixture",
                "device",
                || {
                    state
                        .network
                        .stop_with(|_| std::future::ready(Err("IPC_TIMEOUT".into())))
                },
            )
            .await
            .unwrap();
            assert_eq!(snapshot.user_id.as_deref(), Some("new-user"));
            assert!(state.network.stop_requested.load(Ordering::Relaxed));
            assert!(state.network.current.lock().unwrap().is_some());
            state.network.apply_helper_status(connected());
            assert_eq!(
                state.network.snapshot()["status"]["errorCode"],
                "IPC_TIMEOUT"
            );
        }
        crate::sync::recover(&state, &old, "ACCESS_TOKEN_INVALID").await;
        let _operation = state.activity_operations.lock().await;
        let mut renewed = None;
        crate::session::tick_with_cleanup(&state, &mut renewed, || {
            state
                .network
                .retry_cleanup_with(|request| std::future::ready(handle(&service, request)))
        })
        .await;
        worker.join().unwrap();
        assert_eq!(backend.0.lock().unwrap().as_slice(), ["start", "stop"]);
        assert!(!state.network.stop_requested.load(Ordering::Relaxed));
        assert_eq!(state.network.snapshot()["status"]["state"], "DISCONNECTED");
        assert_eq!(
            state.session.lock().unwrap().as_ref().unwrap(),
            &credentials
        );
    });
}

#[test]
fn renewal_lost_reply_recovers_or_stops_with_the_actual_helper_ticket() {
    for applied in [false, true] {
        for stop in [false, true] {
            tauri::async_runtime::block_on(async {
                let (manager, backend, service, updated) = lifecycle();
                let result = manager
                    .renew_with(updated.clone(), |request| {
                        let result = if applied {
                            handle(&service, request).and_then(|_| Err("IPC_TIMEOUT".into()))
                        } else {
                            Err("IPC_TIMEOUT".into())
                        };
                        std::future::ready(result)
                    })
                    .await;
                assert_eq!(result.err().as_deref(), Some("IPC_TIMEOUT"));
                assert!(manager.pending_renewal.lock().unwrap().is_some());
                assert!(manager.current.lock().unwrap().as_ref().unwrap().ticket == "ticket");
                if stop {
                    manager
                        .stop_with(|request| std::future::ready(handle(&service, request)))
                        .await
                        .unwrap();
                    assert!(manager.current.lock().unwrap().is_none());
                    assert!(manager.pending_renewal.lock().unwrap().is_none());
                    assert_eq!(service.status().state, NetworkState::Disconnected);
                    assert!(backend.0.lock().unwrap().last() == Some(&"stop"));
                } else {
                    manager
                        .renew_with(updated, |request| {
                            std::future::ready(handle(&service, request))
                        })
                        .await
                        .unwrap();
                    assert!(manager.pending_renewal.lock().unwrap().is_none());
                    assert!(
                        manager.current.lock().unwrap().as_ref().unwrap().ticket == "fresh-ticket"
                    );
                    assert_eq!(
                        handle(&service, request("network.stop", Some(&current())))
                            .err()
                            .as_deref(),
                        Some("NETWORK_TICKET_REQUIRED")
                    );
                    manager
                        .stop_with(|request| std::future::ready(handle(&service, request)))
                        .await
                        .unwrap();
                }
            });
        }
    }
}

#[test]
fn uncertain_renewal_stop_never_falls_back_on_transport_or_generation_errors() {
    tauri::async_runtime::block_on(async {
        for code in [
            "IPC_TIMEOUT",
            "GENERATION_STALE",
            "IPC_SERVER_NOT_ALLOWED",
            "NETWORK_CLEANUP_FAILED",
        ] {
            let (manager, _, service, updated) = lifecycle();
            *manager.pending_renewal.lock().unwrap() = Some(updated);
            let mut calls = 0;
            let result = manager
                .stop_with(|_| {
                    calls += 1;
                    std::future::ready(Err(code.into()))
                })
                .await;
            assert_eq!(result.err().as_deref(), Some(code));
            assert_eq!(calls, 1);
            assert!(manager.current.lock().unwrap().is_some());
            assert!(manager.pending_renewal.lock().unwrap().is_some());
            manager
                .stop_with(|request| std::future::ready(handle(&service, request)))
                .await
                .unwrap();
        }
    });
}

#[test]
fn logged_out_maintenance_retries_failed_cleanup_without_renewing_or_claiming_connection() {
    tauri::async_runtime::block_on(async {
        let (manager, backend, service, _) = lifecycle();
        assert_eq!(
            manager
                .stop_with(|_| std::future::ready(Err("IPC_TIMEOUT".into())))
                .await
                .err()
                .as_deref(),
            Some("IPC_TIMEOUT")
        );
        manager.apply_helper_status(connected());
        assert_eq!(manager.snapshot()["status"]["state"], "FAILED");
        assert_eq!(manager.snapshot()["status"]["errorCode"], "IPC_TIMEOUT");
        let state = crate::AppState {
            client: ControlPlaneClient::new("http://127.0.0.1:1").unwrap(),
            core: Default::default(),
            voice: Default::default(),
            network: manager,
            session: Mutex::new(None),
            activity_operations: Default::default(),
        };
        let _operation = state.activity_operations.lock().await;
        state
            .network
            .maintain(
                &state.client,
                "unused",
                &ActivityContext {
                    activity_id: "activity".into(),
                    activity_generation: 1,
                    member_generation: 2,
                    device_id: "device".into(),
                    lease_id: "lease".into(),
                    virtual_ipv4: None,
                    voice: zhijie_core::ChannelStatus::Disconnected,
                    network: zhijie_core::ChannelStatus::Failed,
                },
            )
            .await
            .unwrap();
        assert_eq!(backend.0.lock().unwrap().as_slice(), ["start"]);
        let mut renewed = Some(("old-lease".into(), std::time::Instant::now()));
        let mut attempts = 0;
        crate::session::tick_with_cleanup(&state, &mut renewed, || {
            state.network.retry_cleanup_with(|_| {
                attempts += 1;
                std::future::ready(Err("IPC_TIMEOUT".into()))
            })
        })
        .await;
        assert_eq!(attempts, 1);
        assert!(renewed.is_none());
        assert!(state.network.current.lock().unwrap().is_some());
        crate::session::tick_with_cleanup(&state, &mut renewed, || {
            state.network.retry_cleanup_with(|request| {
                assert_eq!(request.method, "network.stop");
                std::future::ready(handle(&service, request))
            })
        })
        .await;
        assert_eq!(backend.0.lock().unwrap().as_slice(), ["start", "stop"]);
        assert!(state.network.current.lock().unwrap().is_none());
        assert!(!state.network.stop_requested.load(Ordering::Relaxed));
        assert_eq!(state.network.snapshot()["status"]["state"], "DISCONNECTED");
        crate::session::tick_with_cleanup(&state, &mut renewed, || {
            state.network.retry_cleanup_with(
                |_| -> std::future::Ready<Result<NetworkStatus, String>> {
                    panic!("completed cleanup must not send another request")
                },
            )
        })
        .await;
    });
}

fn current() -> NetworkStart {
    NetworkStart {
        protocol_version: 1,
        request_id: "request".into(),
        ticket: "ticket".into(),
        activity_id: "activity".into(),
        activity_generation: 1,
        member_generation: 2,
        device_id: "device".into(),
        lease_id: "lease".into(),
        policy_revision: 3,
        allowed_paths: vec![NetworkPath::Relay],
        private_key: base64::engine::general_purpose::STANDARD.encode([7u8; 32]),
        virtual_ipv4: "10.231.0.1".into(),
        activity_cidr: "10.231.0.0/27".into(),
        expires_at: "2099-01-01T00:00:00Z".into(),
    }
}

fn connected() -> NetworkStatus {
    NetworkStatus {
        state: NetworkState::Connected,
        activity_id: Some("activity".into()),
        member_generation: Some(2),
        policy_revision: Some(3),
        udp_port: Some(39871),
        ..NetworkStatus::default()
    }
}

#[test]
fn failed_start_requires_stop_only_if_delivery_was_possible() {
    for uncertain in [false, true] {
        tauri::async_runtime::block_on(async {
            let manager = NetworkManager::default();
            let original = current();
            *manager.current.lock().unwrap() = Some(original.clone());
            let code = "HELPER_UNAVAILABLE".to_string();
            let failure = if uncertain {
                CallFailure::PossiblySent(code.clone())
            } else {
                CallFailure::NotSent(code.clone())
            };
            assert_eq!(manager.start_result(Err(failure)).err(), Some(code));
            let mut stops = 0;
            manager
                .stop_with(|request| {
                    stops += 1;
                    assert_eq!(request.method, "network.stop");
                    assert_eq!(request.ticket.as_deref(), Some(original.ticket.as_str()));
                    std::future::ready(Ok(NetworkStatus::default()))
                })
                .await
                .unwrap();
            assert_eq!(stops, usize::from(uncertain));
            assert!(manager.current.lock().unwrap().is_none());
        });
    }
}

#[test]
fn registration_failure_survives_helper_poll_until_real_http_recovery() {
    tauri::async_runtime::block_on(async {
        let peers = serde_json::json!([{"userId":"peer", "virtualIpv4":"10.231.0.2"}]);
        let (base, worker) = server(vec![
            (
                "/api/v1/activities/activity/network-peers/register",
                Some("access"),
                503,
                serde_json::json!({"error":{"code":"SERVICE_UNAVAILABLE"}}),
            ),
            (
                "/api/v1/activities/activity/network-peers/register",
                Some("access"),
                200,
                serde_json::json!({"data":peers}),
            ),
        ]);
        let client = ControlPlaneClient::new(base).unwrap();
        let manager = NetworkManager::default();
        let current = current();
        *manager.current.lock().unwrap() = Some(current.clone());
        *manager.peers.lock().unwrap() = vec![serde_json::json!({"userId":"old-peer"})];
        manager.apply_helper_status(connected());
        assert_eq!(
            manager
                .register(&client, "access", &current)
                .await
                .err()
                .as_deref(),
            Some("SERVICE_UNAVAILABLE")
        );
        // 调用生产轮询所用的状态入口，成功的 Helper 不能抹掉注册失败。
        manager.apply_helper_status(connected());
        let snapshot = manager.snapshot();
        assert_eq!(snapshot["status"]["state"], "FAILED");
        assert_eq!(snapshot["status"]["errorCode"], "SERVICE_UNAVAILABLE");
        assert_eq!(snapshot["peers"], serde_json::json!([]));
        assert!(manager.current.lock().unwrap().is_some());
        manager.apply_helper_status(NetworkStatus {
            state: NetworkState::Failed,
            error_code: Some("GRANT_EXPIRED".into()),
            ..connected()
        });
        assert_eq!(manager.snapshot()["status"]["errorCode"], "GRANT_EXPIRED");
        manager.apply_helper_status(connected());
        manager.register(&client, "access", &current).await.unwrap();
        manager.apply_helper_status(connected());
        assert_eq!(manager.snapshot()["status"]["state"], "CONNECTED");
        assert!(manager.snapshot()["status"]["errorCode"].is_null());
        assert_eq!(manager.snapshot()["peers"], peers);
        worker.join().unwrap();
    });
}

#[test]
fn malformed_peer_registration_does_not_survive_as_connected_and_stop_clears_it() {
    tauri::async_runtime::block_on(async {
        let (base, worker) = server(vec![(
            "/api/v1/activities/activity/network-peers/register",
            Some("access"),
            200,
            serde_json::json!({"data":{}}),
        )]);
        let manager = NetworkManager::default();
        manager.apply_helper_status(connected());
        let client = ControlPlaneClient::new(base).unwrap();
        assert_eq!(
            manager
                .register(&client, "access", &current())
                .await
                .err()
                .as_deref(),
            Some("INVALID_SERVER_RESPONSE")
        );
        manager.apply_helper_status(connected());
        assert_eq!(manager.snapshot()["status"]["state"], "FAILED");
        // 此场景没有启动 Helper；复用生产空会话停止分支清除本地状态。
        manager.stop().await.unwrap();
        assert!(manager.registration_error.lock().unwrap().is_none());
        assert_eq!(manager.snapshot()["status"]["state"], "DISCONNECTED");
        worker.join().unwrap();
    });
}
