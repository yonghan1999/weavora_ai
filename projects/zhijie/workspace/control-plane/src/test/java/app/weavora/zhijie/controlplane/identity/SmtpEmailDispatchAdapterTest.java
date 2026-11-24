package app.weavora.zhijie.controlplane.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.*;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class SmtpEmailDispatchAdapterTest {
    @Test
    void registrationVerificationAndPasswordResetDeliverThroughRealSmtp() throws Exception {
        try (SmtpInbox inbox = new SmtpInbox(); EmbeddedPostgres postgres = EmbeddedPostgres.start();
             var context = start(postgres, inbox.port(), true); var client = HttpClient.newHttpClient()) {
            assertThat(context.getBeansOfType(org.springframework.boot.actuate.mail.MailHealthIndicator.class)).hasSize(1);
            var api = new LocalApi(context, client, inbox);
            assertThat(api.get("/actuator/health", null, 200).path("status").asText()).isEqualTo("UP");
            String email = "smtp-recipient@example.test";
            String original = "original-password-2026";
            String replacement = "replacement-password-2026";
            var login = Map.of("email", email, "password", original, "deviceId", "smtp-test");
            var registration = api.post("register", Map.of("email", email, "password", original), 202);
            assertThat(registration.required("data").size()).isEqualTo(1);
            assertThat(registration.at("/data/verificationRequired").asBoolean()).isTrue();
            MimeMessage verification = inbox.receive();
            assertThat(inbox.phases).contains("EHLO", "DATA");
            assertThat(verification.getSubject()).isEqualTo("织界：验证邮箱");
            assertThat(verification.getAllRecipients()[0].toString()).isEqualTo(email);
            assertThat(api.post("login", login, 401).at("/error/code").asText()).isEqualTo("AUTH_REQUIRED");
            String verificationCode = code(verification);
            var verify = Map.of("email", email, "code", verificationCode);
            assertThat(api.post("verify-email", verify, 200).at("/data/verified").asBoolean()).isTrue();
            assertThat(api.post("verify-email", verify, 422).at("/error/code").asText()).isEqualTo("CHALLENGE_INVALID");
            var oldTokens = api.post("login", login, 200).required("data");
            api.get("/api/v1/groups", oldTokens.required("accessToken").asText(), 200);
            var accepted = api.post("password/forgot", Map.of("email", email), 202);
            assertThat(accepted.at("/data/accepted").asBoolean()).isTrue();
            MimeMessage reset = inbox.receive();
            assertThat(reset.getSubject()).isEqualTo("织界：重置密码");
            assertThat(reset.getAllRecipients()[0].toString()).isEqualTo(email);
            var resetBody = Map.of("email", email, "code", code(reset), "newPassword", replacement);
            assertThat(api.post("password/reset", resetBody, 200).at("/data/reset").asBoolean()).isTrue();
            assertThat(api.post("password/reset", resetBody, 422).at("/error/code").asText()).isEqualTo("CHALLENGE_INVALID");
            api.post("login", login, 401);
            api.get("/api/v1/groups", oldTokens.required("accessToken").asText(), 401);
            api.post("refresh", Map.of("refreshToken", oldTokens.required("refreshToken").asText()), 401);
            var renewed = api.post("login", Map.of("email", email, "password", replacement, "deviceId", "smtp-test"), 200).required("data");
            assertThat(renewed.required("userId")).isEqualTo(oldTokens.required("userId"));
            String access = renewed.required("accessToken").asText();
            api.get("/api/v1/groups", access, 200);
            assertThat(api.post("logout", Map.of("accessToken", access), 200).at("/data/loggedOut").asBoolean()).isTrue();
            api.get("/api/v1/groups", access, 401);
            assertThat(api.post("password/forgot", Map.of("email", "unknown@example.test"), 202)).isEqualTo(accepted);
            assertThat(inbox.messages.poll(300, TimeUnit.MILLISECONDS)).isNull();
        }
    }

    @Test
    void smtpFailureRollsBackRegistrationAndDoesNotExposeTransportDetails() throws Exception {
        try (SmtpInbox inbox = new SmtpInbox(); EmbeddedPostgres postgres = EmbeddedPostgres.start();
             var context = start(postgres, inbox.port(), true)) {
            inbox.rejectDelivery = true;
            assertThatThrownBy(() -> context.getBean(IdentityService.class).register(
                    "rejected@example.test", "rejected-password-2026"))
                    .isInstanceOfSatisfying(ApiException.class, error -> {
                        assertThat(error.code()).isEqualTo("EMAIL_DELIVERY_UNAVAILABLE");
                        assertThat(error.retryable()).isTrue();
                        assertThat(error.getCause()).isNull();
                        assertThat(error.getMessage()).doesNotContain("550", "rejected@example.test", "password");
                    });
            assertThat(inbox.phases).contains("REJECTED");
            assertThat(context.getBean(IdentityRepository.class).findUserByEmail("rejected@example.test")).isEmpty();
        }
    }

    @Test
    void disconnectedClientDoesNotDisableTheTestInbox() throws Exception {
        try (var inbox = new SmtpInbox()) {
            try (var probe = new Socket(InetAddress.getLoopbackAddress(), inbox.port())) {
                probe.setSoTimeout(3000);
                assertThat(new BufferedReader(new InputStreamReader(probe.getInputStream(), StandardCharsets.US_ASCII)).readLine()).startsWith("220 ");
                // 模拟已收到欢迎消息、但未发送 QUIT 就断开的客户端。
                probe.setSoLinger(true, 0);
            }
            try (var next = new Socket(InetAddress.getLoopbackAddress(), inbox.port())) {
                next.setSoTimeout(3000);
                assertThat(new BufferedReader(new InputStreamReader(next.getInputStream(), StandardCharsets.US_ASCII)).readLine()).startsWith("220 ");
            }
        }
    }

    @Test
    void missingConfigurationRemainsUnavailableAndHeaderInjectionIsRejected() {
        assertThat(new UnavailableEmailDispatchAdapter().isAvailable()).isFalse();
        assertThatThrownBy(() -> new SmtpEmailDispatchAdapter(new JavaMailSenderImpl(), "a@example.test\r\nBcc: victim"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void disabledMailKeepsHealthUpAndCannotRegisterAnAccount() throws Exception {
        try (var inbox = new SmtpInbox(); var postgres = EmbeddedPostgres.start();
             var context = start(postgres, inbox.port(), false); var client = HttpClient.newHttpClient()) {
            var api = new LocalApi(context, client, inbox);
            assertThat(api.get("/actuator/health", null, 200).path("status").asText()).isEqualTo("UP");
            String email = "disabled@example.test";
            assertThat(api.post("register", Map.of("email", email, "password", "disabled-password-test"), 503).at("/error/code").asText()).isEqualTo("EMAIL_DELIVERY_UNAVAILABLE");
            assertThat(api.post("password/forgot", Map.of("email", email), 503).at("/error/code").asText()).isEqualTo("EMAIL_DELIVERY_UNAVAILABLE");
            assertThat(context.getBean(IdentityRepository.class).findUserByEmail(email)).isEmpty();
            assertThat(inbox.phases).isEmpty();
        }
    }

    private record LocalApi(ServletWebServerApplicationContext context, HttpClient client, SmtpInbox inbox) {
        private HttpRequest.Builder request(String path) {
            return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + context.getWebServer().getPort() + path)).timeout(Duration.ofSeconds(10));
        }

        JsonNode post(String action, Map<String, String> body, int status) throws Exception {
            String payload = context.getBean(ObjectMapper.class).writeValueAsString(body);
            return send(request("/api/v1/auth/" + action).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build(), status);
        }

        JsonNode get(String path, String access, int status) throws Exception {
            var builder = request(path);
            if (access != null) builder.header("Authorization", "Bearer " + access);
            return send(builder.GET().build(), status);
        }

        private JsonNode send(HttpRequest request, int status) throws Exception {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("%s %s; %s", request.method(), request.uri().getPath(), inbox.diagnostics()).isEqualTo(status);
            return context.getBean(ObjectMapper.class).readTree(response.body());
        }
    }

    private ServletWebServerApplicationContext start(EmbeddedPostgres postgres, int smtpPort, boolean enabled) {
        SpringApplication application = new SpringApplication(ControlPlaneApplication.class);
        return (ServletWebServerApplicationContext) application.run("--server.address=127.0.0.1", "--server.port=0",
                "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=",
                "--zhijie.scheduling.enabled=false", "--zhijie.mail.enabled=" + enabled,
                "--zhijie.mail.from=zhijie@example.test", "--spring.mail.host=127.0.0.1",
                "--spring.mail.port=" + smtpPort, "--spring.mail.properties.mail.smtp.auth=false",
                // 回环测试固定 EHLO 身份，避免宿主机反向 DNS 超过受控接收端的命令期限。
                "--spring.mail.properties.mail.smtp.localhost=[127.0.0.1]",
                "--spring.mail.properties.mail.smtp.starttls.enable=false",
                "--spring.mail.properties.mail.smtp.starttls.required=false");
    }

    private String code(MimeMessage message) throws Exception {
        var matcher = Pattern.compile("验证码是：(\\d{6})").matcher((String) message.getContent());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    /** 受控 SMTP 接收端：使用真实 TCP/SMTP 协议接收 MIME 邮件，不替换业务投递接口。 */
    private static class SmtpInbox implements AutoCloseable {
        private final ServerSocket server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private final BlockingQueue<byte[]> messages = new LinkedBlockingQueue<>();
        private final BlockingQueue<String> phases = new LinkedBlockingQueue<>();
        private final Future<?> receiver;
        private volatile boolean rejectDelivery;

        SmtpInbox() throws IOException {
            receiver = worker.submit(() -> {
                while (!server.isClosed()) {
                    Socket accepted;
                    try { accepted = server.accept(); }
                    catch (IOException error) {
                        if (server.isClosed()) return;
                        throw new UncheckedIOException(error);
                    }
                    try (accepted) { phases.add("ACCEPTED"); handle(accepted); }
                    catch (IOException error) { phases.add(error.getClass().getSimpleName()); }
                }
            });
        }

        int port() { return server.getLocalPort(); }

        String diagnostics() { return "receiverDone=" + receiver.isDone() + ", phases=" + phases; }

        MimeMessage receive() throws Exception {
            byte[] bytes = messages.poll(5, TimeUnit.SECONDS);
            assertThat(bytes).as("SMTP 已接收实际邮件").isNotNull();
            return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(bytes));
        }

        private void handle(Socket socket) throws IOException {
            socket.setSoTimeout(5000);
            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            var writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII), true);
            writer.print("220 localhost test SMTP\r\n"); writer.flush();
            String line;
            while ((line = reader.readLine()) != null) {
                // 只保存协议阶段，不记录邮箱、正文、验证码或认证内容。
                phases.add(line.startsWith("EHLO") ? "EHLO" : line.startsWith("HELO") ? "HELO" : line.startsWith("MAIL") ? "MAIL" : line.startsWith("RCPT") ? "RCPT" : line.startsWith("DATA") ? "DATA" : line.startsWith("QUIT") ? "QUIT" : "OTHER");
                if (line.startsWith("DATA")) {
                    if (rejectDelivery) { phases.add("REJECTED"); writer.print("550 rejected\r\n"); writer.flush(); continue; }
                    writer.print("354 send message\r\n"); writer.flush();
                    var content = new StringBuilder();
                    while (!".".equals(line = reader.readLine())) {
                        if (line == null) throw new EOFException();
                        content.append(line.startsWith("..") ? line.substring(1) : line).append("\r\n");
                    }
                    messages.add(content.toString().getBytes(StandardCharsets.US_ASCII));
                    writer.print("250 accepted\r\n");
                } else if (line.startsWith("QUIT")) {
                    writer.print("221 bye\r\n"); writer.flush(); break;
                } else { writer.print("250 localhost\r\n"); }
                writer.flush();
            }
        }

        public void close() throws Exception { server.close(); worker.shutdownNow(); worker.awaitTermination(6, TimeUnit.SECONDS); }
    }
}
