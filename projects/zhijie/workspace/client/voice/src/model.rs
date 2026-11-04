use serde::{Deserialize, Serialize};
use std::collections::HashMap;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "UPPERCASE")]
pub enum VoiceConnectionState {
    Disconnected,
    Connecting,
    Connected,
    Retrying,
    Failed,
    Revoked,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct VoiceSnapshot {
    pub state: VoiceConnectionState,
    pub muted: bool,
    pub receive_enabled: bool,
    pub master_volume: f32,
    pub member_volumes: HashMap<String, f32>,
    pub input_device: Option<String>,
    pub output_device: Option<String>,
    pub processing: AudioProcessingSnapshot,
    pub failure_code: Option<String>,
}

impl Default for VoiceSnapshot {
    fn default() -> Self {
        Self {
            state: VoiceConnectionState::Disconnected,
            muted: false,
            receive_enabled: true,
            master_volume: 1.0,
            member_volumes: HashMap::new(),
            input_device: None,
            output_device: None,
            processing: AudioProcessingSnapshot::default(),
            failure_code: None,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AudioProcessingSnapshot {
    pub echo_cancellation: bool,
    pub noise_suppression: bool,
    pub auto_gain_control: bool,
}

impl Default for AudioProcessingSnapshot {
    fn default() -> Self {
        Self {
            echo_cancellation: true,
            noise_suppression: true,
            auto_gain_control: true,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AudioDeviceView {
    pub id: String,
    pub name: String,
    pub index: usize,
}
