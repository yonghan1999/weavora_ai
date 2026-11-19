use std::io::{Read, Write};
use std::sync::Arc;

use interprocess::local_socket::prelude::*;
use interprocess::local_socket::{Listener, ListenerOptions};

use crate::model::{HelperRequest, HelperResponse, NetworkStatus};
use crate::service::HelperService;

pub const MAX_IPC_MESSAGE_BYTES: usize = 1024 * 1024;

pub struct HelperIpcServer {
    service: Arc<HelperService>,
    client_pid: Option<u32>,
    #[cfg(windows)]
    identity: Option<crate::windows_identity::ClientIdentity>,
}

impl HelperIpcServer {
    #[cfg(test)]
    pub fn new(service: Arc<HelperService>) -> Self {
        Self {
            service,
            client_pid: None,
            #[cfg(windows)]
            identity: None,
        }
    }

    pub fn for_client(service: Arc<HelperService>, client_pid: u32) -> Result<Self, String> {
        if client_pid == 0 {
            return Err("IPC_CLIENT_PROCESS_UNAVAILABLE".into());
        }
        Ok(Self {
            service,
            client_pid: Some(client_pid),
            #[cfg(windows)]
            identity: Some(crate::windows_identity::ClientIdentity::open(client_pid)?),
        })
    }

    pub fn check_client(&self) -> Result<(), String> {
        #[cfg(windows)]
        if let Some(identity) = &self.identity {
            identity.check_alive()?;
        }
        Ok(())
    }

    pub fn bind(&self, path: &std::path::Path) -> Result<Listener, String> {
        self.check_client()?;
        let options = ListenerOptions::new().name(
            path.to_fs_name::<interprocess::local_socket::GenericFilePath>()
                .map_err(|_| "IPC_PATH_INVALID")?,
        );
        #[cfg(windows)]
        let options = {
            use interprocess::os::windows::local_socket::ListenerOptionsExt;
            options.security_descriptor(
                self.identity
                    .as_ref()
                    .ok_or("IPC_CLIENT_IDENTITY_UNAVAILABLE")?
                    .security_descriptor()?,
            )
        };
        options.create_sync().map_err(|_| "IPC_BIND_FAILED".into())
    }

    pub fn serve(&self, listener: &Listener) -> Result<(), String> {
        let result = self.serve_until_exit(listener);
        self.service.shutdown()?;
        result
    }

    fn serve_until_exit(&self, listener: &Listener) -> Result<(), String> {
        listener
            .set_nonblocking(interprocess::local_socket::ListenerNonblockingMode::Both)
            .map_err(|_| "IPC_UNAVAILABLE")?;
        loop {
            self.check_client()?;
            match listener.accept() {
                Ok(mut stream) => {
                    // 非阻塞 accept 让客户端退出时即使没有新请求也能结束服务。
                    let _ = self
                        .authorize_stream(&stream)
                        .and_then(|_| self.handle_stream(&mut stream));
                }
                Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                    std::thread::sleep(std::time::Duration::from_millis(20))
                }
                Err(_) => return Err("IPC_ACCEPT_FAILED".into()),
            }
        }
    }

    pub fn serve_once(&self, listener: &Listener) -> Result<(), String> {
        let mut stream = listener.accept().map_err(|error| error.to_string())?;
        self.authorize_stream(&stream)?;
        stream
            .set_nonblocking(true)
            .map_err(|_| "IPC_UNAVAILABLE")?;
        self.handle_stream(&mut stream)
    }

    fn authorize_stream(&self, stream: &interprocess::local_socket::Stream) -> Result<(), String> {
        self.check_client()?;
        let peer = stream.peer_creds().map_err(|_| "IPC_PEER_UNAVAILABLE")?;
        if self.client_pid.is_some() && peer.pid().map(|pid| pid as u32) != self.client_pid {
            return Err("IPC_PEER_NOT_ALLOWED".into());
        }
        Ok(())
    }

    fn handle_stream(&self, stream: &mut interprocess::local_socket::Stream) -> Result<(), String> {
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
        let body = read_frame_until(stream, deadline, || self.check_client())?;
        let request: HelperRequest =
            serde_json::from_slice(&body).map_err(|_| "IPC_SCHEMA_INVALID".to_string())?;
        self.check_client()?;
        let response: HelperResponse<NetworkStatus> = self.service.handle(request);
        self.check_client()?;
        let encoded = serde_json::to_vec(&response).map_err(|error| error.to_string())?;
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
        write_all_until(
            stream,
            &(encoded.len() as u32).to_be_bytes(),
            deadline,
            || self.check_client(),
        )?;
        write_all_until(stream, &encoded, deadline, || self.check_client())
    }
}

pub fn call(
    path: &std::path::Path,
    request: &HelperRequest,
) -> Result<HelperResponse<NetworkStatus>, String> {
    #[cfg(windows)]
    let expected = std::env::current_exe()
        .map_err(|_| "IPC_SERVER_IDENTITY_UNAVAILABLE")?
        .with_file_name("zhijie-helper.exe");
    call_inner(
        path,
        request,
        #[cfg(windows)]
        &expected,
    )
}

#[cfg(all(test, windows))]
pub(crate) fn call_with_server_image(
    path: &std::path::Path,
    request: &HelperRequest,
    expected: &std::path::Path,
) -> Result<HelperResponse<NetworkStatus>, String> {
    call_inner(path, request, expected)
}

fn call_inner(
    path: &std::path::Path,
    request: &HelperRequest,
    #[cfg(windows)] expected: &std::path::Path,
) -> Result<HelperResponse<NetworkStatus>, String> {
    let name = path
        .to_fs_name::<interprocess::local_socket::GenericFilePath>()
        .map_err(|_| "HELPER_UNAVAILABLE")?;
    let stream =
        interprocess::local_socket::Stream::connect(name).map_err(|_| "HELPER_UNAVAILABLE")?;
    call_connected(
        stream,
        request,
        #[cfg(windows)]
        expected,
    )
}

pub(crate) fn call_connected(
    mut stream: interprocess::local_socket::Stream,
    request: &HelperRequest,
    #[cfg(windows)] expected: &std::path::Path,
) -> Result<HelperResponse<NetworkStatus>, String> {
    #[cfg(windows)]
    let server = {
        let pid = stream
            .peer_creds()
            .map_err(|_| "IPC_SERVER_IDENTITY_UNAVAILABLE")?
            .pid()
            .ok_or("IPC_SERVER_IDENTITY_UNAVAILABLE")?;
        crate::windows_identity::ServerIdentity::verify(pid as u32, expected)?
    };
    stream
        .set_nonblocking(true)
        .map_err(|_| "HELPER_UNAVAILABLE")?;
    let encoded = serde_json::to_vec(request).map_err(|_| "IPC_SCHEMA_INVALID")?;
    if encoded.len() > MAX_IPC_MESSAGE_BYTES {
        return Err("IPC_MESSAGE_TOO_LARGE".into());
    }
    let check = || {
        #[cfg(windows)]
        {
            server.check_alive()
        }
        #[cfg(not(windows))]
        {
            Ok(())
        }
    };
    // 一次调用共用截止；部分进度不能重置预算，原服务进程退出即停止交换。
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
    write_all_until(
        &mut stream,
        &(encoded.len() as u32).to_be_bytes(),
        deadline,
        check,
    )?;
    write_all_until(&mut stream, &encoded, deadline, check)?;
    let body = read_frame_until(&mut stream, deadline, check)?;
    let response: HelperResponse<NetworkStatus> =
        serde_json::from_slice(&body).map_err(|_| "IPC_SCHEMA_INVALID")?;
    if response.protocol_version != 1 || response.request_id != request.request_id {
        return Err("IPC_RESPONSE_MISMATCH".into());
    }
    #[cfg(windows)]
    server.check_alive()?;
    Ok(response)
}

fn read_frame_until(
    stream: &mut impl Read,
    deadline: std::time::Instant,
    mut check: impl FnMut() -> Result<(), String>,
) -> Result<Vec<u8>, String> {
    let mut length = [0; 4];
    read_exact_until(stream, &mut length, deadline, &mut check)?;
    let length = u32::from_be_bytes(length) as usize;
    if length > MAX_IPC_MESSAGE_BYTES {
        return Err("IPC_MESSAGE_TOO_LARGE".into());
    }
    let mut body = vec![0; length];
    read_exact_until(stream, &mut body, deadline, check)?;
    Ok(body)
}

fn read_exact_until(
    stream: &mut impl Read,
    mut buffer: &mut [u8],
    deadline: std::time::Instant,
    mut check: impl FnMut() -> Result<(), String>,
) -> Result<(), String> {
    while !buffer.is_empty() {
        check()?;
        if std::time::Instant::now() >= deadline {
            return Err("IPC_TIMEOUT".into());
        }
        match stream.read(buffer) {
            Ok(0) => {
                // Windows PIPE_NOWAIT 在连接存活但尚无字节时可能返回零。
                if !cfg!(windows) {
                    return Err("IPC_CONNECTION_CLOSED".into());
                }
                if std::time::Instant::now() >= deadline {
                    return Err("IPC_TIMEOUT".into());
                }
                std::thread::sleep(std::time::Duration::from_millis(5));
            }
            Ok(count) => buffer = &mut buffer[count..],
            Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                if std::time::Instant::now() >= deadline {
                    return Err("IPC_TIMEOUT".into());
                }
                std::thread::sleep(std::time::Duration::from_millis(5));
            }
            Err(_) => return Err("IPC_READ_FAILED".into()),
        }
    }
    check()?;
    if std::time::Instant::now() >= deadline {
        return Err("IPC_TIMEOUT".into());
    }
    Ok(())
}

fn write_all_until(
    stream: &mut impl Write,
    mut buffer: &[u8],
    deadline: std::time::Instant,
    mut check: impl FnMut() -> Result<(), String>,
) -> Result<(), String> {
    while !buffer.is_empty() {
        check()?;
        if std::time::Instant::now() >= deadline {
            return Err("IPC_TIMEOUT".into());
        }
        match stream.write(buffer) {
            Ok(0) => return Err("IPC_CONNECTION_CLOSED".into()),
            Ok(count) => buffer = &buffer[count..],
            Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                if std::time::Instant::now() >= deadline {
                    return Err("IPC_TIMEOUT".into());
                }
                std::thread::sleep(std::time::Duration::from_millis(5));
            }
            Err(_) => return Err("IPC_WRITE_FAILED".into()),
        }
    }
    check()?;
    if std::time::Instant::now() >= deadline {
        return Err("IPC_TIMEOUT".into());
    }
    Ok(())
}

#[cfg(test)]
mod deadline_tests {
    use super::*;
    use std::time::{Duration, Instant};

    struct SlowProgress;

    impl Read for SlowProgress {
        fn read(&mut self, buffer: &mut [u8]) -> std::io::Result<usize> {
            std::thread::sleep(Duration::from_millis(20));
            buffer[0] = 1;
            Ok(1)
        }
    }

    impl Write for SlowProgress {
        fn write(&mut self, _: &[u8]) -> std::io::Result<usize> {
            std::thread::sleep(Duration::from_millis(20));
            Ok(1)
        }
        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }

    #[test]
    fn positive_partial_progress_cannot_extend_absolute_io_deadline() {
        let mut stream = SlowProgress;
        let mut buffer = [0; 100];
        let start = Instant::now();
        assert_eq!(
            read_exact_until(
                &mut stream,
                &mut buffer,
                start + Duration::from_millis(50),
                || Ok(())
            )
            .unwrap_err(),
            "IPC_TIMEOUT"
        );
        assert!(start.elapsed() < Duration::from_secs(1));
        let start = Instant::now();
        assert_eq!(
            write_all_until(
                &mut stream,
                &buffer,
                start + Duration::from_millis(50),
                || Ok(())
            )
            .unwrap_err(),
            "IPC_TIMEOUT"
        );
        assert!(start.elapsed() < Duration::from_secs(1));
        // 最后一个字节也不能使截止后完成的 IO 被误判为成功。
        assert!(read_exact_until(
            &mut stream,
            &mut [0],
            Instant::now() + Duration::from_millis(1),
            || Ok(())
        )
        .is_err());
        assert!(write_all_until(
            &mut stream,
            &[0],
            Instant::now() + Duration::from_millis(1),
            || Ok(())
        )
        .is_err());
    }

    #[test]
    fn bound_identity_failure_prevents_read_and_write() {
        let mut stream = std::io::Cursor::new(vec![0; 10]);
        let deadline = Instant::now() + Duration::from_secs(1);
        assert_eq!(
            read_exact_until(&mut stream, &mut [0], deadline, || Err(
                "IPC_CLIENT_EXITED".into()
            ))
            .unwrap_err(),
            "IPC_CLIENT_EXITED"
        );
        assert_eq!(stream.position(), 0);
        assert_eq!(
            write_all_until(&mut stream, &[1], deadline, || Err(
                "IPC_CLIENT_EXITED".into()
            ))
            .unwrap_err(),
            "IPC_CLIENT_EXITED"
        );
        assert_eq!(stream.position(), 0);
    }

    #[test]
    fn client_frame_uses_one_deadline_for_header_and_body() {
        // 响应头持续取得部分字节后，正文不能重新获得完整五秒预算。
        struct SlowFrame(std::io::Cursor<Vec<u8>>);
        impl Read for SlowFrame {
            fn read(&mut self, buffer: &mut [u8]) -> std::io::Result<usize> {
                std::thread::sleep(Duration::from_millis(20));
                self.0.read(&mut buffer[..1])
            }
        }
        let mut stream = SlowFrame(std::io::Cursor::new(vec![0, 0, 0, 4, 1, 2, 3, 4]));
        let start = Instant::now();
        let deadline = start + Duration::from_millis(100);
        assert_eq!(
            read_frame_until(&mut stream, deadline, || Ok(())).unwrap_err(),
            "IPC_TIMEOUT"
        );
        assert!(start.elapsed() < Duration::from_millis(500));
    }
}

#[cfg(test)]
pub fn bind(path: &std::path::Path) -> Result<Listener, String> {
    ListenerOptions::new()
        .name(
            path.to_fs_name::<interprocess::local_socket::GenericFilePath>()
                .map_err(|error| error.to_string())?,
        )
        .create_sync()
        .map_err(|error| error.to_string())
}
