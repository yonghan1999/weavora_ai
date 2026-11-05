use livekit::options::TrackPublishOptions;
use livekit::prelude::*;
use tokio::sync::mpsc;

use crate::model::{AudioDeviceView, AudioProcessingSnapshot};

pub struct LiveKitVoiceSession {
    room: Room,
    audio: PlatformAudio,
    microphone: LocalAudioTrack,
    pub events: mpsc::UnboundedReceiver<RoomEvent>,
}

impl LiveKitVoiceSession {
    pub async fn connect(
        url: &str,
        token: &str,
        processing: AudioProcessingSnapshot,
    ) -> Result<Self, String> {
        let audio = PlatformAudio::new().map_err(|_| "AUDIO_DEVICE_UNAVAILABLE".to_string())?;
        configure_processing(&audio, processing)?;
        let (room, events) = Room::connect(url, token, RoomOptions::default())
            .await
            .map_err(|_| "VOICE_CONNECTION_FAILED".to_string())?;
        let microphone = LocalAudioTrack::create_audio_track("microphone", audio.rtc_source());
        room.local_participant()
            .publish_track(
                LocalTrack::Audio(microphone.clone()),
                TrackPublishOptions::default(),
            )
            .await
            .map_err(|_| "VOICE_PUBLISH_FAILED".to_string())?;
        audio
            .start_recording()
            .map_err(|_| "AUDIO_CAPTURE_FAILED".to_string())?;
        Ok(Self {
            room,
            audio,
            microphone,
            events,
        })
    }

    pub fn set_muted(&self, muted: bool) {
        if muted {
            self.microphone.mute();
        } else {
            self.microphone.unmute();
        }
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

    pub fn input_devices(&self) -> Vec<AudioDeviceView> {
        self.audio
            .recording_devices()
            .map(|device| AudioDeviceView {
                id: device.id.as_str().to_string(),
                name: device.name,
                index: device.index,
            })
            .collect()
    }

    pub fn output_devices(&self) -> Vec<AudioDeviceView> {
        self.audio
            .playout_devices()
            .map(|device| AudioDeviceView {
                id: device.id.as_str().to_string(),
                name: device.name,
                index: device.index,
            })
            .collect()
    }

    pub fn select_input(&self, index: usize) -> Result<(), String> {
        self.audio
            .set_recording_device_by_index(index)
            .map_err(|_| "AUDIO_INPUT_SWITCH_FAILED".to_string())
    }

    pub fn select_output(&self, index: usize) -> Result<(), String> {
        self.audio
            .set_playout_device_by_index(index)
            .map_err(|_| "AUDIO_OUTPUT_SWITCH_FAILED".to_string())
    }

    pub fn test_input(&self) -> Result<(), String> {
        self.audio
            .stop_recording()
            .map_err(|_| "AUDIO_TEST_FAILED".to_string())?;
        self.audio
            .start_recording()
            .map_err(|_| "AUDIO_TEST_FAILED".to_string())
    }

    pub async fn close(self) -> Result<(), String> {
        self.audio
            .stop_recording()
            .map_err(|_| "AUDIO_CLEANUP_FAILED".to_string())?;
        self.room
            .close()
            .await
            .map_err(|_| "VOICE_CLEANUP_FAILED".to_string())
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
