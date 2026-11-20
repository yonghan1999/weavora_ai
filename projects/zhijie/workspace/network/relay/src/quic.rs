use std::net::{Ipv4Addr, SocketAddr};
use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use quinn::{Connection, Endpoint};
use rustls::pki_types::{CertificateDer, PrivateKeyDer};
use tokio::sync::Semaphore;
use tokio::task::JoinSet;

use crate::model::RelayGrant;
use crate::revocation::{RelayRevocations, Session};
use crate::server::GrantValidator;

// IPv4 + WireGuard 帧；小于 QUIC 最小路径 MTU 可用载荷，禁止无界缓冲。
pub const MAX_CIPHERTEXT: usize = 1120;
const MAX_CONNECTIONS: usize = 128;
const MAX_VALIDATIONS: usize = 32;
const AUTHORIZATION_LIFETIME: Duration = Duration::from_secs(3);
pub struct QuicRelayServer {
    endpoint: Endpoint,
    worker: Option<tokio::task::JoinHandle<()>>,
    revocations: RelayRevocations,
}

impl QuicRelayServer {
    pub fn bind_der(
        address: SocketAddr,
        certificate: Vec<u8>,
        key: Vec<u8>,
        validator: Arc<dyn GrantValidator>,
    ) -> Result<Self, String> {
        Self::bind(
            address,
            vec![CertificateDer::from(certificate)],
            rustls::pki_types::PrivatePkcs8KeyDer::from(key).into(),
            validator,
        )
    }
    pub fn bind(
        address: SocketAddr,
        certificates: Vec<CertificateDer<'static>>,
        key: PrivateKeyDer<'static>,
        validator: Arc<dyn GrantValidator>,
    ) -> Result<Self, String> {
        let mut config = quinn::ServerConfig::with_single_cert(certificates, key)
            .map_err(|_| "RELAY_TLS_CONFIGURATION_INVALID")?;
        let transport = Arc::get_mut(&mut config.transport).expect("new transport");
        transport.max_concurrent_bidi_streams(1u8.into());
        transport.max_concurrent_uni_streams(0u8.into());
        transport.datagram_receive_buffer_size(Some(64 * 1024));
        transport.datagram_send_buffer_size(64 * 1024);
        transport.max_idle_timeout(Some(Duration::from_secs(10).try_into().unwrap()));
        let endpoint = Endpoint::server(config, address).map_err(|_| "RELAY_BIND_FAILED")?;
        let listener = endpoint.clone();
        let revocations = RelayRevocations::default();
        let sessions = revocations.clone();
        let validations = Arc::new(Semaphore::new(MAX_VALIDATIONS));
        let worker = tokio::spawn(async move {
            let mut workers = JoinSet::new();
            loop {
                tokio::select! {
                    incoming = listener.accept() => {
                        let Some(incoming) = incoming else { break; };
                        if workers.len() >= MAX_CONNECTIONS { incoming.refuse(); continue; }
                        let sessions = sessions.clone();
                        let validator = validator.clone();
                        let validations = validations.clone();
                        workers.spawn(async move {
                            if let Ok(Ok(connection)) = tokio::time::timeout(Duration::from_secs(3), incoming).await {
                                serve(connection, sessions, validator, validations).await;
                            }
                        });
                    }
                    _ = workers.join_next(), if !workers.is_empty() => {}
                }
            }
            workers.abort_all();
            while workers.join_next().await.is_some() {}
        });
        Ok(Self {
            endpoint,
            worker: Some(worker),
            revocations,
        })
    }

    pub fn local_addr(&self) -> SocketAddr {
        self.endpoint.local_addr().expect("relay address")
    }

    pub fn revocations(&self) -> RelayRevocations {
        self.revocations.clone()
    }

    pub async fn stop(mut self) {
        self.endpoint.close(0u8.into(), b"shutdown");
        if let Some(worker) = self.worker.take() {
            let _ = worker.await;
        }
        self.endpoint.wait_idle().await;
    }
}

impl Drop for QuicRelayServer {
    fn drop(&mut self) {
        self.endpoint.close(0u8.into(), b"shutdown");
        if let Some(worker) = self.worker.take() {
            worker.abort();
        }
    }
}

async fn validate(
    validator: Arc<dyn GrantValidator>,
    ticket: String,
    endpoint: SocketAddr,
    validations: Arc<Semaphore>,
) -> Result<RelayGrant, String> {
    let permit = validations
        .acquire_owned()
        .await
        .map_err(|_| "RELAY_VALIDATION_CAPACITY")?;
    tokio::task::spawn_blocking(move || {
        let _permit = permit;
        validator.validate(&ticket, endpoint)
    })
    .await
    .map_err(|_| "RELAY_VALIDATION_FAILED".to_string())?
}

fn authorization_deadline(grant: &RelayGrant, started: Instant) -> Result<Instant, ()> {
    if grant.version != 1
        || !grant.allow_relay
        || grant.activity_generation == 0
        || grant.member_generation == 0
        || grant.policy_revision == 0
        || [
            &grant.device_id,
            &grant.lease_id,
            &grant.user_id,
            &grant.activity_id,
        ]
        .iter()
        .any(|value| value.is_empty())
        || grant.source_ipv4.parse::<Ipv4Addr>().is_err()
    {
        return Err(());
    }
    let observed = Instant::now();
    let elapsed = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|_| ())?;
    let remaining = Duration::from_millis(grant.expires_at_unix_millis)
        .checked_sub(elapsed)
        .ok_or(())?;
    let deadline =
        (observed + remaining.min(AUTHORIZATION_LIFETIME)).min(started + AUTHORIZATION_LIFETIME);
    if deadline <= Instant::now() {
        return Err(());
    }
    Ok(deadline)
}

async fn serve(
    connection: Connection,
    sessions: RelayRevocations,
    validator: Arc<dyn GrantValidator>,
    validations: Arc<Semaphore>,
) {
    let authenticated = tokio::time::timeout(Duration::from_secs(3), async {
        let (send, mut receive) = connection.accept_bi().await.map_err(|_| ())?;
        let ticket = receive.read_to_end(512).await.map_err(|_| ())?;
        let ticket = String::from_utf8(ticket).map_err(|_| ())?;
        let started = Instant::now();
        let grant = validate(
            validator.clone(),
            ticket.clone(),
            connection.remote_address(),
            validations.clone(),
        )
        .await
        .map_err(|_| ())?;
        if grant.ticket != ticket {
            return Err(());
        }
        let deadline = authorization_deadline(&grant, started)?;
        Ok((grant, send, deadline))
    })
    .await;
    let Ok(Ok((grant, mut send, mut authorized_until))) = authenticated else {
        connection.close(1u8.into(), b"authorization failed");
        return;
    };
    let key = (grant.activity_id.clone(), grant.source_ipv4.clone());
    let inserted = sessions.state.lock().expect("quic sessions").insert(
        key.clone(),
        Session {
            grant: grant.clone(),
            connection: connection.clone(),
            authorized_until,
        },
    );
    if inserted.is_err() {
        connection.close(1u8.into(), b"authorization revoked");
        return;
    }
    if send.write_all(b"OK").await.is_err() || send.finish().is_err() {
        connection.close(1u8.into(), b"authorization failed");
        sessions
            .state
            .lock()
            .expect("quic sessions")
            .remove(&key, &connection);
        return;
    }
    let mut recheck = tokio::time::interval_at(
        tokio::time::Instant::now() + Duration::from_secs(1),
        Duration::from_secs(1),
    );
    recheck.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    let mut refresh: std::pin::Pin<
        Box<dyn std::future::Future<Output = Result<(RelayGrant, Instant), ()>> + Send>,
    > = Box::pin(std::future::pending());
    let mut refreshing = false;
    loop {
        let packet = tokio::select! {
            biased;
            _ = tokio::time::sleep_until(authorized_until.into()) => break,
            result = &mut refresh, if refreshing => {
                let Ok((updated, deadline)) = result else { break; };
                if !grant.same_session(&updated) || sessions.state.lock().expect("quic sessions")
                    .refresh(updated, &connection, deadline).is_err() { break; }
                authorized_until = deadline;
                refresh = Box::pin(std::future::pending());
                refreshing = false;
                continue;
            },
            _ = recheck.tick(), if !refreshing => {
                let validator = validator.clone();
                let ticket = grant.ticket.clone();
                let endpoint = connection.remote_address();
                let validations = validations.clone();
                refresh = Box::pin(async move {
                    let started = Instant::now();
                    let updated = validate(validator, ticket, endpoint, validations).await.map_err(|_| ())?;
                    let deadline = authorization_deadline(&updated, started)?;
                    Ok((updated, deadline))
                });
                refreshing = true;
                continue;
            },
            packet = connection.read_datagram() => match packet { Ok(packet) => packet, Err(_) => break },
        };
        if !sessions
            .state
            .lock()
            .expect("quic sessions")
            .authorized(&grant, &connection)
        {
            break;
        }
        if packet.len() < 5 || packet.len() > 4 + MAX_CIPHERTEXT {
            continue;
        }
        let target_ip = Ipv4Addr::new(packet[0], packet[1], packet[2], packet[3]).to_string();
        if target_ip == grant.source_ipv4 {
            continue;
        }
        let target = sessions
            .state
            .lock()
            .expect("quic sessions")
            .target(&(grant.activity_id.clone(), target_ip));
        let Some(target) = target else {
            continue;
        };
        if target.grant.lease_id == grant.lease_id
            || target.grant.activity_generation != grant.activity_generation
            || target.grant.policy_revision != grant.policy_revision
        {
            continue;
        }
        let Ok(source) = grant.source_ipv4.parse::<Ipv4Addr>() else {
            break;
        };
        let mut forwarded = Vec::with_capacity(packet.len());
        forwarded.extend_from_slice(&source.octets());
        forwarded.extend_from_slice(&packet[4..]);
        let state = sessions.state.lock().expect("quic sessions");
        if !state.authorized(&grant, &connection) {
            break;
        }
        if state.authorized(&target.grant, &target.connection) {
            let _ = target.connection.send_datagram(forwarded.into());
        }
    }
    connection.close(0u8.into(), b"session ended");
    sessions
        .state
        .lock()
        .expect("quic sessions")
        .remove(&key, &connection);
}

#[derive(Clone)]
pub struct RelayConfig {
    pub address: SocketAddr,
    pub server_name: String,
    pub extra_ca: Option<Vec<u8>>,
}

impl RelayConfig {
    pub async fn connect(&self, ticket: &str) -> Result<RelayClient, String> {
        RelayClient::connect(
            self.address,
            &self.server_name,
            self.extra_ca.clone(),
            ticket,
        )
        .await
    }
}

pub struct RelayClient {
    endpoint: Endpoint,
    connection: Connection,
}

impl RelayClient {
    pub async fn connect(
        address: SocketAddr,
        server_name: &str,
        extra_ca: Option<Vec<u8>>,
        ticket: &str,
    ) -> Result<Self, String> {
        let mut roots = rustls::RootCertStore::empty();
        roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
        if let Some(certificate) = extra_ca {
            roots
                .add(CertificateDer::from(certificate))
                .map_err(|_| "RELAY_CA_INVALID")?;
        }
        let mut endpoint =
            Endpoint::client("0.0.0.0:0".parse().unwrap()).map_err(|_| "RELAY_SOCKET_FAILED")?;
        let mut config = quinn::ClientConfig::with_root_certificates(Arc::new(roots))
            .map_err(|_| "RELAY_TLS_CONFIGURATION_INVALID")?;
        let mut transport = quinn::TransportConfig::default();
        transport.keep_alive_interval(Some(Duration::from_secs(2)));
        transport.datagram_receive_buffer_size(Some(64 * 1024));
        transport.datagram_send_buffer_size(64 * 1024);
        config.transport_config(Arc::new(transport));
        endpoint.set_default_client_config(config);
        let result = tokio::time::timeout(Duration::from_secs(3), async {
            let connection = endpoint
                .connect(address, server_name)
                .map_err(|_| "RELAY_TLS_CONFIGURATION_INVALID")?
                .await
                .map_err(|_| "RELAY_CONNECTION_FAILED")?;
            let (mut send, mut receive) = connection
                .open_bi()
                .await
                .map_err(|_| "RELAY_CONNECTION_FAILED")?;
            send.write_all(ticket.as_bytes())
                .await
                .map_err(|_| "RELAY_AUTH_FAILED")?;
            send.finish().map_err(|_| "RELAY_AUTH_FAILED")?;
            if receive
                .read_to_end(2)
                .await
                .map_err(|_| "RELAY_AUTH_FAILED")?
                != b"OK"
            {
                return Err("RELAY_AUTH_FAILED");
            }
            Ok(connection)
        })
        .await
        .map_err(|_| "RELAY_CONNECTION_TIMEOUT")?;
        Ok(Self {
            endpoint,
            connection: result.map_err(str::to_string)?,
        })
    }

    pub fn send(&self, target: Ipv4Addr, ciphertext: &[u8]) -> Result<(), String> {
        if ciphertext.is_empty() || ciphertext.len() > MAX_CIPHERTEXT {
            return Err("RELAY_PACKET_SIZE_INVALID".into());
        }
        let mut packet = Vec::with_capacity(4 + ciphertext.len());
        packet.extend_from_slice(&target.octets());
        packet.extend_from_slice(ciphertext);
        self.connection
            .send_datagram(packet.into())
            .map_err(|_| "RELAY_SEND_FAILED".into())
    }

    pub async fn receive(&self) -> Result<(Ipv4Addr, Vec<u8>), String> {
        let packet = self
            .connection
            .read_datagram()
            .await
            .map_err(|_| "RELAY_CONNECTION_LOST")?;
        if packet.len() < 5 || packet.len() > 4 + MAX_CIPHERTEXT {
            return Err("RELAY_PACKET_SIZE_INVALID".into());
        }
        Ok((
            Ipv4Addr::new(packet[0], packet[1], packet[2], packet[3]),
            packet[4..].to_vec(),
        ))
    }
}

impl Drop for RelayClient {
    fn drop(&mut self) {
        self.endpoint.close(0u8.into(), b"client shutdown");
    }
}

#[cfg(test)]
mod authorization_tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};

    fn grant() -> RelayGrant {
        RelayGrant {
            version: 1,
            ticket: "ticket".into(),
            activity_id: "activity".into(),
            activity_generation: 1,
            member_generation: 1,
            lease_id: "lease".into(),
            device_id: "device".into(),
            user_id: "user".into(),
            source_ipv4: "10.231.0.1".into(),
            policy_revision: 1,
            allow_relay: true,
            expires_at_unix_millis: (SystemTime::now() + Duration::from_secs(60))
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_millis() as u64,
        }
    }

    #[test]
    fn deadline_is_bounded_from_request_start_and_never_revives_expired_results() {
        let started = Instant::now();
        let valid = grant();
        assert!(
            authorization_deadline(&valid, started).unwrap() <= started + AUTHORIZATION_LIFETIME
        );
        let mut short = valid.clone();
        short.expires_at_unix_millis = (SystemTime::now() + Duration::from_millis(500))
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_millis() as u64;
        assert!(
            authorization_deadline(&short, started).unwrap()
                <= Instant::now() + Duration::from_millis(500)
        );
        short.expires_at_unix_millis = 1;
        assert!(authorization_deadline(&short, started).is_err());
        assert!(authorization_deadline(&valid, started - AUTHORIZATION_LIFETIME).is_err());
        for change in [
            (|value: &mut RelayGrant| value.version = 2) as fn(&mut RelayGrant),
            |value: &mut RelayGrant| value.activity_generation = 0,
            |value: &mut RelayGrant| value.device_id.clear(),
            |value: &mut RelayGrant| value.allow_relay = false,
            |value: &mut RelayGrant| value.source_ipv4 = "not-an-ip".into(),
        ] {
            let mut invalid = valid.clone();
            change(&mut invalid);
            assert!(authorization_deadline(&invalid, started).is_err());
        }
    }

    struct BoundedAuthority {
        active: AtomicUsize,
        peak: AtomicUsize,
        release: (std::sync::Mutex<bool>, std::sync::Condvar),
    }

    impl GrantValidator for BoundedAuthority {
        fn validate(&self, _: &str, _: SocketAddr) -> Result<RelayGrant, String> {
            let active = self.active.fetch_add(1, Ordering::SeqCst) + 1;
            self.peak.fetch_max(active, Ordering::SeqCst);
            let _ = self
                .release
                .1
                .wait_timeout_while(
                    self.release.0.lock().unwrap(),
                    Duration::from_secs(5),
                    |released| !*released,
                )
                .unwrap();
            self.active.fetch_sub(1, Ordering::SeqCst);
            Ok(grant())
        }
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn cancelled_validation_keeps_capacity_until_blocking_authority_returns() {
        let authority = Arc::new(BoundedAuthority {
            active: AtomicUsize::new(0),
            peak: AtomicUsize::new(0),
            release: (std::sync::Mutex::new(false), std::sync::Condvar::new()),
        });
        let capacity = Arc::new(Semaphore::new(MAX_VALIDATIONS));
        let mut workers = JoinSet::new();
        for _ in 0..40 {
            workers.spawn(validate(
                authority.clone(),
                "ticket".into(),
                "127.0.0.1:10000".parse().unwrap(),
                capacity.clone(),
            ));
        }
        let saturated = tokio::time::timeout(Duration::from_secs(2), async {
            while authority.active.load(Ordering::SeqCst) != MAX_VALIDATIONS {
                tokio::time::sleep(Duration::from_millis(5)).await;
            }
        })
        .await;
        workers.abort_all();
        while workers.join_next().await.is_some() {}
        let permits = capacity.available_permits();
        *authority.release.0.lock().unwrap() = true;
        authority.release.1.notify_all();
        saturated.unwrap();
        assert_eq!(permits, 0);
        assert_eq!(authority.peak.load(Ordering::SeqCst), MAX_VALIDATIONS);
        tokio::time::timeout(Duration::from_secs(2), async {
            while capacity.available_permits() != MAX_VALIDATIONS {
                tokio::time::sleep(Duration::from_millis(5)).await;
            }
        })
        .await
        .unwrap();
    }
}
