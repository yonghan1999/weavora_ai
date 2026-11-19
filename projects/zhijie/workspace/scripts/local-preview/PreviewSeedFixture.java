import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.file.Files;
import java.nio.file.Path;
import org.flywaydb.core.Flyway;

class PreviewSeedFixture {
    public static void main(String[] args) throws Exception {
        var fixture = Path.of("").toRealPath();
        if (!fixture.getFileName().toString().startsWith("preview-test-") || !fixture.getParent().getFileName().toString().equals("target")) {
            throw new IllegalStateException("Only a dedicated preview test directory is allowed");
        }
        var data = fixture.resolve("target/local-preview/postgres");
        boolean prepare = args[0].equals("prepare");
        if (prepare ? Files.exists(data) : !args[0].equals("verify") || !Files.exists(data.resolve("PG_VERSION"))) {
            throw new IllegalStateException("Unexpected test data directory");
        }
        Files.createDirectories(data.getParent());
        try (var postgres = EmbeddedPostgres.builder().setDataDirectory(data).setCleanDataDirectory(false)
                .setPort(Integer.parseInt(args[1])).setServerConfig("listen_addresses", "127.0.0.1").start()) {
            if (prepare) Flyway.configure().dataSource(postgres.getJdbcUrl("postgres", "postgres"), "postgres", "").load().migrate();
            try (var connection = postgres.getPostgresDatabase().getConnection(); var statement = connection.createStatement()) {
                if (prepare) {
                    statement.execute("ALTER TABLE messages ADD CONSTRAINT preview_test_reject_seed CHECK (false)");
                } else {
                    try (var result = statement.executeQuery("SELECT (SELECT count(*) FROM users) + (SELECT count(*) FROM groups) + "
                            + "(SELECT count(*) FROM rooms) + (SELECT count(*) FROM activities) + (SELECT count(*) FROM messages)")) {
                        if (!result.next() || result.getLong(1) != 0) throw new IllegalStateException("Partial preview seed persisted");
                    }
                    statement.execute("ALTER TABLE messages DROP CONSTRAINT preview_test_reject_seed");
                }
            }
        }
    }
}
