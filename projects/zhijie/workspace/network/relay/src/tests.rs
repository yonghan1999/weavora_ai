use std::collections::HashMap;
use std::net::{SocketAddr, UdpSocket};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use crate::model::RelayGrant;
use crate::server::{encode_packet, GrantValidator, RelayServer};

struct FakeValidator {
    grants: HashMap<String, RelayGrant>,
    calls: Mutex<usize>,
}

impl GrantValidator for FakeValidator {
    fn validate(&self, ticket: &str, _endpoint: SocketAddr) -> Result<RelayGrant, String> {
        *self.calls.lock().unwrap() += 1;
        self.grants.get(ticket).cloned().ok_or_else(|| "RELAY_GRANT_INVALID".into())
    }
}

fn grant(ticket: &str, activity: &str, ip: &str) -> RelayGrant {
    RelayGrant {
        ticket: ticket.into(), activity_id: activity.into(), member_generation: 1,
        lease_id: format!("lease-{ticket}"), user_id: format!("user-{ticket}"), source_ipv4: ip.into(),
        policy_revision: 1, allow_relay: true,
    }
}

fn client() -> UdpSocket {
    let socket = UdpSocket::bind("127.0.0.1:0").unwrap();
    socket.set_read_timeout(Some(Duration::from_millis(300))).unwrap();
    socket
}

#[test]
fn real_udp_datagrams_forward_ciphertext_between_authorized_room_members() {
    let validator = Arc::new(FakeValidator {
        grants: HashMap::from([("a".into(), grant("a", "activity", "10.231.0.1")),
            ("b".into(), grant("b", "activity", "10.231.0.2"))]),
        calls: Mutex::new(0),
    });
    let relay = RelayServer::bind("127.0.0.1:0", validator.clone()).unwrap();
    relay.start();
    let a = client();
    let b = client();
    a.send_to(&encode_packet("a", "10.231.0.2", b"register-a").unwrap(), relay.local_addr()).unwrap();
    let packet = encode_packet("b", "10.231.0.1", b"wireguard-ciphertext").unwrap();
    b.send_to(&packet, relay.local_addr()).unwrap();
    let mut buffer = [0u8; 2048];
    let (length, source) = a.recv_from(&mut buffer).unwrap();
    assert_eq!(source, relay.local_addr());
    assert_eq!(&buffer[..length], b"wireguard-ciphertext");
    relay.stop();
    assert!(*validator.calls.lock().unwrap() > 0);
}

#[test]
fn cross_activity_disabled_and_revoked_sessions_are_not_forwarded() {
    let validator = Arc::new(FakeValidator {
        grants: HashMap::from([
            ("a".into(), grant("a", "activity", "10.231.0.1")),
            ("b".into(), grant("b", "activity", "10.231.0.2")),
            ("other".into(), grant("other", "other-activity", "10.231.0.3")),
        ]),
        calls: Mutex::new(0),
    });
    let relay = RelayServer::bind("127.0.0.1:0", validator).unwrap();
    relay.start();
    let a = client();
    let other = client();
    let b = client();
    other.send_to(&encode_packet("other", "10.231.0.2", b"cross-room").unwrap(), relay.local_addr()).unwrap();
    let mut buffer = [0u8; 128];
    assert!(b.recv_from(&mut buffer).is_err());

    a.send_to(&encode_packet("a", "10.231.0.2", b"register-a").unwrap(), relay.local_addr()).unwrap();
    b.send_to(&encode_packet("b", "10.231.0.1", b"register-b").unwrap(), relay.local_addr()).unwrap();
    relay.revoke_lease("lease-b");
    a.send_to(&encode_packet("a", "10.231.0.2", b"after-revoke").unwrap(), relay.local_addr()).unwrap();
    assert!(b.recv_from(&mut buffer).is_err());
    relay.stop();
}
