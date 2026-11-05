pub mod ipc;
pub mod model;
pub mod service;
pub mod system;

pub const HELPER_PROTOCOL_VERSION: u16 = 1;

#[cfg(test)]
mod tests;
