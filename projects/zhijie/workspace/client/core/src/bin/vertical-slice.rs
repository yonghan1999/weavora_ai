use zhijie_core::{Channel, ClientCore, ClientError, ControlPlaneClient};

#[tokio::main]
async fn main() {
    if let Err(error) = run().await {
        eprintln!("{error}");
        std::process::exit(1);
    }
}

async fn run() -> Result<(), String> {
    let args: Vec<String> = std::env::args().collect();
    if args.len() != 5 {
        return Err("usage: vertical-slice <base-url> <email> <password> <room-id>".into());
    }
    let client = ControlPlaneClient::new(&args[1]).map_err(|_| "HTTP_CLIENT_FAILED".to_string())?;
    let tokens = client
        .login(&args[2], &args[3], "vertical-slice-device")
        .await
        .map_err(error)?;
    let core = ClientCore::default();
    core.session_changed(Some(tokens.user_id.clone()));
    core.begin_activity(
        "vertical-slice-join".into(),
        args[4].clone(),
        "vertical-slice-device".into(),
    );
    let joined = client
        .join(
            &tokens.access_token,
            &args[4],
            "vertical-slice-device",
            None,
            "vertical-slice-join",
        )
        .await
        .map_err(error)?;
    assert!(core.apply_activity_result("vertical-slice-join", joined.clone()));

    let network = client
        .network_grant(
            &tokens.access_token,
            &joined.activity_id,
            "cHVibGljLWtleQ==",
        )
        .await
        .map_err(error)?;
    assert_eq!(network.virtual_ipv4, joined.virtual_ipv4);
    match client
        .voice_grant(&tokens.access_token, &joined.activity_id)
        .await
    {
        Err(ClientError::Api { code, .. }) if code == "VOICE_CAPACITY_LIMITED" => {
            core.channel_failed(&joined.activity_id, Channel::Voice, code);
        }
        Ok(_) => return Err("REAL_VOICE_REQUIRES_SFU_TEST_CONFIGURATION".into()),
        Err(other) => return Err(error(other)),
    }
    let during_failure = core.snapshot();
    assert_eq!(
        during_failure.activity.as_ref().unwrap().network,
        zhijie_core::ChannelStatus::Connecting
    );
    client
        .leave(&tokens.access_token, &joined.activity_id)
        .await
        .map_err(error)?;
    core.clear_activity(&joined.activity_id);
    println!(
        "{}",
        serde_json::json!({
            "login": "verified",
            "join": "verified",
            "networkGrant": "verified",
            "voice": "unverified_external_sfu",
            "independentFailure": "verified",
            "leaveCleanup": "verified",
            "tun": "unverified_real_device"
        })
    );
    Ok(())
}

fn error(error: ClientError) -> String {
    match error {
        ClientError::Api { code, .. } => code,
        ClientError::Transport(_) => "SERVICE_UNAVAILABLE".into(),
        ClientError::InvalidResponse(_) => "INVALID_SERVER_RESPONSE".into(),
    }
}
