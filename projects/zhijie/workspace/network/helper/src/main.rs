use std::sync::Arc;

use zhijie_helper::ipc::HelperIpcServer;
use zhijie_helper::service::HelperService;
use zhijie_helper::system::SystemNetworkBackend;

fn main() -> Result<(), String> {
    let config = zhijie_helper::startup::StartupConfig::load()?;
    let backend = SystemNetworkBackend::with_control_plane_url(config.control_plane_url)?;
    let service = Arc::new(HelperService::new(Arc::new(backend)));
    let server = HelperIpcServer::for_client(service, config.client_pid)?;
    server.check_client()?;
    if config.check_only {
        println!("HELPER_STARTUP_OK protocol=1 client_bound=true tun_created=false");
        return Ok(());
    }
    let listener = server.bind(&config.socket)?;
    server.serve(&listener)
}
