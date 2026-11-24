package app.weavora.zhijie.controlplane.group;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisGroupRepository implements GroupRepository {
    private final GroupMapper mapper;

    public MyBatisGroupRepository(GroupMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<Group> findGroup(String groupId) { return mapper.findGroup(groupId); }
    public Optional<Group> lockGroup(String groupId) { return mapper.lockGroup(groupId); }
    public List<Group> listGroupsForUser(String userId) { return mapper.listGroupsForUser(userId); }
    public void insertGroup(Group group) { mapper.insertGroup(group); }
    public void updateGroup(Group group) { mapper.updateGroup(group); }
    public Optional<GroupMember> findMember(String groupId, String userId) { return mapper.findMember(groupId, userId); }
    public List<GroupMember> listActiveMembers(String groupId) { return mapper.listActiveMembers(groupId); }
    public List<GroupMemberView> listVisibleMembers(String groupId, Instant onlineSince) { return mapper.listVisibleMembers(groupId, onlineSince); }
    public void insertMember(GroupMember member) { mapper.insertMember(member); }
    public void updateMember(GroupMember member) { mapper.updateMember(member); }
    public void deactivateAllMembers(String groupId, Instant leftAt) { mapper.deactivateAllMembers(groupId, leftAt); }
    public void insertInvitation(GroupInvitation invitation) { mapper.insertInvitation(invitation); }
    public Optional<GroupInvitation> findInvitation(String invitationId) { return mapper.findInvitation(invitationId); }
    public List<PendingGroupInvitation> listIncomingInvitations(String userId, String beforeId) { return mapper.listIncomingInvitations(userId, beforeId); }
    public List<PendingGroupInvitation> listGroupInvitations(String groupId, String beforeId) { return mapper.listGroupInvitations(groupId, beforeId); }
    public Optional<GroupInvitation> findPendingInvitation(String groupId, String inviteeId) { return mapper.findPendingInvitation(groupId, inviteeId); }
    public void updateInvitation(GroupInvitation invitation) { mapper.updateInvitation(invitation); }
    public void expireAcceptedInvitations(String groupId, String inviteeId, Instant invalidatedAt) {
        mapper.expireAcceptedInvitations(groupId, inviteeId, invalidatedAt);
    }
    public List<GroupInvitation> pendingInvalidatedByDeparture(String groupId, String userId) {
        return mapper.pendingInvalidatedByDeparture(groupId, userId);
    }
    public void invalidateAllPendingInvitations(String groupId, Instant invalidatedAt) {
        mapper.invalidateAllPendingInvitations(groupId, invalidatedAt);
    }
    public Optional<OwnerTransfer> findPendingTransfer(String groupId) { return mapper.findPendingTransfer(groupId); }
    public Optional<OwnerTransfer> findPendingTransferById(String transferId) { return mapper.findPendingTransferById(transferId); }
    public void insertTransfer(OwnerTransfer transfer) { mapper.insertTransfer(transfer); }
    public void updateTransfer(OwnerTransfer transfer) { mapper.updateTransfer(transfer); }
    public void cancelPendingTransfers(String groupId, Instant completedAt) { mapper.cancelPendingTransfers(groupId, completedAt); }
    public void insertRecoveryRequest(OwnerRecoveryRequest request) { mapper.insertRecoveryRequest(request); }
}
