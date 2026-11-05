package app.weavora.zhijie.controlplane.social;

import app.weavora.zhijie.controlplane.identity.IdentityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
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
public class SocialController {
    private final SocialService socialService;
    private final IdentityService identityService;

    public SocialController(SocialService socialService, IdentityService identityService) {
        this.socialService = socialService;
        this.identityService = identityService;
    }

    @GetMapping("/users/search")
    public Map<String, List<UserSearchView>> search(
            @RequestHeader("Authorization") String authorization, @RequestParam String query
    ) {
        String actorId = identityService.authenticate(authorization).id();
        return Map.of("data", socialService.search(actorId, query));
    }

    @GetMapping("/friends")
    public Map<String, List<FriendView>> friends(@RequestHeader("Authorization") String authorization) {
        String actorId = identityService.authenticate(authorization).id();
        return Map.of("data", socialService.listFriends(actorId));
    }

    @PostMapping("/friend-requests")
    public Map<String, FriendRequest> sendRequest(
            @RequestHeader("Authorization") String authorization, @Valid @RequestBody FriendRequestBody request
    ) {
        String actorId = identityService.authenticate(authorization).id();
        return Map.of("data", socialService.sendRequest(actorId, request.addresseeId()));
    }

    @PostMapping("/friend-requests/{requestId}/accept")
    public Map<String, FriendRequest> accept(
            @RequestHeader("Authorization") String authorization, @PathVariable String requestId
    ) {
        String actorId = identityService.authenticate(authorization).id();
        return Map.of("data", socialService.accept(actorId, requestId));
    }

    @PostMapping("/friend-requests/{requestId}/reject")
    public Map<String, FriendRequest> reject(
            @RequestHeader("Authorization") String authorization, @PathVariable String requestId
    ) {
        String actorId = identityService.authenticate(authorization).id();
        return Map.of("data", socialService.reject(actorId, requestId));
    }

    @DeleteMapping("/friends/{friendId}")
    public Map<String, Object> delete(
            @RequestHeader("Authorization") String authorization, @PathVariable String friendId
    ) {
        String actorId = identityService.authenticate(authorization).id();
        socialService.deleteFriend(actorId, friendId);
        return Map.of("data", Map.of("deleted", true));
    }

    @PostMapping("/presence/heartbeat")
    public Map<String, Object> heartbeat(@RequestHeader("Authorization") String authorization) {
        String actorId = identityService.authenticate(authorization).id();
        socialService.heartbeat(actorId);
        return Map.of("data", Map.of("recorded", true));
    }

    public record FriendRequestBody(@NotBlank String addresseeId) {
    }
}
