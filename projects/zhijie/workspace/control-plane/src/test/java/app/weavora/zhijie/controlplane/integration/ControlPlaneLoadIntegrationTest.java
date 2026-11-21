package app.weavora.zhijie.controlplane.integration;

import static org.assertj.core.api.Assertions.assertThat;

import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.group.GroupService;
import app.weavora.zhijie.controlplane.identity.*;
import app.weavora.zhijie.controlplane.social.SocialService;
import app.weavora.zhijie.controlplane.sync.OutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import com.zaxxer.hikari.HikariDataSource;

class ControlPlaneLoadIntegrationTest {
    @Test void hundredVerifiedAccountsRunRealHttpAndSyncWithPresenceRecovery() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equals(System.getenv("ZHIJIE_TEST_CONTROL_LOAD")));
        runLoad(1, 90, 900);
    }

    @Test void repeatedReconnectsCatchUpSpecificOfflineMessagesDuringHundredAccountLoad() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equals(System.getenv("ZHIJIE_TEST_SYNC_CHURN")));
        runLoad(10, 90, 900);
    }

    @Test void hundredAccountsRotateCredentialsDuringLoad() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equals(System.getenv("ZHIJIE_TEST_LOAD_ROTATION")));
        runLoad(1, 90, 100);
    }

    @Test void hundredAccountsStayOnlineForOneHour() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equals(System.getenv("ZHIJIE_TEST_CONTROL_HOUR")));
        runLoad(1, 3600, 900);
    }

    private void runLoad(int reconnectRounds, int durationSeconds, int initialAccessSeconds) throws Exception {
        var workingDirectory = Path.of(System.getProperty("user.dir"));
        var workspace = Files.isRegularFile(workingDirectory.resolve("control-plane/pom.xml")) ? workingDirectory : workingDirectory.getParent();
        var directory = Files.createTempDirectory(Files.createDirectories(workspace.resolve("target")), "control-load-");
        try (var postgres = EmbeddedPostgres.start()) {
            var app = new SpringApplication(ControlPlaneApplication.class);
            try (var context = app.run("--server.address=127.0.0.1", "--server.port=0",
                    "--server.tomcat.accesslog.enabled=true", "--server.tomcat.accesslog.directory=" + directory,
                    "--server.tomcat.accesslog.rotate=false", "--server.tomcat.accesslog.prefix=http-transport",
                    "--server.tomcat.accesslog.pattern=%{begin:msec}t %{remote}p %s",
                    "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                    "--spring.datasource.username=postgres", "--spring.datasource.password=")) {
                var identities = context.getBean(IdentityRepository.class);
                var credentials = context.getBean(CredentialProtector.class);
                var social = context.getBean(SocialService.class);
                var groups = context.getBean(GroupService.class);
                var accounts = new ArrayList<Map<String, String>>();
                // 测试夹具直接创建已验证账号和随机会话；不调用外部邮件，不将注册/登录吞吐计入成绩。
                for (int index = 0; index < 100; index++) {
                    String user = UUID.randomUUID().toString();
                    String session = UUID.randomUUID().toString();
                    String secret = credentials.randomSecret();
                    String access = session + "." + secret;
                    String refreshSecret = credentials.randomSecret();
                    Instant now = Instant.now();
                    Instant accessExpires = now.plusSeconds(initialAccessSeconds);
                    identities.insertUser(new UserAccount(user, user + "@load.example.test", AccountStatus.ACTIVE, "unused-load-password", now));
                    identities.insertSession(new AuthSession(session, user, "load-" + index,
                            credentials.hashToken(refreshSecret), credentials.hashToken(secret),
                            now.plusSeconds(3600), accessExpires, null, 1L));
                    social.heartbeat(user);
                    accounts.add(Map.of("userId", user, "sessionId", session, "accessToken", access,
                            "refreshToken", session + "." + refreshSecret, "accessExpiresAt", accessExpires.toString()));
                }
                String owner = accounts.getFirst().get("userId");
                var group = groups.createGroup(owner, "百账号本机预验证", null, "");
                for (var account : accounts.subList(1, accounts.size())) {
                    String user = account.get("userId");
                    groups.acceptInvitation(user, groups.invite(owner, group.id(), user).id());
                }
                for (int index = 0; index < 100; index++) {
                    String from = accounts.get(index).get("userId");
                    String to = accounts.get((index + 1) % 100).get("userId");
                    social.accept(to, social.sendRequest(from, to).id());
                }
                var log = directory.resolve("adapter.log");
                var stopFile = directory.resolve("sampling.stop");
                var observation = directory.resolve("host-observation");
                String networkInterface = System.getenv("ZHIJIE_TEST_RESOURCE_INTERFACE");
                Process sampler = null;
                var process = new ProcessBuilder("node", "scripts/load-control-plane.mjs")
                        .directory(workspace.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
                try {
                    var json = context.getBean(ObjectMapper.class);
                    if (networkInterface != null && !networkInterface.isBlank()) {
                        sampler = new ProcessBuilder(System.getenv().getOrDefault("ZHIJIE_TEST_PYTHON", "python"),
                                "scripts/sample-capacity-resources.py", "--pid", Long.toString(ProcessHandle.current().pid()),
                                "--memory-tree-pid", Long.toString(PostgresSamplerTarget.rootPid(context.getBean(JdbcTemplate.class))),
                                "--interface", networkInterface, "--duration", Integer.toString(durationSeconds + 60), "--stop-file", stopFile.toString(),
                                "--output", observation.toString()).directory(workspace.toFile()).redirectErrorStream(true)
                                .redirectOutput(directory.resolve("sampler.log").toFile()).start();
                        long readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                        var samples = observation.resolve("samples.jsonl");
                        while (sampler.isAlive() && System.nanoTime() < readyDeadline && (!Files.exists(samples) || Files.size(samples) == 0)) Thread.sleep(50);
                        assertThat(sampler.isAlive() && Files.exists(samples) && Files.size(samples) > 0).as("host sampler readiness: %s", directory).isTrue();
                    }
                    int port = ((ServletWebServerApplicationContext)context).getWebServer().getPort();
                    var configuration = Map.of("baseUrl", "http://127.0.0.1:" + port, "groupId", group.id(),
                            "accounts", accounts, "cursor", context.getBean(OutboxRepository.class).latestSequence(),
                            "durationSeconds", durationSeconds, "reconnectRounds", reconnectRounds, "output", directory.toString());
                    try (var input = process.getOutputStream()) {
                        input.write(json.writeValueAsBytes(configuration)); input.write('\n');
                    }
                    var resources = directory.resolve("resources.jsonl");
                    var os = (com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
                    var pool = context.getBean(HikariDataSource.class).getHikariPoolMXBean();
                    long previousCpu = os.getProcessCpuTime();
                    long previousTime = System.nanoTime();
                    long deadline = previousTime + TimeUnit.SECONDS.toNanos(durationSeconds + 40);
                    int resourceSamples = 0;
                    try (var writer = Files.newBufferedWriter(resources)) {
                        while (process.isAlive() && System.nanoTime() < deadline) {
                            Thread.sleep(1000);
                            long now = System.nanoTime();
                            long cpu = os.getProcessCpuTime();
                            var sample = Map.of("at", Instant.now().toString(),
                                    "jvmCpuPercent", 100.0 * (cpu - previousCpu) / (now - previousTime),
                                    "jvmHeapUsedBytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),
                                    "jvmThreads", ManagementFactory.getThreadMXBean().getThreadCount(),
                                    "databaseActive", pool.getActiveConnections(), "databaseIdle", pool.getIdleConnections(),
                                    "scope", "test-jvm-including-http-service-excluding-postgres-and-node");
                            writer.write(json.writeValueAsString(sample)); writer.newLine(); writer.flush();
                            previousCpu = cpu; previousTime = now; resourceSamples++;
                        }
                    }
                    assertThat(process.isAlive()).as("Load process deadline; inspect %s", log).isFalse();
                    if (sampler != null) {
                        Files.createFile(stopFile);
                        assertThat(sampler.waitFor(10, TimeUnit.SECONDS)).as("host sampling deadline").isTrue();
                        assertThat(sampler.exitValue()).as("host sampler: %s", directory).isZero();
                        var measured = json.readTree(Files.readString(observation.resolve("summary.json")));
                        assertThat(measured.path("endCondition").asText()).isEqualTo("stop-file");
                        assertThat(measured.path("measuredDurationSeconds").asDouble()).isGreaterThanOrEqualTo(durationSeconds);
                        assertThat(measured.required("sampledPeaks").required("memoryTreePeakMiB").doubleValue()).isPositive();
                    }
                    assertThat(resourceSamples).isGreaterThan((int)(durationSeconds * 0.9));
                    assertThat(process.exitValue()).as("Real control-plane load; inspect %s", log).isZero();
                    var summary = json.readTree(Files.readString(directory.resolve("summary.json")));
                    assertThat(summary.path("accounts").asInt()).isEqualTo(100);
                    assertThat(summary.path("durationSeconds").asDouble()).isGreaterThanOrEqualTo(durationSeconds);
                    int minimumRotations = durationSeconds == 3600 ? 4 : initialAccessSeconds == 100 ? 1 : 0;
                    assertThat(summary.path("rotationsPerAccountMin").intValue()).isGreaterThanOrEqualTo(minimumRotations);
                    var auditLog = directory.resolve("http-activity-audit.json");
                    var audit = new ProcessBuilder(System.getenv().getOrDefault("ZHIJIE_TEST_PYTHON", "python"), "-B",
                            "scripts/audit-control-load.py", directory.toString(), "--seconds", Integer.toString(durationSeconds))
                            .directory(workspace.toFile()).redirectOutput(auditLog.toFile())
                            .redirectError(directory.resolve("http-activity-audit.stderr.log").toFile()).start();
                    try {
                        assertThat(audit.waitFor(45, TimeUnit.SECONDS)).as("HTTP activity audit deadline").isTrue();
                        assertThat(audit.exitValue()).as("Per-account activity audit: %s", directory).isZero();
                    } finally {
                        if (audit.isAlive()) audit.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
                    }
                    assertThat(json.readTree(auditLog.toFile()).required("httpOperations").asLong())
                            .isEqualTo(summary.required("requests").asLong());
                    for (var account : accounts) {
                        var persisted = identities.findSessionById(account.get("sessionId")).orElseThrow();
                        assertThat(persisted.generation()).isGreaterThanOrEqualTo(1L + minimumRotations);
                        assertThat(persisted.revokedAt()).isNull();
                    }
                    assertThat(summary.path("websocketPeak").asInt()).isEqualTo(100);
                    assertThat(summary.path("requests").asInt()).isGreaterThan(3000);
                    assertThat(summary.path("failures").asInt()).isZero();
                    assertThat(summary.path("syncFailures").asInt()).isZero();
                    var traffic = Files.readAllLines(directory.resolve("body-traffic.jsonl"));
                    assertThat(traffic).hasSize((int)Math.ceil(summary.path("durationSeconds").asDouble()));
                    long requestBytes = 0, responseBytes = 0, syncBytes = 0;
                    for (int second = 0; second < traffic.size(); second++) {
                        var sample = json.readTree(traffic.get(second));
                        assertThat(sample.size()).isEqualTo(4);
                        assertThat(sample.required("second").intValue()).isEqualTo(second);
                        requestBytes += sample.required("requestBodyBytes").longValue();
                        responseBytes += sample.required("responseBodyBytes").longValue();
                        syncBytes += sample.required("syncBodyBytes").longValue();
                    }
                    assertThat(requestBytes).isPositive().isEqualTo(summary.required("requestBodyBytes").longValue());
                    assertThat(responseBytes).isPositive().isEqualTo(summary.required("responseBodyBytes").longValue());
                    assertThat(syncBytes).isPositive().isEqualTo(summary.required("syncBodyBytesDuringWorkload").longValue());
                    assertThat(summary.path("reconnectRounds").asInt()).isEqualTo(reconnectRounds);
                    assertThat(summary.path("reconnects").asInt()).isEqualTo(10 * reconnectRounds);
                    assertThat(summary.path("catchupVerified").asInt()).isEqualTo(10 * reconnectRounds);
                    assertThat(summary.path("syncEventsPerAccountMin").asInt()).isGreaterThanOrEqualTo(100);
                    assertThat(summary.path("observedOffline").asInt()).isPositive();
                    assertThat(summary.path("observedOnline").asInt()).isPositive();
                    // 原始记录不应包含输入会话凭据或测试私有内容。
                    String raw = Files.readString(directory.resolve("operations.jsonl"));
                    for (var account : accounts) assertThat(raw.contains(account.get("sessionId") + ".")).isFalse();
                    assertThat(raw).doesNotContain("access_token", "Authorization", "load-password");
                    assertThat(summary.path("acceptance").asText()).isEqualTo(durationSeconds == 3600
                            ? "unverified_target_server" : "unverified_target_server_and_3600_seconds");
                } finally {
                    if (sampler != null && sampler.isAlive()) sampler.destroyForcibly();
                    if (process.isAlive()) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); }
                }
            }
            var safeRecord = java.util.regex.Pattern.compile("[0-9]{13} [0-9]{1,5} [1-5][0-9]{2}");
            try (var records = Files.lines(directory.resolve("http-transport.log"))) {
                assertThat(records.peek(line -> {
                    if (!safeRecord.matcher(line).matches()) throw new AssertionError("Unexpected transport log format");
                }).filter(line -> line.endsWith(" 200")).count()).isGreaterThan(3000);
            }
        }
    }
}
