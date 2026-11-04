use crate::{ActivityResult, Channel, ChannelStatus, ClientCore, CommandRequest};

fn result() -> ActivityResult {
    ActivityResult {
        activity_id: "activity".into(), activity_generation: 1, member_generation: 2,
        device_id: "device".into(), lease_id: "lease".into(), policy_revision: 1,
        voice_state: ChannelStatus::Connected, network_state: ChannelStatus::Connected,
        virtual_ipv4: "10.231.0.1".into(), activity_cidr: "10.231.0.0/27".into(),
    }
}

#[test]
fn late_results_cannot_replace_current_activity() {
    let core = ClientCore::default();
    core.begin_activity("request-current".into(), "activity".into(), "device".into());
    assert!(!core.apply_activity_result("request-old", result()));
    assert!(core.apply_activity_result("request-current", result()));
    assert_eq!(core.snapshot().activity.unwrap().virtual_ipv4.as_deref(), Some("10.231.0.1"));
}

#[test]
fn voice_and_network_fail_retry_and_revoke_independently() {
    let core = ClientCore::default();
    core.begin_activity("request".into(), "activity".into(), "device".into());
    core.apply_activity_result("request", result());
    core.channel_failed("activity", Channel::Voice, "VOICE_FAILED".into());
    assert_eq!(core.snapshot().activity.as_ref().unwrap().voice, ChannelStatus::Failed);
    assert_eq!(core.snapshot().activity.as_ref().unwrap().network, ChannelStatus::Connected);
    core.retry_channel("activity", Channel::Voice);
    assert_eq!(core.snapshot().activity.as_ref().unwrap().voice, ChannelStatus::Retrying);
    core.revoke("activity", 2);
    assert_eq!(core.snapshot().activity.unwrap().network, ChannelStatus::Revoked);
}

#[test]
fn protocol_requires_current_version_and_request_id() {
    let mut request = CommandRequest {
        protocol_version: 1, request_id: "request".into(), activity_id: None,
        member_generation: None, device_id: None, payload: serde_json::json!({}),
    };
    assert!(request.validate().is_ok());
    request.protocol_version = 99;
    assert_eq!(request.validate(), Err("PROTOCOL_VERSION_UNSUPPORTED"));
}
