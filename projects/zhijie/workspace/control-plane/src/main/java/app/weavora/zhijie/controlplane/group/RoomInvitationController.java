package app.weavora.zhijie.controlplane.group;

import app.weavora.zhijie.controlplane.identity.IdentityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public record RoomInvitationController(IdentityService identity, RoomInvitationService invitations) {
    record Recipient(@NotBlank @Email @Size(max=254) String email) {}
    record Code(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String code) {}

    @PostMapping("/rooms/{roomId}/invitations")
    public Map<String, ?> issue(@RequestHeader("Authorization") String auth, @PathVariable String roomId, @Valid @RequestBody Recipient body) {
        return Map.of("data", Map.of("code", invitations.issue(identity.authenticate(auth).id(), roomId, body.email())));
    }
    @PostMapping("/invitations/redeem-room")
    public Map<String, ?> redeem(@RequestHeader("Authorization") String auth, @Valid @RequestBody Code body) {
        return Map.of("data", Map.of("roomId", invitations.redeem(identity.authenticate(auth).id(), body.code())));
    }
}
