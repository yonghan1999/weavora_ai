pub mod livekit_adapter;
pub mod manager;
pub mod model;

pub use livekit_adapter::LiveKitVoiceSession;
pub use manager::VoiceManager;
pub use model::{AudioDeviceView, AudioProcessingSnapshot, VoiceConnectionState, VoiceSnapshot};

pub const VOICE_ADAPTER_PROTOCOL_VERSION: u16 = 1;

#[cfg(test)]
mod tests {
    use crate::{VoiceConnectionState, VoiceManager};

    #[test]
    fn voice_settings_are_local_and_validated() {
        let manager = VoiceManager::default();
        manager.set_muted(true);
        manager.set_receive_enabled(false);
        manager.set_master_volume(0.8).unwrap();
        manager.set_member_volume("member-a", 0.5).unwrap();
        let snapshot = manager.snapshot();
        assert!(snapshot.muted);
        assert!(!snapshot.receive_enabled);
        assert_eq!(snapshot.member_volumes.get("member-a"), Some(&0.5));
        assert!(manager.set_master_volume(f32::NAN).is_err());
        assert!(manager.set_member_volume("", 1.0).is_err());
    }

    #[tokio::test]
    async fn disconnect_and_revoke_do_not_require_a_connected_session() {
        let manager = VoiceManager::default();
        assert_eq!(
            manager.disconnect().await.state,
            VoiceConnectionState::Disconnected
        );
        assert_eq!(manager.revoke().await.state, VoiceConnectionState::Revoked);
    }
}
