use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use quinn::Connection;
use serde::{Deserialize, Serialize};

use crate::model::RelayGrant;

const MAX_DENIALS: usize = 16_384;
const DENIAL_RETENTION: Duration = Duration::from_secs(65);

#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RevocationCommand {
    pub version: u16,
    pub event_id: String,
    pub activity_id: String,
    pub user_id: String,
    pub member_generation: u64,
}

impl RevocationCommand {
    pub fn validate(&self) -> Result<(), &'static str> {
        if self.version != 1
            || self.member_generation == 0
            || [&self.event_id, &self.activity_id, &self.user_id]
                .iter()
                .any(|value| {
                    value.is_empty()
                        || value.len() > 128
                        || !value.bytes().all(|character| {
                            character.is_ascii_alphanumeric() || b"-_.:".contains(&character)
                        })
                })
        {
            return Err("RELAY_REVOCATION_INVALID");
        }
        Ok(())
    }
}

#[derive(Clone)]
pub(crate) struct Session {
    pub grant: RelayGrant,
    pub connection: Connection,
}

struct Denial {
    generation: u64,
    expires: Instant,
}

#[derive(Default)]
pub(crate) struct SessionState {
    sessions: HashMap<(String, String), Session>,
    denials: HashMap<(String, String), Denial>,
}

impl SessionState {
    pub fn denied(&self, grant: &RelayGrant) -> bool {
        self.denials
            .get(&(grant.activity_id.clone(), grant.user_id.clone()))
            .is_some_and(|entry| {
                entry.expires > Instant::now() && grant.member_generation <= entry.generation
            })
    }

    pub fn insert(&mut self, key: (String, String), session: Session) -> Result<(), ()> {
        if self.denied(&session.grant) {
            return Err(());
        }
        if let Some(previous) = self.sessions.insert(key, session) {
            previous.connection.close(0u8.into(), b"superseded");
        }
        Ok(())
    }

    pub fn target(&self, key: &(String, String)) -> Option<Session> {
        self.sessions.get(key).cloned()
    }

    pub fn remove(&mut self, key: &(String, String), connection: &Connection) {
        if self
            .sessions
            .get(key)
            .is_some_and(|session| session.connection.stable_id() == connection.stable_id())
        {
            self.sessions.remove(key);
        }
    }

    fn revoke(&mut self, command: &RevocationCommand) -> Result<(), &'static str> {
        let now = Instant::now();
        self.denials.retain(|_, entry| entry.expires > now);
        let key = (command.activity_id.clone(), command.user_id.clone());
        if !self.denials.contains_key(&key) && self.denials.len() >= MAX_DENIALS {
            return Err("RELAY_REVOCATION_CAPACITY");
        }
        let previous = self.denials.get(&key).map_or(0, |entry| entry.generation);
        let generation = previous.max(command.member_generation);
        self.denials.insert(
            key,
            Denial {
                generation,
                expires: now + DENIAL_RETENTION,
            },
        );
        self.sessions.retain(|_, session| {
            let revoked = session.grant.activity_id == command.activity_id
                && session.grant.user_id == command.user_id
                && session.grant.member_generation <= generation;
            if revoked {
                session
                    .connection
                    .close(1u8.into(), b"authorization revoked");
            }
            !revoked
        });
        Ok(())
    }
}

#[derive(Clone, Default)]
pub struct RelayRevocations {
    pub(crate) state: Arc<Mutex<SessionState>>,
}

impl RelayRevocations {
    pub fn apply(&self, command: &RevocationCommand) -> Result<(), &'static str> {
        command.validate()?;
        self.state
            .lock()
            .map_err(|_| "RELAY_STATE_UNAVAILABLE")?
            .revoke(command)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn command(generation: u64) -> RevocationCommand {
        RevocationCommand {
            version: 1,
            event_id: "event".into(),
            activity_id: "activity".into(),
            user_id: "user".into(),
            member_generation: generation,
        }
    }

    fn grant(generation: u64) -> RelayGrant {
        RelayGrant {
            ticket: "ticket".into(),
            activity_id: "activity".into(),
            user_id: "user".into(),
            member_generation: generation,
            lease_id: "lease".into(),
            source_ipv4: "10.231.0.1".into(),
            policy_revision: 1,
            allow_relay: true,
        }
    }

    #[test]
    fn duplicates_never_lower_denied_generation_and_expired_entries_are_pruned() {
        let mut state = SessionState::default();
        state.revoke(&command(3)).unwrap();
        state.revoke(&command(1)).unwrap();
        assert!(state.denied(&grant(1)));
        assert!(state.denied(&grant(3)));
        assert!(!state.denied(&grant(4)));
        state
            .denials
            .get_mut(&("activity".into(), "user".into()))
            .unwrap()
            .expires = Instant::now();
        assert!(!state.denied(&grant(3)));
        state
            .revoke(&RevocationCommand {
                user_id: "other".into(),
                ..command(1)
            })
            .unwrap();
        assert_eq!(state.denials.len(), 1);
    }

    #[test]
    fn full_denial_table_refuses_new_scope_without_evicting_existing_revocations() {
        let mut state = SessionState::default();
        for index in 0..MAX_DENIALS {
            state.denials.insert(
                ("activity".into(), format!("user-{index}")),
                Denial {
                    generation: 1,
                    expires: Instant::now() + DENIAL_RETENTION,
                },
            );
        }
        assert_eq!(state.revoke(&command(1)), Err("RELAY_REVOCATION_CAPACITY"));
        assert_eq!(state.denials.len(), MAX_DENIALS);
        state
            .revoke(&RevocationCommand {
                user_id: "user-0".into(),
                ..command(2)
            })
            .unwrap();
        assert_eq!(
            state.denials[&("activity".into(), "user-0".into())].generation,
            2
        );
        state
            .denials
            .get_mut(&("activity".into(), "user-1".into()))
            .unwrap()
            .expires = Instant::now();
        state.revoke(&command(1)).unwrap();
        assert_eq!(state.denials.len(), MAX_DENIALS);
        assert!(state.denied(&grant(1)));
    }
}
