use std::sync::{Arc, Mutex};

use crate::model::{ActivityContext, ActivityResult, ChannelStatus, ClientSnapshot};

#[derive(Clone, Default)]
pub struct ClientCore {
    snapshot: Arc<Mutex<ClientSnapshot>>,
    pending: Arc<Mutex<Option<PendingActivity>>>,
}

#[derive(Clone)]
struct PendingActivity {
    request_id: String,
}

impl ClientCore {
    pub fn snapshot(&self) -> ClientSnapshot {
        self.snapshot.lock().expect("client state lock").clone()
    }

    pub fn session_changed(&self, user_id: Option<String>) {
        let mut snapshot = self.snapshot.lock().expect("client state lock");
        snapshot.user_id = user_id;
        snapshot.service = ChannelStatus::Connected;
        snapshot.last_error_code = None;
    }

    pub fn begin_activity(&self, request_id: String, activity_id: String, device_id: String) {
        *self.pending.lock().expect("pending activity lock") = Some(PendingActivity { request_id });
        self.snapshot.lock().expect("client state lock").activity = Some(ActivityContext {
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
        let pending = self.pending.lock().expect("pending activity lock").clone();
        if !pending.is_some_and(|item| item.request_id == request_id) {
            return false;
        }
        let mut snapshot = self.snapshot.lock().expect("client state lock");
        let Some(current) = snapshot.activity.as_ref() else {
            return false;
        };
        if (current.activity_generation > 0 && current.activity_id != result.activity_id)
            || current.activity_generation > result.activity_generation
            || current.member_generation > result.member_generation
        {
            return false;
        }
        snapshot.activity = Some(ActivityContext {
            activity_id: result.activity_id,
            activity_generation: result.activity_generation,
            member_generation: result.member_generation,
            device_id: result.device_id,
            lease_id: result.lease_id,
            virtual_ipv4: Some(result.virtual_ipv4),
            voice: result.voice_state,
            network: result.network_state,
        });
        snapshot.last_error_code = None;
        true
    }

    pub fn channel_failed(&self, activity_id: &str, channel: Channel, error_code: String) {
        let mut snapshot = self.snapshot.lock().expect("client state lock");
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
        let mut snapshot = self.snapshot.lock().expect("client state lock");
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
        let mut snapshot = self.snapshot.lock().expect("client state lock");
        let Some(activity) = snapshot.activity.as_mut().filter(|item| {
            item.activity_id == activity_id && item.member_generation == member_generation
        }) else {
            return;
        };
        activity.voice = ChannelStatus::Revoked;
        activity.network = ChannelStatus::Revoked;
    }

    pub fn clear_activity(&self, activity_id: &str) {
        let mut snapshot = self.snapshot.lock().expect("client state lock");
        if snapshot
            .activity
            .as_ref()
            .is_some_and(|item| item.activity_id == activity_id)
        {
            snapshot.activity = None;
            *self.pending.lock().expect("pending activity lock") = None;
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
