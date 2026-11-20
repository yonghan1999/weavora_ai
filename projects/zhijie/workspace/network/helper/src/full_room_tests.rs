use super::*;
use boringtun::x25519::PublicKey;
use std::collections::HashSet;
use std::io::{Read, Write};
use std::sync::atomic::AtomicUsize;
use zhijie_relay::model::RelayGrant;
use zhijie_relay::quic::QuicRelayServer;

const MEMBERS: usize = 12;
const BURST: u8 = 10;

#[path = "full_room_control_plane_tests.rs"]
mod control_plane;

#[path = "hostile_control_plane_tests.rs"]
mod hostile_control_plane;

struct ControlledTunnel {
    input: tokio::sync::Mutex<tokio::sync::mpsc::UnboundedReceiver<Vec<u8>>>,
    output: tokio::sync::mpsc::UnboundedSender<Vec<u8>>,
    consumed: Arc<AtomicUsize>,
}

impl TunnelDevice for ControlledTunnel {
    async fn recv(&self, packet: &mut [u8]) -> std::io::Result<usize> {
        let data = self
            .input
            .lock()
            .await
            .recv()
            .await
            .ok_or(std::io::ErrorKind::BrokenPipe)?;
        packet[..data.len()].copy_from_slice(&data);
        self.consumed.fetch_add(1, Ordering::Relaxed);
        Ok(data.len())
    }

    async fn send(&self, packet: &[u8]) -> std::io::Result<usize> {
        self.output
            .send(packet.to_vec())
            .map_err(|_| std::io::ErrorKind::BrokenPipe)?;
        Ok(packet.len())
    }
}

struct RoomAuthority {
    active: Arc<Vec<AtomicBool>>,
    rejected_removed_ticket: Arc<AtomicBool>,
}

impl zhijie_relay::server::GrantValidator for RoomAuthority {
    fn validate(&self, ticket: &str, _: std::net::SocketAddr) -> Result<RelayGrant, String> {
        let index: usize = ticket.parse().map_err(|_| "unknown ticket")?;
        if index >= MEMBERS || !self.active[index].load(Ordering::Relaxed) {
            if index == MEMBERS - 1 {
                self.rejected_removed_ticket.store(true, Ordering::SeqCst);
            }
            return Err("revoked".into());
        }
        Ok(RelayGrant {
            version: 1,
            ticket: ticket.into(),
            activity_id: "full-room".into(),
            activity_generation: 1,
            member_generation: 1,
            lease_id: format!("lease-{index}"),
            device_id: format!("device-{index}"),
            user_id: ticket.into(),
            source_ipv4: format!("10.231.0.{}", index + 1),
            policy_revision: 1,
            allow_relay: true,
            expires_at_unix_millis: (std::time::SystemTime::now() + Duration::from_secs(60))
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_millis() as u64,
        })
    }
}

// HTTP 仅提供受控权威响应；加密、UDP、QUIC 和生产数据泵均真实运行。
struct AuthorityServer {
    running: Arc<AtomicBool>,
    worker: Option<std::thread::JoinHandle<()>>,
    url: String,
}

impl AuthorityServer {
    fn start(
        bodies: Vec<serde_json::Value>,
        active: Arc<Vec<AtomicBool>>,
        hostile_direct: bool,
    ) -> Self {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        listener.set_nonblocking(true).unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let running = Arc::new(AtomicBool::new(true));
        let keep_running = running.clone();
        let worker = std::thread::spawn(move || {
            while keep_running.load(Ordering::Relaxed) {
                let (mut stream, _) = match listener.accept() {
                    Ok(stream) => stream,
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
                let mut buffer = [0; 4096];
                let request = loop {
                    let count = match stream.read(&mut buffer) {
                        Ok(count) if count > 0 => count,
                        _ => break None,
                    };
                    bytes.extend_from_slice(&buffer[..count]);
                    if bytes.len() > 8192 {
                        break None;
                    }
                    if let Some(end) = bytes.windows(4).position(|value| value == b"\r\n\r\n") {
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
                            .ok();
                        }
                    }
                };
                let index = request
                    .as_ref()
                    .and_then(|value| value["ticket"].as_str())
                    .and_then(|value| value.parse::<usize>().ok());
                // 直连场景故意不给失权端撤销响应，模拟其继续使用旧密钥发送。
                let (status, body) = match index.filter(|index| {
                    *index < MEMBERS
                        && (active[*index].load(Ordering::Relaxed)
                            || (hostile_direct && *index == MEMBERS - 1))
                }) {
                    Some(index) => {
                        let mut body = bodies[index].clone();
                        body["peers"].as_array_mut().unwrap().retain(|peer| {
                            let index = peer["userId"].as_str().unwrap().parse::<usize>().unwrap();
                            active[index].load(Ordering::Relaxed)
                        });
                        ("200 OK", body.to_string())
                    }
                    None => ("403 Forbidden", "{}".into()),
                };
                let _ = write!(stream, "HTTP/1.1 {status}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len());
            }
        });
        Self {
            running,
            worker: Some(worker),
            url,
        }
    }
}

impl Drop for AuthorityServer {
    fn drop(&mut self) {
        self.running.store(false, Ordering::Relaxed);
        self.worker.take().unwrap().join().unwrap();
    }
}

struct Pumps(Vec<tokio::task::JoinHandle<Result<(), String>>>);
impl Drop for Pumps {
    fn drop(&mut self) {
        for pump in &self.0 {
            pump.abort();
        }
    }
}

fn packet(source: u8, destination: u8, phase: u8, sequence: u8) -> Vec<u8> {
    // 合法 IPv4/UDP 报文：IPv4 校验和、UDP 长度；IPv4 的 UDP 校验和可为零。
    let mut packet = vec![0; 64];
    packet[0] = 0x45;
    packet[2..4].copy_from_slice(&64u16.to_be_bytes());
    packet[8] = 64;
    packet[9] = 17;
    packet[12..16].copy_from_slice(&[10, 231, 0, source]);
    packet[16..20].copy_from_slice(&[10, 231, 0, destination]);
    packet[20..22].copy_from_slice(&23001u16.to_be_bytes());
    packet[22..24].copy_from_slice(&23002u16.to_be_bytes());
    packet[24..26].copy_from_slice(&44u16.to_be_bytes());
    packet[28] = phase;
    packet[29] = sequence;
    let mut checksum: u32 = packet[..20]
        .chunks_exact(2)
        .map(|bytes| u16::from_be_bytes([bytes[0], bytes[1]]) as u32)
        .sum();
    while checksum > 0xffff {
        checksum = (checksum & 0xffff) + (checksum >> 16);
    }
    packet[10..12].copy_from_slice(&(!(checksum as u16)).to_be_bytes());
    packet
}

async fn full_room(mode: &str) {
    let mut sockets = Vec::new();
    for _ in 0..MEMBERS {
        sockets.push(UdpSocket::bind("127.0.0.1:0").await.unwrap());
    }
    let endpoints: Vec<_> = sockets
        .iter()
        .map(|socket| socket.local_addr().unwrap())
        .collect();
    let mut blackholes = Vec::new();
    for _ in 0..MEMBERS {
        blackholes.push(std::net::UdpSocket::bind("127.0.0.1:0").unwrap());
    }
    let active = Arc::new(
        (0..MEMBERS)
            .map(|_| AtomicBool::new(true))
            .collect::<Vec<_>>(),
    );
    let expiry = OffsetDateTime::now_utc() + time::Duration::seconds(45);
    let paths = match mode {
        "direct" => vec![NetworkPath::Direct],
        "relay" => vec![NetworkPath::Relay],
        _ => vec![NetworkPath::Direct, NetworkPath::Relay],
    };
    let requests: Vec<_> = (0..MEMBERS)
        .map(|index| NetworkStart {
            protocol_version: 1,
            request_id: index.to_string(),
            ticket: index.to_string(),
            activity_id: "full-room".into(),
            activity_generation: 1,
            member_generation: 1,
            device_id: index.to_string(),
            lease_id: format!("lease-{index}"),
            policy_revision: 1,
            allowed_paths: paths.clone(),
            private_key: base64::engine::general_purpose::STANDARD.encode([index as u8 + 1; 32]),
            virtual_ipv4: format!("10.231.0.{}", index + 1),
            activity_cidr: "10.231.0.0/27".into(),
            expires_at: expiry
                .format(&time::format_description::well_known::Rfc3339)
                .unwrap(),
        })
        .collect();
    let peers: Vec<Vec<NetworkPeer>> = (0..MEMBERS)
        .map(|local| {
            (0..MEMBERS)
                .filter(|remote| *remote != local)
                .map(|remote| NetworkPeer {
                    user_id: remote.to_string(),
                    member_generation: requests[remote].member_generation,
                    lease_id: requests[remote].lease_id.clone(),
                    expires_at: requests[remote].expires_at.clone(),
                    virtual_ipv4: requests[remote].virtual_ipv4.clone(),
                    public_key: base64::engine::general_purpose::STANDARD.encode(
                        PublicKey::from(&StaticSecret::from([remote as u8 + 1; 32])).to_bytes(),
                    ),
                    endpoint: if mode == "relay" {
                        None
                    } else {
                        Some(
                            if mode == "mixed" && (local == MEMBERS - 1 || remote == MEMBERS - 1) {
                                blackholes[remote].local_addr().unwrap()
                            } else {
                                endpoints[remote]
                            }
                            .to_string(),
                        )
                    },
                })
                .collect()
        })
        .collect();
    let bodies = requests.iter().zip(&peers).enumerate().map(|(index, (request, peers))| serde_json::json!({
        "activityId": request.activity_id, "activityGeneration": 1, "memberGeneration": 1,
        "deviceId": request.device_id, "leaseId": request.lease_id, "policyRevision": 1,
        "allowedPaths": request.allowed_paths, "virtualIpv4": request.virtual_ipv4, "activityCidr": request.activity_cidr,
        "publicKey": base64::engine::general_purpose::STANDARD.encode(PublicKey::from(&StaticSecret::from([index as u8 + 1; 32])).to_bytes()),
        "expiresAt": request.expires_at, "peers": peers,
    })).collect();
    let http = AuthorityServer::start(bodies, active.clone(), mode == "direct");
    let cert = rcgen::generate_simple_self_signed(vec!["localhost".into()]).unwrap();
    let rejected_removed_ticket = Arc::new(AtomicBool::new(false));
    let relay = QuicRelayServer::bind_der(
        "127.0.0.1:0".parse().unwrap(),
        cert.cert.der().to_vec(),
        cert.signing_key.serialize_der(),
        Arc::new(RoomAuthority {
            active: active.clone(),
            rejected_removed_ticket: rejected_removed_ticket.clone(),
        }),
    )
    .unwrap();
    let config = RelayConfig {
        address: relay.local_addr(),
        server_name: "localhost".into(),
        extra_ca: Some(cert.cert.der().to_vec()),
    };
    let mut inputs = Vec::new();
    let mut outputs = Vec::new();
    let mut commands = Vec::new();
    let mut connected = Vec::new();
    let mut consumed = Vec::new();
    let mut actual_paths = Vec::new();
    let mut pumps = Pumps(Vec::new());
    for (index, ((socket, request), peers)) in
        sockets.into_iter().zip(requests).zip(peers).enumerate()
    {
        let mut router = TunnelRouter::new(
            request.virtual_ipv4.parse().unwrap(),
            request.activity_cidr.parse().unwrap(),
            StaticSecret::from([index as u8 + 1; 32]),
        );
        router.update_peers(peers).unwrap();
        let (input, receive) = tokio::sync::mpsc::unbounded_channel();
        let (output, capture) = tokio::sync::mpsc::unbounded_channel();
        let (command, receiver) = tokio::sync::mpsc::unbounded_channel();
        let ready = Arc::new(AtomicBool::new(false));
        let path = Arc::new(AtomicU8::new(0));
        let reads = Arc::new(AtomicUsize::new(0));
        pumps.0.push(tokio::spawn(run_data_plane(
            ControlledTunnel {
                input: tokio::sync::Mutex::new(receive),
                output,
                consumed: reads.clone(),
            },
            socket,
            router,
            expiry,
            tokio::time::Instant::now() + Duration::from_secs(3),
            receiver,
            NetworkAuthority::new(&http.url).unwrap(),
            request,
            ready.clone(),
            path.clone(),
            Ok(config.clone()),
        )));
        inputs.push(input);
        outputs.push(capture);
        commands.push(command);
        connected.push(ready);
        consumed.push(reads);
        actual_paths.push(path);
    }
    let exercise = async {
        loop {
            let ready = connected.iter().all(|value| value.load(Ordering::Relaxed));
            let correct_paths = actual_paths.iter().enumerate().all(|(index, value)| {
                value.load(Ordering::Relaxed)
                    == match mode {
                        "direct" => 0,
                        "relay" => 1,
                        _ if index == MEMBERS - 1 => 1,
                        _ => 2,
                    }
            });
            if ready && correct_paths {
                break;
            }
            assert!(
                pumps.0.iter().all(|pump| !pump.is_finished()),
                "pump stopped before full-room handshake: {mode}"
            );
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
        for (index, input) in inputs.iter().enumerate() {
            let source = index as u8 + 1;
            let other = (index + 1) % MEMBERS + 1;
            input.send(packet(other as u8, source, 5, 0)).unwrap();
            input.send(packet(source, 30, 5, 1)).unwrap();
        }
        tokio::time::sleep(Duration::from_millis(200)).await;
        for output in &mut outputs {
            assert!(
                output.try_recv().is_err(),
                "spoofed source or unknown target escaped filtering"
            );
        }
        for (phase, count) in [(1, MEMBERS), (2, MEMBERS - 1)] {
            for sequence in 0..BURST {
                for (source, input) in inputs.iter().enumerate().take(count) {
                    for destination in 0..count {
                        if source != destination {
                            input
                                .send(packet(
                                    source as u8 + 1,
                                    destination as u8 + 1,
                                    phase,
                                    sequence,
                                ))
                                .unwrap();
                        }
                    }
                }
                tokio::time::sleep(Duration::from_millis(20)).await;
            }
            for (destination, output) in outputs.iter_mut().enumerate().take(count) {
                let mut seen = HashSet::new();
                for _ in 0..(count - 1) * BURST as usize {
                    let received = output.recv().await.expect("pump output");
                    let source = received[15];
                    let sequence = received[29];
                    assert_eq!(
                        received,
                        packet(source, destination as u8 + 1, phase, sequence)
                    );
                    assert!((1..=count as u8).contains(&source) && source != destination as u8 + 1);
                    assert!(
                        sequence < BURST && seen.insert((source, sequence)),
                        "duplicate or unexpected delivery"
                    );
                }
            }
            println!("NETWORK_ROOM_OK mode={mode} phase={phase} members={count} directions={} packets_per_direction={BURST}", count * (count - 1));
            if phase == 1 {
                // 删除一个成员后，其他十一端应继续通信，旧目标不能再次收到载荷。
                active[MEMBERS - 1].store(false, Ordering::Relaxed);
                if mode != "direct" {
                    let code =
                        tokio::time::timeout(Duration::from_secs(5), &mut pumps.0[MEMBERS - 1])
                            .await
                            .unwrap()
                            .unwrap()
                            .unwrap_err();
                    assert!(
                        code == "NETWORK_GRANT_INVALID"
                            || (code == "RELAY_AUTH_FAILED"
                                && rejected_removed_ticket.load(Ordering::SeqCst)),
                        "unexpected revocation result in {mode}: {code}"
                    );
                }
                tokio::time::sleep(Duration::from_millis(1200)).await;
                if mode == "direct" {
                    assert!(
                        !pumps.0[MEMBERS - 1].is_finished(),
                        "hostile old endpoint must still be sending"
                    );
                    let previous_reads = consumed[MEMBERS - 1].load(Ordering::Relaxed);
                    for sequence in 0..BURST {
                        for destination in 1..MEMBERS {
                            inputs[MEMBERS - 1]
                                .send(packet(MEMBERS as u8, destination as u8, 4, sequence))
                                .unwrap();
                        }
                    }
                    tokio::time::sleep(Duration::from_millis(300)).await;
                    assert_eq!(
                        consumed[MEMBERS - 1].load(Ordering::Relaxed) - previous_reads,
                        (MEMBERS - 1) * BURST as usize,
                        "hostile traffic must actually enter the running data pump"
                    );
                    for output in outputs.iter_mut().take(MEMBERS - 1) {
                        assert!(
                            output.try_recv().is_err(),
                            "old direct key still delivered after peer removal"
                        );
                    }
                    println!("NETWORK_HOSTILE_REVOKED mode=direct old_endpoint_running=true attempted_packets=110 delivered=0");
                }
                for input in inputs.iter().take(MEMBERS - 1).enumerate() {
                    input
                        .1
                        .send(packet(input.0 as u8 + 1, MEMBERS as u8, 3, 0))
                        .unwrap();
                }
                tokio::time::sleep(Duration::from_millis(100)).await;
                assert!(
                    outputs[MEMBERS - 1].try_recv().is_err(),
                    "removed target still received data"
                );
            }
        }
        let stop_count = if mode == "direct" {
            MEMBERS
        } else {
            MEMBERS - 1
        };
        for command in commands.iter().take(stop_count) {
            command.send(Command::Stop).unwrap();
        }
        for pump in pumps.0.iter_mut().take(stop_count) {
            tokio::time::timeout(Duration::from_secs(2), pump)
                .await
                .unwrap()
                .unwrap()
                .unwrap();
        }
    };
    let outcome = tokio::time::timeout(Duration::from_secs(25), exercise).await;
    relay.stop().await;
    outcome.expect("full-room data pumps must deliver and clean up within deadline");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn twelve_data_pumps_deliver_all_directions_and_preserve_survivors_after_revocation() {
    for mode in ["direct", "relay", "mixed"] {
        full_room(mode).await;
    }
}
