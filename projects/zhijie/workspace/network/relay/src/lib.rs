pub mod control_plane;
pub mod management;
pub mod model;
pub mod quic;
pub mod revocation;
pub mod server;

pub const RELAY_PROTOCOL_VERSION: u16 = 1;

#[cfg(test)]
mod tests;
