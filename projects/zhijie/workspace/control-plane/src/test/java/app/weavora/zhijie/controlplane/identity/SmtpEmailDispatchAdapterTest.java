package app.weavora.zhijie.controlplane.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.common.ApiException;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class SmtpEmailDispatchAdapterTest {
    @Test
    void registrationVerificationAndPasswordResetDeliverThroughRealSmtp() throws Exception {
        try (SmtpInbox inbox = new SmtpInbox(); EmbeddedPostgres postgres = EmbeddedPostgres.start();
             ConfigurableApplicationContext context = start(postgres, inbox.port())) {
            assertThat(context.getBeansOfType(org.springframework.boot.actuate.mail.MailHealthIndicator.class)).hasSize(1);
            IdentityService identity = context.getBean(IdentityService.class);
            String email = "smtp-recipient@example.test";
            String original = "original-password-2026";
            String replacement = "replacement-password-2026";
            try {
                identity.register(email, original);
            } catch (ApiException error) {
                throw new AssertionError("受控 SMTP 收件失败：" + inbox.diagnostics(), error);
            }
            MimeMessage verification = inbox.receive();
            assertThat(inbox.phases).contains("EHLO", "DATA");
            assertThat(verification.getSubject()).isEqualTo("织界：验证邮箱");
            assertThat(verification.getAllRecipients()[0].toString()).isEqualTo(email);
            assertThatThrownBy(() -> identity.login(email, original, "smtp-test")).isInstanceOf(ApiException.class);
            String verificationCode = code(verification);
            identity.verifyEmail(email, verificationCode);
            assertThatThrownBy(() -> identity.verifyEmail(email, verificationCode)).isInstanceOf(ApiException.class);
            AuthTokens oldTokens = identity.login(email, original, "smtp-test");
            identity.forgotPassword(email);
            MimeMessage reset = inbox.receive();
            assertThat(reset.getSubject()).isEqualTo("织界：重置密码");
            identity.resetPassword(email, code(reset), replacement);
            assertThatThrownBy(() -> identity.login(email, original, "smtp-test")).isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> identity.authenticate("Bearer " + oldTokens.accessToken())).isInstanceOf(ApiException.class);
            assertThat(identity.login(email, replacement, "smtp-test").userId()).isEqualTo(oldTokens.userId());
            identity.forgotPassword("unknown@example.test");
            assertThat(inbox.messages.poll(300, TimeUnit.MILLISECONDS)).isNull();
        }
    }

    @Test
    void smtpFailureRollsBackRegistrationAndDoesNotExposeTransportDetails() throws Exception {
        try (SmtpInbox inbox = new SmtpInbox(); EmbeddedPostgres postgres = EmbeddedPostgres.start();
             ConfigurableApplicationContext context = start(postgres, inbox.port())) {
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
    void missingConfigurationRemainsUnavailableAndHeaderInjectionIsRejected() {
        assertThat(new UnavailableEmailDispatchAdapter().isAvailable()).isFalse();
        assertThatThrownBy(() -> new SmtpEmailDispatchAdapter(new JavaMailSenderImpl(), "a@example.test\r\nBcc: victim"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ConfigurableApplicationContext start(EmbeddedPostgres postgres, int smtpPort) {
        SpringApplication application = new SpringApplication(ControlPlaneApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        return application.run("--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=",
                "--zhijie.scheduling.enabled=false", "--zhijie.mail.enabled=true",
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
                    try (Socket socket = server.accept()) { phases.add("ACCEPTED"); handle(socket); }
                    catch (IOException error) { if (!server.isClosed()) { phases.add(error.getClass().getSimpleName()); throw new UncheckedIOException(error); } }
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
                    while ((line = reader.readLine()) != null && !line.equals(".")) {
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
