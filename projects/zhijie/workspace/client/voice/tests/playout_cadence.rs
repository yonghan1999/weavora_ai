use std::time::{Duration, SystemTime, UNIX_EPOCH};

use livekit::options::TrackPublishOptions;
use livekit::prelude::*;
use livekit::webrtc::{
    audio_frame::AudioFrame,
    audio_source::{native::NativeAudioSource, AudioSourceOptions, RtcAudioSource},
    audio_stream::native::NativeAudioStream,
    peer_connection_factory::native::PeerConnectionFactoryExt,
};
use tokio_stream::StreamExt;

// 只测播放节拍：全零音频，不开启麦克风，不把回调成功作为可听音质验收。
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
#[ignore = "explicit local SFU diagnostic; platform mode uses silent host output"]
async fn silent_full_room_playout_cadence() {
    assert_eq!(zhijie_voice::VOICE_ADAPTER_PROTOCOL_VERSION, 1);
    let mode = std::env::var("ZHIJIE_CADENCE_MODE").expect("explicit playout mode");
    assert!(matches!(mode.as_str(), "synthetic" | "platform"));
    let signal = std::env::var("ZHIJIE_CADENCE_SIGNAL").unwrap_or_else(|_| "silence".into());
    assert!(matches!(signal.as_str(), "silence" | "tone"));
    assert!(signal == "silence" || mode == "synthetic");
    let windows: usize = std::env::var("ZHIJIE_CADENCE_WINDOWS")
        .unwrap_or_else(|_| "1".into())
        .parse()
        .expect("observation window count");
    assert!((1..=7).contains(&windows));
    let audio = if mode == "platform" {
        let audio = PlatformAudio::new().expect("initialize platform audio");
        // PlatformAudio 默认允许 ADM 录音；发布原生源前显式禁用，避免 SDK 自动启动采集。
        let runtime = livekit::rtc_engine::lk_runtime::LkRuntime::instance();
        runtime.pc_factory().set_adm_recording_enabled(false);
        assert!(!runtime.pc_factory().adm_recording_enabled());
        let output = audio
            .playout_devices()
            .next()
            .expect("host output required");
        audio.set_playout_device(&output.id).expect("select output");
        Some(audio)
    } else {
        None
    };
    let url = std::env::var("ZHIJIE_TEST_VOICE_URL").expect("local SFU URL");
    let mut rooms = Vec::new();
    let mut events = Vec::new();
    let mut local_tracks = Vec::new();
    let mut producers = tokio::task::JoinSet::new();
    for index in 0..12 {
        let token = std::env::var(format!("ZHIJIE_TEST_VOICE_TOKEN_{index}"))
            .expect("isolated diagnostic token");
        let (room, receiver) = Room::connect(&url, &token, RoomOptions::default())
            .await
            .expect("connect diagnostic participant");
        println!("PLAYOUT_CONNECTED mode={mode} participant={index}");
        let source = NativeAudioSource::new(AudioSourceOptions::default(), 48000, 1, 100);
        let track = LocalAudioTrack::create_audio_track(
            "silent-cadence",
            RtcAudioSource::Native(source.clone()),
        );
        local_tracks.push(track.rtc_track());
        room.local_participant()
            .publish_track(
                LocalTrack::Audio(track),
                TrackPublishOptions {
                    source: TrackSource::Microphone,
                    ..Default::default()
                },
            )
            .await
            .expect("publish zero PCM source");
        let tone = signal == "tone";
        producers.spawn(async move {
            let mut offset = 0;
            let frequency = 300.0 + index as f32 * 100.0;
            let mut frame = AudioFrame::new(48000, 1, 480);
            loop {
                if tone {
                    for sample in frame.data.to_mut() {
                        *sample = (6000.0
                            * (std::f32::consts::TAU * frequency * offset as f32 / 48000.0).sin())
                            as i16;
                        offset = (offset + 1) % 48000;
                    }
                }
                source
                    .capture_frame(&frame)
                    .await
                    .expect("send controlled PCM");
            }
        });
        rooms.push(room);
        events.push(receiver);
    }
    // 各房间并发收齐订阅，再统一观察；保留事件接收器直到结束。
    let mut subscriptions = tokio::task::JoinSet::new();
    for (receiver, mut events) in events.into_iter().enumerate() {
        subscriptions.spawn(async move {
            let mut streams = Vec::new();
            while streams.len() < 11 {
                if let RoomEvent::TrackSubscribed {
                    track: RemoteTrack::Audio(track),
                    participant,
                    ..
                } = events.recv().await.expect("room event channel")
                {
                    streams.push((
                        participant.identity().to_string(),
                        NativeAudioStream::new(track.rtc_track(), 48000, 1),
                    ));
                }
            }
            (receiver, streams, events)
        });
    }
    let ready = tokio::time::timeout(Duration::from_secs(20), async {
        let mut ready = Vec::new();
        while let Some(result) = subscriptions.join_next().await {
            ready.push(result.expect("collect remote streams"));
        }
        ready
    })
    .await
    .expect("132 streams ready");
    let mut consumers = tokio::task::JoinSet::new();
    let mut retained_events = Vec::new();
    let mut all_streams = Vec::new();
    for (receiver, streams, events) in ready {
        retained_events.push(events);
        for (speaker, stream) in streams {
            all_streams.push(("PLAYOUT_CADENCE", receiver, speaker, stream));
        }
    }
    for (index, track) in local_tracks.into_iter().enumerate() {
        all_streams.push((
            "SOURCE_CADENCE",
            index,
            format!("cadence-{index}:1"),
            NativeAudioStream::new(track, 48000, 1),
        ));
    }
    let start = tokio::time::Instant::now();
    let observation_ms = windows * 15000;
    let deadline = start + Duration::from_secs((windows * 15) as u64);
    let unix_ms = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("wall clock after epoch")
        .as_millis();
    println!("CADENCE_STARTED mode={mode} unix_ms={unix_ms} observation_ms={observation_ms}");
    for (kind, receiver, speaker, mut stream) in all_streams {
        consumers.spawn(async move {
            let initial = stream.stats();
            let measured_from = tokio::time::Instant::now();
            let mut frames = 0;
            let mut samples = 0;
            let mut nonzero = 0;
            let mut active = 0;
            let mut crossings = 0;
            let mut active_samples = 0;
            let mut previous_sample = 0;
            let mut callback_windows = vec![0usize; windows];
            let mut active_windows = vec![0usize; windows];
            let mut native_windows = vec![0u64; windows];
            let mut dropped_windows = vec![0u64; windows];
            let mut previous_stats = initial;
            let mut current_window = 0;
            let mut peak_abs = 0;
            let mut max_gap_us = 0;
            let mut previous = start;
            while let Ok(frame) = tokio::time::timeout_at(deadline, stream.next()).await {
                let frame = frame.expect("observed stream remains open");
                let now = tokio::time::Instant::now();
                if now >= deadline {
                    break;
                }
                let window = now.duration_since(start).as_secs() as usize / 15;
                if window != current_window {
                    let stats = stream.stats();
                    native_windows[current_window] =
                        stats.received_frames - previous_stats.received_frames;
                    dropped_windows[current_window] =
                        stats.dropped_frames - previous_stats.dropped_frames;
                    previous_stats = stats;
                    current_window = window;
                }
                callback_windows[window] += 1;
                max_gap_us = max_gap_us.max(now.duration_since(previous).as_micros());
                previous = now;
                frames += 1;
                samples += frame.samples_per_channel as usize;
                let peak = frame
                    .data
                    .iter()
                    .map(|sample| sample.unsigned_abs())
                    .max()
                    .unwrap_or(0);
                peak_abs = peak_abs.max(peak);
                nonzero += usize::from(peak != 0);
                if peak > 100 {
                    active += 1;
                    active_windows[window] += 1;
                    active_samples += frame.data.len();
                    for &sample in frame.data.iter() {
                        crossings += usize::from(previous_sample <= 0 && sample > 0);
                        previous_sample = sample;
                    }
                }
            }
            stream.close();
            let final_stats = stream.stats();
            let native_frames = final_stats.received_frames - initial.received_frames;
            let native_samples = final_stats.received_samples - initial.received_samples;
            let dropped = final_stats.dropped_frames - initial.dropped_frames;
            let measured_ms = measured_from.elapsed().as_millis();
            native_windows[current_window] =
                final_stats.received_frames - previous_stats.received_frames;
            dropped_windows[current_window] =
                final_stats.dropped_frames - previous_stats.dropped_frames;
            (
                kind,
                receiver,
                speaker,
                frames,
                samples,
                nonzero,
                peak_abs,
                max_gap_us,
                (initial, measured_ms, native_frames, native_samples, dropped),
                (active, crossings, active_samples),
                (
                    callback_windows,
                    active_windows,
                    native_windows,
                    dropped_windows,
                ),
            )
        });
    }
    let mut observations = Vec::new();
    while let Some(result) = consumers.join_next().await {
        observations.push(result.expect("consume remote PCM"));
    }
    producers.abort_all();
    while producers.join_next().await.is_some() {}
    for room in rooms {
        room.close().await.expect("close diagnostic room");
    }
    drop(retained_events);
    drop(audio);
    assert_eq!(observations.len(), 144);
    for (
        kind,
        receiver,
        speaker,
        frames,
        samples,
        nonzero,
        peak_abs,
        max_gap_us,
        queue,
        pcm,
        intervals,
    ) in observations
    {
        let (initial, measured_ms, native_frames, native_samples, dropped) = queue;
        let (active, crossings, active_samples) = pcm;
        let (callback_windows, active_windows, native_windows, dropped_windows) = intervals;
        println!("CADENCE_WINDOWS mode={mode} receiver={receiver} speaker={speaker} callbacks={callback_windows:?} active={active_windows:?} native={native_windows:?} dropped={dropped_windows:?}");
        let frequency = if active_samples == 0 {
            0.0
        } else {
            crossings as f64 * 48000.0 / active_samples as f64
        };
        println!("PCM_SIGNAL mode={mode} signal={signal} receiver={receiver} speaker={speaker} active_frames={active} frequency_hz={frequency:.3}");
        println!("AUDIO_QUEUE mode={mode} receiver={receiver} speaker={speaker} measured_ms={measured_ms} native_frames={native_frames} native_samples={native_samples} dropped={dropped} consumed={frames} initial_native_frames={} initial_dropped={}", initial.received_frames, initial.dropped_frames);
        println!("{kind} mode={mode} receiver={receiver} speaker={speaker} observation_ms={observation_ms} frames={frames} samples={samples} nonzero={nonzero} peak_abs={peak_abs} max_gap_us={max_gap_us}");
        assert!(frames > 0, "diagnostic must observe actual PCM");
    }
}
