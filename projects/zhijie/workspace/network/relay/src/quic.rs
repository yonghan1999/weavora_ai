use std::collections::HashMap;
use std::net::{Ipv4Addr, SocketAddr};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use quinn::{Connection, Endpoint};
use rustls::pki_types::{CertificateDer, PrivateKeyDer};
use tokio::task::JoinSet;

use crate::model::RelayGrant;
use crate::server::GrantValidator;

// IPv4 + WireGuard 帧；小于 QUIC 最小路径 MTU 可用载荷，禁止无界缓冲。
pub const MAX_CIPHERTEXT: usize = 1120;
const MAX_CONNECTIONS: usize = 128;
type Sessions = Arc<Mutex<HashMap<(String, String), Session>>>;

#[derive(Clone)]
struct Session {
    grant: RelayGrant,
    connection: Connection,
}

pub struct QuicRelayServer {
    endpoint: Endpoint,
    worker: Option<tokio::task::JoinHandle<()>>,
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
        let worker = tokio::spawn(async move {
            let sessions: Sessions = Arc::default();
            let mut workers = JoinSet::new();
            loop {
                tokio::select! {
                    incoming = listener.accept() => {
                        let Some(incoming) = incoming else { break; };
                        if workers.len() >= MAX_CONNECTIONS { incoming.refuse(); continue; }
                        let sessions = sessions.clone();
                        let validator = validator.clone();
                        workers.spawn(async move {
                            if let Ok(Ok(connection)) = tokio::time::timeout(Duration::from_secs(3), incoming).await {
                                serve(connection, sessions, validator).await;
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
        })
    }

    pub fn local_addr(&self) -> SocketAddr {
        self.endpoint.local_addr().expect("relay address")
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
) -> Result<RelayGrant, String> {
    tokio::task::spawn_blocking(move || validator.validate(&ticket, endpoint))
        .await
        .map_err(|_| "RELAY_VALIDATION_FAILED".to_string())?
}

async fn serve(connection: Connection, sessions: Sessions, validator: Arc<dyn GrantValidator>) {
    let authenticated = tokio::time::timeout(Duration::from_secs(3), async {
        let (mut send, mut receive) = connection.accept_bi().await.map_err(|_| ())?;
        let ticket = receive.read_to_end(512).await.map_err(|_| ())?;
        let ticket = String::from_utf8(ticket).map_err(|_| ())?;
        let grant = validate(
            validator.clone(),
            ticket.clone(),
            connection.remote_address(),
        )
        .await
        .map_err(|_| ())?;
        if !grant.allow_relay || grant.ticket != ticket {
            return Err(());
        }
        send.write_all(b"OK").await.map_err(|_| ())?;
        send.finish().map_err(|_| ())?;
        Ok(grant)
    })
    .await;
    let Ok(Ok(grant)) = authenticated else {
        connection.close(1u8.into(), b"authorization failed");
        return;
    };
    let key = (grant.activity_id.clone(), grant.source_ipv4.clone());
    let previous = sessions.lock().expect("quic sessions").insert(
        key.clone(),
        Session {
            grant: grant.clone(),
            connection: connection.clone(),
        },
    );
    if let Some(previous) = previous {
        previous.connection.close(0u8.into(), b"superseded");
    }
    let mut recheck = tokio::time::interval(Duration::from_secs(1));
    loop {
        let packet = tokio::select! {
            packet = connection.read_datagram() => match packet { Ok(packet) => Some(packet), Err(_) => break },
            _ = recheck.tick() => None,
        };
        if validate(
            validator.clone(),
            grant.ticket.clone(),
            connection.remote_address(),
        )
        .await
        .as_ref()
            != Ok(&grant)
        {
            break;
        }
        let Some(packet) = packet else {
            continue;
        };
        if packet.len() < 5 || packet.len() > 4 + MAX_CIPHERTEXT {
            continue;
        }
        let target_ip = Ipv4Addr::new(packet[0], packet[1], packet[2], packet[3]).to_string();
        if target_ip == grant.source_ipv4 {
            continue;
        }
        let target = sessions
            .lock()
            .expect("quic sessions")
            .get(&(grant.activity_id.clone(), target_ip))
            .cloned();
        let Some(target) = target else {
            continue;
        };
        if target.grant.lease_id == grant.lease_id {
            continue;
        }
        if validate(
            validator.clone(),
            target.grant.ticket.clone(),
            target.connection.remote_address(),
        )
        .await
        .as_ref()
            != Ok(&target.grant)
        {
            target
                .connection
                .close(1u8.into(), b"authorization expired");
            continue;
        }
        let Ok(source) = grant.source_ipv4.parse::<Ipv4Addr>() else {
            break;
        };
        let mut forwarded = Vec::with_capacity(packet.len());
        forwarded.extend_from_slice(&source.octets());
        forwarded.extend_from_slice(&packet[4..]);
        let _ = target.connection.send_datagram(forwarded.into());
    }
    connection.close(0u8.into(), b"session ended");
    let mut sessions = sessions.lock().expect("quic sessions");
    if sessions
        .get(&key)
        .is_some_and(|item| item.connection.stable_id() == connection.stable_id())
    {
        sessions.remove(&key);
    }
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
