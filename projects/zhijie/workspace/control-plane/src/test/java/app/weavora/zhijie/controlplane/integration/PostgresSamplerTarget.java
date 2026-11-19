package app.weavora.zhijie.controlplane.integration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.jdbc.core.JdbcTemplate;

final class PostgresSamplerTarget {
    private PostgresSamplerTarget() {}

    static long rootPid(JdbcTemplate sql) throws IOException {
        Path dataDirectory = Path.of(sql.queryForObject("SHOW data_directory", String.class));
        long postmaster = Long.parseLong(Files.readAllLines(dataDirectory.resolve("postmaster.pid")).getFirst().trim());
        long backend = sql.queryForObject("SELECT pg_backend_pid()", Long.class);
        if (ProcessHandle.of(backend).orElseThrow().parent().orElseThrow().pid() != postmaster) {
            throw new IllegalStateException("Postgres sampling root differs from current backend parent");
        }
        return postmaster;
    }
}
