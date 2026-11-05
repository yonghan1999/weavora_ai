use std::sync::Arc;

use zhijie_relay::control_plane::ControlPlaneValidator;
use zhijie_relay::server::RelayServer;

fn main() -> Result<(), String> {
    let base_url = std::env::var("ZHIJIE_CONTROL_PLANE_URL")
        .unwrap_or_else(|_| "http://127.0.0.1:8080".into());
    let management_key = std::env::var("ZHIJIE_RELAY_MANAGEMENT_KEY")
        .map_err(|_| "ZHIJIE_RELAY_MANAGEMENT_KEY is required".to_string())?;
    let validator = Arc::new(ControlPlaneValidator::new(base_url, management_key)?);
    let listen = std::env::var("ZHIJIE_RELAY_LISTEN").unwrap_or_else(|_| "127.0.0.1:40000".into());
    let relay = RelayServer::bind(&listen, validator)?;
    relay.start();
    println!("Relay listening on {}", relay.local_addr());
    loop {
        std::thread::park();
    }
}
