use livekit::webrtc::{
    audio_source::{native::NativeAudioSource, AudioSourceOptions},
    peer_connection_factory::{
        native::PeerConnectionFactoryExt, PeerConnectionFactory, RtcConfiguration,
    },
    RtcErrorType,
};

#[test]
fn removing_audio_after_close_returns_invalid_state() {
    assert_eq!(
        zhijie_voice::VoiceManager::default().snapshot().state,
        zhijie_voice::VoiceConnectionState::Disconnected
    );
    let factory = PeerConnectionFactory::default();
    let source = NativeAudioSource::new(AudioSourceOptions::default(), 48000, 1, 100);
    for index in 0..32 {
        let connection = factory
            .create_peer_connection(RtcConfiguration::default())
            .expect("create native connection");
        let track = factory.create_audio_track(&format!("cleanup-{index}"), source.clone());
        let sender = connection
            .add_track(track.into(), &["cleanup"])
            .expect("add native audio");
        if index % 2 == 0 {
            connection
                .remove_track(sender.clone())
                .expect("remove audio while open");
        }
        connection.close();
        let error = connection
            .remove_track(sender)
            .expect_err("closed connection must report an error");
        assert_eq!(error.error_type, RtcErrorType::InvalidState);
        assert!(!error.message.is_empty(), "preserve native error message");
    }
}
