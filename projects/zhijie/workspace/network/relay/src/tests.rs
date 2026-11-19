use std::collections::{HashMap, HashSet};
use std::net::SocketAddr;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use crate::model::RelayGrant;
use crate::quic::{QuicRelayServer, RelayClient};
use crate::server::GrantValidator;

struct Authority {
    grants: HashMap<String, RelayGrant>,
    rejected: Mutex<HashSet<String>>,
}

impl GrantValidator for Authority {
    fn validate(&self, ticket: &str, _: SocketAddr) -> Result<RelayGrant, String> {
        if self.rejected.lock().unwrap().contains(ticket) {
            return Err("RELAY_GRANT_INVALID".into());
        }
        self.grants
            .get(ticket)
            .cloned()
            .ok_or("RELAY_GRANT_INVALID".into())
    }
}

fn grant(ticket: &str, activity: &str, ip: &str, allow_relay: bool) -> RelayGrant {
    RelayGrant {
        ticket: ticket.into(),
        activity_id: activity.into(),
        member_generation: 1,
        lease_id: format!("lease-{ticket}"),
        user_id: ticket.into(),
        source_ipv4: ip.into(),
        policy_revision: 1,
        allow_relay,
    }
}

async fn fixture() -> (QuicRelayServer, Vec<u8>, Arc<Authority>) {
    let authority = Arc::new(Authority {
        grants: HashMap::from([
            ("a".into(), grant("a", "room", "10.231.0.1", true)),
            ("b".into(), grant("b", "room", "10.231.0.2", true)),
            (
                "other".into(),
                grant("other", "other-room", "10.231.0.1", true),
            ),
            (
                "disabled".into(),
                grant("disabled", "room", "10.231.0.3", false),
            ),
        ]),
        rejected: Mutex::default(),
    });
    let cert = rcgen::generate_simple_self_signed(vec!["localhost".into()]).unwrap();
    let der = cert.cert.der().to_vec();
    let server = QuicRelayServer::bind_der(
        "127.0.0.1:0".parse().unwrap(),
        der.clone(),
        cert.signing_key.serialize_der(),
        authority.clone(),
    )
    .unwrap();
    (server, der, authority)
}

async fn connect(server: &QuicRelayServer, cert: &[u8], ticket: &str) -> RelayClient {
    RelayClient::connect(
        server.local_addr(),
        "localhost",
        Some(cert.to_vec()),
        ticket,
    )
    .await
    .unwrap()
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn real_quic_datagrams_forward_ciphertext_with_server_authoritative_source() {
    let (server, cert, _) = fixture().await;
    let a = connect(&server, &cert, "a").await;
    let b = connect(&server, &cert, "b").await;
    a.send("10.231.0.2".parse().unwrap(), b"wireguard-ciphertext")
        .unwrap();
    let (source, bytes) = tokio::time::timeout(Duration::from_secs(2), b.receive())
        .await
        .unwrap()
        .unwrap();
    assert_eq!(source.to_string(), "10.231.0.1");
    assert_eq!(bytes, b"wireguard-ciphertext");
    assert!(a.send(source, &vec![0; 1121]).is_err());
    drop(a);
    drop(b);
    server.stop().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn cross_activity_and_relay_disabled_grants_cannot_forward() {
    let (server, cert, _) = fixture().await;
    assert!(RelayClient::connect(
        server.local_addr(),
        "localhost",
        Some(cert.clone()),
        "disabled"
    )
    .await
    .is_err());
    let b = connect(&server, &cert, "b").await;
    let other = connect(&server, &cert, "other").await;
    other
        .send("10.231.0.2".parse().unwrap(), b"cross-room")
        .unwrap();
    assert!(
        tokio::time::timeout(Duration::from_millis(300), b.receive())
            .await
            .is_err()
    );
    drop(other);
    drop(b);
    server.stop().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn receiver_revoked_in_control_plane_cannot_receive_from_cached_session() {
    let (server, cert, authority) = fixture().await;
    let a = connect(&server, &cert, "a").await;
    let b = connect(&server, &cert, "b").await;
    let target = "10.231.0.2".parse().unwrap();
    a.send(target, b"before").unwrap();
    assert_eq!(b.receive().await.unwrap().1, b"before");
    authority.rejected.lock().unwrap().insert("b".into());
    a.send(target, b"must-not-arrive").unwrap();
    assert!(tokio::time::timeout(Duration::from_secs(2), b.receive())
        .await
        .unwrap()
        .is_err());
    drop(a);
    drop(b);
    server.stop().await;
}
