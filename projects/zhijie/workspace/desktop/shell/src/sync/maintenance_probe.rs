use super::maintain_connection;
use crate::AppState;
use serde_json::Value;
use std::io::Write;
use std::sync::Arc;
use std::time::Duration;
use zhijie_core::{ActivityResult, AuthTokens, ChannelStatus, ClientError, ControlPlaneClient};

fn input() -> Value {
    let mut line = String::new();
    assert!(std::io::stdin().read_line(&mut line).unwrap() > 0);
    serde_json::from_str(&line).expect("Private fixture input")
}

fn signal(value: &str) {
    println!("BACKGROUND_PROBE_{value}");
    std::io::stdout().flush().unwrap();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
#[ignore = "requires parent PostgreSQL/control-plane fixture and anonymous stdin"]
async fn production_session_and_sync_loops() {
    let config = input();
    let base = config["base"].as_str().unwrap();
    assert!(base.starts_with("http://127.0.0.1:"));
    let credentials: AuthTokens = serde_json::from_value(config["tokens"].clone()).unwrap();
    let activity: ActivityResult = serde_json::from_value(config["activity"].clone()).unwrap();
    let original = credentials.access_token.clone();
    let original_lease = activity.lease_id.clone();
    let original_ip = activity.virtual_ipv4.clone();
    let group = config["group"].as_str().unwrap().to_owned();
    let state = Arc::new(AppState::new(ControlPlaneClient::new(base).unwrap()));
    state
        .core
        .session_changed(Some(credentials.user_id.clone()));
    *state.session.lock().unwrap() = Some(credentials);
    state.core.begin_activity(
        "background-join".into(),
        activity.activity_id.clone(),
        activity.device_id.clone(),
    );
    assert!(state
        .core
        .apply_activity_result("background-join", activity));
    let (notices, mut received) = tokio::sync::mpsc::unbounded_channel();
    let mut loops = tokio::task::JoinSet::new();
    let maintenance = state.clone();
    loops.spawn(async move { crate::session_loop(&maintenance).await });
    let sync = state.clone();
    loops.spawn(async move {
        maintain_connection(&sync, |notice| {
            notices.send(notice).unwrap();
        })
        .await;
    });
    tokio::time::timeout(Duration::from_secs(15), async {
        for _ in 0..2 {
            received.recv().await.expect("Sync startup notices");
        }
    })
    .await
    .expect("Production sync startup deadline");
    signal("READY");
    loop {
        assert!(
            loops.try_join_next().is_none(),
            "Background loops stay alive"
        );
        let command = tokio::task::spawn_blocking(input).await.unwrap();
        if command["expire"].as_bool() == Some(true) {
            let _operation = state.activity_operations.lock().await;
            state
                .session
                .lock()
                .unwrap()
                .as_mut()
                .unwrap()
                .access_expires_at = "2000-01-01T00:00:00Z".into();
            signal("ARMED");
            continue;
        }
        if command["activityRevoked"].as_bool() == Some(true) {
            tokio::time::timeout(Duration::from_secs(15), async {
                while state.core.snapshot().activity.is_some() {
                    tokio::time::sleep(Duration::from_millis(20)).await;
                }
            })
            .await
            .expect("Recovered background loops clear revoked activity");
            let token = state.access_token().expect("Account remains signed in");
            assert!(state.client.groups(&token).await.is_ok());
            signal("REVOKED");
            continue;
        }
        if command["offline"].as_bool() == Some(true) {
            tokio::time::timeout(Duration::from_secs(20), async {
                while state.core.snapshot().service != ChannelStatus::Retrying {
                    tokio::time::sleep(Duration::from_millis(20)).await;
                }
            })
            .await
            .expect("Production maintenance reports unreachable service");
            assert!(state.core.snapshot().activity.is_some());
            signal("OFFLINE");
            continue;
        }
        if command["expiredOffline"].as_bool() == Some(true) {
            tokio::time::timeout(Duration::from_secs(80), async {
                while state.core.snapshot().activity.is_some() {
                    tokio::time::sleep(Duration::from_millis(20)).await;
                }
            })
            .await
            .expect("Production maintenance releases activity after heartbeat deadline");
            assert!(state.core.snapshot().user_id.is_some());
            assert!(state.session.lock().unwrap().is_some());
            assert_eq!(state.core.snapshot().service, ChannelStatus::Retrying);
            signal("EXPIRED");
            continue;
        }
        if command["serviceRestored"].as_bool() == Some(true) {
            tokio::time::timeout(Duration::from_secs(15), async {
                while state.core.snapshot().service != ChannelStatus::Connected {
                    tokio::time::sleep(Duration::from_millis(20)).await;
                }
            })
            .await
            .expect("Production maintenance reports restored service");
            signal("ONLINE");
            continue;
        }
        if command["stop"].as_bool() == Some(true) {
            signal("STOPPED");
            break;
        }
        if command["disabled"].as_bool() == Some(true) {
            tokio::time::timeout(Duration::from_secs(20), async {
                loop {
                    if state.session.lock().unwrap().is_none()
                        && state.core.snapshot().user_id.is_none()
                        && state.core.snapshot().activity.is_none()
                    {
                        break;
                    }
                    tokio::time::sleep(Duration::from_millis(20)).await;
                }
            })
            .await
            .expect("Production background loops clear disabled account");
            assert!(matches!(
                state.client.groups(&original).await,
                Err(ClientError::Api {
                    status: 401 | 403,
                    ..
                })
            ));
            signal("CLEARED");
            break;
        }
        let expected = command["messageId"].as_str().unwrap();
        tokio::time::timeout(Duration::from_secs(5), async {
            loop {
                let notice = received.recv().await.expect("Live production sync loop");
                if notice["messageGroupId"].as_str() != Some(group.as_str()) {
                    continue;
                }
                let token = state.access_token().unwrap();
                let messages = state
                    .client
                    .product_request(
                        Some(&token),
                        "GET",
                        &format!("/api/v1/groups/{group}/messages"),
                        Value::Null,
                    )
                    .await
                    .expect("Notified message readable with current credentials");
                assert!(messages
                    .as_array()
                    .unwrap()
                    .iter()
                    .any(|message| message["id"].as_str() == Some(expected)));
                break;
            }
        })
        .await
        .expect("Production loop message notification deadline");
        if command["expectConnected"].as_bool() == Some(true) {
            assert_eq!(
                state.core.snapshot().service,
                ChannelStatus::Connected,
                "A live authenticated message cannot leave the service marked unreachable"
            );
        }
        if command["expectRevoked"].as_bool() != Some(true) {
            let active = state.core.snapshot().activity.expect("Activity retained");
            assert_eq!(active.lease_id, original_lease);
            assert_eq!(active.virtual_ipv4.as_deref(), Some(original_ip.as_str()));
        }
        if command["rotated"].as_bool() == Some(true) {
            let token = state.access_token().unwrap();
            assert!(
                token != original,
                "Background maintenance rotated credentials"
            );
            assert!(state.client.groups(&token).await.is_ok());
            assert!(matches!(
                state.client.groups(&original).await,
                Err(ClientError::Api { status: 401, .. })
            ));
        }
        signal("MESSAGE");
    }
    loops.abort_all();
    while let Some(result) = loops.join_next().await {
        assert!(result.unwrap_err().is_cancelled());
    }
}
