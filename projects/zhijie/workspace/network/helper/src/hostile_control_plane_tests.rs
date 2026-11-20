use super::*;

struct ReleasedTunnel {
    inner: ControlledTunnel,
    released: Arc<AtomicBool>,
}

impl TunnelDevice for ReleasedTunnel {
    async fn recv(&self, packet: &mut [u8]) -> std::io::Result<usize> {
        self.inner.recv(packet).await
    }

    async fn send(&self, packet: &[u8]) -> std::io::Result<usize> {
        self.inner.send(packet).await
    }
}

impl Drop for ReleasedTunnel {
    fn drop(&mut self) {
        self.released.store(true, Ordering::SeqCst);
    }
}

struct Blackhole {
    url: String,
    requests: Arc<AtomicUsize>,
    worker: tokio::task::JoinHandle<()>,
}

impl Blackhole {
    async fn start() -> Self {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let requests = Arc::new(AtomicUsize::new(0));
        let observed = requests.clone();
        let worker = tokio::spawn(async move {
            let mut held = Vec::new();
            loop {
                let (stream, _) = listener.accept().await.unwrap();
                let mut bytes = [0; 1024];
                assert!(stream.peek(&mut bytes).await.unwrap() > 0);
                observed.fetch_add(1, Ordering::SeqCst);
                held.push(stream);
            }
        });
        Self {
            url,
            requests,
            worker,
        }
    }
}

impl Drop for Blackhole {
    fn drop(&mut self) {
        self.worker.abort();
    }
}

struct HostileEndpoint {
    socket: UdpSocket,
    router: TunnelRouter,
    data_sent: usize,
    handshakes_sent: usize,
    received: Vec<Vec<u8>>,
}

impl HostileEndpoint {
    async fn dispatch(&mut self, actions: Vec<PacketAction>) {
        for action in actions {
            match action {
                PacketAction::Network(endpoint, bytes) => {
                    self.socket.send_to(&bytes, endpoint).await.unwrap();
                    if bytes[..4] == [4, 0, 0, 0] {
                        self.data_sent += 1;
                    }
                    if bytes[..4] == [1, 0, 0, 0] {
                        self.handshakes_sent += 1;
                    }
                }
                PacketAction::Tunnel(bytes) => self.received.push(bytes),
                PacketAction::Relay(..) => panic!("direct-only probe cannot use Relay"),
            }
        }
    }

    async fn step(&mut self, plain: &[u8]) {
        let actions = self.router.outbound(plain);
        self.dispatch(actions).await;
        let actions = self.router.tick();
        self.dispatch(actions).await;
        let mut bytes = [0; 2048];
        if let Ok(received) =
            tokio::time::timeout(Duration::from_millis(10), self.socket.recv_from(&mut bytes)).await
        {
            let (length, endpoint) = match received {
                Ok(received) => received,
                Err(error)
                    if matches!(
                        error.kind(),
                        std::io::ErrorKind::ConnectionReset | std::io::ErrorKind::ConnectionRefused
                    ) =>
                {
                    return
                }
                Err(error) => panic!("hostile UDP receive failed: {error}"),
            };
            let actions = self.router.inbound(endpoint, &bytes[..length]);
            self.dispatch(actions).await;
        }
    }
}

fn router(request: &NetworkStart, peers: Vec<NetworkPeer>) -> TunnelRouter {
    let private: [u8; 32] = base64::engine::general_purpose::STANDARD
        .decode(&request.private_key)
        .unwrap()
        .try_into()
        .unwrap();
    let mut router = TunnelRouter::new(
        request.virtual_ipv4.parse().unwrap(),
        request.activity_cidr.parse().unwrap(),
        StaticSecret::from(private),
    );
    router.update_peers(peers).unwrap();
    router
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
#[ignore = "requires real control-plane tickets and coordinated hostile peer revocation"]
async fn revoked_direct_endpoint_keeps_sending_but_cannot_deliver() {
    let mode = std::env::var("ZHIJIE_TEST_HOSTILE_MODE").unwrap();
    assert!(matches!(mode.as_str(), "online" | "offline"));
    let url = std::env::var("ZHIJIE_TEST_CONTROL_PLANE_URL").unwrap();
    let directory =
        std::path::PathBuf::from(std::env::var("ZHIJIE_TEST_HOSTILE_DIRECTORY").unwrap());
    let mut sockets = Vec::new();
    for _ in 0..2 {
        sockets.push(UdpSocket::bind("127.0.0.1:0").await.unwrap());
    }
    let public: Vec<_> = sockets.iter().enumerate().map(|(index, socket)| serde_json::json!({
        "publicKey": base64::engine::general_purpose::STANDARD.encode(PublicKey::from(&StaticSecret::from([index as u8 + 1; 32])).to_bytes()),
        "endpoint": socket.local_addr().unwrap().to_string(),
    })).collect();
    std::fs::write(
        directory.join("public.json"),
        serde_json::to_vec(&public).unwrap(),
    )
    .unwrap();
    let requests: Vec<NetworkStart> = tokio::task::spawn_blocking(|| {
        let mut line = String::new();
        std::io::stdin().read_line(&mut line).unwrap();
        serde_json::from_str(&line).expect("two scoped starts through anonymous stdin")
    })
    .await
    .unwrap();
    assert_eq!(requests.len(), 2);
    assert!(requests
        .iter()
        .all(|request| request.allowed_paths == vec![NetworkPath::Direct]));
    let authority = NetworkAuthority::new(&url).unwrap();
    let authorized_until = tokio::time::Instant::now() + Duration::from_secs(3);
    let receiver = authority.validate(&requests[0]).await.unwrap();
    let hostile = authority.validate(&requests[1]).await.unwrap();
    assert_eq!(receiver.peers.len(), 1);
    assert_eq!(hostile.peers.len(), 1);
    let blackhole = Blackhole::start().await;
    let (input, incoming) = tokio::sync::mpsc::unbounded_channel();
    let (output, mut capture) = tokio::sync::mpsc::unbounded_channel();
    let (commands, receiver_commands) = tokio::sync::mpsc::unbounded_channel();
    let released = Arc::new(AtomicBool::new(false));
    let consumed = Arc::new(AtomicUsize::new(0));
    let connected = Arc::new(AtomicBool::new(false));
    let expires = OffsetDateTime::parse(
        &requests[0].expires_at,
        &time::format_description::well_known::Rfc3339,
    )
    .unwrap();
    let mut pumps = Pumps(vec![tokio::spawn(run_data_plane(
        ReleasedTunnel {
            inner: ControlledTunnel {
                input: tokio::sync::Mutex::new(incoming),
                output,
                consumed: consumed.clone(),
            },
            released: released.clone(),
        },
        sockets.remove(0),
        router(&requests[0], receiver.peers),
        expires,
        authorized_until,
        receiver_commands,
        if mode == "offline" {
            NetworkAuthority::new(&blackhole.url).unwrap()
        } else {
            authority
        },
        requests[0].clone(),
        connected.clone(),
        Arc::new(AtomicU8::new(0)),
        Err("RELAY_PATH_UNAVAILABLE".into()),
    ))]);
    let mut endpoint = HostileEndpoint {
        socket: sockets.remove(0),
        router: router(&requests[1], hostile.peers.clone()),
        data_sent: 0,
        handshakes_sent: 0,
        received: Vec::new(),
    };
    let receiver_address: Ipv4Addr = requests[0].virtual_ipv4.parse().unwrap();
    let hostile_address: Ipv4Addr = requests[1].virtual_ipv4.parse().unwrap();
    let forward = packet(
        hostile_address.octets()[3],
        receiver_address.octets()[3],
        1,
        0,
    );
    let reverse = packet(
        receiver_address.octets()[3],
        hostile_address.octets()[3],
        1,
        0,
    );
    tokio::time::timeout(Duration::from_millis(1500), async {
        let mut delivered = false;
        loop {
            input.send(reverse.clone()).unwrap();
            endpoint.step(&forward).await;
            while let Ok(bytes) = capture.try_recv() {
                assert_eq!(bytes, forward);
                delivered = true;
            }
            if delivered
                && endpoint.received.iter().any(|bytes| bytes == &reverse)
                && connected.load(Ordering::Relaxed)
                && (mode != "offline" || blackhole.requests.load(Ordering::SeqCst) > 0)
            {
                break;
            }
            assert!(!pumps.0[0].is_finished());
        }
    })
    .await
    .expect("real encrypted bidirectional traffic before revocation");
    std::fs::write(directory.join("ready"), b"ready").unwrap();
    tokio::time::timeout(Duration::from_secs(1), async {
        while !directory.join("revoked").is_file() {
            endpoint.step(&forward).await;
        }
    })
    .await
    .expect("parent must confirm real revocation before the offline snapshot expires");
    let confirmed = tokio::time::Instant::now();
    assert!(!pumps.0[0].is_finished());
    assert!(!released.load(Ordering::SeqCst));
    while confirmed.elapsed() < Duration::from_millis(3100) {
        endpoint.step(&forward).await;
    }
    while capture.try_recv().is_ok() {}
    endpoint.received.clear();
    if mode == "offline" {
        assert!(pumps.0[0].is_finished());
        assert_eq!(
            (&mut pumps.0[0]).await.unwrap().unwrap_err(),
            "CONTROL_PLANE_UNAVAILABLE"
        );
        assert!(released.load(Ordering::SeqCst));
        assert!(input.send(reverse.clone()).is_err());
        assert!(blackhole.requests.load(Ordering::SeqCst) > 0);
    } else {
        assert!(!pumps.0[0].is_finished());
        assert!(!released.load(Ordering::SeqCst));
    }
    let sent_before = endpoint.data_sent;
    let consumed_before = consumed.load(Ordering::Relaxed);
    for _ in 0..30 {
        if mode == "online" {
            input.send(reverse.clone()).unwrap();
        }
        endpoint.step(&forward).await;
    }
    assert!(
        endpoint.data_sent - sent_before >= 30,
        "old authenticated session must keep transmitting"
    );
    assert!(
        capture.try_recv().is_err(),
        "revoked old ciphertext reached the receiving TunnelDevice"
    );
    assert!(
        endpoint.received.is_empty(),
        "receiver still emitted plaintext traffic to the revoked peer"
    );
    let old_packets = endpoint.data_sent - sent_before;
    let before_handshakes = endpoint.handshakes_sent;
    endpoint.router = router(&requests[1], hostile.peers);
    for _ in 0..30 {
        endpoint.step(&forward).await;
    }
    assert!(
        endpoint.handshakes_sent > before_handshakes,
        "a fresh handshake must actually be transmitted"
    );
    assert!(
        capture.try_recv().is_err(),
        "revoked fresh handshake restored delivery"
    );
    assert!(endpoint.received.is_empty());
    if mode == "online" {
        assert!(!pumps.0[0].is_finished());
        assert!(consumed.load(Ordering::Relaxed) - consumed_before >= 30);
    }
    assert!(confirmed.elapsed() < Duration::from_millis(4500));
    std::fs::write(directory.join("isolated"), b"isolated").unwrap();
    println!("REAL_HOSTILE_DIRECT_ISOLATED mode={mode} old_endpoint_stopped=false old_packets={old_packets} fresh_handshake_sent=true delivered=0 elapsed_ms={}", confirmed.elapsed().as_millis());
    if mode == "online" {
        commands.send(Command::Stop).unwrap();
        tokio::time::timeout(Duration::from_millis(300), &mut pumps.0[0])
            .await
            .unwrap()
            .unwrap()
            .unwrap();
        assert!(released.load(Ordering::SeqCst));
    }
}
