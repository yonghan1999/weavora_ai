use livekit::prelude::*;
use livekit::webrtc::{
    audio_frame::AudioFrame,
    audio_source::{native::NativeAudioSource, AudioSourceOptions, RtcAudioSource},
    audio_stream::native::{NativeAudioStream, NativeAudioStreamOptions},
};
use tokio_stream::StreamExt;

#[tokio::test]
async fn native_stream_stats_count_callbacks_and_bounded_queue_drops() {
    assert_eq!(zhijie_voice::VOICE_ADAPTER_PROTOCOL_VERSION, 1);
    for iteration in 0..16 {
        let source = NativeAudioSource::new(AudioSourceOptions::default(), 48000, 1, 0);
        let track = LocalAudioTrack::create_audio_track(
            "queue-stats",
            RtcAudioSource::Native(source.clone()),
        );
        let mut stream = NativeAudioStream::with_options(
            track.rtc_track(),
            48000,
            1,
            NativeAudioStreamOptions {
                queue_size_frames: Some(2),
            },
        );
        assert_eq!(stream.stats().received_frames, 0);
        for marker in 1..=20 {
            let mut frame = AudioFrame::new(48000, 1, 480);
            frame.data.to_mut().fill(marker);
            source.capture_frame(&frame).await.expect("capture PCM");
        }
        let stats = stream.stats();
        assert_eq!(stats.received_frames, 20, "iteration {iteration}");
        assert_eq!(stats.received_samples, 9600);
        assert_eq!(stats.dropped_frames, 18);
        assert_eq!(stream.next().await.expect("penultimate frame").data[0], 19);
        assert_eq!(stream.next().await.expect("last frame").data[0], 20);
        assert_eq!(stream.stats(), stats);
        stream.close();
        assert!(stream.next().await.is_none());
        source
            .capture_frame(&AudioFrame::new(48000, 1, 480))
            .await
            .expect("source remains usable after sink removal");
        assert_eq!(stream.stats(), stats);
    }
}

#[tokio::test]
async fn native_stream_stats_do_not_change_default_queue_capacity() {
    assert_eq!(zhijie_voice::VOICE_ADAPTER_PROTOCOL_VERSION, 1);
    let source = NativeAudioSource::new(AudioSourceOptions::default(), 48000, 1, 0);
    let track = LocalAudioTrack::create_audio_track(
        "default-queue",
        RtcAudioSource::Native(source.clone()),
    );
    let mut stream = NativeAudioStream::new(track.rtc_track(), 48000, 1);
    for marker in 1..=20 {
        let mut frame = AudioFrame::new(48000, 1, 480);
        frame.data.to_mut().fill(marker);
        source.capture_frame(&frame).await.expect("capture PCM");
    }
    assert_eq!(stream.stats().received_frames, 20);
    assert_eq!(stream.stats().dropped_frames, 10);
    for marker in 11..=20 {
        assert_eq!(stream.next().await.expect("retained frame").data[0], marker);
    }
    stream.close();
    assert!(stream.next().await.is_none());
}
