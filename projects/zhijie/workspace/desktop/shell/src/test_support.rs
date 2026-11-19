use std::io::{Read, Write};
use std::net::TcpListener;

// 真实 TCP/HTTP，按顺序核对请求路径和 Bearer；错误输出不包含凭据。
pub(crate) fn server(
    steps: Vec<(&'static str, Option<&'static str>, u16, serde_json::Value)>,
) -> (String, std::thread::JoinHandle<()>) {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    listener.set_nonblocking(true).unwrap();
    let base = format!("http://{}", listener.local_addr().unwrap());
    let worker = std::thread::spawn(move || {
        for (path, bearer, status, body) in steps {
            let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
            let mut stream = loop {
                match listener.accept() {
                    Ok((stream, _)) => break stream,
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                        assert!(
                            std::time::Instant::now() < deadline,
                            "HTTP fixture accept deadline"
                        );
                        std::thread::sleep(std::time::Duration::from_millis(5));
                    }
                    Err(_) => panic!("HTTP fixture accept failed"),
                }
            };
            // Windows 接收端可能继承监听器的非阻塞模式；读写使用下面的有限超时。
            stream.set_nonblocking(false).unwrap();
            stream
                .set_read_timeout(Some(std::time::Duration::from_secs(5)))
                .unwrap();
            stream
                .set_write_timeout(Some(std::time::Duration::from_secs(5)))
                .unwrap();
            let mut bytes = Vec::new();
            let header_end = loop {
                let mut chunk = [0; 1024];
                let count = stream.read(&mut chunk).unwrap();
                assert!(count > 0 && bytes.len() < 16384, "HTTP fixture headers");
                bytes.extend_from_slice(&chunk[..count]);
                if let Some(index) = bytes.windows(4).position(|part| part == b"\r\n\r\n") {
                    break index + 4;
                }
            };
            let headers = String::from_utf8(bytes[..header_end].to_vec()).unwrap();
            assert!(
                headers.starts_with(&format!("POST {path} HTTP/1.1\r\n")),
                "HTTP fixture path"
            );
            if let Some(bearer) = bearer {
                assert!(
                    headers
                        .to_ascii_lowercase()
                        .contains(&format!("authorization: bearer {bearer}\r\n")),
                    "HTTP fixture credential binding"
                );
            }
            let length = headers
                .lines()
                .find_map(|line| {
                    line.to_ascii_lowercase()
                        .strip_prefix("content-length: ")
                        .and_then(|v| v.parse::<usize>().ok())
                })
                .unwrap_or(0);
            while bytes.len() < header_end + length {
                let mut chunk = [0; 1024];
                let count = stream.read(&mut chunk).unwrap();
                assert!(count > 0, "HTTP fixture body");
                bytes.extend_from_slice(&chunk[..count]);
            }
            let text = body.to_string();
            write!(stream, "HTTP/1.1 {status} Response\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{text}", text.len()).unwrap();
        }
    });
    (base, worker)
}
