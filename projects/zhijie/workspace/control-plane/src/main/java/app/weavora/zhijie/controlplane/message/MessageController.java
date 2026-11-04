package app.weavora.zhijie.controlplane.message;

import app.weavora.zhijie.controlplane.identity.IdentityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class MessageController {
    private final MessageService messages;
    private final IdentityService identity;

    public MessageController(MessageService messages, IdentityService identity) {
        this.messages = messages;
        this.identity = identity;
    }

    @PostMapping("/groups/{groupId}/messages")
    public Map<String, MessageView> post(@RequestHeader("Authorization") String auth, @PathVariable String groupId,
                                         @Valid @RequestBody MessageBody body) {
        return Map.of("data", messages.post(actor(auth), groupId, body.body()));
    }

    @GetMapping("/groups/{groupId}/messages")
    public Map<String, List<MessageView>> list(@RequestHeader("Authorization") String auth, @PathVariable String groupId,
                                               @RequestParam(required = false) Instant before,
                                               @RequestParam(defaultValue = "50") int limit) {
        return Map.of("data", messages.list(actor(auth), groupId, before, limit));
    }

    @DeleteMapping("/messages/{messageId}")
    public Map<String, MessageView> delete(@RequestHeader("Authorization") String auth, @PathVariable String messageId) {
        return Map.of("data", messages.delete(actor(auth), messageId, UUID.randomUUID().toString()));
    }

    private String actor(String authorization) { return identity.authenticate(authorization).id(); }

    public record MessageBody(@NotBlank @Size(max = 4000) String body) {
    }
}
