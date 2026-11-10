#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::sync::Mutex;
use tauri::State;
use zhijie_core::{AuthTokens, ClientCore, ClientError, ClientSnapshot, ControlPlaneClient};

struct AppState {
    client: ControlPlaneClient,
    core: ClientCore,
    voice: zhijie_voice::VoiceManager,
    session: Mutex<Option<AuthTokens>>,
}

impl AppState {
    fn new() -> Self {
        let base_url = std::env::var("ZHIJIE_CONTROL_PLANE_URL")
            .unwrap_or_else(|_| "http://127.0.0.1:8080".to_string());
        Self {
            client: ControlPlaneClient::new(base_url).expect("valid HTTP client"),
            core: ClientCore::default(),
            voice: zhijie_voice::VoiceManager::default(),
            session: Mutex::new(None),
        }
    }

    fn access_token(&self) -> Result<String, String> {
        self.session
            .lock()
            .expect("session lock")
            .as_ref()
            .map(|tokens| tokens.access_token.clone())
            .ok_or_else(|| "AUTH_REQUIRED".to_string())
    }
}

#[tauri::command]
async fn session_login(
    email: String,
    password: String,
    device_id: String,
    state: State<'_, AppState>,
) -> Result<ClientSnapshot, String> {
    let tokens = state
        .client
        .login(&email, &password, &device_id)
        .await
        .map_err(error_code)?;
    state.core.session_changed(Some(tokens.user_id.clone()));
    *state.session.lock().expect("session lock") = Some(tokens);
    Ok(state.core.snapshot())
}

#[tauri::command]
async fn group_list(state: State<'_, AppState>) -> Result<Vec<serde_json::Value>, String> {
    state
        .client
        .groups(&state.access_token()?)
        .await
        .map_err(error_code)
}

#[tauri::command]
async fn room_list(
    group_id: String,
    state: State<'_, AppState>,
) -> Result<Vec<serde_json::Value>, String> {
    state
        .client
        .rooms(&state.access_token()?, &group_id)
        .await
        .map_err(error_code)
}

#[tauri::command]
async fn activity_join(
    room_id: String,
    device_id: String,
    password: Option<String>,
    operation_id: String,
    state: State<'_, AppState>,
) -> Result<ClientSnapshot, String> {
    let result = state
        .client
        .join(
            &state.access_token()?,
            &room_id,
            &device_id,
            password.as_deref(),
            &operation_id,
        )
        .await
        .map_err(error_code)?;
    // 服务端切换成功后再替换本地活动；失败切换不得破坏原房间快照。
    state.voice.disconnect().await;
    state
        .core
        .begin_activity(operation_id.clone(), room_id, device_id);
    state.core.apply_activity_result(&operation_id, result);
    Ok(state.core.snapshot())
}

#[tauri::command]
async fn session_logout(state: State<'_, AppState>) -> Result<ClientSnapshot, String> {
    if let Some(activity) = state.core.snapshot().activity {
        state
            .client
            .leave(&state.access_token()?, &activity.activity_id)
            .await
            .map_err(error_code)?;
        state.core.clear_activity(&activity.activity_id);
    }
    state.voice.disconnect().await;
    state
        .client
        .product_request(
            None,
            "POST",
            "/api/v1/auth/logout",
            serde_json::json!({"accessToken": state.access_token()?}),
        )
        .await
        .map_err(error_code)?;
    *state.session.lock().expect("session lock") = None;
    state.core.session_changed(None);
    Ok(state.core.snapshot())
}

// 只允许固定产品资源；不向 WebView 暴露任意 URL、请求头或会话令牌。
#[tauri::command]
async fn product_request(
    resource: String,
    id: Option<String>,
    body: Option<serde_json::Value>,
    state: State<'_, AppState>,
) -> Result<serde_json::Value, String> {
    let (method, path, public) = product_route(&resource, id.as_deref())?;
    let token = if public {
        None
    } else {
        Some(state.access_token()?)
    };
    state
        .client
        .product_request(
            token.as_deref(),
            method,
            &path,
            body.unwrap_or(serde_json::Value::Null),
        )
        .await
        .map_err(error_code)
}

fn product_route(resource: &str, id: Option<&str>) -> Result<(&'static str, String, bool), String> {
    let keyed = |prefix: &str, suffix: &str| -> Result<String, String> {
        let value = id.ok_or("INVALID_RESOURCE_ID")?;
        if value.is_empty()
            || value.len() > 128
            || !value
                .bytes()
                .all(|c| c.is_ascii_alphanumeric() || c == b'-')
        {
            return Err("INVALID_RESOURCE_ID".into());
        }
        Ok(format!("/api/v1/{prefix}/{value}{suffix}"))
    };
    let (method, path, public) = match resource {
        "register" => ("POST", "/api/v1/auth/register".into(), true),
        "verify" => ("POST", "/api/v1/auth/verify-email".into(), true),
        "forgot" => ("POST", "/api/v1/auth/password/forgot".into(), true),
        "reset" => ("POST", "/api/v1/auth/password/reset".into(), true),
        "friends" => ("GET", "/api/v1/friends".into(), false),
        "friend-search" => {
            let query: String = id
                .unwrap_or("")
                .bytes()
                .map(|b| format!("%{b:02X}"))
                .collect();
            if query.len() > 768 {
                return Err("INVALID_RESOURCE_ID".into());
            }
            ("GET", format!("/api/v1/users/search?query={query}"), false)
        }
        "friend-request" => ("POST", "/api/v1/friend-requests".into(), false),
        "friend-accept" => ("POST", keyed("friend-requests", "/accept")?, false),
        "friend-reject" => ("POST", keyed("friend-requests", "/reject")?, false),
        "friend-delete" => ("DELETE", keyed("friends", "")?, false),
        "heartbeat" => ("POST", "/api/v1/presence/heartbeat".into(), false),
        "group-create" => ("POST", "/api/v1/groups".into(), false),
        "group-update" => ("PATCH", keyed("groups", "")?, false),
        "group-members" => ("GET", keyed("groups", "/members")?, false),
        "group-invite" => ("POST", keyed("groups", "/invitations")?, false),
        "invitation-accept" => ("POST", keyed("invitations", "/accept")?, false),
        "group-leave" => ("POST", keyed("groups", "/leave")?, false),
        "group-dissolve" => ("POST", keyed("groups", "/dissolve")?, false),
        "audit" => ("GET", keyed("groups", "/audit-logs")?, false),
        "room-create" => ("POST", keyed("groups", "/rooms")?, false),
        "room-update" => ("PATCH", keyed("rooms", "")?, false),
        "room-open" => ("POST", keyed("rooms", "/open")?, false),
        "room-pause" => ("POST", keyed("rooms", "/pause")?, false),
        "room-delete" => ("DELETE", keyed("rooms", "")?, false),
        "activity-start" => ("POST", keyed("rooms", "/activities")?, false),
        "activity-end" => ("POST", keyed("activities", "/end")?, false),
        "activity-members" => ("GET", keyed("activities", "/members")?, false),
        "messages" => ("GET", keyed("groups", "/messages")?, false),
        "message-send" => ("POST", keyed("groups", "/messages")?, false),
        "message-delete" => ("DELETE", keyed("messages", "")?, false),
        _ => return Err("RESOURCE_NOT_ALLOWED".into()),
    };
    Ok((method, path, public))
}

#[tauri::command]
async fn activity_leave(state: State<'_, AppState>) -> Result<ClientSnapshot, String> {
    if let Some(activity) = state.core.snapshot().activity {
        state
            .client
            .leave(&state.access_token()?, &activity.activity_id)
            .await
            .map_err(error_code)?;
        state.voice.disconnect().await;
        state.core.clear_activity(&activity.activity_id);
    }
    Ok(state.core.snapshot())
}

#[tauri::command]
fn state_snapshot(state: State<'_, AppState>) -> ClientSnapshot {
    state.core.snapshot()
}

#[tauri::command]
async fn voice_connect(
    activity_id: String,
    state: State<'_, AppState>,
) -> Result<zhijie_voice::VoiceSnapshot, String> {
    let grant = match state
        .client
        .voice_grant(&state.access_token()?, &activity_id)
        .await
    {
        Ok(grant) => grant,
        Err(error) => {
            let code = error_code(error);
            state.voice.fail(code.clone());
            return Err(code);
        }
    };
    state
        .voice
        .connect(&grant.voice_url, &grant.voice_token)
        .await
}

#[tauri::command]
async fn voice_disconnect(
    state: State<'_, AppState>,
) -> Result<zhijie_voice::VoiceSnapshot, String> {
    Ok(state.voice.disconnect().await)
}

#[tauri::command]
fn voice_set_mute(muted: bool, state: State<'_, AppState>) -> zhijie_voice::VoiceSnapshot {
    state.voice.set_muted(muted)
}

#[tauri::command]
fn voice_set_receive(enabled: bool, state: State<'_, AppState>) -> zhijie_voice::VoiceSnapshot {
    state.voice.set_receive_enabled(enabled)
}

#[tauri::command]
fn voice_set_volume(
    volume: f32,
    member_id: Option<String>,
    state: State<'_, AppState>,
) -> Result<zhijie_voice::VoiceSnapshot, String> {
    match member_id {
        Some(member) => state.voice.set_member_volume(&member, volume),
        None => state.voice.set_master_volume(volume),
    }
}

#[tauri::command]
fn audio_select(
    index: usize,
    output: bool,
    state: State<'_, AppState>,
) -> Result<zhijie_voice::VoiceSnapshot, String> {
    if output {
        state.voice.select_output(index)
    } else {
        state.voice.select_input(index)
    }
}

#[tauri::command]
fn voice_snapshot(state: State<'_, AppState>) -> zhijie_voice::VoiceSnapshot {
    state.voice.snapshot()
}

fn error_code(error: ClientError) -> String {
    match error {
        ClientError::Api { code, .. } => code.clone(),
        ClientError::Transport(_) => "SERVICE_UNAVAILABLE".to_string(),
        ClientError::InvalidResponse(_) => "INVALID_SERVER_RESPONSE".to_string(),
    }
}

fn main() {
    tauri::Builder::default()
        .manage(AppState::new())
        .invoke_handler(tauri::generate_handler![
            session_login,
            product_request,
            activity_leave,
            group_list,
            room_list,
            activity_join,
            session_logout,
            state_snapshot,
            voice_connect,
            voice_disconnect,
            voice_set_mute,
            voice_set_receive,
            voice_set_volume,
            audio_select,
            voice_snapshot
        ])
        .run(tauri::generate_context!())
        .expect("failed to run 织界");
}

#[cfg(test)]
mod tests {
    use super::product_route;

    #[test]
    fn product_routes_reject_arbitrary_paths_and_path_injection() {
        assert!(product_route("https://example.com", None).is_err());
        for id in ["../ops", "a/b", "a?admin=true", "", "a%2fb"] {
            assert!(product_route("group-members", Some(id)).is_err());
        }
        assert_eq!(
            product_route("group-members", Some("group-123")).unwrap(),
            ("GET", "/api/v1/groups/group-123/members".into(), false)
        );
    }

    #[test]
    fn only_authentication_resources_allow_anonymous_access() {
        assert!(product_route("register", None).unwrap().2);
        assert!(!product_route("friends", None).unwrap().2);
        let (_, path, _) =
            product_route("friend-search", Some("a+b@example.test&admin=true")).unwrap();
        assert!(!path.contains('&'));
        assert!(!path.contains('@'));
    }
}
