use crate::AppState;
use zhijie_core::ClientError;

pub type Renewal = Option<(String, std::time::Instant)>;

// 调用者持有 activity_operations；本地退出不能被远端失败阻止。
pub async fn logout(state: &AppState) -> zhijie_core::ClientSnapshot {
    logout_with_cleanup(state, || state.network.stop()).await
}

pub(crate) async fn logout_with_cleanup<F, Fut>(
    state: &AppState,
    cleanup: F,
) -> zhijie_core::ClientSnapshot
where
    F: FnOnce() -> Fut,
    Fut: std::future::Future<Output = Result<(), String>>,
{
    let activity = state.core.snapshot().activity;
    let tokens = state.session.lock().expect("session lock").take();
    state.core.session_changed(None);
    state.voice.disconnect().await;
    let mut incomplete = false;
    if let Err(code) = cleanup().await {
        state.network.fail(code);
        incomplete = true;
    }
    if let Some(tokens) = tokens {
        if let Some(activity) = activity {
            incomplete |= state
                .client
                .leave(&tokens.access_token, &activity.activity_id)
                .await
                .is_err();
        }
        // 即使离房失败也尝试注销；失败不能恢复本地会话或继续续票。
        incomplete |= state
            .client
            .product_request(
                None,
                "POST",
                "/api/v1/auth/logout",
                serde_json::json!({"accessToken": tokens.access_token}),
            )
            .await
            .is_err();
    }
    let mut snapshot = state.core.snapshot();
    if incomplete {
        snapshot.last_error_code = Some("LOGOUT_CLEANUP_PENDING".into());
    }
    snapshot
}

// 调用者持有 activity_operations；登录失败不能破坏现有连接。
pub async fn login(
    state: &AppState,
    email: &str,
    password: &str,
    device_id: &str,
) -> Result<zhijie_core::ClientSnapshot, String> {
    login_with_cleanup(state, email, password, device_id, || state.network.stop()).await
}

pub(crate) async fn login_with_cleanup<F, Fut>(
    state: &AppState,
    email: &str,
    password: &str,
    device_id: &str,
    cleanup: F,
) -> Result<zhijie_core::ClientSnapshot, String>
where
    F: FnOnce() -> Fut,
    Fut: std::future::Future<Output = Result<(), String>>,
{
    let tokens = state
        .client
        .login(email, password, device_id)
        .await
        .map_err(crate::error_code)?;
    state.voice.disconnect().await;
    if let Err(code) = cleanup().await {
        // NetworkManager 保留停止请求，原维护周期会重试，不能续用旧账号连接。
        state.network.fail(code);
    }
    // 同一用户重新登录也是新会话，旧活动资格不得继承。
    state.core.session_changed(None);
    state.core.session_changed(Some(tokens.user_id.clone()));
    *state.session.lock().expect("session lock") = Some(tokens);
    Ok(state.core.snapshot())
}

// 调用者持有 activity_operations，串行登录、切房、刷新与清理。
pub async fn tick(state: &AppState, renewed: &mut Renewal) {
    tick_with_cleanup(state, renewed, || state.network.retry_cleanup()).await;
}

pub(crate) async fn tick_with_cleanup<F, Fut>(state: &AppState, renewed: &mut Renewal, cleanup: F)
where
    F: FnOnce() -> Fut,
    Fut: std::future::Future<Output = Result<(), String>>,
{
    // 网络清理不依赖登录或活动资格，账号失效后仍按原维护周期重试。
    if let Err(code) = cleanup().await {
        state.network.fail(code);
    }
    let tokens = state.session.lock().expect("session lock").clone();
    if let Some(tokens) = tokens {
        let needs_refresh = time::OffsetDateTime::parse(
            &tokens.access_expires_at,
            &time::format_description::well_known::Rfc3339,
        )
        .map(|expires| expires <= time::OffsetDateTime::now_utc() + time::Duration::seconds(60))
        .unwrap_or(true);
        if needs_refresh {
            match state.client.refresh(&tokens.refresh_token).await {
                Ok(fresh) => *state.session.lock().expect("session lock") = Some(fresh),
                Err(ClientError::Api {
                    status: 401 | 403, ..
                }) => {
                    invalidate(state).await;
                    *renewed = None;
                    return;
                }
                Err(_) => {}
            }
        }
    }
    let Ok(token) = state.access_token() else {
        *renewed = None;
        return;
    };
    if let Some(activity) = state.core.snapshot().activity {
        if renewed
            .as_ref()
            .is_none_or(|(lease, _)| lease != &activity.lease_id)
        {
            *renewed = Some((activity.lease_id.clone(), std::time::Instant::now()));
        }
        match state.client.heartbeat(&token, &activity).await {
            Ok(()) => {
                *renewed = Some((activity.lease_id.clone(), std::time::Instant::now()));
                if let Err(code) = state
                    .network
                    .maintain(&state.client, &token, &activity)
                    .await
                {
                    state.network.fail(code);
                }
            }
            Err(error) => {
                let denied = matches!(
                    &error,
                    ClientError::Api {
                        status: 401 | 403 | 404 | 409,
                        ..
                    }
                );
                let expired = renewed
                    .as_ref()
                    .is_some_and(|(_, time)| time.elapsed().as_secs() >= 60);
                if denied || expired {
                    state
                        .core
                        .revoke(&activity.activity_id, activity.member_generation);
                    state.voice.revoke().await;
                    if let Err(code) = state.network.stop().await {
                        state.network.fail(code);
                    }
                    state.core.clear_activity(&activity.activity_id);
                }
            }
        }
    } else {
        *renewed = None;
    }
    let presence = state
        .client
        .product_request(
            Some(&token),
            "POST",
            "/api/v1/presence/heartbeat",
            serde_json::json!({}),
        )
        .await;
    if matches!(
        presence,
        Err(ClientError::Api {
            status: 401 | 403,
            ..
        })
    ) {
        let current = state.session.lock().expect("session lock").clone();
        if let Some(current) = current {
            match state.client.refresh(&current.refresh_token).await {
                Ok(fresh) => *state.session.lock().expect("session lock") = Some(fresh),
                Err(ClientError::Api {
                    status: 401 | 403, ..
                }) => {
                    invalidate(state).await;
                    *renewed = None;
                }
                // 暂时不可达不清除仍可能有效的会话，下个周期继续重试。
                Err(_) => {}
            }
        }
    }
}

async fn invalidate(state: &AppState) {
    if let Some(activity) = state.core.snapshot().activity {
        state
            .core
            .revoke(&activity.activity_id, activity.member_generation);
    }
    state.voice.revoke().await;
    if let Err(code) = state.network.stop().await {
        state.network.fail(code);
    }
    *state.session.lock().expect("session lock") = None;
    state.core.session_changed(None);
}

#[cfg(test)]
mod tests;
