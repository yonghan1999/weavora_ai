#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::sync::Mutex;
use tauri::Manager;
use tauri::State;
use zhijie_core::{AuthTokens, ClientCore, ClientError, ClientSnapshot, ControlPlaneClient};
mod network;
mod session;
mod sync;
#[cfg(test)]
mod test_support;
mod voice;

struct AppState {
    client: ControlPlaneClient,
    core: ClientCore,
    voice: zhijie_voice::VoiceManager,
    network: network::NetworkManager,
    session: Mutex<Option<AuthTokens>>,
    activity_operations: ActivityOperations,
}

#[derive(Default)]
struct ActivityOperations(tokio::sync::RwLock<()>);

impl ActivityOperations {
    async fn lock(&self) -> tokio::sync::RwLockWriteGuard<'_, ()> {
        self.0.write().await
    }

    async fn channel(&self) -> tokio::sync::RwLockReadGuard<'_, ()> {
        self.0.read().await
    }
}

impl AppState {
    fn new(client: ControlPlaneClient) -> Self {
        Self {
            client,
            core: ClientCore::default(),
            voice: zhijie_voice::VoiceManager::default(),
            network: network::NetworkManager::default(),
            session: Mutex::new(None),
            activity_operations: Default::default(),
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
    let _operation = state.activity_operations.lock().await;
    session::login(&state, &email, &password, &device_id).await
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
    let _operation = state.activity_operations.lock().await;
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
    if let Err(code) = state.network.stop().await {
        state.network.fail(code);
    }
    state
        .core
        .begin_activity(operation_id.clone(), room_id, device_id);
    state.core.apply_activity_result(&operation_id, result);
    Ok(state.core.snapshot())
}

#[tauri::command]
async fn session_logout(state: State<'_, AppState>) -> Result<ClientSnapshot, String> {
    let _operation = state.activity_operations.lock().await;
    Ok(session::logout(&state).await)
}

// 只允许固定产品资源；不向 WebView 暴露任意 URL、请求头或会话令牌。
#[tauri::command]
async fn product_request(
    resource: String,
    id: Option<String>,
    target_id: Option<String>,
    body: Option<serde_json::Value>,
    state: State<'_, AppState>,
) -> Result<serde_json::Value, String> {
    let (method, path, public) = product_route(&resource, id.as_deref(), target_id.as_deref())?;
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

fn product_route(
    resource: &str,
    id: Option<&str>,
    target_id: Option<&str>,
) -> Result<(&'static str, String, bool), String> {
    let validate_id = |value: Option<&str>| -> Result<String, String> {
        let value = value.ok_or("INVALID_RESOURCE_ID")?;
        if value.is_empty()
            || value.len() > 128
            || !value
                .bytes()
                .all(|c| c.is_ascii_alphanumeric() || c == b'-')
        {
            return Err("INVALID_RESOURCE_ID".into());
        }
        Ok(value.to_owned())
    };
    let valid_id = || validate_id(id);
    let keyed = |prefix: &str, suffix: &str| -> Result<String, String> {
        Ok(format!("/api/v1/{prefix}/{}{suffix}", valid_id()?))
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
        "friend-requests" => {
            let path = match id {
                Some(_) => format!("/api/v1/friend-requests?beforeId={}", valid_id()?),
                None => "/api/v1/friend-requests".into(),
            };
            ("GET", path, false)
        }
        "friend-accept" => ("POST", keyed("friend-requests", "/accept")?, false),
        "friend-reject" => ("POST", keyed("friend-requests", "/reject")?, false),
        "friend-delete" => ("DELETE", keyed("friends", "")?, false),
        "heartbeat" => ("POST", "/api/v1/presence/heartbeat".into(), false),
        "group-create" => ("POST", "/api/v1/groups".into(), false),
        "group-update" => ("PATCH", keyed("groups", "")?, false),
        "group-members" => ("GET", keyed("groups", "/members")?, false),
        "group-member-role" | "group-member-remove" => (
            if resource == "group-member-role" {
                "PATCH"
            } else {
                "DELETE"
            },
            format!(
                "/api/v1/groups/{}/members/{}",
                valid_id()?,
                validate_id(target_id)?
            ),
            false,
        ),
        "group-invite" => ("POST", keyed("groups", "/invitations")?, false),
        "invitations" => (
            "GET",
            match id {
                Some(_) => format!("/api/v1/invitations?beforeId={}", valid_id()?),
                None => "/api/v1/invitations".into(),
            },
            false,
        ),
        "group-invitations" => (
            "GET",
            format!(
                "{}{}",
                keyed("groups", "/invitations")?,
                match target_id {
                    Some(_) => format!("?beforeId={}", validate_id(target_id)?),
                    None => String::new(),
                }
            ),
            false,
        ),
        "invitation-revoke" => (
            "DELETE",
            format!(
                "/api/v1/groups/{}/invitations/{}",
                valid_id()?,
                validate_id(target_id)?
            ),
            false,
        ),
        "owner-transfers" => ("GET", keyed("groups", "/owner-transfers")?, false),
        "owner-transfer-create" => ("POST", keyed("groups", "/owner-transfers")?, false),
        "owner-transfer-accept" => ("POST", keyed("owner-transfers", "/accept")?, false),
        "owner-transfer-cancel" => ("POST", keyed("owner-transfers", "/cancel")?, false),
        "owner-recovery" => ("POST", keyed("groups", "/owner-recovery-requests")?, false),
        "invitation-accept" => ("POST", keyed("invitations", "/accept")?, false),
        "group-leave" => ("POST", keyed("groups", "/leave")?, false),
        "group-dissolve" => ("POST", keyed("groups", "/dissolve")?, false),
        "audit" => (
            "GET",
            format!(
                "{}{}",
                keyed("groups", "/audit-logs")?,
                match target_id {
                    Some(_) => format!("?beforeId={}", validate_id(target_id)?),
                    None => String::new(),
                }
            ),
            false,
        ),
        "room-create" => ("POST", keyed("groups", "/rooms")?, false),
        "room-update" => ("PATCH", keyed("rooms", "")?, false),
        "room-owner" => ("POST", keyed("rooms", "/owner")?, false),
        "activity-kick" => (
            "POST",
            format!(
                "/api/v1/activities/{}/members/{}/kick",
                valid_id()?,
                validate_id(target_id)?
            ),
            false,
        ),
        "room-open" => ("POST", keyed("rooms", "/open")?, false),
        "room-pause" => ("POST", keyed("rooms", "/pause")?, false),
        "room-delete" => ("DELETE", keyed("rooms", "")?, false),
        "activity-start" => ("POST", keyed("rooms", "/activities")?, false),
        "activity-end" => ("POST", keyed("activities", "/end")?, false),
        "activity-members" => ("GET", keyed("activities", "/members")?, false),
        "messages" => {
            let mut path = keyed("groups", "/messages")?;
            if target_id.is_some() {
                path.push_str(&format!("?beforeId={}", validate_id(target_id)?));
            }
            ("GET", path, false)
        }
        "message-send" => ("POST", keyed("groups", "/messages")?, false),
        "message-delete" => ("DELETE", keyed("messages", "")?, false),
        _ => return Err("RESOURCE_NOT_ALLOWED".into()),
    };
    Ok((method, path, public))
}

#[tauri::command]
async fn activity_leave(state: State<'_, AppState>) -> Result<ClientSnapshot, String> {
    let _operation = state.activity_operations.lock().await;
    if let Some(activity) = state.core.snapshot().activity {
        state
            .client
            .leave(&state.access_token()?, &activity.activity_id)
            .await
            .map_err(error_code)?;
        state.voice.disconnect().await;
        if let Err(code) = state.network.stop().await {
            state.network.fail(code);
        }
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
    lease_id: String,
    state: State<'_, AppState>,
) -> Result<zhijie_voice::VoiceSnapshot, String> {
    voice::connect(&state, &activity_id, &lease_id).await
}

#[tauri::command]
async fn voice_disconnect(
    state: State<'_, AppState>,
) -> Result<zhijie_voice::VoiceSnapshot, String> {
    Ok(voice::disconnect(&state).await)
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
fn audio_devices(state: State<'_, AppState>) -> serde_json::Value {
    serde_json::json!({
        "inputs": state.voice.input_devices(),
        "outputs": state.voice.output_devices(),
    })
}

#[tauri::command]
async fn audio_test_output(state: State<'_, AppState>) -> Result<(), String> {
    state.voice.test_output().await
}

#[tauri::command]
async fn audio_test_microphone(
    state: State<'_, AppState>,
) -> Result<zhijie_voice::MicrophoneTestResult, String> {
    state.voice.test_microphone().await
}

#[tauri::command]
fn audio_select(
    device_id: String,
    output: bool,
    state: State<'_, AppState>,
) -> Result<zhijie_voice::VoiceSnapshot, String> {
    if output {
        state.voice.select_output(&device_id)
    } else {
        state.voice.select_input(&device_id)
    }
}

#[tauri::command]
fn voice_snapshot(state: State<'_, AppState>) -> zhijie_voice::VoiceSnapshot {
    state.voice.snapshot()
}

#[tauri::command]
async fn network_connect(
    activity_id: String,
    lease_id: String,
    state: State<'_, AppState>,
) -> Result<serde_json::Value, String> {
    let _network = state.network.operations.lock().await;
    let _activity = state.activity_operations.channel().await;
    let activity = state
        .core
        .snapshot()
        .activity
        .filter(|activity| activity.activity_id == activity_id && activity.lease_id == lease_id)
        .ok_or("CURRENT_GRANT_REQUIRED")?;
    match state
        .network
        .connect(&state.client, &state.access_token()?, &activity)
        .await
    {
        Ok(()) => state.network.poll().await,
        Err(code) => {
            state.network.fail(code.clone());
            Err(code)
        }
    }
}

#[tauri::command]
async fn network_snapshot(state: State<'_, AppState>) -> Result<serde_json::Value, String> {
    let _network = state.network.operations.lock().await;
    let _activity = state.activity_operations.channel().await;
    match state.network.poll().await {
        Ok(snapshot) => Ok(snapshot),
        Err(code) => {
            state.network.fail(code);
            Ok(state.network.snapshot())
        }
    }
}

fn error_code(error: ClientError) -> String {
    match error {
        ClientError::Api { code, .. } => code.clone(),
        ClientError::Transport(_) => "SERVICE_UNAVAILABLE".to_string(),
        ClientError::InvalidResponse(_) => "INVALID_SERVER_RESPONSE".to_string(),
    }
}

async fn maintain_session(app: tauri::AppHandle) {
    session_loop(&app.state::<AppState>()).await;
}

async fn session_loop(state: &AppState) {
    let mut interval = tokio::time::interval(std::time::Duration::from_secs(15));
    interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    let mut renewed = None;
    loop {
        interval.tick().await;
        let _operation = state.activity_operations.lock().await;
        session::tick(state, &mut renewed).await;
    }
}

fn main() {
    let base = match std::env::var("ZHIJIE_CONTROL_PLANE_URL") {
        Ok(value) => value,
        Err(std::env::VarError::NotPresent) => "http://127.0.0.1:8080".to_string(),
        Err(std::env::VarError::NotUnicode(_)) => String::new(),
    };
    let client = ControlPlaneClient::new(base).unwrap_or_else(|_| {
        eprintln!(
            "无法初始化控制面连接，请检查 ZHIJIE_CONTROL_PLANE_URL。远程服务须使用 HTTPS 源地址。"
        );
        std::process::exit(1);
    });
    tauri::Builder::default()
        .setup(|app| {
            #[cfg(target_os = "windows")]
            {
                // Windows 无装饰窗口的原生阴影会附带强调色细边；关闭它，保留框架的拖动与缩放。
                let window = app
                    .get_webview_window("main")
                    .ok_or("main window missing")?;
                window.set_shadow(false)?;
            }
            tauri::async_runtime::spawn(maintain_session(app.handle().clone()));
            tauri::async_runtime::spawn(sync::maintain(app.handle().clone()));
            Ok(())
        })
        .manage(AppState::new(client))
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
            audio_devices,
            audio_test_microphone,
            audio_test_output,
            voice_snapshot,
            network_connect,
            network_snapshot
        ])
        .run(tauri::generate_context!())
        .expect("failed to run 织界");
}

#[cfg(test)]
mod tests {
    use super::product_route;

    #[test]
    fn group_message_history_uses_only_valid_group_and_cursor_ids() {
        assert_eq!(
            product_route("messages", Some("group-1"), Some("message-9")).unwrap(),
            ("GET", "/api/v1/groups/group-1/messages?beforeId=message-9".into(), false)
        );
        assert_eq!(product_route("messages", Some("group-1"), None).unwrap().1, "/api/v1/groups/group-1/messages");
        for invalid in ["", "a/b", "a&limit=100", "a%2fb"] {
            assert!(product_route("messages", Some("group-1"), Some(invalid)).is_err());
            assert!(product_route("messages", Some(invalid), Some("message-9")).is_err());
        }
    }

    #[test]
    fn product_routes_reject_arbitrary_paths_and_path_injection() {
        assert!(product_route("https://example.com", None, None).is_err());
        for id in ["../ops", "a/b", "a?admin=true", "", "a%2fb"] {
            assert!(product_route("group-members", Some(id), None).is_err());
        }
        assert_eq!(
            product_route("group-members", Some("group-123"), None).unwrap(),
            ("GET", "/api/v1/groups/group-123/members".into(), false)
        );
    }

    #[test]
    fn member_mutations_validate_both_resource_ids() {
        for resource in ["group-member-role", "group-member-remove"] {
            for id in [
                None,
                Some(""),
                Some("a/b"),
                Some("../ops"),
                Some("a?x=1"),
                Some("a%2fb"),
            ] {
                assert!(product_route(resource, Some("group-123"), id).is_err());
                assert!(product_route(resource, id, Some("user-123")).is_err());
            }
            let route = product_route(resource, Some("group-123"), Some("user-123")).unwrap();
            assert_eq!(
                route.0,
                if resource == "group-member-role" {
                    "PATCH"
                } else {
                    "DELETE"
                }
            );
            assert_eq!(route.1, "/api/v1/groups/group-123/members/user-123");
            assert!(!route.2);
        }
    }

    #[test]
    fn governance_routes_are_fixed_authenticated_and_validate_ids() {
        for (resource, method, prefix, suffix) in [
            ("owner-transfers", "GET", "groups", "/owner-transfers"),
            (
                "owner-transfer-create",
                "POST",
                "groups",
                "/owner-transfers",
            ),
            (
                "owner-transfer-accept",
                "POST",
                "owner-transfers",
                "/accept",
            ),
            (
                "owner-transfer-cancel",
                "POST",
                "owner-transfers",
                "/cancel",
            ),
            (
                "owner-recovery",
                "POST",
                "groups",
                "/owner-recovery-requests",
            ),
        ] {
            assert_eq!(
                product_route(resource, Some("id-123"), None).unwrap(),
                (method, format!("/api/v1/{prefix}/id-123{suffix}"), false)
            );
            for id in [None, Some("a/b"), Some("a?x=1"), Some("")] {
                assert!(product_route(resource, id, None).is_err());
            }
        }
    }

    #[test]
    fn invitation_routes_keep_recipient_group_and_cursor_ids_separate() {
        assert_eq!(
            product_route("invitations", None, None).unwrap(),
            ("GET", "/api/v1/invitations".into(), false)
        );
        assert_eq!(
            product_route("invitations", Some("cursor-1"), None)
                .unwrap()
                .1,
            "/api/v1/invitations?beforeId=cursor-1"
        );
        assert_eq!(
            product_route("group-invitations", Some("group-1"), Some("cursor-1")).unwrap(),
            (
                "GET",
                "/api/v1/groups/group-1/invitations?beforeId=cursor-1".into(),
                false
            )
        );
        assert_eq!(
            product_route("invitation-revoke", Some("group-1"), Some("invite-1")).unwrap(),
            (
                "DELETE",
                "/api/v1/groups/group-1/invitations/invite-1".into(),
                false
            )
        );
        for resource in ["group-invitations", "invitation-revoke"] {
            assert!(product_route(resource, Some("a/b"), Some("invite-1")).is_err());
            assert!(product_route(resource, Some("group-1"), Some("a&x=1")).is_err());
        }
        assert!(product_route("invitation-revoke", Some("group-1"), None).is_err());
        assert!(product_route("invitations", Some("a&x=1"), None).is_err());
    }

    #[test]
    fn room_governance_routes_are_authenticated_and_generation_is_a_body_field() {
        assert_eq!(
            product_route("room-owner", Some("room-1"), None).unwrap(),
            ("POST", "/api/v1/rooms/room-1/owner".into(), false)
        );
        assert_eq!(
            product_route("activity-kick", Some("activity-1"), Some("user-1")).unwrap(),
            (
                "POST",
                "/api/v1/activities/activity-1/members/user-1/kick".into(),
                false
            )
        );
        assert!(product_route("activity-kick", Some("activity-1"), None).is_err());
        assert!(product_route("activity-kick", Some("a/b"), Some("user-1")).is_err());
        assert!(product_route("activity-kick", Some("activity-1"), Some("u?x=1")).is_err());
        assert!(product_route("room-owner", Some("r/owner"), None).is_err());
    }

    #[test]
    fn audit_history_separates_group_and_cursor_and_allows_only_read() {
        assert_eq!(
            product_route("audit", Some("group-1"), Some("cursor-1")).unwrap(),
            (
                "GET",
                "/api/v1/groups/group-1/audit-logs?beforeId=cursor-1".into(),
                false
            )
        );
        assert!(product_route("audit", Some("a/b"), Some("cursor-1")).is_err());
        assert!(product_route("audit", Some("group-1"), Some("a&limit=1")).is_err());
        for resource in [
            "audit-create",
            "audit-update",
            "audit-delete",
            "audit-clear",
        ] {
            assert!(product_route(resource, Some("group-1"), None).is_err());
        }
    }

    #[test]
    fn only_authentication_resources_allow_anonymous_access() {
        assert!(product_route("register", None, None).unwrap().2);
        assert!(!product_route("friends", None, None).unwrap().2);
        assert_eq!(
            product_route("friend-requests", None, None).unwrap(),
            ("GET", "/api/v1/friend-requests".into(), false)
        );
        assert_eq!(
            product_route("friend-requests", Some("request-123"), None).unwrap(),
            (
                "GET",
                "/api/v1/friend-requests?beforeId=request-123".into(),
                false
            )
        );
        assert!(product_route("friend-requests", Some("a&userId=other"), None).is_err());
        let (_, path, _) =
            product_route("friend-search", Some("a+b@example.test&admin=true"), None).unwrap();
        assert!(!path.contains('&'));
        assert!(!path.contains('@'));
    }
}
