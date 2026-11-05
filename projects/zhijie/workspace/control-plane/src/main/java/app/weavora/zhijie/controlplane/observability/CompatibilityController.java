package app.weavora.zhijie.controlplane.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CompatibilityController {
    private final ObjectMapper objectMapper;

    public CompatibilityController(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @GetMapping("/api/v1/compatibility-matrix")
    public Map<String, Object> matrix() {
        try {
            Map<String, Object> matrix = objectMapper.readValue(Path.of("release/support-matrix.json").toFile(), Map.class);
            return Map.of("data", matrix);
        } catch (Exception exception) {
            return Map.of("data", Map.of(
                    "status", "unverified",
                    "platforms", List.of("windows", "macos", "linux"),
                    "note", "支持矩阵文件不可用；所有平台保持未验证"
            ));
        }
    }
}
