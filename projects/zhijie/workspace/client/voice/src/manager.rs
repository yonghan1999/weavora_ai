use std::sync::{Arc, Mutex, Weak};

use livekit::prelude::*;
use livekit::DisconnectReason;
use tokio::sync::mpsc;

use crate::livekit_adapter::LiveKitVoiceSession;
use crate::model::{AudioDeviceView, VoiceConnectionState, VoiceSnapshot};

#[derive(Default)]
struct VoiceState {
    snapshot: VoiceSnapshot,
    session: Option<LiveKitVoiceSession>,
    generation: u64,
    input_revision: u64,
    output_revision: u64,
}

#[derive(Default)]
pub struct VoiceManager {
    state: Arc<Mutex<VoiceState>>,
    connect_lock: Arc<tokio::sync::Mutex<()>>,
    output_test_lock: tokio::sync::Mutex<()>,
}

struct LocalRecording(PlatformAudio);

impl Drop for LocalRecording {
    fn drop(&mut self) {
        let _ = self.0.stop_recording();
    }
}

impl VoiceManager {
    pub async fn test_output(&self) -> Result<(), String> {
        let _test_guard = self
            .output_test_lock
            .try_lock()
            .map_err(|_| "AUDIO_TEST_BUSY")?;
        let (generation, revision, device_id, gain) = {
            let state = self.state.lock().expect("voice state lock");
            let id = state
                .snapshot
                .output_device
                .clone()
                .ok_or("AUDIO_OUTPUT_SELECTION_REQUIRED")?;
            (
                state.generation,
                state.output_revision,
                id,
                f64::from(state.snapshot.master_volume) * 0.05,
            )
        };
        if !self
            .audio_devices()?
            .outputs
            .iter()
            .any(|d| d.id == device_id)
        {
            return Err("AUDIO_OUTPUT_DEVICE_REMOVED".into());
        }
        let test = livekit::webrtc::native::output_test::OutputTest::start(&device_id, gain)
            .ok_or("AUDIO_OUTPUT_TEST_FAILED")?;
        let deadline = tokio::time::Instant::now() + std::time::Duration::from_secs(2);
        while tokio::time::Instant::now() < deadline {
            tokio::time::sleep(std::time::Duration::from_millis(20)).await;
            let state = self.state.lock().expect("voice state lock");
            if state.generation != generation || state.output_revision != revision {
                return Err("AUDIO_TEST_SUPERSEDED".into());
            }
        }
        let state = self.state.lock().expect("voice state lock");
        if state.generation != generation || state.output_revision != revision {
            return Err("AUDIO_TEST_SUPERSEDED".into());
        }
        if let Some(failure) = device_failure(
            &state.snapshot.output_device,
            &self.audio_devices()?.outputs,
            None,
            "AUDIO_OUTPUT_DEVICE_REMOVED",
        ) {
            return Err(failure);
        }
        if test.samples() < 32000 {
            return Err("AUDIO_OUTPUT_TEST_FAILED".into());
        }
        Ok(())
    }

    pub async fn test_microphone(&self) -> Result<crate::MicrophoneTestResult, String> {
        // 原生采集与通话共用平台设备；不能在试音清理时停掉新房间的采集。
        let _guard = self
            .connect_lock
            .try_lock()
            .map_err(|_| "AUDIO_TEST_BUSY")?;
        let (generation, revision, selected, session_track) = {
            let state = self.state.lock().expect("voice state lock");
            (
                state.generation,
                state.input_revision,
                state.snapshot.input_device.clone(),
                state
                    .session
                    .as_ref()
                    .map(LiveKitVoiceSession::input_test_track),
            )
        };
        let mut recording = None;
        let track = match session_track {
            Some(track) => track,
            None => {
                let id = selected
                    .as_deref()
                    .ok_or("AUDIO_INPUT_SELECTION_REQUIRED")?;
                crate::livekit_adapter::check_microphone_access()?;
                let audio = PlatformAudio::new().map_err(|_| "AUDIO_DEVICE_UNAVAILABLE")?;
                crate::livekit_adapter::set_local_input(&audio, id)?;
                let track = LocalAudioTrack::create_audio_track(
                    "local-microphone-test",
                    audio.rtc_source(),
                );
                audio
                    .start_recording()
                    .map_err(|_| "AUDIO_CAPTURE_FAILED")?;
                recording = Some(LocalRecording(audio));
                track
            }
        };
        let current = || {
            let state = self.state.lock().expect("voice state lock");
            state.generation == generation && state.input_revision == revision
        };
        let measure = crate::microphone_test::measure(&track, std::time::Duration::from_secs(3));
        tokio::pin!(measure);
        let result = loop {
            tokio::select! {
                result = &mut measure => break result,
                _ = tokio::time::sleep(std::time::Duration::from_millis(40)) => {
                    if !current() { return Err("AUDIO_TEST_SUPERSEDED".into()); }
                }
            }
        };
        drop(recording);
        if !current() {
            return Err("AUDIO_TEST_SUPERSEDED".into());
        }
        if let Some(failure) = device_failure(
            &selected,
            &self.audio_devices()?.inputs,
            None,
            "AUDIO_INPUT_DEVICE_REMOVED",
        ) {
            return Err(failure);
        }
        result
    }

    pub fn snapshot(&self) -> VoiceSnapshot {
        let state = self.state.lock().expect("voice state lock");
        let mut snapshot = state.snapshot.clone();
        if snapshot.state == VoiceConnectionState::Connected {
            if let Some(session) = &state.session {
                snapshot.members = session.members();
                snapshot.input_failure_code = device_failure(
                    &snapshot.input_device,
                    &session.input_devices(),
                    snapshot.input_failure_code,
                    "AUDIO_INPUT_DEVICE_REMOVED",
                );
                snapshot.output_failure_code = device_failure(
                    &snapshot.output_device,
                    &session.output_devices(),
                    snapshot.output_failure_code,
                    "AUDIO_OUTPUT_DEVICE_REMOVED",
                );
            }
        }
        snapshot
    }

    pub async fn connect(&self, url: &str, token: &str) -> Result<VoiceSnapshot, String> {
        let generation = self.begin_connection();
        self.connect_reserved(generation, url, token).await
    }

    pub fn begin_connection(&self) -> u64 {
        let mut state = self.state.lock().expect("voice state lock");
        if state.session.is_none() {
            state.generation += 1;
            set_state(&mut state, VoiceConnectionState::Connecting, None);
        }
        state.generation
    }

    pub fn fail_connection(&self, generation: u64, code: String) -> String {
        let mut state = self.state.lock().expect("voice state lock");
        if state.generation != generation {
            return "VOICE_CONNECTION_SUPERSEDED".into();
        }
        if state.session.is_none() {
            set_state(&mut state, VoiceConnectionState::Failed, Some(code.clone()));
        }
        code
    }

    pub async fn connect_reserved(
        &self,
        generation: u64,
        url: &str,
        token: &str,
    ) -> Result<VoiceSnapshot, String> {
        let _connect_guard = self.connect_lock.lock().await;
        let settings = {
            let mut state = self.state.lock().expect("voice state lock");
            if state.generation != generation {
                return Err("VOICE_CONNECTION_SUPERSEDED".into());
            }
            if state.session.is_some() {
                return Ok(state.snapshot.clone());
            }
            state.snapshot.input_failure_code = None;
            state.snapshot.output_failure_code = None;
            state.snapshot.clone()
        };
        match LiveKitVoiceSession::connect(url, token, &settings).await {
            Ok(mut session) => {
                let events = session.take_events();
                {
                    let mut state = self.state.lock().expect("voice state lock");
                    if state.generation == generation {
                        session.set_muted(state.snapshot.muted);
                        session.set_receive_enabled(state.snapshot.receive_enabled);
                        session.apply_volumes(&state.snapshot);
                        state.session = Some(session);
                        set_state(&mut state, VoiceConnectionState::Connected, None);
                        tokio::spawn(consume_events(
                            Arc::downgrade(&self.state),
                            self.connect_lock.clone(),
                            generation,
                            events,
                        ));
                        return Ok(state.snapshot.clone());
                    }
                }
                // 退出、撤销或新连接已推进代次，迟到会话只能关闭。
                let _ = session.close().await;
                Err("VOICE_CONNECTION_SUPERSEDED".to_string())
            }
            Err(code) => Err(self.fail_connection(generation, code)),
        }
    }

    pub async fn retry(&self, url: &str, token: &str) -> Result<VoiceSnapshot, String> {
        self.disconnect().await;
        self.connect(url, token).await
    }

    pub fn set_muted(&self, muted: bool) -> VoiceSnapshot {
        let mut state = self.state.lock().expect("voice state lock");
        if let Some(session) = &state.session {
            session.set_muted(muted);
        }
        state.snapshot.muted = muted;
        state.snapshot.clone()
    }

    pub fn set_receive_enabled(&self, enabled: bool) -> VoiceSnapshot {
        let mut state = self.state.lock().expect("voice state lock");
        if let Some(session) = &state.session {
            session.set_receive_enabled(enabled);
        }
        state.snapshot.receive_enabled = enabled;
        state.snapshot.clone()
    }

    pub fn set_master_volume(&self, volume: f32) -> Result<VoiceSnapshot, String> {
        validate_volume(volume)?;
        let mut state = self.state.lock().expect("voice state lock");
        state.snapshot.master_volume = volume;
        if let Some(session) = &state.session {
            session.apply_volumes(&state.snapshot);
        }
        Ok(state.snapshot.clone())
    }

    pub fn set_member_volume(&self, member_id: &str, volume: f32) -> Result<VoiceSnapshot, String> {
        validate_volume(volume)?;
        if member_id.is_empty() {
            return Err("MEMBER_REQUIRED".to_string());
        }
        let mut state = self.state.lock().expect("voice state lock");
        state
            .snapshot
            .member_volumes
            .insert(member_id.to_string(), volume);
        if let Some(session) = &state.session {
            session.apply_volumes(&state.snapshot);
        }
        Ok(state.snapshot.clone())
    }

    pub fn select_input(&self, id: &str) -> Result<VoiceSnapshot, String> {
        let mut state = self.state.lock().expect("voice state lock");
        state.input_revision += 1;
        let result = if let Some(session) = &state.session {
            session.select_input(id)
        } else if state.snapshot.state == VoiceConnectionState::Connecting {
            Err("AUDIO_CONNECTION_BUSY".into())
        } else if self.audio_devices()?.inputs.iter().any(|d| d.id == id) {
            Ok(())
        } else {
            Err("AUDIO_INPUT_DEVICE_REMOVED".into())
        };
        state.snapshot.input_failure_code = result.as_ref().err().cloned();
        result?;
        state.snapshot.input_device = Some(id.to_string());
        Ok(state.snapshot.clone())
    }

    pub fn select_output(&self, id: &str) -> Result<VoiceSnapshot, String> {
        let mut state = self.state.lock().expect("voice state lock");
        state.output_revision += 1;
        let result = if let Some(session) = &state.session {
            session.select_output(id)
        } else if state.snapshot.state == VoiceConnectionState::Connecting {
            Err("AUDIO_CONNECTION_BUSY".into())
        } else if self.audio_devices()?.outputs.iter().any(|d| d.id == id) {
            Ok(())
        } else {
            Err("AUDIO_OUTPUT_DEVICE_REMOVED".into())
        };
        state.snapshot.output_failure_code = result.as_ref().err().cloned();
        result?;
        state.snapshot.output_device = Some(id.to_string());
        Ok(state.snapshot.clone())
    }

    /// 仅枚举本机端点，不创建房间、音轨或启动录音。
    pub fn audio_devices(&self) -> Result<crate::model::AudioDevices, String> {
        let audio = PlatformAudio::new().map_err(|_| "AUDIO_DEVICE_UNAVAILABLE".to_string())?;
        Ok(crate::model::AudioDevices {
            inputs: crate::livekit_adapter::input_devices(&audio),
            outputs: crate::livekit_adapter::output_devices(&audio),
        })
    }

    pub async fn disconnect(&self) -> VoiceSnapshot {
        self.close_session(VoiceConnectionState::Disconnected, None)
            .await
    }

    pub async fn revoke(&self) -> VoiceSnapshot {
        self.close_session(
            VoiceConnectionState::Revoked,
            Some("CURRENT_GRANT_REQUIRED".to_string()),
        )
        .await
    }

    async fn close_session(
        &self,
        status: VoiceConnectionState,
        failure: Option<String>,
    ) -> VoiceSnapshot {
        let (session, snapshot) = {
            let mut state = self.state.lock().expect("voice state lock");
            state.generation += 1;
            set_state(&mut state, status, failure);
            (state.session.take(), state.snapshot.clone())
        };
        let _connect_guard = self.connect_lock.lock().await;
        if let Some(session) = session {
            let _ = session.close().await;
        }
        snapshot
    }

    pub fn fail(&self, code: String) -> VoiceSnapshot {
        let mut state = self.state.lock().expect("voice state lock");
        // 授权请求失败不应把仍工作的会话标成失败。
        if state.session.is_none() {
            set_state(&mut state, VoiceConnectionState::Failed, Some(code));
        }
        state.snapshot.clone()
    }
}

async fn consume_events(
    state: Weak<Mutex<VoiceState>>,
    connect_lock: Arc<tokio::sync::Mutex<()>>,
    generation: u64,
    mut events: mpsc::UnboundedReceiver<RoomEvent>,
) {
    while let Some(event) = events.recv().await {
        let _connect_guard = connect_lock.lock().await;
        let Some(state) = state.upgrade() else {
            return;
        };
        let (session, terminal) = {
            let mut state = state.lock().expect("voice state lock");
            if state.generation != generation {
                return;
            }
            let terminal = apply_event(&mut state, event);
            (if terminal { state.session.take() } else { None }, terminal)
        };
        if let Some(session) = session {
            let _ = session.close().await;
        }
        if terminal {
            return;
        }
    }
}

fn apply_event(state: &mut VoiceState, event: RoomEvent) -> bool {
    match event {
        RoomEvent::TrackSubscribed {
            publication,
            track,
            participant,
        } => {
            if publication.kind() == TrackKind::Audio {
                publication.set_enabled(state.snapshot.receive_enabled);
            }
            if let RemoteTrack::Audio(track) = track {
                crate::livekit_adapter::apply_track_volume(
                    &track,
                    participant.identity().as_str(),
                    &state.snapshot,
                );
            }
        }
        RoomEvent::Reconnecting => set_state(state, VoiceConnectionState::Retrying, None),
        RoomEvent::Reconnected => set_state(state, VoiceConnectionState::Connected, None),
        RoomEvent::Disconnected { reason } => {
            let revoked = matches!(
                reason,
                DisconnectReason::ParticipantRemoved | DisconnectReason::RoomDeleted
            );
            set_state(
                state,
                if revoked {
                    VoiceConnectionState::Revoked
                } else {
                    VoiceConnectionState::Failed
                },
                Some(
                    if revoked {
                        "CURRENT_GRANT_REQUIRED"
                    } else {
                        "VOICE_CONNECTION_LOST"
                    }
                    .to_string(),
                ),
            );
            state.generation += 1;
            return true;
        }
        _ => {}
    }
    false
}

fn set_state(state: &mut VoiceState, status: VoiceConnectionState, failure: Option<String>) {
    state.snapshot.state = status;
    state.snapshot.failure_code = failure;
}

fn validate_volume(volume: f32) -> Result<(), String> {
    if !volume.is_finite() || !(0.0..=2.0).contains(&volume) {
        return Err("AUDIO_VOLUME_INVALID".to_string());
    }
    Ok(())
}

fn device_failure(
    selected: &Option<String>,
    devices: &[AudioDeviceView],
    failure: Option<String>,
    removed: &str,
) -> Option<String> {
    if devices.is_empty()
        || selected
            .as_ref()
            .is_some_and(|id| !devices.iter().any(|device| &device.id == id))
    {
        Some(removed.into())
    } else {
        failure
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn device_health_follows_identity_across_reorder_removal_and_return() {
        let selected = Some("usb-mic".to_string());
        let devices = vec![
            AudioDeviceView {
                id: "other".into(),
                name: "Other".into(),
                index: 0,
            },
            AudioDeviceView {
                id: "usb-mic".into(),
                name: "USB".into(),
                index: 7,
            },
        ];
        assert_eq!(device_failure(&selected, &devices, None, "REMOVED"), None);
        assert_eq!(
            device_failure(&selected, &devices[..1], None, "REMOVED"),
            Some("REMOVED".into())
        );
        assert_eq!(device_failure(&selected, &devices, None, "REMOVED"), None);
        assert_eq!(
            device_failure(&None, &[], None, "REMOVED"),
            Some("REMOVED".into())
        );
        // 设备重新出现不能抹去真实切换错误，必须重新选择成功后清除。
        assert_eq!(
            device_failure(&selected, &devices, Some("SWITCH_FAILED".into()), "REMOVED"),
            Some("SWITCH_FAILED".into())
        );
    }

    #[test]
    fn media_events_report_recovery_and_invalidate_removed_generation() {
        let mut state = VoiceState::default();
        assert!(!apply_event(&mut state, RoomEvent::Reconnecting));
        assert_eq!(state.snapshot.state, VoiceConnectionState::Retrying);
        assert!(!apply_event(&mut state, RoomEvent::Reconnected));
        assert_eq!(state.snapshot.state, VoiceConnectionState::Connected);
        assert!(apply_event(
            &mut state,
            RoomEvent::Disconnected {
                reason: DisconnectReason::ParticipantRemoved
            }
        ));
        assert_eq!(state.snapshot.state, VoiceConnectionState::Revoked);
        assert_eq!(state.generation, 1);
    }

    #[tokio::test]
    async fn offline_tests_require_a_selected_endpoint() {
        let manager = VoiceManager::default();
        assert_eq!(
            manager.test_microphone().await.unwrap_err(),
            "AUDIO_INPUT_SELECTION_REQUIRED"
        );
        assert_eq!(
            manager.test_output().await.unwrap_err(),
            "AUDIO_OUTPUT_SELECTION_REQUIRED"
        );
    }

    #[test]
    fn connection_reservation_keeps_preferences_and_rejects_racing_edits() {
        let manager = VoiceManager::default();
        manager.state.lock().unwrap().snapshot.input_device = Some("mic".into());
        manager.begin_connection();
        assert_eq!(
            manager.select_input("other").unwrap_err(),
            "AUDIO_CONNECTION_BUSY"
        );
        assert_eq!(manager.snapshot().input_device.as_deref(), Some("mic"));
    }

    #[tokio::test]
    async fn revoke_invalidates_pending_connections_and_old_events() {
        let manager = VoiceManager::default();
        manager.revoke().await;
        let (sender, receiver) = mpsc::unbounded_channel();
        sender.send(RoomEvent::Reconnected).unwrap();
        drop(sender);
        consume_events(
            Arc::downgrade(&manager.state),
            manager.connect_lock.clone(),
            0,
            receiver,
        )
        .await;
        assert_eq!(manager.snapshot().state, VoiceConnectionState::Revoked);
    }

    #[tokio::test]
    async fn disconnect_cancels_connection_waiting_for_audio_resources() {
        let manager = Arc::new(VoiceManager::default());
        let guard = manager.connect_lock.lock().await;
        let pending_manager = manager.clone();
        let pending =
            tokio::spawn(async move { pending_manager.connect("invalid-url", "unused").await });
        while manager.snapshot().state != VoiceConnectionState::Connecting {
            tokio::task::yield_now().await;
        }
        let closing_manager = manager.clone();
        let closing = tokio::spawn(async move { closing_manager.disconnect().await });
        while manager.snapshot().state != VoiceConnectionState::Disconnected {
            tokio::task::yield_now().await;
        }
        drop(guard);
        closing.await.unwrap();
        assert_eq!(
            pending.await.unwrap().unwrap_err(),
            "VOICE_CONNECTION_SUPERSEDED"
        );
        assert_eq!(manager.snapshot().state, VoiceConnectionState::Disconnected);
    }
}
