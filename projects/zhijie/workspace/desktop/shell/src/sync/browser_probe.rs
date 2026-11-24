use super::maintain_connection;
use crate::AppState;
use serde_json::{json, Value};
use std::io::{BufRead, Write};
use tauri::Manager;

fn emit(value: Value) {
    let mut output = std::io::stdout().lock();
    writeln!(output, "BROWSER_PROBE {value}").unwrap();
    output.flush().unwrap();
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
    let app = tauri::test::mock_builder()
        .manage(AppState::new(client))
        .manage(crate::invitation::Inbox::default())
        .invoke_handler(crate::product_commands())
        .build(tauri::test::mock_context(tauri::test::noop_assets()))
        .unwrap();
    let window = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
        .build()
        .unwrap();
    let session_app = app.handle().clone();
    let maintenance =
        tokio::spawn(async move { crate::session_loop(&session_app.state::<AppState>()).await });
    let sync_app = app.handle().clone();
    let stream = tokio::spawn(async move {
        maintain_connection(&sync_app.state::<AppState>(), |notice| {
            emit(json!({"changed": notice}))
        })
        .await;
    });
    let mut calls = tokio::task::JoinSet::new();
    for line in std::io::stdin().lock().lines() {
        let request: Value = serde_json::from_str(&line.unwrap()).expect("probe JSON");
        if let Some(url) = request["testOpenRoomUrl"].as_str() {
            assert!(app.state::<crate::invitation::Inbox>().receive(std::iter::once(url.to_owned())), "invalid invitation activation");
            emit(json!({"invitationReady":true}));
            continue;
        }
        let id = request["id"].as_u64().unwrap();
        let window = window.clone();
        while let Some(done) = calls.try_join_next() {
            done.unwrap();
        }
        calls.spawn_blocking(move || {
            let response = tauri::test::get_ipc_response(
                &window,
                tauri::webview::InvokeRequest {
                    cmd: request["command"].as_str().unwrap().into(),
                    body: tauri::ipc::InvokeBody::Json(request["args"].clone()),
                    callback: tauri::ipc::CallbackFn(0),
                    error: tauri::ipc::CallbackFn(1),
                    url: if cfg!(windows) {
                        "http://tauri.localhost"
                    } else {
                        "tauri://localhost"
                    }
                    .parse()
                    .unwrap(),
                    headers: Default::default(),
                    invoke_key: tauri::test::INVOKE_KEY.into(),
                },
            );
            emit(match response {
                Ok(body) => json!({"id": id, "value": body.deserialize::<Value>().unwrap()}),
                Err(error) => json!({"id": id, "error": error}),
            });
        });
    }
    while let Some(done) = calls.join_next().await {
        done.unwrap();
    }
    stream.abort();
    maintenance.abort();
}
