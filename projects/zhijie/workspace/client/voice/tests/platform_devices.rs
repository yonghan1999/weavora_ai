use livekit::PlatformAudio;

// 显式使用宿主音频服务；不启动录音或播放，不把无设备视为通过。
#[test]
#[ignore = "requires host audio services and at least one input and output device"]
fn enumerate_host_devices_and_reject_missing_indices() {
    assert_eq!(zhijie_voice::VOICE_ADAPTER_PROTOCOL_VERSION, 1);
    for _ in 0..3 {
        let audio = PlatformAudio::new().expect("initialize host audio service");
        let inputs: Vec<_> = audio.recording_devices().collect();
        let outputs: Vec<_> = audio.playout_devices().collect();
        println!(
            "host audio: {} input(s), {} output(s)",
            inputs.len(),
            outputs.len()
        );
        assert!(!inputs.is_empty(), "host input device required");
        assert!(!outputs.is_empty(), "host output device required");
        audio
            .switch_recording_device(&inputs[0].id)
            .expect("select host input");
        audio
            .switch_playout_device(&outputs[0].id)
            .expect("select host output");
        assert!(audio.switch_recording_device_by_index(usize::MAX).is_err());
        assert!(audio.switch_playout_device_by_index(usize::MAX).is_err());
        assert!(audio
            .switch_recording_device(&livekit::RecordingDeviceId::from_unchecked_guid(
                "zhijie-missing-device"
            ))
            .is_err());
        assert!(audio
            .switch_playout_device(&livekit::PlayoutDeviceId::from_unchecked_guid(
                "zhijie-missing-device"
            ))
            .is_err());
    }
}
