use std::net::SocketAddr;

use crate::model::RelayGrant;

pub trait GrantValidator: Send + Sync {
    fn validate(&self, ticket: &str, endpoint: SocketAddr) -> Result<RelayGrant, String>;
}
