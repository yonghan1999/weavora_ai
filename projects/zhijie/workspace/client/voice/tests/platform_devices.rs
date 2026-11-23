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

#[test]
#[ignore = "requires host input and output devices"]
fn settings_can_enumerate_before_joining_a_room() {
    let manager = zhijie_voice::VoiceManager::default();
    let devices = manager.audio_devices().expect("enumerate without a room");
    assert!(devices.inputs.iter().any(|d| !d.name.is_empty()));
    assert!(devices.outputs.iter().any(|d| !d.name.is_empty()));
    manager
        .select_input(&devices.inputs[0].id)
        .expect("remember local input");
    manager
        .select_output(&devices.outputs[0].id)
        .expect("remember local output");
    assert_eq!(
        manager.select_input("missing").unwrap_err(),
        "AUDIO_INPUT_DEVICE_REMOVED"
    );
    assert_eq!(
        manager.snapshot().input_device.as_ref(),
        Some(&devices.inputs[0].id)
    );
    assert_eq!(
        manager.snapshot().state,
        zhijie_voice::VoiceConnectionState::Disconnected
    );
}
