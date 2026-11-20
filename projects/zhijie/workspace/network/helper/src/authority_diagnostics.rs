use std::time::Duration;

pub(super) fn request_failure(
    member: usize,
    failure: &reqwest::Error,
    elapsed: Duration,
) -> String {
    failure_line(member, "send", error_category(failure), 0, elapsed)
}

pub(super) fn body_failure(member: usize, failure: &reqwest::Error, elapsed: Duration) -> String {
    failure_line(member, "body", error_category(failure), 0, elapsed)
}

pub(super) fn http_failure(
    member: usize,
    status: reqwest::StatusCode,
    elapsed: Duration,
) -> String {
    failure_line(member, "http", "HTTP_STATUS", status.as_u16(), elapsed)
}

fn error_category(failure: &reqwest::Error) -> &'static str {
    if failure.is_timeout() {
        "TIMEOUT"
    } else if failure.is_connect() {
        "CONNECT"
    } else if failure.is_decode() {
        "DECODE"
    } else if failure.is_body() {
        "BODY"
    } else if failure.is_request() {
        "REQUEST"
    } else {
        "TRANSPORT"
    }
}

fn failure_line(
    member: usize,
    operation: &'static str,
    category: &'static str,
    status: u16,
    elapsed: Duration,
) -> String {
    format!(
        "REAL_NETWORK_AUTHORITY_FAILURE member={member} operation={operation} category={category} status={status} elapsed_ms={}",
        elapsed.as_millis()
    )
}

#[tokio::test]
async fn failure_categories_do_not_include_request_or_response_secrets() {
    use std::io::{Read, Write};

    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let url = format!(
        "http://{}/?ticket=secret-ticket",
        listener.local_addr().unwrap()
    );
    let worker = std::thread::spawn(move || {
        for index in 0..2 {
            let (mut socket, _) = listener.accept().unwrap();
            socket
                .set_read_timeout(Some(Duration::from_secs(1)))
                .unwrap();
            let mut buffer = [0; 2048];
            let _ = socket.read(&mut buffer).unwrap();
            if index == 0 {
                let body = "secret-response-not-json";
                write!(
                    socket,
                    "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                    body.len()
                )
                .unwrap();
            } else {
                std::thread::sleep(Duration::from_millis(300));
            }
        }
    });
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(2))
        .build()
        .unwrap();
    let failure = client
        .get(&url)
        .send()
        .await
        .unwrap()
        .json::<serde_json::Value>()
        .await
        .unwrap_err();
    let line = body_failure(3, &failure, Duration::from_millis(12));
    assert_eq!(line, "REAL_NETWORK_AUTHORITY_FAILURE member=3 operation=body category=DECODE status=0 elapsed_ms=12");
    let failure = client
        .get(&url)
        .timeout(Duration::from_millis(150))
        .send()
        .await
        .unwrap_err();
    let line = request_failure(4, &failure, Duration::from_millis(150));
    assert_eq!(line, "REAL_NETWORK_AUTHORITY_FAILURE member=4 operation=send category=TIMEOUT status=0 elapsed_ms=150");
    let line = http_failure(
        5,
        reqwest::StatusCode::TOO_MANY_REQUESTS,
        Duration::from_millis(1),
    );
    assert_eq!(line, "REAL_NETWORK_AUTHORITY_FAILURE member=5 operation=http category=HTTP_STATUS status=429 elapsed_ms=1");
    let line = http_failure(
        6,
        reqwest::StatusCode::SERVICE_UNAVAILABLE,
        Duration::from_millis(2),
    );
    assert_eq!(line, "REAL_NETWORK_AUTHORITY_FAILURE member=6 operation=http category=HTTP_STATUS status=503 elapsed_ms=2");
    worker.join().unwrap();
}
