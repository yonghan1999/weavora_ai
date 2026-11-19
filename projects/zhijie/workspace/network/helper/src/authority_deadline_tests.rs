use super::*;
use boringtun::x25519::PublicKey;
use std::io::{Read, Write};

struct DeadlineTunnel(Arc<AtomicBool>);

impl TunnelDevice for DeadlineTunnel {
    async fn recv(&self, _: &mut [u8]) -> std::io::Result<usize> {
        std::future::pending().await
    }

    async fn send(&self, _: &[u8]) -> std::io::Result<usize> {
        panic!("expired authorization must not deliver tunnel data")
    }
}

impl Drop for DeadlineTunnel {
    fn drop(&mut self) {
        self.0.store(true, Ordering::Relaxed);
    }
}

struct SlowAuthority {
    url: String,
    running: Arc<AtomicBool>,
    delayed: Arc<AtomicBool>,
    worker: Option<std::thread::JoinHandle<()>>,
}

impl SlowAuthority {
    fn start(request: &NetworkStart, renewal: bool) -> Self {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        listener.set_nonblocking(true).unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let running = Arc::new(AtomicBool::new(true));
        let delayed = Arc::new(AtomicBool::new(false));
        let keep_running = running.clone();
        let was_delayed = delayed.clone();
        let original = request.clone();
        let worker = std::thread::spawn(move || {
            while keep_running.load(Ordering::Relaxed) {
                let (mut stream, _) = match listener.accept() {
                    Ok(value) => value,
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
                let ticket = loop {
                    let count = match stream.read(&mut buffer) {
                        Ok(count) if count > 0 => count,
                        _ => break None,
                    };
                    bytes.extend_from_slice(&buffer[..count]);
                    if bytes.len() > 8192 {
                        break None;
                    }
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
                            .unwrap_or(0);
                        if bytes.len() >= end + 4 + length {
                            break serde_json::from_slice::<serde_json::Value>(
                                &bytes[end + 4..end + 4 + length],
                            )
                            .ok()
                            .and_then(|body| body["ticket"].as_str().map(str::to_owned));
                        }
                    }
                };
                let Some(ticket) = ticket else {
                    continue;
                };
                if !renewal || ticket == "new" {
                    was_delayed.store(true, Ordering::Relaxed);
                    // 响应在旧授权截止后才返回，但仍在 HTTP 默认两秒超时之内。
                    std::thread::sleep(Duration::from_millis(1700));
                }
                let expiry = if ticket == "new" {
                    OffsetDateTime::now_utc() + time::Duration::minutes(1)
                } else {
                    OffsetDateTime::parse(
                        &original.expires_at,
                        &time::format_description::well_known::Rfc3339,
                    )
                    .unwrap()
                };
                let body = serde_json::json!({
                    "activityId": original.activity_id, "activityGeneration": 1, "memberGeneration": 1,
                    "deviceId": original.device_id, "leaseId": original.lease_id, "policyRevision": 1,
                    "allowedPaths": ["DIRECT"], "virtualIpv4": original.virtual_ipv4,
                    "activityCidr": original.activity_cidr,
                    "publicKey": base64::engine::general_purpose::STANDARD.encode(PublicKey::from(&StaticSecret::from([1; 32])).to_bytes()),
                    "expiresAt": expiry.format(&time::format_description::well_known::Rfc3339).unwrap(), "peers": [],
                }).to_string();
                let _ = write!(stream, "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len());
            }
        });
        Self {
            url,
            running,
            delayed,
            worker: Some(worker),
        }
    }
}

impl Drop for SlowAuthority {
    fn drop(&mut self) {
        self.running.store(false, Ordering::Relaxed);
        self.worker.take().unwrap().join().unwrap();
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn delayed_poll_and_renewal_stop_at_original_expiry_and_release_tunnel() {
    for renewal in [false, true] {
        let expiry = OffsetDateTime::now_utc() + time::Duration::seconds(1);
        let request = NetworkStart {
            protocol_version: 1,
            request_id: "deadline".into(),
            ticket: "old".into(),
            activity_id: "deadline".into(),
            activity_generation: 1,
            member_generation: 1,
            device_id: "device".into(),
            lease_id: "lease".into(),
            policy_revision: 1,
            allowed_paths: vec![NetworkPath::Direct],
            private_key: base64::engine::general_purpose::STANDARD.encode([1; 32]),
            virtual_ipv4: "10.231.0.1".into(),
            activity_cidr: "10.231.0.0/27".into(),
            expires_at: expiry
                .format(&time::format_description::well_known::Rfc3339)
                .unwrap(),
        };
        let server = SlowAuthority::start(&request, renewal);
        let socket = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let router = TunnelRouter::new(
            "10.231.0.1".parse().unwrap(),
            "10.231.0.0/27".parse().unwrap(),
            StaticSecret::from([1; 32]),
        );
        let (commands, receiver) = tokio::sync::mpsc::unbounded_channel();
        let mut reply = None;
        if renewal {
            let mut updated = request.clone();
            updated.ticket = "new".into();
            updated.expires_at = (expiry + time::Duration::seconds(60))
                .format(&time::format_description::well_known::Rfc3339)
                .unwrap();
            let (send, receive) = mpsc::channel();
            commands.send(Command::Renew(updated, send)).unwrap();
            reply = Some(receive);
        }
        let released = Arc::new(AtomicBool::new(false));
        let pump = run_data_plane(
            DeadlineTunnel(released.clone()),
            socket,
            router,
            expiry,
            receiver,
            NetworkAuthority::new(&server.url).unwrap(),
            request,
            Arc::new(AtomicBool::new(false)),
            Arc::new(AtomicU8::new(0)),
            Err("RELAY_PATH_UNAVAILABLE".into()),
        );
        let result = tokio::time::timeout(Duration::from_millis(1500), pump)
            .await
            .expect("authorization deadline must precede delayed HTTP response");
        assert_eq!(result.unwrap_err(), "GRANT_EXPIRED");
        assert!(server.delayed.load(Ordering::Relaxed));
        assert!(
            released.load(Ordering::Relaxed),
            "owned tunnel must be released before response"
        );
        if let Some(reply) = reply {
            assert_eq!(reply.try_recv().unwrap().unwrap_err(), "GRANT_EXPIRED");
        }
        println!("NETWORK_AUTHORITY_DEADLINE_OK renewal={renewal} late_response_rejected=true tunnel_released=true");
        drop(commands);
    }
}
