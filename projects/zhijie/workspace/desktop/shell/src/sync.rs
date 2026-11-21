use crate::AppState;
use std::time::{Duration, Instant};
use tauri::{Emitter, Manager};

pub async fn maintain(app: tauri::AppHandle) {
    let state = app.state::<AppState>();
    maintain_connection(&state, |notice| {
        let _ = app.emit("product-changed", notice);
    })
    .await;
}

async fn maintain_connection(state: &AppState, notify: impl Fn(serde_json::Value)) {
    let mut binding = String::new();
    let mut token = String::new();
    let mut cursor = None;
    let mut connection = None;
    let mut last_ping = Instant::now();
    let mut last_received = Instant::now();
    loop {
        let tokens = state.session.lock().expect("session lock").clone();
        let Some(tokens) = tokens else {
            binding.clear();
            token.clear();
            cursor = None;
            connection = None;
            tokio::time::sleep(Duration::from_secs(1)).await;
            continue;
        };
        if binding != tokens.session_id {
            binding = tokens.session_id.clone();
            cursor = None;
            connection = None;
        }
        if token != tokens.access_token {
            token = tokens.access_token.clone();
            connection = None;
        }
        if cursor.is_none() {
            match state.client.sync_cursor(&token).await {
                Ok(value) => {
                    cursor = Some(value);
                    notify(notice(&tokens.user_id, None));
                }
                Err(_) => {
                    tokio::time::sleep(Duration::from_secs(1)).await;
                    continue;
                }
            }
        }
        if connection.is_none() {
            match state.client.connect_sync(&token, cursor.unwrap()).await {
                Ok(socket) => {
                    if !restore_service(state, &tokens).await {
                        continue;
                    }
                    connection = Some(socket);
                    last_ping = Instant::now();
                    last_received = Instant::now();
                    notify(notice(&tokens.user_id, None));
                }
                Err(_) => {
                    tokio::time::sleep(Duration::from_secs(2)).await;
                    continue;
                }
            }
        }
        let socket = connection.as_mut().unwrap();
        let outcome = tokio::time::timeout(Duration::from_secs(1), socket.next()).await;
        // 登录切换/刷新后，旧连接结果不能更新游标或撤销新活动。
        let current = state.session.lock().expect("session lock").clone();
        if current
            .as_ref()
            .is_none_or(|value| value.access_token != token)
        {
            connection = None;
            continue;
        }
        match outcome {
            Ok(Ok(Some(event))) => {
                last_received = Instant::now();
                if event.sequence <= cursor.unwrap() {
                    continue;
                }
                if !apply_event(state, &tokens, &event, &mut cursor).await {
                    connection = None;
                    continue;
                }
                notify(notice(&tokens.user_id, Some(&event)));
            }
            Ok(Err(code)) => {
                connection = None;
                if code == "RESYNC_REQUIRED" {
                    cursor = None;
                }
                notify(notice(&tokens.user_id, None));
                recover(state, &tokens, code).await;
                tokio::time::sleep(Duration::from_secs(1)).await;
            }
            Ok(Ok(None)) => {
                last_received = Instant::now();
            }
            Err(_) => {
                if last_received.elapsed() >= Duration::from_secs(45) {
                    connection = None;
                    continue;
                }
                if last_ping.elapsed() >= Duration::from_secs(15) {
                    if socket.ping().await.is_err() {
                        connection = None;
                    }
                    last_ping = Instant::now();
                }
            }
        }
    }
}

async fn restore_service(state: &AppState, tokens: &zhijie_core::AuthTokens) -> bool {
    let _operation = state.activity_operations.lock().await;
    let current = state.session.lock().expect("session lock").clone();
    if current
        .as_ref()
        .is_none_or(|value| value.access_token != tokens.access_token)
    {
        return false;
    }
    recheck_activity(state, tokens).await;
    state.core.set_service_available(true);
    true
}

async fn apply_event(
    state: &AppState,
    tokens: &zhijie_core::AuthTokens,
    event: &zhijie_core::sync::SyncEvent,
    cursor: &mut Option<u64>,
) -> bool {
    // 取得操作锁后再次确认凭据，成功处理前不能确认事件游标。
    let _operation = state.activity_operations.lock().await;
    let current = state.session.lock().expect("session lock").clone();
    if current
        .as_ref()
        .is_none_or(|value| value.access_token != tokens.access_token)
    {
        return false;
    }
    if event.r#type.ends_with(".revoked") {
        if let Some(activity) = state.core.snapshot().activity {
            if event.revokes(&tokens.user_id, &activity) {
                state
                    .core
                    .revoke(&activity.activity_id, activity.member_generation);
                state.voice.revoke().await;
                if let Err(code) = state.network.stop().await {
                    state.network.fail(code);
                }
                state.core.clear_activity(&activity.activity_id);
            }
        }
    }
    state.core.set_service_available(true);
    *cursor = Some(event.sequence);
    true
}

// 撤销可能先关闭同步连接；取得操作锁后再次确认凭据，旧连接不能刷新或清理新会话。
pub(crate) async fn recover(state: &AppState, tokens: &zhijie_core::AuthTokens, code: &str) {
    let _operation = state.activity_operations.lock().await;
    let current = state.session.lock().expect("session lock").clone();
    if current
        .as_ref()
        .is_none_or(|value| value.access_token != tokens.access_token)
    {
        return;
    }
    if code == "ACCESS_TOKEN_INVALID" {
        match state.client.refresh(&tokens.refresh_token).await {
            Ok(fresh) => *state.session.lock().expect("session lock") = Some(fresh),
            Err(zhijie_core::ClientError::Api {
                status: 401 | 403, ..
            }) => {
                state.voice.revoke().await;
                if let Err(code) = state.network.stop().await {
                    state.network.fail(code);
                }
                *state.session.lock().expect("session lock") = None;
                state.core.session_changed(None);
            }
            Err(_) => {}
        }
        return;
    }
    recheck_activity(state, tokens).await;
}

async fn recheck_activity(state: &AppState, tokens: &zhijie_core::AuthTokens) {
    if let Some(activity) = state.core.snapshot().activity {
        if matches!(
            state
                .client
                .heartbeat(&tokens.access_token, &activity)
                .await,
            Err(zhijie_core::ClientError::Api {
                status: 401 | 403 | 404 | 409,
                ..
            })
        ) {
            state
                .core
                .revoke(&activity.activity_id, activity.member_generation);
            state.voice.revoke().await;
            if let Err(code) = state.network.stop().await {
                state.network.fail(code);
            }
            state.core.clear_activity(&activity.activity_id);
        }
    }
}

fn notice(user_id: &str, event: Option<&zhijie_core::sync::SyncEvent>) -> serde_json::Value {
    if let Some(group_id) = event
        .filter(|event| event.r#type == "message.created")
        .and_then(|event| event.group_id.as_deref())
        .filter(|id| !id.is_empty())
    {
        return serde_json::json!({"userId": user_id, "messageGroupId": group_id});
    }
    serde_json::json!({"userId": user_id})
}

#[cfg(test)]
mod browser_probe;
#[cfg(test)]
mod maintenance_probe;
#[cfg(test)]
mod tests;
