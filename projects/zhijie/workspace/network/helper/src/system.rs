use std::net::UdpSocket;
use std::sync::Mutex;

use base64::Engine;
use boringtun::noise::{Tunn, TunnResult};
use boringtun::x25519::{PublicKey, StaticSecret};
use ipnet::Ipv4Net;

use crate::model::{NetworkPath, NetworkPeer, NetworkStart};
use crate::service::NetworkBackend;

pub struct SystemNetworkBackend {
    session: Mutex<Option<SystemNetworkSession>>,
}

impl SystemNetworkBackend {
    pub fn new() -> Self {
        Self {
            session: Mutex::new(None),
        }
    }
}

struct SystemNetworkSession {
    device: tun::Device,
    socket: UdpSocket,
    private_key: StaticSecret,
    peers: Vec<PeerTunnel>,
    active_path: NetworkPath,
}

struct PeerTunnel {
    virtual_ipv4: String,
    public_key: PublicKey,
    endpoint: Option<std::net::SocketAddr>,
    tunn: Tunn,
}

impl NetworkBackend for SystemNetworkBackend {
    fn start(&self, request: &NetworkStart) -> Result<(), String> {
        let mut guard = self.session.lock().expect("system network lock");
        if guard.is_some() {
            return Ok(());
        }
        let key = base64::engine::general_purpose::STANDARD
            .decode(&request.private_key)
            .map_err(|_| "PRIVATE_KEY_INVALID".to_string())?;
        let private_key =
            StaticSecret::from(<[u8; 32]>::try_from(key).map_err(|_| "PRIVATE_KEY_INVALID")?);
        let peers = tunnel_peers(&private_key, Vec::new())?;
        let socket =
            UdpSocket::bind("0.0.0.0:0").map_err(|_| "NETWORK_SOCKET_FAILED".to_string())?;
        socket
            .set_nonblocking(true)
            .map_err(|_| "NETWORK_SOCKET_FAILED".to_string())?;
        let network: Ipv4Net = request
            .activity_cidr
            .parse()
            .map_err(|_| "ACTIVITY_CIDR_INVALID")?;
        let mut configuration = tun::Configuration::default();
        configuration
            .address(request.virtual_ipv4.as_str())
            .netmask(network.netmask().to_string())
            .destination(network.network().to_string())
            .mtu(1380)
            .up();
        let device = tun::create(&configuration)
            .map_err(|_| "TUN_PERMISSION_OR_CREATE_FAILED".to_string())?;
        *guard = Some(SystemNetworkSession {
            device,
            socket,
            private_key,
            peers,
            active_path: request
                .allowed_paths
                .first()
                .copied()
                .unwrap_or(NetworkPath::Direct),
        });
        Ok(())
    }

    fn update_peers(&self, _activity_id: &str, peers: Vec<NetworkPeer>) -> Result<(), String> {
        let mut guard = self.session.lock().expect("system network lock");
        let session = guard.as_mut().ok_or("NETWORK_NOT_STARTED")?;
        session.peers = tunnel_peers(&session.private_key, peers)?;
        Ok(())
    }

    fn migrate_path(&self, _activity_id: &str, path: NetworkPath) -> Result<(), String> {
        let mut guard = self.session.lock().expect("system network lock");
        let session = guard.as_mut().ok_or("NETWORK_NOT_STARTED")?;
        session.active_path = path;
        Ok(())
    }

    fn stop(&self, _activity_id: &str) -> Result<(), String> {
        self.session.lock().expect("system network lock").take();
        Ok(())
    }
}

impl SystemNetworkSession {
    pub fn encapsulate(
        &mut self,
        target_ip: &str,
        packet: &[u8],
    ) -> Result<Option<Vec<u8>>, String> {
        let peer = self
            .peers
            .iter_mut()
            .find(|peer| peer.virtual_ipv4 == target_ip)
            .ok_or("PEER_NOT_ALLOWED")?;
        let mut output = vec![0u8; packet.len() + 160];
        match peer.tunn.encapsulate(packet, &mut output) {
            TunnResult::WriteToNetwork(data) => Ok(Some(data.to_vec())),
            TunnResult::Done => Ok(None),
            TunnResult::Err(error) => Err(format!("WIREGUARD_ENCAPSULATION_FAILED:{error:?}")),
            _ => Ok(None),
        }
    }
}

fn tunnel_peers(
    private_key: &StaticSecret,
    peers: Vec<NetworkPeer>,
) -> Result<Vec<PeerTunnel>, String> {
    peers
        .into_iter()
        .enumerate()
        .map(|(index, peer)| {
            let bytes = base64::engine::general_purpose::STANDARD
                .decode(peer.public_key)
                .map_err(|_| "PEER_PUBLIC_KEY_INVALID".to_string())?;
            let public_key = PublicKey::from(
                <[u8; 32]>::try_from(bytes).map_err(|_| "PEER_PUBLIC_KEY_INVALID")?,
            );
            let endpoint = peer
                .endpoint
                .map(|value| {
                    value
                        .parse()
                        .map_err(|_| "PEER_ENDPOINT_INVALID".to_string())
                })
                .transpose()?;
            Ok(PeerTunnel {
                virtual_ipv4: peer.virtual_ipv4,
                public_key,
                endpoint,
                tunn: Tunn::new(
                    private_key.clone(),
                    public_key,
                    None,
                    None,
                    index as u32,
                    None,
                ),
            })
        })
        .collect()
}
