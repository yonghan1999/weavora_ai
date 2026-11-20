use super::*;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::Arc;

async fn subscriptions(
    events: &mut mpsc::UnboundedReceiver<RoomEvent>,
    expected: &std::collections::HashMap<String, usize>,
) -> Vec<(usize, RemoteAudioTrack)> {
    let mut tracks = Vec::new();
    while tracks.len() < 2 {
        match events.recv().await.expect("session media events") {
            RoomEvent::TrackSubscribed {
                track: RemoteTrack::Audio(track),
                participant,
                ..
            } => {
                let index = expected[&participant.identity().to_string()];
                assert!(!tracks.iter().any(|(previous, _)| *previous == index));
                tracks.push((index, track));
            }
            RoomEvent::Disconnected { .. } => panic!("media disconnected before revocation"),
            _ => {}
        }
    }
    tracks
}

async fn continuous_audio(room: &Room, frequency: f32, attempts: Arc<AtomicUsize>) {
    let source = NativeAudioSource::new(AudioSourceOptions::default(), 48000, 1, 100);
    let track = LocalAudioTrack::create_audio_track(
        "session-revocation-tone",
        RtcAudioSource::Native(source.clone()),
    );
    room.local_participant()
        .publish_track(
            LocalTrack::Audio(track.clone()),
            TrackPublishOptions {
                source: TrackSource::Microphone,
                ..Default::default()
            },
        )
        .await
        .expect("publish actual scoped audio track");
    let mut offset = 0;
    loop {
        let mut frame = AudioFrame::new(48000, 1, 480);
        for sample in frame.data.to_mut() {
            *sample = (6000.0 * (std::f32::consts::TAU * frequency * offset as f32 / 48000.0).sin())
                as i16;
            offset = (offset + 1) % 48000;
        }
        attempts.fetch_add(1, Ordering::SeqCst);
        source.capture_frame(&frame).await.expect("continuous PCM");
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
#[ignore = "requires real scoped voice tickets and parent-controlled authentication revocation"]
async fn authentication_revocation_stops_only_target_media_without_client_stop() {
    assert_eq!(
        zhijie_voice::VoiceManager::default().snapshot().state,
        zhijie_voice::VoiceConnectionState::Disconnected
    );
    let url = std::env::var("ZHIJIE_TEST_VOICE_URL").unwrap();
    let directory = std::path::PathBuf::from(std::env::var("ZHIJIE_TEST_VOICE_DIRECTORY").unwrap());
    let mut rooms = Vec::new();
    let mut events = Vec::new();
    let mut workers = tokio::task::JoinSet::new();
    let mut attempts = Vec::new();
    let mut identities = std::collections::HashMap::new();
    for index in 0..3 {
        let token = std::env::var(format!("ZHIJIE_TEST_VOICE_TOKEN_{index}")).unwrap();
        let (room, incoming) = Room::connect(&url, &token, RoomOptions::default())
            .await
            .expect("scoped media connect");
        identities.insert(room.local_participant().identity().to_string(), index);
        let room = Arc::new(room);
        let sent = Arc::new(AtomicUsize::new(0));
        attempts.push(sent.clone());
        let publisher = room.clone();
        workers.spawn(async move {
            continuous_audio(&publisher, 440.0 + index as f32 * 220.0, sent).await;
        });
        rooms.push(room);
        events.push(incoming);
    }
    let counters: Vec<_> = (0..9).map(|_| Arc::new(AtomicUsize::new(0))).collect();
    let disconnected: Vec<_> = (0..3).map(|_| Arc::new(AtomicBool::new(false))).collect();
    for (receiver, mut incoming) in events.into_iter().enumerate() {
        let mut expected = identities.clone();
        expected.remove(&rooms[receiver].local_participant().identity().to_string());
        let tracks = tokio::time::timeout(
            Duration::from_secs(15),
            subscriptions(&mut incoming, &expected),
        )
        .await
        .expect("two actual subscribed speakers per client");
        for (sender, track) in tracks {
            let count = counters[receiver * 3 + sender].clone();
            workers.spawn(async move {
                let mut stream = NativeAudioStream::new(track.rtc_track(), 48000, 1);
                while let Some(frame) = stream.next().await {
                    assert_eq!(frame.sample_rate, 48000);
                    if frame.data.iter().any(|sample| sample.unsigned_abs() > 100) {
                        count.fetch_add(1, Ordering::SeqCst);
                    }
                }
            });
        }
        let closed = disconnected[receiver].clone();
        workers.spawn(async move {
            wait_disconnected(&mut incoming).await;
            closed.store(true, Ordering::SeqCst);
        });
    }
    let routes = [1, 2, 3, 5, 6, 7];
    tokio::time::timeout(Duration::from_secs(15), async {
        while routes
            .iter()
            .any(|index| counters[*index].load(Ordering::SeqCst) < 20)
        {
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
    })
    .await
    .expect("all six directions decode actual non-silent PCM");
    assert!(disconnected
        .iter()
        .all(|closed| !closed.load(Ordering::SeqCst)));
    std::fs::write(directory.join("ready"), b"SIX_DIRECTIONS_PCM").unwrap();
    tokio::time::timeout(Duration::from_secs(2), async {
        while !directory.join("revoked").is_file() {
            tokio::time::sleep(Duration::from_millis(2)).await;
        }
    })
    .await
    .expect("parent confirms real authentication revocation");
    let confirmed = tokio::time::Instant::now();
    let initial_attempts = attempts[1].load(Ordering::SeqCst);
    tokio::time::sleep(Duration::from_millis(3000)).await;
    assert!(disconnected[1].load(Ordering::SeqCst));
    assert!(!disconnected[0].load(Ordering::SeqCst));
    assert!(!disconnected[2].load(Ordering::SeqCst));
    let before: Vec<_> = counters
        .iter()
        .map(|count| count.load(Ordering::SeqCst))
        .collect();
    tokio::time::sleep(Duration::from_millis(500)).await;
    let after: Vec<_> = counters
        .iter()
        .map(|count| count.load(Ordering::SeqCst))
        .collect();
    for route in [1, 3, 5, 7] {
        assert_eq!(
            before[route], after[route],
            "revoked source and destination PCM must stop"
        );
    }
    for route in [2, 6] {
        assert!(
            after[route] >= before[route] + 20,
            "healthy PCM must remain live"
        );
    }
    let continued = attempts[1].load(Ordering::SeqCst) - initial_attempts;
    assert!(
        continued >= 100,
        "revoked source keeps attempting PCM without Stop"
    );
    while let Some(result) = workers.try_join_next() {
        result.expect("media observer or publisher must not panic");
    }
    assert!(confirmed.elapsed() < Duration::from_millis(4500));
    std::fs::write(
        directory.join("isolated"),
        b"TARGET_PCM_STOPPED_HEALTHY_PCM_CONTINUES",
    )
    .unwrap();
    println!("REAL_AUTH_VOICE_ISOLATED directions=6 target_directions=4 healthy_directions=2 old_publisher_stopped=false continued_frames={continued} observed_ms={}", confirmed.elapsed().as_millis());
    let old_token = std::env::var("ZHIJIE_TEST_VOICE_TOKEN_1").unwrap();
    assert!(
        tokio::time::timeout(
            Duration::from_secs(3),
            Room::connect(&url, &old_token, RoomOptions::default())
        )
        .await
        .expect("stale signal reconnect deadline")
        .is_err(),
        "old unexpired token cannot rejoin through the real authenticated proxy"
    );
    println!("REAL_AUTH_VOICE_STALE_RECONNECT_REJECTED");
    workers.abort_all();
    while let Some(result) = workers.join_next().await {
        if let Err(error) = result {
            assert!(error.is_cancelled());
        }
    }
    for room in &rooms {
        close_room(room).await;
    }
}
