use std::net::Ipv4Addr;
use std::ptr::null_mut;

use ipnet::Ipv4Net;
use windows_sys::Win32::{
    NetworkManagement::IpHelper::{
        FreeMibTable, GetIpForwardTable2, GetUnicastIpAddressTable, MIB_IPFORWARD_TABLE2,
        MIB_UNICASTIPADDRESS_TABLE,
    },
    Networking::WinSock::{AF_INET, SOCKADDR_INET},
};

const QUERY_FAILED: &str = "NETWORK_CONFIGURATION_UNAVAILABLE";

#[derive(Default)]
struct NetworkSnapshot {
    addresses: Vec<Ipv4Net>,
    routes: Vec<Ipv4Net>,
}

struct MibTable<T>(*mut T);

impl<T> Drop for MibTable<T> {
    fn drop(&mut self) {
        unsafe { FreeMibTable(self.0.cast()) };
    }
}

fn ipv4(address: &SOCKADDR_INET, prefix: u8) -> Result<Ipv4Net, String> {
    // 只读取 Get*Table(AF_INET) 返回的 IPv4 union 成员。
    if unsafe { address.si_family } != AF_INET || prefix > 32 {
        return Err(QUERY_FAILED.into());
    }
    let address = Ipv4Addr::from(unsafe { address.Ipv4.sin_addr.S_un.S_addr }.to_ne_bytes());
    Ipv4Net::new(address, prefix).map_err(|_| QUERY_FAILED.into())
}

fn snapshot() -> Result<NetworkSnapshot, String> {
    let mut addresses: *mut MIB_UNICASTIPADDRESS_TABLE = null_mut();
    let status = unsafe { GetUnicastIpAddressTable(AF_INET, &mut addresses) };
    if status != 0 || addresses.is_null() {
        return Err(QUERY_FAILED.into());
    }
    let addresses = MibTable(addresses);
    let mut routes: *mut MIB_IPFORWARD_TABLE2 = null_mut();
    let status = unsafe { GetIpForwardTable2(AF_INET, &mut routes) };
    if status != 0 || routes.is_null() {
        return Err(QUERY_FAILED.into());
    }
    let routes = MibTable(routes);
    let address_count = unsafe { (*addresses.0).NumEntries } as usize;
    let route_count = unsafe { (*routes.0).NumEntries } as usize;
    if address_count > 65536 || route_count > 65536 {
        return Err(QUERY_FAILED.into());
    }
    // Windows 返回具有 NumEntries 行的变长表，释放前复制为纯数据。
    let address_rows = unsafe {
        std::slice::from_raw_parts(std::ptr::addr_of!((*addresses.0).Table).cast::<
            windows_sys::Win32::NetworkManagement::IpHelper::MIB_UNICASTIPADDRESS_ROW,
        >(), address_count)
    };
    let route_rows = unsafe {
        std::slice::from_raw_parts(
            std::ptr::addr_of!((*routes.0).Table)
                .cast::<windows_sys::Win32::NetworkManagement::IpHelper::MIB_IPFORWARD_ROW2>(),
            route_count,
        )
    };
    Ok(NetworkSnapshot {
        addresses: address_rows
            .iter()
            .map(|row| ipv4(&row.Address, row.OnLinkPrefixLength))
            .collect::<Result<_, _>>()?,
        routes: route_rows
            .iter()
            .map(|row| {
                ipv4(
                    &row.DestinationPrefix.Prefix,
                    row.DestinationPrefix.PrefixLength,
                )
            })
            .collect::<Result<_, _>>()?,
    })
}

fn ensure_no_overlap(activity: Ipv4Net, snapshot: &NetworkSnapshot) -> Result<(), String> {
    let overlaps = |other: &Ipv4Net| {
        activity.contains(&other.network()) || other.contains(&activity.network())
    };
    // 默认路由本身不构成地址冲突；所有显式重叠网段/主机路由都保守拒绝。
    if snapshot.addresses.iter().any(overlaps)
        || snapshot
            .routes
            .iter()
            .filter(|route| route.prefix_len() != 0)
            .any(overlaps)
    {
        return Err("NETWORK_SUBNET_CONFLICT".into());
    }
    Ok(())
}

pub(super) fn check_activity_network(activity: Ipv4Net) -> Result<(), String> {
    ensure_no_overlap(activity, &snapshot()?)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn explicit_overlaps_rejected_but_default_and_unrelated_routes_allowed() {
        let activity = "10.231.0.32/27".parse().unwrap();
        for network in [
            "10.231.0.32/27",
            "10.231.0.40/32",
            "10.231.0.0/16",
            "0.0.0.0/1",
        ] {
            let network = network.parse().unwrap();
            assert!(ensure_no_overlap(
                activity,
                &NetworkSnapshot {
                    addresses: vec![network],
                    routes: vec![],
                }
            )
            .is_err());
            assert!(ensure_no_overlap(
                activity,
                &NetworkSnapshot {
                    addresses: vec![],
                    routes: vec![network],
                }
            )
            .is_err());
        }
        assert!(ensure_no_overlap(
            activity,
            &NetworkSnapshot {
                addresses: vec!["192.168.1.9/24".parse().unwrap()],
                routes: [
                    "0.0.0.0/0",
                    "10.231.0.0/27",
                    "10.231.0.64/27",
                    "192.168.1.0/24"
                ]
                .map(|network| network.parse().unwrap())
                .into(),
            }
        )
        .is_ok());
    }

    #[test]
    fn native_ipv4_conversion_preserves_network_byte_order_and_rejects_invalid_data() {
        let mut socket = SOCKADDR_INET::default();
        socket.Ipv4.sin_family = AF_INET;
        socket.Ipv4.sin_addr.S_un.S_addr = u32::from_ne_bytes([10, 231, 0, 40]);
        assert_eq!(ipv4(&socket, 27).unwrap().to_string(), "10.231.0.40/27");
        assert!(ipv4(&socket, 33).is_err());
        socket.si_family = 0;
        assert!(ipv4(&socket, 27).is_err());
    }

    #[test]
    fn real_windows_tables_are_read_without_changing_interfaces_or_routes() {
        let snapshot = snapshot().unwrap();
        assert!(!snapshot.addresses.is_empty());
        assert!(!snapshot.routes.is_empty());
        let existing = snapshot.addresses[0];
        let activity = Ipv4Net::new(existing.addr(), 27).unwrap();
        assert_eq!(
            ensure_no_overlap(activity, &snapshot).unwrap_err(),
            "NETWORK_SUBNET_CONFLICT"
        );
    }
}
