use std::io::{BufRead, Write};
use std::net::{Ipv4Addr, SocketAddr};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use zhijie_relay::control_plane::ControlPlaneValidator;
use zhijie_relay::management::RelayManagementServer;
use zhijie_relay::model::RelayGrant;
use zhijie_relay::quic::{QuicRelayServer, RelayClient};
use zhijie_relay::server::GrantValidator;

struct ObservedValidator {
    inner: ControlPlaneValidator,
    target_ticket: String,
    last_success: Arc<Mutex<Option<Instant>>>,
}

impl GrantValidator for ObservedValidator {
    fn validate(&self, ticket: &str, endpoint: SocketAddr) -> Result<RelayGrant, String> {
        let started = Instant::now();
        let result = self.inner.validate(ticket, endpoint);
        if ticket == self.target_ticket && result.is_ok() {
            *self.last_success.lock().unwrap() = Some(started);
        }
        result
    }
}

#[derive(Default)]
struct Counters {
    attempted: AtomicUsize,
    accepted: AtomicUsize,
    rejected: AtomicUsize,
    received: AtomicUsize,
}

struct Traffic {
    counters: Vec<Arc<Counters>>,
    closed: Vec<Arc<AtomicBool>>,
    closed_at: Vec<Arc<Mutex<Option<Instant>>>>,
    workers: Vec<tokio::task::JoinHandle<()>>,
}

impl Traffic {
    fn start(clients: &[Arc<RelayClient>], addresses: &[Ipv4Addr]) -> Self {
        let routes = [(0, 1), (1, 0), (0, 2), (2, 0)];
        let counters: Vec<_> = routes
            .iter()
            .map(|_| Arc::new(Counters::default()))
            .collect();
        let closed: Vec<_> = clients
            .iter()
            .map(|_| Arc::new(AtomicBool::new(false)))
            .collect();
        let mut workers = Vec::new();
        let closed_at: Vec<_> = clients.iter().map(|_| Arc::new(Mutex::new(None))).collect();
        for (index, (source, target)) in routes.iter().copied().enumerate() {
            let client = clients[source].clone();
            let target = addresses[target];
            let counter = counters[index].clone();
            workers.push(tokio::spawn(async move {
                let mut interval = tokio::time::interval(Duration::from_millis(20));
                interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
                loop {
                    interval.tick().await;
                    counter.attempted.fetch_add(1, Ordering::SeqCst);
                    if client.send(target, b"authority-loss-traffic").is_ok() {
                        counter.accepted.fetch_add(1, Ordering::SeqCst);
                    } else {
                        counter.rejected.fetch_add(1, Ordering::SeqCst);
                    }
                }
            }));
        }
        for (index, client) in clients.iter().enumerate() {
            let client = client.clone();
            let incoming: Vec<_> = routes
                .iter()
                .enumerate()
                .filter(|(_, (_, target))| *target == index)
                .map(|(route, (source, _))| (addresses[*source], counters[route].clone()))
                .collect();
            let closed = closed[index].clone();
            let ended = closed_at[index].clone();
            workers.push(tokio::spawn(async move {
                while let Ok((source, payload)) = client.receive().await {
                    assert_eq!(payload, b"authority-loss-traffic");
                    let (_, counter) = incoming
                        .iter()
                        .find(|(address, _)| *address == source)
                        .expect("only authenticated source addresses may arrive");
                    counter.received.fetch_add(1, Ordering::SeqCst);
                }
                *ended.lock().unwrap() = Some(Instant::now());
                closed.store(true, Ordering::SeqCst);
            }));
        }
        Self {
            counters,
            closed,
            closed_at,
            workers,
        }
    }

    fn received(&self) -> Vec<usize> {
        self.counters
            .iter()
            .map(|counter| counter.received.load(Ordering::SeqCst))
            .collect()
    }

    async fn stop(&mut self) {
        for worker in &self.workers {
            worker.abort();
        }
        for worker in self.workers.drain(..) {
            if let Err(error) = worker.await {
                assert!(
                    error.is_cancelled(),
                    "traffic observation task must not panic"
                );
            }
        }
    }
}

impl Drop for Traffic {
    fn drop(&mut self) {
        for worker in &self.workers {
            worker.abort();
        }
    }
}

async fn input() -> String {
    tokio::task::spawn_blocking(|| {
        let mut line = String::new();
        std::io::stdin().lock().read_line(&mut line).unwrap();
        line.trim().to_owned()
    })
    .await
    .unwrap()
}

fn signal(value: &str) {
    println!("{value}");
    std::io::stdout().flush().unwrap();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
#[ignore = "requires parent-controlled real database, HTTP blackhole and scoped tickets"]
async fn real_http_loss_bounds_quic_forwarding_without_client_stop() {
    let configuration: serde_json::Value = serde_json::from_str(&input().await).unwrap();
    let url = configuration["controlPlaneUrl"]
        .as_str()
        .unwrap()
        .to_owned();
    let key = configuration["validationKey"].as_str().unwrap().to_owned();
    let validator = tokio::task::spawn_blocking(move || ControlPlaneValidator::new(url, key))
        .await
        .unwrap()
        .unwrap();
    let last_success = Arc::new(Mutex::new(None));
    let validator = ObservedValidator {
        inner: validator,
        target_ticket: configuration["peers"][1]["ticket"]
            .as_str()
            .unwrap()
            .to_owned(),
        last_success: last_success.clone(),
    };
    let certificate = rcgen::generate_simple_self_signed(vec!["localhost".into()]).unwrap();
    let der = certificate.cert.der().to_vec();
    let relay = QuicRelayServer::bind_der(
        "127.0.0.1:0".parse().unwrap(),
        der.clone(),
        certificate.signing_key.serialize_der(),
        Arc::new(validator),
    )
    .unwrap();
    let management = RelayManagementServer::bind(
        configuration["managementAddress"]
            .as_str()
            .unwrap()
            .parse()
            .unwrap(),
        configuration["revocationKey"].as_str().unwrap().to_owned(),
        relay.revocations(),
    )
    .await
    .unwrap();
    let mut clients = Vec::new();
    let mut addresses = Vec::new();
    for peer in configuration["peers"].as_array().unwrap() {
        clients.push(Arc::new(
            RelayClient::connect(
                relay.local_addr(),
                "localhost",
                Some(der.clone()),
                peer["ticket"].as_str().unwrap(),
            )
            .await
            .unwrap(),
        ));
        addresses.push(peer["ip"].as_str().unwrap().parse::<Ipv4Addr>().unwrap());
    }
    let mut traffic = Traffic::start(&clients, &addresses);
    tokio::time::timeout(Duration::from_secs(2), async {
        while traffic.received().iter().any(|count| *count < 3) {
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
    })
    .await
    .expect("all four actual QUIC delivery directions must be established");
    signal("RELAY_AUTHORITY_READY");
    assert_eq!(input().await, "REVOKED");
    let started = Instant::now();
    if configuration["automaticPush"] != true {
        assert!(
            !traffic.closed[1].load(Ordering::SeqCst),
            "revoked connection must still be active at confirmation"
        );
    }
    let initial_attempts = traffic.counters[1].attempted.load(Ordering::SeqCst);
    let initial_accepted = traffic.counters[1].accepted.load(Ordering::SeqCst);
    let initial_received = traffic.received();
    signal("RELAY_AUTHORITY_REVOKED_OBSERVED");
    if configuration["mode"] == "push-offline" {
        assert_eq!(input().await, "PUSH_APPLIED");
        tokio::time::timeout(Duration::from_millis(500), async {
            while !traffic.closed[1].load(Ordering::SeqCst) {
                tokio::time::sleep(Duration::from_millis(2)).await;
            }
        })
        .await
        .expect("management push must close QUIC without waiting for the pending HTTP timeout");
        let closed = traffic.closed_at[1].lock().unwrap().unwrap();
        let last_authorized = last_success.lock().unwrap().unwrap();
        let before_deadline = closed.duration_since(last_authorized) < Duration::from_millis(2900);
        if configuration["automaticPush"] == true {
            println!(
                "RELAY_AUTHORITY_AUTOMATIC_CLOSE path={} since_last_authority_ms={}",
                if before_deadline {
                    "push"
                } else {
                    "deadline_or_push"
                },
                closed.duration_since(last_authorized).as_millis()
            );
            signal("RELAY_AUTHORITY_AUTOMATIC_CLOSED");
        } else {
            assert!(
                before_deadline,
                "push must close QUIC before the last real authority result's three-second deadline"
            );
            println!(
                "RELAY_AUTHORITY_PUSH_BEFORE_DEADLINE remaining_ms={}",
                (last_authorized + Duration::from_secs(3))
                    .duration_since(closed)
                    .as_millis()
            );
            signal("RELAY_AUTHORITY_PUSH_CLOSED");
        }
    }
    tokio::time::sleep_until((started + Duration::from_millis(3100)).into()).await;
    assert!(
        traffic.closed[1].load(Ordering::SeqCst),
        "server must forcibly close the affected QUIC connection"
    );
    assert!(!traffic.closed[0].load(Ordering::SeqCst));
    assert!(!traffic.closed[2].load(Ordering::SeqCst));
    let before = traffic.received();
    tokio::time::sleep(Duration::from_millis(350)).await;
    let after = traffic.received();
    assert_eq!(
        &before[..2],
        &after[..2],
        "revoked source and destination must both remain isolated"
    );
    for index in 2..4 {
        assert!(
            after[index] >= before[index] + 5,
            "unaffected QUIC traffic must continue"
        );
    }
    let attempts = traffic.counters[1].attempted.load(Ordering::SeqCst) - initial_attempts;
    let accepted = traffic.counters[1].accepted.load(Ordering::SeqCst) - initial_accepted;
    let rejected = traffic.counters[1].rejected.load(Ordering::SeqCst);
    assert!(
        attempts >= 100,
        "old sender must keep attempting without Stop or authorization polling"
    );
    assert!(
        rejected > 0,
        "server closure must reject further old-session send attempts"
    );
    if configuration["mode"] == "offline" {
        assert!(accepted >= 10);
        for index in 0..2 {
            assert!(
                before[index] >= initial_received[index] + 10,
                "pending HTTP must allow real bounded local forwarding before fail-closed expiry"
            );
        }
    }
    assert!(started.elapsed() < Duration::from_millis(4500));
    println!("RELAY_AUTHORITY_COUNTERS mode={} attempts={attempts} locally_accepted={accepted} rejected={rejected} isolated_deliveries=0 healthy_deliveries={} observed_ms={}",
        configuration["mode"].as_str().unwrap(), after[2] + after[3] - before[2] - before[3], started.elapsed().as_millis());
    signal("RELAY_AUTHORITY_ISOLATED");
    assert_eq!(input().await, "AUTHORITY_RESTORED");
    assert!(
        RelayClient::connect(
            relay.local_addr(),
            "localhost",
            Some(der),
            configuration["peers"][1]["ticket"].as_str().unwrap()
        )
        .await
        .is_err(),
        "real authority must reject the old ticket on reconnect"
    );
    traffic.stop().await;
    signal("RELAY_AUTHORITY_STALE_RECONNECT_REJECTED");
    drop(traffic);
    drop(clients);
    management.stop().await;
    relay.stop().await;
}
