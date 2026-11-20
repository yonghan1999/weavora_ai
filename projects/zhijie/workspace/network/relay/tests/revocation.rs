use std::io::{BufRead, Write};
use std::sync::Arc;
use std::time::Duration;

use zhijie_relay::control_plane::ControlPlaneValidator;
use zhijie_relay::management::RelayManagementServer;
use zhijie_relay::quic::{QuicRelayServer, RelayClient};

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
#[ignore = "requires parent-controlled real database, outbox and scoped tickets"]
async fn real_outbox_revokes_quic_and_late_old_event_preserves_new_generation() {
    let mut input = std::io::stdin().lock();
    let mut line = String::new();
    input.read_line(&mut line).unwrap();
    let configuration: serde_json::Value = serde_json::from_str(&line).unwrap();
    drop(input);
    let url = configuration["controlPlaneUrl"]
        .as_str()
        .unwrap()
        .to_owned();
    let validation_key = configuration["validationKey"].as_str().unwrap().to_owned();
    let revocation_key = configuration["revocationKey"].as_str().unwrap().to_owned();
    let validator =
        tokio::task::spawn_blocking(move || ControlPlaneValidator::new(url, validation_key))
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
    let management = RelayManagementServer::bind(
        configuration["managementAddress"]
            .as_str()
            .unwrap()
            .parse()
            .unwrap(),
        revocation_key,
        relay.revocations(),
    )
    .await
    .unwrap();
    let ticket_a = configuration["ticketA"].as_str().unwrap();
    let ticket_b = configuration["ticketB"].as_str().unwrap();
    let a = RelayClient::connect(relay.local_addr(), "localhost", Some(der.clone()), ticket_a)
        .await
        .unwrap();
    let b = RelayClient::connect(relay.local_addr(), "localhost", Some(der.clone()), ticket_b)
        .await
        .unwrap();
    let ip_a = configuration["ipA"].as_str().unwrap().parse().unwrap();
    let ip_b = configuration["ipB"].as_str().unwrap().parse().unwrap();
    a.send(ip_b, b"before-revocation").unwrap();
    assert_eq!(
        tokio::time::timeout(Duration::from_secs(2), b.receive())
            .await
            .unwrap()
            .unwrap()
            .1,
        b"before-revocation"
    );
    println!("RELAY_OUTBOX_READY");
    std::io::stdout().flush().unwrap();
    assert!(tokio::time::timeout(Duration::from_secs(5), b.receive())
        .await
        .unwrap()
        .is_err());
    assert!(
        RelayClient::connect(relay.local_addr(), "localhost", Some(der.clone()), ticket_b)
            .await
            .is_err()
    );
    println!("RELAY_OUTBOX_REVOKED");
    std::io::stdout().flush().unwrap();
    let next = tokio::task::spawn_blocking(|| {
        let mut line = String::new();
        std::io::stdin().read_line(&mut line).unwrap();
        serde_json::from_str::<serde_json::Value>(&line).unwrap()
    })
    .await
    .unwrap();
    let new_b = RelayClient::connect(
        relay.local_addr(),
        "localhost",
        Some(der),
        next["ticketB"].as_str().unwrap(),
    )
    .await
    .unwrap();
    a.send(ip_b, b"new-generation-before-late-event").unwrap();
    assert_eq!(
        tokio::time::timeout(Duration::from_secs(2), new_b.receive())
            .await
            .unwrap()
            .unwrap()
            .1,
        b"new-generation-before-late-event"
    );
    println!("RELAY_OUTBOX_NEW_GENERATION_READY");
    std::io::stdout().flush().unwrap();
    tokio::task::spawn_blocking(|| {
        let mut line = String::new();
        std::io::stdin().read_line(&mut line).unwrap();
        assert_eq!(line.trim(), "LATE_EVENT_APPLIED");
    })
    .await
    .unwrap();
    new_b
        .send(ip_a, b"new-generation-after-late-event")
        .unwrap();
    assert_eq!(
        tokio::time::timeout(Duration::from_secs(2), a.receive())
            .await
            .unwrap()
            .unwrap()
            .1,
        b"new-generation-after-late-event"
    );
    println!("RELAY_OUTBOX_LATE_EVENT_ISOLATED_OK");
    std::io::stdout().flush().unwrap();
    drop((a, b, new_b));
    management.stop().await;
    relay.stop().await;
}
