use std::io::Read;
use zhijie_core::{Channel, ClientCore, ClientError, ControlPlaneClient};

#[derive(serde::Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Configuration {
    base_url: String,
    email: String,
    password: String,
    room_id: String,
}

#[tokio::main]
async fn main() {
    if let Err(error) = run().await {
        eprintln!("{error}");
        std::process::exit(1);
    }
}

async fn run() -> Result<(), String> {
    if std::env::args_os().len() != 1 {
        return Err("VERTICAL_SLICE_STDIN_REQUIRED".into());
    }
    // 测试凭据只从匿名 stdin 获取，不进入命令行或配置文件。
    let mut input = Vec::new();
    std::io::stdin()
        .take(16385)
        .read_to_end(&mut input)
        .map_err(|_| "VERTICAL_SLICE_INPUT_INVALID")?;
    if input.len() > 16384 {
        return Err("VERTICAL_SLICE_INPUT_INVALID".into());
    }
    let config: Configuration =
        serde_json::from_slice(&input).map_err(|_| "VERTICAL_SLICE_INPUT_INVALID")?;
    let client =
        ControlPlaneClient::new(&config.base_url).map_err(|_| "HTTP_CLIENT_FAILED".to_string())?;
    let tokens = client
        .login(&config.email, &config.password, "vertical-slice-device")
        .await
        .map_err(error)?;
    let core = ClientCore::default();
    core.session_changed(Some(tokens.user_id.clone()));
    core.begin_activity(
        "vertical-slice-join".into(),
        config.room_id.clone(),
        "vertical-slice-device".into(),
    );
    let joined = client
        .join(
            &tokens.access_token,
            &config.room_id,
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
            "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=",
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
        .leave(&tokens.access_token, &joined.activity_id, &joined.lease_id)
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
