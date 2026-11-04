package app.weavora.zhijie.controlplane.group;

import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.identity.IdentityService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GroupService {
    private final GroupRepository repository;
    private final IdentityService identityService;
    private final Clock clock;

    public GroupService(GroupRepository repository, IdentityService identityService, Clock clock) {
        this.repository = repository;
        this.identityService = identityService;
        this.clock = clock;
    }

    @Transactional
    public Group createGroup(String actorId, String name, String avatar, String description) {
        identityService.requireActive(actorId);
        requireName(name);
        Group group = new Group(UUID.randomUUID().toString(), name.trim(), avatar, description == null ? "" : description,
                actorId, GroupStatus.ACTIVE, clock.instant(), null);
        repository.insertGroup(group);
        repository.insertMember(new GroupMember(group.id(), actorId, GroupRole.OWNER, MemberStatus.ACTIVE, group.createdAt(), null));
        return group;
    }

    public List<Group> listGroups(String actorId) {
        identityService.requireActive(actorId);
        return repository.listGroupsForUser(actorId);
    }

    public Group getGroup(String actorId, String groupId) {
        requireMembership(groupId, actorId);
        return activeGroup(groupId);
    }

    public List<GroupMember> listMembers(String actorId, String groupId) {
        requireMembership(groupId, actorId);
        return repository.listActiveMembers(groupId);
    }

    @Transactional
    public Group updateGroup(String actorId, String groupId, String name, String avatar, String description) {
        requireRole(groupId, actorId, GroupRole.OWNER, GroupRole.ADMIN);
        requireName(name);
        Group group = activeGroup(groupId);
        Group updated = new Group(group.id(), name.trim(), avatar, description == null ? "" : description,
                group.ownerUserId(), group.status(), group.createdAt(), group.dissolvedAt());
        repository.updateGroup(updated);
        return updated;
    }

    @Transactional
    public GroupInvitation invite(String actorId, String groupId, String inviteeId) {
        requireMembership(groupId, actorId);
        identityService.requireActive(inviteeId);
        GroupMember existing = repository.findMember(groupId, inviteeId).orElse(null);
        if (existing != null && existing.status() == MemberStatus.ACTIVE) {
            throw conflict("GROUP_MEMBER_EXISTS", "被邀请者已是群成员");
        }
        GroupInvitation invitation = new GroupInvitation(UUID.randomUUID().toString(), groupId, actorId, inviteeId,
                InvitationStatus.PENDING, clock.instant(), null, null, null);
        repository.insertInvitation(invitation);
        return invitation;
    }

    @Transactional
    public GroupInvitation revokeInvitation(String actorId, String groupId, String invitationId) {
        requireRole(groupId, actorId, GroupRole.OWNER, GroupRole.ADMIN);
        GroupInvitation invitation = repository.findInvitation(invitationId).orElseThrow(this::invitationUnavailable);
        if (!invitation.groupId().equals(groupId) || invitation.status() != InvitationStatus.PENDING) {
            throw invitationUnavailable();
        }
        GroupInvitation revoked = new GroupInvitation(invitation.id(), invitation.groupId(), invitation.inviterId(),
                invitation.inviteeId(), InvitationStatus.REVOKED, invitation.createdAt(), invitation.acceptedAt(),
                clock.instant(), invitation.invalidatedAt());
        repository.updateInvitation(revoked);
        return revoked;
    }

    @Transactional
    public GroupInvitation acceptInvitation(String actorId, String invitationId) {
        identityService.requireActive(actorId);
        GroupInvitation invitation = repository.findInvitation(invitationId).orElseThrow(this::invitationUnavailable);
        if (!invitation.inviteeId().equals(actorId)) throw invitationUnavailable();
        activeGroup(invitation.groupId());
        if (invitation.status() == InvitationStatus.ACCEPTED) return invitation;
        if (invitation.status() != InvitationStatus.PENDING) throw invitationUnavailable();
        requireMembership(invitation.groupId(), invitation.inviterId());
        Instant now = clock.instant();
        GroupMember existing = repository.findMember(invitation.groupId(), actorId).orElse(null);
        if (existing == null || existing.status() != MemberStatus.ACTIVE) {
            repository.insertMember(new GroupMember(invitation.groupId(), actorId, GroupRole.MEMBER,
                    MemberStatus.ACTIVE, now, null));
        }
        GroupInvitation accepted = new GroupInvitation(invitation.id(), invitation.groupId(), invitation.inviterId(),
                actorId, InvitationStatus.ACCEPTED, invitation.createdAt(), now, invitation.revokedAt(), invitation.invalidatedAt());
        repository.updateInvitation(accepted);
        return accepted;
    }

    @Transactional
    public void changeRole(String actorId, String groupId, String targetUserId, GroupRole newRole) {
        requireRole(groupId, actorId, GroupRole.OWNER);
        if (newRole == GroupRole.OWNER || targetUserId.equals(actorId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ROLE_CHANGE_NOT_ALLOWED", "请使用群主转让流程", false);
        }
        GroupMember target = requireMembership(groupId, targetUserId);
        repository.updateMember(new GroupMember(target.groupId(), target.userId(), newRole, target.status(), target.joinedAt(), null));
    }

    @Transactional
    public void removeMember(String actorId, String groupId, String targetUserId) {
        GroupMember actor = requireMembership(groupId, actorId);
        GroupMember target = requireMembership(groupId, targetUserId);
        if (target.role() == GroupRole.OWNER || actorId.equals(targetUserId)
                || actor.role() == GroupRole.MEMBER || (actor.role() == GroupRole.ADMIN && target.role() != GroupRole.MEMBER)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CURRENT_GRANT_REQUIRED", "当前角色不能移除该成员", false);
        }
        deactivateMember(target, MemberStatus.REMOVED);
    }

    @Transactional
    public void leaveGroup(String actorId, String groupId) {
        GroupMember member = requireMembership(groupId, actorId);
        if (member.role() == GroupRole.OWNER) {
            throw conflict("OWNER_MUST_TRANSFER_OR_DISSOLVE", "群主退出前必须完成转让或解散");
        }
        deactivateMember(member, MemberStatus.LEFT);
    }

    @Transactional
    public OwnerTransfer initiateTransfer(String actorId, String groupId, String targetUserId) {
        requireRole(groupId, actorId, GroupRole.OWNER);
        requireMembership(groupId, targetUserId);
        if (actorId.equals(targetUserId)) throw conflict("OWNER_TRANSFER_INVALID", "不能转让给自己");
        repository.findPendingTransfer(groupId).ifPresent(existing -> {
            throw conflict("OWNER_TRANSFER_PENDING", "已有待接受的转让");
        });
        OwnerTransfer transfer = new OwnerTransfer(UUID.randomUUID().toString(), groupId, actorId, targetUserId,
                GovernanceStatus.PENDING, clock.instant(), null);
        repository.insertTransfer(transfer);
        return transfer;
    }

    @Transactional
    public OwnerTransfer acceptTransfer(String actorId, String transferId) {
        OwnerTransfer transfer = findPendingTransferById(transferId);
        if (!transfer.toUserId().equals(actorId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CURRENT_GRANT_REQUIRED", "只有指定成员可接受转让", false);
        }
        GroupMember previousOwner = requireMembership(transfer.groupId(), transfer.fromUserId());
        GroupMember newOwner = requireMembership(transfer.groupId(), actorId);
        Group group = activeGroup(transfer.groupId());
        if (previousOwner.role() != GroupRole.OWNER || !group.ownerUserId().equals(previousOwner.userId())) {
            throw conflict("OWNER_TRANSFER_INVALID", "原群主状态已变化");
        }
        Instant now = clock.instant();
        repository.updateMember(new GroupMember(previousOwner.groupId(), previousOwner.userId(), GroupRole.MEMBER,
                MemberStatus.ACTIVE, previousOwner.joinedAt(), null));
        repository.updateMember(new GroupMember(newOwner.groupId(), newOwner.userId(), GroupRole.OWNER,
                MemberStatus.ACTIVE, newOwner.joinedAt(), null));
        repository.updateGroup(new Group(group.id(), group.name(), group.avatar(), group.description(), actorId,
                group.status(), group.createdAt(), group.dissolvedAt()));
        OwnerTransfer accepted = new OwnerTransfer(transfer.id(), transfer.groupId(), transfer.fromUserId(), actorId,
                GovernanceStatus.ACCEPTED, transfer.createdAt(), now);
        repository.updateTransfer(accepted);
        return accepted;
    }

    @Transactional
    public void cancelTransfer(String actorId, String transferId) {
        OwnerTransfer transfer = findPendingTransferById(transferId);
        requireRole(transfer.groupId(), actorId, GroupRole.OWNER);
        repository.updateTransfer(new OwnerTransfer(transfer.id(), transfer.groupId(), transfer.fromUserId(),
                transfer.toUserId(), GovernanceStatus.CANCELLED, transfer.createdAt(), clock.instant()));
    }

    @Transactional
    public OwnerRecoveryRequest requestRecovery(String actorId, String groupId, String evidenceRef) {
        identityService.requireActive(actorId);
        activeGroup(groupId);
        if (evidenceRef == null || evidenceRef.isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "EVIDENCE_REQUIRED", "必须提供人工审核证据引用", false);
        }
        OwnerRecoveryRequest request = new OwnerRecoveryRequest(UUID.randomUUID().toString(), groupId, actorId,
                evidenceRef, GovernanceStatus.PENDING, null, null, clock.instant());
        repository.insertRecoveryRequest(request);
        return request;
    }

    @Transactional
    public void dissolveGroup(String actorId, String groupId) {
        requireRole(groupId, actorId, GroupRole.OWNER);
        Group group = activeGroup(groupId);
        Instant now = clock.instant();
        repository.updateGroup(new Group(group.id(), group.name(), group.avatar(), group.description(), group.ownerUserId(),
                GroupStatus.DISSOLVED, group.createdAt(), now));
        repository.deactivateAllMembers(groupId, now);
        repository.invalidateAllPendingInvitations(groupId, now);
        repository.cancelPendingTransfers(groupId, now);
    }

    public GroupMember requireMembership(String groupId, String userId) {
        identityService.requireActive(userId);
        GroupMember member = repository.findMember(groupId, userId).orElseThrow(this::groupUnavailable);
        if (member.status() != MemberStatus.ACTIVE) throw groupUnavailable();
        activeGroup(groupId);
        return member;
    }

    private GroupMember requireRole(String groupId, String userId, GroupRole... roles) {
        GroupMember member = requireMembership(groupId, userId);
        for (GroupRole role : roles) if (member.role() == role) return member;
        throw new ApiException(HttpStatus.FORBIDDEN, "CURRENT_GRANT_REQUIRED", "当前群角色无权执行此操作", false);
    }

    private Group activeGroup(String groupId) {
        Group group = repository.findGroup(groupId).orElseThrow(this::groupUnavailable);
        if (group.status() != GroupStatus.ACTIVE) throw groupUnavailable();
        return group;
    }

    private void deactivateMember(GroupMember member, MemberStatus status) {
        Instant now = clock.instant();
        repository.updateMember(new GroupMember(member.groupId(), member.userId(), GroupRole.MEMBER, status, member.joinedAt(), now));
        for (GroupInvitation invitation : repository.listPendingInvitationsByInviter(member.groupId(), member.userId())) {
            repository.updateInvitation(new GroupInvitation(invitation.id(), invitation.groupId(), invitation.inviterId(),
                    invitation.inviteeId(), InvitationStatus.INVALIDATED, invitation.createdAt(), invitation.acceptedAt(),
                    invitation.revokedAt(), now));
        }
    }

    private OwnerTransfer findPendingTransferById(String transferId) {
        return repository.findPendingTransferById(transferId).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "OWNER_TRANSFER_NOT_AVAILABLE", "转让不存在或已结束", false));
    }

    private void requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "群名称不能为空", false);
        }
    }

    private ApiException groupUnavailable() {
        return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_AVAILABLE", "群组不可用", false);
    }

    private ApiException invitationUnavailable() {
        return new ApiException(HttpStatus.NOT_FOUND, "INVITATION_NOT_AVAILABLE", "邀请不可用", false);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message, false);
    }
}
