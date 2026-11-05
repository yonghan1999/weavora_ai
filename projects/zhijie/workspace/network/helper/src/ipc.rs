use std::io::{Read, Write};
use std::sync::Arc;

use interprocess::local_socket::prelude::*;
use interprocess::local_socket::{Listener, ListenerOptions};

use crate::model::{HelperRequest, HelperResponse, NetworkStatus};
use crate::service::HelperService;

pub const MAX_IPC_MESSAGE_BYTES: usize = 1024 * 1024;

pub struct HelperIpcServer {
    service: Arc<HelperService>,
}

impl HelperIpcServer {
    pub fn new(service: Arc<HelperService>) -> Self {
        Self { service }
    }

    pub fn serve_once(&self, listener: &Listener) -> Result<(), String> {
        let mut stream = listener.accept().map_err(|error| error.to_string())?;
        let _peer = stream.peer_creds().map_err(|error| error.to_string())?;
        self.handle_stream(&mut stream)
    }

    fn handle_stream(&self, stream: &mut interprocess::local_socket::Stream) -> Result<(), String> {
        let mut length = [0u8; 4];
        stream
            .read_exact(&mut length)
            .map_err(|error| error.to_string())?;
        let length = u32::from_be_bytes(length) as usize;
        if length > MAX_IPC_MESSAGE_BYTES {
            return Err("IPC_MESSAGE_TOO_LARGE".into());
        }
        let mut body = vec![0u8; length];
        stream
            .read_exact(&mut body)
            .map_err(|error| error.to_string())?;
        let request: HelperRequest =
            serde_json::from_slice(&body).map_err(|_| "IPC_SCHEMA_INVALID".to_string())?;
        let response: HelperResponse<NetworkStatus> = self.service.handle(request);
        let encoded = serde_json::to_vec(&response).map_err(|error| error.to_string())?;
        stream
            .write_all(&(encoded.len() as u32).to_be_bytes())
            .map_err(|error| error.to_string())?;
        stream
            .write_all(&encoded)
            .map_err(|error| error.to_string())
    }
}

pub fn bind(path: &std::path::Path) -> Result<Listener, String> {
    ListenerOptions::new()
        .name(
            path.to_fs_name::<interprocess::local_socket::GenericFilePath>()
                .map_err(|error| error.to_string())?,
        )
        .create_sync()
        .map_err(|error| error.to_string())
}
