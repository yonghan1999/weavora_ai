package app.weavora.zhijie.controlplane.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.identity.AccountStatus;
import app.weavora.zhijie.controlplane.identity.IdentityService;
import app.weavora.zhijie.controlplane.identity.UserAccount;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GroupServiceTest {
    private InMemoryGroupRepository repository;
    private GroupService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryGroupRepository();
        IdentityService identity = mock(IdentityService.class);
        when(identity.requireActive(anyString())).thenAnswer(invocation -> new UserAccount(
                invocation.getArgument(0), invocation.getArgument(0) + "@example.com", AccountStatus.ACTIVE, "hidden", Instant.now()
        ));
        service = new GroupService(repository, identity, Clock.systemUTC());
    }

    @Test
    void membersCanInviteAndAcceptanceIsIdempotentWithoutRestoringOldRole() {
        Group group = service.createGroup("owner", "小队", null, "");
        GroupInvitation first = service.invite("owner", group.id(), "member");

        GroupInvitation accepted = service.acceptInvitation("member", first.id());
        assertThat(accepted.status()).isEqualTo(InvitationStatus.ACCEPTED);
        assertThat(service.acceptInvitation("member", first.id())).isEqualTo(accepted);
        assertThat(service.listMembers("member", group.id())).extracting(GroupMember::userId)
                .containsExactlyInAnyOrder("owner", "member");

        service.removeMember("owner", group.id(), "member");
        GroupInvitation second = service.invite("owner", group.id(), "member");
        service.acceptInvitation("member", second.id());
        assertThat(repository.findMember(group.id(), "member").orElseThrow().role()).isEqualTo(GroupRole.MEMBER);
    }

    @Test
    void invitationRevokeIsAdministrativeAndInviterLeavingInvalidatesOnlyPending() {
        Group group = service.createGroup("owner", "小队", null, "");
        GroupInvitation accepted = service.invite("owner", group.id(), "accepted");
        service.acceptInvitation("accepted", accepted.id());
        GroupInvitation pending = service.invite("accepted", group.id(), "pending");

        assertThatThrownBy(() -> service.revokeInvitation("accepted", group.id(), pending.id()))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("CURRENT_GRANT_REQUIRED");
        service.leaveGroup("accepted", group.id());
        assertThat(repository.findInvitation(pending.id()).orElseThrow().status()).isEqualTo(InvitationStatus.INVALIDATED);
        assertThat(repository.findInvitation(accepted.id()).orElseThrow().status()).isEqualTo(InvitationStatus.ACCEPTED);
        assertThatThrownBy(() -> service.acceptInvitation("pending", pending.id())).isInstanceOf(ApiException.class);
    }

    @Test
    void ownershipChangesOnlyWhenDesignatedMemberAccepts() {
        Group group = service.createGroup("owner", "小队", null, "");
        service.acceptInvitation("member", service.invite("owner", group.id(), "member").id());
        OwnerTransfer transfer = service.initiateTransfer("owner", group.id(), "member");

        assertThat(repository.findGroup(group.id()).orElseThrow().ownerUserId()).isEqualTo("owner");
        assertThatThrownBy(() -> service.leaveGroup("owner", group.id()))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("OWNER_MUST_TRANSFER_OR_DISSOLVE");
        assertThatThrownBy(() -> service.acceptTransfer("owner", transfer.id()))
                .isInstanceOf(ApiException.class);
        service.acceptTransfer("member", transfer.id());

        assertThat(repository.findGroup(group.id()).orElseThrow().ownerUserId()).isEqualTo("member");
        assertThat(repository.findMember(group.id(), "owner").orElseThrow().role()).isEqualTo(GroupRole.MEMBER);
        assertThat(repository.findMember(group.id(), "member").orElseThrow().role()).isEqualTo(GroupRole.OWNER);
        service.leaveGroup("owner", group.id());
    }

    @Test
    void recoveryRequestNeverChangesOwnershipAutomatically() {
        Group group = service.createGroup("owner", "小队", null, "");
        OwnerRecoveryRequest request = service.requestRecovery("member", group.id(), "evidence-reference");

        assertThat(request.status()).isEqualTo(GovernanceStatus.PENDING);
        assertThat(repository.findGroup(group.id()).orElseThrow().ownerUserId()).isEqualTo("owner");
        assertThatThrownBy(() -> service.requestRecovery("member", group.id(), " "))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("EVIDENCE_REQUIRED");
    }

    @Test
    void dissolveMakesGroupAndPendingOperationsUnavailable() {
        Group group = service.createGroup("owner", "小队", null, "");
        GroupInvitation invitation = service.invite("owner", group.id(), "member");
        service.dissolveGroup("owner", group.id());

        assertThat(repository.findGroup(group.id()).orElseThrow().status()).isEqualTo(GroupStatus.DISSOLVED);
        assertThat(repository.findInvitation(invitation.id()).orElseThrow().status()).isEqualTo(InvitationStatus.INVALIDATED);
        assertThatThrownBy(() -> service.acceptInvitation("member", invitation.id())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.getGroup("owner", group.id())).isInstanceOf(ApiException.class);
    }

    private static class InMemoryGroupRepository implements GroupRepository {
        private final Map<String, Group> groups = new HashMap<>();
        private final Map<String, GroupMember> members = new HashMap<>();
        private final Map<String, GroupInvitation> invitations = new HashMap<>();
        private final Map<String, OwnerTransfer> transfers = new HashMap<>();
        private final List<OwnerRecoveryRequest> recoveries = new ArrayList<>();

        private String key(String groupId, String userId) { return groupId + ":" + userId; }
        public Optional<Group> findGroup(String groupId) { return Optional.ofNullable(groups.get(groupId)); }
        public List<Group> listGroupsForUser(String userId) {
            return groups.values().stream().filter(group -> group.status() == GroupStatus.ACTIVE)
                    .filter(group -> findMember(group.id(), userId).filter(m -> m.status() == MemberStatus.ACTIVE).isPresent()).toList();
        }
        public void insertGroup(Group group) { groups.put(group.id(), group); }
        public void updateGroup(Group group) { groups.put(group.id(), group); }
        public Optional<GroupMember> findMember(String groupId, String userId) { return Optional.ofNullable(members.get(key(groupId, userId))); }
        public List<GroupMember> listActiveMembers(String groupId) {
            return members.values().stream().filter(m -> m.groupId().equals(groupId) && m.status() == MemberStatus.ACTIVE).toList();
        }
        public void insertMember(GroupMember member) { members.put(key(member.groupId(), member.userId()), member); }
        public void updateMember(GroupMember member) { insertMember(member); }
        public void deactivateAllMembers(String groupId, Instant leftAt) {
            for (GroupMember member : listActiveMembers(groupId)) updateMember(new GroupMember(groupId, member.userId(), member.role(), MemberStatus.REMOVED, member.joinedAt(), leftAt));
        }
        public void insertInvitation(GroupInvitation invitation) { invitations.put(invitation.id(), invitation); }
        public Optional<GroupInvitation> findInvitation(String invitationId) { return Optional.ofNullable(invitations.get(invitationId)); }
        public void updateInvitation(GroupInvitation invitation) { invitations.put(invitation.id(), invitation); }
        public List<GroupInvitation> listPendingInvitationsByInviter(String groupId, String inviterId) {
            return invitations.values().stream().filter(i -> i.groupId().equals(groupId) && i.inviterId().equals(inviterId) && i.status() == InvitationStatus.PENDING).toList();
        }
        public void invalidateAllPendingInvitations(String groupId, Instant invalidatedAt) {
            invitations.values().stream().filter(i -> i.groupId().equals(groupId) && i.status() == InvitationStatus.PENDING).forEach(i ->
                    updateInvitation(new GroupInvitation(i.id(), i.groupId(), i.inviterId(), i.inviteeId(), InvitationStatus.INVALIDATED, i.createdAt(), i.acceptedAt(), i.revokedAt(), invalidatedAt)));
        }
        public Optional<OwnerTransfer> findPendingTransfer(String groupId) {
            return transfers.values().stream().filter(t -> t.groupId().equals(groupId) && t.status() == GovernanceStatus.PENDING).findFirst();
        }
        public Optional<OwnerTransfer> findPendingTransferById(String transferId) {
            return Optional.ofNullable(transfers.get(transferId)).filter(t -> t.status() == GovernanceStatus.PENDING);
        }
        public void insertTransfer(OwnerTransfer transfer) { transfers.put(transfer.id(), transfer); }
        public void updateTransfer(OwnerTransfer transfer) { transfers.put(transfer.id(), transfer); }
        public void cancelPendingTransfers(String groupId, Instant completedAt) {
            transfers.values().stream().filter(t -> t.groupId().equals(groupId) && t.status() == GovernanceStatus.PENDING).forEach(t ->
                    updateTransfer(new OwnerTransfer(t.id(), t.groupId(), t.fromUserId(), t.toUserId(), GovernanceStatus.CANCELLED, t.createdAt(), completedAt)));
        }
        public void insertRecoveryRequest(OwnerRecoveryRequest request) { recoveries.add(request); }
    }
}
