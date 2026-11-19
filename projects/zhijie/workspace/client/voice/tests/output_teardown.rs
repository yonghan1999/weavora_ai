#![cfg(target_os = "windows")]

use livekit::webrtc::native::output_test::OutputTest;
use std::{process::Command, thread, time::Duration};

fn process_resources() -> (usize, usize) {
    let script = format!(
        "$proc = Get-Process -Id {}; $proc.Threads.Count; $proc.HandleCount",
        std::process::id()
    );
    let result = Command::new("powershell.exe")
        .args(["-NoProfile", "-NonInteractive", "-Command", &script])
        .output()
        .expect("sample the test process");
    assert!(result.status.success());
    let counts: Vec<usize> = String::from_utf8(result.stdout)
        .unwrap()
        .lines()
        .map(|line| line.trim().parse().unwrap())
        .collect();
    assert_eq!(counts.len(), 2);
    (counts[0], counts[1])
}

#[test]
fn repeated_rejected_outputs_release_native_threads_and_handles() {
    assert_eq!(zhijie_voice::VOICE_ADAPTER_PROTOCOL_VERSION, 1);
    let rejected = || {
        assert!(OutputTest::start("missing-teardown-device", f64::NAN).is_none());
        assert!(OutputTest::start("missing-teardown-device", 0.0).is_none());
    };
    rejected();
    let baseline = process_resources();
    for _ in 0..24 {
        rejected();
    }
    for _ in 0..5 {
        let current = process_resources();
        if current.0 <= baseline.0 + 2 && current.1 <= baseline.1 + 8 {
            return;
        }
        thread::sleep(Duration::from_millis(200));
    }
    panic!(
        "native resources did not settle: baseline={baseline:?}, final={:?}",
        process_resources()
    );
}
