package app.weavora.zhijie.controlplane.revocation;

import static org.assertj.core.api.Assertions.assertThat;

import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.sync.ExternalDeliveryChannel;
import app.weavora.zhijie.controlplane.sync.OutboxEvent;
import app.weavora.zhijie.controlplane.sync.OutboxRepository;
import app.weavora.zhijie.controlplane.sync.OutboxService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.Ordered;

class ExternalRevocationChannelIntegrationTest {
    @Test void blockedVoiceDeliveryDoesNotDelayNetworkRevocationOrItsCommit() throws Exception {
        verifyIsolation("voice.revoked", "network.revoked");
    }

    @Test void blockedNetworkDeliveryDoesNotDelayVoiceRevocationOrItsCommit() throws Exception {
        verifyIsolation("network.revoked", "voice.revoked");
    }

    @Test void channelFilteringPrecedesBatchLimitAndPreservesRetryEligibility() throws Exception {
        try (var postgres = EmbeddedPostgres.start(); var context = new SpringApplication(ControlPlaneApplication.class).run(
                "--server.address=127.0.0.1", "--server.port=0", "--zhijie.scheduling.enabled=false",
                "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=")) {
            var events = context.getBean(OutboxService.class);
            var repository = context.getBean(OutboxRepository.class);
            List<String> voiceEvents = new ArrayList<>();
            for (int index = 0; index < 110; index++) {
                String eventId = events.record(null, "channel-test", "voice-" + index, "voice.revoked",
                        "{}", 1L, 1L).eventId();
                repository.markSynchronized(eventId);
                voiceEvents.add(eventId);
            }
            String network = events.record(null, "channel-test", "network", "network.revoked", "{}", 1L, 1L).eventId();
            String other = events.record(null, "channel-test", "other", "test.ready", "{}", null, null).eventId();
            events.record(null, "channel-test", "unsynchronized", "network.revoked", "{}", 1L, 1L);
            repository.markSynchronized(network);
            repository.markSynchronized(other);
            Instant now = Instant.now().plusSeconds(1);
            assertThat(repository.listDueExternal(100, now)).extracting(OutboxEvent::eventId).containsExactlyElementsOf(voiceEvents.subList(0, 100));
            assertThat(repository.listDueExternal(100, now, ExternalDeliveryChannel.VOICE)).extracting(OutboxEvent::eventId)
                    .containsExactlyElementsOf(voiceEvents.subList(0, 100));
            assertThat(repository.listDueExternal(100, now, ExternalDeliveryChannel.NETWORK)).extracting(OutboxEvent::eventId).containsExactly(network);
            assertThat(repository.listDueExternal(100, now, ExternalDeliveryChannel.OTHER)).extracting(OutboxEvent::eventId).containsExactly(other);
            repository.recordExternalFailure(network, now);
            assertThat(repository.listDueExternal(100, now.plusSeconds(2).minusNanos(1000), ExternalDeliveryChannel.NETWORK)).isEmpty();
            assertThat(repository.listDueExternal(100, now.plusSeconds(2), ExternalDeliveryChannel.NETWORK)).extracting(OutboxEvent::eventId).containsExactly(network);
            repository.markDelivered(network);
            assertThat(repository.listDueExternal(100, now.plusSeconds(3), ExternalDeliveryChannel.NETWORK)).isEmpty();
        }
    }

    private void verifyIsolation(String blockedType, String readyType) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var delivered = new CountDownLatch(1);
        RevocationTarget blocked = new ProbeTarget(blockedType, () -> {
                entered.countDown();
                try { release.await(15, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                throw new IllegalStateException("CONTROLLED_EXTERNAL_FAILURE");
        });
        RevocationTarget ready = new ProbeTarget(readyType, delivered::countDown);
        var json = new ObjectMapper();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] response = exchange.getRequestURI().getPath().contains("revocations")
                    ? json.writeValueAsBytes(java.util.Map.of("version", 1, "eventId", json.readTree(exchange.getRequestBody()).required("eventId").asText(), "status", "applied"))
                    : "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        var application = new SpringApplication(ControlPlaneApplication.class);
        application.addInitializers(context -> {
            context.getBeanFactory().registerSingleton("blockedChannel", blocked);
            context.getBeanFactory().registerSingleton("readyChannel", ready);
        });
        try (var postgres = EmbeddedPostgres.start(); var context = application.run(
                "--server.address=127.0.0.1", "--server.port=0",
                "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=",
                "--zhijie.voice.enabled=true", "--zhijie.voice.api-key=channel-test",
                "--zhijie.voice.url=wss://voice.example.test",
                "--zhijie.voice.api-secret=" + UUID.randomUUID(),
                "--zhijie.voice.admin-url=http://127.0.0.1:" + server.getAddress().getPort(),
                "--zhijie.relay.revocation-enabled=true", "--zhijie.relay.revocation-key=" + UUID.randomUUID(),
                "--zhijie.relay.admin-url=http://127.0.0.1:" + server.getAddress().getPort())) {
            try {
                var events = context.getBean(OutboxService.class);
                var repository = context.getBean(OutboxRepository.class);
                var blockedEvent = events.record(null, "channel-test", "blocked", blockedType,
                        "{\"user_id\":\"blocked\",\"member_generation\":1}", 1L, 1L);
                repository.markSynchronized(blockedEvent.eventId());
                assertThat(entered.await(5, TimeUnit.SECONDS)).as("Blocked channel started").isTrue();
                var readyEvent = events.record(null, "channel-test", "ready", readyType,
                        "{\"user_id\":\"ready\",\"member_generation\":1}", 1L, 1L);
                repository.markSynchronized(readyEvent.eventId());
                assertThat(delivered.await(3, TimeUnit.SECONDS)).as("Other channel delivered while first remains blocked").isTrue();
                var sql = context.getBean(JdbcTemplate.class);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                boolean committed = false;
                while (System.nanoTime() < deadline && !committed) {
                    committed = sql.queryForObject("SELECT delivered_at IS NOT NULL FROM outbox_events WHERE event_id=?", Boolean.class, readyEvent.eventId());
                    if (!committed) Thread.sleep(10);
                }
                assertThat(committed).as("Other channel committed independently").isTrue();
                assertThat(sql.queryForObject("SELECT delivered_at IS NULL FROM outbox_events WHERE event_id=?", Boolean.class, blockedEvent.eventId())).isTrue();
                assertThat(release.getCount()).isEqualTo(1);
            } finally { release.countDown(); }
        } finally { server.stop(0); }
    }

    private record ProbeTarget(String eventType, Runnable operation) implements RevocationTarget, Ordered {
        public boolean supports(String type) { return eventType.equals(type); }
        public void deliver(app.weavora.zhijie.controlplane.sync.OutboxEvent event) { operation.run(); }
        public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
    }
}
