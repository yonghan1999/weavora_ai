package app.weavora.zhijie.controlplane.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

public final class CargoTestClient {
    private CargoTestClient() {}

    public static Path build(Path workspace, String kind, String name) throws Exception {
        var target = Files.createDirectories(workspace.resolve("target"));
        var output = Files.createTempFile(target, name + "-artifacts-", ".jsonl");
        var diagnostics = Files.createTempFile(target, name + "-build-", ".log");
        var build = new ProcessBuilder("cargo", "build", "--locked", "--offline", "-p", "zhijie-core", "--" + kind, name, "--message-format=json")
                .directory(workspace.toFile()).redirectOutput(output.toFile()).redirectError(diagnostics.toFile()).start();
        try {
            assertThat(build.waitFor(300, TimeUnit.SECONDS)).as("Cargo build deadline: %s", diagnostics).isTrue();
            assertThat(build.exitValue()).as("Cargo build: %s", diagnostics).isZero();
        } finally {
            if (build.isAlive()) { build.descendants().forEach(ProcessHandle::destroyForcibly); build.destroyForcibly(); }
        }
        var json = new ObjectMapper();
        var matches = new ArrayList<Path>();
        try (var lines = Files.newBufferedReader(output)) {
            for (String line; (line = lines.readLine()) != null;) {
                var message = json.readTree(line);
                var artifact = message.path("target");
                if ("compiler-artifact".equals(message.path("reason").asText()) && name.equals(artifact.path("name").asText())
                        && kind.equals(artifact.path("kind").path(0).asText()) && message.path("executable").isTextual()) {
                    matches.add(Path.of(message.get("executable").textValue()));
                }
            }
        }
        assertThat(matches).hasSize(1);
        assertThat(matches.getFirst()).isAbsolute().isRegularFile();
        return matches.getFirst();
    }
}
