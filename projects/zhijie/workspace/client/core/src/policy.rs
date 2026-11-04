use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum NetworkPolicyMode {
    DirectOnly,
    DirectWithRelayFallback,
    RelayOnly,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "UPPERCASE")]
pub enum NetworkPath {
    Direct,
    Relay,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "UPPERCASE")]
pub enum PathFailure {
    DirectUnavailable,
    RelayDisabled,
    PolicyStale,
}

#[derive(Debug, Clone, Copy)]
pub struct PathCoordinator {
    revision: u64,
    mode: NetworkPolicyMode,
    active_path: Option<NetworkPath>,
}

impl PathCoordinator {
    pub fn new(revision: u64, mode: NetworkPolicyMode) -> Self {
        Self { revision, mode, active_path: None }
    }

    pub fn initial_path(&self) -> NetworkPath {
        match self.mode {
            NetworkPolicyMode::RelayOnly => NetworkPath::Relay,
            _ => NetworkPath::Direct,
        }
    }

    pub fn active_path(&self) -> Option<NetworkPath> {
        self.active_path
    }

    pub fn connected(&mut self, revision: u64, path: NetworkPath) -> Result<(), PathFailure> {
        self.check_revision(revision)?;
        if !self.allows(path) {
            return Err(PathFailure::RelayDisabled);
        }
        self.active_path = Some(path);
        Ok(())
    }

    pub fn direct_failed(&mut self, revision: u64) -> Result<NetworkPath, PathFailure> {
        self.check_revision(revision)?;
        match self.mode {
            NetworkPolicyMode::DirectOnly => Err(PathFailure::DirectUnavailable),
            NetworkPolicyMode::RelayOnly => Err(PathFailure::DirectUnavailable),
            NetworkPolicyMode::DirectWithRelayFallback => {
                self.active_path = Some(NetworkPath::Relay);
                Ok(NetworkPath::Relay)
            }
        }
    }

    pub fn migrate(&mut self, revision: u64, mode: NetworkPolicyMode) -> NetworkPath {
        self.revision = revision;
        self.mode = mode;
        if self.active_path.is_some_and(|path| !self.allows(path)) {
            self.active_path = None;
        }
        self.initial_path()
    }

    pub fn revision(&self) -> u64 {
        self.revision
    }

    fn allows(&self, path: NetworkPath) -> bool {
        match self.mode {
            NetworkPolicyMode::DirectOnly => path == NetworkPath::Direct,
            NetworkPolicyMode::RelayOnly => path == NetworkPath::Relay,
            NetworkPolicyMode::DirectWithRelayFallback => true,
        }
    }

    fn check_revision(&self, revision: u64) -> Result<(), PathFailure> {
        if revision == self.revision { Ok(()) } else { Err(PathFailure::PolicyStale) }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn direct_only_never_falls_back_to_relay() {
        let mut policy = PathCoordinator::new(1, NetworkPolicyMode::DirectOnly);
        assert_eq!(policy.initial_path(), NetworkPath::Direct);
        assert_eq!(policy.direct_failed(1), Err(PathFailure::DirectUnavailable));
        assert_eq!(policy.active_path(), None);
    }

    #[test]
    fn fallback_uses_relay_only_after_direct_failure() {
        let mut policy = PathCoordinator::new(1, NetworkPolicyMode::DirectWithRelayFallback);
        assert_eq!(policy.initial_path(), NetworkPath::Direct);
        assert_eq!(policy.direct_failed(1), Ok(NetworkPath::Relay));
        assert_eq!(policy.active_path(), Some(NetworkPath::Relay));
        assert_eq!(policy.connected(1, NetworkPath::Relay), Ok(()));
    }

    #[test]
    fn relay_only_and_revision_changes_are_enforced() {
        let mut policy = PathCoordinator::new(1, NetworkPolicyMode::RelayOnly);
        assert_eq!(policy.initial_path(), NetworkPath::Relay);
        assert_eq!(policy.connected(1, NetworkPath::Direct), Err(PathFailure::RelayDisabled));
        assert_eq!(policy.direct_failed(2), Err(PathFailure::PolicyStale));
        assert_eq!(policy.migrate(2, NetworkPolicyMode::DirectOnly), NetworkPath::Direct);
        assert_eq!(policy.active_path(), None);
    }
}
