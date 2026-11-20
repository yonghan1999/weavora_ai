use super::*;
use zhijie_relay::control_plane::ControlPlaneValidator;

#[path = "full_room_diagnostics.rs"]
mod diagnostics;
use diagnostics::PumpDiagnostic;

// 仅父 Java 集成测试启用；短票和测试私钥通过匿名标准输入传入，不写文件。
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
#[ignore = "requires twelve real control-plane members and coordinated revocation"]
async fn twelve_real_control_plane_data_pumps() {
    let url = std::env::var("ZHIJIE_TEST_CONTROL_PLANE_URL").unwrap();
    let management_key = std::env::var("ZHIJIE_TEST_RELAY_MANAGEMENT_KEY").unwrap();
    let mode = std::env::var("ZHIJIE_TEST_NETWORK_MODE").unwrap();
    assert!(matches!(mode.as_str(), "direct" | "relay" | "mixed"));
    let public_path = std::env::var("ZHIJIE_TEST_NETWORK_PUBLIC").unwrap();
    let ready_path = std::env::var("ZHIJIE_TEST_NETWORK_READY").unwrap();
    let policy_ready = std::env::var("ZHIJIE_TEST_NETWORK_POLICY_READY").unwrap();
    let renew_ready = std::env::var("ZHIJIE_TEST_NETWORK_RENEW_READY").unwrap();
    let mut sockets = Vec::new();
    for _ in 0..MEMBERS {
        sockets.push(UdpSocket::bind("127.0.0.1:0").await.unwrap());
    }
    // 第十二端登记实际持有但不接收的黑洞候选；其真实 socket 继续工作。
    // 双方都无法完成该链路握手，其余十一端仍使用真实可达端点。
    let blackhole = std::net::UdpSocket::bind("127.0.0.1:0").unwrap();
    let public: Vec<_> = sockets.iter().enumerate().map(|(index, socket)| serde_json::json!({
        "publicKey": base64::engine::general_purpose::STANDARD.encode(PublicKey::from(&StaticSecret::from([index as u8 + 1; 32])).to_bytes()),
        "endpoint": if mode == "mixed" && index == MEMBERS - 1 { blackhole.local_addr().unwrap() } else { socket.local_addr().unwrap() }.to_string(),
    })).collect();
    std::fs::write(public_path, serde_json::to_vec(&public).unwrap()).unwrap();
    let requests: Vec<NetworkStart> = tokio::task::spawn_blocking(|| {
        let mut line = String::new();
        std::io::stdin().read_line(&mut line).unwrap();
        serde_json::from_str(&line).expect("twelve starts via anonymous stdin")
    })
    .await
    .unwrap();
    assert_eq!(requests.len(), MEMBERS);
    let original_requests = requests.clone();
    let mut previous_requests = requests.clone();
    let validator_url = url.clone();
    let validator = tokio::task::spawn_blocking(move || {
        ControlPlaneValidator::new(validator_url, management_key)
    })
    .await
    .unwrap()
    .unwrap();
    let cert = rcgen::generate_simple_self_signed(vec!["localhost".into()]).unwrap();
    let relay = QuicRelayServer::bind_der(
        "127.0.0.1:0".parse().unwrap(),
        cert.cert.der().to_vec(),
        cert.signing_key.serialize_der(),
        Arc::new(validator),
    )
    .unwrap();
    let config = RelayConfig {
        address: relay.local_addr(),
        server_name: "localhost".into(),
        extra_ca: Some(cert.cert.der().to_vec()),
    };
    let mut relay = Some(relay);
    let mut inputs = Vec::new();
    let mut outputs = Vec::new();
    let mut commands = Vec::new();
    let mut paths = Vec::new();
    let mut connected = Vec::new();
    let mut addresses = Vec::new();
    let mut pumps = Pumps(Vec::new());
    let phase = Arc::new(AtomicU8::new(0));
    let renewal_round = Arc::new(AtomicU8::new(0));
    let started = std::time::Instant::now();
    for (index, (socket, request)) in sockets.into_iter().zip(requests).enumerate() {
        assert_eq!(
            base64::engine::general_purpose::STANDARD
                .decode(&request.private_key)
                .unwrap(),
            vec![index as u8 + 1; 32]
        );
        let authority = NetworkAuthority::new(&url)
            .unwrap()
            .with_diagnostic_member(index);
        let current = authority.validate(&request).await.unwrap();
        assert_eq!(current.peers.len(), MEMBERS - 1);
        let address: Ipv4Addr = request.virtual_ipv4.parse().unwrap();
        addresses.push(address.octets()[3]);
        let mut router = TunnelRouter::new(
            address,
            request.activity_cidr.parse().unwrap(),
            StaticSecret::from([index as u8 + 1; 32]),
        );
        router.update_peers(current.peers).unwrap();
        let expires = OffsetDateTime::parse(
            &request.expires_at,
            &time::format_description::well_known::Rfc3339,
        )
        .unwrap();
        let (input, receive) = tokio::sync::mpsc::unbounded_channel();
        let (output, capture) = tokio::sync::mpsc::unbounded_channel();
        let (command, receiver) = tokio::sync::mpsc::unbounded_channel();
        let path = Arc::new(AtomicU8::new(0));
        let ready = Arc::new(AtomicBool::new(false));
        let diagnostic = PumpDiagnostic::new(
            mode.clone(),
            index,
            phase.clone(),
            renewal_round.clone(),
            started,
        );
        let pump = run_data_plane(
            ControlledTunnel {
                input: tokio::sync::Mutex::new(receive),
                output,
                consumed: Arc::new(AtomicUsize::new(0)),
            },
            socket,
            router,
            expires,
            receiver,
            authority,
            request,
            ready.clone(),
            path.clone(),
            Ok(config.clone()),
        );
        pumps.0.push(tokio::spawn(async move {
            let mut diagnostic = diagnostic;
            let result = pump.await;
            diagnostic.record(&result);
            result
        }));
        inputs.push(input);
        outputs.push(capture);
        commands.push(command);
        paths.push(path);
        connected.push(ready);
    }
    let exercise = async {
        {
            while !connected.iter().all(|value| value.load(Ordering::Relaxed))
                || paths.iter().enumerate().any(|(index, path)| {
                    path.load(Ordering::Relaxed)
                        != match mode.as_str() {
                            "direct" => 0,
                            "relay" => 1,
                            _ if index == MEMBERS - 1 => 1,
                            _ => 2,
                        }
                })
            {
                for (index, pump) in pumps.0.iter_mut().enumerate() {
                    if pump.is_finished() {
                        panic!(
                            "network startup stopped mode={mode} member={index} code={}",
                            diagnostics::exit_code(&pump.await.unwrap())
                        );
                    }
                }
                tokio::time::sleep(Duration::from_millis(20)).await;
            }
        }
        // 五轮正常续票跨越原始一分钟票据；每轮后重新核验所有方向。
        for current_phase in 1..=7 {
            phase.store(current_phase, Ordering::Relaxed);
            let phase = current_phase;
            let count = if phase == 7 { MEMBERS - 1 } else { MEMBERS };
            // 等待握手队列排空后，真实 UDP/QUIC 报文必须全部交付。
            if phase == 1 {
                tokio::time::sleep(Duration::from_secs(4)).await;
            }
            for sequence in 0..BURST {
                for source in 0..count {
                    for destination in 0..count {
                        if source != destination {
                            inputs[source]
                                .send(packet(
                                    addresses[source],
                                    addresses[destination],
                                    phase,
                                    sequence,
                                ))
                                .unwrap();
                        }
                    }
                }
                tokio::time::sleep(Duration::from_millis(30)).await;
            }
            for destination in 0..count {
                let mut seen = HashSet::new();
                for _ in 0..(count - 1) * BURST as usize {
                    let received = tokio::time::timeout(Duration::from_secs(5), outputs[destination].recv()).await
                        .unwrap_or_else(|_| panic!("missing packet mode={mode} phase={phase} receiver={destination} received={} pumps_finished={:?}", seen.len(), pumps.0.iter().map(|pump| pump.is_finished()).collect::<Vec<_>>())).unwrap();
                    let source = received[15];
                    let sequence = received[29];
                    assert_eq!(
                        received,
                        packet(source, addresses[destination], phase, sequence)
                    );
                    assert!(
                        addresses[..count].contains(&source) && source != addresses[destination]
                    );
                    assert!(sequence < BURST && seen.insert((source, sequence)));
                }
            }
            println!("REAL_NETWORK_ROOM_OK mode={mode} phase={phase} members={count} directions={} packets_per_direction={BURST}", count * (count - 1));
            if phase <= 5 {
                renewal_round.store(phase, Ordering::Relaxed);
                std::fs::write(&renew_ready, phase.to_string()).unwrap();
                let updates: Vec<NetworkStart> = tokio::task::spawn_blocking(|| {
                    let mut line = String::new();
                    std::io::stdin().read_line(&mut line).unwrap();
                    serde_json::from_str(&line).expect("twelve renewals via anonymous stdin")
                })
                .await
                .unwrap();
                assert_eq!(updates.len(), MEMBERS);
                for (index, updated) in updates.iter().enumerate() {
                    let original = &original_requests[index];
                    assert!(
                        updated.ticket != previous_requests[index].ticket,
                        "new scoped credential required without logging credentials"
                    );
                    assert_eq!(updated.lease_id, original.lease_id);
                    assert_eq!(updated.private_key, original.private_key);
                    assert_eq!(updated.virtual_ipv4, original.virtual_ipv4);
                    assert_eq!(updated.member_generation, original.member_generation);
                    let (reply, response) = mpsc::channel();
                    commands[index]
                        .send(Command::Renew(updated.clone(), reply))
                        .unwrap();
                    tokio::task::spawn_blocking(move || {
                        response.recv_timeout(Duration::from_secs(3))
                    })
                    .await
                    .unwrap()
                    .unwrap()
                    .unwrap();
                }
                previous_requests = updates;
                tokio::time::sleep(Duration::from_secs(4)).await;
                assert!(
                    pumps.0.iter().all(|pump| !pump.is_finished()),
                    "renewal must preserve every pump"
                );
                println!(
                    "REAL_NETWORK_RENEWED mode={mode} members=12 preserved_keys_ip_lease=true round={phase}"
                );
            }
            if phase == 6 {
                for original in &original_requests {
                    let expiry = OffsetDateTime::parse(
                        &original.expires_at,
                        &time::format_description::well_known::Rfc3339,
                    )
                    .unwrap();
                    assert!(OffsetDateTime::now_utc() > expiry);
                }
                println!("REAL_NETWORK_ORIGINAL_EXPIRED mode={mode} members=12 renewal_rounds=5 all_directions_delivered=true");
                std::fs::write(&ready_path, b"ready").unwrap();
                let ended = tokio::time::timeout(Duration::from_secs(5), &mut pumps.0[MEMBERS - 1])
                    .await
                    .unwrap()
                    .unwrap();
                // Helper 轮询与 Relay 逐包/重连验权并行；两者都必须拒绝旧资格。
                assert!(
                    matches!(
                        ended.as_ref().map_err(String::as_str),
                        Err("NETWORK_GRANT_INVALID"
                            | "RELAY_AUTH_FAILED"
                            | "RELAY_CONNECTION_LOST")
                    ),
                    "departed member must fail authorization or lose relay: {}",
                    diagnostics::exit_code(&ended)
                );
                tokio::time::sleep(Duration::from_millis(1200)).await;
                for source in 0..MEMBERS - 1 {
                    inputs[source]
                        .send(packet(addresses[source], addresses[MEMBERS - 1], 7, 0))
                        .unwrap();
                }
                tokio::time::sleep(Duration::from_millis(150)).await;
                assert!(outputs[MEMBERS - 1].try_recv().is_err());
                if mode == "mixed" {
                    relay.take().unwrap().stop().await;
                    tokio::time::sleep(Duration::from_secs(4)).await;
                    assert!(pumps
                        .0
                        .iter()
                        .take(MEMBERS - 1)
                        .all(|pump| !pump.is_finished()));
                    println!("REAL_NETWORK_RELAY_STOPPED survivors=11 direct_continues=true");
                }
            }
        }
        // 父端更新运营策略后，所有剩余旧策略会话必须停止。
        assert!(
            pumps
                .0
                .iter()
                .take(MEMBERS - 1)
                .all(|pump| !pump.is_finished()),
            "all survivor pumps must still run before policy change"
        );
        std::fs::write(policy_ready, b"ready").unwrap();
        tokio::time::timeout(Duration::from_secs(5), async {
            for (index, pump) in pumps.0.iter_mut().take(MEMBERS - 1).enumerate() {
                let result = pump.await.unwrap();
                assert!(
                    matches!(
                        result.as_ref().map_err(String::as_str),
                        Err("NETWORK_GRANT_INVALID"
                            | "RELAY_AUTH_FAILED"
                            | "RELAY_CONNECTION_LOST")
                    ),
                    "old policy pump member={index} code={}",
                    diagnostics::exit_code(&result)
                );
            }
        })
        .await
        .expect("old policy must stop all eleven data pumps within five seconds");
        println!("REAL_NETWORK_POLICY_REVOKED mode={mode} stopped=11 deadline_seconds=5");
    };
    let outcome = tokio::time::timeout(Duration::from_secs(130), exercise).await;
    if let Some(relay) = relay {
        relay.stop().await;
    }
    outcome.expect("real full-room transfer and revocation deadline");
}
