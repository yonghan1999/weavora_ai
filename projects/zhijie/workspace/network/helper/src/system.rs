use std::future::Future;
use std::net::Ipv4Addr;
use std::sync::{
    atomic::{AtomicBool, AtomicU8, Ordering},
    Arc,
};
use std::sync::{mpsc, Mutex};
use std::thread::JoinHandle;
use std::time::Duration;

use base64::Engine;
use boringtun::x25519::StaticSecret;
use ipnet::Ipv4Net;
use time::OffsetDateTime;
use tokio::net::UdpSocket;
use zhijie_relay::quic::{RelayClient, RelayConfig};

use crate::authority::{AuthorizedNetwork, NetworkAuthority};
use crate::model::{NetworkPath, NetworkPeer, NetworkRoute, NetworkStart};
use crate::router::{PacketAction, TunnelRouter};
use crate::service::NetworkBackend;

#[cfg(windows)]
#[path = "driver_windows.rs"]
mod driver_windows;
#[cfg(windows)]
#[path = "network_windows.rs"]
mod network_windows;

#[cfg(test)]
#[path = "full_room_tests.rs"]
mod full_room_tests;

#[cfg(test)]
#[path = "authority_deadline_tests.rs"]
mod authority_deadline_tests;

#[cfg(test)]
#[path = "authority_responsiveness_tests.rs"]
mod authority_responsiveness_tests;

#[derive(Default)]
pub struct SystemNetworkBackend {
    session: Mutex<Option<SystemNetworkSession>>,
    control_plane_url: Option<String>,
}

impl SystemNetworkBackend {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn with_control_plane_url(url: String) -> Result<Self, String> {
        crate::authority::validate_base_url(&url)?;
        Ok(Self {
            session: Mutex::new(None),
            control_plane_url: Some(url),
        })
    }
}

struct SystemNetworkSession {
    activity_id: String,
    member_generation: u64,
    policy_revision: u64,
    lease_id: String,
    commands: tokio::sync::mpsc::UnboundedSender<Command>,
    worker: JoinHandle<()>,
    connected: Arc<AtomicBool>,
    udp_port: u16,
    active_path: Arc<AtomicU8>,
    failure: Arc<Mutex<Option<String>>>,
}

enum Command {
    Peers(Vec<NetworkPeer>, mpsc::Sender<Result<(), String>>),
    Renew(NetworkStart, mpsc::Sender<Result<(), String>>),
    Stop,
}

impl NetworkBackend for SystemNetworkBackend {
    fn start(&self, request: &NetworkStart) -> Result<(), String> {
        let mut guard = self.session.lock().expect("system network lock");
        if let Some(session) = guard.as_ref() {
            return if session.activity_id == request.activity_id
                && session.member_generation == request.member_generation
                && session.policy_revision == request.policy_revision
                && session.lease_id == request.lease_id
                && !session.worker.is_finished()
            {
                Ok(())
            } else {
                Err("NETWORK_ALREADY_STARTED".into())
            };
        }
        let (commands, receiver) = tokio::sync::mpsc::unbounded_channel();
        let (ready, result) = mpsc::channel();
        let connected = Arc::new(AtomicBool::new(false));
        let worker_connected = connected.clone();
        let active_path = Arc::new(AtomicU8::new(
            if request.allowed_paths.contains(&NetworkPath::Direct) {
                0
            } else {
                1
            },
        ));
        let worker_path = active_path.clone();
        let failure = Arc::new(Mutex::new(None));
        let worker_failure = failure.clone();
        let request = request.clone();
        let activity_id = request.activity_id.clone();
        let member_generation = request.member_generation;
        let policy_revision = request.policy_revision;
        let lease_id = request.lease_id.clone();
        let control_plane_url = self.control_plane_url.clone().unwrap_or_else(|| {
            std::env::var("ZHIJIE_CONTROL_PLANE_URL")
                .unwrap_or_else(|_| "http://127.0.0.1:8080".into())
        });
        let worker = std::thread::spawn(move || {
            let runtime = match tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
            {
                Ok(runtime) => runtime,
                Err(_) => {
                    let _ = ready.send(Err("NETWORK_RUNTIME_FAILED".to_string()));
                    return;
                }
            };
            runtime.block_on(async move {
                let authority = match NetworkAuthority::new(&control_plane_url) {
                    Ok(authority) => authority,
                    Err(code) => {
                        let _ = ready.send(Err(code));
                        return;
                    }
                };
                let authorized_until = tokio::time::Instant::now() + Duration::from_secs(3);
                let authorized = match authority.validate(&request).await {
                    Ok(authorized) => authorized,
                    Err(code) => {
                        let _ = ready.send(Err(code));
                        return;
                    }
                };
                match create_data_plane(&request).await {
                    Ok((device, socket, mut router, expires)) => {
                        if tokio::time::Instant::now() >= authorized_until {
                            let _ = ready.send(Err("CONTROL_PLANE_UNAVAILABLE".into()));
                            return;
                        }
                        if let Err(code) = router.update_peers(authorized.peers) {
                            let _ = ready.send(Err(code));
                            return;
                        }
                        let port = match socket.local_addr() {
                            Ok(address) => address.port(),
                            Err(_) => {
                                let _ = ready.send(Err("NETWORK_SOCKET_FAILED".into()));
                                return;
                            }
                        };
                        if ready.send(Ok(port)).is_ok() {
                            let outcome = run_data_plane(
                                device,
                                socket,
                                router,
                                expires,
                                authorized_until,
                                receiver,
                                authority,
                                request,
                                worker_connected.clone(),
                                worker_path,
                                relay_configuration(),
                            )
                            .await;
                            if let Err(code) = outcome {
                                *worker_failure.lock().expect("network failure lock") = Some(code);
                            }
                            worker_connected.store(false, Ordering::Relaxed);
                        }
                    }
                    Err(code) => {
                        let _ = ready.send(Err(code));
                    }
                }
            });
        });
        match result.recv_timeout(Duration::from_secs(30)) {
            Ok(Ok(udp_port)) => {
                *guard = Some(SystemNetworkSession {
                    activity_id,
                    member_generation,
                    policy_revision,
                    lease_id,
                    commands,
                    worker,
                    connected,
                    udp_port,
                    active_path,
                    failure,
                });
                Ok(())
            }
            outcome => {
                let _ = commands.send(Command::Stop);
                let _ = worker.join();
                Err(outcome
                    .ok()
                    .and_then(Result::err)
                    .unwrap_or("NETWORK_START_FAILED".into()))
            }
        }
    }

    fn update_peers(&self, activity_id: &str, peers: Vec<NetworkPeer>) -> Result<(), String> {
        let guard = self.session.lock().expect("system network lock");
        let session = guard
            .as_ref()
            .filter(|session| session.activity_id == activity_id)
            .ok_or("NETWORK_NOT_STARTED")?;
        let (reply, result) = mpsc::channel();
        session
            .commands
            .send(Command::Peers(peers, reply))
            .map_err(|_| "NETWORK_WORKER_STOPPED")?;
        result
            .recv_timeout(Duration::from_secs(2))
            .map_err(|_| "NETWORK_PEER_UPDATE_FAILED")?
    }

    fn migrate_path(&self, activity_id: &str, path: NetworkPath) -> Result<(), String> {
        let guard = self.session.lock().expect("system network lock");
        let session = guard
            .as_ref()
            .filter(|session| session.activity_id == activity_id)
            .ok_or("NETWORK_NOT_STARTED")?;
        if session.worker.is_finished() {
            return Err(session
                .failure
                .lock()
                .expect("network failure lock")
                .clone()
                .unwrap_or("NETWORK_WORKER_STOPPED".into()));
        }
        if session.active_path.load(Ordering::Relaxed)
            != match path {
                NetworkPath::Direct => 0,
                NetworkPath::Relay => 1,
            }
        {
            return Err("NETWORK_PATH_AUTOMATIC".into());
        }
        Ok(())
    }

    fn stop(&self, activity_id: &str) -> Result<(), String> {
        let mut guard = self.session.lock().expect("system network lock");
        if guard
            .as_ref()
            .is_some_and(|session| session.activity_id != activity_id)
        {
            return Err("ACTIVITY_MISMATCH".into());
        }
        if let Some(session) = guard.take() {
            let _ = session.commands.send(Command::Stop);
            session
                .worker
                .join()
                .map_err(|_| "NETWORK_CLEANUP_FAILED")?;
        }
        Ok(())
    }

    fn health(&self) -> Result<(), String> {
        let guard = self.session.lock().expect("system network lock");
        let session = guard.as_ref().ok_or("NETWORK_NOT_STARTED")?;
        if session.worker.is_finished() {
            return Err(session
                .failure
                .lock()
                .expect("network failure lock")
                .clone()
                .unwrap_or("NETWORK_WORKER_STOPPED".into()));
        }
        Ok(())
    }

    fn connected(&self) -> bool {
        self.session
            .lock()
            .expect("system network lock")
            .as_ref()
            .is_some_and(|session| {
                !session.worker.is_finished() && session.connected.load(Ordering::Relaxed)
            })
    }

    fn udp_port(&self) -> Option<u16> {
        self.session
            .lock()
            .expect("system network lock")
            .as_ref()
            .map(|session| session.udp_port)
    }

    fn active_path(&self) -> Option<NetworkRoute> {
        self.session
            .lock()
            .expect("system network lock")
            .as_ref()
            .map(
                |session| match session.active_path.load(Ordering::Relaxed) {
                    0 => NetworkRoute::Direct,
                    1 => NetworkRoute::Relay,
                    _ => NetworkRoute::Mixed,
                },
            )
    }

    fn renew(&self, request: &NetworkStart) -> Result<(), String> {
        let guard = self.session.lock().expect("system network lock");
        let session = guard.as_ref().ok_or("NETWORK_NOT_STARTED")?;
        let (reply, result) = mpsc::channel();
        session
            .commands
            .send(Command::Renew(request.clone(), reply))
            .map_err(|_| "NETWORK_WORKER_STOPPED")?;
        result
            .recv_timeout(Duration::from_secs(3))
            .map_err(|_| "NETWORK_RENEW_FAILED")?
    }
}

impl Drop for SystemNetworkBackend {
    fn drop(&mut self) {
        if let Some(session) = self.session.get_mut().expect("system network lock").take() {
            let _ = session.commands.send(Command::Stop);
            let _ = session.worker.join();
        }
    }
}

async fn create_data_plane(
    request: &NetworkStart,
) -> Result<(tun::AsyncDevice, UdpSocket, TunnelRouter, OffsetDateTime), String> {
    let key = base64::engine::general_purpose::STANDARD
        .decode(&request.private_key)
        .map_err(|_| "PRIVATE_KEY_INVALID")?;
    let private_key =
        StaticSecret::from(<[u8; 32]>::try_from(key).map_err(|_| "PRIVATE_KEY_INVALID")?);
    let network: Ipv4Net = request
        .activity_cidr
        .parse()
        .map_err(|_| "ACTIVITY_CIDR_INVALID")?;
    let address: Ipv4Addr = request
        .virtual_ipv4
        .parse()
        .map_err(|_| "VIRTUAL_IP_INVALID")?;
    let expires = OffsetDateTime::parse(
        &request.expires_at,
        &time::format_description::well_known::Rfc3339,
    )
    .map_err(|_| "GRANT_EXPIRY_INVALID")?;
    if expires <= OffsetDateTime::now_utc() {
        return Err("GRANT_EXPIRED".into());
    }
    let mut configuration = tun::Configuration::default();
    #[cfg(windows)]
    let _installation_guard = crate::install_windows::verify_installation(
        &std::env::current_exe().map_err(|_| "HELPER_INSTALLATION_UNPROTECTED")?,
    )?;
    #[cfg(windows)]
    network_windows::check_activity_network(network)?;
    configuration
        .address(request.virtual_ipv4.as_str())
        .netmask(network.netmask().to_string())
        // 兼容 QUIC 最小路径 MTU，给内层 WireGuard 与外层 QUIC 留出开销。
        .mtu(1040)
        .up();
    #[cfg(windows)]
    let _driver_guard = configure_windows_tun(&mut configuration)?;
    // Windows 的 destination 会创建默认网关，不能用于活动网段路由。
    let device =
        tun::create_as_async(&configuration).map_err(|_| "TUN_PERMISSION_OR_CREATE_FAILED")?;
    let socket = UdpSocket::bind("0.0.0.0:0")
        .await
        .map_err(|_| "NETWORK_SOCKET_FAILED")?;
    Ok((
        device,
        socket,
        TunnelRouter::new(address, network, private_key),
        expires,
    ))
}

#[cfg(windows)]
fn configure_windows_tun(configuration: &mut tun::Configuration) -> Result<std::fs::File, String> {
    let executable = std::env::current_exe().map_err(|_| "TUN_DRIVER_UNAVAILABLE")?;
    let driver = sibling_driver(&executable)?;
    let guard = driver_windows::verified_driver_guard(&driver)?;
    // 不复用底层默认的通用 wintun 名称，避免配置其他进程已持有的网卡。
    let nonce = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_err(|_| "TUN_PERMISSION_OR_CREATE_FAILED")?
        .as_nanos();
    configuration.tun_name(format!("zhijie-{}-{nonce:x}", std::process::id()));
    configuration.platform_config(|platform| platform.wintun_file(driver.into_os_string()));
    Ok(guard)
}

#[cfg(windows)]
fn sibling_driver(executable: &std::path::Path) -> Result<std::path::PathBuf, String> {
    // 仅选择配套映像目录，不能让当前工作目录或 PATH 决定管理员加载的 DLL。
    let directory = executable.parent().ok_or("TUN_DRIVER_UNAVAILABLE")?;
    let driver = directory.join("wintun.dll");
    let metadata = std::fs::symlink_metadata(&driver).map_err(|_| "TUN_DRIVER_UNAVAILABLE")?;
    if !metadata.is_file() || metadata.file_type().is_symlink() {
        return Err("TUN_DRIVER_UNAVAILABLE".into());
    }
    let directory = std::fs::canonicalize(directory).map_err(|_| "TUN_DRIVER_UNAVAILABLE")?;
    let driver = std::fs::canonicalize(driver).map_err(|_| "TUN_DRIVER_UNAVAILABLE")?;
    if driver.parent() != Some(directory.as_path()) {
        return Err("TUN_DRIVER_UNAVAILABLE".into());
    }
    Ok(driver)
}

trait TunnelDevice {
    fn recv(&self, packet: &mut [u8]) -> impl Future<Output = std::io::Result<usize>>;
    fn send(&self, packet: &[u8]) -> impl Future<Output = std::io::Result<usize>>;
}

impl TunnelDevice for tun::AsyncDevice {
    async fn recv(&self, packet: &mut [u8]) -> std::io::Result<usize> {
        self.recv(packet).await
    }
    async fn send(&self, packet: &[u8]) -> std::io::Result<usize> {
        self.send(packet).await
    }
}

struct AuthorityCheck {
    result: std::pin::Pin<Box<dyn Future<Output = Result<AuthorizedNetwork, String>> + Send>>,
    valid_until: tokio::time::Instant,
}

impl AuthorityCheck {
    fn new(authority: &NetworkAuthority, request: &NetworkStart, expires: OffsetDateTime) -> Self {
        let valid_until = tokio::time::Instant::now() + Duration::from_secs(3);
        let authority = authority.clone();
        let request = request.clone();
        Self {
            result: Box::pin(async move { authority.validate_until(&request, expires).await }),
            valid_until,
        }
    }
}

struct PendingRenewal {
    check: AuthorityCheck,
    request: NetworkStart,
    reply: mpsc::Sender<Result<(), String>>,
}

async fn run_data_plane(
    device: impl TunnelDevice,
    socket: UdpSocket,
    mut router: TunnelRouter,
    mut expires: OffsetDateTime,
    mut authorized_until: tokio::time::Instant,
    mut commands: tokio::sync::mpsc::UnboundedReceiver<Command>,
    authority: NetworkAuthority,
    mut request: NetworkStart,
    connected: Arc<AtomicBool>,
    active_path: Arc<AtomicU8>,
    relay_config: Result<RelayConfig, String>,
) -> Result<(), String> {
    let mut tunnel_buffer = vec![0; 65535];
    let mut udp_buffer = vec![0; 65535];
    let mut timer = tokio::time::interval(Duration::from_millis(100));
    let mut authorization_timer = tokio::time::interval(Duration::from_secs(1));
    authorization_timer.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    let mut authorization: Option<AuthorityCheck> = None;
    let mut peer_reply: Option<mpsc::Sender<Result<(), String>>> = None;
    let mut renewal: Option<PendingRenewal> = None;
    let mut relay = if !request.allowed_paths.contains(&NetworkPath::Direct) {
        router.set_path(NetworkPath::Relay);
        Some(connect_relay(&relay_config, &request.ticket).await?)
    } else {
        None
    };
    let mut relay_retry_at = tokio::time::Instant::now();
    let mut relay_connect: Option<
        std::pin::Pin<Box<dyn Future<Output = Result<RelayClient, String>> + Send>>,
    > = None;
    loop {
        if OffsetDateTime::now_utc() >= expires {
            if let Some(pending) = renewal.take() {
                let _ = pending.reply.send(Err("GRANT_EXPIRED".into()));
            }
            if let Some(reply) = peer_reply.take() {
                let _ = reply.send(Err("GRANT_EXPIRED".into()));
            }
            return Err("GRANT_EXPIRED".into());
        }
        if tokio::time::Instant::now() >= authorized_until {
            if let Some(pending) = renewal.take() {
                let _ = pending.reply.send(Err("CONTROL_PLANE_UNAVAILABLE".into()));
            }
            if let Some(reply) = peer_reply.take() {
                let _ = reply.send(Err("CONTROL_PLANE_UNAVAILABLE".into()));
            }
            return Err("CONTROL_PLANE_UNAVAILABLE".into());
        }
        let actions = tokio::select! {
            command = commands.recv() => match command {
                Some(Command::Renew(updated, reply)) => {
                    if renewal.is_some() {
                        let _ = reply.send(Err("NETWORK_RENEW_FAILED".into()));
                    } else {
                        renewal = Some(PendingRenewal {
                            check: AuthorityCheck::new(&authority, &updated, expires),
                            request: updated,
                            reply,
                        });
                    }
                    continue;
                }
                Some(Command::Peers(_peers, reply)) => {
                    if peer_reply.is_some() {
                        let _ = reply.send(Err("NETWORK_PEER_UPDATE_FAILED".into()));
                    } else {
                        peer_reply = Some(reply);
                        if authorization.is_none() {
                            authorization = Some(AuthorityCheck::new(&authority, &request, expires));
                        }
                    }
                    continue;
                }
                Some(Command::Stop) | None => break,
            },
            result = async {
                match renewal.as_mut() {
                    Some(pending) => pending.check.result.as_mut().await,
                    None => std::future::pending().await,
                }
            } => {
                if OffsetDateTime::now_utc() >= expires || tokio::time::Instant::now() >= authorized_until {
                    continue;
                }
                let pending = renewal.take().unwrap();
                match result {
                    Ok(current) => {
                        let result = router.update_peers(current.peers);
                        if result.is_ok() {
                            relay_connect = None;
                            if pending.request.allowed_paths.contains(&NetworkPath::Relay) && relay_config.is_ok() {
                                let config = relay_config.clone();
                                let ticket = pending.request.ticket.clone();
                                relay_connect = Some(Box::pin(async move { connect_relay(&config, &ticket).await }));
                            }
                            expires = OffsetDateTime::parse(&pending.request.expires_at, &time::format_description::well_known::Rfc3339).map_err(|_| "GRANT_EXPIRY_INVALID")?;
                            request = pending.request;
                            authorized_until = pending.check.valid_until;
                            authorization = None;
                            authorization_timer.reset();
                            if let Some(reply) = peer_reply.take() { let _ = reply.send(Ok(())); }
                        }
                        let _ = pending.reply.send(result);
                    }
                    Err(code) => {
                        let expired = code == "GRANT_EXPIRED";
                        let _ = pending.reply.send(Err(code));
                        if expired { return Err("GRANT_EXPIRED".into()); }
                    }
                }
                continue;
            },
            result = async {
                match authorization.as_mut() {
                    Some(check) => check.result.as_mut().await,
                    None => std::future::pending().await,
                }
            } => {
                if OffsetDateTime::now_utc() >= expires || tokio::time::Instant::now() >= authorized_until {
                    continue;
                }
                let check = authorization.take().unwrap();
                let result = result.and_then(|current| router.update_peers(current.peers));
                if let Some(reply) = peer_reply.take() { let _ = reply.send(result.clone()); }
                result?;
                authorized_until = check.valid_until;
                continue;
            },
            _ = tokio::time::sleep_until(authorized_until) => continue,
            _ = timer.tick() => {
                if OffsetDateTime::now_utc() >= expires { continue; }
                let failed = router.direct_failures();
                if !failed.is_empty() {
                    if !request.allowed_paths.contains(&NetworkPath::Relay) {
                        if !router.connected() { return Err("DIRECT_UNAVAILABLE".into()); }
                    } else {
                        if relay.is_some() {
                            for peer in failed { router.fallback(peer); }
                        }
                    }
                }
                if relay.is_none() && request.allowed_paths.contains(&NetworkPath::Relay)
                    && (relay_config.is_ok() || !router.direct_failures().is_empty() || router.uses_relay())
                    && relay_connect.is_none() && tokio::time::Instant::now() >= relay_retry_at {
                    let config = relay_config.clone();
                    let ticket = request.ticket.clone();
                    // 重连与 TUN/UDP 并行推进，TLS 超时不能阻塞有效直连。
                    relay_connect = Some(Box::pin(async move { connect_relay(&config, &ticket).await }));
                }
                router.tick()
            },
            result = async {
                match relay_connect.as_mut() {
                    Some(connecting) => connecting.await,
                    None => std::future::pending().await,
                }
            } => {
                relay_connect = None;
                relay_retry_at = tokio::time::Instant::now() + Duration::from_secs(3);
                match result {
                    Ok(client) => {
                        relay = Some(client);
                        for peer in router.direct_failures() { router.fallback(peer); }
                    }
                    Err(code) if !router.connected() && (router.uses_relay() || !router.direct_failures().is_empty()) => return Err(code),
                    Err(_) => {},
                }
                Vec::new()
            },
            _ = authorization_timer.tick() => {
                if authorization.is_none() {
                    authorization = Some(AuthorityCheck::new(&authority, &request, expires));
                }
                continue;
            },
            received = device.recv(&mut tunnel_buffer) => {
                let length = received.map_err(|_| "TUN_READ_FAILED")?;
                router.outbound(&tunnel_buffer[..length])
            },
            received = socket.recv_from(&mut udp_buffer) => {
                let (length, source) = match received {
                    Ok(packet) => packet,
                    // UDP 对端端口关闭的 ICMP 反馈不能终止其他有效 Peer。
                    Err(error) if matches!(error.kind(), std::io::ErrorKind::ConnectionReset | std::io::ErrorKind::ConnectionRefused) => continue,
                    Err(_) => return Err("NETWORK_RECEIVE_FAILED".into()),
                };
                router.inbound(source, &udp_buffer[..length])
            },
            received = async {
                match relay.as_ref() {
                    Some(relay) => relay.receive().await,
                    None => std::future::pending().await,
                }
            } => {
                let (source, packet) = match received {
                    Ok(packet) => packet,
                    Err(code) if router.uses_relay() && !router.connected() => return Err(code),
                    Err(_) => {
                        relay = None;
                        // 续票连接会替换旧 QUIC 连接；沿用同一 Peer 的 WireGuard 会话，
                        // 避免旧连接先关闭时重置密钥状态，丢失已完成握手的对端报文。
                        if relay_connect.is_none() { router.relay_lost(); }
                        continue;
                    }
                };
                router.inbound_relay(source, &packet)
            },
        };
        connected.store(router.connected(), Ordering::Relaxed);
        active_path.store(router.path_code(), Ordering::Relaxed);
        let relay_replacing = relay_connect.is_some();
        tokio::time::timeout_at(
            authorized_until,
            bounded_packet_write(expires, async {
                for action in actions {
                    if OffsetDateTime::now_utc() >= expires {
                        return Err("GRANT_EXPIRED".to_string());
                    }
                    if tokio::time::Instant::now() >= authorized_until {
                        return Err("CONTROL_PLANE_UNAVAILABLE".into());
                    }
                    match action {
                        PacketAction::Network(endpoint, packet) => {
                            socket
                                .send_to(&packet, endpoint)
                                .await
                                .map_err(|_| "NETWORK_SEND_FAILED".to_string())?;
                        }
                        PacketAction::Tunnel(packet) => {
                            device
                                .send(&packet)
                                .await
                                .map_err(|_| "TUN_WRITE_FAILED".to_string())?;
                        }
                        PacketAction::Relay(target, packet) => {
                            if let Some(client) = relay.as_ref() {
                                if let Err(code) = client.send(target, &packet) {
                                    if !relay_replacing {
                                        router.relay_lost();
                                        if !router.connected() {
                                            return Err(code);
                                        }
                                    }
                                    relay = None;
                                }
                            }
                        }
                    }
                }
                Ok(())
            }),
        )
        .await
        .map_err(|_| "CONTROL_PLANE_UNAVAILABLE".to_string())??;
    }
    Ok(())
}

fn relay_configuration() -> Result<RelayConfig, String> {
    let address = std::env::var("ZHIJIE_RELAY_ADDRESS")
        .map_err(|_| "RELAY_PATH_UNAVAILABLE")?
        .parse()
        .map_err(|_| "RELAY_ADDRESS_INVALID")?;
    let server_name =
        std::env::var("ZHIJIE_RELAY_SERVER_NAME").map_err(|_| "RELAY_SERVER_NAME_REQUIRED")?;
    let ca = std::env::var("ZHIJIE_RELAY_CA_DER")
        .ok()
        .map(std::fs::read)
        .transpose()
        .map_err(|_| "RELAY_CA_READ_FAILED")?;
    Ok(RelayConfig {
        address,
        server_name,
        extra_ca: ca,
    })
}

async fn connect_relay(
    config: &Result<RelayConfig, String>,
    ticket: &str,
) -> Result<RelayClient, String> {
    config.as_ref().map_err(Clone::clone)?.connect(ticket).await
}

async fn bounded_packet_write(
    expires: OffsetDateTime,
    write: impl Future<Output = Result<(), String>>,
) -> Result<(), String> {
    let remaining = expires - OffsetDateTime::now_utc();
    if remaining <= time::Duration::ZERO {
        return Err("GRANT_EXPIRED".into());
    }
    // 为整个批次限时，避免 TUN/UDP 背压阻塞退出、续票和权威校验。
    // 超时终止会话并释放网卡，不在未知写入状态下继续使用旧资格。
    let budget = remaining.unsigned_abs().min(Duration::from_millis(100));
    tokio::time::timeout(budget, write)
        .await
        .map_err(|_| "NETWORK_WRITE_TIMEOUT".to_string())?
}

#[cfg(test)]
mod tests {
    use super::*;

    #[cfg(windows)]
    #[tokio::test]
    async fn production_tun_creation_rejects_unprotected_installation_before_driver_load() {
        // 此测试运行在用户可写构建目录；必须在驱动加载和网卡创建之前返回。
        let request = crate::tests::start();
        assert!(matches!(
            create_data_plane(&request).await,
            Err(error) if error == "HELPER_INSTALLATION_UNPROTECTED"
        ));
    }

    #[cfg(windows)]
    #[test]
    fn driver_lookup_requires_a_file_in_the_helper_directory() {
        let directory = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../target")
            .join(format!("driver-location-{}", std::process::id()));
        std::fs::create_dir(&directory).unwrap();
        let result = std::panic::catch_unwind(|| {
            let executable = directory.join("zhijie-helper.exe");
            assert_eq!(
                sibling_driver(&executable).unwrap_err(),
                "TUN_DRIVER_UNAVAILABLE"
            );
            std::fs::create_dir(directory.join("wintun.dll")).unwrap();
            assert!(sibling_driver(&executable).is_err());
            std::fs::remove_dir(directory.join("wintun.dll")).unwrap();
            // 只验证位置选择，不加载这个夹具，也不把它当作有效驱动。
            std::fs::write(directory.join("wintun.dll"), b"location fixture").unwrap();
            assert_eq!(
                sibling_driver(&executable).unwrap(),
                std::fs::canonicalize(directory.join("wintun.dll")).unwrap()
            );
        });
        let driver = directory.join("wintun.dll");
        if driver.is_file() {
            std::fs::remove_file(&driver).unwrap();
        }
        if driver.is_dir() {
            std::fs::remove_dir(&driver).unwrap();
        }
        std::fs::remove_dir(&directory).unwrap();
        if let Err(error) = result {
            std::panic::resume_unwind(error);
        }
    }

    struct PendingWrite(Arc<AtomicBool>);

    impl Future for PendingWrite {
        type Output = Result<(), String>;

        fn poll(
            self: std::pin::Pin<&mut Self>,
            _: &mut std::task::Context<'_>,
        ) -> std::task::Poll<Self::Output> {
            std::task::Poll::Pending
        }
    }

    impl Drop for PendingWrite {
        fn drop(&mut self) {
            self.0.store(true, Ordering::Relaxed);
        }
    }

    #[tokio::test]
    async fn stalled_tunnel_write_is_cancelled_and_releases_owned_resources() {
        let dropped = Arc::new(AtomicBool::new(false));
        let result = tokio::time::timeout(
            Duration::from_secs(1),
            bounded_packet_write(
                OffsetDateTime::now_utc() + time::Duration::minutes(1),
                PendingWrite(dropped.clone()),
            ),
        )
        .await
        .expect("backpressure must not block worker cleanup");
        assert_eq!(result.unwrap_err(), "NETWORK_WRITE_TIMEOUT");
        assert!(dropped.load(Ordering::Relaxed));
    }

    #[tokio::test]
    async fn expired_grant_cannot_begin_writing_even_when_sink_is_ready() {
        let written = AtomicBool::new(false);
        let result = bounded_packet_write(
            OffsetDateTime::now_utc() - time::Duration::seconds(1),
            async {
                written.store(true, Ordering::Relaxed);
                Ok(())
            },
        )
        .await;
        assert_eq!(result.unwrap_err(), "GRANT_EXPIRED");
        assert!(!written.load(Ordering::Relaxed));
    }

    struct ControlledTunnel {
        input: tokio::sync::Mutex<tokio::sync::mpsc::UnboundedReceiver<Vec<u8>>>,
        output: tokio::sync::mpsc::UnboundedSender<Vec<u8>>,
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
            Ok(data.len())
        }
        async fn send(&self, packet: &[u8]) -> std::io::Result<usize> {
            self.output
                .send(packet.to_vec())
                .map_err(|_| std::io::ErrorKind::BrokenPipe)?;
            Ok(packet.len())
        }
    }

    struct TestRelayAuthority {
        allowed: Arc<AtomicBool>,
    }

    impl zhijie_relay::server::GrantValidator for TestRelayAuthority {
        fn validate(
            &self,
            ticket: &str,
            _: std::net::SocketAddr,
        ) -> Result<zhijie_relay::model::RelayGrant, String> {
            if !self.allowed.load(Ordering::Relaxed) {
                return Err("revoked".into());
            }
            Ok(zhijie_relay::model::RelayGrant {
                version: 1,
                ticket: ticket.into(),
                activity_id: "activity".into(),
                activity_generation: 1,
                member_generation: 1,
                lease_id: format!("lease-{ticket}"),
                device_id: format!("device-{ticket}"),
                user_id: ticket.into(),
                source_ipv4: match ticket {
                    "a" => "10.231.0.1",
                    "b" => "10.231.0.2",
                    "c" => "10.231.0.3",
                    _ => return Err("unknown ticket".into()),
                }
                .into(),
                policy_revision: 1,
                allow_relay: true,
                expires_at_unix_millis: (std::time::SystemTime::now() + Duration::from_secs(60))
                    .duration_since(std::time::UNIX_EPOCH)
                    .unwrap()
                    .as_millis() as u64,
            })
        }
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn actual_data_pumps_follow_policy_fallback_and_stop_on_authority_revocation() {
        use boringtun::x25519::PublicKey;
        use std::io::{Read, Write};
        use zhijie_relay::quic::QuicRelayServer;
        for paths in [
            vec![NetworkPath::Direct, NetworkPath::Relay],
            vec![NetworkPath::Relay],
            vec![NetworkPath::Direct],
        ] {
            let allowed = Arc::new(AtomicBool::new(true));
            let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
            listener.set_nonblocking(true).unwrap();
            let url = format!("http://{}", listener.local_addr().unwrap());
            let socket_a = UdpSocket::bind("127.0.0.1:0").await.unwrap();
            let socket_b = UdpSocket::bind("127.0.0.1:0").await.unwrap();
            // 真实 UDP 黑洞候选保留端口，但不参与握手，强制触发直连失败。
            let blackhole_a = std::net::UdpSocket::bind("127.0.0.1:0").unwrap();
            let blackhole_b = std::net::UdpSocket::bind("127.0.0.1:0").unwrap();
            blackhole_a.set_nonblocking(true).unwrap();
            blackhole_b.set_nonblocking(true).unwrap();
            let expiry = OffsetDateTime::now_utc() + time::Duration::seconds(30);
            let make_request = |ticket: &str, index: u8| NetworkStart {
                protocol_version: 1,
                request_id: ticket.into(),
                ticket: ticket.into(),
                activity_id: "activity".into(),
                activity_generation: 1,
                member_generation: 1,
                device_id: ticket.into(),
                lease_id: format!("lease-{ticket}"),
                policy_revision: 1,
                allowed_paths: paths.clone(),
                private_key: base64::engine::general_purpose::STANDARD.encode([index; 32]),
                virtual_ipv4: format!("10.231.0.{index}"),
                activity_cidr: "10.231.0.0/27".into(),
                expires_at: expiry
                    .format(&time::format_description::well_known::Rfc3339)
                    .unwrap(),
            };
            let a = make_request("a", 1);
            let b = make_request("b", 2);
            let peer =
                |request: &NetworkStart, index: u8, endpoint: std::net::SocketAddr| NetworkPeer {
                    user_id: request.ticket.clone(),
                    member_generation: request.member_generation,
                    lease_id: request.lease_id.clone(),
                    expires_at: request.expires_at.clone(),
                    virtual_ipv4: request.virtual_ipv4.clone(),
                    public_key: base64::engine::general_purpose::STANDARD
                        .encode(PublicKey::from(&StaticSecret::from([index; 32])).to_bytes()),
                    endpoint: if paths.contains(&NetworkPath::Direct) {
                        Some(endpoint.to_string())
                    } else {
                        None
                    },
                };
            let peers_a = vec![peer(&b, 2, blackhole_b.local_addr().unwrap())];
            let peers_b = vec![peer(&a, 1, blackhole_a.local_addr().unwrap())];
            let response = |request: &NetworkStart, index: u8, peers: &[NetworkPeer]| {
                serde_json::json!({
                "activityId": request.activity_id, "activityGeneration": 1, "memberGeneration": 1,
                "deviceId": request.device_id, "leaseId": request.lease_id, "policyRevision": 1,
                "allowedPaths": request.allowed_paths, "virtualIpv4": request.virtual_ipv4, "activityCidr": request.activity_cidr,
                "publicKey": base64::engine::general_purpose::STANDARD.encode(PublicKey::from(&StaticSecret::from([index;32])).to_bytes()),
                "expiresAt": request.expires_at, "peers": peers,
            }).to_string()
            };
            let body_a = response(&a, 1, &peers_a);
            let body_b = response(&b, 2, &peers_b);
            let http_running = Arc::new(AtomicBool::new(true));
            let server_running = http_running.clone();
            let server_allowed = allowed.clone();
            let http = std::thread::spawn(move || {
                while server_running.load(Ordering::Relaxed) {
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
                    let mut bytes = Vec::new();
                    let mut buffer = [0; 2048];
                    loop {
                        let count = stream.read(&mut buffer).unwrap();
                        if count == 0 {
                            break;
                        }
                        bytes.extend_from_slice(&buffer[..count]);
                        if let Some(end) = bytes.windows(4).position(|value| value == b"\r\n\r\n") {
                            let headers = String::from_utf8_lossy(&bytes[..end]);
                            let length: usize = headers
                                .lines()
                                .find_map(|line| {
                                    line.to_lowercase()
                                        .strip_prefix("content-length:")
                                        .map(|value| value.trim().parse().unwrap())
                                })
                                .unwrap();
                            if bytes.len() >= end + 4 + length {
                                break;
                            }
                        }
                    }
                    let body = if String::from_utf8_lossy(&bytes).contains("\"ticket\":\"a\"") {
                        &body_a
                    } else {
                        &body_b
                    };
                    let (status, body) = if server_allowed.load(Ordering::Relaxed) {
                        ("200 OK", body.as_str())
                    } else {
                        ("403 Forbidden", "{}")
                    };
                    let _ = write!(stream, "HTTP/1.1 {status}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len());
                }
            });
            let cert = rcgen::generate_simple_self_signed(vec!["localhost".into()]).unwrap();
            let der = cert.cert.der().to_vec();
            let relay = QuicRelayServer::bind_der(
                "127.0.0.1:0".parse().unwrap(),
                der.clone(),
                cert.signing_key.serialize_der(),
                Arc::new(TestRelayAuthority {
                    allowed: allowed.clone(),
                }),
            )
            .unwrap();
            let config = RelayConfig {
                address: relay.local_addr(),
                server_name: "localhost".into(),
                extra_ca: Some(der),
            };
            let make_router = |index, peers| {
                let mut router = TunnelRouter::new(
                    format!("10.231.0.{index}").parse().unwrap(),
                    "10.231.0.0/27".parse().unwrap(),
                    StaticSecret::from([index; 32]),
                );
                router.update_peers(peers).unwrap();
                router
            };
            let (input_a, receive_a) = tokio::sync::mpsc::unbounded_channel();
            let (input_b, receive_b) = tokio::sync::mpsc::unbounded_channel();
            let (output_a, _capture_a) = tokio::sync::mpsc::unbounded_channel();
            let (output_b, mut capture_b) = tokio::sync::mpsc::unbounded_channel();
            let (_commands_a, commands_a) = tokio::sync::mpsc::unbounded_channel();
            let (_commands_b, commands_b) = tokio::sync::mpsc::unbounded_channel();
            let path_a = Arc::new(AtomicU8::new(if paths.contains(&NetworkPath::Direct) {
                0
            } else {
                1
            }));
            let path_b = Arc::new(AtomicU8::new(if paths.contains(&NetworkPath::Direct) {
                0
            } else {
                1
            }));
            let pump_a = run_data_plane(
                ControlledTunnel {
                    input: tokio::sync::Mutex::new(receive_a),
                    output: output_a,
                },
                socket_a,
                make_router(1, peers_a),
                expiry,
                tokio::time::Instant::now() + Duration::from_secs(3),
                commands_a,
                NetworkAuthority::new(&url).unwrap(),
                a,
                Arc::new(AtomicBool::new(false)),
                path_a.clone(),
                Ok(config.clone()),
            );
            let pump_b = run_data_plane(
                ControlledTunnel {
                    input: tokio::sync::Mutex::new(receive_b),
                    output: output_b,
                },
                socket_b,
                make_router(2, peers_b),
                expiry,
                tokio::time::Instant::now() + Duration::from_secs(3),
                commands_b,
                NetworkAuthority::new(&url).unwrap(),
                b,
                Arc::new(AtomicBool::new(false)),
                path_b.clone(),
                Ok(config),
            );
            let relay_allowed = paths.contains(&NetworkPath::Relay);
            let exercise = async {
                // 持有双方输入端，避免断开的测试通道提前终止数据泵。
                let _hold = input_b;
                if !relay_allowed {
                    tokio::time::sleep(Duration::from_secs(4)).await;
                    return;
                }
                tokio::time::timeout(Duration::from_secs(5), async {
                    while path_a.load(Ordering::Relaxed) != 1 || path_b.load(Ordering::Relaxed) != 1
                    {
                        tokio::time::sleep(Duration::from_millis(10)).await;
                    }
                })
                .await
                .unwrap();
                let mut packet = vec![0; 36];
                packet[0] = 0x45;
                packet[2..4].copy_from_slice(&36u16.to_be_bytes());
                packet[12..16].copy_from_slice(&[10, 231, 0, 1]);
                packet[16..20].copy_from_slice(&[10, 231, 0, 2]);
                packet[20..].copy_from_slice(b"controlled-game!");
                input_a.send(packet.clone()).unwrap();
                assert_eq!(
                    tokio::time::timeout(Duration::from_secs(3), capture_b.recv())
                        .await
                        .unwrap()
                        .unwrap(),
                    packet
                );
                allowed.store(false, Ordering::Relaxed);
                tokio::time::sleep(Duration::from_secs(2)).await;
            };
            let result = tokio::time::timeout(Duration::from_secs(10), async {
                tokio::join!(pump_a, pump_b, exercise)
            })
            .await;
            http_running.store(false, Ordering::Relaxed);
            http.join().unwrap();
            relay.stop().await;
            let (a, b, _) = result.expect("data pumps must stop within deadline");
            if relay_allowed {
                assert!(a.is_err() && b.is_err());
            } else {
                assert_eq!(a.unwrap_err(), "DIRECT_UNAVAILABLE");
                assert_eq!(b.unwrap_err(), "DIRECT_UNAVAILABLE");
            }
            let mut buffer = [0; 2048];
            assert_eq!(
                blackhole_a.recv(&mut buffer).is_ok(),
                paths.contains(&NetworkPath::Direct)
            );
        }
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn three_data_pumps_fallback_only_the_unreachable_peer_and_preserve_direct_delivery() {
        use boringtun::x25519::PublicKey;
        use std::io::{Read, Write};
        use zhijie_relay::quic::QuicRelayServer;
        let allowed = Arc::new(AtomicBool::new(true));
        let sockets = [
            UdpSocket::bind("127.0.0.1:0").await.unwrap(),
            UdpSocket::bind("127.0.0.1:0").await.unwrap(),
            UdpSocket::bind("127.0.0.1:0").await.unwrap(),
        ];
        let endpoints: Vec<_> = sockets
            .iter()
            .map(|socket| socket.local_addr().unwrap())
            .collect();
        let blackhole = std::net::UdpSocket::bind("127.0.0.1:0").unwrap();
        let expiry = OffsetDateTime::now_utc() + time::Duration::seconds(30);
        let requests: Vec<_> = (1..=3u8)
            .map(|index| NetworkStart {
                protocol_version: 1,
                request_id: index.to_string(),
                ticket: ["a", "b", "c"][index as usize - 1].into(),
                activity_id: "activity".into(),
                activity_generation: 1,
                member_generation: 1,
                device_id: index.to_string(),
                lease_id: format!("lease-{index}"),
                policy_revision: 1,
                allowed_paths: vec![NetworkPath::Direct, NetworkPath::Relay],
                private_key: base64::engine::general_purpose::STANDARD.encode([index; 32]),
                virtual_ipv4: format!("10.231.0.{index}"),
                activity_cidr: "10.231.0.0/27".into(),
                expires_at: expiry
                    .format(&time::format_description::well_known::Rfc3339)
                    .unwrap(),
            })
            .collect();
        let peers: Vec<Vec<NetworkPeer>> = (0..3)
            .map(|local| {
                (0..3)
                    .filter(|remote| *remote != local)
                    .map(|remote| NetworkPeer {
                        user_id: requests[remote].ticket.clone(),
                        member_generation: requests[remote].member_generation,
                        lease_id: requests[remote].lease_id.clone(),
                        expires_at: requests[remote].expires_at.clone(),
                        virtual_ipv4: requests[remote].virtual_ipv4.clone(),
                        public_key: base64::engine::general_purpose::STANDARD.encode(
                            PublicKey::from(&StaticSecret::from([remote as u8 + 1; 32])).to_bytes(),
                        ),
                        // A-B 可直连；所有涉及 C 的直连候选为实际 UDP 黑洞。
                        endpoint: Some(
                            if local == 2 || remote == 2 {
                                blackhole.local_addr().unwrap()
                            } else {
                                endpoints[remote]
                            }
                            .to_string(),
                        ),
                    })
                    .collect()
            })
            .collect();
        // C 的两个黑洞候选必须是不同端点，符合生产 Peer 校验约束。
        let other_blackhole = std::net::UdpSocket::bind("127.0.0.1:0").unwrap();
        let mut peers = peers;
        peers[2][1].endpoint = Some(other_blackhole.local_addr().unwrap().to_string());
        let bodies: Vec<_> = requests.iter().zip(&peers).enumerate().map(|(index, (request, peers))| serde_json::json!({
            "activityId": request.activity_id, "activityGeneration": 1, "memberGeneration": 1,
            "deviceId": request.device_id, "leaseId": request.lease_id, "policyRevision": 1,
            "allowedPaths": request.allowed_paths, "virtualIpv4": request.virtual_ipv4, "activityCidr": request.activity_cidr,
            "publicKey": base64::engine::general_purpose::STANDARD.encode(PublicKey::from(&StaticSecret::from([index as u8 + 1; 32])).to_bytes()),
            "expiresAt": request.expires_at, "peers": peers,
        }).to_string()).collect();
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        listener.set_nonblocking(true).unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let http_running = Arc::new(AtomicBool::new(true));
        let running = http_running.clone();
        let http_allowed = allowed.clone();
        let http = std::thread::spawn(move || {
            while running.load(Ordering::Relaxed) {
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
                let mut bytes = Vec::new();
                let mut buffer = [0; 2048];
                loop {
                    let count = stream.read(&mut buffer).unwrap();
                    if count == 0 {
                        break;
                    }
                    bytes.extend_from_slice(&buffer[..count]);
                    if let Some(end) = bytes.windows(4).position(|value| value == b"\r\n\r\n") {
                        let headers = String::from_utf8_lossy(&bytes[..end]);
                        let length: usize = headers
                            .lines()
                            .find_map(|line| {
                                line.to_lowercase()
                                    .strip_prefix("content-length:")
                                    .map(|value| value.trim().parse().unwrap())
                            })
                            .unwrap();
                        if bytes.len() >= end + 4 + length {
                            break;
                        }
                    }
                }
                let request: serde_json::Value = serde_json::from_slice(
                    &bytes[bytes.windows(4).position(|v| v == b"\r\n\r\n").unwrap() + 4..],
                )
                .unwrap();
                let index = ["a", "b", "c"]
                    .iter()
                    .position(|ticket| request["ticket"] == *ticket)
                    .unwrap();
                let (status, body) = if http_allowed.load(Ordering::Relaxed) {
                    ("200 OK", bodies[index].as_str())
                } else {
                    ("403 Forbidden", "{}")
                };
                let _ = write!(stream, "HTTP/1.1 {status}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len());
            }
        });
        let cert = rcgen::generate_simple_self_signed(vec!["localhost".into()]).unwrap();
        let relay = QuicRelayServer::bind_der(
            "127.0.0.1:0".parse().unwrap(),
            cert.cert.der().to_vec(),
            cert.signing_key.serialize_der(),
            Arc::new(TestRelayAuthority {
                allowed: allowed.clone(),
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
        let mut paths = Vec::new();
        let mut pumps = Vec::new();
        let mut relay = Some(relay);
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
            let path = Arc::new(AtomicU8::new(0));
            pumps.push(tokio::spawn(run_data_plane(
                ControlledTunnel {
                    input: tokio::sync::Mutex::new(receive),
                    output,
                },
                socket,
                router,
                expiry,
                tokio::time::Instant::now() + Duration::from_secs(3),
                receiver,
                NetworkAuthority::new(&url).unwrap(),
                request,
                Arc::new(AtomicBool::new(false)),
                path.clone(),
                Ok(config.clone()),
            )));
            inputs.push(input);
            outputs.push(capture);
            commands.push(command);
            paths.push(path);
        }
        let exercise = tokio::time::timeout(Duration::from_secs(15), async {
            let packet = |source, destination| {
                let mut packet = vec![0; 36];
                packet[0] = 0x45;
                packet[2..4].copy_from_slice(&36u16.to_be_bytes());
                packet[12..16].copy_from_slice(&[10, 231, 0, source]);
                packet[16..20].copy_from_slice(&[10, 231, 0, destination]);
                packet[20..].copy_from_slice(b"controlled-game!");
                packet
            };
            // 超时前后 A-B 均可传输；自动回退后 A/C 和 B/C 双向传输。
            inputs[0].send(packet(1, 2)).unwrap();
            assert_eq!(outputs[1].recv().await.unwrap(), packet(1, 2));
            while paths[0].load(Ordering::Relaxed) != 2
                || paths[1].load(Ordering::Relaxed) != 2
                || paths[2].load(Ordering::Relaxed) != 1
            {
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
            for (source, destination) in [(1, 2), (2, 1), (1, 3), (3, 1), (2, 3), (3, 2)] {
                inputs[source - 1]
                    .send(packet(source as u8, destination as u8))
                    .unwrap();
                assert_eq!(
                    outputs[destination - 1].recv().await.unwrap(),
                    packet(source as u8, destination as u8)
                );
            }
            relay.take().unwrap().stop().await;
            tokio::time::sleep(Duration::from_secs(4)).await;
            assert!(!pumps[0].is_finished() && !pumps[1].is_finished());
            for (source, destination) in [(1, 2), (2, 1)] {
                inputs[source - 1]
                    .send(packet(source as u8, destination as u8))
                    .unwrap();
                assert_eq!(
                    outputs[destination - 1].recv().await.unwrap(),
                    packet(source as u8, destination as u8)
                );
            }
            // 全中继 C 无可用路径后退出；A/B 继续直连，直到权威撤销。
            assert!(tokio::time::timeout(Duration::from_secs(2), &mut pumps[2])
                .await
                .unwrap()
                .unwrap()
                .is_err());
            allowed.store(false, Ordering::Relaxed);
            for pump in &mut pumps[..2] {
                assert!(tokio::time::timeout(Duration::from_secs(2), pump)
                    .await
                    .unwrap()
                    .unwrap()
                    .is_err());
            }
        })
        .await;
        for pump in &pumps {
            pump.abort();
        }
        http_running.store(false, Ordering::Relaxed);
        http.join().unwrap();
        if let Some(relay) = relay {
            relay.stop().await;
        }
        exercise.expect("mixed data pumps must complete and stop after revocation");
    }
}
