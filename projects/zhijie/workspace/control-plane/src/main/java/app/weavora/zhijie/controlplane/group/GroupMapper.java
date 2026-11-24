package app.weavora.zhijie.controlplane.group;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface GroupMapper {
    Optional<Group> findGroup(String groupId);
    Optional<Group> lockGroup(String groupId);
    List<Group> listGroupsForUser(String userId);
    void insertGroup(Group group);
    void updateGroup(Group group);
    Optional<GroupMember> findMember(@Param("groupId") String groupId, @Param("userId") String userId);
    List<GroupMember> listActiveMembers(String groupId);
    List<GroupMemberView> listVisibleMembers(@Param("groupId") String groupId, @Param("onlineSince") Instant onlineSince);
    void insertMember(GroupMember member);
    void updateMember(GroupMember member);
    void deactivateAllMembers(@Param("groupId") String groupId, @Param("leftAt") Instant leftAt);
    void insertInvitation(GroupInvitation invitation);
    Optional<GroupInvitation> findInvitation(String invitationId);
    List<PendingGroupInvitation> listIncomingInvitations(@Param("userId") String userId, @Param("beforeId") String beforeId);
    List<PendingGroupInvitation> listGroupInvitations(@Param("groupId") String groupId, @Param("beforeId") String beforeId);
    Optional<GroupInvitation> findPendingInvitation(@Param("groupId") String groupId, @Param("inviteeId") String inviteeId);
    void updateInvitation(GroupInvitation invitation);
    void expireAcceptedInvitations(@Param("groupId") String groupId, @Param("inviteeId") String inviteeId, @Param("invalidatedAt") Instant invalidatedAt);
    List<GroupInvitation> pendingInvalidatedByDeparture(@Param("groupId") String groupId, @Param("userId") String userId);
    void invalidateAllPendingInvitations(@Param("groupId") String groupId, @Param("invalidatedAt") Instant invalidatedAt);
    Optional<OwnerTransfer> findPendingTransfer(String groupId);
    Optional<OwnerTransfer> findPendingTransferById(String transferId);
    void insertTransfer(OwnerTransfer transfer);
    void updateTransfer(OwnerTransfer transfer);
    void cancelPendingTransfers(@Param("groupId") String groupId, @Param("completedAt") Instant completedAt);
    void insertRecoveryRequest(OwnerRecoveryRequest request);
}
