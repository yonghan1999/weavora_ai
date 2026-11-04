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
    public List<Group> listGroupsForUser(String userId) { return mapper.listGroupsForUser(userId); }
    public void insertGroup(Group group) { mapper.insertGroup(group); }
    public void updateGroup(Group group) { mapper.updateGroup(group); }
    public Optional<GroupMember> findMember(String groupId, String userId) { return mapper.findMember(groupId, userId); }
    public List<GroupMember> listActiveMembers(String groupId) { return mapper.listActiveMembers(groupId); }
    public void insertMember(GroupMember member) { mapper.insertMember(member); }
    public void updateMember(GroupMember member) { mapper.updateMember(member); }
    public void deactivateAllMembers(String groupId, Instant leftAt) { mapper.deactivateAllMembers(groupId, leftAt); }
    public void insertInvitation(GroupInvitation invitation) { mapper.insertInvitation(invitation); }
    public Optional<GroupInvitation> findInvitation(String invitationId) { return mapper.findInvitation(invitationId); }
    public void updateInvitation(GroupInvitation invitation) { mapper.updateInvitation(invitation); }
    public List<GroupInvitation> listPendingInvitationsByInviter(String groupId, String inviterId) {
        return mapper.listPendingInvitationsByInviter(groupId, inviterId);
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
