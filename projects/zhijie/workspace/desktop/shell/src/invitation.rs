//! 外部邀请只定位待确认的操作，不授权启动 Helper、入房或访问服务端。
use std::sync::Mutex;

const PREFIX: &str = "zhijie://room/";

#[derive(Default)]
pub(crate) struct Inbox(Mutex<Option<String>>);

impl Inbox {
    pub(crate) fn receive(&self, mut args: impl Iterator<Item = String>) -> bool {
        let Some(url) = args.next() else { return false };
        let Some(code) = url.strip_prefix(PREFIX) else {
            return false;
        };
        if args.next().is_some()
            || code.len() != 43
            || !code
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_'))
        {
            return false;
        }
        self.0
            .lock()
            .expect("invitation inbox")
            .replace(code.to_owned());
        true
    }

    pub(crate) fn take(&self) -> Option<String> {
        self.0.lock().expect("invitation inbox").take()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepts_only_one_canonical_invitation_and_takes_it_once() {
        let inbox = Inbox::default();
        let code = "z".repeat(43);
        let canonical = format!("{PREFIX}{code}");
        assert!(inbox.receive([canonical.clone()].into_iter()));
        for invalid in [
            vec![],
            vec![format!("{canonical}?next=other")],
            vec![format!("{canonical}#x")],
            vec![format!("https://room/{code}")],
            vec![format!("zhijie://other/{code}")],
            vec![format!("{PREFIX}{}", "é".repeat(43))],
            vec![format!("{PREFIX}short")],
            vec![canonical, "--other".into()],
        ] {
            assert!(!inbox.receive(invalid.into_iter()));
        }
        assert_eq!(inbox.take(), Some(code));
        assert_eq!(inbox.take(), None);
    }

    #[test]
    fn most_recent_valid_open_wins_before_login() {
        let inbox = Inbox::default();
        for c in ['-', '_'] {
            assert!(inbox.receive([format!("{PREFIX}{}", c.to_string().repeat(43))].into_iter()));
        }
        assert_eq!(inbox.take(), Some("_".repeat(43)));
    }
}
