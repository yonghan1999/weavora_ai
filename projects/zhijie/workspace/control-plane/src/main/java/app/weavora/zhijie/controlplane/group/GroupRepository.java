package app.weavora.zhijie.controlplane.group;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface GroupRepository {
    Optional<Group> findGroup(String groupId);
    List<Group> listGroupsForUser(String userId);
    void insertGroup(Group group);
    void updateGroup(Group group);
    Optional<GroupMember> findMember(String groupId, String userId);
    List<GroupMember> listActiveMembers(String groupId);
    void insertMember(GroupMember member);
    void updateMember(GroupMember member);
    void deactivateAllMembers(String groupId, Instant leftAt);
    void insertInvitation(GroupInvitation invitation);
    Optional<GroupInvitation> findInvitation(String invitationId);
    void updateInvitation(GroupInvitation invitation);
    List<GroupInvitation> listPendingInvitationsByInviter(String groupId, String inviterId);
    void invalidateAllPendingInvitations(String groupId, Instant invalidatedAt);
    Optional<OwnerTransfer> findPendingTransfer(String groupId);
    Optional<OwnerTransfer> findPendingTransferById(String transferId);
    void insertTransfer(OwnerTransfer transfer);
    void updateTransfer(OwnerTransfer transfer);
    void cancelPendingTransfers(String groupId, Instant completedAt);
    void insertRecoveryRequest(OwnerRecoveryRequest request);
}
