package app.weavora.zhijie.controlplane.message;

import static org.assertj.core.api.Assertions.assertThat;

import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.group.GroupService;
import app.weavora.zhijie.controlplane.identity.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

class MessageHistoryPaginationIntegrationTest {
    @Test
    void pagesRemainCompleteAcrossEqualTimesAndDeletedCursor() throws Exception {
        try (var postgres = EmbeddedPostgres.start(); var context = new SpringApplication(ControlPlaneApplication.class).run(
                "--server.address=127.0.0.1", "--server.port=0", "--zhijie.scheduling.enabled=false",
                "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=")) {
            String owner = UUID.randomUUID().toString(), email = "page@example.test", password = UUID.randomUUID().toString();
            var hash = context.getBean(CredentialProtector.class).hashPassword(password);
            context.getBean(IdentityRepository.class).insertUser(new UserAccount(owner, email, AccountStatus.ACTIVE, hash, Instant.now()));
            var token = context.getBean(IdentityService.class).login(email, password, "page-test").accessToken();
            var groups = context.getBean(GroupService.class);
            var group = groups.createGroup(owner, "消息分页", null, "");
            var other = groups.createGroup(owner, "另一个群", null, "");
            var messages = context.getBean(MessageService.class);
            String foreign = messages.post(owner, other.id(), "独立记录").id();
            var expected = new ArrayList<String>();
            for (int index = 0; index < 5; index++) expected.add(messages.post(owner, group.id(), "消息" + index).id());
            var sql = context.getBean(JdbcTemplate.class);
            sql.update("UPDATE messages SET created_at='2026-01-01T00:00:00Z' WHERE group_id=?", group.id());
            expected.sort(java.util.Comparator.reverseOrder());
            var http = HttpClient.newHttpClient();
            var json = context.getBean(ObjectMapper.class);
            String url = "http://127.0.0.1:" + ((ServletWebServerApplicationContext) context).getWebServer().getPort()
                    + "/api/v1/groups/" + group.id() + "/messages?limit=2";
            var seen = new ArrayList<String>();
            String cursor = "";
            for (int page = 0; page < 4; page++) {
                JsonNode rows = getPage(http, json, url + cursor, token);
                assertThat(rows.size()).isEqualTo(page < 2 ? 2 : page == 2 ? 1 : 0);
                for (var row : rows) seen.add(row.get("id").asText());
                if (page == 0) messages.delete(owner, seen.getLast(), "page-test");
                cursor = "&beforeId=" + seen.getLast();
            }
            assertThat(seen).containsExactlyElementsOf(expected);
            assertThat(getPage(http, json, url + "&beforeId=" + foreign, token).size()).isZero();
            assertThat(getPage(http, json, url + "&before=2026-01-01T00:00:00Z", token).size()).isZero();
        }
    }

    private JsonNode getPage(HttpClient http, ObjectMapper json, String url, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token).GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        var rows = json.readTree(response.body()).get("data");
        assertThat(rows.isArray()).isTrue();
        return rows;
    }
}
