use std::sync::Arc;
use std::time::Duration;

use zhijie_relay::control_plane::ControlPlaneValidator;
use zhijie_relay::quic::{QuicRelayServer, RelayClient};

// 由 Java 组合测试创建真实账号、活动和短票，并在 ready 后撤销接收方。
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
#[ignore = "requires live control plane, scoped tickets and coordinated revocation"]
async fn real_control_plane_tickets_and_revocation_over_quic() {
    let url = std::env::var("ZHIJIE_TEST_CONTROL_PLANE_URL").unwrap();
    let key = std::env::var("ZHIJIE_TEST_RELAY_MANAGEMENT_KEY").unwrap();
    let validator = tokio::task::spawn_blocking(move || ControlPlaneValidator::new(url, key))
        .await
        .unwrap()
        .unwrap();
    let cert = rcgen::generate_simple_self_signed(vec!["localhost".into()]).unwrap();
    let der = cert.cert.der().to_vec();
    let relay = QuicRelayServer::bind_der(
        "127.0.0.1:0".parse().unwrap(),
        der.clone(),
        cert.signing_key.serialize_der(),
        Arc::new(validator),
    )
    .unwrap();
    let a = RelayClient::connect(
        relay.local_addr(),
        "localhost",
        Some(der.clone()),
        &std::env::var("ZHIJIE_TEST_NETWORK_TICKET_A").unwrap(),
    )
    .await
    .unwrap();
    let b = RelayClient::connect(
        relay.local_addr(),
        "localhost",
        Some(der),
        &std::env::var("ZHIJIE_TEST_NETWORK_TICKET_B").unwrap(),
    )
    .await
    .unwrap();
    a.send(
        std::env::var("ZHIJIE_TEST_NETWORK_IP_B")
            .unwrap()
            .parse()
            .unwrap(),
        b"controlled-ciphertext",
    )
    .unwrap();
    let (source, payload) = tokio::time::timeout(Duration::from_secs(3), b.receive())
        .await
        .unwrap()
        .unwrap();
    assert_eq!(
        source.to_string(),
        std::env::var("ZHIJIE_TEST_NETWORK_IP_A").unwrap()
    );
    assert_eq!(payload, b"controlled-ciphertext");
    std::fs::write(std::env::var("ZHIJIE_TEST_RELAY_READY").unwrap(), b"ready").unwrap();
    assert!(tokio::time::timeout(Duration::from_secs(5), b.receive())
        .await
        .expect("server must close revoked receiver within five seconds")
        .is_err());
    drop(a);
    drop(b);
    relay.stop().await;
}
