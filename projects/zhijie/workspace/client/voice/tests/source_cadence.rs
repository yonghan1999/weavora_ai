use std::time::Duration;

use livekit::prelude::*;
use livekit::webrtc::{
    audio_frame::AudioFrame,
    audio_source::{native::NativeAudioSource, AudioSourceOptions, RtcAudioSource},
    audio_stream::native::NativeAudioStream,
};
use tokio_stream::StreamExt;

// 独立诊断本机源回调；不连接 SFU、真实麦克风或扬声器，不作为音质通过证据。
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
#[ignore = "explicit local twelve-source cadence diagnostic"]
async fn twelve_buffered_sources_report_local_callback_cadence() {
    assert_eq!(
        zhijie_voice::VoiceManager::default().snapshot().state,
        zhijie_voice::VoiceConnectionState::Disconnected
    );
    let mut tracks = Vec::new();
    let mut sources = Vec::new();
    let mut streams = Vec::new();
    for _ in 0..12 {
        let source = NativeAudioSource::new(AudioSourceOptions::default(), 48000, 1, 100);
        let track =
            LocalAudioTrack::create_audio_track("cadence", RtcAudioSource::Native(source.clone()));
        streams.push(NativeAudioStream::new(track.rtc_track(), 48000, 1));
        sources.push(source);
        tracks.push(track);
    }
    let mut producers = tokio::task::JoinSet::new();
    for source in sources {
        producers.spawn(async move {
            let mut frame = AudioFrame::new(48000, 1, 480);
            frame.data.to_mut().fill(2000);
            loop {
                source.capture_frame(&frame).await.expect("local capture");
            }
        });
    }
    let start = tokio::time::Instant::now();
    let deadline = start + Duration::from_secs(5);
    let mut receivers = tokio::task::JoinSet::new();
    for (index, mut stream) in streams.into_iter().enumerate() {
        receivers.spawn(async move {
            let mut frames = 0;
            let mut non_silent = 0;
            let mut samples = 0;
            let mut max_gap_us = 0;
            let mut previous = start;
            while let Ok(frame) = tokio::time::timeout_at(deadline, stream.next()).await {
                let frame = frame.expect("local source remains open");
                let now = tokio::time::Instant::now();
                max_gap_us = max_gap_us.max(now.duration_since(previous).as_micros());
                previous = now;
                frames += 1;
                samples += frame.data.len();
                non_silent += usize::from(frame.data.iter().any(|sample| sample.unsigned_abs() > 100));
            }
            println!("LOCAL_SOURCE_CADENCE member={index} observation_ms=5000 frames={frames} non_silent={non_silent} samples={samples} max_gap_us={max_gap_us}");
            // 无质量阈值：这里只证明采样确实执行，帧数留给诊断报告判断。
            assert!(frames > 0, "source callback must actually run");
        });
    }
    while let Some(result) = receivers.join_next().await {
        result.expect("source diagnostic receiver");
    }
    producers.abort_all();
    while producers.join_next().await.is_some() {}
    drop(tracks);
}
