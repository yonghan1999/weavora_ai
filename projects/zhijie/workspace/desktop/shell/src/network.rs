use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;

use base64::Engine;
use boringtun::x25519::{PublicKey, StaticSecret};
use rand::RngCore;
use zhijie_core::{ActivityContext, ControlPlaneClient, NetworkGrant};
use zhijie_helper::ipc::CallFailure;
use zhijie_helper::model::{HelperRequest, NetworkPath, NetworkStart, NetworkState, NetworkStatus};

#[derive(Default)]
pub struct NetworkManager {
    pub(super) operations: tokio::sync::Mutex<()>,
    #[cfg(windows)]
    pub(super) helper_prompt: tokio::sync::Mutex<()>,
    packaged_origin_matches: bool,
    current: Mutex<Option<NetworkStart>>,
    pending_renewal: Mutex<Option<NetworkStart>>,
    status: Mutex<NetworkStatus>,
    peers: Mutex<Vec<serde_json::Value>>,
    registration_error: Mutex<Option<String>>,
    stop_requested: AtomicBool,
}

impl NetworkManager {
    pub(super) fn for_origin(origin: &str) -> Self {
        Self {
            packaged_origin_matches: origin == super::packaged_origin(),
            ..Self::default()
        }
    }

    pub(super) fn can_authorize_helper(&self) -> bool {
        cfg!(windows)
            && self.packaged_origin_matches
            && std::env::var_os("ZHIJIE_HELPER_SOCKET").is_none()
    }

    pub fn snapshot(&self) -> serde_json::Value {
        serde_json::json!({"canAuthorizeHelper": self.can_authorize_helper(), "status": self.status.lock().expect("network status lock").clone(),
            "peers": self.peers.lock().expect("network peers lock").clone()})
    }

    #[cfg(windows)]
    pub(super) async fn authorize_helper(
        &self,
        activity: &str,
        origin: &'static str,
    ) -> Result<(), String> {
        {
            let status = self.status.lock().expect("network status lock");
            if status.activity_id.as_deref() != Some(activity)
                || status.state != NetworkState::Failed
                || status.error_code.as_deref() != Some("HELPER_UNAVAILABLE")
            {
                return Err("HELPER_LAUNCH_NOT_NEEDED".into());
            }
        }
        let probe = || call(request("helper.get_capabilities", None));
        match probe().await {
            Ok(_) => return Ok(()),
            Err(code) if code == "HELPER_UNAVAILABLE" => {}
            Err(code) => return Err(code),
        }
        let child = tauri::async_runtime::spawn_blocking(move || {
            zhijie_helper::desktop_launch::request_consent(origin)
        })
        .await
        .map_err(|_| "HELPER_LAUNCH_FAILED")??;
        let deadline = tokio::time::Instant::now() + std::time::Duration::from_secs(5);
        loop {
            if !child.running() {
                return Err("HELPER_LAUNCH_FAILED".into());
            }
            match probe().await {
                Ok(_) => return Ok(()),
                Err(code) if code == "HELPER_UNAVAILABLE" => {}
                Err(code) => return Err(code),
            }
            if tokio::time::Instant::now() >= deadline {
                return Err("HELPER_START_TIMEOUT".into());
            }
            tokio::time::sleep(std::time::Duration::from_millis(150)).await;
        }
    }

    pub async fn connect(
        &self,
        client: &ControlPlaneClient,
        token: &str,
        activity: &ActivityContext,
    ) -> Result<(), String> {
        self.stop().await?;
        *self.status.lock().expect("network status lock") = NetworkStatus {
            activity_id: Some(activity.activity_id.clone()),
            member_generation: Some(activity.member_generation),
            state: zhijie_helper::model::NetworkState::Connecting,
            ..NetworkStatus::default()
        };
        let mut private = [0; 32];
        rand::rngs::OsRng.fill_bytes(&mut private);
        let public = base64::engine::general_purpose::STANDARD
            .encode(PublicKey::from(&StaticSecret::from(private)).to_bytes());
        let grant = client
            .network_grant(token, &activity.activity_id, &public)
            .await
            .map_err(super::error_code)?;
        let start = start(
            activity,
            grant,
            base64::engine::general_purpose::STANDARD.encode(private),
        )?;
        // IPC 可能在网卡创建完成前超时，先保留票据，后续仍可显式清理该请求。
        *self.current.lock().expect("network request lock") = Some(start.clone());
        let response =
            self.start_result(call_with_delivery(request("network.start", Some(&start))).await)?;
        self.apply_helper_status(response);
        self.register(client, token, &start).await
    }

    fn start_result(
        &self,
        result: Result<NetworkStatus, CallFailure>,
    ) -> Result<NetworkStatus, String> {
        result.map_err(|failure| {
            if matches!(failure, CallFailure::NotSent(_)) {
                self.current.lock().expect("network request lock").take();
            }
            failure.into_code()
        })
    }

    pub async fn maintain(
        &self,
        client: &ControlPlaneClient,
        token: &str,
        activity: &ActivityContext,
    ) -> Result<(), String> {
        if self.stop_requested.load(Ordering::Relaxed) {
            // 原维护周期已单独重试清理，此处不得续票或在同一周期重复停止。
            return Ok(());
        }
        let Some(current) = self.current.lock().expect("network request lock").clone() else {
            return Ok(());
        };
        if current.activity_id != activity.activity_id
            || current.member_generation != activity.member_generation
            || current.lease_id != activity.lease_id
        {
            return self.stop().await;
        }
        let pending = self
            .pending_renewal
            .lock()
            .expect("network renewal lock")
            .clone();
        if let Some(updated) = pending {
            self.renew_with(updated.clone(), call).await?;
            return self.register(client, token, &updated).await;
        }
        let public = base64::engine::general_purpose::STANDARD.encode(
            PublicKey::from(&StaticSecret::from(
                <[u8; 32]>::try_from(
                    base64::engine::general_purpose::STANDARD
                        .decode(&current.private_key)
                        .map_err(|_| "PRIVATE_KEY_INVALID")?,
                )
                .map_err(|_| "PRIVATE_KEY_INVALID")?,
            ))
            .to_bytes(),
        );
        let grant = client
            .network_grant(token, &activity.activity_id, &public)
            .await
            .map_err(super::error_code)?;
        let updated = start(activity, grant, current.private_key.clone())?;
        if updated.policy_revision != current.policy_revision
            || updated.allowed_paths != current.allowed_paths
        {
            self.stop().await?;
            return Err("NETWORK_POLICY_CHANGED".into());
        }
        self.renew_with(updated.clone(), call).await?;
        self.register(client, token, &updated).await
    }

    async fn renew_with<F, Fut>(&self, updated: NetworkStart, transport: F) -> Result<(), String>
    where
        F: FnMut(HelperRequest) -> Fut,
        Fut: std::future::Future<Output = Result<NetworkStatus, String>>,
    {
        // 响应丢失时两端可能分别持有新旧票据，确认前不能丢弃任一候选。
        *self.pending_renewal.lock().expect("network renewal lock") = Some(updated.clone());
        let status = self
            .call_current_with("network.renew", Some(updated.clone()), transport)
            .await?;
        *self.current.lock().expect("network request lock") = Some(updated);
        self.pending_renewal
            .lock()
            .expect("network renewal lock")
            .take();
        self.apply_helper_status(status);
        Ok(())
    }

    async fn call_current_with<F, Fut>(
        &self,
        method: &str,
        updated: Option<NetworkStart>,
        mut transport: F,
    ) -> Result<NetworkStatus, String>
    where
        F: FnMut(HelperRequest) -> Fut,
        Fut: std::future::Future<Output = Result<NetworkStatus, String>>,
    {
        let current = self
            .current
            .lock()
            .expect("network request lock")
            .clone()
            .ok_or("NETWORK_NOT_STARTED")?;
        let pending = self
            .pending_renewal
            .lock()
            .expect("network renewal lock")
            .clone();
        if let Some(pending) = pending {
            let mut candidate = request(method, Some(&pending));
            candidate.start = updated.clone();
            match transport(candidate).await {
                Err(code) if code == "NETWORK_TICKET_REQUIRED" => {}
                result => return result,
            }
        }
        let mut candidate = request(method, Some(&current));
        candidate.start = updated;
        transport(candidate).await
    }

    pub async fn poll(&self) -> Result<serde_json::Value, String> {
        let current = self.current.lock().expect("network request lock").clone();
        if let Some(current) = current {
            self.apply_helper_status(call(request("network.status", Some(&current))).await?);
        }
        Ok(self.snapshot())
    }

    async fn register(
        &self,
        client: &ControlPlaneClient,
        token: &str,
        current: &NetworkStart,
    ) -> Result<(), String> {
        let result = self.register_peers(client, token, current).await;
        match &result {
            Ok(()) => {
                self.registration_error
                    .lock()
                    .expect("network registration lock")
                    .take();
            }
            Err(code) => {
                *self
                    .registration_error
                    .lock()
                    .expect("network registration lock") = Some(code.clone());
                self.peers.lock().expect("network peers lock").clear();
                self.fail(code.clone());
            }
        }
        result
    }

    async fn register_peers(
        &self,
        client: &ControlPlaneClient,
        token: &str,
        current: &NetworkStart,
    ) -> Result<(), String> {
        let port = self
            .status
            .lock()
            .expect("network status lock")
            .udp_port
            .ok_or("NETWORK_SOCKET_UNAVAILABLE")?;
        let endpoint = if current.allowed_paths.contains(&NetworkPath::Direct) {
            Some(format!("{}:{port}", advertise_address()?))
        } else {
            None
        };
        let result = client
            .product_request(
                Some(token),
                "POST",
                &format!(
                    "/api/v1/activities/{}/network-peers/register",
                    current.activity_id
                ),
                serde_json::json!({"ticket": current.ticket, "endpoint": endpoint}),
            )
            .await
            .map_err(super::error_code)?;
        let peers = result.as_array().ok_or("INVALID_SERVER_RESPONSE")?.clone();
        *self.peers.lock().expect("network peers lock") = peers;
        Ok(())
    }

    fn apply_helper_status(&self, mut status: NetworkStatus) {
        if self.stop_requested.load(Ordering::Relaxed) {
            status.state = NetworkState::Failed;
            status.error_code = self
                .status
                .lock()
                .expect("network status lock")
                .error_code
                .clone()
                .or_else(|| Some("NETWORK_CLEANUP_FAILED".into()));
        }
        if matches!(
            status.state,
            NetworkState::Connecting | NetworkState::Connected
        ) {
            if let Some(code) = self
                .registration_error
                .lock()
                .expect("network registration lock")
                .clone()
            {
                status.state = NetworkState::Failed;
                status.error_code = Some(code);
            }
        }
        *self.status.lock().expect("network status lock") = status;
    }

    pub async fn stop(&self) -> Result<(), String> {
        self.stop_with(call).await
    }

    pub async fn retry_cleanup(&self) -> Result<(), String> {
        self.retry_cleanup_with(call).await
    }

    async fn retry_cleanup_with<F, Fut>(&self, transport: F) -> Result<(), String>
    where
        F: FnMut(HelperRequest) -> Fut,
        Fut: std::future::Future<Output = Result<NetworkStatus, String>>,
    {
        if self.stop_requested.load(Ordering::Relaxed) {
            self.stop_with(transport).await?;
        }
        Ok(())
    }

    async fn stop_with<F, Fut>(&self, transport: F) -> Result<(), String>
    where
        F: FnMut(HelperRequest) -> Fut,
        Fut: std::future::Future<Output = Result<NetworkStatus, String>>,
    {
        let current = self.current.lock().expect("network request lock").clone();
        if current.is_some() {
            self.stop_requested.store(true, Ordering::Relaxed);
            if let Err(code) = self
                .call_current_with("network.stop", None, transport)
                .await
            {
                self.fail(code.clone());
                return Err(code);
            }
            self.current.lock().expect("network request lock").take();
        }
        self.pending_renewal
            .lock()
            .expect("network renewal lock")
            .take();
        *self.status.lock().expect("network status lock") = NetworkStatus::default();
        self.peers.lock().expect("network peers lock").clear();
        self.registration_error
            .lock()
            .expect("network registration lock")
            .take();
        self.stop_requested.store(false, Ordering::Relaxed);
        Ok(())
    }

    pub fn fail(&self, code: String) {
        let mut status = self.status.lock().expect("network status lock");
        status.state = zhijie_helper::model::NetworkState::Failed;
        status.error_code = Some(code);
    }
}

#[cfg(test)]
mod tests;

fn start(
    activity: &ActivityContext,
    grant: NetworkGrant,
    private_key: String,
) -> Result<NetworkStart, String> {
    if grant.activity_id != activity.activity_id
        || grant.member_generation != activity.member_generation
        || grant.lease_id != activity.lease_id
    {
        return Err("CURRENT_GRANT_REQUIRED".into());
    }
    let paths: Result<Vec<_>, _> = grant
        .allowed_paths
        .iter()
        .map(|value| match value.as_str() {
            "DIRECT" => Ok(NetworkPath::Direct),
            "RELAY" => Ok(NetworkPath::Relay),
            _ => Err("NETWORK_PATH_INVALID"),
        })
        .collect();
    Ok(NetworkStart {
        protocol_version: 1,
        request_id: format!("network-{}", rand::random::<u64>()),
        ticket: grant.network_ticket,
        activity_id: activity.activity_id.clone(),
        activity_generation: activity.activity_generation,
        member_generation: activity.member_generation,
        device_id: activity.device_id.clone(),
        lease_id: activity.lease_id.clone(),
        policy_revision: grant.policy_revision,
        allowed_paths: paths?,
        private_key,
        virtual_ipv4: grant.virtual_ipv4,
        activity_cidr: grant.activity_cidr,
        expires_at: grant.expires_at,
    })
}

fn request(method: &str, start: Option<&NetworkStart>) -> HelperRequest {
    HelperRequest {
        protocol_version: 1,
        request_id: format!("ipc-{}", rand::random::<u64>()),
        method: method.into(),
        activity_id: start.map(|item| item.activity_id.clone()),
        member_generation: start.map(|item| item.member_generation),
        policy_revision: start.map(|item| item.policy_revision),
        ticket: start.map(|item| item.ticket.clone()),
        start: if method == "network.start" {
            start.cloned()
        } else {
            None
        },
        peers: None,
        target_path: None,
    }
}

async fn call(request: HelperRequest) -> Result<NetworkStatus, String> {
    call_with_delivery(request)
        .await
        .map_err(CallFailure::into_code)
}

async fn call_with_delivery(request: HelperRequest) -> Result<NetworkStatus, CallFailure> {
    let path = zhijie_helper::startup::socket_path(std::env::var_os("ZHIJIE_HELPER_SOCKET"))
        .map_err(CallFailure::NotSent)?;
    let response = tauri::async_runtime::spawn_blocking(move || {
        zhijie_helper::ipc::call_with_delivery(&path, &request)
    })
    .await
    .map_err(|_| CallFailure::PossiblySent("HELPER_UNAVAILABLE".into()))??;
    if !response.accepted {
        return Err(CallFailure::PossiblySent(
            response
                .error_code
                .unwrap_or("NETWORK_REQUEST_FAILED".into()),
        ));
    }
    response
        .data
        .ok_or_else(|| CallFailure::PossiblySent("IPC_RESPONSE_INVALID".into()))
}

fn advertise_address() -> Result<std::net::Ipv4Addr, String> {
    let address = if let Ok(configured) = std::env::var("ZHIJIE_NETWORK_ADVERTISE_ADDRESS") {
        configured.parse().map_err(|_| "NETWORK_ENDPOINT_INVALID")?
    } else {
        // UDP connect 只查询操作系统路由，不发送探测数据或访问互联网。
        let socket =
            std::net::UdpSocket::bind("0.0.0.0:0").map_err(|_| "NETWORK_SOCKET_UNAVAILABLE")?;
        socket
            .connect("192.0.2.1:9")
            .map_err(|_| "NETWORK_ENDPOINT_REQUIRED")?;
        match socket
            .local_addr()
            .map_err(|_| "NETWORK_ENDPOINT_REQUIRED")?
            .ip()
        {
            std::net::IpAddr::V4(address) => address,
            _ => return Err("NETWORK_ENDPOINT_REQUIRED".into()),
        }
    };
    if address.is_unspecified() || address.is_multicast() || address.is_broadcast() {
        return Err("NETWORK_ENDPOINT_INVALID".into());
    }
    Ok(address)
}
