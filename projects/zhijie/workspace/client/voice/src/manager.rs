use std::sync::{Arc, Mutex};
use tokio::sync::Mutex as AsyncMutex;

use crate::livekit_adapter::LiveKitVoiceSession;
use crate::model::{AudioDeviceView, VoiceConnectionState, VoiceSnapshot};

#[derive(Default)]
pub struct VoiceManager {
    snapshot: Arc<Mutex<VoiceSnapshot>>,
    session: Arc<AsyncMutex<Option<LiveKitVoiceSession>>>,
}

impl VoiceManager {
    pub fn snapshot(&self) -> VoiceSnapshot {
        self.snapshot.lock().expect("voice state lock").clone()
    }

    pub async fn connect(&self, url: &str, token: &str) -> Result<VoiceSnapshot, String> {
        if self.session.lock().await.is_some() {
            return Ok(self.snapshot());
        }
        self.update_state(VoiceConnectionState::Connecting, None);
        let settings = self.snapshot();
        match LiveKitVoiceSession::connect(url, token, settings.processing.clone()).await {
            Ok(session) => {
                session.set_muted(settings.muted);
                session.set_receive_enabled(settings.receive_enabled);
                *self.session.lock().await = Some(session);
                Ok(self.update_state(VoiceConnectionState::Connected, None))
            }
            Err(code) => {
                self.update_state(VoiceConnectionState::Failed, Some(code.clone()));
                Err(code)
            }
        }
    }

    pub async fn retry(&self, url: &str, token: &str) -> Result<VoiceSnapshot, String> {
        self.close_session().await;
        self.update_state(VoiceConnectionState::Retrying, None);
        self.connect(url, token).await
    }

    pub fn set_muted(&self, muted: bool) -> VoiceSnapshot {
        if let Ok(session) = self.session.try_lock() {
            if let Some(session) = session.as_ref() {
                session.set_muted(muted);
            }
        }
        let mut snapshot = self.snapshot.lock().expect("voice state lock");
        snapshot.muted = muted;
        snapshot.clone()
    }

    pub fn set_receive_enabled(&self, enabled: bool) -> VoiceSnapshot {
        if let Ok(session) = self.session.try_lock() {
            if let Some(session) = session.as_ref() {
                session.set_receive_enabled(enabled);
            }
        }
        let mut snapshot = self.snapshot.lock().expect("voice state lock");
        snapshot.receive_enabled = enabled;
        snapshot.clone()
    }

    pub fn set_master_volume(&self, volume: f32) -> Result<VoiceSnapshot, String> {
        validate_volume(volume)?;
        let mut snapshot = self.snapshot.lock().expect("voice state lock");
        snapshot.master_volume = volume;
        Ok(snapshot.clone())
    }

    pub fn set_member_volume(&self, member_id: &str, volume: f32) -> Result<VoiceSnapshot, String> {
        validate_volume(volume)?;
        if member_id.is_empty() {
            return Err("MEMBER_REQUIRED".to_string());
        }
        let mut snapshot = self.snapshot.lock().expect("voice state lock");
        snapshot
            .member_volumes
            .insert(member_id.to_string(), volume);
        Ok(snapshot.clone())
    }

    pub fn select_input(&self, index: usize) -> Result<VoiceSnapshot, String> {
        if let Ok(session) = self.session.try_lock() {
            if let Some(session) = session.as_ref() {
                session.select_input(index)?;
            }
        }
        let mut snapshot = self.snapshot.lock().expect("voice state lock");
        snapshot.input_device = Some(index.to_string());
        Ok(snapshot.clone())
    }

    pub fn select_output(&self, index: usize) -> Result<VoiceSnapshot, String> {
        if let Ok(session) = self.session.try_lock() {
            if let Some(session) = session.as_ref() {
                session.select_output(index)?;
            }
        }
        let mut snapshot = self.snapshot.lock().expect("voice state lock");
        snapshot.output_device = Some(index.to_string());
        Ok(snapshot.clone())
    }

    pub fn input_devices(&self) -> Vec<AudioDeviceView> {
        self.session
            .try_lock()
            .ok()
            .and_then(|session| session.as_ref().map(LiveKitVoiceSession::input_devices))
            .unwrap_or_default()
    }

    pub fn output_devices(&self) -> Vec<AudioDeviceView> {
        self.session
            .try_lock()
            .ok()
            .and_then(|session| session.as_ref().map(LiveKitVoiceSession::output_devices))
            .unwrap_or_default()
    }

    pub async fn disconnect(&self) -> VoiceSnapshot {
        self.close_session().await;
        self.update_state(VoiceConnectionState::Disconnected, None)
    }

    pub async fn revoke(&self) -> VoiceSnapshot {
        self.close_session().await;
        self.update_state(
            VoiceConnectionState::Revoked,
            Some("CURRENT_GRANT_REQUIRED".to_string()),
        )
    }

    async fn close_session(&self) {
        let session = self.session.lock().await.take();
        if let Some(session) = session {
            let _ = session.close().await;
        }
    }

    fn update_state(&self, state: VoiceConnectionState, failure: Option<String>) -> VoiceSnapshot {
        let mut snapshot = self.snapshot.lock().expect("voice state lock");
        snapshot.state = state;
        snapshot.failure_code = failure;
        snapshot.clone()
    }
}

fn validate_volume(volume: f32) -> Result<(), String> {
    if !volume.is_finite() || !(0.0..=2.0).contains(&volume) {
        return Err("AUDIO_VOLUME_INVALID".to_string());
    }
    Ok(())
}
