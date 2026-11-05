use std::sync::Arc;

use zhijie_helper::ipc::{bind, HelperIpcServer};
use zhijie_helper::service::HelperService;
use zhijie_helper::system::SystemNetworkBackend;

fn main() -> Result<(), String> {
    let path = std::env::var_os("ZHIJIE_HELPER_SOCKET")
        .map(std::path::PathBuf::from)
        .unwrap_or_else(|| {
            #[cfg(windows)]
            {
                std::path::PathBuf::from(r"\\.\pipe\zhijie-helper-v1")
            }
            #[cfg(not(windows))]
            {
                std::path::PathBuf::from("/run/zhijie/helper-v1.sock")
            }
        });
    let listener = bind(&path)?;
    let service = Arc::new(HelperService::new(Arc::new(SystemNetworkBackend::new())));
    let server = HelperIpcServer::new(service);
    loop {
        server.serve_once(&listener)?;
    }
}
