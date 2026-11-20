use std::collections::{HashMap, HashSet};
use std::net::SocketAddr;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use crate::management::RelayManagementServer;
use crate::model::RelayGrant;
use crate::quic::{QuicRelayServer, RelayClient};
use crate::revocation::RevocationCommand;
use crate::server::GrantValidator;

struct Authority {
    grants: HashMap<String, RelayGrant>,
    rejected: Mutex<HashSet<String>>,
    calls: AtomicUsize,
}

impl GrantValidator for Authority {
    fn validate(&self, ticket: &str, _: SocketAddr) -> Result<RelayGrant, String> {
        self.calls.fetch_add(1, Ordering::SeqCst);
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
        version: 1,
        ticket: ticket.into(),
        activity_id: activity.into(),
        activity_generation: 1,
        member_generation: 1,
        lease_id: format!("lease-{ticket}"),
        device_id: format!("device-{ticket}"),
        user_id: ticket.into(),
        source_ipv4: ip.into(),
        policy_revision: 1,
        allow_relay,
        expires_at_unix_millis: (std::time::SystemTime::now() + Duration::from_secs(60))
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_millis() as u64,
    }
}

async fn fixture() -> (QuicRelayServer, Vec<u8>, Arc<Authority>) {
    let authority = Arc::new(Authority {
        grants: HashMap::from([
            ("a".into(), grant("a", "room", "10.231.0.1", true)),
            ("b".into(), grant("b", "room", "10.231.0.2", true)),
            (
                "new-b".into(),
                RelayGrant {
                    user_id: "b".into(),
                    member_generation: 2,
                    ..grant("new-b", "room", "10.231.0.2", true)
                },
            ),
            (
                "other-b".into(),
                RelayGrant {
                    user_id: "b".into(),
                    ..grant("other-b", "other-room", "10.231.0.2", true)
                },
            ),
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
        calls: AtomicUsize::new(0),
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

struct BlockingRefresh {
    authority: Arc<Authority>,
    blocked: AtomicBool,
    entered: AtomicBool,
    release: (Mutex<bool>, std::sync::Condvar),
}

impl GrantValidator for BlockingRefresh {
    fn validate(&self, ticket: &str, endpoint: SocketAddr) -> Result<RelayGrant, String> {
        if self.blocked.load(Ordering::SeqCst) {
            self.entered.store(true, Ordering::SeqCst);
            let (released, _) = self
                .release
                .1
                .wait_timeout_while(
                    self.release.0.lock().unwrap(),
                    Duration::from_secs(6),
                    |released| !*released,
                )
                .unwrap();
            if !*released {
                return Err("CONTROLLED_AUTHORITY_DELAY".into());
            }
        }
        self.authority.validate(ticket, endpoint)
    }
}

struct ReleaseRefresh(Arc<BlockingRefresh>);

impl Drop for ReleaseRefresh {
    fn drop(&mut self) {
        *self.0.release.0.lock().unwrap() = true;
        self.0.release.1.notify_all();
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn blocked_refresh_preserves_local_forwarding_only_until_authorization_deadline() {
    let (fixture, _, authority) = fixture().await;
    fixture.stop().await;
    let validator = Arc::new(BlockingRefresh {
        authority,
        blocked: AtomicBool::new(false),
        entered: AtomicBool::new(false),
        release: (Mutex::new(false), std::sync::Condvar::new()),
    });
    let release = ReleaseRefresh(validator.clone());
    let certificate = rcgen::generate_simple_self_signed(vec!["localhost".into()]).unwrap();
    let der = certificate.cert.der().to_vec();
    let relay = QuicRelayServer::bind_der(
        "127.0.0.1:0".parse().unwrap(),
        der.clone(),
        certificate.signing_key.serialize_der(),
        validator.clone(),
    )
    .unwrap();
    let source = connect(&relay, &der, "a").await;
    let target = connect(&relay, &der, "b").await;
    validator.blocked.store(true, Ordering::SeqCst);
    tokio::time::timeout(Duration::from_secs(2), async {
        while !validator.entered.load(Ordering::SeqCst) {
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
    })
    .await
    .unwrap();
    source
        .send(
            "10.231.0.2".parse().unwrap(),
            b"local-authorized-ciphertext",
        )
        .unwrap();
    let forwarded = tokio::time::timeout(Duration::from_millis(500), target.receive())
        .await
        .expect("data path must not wait for blocked remote refresh")
        .unwrap();
    assert_eq!(forwarded.1, b"local-authorized-ciphertext");
    assert!(!*validator.release.0.lock().unwrap());
    assert!(
        tokio::time::timeout(Duration::from_secs(4), target.receive())
            .await
            .expect("stale authorization must close even while refresh is blocked")
            .is_err()
    );
    assert!(!*validator.release.0.lock().unwrap());
    drop(release);
    tokio::time::sleep(Duration::from_millis(100)).await;
    assert!(target.receive().await.is_err());
    drop((source, target));
    relay.stop().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn push_revocation_during_blocked_refresh_cannot_be_undone_by_late_success() {
    let (fixture, _, authority) = fixture().await;
    fixture.stop().await;
    let validator = Arc::new(BlockingRefresh {
        authority,
        blocked: AtomicBool::new(false),
        entered: AtomicBool::new(false),
        release: (Mutex::new(false), std::sync::Condvar::new()),
    });
    let release = ReleaseRefresh(validator.clone());
    let cert = rcgen::generate_simple_self_signed(vec!["localhost".into()]).unwrap();
    let der = cert.cert.der().to_vec();
    let relay = QuicRelayServer::bind_der(
        "127.0.0.1:0".parse().unwrap(),
        der.clone(),
        cert.signing_key.serialize_der(),
        validator.clone(),
    )
    .unwrap();
    let source = connect(&relay, &der, "a").await;
    let target = connect(&relay, &der, "b").await;
    validator.blocked.store(true, Ordering::SeqCst);
    tokio::time::timeout(Duration::from_secs(2), async {
        while !validator.entered.load(Ordering::SeqCst) {
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
    })
    .await
    .unwrap();
    relay
        .revocations()
        .apply(&RevocationCommand {
            version: 1,
            event_id: "blocked-refresh-revoke".into(),
            activity_id: "room".into(),
            user_id: "b".into(),
            member_generation: 1,
        })
        .unwrap();
    source
        .send("10.231.0.2".parse().unwrap(), b"forbidden-after-push")
        .unwrap();
    assert!(
        tokio::time::timeout(Duration::from_millis(500), target.receive())
            .await
            .unwrap()
            .is_err()
    );
    drop(release);
    assert!(
        RelayClient::connect(relay.local_addr(), "localhost", Some(der), "b")
            .await
            .is_err()
    );
    drop((source, target));
    relay.stop().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn datagram_burst_checks_memory_without_authority_calls_per_packet() {
    let (relay, certificate, authority) = fixture().await;
    let source = connect(&relay, &certificate, "a").await;
    let target = connect(&relay, &certificate, "b").await;
    let calls = authority.calls.load(Ordering::SeqCst);
    tokio::time::timeout(Duration::from_millis(800), async {
        for index in 0..32u8 {
            source
                .send("10.231.0.2".parse().unwrap(), &[index])
                .unwrap();
            assert_eq!(target.receive().await.unwrap().1, [index]);
        }
    })
    .await
    .expect("bounded burst must not perform remote per-packet work");
    assert_eq!(authority.calls.load(Ordering::SeqCst), calls);
    drop((source, target));
    relay.stop().await;
}

struct ChangingAuthority {
    refresh: AtomicUsize,
    change: fn(&mut RelayGrant),
}

impl GrantValidator for ChangingAuthority {
    fn validate(&self, ticket: &str, _: SocketAddr) -> Result<RelayGrant, String> {
        let mut value = grant(ticket, "room", "10.231.0.1", true);
        if self.refresh.fetch_add(1, Ordering::SeqCst) > 0 {
            (self.change)(&mut value);
        }
        Ok(value)
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn renewal_can_extend_expiry_but_cannot_change_session_identity_or_policy() {
    for change in [
        (|value: &mut RelayGrant| value.activity_generation += 1) as fn(&mut RelayGrant),
        |value: &mut RelayGrant| value.member_generation += 1,
        |value: &mut RelayGrant| value.policy_revision += 1,
        |value: &mut RelayGrant| value.device_id = "replacement-device".into(),
        |value: &mut RelayGrant| value.lease_id = "replacement-lease".into(),
        |value: &mut RelayGrant| value.source_ipv4 = "10.231.0.2".into(),
        |value: &mut RelayGrant| value.allow_relay = false,
    ] {
        let validator = Arc::new(ChangingAuthority {
            refresh: AtomicUsize::new(0),
            change,
        });
        let cert = rcgen::generate_simple_self_signed(vec!["localhost".into()]).unwrap();
        let der = cert.cert.der().to_vec();
        let relay = QuicRelayServer::bind_der(
            "127.0.0.1:0".parse().unwrap(),
            der.clone(),
            cert.signing_key.serialize_der(),
            validator,
        )
        .unwrap();
        let client = connect(&relay, &der, "a").await;
        assert!(
            tokio::time::timeout(Duration::from_secs(2), client.receive())
                .await
                .unwrap()
                .is_err()
        );
        drop(client);
        relay.stop().await;
    }
    let (relay, cert, authority) = fixture().await;
    let client = connect(&relay, &cert, "a").await;
    assert!(
        tokio::time::timeout(Duration::from_millis(3500), client.receive())
            .await
            .is_err()
    );
    assert!(authority.calls.load(Ordering::SeqCst) >= 4);
    drop(client);
    relay.stop().await;
}

struct FixedAuthority(RelayGrant);

impl GrantValidator for FixedAuthority {
    fn validate(&self, _: &str, _: SocketAddr) -> Result<RelayGrant, String> {
        Ok(self.0.clone())
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn absolute_ticket_expiry_closes_idle_session_without_waiting_for_refresh() {
    let mut value = grant("a", "room", "10.231.0.1", true);
    value.expires_at_unix_millis = (std::time::SystemTime::now() + Duration::from_millis(700))
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_millis() as u64;
    let cert = rcgen::generate_simple_self_signed(vec!["localhost".into()]).unwrap();
    let der = cert.cert.der().to_vec();
    let relay = QuicRelayServer::bind_der(
        "127.0.0.1:0".parse().unwrap(),
        der.clone(),
        cert.signing_key.serialize_der(),
        Arc::new(FixedAuthority(value)),
    )
    .unwrap();
    let client = connect(&relay, &der, "a").await;
    assert!(
        tokio::time::timeout(Duration::from_millis(900), client.receive())
            .await
            .unwrap()
            .is_err()
    );
    drop(client);
    relay.stop().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn active_cached_sessions_in_different_activity_generations_or_policies_never_exchange() {
    for other in [
        RelayGrant {
            activity_generation: 2,
            ..grant("b", "room", "10.231.0.2", true)
        },
        RelayGrant {
            policy_revision: 2,
            ..grant("b", "room", "10.231.0.2", true)
        },
    ] {
        let authority = Arc::new(Authority {
            grants: HashMap::from([
                ("a".into(), grant("a", "room", "10.231.0.1", true)),
                ("b".into(), other),
            ]),
            rejected: Mutex::default(),
            calls: AtomicUsize::new(0),
        });
        let cert = rcgen::generate_simple_self_signed(vec!["localhost".into()]).unwrap();
        let der = cert.cert.der().to_vec();
        let relay = QuicRelayServer::bind_der(
            "127.0.0.1:0".parse().unwrap(),
            der.clone(),
            cert.signing_key.serialize_der(),
            authority,
        )
        .unwrap();
        let source = connect(&relay, &der, "a").await;
        let target = connect(&relay, &der, "b").await;
        source
            .send(
                "10.231.0.2".parse().unwrap(),
                b"forbidden-generation-or-policy",
            )
            .unwrap();
        assert!(
            tokio::time::timeout(Duration::from_millis(250), target.receive())
                .await
                .is_err()
        );
        drop((source, target));
        relay.stop().await;
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn stale_handshake_cannot_replace_a_live_higher_generation_mapping() {
    let (relay, cert, _) = fixture().await;
    let source = connect(&relay, &cert, "a").await;
    let current = connect(&relay, &cert, "new-b").await;
    assert!(
        RelayClient::connect(relay.local_addr(), "localhost", Some(cert), "b")
            .await
            .is_err()
    );
    source
        .send(
            "10.231.0.2".parse().unwrap(),
            b"higher-generation-still-current",
        )
        .unwrap();
    assert_eq!(
        tokio::time::timeout(Duration::from_millis(500), current.receive())
            .await
            .unwrap()
            .unwrap()
            .1,
        b"higher-generation-still-current"
    );
    drop((source, current));
    relay.stop().await;
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
async fn receiver_revoked_in_control_plane_is_closed_within_two_seconds_without_push() {
    let (server, cert, authority) = fixture().await;
    let a = connect(&server, &cert, "a").await;
    let b = connect(&server, &cert, "b").await;
    let target = "10.231.0.2".parse().unwrap();
    a.send(target, b"before").unwrap();
    assert_eq!(b.receive().await.unwrap().1, b"before");
    authority.rejected.lock().unwrap().insert("b".into());
    a.send(target, b"must-not-arrive").unwrap();
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

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn authenticated_management_revokes_source_and_target_without_waiting_for_validation_failure()
{
    let (relay, cert, authority) = fixture().await;
    let management = RelayManagementServer::bind(
        "127.0.0.1:0".parse().unwrap(),
        "revocation-key-12345678901234567890".into(),
        relay.revocations(),
    )
    .await
    .unwrap();
    let http = reqwest::Client::builder().no_proxy().build().unwrap();
    let url = format!(
        "http://{}/internal/relay/v1/revocations",
        management.local_addr()
    );
    let a = connect(&relay, &cert, "a").await;
    let b = connect(&relay, &cert, "b").await;
    let other = connect(&relay, &cert, "other").await;
    let other_b = connect(&relay, &cert, "other-b").await;
    let command = RevocationCommand {
        version: 1,
        event_id: "revoke-b".into(),
        activity_id: "room".into(),
        user_id: "b".into(),
        member_generation: 1,
    };
    for key in ["wrong-key", ""] {
        assert_eq!(
            http.post(&url)
                .header("X-Relay-Revocation-Key", key)
                .json(&command)
                .send()
                .await
                .unwrap()
                .status(),
            403
        );
    }
    a.send("10.231.0.2".parse().unwrap(), b"still-authorized")
        .unwrap();
    assert_eq!(
        tokio::time::timeout(Duration::from_secs(2), b.receive())
            .await
            .unwrap()
            .unwrap()
            .1,
        b"still-authorized"
    );
    let response = http
        .post(&url)
        .header(
            "X-Relay-Revocation-Key",
            "revocation-key-12345678901234567890",
        )
        .json(&command)
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), 200);
    assert_eq!(
        response.json::<serde_json::Value>().await.unwrap(),
        serde_json::json!({"version": 1, "eventId": "revoke-b", "status": "applied"})
    );
    assert!(tokio::time::timeout(Duration::from_secs(2), b.receive())
        .await
        .unwrap()
        .is_err());
    assert!(authority.rejected.lock().unwrap().is_empty());
    assert!(
        RelayClient::connect(relay.local_addr(), "localhost", Some(cert.clone()), "b")
            .await
            .is_err()
    );
    other
        .send("10.231.0.2".parse().unwrap(), b"other-activity-unaffected")
        .unwrap();
    assert_eq!(
        tokio::time::timeout(Duration::from_secs(2), other_b.receive())
            .await
            .unwrap()
            .unwrap()
            .1,
        b"other-activity-unaffected"
    );
    let new_b = connect(&relay, &cert, "new-b").await;
    assert_eq!(
        http.post(&url)
            .header(
                "X-Relay-Revocation-Key",
                "revocation-key-12345678901234567890"
            )
            .json(&command)
            .send()
            .await
            .unwrap()
            .status(),
        200
    );
    a.send("10.231.0.2".parse().unwrap(), b"new-generation-unaffected")
        .unwrap();
    assert_eq!(
        tokio::time::timeout(Duration::from_secs(2), new_b.receive())
            .await
            .unwrap()
            .unwrap()
            .1,
        b"new-generation-unaffected"
    );
    new_b
        .send("10.231.0.1".parse().unwrap(), b"reverse-before-revoke")
        .unwrap();
    assert_eq!(
        tokio::time::timeout(Duration::from_secs(2), a.receive())
            .await
            .unwrap()
            .unwrap()
            .1,
        b"reverse-before-revoke"
    );
    let source = RevocationCommand {
        event_id: "revoke-a".into(),
        user_id: "a".into(),
        ..command
    };
    assert_eq!(
        http.post(&url)
            .header(
                "X-Relay-Revocation-Key",
                "revocation-key-12345678901234567890"
            )
            .json(&source)
            .send()
            .await
            .unwrap()
            .status(),
        200
    );
    assert!(tokio::time::timeout(Duration::from_secs(2), a.receive())
        .await
        .unwrap()
        .is_err());
    assert!(
        RelayClient::connect(relay.local_addr(), "localhost", Some(cert), "a")
            .await
            .is_err()
    );
    drop((a, b, new_b, other, other_b));
    management.stop().await;
    relay.stop().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn management_rejects_unbounded_invalid_or_unversioned_requests() {
    let (relay, cert, _) = fixture().await;
    let key = "revocation-key-12345678901234567890";
    assert!(RelayManagementServer::bind(
        "0.0.0.0:0".parse().unwrap(),
        key.into(),
        relay.revocations()
    )
    .await
    .is_err());
    assert!(RelayManagementServer::bind(
        "127.0.0.1:0".parse().unwrap(),
        "short".into(),
        relay.revocations()
    )
    .await
    .is_err());
    let management = RelayManagementServer::bind(
        "127.0.0.1:0".parse().unwrap(),
        key.into(),
        relay.revocations(),
    )
    .await
    .unwrap();
    let http = reqwest::Client::builder().no_proxy().build().unwrap();
    let url = format!(
        "http://{}/internal/relay/v1/revocations",
        management.local_addr()
    );
    let valid = serde_json::json!({"version": 1, "eventId": "event", "activityId": "room", "userId": "b", "memberGeneration": 1});
    for body in [
        "{}".to_string(),
        valid.to_string().replace("\"version\":1", "\"version\":2"),
        valid
            .to_string()
            .replace("\"memberGeneration\":1", "\"memberGeneration\":0"),
        valid
            .to_string()
            .replace("\"event\"", "\"invalid/identifier\""),
        valid
            .to_string()
            .replace("\"version\":1", "\"unknown\":true,\"version\":1"),
    ] {
        assert_eq!(
            http.post(&url)
                .header("X-Relay-Revocation-Key", key)
                .header("Content-Type", "application/json")
                .body(body)
                .send()
                .await
                .unwrap()
                .status(),
            400
        );
    }
    assert_eq!(
        http.post(&url)
            .header("X-Relay-Revocation-Key", key)
            .header("Content-Type", "application/json")
            .body("x".repeat(4097))
            .send()
            .await
            .unwrap()
            .status(),
        413
    );
    assert_eq!(
        http.post(&url)
            .header("X-Relay-Revocation-Key", key)
            .body(valid.to_string())
            .send()
            .await
            .unwrap()
            .status(),
        415
    );
    assert_eq!(
        http.get(&url)
            .header("X-Relay-Revocation-Key", key)
            .send()
            .await
            .unwrap()
            .status(),
        404
    );
    assert_eq!(
        http.post(format!("{url}?key=forbidden"))
            .header("X-Relay-Revocation-Key", key)
            .json(&valid)
            .send()
            .await
            .unwrap()
            .status(),
        404
    );
    let a = connect(&relay, &cert, "a").await;
    let b = connect(&relay, &cert, "b").await;
    a.send(
        "10.231.0.2".parse().unwrap(),
        b"invalid-commands-did-not-revoke",
    )
    .unwrap();
    assert_eq!(
        tokio::time::timeout(Duration::from_secs(2), b.receive())
            .await
            .unwrap()
            .unwrap()
            .1,
        b"invalid-commands-did-not-revoke"
    );
    drop((a, b));
    management.stop().await;
    relay.stop().await;
}

struct PausedValidation {
    authority: Arc<Authority>,
    entered: Mutex<Option<tokio::sync::oneshot::Sender<()>>>,
    release: (Mutex<bool>, std::sync::Condvar),
}

impl GrantValidator for PausedValidation {
    fn validate(&self, ticket: &str, endpoint: SocketAddr) -> Result<RelayGrant, String> {
        let grant = self.authority.validate(ticket, endpoint)?;
        if let Some(entered) = self.entered.lock().unwrap().take() {
            let _ = entered.send(());
            let (released, _) = self
                .release
                .1
                .wait_timeout_while(
                    self.release.0.lock().unwrap(),
                    Duration::from_secs(5),
                    |released| !*released,
                )
                .unwrap();
            if !*released {
                return Err("TEST_VALIDATION_DEADLINE".into());
            }
        }
        Ok(grant)
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn revoke_during_successful_remote_validation_cannot_authenticate_stale_handshake() {
    let (fixture, _, authority) = fixture().await;
    fixture.stop().await;
    let (entered, ready) = tokio::sync::oneshot::channel();
    let validator = Arc::new(PausedValidation {
        authority,
        entered: Mutex::new(Some(entered)),
        release: (Mutex::new(false), std::sync::Condvar::new()),
    });
    let certificate = rcgen::generate_simple_self_signed(vec!["localhost".into()]).unwrap();
    let relay = QuicRelayServer::bind_der(
        "127.0.0.1:0".parse().unwrap(),
        certificate.cert.der().to_vec(),
        certificate.signing_key.serialize_der(),
        validator.clone(),
    )
    .unwrap();
    let address = relay.local_addr();
    let der = certificate.cert.der().to_vec();
    let handshake =
        tokio::spawn(
            async move { RelayClient::connect(address, "localhost", Some(der), "b").await },
        );
    tokio::time::timeout(Duration::from_secs(2), ready)
        .await
        .unwrap()
        .unwrap();
    relay
        .revocations()
        .apply(&RevocationCommand {
            version: 1,
            event_id: "handshake-revocation".into(),
            activity_id: "room".into(),
            user_id: "b".into(),
            member_generation: 1,
        })
        .unwrap();
    *validator.release.0.lock().unwrap() = true;
    validator.release.1.notify_all();
    assert!(tokio::time::timeout(Duration::from_secs(2), handshake)
        .await
        .unwrap()
        .unwrap()
        .is_err());
    relay.stop().await;
}
