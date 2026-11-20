use std::collections::HashSet;
use std::net::{Ipv4Addr, SocketAddr};
use std::time::{Duration, Instant};

use base64::Engine;
use boringtun::noise::{Tunn, TunnResult};
use boringtun::x25519::{PublicKey, StaticSecret};
use ipnet::Ipv4Net;

use crate::model::{NetworkPath, NetworkPeer};

const DIRECT_HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(3);
const PEER_IDLE_TIMEOUT: Duration = Duration::from_secs(35);

pub(crate) enum PacketAction {
    Network(SocketAddr, Vec<u8>),
    Relay(Ipv4Addr, Vec<u8>),
    Tunnel(Vec<u8>),
}

struct Peer {
    user_id: String,
    member_generation: u64,
    lease_id: String,
    authorized_until: Instant,
    address: Ipv4Addr,
    key: [u8; 32],
    endpoint: Option<SocketAddr>,
    tunnel: Tunn,
    relay_tunnel: Option<Tunn>,
    path: NetworkPath,
    started: Instant,
    last_authenticated: Option<Instant>,
}

pub(crate) struct TunnelRouter {
    address: Ipv4Addr,
    network: Ipv4Net,
    private_key: StaticSecret,
    peers: Vec<Peer>,
    next_index: u32,
    path: NetworkPath,
}

impl TunnelRouter {
    pub fn new(address: Ipv4Addr, network: Ipv4Net, private_key: StaticSecret) -> Self {
        Self {
            address,
            network,
            private_key,
            peers: Vec::new(),
            next_index: 1,
            path: NetworkPath::Direct,
        }
    }

    pub fn update_peers(&mut self, peers: Vec<NetworkPeer>) -> Result<(), String> {
        if peers.len() > 11 {
            return Err("PEER_LIMIT_EXCEEDED".into());
        }
        let mut addresses = HashSet::new();
        let mut users = HashSet::new();
        let mut keys = HashSet::new();
        let mut endpoints = HashSet::new();
        let mut validated = Vec::new();
        for peer in peers {
            if peer.user_id.is_empty()
                || peer.lease_id.is_empty()
                || peer.member_generation == 0
                || !users.insert(peer.user_id.clone())
            {
                return Err("PEER_IDENTITY_INVALID".into());
            }
            if self.peers.iter().any(|previous| {
                previous.user_id == peer.user_id
                    && previous.member_generation > peer.member_generation
            }) {
                return Err("PEER_GENERATION_STALE".into());
            }
            let observed = Instant::now();
            let expires = time::OffsetDateTime::parse(
                &peer.expires_at,
                &time::format_description::well_known::Rfc3339,
            )
            .map_err(|_| "PEER_EXPIRY_INVALID")?;
            let remaining = expires - time::OffsetDateTime::now_utc();
            if remaining <= time::Duration::ZERO {
                continue;
            }
            let authorized_until = observed
                .checked_add(remaining.unsigned_abs())
                .ok_or("PEER_EXPIRY_INVALID")?;
            let address: Ipv4Addr = peer.virtual_ipv4.parse().map_err(|_| "PEER_IP_INVALID")?;
            if !self.network.contains(&address)
                || address == self.address
                || address == self.network.network()
                || address == self.network.broadcast()
                || !addresses.insert(address)
            {
                return Err("PEER_IP_INVALID".into());
            }
            let key: [u8; 32] = base64::engine::general_purpose::STANDARD
                .decode(&peer.public_key)
                .map_err(|_| "PEER_PUBLIC_KEY_INVALID")?
                .try_into()
                .map_err(|_| "PEER_PUBLIC_KEY_INVALID")?;
            if key == [0; 32]
                || key == PublicKey::from(&self.private_key).to_bytes()
                || !keys.insert(key)
            {
                return Err("PEER_PUBLIC_KEY_INVALID".into());
            }
            let endpoint = peer
                .endpoint
                .as_ref()
                .map(|value| value.parse::<SocketAddr>())
                .transpose()
                .map_err(|_| "PEER_ENDPOINT_INVALID")?;
            if let Some(endpoint) = endpoint {
                if endpoint.port() == 0
                    || endpoint.ip().is_unspecified()
                    || endpoint.ip().is_multicast()
                    || !endpoints.insert(endpoint)
                {
                    return Err("PEER_ENDPOINT_INVALID".into());
                }
            }
            validated.push((peer, address, key, endpoint, authorized_until));
        }
        // 先完整校验；错误输入不能删除仍有效的 Peer。
        let mut previous = std::mem::take(&mut self.peers);
        for (identity, address, key, endpoint, authorized_until) in validated {
            if let Some(index) = previous.iter().position(|peer| {
                peer.address == address
                    && peer.key == key
                    && peer.endpoint == endpoint
                    && peer.user_id == identity.user_id
                    && peer.member_generation == identity.member_generation
                    && peer.lease_id == identity.lease_id
                    && peer.authorized_until > Instant::now()
            }) {
                let mut peer = previous.swap_remove(index);
                peer.authorized_until = authorized_until;
                self.peers.push(peer);
            } else {
                let tunnel = Tunn::new(
                    self.private_key.clone(),
                    PublicKey::from(key),
                    None,
                    Some(15),
                    self.next_index,
                    None,
                );
                self.next_index = self.next_index.wrapping_add(1);
                self.peers.push(Peer {
                    user_id: identity.user_id,
                    member_generation: identity.member_generation,
                    lease_id: identity.lease_id,
                    authorized_until,
                    address,
                    key,
                    endpoint,
                    tunnel,
                    relay_tunnel: None,
                    path: self.path,
                    started: Instant::now(),
                    last_authenticated: None,
                });
            }
        }
        if self.path == NetworkPath::Relay {
            for index in 0..self.peers.len() {
                self.ensure_relay_tunnel(index);
            }
        }
        Ok(())
    }

    pub fn outbound(&mut self, packet: &[u8]) -> Vec<PacketAction> {
        self.remove_expired();
        let Some((source, destination)) = ipv4_addresses(packet) else {
            return Vec::new();
        };
        // 只接收本机活动 IP 发出的流量，不能转发物理局域网或默认路由。
        if source != self.address {
            return Vec::new();
        }
        let Some(peer) = self
            .peers
            .iter_mut()
            .find(|peer| peer.address == destination)
        else {
            return Vec::new();
        };
        let mut buffer = vec![0; 65535 + 160];
        let path = peer.path;
        match Self::active_tunnel(peer).encapsulate(packet, &mut buffer) {
            TunnResult::WriteToNetwork(data) => Self::network_action(path, peer, data.to_vec()),
            _ => Vec::new(),
        }
    }

    pub fn inbound(&mut self, endpoint: SocketAddr, packet: &[u8]) -> Vec<PacketAction> {
        self.remove_expired();
        let Some(index) = self
            .peers
            .iter()
            .position(|peer| peer.endpoint == Some(endpoint) && peer.path == NetworkPath::Direct)
        else {
            return Vec::new();
        };
        self.decapsulate(index, NetworkPath::Direct, packet)
    }

    pub fn inbound_relay(&mut self, source: Ipv4Addr, packet: &[u8]) -> Vec<PacketAction> {
        self.remove_expired();
        let Some(index) = self.peers.iter().position(|peer| peer.address == source) else {
            return Vec::new();
        };
        self.ensure_relay_tunnel(index);
        self.decapsulate(index, NetworkPath::Relay, packet)
    }

    fn decapsulate(&mut self, index: usize, path: NetworkPath, packet: &[u8]) -> Vec<PacketAction> {
        let peer = &mut self.peers[index];
        let mut actions = Vec::new();
        let mut buffer = vec![0; 65535 + 160];
        let mut input = packet;
        // 解握手后排空 boringtun 缓存；固定上限防止单个数据报独占循环。
        for _ in 0..256 {
            if peer.authorized_until <= Instant::now() {
                break;
            }
            let source = if path == NetworkPath::Direct {
                peer.endpoint.map(|endpoint| endpoint.ip())
            } else {
                None
            };
            let tunnel = if path == NetworkPath::Relay {
                peer.relay_tunnel.as_mut().unwrap()
            } else {
                &mut peer.tunnel
            };
            let result = tunnel.decapsulate(source, input, &mut buffer);
            // 响应或会话数据才能确认往返路径；单向 initiation、cookie 与排队输出不算。
            let authenticated = match &result {
                TunnResult::WriteToNetwork(data) => matches!(
                    (input.get(..4), data.get(..4)),
                    (Some([2, 0, 0, 0]), Some([4, 0, 0, 0]))
                ),
                TunnResult::WriteToTunnelV4(..) => !input.is_empty(),
                TunnResult::Done => input.len() == 32 && input[..4] == [4, 0, 0, 0],
                _ => false,
            };
            if authenticated {
                peer.path = path;
                peer.last_authenticated = Some(Instant::now());
            }
            match result {
                TunnResult::WriteToNetwork(data) => {
                    actions.extend(Self::network_action(path, peer, data.to_vec()))
                }
                TunnResult::WriteToTunnelV4(data, _) => {
                    if peer.authorized_until > Instant::now()
                        && ipv4_addresses(data) == Some((peer.address, self.address))
                    {
                        actions.push(PacketAction::Tunnel(data.to_vec()));
                    }
                }
                _ => break,
            }
            input = &[];
        }
        actions
    }

    pub fn tick(&mut self) -> Vec<PacketAction> {
        self.remove_expired();
        let mut actions = Vec::new();
        let mut buffer = vec![0; 65535 + 160];
        for peer in &mut self.peers {
            let mut sent = false;
            let path = peer.path;
            if let TunnResult::WriteToNetwork(data) =
                Self::active_tunnel(peer).update_timers(&mut buffer)
            {
                actions.extend(Self::network_action(path, peer, data.to_vec()));
                sent = true;
            }
            if !sent && Self::active_tunnel(peer).stats().0.is_none() {
                if let TunnResult::WriteToNetwork(data) =
                    Self::active_tunnel(peer).format_handshake_initiation(&mut buffer, false)
                {
                    actions.extend(Self::network_action(path, peer, data.to_vec()));
                }
            }
        }
        actions
    }

    pub fn connected(&self) -> bool {
        self.peers.iter().any(|peer| {
            peer.authorized_until > Instant::now()
                && peer
                    .last_authenticated
                    .is_some_and(|at| at.elapsed() < PEER_IDLE_TIMEOUT)
        })
    }

    pub fn direct_failures(&self) -> Vec<Ipv4Addr> {
        self.peers
            .iter()
            .filter(|peer| {
                peer.authorized_until > Instant::now()
                    && peer.path == NetworkPath::Direct
                    && peer.last_authenticated.map_or_else(
                        || peer.started.elapsed() >= DIRECT_HANDSHAKE_TIMEOUT,
                        |at| at.elapsed() >= PEER_IDLE_TIMEOUT,
                    )
            })
            .map(|peer| peer.address)
            .collect()
    }

    pub fn uses_relay(&self) -> bool {
        self.peers
            .iter()
            .any(|peer| peer.authorized_until > Instant::now() && peer.path == NetworkPath::Relay)
            || self.path == NetworkPath::Relay
    }

    pub fn relay_lost(&mut self) {
        for peer in &mut self.peers {
            peer.relay_tunnel = None;
            if peer.path == NetworkPath::Relay {
                peer.last_authenticated = None;
            }
        }
        for index in 0..self.peers.len() {
            if self.peers[index].path == NetworkPath::Relay {
                self.ensure_relay_tunnel(index);
            }
        }
    }

    pub fn path_code(&self) -> u8 {
        let direct = self
            .peers
            .iter()
            .any(|peer| peer.authorized_until > Instant::now() && peer.path == NetworkPath::Direct);
        if self.uses_relay() {
            if direct {
                2
            } else {
                1
            }
        } else {
            0
        }
    }

    pub fn set_path(&mut self, path: NetworkPath) {
        self.path = path;
        for index in 0..self.peers.len() {
            self.set_peer_path(index, path);
        }
    }

    pub fn fallback(&mut self, address: Ipv4Addr) {
        if let Some(index) = self.peers.iter().position(|peer| peer.address == address) {
            self.set_peer_path(index, NetworkPath::Relay);
        }
    }

    fn set_peer_path(&mut self, index: usize, path: NetworkPath) {
        if path == NetworkPath::Relay {
            self.ensure_relay_tunnel(index);
        }
        let peer = &mut self.peers[index];
        if peer.path != path {
            peer.path = path;
            peer.last_authenticated = None;
        }
    }

    fn ensure_relay_tunnel(&mut self, index: usize) {
        let peer = &mut self.peers[index];
        if peer.relay_tunnel.is_none() {
            // 中继候选独立握手，未认证报文不能重置有效直连或继承其成功状态。
            peer.relay_tunnel = Some(Tunn::new(
                self.private_key.clone(),
                PublicKey::from(peer.key),
                None,
                Some(15),
                self.next_index,
                None,
            ));
            self.next_index = self.next_index.wrapping_add(1);
        }
    }

    fn active_tunnel(peer: &mut Peer) -> &mut Tunn {
        if peer.path == NetworkPath::Relay {
            peer.relay_tunnel.as_mut().unwrap()
        } else {
            &mut peer.tunnel
        }
    }

    fn remove_expired(&mut self) {
        self.peers
            .retain(|peer| peer.authorized_until > Instant::now());
    }

    fn network_action(path: NetworkPath, peer: &Peer, data: Vec<u8>) -> Vec<PacketAction> {
        if peer.authorized_until <= Instant::now() {
            return Vec::new();
        }
        match path {
            NetworkPath::Direct => peer
                .endpoint
                .map(|endpoint| vec![PacketAction::Network(endpoint, data)])
                .unwrap_or_default(),
            NetworkPath::Relay => vec![PacketAction::Relay(peer.address, data)],
        }
    }
}

fn ipv4_addresses(packet: &[u8]) -> Option<(Ipv4Addr, Ipv4Addr)> {
    if packet.len() < 20 || packet[0] >> 4 != 4 {
        return None;
    }
    let header_length = (packet[0] & 15) as usize * 4;
    if header_length < 20
        || header_length > packet.len()
        || u16::from_be_bytes([packet[2], packet[3]]) as usize != packet.len()
    {
        return None;
    }
    Some((
        Ipv4Addr::new(packet[12], packet[13], packet[14], packet[15]),
        Ipv4Addr::new(packet[16], packet[17], packet[18], packet[19]),
    ))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::UdpSocket;
    use std::time::Duration;

    fn peer(ip: &str, key: &StaticSecret, endpoint: SocketAddr) -> NetworkPeer {
        NetworkPeer {
            user_id: ip.into(),
            member_generation: 1,
            lease_id: format!("lease-{ip}"),
            expires_at: (time::OffsetDateTime::now_utc() + time::Duration::seconds(60))
                .format(&time::format_description::well_known::Rfc3339)
                .unwrap(),
            virtual_ipv4: ip.into(),
            public_key: base64::engine::general_purpose::STANDARD
                .encode(PublicKey::from(key).to_bytes()),
            endpoint: Some(endpoint.to_string()),
        }
    }

    fn packet(source: [u8; 4], destination: [u8; 4]) -> Vec<u8> {
        let mut packet = vec![0; 36];
        packet[0] = 0x45;
        packet[2..4].copy_from_slice(&36u16.to_be_bytes());
        packet[8] = 64;
        packet[9] = 17;
        packet[12..16].copy_from_slice(&source);
        packet[16..20].copy_from_slice(&destination);
        packet[20..].copy_from_slice(b"controlled-game!");
        packet
    }

    #[test]
    fn real_udp_wireguard_handshake_delivers_ip_and_removed_peer_cannot_replay() {
        let a = UdpSocket::bind("127.0.0.1:0").unwrap();
        let b = UdpSocket::bind("127.0.0.1:0").unwrap();
        a.set_read_timeout(Some(Duration::from_millis(100)))
            .unwrap();
        b.set_read_timeout(Some(Duration::from_millis(100)))
            .unwrap();
        let key_a = StaticSecret::from([7; 32]);
        let key_b = StaticSecret::from([8; 32]);
        let network = "10.231.0.0/27".parse().unwrap();
        let mut router_a = TunnelRouter::new("10.231.0.1".parse().unwrap(), network, key_a.clone());
        let mut router_b = TunnelRouter::new("10.231.0.2".parse().unwrap(), network, key_b.clone());
        router_a
            .update_peers(vec![peer("10.231.0.2", &key_b, b.local_addr().unwrap())])
            .unwrap();
        router_b
            .update_peers(vec![peer("10.231.0.1", &key_a, a.local_addr().unwrap())])
            .unwrap();
        let plain = packet([10, 231, 0, 1], [10, 231, 0, 2]);
        for action in router_a.outbound(&plain) {
            if let PacketAction::Network(endpoint, bytes) = action {
                a.send_to(&bytes, endpoint).unwrap();
            }
        }
        let mut delivered = Vec::new();
        let mut captured = Vec::new();
        for _ in 0..10 {
            for (socket, router) in [(&b, &mut router_b), (&a, &mut router_a)] {
                let mut buffer = [0; 2048];
                if let Ok((length, source)) = socket.recv_from(&mut buffer) {
                    let cipher = &buffer[..length];
                    assert!(!cipher.windows(16).any(|value| value == b"controlled-game!"));
                    captured = cipher.to_vec();
                    for action in router.inbound(source, cipher) {
                        match action {
                            PacketAction::Network(endpoint, bytes) => {
                                socket.send_to(&bytes, endpoint).unwrap();
                            }
                            PacketAction::Tunnel(bytes) => delivered.push(bytes),
                            PacketAction::Relay(..) => panic!("direct test must not use relay"),
                        }
                    }
                }
            }
            if !delivered.is_empty() {
                break;
            }
        }
        assert_eq!(delivered, vec![plain]);
        assert!(router_a.connected());
        assert!(router_b.connected());
        router_b.update_peers(Vec::new()).unwrap();
        assert!(!router_b.connected());
        assert!(router_b
            .inbound(a.local_addr().unwrap(), &captured)
            .is_empty());
    }

    #[test]
    fn source_spoofing_outside_destinations_and_invalid_updates_are_rejected() {
        let key = StaticSecret::from([7; 32]);
        let other = StaticSecret::from([8; 32]);
        let mut router = TunnelRouter::new(
            "10.231.0.1".parse().unwrap(),
            "10.231.0.0/27".parse().unwrap(),
            key,
        );
        let endpoint = "127.0.0.1:12345".parse().unwrap();
        router
            .update_peers(vec![peer("10.231.0.2", &other, endpoint)])
            .unwrap();
        assert!(!router.connected());
        assert!(router.tick().iter().any(|action| matches!(action, PacketAction::Network(target, packet) if *target == endpoint && packet.len() >= 148)));
        assert!(router
            .outbound(&packet([192, 168, 1, 2], [10, 231, 0, 2]))
            .is_empty());
        assert!(router
            .outbound(&packet([10, 231, 0, 1], [8, 8, 8, 8]))
            .is_empty());
        assert!(router
            .update_peers(vec![peer("10.231.0.32", &other, endpoint)])
            .is_err());
        assert_eq!(router.peers.len(), 1);
        assert!(router
            .update_peers(vec![
                peer("10.231.0.2", &other, endpoint),
                peer("10.231.0.3", &other, endpoint)
            ])
            .is_err());
        assert_eq!(router.peers.len(), 1);
    }

    fn authorized_peer(
        key: &StaticSecret,
        generation: u64,
        lease: &str,
        lifetime: i64,
    ) -> NetworkPeer {
        serde_json::from_value(serde_json::json!({
            "userId": "remote-user", "virtualIpv4": "10.231.0.2",
            "publicKey": base64::engine::general_purpose::STANDARD.encode(PublicKey::from(key).to_bytes()),
            "endpoint": "127.0.0.1:12345", "memberGeneration": generation, "leaseId": lease,
            "expiresAt": (time::OffsetDateTime::now_utc() + time::Duration::milliseconds(lifetime))
                .format(&time::format_description::well_known::Rfc3339).unwrap()
        })).unwrap()
    }

    #[test]
    fn changed_authorization_identity_resets_existing_wireguard_session() {
        for change in [
            (|peer: &mut NetworkPeer| peer.member_generation = 2) as fn(&mut NetworkPeer),
            |peer: &mut NetworkPeer| peer.lease_id = "lease-2".into(),
            |peer: &mut NetworkPeer| peer.user_id = "replacement-user".into(),
        ] {
            let key = StaticSecret::from([8; 32]);
            let mut router = TunnelRouter::new(
                "10.231.0.1".parse().unwrap(),
                "10.231.0.0/27".parse().unwrap(),
                StaticSecret::from([7; 32]),
            );
            router
                .update_peers(vec![authorized_peer(&key, 1, "lease-1", 30000)])
                .unwrap();
            router.peers[0].last_authenticated = Some(Instant::now());
            assert!(router.connected());
            let mut replacement = authorized_peer(&key, 1, "lease-1", 30000);
            change(&mut replacement);
            router.update_peers(vec![replacement]).unwrap();
            assert!(
                !router.connected(),
                "same address/key/endpoint must not inherit an older authorization handshake"
            );
        }
    }

    #[test]
    fn expired_peer_cannot_emit_handshake_or_data_without_another_snapshot() {
        let key = StaticSecret::from([8; 32]);
        let mut router = TunnelRouter::new(
            "10.231.0.1".parse().unwrap(),
            "10.231.0.0/27".parse().unwrap(),
            StaticSecret::from([7; 32]),
        );
        router
            .update_peers(vec![authorized_peer(&key, 1, "lease-1", 200)])
            .unwrap();
        router.peers[0].last_authenticated = Some(Instant::now());
        assert!(router.connected());
        std::thread::sleep(Duration::from_millis(250));
        assert!(
            !router.connected(),
            "remote authorization expiry must override recent traffic"
        );
        assert!(router
            .outbound(&packet([10, 231, 0, 1], [10, 231, 0, 2]))
            .is_empty());
        assert!(router.tick().is_empty());
    }

    #[test]
    fn identity_validation_is_atomic_and_same_lease_refresh_preserves_state() {
        let key = StaticSecret::from([8; 32]);
        let mut router = TunnelRouter::new(
            "10.231.0.1".parse().unwrap(),
            "10.231.0.0/27".parse().unwrap(),
            StaticSecret::from([7; 32]),
        );
        router
            .update_peers(vec![authorized_peer(&key, 2, "lease-2", 30000)])
            .unwrap();
        router.peers[0].last_authenticated = Some(Instant::now());
        let authenticated = router.peers[0].last_authenticated;
        router
            .update_peers(vec![authorized_peer(&key, 2, "lease-2", 60000)])
            .unwrap();
        assert_eq!(router.peers[0].last_authenticated, authenticated);
        assert!(router.connected());
        for change in [
            (|peer: &mut NetworkPeer| peer.user_id.clear()) as fn(&mut NetworkPeer),
            |peer: &mut NetworkPeer| peer.member_generation = 0,
            |peer: &mut NetworkPeer| peer.member_generation = 1,
            |peer: &mut NetworkPeer| peer.lease_id.clear(),
            |peer: &mut NetworkPeer| peer.expires_at = "invalid".into(),
        ] {
            let mut invalid = authorized_peer(&key, 2, "lease-2", 60000);
            change(&mut invalid);
            assert!(router.update_peers(vec![invalid]).is_err());
            assert_eq!(router.peers[0].last_authenticated, authenticated);
        }
        let json = serde_json::to_value(authorized_peer(&key, 2, "lease-2", 60000)).unwrap();
        for field in ["memberGeneration", "leaseId", "expiresAt"] {
            let mut legacy = json.clone();
            legacy.as_object_mut().unwrap().remove(field);
            assert!(serde_json::from_value::<NetworkPeer>(legacy).is_err());
        }
        router
            .update_peers(vec![authorized_peer(&key, 2, "lease-2", -1)])
            .unwrap();
        assert!(router.peers.is_empty());
    }

    #[test]
    fn new_lease_or_expired_authority_rejects_real_old_ciphertext_on_both_paths() {
        for (path, expired) in [
            (NetworkPath::Direct, false),
            (NetworkPath::Relay, false),
            (NetworkPath::Direct, true),
            (NetworkPath::Relay, true),
        ] {
            let key_a = StaticSecret::from([7; 32]);
            let key_b = StaticSecret::from([8; 32]);
            let endpoint_a = "127.0.0.1:12344".parse().unwrap();
            let endpoint_b = "127.0.0.1:12345".parse().unwrap();
            let network = "10.231.0.0/27".parse().unwrap();
            let mut source =
                TunnelRouter::new("10.231.0.1".parse().unwrap(), network, key_a.clone());
            let mut target =
                TunnelRouter::new("10.231.0.2".parse().unwrap(), network, key_b.clone());
            source
                .update_peers(vec![peer("10.231.0.2", &key_b, endpoint_b)])
                .unwrap();
            target
                .update_peers(vec![peer("10.231.0.1", &key_a, endpoint_a)])
                .unwrap();
            source.set_path(path);
            target.set_path(path);
            let plain = packet([10, 231, 0, 1], [10, 231, 0, 2]);
            let mut pending: std::collections::VecDeque<_> = source
                .outbound(&plain)
                .into_iter()
                .map(|action| (true, action))
                .collect();
            let mut delivered = Vec::new();
            for _ in 0..32 {
                let Some((toward_target, action)) = pending.pop_front() else {
                    break;
                };
                let (router, endpoint, address) = if toward_target {
                    (&mut target, endpoint_a, source.address)
                } else {
                    (&mut source, endpoint_b, target.address)
                };
                let actions = match action {
                    PacketAction::Network(_, bytes) => router.inbound(endpoint, &bytes),
                    PacketAction::Relay(_, bytes) => router.inbound_relay(address, &bytes),
                    PacketAction::Tunnel(bytes) => {
                        delivered.push(bytes);
                        Vec::new()
                    }
                };
                pending.extend(actions.into_iter().map(|action| (!toward_target, action)));
            }
            assert_eq!(delivered, vec![plain.clone()]);
            let old_frame = match source.outbound(&plain).pop().unwrap() {
                PacketAction::Network(_, bytes) | PacketAction::Relay(_, bytes) => bytes,
                _ => panic!("expected encrypted packet"),
            };
            assert_eq!(old_frame[..4], [4, 0, 0, 0]);
            if expired {
                target.peers[0].authorized_until = Instant::now() - Duration::from_millis(1);
            } else {
                let mut replacement = peer("10.231.0.1", &key_a, endpoint_a);
                replacement.member_generation = 2;
                replacement.lease_id = "replacement-lease".into();
                target.update_peers(vec![replacement]).unwrap();
            }
            let rejected = if path == NetworkPath::Direct {
                target.inbound(endpoint_a, &old_frame)
            } else {
                target.inbound_relay(source.address, &old_frame)
            };
            assert!(rejected.is_empty());
            if expired {
                assert!(target.peers.is_empty());
            }
        }
    }

    struct RelayAuthority(std::sync::Mutex<bool>);

    impl zhijie_relay::server::GrantValidator for RelayAuthority {
        fn validate(
            &self,
            ticket: &str,
            _: SocketAddr,
        ) -> Result<zhijie_relay::model::RelayGrant, String> {
            if !*self.0.lock().unwrap() || !["a", "b"].contains(&ticket) {
                return Err("revoked".into());
            }
            Ok(zhijie_relay::model::RelayGrant {
                version: 1,
                ticket: ticket.into(),
                activity_id: "room".into(),
                activity_generation: 1,
                member_generation: 1,
                lease_id: format!("lease-{ticket}"),
                device_id: format!("device-{ticket}"),
                user_id: ticket.into(),
                source_ipv4: if ticket == "a" {
                    "10.231.0.1"
                } else {
                    "10.231.0.2"
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
    async fn real_quic_relay_carries_wireguard_without_direct_endpoints_and_revocation_stops_it() {
        use std::sync::Arc;
        use zhijie_relay::quic::{QuicRelayServer, RelayClient};
        for _ in 0..3 {
            let cert = rcgen::generate_simple_self_signed(vec!["localhost".into()]).unwrap();
            let der = cert.cert.der().to_vec();
            let authority = Arc::new(RelayAuthority(std::sync::Mutex::new(true)));
            let server = QuicRelayServer::bind_der(
                "127.0.0.1:0".parse().unwrap(),
                der.clone(),
                cert.signing_key.serialize_der(),
                authority.clone(),
            )
            .unwrap();
            assert!(RelayClient::connect(
                server.local_addr(),
                "wrong.example",
                Some(der.clone()),
                "a"
            )
            .await
            .is_err());
            assert!(
                RelayClient::connect(server.local_addr(), "localhost", None, "a")
                    .await
                    .is_err()
            );
            assert!(RelayClient::connect(
                server.local_addr(),
                "localhost",
                Some(der.clone()),
                "invalid"
            )
            .await
            .is_err());
            let a = RelayClient::connect(server.local_addr(), "localhost", Some(der.clone()), "a")
                .await
                .unwrap();
            let b = RelayClient::connect(server.local_addr(), "localhost", Some(der), "b")
                .await
                .unwrap();
            let key_a = StaticSecret::from([7; 32]);
            let key_b = StaticSecret::from([8; 32]);
            let mut ra = TunnelRouter::new(
                "10.231.0.1".parse().unwrap(),
                "10.231.0.0/27".parse().unwrap(),
                key_a.clone(),
            );
            let mut rb = TunnelRouter::new(
                "10.231.0.2".parse().unwrap(),
                "10.231.0.0/27".parse().unwrap(),
                key_b.clone(),
            );
            let mut pa = peer("10.231.0.1", &key_a, "127.0.0.1:1".parse().unwrap());
            pa.endpoint = None;
            let mut pb = peer("10.231.0.2", &key_b, "127.0.0.1:2".parse().unwrap());
            pb.endpoint = None;
            ra.update_peers(vec![pb]).unwrap();
            rb.update_peers(vec![pa]).unwrap();
            ra.set_path(NetworkPath::Relay);
            rb.set_path(NetworkPath::Relay);
            assert!(!ra.connected());
            assert!(ra
                .inbound("127.0.0.1:1".parse().unwrap(), b"direct")
                .is_empty());
            let plain = packet([10, 231, 0, 1], [10, 231, 0, 2]);
            let send = |client: &RelayClient, actions: Vec<PacketAction>| {
                let mut delivered = Vec::new();
                for action in actions {
                    match action {
                        PacketAction::Relay(target, bytes) => {
                            assert!(!bytes.windows(16).any(|value| value == b"controlled-game!"));
                            client.send(target, &bytes).unwrap();
                        }
                        PacketAction::Tunnel(bytes) => delivered.push(bytes),
                        PacketAction::Network(..) => panic!("relay-only must not send direct"),
                    }
                }
                delivered
            };
            send(&a, ra.outbound(&plain));
            let delivered = tokio::time::timeout(Duration::from_secs(3), async {
                loop {
                    tokio::select! {
                        result = a.receive() => { let (source, bytes) = result.unwrap(); send(&a, ra.inbound_relay(source, &bytes)); }
                        result = b.receive() => { let (source, bytes) = result.unwrap(); let delivered = send(&b, rb.inbound_relay(source, &bytes)); if !delivered.is_empty() { break delivered; } }
                    }
                }
            }).await.unwrap();
            assert_eq!(delivered, vec![plain.clone()]);
            assert!(ra.connected() && rb.connected());
            *authority.0.lock().unwrap() = false;
            send(&a, ra.outbound(&plain));
            tokio::time::timeout(Duration::from_secs(2), async {
                while b.receive().await.is_ok() {}
            })
            .await
            .expect("authority revocation must close within the original two-second bound");
            assert!(b.receive().await.is_err());
            drop(a);
            drop(b);
            server.stop().await;
        }
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn mixed_peers_preserve_direct_session_and_reject_forged_migration_and_replay() {
        use std::sync::Arc;
        use zhijie_relay::quic::{QuicRelayServer, RelayClient};
        let cert = rcgen::generate_simple_self_signed(vec!["localhost".into()]).unwrap();
        let der = cert.cert.der().to_vec();
        let server = QuicRelayServer::bind_der(
            "127.0.0.1:0".parse().unwrap(),
            der.clone(),
            cert.signing_key.serialize_der(),
            Arc::new(RelayAuthority(std::sync::Mutex::new(true))),
        )
        .unwrap();
        let relay_a =
            RelayClient::connect(server.local_addr(), "localhost", Some(der.clone()), "a")
                .await
                .unwrap();
        let relay_c = RelayClient::connect(server.local_addr(), "localhost", Some(der), "b")
            .await
            .unwrap();
        let a = tokio::net::UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let b = tokio::net::UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let key_a = StaticSecret::from([7; 32]);
        let key_b = StaticSecret::from([8; 32]);
        let key_c = StaticSecret::from([9; 32]);
        let network = "10.231.0.0/27".parse().unwrap();
        let mut ra = TunnelRouter::new("10.231.0.1".parse().unwrap(), network, key_a.clone());
        let mut rb = TunnelRouter::new("10.231.0.3".parse().unwrap(), network, key_b.clone());
        let mut rc = TunnelRouter::new("10.231.0.2".parse().unwrap(), network, key_c.clone());
        let pb = peer("10.231.0.3", &key_b, b.local_addr().unwrap());
        let mut pc = peer("10.231.0.2", &key_c, "127.0.0.1:1".parse().unwrap());
        pc.endpoint = None;
        ra.update_peers(vec![pb.clone(), pc.clone()]).unwrap();
        rb.update_peers(vec![peer("10.231.0.1", &key_a, a.local_addr().unwrap())])
            .unwrap();
        rc.update_peers(vec![peer("10.231.0.1", &key_a, a.local_addr().unwrap())])
            .unwrap();
        let plain_b = packet([10, 231, 0, 1], [10, 231, 0, 3]);
        let plain_c = packet([10, 231, 0, 1], [10, 231, 0, 2]);
        let send = |socket: &tokio::net::UdpSocket,
                    relay: Option<&RelayClient>,
                    actions: Vec<PacketAction>| {
            let mut delivered = Vec::new();
            for action in actions {
                match action {
                    PacketAction::Network(endpoint, bytes) => {
                        socket.try_send_to(&bytes, endpoint).unwrap();
                    }
                    PacketAction::Relay(target, bytes) => {
                        relay.unwrap().send(target, &bytes).unwrap()
                    }
                    PacketAction::Tunnel(bytes) => delivered.push(bytes),
                }
            }
            delivered
        };
        // 先建立 A-B 直连，再让 C 主动经中继发起握手。
        let initial = ra.outbound(&plain_b);
        let old_direct_init = match &initial[0] {
            PacketAction::Network(_, bytes) => bytes.clone(),
            _ => panic!("first path must be direct"),
        };
        send(&a, Some(&relay_a), initial);
        let mut buffer_a = [0; 2048];
        let mut buffer_b = [0; 2048];
        tokio::time::timeout(Duration::from_secs(3), async {
            loop {
                tokio::select! {
                    result = a.recv_from(&mut buffer_a) => { let (n, source) = result.unwrap(); send(&a, Some(&relay_a), ra.inbound(source, &buffer_a[..n])); }
                    result = b.recv_from(&mut buffer_b) => {
                        let (n, source) = result.unwrap();
                        let delivered = send(&b, None, rb.inbound(source, &buffer_b[..n]));
                        if !delivered.is_empty() { assert_eq!(delivered, vec![plain_b.clone()]); break; }
                    }
                }
            }
        }).await.unwrap();
        let direct_handshake = ra.peers[0].tunnel.stats().0.unwrap();
        assert_eq!(ra.path_code(), 0);
        // 伪造当前成员的中继来源、握手、keepalive 不得切换或刷新直连。
        let authenticated = ra.peers[0].last_authenticated;
        // 合法旧直连 initiation 在新路径上仍不能证明往返成功。
        rb.inbound_relay("10.231.0.1".parse().unwrap(), &old_direct_init);
        assert_eq!(rb.path_code(), 0);
        for forged in [vec![0; 148], {
            let mut p = vec![0; 32];
            p[0] = 4;
            p
        }] {
            assert!(ra
                .inbound_relay("10.231.0.3".parse().unwrap(), &forged)
                .is_empty());
            assert_eq!(ra.peers[0].last_authenticated, authenticated);
            assert_eq!(ra.path_code(), 0);
        }
        rc.set_path(NetworkPath::Relay);
        let reverse_c = packet([10, 231, 0, 2], [10, 231, 0, 1]);
        send(&b, Some(&relay_c), rc.outbound(&reverse_c));
        let mut captured_relay = Vec::new();
        tokio::time::timeout(Duration::from_secs(3), async {
            loop {
                tokio::select! {
                    result = relay_a.receive() => {
                        let (source, bytes) = result.unwrap();
                        captured_relay = bytes.clone();
                        let delivered = send(&a, Some(&relay_a), ra.inbound_relay(source, &bytes));
                        if !delivered.is_empty() { assert_eq!(delivered, vec![reverse_c.clone()]); break; }
                    }
                    result = relay_c.receive() => { let (source, bytes) = result.unwrap(); send(&b, Some(&relay_c), rc.inbound_relay(source, &bytes)); }
                }
            }
        }).await.unwrap();
        assert_eq!(ra.path_code(), 2);
        assert!(ra.peers[0].tunnel.stats().0.unwrap() >= direct_handshake);
        assert_eq!(ra.peers[0].last_authenticated, authenticated);
        // 权威列表刷新保留各自握手；直连包仍用原会话立即交付。
        ra.update_peers(vec![pb, pc.clone()]).unwrap();
        let actions = ra.outbound(&plain_b);
        assert!(
            matches!(&actions[..], [PacketAction::Network(_, bytes)] if bytes[..4] == [4,0,0,0])
        );
        send(&a, Some(&relay_a), actions);
        let (n, source) = tokio::time::timeout(Duration::from_secs(1), b.recv_from(&mut buffer_b))
            .await
            .unwrap()
            .unwrap();
        assert_eq!(
            send(&b, None, rb.inbound(source, &buffer_b[..n])),
            vec![plain_b]
        );
        send(&a, Some(&relay_a), ra.outbound(&plain_c));
        let (source, bytes) = tokio::time::timeout(Duration::from_secs(1), relay_c.receive())
            .await
            .unwrap()
            .unwrap();
        assert_eq!(
            send(&b, Some(&relay_c), rc.inbound_relay(source, &bytes)),
            vec![plain_c]
        );
        // 重放、过期活跃状态和移除后旧帧均不得恢复连接。
        let before_replay = ra.peers[1].last_authenticated;
        assert!(ra
            .inbound_relay("10.231.0.2".parse().unwrap(), &captured_relay)
            .is_empty());
        assert_eq!(ra.peers[1].last_authenticated, before_replay);
        for peer in &mut ra.peers {
            peer.last_authenticated = Some(Instant::now() - Duration::from_secs(36));
        }
        assert!(!ra.connected());
        assert_eq!(
            ra.direct_failures(),
            vec!["10.231.0.3".parse::<Ipv4Addr>().unwrap()]
        );
        ra.update_peers(vec![pc]).unwrap();
        assert_eq!(ra.path_code(), 1);
        ra.update_peers(Vec::new()).unwrap();
        assert!(ra
            .inbound_relay("10.231.0.2".parse().unwrap(), &captured_relay)
            .is_empty());
        assert!(!ra.connected());
        drop(relay_a);
        drop(relay_c);
        server.stop().await;
    }
}
