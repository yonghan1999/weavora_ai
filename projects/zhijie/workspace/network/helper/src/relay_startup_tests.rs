use super::*;

async fn pending_relay(
    authorized_for: Duration,
    ticket_for: Duration,
    include_peer: bool,
) -> (Fixture, UdpSocket) {
    let blackhole = UdpSocket::bind("127.0.0.1:0").await.unwrap();
    let certificate = rcgen::generate_simple_self_signed(vec!["localhost".into()]).unwrap();
    let config = RelayConfig {
        address: blackhole.local_addr().unwrap(),
        server_name: "localhost".into(),
        extra_ca: Some(certificate.cert.der().to_vec()),
    };
    let test = fixture_with_policy(
        authorized_for,
        ticket_for,
        include_peer,
        vec![NetworkPath::Relay],
        Ok(config),
    )
    .await;
    let mut datagram = [0; 2048];
    let (length, _) = tokio::time::timeout(
        Duration::from_millis(500),
        blackhole.recv_from(&mut datagram),
    )
    .await
    .expect("initial Relay QUIC connection must actually be pending")
    .unwrap();
    assert!(length >= 1200);
    (test, blackhole)
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn initial_relay_connection_does_not_delay_stop_and_tunnel_release() {
    let (mut test, _blackhole) =
        pending_relay(Duration::from_secs(3), Duration::from_secs(30), false).await;
    test.server.current.wait_pending().await;
    test.commands.send(Command::Stop).unwrap();
    tokio::time::timeout(Duration::from_millis(300), &mut test.pump.0)
        .await
        .expect("Stop must not wait for the initial Relay handshake")
        .unwrap()
        .unwrap();
    assert!(test.released.load(Ordering::SeqCst));
    println!("INITIAL_RELAY_PENDING_STOP_RELEASE_OK");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn initial_relay_connection_cannot_extend_the_authorization_snapshot() {
    let (mut test, _blackhole) =
        pending_relay(Duration::from_millis(500), Duration::from_secs(30), false).await;
    let result = tokio::time::timeout(Duration::from_millis(800), &mut test.pump.0)
        .await
        .expect("snapshot expiry must not wait for the initial Relay handshake")
        .unwrap();
    assert_eq!(result.unwrap_err(), "CONTROL_PLANE_UNAVAILABLE");
    assert!(test.released.load(Ordering::SeqCst));
    assert!(!test.server.current.release.load(Ordering::SeqCst));
    println!("INITIAL_RELAY_SNAPSHOT_DEADLINE_OK");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn initial_relay_connection_cannot_extend_the_ticket_expiry() {
    let (mut test, _blackhole) =
        pending_relay(Duration::from_secs(3), Duration::from_millis(500), false).await;
    let result = tokio::time::timeout(Duration::from_millis(800), &mut test.pump.0)
        .await
        .expect("ticket expiry must not wait for the initial Relay handshake")
        .unwrap();
    assert_eq!(result.unwrap_err(), "GRANT_EXPIRED");
    assert!(test.released.load(Ordering::SeqCst));
    println!("INITIAL_RELAY_TICKET_EXPIRY_OK");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn initial_relay_connection_does_not_delay_authority_denial() {
    let (mut test, _blackhole) =
        pending_relay(Duration::from_secs(3), Duration::from_secs(30), false).await;
    test.server.current.wait_pending().await;
    test.server.current.status.store(1, Ordering::SeqCst);
    test.server.current.release.store(true, Ordering::SeqCst);
    let result = tokio::time::timeout(Duration::from_millis(300), &mut test.pump.0)
        .await
        .expect("authority denial must cancel the initial Relay handshake")
        .unwrap();
    assert_eq!(result.unwrap_err(), "NETWORK_GRANT_INVALID");
    assert!(test.released.load(Ordering::SeqCst));
    println!("INITIAL_RELAY_AUTHORITY_DENIAL_OK");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn initial_relay_connection_does_not_prevent_refresh_or_allow_direct_bypass() {
    let (mut test, _blackhole) =
        pending_relay(Duration::from_secs(3), Duration::from_secs(30), true).await;
    test.server.current.wait_pending().await;
    test.server.current.release.store(true, Ordering::SeqCst);
    let mut packet = vec![0; 28];
    packet[0] = 0x45;
    packet[2..4].copy_from_slice(&28u16.to_be_bytes());
    packet[12..16].copy_from_slice(&[10, 231, 0, 1]);
    packet[16..20].copy_from_slice(&[10, 231, 0, 2]);
    test.input.send(packet).unwrap();
    let mut datagram = [0; 2048];
    assert!(
        tokio::time::timeout(
            Duration::from_millis(1200),
            test.remote.recv_from(&mut datagram)
        )
        .await
        .is_err(),
        "Relay-only policy must not emit direct UDP while connecting"
    );
    assert!(test.server.current.requests.load(Ordering::SeqCst) >= 2);
    assert!(!test.pump.0.is_finished());
    test.commands.send(Command::Stop).unwrap();
    tokio::time::timeout(Duration::from_millis(300), &mut test.pump.0)
        .await
        .unwrap()
        .unwrap()
        .unwrap();
    assert!(test.released.load(Ordering::SeqCst));
    println!("INITIAL_RELAY_REFRESH_AND_NO_DIRECT_BYPASS_OK");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn initial_relay_timeout_keeps_the_specific_failure_and_releases_tunnel() {
    let (mut test, _blackhole) =
        pending_relay(Duration::from_secs(3), Duration::from_secs(30), false).await;
    test.server.current.wait_pending().await;
    test.server.current.release.store(true, Ordering::SeqCst);
    let result = tokio::time::timeout(Duration::from_millis(3500), &mut test.pump.0)
        .await
        .expect("initial Relay handshake must keep the existing bounded timeout")
        .unwrap();
    assert_eq!(result.unwrap_err(), "RELAY_CONNECTION_TIMEOUT");
    assert!(test.released.load(Ordering::SeqCst));
    assert!(test.server.current.requests.load(Ordering::SeqCst) >= 3);
    println!("INITIAL_RELAY_TIMEOUT_RELEASE_OK");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn initial_relay_connection_stops_when_the_command_owner_disappears() {
    let (mut test, _blackhole) =
        pending_relay(Duration::from_secs(3), Duration::from_secs(30), false).await;
    drop(test.commands);
    tokio::time::timeout(Duration::from_millis(300), &mut test.pump.0)
        .await
        .expect("command channel closure must cancel the initial Relay handshake")
        .unwrap()
        .unwrap();
    assert!(test.released.load(Ordering::SeqCst));
    println!("INITIAL_RELAY_COMMAND_OWNER_EXIT_RELEASE_OK");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn missing_initial_relay_configuration_remains_an_explicit_failure() {
    let mut test = fixture_with_policy(
        Duration::from_secs(3),
        Duration::from_secs(30),
        false,
        vec![NetworkPath::Relay],
        Err("RELAY_PATH_UNAVAILABLE".into()),
    )
    .await;
    let result = tokio::time::timeout(Duration::from_millis(300), &mut test.pump.0)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(result.unwrap_err(), "RELAY_PATH_UNAVAILABLE");
    assert!(test.released.load(Ordering::SeqCst));
    assert!(test.input.send(vec![0; 28]).is_err());
    println!("INITIAL_RELAY_MISSING_CONFIGURATION_REJECTED_OK");
}
