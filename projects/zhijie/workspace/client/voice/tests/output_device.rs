use livekit::webrtc::{
    native::output_test::OutputTest,
    peer_connection_factory::{native::PeerConnectionFactoryExt, PeerConnectionFactory},
};

#[test]
fn invalid_output_gain_is_rejected_without_playback() {
    assert_eq!(zhijie_voice::VOICE_ADAPTER_PROTOCOL_VERSION, 1);
    for gain in [f64::NAN, f64::INFINITY, -0.01, 0.16] {
        assert!(OutputTest::start("missing-device", gain).is_none());
    }
}

#[tokio::test]
#[ignore = "requires host output service; zero gain produces silence"]
async fn host_output_callbacks_and_cleanup_with_zero_gain() {
    assert_eq!(zhijie_voice::VOICE_ADAPTER_PROTOCOL_VERSION, 1);
    let factory = PeerConnectionFactory::default();
    assert!(factory.acquire_platform_adm());
    let id = factory.playout_device_guid(0);
    factory.release_platform_adm();
    assert!(!id.is_empty(), "host output required");
    assert!(OutputTest::start("missing-device", 0.0).is_none());
    for _ in 0..3 {
        let output = OutputTest::start(&id, 0.0).expect("start local zero-gain output");
        tokio::time::sleep(std::time::Duration::from_secs(2)).await;
        println!("LOCAL_OUTPUT_SILENT samples={}", output.samples());
        assert!(output.samples() >= 32000, "real host callback consumes PCM");
        drop(output);
    }
}
