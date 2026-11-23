mod authority;
#[cfg(windows)]
mod install_windows;
#[cfg(windows)]
pub mod desktop_launch;
pub mod ipc;
pub mod model;
mod router;
pub mod service;
pub mod startup;
pub mod system;
#[cfg(windows)]
mod windows_identity;

pub const HELPER_PROTOCOL_VERSION: u16 = 1;

#[cfg(test)]
mod tests;
