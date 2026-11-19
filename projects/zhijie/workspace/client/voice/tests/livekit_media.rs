use std::time::Duration;

use livekit::options::TrackPublishOptions;
use livekit::prelude::*;
use livekit::webrtc::{
    audio_frame::AudioFrame,
    audio_source::{native::NativeAudioSource, AudioSourceOptions, RtcAudioSource},
    audio_stream::native::NativeAudioStream,
};
use tokio::sync::mpsc;
use tokio_stream::StreamExt;

async fn receive_audio(events: &mut mpsc::UnboundedReceiver<RoomEvent>) -> usize {
    while let Some(event) = events.recv().await {
        if let RoomEvent::TrackSubscribed {
            track: RemoteTrack::Audio(track),
            ..
        } = event
        {
            let native = track.rtc_track();
            // 读取真实远端 WebRTC source，而非应用快照；克隆共享同一播放增益。
            for gain in [0.0, 0.25, 1.0, 4.0, 1.0] {
                assert!(native.set_volume(gain));
                assert!((native.clone().volume() - gain).abs() < 0.000001);
            }
            for invalid in [f64::NAN, f64::INFINITY, -0.1, 10.1] {
                assert!(!native.set_volume(invalid));
                assert_eq!(native.volume(), 1.0);
            }
            let mut stream = NativeAudioStream::new(track.rtc_track(), 48000, 1);
            let mut audible_frames = 0;
            while let Some(frame) = stream.next().await {
                if frame.data.iter().any(|sample| sample.unsigned_abs() > 100) {
                    audible_frames += 1;
                    if audible_frames >= 20 {
                        return audible_frames;
                    }
                }
            }
        }
    }
    panic!("SFU closed before receiving audio");
}

async fn publish_audio(room: &Room, frequency: f32) -> tokio::task::JoinHandle<()> {
    publish_controlled_audio(room, frequency).await.1
}

async fn publish_controlled_audio(
    room: &Room,
    frequency: f32,
) -> (LocalAudioTrack, tokio::task::JoinHandle<()>) {
    let source = NativeAudioSource::new(AudioSourceOptions::default(), 48000, 1, 100);
    let track = LocalAudioTrack::create_audio_track(
        "controlled-tone",
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
        .expect("publish microphone source");
    let producer = tokio::spawn(async move {
        let mut offset = 0;
        loop {
            let mut frame = AudioFrame::new(48000, 1, 480);
            for sample in frame.data.to_mut() {
                *sample = (6000.0
                    * (std::f32::consts::TAU * frequency * offset as f32 / 48000.0).sin())
                    as i16;
                offset = (offset + 1) % 48000;
            }
            source
                .capture_frame(&frame)
                .await
                .expect("capture controlled PCM");
        }
    });
    (track, producer)
}

// 显式连接真实 SFU；普通单元测试不需要服务端、设备或凭据。
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
#[ignore = "requires local SFU and scoped test tokens"]
async fn bidirectional_audio_and_server_removal() {
    assert_eq!(
        zhijie_voice::VoiceManager::default().snapshot().state,
        zhijie_voice::VoiceConnectionState::Disconnected
    );
    let url = std::env::var("ZHIJIE_TEST_VOICE_URL").expect("SFU URL");
    let token_a = std::env::var("ZHIJIE_TEST_VOICE_TOKEN_A").expect("participant A token");
    let token_b = std::env::var("ZHIJIE_TEST_VOICE_TOKEN_B").expect("participant B token");
    let rounds = if std::env::var_os("ZHIJIE_TEST_VOICE_REVOCATION_READY").is_some() {
        1
    } else {
        3
    };
    for _ in 0..rounds {
        media_round(&url, &token_a, &token_b).await;
    }
}

async fn media_round(url: &str, token_a: &str, token_b: &str) {
    let (room_a, mut events_a) = Room::connect(&url, &token_a, RoomOptions::default())
        .await
        .expect("connect A");
    let (room_b, mut events_b) = Room::connect(&url, &token_b, RoomOptions::default())
        .await
        .expect("connect B");
    let (microphone_a, producer_a) = publish_controlled_audio(&room_a, 440.0).await;
    let producer_b = publish_audio(&room_b, 660.0).await;
    let result = tokio::time::timeout(Duration::from_secs(20), async {
        tokio::join!(receive_audio(&mut events_a), receive_audio(&mut events_b))
    })
    .await;
    assert!(
        result.is_ok(),
        "both clients must receive decoded non-silent audio"
    );
    if let Some(ready) = std::env::var_os("ZHIJIE_TEST_VOICE_REVOCATION_READY") {
        // 只向测试父进程报告已收到真实 PCM；业务操作与 outbox 投递由控制面执行。
        std::fs::write(ready, b"MEDIA_READY").expect("publish media readiness");
        tokio::time::timeout(Duration::from_secs(5), async {
            tokio::join!(
                wait_disconnected(&mut events_a),
                wait_disconnected(&mut events_b)
            );
        })
        .await
        .expect("control-plane revocation disconnects both media clients within five seconds");
        producer_a.abort();
        producer_b.abort();
        close_room(&room_a).await;
        close_room(&room_b).await;
        return;
    }
    check_media_controls(&room_a, &room_b, &microphone_a).await;
    #[derive(serde::Serialize)]
    struct Removal {
        room: String,
        identity: String,
    }
    let admin_url = std::env::var("ZHIJIE_TEST_VOICE_ADMIN_URL").unwrap_or_else(|_| {
        url.replace("ws://", "http://")
            .replace("wss://", "https://")
    });
    let response = reqwest::Client::new()
        .post(format!(
            "{}/twirp/livekit.RoomService/RemoveParticipant",
            admin_url.trim_end_matches('/')
        ))
        .bearer_auth(std::env::var("ZHIJIE_TEST_VOICE_ADMIN_TOKEN").expect("scoped admin token"))
        .json(&Removal {
            room: room_a.name(),
            identity: room_a.local_participant().identity().to_string(),
        })
        .send()
        .await
        .expect("remove participant");
    assert!(
        response.status().is_success(),
        "server removal must succeed"
    );
    tokio::time::timeout(Duration::from_secs(5), async {
        while let Some(event) = events_a.recv().await {
            if let RoomEvent::Disconnected { .. } = event {
                return;
            }
        }
        panic!("missing disconnected event");
    })
    .await
    .expect("server removal disconnects within five seconds");
    producer_a.abort();
    producer_b.abort();
    close_room(&room_a).await;
    close_room(&room_b).await;
}

fn remote_audio(room: &Room) -> (RemoteTrackPublication, RemoteAudioTrack) {
    for participant in room.remote_participants().values() {
        for publication in participant.track_publications().values() {
            if let Some(RemoteTrack::Audio(track)) = publication.track() {
                return (publication.clone(), track);
            }
        }
    }
    panic!("subscribed remote audio required");
}

async fn observe_pcm(stream: &mut NativeAudioStream, duration: Duration) -> usize {
    let deadline = tokio::time::Instant::now() + duration;
    let mut audible = 0;
    while let Ok(frame) = tokio::time::timeout_at(deadline, stream.next()).await {
        let frame = frame.expect("audio stream remains open during media controls");
        if frame.data.iter().any(|sample| sample.unsigned_abs() > 100) {
            audible += 1;
        }
    }
    audible
}

async fn check_media_controls(room_a: &Room, room_b: &Room, microphone_a: &LocalAudioTrack) {
    let (publication_a, track_a) = remote_audio(room_a);
    let (_, track_b) = remote_audio(room_b);
    let mut incoming_a = NativeAudioStream::new(track_a.rtc_track(), 48000, 1);
    let mut incoming_b = NativeAudioStream::new(track_b.rtc_track(), 48000, 1);
    // 每段都同时消费双向流，避免积压旧帧冒充切换后的实时结果。
    let baseline = tokio::join!(
        observe_pcm(&mut incoming_a, Duration::from_secs(1)),
        observe_pcm(&mut incoming_b, Duration::from_secs(1))
    );
    assert!(
        baseline.0 >= 80 && baseline.1 >= 80,
        "both directions audible before controls: {baseline:?}"
    );
    let speaker = room_a.local_participant().identity().to_string();
    wait_remote_media_state(room_b, &speaker, false, true).await;
    microphone_a.mute();
    assert!(microphone_a.is_muted());
    tokio::join!(
        observe_pcm(&mut incoming_a, Duration::from_millis(500)),
        observe_pcm(&mut incoming_b, Duration::from_millis(500))
    );
    let muted = tokio::join!(
        observe_pcm(&mut incoming_a, Duration::from_secs(1)),
        observe_pcm(&mut incoming_b, Duration::from_secs(1))
    );
    assert!(
        muted.0 >= 80 && muted.1 == 0,
        "mute stops only outgoing microphone PCM: {muted:?}"
    );
    wait_remote_media_state(room_b, &speaker, true, false).await;
    microphone_a.unmute();
    assert!(!microphone_a.is_muted());
    tokio::join!(
        observe_pcm(&mut incoming_a, Duration::from_millis(500)),
        observe_pcm(&mut incoming_b, Duration::from_millis(500))
    );
    let unmuted = tokio::join!(
        observe_pcm(&mut incoming_a, Duration::from_secs(1)),
        observe_pcm(&mut incoming_b, Duration::from_secs(1))
    );
    assert!(
        unmuted.0 >= 80 && unmuted.1 >= 80,
        "unmute restores real PCM: {unmuted:?}"
    );
    wait_remote_media_state(room_b, &speaker, false, true).await;
    publication_a.set_enabled(false);
    tokio::join!(
        observe_pcm(&mut incoming_a, Duration::from_millis(500)),
        observe_pcm(&mut incoming_b, Duration::from_millis(500))
    );
    let disabled = tokio::join!(
        observe_pcm(&mut incoming_a, Duration::from_secs(1)),
        observe_pcm(&mut incoming_b, Duration::from_secs(1))
    );
    assert!(
        disabled.0 == 0 && disabled.1 >= 80,
        "receive disabled stops only incoming PCM: {disabled:?}"
    );
    publication_a.set_enabled(true);
    tokio::join!(
        observe_pcm(&mut incoming_a, Duration::from_millis(500)),
        observe_pcm(&mut incoming_b, Duration::from_millis(500))
    );
    let enabled = tokio::join!(
        observe_pcm(&mut incoming_a, Duration::from_secs(1)),
        observe_pcm(&mut incoming_b, Duration::from_secs(1))
    );
    assert!(
        enabled.0 >= 80 && enabled.1 >= 80,
        "receive re-enabled restores PCM: {enabled:?}"
    );
    println!("MEDIA_CONTROLS_OK baseline={baseline:?} muted={muted:?} unmuted={unmuted:?} receive_disabled={disabled:?} receive_enabled={enabled:?}");
    println!("MEMBER_MEDIA_STATE_OK speaking=true mute=true speaking_after_unmute=true");
}

async fn wait_remote_media_state(room: &Room, identity: &str, muted: bool, speaking: bool) {
    let (user, generation) = identity.rsplit_once(':').expect("scoped media identity");
    let generation = generation.parse::<u64>().expect("member generation");
    tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            if zhijie_voice::livekit_adapter::room_members(room)
                .iter()
                .any(|member| {
                    member.user_id == user
                        && member.member_generation == generation
                        && member.muted == muted
                        && member.speaking == speaking
                        && member.subscribed
                })
            {
                break;
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    })
    .await
    .expect("real SDK member speaking/mute state within five seconds");
}

async fn close_room(room: &Room) {
    match room.close().await {
        Ok(()) | Err(livekit::RoomError::AlreadyClosed) => {}
        Err(error) => panic!("close room: {error}"),
    }
}

async fn wait_disconnected(events: &mut mpsc::UnboundedReceiver<RoomEvent>) {
    while let Some(event) = events.recv().await {
        if let RoomEvent::Disconnected { .. } = event {
            return;
        }
    }
    panic!("missing disconnected event");
}

// 十二端同时发声，逐一核验远端身份、解码音调和五个时间窗口，避免只证明订阅成功。
#[tokio::test(flavor = "multi_thread", worker_threads = 8)]
#[ignore = "requires authenticated proxy and twelve control-plane member tokens"]
async fn full_room_all_to_all_audio() {
    // 组合测试固定观察七个窗口，覆盖网络续票、退出及策略撤销后的语音。
    let windows = if std::env::var("ZHIJIE_TEST_COMBINED_CHANNELS").as_deref() == Ok("true") {
        7
    } else {
        5
    };
    let url = std::env::var("ZHIJIE_TEST_VOICE_URL").expect("SFU URL");
    let mut rooms = Vec::new();
    let mut receivers = Vec::new();
    let mut producers = Vec::new();
    let mut frequencies = std::collections::HashMap::new();
    for index in 0..12 {
        let token = std::env::var(format!("ZHIJIE_TEST_VOICE_TOKEN_{index}"))
            .expect("control-plane member token");
        let (room, events) = Room::connect(&url, &token, RoomOptions::default())
            .await
            .expect("connect full-room participant");
        let frequency = 300.0 + index as f32 * 100.0;
        frequencies.insert(room.local_participant().identity().to_string(), frequency);
        producers.push(publish_audio(&room, frequency).await);
        rooms.push(room);
        receivers.push(events);
    }
    assert_eq!(frequencies.len(), 12, "twelve distinct media identities");
    let barrier = std::sync::Arc::new(tokio::sync::Barrier::new(133));
    let (clock, observation) = tokio::sync::watch::channel(None);
    let mut monitors = tokio::task::JoinSet::new();
    for (index, mut events) in receivers.into_iter().enumerate() {
        let barrier = barrier.clone();
        let mut observation = observation.clone();
        let mut expected = frequencies.clone();
        expected.remove(&rooms[index].local_participant().identity().to_string());
        monitors.spawn(async move {
            let mut tracks = tokio::task::JoinSet::new();
            while !expected.is_empty() {
                match events.recv().await.expect("full-room event channel") {
                    RoomEvent::TrackSubscribed {
                        track: RemoteTrack::Audio(track),
                        participant,
                        ..
                    } => {
                        let identity = participant.identity().to_string();
                        let frequency =
                            expected.remove(&identity).expect("unique expected speaker");
                        tracks.spawn(measure_full_room_track(
                            index,
                            identity,
                            track,
                            frequency,
                            barrier.clone(),
                            observation.clone(),
                            windows,
                        ));
                    }
                    RoomEvent::Disconnected { .. } => panic!("full-room client disconnected"),
                    _ => {}
                }
            }
            let start = *observation.wait_for(|value| value.is_some()).await.unwrap();
            let deadline = start.unwrap() + Duration::from_secs(windows as u64 * 15);
            loop {
                tokio::select! {
                    _ = tokio::time::sleep_until(deadline) => break,
                    event = events.recv() => match event.expect("full-room event channel") {
                        RoomEvent::Disconnected { .. } | RoomEvent::TrackUnsubscribed { .. } =>
                            panic!("full-room media lost during observation"),
                        _ => {}
                    }
                }
            }
            let mut count = 0;
            while let Some(result) = tracks.join_next().await {
                result.expect("per-direction PCM assertions");
                count += 1;
            }
            assert_eq!(count, 11);
            count
        });
    }
    // 所有真实音轨接收流已创建后才统一计时，启动延迟仍受独立截止约束。
    tokio::time::timeout(Duration::from_secs(20), barrier.wait())
        .await
        .expect("all 132 PCM streams ready before observation");
    clock.send(Some(tokio::time::Instant::now())).unwrap();
    if let Some(ready) = std::env::var_os("ZHIJIE_TEST_FULL_ROOM_READY") {
        std::fs::write(ready, b"ready").expect("publish full-room readiness");
    }
    let total = tokio::time::timeout(Duration::from_secs(windows as u64 * 15 + 15), async {
        let mut count = 0;
        while let Some(result) = monitors.join_next().await {
            count += result.expect("full-room receiver assertions");
        }
        count
    })
    .await
    .expect("full-room observation deadline");
    assert_eq!(
        total, 132,
        "every participant decodes all eleven other speakers"
    );
    for producer in producers {
        producer.abort();
    }
    for room in rooms {
        close_room(&room).await;
    }
    println!(
        "FULL_ROOM_OK participants=12 directions={total} observation_seconds={} windows={windows}",
        windows * 15
    );
}

async fn measure_full_room_track(
    receiver: usize,
    identity: String,
    track: RemoteAudioTrack,
    frequency: f32,
    barrier: std::sync::Arc<tokio::sync::Barrier>,
    mut observation: tokio::sync::watch::Receiver<Option<tokio::time::Instant>>,
    windows: usize,
) {
    let mut stream = NativeAudioStream::new(track.rtc_track(), 48000, 1);
    barrier.wait().await;
    let start = observation
        .wait_for(|value| value.is_some())
        .await
        .unwrap()
        .unwrap();
    let mut frames = vec![0usize; windows];
    let mut callbacks = vec![0usize; windows];
    let mut silent_frames = vec![0usize; windows];
    let mut max_gap_us = vec![0u128; windows];
    let mut previous_callback = start;
    let mut crossings = vec![0usize; windows];
    let mut samples = vec![0usize; windows];
    let mut previous = 0i16;
    let deadline = start + Duration::from_secs(windows as u64 * 15);
    while let Ok(frame) = tokio::time::timeout_at(deadline, stream.next()).await {
        let frame = frame.expect("full-room audio stream remains open");
        let window = (start.elapsed().as_secs() / 15) as usize;
        if window >= windows {
            break;
        }
        let now = tokio::time::Instant::now();
        max_gap_us[window] =
            max_gap_us[window].max(now.duration_since(previous_callback).as_micros());
        previous_callback = now;
        callbacks[window] += 1;
        if !frame.data.iter().any(|sample| sample.unsigned_abs() > 100) {
            silent_frames[window] += 1;
            continue;
        }
        frames[window] += 1;
        samples[window] += frame.data.len();
        for &sample in frame.data.iter() {
            if previous <= 0 && sample > 0 {
                crossings[window] += 1;
            }
            previous = sample;
        }
    }
    // 在质量断言前留下完整窗口，失败也能区分静音与接收回调不足。
    println!("PCM_CALLBACKS receiver={receiver} speaker={identity} callbacks={callbacks:?} silent={silent_frames:?} max_gap_us={max_gap_us:?}");
    for window in 0..windows {
        assert!(frames[window] >= 1200, "receiver {receiver}, speaker {identity}, window {window}: less than twelve seconds of non-silent PCM; frames={frames:?}");
        let measured = crossings[window] as f32 * 48000.0 / samples[window] as f32;
        assert!((measured - frequency).abs() < 15.0,
            "receiver {receiver}, speaker {identity}, window {window}: expected {frequency}Hz, got {measured}Hz");
    }
    println!("PCM_DIRECTION receiver={receiver} speaker={identity} frequency_hz={frequency} non_silent_frames={frames:?}");
}
