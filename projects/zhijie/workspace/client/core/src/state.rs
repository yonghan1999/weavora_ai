use std::sync::{Arc, Mutex};

use crate::model::{ActivityContext, ActivityResult, ChannelStatus, ClientSnapshot};

#[derive(Clone, Default)]
pub struct ClientCore {
    state: Arc<Mutex<ActivityState>>,
}

#[derive(Default)]
struct ActivityState {
    snapshot: ClientSnapshot,
    pending_request: Option<String>,
}

impl ClientCore {
    pub fn snapshot(&self) -> ClientSnapshot {
        self.state
            .lock()
            .expect("client state lock")
            .snapshot
            .clone()
    }

    pub fn session_changed(&self, user_id: Option<String>) {
        let mut state = self.state.lock().expect("client state lock");
        if state.snapshot.user_id != user_id || user_id.is_none() {
            state.snapshot.activity = None;
            state.pending_request = None;
        }
        let snapshot = &mut state.snapshot;
        snapshot.service = if user_id.is_some() {
            ChannelStatus::Connected
        } else {
            ChannelStatus::Disconnected
        };
        snapshot.user_id = user_id;
        snapshot.last_error_code = None;
    }

    pub fn set_service_available(&self, available: bool) {
        let mut state = self.state.lock().expect("client state lock");
        if state.snapshot.user_id.is_some() {
            state.snapshot.service = if available {
                ChannelStatus::Connected
            } else {
                ChannelStatus::Retrying
            };
        }
    }

    pub fn begin_activity(&self, request_id: String, activity_id: String, device_id: String) {
        let mut state = self.state.lock().expect("client state lock");
        state.pending_request = Some(request_id);
        state.snapshot.activity = Some(ActivityContext {
            activity_id,
            activity_generation: 0,
            member_generation: 0,
            device_id,
            lease_id: String::new(),
            virtual_ipv4: None,
            voice: ChannelStatus::Connecting,
            network: ChannelStatus::Connecting,
        });
    }

    pub fn apply_activity_result(&self, request_id: &str, result: ActivityResult) -> bool {
        let mut state = self.state.lock().expect("client state lock");
        if state.pending_request.as_deref() != Some(request_id) {
            return false;
        }
        let Some(current) = state.snapshot.activity.as_ref() else {
            return false;
        };
        if current.device_id != result.device_id
            || (current.activity_generation > 0 && current.activity_id != result.activity_id)
            || current.activity_generation > result.activity_generation
            || current.member_generation > result.member_generation
        {
            return false;
        }
        state.pending_request = None;
        state.snapshot.activity = Some(ActivityContext {
            activity_id: result.activity_id,
            activity_generation: result.activity_generation,
            member_generation: result.member_generation,
            device_id: result.device_id,
            lease_id: result.lease_id,
            virtual_ipv4: Some(result.virtual_ipv4),
            voice: result.voice_state,
            network: result.network_state,
        });
        state.snapshot.last_error_code = None;
        true
    }

    pub fn channel_failed(&self, activity_id: &str, channel: Channel, error_code: String) {
        let mut state = self.state.lock().expect("client state lock");
        let snapshot = &mut state.snapshot;
        let Some(activity) = snapshot
            .activity
            .as_mut()
            .filter(|item| item.activity_id == activity_id)
        else {
            return;
        };
        match channel {
            Channel::Voice => activity.voice = ChannelStatus::Failed,
            Channel::Network => activity.network = ChannelStatus::Failed,
        }
        snapshot.last_error_code = Some(error_code);
    }

    pub fn retry_channel(&self, activity_id: &str, channel: Channel) {
        let mut state = self.state.lock().expect("client state lock");
        let snapshot = &mut state.snapshot;
        let Some(activity) = snapshot
            .activity
            .as_mut()
            .filter(|item| item.activity_id == activity_id)
        else {
            return;
        };
        match channel {
            Channel::Voice => activity.voice = ChannelStatus::Retrying,
            Channel::Network => activity.network = ChannelStatus::Retrying,
        }
    }

    pub fn revoke(&self, activity_id: &str, member_generation: u64) {
        let mut state = self.state.lock().expect("client state lock");
        let snapshot = &mut state.snapshot;
        let Some(activity) = snapshot.activity.as_mut().filter(|item| {
            item.activity_id == activity_id && item.member_generation == member_generation
        }) else {
            return;
        };
        activity.voice = ChannelStatus::Revoked;
        activity.network = ChannelStatus::Revoked;
    }

    pub fn clear_activity(&self, activity_id: &str) {
        let mut state = self.state.lock().expect("client state lock");
        if state
            .snapshot
            .activity
            .as_ref()
            .is_some_and(|item| item.activity_id == activity_id)
        {
            state.snapshot.activity = None;
            state.pending_request = None;
        }
    }
}

#[derive(Clone, Copy)]
pub enum Channel {
    Voice,
    Network,
}

impl Default for ClientSnapshot {
    fn default() -> Self {
        Self {
            user_id: None,
            activity: None,
            service: ChannelStatus::Disconnected,
            last_error_code: None,
        }
    }
}
