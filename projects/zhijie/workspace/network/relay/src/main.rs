use std::sync::Arc;

use zhijie_relay::control_plane::ControlPlaneValidator;
use zhijie_relay::management::RelayManagementServer;
use zhijie_relay::quic::QuicRelayServer;

#[tokio::main]
async fn main() -> Result<(), String> {
    let base_url = std::env::var("ZHIJIE_CONTROL_PLANE_URL")
        .unwrap_or_else(|_| "http://127.0.0.1:8080".into());
    let management_key = std::env::var("ZHIJIE_RELAY_MANAGEMENT_KEY")
        .map_err(|_| "ZHIJIE_RELAY_MANAGEMENT_KEY is required".to_string())?;
    let revocation_key = std::env::var("ZHIJIE_RELAY_REVOCATION_KEY").ok();
    if revocation_key
        .as_ref()
        .is_some_and(|key| key == &management_key)
    {
        return Err("RELAY_MANAGEMENT_KEYS_MUST_DIFFER".into());
    }
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
    let management = if let Some(key) = revocation_key {
        let address = std::env::var("ZHIJIE_RELAY_MANAGEMENT_LISTEN")
            .unwrap_or_else(|_| "127.0.0.1:40001".into());
        Some(
            RelayManagementServer::bind(
                address
                    .parse()
                    .map_err(|_| "RELAY_MANAGEMENT_LISTEN_INVALID")?,
                key,
                relay.revocations(),
            )
            .await?,
        )
    } else {
        None
    };
    println!("Relay listening on {}", relay.local_addr());
    std::future::pending::<()>().await;
    if let Some(management) = management {
        management.stop().await;
    }
    relay.stop().await;
    Ok(())
}
