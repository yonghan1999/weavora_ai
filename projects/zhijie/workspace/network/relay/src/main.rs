use std::sync::Arc;

use zhijie_relay::control_plane::ControlPlaneValidator;
use zhijie_relay::quic::QuicRelayServer;

#[tokio::main]
async fn main() -> Result<(), String> {
    let base_url = std::env::var("ZHIJIE_CONTROL_PLANE_URL")
        .unwrap_or_else(|_| "http://127.0.0.1:8080".into());
    let management_key = std::env::var("ZHIJIE_RELAY_MANAGEMENT_KEY")
        .map_err(|_| "ZHIJIE_RELAY_MANAGEMENT_KEY is required".to_string())?;
    // blocking Client 必须在运行时外创建和销毁。
    let validator =
        tokio::task::spawn_blocking(move || ControlPlaneValidator::new(base_url, management_key))
            .await
            .map_err(|_| "RELAY_VALIDATOR_FAILED")??;
    let listen = std::env::var("ZHIJIE_RELAY_LISTEN").unwrap_or_else(|_| "127.0.0.1:40000".into());
    let certificate = std::fs::read(
        std::env::var("ZHIJIE_RELAY_CERT_DER").map_err(|_| "ZHIJIE_RELAY_CERT_DER is required")?,
    )
    .map_err(|_| "RELAY_CERT_READ_FAILED")?;
    let key = std::fs::read(
        std::env::var("ZHIJIE_RELAY_KEY_DER").map_err(|_| "ZHIJIE_RELAY_KEY_DER is required")?,
    )
    .map_err(|_| "RELAY_KEY_READ_FAILED")?;
    let relay = QuicRelayServer::bind(
        listen.parse().map_err(|_| "RELAY_LISTEN_INVALID")?,
        vec![rustls::pki_types::CertificateDer::from(certificate)],
        rustls::pki_types::PrivatePkcs8KeyDer::from(key).into(),
        Arc::new(validator),
    )?;
    println!("Relay listening on {}", relay.local_addr());
    std::future::pending::<()>().await;
    relay.stop().await;
    Ok(())
}
