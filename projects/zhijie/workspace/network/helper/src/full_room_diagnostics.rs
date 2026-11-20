use std::sync::{
    atomic::{AtomicU8, Ordering},
    Arc,
};
use std::time::Instant;

pub(super) struct PumpDiagnostic {
    mode: String,
    member: usize,
    phase: Arc<AtomicU8>,
    renewal_round: Arc<AtomicU8>,
    started: Instant,
    code: &'static str,
}

impl PumpDiagnostic {
    pub(super) fn new(
        mode: String,
        member: usize,
        phase: Arc<AtomicU8>,
        renewal_round: Arc<AtomicU8>,
        started: Instant,
    ) -> Self {
        assert!(matches!(mode.as_str(), "direct" | "relay" | "mixed"));
        Self {
            mode,
            member,
            phase,
            renewal_round,
            started,
            code: "TASK_DROPPED",
        }
    }

    pub(super) fn record(&mut self, result: &Result<(), String>) {
        self.code = exit_code(result);
    }

    fn line(&self) -> String {
        format!(
            "REAL_NETWORK_PUMP_EXIT mode={} member={} phase={} renewal_round={} elapsed_ms={} code={}",
            self.mode,
            self.member,
            self.phase.load(Ordering::Relaxed),
            self.renewal_round.load(Ordering::Relaxed),
            self.started.elapsed().as_millis(),
            self.code,
        )
    }
}

impl Drop for PumpDiagnostic {
    fn drop(&mut self) {
        eprintln!("{}", self.line());
    }
}

pub(super) fn exit_code(result: &Result<(), String>) -> &'static str {
    const CODES: &[&str] = &[
        "CONTROL_PLANE_UNAVAILABLE",
        "NETWORK_GRANT_INVALID",
        "NETWORK_AUTHORITY_RESPONSE_INVALID",
        "NETWORK_GRANT_MISMATCH",
        "GRANT_EXPIRED",
        "GRANT_EXPIRY_INVALID",
        "PRIVATE_KEY_INVALID",
        "DIRECT_UNAVAILABLE",
        "PEER_LIMIT_EXCEEDED",
        "PEER_IP_INVALID",
        "PEER_PUBLIC_KEY_INVALID",
        "PEER_ENDPOINT_INVALID",
        "NETWORK_RECEIVE_FAILED",
        "NETWORK_SEND_FAILED",
        "NETWORK_WRITE_TIMEOUT",
        "TUN_READ_FAILED",
        "TUN_WRITE_FAILED",
        "RELAY_PATH_UNAVAILABLE",
        "RELAY_ADDRESS_INVALID",
        "RELAY_CA_INVALID",
        "RELAY_SOCKET_FAILED",
        "RELAY_TLS_CONFIGURATION_INVALID",
        "RELAY_CONNECTION_FAILED",
        "RELAY_CONNECTION_TIMEOUT",
        "RELAY_AUTH_FAILED",
        "RELAY_CONNECTION_LOST",
        "RELAY_PACKET_SIZE_INVALID",
        "RELAY_SEND_FAILED",
    ];
    match result {
        Ok(()) => "OK",
        Err(code) => CODES
            .iter()
            .copied()
            .find(|allowed| *allowed == code)
            .unwrap_or("UNCLASSIFIED_ERROR"),
    }
}

#[test]
fn diagnostics_preserve_context_without_printing_unknown_errors() {
    let phase = Arc::new(AtomicU8::new(1));
    let renewal_round = Arc::new(AtomicU8::new(0));
    let mut diagnostic = PumpDiagnostic::new(
        "direct".into(),
        7,
        phase.clone(),
        renewal_round.clone(),
        Instant::now(),
    );
    assert!(diagnostic.line().ends_with("code=TASK_DROPPED"));
    phase.store(2, Ordering::Relaxed);
    renewal_round.store(2, Ordering::Relaxed);
    diagnostic.record(&Err("CONTROL_PLANE_UNAVAILABLE".into()));
    let line = diagnostic.line();
    assert!(line.starts_with(
        "REAL_NETWORK_PUMP_EXIT mode=direct member=7 phase=2 renewal_round=2 elapsed_ms="
    ));
    assert!(line.ends_with("code=CONTROL_PLANE_UNAVAILABLE"));
    assert_eq!(exit_code(&Ok(())), "OK");
    for error in [
        "ticket=private-credential",
        "PRIVATE_KEY_INVALID private-key",
        "NETWORK_GRANT_INVALID\nprivate-credential",
        "",
    ] {
        diagnostic.record(&Err(error.into()));
        let line = diagnostic.line();
        assert!(line.ends_with("code=UNCLASSIFIED_ERROR"));
        assert!(!line.contains("private-credential"));
        assert!(!line.contains("private-key"));
        assert_eq!(line.lines().count(), 1);
    }
}
