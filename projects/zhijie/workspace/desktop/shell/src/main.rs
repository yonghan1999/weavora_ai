#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::sync::Mutex;
use tauri::State;
use zhijie_core::{AuthTokens, Channel, ClientCore, ClientError, ClientSnapshot, ControlPlaneClient};

struct AppState {
    client: ControlPlaneClient,
    core: ClientCore,
    session: Mutex<Option<AuthTokens>>,
}

impl AppState {
    fn new() -> Self {
        let base_url = std::env::var("ZHIJIE_CONTROL_PLANE_URL")
            .unwrap_or_else(|_| "http://127.0.0.1:8080".to_string());
        Self {
            client: ControlPlaneClient::new(base_url).expect("valid HTTP client"),
            core: ClientCore::default(),
            session: Mutex::new(None),
        }
    }

    fn access_token(&self) -> Result<String, String> {
        self.session.lock().expect("session lock").as_ref()
            .map(|tokens| tokens.access_token.clone()).ok_or_else(|| "AUTH_REQUIRED".to_string())
    }
}

#[tauri::command]
async fn session_login(email: String, password: String, device_id: String, state: State<'_, AppState>)
        -> Result<ClientSnapshot, String> {
    let tokens = state.client.login(&email, &password, &device_id).await.map_err(error_code)?;
    state.core.session_changed(Some(tokens.user_id.clone()));
    *state.session.lock().expect("session lock") = Some(tokens);
    Ok(state.core.snapshot())
}

#[tauri::command]
async fn group_list(state: State<'_, AppState>) -> Result<Vec<serde_json::Value>, String> {
    state.client.groups(&state.access_token()?).await.map_err(error_code)
}

#[tauri::command]
async fn activity_join(room_id: String, device_id: String, password: Option<String>, operation_id: String,
                       state: State<'_, AppState>) -> Result<ClientSnapshot, String> {
    state.core.begin_activity(operation_id.clone(), room_id.clone(), device_id.clone());
    let result = state.client.join(&state.access_token()?, &room_id, &device_id, password.as_deref(), &operation_id)
        .await.map_err(|error| {
            state.core.channel_failed(&room_id, Channel::Voice, error_code(error.clone()));
            state.core.channel_failed(&room_id, Channel::Network, error_code(error.clone()));
            error_code(error)
        })?;
    state.core.apply_activity_result(&operation_id, result);
    Ok(state.core.snapshot())
}

#[tauri::command]
async fn session_logout(state: State<'_, AppState>) -> Result<ClientSnapshot, String> {
    if let Some(activity) = state.core.snapshot().activity {
        state.client.leave(&state.access_token()?, &activity.activity_id).await.map_err(error_code)?;
        state.core.clear_activity(&activity.activity_id);
    }
    *state.session.lock().expect("session lock") = None;
    state.core.session_changed(None);
    Ok(state.core.snapshot())
}

#[tauri::command]
fn state_snapshot(state: State<'_, AppState>) -> ClientSnapshot {
    state.core.snapshot()
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
        .invoke_handler(tauri::generate_handler![session_login, group_list, activity_join, session_logout, state_snapshot])
        .run(tauri::generate_context!())
        .expect("failed to run 织界");
}
