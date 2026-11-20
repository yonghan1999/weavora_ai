use super::*;
use boringtun::x25519::PublicKey;
use std::io::{Read, Write};

#[path = "relay_startup_tests.rs"]
mod relay_startup;

struct InputTunnel {
    input: tokio::sync::Mutex<tokio::sync::mpsc::UnboundedReceiver<Vec<u8>>>,
    released: Arc<AtomicBool>,
}

impl TunnelDevice for InputTunnel {
    async fn recv(&self, packet: &mut [u8]) -> std::io::Result<usize> {
        let bytes = self.input.lock().await.recv().await.unwrap();
        packet[..bytes.len()].copy_from_slice(&bytes);
        Ok(bytes.len())
    }

    async fn send(&self, _: &[u8]) -> std::io::Result<usize> {
        panic!("outbound test must not deliver plaintext")
    }
}

impl Drop for InputTunnel {
    fn drop(&mut self) {
        self.released.store(true, Ordering::SeqCst);
    }
}

#[derive(Default)]
struct ResponseControl {
    entered: AtomicBool,
    release: AtomicBool,
    status: AtomicU8,
    requests: std::sync::atomic::AtomicUsize,
}

impl ResponseControl {
    async fn wait_pending(&self) {
        tokio::time::timeout(Duration::from_secs(2), async {
            while !self.entered.load(Ordering::SeqCst) {
                tokio::time::sleep(Duration::from_millis(2)).await;
            }
        })
        .await
        .expect("HTTP validation must actually be pending");
    }
}

struct PausedAuthority {
    url: String,
    running: Arc<AtomicBool>,
    current: Arc<ResponseControl>,
    renewal: Arc<ResponseControl>,
    worker: Option<JoinHandle<()>>,
}

impl PausedAuthority {
    fn start(request: &NetworkStart, peers: &[NetworkPeer]) -> Self {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        listener.set_nonblocking(true).unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let running = Arc::new(AtomicBool::new(true));
        let keep_running = running.clone();
        let current = Arc::new(ResponseControl::default());
        let current_response = current.clone();
        let renewal = Arc::new(ResponseControl::default());
        let renewal_response = renewal.clone();
        let ticket = request.ticket.clone();
        let body = serde_json::json!({
            "activityId": request.activity_id, "activityGeneration": request.activity_generation,
            "memberGeneration": request.member_generation, "deviceId": request.device_id,
            "leaseId": request.lease_id, "policyRevision": request.policy_revision,
            "allowedPaths": request.allowed_paths, "virtualIpv4": request.virtual_ipv4,
            "activityCidr": request.activity_cidr, "expiresAt": request.expires_at,
            "publicKey": base64::engine::general_purpose::STANDARD.encode(PublicKey::from(&StaticSecret::from([1;32])).to_bytes()),
            "peers": peers,
        });
        let mut renewed_body = body.clone();
        renewed_body["expiresAt"] = serde_json::json!(renewed_request(request).expires_at);
        let worker = std::thread::spawn(move || {
            let mut handlers = Vec::new();
            while keep_running.load(Ordering::SeqCst) {
                let (mut stream, _) = match listener.accept() {
                    Ok(connection) => connection,
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                        std::thread::sleep(Duration::from_millis(2));
                        continue;
                    }
                    Err(_) => break,
                };
                stream.set_nonblocking(false).unwrap();
                stream
                    .set_read_timeout(Some(Duration::from_secs(1)))
                    .unwrap();
                stream
                    .set_write_timeout(Some(Duration::from_secs(1)))
                    .unwrap();
                let mut bytes = Vec::new();
                let mut buffer = [0; 2048];
                let incoming = loop {
                    let count = stream.read(&mut buffer).unwrap();
                    assert!(count > 0);
                    bytes.extend_from_slice(&buffer[..count]);
                    assert!(bytes.len() <= 8192);
                    if let Some(end) = bytes.windows(4).position(|part| part == b"\r\n\r\n") {
                        let length = String::from_utf8_lossy(&bytes[..end])
                            .lines()
                            .find_map(|line| {
                                line.to_ascii_lowercase()
                                    .strip_prefix("content-length:")?
                                    .trim()
                                    .parse::<usize>()
                                    .ok()
                            })
                            .unwrap();
                        if bytes.len() >= end + 4 + length {
                            break serde_json::from_slice::<serde_json::Value>(
                                &bytes[end + 4..end + 4 + length],
                            )
                            .unwrap();
                        }
                    }
                };
                let (control, body) = if incoming["ticket"] == ticket {
                    (current_response.clone(), body.to_string())
                } else {
                    assert_eq!(incoming["ticket"], "new-ticket");
                    (renewal_response.clone(), renewed_body.to_string())
                };
                control.requests.fetch_add(1, Ordering::SeqCst);
                control.entered.store(true, Ordering::SeqCst);
                let running = keep_running.clone();
                handlers.push(std::thread::spawn(move || {
                    let started = std::time::Instant::now();
                    while running.load(Ordering::SeqCst) && !control.release.load(Ordering::SeqCst) && started.elapsed() < Duration::from_secs(3) {
                        std::thread::sleep(Duration::from_millis(2));
                    }
                    let status = if control.status.load(Ordering::SeqCst) == 0 {"200 OK"} else {"403 Forbidden"};
                    let _ = write!(stream, "HTTP/1.1 {status}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len());
                }));
            }
            for handler in handlers {
                handler.join().unwrap();
            }
        });
        Self {
            url,
            running,
            current,
            renewal,
            worker: Some(worker),
        }
    }
}

impl Drop for PausedAuthority {
    fn drop(&mut self) {
        self.running.store(false, Ordering::SeqCst);
        self.worker.take().unwrap().join().unwrap();
    }
}

struct Pump(tokio::task::JoinHandle<Result<(), String>>);

impl Drop for Pump {
    fn drop(&mut self) {
        self.0.abort();
    }
}

struct Fixture {
    server: PausedAuthority,
    pump: Pump,
    input: tokio::sync::mpsc::UnboundedSender<Vec<u8>>,
    commands: tokio::sync::mpsc::UnboundedSender<Command>,
    remote: UdpSocket,
    released: Arc<AtomicBool>,
    request: NetworkStart,
}

async fn fixture() -> Fixture {
    fixture_with_deadline(Duration::from_secs(3), true).await
}

async fn fixture_with_deadline(valid_for: Duration, include_peer: bool) -> Fixture {
    fixture_with_policy(
        valid_for,
        Duration::from_secs(30),
        include_peer,
        vec![NetworkPath::Direct],
        Err("RELAY_PATH_UNAVAILABLE".into()),
    )
    .await
}

async fn fixture_with_policy(
    valid_for: Duration,
    ticket_for: Duration,
    include_peer: bool,
    allowed_paths: Vec<NetworkPath>,
    relay_config: Result<RelayConfig, String>,
) -> Fixture {
    let expiry = OffsetDateTime::now_utc() + time::Duration::try_from(ticket_for).unwrap();
    let request = NetworkStart {
        protocol_version: 1,
        request_id: "responsiveness".into(),
        ticket: "test-ticket".into(),
        activity_id: "responsiveness".into(),
        activity_generation: 1,
        member_generation: 1,
        device_id: "device".into(),
        lease_id: "lease".into(),
        policy_revision: 1,
        allowed_paths,
        private_key: base64::engine::general_purpose::STANDARD.encode([1; 32]),
        virtual_ipv4: "10.231.0.1".into(),
        activity_cidr: "10.231.0.0/27".into(),
        expires_at: expiry
            .format(&time::format_description::well_known::Rfc3339)
            .unwrap(),
    };
    let remote = UdpSocket::bind("127.0.0.1:0").await.unwrap();
    let peer = NetworkPeer {
        user_id: "remote".into(),
        member_generation: 1,
        lease_id: "remote-lease".into(),
        expires_at: request.expires_at.clone(),
        virtual_ipv4: "10.231.0.2".into(),
        public_key: base64::engine::general_purpose::STANDARD
            .encode(PublicKey::from(&StaticSecret::from([2; 32])).to_bytes()),
        endpoint: Some(remote.local_addr().unwrap().to_string()),
    };
    let peers = if include_peer { vec![peer] } else { Vec::new() };
    let server = PausedAuthority::start(&request, &peers);
    let mut router = TunnelRouter::new(
        "10.231.0.1".parse().unwrap(),
        "10.231.0.0/27".parse().unwrap(),
        StaticSecret::from([1; 32]),
    );
    router.update_peers(peers).unwrap();
    let (input, receiver) = tokio::sync::mpsc::unbounded_channel();
    let released = Arc::new(AtomicBool::new(false));
    let (commands, command_receiver) = tokio::sync::mpsc::unbounded_channel();
    let pump = Pump(tokio::spawn(run_data_plane(
        InputTunnel {
            input: tokio::sync::Mutex::new(receiver),
            released: released.clone(),
        },
        UdpSocket::bind("127.0.0.1:0").await.unwrap(),
        router,
        expiry,
        tokio::time::Instant::now() + valid_for,
        command_receiver,
        NetworkAuthority::new(&server.url).unwrap(),
        request.clone(),
        Arc::new(AtomicBool::new(false)),
        Arc::new(AtomicU8::new(0)),
        relay_config,
    )));
    Fixture {
        server,
        pump,
        input,
        commands,
        remote,
        released,
        request,
    }
}

fn renewed_request(request: &NetworkStart) -> NetworkStart {
    let mut updated = request.clone();
    updated.ticket = "new-ticket".into();
    updated.expires_at = (OffsetDateTime::parse(
        &request.expires_at,
        &time::format_description::well_known::Rfc3339,
    )
    .unwrap()
        + time::Duration::seconds(60))
    .format(&time::format_description::well_known::Rfc3339)
    .unwrap();
    updated
}

async fn command_reply(reply: &mpsc::Receiver<Result<(), String>>) -> Result<(), String> {
    tokio::time::timeout(Duration::from_millis(300), async {
        loop {
            match reply.try_recv() {
                Ok(result) => break result,
                Err(mpsc::TryRecvError::Empty) => {
                    tokio::time::sleep(Duration::from_millis(2)).await
                }
                Err(error) => panic!("command reply disconnected: {error}"),
            }
        }
    })
    .await
    .expect("command reply must be responsive")
}

async fn assert_udp_output(test: &Fixture) {
    let mut packet = vec![0; 28];
    packet[0] = 0x45;
    packet[2..4].copy_from_slice(&28u16.to_be_bytes());
    packet[12..16].copy_from_slice(&[10, 231, 0, 1]);
    packet[16..20].copy_from_slice(&[10, 231, 0, 2]);
    test.input.send(packet).unwrap();
    let mut encrypted = [0; 2048];
    let (length, _) = tokio::time::timeout(
        Duration::from_millis(300),
        test.remote.recv_from(&mut encrypted),
    )
    .await
    .expect("valid UDP output must continue during pending authority HTTP")
    .unwrap();
    assert_eq!(&encrypted[..4], &[1, 0, 0, 0]);
    assert!(length > 28);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn pending_authority_does_not_stall_authorized_udp_output() {
    let mut test = fixture().await;
    test.server.current.wait_pending().await;
    assert_udp_output(&test).await;
    assert!(!test.server.current.release.load(Ordering::SeqCst));
    test.commands.send(Command::Stop).unwrap();
    tokio::time::timeout(Duration::from_millis(300), &mut test.pump.0)
        .await
        .unwrap()
        .unwrap()
        .unwrap();
    assert!(test.released.load(Ordering::SeqCst));
    println!("PENDING_AUTHORITY_UDP_AND_STOP_RESPONSIVE_OK");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn pending_authority_does_not_delay_stop_or_owned_tunnel_release() {
    let mut test = fixture().await;
    test.server.current.wait_pending().await;
    test.commands.send(Command::Stop).unwrap();
    tokio::time::timeout(Duration::from_millis(300), &mut test.pump.0)
        .await
        .expect("Stop must not wait for authority HTTP")
        .unwrap()
        .unwrap();
    assert!(test.released.load(Ordering::SeqCst));
    assert!(!test.server.current.release.load(Ordering::SeqCst));
    println!("PENDING_AUTHORITY_STOP_RELEASE_OK");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn pending_authority_timeout_stops_traffic_and_releases_tunnel() {
    let mut test = fixture().await;
    test.server.current.wait_pending().await;
    let started = std::time::Instant::now();
    let result = tokio::time::timeout(Duration::from_millis(2400), &mut test.pump.0)
        .await
        .expect("HTTP timeout must stop the bounded snapshot")
        .unwrap();
    assert_eq!(result.unwrap_err(), "CONTROL_PLANE_UNAVAILABLE");
    assert!(started.elapsed() < Duration::from_millis(2400));
    assert!(test.released.load(Ordering::SeqCst));
    assert_eq!(test.server.current.requests.load(Ordering::SeqCst), 1);
    assert!(!test.server.current.release.load(Ordering::SeqCst));
    assert!(test.input.send(vec![0; 28]).is_err());
    println!("PENDING_AUTHORITY_TIMEOUT_RELEASE_OK");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn authority_denial_stops_before_the_cached_snapshot_deadline() {
    let mut test = fixture().await;
    test.server.current.wait_pending().await;
    test.server.current.status.store(1, Ordering::SeqCst);
    test.server.current.release.store(true, Ordering::SeqCst);
    let result = tokio::time::timeout(Duration::from_millis(300), &mut test.pump.0)
        .await
        .expect("explicit denial must not consume cached validity")
        .unwrap();
    assert_eq!(result.unwrap_err(), "NETWORK_GRANT_INVALID");
    assert!(test.released.load(Ordering::SeqCst));
    println!("AUTHORITY_DENIAL_STOPS_SNAPSHOT_IMMEDIATELY_OK");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn peer_commands_share_the_pending_check_and_do_not_override_authority() {
    let mut test = fixture().await;
    test.server.current.wait_pending().await;
    let (first, first_reply) = mpsc::channel();
    test.commands
        .send(Command::Peers(Vec::new(), first))
        .unwrap();
    let (second, second_reply) = mpsc::channel();
    test.commands
        .send(Command::Peers(Vec::new(), second))
        .unwrap();
    assert_eq!(
        command_reply(&second_reply).await.unwrap_err(),
        "NETWORK_PEER_UPDATE_FAILED"
    );
    assert!(matches!(
        first_reply.try_recv(),
        Err(mpsc::TryRecvError::Empty)
    ));
    assert_eq!(test.server.current.requests.load(Ordering::SeqCst), 1);
    test.server.current.release.store(true, Ordering::SeqCst);
    command_reply(&first_reply).await.unwrap();
    assert_udp_output(&test).await;
    test.commands.send(Command::Stop).unwrap();
    tokio::time::timeout(Duration::from_millis(300), &mut test.pump.0)
        .await
        .unwrap()
        .unwrap()
        .unwrap();
    assert!(test.released.load(Ordering::SeqCst));
    println!("PEER_COMMANDS_SHARE_AUTHORITY_AND_KEEP_TRUST_BOUNDARY_OK");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn pending_renewal_keeps_udp_and_stop_responsive_and_rejects_duplicates() {
    let mut test = fixture().await;
    test.server.current.wait_pending().await;
    let updated = renewed_request(&test.request);
    let (first, first_reply) = mpsc::channel();
    test.commands
        .send(Command::Renew(updated.clone(), first))
        .unwrap();
    test.server.renewal.wait_pending().await;
    let (second, second_reply) = mpsc::channel();
    test.commands.send(Command::Renew(updated, second)).unwrap();
    assert_eq!(
        command_reply(&second_reply).await.unwrap_err(),
        "NETWORK_RENEW_FAILED"
    );
    assert!(matches!(
        first_reply.try_recv(),
        Err(mpsc::TryRecvError::Empty)
    ));
    assert_eq!(test.server.current.requests.load(Ordering::SeqCst), 1);
    assert_eq!(test.server.renewal.requests.load(Ordering::SeqCst), 1);
    assert_udp_output(&test).await;
    test.commands.send(Command::Stop).unwrap();
    tokio::time::timeout(Duration::from_millis(300), &mut test.pump.0)
        .await
        .unwrap()
        .unwrap()
        .unwrap();
    assert!(test.released.load(Ordering::SeqCst));
    assert!(matches!(
        first_reply.try_recv(),
        Err(mpsc::TryRecvError::Disconnected)
    ));
    println!("PENDING_RENEWAL_UDP_STOP_AND_REQUEST_BOUND_OK");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn accepted_renewal_discards_the_pending_old_ticket_poll() {
    let mut test = fixture().await;
    test.server.current.wait_pending().await;
    let (reply, result) = mpsc::channel();
    test.commands
        .send(Command::Renew(renewed_request(&test.request), reply))
        .unwrap();
    test.server.renewal.wait_pending().await;
    test.server.renewal.release.store(true, Ordering::SeqCst);
    command_reply(&result).await.unwrap();
    test.server.current.status.store(1, Ordering::SeqCst);
    test.server.current.release.store(true, Ordering::SeqCst);
    tokio::time::sleep(Duration::from_millis(1200)).await;
    assert!(
        !test.pump.0.is_finished(),
        "late old-ticket denial must not replace accepted renewal"
    );
    assert_eq!(test.server.current.requests.load(Ordering::SeqCst), 1);
    assert!(test.server.renewal.requests.load(Ordering::SeqCst) >= 2);
    assert_udp_output(&test).await;
    test.commands.send(Command::Stop).unwrap();
    tokio::time::timeout(Duration::from_millis(300), &mut test.pump.0)
        .await
        .unwrap()
        .unwrap()
        .unwrap();
    println!("ACCEPTED_RENEWAL_CANCELS_OLD_POLL_OK");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn initial_snapshot_deadline_stops_pending_http_without_waiting_for_timeout() {
    let mut test = fixture_with_deadline(Duration::from_millis(500), true).await;
    test.server.current.wait_pending().await;
    let result = tokio::time::timeout(Duration::from_millis(800), &mut test.pump.0)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(result.unwrap_err(), "CONTROL_PLANE_UNAVAILABLE");
    assert!(test.released.load(Ordering::SeqCst));
    assert!(!test.server.current.release.load(Ordering::SeqCst));
    println!("INITIAL_SNAPSHOT_HARD_DEADLINE_OK");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn successful_check_window_starts_before_the_http_response() {
    let test = fixture().await;
    let expires = OffsetDateTime::parse(
        &test.request.expires_at,
        &time::format_description::well_known::Rfc3339,
    )
    .unwrap();
    let authority = NetworkAuthority::new(&test.server.url).unwrap();
    let before = tokio::time::Instant::now();
    let mut check = AuthorityCheck::new(&authority, &test.request, expires);
    assert!(check.valid_until >= before + Duration::from_secs(3));
    assert!(check.valid_until <= tokio::time::Instant::now() + Duration::from_secs(3));
    let deadline = check.valid_until;
    let response = async {
        tokio::time::sleep(Duration::from_millis(200)).await;
        test.server.current.release.store(true, Ordering::SeqCst);
    };
    let (result, ()) = tokio::join!(check.result.as_mut(), response);
    result.unwrap();
    assert_eq!(check.valid_until, deadline);
    assert!(check.valid_until < tokio::time::Instant::now() + Duration::from_millis(2900));
    println!("SLOW_SUCCESS_DOES_NOT_EXTEND_SNAPSHOT_WINDOW_OK");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn late_success_does_not_extend_the_data_plane_deadline() {
    let mut test = fixture_with_deadline(Duration::from_secs(3), false).await;
    test.server.current.wait_pending().await;
    let started = tokio::time::Instant::now();
    let (reply, result) = mpsc::channel();
    test.commands
        .send(Command::Peers(Vec::new(), reply))
        .unwrap();
    tokio::time::sleep(Duration::from_millis(1500)).await;
    test.server.current.release.store(true, Ordering::SeqCst);
    command_reply(&result).await.unwrap();
    test.server.current.release.store(false, Ordering::SeqCst);
    let result = tokio::time::timeout_at(started + Duration::from_millis(3300), &mut test.pump.0)
        .await
        .expect("late response must not create a fresh three-second window")
        .unwrap();
    assert_eq!(result.unwrap_err(), "CONTROL_PLANE_UNAVAILABLE");
    assert!(test.released.load(Ordering::SeqCst));
    assert!(test.server.current.requests.load(Ordering::SeqCst) >= 2);
    println!("DATA_PLANE_RETAINS_REQUEST_START_DEADLINE_OK");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn rejected_renewal_cannot_extend_the_original_snapshot() {
    let mut test = fixture_with_deadline(Duration::from_millis(700), true).await;
    test.server.current.wait_pending().await;
    let (reply, result) = mpsc::channel();
    test.commands
        .send(Command::Renew(renewed_request(&test.request), reply))
        .unwrap();
    test.server.renewal.wait_pending().await;
    test.server.renewal.status.store(1, Ordering::SeqCst);
    test.server.renewal.release.store(true, Ordering::SeqCst);
    assert_eq!(
        command_reply(&result).await.unwrap_err(),
        "NETWORK_GRANT_INVALID"
    );
    assert!(!test.pump.0.is_finished());
    assert_udp_output(&test).await;
    let result = tokio::time::timeout(Duration::from_secs(1), &mut test.pump.0)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(result.unwrap_err(), "CONTROL_PLANE_UNAVAILABLE");
    assert!(test.released.load(Ordering::SeqCst));
    println!("REJECTED_RENEWAL_CANNOT_EXTEND_OLD_AUTHORITY_OK");
}
