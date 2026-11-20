use std::convert::Infallible;
use std::net::SocketAddr;
use std::time::Duration;

use bytes::Bytes;
use http_body_util::{BodyExt, Full, Limited};
use hyper::body::Incoming;
use hyper::service::service_fn;
use hyper::{Method, Request, Response, StatusCode};
use hyper_util::rt::TokioIo;
use subtle::ConstantTimeEq;
use tokio::net::TcpListener;
use tokio::task::JoinSet;

use crate::revocation::{RelayRevocations, RevocationCommand};

pub struct RelayManagementServer {
    address: SocketAddr,
    worker: Option<tokio::task::JoinHandle<()>>,
}

impl RelayManagementServer {
    pub async fn bind(
        address: SocketAddr,
        key: String,
        revocations: RelayRevocations,
    ) -> Result<Self, &'static str> {
        if !address.ip().is_loopback()
            || !(32..=256).contains(&key.len())
            || !key.bytes().all(|character| (33..=126).contains(&character))
        {
            return Err("RELAY_MANAGEMENT_CONFIGURATION_INVALID");
        }
        let listener = TcpListener::bind(address)
            .await
            .map_err(|_| "RELAY_MANAGEMENT_BIND_FAILED")?;
        let address = listener
            .local_addr()
            .map_err(|_| "RELAY_MANAGEMENT_BIND_FAILED")?;
        let worker = tokio::spawn(async move {
            let mut workers = JoinSet::new();
            loop {
                tokio::select! {
                    accepted = listener.accept() => {
                        let Ok((stream, _)) = accepted else { break; };
                        if workers.len() >= 32 { continue; }
                        let key = key.clone();
                        let revocations = revocations.clone();
                        workers.spawn(async move {
                            let service = service_fn(move |request| handle(request, key.clone(), revocations.clone()));
                            let mut builder = hyper::server::conn::http1::Builder::new();
                            builder.keep_alive(false).max_buf_size(8192).auto_date_header(false);
                            let _ = tokio::time::timeout(Duration::from_secs(3),
                                builder.serve_connection(TokioIo::new(stream), service)).await;
                        });
                    }
                    _ = workers.join_next(), if !workers.is_empty() => {}
                }
            }
            workers.abort_all();
        });
        Ok(Self {
            address,
            worker: Some(worker),
        })
    }

    pub fn local_addr(&self) -> SocketAddr {
        self.address
    }

    pub async fn stop(mut self) {
        if let Some(worker) = self.worker.take() {
            worker.abort();
            let _ = worker.await;
        }
    }
}

impl Drop for RelayManagementServer {
    fn drop(&mut self) {
        if let Some(worker) = self.worker.take() {
            worker.abort();
        }
    }
}

async fn handle(
    request: Request<Incoming>,
    key: String,
    revocations: RelayRevocations,
) -> Result<Response<Full<Bytes>>, Infallible> {
    let response = |status, body: String| {
        Response::builder()
            .status(status)
            .header("Content-Type", "application/json")
            .body(Full::new(Bytes::from(body)))
            .unwrap()
    };
    if request.method() != Method::POST
        || request.uri().path() != "/internal/relay/v1/revocations"
        || request.uri().query().is_some()
    {
        return Ok(response(StatusCode::NOT_FOUND, "{}".into()));
    }
    let credentials: Vec<_> = request
        .headers()
        .get_all("X-Relay-Revocation-Key")
        .iter()
        .collect();
    if credentials.len() != 1 || !bool::from(credentials[0].as_bytes().ct_eq(key.as_bytes())) {
        return Ok(response(StatusCode::FORBIDDEN, "{}".into()));
    }
    if request
        .headers()
        .get("Content-Type")
        .and_then(|value| value.to_str().ok())
        != Some("application/json")
    {
        return Ok(response(StatusCode::UNSUPPORTED_MEDIA_TYPE, "{}".into()));
    }
    let body = match Limited::new(request.into_body(), 4096).collect().await {
        Ok(body) => body.to_bytes(),
        Err(_) => return Ok(response(StatusCode::PAYLOAD_TOO_LARGE, "{}".into())),
    };
    let command = match serde_json::from_slice::<RevocationCommand>(&body) {
        Ok(command) if command.validate().is_ok() => command,
        _ => return Ok(response(StatusCode::BAD_REQUEST, "{}".into())),
    };
    if revocations.apply(&command).is_err() {
        return Ok(response(StatusCode::SERVICE_UNAVAILABLE, "{}".into()));
    }
    Ok(response(
        StatusCode::OK,
        serde_json::json!({
            "version": 1, "eventId": command.event_id, "status": "applied"
        })
        .to_string(),
    ))
}
