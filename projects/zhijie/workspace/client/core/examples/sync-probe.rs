use std::io::{BufRead, Write};
use zhijie_core::{sync::SyncConnection, ControlPlaneClient};

fn read() -> serde_json::Value {
    let mut line = String::new();
    std::io::stdin().lock().read_line(&mut line).unwrap();
    serde_json::from_str(&line).unwrap()
}
fn signal(value: &str) {
    println!("{value}");
    std::io::stdout().flush().unwrap();
}
async fn receive(socket: &mut SyncConnection, expected: &str, cursor: &mut u64) {
    tokio::time::timeout(std::time::Duration::from_secs(5), async {
        loop {
            if let Some(event) = socket.next().await.expect("fixed sync error code") {
                assert!(event.sequence > *cursor, "sequence must advance");
                *cursor = event.sequence;
                let payload: serde_json::Value = serde_json::from_str(&event.payload).unwrap();
                if payload["message_id"].as_str() == Some(expected) {
                    break;
                }
            }
        }
    })
    .await
    .expect("specific message deadline");
}

#[tokio::main]
async fn main() {
    // 真实集成夹具：凭据只从匿名 stdin 读取，不出现在参数或标准输出。
    let config = read();
    let client = ControlPlaneClient::new(config["base"].as_str().unwrap()).unwrap();
    let token = config["token"].as_str().unwrap();
    let mut cursor = client.sync_cursor(token).await.expect("cursor request");
    let mut socket = client.connect_sync(token, cursor).await.expect("connect");
    signal("SYNC_READY");
    let live = read();
    receive(&mut socket, live["message"].as_str().unwrap(), &mut cursor).await;
    drop(socket);
    signal("SYNC_OFFLINE");
    let missed = read();
    let mut socket = client.connect_sync(token, cursor).await.expect("reconnect");
    receive(
        &mut socket,
        missed["message"].as_str().unwrap(),
        &mut cursor,
    )
    .await;
    drop(socket);
    signal("SYNC_CATCHUP_OK");
    let rotated = read();
    let mut socket = client
        .connect_sync(rotated["token"].as_str().unwrap(), cursor)
        .await
        .expect("rotated connect");
    receive(
        &mut socket,
        rotated["message"].as_str().unwrap(),
        &mut cursor,
    )
    .await;
    socket.ping().await.expect("ping");
    signal("SYNC_ROTATED_OK");
}
