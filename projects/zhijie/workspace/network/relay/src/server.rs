use std::collections::{HashMap, HashSet};
use std::net::{SocketAddr, UdpSocket};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;
use std::time::Duration;

use crate::model::{RelayGrant, RelaySession};

pub trait GrantValidator: Send + Sync {
    fn validate(&self, ticket: &str, endpoint: SocketAddr) -> Result<RelayGrant, String>;
}

#[derive(Default)]
struct Revocations {
    leases: HashSet<String>,
    activities: HashSet<String>,
}

pub struct RelayServer {
    socket: Arc<UdpSocket>,
    validator: Arc<dyn GrantValidator>,
    sessions: Arc<Mutex<HashMap<String, HashMap<String, RelaySession>>>>,
    revocations: Arc<Mutex<Revocations>>,
    running: Arc<AtomicBool>,
    worker: Mutex<Option<JoinHandle<()>>>,
}

impl RelayServer {
    pub fn bind(address: &str, validator: Arc<dyn GrantValidator>) -> Result<Self, String> {
        let socket = UdpSocket::bind(address).map_err(|error| error.to_string())?;
        socket
            .set_read_timeout(Some(Duration::from_millis(100)))
            .map_err(|error| error.to_string())?;
        Ok(Self {
            socket: Arc::new(socket),
            validator,
            sessions: Arc::new(Mutex::new(HashMap::new())),
            revocations: Arc::new(Mutex::new(Revocations::default())),
            running: Arc::new(AtomicBool::new(false)),
            worker: Mutex::new(None),
        })
    }

    pub fn local_addr(&self) -> SocketAddr {
        self.socket.local_addr().expect("relay address")
    }

    pub fn start(&self) {
        if self.running.swap(true, Ordering::SeqCst) {
            return;
        }
        let socket = self.socket.clone();
        let validator = self.validator.clone();
        let sessions = self.sessions.clone();
        let revocations = self.revocations.clone();
        let running = self.running.clone();
        *self.worker.lock().expect("relay worker lock") = Some(std::thread::spawn(move || {
            let mut buffer = vec![0u8; 65535];
            while running.load(Ordering::SeqCst) {
                match socket.recv_from(&mut buffer) {
                    Ok((length, source)) => {
                        if let Err(_code) = handle_datagram(
                            &socket,
                            &validator,
                            &sessions,
                            &revocations,
                            source,
                            &buffer[..length],
                        ) {}
                    }
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {}
                    Err(error) if error.kind() == std::io::ErrorKind::TimedOut => {}
                    Err(_) => break,
                }
            }
        }));
    }

    pub fn revoke_lease(&self, lease_id: &str) {
        self.revocations
            .lock()
            .expect("relay revocation lock")
            .leases
            .insert(lease_id.to_string());
        let mut sessions = self.sessions.lock().expect("relay sessions lock");
        for activity in sessions.values_mut() {
            activity.retain(|_, session| session.lease_id != lease_id);
        }
        sessions.retain(|_, peers| !peers.is_empty());
    }

    pub fn revoke_activity(&self, activity_id: &str) {
        self.revocations
            .lock()
            .expect("relay revocation lock")
            .activities
            .insert(activity_id.to_string());
        self.sessions
            .lock()
            .expect("relay sessions lock")
            .remove(activity_id);
    }

    pub fn stop(&self) {
        self.running.store(false, Ordering::SeqCst);
        if let Some(worker) = self.worker.lock().expect("relay worker lock").take() {
            let _ = worker.join();
        }
    }
}

impl Drop for RelayServer {
    fn drop(&mut self) {
        self.stop();
    }
}

fn handle_datagram(
    socket: &UdpSocket,
    validator: &Arc<dyn GrantValidator>,
    sessions: &Mutex<HashMap<String, HashMap<String, RelaySession>>>,
    revocations: &Mutex<Revocations>,
    source: SocketAddr,
    packet: &[u8],
) -> Result<(), String> {
    let (ticket, target_ip, ciphertext) = decode_packet(packet)?;
    let mut sessions = sessions.lock().expect("relay sessions lock");
    let grant = validator.validate(&ticket, source)?;
    let revoked = revocations.lock().expect("relay revocation lock");
    if revoked.leases.contains(&grant.lease_id) || revoked.activities.contains(&grant.activity_id) {
        return Err("RELAY_GRANT_REVOKED".into());
    }
    drop(revoked);
    if !grant.allow_relay {
        return Err("RELAY_DISABLED".into());
    }
    let current = RelaySession {
        activity_id: grant.activity_id,
        member_generation: grant.member_generation,
        lease_id: grant.lease_id,
        user_id: grant.user_id,
        source_ipv4: grant.source_ipv4,
        policy_revision: grant.policy_revision,
        endpoint: source,
    };
    let activity = sessions.entry(current.activity_id.clone()).or_default();
    activity.insert(current.source_ipv4.clone(), current.clone());
    let target = activity.get(&target_ip).ok_or("TARGET_NOT_AUTHORIZED")?;
    if target.endpoint == source || target.lease_id == current.lease_id {
        return Err("TARGET_NOT_AUTHORIZED".into());
    }
    socket
        .send_to(&ciphertext, target.endpoint)
        .map_err(|error| error.to_string())?;
    Ok(())
}

fn decode_packet(packet: &[u8]) -> Result<(String, String, Vec<u8>), String> {
    if packet.len() < 6 || &packet[..3] != b"ZR1" {
        return Err("RELAY_PROTOCOL_INVALID".into());
    }
    let ticket_length = u16::from_be_bytes([packet[3], packet[4]]) as usize;
    let target_offset = 5 + ticket_length;
    if packet.len() <= target_offset {
        return Err("RELAY_PROTOCOL_INVALID".into());
    }
    let target_length = packet[target_offset] as usize;
    if packet.len() <= target_offset + 1 + target_length {
        return Err("RELAY_PROTOCOL_INVALID".into());
    }
    let ticket =
        std::str::from_utf8(&packet[5..target_offset]).map_err(|_| "RELAY_TICKET_INVALID")?;
    let target = std::str::from_utf8(&packet[target_offset + 1..target_offset + 1 + target_length])
        .map_err(|_| "RELAY_TARGET_INVALID")?;
    Ok((
        ticket.to_string(),
        target.to_string(),
        packet[target_offset + 1 + target_length..].to_vec(),
    ))
}

pub fn encode_packet(ticket: &str, target_ip: &str, ciphertext: &[u8]) -> Result<Vec<u8>, String> {
    if ticket.len() > u16::MAX as usize || target_ip.len() > u8::MAX as usize {
        return Err("RELAY_PROTOCOL_TOO_LARGE".into());
    }
    let mut packet = Vec::with_capacity(6 + ticket.len() + target_ip.len() + ciphertext.len());
    packet.extend_from_slice(b"ZR1");
    packet.extend_from_slice(&(ticket.len() as u16).to_be_bytes());
    packet.extend_from_slice(ticket.as_bytes());
    packet.push(target_ip.len() as u8);
    packet.extend_from_slice(target_ip.as_bytes());
    packet.extend_from_slice(ciphertext);
    Ok(packet)
}
