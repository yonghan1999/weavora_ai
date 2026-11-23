use livekit::options::TrackPublishOptions;
use livekit::prelude::*;
use tokio::sync::mpsc;

use crate::model::VoiceSnapshot;
use crate::model::{AudioDeviceView, AudioProcessingSnapshot};

pub struct LiveKitVoiceSession {
    room: Room,
    audio: PlatformAudio,
    microphone: LocalAudioTrack,
    events: Option<mpsc::UnboundedReceiver<RoomEvent>>,
}

impl LiveKitVoiceSession {
    pub(crate) fn members(&self) -> Vec<crate::model::VoiceMemberSnapshot> {
        room_members(&self.room)
    }
    pub async fn connect(url: &str, token: &str, settings: &VoiceSnapshot) -> Result<Self, String> {
        let audio = PlatformAudio::new().map_err(|_| "AUDIO_DEVICE_UNAVAILABLE".to_string())?;
        check_microphone_access()?;
        if let Some(id) = &settings.input_device {
            set_local_input(&audio, id)?;
        }
        if let Some(id) = &settings.output_device {
            let device = audio
                .playout_devices()
                .find(|d| d.id.as_str() == id)
                .ok_or("AUDIO_OUTPUT_DEVICE_REMOVED")?;
            audio
                .set_playout_device(&device.id)
                .map_err(|_| "AUDIO_OUTPUT_SWITCH_FAILED")?;
        }
        configure_processing(&audio, settings.processing.clone())?;
        let (room, events) = Room::connect(url, token, RoomOptions::default())
            .await
            .map_err(|_| "VOICE_CONNECTION_FAILED".to_string())?;
        let microphone = LocalAudioTrack::create_audio_track("microphone", audio.rtc_source());
        if settings.muted {
            microphone.mute();
        }
        if room
            .local_participant()
            .publish_track(
                LocalTrack::Audio(microphone.clone()),
                TrackPublishOptions {
                    source: TrackSource::Microphone,
                    ..Default::default()
                },
            )
            .await
            .is_err()
        {
            let _ = room.close().await;
            return Err("VOICE_PUBLISH_FAILED".to_string());
        }
        if audio.start_recording().is_err() {
            let _ = room.close().await;
            return Err("AUDIO_CAPTURE_FAILED".to_string());
        }
        Ok(Self {
            room,
            audio,
            microphone,
            events: Some(events),
        })
    }

    pub fn set_muted(&self, muted: bool) {
        if muted {
            self.microphone.mute();
        } else {
            self.microphone.unmute();
        }
    }

    pub fn take_events(&mut self) -> mpsc::UnboundedReceiver<RoomEvent> {
        self.events.take().expect("voice events already consumed")
    }

    pub fn set_receive_enabled(&self, enabled: bool) {
        for participant in self.room.remote_participants().values() {
            for publication in participant.track_publications().values() {
                if publication.kind() == TrackKind::Audio {
                    publication.set_enabled(enabled);
                }
            }
        }
    }

    pub fn apply_volumes(&self, settings: &VoiceSnapshot) {
        for participant in self.room.remote_participants().values() {
            for publication in participant.track_publications().values() {
                if let Some(RemoteTrack::Audio(track)) = publication.track() {
                    apply_track_volume(&track, participant.identity().as_str(), settings);
                }
            }
        }
    }

    pub fn input_devices(&self) -> Vec<AudioDeviceView> {
        input_devices(&self.audio)
    }

    pub fn output_devices(&self) -> Vec<AudioDeviceView> {
        output_devices(&self.audio)
    }

    pub fn select_input(&self, id: &str) -> Result<(), String> {
        let device = self
            .audio
            .recording_devices()
            .find(|device| device.id.as_str() == id)
            .ok_or("AUDIO_INPUT_DEVICE_REMOVED")?;
        self.audio
            .switch_recording_device(&device.id)
            .map_err(|_| "AUDIO_INPUT_SWITCH_FAILED".to_string())
    }

    pub fn select_output(&self, id: &str) -> Result<(), String> {
        let device = self
            .audio
            .playout_devices()
            .find(|device| device.id.as_str() == id)
            .ok_or("AUDIO_OUTPUT_DEVICE_REMOVED")?;
        self.audio
            .switch_playout_device(&device.id)
            .map_err(|_| "AUDIO_OUTPUT_SWITCH_FAILED".to_string())
    }

    pub(crate) fn input_test_track(&self) -> LocalAudioTrack {
        // 独立本地观测轨道不发布，不改变通话轨道静音或设备状态。
        LocalAudioTrack::create_audio_track("local-microphone-test", self.audio.rtc_source())
    }

    pub async fn close(self) -> Result<(), String> {
        let capture_result = self.audio.stop_recording();
        let room_result = self
            .room
            .close()
            .await
            .map_err(|_| "VOICE_CLEANUP_FAILED".to_string());
        room_result?;
        capture_result.map_err(|_| "AUDIO_CLEANUP_FAILED".to_string())
    }
}

pub(crate) fn check_microphone_access() -> Result<(), String> {
    #[cfg(windows)]
    {
        extern "C" {
            fn zhijie_microphone_denied() -> i32;
        }
        // 查询系统已知的拒绝状态；查询本身不申请录音或改变隐私设置。
        if unsafe { zhijie_microphone_denied() } != 0 {
            return Err("AUDIO_PERMISSION_DENIED".into());
        }
    }
    Ok(())
}

pub(crate) fn set_local_input(audio: &PlatformAudio, id: &str) -> Result<(), String> {
    let device = audio
        .recording_devices()
        .find(|d| d.id.as_str() == id)
        .ok_or("AUDIO_INPUT_DEVICE_REMOVED")?;
    audio
        .set_recording_device(&device.id)
        .map_err(|_| "AUDIO_INPUT_SWITCH_FAILED".into())
}

pub(crate) fn input_devices(audio: &PlatformAudio) -> Vec<AudioDeviceView> {
    audio
        .recording_devices()
        .map(|device| AudioDeviceView {
            id: device.id.as_str().to_string(),
            name: device.name,
            index: device.index,
        })
        .collect()
}

pub(crate) fn output_devices(audio: &PlatformAudio) -> Vec<AudioDeviceView> {
    audio
        .playout_devices()
        .map(|device| AudioDeviceView {
            id: device.id.as_str().to_string(),
            name: device.name,
            index: device.index,
        })
        .collect()
}

/// 从当前 SDK 房间读取媒体状态；身份仍须由上层按活动成员代次匹配。
pub fn room_members(room: &Room) -> Vec<crate::model::VoiceMemberSnapshot> {
    let mut members = Vec::new();
    for participant in room.remote_participants().values() {
        let identity = participant.identity();
        let Some((user_id, generation)) = identity.as_str().rsplit_once(':') else {
            continue;
        };
        let Ok(member_generation) = generation.parse::<u64>() else {
            continue;
        };
        let publications: Vec<_> = participant
            .track_publications()
            .into_values()
            .filter(|publication| publication.kind() == TrackKind::Audio)
            .collect();
        if publications.is_empty() {
            continue;
        }
        let muted = publications
            .iter()
            .all(|publication| publication.is_muted());
        members.push(crate::model::VoiceMemberSnapshot {
            user_id: user_id.into(),
            member_generation,
            speaking: participant.is_speaking() && !muted,
            muted,
            subscribed: publications
                .iter()
                .any(|publication| publication.is_subscribed()),
        });
    }
    let participant = room.local_participant();
    let identity = participant.identity();
    if let Some((user_id, generation)) = identity.as_str().rsplit_once(':') {
        if let Ok(member_generation) = generation.parse::<u64>() {
            let publications: Vec<_> = participant
                .track_publications()
                .into_values()
                .filter(|publication| publication.kind() == TrackKind::Audio)
                .collect();
            if !publications.is_empty() {
                let muted = publications
                    .iter()
                    .all(|publication| publication.is_muted());
                members.push(crate::model::VoiceMemberSnapshot {
                    user_id: user_id.into(),
                    member_generation,
                    speaking: participant.is_speaking() && !muted,
                    muted,
                    subscribed: true,
                });
            }
        }
    }
    members.sort_by(|a, b| a.user_id.cmp(&b.user_id));
    members
}

pub(crate) fn apply_track_volume(
    track: &RemoteAudioTrack,
    identity: &str,
    settings: &VoiceSnapshot,
) {
    track
        .rtc_track()
        .set_volume(playout_volume(identity, settings));
}

fn playout_volume(identity: &str, settings: &VoiceSnapshot) -> f64 {
    let member = identity.rsplit_once(':').map_or(identity, |(user, _)| user);
    let member_volume = settings.member_volumes.get(member).copied().unwrap_or(1.0);
    f64::from(settings.master_volume * member_volume)
}

#[cfg(test)]
mod volume_tests {
    use super::*;

    #[test]
    fn member_gain_follows_user_across_media_generations_and_combines_with_master() {
        let mut settings = VoiceSnapshot::default();
        settings.master_volume = 0.5;
        settings.member_volumes.insert("user".into(), 0.25);
        assert_eq!(playout_volume("user:1", &settings), 0.125);
        assert_eq!(playout_volume("user:42", &settings), 0.125);
        assert_eq!(playout_volume("other:1", &settings), 0.5);
        settings.master_volume = 0.0;
        assert_eq!(playout_volume("user:42", &settings), 0.0);
    }
}

fn configure_processing(
    audio: &PlatformAudio,
    processing: AudioProcessingSnapshot,
) -> Result<(), String> {
    audio
        .set_echo_cancellation(processing.echo_cancellation, true)
        .map_err(|_| "AEC_CONFIGURATION_FAILED".to_string())?;
    audio
        .set_noise_suppression(processing.noise_suppression, true)
        .map_err(|_| "NOISE_SUPPRESSION_CONFIGURATION_FAILED".to_string())?;
    audio
        .set_auto_gain_control(processing.auto_gain_control, true)
        .map_err(|_| "AGC_CONFIGURATION_FAILED".to_string())?;
    Ok(())
}
