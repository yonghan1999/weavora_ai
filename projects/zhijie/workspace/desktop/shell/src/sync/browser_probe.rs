use super::maintain_connection;
use crate::{error_code, product_route, session, AppState};
use serde_json::{json, Value};
use std::io::{BufRead, Write};
use std::sync::Arc;

fn emit(value: Value) {
    let mut output = std::io::stdout().lock();
    writeln!(output, "BROWSER_PROBE {value}").unwrap();
    output.flush().unwrap();
}

async fn invoke(state: &AppState, command: &str, args: &Value) -> Result<Value, String> {
    let string = |key: &str| args[key].as_str().ok_or("INVALID_PROBE_ARGUMENT");
    match command {
        "session_login" => {
            let snapshot = {
                let _operation = state.activity_operations.lock().await;
                session::login(
                    state,
                    string("email")?,
                    string("password")?,
                    string("deviceId")?,
                )
                .await?
            };
            Ok(json!(snapshot))
        }
        "state_snapshot" => Ok(json!(state.core.snapshot())),
        "group_list" => Ok(json!(state
            .client
            .groups(&state.access_token()?)
            .await
            .map_err(error_code)?)),
        "room_list" => Ok(json!(state
            .client
            .rooms(&state.access_token()?, string("groupId")?)
            .await
            .map_err(error_code)?)),
        "product_request" => {
            let (method, path, public) = product_route(
                string("resource")?,
                args["id"].as_str(),
                args["targetId"].as_str(),
            )?;
            let token = if public {
                None
            } else {
                Some(state.access_token()?)
            };
            state
                .client
                .product_request(token.as_deref(), method, &path, args["body"].clone())
                .await
                .map_err(error_code)
        }
        _ => Err("UNSUPPORTED_PROBE_COMMAND".into()),
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
#[ignore = "requires private stdin and isolated browser integration service"]
async fn product_page_client() {
    let port: u16 = std::env::var("ZHIJIE_TEST_GROUP_PORT")
        .unwrap()
        .parse()
        .unwrap();
    assert_ne!(port, 0);
    let client = zhijie_core::ControlPlaneClient::new(format!("http://127.0.0.1:{port}")).unwrap();
    let state = Arc::new(AppState::new(client));
    let session_state = Arc::clone(&state);
    let maintenance = tokio::spawn(async move { crate::session_loop(&session_state).await });
    let sync_state = Arc::clone(&state);
    let stream = tokio::spawn(async move {
        maintain_connection(&sync_state, |notice| emit(json!({"changed": notice}))).await;
    });
    for line in std::io::stdin().lock().lines() {
        let request: Value = serde_json::from_str(&line.unwrap()).expect("probe JSON");
        let id = request["id"].as_u64().unwrap();
        let result = invoke(
            &state,
            request["command"].as_str().unwrap(),
            &request["args"],
        )
        .await;
        emit(match result {
            Ok(value) => json!({"id": id, "value": value}),
            Err(error) => json!({"id": id, "error": error}),
        });
    }
    stream.abort();
    maintenance.abort();
}
