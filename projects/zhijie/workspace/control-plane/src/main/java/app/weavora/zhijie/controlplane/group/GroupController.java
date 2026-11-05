package app.weavora.zhijie.controlplane.group;

import app.weavora.zhijie.controlplane.identity.IdentityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class GroupController {
    private final GroupService groups;
    private final IdentityService identity;

    public GroupController(GroupService groups, IdentityService identity) {
        this.groups = groups;
        this.identity = identity;
    }

    @PostMapping("/groups")
    public Map<String, Group> create(@RequestHeader("Authorization") String auth, @Valid @RequestBody GroupBody body) {
        return Map.of("data", groups.createGroup(actor(auth), body.name(), body.avatar(), body.description()));
    }

    @GetMapping("/groups")
    public Map<String, List<Group>> list(@RequestHeader("Authorization") String auth) {
        return Map.of("data", groups.listGroups(actor(auth)));
    }

    @GetMapping("/groups/{groupId}")
    public Map<String, Group> get(@RequestHeader("Authorization") String auth, @PathVariable String groupId) {
        return Map.of("data", groups.getGroup(actor(auth), groupId));
    }

    @PatchMapping("/groups/{groupId}")
    public Map<String, Group> update(@RequestHeader("Authorization") String auth, @PathVariable String groupId,
                                     @Valid @RequestBody GroupBody body) {
        return Map.of("data", groups.updateGroup(actor(auth), groupId, body.name(), body.avatar(), body.description()));
    }

    @GetMapping("/groups/{groupId}/members")
    public Map<String, List<GroupMember>> members(@RequestHeader("Authorization") String auth, @PathVariable String groupId) {
        return Map.of("data", groups.listMembers(actor(auth), groupId));
    }

    @PostMapping("/groups/{groupId}/invitations")
    public Map<String, GroupInvitation> invite(@RequestHeader("Authorization") String auth, @PathVariable String groupId,
                                               @Valid @RequestBody InvitationBody body) {
        return Map.of("data", groups.invite(actor(auth), groupId, body.inviteeId()));
    }

    @DeleteMapping("/groups/{groupId}/invitations/{invitationId}")
    public Map<String, GroupInvitation> revoke(@RequestHeader("Authorization") String auth, @PathVariable String groupId,
                                               @PathVariable String invitationId) {
        return Map.of("data", groups.revokeInvitation(actor(auth), groupId, invitationId));
    }

    @PostMapping("/invitations/{invitationId}/accept")
    public Map<String, GroupInvitation> accept(@RequestHeader("Authorization") String auth, @PathVariable String invitationId) {
        return Map.of("data", groups.acceptInvitation(actor(auth), invitationId));
    }

    @PatchMapping("/groups/{groupId}/members/{userId}")
    public Map<String, Object> changeRole(@RequestHeader("Authorization") String auth, @PathVariable String groupId,
                                          @PathVariable String userId, @Valid @RequestBody RoleBody body) {
        groups.changeRole(actor(auth), groupId, userId, body.role());
        return Map.of("data", Map.of("changed", true));
    }

    @DeleteMapping("/groups/{groupId}/members/{userId}")
    public Map<String, Object> remove(@RequestHeader("Authorization") String auth, @PathVariable String groupId,
                                      @PathVariable String userId) {
        groups.removeMember(actor(auth), groupId, userId);
        return Map.of("data", Map.of("removed", true));
    }

    @PostMapping("/groups/{groupId}/leave")
    public Map<String, Object> leave(@RequestHeader("Authorization") String auth, @PathVariable String groupId) {
        groups.leaveGroup(actor(auth), groupId);
        return Map.of("data", Map.of("left", true));
    }

    @PostMapping("/groups/{groupId}/owner-transfers")
    public Map<String, OwnerTransfer> transfer(@RequestHeader("Authorization") String auth, @PathVariable String groupId,
                                               @Valid @RequestBody TransferBody body) {
        return Map.of("data", groups.initiateTransfer(actor(auth), groupId, body.targetUserId()));
    }

    @PostMapping("/owner-transfers/{transferId}/accept")
    public Map<String, OwnerTransfer> acceptTransfer(@RequestHeader("Authorization") String auth, @PathVariable String transferId) {
        return Map.of("data", groups.acceptTransfer(actor(auth), transferId));
    }

    @PostMapping("/owner-transfers/{transferId}/cancel")
    public Map<String, Object> cancelTransfer(@RequestHeader("Authorization") String auth, @PathVariable String transferId) {
        groups.cancelTransfer(actor(auth), transferId);
        return Map.of("data", Map.of("cancelled", true));
    }

    @PostMapping("/groups/{groupId}/owner-recovery-requests")
    public Map<String, OwnerRecoveryRequest> recover(@RequestHeader("Authorization") String auth, @PathVariable String groupId,
                                                     @Valid @RequestBody RecoveryBody body) {
        return Map.of("data", groups.requestRecovery(actor(auth), groupId, body.evidenceRef()));
    }

    @PostMapping("/groups/{groupId}/dissolve")
    public Map<String, Object> dissolve(@RequestHeader("Authorization") String auth, @PathVariable String groupId) {
        groups.dissolveGroup(actor(auth), groupId);
        return Map.of("data", Map.of("dissolved", true));
    }

    private String actor(String authorization) {
        return identity.authenticate(authorization).id();
    }

    public record GroupBody(@NotBlank String name, String avatar, String description) {
    }
    public record InvitationBody(@NotBlank String inviteeId) {
    }
    public record RoleBody(@NotNull GroupRole role) {
    }
    public record TransferBody(@NotBlank String targetUserId) {
    }
    public record RecoveryBody(@NotBlank String evidenceRef) {
    }
}
